package com.ridermonkey.userscript

import com.ridermonkey.storage.GmStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URLEncoder

class GmBridgeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var storage: GmStorage
    private lateinit var bridge: GmBridge

    @Before
    fun setUp() {
        storage = GmStorage(temp.newFolder("storage").toPath())
        bridge = GmBridge(storage)
        bridge.registerToken("a.user.js", "1", "tok-1")
    }

    /** 期望的来源 URL：真实值由 CEF 按注入时的 scriptUrl 回填。 */
    private fun source(scriptId: String, token: String, frame: String = "1"): String =
        GmBridge.sourceUrlFor(scriptId, frame, token)

    private fun message(
        scriptId: String,
        token: String,
        op: String,
        key: String,
        value: String?,
        frame: String = "1"
    ): String {
        val parts = mutableListOf(
            "scriptId=" + URLEncoder.encode(scriptId, "UTF-8"),
            "frameId=" + URLEncoder.encode(frame, "UTF-8"),
            "token=" + URLEncoder.encode(token, "UTF-8"),
            "op=" + URLEncoder.encode(op, "UTF-8"),
            "key=" + URLEncoder.encode(key, "UTF-8")
        )
        if (value != null) parts.add("value=" + URLEncoder.encode(value, "UTF-8"))
        return GmBridge.PREFIX + parts.joinToString("&")
    }

    @Test
    fun nonGmMessageIsNotConsumed() {
        assertFalse(bridge.handleConsoleMessage("普通日志", ""))
        assertFalse(bridge.handleConsoleMessage("__RM_INJECT_OK__a.user.js", ""))
    }

    @Test
    fun validTokenAndSourceWritesValue() {
        val msg = message("a.user.js", "tok-1", "set", "k", "42")
        assertTrue(bridge.handleConsoleMessage(msg, source("a.user.js", "tok-1")))
        assertEquals("42", storage.snapshot("a.user.js")["k"])
    }

    @Test
    fun wrongTokenIsRejected() {
        val msg = message("a.user.js", "forged", "set", "k", "42")
        bridge.handleConsoleMessage(msg, source("a.user.js", "forged"))
        assertTrue(storage.snapshot("a.user.js").isEmpty())
    }

    @Test
    fun unknownScriptIsRejected() {
        val msg = message("other.user.js", "tok-1", "set", "k", "1")
        bridge.handleConsoleMessage(msg, source("other.user.js", "tok-1"))
        assertTrue(storage.snapshot("other.user.js").isEmpty())
    }

    /**
     * 核心安全用例：页面 hook console.log 偷到 token 后重放，仍必须被拒——
     * 因为它自己发的消息 source 是页面地址，套不上我们注入代码的来源前缀。
     */
    @Test
    fun stolenTokenIsUselessWithoutMatchingSource() {
        val msg = message("a.user.js", "tok-1", "set", "k", "evil")
        bridge.handleConsoleMessage(msg, "https://www.baidu.com/")
        assertTrue(storage.snapshot("a.user.js").isEmpty())
    }

    @Test
    fun emptySourceIsRejected() {
        val msg = message("a.user.js", "tok-1", "set", "k", "evil")
        bridge.handleConsoleMessage(msg, "")
        assertTrue(storage.snapshot("a.user.js").isEmpty())
    }

    @Test
    fun deleteRemovesValue() {
        bridge.handleConsoleMessage(
            message("a.user.js", "tok-1", "set", "k", "1"),
            source("a.user.js", "tok-1")
        )
        bridge.handleConsoleMessage(
            message("a.user.js", "tok-1", "delete", "k", null),
            source("a.user.js", "tok-1")
        )
        assertTrue(storage.snapshot("a.user.js").isEmpty())
    }

    @Test
    fun specialCharactersRoundTrip() {
        val key = "键 with space & plus+sign"
        val value = String(charArrayOf('{', '"', 'x', '"', ':', '"', '中', '文', '"', '}'))
        bridge.handleConsoleMessage(
            message("a.user.js", "tok-1", "set", key, value),
            source("a.user.js", "tok-1")
        )
        assertEquals(value, storage.snapshot("a.user.js")[key])
    }

    @Test
    fun tokensAreScopedPerFrame() {
        // 同一个脚本注入到两个 frame：两次注入各持一个 token，互不覆盖，
        // 且各自的来源 URL 不同（frame 参与来源指纹）。
        bridge.registerToken("a.user.js", "2", "tok-2")
        bridge.handleConsoleMessage(
            message("a.user.js", "tok-1", "set", "from1", "1", frame = "1"),
            source("a.user.js", "tok-1", "1")
        )
        bridge.handleConsoleMessage(
            message("a.user.js", "tok-2", "set", "from2", "2", frame = "2"),
            source("a.user.js", "tok-2", "2")
        )
        val snapshot = storage.snapshot("a.user.js")
        assertEquals("1", snapshot["from1"])
        assertEquals("2", snapshot["from2"])
    }

    @Test
    fun sourceFromAnotherFrameIsRejected() {
        bridge.registerToken("a.user.js", "2", "tok-2")
        // 用 frame1 的来源去写 frame2 的记录：来源前缀不匹配，必须被拒。
        bridge.handleConsoleMessage(
            message("a.user.js", "tok-2", "set", "k", "9", frame = "2"),
            source("a.user.js", "tok-2", "1")
        )
        assertTrue(storage.snapshot("a.user.js").isEmpty())
    }

    @Test
    fun clearTokensRevokesAccess() {
        bridge.clearTokens()
        bridge.handleConsoleMessage(
            message("a.user.js", "tok-1", "set", "k", "1"),
            source("a.user.js", "tok-1")
        )
        assertTrue(storage.snapshot("a.user.js").isEmpty())
    }

    @Test
    fun unknownOperationIsIgnored() {
        bridge.handleConsoleMessage(
            message("a.user.js", "tok-1", "drop", "k", null),
            source("a.user.js", "tok-1")
        )
        assertTrue(storage.snapshot("a.user.js").isEmpty())
    }

    @Test
    fun isTrustedSourceMatchesOnlyOurInjectedUrls() {
        assertTrue(bridge.isTrustedSource(source("a.user.js", "tok-1"), "a.user.js", "1"))
        assertFalse(bridge.isTrustedSource("https://www.baidu.com/", "a.user.js", "1"))
        assertFalse(bridge.isTrustedSource("", "a.user.js", "1"))
        // 别的脚本 / 别的 frame 的前缀不能通过。
        assertFalse(bridge.isTrustedSource(source("b.user.js", "tok-1"), "a.user.js", "1"))
        assertFalse(bridge.isTrustedSource(source("a.user.js", "tok-1", "2"), "a.user.js", "1"))
    }
}
