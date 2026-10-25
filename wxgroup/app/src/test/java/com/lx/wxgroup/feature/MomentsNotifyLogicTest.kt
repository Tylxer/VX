package com.lx.wxgroup.feature

import com.lx.wxgroup.feature.MomentsNotifyLogic.Gate
import com.lx.wxgroup.feature.MomentsNotifyLogic.Kind
import com.lx.wxgroup.feature.MomentsNotifyLogic.Reason
import com.lx.wxgroup.feature.MomentsNotifyLogic.Reason.DB_NOT_READY
import com.lx.wxgroup.feature.MomentsNotifyLogic.Reason.MKDIRS
import com.lx.wxgroup.feature.MomentsNotifyLogic.Reason.MKDIRS_ITEM
import com.lx.wxgroup.feature.MomentsNotifyLogic.Reason.NOT_DIR
import com.lx.wxgroup.feature.MomentsNotifyLogic.Reason.READONLY
import com.lx.wxgroup.feature.MomentsNotifyLogic.Reason.SPACE
import com.lx.wxgroup.feature.MomentsNotifyLogic.Reason.SPACE_ABORT
import com.lx.wxgroup.feature.MomentsNotifyLogic.Reason.THROW
import com.lx.wxgroup.feature.MomentsNotifyLogic.Reason.WRITE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MomentsNotifyLogic] 的判据回归测试。
 *
 * 锁住四类纯判据：失败原因分类、弹/不弹决策、跨轮去重 latch 状态机、**接线本身**（[Gate]）。
 * 这些写错了要么漏弹（用户不知道导出坏了），要么反复弹（每小时骚扰一次），
 * 要么弹错文案（用户以为路径坏了其实是空间不足）——都得上真机才发现，先在这里锁住。
 *
 * ## R19 变更（对应 `docs/_r18_f4b_review.md`）
 * - 「多轮」用例不再繁衍 `MomentsExport.runBlocking` 的仿真副本，改成直接驱动 [Gate] ——
 *   上线跑的就是这里跑的那份代码（专审 §5.4：仿真接线改坏了测试一条都不红）。
 * - 新增「未送达 / 未受理 / 异步放弃 ⇒ 不占 latch ⇒ 下轮重试」三例（修 F2）。
 * - 新增全枚举 × 期望表断言，钉死 `isNotifiable` 的黑名单风险（专审 §1）。
 * - per-item 原因（[WRITE] / [MKDIRS_ITEM]）改为「默认不弹 + 同因计数超阈值升级」（修 F5）。
 */
class MomentsNotifyLogicTest {

    // ---------------------------------------------------------------- 弹/不弹

    /**
     * 持久性故障必须弹：路径/权限/空间/整轮异常，用户不处理就不会自己好。
     *
     * ⚠️ [WRITE] 已不在此列：它与 [MKDIRS_ITEM] 在四个维度上同构，
     * 归入 [Kind.ESCALATE]「默认不弹、超阈值才升级」。
     */
    @Test
    fun persistentFailuresAreNotifiable() {
        assertTrue(MomentsNotifyLogic.isNotifiable(NOT_DIR))
        assertTrue(MomentsNotifyLogic.isNotifiable(MKDIRS))
        assertTrue(MomentsNotifyLogic.isNotifiable(READONLY))
        assertTrue(MomentsNotifyLogic.isNotifiable(SPACE))
        assertTrue(MomentsNotifyLogic.isNotifiable(SPACE_ABORT))
        assertTrue(MomentsNotifyLogic.isNotifiable(THROW))
    }

