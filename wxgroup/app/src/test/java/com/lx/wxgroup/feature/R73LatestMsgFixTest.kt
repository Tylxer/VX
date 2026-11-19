package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R73 · 聊天页「最新一条消息」长按不出入口的回归锁（零 Android / Xposed 依赖）。
 *
 * 真机根因：`gate closed enabled=true age=127 talker= ref=false` —— trap 命中（age 小），
 * 但 `msgRefOf(pressedView())` 对最新一条返回 null ⇒ hasRef=false 门禁挡死。
 * 修法：hook 菜单构建点 `g0(r3,view,info,h9)` 入口缓存第 4 参 h9，门禁与点击快照
 * 在 View 链取不到 h9 时降级用它（两处都靠「ref 非空」恢复，避免出来后点下去 SKIP）。
 *
 * 验证分两层：
 * 1. 纯逻辑等价复刻（[latestMsgUsesG0CacheWhenViewChainFails] 等）：锁住「降级取 ref」与
 *    「burst 生命周期」两种语义，不依赖 Android。
 * 2. 生产源码字符串断言（其余用例）：直接读 ForceForward.kt，断言 g0 缓存钩子已挂、
 *    门禁与快照两处都走 cachedRef、红线（LABEL_FORWARD / snapshotNow 入参链 / 四条件门禁）未破。
 *
 * 变异有效性（本代理未跑 gradle，由 team-lead 统一执行；以下为等价复刻预期）：
 * - M1 把门禁 `hasRef = viewRef != null || cachedRef()!=null` 改回 `v!=null && msgRefOf(v)!=null`
 *     ⇒ [gateUsesG0CacheFallback] 变红；
 * - M2 把 `snapshotOf` 的 `ref = msgRefOf(v) ?: cachedRef()` 改回只取 msgRefOf
 *     ⇒ [snapshotUsesG0CacheFallback] 变红；
 * - M3 去掉 trap 里的 `cachedG0Ref = null`（新 burst 不清缓存）
 *     ⇒ [newBurstClearsG0Cache]（源码断言）变红；
 *   [staleCacheRejected] 是纯逻辑等价复刻的自证规约锁，不读生产源码，故不随该变异变红。
 * - M4 去掉 `if (newBurst) installG0RefHooks()` ⇒ [trapTriggersDeferredG0Mount] 变红；
 * - M5 把 LABEL_FORWARD 改回旧文案 ⇒ [redLinesUntouched] 变红。
 * - M6（P1）去掉 `ok == 0` 回滚分支的 `g0RefHooksMounted.set(false)`
 *     ⇒ [zeroHookedResetsIdempotentFlag] 变红。
 */
class R73LatestMsgFixTest {

    // ---------------------------------------------------------- 1) 纯逻辑等价复刻

    private data class StubRef(val kind: String, val msgId: Long)

    /** 生产取源逻辑的等价复刻：View 链 ref 优先，取不到才用 g0 缓存。 */
    private fun effectiveRef(viewChain: StubRef?, g0Cache: StubRef?): StubRef? = viewChain ?: g0Cache

    /** 门禁 hasRef 只看「最终 ref 非空」，四条件里其它三项不变。 */
    private fun gateHasRef(viewChain: StubRef?, g0Cache: StubRef?): Boolean =
        effectiveRef(viewChain, g0Cache) != null

    /** 快照 kind 由最终 ref 决定，取不到退化 UNKNOWN（planFor SKIP）。 */
    private fun snapshotKind(viewChain: StubRef?, g0Cache: StubRef?): String =
        effectiveRef(viewChain, g0Cache)?.kind ?: "UNKNOWN"

    @Test
    fun latestMsgUsesG0CacheWhenViewChainFails() {
        // 最新一条：View 链取不到 h9（失败点 B），但 g0 入口缓存到真实 h9
        val viewChain = null
        val g0Cache = StubRef("TEXT", msgId = 1_464_270_849L)
        assertTrue("门禁应因 g0 缓存 ref 放行", gateHasRef(viewChain, g0Cache))
        assertEquals("快照应由 g0 缓存 ref 决定 kind", "TEXT", snapshotKind(viewChain, g0Cache))
        assertEquals("快照应带上真实 msgId（否则原生转发 SKIP）",
            1_464_270_849L, effectiveRef(viewChain, g0Cache)?.msgId)
    }

