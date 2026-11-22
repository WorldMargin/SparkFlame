package com.worldmargin.autospark.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.worldmargin.autospark.BuildConfig
import com.worldmargin.autospark.app.config.ConfigClient
import com.worldmargin.autospark.app.config.SigStore
import java.security.MessageDigest

/**
 * 签名闸门：确认「跑起来的这份代码」是不是我们自己签发的，不是就必须等用户在 App 里授权。
 *
 * 背景：模块被下载站搬运后，经常被二次打包（塞广告 / 改文案 / 改上报）。
 * 这里校验模块 APK 的签名证书摘要，和构建时注入的期望值（[BuildConfig.SIGNATURE_SHA256]）比对：
 *
 * - 一致 → 正常放行；
 * - 不一致 → [allowed] 返回 false，宿主侧（抖音进程）的一切功能都不启动，
 *   直到用户在模块 App 的提示弹窗里点「无视风险」（授权位存在 App 私有 SharedPreferences，
 *   由 [SigStore] 写入，宿主通过 ContentProvider 读取）。
 *
 * 包名、维护者、仓库、期望指纹全部来自 `gradle.properties`（构建时注入 BuildConfig），
 * fork 之后只改配置文件即可，不用动代码。
 */
object AuthGate {
    /** 模块自身的 applicationId（构建期注入；广播、跨包查询都用它）。 */
    val MODULE_PACKAGE: String get() = BuildConfig.MODULE_PACKAGE

    /** 宿主包名（构建期注入；授权变更广播只发给它）。 */
    val HOST_PACKAGE: String get() = BuildConfig.HOST_PACKAGE

    /** 维护者与仓库：改包提示里展示，也用作「去仓库下载」的跳转目标。 */
    val MAINTAINER: String get() = BuildConfig.MAINTAINER
    val REPO_URL: String get() = BuildConfig.REPO_URL

    /** 展示用的仓库地址（去掉协议头）。 */
    val REPO_LABEL: String get() = BuildConfig.REPO_URL.removePrefix("https://").removePrefix("http://")

    private const val UNKNOWN = 0
    private const val MATCH = 1
    private const val MISMATCH = -1

    /** 期望的证书摘要；未配置（开发构建）时为空列表。 */
    private val expected: List<ByteArray> by lazy {
        BuildConfig.SIGNATURE_SHA256
            .split(',')
            .mapNotNull { hexToBytes(it.trim()) }
            .filter { it.size == DIGEST_SIZE }
    }

    /** 最近一次签名校验的各阶段耗时字符串（冷启动诊断用，由 ModuleMain 统一写宿主简报）。 */
    @Volatile
    var lastTiming: String = ""
        private set

    /** 签名校验结论（进程内不变：重装 / 更新会让进程重建）。 */
    @Volatile
    private var verdict = UNKNOWN

    /** 模块 APK 路径（宿主进程里由框架给出，用于直接读文件算签名，不经过 PackageManager）。 */
    @Volatile
    private var apkPath: String? = null

    /** 用户是否已「无视风险」授权（宿主侧跨进程读一次后缓存，授权是不可逆的一次性决定）。 */
    @Volatile
    private var acceptCached: Boolean? = null

    /** 尚未授权时的提示文案：标题 + 正文（App 弹窗与设置页警示卡共用）。 */
    const val WARN_TITLE = "签名校验出错"

    val WARN_BODY: List<String> =
        listOf(
            "您可能是下载了由第三方改包和分发的版本。",
            "出于安全考虑，在您授权之前，本模块不会生效。",
            "如果您确实有改包的需求，我们建议您到模块仓库下载源码后更改而非直接改包。",
        )

