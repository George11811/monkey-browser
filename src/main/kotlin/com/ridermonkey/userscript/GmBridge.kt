package com.ridermonkey.userscript

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.ridermonkey.storage.GmStorage
import com.ridermonkey.util.JsonStrings
import java.awt.datatransfer.StringSelection
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 接收页面侧 GM 写操作的桥。
 *
 * 通道选择（**偏离方案第 4.3 节的 JBCefJSQuery**，理由见 ADR-0005）：
 * 用注入脚本的 `console.log` 回传，因为这条通道在本项目已被实测验证（PoC 与注入器结果
 * 回传都走它），而 JBCefJSQuery 的 inject 模板在字节码里无法静态验证。
 *
 * 安全模型：每次注入生成一次性 token，并按「脚本 + frame」登记；收到消息先校验 token，
 * 不匹配一律拒绝并记日志。**这不是沙箱**：页面脚本若提前 hook 了 console.log 或桥接函数，
 * 仍可能窃取 token 后伪造写入。真正的隔离需要 CEF 的 isolated world / 独立 JS 上下文，
 * 属于后续工作（方案 P1「脚本权限」）。
 *
 * 为什么授权键要带 frame：同一个脚本可能同时注入主 frame 与多个 iframe。若只按脚本 id 登记，
 * 后一次注入会覆盖前一次的 token，先注入的那个 frame 后续所有 `GM_setValue` 都会被静默拒绝。
 */
class GmBridge(private val storage: GmStorage) {

    private val logger = Logger.getInstance("RiderMonkeyBrowser")
    private val tokens = ConcurrentHashMap<String, String>()

    /** 网络请求处理器；由 ScriptInjector 装配（它掌握 frame 与脚本库）。 */
    private var network: GmNetwork? = null

    fun setNetworkHandler(handler: GmNetwork) {
        network = handler
    }

    /** 回投递通道（frameId, json）；由 ScriptInjector 装配，它掌握 frame 表。 */
    private var deliver: ((String, String) -> Unit)? = null

    fun setDeliver(fn: (String, String) -> Unit) {
        deliver = fn
    }

    /** 标签页能力；由 BrowserContainer 装配。未装配时 GM_openInTab 回执 tabId=-1。 */
    private var host: GmHost? = null

    fun setHost(value: GmHost) {
        host = value
    }

    /** 来源校验通过只记一次，避免刷日志（0.9.0 用它确认机制生效，现已实测通过）。 */
    private val sourceVerifiedLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 记录某个脚本在某个 frame 里本次注入使用的一次性 token。 */
    fun registerToken(scriptId: String, frameId: String, token: String) {
        tokens[tokenKey(scriptId, frameId)] = token
    }

    fun clearTokens() = tokens.clear()

