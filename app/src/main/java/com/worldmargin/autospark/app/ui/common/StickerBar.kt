package com.worldmargin.autospark.app.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
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

/** 文本表情：`[name]` 文本码 + 抖音小表情图。 */
data class DouyinSticker(
    val name: String,
    val code: String,
    val asset: String,
)

/** 互动贴纸：脚本里插入 `sticker("name")`。 */
data class InteractiveSticker(
    val name: String,
    val staticUrl: String,
)

object DouyinStickers {
    @Volatile
    private var cache: List<DouyinSticker>? = null

    @Volatile
    private var cachedVersion: String? = null

    private fun version(context: Context): String? =
        runCatching {
            java.io.File(context.filesDir, "emoji/version").readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()

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

/** 互动贴纸目录（模块侧缓存，宿主同步过来的）。 */
object InteractiveStickers {
    @Volatile
    private var cache: List<InteractiveSticker>? = null

    fun items(context: Context): List<InteractiveSticker> {
        cache?.let { return it }
        val loaded =
            runCatching {
                val file = java.io.File(context.filesDir, "sticker_catalog.json")
                if (!file.isFile) return emptyList()
                val array = org.json.JSONArray(file.readText())
                (0 until array.length()).mapNotNull { index ->
                    val obj = array.optJSONObject(index) ?: return@mapNotNull null
                    val name = obj.optString("name")
                    val static = obj.optString("static")
                    if (name.isBlank() || static.isBlank()) null else InteractiveSticker(name, static)
                }
            }.getOrDefault(emptyList())
        cache = loaded
        return loaded
    }
}

/** 编辑器工具栏上的「表情」按钮：底部抽屉。 */
fun Context.stickerButton(editor: SparkEditor): android.view.View =
    iconButton(UiKit.Icons.REACTIONS, "", compact = true, hint = "表情") {
        showStickerPanel(this, editor)
    }

/** 底部抽屉：表情 / 贴纸两个 tab，点一下插入对应文本。 */
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(24))
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

    var closing = false
    fun closeSheet() {
        if (closing) return
        closing = true
        content
            .animate()
            .translationY(content.height.toFloat())
            .setDuration(220L)
            .withEndAction { runCatching { dialog.dismiss() } }
            .start()
    }

    val tabRow = context.row().apply { setPadding(context.dp(12), context.dp(2), context.dp(12), 0) }
    val emojiTab = tabText(context, "表情")
    val stickerTab = tabText(context, "贴纸")
    tabRow.addView(emojiTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    tabRow.addView(stickerTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    content.addView(tabRow)

    val search =
        EditText(context).apply {
            hint = "搜索"
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
                    topMargin = context.dp(8)
                    bottomMargin = context.dp(6)
                }
        }
    content.addView(search)

    val emojiGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val stickerGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val scroll =
        ScrollView(context).apply {
            addView(list)
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    collapsedHeight,
                )
        }
    content.addView(scroll)
    content.setPadding(0, 0, 0, context.dp(8))

    fun fillEmoji(keyword: String) {
        emojiGrid.removeAllViews()
        val items =
            DouyinStickers.items(context).filter {
                keyword.isBlank() || it.name.contains(keyword, true) || it.code.contains(keyword, true)
            }
        items.chunked(6).forEach { rowItems ->
            emojiGrid.addView(
                context.row().apply {
                    setPadding(context.dp(12), context.dp(2), context.dp(12), context.dp(2))
                    rowItems.forEach { item ->
                        addView(
                            stickerCell(context, item.name, DouyinStickers.drawable(context, item.asset), null) {
                                editor.insert(item.code)
                                closeSheet()
                            },
                        )
                    }
                },
            )
        }
    }

    fun fillSticker(keyword: String) {
        stickerGrid.removeAllViews()
        val items =
            InteractiveStickers.items(context).filter { keyword.isBlank() || it.name.contains(keyword, true) }
        items.chunked(4).forEach { rowItems ->
            stickerGrid.addView(
                context.row().apply {
                    setPadding(context.dp(12), context.dp(4), context.dp(12), context.dp(4))
                    rowItems.forEach { item ->
                        addView(
                            stickerCell(context, item.name, null, item.staticUrl) {
                                editor.insert("sticker(\"${item.name}\")")
                                closeSheet()
                            },
                        )
                    }
                },
            )
        }
    }

