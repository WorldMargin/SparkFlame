// SPDX-License-Identifier: SFAL-1.0
// Copyright (c) 2026 WorldMargin. See LICENSE.SPARKFLAME.md and NOTICE.

package com.worldmargin.autospark.app.provider

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时自启的独立日志（模块 App 私有目录，与 spark_config.json 分开）。
 *
 * 记录三类事实，供「设置 → 自启日志」展示：
 *   - 定时任务何时被触发（alarm / job / 开机 / 手动补跑）
 *   - 拉起抖音的动作是否执行、抖音是否在 30 秒内真的响应（hostAliveAt 更新）
 *   - 抖音这次被拉起后活了多久（下次触发或 App 打开时结算）
 *
 * 只保留最近 [KEEP_DAYS] 天的记录；每次读写都顺手清理过期项，无需定时器。
 */
object WakeLog {
    private const val FILE_NAME = "wake_log.json"
    private const val KEEP_DAYS = 2L
    private const val KEEP_MS = KEEP_DAYS * 24 * 60 * 60 * 1000L

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** 一条自启记录。aliveMs = -1 表示尚在存活 / 未结算。 */
    data class Entry(
        val at: Long,
        val source: String,
        val launched: Boolean,
        val responded: Boolean?,
        val responseMs: Long?,
        val aliveMs: Long,
    ) {
        val timeText: String get() = fmt.format(Date(at))
        val respondedText: String
            get() =
                when (responded) {
                    null -> "待观察"
                    true -> "已响应"
                    false -> "未响应"
                }
        val aliveText: String
            get() =
                when {
                    aliveMs < 0 -> "进行中"
                    aliveMs == 0L -> "—"
                    aliveMs < 60_000 -> "${aliveMs / 1000} 秒"
                    aliveMs < 3_600_000 -> "${aliveMs / 60_000} 分 ${(aliveMs % 60_000) / 1000} 秒"
                    else -> "${aliveMs / 3_600_000} 小时 ${(aliveMs % 3_600_000) / 60_000} 分"
                }
    }

    @Synchronized
    fun read(context: Context): List<Entry> {
        val f = file(context)
        val arr =
            runCatching {
                val text = f.readText()
                if (text.isBlank()) JSONArray() else JSONArray(text)
            }.getOrDefault(JSONArray())
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("at", 0L) >= cutoff) kept.put(o)
        }
        if (kept.length() != arr.length()) runCatching { f.writeText(kept.toString()) }
        // 最新的排在最前，UI 直接顺序展示即可
        return (0 until kept.length()).mapNotNull { i -> kept.optJSONObject(i)?.toEntry() }.asReversed()
    }

    /**
     * 追加一条触发记录。
     * 结算上一条「待观察」记录的存活时长：用它的触发时刻到本次触发时刻的间隔，
     * 但这不等于真实存活（抖音可能在中间死了又被手动打开）——所以额外接收 [hostAliveAt]
     * 判断上一次的会话是否延续到这次。
     */
    @Synchronized
    fun addTrigger(
        context: Context,
        source: String,
        launched: Boolean,
        hostAliveAt: Long,
    ) {
        val arr = readArray(context)
        val now = System.currentTimeMillis()
        // 结算上一条：若它的 at 之后 hostAliveAt 仍在更新，说明抖音至少活到了 hostAliveAt
        val last = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.lastOrNull()
        if (last != null && last.optInt("responded", -2) == -2) {
            val lastAt = last.optLong("at", 0L)
            if (hostAliveAt > lastAt) {
                // 触发之后出现过心跳 → 抖音确实起来了，活到 hostAliveAt
                last.put("responded", 1)
                last.put("aliveMs", hostAliveAt - lastAt)
            } else {
                // 心跳时间戳还停在触发之前 → 这次拉起没成功
                last.put("responded", 0)
                last.put("aliveMs", 0L)
            }
        }
        arr.put(
            JSONObject()
                .put("at", now)
                .put("source", source)
                .put("launched", launched)
                .put("responded", -2) // -2 = 待观察
                .put("responseMs", JSONObject.NULL)
                .put("aliveMs", -1L),
        )
        write(context, arr)
    }

    /** 抖音在响应窗内真的回写了 hostAliveAt —— 标记最近一条为「已响应」并记响应耗时。 */
    @Synchronized
    fun markResponded(
        context: Context,
        hostAliveAt: Long,
    ) {
        val arr = readArray(context)
        val last = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.lastOrNull() ?: return
        if (last.optInt("responded", 0) != -2) return // 已结算过
        val at = last.optLong("at", 0L)
        val delay = hostAliveAt - at
        // 抖音心跳每 15 秒一次，且 JobScheduler 触发本身可能延迟；
        // 只要触发之后出现过心跳就视为「真的拉起来了」。上限放宽到 5 分钟，
        // 超过则说明这次拉起后抖音根本没响应（那条心跳是更早的残留）。
        if (delay in 0..(5 * 60 * 1000L)) {
            last.put("responded", 1)
            last.put("responseMs", delay)
        }
        write(context, arr)
    }

    /**
     * 读取当前 hostAliveAt 结算最近一条：响应窗内则标「已响应」，
     * 并把存活时长结算到「最后一次心跳」——抖音死了心跳就停，aliveAt 不再增长，
     * 于是 aliveAt - 触发时刻就是这次被拉起后的实际存活时长。
     */
    @Synchronized
    fun reconcile(context: Context) {
        val config = com.worldmargin.autospark.app.config.ConfigClient.read(context)
        val alive = config?.hostAliveAt ?: 0L
        if (alive <= 0L) return
        markResponded(context, alive)
        // 结算最近一条的存活时长（若尚未结算）
        val arr = readArray(context)
        val last = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.lastOrNull() ?: return
        if (last.optLong("aliveMs", -1L) < 0L) {
            val at = last.optLong("at", 0L)
            if (alive > at) last.put("aliveMs", alive - at)
        }
        write(context, arr)
    }

    private fun readArray(context: Context): JSONArray {
        val f = file(context)
        val arr =
            runCatching {
                val text = f.readText()
                if (text.isBlank()) JSONArray() else JSONArray(text)
            }.getOrDefault(JSONArray())
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("at", 0L) >= cutoff) kept.put(o)
        }
        return kept
    }

    private fun write(
        context: Context,
        arr: JSONArray,
    ) {
        runCatching { file(context).writeText(arr.toString()) }
    }

    private fun JSONObject.toEntry(): Entry? {
        val respondedRaw = optInt("responded", -2)
        val responded =
            when (respondedRaw) {
                1 -> true
                0 -> false
                else -> null
            }
        val responseRaw = opt("responseMs")
        val responseMs =
            if (responseRaw == null || responseRaw === JSONObject.NULL) null else optLong("responseMs")
        return Entry(
            at = optLong("at", 0L),
            source = optString("source", "?"),
            launched = optBoolean("launched", false),
            responded = responded,
            responseMs = responseMs,
            aliveMs = optLong("aliveMs", -1L),
        )
    }
}
