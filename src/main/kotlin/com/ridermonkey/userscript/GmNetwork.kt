package com.ridermonkey.userscript

import com.intellij.openapi.diagnostic.Logger
import com.ridermonkey.util.JsonStrings
import org.cef.browser.CefFrame
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.Charset
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * GM 网络类 API 的后端（`GM_xmlhttpRequest` / `GM_download`）。
 *
 * 通道设计（复用已有两条路，不引入新 API）：
 * - **请求**：JS 走 console 通道发出（`op=xhr`），因此天然带上 ADR-0007 的来源指纹校验；
 * - **响应**：Kotlin 用 `CefFrame.executeJavaScript` 调回页面里的 `window.__rmDeliver(...)`。
 *
 * 跨域授权：只允许脚本通过 `@connect` 显式声明过的主机（[ConnectRules]，最小权限）。
 *
 * 本版补齐（ADR-0012）：
 * - `responseType`：`text`/`json` 走文本；`arraybuffer`/`blob` 以 **base64** 投递
 *   （`responseBase64`），JS 侧解成 ArrayBuffer / Blob。`document`/`stream` 明确拒绝。
 * - `onprogress`：读取响应流时按 [PROGRESS_STEP_BYTES] 节流投递 `progress` 消息，
 *   避免把 console 通道刷爆；`lengthComputable` 由是否存在 `content-length` 决定。
 * - `abort()`：置取消标志并中断 HTTP 请求，读循环随即退出且**不再投递任何回调**。
 *
 * 已知限制（ADR-0009 记录）：投递函数必须挂在 `window` 上，因此页面可以覆盖它
 * （导致响应投递失败）或伪造响应；但**无法伪造跨域请求本身**——请求方向仍受
 * 来源指纹 + 来源校验的约束。
 */
