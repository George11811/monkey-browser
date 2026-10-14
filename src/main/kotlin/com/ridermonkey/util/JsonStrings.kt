package com.ridermonkey.util

/**
 * 最小 JSON/JS 字符串转义工具。
 *
 * 刻意不在源码里写反斜杠字面量，而用字符码构造，避免转义层级出错；
 * 单元测试覆盖引号、反斜杠、控制字符与非 ASCII。
 */
object JsonStrings {

    private val QUOTE: Char = '"'
    private val BACKSLASH: Char = 92.toChar()
    private val NEWLINE: Char = 10.toChar()
    private val CARRIAGE: Char = 13.toChar()
    private val TAB: Char = 9.toChar()
    private val FORM_FEED: Char = 12.toChar()
    private val BACKSPACE: Char = 8.toChar()

    /** 转义成可放进 JSON/JS 双引号字符串里的内容（不含两侧引号）。 */
    fun escape(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            when (ch) {
                QUOTE -> sb.append(BACKSLASH).append(QUOTE)
                BACKSLASH -> sb.append(BACKSLASH).append(BACKSLASH)
                NEWLINE -> sb.append(BACKSLASH).append('n')
                CARRIAGE -> sb.append(BACKSLASH).append('r')
                TAB -> sb.append(BACKSLASH).append('t')
                FORM_FEED -> sb.append(BACKSLASH).append('f')
                BACKSPACE -> sb.append(BACKSLASH).append('b')
                else -> if (ch.code < 32) {
                    sb.append(BACKSLASH).append('u').append(String.format("%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    /** 把字符串包成合法的 JSON 字符串字面量（含两侧引号）。 */
    fun quoted(text: String): String = QUOTE + escape(text) + QUOTE

    /** 把键值对拼成 JSON 对象字面量；值必须是**已经合法的 JSON 片段**（原样嵌入）。 */
    fun objectOfRawValues(entries: Map<String, String>): String {
        val sb = StringBuilder(64)
        sb.append('{')
        var first = true
        for ((key, rawValue) in entries) {
            if (!first) sb.append(',')
            first = false
            sb.append(QUOTE).append(escape(key)).append(QUOTE).append(':').append(rawValue)
        }
        sb.append('}')
        return sb.toString()
    }
}