package com.ridermonkey.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.ridermonkey.browser.BrowserContainers

/**
 * Monkey Browser ToolWindow 的工厂。
 *
 * 内容交给 [BrowserContainer]：它负责在「浏览器面板」与「已移到编辑区」之间切换。
 */
class BrowserToolWindowFactory : ToolWindowFactory {

    private val logger = Logger.getInstance("RiderMonkeyBrowser")

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 平台在少数布局操作后会再次调用本方法。若此时已有容器在册，就会同时存在两个容器、
        // 各自创建一套 JCEF 内核（用户实测：一个标签页出现两条 target）。
        // 这里先只记告警以便定位，暂不改行为，避免在原因未确认时引入回归。
        if (BrowserContainers.get(project) != null) {
            logger.warn("[RM-BROWSER] 同一工程已存在浏览器容器，createToolWindowContent 被再次调用")
        }
        val container = BrowserContainer(project)
        Disposer.register(toolWindow.disposable, container)
        logger.info(
            "[RM-BROWSER] 创建容器#" + System.identityHashCode(container) +
                " toolWindow=" + toolWindow.id
        )

        val content = ContentFactory.getInstance().createContent(container.component, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }
}
