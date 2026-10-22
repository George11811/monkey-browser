package com.ridermonkey.browser

/**
 * 图片右键菜单「在系统看图软件打开」的纯逻辑（不依赖 IDE / JCEF，便于单测）。
 *
 * 为什么需要这一项：CEF 原生菜单对图片只给「在新标签页打开 / 复制图片地址 / 另存为」，
 * 而内嵌浏览器的图片预览受内核缩放限制，想看原始尺寸或放大细节时只能另存再自己找。
 *
 * 安全边界：只接受 http/https。
 * - `data:` / `blob:` 的字节用普通 HTTP 取不回来（取不回来就没有意义）；
 * - `file:` 属于「页面不该决定要不要交给系统程序」的一类（与 ADR-0017 D2 同一条边界）。
 * 三类都不进菜单，避免点下去必然失败。
 */
object ImageContextAction {

    /**
     * 自定义菜单命令号。
     *
     * 避开两个已知占用：CEF 内置项都小于 26500，IntelliJ 自己用 28500（Open DevTools）。
     */
    const val COMMAND_ID: Int = 27_000

    /** 菜单项文案。 */
    const val LABEL: String = "在系统看图软件打开"

    /**
     * CEF `CefContextMenuParams.MediaType` 里的图片枚举名。
     *
     * 用字符串而不是直接引用枚举：本类保持纯逻辑（同 [PopupRouting] 的做法）。
     */
    private const val MEDIA_TYPE_IMAGE = "CM_MEDIATYPE_IMAGE"

    private const val HTTP_PREFIX = "http://"
    private const val HTTPS_PREFIX = "https://"

    /** 临时文件名前缀上限：够看清来源，又不会撑爆路径。 */
    private const val MAX_PREFIX_CHARS = 48

    /** Files.createTempFile 要求前缀至少 3 个字符。 */
    private const val MIN_PREFIX_CHARS = 3

    /** 兜底前缀（URL 里拿不到可用片段时）。 */
    private const val FALLBACK_PREFIX = "image-"

    /** `Content-Type` → 扩展名。看图软件按关联表选程序，扩展名必须靠谱。 */
    private val MIME_EXTENSIONS = mapOf(
        "image/png" to "png",
        "image/apng" to "apng",
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/bmp" to "bmp",
        "image/x-ms-bmp" to "bmp",
        "image/svg+xml" to "svg",
        "image/avif" to "avif",
        "image/heic" to "heic",
        "image/x-icon" to "ico",
        "image/vnd.microsoft.icon" to "ico",
        "image/tiff" to "tiff"
    )

    /** URL 后缀里允许出现的图片类型。 */
    private val URL_EXTENSIONS = setOf(
        "png", "apng", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif", "heic", "ico", "tif", "tiff"
    )

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val BMP_MAGIC = byteArrayOf(0x42, 0x4D)
    private val ICO_MAGIC = byteArrayOf(0x00, 0x00, 0x01, 0x00)

    /**
     * 从右键参数里挑出可下载的图片地址；不是图片（或不是 http/https）就返回 null，菜单不加项。
     *
     * @param hasImageContents CEF 的 `hasImageContents()`：上下文落在图片元素上
     * @param mediaTypeName `CefContextMenuParams.MediaType` 的枚举名
     * @param rawSourceUrl `CefContextMenuParams.getSourceUrl()`：图片自身的地址
     */
    fun pickSourceUrl(hasImageContents: Boolean, mediaTypeName: String?, rawSourceUrl: String?): String? {
        if (!hasImageContents && mediaTypeName != MEDIA_TYPE_IMAGE) return null
        val url = rawSourceUrl?.trim().orEmpty()
        // 按「实际命中的前缀」取剩余部分：用统一长度会误判长度刚好等于 8 的 "http://x"。
        val rest = when {
            url.startsWith(HTTPS_PREFIX, ignoreCase = true) -> url.substring(HTTPS_PREFIX.length)
            url.startsWith(HTTP_PREFIX, ignoreCase = true) -> url.substring(HTTP_PREFIX.length)
            else -> return null
        }
        // scheme 之后必须还有内容，否则下载只会得到一句没有信息量的错。
        if (rest.isEmpty()) return null
        return url
    }

    /**
     * 判定图片格式并给出扩展名；判不出或明确不是图片时返回 null。
     *
     * 顺序是**二进制魔数 > 明确声明 > HTML 否定 > 文本嗅探 > URL 后缀**：
     * 服务端把图片按 `application/octet-stream` 发、或返回 HTML 错误页时，只有魔数说实话。
     *
     * @param head 响应体开头若干字节；传完整 body 也可以（只读前 256 字节做文本判断）
     */
    fun extensionFor(contentType: String?, url: String?, head: ByteArray?): String? {
        // 顺序很重要：二进制魔数 > 明确声明 > HTML 否定 > 文本嗅探 > URL 后缀。
        // 文本嗅探（SVG）不是魔数，绝不能压过站点给出的 Content-Type ——
        // 否则一页带内联 <svg> 图标的 HTML 错误页会被存成 .svg 交给系统程序。
        binaryExtension(head)?.let { return it }
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (mime.startsWith("image/")) {
            MIME_EXTENSIONS[mime]?.let { return it }
        } else if (mime.isNotEmpty() && !isOpaqueMime(mime)) {
            // 明确不是图片（多半是 403/404 的 HTML 错误页）：既不靠后缀猜，也不看文本嗅探。
            return null
        }
        // 走到这里只可能是「没有 Content-Type」或「未知类型」：此时才允许文本嗅探，
        // 但 HTML 无论声明如何都不写盘 —— 它不可能是图片。
        if (looksLikeHtml(head)) return null
        textExtension(head)?.let { return it }
        return urlExtension(url)
    }

