package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnsRestoreLogic] 的判据回归测试。
 *
 * ## 为什么必须有这个
 * 反插是**往微信的库里写数据**，判据错了不是"功能不生效"，是**把微信的 SnsInfo 写坏**
 * （多一行 ID 对不上的、可见位写反的），而写坏了在界面上未必立刻看得出来。
 * 真机一轮 ≈2 分钟且日志会被微信自己的 DB 日志冲掉，所以判据必须在 JVM 里锁住。
 *
 * ## 数据来源
 * 列清单来自 `SnsGuard.readRow`（真机逐列 getColumnIndex 读出来又归档的，实证）；
 * snsId / localFlag 取自真机抓到的真实值（docs/取证结论.md 朋友圈章节：
 * 删动态时 localFlag 落成 2，补可见位后是 18；snsId 是有符号 Long）。
 */
class SnsRestoreLogicTest {

    // ------------------------------------------------------------------ 真实样本

    /** 真机归档里的一条（id 是实测值，attrBuf 长度落在实测区间 235B~1069B 内） */
    private fun realRow(
        snsId: Long = -3447438253133393222L,
        localFlag: Int = 2,
    ): SnsRestoreLogic.ArchiveRow = SnsRestoreLogic.ArchiveRow(
        snsId = snsId,
        userName = "wxid_real_0f1a2b3c",
        createTime = 1760500000L,
        type = 1,
        sourceType = 1,
        localFlag = localFlag,
        subType = 0,
        serverExtFlag = 0,
        head = "http://wx.qlogo.cn/mmhead/realhead/0",
        stringSeq = "1379*****",
        content = ByteArray(512) { it.toByte() },
        attrBuf = ByteArray(235) { it.toByte() },
        postBuf = null,
    )

    // ------------------------------------------------------------------ 列白名单

    @Test
    fun knownColumnsAreTheThirteenArchivedOnes() {
        assertEquals(13, SnsRestoreLogic.KNOWN.size)
        assertEquals(13, SnsRestoreLogic.columns().size)
        // 归档行有值的字段，一个不多一个不少
        assertEquals(
            listOf(
                "snsId", "userName", "createTime", "type", "sourceType", "localFlag",
                "subType", "serverExtFlag", "head", "stringSeq", "content", "attrBuf", "postBuf",
            ),
            SnsRestoreLogic.columns(),
        )
    }

    @Test
    fun columnWhitelistRejectsGuessedNames() {
        for (c in SnsRestoreLogic.columns()) assertTrue(SnsRestoreLogic.isKnown(c))
        assertFalse(SnsRestoreLogic.isKnown("snsID")) // 评论表叫 snsID，动态表不叫
        assertFalse(SnsRestoreLogic.isKnown("localflag")) // 大小写敏感，别想当然
        assertFalse(SnsRestoreLogic.isKnown(""))
    }

    /** SnsInfo 实测 18 列，我们只写 13 列 → 5 列靠默认值（这就是那个已知风险） */
    @Test
    fun unknownColumnCountMatchesForensics() {
        assertEquals(18, SnsRestoreLogic.TOTAL_COLUMNS)
        assertEquals(5, SnsRestoreLogic.unknownColumnCount(SnsRestoreLogic.TOTAL_COLUMNS))
        assertEquals(0, SnsRestoreLogic.unknownColumnCount(13))
        // 表里列数比白名单还少（schema 变了）不能算出负数
        assertEquals(0, SnsRestoreLogic.unknownColumnCount(9))
    }

    /** 真机 schema 必须与白名单对得上：少一列就写不进去，必须停下来 */
    @Test
    fun missingColumnsDetectsFatalSchemaDrift() {
        val actual = SnsRestoreLogic.columns() + listOf("colA", "colB", "colC", "colD", "colE")
        assertEquals(emptyList<String>(), SnsRestoreLogic.missingColumns(actual))
        assertEquals(
            listOf("MISSING attrBuf"),
            SnsRestoreLogic.missingColumns(actual - "attrBuf"),
        )
    }

    /** 多出来的列就是那 5 列待取证的（数量变化是微信升级的信号） */
    @Test
    fun unknownColumnsAreThePendingFive() {
        val actual = SnsRestoreLogic.columns() + listOf("colA", "colB", "colC", "colD", "colE")
        assertEquals(
            listOf("colA", "colB", "colC", "colD", "colE"),
            SnsRestoreLogic.unknownColumns(actual),
        )
        assertEquals(emptyList<String>(), SnsRestoreLogic.unknownColumns(SnsRestoreLogic.columns()))
    }

