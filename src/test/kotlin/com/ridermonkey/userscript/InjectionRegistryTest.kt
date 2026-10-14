package com.ridermonkey.userscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectionRegistryTest {

    @Test
    fun sameTripleIsMarkedOnlyOnce() {
        val registry = InjectionRegistry()
        assertTrue(registry.markIfNew("frame1", "https://a.test/", "script.user.js"))
        assertFalse(registry.markIfNew("frame1", "https://a.test/", "script.user.js"))
        assertEquals(1, registry.size())
    }

    @Test
    fun differentScriptIsNew() {
        val registry = InjectionRegistry()
        assertTrue(registry.markIfNew("frame1", "https://a.test/", "a.user.js"))
        assertTrue(registry.markIfNew("frame1", "https://a.test/", "b.user.js"))
    }

    @Test
    fun differentDocumentUrlAllowsReinjection() {
        val registry = InjectionRegistry()
        assertTrue(registry.markIfNew("frame1", "https://a.test/", "s.user.js"))
        // 同一 frame 导航到新页面后必须能重新注入。
        assertTrue(registry.markIfNew("frame1", "https://a.test/page2", "s.user.js"))
    }

    @Test
    fun differentFrameAllowsReinjection() {
        val registry = InjectionRegistry()
        assertTrue(registry.markIfNew("frame1", "https://a.test/", "s.user.js"))
        assertTrue(registry.markIfNew("frame2", "https://a.test/", "s.user.js"))
    }

    @Test
    fun clearFrameAllowsReinjectionForThatFrameOnly() {
        val registry = InjectionRegistry()
        assertTrue(registry.markIfNew("frame1", "https://a.test/", "s.user.js"))
        assertTrue(registry.markIfNew("frame2", "https://a.test/", "s.user.js"))

        registry.clearFrame("frame1")

        // frame1 重新导航到同一 URL 时必须能再注入（刷新场景）。
        assertTrue(registry.markIfNew("frame1", "https://a.test/", "s.user.js"))
        // frame2 的记录不受影响，同文档内不会被重复注入。
        assertFalse(registry.markIfNew("frame2", "https://a.test/", "s.user.js"))
    }

    @Test
    fun clearFrameIsPrefixSafe() {
        val registry = InjectionRegistry()
        assertTrue(registry.markIfNew("frame1", "https://a.test/", "s.user.js"))
        // frame10 不应被 clearFrame("frame1") 误伤。
        assertTrue(registry.markIfNew("frame10", "https://a.test/", "s.user.js"))
        registry.clearFrame("frame1")
        assertFalse(registry.markIfNew("frame10", "https://a.test/", "s.user.js"))
    }

    @Test
    fun releaseAllowsReinjection() {
        val registry = InjectionRegistry()
        assertTrue(registry.markIfNew("frame1", "https://a.test/", "s.user.js"))
        assertFalse(registry.markIfNew("frame1", "https://a.test/", "s.user.js"))

        // 注入实际落进了别的文档时，必须能把键还回去、让主档重新注入。
        assertTrue(registry.release("frame1", "https://a.test/", "s.user.js"))
        assertTrue(registry.markIfNew("frame1", "https://a.test/", "s.user.js"))
    }

    @Test
    fun releaseUrlAllowsReinjectionForThatUrlOnly() {
        val registry = InjectionRegistry()
        assertTrue(registry.markIfNew("frame1", "https://a.test/", "s.user.js"))
        assertTrue(registry.markIfNew("frame1", "https://b.test/", "s.user.js"))

        registry.releaseUrl("frame1", "https://a.test/")

        assertTrue(registry.markIfNew("frame1", "https://a.test/", "s.user.js"))
        assertFalse(registry.markIfNew("frame1", "https://b.test/", "s.user.js"))
    }

    @Test
    fun releaseOfUnknownKeyReturnsFalse() {
        val registry = InjectionRegistry()
        assertFalse(registry.release("frame1", "https://a.test/", "s.user.js"))
    }

    @Test
    fun clearResetsEverything() {
        val registry = InjectionRegistry()
        assertTrue(registry.markIfNew("frame1", "https://a.test/", "s.user.js"))
        registry.clear()
        assertEquals(0, registry.size())
        assertTrue(registry.markIfNew("frame1", "https://a.test/", "s.user.js"))
    }
}