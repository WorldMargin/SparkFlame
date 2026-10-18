package com.worldmargin.autospark.features.autospark.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
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

    /** 打开抖音后稍等 IM 初始化；之后就靠探测（refreshSnapshot 成功即视为就绪）。 */
    private const val START_DELAY_MS = 5_000L

    /** 探测间隔：取不到会话就等这么久再试，近似握手重试。 */
    private const val RETRY_DELAY_MS = 2_500L

    /** 最多探测这么多次（约 5s + 22×2.5s ≈ 60s 封顶）。 */
    private const val MAX_ATTEMPTS = 22

    /** 唤醒模块进程后等它就绪的时间。 */
    private const val WAKE_WAIT_MS = 2_000L

    /** 模块 App 的 applicationId（广播 / Activity 跳转用）。 */
    private const val MODULE_PACKAGE = "io.github.worldmargin.sparkflame"

    /** 空页面的全限定类名：代码包名与 applicationId 不同，不能拼。 */
    private const val WAKE_ACTIVITY = "com.worldmargin.autospark.WakeActivity"

    private val refreshReceiverInstalled = AtomicBoolean(false)

    @Volatile
    private var refreshReceiver: BroadcastReceiver? = null

    /**
     * 无任何模板时的兜底文案（模板页的「按时问候」内置模板用的就是这份）。
     * 注意：它**不会**自动生效——没写文案的会话一律不发。
     */
    val DEFAULT_SCRIPT: String get() = BuiltinTemplates.GREETING.body

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * 某会话要执行的完整程序：**自定义函数注入 → 模板脚本 → 宏（按顺序）**。
     *
     * 「空白模板」不是不发：会话自己写的文案就是这里的 body。
     * 只有**文案与宏都没有**时返回空串，[runRenew] 才跳过这个会话（不回退默认模板）。
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

    /** 脚本可见的沙盒环境：归属、已有数据、会话列表。 */
    private fun sandboxEnv(
        config: SparkConfig,
        conversationId: String,
        conversations: org.json.JSONArray,
    ): SparkScriptEngine.SandboxEnv =
        SparkScriptEngine.SandboxEnv(
            data = config.sandbox.groupBy { it.owner },
            conversations = conversationsJson(conversations),
        )

    /** `ctx.system.getAllConversation()` 的返回值（字段少而稳定，不把原始负载带进脚本）。 */
    private fun conversationsJson(conversations: org.json.JSONArray): String {
        val array = org.json.JSONArray()
        for (index in 0 until conversations.length()) {
            val item = conversations.optJSONObject(index) ?: continue
            val spark = item.optJSONObject("spark")
            array.put(
                JSONObject()
                    .put("id", item.optString("conversationId"))
                    .put("name", item.optString("name"))
                    .put("remark", item.optString("remarkName"))
                    .put("days", spark?.optInt("days") ?: 0)
                    .put("state", spark?.optInt("state", -1) ?: -1)
                    .put("stateText", spark?.optString("stateText") ?: "")
                    .put("isGroup", item.optBoolean("isGroup")),
            )
        }
        return array.toString()
    }

    /** `ctx.template.template`：当前生效的模板名（空白模板就是「空白模板」）。 */
    private fun templateNameOf(
        config: SparkConfig,
        conversationId: String,
    ): String {
        val parent = config.conversation(conversationId)?.inheritFrom?.let { config.templateName(it) }
        return parent ?: BuiltinTemplates.BLANK.name
    }

    /** `ctx.template.parent_template`：父模板名（两层结构下一般为空）。 */
    private fun parentTemplateNameOf(
        config: SparkConfig,
        conversationId: String,
    ): String = config.conversation(conversationId)?.inheritFrom?.let { config.templateName(it) }?.let { "" } ?: ""

    /** 把脚本改动写回配置（沙盒变量 + 宏挂载）。 */
    private fun applyScriptMutations(
        context: Context,
        config: SparkConfig,
        writes: List<com.worldmargin.autospark.features.autospark.model.SandboxVar>,
        deletes: List<Pair<String, String>>,
        macroOps: List<SparkScriptEngine.MacroOp>,
    ) {
        if (writes.isEmpty() && deletes.isEmpty() && macroOps.isEmpty()) return
        // 重新读一次：这一轮里 markSent 已经改过配置，
        // 拿跑步时的快照写回会把「今日已发」覆盖掉
        val latest = ConfigClient.read(context) ?: config
        val sandbox = latest.sandbox.toMutableList()
        writes.forEach { write ->
            val index = sandbox.indexOfFirst { it.owner == write.owner && it.key == write.key }
            if (index >= 0) sandbox[index] = write else sandbox += write
        }
        deletes.forEach { (owner, key) -> sandbox.removeAll { it.owner == owner && it.key == key } }
        var macros = latest.macros
        macroOps.forEach { op ->
            val targets = if (op.kind == "unmountAll") macros.indices.toList() else listOfNotNull(macros.indexOfFirst { it.name == op.name }.takeIf { it >= 0 })
            targets.forEach { index ->
                val macro = macros[index]
                val applies = macro.appliesTo.toMutableSet()
                val only = macro.templateOnly.toMutableSet()
                // 目标是模板时：传播 → appliesTo，仅模板 → templateOnly；两边互斥
                val isTemplate = op.targetId.isNotBlank() && !op.targetId.contains(":")
                when (op.kind) {
                    "mount" -> if (isTemplate && !op.inherit) {
                        applies -= op.targetId
                        only += op.targetId
                    } else {
                        only -= op.targetId
                        applies += op.targetId
                    }
                    else -> {
                        applies -= op.targetId
                        only -= op.targetId
                    }
                }
                macros = macros.toMutableList().also { it[index] = macro.copy(appliesTo = applies, templateOnly = only) }
            }
        }
        val updated = latest.copy(sandbox = sandbox, macros = macros)
        if (!ConfigClient.write(context, updated)) {
            ModLog.w("脚本沙盒 / 宏挂载写入失败")
        }
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
                    ConfigClient.touchHost(context, System.currentTimeMillis())
                    when (action) {
                        ConfigContract.ACTION_PING_HOST -> {
                            // 探活：立刻回执（界面拿到回执才认为宿主在线）
                            runCatching { context.sendBroadcast(Intent(ConfigContract.ACTION_HOST_ALIVE)) }
                                .onFailure { ModLog.e("探活回执发送失败", it) }
                        }

                        ConfigContract.ACTION_SEND_NOW -> {
                            val targets = intent.getStringArrayListExtra(ConfigContract.EXTRA_TARGETS).orEmpty()
                            Thread({ sendNowAndNotify(context, targets) }, "doumcp-send").apply { isDaemon = true }.start()
                        }

                        else -> Thread({ refreshAndNotify(context) }, "doumcp-refresh").apply { isDaemon = true }.start()
                    }
                }
            }
        refreshReceiver = receiver
        val filter =
            IntentFilter().apply {
                addAction(ConfigContract.ACTION_REFRESH_SNAPSHOT)
                addAction(ConfigContract.ACTION_SEND_NOW)
                addAction(ConfigContract.ACTION_PING_HOST)
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
     * 手动发送一轮（界面点「立即发送」，或抖音设置页入口选「立即续火花」）。
     *
     * 不受总开关与「今日已发」限制：这是用户明确点的操作；[targets] 为空表示发给所有「已选目标」。
     * [toast] 为 true 时直接在宿主里弹提示（抖音设置页入口那条路径用，管理界面不在前台）。
     */
    private fun sendNowAndNotify(
        context: Context,
        targets: List<String>,
        toast: Boolean = false,
    ) {
        wakeModule(context)
        val summary = runRenew(context, respectEnabled = false, only = targets.toSet(), ignoreSentToday = true)
        // 发完把快照写回模块 App：不用打开 App 也能看到最新状态与已发记录
        pushSnapshotToModule(context, attempts = 2)
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
        if (toast) {
            val brief = line.lineSequence().first()
            runCatching { Toast.makeText(context, brief, Toast.LENGTH_LONG).show() }
        }
        runCatching {
            context.sendBroadcast(
                Intent(ConfigContract.ACTION_SEND_DONE).putExtra(ConfigContract.EXTRA_SUMMARY, line),
            )
        }.onFailure { ModLog.e("发送结果广播失败", it) }
    }

    /** 抖音设置页入口里的「立即续火花」：就地发一轮，结果用提示告诉用户。 */
    fun manualSendInPlace(context: Context) {
        Thread({ sendNowAndNotify(context, emptyList(), toast = true) }, "doumcp-send-now")
            .apply { isDaemon = true }
            .start()
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
    /**
     * 叫醒模块进程（让它的 ContentProvider 安装上）。
     *
     * 两级：先发 `ACTION_WAKE` 广播；模块 App 被系统置为「停用」
     * （`adb install` 覆盖安装、`am force-stop` 之后都是）时广播会被丢掉，
     * 这时改用启动一个透明空页面把进程拉起来（停用状态下只有启动 Activity 才管用）。
     */
    private fun wakeModule(context: Context) {
        ConfigClient.touchHost(context, System.currentTimeMillis())
        runCatching {
            context.sendBroadcast(
                Intent(ConfigContract.ACTION_WAKE)
                    .setPackage(MODULE_PACKAGE)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
            )
        }.onFailure { ModLog.e("唤醒模块进程失败", it) }
        Thread.sleep(WAKE_WAIT_MS)
        if (ConfigClient.read(context) != null) return
        // 广播叫不醒（模块 App 被停用）：启动透明空页面兜底，让进程 / provider 就位
        runCatching {
            context.startActivity(
                Intent()
                    .setClassName(MODULE_PACKAGE, WAKE_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }.onFailure { ModLog.w("启动模块空页面失败：$it") }
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

    // ------------------------------------------------------------ 跨天值守

    /** 值守用的主线程 Handler：只在抖音有可见 Activity 时挂任务。 */
    private val watcherHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 当前是否有可见 Activity（用 started 计数判断）。 */
    private val visibleActivities = java.util.concurrent.atomic.AtomicInteger(0)

    /** 值守是否已经挂上（避免重复注册生命周期回调）。 */
    private val watcherInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 自动任务互斥：上一轮没跑完就不再起新的。 */
    private val autoRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 上次把快照写回 App 的时间（前台切换很频，限一下频）。 */
    @Volatile
    private var lastSnapshotPushMs = 0L

    /** 前台刷新间隔：1 分钟内不重复写。 */
    private const val SNAPSHOT_PUSH_INTERVAL_MS = 60_000L

    private var watcherContext: Context? = null

    /** 「上次自动跑完的日期」落在抖音自己的目录里，跟着抖音的数据走。 */
    private fun lastRunFile(context: Context) = java.io.File(context.filesDir, "spark_last_auto_day")

    private fun lastRunDay(context: Context): String =
        runCatching { lastRunFile(context).readText().trim() }.getOrDefault("")

    private fun markRunDay(
        context: Context,
        day: String,
    ) {
        runCatching { lastRunFile(context).writeText(day) }
    }

    /**
     * 挂上值守：抖音有 Activity 可见时按时间自动升级检查频率。
     *
     * 平时不轮询 —— 只在 23:30 之后才密集检查，白天几乎零开销。
     */
    fun startDayWatcher(application: android.app.Application) {
        if (!watcherInstalled.compareAndSet(false, true)) return
        val context = application.applicationContext ?: application
        watcherContext = context
        application.registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
                override fun onActivityResumed(activity: android.app.Activity) = Unit

                override fun onActivityPaused(activity: android.app.Activity) = Unit

                override fun onActivityStarted(activity: android.app.Activity) {
                    visibleActivities.incrementAndGet()
                    // 每次回到界面：先把快照刷新一下（限流），再按条件补跑，最后重新排表
                    Thread {
                        pushSnapshotIfStale(context)
                        autoRunIfDayChanged(context)
                    }.start()
                    rescheduleWatcher()
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    if (visibleActivities.decrementAndGet() <= 0) {
                        visibleActivities.set(0)
                        watcherHandler.removeCallbacksAndMessages(null)
                    }
                }

                override fun onActivitySaveInstanceState(
                    activity: android.app.Activity,
                    outState: android.os.Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: android.app.Activity) = Unit
            },
        )
    }

    /** 按当前时间和可见性重排下一次检查（这就是"每次唤醒界面检查条件、切换定时器"）。 */
    private fun rescheduleWatcher() {
        val context = watcherContext ?: return
        watcherHandler.removeCallbacksAndMessages(null)
        if (visibleActivities.get() <= 0) return
        watcherHandler.postDelayed(
            {
                Thread { autoRunIfDayChanged(context) }.start()
                rescheduleWatcher()
            },
            watcherDelayMs(),
        )
    }

    /** 离零点越近检查越密：平时一次等到 23:30，之后每分钟，23:58 起每 5 秒。 */
    private fun watcherDelayMs(): Long {
        val calendar = java.util.Calendar.getInstance()
        val minutes = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        val lateWindow = 23 * 60 + 58
        val eveningStart = 23 * 60 + 30
        val earlyWindow = 5
        return when {
            // 零点前后：5 秒一次，抓住跨天的那一刻
            minutes >= lateWindow || minutes < earlyWindow -> 5_000L
            // 23:30 之后：每分钟
            minutes >= eveningStart -> 60_000L
            // 其余时间：一次性等到 23:30，中间不轮询
            else -> ((eveningStart - minutes).toLong() * 60_000L).coerceAtLeast(60_000L)
        }
    }

    /** 今天还没自动跑过就跑一轮（自动任务，遵守总开关）。 */
    private fun autoRunIfDayChanged(context: Context) {
        val today = dayFormat.format(Date())
        if (lastRunDay(context) == today) return
        if (!autoRunning.compareAndSet(false, true)) return
        try {
            // 先把快照写回模块 App：跨天补跑不需要用户打开 App 也能看到最新数据
            pushSnapshotToModule(context)
            val summary = runRenew(context, respectEnabled = true)
            if (summary.optBoolean("ok")) {
                markRunDay(context, today)
                val sent = summary.optJSONArray("sent")?.length() ?: 0
                HostTrace.add(context, "跨天补跑完成：已发 $sent 个")
            }
            // 发完再刷一次：状态 / 已发记录都变了
            pushSnapshotToModule(context, attempts = 2)
        } catch (error: Throwable) {
            ModLog.e("跨天补跑失败", error)
        } finally {
            autoRunning.set(false)
        }
    }

    /**
     * 回到前台时刷新一下 App 里的快照（限流）。
     *
     * 以前只有「今天还没自动跑过」时才会刷，白天对方回复了、火花变成已续，
     * App 里还是早上那份旧数据（表现就是「明明已续了还算待对方回复」）。
     */
    private fun pushSnapshotIfStale(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastSnapshotPushMs < SNAPSHOT_PUSH_INTERVAL_MS) return
        lastSnapshotPushMs = now
        pushSnapshotToModule(context, attempts = 3)
    }

    /**
     * 把当前会话快照写回模块 App（就是界面里看到的那份缓存）。
     *
     * IM 没就绪就短退避重试几次；写入走的是 ContentProvider，
     * 所以**不需要用户打开 App**。
     */
    private fun pushSnapshotToModule(
        context: Context,
        attempts: Int = 6,
    ): Boolean {
        wakeModule(context)
        var attempt = 0
        while (true) {
            val outcome = refreshSnapshot(context)
            if (outcome.ok) {
                HostTrace.add(context, "快照已同步到 App：${outcome.detail}")
                return true
            }
            attempt++
            if (attempt >= attempts) {
                HostTrace.add(context, "快照同步失败：${outcome.detail}")
                return false
            }
            runCatching { Thread.sleep(RETRY_DELAY_MS) }
        }
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
        // 脚本对沙盒与宏的改动：这一轮跑完再统一落盘
        val pendingSandbox = mutableListOf<com.worldmargin.autospark.features.autospark.model.SandboxVar>()
        val pendingSandboxDeletes = mutableListOf<Pair<String, String>>()
        val macroOps = mutableListOf<SparkScriptEngine.MacroOp>()

        // 第一遍：只筛选，不发送 —— 先算出这轮「待续」有几个，提示要用它
        val candidates = mutableListOf<JSONObject>()
        for (index in 0 until conversations.length()) {
            val conversation = conversations.optJSONObject(index) ?: continue
            val isGroup = conversation.optBoolean("isGroup")
            val conversationId = conversation.optString("conversationId")
            if (conversationId.isEmpty()) continue
            if (only.isNotEmpty()) {
                // 手动点名：只发这些，不再看抖音判定与手动归属
                if (conversationId !in only) continue
            } else {
                // 群聊默认不参与自动发送：只有被手动移进「已选目标」才发（界面里群聊单独一张卡片）
                if (isGroup && conversationId !in config.forcedRenew) continue
                // 「需要续」来自抖音判定，可被管理界面的手动移动覆盖
                if (conversationId in config.forcedSkip) continue
                val spark = conversation.optJSONObject("spark")
                val needsRenewal =
                    conversationId in config.forcedRenew ||
                        (spark?.optBoolean("hasSpark") == true && spark.optBoolean("needsRenewal"))
                if (!needsRenewal) continue
            }
            candidates += conversation
        }
        HostTrace.add(context, "待续 ${candidates.size} 个")
        if (candidates.isEmpty()) {
            com.worldmargin.autospark.core.DouToastHelper.show(context, "（今日已无需发送）")
            return JSONObject()
                .put("ok", true)
                .put("today", today)
                .put("sent", sent)
                .put("skipped", skipped)
                .put("failed", failed)
        }
        // 待续拆两层：
        //  待发        = 这轮确实要发的（今天还没发）
        //  待对方回复 = 今天已经发过、火花还是灰的（state 2/3）—— 已经互动过的（state=1）不算
        // （手动移进「已选目标」的会议即使已续也在 candidates 里，所以这里要按 state 过滤一遍）
        val waitingReply =
            candidates.count { conv ->
                val spark = conv.optJSONObject("spark")
                config.sentDays[conv.optString("conversationId")] == today &&
                    spark != null &&
                    spark.optInt("state", -1) in 2..3
            }
        val toSend = candidates.count { config.sentDays[it.optString("conversationId")] != today }
        val pendingHint =
            if (waitingReply == 0) {
                "（有 $toSend 个待发）"
            } else {
                "（待发 $toSend 个 · 待对方回复 $waitingReply 个）"
            }
        HostTrace.add(context, "待发 $toSend 个，待对方回复 $waitingReply 个")
        com.worldmargin.autospark.core.DouToastHelper.show(context, pendingHint)

        // 第二遍：真正发送
        for (conversation in candidates) {
            val isGroup = conversation.optBoolean("isGroup")
            val conversationId = conversation.optString("conversationId")
            if (!ignoreSentToday && config.sentDays[conversationId] == today) {
                skipped.put(JSONObject().put("conversationId", conversationId).put("reason", "今日已发送"))
                continue
            }

            val name = displayName(conversation)
            val script = scriptFor(config, conversationId)
            if (script.isEmpty()) {
                // 没写文案、也没挂宏：这个会话确实没东西可发
                skipped.put(JSONObject().put("conversationId", conversationId).put("name", name).put("reason", "没有文案"))
                continue
            }
            val spark = conversation.optJSONObject("spark")
            val rendered =
                runCatching {
                    SparkScriptEngine.renderFull(
                        com.worldmargin.autospark.features.autospark.script.TemplateVarPrelude.of(
                            config.templateVarsFor(conversationId),
                        ) + script,
                        buildVariables(conversation, spark),
                        sandboxEnv(config, conversationId, conversations),
                        templateNameOf(config, conversationId),
                        parentTemplateNameOf(config, conversationId),
                    )
                }
                    .getOrElse { error ->
                        failed.put(
                            JSONObject().put("conversationId", conversationId).put("name", name)
                                .put("error", error.message ?: error.toString()),
                        )
                        continue
                    }
            pendingSandbox.addAll(rendered.sandboxWrites)
            pendingSandboxDeletes.addAll(rendered.sandboxDeletes.map { it.owner to it.key })
            // 脚本没给目标就默认当前会话
            macroOps +=
                rendered.macroOps.map { op -> if (op.targetId.isBlank()) op.copy(targetId = conversationId) else op }
            val text = rendered.text

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

        applyScriptMutations(context, config, pendingSandbox, pendingSandboxDeletes, macroOps)
        val skippedCount = skipped.length()
        com.worldmargin.autospark.core.DouToastHelper.show(
            context,
            "（星火燎原：已发 ${sent.length()} 个" +
                (if (skippedCount > 0) "，跳过 $skippedCount 个" else "") +
                "，失败 ${failed.length()} 个）",
        )

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
                HostTrace.start(context, "已安排：探测 IM 就绪，就绪即发送")
                com.worldmargin.autospark.core.DouToastHelper.show(context, "（星火燎原：正在检测火花状态）")
                runCatching {
                    Thread.sleep(START_DELAY_MS)
                    // 冷启动时 IM 可能还没就绪，会话列表取不到就退避重试
                    var attempt = 0
                    var outcome = refreshSnapshot(context)
                    while (!outcome.ok) {
                        attempt++
                        // 探测很密，每 4 次记一条，避免把宿主轨迹刷满
                        if (attempt % 4 == 0) {
                            HostTrace.add(context, "第 $attempt 次探测未就绪：${outcome.detail}")
                        }
                        if (attempt >= MAX_ATTEMPTS) {
                            HostTrace.add(context, "探测 $MAX_ATTEMPTS 次仍未就绪，本次跳过")
                            ModLog.i("会话列表未就绪，本次不续火花")
                            // 报结果：不能只弹「正在检测」就没下文
                            com.worldmargin.autospark.core.DouToastHelper.show(
                                context,
                                "（星火燎原：IM 未就绪，本次已跳过）",
                            )
                            return@runCatching
                        }
                        Thread.sleep(RETRY_DELAY_MS)
                        outcome = refreshSnapshot(context)
                    }
                    HostTrace.add(context, "IM 已就绪（探测 $attempt 次），快照已写入")
                    markRunDay(context, dayFormat.format(Date()))
                    val summary = runRenew(context, respectEnabled = true)
                    if (summary.optBoolean("ok")) {
                        val line =
                            "续火花结果：已发 ${summary.optJSONArray("sent")?.length() ?: 0} / " +
                                "跳过 ${summary.optJSONArray("skipped")?.length() ?: 0} / " +
                                "失败 ${summary.optJSONArray("failed")?.length() ?: 0}"
                        HostTrace.add(context, line)
                        ModLog.i(line)
                    } else {
                        val reason = summary.optString("error").ifBlank { "未知原因" }
                        HostTrace.add(context, "续火花未执行：$reason")
                        ModLog.i("自动续火花未执行：$reason")
                        com.worldmargin.autospark.core.DouToastHelper.show(context, "（星火燎原：$reason）")
                    }
                }.onFailure {
                    HostTrace.add(context, "执行异常：${it.message ?: it.toString()}")
                    ModLog.e("自动续火花执行异常", it)
                    com.worldmargin.autospark.core.DouToastHelper.show(context, "（星火燎原：执行异常）")
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
