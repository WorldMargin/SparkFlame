package com.worldmargin.autospark.features.autospark.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import com.worldmargin.autospark.features.autospark.bridge.AutoSparkBridge
import com.worldmargin.autospark.features.autospark.model.BuiltinTemplates
import com.worldmargin.autospark.features.autospark.model.ConversationTemplate
import com.worldmargin.autospark.features.autospark.model.SparkConfig
import com.worldmargin.autospark.features.autospark.model.TemplateOps
import com.worldmargin.autospark.features.autospark.model.TemplateSource
import com.worldmargin.autospark.features.autospark.script.SparkScriptEngine
import com.worldmargin.autospark.features.autospark.store.ConfigStore
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_ACCENT
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_CARD
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_DIM
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_TEXT
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_WARN
import com.worldmargin.autospark.features.autospark.ui.UiKit.actionRow
import com.worldmargin.autospark.features.autospark.ui.UiKit.button
import com.worldmargin.autospark.features.autospark.ui.UiKit.column
import com.worldmargin.autospark.features.autospark.ui.UiKit.divider
import com.worldmargin.autospark.features.autospark.ui.UiKit.dp
import com.worldmargin.autospark.features.autospark.ui.UiKit.enterFromLeft
import com.worldmargin.autospark.features.autospark.ui.UiKit.enterFromRight
import com.worldmargin.autospark.features.autospark.ui.UiKit.icon
import com.worldmargin.autospark.features.autospark.ui.UiKit.iconButton
import com.worldmargin.autospark.features.autospark.ui.UiKit.padSystemBars
import com.worldmargin.autospark.features.autospark.ui.UiKit.rounded
import com.worldmargin.autospark.features.autospark.ui.UiKit.row
import com.worldmargin.autospark.features.autospark.ui.UiKit.scroll
import com.worldmargin.autospark.features.autospark.ui.UiKit.sectionTitle
import com.worldmargin.autospark.features.autospark.ui.UiKit.spacer
import com.worldmargin.autospark.features.autospark.ui.UiKit.text
import com.worldmargin.autospark.features.autospark.ui.UiKit.titleBar

