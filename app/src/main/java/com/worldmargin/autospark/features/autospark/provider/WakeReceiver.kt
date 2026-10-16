package com.worldmargin.autospark.features.autospark.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 唤醒接收器（跑在模块进程内，Manifest 注册）。
 *
 * 宿主（抖音进程）写配置前会先发 [ConfigContract.ACTION_WAKE]，让本模块进程先起来：
 * 进程被创建时系统会一并安装它的 ContentProvider，宿主的跨进程写入才不会失败。
 * 自身不做任何事，收到广播即为目的达成。
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) = Unit
}
