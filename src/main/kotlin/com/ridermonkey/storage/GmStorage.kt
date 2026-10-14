package com.ridermonkey.storage

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path

/**
 * 用户脚本的 GM 存储（`GM_setValue` 系列）。
 *
 * 布局：`<root>/<scriptId>/<URL 编码后的 key>`，文件内容是该键值的**原始 JSON 片段**。
 *
 * 为什么一键一文件、而不是一个 JSON 文件：
 * - 避免自己写 JSON 解析器（值可以是任意 JSON，含嵌套对象）；
 * - 单键写入是原子替换，不会因为一次写坏而丢掉整个脚本的存储。
 *
 * key 用 URL 编码落到文件名，因此任意 UTF-8 键都能安全存取；scriptId 来自脚本文件名，
 * 这里再做一次白名单过滤，杜绝路径穿越。
 */
class GmStorage(private val root: Path) {

    /** 读取某脚本的全部存储：key -> 原始 JSON 值。 */
    fun snapshot(scriptId: String): Map<String, String> {
        val dir = scriptDir(scriptId)
        if (!Files.isDirectory(dir)) return emptyMap()

        val out = LinkedHashMap<String, String>()
        Files.newDirectoryStream(dir).use { stream ->
            for (file in stream) {
                if (!Files.isRegularFile(file)) continue
                val key = runCatching {
                    URLDecoder.decode(file.fileName.toString(), "UTF-8")
                }.getOrNull() ?: continue
                val raw = runCatching { Files.readString(file) }.getOrNull() ?: continue
                out[key] = raw
            }
        }
        return out
    }

    /** 写入一个键。rawJson 必须是合法 JSON 片段。 */
    fun put(scriptId: String, key: String, rawJson: String) {
        val dir = scriptDir(scriptId)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(encodeKey(key)), rawJson)
    }

    fun remove(scriptId: String, key: String) {
        runCatching { Files.deleteIfExists(scriptDir(scriptId).resolve(encodeKey(key))) }
    }

    fun keys(scriptId: String): List<String> = snapshot(scriptId).keys.toList()

    private fun scriptDir(scriptId: String): Path = root.resolve(safeId(scriptId))

    private fun encodeKey(key: String): String = URLEncoder.encode(key, "UTF-8")

    /** 只保留文件系统安全字符，防止 scriptId 里混入路径分隔符。 */
    private fun safeId(scriptId: String): String =
        scriptId.replace(Regex("[^A-Za-z0-9._-]"), "_")
}