package com.worldmargin.autospark.features.autospark.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.worldmargin.autospark.features.autospark.model.SparkConfig
import com.worldmargin.autospark.features.autospark.model.UserFunction
import com.worldmargin.autospark.features.autospark.script.SparkFunctionSource
import com.worldmargin.autospark.features.autospark.store.ConfigStore
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_DIM
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_WARN
import com.worldmargin.autospark.features.autospark.ui.UiKit.actionRow
import com.worldmargin.autospark.features.autospark.ui.UiKit.column
import com.worldmargin.autospark.features.autospark.ui.UiKit.divider
import com.worldmargin.autospark.features.autospark.ui.UiKit.dp
import com.worldmargin.autospark.features.autospark.ui.UiKit.iconButton
import com.worldmargin.autospark.features.autospark.ui.UiKit.padSystemBars
import com.worldmargin.autospark.features.autospark.ui.UiKit.pasteButton
import com.worldmargin.autospark.features.autospark.ui.UiKit.text
import com.worldmargin.autospark.features.autospark.ui.UiKit.titleBar
import java.util.UUID

/**
 * 自定义函数编辑。
 *
 * 直接写**完整函数声明**（不再只写函数内部）：
 *
 * ```js
 * function daysTo(date) {
 *   return Math.ceil((new Date(date) - new Date()) / 86400000);
 * }
 * ```
 *
 * 保存时由 [SparkFunctionSource] 解析出函数名与形参，挂到 `ctx.functions.<名字>` 供脚本调用；
 * **只允许一个函数** —— 声明之后再写别的内容会被拒绝。
 * 早期版本只存了「函数内部」，打开时会自动补成完整声明，可以继续编辑。
 */
class FunctionEditActivity : Activity() {
    companion object {
        const val EXTRA_FUNCTION_ID = "function_id"

        /** 新建函数时的起步模板，让格式一眼就懂。 */
        const val DEFAULT_TEMPLATE = "function myFunction(value) {\n  return value;\n}\n"
    }

    private var functionId: String? = null
    private var existed = false
    private lateinit var descField: EditText
    private lateinit var editor: SparkEditor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        functionId = intent.getStringExtra(EXTRA_FUNCTION_ID)
        val config = ConfigStore.load(this)
        val function = config.functions.firstOrNull { it.id == functionId }
        existed = function != null

        val root = column().apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }
        root.addView(text("说明（可选，会显示在补全提示里）", 13f, COLOR_DIM))
        descField =
            EditText(this).apply {
                hint = "例如 算到某天还有几天"
                setText(function?.desc ?: "")
                setSingleLine()
            }
        root.addView(descField)
        root.addView(text("函数（直接写完整声明，只能有一个函数）", 13f, COLOR_DIM))
        root.addView(
            text(
                "function 名字(形参) { … }　—— 保存后脚本里用 ctx.functions.名字(...) 调用",
                12f,
                COLOR_DIM,
            ),
        )
        editor =
            SparkEditors
                .create(this, config)
                .apply {
                    setText(function?.declaration() ?: DEFAULT_TEMPLATE)
                    applyTypes(config)
                }
        root.addView(
            editor.view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(divider())
        root.addView(
            actionRow {
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
                addView(
                    titleBar(
                        if (existed) "编辑函数" else "新建函数",
                        action = saveAction(),
                    ) { finish() },
                )
                addView(divider())
                addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })
            },
        )
    }

    /** 标题栏右侧的保存动作。 */
    private fun saveAction(): View = iconButton(UiKit.Icons.CHECK, "保存", primary = true, compact = true) { save() }

    private fun save() {
        val result = SparkFunctionSource.parse(editor.text)
        val parsed = result.parsed
        if (parsed == null) {
            AlertDialog
                .Builder(this)
                .setTitle("函数写得不对")
                .setMessage((result.error ?: "解析失败") + "\n\n要写成：\nfunction 名字(形参) {\n  …\n}\n\n而且只能有一个函数。")
                .setPositiveButton("继续改", null)
                .show()
            return
        }
        val now = System.currentTimeMillis()
        ConfigStore.update(this) { config ->
            val next =
                UserFunction(
                    id = functionId ?: UUID.randomUUID().toString(),
                    name = parsed.name,
                    params = parsed.params,
                    body = parsed.source,
                    desc = descField.text.toString().trim(),
                    updatedAt = now,
                )
            config.copy(
                functions =
                    if (existed) {
                        config.functions.map { if (it.id == functionId) next else it }
                    } else {
                        functionId = next.id
                        existed = true
                        config.functions + next
                    },
            )
        }
        Toast.makeText(this, "已保存 ctx.functions.${parsed.name}", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        val id = functionId ?: return
        AlertDialog
            .Builder(this)
            .setTitle("删除这个函数？")
            .setMessage("删掉后脚本里的 ctx.functions.<名字> 就没有了；用了它的模板执行时会报错。")
            .setPositiveButton("删除") { _, _ ->
                ConfigStore.update(this) { config -> config.copy(functions = config.functions.filterNot { it.id == id }) }
                finish()
            }.setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::editor.isInitialized) editor.release()
    }
}
