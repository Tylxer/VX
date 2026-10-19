package com.lx.wxgroup.feature

import com.lx.wxgroup.feature.MassSendLogic.Event
import com.lx.wxgroup.feature.MassSendLogic.ItemState
import com.lx.wxgroup.feature.MassSendLogic.Outcome
import com.lx.wxgroup.feature.MassSendLogic.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 需求 6.5「群发」纯逻辑层（[MassSendLogic]）的单测。
 *
 * ⚠️ 给嵌套类型起短名**不能**写 `private val Ev = MassSendLogic.Event` —— sealed class 和
 * enum class 都不是对象，不能当表达式用（编译期报 "does not have a companion object"）。
 * 只有 `object` 可以这样赋值。嵌套类型一律走 import。
 *
 * 锁的是需求里那几条**最容易写错、且错了要上一轮真机才发现**的口径：
 *  1. 目标去重（同人被多个分组/标签选中只发一次，来源要留全）；
 *  2. 间隔边界（设定 0 秒 / 随机源 0 与 1 / 越界钳制）；
 *  3. 单条失败「重试 1 次 → 仍失败则跳过继续」；
 *  4. 连续失败 5 条自动中止（且不能被「没有发送链路」误触发）；
 *  5. 汇总「完成 n/m」+ 失败数 + 未发送数。
 *
 * 状态机是纯函数（[MassSendLogic.reduce]），整条链路可以完全在 JVM 上跑完。
 *
 * ⚠️ Kotlin 不支持局部 typealias，所以嵌套类型走 import（上面的 `import ...MassSendLogic.Event`）。
 */
class MassSendLogicTest {

    private fun jobOf(vararg wxids: String) = MassSendLogic.start(wxids.map { MassSendLogic.Target(it) })

    /** 派发 → 回报，一次完整的单条尝试 */
    private fun attempt(j: MassSendLogic.Job, wxid: String, outcome: Outcome, reason: String = ""): MassSendLogic.Job =
        MassSendLogic.reduce(MassSendLogic.reduce(j, Event.Dispatch), Event.Result(wxid, outcome, reason))

    // ------------------------------------------------------------ 目标去重

    @Test
    fun resolveDedupsAcrossGroupsAndLabels() {
        val buckets = MassSendLogic.Buckets(
            groups = mapOf("1" to listOf("a", "b", "c")),
            labels = mapOf("L" to listOf("b", "d")),
        )
        val t = MassSendLogic.resolveTargets(listOf("g:1", "t:L", "a"), buckets)
        // 顺序按选择顺序；b 被分组和标签同时选中 → 只出现一次
        assertEquals(listOf("a", "b", "c", "d"), t.map { it.wxid })
    }

    @Test
    fun resolveRecordsAllSources() {
        val buckets = MassSendLogic.Buckets(
            groups = mapOf("1" to listOf("b")),
            labels = mapOf("L" to listOf("b")),
        )
        val t = MassSendLogic.resolveTargets(listOf("g:1", "t:L"), buckets)
        assertEquals(1, t.size)
        assertEquals(setOf("g:1", "t:L"), t[0].sources.toSet())
    }

    @Test
    fun resolveUsesNameOfAndSkipsExcluded() {
        val buckets = MassSendLogic.Buckets(groups = mapOf("1" to listOf("a", "b")))
        val t = MassSendLogic.resolveTargets(listOf("g:1"), buckets, exclude = setOf("b")) { "N-$it" }
        assertEquals(listOf("a"), t.map { it.wxid })
        assertEquals("N-a", t[0].name)
    }

    @Test
    fun resolveIgnoresBlankAndUnknownBuckets() {
        val t = MassSendLogic.resolveTargets(listOf("", "  ", "g:404", "t:404", "z"), MassSendLogic.Buckets())
        assertEquals(listOf("z"), t.map { it.wxid })
    }

    @Test
    fun startDedupsTargetsThemselves() {
        val j = MassSendLogic.start(listOf(MassSendLogic.Target("a"), MassSendLogic.Target("a")))
        assertEquals(1, j.items.size)
        assertEquals(Phase.RUNNING, j.phase)
    }

    @Test
    fun startWithNoTargetsIsDoneNotRunning() {
        val j = MassSendLogic.start(emptyList())
        assertEquals(Phase.DONE, j.phase)
        assertEquals("没有可发送的目标", j.note)
    }

    // ------------------------------------------------------------ 间隔

    @Test
    fun intervalZeroSettingIsZero() {
        assertEquals(0L, MassSendLogic.intervalMs(0, 0.5))
        assertEquals(0L, MassSendLogic.intervalMs(-5, 0.5))
    }

    @Test
    fun intervalSpansZeroToSettingSeconds() {
        assertEquals(0L, MassSendLogic.intervalMs(30, 0.0))
        assertEquals(15_000L, MassSendLogic.intervalMs(30, 0.5))
        assertEquals(30_000L, MassSendLogic.intervalMs(30, 1.0))
    }

