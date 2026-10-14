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

class GmStorageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var storage: GmStorage
    private lateinit var root: Path

    @Before
    fun setUp() {
        root = temp.newFolder("storage").toPath()
        storage = GmStorage(root)
    }

    @Test
    fun emptyStorageYieldsEmptySnapshot() {
        assertTrue(storage.snapshot("a.user.js").isEmpty())
    }

    @Test
    fun putThenSnapshot() {
        storage.put("a.user.js", "count", "3")
        storage.put("a.user.js", "name", String(charArrayOf('"', 'x', '"')))
        val snapshot = storage.snapshot("a.user.js")
        assertEquals("3", snapshot["count"])
        assertEquals(String(charArrayOf('"', 'x', '"')), snapshot["name"])
    }

    @Test
    fun putOverwritesExistingKey() {
        storage.put("a.user.js", "k", "1")
        storage.put("a.user.js", "k", "2")
        assertEquals("2", storage.snapshot("a.user.js")["k"])
        assertEquals(1, storage.snapshot("a.user.js").size)
    }

    @Test
    fun removeDeletesKey() {
        storage.put("a.user.js", "k", "1")
        storage.remove("a.user.js", "k")
        assertFalse(storage.snapshot("a.user.js").containsKey("k"))
    }

    @Test
    fun keysAndUnicodeKeysRoundTrip() {
        val tricky = "带空格 的键 + plus & ampersand"
        storage.put("a.user.js", tricky, String(charArrayOf('{', '}')))
        assertEquals(listOf(tricky), storage.keys("a.user.js"))
        assertEquals(String(charArrayOf('{', '}')), storage.snapshot("a.user.js")[tricky])
    }

    @Test
    fun scriptsAreIsolated() {
        storage.put("a.user.js", "k", "1")
        storage.put("b.user.js", "k", "2")
        assertEquals("1", storage.snapshot("a.user.js")["k"])
        assertEquals("2", storage.snapshot("b.user.js")["k"])
    }

    @Test
    fun nestedValueJsonIsStoredVerbatim() {
        val nested = String(charArrayOf('{', '"', 'a', '"', ':', '[', '1', ',', '2', ']', '}'))
        storage.put("a.user.js", "obj", nested)
        assertEquals(nested, storage.snapshot("a.user.js")["obj"])
    }

    @Test
    fun scriptIdWithPathSeparatorsIsNeutralised() {
        storage.put("..\\evil/user.js", "k", "1")
        // 只能落在 storage 根目录下的单层目录里，不能穿越出去。
        val dirs = Files.list(root).use { it.toList() }
        assertEquals(1, dirs.size)
        assertEquals(root, dirs.first().parent)
    }
}
