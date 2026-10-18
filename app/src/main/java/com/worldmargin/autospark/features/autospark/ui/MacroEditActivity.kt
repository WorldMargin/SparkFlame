package com.worldmargin.autospark.features.autospark.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.worldmargin.autospark.features.autospark.model.MacroScript
import com.worldmargin.autospark.features.autospark.store.ConfigStore
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_DIM
import com.worldmargin.autospark.features.autospark.ui.UiKit.actionRow
import com.worldmargin.autospark.features.autospark.ui.UiKit.button
import com.worldmargin.autospark.features.autospark.ui.UiKit.column
import com.worldmargin.autospark.features.autospark.ui.UiKit.divider
import com.worldmargin.autospark.features.autospark.ui.UiKit.dp
import com.worldmargin.autospark.features.autospark.ui.UiKit.iconButton
import com.worldmargin.autospark.features.autospark.ui.UiKit.padSystemBars
import com.worldmargin.autospark.features.autospark.ui.UiKit.pasteButton
import com.worldmargin.autospark.features.autospark.ui.UiKit.row
import com.worldmargin.autospark.features.autospark.ui.UiKit.text
import com.worldmargin.autospark.features.autospark.ui.UiKit.titleBar
import java.util.UUID

/**
 * 宏编辑：名称 / 生效目标（通用模板或具体会话，可多选）/ 脚本内容。
 *
 * 宏在模板脚本**之后**执行，所以能改写 `$spark_result$`；一个宏可以挂到多个模板上，
 * 一个模板也可以挂多个宏。
 */
class MacroEditActivity : Activity() {
    companion object {
        private const val REQUEST_SANDBOX = 0x93

        const val EXTRA_MACRO_ID = "macro_id"
    }

    private var macroId: String? = null
    private var existed = false
    private lateinit var nameField: EditText
    private lateinit var editor: SparkEditor
    private lateinit var targetsView: LinearLayout

    /** 当前选中的生效目标（模板 id 或会话 id）。 */
    private var targets: Set<String> = emptySet()

    private lateinit var sandboxEntry: android.widget.TextView

    /** 这个宏自己的沙盒变量（保存时写回配置）。 */
    private var sandbox: List<com.worldmargin.autospark.features.autospark.model.SandboxVar> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        macroId = intent.getStringExtra(EXTRA_MACRO_ID)
        val config = ConfigStore.load(this)
        val macro = config.macros.firstOrNull { it.id == macroId }
        existed = macro != null
        targets = macro?.appliesTo ?: emptySet()
        sandbox =
            macroId?.let { id ->
                config.sandbox.filter { it.owner == "macro:" + id }
            }.orEmpty()

