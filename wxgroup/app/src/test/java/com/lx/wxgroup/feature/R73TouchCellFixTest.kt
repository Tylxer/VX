package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R73-g0 第一取源上位的回归锁（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 根因与裁决：`docs/_r73_touch_coordinate_verdict.md` §1/§5/§6。三次真机稳定 +1：
 * 图片 19733550(type=3, pos26/vt76) → 文本 19733551(type=1, pos27/vt0)。
 * 坐标法（findChildViewUnder）真机返回 null；冻结 freshRef=msgRefOf(v) 在长按当刻就已冻结到文本，
 * 二者均证伪。唯一裁决：把 g0(r3, View, ContextMenuInfo, h9) 菜单构建点缓存的 h9 提为第一取源，
 * 绕过会 +1 的 F0/getItem 链。
 *
 * 主修（被本单测源码断言锁住）：
 * 1. newBurst 冻结退化为 `burstFrozenRef = freshRef`（探针依赖 + g0 未命中时的降级），
 *    不再调用 resolveRefByTouch；该方法与 TouchCell 保留供取证，生产不再调用；
 * 2. [resolveRefForBurst] 优先级改为 `cached(g0) > frozen > view`，命中打 `FF ref src=cached`；
 * 3. `burstFrozenRejected` 字段与 rejected 硬停检查保留为通用防御（本轮不再由 touch 触发）；
 * 4. 门禁 [onShowGridMenu] 与快照 [snapshotOf] 仍共用该函数（同源未拆）。
 *
 * 验证分两层（与 R73FrozenMissProbeTest 同构）：
 * 1. 生产源码字符串断言（本节主体）：真锁，随源码变异变红，见下方「变异映射」；
 * 2. 逻辑复刻（末尾 toy 用例）：本地 data class + 本地函数复刻判定语义，不读源码、是玩具，
 *    不随源码变异变红——真锁在源码断言层。
 *
 * KDoc 变异映射（本代理未跑 gradle，由主会话统一执行；以下为预期变红映射）：
 * - ★变异验证点：把 [resolveRefForBurst] 中 cached 分支与 frozen 分支对调成 `frozen > cached`
 *   ⇒ [cachedRefWinsOverFrozen] 的 indexOf 顺序断言立刻变红（cachedRef() 调用须在 frozen 取用之前）；
 *   同时 toy [cachedImageRefWinsOverFrozenText] 把 replicaResolve 内 cached/frozen 判断对调也会变红，
 *   两处独立证明断言有判别力。验证方法（手工）：交换生产两分支位置后单跑本类，
 *   预期 cachedRefWinsOverFrozen 与 cachedSourceLogged 中至少前者变红，复原后全绿。
 * - 删 `FF ref src=cached` 日志 ⇒ [cachedSourceLogged] 变红；
 * - 恢复 newBurst 调用坐标法 ⇒ [newBurstFrozenFreshRefNoTouch] 变红；
 * - 去掉 rejected 字段/硬停检查或加回回退 ⇒ [rejectedFieldDeclaredAndReadInLock] /
 *   [rejectedRefIsHardStopNoFallback] 变红；
 * - 拆散门禁/快照同源 ⇒ [gateAndSnapshotStillShareResolve] 变红；
 * - 删 `freshRef = msgRefOf(v)` ⇒ [freshRefStillComputedForProbeAndG0] 变红；
 * - 删取证保留的 resolveRefByTouch/TouchCell ⇒ [touchCoordinateMethodLocksGroundTruth] 变红；
 * - 碰红线（planFor / 原生开关 / g0 时效 / 清缓存 / allowsViewText / 硬编码 +1）⇒ [redlinesStillIntact] 变红。
 */
class R73TouchCellFixTest {

    // ---------------------------------------------------------- 1) 生产源码断言（真锁）

