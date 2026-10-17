package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红包决策引擎的纯判据单测（JVM，不依赖 Android / Xposed）。
 *
 * 重点覆盖两类：
 *  1. **安全默认**：取证前（typeCodes / textMarkers 留空）引擎必须恒输出 Skip ——
 *     这组用例是本轮「不许自动领取」红线唯一的机器保障，改动后一旦变红立刻能发现；
 *  2. **四条判据**：延迟钳制（含 0ms）、名单范围、幂等、失败退避。
 */
class LuckyLogicTest {

    private val room = "1234567890@chatroom"
    private val empty = LuckyLogic.Cfg()

    private fun cfg(
        enabled: Boolean = true,
        types: Set<Int> = emptySet(),
        markers: Set<String> = emptySet(),
        delayMs: Int = LuckyLogic.DEFAULT_DELAY_MS,
        maxAttempts: Int = LuckyLogic.DEFAULT_MAX_ATTEMPTS,
        allow: Set<String> = emptySet(),
        exclude: Set<String> = emptySet(),
    ) = LuckyLogic.Cfg(enabled, types, markers, delayMs, maxAttempts, allow, exclude)

    private fun signal(
        id: String = "svr-1",
        talker: String = room,
        type: Int = 0x10000000 or 2001,
        content: String? = null,
        isSend: Boolean = false,
        createMs: Long = 1000L,
    ) = LuckyLogic.Signal(id, talker, type, content, isSend, createMs)

    // ---------------------------------------------------------- 解析：名单 / type / 延迟

    @Test
    fun entriesSplitAndTrim() {
        assertEquals(emptySet<String>(), LuckyLogic.entries(null))
        assertEquals(emptySet<String>(), LuckyLogic.entries("  "))
        assertEquals(setOf("a", "b"), LuckyLogic.entries("a, b"))
        // 全角逗号 / 顿号 / 换行都算分隔符（手机上从备忘录粘贴进来的常态）
        assertEquals(setOf("a", "b", "c"), LuckyLogic.entries("a，b、c"))
        assertEquals(setOf("a", "b"), LuckyLogic.entries("a\nb"))
        // 重复项去重
        assertEquals(setOf("a"), LuckyLogic.entries("a,a,a"))
    }

    @Test
    fun typeCodesIgnoreGarbage() {
        assertEquals(emptySet<Int>(), LuckyLogic.typeCodes(""))
        assertEquals(setOf(2001, 10000), LuckyLogic.typeCodes("2001, 10000"))
        assertEquals(setOf(49), LuckyLogic.typeCodes("abc, 49, ") )
        // 0 与负数不是合法的消息类型，直接丢掉（宁可少判据，也不要拿 0 去匹配一堆无关消息）
        assertTrue(LuckyLogic.typeCodes("0, -1, 3").contains(3))
        assertFalse(LuckyLogic.typeCodes("0, -1, 3").contains(0))
    }

    @Test
    fun lowTypeDropsWeChatFlagBits() {
        // AntiRevoke 的实证：type=268445458 的低位是 10002
        assertEquals(10002, LuckyLogic.lowType(268445458))
        assertEquals(2001, LuckyLogic.lowType(0x10000000 or 2001))
        assertEquals(1, LuckyLogic.lowType(1))
    }

    @Test
    fun delayIsClampedAndNeverZero() {
        // 0 是最危险的手误值（秒抢），必须被拉到下限
        assertEquals(LuckyLogic.MIN_DELAY_MS, LuckyLogic.clampDelay(0))
        assertEquals(LuckyLogic.MIN_DELAY_MS, LuckyLogic.clampDelay(-5))
        assertEquals(LuckyLogic.MIN_DELAY_MS, LuckyLogic.clampDelay(1))
        assertEquals(LuckyLogic.MAX_DELAY_MS, LuckyLogic.clampDelay(999_999))
        assertEquals(LuckyLogic.MAX_DELAY_MS, LuckyLogic.clampDelay(LuckyLogic.MAX_DELAY_MS))
        assertEquals(800, LuckyLogic.clampDelay(800))
        assertTrue(LuckyLogic.MIN_DELAY_MS > 0)
    }

    @Test
    fun backoffDoublesThenCaps() {
        assertEquals(0L, LuckyLogic.backoffMs(0))
        assertEquals(1_000L, LuckyLogic.backoffMs(1))
        assertEquals(2_000L, LuckyLogic.backoffMs(2))
        assertEquals(4_000L, LuckyLogic.backoffMs(3))
        assertEquals(8_000L, LuckyLogic.backoffMs(4))
        assertEquals(LuckyLogic.BACKOFF_MAX_MS, LuckyLogic.backoffMs(30))
        assertTrue(LuckyLogic.backoffMs(1_000) <= LuckyLogic.BACKOFF_MAX_MS)
    }

    // ---------------------------------------------------------- 识别

