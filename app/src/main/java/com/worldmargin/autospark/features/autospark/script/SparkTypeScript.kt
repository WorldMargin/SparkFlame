package com.worldmargin.autospark.features.autospark.script

import com.worldmargin.autospark.features.autospark.model.SparkConfig
import com.worldmargin.autospark.features.autospark.model.UserFunction

/**
 * 生成 Monaco 用的 TypeScript 类型定义（`spark.d.ts`）。
 *
 * 编辑器把这份声明通过 `addExtraLib` 注入，于是脚本里写 `ctx.` 时能拿到变量、内置函数
 * 以及用户自定义函数的**类型与说明**，JavaScript 内置对象的成员则由 Monaco 自带的
 * TypeScript 语言服务提供。
 */
object SparkTypeScript {
    /** 脚本结果变量：引擎读取它作为要发送的文案；**统一按列表**，用 `.push()` 逐行添加。 */
    private const val RESULT_DECL = "declare var \$spark_result\$: string[];"

    fun dts(config: SparkConfig): String {
        val builder = StringBuilder()
        builder.append("// 由模块生成：上下文对象与自定义函数声明\n")
        builder.append(RESULT_DECL).append('\n')
        builder.append("interface SparkFunctions {\n")
        config.functions.forEach { function ->
            builder.append("\t/** ").append(escape(function.desc.ifBlank { "自定义函数" })).append(" */\n")
            builder.append("\t").append(function.name).append('(').append(params(function)).append("): any;\n")
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
        builder.append("}\n")
        builder.append("declare var ctx: SparkCtx;\n")
        return builder.toString()
    }

    private fun params(function: UserFunction): String =
        function.paramList.mapIndexed { index, name ->
            if (index == function.paramList.lastIndex) "$name?: any" else "$name: any"
        }.joinToString(", ")

    /** 声明里的说明文字不能带换行，也不能出现注释结束标记。 */
    private fun escape(text: String): String =
        text.replace("*/", "*\\/").replace('\n', ' ').replace('\r', ' ')
}
