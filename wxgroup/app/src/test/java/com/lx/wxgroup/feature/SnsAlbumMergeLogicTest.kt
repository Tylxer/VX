package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnsAlbumMergeLogic] 的回归测试（R30 Lane2 · 好友相册页显示被删动态）。
 *
 * ## 为什么必须有
 * 相册页注入比时间线多一道**按 wxid 过滤**的闸门（相册天然只显示 owner 的动态），
 * 判据错了的后果分两种：
 * - **漏注入**（ownerMatches 误判不匹配）⇒ 功能不生效，跟没做一样；
 * - **串号**（ownerMatches 误判匹配 / 不过滤）⇒ 把 A 的已删动态插进 B 的相册，
 *   非常显眼且难回滚。
 * 这些都只在真机进某个好友相册 + 逐屏看才能发现 ⇒ 在 JVM 里先锁死。
 *
 * ## 数据来源
 * - `pickForAlbum` 输入口径：`GroupDb.snsLedgerIds()` 的并集（归档 ∪ src 补位），
 *   响应列表已有的要排除（与时间线 `pickMissing` 同语义）。
 * - `insertIndexDesc` 的降序假设：`jc5.f` 是相册响应列表，按 `CreateTime` 倒序
 *   （新的在前），dex 取证见 `docs/_r30_lane2_album_page.md` §4。
 */
class SnsAlbumMergeLogicTest {

    // ------------------------------------------------------------------ 候选挑选

    @Test
    fun picksOnlyMissingFromResponse() {
        val ledger = listOf(-1L, -2L, -3L)
        val resp = setOf(-2L)
        assertEquals(listOf(-1L, -3L), SnsAlbumMergeLogic.pickForAlbum(ledger, resp, 10))
    }

    @Test
    fun emptyWhenAllAlreadyInResponse() {
        val ledger = listOf(-1L, -2L)
        val resp = setOf(-1L, -2L)
        assertTrue(SnsAlbumMergeLogic.pickForAlbum(ledger, resp, 10).isEmpty())
    }

    @Test
    fun dedupsRepeatedLedgerIds() {
        // 台账并集里同一 snsId 可能重复出现；重复注入会让相册出现重影
        val ledger = listOf(-5L, -5L, -6L, -5L)
        assertEquals(listOf(-5L, -6L), SnsAlbumMergeLogic.pickForAlbum(ledger, emptySet(), 10))
    }

    @Test
    fun skipsZeroId() {
        // snsId=0 是「无效项」（`oh3/p.isInValid()` 判 die），绝不能注入
        val ledger = listOf(0L, -7L)
        assertEquals(listOf(-7L), SnsAlbumMergeLogic.pickForAlbum(ledger, emptySet(), 10))
    }

    @Test
    fun respectsLimit() {
        val ledger = listOf(-1L, -2L, -3L, -4L, -5L)
        assertEquals(listOf(-1L, -2L), SnsAlbumMergeLogic.pickForAlbum(ledger, emptySet(), 2))
    }

    @Test
    fun limitZeroOrNegativeYieldsEmpty() {
        val ledger = listOf(-1L)
        assertTrue(SnsAlbumMergeLogic.pickForAlbum(ledger, emptySet(), 0).isEmpty())
        assertTrue(SnsAlbumMergeLogic.pickForAlbum(ledger, emptySet(), -1).isEmpty())
    }

    @Test
    fun emptyLedgerYieldsEmpty() {
        assertTrue(SnsAlbumMergeLogic.pickForAlbum(emptyList(), setOf(-1L), 10).isEmpty())
    }

    // ------------------------------------------------------------------ 作者闸门

    @Test
    fun matchesExactSameWxid() {
        assertTrue(SnsAlbumMergeLogic.ownerMatches("wxid_abc", "wxid_abc"))
    }

    @Test
    fun caseSensitiveNoMatch() {
        // ⛔ wxid 大小写有语义，绝不能忽略大小写
        assertFalse(SnsAlbumMergeLogic.ownerMatches("WxId_Abc", "wxid_abc"))
        assertFalse(SnsAlbumMergeLogic.ownerMatches("wxid_abc", "WXID_ABC"))
    }

