package com.worldmargin.autospark.app.ui.variable

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.worldmargin.autospark.app.config.ConfigCodec
import com.worldmargin.autospark.app.config.SparkConfig
import com.worldmargin.autospark.app.config.TemplateVar
import com.worldmargin.autospark.app.script.SparkScriptEngine
import com.worldmargin.autospark.app.script.PreviewData
import com.worldmargin.autospark.app.script.TemplateVarPrelude
import com.worldmargin.autospark.app.config.ConfigStore
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_ACCENT
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_BG
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_CARD
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_DIM
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_TEXT
import com.worldmargin.autospark.app.ui.common.UiKit.animateFold
import com.worldmargin.autospark.app.ui.common.UiKit.bottomBar
import com.worldmargin.autospark.app.ui.common.UiKit.card
import com.worldmargin.autospark.app.ui.common.UiKit.column
import com.worldmargin.autospark.app.ui.common.UiKit.divider
import com.worldmargin.autospark.app.ui.common.UiKit.dp
import com.worldmargin.autospark.app.ui.common.UiKit.icon
import com.worldmargin.autospark.app.ui.common.UiKit.iconButton
import com.worldmargin.autospark.app.ui.common.UiKit.padSystemBars
import com.worldmargin.autospark.app.ui.common.UiKit.rounded
import com.worldmargin.autospark.app.ui.common.UiKit.row
import com.worldmargin.autospark.app.ui.common.UiKit.scroll
import com.worldmargin.autospark.app.ui.common.UiKit.spacer
import com.worldmargin.autospark.app.ui.common.UiKit.text
import com.worldmargin.autospark.app.ui.common.UiKit.titleBar
import org.json.JSONArray
import org.json.JSONObject
import com.worldmargin.autospark.app.editor.SparkEditor
import com.worldmargin.autospark.app.editor.SparkEditors
import com.worldmargin.autospark.app.ui.settings.SparkPreview
import com.worldmargin.autospark.app.ui.common.UiKit

/**
 * 变量页：**一行一个变量**，名字在左、两三行高的小 Monaco 在右（写不下会自己长高）。
 *
 * 两张可折叠卡片：
 * - **继承**：父模板定义的变量。名字不能改、也不能删，但**表达式可以改** ——
 *   改了就算这一层的覆盖（保存在自己的 `variables` 里，父模板不动）；旁边出现「还原」回到父模板的值
 * - **自定义**：这一层自己的新增变量，可增删改；同名时覆盖继承来的那些
 *
 * 保存时只回传**本层自己**的变量（改过的继承项 + 自定义项），父模板一个字都不动。
 */
class VariableListActivity : Activity() {
    companion object {
        /** 进来时的变量列表（JSON 数组，见 [ConfigCodec.encodeVars]）。 */
        const val EXTRA_VARS = "vars_json"

        /** 从父模板**继承来的**变量（JSON 数组，可选）：用来铺「继承」卡片、做补全与试算。 */
        const val EXTRA_INHERITED = "vars_inherited_json"

        /** 父模板 id：没传 [EXTRA_INHERITED]（或传了空列表）时，页面自己按它去配置里取父模板变量。 */
        const val EXTRA_PARENT_ID = "vars_parent_id"

        /** 这页变量属于谁（会话名 / 模板名），只有标题栏显示用。 */
        const val EXTRA_OWNER = "vars_owner"

        /** 回传的变量列表（只含本层自己那一份，JSON 数组）。 */
        const val RESULT_VARS = "vars_result_json"

        /** 小编辑器的高度：至少 3 行，写不下就往上长（最多 8 行，再长就在编辑器里滚动）。 */
        private const val MIN_LINES = 3
        private const val MAX_LINES = 8
        private const val LINE_DP = 20
        private const val PAD_DP = 10
    }

    /** 自定义行：名字输入框 + 小编辑器 + 删除。 */
    private class CustomRow(
        val name: EditText,
        val editor: SparkEditor,
        val view: LinearLayout,
    )

    /** 继承行：名字固定（只读）+ 小编辑器 + 「还原」。 */
    private class InheritedRow(
        val name: String,
        val parentExpr: String,
        val editor: SparkEditor,
        val reset: View,
        val view: LinearLayout,
    )

