package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ------------------------------------------------------------------ 写回值（R42 bit3 缺口）
    // 判据写错的代价是静默的（主页消失 / 把不可见动态复活），且变异方向多，故逐条钉死。

    private fun keep(old: Int?) = SnsBitLogic.keepAliveSource(old)

    @Test
    fun visible_mask_is_the_union_of_the_two_bits() {
        assertEquals(
            "VISIBLE_MASK 必须正好是 bit1|bit3",
            SnsBitLogic.BIT_TIMELINE or SnsBitLogic.BIT_ALBUM,
            SnsBitLogic.VISIBLE_MASK,
        )
        assertEquals(10, SnsBitLogic.VISIBLE_MASK)
    }

    @Test
    fun keep_alive_typical_four_values() {
        // team-lead 点名的四个 src：0 不复活、2/8 补成 10、10 幂等
        assertNull("old=0 死来源，不许复活", keep(0))
        assertEquals("⭐ 缺口场景 2 → 10", 10, keep(2)!!)
        assertEquals("old=8 只补 bit3 不够，要连 bit1 一起补", 10, keep(8)!!)
        assertEquals("old=10 已可见，写回是幂等 no-op", 10, keep(10)!!)
    }

    @Test
    fun keep_alive_degrades_when_bits_unreadable() {
        // 硬约束 2：bits 读不到时绝不凭空造位
        assertNull(keep(null))
    }

    @Test
    fun keep_alive_does_not_resurrect_fully_invisible_values() {
        // 两个可见位都没有 ⇒ 不写回（不制造可见性）
        for (v in intArrayOf(0, 4, 32, 128, 256, 512, 1, 64)) {
            assertNull("old=$v 两个可见位皆无，不许复活", keep(v))
        }
    }

    @Test
    fun keep_alive_preserves_high_bits() {
        // ⛔ 不是常量 10：高位必须原样保留
        assertEquals(138, keep(130)!!)  // 130=128|2 → 138=128|8|2
        assertEquals(266, keep(264)!!)  // 264=8|256 → 266=8|256|2
        assertEquals(266, keep(266)!!)  // 已含两位 ⇒ 幂等
        assertEquals(14, keep(6)!!)     // 6=2|4 私密位也保留
    }

    @Test
    fun keep_alive_is_not_a_constant() {
        assertTrue("写死 10 会抹掉 264 的 256 位", keep(264)!! != 10)
    }

    @Test
    fun keep_alive_only_adding_bit3_is_wrong() {
        // ⛔ `old or 8` 会把「只在相册」的 8 留在「时间线仍不可见」
        assertTrue(
            "old=8 只补 bit3 得 8，bit1 仍缺",
            keep(8)!! != (8 or SnsBitLogic.BIT_ALBUM),
        )
        assertEquals("old=8 必须连 bit1 一起补 ⇒ 10", 10, keep(8)!!)
    }

    @Test
    fun keep_alive_is_idempotent_and_only_adds_bits() {
        for (old in intArrayOf(2, 8, 10, 130, 138, 264, 266, 6, 14)) {
            val k = keep(old)!!
            // 幂等：再跑一次值不变（微信重试删除不会越写越大）
            assertEquals("old=$old 二次写回必须收敛", k, keep(k)!!)
            // 只加位、不删位：old 是 k 的子集
            assertEquals("old=$old 写回不许丢掉任何旧位", 0, old and k.inv())
            // 两个可见位都在
            assertEquals(SnsBitLogic.VISIBLE_MASK, k and SnsBitLogic.VISIBLE_MASK)
        }
    }

    @Test
    fun rewrite_path_keep_is_always_distinct_from_want() {
        // 会被拦的写入：want 是 0 或纯降位（缺至少一个可见位）；keep 必含两可见位 ⇒ 必不等。
        // 这条是 afterShape 第 2 类「keep != want」成立的前提，不能只靠口头约定。
        val pairs = listOf(
            0 to 2, 0 to 8, 0 to 10,   // 写 0 路径（want=0，old 含可见位）
            8 to 10, 2 to 10,          // 纯降位路径：10→8 清 bit1 / 10→2 清 bit3
        )
        for ((w, old) in pairs) {
            val k = keep(old)!!
            assertTrue("want=$w old=$old ⇒ keep=$k 必须与 want 不同", k != w)
            assertEquals(
                "want=$w keep=$k observed=$k 必须判为「拦住了·写回」",
                SnsBitLogic.AfterShape.BLOCKED_BY_REWRITE,
                SnsBitLogic.afterShape(w, k, k),
            )
        }
    }

    // ------------------------------------------------------------------ after 互斥形态判据

    @Test
    fun after_shape_removed_path_is_blocked() {
        // 降级 / 不复活路径：键被摘掉 ⇒ observed=null ⇒ 拦住了
        assertEquals(
            SnsBitLogic.AfterShape.BLOCKED_BY_REMOVE,
            SnsBitLogic.afterShape(want = 0, keep = null, observed = null),
        )
        // 即使本来算出了 keep，只要 after 读不到键就按「摘键」算（互斥优先）
        assertEquals(
            SnsBitLogic.AfterShape.BLOCKED_BY_REMOVE,
            SnsBitLogic.afterShape(want = 2, keep = 10, observed = null),
        )
    }

    @Test
    fun after_shape_rewrite_path_is_blocked() {
        assertEquals(
            SnsBitLogic.AfterShape.BLOCKED_BY_REWRITE,
            SnsBitLogic.afterShape(want = 0, keep = 10, observed = 10),
        )
        assertEquals(
            SnsBitLogic.AfterShape.BLOCKED_BY_REWRITE,
            SnsBitLogic.afterShape(want = 8, keep = 10, observed = 10),
        )
        assertEquals(
            SnsBitLogic.AfterShape.BLOCKED_BY_REWRITE,
            SnsBitLogic.afterShape(want = 2, keep = 10, observed = 10),
        )
    }

    @Test
    fun after_shape_not_blocked_when_want_still_present() {
        // 防御性守门：记了 BLOCKED 但 ContentValues 里仍是微信原值 ⇒ 没拦住（不许误报成功）
        assertEquals(
            SnsBitLogic.AfterShape.NOT_BLOCKED,
            SnsBitLogic.afterShape(want = 0, keep = null, observed = 0),
        )
        assertEquals(
            SnsBitLogic.AfterShape.NOT_BLOCKED,
            SnsBitLogic.afterShape(want = 8, keep = 10, observed = 8),
        )
    }

    @Test
    fun after_shape_unknown_is_not_silently_swallowed() {
        // 形态异常：值既不是 want 也不是 keep ⇒ 单独一类，不许归到拦住/没拦住
        assertEquals(
            SnsBitLogic.AfterShape.UNKNOWN,
            SnsBitLogic.afterShape(want = 0, keep = 10, observed = 6),
        )
        assertEquals(
            SnsBitLogic.AfterShape.UNKNOWN,
            SnsBitLogic.afterShape(want = 8, keep = null, observed = 10),
        )
    }

    @Test
    fun after_shape_keep_equals_want_is_not_rewritten() {
        // ⭐ 互斥前提：keep==want 时第 2 类不得命中，否则与 NOT_BLOCKED 同时为真。
        // 这是「keep != want」守卫的专用反例（变异 M5 会把它变红）。
        assertEquals(
            SnsBitLogic.AfterShape.NOT_BLOCKED,
            SnsBitLogic.afterShape(want = 10, keep = 10, observed = 10),
        )
        assertEquals(
            SnsBitLogic.AfterShape.NOT_BLOCKED,
            SnsBitLogic.afterShape(want = 2, keep = 2, observed = 2),
        )
    }

    @Test
    fun after_shape_is_mutually_exclusive_and_total() {
        // 穷举一组输入：每个组合恰好落一类，且三个「证据」不能有两个同时为真
        val wants = intArrayOf(0, 2, 8, 10, 130)
        val keeps = arrayOf<Int?>(null, 2, 8, 10, 130, 138)
        val observed = arrayOf<Int?>(null, 0, 2, 8, 10, 130, 138, 6)
        var blocked = 0
        var notBlocked = 0
        var unknown = 0
        for (w in wants) for (k in keeps) for (ob in observed) {
            val s = SnsBitLogic.afterShape(w, k, ob)
            val eRemove = ob == null
            val eRewrite = k != null && k != w && ob == k
            val eIntact = ob != null && ob == w
            val evidence = listOf(eRemove, eRewrite, eIntact).count { it }
            assertTrue("want=$w keep=$k observed=$ob 出现两个以上证据", evidence <= 1)
            when (s) {
                SnsBitLogic.AfterShape.BLOCKED_BY_REMOVE,
                SnsBitLogic.AfterShape.BLOCKED_BY_REWRITE -> blocked++
                SnsBitLogic.AfterShape.NOT_BLOCKED -> notBlocked++
                SnsBitLogic.AfterShape.UNKNOWN -> unknown++
            }
        }
        assertTrue("拦住了的样本必须存在", blocked > 0)
        assertTrue("没拦住的样本必须存在", notBlocked > 0)
        assertTrue("异常样本必须存在（说明补集非空、不是死分支）", unknown > 0)
    }
}
