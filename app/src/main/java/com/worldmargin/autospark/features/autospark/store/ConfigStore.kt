package com.worldmargin.autospark.features.autospark.store

import android.content.Context
import com.worldmargin.autospark.features.autospark.model.ConfigCodec
import com.worldmargin.autospark.features.autospark.model.SparkConfig
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 配置文件存储：单一来源，存在模块 App 的私有目录。
 *
 * 仅由模块进程（管理界面 / [com.worldmargin.autospark.features.autospark.provider.ConfigProvider]）访问；
 * 抖音进程通过 ContentProvider 跨进程读写。
 *
 * 内存缓存按**文件修改时间**失效：配置文件可能被外部改掉（备份还原、root 下直接改、
 * 调试时用 adb 写入），只看进程内缓存会读到旧内容、再把旧内容写回去。
 */
object ConfigStore {
    private const val FILE_NAME = "spark_config.json"

    private class Cached(
        val config: SparkConfig,
        val stamp: Long,
        val size: Long,
    )

    private val cache = AtomicReference<Cached?>(null)

    @Synchronized
    fun load(context: Context): SparkConfig {
        val file = File(context.filesDir, FILE_NAME)
        val stamp = if (file.exists()) file.lastModified() else NO_FILE
        val size = if (file.exists()) file.length() else NO_FILE
        val cached = cache.get()
        // 修改时间精度只有秒级：同一秒内的外部改动要靠文件长度一起判断。
        if (cached != null && cached.stamp == stamp && cached.size == size) return cached.config
        if (file.exists()) {
            val decoded = runCatching { ConfigCodec.decode(file.readText(Charsets.UTF_8)) }.getOrNull()
            if (decoded != null) {
                cache.set(Cached(decoded, stamp, size))
                return decoded
            }
            // 读失败（外部写入半截文件等）时**不要**缓存空配置：否则下一次写回就把配置清空了。
            cached?.let { return it.config }
            return SparkConfig()
        }
        val fresh = SparkConfig()
        cache.set(Cached(fresh, stamp, size))
        return fresh
    }

    @Synchronized
    fun save(
        context: Context,
        config: SparkConfig,
    ) {
        val file = File(context.filesDir, FILE_NAME)
        // 原子写：先写临时文件再改名，避免读到半截内容
        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        temp.writeText(ConfigCodec.encode(config), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
            temp.delete()
        }
        cache.set(Cached(config, file.lastModified(), file.length()))
    }

    /** 读改写，返回新配置。 */
    @Synchronized
    fun update(
        context: Context,
        block: (SparkConfig) -> SparkConfig,
    ): SparkConfig {
        val next = block(load(context))
        save(context, next)
        return next
    }

    /** 文件不存在时的缓存标记。 */
    private const val NO_FILE = -1L
}
