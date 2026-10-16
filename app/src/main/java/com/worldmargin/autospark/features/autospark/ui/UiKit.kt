package com.worldmargin.autospark.features.autospark.ui

import android.content.Context
import android.graphics.Insets
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.ColorInt

/** 轻量 UI 工具：全部用代码建视图，避免引入布局资源与主题依赖。 */
object UiKit {
    const val COLOR_TEXT = 0xFF1B1B1F.toInt()
    const val COLOR_DIM = 0xFF6B6B72.toInt()
    const val COLOR_ACCENT = 0xFF3B7CFF.toInt()
    const val COLOR_WARN = 0xFFE0523B.toInt()
    const val COLOR_CARD = 0xFFF3F4F7.toInt()
    const val COLOR_BG = 0xFFFFFFFF.toInt()
    const val COLOR_LINE = 0x1F000000

    /** 图标字体（VSCode codicon，随模块打包，离线可用）：名字 → 码点。 */
    object Icons {
        const val FIRE = "\ueaf2"
        const val TEMPLATE = "\ueaf0"
        const val CODE = "\ueac4"
        const val GEAR = "\ueaf8"
        const val BACK = "\uea9b"
        const val CHEVRON_RIGHT = "\ueab6"
        const val CHEVRON_DOWN = "\ueab4"
        const val ADD = "\uea60"
        const val REMOVE = "\ueb3b"
        const val GRAPH = "\ueb03"
        const val SWAP = "\uebcb"
        const val BOOK = "\ueaa4"
        const val SERVER = "\ueba2"
        const val INFO = "\uea74"
        const val CHECK = "\ueab2"
        const val CLOSE = "\uea76"
        const val SEARCH = "\uea6d"
        const val REFRESH = "\ueb37"
        const val PLAY = "\ueb2c"
        const val COPY = "\uebcc"
        const val SHARE = "\uec25"
        const val DOWNLOAD = "\ueac2"
        const val EXPORT = "\uebac"
        const val IMPORT = "\ueac3"
        const val EDIT = "\uea73"
        const val TRASH = "\uea81"
        const val MAIL = "\ueb1c"
        const val WARNING = "\uea6c"
    }

    private var iconTypeface: Typeface? = null

    /** 图标字体（懒加载；取不到时退回默认字体，界面不至于崩）。 */
    private fun Context.iconFont(): Typeface =
        iconTypeface ?: runCatching { Typeface.createFromAsset(assets, "fonts/codicon.ttf") }
            .getOrDefault(Typeface.DEFAULT)
            .also { iconTypeface = it }

    /** 一个图标（codicon 码点，见 [Icons]）。 */
    fun Context.icon(
        glyph: String,
        sizeSp: Float = 18f,
        @ColorInt color: Int = COLOR_TEXT,
    ): TextView =
        TextView(this).apply {
            text = glyph
            textSize = sizeSp
            setTextColor(color)
            typeface = iconFont()
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun Context.column(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    /** 撑满剩余高度的一列（用于页面根容器）。 */
    fun Context.pageColumn(): LinearLayout =
        column().apply {
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        }

    fun Context.card(): LinearLayout =
        column().apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(COLOR_CARD)
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                }
        }