/** 编辑单个会话的文案：继承来源 / 自动同步 / 本地内容 / 重置。 */
class ConversationEditActivity : Activity() {
    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }

    private lateinit var conversationId: String
    private lateinit var headline: String
    private var autoSync = false
    private lateinit var editor: SparkEditor
    private lateinit var info: LinearLayout
    private lateinit var titleHost: LinearLayout
    private lateinit var contentHost: LinearLayout
    private lateinit var editView: View

    /** 正在看「继承来源」二级页面。 */
    private var sourcePage = false

    /** 继承来源页里展开的一级模板 id。 */
    private var sourceExpanded: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: run { finish(); return }

        // 没有模板就当作空白：不自动套默认模板 / 内置文案，写了或选了模板再落盘
        val config = ConfigStore.load(this)
        val template = config.conversation(conversationId)
        val headline = config.snapshots.firstOrNull { it.conversationId == conversationId }?.displayName ?: "会话文案"
        autoSync = template?.autoSync ?: false

        // 编辑区尽量给足高度：顶部只留「继承来源」一行，动作按钮放到底部（也贴近键盘）
        val root = column().apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }
        info = column()
        root.addView(info)
        editor =
            SparkEditors.create(this, config).apply {
                setText(template?.body.orEmpty())
                applyTypes(config)
            }
        root.addView(
            editor.view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(divider())
        root.addView(actionBar())
        editView = root
        titleHost = column()
        contentHost = column()
        setContentView(
            column().apply {
                setBackgroundColor(UiKit.COLOR_BG)
                padSystemBars()
                addView(titleHost)
                addView(divider())
                addView(contentHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })
                addView(divider())
            },
        )
        this.headline = headline
        renderInfo()
        renderBody()
    }

    /** 在「文案编辑」与「继承来源」两个页面之间切换（带转场）。 */
    private fun renderBody() {
        val config = ConfigStore.load(this)
        titleHost.removeAllViews()
        titleHost.addView(
            if (sourcePage) {
                titleBar("继承来源") {
                    sourcePage = false
                    renderBody()
                }
            } else {
                titleBar(headline, action = saveAction(), onBack = { finish() })
            },
        )
        contentHost.removeAllViews()
        val page = if (sourcePage) buildSourcePage(config) else editView
        contentHost.addView(
            page,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
        )
        if (sourcePage) page.enterFromRight() else page.enterFromLeft()
    }

    /**
     * 继承来源页：按分类列出可继承的模板 —— 空白 / 内置模板 / 自定义模板，
     * 每个一级模板都能展开看它的**派生模板**；另有「内容固定」的会话模板可以克隆。
     */
    private fun buildSourcePage(config: SparkConfig): View {
        val body = column().apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }

        body.addView(sourceRow("空白（不发）", "清掉继承关系，保留内容", null))
        body.addView(sectionTitle("内置模板"))
        BuiltinTemplates.ALL.filter { it.body.isNotBlank() }.forEach { builtin ->
            val id = SparkConfig.BUILTIN_PREFIX + builtin.id
            addTemplateSource(body, config, id, builtin.name, builtin.desc)
        }
        body.addView(sectionTitle("自定义模板"))
        if (config.groups.isEmpty()) {
            body.addView(text("还没有自定义模板", 13f, COLOR_DIM))
        } else {
            config.groups.forEach { group ->
                addTemplateSource(body, config, group.id, group.name.ifBlank { "(未命名)" }, "自定义模板")
            }
        }

        val standalone = config.conversations.filter { it.conversationId != conversationId && it.body.isNotBlank() && it.inheritFrom == null }
        if (standalone.isNotEmpty()) {
            body.addView(sectionTitle("内容固定的会话模板（克隆）"))
            standalone.forEach { child ->
                body.addView(
                    sourceRow(
                        "克隆：" + config.conversationLabel(child.conversationId),
                        "复制它的内容，之后不跟随任何模板",
                        TemplateSource(null, "克隆", child.body),
                    ),
                )
            }
        }
        return column().apply { addView(scroll().apply { addView(body) }) }
    }

    /** 一个一级模板行：左侧箭头展开派生模板，点行本身即选中（跟随该模板）。 */
    private fun addTemplateSource(
        parent: LinearLayout,
        config: SparkConfig,
        templateId: String,
        name: String,
        desc: String,
    ) {
        val derived = config.membersOf(templateId)
        val expanded = sourceExpanded == templateId
        val rowView =
            row().apply {
                background = rounded(COLOR_CARD, 10)
                setPadding(dp(10), dp(12), dp(14), dp(12))
                layoutParams =
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(8)
                    }
                addView(
                    icon(if (expanded) UiKit.Icons.CHEVRON_DOWN else UiKit.Icons.CHEVRON_RIGHT, 14f, COLOR_DIM).apply {
                        setPadding(0, 0, dp(10), 0)
                        isClickable = true
                        setOnClickListener {
                            sourceExpanded = if (expanded) null else templateId
                            renderBody()
                        }
                    },
                )
                addView(
                    column().apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        addView(text(name, 15f, COLOR_TEXT, bold = true))
                        addView(text(if (derived.isEmpty()) desc else "$desc　派生 ${derived.size}", 12f, COLOR_DIM))
                    },
                )
                if (expanded && derived.isNotEmpty()) addView(icon(UiKit.Icons.CHECK, 14f, COLOR_ACCENT))
                isClickable = true
                setOnClickListener {
                    applySource(TemplateSource(templateId, name, config.templateBody(templateId)))
                }
            }
        parent.addView(rowView)
        if (expanded) {
            if (derived.isEmpty()) {
                parent.addView(
                    text("　　（还没有会话继承它）", 12f, COLOR_DIM)
                        .apply { setPadding(dp(14), dp(8), 0, 0) },
                )
            }
            derived.forEach { child ->
                val parentName = config.templateName(child.inheritFrom) ?: "内容固定"
                parent.addView(
                    sourceRow(
                        "└ " + config.conversationLabel(child.conversationId),
                        "挂在 $parentName、复制它的内容",
                        TemplateSource(child.inheritFrom, config.conversationLabel(child.conversationId), child.body),
                    ),
                )
            }
        }
    }

    /** 可选来源的一行（[source] 为 null 表示「空白」）。 */
    private fun sourceRow(
        name: String,
        desc: String,
        source: TemplateSource?,
    ): View =
        row().apply {
            background = rounded(COLOR_CARD, 10)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
            addView(
                column().apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(text(name, 15f, COLOR_TEXT, bold = true))
                    if (desc.isNotBlank()) addView(text(desc, 12f, COLOR_DIM))
                },
            )
            addView(icon(UiKit.Icons.CHEVRON_RIGHT, 14f, COLOR_DIM))
            isClickable = true
            setOnClickListener { applySource(source) }
        }

    /** 选中某个来源：跟随它的父模板并复制它的内容；[source] 为 null 则是清掉继承。 */
    private fun applySource(source: TemplateSource?) {
        val now = System.currentTimeMillis()
        ConfigStore.update(this) { cfg ->
            val current = cfg.conversation(conversationId)
            val selectedAt = current?.selectedAt ?: if (conversationId in cfg.selectedIds()) now else 0L
            val next =
                if (source == null) {
                    TemplateOps.blank(conversationId, "", now, selectedAt)
                } else {
                    TemplateOps.fromSource(conversationId, source, autoSync, now, selectedAt)
                }
            if (current == null) {
                cfg.copy(conversations = cfg.conversations + next)
            } else {
                cfg.copy(conversations = cfg.conversations.map { if (it.conversationId == conversationId) next else it })
            }
        }
        editor.setText(ConfigStore.load(this).conversation(conversationId)?.body ?: "")
        sourcePage = false
        renderInfo()
        renderBody()
    }

    private fun renderInfo() {
        val config = ConfigStore.load(this)
        val template = config.conversation(conversationId)
        info.removeAllViews()
        // 顶部压成一行：继承来源 + 自动同步勾选 + 宏数量，把高度留给编辑区
        val ownMacros = config.macrosFor(listOf(conversationId))
        val parentMacros = template?.inheritFrom?.let { config.macrosFor(listOf(it)) }.orEmpty()
        info.addView(
            row().apply {
                addView(
                    text(
                        when {
                            template == null -> "未选模板（不发）"
                            template.inheritFrom == null -> "内容固定（克隆）"
                            config.templateName(template.inheritFrom) != null -> config.templateName(template.inheritFrom)!!
                            // 父模板被清理掉了：内容还在，当成「内容固定」
                            else -> "内容固定（原父模板已不存在）"
                        },
                        14f,
                        if (template == null) COLOR_WARN else COLOR_ACCENT,
                        bold = true,
                    ),
                )
                addView(
                    icon(UiKit.Icons.EDIT, 14f, COLOR_DIM).apply {
                        setPadding(dp(10), 0, 0, 0)
                        isClickable = true
                        setOnClickListener {
                            sourcePage = true
                            renderBody()
                        }
                    },
                )
                if (template?.modified == true) addView(text("　已本地修改", 12f, COLOR_WARN))
                addView(spacer())
                addView(
                    text(
                        "宏 ${ownMacros.size + parentMacros.size}",
                        12f,
                        COLOR_DIM,
                    ),
                )
                addView(
                    CheckBox(this@ConversationEditActivity).apply {
                        text = "自动同步"
                        textSize = 12f
                        isEnabled = template?.inheritFrom != null
                        isChecked = autoSync && template?.inheritFrom != null
                        setOnCheckedChangeListener { _, checked -> autoSync = checked }
                    },
                )
            },
        )
    }

    /** 底部动作条：放在编辑区下面，键盘弹出时也够得着；横向可滚动，按钮再多也不会被挤掉。 */
    private fun actionBar(): View =
        actionRow {
            addView(iconButton(UiKit.Icons.PLAY, "预览", compact = true) { showPreview() })
            addView(iconButton(UiKit.Icons.SEARCH, "补全", compact = true) { editor.showCompletion() })
            addView(iconButton(UiKit.Icons.EDIT, "格式化", compact = true) { editor.format() })
            addView(iconButton(UiKit.Icons.CODE, "", compact = true) { pickMacros() })
            if (ConfigStore.load(this@ConversationEditActivity).conversation(conversationId)?.inheritFrom != null) {
                addView(iconButton(UiKit.Icons.REFRESH, "", compact = true) { reset() })
            }
        }

    /** 标题栏右侧的保存动作。 */
    private fun saveAction(): View = iconButton(UiKit.Icons.CHECK, "保存", primary = true, compact = true) { save() }

    /** 勾选只挂在本会话上的宏。 */
    private fun pickMacros() {
        val label = ConfigStore.load(this).snapshots.firstOrNull { it.conversationId == conversationId }?.displayName ?: "本会话"
        MacroPicker.show(this, conversationId, label) { renderInfo() }
    }

    private fun save() {
        val now = System.currentTimeMillis()
        val body = editor.text
        ConfigStore.update(this) { cfg ->
            val current = cfg.conversation(conversationId)
            if (current == null) {
                // 第一次保存才落盘：默认是空白，没写内容就不建模板
                cfg.copy(
                    conversations =
                        cfg.conversations +
                            TemplateOps.blank(
                                conversationId = conversationId,
                                body = body,
                                now = now,
                                selectedAt = if (conversationId in cfg.selectedIds()) now else 0L,
                            ),
                )
            } else {
                cfg.copy(
                    conversations =
                        cfg.conversations.map {
                            if (it.conversationId == conversationId) {
                                it.copy(body = body, autoSync = autoSync, updatedAt = now)
                            } else {
                                it
                            }
                        },
                )
            }
        }
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        renderInfo()
    }

    private fun reset() {
        ConfigStore.update(this) { cfg ->
            val current = cfg.conversation(conversationId) ?: return@update cfg
            val group = cfg.group(current.inheritFrom) ?: return@update cfg
            val next = TemplateOps.reset(current, group, System.currentTimeMillis())
            cfg.copy(conversations = cfg.conversations.map { if (it.conversationId == conversationId) next else it })
        }
        editor.setText(ConfigStore.load(this).conversation(conversationId)?.body ?: "")
        renderInfo()
        Toast.makeText(this, "已重置为父模板内容", Toast.LENGTH_SHORT).show()
    }

    private fun showPreview() {
        val config = ConfigStore.load(this)
        val snapshot = config.snapshots.firstOrNull { it.conversationId == conversationId }
        // 与实际执行一致：自定义函数注入 → 本会话文案 → 挂在本会话（或它的父模板）上的宏
        val targets = listOf(conversationId) + listOfNotNull(config.conversation(conversationId)?.inheritFrom)
        val program = config.composeProgram(editor.text, config.macrosFor(targets))
        val rendered =
            runCatching {
                SparkScriptEngine.render(program, SparkPreview.variables(snapshot))
            }.getOrElse { "【预览失败】" + (it.message ?: it.toString()) }
        AlertDialog
            .Builder(this)
            .setTitle("预览（${snapshot?.displayName ?: "无缓存，用占位值"}）")
            .setMessage(rendered)
            .setPositiveButton("好", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::editor.isInitialized) editor.release()
    }
}
