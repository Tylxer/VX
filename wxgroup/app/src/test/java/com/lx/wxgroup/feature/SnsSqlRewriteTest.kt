package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R42 方案 B 的守门测试：`execSQL ... sourceType & -9` 的**判定 + 改写**。
 *
 * ## 为什么这组测试非有不可
 * 改写是**改微信要执行的 SQL**，错的方向全是灾难且不一定立刻可见：
 * - 改到 WHERE 里的同名表达式 ⇒ 过滤条件恒真，命中集合暴涨（写整表）；
 * - 掩码写错（`-3` / `-8`）⇒ 把时间线位也削掉，比不拦更糟；
 * - 括号没保持 ⇒ SQL 语法错误，微信直接抛异常（用户侧是朋友圈闪退）。
 *
 * ## 三条硬约束（对应下面的守门用例）
 * 1. **WHERE 一个字节都不许动**（[rewrite_never_touches_where_clause]）；
 * 2. 掩码只能是 `-1`，且 `x & -1 == x`（[noop_mask_is_identity]）；
 * 3. `& -3` / 非 UPDATE / 非 SnsInfo / 加位写 —— 一律**逐字**返回原文
 *    （[bit1_only_is_recognized_but_not_rewritten] 等）。
 */
class SnsSqlRewriteTest {

    /** 真机上抓到的那条（`docs/_r42_bit_paths.md` §8.3），不是编的 */
    private val real =
        "UPDATE SnsInfo SET sourceType = sourceType & -9\n" +
            " WHERE SnsInfo.userName=\"wxid_xlrbelf6hv0w22\"\n" +
            "   AND (sourceType in (8,264,10,266,12,268,14,270,24,280,26,282,28,284,30,286," +
            "72,328,74,330,76,332,78,334,88,344,90,346,92,348,94,350,136,392,138,394,140,396," +
            "142,398,152,408,154,410,156,412,158,414,200,456,202,458,204,460,206,462,216,472," +
            "218,474,220,476,222,478))\n" +
            "   AND  (snsId != 0  )\n" +
            "   AND  (snsId > -3397825059229724135)"

    // ------------------------------------------------------------------ 主形态

    @Test
    fun real_sql_is_blocked_and_rewritten_to_noop() {
        assertTrue(SnsSqlRewrite.shouldBlock(real))
        val out = SnsSqlRewrite.rewrite(real)
        assertTrue("必须留下恒等掩码", out.contains("sourceType = sourceType & -1"))
        assertFalse("原来的 -9 必须消失", out.contains("-9"))
        // 改写后再识别，就不该再被判成清位（否则等于「改了但看起来还是清位」）
        assertEquals(SnsBitLogic.SqlClear.NONE, SnsBitLogic.sqlClear(out))
    }

    @Test
    fun rewrite_never_touches_where_clause() {
        // ⛔ 最重要的一条：WHERE 必须逐字不变。改了它 ⇒ 过滤条件恒真、命中集合暴涨
        val where = real.substring(real.indexOf("WHERE"))
        val outWhere = SnsSqlRewrite.rewrite(real).substring(real.indexOf("WHERE"))
        assertEquals(where, outWhere)
        assertEquals("整条只允许差 1 个字符（9→1）", 1, diffCount(real, SnsSqlRewrite.rewrite(real)))
    }

    @Test
    fun where_only_bit3_expression_is_not_a_source_write() {
        // WHERE 里的 `sourceType = sourceType & -9` 是**过滤条件**，不是清位 —— 一个字节都不许动
        val s = "UPDATE SnsInfo SET content='x' WHERE snsId=1 AND sourceType = sourceType & -9"
        assertFalse(SnsSqlRewrite.shouldBlock(s))
        assertEquals(s, SnsSqlRewrite.rewrite(s))

        // 极端形态：SET 里清 bit3、WHERE 里也出现同一个表达式 ⇒ 只改 SET 那一处
        val both = "UPDATE SnsInfo SET sourceType = sourceType & -9 " +
            "WHERE snsId > 0 AND sourceType = sourceType & -9"
        assertTrue(SnsSqlRewrite.shouldBlock(both))
        val out = SnsSqlRewrite.rewrite(both)
        assertEquals("SET 里改掉 1 处", 1, countOf(out, "& -1"))
        assertEquals("WHERE 里剩 1 处 -9", 1, countOf(out, "& -9"))
        assertTrue(out.endsWith("WHERE snsId > 0 AND sourceType = sourceType & -9"))
    }

