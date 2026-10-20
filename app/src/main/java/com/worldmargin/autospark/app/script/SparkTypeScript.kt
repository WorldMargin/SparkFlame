package com.worldmargin.autospark.app.script

import com.worldmargin.autospark.app.config.SparkConfig
import com.worldmargin.autospark.app.config.TemplateVar
import com.worldmargin.autospark.app.config.UserFunction

/**
 * 生成 Monaco 用的 TypeScript 类型定义（`spark.d.ts`）。
 *
 * 编辑器把这份声明通过 `addExtraLib` 注入，于是脚本里写 `ctx.` 时能拿到变量、内置函数
 * 以及用户自定义函数的**类型与说明**，JavaScript 内置对象的成员则由 Monaco 自带的
 * TypeScript 语言服务提供。
 *
 * [templateVars] 是当前模板（含父模板）定义的变量：逐个声明进 `ctx.template`，
 * 于是 `ctx.template.` 后面能补全出变量名，表达式编辑页里也能看到自己前面算出来的变量。
 */
object SparkTypeScript {
    /** 旧写法：脚本结果变量。新脚本用 `ctx.result`，这里保留声明只为兼容老脚本。 */

    fun dts(
        config: SparkConfig,
        templateVars: List<TemplateVar> = emptyList(),
    ): String {
        val builder = StringBuilder()
        builder.append("// 由模块生成：上下文对象与自定义函数声明\n")
        builder.append("interface SparkFunctions {\n")
        config.functions.forEach { function ->
            builder.append("\t/** ").append(escape(function.desc.ifBlank { "自定义函数" })).append(" */\n")
            builder.append("\t").append(function.name).append('(').append(params(function)).append("): any;\n")
        }
        builder.append("}\n")
        builder.append("interface SparkTemplateParent {\n")
        builder.append("\t/** 父模板 id（空白模板为 builtin:blank） */\n")
        builder.append("\tid: string;\n")
        builder.append("\t/** 父模板名 */\n")
        builder.append("\tname: string;\n")
        builder.append("\t/** 父模板的派生列表 */\n")
        builder.append("\tchilds: Array<{ id: string; name: string }>;\n")
        builder.append("}\n")
        builder.append("interface SparkTemplate {\n")


        builder.append("\t/** 当前模板（会话模板 / 模板） */\n")
        builder.append("\ttemplate: { id: string; name: string };\n")
        builder.append("\t/** 父模板信息 */\n")
        builder.append("\tparent: SparkTemplateParent;\n")
        builder.append("\t/** 变量都在 ctx.template.vars 下 */\n")
        builder.append("\tvars: SparkVars;\n")
        // 同名只声明一次（会话自己的变量排在后面，所以后者覆盖）
        val unique = LinkedHashMap<String, TemplateVar>()
        templateVars.filter { it.name.isNotBlank() }.forEach { unique[it.name] = it }
        unique.values.forEach { variable ->
            builder.append("\t/** 模板变量：").append(escape(variable.name)).append(" */\n")
            builder.append("\t").append(property(variable.name)).append(": any;\n")
        }
        builder.append("}\n")
        builder.append("interface SparkVars {\n")
        builder.append("\t/** 变量名 → 求值结果；没声明的名字也能读（值为 undefined） */\n")
        builder.append("\t[key: string]: any;\n")
        unique.values.forEach { variable ->
            builder.append("\t/** 变量：").append(escape(variable.name)).append(" */\n")
            builder.append("\t").append(property(variable.name)).append(": any;\n")
        }
        builder.append("}\n")
        builder.append("interface SparkBuiltin {\n")
        SparkScriptEngine.VARIABLES.filter { it.scope == SparkScriptEngine.BUILTIN_OBJ }.forEach { variable ->
            builder.append("\t/** ").append(escape(variable.desc)).append(" */\n")
            builder.append("\t").append(variable.name).append(": ").append(variable.type).append(";\n")
        }
        SparkScriptEngine.FUNCTIONS.forEach { function ->
            builder.append("\t/** ").append(escape(function.desc)).append(" */\n")
            builder.append("\t")
                .append(function.name)
                .append('(')
                .append(SparkScriptEngine.PARAM_TYPES[function.name] ?: function.params)
                .append("): ")
                .append(function.type)
                .append(";\n")
        }
        builder.append("}\n")
        builder.append("interface SparkIo {\n")
        builder.append("\t/** 从抖音取到的会话数据 */\n")
        SparkScriptEngine.VARIABLES.filter { it.scope == SparkScriptEngine.IO_OBJ }.forEach { variable ->
            builder.append("\t/** ").append(escape(variable.desc)).append(" */\n")
            builder.append("\t").append(variable.name).append(": ").append(variable.type).append(";\n")
        }
        builder.append("}\n")
        builder.append("interface SparkMacro {\n")
        builder.append("\t/** 当前宏的 id（模板段为空串） */\n")
        builder.append("\tid: string;\n")
        builder.append("\t/** 当前宏的名字（模板段为空串） */\n")
        builder.append("\tname: string;\n")
        SparkScriptEngine.SANDBOX_FUNCTIONS.forEach { function ->
            builder.append("\t/** ").append(escape(function.desc)).append(" */\n")
            builder.append("\t").append(function.name).append('(').append(function.params).append("): any;\n")
        }
        builder.append("}\n")
        builder.append("interface SparkConversation {\n")
        builder.append("\t/** 会话 id（与 ctx.io.id 一致） */\n")
        builder.append("\tid: string;\n")
        builder.append("\t/** 昵称 */\n")
        builder.append("\tnickname: string;\n")
        builder.append("\t/** 显示名（备注优先，其次昵称） */\n")
        builder.append("\tname: string;\n")
        builder.append("\t/** 备注名（可能为空） */\n")
        builder.append("\tremark: string;\n")
        builder.append("\t/** 对方 UID */\n")
        builder.append("\tpeerUid: string;\n")
        builder.append("\t/** 这个会话的标签（id + 名字） */\n")
        builder.append("\ttags: Array<{ id: string; name: string }>;\n")
        builder.append("\t/** 已连续聊天天数 */\n")
        builder.append("\tdays: number;\n")
        builder.append("\t/** 火花状态码（-1 没有火苗） */\n")
        builder.append("\tstate: number;\n")
        builder.append("\t/** 火花状态文本 */\n")
        builder.append("\tstateText: string;\n")
        builder.append("\t/** 是否群聊 */\n")
        builder.append("\tisGroup: boolean;\n")
        builder.append("\t/** 是否在「已选目标」里（隐藏的会话不算） */\n")
        builder.append("\tselected: boolean;\n")
        builder.append("\t/** 今天是否已经发过 */\n")
        builder.append("\tsentToday: boolean;\n")
        builder.append("\t/** 手动发送会不会发它 */\n")
        builder.append("\tmanualSend: boolean;\n")
        builder.append("\t/** 自动发送会不会发它 */\n")
        builder.append("\tautoSend: boolean;\n")
        builder.append("\t/** 本轮实际会不会发它（当前轮次口径） */\n")
        builder.append("\twillSend: boolean;\n")
        builder.append("}\n")
        builder.append("interface SparkConversationDetail extends SparkConversation {\n")
        builder.append("\t/** 这个会话自己的模板 */\n")
        builder.append("\ttemplate: { id: string; name: string; body: string };\n")
        builder.append("\t/** 父模板（继承来源） */\n")
        builder.append("\tparent: { id: string; name: string; body: string };\n")
        builder.append("\t/** 挂在这个会话上的宏 */\n")
        builder.append("\tmacros: Array<{ id: string; name: string }>;\n")
        builder.append("}\n")
        builder.append("/** 会话筛选条件：对象（每个键相等；值为数组表示其中之一）或逐项判断的函数 */\n")
        builder.append("type SparkConversationFilter =\n")
        builder.append("\t| ((conversation: SparkConversation) => boolean)\n")
        builder.append("\t| {\n")
        builder.append("\t\t/** 任意会话字段：直接相等；数组 = 其中之一；函数 = 自定义判断 */\n")
        builder.append("\t\t[key: string]: any;\n")
        builder.append("\t\t/** 标签条件：内层数组 = 交集（组内标签都要有），外层数组 = 并集（任一组成立即可） */\n")
        builder.append("\t\ttags?:\n")
        builder.append("\t\t\t| Array<Array<string>>\n")
        builder.append("\t\t\t| string[]\n")
        builder.append("\t\t\t| { include?: Array<Array<string>> | string[]; except?: Array<Array<string>> | string[] };\n")
        builder.append("\t};\n")
        builder.append("/** 会话结果：发送成功 / 跳过（例如没有内容）/ 发送失败 */\n")
        builder.append("type SparkSendOutcome = \"Succeed\" | \"Skipped\" | \"Failed\";\n")
        builder.append("interface SparkSendReport {\n")
        builder.append("\t/** 刚处理完的会话 id */\n")
        builder.append("\tid: string;\n")
        builder.append("\t/** 结果：Succeed / Skipped / Failed */\n")
        builder.append("\tresult: SparkSendOutcome;\n")
        builder.append("\t/** 具体原因（跳过 / 失败时一般会有） */\n")
        builder.append("\treason?: string;\n")
        builder.append("}\n")
        builder.append("interface SparkSystem {\n")
        SparkScriptEngine.SYSTEM_FUNCTIONS.forEach { function ->
            builder.append("\t/** ").append(escape(function.desc)).append(" */\n")
            builder.append("\t").append(function.name).append('(').append(function.params).append("): ")
                .append(function.type).append(";\n")
        }
        builder.append("}\n")
        builder.append("interface SparkCtx {\n")
        builder.append("\t/** 要发送的文案：逐行 push，空列表 = 不发 */\n")
        builder.append("\tresult: string[];\n")
        builder.append("\t/** 注册会话结果回调：发送结束后调用，参数是 { id, result, reason? } */\n")
        builder.append("\tregisterCallback(callback: (report: SparkSendReport) => void): void;\n")
        builder.append("\t/** 内置能力：日期时间 + 内置函数 */\n")
        builder.append("\tbuiltin: SparkBuiltin;\n")
        builder.append("\t/** 宿主数据：抖音给的会话信息 */\n")
        builder.append("\tio: SparkIo;\n")
        builder.append("\t/** 宏自己的变量与挂载查询 */\n")
        builder.append("\tmacro: SparkMacro;\n")
        builder.append("\t/** 系统接口：会话列表与宏挂载 */\n")
        builder.append("\tsystem: SparkSystem;\n")
        builder.append("\t/** 自定义函数（在「宏/函数」页定义） */\n")
        builder.append("\tfunctions: SparkFunctions;\n")
        builder.append("\t/** 模板变量（在模板编辑页的「变量」卡里定义） */\n")
        builder.append("\ttemplate: SparkTemplate;\n")
        builder.append("}\n")
        builder.append("declare var ctx: SparkCtx;\n")
        return builder.toString()
    }

    private fun params(function: UserFunction): String =
        function.paramList.mapIndexed { index, name ->
            if (index == function.paramList.lastIndex) "$name?: any" else "$name: any"
        }.joinToString(", ")

    /** 合法的 JS 标识符直接写名字，否则（含空格 / 连字符之类）用引号包起来。 */
    private fun property(name: String): String =
        if (IDENTIFIER.matches(name)) name else "\"" + escape(name).replace("\"", "\\\"") + "\""

    private val IDENTIFIER = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

    /** 声明里的说明文字不能带换行，也不能出现注释结束标记。 */
    private fun escape(text: String): String =
        text.replace("*/", "*\\/").replace('\n', ' ').replace('\r', ' ')
}