    /**
     * 默认不弹的：
     * - DB_NOT_READY：微信启动早期数据库没就绪，几秒后自动恢复；
     * - MKDIRS_ITEM / WRITE：循环内单条失败（坏昵称、个别文件被占用等），
     *   不阻断整轮，**只有同因失败越过阈值才升级为「弹」**（见 [perItemEscalatesOnSameRoundThreshold]）。
     */
    @Test
    fun transientFailuresAreSilent() {
        assertFalse(MomentsNotifyLogic.isNotifiable(DB_NOT_READY))
        assertFalse(MomentsNotifyLogic.isNotifiable(MKDIRS_ITEM))
        assertFalse("WRITE 未升级时不该弹", MomentsNotifyLogic.isNotifiable(WRITE))
    }

    /** 越过升级阈值的单条故障要升级为「可弹」 */
    @Test
    fun escalatedPerItemFailureIsNotifiable() {
        assertTrue(MomentsNotifyLogic.isNotifiable(WRITE, escalated = true))
        assertTrue(MomentsNotifyLogic.isNotifiable(MKDIRS_ITEM, escalated = true))
    }

    /**
     * **全枚举 × 期望表**（专审 §1 的附带风险）。
     *
     * 旧判据是黑名单 `reason != DB_NOT_READY && reason != MKDIRS_ITEM`，
     * 新增枚举会默认「弹」，而没人提醒你去决定它该不该弹。
     * ⇒ 这里放一张显式期望表：新增枚举不同步更新本表 ⇒ 断言挂（期望值取到 null）。
     */
    @Test
    fun everyReasonHasAnExplicitKind() {
        val expected = mapOf(
            DB_NOT_READY to Kind.SILENT,
            NOT_DIR to Kind.ALWAYS,
            MKDIRS to Kind.ALWAYS,
            READONLY to Kind.ALWAYS,
            SPACE to Kind.ALWAYS,
            SPACE_ABORT to Kind.ALWAYS,
            THROW to Kind.ALWAYS,
            MKDIRS_ITEM to Kind.ESCALATE,
            WRITE to Kind.ESCALATE,
        )
        assertEquals("⛔ 新增/删除 Reason 必须同步更新本期望表", Reason.values().size, expected.size)
        for (r in Reason.values()) {
            assertEquals("reason=$r 缺少显式 Kind", expected[r], MomentsNotifyLogic.kindOf(r))
        }
    }

    /** 静态（未升级）口径下「弹 / 不弹」的全枚举快照 */
    @Test
    fun staticNotifiabilityMatchesExpectedTable() {
        val expected = mapOf(
            DB_NOT_READY to false,
            NOT_DIR to true,
            MKDIRS to true,
            READONLY to true,
            SPACE to true,
            SPACE_ABORT to true,
            THROW to true,
            MKDIRS_ITEM to false,
            WRITE to false,
        )
        assertEquals(Reason.values().size, expected.size)
        for (r in Reason.values()) {
            assertEquals("reason=$r", expected[r], MomentsNotifyLogic.isNotifiable(r))
            assertEquals(
                "reason=$r 升级后口径",
                expected[r] == true || MomentsNotifyLogic.kindOf(r) == Kind.ESCALATE,
                MomentsNotifyLogic.isNotifiable(r, escalated = true),
            )
        }
    }

    // ---------------------------------------------------------------- 文案映射

    /** 9 个原因都有非空文案，且标题统一（避免某些原因忘了接文案静默弹个空框） */
    @Test
    fun everyReasonHasMessage() {
        for (r in Reason.values()) {
            val m = MomentsNotifyLogic.messageFor(r)
            assertFalse("reason=$r body empty", m.body.isBlank())
            assertEquals("朋友圈自动导出失败", m.title)
        }
    }

    /** 不同原因的正文彼此不同（分类别糊成一坨；空间不足不能说成路径问题） */
    @Test
    fun messagesAreDistinctPerReason() {
        val bodies = Reason.values().map { MomentsNotifyLogic.messageFor(it).body }
        assertEquals(bodies.size, bodies.toSet().size)
    }

    // ---------------------------------------------------------------- 去重 latch

