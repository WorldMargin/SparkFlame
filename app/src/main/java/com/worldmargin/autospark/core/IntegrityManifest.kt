package com.worldmargin.autospark.core

import android.util.Base64
import com.worldmargin.autospark.BuildConfig
import org.json.JSONObject
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile

/**
 * 校验构建期预生成、随 APK 一起打包的内容签名清单。
 *
 * 清单（assets/spark/integrity.json）由构建脚本用发布私钥对资源哈希签名后生成：
 * - 先用期望证书指纹确认这份清单出自我们的构建；
 * - 再用清单里的证书验签，防止有人自己生成一份清单塞进改包；
 * - 最后把清单里的哈希与运行时从 APK 重算的资源哈希比对。
 */
object IntegrityManifest {
    private const val ENTRY = "assets/spark/integrity.json"

    fun verify(
        apkPath: String,
        actualSha256: String,
    ): Boolean =
        runCatching {
            val text =
                ZipFile(apkPath).use { zip ->
                    zip.getEntry(ENTRY)?.let { entry ->
                        zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    }
                } ?: return false
            val json = JSONObject(text)
            val digest = json.getString("sha256")
            val sig = Base64.decode(json.getString("sig"), Base64.DEFAULT)
            val certDer = Base64.decode(json.getString("cert"), Base64.DEFAULT)

            if (digest != actualSha256) return false
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(certDer.inputStream())
            val certDigest =
                MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    .joinToString("") { "%02x".format(it) }
            if (BuildConfig.SIGNATURE_SHA256.split(',').none { expected ->
                    certDigest.equals(expected.trim(), ignoreCase = true)
                }
            ) {
                return false
            }

            val algorithm =
                if (cert.publicKey.algorithm.equals("EC", ignoreCase = true)) "SHA256withECDSA" else "SHA256withRSA"
            Signature.getInstance(algorithm).apply {
                initVerify(cert)
                update(digest.toByteArray(Charsets.UTF_8))
            }.verify(sig)
        }.getOrDefault(false)
}
