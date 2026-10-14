package com.ridermonkey.userscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptVersionsTest {

    private fun newer(a: String, b: String) = assertTrue("期望 $a 比 $b 新", ScriptVersions.compare(a, b) > 0)
    private fun older(a: String, b: String) = assertTrue("期望 $a 比 $b 旧", ScriptVersions.compare(a, b) < 0)
    private fun same(a: String, b: String) = assertEquals("期望 $a 与 $b 等价", 0, ScriptVersions.compare(a, b))

    @Test
    fun identicalVersionsAreEqual() {
        same("1.0", "1.0")
        same("1.0.0", "1.0.0")
    }

    @Test
    fun trailingZeroSegmentCountsAsNewer() {
        // 简化取舍：1.0.0 视为比 1.0 新（与 KDoc 里「数字段 vs 空段 = 数字段更新」一致）。
        // 代价只是可能多提示一次「有更新」，不会漏掉更新 —— 这个方向的不对称是有意选的。
        newer("1.0.0", "1.0")
    }

    @Test
    fun numericSegmentsCompareNumericallyNotLexically() {
        // 这是引入本对象的原因：按字符串比 "1.10" < "1.9"，会得出相反结论。
        newer("1.10", "1.9")
        newer("1.2", "1.1")
        older("2.0", "10.0")
    }

    @Test
    fun extraNumericSegmentIsNewer() {
        newer("1.2.1", "1.2")
    }

    @Test
    fun prereleaseIsOlderThanRelease() {
        older("1.0-beta", "1.0")
        older("2.1-alpha", "2.1")
    }

    @Test
    fun separatorsAreInterchangeable() {
        same("1.0.0", "1-0_0")
    }

    @Test
    fun whitespaceIsIgnored() {
        same(" 1.2 ", "1.2")
    }

    @Test
    fun compareIsAntisymmetric() {
        assertTrue(ScriptVersions.compare("1.1", "1.0") > 0)
        assertTrue(ScriptVersions.compare("1.0", "1.1") < 0)
    }
}
