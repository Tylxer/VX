package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnsTimelineSrcLogic] 的判据回归测试（R27-3 srcfix）。
 *
 * ## 为什么必须有
 * 补位是**往微信的 `SnsInfo` 里写 sourceType**，判据错了不是「功能不生效」而是
 * **把用户的私密动态推到主时间线**（`src=4 → 6`，隐私事故），或者把对方早就删掉的动态
 * 凭空顶上时间线（`src=0 → 2`，行还在就显示）。真机一轮要下拉刷新 + 看界面，
 * 日志还会被微信自己的 DB 日志冲掉 ⇒ 判据必须在 JVM 里锁死。
 *
 * ## 数据来源
 * `sourceType` 的取值集合来自 `docs/_r27_srcfix_design.md`（dex 取证）：
 * 时间线查询 `& 2`、私密 `4`、广告 `128`、微信清位 `& -3`；
 * 21:20 干净对照实验的真实样本：好友被删动态 `2 → 0`、自己的私密 `4`、广告位 `130`。
 */
class SnsTimelineSrcLogicTest {

    // ------------------------------------------------------------------ 台账准入

    @Test
    fun timelineBitIsTwo() {
        assertEquals(2, SnsTimelineSrcLogic.TIMELINE_BIT)
        assertEquals(4, SnsTimelineSrcLogic.PRIVATE_BIT)
    }

    @Test
    fun admitsOnlyRowsThatAreCurrentlyVisibleOnTimeline() {
        // 真机见过的值：2 = 好友公开动态；6 = 2|4（既在时间线又在私密位，微信清位后落 4）
        assertTrue(SnsTimelineSrcLogic.admit(1L, 2))
        assertTrue(SnsTimelineSrcLogic.admit(-3405809214430834153L, 2))
        assertTrue(SnsTimelineSrcLogic.admit(1L, 6))
        // 130 = 128|2（广告 + 时间线）：它本来就在时间线里显示，可以进台账
        assertTrue(SnsTimelineSrcLogic.admit(1L, 130))
    }

    @Test
    fun neverAdmitsPrivateOrDeadOrAdOnlyRows() {
        // 4 = 自己发的私密 —— 进不了台账就永远补不到（结构性硬保证）
        assertFalse(SnsTimelineSrcLogic.admit(1L, 4))
        // 0 = 死来源（从没可见过）
        assertFalse(SnsTimelineSrcLogic.admit(1L, 0))
        // 128 = 纯广告，走的是另一条查询通道（& 128），不是时间线
        assertFalse(SnsTimelineSrcLogic.admit(1L, 128))
        // 8 = 实测出现过的非时间线值（没有 bit1）
        assertFalse(SnsTimelineSrcLogic.admit(1L, 8))
    }

    @Test
    fun admitsCompositeRowThatCarriesTimelineBit() {
        // 10 = 8|2：虽然用途未定死，但它**带 bit1** ⇒ 时间线查询本来就会命中它，
        // 所以它进台账是对的（不让它进，反而会在被清位后救不回来）
        assertTrue(SnsTimelineSrcLogic.admit(1L, 10))
    }

    @Test
    fun snsIdZeroIsNeverAdmitted() {
        // snsId=0 是「这一列没读出来」的哨兵值（真机 snsId 可能为负，只能判 0）
        assertFalse(SnsTimelineSrcLogic.admit(0L, 2))
    }

    // ------------------------------------------------------------------ 补位判据

    @Test
    fun fixesOnlyWhenLedgerSaysEverVisible() {
        // 台账有记录 + 现在被清成 0 ⇒ 补
        assertTrue(SnsTimelineSrcLogic.shouldFix(current = 0, everVisible = true))
        // 台账没记录 + 现在是 0 ⇒ **绝不补**（从没可见过的 src=0，补了等于凭空造动态）
        assertFalse(SnsTimelineSrcLogic.shouldFix(current = 0, everVisible = false))
    }

    @Test
    fun neverFixesPrivateEvenIfLedgerSaysVisible() {
        // 第二道保险：台账被写脏 / 微信自己把行改成私密（4），一律跳过
        assertFalse(SnsTimelineSrcLogic.shouldFix(current = 4, everVisible = true))
        // 6 = 私密 + 时间线：私密位在，不接手
        assertFalse(SnsTimelineSrcLogic.shouldFix(current = 6, everVisible = true))
    }

    @Test
    fun doesNotRewriteRowsThatAreAlreadyVisible() {
        // 幂等：已经在时间线里就什么都不做（多写一次 = 多一次无意义的微信库写入）
        assertFalse(SnsTimelineSrcLogic.shouldFix(current = 2, everVisible = true))
        assertFalse(SnsTimelineSrcLogic.shouldFix(current = 130, everVisible = true))
    }

