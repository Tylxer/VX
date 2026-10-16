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
}
