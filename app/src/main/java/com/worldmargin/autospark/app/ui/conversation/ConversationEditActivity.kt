package com.worldmargin.autospark.app.ui.conversation

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import com.worldmargin.autospark.host.bridge.AutoSparkBridge
import com.worldmargin.autospark.app.config.BuiltinTemplates
import com.worldmargin.autospark.app.config.ConfigCodec
import com.worldmargin.autospark.app.config.ConversationTemplate
import com.worldmargin.autospark.app.config.SparkConfig
import com.worldmargin.autospark.app.config.TemplateOps
import com.worldmargin.autospark.app.config.TemplateSource
import com.worldmargin.autospark.app.config.TemplateVar
import com.worldmargin.autospark.app.script.SparkScriptEngine
import com.worldmargin.autospark.app.script.PreviewData
import com.worldmargin.autospark.app.script.TemplateVarPrelude
import com.worldmargin.autospark.app.config.ConfigStore
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_ACCENT
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_CARD
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_DIM
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_TEXT
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_WARN
import com.worldmargin.autospark.app.ui.common.UiKit.actionRow
import com.worldmargin.autospark.app.ui.common.UiKit.card
import com.worldmargin.autospark.app.ui.common.UiKit.button
import com.worldmargin.autospark.app.ui.common.UiKit.column
import com.worldmargin.autospark.app.ui.common.UiKit.divider
import com.worldmargin.autospark.app.ui.common.UiKit.dp
import com.worldmargin.autospark.app.ui.common.UiKit.enterFromLeft
import com.worldmargin.autospark.app.ui.common.UiKit.enterFromRight
import com.worldmargin.autospark.app.ui.common.UiKit.icon
import com.worldmargin.autospark.app.ui.common.UiKit.iconButton
import com.worldmargin.autospark.app.ui.common.UiKit.padSystemBars
import com.worldmargin.autospark.app.ui.common.UiKit.pasteButton
import com.worldmargin.autospark.app.ui.common.UiKit.rounded
import com.worldmargin.autospark.app.ui.common.UiKit.row
import com.worldmargin.autospark.app.ui.common.UiKit.scroll
import com.worldmargin.autospark.app.ui.common.UiKit.sectionTitle
import com.worldmargin.autospark.app.ui.common.UiKit.spacer
import com.worldmargin.autospark.app.ui.common.UiKit.text
import com.worldmargin.autospark.app.ui.common.UiKit.titleBar
import com.worldmargin.autospark.app.editor.SparkEditor
import com.worldmargin.autospark.app.editor.SparkEditors
import com.worldmargin.autospark.app.ui.macro.MacroPicker
import com.worldmargin.autospark.app.ui.settings.SparkPreview
import com.worldmargin.autospark.app.ui.variable.VariableListActivity
import com.worldmargin.autospark.app.ui.common.UiKit

/** 编辑单个会话的文案：继承来源 / 自动同步 / 本地内容 / 重置 / **这个会话自己的变量**。 */
class ConversationEditActivity : Activity() {
    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"

        /** 变量页的请求码。 */
        private const val REQUEST_VARIABLES = 0x74
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
                // 继承来的模板变量也声明进类型定义：脚本里 ctx.template. 能补全出变量名
                applyTypes(config, config.templateVarsFor(conversationId))
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
     * 继承来源页：按分类列出可继承的模板 —— 空白模板 / 内置模板 / 自定义模板，
     * 每个一级模板都能展开看它的**派生模板**；另有别的会话模板可以克隆。
     *
     * 「空白模板」和别的模板一样是**跟随**关系，只是父模板内容是空的（自己写）。
     */
    private fun buildSourcePage(config: SparkConfig): View {
        val body = column().apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }

