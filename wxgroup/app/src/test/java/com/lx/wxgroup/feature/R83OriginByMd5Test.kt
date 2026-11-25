package com.lx.wxgroup.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R83 选项2「字节落盘」fallback 回归锁（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 背景（主会话已钉死，本测试只锁代码事实、不重做根因）：源消息坏图的 ImgInfo2 记录里只有
 * th_ 缩略图 + 36B 占位，[ForceForward.p3SrcOf] 找不到绝对路径 ⇒ 回退缩略图发模糊；
 * 但原图字节按 md5 分桶躺在磁盘（`image2/<2>/<2>/<md5>.jpg`）。新增 fallback 从 img 抠 md5、
 * 从 snap.localPath 动态取 MicroMsg/<hash>，直算原图绝对路径。
 *
 * 锁定层次（全部读去注释后的代码行，注释 KDoc 里的历史串不得制造假绿）：
 * 1. [fallbackFunctionExistsAndStitchesMd5Buckets] 函数存在且拼 image2/<2>/<2>/<md5>.jpg；
 *    1b. [handlesBareMd5WithoutExtension] img 字段两种形态都要吃：带 .jpg/.jpeg/.png/.webp 与纯 32 位 md5（缺省补 .jpg，R76 真机失败形态）；
 *    1c. [preservesExplicitExtension] 带明确扩展名时保留原扩展名，不被默认 .jpg 覆盖；
 * 2. [threeThresholdsLockedInFallback] 三门槛已收敛为单一 gate 判据 P3AdmitGate.admit(f, true)（R85 路线 A）；
 * 3. [rootHashIsDynamicNotHardcoded] MicroMsg/<hash> 从 localPath 正则动态取，不硬编码；
 * 4. [logOnlyTailsNoFullHash] 命中日志只打尾 24 + 长度；
 * 5. [fallbackIsReadOnly] 不写宿主字段 / 不写 param；
 * 6. [wiredAfterSrcOfBeforeSrcBigImg] 两处接线（prepareBigImg + listener）优先级；
 * 7. [closedLoopHostWritesUntouched] 已闭环 q1 字段写入一字未动。
 *
 * 真实变异验证（对真实源码做内存变异，跑同一判据，确认会红）：
 * - M1 分桶位数 substring(0, 2)→(0, 3) ⇒ [mutationWrongBucketStitchingIsDetected]；
 * - M2 摘掉 strictShape（admit(f,true)→admit(f,false)）⇒ [mutationRelaxedThresholdIsDetected]；
 * - M3 hash 正则改硬编码        ⇒ [mutationHardcodedHashIsDetected]；
 * - M4 fallback 挪到 srcBigImg 之后 ⇒ [mutationReorderedFallbackAfterSrcBigImgIsDetected]；
 * - M5 listener 去掉 fallback   ⇒ [mutationDroppedListenerFallbackIsDetected]；
 * - M6 扩展名改必填 / 去掉缺省 .jpg ⇒ [mutationMandatoryExtensionIsDetected] /
 *   [mutationDroppedExtDefaultIsDetected]。
 */
class R83OriginByMd5Test {

    companion object {
        private const val FF = "app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt"
        private const val SIG = "private fun p3OriginFromMd5("
    }

    // ------------------------------------------------ 1) 函数存在 + md5 分桶拼接

    @Test
    fun fallbackFunctionExistsAndStitchesMd5Buckets() {
        val body = functionBody(codeLines(source(FF)), SIG)
        assertTrue("fallback 函数必须真实存在", body.isNotEmpty())
        assertTrue("必须按 md5 抠 32 位文件名", body.any { "0-9a-fA-F]{32}" in it })
        assertTrue("必须按 image2/<2>/<2>/<md5>.jpg 分桶拼接", md5PathStitchedOk(body))
    }

    /** R76 真机形态：img 字段可能是纯 32 位 md5（无 .jpg），必须缺省补 .jpg 后拼绝对路径再过三门槛。 */
    @Test
    fun handlesBareMd5WithoutExtension() {
        val body = functionBody(codeLines(source(FF)), SIG)
        assertTrue("抠文件名的正则扩展名必须可选（兼容裸 md5 与带 .jpg/.jpeg/.png/.webp 两形态）", extOptionalOk(body))
        assertTrue("裸 md5 无扩展名时必须补 .jpg", extDefaultJpgOk(body))
        assertTrue("三门槛必须用拼出的绝对路径 f 判", body.any { "val f = File(path)" in it })
    }

