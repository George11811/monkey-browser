package com.ridermonkey.userscript

/**
 * 用户脚本 URL 匹配器，语义对齐 Tampermonkey / Violentmonkey 的用户脚本规则。
 *
 * ## match pattern
 *
 * 形如 `scheme://host[:port]/path`：
 *
 * - scheme：`http` / `https` / `file` / `ftp` / `*`；`*` 等价于 http 或 https。
 * - host：`*` 匹配任意主机；`*.example.com` 匹配 example.com 自身**及其子域**
 *   （这是 Chrome/VM 的实际行为，不是笔误）；其余按精确主机、不区分大小写匹配。
 * - port：可省略、写 `*`（两者都匹配任意端口）、或写具体数字（只匹配该有效端口；
 *   scheme 默认端口参与比较，如 https 未显式写端口时视作 443）。
 *   方括号 IPv6 pattern 本实现不支持，视为非法 pattern。
 * - file 模式忽略 host 与 port（file URL 本身没有有意义的主机）。
 * - path 必须存在且以斜杠开头；路径里的 `*` 可以跨越斜杠，其余字符按字面匹配。
 * - **本实现只看 path，不看 query 与 fragment**。这一点与 Chrome 扩展的 match pattern 不同
 *   （Chrome 用 path+query 做路径匹配），是**有意对齐 Violentmonkey 文档中「@match 忽略
 *   query/hash」的行为**，也是首版的产品口径。待真实 Tampermonkey 实测后再决定是否调整。
 * - `<all_urls>` 匹配任意 http / https / file / ftp URL。
 *
 * ## include / exclude
 *
 * `include` / `exclude` 支持两种写法：
 *
 * - glob：`*` 匹配任意字符（可跨越斜杠），`?` 匹配单个字符，其余字面匹配，**整串匹配**。
 * - 正则：形如 `/body/flags`，flags 只接受 JS 合法标志 `gimsuy`（不允许重复）；
 *   用「存在匹配」而非整串匹配（与 Tampermonkey 的 test() 行为一致）。
 *
 * 注意 glob 与正则的区分**只取决于末尾 flags 是否合法**：像 `/some/path/file` 这种以路径
 * 结尾的 glob，其 `file` 含非法 flag 字符，会被正确判回 glob；只有 `/.../`、`/.../i`
 * 这类才会被当成正则。
 *
 * ## 组合规则
 *
 * - `match` 与 `include` 取**并集**，任一命中即为命中。
 * - `exclude` **否决**：任一 exclude 命中则整体不匹配（先算 exclude，再算并集）。
 * - `match` 与 `include` 都为空时**不匹配任何 URL**。这是有意的保守选择：
 *   规则缺失的文件（元数据块为空、只有 `noframes` 等）不会被误注入到所有页面。
 *   真实 VM 的默认是「匹配所有 URL」，我们偏离了它，因此调用方应先用 [hasNoUrlRules]
 *   识别这种脚本并给出警告，而不是让它静默不生效。
 * - `noframes` 不参与 URL 匹配，由调用方处理。
 *
 * 任何非法 pattern（空串、缺少路径、未知 scheme、非法端口）都返回 false，不抛异常。
 */
object UserscriptMatcher {

    /** 匹配所有受支持 scheme 的特殊值。 */
    private const val ALL_URLS = "<all_urls>"

    /** `<all_urls>` 覆盖的 scheme。 */
    private val ALL_URLS_SCHEMES = setOf("http", "https", "file", "ftp")

    /** match pattern 的 `*` scheme 等价于这两种 scheme。 */
    private val STAR_SCHEMES = setOf("http", "https")

    /** match pattern 显式写出的 scheme 只允许这几种。 */
    private val SUPPORTED_SCHEMES = setOf("http", "https", "file", "ftp")

    /** 正则字面量允许的 flags（JS 合法标志）。 */
    private const val LEGAL_REGEX_FLAGS = "gimsuy"