    @Test
    fun viewChainRefTakesPriorityOverG0Cache() {
        // 历史消息：View 链正常取到 h9，不应被 g0 缓存串味
        val viewChain = StubRef("IMAGE", msgId = 7L)
        val g0Cache = StubRef("TEXT", msgId = 999L)
        assertEquals("View 链优先", "IMAGE", snapshotKind(viewChain, g0Cache))
        assertEquals(7L, effectiveRef(viewChain, g0Cache)?.msgId)
    }

    @Test
    fun noRefAtAllStillFailsClosed() {
        // 两侧都取不到：必须安全降级（门禁挡死、快照 UNKNOWN），绝不误注入/误转
        assertFalse(gateHasRef(null, null))
        assertEquals("UNKNOWN", snapshotKind(null, null))
        assertNull(effectiveRef(null, null))
    }

    /** g0 缓存生命周期等价复刻。 */
    private class StubG0Cache {
        var ref: StubRef? = null
        var at = 0L
        var burstStartAt = 0L

        fun onNewBurst(now: Long) {
            burstStartAt = now
            ref = null      // 生产：cachedG0Ref = null / cachedG0At = 0
            at = 0L
        }

        fun onG0(r: StubRef, now: Long) {
            ref = r
            at = now
        }

        fun get(): StubRef? = if (ref != null && at >= burstStartAt) ref else null
    }

    @Test
    fun staleCacheRejected() {
        val cache = StubG0Cache()
        cache.onNewBurst(1_000L)
        cache.onG0(StubRef("TEXT", 1L), now = 1_100L)
        assertNotNull("本次 burst 内 g0 缓存可用", cache.get())

        // 新一次长按：必须先清缓存，否则会把上一条消息的 ref 串到本条
        cache.onNewBurst(2_000L)
        assertNull("新一轮 burst 开始应清空旧 g0 缓存", cache.get())
    }

    @Test
    fun cacheBeforeBurstStartIsIgnored() {
        val cache = StubG0Cache()
        cache.onNewBurst(5_000L)
        cache.ref = StubRef("TEXT", 1L)
        cache.at = 4_999L // 早于 burstStartAt ⇒ 上一轮遗留
        assertNull("早于本次 burst 的缓存必须拒绝", cache.get())
    }

    // ---------------------------------------------------------- 2) 生产源码断言

    @Test
    fun gateUsesG0CacheFallback() {
        val line = sourceLines().first { it.trimStart().startsWith("val hasRef = ") }
        assertTrue(
            "门禁 hasRef 必须在 View 链取不到时用 cachedRef() 降级：实际=($line)",
            "cachedRef()" in line && "viewRef != null" in line
        )
        assertTrue(
            "门禁仍须调用 NativeMenuGate.shouldInject 四条件（不得绕过）",
            source().contains("NativeMenuGate.shouldInject(enabled, lastPressAt, now, hasRef)")
        )
    }

    @Test
    fun snapshotUsesG0CacheFallback() {
        val body = functionBody("private fun snapshotOf")
        assertTrue(
            "snapshotOf 的 ref 必须在 msgRefOf 失败时用 ?: cachedRef() 降级",
            "?: runCatching { cachedRef() }.getOrNull()" in body
        )
        assertTrue("快照仍须先走 View 链 msgRefOf（View 链优先）", "msgRefOf(it)" in body)
    }

    @Test
    fun g0HostClassesFullyEnumeratedAndMounted() {
        val src = source()
        val count = src.count { it == '"' } // 粗校验：整段类清单存在
        assertTrue("应含 g0 宿主类清单", "G0_HOST_CLASSES" in src)
        // 精确统计 134 个类名字符串
        val listBlock = src.substringBetween("private val G0_HOST_CLASSES", ")\n")
        val classEntries = Regex("\"com\\.tencent\\.mm\\.ui\\.chatting\\.viewitems\\.[A-Za-z0-9_]+\"")
            .findAll(listBlock).count()
        assertEquals("g0 宿主类应为 dex 全量枚举的 134 个", 134, classEntries)
        assertTrue(count > 0)
    }

    @Test
    fun trapTriggersDeferredG0Mount() {
        val body = functionBody("private fun installLongPressTrap")
        assertTrue("trap 新 burst 应触发延迟挂载", "if (newBurst) installG0RefHooks()" in body)
        val mount = functionBody("private fun installG0RefHooks")
        assertTrue("g0 挂载应幂等", "compareAndSet(false, true)" in mount)
        assertTrue("应按 134 个类名逐类 findClass", "XposedHelpers.findClass(name, cl)" in mount)
        assertTrue("应 hook g0 方法", "hookAll(cls, \"g0\", cb)" in mount)
        assertTrue("应缓存第 4 参 h9", "args[3]" in mount)
        assertTrue("全程 runCatching 不崩宿主", "runCatching" in mount)
    }

