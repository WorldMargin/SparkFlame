// SPDX-License-Identifier: SFAL-1.0
// Copyright (c) 2026 WorldMargin. See LICENSE.SPARKFLAME.md and NOTICE.

package com.worldmargin.autospark.app.provider

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.worldmargin.autospark.app.config.ConfigStore
import java.util.Calendar

/**
 * 定时自启（零 root / 零权限，多层冗余）：
 *
 * 单一 alarm 在 vivo 上会被电池策略冻结（实测）。这里用三层系统级触发互相兜底：
 *   1. [AlarmManager.setAndAllowWhileIdle] —— 每天定点，精确定时首选
 *   2. [JobScheduler] —— 系统级周期任务，OEM 受 CDD 约束不敢冻结，15 分钟检查一次
 *   3. 开机广播 + App 打开补跑 —— 覆盖重启/停止后的恢复
 *
 * 任一层触发都调用 [fireWake]：调抖音 exported provider 拉起主进程，
 * LSPosed 注入后宿主的 scheduleOnAppStart() 会跑一轮续火花。
 *
 * 节流：JobScheduler 虽每 15 分钟被唤醒，但只有「过了设定时刻 + 今天没拉过」才真拉，
 * 一天最多一次，不打扰用户。
 */
object AutoWakeScheduler {
    const val ACTION_WAKE = "com.worldmargin.autospark.action.AUTO_WAKE"
    const val ACTION_BOOT = "android.intent.action.BOOT_COMPLETED"
    private const val REQ_WAKE = 0xA17E
    private const val JOB_ID = 0xA17F
    private const val JOB_INTERVAL_MS = 15 * 60 * 1000L
    private const val FLAG_FILE = "auto_wake_last_day"

    /** 抖音 exported provider —— 已实测可调起主进程（getType 即可，无需参数）。 */
    private const val DOUYIN_URI = "content://com.ss.android.ugc.aweme.provider"

    /** 按配置重排所有触发层；开关关闭时全部取消。 */
    fun reschedule(context: Context) {
        val config = ConfigStore.load(context)
        if (!config.autoWakeEnabled) {
            cancel(context)
            return
        }
        scheduleAlarm(context, config.autoWakeMinute)
        scheduleJob(context)
    }

    /** 立刻拉起抖音（补跑 / 各层触发共用）。 */
    fun wakeDouyinNow(context: Context) {
        runCatching { context.contentResolver.getType(Uri.parse(DOUYIN_URI)) }
    }

    /** 实际触发逻辑：节流检查后拉抖音。供 alarm/job 共用。 */
    private fun fireWake(context: Context) {
        val config = ConfigStore.load(context)
        if (!config.autoWakeEnabled) return
        val now = Calendar.getInstance()
        val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        // 只在过了设定时刻后才拉（alarm 准点触发时刚好满足；job 靠这个判断）
        if (currentMinute < config.autoWakeMinute) return
        val today = todayKey()
        val lastDay = runCatching { java.io.File(context.filesDir, FLAG_FILE).readText().trim() }.getOrDefault("")
        if (lastDay == today) return // 今天已拉过，节流
        runCatching { java.io.File(context.filesDir, FLAG_FILE).writeText(today) }
        wakeDouyinNow(context)
        runCatching {
            java.io.File(context.filesDir, "auto_wake_last.txt").writeText(
                "fired at=${System.currentTimeMillis()} via trigger",
            )
        }
    }

    private fun todayKey(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())

    private fun scheduleAlarm(
        context: Context,
        minuteOfDay: Int,
    ) {
        val now = Calendar.getInstance()
        val due = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (due.timeInMillis <= now.timeInMillis) due.add(Calendar.DAY_OF_YEAR, 1)
        val pi = wakePendingIntent(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due.timeInMillis, pi)
    }

    /**
     * JobScheduler 层：15 分钟周期的系统级任务。
     * OEM 受 Android CDD 约束必须履行 JobScheduler 注册（否则联系人/日历同步也会坏），
     * 因此比裸 alarm 更难被冻结。
     */
    private fun scheduleJob(context: Context) {
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val job =
            JobInfo
                .Builder(JOB_ID, ComponentName(context, WakeJobService::class.java))
                .setPeriodic(JOB_INTERVAL_MS)
                .setPersisted(true) // 重启后系统自动恢复 job
                .build()
        runCatching { js.schedule(job) }
    }

    private fun wakePendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            REQ_WAKE,
            Intent(context, WakeReceiver::class.java).setAction(ACTION_WAKE),
            flags,
        )
    }

    private fun cancel(context: Context) {
        runCatching {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(wakePendingIntent(context))
        }
        runCatching {
            (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler)?.cancel(JOB_ID)
        }
    }

    /** alarm 到点：直接拉（alarm 已保证定点，不必再节流），重排下一次。 */
    class WakeReceiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context?,
            intent: Intent?,
        ) {
            if (context == null || intent?.action != ACTION_WAKE) return
            val app = context.applicationContext
            if (ConfigStore.load(app).autoWakeEnabled) wakeDouyinNow(app)
            reschedule(app)
        }
    }

    /** JobScheduler 周期触发：走节流逻辑，一天最多拉一次。 */
    class WakeJobService : JobService() {
        override fun onStartJob(params: JobParameters?): Boolean {
            fireWake(applicationContext)
            jobFinished(params, false)
            return false
        }

        override fun onStopJob(params: JobParameters?): Boolean = false
    }

    /** 开机后重排（普通权限 RECEIVE_BOOT_COMPLETED）。 */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context?,
            intent: Intent?,
        ) {
            if (context == null || intent?.action != ACTION_BOOT) return
            reschedule(context)
        }
    }
}
