package com.worldmargin.autospark.app.config

/**
 * 配置的按项导出 / 导入。
 *
 * 勾选粒度：模板、会话文案、宏、函数、标签**逐项**；快照与发送记录是**整类**开关。
 * 依赖关系（导出时自动带上，用户仍可手动取消）：
 * - 勾了一个模板 → 自动带上**它的父模板**（继承链一路向上）；
 * - 勾了模板或会话 → 自动带上**挂在上面的宏**（`MacroScript.appliesTo` 命中）；
 * - 勾了会话 → 自动带上它身上的**标签**（导入后标签归属不会丢）；
 * - 勾到的模板 / 宏脚本里出现 `ctx.functions.<名字>` → 自动带上对应函数。
 *
 * 函数用的是**文本扫描**（不是动态分析）：脚本里按名字调用就能认出来，
 * 拼接函数名之类写法认不出，属于"量力而行"。
 */
data class TransferSelection(
    val templates: Set<String> = emptySet(),
    val conversations: Set<String> = emptySet(),
    val macros: Set<String> = emptySet(),
    val functions: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val snapshots: Boolean = false,
    val sentDays: Boolean = false,
) {
    val isEmpty: Boolean
        get() =
            templates.isEmpty() && conversations.isEmpty() && macros.isEmpty() &&
                functions.isEmpty() && tags.isEmpty() && !snapshots && !sentDays

    fun toggle(
        kind: Kind,
        id: String,
        checked: Boolean,
    ): TransferSelection =
        when (kind) {
            Kind.TEMPLATE -> copy(templates = templates.let { if (checked) it + id else it - id })
            Kind.CONVERSATION -> copy(conversations = conversations.let { if (checked) it + id else it - id })
            Kind.MACRO -> copy(macros = macros.let { if (checked) it + id else it - id })
            Kind.FUNCTION -> copy(functions = functions.let { if (checked) it + id else it - id })
            Kind.TAG -> copy(tags = tags.let { if (checked) it + id else it - id })
        }

    fun has(
        kind: Kind,
        id: String,
    ): Boolean =
        when (kind) {
            Kind.TEMPLATE -> id in templates
            Kind.CONVERSATION -> id in conversations
            Kind.MACRO -> id in macros
            Kind.FUNCTION -> id in functions
            Kind.TAG -> id in tags
        }

    enum class Kind { TEMPLATE, CONVERSATION, MACRO, FUNCTION, TAG }
}

/**
 * 勾选一个条目后，把它的依赖一并带上（父模板 / 挂载的宏 / 用到的函数）。
 *
 * [excluded] 是用户**手动取消**过的条目：自动勾选不会再把它们加回来。
 */
fun SparkConfig.withDependencies(
    selection: TransferSelection,
    kind: TransferSelection.Kind,
    id: String,
    excluded: Set<String> = emptySet(),
): TransferSelection {
    if (kind == TransferSelection.Kind.FUNCTION || kind == TransferSelection.Kind.MACRO || kind == TransferSelection.Kind.TAG) {
        return selection
    }
    // 1) 父模板：继承链一路向上
    val parents = mutableSetOf<String>()
    var cursor = if (kind == TransferSelection.Kind.TEMPLATE) id else conversation(id)?.inheritFrom
    var guard = 0
    while (cursor != null && guard++ < MAX_PARENT_DEPTH) {
        if (group(cursor) != null) parents += cursor
        cursor = conversation(cursor)?.inheritFrom
    }
    // 2) 挂在这些模板 / 会话上的宏
    val targets = selection.templates + parents + selection.conversations + id
    val macroIds = macros.filter { it.appliesTo.any { target -> target in targets } }.map { it.id }.toSet()
    // 3) 这些脚本里按名字用到的函数
    val bodies =
        buildList {
            selection.templates.forEach { group(it)?.body?.let(::add) }
            parents.forEach { group(it)?.body?.let(::add) }
            selection.conversations.forEach { conversation(it)?.body?.let(::add) }
            conversation(id)?.body?.let(::add)
            (selection.macros + macroIds).forEach { macroId ->
                macros.firstOrNull { it.id == macroId }?.body?.let(::add)
            }
        }
    val functions = functionIdsIn(bodies)
    // 4) 会话身上的标签：导入后归属不丢
    val tagIds = if (kind == TransferSelection.Kind.CONVERSATION) tagsOf(id).map { it.id }.toSet() else emptySet()
    return selection.copy(
        templates = selection.templates + parents - excluded,
        macros = selection.macros + macroIds - excluded,
        functions = selection.functions + functions - excluded,
        tags = selection.tags + tagIds,
    )
}

