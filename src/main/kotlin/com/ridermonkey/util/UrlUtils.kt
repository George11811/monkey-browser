package com.ridermonkey.util

/**
 * 地址栏输入的规范化工具。
 *
 * 纯逻辑、无 IDE 依赖，便于单元测试。
 *
 * 设计取舍：本方法定位是「地址栏导航入口」，只放行三类输入——
 * 分层 scheme（带双斜杠）、已知的非分层 scheme、裸主机名。
 * 危险 scheme（javascript: / vbscript:，**无论是否带双斜杠**）一律拒绝；
 * data: 也不放行（Chromium 本来就拦截顶层 data: 导航，放行只会静默失败）。
 * mailto: / tel: / blob: 同样拒绝，属有意为之。
 */
object UrlUtils {

    /** 新标签页的起始地址。 */
    const val START_PAGE: String = "about:blank"

    private const val DEFAULT_SCHEME = "https://"

    /**
     * 带双斜杠的 scheme。
     *
     * 末尾的 .+ 不是装饰：没有它，"https://" 会被判定为合法输入，随后 loadURL 静默失败。
     */
    private val HIERARCHICAL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://.+")

    /**
     * 危险 scheme。必须在分层 scheme 判断**之前**检查，否则
     * "javascript://%0aalert(1)" 会命中 HIERARCHICAL_SCHEME 被原样放行。
     */
    private val DANGEROUS_SCHEMES = listOf("javascript:", "vbscript:")

    /** 不带双斜杠的已知 scheme。有意排除 data: 与 javascript:，见文件头说明。 */
    private val OPAQUE_SCHEMES = listOf("about:", "file:", "chrome:", "devtools:", "view-source:")

    /**
     * 把地址栏里的原始输入规范化为可交给 JCEF 的 URL。
     *
     * - 空输入或含空白字符的输入返回 null。
     * - 已带 scheme 的输入原样返回。
     * - 形如 example.com 或 localhost:8080 的裸主机名补上 https:// 。
     * - 其余（例如搜索词）返回 null，由调用方给出提示。
     */
    fun normalizeInput(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // URL 里不允许出现原始空白。注意 Kotlin 的 Char.isWhitespace() 等价于
        // Character.isWhitespace() || Character.isSpaceChar()，因此 NBSP(U+00A0)、
        // 全角空格(U+3000) 已经被覆盖，不需要额外判断。
        if (text.any { it.isWhitespace() }) return null

        if (DANGEROUS_SCHEMES.any { text.startsWith(it, ignoreCase = true) }) return null

        if (HIERARCHICAL_SCHEME.containsMatchIn(text)) return text

        // 与 HIERARCHICAL_SCHEME 保持一致：scheme 之后必须还有内容，
        // 否则 "about:" / "file:" 这类输入会被放行，随后 loadURL 静默失败。
        if (OPAQUE_SCHEMES.any { text.startsWith(it, ignoreCase = true) && text.length > it.length }) return text

        return if (looksLikeHost(text)) DEFAULT_SCHEME + text else null
    }

    private fun looksLikeHost(text: String): Boolean {
        val hostPart = text.substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPart.isEmpty()) return false

        // 方括号 IPv6 字面量：[::1] 或 [::1]:8080 。
        // 这里不能提前 return true——必须和普通主机走同一套端口校验。
        if (hostPart.startsWith("[")) {
            val end = hostPart.indexOf(']')
            if (end <= 1) return false
            if (!isIpv6Literal(hostPart.substring(1, end))) return false
            val rest = hostPart.substring(end + 1)
            return rest.isEmpty() || isValidPort(rest)
        }

        val colon = hostPart.indexOf(':')
        if (colon >= 0 && !isValidPort(hostPart.substring(colon))) return false

        val host = if (colon >= 0) hostPart.substring(0, colon) else hostPart
        if (host.isEmpty()) return false

        // 允许 FQDN 绝对形式的单个尾点（example.com.），但拒绝空标签（a..b）与前导点（.example.com）。
        val bare = host.removeSuffix(".")
        if (bare.equals("localhost", ignoreCase = true)) return true
        if (bare.isEmpty() || bare.startsWith('.') || !bare.contains('.')) return false
        return bare.split('.').all { isValidLabel(it) }
    }

    /** 主机标签：允许 ASCII 字母/数字/连字符，以及非 ASCII 字符（IDN）。拒绝 ! _ < 等。 */
    private fun isValidLabel(label: String): Boolean =
        label.isNotEmpty() && label.all {
            it == '-' || it.code >= 0x80 ||
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9'
        }

    /** rest 形如 ":8080"：冒号后必须是 1..65535 的 ASCII 十进制数字。 */
    private fun isValidPort(rest: String): Boolean {
        if (!rest.startsWith(":")) return false
        val port = rest.substring(1)
        if (port.isEmpty() || !port.all { it in '0'..'9' }) return false
        val value = port.toIntOrNull() ?: return false
        return value in 1..65535
    }

    /** 轻量校验方括号内容，只保证像 IPv6 且不含危险字符，不做完整 IPv6 语法解析。 */
    private fun isIpv6Literal(literal: String): Boolean {
        if (literal.isEmpty()) return false
        if (literal.any { it !in "0123456789abcdefABCDEF:.%" }) return false
        return literal.contains(':')
    }
}
