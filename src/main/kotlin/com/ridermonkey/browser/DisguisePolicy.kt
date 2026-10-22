package com.ridermonkey.browser

/**
 * 摸鱼遮罩的纯逻辑：决定「现在该不该开始累计『指针在面板外』的时间」。
 *
 * 为什么不是一个布尔条件：遮罩有三个前置条件，缺一不可 ——
 * - 指针在面板外；
 * - 用户至少进过面板一次（否则切到这个标签页就会立刻被盖住，很莫名）；
 * - 当前没有右键菜单 / 弹出菜单正在使用（否则遮罩会浮在菜单背后，看起来像坏了）。
 *
 * 时间与状态由调用方持有（[BrowserPanel] 的定时器），本类只做判定，便于单测。
 */
object DisguisePolicy {

    /**
     * 指针离开面板多久后才开始遮。
     *
     * 「扫过去拿个东西」与「人已经走了」之间的分界：太短会闪，太长起不到遮的作用。
     *
     * 2026-10-21 由 800ms 调成 200ms：用户反馈等待太明显。配 [POLL_MS]=100ms，
     * 实际观感是 0.2～0.3 秒盖上；极短的扫出（<200ms）仍会被吃掉，不会闪。
     */
    const val DELAY_MS: Long = 200L

    /**
     * 轮询间隔。
     *
     * 不能用 Swing 的 mouseExited：指针进入子组件时它也会在父组件上触发，
     * 而 JCEF 的指针交互发生在 CEF 侧，Swing 根本收不到。
     *
     * 100ms：轮询间隔就是「发现指针离开」的固有延迟，250ms 时会占掉延迟预算的一半。
     * 再往下（如 50ms）收益不明显、EDT 上的坐标换算调用翻倍。
     */
    const val POLL_MS: Long = 100L

    /**
     * 是否允许开始/继续累计「在外时间」。
     *
     * @param pointerInside 指针当前是否在面板内
     * @param armed 指针是否至少进过面板一次
     * @param menuOpen 右键菜单 / 弹出菜单是否正在使用
     * @param enabled 全局开关是否打开（工具栏「伪装开/关」）
     */
    fun mayTrack(pointerInside: Boolean, armed: Boolean, menuOpen: Boolean, enabled: Boolean = true): Boolean =
        enabled && !pointerInside && armed && !menuOpen

    /** 从 [outsideSinceMs] 起算，到 [nowMs] 是否已经超过 [delayMs]。 */
    fun delayElapsed(outsideSinceMs: Long, nowMs: Long, delayMs: Long = DELAY_MS): Boolean =
        nowMs - outsideSinceMs >= delayMs
}
