package com.lx.wxgroup.probe

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R73「真机观察微X图片转发为何清晰」只读取证探针回归锁（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 探针主体 [MicroXImageObserveProbe] 由另一 lane 独占新建，本测试只读其源码字符串 + 两处接线源码，
 * 不实例化、不依赖运行时类（故编译期不需要 Xposed/Android 类路径）。
 *
 * 锁的层次：
 * 1. [probeInterfaceMatchesContract] 锁死共享契约的 object + 三个函数签名（探针与接线两侧共同依赖）；
 * 2. [all13HookCodesPresent] / [criticalDexSignaturesPresent] 锁死 13 个只读 hook 落点没被删/改签名；
 * 3. [readOnlyRedLines] 锁死「只读」红线——不写宿主参数/返回值、不主动调宿主业务方法；
 * 4. [wiringPresent] 锁死 HookEntry 安装点 + EntryReceiver 的 `op=microimg` 广播入口；
 * 5. [statusFormatHasEnabledOkFail] 锁死 F2 前置校验要用的 status 回显格式。
 *
 * 变异有效性映射（本 lane 未跑 gradle，交主会话统一执行；手工变异 = 预期变红用例）：
 * - M1：删 `object MicroXImageObserveProbe` 或改任一函数签名 ⇒ [probeInterfaceMatchesContract] 变红。
 * - M2：删任一 hook code 的日志字符串（如去掉 "D5-..."）⇒ [all13HookCodesPresent] 变红。
 * - M3：把 D5 的 `getObjectField(..., "u")` 改成别的字段/删掉 ⇒ [criticalDexSignaturesPresent] 变红
 *   （终局判据丢失，等于把「r0.u 是否非空」这个清晰/模糊开关拿掉）。
 * - M4：在任一回调里加 `param.result = ...` 或 `param.args[0] = ...` ⇒ [readOnlyRedLines] 变红。
 * - M5：在回调里主动 `callMethod(r0, "R5"/"z3"/...)` 替宿主做事 ⇒ [readOnlyRedLines] 变红。
 * - M6：删 HookEntry 的 install 行 / 删 EntryReceiver 的 `microimg` 分支 ⇒ [wiringPresent] 变红。
 * - M7：status 去掉 `enabled=`/`ok=`/`fail=` ⇒ [statusFormatHasEnabledOkFail] 变红（F2 前置校验失效）。
 *
 * 注意：所有断言读的是**去注释后的代码行**，避免 KDoc 里引用的死字符串造成假绿。
 */
class MicroXImageObserveProbeTest {

    companion object {
        private const val PROBE = "app/src/main/java/com/lx/wxgroup/probe/MicroXImageObserveProbe.kt"
        private const val HOOK_ENTRY = "app/src/main/java/com/lx/wxgroup/HookEntry.kt"
        private const val ENTRY_RECEIVER = "app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt"

        /** 13 个必选 hook code（Q1 下载链 5 + Q2 入队链 5 + Q3 路径 3）。 */
        private val HOOK_CODES = listOf(
            "D1", "D2", "D3", "D4", "D5",
            "E1", "E2", "E3", "E4", "E5",
            "F1", "F2", "F3"
        )
    }

    /** 契约：object 单例 + 三个函数签名必须逐字存在（另一 lane 照此实现）。 */
    @Test
    fun probeInterfaceMatchesContract() {
        val code = codeLines(source(PROBE))
        assertTrue("探针必须是 object 单例", code.any { "object MicroXImageObserveProbe" in it })
        assertTrue(
            "install 签名须逐字符合契约：fun install(lpparam: XC_LoadPackage.LoadPackageParam)",
            code.any { "fun install(lpparam: XC_LoadPackage.LoadPackageParam)" in it }
        )
        assertTrue(
            "turn 签名须逐字符合契约：fun turn(on: Boolean)",
            code.any { "fun turn(on: Boolean)" in it }
        )
        assertTrue(
            "status 签名须逐字符合契约：fun status(): List<String>",
            code.any { "fun status(): List<String>" in it }
        )
    }

    /** 13 个 hook code 必须以字符串字面量落在源码（status 行 / 日志格式串），否则 F2 前置校验无从核对。 */
    @Test
    fun all13HookCodesPresent() {
        val literals = stringLiterals(source(PROBE))
        for (code in HOOK_CODES) {
            val asWord = Regex("\\b${Regex.escape(code)}\\b")
            assertTrue(
                "hook code $code 必须以字符串字面量出现（status / 日志格式串），实际字面量=$literals",
                literals.any { asWord.containsMatchIn(it) }
            )
        }
    }

