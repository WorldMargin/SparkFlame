package com.worldmargin.autospark.features.autospark.ui

/**
 * 把文档 Markdown 渲染成 HTML（够用即可：标题、代码块、表格、列表、引用、分隔线、
 * 粗体/斜体/行内代码/链接）。文档由模块自带，语法可控，因此不引入第三方渲染库。
 */
object Markdown {
    fun toHtml(markdown: String): String {
        val out = StringBuilder()
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var index = 0
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            out.append("<p>").append(inline(paragraph.joinToString(" "))).append("</p>\n")
            paragraph.clear()
        }

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            when {
                // 代码块
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    val language = trimmed.removePrefix("```").trim()
                    val code = StringBuilder()
                    index++
                    while (index < lines.size && !lines[index].trim().startsWith("```")) {
                        code.append(lines[index]).append('\n')
                        index++
                    }
                    out.append("<pre><code")
                    if (language.isNotEmpty()) out.append(" class=\"language-").append(escape(language)).append('"')
                    out.append('>').append(escape(code.toString())).append("</code></pre>\n")
                }
                // 表格：表头 + 分隔行 + 数据行
                trimmed.startsWith("|") && index + 1 < lines.size && isSeparator(lines[index + 1]) -> {
                    flushParagraph()
                    val header = cells(trimmed)
                    index += 2
                    val rows = mutableListOf<List<String>>()
                    while (index < lines.size && lines[index].trim().startsWith("|")) {
                        rows += cells(lines[index].trim())
                        index++
                    }
                    index--
                    out.append("<table><thead><tr>")
                    header.forEach { out.append("<th>").append(inline(it)).append("</th>") }
                    out.append("</tr></thead><tbody>")
                    rows.forEach { row ->
                        out.append("<tr>")
                        row.forEach { out.append("<td>").append(inline(it)).append("</td>") }
                        out.append("</tr>")
                    }
                    out.append("</tbody></table>\n")
                }
                // 标题
                Regex("^#{1,6}\\s+.*$").matches(trimmed) -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length
                    out.append("<h").append(level).append('>')
                        .append(inline(trimmed.drop(level).trim()))
                        .append("</h").append(level).append(">\n")
                }
                trimmed == "---" || trimmed == "***" -> {
                    flushParagraph()
                    out.append("<hr>\n")
                }
                // 引用
                trimmed.startsWith(">") -> {
                    flushParagraph()
                    val quote = mutableListOf<String>()
                    while (index < lines.size && lines[index].trim().startsWith(">")) {
                        quote += lines[index].trim().removePrefix(">").trim()
                        index++
                    }
                    index--
                    out.append("<blockquote>").append(inline(quote.joinToString(" "))).append("</blockquote>\n")
                }
                // 无序列表
                Regex("^[-*]\\s+.*$").matches(trimmed) -> {
                    flushParagraph()
                    out.append("<ul>")
                    while (index < lines.size && Regex("^[-*]\\s+.*$").matches(lines[index].trim())) {
                        out.append("<li>").append(inline(lines[index].trim().drop(2).trim())).append("</li>")
                        index++
                    }
                    index--
                    out.append("</ul>\n")
                }
                // 有序列表
                Regex("^\\d+\\.\\s+.*$").matches(trimmed) -> {
                    flushParagraph()
                    out.append("<ol>")
                    while (index < lines.size && Regex("^\\d+\\.\\s+.*$").matches(lines[index].trim())) {
                        out.append("<li>")
                            .append(inline(lines[index].trim().substringAfter('.').trim()))
                            .append("</li>")
                        index++
                    }
                    index--
                    out.append("</ol>\n")
                }
                trimmed.isEmpty() -> flushParagraph()
                else -> paragraph += trimmed
            }
            index++
        }
        flushParagraph()
        return out.toString()
    }

    private fun isSeparator(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("|")) return false
        return cells(trimmed).all { cell -> cell.isNotEmpty() && cell.all { it == '-' || it == ':' } }
    }

    private fun cells(row: String): List<String> =
        row.trim().trim('|').split('|').map { it.trim() }

    private fun escape(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    /** 行内标记：先把行内代码抠出来，避免里面的符号被当成粗体/链接。 */
    private fun inline(text: String): String {
        val codes = mutableListOf<String>()
        var body = Regex("`([^`]+)`").replace(text) { match ->
            codes += match.groupValues[1]
            "\u0000${codes.size - 1}\u0000"
        }
        body = escape(body)
        body =
            Regex("\\*\\*([^*]+)\\*\\*")
                .replace(body) { "<strong>${it.groupValues[1]}</strong>" }
        body = Regex("(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])").replace(body) { "<em>${it.groupValues[1]}</em>" }
        body = Regex("\\[([^\\]]+)]\\(([^)]+)\\)").replace(body) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        codes.forEachIndexed { position, code ->
            body = body.replace("\u0000$position\u0000", "<code>" + escape(code) + "</code>")
        }
        return body
    }
}
