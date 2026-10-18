package com.worldmargin.autospark.features.autospark.script

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/** 脚本执行超时（多半是死循环）。 */
class ScriptTimeoutException : RuntimeException("脚本执行超时（可能存在死循环）")

/**
 * 动态文案脚本引擎（Rhino JS 沙盒）。
 *
 * 宿主把上下文挂到沙盒里的 `ctx` 上，脚本把要发送的文本赋给固定变量 [RESULT_VAR]
 * （`$spark_result$`），引擎只读该变量（不会去取「最后一个表达式」的值）。
 *
 * `ctx` 下面按来源分三个命名空间：
 * - [BUILTIN_OBJ]：内置能力 —— 日期时间等内置变量与 [FUNCTIONS]（`ctx.builtin.pick(...)`）
 * - [IO_OBJ]：宿主数据 —— 从抖音取到的会话信息（`ctx.io.nickname`、`ctx.io.days`…）
 * - [FUNCTIONS_OBJ]：用户自定义函数（`ctx.functions.<名字>`）
 * 另外 [VAR_OBJ] 是模板/会话变量（`ctx.template.<名字>`）。
 *
 * 脚本看不到任何 Java 类（[ClassShutter] 全拒），并有执行时间预算，超时即中断。
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

    /** 用户自定义函数挂载点：`ctx.functions.<名字>`。 */
    const val FUNCTIONS_OBJ = "functions"

    /** 内置能力挂载点：内置变量与内置函数都在这里。 */
    const val BUILTIN_OBJ = "builtin"

    /** 宿主数据挂载点：从抖音取到的会话数据。 */
    const val IO_OBJ = "io"

    /** 模板 / 会话变量挂载点：`ctx.template.<名字>`。 */
    const val VAR_OBJ = "template"

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
     * 执行脚本并取回文案。
     *
     * @param script 用户脚本文本
     * @param values 注入 [CTX] 的变量；按 [VARIABLES] 里声明的归属分别进 `builtin` / `io`
     * @return 脚本写入 [RESULT_VAR] 的内容（去掉首尾空白）
     */
    fun render(
        script: String,
        values: Map<String, Any?>,
    ): String {
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
            ScriptableObject.putProperty(scope, CTX, ctxObject)

            cx.evaluateString(scope, MACROS, "macros", 1, null)

            // 结果变量统一按**列表**初始化：脚本用 `$spark_result$.push("…")` 逐行添加。
            // 隐式赋值建立的是可配置的全局属性，用户随后写 var / let / const 也不会重声明报错。
            // 直接赋字符串（`= "…"`）同样接受，[flatten] 会把它当一行处理。
            val program =
                "$RESULT_VAR = [];\n" +
                    script +
                    "\n(typeof $RESULT_VAR === 'undefined' || $RESULT_VAR == null)" +
                    " ? undefined : $RESULT_VAR;"
            val result = cx.evaluateString(scope, program, "autospark", 1, null)
            if (result == null || result == Scriptable.NOT_FOUND || result == Undefined.instance) {
                throw IllegalStateException("脚本没有给 $RESULT_VAR 赋值")
            }
            val text = flatten(result).trim()
            if (text.isEmpty()) throw IllegalStateException("$RESULT_VAR 是空的（没有内容可发送）")
            return text
        } finally {
            Context.exit()
        }
    }

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

    /**
     * 脚本可见变量，按来源分两处：
     * - `ctx.builtin`：日期时间这类**引擎自己算出来的**（[VARIABLES] 里 scope 为 [BUILTIN_OBJ]）
     * - `ctx.io`：**从抖音取到的**会话数据（昵称、备注、天数、状态…）
     */
    val VARIABLES: List<Doc> =
        listOf(
            Doc("date", "", "当天日期 yyyy-MM-dd", "string", BUILTIN_OBJ),
            Doc("time", "", "当前时间 HH:mm", "string", BUILTIN_OBJ),
            Doc("weekday", "", "星期几", "string", BUILTIN_OBJ),
            Doc("timestamp", "", "当前毫秒时间戳", "number", BUILTIN_OBJ),
            Doc("nickname", "", "对方昵称", "string", IO_OBJ),
            Doc("remark", "", "备注名（可能为空）", "string", IO_OBJ),
            Doc("name", "", "显示名（备注优先，其次昵称）", "string", IO_OBJ),
            Doc("days", "", "已连续聊天天数", "number", IO_OBJ),
            Doc("state", "", "火花状态码", "number", IO_OBJ),
            Doc("stateText", "", "火花状态文本", "string", IO_OBJ),
            Doc("peerUid", "", "对方 UID", "string", IO_OBJ),
            Doc("conversationId", "", "会话 ID", "string", IO_OBJ),
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

    /** 全局宏：脚本里以 `ctx.builtin.xxx(...)` 调用。 */
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
        """.trimIndent()
}
