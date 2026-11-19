package com.worldmargin.autospark.host

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 抖音内的版本提醒浮层（可复用）。
 *
 * 每次改 [NOTICE_ID] 并写好 [title] / [body]，用户下一次进入抖音时会看到一次；
 * 点「知道了」后把 id 记到抖音自己的 files 目录，同一版本不再重复提醒。
 */
object HostNotice {
    /** 改这个值 = 触发一次新的提醒。 */
    private const val NOTICE_ID = "v1.5.7"

    private const val TITLE = "星火燎原已更新"
    private const val BODY = "表情面板支持搜索，并会从抖音同步表情资源；脚本筛选会话请传函数。"

    private const val FILE = "spark_notice_id"

    private val inflight = AtomicBoolean(false)

    fun maybeShow(activity: Activity) {
        val context = activity.applicationContext
        val file = File(context.filesDir, FILE)
        val shown = runCatching { file.readText().trim() }.getOrDefault("")
        if (shown == NOTICE_ID) return
        if (!inflight.compareAndSet(false, true)) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val card = buildCard(activity) {
            runCatching { file.writeText(NOTICE_ID) }
            inflight.set(false)
        }
        root.addView(card)
    }

    private fun buildCard(
        activity: Activity,
        onDismiss: () -> Unit,
    ): View {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        var cardRef: LinearLayout? = null
        val card =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(10))
                background =
                    GradientDrawable().apply {
                        setColor(0xFFFFFFFF.toInt())
                        cornerRadius = dp(14).toFloat()
                    }
                elevation = dp(6).toFloat()
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        leftMargin = dp(16)
                        rightMargin = dp(16)
                        topMargin = dp(40)
                    }
            }

        card.addView(
            TextView(activity).apply {
                text = TITLE
                textSize = 15f
                setTextColor(0xFF1B1B1F.toInt())
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        card.addView(
            TextView(activity).apply {
                text = BODY
                textSize = 13f
                setTextColor(0xFF4B4B52.toInt())
                setPadding(0, dp(6), 0, dp(4))
            },
        )
        card.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(
                    TextView(activity).apply {
                        text = "知道了"
                        textSize = 14f
                        setTextColor(0xFF3B7CFF.toInt())
                        setPadding(dp(10), dp(6), dp(4), dp(6))
                        isClickable = true
                        setOnClickListener {
                            cardRef?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
                            onDismiss()
                        }
                    },
                )
            },
        )
        cardRef = card
        return card
    }
}
