package com.lx.wxgroup.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R80 最终裁决 (a) 回归锁（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 本测试只读源码字符串 + 两处接线源码，不实例化、不依赖运行时类（故编译期不需要 Xposed/Android 类路径）。
 * 所有断言读**去注释后的代码行**（[codeLines]），避免 KDoc 里引用的死字符串造成假绿。
 *
 * 锁的层次：
 * 1. [objectAndApiContractPresent] object 单例 + install/arm/clear 签名；
 * 2. [m1eHookAnchorsPresent] ok0/m1.e(tj.k)Z hook（精确 + hookAllMethods 兜底 + msig）；
 * 3. [m1eHookWritesNonEmptyFileIdIntoTask] **R80 §2.5 判据文本**：hook m1.e 后 task.field_fileId 非空；
 * 4. [armReadsCdnParamsReadOnly] arm 反射只读链 d3.O8().a3 → r0.u → 三参 g9.c → cdnbigimgurl/aeskey；
 * 5. [gateTtlOneShotPresent] enabled/pending 闸门 + TTL 2000ms + 一次性消费 + 只打长度；
 * 6. [readOnlyRedLines] 写宿主仅限 inject 两处 task 字段，arm 只读、不写 r0/DB、不写 param；
 * 7. [fileSenderWiringPresent] / [hookEntryWiringPresent] 两处接线。
 *
 * 变异有效性（本 lane 未跑 gradle，交主会话统一三闸）：
 * - [mutationRealFileIdFieldIsDetected]：真实源码把 setObjectField 的 "field_fileId" 改掉 ⇒ 第 3 条判红；
 * - [mutationRealG9ThreeArgsIsDetected]：真实源码把 g9.c 的三参改成两参 ⇒ 第 4 条判红。
 */
class ForwardCdnFieldHookTest {

    companion object {
        private const val HOOK = "app/src/main/java/com/lx/wxgroup/core/ForwardCdnFieldHook.kt"
        private const val FILE_SENDER = "app/src/main/java/com/lx/wxgroup/core/FileSender.kt"
        private const val HOOK_ENTRY = "app/src/main/java/com/lx/wxgroup/HookEntry.kt"
    }

    /** 契约：object 单例 + 三个函数签名必须逐字存在。 */
    @Test
    fun objectAndApiContractPresent() {
        val code = codeLines(source(HOOK))
        assertTrue("必须是 object 单例", code.any { "object ForwardCdnFieldHook" in it })
        assertTrue(
            "install 签名须为 fun install(lpparam: XC_LoadPackage.LoadPackageParam)",
            code.any { "fun install(lpparam: XC_LoadPackage.LoadPackageParam)" in it }
        )
        assertTrue(
            "arm 签名须为 fun arm(talker: String, msgSvrId: Long, imgLocalId: Long)",
            code.any { "fun arm(talker: String, msgSvrId: Long, imgLocalId: Long)" in it }
        )
        assertTrue("必须有 fun clear()", code.any { "fun clear()" in it })
    }

    /** 全局上传下发点 hook：ok0.m1.e(tj.k)Z，精确签名 + hookAllMethods 兜底 + msig。 */
    @Test
    fun m1eHookAnchorsPresent() {
        val code = codeLines(source(HOOK))
        assertTrue("必须 findClass(\"ok0.m1\")", code.any { "findClass(\"ok0.m1\"" in it })
        assertTrue("必须 findClass(\"tj.k\") 作精确参数", code.any { "findClass(\"tj.k\"" in it })
        assertTrue("必须精确挂方法 e", code.any { "m.name == \"e\"" in it })
        assertTrue("返回类型必须是 boolean（Boolean.TYPE）", code.any { "java.lang.Boolean.TYPE" in it })
        assertTrue("找不到签名须 hookAllMethods(\"e\") 兜底",
            code.any { "hookAllMethods(m1cls, \"e\", cb)" in it })
        assertTrue("兜底回调必须打 msig（实际命中签名）",
            code.any { "msig=" in it && "sigOf(param)" in it })
        assertTrue("after 不得写任何东西（本方法只有 before）",
            code.none { "afterHookedMethod" in it })
    }

    /**
     * R80 §2.5 判据文本：**hook m1.e 后 task.field_fileId 非空**。
     * 当前无任何单测覆盖该字段写入——本条锁定 before 内 inject 对 task 的两处 setObjectField。
     */
    @Test
    fun m1eHookWritesNonEmptyFileIdIntoTask() {
        val code = codeLines(source(HOOK))
        assertTrue(
            "hook m1.e 后 task.field_fileId 非空：before 必须 setObjectField(task,\"field_fileId\", fileId)",
            m1eWritesFields(code)
        )
    }