    /**
     * 判断 URL 是否命中元数据里的规则。
     *
     * 先算 exclude / exclude-match（任一命中即整体否决），再算 match 与 include 的并集。
     * match 与 include 都为空时返回 false，见对象级 KDoc 的说明。
     */
    fun matches(metadata: UserscriptMetadata, url: String): Boolean {
        // exclude-match 用 match pattern 语义（Chrome 口径），exclude 用 glob/正则语义（VM 口径）。
        if (metadata.excludeMatch.any { matchesPattern(it, url) }) return false
        if (metadata.exclude.any { matchesIncludeRule(it, url) }) return false
        if (hasNoUrlRules(metadata)) return false
        if (metadata.match.any { matchesPattern(it, url) }) return true
        return metadata.include.any { matchesIncludeRule(it, url) }
    }

    /**
     * 该脚本是否完全没有 URL 规则（`match` 与 `include` 都为空）。
     *
     * 这种脚本在本实现口径下不会注入任何页面；真实 Tampermonkey/Violentmonkey 的默认是
     * 「匹配所有 URL」。我们有意选择更保守的行为，因此调用方应据此给出警告。
     */
    fun hasNoUrlRules(metadata: UserscriptMetadata): Boolean =
        metadata.match.isEmpty() && metadata.include.isEmpty()

    /**
     * 判断 URL 是否命中单个 match pattern。
     *
     * pattern 非法（空串、缺少以斜杠开头的路径、未知 scheme、非法端口）时返回 false。
     * URL 无法解析成带双斜杠的分层地址时也返回 false。
     */
    fun matchesPattern(pattern: String, url: String): Boolean {
        val normalized = pattern.trim()
        if (normalized.isEmpty()) return false

        val target = ParsedUrl.parse(url) ?: return false
        if (normalized == ALL_URLS) return target.scheme in ALL_URLS_SCHEMES

        val separator = normalized.indexOf("://")
        if (separator <= 0) return false

        val schemePattern = normalized.substring(0, separator).lowercase()
        val rest = normalized.substring(separator + 3)
        val slash = rest.indexOf('/')
        if (slash < 0) return false
        val hostPattern = rest.substring(0, slash)
        val pathPattern = rest.substring(slash)

        val schemeMatches = when {
            schemePattern == "*" -> target.scheme in STAR_SCHEMES
            schemePattern in SUPPORTED_SCHEMES -> schemePattern == target.scheme
            else -> false
        }
        if (!schemeMatches) return false

        if (schemePattern != "file") {
            if (hostPattern.isEmpty()) return false
            val hostAndPort = splitHostAndPort(hostPattern) ?: return false
            if (!hostMatches(hostAndPort.first, target.host)) return false
            if (!portMatches(hostAndPort.second, target.scheme, target.port)) return false
        }

        return wildcardRegex(pathPattern).matches(target.path)
    }

    /** include / exclude 单条规则的匹配：正则写法优先，否则按 glob 处理。 */
    private fun matchesIncludeRule(pattern: String, url: String): Boolean {
        val normalized = pattern.trim()
        if (normalized.isEmpty()) return false
        if (normalized == ALL_URLS) {
            val target = ParsedUrl.parse(url) ?: return false
            return target.scheme in ALL_URLS_SCHEMES
        }

        val literal = regexLiteral(normalized)
        if (literal != null) {
            return try {
                Regex(literal.first, literal.second).containsMatchIn(url)
            } catch (e: IllegalArgumentException) {
                false
            }
        }
        return globRegex(normalized).matches(url)
    }

