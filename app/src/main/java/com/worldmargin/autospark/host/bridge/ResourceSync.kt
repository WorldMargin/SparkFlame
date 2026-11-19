package com.worldmargin.autospark.host.bridge

import android.content.Context
import com.worldmargin.autospark.BuildConfig
import com.worldmargin.autospark.app.config.ConfigClient
import com.worldmargin.autospark.app.config.SparkConfig
import com.worldmargin.autospark.core.ModLog
import com.worldmargin.autospark.host.im.StickerCache
import org.json.JSONObject
import java.io.File

/**
 * 资源同步的总闸：表情 / 贴纸目录 / 贴纸图什么时候刷新。
 *
 * 以前是每次打开抖音都逐项比对版本（还要跨进程问模块 App），开销全花在「什么都没变」上。
 * 现在改成本地状态文件 `files/spark_res_state.json`：
 *
 * ```
 * { module: 模块版本, douyin: 抖音版本, at: 完成时间, complete: 是否拉全, days: 过期天数, onExpire: 过期刷新开关 }
 * ```
 *
 * 启动时只比几个本地值（模块版本、抖音版本、时间），**没有任何跨进程调用**；
 * 只有命中「模块更新 / 抖音更新 / 过期」时才去读设置、按设置里的开关决定要不要真同步。
 *
 * - `complete = false`（上次中断、模块 App 被停用等）→ 下次启动继续补，直到拉全
 * - 拉全之后，除非上面三个条件命中，否则一直跳过
 * - 开关关着导致「不刷新」时，也会把当前版本记进状态文件，避免每次启动都重新判定
 */
object ResourceSync {
    private const val FILE = "spark_res_state.json"

    /**
     * @param force App 主动请求、且它那边一张缩略图都没有（重装 / 清数据）时用：
     *   跳过「已完成就等等看」的本地判断，直接补一轮
     */
    fun maybeSync(
        context: Context,
        force: Boolean = false,
    ) {
        runCatching {
            val state = read(context)
            val moduleVersion = BuildConfig.VERSION_NAME
            val douyinVersion = douyinVersion(context)
            val now = System.currentTimeMillis()

            val complete = state?.optBoolean("complete", false) ?: false
            val moduleChanged = state != null && state.optString("module") != moduleVersion
            val douyinChanged = state != null && state.optString("douyin") != douyinVersion
            val expired = state != null && isExpired(state, now)
            // 拉全了、又没有任何变化 → 直接走人（不碰 provider）
            if (!force && complete && !moduleChanged && !douyinChanged && !expired) return

            val config = ConfigClient.read(context) ?: return
            val first = state == null
            val want =
                force ||
                    !complete ||
                    first ||
                    (moduleChanged && config.resRefreshOnModule) ||
                    (douyinChanged && config.resRefreshOnDouyin) ||
                    (expired && config.resRefreshOnExpire)
            if (!want) {
                // 开关关着：记下当前版本，下次启动不再为同一件事判定
                write(context, moduleVersion, douyinVersion, now, complete, config)
                return
            }

            val emojiOk = AutoSparkBridge.syncEmojiAssets(context)
            val sticker = StickerCache.sync(context)
            val catalogOk =
                if (sticker.usable.isEmpty()) {
                    true
                } else {
                    AutoSparkBridge.syncStickerCatalog(context, sticker.usable)
                }
            val done = emojiOk && sticker.ok && catalogOk
            if (!done) ModLog.w("资源同步未完成，下次启动继续（emoji=$emojiOk sticker=${sticker.ok} catalog=$catalogOk）")
            write(context, moduleVersion, douyinVersion, now, done, config)
        }.onFailure { ModLog.w("资源同步调度失败：$it") }
    }

    private fun isExpired(
        state: JSONObject,
        now: Long,
    ): Boolean {
        if (!state.optBoolean("onExpire")) return false
        val days = state.optInt("days", 0)
        if (days <= 0) return false
        return now - state.optLong("at") > days * 86_400_000L
    }

    private fun douyinVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()
            .orEmpty()

    private fun read(context: Context): JSONObject? =
        runCatching {
            val file = File(context.filesDir, FILE)
            if (!file.isFile) null else JSONObject(file.readText())
        }.getOrNull()

    private fun write(
        context: Context,
        moduleVersion: String,
        douyinVersion: String,
        at: Long,
        complete: Boolean,
        config: SparkConfig,
    ) {
        runCatching {
            val json =
                JSONObject()
                    .put("module", moduleVersion)
                    .put("douyin", douyinVersion)
                    .put("at", at)
                    .put("complete", complete)
                    .put("days", config.resRefreshDays)
                    .put("onExpire", config.resRefreshOnExpire)
            File(context.filesDir, FILE).writeText(json.toString())
        }
    }
}