    /**
     * 「当前正在运行的这份代码」是不是我们签发的。
     *
     * 只看实际运行的 APK，绝不查构建期写死的 [MODULE_PACKAGE]：
     * 只改 manifest 包名再签名的改包，DEX 里仍是正版包名，旧实现会查到设备上装的正版，
     * 拿到正版签名就放行 —— 实测这种改包确实绕过了闸门。
     *
     * - 模块进程：读自己的 `applicationInfo.sourceDir`；
     * - 宿主进程：读框架给出的模块 APK 路径（LSPosed 实际加载的那份）。
     *
     * 签名从 APK 文件的 v2/v3 签名块直接解析（见 [ApkFingerprint]），**不调用**
     * `getPackageInfo` / `getPackageArchiveInfo` 取签名 —— 那些返回值能被 Xposed hook 成伪造值。
     * 所有签名者都必须在期望列表里；指纹读不出来按**不通过**处理，不打开后门。
     * 只有没注入期望指纹（无签名配置的开发构建）才跳过。
     */
    fun signatureOk(context: Context): Boolean {
        val cached = verdict
        if (cached != UNKNOWN) return cached == MATCH
        val ok = decide(context)
        verdict = if (ok) MATCH else MISMATCH
        return ok
    }

    /** 只读已算出的结论（未知按 false，供不能做重活的调用点用）。 */
    fun signatureOkCached(): Boolean = verdict == MATCH

    /**
     * 用户是否点过「无视风险」。
     *
     * 模块进程直接读自己的 SharedPreferences；宿主进程走 ContentProvider（读不到按未授权）。
     */
    fun isAccepted(context: Context): Boolean {
        acceptCached?.let { return it }
        // 按「是不是宿主进程」判断，不能用 MODULE_PACKAGE：
        // 改包只换 manifest 包名时，它的 DEX 里仍是正版包名，会把授权位读串。
        val accepted =
            if (isHost(context)) {
                ConfigClient.sigAccepted(context) ?: return false
            } else {
                SigStore.accepted(context)
            }
        acceptCached = accepted
        return accepted
    }

    /** 是否允许跑宿主侧功能：签名通过，或签名不符但用户已明确授权。 */
    fun allowed(context: Context?): Boolean {
        if (context == null) return false
        return signatureOk(context) || isAccepted(context)
    }

    /** 只读缓存判断（不触发跨进程查询）：给主线程 / 高频路径用。 */
    fun allowedCached(): Boolean = verdict == MATCH || acceptCached == true

    /** 授权状态变了（用户点了「无视风险」）时清掉缓存，让下一次判断重新读。 */
    fun invalidate() {
        verdict = UNKNOWN
        acceptCached = null
    }

    // ------------------------------------------------------------ 内部

    private fun decide(context: Context): Boolean {
        // 冷启动诊断：各阶段耗时（毫秒）。仅打点，不改变任何校验逻辑。
        var t = System.currentTimeMillis()
        val timings = StringBuilder()
        fun mark(label: String) {
            val now = System.currentTimeMillis()
            timings.append(" $label=${now - t}ms")
            t = now
        }
        val wanted = expected
        if (wanted.isEmpty()) {
            ModLog.w("未配置期望签名（无签名配置的开发构建），跳过签名校验")
            return true
        }
        val path = apkPathOf(context)
        mark("路径")
        // 用裸 syscall 读一次字节，后面三层都基于这份字节在内存里解析；
        // 全程不调用 File / ZipFile，xhook 的 PLT/GOT hook 拦不到。
        val bytes = path?.let { NativeFileReader.readAll(it) }
        mark("读包")
        if (bytes == null || bytes.isEmpty()) {
            ModLog.w("无法读取当前 APK 字节，按校验不通过处理")
            return false
        }
        val actual = ApkFingerprint.sha256Certificates(bytes)
        mark("签名解析")
        if (actual.isEmpty()) {
            // 有期望指纹却读不到实际指纹时不放行：放行就等于给 hook 留后门
            ModLog.w("无法直接解析当前 APK 的签名，按校验不通过处理")
            return false
        }
        // PackageManager 只做快速交叉验证：返回值能被 hook，所以不作为判定依据，
        // 但一旦它给出的签名与「直接读文件」的结果不一致，就说明有人动过手脚。
        val viaManager = path?.let { packageManagerDigests(context, it) }.orEmpty()
        mark("PM交叉")
        if (viaManager.any { pm -> actual.none { it.contentEquals(pm) } }) {
            ModLog.w("包管理器签名与 APK 文件不一致（可能被 hook），按校验不通过处理")
            return false
        }

        // 没有发布签名配置的本地开发构建不会生成清单，整层跳过
        if (BuildConfig.INTEGRITY_ENABLED && BuildConfig.CONTENT_SHA256.isNotBlank()) {
            val content = ContentFingerprint.sha256(bytes)
            mark("内容哈希")
            if (content != BuildConfig.CONTENT_SHA256) {
                ModLog.w("资源内容哈希不符，按校验不通过处理")
                return false
            }
            if (!IntegrityManifest.verify(bytes, content)) {
                ModLog.w("内容签名清单校验失败，按校验不通过处理")
                return false
            }
            mark("清单验签")
        }
        val timingLine = "签名校验耗时:$timings"
        lastTiming = timingLine
        // 只进 ModLog（LSPosed 日志），不写 HostTrace——后者是同步 IPC，
        // 放在签名同步路径里会反过来拖慢冷启动，污染要测量的耗时本身。
        // 宿主简报由 ModuleMain 在 attach 完成后连同总耗时一起写一次。
        ModLog.i(timingLine)
        return actual.all { digest -> wanted.any { it.contentEquals(digest) } }
    }

