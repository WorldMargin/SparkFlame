package com.worldmargin.autospark.app.ui.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.animation.ObjectAnimator
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import com.worldmargin.autospark.app.config.BuiltinTemplate
import com.worldmargin.autospark.app.config.BuiltinTemplates
import com.worldmargin.autospark.app.config.ConversationSnapshot
import com.worldmargin.autospark.app.config.ConfigCodec
import com.worldmargin.autospark.app.config.ConversationTemplate
import com.worldmargin.autospark.app.config.MacroScript
import com.worldmargin.autospark.app.config.SparkConfig
import com.worldmargin.autospark.app.config.SparkTag
import com.worldmargin.autospark.app.config.TransferSelection
import com.worldmargin.autospark.app.config.fullSelection
import com.worldmargin.autospark.app.config.mergeFrom
import com.worldmargin.autospark.app.config.subsetOf
import com.worldmargin.autospark.app.config.withDependencies
import com.worldmargin.autospark.app.config.TemplateGroup
import com.worldmargin.autospark.app.config.TemplateOps
import com.worldmargin.autospark.app.config.TemplateSource
import com.worldmargin.autospark.app.config.UserFunction
import com.worldmargin.autospark.app.provider.ConfigContract
import com.worldmargin.autospark.app.script.PreviewData
import com.worldmargin.autospark.app.script.SparkScriptEngine
import com.worldmargin.autospark.app.config.ConfigStore
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_ACCENT
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_BG
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_CARD
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_DIM
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_TEXT
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_WARN
import com.worldmargin.autospark.app.ui.common.UiKit.asClickable
import com.worldmargin.autospark.app.ui.common.UiKit.actionRow
import com.worldmargin.autospark.app.ui.common.UiKit.button
import com.worldmargin.autospark.app.ui.common.UiKit.animateFold
import com.worldmargin.autospark.app.ui.common.UiKit.bottomBar
import com.worldmargin.autospark.app.ui.common.UiKit.card
import com.worldmargin.autospark.app.ui.common.UiKit.column
import com.worldmargin.autospark.app.ui.common.UiKit.divider
import com.worldmargin.autospark.app.ui.common.UiKit.dp
import com.worldmargin.autospark.app.ui.common.UiKit.enterFromLeft
import com.worldmargin.autospark.app.ui.common.UiKit.enterFromRight
import com.worldmargin.autospark.app.ui.common.UiKit.flatRow
import com.worldmargin.autospark.app.ui.common.UiKit.icon
import com.worldmargin.autospark.app.ui.common.UiKit.iconButton
import com.worldmargin.autospark.app.ui.common.UiKit.linkRow
import com.worldmargin.autospark.app.ui.common.UiKit.menuRow
import com.worldmargin.autospark.app.ui.common.UiKit.navBar
import com.worldmargin.autospark.app.ui.common.UiKit.padSystemBars
import com.worldmargin.autospark.app.ui.common.UiKit.rounded
import com.worldmargin.autospark.app.ui.common.UiKit.row
import com.worldmargin.autospark.app.ui.common.UiKit.scroll
import com.worldmargin.autospark.app.ui.common.UiKit.sectionCard
import com.worldmargin.autospark.app.ui.common.UiKit.filterGroups
import com.worldmargin.autospark.app.ui.common.UiKit.searchRow
import com.worldmargin.autospark.app.ui.common.UiKit.sectionTitle
import com.worldmargin.autospark.app.ui.common.UiKit.segmented
import com.worldmargin.autospark.app.ui.common.UiKit.spacer
import com.worldmargin.autospark.app.ui.common.UiKit.switchRow
import com.worldmargin.autospark.app.ui.common.UiKit.text
import com.worldmargin.autospark.app.ui.common.UiKit.textContent
import com.worldmargin.autospark.app.ui.common.UiKit.titleBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.worldmargin.autospark.app.config.TemplateVar
import com.worldmargin.autospark.app.script.TemplateVarPrelude
import com.worldmargin.autospark.app.ui.common.AvatarLoader
import com.worldmargin.autospark.app.ui.conversation.ConversationEditActivity
import com.worldmargin.autospark.app.ui.conversation.ConversationPickerActivity
import com.worldmargin.autospark.app.ui.function.FunctionEditActivity
import com.worldmargin.autospark.app.ui.macro.MacroEditActivity
import com.worldmargin.autospark.app.ui.template.TemplateEditActivity
import com.worldmargin.autospark.app.ui.variable.VariableListActivity
import com.worldmargin.autospark.app.ui.common.UiKit

/**
 * 续火花管理主界面（运行在模块进程）。
 *
 * 底部五个标签：续火花 / 模板 / 标签 / 宏/函数 / 设置；
 * 「续火花」标签内再分「已选目标」（会续火花）/「未选目标」两页。
 */
class SparkSettingsActivity : Activity() {
    private lateinit var headerHost: LinearLayout
    private lateinit var pageHost: LinearLayout
    private lateinit var navHost: LinearLayout

    /** 当前顶层标签下标。 */
    private var tab = TAB_SPARK

    /** 「续火花」标签内的分页下标。 */
    private var sparkPage = PAGE_SELECTED

    /** 「宏/函数」标签内的分页下标（宏 / 函数）。 */
    private var functionsPage = PAGE_MACROS

    /** 模板页里当前展开的一级模板 id（展开后显示它的派生模板）。 */
    private var expandedTemplateId: String? = null

    /** 模板页两个分段的折叠状态（内置模板默认折叠，页面清爽一些）。 */
    private var builtinSectionOpen = false
    private var customSectionOpen = true

    /** 当前打开的设置二级页面（null = 正常标签页）。 */
    private var subPage: String? = null

    /** 上一次渲染时的二级页面，用来决定转场方向。 */
    private var lastRenderedSub: String? = null

    /** 正在等宿主回传手动发送结果（用于超时提示）。 */
    private var awaitingSendResult = false

    /** 待写入文件的配置 JSON（导出走系统文件选择，回来才写）。 */
    private var pendingExport = ""

    /** 从派生项长按进来时，变量页回来后要写回哪个自定义模板。 */
    private var pendingVarsGroup: String? = null

    /** 同上，要写回哪个会话自己的变量。 */
    private var pendingVarsConversation: String? = null

    /** 会话选择页回来后要写的模板（继承 / 克隆用）。 */
    private var pendingPickTemplateId: String? = null
    private var pendingPickTemplateName = ""
    private var pendingCloneName = ""
    private var pendingCloneBody = ""

    /** 标签页：正在为哪个标签选择会话。 */
    private var pendingTagId: String? = null

    /** 导出页勾选的条目；[exportExcluded] 记录用户手动取消过的（依赖自动勾选不再加回来）。 */
    private var exportSelection = TransferSelection()
    private var exportExcluded = emptySet<String>()

    /** 导入页：选中文件解析出的配置 + 勾选。 */
    private var importSource: SparkConfig? = null
    private var importSelection = TransferSelection()

    /** 页面上的勾选框，按「类型:ID」索引，自动勾选后用来同步界面。 */
    private val transferChecks = mutableMapOf<String, CheckBox>()

    /** 页面上的标签行，用来就地刷新「强制导出 / 可取消」状态，避免整页重绘。 */
    private val transferTagRows = mutableMapOf<String, TagRowView>()

    /** 导出 / 导入页会话列表的搜索与单聊群聊筛选。 */
    private var exportQuery = ""
    private var exportType = 0
    private var importQuery = ""
    private var importType = 0

    /** 等宿主探活回执的回调（同一时刻只会有一次探活）。 */
    private var hostAliveWaiter: ((Boolean) -> Unit)? = null

    /** 「续火花」页的搜索关键字（重建页面后恢复用）。 */
    private var sparkQuery = ""

    /** 续火花页的标记筛选：标签可多选（并集），来源单选（null = 不筛；来源用 [SRC_NONE] 表示「未写文案」）。 */
    private val sparkTagFilter = linkedSetOf<String>()
    private var sparkSourceFilter: String? = null

    /** 模板页 / 宏函数页的搜索关键字。 */
    private var templateQuery = ""
    private var macroQuery = ""

    /** 「续火花」页两个分组卡片的折叠状态。 */
    private var singlesCollapsed = false
    private var groupsCollapsed = false

    /** 「被隐藏」卡片的折叠状态（默认收起，不占地方）。 */
    private var hiddenCollapsed = true

    /** 多选中的会话 ID。 */
    private val selected = linkedSetOf<String>()

    /** 「续火花」页里会话行 → 勾选框，用于原地同步勾选状态。 */
    private val rowChecks = mutableMapOf<String, CheckBox>()

    /** 模板卡片里可折叠的那块（派生列表 + 操作行）与它的三角图标：点击只动这块的高度，不重建页面。 */
    private val templateFolds = mutableMapOf<String, Pair<View, TextView>>()

    /** 会话行视图：勾选后给整行加底色，免得"选中了但看不出来"。 */
    private val rowViews = mutableMapOf<String, View>()

    private var selectionBar: View? = null
    private var selectionCount: TextView? = null

    /** 勾选行的底色（淡蓝，和卡片底色区分得开又不刺眼）。 */
    private val COLOR_SELECTED = 0x333B7CFF

    /** 顶部「发送…」按钮的文字：勾选数一变化就要跟着改，不能等重建页面。 */
    private var sendLabel: TextView? = null
    private var renewalCount = 0

