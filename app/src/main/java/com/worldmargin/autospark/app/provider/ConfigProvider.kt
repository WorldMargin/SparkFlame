package com.worldmargin.autospark.app.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.worldmargin.autospark.app.config.ConfigCodec
import com.worldmargin.autospark.app.config.ConfigStore

/** 跨进程配置通道的契约常量（模块进程与抖音进程共用）。 */
object ConfigContract {
    const val AUTHORITY = "io.github.worldmargin.sparkflame.config"
    val URI: Uri = Uri.parse("content://$AUTHORITY")

    /** 读取全量配置，返回 Bundle["json"]。 */
    const val METHOD_READ = "read"

    /** 覆盖保存全量配置，入参 Bundle["json"]。 */
    const val METHOD_WRITE = "write"

    /** 记录某会话今日已发送，arg = conversationId，Bundle["day"]。 */
    const val METHOD_MARK_SENT = "markSent"

    /** 写入会话快照，Bundle["json"] = 快照数组 JSON。 */
    const val METHOD_PUT_SNAPSHOTS = "putSnapshots"

    /** 写入宿主侧运行简报，arg = 文本，Bundle["time"] = 时间戳。 */
    const val METHOD_NOTE = "note"

    /** 按数据保留策略清理（未在已选目标的会话，模板与发送记录超期即清），Bundle["time"] = 现在。 */
    const val METHOD_PRUNE = "prune"

    const val KEY_JSON = "json"
    const val KEY_DAY = "day"
    const val KEY_OK = "ok"
    const val KEY_TIME = "time"
    const val KEY_VERSION = "version"
    const val KEY_INDEX = "index"
    const val KEY_TOTAL = "total"
    const val KEY_PAYLOAD = "payload"
    const val KEY_BYTES = "bytes"
    const val KEY_OFFSET = "offset"

    /** 管理界面 → 抖音进程：请求刷新会话快照。 */
    const val ACTION_REFRESH_SNAPSHOT = "com.worldmargin.autospark.action.REFRESH_SNAPSHOT"

    /** 抖音进程 → 管理界面：快照已更新。 */
    const val ACTION_SNAPSHOT_UPDATED = "com.worldmargin.autospark.action.SNAPSHOT_UPDATED"

    /**
     * 管理界面 → 抖音进程：立即手动发送。
     *
     * 可选带上 [EXTRA_TARGETS]（会话 id 列表）；不带则发给所有「已选目标」。
     * 手动发送不受总开关与「今日已发」限制（是用户明确点的）。
     */
    const val ACTION_SEND_NOW = "com.worldmargin.autospark.action.SEND_NOW"

    /** 抖音进程 → 管理界面：手动发送结束，[EXTRA_SUMMARY] 是结果摘要。 */
    const val ACTION_SEND_DONE = "com.worldmargin.autospark.action.SEND_DONE"

    /** 手动发送的目标会话 id 列表（ArrayList&lt;String&gt;）。 */
    const val EXTRA_TARGETS = "targets"

    /** 手动发送的结果摘要。 */
    const val EXTRA_SUMMARY = "summary"

    /**
     * 管理界面 → 抖音进程：探活。
     *
     * 宿主进程没在运行时，发出去的指令广播会石沉大海（手动续火花看起来「点了没反应」），
     * 所以界面先探活、确认在线再下指令；宿主收到立刻回 [ACTION_HOST_ALIVE]。
     */
    const val ACTION_PING_HOST = "com.worldmargin.autospark.action.PING_HOST"

    /** 抖音进程 → 管理界面：探活回执（同时刷新存活时间戳）。 */
    const val ACTION_HOST_ALIVE = "com.worldmargin.autospark.action.HOST_ALIVE"

    /** 写入宿主存活时间戳，Bundle["time"]。 */
    const val METHOD_TOUCH_HOST = "touchHost"

    /** 查询已同步的抖音表情资源版本。 */
    const val METHOD_EMOJI_VERSION = "emojiVersion"

    /** 写入一批表情资源（version / index / total / payload）。 */
    const val METHOD_PUT_EMOJI_CHUNK = "putEmojiChunk"

    /**
     * 抖音进程 → 本模块进程：唤醒。
     *
     * 宿主调本模块的 ContentProvider 之前先发这个广播，让模块进程（连同它的 provider）先建起来。
     * 模块 App 装完从没启动过时，宿主的 provider 调用会失败（Failed to find provider info）。
     */
    const val ACTION_WAKE = "com.worldmargin.autospark.action.WAKE"
}

/**
 * 配置的跨进程读写入口。运行在模块 App 进程内，
 * 抖音进程通过 [ConfigContract.URI] 调用；管理界面同进程，可直接用 [ConfigStore]。
 */
class ConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        val context = context ?: return Bundle().apply { putBoolean(ConfigContract.KEY_OK, false) }
        return when (method) {
            ConfigContract.METHOD_READ -> {
                val json = ConfigCodec.encode(ConfigStore.load(context))
                Bundle().apply {
                    putBoolean(ConfigContract.KEY_OK, true)
                    putString(ConfigContract.KEY_JSON, json)
                }
            }

            ConfigContract.METHOD_WRITE -> {
                val json = extras?.getString(ConfigContract.KEY_JSON)
                if (json == null) {
                    Bundle().apply { putBoolean(ConfigContract.KEY_OK, false) }
                } else {
                    ConfigStore.save(context, ConfigCodec.decode(json))
                    Bundle().apply { putBoolean(ConfigContract.KEY_OK, true) }
                }
            }

            ConfigContract.METHOD_MARK_SENT -> {
                val day = extras?.getString(ConfigContract.KEY_DAY)
                if (arg == null || day == null) {
                    Bundle().apply { putBoolean(ConfigContract.KEY_OK, false) }
                } else {
                    ConfigStore.update(context) { config ->
                        config.copy(sentDays = config.sentDays + (arg to day))
                    }
                    Bundle().apply { putBoolean(ConfigContract.KEY_OK, true) }
                }
            }

            ConfigContract.METHOD_PUT_SNAPSHOTS -> {
                val json = extras?.getString(ConfigContract.KEY_JSON)
                if (json == null) {
                    Bundle().apply { putBoolean(ConfigContract.KEY_OK, false) }
                } else {
                    val snapshots = ConfigCodec.decode("""{"snapshots":$json}""").snapshots
                    val now = System.currentTimeMillis()
                    // 合并而不是整份替换：IM 分批吐会话，一次扫描只更新扫到的，没扫到的先留着
                    ConfigStore.update(context) { config -> config.mergeSnapshots(snapshots, now) }
                    Bundle().apply { putBoolean(ConfigContract.KEY_OK, true) }
                }
            }

            ConfigContract.METHOD_NOTE -> {
                val text = arg ?: extras?.getString(ConfigContract.KEY_JSON)
                val at = extras?.getLong(ConfigContract.KEY_TIME) ?: System.currentTimeMillis()
                if (text == null) {
                    Bundle().apply { putBoolean(ConfigContract.KEY_OK, false) }
                } else {
                    ConfigStore.update(context) { config -> config.copy(hostNote = text, hostNoteAt = at) }
                    Bundle().apply { putBoolean(ConfigContract.KEY_OK, true) }
                }
            }

            ConfigContract.METHOD_PRUNE -> {
                val now = extras?.getLong(ConfigContract.KEY_TIME) ?: System.currentTimeMillis()
                ConfigStore.update(context) { config -> config.pruneTemplates(now) }
                Bundle().apply { putBoolean(ConfigContract.KEY_OK, true) }
            }

            ConfigContract.METHOD_EMOJI_VERSION -> {
                val version = emojiVersion(context)
                Bundle().apply {
                    putBoolean(ConfigContract.KEY_OK, true)
                    putString(ConfigContract.KEY_VERSION, version)
                }
            }

            ConfigContract.METHOD_PUT_EMOJI_CHUNK -> {
                val version = extras?.getString(ConfigContract.KEY_VERSION)
                val bytes = extras?.getByteArray(ConfigContract.KEY_BYTES)
                val index = extras?.getInt(ConfigContract.KEY_INDEX, -1) ?: -1
                val total = extras?.getInt(ConfigContract.KEY_TOTAL, -1) ?: -1
                val offset = extras?.getLong(ConfigContract.KEY_OFFSET, -1L) ?: -1L
                if (version.isNullOrBlank() || bytes == null || index < 0 || total <= 0 || offset < 0) {
                    Bundle().apply { putBoolean(ConfigContract.KEY_OK, false) }
                } else {
                    val ok = putEmojiChunk(context, version, index, total, offset, bytes)
                    Bundle().apply { putBoolean(ConfigContract.KEY_OK, ok) }
                }
            }

            ConfigContract.METHOD_TOUCH_HOST -> {
                val at = extras?.getLong(ConfigContract.KEY_TIME) ?: System.currentTimeMillis()
                ConfigStore.update(context) { config -> config.copy(hostAliveAt = at) }
                Bundle().apply { putBoolean(ConfigContract.KEY_OK, true) }
            }

            else -> Bundle().apply { putBoolean(ConfigContract.KEY_OK, false) }
        }
    }

    private fun emojiRoot(context: Context) = java.io.File(context.filesDir, "emoji")

    private fun emojiVersion(context: Context): String? =
        runCatching {
            val file = java.io.File(emojiRoot(context), "version")
            if (file.isFile) file.readText().trim().takeIf { it.isNotEmpty() } else null
        }.getOrNull()

    private fun putEmojiChunk(
        context: Context,
        version: String,
        index: Int,
        total: Int,
        offset: Long,
        chunk: ByteArray,
    ): Boolean =
        runCatching {
            val root = emojiRoot(context)
            root.mkdirs()
            val dir = java.io.File(root, version)
            val part = java.io.File(root, version + ".zip.part")
            if (index == 0) {
                root.listFiles()?.forEach { file -> if (file != dir && file != part) file.deleteRecursively() }
                dir.deleteRecursively()
                part.delete()
            }
            java.io.RandomAccessFile(part, "rw").use { raf ->
                raf.seek(offset)
                raf.write(chunk)
            }
            if (index == total - 1) {
                java.util.zip.ZipInputStream(part.inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val out = java.io.File(dir, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
                // 校验：catalog 能解析且每张图都在，才认这个版本
                val ok = verifyEmojiDir(dir)
                if (ok) {
                    part.delete()
                    java.io.File(root, "version").writeText(version)
                } else {
                    dir.deleteRecursively()
                }
                ok
            } else {
                true
            }
        }.getOrDefault(false)

    /** 解压完整性校验：catalog.json 可解析，且每个条目对应的图片文件存在。 */
    private fun verifyEmojiDir(dir: java.io.File): Boolean =
        runCatching {
            val catalog = java.io.File(dir, "catalog.json")
            if (!catalog.isFile) return false
            val array = org.json.JSONArray(catalog.readText())
            if (array.length() == 0) return false
            for (i in 0 until array.length()) {
                val file = array.optJSONObject(i)?.optString("file").orEmpty()
                if (file.isBlank() || !java.io.File(dir, file).isFile) return false
            }
            true
        }.getOrDefault(false)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
