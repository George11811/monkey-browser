package com.ridermonkey.userscript

import java.net.URI

/**
 * `GM_xmlhttpRequest` 的跨域授权（`@connect`）。
 *
 * **最小权限**：没有声明 `@connect` 就一个外部主机都不许访问。这与 Tampermonkey 的口径一致
 * （方案 §8 也把「按 @connect 等元数据授权」列为本项目的风险对策）。
 *
 * 支持三种写法：
 * - `@connect example.com`：精确主机；
 * - `@connect *.example.com`：example.com 自身及其子域；
 * - `@connect *`：任意主机（显式放弃限制，用户自己负责）；
 * - `@connect self`：当前文档所在主机。
 *
 * 主机名比较不区分大小写；解析不出主机的 URL 一律拒绝。
 */
object ConnectRules {

    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { URI(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun allows(metadata: UserscriptMetadata, requestUrl: String, documentUrl: String?): Boolean {
        val host = hostOf(requestUrl) ?: return false
        val rules = metadata.connect.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (rules.isEmpty()) return false
        if (rules.any { it == "*" }) return true
        if (rules.any { it == "self" } && host == hostOf(documentUrl)) return true
        return rules.any { rule ->
            if (rule.startsWith("*.")) {
                // 与 UserscriptMatcher.hostMatches 保持一致：*.example.com 也包含 example.com 自身。
                val bare = rule.substring(2)
                host == bare || host.endsWith("." + bare)
            } else {
                rule == host
            }
        }
    }
}