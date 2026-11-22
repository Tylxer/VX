package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R73-A1 · 图片转发误选后面一条文本的回归锁（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 根因：长按当刻 View 是新鲜的（F0 解析到真被按住的图片 msgId=19725728），12s 后用户点击
 * 菜单时，陈旧 View 经同一条 F0 链被重解析到后面一条文本（msgId=19725729）—— 即「陈旧 View
 * + F0 TOCTOU」。修法两层：
 * - 修点 1：长按当刻冻结 MsgRef（[burstFrozenRef]/[burstFrozenAt]），门禁与点击快照统一经
 *   `resolveRefForBurst` 同源读取，彻底切断「点击时再解析」的漂移路径；
 * - 修点 2：正文来源按 kind 门禁（`allowsViewText`）——仅 TEXT/CARD/LINK/LOCATION 可用 viewText
 *   （且 ref.content 优先），IMAGE/VIDEO/VOICE/FILE/UNKNOWN 正文恒空。
 *
 * 验证分两层（与 R73LatestMsgFixTest 同构）：
 * 1. 纯逻辑等价复刻（[frozenImageRefWinsOverStaleView]、[nullFrozenFallsBackToG0Cache]、
 *    [staleFrozenIsRejected]、[allowsViewTextReplica]）：本地 data class + 本地函数复刻语义，
 *    不读生产源码、不依赖 Android。
 * 2. 生产源码字符串断言（其余用例）：直接读 ForceForward.kt 全文，断言冻结字段、统一取源、
 *    kind 正文门禁、g0 生命周期与时效红线都真实落在源码里。
 *
 * 变异有效性（本代理未跑 gradle，由 team-lead 统一执行；以下为预期变红映射）：
 * - M1 删冻结字段 / newBurst 分支不写 `burstFrozenRef = freshRef`
 *     ⇒ [frozenFieldsDeclaredAndWrittenOnNewBurst] 变红；
 * - M2 拆散门禁/快照同源（onShowGridMenu 改回现场 viewRef 门禁、或 snapshotOf 不调
 *     resolveRefForBurst、或把 resolve 挪到 extractText 之前）
 *     ⇒ [gateAndSnapshotShareFrozenResolve] 变红；
 * - M3 让 IMAGE 使用 viewText（snapshotOf 去掉 allowsViewText 门禁、或把 IMAGE 塞进 allow 集合）
 *     ⇒ [snapshotOfGatesViewTextByKind] 与 [allowsViewTextPureFunctionLocksKindGate] 变红；
 * - M4 newBurst 不清 g0 缓存 / cachedRef 去掉 `cachedAt < burstStartAt` 时效判断
 *     ⇒ [newBurstClearsG0CacheAndKeepsTtl] 变红。
 *
 * 注意：逻辑复刻用例是「等价副本」，不读生产源码，故不随上述源码变异变红；真正的锁在源码断言层。
 */
class R73ImageForwardFixTest {

    // ---------------------------------------------------------- 1) 纯逻辑等价复刻（不读源码）

    /** 生产 MsgRef 的等价副本：kind 用字符串，避免依赖 ForwardLogic.Kind / Android 类型。 */
    private data class StubRef(
        val kind: String,
        val msgId: Long,
        val msgSvrId: Long = 0L,
        val content: String = "",
        val imgPath: String = "",
        val talker: String = "",
    )

    private companion object {
        const val TEXT = "TEXT"
        const val CARD = "CARD"
        const val LINK = "LINK"
        const val LOCATION = "LOCATION"
        const val IMAGE = "IMAGE"
        const val VIDEO = "VIDEO"
        const val VOICE = "VOICE"
        const val FILE = "FILE"
        const val UNKNOWN = "UNKNOWN"
        val TEXTUAL_KINDS = setOf(TEXT, CARD, LINK, LOCATION)
    }

    /**
     * 生产 burst 生命周期（冻结 ref + g0 降级缓存）的等价副本：
     * - onNewBurst：冻结当刻新鲜 ref、绑定本轮 startAt，并清空上一轮 g0 缓存；
     * - cachedRef：g0 缓存须满足 `cachedAt >= burstStartAt`，否则判为上一轮遗留；
     * - resolve：冻结（frozenAt==startAt>0）> g0 缓存 > 现场 view 解析。
     */
    private class StubBurst {
        var startAt = 0L
        var frozen: StubRef? = null
        var frozenAt = 0L
        var cached: StubRef? = null
        var cachedAt = 0L

        fun onNewBurst(now: Long, fresh: StubRef?) {
            startAt = now
            frozen = fresh          // 生产：burstFrozenRef = freshRef
            frozenAt = now          // 生产：burstFrozenAt = burstStartAt
            cached = null           // 生产：cachedG0Ref = null
            cachedAt = 0L           // 生产：cachedG0At = 0L
        }

