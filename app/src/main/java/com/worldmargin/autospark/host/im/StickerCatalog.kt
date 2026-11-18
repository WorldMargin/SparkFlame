package com.worldmargin.autospark.host.im

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 互动表情目录：从 keva strategy_config 里解析 interactive_resource_config。 */
object StickerCatalog {
    data class Item(
        val name: String,
        val staticUrl: String,
        val animateUrl: String,
        val bizType: Int,
    )

    @Volatile
    private var cache: List<Item>? = null

    fun items(context: Context): List<Item> {
        cache?.let { return it }
        val loaded = runCatching { parse(context) }.getOrDefault(emptyList())
        cache = loaded
        return loaded
    }

    private fun parse(context: Context): List<Item> {
        val repos = java.io.File(context.filesDir, "keva/repo")
        val files =
            repos.listFiles()?.filter { it.isDirectory && it.name.startsWith("imbase_") }
                ?.map { java.io.File(it, "strategy_config.sgv") }
                ?.filter { it.isFile } ?: return emptyList()
        for (file in files) {
            val bytes = file.readBytes()
            val key = "\"interactive_resource_config\"".toByteArray()
            val start = indexOf(bytes, key)
            if (start < 0) continue
            var brace = -1
            for (i in start until bytes.size) {
                if (bytes[i] == '{'.code.toByte()) { brace = i; break }
            }
            if (brace < 0) continue
            var depth = 0
            var end = -1
            for (i in brace until bytes.size) {
                when (bytes[i]) {
                    '{'.code.toByte() -> depth++
                    '}'.code.toByte() -> {
                        depth--
                        if (depth == 0) { end = i + 1; break }
                    }
                }
            }
            if (end <= brace) continue
            val arr = JSONArray()
            val root = runCatching { JSONObject(String(bytes, brace, end - brace, Charsets.UTF_8)) }.getOrNull() ?: continue
            root.keys().forEach { group ->
                val node = root.optJSONObject(group) ?: return@forEach
                val list = node.optJSONArray("interactive_resources") ?: return@forEach
                for (i in 0 until list.length()) {
                    val it = list.optJSONObject(i) ?: continue
                    arr.put(it)
                }
            }
            val out = mutableListOf<Item>()
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val name = it.optString("display_name")
                val static = it.optString("static_url")
                if (name.isBlank() || static.isBlank()) continue
                out += Item(name, static, it.optString("animate_url"), it.optInt("biz_type", 1))
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