    /** 带明确扩展名（.jpg/.jpeg/.png/.webp）时必须保留原扩展名，不能被默认 .jpg 覆盖。 */
    @Test
    fun preservesExplicitExtension() {
        val body = functionBody(codeLines(source(FF)), SIG)
        assertTrue(
            "ext 必须取 groupValues[2]，命中才用、未命中才 ifBlank 补 .jpg",
            body.any { "m.groupValues[2].ifBlank { \"jpg\" }" in it }
        )
        assertTrue("正则必须覆盖 4 种图片扩展名", extOptionalOk(body))
    }

    // ------------------------------------------------ 2) 三门槛

    @Test
    fun threeThresholdsLockedInFallback() {
        assertTrue(
            "fallback 内必须存在 + >15KB + 非 th_ 三门槛（去注释判定）",
            threeThresholdsOk(functionBody(codeLines(source(FF)), SIG))
        )
    }

    // ------------------------------------------------ 3) hash 动态取，不硬编码

    @Test
    fun rootHashIsDynamicNotHardcoded() {
        val body = functionBody(codeLines(source(FF)), SIG)
        assertTrue("必须从 localPath 正则动态取 MicroMsg/<32位hash>/ 根", dynamicRootOk(body))
        // 主会话实测 hash 为 89476c7f...，落进源码即硬编码，必须禁止
        assertFalse("绝不能把实测 hash 硬编码进源码", body.any { "89476c7f" in it })
    }

    // ------------------------------------------------ 4) 日志只打尾 24 + 长度

    @Test
    fun logOnlyTailsNoFullHash() {
        val body = functionBody(codeLines(source(FF)), SIG)
        assertTrue(
            "命中日志必须只打 path.takeLast(24) 与 size，不打完整 hash/md5",
            body.any {
                "FORWARD P3 origin-by-md5 path=" in it &&
                    "takeLast(24)" in it && "size=" in it
            }
        )
    }

    // ------------------------------------------------ 5) 只读红线

    @Test
    fun fallbackIsReadOnly() {
        assertTrue(
            "fallback 只读 img/localPath，禁止写宿主字段 / param",
            readOnlyOk(functionBody(codeLines(source(FF)), SIG))
        )
    }

    // ------------------------------------------------ 6) 两处接线优先级

    @Test
    fun wiredAfterSrcOfBeforeSrcBigImg() {
        assertTrue(
            "prepareBigImg + listener 两处都须 p3SrcOf → fallback → srcBigImgPath",
            wiringOk(codeLines(source(FF)))
        )
    }

    // ------------------------------------------------ 7) 已闭环字段不碰

    @Test
    fun closedLoopHostWritesUntouched() {
        val code = codeLines(source(FF))
        assertTrue("q1.j 缩略图写入仍在", code.any { "setObjectField(q1, \"j\", snap.localPath)" in it })
        assertTrue("q1.l 写入仍在", code.any { "setObjectField(q1, \"l\", null)" in it })
        assertTrue("q1.r 写入仍在", code.any { "setObjectField(q1, \"r\", snap.srcTalkerPrefix)" in it })
        assertTrue("q1.j 原生大图写入仍在", code.any { "setObjectField(q1, \"j\", snap.srcBigImgPath)" in it })
    }

    // ------------------------------------------------ 真实源码变异验证

    /** M1：分桶位数 substring(0, 2)→(0, 3)，拼接判据必须判红。 */
    @Test
    fun mutationWrongBucketStitchingIsDetected() {
        val body = functionBody(codeLines(source(FF)), SIG)
        assertTrue("变异前置：函数体非空", body.isNotEmpty())
        val mutated = body.map { it.replace("substring(0, 2)", "substring(0, 3)") }
        assertTrue("变异必须真正改动源码", mutated != body)
        assertFalse("分桶位数改错必须被判红", md5PathStitchedOk(mutated))
    }