    /** 同一原因只弹一次：第一次 shouldFire=true，markFired 后恒 false */
    @Test
    fun latchFiresOncePerReason() {
        val latch = MomentsNotifyLogic.Latch()
        assertTrue(latch.shouldFire(SPACE))
        latch.markFired(SPACE)
        assertFalse(latch.shouldFire(SPACE))
        assertFalse(latch.shouldFire(SPACE))
    }

    /** 不同原因互不影响：SPACE 弹过了，WRITE 还该弹（用户需要知道是哪个原因） */
    @Test
    fun latchIsPerReason() {
        val latch = MomentsNotifyLogic.Latch()
        latch.markFired(SPACE)
        assertTrue(latch.shouldFire(WRITE))
        assertFalse(latch.shouldFire(SPACE))
    }

    /** 成功导出复位：fired 清空后，同一原因重新该弹 */
    @Test
    fun resetAfterSuccessReallows() {
        val latch = MomentsNotifyLogic.Latch()
        latch.markFired(MKDIRS)
        latch.markFired(SPACE)
        assertEquals(2, latch.firedReasons().size)

        latch.resetAll()
        assertTrue(latch.firedReasons().isEmpty())
        assertTrue(latch.shouldFire(MKDIRS))
        assertTrue(latch.shouldFire(SPACE))
    }

    // ---------------------------------------------------------------- decide 纯函数
    //
    // ⛔ 这一节是 R18 复核补的。原有 7 条测试只覆盖 Latch 的三个方法本身，
    //    **没有一条测「多轮驱动」**，所以下面这个 P0 漏到了交付之后：
    //
    //    早先接线用 `notify()` 的返回值（有没有弹窗）当「本轮是否成功」：
    //      第1轮 弹了       → clean=false，latch 记住
    //      第2轮 去重不弹   → clean=true  → resetAll() 把 latch 清了
    //      第3轮 又弹       → ...
    //    ⇒ 同一持久性故障每隔一轮弹一次。
    //
    //    教训：**只测单个方法的行为，测不出「多个方法接起来跑多轮」的错误。**

    /** 值得弹的原因：算失败，且首次要弹 */
    @Test
    fun decidePersistentFirstTimeIsFailureAndNotify() {
        val latch = MomentsNotifyLogic.Latch()
        val d = MomentsNotifyLogic.decide(latch, NOT_DIR)
        assertTrue("持久性故障必须算本轮失败", d.countsAsFailure)
        assertTrue("首次必须弹", d.doNotify)
    }

    /**
     * **核心回归**：latch 已记住后，`doNotify=false` 但 **`countsAsFailure` 仍必须为 true**。
     *
     * 这是上一轮 P0 的正中心 —— 若这里退化成 false，`clean` 就会误判成功、
     * `resetAll()` 清空 latch，于是「每隔一轮弹一次」的骚扰循环复活。
     */
    @Test
    fun decideAlreadyFiredStillCountsAsFailure() {
        val latch = MomentsNotifyLogic.Latch()
        latch.markFired(WRITE)

        val d = MomentsNotifyLogic.decide(latch, WRITE, escalated = true)
        assertTrue("⛔ 去重命中 ≠ 本轮成功！必须仍算失败，否则 latch 会被 resetAll 清掉", d.countsAsFailure)
        assertFalse("已经弹过，这次不该再弹", d.doNotify)
    }

    /** 不弹的原因：既不算失败（不 reset latch），也不弹 */
    @Test
    fun decideTransientIsNeitherFailureNorNotify() {
        val latch = MomentsNotifyLogic.Latch()
        for (r in listOf(DB_NOT_READY, MKDIRS_ITEM, WRITE)) {
            val d = MomentsNotifyLogic.decide(latch, r)
            assertFalse("reason=$r 不该算失败", d.countsAsFailure)
            assertFalse("reason=$r 不该弹", d.doNotify)
        }
    }

