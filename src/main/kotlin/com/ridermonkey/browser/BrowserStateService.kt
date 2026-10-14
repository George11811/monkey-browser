package com.ridermonkey.browser

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * 浏览器标签页的会话恢复状态（工程级）。
 *
 * 用平台标准的 \`PersistentStateComponent\`：容器在标签页/地址变化时回写内存状态，
 * 平台在关闭工程或退出 IDE 时序列化到 \`rider-monkey-browser.xml\`。
 *
 * 只持久化 URL（不持久化「哪个标签在编辑区」）：启动时自动打开编辑标签会打扰用户，
 * 且编辑区标签由平台的会话恢复机制管，两边都做容易打架。
 */
@State(name = "RiderMonkeyBrowser", storages = [Storage("rider-monkey-browser.xml")])
@Service(Service.Level.PROJECT)
class BrowserStateService : PersistentStateComponent<BrowserStateService.BrowserState> {

    /** 序列化载体：必须是 public、有无参构造、字段是 public var。 */
    class BrowserState {
        var tabs: MutableList<TabState> = mutableListOf()
        var activeIndex: Int = 0
    }

    class TabState {
        var url: String? = null
    }

    private var state = BrowserState()

    override fun getState(): BrowserState = state

    override fun loadState(state: BrowserState) {
        this.state = state
    }

    /** 回写当前标签页。地址为空白或起始页的不记，避免恢复出一堆无意义的空白页。 */
    fun remember(urls: List<String>, activeIndex: Int) {
        state.tabs = urls
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "about:blank" }
            .take(MAX_TABS)
            .map { url -> TabState().also { it.url = url } }
            .toMutableList()
        state.activeIndex = activeIndex.coerceAtLeast(0)
    }

    fun savedUrls(): List<String> = state.tabs.mapNotNull { it.url }.filter { it.isNotBlank() }

    fun savedActiveIndex(): Int = state.activeIndex

    companion object {
        /** 上限：避免恢复出几十个标签页把内存吃光。 */
        private const val MAX_TABS = 8

        fun getInstance(project: Project): BrowserStateService = project.service()
    }
}
