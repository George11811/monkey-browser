package com.ridermonkey.userscript

/**
 * `@grant` 能力裁剪（最小权限）。
 *
 * Tampermonkey 的语义是「**显式声明**才有」：脚本只拿到它 `@grant` 里列出的 API。
 * 本对象把元数据里的 grant 列表映射成本实现真正提供的能力名，并与 gm-api.js 里
 * `__rmGranted(...)` 的判断保持一致（改一处必须改另一处）。
 *
 * 为什么重要：不裁剪就等于给每个脚本全量能力，而 `@grant none` / 未声明 grant 的脚本
 * 本来就不该有 GM 能力。裁剪后「脚本能力 = 它声明过的能力」。
 *
 * 有意偏离：Tampermonkey 在**完全未声明** `@grant` 时按 `none` 处理（不给 API）。
 * 本实现同样不给，但会在脚本管理界面把它标成「疑似需要 GM 却未声明」，避免静默失效
 * （沿用 ADR-0004 D5 的做法：偏离必须可被发现）。
 */
object GmCapabilities {

    /** 本实现已实现的 API 名。 */
    private val KNOWN: List<String> = listOf(
        "GM_getValue",
        "GM_setValue",
        "GM_deleteValue",
        "GM_listValues",
        "GM_addStyle",
        "GM.getValue",
        "GM.setValue",
        "GM.deleteValue",
        "GM.listValues",
        "GM.addStyle",
        "GM_xmlhttpRequest",
        "GM.xmlHttpRequest",
        "GM_info",
        "GM_getResourceText",
        "GM_getResourceURL",
        "GM_setClipboard",
        "GM_notification",
        "GM.getResourceText",
        "GM.getResourceUrl",
        "GM.setClipboard",
        "GM.notification",
        "GM_download",
        "GM.download",
        "GM_openInTab",
        "GM.openInTab"
    )

    /** 该脚本被授权的 API 集合（大小写不敏感，未识别的 grant 静默忽略）。 */
    fun granted(metadata: UserscriptMetadata): Set<String> {
        val declared = metadata.grant.map { it.trim() }
        return KNOWN.filter { name -> declared.any { it.equals(name, ignoreCase = true) } }.toSet()
    }

    /** 是否要注入 GM 运行时（一个能力都没有就不注入，连快照都不嵌入）。 */
    fun providesAny(metadata: UserscriptMetadata): Boolean = granted(metadata).isNotEmpty()

    /**
     * 脚本正文里出现了 GM 用法，但没有任何被授权的 API。
     *
     * 用于管理界面提示：这类脚本在本实现下会「装了却不生效」，必须让用户看见。
     */
    fun looksLikeItUsesGmWithoutGrant(body: String, metadata: UserscriptMetadata): Boolean {
        if (providesAny(metadata)) return false
        return body.contains("GM_") || body.contains("GM.")
    }
}