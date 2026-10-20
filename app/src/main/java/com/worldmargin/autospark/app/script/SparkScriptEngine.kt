package com.worldmargin.autospark.app.script

import com.worldmargin.autospark.app.config.SandboxVar
import com.worldmargin.autospark.core.ModLog
import org.json.JSONObject
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/** 脚本执行超时（多半是死循环）。 */
class ScriptTimeoutException : RuntimeException("脚本执行超时（可能存在死循环）")

/**
 * 脚本执行完了，但 `ctx.result` 是空的：这一条不发（按「跳过」处理，不算失败）。
 *
 * 把已经注册的回调一起带出去，宿主还能给脚本回一个 `Skipped`。
 */
class EmptyResultException(
    message: String,
    val callbacks: List<Function>,
) : RuntimeException(message)

/**
 * 动态文案脚本引擎（Rhino JS 沙盒）。
 *
 * 宿主把上下文挂到沙盒里的 `ctx` 上，脚本把要发送的文本 push 进 `ctx.result`
 *（老写法 [RESULT_VAR] `$spark_result$` 仍然可用，两者是同一个数组），
 * 引擎只读这个列表（不会去取「最后一个表达式」的值）。
 *
 * `ctx` 下面按来源分命名空间：
 * - [BUILTIN_OBJ]：内置能力 —— 日期时间等内置变量与 [FUNCTIONS]（`ctx.builtin.pick(...)`）
 * - [IO_OBJ]：宿主数据 —— 从抖音取到的会话信息（`ctx.io.nickname`、`ctx.io.days`…）
 * - [MACRO_OBJ]：沙盒变量（跨次运行的 JSON 存储，见 [SandboxEnv]）
 * - [SYSTEM_OBJ]：系统接口（会话列表、宏挂载）
 * - [FUNCTIONS_OBJ]：用户自定义函数
 * 另外 [VAR_OBJ] 是模板/会话变量（`ctx.template.<名字>`，含固定的 `template` / `parent_template`）。
 */
object SparkScriptEngine {
    private const val TIMEOUT_MS = 2_000L
    private const val OBSERVER_THRESHOLD = 100_000

    /** 脚本上下文对象名。 */
    const val CTX = "ctx"

    /** 成员访问前缀，如 `ctx.`。 */
    const val CTX_DOT = "$CTX."

    /** 脚本约定的输出变量名：脚本给它赋值，引擎读它。 */
    const val RESULT_VAR = "\$spark_result\$"

    /** 输出列表的新写法：`ctx.result`（和 [RESULT_VAR] 是同一个数组，两种写法都认）。 */
    const val RESULT_OBJ = "result"

    /** 会话结果回调用法：`ctx.registerCallback((res) => { ... })`。 */
    const val CALLBACK_FN = "registerCallback"

    /** [CALLBACK_FN] 的旧拼写（早期文档里写成 registCallback，保留兼容）。 */
    const val CALLBACK_FN_ALIAS = "registCallback"

    /** 回调结果：发送成功。 */
    const val SEND_SUCCEED = "Succeed"

    /** 回调结果：这一条跳过了（例如脚本没有产出内容）。 */
    const val SEND_SKIPPED = "Skipped"

    /** 回调结果：发送失败（带失败原因）。 */
    const val SEND_FAILED = "Failed"

    /** 用户自定义函数挂载点：`ctx.functions.<名字>`。 */
    const val FUNCTIONS_OBJ = "functions"

    /** 内置能力挂载点：内置变量与内置函数都在这里。 */
    const val BUILTIN_OBJ = "builtin"

    /** 宿主数据挂载点：从抖音取到的会话数据。 */
    const val IO_OBJ = "io"

    /** 模板 / 会话变量挂载点：`ctx.template.<名字>`。 */
    const val VAR_OBJ = "template"

    /** 沙盒变量挂载点：`ctx.macro.getvar / setvar / deletevar`。 */
    const val MACRO_OBJ = "macro"

    /** 系统接口挂载点：`ctx.system.getAllConversation / mountMacro / unmountMacro`。 */
    const val SYSTEM_OBJ = "system"

    /** 模板变量里两个固定键：当前模板名、父模板名。 */
    const val VAR_TEMPLATE = "template"

    /** 父模板名（没有父模板时为空串）。 */
    const val VAR_PARENT_TEMPLATE = "parent_template"

    /** 沙盒宿主所有者前缀：`template:<会话模板 id>`。 */
    const val OWNER_TEMPLATE = "template:"

    /** 沙盒宏所有者前缀：`macro:<宏 id>`。 */
    const val OWNER_MACRO = "macro:"

