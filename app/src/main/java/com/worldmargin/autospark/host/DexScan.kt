package com.worldmargin.autospark.host

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 临时：用 DexFile 枚举宿主类名，确认类可见性并筛贴纸相关类。 */
object DexScan {
    fun dump(context: Context) {
        val out = JSONObject()
        runCatching {
            val ai = context.applicationInfo
            val paths = mutableListOf<String>()
            ai.sourceDir?.let { paths.add(it) }
            ai.splitSourceDirs?.let { paths.addAll(it) }
            val names = mutableListOf<String>()
            paths.forEach { path ->
                runCatching {
                    val cls = Class.forName("dalvik.system.DexFile")
                    val df = cls.getDeclaredConstructor(String::class.java).newInstance(path)
                    val e = cls.getMethod("entries").invoke(df) as java.util.Enumeration<*>
                    while (e.hasMoreElements()) names.add(e.nextElement() as String)
                }.onFailure { out.put("err_path_${paths.indexOf(path)}", it.toString()) }
            }
            val hit = JSONArray()
            names.filter { n ->
                listOf("emoji", "sticker", "lite", "TextContent").any { n.contains(it, ignoreCase = true) }
            }.forEach { n -> hit.put(n) }
            out.put("ok", true).put("total", names.size).put("classes", hit)
        }.onFailure { out.put("ok", false).put("error", it.toString()) }
        runCatching { java.io.File(context.filesDir, "spark_lite_scan.json").writeText(out.toString()) }
    }
}
