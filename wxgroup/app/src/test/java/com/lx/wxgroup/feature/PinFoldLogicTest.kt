package com.lx.wxgroup.feature

import com.lx.wxgroup.data.ConvLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PinFoldLogic] 的 JVM 回归（R31）。
 *
 * 钉死「折叠置顶聊天」的两层判据：
 * ① [PinFoldLogic.detectPinFolded] —— 数据层兜底，从会话快照推导折叠态；
 * ② [PinFoldLogic.shouldInsertPinned] —— 给定折叠态，pinned 分组行该不该插入。
 *
 * 核心语义：折叠 ⇒ 收进「N个置顶聊天」不插 pinned 分组行；展开/判不出 ⇒ 照常插。
 */
class PinFoldLogicTest {

    private fun conv(wxid: String, unreadable: Boolean = false) =
        ConvLayout.ConvEntry(wxid, unreadable = unreadable)

    // ---------------------------------------------------------------- detectPinFolded：折叠

    @Test
    fun foldRowAtHeadPlusRealSessionsIsFolded() {
        // 折叠入口（空 wxid、真实 item）+ 后续真实会话 ⇒ 折叠
        assertTrue(PinFoldLogic.detectPinFolded(listOf(conv(""), conv("alice"), conv("bob"))))
    }

    @Test
    fun foldRowAtHeadWithSingleRealAfterIsFolded() {
        assertTrue(PinFoldLogic.detectPinFolded(listOf(conv(""), conv("alice"))))
    }

    // ---------------------------------------------------------------- detectPinFolded：展开

    @Test
    fun realSessionAtHeadIsExpanded() {
        // 顶部是真实会话 ⇒ 展开
        assertFalse(PinFoldLogic.detectPinFolded(listOf(conv("alice"), conv("bob"))))
    }

    @Test
    fun foldRowNotAtHeadIsNotDetectedAsFolded() {
        // 折叠行不在顶部（异常布局）⇒ 不判折叠，宁可按展开
        assertFalse(PinFoldLogic.detectPinFolded(listOf(conv("alice"), conv(""))))
    }

    // ---------------------------------------------------------------- detectPinFolded：判不出

    @Test
    fun emptyListIsNotFolded() {
        assertFalse(PinFoldLogic.detectPinFolded(emptyList()))
    }

    @Test
    fun unreadablePlaceholderAtHeadIsNotFolded() {
        // getItem==null 的占位（unreadable=true）绝不能算折叠
        assertFalse(PinFoldLogic.detectPinFolded(listOf(conv("", unreadable = true), conv("alice"))))
    }

    @Test
    fun singleFoldRowWithoutRealAfterIsNotFolded() {
        // 折叠入口后面没有任何真实会话 ⇒ 判不出按展开（保守，宁可多显示）
        assertFalse(PinFoldLogic.detectPinFolded(listOf(conv(""))))
    }

    @Test
    fun allBlankSnapsAreNotFolded() {
        // field_username 全局读失败导致整列空 ⇒ 不能误判成折叠
        assertFalse(PinFoldLogic.detectPinFolded(listOf(conv(""), conv(""))))
    }

    @Test
    fun globalFailureMixedWithPlaceholderIsNotFolded() {
        // 空 wxid（真实 item）占多数 + 无真实会话 ⇒ 仍是判不出
        assertFalse(
            PinFoldLogic.detectPinFolded(listOf(conv(""), conv("", unreadable = true), conv("")))
        )
    }

    // ---------------------------------------------------------------- shouldInsertPinned

    @Test
    fun foldedDoesNotInsertPinned() {
        assertFalse(PinFoldLogic.shouldInsertPinned(true))
    }

    @Test
    fun expandedInsertsPinned() {
        assertTrue(PinFoldLogic.shouldInsertPinned(false))
    }

    @Test
    fun unknownInsertsPinned() {
        // null（判不出）按展开处理，宁可多显示
        assertTrue(PinFoldLogic.shouldInsertPinned(null))
    }

    // ---------------------------------------------------------------- 两者口径一致

    @Test
    fun detectThenShouldInsertIsConsistentWithBuild() {
        // detectPinFolded 返回 false 的地方，shouldInsertPinned 必须为 true（要插）
        assertTrue(PinFoldLogic.shouldInsertPinned(PinFoldLogic.detectPinFolded(emptyList())))
        assertTrue(
            PinFoldLogic.shouldInsertPinned(PinFoldLogic.detectPinFolded(listOf(conv("alice"))))
        )
        // 判出折叠的地方，shouldInsertPinned 必须为 false（不插）
        assertFalse(
            PinFoldLogic.shouldInsertPinned(
                PinFoldLogic.detectPinFolded(listOf(conv(""), conv("alice")))
            )
        )
    }
}
