package com.worldmargin.autospark.features.autospark.model

import com.worldmargin.autospark.features.autospark.script.SparkFunctionSource
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** 同步策略：父模板改动后，向子模板推送的方式。 */
enum class SyncMode {
    /** 仅同步「未修改」的子模板（保留用户改过的）。 */
    UNMODIFIED,

    /** 仅同步「已修改」的子模板（用父覆盖本地改动）。 */
    MODIFIED,

    /** 全部同步。 */
    ALL,
}

/** 通用模板（可命名，多个会话可挂在其下共享同一份文案）。 */
/** 模板变量：名字 + 一条 JS 表达式（求值时可用 ctx，按定义顺序求值）。 */
data class TemplateVar(
    val name: String,
    val expr: String,
)

data class TemplateGroup(
    val id: String,
    val name: String,
    val body: String,
    val updatedAt: Long,
    val variables: List<TemplateVar> = emptyList(),
) {
    val bodyHash: String get() = Hash.of(body)
}

/**
 * 会话模板。
 *
 * - [inheritFrom] 指向「跟随谁」：某个通用模板 [TemplateGroup.id]、内置模板（`builtin:xxx`，
 *   含 `builtin:blank` = **空白模板**，即"不用模板、自己写"；克隆来的也归空白模板）。
 * - [parentHashAtSync] 记录上次同步时父模板 body 的哈希，
 *   用于判定本模板是否被本地改过（[modified]）。
 * - [selectedAt] 最后一次出现在「已选目标」里的时间；不在已选目标里的会话，
 *   从这一刻起算满 [SparkConfig.TEMPLATE_GRACE_MS] 就清掉它的模板与发送记录。
 * - [variables] 这个会话**自己的**变量：生效时排在父模板变量之后（同名覆盖父模板），
 *   所以能引用父模板的变量；父模板同步内容时不会动它。
 */
data class ConversationTemplate(
    val conversationId: String,
    val inheritFrom: String?,
    val body: String,
    val parentHashAtSync: String,
    val autoSync: Boolean,
    val updatedAt: Long,
    val selectedAt: Long = 0L,
    val variables: List<TemplateVar> = emptyList(),
) {
    /**
     * 已修改 = 自身内容与「上次同步到的父内容」不一致。
     *
     * 空白模板（`builtin:blank`）不算：它的内容本来就是空的，自己写的每一句都"不等于父模板"，
     * 但那是正常用法，不该标成「已本地修改」。
     */
    val modified: Boolean
        get() = inheritFrom != null && inheritFrom != SparkConfig.BUILTIN_BLANK && Hash.of(body) != parentHashAtSync
}

/**
 * 宏：一段独立的小脚本，可以挂到**多个模板**（模板 id）或**某个会话**（会话 id）上。
 *
 * 执行顺序是「自定义函数注入 → 模板脚本 → 宏（按顺序）」，所以宏能改写 `$spark_result$`。
 * 典型用法是节日倒计时这种临时性内容：用完把宏删掉，模板一个字都不用动。
 */
data class MacroScript(
    val id: String,
    val name: String,
    val body: String,
    /** 生效目标：通用模板 id 或会话 id（两者命名空间不重叠）。 */
    val appliesTo: Set<String> = emptySet(),
    val updatedAt: Long = 0L,
)

/**
 * 用户自定义函数：每次执行脚本前注入成 `ctx.functions.<name>`，模板里可直接调用。
 *
 * [params] 是逗号分隔的形参名；[body] 是函数体（要返回值就写 return）。
 */
data class UserFunction(
    val id: String,
    val name: String,
    val params: String,
    val body: String,
    val desc: String = "",
    val updatedAt: Long = 0L,
) {
    /** 形参名列表（去掉空项）。 */
    val paramList: List<String>
        get() = params.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 完整函数声明。
     *
     * 新数据 [body] 本身就是 `function 名字(形参) { … }`；早期版本只存「函数内部」，
     * 这里按名字与形参补一层包装，老配置打开后照样能编辑。
     */
    fun declaration(): String =
        if (SparkFunctionSource.looksLikeDeclaration(body)) {
            body.trim()
        } else {
            SparkFunctionSource.wrapLegacy(name, params, body)
        }
}

/**
 * 继承来源：内置模板、自定义模板，或某个会话的派生模板。
 *
 * [parentId] 是「跟随谁」：内置模板用 `builtin:xxx`、自定义模板用模板 id；
 * 为 null 只会出现在未升级的旧数据里（读入时会归到空白模板）。
 */
