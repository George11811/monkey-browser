package com.ridermonkey.storage

import java.nio.file.Files
import java.nio.file.Path

/**
 * 书签存储。
 *
 * 格式：纯文本，一行一条 `标题<TAB>URL`。
 *
 * 为什么不用 JSON/逗号分隔：URL 里**可以含逗号**（查询串、路径都可能），用逗号会把一条拆成两条；
 * 制表符出现在标题里的概率极低，且写入前会被替换成空格。纯文本的额外好处是用户能直接编辑、
 * 也方便自己备份。
 *
 * 与 [ScriptFileStore] 一样**刻意不依赖任何 IDE API**，便于用临时目录做单元测试；
 * 默认路径由 [ScriptStoreFactory] 从平台配置目录推导。
 */
class BookmarkStore(private val file: Path) {

    /** 一条书签。列表顺序即展示顺序，**最近添加的排在最前**。 */
    data class Bookmark(val title: String, val url: String)

    /** 读取全部书签；文件不存在、不可读或某行损坏时跳过该行，不抛异常。 */
    fun load(): List<Bookmark> {
        if (!Files.isRegularFile(file)) return emptyList()
        return runCatching {
            Files.readAllLines(file).mapNotNull { parseLine(it) }
        }.getOrDefault(emptyList())
    }

    fun save(items: List<Bookmark>) {
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            Files.write(file, items.map { encode(it) })
        }
    }

    /**
     * 收藏：按 **URL 去重**（同一个页面只留一条），并把该条移到最前。
     *
     * 纯函数，不改磁盘 —— 调用方决定何时 [save]。
     */
    fun withAdded(items: List<Bookmark>, title: String, url: String): List<Bookmark> {
        val target = url.trim()
        if (target.isEmpty()) return items
        return listOf(Bookmark(normalizeTitle(title, target), target)) + without(items, target)
    }

    /**
     * 重命名：**保持原有顺序**。
     *
     * 故意不把条目顶到最前 —— 重命名是「改个名字」，不是「重新收藏」；
     * 如果它同时改变排序，用户会以为书签丢了。名称留空时回落到 URL（与 [withAdded] 一致）。
     */
    fun withRenamed(items: List<Bookmark>, url: String, title: String): List<Bookmark> {
        val target = url.trim()
        val name = normalizeTitle(title, target)
        return items.map { if (it.url == target) it.copy(title = name) else it }
    }

    fun without(items: List<Bookmark>, url: String): List<Bookmark> {
        val target = url.trim()
        return items.filter { it.url != target }
    }

    fun contains(items: List<Bookmark>, url: String): Boolean = items.any { it.url == url.trim() }

    private fun normalizeTitle(title: String, url: String): String {
        val text = title.replace(SEPARATOR, ' ').trim()
        val effective = text.ifEmpty { url }
        return if (effective.length > MAX_TITLE_LENGTH) effective.take(MAX_TITLE_LENGTH) else effective
    }

    companion object {
        internal const val SEPARATOR: Char = '\t'
        private const val MAX_TITLE_LENGTH = 200

        internal fun encode(bookmark: Bookmark): String =
            bookmark.title.replace(SEPARATOR, ' ') + SEPARATOR + bookmark.url

        /** 解析一行；缺 URL 或没有分隔符时返回 null（视作损坏行，跳过）。 */
        internal fun parseLine(line: String): Bookmark? {
            val text = line.trim()
            if (text.isEmpty()) return null
            val at = text.indexOf(SEPARATOR)
            if (at < 0) return null
            val url = text.substring(at + 1).trim()
            if (url.isEmpty()) return null
            val title = text.substring(0, at).trim()
            return Bookmark(title.ifEmpty { url }, url)
        }
    }
}