    /**
     * 识别 `/body/flags` 形式的正则字面量。
     *
     * flags 只接受 JS 合法标志 `gimsuy`，且不允许重复；出现其它字符就返回 null 交给 glob。
     * 这条规则正是把 `/some/path/file`（glob）与 `/foo/i`（正则）区分开的关键——
     * 早期实现把「以斜杠开头且末尾斜杠后全是字母」都当正则，导致 `/a/b` 被判成正则
     * `a`，几乎命中所有 URL。
     *
     * `g` / `u` / `y` 在 Kotlin 正则里没有对应语义，接受但忽略；`i` / `m` / `s` 会映射。
     */
    private fun regexLiteral(text: String): Pair<String, Set<RegexOption>>? {
        if (text.length < 2 || !text.startsWith("/")) return null
        val closing = text.lastIndexOf('/')
        if (closing <= 0) return null
        val flags = text.substring(closing + 1)
        if (!flags.all { it in LEGAL_REGEX_FLAGS }) return null
        if (flags.toSet().size != flags.length) return null
        return text.substring(1, closing) to regexOptions(flags)
    }

    private fun regexOptions(flags: String): Set<RegexOption> {
        val options = mutableSetOf<RegexOption>()
        if (flags.contains('i')) options.add(RegexOption.IGNORE_CASE)
        if (flags.contains('m')) options.add(RegexOption.MULTILINE)
        if (flags.contains('s')) options.add(RegexOption.DOT_MATCHES_ALL)
        return options
    }

    /**
     * 把 pattern 的 host 段拆成「主机 + 可选端口」。
     *
     * 端口省略或写 `*` 表示匹配任意端口；写数字时校验 1..65535。
     * 方括号 IPv6 pattern 形如 `[::1]` 或 `[::1]:8080`；方括号不配对视为非法。
     */
    private fun splitHostAndPort(hostPattern: String): Pair<String, String?>? {
        // IPv6 字面量写成 [::1]（可带端口，如 [::1]:8080）。URL 侧解析时已剥掉方括号，
        // 因此这里保留方括号作为「这是 IPv6」的标记，由 hostMatches 决定怎么比。
        if (hostPattern.startsWith("[")) {
            val close = hostPattern.indexOf(']')
            if (close < 0) return null
            val host = hostPattern.substring(0, close + 1)
            val rest = hostPattern.substring(close + 1)
            if (rest.isEmpty()) return host to null
            if (!rest.startsWith(":")) return null
            return portPart(host, rest.substring(1))
        }
        val colon = hostPattern.indexOf(':')
        if (colon < 0) return hostPattern to null
        val host = hostPattern.substring(0, colon)
        val port = hostPattern.substring(colon + 1)
        if (host.isEmpty() || port.isEmpty()) return null
        return portPart(host, port)
    }

    /** 校验端口段并组装结果；`*` 表示任意端口，数字必须在 1..65535。 */
    private fun portPart(host: String, port: String): Pair<String, String?>? {
        if (port.isEmpty()) return null
        if (port == "*") return host to port
        if (port.length > 5 || !port.all { it in '0'..'9' }) return null
        val value = port.toIntOrNull() ?: return null
        if (value !in 1..65535) return null
        return host to port
    }

    /** 判断 URL 的有效端口是否满足 pattern 的端口要求。 */
    private fun portMatches(portPattern: String?, scheme: String, urlPort: Int?): Boolean {
        if (portPattern == null || portPattern == "*") return true
        val expected = portPattern.toIntOrNull() ?: return false
        return effectivePort(scheme, urlPort) == expected
    }

    /** URL 的有效端口：显式端口优先，否则取 scheme 默认端口。 */
    private fun effectivePort(scheme: String, explicit: Int?): Int? = explicit ?: when (scheme) {
        "http" -> 80
        "https" -> 443
        "ftp" -> 21
        else -> null
    }

    /** host 匹配：星号通配、`*.domain`（含 domain 自身）、精确主机。 */
    private fun hostMatches(pattern: String, host: String): Boolean {
        // IPv6：pattern 保留方括号，URL 侧的 host 已无方括号，直接逐字比较（不区分大小写）。
        if (pattern.startsWith("[") && pattern.endsWith("]")) {
            return host.equals(pattern.substring(1, pattern.length - 1), ignoreCase = true)
        }
        val normalized = pattern.lowercase()
        if (normalized == "*") return true
        if (normalized.startsWith("*.")) {
            val bare = normalized.substring(2)
            return host.equals(bare, ignoreCase = true) || host.endsWith(".$bare", ignoreCase = true)
        }
        return host.equals(normalized, ignoreCase = true)
    }