        fun onG0(r: StubRef, now: Long) {
            cached = r
            cachedAt = now
        }

        fun cachedRef(): StubRef? {
            val c = cached ?: return null
            if (cachedAt <= 0L || cachedAt < startAt) return null
            return c
        }

        fun resolve(view: StubRef?): StubRef? {
            val f = frozen
            if (f != null && frozenAt == startAt && startAt > 0L) return f
            cachedRef()?.let { return it }
            return view
        }
    }

    /** 生产纯函数 allowsViewText(kind) 的等价副本。 */
    private fun allowsViewText(kind: String): Boolean = kind in TEXTUAL_KINDS

    /** 生产 snapshotOf 里 rawText 门禁的等价副本（stripSender 与本案无关，略）。 */
    private fun rawTextOf(ref: StubRef?, viewText: String): String {
        val kind = ref?.kind ?: UNKNOWN
        return when {
            kind == TEXT -> ref?.content?.takeIf { it.isNotBlank() } ?: viewText
            kind in setOf(CARD, LINK, LOCATION) -> viewText // 沿用旧语义，不发 appmsg XML
            else -> ""
        }
    }

    @Test
    fun frozenImageRefWinsOverStaleViewText() {
        // A1 现场：冻结的是图片 19725728，点击时刻陈旧 View 已重解析成文本 19725729。
        val frozenImage = StubRef(
            IMAGE, msgId = 19_725_728L, imgPath = "/sdcard/DCIM/Camera/img.jpg"
        )
        val staleView = StubRef(TEXT, msgId = 19_725_729L, content = "误选的后面一条正文")
        val burst = StubBurst().apply { onNewBurst(1_700_000_000_000L, fresh = frozenImage) }

        val resolved = burst.resolve(staleView)

        assertEquals("A1 回归：必须仍返回长按当刻冻结的图片 ref", frozenImage, resolved)
        assertEquals("kind 必须钉死为 IMAGE，不得漂移成 TEXT", IMAGE, resolved?.kind)
        assertEquals(19_725_728L, resolved?.msgId)
        assertEquals("路径必须来自冻结的图片 ref", "/sdcard/DCIM/Camera/img.jpg", resolved?.imgPath)
        assertEquals("IMAGE 不得采信 stale viewText", "", rawTextOf(resolved, staleView.content))
    }

    @Test
    fun nullFrozenFallsBackToG0Cache() {
        // 冻结为空（freshRef 解析失败）：g0 cachedRef 应兜底最新一条，避免门禁误挡/快照 SKIP。
        val latest = StubRef(TEXT, msgId = 19_725_730L, content = "最新一条正文")
        val burst = StubBurst().apply {
            onNewBurst(1_000L, fresh = null)
            onG0(latest, now = 1_100L)
        }

        val resolved = burst.resolve(view = null)

        assertEquals("冻结为空时 g0 cachedRef 应兜底最新一条", latest, resolved)
        assertEquals(19_725_730L, resolved?.msgId)
        assertEquals("最新一条正文", rawTextOf(resolved, viewText = "脏 viewText"))
    }

    @Test
    fun staleFrozenIsRejected() {
        // 冻结存在但属于上一轮（frozenAt != burstStartAt）：不得采信，回退 cached → view。
        val staleFrozen = StubRef(IMAGE, msgId = 100L)
        val cached = StubRef(TEXT, msgId = 200L, content = "缓存正文")
        val viewRef = StubRef(FILE, msgId = 300L)
        val burst = StubBurst().apply { onNewBurst(2_000L, fresh = staleFrozen) }
        burst.frozenAt = 1_000L // 模拟上一轮遗留的冻结时间戳

        // 缓存新鲜 ⇒ 回退 cached
        burst.onG0(cached, now = 2_100L)
        assertEquals("过期冻结不采信，应回退 g0 cached", cached, burst.resolve(view = null))

        // 缓存也过期（cachedAt < burstStartAt）⇒ 再回退现场 view
        burst.onG0(cached, now = 1_900L)
        assertEquals("过期冻结 + 过期缓存 ⇒ 回退 view", viewRef, burst.resolve(viewRef))
    }

    @Test
    fun newBurstClearsFrozenAndCache() {
        val burst = StubBurst()
        burst.onNewBurst(1_000L, fresh = StubRef(IMAGE, msgId = 1L))
        burst.onG0(StubRef(TEXT, msgId = 2L), now = 1_100L)
        assertNotNull("本轮 burst 内缓存可用", burst.cachedRef())

        burst.onNewBurst(2_000L, fresh = StubRef(VIDEO, msgId = 3L))
        assertNull("新一轮 burst 必须清空上一轮缓存", burst.cachedRef())
        assertEquals("新一轮 burst 必须换冻结 ref", 3L, burst.frozen?.msgId)
        assertEquals("冻结时间必须绑定本轮 startAt", 2_000L, burst.frozenAt)
    }

