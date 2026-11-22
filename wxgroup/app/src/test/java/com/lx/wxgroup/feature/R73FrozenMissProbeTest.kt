package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R73 冻结 ref 只读取证探针的锁点（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 覆盖两个探针：
 * - 探针 A：`installLongPressTrap` 的 beforeHookedMethod 内逐回调打 `FF LCP cb` /
 *   `FF LCP frozen`，经 `probeLongClickCallback` 外露 fresh 及各位置/图片字段（msgId/type/kind/path/pos/posVia/itemRoot/vHasImg）；
 * - 探针 B：`msgsnapReport` 内 A0scan 附近打 `FF LCP A0` / `FF LCP A0MAP` 独立位置源。
 *
 * 验证分两层（与 R73ImageForwardFixTest 同构）：
 * 1. 生产源码字符串断言（本节主体）：直接读 ForceForward.kt 全文，断言探针日志/挂载点真实存在，
 *    并用「越权断言」锁死探针函数体内不得写 `burstFrozenRef =`、不得出现 `hasRef` / `result.param`。
 * 2. 决策树逻辑复刻（末尾两例）：本地 toy 函数复刻 L0/L1/H2 判定。
 *
 * KDoc 诚实声明：**逻辑复刻是玩具**，不读生产源码、不随源码变异变红；真正防越权、防删日志的锁
 * 在源码断言层。变异映射：
 * - 删任一探针日志行（`FF LCP cb` / `FF LCP frozen` / `FF LCP A0` / `FF LCP A0MAP`）
 *   ⇒ 对应源码断言变红；
 * - 探针函数体里改生产冻结（`burstFrozenRef =`）或碰门禁/param
 *   ⇒ [probeCannotMutateProductionState] 变红；
 * - 把探针挂载调用从 trap 删掉 ⇒ [probeACallbackLogWiredInTrap] 变红。
 */
class R73FrozenMissProbeTest {

    // ---------------------------------------------------------- 1) 生产源码断言

    @Test
    fun probeACallbackLogWiredInTrap() {
        val src = source()
        val trap = functionBody("private fun installLongPressTrap")

        assertTrue("必须声明回调序号计数器 lcpSeq", "private val lcpSeq = AtomicInteger(0)" in src)
        assertTrue("trap 必须挂探针调用", "probeLongClickCallback(" in trap)
        // trap 注释里也显式标出两个日志 tag，便于从 trap 区域直接检索。
        assertTrue("trap 区域须出现 FF LCP cb 日志 tag", "FF LCP cb" in trap)
        assertTrue("trap 区域须出现 FF LCP frozen 日志 tag", "FF LCP frozen" in trap)

        val probe = functionBody("private fun probeLongClickCallback", 120)
        assertTrue("探针必须打 FF LCP cb", "FF LCP cb" in probe)
        assertTrue("探针必须打 FF LCP frozen", "FF LCP frozen" in probe)
        assertTrue("frozen 行只在 newBurst 时打", "if (newBurst)" in probe)
        listOf(
            "freshMsgId", "freshType", "freshKind", "freshPath",
            "vHasImg", "vImgPath", "pos=", "posVia", "itemRootCls", "itemRootId",
            "vCls", "vId", "args=", "x=", "y=", "newBurst", "seq=", "parentChain",
        ).forEach { field ->
            assertTrue("cb 日志字段缺失：$field", field in probe)
        }
        val chainFn = functionBody("private fun probeParentChain")
        assertTrue("parentChain 必须逐层打印类名#id", "javaClass.name" in chainFn && "append(n.id)" in chainFn)
        assertTrue("parentChain 必须标出 parent===list 的层", "listKindOf(parent)" in chainFn && "\"*\"" in chainFn)
        assertTrue("parentChain 必须限深 ≤12", "g++ < 12" in chainFn)

        // 生产冻结判断必须原样保留（探针不得绕开/替换它）。
        assertTrue("newBurst 判断必须原样保留", "now - burstStartAt > BURST_GAP_MS" in trap)
        assertTrue("冻结写入必须原样保留", "burstFrozenRef = freshRef" in trap)
    }

    @Test
    fun probeCannotMutateProductionState() {
        val probe = functionBody("private fun probeLongClickCallback", 120)
        assertFalse("探针体内不得写 burstFrozenRef =", "burstFrozenRef =" in probe)
        assertFalse("探针体内不得出现 hasRef", "hasRef" in probe)
        assertFalse("探针体内不得出现 result.param", "result.param" in probe)
        // 附加越权防线：不得改写其它生产 burst 状态。
        listOf(
            "burstStartAt =", "burstFrozenAt =", "cachedG0Ref =", "cachedG0At =", "lastPressAt =",
        ).forEach { bad ->
            assertFalse("探针不得改写生产状态：$bad", bad in probe)
        }
    }

