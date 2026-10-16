package com.ridermonkey.browser

/**
 * 页面弹窗（`target="_blank"` 链接、`window.open`）的处理方式。
 *
 * 抽成纯决策、不碰 UI，是为了能直接单测 —— 这是本批里**唯一写错了会静默改变浏览器行为**的部分。
 */
sealed interface PopupDecision {

    /** 在插件里新开标签页。 */
    data class OpenTab(val url: String) : PopupDecision

    /** 交给系统默认浏览器 / 系统协议处理器。 */
    data class SystemBrowser(val url: String) : PopupDecision

    /**
     * 不拦截，放行 CEF 默认行为。
     *
     * 两类目标走这里：
     * - **没有真实地址**的弹窗（空串 / 任何 `about:` url）。这类弹窗的典型用法是
     *   `w = window.open(); w.document.write(...)`（OAuth 中转、在线编辑器）；而本构建的
     *   `CefLifeSpanHandler.onBeforePopup` 只有四个参数、**拿不到弹窗浏览器句柄**，
     *   拦截等于把 `w` 变成 null、把对方流程弄坏。
     * - **我们不认识的协议**（`data:` / `blob:` / `file:` / `javascript:` / 自定义 scheme）。
     *   它们既进不了内嵌标签页，也不该由**不可信页面**决定交给操作系统去处理 ——
     *   `file:` 交给系统等于让默认关联程序去打开页面指定的本地路径。宁可不接管。
     */
    data object Ignore : PopupDecision
}

object PopupRouting {

    /**
     * 根据弹窗目标地址决定怎么处理。规则按顺序：
     *
     * 1. 空串 / 任何 `about:` → [PopupDecision.Ignore]（没有真实地址）
     * 2. `http` / `https` → [PopupDecision.OpenTab]
     * 3. `mailto:` / `tel:` → [PopupDecision.SystemBrowser]
     * 4. 其余一律 [PopupDecision.Ignore]
     */
    fun decide(targetUrl: String?): PopupDecision {
        val url = targetUrl?.trim().orEmpty()
        if (url.isEmpty() || url.startsWith(ABOUT_SCHEME, ignoreCase = true)) return PopupDecision.Ignore
        if (isHttp(url)) return PopupDecision.OpenTab(url)
        if (isOsHandler(url)) return PopupDecision.SystemBrowser(url)
        return PopupDecision.Ignore
    }

    /**
     * 只有 http(s) 进插件标签页。
     *
     * 判据必须带 `://`：只判 `startsWith("http")` 会把 `httpx://` 这类前缀误判成网页。
     */
    private fun isHttp(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /**
     * 只有这两个协议交给系统。
     *
     * 它们是**公认由系统处理、且参数不可执行**的（打开邮件客户端 / 拨号器）。
     * 其余自定义 scheme 也「本该」由系统接管，但那是**页面可控的协议处理器调用**：
     * 浏览器自身遇到这类协议会先弹确认框，我们静默调用就等于替用户点了「允许」，所以不做。
     */
    private fun isOsHandler(url: String): Boolean =
        url.startsWith("mailto:", ignoreCase = true) || url.startsWith("tel:", ignoreCase = true)

    private const val ABOUT_SCHEME = "about:"
}