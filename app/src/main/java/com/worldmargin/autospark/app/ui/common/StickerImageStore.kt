package com.worldmargin.autospark.app.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.worldmargin.autospark.core.StickerKey
import java.io.File

/**
 * 互动贴纸缩略图的本地缓存（模块进程侧）。
 *
 * 贴纸图在抖音的 CDN 上，模块本身不申请网络权限；图片由宿主（抖音进程）下载后推过来。
 * 落盘分两段（见 ConfigProvider）：
 *
 * - A = `files/sticker/`：已生效的一版（`<md5>.img` + index.json）
 * - B = `files/sticker.next/`：正在推的新一版，整版到齐后才并进 A
 *
 * 读取时**先 A 后 B**：更新过程中新图可能只在 B，旧的仍在 A，两边都能拿到完整文件，
 * 不会读到写了一半的图。
 */
object StickerImageStore {
    private const val DIR = "sticker"
    private const val STAGE = "sticker.next"
    private val memory = LruCache<String, Bitmap>(32)

    fun file(
        context: Context,
        url: String,
    ): File? {
        if (url.isBlank()) return null
        val name = key(url) + ".img"
        val live = File(File(context.filesDir, DIR), name)
        if (live.isFile && live.length() > 0) return live
        val staged = File(File(context.filesDir, STAGE), name)
        return staged.takeIf { it.isFile && it.length() > 0 }
    }

    /** 按地址取缩略图；没同步过 / 解不出来返回 null（调用方显示占位）。 */
    fun load(
        context: Context,
        url: String,
    ): Bitmap? {
        if (url.isBlank()) return null
        val id = key(url)
        memory.get(id)?.let { return it }
        val file = file(context, url) ?: return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        memory.put(id, bitmap)
        return bitmap
    }

    fun key(url: String): String = StickerKey.md5(url)
}
