package com.worldmargin.autospark.app.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.worldmargin.autospark.BuildConfig
import com.worldmargin.autospark.app.config.ConfigCodec
import com.worldmargin.autospark.app.config.ConfigStore
import com.worldmargin.autospark.app.config.SigStore
import com.worldmargin.autospark.core.AuthGate

/** 跨进程配置通道的契约常量（模块进程与抖音进程共用）。 */
object ConfigContract {
    /** authority 跟着 applicationId 走（manifest 里用 `${applicationId}.config`） */
    const val AUTHORITY = BuildConfig.APPLICATION_ID + ".config"
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

    /**
     * 管理界面 → 抖音进程：请求同步一次资源（表情 / 贴纸目录 / 贴纸图）。
     *
     * 由 App 启动时主动发出 —— 此时它必然在运行，宿主的回推才有着落，
     * 不需要让模块 App 常驻后台（宿主也不必反向拉起它）。
     */
    const val ACTION_RES_SYNC = "com.worldmargin.autospark.action.RES_SYNC"

    /** 写入宿主存活时间戳，Bundle["time"]。 */
    const val METHOD_TOUCH_HOST = "touchHost"

    /** 查询已同步的抖音表情资源版本。 */
    const val METHOD_EMOJI_VERSION = "emojiVersion"

    /**
     * 查询「用户是否已授权继续使用改包版本」（签名校验失败时的一次性授权位）。
     *
     * 只读，且只对本模块自己的进程与抖音进程开放（见 [ConfigProvider.call] 的调用方校验）。
     */
    const val METHOD_SIG_STATE = "sigState"

    /** [METHOD_SIG_STATE] 的返回字段：是否已授权。 */
    const val KEY_ACCEPTED = "accepted"

    /** 授权位变化（模块 App 里点了「无视风险」）→ 通知宿主重新判定，不用重启抖音。 */
    const val ACTION_SIG_CHANGED = "com.worldmargin.autospark.action.SIG_CHANGED"

    /** 写入互动贴纸目录 + 同步元信息。 */
    const val METHOD_PUT_STICKERS = "putStickers"

    /**
     * 贴纸缩略图：一个入口，用 [KEY_OP] 区分动作。
     *
     * 之前拆成「查版本 / 逐张推 / 提交」三个方法，一次同步要 23 次 binder 调用；
     * 合并后一次同步只走「N 批（put）+ 1 次提交（commit）」，方法表也只多这一项。
     */
    const val METHOD_STICKER_ASSETS = "stickerAssets"

    /** [METHOD_STICKER_ASSETS] 的动作：查状态（不带 op 即此）。 */
    const val OP_STATE = "state"

    /** [METHOD_STICKER_ASSETS] 的动作：写入一批图，[KEY_IMAGES] = Bundle(key → byte[])。 */
    const val OP_PUT = "put"

    /** [METHOD_STICKER_ASSETS] 的动作：提交这一版，[KEY_VERSION] / [KEY_COUNT] / [KEY_KEYS]。 */
    const val OP_COMMIT = "commit"

    const val KEY_OP = "op"
    const val KEY_IMAGES = "images"
    const val KEY_COUNT = "count"

    /** 当前版本需要的缩略图 key 列表（ArrayList&lt;String&gt;）。 */
    const val KEY_KEYS = "keys"

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

            ConfigContract.METHOD_PUT_STICKERS -> {
                val json = extras?.getString(ConfigContract.KEY_JSON)
                if (json == null) {
                    Bundle().apply { putBoolean(ConfigContract.KEY_OK, false) }
                } else {
                    runCatching {
                        java.io.File(context.filesDir, "sticker_catalog.json").writeText(json)
                        val meta = org.json.JSONObject()
                        meta.put("moduleVersion", extras.getString("moduleVersion") ?: "")
                        meta.put("douyinVersion", extras.getString("douyinVersion") ?: "")
                        meta.put("at", extras.getLong("at", System.currentTimeMillis()))
                        java.io.File(context.filesDir, "sticker_meta.json").writeText(meta.toString())
                    }
                    Bundle().apply { putBoolean(ConfigContract.KEY_OK, true) }
                }
            }