    @Test
    fun zeroHookedResetsIdempotentFlag() {
        // P1：134 次挂载全失败（ok==0）时必须回滚幂等标志，否则后续长按在开头直接 return，
        // 修复永久失效且不可自愈。用源码字符串断言锁住该回滚分支。
        val mount = functionBody("private fun installG0RefHooks")
        assertTrue("循环后必须有 ok == 0 全失败判断", "if (ok == 0)" in mount)
        val fromGuard = mount.substring(mount.indexOf("if (ok == 0)"))
        val rollbackBlock = fromGuard.substring(0, fromGuard.indexOf('}') + 1)
        assertTrue("ok==0 时必须复位幂等标志 g0RefHooksMounted 以允许重试",
            "g0RefHooksMounted.set(false)" in rollbackBlock)
        assertTrue("回滚 WARN 须含 will retry 便于真机观测", "will retry" in rollbackBlock)
        assertTrue("回滚后必须提前 return，不再走成功日志", "return" in rollbackBlock)
    }

    @Test
    fun newBurstClearsG0Cache() {
        val body = functionBody("private fun installLongPressTrap")
        assertTrue("新 burst 必须清空 g0 缓存", "cachedG0Ref = null" in body)
        assertTrue("新 burst 必须清时间戳", "cachedG0At = 0L" in body)
        val cached = functionBody("private fun cachedRef")
        assertTrue("读缓存须解弱引用", "cachedG0Ref?.get()" in cached)
        assertTrue("缓存须做时效校验防串味", "cachedAt < burstStartAt" in cached)
    }

    @Test
    fun cachedRefSharesH9ReflectorWithMsgRefOf() {
        assertTrue("msgRefOf 第 5 步应复用 msgRefFromH9",
            "return msgRefFromH9(h9)" in functionBody("private fun msgRefOf"))
        assertTrue("cachedRef 应复用 msgRefFromH9（字段与 View 链来源完全一致）",
            "msgRefFromH9(h9)" in functionBody("private fun cachedRef"))
    }

    @Test
    fun redLinesUntouched() {
        val src = source()
        assertTrue("文案必须保持 LABEL_FORWARD=转发（模块）",
            "LABEL_FORWARD = \"转发（模块）\"" in src)
        // R73-L6 入参链：snapshotNow 仍走 staleViewForProbe，不被改成 pressedView
        val snapNow = sourceLines().first { it.trimStart().startsWith("private fun snapshotNow") }
        assertTrue("snapshotNow 仍须走 staleViewForProbe", "snapshotOf(staleViewForProbe())" in snapNow)
        assertFalse("snapshotNow 不得改回 pressedView", "snapshotOf(pressedView())" in snapNow)
        // NativeMenuGate 四条件门禁逻辑本身不变（shouldInject 是表达式体、无大括号，
        // 故取其所在 object 的整块断言，而非函数体）
        val gateObj = functionBody("object NativeMenuGate")
        assertTrue("门禁四条件须保留 hasRef", "hasRef" in gateObj)
        assertTrue("门禁时间窗条件须保留", "now - lastPressAt <= WINDOW_MS" in gateObj)
        assertTrue("时间窗常量须保持 4000L", "WINDOW_MS: Long = 4000L" in src)
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

    private fun String.substringBetween(start: String, end: String): String {
        val i = indexOf(start)
        if (i < 0) return ""
        val j = indexOf(end, i + start.length)
        return if (j < 0) substring(i) else substring(i, j)
    }

    /** 取以 [signature] 开头函数的大括号包体（含函数声明行，按第一个 '{' 到配平 '}'）。 */
    private fun functionBody(signature: String, objectName: String? = null): String {
        val lines = sourceLines()
        var start = lines.indexOfFirst { it.trimStart().startsWith(signature) }
        if (start < 0 && objectName != null) {
            // 在指定 object 块内找（NativeMenuGate vs ForceForward 同名前缀时）
            val objStart = lines.indexOfFirst { it.trimStart().startsWith("object $objectName") }
            if (objStart >= 0) {
                start = (objStart until lines.size).firstOrNull {
                    lines[it].trimStart().startsWith(signature)
                } ?: -1
            }
        }
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
