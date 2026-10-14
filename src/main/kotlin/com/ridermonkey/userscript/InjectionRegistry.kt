package com.ridermonkey.userscript

import java.util.concurrent.ConcurrentHashMap

/**
 * 注入去重表。
 *
 * 同一个「frame + 文档 URL + 脚本」只允许注入一次，避免：
 * - \`onLoadEnd\` 与 \`onLoadingStateChange(false)\` 都对同一文档触发；
 * - SPA 在同一文档里多次触发地址变化；
 * 导致同一脚本被重复执行。
 *
 * 键里带文档 URL 而不是只带 frame，是为了让同一个 frame 导航到新页面后能重新注入。
 *
 * 用并发集合：CEF 回调与 dispose（EDT）可能并发访问，普通 LinkedHashSet 会结构性损坏。
 */
class InjectionRegistry {

    private val keys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 首次见到该组合返回 true，重复返回 false。 */
    fun markIfNew(frameId: String, documentUrl: String, scriptId: String): Boolean =
        keys.add(frameId + "|" + documentUrl + "|" + scriptId)

    fun size(): Int = keys.size

    /**
     * 丢弃某个 frame 的全部记录。
     *
     * 用于「该 frame 开始新导航」：新导航意味着新文档，同一个 (frame, url, 脚本) 必须允许
     * 重新注入。0.5.0 因为从不清理，刷新同一网址时脚本被去重表挡掉、不再执行。
     */
    fun clearFrame(frameId: String) {
        val prefix = frameId + "|"
        keys.removeAll(keys.filter { it.startsWith(prefix) }.toSet())
    }

    /**
     * 撤销某 frame 在某个文档 URL 下的全部记录。
     *
     * 用于「URL 没变的主 frame 导航」（刷新）：此时不提前注入，改为把键还回去，
     * 让文档真正就绪后的主档重新注入。
     */
    fun releaseUrl(frameId: String, documentUrl: String) {
        val prefix = frameId + "|" + documentUrl + "|"
        keys.removeAll(keys.filter { it.startsWith(prefix) }.toSet())
    }

    /**
     * 撤销一条记录。
     *
     * 用于「注入实际落进了别的文档」的情况：此时脚本并没有跑在目标文档里，
     * 必须把键还回去，让文档真正就绪后的那一档重新注入，否则目标页面永远拿不到脚本。
     */
    fun release(frameId: String, documentUrl: String, scriptId: String): Boolean =
        keys.remove(frameId + "|" + documentUrl + "|" + scriptId)

    /** 丢弃全部记录（主 frame 开始新导航 = 整篇新文档）。 */
    fun clear() = keys.clear()
}