    /** match pattern 的 path：只有星号是通配，可以跨越斜杠。 */
    private fun wildcardRegex(pattern: String): Regex {
        val builder = StringBuilder()
        val parts = pattern.split('*')
        for ((index, part) in parts.withIndex()) {
            if (index > 0) builder.append(".*")
            builder.append(Regex.escape(part))
        }
        return Regex(builder.toString())
    }

    /** include 的 glob：星号匹配任意字符，问号匹配单个字符，其余字面匹配。 */
    private fun globRegex(pattern: String): Regex {
        val builder = StringBuilder()
        var index = 0
        while (index < pattern.length) {
            when (pattern[index]) {
                '*' -> {
                    builder.append(".*")
                    index++
                }
                '?' -> {
                    builder.append('.')
                    index++
                }
                else -> {
                    var end = index
                    while (end < pattern.length && pattern[end] != '*' && pattern[end] != '?') end++
                    builder.append(Regex.escape(pattern.substring(index, end)))
                    index = end
                }
            }
        }
        return Regex(builder.toString())
    }

    /** 分层 URL 的拆解结果：scheme 与 host 已小写，path 保留原大小写。 */
    private class ParsedUrl(val scheme: String, val host: String, val port: Int?, val path: String) {

        companion object {
            /**
             * 只接受 scheme 加双斜杠的分层 URL；其余（mailto:、javascript: 等）返回 null。
             *
             * URL 没有显式路径时 path 记作斜杠，因此 host 后面的通配路径也能命中。
             * query 与 fragment 会从 path 中截掉（见对象级 KDoc 的口径说明）。
             */
            fun parse(url: String): ParsedUrl? {
                val colon = url.indexOf(':')
                if (colon <= 0) return null
                val scheme = url.substring(0, colon).lowercase()
                val validScheme = scheme.isNotEmpty() &&
                    scheme.all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }
                if (!validScheme) return null
                if (!url.startsWith("//", colon + 1)) return null

                val rest = url.substring(colon + 3)
                val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
                    .let { if (it < 0) rest.length else it }
                val authority = rest.substring(0, authorityEnd)
                val host = extractHost(authority)
                val port = extractPort(authority)

                var path = if (authorityEnd < rest.length && rest[authorityEnd] == '/') {
                    rest.substring(authorityEnd)
                } else {
                    "/"
                }
                val cut = path.indexOfFirst { it == '?' || it == '#' }
                if (cut >= 0) path = path.substring(0, cut)
                if (path.isEmpty()) path = "/"
                return ParsedUrl(scheme, host, port, path)
            }

            /** 从 authority 中取主机：去掉 userinfo 与端口，IPv6 去掉方括号，统一小写。 */
            private fun extractHost(authority: String): String {
                var text = stripUserInfo(authority)
                if (text.startsWith("[")) {
                    val end = text.indexOf(']')
                    if (end > 0) return text.substring(1, end).lowercase()
                }
                val colon = text.indexOf(':')
                if (colon >= 0) text = text.substring(0, colon)
                return text.lowercase()
            }

            /** 从 authority 中取显式端口；没有或非法时返回 null。 */
            private fun extractPort(authority: String): Int? {
                var text = stripUserInfo(authority)
                if (text.startsWith("[")) {
                    val end = text.indexOf(']')
                    if (end <= 0) return null
                    text = text.substring(end + 1)
                } else {
                    val colon = text.indexOf(':')
                    if (colon < 0) return null
                    text = text.substring(colon)
                }
                if (!text.startsWith(":")) return null
                val digits = text.substring(1)
                if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
                return digits.toIntOrNull()
            }

            private fun stripUserInfo(authority: String): String {
                val at = authority.lastIndexOf('@')
                return if (at >= 0) authority.substring(at + 1) else authority
            }
        }
    }
}