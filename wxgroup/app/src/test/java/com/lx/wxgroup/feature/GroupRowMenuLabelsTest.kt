package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分组行长按菜单末项文案映射（R60）的 JVM 回归。
 *
 * 核心是守住红线①：gconv=0 时文案必须与改造前逐字相同（旧自绘分组行仍用它）。
 * 两条分支互斥，不存在第三个取值。
 */
class GroupRowMenuLabelsTest {

    @Test
    fun offKeepsLegacyLabel() {
        // 红线①：GCONV_MODE=false 零差异 —— 关着新模式的用户看到的必须还是旧文案
        assertEquals("删除分组", GroupRowMenuLabels.delete(false))
        assertEquals("已删除分组", GroupRowMenuLabels.deleted(false))
    }

    @Test
    fun onAlignsWithMicroX() {
        // gconv=1：对齐微X「解散聊天分组」
        assertEquals("解散聊天分组", GroupRowMenuLabels.delete(true))
        assertEquals("已解散聊天分组", GroupRowMenuLabels.deleted(true))
    }

    @Test
    fun twoBranchesAreMutuallyExclusive() {
        // 互斥判据：同一开关下菜单文案与 toast 文案必须同向，不能一个对齐一个没对齐
        assertEquals(
            GroupRowMenuLabels.delete(true) == GroupRowMenuLabels.DISSOLVE,
            GroupRowMenuLabels.deleted(true) == "已解散聊天分组"
        )
        assertEquals(
            GroupRowMenuLabels.delete(false) == GroupRowMenuLabels.DELETE,
            GroupRowMenuLabels.deleted(false) == "已删除分组"
        )
    }

    // ----------------------------------------------------------- R61 按 gid 分流菜单项集合

    @Test
    fun realSessionGetsJoinAndReadOnly() {
        // 普通会话零差异：真实 wxid 仍是「加入分组 + 标记已读」两项
        val set = GroupRowMenuLabels.itemSet(null)
        assertEquals(listOf("加入分组", "标记已读"), set.items.map { it.label })
    }

    @Test
    fun pseudoSessionGetsDissolveOnly() {
        // 伪会话只插「解散聊天分组」1 项；误插 join 会让本判据变红（M2 变异守）
        val set = GroupRowMenuLabels.itemSet(42L)
        assertEquals(listOf("解散聊天分组"), set.items.map { it.label })
    }

    @Test
    fun menuSetsAreMutuallyExclusive() {
        // 两条分支互斥：真实会话集合不得含解散；伪会话集合不得含 join/read
        val real = GroupRowMenuLabels.itemSet(null).items
        val pseudo = GroupRowMenuLabels.itemSet(1L).items
        assertEquals(false, GroupRowMenuLabels.MenuItem.DISSOLVE in real)
        assertEquals(false, GroupRowMenuLabels.MenuItem.JOIN in pseudo)
        assertEquals(false, GroupRowMenuLabels.MenuItem.READ in pseudo)
        assertEquals(false, real == pseudo)
    }

    @Test
    fun dissolveLabelIsSingleSourceOfTruth() {
        // 菜单项标签必须与 R60 的判据常量同源，防止两处文案漂移
        assertEquals(GroupRowMenuLabels.DISSOLVE, GroupRowMenuLabels.MenuItem.DISSOLVE.label)
    }
}