    @Test
    fun quoted_where_keyword_does_not_split_the_statement() {
        // `content='where x'` 里的词不是子句；扫错了会把 SET 之后的真 WHERE 当值域继续改写
        val s = "UPDATE SnsInfo SET sourceType = sourceType & -9, content='where x' WHERE snsId=1"
        assertTrue(SnsSqlRewrite.shouldBlock(s))
        assertEquals(
            "UPDATE SnsInfo SET sourceType = sourceType & -1, content='where x' WHERE snsId=1",
            SnsSqlRewrite.rewrite(s),
        )
    }

    @Test
    fun quoted_where_before_the_assignment_does_not_disable_rewrite() {
        // ⛔ 反方向的坑：引号里的 `where` 若被当成子句，切点落在赋值**之前** ⇒ 整条被判定为「SET 里没有清位」
        //    ⇒ 静默漏拦（fail-open）。这比改错更隐蔽，所以单独钉一条。
        val s = "UPDATE SnsInfo SET content='where', sourceType = sourceType & -9 WHERE snsId=1"
        assertTrue("引号内的 where 不能截断 SET", SnsSqlRewrite.shouldBlock(s))
        assertEquals(
            "UPDATE SnsInfo SET content='where', sourceType = sourceType & -1 WHERE snsId=1",
            SnsSqlRewrite.rewrite(s),
        )
    }

    // ------------------------------------------------------------------ 掩码正确性

