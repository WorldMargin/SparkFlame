package com.worldmargin.autospark.app.script

import com.worldmargin.autospark.app.config.TemplateVar

/**
 * 模板变量的求值前导。
 *
 * 变量值是**一条 JS 表达式**：按定义顺序逐条求值，包成 `(function(){ return (表达式); })()`，
 * 因此能读到完整的 `ctx`，也能引用**前面已经算出来**的 `ctx.template.vars.xxx`。
 * 单条表达式出错只让该变量为 `null`（值是"空"的语义，和「没填」一致），不影响脚本本身。
 *
 * 表达式**单独占一行**（左右括号各在一行）：这样表达式末尾写 `// 注释`、
 * 或者写成跨多行的三元表达式都不会把收尾的 `);` 吃掉。
 */
object TemplateVarPrelude {
    fun of(variables: List<TemplateVar>): String {
        if (variables.isEmpty()) return ""
        val builder = StringBuilder("ctx.template = ctx.template || {};\nctx.template.vars = ctx.template.vars || {};\n")
        variables.forEach { variable ->
            if (variable.name.isBlank()) return@forEach
            val key = variable.name.replace("\\", "\\\\").replace("\"", "\\\"")
            // 表达式为空 = 没定义这个值：直接给 null（写 `return ()` 会是语法错，变成 undefined）
            if (variable.expr.isBlank()) {
                builder.append("ctx.template.vars[\"").append(key).append("\"] = null;\n")
                return@forEach
            }
            builder.append("try { ctx.template.vars[\"").append(key).append("\"] = (function(){ return (\n")
            builder.append(variable.expr)
            builder.append("\n); })(); } catch (e) { ctx.template.vars[\"").append(key).append("\"] = null; }\n")
        }
        return builder.toString()
    }
}
