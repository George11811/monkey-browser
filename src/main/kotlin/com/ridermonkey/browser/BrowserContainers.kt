package com.ridermonkey.browser

import com.intellij.openapi.project.Project
import com.ridermonkey.ui.BrowserContainer
import java.util.concurrent.ConcurrentHashMap

/**
 * 工程 -> 浏览器容器 的登记表。
 *
 * FileEditorProvider 只能在 createEditor 时拿到 Project 与 VirtualFile，拿不到工具窗里的容器，
 * 因此需要一个按工程查容器的入口。容器在 ToolWindow 生命周期结束时自行注销。
 */
object BrowserContainers {

    private val containers = ConcurrentHashMap<Project, BrowserContainer>()

    fun put(project: Project, container: BrowserContainer) {
        containers[project] = container
    }

    fun get(project: Project): BrowserContainer? = containers[project]

    /**
     * 只在仍是同一个实例时撤销注册。
     *
     * 无条件 `remove(project)` 会把**后创建**的容器的注册一起抹掉 ——
     * 过期容器被 dispose 时会让在册的新容器变成"查不到"。
     */
    fun remove(project: Project, container: BrowserContainer) {
        containers.remove(project, container)
    }
}
