package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietLogicTest {

    @Test
    fun parsesMinutePrecisely() {
        assertEquals(0, QuietLogic.minuteOf("00:00"))
        assertEquals(23 * 60 + 59, QuietLogic.minuteOf("23:59"))
        assertEquals(7 * 60 + 5, QuietLogic.minuteOf(" 07:05 "))
        assertNull(QuietLogic.minuteOf("24:00"))
        assertNull(QuietLogic.minuteOf("08:60"))
        assertNull(QuietLogic.minuteOf("8"))
    }

    @Test
    fun sameDayWindowIncludesStartAndExcludesEnd() {
        assertFalse(QuietLogic.inDndWindow(7 * 60 + 59, "08:00", "10:30"))
        assertTrue(QuietLogic.inDndWindow(8 * 60, "08:00", "10:30"))
        assertTrue(QuietLogic.inDndWindow(10 * 60 + 29, "08:00", "10:30"))
        assertFalse(QuietLogic.inDndWindow(10 * 60 + 30, "08:00", "10:30"))
    }

    @Test
    fun overnightWindowCrossesMidnight() {
        assertTrue(QuietLogic.inDndWindow(23 * 60, "23:00", "07:00"))
        assertTrue(QuietLogic.inDndWindow(0, "23:00", "07:00"))
        assertTrue(QuietLogic.inDndWindow(6 * 60 + 59, "23:00", "07:00"))
        assertFalse(QuietLogic.inDndWindow(7 * 60, "23:00", "07:00"))
        assertFalse(QuietLogic.inDndWindow(12 * 60, "23:00", "07:00"))
    }

    @Test
    fun equalOrInvalidWindowNeverActivates() {
        assertFalse(QuietLogic.inDndWindow(0, "08:00", "08:00"))
        assertFalse(QuietLogic.inDndWindow(0, "bad", "07:00"))
        assertFalse(QuietLogic.inDndWindow(-1, "23:00", "07:00"))
        assertFalse(QuietLogic.inDndWindow(24 * 60, "23:00", "07:00"))
    }

    @Test
    fun atAliasRequiresNameBoundary() {
        assertTrue(QuietLogic.containsAtAlias("张三: @V 收到", "V"))
        assertTrue(QuietLogic.containsAtAlias("@V", "V"))
        assertFalse(QuietLogic.containsAtAlias("张三: @Verity 收到", "V"))
        assertFalse(QuietLogic.containsAtAlias("张三: @VIP 收到", "V"))
        assertFalse(QuietLogic.containsAtAlias("张三: @V_2 收到", "V"))
        assertFalse(QuietLogic.containsAtAlias("张三: @V-test 收到", "V"))
        assertTrue(QuietLogic.containsAtAlias("先 @VIP 后 @V。", "V"))
    }

    @Test
    fun aliasesAndBroadcastMentionsAreRecognized() {
        assertEquals(listOf("V", "老王", "Alice"), QuietLogic.aliases("V，老王、 Alice\nV"))
        assertTrue(QuietLogic.isAtMe("@所有人 请查看", emptyList()))
        assertTrue(QuietLogic.isAtMe("@all 请查看", emptyList()))
        assertFalse(QuietLogic.isAtMe("@allow 请查看", emptyList()))
        assertTrue(QuietLogic.isAtMe("@老王 请查看", listOf("老王")))
    }

    @Test
    fun exclusionWinsBeforeAllowList() {
        val room = "123@chatroom"
        assertFalse(QuietLogic.groupInScope(room, listOf(room), listOf(room)))
        assertTrue(QuietLogic.groupInScope(room, listOf(room), emptyList()))
        assertFalse(QuietLogic.groupInScope(room, listOf("other@chatroom"), emptyList()))
    }

    @Test
    fun emptyAllowMeansAllGroupsButNeverDirectChats() {
        assertTrue(QuietLogic.groupInScope("123@chatroom", emptyList(), emptyList()))
        assertFalse(QuietLogic.groupInScope("wxid_someone", emptyList(), emptyList()))
        assertFalse(QuietLogic.groupInScope(null, emptyList(), emptyList()))
    }

    @Test
    fun isGroupTalkerRequiresChatroomSuffix() {
        assertTrue(QuietLogic.isGroupTalker("123@chatroom"))
        assertTrue(QuietLogic.isGroupTalker("wx__c_18c13d8bbf6@chatroom"))
        assertFalse(QuietLogic.isGroupTalker("wxid_someone"))
        assertFalse(QuietLogic.isGroupTalker(""))
        assertFalse(QuietLogic.isGroupTalker(null))
    }

    @Test
    fun classifyGroupSeparatesNewExistingHandled() {
        val baseline = setOf("old1@chatroom", "old2@chatroom")
        val handled = setOf("done@chatroom")
        // 非群聊一律不管
        assertEquals(
            QuietLogic.GroupDndVerdict.NOT_GROUP,
            QuietLogic.classifyGroup("wxid_x", baseline, handled, true)
        )
        // 快照里已有的存量群：不管
        assertEquals(
            QuietLogic.GroupDndVerdict.EXISTING,
            QuietLogic.classifyGroup("old1@chatroom", baseline, handled, true)
        )
        // 台账里已有（设过）：不管
        assertEquals(
            QuietLogic.GroupDndVerdict.HANDLED,
            QuietLogic.classifyGroup("done@chatroom", baseline, handled, true)
        )
        // 台账优先于「新群」判定
        assertEquals(
            QuietLogic.GroupDndVerdict.HANDLED,
            QuietLogic.classifyGroup("done@chatroom", emptySet(), handled, true)
        )
        // 快照之后才出现的群聊 = 新群
        assertEquals(
            QuietLogic.GroupDndVerdict.NEW,
            QuietLogic.classifyGroup("new@chatroom", baseline, handled, true)
        )
    }

    @Test
    fun classifyGroupBeforeBaselineReadyAlwaysTreatsAsExisting() {
        // 快照未就绪（微信可能还停在聊天页/数据没填完）：宁漏设，也不误静音老群
        val verdict = QuietLogic.classifyGroup(
            "maybe-old@chatroom", emptySet(), emptySet(), baselineReady = false
        )
        assertEquals(QuietLogic.GroupDndVerdict.EXISTING, verdict)
        // 快照一就绪，同一个群就是 NEW
        assertEquals(
            QuietLogic.GroupDndVerdict.NEW,
            QuietLogic.classifyGroup("maybe-old@chatroom", emptySet(), emptySet(), true)
        )
    }

    @Test
    fun overnightWindowBoundariesAroundStartAndEnd() {
        // 22:00→06:00 跨午夜：22:00 含、06:00 不含
        assertTrue(QuietLogic.inDndWindow(22 * 60, "22:00", "06:00"))
        assertTrue(QuietLogic.inDndWindow(23 * 60 + 59, "22:00", "06:00"))
        assertTrue(QuietLogic.inDndWindow(5 * 60 + 59, "22:00", "06:00"))
        assertFalse(QuietLogic.inDndWindow(6 * 60, "22:00", "06:00"))
        // 默认配置 23:00→07:00：23:59 与 00:00 都在窗内
        assertTrue(QuietLogic.inDndWindow(23 * 60 + 59, "23:00", "07:00"))
        assertTrue(QuietLogic.inDndWindow(0, "23:00", "07:00"))
        // 起止相等 = 零时长，任何时刻都不生效（决策 #7：不是全天）
        assertFalse(QuietLogic.inDndWindow(0, "00:00", "00:00"))
        assertFalse(QuietLogic.inDndWindow(12 * 60, "07:00", "07:00"))
    }
}
