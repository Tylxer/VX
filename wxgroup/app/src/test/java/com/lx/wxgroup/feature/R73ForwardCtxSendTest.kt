package com.lx.wxgroup.feature

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R73 图片转发修复「发送链」回归锁（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 原生 `MsgRetransmitUI.k6` 除了已上线的 `m.a`，还填了三个我方旧版缺失的转发上下文字段：
 * `m.b`(源 msgId) / `q1.r`(带冒号转发源前缀) / `q1.j`(源图大图路径)。本测试锁住发送链
 * 按字节锚点补齐这三项、且取不到值一律回退现状（单调追加、不倒退已闭环修复）。
 *
 * 与 core 数据层的 [R73ForwardCtxTest] 互补：那边锁 Snapshot 三字段的契约，这边锁
 * 「字段真的被搬进 m/q1」+「两条链都传了值」+「红线未破」。
 *
 * 变异有效性（本 lane 未跑 gradle，交主会话统一执行；手工步骤 = 预期变红映射）：
 * - M1：删掉 send 第 5 参 forwardCtx ⇒ [fileSenderSendCarriesForwardCtx] 变红。
 * - M2：删掉 m.b 或 q1.r 或 q1.j 任一写入 ⇒ [fileSenderSendCarriesForwardCtx] 变红。
 * - M3：q1.j 大图覆盖写在 q1.a() 之后（或删掉）⇒ [q1jBigPathOverridesThumbnail] 变红。
 * - M4：resendFile 不传第 5 参 snap ⇒ [forceForwardFeedsCtxOnBothChains] 变红。
 * - M5：snapshotOf 不填三字段 / 删掉 FORWARD ctx 日志 ⇒ [forceForwardFeedsCtxOnBothChains] 变红。
 * - M6：误写 q1.B / 引入 CDN_IMAGE / modelimage.k2 / ot5 ⇒ [redLinesUntouched] 变红。
 * - M7：删掉 CONSTRUCTOR_ID_IMAGE 或 m.a 的 >0 门控 ⇒ [redLinesUntouched] 变红。
 */
class R73ForwardCtxSendTest {

    companion object {
        private const val FILE_SENDER = "app/src/main/java/com/lx/wxgroup/core/FileSender.kt"
        private const val FORCE_FORWARD = "app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt"
    }

    /** send 必须增第 5 参（默认 null，老调用点零影响），且三项写入都在源码里。 */
    @Test
    fun fileSenderSendCarriesForwardCtx() {
        val src = source(FILE_SENDER)
        assertTrue(
            "send 必须追加第 5 参 forwardCtx: ForwardLogic.Snapshot? = null",
            "forwardCtx: ForwardLogic.Snapshot? = null" in src
        )
        assertTrue("必须写 m.b（源 msgId）", "setLongField(m, \"b\"" in src)
        assertTrue("必须写 q1.r（转发源前缀）", "setObjectField(q1, \"r\"" in src)
        assertTrue("必须写 q1.j（源图大图路径）", "setObjectField(q1, \"j\"" in src)
        // 三项都落在 forwardCtx?.let 内：取不到 (null) ⇒ 全不写，逐字节回退现状。
        assertTrue("三项必须由 forwardCtx 门控", "forwardCtx?.let { ctx ->" in src)
        assertTrue("m.b 只在 >0 时写", "if (ctx.srcMsgId > 0L)" in src)
        assertTrue("q1.r 只在非空时写", "if (ctx.srcTalkerPrefix.isNotBlank())" in src)
        assertTrue("q1.j 只在非空时写", "if (ctx.srcBigImgPath.isNotBlank())" in src)
    }

    /**
     * q1.j 的新值必须**覆盖**原缩略图路径写入，且覆盖动作在 q1.a() 之前（顺序是判据）。
     * 形成「原生大图路径优先、取不到回退缩略图路径」。
     */
    @Test
    fun q1jBigPathOverridesThumbnail() {
        val code = codeLines(source(FILE_SENDER))
        val thumbIdx = code.indexOfFirst { "setObjectField(q1, \"j\", path)" in it }
        val overrideIdx = code.indexOfFirst { "setObjectField(q1, \"j\", ctx.srcBigImgPath)" in it }
        val buildIdx = code.indexOfFirst { "callMethod(q1, \"a\")" in it }
        assertTrue("必须保留原 q1.j=path（缩略图）写入作为回退", thumbIdx >= 0)
        assertTrue("必须新增 q1.j=ctx.srcBigImgPath 覆盖写入", overrideIdx >= 0)
        assertTrue("q1.a() 锚点必须存在", buildIdx >= 0)
        assertTrue(
            "覆盖必须发生在缩略图写入之后、q1.a() 之前（实际 thumb=$thumbIdx override=$overrideIdx a=$buildIdx）",
            thumbIdx < overrideIdx && overrideIdx < buildIdx
        )
    }