    @Test
    fun albumFixIsScopedToLedgerNotToAllRows() {
        // ⛔ 2026-10-27 真机事故：一度把 `shouldFix` 改成「缺 bit8 也补」，
        // 而库里有 17.6 万条正常的 sourceType=2 —— 一夜之间给几百条正常动态补了相册位，
        // 它们全部涌进好友个人相册。⇒ **时间线补位判据只认 bit1**，与相册位彻底解耦。
        assertFalse(SnsTimelineSrcLogic.shouldFix(current = 2, everVisible = true))
        // 相册位补位是**独立的窄口径**判据，只对「被删又救回」台账调用：
        assertTrue(SnsTimelineSrcLogic.shouldFixAlbum(current = 2, everVisible = true))
        assertFalse(SnsTimelineSrcLogic.shouldFixAlbum(current = 10, everVisible = true)) // 幂等
        assertFalse(SnsTimelineSrcLogic.shouldFixAlbum(current = 6, everVisible = true)) // 私密位不接手
        assertFalse(SnsTimelineSrcLogic.shouldFixAlbum(current = 2, everVisible = false))
    }

    @Test
    fun fixesAdRowThatLostTimelineBit() {
        // 130(128|2) 被清成 128：台账里有 130 ⇒ 应当补回 130（维持用户原本看得见的状态）
        assertTrue(SnsTimelineSrcLogic.shouldFix(current = 128, everVisible = true))
    }

    // ------------------------------------------------------------------ 目标值

    @Test
    fun fixTargetSetsTimelineAndAlbumBits() {
        // R30：补位 = bit1(时间线) + bit3(相册) ⇒ 0 -> 10
        assertEquals(10, SnsTimelineSrcLogic.fixTarget(0))
        // 幂等
        assertEquals(10, SnsTimelineSrcLogic.fixTarget(10))
        // 微信只清 bit1 的情形：8(相册) -> 10
        assertEquals(10, SnsTimelineSrcLogic.fixTarget(8))
        // 其它位一个都不动（折叠位 128 必须留着）
        assertEquals(138, SnsTimelineSrcLogic.fixTarget(128))
        // 私密位在时 shouldFix 已先挡掉，这里只验证不误清
        assertEquals(14, SnsTimelineSrcLogic.fixTarget(4))
    }

    @Test
    fun fixTargetValueIsInAlbumWhitelist() {
        // 真机抓到的相册白名单片段（含 10 / 14 / 12 / 138 / 12+256 等）
        val albumWhitelist = setOf(8, 10, 12, 14, 24, 26, 28, 30, 72, 74, 76, 78, 136, 138, 266, 264, 268, 270)
        for (cur in listOf(0, 2, 8, 10, 16, 64, 128, 130, 256)) {
            val fixed = SnsTimelineSrcLogic.fixTarget(cur)
            assertTrue(
                "fixTarget($cur)=$fixed 不在相册白名单里",
                fixed in albumWhitelist,
            )
            // 同时必须仍然通过时间线门槛
            assertTrue("fixTarget($cur)=$fixed 丢了时间线位", (fixed and 2) != 0)
        }
    }

    // ------------------------------------------------------------------ 回滚

    @Test
    fun rollbackRestoresRecordedOriginal() {
        // 补位前是 0 ⇒ 还原成 0（等价于微信自己的 & -3）
        assertEquals(0, SnsTimelineSrcLogic.rollbackTarget(current = 2, orig = 0))
        // 130 被清成 128 后我们补回 130 ⇒ 回滚要回到 128，**不能**回到 0
        assertEquals(128, SnsTimelineSrcLogic.rollbackTarget(current = 130, orig = 128))
    }

    @Test
    fun rollbackFallsBackToClearingBitWhenOriginalIsMissing() {
        assertEquals(0, SnsTimelineSrcLogic.rollbackTarget(current = 2, orig = null))
        assertEquals(128, SnsTimelineSrcLogic.rollbackTarget(current = 130, orig = null))
    }

    @Test
    fun rollbackIgnoresDirtyOriginalThatStillHasTimelineBit() {
        // 脏台账（orig 自带 bit1）：照它写回去等于没回滚 ⇒ 必须退化成只清 bit1
        assertEquals(0, SnsTimelineSrcLogic.rollbackTarget(current = 2, orig = 2))
        assertEquals(128, SnsTimelineSrcLogic.rollbackTarget(current = 130, orig = 130))
    }

    // ------------------------------------------------------------------ 台账解释

