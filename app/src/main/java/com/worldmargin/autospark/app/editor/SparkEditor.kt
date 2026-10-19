package com.worldmargin.autospark.app.editor

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
import com.worldmargin.autospark.app.config.SparkConfig
import com.worldmargin.autospark.app.config.TemplateVar
import com.worldmargin.autospark.app.script.SparkTypeScript
import org.json.JSONObject
import com.worldmargin.autospark.app.ui.common.Markdown

/** 脚本编辑器：Monaco（WebView）与原生 sora-editor 两套实现的统一接口。 */
interface SparkEditor {
    /** 放进界面的 view。 */
    val view: View

    /** 当前文本。Monaco 侧取编辑器回传的缓存值，原生侧直接取编辑器内容。 */
    val text: String

    fun setText(value: String)

    /**
     * 注入上下文类型声明：Monaco 用它给出 `ctx` 与自定义函数的补全与文档。
     *
     * [templateVars] 是当前模板已定义的变量，会被声明进 `ctx.template.<名字>`，
     * 于是脚本里写 `ctx.template.` 能补全出变量名（变量表达式页里也能引用前面的变量）。
     */
    fun applyTypes(
        config: SparkConfig,
        templateVars: List<TemplateVar> = emptyList(),
    )

    /** 主动唤起补全。 */
    fun showCompletion()

    /** 在当前光标 / 选区处插入文本（「粘贴」按钮用）。 */
    fun insert(text: String)

    /** 快速格式化：清掉不可见字符 / 智能引号 / Markdown 围栏，并尽量重排缩进。 */
    fun format()

    /**
     * 紧凑模式：给「变量」页那种两三行高的小输入框用（关行号、收内边距）。
     *
     * 高度不由网页自己决定 —— 调用方按内容行数算好高度设给 `view`，
     * 所以这里只把「看起来像个小输入框」的部分调好。
     */
    fun setCompact(compact: Boolean) = Unit

    /**
     * 观察内容变化（每次键入都会回调，用来给紧凑模式的小编辑器算高度）。
     *
     * 原生编辑器不实现（它有自己的滚动条），回调不触发也不影响使用。
     */
    fun observeTextChange(onChange: (String) -> Unit) = Unit

    /** 页面销毁时释放资源（WebView 需要显式销毁）。 */
    fun release()
}

/** 编辑器工厂：只有一套实现（WebView + CodeMirror）。 */
object SparkEditors {
    fun create(
        activity: Activity,
        @Suppress("UNUSED_PARAMETER") config: SparkConfig,
    ): SparkEditor = MonacoSparkEditor(activity)
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
    private var pendingCompact: Boolean? = null

    /** 内容变化回调（紧凑模式下用来重算高度）。 */
    private var changed: ((String) -> Unit)? = null

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
        // 长按走系统原生的选区与复制 / 全选菜单（CodeMirror 不接管长按）
        web.isLongClickable = true
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

    override fun applyTypes(
        config: SparkConfig,
        templateVars: List<TemplateVar>,
    ) {
        val dts = SparkTypeScript.dts(config, templateVars)
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

    override fun setCompact(compact: Boolean) {
        if (ready) {
            eval("window.spark && window.spark.setCompact(" + (if (compact) "true" else "false") + ")")
        } else {
            pendingCompact = compact
        }
    }

    override fun observeTextChange(onChange: (String) -> Unit) {
        changed = onChange
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
        pendingCompact?.let { setCompact(it) }
        pendingCompact = null
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
            container.post {
                cached = text
                changed?.invoke(text)
            }
        }

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
        const val ENTRY_URL = "https://appassets.androidplatform.net/assets/cm6/index.html"
        const val BACKGROUND = 0xFFFFFFFF.toInt()
    }
}
