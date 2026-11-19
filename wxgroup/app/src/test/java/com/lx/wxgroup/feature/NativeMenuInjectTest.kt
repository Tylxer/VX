package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R73 原生菜单注入的纯逻辑单测（零 Android / Xposed 依赖）。
 *
 * 锁两件事：
 * 1. add 条目参数常量不被漂移（group / itemId / order / icon / window）；
 * 2. [NativeMenuGate.shouldInject] 门禁四条件的真值表 —— 任一条件缺失都必须拒绝，
 *    否则会误注入会话列表 / 输入栏 / 图片浏览菜单。
 *
 * talker 不再是门禁条件：真机长按普通聊天消息时前台 activity 是 LauncherUI，
 * `currentChatTalker()` 取的 `Chat_User` extra 恒空，导致原五条件门禁把入口挡死。
 * hasRef 单条即可区分聊天消息菜单（详见 [NativeMenuGate.shouldInject] KDoc）。
 *
 * 变异验证（不跑 gradle，用 Python 等价复刻，见回报）：
 * - M1 去掉 hasRef 门禁 → gateTruthTable 变红；
 * - M2 去掉时间窗门禁 → gateWindowBoundary 变红。
 */
class NativeMenuInjectTest {

    @Test
    fun constantsAreLocked() {
        assertEquals(0x5747_0000, NativeMenuGate.GROUP_ID)
        assertEquals(0x5747_0001, NativeMenuGate.ITEM_ID)
        assertEquals(Int.MAX_VALUE - 1, NativeMenuGate.ORDER)
        assertEquals(0x7f0f0529, NativeMenuGate.ICON)
        assertEquals(4000L, NativeMenuGate.WINDOW_MS)
    }

    @Test
    fun itemIdAvoidsNativeIds() {
        // 宿主原生 id 111/116/118/100/13/20 都是小整数；高位 "WXG" 不与任何原生值相等
        val natives = setOf(111, 116, 118, 100, 13, 20)
        assertFalse(NativeMenuGate.ITEM_ID in natives)
        assertTrue(NativeMenuGate.ITEM_ID and 0xFFFF0000.toInt() != 0)
    }

    @Test
    fun gateAcceptsOnlyWhenAllConditionsMet() {
        val now = 10_000L
        assertTrue(
            NativeMenuGate.shouldInject(
                enabled = true,
                lastPressAt = now - 2_000L,
                now = now,
                hasRef = true,
            )
        )
    }

    @Test
    fun gateRejectsWhenAnySingleConditionFails() {
        val now = 10_000L
        val press = now - 2_000L
        // 逐条翻转：enabled / 未长按 / 超窗 / 无 h9 ref
        assertFalse(NativeMenuGate.shouldInject(false, press, now, true))
        assertFalse(NativeMenuGate.shouldInject(true, 0L, now, true))
        assertFalse(NativeMenuGate.shouldInject(true, now - 4_001L, now, true))
        assertFalse(NativeMenuGate.shouldInject(true, press, now, false))
    }

    @Test
    fun gateWindowBoundary() {
        val now = 10_000L
        // 恰好等于窗口：放行；刚过 1ms：拒绝
        assertTrue(
            NativeMenuGate.shouldInject(
                true, now - NativeMenuGate.WINDOW_MS, now, true
            )
        )
        assertFalse(
            NativeMenuGate.shouldInject(
                true, now - NativeMenuGate.WINDOW_MS - 1, now, true
            )
        )
    }

    @Test
    fun gateTruthTable() {
        // 4 个维度穷举（enabled, pressed, inWindow, hasRef）共 16 组
        val now = 10_000L
        var accepted = 0
        for (e in listOf(true, false))
            for (p in listOf(true, false))
                for (w in listOf(true, false))
                    for (r in listOf(true, false)) {
                        val lastPressAt = when {
                            !p -> 0L
                            w -> now - 1_000L
                            else -> now - NativeMenuGate.WINDOW_MS - 1L
                        }
                        val got = NativeMenuGate.shouldInject(e, lastPressAt, now, r)
                        val expected = e && p && w && r
                        assertEquals("e=$e p=$p w=$w r=$r", expected, got)
                        if (got) accepted++
                    }
        assertEquals("只有全 1 组合放行", 1, accepted)
    }

    @Test
    fun gateIgnoresTalker() {
        // R73-L3 talker 恒空回归锁：真机日志 `gate closed enabled=true age=151 talker= ref=true`
        // 曾因 talker 空被挡死。函数已无 talker 参数；此处直接构造「talker 为空（不存在）
        // 但 enabled=true / lastPressAt>0 / now 在窗内 / hasRef=true」场景，断言放行。
        val now = 10_000L
        assertTrue(
            NativeMenuGate.shouldInject(
                enabled = true,
                lastPressAt = now - 2_000L,
                now = now,
                hasRef = true,
            )
        )
    }
}