data class TemplateSource(
    val parentId: String?,
    val name: String,
    val body: String,
)

/**
 * 会话快照：由抖音进程写入，供管理界面展示。
 *
 * 同时也是「预览用的抖音侧变量缓存」：昵称/备注/头像/天数/状态等都缓存下来，
 * 预览时直接用缓存值，不会因为缺变量而报错。
 *
 * 火花字段语义（来自 `a:consecutive_chat_data.flame_infos` 的时间轴，取覆盖当前时刻的那段）：
 * [state] `1` = 今天已互动（火苗亮）、`2` = 今天还没互动（火苗灰，今天必须发）、
 * `3` = 待重燃（已断，靠连续互发 [recoverNeedDays] 天恢复，[recoverDays] 是已恢复进度）、
 * `-1` / [hasSpark] 为 false = 没有火苗。
 */
data class ConversationSnapshot(
    val conversationId: String,
    val name: String,
    val remark: String,
    val avatar: String,
    val peerUid: String,
    val days: Int,
    val state: Int,
    val stateText: String,
    val recoverDays: Int,
    val recoverNeedDays: Int,
    val hasSpark: Boolean,
    val needsRenewal: Boolean,
    val isGroup: Boolean,
    val updatedAt: Long,
) {
    val displayName: String get() = remark.ifBlank { name }
}

/** 全量配置：模板 + 发送记录 + 会话快照 + 手动归属。 */
/**
 * 沙盒变量：脚本通过 `ctx.sandbox.setvar` 写下的数据，**跳次运行保留**，值存成 JSON 文本。
 *
 * 归属（谁创建谁管理）：
 * - [owner] 为 `template:<会话模板 id>`：模板创建的，宏可以读、也可以改；
 * - [owner] 为 `macro:<宏 id>`：宏自己创建的，模板读不到也写不了，只有创建它的宏能删。
 */
data class SandboxVar(
    val owner: String,
    val key: String,
    val value: String,
)

