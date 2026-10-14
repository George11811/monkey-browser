package com.ridermonkey.userscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserscriptParserTest {

    private fun script(vararg lines: String): String = lines.joinToString("\n") + "\n"

    @Test
    fun parsesStandardScript() {
        val source = script(
            "// ==UserScript==",
            "// @name         Example Script",
            "// @namespace    https://example.com/ns",
            "// @version      1.2.3",
            "// @description  A demo script",
            "// @match        https://example.com/*",
            "// @match        https://*.example.org/*",
            "// @include      https://included.test/*",
            "// @exclude      https://example.com/private*",
            "// @grant        GM_setValue",
            "// @grant        GM_getValue",
            "// @run-at       document-start",
            "// @noframes",
            "// ==/UserScript==",
            "",
            "console.log(\"hi\");"
        )
        val parsed = requireNotNull(UserscriptParser.parse(source))
        val metadata = parsed.metadata

        assertEquals("Example Script", metadata.name)
        assertEquals("https://example.com/ns", metadata.namespace)
        assertEquals("1.2.3", metadata.version)
        assertEquals("A demo script", metadata.description)
        assertEquals(listOf("https://example.com/*", "https://*.example.org/*"), metadata.match)
        assertEquals(listOf("https://included.test/*"), metadata.include)
        assertEquals(listOf("https://example.com/private*"), metadata.exclude)
        assertEquals(listOf("GM_setValue", "GM_getValue"), metadata.grant)
        assertEquals(RunAt.DOCUMENT_START, metadata.runAt)
        assertEquals("document-start", metadata.rawRunAt)
        assertTrue(metadata.noframes)
        assertEquals("console.log(\"hi\");\n", parsed.body)
    }

    @Test
    fun crlfLineEndingsAreTolerated() {
        val source = "// ==UserScript==\r\n" +
            "// @name CrLf\r\n" +
            "// @match https://crlf.test/*\r\n" +
            "// ==/UserScript==\r\n" +
            "var a = 1;\r\n"
        val parsed = requireNotNull(UserscriptParser.parse(source))
        assertEquals("CrLf", parsed.metadata.name)
        assertEquals(listOf("https://crlf.test/*"), parsed.metadata.match)
        assertEquals("var a = 1;\r\n", parsed.body)
    }

    @Test
    fun leadingBomIsIgnored() {
        val source = "\uFEFF" + script(
            "// ==UserScript==",
            "// @name Bom",
            "// ==/UserScript==",
            "body();"
        )
        val parsed = requireNotNull(UserscriptParser.parse(source))
        assertEquals("Bom", parsed.metadata.name)
        assertEquals("body();\n", parsed.body)
    }

    @Test
    fun blockCommentFormIsSupported() {
        val source = script(
            "/* ==UserScript==",
            "// @name Block Form",
            "// @match https://block.test/*",
            "// ==/UserScript== */",
            "run();"
        )
        val parsed = requireNotNull(UserscriptParser.parse(source))
        assertEquals("Block Form", parsed.metadata.name)
        assertEquals(listOf("https://block.test/*"), parsed.metadata.match)
        assertEquals("run();\n", parsed.body)
    }

    @Test
    fun blockCommentAllowsLinesWithoutSlashes() {
        val source = script(
            "/* ==UserScript==",
            "@name Bare Block",
            "@match https://bare.test/*",
            "==/UserScript== */",
            "bare();"
        )
        val parsed = requireNotNull(UserscriptParser.parse(source))
        assertEquals("Bare Block", parsed.metadata.name)
        assertEquals(listOf("https://bare.test/*"), parsed.metadata.match)
        assertEquals("bare();\n", parsed.body)
    }

    @Test
    fun missingMetadataBlockReturnsNull() {
        assertNull(UserscriptParser.parse("console.log(1);\n"))
        assertNull(UserscriptParser.parse("// @name No Block\n"))
    }

    @Test
    fun endMarkerBeforeStartMarkerIsIgnored() {
        assertNull(UserscriptParser.parse(script("// ==/UserScript==", "// ==UserScript==", "code();")))
    }

    @Test
    fun unterminatedMetadataBlockReturnsNull() {
        assertNull(UserscriptParser.parse(script("// ==UserScript==", "// @name X", "code();")))
    }

    @Test
    fun metadataKeysAreCaseInsensitive() {
        val source = script(
            "// ==UserScript==",
            "// @Name Mixed Case",
            "// @MATCH https://case.test/*",
            "// @Include https://case.test/inc",
            "// @EXCLUDE https://case.test/out",
            "// @Grant GM_log",
            "// @RUN-AT document-idle",
            "// @NoFrames",
            "// ==/UserScript==",
            "code();"
        )
        val metadata = requireNotNull(UserscriptParser.parse(source)).metadata
        assertEquals("Mixed Case", metadata.name)
        assertEquals(listOf("https://case.test/*"), metadata.match)
        assertEquals(listOf("https://case.test/inc"), metadata.include)
        assertEquals(listOf("https://case.test/out"), metadata.exclude)
        assertEquals(listOf("GM_log"), metadata.grant)
        assertEquals(RunAt.DOCUMENT_IDLE, metadata.runAt)
        assertTrue(metadata.noframes)
    }

    @Test
    fun multiValueFieldsAccumulateInOrder() {
        val source = script(
            "// ==UserScript==",
            "// @match https://a.test/*",
            "// @match https://b.test/*",
            "// @match https://c.test/*",
            "// @include https://b.test/inc1",
            "// @include https://b.test/inc2",
            "// @grant GM_a",
            "// @grant GM_b",
            "// ==/UserScript==",
            "code();"
        )
        val metadata = requireNotNull(UserscriptParser.parse(source)).metadata
        assertEquals(listOf("https://a.test/*", "https://b.test/*", "https://c.test/*"), metadata.match)
        assertEquals(listOf("https://b.test/inc1", "https://b.test/inc2"), metadata.include)
        assertEquals(listOf("GM_a", "GM_b"), metadata.grant)
    }

    @Test
    fun singleValueFieldsKeepFirstOccurrence() {
        val source = script(
            "// ==UserScript==",
            "// @name First",
            "// @name Second",
            "// @namespace ns1",
            "// @namespace ns2",
            "// @version 1",
            "// @version 2",
            "// @description d1",
            "// @description d2",
            "// @run-at document-start",
            "// @run-at document-idle",
            "// ==/UserScript==",
            "code();"
        )
        val metadata = requireNotNull(UserscriptParser.parse(source)).metadata
        assertEquals("First", metadata.name)
        assertEquals("ns1", metadata.namespace)
        assertEquals("1", metadata.version)
        assertEquals("d1", metadata.description)
        assertEquals(RunAt.DOCUMENT_START, metadata.runAt)
        assertEquals("document-start", metadata.rawRunAt)
        // 被忽略的重复值仍完整保留在 raw 里，便于调试。
        assertEquals(listOf("First", "Second"), metadata.raw["name"])
        assertEquals(listOf("document-start", "document-idle"), metadata.raw["run-at"])
    }

    @Test
    fun grantNoneMeansNoGmApis() {
        val source = script(
            "// ==UserScript==",
            "// @grant none",
            "// ==/UserScript==",
            "code();"
        )
        val metadata = requireNotNull(UserscriptParser.parse(source)).metadata
        assertEquals(listOf("none"), metadata.grant)
    }

    @Test
    fun grantNoneCollapsesOtherGrants() {
        val source = script(
            "// ==UserScript==",
            "// @grant GM_setValue",
            "// @grant None",
            "// ==/UserScript==",
            "code();"
        )
        val metadata = requireNotNull(UserscriptParser.parse(source)).metadata
        assertEquals(listOf("none"), metadata.grant)
        assertEquals(listOf("GM_setValue", "None"), metadata.raw["grant"])
    }

    @Test
    fun keyWithoutValueBecomesEmptyString() {
        val source = script(
            "// ==UserScript==",
            "// @version",
            "// @name",
            "// @noframes   ",
            "// ==/UserScript==",
            "code();"
        )
        val metadata = requireNotNull(UserscriptParser.parse(source)).metadata
        assertEquals("", metadata.version)
        assertEquals("", metadata.name)
        assertTrue(metadata.noframes)
        assertEquals(listOf(""), metadata.raw["version"])
    }

    @Test
    fun bodyIsNotRescannedForMarkers() {
        val source = script(
            "// ==UserScript==",
            "// @name Boundary",
            "// ==/UserScript==",
            "var marker = \"==UserScript==\";",
            "// ==/UserScript==",
            "tail();"
        )
        val parsed = requireNotNull(UserscriptParser.parse(source))
        assertEquals("Boundary", parsed.metadata.name)
        assertEquals("var marker = \"==UserScript==\";\n// ==/UserScript==\ntail();\n", parsed.body)
    }

    @Test
    fun emptyMetadataBlockYieldsDefaults() {
        val source = script("// ==UserScript==", "// ==/UserScript==", "body();")
        val parsed = requireNotNull(UserscriptParser.parse(source))
        val metadata = parsed.metadata
        assertNull(metadata.name)
        assertNull(metadata.namespace)
        assertNull(metadata.version)
        assertNull(metadata.description)
        assertEquals(emptyList<String>(), metadata.match)
        assertEquals(emptyList<String>(), metadata.include)
        assertEquals(emptyList<String>(), metadata.exclude)
        assertEquals(emptyList<String>(), metadata.grant)
        assertEquals(RunAt.DOCUMENT_END, metadata.runAt)
        assertNull(metadata.rawRunAt)
        assertFalse(metadata.noframes)
        assertTrue(metadata.raw.isEmpty())
        assertEquals("body();\n", parsed.body)
    }

    @Test
    fun leadingWhitespaceAndExtraSlashesAreTolerated() {
        val source = script(
            "   //   ==UserScript==",
            "  ///    @name    Spaced   Name  ",
            "\t//\t@match\thttps://spaced.test/*",
            "  // ==/UserScript==  ",
            "go();"
        )
        val metadata = requireNotNull(UserscriptParser.parse(source)).metadata
        assertEquals("Spaced   Name", metadata.name)
        assertEquals(listOf("https://spaced.test/*"), metadata.match)
    }

    @Test
    fun unknownRunAtFallsBackAndKeepsRawValue() {
        val source = script(
            "// ==UserScript==",
            "// @run-at when-ready",
            "// ==/UserScript==",
            "code();"
        )
        val metadata = requireNotNull(UserscriptParser.parse(source)).metadata
        assertEquals(RunAt.DOCUMENT_END, metadata.runAt)
        assertEquals("when-ready", metadata.rawRunAt)
    }

    @Test
    fun allKnownRunAtValuesAreParsed() {
        assertEquals(RunAt.DOCUMENT_START, runAtOf("document-start"))
        assertEquals(RunAt.DOCUMENT_END, runAtOf("document-end"))
        assertEquals(RunAt.DOCUMENT_IDLE, runAtOf("document-idle"))
        assertEquals(RunAt.DOCUMENT_BODY, runAtOf("document-body"))
    }

    @Test
    fun rawMapKeepsEveryMetadataLine() {
        val source = script(
            "// ==UserScript==",
            "// @name A",
            "// @Name B",
            "// @unknown x y",
            "// @noframes",
            "// ==/UserScript==",
            "code();"
        )
        val metadata = requireNotNull(UserscriptParser.parse(source)).metadata
        assertEquals("A", metadata.name)
        assertEquals(listOf("A", "B"), metadata.raw["name"])
        assertEquals(listOf("x y"), metadata.raw["unknown"])
        assertEquals(listOf(""), metadata.raw["noframes"])
        assertEquals(setOf("name", "unknown", "noframes"), metadata.raw.keys)
    }

    @Test
    fun firstBlankLineAfterBlockIsDropped() {
        val source = script(
            "// ==UserScript==",
            "// @name A",
            "// ==/UserScript==",
            "",
            "// leading comment",
            "code();"
        )
        val parsed = requireNotNull(UserscriptParser.parse(source))
        assertEquals("// leading comment\ncode();\n", parsed.body)
    }

    @Test
    fun descriptionKeepsInternalSpaces() {
        val source = script(
            "// ==UserScript==",
            "// @description  keeps   inner    spaces  ",
            "// ==/UserScript==",
            "code();"
        )
        val metadata = requireNotNull(UserscriptParser.parse(source)).metadata
        assertEquals("keeps   inner    spaces", metadata.description)
    }

    private fun runAtOf(value: String): RunAt {
        val source = script("// ==UserScript==", "// @run-at $value", "// ==/UserScript==", "code();")
        return requireNotNull(UserscriptParser.parse(source)).metadata.runAt
    }
}