    @Test
    fun ledgerInterpretationMatchesAdmission() {
        // 台账里存的是「最近一次可见时的 sourceType」⇒ 它自己一定带 bit1
        assertTrue(SnsTimelineSrcLogic.everVisible(2))
        assertTrue(SnsTimelineSrcLogic.everVisible(130))
        assertFalse(SnsTimelineSrcLogic.everVisible(4))
        assertFalse(SnsTimelineSrcLogic.everVisible(0))
    }

    // ------------------------------------------------------------------ 限流与规模

    @Test
    fun detailLogBudgetIsFifty() {
        // 微信 logcat / LogX 落盘都是 5000 行环形缓冲：明细超 50 行就会挤掉关键判据
        assertEquals(50, SnsTimelineSrcLogic.MAX_DETAIL_LOGS)
        // 单页 ≤100（与 SnsGuard.listCurrentSns 的 200 上限口径不冲突）
        assertTrue(SnsTimelineSrcLogic.PAGE_SIZE in 1..100)
        // 单轮补位有界：一轮不能把整本台账都写了
        assertTrue(SnsTimelineSrcLogic.MAX_FIX_ROWS in 1..2000)
    }

    // ------------------------------------------------------------------ 方案 B：清位 SQL 识别

    @Test
    fun detectsWechatClearSourceSql() {
        // 真机 dex 里 d2.S7 拼出来的原文（大小写/空格照抄）
        assertTrue(
            SnsTimelineSrcLogic.isSourceClearSql(
                "UPDATE SnsInfo SET sourceType = sourceType & -3 WHERE (sourceType & 2 != 0) AND (snsId != 0)"
            )
        )
        // 微信换写法时也得认：无空格 / 带括号 / 全小写
        assertTrue(
            SnsTimelineSrcLogic.isSourceClearSql(
                "update SnsInfo set sourceType=sourceType&-3 where (sourceType&2)!=0"
            )
        )
        assertTrue(
            SnsTimelineSrcLogic.isSourceClearSql(
                "UPDATE SnsInfo SET sourceType = (sourceType & (-3)) WHERE snsId = ?"
            )
        )
    }

    @Test
    fun rejectsOwnFixSql() {
        // ⛔ 最关键的一条：我们的补位 SQL 绝不能被认成「清位」，
        // 否则 execSQL 的 after 钩子会自己触发自己 —— 无限递归把同步线程挂死。
        assertFalse(
            SnsTimelineSrcLogic.isSourceClearSql(
                "update SnsInfo set sourceType = sourceType | 2 " +
                    "where snsId in (-1,-2) and (sourceType & 2) = 0 and (sourceType & 4) = 0"
            )
        )
        // 其它表的写、其它 SQL 一律不认
        assertFalse(SnsTimelineSrcLogic.isSourceClearSql("delete from SnsInfo where snsId = ?"))
        assertFalse(SnsTimelineSrcLogic.isSourceClearSql("update SnsComment set isSend = 0 where snsID = ?"))
        assertFalse(SnsTimelineSrcLogic.isSourceClearSql(null))
        assertFalse(SnsTimelineSrcLogic.isSourceClearSql("   "))
    }

    @Test
    fun flagsUnmatchedSourceWriteForSampling() {
        // 形态变了（比如换成参数化/位运算常量）⇒ 必须能被采到，否则补位静默失效
        val odd = "UPDATE SnsInfo SET sourceType = ? WHERE snsId = ?"
        assertFalse(SnsTimelineSrcLogic.isSourceClearSql(odd))
        assertTrue(SnsTimelineSrcLogic.looksLikeSourceWrite(odd))
        // 我们的补位 SQL 也算「改 sourceType」，但采样只在没认出来时才打，不会重复
        assertTrue(
            SnsTimelineSrcLogic.looksLikeSourceWrite(
                "update SnsInfo set sourceType = sourceType | 2 where snsId in (1)"
            )
        )
        assertFalse(SnsTimelineSrcLogic.looksLikeSourceWrite("delete from SnsInfo where snsId = ?"))
    }

    @Test
    fun ownFixSqlIsRecognisedSoItDoesNotPolluteSampling() {
        // 我们补位/回滚写的就是这一条：必须能和「微信清位」区分开，
        // 否则 after 钩子会把自家 SQL 当成「未知写法」采样，日志被自己刷满
        assertTrue(
            SnsTimelineSrcLogic.isOwnFixSql(
                "update SnsInfo set sourceType = sourceType | 2 where snsId in (1,2)"
            )
        )
        assertTrue(
            SnsTimelineSrcLogic.isOwnFixSql(
                "update SnsInfo set sourceType = (sourceType | (2)) where snsId = ?"
            )
        )
        assertFalse(SnsTimelineSrcLogic.isOwnFixSql("UPDATE SnsInfo SET sourceType = sourceType & -3"))
        assertFalse(SnsTimelineSrcLogic.isOwnFixSql(null))
    }
}