    @Test
    fun noEvidenceMeansNoMatch() {
        // 两条判据都没配置 → 连完整性可疑都算不上
        assertEquals(LuckyLogic.Match.NONE, LuckyLogic.classify(signal(content = "[微信红包]"), empty))
        assertEquals(LuckyLogic.Match.NONE, LuckyLogic.classify(signal(type = 2001), empty))
        assertEquals(LuckyLogic.Match.NONE, LuckyLogic.classify(signal(content = null), cfg(markers = setOf("红包"))))
    }

    @Test
    fun strongTypeEvidenceWinsOverWeakText() {
        val s = signal(type = 0x10000000 or 2001, content = "发个红包呗")
        assertEquals(LuckyLogic.Match.HIT, LuckyLogic.classify(s, cfg(types = setOf(2001), markers = setOf("红包"))))
    }

    @Test
    fun weakTextEvidenceIsOnlySuspect() {
        val s = signal(content = "[微信红包]恭喜发财")
        assertEquals(LuckyLogic.Match.SUSPECT, LuckyLogic.classify(s, cfg(markers = setOf("微信红包"))))
        // 大小写不敏感：xml 里的 type 节点经常是 <luckymoney>
        assertEquals(
            LuckyLogic.Match.SUSPECT,
            LuckyLogic.classify(signal(content = "<LUCKYMONEY>"), cfg(markers = setOf("luckymoney")))
        )
    }

    // ---------------------------------------------------------- 名单范围

    @Test
    fun scopeDefaultsToEverything() {
        assertTrue(LuckyLogic.inScope(room, null, emptySet(), emptySet()))
        assertTrue(LuckyLogic.inScope("wxid_someone", null, emptySet(), emptySet()))
        // talker 认不出来（空）就不该管
        assertFalse(LuckyLogic.inScope("", null, emptySet(), emptySet()))
        assertFalse(LuckyLogic.inScope(null, null, emptySet(), emptySet()))
    }

    @Test
    fun scopeAllowListIsWhenNotEmpty() {
        val allow = setOf(room)
        assertTrue(LuckyLogic.inScope(room, null, allow, emptySet()))
        assertFalse(LuckyLogic.inScope("other@chatroom", null, allow, emptySet()))
    }

    @Test
    fun scopeExcludeWinsOverAllow() {
        val allow = setOf(room)
        val exclude = setOf(room)
        assertFalse(LuckyLogic.inScope(room, null, allow, exclude))
    }

    @Test
    fun scopeMatchesGroupNameToo() {
        val allow = setOf("家庭群")
        // 传 talker 匹配不上时，群显示名要能补位
        assertTrue(LuckyLogic.inScope(room, "家庭群", allow, emptySet()))
        // 群名拿不到（null）就只能看 talker
        assertFalse(LuckyLogic.inScope(room, null, allow, emptySet()))
    }

    // ---------------------------------------------------------- 幂等 / 退避台账

    @Test
    fun ledgerClaimsOnlyOnce() {
        val l = LuckyLogic.Ledger()
        assertTrue(l.first("k", 0L))
        assertFalse(l.first("k", 10L))
        assertTrue(l.first("k2", 10L))
        // 空 key 不参与幂等（认不出是哪个红包就别占名额，也别把两条消息混成一个）
        assertFalse(l.first("", 10L))
    }

    @Test
    fun ledgerFailureSetsCooldown() {
        val l = LuckyLogic.Ledger()
        l.first("k", 0L)
        assertEquals(0, l.failsOf("k"))
        assertEquals(1, l.noteFail("k", 5_000L))
        assertEquals(1_000L, l.cooldownLeft("k", 5_000L))
        assertEquals(0L, l.cooldownLeft("k", 6_000L))
        assertEquals(2, l.noteFail("k", 6_000L))
        assertEquals(2_000L, l.cooldownLeft("k", 6_000L))
    }

    @Test
    fun ledgerDropsExpiredRows() {
        val l = LuckyLogic.Ledger(ttlMs = 100L, maxRows = 4)
        l.first("a", 0L)
        l.first("b", 10L)
        assertEquals(2, l.size())
        l.first("c", 200L) // 触发清理，a/b 都过期（200-0 > 100）
        assertEquals(1, l.size())
    }

    @Test
    fun ledgerCapsItsSize() {
        val l = LuckyLogic.Ledger(ttlMs = 100_000L, maxRows = 10)
        for (i in 0 until 20) l.first("k$i", i.toLong())
        // 超额时被丢掉一部分（具体丢多少是实现细节，但不能无上限地涨）
        assertTrue("size=${l.size()}", l.size() <= 10)
    }

    // ---------------------------------------------------------- 决策：安全默认

    @Test
    fun noForensicsNoAction() {
        // ⚠️ 红线：取证前（默认配置）不许有任何动作。改坏了这条必须立刻发现。
        val l = LuckyLogic.Ledger()
        val s = signal()
        assertTrue(LuckyLogic.decide(s, empty, l, 0L) is LuckyLogic.Verdict.Skip)
        assertTrue(LuckyLogic.decide(s, cfg(enabled = false), l, 0L) is LuckyLogic.Verdict.Skip)
        // 只配了文案那条弱判据：最多落到 Observe（记录），绝不进 Wait
        val weak = LuckyLogic.decide(
            signal(content = "[微信红包]"), cfg(markers = setOf("微信红包")), l, 0L
        )
        assertTrue(weak is LuckyLogic.Verdict.Observe)
        assertEquals(0, l.size())
    }

