package com.ridermonkey.userscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectRulesTest {

    private fun meta(vararg connect: String) = UserscriptMetadata(connect = connect.toList())

    @Test
    fun noConnectRuleDeniesEverything() {
        assertFalse(ConnectRules.allows(UserscriptMetadata(), "https://a.test/x", "https://a.test/"))
    }

    @Test
    fun exactHostIsAllowed() {
        assertTrue(ConnectRules.allows(meta("a.test"), "https://a.test/x", null))
        assertFalse(ConnectRules.allows(meta("a.test"), "https://other.test/x", null))
    }

    @Test
    fun hostComparisonIsCaseInsensitive() {
        assertTrue(ConnectRules.allows(meta("A.TEST"), "https://a.test/x", null))
    }

    @Test
    fun starAllowsAnyHost() {
        assertTrue(ConnectRules.allows(meta("*"), "https://anything.test/x", null))
    }

    @Test
    fun subdomainWildcardIncludesBareDomain() {
        assertTrue(ConnectRules.allows(meta("*.a.test"), "https://x.a.test/y", null))
        assertTrue(ConnectRules.allows(meta("*.a.test"), "https://a.test/y", null))
        assertFalse(ConnectRules.allows(meta("*.a.test"), "https://b.test/y", null))
    }

    @Test
    fun selfResolvesToDocumentHost() {
        assertTrue(ConnectRules.allows(meta("self"), "https://a.test/api", "https://a.test/page"))
        assertFalse(ConnectRules.allows(meta("self"), "https://a.test/api", "https://b.test/page"))
    }

    @Test
    fun unparsableRequestUrlIsDenied() {
        assertFalse(ConnectRules.allows(meta("*"), "not a url", null))
    }

    @Test
    fun hostOfHandlesPortAndIpv6() {
        assertEquals("a.test", ConnectRules.hostOf("https://a.test:8443/x"))
        assertEquals("[::1]", ConnectRules.hostOf("http://[::1]:8080/x"))
        assertEquals(null, ConnectRules.hostOf("about:blank"))
    }
}