    /** 沙盒环境：已有数据 + 本轮会话列表。沙盒只给**宏**用，归属就是宏自己。 */
    data class SandboxEnv(
        /** 已有沙盒数据：owner（`macro:<宏 id>`）→ 键值。 */
        val data: Map<String, List<SandboxVar>>,
        /** 本轮可见的会话（`ctx.system.getAllConversation()` 的返回值）。 */
        val conversations: String,
        /** 这个宏的挂载目标（模板 id / 会话 id 的 JSON 数组）。 */
        val mountTargets: String = "[]",
        /** 模板信息 JSON：`{thisid, parent:{id, childs:[{id,name}]}}`。 */
        val templateInfo: String = "{}",
        /** 宏 id → 宏名的 JSON（`ctx.macro.name` 用）。 */
        val macroNames: String = "{}",
        /** 按 id 取信息的数据：`{conversations:{id:..}, templates:{id:..}, macros:{id:..}}`。 */
        val info: String = "{}",
        /**
         * 预览 / 试算模式：**不触发任何会改数据的动作**
         *（写变量、删变量、挂载 / 摘掉宏），只把文案算出来。
         */
        val preview: Boolean = false,
    ) {
        companion object {
            val EMPTY = SandboxEnv(emptyMap(), "[]")
        }
    }

    /** 渲染结果：文案 + 脚本对沙盒与宏的改动（由调用方落盘）。 */
    data class Rendered(
        val text: String,
        val sandboxWrites: List<SandboxVar>,
        val sandboxDeletes: List<SandboxVar>,
        /** 宏挂载改动（见 [MacroOp]）。 */
        val macroOps: List<MacroOp>,
        /** 脚本用 `ctx.registerCallback` 注册的会话结果回调（发送后由宿主触发）。 */
        val callbacks: List<Function> = emptyList(),
    )

    /**
     * 一次宏挂载改动。
     *
     * @param kind `mount` / `unmount` / `unmountAll`
     * @param name 宏名（unmountAll 时为空）
     * @param targetId 目标会话 id 或模板 id；空表示当前会话
     * @param inherit 目标是模板时：true = 跟着派生会话一起跑，false = 仅模板自己
     */
    data class MacroOp(
        val kind: String,
        val name: String,
        val targetId: String,
        val inherit: Boolean,
    )

    /** 沙盒：脚本不可见任何 Java 类。 */
    private val denyJava = ClassShutter { false }

    /** 带时间预算的 ContextFactory：借指令计数回调实现超时中断。 */
    private class TimeoutFactory(private val deadlineMs: Long) : ContextFactory() {
        override fun observeInstructionCount(
            cx: Context,
            instructionCount: Int,
        ) {
            if (System.currentTimeMillis() > deadlineMs) throw ScriptTimeoutException()
        }
    }

    /**
     * 执行脚本并取回文案（不关心沙盒改动时用这个重载）。
     *
     * @param script 用户脚本文本
     * @param values 注入 [CTX] 的变量；按 [VARIABLES] 里声明的归属分别进 `builtin` / `io`
     */
    fun render(
        script: String,
        values: Map<String, Any?>,
    ): String = renderFull(script, values, SandboxEnv.EMPTY.copy(preview = true), "", "").text

    /**
     * 预览用重载：数据由 [PreviewData] 造好（只带正在预览的那个会话），
     * 引擎只负责把 [SandboxEnv.preview] 置为 true：不触发任何修改。
     */
    fun render(
        script: String,
        values: Map<String, Any?>,
        env: SandboxEnv,
    ): String = renderFull(script, values, env.copy(preview = true), "", "").text

