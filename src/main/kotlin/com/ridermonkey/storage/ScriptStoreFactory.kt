package com.ridermonkey.storage

import com.intellij.openapi.application.PathManager
import java.nio.file.Path

/**
 * 从平台配置目录推导出默认脚本库位置。
 *
 * 实测 PathManager.getConfigDir() 返回 java.nio.file.Path（262 平台），
 * 在 Windows 上即 %APPDATA%\JetBrains\Rider2026.2。
 */
object ScriptStoreFactory {

    const val DIR_NAME: String = "rider-monkey"

    fun baseDir(): Path = PathManager.getConfigDir().resolve(DIR_NAME)

    fun scriptsDir(): Path = baseDir().resolve("scripts")

    fun stateFile(): Path = baseDir().resolve("disabled.txt")

    /** GM 存储根目录：<base>/storage/<scriptId>/<key>。 */
    fun storageDir(): Path = baseDir().resolve("storage")

    /** @resource 的缓存根目录：<base>/resources/<scriptId>/<name>。 */
    fun resourcesDir(): Path = baseDir().resolve("resources")

    /** 全局暂停标记文件。 */
    fun pausedFile(): Path = baseDir().resolve("paused.txt")

    /** 书签文件：纯文本，一行一条 `标题<TAB>URL`。用户可以自己编辑/备份。 */
    fun bookmarksFile(): Path = baseDir().resolve("bookmarks.txt")

    fun defaultStore(): ScriptFileStore = ScriptFileStore(scriptsDir(), stateFile(), pausedFile())
}