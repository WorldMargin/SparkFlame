package com.worldmargin.autospark.host

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 临时：反射互动表情相关类的字段与方法。 */
object LiteReflect {
    private val CLASSES = listOf(
        "com.ss.android.ugc.aweme.im.business.chat.feature.actionbar.schema.SendInteractiveEmojiRoute",
        "com.ss.android.ugc.aweme.im.business.chat.feature.emojihorizontal.autoemoji.AutoEmojiDataRepository",
        "com.ss.android.ugc.aweme.im.business.chat.feature.emojihorizontal.autoemoji.hotcache.AutoEmojiDatabase",
    )

    fun dump(context: Context) {
        val arr = JSONArray()
        CLASSES.forEach { name ->
            runCatching {
                val c = Class.forName(name, false, context.classLoader)
                val fields = JSONArray(c.declaredFields.map { it.name + ":" + it.type.simpleName })
                val methods = JSONArray(
                    c.declaredMethods.map {
                        it.name + "(" + it.parameterTypes.joinToString(",") { p -> p.simpleName } + "):" + it.returnType.simpleName
                    },
                )
                arr.put(JSONObject().put("class", name).put("fields", fields).put("methods", methods))
            }.onFailure { arr.put(JSONObject().put("class", name).put("error", it.toString())) }
        }
        runCatching { java.io.File(context.filesDir, "spark_lite_members.json").writeText(arr.toString()) }
    }
}