    /**
     * 执行脚本，取回文案与沙盒改动。
     *
     * @param env 沙盒环境（拥有者、已有数据、会话列表）
     * @param templateName 当前模板名（`ctx.template.template`）
     * @param parentTemplateName 父模板名（`ctx.template.parent_template`）
     */
    fun renderFull(
        script: String,
        values: Map<String, Any?>,
        env: SandboxEnv,
        templateName: String,
        parentTemplateName: String,
    ): Rendered {
        val factory = TimeoutFactory(System.currentTimeMillis() + TIMEOUT_MS)
        val cx = factory.enterContext()
        try {
            // 解释执行：避免运行时生成字节码（Android 上更稳）
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.setClassShutter(denyJava)
            cx.instructionObserverThreshold = OBSERVER_THRESHOLD

            val scope = cx.initStandardObjects()
            val ctxObject = cx.newObject(scope)
            // 自定义函数挂载点：执行前编译期会把用户函数写进 ctx.functions.<名字>
            ScriptableObject.putProperty(ctxObject, FUNCTIONS_OBJ, cx.newObject(scope))
            // 变量按来源分两处挂：内置变量进 ctx.builtin，宿主数据进 ctx.io
            val builtinObject = cx.newObject(scope)
            val ioObject = cx.newObject(scope)
            for ((key, value) in values) {
                val target = if (key in IO_KEYS) ioObject else builtinObject
                ScriptableObject.putProperty(target, key, Context.javaToJS(value, scope))
            }
            ScriptableObject.putProperty(ctxObject, BUILTIN_OBJ, builtinObject)
            ScriptableObject.putProperty(ctxObject, IO_OBJ, ioObject)
            // 模板变量对象：先放两个固定键，前导脚本再把用户的变量写进来
            val templateObject = cx.newObject(scope)
            ScriptableObject.putProperty(templateObject, VAR_TEMPLATE, templateName)
            ScriptableObject.putProperty(templateObject, VAR_PARENT_TEMPLATE, parentTemplateName)
            ScriptableObject.putProperty(ctxObject, VAR_OBJ, templateObject)
            ScriptableObject.putProperty(scope, CTX, ctxObject)
            // 沙盒 / 系统接口的底层数据（JS 侧用 JSON.parse 展开）
            val state = cx.newObject(scope)
            ScriptableObject.putProperty(state, "dataJson", sandboxJson(env))
            ScriptableObject.putProperty(state, "mountJson", env.mountTargets)
            ScriptableObject.putProperty(state, "tplJson", env.templateInfo)
            ScriptableObject.putProperty(state, "namesJson", env.macroNames)
            ScriptableObject.putProperty(state, "infoJson", env.info)
            ScriptableObject.putProperty(state, "preview", env.preview)
            ScriptableObject.putProperty(state, "convJson", env.conversations)
            ScriptableObject.putProperty(scope, SANDBOX_STATE_VAR, state)

            cx.evaluateString(scope, MACROS, "macros", 1, null)

            // 结果统一按**列表**初始化：脚本用 `ctx.result.push("…")` 或
            // `$spark_result$.push("…")` 逐行添加（两者是同一个数组）。
            // 隐式赋值建立的是可配置的全局属性，用户随后写 var / let / const 也不会重声明报错。
            // 直接赋字符串（`ctx.result = "…"`）同样接受，[flatten] 会把它当一行处理。
            // 开头的 `;` 很关键：脚本以 `)` / `]` 结尾且没写分号时，
            // 下一行以 `(` 开头会被 ASI 当成连续调用（push(..)(..) → 数字不是函数）
            val program =
                "$RESULT_VAR = [];\n" +
                    "$CTX.$RESULT_OBJ = $RESULT_VAR;\n" +
                    "__sb.result0 = $RESULT_VAR;\n" +
                    script +
                    "\n;\n" +
                    "__sparkSyncResult();\n" +
                    "(typeof $CTX.$RESULT_OBJ === 'undefined' || $CTX.$RESULT_OBJ == null)" +
                    " ? ((typeof $RESULT_VAR === 'undefined' || $RESULT_VAR == null) ? undefined : $RESULT_VAR)" +
                    " : $CTX.$RESULT_OBJ;"
            val result = cx.evaluateString(scope, program, "autospark", 1, null)
            if (result == null || result == Scriptable.NOT_FOUND || result == Undefined.instance) {
                throw IllegalStateException("脚本没有给 ctx.result（或 $RESULT_VAR）赋值")
            }
            val text = flatten(result).trim()
            val changes = collectChanges(cx, scope)
            val callbacks = collectCallbacks(scope)
            // 空结果按「跳过」处理（文档口径：空列表 = 这条不发），把回调一起带出去，宿主可以回一个 Skipped
            if (text.isEmpty()) throw EmptyResultException("ctx.result 是空的（没有内容可发送）", callbacks)
            return Rendered(text, changes.writes, changes.deletes, changes.ops, callbacks)
        } finally {
            Context.exit()
        }
    }

    /** 沙盒里 `__sb` 这个名字（脚本可见但约定不碰）。 */
    private const val SANDBOX_STATE_VAR = "__sb"

    /** 把 owner → key → 值 拼成 `{"owner":{"key":<json>}}`（值本身就是 JSON 文本）。 */
    private fun sandboxJson(env: SandboxEnv): String {
        val root = JSONObject()
        env.data.forEach { (owner, vars) ->
            val scope = JSONObject()
            vars.forEach { variable ->
                runCatching { scope.put(variable.key, JSONObject(variable.value)) }
                    .recoverCatching { scope.put(variable.key, org.json.JSONTokener(variable.value).nextValue()) }
            }
            root.put(owner, scope)
        }
        return root.toString()
    }

