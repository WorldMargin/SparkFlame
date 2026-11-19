package com.worldmargin.autospark.core

import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 资源内容指纹：把 APK 里「原样打包」的条目按固定顺序哈希，和构建期注入的
 * `BuildConfig.CONTENT_SHA256` 比对。
 *
 * 这是签名之外的第二层校验：即使有人用 hook 伪造了包管理器返回值，或者把资源
 * （编辑器离线文件、内置手册）替换掉，也过不了这一关。
 * 参与计算的条目必须与 app/build.gradle.kts 的 contentEntries 完全一致。
 */
object ContentFingerprint {
    // 与 app/build.gradle.kts 的 contentEntries 一致。xposed 清单不算：构建时会按混淆结果改写。
    private val PREFIXES =
        listOf(
            "assets/cm6/",
            "assets/docs/manual.md",
        )

    fun sha256(apkPath: String): String =
        runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            ZipFile(apkPath).use { zip ->
                zip.entries()
                    .toList()
                    .filter { entry -> !entry.isDirectory && PREFIXES.any { entry.name.startsWith(it) } }
                    .sortedBy { it.name }
                    .forEach { entry ->
                        md.update(entry.name.toByteArray(Charsets.UTF_8))
                        zip.getInputStream(entry).use { md.update(it.readBytes()) }
                    }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
}
