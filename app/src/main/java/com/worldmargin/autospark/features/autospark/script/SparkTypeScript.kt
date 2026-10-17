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
        builder.append("interface SparkTemplate {\n")
        builder.append("\t/** 变量名 → 求值结果；没声明的名字也能读（值为 undefined） */\n")
        builder.append("\t[key: string]: any;\n")
        // 同名只声明一次（会话自己的变量排在后面，所以后者覆盖）
        val unique = LinkedHashMap<String, TemplateVar>()
        templateVars.filter { it.name.isNotBlank() }.forEach { unique[it.name] = it }
        unique.values.forEach { variable ->
            builder.append("\t/** 模板变量：").append(escape(variable.name)).append(" */\n")
            builder.append("\t").append(property(variable.name)).append(": any;\n")
        }
        builder.append("}\n")
        builder.append("interface SparkCtx {\n")
        SparkScriptEngine.VARIABLES.forEach { variable ->
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
