package com.worldmargin.autospark.features.autospark.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.worldmargin.autospark.core.HostRuntime
import com.worldmargin.autospark.core.ModLog
import com.worldmargin.autospark.features.account.bridge.AccountBridge
import com.worldmargin.autospark.features.autospark.model.BuiltinTemplates
import com.worldmargin.autospark.features.autospark.model.ConversationSnapshot
import com.worldmargin.autospark.features.autospark.model.SparkConfig
import com.worldmargin.autospark.features.autospark.provider.ConfigClient
import com.worldmargin.autospark.features.autospark.provider.ConfigContract
import com.worldmargin.autospark.features.autospark.script.SparkScriptEngine
import com.worldmargin.autospark.features.im.bridge.ImBridge
import com.worldmargin.autospark.features.im.bridge.ImMsgBridge
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动续火花（运行在抖音进程内）。
 *
 * 流程：读配置 → 扫描会话 → 取「需要续」的 → 用模板脚本渲染文案 → 发送 → 记录当日已发。
 * 文案来源：该会话的模板内容；无则用默认模板；再无则用 [DEFAULT_SCRIPT]。
 */
object AutoSparkBridge {
    private const val DEFAULT_LIMIT = 200

    /** 打开抖音后延迟触发：等 IM 初始化完成。 */
    private const val START_DELAY_MS = 30_000L

    /** 会话列表还没就绪时的重试间隔与次数。 */
    private const val RETRY_DELAY_MS = 20_000L
    private const val MAX_ATTEMPTS = 8

    /** 唤醒模块进程后等它就绪的时间。 */
    private const val WAKE_WAIT_MS = 2_000L

    private const val MODULE_PACKAGE = "com.worldmargin.autospark"

    private val refreshReceiverInstalled = AtomicBoolean(false)

    @Volatile
    private var refreshReceiver: BroadcastReceiver? = null

    /**
     * 无任何模板时的兜底文案（模板页的「按时问候」内置模板用的就是这份）。
     * 注意：它**不会**自动生效——没选模板的会话一律不发。
     */
    val DEFAULT_SCRIPT: String get() = BuiltinTemplates.GREETING.body

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * 某会话要执行的完整程序：**自定义函数注入 → 模板脚本 → 宏（按顺序）**。
     *
     * 默认是**空白**：既没有模板内容、也没有宏时返回空串，[runRenew] 会跳过这个会话，
     * 不再回退到什么默认模板 / 内置文案——内置文案要用户在模板页里自己选。
     */
    fun scriptFor(
        config: SparkConfig,
        conversationId: String,
    ): String {
        val body = config.conversation(conversationId)?.body?.trim().orEmpty()
        val macros = config.macrosFor(listOf(conversationId) + listOfNotNull(config.conversation(conversationId)?.inheritFrom))
        if (body.isEmpty() && macros.isEmpty()) return ""
        return config.composeProgram(body, macros)
    }

    /** 把当前会话列表写入快照（供管理界面展示）。 */
    fun refreshSnapshot(context: Context): RefreshOutcome {
        val fetched = fetchConversations()
        val conversations = fetched.conversations ?: return RefreshOutcome(false, fetched.error)
        traceConversations(context, fetched)
        val now = System.currentTimeMillis()
        val snapshots = mutableListOf<ConversationSnapshot>()
        for (index in 0 until conversations.length()) {
            val conversation = conversations.optJSONObject(index) ?: continue
            val spark = conversation.optJSONObject("spark")
            snapshots +=
                ConversationSnapshot(
                    conversationId = conversation.optString("conversationId"),
                    name = conversation.optString("name"),
                    remark = conversation.optString("remarkName"),
                    avatar = conversation.optString("peerAvatar"),
                    peerUid = conversation.optString("peerUid"),
                    days = spark?.optInt("days") ?: 0,
                    state = spark?.optInt("state", -1) ?: -1,
                    stateText = spark?.optString("stateText") ?: "",
                    recoverDays = spark?.optInt("recoverDays") ?: 0,
                    recoverNeedDays = spark?.optInt("recoverNeedDays") ?: 0,
                    hasSpark = spark?.optBoolean("hasSpark") == true,
                    needsRenewal = spark?.optBoolean("needsRenewal") == true,
                    isGroup = conversation.optBoolean("isGroup"),
                    updatedAt = now,
                )
        }
        val written = ConfigClient.putSnapshots(context, snapshots)
        if (!written) return RefreshOutcome(false, "快照写入配置失败（跨进程通道不可用）")
        // 快照落盘后按保留策略清理：不在已选目标里的会话，模板与发送记录按 30 天宽限期清掉
        ConfigClient.prune(context, now)
        return RefreshOutcome(true, "${snapshots.size} 个会话")
    }

