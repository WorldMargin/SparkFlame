package com.worldmargin.autospark.features.autospark.ui

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
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.worldmargin.autospark.features.autospark.model.BuiltinTemplate
import com.worldmargin.autospark.features.autospark.model.BuiltinTemplates
import com.worldmargin.autospark.features.autospark.model.ConversationSnapshot
import com.worldmargin.autospark.features.autospark.model.ConfigCodec
import com.worldmargin.autospark.features.autospark.model.ConversationTemplate
import com.worldmargin.autospark.features.autospark.model.MacroScript
import com.worldmargin.autospark.features.autospark.model.SparkConfig
import com.worldmargin.autospark.features.autospark.model.TemplateGroup
import com.worldmargin.autospark.features.autospark.model.TemplateOps
import com.worldmargin.autospark.features.autospark.model.TemplateSource
import com.worldmargin.autospark.features.autospark.model.UserFunction
import com.worldmargin.autospark.features.autospark.provider.ConfigContract
import com.worldmargin.autospark.features.autospark.script.SparkScriptEngine
import com.worldmargin.autospark.features.autospark.store.ConfigStore
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_ACCENT
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_BG
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_CARD
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_DIM
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_TEXT
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_WARN
import com.worldmargin.autospark.features.autospark.ui.UiKit.asClickable
import com.worldmargin.autospark.features.autospark.ui.UiKit.actionRow
import com.worldmargin.autospark.features.autospark.ui.UiKit.button
import com.worldmargin.autospark.features.autospark.ui.UiKit.card
import com.worldmargin.autospark.features.autospark.ui.UiKit.column
import com.worldmargin.autospark.features.autospark.ui.UiKit.divider
import com.worldmargin.autospark.features.autospark.ui.UiKit.dp
import com.worldmargin.autospark.features.autospark.ui.UiKit.enterFromLeft
import com.worldmargin.autospark.features.autospark.ui.UiKit.enterFromRight
import com.worldmargin.autospark.features.autospark.ui.UiKit.icon
import com.worldmargin.autospark.features.autospark.ui.UiKit.iconButton
import com.worldmargin.autospark.features.autospark.ui.UiKit.menuRow
import com.worldmargin.autospark.features.autospark.ui.UiKit.navBar
import com.worldmargin.autospark.features.autospark.ui.UiKit.padSystemBars
import com.worldmargin.autospark.features.autospark.ui.UiKit.rounded
import com.worldmargin.autospark.features.autospark.ui.UiKit.row
import com.worldmargin.autospark.features.autospark.ui.UiKit.scroll
import com.worldmargin.autospark.features.autospark.ui.UiKit.sectionTitle
import com.worldmargin.autospark.features.autospark.ui.UiKit.segmented
import com.worldmargin.autospark.features.autospark.ui.UiKit.spacer
import com.worldmargin.autospark.features.autospark.ui.UiKit.switchRow
import com.worldmargin.autospark.features.autospark.ui.UiKit.text
import com.worldmargin.autospark.features.autospark.ui.UiKit.titleBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 续火花管理主界面（运行在模块进程）。
 *
 * 底部四个标签：续火花 / 模板 / 宏/函数 / 设置；
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

    /** 多选中的会话 ID。 */
    private val selected = linkedSetOf<String>()

    /** 「续火花」页里会话行 → 勾选框，用于原地同步勾选状态。 */
    private val rowChecks = mutableMapOf<String, CheckBox>()

    private var selectionBar: View? = null
    private var selectionCount: TextView? = null

    private val snapshotReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    /** 导出/导入配置的文件选择回调。 */
    @Deprecated("文件选择结果")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
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

            REQUEST_IMPORT_CONFIG -> applyImportedConfig(uri)
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(snapshotReceiver) }
    }

    private fun registerSnapshotReceiver() {
        val filter = IntentFilter().apply {
            addAction(ConfigContract.ACTION_SNAPSHOT_UPDATED)
            addAction(ConfigContract.ACTION_SEND_DONE)
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
        awaitingSendResult = true
        sendBroadcast(
            Intent(ConfigContract.ACTION_SEND_NOW)
                .putStringArrayListExtra(ConfigContract.EXTRA_TARGETS, ArrayList(targets)),
        )
        Toast.makeText(this, "已请求发送，等待抖音返回结果…", Toast.LENGTH_SHORT).show()
        // 抖音没在运行 / 模块没注入时不会有回执，超时给个提示
        pageHost.postDelayed({
            if (awaitingSendResult) {
                awaitingSendResult = false
                Toast.makeText(this, "没等到结果：抖音可能没在运行（打开抖音后再试）", Toast.LENGTH_LONG).show()
            }
        }, SEND_TIMEOUT_MS)
    }

    private fun render() {
        val config = ConfigStore.load(this)
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
                val conversations = config.snapshots.filter { !it.isGroup }
                val renewal = conversations.filter { config.needsRenewal(it) }
                body.addView(infoCard("会话总数", conversations.size.toString()))
                body.addView(infoCard("今日待续", renewal.count { it.state == SPARK_PENDING }.toString()))
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
                                    button("清除手动标记").apply {
                                        setOnClickListener {
                                            ConfigStore.update(this@SparkSettingsActivity) {
                                                it.copy(forcedRenew = emptySet(), forcedSkip = emptySet()).pruneTemplates(System.currentTimeMillis())
                                            }
                                            Toast.makeText(this@SparkSettingsActivity, "已清除手动标记", Toast.LENGTH_SHORT).show()
                                            render()
                                        }
                                    },
                                )
                            },
                        )
                    },
                )
            }

            SUB_EDITOR ->
                body.addView(
                    card().apply {
                        addView(
                            switchRow(
                                "Monaco（VSCode 同款）",
                                "补全 JS 内置成员与 ctx、类型检查；关掉则用原生编辑器",
                                config.useMonacoEditor,
                            ) { checked ->
                                ConfigStore.update(this@SparkSettingsActivity) { it.copy(useMonacoEditor = checked) }
                            },
                        )
                    },
                )

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

            SUB_CONFIG -> {
                body.addView(
                    card().apply {
                        addView(text("导出 / 导入配置", 16f, bold = true))
                        addView(
                            text(
                                "导出的是整份配置（模板、会话文案、宏、自定义函数、快照、今日已发记录）。" +
                                    "导入会整份覆盖当前配置，覆盖前会再确认一次。",
                                12f,
                                COLOR_DIM,
                            ),
                        )
                        addView(
                            actionRow {
                                addView(iconButton(UiKit.Icons.EXPORT, "导出到文件", primary = true) { exportConfig() })
                                addView(iconButton(UiKit.Icons.IMPORT, "从文件导入") { importConfig() })
                                addView(iconButton(UiKit.Icons.COPY, "复制") { copyConfig() })
                            },
                        )
                    },
                )
                body.addView(
                    card().apply {
                        addView(text("当前配置", 14f, bold = true))
                        addView(infoLine("模板", "${config.groups.size} 个"))
                        addView(infoLine("会话文案", "${config.conversations.size} 条"))
                        addView(infoLine("宏", "${config.macros.size} 个"))
                        addView(infoLine("自定义函数", "${config.functions.size} 个"))
                        addView(infoLine("会话快照", "${config.snapshots.size} 条"))
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
                body.addView(
                    card().apply {
                        addView(text("署名与来源", 14f, bold = true))
                        addView(
                            text(
                                "· yfishyon/doumcp（原仓库）—— 抖音逆向与注入能力\n" +
                                    "  github.com/yfishyon/doumcp\n" +
                                    "· WorldMargin —— 模板 / 宏 / 函数 这套思路\n" +
                                    "· DeepSeek 油猴脚本 迁移至安卓",
                                13f,
                                COLOR_DIM,
                            ),
                        )
                        addView(
                            text(
                                "本模块 fork 自 doumcp（Apache-2.0 许可），只保留「续火花」功能；" +
                                    "界面、脚本引擎、模板与宏体系为重写。",
                                12f,
                                COLOR_DIM,
                            ),
                        )
                    },
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

    /** 「续火花」页：内层「已选目标 / 未选目标」两页 + 勾选后的批量移动条。 */
    private fun buildSparkPage(config: SparkConfig): View {
        val conversations = config.snapshots.filter { !it.isGroup }
        val renewal = conversations.filter { config.needsRenewal(it) }
        val idle = conversations.filterNot { config.needsRenewal(it) }
        val page = column().apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        rowChecks.clear()
        selectionBar = null
        selectionCount = null

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
        val manualTargets = if (selected.isNotEmpty()) selected.toList() else renewal.map { it.conversationId }
        page.addView(
            row().apply {
                setPadding(0, dp(6), 0, 0)
                addView(
                    iconButton(
                        UiKit.Icons.MAIL,
                        if (selected.isNotEmpty()) {
                            "发送勾选的 ${selected.size} 个"
                        } else {
                            "发送已选目标 ${renewal.size} 个"
                        },
                        primary = true,
                        compact = true,
                    ) { confirmManualSend(manualTargets) },
                )
                addView(spacer())
            },
        )

        val current = if (sparkPage == PAGE_SELECTED) renewal else idle
        val list = column().apply { setPadding(0, dp(8), 0, dp(16)) }
        if (conversations.isEmpty()) {
            list.addView(text("没有会话快照。先打开一次抖音，再回来点「设置 → 刷新会话」。", 14f, COLOR_DIM))
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
                current.forEach { list.addView(buildConversationRow(config, it)) }
            }
            list.addView(
                text("点会话行编辑该会话的文案；勾选后用下方按钮批量移动归属。", 12f, COLOR_DIM).apply {
                    setPadding(0, dp(14), 0, 0)
                },
            )
        }
        page.addView(scroll().apply { addView(list) })
        page.addView(buildSelectionBar())
        applySelection()
        return page
    }

    /** 多选后的操作条（没有勾选时隐藏）：上行计数 + 取消，下行两个批量移动按钮。 */
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
                        button("→ 已选目标").apply {
                            layoutParams =
                                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            setOnClickListener { moveSelection(true) }
                        },
                    )
                    addView(
                        button("→ 未选目标").apply {
                            layoutParams =
                                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            setOnClickListener { moveSelection(false) }
                        },
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
        selectionCount?.text = "已选 ${selected.size} 个"
        selectionBar?.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 火花状态文案：今天已互动 / 今天还没互动 / 待重燃（断了在恢复）/ 可点亮 / 没有火苗。 */
    private fun sparkLabel(snapshot: ConversationSnapshot): String =
        when {
            // 没有火苗时抖音可能给一句引导文案（互为好友但还没建立火苗 → 「可点亮」）
            !snapshot.hasSpark -> snapshot.stateText.takeIf { it.isNotBlank() } ?: "无火花"
            snapshot.state == SPARK_DONE -> "火花 ${snapshot.days} 天 · 今日已续"
            snapshot.state == SPARK_PENDING -> "火花 ${snapshot.days} 天 · 今日待续"
            snapshot.state == SPARK_RECOVER ->
                "待重燃 ${snapshot.recoverDays}/${snapshot.recoverNeedDays.coerceAtLeast(1)} 天"
            else -> "火花 ${snapshot.days} 天"
        }

    private fun buildConversationRow(
        config: SparkConfig,
        snapshot: ConversationSnapshot,
    ): View {
        val id = snapshot.conversationId
        val template = config.conversation(id)
        val sourceText =
            when {
                template == null -> "未选模板（不发）"
                template.inheritFrom == null -> "空白（自定义）"
                else -> "继承：" + (config.group(template.inheritFrom)?.name ?: "(已删除的模板)")
            }
        val marker =
            when (id) {
                in config.forcedRenew -> "　手动移入"
                in config.forcedSkip -> "　手动移出"
                else -> ""
            }
        val sparkText = sparkLabel(snapshot)
        val sparkColor =
            when {
                snapshot.state == SPARK_RECOVER || snapshot.state == SPARK_PENDING -> COLOR_WARN
                snapshot.hasSpark -> COLOR_ACCENT
                else -> COLOR_DIM
            }
        return card().apply {
            addView(
                row().apply {
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
                                LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) }
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
                                    addView(text(snapshot.displayName.ifBlank { "(无名)" }, 16f, bold = true))
                                    addView(text("　$sparkText", 13f, sparkColor))
                                    if (marker.isNotEmpty()) addView(text(marker, 12f, COLOR_ACCENT))
                                },
                            )
                            addView(text(sourceText, 13f, COLOR_DIM))
                            if (template?.modified == true) {
                                addView(text("已本地修改（父模板同步时不会被覆盖）", 12f, COLOR_WARN))
                            }
                        },
                    )
                },
            )
        }.asClickable {
            startActivity(
                Intent(this@SparkSettingsActivity, ConversationEditActivity::class.java)
                    .putExtra(ConversationEditActivity.EXTRA_CONVERSATION_ID, snapshot.conversationId),
            )
        }.apply {
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
     * - 「选会话继承」= 跟随该模板（父模板改了会同步）；「克隆到会话」= 只复制内容、内容固定。
     */
    private fun buildTemplatesPage(config: SparkConfig): View {
        val body = column().apply { setPadding(dp(16), dp(0), dp(16), dp(16)) }

        body.addView(
            sectionToggle("内置模板", "${BuiltinTemplates.ALL.size} 个", builtinSectionOpen) {
                builtinSectionOpen = !builtinSectionOpen
                render()
            },
        )
        if (builtinSectionOpen) {
            BuiltinTemplates.ALL.forEachIndexed { index, builtin ->
                val id = SparkConfig.BUILTIN_PREFIX + builtin.id
                val derived = config.membersOf(id).size
                val expanded = id == expandedTemplateId
                body.addView(
                    card().apply {
                        addView(
                            row().apply {
                                addView(text(if (expanded) "▾ " else "▸ ", 14f, COLOR_DIM))
                                addView(text(builtin.name, 16f, bold = true))
                                if (builtin.id == BuiltinTemplates.BLANK.id) addView(text("　默认", 12f, COLOR_DIM))
                                addView(spacer())
                                if (derived > 0) addView(text("派生 $derived", 12f, COLOR_ACCENT))
                            },
                        )
                        addView(text(builtin.desc, 13f, COLOR_DIM))
                        if (builtin.body.isNotBlank()) {
                            addView(text(previewBody(config, builtin.body, index), 13f, COLOR_DIM))
                        }
                        if (expanded) {
                            buildChildren(config, id, this)
                            if (builtin.body.isNotBlank()) {
                                addView(
                                    row().apply {
                                        setPadding(0, dp(8), 0, 0)
                                        addView(button("添加为我的模板", primary = true).apply { setOnClickListener { addBuiltinAsTemplate(builtin) } })
                                        addView(button("选会话继承").apply { setOnClickListener { pickMembers(id, builtin.name) } })
                                        addView(button("克隆到会话").apply { setOnClickListener { cloneBodyToConversations(builtin.name, builtin.body) } })
                                    },
                                )
                            }
                        }
                    }.asClickable {
                        expandedTemplateId = if (expanded) null else id
                        render()
                    },
                )
            }
        }

        body.addView(
            sectionToggle("自定义模板", "${config.groups.size} 个", customSectionOpen) {
                customSectionOpen = !customSectionOpen
                render()
            },
        )
        if (customSectionOpen) {
            if (config.groups.isEmpty()) {
                body.addView(text("还没有模板。可以从内置模板「添加为我的模板」，或直接新建。", 14f, COLOR_DIM))
            } else {
                config.groups.forEachIndexed { index, group ->
                    val derived = config.membersOf(group.id).size
                    val expanded = group.id == expandedTemplateId
                    body.addView(
                        card().apply {
                            addView(
                                row().apply {
                                    addView(text(if (expanded) "▾ " else "▸ ", 14f, COLOR_DIM))
                                    addView(text(group.name.ifBlank { "(未命名)" }, 16f, bold = true))
                                    addView(spacer())
                                    if (derived > 0) addView(text("派生 $derived", 12f, COLOR_ACCENT))
                                },
                            )
                            addView(text(previewGroup(config, group, index), 13f, COLOR_DIM))
                            if (expanded) {
                                buildChildren(config, group.id, this)
                                addView(
                                    row().apply {
                                        setPadding(0, dp(8), 0, 0)
                                        addView(button("编辑模板", primary = true).apply { setOnClickListener { openTemplateEditor(group.id) } })
                                        addView(button("选会话继承").apply { setOnClickListener { pickMembers(group.id, group.name) } })
                                        addView(button("克隆到会话").apply { setOnClickListener { cloneBodyToConversations(group.name, group.body) } })
                                    },
                                )
                            }
                        }.asClickable {
                            expandedTemplateId = if (expanded) null else group.id
                            render()
                        },
                    )
                }
            }
            body.addView(
                row().apply {
                    setPadding(0, dp(12), 0, 0)
                    addView(button("＋ 新增模板", primary = true).apply { setOnClickListener { openTemplateEditor(null) } })
                },
            )
        }
        return column().apply { addView(scroll().apply { addView(body) }) }
    }

    /** 可折叠的段标题。 */
    private fun sectionToggle(
        title: String,
        count: String,
        open: Boolean,
        onToggle: () -> Unit,
    ): View =
        row().apply {
            setPadding(0, dp(16), 0, dp(4))
            isClickable = true
            setOnClickListener { onToggle() }
            addView(text(if (open) "▾ " else "▸ ", 13f, COLOR_DIM, bold = true))
            addView(text(title, 14f, COLOR_TEXT, bold = true))
            addView(spacer())
            addView(text(count, 13f, COLOR_DIM))
        }

    /**
     * 展开后列出的派生模板（继承自该模板的会话），多层派生按缩进铺开：
     * `A → B → C` 时在 A 下面先列 B，再缩进列 C。
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
            body.addView(
                row().apply {
                    setPadding(dp(depth * 14), dp(6), 0, 0)
                    addView(text("└ " + config.conversationLabel(member.conversationId), 14f, COLOR_TEXT))
                    when {
                        member.modified -> addView(text("　已本地修改", 12f, COLOR_WARN))
                        member.inheritFrom == null -> addView(text("　内容固定", 12f, COLOR_DIM))
                    }
                },
            )
            buildChildren(config, member.conversationId, body, depth + 1)
        }
    }

    /** 把内置模板复制成一份可编辑的自定义模板。 */
    private fun addBuiltinAsTemplate(builtin: BuiltinTemplate) {
        if (builtin.body.isBlank()) {
            Toast.makeText(this, "空白模板不用添加，未选模板就是不发送", Toast.LENGTH_SHORT).show()
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

    /** 把某个模板的内容**克隆**给选中的会话（内容固定，不跟随父模板）。 */
    private fun cloneBodyToConversations(
        name: String,
        body: String,
    ) {
        val snapshots = ConfigStore.load(this).snapshots.filter { !it.isGroup }
        if (snapshots.isEmpty()) {
            Toast.makeText(this, "暂无会话快照，请先刷新会话", Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(snapshots.size)
        AlertDialog
            .Builder(this)
            .setTitle("克隆「$name」到会话（内容固定）")
            .setMultiChoiceItems(snapshots.map { it.displayName.ifBlank { "(无名)" } }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }.setPositiveButton("克隆") { _, _ ->
                val now = System.currentTimeMillis()
                val source = TemplateSource(parentId = null, name = name, body = body)
                ConfigStore.update(this) { config ->
                    var list = config.conversations
                    snapshots.forEachIndexed { index, snapshot ->
                        if (!checked[index]) return@forEachIndexed
                        val current = list.firstOrNull { it.conversationId == snapshot.conversationId }
                        val next =
                            TemplateOps.fromSource(
                                conversationId = snapshot.conversationId,
                                source = source,
                                autoSync = false,
                                now = now,
                                selectedAt = current?.selectedAt ?: 0L,
                            )
                        list =
                            if (current == null) {
                                list + next
                            } else {
                                list.map { if (it.conversationId == snapshot.conversationId) next else it }
                            }
                    }
                    config.copy(conversations = list)
                }
                Toast.makeText(this, "已克隆到 ${checked.count { it }} 个会话", Toast.LENGTH_SHORT).show()
                render()
            }.setNegativeButton("取消", null)
            .show()
    }

    /**
     * 批量**继承**：把选中的会话挂到某个一级模板下（内置模板也行）。
     *
     * 继承 = 跟随父模板：父模板内容改了可以同步过来；取消勾选则退回「空白（不发）」但保留已写内容。
     */
    private fun pickMembers(
        templateId: String,
        templateName: String,
    ) {
        val config = ConfigStore.load(this)
        val snapshots = config.snapshots.filter { !it.isGroup }
        if (snapshots.isEmpty()) {
            Toast.makeText(this, "暂无会话快照，请先刷新会话", Toast.LENGTH_SHORT).show()
            return
        }
        val source = TemplateSource(templateId, templateName, config.templateBody(templateId))
        val checked = BooleanArray(snapshots.size) { config.conversation(snapshots[it].conversationId)?.inheritFrom == templateId }
        AlertDialog
            .Builder(this)
            .setTitle("选择继承「${templateName.ifBlank { "(未命名)" }}」的会话")
            .setMultiChoiceItems(snapshots.map { it.displayName.ifBlank { "(无名)" } }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }.setPositiveButton("应用") { _, _ ->
                val now = System.currentTimeMillis()
                ConfigStore.update(this) { cfg ->
                    var list = cfg.conversations
                    snapshots.forEachIndexed { index, snapshot ->
                        val current = list.firstOrNull { it.conversationId == snapshot.conversationId }
                        val next: ConversationTemplate? =
                            when {
                                checked[index] && current?.inheritFrom != templateId ->
                                    TemplateOps.fromSource(
                                        conversationId = snapshot.conversationId,
                                        source = source,
                                        autoSync = current?.autoSync ?: false,
                                        now = now,
                                        selectedAt = current?.selectedAt ?: 0L,
                                    )
                                !checked[index] && current?.inheritFrom == templateId ->
                                    TemplateOps.blank(snapshot.conversationId, current.body, now, current.selectedAt)
                                else -> null
                            }
                        if (next != null) {
                            list =
                                if (current == null) {
                                    list + next
                                } else {
                                    list.map { if (it.conversationId == snapshot.conversationId) next else it }
                                }
                        }
                    }
                    cfg.copy(conversations = list)
                }
                Toast.makeText(this, "已更新继承关系", Toast.LENGTH_SHORT).show()
                render()
            }.setNegativeButton("取消", null)
            .show()
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
    private fun exportConfig() {
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

    private fun copyConfig() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("星火燎原 配置", configJson()))
        Toast.makeText(this, "配置 JSON 已复制", Toast.LENGTH_SHORT).show()
    }

    private fun configJson(): String = ConfigCodec.encode(ConfigStore.load(this))

    private fun exportFileName(): String =
        "星火燎原-配置-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()) + ".json"

    /** 读取导入的文件并整份覆盖（先确认，再校验 JSON，最后落盘）。 */
    private fun applyImportedConfig(uri: android.net.Uri) {
        val text =
            runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
                .getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "读不到文件内容", Toast.LENGTH_SHORT).show()
            return
        }
        val root = runCatching { org.json.JSONObject(text) }.getOrNull()
        if (root == null || !looksLikeSparkConfig(root)) {
            Toast.makeText(this, "这不是星火燎原的配置文件", Toast.LENGTH_LONG).show()
            return
        }
        val imported = ConfigCodec.decode(text)
        AlertDialog
            .Builder(this)
            .setTitle("用这个配置覆盖当前配置？")
            .setMessage(
                "将导入：模板 ${imported.groups.size} 个、会话文案 ${imported.conversations.size} 条、" +
                    "宏 ${imported.macros.size} 个、自定义函数 ${imported.functions.size} 个、快照 ${imported.snapshots.size} 条。" +
                    "\n\n当前配置会被整份替换（总开关保持原样：${if (ConfigStore.load(this).enabled) "开" else "关"}）。",
            )
            .setPositiveButton("覆盖导入") { _, _ ->
                val keepEnabled = ConfigStore.load(this).enabled
                ConfigStore.save(this, imported.copy(enabled = keepEnabled))
                Toast.makeText(this, "配置已导入", Toast.LENGTH_SHORT).show()
                render()
            }.setNegativeButton("取消", null)
            .show()
    }

    /** 粗校验：至少带一个本模块的字段，避免把随便一个 json 导进来清空配置。 */
    private fun looksLikeSparkConfig(root: org.json.JSONObject): Boolean =
        listOf("groups", "conversations", "snapshots", "macros", "functions", "sentDays", "enabled")
            .any { root.has(it) }

    /** 复制整份使用文档：直接贴给 AI 就能照着 API 生成脚本。 */
    private fun copyManual() {
        val manual =
            runCatching { assets.open(DocsActivity.MANUAL_ASSET).bufferedReader().use { it.readText() } }
                .getOrElse { return }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("星火燎原 使用文档", manual))
        Toast.makeText(this, "已复制全文，可以贴给 AI 了", Toast.LENGTH_SHORT).show()
    }

    /** 预览一段模板文案（自动带上自定义函数注入）。[sampleIndex] 让每行轮换样例，免得整页都是同一个人。 */
    private fun previewBody(
        config: SparkConfig,
        body: String,
        sampleIndex: Int,
    ): String = preview(config, config.composeProgram(body), sampleIndex)

    /** 预览某个通用模板：函数注入 + 模板 + 挂在它上面的宏。 */
    private fun previewGroup(
        config: SparkConfig,
        group: TemplateGroup,
        sampleIndex: Int,
    ): String =
        preview(config, config.composeProgram(group.body, config.macrosFor(listOf(group.id))), sampleIndex)

    private fun preview(
        config: SparkConfig,
        program: String,
        sampleIndex: Int = 0,
    ): String {
        val sample = SparkPreview.variables(SparkPreview.sampleAt(config, sampleIndex))
        return runCatching { SparkScriptEngine.render(program, sample) }
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
        val body = column().apply { setPadding(0, dp(8), 0, dp(16)) }
        if (functionsPage == PAGE_MACROS) {
            buildMacrosSection(config, body)
        } else {
            buildFunctionsSection(config, body)
        }
        page.addView(scroll().apply { addView(body) })
        return page
    }

    /** 宏列表 + 新增（宏在模板脚本之后执行，可挂多个模板/会话）。 */
    private fun buildMacrosSection(
        config: SparkConfig,
        body: LinearLayout,
    ) {
        if (config.macros.isEmpty()) {
            body.addView(text("还没有宏", 14f, COLOR_DIM))
        } else {
            config.macros.forEach { macro ->
                val targetNames = macroTargetNames(config, macro)
                body.addView(
                    card().apply {
                        addView(text(macro.name.ifBlank { "(未命名宏)" }, 16f, bold = true))
                        addView(
                            text(
                                if (targetNames.isEmpty()) "未挂到任何模板或会话" else targetNames.joinToString("、"),
                                13f,
                                if (targetNames.isEmpty()) COLOR_WARN else COLOR_DIM,
                            ),
                        )
                        addView(text(macro.body.lineSequence().firstOrNull().orEmpty(), 13f, COLOR_DIM))
                    }.asClickable { openMacroEditor(macro.id) },
                )
            }
        }
        body.addView(
            row().apply {
                setPadding(0, dp(12), 0, 0)
                addView(button("＋ 新增宏", primary = true).apply { setOnClickListener { openMacroEditor(null) } })
            },
        )
    }

    /** 自定义函数列表 + 新增（注入成 ctx.functions.<名字>）。 */
    private fun buildFunctionsSection(
        config: SparkConfig,
        body: LinearLayout,
    ) {
        if (config.functions.isEmpty()) {
            body.addView(text("还没有自定义函数", 14f, COLOR_DIM))
        } else {
            config.functions.forEach { fn ->
                body.addView(
                    card().apply {
                        addView(text("ctx.functions.${fn.name}(${fn.paramList.joinToString(", ")})", 15f, COLOR_TEXT, bold = true))
                        if (fn.desc.isNotBlank()) addView(text(fn.desc, 13f, COLOR_DIM))
                        addView(text(fn.body.lineSequence().take(2).joinToString(" ").trim(), 13f, COLOR_DIM))
                    }.asClickable { openFunctionEditor(fn.id) },
                )
            }
        }
        body.addView(
            row().apply {
                setPadding(0, dp(12), 0, 0)
                addView(button("＋ 新增函数", primary = true).apply { setOnClickListener { openFunctionEditor(null) } })
            },
        )
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
                    iconButton(UiKit.Icons.REFRESH, "刷新会话", primary = true) {
                        sendBroadcast(Intent(ConfigContract.ACTION_REFRESH_SNAPSHOT))
                        Toast.makeText(this@SparkSettingsActivity, "已请求刷新，请稍候…", Toast.LENGTH_SHORT).show()
                    },
                )
                addView(iconButton(UiKit.Icons.BOOK, "使用文档") { openDocs() })
                addView(iconButton(UiKit.Icons.COPY, "复制全文") { copyManual() })
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
            menuRow(UiKit.Icons.CODE, "编辑器", if (config.useMonacoEditor) "Monaco（VSCode 同款）" else "原生编辑器") {
                openSubPage(SUB_EDITOR)
            },
        )
        body.addView(
            menuRow(UiKit.Icons.SERVER, "宿主状态", hostSummary(config)) {
                openSubPage(SUB_HOST)
            },
        )
        body.addView(
            menuRow(UiKit.Icons.EXPORT, "配置", "导出 / 导入整份配置（换机、备份用）") {
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

    private companion object {
        val TABS = listOf("续火花", "模板", "宏/函数", "设置")
        val TAB_ICONS =
            listOf(
                UiKit.Icons.FIRE,
                UiKit.Icons.TEMPLATE,
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
                SUB_HOST to "宿主状态",
                SUB_ABOUT to "关于",
            )

        const val TAB_SPARK = 0
        const val TAB_TEMPLATE = 1
        const val TAB_FUNCTIONS = 2
        const val TAB_SETTINGS = 3

        const val PAGE_SELECTED = 0
        const val PAGE_UNSELECTED = 1

        /** 「宏/函数」页内的分页下标。 */
        const val PAGE_MACROS = 0
        const val PAGE_FUNCTIONS = 1

        const val SUB_OVERVIEW = "overview"
        const val SUB_ASSIGN = "assign"
        const val SUB_EDITOR = "editor"
        const val SUB_CONFIG = "config"
        const val SUB_HOST = "host"
        const val SUB_ABOUT = "about"

        /** 手动发送等回执的超时（抖音没运行时不至于一直等）。 */
        const val SEND_TIMEOUT_MS = 12_000L

        /** 配置导出 / 导入的文件选择请求码。 */
        const val REQUEST_EXPORT_CONFIG = 0x61
        const val REQUEST_IMPORT_CONFIG = 0x62

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
        val singles = config.snapshots.filter { !it.isGroup }
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