    /** 两条链都要拿到上下文：RESEND_FILE 传第 5 参 + snapshotOf 填三字段 + 验收日志。 */
    @Test
    fun forceForwardFeedsCtxOnBothChains() {
        val src = source(FORCE_FORWARD)
        // 主链 RESEND_FILE：FileSender.send 第 5 参 = snap
        // R76 P3 起增加可选第 6 参 bigImgPath：前 5 参契约不变，第 6 参为新增（默认 null 单调）。
        assertTrue(
            "resendFile 必须把 snap 作为第 5 参传给 FileSender.send（R76 起可带第 6 参 bigImgPath）",
            codeLines(src).any {
                "FileSender.send(kind, target, snap.localPath, snap.imgLocalId, snap" in it
            }
        )
        // 快照必须填满三字段（具名传参，取不到由 snapshotOf 留默认值）
        assertTrue("snapshotOf 必须传 srcMsgId", "srcMsgId = srcMsgId" in src)
        assertTrue("snapshotOf 必须传 srcTalkerPrefix", "srcTalkerPrefix = srcTalkerPrefix" in src)
        assertTrue("snapshotOf 必须传 srcBigImgPath", "srcBigImgPath = srcBigImgPath" in src)
        // 只读取值 + 安全默认值：取不到留 0/""/""
        assertTrue("srcMsgId 取 ref?.msgId", "val srcMsgId = ref?.msgId ?: 0L" in src)
        assertTrue("srcTalkerPrefix 已带冒号不重复加", "srcTalker.endsWith(\":\")" in src)
        assertTrue("srcBigImgPath 排除 th_ 前缀", "!File(path).name.startsWith(\"th_\")" in src)
        // 验收日志（值 take(40) 防刷屏）
        assertTrue("必须有 FORWARD ctx 验收日志", "FORWARD ctx srcMsgId=" in src)
        assertTrue("日志值必须 take(40)", "srcTalkerPrefix.take(40)" in src)
        // 备选链 NATIVE：attachForwardInfo 同源补齐 q1.r / q1.j / fw.b
        assertTrue("NATIVE 链必须写 q1.r", "setObjectField(q1, \"r\", snap.srcTalkerPrefix)" in src)
        assertTrue("NATIVE 链必须写 q1.j", "setObjectField(q1, \"j\", snap.srcBigImgPath)" in src)
        assertTrue(
            "NATIVE 链 fw.b 必须优先取 srcMsgId、取不到回退 fi.msgId",
            "if (snap.srcMsgId > 0L) snap.srcMsgId else fi.msgId" in src
        )
    }

    /** 红线：不碰 q1.B / CDN_IMAGE / modelimage.k2 / ot5；已上线的 constructorId 与 m.a 门控不倒退。 */
    @Test
    fun redLinesUntouched() {
        val fs = codeLines(source(FILE_SENDER))
        val ff = codeLines(source(FORCE_FORWARD))
        for ((name, code) in listOf("FileSender" to fs, "ForceForward" to ff)) {
            assertTrue(
                "$name 不得写 q1.B",
                code.none { "setObjectField(q1, \"B\"" in it || "setByteField(q1, \"B\"" in it }
            )
            assertTrue("$name 不得引入 CDN_IMAGE", code.none { "CDN_IMAGE" in it })
            assertTrue("$name 不得重建 modelimage.k2", code.none { "modelimage.k2" in it })
            assertTrue("$name 不得造 ot5", code.none { "ot5" in it })
        }
        assertTrue(
            "constructorId 仍须设 13",
            fs.any { "setIntField(q1, \"h\", CONSTRUCTOR_ID_IMAGE)" in it }
        )
        assertTrue(
            "m.a 的 >0 门控必须保留（不倒退已上线的 imgLocalId 修复）",
            fs.any { "if (imgLocalId > 0L) imgLocalId else -1L" in it }
        )
    }

    // ------------------------------------------------------ 工具（与 R73ImgLocalIdFixTest 同构）

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
}
