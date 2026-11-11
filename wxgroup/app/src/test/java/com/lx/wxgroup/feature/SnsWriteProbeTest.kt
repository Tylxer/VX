package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R49 阶段二 · `SnsWriteProbe` 纯逻辑单测（**零 Android 依赖纯 JVM**）。
 *
 * 被测 [SnsWriteProbeCore] 是纯 Kotlin object，与桥接 object 不同 `.class`，
 * 单测加载它不会触发 Android / Xposed 类（`build.gradle.kts` 里 xposed 是 compileOnly）。
 *
 * ## 为什么用例要能「区分正确实现与看似合理的错误实现」
 * 项目吃过「等价变异假验证」的亏（速查 `:330`）。本文件的每个判据都配了
 * **能被打红的边界用例**，S6 报告里 5 组变异逐组验证「改一行 → 哪个用例变红」：
 *  ① `<`→`<=` ② `& -9` 白名单匹配改恒真 ③ 区间 `>`/`<` 互换
 *  ④ 去重键去掉 `userName` ⑤ `hitOlder3d` 恒返回 0。
 */
class SnsWriteProbeTest {

    private val core = SnsWriteProbeCore

    /** 真机抓到的 `& -9` 原文（`docs/_r42_bit_paths.md` §8.3 / `SnsSqlRewriteTest.real`）。 */
    private val real9 =
        "UPDATE SnsInfo SET sourceType = sourceType & -9\n" +
            " WHERE SnsInfo.userName=\"wxid_xlrbelf6hv0w22\"\n" +
            "   AND (sourceType in (8,264,10,266,12,268,14,270,24,280,26,282,28,284,30,286," +
            "72,328,74,330,76,332,78,334,88,344,90,346,92,348,94,350,136,392,138,394,140,396," +
            "142,398,152,408,154,410,156,412,158,414,200,456,202,458,204,460,206,462,216,472," +
            "218,474,220,476,222,478))\n" +
            "   AND  (snsId != 0  )\n" +
            "   AND  (snsId > -3397825059229724135)"

    @Before
    fun setUp() {
        // 账本是进程内单例，用例间必须隔离；setArmed(false) 模拟冷启默认关。
        core.reset()
        core.setArmed(false)
    }

    private fun ev(
        sql: String?,
        user: String?,
        kind: SnsWriteProbeCore.Kind = SnsWriteProbeCore.Kind.EXECSQL,
    ): SnsWriteProbeCore.WriteEvent = SnsWriteProbeCore.WriteEvent(
        ts = 1L,
        kind = kind,
        sql = sql,
        cvKeys = emptyList(),
        cvSourceType = null,
        table = "SnsInfo",
        where = null,
        snsIds = null,
        userName = user,
        caller = null,
    )

    // ================================================================ parseClear9

    @Test
    fun real_minus9_sql_parsed_with_user_whitelist_and_range() {
        val c = core.parseClear9(real9)
        assertNotNull(c)
        assertEquals("SnsInfo", c!!.table)
        assertEquals("wxid_xlrbelf6hv0w22", c.userName)
        assertEquals(-3397825059229724135L, c.lo!!)
        assertNull("真机这条只有下界，无 snsId < B", c.hi)
        assertFalse("原文是 & -9，不是已改写态", c.rewritten)
        assertEquals("64 个含 bit3 的 sourceType 白名单", 64, c.sourceTypes.size)
        assertTrue("白名单含 10", c.sourceTypes.contains(10))
        assertTrue(c.sourceTypes.contains(8))
    }

    @Test
    fun rewritten_minus1_is_recognized_not_absent() {
        // ⭐ 防假阴性关键：`& -1` 是 SnsSqlRewrite 改写后的 no-op，必须被识别成
        // 「已改写」而不是「无写事件」，否则库快照差分会把有写事件误判成没有。
        val s = "UPDATE SnsInfo SET sourceType = sourceType & -1 " +
            "WHERE userName=\"u\" AND (snsId > 0)"
        val c = core.parseClear9(s)
        assertNotNull("& -1 必须被识别成「已改写」而不是「无写事件」", c)
        assertTrue(c!!.rewritten)
        assertEquals("u", c.userName)
        assertEquals(0L, c.lo!!)
    }