    /** 同一 Grid：单条故障未升级 ⇒ 静默；升级后 ⇒ 算失败 + 弹 */
    @Test
    fun decidePerItemBelowThresholdIsSilent() {
        val latch = MomentsNotifyLogic.Latch()
        val below = MomentsNotifyLogic.decide(latch, WRITE)
        assertFalse(below.countsAsFailure)
        assertFalse(below.doNotify)

        val escalated = MomentsNotifyLogic.decide(latch, WRITE, escalated = true)
        assertTrue(escalated.countsAsFailure)
        assertTrue(escalated.doNotify)
    }

    /**
     * [MomentsNotifyLogic.decide] 必须**纯**（专审 §5.2）：算完不许偷偷占 latch。
     * 若哪天有人在 decide 里顺手 `markFired`，13 条用例一条都不会红（幂等），只有这条会红。
     */
    @Test
    fun decideDoesNotMutateLatch() {
        val latch = MomentsNotifyLogic.Latch()
        repeat(3) { MomentsNotifyLogic.decide(latch, READONLY) }
        assertTrue("decide 不得有副作用", latch.firedReasons().isEmpty())
        assertTrue(latch.shouldFire(READONLY))
    }

    // ---------------------------------------------------------------- Escalation 升级状态机

    /** 阈值：同一轮里同因失败 ≥ 3 次 ⇒ 升级 */
    @Test
    fun perItemEscalatesOnSameRoundThreshold() {
        val esc = MomentsNotifyLogic.Escalation()
        esc.beginRound()
        assertFalse("第 1 次仍可能是偶发", esc.note(WRITE))
        assertFalse("第 2 次仍可能是偶发", esc.note(WRITE))
        assertTrue("第 3 次必须升级", esc.note(WRITE))
        assertEquals(3, esc.thisRoundCount(WRITE))
    }

    /** 阈值：连续第 2 轮还在报同一个原因 ⇒ 一上来就升级 */
    @Test
    fun perItemEscalatesOnConsecutiveRounds() {
        val esc = MomentsNotifyLogic.Escalation()
        esc.beginRound()
        assertFalse(esc.note(WRITE))
        esc.endRound()
        assertEquals(1, esc.consecutiveRounds(WRITE))

        esc.beginRound()
        assertTrue("连续第 2 轮 ⇒ 首笔就该升级", esc.note(WRITE))
    }

    /** 中间出现一次干净轮 ⇒ 连续计数清零，回到「默认不弹」 */
    @Test
    fun cleanRoundClearsEscalationStreak() {
        val esc = MomentsNotifyLogic.Escalation()
        esc.beginRound()
        esc.note(WRITE)
        esc.endRound()
        assertEquals(1, esc.consecutiveRounds(WRITE))

        esc.beginRound()      // 本轮没有任何失败
        esc.endRound()
        assertEquals(0, esc.consecutiveRounds(WRITE))

        esc.beginRound()
        assertFalse("干净轮之后回到默认不弹", esc.note(WRITE))
    }

    // ---------------------------------------------------------------- onFailure / Gate（真接线）
    //
    // ⛔ 这一节 R19 新增。专审 §5.4 指出：此前多轮用例是对 `MomentsExport.notify()`
    //    的**仿真**，把它改坏（markFired 挪到 if 之外 / 返回值改回「有没有弹」）
    //    一条测试都不会红，而 P0 与 F1/F2 恰恰住在那段真接线里。
    //    ⇒ 下面驱动的是 [Gate]，即 `MomentsExport` 即将接进去的同一份代码。

    /**
     * **端到端多轮**：同一持久性故障连续 5 轮，**总共只弹 1 次**。
     *
     * 修前：弹 3 次（隔轮一次）。修后：弹 1 次。
     */
    @Test
    fun fiveConsecutiveFailuresNotifyExactlyOnce() {
        val gate = Gate()
        var notifyCount = 0
        repeat(5) { round(gate, SPACE_ABORT) { _, cb -> notifyCount++; cb(true); true } }
        assertEquals("⛔ 5 轮同故障必须只弹 1 次（修前是 3 次）", 1, notifyCount)
    }

