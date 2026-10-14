package com.ridermonkey.userscript

import com.ridermonkey.util.JsonStrings

/**
 * `GM_info` 的内容（Tampermonkey 的脚本自省对象）。
 *
 * 只暴露元数据里真实存在的字段，不编造；`scriptHandler` 与 `version` 用于脚本判断运行环境。
 */
object GmInfo {

    /** 本插件的版本号，由构建脚本写入（见 build.gradle.kts 的 pluginVersion 常量）。 */
    const val SCRIPT_HANDLER: String = "Monkey Browser"

    /**
     * 本插件版本，用于 \`GM_info.version\`。
     *
     * 刻意写成常量而不是读 \`PluginManagerCore\`：后者是**内部 API**，会被 Plugin Verifier
     * 判为 INTERNAL_API_USAGES 而让构建失败。为避免版本漂移，构建里有一个
     * \`checkHandlerVersion\` 任务会拿它和 build.gradle.kts 的 version 比对，不一致就直接失败。
     */
    const val HANDLER_VERSION: String = "0.17.3"

    fun toJson(entry: UserscriptEntry, version: String = HANDLER_VERSION): String {
        val meta = entry.metadata
        val script = "{" +
            JsonStrings.quoted("name") + ":" + JsonStrings.quoted(meta.name ?: entry.id) + "," +
            JsonStrings.quoted("namespace") + ":" + JsonStrings.quoted(meta.namespace ?: "") + "," +
            JsonStrings.quoted("version") + ":" + JsonStrings.quoted(meta.version ?: "") + "," +
            JsonStrings.quoted("description") + ":" + JsonStrings.quoted(meta.description ?: "") + "," +
            JsonStrings.quoted("matches") + ":" + stringArray(meta.match) + "," +
            JsonStrings.quoted("includes") + ":" + stringArray(meta.include) + "," +
            JsonStrings.quoted("excludes") + ":" + stringArray(meta.exclude + meta.excludeMatch) + "," +
            JsonStrings.quoted("grants") + ":" + stringArray(meta.grant) + "," +
            JsonStrings.quoted("runAt") + ":" + JsonStrings.quoted(meta.rawRunAt ?: "document-end") +
            "}"
        return "{" +
            JsonStrings.quoted("script") + ":" + script + "," +
            JsonStrings.quoted("scriptHandler") + ":" + JsonStrings.quoted(SCRIPT_HANDLER) + "," +
            JsonStrings.quoted("version") + ":" + JsonStrings.quoted(version) +
            "}"
    }

    private fun stringArray(values: List<String>): String =
        "[" + values.joinToString(",") { JsonStrings.quoted(it) } + "]"
}