    @Test
    fun intervalClampsBadRandom() {
        // 越界随机源钳到边界，NaN 当 0 —— 结果永远落在 [0, 设定秒*1000]
        assertEquals(30_000L, MassSendLogic.intervalMs(30, 1.9))
        assertEquals(0L, MassSendLogic.intervalMs(30, -0.3))
        assertEquals(0L, MassSendLogic.intervalMs(30, Double.NaN))
    }

    // ------------------------------------------------------------ 单条失败 → 重试 1 次

    @Test
    fun firstFailureRetriesInPlace() {
        var j = jobOf("a", "b")
        j = MassSendLogic.reduce(j, Event.Dispatch)
        assertEquals(ItemState.SENDING, j.items[0].state)

        j = MassSendLogic.reduce(j, Event.Result("a", Outcome.FAIL, "boom"))
        // 回到 PENDING 等重试：游标不动、连续失败还没开始算
        assertEquals(ItemState.PENDING, j.items[0].state)
        assertEquals(1, j.items[0].attempts)
        assertEquals(0, j.cursor)
        assertEquals(0, j.consecutiveFail)
        assertEquals("boom", j.items[0].lastError)
    }

    // ⚠️ 状态机的事件顺序是 **Dispatch → Result**（先派发再回报），不能倒过来。
    // [MassSendLogic.dispatch] 只认 PENDING，[MassSendLogic.onResult] 只认
    // SENDING/RETRYING —— 先发 Result 的话 item 还是 PENDING，`indexOfFirst` 找不到
    // 就原样返回，那次失败会被**整个吞掉**（看起来像成功了，最难发现的失效模式）。
    // 所以下面一律 Dispatch 开头，[attempt] 也是这个顺序。

    @Test
    fun retryOnceThenSuccess() {
        var j = jobOf("a", "b")
        j = MassSendLogic.reduce(j, Event.Dispatch) // 首次派发：PENDING → SENDING
        j = MassSendLogic.reduce(j, Event.Result("a", Outcome.FAIL)) // attempts=1 → 回 PENDING 待重试
        j = MassSendLogic.reduce(j, Event.Dispatch) // 重试派发：attempts>0 → RETRYING
        assertEquals(ItemState.RETRYING, j.items[0].state)

        j = MassSendLogic.reduce(j, Event.Result("a", Outcome.OK))
        assertEquals(ItemState.SUCCESS, j.items[0].state)
        assertEquals(2, j.items[0].attempts)
        assertEquals(1, j.cursor) // 推进到下一条
        assertEquals(0, j.consecutiveFail)
    }

    @Test
    fun secondFailureSkipsAndContinues() {
        var j = jobOf("a", "b")
        j = attempt(j, "a", Outcome.FAIL) // 第 1 次：SENDING → 失败，attempts=1，回 PENDING
        assertEquals(ItemState.PENDING, j.items[0].state) // 重试机会还在，不计连续失败
        assertEquals(1, j.items[0].attempts)
        assertEquals(0, j.consecutiveFail)

        j = attempt(j, "a", Outcome.FAIL) // 第 2 次：RETRYING → 仍失败，重试用完
        assertEquals(ItemState.FAILED, j.items[0].state)
        assertEquals(MassSendLogic.MAX_ATTEMPTS, j.items[0].attempts)
        assertEquals(1, j.cursor) // 跳过继续，不中断整个任务
        assertEquals(1, j.consecutiveFail)
        assertEquals(Phase.RUNNING, j.phase)
    }

    @Test
    fun successResetsConsecutiveFailures() {
        var j = jobOf("a", "b", "c", "d", "e", "f", "g", "h", "i")
        // a~d 各连败 2 次（4 条连续失败），e 成功清零，f~g 再败 → 距闸门还有 3 条，不该中止
        for (w in listOf("a", "b", "c", "d")) {
            j = attempt(j, w, Outcome.FAIL)
            j = attempt(j, w, Outcome.FAIL)
        }
        assertEquals(4, j.consecutiveFail)
        j = attempt(j, "e", Outcome.OK)
        assertEquals(0, j.consecutiveFail)
        j = attempt(j, "f", Outcome.FAIL)
        j = attempt(j, "f", Outcome.FAIL)
        j = attempt(j, "g", Outcome.FAIL)
        j = attempt(j, "g", Outcome.FAIL)
        assertEquals(2, j.consecutiveFail)
        assertEquals(Phase.RUNNING, j.phase)
    }

    // ------------------------------------------------------------ 风控闸门

    @Test
    fun fiveConsecutiveFailuresAbort() {
        var j = jobOf("a", "b", "c", "d", "e", "f")
        for (w in listOf("a", "b", "c", "d", "e")) {
            j = attempt(j, w, Outcome.FAIL)
            j = attempt(j, w, Outcome.FAIL)
        }
        assertEquals(MassSendLogic.MAX_CONSECUTIVE_FAIL, j.consecutiveFail)
        assertEquals(Phase.ABORTED, j.phase)
        assertEquals("连续失败 5 条，已自动中止", j.note)
        assertEquals(ItemState.SKIPPED, j.items[5].state) // 剩下的不再尝试
        val s = MassSendLogic.summary(j)
        assertEquals(5, s.failed)
        assertEquals(1, s.skipped)
        assertEquals(0, s.unsent)
    }