    /** 折叠卡片的三个部件：整张卡、内容区、标题右边的数量文字。 */
    private class FoldCard(
        val card: LinearLayout,
        val body: LinearLayout,
        val count: TextView,
    )

    private lateinit var config: SparkConfig
    private lateinit var scroller: ScrollView
    private lateinit var customHost: LinearLayout
    private lateinit var customCount: TextView
    private val customRows = mutableListOf<CustomRow>()
    private val inheritedRows = mutableListOf<InheritedRow>()

    /** 父模板继承来的变量定义（只读的来源）。 */
    private var inherited: List<TemplateVar> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = ConfigStore.load(this)
        inherited = resolveInherited()
        val own = parseVars(intent.getStringExtra(EXTRA_VARS))
        val overrides = own.associateBy { it.name }
        val customInitial = own.filter { item -> inherited.none { it.name == item.name } }
        val owner = intent.getStringExtra(EXTRA_OWNER).orEmpty().trim()

        val content = column().apply { setPadding(dp(12), dp(4), dp(12), dp(12)) }
        scroller = scroll().apply { addView(content) }
        if (inherited.isNotEmpty()) content.addView(inheritedCard(overrides).card)
        val custom = customCard()
        customHost = custom.body
        customCount = custom.count
        content.addView(custom.card)

        customInitial.forEach { addCustomRow(it.name, it.expr) }
        refreshCustomCount()

