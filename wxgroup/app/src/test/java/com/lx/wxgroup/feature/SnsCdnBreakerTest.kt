package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnsCdnBreaker] 的熔断状态机回归测试。
 *
 * 逐条覆盖契约 1 的 8 行语义表，外加 limit / coolMs 边界。
 * 时间一律手动传入 `now`，不依赖系统时钟。
 */
class SnsCdnBreakerTest {

    // ---------------------------------------------------------------- 语义表逐条

    @Test
    fun hitReturnsFalseUntilLimitMinusOne() {
        val b = SnsCdnBreaker(limit = 8)
        for (i in 1..7) {
            assertFalse("第 $i 次 hit 不该触发", b.hit(i * 1000L))
        }
        assertFalse(b.blocked(7_000L))
        assertEquals(7, b.streak())
    }

    @Test
    fun limitThHitTriggers() {
        val b = SnsCdnBreaker(limit = 8)
        for (i in 1..7) b.hit(i * 1000L)
        assertTrue("第 8 次 hit 应刚好触发熔断", b.hit(8_000L))
        assertTrue(b.blocked(8_000L))
        assertTrue(b.rawTripped())
    }

    @Test
    fun hitAfterTrippedKeepsBlockedAndClampsStreak() {
        val b = SnsCdnBreaker(limit = 8)
        for (i in 1..8) b.hit(i * 1000L)
        // 熔断后再多次 hit：blocked 保持 true，streak 钳在 limit
        for (i in 9..100) b.hit(i * 1000L)
        assertTrue(b.blocked(100_000L))
        assertEquals(8, b.streak())
    }

    @Test
    fun blockedAutoResetsAfterCoolDown() {
        val b = SnsCdnBreaker(limit = 8, coolMs = 10_000L)
        for (i in 1..8) b.hit(i * 1000L)
        assertTrue(b.blocked(8_000L))
        // 冷却时长已过
        assertFalse(b.blocked(8_000L + 10_000L + 1L))
        assertFalse(b.rawTripped())
    }

    @Test
    fun boundaryEqualCoolMsStillBlocked() {
        val b = SnsCdnBreaker(limit = 8, coolMs = 10_000L)
        for (i in 1..8) b.hit(i * 1000L)
        // now - tripAt == coolMs 仍算熔断中（> 而非 >=）
        assertTrue(b.blocked(8_000L + 10_000L))
        assertTrue(b.rawTripped())
    }

    @Test
    fun okClearsStreak() {
        val b = SnsCdnBreaker(limit = 8)
        for (i in 1..5) b.hit(i * 1000L)
        assertEquals(5, b.streak())
        b.ok()
        assertEquals(0, b.streak())
    }

    @Test
    fun resetClearsAll() {
        val b = SnsCdnBreaker(limit = 8)
        for (i in 1..8) b.hit(i * 1000L)
        assertTrue(b.rawTripped())
        b.reset()
        assertFalse(b.blocked(8_000L))
        assertFalse(b.rawTripped())
        assertEquals(0, b.streak())
    }

    @Test
    fun hitTriggeringResetsStreak() {
        val b = SnsCdnBreaker(limit = 3, coolMs = 10_000L)
        for (i in 1..3) b.hit(i * 1000L)
        // 触发熔断瞬间 streak 被清零
        assertEquals(0, b.streak())
        // 冷却过后自动复位
        assertFalse(b.blocked(3_000L + 10_000L + 1L))
        assertFalse(b.rawTripped())
        assertEquals(0, b.streak())
        // 重新累计：需要满 limit 次才再触发
        assertFalse(b.hit(200_000L))
        assertFalse(b.hit(201_000L))
        assertTrue(b.hit(202_000L))
    }

    // ---------------------------------------------------------------- 边界

    @Test
    fun limitOneTriggersOnFirstHit() {
        val b = SnsCdnBreaker(limit = 1)
        assertTrue(b.hit(1_000L))
        assertTrue(b.blocked(1_000L))
    }

    @Test
    fun coolMsZeroResetsImmediately() {
        val b = SnsCdnBreaker(limit = 8, coolMs = 0L)
        for (i in 1..8) b.hit(i * 1000L)
        assertTrue(b.rawTripped())
        // coolMs = 0：now - tripAt > 0 即复位（now 稍大于 tripAt）
        assertFalse(b.blocked(8_001L))
        assertFalse(b.rawTripped())
    }

    @Test
    fun okDuringTrippedKeepsTripped() {
        val b = SnsCdnBreaker(limit = 8)
        for (i in 1..8) b.hit(i * 1000L)
        // 熔断中 ok 只清 streak，不解除熔断
        b.ok()
        assertTrue(b.blocked(8_000L))
        assertTrue(b.rawTripped())
    }
}
