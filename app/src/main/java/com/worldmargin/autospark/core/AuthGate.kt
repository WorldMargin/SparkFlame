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

    /** 签名校验结论（进程内不变：重装 / 更新会让进程重建）。 */
    @Volatile
    private var verdict = UNKNOWN

    /** 模块 APK 路径（宿主进程里由框架给出，用于直接解析 APK 签名，绕开包可见性限制）。 */
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
     * 模块 APK 的签名是否为我们签发。
     *
     * 在模块进程里查自己；在抖音进程里两条路都试：
     * 1. `getPackageInfo`（前提是宿主有包可见性，抖音有 `QUERY_ALL_PACKAGES`，但不能只靠它）；
     * 2. 框架给的模块 APK 路径 + `getPackageArchiveInfo` 直接解析文件（不依赖可见性）。
     *
     * 任一路径取到的证书里有期望值即通过；取不到任何证书（框架 / 权限异常）时放行并留日志 ——
     * 这时连模块本身都读不到，把用户挡在门外没有意义；能读到但摘要不符才算改包。
     * 未注入期望指纹（没有签名配置的开发构建）时同样跳过。
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
        val accepted =
            if (context.packageName == MODULE_PACKAGE) {
                SigStore.accepted(context)
            } else {
                ConfigClient.sigAccepted(context) ?: return false
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
        val wanted = expected
        if (wanted.isEmpty()) {
            ModLog.w("未配置期望签名（无签名配置的开发构建），跳过签名校验")
            return true
        }
        val actual = certDigests(context)
        if (actual.isEmpty()) {
            ModLog.w("读不到模块签名（框架或包可见性异常），本次跳过校验")
            return true
        }
        return actual.any { digest -> wanted.any { it.contentEquals(digest) } }
    }

    /** 取模块的全部签名证书摘要：已安装包 + 框架给出的 APK 文件（哪条通算哪条）。 */
    private fun certDigests(context: Context): List<ByteArray> {
        val manager = context.packageManager
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
        val digests = mutableListOf<ByteArray>()
        runCatching { digestsOf(manager.getPackageInfo(MODULE_PACKAGE, flags)) }
            .getOrNull()
            ?.let { digests += it }
        moduleApkPath()?.let { path ->
            runCatching { digestsOf(manager.getPackageArchiveInfo(path, flags)) }
                .getOrNull()
                ?.let { digests += it }
        }
        return digests
    }

    /** 框架（LSPosed）加载的模块 APK 路径；模块进程里拿不到（也不需要）。 */
    private fun moduleApkPath(): String? {
        apkPath?.let { return it }
        val path =
            runCatching { HostRuntime.module?.moduleApplicationInfo?.sourceDir }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        apkPath = path
        return path
    }

    /** PackageInfo → 证书 SHA-256 列表（取不到签名返回 null）。 */
    private fun digestsOf(info: android.content.pm.PackageInfo?): List<ByteArray>? {
        if (info == null) return null
        val certificates: Array<out android.content.pm.Signature> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signing = info.signingInfo ?: return null
                if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                info.signatures ?: return null
            }
        return certificates.mapNotNull { certificate ->
            runCatching { MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()) }.getOrNull()
        }
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
