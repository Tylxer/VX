package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R42 方案 A 的守门测试：`sourceType` 位判据 + execSQL 清位形态识别。
 *
 * ## 为什么这组测试非有不可
 * 判据错的两个方向都是**静默**的：
 * - 该拦的没拦（`10→8`）⇒ 删除好友后动态照样消失，要等真机复现；
 * - 不该拦的拦了（`0→2`）⇒ 新动态进不了相册位，界面上看不出「是模块干的」。
 * 而这两者的**新值可能是同一个数**（都是 `2`），所以判据必须吃旧值 —— 下面
 * [downgrade_10_to_2_is_blocked] 与 [add_timeline_bit_is_allowed] 就是同一新值的正反两面。
 */
class SnsBitLogicTest {

    private fun block(old: Int?, new: Int) =
        assertEquals("old=$old new=$new 应当拦", SnsBitLogic.Action.BLOCK, SnsBitLogic.decide(old, new))

    private fun allow(old: Int?, new: Int) =
        assertEquals("old=$old new=$new 应当放行", SnsBitLogic.Action.ALLOW, SnsBitLogic.decide(old, new))

    // ------------------------------------------------------------------ 位常量

    @Test
    fun bit_constants_match_forensics() {
        assertEquals("时间线位必须是 bit1", 2, SnsBitLogic.BIT_TIMELINE)
        assertEquals("好友主页相册位必须是 bit3", 8, SnsBitLogic.BIT_ALBUM)
    }

    // ------------------------------------------------------------------ 拦截：写 0

    @Test
    fun write_zero_is_blocked_even_without_old_value() {
        // 旧实现的行为：值 == 0 直接拦，不查库。⛔ 这条被改掉 = 纯文字删除会漏
        block(null, 0)
        block(2, 0)
        block(8, 0)
        block(10, 0)
        block(130, 0)
    }

    // ------------------------------------------------------------------ 拦截：降位

    @Test
    fun downgrade_10_to_8_is_blocked() {
        // 好友删除第一步：10 & ~2 = 8。放行 ⇒ 时间线先丢（R42 §2.1 实测轨迹）
        block(10, 8)
    }

    @Test
    fun downgrade_10_to_2_is_blocked() {
        // 进好友主页对账：10 & ~8 = 2。放行 ⇒ 主页相册丢（R42 §3 实测 25 条）
        block(10, 2)
    }

    @Test
    fun other_pure_downgrades_are_blocked_by_the_same_rule() {
        // 同语义形态（纯删位且丢掉可见位），按位判自然覆盖
        block(264, 256)  // 264 = 8|256，丢 bit3
        block(138, 130)  // 138 = 128|8|2 → 130 = 128|2，丢 bit3
        block(136, 128)  // 136 = 128|8 → 128，丢 bit3
        block(11, 8)     // 11 = 8|2|1，丢 bit1（其余位保留）
    }

    @Test
    fun mixed_rewrites_are_allowed() {
        // ⛔ 混合改写（删一个位、同时添另一个位）不在用户枚举里，一律不动。
        // 尤其 8→2：拦掉它等于把动态按在「只在相册、时间线仍不可见」，与目标相反。
        allow(8, 2)
        allow(2, 8)
        allow(10, 130)   // 丢 bit3 但加回折叠位 128
        allow(10, 6)     // 丢 bit3 但加私密位 4
        allow(6, 2)      // 丢私密位（不在关注范围内）
    }

    // ------------------------------------------------------------------ 放行：加位 / 原地

    @Test
    fun add_timeline_bit_is_allowed() {
        // ⚠️ 与 [downgrade_10_to_2_is_blocked] 新值相同（都是 2），只靠旧值区分
        allow(0, 2)
    }

    @Test
    fun add_album_bit_is_allowed() {
        // ⚠️ 与 [downgrade_10_to_8_is_blocked] 新值相同（都是 8）
        allow(0, 8)
        allow(2, 10)
    }

    @Test
    fun add_high_bits_is_allowed() {
        allow(2, 130)    // 折叠位加入
        allow(0, 130)
        allow(8, 10)     // 加 bit1
        allow(130, 138)  // 加 bit3，不丢位
    }

    @Test
    fun rewrite_with_same_value_is_allowed() {
        for (v in intArrayOf(2, 8, 10, 130, 264)) allow(v, v)
    }

    @Test
    fun folded_bit_loss_alone_is_not_a_visibility_loss() {
        // 130 = 128|2 → 2 只丢折叠位：bit1 还在，时间线照常可见 ⇒ 不拦
        allow(130, 2)
    }

    @Test
    fun unknown_old_value_never_blocks_nonzero_write() {
        // 查不到旧值就证明不了降位；这种时候「多拦」会把动态写不进相册，代价不对等
        for (n in intArrayOf(2, 6, 8, 10, 130, 264)) allow(null, n)
    }

    // ------------------------------------------------------------------ 快速排除

    @Test
    fun needs_old_only_when_a_watched_bit_is_missing() {
        assertFalse("10 同时含 bit1+bit3，一定没丢位", SnsBitLogic.needsOld(10))
        assertFalse("0 由旧实现直接拦，不需要旧值", SnsBitLogic.needsOld(0))
        assertTrue(SnsBitLogic.needsOld(2))
        assertTrue(SnsBitLogic.needsOld(8))
        assertTrue(SnsBitLogic.needsOld(130))
        assertTrue(SnsBitLogic.needsOld(264))
        // 必要性守门：needsOld=false 时必须真的放行（否则热路径会漏判）
        // ⚠️ 0 不在其中：它 needsOld=false 但**必须拦**（旧实现的写 0 路径）
        for (v in intArrayOf(10, 138)) {
            if (!SnsBitLogic.needsOld(v)) {
                assertEquals(SnsBitLogic.Action.ALLOW, SnsBitLogic.decide(10, v))
            }
        }
    }