    /** 脚本跑完后把沙盒写入 / 删除 / 宏操作取回来。 */
    private fun collectChanges(
        cx: Context,
        scope: Scriptable,
    ): Changes {
        val probe =
            "JSON.stringify({w: $SANDBOX_STATE_VAR.writes, d: $SANDBOX_STATE_VAR.deletes, " +
                "o: $SANDBOX_STATE_VAR.ops})"
        val value =
            runCatching { cx.evaluateString(scope, probe, "sandbox", 1, null) }
                .getOrNull() ?: return Changes()
        val json = runCatching { JSONObject(Context.toString(value)) }.getOrNull() ?: return Changes()

        val writes =
            (0 until json.optJSONArray("w").orEmptyLength()).mapNotNull { index ->
                val item = json.optJSONArray("w")?.optJSONArray(index) ?: return@mapNotNull null
                val owner = item.optString(0)
                val key = item.optString(1)
                val raw = item.optString(2)
                if (owner.isBlank() || key.isBlank()) null else SandboxVar(owner, key, raw)
            }
        val deletes =
            (0 until json.optJSONArray("d").orEmptyLength()).mapNotNull { index ->
                val item = json.optJSONArray("d")?.optJSONArray(index) ?: return@mapNotNull null
                val owner = item.optString(0)
                val key = item.optString(1)
                if (owner.isBlank() || key.isBlank()) null else SandboxVar(owner, key, "")
            }
        val ops =
            (0 until json.optJSONArray("o").orEmptyLength()).mapNotNull { index ->
                val item = json.optJSONArray("o")?.optJSONArray(index) ?: return@mapNotNull null
                val kind = item.optString(0)
                val name = item.optString(1)
                val target = item.optString(2)
                val inherit = item.optBoolean(3, true)
                // unmountAll 本来就没有宏名（第三个元素是空串），不能因为 name 为空就丢掉
                if (kind.isBlank() || (kind != "unmountAll" && name.isBlank())) null else MacroOp(kind, name, target, inherit)
            }
        return Changes(writes, deletes, ops)
    }

    private fun org.json.JSONArray?.orEmptyLength(): Int = this?.length() ?: 0

