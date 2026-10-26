package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnsTimelineMerge] 的纯判据回归测试（R27 方案 A · Response-Merge）。
 *
 * ## 为什么必须有
 * 注入是把**服务端已经不返回**的动态塞回时间线响应列表。判据错了的后果分两种：
 * - **漏了**（该注入的没注入）⇒ 功能不生效，跟没做一样；
 * - **多了/重复**（已在响应里的又注一次）⇒ 时间线出现重复条目，且每轮同步都重复，
 *   用户一眼能看见，属于「做比不做更糟」。
 * 这两种都是真机上要下拉刷新 + 逐屏看界面才能发现的，代价一轮十几分钟 ⇒ 必须在 JVM 里锁死。
 *
 * ## 数据来源
 * - `pickMissing` 的输入口径：台账 `sns_visible_src` 里 `fixed_orig IS NOT NULL` 的行
 *   （= 被微信清过位、我们补回来的），响应列表里已有的要排除。
 * - `insertIndexDesc` 的降序假设：`zb5.f` 是时间线响应列表，按 `CreateTime` 倒序
 *   （新的在前），dex 取证见 `.tmp/design_a.md` §2.0。
 */
class SnsTimelineMergeTest {

    // ------------------------------------------------------------------ 候选挑选

    @Test
    fun picksOnlyMissingFromResponse() {
        val ledger = listOf(-1L, -2L, -3L)
        val resp = setOf(-2L)
        assertEquals(listOf(-1L, -3L), SnsTimelineMergeLogic.pickMissing(ledger, resp, 10))
    }

    @Test
    fun emptyWhenAllAlreadyInResponse() {
        val ledger = listOf(-1L, -2L)
        val resp = setOf(-1L, -2L)
        assertTrue(SnsTimelineMergeLogic.pickMissing(ledger, resp, 10).isEmpty())
    }

    @Test
    fun dedupsRepeatedLedgerIds() {
        // 台账同一 snsId 可能出现多行；重复必须只注入一次，否则时间线出现重影
        val ledger = listOf(-5L, -5L, -6L, -5L)
        assertEquals(listOf(-5L, -6L), SnsTimelineMergeLogic.pickMissing(ledger, emptySet(), 10))
    }

    @Test
    fun skipsZeroId() {
        // snsId=0 是「无效项」（`oh3/p.isInValid()` 判 die），绝不能注入
        val ledger = listOf(0L, -7L)
        assertEquals(listOf(-7L), SnsTimelineMergeLogic.pickMissing(ledger, emptySet(), 10))
    }

    @Test
    fun respectsLimit() {
        val ledger = listOf(-1L, -2L, -3L, -4L, -5L)
        assertEquals(2, SnsTimelineMergeLogic.pickMissing(ledger, emptySet(), 2).size)
        assertEquals(listOf(-1L, -2L), SnsTimelineMergeLogic.pickMissing(ledger, emptySet(), 2))
    }

    @Test
    fun limitZeroOrNegativeYieldsEmpty() {
        val ledger = listOf(-1L)
        assertTrue(SnsTimelineMergeLogic.pickMissing(ledger, emptySet(), 0).isEmpty())
        assertTrue(SnsTimelineMergeLogic.pickMissing(ledger, emptySet(), -1).isEmpty())
    }

    @Test
    fun emptyLedgerYieldsEmpty() {
        assertTrue(SnsTimelineMergeLogic.pickMissing(emptyList(), setOf(-1L), 10).isEmpty())
    }

    // ------------------------------------------------------------------ 插入位置

    @Test
    fun insertsAtHeadWhenNewest() {
        // 列表降序 [300, 200, 100]，注入 400 ⇒ 插到 0
        assertEquals(0, SnsTimelineMergeLogic.insertIndexDesc(listOf(300, 200, 100), 400))
    }

    @Test
    fun insertsInMiddleByCreateTime() {
        // 列表降序 [300, 100]，注入 200 ⇒ 插到 1（保持降序）
        assertEquals(1, SnsTimelineMergeLogic.insertIndexDesc(listOf(300, 100), 200))
        // 列表 [300, 200, 100]，注入 250 ⇒ 插到 1
        assertEquals(1, SnsTimelineMergeLogic.insertIndexDesc(listOf(300, 200, 100), 250))
    }

    @Test
    fun insertsAtTailWhenOldest() {
        assertEquals(3, SnsTimelineMergeLogic.insertIndexDesc(listOf(300, 200, 100), 50))
    }

    @Test
    fun insertsIntoEmptyList() {
        assertEquals(0, SnsTimelineMergeLogic.insertIndexDesc(emptyList(), 123))
    }

    @Test
    fun equalCreateTimeGoesAfterExisting() {
        // 时间相同不应插到同位置之前（避免把原条目挤到后面、看着像顺序抖动）
        assertEquals(1, SnsTimelineMergeLogic.insertIndexDesc(listOf(200), 200))
    }

    // ------------------------------------------------------------------ 组合（回归真实流程）

    @Test
    fun oneRoundKeepsOrderAndUniqueness() {
        // 台账 3 条待注入，响应里已有中间那条
        val ledger = listOf(-1L, -2L, -3L)
        val resp = setOf(-2L)
        val missing = SnsTimelineMergeLogic.pickMissing(ledger, resp, 12)
        assertEquals(listOf(-1L, -3L), missing)
        // 逐条注入时下标必须各自独立计算（不是都插同一个位置）
        val times = mutableListOf(300, 100)
        assertEquals(1, SnsTimelineMergeLogic.insertIndexDesc(times, 200))
        times.add(1, 200)
        assertEquals(0, SnsTimelineMergeLogic.insertIndexDesc(times, 400))
        times.add(0, 400)
        assertEquals(listOf(400, 300, 200, 100), times)
    }
}
