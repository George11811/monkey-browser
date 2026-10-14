package com.ridermonkey.browser

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 代表「一个浏览器标签页」的虚拟文件，用于把该标签页放进中央编辑区。
 *
 * 每个标签页一个实例、且由 BrowserContainer 长期持有：VirtualFile 按实例身份比较，
 * 复用同一实例才能让 FileEditorManager 复用同一个编辑标签，而不是每点一次开一个新标签。
 *
 * 不承载内容：读写一律拒绝或返回空，编辑区里的内容由 BrowserFileEditor 提供。
 */
class BrowserVirtualFile(private val tabNumber: Int) : VirtualFile() {

    override fun getName(): String = "Monkey Browser " + tabNumber

    override fun getFileSystem(): VirtualFileSystem = BrowserFileSystem

    override fun getPath(): String = "/" + getName()

    override fun isWritable(): Boolean = false

    override fun isDirectory(): Boolean = false

    override fun isValid(): Boolean = true

    override fun getParent(): VirtualFile? = null

    override fun getChildren(): Array<VirtualFile> = emptyArray()

    override fun getOutputStream(
        requestor: Any?,
        newModificationStamp: Long,
        newTimeStamp: Long
    ): OutputStream = throw UnsupportedOperationException("浏览器标签页不支持写入")

    override fun contentsToByteArray(): ByteArray = ByteArray(0)

    override fun getTimeStamp(): Long = 0

    override fun getLength(): Long = 0

    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) = Unit

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
}