    @Test
    fun touchCoordinateMethodLocksGroundTruth() {
        val src = source()
        assertTrue("取证保留：必须存在 resolveRefByTouch(v, args)",
            "private fun resolveRefByTouch(v: View, args: Array<Any?>?)" in src)
        assertTrue("取证保留：必须存在 TouchCell 结果载体", "private data class TouchCell(" in src)
        assertTrue("取证方法 KDoc 须标注已下线", "R73-g0 起不再在生产路径调用" in src)

        val body = functionBody("private fun resolveRefByTouch")
        assertTrue("仅 args.size==2 时执行", "a.size != 2" in body)
        assertTrue("两参须为 Number", "!is Number" in src && "as Number" in src)
        assertTrue("v 屏幕原点", "v.getLocationOnScreen(vl)" in body)
        assertTrue("触点叠加 v 原点", "vl[0] + ax.toFloat()" in body && "vl[1] + ay.toFloat()" in body)
        assertTrue("向上 ≤12 层找 recycler", "guard++ < 12" in body)
        assertTrue("recycler 按继承链或类名含 Recycler 命中",
            "listKindOf(node) == \"recycler\"" in body && "contains(\"Recycler\")" in body)
        assertTrue("findChildViewUnder 定位触点 cell", "findChildViewUnder" in body)
        assertTrue("recycler-local 坐标", "touchX - rl[0]" in body && "touchY - rl[1]" in body)
        assertTrue("触点 cell ref 复用 msgRefOf(child)", "msgRefOf(child)" in body)
        assertTrue("触点 cell imgPath 取子树", "probeImgPathInSubtree(child)" in body)
        assertTrue("viewType 从 viewholder/tag 取、不得 pos 反查",
            "getChildViewHolder" in body && "child.getTag()" in body && "getItemViewType" in body)
        assertTrue("viewType 不得用 pos 反查", "getItemViewType(pos)" !in body && "getItemViewType(p)" !in body)
        assertTrue("必须保留 FF TOUCH 诊断日志", "FF TOUCH x=" in body)
        listOf("y=", "child=", "pos=", "msgId=", "kind=", "vt=", "imgPath=").forEach { f ->
            assertTrue("FF TOUCH 缺字段：$f", f in body)
        }
        assertTrue("全程 runCatching 降级",
            "private fun resolveRefByTouch(v: View, args: Array<Any?>?): TouchCell? = runCatching" in src)
        assertTrue("失败降级返回 null", ".getOrNull()" in body)
    }

    @Test
    fun newBurstFrozenFreshRefNoTouch() {
        val trap = functionBody("private fun installLongPressTrap", 90)
        assertFalse("newBurst 不得再调用坐标法 resolveRefByTouch(",
            "resolveRefByTouch(v, param.args)" in trap)
        assertTrue("冻结 ref 退化为 freshRef（降级取源）", "burstFrozenRef = freshRef" in trap)
        assertTrue("冻结时间仍绑定本轮 burstStartAt", "burstFrozenAt = burstStartAt" in trap)
        assertFalse("不得再有 touch 优先级", "touch?.ref ?: freshRef" in trap)
        assertFalse("不得再读触点 cell imgPath", "cellImg = touch?.imgPath.orEmpty()" in trap)
        assertFalse("不得再有 contradictory 矛盾拒绝", "contradictory" in trap)
        assertFalse("不得再由 touch 置 rejected", "burstFrozenRejected = true" in trap)
    }

    @Test
    fun rejectedFieldDeclaredAndReadInLock() {
        val src = source()
        assertTrue("必须声明实例字段 burstFrozenRejected",
            "private var burstFrozenRejected = false" in src)
        val body = functionBody("private fun resolveRefForBurst")
        assertTrue("rejected 须在 pressLock 内读取", "rejected = burstFrozenRejected" in body)
        assertTrue("rejected 读取须在 synchronized(pressLock) 块内", "synchronized(pressLock)" in body)
    }

    @Test
    fun rejectedRefIsHardStopNoFallback() {
        val body = functionBody("private fun resolveRefForBurst")
        assertTrue("必须检查 rejected", "if (rejected)" in body)
        assertTrue("rejected 时须打 FF ref rejected", "FF ref rejected" in body)

        // 截出 rejected 分支（从 if (rejected) 到其闭合 } 之前），断言其内部无任何回退。
        val iReject = body.indexOf("if (rejected)")
        val iReturn = body.indexOf("return null", iReject)
        val iClose = body.indexOf('}', iReturn)
        assertTrue("rejected 分支须存在并闭合", iReject >= 0 && iReturn > iReject && iClose > iReturn)
        val rejectBranch = body.substring(iReject, iClose)
        assertTrue("rejected 分支必须 return null", "return null" in rejectBranch)
        assertFalse("rejected 分支不得回退 cachedRef()", "cachedRef()" in rejectBranch)
        assertFalse("rejected 分支不得回退 msgRefOf", "msgRefOf(" in rejectBranch)

        // 三源仍在（优先级顺序由 cachedRefWinsOverFrozen 单独锁定）。
        assertTrue("cached 源仍在", "cachedRef()" in body)
        assertTrue("frozen 认领条件不变", "frozenAt == startAt" in body && "startAt > 0L" in body)
        assertTrue("view 源仍在", "msgRefOf(it)" in body)
    }