    /** M2：摘掉 strictShape（admit(f, true)→admit(f, false)），三门槛判据必须判红。 */
    @Test
    fun mutationRelaxedThresholdIsDetected() {
        val body = functionBody(codeLines(source(FF)), SIG)
        val mutated = body.map {
            it.replace("P3AdmitGate.admit(f, true)", "P3AdmitGate.admit(f, false)")
        }
        assertTrue("变异必须真正改动源码", mutated != body)
        assertFalse("strictShape 被摘掉必须被判红", threeThresholdsOk(mutated))
    }

    /** M3：把动态 hash 正则改成硬编码，动态根判据必须判红。 */
    @Test
    fun mutationHardcodedHashIsDetected() {
        val body = functionBody(codeLines(source(FF)), SIG)
        val mutated = body.map {
            it.replace(
                "Regex(\"/MicroMsg/[0-9a-fA-F]{32}/\")",
                "Regex(\"/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37/\")"
            )
        }
        assertTrue("变异必须真正改动源码", mutated != body)
        assertFalse("hash 硬编码必须被判红", dynamicRootOk(mutated))
    }

    /** M4：把 fallback 挪到 srcBigImgPath 之后，接线判据必须判红。 */
    @Test
    fun mutationReorderedFallbackAfterSrcBigImgIsDetected() {
        val code = codeLines(source(FF)).toMutableList()
        val md5 = code.indexOfFirst { "?: p3OriginFromMd5(img, snap.localPath)" in it }
        val big = code.indexOfFirst { "?: snap.srcBigImgPath.takeIf" in it }
        assertTrue("变异前置：两行都在且 fallback 在前", md5 >= 0 && big >= 0 && md5 < big)
        val t = code[md5]
        code[md5] = code[big]
        code[big] = t
        assertFalse("fallback 挪到 srcBigImgPath 之后必须被判红", wiringOk(code))
    }

    /** M5：listener 去掉 fallback，接线判据必须判红。 */
    @Test
    fun mutationDroppedListenerFallbackIsDetected() {
        val mutated = codeLines(source(FF)).map {
            it.replace(
                "val path = p3SrcOf(img) ?: p3OriginFromMd5(img, p3SrcLocalPath)",
                "val path = p3SrcOf(img)"
            )
        }
        assertFalse("listener 去掉 fallback 必须被判红", wiringOk(mutated))
    }

    /** M6：扩展名改必填（去掉可选 ?），裸 md5 形态判据必须判红。 */
    @Test
    fun mutationMandatoryExtensionIsDetected() {
        val body = functionBody(codeLines(source(FF)), SIG)
        val mutated = body.map {
            it.replace("(?:\\\\.(jpg|jpeg|png|webp))?", "\\\\.(jpg|jpeg|png|webp)")
        }
        assertTrue("变异必须真正改动源码", mutated != body)
        assertFalse("扩展名改必填必须被判红", extOptionalOk(mutated))
    }

    /** M7：去掉缺省 .jpg（ifBlank 改为原样返回），裸 md5 形态判据必须判红。 */
    @Test
    fun mutationDroppedExtDefaultIsDetected() {
        val body = functionBody(codeLines(source(FF)), SIG)
        val mutated = body.map { it.replace("ifBlank { \"jpg\" }", "ifBlank { \"\" }") }
        assertTrue("变异必须真正改动源码", mutated != body)
        assertFalse("去掉缺省 .jpg 必须被判红", extDefaultJpgOk(mutated))
    }

    // ------------------------------------------------ 判据（纯函数，输入 codeLines 结果）

    /** md5 分桶拼接：image2/<2>/<2>/<md5>.jpg，两段 substring 各取 2 位 + 保留扩展名。 */
    private fun md5PathStitchedOk(body: List<String>): Boolean =
        body.any {
            "image2/" in it &&
                "substring(0, 2)" in it &&
                "substring(2, 4)" in it &&
                "\$md5.\$ext" in it
        }

    /** 抠文件名的正则必须允许「带 .jpg/.jpeg」与「纯 32 位 md5 无扩展名」两形态。 */
    private fun extOptionalOk(body: List<String>): Boolean =
        body.any { "(?:\\\\.(jpg|jpeg|png|webp))?" in it }

