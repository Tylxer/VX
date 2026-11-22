package com.lx.wxgroup.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R73 决定性修复（Q1-R）回归锁：把 `hl0/m.a`（forwardFromImgId）从硬编码 **-1**
 * 改成**源图 imgLocalId**，让微信 j2 上传器走 ImgInfo2/CDN 服务端引用分支（清晰大图），
 * 而不是"读本地 ori"分支（本地无大图只能发 15KB 模糊缩略图）。
 *
 * 验证分两层（与 R73ImageForwardFixTest 同构，JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）：
 * 1. 纯逻辑等价复刻（[cdnBranch] 等）：本地函数复刻">0 走 CDN、<=0 走本地"判定，不读源码；
 * 2. 生产源码字符串断言（其余用例）：直接读四个生产文件全文，断言字段、取值链、日志、
 *    两条链（FileSender RESEND_FILE / attachForwardInfo NATIVE）都真实落在源码里。
 *
 * 变异有效性（本 lane 未跑 gradle，交 team-lead 统一执行；手工步骤 = 预期变红映射）：
 * - **M1（核心）**：把 `FileSender.kt` 的 `XposedHelpers.setLongField(m, "a", forwardFrom)`
 *   改成 `XposedHelpers.setLongField(m, "a", -1L)` ⇒ [fileSenderMaIsNotHardcodedMinusOne] 变红；
 *   [mutationToHardcodedMinusOneIsDetected] 用 toy 串复刻同一变异，证明该断言有判别力。
 * - M2：删掉 `send` 第 4 参 `imgLocalId: Long = -1L` ⇒ [fileSenderSendSignatureCarriesImgLocalId] 变红。
 * - M3：把 `Snapshot.imgLocalId` 默认值从 -1 改成 0 ⇒ [snapshotDeclaresImgLocalIdDefaultMinusOne] 变红。
 * - M4：`resendFile` 不传 `snap.imgLocalId` ⇒ [forceForwardFeedsBothChains] 变红。
 * - M5：INP 探针删掉 `INP j2init m.a=` / `q1.B=` ⇒ [inpProbeLogsMaAndQ1B] 变红。
 * - M6：把 `q1.B` 改成 CDN_IMAGE 或重建 k2/ot5 ⇒ [noRedLineChanges] 变红。
 *
 * 注意：逻辑复刻用例不读生产源码，故不随上面源码变异变红；真正的锁在源码断言层。
 */
class R73ImgLocalIdFixTest {

    companion object {
        private const val FILE_SENDER = "app/src/main/java/com/lx/wxgroup/core/FileSender.kt"
        private const val FORCE_FORWARD = "app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt"
        private const val FORWARD_LOGIC = "app/src/main/java/com/lx/wxgroup/feature/ForwardLogic.kt"
        private const val INP_PROBE = "app/src/main/java/com/lx/wxgroup/probe/ImageNetSceneProbe.kt"
        private const val NATIVE_LOGIC = "app/src/main/java/com/lx/wxgroup/feature/ForwardNativeLogic.kt"
    }

    // ------------------------------------------------------ 1) 纯逻辑等价复刻（不读源码）

    /** 与 FileSender 中 `imgLocalId > 0L` 门控等价的纯副本。 */
    private fun cdnBranch(imgLocalId: Long): Boolean = imgLocalId > 0L

    @Test
    fun cdnBranchTakenForPositiveImgLocalId() {
        assertTrue(cdnBranch(1L))
        assertTrue(cdnBranch(27_721_234_567L)) // 真机量级 imgLocalId
    }

    @Test
    fun localFileBranchTakenForMinusOneAndZero() {
        assertFalse(cdnBranch(-1L)) // 默认 / 取不到 ⇒ 读本地 ori 旧行为
        assertFalse(cdnBranch(0L))
    }

    // ------------------------------------------------------ 2) 生产源码断言

