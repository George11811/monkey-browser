package com.ridermonkey.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class PopupRoutingTest {

    @Test
    fun blankAndAboutTargetsAreIgnored() {
        assertEquals(PopupDecision.Ignore, PopupRouting.decide(null))
        assertEquals(PopupDecision.Ignore, PopupRouting.decide(""))
        assertEquals(PopupDecision.Ignore, PopupRouting.decide("   "))
        assertEquals(PopupDecision.Ignore, PopupRouting.decide("about:blank"))
        assertEquals(PopupDecision.Ignore, PopupRouting.decide("  ABOUT:BLANK  "))
        // 防回归：这些也是「没有真实地址」，甩给系统浏览器只会得到一个打不开的结果。
        assertEquals(PopupDecision.Ignore, PopupRouting.decide("about:srcdoc"))
        assertEquals(PopupDecision.Ignore, PopupRouting.decide("about:blank#x"))
        assertEquals(PopupDecision.Ignore, PopupRouting.decide("about:blank?x"))
    }

    @Test
    fun httpUrlsOpenATab() {
        assertEquals(PopupDecision.OpenTab("https://linux.do/t/1"), PopupRouting.decide("https://linux.do/t/1"))
        assertEquals(PopupDecision.OpenTab("http://example.com"), PopupRouting.decide("  http://example.com "))
        assertEquals(PopupDecision.OpenTab("HTTPS://EXAMPLE.COM"), PopupRouting.decide("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun onlyMailtoAndTelReachTheSystem() {
        assertEquals(PopupDecision.SystemBrowser("mailto:a@b.c"), PopupRouting.decide("mailto:a@b.c"))
        assertEquals(PopupDecision.SystemBrowser("MAILTO:a@b.c"), PopupRouting.decide("MAILTO:a@b.c"))
        assertEquals(PopupDecision.SystemBrowser("tel:+8610000"), PopupRouting.decide("tel:+8610000"))
        assertEquals(PopupDecision.SystemBrowser("mailto:a@b.c?subject=hi"), PopupRouting.decide(" mailto:a@b.c?subject=hi "))
    }

    @Test
    fun unknownSchemesAreNeverHandedToTheOs() {
        // 安全关键：这些一旦交给系统浏览器，就是让不可信页面去调系统处理器。
        assertEquals(PopupDecision.Ignore, PopupRouting.decide("file:///C:/Windows/System32/calc.exe"))
        assertEquals(PopupDecision.Ignore, PopupRouting.decide("javascript:void(0)"))
        assertEquals(PopupDecision.Ignore, PopupRouting.decide("data:text/html,<b>x</b>"))
        assertEquals(PopupDecision.Ignore, PopupRouting.decide("blob:https://linux.do/abc"))
        assertEquals(PopupDecision.Ignore, PopupRouting.decide("magnet:?xt=urn:btih:x"))
        assertEquals(PopupDecision.Ignore, PopupRouting.decide("slack://channel?id=1"))
    }

    @Test
    fun httpLikeSchemesAreNotMistakenForHttp() {
        // 防回归：只判 startsWith("http") 会把这两种当成网页，内嵌浏览器必然打不开。
        assertEquals(PopupDecision.Ignore, PopupRouting.decide("httpx://example.com"))
        assertEquals(PopupDecision.Ignore, PopupRouting.decide("https-ftp://example.com"))
    }
}