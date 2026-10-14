package com.ridermonkey.browser

/**
 * 浏览器会话的「可持久化」状态。
 *
 * 注意：这里只放可序列化的数据。绝不要把 JBCefBrowser、JBCefClient 等本地资源放进来——
 * 它们是不可序列化的运行时对象，并且有明确的 dispose 生命周期。
 */
data class BrowserSessionState(
    val id: String,
    val url: String,
    val title: String,
    val placement: BrowserPlacement,
    val isPinned: Boolean = false
)
