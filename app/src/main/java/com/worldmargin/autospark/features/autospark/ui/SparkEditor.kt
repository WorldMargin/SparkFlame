package com.worldmargin.autospark.features.autospark.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import com.worldmargin.autospark.core.ModLog
import com.worldmargin.autospark.features.autospark.model.SparkConfig
import com.worldmargin.autospark.features.autospark.script.SparkTypeScript
import io.github.rosemoe.sora.widget.CodeEditor
import org.json.JSONObject

/** 脚本编辑器：Monaco（WebView）与原生 sora-editor 两套实现的统一接口。 */
interface SparkEditor {
    /** 放进界面的 view。 */
    val view: View

    /** 当前文本。Monaco 侧取编辑器回传的缓存值，原生侧直接取编辑器内容。 */
    val text: String

    fun setText(value: String)

    /**
     * 注入上下文类型声明：Monaco 用它给出 `ctx` 与自定义函数的补全与文档。
     */
    fun applyTypes(config: SparkConfig)

    /** 主动唤起补全。 */
    fun showCompletion()

    /** 在当前光标 / 选区处插入文本（「粘贴」按钮用）。 */
    fun insert(text: String)

    /** 快速格式化：清掉不可见字符 / 智能引号 / Markdown 围栏，并尽量重排缩进。 */
    fun format()

    /** 页面销毁时释放资源（WebView 需要显式销毁）。 */
    fun release()
}

/** 编辑器工厂：默认 Monaco，设置页可切回原生编辑器。 */
object SparkEditors {
    fun create(
        activity: Activity,
        config: SparkConfig,
    ): SparkEditor = if (config.useMonacoEditor) MonacoSparkEditor(activity) else SoraSparkEditor(activity, config)
}

/** 原生编辑器（sora-editor）：自带 JS 高亮与基于 Rhino 的补全。 */
class SoraSparkEditor(
    activity: Activity,
    config: SparkConfig,
) : SparkEditor {
    private val editor =
        CodeEditor(activity).apply {
            typefaceText = Typeface.MONOSPACE
            setTextSize(14f)
            setupSparkEditing(config.functions)
        }

    override val view: View get() = editor

    override val text: String get() = editor.text.toString()

    override fun setText(value: String) {
        editor.setText(value)
    }

    override fun applyTypes(config: SparkConfig) = Unit

    override fun showCompletion() {
        editor.showSparkCompletion()
    }

    override fun insert(text: String) {
        if (text.isEmpty()) return
        try {
            val current = editor.text.toString()
            val at = editor.cursor.left.coerceIn(0, current.length)
            editor.setText(current.substring(0, at) + text + current.substring(at))
            val caret = at + text.length
            editor.setSelection(caret, caret)
        } catch (error: Throwable) {
            ModLog.w("原生编辑器插入文本失败: $error")
        }
    }

    override fun format() {
        val current = editor.text.toString()
        val cleaned = SparkEditorSanitizer.clean(current)
        if (cleaned != current) editor.setText(cleaned)
    }

    override fun release() = Unit
}

/**
 * Monaco 编辑器：WebView 加载模块 assets 里的 Monaco（离线，无网络请求）。
 *
 * 页面通过 [Bridge] 回传就绪与文本变化，补全与类型检查由 Monaco 的 TypeScript
 * 语言服务在 worker 里完成；`ctx` 的声明由 [SparkTypeScript] 生成后注入。
 * assets 用 [WebViewAssetLoader] 映射到 https 源，worker 才能正常加载。
 */
