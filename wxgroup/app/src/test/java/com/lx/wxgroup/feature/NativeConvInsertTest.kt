package com.lx.wxgroup.feature

import com.lx.wxgroup.data.GroupConvPlan
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.PrefKind
import com.lx.wxgroup.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R67 b2 `NativeConvInsert` 的纯函数级回归（不依赖安卓运行时，不碰反射/Xposed）。
 *
 * 被测的是 [NativeConvInsert.nativeSetters] / [NativeConvInsert.flagOf] 两个纯函数 ——
 * 它们把「7 个 setter 的参数构造」从反射链里抽出来，反射链 `insertConv` 只是按序消费它。
 * 守住的关键判据（对齐 docs/_r67_b2_native_insert.md §2.3/§2.5/§6.1/§7.3）：
 * - flag = 2^62 | time（PIN_BASE + timeMs；`s4.I` 会经 `po2.a.c` 重算成 `2^62|time`，值不变）
 * - msgType 必须是 String "1"（不是整数 1）
 * - status/isSend/chatmode 必须是 int（不是 String）
 * - 开关默认 false（Prefs.ALL def="false"）
 *
 * # 变异验证
 * 以下每处**生产代码**变异都必须让本文件某条断言失败，证明断言不是摆设：
 * 1. 把 `nativeSetters` 的 `SET_FLAG to flagOf(timeMs)` 改成 `SET_FLAG to timeMs`
 *    → [settersAreSevenAndInOrder] 红（s[2] 少了 2^62 置顶位）。
 *    注：[flagIsPinBitOrTime] 测的是 flagOf() 本身、不随本变异改变，R67 复核已纠正此前误述。
 * 2. 把 `MSG_TYPE_TEXT` 从 `"1"` 改成 `1`（Int）
 *    → [msgTypeIsStringNotInt] 的 `assertTrue(msgType is String)` 红。
 * 3. 把 `SET_STATUS to 3` 改成 `SET_STATUS to "3"`（String）
 *    → [statusIsSendChatmodeAreInts] 的 `assertTrue(s[3].second is Int)` 红。
 * 4. 把 Keys.kt 里 `GCONV_NATIVE_INSERT` 的 PrefSpec def 从 `"false"` 改成 `"true"`
 *    → [switchDefaultsToFalse] 红。
 * ⚠️ 本轮 gradle 构建锁归主会话、我不跑单测；上述「红」由主会话 `gradlew test` 实跑确认。
 */
class NativeConvInsertTest {

    @Test
    fun flagIsPinBitOrTime() {
        // 2^62 置顶位 + 时间：漏掉置顶位会让行掉出置顶区（§6.3 变异点）
        assertEquals(GroupConvPlan.PIN_BASE + 123L, NativeConvInsert.flagOf(123L))
        // 顺带锁死 PIN_BASE 的字面值，防有人把 2^62 抄错
        assertEquals(4611686018427387904L, GroupConvPlan.PIN_BASE)
    }

    @Test
    fun settersAreSevenAndInOrder() {
        val s = NativeConvInsert.nativeSetters("wxgrp_0000000000000001", 999L)
        assertEquals("必须正好 7 个 setter（§7.3 必填字段清单）", 7, s.size)
        assertEquals("B1", s[0].first)
        assertEquals("wxgrp_0000000000000001", s[0].second)
        assertEquals("T0", s[1].first)
        assertEquals(999L, s[1].second)
        assertEquals("b1", s[2].first)
        assertEquals(GroupConvPlan.PIN_BASE + 999L, s[2].second)
        assertEquals("r1", s[3].first)
        assertEquals(3, s[3].second)
        assertEquals("j1", s[4].first)
        assertEquals(1, s[4].second)
        assertEquals("Q0", s[5].first)
        assertEquals(1, s[5].second)
        assertEquals("n1", s[6].first)
        assertEquals("1", s[6].second)
    }

    @Test
    fun msgTypeIsStringNotInt() {
        val msgType = NativeConvInsert.nativeSetters("x", 0L).last().second
        assertTrue("msgType 必须是 String（§2.5），不是整数", msgType is String)
        assertEquals("1", msgType)
    }

    @Test
    fun statusIsSendChatmodeAreInts() {
        val s = NativeConvInsert.nativeSetters("x", 0L)
        assertTrue("status 必须是 int", s[3].second is Int)
        assertTrue("isSend 必须是 int", s[4].second is Int)
        assertTrue("chatmode 必须是 int", s[5].second is Int)
    }

    @Test
    fun switchDefaultsToFalse() {
        // 默认关：关着 = 现状 execSQL 直写，零行为变化（§6.1）
        assertEquals("false", Prefs.defaultOf(Keys.GCONV_NATIVE_INSERT))
    }

    @Test
    fun switchIsChatDomainSwitchNotHidden() {
        val spec = Prefs.spec(Keys.GCONV_NATIVE_INSERT)!!
        assertEquals(PrefKind.SWITCH, spec.kind)
        assertEquals("聊天", spec.domain)
        assertFalse("b2 是实验开关，要出现在设置页，不能 hidden", spec.hidden)
    }
}
