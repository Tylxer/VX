package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatroomSortLogic] 的判据 + 稳定性回归测试（R29-4）。
 *
 * 场景来自真机：群聊 Tab 里大量群 `remark` / `nickname` 都是空串（或只有空格），
 * 微信源表按 `remark, nickname` 升序排 → 空串排最前 → 进 Tab 满屏数字群号。
 * 这里钉死两件事：① 空白的判据；② 排序是**稳定**的（组内不许乱序）。
 *
 * 用测试内自带的 [Room] 而不是 [Friend]，避免把 Android 依赖拖进 JVM 单测。
 */
class ChatroomSortLogicTest {

    /** 群聊的最小投影，只保留判据/排序关心的字段 */
    private data class Room(
        val wxid: String,
        val remark: String = "",
        val nickname: String = "",
    )

    private fun sort(rooms: List<Room>): List<Room> =
        ChatroomSortLogic.sortNamedFirst(rooms, remarkOf = { it.remark }, nicknameOf = { it.nickname })

    private fun ids(rooms: List<Room>): List<String> = rooms.map { it.wxid }

    // ---------------------------------------------------------------- 判据

    @Test
    fun remarkOnlyIsNamed() {
        // remark 有值、nickname 空 —— 最典型的「用户起了群名」
        assertTrue(ChatroomSortLogic.hasName(remark = "家人群", nickname = ""))
        assertEquals(0, ChatroomSortLogic.sortKey("家人群", ""))
    }

    @Test
    fun nicknameOnlyIsNamed() {
        assertTrue(ChatroomSortLogic.hasName(remark = "", nickname = "项目组"))
        assertEquals(0, ChatroomSortLogic.sortKey("", "项目组"))
    }

    @Test
    fun blankBothIsUnnamed() {
        assertFalse(ChatroomSortLogic.hasName("", ""))
        assertEquals(1, ChatroomSortLogic.sortKey("", ""))
    }

    @Test
    fun whitespaceOnlyCountsAsUnnamed() {
        // 纯空格 / 制表符 / 换行 —— 真机源表里确实有这种值，必须算无名
        for (blank in listOf("   ", "\t", "\n", " \t\n ")) {
            assertFalse("[$blank] 应算无名 remark", ChatroomSortLogic.hasName(blank, ""))
            assertFalse("[$blank] 应算无名 nickname", ChatroomSortLogic.hasName("", blank))
            assertEquals("[$blank] 的排序键应为无名", 1, ChatroomSortLogic.sortKey(blank, blank))
        }
    }

    @Test
    fun surroundingSpacesAndCaseStillNamed() {
        // 首尾空格会被 trim 掉，不是「空白」；大小写不影响「有没有名字」这件事
        assertTrue(ChatroomSortLogic.hasName("  家人群  ", ""))
        assertTrue(ChatroomSortLogic.hasName("", "  TeamAlpha  "))
        assertTrue(ChatroomSortLogic.hasName("ABC", ""))
        assertTrue(ChatroomSortLogic.hasName("abc", ""))
        assertEquals(0, ChatroomSortLogic.sortKey("  家人群  ", ""))
    }

    @Test
    fun remarkBlankFallsBackToNickname() {
        // remark 是纯空格 ⇒ 看 nickname；nickname 有值 ⇒ 有名
        assertTrue(ChatroomSortLogic.hasName(remark = "   ", nickname = "项目组"))
        assertEquals(0, ChatroomSortLogic.sortKey("   ", "项目组"))
    }

    // ---------------------------------------------------------------- 排序

    @Test
    fun mixedNamedGoesFirst() {
        val input = listOf(
            Room("1@chatroom", "", ""),                          // 无名
            Room("2@chatroom", "家人群", ""),                    // 有名
            Room("3@chatroom", "   ", "  "),                     // 无名（全空白）
            Room("4@chatroom", "", "项目组"),                    // 有名
            Room("5@chatroom", "", ""),                          // 无名
        )
        assertEquals(
            listOf("2@chatroom", "4@chatroom", "1@chatroom", "3@chatroom", "5@chatroom"),
            ids(sort(input)),
        )
    }

    @Test
    fun allUnnamedKeepsInputOrder() {
        val input = listOf(
            Room("1@chatroom", "", ""),
            Room("2@chatroom", "  ", ""),
            Room("3@chatroom", "", " "),
            Room("4@chatroom", "", ""),
        )
        assertEquals(ids(input), ids(sort(input)))
    }

    @Test
    fun allNamedKeepsInputOrder() {
        val input = listOf(
            Room("1@chatroom", "Zebra", ""),
            Room("2@chatroom", "", "apple"),
            Room("3@chatroom", "家人群", ""),
            Room("4@chatroom", "", "项目组"),
        )
        assertEquals(ids(input), ids(sort(input)))
    }

    @Test
    fun emptyListAndSingleItem() {
        assertTrue(sort(emptyList()).isEmpty())
        val one = listOf(Room("1@chatroom", "", ""))
        assertEquals(ids(one), ids(sort(one)))
    }

    // ---------------------------------------------------------------- 稳定性（核心）

    @Test
    fun sortIsStableForEqualKeys() {
        // 同组 3 条互不相同的输入（构造上按 wxid 递减），稳定排序必须原样保留
        val unnamed = listOf(
            Room("9@chatroom", "", ""),
            Room("7@chatroom", "", ""),
            Room("5@chatroom", "", ""),
        )
        val named = listOf(
            Room("8@chatroom", "群C", ""),
            Room("6@chatroom", "", "群B"),
            Room("4@chatroom", "群A", ""),
        )
        // 交错输入，验证的不是「碰巧」而是真正的稳定
        val input = listOf(unnamed[0], named[0], unnamed[1], named[1], unnamed[2], named[2])
        val out = ids(sort(input))

        assertEquals(
            listOf("8@chatroom", "6@chatroom", "4@chatroom", "9@chatroom", "7@chatroom", "5@chatroom"),
            out,
        )
        // 组内相对顺序 == 各自在输入里出现的顺序
        assertEquals(ids(named), out.filter { it in named.map { r -> r.wxid } })
        assertEquals(ids(unnamed), out.filter { it in unnamed.map { r -> r.wxid } })
    }

    @Test
    fun sortDoesNotMutateInput() {
        val input = listOf(Room("1@chatroom", "", ""), Room("2@chatroom", "有名", ""))
        val snapshot = ids(input)
        sort(input)
        assertEquals("排序不应改动原列表（返回新列表）", snapshot, ids(input))
    }
}
