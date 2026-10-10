package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K7 布局算法的测试。
 *
 * 这块的 bug 表现是「点开错人」或者「列表变空白」，而 hook 代码只能上真机验证 ——
 * 所以把算法抽成纯 Kotlin（[ConvLayout]）在这里逐个用例钉死。
 *
 * 语义是 **A6 收纳**：已归组的会话从主列表消失，由分组行代表它。
 * 不是「插一行」那么简单 —— 会话会被真的抽掉。
 */
class ConvLayoutTest {

    private fun convs(vararg wxids: String) = wxids.map { ConvLayout.ConvEntry(it) }

    private fun group(id: Long, name: String = "g$id", pinned: Boolean = false, order: Int = 0) =
        ConvLayout.GroupRowSpec(id, name, pinned, order)

    /** 把布局渲染成人能看懂的样子，断言失败时一眼看出差在哪 */
    private fun ConvLayout.Layout.dump(c: List<ConvLayout.ConvEntry>): String =
        (0 until size).joinToString(" | ") { i ->
            val idx = convIndexOf(i)
            if (idx >= 0) "C:${c[idx].wxid}" else "G:${groupIdAt(i)}"
        }

    // ------------------------------------------------------------ 最小侵入

    @Test
    fun disabled_passthrough() {
        val c = convs("a", "b", "c")
        val l = ConvLayout.build(
            c,
            listOf(group(1)),
            mapOf(1L to setOf("a")),
            enabled = false
        )
        assertEquals(3, l.size)
        // 关掉开关必须跟微信原样：一个分组行不能有，一条会话也不许少
        for (i in 0 until 3) assertEquals(i, l.convIndexOf(i))
        assertEquals("C:a | C:b | C:c", l.dump(c))
    }

    @Test
    fun no_groups_passthrough() {
        val c = convs("a", "b")
        val l = ConvLayout.build(c, emptyList(), emptyMap())
        assertEquals(2, l.size)
        assertEquals(0, l.convIndexOf(0))
        assertEquals(1, l.convIndexOf(1))
    }

    @Test
    fun group_with_empty_members_still_shows_its_row() {
        // 空分组：一个会话都不该被隐藏，但分组行自己要出现（排末尾）
        val c = convs("a", "b")
        val l = ConvLayout.build(c, listOf(group(1)), mapOf(1L to emptySet()))
        assertEquals(3, l.size)
        assertEquals("C:a | C:b | G:1", l.dump(c))
    }

    // ------------------------------------------------------------ A6 收纳语义

    /** 核心：已归组的会话消失，分组行取代其中最新的那一条 */
    @Test
    fun grouped_conversations_are_retracted() {
        val c = convs("a", "b", "c", "d")
        val l = ConvLayout.build(
            c,
            listOf(group(1), group(2)),
            mapOf(1L to setOf("b", "c"), 2L to setOf("d"))
        )
        // b、c 同属组 1，整组只占一行（取代 b）；d 被组 2 取代；a 没归组原样保留
        assertEquals("C:a | G:1 | G:2", l.dump(c))
        assertEquals(3, l.size)
    }

    /** 规则 1：没归组的会话之间相对顺序永远不变 */
    @Test
    fun ungrouped_conversation_order_never_changes() {
        val c = convs("a", "b", "c", "d", "e")
        val l = ConvLayout.build(
            c,
            listOf(group(1)),
            mapOf(1L to setOf("b", "d"))   // 抽掉 b、d
        )
        val seen = (0 until l.size)
            .map { l.convIndexOf(it) }
            .filter { it >= 0 }
            .map { c[it].wxid }
        assertEquals(listOf("a", "c", "e"), seen)
    }

    /** 整组收纳后，列表不可能比原来更长 */
    @Test
    fun retracted_list_never_grows() {
        val c = convs("a", "b", "c", "d", "e", "f")
        val l = ConvLayout.build(
            c,
            listOf(group(1), group(2)),
            mapOf(1L to setOf("a", "b", "c"), 2L to setOf("d", "e", "f"))
        )
        // 6 条会话全被两个组收走 → 只剩 2 行分组行
        assertEquals(2, l.size)
        assertEquals("G:1 | G:2", l.dump(c))
    }

    // ------------------------------------------------------------ A2 位置

