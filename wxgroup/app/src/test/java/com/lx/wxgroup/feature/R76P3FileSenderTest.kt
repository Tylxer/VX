package com.lx.wxgroup.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R76 P3「字节落盘」接线回归锁（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 只锁三件接线事实（不做根因推理、不改判据）：
 * 1. `FileSender.send` 必须是真实 **6 参**签名，第 6 参 `bigImgPath: String? = null`
 *    （默认 null ⇒ 所有现有调用点逐字节不变）；
 * 2. `q1.j` 的 P3 覆盖块必须插在 `forwardCtx?.let { ... }` 块结束（:207）之后、
 *    `XposedHelpers.callMethod(q1, "f", m)`（:208）之前，形成单调优先级
 *    「P3 大图 > 原生大图 > 缩略图」，且三处都在 `q1.a()` 之前；
 * 3. 三门槛（存在 + `length() > 15 * 1024` + 非 `th_` 前缀）与
 *    「默认 null 整块跳过 + 异常只丢本次覆盖」的判据文本必须真实落在源码里。
 *
 * 防假绿：所有源码断言一律走 [codeLines]（去注释行判定），注释里的历史签名/死字符串
 * 不得制造绿。变异用例对**真实源码**做内存变异后跑同一套判据，确认会红（非纸面声明）。
 *
 * 变异映射（本 lane 已离线跑过，见回报）：
 * - M1：门槛 `15 * 1024` 放宽/收紧 ⇒ [p3ThreeThresholdsAreLocked] 红；
 * - M2：删第 6 参 `bigImgPath` ⇒ [sendHasRealSixParamSignature] 红；
 * - M3：把 P3 覆盖块挪到 `callMethod(q1, "f", m)` 之后 ⇒ [p3OverrideBlockSitsBetweenLetCloseAndFCall] 红；
 * - M4：去掉 `if (!bigImgPath.isNullOrBlank())` 门控或独立 onFailure ⇒ [p3NullSkipsWholeBlock] 红。
 */
class R76P3FileSenderTest {

    companion object {
        private const val FILE_SENDER = "app/src/main/java/com/lx/wxgroup/core/FileSender.kt"
    }

    // ------------------------------------------------ 1) 真实 6 参签名（去注释判定）

    /** send 必须是真实 6 参签名，第 6 参默认 null（老调用点零影响）。 */
    @Test
    fun sendHasRealSixParamSignature() {
        assertTrue(
            "send 必须是真实 6 参签名（codeLines 去注释，防历史签名注释假绿）",
            sixParamSignatureOk(codeLines(source(FILE_SENDER)))
        )
    }

    // ------------------------------------------------ 2) P3 覆盖块顺序

    /**
     * P3 的 `q1.j` 覆盖必须在 `forwardCtx?.let` 块结束（:207）之后、`f(m)`（:208）之前，
     * 且优先级严格为 缩略图 < 原生大图 < P3 大图 < f(m) < q1.a()。
     */
    @Test
    fun p3OverrideBlockSitsBetweenLetCloseAndFCall() {
        assertTrue(
            "P3 q1.j 覆盖块必须在 :207 后、:208 前，且优先级 P3>原生大图>缩略图，三处都在 q1.a() 之前",
            p3OrderOk(codeLines(source(FILE_SENDER)))
        )
    }

    // ------------------------------------------------ 3) 三门槛

    /** 准入三门槛字符串必须全在代码里（去注释判定）。 */
    @Test
    fun p3ThreeThresholdsAreLocked() {
        assertTrue(
            "exists + length() > 15 * 1024 + 非 th_ 三门槛必须全在代码里（codeLines 去注释）",
            p3ThresholdsOk(codeLines(source(FILE_SENDER)))
        )
    }

    // ------------------------------------------------ 4) 默认 null 整块跳过 + 异常隔离

    /** 默认 null ⇒ 整块跳过；任何异常只丢本次覆盖，不冒泡到外层 runCatching。 */
    @Test
    fun p3NullSkipsWholeBlock() {
        val code = codeLines(source(FILE_SENDER))
        assertTrue(
            "第 6 参必须默认 null（老调用点逐字节不变）",
            code.any { it == "bigImgPath: String? = null," }
        )
        assertTrue(
            "整块必须由 !bigImgPath.isNullOrBlank() 门控：null/blank 时一行都不执行",
            code.any { it == "if (!bigImgPath.isNullOrBlank()) {" }
        )
        assertTrue(
            "P3 覆盖必须包在 runCatching 里（异常只丢本次覆盖）",
            code.any { "runCatching { XposedHelpers.setObjectField(q1, \"j\", bigImgPath) }" in it }
        )
        assertTrue(
            "P3 覆盖必须有独立 onFailure 日志（不影响外层 runCatching）",
            code.any { "FileSender P3 q1.j skipped:" in it }
        )
    }

