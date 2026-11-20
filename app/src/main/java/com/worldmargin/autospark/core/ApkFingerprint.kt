package com.worldmargin.autospark.core

import java.security.MessageDigest

/**
 * 直接从 APK 字节里的签名块（APK Signature Scheme v2 / v3 / v3.1）读取证书，算 SHA-256。
 *
 * 输入是 [NativeFileReader] 用裸 syscall 读出的字节，不是文件路径 —— 全程不调用
 * File / PackageManager，xhook 那类 PLT/GOT hook 无从下手。
 *
 * 老包只有 v1（META-INF 下的 .RSA）签名时本类返回空，调用方按「校验不通过」处理，
 * 不为了兼容旧包退回可被 hook 的接口。
 */
object ApkFingerprint {
    private const val V2 = 0x7109871AL
    private const val V3 = 0xF05368C0L
    private const val V31 = 0x1B93AD61L

    private val MAGIC =
        byteArrayOf(
            0x41, 0x50, 0x4B, 0x20, 0x53, 0x69, 0x67, 0x20,
            0x42, 0x6C, 0x6F, 0x63, 0x6B, 0x20, 0x34, 0x32,
        ) // "APK Sig Block 42"

    /** 证书 DER 的 SHA-256 列表；输入为 APK 字节，解析失败返回空。 */
    fun sha256Certificates(data: ByteArray): List<ByteArray> =
        runCatching {
            val eocd = findEocd(data) ?: return@runCatching emptyList()
            val cd = u32(data, eocd + 16)
            if (cd < 24 || cd > data.size) return@runCatching emptyList()

            val blockSize = u64(data, cd.toInt() - 24)
            if (blockSize <= 0 || blockSize + 8 > cd) return@runCatching emptyList()
            if (!data.copyOfRange(cd.toInt() - 16, cd.toInt()).contentEquals(MAGIC)) {
                return@runCatching emptyList()
            }

            val start = cd.toInt() - blockSize.toInt() - 8
            val end = cd.toInt() - 24
            var pos = start + 8
            val out = mutableListOf<ByteArray>()
            while (pos + 8 <= end) {
                val pairLength = u64(data, pos).toInt()
                if (pairLength < 4 || pos + 8 + pairLength > end) break
                val id = u32(data, pos + 8)
                val value = data.copyOfRange(pos + 12, pos + 8 + pairLength)
                if (id == V2 || id == V3 || id == V31) {
                    out += certificatesOf(value)
                }
                pos += 8 + pairLength
            }
            out.distinctBy { it.joinToString("") { b -> "%02x".format(b) } }
        }.getOrDefault(emptyList())

    /** 从 v2/v3 的 signer block 里取第一个 signer 的证书 DER。 */
    private fun certificatesOf(value: ByteArray): List<ByteArray> {
        val (signers, _) = slice(value, 0) ?: return emptyList()
        var pos = 0
        while (pos < signers.size) {
            val (signer, next) = slice(signers, pos) ?: return emptyList()
            pos = next
            val (signed, _) = slice(signer, 0) ?: return emptyList()
            val (_, afterDigests) = slice(signed, 0) ?: return emptyList()
            val (certificates, _) = slice(signed, afterDigests) ?: return emptyList()
            var q = 0
            val out = mutableListOf<ByteArray>()
            while (q < certificates.size) {
                val (der, after) = slice(certificates, q) ?: return emptyList()
                q = after
                out += MessageDigest.getInstance("SHA-256").digest(der)
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    /** 长度前缀（uint32 LE）+ 内容。 */
    private fun slice(
        data: ByteArray,
        offset: Int,
    ): Pair<ByteArray, Int>? {
        if (offset < 0 || offset + 4 > data.size) return null
        val length = u32(data, offset).toInt()
        if (length < 0 || offset + 4 + length > data.size) return null
        return data.copyOfRange(offset + 4, offset + 4 + length) to offset + 4 + length
    }

    /** 从尾部找 ZIP End Of Central Directory（APK 不会用 ZIP64）。 */
    private fun findEocd(data: ByteArray): Int? {
        val floor = maxOf(0, data.size - 65_557)
        for (i in data.size - 22 downTo floor) {
            if (data[i] == 0x50.toByte() &&
                data[i + 1] == 0x4B.toByte() &&
                data[i + 2] == 0x05.toByte() &&
                data[i + 3] == 0x06.toByte()
            ) {
                return i
            }
        }
        return null
    }

    private fun u32(
        data: ByteArray,
        offset: Int,
    ): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    private fun u64(
        data: ByteArray,
        offset: Int,
    ): Long = u32(data, offset) or (u32(data, offset + 4) shl 32)
}
