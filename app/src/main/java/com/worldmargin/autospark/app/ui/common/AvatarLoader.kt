package com.worldmargin.autospark.app.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 头像加载器：内存缓存 → 磁盘缓存 → 网络下载（成功即落盘）。
 *
 * 请求不到时用「名字首字 + 色块」的占位图，界面上不会出现空洞。
 */
object AvatarLoader {
    private val memory = LruCache<String, Bitmap>(96)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    private const val TIMEOUT_MS = 8_000
    private const val MAX_EDGE = 256

    /** 把 [url] 的头像装进 [view]；失败/为空时显示 [name] 首字占位图。头像按圆形显示。 */
    fun load(
        context: Context,
        view: ImageView,
        url: String,
        name: String,
        sizeDp: Int = 42,
    ) {
        clipToCircle(view)
        val size = (sizeDp * context.resources.displayMetrics.density).toInt()
        view.tag = url
        val placeholder = placeholder(name, size)
        memory.get(memoryKey(url, name, size))?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageBitmap(placeholder)
        if (url.isBlank()) return

        pool.execute {
            val cache = File(avatarDir(context), fileName(url))
            val bitmap = if (cache.isFile) decode(cache) else download(url)?.also { save(cache, it) }
            if (bitmap == null) return@execute
            memory.put(memoryKey(url, name, size), bitmap)
            main.post {
                if (view.tag == url) view.setImageBitmap(bitmap)
            }
        }
    }

    /** 让 ImageView 以圆形裁切显示（占位图本身就是圆的，一起裁没有副作用）。 */
    private fun clipToCircle(view: ImageView) {
        if (view.clipToOutline) return
        view.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(
                    target: View,
                    outline: Outline,
                ) {
                    outline.setOval(0, 0, target.width, target.height)
                }
            }
        view.clipToOutline = true
    }

    /** 名字首字占位图（按名字散列取色）。 */
    private fun placeholder(
        name: String,
        size: Int,
    ): Bitmap {
        val key = "placeholder:${name}:$size"
        memory.get(key)?.let { return it }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorOf(name) }
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, fill)
        val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.45f
            textAlign = Paint.Align.CENTER
        }
        val text = name.trim().take(1).ifEmpty { "?" }
        val offset = (letter.descent() + letter.ascent()) / 2f
        canvas.drawText(text, radius, radius - offset, letter)
        memory.put(key, bitmap)
        return bitmap
    }

    private fun colorOf(name: String): Int {
        val palette =
            intArrayOf(0xFF3B7CFF.toInt(), 0xFF12B886.toInt(), 0xFFE8590C.toInt(), 0xFF9C36B5.toInt(), 0xFF1098AD.toInt())
        val hash = name.fold(0) { acc, c -> acc * 31 + c.code }
        return palette[Math.abs(hash % palette.size)]
    }

    private fun decode(file: File): Bitmap? =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val options =
                BitmapFactory.Options().apply {
                    inSampleSize =
                        generateSequence(1) { it * 2 }
                            .takeWhile { bounds.outWidth / it > MAX_EDGE || bounds.outHeight / it > MAX_EDGE }
                            .lastOrNull() ?: 1
                }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }.getOrNull()

    private fun download(url: String): Bitmap? =
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            }
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

    private fun save(
        file: File,
        bitmap: Bitmap,
    ) {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun avatarDir(context: Context): File = File(context.cacheDir, "avatars")

    private fun fileName(url: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".png"
    }

    private fun memoryKey(
        url: String,
        name: String,
        size: Int,
    ): String = if (url.isBlank()) "name:$name:$size" else "$url:$size"
}
