package com.ridermonkey.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageContextActionTest {

    private val pngHead = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
    private val jpegHead = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

    @Test
    fun onlyImageContextsOfferTheItem() {
        assertEquals(
            "https://cdn.example.com/a.png",
            ImageContextAction.pickSourceUrl(true, "CM_MEDIATYPE_NONE", "https://cdn.example.com/a.png")
        )
        assertEquals(
            "https://cdn.example.com/a.png",
            ImageContextAction.pickSourceUrl(false, "CM_MEDIATYPE_IMAGE", "https://cdn.example.com/a.png")
        )
        // 普通链接/文字/空白处不出这一项，否则点了必然失败。
        assertNull(ImageContextAction.pickSourceUrl(false, "CM_MEDIATYPE_NONE", "https://cdn.example.com/a.png"))
        assertNull(ImageContextAction.pickSourceUrl(false, "CM_MEDIATYPE_VIDEO", "https://cdn.example.com/a.mp4"))
        assertNull(ImageContextAction.pickSourceUrl(false, null, "https://cdn.example.com/a.png"))
        assertNull(ImageContextAction.pickSourceUrl(true, "CM_MEDIATYPE_IMAGE", null))
    }

    @Test
    fun onlyHttpSourcesAreOffered() {
        assertEquals(
            "HTTPS://CDN.EXAMPLE.COM/A.PNG",
            ImageContextAction.pickSourceUrl(true, null, "  HTTPS://CDN.EXAMPLE.COM/A.PNG  ")
        )
        // 这些取不到字节 / 不该由页面点单，一律不出菜单项。
        assertNull(ImageContextAction.pickSourceUrl(true, null, "data:image/png;base64,AAAA"))
        assertNull(ImageContextAction.pickSourceUrl(true, null, "blob:https://linux.do/abc"))
        assertNull(ImageContextAction.pickSourceUrl(true, null, "file:///C:/Windows/win.png"))
        assertNull(ImageContextAction.pickSourceUrl(true, null, "about:blank"))
        assertNull(ImageContextAction.pickSourceUrl(true, null, "//cdn.example.com/a.png"))
        assertNull(ImageContextAction.pickSourceUrl(true, null, "httpx://example.com/a.png"))
        // 只有 scheme 没有内容：下载必然失败，不给用户一个点了没反应的项。
        assertNull(ImageContextAction.pickSourceUrl(true, null, "https://"))
        assertNull(ImageContextAction.pickSourceUrl(true, null, "http://"))
        // 防回归：判据曾经用 https 前缀长度(8)统一比较，把长度刚好是 8 的 "http://x" 误拒了。
        assertEquals("http://x", ImageContextAction.pickSourceUrl(true, null, "http://x"))
    }

    @Test
    fun magicBytesWinOverALyingContentType() {
        // 图床常见：图片按 application/octet-stream 发；错误页则常带 text/html。
        assertEquals("png", ImageContextAction.extensionFor("application/octet-stream", "https://x/a", pngHead))
        assertEquals("png", ImageContextAction.extensionFor("text/html", "https://x/a.jpg", pngHead))
        assertEquals("jpg", ImageContextAction.extensionFor(null, "https://x/no-extension", jpegHead))
        assertEquals("gif", ImageContextAction.extensionFor(null, null, "GIF89a....".toByteArray()))
        assertEquals("webp", ImageContextAction.extensionFor(null, null, "RIFF....WEBPVP8 ".toByteArray()))
        assertEquals("avif", ImageContextAction.extensionFor(null, null, "....ftypavif....".toByteArray()))
        assertEquals("svg", ImageContextAction.extensionFor(null, null, "  <svg xmlns=\"x\"/>".toByteArray()))
    }

    @Test
    fun contentTypeThenUrlAreTheFallbacks() {
        assertEquals("webp", ImageContextAction.extensionFor("image/webp; charset=binary", "https://x/a", null))
        assertEquals("svg", ImageContextAction.extensionFor("image/svg+xml", "https://x/a", null))
        // octet-stream 属于「未知」：允许继续看 URL 后缀。
        assertEquals("jpg", ImageContextAction.extensionFor("application/octet-stream", "https://x/a.JPEG?size=2", null))
        assertEquals("tiff", ImageContextAction.extensionFor(null, "https://x/a.tif", null))
        assertEquals("png", ImageContextAction.extensionFor(null, "https://x/dir.v2/a.png#frag", null))
    }

    @Test
    fun htmlErrorPageIsNotSavedAsAnImage() {
        // 明确的非图片类型：不靠 .jpg 后缀去猜，否则会把错误页存成图片。
        assertNull(
            ImageContextAction.extensionFor(
                "text/html; charset=utf-8",
                "https://x/a.jpg",
                "<!doctype html><html>403</html>".toByteArray()
            )
        )
        assertNull(ImageContextAction.extensionFor(null, "https://x/avatar.php", null))
        assertNull(ImageContextAction.extensionFor(null, "https://x/a", null))
        // 未知的 image/* 子类型又没有 URL 后缀：判不出就是判不出，不硬猜一个扩展名。
        assertNull(ImageContextAction.extensionFor("image/bogus", "https://x/a", "ZZ".toByteArray()))
        assertNull(ImageContextAction.extensionFor(null, null, null))
    }

    @Test
    fun htmlWithInlineSvgIsNotMistakenForAnImage() {
        // 错误页里内联 <svg> 图标很常见；文本嗅探不能压过 Content-Type，否则会存成 .svg。
        val html = "<!doctype html><html><body><svg viewBox='0 0 1 1'></svg></body></html>".toByteArray()
        assertNull(ImageContextAction.extensionFor("text/html", "https://x/a.svg", html))
        assertNull(ImageContextAction.extensionFor("application/octet-stream", "https://x/a.jpg", html))
        // 真正的 SVG 仍然认得出（Content-Type 缺失或未知时才走文本嗅探）。
        assertEquals("svg", ImageContextAction.extensionFor(null, "https://x/a", "<svg xmlns='x'/>".toByteArray()))
        assertEquals(
            "svg",
            ImageContextAction.extensionFor(
                "application/octet-stream", "https://x/a", "<?xml version='1.0'?><svg/>".toByteArray()
            )
        )
    }

    @Test
    fun commandIdStaysInCefUserRange() {
        // CEF 只接受 26500 之后的自定义号；28500 已被 IntelliJ 的 Open DevTools 占用。
        assertTrue(ImageContextAction.COMMAND_ID >= 26_500)
        assertTrue(ImageContextAction.COMMAND_ID < 28_500)
    }

    @Test
    fun tempFilePrefixIsSafeAndLongEnough() {
        assertEquals("photo-", ImageContextAction.filePrefixFor("https://cdn.example.com/dir/photo.jpg?size=large"))
        // Files.createTempFile 要求前缀 ≥ 3 字符：URL 里没有可用片段时必须兜底。
        assertEquals("image-", ImageContextAction.filePrefixFor(null))
        assertEquals("image-", ImageContextAction.filePrefixFor("https://example.com/"))
        assertEquals("image-", ImageContextAction.filePrefixFor("https://example.com/ab"))
        val cjk = ImageContextAction.filePrefixFor("https://example.com/日落 风景.png")
        assertTrue(cjk.endsWith("-"))
        // Windows 文件名非法字符全集：出现任何一个都会让 createTempFile 抛异常。
        val illegal = setOf(' ', '/', '\\', ':', '*', '?', '"', '<', '>', '|')
        assertTrue(cjk.none { it in illegal })
        val long = ImageContextAction.filePrefixFor("https://example.com/" + "a".repeat(300) + ".png")
        assertTrue(long.length <= 49)
        // Windows 保留名只按主名匹配：末尾的 "-" 让 CON/NUL/COM1 之类不再命中。
        assertEquals("CON-", ImageContextAction.filePrefixFor("https://example.com/CON.png"))
        assertEquals("NUL-", ImageContextAction.filePrefixFor("https://example.com/NUL"))
    }

    @Test
    fun prefixIsAcceptedByCreateTempFile() {
        // 前缀「至少 3 个字符」的真实失败点是 createTempFile，直接断言它不抛。
        val dir = java.nio.file.Files.createDirectories(java.nio.file.Path.of("build", "tmp", "image-prefix-test"))
        val prefix = ImageContextAction.filePrefixFor("https://cdn.example.com/photo.jpg")
        val file = java.nio.file.Files.createTempFile(dir, prefix, ".png")
        assertTrue(file.fileName.toString().startsWith(prefix))
        assertTrue(file.fileName.toString().endsWith(".png"))
    }
}
