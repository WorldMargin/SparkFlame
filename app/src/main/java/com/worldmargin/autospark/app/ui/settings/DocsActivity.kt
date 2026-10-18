package com.worldmargin.autospark.app.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.Toast
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_DIM
import com.worldmargin.autospark.app.ui.common.UiKit.button
import com.worldmargin.autospark.app.ui.common.UiKit.column
import com.worldmargin.autospark.app.ui.common.UiKit.divider
import com.worldmargin.autospark.app.ui.common.UiKit.dp
import com.worldmargin.autospark.app.ui.common.UiKit.iconButton
import com.worldmargin.autospark.app.ui.common.UiKit.padSystemBars
import com.worldmargin.autospark.app.ui.common.UiKit.row
import com.worldmargin.autospark.app.ui.common.UiKit.text
import com.worldmargin.autospark.app.ui.common.UiKit.titleBar
import com.worldmargin.autospark.app.ui.common.Markdown
import com.worldmargin.autospark.app.ui.common.UiKit

/**
 * 使用文档：读模块 assets 里的 Markdown，渲染成 HTML 显示，可导出 / 分享 / 复制。
 *
 * 「复制全文」是为了把整份文档贴给 AI，让它按文档里的 API 直接生成脚本。
 */
class DocsActivity : Activity() {
    private var markdown = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        markdown = readManual()
        val web = WebView(this)
        web.settings.javaScriptEnabled = false
        web.setBackgroundColor(BACKGROUND)
        web.loadDataWithBaseURL(null, page(Markdown.toHtml(markdown)), "text/html", "utf-8", null)

        setContentView(
            column().apply {
                setBackgroundColor(UiKit.COLOR_BG)
                padSystemBars()
                addView(titleBar("使用文档") { finish() })
                addView(divider())
                addView(
                    row().apply {
                        setPadding(dp(16), dp(4), dp(16), dp(4))
                        addView(iconButton(UiKit.Icons.COPY, "复制全文", primary = true, compact = true) { copyAll() })
                        addView(iconButton(UiKit.Icons.DOWNLOAD, "导出 .md", compact = true) { export() })
                        addView(iconButton(UiKit.Icons.SHARE, "分享", compact = true) { share() })
                    },
                )
                addView(
                    web,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )
    }

    private fun readManual(): String =
        runCatching {
            assets.open(MANUAL_ASSET).bufferedReader().use { it.readText() }
        }.getOrElse {
            "# 使用文档缺失\n\n请重新安装模块（$it）"
        }

    private fun copyAll() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("星火燎原 使用文档", markdown))
        Toast.makeText(this, "已复制全文，可以贴给 AI 了", Toast.LENGTH_SHORT).show()
    }

    /** 走系统的「新建文件」流程导出 Markdown，方便传给 AI 或存到电脑上。 */
    private fun export() {
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/markdown")
                .putExtra(Intent.EXTRA_TITLE, FILE_NAME)
        runCatching { startActivityForResult(intent, REQUEST_EXPORT) }
            .onFailure { Toast.makeText(this, "没有可用的文件管理器", Toast.LENGTH_SHORT).show() }
    }

    private fun share() {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("text/markdown")
                .putExtra(Intent.EXTRA_SUBJECT, "星火燎原 使用文档")
                .putExtra(Intent.EXTRA_TEXT, markdown)
        runCatching { startActivity(Intent.createChooser(intent, "分享使用文档")) }
            .onFailure { Toast.makeText(this, "没有可分享的应用", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("导出结果回调")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val ok =
            runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(markdown.toByteArray()) } != null
            }.getOrDefault(false)
        Toast.makeText(this, if (ok) "已导出 $FILE_NAME" else "导出失败", Toast.LENGTH_SHORT).show()
    }

    private fun page(body: String): String =
        """
        <!DOCTYPE html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        body { margin: 0; padding: 12px 16px 48px; background: #f7f7f9; color: #1f1f1f;
               font: 15px/1.7 -apple-system, "Noto Sans CJK SC", sans-serif; }
        h1 { font-size: 22px; margin: 8px 0 12px; }
        h2 { font-size: 19px; margin: 22px 0 8px; padding-bottom: 4px; border-bottom: 1px solid #e3e3e8; }
        h3 { font-size: 16px; margin: 18px 0 6px; }
        p, li { margin: 6px 0; }
        code { background: #ececf1; border-radius: 4px; padding: 1px 5px;
               font: 13px/1.5 ui-monospace, Menlo, monospace; }
        pre { background: #26262b; color: #f2f2f7; border-radius: 8px; padding: 12px;
              overflow-x: auto; }
        pre code { background: none; color: inherit; padding: 0; font-size: 12.5px; }
        table { border-collapse: collapse; width: 100%; margin: 10px 0; font-size: 13.5px; }
        th, td { border: 1px solid #dcdce3; padding: 6px 8px; text-align: left; vertical-align: top; }
        th { background: #ececf1; }
        blockquote { margin: 10px 0; padding: 8px 12px; background: #efeff5;
                     border-left: 3px solid #6c7cff; color: #414154; }
        hr { border: none; border-top: 1px solid #e3e3e8; margin: 18px 0; }
        a { color: #2f6bff; }
        </style></head><body>
        $body
        </body></html>
        """.trimIndent()

    companion object {
        /** 使用文档在 assets 里的位置（构建时从仓库根目录的 使用文档.md 复制过来）。 */
        const val MANUAL_ASSET = "docs/manual.md"

        private const val FILE_NAME = "星火燎原-使用文档.md"
        private const val REQUEST_EXPORT = 0x51
        private const val BACKGROUND = 0xFFF7F7F9.toInt()
    }
}
