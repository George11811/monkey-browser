package com.ridermonkey.userscript

/**
 * [UserscriptParser] 的解析结果。
 *
 * @property metadata 元数据块的解析结果；块存在但为空时，是 [UserscriptMetadata] 的全部默认值。
 * @property body 元数据块结束标记之后的正文原文。
 */
data class ParsedUserscript(
    val metadata: UserscriptMetadata,
    val body: String,
)

/**
 * UserScript 头部（元数据块）解析器。
 *
 * ## 支持的元数据块形式
 *
 * - 行注释形式：起始行是一个或多个斜杠加 `==UserScript==`，结束行是
 *   一个或多个斜杠加 `==/UserScript==`。
 * - 块注释形式：起始行把斜杠换成单斜杠加星号，结束行在结束标记之后补上
 *   块注释的收尾符号（形如 `// ==/UserScript==` 后接星号斜杠）。
 *
 * 解析器容忍：LF / CRLF / CR 三种换行、文件开头的 UTF-8 BOM、块内空行、
 * 行首多余空白、`@` 前多余的斜杠与空白、没有值的键（值记为空字符串）。
 *
 * ## 语义决策
 *
 * - **正文边界**：`body` 是结束标记所在行的换行符之后的全部内容，原样保留
 *   （不 trim 内部换行与行尾空白）。作为唯一例外，紧随元数据块之后的
 *   **第一个空行**会被丢弃：它在实际脚本里几乎总是排版用的分隔行，保留只会
 *   让每个调用方各写一遍 trimStart。只丢一个，不全丢。
 * - **单值字段首次优先**：name / namespace / version / description / run-at
 *   在重复声明时取**第一次**出现的值，后续同名字段只进 [UserscriptMetadata.raw]。
 * - **多值字段按序累加**：match / include / exclude / grant 按出现顺序追加。
 * - **键不区分大小写**：`@Name` 与 `@name` 等价；[UserscriptMetadata.raw]
 *   的键统一小写，值保持原文。
 * - **`grant none`**：只要 grant 里出现 none（不区分大小写），最终
 *   [UserscriptMetadata.grant] 规整为只含 none 的列表。none 不是 GM API 名，
 *   而是「不使用任何 GM API」的标记，调用方需要先据此判断再决定注不注入 API。
 * - **未知 `run-at`**：回落到 [RunAt.DOCUMENT_END]，原始文本仍留在
 *   [UserscriptMetadata.rawRunAt] 中。
 * - 找不到起始标记、或只有起始标记没有结束标记，都返回 null。
 */
object UserscriptParser {

    /** 元数据块起始标记：一个或多个斜杠加 ==UserScript==。 */
    private val START_MARKER = Regex("""^\s*(?://+|/\*+)\s*==UserScript==\s*(?:\*/)?\s*$""")

    /** 元数据块结束标记：可带行注释前缀，也可带块注释收尾符号。 */
    private val END_MARKER = Regex("""^\s*(?:(?://+|/\*+)\s*)?==/UserScript==\s*(?:\*/)?\s*$""")

    /** 元数据行前缀：一个或多个斜杠及其后的空白，容忍 `//   @name` 这种写法。 */
    private val COMMENT_PREFIX = Regex("""^/+\s*""")

    /** 元数据行的键值部分：键为非空白段，值可缺省，分隔符为一个及以上空格或制表符。 */
    private val KEY_VALUE = Regex("""^(\S+)(?:[ \t]+(.*))?$""")