    // ------------------------------------------------ 变异验证（对真实源码做内存变异，跑同一判据）

    /** M1：把 15KB 门槛放宽成 5KB，三门槛判据必须判红。 */
    @Test
    fun mutationRelaxedThresholdIsDetected() {
        val mutated = codeLines(source(FILE_SENDER)).map {
            it.replace("bf.length() > 15 * 1024", "bf.length() > 5 * 1024")
        }
        assertFalse("门槛从 15KB 放宽到 5KB 必须被判红", p3ThresholdsOk(mutated))
    }

    /** M2：删掉第 6 参，签名判据必须判红。 */
    @Test
    fun mutationDroppedSixthParamIsDetected() {
        val mutated = codeLines(source(FILE_SENDER)).filterNot { it == "bigImgPath: String? = null," }
        assertFalse("删掉第 6 参必须被判红", sixParamSignatureOk(mutated))
    }

    /** M3：把 P3 的 setObjectField 行挪到 f(m) 之后，顺序判据必须判红。 */
    @Test
    fun mutationReorderedP3AfterFCallIsDetected() {
        val code = codeLines(source(FILE_SENDER)).toMutableList()
        val p3 = code.indexOfFirst { "setObjectField(q1, \"j\", bigImgPath)" in it }
        val f = code.indexOfFirst { "callMethod(q1, \"f\", m)" in it }
        assertTrue("变异前置：P3 写入与 f 调用锚点都存在且 P3 在前", p3 >= 0 && f >= 0 && p3 < f)
        val t = code[p3]
        code[p3] = code[f]
        code[f] = t
        assertFalse("把 P3 覆盖挪到 :208 之后必须被判红", p3OrderOk(code))
    }

    // ------------------------------------------------ 判据（纯函数，输入 codeLines 结果）

    /** 六参必须按签名顺序连续出现在 `fun send(` 内，且 `): Boolean {` 在其后。 */
    private fun sixParamSignatureOk(code: List<String>): Boolean {
        val params = listOf(
            "kind: Kind,",
            "wxid: String,",
            "path: String,",
            "imgLocalId: Long = -1L,",
            "forwardCtx: ForwardLogic.Snapshot? = null,",
            "bigImgPath: String? = null,",
        )
        val idxs = params.map { code.indexOf(it) }
        if (idxs.any { it < 0 }) return false
        if (idxs != idxs.sorted()) return false
        if (idxs.zipWithNext().any { (a, b) -> b != a + 1 }) return false
        return code.indexOf("): Boolean {") > idxs.last()
    }

    /** 单调优先级：缩略图 < 原生大图 < P3 < f(m) < q1.a()。 */
    private fun p3OrderOk(code: List<String>): Boolean {
        val thumb = code.indexOfFirst { "setObjectField(q1, \"j\", path)" in it }
        val native = code.indexOfFirst { "setObjectField(q1, \"j\", ctx.srcBigImgPath)" in it }
        val p3 = code.indexOfFirst { "setObjectField(q1, \"j\", bigImgPath)" in it }
        val fCall = code.indexOfFirst { "callMethod(q1, \"f\", m)" in it }
        val build = code.indexOfFirst { "callMethod(q1, \"a\")" in it }
        if (listOf(thumb, native, p3, fCall, build).any { it < 0 }) return false
        return thumb < native && native < p3 && p3 < fCall && fCall < build
    }

    /** 三门槛：存在 + >15KB + 非 th_ 前缀（逐项 + 完整条件行）。 */
    private fun p3ThresholdsOk(code: List<String>): Boolean =
        code.any { "bf.exists()" in it } &&
            code.any { "bf.length() > 15 * 1024" in it } &&
            code.any { "bf.name.startsWith(\"th_\")" in it } &&
            code.any { "if (bf.exists() && bf.length() > 15 * 1024 && !bf.name.startsWith(\"th_\"))" in it }

    // ------------------------------------------------ 工具（与 R73ImgLocalIdFixTest 同构）

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

    /** 只留代码行（去掉注释/文档行），避免注释里的历史签名或死字符串制造假绿。 */
    private fun codeLines(src: String): List<String> = src.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        .toList()
}