    @Test
    fun fileSenderSendSignatureCarriesImgLocalId() {
        // R73-ctx：断言真实代码行（去注释），不许用「历史签名」注释里的死字符串骗判据。
        // 签名现为 5 参：imgLocalId=-1L + forwardCtx 默认 null。
        val code = codeLines(source(FILE_SENDER))
        assertTrue(
            "send 必须保留 imgLocalId 第4参且默认 -1",
            code.any { "imgLocalId: Long = -1L" in it }
        )
        assertTrue(
            "send 必须追加 forwardCtx 第5参且默认 null（其它调用点不受影响）",
            code.any { "forwardCtx: ForwardLogic.Snapshot? = null" in it }
        )
    }

    @Test
    fun fileSenderMaIsNotHardcodedMinusOne() {
        val line = maAssignmentLine(source(FILE_SENDER))
        assertNotNull("未找到 FileSender 的 m.a 赋值行", line)
        assertTrue(
            "m.a 必须由 imgLocalId 派生（forwardFrom），实际行=$line",
            "forwardFrom" in line!!
        )
        assertFalse(
            "m.a 不得恒 -1（M1 变异会让本条变红），实际行=$line",
            line.contains("\"a\", -1") || line.contains("\"a\",-1")
        )
        // 门控本身也必须落在源码里：>0 才取 imgLocalId，否则 -1
        assertTrue(
            "必须保留 >0 门控",
            "if (imgLocalId > 0L) imgLocalId else -1L" in source(FILE_SENDER)
        )
    }

    @Test
    fun mutationToHardcodedMinusOneIsDetected() {
        // toy 复刻 M1：证明 [fileSenderMaIsNotHardcodedMinusOne] 的判别力，而非永远为真。
        val mutated = "XposedHelpers.setLongField(m, \"a\", -1L)"
        val line = maAssignmentLine(mutated)
        assertNotNull("toy 变异行应被识别", line)
        assertFalse("恒 -1 的变异必须判为不合规", "forwardFrom" in line!!)
        assertTrue("变异行必须含硬编码 -1", line.contains("\"a\", -1"))
    }

    @Test
    fun fileSenderKeepsRestOfFieldSequence() {
        // 红线：q1 字段序列一字不动、q1.B 不重建、k2/ot5 不引入。
        val code = codeLines(source(FILE_SENDER))
        assertTrue("constructorId 仍须设 13", code.any { "setIntField(q1, \"h\", CONSTRUCTOR_ID_IMAGE)" in it })
        assertTrue("q1.f(m) 仍须在原位置", code.any { "callMethod(q1, \"f\", m)" in it })
        assertTrue("q1.a() 仍须保留", code.any { "callMethod(q1, \"a\")" in it })
        assertTrue("g6(l1) 仍须保留", code.any { "\"g6\"" in it })
    }

    @Test
    fun snapshotDeclaresImgLocalIdDefaultMinusOne() {
        val src = source(FORWARD_LOGIC)
        assertTrue(
            "Snapshot 必须新增 val imgLocalId: Long = -1L（默认值不破坏现有构造点）",
            "val imgLocalId: Long = -1L" in src
        )
    }

    @Test
    fun forceForwardFillsImgLocalIdInSnapshot() {
        val src = source(FORCE_FORWARD)
        assertTrue("snapshotOf 必须调用取值函数", "imgLocalIdOf(ref)" in src)
        assertTrue("必须按 Q1-R 链取单例 d3.O8()", "com.tencent.mm.modelimage.d3" in src && "\"O8\"" in src)
        assertTrue("必须走 b1.Q2(talker,msgId)", "\"Q2\"" in src)
        assertTrue("必须走 b1.a3(talker,msgSvrId)", "\"a3\"" in src)
        assertTrue("必须读 r0.a 得 imgLocalId", "getLongField(r0, \"a\")" in src)
        assertTrue("必须写入 Snapshot.imgLocalId=", "imgLocalId = imgLocalId" in src)
        assertTrue("必须有验收诊断日志", "FORWARD imgLocalId=" in src)
    }

