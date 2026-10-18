package com.worldmargin.autospark.app.script

/**
 * 自定义函数的源码解析。
 *
 * 用户在函数编辑页里**直接写完整声明**，例如：
 *
 * ```js
 * function daysTo(date) {
 *   const target = new Date(date + "T00:00:00");
 *   return Math.ceil((target - new Date()) / 86400000);
 * }
 * ```
 *
 * 保存时这里负责：
 * - 取出函数名与形参（用于生成 `spark.d.ts` 的补全与文档）；
 * - **强制只含一个函数** —— 声明之后只允许空白、注释与分号，多写一个函数或多余语句都会被拒绝。
 */
object SparkFunctionSource {
    /** 解析结果：函数名、形参原文、规范化后的完整声明。 */
    data class Parsed(
        val name: String,
        val params: String,
        val source: String,
    )

    /** 解析成功给 [parsed]，失败给 [error]（给界面直接展示）。 */
    data class Result(
        val parsed: Parsed?,
        val error: String? = null,
    ) {
        val ok: Boolean get() = parsed != null
    }

    private val HEAD = Regex("""^(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(""")

    /** 解析一段「只含一个函数声明」的源码。 */
    fun parse(text: String): Result {
        val source = text.trim()
        if (source.isEmpty()) return Result(null, "内容为空，写一个 function 声明")

        val head = HEAD.find(source) ?: return Result(null, "要以 function 名字(形参) { … } 开头")
        if (head.range.first != 0) return Result(null, "函数声明前不要有别的内容")
        val name = head.groupValues[1]

        // 形参：从名字后面的 '(' 开始配对
        val openParen = source.indexOf('(', head.range.last)
        val closeParen = matchPair(source, openParen, '(', ')')
            ?: return Result(null, "形参括号没有闭合")
        val params = source.substring(openParen + 1, closeParen).trim()

        // 函数体：形参括号之后必须是 '{'
        val openBrace = source.indexOf('{', closeParen)
        if (openBrace < 0) return Result(null, "缺少函数体 { … }")
        if (source.substring(closeParen + 1, openBrace).isNotBlank()) {
            return Result(null, "形参括号后面直接跟 { 开始函数体")
        }
        val closeBrace = matchPair(source, openBrace, '{', '}')
            ?: return Result(null, "函数体花括号没有闭合")

        // 只允许一个函数：声明之后不许再有内容（空白、分号、注释除外）
        if (hasCodeAfter(source, closeBrace + 1)) {
            return Result(null, "只能包含一个函数，声明之后不要写别的代码")
        }

        val normalized = "function $name($params) " + source.substring(openBrace, closeBrace + 1)
        return Result(Parsed(name, params, normalized))
    }

    /**
     * 老数据兼容：早期版本只存「函数内部」，这里按名字与形参包成完整声明，
     * 让用户打开时看到的就是可以直接编辑的函数声明。
     */
    fun wrapLegacy(
        name: String,
        params: String,
        body: String,
    ): String = "function $name($params) {\n$body\n}"

    /** 文本是否已经是完整声明（老数据是纯函数体时返回 false）。 */
    fun looksLikeDeclaration(text: String): Boolean = HEAD.containsMatchIn(text.trim())

    /** 从 [open] 处配对括号，跳过字符串与注释；找不到返回 null。 */
    private fun matchPair(
        text: String,
        open: Int,
        left: Char,
        right: Char,
    ): Int? {
        if (open < 0 || open >= text.length || text[open] != left) return null
        var depth = 0
        var index = open
        while (index < text.length) {
            val char = text[index]
            when {
                char == '\'' || char == '"' || char == '`' -> {
                    index = skipString(text, index)
                    continue
                }

                char == '/' && index + 1 < text.length && text[index + 1] == '/' -> {
                    index = skipLineComment(text, index)
                    continue
                }

                char == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                    index = skipBlockComment(text, index)
                    continue
                }

                char == left -> depth++
                char == right -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    /** [from] 之后（跳过空白、分号与注释）是否还有真正的代码。 */
    private fun hasCodeAfter(
        text: String,
        from: Int,
    ): Boolean {
        var index = from
        while (index < text.length) {
            val char = text[index]
            when {
                char.isWhitespace() || char == ';' -> index++
                char == '/' && index + 1 < text.length && text[index + 1] == '/' ->
                    index = skipLineComment(text, index)

                char == '/' && index + 1 < text.length && text[index + 1] == '*' ->
                    index = skipBlockComment(text, index)

                else -> return true
            }
        }
        return false
    }

    private fun skipString(
        text: String,
        start: Int,
    ): Int {
        val quote = text[start]
        var index = start + 1
        while (index < text.length) {
            val char = text[index]
            if (char == '\\') {
                index += 2
                continue
            }
            if (char == quote) return index + 1
            index++
        }
        return text.length
    }

    private fun skipLineComment(
        text: String,
        start: Int,
    ): Int {
        var index = start
        while (index < text.length && text[index] != '\n') index++
        return index
    }

    private fun skipBlockComment(
        text: String,
        start: Int,
    ): Int {
        val end = text.indexOf("*/", start + 2)
        return if (end < 0) text.length else end + 2
    }
}
