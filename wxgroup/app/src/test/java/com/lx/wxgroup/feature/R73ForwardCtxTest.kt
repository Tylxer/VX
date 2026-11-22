package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R73 图片转发修复「core 数据层」回归锁（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 原生 `MsgRetransmitUI.k6` 在 q1/m 里填了一组我方旧版缺失的「转发上下文字段」：
 * `m.b`(源 msgId) / `q1.r`(带冒号转发源前缀) / `q1.j`(源图大图路径)，是「补齐即清晰」
 * 的第二判别标准（见 docs/_r73_ma_residual.md）。本测试只锁数据契约：
 * [ForwardLogic.Snapshot] 上这三个字段的**字段名 + 安全默认值 + 声明存在性**，
 * 保证另一 lane 能按约定名字引用、取不到时能安全回退现状。
 *
 * ## 变异有效性（本 lane 未跑 gradle，交主会话统一执行；手工步骤 = 预期变红映射）
 *
 * 前提：生产代码里 `Snapshot` 还有 `Snapshot(kind=..., text=...)` 这类**不带新字段**的构造点，
 * 所以「删掉默认值」= 让字段变成必填 ⇒ 整个 module 编译不过 ⇒ 全部用例变红。
 * 下面按「改默认值」（只改字面量、不破坏编译）给出精确映射，这是真正要锁的判别力：
 *
 * - **M1（srcMsgId 默认值）**：把 `val srcMsgId: Long = 0L` 的 `0L` 改成 `-1L` 或其它 ⇒
 *   [snapshotDefaultsKeepNewFieldsSafe] 变红（期望 0L）、[fieldNamesMatchSendContract] 变红
 *   （声明串不再含 `= 0L`）；[namedArgsCarryForwardCtx] 仍绿（显式传参不依赖默认值）。
 * - **M2（srcTalkerPrefix 默认值）**：把 `val srcTalkerPrefix: String = ""` 的 `""` 改成 `"wxid:"`
 *   等 ⇒ [snapshotDefaultsKeepNewFieldsSafe] + [fieldNamesMatchSendContract] 变红。
 * - **M3（srcBigImgPath 默认值）**：把 `val srcBigImgPath: String = ""` 改成非空 ⇒
 *   [snapshotDefaultsKeepNewFieldsSafe] + [fieldNamesMatchSendContract] 变红。
 * - **M4（字段名漂移）**：任一字段改名（如 srcMsgId → sourceMsgId）⇒
 *   [fieldNamesMatchSendContract] 变红（另一 lane 按旧名引用会失败，这正是要防的）；
 *   同时 [snapshotDefaultsKeepNewFieldsSafe] / [namedArgsCarryForwardCtx] 因引用旧名编译不过而变红。
 * - **M5（误动 imgLocalId 默认值）**：把 `val imgLocalId: Long = -1L` 改成 `0L` ⇒
 *   [snapshotDefaultsKeepNewFieldsSafe] 变红（锁住已上线的 m.a 修复不倒退）、
 *   [fieldNamesMatchSendContract] 亦变红（锁 imgLocalId 声明串）。
 *
 * 结论：三个用例互补 —— [snapshotDefaultsKeepNewFieldsSafe] 锁「安全默认值 + 不倒退」，
 * [namedArgsCarryForwardCtx] 锁「具名传参可读回」，[fieldNamesMatchSendContract] 锁「字段名/声明不漂移」。
 */
class R73ForwardCtxTest {

    companion object {
        private const val FORWARD_LOGIC = "app/src/main/java/com/lx/wxgroup/feature/ForwardLogic.kt"
    }

    /**
     * 默认 Snapshot 的三个新字段必须是「取不到 ⇒ 回退现状」的安全值，
     * 且**已上线的 imgLocalId 默认值 -1L 不许倒退**（单调追加纪律）。
     */
    @Test
    fun snapshotDefaultsKeepNewFieldsSafe() {
        val snap = ForwardLogic.Snapshot()
        assertEquals(0L, snap.srcMsgId)
        assertEquals("", snap.srcTalkerPrefix)
        assertEquals("", snap.srcBigImgPath)
        // 不倒退已闭环的 m.a 修复（M5 变异会让本行变红）
        assertEquals(-1L, snap.imgLocalId)
    }

    /** 具名构造传入三字段后，原值必须逐字可读回（供另一 lane 按名引用）。 */
    @Test
    fun namedArgsCarryForwardCtx() {
        val snap = ForwardLogic.Snapshot(
            kind = ForwardLogic.Kind.IMAGE,
            srcMsgId = 123_456L,
            srcTalkerPrefix = "wxid_abc123:",
            srcBigImgPath = "/storage/emulated/0/Android/data/com.tencent.mm/image2/ab/cd/abcdef.jpg",
        )
        assertEquals(123_456L, snap.srcMsgId)
        assertEquals("wxid_abc123:", snap.srcTalkerPrefix)
        assertEquals(
            "/storage/emulated/0/Android/data/com.tencent.mm/image2/ab/cd/abcdef.jpg",
            snap.srcBigImgPath
        )
        // 三字段与既有字段共存，互不覆盖
        assertEquals(ForwardLogic.Kind.IMAGE, snap.kind)
        assertEquals(-1L, snap.imgLocalId)
    }

    /**
     * 源码断言：ForwardLogic.kt 必须含三字段的**完整声明串**（字段名 + 类型 + 默认值）。
     * 只检查声明、不依赖反射，专门挡「字段名漂移导致另一 lane 引用失败」。
     */
    @Test
    fun fieldNamesMatchSendContract() {
        val src = source(FORWARD_LOGIC)
        assertTrue(
            "必须声明 val srcMsgId: Long = 0L（M1/M4 变异会让本条变红）",
            "val srcMsgId: Long = 0L" in src
        )
        assertTrue(
            "必须声明 val srcTalkerPrefix: String = \"\"（M2/M4 变异会让本条变红）",
            "val srcTalkerPrefix: String = \"\"" in src
        )
        assertTrue(
            "必须声明 val srcBigImgPath: String = \"\"（M3/M4 变异会让本条变红）",
            "val srcBigImgPath: String = \"\"" in src
        )
        // 已上线的 m.a 修复声明不许被动（M5 变异会让本条变红）
        assertTrue(
            "imgLocalId 默认值必须仍为 -1L",
            "val imgLocalId: Long = -1L" in src
        )
    }

    // ------------------------------------------------------ 工具

    /** 从若干候选父目录向上找源码文件，零 Android 依赖（与 R73ImgLocalIdFixTest 同构）。 */
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
}