    /**
     * 处理一条 console 消息。
     *
     * @return true 表示这条消息属于 GM 通道（已被消费或已被拒绝），调用方不应再当普通日志处理。
     */
    fun handleConsoleMessage(message: String, source: String): Boolean {
        if (!message.startsWith(PREFIX)) return false

        val fields = parsePayload(message.removePrefix(PREFIX))
        val scriptId = fields["scriptId"]
        val frameId = fields["frameId"]
        val token = fields["token"]
        if (scriptId == null || frameId == null || token == null ||
            tokens[tokenKey(scriptId, frameId)] != token
        ) {
            logger.info("[RM-GM] 拒绝未授权调用：scriptId=" + scriptId + " frameId=" + frameId)
            return true
        }

        // 来源指纹：console 消息的 source 由 CEF 按「执行脚本时的 scriptUrl」填充，页面**无法伪造**
        // （它自己 console.log 时 source 是页面自己的脚本地址）。
        // 0.9.0 先实测确认了平台确实这样回填（日志：来源校验通过），0.9.1 起收紧为硬校验：
        // 即使页面 hook console.log 偷到 token，重放也会因 source 不符被拒。
        val expectedSource = sourceUrlFor(scriptId, frameId, token)
        if (source != expectedSource) {
            logger.info("[RM-GM] 拒绝来源不符的调用：source=" + source + " 期望=" + expectedSource)
            return true
        }
        if (sourceVerifiedLogged.compareAndSet(false, true)) {
            logger.info("[RM-GM] 来源校验通过：source=" + source)
        }

        when (fields["op"]) {
            "set" -> {
                val key = fields["key"] ?: return true
                val value = fields["value"] ?: return true
                storage.put(scriptId, key, value)
            }
            "delete" -> {
                val key = fields["key"] ?: return true
                storage.remove(scriptId, key)
            }
            "clipboard" -> {
                val text = fields["text"] ?: return true
                runCatching {
                    CopyPasteManager.getInstance().setContents(StringSelection(text))
                }.onFailure { logger.info("[RM-GM] 写剪贴板失败：" + it.message) }
            }
            "notify" -> {
                val text = fields["text"] ?: return true
                val title = fields["title"]?.takeIf { it.isNotBlank() } ?: "Monkey Browser"
                runCatching {
                    Notifications.Bus.notify(
                        Notification("Monkey Browser", title, text, NotificationType.INFORMATION)
                    )
                }.onFailure { logger.info("[RM-GM] 发送通知失败：" + it.message) }
            }
            "xhr" -> {
                val handler = network
                if (handler == null) {
                    logger.info("[RM-GM] 网络处理器尚未装配，忽略该请求")
                } else {
                    handler.handle(scriptId, frameId, fields)
                }
            }
            "abort" -> {
                // GM_xmlhttpRequest / GM_download 返回的句柄调 abort() 时走这里。
                // 仍受来源指纹与令牌校验约束。
                val handler = network
                val requestId = fields["id"]
                if (handler != null && requestId != null) {
                    handler.abort(frameId, requestId)
                }
            }
            "download" -> {
                val handler = network
                if (handler == null) {
                    logger.info("[RM-GM] 网络处理器尚未装配，忽略下载请求")
                } else {
                    handler.handleDownload(scriptId, frameId, fields)
                }
            }
            "openTab" -> {
                // 打开新标签页是同步动作，但句柄要**异步**回传：这里通过投递通道把 tabId 送回页面。
                // host 未装配时回 -1，脚本侧据此把 close() 变成安全的空操作。
                val requestId = fields["id"] ?: return true
                val url = fields["url"] ?: return true
                val active = fields["active"] != "false"
                val tabId = host?.openTab(url, active) ?: -1
                deliver?.invoke(
                    frameId,
                    "{" + JsonStrings.quoted("id") + ":" + JsonStrings.quoted(requestId) + "," +
                        JsonStrings.quoted("tabId") + ":" + tabId + "}"
                )
            }
            "closeTab" -> {
                val tabId = fields["tabId"]?.toIntOrNull()
                if (tabId != null) host?.closeTabById(tabId)
            }
            else -> logger.info("[RM-GM] 忽略未知操作：" + fields["op"])
        }
        return true
    }

    private fun tokenKey(scriptId: String, frameId: String): String = scriptId + "|" + frameId

    /**
     * 判断某个来源是否属于「我们注入给该脚本该 frame 的代码」。
     *
     * 用于校验注入完成标记（标记里没有 token，无法比对完整 URL，但前缀本身已不可伪造）。
     * 页面自己发的 console 消息 source 是页面地址，套不上这个前缀。
     */
    fun isTrustedSource(source: String, scriptId: String, frameId: String): Boolean =
        source.startsWith(sourceUrlPrefix(scriptId, frameId))

    private fun parsePayload(payload: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in payload.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq)
            val value = runCatching {
                URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }.getOrNull() ?: continue
            out[name] = value
        }
        return out
    }

    companion object {
        const val PREFIX: String = "__RM_GM__"

        /** 注入脚本的来源 URL 前缀。用 .invalid（RFC 2606 保留、保证不存在）避免与真实站点混淆。 */
        private const val SOURCE_URL_BASE = "https://rider-monkey.invalid/userscript/"

        /**
         * 本次注入的脚本来源 URL。
         *
         * 同时用作两件事：`CefFrame.executeJavaScript` 的 scriptUrl，以及 console 消息 source 的比对基准。
         * 因此它是个**不可伪造的指纹**——token 可能被页面 hook console.log 后窃取，
         * 但页面自己发的消息 source 永远不等于这个 URL。
         */
        fun sourceUrlFor(scriptId: String, frameId: String, token: String): String =
            sourceUrlPrefix(scriptId, frameId) + token

        /** 来源 URL 的前缀（不含 token），用于前缀级校验。 */
        fun sourceUrlPrefix(scriptId: String, frameId: String): String =
            SOURCE_URL_BASE + java.net.URLEncoder.encode(scriptId, "UTF-8") + "/" +
                java.net.URLEncoder.encode(frameId, "UTF-8") + "/"
    }
}