data class SparkConfig(
    val enabled: Boolean = false,
    /**
     * 旧字段：早期版本用「默认模板」给没单独设置的会话兜底。
     * 现在默认是空白模板（没选模板就不发），这个字段只为兼容老配置保留。
     */
    val defaultGroupId: String? = null,
    val groups: List<TemplateGroup> = emptyList(),
    val conversations: List<ConversationTemplate> = emptyList(),
    val sentDays: Map<String, String> = emptyMap(),
    val snapshots: List<ConversationSnapshot> = emptyList(),
    /** 手动移入「需要续火花」的会话（覆盖抖音侧判定）。 */
    val forcedRenew: Set<String> = emptySet(),
    /** 手动移入「无需续火花」的会话（覆盖抖音侧判定）。 */
    val forcedSkip: Set<String> = emptySet(),
    /** 宏：可挂到多个模板 / 会话上的独立小脚本，在模板脚本之后执行。 */
    val macros: List<MacroScript> = emptyList(),
    /** 自定义函数：执行前注入成 `ctx.functions.<名字>`。 */
    val functions: List<UserFunction> = emptyList(),
    /** 沙盒变量：脚本 `ctx.sandbox.setvar` 写下的 JSON 数据，按创建者归属。 */
    val sandbox: List<SandboxVar> = emptyList(),
    /**
     * 宿主（抖音进程）最近一次运行的简报：快照刷新是否成功、失败原因、续火花结果。
     * 本机 LSPosed 日志不可用，这是唯一能让管理界面看到宿主侧状态的通道。
     */
    val hostNote: String = "",
    val hostNoteAt: Long = 0L,
    /** 宿主（抖音进程）最近一次响应指令的时间戳：界面用它判断抖音是否在运行。 */
    val hostAliveAt: Long = 0L,
    /** 脚本编辑器用 Monaco（WebView）；关闭则用原生 sora-editor。 */
    val useMonacoEditor: Boolean = true,
) {
    fun group(id: String?): TemplateGroup? = groups.firstOrNull { it.id == id }

    fun conversation(id: String): ConversationTemplate? = conversations.firstOrNull { it.conversationId == id }

    /** 是否列入「需要续火花」：抖音侧「今天还没互动」（火苗灰 / 待重燃），可被手动移动覆盖。 */
    fun needsRenewal(snapshot: ConversationSnapshot): Boolean =
        when (snapshot.conversationId) {
            in forcedRenew -> true
            in forcedSkip -> false
            else -> snapshot.hasSpark && snapshot.needsRenewal
        }

    /**
     * 解析某个会话最终生效的模板变量：**父模板的变量在前、会话自己的在后**，同名覆盖。
     *
     * 自己的变量排在后面有两个好处：求值时能引用父模板已经算出来的变量，
     * 同名时又能覆盖父模板的值（「就近优先」）。**表达式为空的会话变量等于没填** ——
     * 会用父模板的同名变量；父模板也没有就留一条空表达式，求值结果为 `null`
     * （见 `TemplateVarPrelude`）。
     */
    fun templateVarsFor(conversationId: String): List<TemplateVar> {
        val own = conversation(conversationId)
        return mergeVars(varsAlong(own?.inheritFrom), own?.variables.orEmpty())
    }

    /** 解析某个一级模板的变量（含父模板），供模板编辑页预览用。 */
    fun templateVarsForGroup(groupId: String): List<TemplateVar> = varsAlong(groupId)

    private fun varsAlong(startId: String?): List<TemplateVar> {
        val merged = LinkedHashMap<String, TemplateVar>()
        var cursor = startId
        var guard = 0
        while (cursor != null && guard++ < 32) {
            val current = group(cursor) ?: break
            current.variables.forEach { variable ->
                if (variable.name.isNotBlank() && variable.name !in merged) merged[variable.name] = variable
            }
            cursor = conversation(cursor)?.inheritFrom
        }
        return merged.values.toList()
    }

    /** 挂在指定模板下的会话（内置模板的 id 同样适用）。 */
    fun membersOf(templateId: String): List<ConversationTemplate> = conversations.filter { it.inheritFrom == templateId }

    /** 模板 id 的显示名：内置模板 / 自定义模板 / 某个会话的派生模板（未知返回 null）。 */
    fun templateName(id: String?): String? =
        when {
            id == null -> null
            id.startsWith(BUILTIN_PREFIX) -> BuiltinTemplates.byId(id.removePrefix(BUILTIN_PREFIX))?.name
            group(id) != null -> group(id)?.name
            conversation(id) != null -> conversationLabel(id) + " 的模板"
            else -> null
        }

    /** 模板 id 的内容：内置模板 / 自定义模板 / 某个会话的派生模板。 */
    fun templateBody(id: String?): String =
        when {
            id == null -> ""
            id.startsWith(BUILTIN_PREFIX) -> BuiltinTemplates.byId(id.removePrefix(BUILTIN_PREFIX))?.body.orEmpty()
            group(id) != null -> group(id)?.body.orEmpty()
            else -> conversation(id)?.body.orEmpty()
        }

    fun conversationLabel(conversationId: String): String =
        snapshots.firstOrNull { it.conversationId == conversationId }?.displayName?.takeIf { it.isNotBlank() }
            ?: conversationId

    /**
     * 可作为继承来源的清单：内置模板（**含空白模板** = 不用模板、自己写）+ 自定义模板 + 其它会话的派生模板。
     *
     * 「派生自某个派生模板」的语义（用户确认）：**挂到它的父模板上，但内容复制它的当前内容**。
     * 例：`A → B`，C 派生自 B ⇒ C 挂在 A 上（以后跟 A 同步），C 的内容 = B 的内容。
     * 若来源本身跟随的是空白模板（没有父内容），则 C 也跟随空白模板。另有一条**克隆**：同样复制内容，之后跟随空白模板。
     */
    fun sources(excludeConversationId: String? = null): List<TemplateSource> =
        BuiltinTemplates.ALL
            .map { TemplateSource(BUILTIN_PREFIX + it.id, "内置：" + it.name, it.body) } +
            groups.map { TemplateSource(it.id, "模板：" + it.name.ifBlank { "(未命名)" }, it.body) } +
            conversations
                .filter { it.conversationId != excludeConversationId && it.body.isNotBlank() }
                .flatMap { child ->
                    val label = conversationLabel(child.conversationId)
                    val parent = child.inheritFrom
                    val parentLabel = templateName(parent)?.let { "挂在 $it、复制它的内容" } ?: "空白模板"
                    listOf(
                        TemplateSource(parent, "派生：$label（$parentLabel）", child.body),
                        TemplateSource(null, "克隆：$label（复制内容，之后跟空白模板）", child.body),
                    )
                }

    /** 挂在某个模板（通用模板 id）或某个会话（会话 id）上的宏，按配置顺序。 */
    fun macrosFor(targetIds: Collection<String>): List<MacroScript> =
        macros.filter { macro -> macro.appliesTo.any { it in targetIds } }

    /**
     * 某个会话要执行的完整程序：**自定义函数注入 → 模板脚本 → 宏（按顺序）**。
     *
     * 宏的生效目标既可以是会话自己（会话模板不继承任何模板时也能用宏），
     * 也可以是它继承的通用模板；宏是动态取的，删掉宏不影响模板内容。
     */
    fun programFor(conversationId: String): String {
        val template = conversation(conversationId)
        val targets = buildList {
            add(conversationId)
            template?.inheritFrom?.let { add(it) }
        }
        return composeProgram(template?.body.orEmpty(), macrosFor(targets))
    }

    /**
     * 组装程序：函数注入 + [body] + [macros]（预览与发送共用同一份逻辑）。
     *
     * 每个宏之前插一句**结果归一化**：结果变量按列表设计（模板里用 `.push()`），
     * 万一某个模板写成 `$spark_result$ = "…"`，宏执行前会被自动包成单元素列表，
     * 于是宏里可以放心 `.push()`，不需要自己判断类型。
     */
    fun composeProgram(
        body: String,
        macros: List<MacroScript> = emptyList(),
    ): String =
        buildString {
            val prelude = functionsPrelude()
            if (prelude.isNotEmpty()) append(prelude).append('\n')
            append(body)
            macros.filter { it.body.isNotBlank() }.forEach { macro ->
                append('\n').append(RESULT_NORMALIZE).append('\n')
                // 沙盒归属切到这个宏自己（模板段拿不到 ctx.sandbox）
                append(SANDBOX_OWNER_CALL).append(MACRO_OWNER_PREFIX).append(macro.id).append("\");\n")
                append("// 宏：").append(macro.name).append('\n').append(macro.body)
            }
        }

    /** 沙盒归属切换：脚本里由引擎提供的内部函数。 */
    private val SANDBOX_OWNER_CALL: String = "__sandboxOwner(\""

    /** 沙盒里宏归属的前缀。 */
    private val MACRO_OWNER_PREFIX: String = "macro:"

    /** 自定义函数注入：函数声明本体 + 挂到 `ctx.functions.<名字>`。 */
    fun functionsPrelude(): String =
        functions
            .filter { it.name.isNotBlank() && it.body.isNotBlank() }
            .joinToString("\n") { fn ->
                val declaration = fn.declaration()
                "$declaration\nctx.functions.${fn.name} = ${fn.name};"
            }

    /** 当前在「已选目标」里的会话（单聊、按判定或手动归属）。 */
    fun selectedIds(): Set<String> =
        snapshots.filter { !it.isGroup && needsRenewal(it) }.map { it.conversationId }.toSet()

    /**
     * 数据保留策略（用户确认过的口径）：
     * - 在「已选目标」里：给它的会话模板打上「最后一次在已选目标的时间」；
     * - 不在「已选目标」里：从没被选中过 → 立即清；曾经选中过 → 从**最后一次被选中**起算，
     *   满 [TEMPLATE_GRACE_MS] 才清。
     * 清理范围：会话模板 + 该会话的发送记录。**快照不参与清理**（它就是两个列表的显示来源）。
     */
    fun pruneTemplates(now: Long): SparkConfig {
        val selected = selectedIds()
        val dropped = mutableSetOf<String>()
        var changed = false
        val kept =
            conversations.mapNotNull { template ->
                val id = template.conversationId
                if (id in selected) {
                    if (template.selectedAt == now) {
                        template
                    } else {
                        changed = true
                        template.copy(selectedAt = now)
                    }
                } else {
                    val lastSelected = template.selectedAt
                    if (lastSelected == 0L || now - lastSelected > TEMPLATE_GRACE_MS) {
                        changed = true
                        dropped += id
                        null
                    } else {
                        template
                    }
                }
            }
        if (!changed) return this
        // 发送记录跟着一起清：不在「已选目标」里、又没有模板兜着（说明已经过了宽限期或从没选过）的会话，
        // 它的「今日已发」记录也没有保留意义。
        val keptIds = kept.map { it.conversationId }.toSet()
        val keptDays = sentDays.filterKeys { it in selected || it in keptIds }
        return copy(conversations = kept, sentDays = keptDays)
    }

    companion object {
        /** 移出「已选目标」后，模板与发送记录的保留时长。 */
        const val TEMPLATE_GRACE_MS = 30L * 24 * 60 * 60 * 1000

        /** 内置模板作为继承来源时的 id 前缀（内置模板不在 groups 里，用前缀区分）。 */
        const val BUILTIN_PREFIX = "builtin:"

        /**
         * 空白模板的 id：= **不用模板、自己写**。
         *
         * 它和自定义模板在继承关系上没有区别（一样「跟随父模板」，一样出现在模板页的派生列表里），
         * 唯一的区别是父模板内容是空的 —— 所以会话照常发自己写的那段文案。
         */
        const val BUILTIN_BLANK = BUILTIN_PREFIX + "blank"

        /**
         * 合并两层变量：父模板的在前、下级（会话）的在后，同名覆盖。
         *
         * - 下级变量**表达式为空**等于没填：父模板有同名的就用父模板的；都没有就保留这条空表达式，
         *   求值结果为 `null`（`TemplateVarPrelude` 直接赋 null，不会因为语法错变成 undefined）。
         * - 名字为空的条目一律忽略。
         */
        fun mergeVars(
            parent: List<TemplateVar>,
            child: List<TemplateVar>,
        ): List<TemplateVar> {
            val merged = LinkedHashMap<String, TemplateVar>()
            parent.filter { it.name.isNotBlank() }.forEach { merged[it.name] = it }
            child.filter { it.name.isNotBlank() }.forEach { variable ->
                if (variable.expr.isBlank() && variable.name in merged) return@forEach
                merged.remove(variable.name)
                merged[variable.name] = variable
            }
            return merged.values.toList()
        }

        /** 宏执行前把结果变量归一成列表（模板里若写成字符串赋值，这里包成单元素列表）。 */
        private const val RESULT_NORMALIZE =
            "if (typeof \$spark_result\$ === 'string') { \$spark_result\$ = [\$spark_result\$]; }"
    }
}

