package com.ridermonkey.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisguisePolicyTest {

    @Test
    fun tracksOnlyWhenPointerIsOutsideAndArmed() {
        // 只有「在外 + 已武装 + 没有菜单」这一种组合允许累计时间。
        assertTrue(DisguisePolicy.mayTrack(pointerInside = false, armed = true, menuOpen = false))
        // 指针还在面板里：随时可能要用，不遮。
        assertFalse(DisguisePolicy.mayTrack(pointerInside = true, armed = true, menuOpen = false))
        // 从没进过面板（例如刚切到该标签页、指针还在标签栏）：不能一上来就盖住。
        assertFalse(DisguisePolicy.mayTrack(pointerInside = false, armed = false, menuOpen = false))
        // 右键菜单开着：指针必然在菜单上（面板外），此时遮会浮在菜单背后。
        assertFalse(DisguisePolicy.mayTrack(pointerInside = false, armed = true, menuOpen = true))
        assertFalse(DisguisePolicy.mayTrack(pointerInside = false, armed = false, menuOpen = true))
        // 开关关掉时，其它条件再满足也不该累计（工具栏「伪装关」）。
        assertFalse(DisguisePolicy.mayTrack(pointerInside = false, armed = true, menuOpen = false, enabled = false))
        assertFalse(DisguisePolicy.mayTrack(pointerInside = true, armed = true, menuOpen = false, enabled = false))
        assertTrue(DisguisePolicy.mayTrack(pointerInside = false, armed = true, menuOpen = false, enabled = true))
    }

    @Test
    fun delayHasToBeFullyElapsed() {
        // 默认 200ms：差 1 毫秒都不遮（避免拖着鼠标划过就闪一下）。
        assertFalse(DisguisePolicy.delayElapsed(outsideSinceMs = 1_000L, nowMs = 1_199L))
        assertTrue(DisguisePolicy.delayElapsed(outsideSinceMs = 1_000L, nowMs = 1_200L))
        assertTrue(DisguisePolicy.delayElapsed(outsideSinceMs = 1_000L, nowMs = 60_000L))
        // 时钟回拨（now < since）不能算「已经过完延迟」，否则会立刻遮住。
        assertFalse(DisguisePolicy.delayElapsed(outsideSinceMs = 2_000L, nowMs = 1_000L))
    }

    @Test
    fun pollIntervalStaysInAReasonableRange() {
        // 太小会白烧 EDT，太大「鼠标移回立即恢复」与「离开后尽快盖上」都不成立。
        assertTrue(DisguisePolicy.POLL_MS in 50L..250L)
        // 延迟必须明显大于一次轮询间隔，否则判定会在同一 tick 里抖动。
        assertTrue(DisguisePolicy.DELAY_MS > DisguisePolicy.POLL_MS)
    }
}