    @Test
    fun forceForwardFeedsBothChains() {
        val src = source(FORCE_FORWARD)
        // 主链 RESEND_FILE：FileSender.send 第 4/5 参（去注释判定，禁止历史调用注释假绿）
        // R76 P3 起增加可选第 6 参 bigImgPath：前 5 参契约不变，第 6 参为新增（默认 null 单调）。
        assertTrue(
            "resendFile 必须把 snap.imgLocalId + snap(ctx) 传给 FileSender.send（R76 起可带第 6 参）",
            codeLines(src).any {
                "FileSender.send(kind, target, snap.localPath, snap.imgLocalId, snap" in it
            }
        )
        // 备选链 NATIVE：attachForwardInfo 的 fw.a
        assertTrue(
            "attachForwardInfo 必须用 snap.imgLocalId>0 覆盖 fi.fromImgId(-1)",
            "if (snap.imgLocalId > 0L) snap.imgLocalId else fi.fromImgId" in src
        )
        assertTrue(
            "fw.a 必须写派生值 fromImgId（不是直接 fi.fromImgId）",
            "setLongField(fw, \"a\", fromImgId)" in src
        )
    }

    @Test
    fun inpProbeLogsMaAndQ1B() {
        val src = source(INP_PROBE)
        assertTrue("INP 必须打 m.a", "INP j2init m.a=" in src)
        assertTrue("INP 必须打 q1.B", "INP j2init q1.B=" in src)
        assertTrue("m.a 必须读 hl0.m 的 a 字段", "getLongField(m, \"a\")" in src)
        assertTrue("q1.B 必须读 hl0.q1 的 B 字段", "getObjectField(q1, \"B\")" in src)
        assertTrue("必须保留 type 扫描兜底（混淆字段名不可靠）", "findObjectOfType" in src)
        assertTrue(
            "enabled 首行 return 纪律不变（H1+H2 各一次）",
            src.lineSequence().count { it.trim() == "if (!enabled) return" } >= 2
        )
    }

    @Test
    fun noRedLineChanges() {
        val fs = codeLines(source(FILE_SENDER))
        val ff = codeLines(source(FORCE_FORWARD))
        // 不改 q1.B（保持 IMAGE→j2）、不重建 k2/ot5（红线）。
        for ((name, lines) in listOf("FileSender" to fs, "ForceForward" to ff)) {
            assertTrue(
                "$name 不得写 q1.B",
                lines.none { "setObjectField(q1, \"B\"" in it || "setByteField(q1, \"B\"" in it }
            )
            assertTrue("$name 不得引入 CDN_IMAGE", lines.none { "CDN_IMAGE" in it })
            assertTrue("$name 不得重建 modelimage.k2", lines.none { "modelimage.k2" in it })
            assertTrue("$name 不得造 ot5", lines.none { "ot5" in it })
        }
        // forwardInfoOf 不改（红线）：fromImgId 仍由纯函数给 NO_IMG_ID。
        val nl = source(NATIVE_LOGIC)
        assertTrue("forwardInfoOf 必须仍返回 NO_IMG_ID", "fromImgId = NO_IMG_ID" in nl)
    }

    // ------------------------------------------------------ 工具

    /** 从若干候选父目录向上找源码文件，零 Android 依赖。 */
    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        val candidates = mutableListOf<File>()
        for (i in 0 until 6) {
            if (dir == null) break
            candidates += File(dir, rel)
            dir = dir.parentFile
        }
        return candidates.firstOrNull { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?: throw AssertionError("找不到源码：$rel（工作目录=${System.getProperty("user.dir")}）")
    }

    /** 只留代码行（去掉注释/文档行），避免注释里的 ot5/CDN_IMAGE 误触发红线断言。 */
    private fun codeLines(src: String): List<String> = src.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        .toList()

    /** 取 m.a 赋值行（跳过注释）。 */
    private fun maAssignmentLine(src: String): String? = codeLines(src).firstOrNull {
        "setLongField(m, \"a\"," in it
    }
}
