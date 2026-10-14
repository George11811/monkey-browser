package com.ridermonkey.userscript

/**
 * `run-at` 声明的脚本注入时机。
 *
 * 与 Tampermonkey / Greasemonkey 的 document-start / document-end /
 * document-idle / document-body 一一对应。解析时遇到无法识别的值一律
 * 回落到 [RunAt.DOCUMENT_END]，原始文本仍保存在
 * [UserscriptMetadata.rawRunAt] 中以便排查。
 */
enum class RunAt {
    DOCUMENT_START,
    DOCUMENT_END,
    DOCUMENT_IDLE,
    DOCUMENT_BODY,
}

/**
 * 单个用户脚本的元数据（元数据块中的 `key value` 行）。
 *
 * 解析约定（由 [UserscriptParser] 保证，调用方可以依赖）：
 *
 * - 单值字段（[name] / [namespace] / [version] / [description] / [rawRunAt]）
 *   采用「首次出现优先」：重复声明时保留第一次的值，后续忽略。
 * - 多值字段（[match] / [include] / [exclude] / [excludeMatch] / [grant]）按出现顺序累加。
 * - 键名不区分大小写（`@Name` 等价于 `@name`）；[raw] 的键统一小写，值保持原文。
 * - [grant] 出现 `none`（不区分大小写）时会被规整为只含 `none` 的列表。
 *   `none` 不是某个 GM API 的名字，而是「不使用任何 GM API」的标记；
 *   调用方必须先判断 `grant == listOf("none")`，再决定是否注入 API。
 * - [noframes] 只表示「声明过 `noframes`」；该字段不参与 URL 匹配，
 *   由调用方在决定是否注入到 iframe 时自行处理。
 *
 * @property name `name`，脚本显示名。
 * @property namespace `namespace`。
 * @property version `version`。
 * @property description `description`。
 * @property match `match`，Chrome match pattern 列表。
 * @property include `include`，glob 或正则列表。
 * @property exclude `exclude`，命中即否决，写法同 [include]。
 * @property excludeMatch `exclude-match`，Chrome match pattern 语义，命中即否决。
 * @property grant `grant`，GM API 白名单；只含 `none` 表示不使用任何 API。
 * @property connect `connect`，`GM_xmlhttpRequest` 允许访问的主机白名单（`*` = 任意，`self` = 当前文档主机）。
 * @property runAt 解析后的注入时机，未知值回落 [RunAt.DOCUMENT_END]。
 * @property rawRunAt `run-at` 的原始文本；未声明该键时为 null。
 * @property noframes 是否声明了 `noframes`（只看键是否存在，不看值）。
 * @property raw 所有元数据行的原始值，键为小写键名、值为按出现顺序的列表。
 */
data class UserscriptMetadata(
    val name: String? = null,
    val namespace: String? = null,
    val version: String? = null,
    val description: String? = null,
    val match: List<String> = emptyList(),
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val excludeMatch: List<String> = emptyList(),
    val grant: List<String> = emptyList(),
    val connect: List<String> = emptyList(),
    /** `resource`，形如 `name url` 的原始条目（解析成 name -> url 见 [UserscriptMetadata.resourceMap]）。 */
    val resource: List<String> = emptyList(),
    val runAt: RunAt = RunAt.DOCUMENT_END,
    val rawRunAt: String? = null,
    val noframes: Boolean = false,
    val raw: Map<String, List<String>> = emptyMap(),
) {

    /**
     * `@updateURL`；未声明时为 null。
     *
     * 用 [raw] 取值而不是新增构造参数：解析器已经把**所有**元数据行按「小写键名 -> 值列表」存进 [raw]，
     * 单值字段的「首次出现优先」规则也在那里成立，这里只是给调用方一个有类型的入口。
     */
    val updateURL: String?
        get() = raw["updateurl"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** `@downloadURL`；未声明时回落到 [updateURL]（与 Tampermonkey 一致）。 */
    val downloadURL: String?
        get() = (raw["downloadurl"]?.firstOrNull() ?: updateURL)?.trim()?.takeIf { it.isNotEmpty() }

    /** `@resource` 的名称 -> URL；条目不合法（缺名称或 URL）时忽略。 */
    val resourceMap: Map<String, String>
        get() = resource.mapNotNull { line ->
            val text = line.trim()
            val space = text.indexOfFirst { it == ' ' || it == '\t' }
            if (space <= 0) return@mapNotNull null
            val name = text.substring(0, space).trim()
            val url = text.substring(space + 1).trim()
            if (name.isEmpty() || url.isEmpty()) null else name to url
        }.toMap()
}