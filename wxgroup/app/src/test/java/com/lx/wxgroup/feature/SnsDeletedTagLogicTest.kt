package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnsDeletedTagLogic] 的回归测试（R30 §6.1「时间行追加 `[删除已拦截]`」）。
 *
 * ## 为什么必须有
 * 标记是追加在一个**复用**的 `TextView` 上的。判据错了会出两类界面故障：
 * - **重复**（hook 重入 / 再次 bind 同一项）⇒ `[删除已拦截] [删除已拦截]`；
 * - **串味**（复用残留）⇒ 普通动态被标成已删，看起来像「防删除功能乱标」。
 * 两者都只在**滚动之后**出现，真机复现代价高 ⇒ 在 JVM 里先锁死。
 */
class SnsDeletedTagLogicTest {

    // ---------------------------------------------------------------- 文案追加

    @Test
    fun appendsMarkAfterNormalText() {
        assertEquals("10分钟前 [删除已拦截]", SnsDeletedTagLogic.appendTag("10分钟前"))
    }

    @Test
    fun appendsMarkAfterDayText() {
        assertEquals("昨天 [删除已拦截]", SnsDeletedTagLogic.appendTag("昨天"))
    }

    /** 微信自己的文案尾部可能带空格（不同版本/不同语言）⇒ 不能拼出双空格 */
    @Test
    fun trimsTrailingSpaceBeforeAppending() {
        assertEquals("昨天 [删除已拦截]", SnsDeletedTagLogic.appendTag("昨天   "))
    }

    /** 空文案：只留标记，且**不能有前导空格**（否则 TextView 里会看到缩进） */
    @Test
    fun emptyBecomesMarkOnly() {
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag(""))
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag("   "))
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag(null))
    }

    @Test
    fun nullHasNoLeadingSpace() {
        assertFalse(SnsDeletedTagLogic.appendTag(null).startsWith(" "))
    }

    // ------------------------------------------------------------------- 幂等

    /** ⛔ 防 hook 重入：第二次追加必须原样返回 */
    @Test
    fun isIdempotentWhenAlreadyTagged() {
        assertEquals(
            "10分钟前 [删除已拦截]",
            SnsDeletedTagLogic.appendTag("10分钟前 [删除已拦截]"),
        )
    }

    /** 连调两次 == 调一次（把「先追加再被重入」这个真实序列走一遍） */
    @Test
    fun appendTwiceEqualsAppendOnce() {
        val once = SnsDeletedTagLogic.appendTag("3分钟前")
        val twice = SnsDeletedTagLogic.appendTag(once)
        assertEquals(once, twice)
        assertEquals(1, Regex(Regex.escape(SnsDeletedTagLogic.MARK)).findAll(twice).count())
    }

    /** 用户内容里恰好含同样文字也不该被当成"已经标过"以外的特殊处理 —— 幂等优先 */
    @Test
    fun taggedInputWithTrailingSpaceStillIdempotent() {
        assertEquals(
            "昨天 [删除已拦截]",
            SnsDeletedTagLogic.appendTag("昨天 [删除已拦截]   "),
        )
    }

    // --------------------------------------------------------------- snsId 集合

    @Test
    fun taggedIdsDropsZeroAndDuplicates() {
        val set = SnsDeletedTagLogic.taggedIds(listOf(0L, 11L, 11L, 22L, 0L))
        assertEquals(setOf(11L, 22L), set)
    }

    @Test
    fun taggedIdsOnEmptyInput() {
        assertTrue(SnsDeletedTagLogic.taggedIds(emptyList()).isEmpty())
    }

    /** `snsId=0` 是无效项，永远不标（与 `pickMissing` 同口径） */
    @Test
    fun zeroSnsIdIsNeverTagged() {
        assertFalse(SnsDeletedTagLogic.isTagged(0L, setOf(0L, 11L)))
    }

    @Test
    fun isTaggedBySetMembership() {
        val tagged = SnsDeletedTagLogic.taggedIds(listOf(11L))
        assertTrue(SnsDeletedTagLogic.isTagged(11L, tagged))
        assertFalse(SnsDeletedTagLogic.isTagged(12L, tagged))
        assertFalse(SnsDeletedTagLogic.isTagged(11L, emptySet()))
    }
}