        setContentView(
            column().apply {
                setBackgroundColor(COLOR_BG)
                padSystemBars()
                addView(
                    titleBar(
                        if (owner.isEmpty()) "变量" else "$owner 的变量",
                        action = saveAction(),
                    ) { finish() },
                )
                addView(divider())
                addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })
                addView(bottomBar { addView(iconButton(UiKit.Icons.ADD, "添加变量", primary = true, compact = true) { appendCustomRow() }) })
            },
        )
    }

    /** 父模板的变量：优先用调用方传的，传空就按父模板 id 自己去取，都没有就当没有继承。 */
    private fun resolveInherited(): List<TemplateVar> {
        val passed = parseVars(intent.getStringExtra(EXTRA_INHERITED))
        if (passed.isNotEmpty()) return passed
        val parentId = intent.getStringExtra(EXTRA_PARENT_ID).orEmpty()
        if (parentId.isBlank()) return emptyList()
        return config.templateVarsForGroup(parentId)
    }

    /** 可折叠的卡片：标题（名字 + 数量 + 箭头）+ 内容区，没有说明文字。 */
    private fun foldCard(
        title: String,
        count: Int,
        startOpen: Boolean,
        build: (LinearLayout) -> Unit,
    ): FoldCard {
        val body = column().apply { visibility = if (startOpen) View.VISIBLE else View.GONE }
        val chevron = icon(if (startOpen) UiKit.Icons.FOLD else UiKit.Icons.UNFOLD, 14f, COLOR_DIM)
        val countView = text("　$count 个", 13f, COLOR_DIM)
        val card =
            card().apply {
                addView(
                    row().apply {
                        setPadding(0, dp(2), 0, dp(4))
                        isClickable = true
                        setOnClickListener {
                            val expand = body.visibility != View.VISIBLE
                            animateFold(body, expand, chevron)
                        }
                        addView(text(title, 15f, COLOR_TEXT, bold = true))
                        addView(countView)
                        addView(spacer())
                        addView(chevron)
                    },
                )
                addView(body)
            }
        build(body)
        return FoldCard(card, body, countView)
    }

    /** 继承卡片：父模板的变量，表达式可改（改成覆盖），不能删。 */
    private fun inheritedCard(overrides: Map<String, TemplateVar>): FoldCard =
        foldCard("继承", inherited.size, startOpen = true) { body ->
            inherited.forEachIndexed { index, parentVar ->
                if (index > 0) body.addView(divider())
                body.addView(inheritedRow(parentVar, overrides[parentVar.name]))
            }
        }

    /** 一行继承变量：名字（只读）+ 可编辑表达式 + 还原。 */
    private fun inheritedRow(
        parentVar: TemplateVar,
        seed: TemplateVar?,
    ): View {
        val expr = seed?.expr?.takeIf { it.isNotBlank() } ?: parentVar.expr
        val editor =
            SparkEditors.create(this, config).apply {
                setText(expr)
                setCompact(true)
                applyTypes(config, effectiveVars())
            }
        val editorView = editor.view.apply { layoutParams = LinearLayout.LayoutParams(0, heightFor(expr), 0.66f) }
        val reset =
            icon(UiKit.Icons.DISCARD, 14f, COLOR_DIM).apply {
                contentDescription = "还原成父模板的值"
                setPadding(dp(6), dp(6), dp(4), dp(6))
                visibility = if (seed != null) View.VISIBLE else View.INVISIBLE
                isClickable = true
                setOnClickListener {
                    editor.setText(parentVar.expr)
                    visibility = View.INVISIBLE
                }
                setOnLongClickListener {
                    Toast.makeText(this@VariableListActivity, "还原成父模板的值", Toast.LENGTH_SHORT).show()
                    true
                }
            }
        val rowView =
            row().apply {
                gravity = Gravity.TOP
                setPadding(dp(2), dp(8), dp(2), dp(8))
                addView(
                    text(parentVar.name, 14f, COLOR_TEXT, bold = true).apply {
                        gravity = Gravity.TOP
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                        maxLines = 2
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.34f),
                )
                addView(editorView)
                addView(reset)
            }
        val row = InheritedRow(parentVar.name, parentVar.expr, editor, reset, rowView)
        inheritedRows += row
        editor.observeTextChange { value ->
            resize(editorView, value)
            reset.visibility = if (isOverridden(row)) View.VISIBLE else View.INVISIBLE
        }
        return rowView
    }

    /** 自定义卡片：本层新增的变量。 */
    private fun customCard(): FoldCard =
        foldCard("自定义", 0, startOpen = true) { }

    /** 一行自定义变量：`[名字] [小编辑器] [删除]`，编辑器高度按内容行数算。 */
    private fun addCustomRow(
        name: String,
        expr: String,
    ) {
        val nameField =
            EditText(this).apply {
                setText(name)
                hint = "名字"
                textSize = 14f
                setSingleLine()
                gravity = Gravity.TOP
                setHintTextColor(COLOR_DIM)
                background = rounded(COLOR_CARD, 8)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
        val editor =
            SparkEditors.create(this, config).apply {
                setText(expr)
                setCompact(true)
                applyTypes(config, effectiveVars())
                observeTextChange { value -> resize(view, value) }
            }
        val editorView = editor.view.apply { layoutParams = LinearLayout.LayoutParams(0, heightFor(expr), 0.66f) }
        val rowView =
            row().apply {
                gravity = Gravity.TOP
                setPadding(dp(2), dp(8), dp(2), dp(8))
                addView(
                    nameField,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.34f),
                )
                addView(editorView)
            }
        val entry = CustomRow(nameField, editor, rowView)
        rowView.addView(
            icon(UiKit.Icons.TRASH, 14f, COLOR_DIM).apply {
                contentDescription = "删除这个变量"
                setPadding(dp(6), dp(6), dp(4), dp(6))
                isClickable = true
                setOnClickListener {
                    customRows.remove(entry)
                    customHost.removeView(rowView)
                    refreshCustomCount()
                }
                setOnLongClickListener {
                    Toast.makeText(this@VariableListActivity, "删除这个变量", Toast.LENGTH_SHORT).show()
                    true
                }
            },
        )
        customRows += entry
        customHost.addView(rowView)
    }

    /** 新加一行并滚到底、把焦点给名字输入框（键盘由系统弹）。 */
    private fun appendCustomRow() {
        addCustomRow("", "")
        refreshCustomCount()
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
        customRows.lastOrNull()?.name?.let { field ->
            field.requestFocus()
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** 自定义卡片的数量跟着行数走。 */
    private fun refreshCustomCount() {
        if (::customCount.isInitialized) customCount.text = "　${customRows.size} 个"
    }

    /** 编辑器高度（dp）：行数夹在 3–8 行之间。 */
    private fun heightFor(text: String): Int {
        val lines = text.lines().size.coerceIn(MIN_LINES, MAX_LINES)
        return dp(lines * LINE_DP + PAD_DP)
    }

    private fun resize(
        view: View,
        text: String,
    ) {
        val params = view.layoutParams as? LinearLayout.LayoutParams ?: return
        val height = heightFor(text)
        if (params.height != height) {
            params.height = height
            view.layoutParams = params
        }
    }

    /** 这一行的表达式是否改过（空 = 没填，也算回落到父模板）。 */
    private fun isOverridden(row: InheritedRow): Boolean {
        val expr = row.editor.text.trim()
        return expr.isNotEmpty() && expr != row.parentExpr.trim()
    }

    /** 继承行里改过的那些（要存成自己的变量）。 */
    private fun inheritedOverrides(): List<TemplateVar> =
        inheritedRows.filter { isOverridden(it) }.map { TemplateVar(it.name, it.editor.text.trim()) }

    /** 自定义行（空名字忽略）。 */
    private fun customVars(): List<TemplateVar> =
        customRows.mapNotNull { row ->
            val name = row.name.text.toString().trim()
            if (name.isEmpty()) null else TemplateVar(name, row.editor.text.trim())
        }

    /** 这一层自己的变量（覆盖项 + 自定义），同名后者覆盖前者。 */
    private fun ownVars(): List<TemplateVar> {
        val merged = LinkedHashMap<String, TemplateVar>()
        inheritedOverrides().forEach { merged[it.name] = it }
        customVars().forEach { merged[it.name] = it }
        return merged.values.toList()
    }

    /**
     * 实际生效的变量 = 父模板的 + 这一层自己的。
     *
     * 与 `SparkConfig.mergeVars` 同一套语义（同名覆盖、表达式为空视为没填），
     * 所以试算结果就是发送时的结果。
     */
    private fun effectiveVars(): List<TemplateVar> = SparkConfig.mergeVars(inherited, ownVars())

    private fun saveAction(): View =
        row().apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            addView(iconButton(UiKit.Icons.PREVIEW, "试算", compact = true) { trial() })
            addView(iconButton(UiKit.Icons.CHECK, "保存", primary = true, compact = true) { save() })
        }

    /**
     * 试算：按会话快照把生效的整套变量依次算一遍，列出「名字 = 值」。
     *
     * 只读不写、不发消息；算不出来显示 `null`（= 发送时的行为），语法错则整页报错。
     */
    private fun trial() {
        val vars = effectiveVars()
        if (vars.isEmpty()) {
            Toast.makeText(this, "还没有变量", Toast.LENGTH_SHORT).show()
            return
        }
        val sample = SparkPreview.sample(config)
        val pushes =
            buildString {
                vars.forEach { variable ->
                    append(SparkScriptEngine.RESULT_VAR)
                    append(".push(")
                    append(JSONObject.quote(variable.name + " = "))
                    append(" + String(ctx.template.vars[")
                    append(JSONObject.quote(variable.name))
                    append("]));\n")
                }
            }
        val outcome =
            runCatching {
                SparkScriptEngine.render(
                    TemplateVarPrelude.of(vars) + pushes,
                    SparkPreview.variables(sample),
                    PreviewData.env(config, sample?.conversationId),
                )
            }
        AlertDialog
            .Builder(this)
            .setTitle("试算（样例：${sample?.displayName ?: "无缓存，用占位值"}）")
            .setMessage(outcome.getOrElse { "跑不通：\n" + (it.message ?: it.toString()) })
            .setPositiveButton("好", null)
            .show()
    }

    private fun save() {
        val merged = LinkedHashMap<String, TemplateVar>()
        ownVars().forEach { merged[it.name] = it }
        val unnamed = customRows.count { it.name.text.toString().isBlank() }
        setResult(RESULT_OK, Intent().putExtra(RESULT_VARS, ConfigCodec.encodeVars(merged.values.toList()).toString()))
        if (unnamed > 0) Toast.makeText(this, "$unnamed 行没写名字已忽略", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** 进来时的变量列表解析（坏 JSON 当作没有）。 */
    private fun parseVars(raw: String?): List<TemplateVar> =
        runCatching { ConfigCodec.decodeVars(raw?.let { JSONArray(it) }) }.getOrDefault(emptyList())

    override fun onDestroy() {
        super.onDestroy()
        customRows.forEach { runCatching { it.editor.release() } }
        inheritedRows.forEach { runCatching { it.editor.release() } }
        customRows.clear()
        inheritedRows.clear()
    }
}