class MonacoSparkEditor(
    private val activity: Activity,
) : SparkEditor {
    private val container = FrameLayout(activity)
    private val web = WebView(activity)
    private var cached = ""
    private var ready = false
    private var released = false
    private var pendingText: String? = null
    private var pendingTypes: String? = null

    init {
        val loader =
            WebViewAssetLoader
                .Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
                .build()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.isVerticalScrollBarEnabled = false
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        // 关掉 WebView 的原生长按选择：否则长按会冒出手柄与系统的复制 / 全选工具栏，
        // 和 Monaco 自己的选区打架。长按由页面侧当作普通触摸处理。
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.setBackgroundColor(BACKGROUND)
        web.addJavascriptInterface(Bridge(), "AndroidEditor")
        web.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
            }
        container.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        web.loadUrl(ENTRY_URL)
    }

    override val view: View get() = container

    override val text: String get() = cached

    override fun setText(value: String) {
        cached = value
        if (ready) {
            eval("window.spark && window.spark.setText(" + JSONObject.quote(value) + ")")
        } else {
            pendingText = value
        }
    }

    override fun applyTypes(config: SparkConfig) {
        val dts = SparkTypeScript.dts(config)
        if (ready) {
            eval("window.spark && window.spark.setTypes(" + JSONObject.quote(dts) + ")")
        } else {
            pendingTypes = dts
        }
    }

    override fun showCompletion() {
        // 只唤起候选，不动键盘：键盘由用户直接点编辑区触发
        eval("window.spark && window.spark.triggerSuggest()")
    }

    override fun insert(text: String) {
        if (text.isEmpty()) return
        cached = if (cached.isEmpty()) text else cached
        eval("window.spark && window.spark.insertText(" + JSONObject.quote(text) + ")")
    }

    override fun format() {
        eval("window.spark && window.spark.format()")
    }

    /** 拉起软键盘（Monaco 的输入框拿到焦点后调用）。 */
    private fun showKeyboard() {
        runCatching {
            val manager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * 收起软键盘（页面判断出这是滑动而不是点击时调用）。
     *
     * 编辑器一旦获得过焦点，WebView 会把输入法一直挂在自己的可编辑节点上 ——
     * 之后再滑动也会把键盘带回来，所以滑动时要在网页侧主动 blur，这里再把输入法收掉。
     */
    private fun hideKeyboard() {
        runCatching {
            // WebView 自己还拿着焦点时 hideSoftInputFromWindow 经常不生效，
            // 先把焦点清掉（再次点击时 onFocus 会重新 requestFocus）。
            web.clearFocus()
            val manager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.hideSoftInputFromWindow(web.windowToken, 0)
        }
    }

    override fun release() {
        released = true
        runCatching {
            container.removeAllViews()
            web.destroy()
        }
    }

    private fun eval(script: String) {
        if (released) return
        runCatching { web.evaluateJavascript(script, null) }
            .onFailure { ModLog.w("Monaco 执行失败: $it") }
    }

    /** 页面就绪后把排队的文本与类型定义推过去。 */
    private fun flush() {
        pendingText?.let { setText(it) }
        pendingText = null
        pendingTypes?.let { dts -> eval("window.spark && window.spark.setTypes(" + JSONObject.quote(dts) + ")") }
        pendingTypes = null
    }

    /** 页面回调（JS 线程），统一切回主线程处理。 */
    private inner class Bridge {
        @JavascriptInterface
        fun onReady() {
            container.post {
                ready = true
                flush()
            }
        }

        @JavascriptInterface
        fun onChange(text: String) {
            container.post { cached = text }
        }

        /** Monaco 的输入框拿到焦点：这时才拉起输入法。 */
        @JavascriptInterface
        fun onFocus() {
            container.post {
                web.requestFocus()
                showKeyboard()
            }
        }

        /** 页面判断这是滑动（不是点击）：把输入法收掉，免得一滑键盘就回来。 */
        @JavascriptInterface
        fun onBlur() {
            container.post { hideKeyboard() }
        }
    }

    private companion object {
        const val ENTRY_URL = "https://appassets.androidplatform.net/assets/monaco/index.html"
        const val BACKGROUND = 0xFFFFFFFF.toInt()
    }
}

/**
 * 复制粘贴常见脏字符的清洗：从 AI 或网页复制脚本时容易带上的零宽字符、
 * 不换行空格、智能引号与 Markdown 代码围栏。
 *
 * Monaco 侧在页面里做同一件事（见 `assets/monaco/spark.js` 的 `format()`），
 * 这里给原生编辑器用。
 */
object SparkEditorSanitizer {
    private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\u2060\\uFEFF]")
    private val FENCE_HEAD = Regex("^\\s*```[a-zA-Z]*\\s*\\n?")
    private val FENCE_TAIL = Regex("\\n?```\\s*$")

    fun clean(text: String): String =
        text
            .replace(FENCE_HEAD, "")
            .replace(FENCE_TAIL, "")
            .replace(ZERO_WIDTH, "")
            .replace('\u00A0', ' ')
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u201C', '"')
            .replace('\u201D', '"')
}