    /**
     * **未受理**（sender 返回 false）⇒ 不占 latch ⇒ 下轮还会再试。
     */
    @Test
    fun notAcceptedDoesNotOccupyLatchAndRetriesNextRound() {
        val gate = Gate()
        var attempts = 0
        repeat(3) { round(gate, READONLY) { _, _ -> attempts++; false } }   // 受理失败，回调无从谈起
        assertEquals("受理失败必须每轮重试", 3, attempts)
        assertTrue("受理失败绝不能占 latch", gate.firedReasons().isEmpty())
    }

    /**
     * **受理了但没送出去**（`delivered=false`，修 F2 的正中心）：
     * 老实现里 `notify()` 返回 true ⇒ 占 latch ⇒ 用户没看到窗却再也不弹。
     */
    @Test
    fun notDeliveredDoesNotOccupyLatchAndRetriesNextRound() {
        val gate = Gate()
        var attempts = 0
        repeat(3) { round(gate, READONLY) { _, cb -> attempts++; cb(false); true } }
        assertEquals("没送达就必须下轮重试", 3, attempts)
        assertTrue("⛔ 没送达绝不能占 latch", gate.firedReasons().isEmpty())
    }

    /**
     * **异步放弃**（无 Activity → 拉起 → 1.5s 后仍无 Activity ⇒ `onOutcome(false)`）：
     * 文档自述「属环境问题，下轮重试」，旧实现因为立刻 return true 而永远做不到。
     * 这里用「回调晚于本轮结束才到达」来仿真。
     */
    @Test
    fun asyncGiveUpRetriesNextRound() {
        val gate = Gate()
        val pending = ArrayList<(Boolean) -> Unit>()
        var attempts = 0

        repeat(3) {
            gate.beginRound()
            var clean = true
            val failed = gate.onFailure(READONLY) { _, cb ->
                attempts++
                pending.add(cb)          // 异步：本轮结束后才交付结果
                true
            }
            if (failed) clean = false
            gate.endRound(clean)
            pending.forEach { it(false) }   // 1.5s 后仍无 Activity ⇒ 放弃
            pending.clear()
        }

        assertEquals("异步放弃不算送达 ⇒ 每轮都该重试", 3, attempts)
        assertTrue(gate.firedReasons().isEmpty())
    }

    /**
     * 「中途成功要复位」走**因果链**（不再像旧版那样直接调 `resetAll()` 绕过）：
     * 失败 2 轮 → **无故障的一轮**（clean=true → endRound 内部 resetAll）→ 再失败，
     * 总共应弹 **2 次**。
     */
    @Test
    fun successRoundResetsSoNextFailureNotifiesAgain() {
        val gate = Gate()
        var notifyCount = 0
        val sender: (MomentsNotifyLogic.Message, (Boolean) -> Unit) -> Boolean =
            { _, cb -> notifyCount++; cb(true); true }

        round(gate, READONLY, sender = sender)
        round(gate, READONLY, sender = sender)
        assertEquals(1, notifyCount)

        gate.beginRound()          // 干净轮：一笔故障都没有
        gate.endRound(clean = true)

        round(gate, READONLY, sender = sender)
        assertEquals("成功后再次故障必须重新提醒", 2, notifyCount)
    }

    /** 两个不同原因交替：各自只弹一次，总共 2 次（互不挤占） */
    @Test
    fun alternatingReasonsEachNotifyOnce() {
        val gate = Gate()
        val counts = HashMap<Reason, Int>()

        for (r in listOf(READONLY, SPACE_ABORT, READONLY, SPACE_ABORT, READONLY)) {
            round(gate, r) { _, cb -> counts[r] = (counts[r] ?: 0) + 1; cb(true); true }
        }

        assertEquals(1, counts[READONLY])
        assertEquals(1, counts[SPACE_ABORT])
    }