        body.addView(
            card().apply {
                addView(text("空白", 15f, COLOR_TEXT, bold = true))
                addView(
                    sourceRow(
                        BuiltinTemplates.BLANK.name,
                        "不用模板，自己写",
                        TemplateSource(SparkConfig.BUILTIN_BLANK, BuiltinTemplates.BLANK.name, ""),
                    ),
                )
            },
        )
        body.addView(
            card().apply {
                addView(text("内置模板", 15f, COLOR_TEXT, bold = true))
                BuiltinTemplates.ALL.filter { it.body.isNotBlank() }.forEach { builtin ->
                    val id = SparkConfig.BUILTIN_PREFIX + builtin.id
                    addTemplateSource(this, config, id, builtin.name, builtin.desc)
                }
            },
        )
        body.addView(
            card().apply {
                addView(text("自定义模板", 15f, COLOR_TEXT, bold = true))
                if (config.groups.isEmpty()) {
                    addView(text("还没有自定义模板", 13f, COLOR_DIM))
                } else {
                    config.groups.forEach { group ->
                        addTemplateSource(this, config, group.id, group.name.ifBlank { "(未命名)" }, "自定义模板")
                    }
                }
            },
        )

        val standalone = config.conversations.filter { it.conversationId != conversationId && it.body.isNotBlank() && it.inheritFrom == SparkConfig.BUILTIN_BLANK }
        if (standalone.isNotEmpty()) {
            body.addView(sectionTitle("自写的会话模板（克隆）"))
            standalone.forEach { child ->
                body.addView(
                    sourceRow(
                        "克隆：" + config.conversationLabel(child.conversationId),
                        "复制它的内容，之后跟空白模板",
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
                // 行在卡片里面：扁平行，靠内边距分隔
                setPadding(dp(4), dp(12), dp(4), dp(12))
                addView(
                    icon(if (expanded) UiKit.Icons.FOLD else UiKit.Icons.UNFOLD, 14f, COLOR_DIM).apply {
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
                val parentName = config.templateName(child.inheritFrom) ?: "空白模板"
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
            // 行在卡片里面：不再自画卡片底与外边距，只留内边距
            setPadding(dp(4), dp(12), dp(4), dp(12))
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

    /** 选中某个来源：跟随它的父模板并复制它的内容；空白模板不覆盖已写的文案。 */
    private fun applySource(source: TemplateSource?) {
        val now = System.currentTimeMillis()
        ConfigStore.update(this) { cfg ->
            val current = cfg.conversation(conversationId)
            val selectedAt = current?.selectedAt ?: if (conversationId in cfg.selectedIds()) now else 0L
            val next =
                when {
                    source == null ->
                        TemplateOps.blank(
                            conversationId,
                            "",
                            now,
                            selectedAt,
                            current?.variables.orEmpty(),
                        )
                    // 空白模板 = 自己写：保留已有内容，只把「跟随谁」换成空白模板
                    source.parentId == SparkConfig.BUILTIN_BLANK ->
                        TemplateOps.blank(
                            conversationId,
                            current?.body ?: editor.text,
                            now,
                            selectedAt,
                            current?.variables.orEmpty(),
                        )
                    else ->
                        TemplateOps.fromSource(
                            conversationId,
                            source,
                            autoSync,
                            now,
                            selectedAt,
                            current?.variables.orEmpty(),
                        )
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
        val following = template?.inheritFrom
        info.addView(
            row().apply {
                addView(
                    text(
                        when {
                            // 尚未建模板：等同于「不用模板、自己写」
                            template == null -> BuiltinTemplates.BLANK.name
                            following == null -> "空白模板"
                            config.templateName(following) != null -> config.templateName(following)!!
                            // 父模板被清理掉了：内容还在，当成空白模板
                            else -> "空白模板"
                        },
                        14f,
                        COLOR_ACCENT,
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
                val ownVars = template?.variables.orEmpty()
                if (ownVars.isNotEmpty()) addView(text("　变量 ${ownVars.size}", 12f, COLOR_ACCENT))
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
                        // 空白模板没有内容可同步，不给勾
                        isEnabled = following != null && following != SparkConfig.BUILTIN_BLANK
                        isChecked = autoSync && isEnabled
                        setOnCheckedChangeListener { _, checked -> autoSync = checked }
                    },
                )
            },
        )
    }

    /** 底部动作条：放在编辑区下面，键盘弹出时也够得着；横向可滚动，按钮再多也不会被挤掉。 */
    private fun actionBar(): View =
        actionRow {
            addView(iconButton(UiKit.Icons.PREVIEW, "", compact = true, hint = "预览") { showPreview() })
            addView(pasteButton(editor))
            addView(iconButton(UiKit.Icons.WAND, "", compact = true, hint = "格式化") { editor.format() })
            addView(iconButton(UiKit.Icons.PLUG, "", compact = true, hint = "挂载宏") { pickMacros() })
            // 这个会话自己的变量（同名会覆盖父模板的）：变量页
            addView(iconButton(UiKit.Icons.VARIABLE_GROUP, "", compact = true, hint = "本会话的变量") { openVariables() })
            val following = ConfigStore.load(this@ConversationEditActivity).conversation(conversationId)?.inheritFrom
            if (following != null && following != SparkConfig.BUILTIN_BLANK) {
                addView(iconButton(UiKit.Icons.DISCARD, "", compact = true, hint = "重置为父模板内容") { reset() })
            }
        }

    /** 标题栏右侧的保存动作。 */
    private fun saveAction(): View = iconButton(UiKit.Icons.CHECK, "保存", primary = true, compact = true) { save() }

    /** 勾选只挂在本会话上的宏。 */
    private fun pickMacros() {
        val label = ConfigStore.load(this).snapshots.firstOrNull { it.conversationId == conversationId }?.displayName ?: "本会话"
        MacroPicker.show(this, conversationId, label) { renderInfo() }
    }

    /**
     * 打开**本会话自己的**变量页。
     *
     * 父模板的变量一起带过去，但只用于补全、试算和提示；保存时只回传这个会话自己的那些，
     * 父模板的变量一个字都不动。
     */
    private fun openVariables() {
        val config = ConfigStore.load(this)
        val template = config.conversation(conversationId)
        val inherited = config.templateVarsForGroup(template?.inheritFrom.orEmpty())
        val label = config.snapshots.firstOrNull { it.conversationId == conversationId }?.displayName?.takeIf { it.isNotBlank() }
            ?: config.conversationLabel(conversationId)
        runCatching {
            startActivityForResult(
                Intent(this, VariableListActivity::class.java)
                    .putExtra(VariableListActivity.EXTRA_VARS, ConfigCodec.encodeVars(template?.variables.orEmpty()).toString())
                    .putExtra(VariableListActivity.EXTRA_INHERITED, ConfigCodec.encodeVars(inherited).toString())
                    .putExtra(VariableListActivity.EXTRA_PARENT_ID, template?.inheritFrom)
                    .putExtra(VariableListActivity.EXTRA_OWNER, label),
                REQUEST_VARIABLES,
            )
        }.onFailure { Toast.makeText(this, "打不开变量页", Toast.LENGTH_SHORT).show() }
    }

    /** 变量页回来：写进这个会话模板的 `variables`（还没建模板就顺手建一个空白模板）。 */
    @Deprecated("变量页回传")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VARIABLES || resultCode != RESULT_OK || data == null) return
        val raw = data.getStringExtra(VariableListActivity.RESULT_VARS) ?: return
        val vars: List<TemplateVar> =
            runCatching { ConfigCodec.decodeVars(org.json.JSONArray(raw)) }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        ConfigStore.update(this) { cfg ->
            val current = cfg.conversation(conversationId)
            if (current == null) {
                cfg.copy(
                    conversations =
                        cfg.conversations +
                            TemplateOps.blank(
                                conversationId = conversationId,
                                body = editor.text,
                                now = now,
                                selectedAt = if (conversationId in cfg.selectedIds()) now else 0L,
                                variables = vars,
                            ),
                )
            } else {
                cfg.copy(
                    conversations =
                        cfg.conversations.map {
                            if (it.conversationId == conversationId) {
                                it.copy(variables = vars, updatedAt = now)
                            } else {
                                it
                            }
                        },
                )
            }
        }
        renderInfo()
        // 变量改了：脚本编辑器的补全也刷新一下（ctx.template.新变量）
        val latest = ConfigStore.load(this)
        editor.applyTypes(latest, latest.templateVarsFor(conversationId))
        Toast.makeText(this, "已保存 ${vars.size} 个变量", Toast.LENGTH_SHORT).show()
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
        // 变量与真实发送一致：父模板的变量 + 本会话自己的（同名覆盖）
        val vars = config.templateVarsFor(conversationId)
        val rendered =
            runCatching {
                SparkScriptEngine.render(
                        TemplateVarPrelude.of(vars) + program,
                        SparkPreview.variables(snapshot),
                        PreviewData.env(config, conversationId),
                    )
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
