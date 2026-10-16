package com.worldmargin.autospark.features.account.bridge

import com.worldmargin.autospark.core.HostRuntime

/**
 * 抖音账号能力桥（精简版）：只保留「取当前登录用户 ID」。
 *
 * 用途是续火花前的登录态判断——未登录时会话列表没有意义。
 * `AccountProxyService.userService().getCurUserId()` 均未混淆，直接调用。
 */
object AccountBridge {
    private const val ACCOUNT_PROXY_SERVICE = "com.ss.android.ugc.aweme.account.AccountProxyService"

    fun getCurrentUserId(): String? =
        runCatching {
            val serviceClass = Class.forName(ACCOUNT_PROXY_SERVICE, false, HostRuntime.requireClassLoader())
            val userService = serviceClass.getDeclaredMethod("userService").invoke(null) ?: return@runCatching null
            userService.javaClass.getMethod("getCurUserId").invoke(userService) as? String
        }.getOrNull()
}