    @Test
    fun cachedRefWinsOverFrozen() {
        val body = functionBody("private fun resolveRefForBurst")
        // ★ 变异验证点（见类 KDoc）：对调 cached/frozen 分支顺序，下面顺序断言立刻变红。
        val iReject = body.indexOf("if (rejected)")
        val iCachedCall = body.indexOf("val cached = runCatching { cachedRef() }")
        val iCachedReturn = body.indexOf("return cached")
        val iFrozenClaim = body.indexOf("val frozenRef = frozen")
        val iFrozenReturn = body.indexOf("return frozenRef")
        val iView = body.indexOf("val fresh = v?.let")
        assertTrue("rejected 硬停须在取源之前", iReject >= 0 && iCachedCall > iReject)
        assertTrue("cachedRef() 调用必须存在", iCachedCall >= 0)
        assertTrue("frozen 认领必须存在", iFrozenClaim >= 0)
        assertTrue("★★ cached(g0) 调用须在 frozen 取用之前（cached 第一取源）", iFrozenClaim > iCachedCall)
        assertTrue("cached 命中 return 须在 frozen 命中 return 之前", iFrozenReturn > iCachedReturn)
        assertTrue("view 兜底须在 frozen 之后", iView > iFrozenClaim)
    }

    @Test
    fun cachedSourceLogged() {
        val body = functionBody("private fun resolveRefForBurst")
        assertTrue("cached 命中须打 FF ref src=cached 日志", "FF ref src=cached msgId=" in body)
        assertTrue("frozen 命中须打 FF ref src=frozen 日志", "FF ref src=frozen msgId=" in body)
        assertTrue("view 兜底须打 FF ref src=view 日志", "FF ref src=view msgId=" in body)
    }

    @Test
    fun gateAndSnapshotStillShareResolve() {
        val gate = functionBody("private fun onShowGridMenu")
        assertTrue("门禁仍走 resolveRefForBurst(v)", "resolveRefForBurst(v)" in gate)
        assertTrue("门禁 hasRef 仍由同源 ref 决定", "hasRef = ref != null" in gate)
        assertTrue("shouldInject 四条件仍在",
            "NativeMenuGate.shouldInject(enabled, lastPressAt, now, hasRef)" in gate)

        val snap = functionBody("private fun snapshotOf")
        assertTrue("快照仍走 resolveRefForBurst(v)", "val ref = resolveRefForBurst(v)" in snap)
        assertFalse("快照不得改回现场 msgRefOf(v) 当 ref 源", "val ref = msgRefOf(v)" in snap)
        assertTrue("门禁与快照须同源（均仅依赖 resolveRefForBurst）",
            ("resolveRefForBurst(v)" in gate) && ("resolveRefForBurst(v)" in snap))
    }

    @Test
    fun freshRefStillComputedForProbeAndG0() {
        val trap = functionBody("private fun installLongPressTrap", 90)
        assertTrue("freshRef = msgRefOf(v) 必须保留", "val freshRef = runCatching { msgRefOf(v) }" in trap)
        assertTrue("探针调用须保留且仍传 freshRef",
            "probeLongClickCallback(v, freshRef, param.args, newBurst)" in trap)
        assertTrue("g0 钩子挂载仍绑定 newBurst", "if (newBurst) installG0RefHooks()" in trap)
    }