    /**
     * 解析用户脚本源码。
     *
     * 返回 null 的两种情况：完全没有元数据块；只有起始标记而没有结束标记。
     * 元数据块存在但为空时，返回全默认值的元数据加上块之后的完整正文。
     */
    fun parse(source: String): ParsedUserscript? {
        // 去掉 UTF-8 BOM，否则带 BOM 文件的第一行会变成 "\uFEFF// ==UserScript==" 匹配不上。
        val text = source.removePrefix("\uFEFF")
        val lines = splitLines(text)

        val startIndex = lines.indexOfFirst { START_MARKER.matches(it.text) }
        if (startIndex < 0) return null

        var endIndex = -1
        for (i in startIndex + 1 until lines.size) {
            if (END_MARKER.matches(lines[i].text)) {
                endIndex = i
                break
            }
        }
        if (endIndex < 0) return null

        val raw = LinkedHashMap<String, MutableList<String>>()
        val match = ArrayList<String>()
        val include = ArrayList<String>()
        val exclude = ArrayList<String>()
    val excludeMatch = ArrayList<String>()
        val grant = ArrayList<String>()
    val connect = ArrayList<String>()
    val resource = ArrayList<String>()
        var name: String? = null
        var namespace: String? = null
        var version: String? = null
        var description: String? = null
        var rawRunAt: String? = null
        var noframes = false

        for (i in startIndex + 1 until endIndex) {
            val entry = parseMetadataLine(lines[i].text) ?: continue
            val key = entry.first.lowercase()
            val value = entry.second
            raw.getOrPut(key) { ArrayList() }.add(value)

            when (key) {
                "name" -> if (name == null) name = value
                "namespace" -> if (namespace == null) namespace = value
                "version" -> if (version == null) version = value
                "description" -> if (description == null) description = value
                "match" -> match.add(value)
                "include" -> include.add(value)
                "exclude" -> exclude.add(value)
                "exclude-match" -> excludeMatch.add(value)
                "grant" -> grant.add(value)
                "connect" -> connect.add(value)
                "resource" -> resource.add(value)
                "run-at" -> if (rawRunAt == null) rawRunAt = value
                "noframes" -> noframes = true
            }
        }

        // grant 里任何位置出现 none 都表示「不使用 GM API」，此时其它 grant 一并丢弃。
        val normalizedGrant = if (grant.any { it.equals("none", ignoreCase = true) }) {
            listOf("none")
        } else {
            grant.toList()
        }

        val bodyStart = bodyStartOffset(lines, endIndex)
        return ParsedUserscript(
            metadata = UserscriptMetadata(
                name = name,
                namespace = namespace,
                version = version,
                description = description,
                match = match.toList(),
                include = include.toList(),
                exclude = exclude.toList(),
                excludeMatch = excludeMatch.toList(),
                grant = normalizedGrant,
                connect = connect.toList(),
                resource = resource.toList(),
                runAt = parseRunAt(rawRunAt),
                rawRunAt = rawRunAt,
                noframes = noframes,
                raw = raw.mapValues { (_, values) -> values.toList() },
            ),
            body = text.substring(bodyStart),
        )
    }

    /**
     * 正文起点：结束标记所在行换行符之后。
     *
     * 若紧随其后的第一行是空行（只含空白），连同它的换行符一起跳过；
     * 只跳一个，不处理后面的空行。
     */
    private fun bodyStartOffset(lines: List<SourceLine>, endIndex: Int): Int {
        val next = lines.getOrNull(endIndex + 1)
        if (next != null && next.text.isBlank()) return next.end
        return lines[endIndex].end
    }

    /**
     * 解析一行元数据，返回原始键与值；不是元数据行时返回 null。
     *
     * 会先去掉行首空白与注释前缀（任意个数的斜杠及其后的空白），再容忍
     * 块注释里常见的装饰性前导星号。
     */
    private fun parseMetadataLine(line: String): Pair<String, String>? {
        var text = line.trim()
        if (text.isEmpty()) return null
        text = COMMENT_PREFIX.replaceFirst(text, "")
        if (text.startsWith("*")) text = text.substring(1).trimStart()
        if (!text.startsWith("@")) return null

        val parts = KEY_VALUE.find(text.substring(1)) ?: return null
        val key = parts.groupValues[1]
        if (key.isEmpty()) return null
        return key to parts.groupValues[2].trim()
    }

    /** run-at 取值解析；null 与未知值都回落到 [RunAt.DOCUMENT_END]。 */
    private fun parseRunAt(value: String?): RunAt = when (value?.trim()?.lowercase()) {
        "document-start" -> RunAt.DOCUMENT_START
        "document-end" -> RunAt.DOCUMENT_END
        "document-idle" -> RunAt.DOCUMENT_IDLE
        "document-body" -> RunAt.DOCUMENT_BODY
        else -> RunAt.DOCUMENT_END
    }

    /**
     * 按 LF / CRLF / CR 切行，并记录每行在原文中的起止偏移。
     *
     * [SourceLine.end] 包含该行的换行符，因此它同时是下一行的起点。
     */
    private fun splitLines(text: String): List<SourceLine> {
        val result = ArrayList<SourceLine>()
        var index = 0
        while (index < text.length) {
            var cursor = index
            while (cursor < text.length && text[cursor] != '\n' && text[cursor] != '\r') cursor++
            val contentEnd = cursor
            if (cursor < text.length) {
                cursor = if (text[cursor] == '\r' && cursor + 1 < text.length && text[cursor + 1] == '\n') {
                    cursor + 2
                } else {
                    cursor + 1
                }
            }
            result.add(SourceLine(text.substring(index, contentEnd), index, cursor))
            index = cursor
        }
        return result
    }

    /** 原文中的一行：文本不含换行符，[end] 指向换行符之后。 */
    private class SourceLine(val text: String, val start: Int, val end: Int)
}