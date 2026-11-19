package com.worldmargin.autospark.host.im

import android.content.Context
import com.worldmargin.autospark.app.config.ConfigClient
import com.worldmargin.autospark.core.DouToastHelper
import com.worldmargin.autospark.core.ModLog
import com.worldmargin.autospark.core.StickerKey
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 互动贴纸缩略图：由宿主下载后推给模块 App。
 *
 * 模块本身不申请 `INTERNET`（见 manifest），贴纸图又只在抖音的 CDN 上，所以统一走宿主：
 *
 * 1. 宿主本地缓存 `files/spark_sticker_cache/<key>.img` 命中就直接用（换版本也不用重下）；
 * 2. 没有就 HTTP 拉一次（地址自带签名，普通 GET 即可，不需要登录态）；
 * 3. 逐张经 ContentProvider 推给模块的暂存目录，整版到齐后由模块并进正式目录并清理旧图；
 * 4. 把本版**可用**的 key 记在 `files/spark_sticker_keys.json`，脚本 / 面板据此过滤掉过期贴纸。
 *
 * 过期处理：官方下架的活动贴纸地址会 404/403，这类直接剔除（不再出现在目录里、缓存也删掉），
 * 不阻塞其余贴纸；只有网络抖动这类失败才整轮放弃、下次再补。
 */
object StickerCache {
    private const val UA = "Mozilla/5.0 (Linux; Android 14)"
    private const val TIMEOUT_MS = 10_000
    private const val PROBE_TIMEOUT_MS = 6_000
    private const val MAX_BYTES = 512 * 1024

    /** 单次 IPC 的图片总量上限：留足余量，不撞 binder 的 1MB transaction 限制。 */
    private const val BATCH_BYTES = 200 * 1024
    private const val KEYS_FILE = "spark_sticker_keys.json"

    /** 一次同步的结果：是否成功 + 这一版真正可用的资源 key。 */
    data class Result(
        val ok: Boolean,
        val usable: Set<String> = emptySet(),
    )

    private sealed interface Fetch {
        class Ok(
            val bytes: ByteArray,
            val downloaded: Boolean,
        ) : Fetch

        /** 官方已下架 / 签名失效：这一张不再算资源。 */
        data object Gone : Fetch

        /** 网络抖动 / 服务端异常：整轮放弃，下次重试。 */
        data object Failed : Fetch
    }

    @Volatile
    private var cachedUsable: Set<String>? = null

    /**
     * 把目录里的贴纸图同步到模块。
     *
     * 每次触发都逐张确认地址还有效（有缓存的发 HEAD，没缓存的直接 GET）：
     * 官方下架的活动贴纸地址会 404，这类从资源列表里剔除、缓存删掉，不阻塞其余贴纸；
     * 只有网络抖动才整轮放弃。触发本身很稀疏（模块更新 / 抖音更新 / 过期），这点请求可以接受。
     *
     * @return [Result.ok] 是否完成；[Result.usable] 这一版可用的资源 key（供目录过滤）
     */
    fun sync(context: Context): Result =
        runCatching {
            val items = StickerCatalog.items(context)
            if (items.isEmpty()) return@runCatching Result(true)
            val byKey = LinkedHashMap<String, StickerCatalog.Item>()
            items.forEach { item -> byKey.putIfAbsent(StickerKey.md5(item.staticUrl), item) }
            val version = version(items.map { it.staticUrl })

            val usable = LinkedHashSet<String>()
            var changed = false
            // ① 先做与模块 App 无关的网络工作（下载 + 地址校验）。
            //    这一步要好几秒，期间不需要模块进程跟着；做完再推，占线时间才短
            //    （实测：进程被拉起后 6~8 秒就可能被 LMK 杀掉）。
            val fetched = ArrayList<Triple<String, StickerCatalog.Item, Fetch>>(byKey.size)
            for ((key, item) in byKey) {
                val result = ensure(context, key, item.staticUrl)
                if (result is Fetch.Failed) return@runCatching Result(false)
                fetched += Triple(key, item, result)
            }

            // ② 短促地把这一版推给模块：本地 IPC，分批走（单批 ~200KB，避免撞 transaction 上限）
            val pending = LinkedHashMap<String, ByteArray>()
            var pendingBytes = 0
            fun flush() {
                if (pending.isEmpty()) return
                if (ConfigClient.putStickerImages(context, pending)) usable += pending.keys
                pending.clear()
                pendingBytes = 0
            }
            for ((key, item, result) in fetched) {
                when (result) {
                    is Fetch.Ok -> {
                        if (result.downloaded) changed = true
                        if (result.bytes.size >= BATCH_BYTES) {
                            flush()
                            if (ConfigClient.putStickerImages(context, mapOf(key to result.bytes))) usable += key
                        } else {
                            pending[key] = result.bytes
                            pendingBytes += result.bytes.size
                            if (pendingBytes >= BATCH_BYTES) flush()
                        }
                    }

                    Fetch.Gone -> {
                        // 过期贴纸：连宿主缓存一起清掉，且不进入本版资源列表
                        ModLog.w("贴纸已失效，剔除：${item.name}")
                        File(hostCacheDir(context), "$key.img").delete()
                        changed = true
                    }

                    Fetch.Failed -> Unit
                }
            }
            flush()
            if (usable.isEmpty()) return@runCatching Result(false)

            val before = readKeys(context)
            ConfigClient.commitStickerAssets(context, version, usable.size, usable.toList())
            pruneHostCache(context, usable)
            writeKeys(context, usable)
            if (changed || before != usable) {
                DouToastHelper.show(context, "（星火燎原：贴纸图已就绪 ${usable.size} 个）")
            }
            Result(true, usable)
        }.getOrDefault(Result(false))