    /** 包管理器看到的当前签名者摘要；只用于与直接解析结果交叉比对。 */
    private fun packageManagerDigests(
        context: Context,
        path: String,
    ): List<ByteArray> {
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
        val info =
            runCatching {
                val pkg = if (isHost(context)) MODULE_PACKAGE else context.packageName
                context.packageManager.getPackageInfo(pkg, flags)
            }.getOrNull()
                ?: runCatching { context.packageManager.getPackageArchiveInfo(path, flags) }.getOrNull()
                ?: return emptyList()
        val certificates =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signing = info.signingInfo ?: return emptyList()
                if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory.take(1).toTypedArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures ?: return emptyList()
            }
        return certificates.mapNotNull { cert ->
            runCatching { MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()) }.getOrNull()
        }
    }

    private fun isHost(context: Context): Boolean = context.packageName == HOST_PACKAGE

    /**
     * 定位「当前这份代码」的 APK 文件。路径本身只用来读文件：
     * 签名与资源哈希都从字节算，不碰 PackageManager 的签名接口
     * （`getPackageInfo` 返回的 Signature[] 能被 Xposed hook 成任意值）。
     *
     * 模块进程用 `applicationInfo.sourceDir`，宿主进程用框架给出的模块 APK 路径；
     * 拿不到时才按包名换一个路径。
     */
    private fun apkPathOf(context: Context): String? {
        var path = if (isHost(context)) moduleApkPath() else context.applicationInfo?.sourceDir
        if (path.isNullOrBlank()) {
            val pkg = if (isHost(context)) MODULE_PACKAGE else context.packageName
            path = runCatching { context.packageManager.getPackageInfo(pkg, 0) }
                .getOrNull()
                ?.applicationInfo
                ?.sourceDir
        }
        return path?.takeIf { it.isNotBlank() }
    }

    /** 框架（LSPosed）加载的模块 APK 路径；仅在宿主进程用。 */
    private fun moduleApkPath(): String? {
        apkPath?.let { return it }
        val path =
            runCatching { HostRuntime.module?.moduleApplicationInfo?.sourceDir }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        apkPath = path
        return path
    }

    /** hex 字符串 → 字节数组（长度不对 / 非法字符返回 null）。 */
    private fun hexToBytes(text: String): ByteArray? {
        if (text.isEmpty() || text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (index in out.indices) {
            val high = Character.digit(text[index * 2], 16)
            val low = Character.digit(text[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private const val DIGEST_SIZE = 32
}