    /** 分组行占据该组最新一条会话的位置（会话按时间降序 = 下标最小） */
    @Test
    fun group_row_takes_place_of_newest_member() {
        val c = convs("x", "y", "z", "w")
        val l = ConvLayout.build(c, listOf(group(7)), mapOf(7L to setOf("z", "w")))
        // z 最新（下标 2）→ 分组行取代它；w 被隐藏
        assertEquals(3, l.size)
        assertEquals("C:x | C:y | G:7", l.dump(c))
        assertTrue(l.isGroupRow(2))
        assertEquals(7L, l.groupIdAt(2))
    }

    @Test
    fun group_without_conversation_goes_to_tail() {
        val c = convs("a", "b")
        val l = ConvLayout.build(c, listOf(group(1)), mapOf(1L to setOf("nobody")))
        assertEquals("C:a | C:b | G:1", l.dump(c))
    }

    @Test
    fun tail_groups_keep_sort_order() {
        val c = convs("a")
        val l = ConvLayout.build(
            c,
            listOf(group(1, order = 2), group(2, order = 1)),
            mapOf(1L to setOf("x"), 2L to setOf("y"))
        )
        assertEquals("C:a | G:2 | G:1", l.dump(c))
    }

    // ------------------------------------------------------------ A13 置顶

    @Test
    fun pinned_group_goes_to_head() {
        val c = convs("a", "b", "c")
        val l = ConvLayout.build(
            c,
            listOf(group(1), group(2, pinned = true)),
            mapOf(1L to setOf("b"), 2L to setOf("c"))
        )
        // 置顶组在最前；它的成员会话 c 被隐藏；组 1 取代 b
        assertEquals("G:2 | C:a | G:1", l.dump(c))
    }

    @Test
    fun pinned_groups_keep_sort_order() {
        val c = convs("a")
        val l = ConvLayout.build(
            c,
            listOf(group(1, pinned = true, order = 2), group(2, pinned = true, order = 1)),
            mapOf(1L to setOf("a"), 2L to setOf("nobody"))
        )
        assertEquals("G:2 | G:1", l.dump(c))
    }

    // ------------------------------------------------------------ 位置映射

    /** 点击换算：分组行必须返回 -1，会话行必须精确对应原始下标 */
    @Test
    fun click_position_maps_back_exactly() {
        val c = convs("a", "b", "c", "d")
        val l = ConvLayout.build(
            c,
            listOf(group(1), group(2)),
            mapOf(1L to setOf("b"), 2L to setOf("d"))
        )
        assertEquals("C:a | G:1 | C:c | G:2", l.dump(c))
        // 逐位核对：分组行的 convIndexOf 必须是 -1，绝不能转交给微信
        assertEquals(0, l.convIndexOf(0))
        assertEquals(-1, l.convIndexOf(1))
        assertEquals(2, l.convIndexOf(2))
        assertEquals(-1, l.convIndexOf(3))
        // 分组行 id 也要对得上
        assertEquals(1L, l.groupIdAt(1))
        assertEquals(2L, l.groupIdAt(3))
    }

    /** 会话行的 groupIdAt 必须是哨兵值，不是 0 也不是 -1 */
    @Test
    fun conv_row_reports_not_a_group_sentinel() {
        val c = convs("a", "b")
        val l = ConvLayout.build(c, listOf(group(1)), mapOf(1L to setOf("b")))
        assertEquals(ConvLayout.Layout.NOT_A_GROUP, l.groupIdAt(0))
        assertFalse(l.isGroupRow(0))
    }

    /**
     * 回归：分组 id 是负数时也必须被认成分组行。
     *
     * 旧实现用 `groupIdAt(pos) >= 0` 判断，负 id 的行被误判成会话行 →
     * 拿不到下标 → getView 返回 null → 那一行永远画不出来，
     * 用户被卡死在空白列表上（真机实测踩到）。
     */
    @Test
    fun negative_group_id_is_still_a_group_row() {
        val c = convs("a")
        val l = ConvLayout.build(c, listOf(group(-2L)), mapOf(-2L to setOf("a")))
        assertTrue("负 id 分组行必须被识别", l.isGroupRow(0))
        assertEquals(-2L, l.groupIdAt(0))
        assertEquals(-1, l.convIndexOf(0))
    }

