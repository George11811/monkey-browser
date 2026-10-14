package com.ridermonkey.storage

import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * `@resource` 的本地缓存。
 *
 * Tampermonkey 的 `GM_getResourceText` 是**同步**的，因此资源必须在注入前就绪。
 * 但注入路径在 CEF 回调线程上，不能在那里做网络请求（会卡住导航）。做法：
 *
 * 1. 注入时只读**本地缓存**，有就嵌入，没有就先不嵌入（`GM_getResourceText` 返回 null，与 TM 对缺失资源的行为一致）；
 * 2. 缺失的资源在**后台**抓取并落盘，下一次注入即可用。
 *
 * 代价：脚本首次运行的页面上拿不到资源，刷新一次就好。这是为了不牺牲导航延迟，
 * 已在 ADR-0010 记录。
 */
class ResourceCache(private val root: Path) {

    private val logger = Logger.getInstance("RiderMonkeyBrowser")
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** 已缓存的文本资源；缺失的会安排后台抓取。 */
    fun cachedTexts(scriptId: String, resources: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, url) in resources) {
            val bytes = readCached(scriptId, name)
            if (bytes != null) {
                out[name] = bytes.toString(Charsets.UTF_8)
            } else {
                scheduleFetch(scriptId, name, url)
            }
        }
        return out
    }

    /** 已缓存资源转成 data URL（等价于 TM 的 `GM_getResourceURL`）。 */
    fun cachedDataUrls(scriptId: String, resources: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, url) in resources) {
            val bytes = readCached(scriptId, name) ?: continue
            out[name] = "data:" + mimeOf(url) + ";base64," +
                Base64.getEncoder().encodeToString(bytes)
        }
        return out
    }

    private fun readCached(scriptId: String, name: String): ByteArray? =
        runCatching {
            val file = scriptDir(scriptId).resolve(safeName(name))
            if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
        }.getOrNull()

    private fun scheduleFetch(scriptId: String, name: String, url: String) {
        val key = scriptId + "|" + name
        if (!inFlight.add(key)) return
        CompletableFuture.runAsync {
            try {
                val uri = URI.create(url)
                val client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                val request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() in 200..299) {
                    val dir = scriptDir(scriptId)
                    Files.createDirectories(dir)
                    Files.write(dir.resolve(safeName(name)), response.body())
                    logger.info("[RM-RES] 已缓存资源 " + name + "（" + response.body().size + " 字节）")
                } else {
                    logger.info("[RM-RES] 抓取资源失败（HTTP " + response.statusCode() + "）：" + url)
                }
            } catch (t: Throwable) {
                logger.info("[RM-RES] 抓取资源失败：" + url + " - " + t.message)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun scriptDir(scriptId: String): Path =
        root.resolve(scriptId.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun mimeOf(url: String): String {
        val lower = url.lowercase().substringBefore('?')
        return when {
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".js") -> "application/javascript"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".woff") -> "font/woff"
            else -> "text/plain"
        }
    }
}
