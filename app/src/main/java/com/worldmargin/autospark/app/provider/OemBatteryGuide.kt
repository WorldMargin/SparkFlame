// SPDX-License-Identifier: SFAL-1.0
// Copyright (c) 2026 WorldMargin. See LICENSE.SPARKFLAME.md and NOTICE.

package com.worldmargin.autospark.app.provider

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 国产 ROM 后台保活引导：定时自启在 vivo/小米/华为等机型上会被电池策略冻结，
 * 必须让用户手动把本 App 加进「自启动 / 电池不优化」白名单。
 *
 * 组件名随 ROM 版本变化，这里对每个品牌维护多个候选，逐个 [resolveActivity] 探测，
 * 都失败则降级到系统「应用详情」页 + 文字路径兜底（见 [manualPath]）。
 *
 * 不申请任何危险权限：只读取 Build / PowerManager 状态，跳转用显式 intent。
 */
object OemBatteryGuide {
    data class Candidate(
        val pkg: String,
        val cls: String,
    )

    /** 品牌识别：MANUFACTURER 与 BRAND 都看（不同 ROM 上报不一致）。 */
    fun brand(): String {
        val raw = listOf(Build.MANUFACTURER, Build.BRAND).joinToString(" ").lowercase()
        return when {
            raw.contains("xiaomi") || raw.contains("redmi") || raw.contains("poco") -> "小米 / Redmi"
            raw.contains("huawei") || raw.contains("honor") -> "华为 / 荣耀"
            raw.contains("vivo") || raw.contains("iqoo") -> "vivo / iQOO"
            raw.contains("oppo") || raw.contains("realme") || raw.contains("oneplus") -> "OPPO / realme / 一加"
            raw.contains("samsung") -> "三星"
            raw.contains("asus") -> "华硕"
            raw.contains("letv") || raw.contains("leeco") -> "乐视"
            raw.contains("transsion") || raw.contains("tecno") || raw.contains("infinix") || raw.contains("itel") -> "传音"
            else -> "通用 Android"
        }
    }

    private fun rawBrand(): String =
        listOf(Build.MANUFACTURER, Build.BRAND).joinToString(" ").lowercase()

    /** 候选组件列表：按品牌给多个 ROM 版本可能的类名。 */
    private fun candidates(): List<Candidate> {
        val b = rawBrand()
        return when {
            b.contains("xiaomi") || b.contains("redmi") || b.contains("poco") ->
                listOf(
                    Candidate("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    Candidate("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                ).distinct()

            b.contains("huawei") || b.contains("honor") ->
                listOf(
                    Candidate("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    Candidate("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                    Candidate("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                )

            b.contains("vivo") || b.contains("iqoo") ->
                listOf(
                    Candidate("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    Candidate("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                    Candidate("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                    Candidate("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
                )

            b.contains("oppo") || b.contains("realme") || b.contains("oneplus") ->
                listOf(
                    Candidate("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    Candidate("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    Candidate("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                    Candidate("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                )

            b.contains("samsung") ->
                listOf(
                    Candidate("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                    Candidate("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                )

            b.contains("asus") -> listOf(Candidate("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity"))
            b.contains("letv") || b.contains("leeco") ->
                listOf(Candidate("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"))

            else -> emptyList()
        }
    }

    /** 电池优化白名单是否已加（已加则不受 Doze 限制）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

    /**
     * 跳转 OEM 自启动设置页。
     * @return true = 成功跳到 OEM 页或降级页；false = 连降级都失败
     */
    fun openAutoStartSettings(context: Context): Boolean {
        // 1) OEM 自启动页：逐个探测组件是否存在
        for (c in candidates()) {
            val intent =
                Intent().apply {
                    component = ComponentName(c.pkg, c.cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            if (runCatching { context.packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)) {
                if (runCatching { context.startActivity(intent) }.isSuccess) return true
            }
        }
        // 2) 通用电池优化白名单（normal 权限，非危险）
        if (requestIgnoreBatteryOptimizations(context)) return true
        // 3) 兜底：应用详情页
        val detail =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return runCatching { context.startActivity(detail) }.isSuccess
    }

    /**
     * 直接弹系统对话框，把本 App 加入电池优化白名单（不受 Doze 限制）。
     * 这是「电池不优化」最直接的方式——系统原生确认框，用户点一下即可。
     * @return true = 成功弹出；false = 设备不支持
     */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) return true // 已加，无需再弹
        val intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { context.packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)) {
            return runCatching { context.startActivity(intent) }.isSuccess
        }
        // 降级：打开电池优化列表让用户自己找
        return openBatteryOptimizationList(context)
    }

    /** 跳转系统电池优化列表（让用户自己找本 App，适合通用 Android）。 */
    fun openBatteryOptimizationList(context: Context): Boolean {
        val intent =
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 手动路径兜底：跳转失败时给用户看的文字说明。 */
    fun manualPath(): String =
        when (brand()) {
            "小米 / Redmi" -> "设置 → 应用设置 → 授权管理 → 自启动管理 → 允许「星火燎原」；再进 设置 → 省电与电池 → 电池优化 → 选「不优化」"
            "华为 / 荣耀" -> "设置 → 应用 → 应用启动管理 → 找到「星火燎原」→ 关闭「自动管理」并手动打开「自启动 / 关联启动 / 后台活动」"
            "vivo / iQOO" -> "i管家 → 应用管理 → 权限管理 → 自启动 → 允许「星火燎原」；再进 i管家 → 耗电管理 → 后台高耗电 → 允许"
            "OPPO / realme / 一加" -> "手机管家 → 权限隐私 → 自启动管理 → 允许「星火燎原」；再进 手机管家 → 耗电管理 → 允许后台运行"
            "三星" -> "设置 → 应用程序 → 找到「星火燎原」→ 电池 → 设为「不受限制的电池使用量」；并关闭「休眠应用」"
            "华硕" -> "Mobile Manager → 省电管理 → 将「星火燎原」加入白名单"
            "乐视" -> "系统管家 → 自启动管理 → 允许「星火燎原」"
            "传音" -> "设置 → 应用管理 → 找到「星火燎原」→ 电池 → 关闭电池优化（该品牌无公开跳转组件）"
            else -> "设置 → 应用 → 找到「星火燎原」→ 电池 / 省电 → 设为「不优化」；并在系统的「自启动管理」里允许本 App"
        }
}
