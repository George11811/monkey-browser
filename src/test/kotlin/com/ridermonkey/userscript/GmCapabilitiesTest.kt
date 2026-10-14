package com.ridermonkey.userscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmCapabilitiesTest {

    private fun meta(vararg grants: String) = UserscriptMetadata(grant = grants.toList())

    @Test
    fun noneGrantYieldsNoCapability() {
        assertTrue(GmCapabilities.granted(meta("none")).isEmpty())
        assertFalse(GmCapabilities.providesAny(meta("none")))
    }

    @Test
    fun emptyGrantYieldsNoCapability() {
        assertFalse(GmCapabilities.providesAny(UserscriptMetadata()))
    }

    @Test
    fun declaredGrantsAreReturned() {
        assertEquals(
            setOf("GM_getValue", "GM_addStyle"),
            GmCapabilities.granted(meta("GM_getValue", "GM_addStyle"))
        )
    }

    @Test
    fun grantNamesAreCaseInsensitive() {
        assertEquals(setOf("GM_addStyle"), GmCapabilities.granted(meta("gm_addstyle")))
    }

    @Test
    fun unknownGrantsAreIgnored() {
        // GM_registerMenuCommand 尚未实现：声明了也不给，避免「给了却不能用」。
        // 注意：这里举的例子必须是**当前确实未实现**的 API —— 每实现一个就要换一个，
        // 否则这条测试会在实现该 API 时误报失败（历史上 GM_xmlhttpRequest、GM_download 都踩过）。
        assertEquals(
            setOf("GM_addStyle"),
            GmCapabilities.granted(meta("GM_addStyle", "GM_registerMenuCommand"))
        )
    }

    @Test
    fun downloadAndOpenTabAreGranted() {
        assertEquals(setOf("GM_download"), GmCapabilities.granted(meta("GM_download")))
        assertEquals(setOf("GM.download"), GmCapabilities.granted(meta("GM.download")))
        assertEquals(setOf("GM_openInTab"), GmCapabilities.granted(meta("GM_openInTab")))
        assertEquals(setOf("GM.openInTab"), GmCapabilities.granted(meta("GM.openInTab")))
    }

    @Test
    fun networkApisAreGrantedBothWays() {
        assertEquals(setOf("GM_xmlhttpRequest"), GmCapabilities.granted(meta("GM_xmlhttpRequest")))
        assertEquals(setOf("GM.xmlHttpRequest"), GmCapabilities.granted(meta("GM.xmlHttpRequest")))
    }

    @Test
    fun modernAndLegacyNamesAreSeparate() {
        assertEquals(setOf("GM.getValue"), GmCapabilities.granted(meta("GM.getValue")))
        assertEquals(setOf("GM_getValue"), GmCapabilities.granted(meta("GM_getValue")))
    }

    @Test
    fun detectsGmUsageWithoutGrant() {
        assertTrue(
            GmCapabilities.looksLikeItUsesGmWithoutGrant("GM_setValue('a', 1)", UserscriptMetadata())
        )
        assertTrue(
            GmCapabilities.looksLikeItUsesGmWithoutGrant("GM.getValue('a')", UserscriptMetadata())
        )
        assertFalse(
            GmCapabilities.looksLikeItUsesGmWithoutGrant("console.log(1)", UserscriptMetadata())
        )
    }

    @Test
    fun grantedScriptIsNotFlagged() {
        assertFalse(
            GmCapabilities.looksLikeItUsesGmWithoutGrant("GM_setValue('a', 1)", meta("GM_setValue"))
        )
    }
}