    @Test
    fun allowsViewTextReplica() {
        listOf(TEXT, CARD, LINK, LOCATION).forEach {
            assertTrue("$it 应允许 viewText", allowsViewText(it))
        }
        listOf(IMAGE, VIDEO, VOICE, FILE, UNKNOWN).forEach {
            assertFalse("$it 不得允许 viewText", allowsViewText(it))
        }
        // rawText 门禁：图片即使混入脏 viewText 也恒空；文本类优先 content，空白才用 viewText。
        assertEquals("", rawTextOf(StubRef(IMAGE, 1L, content = "图片 content 非正文"), "脏 viewText"))
        assertEquals("库正文", rawTextOf(StubRef(TEXT, 2L, content = "库正文"), "view 正文"))
        assertEquals("view 正文", rawTextOf(StubRef(TEXT, 3L, content = "   "), "view 正文"))
        assertEquals("CARD/LINK/LOCATION 仍用 viewText，绝不发 appmsg XML",
            "卡片标题", rawTextOf(StubRef(CARD, 4L, content = "<msg>xml</msg>"), "卡片标题"))
    }

    // ---------------------------------------------------------- 2) 生产源码字符串断言

    @Test
    fun frozenFieldsDeclaredAndWrittenOnNewBurst() {
        val src = source()
        assertTrue("必须声明实例字段 burstFrozenRef: MsgRef?",
            "burstFrozenRef: MsgRef?" in src)
        assertTrue("必须声明实例字段 burstFrozenAt",
            "private var burstFrozenAt" in src)

        val trap = functionBody("private fun installLongPressTrap")
        assertTrue("trap 必须在 synchronized 前调 msgRefOf(v) 取新鲜 ref", "msgRefOf(v)" in trap)
        val freezeLine = trap.lines().firstOrNull { "burstFrozenRef =" in it }
            ?: throw AssertionError("newBurst 分支未见对 burstFrozenRef 赋值")
        assertTrue("冻结必须来自新鲜解析的 freshRef", "freshRef" in freezeLine)
        assertTrue("必须把冻结时间绑定本轮 burstStartAt", "burstFrozenAt = burstStartAt" in trap)
    }

    @Test
    fun resolveRefForBurstMethodExists() {
        val src = source()
        assertTrue("必须存在 resolveRefForBurst(v: View?): MsgRef?",
            "fun resolveRefForBurst(v: View?): MsgRef?" in src)
        val body = functionBody("private fun resolveRefForBurst")
        assertTrue("冻结 ref 认领条件须为 frozenAt==startAt && startAt>0",
            "frozenAt == startAt" in body && "startAt > 0L" in body)
        assertTrue("冻结未命中须回退 cachedRef()", "cachedRef()" in body)
        assertTrue("缓存未命中须回退 msgRefOf(v) 现场解析", "msgRefOf(it)" in body)
    }

    @Test
    fun gateAndSnapshotShareFrozenResolve() {
        val gate = functionBody("private fun onShowGridMenu")
        assertTrue("onShowGridMenu 必须走 resolveRefForBurst(v) 同源取 ref",
            "resolveRefForBurst(v)" in gate)
        val gateCode = gate.lines().filter {
            val s = it.trimStart()
            !s.startsWith("//") && !s.startsWith("*") && !s.startsWith("/*")
        }
        assertFalse("onShowGridMenu 代码不得再出现现场 viewRef 门禁",
            gateCode.any { "viewRef" in it })
        assertTrue("门禁 hasRef 必须由同源 ref 决定", "hasRef = ref != null" in gate)
        assertTrue("shouldInject 四条件不得绕过",
            "NativeMenuGate.shouldInject(enabled, lastPressAt, now, hasRef)" in gate)

        val snap = functionBody("private fun snapshotOf")
        assertTrue("snapshotOf 的 ref 必须来自 resolveRefForBurst(v)",
            "val ref = resolveRefForBurst(v)" in snap)
        assertFalse("snapshotOf 不得再用 msgRefOf(v) 现场重解析当 ref 来源",
            "val ref = msgRefOf(v)" in snap)
        val iExtract = snap.indexOf("extractText")
        val iResolve = snap.indexOf("resolveRefForBurst")
        assertTrue("resolveRefForBurst 必须在 extractText 之后用于 ref",
            iExtract >= 0 && iResolve > iExtract)
        assertTrue("kind 必须由同源 ref 决定", "val kind = ref?.kind" in snap)
    }