        val root = column().apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }

        root.addView(text("宏名称", 13f, COLOR_DIM))
        nameField =
            EditText(this).apply {
                setText(macro?.name ?: "")
                hint = "例如：元旦倒计时"
                setSingleLine()
            }
        root.addView(nameField)

        root.addView(text("生效目标（可多选：模板或具体会话）", 13f, COLOR_DIM))
        targetsView = column()
        root.addView(targetsView)

        sandboxEntry =
            text(sandboxLabel(), 13f, COLOR_DIM).apply {
                setPadding(0, dp(12), 0, dp(4))
                isClickable = true
                setOnClickListener { openSandbox() }
            }
        root.addView(sandboxEntry)

        root.addView(text("宏脚本（在模板脚本之后执行，可改写 " + "\$spark_result\$" + "）", 13f, COLOR_DIM))
        editor =
            SparkEditors.create(this, config).apply {
                setText(macro?.body ?: "")
                applyTypes(config)
            }
        root.addView(
            editor.view,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        root.addView(
            actionRow {
                addView(iconButton(UiKit.Icons.TARGET, "", compact = true, hint = "选目标") { pickTargets() })

                addView(pasteButton(editor))
                addView(iconButton(UiKit.Icons.LIGHTBULB, "", compact = true, hint = "补全") { editor.showCompletion() })
                addView(iconButton(UiKit.Icons.WAND, "", compact = true, hint = "格式化") { editor.format() })
                if (existed) {
                    addView(iconButton(UiKit.Icons.TRASH, "", compact = true, hint = "删除") { confirmDelete() })
                }
            },
        )

        setContentView(
            column().apply {
                setBackgroundColor(UiKit.COLOR_BG)
                padSystemBars()
                addView(titleBar(if (existed) "编辑宏" else "新建宏", action = saveAction()) { finish() })
                addView(divider())
                addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })
            },
        )
        renderTargets()
    }

    private fun renderTargets() {
        val config = ConfigStore.load(this)
        targetsView.removeAllViews()
        val names =
            targets.map { target ->
                config.group(target)?.name?.ifBlank { "(未命名模板)" }
                    ?: config.snapshots.firstOrNull { it.conversationId == target }?.displayName
                    ?: "(已删除)"
            }
        targetsView.addView(
            text(
                if (names.isEmpty()) "未选择（这个宏不会执行）" else names.joinToString("、"),
                14f,
                if (names.isEmpty()) UiKit.COLOR_WARN else UiKit.COLOR_TEXT,
            ),
        )
    }

    /** 多选：通用模板 + 单个会话，两段列在一个对话框里。 */
    private fun pickTargets() {
        val config = ConfigStore.load(this)
        val groups = config.groups
        val snapshots = config.snapshots.filter { !it.isGroup }
        val labels =
            groups.map { "模板：" + it.name.ifBlank { "(未命名)" } } +
                snapshots.map { "会话：" + it.displayName.ifBlank { "(无名)" } }
        if (labels.isEmpty()) {
            Toast.makeText(this, "还没有模板或会话，先去模板页/刷新会话", Toast.LENGTH_SHORT).show()
            return
        }
        val ids = groups.map { it.id } + snapshots.map { it.conversationId }
        val checked = BooleanArray(ids.size) { ids[it] in targets }
        AlertDialog
            .Builder(this)
            .setTitle("宏挂到哪些模板 / 会话")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }.setPositiveButton("应用") { _, _ ->
                targets = ids.filterIndexed { index, _ -> checked[index] }.toSet()
                renderTargets()
            }.setNegativeButton("取消", null)
            .show()
    }

    /** 标题栏右侧的保存动作。 */
    private fun saveAction(): View = iconButton(UiKit.Icons.CHECK, "保存", primary = true, compact = true) { save() }

    private fun save() {
        val name = nameField.text.toString().trim()
        val body = editor.text
        val now = System.currentTimeMillis()
        ConfigStore.update(this) { config ->
            val next =
                MacroScript(
                    id = macroId ?: UUID.randomUUID().toString(),
                    name = name.ifBlank { "(未命名宏)" },
                    body = body,
                    appliesTo = targets,
                    updatedAt = now,
                )
            config.copy(
                macros =
                    if (existed) {
                        config.macros.map { if (it.id == macroId) next else it }
                    } else {
                        macroId = next.id
                        existed = true
                        config.macros + next
                    },
            )
        }
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun reloadSandbox() {
        sandbox =
            macroId?.let { id -> com.worldmargin.autospark.features.autospark.store.ConfigStore.load(this).sandbox.filter { it.owner == "macro:" + id } }
                .orEmpty()
    }

    private fun sandboxLabel(): String = "沙盒变量（仅这个宏可读写）· " + sandbox.size + " 个"

    /** 打开沙盒变量页（独立一页，和编辑器分开）。 */
    private fun openSandbox() {
        val id = macroId ?: UUID.randomUUID().toString().also { macroId = it }
        startActivityForResult(
            android.content.Intent(this, SandboxVarsActivity::class.java)
                .putExtra(SandboxVarsActivity.EXTRA_OWNER, "macro:" + id)
                .putExtra(SandboxVarsActivity.EXTRA_VARS, com.worldmargin.autospark.features.autospark.model.ConfigCodec.encodeSandbox(sandbox)),
            REQUEST_SANDBOX,
        )
    }

    @Deprecated("Android 里仍可用 startActivityForResult")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SANDBOX && resultCode == RESULT_OK) {
            reloadSandbox()
            sandboxEntry.text = sandboxLabel()
        }
    }

    private fun confirmDelete() {
        val id = macroId ?: return
        AlertDialog
            .Builder(this)
            .setTitle("删除这个宏？")
            .setMessage("宏是独立的临时脚本，删掉不影响任何模板内容。")
            .setPositiveButton("删除") { _, _ ->
                ConfigStore.update(this) { config ->
                    config.copy(
                        macros = config.macros.filterNot { it.id == id },
                        // 沙盒归属就是宏自己：宏没了它的键也没人读得到了
                        sandbox = config.sandbox.filterNot { it.owner == "macro:" + id },
                    )
                }
                finish()
            }.setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::editor.isInitialized) editor.release()
    }
}
