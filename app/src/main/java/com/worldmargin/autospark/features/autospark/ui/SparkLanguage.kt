package com.worldmargin.autospark.features.autospark.ui

import android.os.Bundle
import com.worldmargin.autospark.features.autospark.model.UserFunction
import com.worldmargin.autospark.features.autospark.script.SparkScriptEngine
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** 给编辑器装上文案脚本支持：JS 高亮 + 宏/变量/内置对象补全。 */
fun CodeEditor.setupSparkEditing(userFunctions: List<UserFunction> = emptyList()) {
    setEditorLanguage(SparkLanguage(userFunctions))
    // 自带组件，默认已存在，这里显式打开自动补全
    getComponent(EditorAutoCompletion::class.java).setEnabled(true)
}

/** 手动弹出补全（自动补全的兜底入口）。 */
fun CodeEditor.showSparkCompletion() {
    getComponent(EditorAutoCompletion::class.java)?.requireCompletion()
}

/**
 * 文案脚本的语言支持：JS 风格高亮 + 上下文补全。
 *
 * 补全范围（尽量对齐 VSCode 的体验）：
 * - `ctx.` → ctx 的变量 / 内置函数 / `functions`；
 * - `ctx.functions.` → 用户在「宏/函数」页定义的函数；
 * - `Math.` / `JSON.` / `Object.` / `Array.` / `String.` / `Number.` / `Date.` … → JS 内置成员
 *   （用 Rhino 反射真实内置对象，不写死表，随引擎版本自动准确）；
 * - 普通位置 → 脚本里已声明的变量 / 形参 / 函数名 + 关键字 + `ctx` / `$spark_result$`。
 */
