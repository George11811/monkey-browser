package com.ridermonkey.storage

import com.ridermonkey.userscript.UserscriptEntry
import com.ridermonkey.userscript.UserscriptParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * 以「一个文件夹」作为脚本库的存储实现。
 *
 * - 脚本：<scriptsDir> 下所有以 .user.js 结尾的文件（丢弃即导入）。
 * - 启用状态：`<stateFile>` 里每行一个**被禁用**的脚本文件名；文件不存在表示全部启用。
 *
 * 之所以用「禁用清单」而不是「启用清单」：新丢进来的脚本默认就是启用，符合直觉。
 *
 * 该实现刻意不依赖任何 IDE API，便于用临时目录做单元测试；
 * 默认路径由 [ScriptStoreFactory] 从平台配置目录推导。
 */
class ScriptFileStore(
    private val scriptsDir: Path,
    private val stateFile: Path,
    /** 全局暂停标记文件；存在即表示「暂停全部脚本」。 */
    private val pausedFile: Path = stateFile.resolveSibling("paused.txt")
) {

    fun scriptsDir(): Path = scriptsDir

    /** 读取脚本库里所有能解析出元数据块的脚本，按文件名排序。 */
    fun load(): List<UserscriptEntry> {
        if (!Files.isDirectory(scriptsDir)) return emptyList()
        val disabled = readDisabled()
        val result = mutableListOf<UserscriptEntry>()

        Files.newDirectoryStream(scriptsDir, "*.user.js").use { stream ->
            for (path in stream) {
                val source = runCatching { Files.readString(path) }.getOrNull() ?: continue
                val parsed = UserscriptParser.parse(source) ?: continue
                val id = path.fileName.toString()
                result += UserscriptEntry(
                    id = id,
                    displayName = parsed.metadata.name ?: id,
                    enabled = id !in disabled,
                    source = source,
                    metadata = parsed.metadata,
                    body = parsed.body,
                    filePath = path.toString()
                )
            }
        }
        return result.sortedBy { it.id }
    }

    /** 记录/取消某个脚本的禁用状态。 */
    fun setEnabled(id: String, enabled: Boolean) {
        val disabled = readDisabled().toMutableSet()
        if (enabled) disabled.remove(id) else disabled.add(id)
        writeDisabled(disabled)
    }

    fun isEnabled(id: String): Boolean = id !in readDisabled()

    /**
     * 带缓存的加载：注入路径每次导航都会问一次脚本列表，若每次都从磁盘重读 + 重解析，
     * 脚本库有几 MB 时开销可观（每次注入都发生，且每个 frame 一次）。
     *
     * 用短 TTL 缓存：既覆盖「一次导航内多次询问」的突发，又能在用户刚编辑过脚本后很快生效。
     * 改动脚本后想立刻生效可以点管理界面的「刷新」（[invalidate]）。
     */
    @Synchronized
    fun loadCached(): List<UserscriptEntry> {
        val now = System.currentTimeMillis()
        val cached = cachedEntries
        val cachedAt = cachedAtMillis
        if (cached != null && now - cachedAt < CACHE_TTL_MILLIS) return cached
        val loaded = load()
        cachedEntries = loaded
        cachedAtMillis = now
        return loaded
    }

    /** 让下一次 [loadCached] 重新读盘。 */
    @Synchronized
    fun invalidate() {
        cachedEntries = null
        cachedAtMillis = 0
    }

    private var cachedEntries: List<UserscriptEntry>? = null
    private var cachedAtMillis: Long = 0

    /**
     * 全局暂停：存在标记文件即为暂停。
     *
     * 逃生出口——当某个脚本把页面改坏（例如在 Cloudflare 人机验证页上盖住了验证控件）时，
     * 用户需要一个不用去删文件就能立刻让页面恢复正常的开关。
     */
    fun isPaused(): Boolean = Files.isRegularFile(pausedFile)

    fun setPaused(paused: Boolean) {
        runCatching {
            if (paused) {
                Files.createDirectories(pausedFile.parent)
                Files.writeString(pausedFile, "paused" + System.lineSeparator())
            } else {
                Files.deleteIfExists(pausedFile)
            }
        }
    }

    private fun readDisabled(): Set<String> {
        if (!Files.isRegularFile(stateFile)) return emptySet()
        return runCatching {
            Files.readAllLines(stateFile)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun writeDisabled(disabled: Set<String>) {
        runCatching {
            Files.createDirectories(stateFile.parent)
            val content = StringBuilder()
            content.appendLine("# 每行一个被禁用的脚本文件名；本文件由 Monkey Browser 维护。")
            disabled.sorted().forEach { content.appendLine(it) }
            Files.writeString(stateFile, content.toString())
        }
    }

    private companion object {
        /** 缓存有效期：够覆盖一次导航里的多次询问，又不至于让脚本改动长时间不生效。 */
        const val CACHE_TTL_MILLIS = 2000L
    }
}