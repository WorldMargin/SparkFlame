package com.worldmargin.autospark.app.config

/** 内置文案模板（模板页里可直接「添加为我的模板」或一键复制给会话）。 */
data class BuiltinTemplate(
    val id: String,
    val name: String,
    val desc: String,
    val body: String,
)

/**
 * 内置模板：只是**可选**的起点，不会自动套给任何会话。
 *
 * 默认状态是「空白模板」= 不用模板、自己写（和别的模板一样是跟随关系，只是父模板内容是空的）。
 */
object BuiltinTemplates {
    /** 空白：不用模板，会话自己写文案（照常发送）。 */
    val BLANK =
        BuiltinTemplate(
            id = "blank",
            name = "空白模板",
            desc = "不用模板，自己写",
            body = "",
        )

    /** 按时间选招呼语，凌晨不说早安。 */
    val GREETING =
        BuiltinTemplate(
            id = "greeting",
            name = "按时问候",
            desc = "夜深了 / 早安 / 午安 / 下午好 / 晚上好 + 续火花",
            body =
                """var who = ctx.io.name || "朋友";
var hour = parseInt(ctx.builtin.time.split(":")[0], 10);
var hi = hour < 5 ? "夜深了" : hour < 11 ? "早安" : hour < 14 ? "午安" : hour < 18 ? "下午好" : hour < 23 ? "晚上好" : "夜深了";
${'$'}spark_result${'$'}.push(ctx.builtin.pick([
  hi + "～" + who + "，今天也要记得续火花哦",
  "火花打卡 " + ctx.builtin.date + "：" + who + "，已经 " + ctx.io.days + " 天啦",
  "滴——" + who + "，第 " + ctx.io.days + " 天的火花，别让它灭了"
]));""",
        )

    /** 只报天数与日期，简短不带招呼。 */
    val STREAK =
        BuiltinTemplate(
            id = "streak",
            name = "火花天数打卡",
            desc = "带上已连续天数与当天日期",
            body =
                """var who = ctx.io.name || "朋友";
${'$'}spark_result${'$'}.push("火花打卡 " + ctx.builtin.date + "：" + who + "，已经 " + ctx.io.days + " 天啦");""",
        )

    /** 最短的一句话提醒。 */
    val REMIND =
        BuiltinTemplate(
            id = "remind",
            name = "简短提醒",
            desc = "一两句短提醒，不啰嗦",
            body =
                """var who = ctx.io.name || "朋友";
${'$'}spark_result${'$'}.push(ctx.builtin.pick([
  who + "，滴——打卡啦",
  who + "，别忘了咱们的火花～",
  who + "，今天也来续一下火花吧"
]));""",
        )

    val ALL = listOf(BLANK, GREETING, STREAK, REMIND)

    fun byId(id: String): BuiltinTemplate? = ALL.firstOrNull { it.id == id }
}
