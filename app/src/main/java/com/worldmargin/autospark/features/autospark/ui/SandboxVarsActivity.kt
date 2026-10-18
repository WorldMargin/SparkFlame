package com.worldmargin.autospark.features.autospark.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.worldmargin.autospark.features.autospark.model.ConfigCodec
import com.worldmargin.autospark.features.autospark.model.SandboxVar
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_ACCENT
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_BG
import com.worldmargin.autospark.features.autospark.ui.UiKit.COLOR_DIM
import com.worldmargin.autospark.features.autospark.ui.UiKit.card
import com.worldmargin.autospark.features.autospark.ui.UiKit.column
import com.worldmargin.autospark.features.autospark.ui.UiKit.divider
import com.worldmargin.autospark.features.autospark.ui.UiKit.dp
import com.worldmargin.autospark.features.autospark.ui.UiKit.iconButton
import com.worldmargin.autospark.features.autospark.ui.UiKit.padSystemBars
import com.worldmargin.autospark.features.autospark.ui.UiKit.row
import com.worldmargin.autospark.features.autospark.ui.UiKit.scroll
import com.worldmargin.autospark.features.autospark.ui.UiKit.text
import com.worldmargin.autospark.features.autospark.ui.UiKit.titleBar

/**
 * 宏的沙盒变量编辑页：列出 `macro:<宏 id>` 名下的键值，可改、可删、可新增。
 *
 * 值是 **JSON 文本**（`"文本"` / `12` / `true` / `{"a":1}` / `[1,2]`），保存时逐个校验；
 * 单独一页，不跟宏脚本编辑器挤在一起（宏编辑页只留一个入口）。
 */
class SandboxVarsActivity : Activity() {
    private lateinit var listView: LinearLayout
    private val rows = mutableListOf<Pair<EditText, EditText>>()
    private var owner: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        owner = intent.getStringExtra(EXTRA_OWNER).orEmpty()
        val initial = ConfigCodec.decodeSandbox(intent.getStringExtra(EXTRA_VARS))
        if (initial.isEmpty()) rows += newRow("", "") else initial.forEach { rows += newRow(it.key, it.value) }

        listView = column()
        val body =
            scroll().apply {
                addView(
                    column().apply {
                        setPadding(dp(16), dp(8), dp(16), dp(24))
                        addView(listView)
                        addView(
                            text("\uff0b \u6dfb\u52a0\u53d8\u91cf", 15f, COLOR_ACCENT).apply {
                                setPadding(0, dp(12), 0, dp(12))
                                isClickable = true
                                setOnClickListener {
                                    rows += newRow("", "")
                                    render()
                                }
                            },
                        )
                        addView(
                            text("\u503c\u662f JSON\uff1a\"\u6587\u672c\" / 12 / true / {\"a\":1} / [1,2]", 12f, COLOR_DIM),
                        )
                    },
                )
            }

        setContentView(
            column().apply {
                setBackgroundColor(COLOR_BG)
                padSystemBars()
                addView(titleBar("\u6c99\u76d2\u53d8\u91cf", action = saveAction()) { finish() })
                addView(divider())
                addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })
            },
        )
        render()
    }

    private fun newRow(
        key: String,
        value: String,
    ): Pair<EditText, EditText> {
        val keyField =
            EditText(this).apply {
                setText(key)
                hint = "\u540d\u5b57"
                setSingleLine()
                textSize = 14f
            }
        val valueField =
            EditText(this).apply {
                setText(value)
                hint = "JSON \u503c"
                setSingleLine()
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        return keyField to valueField
    }

    private fun render() {
        listView.removeAllViews()
        rows.forEachIndexed { index, pair ->
            val host =
                row().apply {
                    addView(pair.first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.34f))
                    addView(
                        pair.second,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.66f).apply {
                            setPadding(dp(8), 0, dp(8), 0)
                        },
                    )
                    addView(
                        iconButton(UiKit.Icons.TRASH, "", compact = true, hint = "\u5220\u9664") {
                            if (index in rows.indices) {
                                rows.removeAt(index)
                                render()
                            }
                        },
                    )
                }
            listView.addView(
                card().apply {
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(host)
                },
            )
        }
        if (rows.isEmpty()) listView.addView(text("\u8fd8\u6ca1\u6709\u53d8\u91cf", 13f, COLOR_DIM))
    }

    private fun saveAction(): View = iconButton(UiKit.Icons.CHECK, "\u4fdd\u5b58", primary = true, compact = true) { save() }

    private fun save() {
        val result = mutableListOf<SandboxVar>()
        val seen = mutableSetOf<String>()
        for ((keyField, valueField) in rows) {
            val key = keyField.text.toString().trim()
            if (key.isEmpty()) continue
            if (!seen.add(key)) {
                Toast.makeText(this, "\u53d8\u91cf\u540d\u91cd\u590d\uff1a$key", Toast.LENGTH_SHORT).show()
                return
            }
            val raw = valueField.text.toString().trim()
            if (!ConfigCodec.isValidJsonValue(raw)) {
                Toast.makeText(this, "$key \u7684\u503c\u4e0d\u662f\u5408\u6cd5 JSON", Toast.LENGTH_SHORT).show()
                return
            }
            result += SandboxVar(owner, key, raw.ifEmpty { "null" })
        }
        // 直接写回配置：沙盒变量不属于宏脚本那份草稿，不能等宏编辑页的保存
        com.worldmargin.autospark.features.autospark.store.ConfigStore.update(this) { config ->
            config.copy(sandbox = config.sandbox.filterNot { it.owner == owner } + result)
        }
        setResult(RESULT_OK, Intent().putExtra(RESULT_VARS, ConfigCodec.encodeSandbox(result)))
        finish()
    }

    companion object {
        const val EXTRA_OWNER = "owner"
        const val EXTRA_VARS = "vars"
        const val RESULT_VARS = "vars_out"
    }
}
