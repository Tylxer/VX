package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K7 布局算法的测试。
 *
 * 这块的 bug 表现是「点开错人」，而 hook 代码只能上真机验证 ——
 * 所以把算法抽成纯 Kotlin（[ConvLayout]）在这里逐个用例钉死。
 */
class ConvLayoutTest {

    private fun convs(vararg wxids: String) = wxids.map { ConvLayout.ConvEntry(it) }

    private fun group(id: Long, name: String = "g$id", pinned: Boolean = false, order: Int = 0) =
        ConvLayout.GroupRowSpec(id, name, pinned, order)

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
        // 关掉开关必须跟微信原样，一个多余的行都不能有
        for (i in 0 until 3) assertEquals(i, l.convIndexOf(i))
    }

    @Test
    fun no_groups_passthrough() {
        val l = ConvLayout.build(convs("a", "b"), emptyList(), emptyMap())
        assertEquals(2, l.size)
        assertEquals(0, l.convIndexOf(0))
        assertEquals(1, l.convIndexOf(1))
    }

    /** 规则 1：会话之间的相对顺序永远不变，插入的只是分组行 */
    @Test
    fun conversation_order_never_changes() {
        val c = convs("a", "b", "c", "d")
        val l = ConvLayout.build(
            c,
            listOf(group(1), group(2)),
            mapOf(1L to setOf("a"), 2L to setOf("c"))
        )
        val seen = l.rows.filterIsInstance<ConvLayout.ConvRow.Conv>().map { c[it.index].wxid }
        assertEquals(listOf("a", "b", "c", "d"), seen)
    }

    // ------------------------------------------------------------ 插入位置

    /** 分组行插在该组最新一条会话之前（会话是时间降序） */
    @Test
    fun group_row_sits_before_newest_member() {
        val c = convs("x", "y", "z", "w")
        val l = ConvLayout.build(c, listOf(group(7)), mapOf(7L to setOf("z")))
        // z 在下标 2，所以分组行应该在下标 2，z 被挤到 3
        assertTrue(l.isGroupRow(2))
        assertEquals(7L, l.groupIdAt(2))
        assertEquals(2, l.convIndexOf(3))
        assertEquals("z", c[l.convIndexOf(3)].wxid)
    }

    @Test
    fun group_without_conversation_goes_to_tail() {
        val c = convs("a", "b")
        val l = ConvLayout.build(c, listOf(group(1)), mapOf(1L to setOf("nobody")))
        assertEquals(3, l.size)
        assertTrue(l.isGroupRow(2))
        assertEquals(1L, l.groupIdAt(2))
    }

    @Test
    fun pinned_group_goes_to_head() {
        val c = convs("a", "b", "c")
        val l = ConvLayout.build(
            c,
            listOf(group(1), group(2, pinned = true)),
            mapOf(1L to setOf("b"), 2L to setOf("c"))
        )
        // 置顶的组在最前，另一个组按自己的锚点插在 b 之前
        assertEquals(2L, l.groupIdAt(0))
        assertEquals("a", c[l.convIndexOf(1)].wxid)
        assertEquals(1L, l.groupIdAt(2))
        assertEquals("b", c[l.convIndexOf(3)].wxid)
    }

    @Test
    fun multiple_groups_keep_sort_order_at_same_anchor() {
        val c = convs("a", "b")
        val l = ConvLayout.build(
            c,
            listOf(group(1, order = 1), group(2, order = 0)),
            mapOf(1L to setOf("a"), 2L to setOf("a"))
        )
        // 两组都锚在 a 上：sort_order 小的在前
        assertEquals(2L, l.groupIdAt(0))
        assertEquals(1L, l.groupIdAt(1))
        assertEquals("a", c[l.convIndexOf(2)].wxid)
        assertEquals("b", c[l.convIndexOf(3)].wxid)
    }

    // ------------------------------------------------------------ 位置映射

    /** 点击换算：分组行 -1，会话行必须精确对应原始下标 */
    @Test
    fun click_position_maps_back_exactly() {
        val c = convs("a", "b", "c", "d")
        val l = ConvLayout.build(
            c,
            listOf(group(1), group(2)),
            mapOf(1L to setOf("b"), 2L to setOf("d"))
        )
        // 期望：a, [1], b, c, [2], d
        assertEquals(6, l.size)
        val expected = listOf("a" to false, null to true, "b" to false, "c" to false, null to true, "d" to false)
        expected.forEachIndexed { pos, (wxid, isGroup) ->
            assertEquals("pos=$pos isGroup", isGroup, l.isGroupRow(pos))
            if (isGroup) {
                assertEquals(-1, l.convIndexOf(pos))
            } else {
                assertEquals("pos=$pos wxid", wxid, c[l.convIndexOf(pos)].wxid)
            }
        }
    }

    @Test
    fun out_of_range_is_safe() {
        val l = ConvLayout.build(convs("a"), listOf(group(1)), mapOf(1L to setOf("a")))
        assertEquals(-1, l.convIndexOf(99))
        assertEquals(-1, l.convIndexOf(-1))
        assertEquals(-1L, l.groupIdAt(99))
        assertFalse(l.isGroupRow(99))
    }

    /** 一人一组：同一个人不会把两个组锚到同一条会话上造成重复插入 */
    @Test
    fun member_belongs_to_one_group_only() {
        val c = convs("shared", "other")
        val l = ConvLayout.build(
            c,
            listOf(group(1), group(2)),
            // 数据层保证独占，这里模拟「shared 只在 1 组」
            mapOf(1L to setOf("shared"), 2L to setOf("other"))
        )
        val groupRows = l.rows.filterIsInstance<ConvLayout.ConvRow.Group>().map { it.groupId }
        assertEquals(listOf(1L, 2L), groupRows)
        // 每个分组行只出现一次
        assertEquals(1, l.rows.count { it is ConvLayout.ConvRow.Group && it.groupId == 1L })
        assertEquals(1, l.rows.count { it is ConvLayout.ConvRow.Group && it.groupId == 2L })
    }
}