    @Test
    fun snapshotOfGatesViewTextByKind() {
        val snap = functionBody("private fun snapshotOf")
        assertTrue("snapshotOf 必须按 kind 门禁 viewText（调用 allowsViewText）",
            "allowsViewText(kind)" in snap)
        assertTrue("TEXT 须优先采信 ref.content", "ref?.content" in snap)
        assertTrue("TEXT content 空白才退回 viewText", "?: viewText" in snap)
        assertTrue("CARD/LINK/LOCATION 必须沿用 viewText 优先，不得采信 appmsg XML",
            "allowsViewText(kind) -> viewText" in snap)
        // 兼容 `else -> ""` 与 `} else {\n "" \n}` 两种写法（当前实现是后者）。
        assertTrue("非文本类正文须恒空",
            "else -> \"\"" in snap || Regex("else\\s*\\{?\\s*\"\"").containsMatchIn(snap))
        assertTrue("rawText 仍须经 stripSender 去掉 wxid 前缀",
            "HintTextLogic.stripSender(rawText)" in snap)
    }

    @Test
    fun allowsViewTextPureFunctionLocksKindGate() {
        val src = source()
        assertTrue("必须抽出纯函数 allowsViewText(kind: ForwardLogic.Kind)",
            "fun allowsViewText(kind: ForwardLogic.Kind)" in src)
        val body = functionWindow("fun allowsViewText")
        val tIdx = body.indexOf("-> true")
        val fIdx = body.indexOf("-> false")
        assertTrue("allowsViewText 须含 -> true / -> false 两个分支", tIdx >= 0 && fIdx > tIdx)

        // 覆盖跨行枚举：allow 段取 [开头 .. -> true]，deny 段取 [-> true 之后 .. -> false]。
        val allowBranch = body.substring(0, tIdx + "-> true".length)
        val denyBranch = body.substring(tIdx + "-> true".length, fIdx + "-> false".length)
        listOf("TEXT", "CARD", "LINK", "LOCATION").forEach {
            assertTrue("$it 必须落在 allow 分支", it in allowBranch)
        }
        assertFalse("IMAGE 不得落在 allow 分支", "IMAGE" in allowBranch)
        listOf("IMAGE", "VIDEO", "VOICE", "FILE", "UNKNOWN").forEach {
            assertTrue("$it 必须落在 deny 分支", it in denyBranch)
        }
    }

    @Test
    fun newBurstClearsG0CacheAndKeepsTtl() {
        val trap = functionBody("private fun installLongPressTrap")
        assertTrue("新 burst 必须清空 g0 缓存", "cachedG0Ref = null" in trap)
        assertTrue("新 burst 必须清空 g0 时间戳", "cachedG0At = 0L" in trap)
        val cached = functionBody("private fun cachedRef")
        assertTrue("cachedRef 须解弱引用", "cachedG0Ref?.get()" in cached)
        assertTrue("cachedRef 须保留时效判断 cachedAt < burstStartAt",
            "cachedAt < burstStartAt" in cached)
    }

    // ---------------------------------------------------------- 工具

    private fun source(): String {
        val rels = listOf(
            "app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt",
            "wxgroup/app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt",
            "src/main/java/com/lx/wxgroup/feature/ForceForward.kt",
        )
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        for (i in 0 until 7) {
            if (dir == null) break
            rels.forEach { candidates += File(dir, it) }
            dir = dir.parentFile
        }
        val found = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "找不到 ForceForward.kt 源码（user.dir=${System.getProperty("user.dir")}）"
            )
        return found.readText(Charsets.UTF_8)
    }

    private fun sourceLines(): List<String> = source().lineSequence().toList()

    /**
     * 取以 [signature] 开头的函数块：含声明行，直到大括号配平（表达式体函数亦适用），
     * 以 [maxLines] 兜底防跑飞。
     */
    private fun functionBody(signature: String, maxLines: Int = 80): String {
        val lines = sourceLines()
        val start = lines.indexOfFirst { it.trimStart().startsWith(signature) }
        assertTrue("未找到函数：$signature", start >= 0)
        val sb = StringBuilder()
        var depth = 0
        var sawBrace = false
        var count = 0
        for (i in start until lines.size) {
            if (count++ >= maxLines) break
            val line = lines[i]
            sb.append(line).append('\n')
            for (c in line) {
                if (c == '{') {
                    depth++
                    sawBrace = true
                } else if (c == '}') {
                    depth--
                }
            }
            if (sawBrace && depth <= 0) break
        }
        return sb.toString()
    }

    /** 取函数声明行起的固定窗口（适合 allowsViewText 这类表达式体小函数）。 */
    private fun functionWindow(signatureToken: String, maxLines: Int = 30): String {
        val lines = sourceLines()
        val start = lines.indexOfFirst { "fun " in it && signatureToken in it }
        assertTrue("未找到函数：$signatureToken", start >= 0)
        return lines.subList(start, minOf(lines.size, start + maxLines)).joinToString("\n")
    }
}
