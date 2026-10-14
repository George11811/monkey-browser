package com.ridermonkey.userscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptSelectionTest {

    private fun entry(
        id: String,
        enabled: Boolean = true,
        match: List<String> = listOf("https://example.com/*"),
        noframes: Boolean = false
    ): UserscriptEntry = UserscriptEntry(
        id = id,
        displayName = id,
        enabled = enabled,
        source = "",
        metadata = UserscriptMetadata(match = match, noframes = noframes),
        body = "",
        filePath = "/tmp/" + id
    )

    @Test
    fun onlyEnabledScriptsAreSelected() {
        val entries = listOf(entry("a"), entry("b", enabled = false))
        assertEquals(listOf("a"), ScriptSelection.select(entries, "https://example.com/x", true).map { it.id })
    }

    @Test
    fun onlyMatchingScriptsAreSelected() {
        val entries = listOf(entry("hit"), entry("miss", match = listOf("https://other.test/*")))
        assertEquals(listOf("hit"), ScriptSelection.select(entries, "https://example.com/x", true).map { it.id })
    }

    @Test
    fun noframesSkipsSubFrames() {
        val entries = listOf(entry("all"), entry("mainOnly", noframes = true))
        assertEquals(
            listOf("all", "mainOnly"),
            ScriptSelection.select(entries, "https://example.com/", true).map { it.id }
        )
        assertEquals(
            listOf("all"),
            ScriptSelection.select(entries, "https://example.com/", false).map { it.id }
        )
    }

    @Test
    fun scriptsWithoutUrlRulesAreNeverSelected() {
        val entries = listOf(entry("noRules", match = emptyList()))
        assertTrue(ScriptSelection.select(entries, "https://example.com/", true).isEmpty())
    }

    @Test
    fun includeOnlyScriptIsSelected() {
        val includeOnly = UserscriptEntry(
            id = "inc",
            displayName = "inc",
            enabled = true,
            source = "",
            metadata = UserscriptMetadata(include = listOf("https://example.com/*")),
            body = "",
            filePath = "/tmp/inc"
        )
        assertEquals(
            listOf("inc"),
            ScriptSelection.select(listOf(includeOnly), "https://example.com/x", true).map { it.id }
        )
    }
}