    /**
     * per-item（[WRITE]）判据：第 1 轮 1 次静默 → 第 2 轮（连续第 2 轮）升级弹 1 次
     * → 第 3 轮起 latch 已占，不再弹。
     */
    @Test
    fun perItemFailureNotifiesOnceAfterEscalation() {
        val gate = Gate()
        var notifyCount = 0
        val sender: (MomentsNotifyLogic.Message, (Boolean) -> Unit) -> Boolean =
            { _, cb -> notifyCount++; cb(true); true }

        round(gate, WRITE, sender = sender)
        assertEquals("第 1 轮单条偶发不该打扰用户", 0, notifyCount)

        round(gate, WRITE, sender = sender)
        assertEquals("连续第 2 轮还在报 ⇒ 升级，弹一次", 1, notifyCount)

        round(gate, WRITE, sender = sender)
        round(gate, WRITE, sender = sender)
        assertEquals("已送达 ⇒ latch 占住，不再重复弹", 1, notifyCount)
    }

    /** 同一轮内 3 次同因失败 ⇒ 第 3 笔才升级（前 2 笔静默） */
    @Test
    fun sameRoundThresholdEscalatesPerItemFailure() {
        val gate = Gate()
        var notifyCount = 0
        gate.beginRound()
        repeat(3) { i ->
            gate.onFailure(WRITE) { _, cb -> notifyCount++; cb(true); true }
            if (i < 2) assertEquals("第 ${i + 1} 笔还没到阈值，不该弹", 0, notifyCount)
        }
        assertEquals(1, notifyCount)
        assertTrue(WRITE in gate.firedReasons())
    }

    /**
     * 未升级的单条失败**不算**本轮失败（保持原有「不影响 clean」的语义），
     * 也不会误占 latch —— 谁也不许因为偶发的一两个坏昵称让整轮变「不干净」。
     */
    @Test
    fun perItemBelowThresholdKeepsRoundClean() {
        val gate = Gate()
        var notifyCount = 0
        gate.beginRound()
        var clean = true
        repeat(2) {
            if (gate.onFailure(WRITE) { _, cb -> notifyCount++; cb(true); true }) clean = false
        }
        gate.endRound(clean)

        assertTrue("未越过阈值的单条失败不该算本轮失败", clean)
        assertEquals(0, notifyCount)
        assertTrue(gate.firedReasons().isEmpty())
    }

    /**
     * [Gate.onFailure] 的返回值 = **本轮是否发生故障**（与「有没有真的弹出去」无关）
     * —— 这是上一个 P0 的关键一行，搬进纯层后同样要有回归。
     */
    @Test
    fun onFailureReturnsCountsAsFailureNotDelivered() {
        val gate = Gate()
        gate.beginRound()

        // 去重命中后：既不弹，返回值却仍必须是 true（否则 clean 误判 ⇒ resetAll ⇒ 隔轮骚扰）
        gate.onFailure(READONLY) { _, cb -> cb(true); true }
        assertTrue("⛔ 去重命中 ≠ 本轮成功", gate.onFailure(READONLY) { _, _ -> true })

        // 不弹的原因：返回 false
        assertFalse(gate.onFailure(DB_NOT_READY) { _, _ -> true })
        assertFalse(gate.onFailure(WRITE) { _, _ -> true })
    }

    // ------------------------------------------------ 早退轮（abortRound，R20 F-1 地雷）