/** 内容哈希（SHA-256 十六进制）。 */
object Hash {
    fun of(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** 模板操作：继承 / 重置 / 同步。均为纯函数，便于在 UI 与宿主两侧一致地推理。 */
object TemplateOps {
    private const val BLANK = "blank"

    /**
     * 新建/改写模板时，从父模板继承内容（[selectedAt] 沿用原有记录，别把保留时间重置掉）。
     *
     * [variables] 是这个会话自己的变量：换继承来源、重置内容都不该把它们弄丢，调用方传进来即可。
     */
    fun inheritFrom(
        group: TemplateGroup,
        conversationId: String,
        autoSync: Boolean,
        now: Long,
        selectedAt: Long = 0L,
        variables: List<TemplateVar> = emptyList(),
    ): ConversationTemplate =
        ConversationTemplate(
            conversationId = conversationId,
            inheritFrom = group.id,
            body = group.body,
            parentHashAtSync = group.bodyHash,
            autoSync = autoSync,
            updatedAt = now,
            selectedAt = selectedAt,
            variables = variables,
        )

    /**
     * 跟随**空白模板**（不用模板、自己写；克隆来的也归这一类）。
     *
     * 和跟随自定义模板没有区别（还是「跟随父模板」、还是出现在模板页的派生列表里），
     * 唯一区别是父模板内容是空的，所以 [body] 保留用户已经写好的文案 —— 这条会照常发送。
     */
    fun blank(
        conversationId: String,
        body: String = "",
        now: Long,
        selectedAt: Long = 0L,
        variables: List<TemplateVar> = emptyList(),
    ): ConversationTemplate =
        ConversationTemplate(
            conversationId = conversationId,
            inheritFrom = SparkConfig.BUILTIN_BLANK,
            body = body,
            parentHashAtSync = Hash.of(""),
            autoSync = false,
            updatedAt = now,
            selectedAt = selectedAt,
            variables = variables,
        )

    /** 重置：回到当前继承来源的内容（空白模板就清空；这个会话自己的变量保留）。 */
    fun reset(
        template: ConversationTemplate,
        group: TemplateGroup?,
        now: Long,
    ): ConversationTemplate {
        if (group == null || template.inheritFrom == SparkConfig.BUILTIN_BLANK) {
            return blank(template.conversationId, "", now, template.selectedAt, template.variables)
        }
        return inheritFrom(group, template.conversationId, template.autoSync, now, template.selectedAt, template.variables)
    }

    /**
     * 从某个[来源][source]派生：跟随它的父模板（`source.parentId`；**为 null 的克隆也算跟随空白模板**），
     * 内容取来源当前内容并记下哈希（下次父模板同步会以它为基准）。
     */
    fun fromSource(
        conversationId: String,
        source: TemplateSource,
        autoSync: Boolean,
        now: Long,
        selectedAt: Long = 0L,
        variables: List<TemplateVar> = emptyList(),
    ): ConversationTemplate {
        val parent = source.parentId ?: SparkConfig.BUILTIN_BLANK
        return ConversationTemplate(
            conversationId = conversationId,
            inheritFrom = parent,
            body = source.body,
            parentHashAtSync = Hash.of(source.body),
            autoSync = parent != SparkConfig.BUILTIN_BLANK && autoSync,
            updatedAt = now,
            selectedAt = selectedAt,
            variables = variables,
        )
    }

    /**
     * 父模板改动后，按策略把新内容**级联**推给子树（`A → B → C` 会一层层跟下来）。
     *
     * @return 更新后的会话模板列表
     */
    fun syncChildren(
        config: SparkConfig,
        parentId: String,
        parentBody: String,
        mode: SyncMode,
        now: Long,
    ): List<ConversationTemplate> = cascade(config, parentId, parentBody, now) { child, _ -> shouldSync(child, mode) }

    /** 自动同步：父模板改动后，勾了「自动同步」且未本地改过的子模板（含其子树）跟随。 */
    fun autoSyncChildren(
        config: SparkConfig,
        parentId: String,
        parentBody: String,
        now: Long,
    ): List<ConversationTemplate> = cascade(config, parentId, parentBody, now) { child, _ -> child.autoSync && !child.modified }

    /**
     * 只把新内容推给点名的这几个子模板（保存父模板时用户确认「覆盖这些」用）。
     *
     * 级联时只认点名集合，集合里的子模板会连同它的子树一起跟随。
     */
    fun syncSelected(
        config: SparkConfig,
        parentId: String,
        parentBody: String,
        selected: Set<String>,
        now: Long,
    ): List<ConversationTemplate> = cascade(config, parentId, parentBody, now) { child, _ -> child.conversationId in selected || child.inheritFrom in selected }

    /** 逐层把父内容推给子模板（[accept] 决定某个子模板这次要不要跟）。 */
    private fun cascade(
        config: SparkConfig,
        parentId: String,
        parentBody: String,
        now: Long,
        accept: (ConversationTemplate, Int) -> Boolean,
    ): List<ConversationTemplate> {
        val template = config.conversations
        val result = template.toMutableList()
        val position = template.withIndex().associate { (index, item) -> item.conversationId to index }
        val childrenOf = template.groupBy { it.inheritFrom }

        fun push(
            id: String,
            body: String,
            depth: Int,
        ) {
            if (depth > MAX_DEPTH) return
            childrenOf[id].orEmpty().forEach { child ->
                val index = position[child.conversationId] ?: return@forEach
                val current = result[index]
                if (!accept(current, depth)) return@forEach
                val next = applyParent(current, id, body, now)
                result[index] = next
                push(child.conversationId, next.body, depth + 1)
            }
        }
        push(parentId, parentBody, 0)
        return result
    }

    private fun shouldSync(
        child: ConversationTemplate,
        mode: SyncMode,
    ): Boolean =
        when (mode) {
            SyncMode.UNMODIFIED -> !child.modified
            SyncMode.MODIFIED -> child.modified
            SyncMode.ALL -> true
        }

    /** 用父模板当前内容刷新一个子模板（保留它的 autoSync 与 selectedAt）。 */
    private fun applyParent(
        child: ConversationTemplate,
        parentId: String,
        parentBody: String,
        now: Long,
    ): ConversationTemplate =
        child.copy(
            inheritFrom = parentId,
            body = parentBody,
            parentHashAtSync = Hash.of(parentBody),
            updatedAt = now,
        )

    /** 级联同步的深度上限（防配置里意外成环）。 */
    private const val MAX_DEPTH = 8
}

/** JSON 序列化（配置文件的读写格式）。 */
object ConfigCodec {
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DEFAULT_GROUP = "defaultGroupId"
    private const val KEY_GROUPS = "groups"
    private const val KEY_CONVERSATIONS = "conversations"
    private const val KEY_SENT_DAYS = "sentDays"
    private const val KEY_SNAPSHOTS = "snapshots"
    private const val KEY_FORCED_RENEW = "forcedRenew"
    private const val KEY_FORCED_SKIP = "forcedSkip"
    private const val KEY_HOST_NOTE = "hostNote"
    private const val KEY_HOST_NOTE_AT = "hostNoteAt"
    private const val KEY_HOST_ALIVE_AT = "hostAliveAt"
    private const val KEY_MACROS = "macros"
    private const val KEY_FUNCTIONS = "functions"
    private const val KEY_USE_MONACO = "useMonacoEditor"
    private const val KEY_SANDBOX = "sandbox"

    private const val INHERIT_BLANK = "blank"

    /** 模板变量数组 → JSON（配置文件与「变量编辑页」的来回传参共用同一份编码）。 */
    fun encodeVars(variables: List<TemplateVar>): JSONArray =
        JSONArray().apply {
            variables.forEach { variable -> put(JSONObject().put("name", variable.name).put("expr", variable.expr)) }
        }

    /** JSON → 模板变量数组（缺字段按空串处理）。 */
    fun decodeVars(array: JSONArray?): List<TemplateVar> =
        if (array == null) {
            emptyList()
        } else {
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { TemplateVar(it.optString("name"), it.optString("expr")) }
            }
        }

    /** 会话快照数组 → JSON（跨进程写快照与管理界面存盘共用同一份编码，字段不会漏）。 */
    fun encodeSnapshots(snapshots: List<ConversationSnapshot>): JSONArray {
        val array = JSONArray()
        snapshots.forEach { snapshot ->
            array.put(
                JSONObject()
                    .put("conversationId", snapshot.conversationId)
                    .put("name", snapshot.name)
                    .put("remark", snapshot.remark)
                    .put("avatar", snapshot.avatar)
                    .put("peerUid", snapshot.peerUid)
                    .put("days", snapshot.days)
                    .put("state", snapshot.state)
                    .put("stateText", snapshot.stateText)
                    .put("recoverDays", snapshot.recoverDays)
                    .put("recoverNeedDays", snapshot.recoverNeedDays)
                    .put("hasSpark", snapshot.hasSpark)
                    .put("needsRenewal", snapshot.needsRenewal)
                    .put("isGroup", snapshot.isGroup)
                    .put("updatedAt", snapshot.updatedAt),
            )
        }
        return array
    }

    fun encode(config: SparkConfig): String {
        val groups = JSONArray()
        config.groups.forEach { group ->
            groups.put(
                JSONObject()
                    .put("id", group.id)
                    .put("name", group.name)
                    .put("body", group.body)
                    .put("updatedAt", group.updatedAt)
                    .put("variables", encodeVars(group.variables)),
            )
        }

        val conversations = JSONArray()
        config.conversations.forEach { template ->
            conversations.put(
                JSONObject()
                    .put("conversationId", template.conversationId)
                    .put("inheritFrom", template.inheritFrom ?: INHERIT_BLANK)
                    .put("body", template.body)
                    .put("parentHashAtSync", template.parentHashAtSync)
                    .put("autoSync", template.autoSync)
                    .put("updatedAt", template.updatedAt)
                    .put("selectedAt", template.selectedAt)
                    .put("variables", encodeVars(template.variables)),
            )
        }

        val sentDays = JSONObject()
        config.sentDays.forEach { (conversationId, day) -> sentDays.put(conversationId, day) }

        val snapshots = encodeSnapshots(config.snapshots)

        val macros = JSONArray()
        config.macros.forEach { macro ->
            macros.put(
                JSONObject()
                    .put("id", macro.id)
                    .put("name", macro.name)
                    .put("body", macro.body)
                    .put("appliesTo", JSONArray(macro.appliesTo.toList()))
                    .put("updatedAt", macro.updatedAt),
            )
        }

        val sandbox = JSONArray()
        config.sandbox.forEach { item ->
            sandbox.put(JSONObject().put("owner", item.owner).put("key", item.key).put("value", item.value))
        }

        val functions = JSONArray()
        config.functions.forEach { fn ->
            functions.put(
                JSONObject()
                    .put("id", fn.id)
                    .put("name", fn.name)
                    .put("params", fn.params)
                    .put("body", fn.body)
                    .put("desc", fn.desc)
                    .put("updatedAt", fn.updatedAt),
            )
        }

        return JSONObject()
            .put(KEY_ENABLED, config.enabled)
            .put(KEY_GROUPS, groups)
            .put(KEY_CONVERSATIONS, conversations)
            .put(KEY_SENT_DAYS, sentDays)
            .put(KEY_SNAPSHOTS, snapshots)
            .put(KEY_FORCED_RENEW, JSONArray(config.forcedRenew.toList()))
            .put(KEY_FORCED_SKIP, JSONArray(config.forcedSkip.toList()))
            .put(KEY_MACROS, macros)
            .put(KEY_FUNCTIONS, functions)
            .put(KEY_SANDBOX, sandbox)
            .put(KEY_HOST_NOTE, config.hostNote)
            .put(KEY_HOST_NOTE_AT, config.hostNoteAt)
            .put(KEY_HOST_ALIVE_AT, config.hostAliveAt)
            .put(KEY_USE_MONACO, config.useMonacoEditor)
            .toString()
    }

    fun decode(text: String): SparkConfig {
        if (text.isBlank()) return SparkConfig()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return SparkConfig()

        val groups = mutableListOf<TemplateGroup>()
        root.optJSONArray(KEY_GROUPS)?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                groups +=
                    TemplateGroup(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        body = item.optString("body"),
                        updatedAt = item.optLong("updatedAt"),
                        variables = decodeVars(item.optJSONArray("variables")),
                    )
            }
        }