    private val snapshotReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == ConfigContract.ACTION_HOST_ALIVE) {
                    hostAliveWaiter?.let { waiter ->
                        hostAliveWaiter = null
                        waiter(true)
                    }
                    return
                }
                if (intent?.action == ConfigContract.ACTION_SEND_DONE) {
                    awaitingSendResult = false
                    val summary = intent.getStringExtra(ConfigContract.EXTRA_SUMMARY).orEmpty()
                    render()
                    AlertDialog
                        .Builder(this@SparkSettingsActivity)
                        .setTitle("发送结果")
                        .setMessage(summary.ifBlank { "宿主没有返回结果" })
                        .setPositiveButton("好", null)
                        .show()
                    return
                }
                render()
                Toast.makeText(this@SparkSettingsActivity, "会话已刷新", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val requested = intent?.getIntExtra(EXTRA_TAB, -1) ?: -1
        if (requested in TAB_SPARK..TAB_SETTINGS) {
            tab = requested
            subPage = null
            selected.clear()
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = intent.getIntExtra(EXTRA_TAB, TAB_SPARK).coerceIn(TAB_SPARK, TAB_SETTINGS)
        pageHost = column()
        navHost = column()
        headerHost = column()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(COLOR_BG)
                addView(headerHost)
                addView(divider())
                addView(pageHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })
                addView(divider())
                addView(navHost)
            }
        root.padSystemBars()
        setContentView(root)
        installGuideBubble()
    }

    override fun onResume() {
        super.onResume()
        registerSnapshotReceiver()
        render()
    }

    @Deprecated("系统返回键：二级页面优先回上一级")
    override fun onBackPressed() {
        if (subPage != null) {
            subPage = null
            render()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** 导出/导入配置、派生项变量页的回调。 */
    @Deprecated("文件选择 / 变量页结果")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_GROUP_VARS || requestCode == REQUEST_CONVERSATION_VARS) {
            val groupId = pendingVarsGroup
            val conversationId = pendingVarsConversation
            pendingVarsGroup = null
            pendingVarsConversation = null
            if (resultCode != RESULT_OK || data == null) return
            val raw = data.getStringExtra(VariableListActivity.RESULT_VARS) ?: return
            val vars =
                runCatching { ConfigCodec.decodeVars(org.json.JSONArray(raw)) }
                    .getOrDefault(emptyList())
            val now = System.currentTimeMillis()
            when {
                groupId != null ->
                    ConfigStore.update(this) { config ->
                        config.copy(
                            groups =
                                config.groups.map { group ->
                                    if (group.id == groupId) group.copy(variables = vars, updatedAt = now) else group
                                },
                        )
                    }
                conversationId != null ->
                    ConfigStore.update(this) { config ->
                        config.copy(
                            conversations =
                                config.conversations.map { template ->
                                    if (template.conversationId == conversationId) {
                                        template.copy(variables = vars, updatedAt = now)
                                    } else {
                                        template
                                    }
                                },
                        )
                    }
                else -> return
            }
            Toast.makeText(this, "已保存 ${vars.size} 个变量", Toast.LENGTH_SHORT).show()
            render()
            return
        }
        if (requestCode == REQUEST_PICK_MEMBERS) {
            if (resultCode == RESULT_OK) applyPickedMembers(data)
            return
        }
        if (requestCode == REQUEST_PICK_CLONE) {
            if (resultCode == RESULT_OK) applyClone(data)
            return
        }
        if (requestCode == REQUEST_TAG_MEMBERS) {
            if (resultCode == RESULT_OK) applyTagMembership(data)
            return
        }
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT_CONFIG -> {
                val ok =
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { it.write(pendingExport.toByteArray()) } != null
                    }.getOrDefault(false)
                Toast.makeText(this, if (ok) "配置已导出" else "导出失败", Toast.LENGTH_SHORT).show()
            }

            REQUEST_IMPORT_CONFIG -> loadImportSource(uri)
        }
    }

    override fun onPause() {
        super.onPause()
        stopGuideBreathing()
        runCatching { unregisterReceiver(snapshotReceiver) }
    }

    private fun registerSnapshotReceiver() {
        val filter = IntentFilter().apply {
            addAction(ConfigContract.ACTION_SNAPSHOT_UPDATED)
            addAction(ConfigContract.ACTION_SEND_DONE)
            addAction(ConfigContract.ACTION_HOST_ALIVE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(snapshotReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(snapshotReceiver, filter)
        }
    }

    /** 手动发送前确认一次（避免误点直接群发）。 */
    private fun confirmManualSend(targets: List<String>) {
        if (targets.isEmpty()) {
            Toast.makeText(this, "没有可发送的会话：先去「已选目标」勾几个，或等抖音判定出待续会话", Toast.LENGTH_SHORT).show()
            return
        }
        val config = ConfigStore.load(this)
        val names =
            targets.mapNotNull { id ->
                config.snapshots.firstOrNull { it.conversationId == id }?.displayName?.takeIf { it.isNotBlank() }
            }
        val preview = if (names.isEmpty()) "" else "\n\n" + names.take(8).joinToString("、") + if (names.size > 8) " 等 ${names.size} 个" else ""
        AlertDialog
            .Builder(this)
            .setTitle("立即发送给 ${targets.size} 个会话？")
            .setMessage("手动发送不受总开关与「今日已发」限制；没选模板的会话会被跳过。" + preview)
            .setPositiveButton("发送") { _, _ -> sendNow(targets) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把发送请求发给抖音进程（消息发送能力在宿主里）。 */
    private fun sendNow(targets: List<String>) {
        withHostOnline {
            awaitingSendResult = true
            sendBroadcast(
                Intent(ConfigContract.ACTION_SEND_NOW)
                    .putStringArrayListExtra(ConfigContract.EXTRA_TARGETS, ArrayList(targets)),
            )
            Toast.makeText(this, "已请求发送，等待抖音返回结果…", Toast.LENGTH_SHORT).show()
            pageHost.postDelayed({
                if (awaitingSendResult) {
                    awaitingSendResult = false
                    Toast.makeText(this, "没等到结果：抖音可能刚被系统冻结，稍后重试", Toast.LENGTH_LONG).show()
                }
            }, SEND_TIMEOUT_MS)
        }
    }

    // ------------------------------------------------------------------ 握手机制

    /**
     * 确认宿主（抖音进程）在线后再执行 [action]。
     *
     * 抖音没运行时，发给它的广播没人接，表现就是「点了没反应」（手动续火花尤其明显）。
     * 这里先看存活时间戳，过期就发探活广播、等回执；还是没回执就引导用户打开抖音，
     * 打开后再探一次，成功就自动把原来那件事做掉。
     */
    private fun withHostOnline(action: () -> Unit) {
        val config = ConfigStore.load(this)
        val fresh = System.currentTimeMillis() - config.hostAliveAt < HOST_FRESH_MS
        if (fresh) {
            action()
            return
        }
        Toast.makeText(this, "正在确认抖音是否在运行…", Toast.LENGTH_SHORT).show()
        waitHostAlive(HOST_PING_MS) { alive ->
            if (alive) {
                action()
                return@waitHostAlive
            }
            promptOpenHost { opened ->
                if (!opened) return@promptOpenHost
                // 抖音冷启动要几秒才把模块装载完，稍等再探一次
                pageHost.postDelayed({
                    waitHostAlive(HOST_PING_MS * 3) { aliveAfterOpen ->
                        if (aliveAfterOpen) {
                            action()
                        } else {
                            Toast
                                .makeText(this, "抖音已打开但还没就绪，请稍后再点一次", Toast.LENGTH_LONG)
                                .show()
                        }
                    }
                }, HOST_BOOT_WAIT_MS)
            }
        }
    }

    /** 发探活广播并在 [timeoutMs] 内等回执。 */
    private fun waitHostAlive(
        timeoutMs: Long,
        callback: (Boolean) -> Unit,
    ) {
        hostAliveWaiter = callback
        sendBroadcast(Intent(ConfigContract.ACTION_PING_HOST))
        pageHost.postDelayed({
            hostAliveWaiter?.let { waiter ->
                hostAliveWaiter = null
                waiter(false)
            }
        }, timeoutMs)
    }

    /** 提示用户先打开抖音（宿主不在，发什么都不生效）。 */
    private fun promptOpenHost(callback: (Boolean) -> Unit) {
        AlertDialog
            .Builder(this)
            .setTitle("抖音没在运行")
            .setMessage("续火花要在抖音进程里执行。现在打开抖音，等它就绪后会自动继续刚才的操作。")
            .setPositiveButton("打开抖音") { _, _ ->
                val launched = openDouyin()
                if (launched) {
                    Toast.makeText(this, "正在打开抖音，稍等几秒会自动继续…", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "没找到抖音，请手动打开", Toast.LENGTH_SHORT).show()
                }
                callback(launched)
            }.setNegativeButton("取消") { _, _ -> callback(false) }
            .show()
    }

    /** 用启动 Intent 打开抖音（拿不到就返回 false）。 */
    private fun openDouyin(): Boolean =
        runCatching {
            val intent = packageManager.getLaunchIntentForPackage(DOUYIN_PACKAGE) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        }.getOrDefault(false)

    private fun render() {
        val config = ConfigStore.load(this)
        // 页面重建时先停掉旧控件的呼吸动画（新页面会按需重新开始）
        stopGuideBreathing()
        // 页面重建时关掉浮层，避免它停在旧内容上
        guidePopup?.dismiss()
        // 二级页面（设置里的各项）在同一个 Activity 内切换，带转场动画
        val sub = subPage
        val enteringFromSub = lastRenderedSub == null && sub != null
        val leavingSub = lastRenderedSub != null && sub == null
        lastRenderedSub = sub

        headerHost.removeAllViews()
        headerHost.addView(
            if (sub == null) {
                titleBar(TABS[tab])
            } else {
                titleBar(SUB_PAGES[sub] ?: "") {
                    subPage = null
                    render()
                }
            },
        )

        navHost.removeAllViews()
        if (sub == null) {
            navHost.addView(
                navBar(TAB_ICONS, TABS, tab) { index ->
                    if (index != tab) {
                        tab = index
                        selected.clear()
                        render()
                    }
                },
            )
        }

        pageHost.removeAllViews()
        val page =
            if (sub != null) {
                buildSubPage(config, sub)
            } else {
                when (tab) {
                    TAB_SPARK -> buildSparkPage(config)
                    TAB_TEMPLATE -> buildTemplatesPage(config)
                    TAB_TAG -> buildTagPage(config)
                    TAB_FUNCTIONS -> buildFunctionsPage(config)
                    else -> buildSettingsPage(config)
                }
            }
        pageHost.addView(page, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })
        when {
            enteringFromSub -> page.enterFromRight()
            leavingSub -> page.enterFromLeft()
            else -> page.enterFromRight()
        }
        refreshGuideBubble(config)
    }

    private fun openSubPage(key: String) {
        subPage = key
        render()
    }

    // -------------------------------------------------------------- 设置二级页

    /** 设置里的二级页面：内容都从设置页搬过来，设置页只留入口。 */
    private fun buildSubPage(
        config: SparkConfig,
        key: String,
    ): View {
        val body = column().apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        when (key) {
            SUB_OVERVIEW -> {
                val conversations = config.snapshots.filter { !it.isGroup && !it.isHidden }
                val renewal = conversations.filter { config.needsRenewal(it) }
                val today = todayText()
                val pending = renewal.filter { it.state == SPARK_PENDING }
                val waitingReply = pending.count { config.sentDays[it.conversationId] == today }
                val toSend = pending.size - waitingReply
                body.addView(infoCard("会话总数", conversations.size.toString()))
                body.addView(infoCard("待发", toSend.toString()))
                body.addView(infoCard("待对方回复", waitingReply.toString()))
                body.addView(infoCard("待重燃", renewal.count { it.state == SPARK_RECOVER }.toString()))
                body.addView(infoCard("今日已续", conversations.count { it.state == SPARK_DONE }.toString()))
                body.addView(infoCard("已选目标", config.selectedIds().size.toString()))
                body.addView(
                    infoCard(
                        "快照时间",
                        if (conversations.isEmpty()) {
                            "还没有快照"
                        } else {
                            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                .format(Date(conversations.maxOf { it.updatedAt }))
                        },
                    ),
                )
                body.addView(infoCard("今日已发", sentToday(config).toString()))
            }

            SUB_ASSIGN -> {
                body.addView(
                    card().apply {
                        addView(text("手动归属", 16f, bold = true))
                        addView(
                            text(
                                "手动移入「要续」的会话 ${config.forcedRenew.size} 个，" +
                                    "手动移入「不用续」的 ${config.forcedSkip.size} 个。没有手动标记的会话按抖音状态自动判定。",
                                13f,
                                COLOR_DIM,
                            ),
                        )
                        addView(
                            row().apply {
                                setPadding(0, dp(8), 0, 0)
                                addView(
                                    iconButton(UiKit.Icons.CLEAR_ALL, "清除手动标记", compact = true) {
                                        ConfigStore.update(this@SparkSettingsActivity) {
                                            it.copy(forcedRenew = emptySet(), forcedSkip = emptySet()).pruneTemplates(System.currentTimeMillis())
                                        }
                                        Toast.makeText(this@SparkSettingsActivity, "已清除手动标记", Toast.LENGTH_SHORT).show()
                                        render()
                                    },
                                )
                            },
                        )
                    },
                )
            }

            SUB_HOST -> {
                body.addView(
                    card().apply {
                        addView(
                            text(
                                config.hostNote.ifBlank {
                                    "宿主还没上报过：模块可能没注入抖音，或抖音本次启动后还没到扫描时间。"
                                },
                                12f,
                                COLOR_DIM,
                            ),
                        )
                    },
                )
            }

            SUB_GUIDE -> {
                val basic = basicSteps(config)
                val advanced = advancedSteps(config)
                body.addView(
                    card().apply {
                        addView(text("悬浮引导", 16f, bold = true))
                        addView(
                            text(
                                "右下角胶囊可以拖动，点开是浮层、不占页面。当前基础 " +
                                    "${basic.count { it.done }}/${basic.size} · 进阶 ${advanced.count { it.done }}/${advanced.size}。",
                                12f,
                                COLOR_DIM,
                            ),
                        )
                    },
                )
                body.addView(
                    menuRow(UiKit.Icons.LIGHTBULB, "预览引导", "打开浮层，看当前进度") {
                        guidePreview = true
                        ConfigStore.update(this) { it.copy(guideDismissed = false) }
                        tab = TAB_SPARK
                        subPage = null
                        render()
                        showGuidePopup()
                    },
                )
                body.addView(
                    menuRow(UiKit.Icons.CLOSE, "隐藏引导", "收起胶囊；之后都在这里重新打开") {
                        guidePreview = false
                        ConfigStore.update(this) { it.copy(guideDismissed = true) }
                        subPage = null
                        render()
                    },
                )
            }

            SUB_EXPORT -> buildExportPage(config, body)

            SUB_IMPORT -> buildImportPage(config, body)

            SUB_CONFIG -> {
                body.addView(
                    card().apply {
                        addView(text("按项导出 / 导入", 16f, bold = true))
                        addView(
                            text(
                                "都能逐项勾选：模板、会话文案、宏、自定义函数，" +
                                    "外加整类的会话快照与今日已发记录。导入是合并，没勾的部分不动。",
                                12f,
                                COLOR_DIM,
                            ),
                        )
                    },
                )
                body.addView(
                    menuRow(UiKit.Icons.DOWNLOAD, "导出配置", "挑要带走的模板 / 宏 / 函数") {
                        exportSelection = TransferSelection()
                        exportExcluded = emptySet()
                        transferChecks.clear()
                        openSubPage(SUB_EXPORT)
                    },
                )
                body.addView(
                    menuRow(UiKit.Icons.IMPORT, "导入配置", "选文件，再挑要合并进来的条目") {
                        importSource = null
                        transferChecks.clear()
                        openSubPage(SUB_IMPORT)
                    },
                )
                body.addView(
                    card().apply {
                        addView(text("当前配置", 14f, bold = true))
                        addView(infoLine("模板", "${config.groups.size} 个"))
                        addView(infoLine("会话文案", "${config.conversations.size} 条"))
                        addView(infoLine("宏", "${config.macros.size} 个"))
                        addView(infoLine("自定义函数", "${config.functions.size} 个"))
                        addView(
                            infoLine(
                                "会话快照",
                                "${config.snapshots.count { !it.isHidden }} 条" +
                                    config.snapshots.count { it.isHidden }.let { hidden ->
                                        if (hidden > 0) "（另有 $hidden 条已隐藏）" else ""
                                    },
                            ),
                        )
                    },
                )
            }

            else -> {
                body.addView(
                    card().apply {
                        addView(infoLine("版本", versionName()))
                        addView(text("配置与快照", 14f, COLOR_TEXT))
                        addView(
                            text(
                                "/data/data/com.worldmargin.autospark/files/spark_config.json",
                                12f,
                                COLOR_DIM,
                            ),
                        )
                        addView(text("每次刷新会话都会重扫；总开关关闭时只扫描不发送。", 12f, COLOR_DIM))
                    },
                )
                body.addView(sectionTitle("署名与来源（点一下可跳转）"))
                body.addView(
                    linkRow(
                        title = "原仓库 · yfishyon/doumcp",
                        url = "https://github.com/yfishyon/doumcp",
                        desc = "抖音逆向与注入能力的基础（Apache-2.0）",
                    ),
                )
                body.addView(
                    linkRow(
                        title = "重写版 · WorldMargin/SparkFlame",
                        url = "https://gitee.com/WorldMargin/SparkFlame",
                        desc = "本模块：模板 / 宏 / 函数 + Monaco 编辑器",
                    ),
                )
                body.addView(
                    linkRow(
                        title = "WorldMargin",
                        url = "https://gitee.com/WorldMargin",
                        desc = "模板 / 宏 / 函数 这套思路，以及 DouYinSpark 油猴脚本原作",
                    ),
                )
                body.addView(
                    linkRow(
                        title = "DeepSeek",
                        url = "https://www.deepseek.com",
                        desc = "把 DouYinSpark 油猴脚本的完整逻辑迁移至安卓",
                    ),
                )
                body.addView(
                    text(
                        "本模块 fork 自 doumcp（Apache-2.0 许可），只保留「续火花」功能；" +
                            "界面、脚本引擎、模板与宏体系为重写。",
                        12f,
                        COLOR_DIM,
                    ).apply { setPadding(dp(2), dp(8), 0, 0) },
                )
            }
        }
        return column().apply { addView(scroll().apply { addView(body) }) }
    }

    /** 二级页里的一行「名称 —— 值」卡片。 */
    private fun infoCard(
        label: String,
        value: String,
    ): View =
        card().apply {
            addView(
                row().apply {
                    addView(text(label, 14f, COLOR_DIM))
                    addView(spacer())
                    addView(text(value, 15f, COLOR_TEXT, bold = true))
                },
            )
        }

    // ------------------------------------------------------------------ 续火花

    /** 新手引导的一步：状态、标题、提示，以及可选的「去完成」入口。 */
    private data class GuideStep(
        val done: Boolean,
        val title: String,
        val hint: String,
        val action: String? = null,
        /** 前面的步骤还没完成时锁住，必须先按顺序来。 */
        val locked: Boolean = false,
        val onClick: (() -> Unit)? = null,
    )

    /** 按顺序锁定：前一步没完成，后面的步骤都不可点。 */
    private fun List<GuideStep>.sequential(): List<GuideStep> {
        var blocked = false
        return map { step ->
            val locked = blocked
            if (!step.done) blocked = true
            step.copy(locked = locked)
        }
    }

    /** 基础路线：注入、刷新、选会话、套内置模板、发出去。 */
    private fun basicSteps(config: SparkConfig): List<GuideStep> {
        val injected = config.hostAliveAt > 0L
        val synced = config.snapshots.isNotEmpty()
        val targeted = config.selectedIds().isNotEmpty() || config.snapshots.any { !it.isHidden && config.needsRenewal(it) }
        val templated = config.conversations.any { it.inheritFrom != null && it.inheritFrom != SparkConfig.BUILTIN_BLANK }
        val sent = config.sentDays.isNotEmpty()
        return listOf(
            GuideStep(injected, "模块已注入抖音", "在 LSPosed 里勾选抖音并重启，再打开抖音", "看说明") { openDocs() },
            GuideStep(synced, "刷新出会话", "打开一次抖音，等它把会话写回来", "去刷新") {
                tab = TAB_SETTINGS
                render()
                breatheGuideTarget("刷新会话")
            },
            GuideStep(targeted, "选中要续的会话", "把会话移进「已选目标」，或勾选后手动发送", null),
            GuideStep(templated, "给会话套模板", "展开一个内置模板，点「选会话继承」套给会话", "去模板") {
                openGuideTemplates()
            },
            GuideStep(sent, "发出去一次", "回抖音等自动发送，或在 App 内手动发送看回执", null),
        ).sequential()
    }

    /** 进阶路线：标签、搜索、多模板、文档给 AI、自定义模板、宏。 */
    private fun advancedSteps(config: SparkConfig): List<GuideStep> {
        val tagged = config.tags.isNotEmpty()
        val assigned = config.conversationTags.values.any { it.isNotEmpty() }
        val searched = config.guideSearched
        val distinctTemplates = config.conversations.mapNotNull { it.inheritFrom }.filter { it != SparkConfig.BUILTIN_BLANK }.distinct().size
        val multiTemplate = distinctTemplates >= 2
        val shared = config.guideShared
        val customTemplate = config.groups.any { !it.id.startsWith(SparkConfig.BUILTIN_PREFIX) }
        val hasMacro = config.macros.isNotEmpty()
        val mounted = config.macros.any { it.appliesTo.isNotEmpty() || it.templateOnly.isNotEmpty() }
        return listOf(
            GuideStep(tagged, "创建标签", "在「标签」页新增一个标签，比如家人 / 同学", "去标签") {
                tab = TAB_TAG
                render()
                breatheGuideTarget("新增标签")
            },
            GuideStep(assigned, "给会话打标签", "点标签卡片配置目标，之后可按标签筛选", "去标签页") {
                tab = TAB_TAG
                render()
            },
            GuideStep(searched, "用搜索找会话", "在续火花页顶部按昵称 / 备注搜一次", "去搜索") {
                tab = TAB_SPARK
                render()
                breatheGuideTarget("guide_search")
            },
            GuideStep(multiTemplate, "不同会话用不同模板", "用「选会话继承」让至少两个会话继承不同的模板", "去模板") {
                openGuideTemplates()
            },
            GuideStep(shared, "把文档导出 / 分享给 AI", "文档页可复制全文、导出 .md 或分享", "去文档") { openDocs() },
            GuideStep(customTemplate, "创建自定义模板", "在「模板」页新建一个自己的模板", "去模板") {
                tab = TAB_TEMPLATE
                render()
                breatheGuideTarget("新增模板")
            },
            GuideStep(hasMacro, "创建一个宏", "「宏/函数」页新增宏，给文案追加一句", "去宏") {
                tab = TAB_FUNCTIONS
                render()
                breatheGuideTarget("新增宏")
            },
            GuideStep(mounted, "把宏挂到模板或会话", "编辑宏时挂载到模板或会话，发送时生效", "去挂载") {
                tab = TAB_FUNCTIONS
                render()
            },
        ).sequential()
    }

    /** 当前路线：0 基础，1 进阶。基础走完自动切到进阶。 */
    private var guideTrack = 0

    /** 模板步骤：内置模板行 / 展开后的按钮呼吸，直到用户点中。 */
    private var guideTemplateBreathing = false
    private var guideTrackPinned = false
    private var guidePreview = false

    /** 引导用：本进程内只写一次「用过搜索」。 */
    private var guideSearchMarked = false

    private fun markGuideSearched(query: String) {
        if (query.isBlank() || guideSearchMarked) return
        guideSearchMarked = true
        ConfigStore.update(this) { it.copy(guideSearched = true) }
    }

    /**
     * 新手引导卡片：5 步按真实状态自动打勾，不靠用户自己记。
     *
     * 只看本地已有数据（宿主是否上报、快照、目标、文案、发送记录），不额外请求宿主；
     * 收起状态存进配置，设置里可以重新打开。
     */
    /**
     * 新手引导卡片：基础 / 进阶两条路线，按真实状态自动打勾。
     *
     * 只看本地已有数据（宿主是否上报、快照、目标、模板、标签、宏、发送记录），不额外请求宿主；
     * 收起状态存进配置，设置里可以重新打开。
     */
    private fun buildGuideCard(config: SparkConfig): View {
        val basic = basicSteps(config)
        val advanced = advancedSteps(config)
        val basicDone = basic.count { it.done }
        val advancedDone = advanced.count { it.done }
        if (!guideTrackPinned && guideTrack == 0 && basicDone == basic.size && advancedDone < advanced.size) guideTrack = 1
        val steps = if (guideTrack == 0) basic else advanced
        val done = steps.count { it.done }

        return column().apply {
            background = rounded(COLOR_CARD, 12)
            setPadding(dp(14), dp(11), dp(14), dp(9))
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) }

            addView(
                row().apply {
                    addView(text("新手引导", 14f, COLOR_TEXT, bold = true))
                    addView(spacer())
                    addView(
                        text("最小化", 11f, COLOR_DIM).apply {
                            isClickable = true
                            setOnClickListener { guidePopup?.dismiss() }
                        },
                    )
                },
            )

            addView(
                segmented(
                    listOf("基础 $basicDone/${basic.size}", "进阶 $advancedDone/${advanced.size}"),
                    guideTrack,
                ) { index ->
                    if (index == 1 && basicDone < basic.size) {
                        Toast.makeText(this@SparkSettingsActivity, "先完成基础 ${basic.size} 步", Toast.LENGTH_SHORT).show()
                    } else {
                        guideTrack = index
                        guideTrackPinned = true
                        render()
                    }
                }.apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(8) }
                },
            )

            addView(
                row().apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(5),
                        ).apply {
                            topMargin = dp(8)
                            bottomMargin = dp(4)
                        }
                    background = rounded(0x1A000000, 3)
                    if (done > 0) {
                        addView(
                            View(this@SparkSettingsActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(0, dp(5), done.toFloat())
                                background = rounded(COLOR_ACCENT, 3)
                            },
                        )
                    }
                    if (done < steps.size) {
                        addView(
                            View(this@SparkSettingsActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(0, dp(5), (steps.size - done).toFloat())
                            },
                        )
                    }
                },
            )

            steps.forEach { step ->
                addView(
                    row().apply {
                        setPadding(0, dp(4), 0, dp(4))
                        val marker = when { step.done -> "✓"; step.locked -> "·"; else -> "○" }
                        val markerBg = when {
                            step.done -> COLOR_ACCENT
                            step.locked -> 0x0F000000
                            else -> 0x1A000000
                        }
                        addView(
                            text(marker, 10f, if (step.done) 0xFFFFFFFF.toInt() else COLOR_DIM, bold = true).apply {
                                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
                                gravity = android.view.Gravity.CENTER
                                background = rounded(markerBg, 9)
                            },
                        )
                        addView(
                            column().apply {
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                addView(text(step.title, 12f, if (step.done || step.locked) COLOR_DIM else COLOR_TEXT, bold = !step.done && !step.locked))
                                if (!step.done) {
                                    addView(text(if (step.locked) "完成上一步后解锁" else step.hint, 9.5f, COLOR_DIM))
                                }
                            },
                        )
                        if (!step.done && !step.locked && step.action != null) {
                            addView(
                                text(step.action, 10.5f, COLOR_ACCENT).apply {
                                    isClickable = true
                                    setOnClickListener { step.onClick?.invoke() }
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    /** 悬浮引导：右下角小胶囊，点开是浮层卡片，不占页面布局。 */
    private var guideBubble: TextView? = null
    private var guidePopup: PopupWindow? = null

    private var bubbleDownX = 0f
    private var bubbleDownY = 0f
    private var bubbleStartRight = 0
    private var bubbleStartBottom = 0
    private var bubbleMoved = false

    private fun installGuideBubble() {
        val bubble =
            text("引导", 12f, 0xFFFFFFFF.toInt(), bold = true).apply {
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = rounded(COLOR_ACCENT, 18)
                elevation = dp(6).toFloat()
                isClickable = true
                visibility = View.GONE
                val slop = android.view.ViewConfiguration.get(this@SparkSettingsActivity).scaledTouchSlop
                setOnTouchListener { v, event ->
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            bubbleDownX = event.rawX
                            bubbleDownY = event.rawY
                            bubbleStartRight = lp.rightMargin
                            bubbleStartBottom = lp.bottomMargin
                            bubbleMoved = false
                            true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - bubbleDownX).toInt()
                            val dy = (event.rawY - bubbleDownY).toInt()
                            if (abs(dx) > slop || abs(dy) > slop) bubbleMoved = true
                            val parent = v.parent as? View
                            if (parent != null) {
                                val maxRight = (parent.width - v.width - dp(4)).coerceAtLeast(dp(4))
                                val maxBottom = (parent.height - v.height - dp(4)).coerceAtLeast(dp(4))
                                lp.rightMargin = (bubbleStartRight - dx).coerceIn(dp(4), maxRight)
                                lp.bottomMargin = (bubbleStartBottom - dy).coerceIn(dp(4), maxBottom)
                                v.layoutParams = lp
                            }
                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            val dx = abs(event.rawX - bubbleDownX)
                            val dy = abs(event.rawY - bubbleDownY)
                            if (!bubbleMoved && dx < slop && dy < slop) v.performClick()
                            true
                        }

                        MotionEvent.ACTION_CANCEL -> true

                        else -> false
                    }
                }
                setOnClickListener { showGuidePopup() }
            }
        guideBubble = bubble
        addContentView(
            bubble,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM,
            ).apply {
                rightMargin = dp(12)
                bottomMargin = dp(84)
            },
        )
    }

    /** 按当前进度刷新胶囊文字；全部完成或已收起时隐藏。 */
    private fun refreshGuideBubble(config: SparkConfig) {
        val bubble = guideBubble ?: return
        val basic = basicSteps(config)
        val advanced = advancedSteps(config)
        val basicDone = basic.count { it.done }
        val pending = basicDone < basic.size || advanced.any { !it.done }
        if (!pending && !guidePreview) {
            bubble.visibility = View.GONE
            return
        }
        if (config.guideDismissed && !guidePreview) {
            bubble.visibility = View.GONE
            return
        }
        bubble.text =
            if (basicDone < basic.size) {
                "引导 $basicDone/${basic.size}"
            } else {
                "进阶 ${advanced.count { it.done }}/${advanced.size}"
            }
        bubble.visibility = View.VISIBLE
    }

    /** 打开浮层引导；再点一次收起。 */
    private fun showGuidePopup() {
        if (guidePopup?.isShowing == true) {
            guidePopup?.dismiss()
            return
        }
        val config = ConfigStore.load(this)
        val width = minOf(dp(360), resources.displayMetrics.widthPixels - dp(24))
        val content = buildGuideCard(config)
        val popup = PopupWindow(content, width, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        popup.isOutsideTouchable = true
        popup.elevation = dp(8).toFloat()
        popup.setOnDismissListener { guidePopup = null }
        guidePopup = popup

        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupHeight = content.measuredHeight
        val anchor = guideBubble ?: window.decorView
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val x = (loc[0] + anchor.width - width).coerceIn(dp(8), (screenWidth - width - dp(8)).coerceAtLeast(dp(8)))
        val y =
            (loc[1] - popupHeight - dp(8))
                .coerceIn(dp(8), (screenHeight - popupHeight - dp(8)).coerceAtLeast(dp(8)))
        popup.showAtLocation(window.decorView, Gravity.TOP or Gravity.START, x, y)
    }

    /** 轮换高亮：同一时刻只圈一个控件，避免多个描边叠在一起。 */
    private var guideCycleViews: List<View> = emptyList()
    private var guideCycleIndex = 0
    private var guideCycleRunnable: Runnable? = null
    private var guideRingView: View? = null
    private var guideRingAnim: ObjectAnimator? = null

    /** 从第一个开始轮流圈；单个控件时就是常亮呼吸。 */
    private fun startGuideCycle(views: List<View>) {
        stopGuideBreathing()
        if (views.isEmpty()) return
        guideCycleViews = views
        guideCycleIndex = 0
        // 等页面切换动画结束再量位置，否则描边会按动画中的旧坐标画偏
        pageHost.postDelayed({ if (guideCycleViews.isNotEmpty()) showGuideRingAt(guideCycleViews[0]) }, 360)
        views.forEach { view ->
            view.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) stopGuideBreathing()
                false
            }
        }
        val runnable =
            object : Runnable {
                override fun run() {
                    if (guideCycleViews.isEmpty()) return
                    guideCycleIndex = (guideCycleIndex + 1) % guideCycleViews.size
                    showGuideRingAt(guideCycleViews[guideCycleIndex])
                    pageHost.postDelayed(this, 1500)
                }
            }
        if (views.size > 1) {
            guideCycleRunnable = runnable
            pageHost.postDelayed(runnable, 1860)
        }
    }

    private fun showGuideRingAt(view: View) {
        clearGuideRing()
        val draw = {
            if (view.isAttachedToWindow) {
                val root = findViewById<ViewGroup>(android.R.id.content)
                if (root != null) {
                    val loc = IntArray(2)
                    view.getLocationInWindow(loc)
                    val rootLoc = IntArray(2)
                    root.getLocationInWindow(rootLoc)
                    val pad = dp(5)
                    val ring =
                        View(this).apply {
                            background =
                                android.graphics.drawable.GradientDrawable().apply {
                                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                    cornerRadius = dp(14).toFloat()
                                    setStroke(dp(2), COLOR_ACCENT)
                                }
                            alpha = 0.25f
                        }
                    root.addView(
                        ring,
                        FrameLayout.LayoutParams(view.width + pad * 2, view.height + pad * 2).apply {
                            leftMargin = loc[0] - rootLoc[0] - pad
                            topMargin = loc[1] - rootLoc[1] - pad
                        },
                    )
                    guideRingView = ring
                    guideRingAnim =
                        ObjectAnimator.ofFloat(ring, "alpha", 0.25f, 1f).apply {
                            duration = 900
                            repeatMode = ObjectAnimator.REVERSE
                            repeatCount = ObjectAnimator.INFINITE
                            start()
                        }
                }
            }
        }
        if (view.isLaidOut) {
            draw()
        } else {
            view.addOnLayoutChangeListener(
                object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View,
                        left: Int,
                        top: Int,
                        right: Int,
                        bottom: Int,
                        oldLeft: Int,
                        oldTop: Int,
                        oldRight: Int,
                        oldBottom: Int,
                    ) {
                        v.removeOnLayoutChangeListener(this)
                        draw()
                    }
                },
            )
        }
    }

    private fun clearGuideRing() {
        guideRingAnim?.cancel()
        guideRingAnim = null
        guideRingView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        guideRingView = null
    }

    private fun stopGuideBreathing() {
        guideCycleRunnable?.let { pageHost.removeCallbacks(it) }
        guideCycleRunnable = null
        guideCycleViews.forEach { it.setOnTouchListener(null) }
        guideCycleViews = emptyList()
        clearGuideRing()
    }

    /** 模板步骤：展开内置模板，清掉旧展开状态；先让整行呼吸，用户展开后再闪按钮。 */
    private fun openGuideTemplates() {
        tab = TAB_TEMPLATE
        builtinSectionOpen = true
        guideTemplateBreathing = true
        expandedTemplateId = null
        render()
        // 浮层点击的抬手可能落到刚切过来的行上，稍后再清一次展开状态
        pageHost.postDelayed({
            if (guideTemplateBreathing && expandedTemplateId != null) {
                expandedTemplateId = null
                render()
            }
        }, 260)
    }

    /** 跳到目标页后，让目标控件进入呼吸状态并滚到可见位置。 */
    private fun breatheGuideTarget(key: String?) {
        if (key == null) return
        guideTemplateBreathing = false
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return
        val target = findGuideTarget(root, key) ?: return
        target.postDelayed({
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
            startGuideCycle(listOf(target))
        }, 180)
    }

    /** 先按 tag 找，找不到再按可见文字找（按钮多用文字标签）。 */
    private fun findGuideTarget(view: View, key: String): View? {
        if (!view.isShown) return null
        if (view.tag == key) return view
        if (view is TextView && view.text?.toString() == key) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findGuideTarget(view.getChildAt(i), key)?.let { return it }
            }
        }
        return null
    }

    /** 「续火花」页：内层「已选目标 / 未选目标」两页 + 勾选后的批量移动条。 */
    private fun buildSparkPage(config: SparkConfig): View {
        // 群聊也进列表，但和单聊分成两张卡片（群聊默认不自动发送，要手动移进已选目标/勾选发送）
        // 连续多次没在抖音出现的会话（isHidden）不进列表，统一收进下方「被隐藏」折叠卡片
        val conversations = config.snapshots.filter { !it.isHidden }
        val hidden = config.snapshots.filter { it.isHidden }
        // 标签被删掉后，筛选状态跟着失效，避免列表被一个不存在的标签筛成空
        sparkTagFilter.removeAll { id -> config.tag(id) == null }
        val renewal = conversations.filter { config.needsRenewal(it) }
        val idle = conversations.filterNot { config.needsRenewal(it) }
        val page = column().apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        rowChecks.clear()
        rowViews.clear()
        selectionBar = null
        selectionCount = null
        sendLabel = null
        renewalCount = renewal.size

        page.addView(
            segmented(listOf("已选目标 ${renewal.size}", "未选目标 ${idle.size}"), sparkPage) { index ->
                if (index != sparkPage) {
                    sparkPage = index
                    selected.clear()
                    render()
                }
            },
        )

        // 立即手动发送：勾选了就发勾选的，没勾就发「已选目标」全部（不受总开关与今日已发限制）
        // 目标要在点击时现算：勾选框是页面建好之后才变的，构建时算会拿到旧列表
        fun currentTargets(): List<String> = if (selected.isNotEmpty()) selected.toList() else renewal.map { it.conversationId }
        page.addView(
            row().apply {
                setPadding(0, dp(6), 0, 0)
                val send =
                    iconButton(
                        UiKit.Icons.MAIL,
                        sendText(),
                        primary = true,
                        compact = true,
                    ) { confirmManualSend(currentTargets()) }
                // iconButton 里图标在前、文字在后，取文字视图留着实时改
                sendLabel = (send as? android.view.ViewGroup)?.getChildAt(1) as? TextView
                addView(send)
                addView(spacer())
            },
        )

        val current = if (sparkPage == PAGE_SELECTED) renewal else idle
        val list = column().apply { setPadding(0, dp(8), 0, dp(16)) }
        if (conversations.isEmpty()) {
            list.addView(
                text(
                    if (config.snapshots.isEmpty()) {
                        "没有会话快照。先打开一次抖音，再回来点「设置 → 刷新会话」。"
                    } else {
                        "所有会话都已隐藏（连续 ${SparkConfig.HIDDEN_AFTER_MISSES} 次未出现）。打开抖音刷新后会重新出现。"
                    },
                    14f,
                    COLOR_DIM,
                ),
            )
        } else {
            list.addView(
                text(
                    "快照 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(conversations.maxOf { it.updatedAt })) +
                        "　长按整行或勾选可多选",
                    12f,
                    COLOR_DIM,
                ),
            )
            if (current.isEmpty()) {
                list.addView(
                    text(
                        if (sparkPage == PAGE_SELECTED) "（暂无需要续火花的会话）" else "（暂无未选目标）",
                        13f,
                        COLOR_DIM,
                    ).apply { setPadding(0, dp(12), 0, 0) },
                )
            } else {
                // 分组卡片：单聊一组、群聊一组（标题 + 分隔线 + 条目）；下面挂搜索，按关键字过滤
                // 按标签筛选时：命中的所选标签越多越靠前（并集筛选下的相关性排序），同分保持原顺序
                fun matchedTags(snapshot: ConversationSnapshot): Int =
                    if (sparkTagFilter.isEmpty()) 0 else config.tagsOf(snapshot.conversationId).count { it.id in sparkTagFilter }
                val singles =
                    current.filterNot { it.isGroup }.let { rows ->
                        if (sparkTagFilter.isEmpty()) rows else rows.sortedByDescending { matchedTags(it) }
                    }
                val groups =
                    current.filter { it.isGroup }.let { rows ->
                        if (sparkTagFilter.isEmpty()) rows else rows.sortedByDescending { matchedTags(it) }
                    }
                val filterables = mutableListOf<Pair<View, List<Pair<View, String>>>>()
                val singleRows = singles.map { it to buildConversationRow(config, it) }
                val groupRows = groups.map { it to buildConversationRow(config, it) }
                val singlesCard =
                    sectionCard(
                        title = "单聊",
                        count = singles.size,
                        collapsed = singlesCollapsed,
                        onToggle = { singlesCollapsed = it },
                        items = singleRows.map { it.second },
                    )
                val groupsCard =
                    sectionCard(
                        title = "群聊",
                        count = groups.size,
                        hint = "群聊默认不自动发送：勾选后点「发送勾选」或把它移进已选目标才会发",
                        collapsed = groupsCollapsed,
                        onToggle = { groupsCollapsed = it },
                        items = groupRows.map { it.second },
                    )
                filterables += singlesCard to singleRows.map { (snap, view) -> view to searchTextOf(snap) }
                filterables += groupsCard to groupRows.map { (snap, view) -> view to searchTextOf(snap) }
                val viewToId = rowViews.entries.associate { (id, view) -> view to id }
                list.addView(searchRow("搜索昵称 / 备注", sparkQuery) { query ->
                    sparkQuery = query
                    markGuideSearched(query)
                    applySparkFilter(query, filterables, viewToId)
                })
                if (config.tags.isNotEmpty()) {
                    list.addView(
                        row().apply {
                            setPadding(0, dp(6), 0, 0)
                            addView(text("标签", 11f, COLOR_DIM).apply { setPadding(0, 0, dp(6), 0) })
                            addView(
                                android.widget.HorizontalScrollView(this@SparkSettingsActivity).apply {
                                    isHorizontalScrollBarEnabled = false
                                    addView(buildTagFilterChips(config))
                                },
                            )
                        },
                    )
                }
                val sources =
                    config.snapshots
                        .filterNot { it.isHidden }
                        .map { config.inheritKey(it.conversationId) }
                        .distinct()
                if (sources.size > 1) {
                    list.addView(
                        row().apply {
                            setPadding(0, dp(4), 0, 0)
                            addView(text("来源", 11f, COLOR_DIM).apply { setPadding(0, 0, dp(6), 0) })
                            addView(
                                android.widget.HorizontalScrollView(this@SparkSettingsActivity).apply {
                                    isHorizontalScrollBarEnabled = false
                                    addView(buildSourceFilterChips(config, sources))
                                },
                            )
                        },
                    )
                }
                applySparkFilter(sparkQuery, filterables, viewToId)
                list.addView(singlesCard)
                list.addView(groupsCard)
            }
            list.addView(
                text("点会话行编辑该会话的文案；勾选后用下方按钮批量移动归属。", 12f, COLOR_DIM).apply {
                    setPadding(0, dp(14), 0, 0)
                },
            )
        }
        if (hidden.isNotEmpty()) list.addView(buildHiddenSection(config, hidden))
        page.addView(scroll().apply { addView(list) })
        page.addView(buildSelectionBar())
        applySelection()
        return page
    }

    /**
     * 「被隐藏」分组卡片：连续多次没在抖音出现的会话收在这里（默认收起）。
     *
     * 隐藏不等于删除：快照要到 [SparkConfig.SNAPSHOT_GRACE_MS] 之后才清掉，
     * 期间重新出现会自动恢复。这里提供两个出口：恢复显示、把手动归属移出「已选目标」。
     */
    private fun buildHiddenSection(
        config: SparkConfig,
        hidden: List<ConversationSnapshot>,
    ): View =
        sectionCard(
            title = "被隐藏",
            count = hidden.size,
            hint = "连续 ${SparkConfig.HIDDEN_AFTER_MISSES} 次没在抖音出现就从列表隐藏，" +
                "满 30 天连快照一起删除；重新出现会自动恢复。",
            collapsed = hiddenCollapsed,
            onToggle = { hiddenCollapsed = it },
            items = hidden.map { snapshot -> hiddenRow(config, snapshot) },
        )

    /** 隐藏列表里的一行：名字 + 最后出现时间/次数 + 恢复 / 移出目标；点整行进会话编辑页。 */
    private fun hiddenRow(
        config: SparkConfig,
        snapshot: ConversationSnapshot,
    ): View {
        val manual = snapshot.conversationId in config.forcedRenew
        val lastSeen = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(snapshot.updatedAt))
        return flatRow().apply {
            addView(
                column().apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(text(snapshot.displayName.ifBlank { "(无名)" }, 15f, COLOR_TEXT, bold = true))
                    addView(
                        text(
                            (if (manual) "手动移入 · " else "") +
                                "最后出现 $lastSeen · 连续 ${snapshot.missCount} 次未出现",
                            12f,
                            COLOR_DIM,
                        ),
                    )
                },
            )
            if (manual) {
                addView(
                    text("移出目标", 13f, COLOR_ACCENT).apply {
                        isClickable = true
                        setPadding(dp(8), 0, dp(12), 0)
                        setOnClickListener { confirmRemoveTarget(snapshot) }
                    },
                )
            }
            addView(
                text("恢复", 13f, COLOR_ACCENT).apply {
                    isClickable = true
                    setOnClickListener { restoreHidden(snapshot.conversationId) }
                },
            )
            isClickable = true
            setOnClickListener { openTemplateOrConversation(snapshot.conversationId, false) }
        }
    }

    /** 恢复显示：把连续未出现计数清零（下次扫描还没出现会重新隐藏）。 */
    private fun restoreHidden(conversationId: String) {
        ConfigStore.update(this) { config ->
            config.copy(
                snapshots =
                    config.snapshots.map { snapshot ->
                        if (snapshot.conversationId == conversationId) snapshot.copy(missCount = 0) else snapshot
                    },
            )
        }
        Toast.makeText(this, "已恢复显示；下次扫描如果还没出现会重新隐藏", Toast.LENGTH_SHORT).show()
        render()
    }

    /** 把会话从手动归属里拿掉（只动「已选目标」，快照按保留策略自己过期）。 */
    private fun confirmRemoveTarget(snapshot: ConversationSnapshot) {
        val name = snapshot.displayName.ifBlank { "(无名)" }
        AlertDialog
            .Builder(this)
            .setTitle("把「$name」移出已选目标？")
            .setMessage("它仍然留在「被隐藏」列表里，快照保留到 30 天后自动清除。")
            .setPositiveButton("移出") { _, _ ->
                ConfigStore.update(this) { config ->
                    config.copy(
                        forcedRenew = config.forcedRenew - snapshot.conversationId,
                        forcedSkip = config.forcedSkip - snapshot.conversationId,
                    )
                }
                Toast.makeText(this, "已移出已选目标", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 点模板标题：展开时直接重建页面，新卡片进场做一次高度动画；
     * 收起先把这张卡片的高度收掉再重建，避免整页「啪」地跳一下。
     */
    private fun toggleTemplateFold(id: String) {
        val opening = expandedTemplateId != id
        // 手风琴：先把之前展开的那张收起来，再展开 / 收起这一张，全程只动高度
        expandedTemplateId?.takeIf { it != id }?.let { prev ->
            templateFolds[prev]?.let { (extra, glyph) -> animateFold(extra, false, glyph) }
        }
        templateFolds[id]?.let { (extra, glyph) -> animateFold(extra, opening, glyph) }
        expandedTemplateId = if (opening) id else null
    }

    /** 会话的搜索文本：昵称 + 备注（标签走标记筛选，不参与搜索）。 */
    private fun searchTextOf(snapshot: ConversationSnapshot): String =
        buildString {
            append(snapshot.displayName).append(' ')
            append(snapshot.remark).append(' ')
            append(snapshot.name)
        }

    /**
     * 续火花页的筛选：搜索词（昵称 / 备注）+ 多选标签（**并集**：命中任一所选标签即可）+ 来源（单选）。
     */
    private fun applySparkFilter(
        query: String,
        groups: List<Pair<View, List<Pair<View, String>>>>,
        viewToId: Map<View, String>,
    ) {
        val config = ConfigStore.load(this)
        val keyword = query.trim().lowercase()
        val tagging = sparkTagFilter.isNotEmpty()
        groups.forEach { (card, rows) ->
            var anyHit = keyword.isEmpty() && !tagging && sparkSourceFilter == null
            rows.forEach { (view, text) ->
                val id = viewToId[view]
                val tagOk = !tagging || (id != null && config.tagsOf(id).any { it.id in sparkTagFilter })
                val sourceOk =
                    sparkSourceFilter == null ||
                        (id != null && (config.inheritKey(id) ?: SRC_NONE) == sparkSourceFilter)
                val textOk = keyword.isEmpty() || text.lowercase().contains(keyword)
                val hit = tagOk && sourceOk && textOk
                view.visibility = if (hit) View.VISIBLE else View.GONE
                if (hit) anyHit = true
            }
            card.visibility = if (anyHit) View.VISIBLE else View.GONE
        }
    }

    private fun buildTagFilterChips(config: SparkConfig): View =
        row().apply {
            addView(
                filterChip("全部", sparkTagFilter.isEmpty()) {
                    sparkTagFilter.clear()
                    render()
                },
            )
            config.tags.forEach { tag ->
                val active = tag.id in sparkTagFilter
                addView(
                    filterChip(tag.name, active, if (active) null else SparkConfig.tagColor(tag.color)) {
                        // 可多选：再点一次取消这一个，其余保留
                        if (active) sparkTagFilter.remove(tag.id) else sparkTagFilter.add(tag.id)
                        render()
                    },
                )
            }
        }

    private fun buildSourceFilterChips(
        config: SparkConfig,
        sources: List<String?>,
    ): View =
        row().apply {
            addView(
                filterChip("全部", sparkSourceFilter == null) {
                    sparkSourceFilter = null
                    render()
                },
            )
            sources.forEach { key ->
                val value = key ?: SRC_NONE
                val active = sparkSourceFilter == value
                addView(
                    filterChip(config.inheritLabel(key), active) {
                        sparkSourceFilter = if (active) null else value
                        render()
                    },
                )
            }
        }

    /** 标记筛选的小药丸（选中：强调色 + 浅底；未选中：标签色或灰）。 */
    private fun filterChip(
        label: String,
        active: Boolean,
        color: Int? = null,
        onClick: () -> Unit,
    ): View =
        text(label, 12f, if (active) COLOR_ACCENT else (color ?: COLOR_DIM), bold = active).apply {
            background = rounded(if (active) 0x333B7CFF else 0x0A000000, 12)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 会话行里的小标签。 */
    private fun tagChip(
        name: String,
        color: Int,
    ): View =
        text(name, 11f, SparkConfig.tagColor(color)).apply {
            background = rounded(0x14000000, 8)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(4) }
        }

    /** 应用搜索关键字：条目按关键字显隐，整组都不匹配就把那张卡片藏掉；顺手记住关键字。 */
    private fun applyQuery(
        query: String,
        groups: List<Pair<View, List<Pair<View, String>>>>,
        remember: Boolean = true,
    ) {
        if (remember) sparkQuery = query
        filterGroups(query, groups)
    }

    /** 多选后的操作条（没有勾选时隐藏）：计数 + 取消、批量移动。 */
    private fun buildSelectionBar(): View =
        column().apply {
            background = rounded(COLOR_CARD, 10)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            val count = text("已选 ${selected.size} 个", 13f, COLOR_TEXT, bold = true)
            addView(
                row().apply {
                    addView(count)
                    addView(spacer())
                    addView(
                        text("取消", 13f, COLOR_ACCENT).apply {
                            isClickable = true
                            setOnClickListener {
                                selected.clear()
                                applySelection()
                            }
                        },
                    )
                },
            )
            addView(
                row().apply {
                    addView(
                        iconButton(UiKit.Icons.ADD, "移入已选目标", primary = true, compact = true) { moveSelection(true) }
                            .apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) },
                    )
                    addView(
                        iconButton(UiKit.Icons.REMOVE, "移出到未选目标", compact = true) { moveSelection(false) }
                            .apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) },
                    )
                },
            )
            selectionBar = this
            selectionCount = count
        }

    /** 按当前勾选状态刷新行内勾选框与操作条（不重建列表，保持滚动位置）。 */
    private fun applySelection() {
        rowChecks.forEach { (id, box) ->
            val checked = id in selected
            if (box.isChecked != checked) box.isChecked = checked
        }
        rowViews.forEach { (id, view) -> view.setBackgroundColor(if (id in selected) COLOR_SELECTED else 0x00000000) }
        selectionCount?.text = "已选 ${selected.size} 个"
        selectionBar?.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        sendLabel?.text = sendText()
    }

    /** 顶部发送按钮的文案：有勾选按勾选数，没有就按「已选目标」数。 */
    private fun sendText(): String =
        if (selected.isNotEmpty()) {
            "发送给选中的 ${selected.size} 个"
        } else {
            "发送全部 $renewalCount 个"
        }

    /**
     * 火花状态文案。
     *
     * 「待续」分两种：**待发**（今天我们还没发）与 **待对方回复**
     * （我们今天已经发了、对方还没回，所以抖音还算「今天没互动」）——
     * 后者不用再发，只能等。
     */
    private fun sparkLabel(
        config: SparkConfig,
        snapshot: ConversationSnapshot,
    ): String {
        val sentToday = config.sentDays[snapshot.conversationId] == todayText()
        return when {
            // 没有火苗时抖音可能给一句引导文案（互为好友但还没建立火苗 → 「可点亮」）
            !snapshot.hasSpark -> snapshot.stateText.takeIf { it.isNotBlank() } ?: "无火花"
            snapshot.state == SPARK_DONE -> "火花 ${snapshot.days} 天 · 今日已续"
            snapshot.state == SPARK_PENDING ->
                if (sentToday) {
                    "火花 ${snapshot.days} 天 · 已发，待对方回复"
                } else {
                    "火花 ${snapshot.days} 天 · 待发"
                }
            snapshot.state == SPARK_RECOVER ->
                "待重燃 ${snapshot.recoverDays}/${snapshot.recoverNeedDays.coerceAtLeast(1)} 天" +
                    if (sentToday) "（已发，待对方回复）" else ""
            else -> "火花 ${snapshot.days} 天"
        }
    }

    /** 今天的日期（与宿主记录同一格式）。 */
    private fun todayText(): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    /** 会话条目（放进分组卡片里，自身不画背景；条目之间的分隔线由分组卡片负责）。 */
    private fun buildConversationRow(
        config: SparkConfig,
        snapshot: ConversationSnapshot,
    ): View {
        val id = snapshot.conversationId
        val template = config.conversation(id)
        val sourceText =
            when {
                // 没建模板 = 还没写文案；空白模板 = 不用模板、自己写（两者都会照常发送）
                template == null -> "未写文案"
                template.inheritFrom == null -> "空白模板"
                else -> "继承：" + (config.templateName(template.inheritFrom) ?: "(已删除的模板)")
            }
        val marker =
            when (id) {
                in config.forcedRenew -> "　手动移入"
                in config.forcedSkip -> "　手动移出"
                else -> ""
            }
        val sparkText = sparkLabel(config, snapshot)
        val sparkColor =
            when {
                snapshot.state == SPARK_RECOVER || snapshot.state == SPARK_PENDING -> COLOR_WARN
                snapshot.hasSpark -> COLOR_ACCENT
                else -> COLOR_DIM
            }
        return flatRow().apply {
            val box =
                CheckBox(this@SparkSettingsActivity).apply {
                    isChecked = id in selected
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected += id else selected -= id
                        applySelection()
                    }
                }
            rowChecks[id] = box
            addView(box)
            val avatar =
                ImageView(this@SparkSettingsActivity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            AvatarLoader.load(this@SparkSettingsActivity, avatar, snapshot.avatar, snapshot.displayName)
            addView(avatar)
            addView(
                column().apply {
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(
                        row().apply {
                            addView(text(snapshot.displayName.ifBlank { "(无名)" }, 15.5f, bold = true))
                            addView(spacer())
                            if (marker.isNotEmpty()) addView(text(marker.trim(), 12f, COLOR_ACCENT))
                        },
                    )
                    addView(text(sparkText, 13f, sparkColor).apply { setPadding(0, dp(2), 0, 0) })
                    addView(
                        text(sourceText, 12.5f, COLOR_DIM).apply {
                            setPadding(0, dp(2), 0, 0)
                            // 点「继承：X」直接按来源筛选
                            isClickable = true
                            setOnClickListener {
                                sparkSourceFilter = config.inheritKey(id) ?: SRC_NONE
                                render()
                            }
                        },
                    )
                    val tags = config.tagsOf(id)
                    if (tags.isNotEmpty()) {
                        addView(
                            row().apply {
                                setPadding(0, dp(3), 0, 0)
                                tags.forEach { tag ->
                                    addView(
                                        tagChip(tag.name, tag.color).apply {
                                            // 点标签切换这个标签的筛选（与顶部标记一致，可多选）
                                            isClickable = true
                                            setOnClickListener {
                                                if (tag.id in sparkTagFilter) {
                                                    sparkTagFilter.remove(tag.id)
                                                } else {
                                                    sparkTagFilter.add(tag.id)
                                                }
                                                render()
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    }
                    if (template?.modified == true) {
                        addView(
                            text("已本地修改（父模板同步时不会被覆盖）", 12f, COLOR_WARN)
                                .apply { setPadding(0, dp(2), 0, 0) },
                        )
                    }
                },
            )
        }.asClickable {
            // 选择是页面内的临时状态：进会话前清掉，免得回来还留着"发送给选中的 1 个"
            if (selected.isNotEmpty()) {
                selected.clear()
            }
            startActivity(
                Intent(this@SparkSettingsActivity, ConversationEditActivity::class.java)
                    .putExtra(ConversationEditActivity.EXTRA_CONVERSATION_ID, snapshot.conversationId),
            )
        }.apply {
            rowViews[id] = this
            setBackgroundColor(if (id in selected) COLOR_SELECTED else 0x00000000)
            setOnLongClickListener {
                if (id in selected) selected -= id else selected += id
                applySelection()
                true
            }
        }
    }

    /**
     * 把勾选的会话移入「已选目标（需要续）」或「未选目标（无需续）」。
     *
     * 移入时给它的会话模板打上「最后在已选目标的时间」，这样以后移出也有 30 天宽限期；
     * 移动完顺手跑一次保留清理（未选中的会话模板与发送记录按策略清掉）。
     */
    private fun moveSelection(renew: Boolean) {
        if (selected.isEmpty()) {
            Toast.makeText(this, "先长按或勾选要移动的会话", Toast.LENGTH_SHORT).show()
            return
        }
        val moving = selected.toSet()
        val now = System.currentTimeMillis()
        ConfigStore.update(this) { config ->
            val moved =
                config.copy(
                    forcedRenew = if (renew) config.forcedRenew + moving else config.forcedRenew - moving,
                    forcedSkip = if (renew) config.forcedSkip - moving else config.forcedSkip + moving,
                )
            val stamped =
                if (!renew) {
                    moved
                } else {
                    moved.copy(
                        conversations = moved.conversations.map { if (it.conversationId in moving) it.copy(selectedAt = now) else it },
                    )
                }
            stamped.pruneTemplates(now)
        }
        Toast.makeText(this, "已移动 ${moving.size} 个会话", Toast.LENGTH_SHORT).show()
        selected.clear()
        render()
    }

    // -------------------------------------------------------------------- 模板

    /**
     * 模板页：一级模板（内置 + 自定义）都能**折叠展开**，展开后显示它的**派生模板**
     * （继承自它的会话）。
     *
     * - 段标题也可折叠：内置模板默认折叠，免得内置项一多就把页面撑满。
     * - 「选会话继承」= 跟随该模板（父模板改了会同步）；「克隆到会话」= 只复制内容，之后跟空白模板。
     */
    private fun buildTemplatesPage(config: SparkConfig): View {
        val body = column().apply { setPadding(dp(16), dp(0), dp(16), dp(16)) }
        templateFolds.clear()
        val templateFilter = mutableListOf<Pair<View, List<Pair<View, String>>>>()
        val contentStart = body.childCount
        val templateEmpty = text("没有匹配的模板", 13f, COLOR_DIM).apply { visibility = View.GONE }
        body.addView(
            searchRow("搜索模板名 / 内容", templateQuery) { query ->
                templateQuery = query
                markGuideSearched(query)
                // 逐张卡片判定：搜索行不在过滤单元里，所以不会被一起藏掉
                filterGroups(query, templateFilter)
                templateEmpty.visibility = if (query.isNotBlank() && templateFilter.none { it.first.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
            },
        )

        run {
            val rows = mutableListOf<View>()
            val builtinRows = mutableListOf<View>()
            val expandedButtons = mutableListOf<View>()
            BuiltinTemplates.ALL.forEachIndexed { index, builtin ->
                val id = SparkConfig.BUILTIN_PREFIX + builtin.id
                val derived = config.membersOf(id).size
                val expanded = id == expandedTemplateId
                lateinit var builtinRow: View
                val actionButtons = mutableListOf<View>()
                rows +=
                    column().apply {
                        setPadding(0, dp(10), 0, dp(10))
                        val glyph = icon(if (expanded) UiKit.Icons.FOLD else UiKit.Icons.UNFOLD, 14f, COLOR_DIM).apply { setPadding(0, 0, dp(6), 0) }
                        addView(
                            row().apply {
                                addView(glyph)
                                addView(text(builtin.name, 16f, bold = true))
                                if (builtin.id == BuiltinTemplates.BLANK.id) addView(text("　默认", 12f, COLOR_DIM))
                                addView(spacer())
                                if (derived > 0) addView(text("派生 $derived", 12f, COLOR_ACCENT))
                            },
                        )
                        addView(text(builtin.desc, 13f, COLOR_DIM))
                        if (builtin.body.isNotBlank()) {
                            addView(text(previewBody(config, builtin.body, index, config.templateVarsForGroup(id)), 13f, COLOR_DIM))
                        }
                        val extra =
                            column().apply {
                                visibility = if (expanded) View.VISIBLE else View.GONE
                                buildChildren(config, id, this)
                                if (builtin.body.isNotBlank()) {
                                    // 三个入口分两行，窄屏下「克隆到会话」不会被挤成竖排
                                    val addButton =
                                        iconButton(UiKit.Icons.NEW_FILE, "添加为我的模板", primary = true, compact = true) {
                                            guideTemplateBreathing = false
                                            stopGuideBreathing()
                                            addBuiltinAsTemplate(builtin)
                                        }
                                    val inheritButton =
                                        iconButton(UiKit.Icons.PERSON_ADD, "选会话继承", compact = true) {
                                            guideTemplateBreathing = false
                                            stopGuideBreathing()
                                            pickMembers(id, builtin.name)
                                        }
                                    val cloneButton =
                                        iconButton(UiKit.Icons.CLONE, "克隆到会话", compact = true) {
                                            guideTemplateBreathing = false
                                            stopGuideBreathing()
                                            cloneBodyToConversations(builtin.name, builtin.body)
                                        }
                                    actionButtons += addButton
                                    actionButtons += inheritButton
                                    actionButtons += cloneButton
                                    addView(
                                        column().apply {
                                            setPadding(0, dp(8), 0, 0)
                                            addView(
                                                row().apply {
                                                    addView(addButton)
                                                    addView(inheritButton)
                                                },
                                            )
                                            addView(
                                                row().apply {
                                                    setPadding(0, dp(6), 0, 0)
                                                    addView(cloneButton)
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                        addView(extra)
                        templateFolds[id] = extra to glyph
                        builtinRow = this
                    }.asClickable {
                        val guided = guideTemplateBreathing
                        stopGuideBreathing()
                        toggleTemplateFold(id)
                        if (guided) {
                            // 等展开后的布局稳定，只圈“选会话继承”
                            pageHost.postDelayed({
                                if (guideTemplateBreathing) {
                                    val root = findViewById<ViewGroup>(android.R.id.content)
                                    val target = root?.let { findGuideTarget(it, "选会话继承") }
                                    if (target != null) startGuideCycle(listOf(target))
                                }
                            }, 700)
                        }
                    }
                if (builtin.id != BuiltinTemplates.BLANK.id) builtinRows += builtinRow
                if (expanded) expandedButtons += actionButtons
            }
            body.addView(
                sectionCard(
                    title = "内置模板",
                    count = rows.size,
                    collapsed = !builtinSectionOpen,
                    onToggle = { builtinSectionOpen = !it },
                    items = rows,
                ),
            )
            if (guideTemplateBreathing) {
                pageHost.post {
                    if (!guideTemplateBreathing) return@post
                    val anyExpanded = expandedTemplateId?.startsWith(SparkConfig.BUILTIN_PREFIX) == true
                    if (!anyExpanded) startGuideCycle(builtinRows)
                }
            }
        }

        run {
            val rows = mutableListOf<View>()
            if (config.groups.isEmpty()) {
                rows += text("还没有模板。可以从内置模板「添加为我的模板」，或直接新建。", 14f, COLOR_DIM)
            } else {
                config.groups.forEachIndexed { index, group ->
                    val derived = config.membersOf(group.id).size
                    val expanded = group.id == expandedTemplateId
                    rows +=
                        column().apply {
                            setPadding(0, dp(10), 0, dp(10))
                            val glyph = icon(if (expanded) UiKit.Icons.FOLD else UiKit.Icons.UNFOLD, 14f, COLOR_DIM).apply { setPadding(0, 0, dp(6), 0) }
                            addView(
                                row().apply {
                                    addView(glyph)
                                    addView(text(group.name.ifBlank { "(未命名)" }, 16f, bold = true))
                                    addView(spacer())
                                    if (derived > 0) addView(text("派生 $derived", 12f, COLOR_ACCENT))
                                },
                            )
                            addView(text(previewGroup(config, group, index, config.templateVarsForGroup(group.id)), 13f, COLOR_DIM))
                            val extra =
                                column().apply {
                                    visibility = if (expanded) View.VISIBLE else View.GONE
                                    buildChildren(config, group.id, this)
                                    addView(
                                        row().apply {
                                            setPadding(0, dp(8), 0, 0)
                                            addView(iconButton(UiKit.Icons.EDIT, "编辑模板", primary = true, compact = true) { openTemplateEditor(group.id) })
                                            addView(iconButton(UiKit.Icons.PERSON_ADD, "选会话继承", compact = true) { pickMembers(group.id, group.name) })
                                            addView(iconButton(UiKit.Icons.CLONE, "克隆到会话", compact = true) { cloneBodyToConversations(group.name, group.body) })
                                        },
                                    )
                                }
                            addView(extra)
                            templateFolds[group.id] = extra to glyph
                        }.asClickable { toggleTemplateFold(group.id) }
                }
            }
            body.addView(
                sectionCard(
                    title = "自定义模板",
                    count = config.groups.size,
                    collapsed = !customSectionOpen,
                    onToggle = { customSectionOpen = !it },
                    items = rows,
                ),
            )
        }
        // 搜索过滤单元要在返回前登记，否则整页搜索不生效
        val templateCards = (contentStart until body.childCount).map { body.getChildAt(it) }
        // 每张卡片各自成组（不要用 body 当组，否则会把搜索行一起藏掉）
        templateCards.forEach { card -> templateFilter += card to listOf(card to card.textContent()) }
        if (templateQuery.isNotEmpty()) filterGroups(templateQuery, templateFilter)
        return column().apply {
            addView(
                scroll().apply { addView(body) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            addView(templateEmpty)
            addView(
                bottomBar {
                    addView(iconButton(UiKit.Icons.ADD, "新增模板", primary = true, compact = true) { openTemplateEditor(null) })
                },
            )
        }
    }

    /** 可折叠的段标题（圆角卡片样式，与列表页的分组卡片一致）。 */
    private fun sectionToggle(
        title: String,
        count: String,
        open: Boolean,
        onToggle: () -> Unit,
    ): View =
        card().apply {
            isClickable = true
            setOnClickListener { onToggle() }
            addView(
                row().apply {
                    addView(text(title, 15f, COLOR_TEXT, bold = true))
                    addView(spacer())
                    addView(text(count, 13f, COLOR_DIM).apply { setPadding(0, 0, dp(8), 0) })
                    addView(icon(if (open) UiKit.Icons.FOLD else UiKit.Icons.UNFOLD, 14f, COLOR_DIM))
                },
            )
        }

    /**
     * 展开后列出的派生模板（继承自该模板的会话），多层派生按缩进铺开：
     * `A → B → C` 时在 A 下面先列 B，再缩进列 C。
     */
    /**
     * 展开后列出的派生模板（继承自该模板的会话/子模板），多层派生按缩进铺开：
     * `A → B → C` 时在 A 下面先列 B，再缩进列 C。
     *
     * 每一行都可以点：点会话就进它的文案编辑页，点子模板就进模板编辑页。
     */
    private fun buildChildren(
        config: SparkConfig,
        templateId: String,
        body: LinearLayout,
        depth: Int = 0,
    ) {
        val members = config.membersOf(templateId)
        if (members.isEmpty()) {
            if (depth == 0) body.addView(text("（还没有派生模板）", 13f, COLOR_DIM))
            return
        }
        members.forEach { member ->
            val id = member.conversationId
            val isTemplate = config.groups.any { it.id == id }
            body.addView(
                flatRow().apply {
                    setPadding(dp(depth * 16), dp(10), 0, dp(10))
                    addView(icon(UiKit.Icons.SWAP, 13f, COLOR_DIM).apply { setPadding(0, 0, dp(8), 0) })
                    addView(
                        column().apply {
                            layoutParams =
                                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            addView(
                                text(
                                    if (isTemplate) "模板：" + (config.group(id)?.name ?: "(已删除)")
                                    else config.conversationLabel(id),
                                    14f,
                                    COLOR_TEXT,
                                ),
                            )
                            when {
                                member.modified -> addView(text("已本地修改（未跟随父模板）", 12f, COLOR_WARN))
                                member.inheritFrom == null -> addView(text("空白模板", 12f, COLOR_DIM))
                                member.inheritFrom == SparkConfig.BUILTIN_BLANK -> addView(text("空白模板", 12f, COLOR_DIM))
                                else -> addView(text("跟随父模板", 12f, COLOR_DIM))
                            }
                            // 这个会话自己定义的变量（同名会覆盖父模板的）
                            if (member.variables.isNotEmpty()) {
                                addView(text("变量 ${member.variables.size}", 12f, COLOR_ACCENT))
                            }
                        },
                    )
                    addView(icon(UiKit.Icons.CHEVRON_RIGHT, 13f, COLOR_DIM))
                    isClickable = true
                    setOnClickListener { openTemplateOrConversation(id, isTemplate) }
                    // 长按：编辑 / 编辑它用的变量（变量在父模板上，所以这里能直接改到父模板）
                    setOnLongClickListener {
                        showDerivedActions(id, isTemplate, member.inheritFrom)
                        true
                    }
                },
            )
            if (isTemplate) buildChildren(config, id, body, depth + 1)
        }
    }

    /**
     * 长按派生项的菜单：「更改模板」进去换/编辑模板，「编辑模板变量」编辑**这个派生项自己的变量**。
     *
     * 会话的变量和父模板的变量是两份数据：会话那份在生效时排在父模板之后（同名覆盖），
     * 改这里**不会**动父模板、也不会影响别的派生项。
     */
    private fun showDerivedActions(
        id: String,
        isTemplate: Boolean,
        inheritFrom: String?,
    ) {
        val config = ConfigStore.load(this)
        val label =
            if (isTemplate) {
                config.group(id)?.name.orEmpty().ifBlank { "(未命名模板)" }
            } else {
                config.conversationLabel(id)
            }
        // 会话（派生项）可以直接复制 id，脚本里挂载宏 / 定位会话用得到
        val idActions =
            if (isTemplate) {
                emptyArray()
            } else {
                arrayOf(
                    "复制会话 ID" to {
                        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("会话 ID", id))
                        android.widget.Toast.makeText(this, "已复制：$id", android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }
        val ownVars =
            if (isTemplate) {
                config.group(id)?.variables.orEmpty()
            } else {
                config.conversation(id)?.variables.orEmpty()
            }
        val items =
            arrayOf(
                "更改模板",
                if (ownVars.isEmpty()) "编辑模板变量" else "编辑模板变量（${ownVars.size} 个）",
            ) + idActions.map { it.first }
        AlertDialog
            .Builder(this)
            .setTitle("「$label」")
            .setItems(items) { _, which ->
                if (which >= 2) {
                    idActions[which - 2].second.invoke()
                    return@setItems
                }
                if (which == 0) {
                    openTemplateOrConversation(id, isTemplate)
                } else if (isTemplate) {
                    openGroupVariables(id, label)
                } else {
                    openConversationVariables(id, label, inheritFrom)
                }
            }.show()
    }

    /**
     * 打开**这个会话自己的**变量页（父模板的变量只带过去做提示与补全）。
     *
     * 保存后写回 [com.worldmargin.autospark.app.config.ConversationTemplate.variables]，
     * **不动父模板**—— 它们是两份数据，同名时会话的覆盖父模板的。
     */
    private fun openConversationVariables(
        conversationId: String,
        label: String,
        inheritFrom: String?,
    ) {
        val config = ConfigStore.load(this)
        val template = config.conversation(conversationId)
        val inherited = config.templateVarsForGroup(inheritFrom.orEmpty())
        pendingVarsConversation = conversationId
        runCatching {
            startActivityForResult(
                Intent(this, VariableListActivity::class.java)
                    .putExtra(VariableListActivity.EXTRA_VARS, ConfigCodec.encodeVars(template?.variables.orEmpty()).toString())
                    .putExtra(VariableListActivity.EXTRA_INHERITED, ConfigCodec.encodeVars(inherited).toString())
                    .putExtra(VariableListActivity.EXTRA_PARENT_ID, inheritFrom)
                    .putExtra(VariableListActivity.EXTRA_OWNER, label),
                REQUEST_CONVERSATION_VARS,
            )
        }.onFailure { Toast.makeText(this, "打不开变量页", Toast.LENGTH_SHORT).show() }
    }

    /** 打开某个自定义模板自己的变量页（只有模板本身的变量）。 */
    private fun openGroupVariables(
        groupId: String,
        label: String,
    ) {
        val group = ConfigStore.load(this).group(groupId)
        if (group == null) {
            Toast.makeText(this, "模板已被删除", Toast.LENGTH_SHORT).show()
            return
        }
        pendingVarsGroup = groupId
        runCatching {
            startActivityForResult(
                Intent(this, VariableListActivity::class.java)
                    .putExtra(VariableListActivity.EXTRA_VARS, ConfigCodec.encodeVars(group.variables).toString())
                    .putExtra(VariableListActivity.EXTRA_OWNER, label),
                REQUEST_GROUP_VARS,
            )
        }.onFailure { Toast.makeText(this, "打不开变量页", Toast.LENGTH_SHORT).show() }
    }

    /** 点派生项时跳到对应编辑页：子模板 → 模板编辑页，会话 → 会话文案编辑页。 */
    private fun openTemplateOrConversation(
        id: String,
        isTemplate: Boolean,
    ) {
        val intent =
            if (isTemplate) {
                Intent(this, TemplateEditActivity::class.java)
                    .putExtra(TemplateEditActivity.EXTRA_GROUP_ID, id)
            } else {
                Intent(this, ConversationEditActivity::class.java)
                    .putExtra(ConversationEditActivity.EXTRA_CONVERSATION_ID, id)
            }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "打不开：目标可能已被删除", Toast.LENGTH_SHORT).show() }
    }

    /** 把内置模板复制成一份可编辑的自定义模板。 */
    private fun addBuiltinAsTemplate(builtin: BuiltinTemplate) {
        if (builtin.body.isBlank()) {
            Toast.makeText(this, "空白模板不用复制，直接给会话选它就能自己写", Toast.LENGTH_SHORT).show()
            return
        }
        ConfigStore.update(this) { config ->
            config.copy(
                groups =
                    config.groups +
                        TemplateGroup(
                            id = java.util.UUID.randomUUID().toString(),
                            name = builtin.name,
                            body = builtin.body,
                            updatedAt = System.currentTimeMillis(),
                        ),
            )
        }
        Toast.makeText(this, "已添加为我的模板，可点开编辑", Toast.LENGTH_SHORT).show()
        render()
    }

    /** 把某个模板的内容**克隆**给选中的会话（只复制内容，之后跟空白模板）。 */
    private fun cloneBodyToConversations(
        name: String,
        body: String,
    ) {
        val config = ConfigStore.load(this)
        if (config.snapshots.none { !it.isHidden && !it.isGroup }) {
            Toast.makeText(this, "暂无会话快照，请先刷新会话", Toast.LENGTH_SHORT).show()
            return
        }
        pendingCloneName = name
        pendingCloneBody = body
        runCatching {
            startActivityForResult(
                Intent(this, ConversationPickerActivity::class.java)
                    .putExtra(ConversationPickerActivity.EXTRA_TITLE, "克隆「$name」到会话")
                    .putStringArrayListExtra(ConversationPickerActivity.EXTRA_SELECTED, ArrayList<String>()),
                REQUEST_PICK_CLONE,
            )
        }.onFailure { Toast.makeText(this, "打不开会话选择页", Toast.LENGTH_SHORT).show() }
    }

    /** 会话选择页回来：把选中的会话克隆成该模板内容。 */
    private fun applyClone(data: Intent?) {
        val name = pendingCloneName
        val body = pendingCloneBody
        pendingCloneName = ""
        pendingCloneBody = ""
        val picked = data?.getStringArrayListExtra(ConversationPickerActivity.EXTRA_RESULT).orEmpty().toSet()
        val now = System.currentTimeMillis()
        val source = TemplateSource(parentId = null, name = name, body = body)
        ConfigStore.update(this) { config ->
            var list = config.conversations
            picked.forEach { conversationId ->
                val current = list.firstOrNull { it.conversationId == conversationId }
                val next =
                    TemplateOps.fromSource(
                        conversationId = conversationId,
                        source = source,
                        autoSync = false,
                        now = now,
                        selectedAt = current?.selectedAt ?: 0L,
                        // 会话自己的变量不能被克隆冲掉
                        variables = current?.variables.orEmpty(),
                    )
                list = if (current == null) list + next else list.map { if (it.conversationId == conversationId) next else it }
            }
            config.copy(conversations = list)
        }
        Toast.makeText(this, "已克隆到 ${picked.size} 个会话", Toast.LENGTH_SHORT).show()
        render()
    }

    /**
     * 批量**继承**：把选中的会话挂到某个一级模板下（内置模板也行）。
     *
     * 继承 = 跟随父模板：父模板内容改了可以同步过来；取消勾选则退回「空白模板」（自己写）并保留已写内容。
     */
    private fun pickMembers(
        templateId: String,
        templateName: String,
    ) {
        val config = ConfigStore.load(this)
        if (config.snapshots.none { !it.isHidden }) {
            Toast.makeText(this, "暂无会话快照，请先刷新会话", Toast.LENGTH_SHORT).show()
            return
        }
        pendingPickTemplateId = templateId
        pendingPickTemplateName = templateName
        val preselected = config.conversations.filter { it.inheritFrom == templateId }.map { it.conversationId }
        runCatching {
            startActivityForResult(
                Intent(this, ConversationPickerActivity::class.java)
                    .putExtra(ConversationPickerActivity.EXTRA_TITLE, "选择继承「${templateName.ifBlank { "(未命名)" }}」的会话")
                    .putExtra(ConversationPickerActivity.EXTRA_ONLY_SINGLE, false)
                    .putStringArrayListExtra(ConversationPickerActivity.EXTRA_SELECTED, ArrayList(preselected)),
                REQUEST_PICK_MEMBERS,
            )
        }.onFailure { Toast.makeText(this, "打不开会话选择页", Toast.LENGTH_SHORT).show() }
    }

    /** 会话选择页回来：按选中的会话更新继承关系（取消的退回空白模板）。 */
    private fun applyPickedMembers(data: Intent?) {
        val templateId = pendingPickTemplateId ?: return
        val templateName = pendingPickTemplateName
        pendingPickTemplateId = null
        pendingPickTemplateName = ""
        val picked = data?.getStringArrayListExtra(ConversationPickerActivity.EXTRA_RESULT).orEmpty().toSet()
        val config = ConfigStore.load(this)
        val source = TemplateSource(templateId, templateName, config.templateBody(templateId))
        val now = System.currentTimeMillis()
        ConfigStore.update(this) { cfg ->
            var list = cfg.conversations
            cfg.snapshots.filter { !it.isHidden && !it.isGroup }.forEach { snapshot ->
                val conversationId = snapshot.conversationId
                val current = list.firstOrNull { it.conversationId == conversationId }
                val next: ConversationTemplate? =
                    when {
                        conversationId in picked && current?.inheritFrom != templateId ->
                            TemplateOps.fromSource(
                                conversationId = conversationId,
                                source = source,
                                autoSync = current?.autoSync ?: false,
                                now = now,
                                selectedAt = current?.selectedAt ?: 0L,
                                variables = current?.variables.orEmpty(),
                            )
                        conversationId !in picked && current?.inheritFrom == templateId ->
                            TemplateOps.blank(
                                conversationId,
                                current.body,
                                now,
                                current.selectedAt,
                                current.variables,
                            )
                        else -> null
                    }
                if (next != null) {
                    list =
                        if (current == null) {
                            list + next
                        } else {
                            list.map { if (it.conversationId == conversationId) next else it }
                        }
                }
            }
            cfg.copy(conversations = list)
        }
        Toast.makeText(this, "已更新继承关系", Toast.LENGTH_SHORT).show()
        render()
    }


    private fun openTemplateEditor(groupId: String?) {
        startActivity(
            Intent(this, TemplateEditActivity::class.java)
                .putExtra(TemplateEditActivity.EXTRA_GROUP_ID, groupId),
        )
    }

    private fun openDocs() {
        startActivity(Intent(this, DocsActivity::class.java))
    }

    // ------------------------------------------------------------ 配置导出导入

    /** 导出整份配置（JSON，带缩进便于查看与改）。 */
    private fun exportConfig(config: SparkConfig) {
        val pretty = runCatching { org.json.JSONObject(configJson()).toString(2) }.getOrDefault(configJson())
        pendingExport = pretty
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, exportFileName())
        runCatching { startActivityForResult(intent, REQUEST_EXPORT_CONFIG) }
            .onFailure { Toast.makeText(this, "没有可用的文件管理器", Toast.LENGTH_SHORT).show() }
    }

    /** 从文件导入配置（整份覆盖）。 */
    private fun importConfig() {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
        runCatching { startActivityForResult(intent, REQUEST_IMPORT_CONFIG) }
            .onFailure { Toast.makeText(this, "没有可用的文件管理器", Toast.LENGTH_SHORT).show() }
    }

    private fun copyConfig(config: SparkConfig = ConfigStore.load(this)) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("星火燎原 配置", configJson(config)))
        Toast.makeText(this, "配置 JSON 已复制", Toast.LENGTH_SHORT).show()
    }

    private fun configJson(config: SparkConfig = ConfigStore.load(this)): String = ConfigCodec.encode(config)

    private fun exportFileName(): String =
        "星火燎原-配置-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()) + ".json"

    /** 读取导入的文件，交给「导入配置」页去逐项勾选（不再整份覆盖）。 */
    private fun loadImportSource(uri: android.net.Uri) {
        val raw =
            runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
                .getOrNull()
        if (raw.isNullOrBlank()) {
            Toast.makeText(this, "读不到文件内容", Toast.LENGTH_SHORT).show()
            return
        }
        val root = runCatching { org.json.JSONObject(raw) }.getOrNull()
        if (root == null || !looksLikeSparkConfig(root)) {
            Toast.makeText(this, "这不是星火燎原的配置文件", Toast.LENGTH_LONG).show()
            return
        }
        val source = ConfigCodec.decode(raw)
        importSource = source
        importSelection = source.fullSelection()
        transferChecks.clear()
        Toast.makeText(this, "已读取文件，下面挑要导入的内容", Toast.LENGTH_SHORT).show()
        render()
    }

    // ------------------------------------------------------------ 按项导出 / 导入

    /** 勾选行：勾选框 + 标题（+ 说明），[key] 形如 `t:模板id`，用于自动勾选后同步界面。 */
    private fun checkRow(
        key: String,
        label: String,
        desc: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
    ): View =
        flatRow().apply {
            val box =
                CheckBox(this@SparkSettingsActivity).apply {
                    isChecked = checked
                    setOnCheckedChangeListener { _, value -> onToggle(value) }
                }
            transferChecks[key] = box
            addView(box)
            addView(
                column().apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(text(label, 14.5f, COLOR_TEXT))
                    if (desc.isNotBlank()) addView(text(desc, 12f, COLOR_DIM))
                },
            )
        }

    /** 导出 / 导入页的会话行：勾选框 + 头像 + 名称 + 来源。 */
    private fun conversationCheckRow(
        config: SparkConfig,
        avatarSource: SparkConfig,
        key: String,
        conversationId: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
    ): View {
        val snapshot =
            avatarSource.snapshots.firstOrNull { it.conversationId == conversationId }
                ?: config.snapshots.firstOrNull { it.conversationId == conversationId }
        return flatRow().apply {
            val box =
                CheckBox(this@SparkSettingsActivity).apply {
                    isChecked = checked
                    setOnCheckedChangeListener { _, value -> onToggle(value) }
                }
            transferChecks[key] = box
            addView(box)
            val avatar =
                ImageView(this@SparkSettingsActivity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            AvatarLoader.load(this@SparkSettingsActivity, avatar, snapshot?.avatar.orEmpty(), config.conversationLabel(conversationId))
            addView(avatar)
            addView(
                column().apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(text(config.conversationLabel(conversationId), 14.5f, COLOR_TEXT))
                    addView(
                        text(
                            "来源：" + (config.group(config.conversation(conversationId)?.inheritFrom)?.name ?: "自定义 / 空白"),
                            12f,
                            COLOR_DIM,
                        ),
                    )
                },
            )
        }
    }

    /** 行尾加一个「选会话」快捷操作：点一下选中该标签 / 模板下的所有会话。 */
    private fun quickPickRow(
        item: View,
        onClick: () -> Unit,
    ): View =
        row().apply {
            addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                text("一键选中", 12f, COLOR_ACCENT).apply {
                    setPadding(dp(10), dp(10), 0, dp(10))
                    isClickable = true
                    setOnClickListener { onClick() }
                },
            )
        }

    /** 导出 / 导入页的会话卡片：标题 + 搜索 + 单聊/群聊筛选 + 列表（只重绘列表）。 */
    private fun transferConversationCard(
        config: SparkConfig,
        avatarSource: SparkConfig,
        items: List<ConversationTemplate>,
        checked: (String) -> Boolean,
        query: String,
        type: Int,
        onQuery: (String) -> Unit,
        onType: (Int) -> Unit,
        onToggle: (String, Boolean) -> Unit,
    ): View {
        var currentQuery = query
        var currentType = type
        val listHost = column()
        val card = card()

        fun isGroup(item: ConversationTemplate): Boolean {
            val snapshot =
                avatarSource.snapshots.firstOrNull { it.conversationId == item.conversationId }
                    ?: config.snapshots.firstOrNull { it.conversationId == item.conversationId }
            return snapshot?.isGroup == true
        }

        fun renderList() {
            listHost.removeAllViews()
            val filtered =
                items.filter { item ->
                    val label = config.conversationLabel(item.conversationId)
                    val hitQuery = currentQuery.isBlank() || label.contains(currentQuery, ignoreCase = true)
                    val hitType =
                        when (currentType) {
                            1 -> !isGroup(item)
                            2 -> isGroup(item)
                            else -> true
                        }
                    hitQuery && hitType
                }
            if (filtered.isEmpty()) {
                listHost.addView(text("没有匹配的会话", 12f, COLOR_DIM).apply { setPadding(0, dp(8), 0, dp(8)) })
                return
            }
            filtered.forEachIndexed { index, item ->
                if (index > 0) listHost.addView(divider())
                listHost.addView(
                    conversationCheckRow(
                        config,
                        avatarSource,
                        "c:${item.conversationId}",
                        item.conversationId,
                        checked(item.conversationId),
                    ) { value -> onToggle(item.conversationId, value) },
                )
            }
        }

        fun chip(
            label: String,
            selected: Boolean,
            onClick: () -> Unit,
        ): TextView =
            text(label, 12f, if (selected) COLOR_ACCENT else COLOR_DIM).apply {
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = rounded(if (selected) 0x1A3B7CFF else 0xFFF0F1F5.toInt(), 14)
                isClickable = true
                setOnClickListener { onClick() }
            }

        val chipsRow = row()
        fun paintChips() {
            chipsRow.removeAllViews()
            listOf("全部" to 0, "单聊" to 1, "群聊" to 2).forEach { (label, value) ->
                chipsRow.addView(chip(label, currentType == value) {
                    currentType = value
                    onType(value)
                    paintChips()
                    renderList()
                }.apply { setPadding(dp(12), dp(6), dp(12), dp(6)); if (value != 0) setPadding(dp(6), dp(6), dp(12), dp(6)) })
            }
        }

        val search =
            EditText(this@SparkSettingsActivity).apply {
                hint = "搜索会话名 / 备注"
                setSingleLine()
                setText(query)
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(0xFFF0F1F5.toInt(), 10)
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                        override fun afterTextChanged(s: Editable?) {
                            currentQuery = s?.toString().orEmpty()
                            onQuery(currentQuery)
                            renderList()
                        }
                    },
                )
            }

        card.addView(
            row().apply {
                addView(text("会话文案", 15f, COLOR_TEXT, bold = true))
                addView(spacer())
                addView(text("${items.size}", 13f, COLOR_DIM))
            },
        )
        card.addView(search.apply { setPadding(dp(12), dp(8), dp(12), dp(8)) })
        card.addView(chipsRow.apply { setPadding(0, dp(6), 0, dp(2)) })
        card.addView(divider().apply { setPadding(0, dp(6), 0, 0) })
        card.addView(listHost)
        paintChips()
        renderList()
        return card
    }

    /** 就地刷新标签行的强制状态（不触发整页重绘）。 */
    private fun refreshTransferTagRows(
        forced: Set<String>,
        selected: Set<String>,
        forcedLabel: String,
    ) {
        transferTagRows.forEach { (key, row) ->
            val id = key.removePrefix("tag:")
            val isForced = id in forced
            row.box.isChecked = isForced || id in selected
            row.box.isEnabled = !isForced
            row.desc.text = if (isForced) "被选中会话引用，$forcedLabel" else "已标记 ${row.usage} 个会话"
        }
    }

    /** 导出 / 导入页的标签行：颜色 + 名称；被选中会话引用的标签强制勾选且不可取消。 */
    private fun tagCheckRow(
        key: String,
        tag: SparkTag,
        usage: Int,
        forced: Boolean,
        checked: Boolean,
        forcedLabel: String,
        onToggle: (Boolean) -> Unit,
    ): View {
        val color = SparkConfig.tagColor(tag.color)
        return flatRow().apply {
            val box =
                CheckBox(this@SparkSettingsActivity).apply {
                    isChecked = forced || checked
                    isEnabled = !forced
                    setOnCheckedChangeListener { _, value -> if (!forced) onToggle(value) }
                }
            transferChecks[key] = box
            addView(box)
            addView(
                View(this@SparkSettingsActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) }
                    background = rounded(color, 5)
                },
            )
            val desc =
                text(
                    if (forced) "被选中会话引用，$forcedLabel" else "已标记 $usage 个会话",
                    12f,
                    COLOR_DIM,
                )
            transferTagRows[key] = TagRowView(box, desc, usage)
            addView(
                column().apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(text(tag.name, 14.5f, color))
                    addView(desc)
                },
            )
        }
    }

    /** 标签行：勾选框 + 说明文字 + 使用数，用于就地刷新强制状态。 */
    private data class TagRowView(
        val box: CheckBox,
        val desc: TextView,
        val usage: Int,
    )

    /** 把界面上所有勾选框同步成 [selection] 的状态（依赖自动勾选后调用）。 */
    private fun syncTransferChecks(selection: TransferSelection) {
        transferChecks.forEach { (key, box) ->
            val parts = key.split(":", limit = 2)
            if (parts.size != 2) return@forEach
            val kind =
                when (parts[0]) {
                    "t" -> TransferSelection.Kind.TEMPLATE
                    "c" -> TransferSelection.Kind.CONVERSATION
                    "m" -> TransferSelection.Kind.MACRO
                    "f" -> TransferSelection.Kind.FUNCTION
                    "tag" -> TransferSelection.Kind.TAG
                    else -> return@forEach
                }
            val want = selection.has(kind, parts[1])
            if (box.isChecked != want) box.isChecked = want
        }
    }

    /** 导出页：逐项勾选要带走的内容。 */
    private fun buildExportPage(
        config: SparkConfig,
        body: LinearLayout,
    ) {
        transferTagRows.clear()
        body.addView(
            card().apply {
                addView(text("导出哪些内容", 16f, bold = true))
                addView(
                    text(
                        "勾了模板会自动带上它的父模板，以及挂在上面的宏；" +
                            "脚本里用到的 ctx.functions.名字 也会一起带上（按名字文本匹配）。" +
                            "自动勾上的宏 / 函数可以再手动取消。",
                        12f,
                        COLOR_DIM,
                    ),
                )
                addView(switchRow("会话快照", "昵称 / 头像 / 火花天数（换机后不用等抖音重扫）", exportSelection.snapshots) { on ->
                    exportSelection = exportSelection.copy(snapshots = on)
                })
                addView(switchRow("今日已发记录", "避免导入后当天重复发送", exportSelection.sentDays) { on ->
                    exportSelection = exportSelection.copy(sentDays = on)
                })
            },
        )

        fun toggle(
            kind: TransferSelection.Kind,
            id: String,
            checked: Boolean,
        ) {
            val key =
                when (kind) {
                    TransferSelection.Kind.TEMPLATE -> "t:$id"
                    TransferSelection.Kind.CONVERSATION -> "c:$id"
                    TransferSelection.Kind.MACRO -> "m:$id"
                    TransferSelection.Kind.FUNCTION -> "f:$id"
                    TransferSelection.Kind.TAG -> "tag:$id"
                }
            exportSelection = exportSelection.toggle(kind, id, checked)
            exportExcluded = if (checked) exportExcluded - key else exportExcluded + key
            if (checked) exportSelection = config.withDependencies(exportSelection, kind, id, exportExcluded)
            syncTransferChecks(exportSelection)
            if (kind == TransferSelection.Kind.CONVERSATION) {
                // 只就地刷新标签行，不整页重绘
                val forced = exportSelection.conversations.flatMap { id -> config.tagsOf(id).map { it.id } }.toSet()
                exportSelection = exportSelection.copy(tags = exportSelection.tags + forced)
                refreshTransferTagRows(forced, exportSelection.tags, "强制导出")
            }
        }

        // 一键选中某标签 / 模板下的所有会话（连带父模板、宏、函数）
        fun pickConversations(ids: Collection<String>) {
            if (ids.isEmpty()) {
                Toast.makeText(this@SparkSettingsActivity, "没有可选的会话", Toast.LENGTH_SHORT).show()
                return
            }
            ids.forEach { id ->
                exportSelection =
                    config.withDependencies(
                        exportSelection.copy(conversations = exportSelection.conversations + id),
                        TransferSelection.Kind.CONVERSATION,
                        id,
                        exportExcluded,
                    )
            }
            val forced = exportSelection.conversations.flatMap { id -> config.tagsOf(id).map { it.id } }.toSet()
            exportSelection = exportSelection.copy(tags = exportSelection.tags + forced)
            syncTransferChecks(exportSelection)
            refreshTransferTagRows(forced, exportSelection.tags, "强制导出")
            Toast.makeText(this@SparkSettingsActivity, "已选中 " + ids.size + " 个会话", Toast.LENGTH_SHORT).show()
        }

        // 被选中会话引用的标签：强制导出，界面上不允许取消
        fun forcedTagIds(): Set<String> =
            exportSelection.conversations
                .flatMap { id -> config.tagsOf(id).map { tag -> tag.id } }
                .toSet()
        exportSelection = exportSelection.copy(tags = exportSelection.tags + forcedTagIds())

        if (config.groups.isNotEmpty()) {
            body.addView(
                sectionCard(
                    title = "模板",
                    count = config.groups.size,
                    items =
                        config.groups.map { group ->
                            quickPickRow(
                                checkRow(
                                    "t:${group.id}",
                                    group.name.ifBlank { "(未命名模板)" },
                                    "模板正文 " + group.body.length + " 字",
                                    group.id in exportSelection.templates,
                                ) { checked -> toggle(TransferSelection.Kind.TEMPLATE, group.id, checked) },
                            ) {
                                pickConversations(
                                    config.conversations
                                        .filter { it.inheritFrom == group.id }
                                        .map { it.conversationId },
                                )
                            }
                        },
                ),
            )
        }
        if (config.conversations.isNotEmpty()) {
            body.addView(
                transferConversationCard(
                    config = config,
                    avatarSource = config,
                    items = config.conversations,
                    checked = { it in exportSelection.conversations },
                    query = exportQuery,
                    type = exportType,
                    onQuery = { exportQuery = it },
                    onType = { exportType = it },
                    onToggle = { id, checked -> toggle(TransferSelection.Kind.CONVERSATION, id, checked) },
                ),
            )
        }
        if (config.tags.isNotEmpty()) {
            body.addView(
                sectionCard(
                    title = "标签",
                    count = config.tags.size,
                    items =
                        config.tags.map { tag ->
                            quickPickRow(
                                tagCheckRow(
                                    "tag:${tag.id}",
                                    tag,
                                    usage = config.tagUsage()[tag.id] ?: 0,
                                    forced = tag.id in forcedTagIds(),
                                    checked = tag.id in exportSelection.tags,
                                    forcedLabel = "强制导出",
                                ) { checked -> toggle(TransferSelection.Kind.TAG, tag.id, checked) },
                            ) {
                                pickConversations(
                                    config.conversations
                                        .filter { tag.id in config.conversationTags[it.conversationId].orEmpty() }
                                        .map { it.conversationId },
                                )
                            }
                        },
                ),
            )
        }
        if (config.macros.isNotEmpty()) {
            body.addView(
                sectionCard(
                    title = "宏",
                    count = config.macros.size,
                    hint = "勾了模板 / 会话会自动勾上挂在它上面的宏",
                    items =
                        config.macros.map { macro ->
                            checkRow(
                                "m:${macro.id}",
                                macro.name.ifBlank { "(未命名宏)" },
                                "挂在 " + macro.appliesTo.size + " 个目标上",
                                macro.id in exportSelection.macros,
                            ) { checked -> toggle(TransferSelection.Kind.MACRO, macro.id, checked) }
                        },
                ),
            )
        }
        if (config.functions.isNotEmpty()) {
            body.addView(
                sectionCard(
                    title = "自定义函数",
                    count = config.functions.size,
                    hint = "脚本里出现 ctx.functions.<名字> 的会自动勾上",
                    items =
                        config.functions.map { function ->
                            checkRow(
                                "f:${function.id}",
                                function.name,
                                if (function.desc.isBlank()) function.params else function.desc,
                                function.id in exportSelection.functions,
                            ) { checked -> toggle(TransferSelection.Kind.FUNCTION, function.id, checked) }
                        },
                ),
            )
        }

        body.addView(
            actionRow {
                addView(
                    iconButton(UiKit.Icons.DOWNLOAD, "导出到文件", primary = true, compact = true) {
                        if (exportSelection.isEmpty) {
                            Toast.makeText(this@SparkSettingsActivity, "先勾选要导出的内容", Toast.LENGTH_SHORT).show()
                        } else {
                            exportConfig(config.subsetOf(exportSelection))
                        }
                    },
                )
                addView(
                    iconButton(UiKit.Icons.COPY, "复制 JSON", compact = true) {
                        if (exportSelection.isEmpty) {
                            Toast.makeText(this@SparkSettingsActivity, "先勾选要导出的内容", Toast.LENGTH_SHORT).show()
                        } else {
                            copyConfig(config.subsetOf(exportSelection))
                        }
                    },
                )
            },
        )
    }

    /** 导入页：先选文件，再逐项勾选要合并进来的内容。 */
    private fun buildImportPage(
        config: SparkConfig,
        body: LinearLayout,
    ) {
        transferTagRows.clear()
        body.addView(
            card().apply {
                addView(text("导入配置", 16f, bold = true))
                addView(
                    text(
                        "选一个导出的 json，再挑要合并进来的条目。导入是合并：" +
                            "同名条目覆盖，没勾的部分保持原样（总开关也不会被改）。",
                        12f,
                        COLOR_DIM,
                    ),
                )
                addView(
                    actionRow {
                        addView(
                            iconButton(UiKit.Icons.IMPORT, "选择文件", primary = true, compact = true) { importConfig() },
                        )
                    },
                )
            },
        )

        val source = importSource
        if (source == null) {
            body.addView(text("还没有选择文件。", 13f, COLOR_DIM))
            return
        }

        body.addView(
            card().apply {
                addView(
                    text(
                        "文件里共 " + source.groups.size + " 个模板 / " + source.conversations.size +
                            " 条会话文案 / " + source.macros.size + " 个宏 / " + source.functions.size +
                            " 个函数 / " + source.tags.size + " 个标签 / " + source.snapshots.size + " 条快照。",
                        12f,
                        COLOR_DIM,
                    ),
                )
                addView(switchRow("会话快照", "覆盖同名会话的快照", importSelection.snapshots) { on ->
                    importSelection = importSelection.copy(snapshots = on)
                })
                addView(switchRow("今日已发记录", "合并进当前记录", importSelection.sentDays) { on ->
                    importSelection = importSelection.copy(sentDays = on)
                })
            },
        )

        // 被勾选的导入会话引用的标签：同样强制带上，不允许取消
        fun forcedTagIds(): Set<String> =
            importSelection.conversations
                .flatMap { id -> source.tagsOf(id).map { tag -> tag.id } }
                .toSet()
        importSelection = importSelection.copy(tags = importSelection.tags + forcedTagIds())

        fun toggle(
            kind: TransferSelection.Kind,
            id: String,
            checked: Boolean,
        ) {
            importSelection = importSelection.toggle(kind, id, checked)
            if (kind == TransferSelection.Kind.CONVERSATION) {
                val forced = importSelection.conversations.flatMap { id -> source.tagsOf(id).map { it.id } }.toSet()
                importSelection = importSelection.copy(tags = importSelection.tags + forced)
                refreshTransferTagRows(forced, importSelection.tags, "强制导入")
            }
        }

        if (source.groups.isNotEmpty()) {
            body.addView(
                sectionCard(
                    title = "模板",
                    count = source.groups.size,
                    items =
                        source.groups.map { group ->
                            checkRow(
                                "t:${group.id}",
                                group.name.ifBlank { "(未命名模板)" },
                                "文件里的模板",
                                group.id in importSelection.templates,
                            ) { checked -> toggle(TransferSelection.Kind.TEMPLATE, group.id, checked) }
                        },
                ),
            )
        }
        if (source.conversations.isNotEmpty()) {
            body.addView(
                transferConversationCard(
                    config = config,
                    avatarSource = source,
                    items = source.conversations,
                    checked = { it in importSelection.conversations },
                    query = importQuery,
                    type = importType,
                    onQuery = { importQuery = it },
                    onType = { importType = it },
                    onToggle = { id, checked -> toggle(TransferSelection.Kind.CONVERSATION, id, checked) },
                ),
            )
        }
        if (source.tags.isNotEmpty()) {
            body.addView(
                sectionCard(
                    title = "标签",
                    count = source.tags.size,
                    items =
                        source.tags.map { tag ->
                            tagCheckRow(
                                "tag:${tag.id}",
                                tag,
                                usage = source.tagUsage()[tag.id] ?: 0,
                                forced = tag.id in forcedTagIds(),
                                checked = tag.id in importSelection.tags,
                                forcedLabel = "强制导入",
                            ) { checked -> toggle(TransferSelection.Kind.TAG, tag.id, checked) }
                        },
                ),
            )
        }
        if (source.macros.isNotEmpty()) {
            body.addView(
                sectionCard(
                    title = "宏",
                    count = source.macros.size,
                    items =
                        source.macros.map { macro ->
                            checkRow(
                                "m:${macro.id}",
                                macro.name.ifBlank { "(未命名宏)" },
                                "文件里的宏",
                                macro.id in importSelection.macros,
                            ) { checked -> toggle(TransferSelection.Kind.MACRO, macro.id, checked) }
                        },
                ),
            )
        }
        if (source.functions.isNotEmpty()) {
            body.addView(
                sectionCard(
                    title = "自定义函数",
                    count = source.functions.size,
                    items =
                        source.functions.map { function ->
                            checkRow(
                                "f:${function.id}",
                                function.name,
                                function.desc,
                                function.id in importSelection.functions,
                            ) { checked -> toggle(TransferSelection.Kind.FUNCTION, function.id, checked) }
                        },
                ),
            )
        }

        body.addView(
            actionRow {
                addView(
                    iconButton(UiKit.Icons.CHECK, "导入所选", primary = true, compact = true) {
                        if (importSelection.isEmpty) {
                            Toast.makeText(this@SparkSettingsActivity, "先勾选要导入的内容", Toast.LENGTH_SHORT).show()
                        } else {
                            val merged = ConfigStore.load(this@SparkSettingsActivity).mergeFrom(source, importSelection)
                            ConfigStore.save(this@SparkSettingsActivity, merged)
                            Toast.makeText(
                                this@SparkSettingsActivity,
                                "已导入：模板 " + importSelection.templates.size + " / 文案 " +
                                    importSelection.conversations.size + " / 宏 " + importSelection.macros.size +
                                    " / 函数 " + importSelection.functions.size,
                                Toast.LENGTH_LONG,
                            ).show()
                            importSource = null
                            transferChecks.clear()
                            render()
                        }
                    },
                )
            },
        )
    }

    /** 粗校验：至少带一个本模块的字段，避免把随便一个 json 导进来清空配置。 */
    private fun looksLikeSparkConfig(root: org.json.JSONObject): Boolean =
        listOf("groups", "conversations", "snapshots", "macros", "functions", "sentDays", "enabled")
            .any { root.has(it) }

    /** 预览一段模板文案（自动带上自定义函数注入）。[sampleIndex] 让每行轮换样例，免得整页都是同一个人。 */
    private fun previewBody(
        config: SparkConfig,
        body: String,
        sampleIndex: Int,
        vars: List<com.worldmargin.autospark.app.config.TemplateVar> = emptyList(),
    ): String = preview(config, config.composeProgram(body), sampleIndex, vars)

    /** 预览某个通用模板：函数注入 + 模板 + 挂在它上面的宏。 */
    private fun previewGroup(
        config: SparkConfig,
        group: TemplateGroup,
        sampleIndex: Int,
        vars: List<com.worldmargin.autospark.app.config.TemplateVar> = emptyList(),
    ): String =
        preview(config, config.composeProgram(group.body, config.macrosForTemplate(group.id)), sampleIndex, vars)

    private fun preview(
        config: SparkConfig,
        program: String,
        sampleIndex: Int = 0,
        vars: List<com.worldmargin.autospark.app.config.TemplateVar> = emptyList(),
    ): String {
        val sample = SparkPreview.variables(SparkPreview.sampleAt(config, sampleIndex))
        return runCatching { SparkScriptEngine.render(
                com.worldmargin.autospark.app.script.TemplateVarPrelude.of(vars) + program,
                sample,
                PreviewData.env(config, SparkPreview.sampleAt(config, sampleIndex)?.conversationId),
            ) }
            .getOrElse { "（预览失败：" + (it.message ?: it.toString()) + "）" }
    }

    // ---------------------------------------------------------------- 宏/函数

    /**
     * 宏/函数页：内层分「宏」「函数」两页（说明类内容都在使用文档里，界面只放列表与操作）。
     */
    private fun buildFunctionsPage(config: SparkConfig): View {
        val page = column().apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        page.addView(
            segmented(listOf("宏 ${config.macros.size}", "函数 ${config.functions.size}"), functionsPage) { index ->
                if (index != functionsPage) {
                    functionsPage = index
                    render()
                }
            },
        )
        val rows = mutableListOf<View>()
        val filterables = mutableListOf<Pair<View, List<Pair<View, String>>>>()
        val macroEmpty = text("没有匹配的宏 / 函数", 13f, COLOR_DIM).apply { visibility = View.GONE }
        page.addView(
            searchRow("搜索宏 / 函数名与内容", macroQuery) { query ->
                macroQuery = query
                markGuideSearched(query)
                filterGroups(query, filterables)
                macroEmpty.visibility = if (query.isNotBlank() && rows.none { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
            },
        )
        val body = column().apply { setPadding(0, dp(8), 0, dp(16)) }
        if (functionsPage == PAGE_MACROS) {
            buildMacrosSection(config, body, rows)
        } else {
            buildFunctionsSection(config, body, rows)
        }
        filterables += body to rows.map { row -> row to row.textContent() }
        if (macroQuery.isNotEmpty()) filterGroups(macroQuery, filterables)
        macroEmpty.visibility = if (macroQuery.isNotBlank() && rows.none { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
        page.addView(
            scroll().apply { addView(body) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        page.addView(macroEmpty)
        page.addView(
            bottomBar {
                if (functionsPage == PAGE_MACROS) {
                    addView(iconButton(UiKit.Icons.METHOD, "新增宏", primary = true, compact = true) { openMacroEditor(null) })
                } else {
                    addView(iconButton(UiKit.Icons.NAMESPACE, "新增函数", primary = true, compact = true) { openFunctionEditor(null) })
                }
            },
        )
        return page
    }

    /** 宏列表（纯滚动列表，不再折叠）+ 新增（宏在模板脚本之后执行，可挂多个模板/会话）。 */
    private fun buildMacrosSection(
        config: SparkConfig,
        body: LinearLayout,
        rows: MutableList<View>,
    ) {
        if (config.macros.isEmpty()) {
            body.addView(text("还没有宏", 14f, COLOR_DIM))
            return
        }
        body.addView(
            text("宏在模板脚本之后执行；点一行编辑", 12f, COLOR_DIM)
                .apply { setPadding(0, 0, 0, dp(4)) },
        )
        addCards(body, rows, config.macros.map { macro -> macroItem(config, macro) })
    }

    /** 把一行包成卡片（圆角浅底，卡片之间留 8dp），替代生硬的横线分隔。 */
    private fun cardRow(item: View): View =
        column().apply {
            background = rounded(COLOR_CARD, 12)
            setPadding(dp(14), dp(2), dp(14), dp(2))
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            addView(item)
        }

    /** 把列表行以卡片形式加进页面；筛选时整张卡片一起显隐。 */
    private fun addCards(
        body: LinearLayout,
        rows: MutableList<View>,
        items: List<View>,
    ) {
        items.forEach { item ->
            val card = cardRow(item)
            body.addView(card)
            rows += card
        }
    }

    /** 宏列表里的一行：名字 + 生效目标 + 首行代码；点整行编辑。 */
    private fun macroItem(
        config: SparkConfig,
        macro: MacroScript,
    ): View {
        val targetNames = macroTargetNames(config, macro)
        return flatRow().apply {
            addView(
                column().apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(text(macro.name.ifBlank { "(未命名宏)" }, 16f, bold = true))
                    addView(
                        text(
                            if (targetNames.isEmpty()) "未挂到任何模板或会话" else targetNames.joinToString("、"),
                            13f,
                            if (targetNames.isEmpty()) COLOR_WARN else COLOR_DIM,
                        ),
                    )
                    addView(text(macro.body.lineSequence().firstOrNull().orEmpty(), 13f, COLOR_DIM))
                },
            )
            isClickable = true
            setOnClickListener { openMacroEditor(macro.id) }
        }
    }

    /** 自定义函数列表（纯滚动列表，不再折叠）+ 新增（注入成 ctx.functions.<名字>）。 */
    private fun buildFunctionsSection(
        config: SparkConfig,
        body: LinearLayout,
        rows: MutableList<View>,
    ) {
        if (config.functions.isEmpty()) {
            body.addView(text("还没有自定义函数", 14f, COLOR_DIM))
            return
        }
        body.addView(
            text("注入成 ctx.functions.<名字>；点一行编辑", 12f, COLOR_DIM)
                .apply { setPadding(0, 0, 0, dp(4)) },
        )
        addCards(body, rows, config.functions.map { fn -> functionItem(fn) })
    }

    /** 函数列表里的一行：签名 + 说明 + 前两行代码；点整行编辑。 */
    private fun functionItem(fn: UserFunction): View =
        flatRow().apply {
            addView(
                column().apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(text("ctx.functions.${fn.name}(${fn.paramList.joinToString(", ")})", 15f, COLOR_TEXT, bold = true))
                    if (fn.desc.isNotBlank()) addView(text(fn.desc, 13f, COLOR_DIM))
                    addView(text(fn.body.lineSequence().take(2).joinToString(" ").trim(), 13f, COLOR_DIM))
                },
            )
            isClickable = true
            setOnClickListener { openFunctionEditor(fn.id) }
        }

    /** 宏的生效目标名字（通用模板名 / 会话显示名）。 */
    private fun macroTargetNames(
        config: SparkConfig,
        macro: MacroScript,
    ): List<String> =
        macro.appliesTo.map { target ->
            config.group(target)?.name?.ifBlank { "(未命名模板)" }
                ?: config.snapshots.firstOrNull { it.conversationId == target }?.displayName
                ?: "(已删除的目标)"
        }

    private fun openMacroEditor(macroId: String?) {
        startActivity(Intent(this, MacroEditActivity::class.java).putExtra(MacroEditActivity.EXTRA_MACRO_ID, macroId))
    }

    private fun openFunctionEditor(functionId: String?) {
        startActivity(
            Intent(this, FunctionEditActivity::class.java)
                .putExtra(FunctionEditActivity.EXTRA_FUNCTION_ID, functionId),
        )
    }

    // -------------------------------------------------------------------- 标签

    /**
     * 标签页：顶层标签之一（与模板同级），只有一个滚动列表，不再做折叠分组。
     *
     * 点一行 = 管理这个标签包含的会话；右侧图标改名 / 换色 / 删除。
     */
    private fun buildTagPage(config: SparkConfig): View {
        val page = column()
        val body = column().apply { setPadding(dp(16), dp(8), dp(16), dp(16)) }
        if (config.tags.isEmpty()) {
            body.addView(
                text("还没有标签。标签用来把会话分组（家人 / 同事 / 待续……），只影响整理和筛选，不影响发送。", 14f, COLOR_DIM),
            )
        } else {
            val usage = config.tagUsage()
            body.addView(
                text("点击配置要使用该标签的会话；右侧图标改名 / 换色 / 删除。", 12f, COLOR_DIM)
                    .apply { setPadding(0, 0, 0, dp(4)) },
            )
            config.tags.forEach { tag ->
                body.addView(cardRow(tagRow(tag, usage[tag.id] ?: 0)))
            }
        }
        page.addView(
            scroll().apply { addView(body) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        page.addView(
            bottomBar {
                addView(iconButton(UiKit.Icons.ADD, "新增标签", primary = true, compact = true) { showTagDialog(null) })
            },
        )
        return page
    }

    private fun tagRow(
        tag: SparkTag,
        count: Int,
    ): View =
        flatRow().apply {
            addView(
                View(this@SparkSettingsActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(10) }
                    background = rounded(SparkConfig.tagColor(tag.color), 7)
                },
            )
            addView(
                column().apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(text(tag.name, 15f, COLOR_TEXT, bold = true))
                    addView(text(if (count == 0) "还没有会话用它 · 点击配置目标" else "$count 个会话 · 点击配置目标", 12f, COLOR_DIM))
                },
            )
            addView(iconButton(UiKit.Icons.EDIT, "", compact = true, hint = "改名 / 换色") { showTagDialog(tag) })
            addView(iconButton(UiKit.Icons.TRASH, "", compact = true, hint = "删除") { confirmDeleteTag(tag) })
            isClickable = true
            setOnClickListener { openTagMembership(tag) }
        }

    /** 新增 / 编辑标签（名字 + 颜色表单）。 */
    private fun showTagDialog(existing: SparkTag?) {
        val config = ConfigStore.load(this)
        val nameField =
            EditText(this).apply {
                setText(existing?.name.orEmpty())
                hint = "标签名，比如：家人 / 同事"
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT
            }
        var color = existing?.color ?: (config.tags.size % SparkConfig.TAG_COLORS.size)
        val colorsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun paintColors() {
            colorsRow.removeAllViews()
            SparkConfig.TAG_COLORS.indices.forEach { index ->
                colorsRow.addView(
                    View(this).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(6) }
                        background =
                            rounded(SparkConfig.tagColor(index), 15).apply {
                                if (index == color) setStroke(dp(2), COLOR_TEXT)
                            }
                        isClickable = true
                        setOnClickListener {
                            color = index
                            paintColors()
                        }
                    },
                )
            }
        }
        paintColors()
        val box =
            column().apply {
                setPadding(dp(20), dp(8), dp(20), dp(0))
                addView(nameField)
                addView(text("颜色", 12f, COLOR_DIM).apply { setPadding(0, dp(10), 0, dp(6)) })
                addView(colorsRow)
            }
        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle(if (existing == null) "新增标签" else "编辑标签")
                .setView(box)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .apply { if (existing != null) setNeutralButton("删除", null) }
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameField.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "标签名不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (config.tags.any { it.id != existing?.id && it.name == name }) {
                    Toast.makeText(this, "已经有同名标签了", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ConfigStore.update(this) { cfg ->
                    if (existing == null) {
                        cfg.copy(
                            tags =
                                cfg.tags +
                                    SparkTag(
                                        id = java.util.UUID.randomUUID().toString(),
                                        name = name,
                                        color = color,
                                        createdAt = System.currentTimeMillis(),
                                    ),
                        )
                    } else {
                        cfg.copy(tags = cfg.tags.map { if (it.id == existing.id) it.copy(name = name, color = color) else it })
                    }
                }
                dialog.dismiss()
                render()
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    dialog.dismiss()
                    confirmDeleteTag(existing)
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteTag(tag: SparkTag) {
        val used = ConfigStore.load(this).conversationTags.count { tag.id in it.value }
        AlertDialog
            .Builder(this)
            .setTitle("删除标签「${tag.name}」？")
            .setMessage(if (used > 0) "有 $used 个会话在用这个标签，删除后会从它们身上一起摘掉。" else "这个标签还没有被使用。")
            .setPositiveButton("删除") { _, _ ->
                ConfigStore.update(this) { it.withoutTag(tag.id) }
                Toast.makeText(this, "已删除标签", Toast.LENGTH_SHORT).show()
                render()
            }.setNegativeButton("取消", null)
            .show()
    }

    /** 点标签行：进会话选择页勾选这个标签包含哪些会话（确定即替换归属）。 */
    private fun openTagMembership(tag: SparkTag) {
        val config = ConfigStore.load(this)
        if (config.snapshots.none { !it.isHidden }) {
            Toast.makeText(this, "暂无会话快照，请先刷新会话", Toast.LENGTH_SHORT).show()
            return
        }
        pendingTagId = tag.id
        val preselected = config.conversationTags.filterValues { tag.id in it }.keys.toList()
        runCatching {
            startActivityForResult(
                Intent(this, ConversationPickerActivity::class.java)
                    .putExtra(ConversationPickerActivity.EXTRA_TITLE, "「${tag.name}」包含的会话")
                    .putExtra(ConversationPickerActivity.EXTRA_ONLY_SINGLE, false)
                    .putStringArrayListExtra(ConversationPickerActivity.EXTRA_SELECTED, ArrayList(preselected)),
                REQUEST_TAG_MEMBERS,
            )
        }.onFailure { Toast.makeText(this, "打不开会话选择页", Toast.LENGTH_SHORT).show() }
    }

    /** 会话选择页回来：按勾选结果替换这个标签的会话集合。 */
    private fun applyTagMembership(data: Intent?) {
        val tagId = pendingTagId ?: return
        pendingTagId = null
        val picked = data?.getStringArrayListExtra(ConversationPickerActivity.EXTRA_RESULT).orEmpty().toSet()
        ConfigStore.update(this) { config ->
            val current = config.conversationTags.filterValues { tagId in it }.keys
            var next = config
            val add = picked - current
            val remove = current - picked
            if (add.isNotEmpty()) next = next.applyTag(add, tagId, true)
            if (remove.isNotEmpty()) next = next.applyTag(remove, tagId, false)
            next
        }
        render()
    }

    // -------------------------------------------------------------------- 设置

    /**
     * 设置页只留最常用的开关与入口：概览 / 手动归属 / 编辑器 / 宿主状态 / 关于都进二级页面
     * （见 [buildSubPage]），避免一屏塞满。
     */
    private fun buildSettingsPage(config: SparkConfig): View {
        val body = column().apply { setPadding(dp(16), dp(8), dp(16), dp(16)) }

        body.addView(sectionTitle("总开关"))
        body.addView(
            card().apply {
                addView(
                    switchRow("自动续火花", "打开后才会自动发送；默认关闭", config.enabled) { checked ->
                        ConfigStore.update(this@SparkSettingsActivity) { it.copy(enabled = checked) }
                    },
                )
            },
        )

        body.addView(
            row().apply {
                setPadding(0, dp(12), 0, 0)
                addView(
                    iconButton(UiKit.Icons.REFRESH, "刷新会话", primary = true, compact = true) {
                        withHostOnline {
                            sendBroadcast(Intent(ConfigContract.ACTION_REFRESH_SNAPSHOT))
                            Toast.makeText(this@SparkSettingsActivity, "已请求刷新，请稍候…", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                addView(iconButton(UiKit.Icons.BOOK, "使用文档", compact = true) { openDocs() })
            },
        )

        body.addView(sectionTitle("更多"))
        body.addView(
            menuRow(UiKit.Icons.GRAPH, "概览", "会话数、今日已续 / 待续 / 待重燃、今日已发") {
                openSubPage(SUB_OVERVIEW)
            },
        )
        body.addView(
            menuRow(UiKit.Icons.SWAP, "手动归属", manualSummary(config)) {
                openSubPage(SUB_ASSIGN)
            },
        )
        body.addView(
            menuRow(UiKit.Icons.SERVER, "宿主状态", hostSummary(config)) {
                openSubPage(SUB_HOST)
            },
        )
        val basicList = basicSteps(config)
        val advancedList = advancedSteps(config)
        val basicDone = basicList.count { it.done }
        val advancedDone = advancedList.count { it.done }
        val guidePending = basicDone < basicList.size || advancedDone < advancedList.size
        body.addView(
            menuRow(
                UiKit.Icons.LIGHTBULB,
                "新手引导",
                when {
                    !guidePending -> "基础 $basicDone/${basicList.size} · 进阶 $advancedDone/${advancedList.size} · 全部完成，点这里预览"
                    config.guideDismissed -> "已隐藏，点这里重新打开"
                    else -> "基础 $basicDone/${basicList.size} · 进阶 $advancedDone/${advancedList.size}"
                },
            ) { openSubPage(SUB_GUIDE) },
        )
        body.addView(
            menuRow(UiKit.Icons.DOWNLOAD, "配置", "导出 / 导入整份配置（换机、备份用）") {
                openSubPage(SUB_CONFIG)
            },
        )
        body.addView(
            menuRow(UiKit.Icons.INFO, "关于", "版本与配置文件位置") {
                openSubPage(SUB_ABOUT)
            },
        )

        return column().apply { addView(scroll().apply { addView(body) }) }
    }

    private fun manualSummary(config: SparkConfig): String =
        if (config.forcedRenew.isEmpty() && config.forcedSkip.isEmpty()) {
            "没有手动标记，全部按抖音判定"
        } else {
            "手动移入 ${config.forcedRenew.size} 个 / 移出 ${config.forcedSkip.size} 个"
        }

    private fun hostSummary(config: SparkConfig): String =
        if (config.hostNoteAt == 0L) {
            "无记录"
        } else {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(config.hostNoteAt))
        }

    /** 一行「名称 —— 值」。 */
    private fun infoLine(
        label: String,
        value: String,
    ): View =
        row().apply {
            setPadding(0, dp(3), 0, dp(3))
            addView(text(label, 14f, COLOR_TEXT))
            addView(spacer())
            addView(text(value, 14f, COLOR_ACCENT))
        }

    private fun sentToday(config: SparkConfig): Int {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return config.sentDays.count { it.value == today }
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "未知"

    private fun confirmClearManual() {
        AlertDialog
            .Builder(this)
            .setTitle("清除手动移动标记？")
            .setMessage("清除后，全部会话重新按抖音的火花状态自动判定归属。")
            .setPositiveButton("清除") { _, _ ->
                ConfigStore.update(this) { it.copy(forcedRenew = emptySet(), forcedSkip = emptySet()) }
                Toast.makeText(this, "已清除手动标记", Toast.LENGTH_SHORT).show()
                render()
            }.setNegativeButton("取消", null)
            .show()
    }

    companion object {
        val TABS = listOf("续火花", "模板", "标签", "宏/函数", "设置")
        val TAB_ICONS =
            listOf(
                UiKit.Icons.FIRE,
                UiKit.Icons.TEMPLATE,
                UiKit.Icons.TAG,
                UiKit.Icons.CODE,
                UiKit.Icons.GEAR,
            )

        /** 设置页的二级页面：键 → 标题。 */
        val SUB_PAGES =
            linkedMapOf(
                SUB_OVERVIEW to "概览",
                SUB_ASSIGN to "手动归属",
                SUB_EDITOR to "编辑器",
                SUB_CONFIG to "配置",
                SUB_EXPORT to "导出配置",
                SUB_IMPORT to "导入配置",
                SUB_HOST to "宿主状态",
                SUB_GUIDE to "新手引导",
                SUB_ABOUT to "关于",
            )

        const val TAB_SPARK = 0
        const val TAB_TEMPLATE = 1
        const val TAB_TAG = 2
        const val TAB_FUNCTIONS = 3
        const val TAB_SETTINGS = 4

        const val PAGE_SELECTED = 0
        const val PAGE_UNSELECTED = 1

        /** 「宏/函数」页内的分页下标。 */
        const val PAGE_MACROS = 0
        const val PAGE_FUNCTIONS = 1

        const val SUB_OVERVIEW = "overview"
        const val SUB_ASSIGN = "assign"
        const val SUB_EDITOR = "editor"
        const val SUB_CONFIG = "config"
        const val SUB_EXPORT = "export"
        const val SUB_IMPORT = "import"
        const val SUB_HOST = "host"
        const val SUB_GUIDE = "guide"
        const val SUB_ABOUT = "about"

        /** 手动发送等回执的超时（抖音没运行时不至于一直等）。 */
        const val SEND_TIMEOUT_MS = 12_000L

        /** 宿主存活时间戳的有效期：超过就认为抖音已经不在运行了。 */
        const val HOST_FRESH_MS = 90_000L

        /** 探活广播等回执的时间。 */
        const val HOST_PING_MS = 1_500L

        /** 抖音冷启动后等它把模块装载好的时间。 */
        const val HOST_BOOT_WAIT_MS = 4_000L

        const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"

        /** 配置导出 / 导入的文件选择请求码。 */
        const val REQUEST_EXPORT_CONFIG = 0x61
        const val REQUEST_IMPORT_CONFIG = 0x62

        /** 派生项长按 → 编辑父模板变量。 */
        const val REQUEST_GROUP_VARS = 0x63

        /** 派生项长按 → 编辑这个会话自己的变量。 */
        const val REQUEST_CONVERSATION_VARS = 0x64
        const val REQUEST_PICK_MEMBERS = 0x65
        const val REQUEST_PICK_CLONE = 0x66
        const val REQUEST_TAG_MEMBERS = 0x68

        /** 从别处直接落到某个顶层标签（例如会话编辑页的「管理标签」）。 */
        const val EXTRA_TAB = "extra_tab"

        /** 「来源」筛选里代表「未写文案」的哨兵值（模板 id 不会是它）。 */
        private const val SRC_NONE = "none:"

        /** 抖音火苗状态码（见 ConversationSnapshot 注释）。 */
        const val SPARK_DONE = 1
        const val SPARK_PENDING = 2
        const val SPARK_RECOVER = 3
    }
}

/**
 * 预览用变量：抖音侧的值优先用「会话快照」这个缓存（昵称/备注/天数/状态…），
 * 只有完全没有快照时才退到占位值，避免预览因为缺变量而报错。
 */
object SparkPreview {
    fun variables(snapshot: ConversationSnapshot? = null): Map<String, Any?> {
        val now = Date()
        val name = snapshot?.displayName?.takeIf { it.isNotBlank() } ?: "小美"
        return mapOf(
            "date" to SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now),
            "time" to SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
            "weekday" to SimpleDateFormat("EEEE", Locale.getDefault()).format(now),
            "timestamp" to now.time,
            "nickname" to (snapshot?.name?.takeIf { it.isNotBlank() } ?: name),
            "remark" to (snapshot?.remark ?: ""),
            "name" to name,
            "days" to (snapshot?.days ?: 7),
            "state" to (snapshot?.state ?: 2),
            "stateText" to (snapshot?.stateText?.takeIf { it.isNotBlank() } ?: "待续"),
            "peerUid" to (snapshot?.peerUid?.takeIf { it.isNotBlank() } ?: "0"),
            "conversationId" to (snapshot?.conversationId ?: "sample"),
        )
    }

    /** 样例候选：已选目标的单聊排在前面，其它单聊跟在后面。 */
    fun samples(config: SparkConfig): List<ConversationSnapshot> {
        val singles = config.snapshots.filter { !it.isGroup && !it.isHidden }
        val selected = singles.filter { config.needsRenewal(it) }
        return selected + singles.filterNot { config.needsRenewal(it) }
    }

    /** 第 [index] 个样例（按行轮换，避免整页预览都是同一个人）。 */
    fun sampleAt(
        config: SparkConfig,
        index: Int,
    ): ConversationSnapshot? {
        val list = samples(config)
        return if (list.isEmpty()) null else list[index.mod(list.size)]
    }

    fun sample(config: SparkConfig): ConversationSnapshot? = sampleAt(config, 0)
}