    @Test
    fun selfSentAndUnknownSessionAreSkipped() {
        val l = LuckyLogic.Ledger()
        val c = cfg(types = setOf(2001))
        assertTrue(LuckyLogic.decide(signal(isSend = true), c, l, 0L) is LuckyLogic.Verdict.Skip)
        assertTrue(LuckyLogic.decide(signal(talker = ""), c, l, 0L) is LuckyLogic.Verdict.Skip)
        assertEquals(0, l.size())
    }

    @Test
    fun outOfScopeIsSkippedBeforeTouchingLedger() {
        val l = LuckyLogic.Ledger()
        val c = cfg(types = setOf(2001), exclude = setOf(room))
        val v = LuckyLogic.decide(signal(), c, l, 0L)
        assertTrue(v is LuckyLogic.Verdict.Skip)
        assertEquals("scope", (v as LuckyLogic.Verdict.Skip).reason)
        assertEquals(0, l.size())
    }

    // ---------------------------------------------------------- 决策：Happy path

    @Test
    fun firstHitWaitsWithinClampedDelay() {
        val l = LuckyLogic.Ledger()
        val v = LuckyLogic.decide(signal(), cfg(types = setOf(2001), delayMs = 0), l, 0L)
        assertTrue(v is LuckyLogic.Verdict.Wait)
        v as LuckyLogic.Verdict.Wait
        assertEquals("svr-1", v.key)
        assertEquals(1, v.attempt)
        // delayMs=0 被拉到下限：这是「防秒抢」最关键的一条断言
        assertEquals(LuckyLogic.MIN_DELAY_MS, v.delayMs)
        assertTrue(l.size() == 1)
    }

    @Test
    fun secondDecisionOnSamePacketIsSkipped() {
        val l = LuckyLogic.Ledger()
        val c = cfg(types = setOf(2001))
        assertTrue(LuckyLogic.decide(signal(), c, l, 0L) is LuckyLogic.Verdict.Wait)
        val again = LuckyLogic.decide(signal(), c, l, 10L)
        assertTrue(again is LuckyLogic.Verdict.Skip)
        assertEquals("dup", (again as LuckyLogic.Verdict.Skip).reason)
    }

    @Test
    fun lowTypeFromRealWeChatValueDecides() {
        // type 高位是微信自己的标志位，只有低位参与比对
        val l = LuckyLogic.Ledger()
        val c = cfg(types = setOf(2001))
        val v = LuckyLogic.decide(signal(type = 0x10000000 or 2001), c, l, 0L)
        assertTrue(v is LuckyLogic.Verdict.Wait)
    }

    @Test
    fun cooldownDefersTheNextWait() {
        val l = LuckyLogic.Ledger()
        val c = cfg(types = setOf(2001), delayMs = 300)
        l.first("svr-1", 0L)
        l.noteFail("svr-1", 0L) // 第 1 次失败 → 冷却 1s
        val v = LuckyLogic.decide(signal(), c, l, 400L)
        assertTrue(v is LuckyLogic.Verdict.Wait)
        assertEquals(600, (v as LuckyLogic.Verdict.Wait).delayMs) // 1000 - 400
        val later = LuckyLogic.decide(signal(), c, l, 1_500L)
        // 冷却过了也不会再给一次 Wait：**同一个红包只决策一次**是硬约束，
        // 冷却只服务于「调用方明确报了失败」的情况，不是无限重试券
        assertTrue(later is LuckyLogic.Verdict.Skip)
        assertEquals("dup", (later as LuckyLogic.Verdict.Skip).reason)
    }

    @Test
    fun tooManyAttemptsFallsBackToManual() {
        val l = LuckyLogic.Ledger()
        val c = cfg(types = setOf(2001), maxAttempts = 2)
        l.first("svr-1", 0L)
        l.noteFail("svr-1", 0L)
        l.noteFail("svr-1", 5_000L) // fails=2 == maxAttempts
        val v = LuckyLogic.decide(signal(), c, l, 20_000L)
        assertTrue(v is LuckyLogic.Verdict.Manual)
    }

    @Test
    fun syntheticKeyIsStableAndDistinct() {
        val a = LuckyLogic.Signal(id = "", talker = room, createMs = 42L, content = "x")
        val b = LuckyLogic.Signal(id = "", talker = room, createMs = 42L, content = "x")
        val c = LuckyLogic.Signal(id = "", talker = room, createMs = 43L, content = "x")
        assertEquals(LuckyLogic.keyOf(a), LuckyLogic.keyOf(b))
        assertTrue(LuckyLogic.keyOf(a) != LuckyLogic.keyOf(c))
        // 没有会话、没有 id → 合成不出来 → 跳过
        assertEquals("", LuckyLogic.keyOf(LuckyLogic.Signal(talker = "")))
    }
}
