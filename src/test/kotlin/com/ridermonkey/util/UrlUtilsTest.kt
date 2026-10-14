package com.ridermonkey.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun startPageContract() {
        assertEquals("about:blank", UrlUtils.START_PAGE)
    }

    @Test
    fun emptyInputIsRejected() {
        assertNull(UrlUtils.normalizeInput(null))
        assertNull(UrlUtils.normalizeInput(""))
        assertNull(UrlUtils.normalizeInput("   "))
    }

    @Test
    fun existingSchemeIsKept() {
        assertEquals("https://example.com/a?b=1", UrlUtils.normalizeInput("https://example.com/a?b=1"))
        assertEquals("http://example.com", UrlUtils.normalizeInput("http://example.com"))
        assertEquals("about:blank", UrlUtils.normalizeInput("about:blank"))
        assertEquals("file:///C:/tmp/a.html", UrlUtils.normalizeInput("file:///C:/tmp/a.html"))
    }

    @Test
    fun schemeIsCaseInsensitive() {
        assertEquals("HTTPS://EXAMPLE.COM", UrlUtils.normalizeInput("HTTPS://EXAMPLE.COM"))
        assertEquals("ABOUT:BLANK", UrlUtils.normalizeInput("ABOUT:BLANK"))
        assertEquals("FILE:C:/a", UrlUtils.normalizeInput("FILE:C:/a"))
    }

    @Test
    fun schemeWithoutHostIsRejected() {
        assertNull(UrlUtils.normalizeInput("https://"))
        assertNull(UrlUtils.normalizeInput("http://"))
        assertNull(UrlUtils.normalizeInput("about:"))
        assertNull(UrlUtils.normalizeInput("file:"))
    }

    @Test
    fun dangerousSchemesAreRejectedEvenWithDoubleSlash() {
        assertNull(UrlUtils.normalizeInput("javascript:void(0)"))
        assertNull(UrlUtils.normalizeInput("javascript://%0aalert(1)"))
        assertNull(UrlUtils.normalizeInput("JavaScript://x"))
        assertNull(UrlUtils.normalizeInput("vbscript://x"))
        assertNull(UrlUtils.normalizeInput("vbscript:msgbox(1)"))
    }

    @Test
    fun dataAndOpaqueSchemesAreNotNavigationEntries() {
        assertNull(UrlUtils.normalizeInput("data:"))
        assertNull(UrlUtils.normalizeInput("data:text/html,<script>alert(1)</script>"))
    }

    @Test
    fun unsupportedSchemesAreRejected() {
        assertNull(UrlUtils.normalizeInput("mailto:a@b.com"))
        assertNull(UrlUtils.normalizeInput("tel:+8613800138000"))
    }

    @Test
    fun whitespaceInsideInputIsRejected() {
        assertNull(UrlUtils.normalizeInput("https://exa mple.com"))
        assertNull(UrlUtils.normalizeInput("http://a\nb"))
        assertNull(UrlUtils.normalizeInput("exa\u00A0mple.com"))
        assertNull(UrlUtils.normalizeInput("exa\u3000mple.com"))
        assertNull(UrlUtils.normalizeInput("hello world"))
    }

    @Test
    fun bareHostGetsHttpsScheme() {
        assertEquals("https://example.com", UrlUtils.normalizeInput("example.com"))
        assertEquals("https://example.com", UrlUtils.normalizeInput("  example.com  "))
        assertEquals("https://localhost", UrlUtils.normalizeInput("localhost"))
        assertEquals("https://localhost:8080", UrlUtils.normalizeInput("localhost:8080"))
    }

    @Test
    fun localhostIsCaseInsensitiveAndAllowsTrailingDot() {
        assertEquals("https://LOCALHOST", UrlUtils.normalizeInput("LOCALHOST"))
        assertEquals("https://Localhost:8080", UrlUtils.normalizeInput("Localhost:8080"))
        assertEquals("https://localhost.", UrlUtils.normalizeInput("localhost."))
    }

    @Test
    fun bracketedIpv6IsAccepted() {
        assertEquals("https://[::1]", UrlUtils.normalizeInput("[::1]"))
        assertEquals("https://[::1]:8080", UrlUtils.normalizeInput("[::1]:8080"))
    }

    @Test
    fun malformedBracketedHostIsRejected() {
        assertNull(UrlUtils.normalizeInput("[]"))
        assertNull(UrlUtils.normalizeInput("[abc]"))
        assertNull(UrlUtils.normalizeInput("[abc]x"))
        assertNull(UrlUtils.normalizeInput("[::1]x"))
        assertNull(UrlUtils.normalizeInput("[::1]:abc"))
        assertNull(UrlUtils.normalizeInput("[::1]:"))
    }

    @Test
    fun portIsNotMistakenForScheme() {
        assertEquals("https://example.com:8443/path", UrlUtils.normalizeInput("example.com:8443/path"))
        assertEquals("https://example.com:65535", UrlUtils.normalizeInput("example.com:65535"))
    }

    @Test
    fun invalidPortIsRejected() {
        assertNull(UrlUtils.normalizeInput("example.com:"))
        assertNull(UrlUtils.normalizeInput("example.com:abc"))
        assertNull(UrlUtils.normalizeInput("example.com:\u0668"))
        assertNull(UrlUtils.normalizeInput("example.com:0"))
        assertNull(UrlUtils.normalizeInput("example.com:65536"))
        assertNull(UrlUtils.normalizeInput("example.com:99999"))
        assertNull(UrlUtils.normalizeInput("https:example.com"))
        assertNull(UrlUtils.normalizeInput("C:\\temp\\a.html"))
    }

    @Test
    fun invalidHostLabelCharactersAreRejected() {
        assertNull(UrlUtils.normalizeInput("exa!mple.com"))
        assertNull(UrlUtils.normalizeInput("foo_bar.com"))
        assertNull(UrlUtils.normalizeInput("exa<mple.com"))
        assertEquals("https://my-host.example.com", UrlUtils.normalizeInput("my-host.example.com"))
    }

    @Test
    fun trailingDotAndEmptyLabels() {
        assertEquals("https://example.com.", UrlUtils.normalizeInput("example.com."))
        assertNull(UrlUtils.normalizeInput(".example.com"))
        assertNull(UrlUtils.normalizeInput("a..b"))
        assertNull(UrlUtils.normalizeInput("example.com.."))
    }

    @Test
    fun hostWithQueryOrFragmentOnly() {
        assertEquals("https://example.com?q=1#f", UrlUtils.normalizeInput("example.com?q=1#f"))
        assertEquals("https://example.com/p?q=1#f", UrlUtils.normalizeInput("example.com/p?q=1#f"))
        assertNull(UrlUtils.normalizeInput("?q=1"))
        assertNull(UrlUtils.normalizeInput("#frag"))
    }

    @Test
    fun searchLikeInputIsRejected() {
        assertNull(UrlUtils.normalizeInput("/not/a/url"))
        assertNull(UrlUtils.normalizeInput("foo/bar"))
        assertNull(UrlUtils.normalizeInput("://x"))
    }

    @Test
    fun asciiIdnIsAcceptedFullWidthDotIsNot() {
        assertEquals("https://例子.测试", UrlUtils.normalizeInput("例子.测试"))
        assertNull(UrlUtils.normalizeInput("例子。测试"))
        assertEquals("https://xn--fsq.xn--0zwm56d", UrlUtils.normalizeInput("xn--fsq.xn--0zwm56d"))
    }
}
