package com.ridermonkey.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ScriptFileStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var scriptsDir: Path
    private lateinit var stateFile: Path
    private lateinit var store: ScriptFileStore

    private val sample = """
        // ==UserScript==
        // @name         Hello
        // @namespace    rider.monkey.test
        // @match        https://example.com/*
        // ==/UserScript==
        console.log('hello');
    """.trimIndent()

    @Before
    fun setUp() {
        scriptsDir = temp.newFolder("scripts").toPath()
        stateFile = temp.newFolder("state").toPath().resolve("disabled.txt")
        store = ScriptFileStore(scriptsDir, stateFile)
    }

    private fun writeScript(name: String, content: String) {
        Files.writeString(scriptsDir.resolve(name), content)
    }

    @Test
    fun missingDirectoryYieldsEmptyLibrary() {
        val missing = ScriptFileStore(scriptsDir.resolve("nope"), stateFile)
        assertTrue(missing.load().isEmpty())
    }

    @Test
    fun scriptIsParsedAndEnabledByDefault() {
        writeScript("hello.user.js", sample)
        val loaded = store.load()
        assertEquals(1, loaded.size)
        val entry = loaded.first()
        assertEquals("hello.user.js", entry.id)
        assertEquals("Hello", entry.displayName)
        assertTrue(entry.enabled)
        assertTrue(entry.body.contains("console.log('hello')"))
    }

    @Test
    fun nonUserscriptFilesAreIgnored() {
        writeScript("readme.txt", "not a script")
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun fileWithoutMetadataBlockIsSkipped() {
        writeScript("broken.user.js", "console.log('no metadata');")
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun disablingIsPersistedAcrossReload() {
        writeScript("hello.user.js", sample)
        assertTrue(store.load().first().enabled)

        store.setEnabled("hello.user.js", false)
        assertFalse(store.isEnabled("hello.user.js"))

        // 重新构造 store（模拟 IDE 重启）后状态仍然生效。
        val reloaded = ScriptFileStore(scriptsDir, stateFile)
        assertFalse(reloaded.load().first().enabled)

        reloaded.setEnabled("hello.user.js", true)
        assertTrue(ScriptFileStore(scriptsDir, stateFile).load().first().enabled)
    }

    @Test
    fun pauseFlagPersistsAcrossReload() {
        assertFalse(store.isPaused())
        store.setPaused(true)
        assertTrue(store.isPaused())
        // 重新构造 store（模拟 IDE 重启）后暂停状态仍然生效。
        assertTrue(ScriptFileStore(scriptsDir, stateFile).isPaused())
        ScriptFileStore(scriptsDir, stateFile).setPaused(false)
        assertFalse(store.isPaused())
    }

    @Test
    fun scriptsAreSortedByFileName() {
        writeScript("b.user.js", sample)
        writeScript("a.user.js", sample)
        assertEquals(listOf("a.user.js", "b.user.js"), store.load().map { it.id })
    }
}