    @Test
    fun noop_mask_is_identity() {
        // `x & -1 == x` 对任意 64 位整数成立 ⇒ 改写后「赋回原值」，语义不变
        assertEquals("-1", SnsSqlRewrite.NOOP_MASK)
        for (v in intArrayOf(0, 2, 8, 10, 130, 264, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals("v=$v", v, v and -1)
        }
        // 反向守门：原来的掩码必须真的会清 bit3（证明这不是「本来就相等」的空测试）
        assertEquals(2, 10 and -9)
        assertEquals(10, 10 and -1)
    }

    @Test
    fun rewritten_sql_is_still_a_legal_statement() {
        val out = SnsSqlRewrite.rewrite(real)
        assertTrue("仍以 UPDATE 开头", out.trimStart().startsWith("UPDATE"))
        assertEquals("括号必须配平", parenBalance(real), parenBalance(out))
        assertEquals("引号必须配平", quoteCount(real), quoteCount(out))
        assertTrue("仍是单条语句", !out.contains(";"))
        // 幂等：再跑一次不该有第二处变化（改完的形态不能再被认成清位）
        assertEquals(out, SnsSqlRewrite.rewrite(out))
    }

    @Test
    fun nested_parentheses_keep_their_shape() {
        val s = "UPDATE SnsInfo SET sourceType = (sourceType & (-9)) WHERE snsId=1"
        assertTrue(SnsSqlRewrite.shouldBlock(s))
        assertEquals(
            "UPDATE SnsInfo SET sourceType = (sourceType & (-1)) WHERE snsId=1",
            SnsSqlRewrite.rewrite(s),
        )
    }

    // ------------------------------------------------------------------ 不该动的形态

    @Test
    fun bit1_only_is_recognized_but_not_rewritten() {
        // 4.3 的决定：`& -3` 只记录告警不拦（真机从未出现，无样本可估副作用）
        val s = "UPDATE SnsInfo SET sourceType = sourceType & -3 WHERE snsId=-1"
        assertEquals(SnsBitLogic.SqlClear.TIMELINE, SnsBitLogic.sqlClear(s))
        assertFalse("⛔ 只识别，不许拦", SnsSqlRewrite.shouldBlock(s))
        assertEquals("必须逐字返回", s, SnsSqlRewrite.rewrite(s))
    }

    @Test
    fun both_kinds_rewrite_only_bit3() {
        // BOTH 形态：仍只动 `-9`，`-3`（这里在 WHERE 里）必须原样留着
        val s = "UPDATE SnsInfo SET sourceType = sourceType & -9, localFlag = 0 " +
            "WHERE snsId = 1 AND sourceType = sourceType & -3"
        assertEquals(SnsBitLogic.SqlClear.BOTH, SnsBitLogic.sqlClear(s))
        val out = SnsSqlRewrite.rewrite(s)
        assertTrue(out, out.contains("sourceType = sourceType & -1, localFlag = 0"))
        assertTrue(out, out.endsWith("AND sourceType = sourceType & -3"))
    }

    @Test
    fun both_kinds_with_bit3_only_in_where_is_not_rewritten() {
        // BOTH 的另一种排布：`-3` 在 SET、`-9` 只在 WHERE。
        // ⛔ 这条**必须一个字都不改** —— 改掉 WHERE 里的 `& -9` 会让过滤条件恒真。
        val s = "UPDATE SnsInfo SET sourceType = sourceType & -3, localFlag = 0 " +
            "WHERE snsId = 1 AND sourceType = sourceType & -9"
        assertEquals(SnsBitLogic.SqlClear.BOTH, SnsBitLogic.sqlClear(s))
        assertFalse(SnsSqlRewrite.shouldBlock(s))
        assertEquals(s, SnsSqlRewrite.rewrite(s))
    }

    @Test
    fun other_tables_and_other_columns_are_untouched() {
        val other = "UPDATE Message SET sourceType = sourceType & -9 WHERE snsId=1"
        assertFalse(SnsSqlRewrite.shouldBlock(other))
        assertEquals(other, SnsSqlRewrite.rewrite(other))

        val otherCol = "UPDATE SnsInfo SET localFlag = localFlag & -9 WHERE snsId=1"
        assertFalse(SnsSqlRewrite.shouldBlock(otherCol))
        assertEquals(otherCol, SnsSqlRewrite.rewrite(otherCol))
    }

    @Test
    fun select_with_the_same_expression_is_untouched() {
        // 只有 UPDATE 才允许动刀：SELECT 里的同名表达式一旦被改写，查询范围会变
        val s = "SELECT * FROM SnsInfo WHERE sourceType = sourceType & -9"
        assertFalse(SnsSqlRewrite.shouldBlock(s))
        assertEquals(s, SnsSqlRewrite.rewrite(s))
    }

    @Test
    fun add_bit_write_is_not_a_clear() {
        // 我们自己补位/加位的写法：⛔ 一个字节都不能动，否则等于替微信改库
        for (s in listOf(
            "UPDATE SnsInfo SET sourceType = sourceType | 10 WHERE snsId=1",
            "UPDATE SnsInfo SET sourceType = sourceType | 2 WHERE snsId=1",
            "UPDATE SnsInfo SET sourceType = 10 WHERE snsId=1",
            "UPDATE SnsInfo SET sourceType = -9 WHERE snsId=1",
        )) {
            assertFalse(s, SnsSqlRewrite.shouldBlock(s))
            assertEquals(s, SnsSqlRewrite.rewrite(s))
        }
    }

    @Test
    fun near_miss_constants_do_not_false_positive() {
        // `-90` / `-93` / `- 9` 都不是 `-9`：误判会把无关模板改坏
        for (s in listOf(
            "UPDATE SnsInfo SET sourceType = sourceType & -90 WHERE snsId=1",
            "UPDATE SnsInfo SET sourceType = sourceType & -93 WHERE snsId=1",
            "UPDATE SnsInfo SET sourceType = sourceType & - 9 WHERE snsId=1",
        )) {
            assertFalse(s, SnsSqlRewrite.shouldBlock(s))
            assertEquals(s, SnsSqlRewrite.rewrite(s))
        }
    }

    @Test
    fun null_and_blank_are_safe() {
        assertFalse(SnsSqlRewrite.shouldBlock(null))
        assertFalse(SnsSqlRewrite.shouldBlock(""))
        assertFalse(SnsSqlRewrite.shouldBlock("   "))
        assertEquals("", SnsSqlRewrite.rewrite(null))
        assertEquals("", SnsSqlRewrite.rewrite(""))
    }

    @Test
    fun case_and_spacing_variants_are_handled() {
        val variants = listOf(
            "update SnsInfo set sourceType = sourceType & -9 where snsId=1",
            "UPDATE SnsInfo SET sourceType=sourceType&-9 WHERE snsId=1",
            "UpDaTe SnSiNfO sEt SoUrCeTyPe = SoUrCeTyPe & -9 WhErE snsId = 1",
            "UPDATE SnsInfo SET\n  sourceType\n  =\n  sourceType\n  & -9\nWHERE snsId=1",
        )
        for (s in variants) {
            assertTrue(s, SnsSqlRewrite.shouldBlock(s))
            val out = SnsSqlRewrite.rewrite(s)
            assertFalse(out, out.contains("-9"))
            assertTrue(out, out.contains("-1"))
        }
    }

    @Test
    fun recognition_and_action_are_two_deliberately_different_layers() {
        // 记账层（sqlClear）宽松、动作层（shouldBlock）严格：WHERE 里的同名表达式**只记账不动手**。
        // 若把两层合成一个 bool，要么漏记新模板，要么改坏过滤条件 —— 这里把差异钉死。
        val whereOnly = "UPDATE SnsInfo SET content='x' WHERE sourceType = sourceType & -9"
        assertEquals(SnsBitLogic.SqlClear.ALBUM, SnsBitLogic.sqlClear(whereOnly))
        assertFalse(SnsSqlRewrite.shouldBlock(whereOnly))
        // `& -3` 同理：识别得到，但不拦
        val bit1 = "UPDATE SnsInfo SET sourceType = sourceType & -3 WHERE snsId=1"
        assertEquals(SnsBitLogic.SqlClear.TIMELINE, SnsBitLogic.sqlClear(bit1))
        assertFalse(SnsSqlRewrite.shouldBlock(bit1))
    }

    @Test
    fun update_without_where_still_rewrites() {
        val s = "UPDATE SnsInfo SET sourceType = sourceType & -9"
        assertTrue(SnsSqlRewrite.shouldBlock(s))
        assertEquals("UPDATE SnsInfo SET sourceType = sourceType & -1", SnsSqlRewrite.rewrite(s))
    }

    // ------------------------------------------------------------------ 区间（重试判据）

    @Test
    fun range_key_covers_paged_forms() {
        assertEquals(
            "-3397825059229724135..*",
            SnsSqlRewrite.rangeKey(real),
        )
        assertEquals(
            "-100..-10",
            SnsSqlRewrite.rangeKey(
                "UPDATE SnsInfo SET sourceType = sourceType & -9 " +
                    "WHERE (snsId > -100) AND (snsId < -10)"
            ),
        )
        // `!=` 不是区间；只有 `!=` 时抠不出下界 ⇒ null（不参与区间统计）
        assertNull(
            SnsSqlRewrite.rangeKey(
                "UPDATE SnsInfo SET sourceType = sourceType & -9 WHERE (snsId != 0)"
            )
        )
        assertNull(SnsSqlRewrite.rangeKey(null))
        assertNotNull(SnsSqlRewrite.rangeKey(real))
    }

    // ------------------------------------------------------------------ 小工具

    private fun countOf(s: String, sub: String): Int {
        var n = 0
        var i = s.indexOf(sub)
        while (i >= 0) {
            n++
            i = s.indexOf(sub, i + sub.length)
        }
        return n
    }

    /** 逐字符差异数（改写必须只差掩码那 1 个字符） */
    private fun diffCount(a: String, b: String): Int {
        if (a.length != b.length) return Int.MAX_VALUE
        var n = 0
        for (i in a.indices) if (a[i] != b[i]) n++
        return n
    }

    /** 允许配平：所有括号的净层数（含引号内的不计） */
    private fun parenBalance(s: String): Int {
        var depth = 0
        var q: Char? = null
        for (c in s) {
            when {
                q != null -> if (c == q) q = null
                c == '\'' || c == '"' -> q = c
                c == '(' -> depth++
                c == ')' -> depth--
            }
        }
        return depth
    }

    private fun quoteCount(s: String): Int = s.count { it == '"' }
}
