package com.lx.wxgroup.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R73-L6 回归锁：慢选消息（长按 → 点转发 >10s）仍能拿到快照。
 *
 * 真机根因：点击链路经 [snapshotNow] → `snapshotOf(pressedView())`，而 `pressedView()` 带
 * `BURST_TTL_MS=10s` 过期判断；慢选 11.5s 后返回 null ⇒ 快照退化 UNKNOWN ⇒ planFor 判 SKIP，
 * 一条都发不出去。修法：`snapshotNow` 改走 `staleViewForProbe()`（忽略 TTL）。
 *
 * 因为 `staleViewForProbe()` / `pressedView()` 依赖 Android View，无法在纯 JVM 单测里直接跑，
 * 这里用两层可执行验证锁住该语义（零 Android / Xposed 依赖）：
 * 1. 纯逻辑等价复刻 TTL 边界（[slowPickBeyondTtlStillGetsSnapshot]）：超过 TTL 时 pressed 源
 *    退化 null、stale 源仍拿到 View —— 锁住「为什么必须换源」。
 * 2. 源码字符串断言（其余用例）：直接读 ForceForward.kt，断言 `snapshotNow` 确实用了
 *    `staleViewForProbe()` 且不再用 `pressedView()`、`staleViewForProbe` 实现未引入 TTL、
 *    KDoc 已改「长按快照取源」、点击日志带 `stale=true` —— 锁住「修复确实落到生产代码」。
 *
 * 说明：以上测试无需设备/adb；本代理未跑 gradle，由 team-lead 统一执行验证。
 */
class SnapshotSourceTest {

    // ---------------------------------------------------------- 1) 纯逻辑 TTL 边界

    @Test
    fun slowPickBeyondTtlStillGetsSnapshot() {
        val ttlMs = 10_000L
        val pressedAt = 0L
        val clickAt = 11_500L // 真机实证：长按 00:54:55 → 点击 00:55:07
        val liveView = Any() // 等价「仍挂在窗口上的长按 View」

        // pressedView()：超过 TTL 返回 null（根因）
        fun pressed(now: Long) = if (now - pressedAt > ttlMs) null else liveView
        // staleViewForProbe()：忽略 TTL（修复）
        fun stale() = liveView

        assertNotNull("窗口内 pressedView 正常拿到 View", pressed(ttlMs - 1))
        assertNull("超过 TTL 时 pressedView 退化 null（真机 UNKNOWN/SKIP 根因）", pressed(clickAt))
        assertNotNull("慢选超过 TTL 后 staleViewForProbe 仍拿到 View（修复）", stale())
    }

    // ---------------------------------------------------------- 2) 生产源码断言

    @Test
    fun snapshotNowUsesStaleSource() {
        val line = sourceLines().first { it.trimStart().startsWith("private fun snapshotNow") }
        assertTrue(
            "snapshotNow 必须走 staleViewForProbe()：实际=($line)",
            "snapshotOf(staleViewForProbe())" in line
        )
        assertFalse(
            "snapshotNow 不得再走 pressedView()：实际=($line)",
            "snapshotOf(pressedView())" in line
        )
    }

    @Test
    fun staleSourceStillIgnoresTtl() {
        val body = functionBody("private fun staleViewForProbe")
        assertTrue("staleViewForProbe 实现应读 burstDeepest?.get()", "burstDeepest?.get()" in body)
        assertFalse("staleViewForProbe 不得引入 TTL 判断", "BURST_TTL_MS" in body)
        assertFalse("staleViewForProbe 不得引入时间戳判断", "currentTimeMillis" in body)
    }

    @Test
    fun staleSourceKdocUpdatedAndClickLogHasStaleFlag() {
        val src = source()
        assertTrue("KDoc 应改为「长按快照取源（忽略 TTL）」", "长按快照取源（忽略 TTL）" in src)
        assertFalse("旧「探针专用」KDoc 不应残留", "探针专用" in src)
        assertTrue("点击日志应带 stale=true 便于真机确认", "stale=true" in src)
    }

    // ---------------------------------------------------------- 工具

    private fun source(): String {
        val rel = "app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt"
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        for (i in 0 until 6) {
            if (dir == null) break
            candidates += File(dir, rel)
            dir = dir.parentFile
        }
        val found = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 ForceForward.kt 源码（工作目录=${System.getProperty("user.dir")}）")
        return found.readText(Charsets.UTF_8)
    }

    private fun sourceLines(): List<String> = source().lineSequence().toList()

    /** 取以 [signature] 开头函数的大括号包体（含函数声明行，按第一个 '{' 到配平 '}'）。 */
    private fun functionBody(signature: String): String {
        val lines = sourceLines()
        val start = lines.indexOfFirst { it.trimStart().startsWith(signature) }
        assertTrue("未找到函数：$signature", start >= 0)
        var depth = 0
        val sb = StringBuilder()
        for (i in start until lines.size) {
            val line = lines[i]
            sb.append(line).append('\n')
            for (c in line) {
                if (c == '{') depth++
                if (c == '}') depth--
            }
            if (depth <= 0) break
        }
        return sb.toString()
    }
}
