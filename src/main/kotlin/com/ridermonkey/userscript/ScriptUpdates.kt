package com.ridermonkey.userscript

import com.intellij.openapi.diagnostic.Logger
import com.ridermonkey.storage.ScriptFileStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * 版本号比较。
 *
 * 单独抽成对象是为了能直接单测 —— 它是自动更新里唯一容易写错、又最容易悄悄写错的部分
 * （`1.10` 与 `1.9` 按字符串比会得出相反结论）。
 */
object ScriptVersions {

    /**
     * 比较两个版本号；返回正数表示 [a] 比 [b] 新，0 表示等价。
     *
     * 规则：先按 `.` / `-` / `_` 分段；逐段比较时**两侧都是数字就按数值比**，
     * 否则按不区分大小写的字典序。段数不等时短的补空段，并约定：
     * - 数字段 vs 空段：数字段更新（`1.2.1` > `1.2`）
     * - 非数字段 vs 空段：非数字段更旧（`1.0-beta` < `1.0`，与语义化版本的预发布约定一致）
     *
     * **这是简化实现**：不处理 `build.5` 这类带前缀的段语义，也不做完整 semver 校验。
     * 目的是「判断远端是不是更新了」，不是严格排序。
     */
    fun compare(a: String, b: String): Int {
        val left = segments(a)
        val right = segments(b)
        val count = maxOf(left.size, right.size)
        for (i in 0 until count) {
            val p = left.getOrNull(i).orEmpty()
            val q = right.getOrNull(i).orEmpty()
            val pn = p.toLongOrNull()
            val qn = q.toLongOrNull()
            val diff = when {
                pn != null && qn != null -> pn.compareTo(qn)
                pn != null && q.isEmpty() -> 1
                qn != null && p.isEmpty() -> -1
                p.isEmpty() && q.isNotEmpty() -> if (qn != null) -1 else 1
                p.isNotEmpty() && q.isEmpty() -> if (pn != null) 1 else -1
                else -> p.compareTo(q, ignoreCase = true)
            }
            if (diff != 0) return diff
        }
        return 0
    }

    private fun segments(version: String): List<String> =
        version.trim().split('.', '-', '_').filter { it.isNotEmpty() }
}

/**
 * 脚本更新检查与应用。
 *
 * 设计取舍（见 ADR-0013）：
 * - **只在用户显式触发时检查**。`@updateURL` 是外部地址，定时静默联网既不可预期，
 *   也会拖慢 IDE 启动。
 * - **不静默覆盖脚本**。发现新版本只报告，由用户确认后才写盘；写盘前把旧文件另存为
 *   `<名字>.user.js.bak`。备份**不会**被当成脚本读入 —— [ScriptFileStore.load] 只收集
 *   `*.user.js`。
 * - `@updateURL` 允许只放元数据块（Tampermonkey 的 `.meta.js` 约定），此时正文取
 *   `@downloadURL`（未声明则回落到 `@updateURL`）。
 */
class ScriptUpdates(private val store: ScriptFileStore) {

    /** 单个脚本的检查结果。 */
    sealed interface CheckResult {
        /** 远端更新，[remoteBody] 是可直接写盘的完整脚本文本。 */
        data class UpdateAvailable(val remoteVersion: String, val remoteBody: String) : CheckResult
        data class UpToDate(val localVersion: String, val remoteVersion: String) : CheckResult
        data class Failed(val message: String) : CheckResult
        /** 脚本没有声明 `@updateURL`，无从检查。 */
        data object NotDeclared : CheckResult
    }

    private val logger = Logger.getInstance("RiderMonkeyBrowser")

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    fun check(entry: UserscriptEntry): CheckResult {
        val metaUrl = entry.metadata.updateURL ?: return CheckResult.NotDeclared
        val metaText = fetch(metaUrl).getOrElse { return CheckResult.Failed(it.message ?: "请求失败") }
        val remoteMeta = UserscriptParser.parse(metaText)?.metadata
            ?: return CheckResult.Failed("远端内容不是有效的用户脚本（缺少元数据块）")
        val remoteVersion = remoteMeta.version ?: return CheckResult.Failed("远端脚本没有 @version")

        val local = entry.metadata.version
        if (local != null && ScriptVersions.compare(remoteVersion, local) <= 0) {
            return CheckResult.UpToDate(local, remoteVersion)
        }

        val bodyUrl = entry.metadata.downloadURL ?: metaUrl
        val bodyText = if (bodyUrl == metaUrl) metaText else fetch(bodyUrl).getOrElse {
            return CheckResult.Failed("下载正文失败：" + (it.message ?: "未知错误"))
        }
        // 只有元数据块、没有脚本正文时**绝不能**写盘，否则会把脚本替换成一个空壳。
        val parsed = UserscriptParser.parse(bodyText)
        if (parsed == null || parsed.body.isBlank()) {
            return CheckResult.Failed("远端只有元数据，没有脚本正文（应提供 @downloadURL）")
        }
        return CheckResult.UpdateAvailable(remoteVersion, bodyText)
    }

    /**
     * 写盘应用更新。
     *
     * @return null 表示成功；否则是给用户看的失败原因
     */
    fun apply(entry: UserscriptEntry, remoteBody: String): String? {
        val target = runCatching { Path.of(entry.filePath) }.getOrNull() ?: return "脚本路径不可用"
        return runCatching {
            if (Files.isRegularFile(target)) {
                val backup = target.resolveSibling(target.fileName.toString() + ".bak")
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.writeString(target, remoteBody)
            store.invalidate()
            logger.info("[RM-UPDATE] 已更新 " + target.fileName)
            null
        }.getOrElse { it.message ?: "写入失败" }
    }

    private fun fetch(url: String): Result<String> {
        val uri = runCatching { URI.create(url) }.getOrElse { return Result.failure(it) }
        return runCatching {
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() >= 400) error("HTTP " + response.statusCode())
            response.body()?.takeIf { it.isNotBlank() } ?: error("响应为空")
        }
    }
}