    /** 纯 md5 无扩展名时缺省补 .jpg（R76 真机回调失败形态）。 */
    private fun extDefaultJpgOk(body: List<String>): Boolean =
        body.any { "ifBlank { \"jpg\" }" in it && "lowercase()" in it }

    /**
     * 三门槛判据（R85 迁移）：旧版锁死字面量 `f.exists() && f.length() > 15*1024 && !f.name.startsWith("th_")`，
     * 但该字面量已随路线 A 收敛为 [P3AdmitGate.admit] 而从源码消失，旧断言会假红。按 Pro §4.3
     * 「改判据而非删测试」：body 内出现 `P3AdmitGate.admit(f, true)` 即视为三门槛已被 gate 取代；
     * 同时保留 `val f = File(path)` 断言（闭环拼出的绝对路径仍喂给 gate 判定）。
     */
    private fun threeThresholdsOk(body: List<String>): Boolean =
        body.any { "val f = File(path)" in it } &&
            body.any { "P3AdmitGate.admit(f, true)" in it }

    /** 动态根：正则取 MicroMsg/<32位hash>/ + 从 localPath 截到根尾。 */
    private fun dynamicRootOk(body: List<String>): Boolean =
        body.any { "Regex(\"/MicroMsg/[0-9a-fA-F]{32}/\")" in it } &&
            body.any { "localPath.substring(0, rootHit.range.last + 1)" in it }

    /** 只读：函数体内不写宿主字段、不写 param.result/args。 */
    private fun readOnlyOk(body: List<String>): Boolean =
        body.none { it.contains("setObjectField") } &&
            body.none { Regex("param\\.(result|args)").containsMatchIn(it) }

    /**
     * 接线：prepareBigImg 里 p3SrcOf → p3OriginFromMd5(img, snap.localPath) → srcBigImgPath 顺序；
     * listener 里同样接 fallback（用 p3SrcLocalPath 字段），且字段声明/赋值齐全。
     */
    private fun wiringOk(code: List<String>): Boolean {
        val srcIdx = code.indexOfFirst { "p3SrcOf(img)" in it }
        val md5Idx = code.indexOfFirst { "p3OriginFromMd5(img, snap.localPath)" in it }
        val bigIdx = code.indexOfFirst { "snap.srcBigImgPath.takeIf" in it }
        if (srcIdx < 0 || md5Idx < 0 || bigIdx < 0) return false
        if (!(srcIdx < md5Idx && md5Idx < bigIdx)) return false
        val listenerFallback =
            code.any { "val path = p3SrcOf(img) ?: p3OriginFromMd5(img, p3SrcLocalPath)" in it }
        val fieldDecl = code.any { "private var p3SrcLocalPath: String = \"\"" in it }
        val fieldSet = code.any { "p3SrcLocalPath = snap.localPath" in it }
        return listenerFallback && fieldDecl && fieldSet
    }

    // ------------------------------------------------ 工具（与 R76P3WireTest 同构，零 Android 依赖）

    /** 从若干候选父目录向上找源码文件；兼容 gradle cwd = 仓库根 / wxgroup / wxgroup/app 三种情况。 */
    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        val candidates = mutableListOf<File>()
        for (i in 0 until 6) {
            if (dir == null) break
            candidates += File(dir, rel)
            candidates += File(dir, "wxgroup/$rel")
            dir = dir.parentFile
        }
        return candidates.firstOrNull { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?: throw AssertionError("找不到源码：$rel（工作目录=${System.getProperty("user.dir")}）")
    }

    /** 只留代码行（去注释/文档行），避免 KDoc 里引用的字符串造成假绿。 */
    private fun codeLines(src: String): List<String> = src.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        .toList()

    /** 取某函数体（含签名行）：从签名行起按大括号配平，深度归零即止。 */
    private fun functionBody(code: List<String>, sig: String): List<String> {
        val start = code.indexOfFirst { it.startsWith(sig) }
        if (start < 0) return emptyList()
        var depth = 0
        val out = mutableListOf<String>()
        for (i in start until code.size) {
            val line = code[i]
            out += line
            line.forEach { c ->
                if (c == '{') depth++
                else if (c == '}') depth--
            }
            if (i > start && depth == 0) break
        }
        return out
    }
}