    @Test
    fun out_of_range_is_safe() {
        val l = ConvLayout.build(convs("a"), listOf(group(1)), mapOf(1L to setOf("a")))
        assertEquals(-1, l.convIndexOf(99))
        assertEquals(-1, l.convIndexOf(-1))
        assertEquals(ConvLayout.Layout.NOT_A_GROUP, l.groupIdAt(99))
        assertFalse(l.isGroupRow(99))
    }

    // ------------------------------------------------------------ 筛选态（二级页）

    @Test
    fun filtered_shows_only_members() {
        val c = convs("a", "b", "c")
        val l = ConvLayout.filtered(c, setOf("b", "c"))
        // 没有「返回全部」行，只剩成员会话；退出筛选走系统返回键
        assertEquals("C:b | C:c", l.dump(c))
        assertEquals(2, l.size)
        assertFalse(l.isGroupRow(0))
        assertFalse(l.isGroupRow(1))
    }

    /** 坑：空分组必须过滤成 0 条，不能退化成「全部显示」 */
    @Test
    fun filtered_empty_group_shows_nothing() {
        val c = convs("a", "b", "c")
        val l = ConvLayout.filtered(c, emptySet())
        assertEquals(0, l.size)
        assertEquals("", l.dump(c))
    }

    @Test
    fun filtered_members_not_in_list() {
        val c = convs("a", "b")
        val l = ConvLayout.filtered(c, setOf("nobody"))
        assertEquals(0, l.size)
        assertEquals("", l.dump(c))
    }

    /** 筛选态里不该出现任何分组行（返回行已取消），否则会被当成导航行渲染出来 */
    @Test
    fun filtered_has_no_group_row() {
        val c = convs("a", "b", "c")
        val l = ConvLayout.filtered(c, setOf("a", "b", "c"))
        assertEquals(3, l.size)
        for (i in 0 until l.size) {
            assertEquals(ConvLayout.Layout.NOT_A_GROUP, l.groupIdAt(i))
            assertFalse(l.isGroupRow(i))
        }
    }

    /** 筛选态下位置映射也要准：命中的会话必须还原成原始下标 */
    @Test
    fun filtered_click_position_maps_back() {
        val c = convs("a", "b", "c", "d")
        val l = ConvLayout.filtered(c, setOf("c", "d"))
        // 没有返回行：第 0 行就是第一条成员会话，下标直接映射回原始列表
        assertEquals(2, l.convIndexOf(0))             // c
        assertEquals(3, l.convIndexOf(1))             // d
        assertEquals("c", c[l.convIndexOf(0)].wxid)
        assertEquals("d", c[l.convIndexOf(1)].wxid)
    }

    // ------------------------------------------------------------ 脏数据防御

    /** 一人多组（数据层不该出现，但出现了也不能插出两行） */
    @Test
    fun dirty_data_member_in_two_groups_inserts_once() {
        val c = convs("shared", "other")
        val l = ConvLayout.build(
            c,
            listOf(group(1), group(2)),
            mapOf(1L to setOf("shared"), 2L to setOf("shared", "other"))
        )
        assertEquals(1, l.rows.count { it is ConvLayout.ConvRow.Group && it.groupId == 1L })
        assertEquals(1, l.rows.count { it is ConvLayout.ConvRow.Group && it.groupId == 2L })
        // 分组行总数恰好等于分组数，不多不少
        assertEquals(2, l.rows.count { it is ConvLayout.ConvRow.Group })
    }

    /** 成员列表里的空 wxid 不能污染归属表 */
    @Test
    fun blank_wxid_is_ignored() {
        val c = convs("", "a")
        val l = ConvLayout.build(c, listOf(group(1)), mapOf(1L to setOf("", " ")))
        // 空串不该被算作某组的成员 → 两条会话都原样保留，分组行排末尾
        assertEquals("C: | C:a | G:1", l.dump(c))
    }

    /** 分组表里没有对应 members 条目时，视作空组排末尾 */
    @Test
    fun group_missing_from_members_map_goes_to_tail() {
        val c = convs("a", "b")
        val l = ConvLayout.build(c, listOf(group(1), group(2)), mapOf(1L to setOf("a")))
        assertEquals("G:1 | C:b | G:2", l.dump(c))
    }
}