    fun Context.text(
        text: String,
        sizeSp: Float = 15f,
        @ColorInt color: Int = COLOR_TEXT,
        bold: Boolean = false,
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun Context.sectionTitle(title: String): TextView =
        text(title, 13f, COLOR_DIM, bold = true).apply {
            setPadding(0, dp(14), 0, dp(2))
        }

    /**
     * 顶部标题栏（本应用不用系统 ActionBar，避免 Android 15+ 边到边窗口下内容被标题栏遮住）。
     *
     * [onBack] 不为空时左侧显示返回图标；[action] 是右侧动作（例如「保存」）。
     * 注意 [onBack] 排在最后，`titleBar("标题") { finish() }` 这种尾随 lambda 才算返回动作。
     */
    fun Context.titleBar(
        title: String,
        action: View? = null,
        onBack: (() -> Unit)? = null,
    ): LinearLayout =
        row().apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            if (onBack != null) {
                addView(
                    icon(Icons.BACK, 20f).apply {
                        setPadding(0, dp(2), dp(14), dp(2))
                        isClickable = true
                        setOnClickListener { onBack() }
                    },
                )
            }
            addView(
                text(title, 19f, COLOR_TEXT, bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
            if (action != null) addView(action)
        }

    /**
     * 让内容避开系统栏。
     *
     * Android 15 起「边到边」是强制的（targetSdk ≥ 35），窗口内容会铺满整屏、
     * 系统状态栏与导航栏直接盖在内容上；老系统上这些 inset 已被系统扣掉，这里补 0，行为不变。
     *
     * 底部同时考虑**输入法**高度：键盘弹出时把内容顶上去，否则编辑区下半截会被键盘盖住。
     */
    fun View.padSystemBars() {
        val baseTop = paddingTop
        val baseBottom = paddingBottom
        setOnApplyWindowInsetsListener { view, insets ->
            val bars =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.systemBars())
                } else {
                    @Suppress("DEPRECATION")
                    Insets.of(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                }
            val keyboard =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.ime()).bottom
                } else {
                    0
                }
            view.setPadding(
                view.paddingLeft,
                baseTop + bars.top,
                view.paddingRight,
                baseBottom + maxOf(bars.bottom, keyboard),
            )
            insets
        }
        requestApplyInsets()
    }

    /** 只占位、不显示内容的分隔（用于把后面的控件推到右边）。 */
    fun Context.spacer(): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1).apply { weight = 1f }
        }

    fun Context.button(
        label: String,
        primary: Boolean = false,
    ): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), dp(4), dp(12), dp(4))
            if (primary) setTextColor(COLOR_ACCENT)
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                }
        }

    fun Context.row(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    /** 竖向滚动容器，用于每个标签页的内容。 */
    fun Context.scroll(): ScrollView =
        ScrollView(this).apply {
            isFillViewport = true
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        }

    /**
     * 底部主导航：图标 + 文字等分排列，选中项用强调色。
     */
    fun Context.navBar(
        icons: List<String>,
        labels: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ): LinearLayout =
        row().apply {
            setPadding(dp(4), dp(6), dp(4), dp(6))
            labels.forEachIndexed { index, label ->
                val active = index == selected
                val color = if (active) COLOR_ACCENT else COLOR_DIM
                addView(
                    column().apply {
                        gravity = Gravity.CENTER
                        isClickable = true
                        setPadding(0, dp(8), 0, dp(8))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        addView(icon(icons[index], 20f, color))
                        addView(
                            text(label, 12f, color, bold = active).apply {
                                gravity = Gravity.CENTER
                                setPadding(0, dp(3), 0, 0)
                            },
                        )
                        setOnClickListener { onSelect(index) }
                    },
                )
            }
        }

    /**
     * 开关行：**整行可点**，[android.widget.Switch] 只当指示器。
     *
     * 之前把回调挂在 Switch 上，点按区域小、且容易被外层容器吃掉，表现为「开关点不动」。
     */
    fun Context.switchRow(
        title: String,
        desc: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View =
        row().apply {
            setPadding(0, dp(6), 0, dp(6))
            addView(
                column().apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(text(title, 16f, COLOR_TEXT, bold = true))
                    if (desc.isNotBlank()) addView(text(desc, 12f, COLOR_DIM))
                },
            )
            val indicator =
                Switch(this@switchRow).apply {
                    isChecked = checked
                    isClickable = false
                    isFocusable = false
                }
            addView(indicator)
            isClickable = true
            setOnClickListener {
                val next = !indicator.isChecked
                indicator.isChecked = next
                onChange(next)
            }
        }

    /**
     * 二级页面入口行：图标 + 标题（+ 说明）+ 右箭头，整行可点。
     */
    fun Context.menuRow(
        glyph: String,
        title: String,
        desc: String,
        onClick: () -> Unit,
    ): View =
        row().apply {
            background = rounded(COLOR_CARD, 10)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                }
            addView(icon(glyph, 18f, COLOR_ACCENT).apply { setPadding(0, 0, dp(12), 0) })
            addView(
                column().apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(text(title, 16f, COLOR_TEXT, bold = true))
                    if (desc.isNotBlank()) addView(text(desc, 12f, COLOR_DIM))
                },
            )
            addView(icon(Icons.CHEVRON_RIGHT, 16f, COLOR_DIM))
            setOnClickListener { onClick() }
            isClickable = true
        }

    /** 带图标的按钮（[compact] 用于编辑器底部动作条，占位更小）。 */
    fun Context.iconButton(
        glyph: String,
        label: String,
        primary: Boolean = false,
        compact: Boolean = false,
        onClick: () -> Unit,
    ): View =
        row().apply {
            background = rounded(if (primary) 0x1A3B7CFF else COLOR_CARD, 10)
            if (compact) {
                setPadding(dp(10), dp(6), dp(10), dp(6))
            } else {
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = if (compact) dp(6) else dp(8)
                }
            val color = if (primary) COLOR_ACCENT else COLOR_TEXT
            addView(icon(glyph, if (compact) 14f else 15f, color).apply { if (label.isNotEmpty()) setPadding(0, 0, dp(5), 0) })
            if (label.isNotEmpty()) addView(text(label, if (compact) 13f else 14f, color))
            isClickable = true
            setOnClickListener { onClick() }
        }

    /**
     * 编辑器底部动作条：横向可滚动，按钮再多也不会被挤出屏幕。
     */
    fun Context.actionRow(build: LinearLayout.() -> Unit): View {
        val inner = row().apply { setPadding(0, dp(4), 0, dp(4)) }
        inner.build()
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(inner)
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * 页面转场：新内容淡入 + 轻微右滑，避免标签/页面切换太生硬。
     */
    fun View.enterFromRight() {
        alpha = 0f
        translationX = resources.displayMetrics.density * 24f
        animate().alpha(1f).translationX(0f).setDuration(180).start()
    }

    /** 页面转场：新内容淡入 + 轻微左滑（从二级页面返回时用）。 */
    fun View.enterFromLeft() {
        alpha = 0f
        translationX = -resources.displayMetrics.density * 24f
        animate().alpha(1f).translationX(0f).setDuration(180).start()
    }

    /**
     * 页内分段控件：整体浅色圆角底，选中项为白色圆角块 + 强调色文字。
     */
    fun Context.segmented(
        labels: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ): LinearLayout =
        row().apply {
            background = rounded(COLOR_CARD, 10)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(4)
                }
            labels.forEachIndexed { index, label ->
                val active = index == selected
                addView(
                    text(label, 14f, if (active) COLOR_ACCENT else COLOR_DIM, bold = active).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(8), 0, dp(8))
                        background = if (active) rounded(COLOR_BG, 8) else null
                        layoutParams =
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                if (index > 0) marginStart = dp(3)
                            }
                        isClickable = true
                        setOnClickListener { onSelect(index) }
                    },
                )
            }
        }

    /** 圆角纯色背景。 */
    fun Context.rounded(
        @ColorInt color: Int,
        radiusDp: Int,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    /** 一条细分隔线。 */
    fun Context.divider(): View =
        View(this).apply {
            setBackgroundColor(COLOR_LINE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        }

    /** 可点击的卡片容器。 */
    fun View.asClickable(onClick: () -> Unit): View =
        apply {
            isClickable = true
            setOnClickListener { onClick() }
        }
}
