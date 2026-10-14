package com.ridermonkey.userscript

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserscriptMatcherTest {

    private fun metadata(
        match: List<String> = emptyList(),
        include: List<String> = emptyList(),
        exclude: List<String> = emptyList()
    ): UserscriptMetadata = UserscriptMetadata(match = match, include = include, exclude = exclude)

    @Test
    fun hostWildcardPatternMatchesPathVariants() {
        assertTrue(UserscriptMatcher.matchesPattern("https://example.com/*", "https://example.com/"))
        assertTrue(UserscriptMatcher.matchesPattern("https://example.com/*", "https://example.com/a/b?c=1#d"))
        assertTrue(UserscriptMatcher.matchesPattern("https://example.com/*", "https://example.com"))
    }

    @Test
    fun exactHostDoesNotMatchSubdomain() {
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com/*", "https://sub.example.com/"))
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com/*", "https://example.com.evil.test/"))
    }

    @Test
    fun exactPathMustMatchLiterally() {
        assertTrue(UserscriptMatcher.matchesPattern("https://example.com/foo", "https://example.com/foo"))
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com/foo", "https://example.com/foobar"))
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com/Foo", "https://example.com/foo"))
    }

    @Test
    fun starSchemeMatchesHttpAndHttpsOnly() {
        assertTrue(UserscriptMatcher.matchesPattern("*://example.com/*", "http://example.com/a"))
        assertTrue(UserscriptMatcher.matchesPattern("*://example.com/*", "https://example.com/a"))
        assertFalse(UserscriptMatcher.matchesPattern("*://example.com/*", "ftp://example.com/a"))
        assertFalse(UserscriptMatcher.matchesPattern("*://example.com/*", "file:///tmp/a"))
    }

    @Test
    fun ftpSchemeIsSupported() {
        assertTrue(UserscriptMatcher.matchesPattern("ftp://files.test/*", "ftp://files.test/pub/a.txt"))
        assertFalse(UserscriptMatcher.matchesPattern("ftp://files.test/*", "https://files.test/pub/a.txt"))
    }

    @Test
    fun wildcardSubdomainMatchesDomainItself() {
        assertTrue(UserscriptMatcher.matchesPattern("https://*.example.com/*", "https://example.com/"))
    }

    @Test
    fun wildcardSubdomainMatchesNestedSubdomains() {
        assertTrue(UserscriptMatcher.matchesPattern("https://*.example.com/*", "https://a.example.com/"))
        assertTrue(UserscriptMatcher.matchesPattern("https://*.example.com/*", "https://a.b.example.com/x"))
    }

    @Test
    fun wildcardSubdomainDoesNotMatchLookalikes() {
        assertFalse(UserscriptMatcher.matchesPattern("https://*.example.com/*", "https://notexample.com/"))
        assertFalse(UserscriptMatcher.matchesPattern("https://*.example.com/*", "https://example.com.evil.test/"))
        assertFalse(UserscriptMatcher.matchesPattern("https://*.example.com/*", "https://example.org/"))
    }

    @Test
    fun pathWildcardCrossesSlashes() {
        assertTrue(UserscriptMatcher.matchesPattern("https://example.com/a/*", "https://example.com/a/b/c/d"))
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com/a/*", "https://example.com/b/c"))
    }

    @Test
    fun allUrlsMatchesSupportedSchemes() {
        assertTrue(UserscriptMatcher.matchesPattern("<all_urls>", "http://a.test/"))
        assertTrue(UserscriptMatcher.matchesPattern("<all_urls>", "https://a.test/x"))
        assertTrue(UserscriptMatcher.matchesPattern("<all_urls>", "file:///C:/tmp/a.html"))
        assertTrue(UserscriptMatcher.matchesPattern("<all_urls>", "ftp://a.test/pub"))
        assertFalse(UserscriptMatcher.matchesPattern("<all_urls>", "mailto:a@b.test"))
        assertFalse(UserscriptMatcher.matchesPattern("<all_urls>", "javascript:alert(1)"))
    }

    @Test
    fun fileSchemeIgnoresHost() {
        assertTrue(UserscriptMatcher.matchesPattern("file:///*", "file:///C:/tmp/a.html"))
        assertFalse(UserscriptMatcher.matchesPattern("file:///*", "https://example.com/"))
    }

    @Test
    fun patternWithoutPortMatchesAnyPort() {
        assertTrue(UserscriptMatcher.matchesPattern("https://example.com/*", "https://example.com:8443/path"))
        assertTrue(UserscriptMatcher.matchesPattern("http://localhost/*", "http://localhost:3000/"))
        assertTrue(UserscriptMatcher.matchesPattern("https://example.com/*", "https://example.com/plain"))
    }

    @Test
    fun explicitPortMustMatch() {
        assertTrue(UserscriptMatcher.matchesPattern("https://example.com:8443/*", "https://example.com:8443/x"))
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com:8443/*", "https://example.com/x"))
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com:8443/*", "https://example.com:9443/x"))
        // scheme 默认端口参与比较：pattern 写 443 时，未显式写端口的 https URL 也算命中。
        assertTrue(UserscriptMatcher.matchesPattern("https://example.com:443/*", "https://example.com/x"))
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com:80/*", "https://example.com/x"))
    }

    @Test
    fun portWildcardMatchesAnyPort() {
        assertTrue(UserscriptMatcher.matchesPattern("https://example.com:*/*", "https://example.com:1234/x"))
        assertTrue(UserscriptMatcher.matchesPattern("https://example.com:*/*", "https://example.com/x"))
    }

    @Test
    fun invalidPortPatternIsRejected() {
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com:/*", "https://example.com/x"))
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com:abc/*", "https://example.com/x"))
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com:99999/*", "https://example.com/x"))
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com:0/*", "https://example.com/x"))
    }

    @Test
    fun schemeAndHostAreCaseInsensitive() {
        assertTrue(UserscriptMatcher.matchesPattern("https://EXAMPLE.com/*", "HTTPS://example.COM/Path"))
    }

    /**
     * 本实现有意只看 path、忽略 query 与 fragment，以对齐 Violentmonkey 文档中 @match 的行为；
     * 这与 Chrome 扩展 match pattern（path+query）不同，属首版既定口径，见 UserscriptMatcher 的 KDoc。
     */
    @Test
    fun queryAndFragmentAreIgnoredByDesign() {
        assertTrue(UserscriptMatcher.matchesPattern("https://example.com/foo", "https://example.com/foo?x=1"))
        assertTrue(UserscriptMatcher.matchesPattern("https://example.com/foo", "https://example.com/foo#section"))
    }

    @Test
    fun invalidPatternsDoNotMatch() {
        assertFalse(UserscriptMatcher.matchesPattern("", "https://example.com/"))
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com", "https://example.com/"))
        assertFalse(UserscriptMatcher.matchesPattern("foo://bar/*", "foo://bar/x"))
        assertFalse(UserscriptMatcher.matchesPattern("https:///*", "https://example.com/"))
    }

    @Test
    fun malformedUrlDoesNotMatch() {
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com/*", "example.com"))
        assertFalse(UserscriptMatcher.matchesPattern("<all_urls>", "not a url"))
    }

    @Test
    fun noMatchOrIncludeMeansNoMatch() {
        assertFalse(UserscriptMatcher.matches(UserscriptMetadata(), "https://example.com/"))
        assertFalse(
            UserscriptMatcher.matches(
                UserscriptMetadata(exclude = listOf("https://example.com/*")),
                "https://example.com/"
            )
        )
    }

    @Test
    fun noUrlRulesIsReported() {
        assertTrue(UserscriptMatcher.hasNoUrlRules(UserscriptMetadata()))
        assertTrue(UserscriptMatcher.hasNoUrlRules(UserscriptMetadata(exclude = listOf("https://x.test/*"))))
        assertFalse(UserscriptMatcher.hasNoUrlRules(UserscriptMetadata(include = listOf("https://x.test/*"))))
        assertFalse(UserscriptMatcher.hasNoUrlRules(UserscriptMetadata(match = listOf("https://x.test/*"))))
    }

    @Test
    fun excludeVetoesMatch() {
        val rules = metadata(
            match = listOf("https://example.com/*"),
            exclude = listOf("https://example.com/private*")
        )
        assertTrue(UserscriptMatcher.matches(rules, "https://example.com/public"))
        assertFalse(UserscriptMatcher.matches(rules, "https://example.com/private/x"))
    }

    @Test
    fun excludeVetoesInclude() {
        val rules = metadata(
            include = listOf("https://example.com/*"),
            exclude = listOf("https://example.com/private*")
        )
        assertTrue(UserscriptMatcher.matches(rules, "https://example.com/public"))
        assertFalse(UserscriptMatcher.matches(rules, "https://example.com/private/x"))
    }

    @Test
    fun regexExcludeVetoesEverything() {
        val rules = metadata(
            match = listOf("https://example.com/*"),
            exclude = listOf("/private/")
        )
        assertTrue(UserscriptMatcher.matches(rules, "https://example.com/public/x"))
        assertFalse(UserscriptMatcher.matches(rules, "https://example.com/private/x"))
    }

    /**
     * 回归用例：早期实现把「以斜杠开头、末尾斜杠后全是字母」都当正则，
     * 于是 include=/a/b 被判成正则 a，只要 URL 含字母 a 就命中几乎所有页面。
     */
    @Test
    fun leadingSlashGlobIsNotMistakenForRegex() {
        val rules = metadata(include = listOf("/a/b"))
        assertFalse(UserscriptMatcher.matches(rules, "https://x.test/anything"))
        assertFalse(UserscriptMatcher.matches(rules, "https://x.test/zzz"))
        // 按 glob 的整串匹配语义，裸路径 glob 不会命中任何完整 URL —— 这正是预期行为。
        assertFalse(UserscriptMatcher.matches(rules, "https://x.test/a/b"))
    }

    @Test
    fun pathLikeGlobInIncludeFallsBackToGlob() {
        val rules = metadata(include = listOf("*/some/path/file"))
        assertTrue(UserscriptMatcher.matches(rules, "https://x.test/some/path/file"))
        assertFalse(UserscriptMatcher.matches(rules, "https://x.test/some/path"))
    }

    @Test
    fun pathLikeGlobInExcludeDoesNotOverMatch() {
        val rules = metadata(
            match = listOf("https://x.test/*"),
            exclude = listOf("*/admin/panel")
        )
        assertTrue(UserscriptMatcher.matches(rules, "https://x.test/administrator"))
        assertFalse(UserscriptMatcher.matches(rules, "https://x.test/admin/panel"))
    }

    @Test
    fun regexFlagsAreValidated() {
        assertTrue(UserscriptMatcher.matches(metadata(include = listOf("""/^https://x\.test//m""")), "https://x.test/a"))
        assertTrue(UserscriptMatcher.matches(metadata(include = listOf("""/^https://x\.test//gimsuy""")), "https://x.test/a"))
        // 重复 flag 非法 → 落回 glob → 整串匹配不命中。
        assertFalse(UserscriptMatcher.matches(metadata(include = listOf("""/^https://x\.test//ii""")), "https://x.test/a"))
        // 非法 flag 字符 → 落回 glob。
        assertFalse(UserscriptMatcher.matches(metadata(include = listOf("/abc/z")), "https://x.test/a"))
    }

    @Test
    fun matchAndIncludeAreUnion() {
        val rules = metadata(
            match = listOf("https://a.test/*"),
            include = listOf("https://b.test/*")
        )
        assertTrue(UserscriptMatcher.matches(rules, "https://a.test/x"))
        assertTrue(UserscriptMatcher.matches(rules, "https://b.test/x"))
        assertFalse(UserscriptMatcher.matches(rules, "https://c.test/x"))
    }

    @Test
    fun includeGlobCrossesSlashesAndSupportsQuestionMark() {
        assertTrue(
            UserscriptMatcher.matches(
                metadata(include = listOf("https://example.com/*")),
                "https://example.com/a/b/c"
            )
        )
        assertTrue(
            UserscriptMatcher.matches(
                metadata(include = listOf("https://example.com/pa?e")),
                "https://example.com/page"
            )
        )
        assertFalse(
            UserscriptMatcher.matches(
                metadata(include = listOf("https://example.com/pa?e")),
                "https://example.com/pages"
            )
        )
    }

    @Test
    fun regexIncludeIsSupported() {
        val rules = metadata(include = listOf("""/^https://regex\.test/"""))
        assertTrue(UserscriptMatcher.matches(rules, "https://regex.test/page"))
        assertFalse(UserscriptMatcher.matches(rules, "http://regex.test/page"))
        assertFalse(UserscriptMatcher.matches(rules, "https://other.test/https://regex.test/"))
    }

    @Test
    fun regexIncludeHonoursIgnoreCaseFlag() {
        val insensitive = metadata(include = listOf("""/^https://Case\.test//i"""))
        assertTrue(UserscriptMatcher.matches(insensitive, "https://case.test/page"))

        val sensitive = metadata(include = listOf("""/^https://Case\.test/"""))
        assertFalse(UserscriptMatcher.matches(sensitive, "https://case.test/page"))
    }

    @Test
    fun includeAllUrlsIsRecognised() {
        assertTrue(UserscriptMatcher.matches(metadata(include = listOf("<all_urls>")), "https://anything.test/x"))
        assertFalse(UserscriptMatcher.matches(metadata(include = listOf("<all_urls>")), "mailto:a@b.test"))
    }

    @Test
    fun combinedRulesFollowChromeSemantics() {
        val rules = metadata(
            match = listOf("*://*.example.com/*"),
            include = listOf("https://other.test/*"),
            exclude = listOf("https://example.com/blocked*")
        )
        assertTrue(UserscriptMatcher.matches(rules, "https://a.example.com/page"))
        assertTrue(UserscriptMatcher.matches(rules, "https://example.com/page"))
        assertTrue(UserscriptMatcher.matches(rules, "https://other.test/page"))
        assertFalse(UserscriptMatcher.matches(rules, "https://example.com/blocked/page"))
        assertFalse(UserscriptMatcher.matches(rules, "https://nope.test/page"))
    }

    @Test
    fun matchesPatternIsAvailableStandalone() {
        assertTrue(UserscriptMatcher.matchesPattern("https://example.com/*", "https://example.com/direct"))
        assertFalse(UserscriptMatcher.matchesPattern("https://example.com/*", "https://other.com/direct"))
    }
    @Test
    fun excludeMatchVetoesMatchingUrl() {
        val metadata = UserscriptMetadata(
            match = listOf("https://a.test/*"),
            excludeMatch = listOf("https://a.test/private/*")
        )
        assertTrue(UserscriptMatcher.matches(metadata, "https://a.test/public"))
        assertFalse(UserscriptMatcher.matches(metadata, "https://a.test/private/x"))
    }

    @Test
    fun excludeMatchUsesPatternSemantics() {
        // exclude-match 是 match pattern（host 逐段匹配），不是 glob 子串。
        val metadata = UserscriptMetadata(
            match = listOf("*://a.test/*"),
            excludeMatch = listOf("*://b.test/*")
        )
        assertTrue(UserscriptMatcher.matches(metadata, "https://a.test/x"))
        assertFalse(UserscriptMatcher.matches(metadata, "https://b.test/x"))
    }

    @Test
    fun excludeMatchWildcardHostVetoes() {
        val metadata = UserscriptMetadata(
            match = listOf("https://*.a.test/*"),
            excludeMatch = listOf("https://secret.a.test/*")
        )
        assertTrue(UserscriptMatcher.matches(metadata, "https://www.a.test/x"))
        assertFalse(UserscriptMatcher.matches(metadata, "https://secret.a.test/x"))
    }

    @Test
    fun ipv6LiteralPatternMatchesBareHost() {
        // URL 侧解析会剥掉方括号，pattern 侧保留方括号作为「这是 IPv6」的标记。
        val metadata = UserscriptMetadata(match = listOf("http://[::1]/*"))
        assertTrue(UserscriptMatcher.matches(metadata, "http://[::1]/path"))
        assertFalse(UserscriptMatcher.matches(metadata, "http://[::2]/path"))
    }

    @Test
    fun ipv6LiteralPatternWithPort() {
        val metadata = UserscriptMetadata(match = listOf("http://[::1]:8080/*"))
        assertTrue(UserscriptMatcher.matches(metadata, "http://[::1]:8080/x"))
        assertFalse(UserscriptMatcher.matches(metadata, "http://[::1]:9090/x"))
    }

    @Test
    fun unpairedIpv6BracketIsIllegalPattern() {
        val metadata = UserscriptMetadata(match = listOf("http://[::1/*"))
        assertFalse(UserscriptMatcher.matches(metadata, "http://[::1]/x"))
    }

}