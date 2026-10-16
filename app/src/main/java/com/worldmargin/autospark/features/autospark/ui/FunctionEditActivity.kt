package com.worldmargin.autospark.features.autospark.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.worldmargin.autospark.features.autospark.model.SparkConfig
import com.worldmargin.autospark.features.autospark.model.UserFunction
import com.worldmargin.autospark.features.autospark.store.ConfigStore
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_DIM
import com.worldmargin.autospark.features.autospark.ui.UiKit.actionRow
import com.worldmargin.autospark.features.autospark.ui.UiKit.button
import com.worldmargin.autospark.features.autospark.ui.UiKit.column
import com.worldmargin.autospark.features.autospark.ui.UiKit.divider
import com.worldmargin.autospark.features.autospark.ui.UiKit.dp
import com.worldmargin.autospark.features.autospark.ui.UiKit.iconButton
import com.worldmargin.autospark.features.autospark.ui.UiKit.padSystemBars
import com.worldmargin.autospark.features.autospark.ui.UiKit.text
import com.worldmargin.autospark.features.autospark.ui.UiKit.titleBar
import java.util.UUID

/**
 * 自定义函数编辑（名称 / 形参 / 说明 / 函数体）。
 *
 * 函数体用与模板、宏一致的 Monaco 编辑器（可在设置里切回原生编辑器），
 * 执行前会注入成 `ctx.functions.<名字>` 供脚本调用。
 */
class FunctionEditActivity : Activity() {
    companion object {
        const val EXTRA_FUNCTION_ID = "function_id"
    }

    private var functionId: String? = null
    private var existed = false
    private lateinit var nameField: EditText
    private lateinit var paramsField: EditText
    private lateinit var descField: EditText
    private lateinit var editor: SparkEditor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        functionId = intent.getStringExtra(EXTRA_FUNCTION_ID)
        val config = ConfigStore.load(this)
        val function = config.functions.firstOrNull { it.id == functionId }
        existed = function != null

        val root = column().apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }
        root.addView(text("函数名", 13f, COLOR_DIM))
        nameField =
            EditText(this).apply {
                hint = "例如 daysTo"
                setText(function?.name ?: "")
                setSingleLine()
            }
        root.addView(nameField)
        root.addView(text("形参（逗号分隔，可留空）", 13f, COLOR_DIM))
        paramsField =
            EditText(this).apply {
                hint = "例如 date, pattern"
                setText(function?.params ?: "")
                setSingleLine()
            }
        root.addView(paramsField)
        root.addView(text("说明（可选，会显示在补全提示里）", 13f, COLOR_DIM))
        descField =
            EditText(this).apply {
                hint = "例如 算到某天还有几天"
                setText(function?.desc ?: "")
                setSingleLine()
            }
        root.addView(descField)
        root.addView(text("函数体（要返回值就写 return）", 13f, COLOR_DIM))
        editor =
            SparkEditors
                .create(this, config)
                .apply {
                    setText(function?.body ?: "")
                    applyTypes(config)
                }
        root.addView(
            editor.view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(divider())
        root.addView(
            actionRow {
                addView(iconButton(UiKit.Icons.SEARCH, "补全", compact = true) { editor.showCompletion() })
                addView(iconButton(UiKit.Icons.EDIT, "格式化", compact = true) { editor.format() })
                if (existed) {
                    addView(iconButton(UiKit.Icons.TRASH, "删除", compact = true) { confirmDelete() })
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
        val name = nameField.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "函数名不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (!name.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) {
            Toast.makeText(this, "函数名只能是字母/数字/下划线，且不能以数字开头", Toast.LENGTH_LONG).show()
            return
        }
        val now = System.currentTimeMillis()
        val body = editor.text
        ConfigStore.update(this) { config ->
            val next =
                UserFunction(
                    id = functionId ?: UUID.randomUUID().toString(),
                    name = name,
                    params = paramsField.text.toString().trim(),
                    body = body,
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
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
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