    /**
     * 注册「刷新会话」与「立即发送」广播接收器（跑在抖音进程内）。
     *
     * 管理界面在模块进程发 [ConfigContract.ACTION_REFRESH_SNAPSHOT]，宿主收到后重新扫描会话，
     * 成功再回发 [ConfigContract.ACTION_SNAPSHOT_UPDATED] 通知界面刷新；
     * 发 [ConfigContract.ACTION_SEND_NOW] 则立即手动发送一轮，结果通过
     * [ConfigContract.ACTION_SEND_DONE] 回给界面。
     */
    fun installRefreshReceiver(context: Context) {
        if (!refreshReceiverInstalled.compareAndSet(false, true)) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    received: Context?,
                    intent: Intent?,
                ) {
                    val action = intent?.action
                    if (action == ConfigContract.ACTION_SEND_NOW) {
                        val targets = intent.getStringArrayListExtra(ConfigContract.EXTRA_TARGETS).orEmpty()
                        Thread({ sendNowAndNotify(context, targets) }, "doumcp-send").apply { isDaemon = true }.start()
                        return
                    }
                    Thread({ refreshAndNotify(context) }, "doumcp-refresh").apply { isDaemon = true }.start()
                }
            }
        refreshReceiver = receiver
        val filter =
            IntentFilter().apply {
                addAction(ConfigContract.ACTION_REFRESH_SNAPSHOT)
                addAction(ConfigContract.ACTION_SEND_NOW)
            }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        }.onFailure { ModLog.e("刷新会话广播注册失败", it) }
    }

    /**
     * 手动发送一轮（界面点「立即发送」）。
     *
     * 不受总开关与「今日已发」限制：这是用户明确点的操作；[targets] 为空表示发给所有「已选目标」。
     */
    private fun sendNowAndNotify(
        context: Context,
        targets: List<String>,
    ) {
        wakeModule(context)
        val summary = runRenew(context, respectEnabled = false, only = targets.toSet(), ignoreSentToday = true)
        val line =
            if (summary.optBoolean("ok")) {
                val sent = summary.optJSONArray("sent")
                val skipped = summary.optJSONArray("skipped")
                val failed = summary.optJSONArray("failed")
                "手动发送：已发 ${sent?.length() ?: 0} / 跳过 ${skipped?.length() ?: 0} / 失败 ${failed?.length() ?: 0}" +
                    detailOf(sent, "已发") + detailOf(skipped, "跳过") + detailOf(failed, "失败")
            } else {
                "手动发送未执行：${summary.optString("error")}"
            }
        HostTrace.add(context, line)
        ModLog.i(line)
        runCatching {
            context.sendBroadcast(
                Intent(ConfigContract.ACTION_SEND_DONE).putExtra(ConfigContract.EXTRA_SUMMARY, line),
            )
        }.onFailure { ModLog.e("发送结果广播失败", it) }
    }

    /** 把结果数组拼成「名字（原因）」的短串，便于界面直接展示。 */
    private fun detailOf(
        items: JSONArray?,
        label: String,
    ): String {
        if (items == null || items.length() == 0) return ""
        val names =
            (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("name").ifBlank { item.optString("conversationId").takeLast(6) }
                val reason = item.optString("reason").ifBlank { item.optString("error") }
                if (reason.isBlank()) name else "$name（$reason）"
            }
        return if (names.isEmpty()) "" else "\n$label：" + names.joinToString("、")
    }

    /**
     * 唤醒模块 App 进程。
     *
     * 宿主调模块的 ContentProvider 时，模块进程如果从没被创建过（重装 APK 后就是这种状态），
     * Android 会拒绝解析 provider（日志里是 Failed to find provider info），配置就写不进去。
     * 先发一个包内广播把模块进程拉起来（进程创建时系统会一并安装它的 provider），等它就绪再写。
     */
    private fun wakeModule(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(ConfigContract.ACTION_WAKE).setPackage(MODULE_PACKAGE))
        }.onFailure { ModLog.e("唤醒模块进程失败", it) }
        Thread.sleep(WAKE_WAIT_MS)
    }

    /** 手动刷新一次并通知界面（广播接收线程）。 */
    private fun refreshAndNotify(context: Context) {
        wakeModule(context)
        val outcome = refreshSnapshot(context)
        HostTrace.add(context, if (outcome.ok) "手动刷新成功：${outcome.detail}" else "手动刷新失败：${outcome.detail}")
        if (!outcome.ok) return
        runCatching { context.sendBroadcast(Intent(ConfigContract.ACTION_SNAPSHOT_UPDATED)) }
            .onFailure { ModLog.e("快照更新广播发送失败", it) }
    }

    /**
     * 扫描并发送一轮。
     *
     * @param respectEnabled 是否遵守总开关（自动触发时为 true，手动运行不考虑）
     * @param only 只处理这些会话（手动发送时用）；为空表示按抖音判定挑「需要续」的
     * @param ignoreSentToday 忽略「今日已发」记录（手动发送可以再发一次）
     */
    fun runRenew(
        context: Context,
        respectEnabled: Boolean,
        only: Set<String> = emptySet(),
        ignoreSentToday: Boolean = false,
    ): JSONObject {
        val config = ConfigClient.read(context) ?: return error("配置读取失败（模块未安装或未启用）")
        if (respectEnabled && !config.enabled) return error("总开关未开启")
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return error("未登录")

        val conversations = loadConversations() ?: return error("会话列表获取失败（IM 未初始化或链路变动）")
        val today = dayFormat.format(Date())

        val sent = JSONArray()
        val skipped = JSONArray()
        val failed = JSONArray()

        for (index in 0 until conversations.length()) {
            val conversation = conversations.optJSONObject(index) ?: continue
            if (conversation.optBoolean("isGroup")) continue
            val conversationId = conversation.optString("conversationId")
            if (conversationId.isEmpty()) continue
            if (only.isNotEmpty()) {
                // 手动点名：只发这些，不再看抖音判定与手动归属
                if (conversationId !in only) continue
            } else {
                // 「需要续」来自抖音判定，可被管理界面的手动移动覆盖
                if (conversationId in config.forcedSkip) continue
                val spark = conversation.optJSONObject("spark")
                val needsRenewal =
                    conversationId in config.forcedRenew ||
                        (spark?.optBoolean("hasSpark") == true && spark.optBoolean("needsRenewal"))
                if (!needsRenewal) continue
            }

            if (!ignoreSentToday && config.sentDays[conversationId] == today) {
                skipped.put(JSONObject().put("conversationId", conversationId).put("reason", "今日已发送"))
                continue
            }

            val name = displayName(conversation)
            val script = scriptFor(config, conversationId)
            if (script.isEmpty()) {
                // 默认空白：没给这个会话选模板就不发（内置文案也要用户自己选）
                skipped.put(JSONObject().put("conversationId", conversationId).put("name", name).put("reason", "未选择模板"))
                continue
            }
            val spark = conversation.optJSONObject("spark")
            val text =
                runCatching { SparkScriptEngine.render(script, buildVariables(conversation, spark)) }
                    .getOrElse { error ->
                        failed.put(
                            JSONObject().put("conversationId", conversationId).put("name", name)
                                .put("error", error.message ?: error.toString()),
                        )
                        continue
                    }

            val outcome = runCatching { JSONObject(ImMsgBridge.sendTextJson(conversationId, text, 0L, "")) }.getOrNull()
            if (outcome != null && outcome.optBoolean("ok")) {
                ConfigClient.markSent(context, conversationId, today)
                sent.put(
                    JSONObject().put("conversationId", conversationId).put("name", name)
                        .put("text", text).put("msgId", outcome.optLong("msgId")),
                )
            } else {
                failed.put(
                    JSONObject().put("conversationId", conversationId).put("name", name)
                        .put("error", outcome?.optString("error") ?: "发送失败"),
                )
            }
        }

        return JSONObject()
            .put("ok", true)
            .put("today", today)
            .put("sent", sent)
            .put("skipped", skipped)
            .put("failed", failed)
    }

    /** 打开抖音后延迟自动执行一次（总开关关闭时内部直接返回），并刷新会话快照。 */
    fun scheduleOnAppStart() {
        val context = HostRuntime.context ?: return
        Thread(
            {
                wakeModule(context)
                HostTrace.start(context, "已安排：30 秒后扫描会话")
                runCatching {
                    Thread.sleep(START_DELAY_MS)
                    // 冷启动时 IM 可能还没就绪，会话列表取不到就退避重试
                    var attempt = 0
                    var outcome = refreshSnapshot(context)
                    while (!outcome.ok) {
                        attempt++
                        HostTrace.add(context, "第 $attempt 次取会话失败：${outcome.detail}")
                        if (attempt >= MAX_ATTEMPTS) {
                            HostTrace.add(context, "重试 $MAX_ATTEMPTS 次仍未取到会话，本次跳过")
                            ModLog.i("会话列表未就绪，本次不续火花")
                            return@runCatching
                        }
                        Thread.sleep(RETRY_DELAY_MS)
                        outcome = refreshSnapshot(context)
                    }
                    HostTrace.add(context, "快照已写入：${outcome.detail}")
                    val summary = runRenew(context, respectEnabled = true)
                    if (summary.optBoolean("ok")) {
                        val line =
                            "续火花结果：已发 ${summary.optJSONArray("sent")?.length() ?: 0} / " +
                                "跳过 ${summary.optJSONArray("skipped")?.length() ?: 0} / " +
                                "失败 ${summary.optJSONArray("failed")?.length() ?: 0}"
                        HostTrace.add(context, line)
                        ModLog.i(line)
                    } else {
                        HostTrace.add(context, "续火花未执行：${summary.optString("error")}")
                        ModLog.i("自动续火花未执行：${summary.optString("error")}")
                    }
                }.onFailure {
                    HostTrace.add(context, "执行异常：${it.message ?: it.toString()}")
                    ModLog.e("自动续火花执行异常", it)
                }
            },
            "doumcp-autospark",
        ).apply { isDaemon = true }.start()
    }

    private fun loadConversations(): JSONArray? = fetchConversations().conversations

    /** 单聊会话的火花数据摘进宿主简报：IM 返回了多少条、每条的火花字段与原始负载。 */
    private fun traceConversations(
        context: Context,
        fetched: Fetched,
    ) {
        val conversations = fetched.conversations ?: return
        HostTrace.add(context, "IM 会话 ${fetched.total} 条，进入快照的 ${conversations.length()} 条")
        for (index in 0 until conversations.length()) {
            val conversation = conversations.optJSONObject(index) ?: continue
            val name = conversation.optString("remarkName").ifBlank { conversation.optString("name") }
            val spark = conversation.optJSONObject("spark")
            val summary =
                if (spark == null) {
                    "无火花数据"
                } else {
                    "hasSpark=${spark.optBoolean("hasSpark")} state=${spark.optInt("state", -1)} " +
                        "days=${spark.optInt("days")} text=${spark.optString("stateText")} " +
                        "level=${spark.optString("level")} start=${spark.optLong("start")} end=${spark.optLong("end")} " +
                        "petElf=${spark.optBoolean("petElf")} relation=${spark.optString("relationType")} " +
                        "effect=${spark.optString("effectKind")}"
                }
            HostTrace.add(context, "$name · $summary")
            spark?.optString("raw")?.takeIf { it.isNotBlank() }?.let { raw ->
                HostTrace.add(context, "raw[$name]=${raw.take(700)}")
            }
        }
    }

    /** 会话列表 + 失败原因（失败原因会写进宿主简报，便于定位是哪一步断了）。 */
    private class Fetched(
        val conversations: JSONArray?,
        val error: String,
        val total: Int = 0,
    )

    private fun fetchConversations(): Fetched =
        runCatching {
            val json = JSONObject(ImBridge.getConversationsJson(DEFAULT_LIMIT, withSpark = true))
            if (json.optBoolean("ok")) {
                Fetched(json.optJSONArray("conversations"), "", json.optInt("total"))
            } else {
                Fetched(null, json.optString("error").ifBlank { "会话列表读取失败" })
            }
        }.getOrElse { Fetched(null, it.message ?: it.toString()) }

    /** 脚本可见的变量：当日信息 + 对方资料 + 火花字段（全部挂在 ctx 上）。 */
    private fun buildVariables(
        conversation: JSONObject,
        spark: JSONObject?,
    ): Map<String, Any?> {
        val now = Date()
        val nickname = conversation.optString("name")
        val remark = conversation.optString("remarkName")
        return mapOf(
            "date" to dayFormat.format(now),
            "time" to timeFormat.format(now),
            "weekday" to weekday(now),
            "timestamp" to now.time,
            "nickname" to nickname,
            "remark" to remark,
            "name" to remark.ifBlank { nickname },
            "days" to (spark?.optInt("days") ?: 0),
            "state" to (spark?.optInt("state", -1) ?: -1),
            "stateText" to (spark?.optString("stateText") ?: ""),
            "peerUid" to conversation.optString("peerUid"),
            "conversationId" to conversation.optString("conversationId"),
        )
    }

    private fun displayName(conversation: JSONObject): String {
        val remark = conversation.optString("remarkName")
        return remark.ifBlank { conversation.optString("name") }
    }

    private fun weekday(date: Date): String {
        val calendar = Calendar.getInstance().apply { time = date }
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "星期一"
            Calendar.TUESDAY -> "星期二"
            Calendar.WEDNESDAY -> "星期三"
            Calendar.THURSDAY -> "星期四"
            Calendar.FRIDAY -> "星期五"
            Calendar.SATURDAY -> "星期六"
            else -> "星期日"
        }
    }

    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)
}

/** 一次快照刷新的结果：[detail] 在成功时是会话数，失败时是原因。 */
data class RefreshOutcome(
    val ok: Boolean,
    val detail: String,
)