    @Test
    fun noChannelNeverTriggersAbort() {
        var j = jobOf("a", "b", "c", "d", "e", "f", "g", "h")
        for (w in j.items.map { it.wxid }) {
            j = attempt(j, w, Outcome.NO_CHANNEL)
        }
        // 没有发送链路**不是失败**：不重试、不计连续失败、不中止，任务正常走完
        assertEquals(Phase.DONE, j.phase)
        assertEquals(0, j.consecutiveFail)
        val s = MassSendLogic.summary(j)
        assertEquals(0, s.failed)
        assertEquals(8, s.unsent)
        assertEquals(0, s.success)
        assertTrue(s.noChannelHit())
        assertTrue(s.nothingSent())
    }

    @Test
    fun noChannelDoesNotRetry() {
        var j = jobOf("a", "b")
        j = attempt(j, "a", Outcome.NO_CHANNEL)
        assertEquals(1, j.items[0].attempts)
        assertEquals(ItemState.SKIPPED, j.items[0].state)
        assertEquals(1, j.cursor)
    }

    // ------------------------------------------------------------ 暂停 / 结束

    @Test
    fun pauseFreezesDispatchAndResumeWorks() {
        var j = jobOf("a", "b")
        j = MassSendLogic.reduce(j, Event.Pause)
        assertEquals(Phase.PAUSED, j.phase)

        val frozen = MassSendLogic.reduce(
            MassSendLogic.reduce(j, Event.Dispatch),
            Event.Result("a", Outcome.OK)
        )
        assertEquals(ItemState.PENDING, frozen.items[0].state) // 暂停期间不推进

        j = MassSendLogic.reduce(j, Event.Resume)
        assertEquals(Phase.RUNNING, j.phase)
        j = attempt(j, "a", Outcome.OK)
        assertEquals(1, j.cursor)
    }

    @Test
    fun stopMarksRestSkippedAndFinishes() {
        var j = jobOf("a", "b", "c")
        j = attempt(j, "a", Outcome.OK)
        j = MassSendLogic.reduce(j, Event.Stop)
        assertEquals(Phase.DONE, j.phase)
        assertEquals("已手动结束", j.note)
        assertEquals(ItemState.SUCCESS, j.items[0].state)
        assertEquals(ItemState.SKIPPED, j.items[1].state)
        assertEquals(ItemState.SKIPPED, j.items[2].state)
    }

    @Test
    fun eventsAfterFinishAreNoop() {
        var j = jobOf("a")
        j = attempt(j, "a", Outcome.OK)
        assertEquals(Phase.DONE, j.phase)
        assertEquals(j, MassSendLogic.reduce(j, Event.Stop))
    }

    // ------------------------------------------------------------ 汇总与进度

    @Test
    fun summaryCountsAndMessage() {
        var j = jobOf("a", "b", "c", "d", "e")
        j = attempt(j, "a", Outcome.OK)
        j = attempt(j, "b", Outcome.FAIL)
        j = attempt(j, "b", Outcome.FAIL)
        j = attempt(j, "c", Outcome.NO_CHANNEL)
        j = MassSendLogic.reduce(j, Event.Stop)

        val s = MassSendLogic.summary(j)
        assertEquals(5, s.total)
        assertEquals(1, s.success)
        assertEquals(1, s.failed)
        assertEquals(1, s.unsent)
        assertEquals(2, s.skipped)
        assertEquals("完成 1/5", s.headline())
        assertEquals("完成 1/5 · 失败 1 · 跳过 2 · 未发送 1\n已手动结束", s.message())
    }

    @Test
    fun progressNeverExceedsTotal() {
        var j = jobOf("a", "b")
        assertEquals(0, MassSendLogic.doneCount(j))
        j = attempt(j, "a", Outcome.OK)
        assertEquals(1, MassSendLogic.doneCount(j))
        assertEquals(0.5f, MassSendLogic.ratio(j), 0.0001f)
        j = attempt(j, "b", Outcome.OK)
        assertEquals(2, MassSendLogic.doneCount(j)) // 游标越界也要显示 2/2，不能是 3/2
        assertEquals(1f, MassSendLogic.ratio(j), 0.0001f)
        assertEquals(Phase.DONE, j.phase)
    }

    @Test
    fun ratioOfEmptyJobIsZero() {
        assertEquals(0, MassSendLogic.doneCount(MassSendLogic.Job()))
        assertEquals(0f, MassSendLogic.ratio(MassSendLogic.Job()), 0.0001f)
    }

    // ------------------------------------------------------------ 降级产物

    @Test
    fun renderPlanContainsTextAndAllTargets() {
        val t = listOf(
            MassSendLogic.Target("a", "阿甲"),
            MassSendLogic.Target("b"),
        )
        val out = MassSendLogic.renderPlan("新年快乐", t)
        assertTrue(out.contains("新年快乐"))
        assertTrue(out.contains("阿甲 <a>"))
        assertTrue(out.contains("b <b>")) // 没名字时退回 wxid
        assertTrue(out.contains("共 2 个目标"))
    }
}