    @Test
    fun probeBSnapshotLogWired() {
        val src = source()
        val iDecl = src.indexOf("private fun msgsnapReport")
        val iA0 = src.indexOf("FF LCP A0 first=")
        assertTrue("探针 B 必须落在 msgsnapReport 内", iDecl >= 0 && iA0 > iDecl)
        assertTrue("必须打 A0MAP 行", "FF LCP A0MAP" in src)

        // 从 A0 first/count 行前后取窗，确保反射确实在探针 B 块内（而非旧 A0scan 块）。
        val win = tokenWindow("FF LCP A0 first=", before = 16, after = 48)
        assertTrue("A0MAP 必须用 getMethod(\"A0\"", "getMethod(\"A0\"" in win)
        assertTrue("A0MAP 必须用 getObjectField(vh, \"d\")", "getObjectField(vh, \"d\")" in win)
        assertTrue("A0MAP 必须反射 getItem(i)", "getItem" in win)
        assertTrue("A0MAP 必须打 field_imgPath", "field_imgPath" in win)
        assertTrue("A0MAP 必须打 ivCls/ivId 以互斥 H3/H4", "ivCls=" in win && "ivId=" in win)
        assertTrue("A0MAP 必须打 itemViewType（vt）", "vt=" in win && "getItemViewType" in win)
        assertTrue("A0MAP 必须限量 8 行", "printed >= 8" in win)
        assertTrue("A0MAP 必须 continue 不 break 于空 vh", "?: continue" in win)
        assertTrue("A0MAP 错误不得阻断（err 行）", "A0MAP err=" in win)

        // H3 header/时间分隔行探测 + getItem(P-1/P/P+1) 核对。
        val hdr = tokenWindow("FF LCP A0HDR ", before = 10, after = 34)
        assertTrue("必须打 A0HDR 行", "FF LCP A0HDR wrap=" in hdr)
        assertTrue("必须探测 HeaderViewListAdapter 包裹", "HeaderViewListAdapter" in hdr)
        assertTrue("必须探测 header 数量", "getHeadersCount" in hdr)
        assertTrue("必须打 vt / vtM1 / vtP1", "vt=" in hdr && "vtM1=" in hdr && "vtP1=" in hdr)
        assertTrue("必须打 A0HDRITEM 行", "FF LCP A0HDRITEM pM1=" in hdr)
        assertTrue("A0HDRITEM 必须包含 pM1/p/pP1 三段", "pM1=" in hdr && " p=" in hdr && "pP1=" in hdr)
    }

    // ---------------------------------------------------------- 2) 决策树逻辑复刻（toy）

    private companion object {
        const val IMAGE_MSG_ID = 19_733_131L
        const val LATER_TEXT_ID = 19_733_134L
    }

    /** toy 回调记录：只留决策树 L0/L1/H2 需要的字段。 */
    private data class ToyCb(
        val seq: Int,
        val newBurst: Boolean,
        val vHasImg: Int,
        val freshMsgId: Long,
        val freshKind: String,
    )

    private enum class Verdict {
        L0_NO_IMAGE_ANYWHERE,
        H2_CORRECT_REF_DISCARDED,
        H1_FIRST_NOT_IMAGE_CELL,
        NONE,
    }

    /**
     * 文档 §3.3 决策树的 toy 复刻：L0 先看有没有任何回调解析出图片；L1/H2 看正确 ref 是否出现在
     * 「非首回调」（newBurst=0）却被丢弃；再 L2/H1 看首回调 v 子树是否无图。
     * ⚠️ 玩具复刻，仅讲清判定语义，不是生产逻辑——真锁在源码断言层（见类 KDoc）。
     */
    private fun replicaVerdict(cbs: List<ToyCb>): Verdict {
        if (cbs.none { it.freshMsgId == IMAGE_MSG_ID }) return Verdict.L0_NO_IMAGE_ANYWHERE
        if (cbs.any { it.freshMsgId == IMAGE_MSG_ID && !it.newBurst }) {
            return Verdict.H2_CORRECT_REF_DISCARDED
        }
        if ((cbs.firstOrNull()?.vHasImg ?: 0) == 0) return Verdict.H1_FIRST_NOT_IMAGE_CELL
        return Verdict.NONE
    }

    @Test
    fun h2CorrectImageRefDiscardedByFirstCallbackFreeze() {
        // 现场复刻：首回调冻结文本 19733134，后续回调拿到图片 19733131 却被丢弃。
        val cbs = listOf(
            ToyCb(1, true, 0, LATER_TEXT_ID, "TEXT"),
            ToyCb(2, false, 1, IMAGE_MSG_ID, "IMAGE"),
            ToyCb(3, false, 0, 19_733_135L, "TEXT"),
        )
        assertEquals(
            "正确 ref 出现在后续回调却被首回调冻结丢弃 ⇒ H2 唯一成立",
            Verdict.H2_CORRECT_REF_DISCARDED, replicaVerdict(cbs),
        )
        assertTrue("首回调 v 无图（H1 伴生），但 H2 是被独立判定的结构因", cbs.first().vHasImg == 0)
    }

    @Test
    fun allTextCallbacksDoNotTriggerH2() {
        // 反例：所有回调都解析成文本，根本没有图片 ref 出现 ⇒ H2 不成立。
        val cbs = listOf(
            ToyCb(1, true, 0, LATER_TEXT_ID, "TEXT"),
            ToyCb(2, false, 0, 19_733_135L, "TEXT"),
            ToyCb(3, false, 0, 19_733_136L, "TEXT"),
        )
        val verdict = replicaVerdict(cbs)
        assertEquals(Verdict.L0_NO_IMAGE_ANYWHERE, verdict)
        assertFalse("没有任何回调解析出图片时 H2 不成立",
            verdict == Verdict.H2_CORRECT_REF_DISCARDED)
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
     * 以 [maxLines] 兜底防跑飞。与 R73ImageForwardFixTest 同款。
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

    /** 取包含 [token] 的行及其前后若干行的窗口（用于精确断言某段代码块内的反射语句）。 */
    private fun tokenWindow(token: String, before: Int = 12, after: Int = 48): String {
        val lines = sourceLines()
        val at = lines.indexOfFirst { token in it }
        assertTrue("未找到 token：$token", at >= 0)
        val from = maxOf(0, at - before)
        val to = minOf(lines.size, at + after)
        return lines.subList(from, to).joinToString("\n")
    }
}
