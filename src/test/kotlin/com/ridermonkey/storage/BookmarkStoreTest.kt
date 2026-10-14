package com.ridermonkey.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookmarkStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = BookmarkStore(temp.newFile().toPath())

    @Test
    fun loadOnMissingFileIsEmpty() {
        val s = BookmarkStore(temp.root.toPath().resolve("nope.txt"))
        assertTrue(s.load().isEmpty())
    }

    @Test
    fun saveThenLoadRoundTrips() {
        val s = store()
        s.save(listOf(BookmarkStore.Bookmark("A", "https://a.example/"), BookmarkStore.Bookmark("B", "https://b.example/")))
        assertEquals(
            listOf(BookmarkStore.Bookmark("A", "https://a.example/"), BookmarkStore.Bookmark("B", "https://b.example/")),
            s.load()
        )
    }

    @Test
    fun urlContainingCommaSurvives() {
        // 这正是用制表符而不是逗号分隔的原因：逗号分隔会把这条拆坏。
        val s = store()
        val url = "https://a.example/s?x=1,2,3&y=4"
        s.save(listOf(BookmarkStore.Bookmark("带逗号", url)))
        assertEquals(url, s.load().single().url)
    }

    @Test
    fun addIsDeduplicatedByUrlAndMovesToFront() {
        val s = store()
        var items = s.withAdded(emptyList(), "一", "https://one.example/")
        items = s.withAdded(items, "二", "https://two.example/")
        items = s.withAdded(items, "一（改名）", "https://one.example/")
        assertEquals(2, items.size)
        assertEquals("https://one.example/", items.first().url)
        assertEquals("一（改名）", items.first().title)
    }

    @Test
    fun blankUrlIsIgnored() {
        val s = store()
        assertTrue(s.withAdded(emptyList(), "x", "   ").isEmpty())
    }

    @Test
    fun blankTitleFallsBackToUrl() {
        val s = store()
        assertEquals("https://x.example/", s.withAdded(emptyList(), "  ", "https://x.example/").single().title)
    }

    @Test
    fun titleWithTabIsSanitized() {
        val s = store()
        val items = s.withAdded(emptyList(), "含\t制表符", "https://x.example/")
        assertEquals(1, items.size)
        assertFalse(items.single().title.contains('\t'))
    }

    @Test
    fun withoutRemovesByUrl() {
        val s = store()
        val items = s.withAdded(s.withAdded(emptyList(), "一", "https://one.example/"), "二", "https://two.example/")
        val left = s.without(items, "https://one.example/")
        assertEquals(1, left.size)
        assertEquals("https://two.example/", left.single().url)
    }

    @Test
    fun corruptedLinesAreSkipped() {
        val file = temp.newFile().toPath()
        java.nio.file.Files.write(file, listOf("只有标题没有分隔符", "", "标题\thttps://ok.example/"))
        val loaded = BookmarkStore(file).load()
        assertEquals(1, loaded.size)
        assertEquals("https://ok.example/", loaded.single().url)
    }

    @Test
    fun renameKeepsPositionAndChangesTitle() {
        val s = store()
        var items = s.withAdded(emptyList(), "一", "https://one.example/")
        items = s.withAdded(items, "二", "https://two.example/")
        // 「一」在列表末尾（最近添加的在前），重命名后必须还在末尾 —— 不能被顶到最前。
        val renamed = s.withRenamed(items, "https://one.example/", "改过的名字")
        assertEquals(listOf("https://two.example/", "https://one.example/"), renamed.map { it.url })
        assertEquals("改过的名字", renamed.last().title)
    }

    @Test
    fun renameToBlankFallsBackToUrl() {
        val s = store()
        val items = s.withAdded(emptyList(), "一", "https://one.example/")
        assertEquals("https://one.example/", s.withRenamed(items, "https://one.example/", "   ").single().title)
    }

    @Test
    fun renameUnknownUrlIsNoOp() {
        val s = store()
        val items = s.withAdded(emptyList(), "一", "https://one.example/")
        assertEquals(items, s.withRenamed(items, "https://other.example/", "x"))
    }

    @Test
    fun containsMatchesTrimmedUrl() {
        val s = store()
        val items = s.withAdded(emptyList(), "一", "https://one.example/")
        assertTrue(s.contains(items, " https://one.example/ "))
        assertFalse(s.contains(items, "https://other.example/"))
    }
}
