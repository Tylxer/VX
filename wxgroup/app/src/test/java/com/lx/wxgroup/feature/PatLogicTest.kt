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
        // 只有空白 → 视同空值，按关
        assertFalse(PatLogic.enabled("   "))
        assertFalse(PatLogic.enabled("\t\n"))
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

    @Test
    fun methodCandidatesHandleEmptyAndMiss() {
        assertEquals(emptyList<String>(), PatLogic.methodCandidates(emptyList()))
        assertEquals(emptyList<String>(), PatLogic.methodCandidates(listOf("sendText", "onCreate")))
    }

    // ------------------------------------------------------------------ 候选去重 / 保序

    @Test
    fun classCandidatesDedupeAndPreserveFirstSeenOrder() {
        // 真机入参来自多份栈 dump（见 docs/拍一拍下一轮取证清单.md「结果 C」），
        // 同一个类会在多帧重复出现；输出必须去重，且保留首次出现顺序 = 栈序线索。
        val names = listOf(
            "com.tencent.mm.ui.chatting.PatAvatarHelper", // 第 0 次出现
            "com.tencent.mm.ui.LauncherUI",               // 不命中
            "com.tencent.mm.ui.chatting.PatAvatarHelper", // 重复
            "com.tencent.mm.ui.chatting.PaiPaiHelper",    // 第 1 个不同候选
            "com.tencent.mm.ui.chatting.PatAvatarHelper", // 再重复
        )
        assertEquals(
            listOf(
                "com.tencent.mm.ui.chatting.PatAvatarHelper",
                "com.tencent.mm.ui.chatting.PaiPaiHelper",
            ),
            PatLogic.classCandidates(names),
        )
    }

    @Test
    fun methodCandidatesDedupeAndPreserveFirstSeenOrder() {
        val names = listOf("onDoubleTap", "onPatAvatar", "onDoubleTap", "paiPai", "onPatAvatar")
        assertEquals(
            listOf("onDoubleTap", "onPatAvatar", "paiPai"),
            PatLogic.methodCandidates(names),
        )
    }

    @Test
    fun candidatesDoNotMutateInput() {
        // 纯函数契约：不改动入参（探针侧可能传共享列表）
        val input = mutableListOf("onDoubleTap", "a.PAT.b", "onCreate")
        val snapshot = input.toList()
        PatLogic.methodCandidates(input)
        PatLogic.classCandidates(input)
        assertEquals(snapshot, input)
    }

    @Test
    fun blankNameElementsDoNotMatchNorCrash() {
        // 空串 / 空白串元素不命中任何关键词，也不抛异常
        assertEquals(emptyList<String>(), PatLogic.classCandidates(listOf("", "   ")))
        assertEquals(emptyList<String>(), PatLogic.methodCandidates(listOf("", "   ")))
    }

    @Test
    fun classKeywordsAreDeliberatelyLooseSubstringHeuristic() {
        // 子串匹配的**已知假阳性**（Pattern / Patch 含 "pat"）是刻意的「宽进严出」：
        // 候选只负责缩小人工确认范围，最终回填锚点必须人工过目
        // （见 docs/拍一拍下一轮取证清单.md §五 交叉验证）。
        // 锁住该行为，防止有人把它当 bug 收紧成精确匹配而漏掉混淆名。
        assertEquals(
            listOf("com.x.PatternBox", "com.x.Patcher"),
            PatLogic.classCandidates(listOf("com.x.PatternBox", "com.x.Patcher")),
        )
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
        // 预算为 0 或负：一律不再打（防御性，探针理论上不会传负值）
        assertFalse(PatLogic.stackDumpDue(0, max = 0))
        assertFalse(PatLogic.stackDumpDue(0, max = -1))
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
        // 预算为 0：新类也不打
        assertFalse(PatLogic.shouldDumpListener(alreadySeen = false, count = 0, max = 0))
    }

    @Test
    fun constantsAreStable() {
        assertEquals("拍了拍", PatLogic.PAT_KEYWORD)
        assertEquals(50, PatLogic.MAX_STACK_DUMPS)
    }
}