    /** 关键 dex 签名串 + D5 的 r0.u 只读字段——终局判据的物理载体，删了就无法判清晰/模糊。 */
    @Test
    fun criticalDexSignaturesPresent() {
        val src = source(PROBE)
        val code = codeLines(src)
        val literals = stringLiterals(src)
        assertTrue("必须 hook com.tencent.mm.modelimage.b1（D1/D2 R5·j6、F1/F2 C4·z3）",
            "com.tencent.mm.modelimage.b1" in src)
        assertTrue("必须含 R5 方法名（下载兜底入口）", literals.any { it == "R5" })
        assertTrue("必须含 j6 方法名（真正下载大图）", literals.any { it == "j6" })
        assertTrue(
            "必须 hook mq.k.B8（CDN check 发起）：字面量 mq.k.B8 / mq/k.B8 或分开的 \"mq.k\"+\"B8\"",
            "mq.k.B8" in src || "mq/k.B8" in src ||
                (literals.any { it == "mq.k" } && literals.any { it == "B8" })
        )
        assertTrue("必须 hook com.tencent.mm.modelimage.j2（D5 终局判据所在类）",
            "com.tencent.mm.modelimage.j2" in src)
        assertTrue(
            "D5 必须用 getObjectField 只读 r0 的 \"u\" 字段（cdninfo 非空/空 = 清晰/模糊终局开关）",
            code.any { "getObjectField" in it && "\"u\"" in it }
        )
    }

    /** 只读红线：不写宿主入参/返回值、不主动调宿主业务方法（纯 getter 除外）。 */
    @Test
    fun readOnlyRedLines() {
        val code = codeLines(source(PROBE))
        val argWrite = Regex("param\\.args\\[[^]]*\\]\\s*=(?!=)")
        val resultWrite = Regex("param\\.result\\s*=(?!=)")
        assertTrue("严禁写宿主入参 param.args[...]（只读观察）", code.none { argWrite.containsMatchIn(it) })
        assertTrue("严禁写宿主返回值 param.result（只读观察）", code.none { resultWrite.containsMatchIn(it) })

        val activeCall = Regex("call(?:Static)?Method")
        for (name in listOf("R5", "j6", "B8", "C2", "o2", "z3", "Y2")) {
            assertTrue(
                "严禁主动调宿主方法 $name 替宿主做事（会引入下载/DB/发送副作用）；纯 getter 如 getType() 允许",
                code.none { activeCall.containsMatchIn(it) && "\"$name\"" in it }
            )
        }
    }

    /** 接线：HookEntry 安装点 + EntryReceiver 第一个 when(op) 里的 microimg 分支。 */
    @Test
    fun wiringPresent() {
        val hookEntry = codeLines(source(HOOK_ENTRY))
        val receiver = codeLines(source(ENTRY_RECEIVER))
        assertTrue(
            "HookEntry 必须 runCatching 安装 MicroXImageObserveProbe",
            hookEntry.any { "MicroXImageObserveProbe.install(lpparam)" in it }
        )
        assertTrue("EntryReceiver 必须有 \"microimg\" -> 分支", receiver.any { "\"microimg\" ->" in it })
        assertTrue("microimg on/off 必须调 MicroXImageObserveProbe.turn(",
            receiver.any { "MicroXImageObserveProbe.turn(" in it })
        assertTrue("microimg 默认分支必须回显 MicroXImageObserveProbe.status()",
            receiver.any { "MicroXImageObserveProbe.status()" in it })
    }

    /** F2 前置校验依赖的 status 回显格式（enabled=true installed=true ok=13 fail=0 + 每 hook 行）。 */
    @Test
    fun statusFormatHasEnabledOkFail() {
        val literals = stringLiterals(source(PROBE))
        assertTrue("status 必须打 \"MIO STATUS\" 汇总行", literals.any { "MIO STATUS" in it })
        assertTrue("status 必须回显 enabled=", literals.any { "enabled=" in it })
        assertTrue("status 必须回显 ok=", literals.any { "ok=" in it })
        assertTrue("status 必须回显 fail=", literals.any { "fail=" in it })
    }

    // ------------------------------------------------------ 工具（与 R73ImgLocalIdFixTest 同构，零 Android 依赖）

    /** 从若干候选父目录向上找源码文件；同时兼容 gradle cwd = 仓库根 / wxgroup / wxgroup/app 三种情况。 */
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

    /** 取代码行中的全部字符串字面量内容（去掉外层引号），用于断言 code 标识 / 日志格式串。 */
    private fun stringLiterals(src: String): List<String> {
        val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        return codeLines(src).flatMap { line ->
            literal.findAll(line).map { it.groupValues[1] }.toList()
        }
    }
}