    /** 前 256 字节看起来是 HTML（错误页/普通网页）：任何格式判定都不成立。 */
    private fun looksLikeHtml(head: ByteArray?): Boolean {
        if (head == null || head.isEmpty()) return false
        val text = String(head, 0, minOf(head.size, 256), Charsets.UTF_8).trimStart().lowercase()
        return text.startsWith("<!doctype") || text.startsWith("<html") ||
            text.startsWith("<head") || text.startsWith("<body") || text.startsWith("<!--")
    }

    /**
     * 临时文件名前缀：URL 最后一段的可见部分，去掉危险字符并限长。
     *
     * 两个硬约束：
     * - 必须 ≥ 3 个字符：`Files.createTempFile` 对短前缀直接抛异常；
     * - 末尾统一拼 `-`：Windows 的保留名（CON / NUL / COM1…）只按主名匹配，
     *   加个尾巴就不会命中；顺带避免以点号结尾、以及拼出的文件名撞上设备名。
     */
    fun filePrefixFor(url: String?): String {
        val path = url?.trim()?.substringBefore('#')?.substringBefore('?').orEmpty()
        val name = path.substringAfterLast('/')
        val base = if (name.contains('.')) name.substringBeforeLast('.') else name
        val cleaned = buildString {
            for (ch in base) {
                append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '_')
            }
        }.trim('.', '_', '-').take(MAX_PREFIX_CHARS)
        return if (cleaned.length >= MIN_PREFIX_CHARS) cleaned + "-" else FALLBACK_PREFIX
    }

    /** 二进制魔数：只认不会把文本误判成图片的格式。识别不出返回 null（不猜）。 */
    private fun binaryExtension(head: ByteArray?): String? {
        if (head == null || head.isEmpty()) return null
        if (head.startsWithBytes(PNG_MAGIC)) return "png"
        if (head.startsWithBytes(JPEG_MAGIC)) return "jpg"
        if (head.asciiAt(0, "GIF8")) return "gif"
        if (head.asciiAt(0, "RIFF") && head.asciiAt(8, "WEBP")) return "webp"
        if (head.startsWithBytes(BMP_MAGIC)) return "bmp"
        if (head.startsWithBytes(ICO_MAGIC)) return "ico"
        // HEIF 家族：第 4..7 字节是 "ftyp"，紧随其后是品牌号。
        if (head.asciiAt(4, "ftyp")) {
            val brand = if (head.size >= 12) String(head, 8, 4, Charsets.US_ASCII).lowercase() else ""
            when (brand) {
                "avif", "avis" -> return "avif"
                "heic", "heix", "mif1" -> return "heic"
            }
        }
        return null
    }

    /**
     * 文本型图片（SVG）：只在 Content-Type 缺失/未知时才调用。
     *
     * 要求开头就是 SVG 根元素或 XML 声明 —— 不允许「正文里出现 <svg」，
     * 那正是 HTML 错误页命中 .svg 的路径。
     */
    private fun textExtension(head: ByteArray?): String? {
        if (head == null || head.isEmpty()) return null
        val text = String(head, 0, minOf(head.size, 256), Charsets.UTF_8).trimStart()
        if (text.startsWith("<svg", ignoreCase = true)) return "svg"
        if (text.startsWith("<?xml", ignoreCase = true) && text.contains("<svg", ignoreCase = true)) return "svg"
        return null
    }

    /** URL 路径最后一段的后缀；不在白名单里就是 null（防 `.php`、`.exe` 之类）。 */
    private fun urlExtension(url: String?): String? {
        val path = url?.trim()?.substringBefore('#')?.substringBefore('?').orEmpty()
        if (path.isEmpty()) return null
        val name = path.substringAfterLast('/')
        if (name.isEmpty() || !name.contains('.')) return null
        return when (val raw = name.substringAfterLast('.').lowercase()) {
            "jpeg" -> "jpg"
            "tif" -> "tiff"
            in URL_EXTENSIONS -> raw
            else -> null
        }
    }

    /**
     * 「未知」而不是「已知不是图片」的 MIME。
     *
     * 对象存储/图床经常用这两种发图片；此时宁可继续看 URL 后缀与魔数，也不要直接判死。
     */
    private fun isOpaqueMime(mime: String): Boolean =
        mime == "application/octet-stream" || mime == "binary/octet-stream"

    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    private fun ByteArray.asciiAt(offset: Int, ascii: String): Boolean {
        if (offset < 0 || size < offset + ascii.length) return false
        for (i in ascii.indices) {
            if (this[offset + i] != ascii[i].code.toByte()) return false
        }
        return true
    }
}
