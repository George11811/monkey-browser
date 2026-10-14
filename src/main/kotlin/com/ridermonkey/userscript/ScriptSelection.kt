package com.ridermonkey.userscript

/**
 * 从脚本库里挑出某个文档该跑哪些脚本。
 *
 * 纯函数、无副作用，便于单元测试。真正决定「注入几次」的去重在 [InjectionRegistry]。
 */
object ScriptSelection {

    /**
     * @param isMainFrame 当前 frame 是否主 frame，用于处理 `@noframes`
     *
     * 规则：必须已启用；`@noframes` 的脚本只跑主 frame；URL 必须命中（见 [UserscriptMatcher]）。
     * 无 URL 规则的脚本一律不选（[UserscriptMatcher.matches] 已保证），调用方应另行提示用户。
     */
    fun select(entries: List<UserscriptEntry>, url: String, isMainFrame: Boolean): List<UserscriptEntry> =
        entries.filter { entry ->
            entry.enabled &&
                (!entry.metadata.noframes || isMainFrame) &&
                UserscriptMatcher.matches(entry.metadata, url)
        }
}