class GmNetwork(
    private val scripts: () -> List<UserscriptEntry>,
    private val frameLookup: (String) -> CefFrame?,
    private val documentUrlOf: (String) -> String?
) {

    /** 一次在途请求的取消状态。 */
    private class InFlight {
        @Volatile
        var cancelled: Boolean = false

        @Volatile
        var future: CompletableFuture<*>? = null
    }

    private val logger = Logger.getInstance("RiderMonkeyBrowser")

    /** key = frameId|requestId。abort 只认这个键，避免跨 frame 误伤。 */
    private val inFlight = ConcurrentHashMap<String, InFlight>()

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    fun handle(scriptId: String, frameId: String, fields: Map<String, String>) {
        val requestId = fields["id"] ?: return
        val url = fields["url"] ?: return
        val method = (fields["method"] ?: "GET").uppercase()

        val script = scripts().firstOrNull { it.id == scriptId }
        if (script == null) {
            deliverError(frameId, requestId, "脚本已不在脚本库中", timeout = false)
            return
        }
        if (!ConnectRules.allows(script.metadata, url, documentUrlOf(frameId))) {
            val host = ConnectRules.hostOf(url) ?: url
            logger.info("[RM-XHR] 拒绝未授权请求：" + url + "（脚本 " + scriptId + " 未声明 @connect " + host + "）")
            deliverError(frameId, requestId, "未授权的主机，请在脚本里声明 @connect " + host, timeout = false)
            return
        }

        val uri = runCatching { URI.create(url) }.getOrNull()
        if (uri == null) {
            deliverError(frameId, requestId, "非法的 URL：" + url, timeout = false)
            return
        }

        // 这两个类型无法在没有 document/stream 原语的情况下如实实现，明确拒绝好过静默降级为文本。
        val responseType = (fields["responseType"] ?: "").lowercase()
        if (responseType == "document" || responseType == "stream") {
            deliverError(frameId, requestId, "不支持的 responseType：" + responseType, timeout = false)
            return
        }

        val timeoutMs = fields["timeout"]?.toLongOrNull()?.coerceIn(1000L, 120_000L) ?: 30_000L
        val data = fields["data"]
        val publisher = if (data == null) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(data)
        }

        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMs))
        try {
            builder.method(method, publisher)
        } catch (t: Throwable) {
            deliverError(frameId, requestId, "不支持的请求方法：" + method, timeout = false)
            return
        }
        for ((name, value) in fields) {
            if (!name.startsWith(HEADER_PREFIX)) continue
            runCatching { builder.header(name.removePrefix(HEADER_PREFIX), value) }
        }

        val started = System.currentTimeMillis()
        val key = requestKey(frameId, requestId)
        val state = InFlight()
        inFlight[key] = state

        // ofInputStream（而不是 ofString）：只有拿到流才能做进度投递与中途取消。
        val future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        state.future = future

        future.whenComplete { response, error ->
            inFlight.remove(key)
            if (state.cancelled) {
                // 脚本已 abort：既不发 onload 也不发 onerror（与 XHR 语义一致）。
                logger.debug("[RM-XHR] 已取消：" + method + " " + url)
                return@whenComplete
            }
            val elapsed = System.currentTimeMillis() - started
            if (error != null) {
                val timeout = error is HttpTimeoutException || error.cause is HttpTimeoutException
                logger.info("[RM-XHR] " + method + " " + url + " 失败：" + error.message)
                deliverError(frameId, requestId, error.message ?: "请求失败", timeout)
                return@whenComplete
            }
            logger.info(
                "[RM-XHR] " + method + " " + url + " -> " + response.statusCode() + "（" + elapsed + "ms）"
            )
            readAndDeliver(frameId, requestId, response, url, responseType, state)
        }
    }

    /** `abort()`：中断在途请求。已完成或找不到时静默忽略。 */
    fun abort(frameId: String, requestId: String) {
        val state = inFlight.remove(requestKey(frameId, requestId)) ?: return
        state.cancelled = true
        runCatching { state.future?.cancel(true) }
        logger.debug("[RM-XHR] 收到 abort：requestId=" + requestId)
    }

    /**
     * `GM_download` 的后端。
     *
     * 与 xhr 共用三样东西：@connect 授权、取消机制（因此 `abort()` 也能用）、以及
     * `window.__rmDeliver` 投递通道。差别只在响应处理：**边读边落盘**，不把整包读进内存。
     *
     * 保存位置：系统下载目录（`~/Downloads`，不存在则尝试创建）。文件名只取 basename 并过滤
     * 非法字符与路径分隔符——脚本给的名字**不可信**，绝不能让它写到别处去。
     */
    fun handleDownload(scriptId: String, frameId: String, fields: Map<String, String>) {
        val requestId = fields["id"] ?: return
        val url = fields["url"] ?: return

        val script = scripts().firstOrNull { it.id == scriptId }
        if (script == null) {
            deliverError(frameId, requestId, "脚本已不在脚本库中", timeout = false)
            return
        }
        if (!ConnectRules.allows(script.metadata, url, documentUrlOf(frameId))) {
            val host = ConnectRules.hostOf(url) ?: url
            logger.info("[RM-DL] 拒绝未授权下载：" + url + "（脚本 " + scriptId + " 未声明 @connect " + host + "）")
            deliverError(frameId, requestId, "未授权的主机，请在脚本里声明 @connect " + host, timeout = false)
            return
        }
        val uri = runCatching { URI.create(url) }.getOrNull()
        if (uri == null) {
            deliverError(frameId, requestId, "非法的 URL：" + url, timeout = false)
            return
        }

        val dir = downloadsDir()
        if (dir == null) {
            deliverError(frameId, requestId, "找不到可写入的下载目录", timeout = false)
            return
        }
        val target = uniqueTarget(dir, safeFileName(fields["name"], uri))

        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET()
        for ((name, value) in fields) {
            if (!name.startsWith(HEADER_PREFIX)) continue
            runCatching { builder.header(name.removePrefix(HEADER_PREFIX), value) }
        }

        val started = System.currentTimeMillis()
        val key = requestKey(frameId, requestId)
        val state = InFlight()
        inFlight[key] = state

        val future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        state.future = future

        future.whenComplete { response, error ->
            inFlight.remove(key)
            if (state.cancelled) {
                runCatching { target.delete() }
                logger.debug("[RM-DL] 已取消：" + url)
                return@whenComplete
            }
            if (error != null) {
                logger.info("[RM-DL] " + url + " 失败：" + error.message)
                deliverError(frameId, requestId, error.message ?: "下载失败", timeout = false)
                return@whenComplete
            }
            if (response.statusCode() >= 400) {
                logger.info("[RM-DL] " + url + " -> HTTP " + response.statusCode())
                deliverError(frameId, requestId, "HTTP " + response.statusCode(), timeout = false)
                return@whenComplete
            }

            val total = response.headers().firstValueAsLong("content-length").orElse(-1L)
            var written = 0L
            var reported = 0L
            try {
                val stream = response.body()
                stream.use { input ->
                    FileOutputStream(target).use { output ->
                        val chunk = ByteArray(CHUNK_BYTES)
                        while (true) {
                            if (state.cancelled) return@whenComplete
                            val read = runCatching { input.read(chunk) }.getOrDefault(-1)
                            if (read < 0) break
                            output.write(chunk, 0, read)
                            written += read
                            if (written - reported >= PROGRESS_STEP_BYTES) {
                                reported = written
                                deliverProgress(frameId, requestId, written, total)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                // 写盘失败时把半截文件删掉，避免留下一个看起来完整的坏文件。
                runCatching { target.delete() }
                logger.info("[RM-DL] 写入失败：" + t.message)
                deliverError(frameId, requestId, t.message ?: "写入文件失败", timeout = false)
                return@whenComplete
            }
            if (state.cancelled) {
                runCatching { target.delete() }
                return@whenComplete
            }
            if (written > reported) deliverProgress(frameId, requestId, written, total)
            logger.info(
                "[RM-DL] 已保存 " + target.absolutePath + "（" + written + " 字节，" +
                    (System.currentTimeMillis() - started) + "ms）"
            )
            deliver(
                frameId,
                "{" +
                    JsonStrings.quoted("id") + ":" + JsonStrings.quoted(requestId) + "," +
                    JsonStrings.quoted("status") + ":" + response.statusCode() + "," +
                    JsonStrings.quoted("path") + ":" + JsonStrings.quoted(target.absolutePath) + "," +
                    JsonStrings.quoted("name") + ":" + JsonStrings.quoted(target.name) + "," +
                    JsonStrings.quoted("finalUrl") + ":" + JsonStrings.quoted(url) +
                    "}"
            )
        }
    }

    /** 供其它 GM 操作复用同一条投递通道（例如 GM_openInTab 的回执）。 */
    fun deliverToFrame(frameId: String, json: String) = deliver(frameId, json)

    /** 系统下载目录；不存在时尝试创建，失败返回 null（由调用方给出可读错误）。 */
    private fun downloadsDir(): File? {
        val home = System.getProperty("user.home") ?: return null
        val candidate = File(home, "Downloads")
        if (candidate.isDirectory) return candidate
        return if (runCatching { candidate.mkdirs() }.getOrDefault(false) && candidate.isDirectory) candidate else null
    }

    /**
     * 把脚本给的文件名清洗成**纯文件名**。
     *
     * 安全要点：先按两种分隔符取 basename（挡掉 `../../evil`），再过滤控制字符与
     * Windows 非法字符，最后兜底一个默认名。脚本提供的内容不可信。
     */
    private fun safeFileName(raw: String?, uri: URI): String {
        var name = raw?.trim().orEmpty()
        if (name.isEmpty()) {
            name = uri.path?.substringAfterLast('/').orEmpty()
        }
        name = name.substringAfterLast('/').substringAfterLast('\\')
        name = name.replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_").trim()
        if (name.isEmpty() || name == "." || name == "..") name = "download"
        return if (name.length > 150) name.take(150) else name
    }

    /** 同名时追加 ` (1)`、` (2)`…，不覆盖用户已有文件。 */
    private fun uniqueTarget(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists() && i <= 9999) {
            candidate = File(dir, base + " (" + i + ")" + ext)
            i++
        }
        return candidate
    }

    private fun requestKey(frameId: String, requestId: String): String = frameId + "|" + requestId

    private fun readAndDeliver(
        frameId: String,
        requestId: String,
        response: HttpResponse<InputStream>,
        finalUrl: String,
        responseType: String,
        state: InFlight
    ) {
        val total = response.headers().firstValueAsLong("content-length").orElse(-1L)
        val sink = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        var loaded = 0L
        var reported = 0L

        val stream = response.body() ?: return
        stream.use { input ->
            while (true) {
                if (state.cancelled) return
                // 读失败（含 abort 打断）当作流结束，交由下面的投递收尾；取消与否由 state 决定。
                val read = runCatching { input.read(chunk) }.getOrDefault(-1)
                if (read < 0) break
                sink.write(chunk, 0, read)
                loaded += read
                if (loaded - reported >= PROGRESS_STEP_BYTES) {
                    reported = loaded
                    deliverProgress(frameId, requestId, loaded, total)
                }
            }
        }
        if (state.cancelled) return
        // 收尾补一次，让已知总长的请求能走到 100%。
        if (loaded > reported) deliverProgress(frameId, requestId, loaded, total)
        deliver(frameId, successJson(requestId, response, sink.toByteArray(), finalUrl, responseType))
    }

    private fun successJson(
        requestId: String,
        response: HttpResponse<InputStream>,
        body: ByteArray,
        finalUrl: String,
        responseType: String
    ): String {
        val headerText = response.headers().map().entries.joinToString("") { (name, values) ->
            values.joinToString("") { value -> name + ": " + value + System.lineSeparator() }
        }
        val bodyField = if (responseType == "arraybuffer" || responseType == "blob") {
            JsonStrings.quoted("responseBase64") + ":" +
                JsonStrings.quoted(Base64.getEncoder().encodeToString(body)) + ","
        } else {
            JsonStrings.quoted("responseText") + ":" +
                JsonStrings.quoted(String(body, charsetOf(response))) + ","
        }
        return "{" +
            JsonStrings.quoted("id") + ":" + JsonStrings.quoted(requestId) + "," +
            JsonStrings.quoted("status") + ":" + response.statusCode() + "," +
            bodyField +
            JsonStrings.quoted("responseHeaders") + ":" + JsonStrings.quoted(headerText) + "," +
            JsonStrings.quoted("finalUrl") + ":" + JsonStrings.quoted(finalUrl) +
            "}"
    }

    /** 从 Content-Type 取字符集；缺失或非法时回落到 UTF-8（与浏览器 XHR 的宽松行为一致）。 */
    private fun charsetOf(response: HttpResponse<*>): Charset {
        val contentType = response.headers().firstValue("content-type").orElse("")
        val marker = "charset="
        val at = contentType.lowercase().indexOf(marker)
        if (at < 0) return Charsets.UTF_8
        val raw = contentType.substring(at + marker.length)
            .substringBefore(';')
            .trim()
            .trim('"')
        if (raw.isEmpty()) return Charsets.UTF_8
        return runCatching { Charset.forName(raw) }.getOrDefault(Charsets.UTF_8)
    }

    private fun deliverProgress(frameId: String, requestId: String, loaded: Long, total: Long) {
        deliver(
            frameId,
            "{" +
                JsonStrings.quoted("id") + ":" + JsonStrings.quoted(requestId) + "," +
                JsonStrings.quoted("progress") + ":true," +
                JsonStrings.quoted("loaded") + ":" + loaded + "," +
                JsonStrings.quoted("total") + ":" + total +
                "}"
        )
    }

    private fun deliverError(frameId: String, requestId: String, message: String, timeout: Boolean) {
        deliver(
            frameId,
            "{" +
                JsonStrings.quoted("id") + ":" + JsonStrings.quoted(requestId) + "," +
                JsonStrings.quoted("error") + ":" + JsonStrings.quoted(message) + "," +
                JsonStrings.quoted("timeout") + ":" + timeout +
                "}"
        )
    }

    private fun deliver(frameId: String, json: String) {
        val frame = frameLookup(frameId)
        if (frame == null) {
            logger.info("[RM-XHR] 无法投递响应：frame 已不存在")
            return
        }
        runCatching {
            frame.executeJavaScript(DELIVER_FUNCTION + "(" + JsonStrings.quoted(json) + ");", DELIVER_URL, 0)
        }.onFailure { logger.info("[RM-XHR] 投递响应失败：" + it.message) }
    }

    companion object {
        /** JS 侧注册的响应投递入口（挂在 window 上，见类 KDoc 的限制说明）。 */
        const val DELIVER_FUNCTION: String = "window.__rmDeliver"

        private const val DELIVER_URL = "https://rider-monkey.invalid/deliver"

        /** 请求头在协议里的前缀：`header.<name>=<value>`。 */
        const val HEADER_PREFIX: String = "header."

        /** 每次投递进度之间的最小新增字节数：太小会把 console 通道刷爆。 */
        const val PROGRESS_STEP_BYTES: Long = 64 * 1024

        /** 单次读取的缓冲区大小。 */
        private const val CHUNK_BYTES: Int = 16 * 1024
    }
}