    /** arm 反射只读取源 CDN 参数：d3.O8().a3 → r0.u → 三参 g9.c → cdnbigimgurl/aeskey；不写 r0/DB。 */
    @Test
    fun armReadsCdnParamsReadOnly() {
        val code = codeLines(source(HOOK))
        assertTrue("必须 d3.O8()", code.any { "callStaticMethod(d3cls, \"O8\")" in it })
        assertTrue("必须 a3(talker, msgSvrId)",
            code.any { "callMethod(o8, \"a3\", talker, msgSvrId)" in it })
        assertTrue("必须只读 r0.u", code.any { "getObjectField(r0, \"u\")" in it })
        assertTrue("必须 findClass(\"com.tencent.mm.sdk.platformtools.g9\")",
            code.any { "findClass(\"com.tencent.mm.sdk.platformtools.g9\"" in it })
        assertTrue("g9.c 必须是三参 (u, \"msg\", \"\")", armReadsCdnViaThreeArgG9(code))
        assertTrue("必须取 .msg.img.\$cdnbigimgurl",
            code.any { ".msg.img." in it && "cdnbigimgurl" in it })
        assertTrue("退路必须取 .msg.img.\$cdnmidimgurl",
            code.any { ".msg.img." in it && "cdnmidimgurl" in it })
        assertTrue("必须取 .msg.img.\$aeskey",
            code.any { ".msg.img." in it && "aeskey" in it })
        assertTrue("arm 只读 r0，不写 r0 字段",
            code.none { Regex("set(?:Object|Long|Int|Boolean)Field\\(\\s*r0").containsMatchIn(it) })
        assertTrue("arm 不得写 DB / ImgInfo2（无 set 消息表调用）",
            code.none { it.contains("setObjectField") && (it.contains("img") || it.contains("DB")) })
    }

    /** 闸门 + TTL + 一次性消费 + 只打长度。 */
    @Test
    fun gateTtlOneShotPresent() {
        val code = codeLines(source(HOOK))
        assertTrue("m1.e before 首行必须 enabled 闸门",
            code.count { it == "if (!enabled) return" } >= 1)
        assertTrue("无 pending 必须直接 return，绝不碰 task",
            code.any { "val p = pending ?: return" in it })
        assertTrue("TTL 必须为 2000ms", code.any { "TTL_MS = 2000L" in it })
        assertTrue("必须按 now - ts 判 TTL",
            code.any { "System.currentTimeMillis() - p.ts > TTL_MS" in it })
        assertTrue("pending / enabled 必须是 @Volatile 状态",
            code.any { "private var pending: Pending? = null" in it } &&
                code.any { "private var enabled = false" in it } &&
                code.count { it == "@Volatile" } >= 3)
        assertTrue("TTL 超时必须清 pending 并关闸",
            code.any { "arm expired reason=ttl" in it })
        assertTrue("一次性消费：注入后立即清 pending 并关闸",
            code.count { it == "pending = null" } >= 3 &&
                code.count { it == "enabled = false" } >= 3)
        assertTrue("skip 必须打原因（不打值）",
            code.any { "arm skip reason=\$reason" in it })
        assertTrue("注入日志只打长度，绝不打完整 fileId/aesKey",
            code.any { "fileIdLen=" in it && "aesKeyLen=" in it } &&
                stringLiterals(source(HOOK)).none {
                    it.contains("fileId=$") || it.contains("aesKey=$")
                })
    }

    /** 只读红线：写宿主仅限 inject 两处 task 字段，arm 只读，不写 param。 */
    @Test
    fun readOnlyRedLines() {
        val code = codeLines(source(HOOK))
        val setFields = code.filter {
            Regex("set(?:Object|Long|Int|Boolean|Short|Float|Double|Byte|Char)Field").containsMatchIn(it)
        }
        assertTrue("只允许两处写宿主字段（实际 ${setFields.size}）", setFields.size == 2)
        assertTrue(
            "只允许写 task 的 field_fileId/field_aesKey",
            setFields.all {
                "setObjectField(task, \"field_fileId\"" in it ||
                    "setObjectField(task, \"field_aesKey\"" in it
            }
        )
        assertTrue("严禁写 param.result / param.args",
            code.none { Regex("param\\.result\\s*=(?!=)|param\\.args\\[[^]]*\\]\\s*=(?!=)").containsMatchIn(it) })
    }