            ConfigContract.METHOD_STICKER_ASSETS -> {
                val ex = extras
                when (ex?.getString(ConfigContract.KEY_OP)) {
                    ConfigContract.OP_PUT -> {
                        val images = ex.getBundle(ConfigContract.KEY_IMAGES)
                        val ok = images != null && writeStickerImages(context, images)
                        Bundle().apply { putBoolean(ConfigContract.KEY_OK, ok) }
                    }

                    ConfigContract.OP_COMMIT -> {
                        val version = ex.getString(ConfigContract.KEY_VERSION)
                        val keys = ex.getStringArrayList(ConfigContract.KEY_KEYS).orEmpty()
                        val count = ex.getInt(ConfigContract.KEY_COUNT, 0)
                        val ok = !version.isNullOrBlank() && commitStickerAssets(context, version, count, keys)
                        Bundle().apply { putBoolean(ConfigContract.KEY_OK, ok) }
                    }

                    else -> {
                        val index = stickerIndex(context)
                        Bundle().apply {
                            putBoolean(ConfigContract.KEY_OK, true)
                            putString(ConfigContract.KEY_VERSION, index?.optString("version"))
                            putInt(ConfigContract.KEY_COUNT, index?.optInt("count", 0) ?: 0)
                        }
                    }
                }
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

            ConfigContract.METHOD_SIG_STATE -> {
                // 调用方校验：provider 是导出的，不设限等于把「这台设备装了什么」告诉任何应用
                if (!sigStateCallerAllowed(context)) {
                    Bundle().apply { putBoolean(ConfigContract.KEY_OK, false) }
                } else {
                    Bundle().apply {
                        putBoolean(ConfigContract.KEY_OK, true)
                        putBoolean(ConfigContract.KEY_ACCEPTED, SigStore.accepted(context))
                    }
                }
            }

            else -> Bundle().apply { putBoolean(ConfigContract.KEY_OK, false) }
        }
    }

    /**
     * [ConfigContract.METHOD_SIG_STATE] 的调用方校验：只允许本模块自己的进程和抖音读取。
     *
     * 授权位本身读不出敏感信息，但它暴露「这台设备上装了什么、是不是改包」，没理由对路过的应用开放。
     * 按 uid 反查包名，包名无法伪造。
     */
    private fun sigStateCallerAllowed(context: Context): Boolean {
        val uid = android.os.Binder.getCallingUid()
        if (uid == android.os.Process.myUid()) return true
        val packages = runCatching { context.packageManager.getPackagesForUid(uid) }.getOrNull()
        return packages?.any { it == AuthGate.MODULE_PACKAGE || it == AuthGate.HOST_PACKAGE } == true
    }

    private fun emojiRoot(context: Context) = java.io.File(context.filesDir, "emoji")

    // ------------------------------------------------------------ 贴纸缩略图
    // 两段式落地：新图先写暂存目录（B = files/sticker.next），确认这一版全部到齐后
    // 一次性并进正式目录（A = files/sticker），再删掉 A 里这一版不再引用的旧图。
    //
    // 好处：① 更新过程中 A 始终是完整可用的旧集合，读图先 A 后 B，不会读到半截文件；
    //       ② 不会有「A、B 各存一份」的长期双份；
    //       ③ key 是图片对象名的 md5，A 里已有同一份就不再收，重复下载为 0。

    private fun stickerDir(context: Context) = java.io.File(context.filesDir, "sticker")

    private fun stickerStage(context: Context) = java.io.File(context.filesDir, "sticker.next")

    /** 正式目录里的 index.json（没有 = 从没同步过一版）。 */
    private fun stickerIndex(context: Context): org.json.JSONObject? =
        runCatching {
            val file = java.io.File(stickerDir(context), "index.json")
            if (file.isFile) org.json.JSONObject(file.readText()) else null
        }.getOrNull()

    /** 一批图逐张写进暂存目录；有一张失败就返回 false（整批会在下次触发时重来）。 */
    private fun writeStickerImages(
        context: Context,
        images: Bundle,
    ): Boolean {
        var ok = true
        images.keySet().forEach { key ->
            val bytes = images.getByteArray(key)
            if (bytes == null || !writeStickerImage(context, key, bytes)) ok = false
        }
        return ok
    }

    private fun writeStickerImage(
        context: Context,
        key: String,
        bytes: ByteArray,
    ): Boolean {
        if (!key.matches(Regex("[0-9a-f]{32}"))) return false
        if (bytes.isEmpty() || bytes.size > 512 * 1024) return false
        return runCatching {
            val live = java.io.File(stickerDir(context), "$key.img")
            if (live.isFile && live.length() > 0) {
                // A 里已经是同一份（key = 内容地址）：不必再收，顺手清掉暂存的重复
                java.io.File(stickerStage(context), "$key.img").delete()
                return@runCatching true
            }
            val dir = stickerStage(context)
            dir.mkdirs()
            val file = java.io.File(dir, "$key.img")
            if (file.isFile && file.length() == bytes.size.toLong()) return@runCatching true
            val temp = java.io.File(dir, "$key.part")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                file.writeBytes(bytes)
                temp.delete()
            }
            true
        }.getOrDefault(false)
    }

    /**
     * 提交这一版：[keys] 全部到齐后，把暂存目录整体并进正式目录，再清掉旧图并写 index.json。
     */
    private fun commitStickerAssets(
        context: Context,
        version: String,
        count: Int,
        keys: List<String>,
    ): Boolean =
        runCatching {
            val wanted = keys.filter { it.matches(Regex("[0-9a-f]{32}")) }.toHashSet()
            if (wanted.isEmpty()) return@runCatching false
            val live = stickerDir(context)
            val stage = stickerStage(context)
            live.mkdirs()

            // B → A：同盘 rename；目标已存在（A 本来就有同一份）就丢掉 B 的那份
            stage.listFiles()?.forEach { file ->
                if (file.name.endsWith(".part")) return@forEach
                val target = java.io.File(live, file.name)
                if (target.isFile && target.length() > 0) {
                    file.delete()
                } else if (!file.renameTo(target)) {
                    runCatching {
                        file.copyTo(target, overwrite = true)
                        file.delete()
                    }
                }
            }
            stage.deleteRecursively()

            // A 里不属于这一版的旧图删掉（地址换了之后旧对象不再被引用）
            live.listFiles()?.forEach { file ->
                if (file.name == "index.json") return@forEach
                if (file.name.substringBeforeLast('.') !in wanted) file.delete()
            }

            val json =
                org.json.JSONObject()
                    .put("version", version)
                    .put("count", count)
                    .put("at", System.currentTimeMillis())
            java.io.File(live, "index.json").writeText(json.toString())
            true
        }.getOrDefault(false)

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
            // 两段式：先解到暂存目录（B = files/emoji.next），整包校验通过后再整体换进
            // 正式目录（A = files/emoji）。中途失败时 A 里的旧版本原样可用。
            val root = emojiRoot(context)
            val stageRoot = java.io.File(context.filesDir, "emoji.next")
            val part = java.io.File(stageRoot, "$version.zip.part")
            val stageDir = java.io.File(stageRoot, version)
            if (index == 0) {
                stageRoot.deleteRecursively()
                stageDir.mkdirs()
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
                        val out = java.io.File(stageDir, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
                // 校验：catalog 能解析且每张图都在，才认这个版本
                val ok = verifyEmojiDir(stageDir)
                if (ok) {
                    root.mkdirs()
                    val target = java.io.File(root, version)
                    target.deleteRecursively()
                    if (!stageDir.renameTo(target)) {
                        stageDir.copyRecursively(target, overwrite = true)
                    }
                    java.io.File(root, "version").writeText(version)
                    // 资源唯一性：新版本就位后再清掉旧版本目录，任何时刻只有一份
                    root.listFiles()?.forEach { entry ->
                        if (entry != target && entry.name != "version") entry.deleteRecursively()
                    }
                    stageRoot.deleteRecursively()
                } else {
                    stageRoot.deleteRecursively()
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
