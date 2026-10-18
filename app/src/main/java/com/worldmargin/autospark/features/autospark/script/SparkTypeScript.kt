package com.worldmargin.autospark.features.autospark.script

import com.worldmargin.autospark.features.autospark.model.SparkConfig
import com.worldmargin.autospark.features.autospark.model.TemplateVar
import com.worldmargin.autospark.features.autospark.model.UserFunction

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
    /** 脚本结果变量：引擎读取它作为要发送的文案；**统一按列表**，用 `.push()` 逐行添加。 */
    private const val RESULT_DECL = "declare var \$spark_result\$: string[];"

    fun dts(
        config: SparkConfig,
        templateVars: List<TemplateVar> = emptyList(),
    ): String {
        val builder = StringBuilder()
        builder.append("// 由模块生成：上下文对象与自定义函数声明\n")
        builder.append(RESULT_DECL).append('\n')
        builder.append("interface SparkFunctions {\n")
        config.functions.forEach { function ->
            builder.append("\t/** ").append(escape(function.desc.ifBlank { "自定义函数" })).append(" */\n")
            builder.append("\t").append(function.name).append('(').append(params(function)).append("): any;\n")
        }
        builder.append("}\n")
        builder.append("interface SparkTemplateParent {\n")
        builder.append("\t/** 父模板 id（空白模板为 builtin:blank） */\n")
        builder.append("\tid: string;\n")
        builder.append("\t/** 父模板的派生列表 */\n")
        builder.append("\tchilds: Array<{ id: string; name: string }>;\n")
        builder.append("}\n")
        builder.append("interface SparkTemplate {\n")
        builder.append("\t/** 当前生效的模板名 */\n")
        builder.append("\t").append(SparkScriptEngine.VAR_TEMPLATE).append(": string;\n")
        builder.append("\t/** 父模板名（没有父模板时为空串） */\n")
        builder.append("\t").append(SparkScriptEngine.VAR_PARENT_TEMPLATE).append(": string;\n")
        builder.append("\t/** 当前模板（会话模板 / 模板）的 id */\n")
        builder.append("\tthisid: string;\n")
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
        builder.append("interface SparkBuiltin {\n")
        builder.append("interface SparkVars {\n")
        builder.append("\t/** 变量名 → 求值结果；没声明的名字也能读（值为 undefined） */\n")
        builder.append("\t[key: string]: any;\n")
        unique.values.forEach { variable ->
            builder.append("\t/** 变量：").append(escape(variable.name)).append(" */\n")
            builder.append("\t").append(property(variable.name)).append(": any;\n")
        }
        builder.append("}\n")
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
        builder.append("interface SparkSandbox {\n")
        SparkScriptEngine.SANDBOX_FUNCTIONS.forEach { function ->
            builder.append("\t/** ").append(escape(function.desc)).append(" */\n")
            builder.append("\t").append(function.name).append('(').append(function.params).append("): any;\n")
        }
        builder.append("}\n")
        builder.append("interface SparkSystem {\n")
        SparkScriptEngine.SYSTEM_FUNCTIONS.forEach { function ->
            builder.append("\t/** ").append(escape(function.desc)).append(" */\n")
            builder.append("\t").append(function.name).append('(').append(function.params).append("): ")
                .append(function.type).append(";\n")
        }
        builder.append("}\n")
        builder.append("interface SparkCtx {\n")
        builder.append("\t/** 内置能力：日期时间 + 内置函数 */\n")
        builder.append("\tbuiltin: SparkBuiltin;\n")
        builder.append("\t/** 宿主数据：抖音给的会话信息 */\n")
        builder.append("\tio: SparkIo;\n")
        builder.append("\t/** 沙盒变量：只在宏里有（模板里为 undefined） */\n")
        builder.append("\tsandbox?: SparkSandbox;\n")
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
