package com.ridermonkey.userscript

/**
 * GM API 里需要触达「标签页 / 文件系统」的那一小部分能力。
 *
 * 抽成接口而不是让 [GmBridge] 直接依赖 UI，理由有两条：
 * - 桥本身保持可单测（不引入 Swing / Project）；
 * - 依赖方向保持单向：UI 实现接口并注册进来，桥不反向引用 UI。
 *
 * 由 [com.ridermonkey.ui.BrowserContainer] 实现。
 */
interface GmHost {

    /**
     * 打开一个新标签页。
     *
     * @param active 是否把它切到前台（对应 `GM_openInTab` 的 `options.active`）
     * @return 插件内唯一的标签页编号；返回值会被回传给脚本用作句柄，**不要返回 0**
     */
    fun openTab(url: String, active: Boolean): Int

    /**
     * 关闭由 [openTab] 打开过的标签页。
     *
     * @return true 表示确实关掉了；false 表示编号不存在（可能已被用户手动关闭）
     */
    fun closeTabById(tabId: Int): Boolean
}
