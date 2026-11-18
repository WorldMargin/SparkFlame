package com.worldmargin.autospark.app.ui.preview

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import com.worldmargin.autospark.app.ui.common.DouyinStickers
import org.json.JSONArray
import java.net.URL

/** 预览页：按发送分段展示文本 / 表情 / 互动贴纸。 */
class PreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra("title") ?: "预览"
        val segmentsJson = intent.getStringExtra("segments") ?: "[]"
        val segments = runCatching { JSONArray(segmentsJson) }.getOrDefault(JSONArray())
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }
        root.addView(TextView(this).apply { text = title; textSize = 12f; setTextColor(0xFF8A8A90.toInt()); setPadding(0,0,0,dp(10)) })
        if (segments.length() == 0) root.addView(TextView(this).apply { text = "没有可发送的内容"; textSize = 15f })
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONObject(i) ?: continue
            when (seg.optString("kind")) {
                "text" -> root.addView(textBubble(seg.optString("text")))
                else -> root.addView(stickerBubble(seg.optString("name")))
            }
        }
        val scroll = ScrollView(this).apply { addView(root) }
        scroll.post { scroll.scrollTo(0, 0) }
        val statusBar = resources.getIdentifier("status_bar_height", "dimen", "android")
        val topInset = if (statusBar > 0) resources.getDimensionPixelSize(statusBar) else dp(24)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, topInset, 0, 0)
        }
        page.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(16), dp(8))
                addView(
                    TextView(this@PreviewActivity).apply {
                        text = "\uea9b"
                        textSize = 18f
                        setTextColor(0xFF1B1B1F.toInt())
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        setOnClickListener { finish() }
                    },
                )
                addView(
                    TextView(this@PreviewActivity).apply {
                        text = "预览"
                        textSize = 17f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(0xFF1B1B1F.toInt())
                        setPadding(dp(4), 0, 0, 0)
                    },
                )
            },
        )
        page.addView(
            View(this).apply {
                setBackgroundColor(0x11000000)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            },
        )
        page.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(page)
    }

    private fun textBubble(text: String): TextView {
        val tv = TextView(this).apply {
            textSize = 15f
            isFocusable = false
            isFocusableInTouchMode = false
            setTextColor(0xFF1B1B1F.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(12).toFloat() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
        tv.text = emojiSpanned(text)
        return tv
    }

    private fun emojiSpanned(text: String): CharSequence {
        val sb = SpannableStringBuilder(text)
        val map = DouyinStickers.items(this).associateBy { it.code }
        val re = Regex("\\[[^\\[\\]\\n]{1,12}]")
        re.findAll(text).toList().reversed().forEach { m ->
            val item = map[m.value] ?: return@forEach
            val d = DouyinStickers.drawable(this, item.asset) ?: return@forEach
            val size = dp(20)
            d.setBounds(0, 0, size, size)
            sb.setSpan(ImageSpan(d), m.range.first, m.range.last + 1, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun stickerBubble(name: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFF4F4F7.toInt()); cornerRadius = dp(12).toFloat() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
        val image = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)) }
        box.addView(image)
        box.addView(TextView(this).apply { text = "[互动表情] $name"; textSize = 12f; setTextColor(0xFF6B6B72.toInt()) })
        val url = stickerUrl(name)
        if (url != null) {
            Thread {
                runCatching {
                    val bitmap = URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                    if (bitmap != null) runOnUiThread { image.setImageDrawable(BitmapDrawable(resources, bitmap)) }
                }
            }.start()
        }
        return box
    }

    private fun stickerUrl(name: String): String? =
        runCatching {
            val file = java.io.File(filesDir, "sticker_catalog.json")
            if (!file.isFile) return null
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.optJSONObject(it) }
                .firstOrNull { it?.optString("name") == name }?.optString("static")
        }.getOrNull()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 把引擎的分段结果交给预览页展示。 */
        fun open(
            context: Context,
            title: String,
            rendered: com.worldmargin.autospark.app.script.SparkScriptEngine.Rendered,
        ) {
            val arr = JSONArray()
            rendered.segments.forEach { seg ->
                arr.put(org.json.JSONObject().put("kind", seg.kind).put("text", seg.text).put("name", seg.name))
            }
            if (rendered.segments.isEmpty()) {
                arr.put(org.json.JSONObject().put("kind", "text").put("text", rendered.text))
            }
            context.startActivity(
                Intent(context, PreviewActivity::class.java)
                    .putExtra("title", title)
                    .putExtra("segments", arr.toString()),
            )
        }
    }
}
