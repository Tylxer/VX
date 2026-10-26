package com.lx.wxgroup.feature

import com.lx.wxgroup.feature.SnsScanCursor.Src
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R27 · 图片回填的**跨轮游标**判据回归测试。
 *
 * 治的病（R26 复核 §8(b)）：`backfillImages` 每轮从最新重扫、只补最新 200 条 ⇒
 * 「最新窗口一旦持续补不到，更旧但图还取得到的行永远轮不到」。修法是低水位游标：
 * 本轮扫到哪儿落盘，下轮从那儿继续，扫到底回绕。
 *
 * 写错了都**只在真机跑很多轮才暴露**，而且不崩、不报错：
 *
 * | 写错 | 真机症状 |
 * |---|---|
 * | 编码/解析丢负号（snsId 是有符号 64 位） | 游标永久偏到「最新」，等于没修 |
 * | `ct` 用 Int 解析 | 毫秒时间戳被截断 ⇒ 游标乱跳，每轮重扫或漏扫 |
 * | 脏值不返回 null 而是猜一个 | 从奇怪的位置开始扫，越补越乱 |
 * | 扫到底不回绕 | 到最旧之后每轮拿到空页，回填**永久停摆** |
 * | `seek` 不校验 `ct` | `ct=MAX_VALUE` 退化成「最新一页」，每轮原地重扫（正是本 bug） |
 *
 * ⛔ 纯 JVM：被测的两处（[MomentsLogic] 的编解码、[SnsScanCursor]）都刻意不碰 Android。
 */
class MomentsBackfillCursorTest {

    /** 真机 snsId（SnsGuard 头注释里那条，**负数**） */
    private val REAL_SNS = -3447438253133393222L

    /** 真机 createTime 量级（毫秒；> Int.MAX，专治 `toInt()` 截断） */
    private val REAL_CT = 1_770_000_000_000L

    // ------------------------------------------------------------------ 编解码

    @Test
    fun encodeParseRoundTripKeepsNegativeSidAndMillisCt() {
        val c = MomentsLogic.FillCursor(MomentsLogic.FILL_SRC_ARCHIVE, REAL_CT, REAL_SNS)
        val text = MomentsLogic.encodeFillCursor(c)
        assertEquals("1:$REAL_CT:$REAL_SNS", text)
        assertEquals(c, MomentsLogic.parseFillCursor(text))
    }

    @Test
    fun parseKeepsCtAsMillisNotTruncatedToInt() {
        // ⛔ 变异靶子：`ct` 改成 `toInt()` 后这里必红（1770000000000 装不进 Int）
        val c = MomentsLogic.parseFillCursor("0:$REAL_CT:$REAL_SNS")!!
        assertEquals(REAL_CT, c.ct)
    }

    @Test
    fun parseKeepsNegativeSid() {
        // ⛔ 变异靶子：`sid` 改成 `toInt()` / 按无符号解析后这里必红
        assertEquals(REAL_SNS, MomentsLogic.parseFillCursor("0:$REAL_CT:$REAL_SNS")!!.sid)
    }

    @Test
    fun parseBothSources() {
        assertEquals(MomentsLogic.FILL_SRC_CURRENT, MomentsLogic.parseFillCursor("0:1:2")!!.src)
        assertEquals(MomentsLogic.FILL_SRC_ARCHIVE, MomentsLogic.parseFillCursor("1:1:2")!!.src)
    }

    @Test
    fun parseRejectsDirtyValuesAsRestartFromNewest() {
        // 一律 null = 「从最新开始」，绝不抛异常、绝不猜一个位置
        val dirty = listOf(
            null, "", "   ",            // 没存过 / 被清空
            "C:1:2",                    // 源不是数字
            "2:1:2",                    // 源越界
            "-1:1:2",                   // 源越界（负数）
            "0:1",                      // 少一段
            "0:1:2:3",                  // 多一段
            "0::2",                     // ct 空
            "0:0:2",                    // ct = 0（脏值，createTime 恒正）
            "0:-5:2",                   // ct 负
            "0:1:abc",                  // sid 非数字
            "abc",                      // 整条乱码
            "0:1.5:2",                  // ct 是小数
        )
        for (raw in dirty) {
            assertNull("dirty=$raw", MomentsLogic.parseFillCursor(raw))
        }
    }

    // ------------------------------------------------------------------ 轮转闭合

    @Test
    fun afterRoundKeepsPositionWhenCutByQuota() {
        val last = MomentsLogic.FillCursor(MomentsLogic.FILL_SRC_CURRENT, REAL_CT, REAL_SNS)
        // 被配额截断 ⇒ 记住位置，下轮接着扫（这就是「真轮转」的一半）
        assertEquals(last, MomentsLogic.fillCursorAfterRound(exhausted = false, last = last))
    }

    @Test
    fun afterRoundWrapsToNewestWhenBothSourcesDrained() {
        val last = MomentsLogic.FillCursor(MomentsLogic.FILL_SRC_ARCHIVE, REAL_CT, REAL_SNS)
        // ⛔ 变异靶子：`exhausted` 取反 / 常返回 last ⇒ 这里红。不回绕 = 回填永久停摆
        assertNull(MomentsLogic.fillCursorAfterRound(exhausted = true, last = last))
    }

    @Test
    fun afterRoundToleratesMissingLast() {
        assertNull(MomentsLogic.fillCursorAfterRound(exhausted = true, last = null))
        assertNull(MomentsLogic.fillCursorAfterRound(exhausted = false, last = null))
    }

    // ------------------------------------------------------------------ 游标恢复

    @Test
    fun seekRestoresPositionAndSource() {
        val cur = SnsScanCursor()
        cur.seek(Src.ARCHIVE, REAL_CT, REAL_SNS)
        assertEquals(Src.ARCHIVE, cur.src)
        assertEquals(REAL_CT, cur.ct)
        assertEquals(REAL_SNS, cur.sid)
    }

    @Test
    fun seekIgnoresNonPositiveCt() {
        // ⛔ 变异靶子：去掉 `ct <= 0` 的守门 ⇒ 这里红（游标被脏值钉在原地）
        val cur = SnsScanCursor()
        cur.seek(Src.ARCHIVE, 0L, REAL_SNS)
        assertEquals(Src.CURRENT, cur.src)
        assertEquals(Long.MAX_VALUE, cur.ct)
        assertEquals(Long.MAX_VALUE, cur.sid)
    }

    @Test
    fun seekToArchiveDoesNotSwitchBackToCurrent() {
        // 恢复到归档位置后，归档扫完必须**结束本轮**（nextSource=false），
        // 否则会切回「当前源最新一页」⇒ 本轮重扫最新窗口，轮转断在这里。
        val cur = SnsScanCursor()
        cur.seek(Src.ARCHIVE, REAL_CT, REAL_SNS)
        assertFalse(cur.nextSource())
        assertEquals(Src.ARCHIVE, cur.src)
    }

    @Test
    fun seekToCurrentStillSwitchesToArchiveFromItsNewest() {
        // 从当前源的中段恢复 ⇒ 切源时归档仍要**从最新**开始（不能把 ct 带过去，否则归档大半漏扫）
        val cur = SnsScanCursor()
        cur.seek(Src.CURRENT, REAL_CT, REAL_SNS)
        assertTrue(cur.nextSource())
        assertEquals(Src.ARCHIVE, cur.src)
        assertEquals(Long.MAX_VALUE, cur.ct)
        assertEquals(Long.MAX_VALUE, cur.sid)
    }
}