    /** FileSender 接线：arm（每目标发送前）+ onFailure clear。 */
    @Test
    fun fileSenderWiringPresent() {
        val fs = codeLines(source(FILE_SENDER))
        assertTrue("FileSender 必须 import ForwardCdnFieldHook",
            fs.any { "import com.lx.wxgroup.core.ForwardCdnFieldHook" in it })
        assertTrue("arm 条件必须是 imgLocalId>0 且 msgSvrId>0",
            fs.any { "ctx.imgLocalId > 0L && ctx.msgSvrId > 0L" in it })
        assertTrue("FileSender 必须调 arm(sourceChat, msgSvrId, imgLocalId)",
            fs.any { "ForwardCdnFieldHook.arm(ctx.sourceChat, ctx.msgSvrId, ctx.imgLocalId)" in it })
        assertTrue("FileSender 的 runCatching onFailure 必须 clear",
            fs.any { "ForwardCdnFieldHook.clear()" in it })
    }

    /** HookEntry 接线：FileSender.prepare 之后 runCatching install + WARN。 */
    @Test
    fun hookEntryWiringPresent() {
        val he = codeLines(source(HOOK_ENTRY))
        assertTrue("HookEntry 必须 import ForwardCdnFieldHook",
            he.any { "import com.lx.wxgroup.core.ForwardCdnFieldHook" in it })
        val prepareIdx = he.indexOfFirst { "FileSender.prepare(lpparam)" in it }
        val installIdx = he.indexOfFirst { "ForwardCdnFieldHook.install(lpparam)" in it }
        assertTrue("install 必须在 FileSender.prepare 之后（prepare=$prepareIdx install=$installIdx）",
            prepareIdx >= 0 && installIdx > prepareIdx)
        assertTrue("install 失败只 WARN，不崩微信",
            he.any { "ForwardCdnFieldHook install failed" in it && "LogX.w(" in it })
    }

    // ------------------------------------------------------ 真实源码变异（证明判据判别力）

    /** 真实变异：把 setObjectField 的 "field_fileId" 改掉，必须被 §2.5 判据判红。 */
    @Test
    fun mutationRealFileIdFieldIsDetected() {
        val src = source(HOOK)
        val mutated = src.replace("\"field_fileId\"", "\"field_fileIdX\"")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        assertTrue(
            "真实变异（改掉 field_fileId）必须被 hook m1.e 后 task.field_fileId 非空判据判红",
            !m1eWritesFields(codeLines(mutated))
        )
    }

    /** 真实变异：把 g9.c 三参改成两参，必须被三参判据判红。 */
    @Test
    fun mutationRealG9ThreeArgsIsDetected() {
        val src = source(HOOK)
        val mutated = src.replace(", \"msg\", \"\")", ", \"msg\")")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        assertTrue(
            "真实变异（g9.c 去掉第三参）必须被三参判据判红",
            !armReadsCdnViaThreeArgG9(codeLines(mutated))
        )
    }

    // ------------------------------------------------------ 判定函数（主测 + 变异共用）

    /** hook m1.e 后 task.field_fileId 非空：before 调 inject、inject 内写两处 task 字段。 */
    private fun m1eWritesFields(code: List<String>): Boolean {
        val fileId = code.any { "setObjectField(task, \"field_fileId\"" in it }
        val aesKey = code.any { "setObjectField(task, \"field_aesKey\"" in it }
        val inBefore = code.any { "beforeHookedMethod(param: MethodHookParam)" in it } &&
            code.any { "inject(param)" in it }
        return fileId && aesKey && inBefore
    }

    /** g9.c 必须是三参 (u, "msg", "")。 */
    private fun armReadsCdnViaThreeArgG9(code: List<String>): Boolean =
        code.any { it.contains("callStaticMethod(g9cls, \"c\",") && it.contains("\"msg\", \"\"") }

    // ------------------------------------------------------ 工具（与 StockCdnProbeTest 同构，零 Android 依赖）

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

    /** 只留代码行（去掉注释/文档行），避免 KDoc 里引用的字符串造成假绿。 */
    private fun codeLines(src: String): List<String> = src.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        .toList()

    /** 取代码行中的全部字符串字面量内容（去掉外层引号）。 */
    private fun stringLiterals(src: String): List<String> {
        val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        return codeLines(src).flatMap { line ->
            literal.findAll(line).map { it.groupValues[1] }.toList()
        }
    }
}
