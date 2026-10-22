package com.ridermonkey.browser

import com.intellij.ide.util.PropertiesComponent

/**
 * 摸鱼遮罩的全局开关（持久化在 IDE 配置里，默认开启）。
 *
 * 为什么是全局而不是每个标签页一份：遮罩是「别被看到」的安全网，
 * 逐个标签页配置只会让人怀疑「这个标签页到底开没开」；而且一个面板关掉、
 * 另一个还开着时，用户很难从界面上分辨。
 *
 * 为什么用 PropertiesComponent 而不是新写一个 PersistentStateComponent：
 * 只有一个布尔值，平台已提供键值存储；新建 service 只为存一个开关不划算。
 */
object DisguiseSettings {

    private const val KEY = "monkeyBrowser.disguiseEnabled"

    fun isEnabled(): Boolean = PropertiesComponent.getInstance().getBoolean(KEY, true)

    fun setEnabled(enabled: Boolean) {
        PropertiesComponent.getInstance().setValue(KEY, enabled, true)
    }
}
