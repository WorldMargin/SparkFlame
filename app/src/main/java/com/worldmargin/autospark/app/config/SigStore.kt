package com.worldmargin.autospark.app.config

import android.content.Context

/**
 * 「无视风险」授权位的本地存储（模块进程私有 SharedPreferences）。
 *
 * 只存一个一次性决定：签名不符时，用户是否明确同意继续使用。
 * 放 SharedPreferences 而不是配置文件，是为了**不随「导出配置」被带到别的设备 / 别的改包上**。
 *
 * 写入方：模块 App 的签名提示弹窗；读取方：模块 App 自己 + `ConfigProvider`（供宿主跨进程读）。
 */
object SigStore {
    private const val PREFS = "spark_guard"
    private const val KEY_ACCEPTED = "signature_accepted"

    fun accepted(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_ACCEPTED, false) }.getOrDefault(false)

    fun accept(context: Context) {
        runCatching { prefs(context).edit().putBoolean(KEY_ACCEPTED, true).commit() }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
