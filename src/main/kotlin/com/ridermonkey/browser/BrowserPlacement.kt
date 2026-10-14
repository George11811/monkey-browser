package com.ridermonkey.browser

/**
 * 浏览器会话当前挂载的 UI 容器。
 *
 * ToolWindow 与 Editor Tab 共用同一套会话模型，切换容器时不重建会话。
 */
enum class BrowserPlacement {
    TOOL_WINDOW,
    EDITOR_TAB
}
