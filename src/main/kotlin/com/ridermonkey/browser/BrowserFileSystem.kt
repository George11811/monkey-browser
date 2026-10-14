package com.ridermonkey.browser

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileSystem

/**
 * 浏览器标签页专用的虚拟文件系统。
 *
 * 平台自带的 \`LightVirtualFile\` 位于 testFramework 包（生产代码不该用），
 * \`DummyFileSystem\` 位于 impl 模块（会被 Plugin Verifier 判为使用非公开 API），
 * 因此这里自己实现最小 VFS：只提供一个标识，不承载任何真实内容。
 */
object BrowserFileSystem : VirtualFileSystem() {

    const val PROTOCOL: String = "rider-monkey-browser"

    override fun getProtocol(): String = PROTOCOL

    override fun findFileByPath(path: String): VirtualFile? = null

    override fun refresh(asynchronous: Boolean) = Unit

    override fun refreshAndFindFileByPath(path: String): VirtualFile? = null

    override fun addVirtualFileListener(listener: VirtualFileListener) = Unit

    override fun removeVirtualFileListener(listener: VirtualFileListener) = Unit

    override fun isReadOnly(): Boolean = true

    // 下面这些是 VirtualFileSystem 的文件操作入口。本 VFS 只是「一个标签页的标识」，
    // 不承载任何真实内容，因此一律拒绝；错误信息写清原因，便于日后定位。
    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("浏览器标签页的虚拟文件系统不支持文件操作")

    override fun deleteFile(requestor: Any?, file: VirtualFile) = unsupported()

    override fun moveFile(requestor: Any?, file: VirtualFile, newParent: VirtualFile) = unsupported()

    override fun renameFile(requestor: Any?, file: VirtualFile, newName: String) = unsupported()

    override fun createChildFile(requestor: Any?, parent: VirtualFile, name: String): VirtualFile =
        unsupported()

    override fun createChildDirectory(requestor: Any?, parent: VirtualFile, name: String): VirtualFile =
        unsupported()

    override fun copyFile(
        requestor: Any?,
        file: VirtualFile,
        newParent: VirtualFile,
        copyName: String
    ): VirtualFile = unsupported()
}