/** 在脚本里找 `ctx.functions.<名字>`，映射回函数 id。 */
fun SparkConfig.functionIdsIn(bodies: Collection<String>): Set<String> {
    if (functions.isEmpty() || bodies.isEmpty()) return emptySet()
    val text = bodies.joinToString("\n")
    return functions.filter { it.name.isNotBlank() && text.contains("ctx.functions." + it.name) }.map { it.id }.toSet()
}

/** 按勾选裁出一份配置（导出用）。 */
fun SparkConfig.subsetOf(selection: TransferSelection): SparkConfig =
    copy(
        groups = groups.filter { it.id in selection.templates },
        conversations = conversations.filter { it.conversationId in selection.conversations },
        macros = macros.filter { it.id in selection.macros },
        functions = functions.filter { it.id in selection.functions },
        tags = tags.filter { it.id in selection.tags },
        conversationTags =
            if (selection.tags.isEmpty()) {
                emptyMap()
            } else {
                conversationTags
                    .filterKeys { it in selection.conversations }
                    .mapValues { (_, ids) -> ids.filter { it in selection.tags }.toSet() }
                    .filterValues { it.isNotEmpty() }
            },
        snapshots = if (selection.snapshots) snapshots else emptyList(),
        sentDays = if (selection.sentDays) sentDays else emptyMap(),
        forcedRenew = if (selection.conversations.isEmpty()) emptySet() else forcedRenew.filter { it in selection.conversations }.toSet(),
        forcedSkip = if (selection.conversations.isEmpty()) emptySet() else forcedSkip.filter { it in selection.conversations }.toSet(),
    )

/**
 * 把 [source] 里勾选的条目并进当前配置（同名 id 覆盖，其余保留）。
 *
 * 「导入」是**合并**而不是整份覆盖：没勾到的部分一个字都不动。
 */
fun SparkConfig.mergeFrom(
    source: SparkConfig,
    selection: TransferSelection,
): SparkConfig {
    val groupIds = selection.templates
    val conversationIds = selection.conversations
    val macroIds = selection.macros
    val functionIds = selection.functions
    val tagIds = selection.tags
    return copy(
        groups = groups.filterNot { it.id in groupIds } + source.groups.filter { it.id in groupIds },
        conversations = conversations.filterNot { it.conversationId in conversationIds } +
            source.conversations.filter { it.conversationId in conversationIds },
        macros = macros.filterNot { it.id in macroIds } + source.macros.filter { it.id in macroIds },
        functions = functions.filterNot { it.id in functionIds } + source.functions.filter { it.id in functionIds },
        snapshots =
            if (selection.snapshots) {
                snapshots.filterNot { snap -> source.snapshots.any { it.conversationId == snap.conversationId } } + source.snapshots
            } else {
                snapshots
            },
        sentDays = if (selection.sentDays) sentDays + source.sentDays else sentDays,
        tags = tags.filterNot { it.id in tagIds } + source.tags.filter { it.id in tagIds },
        conversationTags = mergeConversationTags(source, selection, tagIds),
        forcedRenew = if (selection.conversations.isEmpty()) forcedRenew else forcedRenew + source.forcedRenew.filter { it in conversationIds },
        forcedSkip = if (selection.conversations.isEmpty()) forcedSkip else forcedSkip + source.forcedSkip.filter { it in conversationIds },
    )
}

/** 从一份导入的配置里，按「它有什么」生成默认勾选（全勾）。 */
fun SparkConfig.fullSelection(): TransferSelection =
    TransferSelection(
        templates = groups.map { it.id }.toSet(),
        conversations = conversations.map { it.conversationId }.toSet(),
        macros = macros.map { it.id }.toSet(),
        functions = functions.map { it.id }.toSet(),
        tags = tags.map { it.id }.toSet(),
        snapshots = snapshots.isNotEmpty(),
        sentDays = sentDays.isNotEmpty(),
    )


/** 父模板继承链最多向上找这么多层（防止配置里出现环）。 */
private const val MAX_PARENT_DEPTH = 8


/** 导入时合并标签归属：只处理被勾选的会话，且只保留已勾选的标签。 */
private fun SparkConfig.mergeConversationTags(
    source: SparkConfig,
    selection: TransferSelection,
    tagIds: Set<String>,
): Map<String, Set<String>> {
    if (selection.conversations.isEmpty() || tagIds.isEmpty()) return conversationTags
    val next = conversationTags.toMutableMap()
    selection.conversations.forEach { conversationId ->
        val incoming = source.conversationTags[conversationId] ?: return@forEach
        val valid = incoming.filter { it in tagIds }.toSet()
        if (valid.isEmpty()) next.remove(conversationId) else next[conversationId] = valid
    }
    return next
}
