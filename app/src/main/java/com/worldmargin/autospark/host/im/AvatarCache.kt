package com.worldmargin.autospark.host.im

import android.content.Context
import android.os.Bundle
import com.worldmargin.autospark.app.config.ConfigClient
import com.worldmargin.autospark.core.ModLog
import java.io.File
import java.security.MessageDigest

/**
 * 会话头像：从抖音自己的本地缓存读取后推给模块 App。
 *
 * 模块不申请 INTERNET，AvatarLoader 只读 `cacheDir/avatars/<uid 的 md5>.png`；
 * 缓存会因卸载 / 更新丢掉，且抖音里换头像后内容会变。所以宿主在刷新快照时，
 * 直接从抖音的头像缓存目录按 URL 末段找文件、推给模块覆盖写入：
 *
 * 1. 断网可用 —— 读的是抖音已缓存的图片，不发任何网络请求；
 * 2. 内容变更自动跟上 —— 每次刷新都推，抖音缓存更新后文件字节就变了；
 * 3. 找不到缓存的（抖音还没加载过）跳过，等下次刷新。
 *
 * 抖音缓存目录：`files/tc_effect/aweme/720x720/aweme-avatar/<url 末段>.jpeg`，
 * 文件名就是头像 URL 的最后一段，实测一一对应。
 *
 * key 规则与模块 AvatarLoader.fileName 一致：uid 的 UTF-8 MD5 十六进制。
 */
object AvatarCache {
    /** 抖音头像缓存目录（相对其 files 目录）。 */
    private const val DOUYIN_AVATAR_DIR = "tc_effect/aweme/720x720/aweme-avatar"

    /** 单次 IPC 的图片总量上限，避免撞 binder 的 1MB transaction 限制。 */
    private const val BATCH_BYTES = 200 * 1024

    /**
     * 把抖音已缓存的头像推给模块。返回本次推送的张数。
     *
     * [items] 是 (uid, url) 对：
     * - uid 是稳定标识，用作模块侧缓存 key —— 换头像 / URL 参数变化都不影响命中；
     * - url 只用来在抖音缓存目录里定位文件（文件名就是 URL 末段）。
     */
    fun sync(
        context: Context,
        items: Collection<Pair<String, String>>,
    ): Int {
        if (items.isEmpty()) return 0

        // 抖音的 files 目录：宿主进程跑在抖音内，applicationInfo 就是抖音的
        val source =
            runCatching { File(context.applicationInfo.dataDir, "files/$DOUYIN_AVATAR_DIR") }
                .getOrNull() ?: return 0
        if (!source.isDirectory) return 0

        var pushed = 0
        var batch = Bundle()
        var batchBytes = 0
        for ((uid, url) in items) {
            if (uid.isBlank() || url.isBlank()) continue
            // 末段可能带查询参数（?from=...），先去掉再匹配磁盘文件名
            val name =
                url.trim().substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
                    ?: continue
            val file = File(source, if (name.endsWith(".jpeg") || name.endsWith(".jpg")) name else "$name.jpeg")
            if (!file.isFile) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            if (bytes.isEmpty() || bytes.size > 512 * 1024) continue
            batch.putByteArray(keyOf(uid), bytes)
            batchBytes += bytes.size
            if (batchBytes >= BATCH_BYTES) {
                if (ConfigClient.putAvatars(context, batch)) pushed += batch.keySet().size
                batch = Bundle()
                batchBytes = 0
            }
        }
        if (batch.keySet().isNotEmpty()) {
            if (ConfigClient.putAvatars(context, batch)) pushed += batch.keySet().size
        }
        if (pushed > 0) ModLog.i("头像同步 $pushed 张（按 uid 对应）")
        return pushed
    }

    private fun keyOf(uid: String): String =
        MessageDigest.getInstance("MD5")
            .digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
