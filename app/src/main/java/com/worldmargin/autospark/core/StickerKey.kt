package com.worldmargin.autospark.core

import java.security.MessageDigest

/**
 * 贴纸图片的「稳定 key」。
 *
 * 抖音下发的贴纸地址每次解析都可能换 CDN 域名与签名参数（`p26-sign` → `p5-ex-gddgtc-sign`、
 * `x-expires`、`l=` 都会变），只有 `/obj/` 后面的对象名是稳定的：
 *
 * ```
 * https://p26-sign.douyinpic.com/obj/im-resource/1687263281313-ts-e7bb...?x-expires=...
 * └────────────────────────── 会变 ──────────────────────────┘└─ 稳定 ─┘
 * ```
 *
 * 缓存 key / 版本号都基于对象名，这样「地址轮换」不会被当成「资源变了」而重下一遍。
 * 宿主（下载、推图）与模块（读图）必须用同一套规则，所以放在 core 里共用。
 */
object StickerKey {
    /** 对象名：去掉协议域名与查询参数，保留 `/obj/` 之后的部分（没有 `/obj/` 就用整个路径）。 */
    fun of(url: String): String {
        val noQuery = url.substringBefore('?').substringBefore('#')
        val marker = "/obj/"
        val index = noQuery.indexOf(marker)
        return if (index >= 0) noQuery.substring(index + marker.length) else noQuery
    }

    /** 缓存文件名用的 md5（32 位小写十六进制）。 */
    fun md5(url: String): String =
        MessageDigest
            .getInstance("MD5")
            .digest(of(url).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