    fun render() {
        list.removeAllViews()
        list.addView(if (emojiTab.isSelected) emojiGrid else stickerGrid)
    }

    emojiTab.setOnClickListener {
        emojiTab.isSelected = true
        stickerTab.isSelected = false
        styleTab(context, emojiTab, true)
        styleTab(context, stickerTab, false)
        fillEmoji(search.text.toString())
        render()
    }
    stickerTab.setOnClickListener {
        emojiTab.isSelected = false
        stickerTab.isSelected = true
        styleTab(context, emojiTab, false)
        styleTab(context, stickerTab, true)
        fillSticker(search.text.toString())
        render()
    }
    search.addTextChangedListener(
        object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: android.text.Editable?) {
                if (emojiTab.isSelected) fillEmoji(s?.toString().orEmpty()) else fillSticker(s?.toString().orEmpty())
            }
        },
    )

    emojiTab.isSelected = true
    styleTab(context, emojiTab, true)
    styleTab(context, stickerTab, false)
    fillEmoji("")
    fillSticker("")
    render()

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
                val target = (startHeight - (event.rawY - startY).toInt()).coerceIn(minHeight, expandedHeight)
                (scroll.layoutParams as ViewGroup.MarginLayoutParams).height = target
                scroll.requestLayout()
                true
            }
            MotionEvent.ACTION_UP -> {
                val dy = event.rawY - startY
                if (dy > context.dp(120)) {
                    closeSheet()
                    return@setOnTouchListener true
                }
                val target = if (dy < -context.dp(40)) expandedHeight else collapsedHeight
                (scroll.layoutParams as ViewGroup.MarginLayoutParams).height = target
                scroll.requestLayout()
                true
            }
            else -> false
        }
    }

    dialog.setCanceledOnTouchOutside(false)
    dialog.window?.decorView?.setOnTouchListener { _, event ->
        if (event.action == MotionEvent.ACTION_DOWN) {
            val loc = IntArray(2)
            content.getLocationOnScreen(loc)
            if (event.rawY < loc[1]) {
                closeSheet()
                return@setOnTouchListener true
            }
        }
        false
    }
    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
            closeSheet()
            true
        } else {
            false
        }
    }
    dialog.show()
    content.post {
        content.translationY = content.height.toFloat()
        content.animate().translationY(0f).setDuration(220L).start()
    }
}

private fun tabText(
    context: Context,
    label: String,
): TextView =
    context.text(label, 15f, COLOR_TEXT).apply {
        gravity = Gravity.CENTER
        setPadding(0, context.dp(8), 0, context.dp(8))
        isFocusable = false
    }

private fun styleTab(
    context: Context,
    view: TextView,
    selected: Boolean,
) {
    view.setTextColor(if (selected) 0xFF1B1B1F.toInt() else 0xFF8A8A90.toInt())
    view.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
}

private fun stickerCell(
    context: Context,
    name: String,
    icon: Drawable?,
    imageUrl: String?,
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
        val image =
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44))
                scaleType = ImageView.ScaleType.FIT_CENTER
                icon?.let { setImageDrawable(it) }
            }
        addView(image)
        if (icon == null && !imageUrl.isNullOrBlank()) {
            // 图由宿主下载后推过来（模块不联网），这里只读本地缓存
            val cached = StickerImageStore.load(context, imageUrl)
            if (cached != null) {
                image.setImageBitmap(cached)
            } else {
                // 还没同步到 / 同步失败：留一块浅色占位，名字在下面一行
                image.setBackgroundColor(0x0F000000)
            }
        }
        addView(
            context.text(name, 11f, COLOR_TEXT).apply {
                gravity = Gravity.CENTER
                setPadding(0, context.dp(3), 0, 0)
            },
        )
    }
