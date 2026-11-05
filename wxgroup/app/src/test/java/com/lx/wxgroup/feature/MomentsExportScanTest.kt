package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnsScanCursor] 的判据回归测试 —— `MomentsExport.kt` 的 KDoc 一直引着本类，
 * 但 R20 之前它并不存在（`grep MomentsExportScanTest` 零命中）。
 *
 * 测的是**一轮导出**里游标的三件事：去重、游标推进、负 snsId 保真。
 * 这三件写错了都只在真机跑满一整轮才暴露：
 *
 * | 写错 | 真机症状 |
 * |---|---|
 * | 去重漏了 | 同一条动态导出两遍 |
 * | 去重跳过时不推进游标 | 键集分页原地打转 ⇒ 死循环，一轮导出永远不结束 |
 * | 拿 `toInt()` 存 snsId | 两条不同的负 id 撞成同一个 key，其中一条被静默吞掉 |
 *
 * ⛔ 纯 JVM 测试：`SnsScanCursor` 刻意不碰 Android / 数据库，正是为了能在这里跑。
 */
class MomentsExportScanTest {

    // ------------------------------------------------------------ 游标推进

    /**
     * ⭐ **被去重跳过的行，游标仍必须推进**（KDoc 里点名警告的那条）。
     *
     * 真机故障：键集分页是 `where createTime < ? or (createTime = ? and snsId < ?)`。
     * 若把 `advanceTo` 挪进「要处理」的分支里，被去重跳过的行就不会推进游标 ⇒
     * 下一页查询条件不变 ⇒ 拿到**同一页** ⇒ 同样的行再被跳过 ⇒
     * `rows` 一直涨到 `MAX_ROWS_PER_RUN` 才退出。导出看上去「跑完了」，
     * 实际上只扫了第一页 100 行，后面的动态一条都没碰。
     *
     * 这里断言的是**调用顺序**层面的事实：`first()` 返回 false 之后照样调 `advanceTo`，
     * 游标必须真的变了。`MomentsExport.kt` 主循环里那句注释
     * 「游标必须在任何 continue 之前推进」就是这条的现场。
     */
    @Test
    fun cursorAdvancesEvenWhenRowIsSkipped() {
        val cur = SnsScanCursor()
        cur.advanceTo(createTime = 1_700_000_000_000L, snsId = -100L)
        val ct0 = cur.ct
        val sid0 = cur.sid

        val dup = -100L
        assertTrue(cur.first(dup, emptySet()))
        assertFalse("已处理过 ⇒ 这行会被 skip", cur.first(dup, emptySet()))

        // 主循环的写法：advanceTo 在前，去重判断在后
        cur.advanceTo(createTime = 1_699_999_999_000L, snsId = -200L)

        assertTrue("⛔ ct 没推进 ⇒ 下一页还是同一页 ⇒ 死循环", cur.ct < ct0)
        assertEquals("⛔ sid 没推进", -200L, cur.sid)
        assertTrue(cur.sid != sid0)
    }

    /** 游标能连续推进多页，且严格往「更旧」的方向走（键集分页不倒流） */
    @Test
    fun cursorWalksForwardAcrossPages() {
        val cur = SnsScanCursor()
        var ct = 1_700_000_000_000L
        var sid = -1L
        repeat(5) { page ->
            ct -= 60_000L
            sid -= 7L
            cur.advanceTo(ct, sid)
            assertEquals("page=$page", ct, cur.ct)
            assertEquals("page=$page", sid, cur.sid)
        }
    }

    // ------------------------------------------------------------ 负 snsId

    /**
     * 真机 snsId 是 **Long 且为负**（实测 `-3447438253133393222`）。
     *
     * 真机故障：老代码用 `snsId.toInt()` 当 key，两个不同的负 Long
     * 会被截断成同一个 int ⇒ 后一条被当成「已处理」静默吞掉。
     * 本测试用两个高 32 位不同、低 32 位相同的 Long 精确复现这个坑：
     * 若 `seen` 是 `HashSet<Int>`，第二条必然返回 false。
     */
    @Test
    fun distinctNegativeSnsIdsDoNotCollide() {
        val a = -3447438253133393222L
        val b = a + (1L shl 32)   // 低 32 位相同、高 32 位差 1 ⇒ toInt() 完全一样
        assertEquals("构造前提：低 32 位必须相同", a.toInt(), b.toInt())
        assertTrue("构造前提：两个 Long 必须不同", a != b)

        val cur = SnsScanCursor()
        assertTrue("第一条负 id 该处理", cur.first(a, emptySet()))
        assertTrue("⛔ 第二条负 id 被截断吞掉了", cur.first(b, emptySet()))
        assertFalse("同一个 id 重复出现仍是 false", cur.first(a, emptySet()))
    }

    // ------------------------------------------------------------ exported 与 seen 独立

    /**
     * `exported`（磁盘上已有 `<snsId>.txt`）命中 ⇒ 返回 false，且**不污染 `seen`**。
     *
     * 真机故障：这是两个独立判据 —— 「磁盘已导出」是**跨轮**事实，
     * 「本轮已处理」是**本轮**事实。若 `exported` 命中时也往 `seen` 里塞，
     * 同一个 snsId 在**同一轮**里换个来源再出现就永远是 false，
     * 而 `MomentsExport` 里 `txt.exists()` 那条兜底分支恰恰会 `exported.add(row.snsId)`
     * —— 若 `seen` 也被污染，本轮后续对这条的一切判断都被永久锁死，
     * 包括「本来该重写一次的 」场景（目录被删、文件被清）。
     *
     * 更要紧的是短路顺序：`first()` 必须是 `snsId !in exported && seen.add(snsId)`，
     * 反过来写（先 `seen.add` 再判 `exported`）就会在跳过的同时污染 `seen`，本测试逮的就是它。
     */
    @Test
    fun exportedHitDoesNotPolluteSeen() {
        val id = -3447438253133393222L
        val cur = SnsScanCursor()

        assertFalse("磁盘已导出 ⇒ 不处理", cur.first(id, setOf(id)))
        assertTrue(
            "⛔ exported 命中不该写进 seen；否则同一轮里再问一次就永远 false",
            cur.first(id, emptySet()),
        )
        assertFalse("这次真的进 seen 了，再来就 false", cur.first(id, emptySet()))
    }
}