    /** 当前版本可用的资源 key；空集 = 不知道（按不过滤处理）。 */
    fun usableKeys(context: Context): Set<String> =
        cachedUsable ?: (readKeys(context) ?: emptySet()).also { cachedUsable = it }

    /** 宿主自己的下载缓存只留当前这批，避免越攒越大。 */
    private fun pruneHostCache(
        context: Context,
        keys: Set<String>,
    ) {
        runCatching {
            hostCacheDir(context).listFiles()?.forEach { file ->
                if (file.name.substringBeforeLast('.') !in keys) file.delete()
            }
        }
    }

    /**
     * 确认这一张还能用，并保证拿到字节。
     *
     * - 有本地缓存：发一次 HEAD 只验地址（几毫秒），避免「图还留着但官方已下架、发出去是坏消息」
     * - 没有缓存：直接 GET，成功顺手落盘
     */
    private fun ensure(
        context: Context,
        key: String,
        url: String,
    ): Fetch {
        val cache = File(hostCacheDir(context), "$key.img")
        if (cache.isFile && cache.length() in 1..MAX_BYTES.toLong()) {
            return when (val code = probe(url)) {
                in 200..299 -> {
                    val bytes = runCatching { cache.readBytes() }.getOrNull() ?: return download(cache, url)
                    Fetch.Ok(bytes, downloaded = false)
                }

                403, 404, 410 -> Fetch.Gone
                else -> Fetch.Failed
            }
        }
        return download(cache, url)
    }

    /** HEAD 探测：只关心状态码。-1 = 请求本身失败。 */
    private fun probe(url: String): Int =
        runCatching {
            val connection =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = PROBE_TIMEOUT_MS
                    readTimeout = PROBE_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                }
            try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(-1)

    /** 下载图片并落盘。 */
    private fun download(
        cache: File,
        url: String,
    ): Fetch {
        val connection =
            runCatching {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                }
            }.getOrNull() ?: return Fetch.Failed
        return try {
            when (connection.responseCode) {
                // 下架 / 无权限 / 已删除：当作这张不存在
                403, 404, 410 -> Fetch.Gone
                !in 200..299 -> Fetch.Failed
                else -> {
                    val data = connection.inputStream.use { it.readBytes() }
                    if (data.isEmpty() || data.size > MAX_BYTES) {
                        Fetch.Failed
                    } else {
                        runCatching {
                            cache.parentFile?.mkdirs()
                            cache.writeBytes(data)
                        }
                        Fetch.Ok(data, downloaded = true)
                    }
                }
            }
        } catch (error: Exception) {
            ModLog.w("贴纸图下载失败：$error")
            Fetch.Failed
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun hostCacheDir(context: Context) = File(context.filesDir, "spark_sticker_cache")

    /** 版本：所有贴纸**对象名**的 SHA-256 前 16 位。CDN 域名 / 签名轮换不算变化。 */
    private fun version(urls: List<String>): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(urls.map { StickerKey.of(it) }.sorted().joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private fun readKeys(context: Context): Set<String>? =
        runCatching {
            val file = File(context.filesDir, KEYS_FILE)
            if (!file.isFile) return null
            val array = org.json.JSONObject(file.readText()).optJSONArray("keys") ?: return null
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { key -> key.isNotEmpty() } }.toSet()
        }.getOrNull()

    private fun writeKeys(
        context: Context,
        keys: Set<String>,
    ) {
        cachedUsable = keys
        runCatching {
            val json =
                org.json.JSONObject()
                    .put("keys", org.json.JSONArray(keys.toList()))
                    .put("at", System.currentTimeMillis())
            File(context.filesDir, KEYS_FILE).writeText(json.toString())
        }
    }
}
