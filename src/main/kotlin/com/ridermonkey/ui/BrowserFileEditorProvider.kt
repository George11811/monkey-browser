package com.ridermonkey.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.ridermonkey.browser.BrowserContainers
import com.ridermonkey.browser.BrowserVirtualFile

/** 只接管 [BrowserVirtualFile] 的编辑区提供者。 */
class BrowserFileEditorProvider : FileEditorProvider {

    override fun accept(project: Project, file: VirtualFile): Boolean = file is BrowserVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val container = BrowserContainers.get(project)
            ?: error("浏览器容器尚未创建，无法在编辑区打开")
        val tab = container.tabFor(file)
            ?: error("找不到虚拟文件对应的浏览器标签页：" + file.path)
        return BrowserFileEditor(project, file, tab.panel) { container.onEditorClosed(tab) }
    }

    override fun getEditorTypeId(): String = "rider-monkey-browser-editor"

    /** 浏览器不依赖索引，索引构建期间也能用；不声明会被平台刷警告。 */
    override fun isDumbAware(): Boolean = true

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
