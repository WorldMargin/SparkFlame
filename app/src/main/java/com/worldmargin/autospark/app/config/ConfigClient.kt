package com.worldmargin.autospark.app.config

import android.content.Context
import android.os.Bundle
import com.worldmargin.autospark.app.provider.ConfigContract
import com.worldmargin.autospark.app.config.ConfigCodec
import com.worldmargin.autospark.app.config.ConversationSnapshot
import com.worldmargin.autospark.app.config.SparkConfig
import com.worldmargin.autospark.app.provider.ConfigProvider

/**
 * 抖音进程访问配置的客户端（走 ContentResolver → [ConfigProvider]）。
 *
 * 所有方法都会在跨进程失败时安全返回，不抛出——宿主侧调用点只需处理 null/false。
 */
object ConfigClient {
    fun read(context: Context): SparkConfig? =
        runCatching {
            val result = context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_READ, null, null)
            val json = result?.getString(ConfigContract.KEY_JSON) ?: return null
            ConfigCodec.decode(json)
        }.getOrNull()

    fun write(
        context: Context,
        config: SparkConfig,
    ): Boolean =
        runCatching {
            val extras = Bundle().apply { putString(ConfigContract.KEY_JSON, ConfigCodec.encode(config)) }
            context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_WRITE, null, extras)
                ?.getBoolean(ConfigContract.KEY_OK) == true
        }.getOrDefault(false)

    fun markSent(
        context: Context,
        conversationId: String,
        day: String,
    ): Boolean =
        runCatching {
            val extras = Bundle().apply { putString(ConfigContract.KEY_DAY, day) }
            context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_MARK_SENT, conversationId, extras)
                ?.getBoolean(ConfigContract.KEY_OK) == true
        }.getOrDefault(false)

    /** 写入宿主侧运行简报（管理界面的「设置 → 宿主状态」直接读配置里的这份文本）。 */
    fun note(
        context: Context,
        text: String,
        at: Long,
    ): Boolean =
        runCatching {
            val extras = Bundle().apply { putLong(ConfigContract.KEY_TIME, at) }
            context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_NOTE, text, extras)
                ?.getBoolean(ConfigContract.KEY_OK) == true
        }.getOrDefault(false)

    /** 触发一次数据保留清理（宿主写完全快照后调用）。 */
    fun prune(
        context: Context,
        now: Long,
    ): Boolean =
        runCatching {
            val extras = Bundle().apply { putLong(ConfigContract.KEY_TIME, now) }
            context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_PRUNE, null, extras)
                ?.getBoolean(ConfigContract.KEY_OK) == true
        }.getOrDefault(false)

    /** 刷新宿主存活时间戳（宿主进程每次响应指令时调用）。 */
    fun touchHost(
        context: Context,
        at: Long,
    ): Boolean =
        runCatching {
            val extras = Bundle().apply { putLong(ConfigContract.KEY_TIME, at) }
            context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_TOUCH_HOST, null, extras)
                ?.getBoolean(ConfigContract.KEY_OK) == true
        }.getOrDefault(false)

    fun putSnapshots(
        context: Context,
        snapshots: List<ConversationSnapshot>,
    ): Boolean =
        runCatching {
            val extras =
                Bundle().apply {
                    putString(ConfigContract.KEY_JSON, ConfigCodec.encodeSnapshots(snapshots).toString())
                }
            context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_PUT_SNAPSHOTS, null, extras)
                ?.getBoolean(ConfigContract.KEY_OK) == true
        }.getOrDefault(false)
    /** 已同步的抖音表情资源版本。 */
    fun emojiVersion(context: Context): String? =
        runCatching {
            context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_EMOJI_VERSION, null, null)
                ?.getString(ConfigContract.KEY_VERSION)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

    /** 推送一批表情资源（版本 + 分片序号 + JSON 负载）。 */
    fun putEmojiChunk(
        context: Context,
        version: String,
        index: Int,
        total: Int,
        offset: Long,
        bytes: ByteArray,
    ): Boolean =
        runCatching {
            val extras =
                Bundle().apply {
                    putString(ConfigContract.KEY_VERSION, version)
                    putInt(ConfigContract.KEY_INDEX, index)
                    putInt(ConfigContract.KEY_TOTAL, total)
                    putLong(ConfigContract.KEY_OFFSET, offset)
                    putByteArray(ConfigContract.KEY_BYTES, bytes)
                }
            context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_PUT_EMOJI_CHUNK, null, extras)
                ?.getBoolean(ConfigContract.KEY_OK) == true
        }.getOrDefault(false)

    /** 保存互动贴纸目录到模块缓存。 */
    fun putStickers(
        context: Context,
        json: String,
        moduleVersion: String,
        douyinVersion: String,
    ): Boolean =
        runCatching {
            val extras =
                Bundle().apply {
                    putString(ConfigContract.KEY_JSON, json)
                    putString("moduleVersion", moduleVersion)
                    putString("douyinVersion", douyinVersion)
                    putLong("at", System.currentTimeMillis())
                }
            context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_PUT_STICKERS, null, extras)
                ?.getBoolean(ConfigContract.KEY_OK) == true
        }.getOrDefault(false)

    /** 贴纸目录同步元信息：moduleVersion / douyinVersion / at。 */
    fun stickerMeta(context: Context): org.json.JSONObject? =
        runCatching {
            val file = java.io.File(context.filesDir, "sticker_meta.json")
            if (file.isFile) org.json.JSONObject(file.readText()) else null
        }.getOrNull()

}
