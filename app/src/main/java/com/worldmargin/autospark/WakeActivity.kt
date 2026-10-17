package com.worldmargin.autospark

import android.app.Activity
import android.os.Bundle

/**
 * 空壳页面：只用来把模块进程（连同它的 ContentProvider）拉起来。
 *
 * 模块 App 被系统置为「停用」（`adb install` 覆盖安装、`am force-stop` 之后都是）
 * 时，广播和 provider 调用都会被系统丢掉 —— 只有「启动一个 Activity」能把进程叫起来。
 * 它的主题是透明的（`Theme.Translucent.NoTitleBar`），启动后立刻 finish，用户几乎看不到。
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