class SparkLanguage(
    private val userFunctions: List<UserFunction> = emptyList(),
) : EmptyLanguage() {
    private val analyzer = SparkAnalyzer()

    override fun getAnalyzeManager(): AnalyzeManager = analyzer

    override fun getInterruptionLevel(): Int = Language.INTERRUPTION_LEVEL_NONE

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        val line = content.getLine(position.line)
        val end = position.column.coerceIn(0, line.length)
        var start = end
        while (start > 0 && isIdentifierChar(line[start - 1])) start--
        val prefix = line.substring(start, end)
        val receiver = receiverBefore(line, start)

        val candidates: List<Candidate> =
            when (receiver) {
                // `ctx.` 之后：补全 ctx 的成员（含自定义函数挂载点）
                SparkScriptEngine.CTX -> ctxMembers()
                // `ctx.functions.` 之后：补全用户自定义函数
                SparkScriptEngine.FUNCTIONS_OBJ -> functionCandidates()
                // 普通位置：脚本里已声明的变量 + ctx/关键字等
                null -> localCandidates(content, position, end) + rootCandidates()
                // JS 内置对象（Math. / JSON. / …）：反射出来的成员
                else -> JsBuiltins.members[receiver].orEmpty()
            }
        for (candidate in candidates) {
            if (prefix.isNotEmpty() && !candidate.label.startsWith(prefix)) continue
            publisher.addItem(
                SimpleCompletionItem(candidate.label, candidate.desc, prefix.length, candidate.commit),
            )
        }
    }

    /** 用户自定义函数：`ctx.functions.<名字>(形参)`。 */
    private fun functionCandidates(): List<Candidate> =
        userFunctions
            .filter { it.name.isNotBlank() }
            .map {
                Candidate(
                    "${it.name}(${it.paramList.joinToString(", ")})",
                    it.desc.ifBlank { "自定义函数" },
                    "${it.name}()",
                )
            }

    private fun ctxMembers(): List<Candidate> =
        SparkScriptEngine.FUNCTIONS.map {
            Candidate(if (it.isFunction) "${it.name}(${it.params})" else it.name, it.desc, "${it.name}()")
        } + SparkScriptEngine.VARIABLES.map { Candidate(it.name, it.desc, it.name) } +
            Candidate(
                SparkScriptEngine.FUNCTIONS_OBJ,
                "自定义函数（在「宏/函数」页定义）",
                SparkScriptEngine.CTX_DOT + SparkScriptEngine.FUNCTIONS_OBJ + ".",
            )

    /** 光标之前脚本里声明过的变量、函数名与函数形参（VSCode 那样的局部补全）。 */
    private fun localCandidates(
        content: ContentReference,
        position: CharPosition,
        lineEnd: Int,
    ): List<Candidate> {
        val head = StringBuilder()
        for (i in 0..position.line) {
            val line = content.getLine(i)
            head.append(if (i == position.line) line.take(lineEnd) else line).append('\n')
        }
        val script = head.toString()
        val names = linkedSetOf<String>()
        DECL.forEach { regex -> regex.findAll(script).forEach { names += it.groupValues[1] } }
        PARAM.forEach { regex ->
            regex.findAll(script).forEach { match ->
                names += paramNames(match.groupValues[1].ifEmpty { match.groupValues[2] })
            }
        }
        return names.map { Candidate(it, "脚本里声明的变量", it) }
    }

    private fun paramNames(list: String): List<String> =
        list.split(',').map { it.substringBefore('=').trim() }.filter { IDENTIFIER.matches(it) }

    private fun rootCandidates(): List<Candidate> =
        listOf(
            Candidate(SparkScriptEngine.CTX, "脚本上下文：变量和内置函数都在里面", SparkScriptEngine.CTX),
            Candidate(
                SparkScriptEngine.RESULT_VAR,
                "把要发送的文案赋给它",
                SparkScriptEngine.RESULT_VAR,
            ),
        ) +
            KEYWORDS.map { Candidate(it, "关键字", it) } +
            BUILTINS.map { Candidate(it, "JavaScript 内置对象", it) }

    /** 点号左边那个标识符；没有点号时返回 null。 */
    private fun receiverBefore(
        line: String,
        wordStart: Int,
    ): String? {
        if (wordStart == 0 || line[wordStart - 1] != '.') return null
        var end = wordStart - 1
        var start = end
        while (start > 0 && isIdentifierChar(line[start - 1])) start--
        return line.substring(start, end).ifEmpty { null }
    }

    private data class Candidate(
        val label: String,
        val desc: String,
        val commit: String,
    )

    /**
     * JS 内置对象成员（Math / JSON / Object / Array / String / Number / Date / Boolean / RegExp）。
     *
     * 用 Rhino **反射真实内置对象**，而不是写死成员表：引擎或语言版本变化都会自动跟上。
     * 只反射一次（[members] 是 lazy），每个对象取「自身 + 原型」的 id。
     */
    private object JsBuiltins {
        private val receivers =
            listOf("Math", "JSON", "Object", "Array", "String", "Number", "Date", "Boolean", "RegExp")

        val members: Map<String, List<Candidate>> by lazy { introspect() }

        private fun introspect(): Map<String, List<Candidate>> {
            val cx = Context.enter()
            return try {
                cx.optimizationLevel = -1
                val scope = cx.initStandardObjects()
                receivers
                    .mapNotNull { name ->
                        val target = ScriptableObject.getProperty(scope, name) as? Scriptable ?: return@mapNotNull null
                        val proto = ScriptableObject.getProperty(target, "prototype") as? Scriptable
                        // 自身成员（Math.ceil 之类）+ 原型成员（"abc".split 之类）
                        val ids = linkedSetOf<String>()
                        runCatching { target.ids?.forEach { id -> ids += id.toString() } }
                        runCatching { proto?.ids?.forEach { id -> ids += id.toString() } }
                        val list =
                            ids
                                .filter { it.isNotBlank() }
                                .sorted()
                                .map { id ->
                                    val value =
                                        runCatching { ScriptableObject.getProperty(target, id) }.getOrNull()
                                            ?: runCatching { proto?.let { ScriptableObject.getProperty(it, id) } }.getOrNull()
                                    val isFunction = value is Function
                                    Candidate(
                                        label = if (isFunction) "$id()" else id,
                                        desc = if (isFunction) "JS 内置方法" else "JS 内置属性",
                                        commit = id,
                                    )
                                }
                        name to list
                    }.toMap()
            } finally {
                Context.exit()
            }
        }
    }

    private companion object {
        val KEYWORDS =
            setOf(
                "var", "let", "const", "function", "return", "if", "else", "for", "while", "do",
                "break", "continue", "true", "false", "null", "undefined", "new", "typeof", "in", "of",
            )
        val BUILTINS =
            setOf("Math", "JSON", "Date", "String", "Number", "Array", "Object", "parseInt", "parseFloat", "isNaN")
        val FUNCTION_NAMES = SparkScriptEngine.FUNCTIONS.map { it.name }.toSet()
        val VARIABLE_NAMES = SparkScriptEngine.VARIABLES.map { it.name }.toSet()

        /** 变量/函数声明。 */
        val DECL =
            listOf(
                Regex("""\b(?:var|let|const)\s+([A-Za-z_$][\w$]*)"""),
                Regex("""\bfunction\s+([A-Za-z_$][\w$]*)"""),
            )

        /** 函数形参：function f(a, b) / (a, b) => / a =>。 */
        val PARAM =
            listOf(
                Regex("""\bfunction(?:\s+[A-Za-z_$][\w$]*)?\s*\(([^)]*)\)"""),
                Regex("""(?:\(([^)]*)\)|([A-Za-z_$][\w$]*))\s*=>"""),
            )
        val IDENTIFIER = Regex("""[A-Za-z_$][\w$]*""")

        fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
    }

    /** 逐行词法着色（脚本很短，同步分析即可）。 */
    private class SparkAnalyzer : AnalyzeManager {
        private var receiver: StyleReceiver? = null
        private var reference: ContentReference? = null

        override fun setReceiver(receiver: StyleReceiver?) {
            this.receiver = receiver
        }

        override fun reset(
            content: ContentReference,
            extraArguments: Bundle,
        ) {
            reference = content
            analyze()
        }

        override fun insert(
            start: CharPosition,
            end: CharPosition,
            inserted: CharSequence,
        ) = analyze()

        override fun delete(
            start: CharPosition,
            end: CharPosition,
            deleted: CharSequence,
        ) = analyze()

        override fun rerun() = analyze()

        override fun destroy() {
            reference = null
            receiver = null
        }

        private fun analyze() {
            val receiver = receiver ?: return
            val content = reference ?: return
            val lineCount = content.lineCount
            val builder = MappedSpans.Builder(lineCount)
            for (line in 0 until lineCount) {
                highlightLine(content.getLine(line)).forEach { span -> builder.add(line, span) }
                builder.determine(line)
            }
            builder.addNormalIfNull()
            receiver.setStyles(this, Styles(builder.build()))
        }

        private fun highlightLine(line: String): List<Span> {
            val spans = ArrayList<Span>()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                        spans += SpanFactory.obtain(i, style(EditorColorScheme.COMMENT))
                        i = line.length
                    }

                    c == '"' || c == '\'' -> {
                        val start = i++
                        while (i < line.length && line[i] != c) {
                            if (line[i] == '\\') i++
                            i++
                        }
                        if (i < line.length) i++
                        spans += SpanFactory.obtain(start, style(EditorColorScheme.LITERAL))
                    }

                    c.isDigit() -> {
                        val start = i
                        while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '.')) i++
                        spans += SpanFactory.obtain(start, style(EditorColorScheme.LITERAL))
                    }

                    isIdentifierChar(c) -> {
                        val start = i
                        while (i < line.length && isIdentifierChar(line[i])) i++
                        spans += SpanFactory.obtain(start, style(colorOf(line.substring(start, i))))
                    }

                    else -> i++
                }
            }
            return spans
        }

        private fun colorOf(word: String): Int =
            when {
                word in KEYWORDS -> EditorColorScheme.KEYWORD
                word in FUNCTION_NAMES -> EditorColorScheme.FUNCTION_NAME
                word == SparkScriptEngine.RESULT_VAR || word == SparkScriptEngine.CTX || word in VARIABLE_NAMES ->
                    EditorColorScheme.IDENTIFIER_VAR
                else -> EditorColorScheme.TEXT_NORMAL
            }

        private fun style(colorId: Int): Long = TextStyle.makeStyle(colorId)
    }
}
