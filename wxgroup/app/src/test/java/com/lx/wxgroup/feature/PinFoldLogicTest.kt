package com.lx.wxgroup.feature

import com.lx.wxgroup.data.ConvLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // ---- configStamp：折叠态必须在指纹里（R31 真机 bug 的守门用例）----

    @Test
    fun stampChangesWhenOnlyPinFoldedChanges() {
        // 除折叠态外所有输入都相同 —— 真机上折叠/展开就是这个场景。
        // 指纹不变 ⇒ rebuild 被短路 ⇒ 日志打了"rebuild"但列表纹丝不动。
        val a = PinFoldLogic.configStamp(20, true, 0L, false, 123, 456, null)
        val b = PinFoldLogic.configStamp(20, true, 0L, false, 123, 456, false)
        val c = PinFoldLogic.configStamp(20, true, 0L, false, 123, 456, true)
        assertNotEquals(a, b)
        assertNotEquals(b, c)
        assertNotEquals(a, c)
    }

    @Test
    fun stampIsStableForSameInputs() {
        assertEquals(
            PinFoldLogic.configStamp(20, true, 0L, false, 123, 456, true),
            PinFoldLogic.configStamp(20, true, 0L, false, 123, 456, true),
        )
    }

    @Test
    fun stampStillCoversTheOldSixFields() {
        // 回归守门：老六项任何一项变化都必须改指纹（曾因漏 showUnread 出过同类 bug）
        val base = PinFoldLogic.configStamp(20, true, 0L, false, 123, 456, false)
        val variants = listOf(
            PinFoldLogic.configStamp(21, true, 0L, false, 123, 456, false),
            PinFoldLogic.configStamp(20, false, 0L, false, 123, 456, false),
            PinFoldLogic.configStamp(20, true, 7L, false, 123, 456, false),
            PinFoldLogic.configStamp(20, true, 0L, true, 123, 456, false),
            PinFoldLogic.configStamp(20, true, 0L, false, 999, 456, false),
            PinFoldLogic.configStamp(20, true, 0L, false, 123, 999, false),
        )
        for (v in variants) assertNotEquals(base, v)
    }

    // ---------------------------------------------------------------- foldAction：快路径决策

    @Test
    fun foldAction_noopWhenAppliedEqualsDetected() {
        // 没翻转（同方向重复上报）⇒ 不动，无论 count 变没变
        assertEquals(
            PinFoldLogic.FoldAction.NOOP,
            PinFoldLogic.foldAction(true, true, 100, 100),
        )
        assertEquals(
            PinFoldLogic.FoldAction.NOOP,
            PinFoldLogic.foldAction(false, false, 100, 100),
        )
        // 即便 count 变了，只要方向没翻，也判 NOOP（count 变化由 rebuild 的 orderDirty 兜）
        assertEquals(
            PinFoldLogic.FoldAction.NOOP,
            PinFoldLogic.foldAction(true, true, 100, 99),
        )
    }

    @Test
    fun foldAction_flipReuseWhenCountUnchanged() {
        // 快路径成立条件：翻转了 + count 未变 ⇒ 折叠入口不是真实 item，快照可复用
        assertEquals(
            PinFoldLogic.FoldAction.RELAYOUT_REUSE,
            PinFoldLogic.foldAction(false, true, 100, 100),
        )
        assertEquals(
            PinFoldLogic.FoldAction.RELAYOUT_REUSE,
            PinFoldLogic.foldAction(true, false, 100, 100),
        )
    }

    @Test
    fun foldAction_flipRereadWhenCountChanged() {
        // 必须重读条件：翻转了 + count 变了 ⇒ 折叠入口是真实 item，快照下标整体偏移
        assertEquals(
            PinFoldLogic.FoldAction.RELAYOUT_REREAD,
            PinFoldLogic.foldAction(false, true, 100, 99),
        )
        assertEquals(
            PinFoldLogic.FoldAction.RELAYOUT_REREAD,
            PinFoldLogic.foldAction(true, false, 99, 100),
        )
    }

    @Test
    fun foldAction_nullAppliedIsNotNoop() {
        // 三态不混同：applied=null（从未应用）与任何布尔都不相等 ⇒ 恒触发重建，
        // 不会和 applied=false（已应用展开）混同成 NOOP
        assertEquals(
            PinFoldLogic.FoldAction.RELAYOUT_REUSE,
            PinFoldLogic.foldAction(null, false, 100, 100),
        )
        assertEquals(
            PinFoldLogic.FoldAction.RELAYOUT_REUSE,
            PinFoldLogic.foldAction(null, true, 100, 100),
        )
        // null 与 true/false 在 NOOP 判据上两两不等
        assertNotEquals(
            PinFoldLogic.foldAction(null, false, 100, 100),
            PinFoldLogic.foldAction(false, false, 100, 100),
        )
    }

    @Test
    fun foldAction_threeStatesDoNotMix() {
        // applied 的三种取值（null/false/true）在「是否 NOOP」上必须两两可区分：
        // 对同一 detected=false，三者给出的动作各不相同（null≠false≠true）
        val fromNull = PinFoldLogic.foldAction(null, false, 10, 10)
        val fromFalse = PinFoldLogic.foldAction(false, false, 10, 10)
        val fromTrue = PinFoldLogic.foldAction(true, false, 10, 10)
        assertNotEquals(fromNull, fromFalse)
        assertNotEquals(fromFalse, fromTrue)
        // ⛔ 原断言 `assertNotEquals(fromNull, fromTrue)` 是**错的**（2026-10-28 修）：
        //    实现只分出两类动作 —— NOOP 与「需要重建」（再按 count 分 REUSE/REREAD）。
        //    `null`（从未应用）和 `true`（上次折叠、现在检测到展开）都只是「需要重建」，
        //    count 同为 10 时必然都是 RELAYOUT_REUSE，要求二者不同等于要求实现说谎。
        //    三态的真正区分点是 **NOOP 判据**（下面几条），不是「动作两两不同」。
        // false==false 才是 NOOP，true==true 才是 NOOP
        assertEquals(PinFoldLogic.FoldAction.NOOP, fromFalse)
        assertNotEquals(PinFoldLogic.FoldAction.NOOP, fromTrue)
    }

    @Test
    fun flipRelayoutPutsPinnedRowsCorrectly() {
        // 翻转后 pinned 行位置正确：展开在最前，折叠被收掉 —— 与 foldAction 决策一致
        val convs = listOf(conv("alice"), conv("bob"))
        val groups = listOf(ConvLayout.GroupRowSpec(1L, "g1", pinned = true, sortOrder = 0))
        val members = mapOf(1L to setOf("alice"))
        val unfolded = ConvLayout.build(convs, groups, members, pinFolded = false)
        val folded = ConvLayout.build(convs, groups, members, pinFolded = true)
        // 展开态：pinned 分组行在最前（isGroupRow 判位置 0）
        assertTrue(unfolded.isGroupRow(0))
        // 折叠态：位置 0 不再是分组行（pinned 分组行被收进「N个置顶聊天」）
        assertFalse(folded.isGroupRow(0))
        // 决策口径一致：折叠 ⇒ shouldInsertPinned=false
        assertFalse(PinFoldLogic.shouldInsertPinned(true))
    }
}
