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

    // ------------------------------------------------------------ orderChanged（分层失效判据）

    /** 长度不同：条数变化必须重算 layout */
    @Test
    fun orderChanged_different_length_returns_true() {
        assertTrue(ConvLayout.orderChanged(convs("a", "b"), convs("a", "b", "c")))
        assertTrue(ConvLayout.orderChanged(convs("a", "b", "c"), convs("a", "b")))
    }

    /** 条数相同、wxid 顺序不同：必须返回 true，否则 layout 冻结 → 点错人 */
    @Test
    fun orderChanged_reordered_returns_true() {
        assertTrue(ConvLayout.orderChanged(convs("a", "b", "c"), convs("b", "a", "c")))
        assertTrue(ConvLayout.orderChanged(convs("a", "b", "c"), convs("c", "b", "a")))
    }

    /** 只有 timeMs 变、顺序不变：layout 可复用，必须返回 false（timeMs 不参与顺序判据） */
    @Test
    fun orderChanged_same_order_different_time_returns_false() {
        val old = listOf(
            ConvLayout.ConvEntry("a", 100),
            ConvLayout.ConvEntry("b", 200),
            ConvLayout.ConvEntry("c", 300),
        )
        val new = listOf(
            ConvLayout.ConvEntry("a", 999),
            ConvLayout.ConvEntry("b", 888),
            ConvLayout.ConvEntry("c", 777),
        )
        assertFalse(ConvLayout.orderChanged(old, new))
    }

    /** 完全相同：返回 false */
    @Test
    fun orderChanged_identical_returns_false() {
        val a = listOf(ConvLayout.ConvEntry("a", 1), ConvLayout.ConvEntry("b", 2))
        val b = listOf(ConvLayout.ConvEntry("a", 1), ConvLayout.ConvEntry("b", 2))
        assertFalse(ConvLayout.orderChanged(a, b))
    }

    /** 空列表边界 */
    @Test
    fun orderChanged_empty_boundaries() {
        assertFalse(ConvLayout.orderChanged(convs(), convs()))
        assertTrue(ConvLayout.orderChanged(convs(), convs("a")))
        assertTrue(ConvLayout.orderChanged(convs("a"), convs()))
    }

    /**
     * 空 wxid 占位 = 顺序未知 ⇒ 必须返 true（保守强制重算）。
     *
     * `readSnaps` 在 `getItem(i)` 返回 null 时塞 `ConvEntry("")` 占位以对齐下标。
     * 「两次都读到空」不代表底层没变 —— 只是两次都没读到，那条会话可能已经换人。
     * 若把空串当普通值比（`"" == ""` → false），layout 不重算 ⇒ 该行按旧下标渲染
     * ⇒ 又变成「点错人」。这条用例就是钉死这个保守判据。
     */
    @Test
    fun orderChanged_blank_wxid_forces_true() {
        // 两边都在同一位置读到 null 占位：仍然要重算
        assertTrue(ConvLayout.orderChanged(convs("a", "", "c"), convs("a", "", "c")))
        // 只有一边是空占位：同样必须重算
        assertTrue(ConvLayout.orderChanged(convs("a", "", "c"), convs("a", "b", "c")))
        assertTrue(ConvLayout.orderChanged(convs("a", "b", "c"), convs("a", "", "c")))
        // 首尾位置也要覆盖（不能只判中间）
        assertTrue(ConvLayout.orderChanged(convs("", "b"), convs("", "b")))
        assertTrue(ConvLayout.orderChanged(convs("a", ""), convs("a", "")))
    }

    // ------------------------------------------------------------ R31 折叠置顶聊天

    private fun foldFixture(): Triple<
        List<ConvLayout.ConvEntry>,
        List<ConvLayout.GroupRowSpec>,
        Map<Long, Set<String>>
        > = Triple(
        convs("a", "b", "c", "d"),
        listOf(group(1, "p", pinned = true, order = 0), group(2, "n", pinned = false, order = 1)),
        mapOf(1L to setOf("a", "b"), 2L to setOf("c")),
    )

    /**
     * 折叠态下置顶分组行必须消失。
     *
     * 微信折叠「置顶聊天」时把置顶区收进「N个置顶聊天」一行；置顶分组行如果还插在
     * 最前，就会孤零零地停在那一行上面 —— 用户看到的就是「分组行没被折叠收进去」。
     */
    @Test
    fun pinFolded_hides_pinned_group_rows() {
        val (c, g, m) = foldFixture()
        val unfolded = ConvLayout.build(c, g, m, pinFolded = false)
        val folded = ConvLayout.build(c, g, m, pinFolded = true)

        assertEquals("G:1 | G:2 | C:d", unfolded.dump(c))
        // 置顶组 1 的行没了，非置顶组 2 的行原样保留
        assertEquals("G:2 | C:d", folded.dump(c))
    }

    /** 默认参数（不传 pinFolded）必须与「展开态」逐字节一致 —— 防回归的关键用例。 */
    @Test
    fun pinFolded_defaultEqualsUnfolded() {
        val (c, g, m) = foldFixture()
        val byDefault = ConvLayout.build(c, g, m, enabled = true)
        val explicit = ConvLayout.build(c, g, m, enabled = true, pinFolded = false)
        assertEquals(explicit.dump(c), byDefault.dump(c))
        assertEquals(explicit.size, byDefault.size)
    }

    /** 没有置顶分组时，折叠态与展开态完全等价（不能因为开关状态影响别的东西）。 */
    @Test
    fun pinFolded_withoutPinnedGroupsIsNoop() {
        val c = convs("a", "b", "c")
        val g = listOf(group(1, "n1", pinned = false, order = 0))
        val m = mapOf(1L to setOf("a"))
        assertEquals(
            ConvLayout.build(c, g, m, pinFolded = false).dump(c),
            ConvLayout.build(c, g, m, pinFolded = true).dump(c),
        )
    }

    /**
     * 折叠只藏「分组行」，**不许**因此把置顶组的成员会话放出来。
     *
     * 收纳语义（一人只属于一组、成员会话恒被隐藏）跟折叠无关；如果折叠时把 a/b
     * 放出来，用户会看到同一批人既在分组里又在列表里，属于数据错乱。
     */
    @Test
    fun pinFolded_doesNotLeakPinnedMemberConvs() {
        val (c, g, m) = foldFixture()
        val folded = ConvLayout.build(c, g, m, pinFolded = true)
        val dump = folded.dump(c)
        assertFalse(dump, dump.contains("C:a"))
        assertFalse(dump, dump.contains("C:b"))
        // 也不许把「空行」留在原处：置顶组占的位置必须真的被回收
        assertEquals(2, folded.size)
    }

    /** 折叠 + 筛选态：`filtered()` 本来就不插分组行，与折叠无关，这里钉死不回归。 */
    @Test
    fun pinFolded_doesNotAffectFilteredPath() {
        val c = convs("a", "b", "c")
        val f = ConvLayout.filtered(c, setOf("a", "c"))
        assertEquals(2, f.size)
        assertEquals("C:a | C:c", f.dump(c))
    }
}
