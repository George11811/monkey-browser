package com.ridermonkey.ui

import com.ridermonkey.browser.ImageContextAction
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration

/**
 * 「在系统看图软件打开」的落地：取字节 → 落临时文件 → 交给系统默认程序。
 *
 * 为什么不用 CEF 自己的下载：JCEF 的 `CefBrowser.startDownload` 走的是
 * `CefBrowserHost::StartDownload`，按 CEF 的约定这类下载**不会**回调 `onBeforeDownload`，
 * 我们也就没有机会指定落盘路径（只能落进 CEF 的默认下载目录或弹系统保存框）。
 * CEF 的下载链路还依赖下载管理器与「用户点击」语义，在 OSR 内核里行为更难保证。
 *
 * 因此这里自己发一次 HTTP GET：带上 `Referer`（绕开常见热链校验）与图片 `Accept`。
 * 代价见 ADR-0018 的已知限制：**只在浏览器 cookie 里的登录态图片取不回来**，
 * 这种情况会明确报错，而不是静默存下一张错误页。
 *
 * 线程约定：本类是阻塞 IO，**只能在后台线程调用**（面板用 `executeOnPooledThread`）。
 */
object SystemImageViewer {

    /**
     * 单张图片上限：防止把一个「其实是视频」的地址整个读进内存。
     *
     * 取 32 MB：常见图片最大的也就几 MB，超过这个量级基本是「地址根本不是图片」。
     */
    private const val MAX_BYTES: Long = 32L * 1024 * 1024

    /**
     * 固定 UA。
     *
     * 不传 UA 时 JDK 会发 `Java-http-client/...`，部分图床/CDN 会直接拒绝；
     * JCEF 的内核版本号在运行时可查但不能写进常量，这里用一个浏览器形态的通用串。
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    /**
     * 与 GM 通道同一种客户端配置：跟随重定向、15 秒连接超时。
     *
     * 不显式设置 proxy：JDK 的 HttpClient 在未指定时取 ProxySelector.getDefault()，
     * 也就是 IDE / 系统代理设置（已实测：把代理端口指向关闭的端口即 ConnectException）。
     */
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    /**
     * 下载 [url] 到系统临时文件并返回该文件。
     *
     * @param referer 页面地址，用作 Referer 请求头；非 http(s) 时忽略
     * @throws IOException 网络失败、非 2xx、超大、或无法识别为图片
     */
    fun saveToTempFile(url: String, referer: String?): File {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            // 页面地址里可能带未百分号编码的空格/花括号：别把英文异常甩给用户。
            throw IOException("图片地址不合法", e)
        }
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .header("User-Agent", USER_AGENT)
            .GET()
        // 与 ImageContextAction.pickSourceUrl 用同一套判据："httpx://" 之类不能混进来。
        val refererValue = referer?.trim().orEmpty()
        if (refererValue.startsWith("http://", ignoreCase = true) ||
            refererValue.startsWith("https://", ignoreCase = true)
        ) {
            builder.header("Referer", refererValue)
        }

        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: InterruptedException) {
            // 线程被中断（IDE 关闭 / 任务取消）：恢复中断标志，交给上层按失败处理。
            Thread.currentThread().interrupt()
            throw IOException("下载被中断", e)
        }
        response.body().use { stream ->
            if (response.statusCode() !in 200..299) {
                throw IOException("HTTP " + response.statusCode())
            }
            val declared = response.headers().firstValueAsLong("content-length").orElse(-1L)
            if (declared > MAX_BYTES) {
                throw IOException("图片超过 32 MB，已放弃")
            }
            val bytes = stream.readNBytes((MAX_BYTES + 1).toInt())
            if (bytes.size > MAX_BYTES) {
                throw IOException("图片超过 32 MB，已放弃")
            }
            if (bytes.isEmpty()) {
                throw IOException("图片内容为空")
            }
            val contentType = response.headers().firstValue("content-type").orElse(null)
            val extension = ImageContextAction.extensionFor(contentType, url, bytes)
                ?: throw IOException("无法识别图片格式（Content-Type: " + (contentType ?: "无") + "）")
            val file = Files.createTempFile(ImageContextAction.filePrefixFor(url), "." + extension).toFile()
            file.writeBytes(bytes)
            return file
        }
    }

    /**
     * 用系统默认关联程序打开文件（图片就是「看图软件」）。
     *
     * @throws IOException 系统不支持、没有关联程序、或启动失败
     */
    fun openExternally(file: File) {
        if (!Desktop.isDesktopSupported()) {
            throw IOException("当前系统不支持用默认程序打开文件")
        }
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.OPEN)) {
            throw IOException("当前系统不支持用默认程序打开文件")
        }
        desktop.open(file)
    }
}
