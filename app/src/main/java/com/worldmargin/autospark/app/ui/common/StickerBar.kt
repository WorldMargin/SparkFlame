package com.worldmargin.autospark.app.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.worldmargin.autospark.app.editor.SparkEditor
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_CARD
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_TEXT
import com.worldmargin.autospark.app.ui.common.UiKit.dp
import com.worldmargin.autospark.app.ui.common.UiKit.iconButton
import com.worldmargin.autospark.app.ui.common.UiKit.row
import com.worldmargin.autospark.app.ui.common.UiKit.rounded
import com.worldmargin.autospark.app.ui.common.UiKit.text
import org.json.JSONObject

/**
 * 一个抖音表情：[name] 面板显示名，[code] 插入编辑器的文本表情码，[asset] 抖音自己的表情图。
 *
 * 表情图取自抖音 `small_emoji_res/.../emoji_android/static`，与客户端面板同源；
 * 编辑器里的内联预览也用它，不再用 Unicode emoji 近似。
 */
data class DouyinSticker(
    val name: String,
    val code: String,
    val asset: String,
)

/** 抖音小表情目录（只收录已确认有对应图片文件的项）。 */
object DouyinStickers {
    @Volatile
    private var cache: List<DouyinSticker>? = null

    @Volatile
    private var cachedVersion: String? = null

    /** 当前已同步的抖音表情版本（宿主推过来的）。 */
    private fun version(context: Context): String? =
        runCatching {
            java.io.File(context.filesDir, "emoji/version").readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()

    /** 从宿主同步下来的 files/emoji/<version>/catalog.json 读取。 */
    fun items(context: Context): List<DouyinSticker> {
        val version = version(context) ?: return emptyList()
        if (version == cachedVersion) cache?.let { return it }
        val dir = java.io.File(context.filesDir, "emoji/$version")
        val loaded =
            runCatching {
                val array = org.json.JSONArray(java.io.File(dir, "catalog.json").readText())
                (0 until array.length()).mapNotNull { index ->
                    val obj = array.optJSONObject(index) ?: return@mapNotNull null
                    val file = obj.optString("file")
                    val code = obj.optString("code")
                    val name = obj.optString("name")
                    if (file.isBlank() || code.isBlank()) null else DouyinSticker(name, code, file)
                }
            }.getOrDefault(emptyList())
        cache = loaded
        cachedVersion = version
        return loaded
    }

    /** 表情码 → 内联预览图地址（WebView 通过 /files/emoji/ 映射到 filesDir）。 */
    fun previewMapJson(context: Context): String {
        val version = version(context) ?: return "{}"
        val json = JSONObject()
        items(context).forEach { item -> json.put(item.code, "/files/emoji/$version/" + item.asset) }
        return json.toString()
    }

    fun drawable(
        context: Context,
        asset: String,
    ): Drawable? {
        val version = version(context) ?: return null
        val file = java.io.File(context.filesDir, "emoji/$version/$asset")
        if (!file.isFile) return null
        return runCatching { Drawable.createFromPath(file.absolutePath) }.getOrNull()
    }
}

/** 编辑器工具栏上的「表情」按钮：从底部弹出表情面板。 */
fun Context.stickerButton(editor: SparkEditor): android.view.View =
    iconButton(UiKit.Icons.REACTIONS, "", compact = true, hint = "表情") {
        showStickerPanel(this, editor)
    }

/** 底部表情面板：抖音表情图网格，点一下插到编辑器光标处并关闭。 */
fun showStickerPanel(
    context: Context,
    editor: SparkEditor,
) {
    val dialog = Dialog(context)
    val metrics = context.resources.displayMetrics
    val collapsedHeight = (metrics.heightPixels * 0.45f).toInt()
    val expandedHeight = (metrics.heightPixels * 0.72f).toInt()

    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    val handle =
        android.widget.FrameLayout(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(24),
                )
            addView(
                android.view.View(context).apply {
                    layoutParams =
                        android.widget.FrameLayout.LayoutParams(context.dp(36), context.dp(4)).apply {
                            gravity = Gravity.CENTER
                        }
                    background = context.rounded(0x33000000, 2)
                },
            )
        }
    content.addView(handle)

