package com.ridermonkey.userscript

/**
 * 一个已导入的用户脚本。
 *
 * `id` 用文件名（含 .user.js 后缀）——保证稳定、可读、天然去重；改名会改变 id 与启用状态。
 */
data class UserscriptEntry(
    val id: String,
    val displayName: String,
    val enabled: Boolean,
    val source: String,
    val metadata: UserscriptMetadata,
    val body: String,
    val filePath: String
)