    @Test
    fun redlinesStillIntact() {
        val src = source()
        assertTrue("planFor 仍在", "ForwardLogic.planFor(snap, nativeState())" in src)
        assertTrue("原生开关默认关逻辑仍在",
            "if (!Kv.getBool(Keys.FORWARD_NATIVE)) return ForwardLogic.NativeState.UNKNOWN" in src)
        val trap = functionBody("private fun installLongPressTrap", 90)
        assertTrue("burst 间隔判断仍在", "now - burstStartAt > BURST_GAP_MS" in trap)
        assertTrue("新 burst 仍清 g0 ref", "cachedG0Ref = null" in trap)
        assertTrue("新 burst 仍清 g0 时间戳", "cachedG0At = 0L" in trap)
        val cached = functionBody("private fun cachedRef")
        assertTrue("cachedRef 时效判断仍在", "cachedAt < burstStartAt" in cached)
        assertTrue("BURST_TTL_MS 仍在", "BURST_TTL_MS" in src)
        assertTrue("staleViewForProbe 仍在", "staleViewForProbe" in src)
        val snap = functionBody("private fun snapshotOf")
        assertTrue("allowsViewText 门禁仍在", "allowsViewText(kind)" in snap)
        assertTrue("stripSender 仍在", "HintTextLogic.stripSender(rawText)" in snap)
        assertTrue("allowsViewText 纯函数仍在", "fun allowsViewText(kind: ForwardLogic.Kind)" in src)
        val touch = functionBody("private fun resolveRefByTouch")
        val noOffset = Regex("(pos|position|msgId)\\s*\\+\\s*1\\b")
        assertFalse("坐标法不得硬编码 +1 偏移", noOffset.containsMatchIn(touch))
        assertFalse("冻结块不得硬编码 +1 偏移", noOffset.containsMatchIn(trap))
    }

    // ---------------------------------------------------------- 2) 逻辑复刻（toy，非真锁）

    private companion object {
        const val IMAGE_MSG_ID = 19_733_550L
        const val LATER_TEXT_ID = 19_733_551L
        const val THUMB = "THUMBNAIL_DIRPATH://th_216b1f7ccd59b1268349fd0eb3c46801"
        const val TEXT = "TEXT"
        const val KIND_IMAGE = "IMAGE"
    }

    /** toy ref：kind 用字符串，避开 ForwardLogic.Kind / Android 类型。 */
    private data class ToyRef(val kind: String, val msgId: Long, val imgPath: String = "")

    /**
     * 生产 [resolveRefForBurst] 的等价副本：rejected 硬停 → cached(g0) → frozen → view。
     * ⚠️ 玩具副本，不读源码、不随源码变异变红——真锁在源码断言层（见类 KDoc）。
     * 变异验证：把下面 cached/frozen 两行对调成 frozen 优先，[cachedImageRefWinsOverFrozenText] 变红。
     */
    private fun replicaResolve(
        frozen: ToyRef?,
        rejected: Boolean,
        cached: ToyRef?,
        view: ToyRef?,
    ): ToyRef? {
        if (rejected) return null
        if (cached != null) return cached   // R73-g0：cached 第一取源
        if (frozen != null) return frozen
        return view
    }

    @Test
    fun cachedImageRefWinsOverFrozenText() {
        // 现场：frozen=freshRef 已冻结到后面文本；g0 cached 才是图片（微信菜单权威源）。
        val frozenText = ToyRef(TEXT, LATER_TEXT_ID)
        val cachedImage = ToyRef(KIND_IMAGE, IMAGE_MSG_ID, THUMB)
        assertEquals("g0(cached) 必须压过冻结文本", IMAGE_MSG_ID,
            replicaResolve(frozenText, false, cachedImage, ToyRef(TEXT, 888L))?.msgId)
        assertEquals(KIND_IMAGE, replicaResolve(frozenText, false, cachedImage, null)?.kind)
    }

    @Test
    fun rejectedRefHardStopsEvenWithCachedImage() {
        assertNull("rejected 时即便 cached/view 存在也不回退",
            replicaResolve(ToyRef(TEXT, LATER_TEXT_ID), true,
                ToyRef(KIND_IMAGE, IMAGE_MSG_ID, THUMB), ToyRef(TEXT, 888L)))
    }

    @Test
    fun noCachedFallsBackToFrozenThenView() {
        val frozenImage = ToyRef(KIND_IMAGE, IMAGE_MSG_ID, THUMB)
        assertEquals("无 cached 时用 frozen", IMAGE_MSG_ID,
            replicaResolve(frozenImage, false, null, ToyRef(TEXT, 888L))?.msgId)
        assertEquals("无 cached/frozen 时用 view", 888L,
            replicaResolve(null, false, null, ToyRef(TEXT, 888L))?.msgId)
    }

    // ---------------------------------------------------------- 工具

    /** 与 R73FrozenMissProbeTest 同款：向上遍历找 ForceForward.kt。 */
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

    /** 取以 [signature] 开头的函数块：含声明行，直到大括号配平，[maxLines] 兜底防跑飞。 */
    private fun functionBody(signature: String, maxLines: Int = 90): String {
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
}