    /**
     * **早退轮必须结算 streak**（`gate-review` 报的 F-1，主会话补测）。
     *
     * ## 这颗雷长什么样
     * `MomentsExport.runBlocking()` 有 5 条提前 `return`（db not ready / not-dir /
     * mkdirs / readonly / space）。它们绕过 `endRound`，于是 `Escalation.endRound()`
     * **也被跳过** ⇒ streak 恒 0 ⇒ ESCALATE 类原因（`WRITE` / `MKDIRS_ITEM`）
     * **永远升不了级 ⇒ 永久漏弹**。
     *
     * ⚠️ 眼下不体现，因为那 5 条早退用的 Reason 全是 ALWAYS/SILENT（不读 `escalated`）。
     * 但只要将来有人往早退分支加一个 ESCALATE 原因就中招，且**不崩、无日志、单测不红**。
     *
     * 本条锁的是：**早退处调 `abortRound()` 之后，streak 真的会累积**。
     */
    @Test
    fun abortRoundStillCountsStreakSoEscalateCanTrigger() {
        val gate = Gate()

        // 连续 2 轮「早退」（每轮只发生一笔 WRITE，然后走 abortRound 而非 endRound）
        repeat(2) {
            gate.beginRound()
            gate.onFailure(WRITE) { _, cb -> cb(true); true }
            gate.abortRound()
        }

        assertEquals(
            "⛔ 早退轮也必须把 streak 结算到 2（CONSECUTIVE_ROUNDS=2）",
            2,
            gate.consecutiveRounds(WRITE),
        )

        // 第 3 轮：streak 已够 ⇒ 即使本轮只失败 1 笔，也应越过阈值
        gate.beginRound()
        assertTrue(
            "⛔ streak 达标后单笔 WRITE 就该升级为「值得弹」",
            gate.onFailure(WRITE) { _, cb -> cb(true); true },
        )
    }

    /**
     * **对照组**：早退处**什么都不调**会怎样 —— streak 恒 0。
     *
     * 这条不测「代码实现」，而是把 F-1 描述的故障**复现出来**存档：
     * 它证明「漏调 `abortRound`」确实会让 ESCALATE 永远上不去。
     * 若将来有人把 `abortRound()` 从早退处删掉，本条仍绿（它模拟的就是错误接线），
     * 但上一条会红 —— 所以两条必须成对存在。
     */
    @Test
    fun skippingAbortRoundFreezesStreakAtZero() {
        val gate = Gate()
        repeat(3) {
            gate.beginRound()
            gate.onFailure(WRITE) { _, cb -> cb(true); true }
            // ⛔ 刻意不调 abortRound / endRound —— 复现 F-1 的漏接线
        }
        assertEquals("漏调结算 ⇒ streak 恒 0（这正是 F-1 描述的静默失效）", 0, gate.consecutiveRounds(WRITE))
    }

    /**
     * **`abortRound` 绝不 reset latch**（它与 `endRound` 的唯一区别）。
     *
     * 早退 = 失败轮，失败轮清掉 latch 就变回「每隔一轮弹一次」那个 P0。
     */
    @Test
    fun abortRoundNeverResetsLatch() {
        val gate = Gate()
        var notifyCount = 0

        // 第 1 轮正常跑完并弹了窗（占 latch）
        gate.beginRound()
        gate.onFailure(READONLY) { _, cb -> notifyCount++; cb(true); true }
        gate.endRound(clean = false)

        // 第 2 轮早退
        gate.beginRound()
        gate.onFailure(READONLY) { _, cb -> notifyCount++; cb(true); true }
        gate.abortRound()

        assertEquals("⛔ 早退绝不能清掉 latch（否则退化成隔轮弹一次）", 1, notifyCount)
        assertTrue("latch 应仍记着 READONLY", READONLY in gate.firedReasons())
    }

    // ---------------------------------------------------------------- 驱动助手

    /**
     * 跑完**一轮**（模拟 `MomentsExport.runBlocking` 的接线顺序）：
     * `beginRound` → 失败若干笔 `onFailure` → `endRound(clean)`。
     *
     * @param sender 返回「是否受理」；`cb(true)` = 真的送达到用户眼前
     */
    private fun round(
        gate: Gate,
        reason: Reason,
        times: Int = 1,
        sender: (MomentsNotifyLogic.Message, (Boolean) -> Unit) -> Boolean,
    ) {
        gate.beginRound()
        var clean = true
        repeat(times) {
            if (gate.onFailure(reason, sender)) clean = false
        }
        gate.endRound(clean)
    }
}
