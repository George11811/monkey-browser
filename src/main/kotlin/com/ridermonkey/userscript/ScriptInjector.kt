package com.ridermonkey.userscript

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.network.CefRequest
import com.ridermonkey.storage.GmStorage
import com.ridermonkey.storage.ResourceCache
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把匹配的用户脚本注入到页面里。
 *
 * 设计依据 ADR-0003；实现上有一处**有意的简化**：
 *
 * ADR-0003 原计划在 `onAddressChange` 上做「早档」注入。但实测发现导航早期的
 * `frame.url` 仍是**旧文档**的 URL（ADR-0002 C5），据此注入可能把脚本打进旧文档，
 * 却按新 URL 记账，导致新文档真正就绪时被去重表挡掉——脚本永远不会执行。
 *
 * 因此首版只在**文档已提交**的两个时机注入：
 * - `onLoadEnd`：拿到的是本帧已提交的 URL（`readyState = complete`）；
 * - `onLoadingStateChange(false)`：补一次，用于 SPA 在同一文档内改变 URL 的情况。
 *
 * 两者都用 [InjectionRegistry] 去重。代价是首版注入点固定在 `complete`，
 * 比 `document-end` 略晚——这一点已记入 ADR-0003。
 */
class ScriptInjector(
    private val browser: JBCefBrowser,
    private val scriptsProvider: () -> List<UserscriptEntry>,
    private val storage: GmStorage,
    private val resources: ResourceCache,
    private val bridge: GmBridge
) : Disposable {

    private val logger = Logger.getInstance("RiderMonkeyBrowser")
    private val registry = InjectionRegistry()
    private val disposed = AtomicBoolean(false)

    /**
     * 每个主 frame 上次提前注入过的 URL。
     *
     * 用来区分「刷新同一 URL」与「导航到新 URL、但此刻 frame.url 还是旧文档的值」：
     * URL 与上次相同就不提前注入——否则会在即将销毁的旧文档里再跑一遍脚本，GM 写入会翻倍。
     */
    private val lastMainUrl = ConcurrentHashMap<String, String>()

    /** frameId -> CefFrame：网络响应要用它回调页面。文档销毁后条目会被覆盖或失效。 */
    private val frames = ConcurrentHashMap<String, CefFrame>()

    /** 注册 CEF 回调。必须在浏览器内核已创建之后调用。 */
    fun attach() {
        val cef: CefBrowser? = browser.cefBrowser
        if (cef == null) {
            logger.info("[RM-INJECT] 内核未就绪，注入器未注册")
            return
        }
        val client = browser.getJBCefClient()
        client.addLoadHandler(LoadHandler(), cef)
        client.addDisplayHandler(ResultHandler(), cef)
        // 装配 GM 网络后端：它需要 frame 表与脚本库来做响应投递与 @connect 授权。
        val networkHandler = GmNetwork(
            scripts = { scriptsProvider() },
            frameLookup = { frameId -> frames[frameId] },
            documentUrlOf = { frameId -> frames[frameId]?.url }
        )
        bridge.setNetworkHandler(networkHandler)
        // GM_openInTab 的句柄回执也要走同一条投递通道 —— 复用它的实现，不再写一份。
        bridge.setDeliver { frameId, json -> networkHandler.deliverToFrame(frameId, json) }
        logger.info("[RM-INJECT] 注入器已注册，当前脚本数=" + scriptsProvider().size)
    }

    override fun dispose() {
        disposed.set(true)
        registry.clear()
        frames.clear()
        bridge.clearTokens()
    }

    private inner class LoadHandler : CefLoadHandler {

        override fun onLoadingStateChange(
            cefBrowser: CefBrowser,
            isLoading: Boolean,
            canGoBack: Boolean,
            canGoForward: Boolean
        ) {
            // 这里**刻意不再注入**。
            //
            // 原实现在加载结束时按当前 URL 补一次注入，为的是覆盖 SPA 路由变化。但去重键里带文档 URL，
            // 而 SPA 站点（linux.do / v2ex 等）在无限滚动时只改 URL、不换文档 —— 于是每次滚动
            // 都被当成「新文档」，把整份脚本（实测 250–400KB 的换皮脚本）重跑一遍，
            // 页面看起来像刷新、还会闪（用户实测：帖子内滚动偶现刷新）。
            //
            // 这也与 Tampermonkey 的口径不一致：TM 只在文档加载时执行一次，不会因 pushState 重跑。
            // 真实导航仍由早档（主 frame onLoadStart）与 onLoadEnd 两档覆盖，不会漏注入。
            if (isLoading) return
        }

        override fun onLoadStart(
            cefBrowser: CefBrowser,
            frame: CefFrame,
            transitionType: CefRequest.TransitionType
        ) {
            val frameId = frame.identifier
            val url = frame.url
            // 逐帧加载：一页可能有多个跨域 iframe，每帧还会多次导航，属高频噪声。
            // 降为 DEBUG —— 需要时用 Help | Diagnostic Tools | Debug Log Settings 打开 RiderMonkeyBrowser。
            logger.debug(
                "[RM-BROWSER] onLoadStart main=" + frame.isMain + " frame=" + frameId + " url=" + url
            )
            if (frame.isMain) {
                if (frameId != null && url != null && url == lastMainUrl[frameId]) {
                    // URL 与上次相同：可能是刷新，也可能只是旧文档在导航开始时做的最后一次回调。
                    // 两种情况都不该在这里提前注入——刷新不需要（主档会注），
                    // 而后者更糟：会在即将销毁的旧文档里再跑一遍脚本，**GM 写入会翻倍**。
                    // 改为撤销该 URL 的去重键，交给文档真正就绪后的主档。
                    registry.releaseUrl(frameId, url)
                } else {
                    if (frameId != null && url != null) lastMainUrl[frameId] = url
                    registry.clear()
                    logger.info("[RM-INJECT] 主 frame 开始导航，清空注入去重表")
                    // 早档注入：实测落点常在 loading/interactive，比主档的 complete 更早。
                    // 落点是否真的在目标文档里由完成标记核对，不符则自愈（见 onInjected）。
                    injectInto(frame)
                }
            } else if (frameId != null) {
                registry.clearFrame(frameId)
            }
        }

        override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
            logger.debug("[RM-BROWSER] onLoadEnd main=" + frame.isMain + " status=" + httpStatusCode)
            injectInto(frame)
        }

        override fun onLoadError(
            cefBrowser: CefBrowser,
            frame: CefFrame,
            errorCode: CefLoadHandler.ErrorCode,
            errorText: String,
            failedUrl: String
        ) = Unit
    }

    /** 接收脚本包装器回传的执行结果。 */
    private inner class ResultHandler : CefDisplayHandlerAdapter() {
        override fun onConsoleMessage(
            cefBrowser: CefBrowser,
            level: CefSettings.LogSeverity,
            message: String,
            source: String,
            line: Int
        ): Boolean {
            // GM 写操作走同一条 console 通道，先交给桥处理。
            if (bridge.handleConsoleMessage(message, source)) return false
            if (message.startsWith(OK_PREFIX)) {
                onInjected(message.removePrefix(OK_PREFIX), source)
            } else if (message.startsWith(ERR_PREFIX)) {
                logger.info("[RM-INJECT] 脚本抛异常 " + message.removePrefix(ERR_PREFIX))
            }
            return false
        }
    }

    private fun injectInto(frame: CefFrame) {
        if (disposed.get()) return
        val url = frame.url ?: return
        if (url.isEmpty() || url == "about:blank") return

        val frameId = frame.identifier ?: "unknown"
        frames[frameId] = frame
        val scripts = ScriptSelection.select(scriptsProvider(), url, frame.isMain)
        for (script in scripts) {
            if (!registry.markIfNew(frameId, url, script.id)) continue
            // 每次注入一个一次性 token，GM 写入必须带上它，详见 GmBridge 的安全模型说明。
            val token = java.util.UUID.randomUUID().toString()
            bridge.registerToken(script.id, frameId, token)
            // scriptUrl 用不可伪造的专属来源：CEF 会用它在 console 消息里回填 source，
            // 成为校验调用方身份的指纹（页面自己发的消息 source 不同）。详见 ADR-0007。
            val scriptUrl = GmBridge.sourceUrlFor(script.id, frameId, token)
            try {
                frame.executeJavaScript(wrap(script, token, frameId, url), scriptUrl, 0)
                logger.info("[RM-INJECT] 注入「" + script.displayName + "」-> " + url)
            } catch (t: Throwable) {
                logger.info("[RM-INJECT] 注入「" + script.displayName + "」失败：" + t.message)
                // 只回滚这一条键。原先这里调用 registry.clear() 会把**所有 frame** 的记录一起清掉，
                // 导致别的文档被重复注入。
                registry.release(frameId, url, script.id)
            }
        }
    }

    /**
     * 把脚本正文包进 IIFE + try/catch，并在结束时回传一个带脚本 id 的标记。
     *
     * 用 IIFE 贴近 Tampermonkey 的执行模型（脚本内的 var 不污染页面全局），
     * 用 try/catch 保证脚本抛错时能被我方观测到，而不是静默消失。
     * 注意：语法错误无法被 try/catch 捕获，那种情况下只会看到「注入」而看不到结果。
     */
    private fun wrap(script: UserscriptEntry, token: String, frameId: String, url: String): String {
        val sb = StringBuilder()
        sb.appendLine("(function(){")
        if (providesGmApi(script.metadata)) {
            val grants = GmCapabilities.granted(script.metadata)
            val resourceMap = script.metadata.resourceMap
            sb.appendLine(
                GmRuntime.buildPreamble(
                    GmRuntime.Preamble(
                        scriptId = script.id,
                        frameId = frameId,
                        token = token,
                        snapshot = storage.snapshot(script.id),
                        grants = grants,
                        infoJson = GmInfo.toJson(script),
                        // 只嵌入脚本真正被授权读取的那部分资源，避免给不需要的脚本塞几百 KB base64。
                        resources = if (
                            grants.contains("GM_getResourceText") ||
                            grants.contains("GM.getResourceText")
                        ) {
                            resources.cachedTexts(script.id, resourceMap)
                        } else {
                            emptyMap()
                        },
                        resourceUrls = if (
                            grants.contains("GM_getResourceURL") ||
                            grants.contains("GM.getResourceUrl")
                        ) {
                            resources.cachedDataUrls(script.id, resourceMap)
                        } else {
                            emptyMap()
                        }
                    )
                )
            )
        }
        sb.appendLine("try {")
        sb.appendLine(script.body)
        // 完成标记里带上「本次注入的目标」（scriptId|frameId|目标URL）与「实际落点 href」。
        // 有了目标 URL，日志才能一一配对；落点不符时 Kotlin 侧会撤销去重键并等待重新注入。
        val marker = OK_PREFIX + encodeForMarker(script.id) + "|" +
            encodeForMarker(frameId) + "|" + encodeForMarker(url) + "|"
        sb.appendLine(
            "console.log(" + jsLiteral(marker) +
                " + document.readyState + '|' + encodeURIComponent(location.href));"
        )
        sb.appendLine("} catch (e) { console.log('" + ERR_PREFIX + script.id + " ' + (e && e.message ? e.message : String(e))); }")
        sb.append("})();")
        return sb.toString()
    }

    /**
     * 处理注入完成标记：`scriptId|frameId|目标URL|readyState|实际href`（前三个与 href 都做过 URL 编码）。
     *
     * 0.7.0 的教训：主 frame `onLoadStart` 注入有时会**落进旧文档**，却被按新 URL 记账，
     * 结果目标页面永远等不到脚本（表现为闪原样式）。这里做落点核对并自愈。
     */
    private fun onInjected(body: String, source: String) {
        val parts = body.split("|", limit = 5)
        if (parts.size < 5) {
            logger.info("[RM-INJECT] 脚本执行完毕 " + body)
            return
        }
        val scriptId = decodeMarker(parts[0])
        val frameId = decodeMarker(parts[1])
        val intendedUrl = decodeMarker(parts[2])
        val ready = parts[3]
        val actualHref = decodeMarker(parts[4])

        // 完成标记会改动去重表，属于状态变更，必须确认它真的来自我们注入的代码——
        // 否则页面可以伪造标记把键撤销（导致重复注入）或占位（导致脚本不再注入）。
        if (!bridge.isTrustedSource(source, scriptId, frameId)) {
            logger.info("[RM-INJECT] 忽略来源可疑的完成标记：source=" + source + " scriptId=" + scriptId)
            return
        }

        if (actualHref == intendedUrl) {
            logger.info("[RM-INJECT] 脚本执行完毕 " + scriptId + " ready=" + ready + " href=" + actualHref)
        } else {
            // 落点不对：脚本并没有跑在目标文档里，把去重键还回去，等文档真正就绪后重新注入。
            registry.release(frameId, intendedUrl, scriptId)
            // 但脚本确实在「实际文档」里跑过了，把那一个也记上：否则它随后的主档注入会再跑一遍，
            // 造成重复执行（GM 写入会翻倍）。
            registry.markIfNew(frameId, actualHref, scriptId)
            logger.info(
                "[RM-INJECT] 落点不符，已撤销等待重新注入 " + scriptId +
                    " | 目标=" + intendedUrl + " | 实际=" + actualHref
            )
        }
    }

    private fun encodeForMarker(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun decodeMarker(text: String): String =
        runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)

    private fun jsLiteral(text: String): String =
        String(charArrayOf('"')) + com.ridermonkey.util.JsonStrings.escape(text) + String(charArrayOf('"'))

    /**
     * 是否注入 GM 运行时。
     *
     * 由 \`@grant\` 决定（最小权限）：\`none\`、未声明、或声明的都是本实现未实现的 API 时，
     * 一个能力都不给，连存储快照都不嵌入。能力裁剪本身见 [GmCapabilities]。
     */
    private fun providesGmApi(metadata: UserscriptMetadata): Boolean =
        GmCapabilities.providesAny(metadata)

    private companion object {
        const val OK_PREFIX: String = "__RM_INJECT_OK__"
        const val ERR_PREFIX: String = "__RM_INJECT_ERR__"
    }
}