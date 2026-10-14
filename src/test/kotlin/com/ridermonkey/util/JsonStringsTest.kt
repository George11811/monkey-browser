package com.ridermonkey.util

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonStringsTest {

    private val quote = String(charArrayOf('"'))
    private val backslash = String(charArrayOf(92.toChar()))

    @Test
    fun plainTextIsUnchanged() {
        assertEquals("abc", JsonStrings.escape("abc"))
        assertEquals("", JsonStrings.escape(""))
    }

    @Test
    fun quoteIsEscaped() {
        val escaped = JsonStrings.escape(quote)
        assertEquals(backslash + quote, escaped)
        assertEquals(2, escaped.length)
    }

    @Test
    fun backslashIsEscaped() {
        assertEquals(backslash + backslash, JsonStrings.escape(backslash))
    }

    @Test
    fun newlineIsEscaped() {
        assertEquals(backslash + "n", JsonStrings.escape(String(charArrayOf(10.toChar()))))
    }

    @Test
    fun tabIsEscaped() {
        assertEquals(backslash + "t", JsonStrings.escape(String(charArrayOf(9.toChar()))))
    }

    @Test
    fun controlCharsUseUnicodeEscape() {
        assertEquals(backslash + "u0001", JsonStrings.escape(String(charArrayOf(1.toChar()))))
    }

    @Test
    fun nonAsciiIsKeptAsIs() {
        assertEquals("中文", JsonStrings.escape("中文"))
    }

    @Test
    fun objectOfRawValuesKeepsRawJson() {
        // 值必须是合法 JSON 片段，会原样嵌入；这里用 {"a":2} 构造，全程避开源码里的转义字面量。
        val rawValue = String(charArrayOf('{', '"', 'a', '"', ':', '2', '}'))
        val json = JsonStrings.objectOfRawValues(linkedMapOf("k" to "1", "j" to rawValue))
        val expected = "{" + quote + "k" + quote + ":1," + quote + "j" + quote + ":" + rawValue + "}"
        assertEquals(expected, json)
    }

    @Test
    fun objectOfRawValuesEscapesKeys() {
        val key = "a" + quote + "b"
        val json = JsonStrings.objectOfRawValues(linkedMapOf(key to "1"))
        assertEquals("{" + quote + "a" + backslash + quote + "b" + quote + ":1}", json)
    }

    @Test
    fun objectOfRawValuesHandlesUnicodeKeys() {
        val json = JsonStrings.objectOfRawValues(linkedMapOf("键" to "1"))
        assertEquals("{" + quote + "键" + quote + ":1}", json)
    }

    @Test
    fun emptyObjectIsEmptyBraces() {
        assertEquals("{}", JsonStrings.objectOfRawValues(emptyMap()))
    }
}
