package com.ridermonkey.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * 把某个浏览器标签页的面板放进中央编辑区的 FileEditor。
 *
 * 它**不创建**浏览器：组件始终是同一个 [BrowserPanel] 的那一个，容器切换时由 Swing 完成
 * 父容器迁移（先由 BrowserContainer 从标签条摘掉，再挂到这里）。
 */
class BrowserFileEditor(
    private val project: Project,
    private val file: VirtualFile,
    private val panel: BrowserPanel,
    private val onClosed: () -> Unit
) : UserDataHolderBase(), FileEditor {

    override fun getComponent(): JComponent = panel.component

    override fun getPreferredFocusedComponent(): JComponent = panel.component

    override fun getName(): String = "Monkey Browser"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        // 用户直接关掉编辑标签（而不是点「侧边栏」）时，也要把面板还给工具窗，
        // 否则 JCEF 组件会失去父容器、变成孤儿。
        onClosed()
    }
}