    /**
     * 发送结束后触发脚本注册的会话结果回调，参数是 `{conversationId, result, reason?}`。
     *
     * [result] 取 [SEND_SUCCEED] / [SEND_SKIPPED] / [SEND_FAILED]；[reason] 有具体原因时才带。
     * 回调里对沙盒 / 宏的改动会重新收集一遍返回（和渲染时收集的有重复，
     * 落盘是幂等的，后写的值生效）。预览模式不注册回调，这里直接返回空结果。
     */
    fun notifySendResult(
        callbacks: List<Function>,
        conversationId: String,
        result: String,
        reason: String = "",
    ): Rendered {
        if (callbacks.isEmpty()) return Rendered("", emptyList(), emptyList(), emptyList())
        val scope = callbacks.first().parentScope ?: return Rendered("", emptyList(), emptyList(), emptyList())
        val factory = TimeoutFactory(System.currentTimeMillis() + TIMEOUT_MS)
        val cx = factory.enterContext()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.setClassShutter(denyJava)
            cx.instructionObserverThreshold = OBSERVER_THRESHOLD
            val argument = cx.newObject(scope)
            // 统一用 id；conversationId 是同一值的旧名，保留兼容
            ScriptableObject.putProperty(argument, "id", conversationId)
            ScriptableObject.putProperty(argument, "conversationId", conversationId)
            ScriptableObject.putProperty(argument, "result", result)
            if (reason.isNotBlank()) ScriptableObject.putProperty(argument, "reason", reason)
            callbacks.forEach { callback ->
                runCatching { callback.call(cx, scope, scope, arrayOf<Any>(argument)) }
                    .onFailure { ModLog.w("会话结果回调执行失败", it) }
            }
            val changes = collectChanges(cx, scope)
            return Rendered("", changes.writes, changes.deletes, changes.ops)
        } finally {
            Context.exit()
        }
    }

    /** 取出脚本用 `ctx.registerCallback` 注册的回调（预览模式下为空）。 */
    private fun collectCallbacks(scope: Scriptable): List<Function> {
        val state = ScriptableObject.getProperty(scope, SANDBOX_STATE_VAR) as? Scriptable ?: return emptyList()
        val array = ScriptableObject.getProperty(state, "callbacks") as? Scriptable ?: return emptyList()
        val length = (ScriptableObject.getProperty(array, "length") as? Number)?.toInt() ?: 0
        return (0 until length).mapNotNull { index ->
            ScriptableObject.getProperty(array, index) as? Function
        }
    }

    private data class Changes(
        val writes: List<SandboxVar> = emptyList(),
        val deletes: List<SandboxVar> = emptyList(),
        val ops: List<MacroOp> = emptyList(),
    )

    /** 结果是列表时按行拼接（一条消息里多行），字符串或其它值直接转字符串。 */
    private fun flatten(result: Any?): String {
        val scriptable = result as? Scriptable
        if (scriptable != null && scriptable.className == "Array") {
            val length = ScriptableObject.getProperty(scriptable, "length")?.let { Context.toNumber(it).toInt() } ?: 0
            return (0 until length).joinToString("\n") { index ->
                Context.toString(ScriptableObject.getProperty(scriptable, index))
            }
        }
        return Context.toString(result)
    }

    /** 上下文成员说明：供界面展示、编辑器补全与类型定义生成。 */
    data class Doc(
        val name: String,
        val params: String,
        val desc: String,
        /** 变量是它的类型，函数是它的返回类型（生成 `spark.d.ts` 用）。 */
        val type: String = "any",
        /** 挂在哪个命名空间下：[BUILTIN_OBJ] 或 [IO_OBJ]。 */
        val scope: String = BUILTIN_OBJ,
    ) {
        /** 是否需要括号调用。 */
        val isFunction: Boolean get() = params.isNotEmpty()

        /** 完整签名，如 `ctx.builtin.pick(list)` / `ctx.io.nickname`。 */
        val signature: String get() = "$CTX_DOT$scope.$name" + if (isFunction) "($params)" else ""
    }

    /** 内置函数：都挂在 `ctx.builtin` 下。 */
    val FUNCTIONS: List<Doc> =
        listOf(
            Doc("pick", "list", "从数组里随机取一个", "any", BUILTIN_OBJ),
            Doc("pad", "n", "数字补零到两位", "string", BUILTIN_OBJ),
            Doc("repeat", "s, n", "把字符串 s 重复 n 次", "string", BUILTIN_OBJ),
            Doc("random", "a, b", "[a, b) 区间内的随机整数", "number", BUILTIN_OBJ),
            Doc("shuffle", "list", "洗牌，返回新数组", "any[]", BUILTIN_OBJ),
            Doc("countdown", "target", "距目标日期还有几天（已过为负）", "number", BUILTIN_OBJ),
            Doc("nextNewYear", "", "下一个元旦的日期字符串", "string", BUILTIN_OBJ),
            Doc("untilNewYear", "", "距下一个元旦还有几天", "number", BUILTIN_OBJ),
            Doc("formatDate", "date, pattern", "按 yyyy/MM/dd/HH/mm/ss 格式化日期", "string", BUILTIN_OBJ),
        )

    /** 沙盒与系统接口：挂在 `ctx.macro` / `ctx.system` 下。 */
    val SANDBOX_FUNCTIONS: List<Doc> =
        listOf(
            Doc("id", "", "当前宏的 id（模板段为空串）", "string", MACRO_OBJ),
            Doc("name", "", "当前宏的名字（模板段为空串）", "string", MACRO_OBJ),
            Doc("getvar", "name", "读一个自己的变量", "any", MACRO_OBJ),
            Doc("setvar", "name, value", "写一个自己的变量（值是任意 JS 值，存成 JSON）", "void", MACRO_OBJ),
            Doc("deletevar", "name", "删一个自己创建的变量", "boolean", MACRO_OBJ),
        )

    /** 系统接口：挂在 `ctx.system` 下。 */
    val SYSTEM_FUNCTIONS: List<Doc> =
        listOf(
            Doc(
                "getAllConversation",
                "filter?: SparkConversationFilter",
                "本轮会话（SparkConversation[]）；对象逐项相等 / 数组任一 / 函数判断；tags.include、tags.except 按标签筛（内层交集、外层并集）",
                "SparkConversation[]",
                SYSTEM_OBJ,
            ),
            Doc("getConversation", "id: string", "按会话 id 取信息结构（没有返回 null）", "SparkConversationDetail | null", SYSTEM_OBJ),
            Doc("getTemplate", "id: string", "按模板 id 取信息结构（id/name/body/childs/isBuiltin）", "any", SYSTEM_OBJ),
            Doc("getMacro", "id: string", "按宏 id 取信息结构（id/name/body/templates/conversations）", "any", SYSTEM_OBJ),
            Doc("mountMacro", "id: string, target?: any", "按宏 id 挂到指定会话 / 模板（不传 = 当前会话）", "void", SYSTEM_OBJ),
            Doc("unmountMacro", "id: string, target?: any", "按宏 id 从指定会话 / 模板摘掉", "void", SYSTEM_OBJ),
            Doc("unmountAll", "target?: any", "把指定目标上的所有宏都摘掉", "void", SYSTEM_OBJ),
        )

    /**
     * 脚本可见变量，按来源分两处：
     * - `ctx.builtin`：日期时间这类**引擎自己算出来的**
     * - `ctx.io`：**从抖音取到的**会话数据（昵称、备注、天数、状态…）
     */
    val VARIABLES: List<Doc> =
        listOf(
            Doc("date", "", "当天日期 yyyy-MM-dd", "string", BUILTIN_OBJ),
            Doc("time", "", "当前时间 HH:mm", "string", BUILTIN_OBJ),
            Doc("weekday", "", "星期几", "string", BUILTIN_OBJ),
            Doc("timestamp", "", "当前毫秒时间戳", "number", BUILTIN_OBJ),
            Doc("id", "", "会话 ID（与会话对象的 id 一致）", "string", IO_OBJ),
            Doc("nickname", "", "对方昵称", "string", IO_OBJ),
            Doc("remark", "", "备注名（可能为空）", "string", IO_OBJ),
            Doc("name", "", "显示名（备注优先，其次昵称）", "string", IO_OBJ),
            Doc("days", "", "已连续聊天天数", "number", IO_OBJ),
            Doc("state", "", "火花状态码", "number", IO_OBJ),
            Doc("stateText", "", "火花状态文本", "string", IO_OBJ),
            Doc("peerUid", "", "对方 UID", "string", IO_OBJ),
            Doc("conversationId", "", "会话 ID 的旧名，等同 id", "string", IO_OBJ),
        )

    /** 宿主数据（`ctx.io` 下）的键：其余变量都进 `ctx.builtin`。 */
    private val IO_KEYS: Set<String> = VARIABLES.filter { it.scope == IO_OBJ }.map { it.name }.toSet()

    /** 内置函数的形参声明（生成类型定义用，键是函数名）。 */
    val PARAM_TYPES: Map<String, String> =
        mapOf(
            "pick" to "list: any[]",
            "pad" to "n: number",
            "repeat" to "s: string, n: number",
            "random" to "a: number, b: number",
            "shuffle" to "list: any[]",
            "countdown" to "target: string",
            "formatDate" to "date: string, pattern?: string",
        )

    /** 全局宏：脚本里以 `ctx.builtin.xxx(...)` / `ctx.macro.xxx(...)` / `ctx.system.xxx(...)` 调用。 */
    private val MACROS =
        """
        // 从数组里随机取一个
        function pick(list){ if (!list || list.length === 0) return ""; return list[Math.floor(Math.random() * list.length)]; }
        // 两位补零
        function pad(n){ n = Math.trunc(n); return (n < 10 ? "0" : "") + n; }
        // 重复字符串 n 次
        function repeat(s, n){ var out = ""; for (var i = 0; i < n; i++) out += s; return out; }
        // [a, b) 随机整数
        function random(a, b){ if (b === undefined){ b = a; a = 0; } return Math.floor(a + Math.random() * (b - a)); }
        // 洗牌（返回新数组）
        function shuffle(list){ var arr = list.slice(); for (var i = arr.length - 1; i > 0; i--){ var j = Math.floor(Math.random() * (i + 1)); var t = arr[i]; arr[i] = arr[j]; arr[j] = t; } return arr; }
        // 距离目标日期还有几天（正=未来，0=今天，负=已过）。target 可为 "2027-01-01" 或毫秒时间戳
        function countdown(target){ var t = (typeof target === "number") ? target : new Date(target).getTime(); var now = new Date(); now.setHours(0,0,0,0); return Math.round((t - now.getTime()) / 86400000); }
        // 下一个元旦（1 月 1 日）的日期字符串
        function nextNewYear(){ var now = new Date(); var y = now.getFullYear(); var target = new Date(y, 0, 1); if (target.getTime() < now.getTime()) target = new Date(y + 1, 0, 1); return target.getFullYear() + "-" + ("0" + (target.getMonth() + 1)).slice(-2) + "-" + ("0" + target.getDate()).slice(-2); }
        // 距离下一个元旦还有几天
        function untilNewYear(){ return countdown(new Date(nextNewYear()).getTime()); }
        // 按模式格式化日期，支持 yyyy / MM / dd / HH / mm / ss
        function formatDate(date, pattern){ var d = (date === undefined) ? new Date() : ((typeof date === "number") ? new Date(date) : new Date(date)); var map = { yyyy: d.getFullYear(), MM: ("0" + (d.getMonth() + 1)).slice(-2), dd: ("0" + d.getDate()).slice(-2), HH: ("0" + d.getHours()).slice(-2), mm: ("0" + d.getMinutes()).slice(-2), ss: ("0" + d.getSeconds()).slice(-2) }; return pattern.replace(/yyyy|MM|dd|HH|mm|ss/g, function(k){ return map[k]; }); }
        // 内置函数统一挂到 ctx.builtin 上（脚本里用 ctx.builtin.pick(...) 调用）
        ctx.builtin.pick = pick; ctx.builtin.pad = pad; ctx.builtin.repeat = repeat; ctx.builtin.random = random;
        ctx.builtin.shuffle = shuffle; ctx.builtin.countdown = countdown; ctx.builtin.nextNewYear = nextNewYear;
        ctx.builtin.untilNewYear = untilNewYear; ctx.builtin.formatDate = formatDate;

        // 沙盒 / 系统接口的展开：Kotlin 侧塞进来的是 JSON 文本
        __sb.data = (__sb.dataJson && __sb.dataJson.length > 1) ? JSON.parse(__sb.dataJson) : {};
        __sb.mounts = (__sb.mountJson && __sb.mountJson.length > 1) ? JSON.parse(__sb.mountJson) : [];
        __sb.tpl = (__sb.tplJson && __sb.tplJson.length > 1) ? JSON.parse(__sb.tplJson) : { thisid: "", parent: { id: "", childs: [] } };
        ctx.template = ctx.template || {};
        ctx.template.template = __sb.tpl.template || { id: "", name: "" };
        ctx.template.parent = __sb.tpl.parent || { id: "", name: "", childs: [] };
        __sb.convs = (__sb.convJson && __sb.convJson.length > 1) ? JSON.parse(__sb.convJson) : [];
        __sb.writes = []; __sb.deletes = []; __sb.ops = []; __sb.owner = ""; __sb.callbacks = [];

        // 沙盒变量：**只给宏用**，归属就是宏自己（模板里 ctx.macro 是 undefined）。
        // 每个宏执行前，程序里会插一句 __sandboxOwner("macro:<宏 id>")
        function __makeSandbox(owner){
          var api = {
            getvar: function(name){
              var own = __sb.data[owner];
              return (own && Object.prototype.hasOwnProperty.call(own, String(name))) ? own[String(name)] : undefined;
            },
            setvar: function(name, value){
              name = String(name);
              var own = __sb.data[owner];
              if (!own) { own = {}; __sb.data[owner] = own; }
              own[name] = value;
              __sb.writes.push([owner, name, JSON.stringify(value === undefined ? null : value)]);
            },
            deletevar: function(name){
              name = String(name);
              var own = __sb.data[owner];
              if (own && Object.prototype.hasOwnProperty.call(own, name)) {
                delete own[name];
                __sb.deletes.push([owner, name]);
                return true;
              }
              return false;
            }
          };
          return api;
        }
        // ctx.macro 始终存在（否则宏定义脚本里赋值就报 undefined），但只有宏段能用
        ctx.macro = {
          getvar: function(name){ return __requireMacro().getvar(name); },
          setvar: function(name, value){ return __requireMacro().setvar(name, value); },
          deletevar: function(name){ return __requireMacro().deletevar(name); },
        };
        function __requireMacro(){
          if (String(__sb.owner).indexOf("macro:") !== 0) {
            throw new Error("ctx.macro 的读写只能在宏脚本里用");
          }
          var owner = __sb.owner;
          var own = __sb.data[owner];
          if (!own) { own = {}; __sb.data[owner] = own; }
          return {
            getvar: function(name){ name = String(name); return Object.prototype.hasOwnProperty.call(own, name) ? own[name] : undefined; },
            setvar: function(name, value){ if (__sb.preview) return; name = String(name); own[name] = value; __sb.writes.push([owner, name, JSON.stringify(value === undefined ? null : value)]); },
            deletevar: function(name){ if (__sb.preview) return false; name = String(name); if (Object.prototype.hasOwnProperty.call(own, name)) { delete own[name]; __sb.deletes.push([owner, name]); return true; } return false; }
          };
        }
        __sb.names = (__sb.namesJson && __sb.namesJson.length > 1) ? JSON.parse(__sb.namesJson) : {};
        __sb.preview = __sb.preview === true;
        function __sandboxOwner(owner){
          __sb.owner = owner;
          var isMacro = String(owner).indexOf("macro:") === 0;
          __sb.macroId = isMacro ? String(owner).substring(6) : "";
          ctx.macro.id = __sb.macroId;
          ctx.macro.name = isMacro ? (__sb.names[__sb.macroId] || "") : "";
        }

        // 结果列表：老写法 ${'$'}spark_result${'$'} 与新写法 ctx.result 指向同一个数组。
        // 宏执行前、程序结束时各同步一次（脚本可能把其中一个换成了字符串或新数组）。
        function __sparkSyncResult(){
          var initial = __sb.result0;
          var old = ${'$'}spark_result${'$'}, api = ctx.result, chosen;
          if (old !== initial && old !== undefined && old !== null) chosen = old;
          else if (api !== initial && api !== undefined && api !== null) chosen = api;
          else chosen = (old !== undefined && old !== null) ? old : api;
          if (typeof chosen === "string") chosen = [chosen];
          if (Object.prototype.toString.call(chosen) !== "[object Array]") chosen = [chosen];
          ${'$'}spark_result${'$'} = chosen;
          ctx.result = chosen;
          __sb.result0 = chosen;
        }

        // 系统接口：会话列表 + 宏挂载
        // target 可以是：不传（当前会话）/ { conversation: "id" } / { template: "id", inherit: true|false }
        function __targetOf(target){
          if (!target) return ["", true];
          if (typeof target === "string") return [String(target), true];
          if (target.conversation) return [String(target.conversation), true];
          if (target.template) return [String(target.template), !(target.inherit === false)];
          return ["", true];
        }
        __sb.info = (__sb.infoJson && __sb.infoJson.length > 1) ? JSON.parse(__sb.infoJson) : {};
        function __requireMacroId(id){
          var table = (__sb.info && __sb.info.macros) ? __sb.info.macros : null;
          if (!table) return;
          if (!table[String(id)]) {
            throw new Error("找不到这个宏 id：" + String(id) + "（这里要传宏的 id，不是名字）");
          }
        }
        function __lookup(kind, id){
          var table = __sb.info[kind] || {};
          var hit = table[String(id)];
          return hit || null;
        }
        // 会话筛选：参数可以是函数（逐项判断），也可以是对象
        //（每个键都要相等；值是数组表示「其中之一」；值是函数表示自定义判断）
        // tags: { include, except } 是标签的两级条件：内层数组 = 交集（都要有），
        // 外层数组 = 并集（任一组成立即可）；元素可以是标签 id 或名字
        function __tagHit(item, tag){
          var list = item.tags || [];
          for (var i = 0; i < list.length; i++){
            var t = list[i];
            if (t && (String(t.id) === String(tag) || String(t.name) === String(tag))) return true;
          }
          return false;
        }
        function __tagGroups(value){
          if (!value || !value.length) return [];
          // 一维数组 = 单个交集组；二维数组 = 多个交集组取并集
          return (typeof value[0] === "string") ? [value] : value;
        }
        function __matchTagGroups(item, groups){
          var list = __tagGroups(groups);
          if (!list.length) return true;
          for (var i = 0; i < list.length; i++){
            var group = list[i];
            if (typeof group === "string") group = [group];
            if (!group.length) return true;
            var ok = true;
            for (var j = 0; j < group.length; j++){
              if (!__tagHit(item, group[j])){ ok = false; break; }
            }
            if (ok) return true;
          }
          return false;
        }
        function __matchConversation(item, filter){
          if (!filter) return true;
          for (var key in filter){
            if (!Object.prototype.hasOwnProperty.call(filter, key)) continue;
            if (key === "tags") continue;
            var want = filter[key];
            if (typeof want === "function"){ if (!want(item)) return false; continue; }
            var actual = item[key];
            if (Object.prototype.toString.call(want) === "[object Array]"){
              if (want.indexOf(actual) < 0) return false;
            } else if (actual !== want){
              return false;
            }
          }
          var tagCond = filter.tags;
          if (tagCond && Object.prototype.toString.call(tagCond) === "[object Array]") tagCond = { include: tagCond };
          if (tagCond){
            if (tagCond.include && !__matchTagGroups(item, tagCond.include)) return false;
            if (tagCond.except && __matchTagGroups(item, tagCond.except)) return false;
          }
          return true;
        }
        ctx.system = {
          getAllConversation: function(filter){
            if (typeof filter === "function") return __sb.convs.filter(filter);
            return __sb.convs.filter(function(item){ return __matchConversation(item, filter); });
          },
          getConversation: function(id){ return __lookup("conversations", id); },
          getTemplate: function(id){ return __lookup("templates", id); },
          getMacro: function(id){ return __lookup("macros", id); },
          mountMacro: function(id, target){
            if (__sb.preview) return;
            __requireMacroId(id);
            var t = __targetOf(target);
            __sb.ops.push(["mount", String(id), t[0], t[1]]);
          },
          unmountMacro: function(id, target){
            if (__sb.preview) return;
            __requireMacroId(id);
            var t = __targetOf(target);
            __sb.ops.push(["unmount", String(id), t[0], t[1]]);
          },
          unmountAll: function(target){
            if (__sb.preview) return;
            var t = __targetOf(target);
            __sb.ops.push(["unmountAll", "", t[0], t[1]]);
          }
        };

        // 会话结果回调：脚本注册，宿主在「这个会话发送结束」后调用，参数 {conversationId, success}
        function __registCallback(fn){
          if (typeof fn !== "function") throw new Error("ctx.registerCallback 需要一个函数");
          if (__sb.preview) return;
          __sb.callbacks.push(fn);
        }
        ctx.registerCallback = __registCallback;
        ctx.registCallback = __registCallback;
        """.trimIndent()
}