    /**
     * 「INSERT 只给部分列」的硬风险：待取证的 5 列里只要有 NOT NULL 且无默认值的，
     * INSERT 必挂 —— 必须被算出来（宁可不做，也不能写一半进去）。
     */
    @Test
    fun blockingColumnsFlagsNotNullWithoutDefault() {
        val schema = listOf(
            SnsRestoreLogic.ColInfo("snsId", notNull = true, hasDefault = false), // 已知列，会写
            SnsRestoreLogic.ColInfo("localFlag", notNull = true, hasDefault = false),
            SnsRestoreLogic.ColInfo("someNew1", notNull = true, hasDefault = false), // ⚠️
            SnsRestoreLogic.ColInfo("someNew2", notNull = true, hasDefault = true), // 有默认值，OK
            SnsRestoreLogic.ColInfo("someNew3", notNull = false, hasDefault = false), // 可空，OK
        )
        assertEquals(listOf("someNew1"), SnsRestoreLogic.blockingColumns(schema))
    }

    // ------------------------------------------------------------------ 可见位

    @Test
    fun visibleBitRules() {
        assertFalse(SnsRestoreLogic.isVisible(2)) // 微信清位后
        assertFalse(SnsRestoreLogic.isVisible(0))
        assertTrue(SnsRestoreLogic.isVisible(16))
        assertTrue(SnsRestoreLogic.isVisible(18)) // 2|16，恢复目标
        assertTrue(SnsRestoreLogic.isVisible(2064)) // 白名单里带 2048 的
        assertTrue(SnsRestoreLogic.isVisible(2066))
    }

    @Test
    fun withVisibleIsIdempotentAndKeepsOtherBits() {
        assertEquals(18, SnsRestoreLogic.withVisible(2))
        assertEquals(18, SnsRestoreLogic.withVisible(18)) // 再或一次不变
        assertEquals(2064, SnsRestoreLogic.withVisible(2048))
        assertEquals(2066, SnsRestoreLogic.withVisible(2050)) // 2050|16
    }

    // ------------------------------------------------------------------ INSERT 映射

    @Test
    fun insertPutsCoverAllKnownColumnsInOrder() {
        val puts = SnsRestoreLogic.insertPuts(realRow())
        assertEquals(13, puts.size)
        assertEquals(SnsRestoreLogic.columns(), puts.map { it.col })
    }

    /** 归档快照的 localFlag 恒 2，插回去必须补可见位，否则界面上还是看不到 */
    @Test
    fun insertPutsRestoresVisibleBit() {
        val puts = SnsRestoreLogic.insertPuts(realRow(localFlag = 2))
        val flag = puts.first { it.col == "localFlag" }
        assertEquals(SnsRestoreLogic.Kind.INT, flag.kind)
        assertEquals(18, flag.v)
    }

    @Test
    fun insertPutsKeepsTypesAndNulls() {
        val row = realRow().copy(userName = null, content = null)
        val puts = SnsRestoreLogic.insertPuts(row)
        val byCol = puts.associateBy { it.col }
        assertEquals(SnsRestoreLogic.Kind.TEXT, byCol["userName"]!!.kind)
        assertNull(byCol["userName"]!!.v)
        assertEquals(SnsRestoreLogic.Kind.BLOB, byCol["content"]!!.kind)
        assertNull(byCol["content"]!!.v)
        assertEquals(SnsRestoreLogic.Kind.INT, byCol["createTime"]!!.kind)
        assertEquals(1760500000L, byCol["createTime"]!!.v)
        // snsId 必须原样带过去（它是主键，位模式错了就永远对不上）
        assertEquals(-3447438253133393222L, byCol["snsId"]!!.v)
    }

    // ------------------------------------------------------------------ 幂等判定

    @Test
    fun classifyDecidesPerRowState() {
        assertEquals(SnsRestoreLogic.Action.INSERT, SnsRestoreLogic.classify(null))
        assertEquals(SnsRestoreLogic.Action.SET_FLAG, SnsRestoreLogic.classify(2))
        assertEquals(SnsRestoreLogic.Action.SET_FLAG, SnsRestoreLogic.classify(0))
        assertEquals(SnsRestoreLogic.Action.SKIP, SnsRestoreLogic.classify(16))
        assertEquals(SnsRestoreLogic.Action.SKIP, SnsRestoreLogic.classify(18))
        assertEquals(SnsRestoreLogic.Action.SKIP, SnsRestoreLogic.classify(2064))
    }

    @Test
    fun planSplitsInsertAndSetFlag() {
        val a = realRow(snsId = 1L)
        val b = realRow(snsId = 2L)
        val c = realRow(snsId = 3L)
        // 1 行没了 → INSERT；2 行在但被清位 → SET_FLAG；3 行在且可见 → 不进计划
        val steps = SnsRestoreLogic.plan(listOf(a, b, c), mapOf(2L to 2, 3L to 18))
        assertEquals(2, steps.size)
        assertEquals(SnsRestoreLogic.Action.INSERT, steps[0].action)
        assertEquals(1L, steps[0].snsId)
        assertEquals(SnsRestoreLogic.Action.SET_FLAG, steps[1].action)
        assertEquals(2L, steps[1].snsId)
        assertEquals(2, steps[1].fromFlag)
        assertEquals(18, steps[1].toFlag)
    }