        val conversations = mutableListOf<ConversationTemplate>()
        root.optJSONArray(KEY_CONVERSATIONS)?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val parent = item.optString("inheritFrom", INHERIT_BLANK)
                conversations +=
                    ConversationTemplate(
                        conversationId = item.optString("conversationId"),
                        inheritFrom = if (parent.isEmpty() || parent == INHERIT_BLANK) SparkConfig.BUILTIN_BLANK else parent,
                        body = item.optString("body"),
                        parentHashAtSync = item.optString("parentHashAtSync"),
                        autoSync = item.optBoolean("autoSync"),
                        updatedAt = item.optLong("updatedAt"),
                        selectedAt = item.optLong("selectedAt"),
                        variables = decodeVars(item.optJSONArray("variables")),
                    )
            }
        }

        val sentDays = mutableMapOf<String, String>()
        root.optJSONObject(KEY_SENT_DAYS)?.let { obj ->
            obj.keys().forEach { key -> sentDays[key] = obj.optString(key) }
        }

        val snapshots = mutableListOf<ConversationSnapshot>()
        root.optJSONArray(KEY_SNAPSHOTS)?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                snapshots +=
                    ConversationSnapshot(
                        conversationId = item.optString("conversationId"),
                        name = item.optString("name"),
                        remark = item.optString("remark"),
                        avatar = item.optString("avatar"),
                        peerUid = item.optString("peerUid"),
                        days = item.optInt("days"),
                        state = item.optInt("state"),
                        stateText = item.optString("stateText"),
                        recoverDays = item.optInt("recoverDays"),
                        recoverNeedDays = item.optInt("recoverNeedDays"),
                        hasSpark = item.optBoolean("hasSpark"),
                        needsRenewal = item.optBoolean("needsRenewal"),
                        isGroup = item.optBoolean("isGroup"),
                        updatedAt = item.optLong("updatedAt"),
                    )
            }
        }

        val macros = mutableListOf<MacroScript>()
        root.optJSONArray(KEY_MACROS)?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                macros +=
                    MacroScript(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        body = item.optString("body"),
                        appliesTo = item.readIdSet("appliesTo"),
                        updatedAt = item.optLong("updatedAt"),
                    )
            }
        }

        val sandbox = mutableListOf<SandboxVar>()
        root.optJSONArray(KEY_SANDBOX)?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                sandbox +=
                    SandboxVar(
                        owner = item.optString("owner"),
                        key = item.optString("key"),
                        value = item.optString("value"),
                    )
            }
        }

        val functions = mutableListOf<UserFunction>()
        root.optJSONArray(KEY_FUNCTIONS)?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                functions +=
                    UserFunction(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        params = item.optString("params"),
                        body = item.optString("body"),
                        desc = item.optString("desc"),
                        updatedAt = item.optLong("updatedAt"),
                    )
            }
        }

        return SparkConfig(
            enabled = root.optBoolean(KEY_ENABLED),
            defaultGroupId = root.optString(KEY_DEFAULT_GROUP).takeIf { it.isNotEmpty() && it != "null" },
            groups = groups,
            conversations = conversations,
            sentDays = sentDays,
            snapshots = snapshots,
            forcedRenew = root.readIdSet(KEY_FORCED_RENEW),
            forcedSkip = root.readIdSet(KEY_FORCED_SKIP),
            macros = macros,
            functions = functions,
            sandbox = sandbox,
            hostNote = root.optString(KEY_HOST_NOTE),
            hostNoteAt = root.optLong(KEY_HOST_NOTE_AT),
            hostAliveAt = root.optLong(KEY_HOST_ALIVE_AT),
            useMonacoEditor = root.optBoolean(KEY_USE_MONACO, true),
        )
    }

    private fun JSONObject.readIdSet(key: String): Set<String> {
        val array = optJSONArray(key) ?: return emptySet()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { id -> id.isNotEmpty() } }.toSet()
    }
}