    val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    inner.addView(
        context.text("抖音表情", 15f, COLOR_TEXT, bold = true).apply {
            setPadding(context.dp(16), context.dp(2), context.dp(16), context.dp(8))
        },
    )

    var closing = false
    fun closeSheet() {
        if (closing) return
        closing = true
        content.animate()
            .translationY(content.height.toFloat())
            .setDuration(220L)
            .withEndAction { runCatching { dialog.dismiss() } }
            .start()
    }

    val search =
        android.widget.EditText(context).apply {
            hint = "搜索表情"
            setSingleLine()
            textSize = 14f
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            background = context.rounded(COLOR_CARD, 10)
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    leftMargin = context.dp(12)
                    rightMargin = context.dp(12)
                    bottomMargin = context.dp(6)
                }
        }
    inner.addView(search)

    val all = DouyinStickers.items(context)
    val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun render(query: String) {
        grid.removeAllViews()
        val keyword = query.trim()
        val filtered =
            if (keyword.isEmpty()) {
                all
            } else {
                all.filter { item ->
                    item.name.contains(keyword, ignoreCase = true) ||
                        item.code.contains(keyword, ignoreCase = true)
                }
            }
        filtered.chunked(6).forEach { rowItems ->
            grid.addView(
                context.row().apply {
                    setPadding(context.dp(12), context.dp(2), context.dp(12), context.dp(2))
                    rowItems.forEach { item ->
                        addView(
                            stickerCell(context, item) {
                                editor.insert(item.code)
                                closeSheet()
                            },
                        )
                    }
                },
            )
        }
    }

    search.addTextChangedListener(
        object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: android.text.Editable?) {
                render(s?.toString().orEmpty())
            }
        },
    )
    render("")
    inner.addView(grid)

    val scroll =
        ScrollView(context).apply {
            isFillViewport = false
            addView(
                inner,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    collapsedHeight,
                )
        }
    content.addView(scroll)
    content.setPadding(0, 0, 0, context.dp(8))

    val radius = context.dp(16).toFloat()
    content.background =
        GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }

    dialog.setContentView(content)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setGravity(Gravity.BOTTOM)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.35f)
    }

    // 手柄拖动调节高度：上拉放大、下拉收起，松开按方向吸附
    var startY = 0f
    var startHeight = collapsedHeight
    val minHeight = (metrics.heightPixels * 0.3f).toInt()
    handle.setOnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startY = event.rawY
                startHeight = scroll.layoutParams.height
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = (event.rawY - startY).toInt()
                val target = (startHeight - dy).coerceIn(minHeight, expandedHeight)
                (scroll.layoutParams as ViewGroup.MarginLayoutParams).height = target
                scroll.requestLayout()
                true
            }
            MotionEvent.ACTION_UP -> {
                val dy = event.rawY - startY
                val target = if (dy < -context.dp(40)) expandedHeight else collapsedHeight
                (scroll.layoutParams as ViewGroup.MarginLayoutParams).height = target
                scroll.requestLayout()
                true
            }
            else -> false
        }
    }

    dialog.setCanceledOnTouchOutside(false)
    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
            closeSheet()
            true
        } else {
            false
        }
    }
    dialog.window?.decorView?.setOnTouchListener { _, event ->
        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
            val loc = IntArray(2)
            content.getLocationOnScreen(loc)
            if (event.rawY < loc[1]) {
                closeSheet()
                return@setOnTouchListener true
            }
        }
        false
    }
    dialog.show()
    content.post {
        content.translationY = content.height.toFloat()
        content.animate().translationY(0f).setDuration(220L).start()
    }
}

private fun stickerCell(
    context: Context,
    item: DouyinSticker,
    onClick: () -> Unit,
): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(4), context.dp(8), context.dp(4), context.dp(6))
        background = context.rounded(COLOR_CARD, 10)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = context.dp(4)
            }
        addView(
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(context.dp(40), context.dp(40))
                scaleType = ImageView.ScaleType.FIT_CENTER
                DouyinStickers.drawable(context, item.asset)?.let { setImageDrawable(it) }
            },
        )
        addView(
            context.text(item.name, 11f, COLOR_TEXT).apply {
                gravity = Gravity.CENTER
                setPadding(0, context.dp(3), 0, 0)
            },
        )
    }