    @Test
    fun planDeduplicatesAndClamps() {
        val rows = listOf(
            realRow(snsId = 1L), realRow(snsId = 1L), realRow(snsId = 2L), realRow(snsId = 3L),
        )
        assertEquals(2, SnsRestoreLogic.plan(rows, emptyMap(), limit = 2).size)
        // 广播 --ei n 外人可控，必须钳位
        assertEquals(1, SnsRestoreLogic.clampBatch(0))
        assertEquals(3, SnsRestoreLogic.clampBatch(3))
        assertEquals(50, SnsRestoreLogic.clampBatch(999999))
    }

    /** 幂等：按计划改完库之后再算一次，必须**什么都不用做** */
    @Test
    fun planIsIdempotentAfterApply() {
        val rows = listOf(realRow(snsId = 1L), realRow(snsId = 2L))
        val live = mutableMapOf(2L to 2) // 1 已不在，2 被清位
        val first = SnsRestoreLogic.plan(rows, live)
        assertEquals(2, first.size)

        // 模拟执行
        for (s in first) {
            when (s.action) {
                SnsRestoreLogic.Action.INSERT -> live[s.snsId] = 18
                SnsRestoreLogic.Action.SET_FLAG -> live[s.snsId] = s.toFlag!!
                else -> Unit
            }
        }
        assertEquals(emptyList<SnsRestoreLogic.Step>(), SnsRestoreLogic.plan(rows, live))
    }

    // ------------------------------------------------------------------ 回滚

    @Test
    fun rollbackInvertsExactlyOneStep() {
        val ins = SnsRestoreLogic.Step(1L, SnsRestoreLogic.Action.INSERT, null, null)
        assertEquals(
            SnsRestoreLogic.Step(1L, SnsRestoreLogic.Action.ROLLBACK_DELETE, null, null),
            SnsRestoreLogic.rollbackOf(ins),
        )
        val set = SnsRestoreLogic.Step(2L, SnsRestoreLogic.Action.SET_FLAG, 2, 18)
        // 回滚 = 把 from/to 换个方向带回去，执行端写 fromFlag（2）
        assertEquals(SnsRestoreLogic.Action.ROLLBACK_FLAG, SnsRestoreLogic.rollbackOf(set)!!.action)
        assertEquals(2, SnsRestoreLogic.rollbackOf(set)!!.fromFlag)
        assertEquals(18, SnsRestoreLogic.rollbackOf(set)!!.toFlag)
        // SKIP 什么都没写，没有可回滚的
        assertNull(SnsRestoreLogic.rollbackOf(SnsRestoreLogic.Step(3L, SnsRestoreLogic.Action.SKIP, 18, null)))
    }

    /** 回滚必须**倒序**：后写的先撤，且只撤真的改过库的（SKIP 不进 done） */
    @Test
    fun rollbackOrderIsReversedAndSkipsNoops() {
        val done = listOf(
            SnsRestoreLogic.Step(1L, SnsRestoreLogic.Action.INSERT, null, null),
            SnsRestoreLogic.Step(2L, SnsRestoreLogic.Action.SET_FLAG, 2, 18),
        )
        val back = SnsRestoreLogic.rollbackOrder(done)
        assertEquals(2, back.size)
        assertEquals(SnsRestoreLogic.Action.ROLLBACK_FLAG, back[0].action)
        assertEquals(2L, back[0].snsId)
        assertEquals(SnsRestoreLogic.Action.ROLLBACK_DELETE, back[1].action)
        assertEquals(1L, back[1].snsId)
        assertEquals(emptyList<SnsRestoreLogic.Step>(), SnsRestoreLogic.rollbackOrder(emptyList()))
    }

    // ------------------------------------------------------------------ 汇总

    @Test
    fun tallyAddsUp() {
        val t = SnsRestoreLogic.Tally(total = 3, inserted = 1) +
            SnsRestoreLogic.Tally(total = 2, unhidden = 1, failed = 1, rolledBack = 1)
        assertEquals(5, t.total)
        assertEquals(1, t.inserted)
        assertEquals(1, t.unhidden)
        assertEquals(1, t.failed)
        assertEquals(1, t.rolledBack)
        assertEquals(0, t.skipped)
    }

    @Test
    fun summariesAreStable() {
        val steps = listOf(
            SnsRestoreLogic.Step(1L, SnsRestoreLogic.Action.INSERT, null, null),
            SnsRestoreLogic.Step(2L, SnsRestoreLogic.Action.SET_FLAG, 2, 18),
        )
        assertEquals("plan total=2 insert=1 setFlag=1", SnsRestoreLogic.planSummary(steps))
        assertEquals("plan total=0 insert=0 setFlag=0", SnsRestoreLogic.planSummary(emptyList()))
        assertEquals(
            "total=2 inserted=1 unhidden=1 skipped=0 failed=0 rolledBack=0",
            SnsRestoreLogic.summary(SnsRestoreLogic.Tally(total = 2, inserted = 1, unhidden = 1)),
        )
    }
}