    @Test
    fun bit1_only_minus3_is_not_target() {
        assertNull(core.parseClear9("UPDATE SnsInfo SET sourceType = sourceType & -3 WHERE snsId > 0"))
    }

    @Test
    fun plain_sourceType_write_is_not_target() {
        // 加位写（sourceType = 10）不是清位，绝不能被判成目标 SQL
        assertNull(core.parseClear9("UPDATE SnsInfo SET sourceType = 10 WHERE snsId = 1"))
    }

    @Test
    fun select_delete_other_table_return_null() {
        assertNull(core.parseClear9("SELECT * FROM SnsInfo"))
        assertNull(core.parseClear9("DELETE FROM SnsInfo WHERE snsId=1"))
        assertNull(core.parseClear9("UPDATE SnsComment SET sourceType = sourceType & -9"))
        assertNull("updateXxx 不算 UPDATE（词边界）", core.parseClear9("updateXxx SnsInfo SET sourceType = sourceType & -9"))
    }

    @Test
    fun null_blank_return_null() {
        assertNull(core.parseClear9(null))
        assertNull(core.parseClear9(""))
        assertNull(core.parseClear9("   \n  "))
    }

    @Test
    fun case_and_space_variants_recognized() {
        val s = "uPdAtE   sNsInFo   SeT   sourceType  =  ( sourceType  &  -9 )  " +
            "WhErE  username = 'U'  aNd  ( snsId  >  5 )"
        val c = core.parseClear9(s)
        assertNotNull(c)
        assertFalse(c!!.rewritten)
        assertEquals("U", c.userName)
        assertEquals(5L, c.lo!!)
    }

    @Test
    fun where_only_expression_is_not_source_write() {
        // WHERE 里出现同名表达式只是过滤条件，清位必须发生在 SET 里
        val s = "UPDATE SnsInfo SET content='x' WHERE snsId=1 AND sourceType = sourceType & -9"
        assertNull(core.parseClear9(s))
    }

    @Test
    fun snsId_both_bounds_parsed() {
        val s = "UPDATE SnsInfo SET sourceType = sourceType & -9 " +
            "WHERE userName='u' AND (snsId > 100) AND (snsId < 200)"
        val c = core.parseClear9(s)!!
        assertEquals(100L, c.lo!!)
        assertEquals(200L, c.hi!!)
        assertEquals("u", c.userName)
    }

    @Test
    fun minus1_with_user_and_range_rewritten() {
        val s = "UPDATE SnsInfo SET sourceType = sourceType & -1 " +
            "WHERE userName=\"u2\" AND (snsId > 7) AND (snsId < 9)"
        val c = core.parseClear9(s)!!
        assertTrue(c.rewritten)
        assertEquals("u2", c.userName)
        assertEquals(7L, c.lo!!)
        assertEquals(9L, c.hi!!)
    }

    @Test
    fun no_createTime_window_in_real_sql() {
        // 阶段一取证：WHERE 是 userName + 白名单 + snsId 区间，**无 createTime 窗口**
        assertFalse(real9.contains("createTime", ignoreCase = true))
    }

    // ================================================================ hitOlder3d 判据

    @Test
    fun createTime_equal_T0_is_not_older_3d() {
        val t0 = 1793884838L
        assertFalse("等于 T0 不算三天外（严格 <，⛔ 不是 <=）", core.isOlder3dSec(t0, t0))
    }

    @Test
    fun createTime_strictly_less_than_T0_is_older() {
        val t0 = 1793884838L
        assertTrue(core.isOlder3dSec(t0 - 1, t0))
        assertTrue(core.isOlder3dSec(1_700_000_000L, t0))
    }

    @Test
    fun millis_13_digit_not_confused_with_seconds() {
        val t0 = 1793884838L
        assertFalse("13 位毫秒不得当 10 位秒混淆", core.isOlder3dSec(1_700_000_000_000L, t0))
        assertFalse(core.isOlder3dSec(1793884838000L, t0))
    }

    @Test
    fun seconds_10_digit_accepted() {
        assertTrue(core.isOlder3dSec(1_000_000_000L, 1_100_000_000L))
        assertFalse(">=1e10 不是 10 位秒", core.isOlder3dSec(10_000_000_000L, 20_000_000_000L))
        assertFalse("<1e9 不是 10 位秒", core.isOlder3dSec(999_999_999L, 20_000_000_000L))
    }

