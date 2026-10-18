package com.worldmargin.autospark.app.editor

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
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
import com.worldmargin.autospark.app.ui.common.UiKit
import com.worldmargin.autospark.app.ui.common.UiKit.icon
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

    /** 把系统剪贴板的内容插到 Monaco 光标处（「粘贴」按钮与长按菜单共用）。 */
    private fun pasteFromClipboard() {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(activity, "剪贴板是空的", Toast.LENGTH_SHORT).show()
        } else {
            eval("window.spark && window.spark.insertText(" + JSONObject.quote(text) + ")")
        }
    }

    /**
     * 长按菜单：悬浮在**编辑器上方**的一个小圆角浮层（只画图标）。
     *
     * 四个动作：全选 / 复制 / 粘贴 / 撤销，做完就自己消失；点别处也会消失。
     * 不画在网页里、也不居中弹窗（两者都会把两三行高的小编辑器整块盖住），
     * 上方没地方就浮在编辑器下方，都不行才贴底部。
     */
    private fun showLongPressMenu() {
        if (released) return
        val root = activity.window?.decorView ?: return
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        var popup: android.widget.PopupWindow? = null
        val pill =
            android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dp(22).toFloat()
                        setColor(0xF2202124.toInt())
                    }
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
        val actions =
            listOf(
                UiKit.Icons.SELECTION to ("全选" to { eval("window.spark && window.spark.selectAll()") }),
                UiKit.Icons.COPY to ("复制" to { eval("window.spark && window.spark.copySelection()") }),
                UiKit.Icons.CLIPPY to ("粘贴" to { pasteFromClipboard() }),
                UiKit.Icons.DISCARD to ("撤销" to { eval("window.spark && window.spark.undo()") }),
            )
        actions.forEach { (glyph, entry) ->
            val (label, action) = entry
            val button =
                activity.icon(glyph, 19f, 0xFFFFFFFF.toInt()).apply {
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(46), dp(46))
                    contentDescription = label
                    isClickable = true
                    // 按下反馈：圆角水波
                    background =
                        android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                            null,
                            android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                cornerRadius = dp(20).toFloat()
                                setColor(0xFFFFFFFF.toInt())
                            },
                        )
                    setOnClickListener {
                        popup?.dismiss()
                        action()
                    }
                }
            pill.addView(button)
        }

        pill.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        val width = pill.measuredWidth
        val height = pill.measuredHeight
        val metrics = activity.resources.displayMetrics
        val insets =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                root.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())
            } else {
                null
            }
        val statusTop = insets?.top ?: 0
        val navBottom = insets?.bottom ?: 0
        val margin = dp(8)
        val location = IntArray(2)
        web.getLocationOnScreen(location)
        val anchorWidth = if (web.width > 0) web.width else metrics.widthPixels
        val anchorHeight = if (web.height > 0) web.height else dp(60)
        val x =
            (location[0] + (anchorWidth - width) / 2)
                .coerceIn(margin, (metrics.widthPixels - width - margin).coerceAtLeast(margin))
        val above = location[1] - height - dp(6)
        val below = location[1] + anchorHeight + dp(6)
        val aboveFits = above >= statusTop + dp(4)
        val belowFits = below + height <= metrics.heightPixels - navBottom - dp(4)
        // 编辑器在上半屏：浮在它下面；在下半屏：浮在它上面（都不挡编辑区）
        val editorInUpperHalf = location[1] + anchorHeight / 2 < metrics.heightPixels / 2
        val y =
            when {
                editorInUpperHalf && belowFits -> below
                aboveFits -> above
                belowFits -> below
                else -> metrics.heightPixels - navBottom - height - margin
            }
        val window =
            android.widget.PopupWindow(
                pill,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true,
            ).apply {
                isOutsideTouchable = true
                elevation = 10f * density
            }
        popup = window
        window.showAtLocation(root, android.view.Gravity.NO_GRAVITY, x, y)
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

        /** Monaco 的输入框拿到焦点：这时才拉起输入法。 */
        /** 复制：把选中的文字（没选就是全文）写进系统剪贴板。 */
        @JavascriptInterface
        fun onCopy(text: String) {
            activity.runOnUiThread {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("星火燎原", text))
                Toast.makeText(activity, "已复制 " + text.length + " 字", Toast.LENGTH_SHORT).show()
            }
        }

        /** 粘贴：读系统剪贴板再回填到 Monaco 光标处（WebView 里读剪贴板不稳，走原生）。 */
        @JavascriptInterface
        fun onPasteRequest() {
            activity.runOnUiThread { pasteFromClipboard() }
        }

        /**
         * 长按：弹**原生**菜单（全选 / 复制 / 粘贴 / 撤销）。
         *
         * 原来这套菜单画在网页里，编辑器只有两三行高时（变量页）会把整块编辑区盖住，
         * 所以改成 Kotlin 侧 `AlertDialog`，动作再回灌给网页。
         */
        @JavascriptInterface
        fun onMenuRequest() {
            activity.runOnUiThread { showLongPressMenu() }
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
