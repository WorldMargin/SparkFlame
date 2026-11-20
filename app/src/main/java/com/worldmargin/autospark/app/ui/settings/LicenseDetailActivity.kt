// SPDX-License-Identifier: SFAL-1.0
// Copyright (c) 2026 WorldMargin. See LICENSE.SPARKFLAME.md and NOTICE.

package com.worldmargin.autospark.app.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.Toast
import com.worldmargin.autospark.app.ui.common.Markdown
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_BG
import com.worldmargin.autospark.app.ui.common.UiKit.Icons.COPY
import com.worldmargin.autospark.app.ui.common.UiKit.column
import com.worldmargin.autospark.app.ui.common.UiKit.divider
import com.worldmargin.autospark.app.ui.common.UiKit.dp
import com.worldmargin.autospark.app.ui.common.UiKit.iconButton
import com.worldmargin.autospark.app.ui.common.UiKit.padSystemBars
import com.worldmargin.autospark.app.ui.common.UiKit.titleBar

/** 单份协议的整页预览：从 assets 读全文，WebView 渲染，可复制。 */
class LicenseDetailActivity : Activity() {
    private var title = ""
    private var content = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val asset = intent.getStringExtra(EXTRA_ASSET).orEmpty()
        content = read(asset)

        val web = WebView(this).apply {
            settings.javaScriptEnabled = false
            setBackgroundColor(BACKGROUND)
            loadDataWithBaseURL(null, page(Markdown.toHtml(content)), "text/html", "utf-8", null)
        }

        setContentView(
            column().apply {
                setBackgroundColor(COLOR_BG)
                padSystemBars()
                addView(titleBar(title) { finish() })
                addView(divider())
                addView(
                    iconButton(COPY, "复制全文", primary = true, compact = true) { copy() }.apply {
                        setPadding(dp(16), dp(6), dp(16), dp(4))
                    },
                )
                addView(
                    web,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
    }

    private fun read(asset: String): String =
        runCatching { assets.open(asset).bufferedReader().use { it.readText() } }
            .getOrElse { "# 协议缺失\n\n请重新安装模块（$it）" }

    private fun copy() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("星火燎原 $title", content))
        Toast.makeText(this, "已复制：$title", Toast.LENGTH_SHORT).show()
    }

    private fun page(body: String): String =
        """
        <!DOCTYPE html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        body { margin: 0; padding: 12px 16px 48px; background: #f7f7f9; color: #1f1f1f;
               font: 14px/1.65 -apple-system, "Noto Sans CJK SC", sans-serif; }
        h1 { font-size: 20px; margin: 8px 0 12px; }
        h2 { font-size: 17px; margin: 20px 0 8px; padding-bottom: 4px; border-bottom: 1px solid #e3e3e8; }
        h3 { font-size: 15px; margin: 16px 0 6px; }
        p, li { margin: 5px 0; }
        code { background: #ececf1; border-radius: 4px; padding: 1px 5px;
               font: 12.5px/1.5 ui-monospace, Menlo, monospace; }
        pre { background: #26262b; color: #f2f2f7; border-radius: 8px; padding: 12px;
              overflow-x: auto; white-space: pre-wrap; word-break: break-all; }
        pre code { background: none; color: inherit; padding: 0; font-size: 12px; }
        table { border-collapse: collapse; width: 100%; margin: 10px 0; font-size: 13px; }
        th, td { border: 1px solid #dcdce3; padding: 6px 8px; text-align: left; vertical-align: top; }
        th { background: #ececf1; }
        blockquote { margin: 10px 0; padding: 8px 12px; background: #efeff5;
                     border-left: 3px solid #6c7cff; color: #414154; }
        hr { border: none; border-top: 1px solid #e3e3e8; margin: 16px 0; }
        a { color: #2f6bff; overflow-wrap: anywhere; }
        </style></head><body>
        $body
        </body></html>
        """.trimIndent()

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_ASSET = "asset"
        private const val BACKGROUND = 0xFFF7F7F9.toInt()
    }
}