    @Test
    fun range_open_boundaries() {
        assertTrue("区间内", core.inOpenRange(150L, 100L, 200L))
        assertFalse("lo 开区间：等于 lo 不算命中", core.inOpenRange(100L, 100L, 200L))
        assertFalse("hi 开区间：等于 hi 不算命中", core.inOpenRange(200L, 100L, 200L))
        assertFalse("小于 lo", core.inOpenRange(99L, 100L, 200L))
        assertFalse("大于 hi", core.inOpenRange(201L, 100L, 200L))
    }

    @Test
    fun range_null_bounds_unbounded() {
        assertTrue(core.inOpenRange(5L, null, null))
        assertTrue(core.inOpenRange(5L, 1L, null))
        assertTrue(core.inOpenRange(5L, null, 10L))
    }

    @Test
    fun user_name_mismatch_not_hit() {
        assertFalse(core.userMatches("wxid_a", "wxid_b"))
        assertTrue(core.userMatches("wxid_a", "wxid_a"))
        val cond = SnsWriteProbeCore.HitCond(1793884838L, null, null, "wxid_b")
        assertFalse(core.rowHit(1_700_000_000L, 5L, "wxid_a", cond))
    }

    @Test
    fun user_name_empty_target_no_restriction() {
        assertTrue(core.userMatches("wxid_a", null))
        assertTrue(core.userMatches("wxid_a", ""))
        val cond = SnsWriteProbeCore.HitCond(1793884838L, null, null, null)
        assertTrue(core.rowHit(1_700_000_000L, 5L, "wxid_a", cond))
    }

    @Test
    fun hit_count_mixed_rows() {
        // 8 行里只有前 2 行同时满足：10 位秒且 < T0、开区间内、userName 匹配
        val t0 = 1_800_000_000L
        val cond = SnsWriteProbeCore.HitCond(t0, 100L, 900L, "u")
        val rows = listOf(
            SnsWriteProbeCore.Row(1_700_000_000L, 200L, "u"),        // hit
            SnsWriteProbeCore.Row(1_700_000_000L, 300L, "u"),        // hit
            SnsWriteProbeCore.Row(t0, 200L, "u"),                    // == T0 不算三天外
            SnsWriteProbeCore.Row(1_700_000_000L, 100L, "u"),        // == lo 开区间排除
            SnsWriteProbeCore.Row(1_700_000_000L, 900L, "u"),        // == hi 开区间排除
            SnsWriteProbeCore.Row(1_700_000_000_000L, 200L, "u"),    // 13 位毫秒
            SnsWriteProbeCore.Row(1_700_000_000L, 200L, "other"),    // userName 不匹配
            SnsWriteProbeCore.Row(1_900_000_000L, 200L, "u"),        // 新于 T0
        )
        assertEquals(2L, core.hitOlder3d(rows, cond))
    }

    @Test
    fun rowHit_combines_three_predicates() {
        val cond = SnsWriteProbeCore.HitCond(1_800_000_000L, 100L, 900L, "u")
        assertTrue(core.rowHit(1_700_000_000L, 200L, "u", cond))
        assertFalse("时间不满足", core.rowHit(1_800_000_000L, 200L, "u", cond))
        assertFalse("区间不满足", core.rowHit(1_700_000_000L, 50L, "u", cond))
        assertFalse("userName 不满足", core.rowHit(1_700_000_000L, 200L, "x", cond))
    }

    // ================================================================ 只读 SQL 构造（防注入 / BLOB hex）

    @Test
    fun buildHitCountSql_uses_hex_not_cast() {
        val sql = core.buildHitCountSql(SnsWriteProbeCore.HitCond(1793884838L, 100L, 900L, "wxid_x"))
        assertTrue(sql.startsWith("select count(*),min(snsId) from SnsInfo"))
        assertTrue(sql.contains("createTime < 1793884838"))
        assertTrue(sql.contains("snsId > 100"))
        assertTrue(sql.contains("snsId < 900"))
        assertTrue(sql.contains("userName='wxid_x'"))
        assertTrue("BLOB 判空用 hex()", sql.contains("hex(content)"))
        assertTrue(sql.contains("hex(attrBuf)"))
        assertFalse("⛔ BLOB 判空禁用 cast(... as text)", sql.lowercase().contains("cast("))
    }

