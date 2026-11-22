package com.worldmargin.autospark.host.bridge

import android.content.Context
import com.worldmargin.autospark.app.config.ConfigClient
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

    /**
     * 新一轮宿主进程开始。不再清空历史：冷启动诊断行（签名校验耗时 / 模块冷启动完成）
     * 是在 attach 阶段写的，比这里（后台线程）更早，清空会把它们抹掉。
     * 改成加一条分隔行，保留最近 [MAX_LINES] 行窗口内的上一轮尾部供对照。
     */
    @Synchronized
    fun start(
        context: Context,
        text: String,
    ) {
        if (lines.isNotEmpty()) lines += "──── 新一轮 ────"
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