    // ------------------------------------------------------------------ execSQL 形态

    @Test
    fun sql_clear_bit1_is_recognized() {
        val kinds = listOf(
            "UPDATE SnsInfo SET sourceType = sourceType & -3 WHERE snsId=?",
            "update SnsInfo set sourceType=sourceType&-3 where userName=?",
            "UPDATE SnsInfo SET sourceType = (sourceType & (-3)) WHERE 1",
        )
        for (s in kinds) {
            assertEquals(s, SnsBitLogic.SqlClear.TIMELINE, SnsBitLogic.sqlClear(s))
        }
    }

    @Test
    fun sql_clear_bit3_is_recognized() {
        val kinds = listOf(
            "UPDATE SnsInfo SET sourceType = sourceType & -9 WHERE snsId=?",
            "update SnsInfo set sourceType=sourceType&-9 where userName='x'",
            // 真机那条裸 SQL 的 WHERE 枚举里含 10（见 _r42_bit_paths.md §5）
            "UPDATE SnsInfo SET sourceType = sourceType & -9 WHERE userName=? AND " +
                "sourceType IN (8,264,10,266,12,268)",
        )
        for (s in kinds) {
            assertEquals(s, SnsBitLogic.SqlClear.ALBUM, SnsBitLogic.sqlClear(s))
        }
    }

    @Test
    fun sql_with_both_masks_is_reported_as_both() {
        // 两处都是「赋值」形态（WHERE 里的 `sourceType & -9` 只是过滤条件，不算清位）
        val s = "UPDATE SnsInfo SET sourceType = sourceType & -9, localFlag = 0 " +
            "WHERE snsId = 1 AND sourceType = sourceType & -3"
        assertEquals(SnsBitLogic.SqlClear.BOTH, SnsBitLogic.sqlClear(s))
        // 反向守门：只有 WHERE 里出现 `& -9` 时不能当成清 bit3
        assertEquals(
            SnsBitLogic.SqlClear.TIMELINE,
            SnsBitLogic.sqlClear(
                "UPDATE SnsInfo SET sourceType = sourceType & -3 WHERE (sourceType & -9) = 0"
            ),
        )
    }

    @Test
    fun sql_negative_numbers_do_not_false_positive() {
        // `-90` / `-93` 不能被 `-9\b` 误认：否则计数会把完全无关的模板算成清 bit3
        assertEquals(
            SnsBitLogic.SqlClear.NONE,
            SnsBitLogic.sqlClear("UPDATE SnsInfo SET sourceType = sourceType & -90"),
        )
        assertEquals(
            SnsBitLogic.SqlClear.NONE,
            SnsBitLogic.sqlClear("UPDATE SnsInfo SET sourceType = sourceType & -93"),
        )
        // 直接赋值 -9 也不是「& -9」
        assertEquals(
            SnsBitLogic.SqlClear.NONE,
            SnsBitLogic.sqlClear("UPDATE SnsInfo SET sourceType = -9 WHERE snsId=1"),
        )
    }

    @Test
    fun sql_on_other_tables_or_other_columns_is_none() {
        assertEquals(SnsBitLogic.SqlClear.NONE, SnsBitLogic.sqlClear(null))
        assertEquals(SnsBitLogic.SqlClear.NONE, SnsBitLogic.sqlClear(""))
        assertEquals(
            SnsBitLogic.SqlClear.NONE,
            SnsBitLogic.sqlClear("UPDATE Message SET sourceType = sourceType & -9"),
        )
        assertEquals(
            SnsBitLogic.SqlClear.NONE,
            SnsBitLogic.sqlClear("UPDATE SnsInfo SET localFlag = localFlag & -9"),
        )
    }

    @Test
    fun own_fix_sql_is_recognized() {
        assertTrue(SnsBitLogic.isOwnFix("UPDATE SnsInfo SET sourceType = sourceType | 10 WHERE snsId=1"))
        assertTrue(SnsBitLogic.isOwnFix("update SnsInfo set sourceType=sourceType|2 where snsId=1"))
        assertFalse(SnsBitLogic.isOwnFix("UPDATE SnsInfo SET sourceType = sourceType | 4 WHERE snsId=1"))
        // 补位与清位不能互相误判
        assertEquals(
            SnsBitLogic.SqlClear.NONE,
            SnsBitLogic.sqlClear("UPDATE SnsInfo SET sourceType = sourceType | 10 WHERE snsId=1"),
        )
    }

    @Test
    fun looks_like_source_write_covers_update_shapes() {
        assertTrue(
            SnsBitLogic.looksLikeSourceWrite("UPDATE SnsInfo SET sourceType = 2 WHERE snsId=1")
        )
        assertTrue(
            SnsBitLogic.looksLikeSourceWrite("insert or replace into SnsInfo set sourceType=2")
        )
        assertFalse(
            SnsBitLogic.looksLikeSourceWrite("select sourceType from SnsInfo where snsId=1")
        )
        assertFalse(
            SnsBitLogic.looksLikeSourceWrite("UPDATE Message SET sourceType = 2")
        )
    }
}