    @Test
    fun buildHitCountSql_escapes_username_injection() {
        val sql = core.buildHitCountSql(SnsWriteProbeCore.HitCond(1793884838L, null, null, "x' OR 1=1 --"))
        assertTrue("userName 必须转义单引号", sql.contains("userName='x'' OR 1=1 --'"))
    }

    @Test
    fun sqlQuote_doubles_single_quote() {
        assertEquals("'O''Brien'", core.sqlQuote("O'Brien"))
        assertEquals("'abc'", core.sqlQuote("abc"))
    }

    @Test
    fun buildHitCountSql_no_semicolon_and_strict_ops() {
        val sql = core.buildHitCountSql(SnsWriteProbeCore.HitCond(1793884838L, 1L, 2L, "u"))
        assertFalse("过 runReadOnlySql 白名单：不能有内部 ;", sql.contains(";"))
        assertTrue(sql.contains("snsId > 1"))
        assertTrue(sql.contains("snsId < 2"))
        assertFalse(sql.contains("createTime <= "))
        assertFalse(sql.contains("snsId >= "))
        assertFalse(sql.contains("snsId <= "))
    }

    // ================================================================ 账本：去重 / 封顶 / 冷启清零 / 三态

    @Test
    fun dedup_key_includes_username() {
        val s = "UPDATE SnsInfo SET sourceType = sourceType & -9 WHERE snsId > 1"
        assertTrue(core.record(ev(s, "u1")))
        assertTrue(core.record(ev(s, "u2")))
        assertEquals("不同 userName 的同形 SQL 必须都入账", 2, core.snapshot().size)
    }

    @Test
    fun dedup_same_key_collapsed_but_seen_counts() {
        val s = "UPDATE SnsInfo SET sourceType = sourceType & -9"
        assertTrue(core.record(ev(s, "u")))
        assertFalse("同 key 第二次被去重", core.record(ev(s, "u")))
        assertEquals(1, core.snapshot().size)
        assertEquals("seen 不去重", 2L, core.counts().first)
        assertEquals("matched 不去重", 2L, core.counts().second)
    }

    @Test
    fun ring_cap_evicts_oldest() {
        for (i in 0 until core.RING + 50) {
            core.record(ev("UPDATE SnsInfo SET sourceType = sourceType & -9 WHERE snsId=$i", "u$i"))
        }
        assertEquals(core.RING, core.snapshot().size)
        assertTrue("最老 50 条应被环形淘汰", core.snapshot().first().sql!!.contains("snsId=50"))
    }

    @Test
    fun reset_clears_ledger_and_counts_keeps_armed() {
        core.record(ev("UPDATE SnsInfo SET sourceType = sourceType & -9", "u"))
        core.setArmed(true)
        core.reset()
        assertEquals(0, core.snapshot().size)
        assertEquals(0L, core.counts().first)
        assertEquals(0L, core.counts().second)
        assertTrue("reset 不改 armed（关开关是 disarm，单独做）", core.isArmed())
    }

    @Test
    fun cold_start_default_disarmed_and_switch_roundtrip() {
        assertFalse("默认关：进程内结构冷启清零、arm 前不追溯", core.isArmed())
        core.setArmed(true)
        assertTrue(core.isArmed())
        core.setArmed(false)
        assertFalse(core.isArmed())
    }

    @Test
    fun verdict_three_states_mutually_exclusive() {
        assertTrue(core.verdict(0, 0).contains("无观测机会"))
        assertTrue(core.verdict(5, 0).contains("判据不识别"))
        assertTrue(core.verdict(5, 3).contains("有真实写事件"))
        // 互斥：seen==0 优先，不被 matched>0 拉到第三态（⛔ 别写成并列）
        assertTrue(core.verdict(0, 3).contains("无观测机会"))
        // record 计数：目标 + 非目标各一条
        core.record(ev("UPDATE SnsInfo SET sourceType = sourceType & -9", "u"))
        core.record(ev("UPDATE SnsInfo SET content='x'", "u"))
        assertEquals(2L, core.counts().first)
        assertEquals(1L, core.counts().second)
    }
}
