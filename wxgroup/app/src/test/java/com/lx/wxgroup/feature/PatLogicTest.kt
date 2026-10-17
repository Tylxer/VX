package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatLogicTest {

    // ------------------------------------------------------------------ 开关判据

    @Test
    fun switchOnlyAcceptsTrueOrOne() {
        assertTrue(PatLogic.enabled("true"))
        assertTrue(PatLogic.enabled("1"))
        assertTrue(PatLogic.enabled(" true "))
        assertTrue(PatLogic.enabled("TRUE"))
        assertFalse(PatLogic.enabled("false"))
        assertFalse(PatLogic.enabled("0"))
        assertFalse(PatLogic.enabled("on"))
        assertFalse(PatLogic.enabled(""))
        assertFalse(PatLogic.enabled(null))
    }

    @Test
    fun dirtySwitchValueFallsBackToOff() {
        // 宁可漏拦也不误拦：读不到 / 脏值一律按关
        assertFalse(PatLogic.enabled("yes"))
        assertFalse(PatLogic.enabled("truex"))
        assertFalse(PatLogic.enabled(" enabled"))
        assertFalse(PatLogic.enabled("2"))
    }

    // ------------------------------------------------------------------ 文本判据

    @Test
    fun recognizesPatSystemMessageShapes() {
        // 需求原文「对方收到『XXX 拍了拍 XXX』的系统消息」的已知形态
        assertTrue(PatLogic.isPatText("\"张三\" 拍了拍 \"李四\""))
        assertTrue(PatLogic.isPatText("\"张三\" 拍了拍 \"李四\" 的肩膀"))
        assertTrue(PatLogic.isPatText("\"张三\" 拍了拍自己"))
        assertTrue(PatLogic.isPatText("  拍了拍  "))
    }

    @Test
    fun normalChatTextIsNotPat() {
        assertFalse(PatLogic.isPatText("你好"))
        assertFalse(PatLogic.isPatText("在吗"))
        assertFalse(PatLogic.isPatText(""))
        assertFalse(PatLogic.isPatText("   "))
        assertFalse(PatLogic.isPatText(null))
    }

    @Test
    fun overlyLongTextIsNotPat() {
        // 转发的长文含「拍了拍」字样也不能当拍一拍消息拦
        val long = "x".repeat(PatLogic.PAT_TEXT_MAX_LEN) + PatLogic.PAT_KEYWORD
        assertFalse(PatLogic.isPatText(long))
        // 恰好等于上限仍算（真实文案远短于 200 字符）
        val atLimit = "y".repeat(PatLogic.PAT_TEXT_MAX_LEN - PatLogic.PAT_KEYWORD.length) +
            PatLogic.PAT_KEYWORD
        assertEquals(PatLogic.PAT_TEXT_MAX_LEN, atLimit.length)
        assertTrue(PatLogic.isPatText(atLimit))
    }

    // ------------------------------------------------------------------ 组合判据（收到的消息）

    @Test
    fun incomingBlockedOnlyWhenAllConditionsHold() {
        val on = "true"
        val off = "false"
        assertTrue(PatLogic.shouldBlockIncoming(on, sysMsg = true, text = "\"A\" 拍了拍 \"B\""))
        // 开关关着不拦
        assertFalse(PatLogic.shouldBlockIncoming(off, sysMsg = true, text = "\"A\" 拍了拍 \"B\""))
        // 系统消息但文本不是拍一拍不拦
        assertFalse(PatLogic.shouldBlockIncoming(on, sysMsg = true, text = "你收到一个红包"))
        // 拍一拍文本但不是系统消息（用户手打）不拦——防误伤正常聊天
        assertFalse(PatLogic.shouldBlockIncoming(on, sysMsg = false, text = "我拍了拍你"))
        // 脏开关值不拦
        assertFalse(PatLogic.shouldBlockIncoming("on", sysMsg = true, text = "\"A\" 拍了拍 \"B\""))
        // null 文本不拦
        assertFalse(PatLogic.shouldBlockIncoming(on, sysMsg = true, text = null))
    }

    // ------------------------------------------------------------------ 组合判据（自己触发）

    @Test
    fun triggerBlockedPurelyBySwitch() {
        assertTrue(PatLogic.shouldBlockTrigger("true"))
        assertTrue(PatLogic.shouldBlockTrigger("1"))
        assertFalse(PatLogic.shouldBlockTrigger("false"))
        assertFalse(PatLogic.shouldBlockTrigger(null))
    }

    // ------------------------------------------------------------------ 取证候选筛选

    @Test
    fun classCandidatesFilterByHeuristicKeywords() {
        val names = listOf(
            "com.tencent.mm.ui.chatting.PatAvatarHelper",   // pat
            "com.tencent.mm.ui.chatting.PaiPaiDoubleTap",   // pai + doubletap
            "com.tencent.mm.ui.chatting.HeadDoubleTapView", // doubletap
            "com.tencent.mm.ui.LauncherUI",                 // 不相干
            "com.tencent.mm.storage.MsgInfo",               // 不相干
        )
        val hits = PatLogic.classCandidates(names)
        assertEquals(3, hits.size)
        assertTrue(hits.containsAll(
            listOf(
                "com.tencent.mm.ui.chatting.PatAvatarHelper",
                "com.tencent.mm.ui.chatting.PaiPaiDoubleTap",
                "com.tencent.mm.ui.chatting.HeadDoubleTapView",
            )
        ))
    }

    @Test
    fun classCandidatesHandleEmptyAndMiss() {
        assertEquals(emptyList<String>(), PatLogic.classCandidates(emptyList()))
        assertEquals(emptyList<String>(), PatLogic.classCandidates(listOf("com.a.B", "com.c.D")))
        // 大小写不敏感
        assertEquals(
            listOf("a.PAT.b"), PatLogic.classCandidates(listOf("a.PAT.b"))
        )
    }

    @Test
    fun methodCandidatesFilterByHeuristicKeywords() {
        val names = listOf(
            "onDoubleTap",
            "onPatAvatar",
            "paiPai",
            "onAvatarClick",
            "onHeadClick",
            "sendText",
            "onCreate",
        )
        val hits = PatLogic.methodCandidates(names)
        assertEquals(5, hits.size)
        assertFalse(hits.contains("sendText"))
        assertFalse(hits.contains("onCreate"))
    }

    // ------------------------------------------------------------------ 探针限流

    @Test
    fun stackDumpStopsAtLimit() {
        assertTrue(PatLogic.stackDumpDue(0))
        assertTrue(PatLogic.stackDumpDue(PatLogic.MAX_STACK_DUMPS - 1))
        assertFalse(PatLogic.stackDumpDue(PatLogic.MAX_STACK_DUMPS))
        assertFalse(PatLogic.stackDumpDue(PatLogic.MAX_STACK_DUMPS + 10))
    }

    @Test
    fun stackDumpDueHonorsCustomMax() {
        assertTrue(PatLogic.stackDumpDue(2, max = 3))
        assertFalse(PatLogic.stackDumpDue(3, max = 3))
    }

    @Test
    fun newListenerClassDumpsOnceRepeatsAreSilenced() {
        // 探针挂在 GestureDetector#onTouchEvent 上：每个触摸事件都会进回调，
        // 所以同一个 doubleTap listener 实现类只能打一次，否则滑列表就刷屏
        assertTrue(PatLogic.shouldDumpListener(alreadySeen = false, count = 0))
        assertFalse(PatLogic.shouldDumpListener(alreadySeen = true, count = 0))
        assertFalse(PatLogic.shouldDumpListener(alreadySeen = true, count = 7))
    }

    @Test
    fun listenerDumpStillRespectsGlobalBudget() {
        assertFalse(PatLogic.shouldDumpListener(alreadySeen = false, count = PatLogic.MAX_STACK_DUMPS))
        assertTrue(
            PatLogic.shouldDumpListener(
                alreadySeen = false,
                count = PatLogic.MAX_STACK_DUMPS - 1,
            )
        )
        // 自定义预算同样生效
        assertTrue(PatLogic.shouldDumpListener(alreadySeen = false, count = 2, max = 3))
        assertFalse(PatLogic.shouldDumpListener(alreadySeen = false, count = 3, max = 3))
    }

    @Test
    fun constantsAreStable() {
        assertEquals("拍了拍", PatLogic.PAT_KEYWORD)
        assertEquals(50, PatLogic.MAX_STACK_DUMPS)
    }
}
