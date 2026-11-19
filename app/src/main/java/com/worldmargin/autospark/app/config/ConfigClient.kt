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

    /**
     * 用户在 App 里是否已「无视风险」授权改包版本继续使用。
     *
     * 返回 null = 通道不可达（模块 App 被停用 / 从没启动过），调用方按未授权处理。
     */
    fun sigAccepted(context: Context): Boolean? =
        runCatching {
            val result = context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_SIG_STATE, null, null)
                ?: return null
            if (!result.getBoolean(ConfigContract.KEY_OK)) return null
            result.getBoolean(ConfigContract.KEY_ACCEPTED)
        }.getOrNull()

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

    /** 贴纸缩略图在模块侧的状态：[version] 为 null 表示一版都没同步过。 */
    data class StickerAssets(
        val version: String?,
        val count: Int,
    )

    /** 查模块侧的缩略图状态（只在必要时用，正常推送不需要先查）。 */
    fun stickerAssets(context: Context): StickerAssets =
        runCatching {
            val result =
                context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_STICKER_ASSETS, null, null)
                    ?: return StickerAssets(null, 0)
            if (!result.getBoolean(ConfigContract.KEY_OK)) return StickerAssets(null, 0)
            StickerAssets(
                result.getString(ConfigContract.KEY_VERSION)?.takeIf { it.isNotBlank() },
                result.getInt(ConfigContract.KEY_COUNT, 0),
            )
        }.getOrDefault(StickerAssets(null, 0))

    /**
     * 推一批贴纸缩略图（key = 图片对象名的 md5）。
     *
     * 分批而不是逐张：每批一个 Bundle，省掉几十次 binder 往返；单批控制在 ~200KB 以内，
     * 避免撞上 1MB 的 transaction 上限。
     */
    fun putStickerImages(
        context: Context,
        images: Map<String, ByteArray>,
    ): Boolean =
        runCatching {
            val bundle = Bundle()
            images.forEach { (key, bytes) -> bundle.putByteArray(key, bytes) }
            val extras =
                Bundle().apply {
                    putString(ConfigContract.KEY_OP, ConfigContract.OP_PUT)
                    putBundle(ConfigContract.KEY_IMAGES, bundle)
                }
            context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_STICKER_ASSETS, null, extras)
                ?.getBoolean(ConfigContract.KEY_OK) == true
        }.getOrDefault(false)

    /**
     * 提交这一版：把暂存目录并进正式目录、清掉旧图、写版本。
     *
     * [keys] 是这一版需要的全部 key；不在里面的旧图会在这一步删掉。
     */
    fun commitStickerAssets(
        context: Context,
        version: String,
        count: Int,
        keys: List<String>,
    ): Boolean =
        runCatching {
            val extras =
                Bundle().apply {
                    putString(ConfigContract.KEY_OP, ConfigContract.OP_COMMIT)
                    putString(ConfigContract.KEY_VERSION, version)
                    putInt(ConfigContract.KEY_COUNT, count)
                    putStringArrayList(ConfigContract.KEY_KEYS, ArrayList(keys))
                }
            context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_STICKER_ASSETS, null, extras)
                ?.getBoolean(ConfigContract.KEY_OK) == true
        }.getOrDefault(false)

    /** 贴纸目录同步元信息：moduleVersion / douyinVersion / at。 */
    fun stickerMeta(context: Context): org.json.JSONObject? =
        runCatching {
            val file = java.io.File(context.filesDir, "sticker_meta.json")
            if (file.isFile) org.json.JSONObject(file.readText()) else null
        }.getOrNull()

}
