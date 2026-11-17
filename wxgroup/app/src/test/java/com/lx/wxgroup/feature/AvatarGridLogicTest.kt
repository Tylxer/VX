package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2×2 拼图头像「取哪 4 个人」判据（[AvatarGridLogic]）的 JVM 回归。
 *
 * 重点不是「挑得好看」，而是**同一个成员集合永远挑出同一组人** ——
 * 顺序不稳的表现是「分组行头像每隔几秒换一组脸」，而且只在 rebuild 之后才出现，
 * 真机上极难复现定位。所以这里专门用不同插入顺序的 Set 交叉验证。
 */
class AvatarGridLogicTest {

    private fun setOf(vararg ids: String): Set<String> = HashSet(ids.toList())

    @Test
    fun `成员不足 4 人返回空列表`() {
        assertTrue(AvatarGridLogic.order(setOf("a", "b", "c"), "a").isEmpty())
        assertTrue(AvatarGridLogic.order(emptySet(), "").isEmpty())
        // 边界：3 人 + 最后说话人在组内，也仍然是空（不做 3 格留白拼图）
        assertTrue(AvatarGridLogic.order(setOf("a", "b", "c"), "b").isEmpty())
    }

    @Test
    fun `恰好 4 人且最后说话人在组内 —— 第一格是他，四人不重不漏`() {
        val wx = setOf("d", "c", "b", "a")
        val out = AvatarGridLogic.order(wx, "c")
        assertEquals(4, out.size)
        assertEquals("c", out[0])
        assertEquals(wx, out.toSet())
    }

    @Test
    fun `超过 4 人 —— 第一格是最后说话人，其余按 wxid 升序补齐`() {
        val wx = setOf("f", "e", "d", "c", "b", "a")
        // 规则是「按升序逐个尝试、跳过已选的」：c 先占第一格，然后 a、b、c(跳)、d
        assertEquals(listOf("c", "a", "b", "d"), AvatarGridLogic.order(wx, "c"))
    }

    @Test
    fun `最后说话人不在组内 —— 不塞进去，退化为纯升序前 4`() {
        val wx = setOf("e", "d", "c", "b", "a")
        assertEquals(listOf("a", "b", "c", "d"), AvatarGridLogic.order(wx, "zzz"))
    }

    @Test
    fun `最后说话人为空白 —— 退化为纯升序前 4`() {
        val wx = setOf("e", "d", "c", "b", "a")
        assertEquals(listOf("a", "b", "c", "d"), AvatarGridLogic.order(wx, ""))
        assertEquals(listOf("a", "b", "c", "d"), AvatarGridLogic.order(wx, "   "))
    }

    @Test
    fun `顺序稳定性 —— 插入顺序不同的 Set 结果必须逐字相同`() {
        val asc = LinkedHashSet(listOf("a", "b", "c", "d", "e", "f"))
        val desc = LinkedHashSet(listOf("f", "e", "d", "c", "b", "a"))
        val shuffled = LinkedHashSet(listOf("d", "a", "f", "c", "e", "b"))
        val a1 = AvatarGridLogic.order(asc, "e")
        val a2 = AvatarGridLogic.order(desc, "e")
        val a3 = AvatarGridLogic.order(shuffled, "e")
        assertEquals(a1, a2)
        assertEquals(a1, a3)
        assertEquals("e", a1[0])
    }

    @Test
    fun `结果长度恒为 4 且无重复`() {
        val wx = HashSet((1..50).map { "wxid_%02d".format(it) })
        val out = AvatarGridLogic.order(wx, "wxid_37")
        assertEquals(4, out.size)
        assertEquals(4, out.toSet().size)
        assertTrue(out.all { it in wx })
        assertEquals("wxid_37", out[0])
    }
}
