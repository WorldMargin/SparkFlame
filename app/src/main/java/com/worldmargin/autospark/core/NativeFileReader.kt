package com.worldmargin.autospark.core

/**
 * 用裸 syscall 读取 APK 文件的字节，绕开 libc 的 open/read ——
 * xhook 一类的 PLT/GOT hook 只改函数跳转表，挡不住直接 `svc` 陷内核。
 *
 * 这里只负责「把文件读成字节」，签名块 / ZIP / 清单的解析仍由 Kotlin 在内存里完成，
 * 全程不再调用 File / ZipFile 等会重新打开文件的 API。
 *
 * native 库加载失败时 [readAll] 返回 null，调用方按校验不通过处理，绝不降级到 File 读取
 * （降级等于把后门留回来）。
 */
object NativeFileReader {
    private val available: Boolean =
        runCatching { System.loadLibrary("sparkfile") }
            .onFailure { ModLog.w("native 文件读取库加载失败：${it.message}") }
            .isSuccess

    /** 读取整个文件；失败返回 null。 */
    fun readAll(path: String): ByteArray? {
        if (!available) return null
        return runCatching { r0(path) }
            .onFailure { ModLog.w("native 读取 APK 失败：${it.message}") }
            .getOrNull()
    }

    // 方法名固定为 r0，与 jni/spark_file.c 的 RegisterNatives 映射一致；
    // 类名/成员名由 proguard-rules.pro 固定为 a.a / r0，so 里看不到明文入口。
    private external fun r0(path: String): ByteArray?
}
