package com.worldmargin.autospark.features.autospark.bridge

import android.content.Context
import com.worldmargin.autospark.features.autospark.provider.ConfigClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 宿主（抖音进程）侧运行简报。
 *
 * 本机的 LSPosed 日志与 logcat 都取不到模块内容，宿主进程又无法写模块 App 的私有目录，
 * 所以宿主只能把「自己做了什么、失败在哪一步」写回配置，由管理界面的「设置 → 宿主状态」展示。
 * 内容只保留最近 [MAX_LINES] 行，避免配置文件无限增长。
 */
object HostTrace {
    private const val MAX_LINES = 60

    private val lines = mutableListOf<String>()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /** 新的一轮宿主进程（Application.attach）开始时清空历史，便于判断当前这次启动走到了哪。 */
    @Synchronized
    fun start(
        context: Context,
        text: String,
    ) {
        lines.clear()
        add(context, text)
    }

    /** 追加一行简报并立刻写进配置。 */
    @Synchronized
    fun add(
        context: Context,
        text: String,
    ) {
        lines += format.format(Date()) + "  " + text
        while (lines.size > MAX_LINES) lines.removeAt(0)
        ConfigClient.note(context, lines.joinToString("\n"), System.currentTimeMillis())
    }
}