    @Test
    fun trimsBothEndsBeforeCompare() {
        // 两端 trim：protobuf 字符串可能带杂散空白，不 trim 会漏注入
        assertTrue(SnsAlbumMergeLogic.ownerMatches("  wxid_abc  ", "wxid_abc"))
        assertTrue(SnsAlbumMergeLogic.ownerMatches("wxid_abc", "\twxid_abc\n"))
    }

    @Test
    fun whitespaceOnlyIsEmptyFalse() {
        // 全是空白的 username / target 视为空 ⇒ 不匹配（防「空值串号」）
        assertFalse(SnsAlbumMergeLogic.ownerMatches("   ", "wxid_abc"))
        assertFalse(SnsAlbumMergeLogic.ownerMatches("wxid_abc", "   "))
        assertFalse(SnsAlbumMergeLogic.ownerMatches("   ", "   "))
    }

    @Test
    fun nullAlwaysFalse() {
        assertFalse(SnsAlbumMergeLogic.ownerMatches(null, "wxid_abc"))
        assertFalse(SnsAlbumMergeLogic.ownerMatches("wxid_abc", null))
        assertFalse(SnsAlbumMergeLogic.ownerMatches(null, null))
    }

    @Test
    fun emptyStringAlwaysFalse() {
        assertFalse(SnsAlbumMergeLogic.ownerMatches("", "wxid_abc"))
        assertFalse(SnsAlbumMergeLogic.ownerMatches("wxid_abc", ""))
        assertFalse(SnsAlbumMergeLogic.ownerMatches("", ""))
    }

    @Test
    fun differentOwnerFalse() {
        assertFalse(SnsAlbumMergeLogic.ownerMatches("wxid_a", "wxid_b"))
    }

    // ------------------------------------------------------------------ 插入位置

    @Test
    fun insertsAtHeadWhenNewest() {
        // 列表降序 [300, 200, 100]，注入 400 ⇒ 插到 0
        assertEquals(0, SnsAlbumMergeLogic.insertIndexDesc(listOf(300, 200, 100), 400))
    }

    @Test
    fun insertsInMiddleByCreateTime() {
        // 列表降序 [300, 100]，注入 200 ⇒ 插到 1（保持降序）
        assertEquals(1, SnsAlbumMergeLogic.insertIndexDesc(listOf(300, 100), 200))
        assertEquals(1, SnsAlbumMergeLogic.insertIndexDesc(listOf(300, 200, 100), 250))
    }

    @Test
    fun insertsAtTailWhenOldest() {
        assertEquals(3, SnsAlbumMergeLogic.insertIndexDesc(listOf(300, 200, 100), 50))
    }

    @Test
    fun insertsIntoEmptyList() {
        assertEquals(0, SnsAlbumMergeLogic.insertIndexDesc(emptyList(), 123))
    }

    @Test
    fun equalCreateTimeGoesAfterExisting() {
        // 时间相同不应插到同位置之前（避免把原条目挤到后面、看着像顺序抖动）
        assertEquals(1, SnsAlbumMergeLogic.insertIndexDesc(listOf(200), 200))
    }

    // ------------------------------------------------------------------ 组合（回归真实流程）

    @Test
    fun oneRoundKeepsOrderAndUniqueness() {
        val ledger = listOf(-1L, -2L, -3L)
        val resp = setOf(-2L)
        val missing = SnsAlbumMergeLogic.pickForAlbum(ledger, resp, 12)
        assertEquals(listOf(-1L, -3L), missing)
        val times = mutableListOf(300, 100)
        assertEquals(1, SnsAlbumMergeLogic.insertIndexDesc(times, 200))
        times.add(1, 200)
        assertEquals(0, SnsAlbumMergeLogic.insertIndexDesc(times, 400))
        times.add(0, 400)
        assertEquals(listOf(400, 300, 200, 100), times)
    }
}
