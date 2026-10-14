package com.ridermonkey.userscript

import com.ridermonkey.util.JsonStrings

/**
 * 加载并填充注入用的 GM API 运行时模板（资源文件 js/gm-api.js）。
 *
 * 所有占位符都在注入前替换；模板文件的头部注释列了完整清单。
 */
object GmRuntime {

    private const val RESOURCE = "/js/gm-api.js"

    private val template: String by lazy { loadTemplate() }

    /** 一次注入需要的全部内容。 */
    class Preamble(
        val scriptId: String,
        val frameId: String,
        val token: String,
        /** 注入时刻的存储快照：key -> 原始 JSON 值。 */
        val snapshot: Map<String, String>,
        /** 按 \`@grant\` 裁剪后的能力名。 */
        val grants: Set<String>,
        /** 已序列化的 \`GM_info\` JSON。 */
        val infoJson: String,
        /** 已缓存的文本资源：name -> 文本。 */
        val resources: Map<String, String>,
        /** 已缓存的资源 data URL：name -> data:...;base64,... */
        val resourceUrls: Map<String, String>
    )

    fun buildPreamble(preamble: Preamble): String =
        template
            .replace("__RM_SCRIPT_ID__", JsonStrings.escape(preamble.scriptId))
            .replace("__RM_FRAME_ID__", JsonStrings.escape(preamble.frameId))
            .replace("__RM_TOKEN__", JsonStrings.escape(preamble.token))
            .replace("__RM_SNAPSHOT__", JsonStrings.objectOfRawValues(preamble.snapshot))
            .replace("__RM_GRANTS__", stringArray(preamble.grants.toList()))
            .replace("__RM_INFO__", preamble.infoJson)
            .replace("__RM_RESOURCES__", objectOfQuoted(preamble.resources))
            .replace("__RM_RESOURCE_URLS__", objectOfQuoted(preamble.resourceUrls))

    /** 排序后拼成 JS 字符串数组，保证同样的能力集生成同样的代码（便于排查）。 */
    private fun stringArray(values: List<String>): String =
        "[" + values.sorted().joinToString(",") { JsonStrings.quoted(it) } + "]"

    private fun objectOfQuoted(values: Map<String, String>): String =
        "{" + values.entries.joinToString(",") {
            JsonStrings.quoted(it.key) + ":" + JsonStrings.quoted(it.value)
        } + "}"

    private fun loadTemplate(): String {
        val stream = GmRuntime::class.java.getResourceAsStream(RESOURCE)
            ?: error("缺少内置资源：" + RESOURCE)
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
