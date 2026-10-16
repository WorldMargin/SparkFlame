package com.worldmargin.autospark.features.autospark.provider

import android.content.Context
import android.os.Bundle
import com.worldmargin.autospark.features.autospark.model.ConfigCodec
import com.worldmargin.autospark.features.autospark.model.ConversationSnapshot
import com.worldmargin.autospark.features.autospark.model.SparkConfig

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
}
