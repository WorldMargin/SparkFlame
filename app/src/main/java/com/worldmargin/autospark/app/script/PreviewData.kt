package com.worldmargin.autospark.app.script

import com.worldmargin.autospark.app.config.SparkConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 预览用的上下文数据。
 *
 * 预览只算文案、不做任何修改，但**数据要齐**：
 * 预览的「本轮」只有正在预览的这一个会话（[getAllConversation] 返回单元素数组），
 * 模板 / 宏的信息表也一起带上，这样 `getConversation` / `getTemplate` / `getMacro` 在预览里也能用。
 */
object PreviewData {
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 为某个会话（或某个模板）造一份只读的沙盒环境。 */
    fun env(
        config: SparkConfig,
        conversationId: String?,
    ): SparkScriptEngine.SandboxEnv {
        val today = dayFormat.format(Date())
        val selectedIds = config.selectedIds()
        val conversations = JSONArray()
        var templateName = ""
        var parentName = ""
        var parentId = ""

        if (!conversationId.isNullOrBlank()) {
            val snapshot = config.snapshots.firstOrNull { it.conversationId == conversationId }
            val own = config.conversation(conversationId)
            parentId = own?.inheritFrom.orEmpty()
            val selected = conversationId in selectedIds
            val sentToday = config.sentDays[conversationId] == today
            val isGroup = snapshot?.isGroup ?: false
            val forced = conversationId in config.forcedRenew
            val autoCandidate =
                when {
                    conversationId in config.forcedSkip -> false
                    isGroup -> forced
                    else -> forced || selected
                }
            val tags = JSONArray()
            config.tagsOf(conversationId).forEach { tag ->
                tags.put(JSONObject().put("id", tag.id).put("name", tag.name))
            }
            conversations.put(
                JSONObject()
                    .put("id", conversationId)
                    .put("nickname", snapshot?.name.orEmpty())
                    .put("name", snapshot?.displayName.orEmpty())
                    .put("remark", snapshot?.remark.orEmpty())
                    .put("peerUid", snapshot?.peerUid.orEmpty())
                    .put("tags", tags)
                    .put("days", snapshot?.days ?: 0)
                    .put("state", snapshot?.state ?: -1)
                    .put("stateText", snapshot?.stateText.orEmpty())
                    .put("isGroup", isGroup)
                    .put("selected", selected)
                    .put("sentToday", sentToday)
                    // 预览的「本轮」里只有它自己，所以它就是本轮要发的那个
                    .put("manualSend", true)
                    .put("autoSend", autoCandidate && !sentToday)
                    .put("willSend", true),
            )
            templateName = config.conversationLabel(conversationId)
            parentName = config.templateName(parentId) ?: ""
        }

        val info = JSONObject()
            .put("conversations", conversations.let { list -> keyed(list, "id") })
            .put("templates", templates(config))
            .put("macros", macros(config))

        val templateInfo =
            JSONObject()
                .put(
                    "template",
                    JSONObject().put("id", conversationId.orEmpty()).put("name", templateName),
                )
                .put(
                    "parent",
                    JSONObject()
                        .put("id", parentId)
                        .put("name", parentName)
                        .put("childs", JSONArray()),
                )

        return SparkScriptEngine.SandboxEnv(
            data = config.sandbox.groupBy { it.owner },
            conversations = conversations.toString(),
            mountTargets = "[]",
            templateInfo = templateInfo.toString(),
            macroNames = macroNames(config),
            info = info.toString(),
            preview = true,
        )
    }

    /** 数组按某个字段转成 `{id: 对象}` 的表，供 `getXxx(id)` 查。 */
    private fun keyed(
        list: JSONArray,
        field: String,
    ): JSONObject {
        val table = JSONObject()
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val key = item.optString(field)
            if (key.isNotEmpty()) table.put(key, item)
        }
        return table
    }

    private fun templates(config: SparkConfig): JSONObject {
        val table = JSONObject()
        config.groups.forEach { group ->
            val childs = JSONArray()
            config.conversations.filter { it.inheritFrom == group.id }.forEach { child ->
                childs.put(JSONObject().put("id", child.conversationId).put("name", config.conversationLabel(child.conversationId)))
            }
            table.put(
                group.id,
                JSONObject()
                    .put("id", group.id)
                    .put("name", group.name)
                    .put("body", group.body)
                    .put("childs", childs)
                    .put("isBuiltin", false),
            )
        }
        return table
    }

    private fun macros(config: SparkConfig): JSONObject {
        val table = JSONObject()
        config.macros.forEach { macro ->
            val templ = JSONArray()
            val conv = JSONArray()
            (macro.appliesTo + macro.templateOnly).forEach { target ->
                if (target.contains(":")) conv.put(target) else templ.put(target)
            }
            table.put(
                macro.id,
                JSONObject()
                    .put("id", macro.id)
                    .put("name", macro.name)
                    .put("body", macro.body)
                    .put("templates", templ)
                    .put("conversations", conv),
            )
        }
        return table
    }

    private fun macroNames(config: SparkConfig): String {
        val names = JSONObject()
        config.macros.forEach { macro -> names.put(macro.id, macro.name) }
        return names.toString()
    }
}
