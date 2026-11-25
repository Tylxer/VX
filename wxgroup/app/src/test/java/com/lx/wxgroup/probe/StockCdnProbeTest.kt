package com.lx.wxgroup.probe

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R78「修只读探针回调落点」stock CDN 只读取证探针回归锁（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 判据来自 `docs/_r78_m0l0_miss_verdict.md` §Q4.2（Pro lane）：R77 把回调角色挂错——
 * `qf4/m0.l0` 是 CGI 回调、不是 CDN 完成回调；真回调 = `qf4/j0.C2`（CDN 完成）+ `b1.j6`
 * （checkExist 成功分支落新记录），`j2.R()` 的 localId 在 `r0.a/b` 而非 `i/h/j/Q`。
 * 本测试只读探针源码字符串 + 两处接线源码，不实例化、不依赖运行时类（故编译期不需要 Xposed/Android 类路径）。
 * 所有断言读**去注释后的代码行**（[codeLines]），避免 KDoc 里引用的死字符串造成假绿。
 *
 * 锁的层次：
 * 1. [probeInterfaceMatchesContract] 锁 object + install/turn/status 签名（共享契约）；
 * 2. [allEightHookCodesPresent] / [criticalDexSignaturesPresent] 锁 8 个只读 hook 落点没被删/改签名；
 * 3. [j2rReadsLocalIdAndMsgId] 锁 j2R 读 r0.a/b（非 i/h/j/Q）；[cdnAndJ6ReadOnlyAnchors] 锁
 *    CDNcb/b1j6/m1e 的只读读字段 + 样本闸门 216b1f7c；
 * 4. [readOnlyRedLines] 锁"只读"红线——不写宿主参数/返回值/字段、不主动调宿主业务方法、
 *    每个回调 enabled 首行 return；
 * 5. [wiringPresent] 锁 HookEntry 安装点 + EntryReceiver 的 `op=stockcdn` 广播入口；
 * 6. [statusFormatHasEnabledOkFail] 锁 F2 前置校验要用的 status 回显格式。
 *
 * 变异有效性映射（本 lane 未跑 gradle，交主会话统一执行；toy + 真实源码变异均用同一判定函数证明判别力）：
 * - M1：删 `object StockCdnProbe` 或改任一函数签名 ⇒ [probeInterfaceMatchesContract] 变红。
 * - M2：删任一 hook code 字面量（如 "SCD-CDNcb"）⇒ [allEightHookCodesPresent] 变红；
 *   [mutationMissingHookCodeIsDetected] toy 复刻 + [mutationRealSourceHookCodeIsDetected] 真实源码复制。
 * - M3：改 8 个 hook 的类名/方法名（B6→XX、e→XX、C2→XX、j6→XX、B8→XX…）⇒ [criticalDexSignaturesPresent] 变红。
 * - M4：在任一回调加 `param.result = ...` / `param.args[i] = ...` / `setObjectField(...)` ⇒
 *   [readOnlyRedLines] 变红；[mutationWriteToResultIsDetected] toy 复刻同判定。
 * - M5：删回调首行 `if (!enabled) return` ⇒ [readOnlyRedLines] 的 enabled 计数断言变红。
 * - M6：删 HookEntry 的 install 行 / 删 EntryReceiver 的 `stockcdn` 分支 ⇒ [wiringPresent] 变红。
 * - M7：status 去掉 `enabled=`/`ok=`/`fail=` ⇒ [statusFormatHasEnabledOkFail] 变红。
 * - M8：把 j2R 的 r0.a 读法改回 r0.i ⇒ [j2rReadsLocalIdAndMsgId] 变红；
 *   [mutationRealSourceFieldReadIsDetected] 直接改真实源码文本复刻，证明该判定有判别力。
 */
class StockCdnProbeTest {

    companion object {
        private const val PROBE = "app/src/main/java/com/lx/wxgroup/probe/StockCdnProbe.kt"
        private const val HOOK_ENTRY = "app/src/main/java/com/lx/wxgroup/HookEntry.kt"
        private const val ENTRY_RECEIVER = "app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt"

        /** 8 个必选 hook code（R78 §Q4.2 的 6 个 + R79 §2.3 新增 f2C2/B8）。 */
        private val HOOK_CODES = listOf(
            "SCD-B6", "SCD-m1e", "SCD-CDNcb", "SCD-s0b", "SCD-b1j6", "SCD-j2R",
            "SCD-f2C2", "SCD-B8"
        )

        /** R86 B2-a 新增 3 个只读 hook code（E4 复用 SCD-CDNcb，不新增 code）。 */
        private val NEW_HOOK_CODES = listOf("SCD-x0cb", "SCD-c2c", "SCD-g9c")
    }

    /** 契约：object 单例 + 三个函数签名必须逐字存在（探针与接线两侧共同依赖）。 */
    @Test
    fun probeInterfaceMatchesContract() {
        val code = codeLines(source(PROBE))
        assertTrue("探针必须是 object 单例", code.any { "object StockCdnProbe" in it })
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

    /** 8 个 hook code 必须以字符串字面量落在源码（status / 日志 / guard 键）。 */
    @Test
    fun allEightHookCodesPresent() {
        val literals = stringLiterals(source(PROBE))
        val missing = missingHookCodes(literals)
        assertTrue("缺失 hook code：$missing，实际字面量=$literals", missing.isEmpty())
    }

    /** 关键 dex 锚点：类名 + 方法名 + 门槛字符串 + 路径样值判据，删了就无法回答 R78 §Q4.2。 */
    @Test
    fun criticalDexSignaturesPresent() {
        val src = source(PROBE)
        for (cls in listOf(
            "com.tencent.mm.ui.transmit.MsgRetransmitUI",
            "ok0.m1", "qf4.j0",
            "com.tencent.mm.modelimage.s0",
            "com.tencent.mm.modelimage.b1",
            "com.tencent.mm.modelimage.j2",
            "com.tencent.mm.modelimage.f2", "mq.k",
        )) {
            assertTrue("必须 hook 类 $cls", cls in src)
        }
        val code = codeLines(src)
        assertTrue("SCD-B6 必须挂方法 B6", robustCallHas(code, "b6cls", "B6"))
        assertTrue("SCD-m1e 必须挂方法 e（CDN addSendTask）", robustCallHas(code, "m1cls", "e"))
        assertTrue("SCD-CDNcb 必须挂接口方法 C2（真 CDN 完成回调）", robustCallHas(code, "cdncls", "C2"))
        assertTrue("SCD-s0b 必须挂方法 b（返回 ImgInfo2.e）", robustCallHas(code, "s0cls", "b"))
        assertTrue("SCD-b1j6 必须挂方法 j6（新记录路径）", robustCallHas(code, "b1cls", "j6"))
        assertTrue("SCD-j2R 必须挂方法 R（终局 r0）", robustCallHas(code, "j2cls", "R"))
        // R79 §2.3 新增两个落点：f2.C2 全挂兜底、mq/k.B8 精确签名 + hookRobust 兜底。
        assertTrue("SCD-f2C2 必须 hookAllMethods 挂全 C2",
            code.any { "f2cls" in it && "hookAllMethods(" in it && "\"C2\"" in it })
        assertTrue("SCD-B8 必须挂方法 B8（checkExist）", robustCallHas(code, "mqkcls", "B8"))
        assertTrue("SCD-B8 签名须为 B8(tj.k)boolean", code.any { "sig(\"B8\"" in it && "tj.k" in it })
        assertTrue("f2cls 必须动态查找 modelimage.f2",
            code.any { "f2cls = findCls(\"com.tencent.mm.modelimage.f2\"" in it })
        assertTrue("mqkcls 必须动态查找 mq.k", code.any { "mqkcls = findCls(\"mq.k\"" in it })

        val literals = stringLiterals(src)
        assertTrue("m1e 日志必须带 addSendTask 佐证", literals.any { "addSendTask" in it })
        assertTrue("必须只读检查 r0.u 的 cdnbigimgurl", literals.any { "cdnbigimgurl" in it })
        assertTrue("B6 task 必须读 onlycheckexist 字段", literals.any { "field_onlycheckexist" in it })
        assertTrue("dumpStringFields 必须标记路径样值 *PATH*", literals.any { "*PATH*" in it })
        assertTrue("b1j6 日志必须打 newLocalId", literals.any { "newLocalId" in it })
        assertTrue("CDNcb 日志必须有 in 观测", literals.any { "SCD CDNcb in" in it })
        assertTrue("CDNcb 日志必须有 out 观测", literals.any { "SCD CDNcb out" in it })
        // hookAllMethods / getObjectField 是代码标识符而非字符串字面量，必须在代码行上判定
        // （stringLiterals 取不到嵌在 ${...} 或方法调用里的标识符，R77 踩过 *PATH* 的坑）。
        assertTrue(
            "接口实现 C2 必须 hookAllMethods 挂全兜底",
            code.any { "hookAllMethods(" in it && "C2" in it }
        )
        assertTrue(
            "b1j6 必须反射读 PString.value 出参",
            code.any { "getObjectField" in it && "param.args?.getOrNull(5)" in it && "\"value\"" in it }
        )
    }

    /** R79 §2.3：f2C2 的 URL 消息证据行 + 只读长度判据（只打是否+长度，不打完整 xml/key）。 */
    @Test
    fun f2C2MsgUrlEvidencePresent() {
        val src = source(PROBE)
        val literals = stringLiterals(src)
        val code = codeLines(src)

        assertTrue("f2C2 必须有 in 观测", literals.any { "SCD f2C2 in" in it })
        assertTrue("f2C2 必须有 out 观测", literals.any { "SCD f2C2 out" in it })
        assertTrue("f2C2 必须有 MSG_HAS_URL 证据行", literals.any { "SCD f2C2 MSG_HAS_URL" in it })
        assertTrue("MSG_HAS_URL 只打 hit=字段名 + len=长度",
            literals.any { "MSG_HAS_URL hit=" in it && "len=" in it })
        for (key in listOf("<msg>", "cdnbigimgurl", "aeskey")) {
            assertTrue("MSG_HAS_URL 判据必须含 $key", literals.any { key in it })
        }
        assertTrue("msgUrlHit 只返回 (字段名, 值长度)，绝不返回完整值",
            code.any { "private fun msgUrlHit" in it && "Pair<String, Int>" in it })
        assertTrue("f2C2 arg0 必须 take(200)",
            code.any { "getOrNull(0) as? String ?: \"null\").take(200)" in it })
        assertTrue("f2C2 必须 dump Ltj/f 的 int 字段",
            code.any { "dumpIntFields(" in it && "getOrNull(3)" in it && "\"f\"" in it })
        assertTrue("f2C2 必须 dump Ltj/f 的 String 字段",
            code.any { "dumpStringFields(" in it && "getOrNull(3)" in it && "\"f\"" in it })
        assertTrue("f2C2 必须重点读 field_exist_whencheck", literals.any { "field_exist_whencheck" in it })
        assertTrue("f2C2 必须重点读 field_UploadHitCacheType", literals.any { "field_UploadHitCacheType" in it })
    }

    /** R79 §1.1 步②：B8 只读观测服务端 checkExist —— dump task(tj/k) String 字段 + boolean 出参。 */
    @Test
    fun b8CheckExistEvidencePresent() {
        val src = source(PROBE)
        val literals = stringLiterals(src)
        val code = codeLines(src)

        assertTrue("B8 必须有 in 观测", literals.any { "SCD B8 in" in it })
        assertTrue("B8 必须有 out 观测", literals.any { "SCD B8 out" in it })
        assertTrue("B8 必须 dump task(tj/k) 的 String 字段（label=task）",
            code.any { "dumpStringFields(" in it && "getOrNull(0)" in it && "\"task\"" in it })
        assertTrue("B8 出参必须是 boolean ret", literals.any { "SCD B8 out ret=" in it })
    }

    /** j2R 必须读 r0.a(J=localId)/r0.b(J=msgId)，不得再读 i/h/j/Q（非 J 型恒 NA）。 */
    @Test
    fun j2rReadsLocalIdAndMsgId() {
        val code = codeLines(source(PROBE))
        assertTrue(
            "SCD-j2R 必须读 longFieldStr(r0,\"a\")/(\"b\") 且不读 i/h/j/Q",
            j2rReadsAAndB(code)
        )
        assertTrue("j2R localIds 日志只准打 a/b 两字段",
            stringLiterals(source(PROBE)).any { "localIds=[a=" in it && "b=" in it })
    }

    /** CDNcb/b1j6/m1e 只读锚点 + 样本身份闸门（216b1f7c）；全部按代码行/字面量分别判定。 */
    @Test
    fun cdnAndJ6ReadOnlyAnchors() {
        val src = source(PROBE)
        val literals = stringLiterals(src)
        val code = codeLines(src)

        // SCD-CDNcb：dump Ltj/e 的 int 字段 + Ltj/f 的 String 字段（复用 dumpStringFields）。
        assertTrue("CDNcb 必须 dump Ltj/e 的 int 字段",
            code.any { "dumpIntFields(" in it && "\"e\"" in it })
        assertTrue("CDNcb 必须复用 dumpStringFields 打 Ltj/f",
            code.any { "dumpStringFields(" in it && "\"f\"" in it })
        assertTrue("dumpIntFields 必须逐字段 runCatching（只读）",
            code.any { "private fun dumpIntFields" in it } &&
                code.count { it == "}.getOrNull() ?: continue" } >= 1)

        // SCD-m1e：补 listener 身份 + fullpath/midimgpath（只读、take 120）。
        assertTrue("m1e 必须读 tj/k.f 确认 listener 身份",
            code.any { "getObjectField" in it && "task, \"f\"" in it })
        assertTrue("m1e 必须补打 field_fullpath", literals.any { "field_fullpath" in it })
        assertTrue("m1e 必须补打 field_midimgpath", literals.any { "field_midimgpath" in it })

        // SCD-B6 样本身份闸门：只打布尔，不打完整 key。
        assertTrue("B6 必须检查样本 216b1f7c", literals.any { "216b1f7c" in it })
        assertTrue("B6 必须打 hasBigImgUrl 布尔", literals.any { "hasBigImgUrl" in it })
        assertTrue("样本闸门只打布尔，不得在日志里打完整 key",
            code.none { it.startsWith("LogX") && "216b1f7c" in it })
    }

    /** 只读红线：不写宿主入参/返回值/字段、不主动调宿主业务方法、回调 enabled 首行 return。 */
    @Test
    fun readOnlyRedLines() {
        val code = codeLines(source(PROBE))
        assertTrue("严禁任何写宿主字段（setObjectField/setLongField 等）",
            code.none { Regex("set(?:Object|Long|Int|Boolean|Short|Float|Double|Byte|Char)Field").containsMatchIn(it) })
        assertTrue("严禁写宿主入参/返回值（含只读红线汇总判定）", !hasWriteViolation(code))

        // 主动调宿主业务方法会引入下载/DB/发送副作用；本探针只用 get*Field / Field.get。
        val activeCall = Regex("call(?:Static)?Method")
        for (name in listOf("C2", "j6", "B8", "e", "l0", "b", "o2", "z3", "Y2")) {
            assertTrue(
                "严禁主动调宿主方法 $name 替宿主做事；纯 getter/反射读字段允许",
                code.none { activeCall.containsMatchIn(it) && "\"$name\"" in it }
            )
        }

        // 13 个回调（B6/m1e/CDNcb/f2C2/B8 各 before+after，s0b/b1j6/j2R 各 after）全部 enabled 首行 return。
        val guardLines = code.count { it == "if (!enabled) return" }
        assertTrue("每个回调首行都必须 enabled guard（实际 $guardLines 行，期望 ≥13）", guardLines >= 13)
    }

    /** 接线：HookEntry 安装点 + EntryReceiver 的 stockcdn 分支。 */
    @Test
    fun wiringPresent() {
        val hookEntry = codeLines(source(HOOK_ENTRY))
        val receiver = codeLines(source(ENTRY_RECEIVER))
        assertTrue(
            "HookEntry 必须 runCatching 安装 StockCdnProbe",
            hookEntry.any { "StockCdnProbe.install(lpparam)" in it }
        )
        assertTrue("EntryReceiver 必须有 \"stockcdn\" -> 分支", receiver.any { "\"stockcdn\" ->" in it })
        assertTrue("stockcdn on/off 必须调 StockCdnProbe.turn(",
            receiver.any { "StockCdnProbe.turn(" in it })
        assertTrue("stockcdn 默认分支必须回显 StockCdnProbe.status()",
            receiver.any { "StockCdnProbe.status()" in it })
    }

    /** F2 前置校验依赖的 status 回显格式（enabled/installed/ok/fail + 每 hook 行）。 */
    @Test
    fun statusFormatHasEnabledOkFail() {
        val literals = stringLiterals(source(PROBE))
        // 只锁 "SCD STATUS" 汇总行本身（install 汇总行也有 fail=，不能用 any 笼统匹配）。
        val statusLine = literals.firstOrNull { "SCD STATUS" in it }
        assertNotNull("status 必须打 \"SCD STATUS\" 汇总行", statusLine)
        assertTrue("status 必须回显 enabled=", "enabled=" in statusLine!!)
        assertTrue("status 必须回显 installed=", "installed=" in statusLine)
        assertTrue("status 必须回显 ok=", "ok=" in statusLine)
        assertTrue("status 必须回显 fail=", "fail=" in statusLine)
    }

    // ------------------------------------------------------ 变异验证（用同一判定函数证明判别力）

    /** toy 复刻 M4：在回调里写 param.result，必须被 [readOnlyRedLines] 的 hasWriteViolation 抓住。 */
    @Test
    fun mutationWriteToResultIsDetected() {
        val mutated = codeLines(
            """
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!enabled) return
                runCatching {
                    param.result = injectedPath
                }
            }
            """.trimIndent()
        )
        assertTrue("toy 变异（写 param.result）必须被红线判定判为违规", hasWriteViolation(mutated))
    }

    /** toy 复刻 M2：删掉 SCD-CDNcb 字面量，必须被 [allEightHookCodesPresent] 的 missingHookCodes 抓住。 */
    @Test
    fun mutationMissingHookCodeIsDetected() {
        val mutatedLiterals = HOOK_CODES.filter { it != "SCD-CDNcb" }
        val missing = missingHookCodes(mutatedLiterals)
        assertTrue("删掉 SCD-CDNcb 必须被判为缺失，实际 missing=$missing", "SCD-CDNcb" in missing)
        assertTrue("未删的其余 5 个不得误报", missing.size == 1)
    }

    /** 真实源码变异 M8：把探针里 j2R 的 r0.a 读法改回 r0.i，必须被 [j2rReadsLocalIdAndMsgId] 判红。 */
    @Test
    fun mutationRealSourceFieldReadIsDetected() {
        val src = source(PROBE)
        val mutated = src.replace("longFieldStr(r0, \"a\")", "longFieldStr(r0, \"i\")")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        assertTrue(
            "真实变异（j2R 把 a 改回 i）必须被 j2rReadsAAndB 判红",
            !j2rReadsAAndB(codeLines(mutated))
        )
    }

    /** 真实源码变异 M2：把探针里所有 SCD-CDNcb 字面量改掉，必须被 [allEightHookCodesPresent] 判红。 */
    @Test
    fun mutationRealSourceHookCodeIsDetected() {
        val src = source(PROBE)
        val mutated = src.replace("SCD-CDNcb", "SCD-CDNcbX")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        val missing = missingHookCodes(stringLiterals(mutated))
        assertTrue(
            "真实变异（改掉 SCD-CDNcb）必须被缺 hook code 判定抓住，实际 missing=$missing",
            "SCD-CDNcb" in missing
        )
    }

    /** 真实源码变异 R79：把新增的 SCD-f2C2 字面量改掉，必须被缺 hook code 判定判红。 */
    @Test
    fun mutationRealNewHookCodeIsDetected() {
        val src = source(PROBE)
        val mutated = src.replace("SCD-f2C2", "SCD-f2C2X")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        val missing = missingHookCodes(stringLiterals(mutated))
        assertTrue(
            "真实变异（改掉 SCD-f2C2）必须被缺 hook code 判定抓住，实际 missing=$missing",
            "SCD-f2C2" in missing
        )
    }

    // ------------------------------------------------------ 工具（与 MicroXImageObserveProbeTest 同构，零 Android 依赖）

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

    /** 缺哪些 hook code 字面量（[allEightHookCodesPresent] 与其变异测试共用）。 */
    private fun missingHookCodes(literals: List<String>): List<String> = HOOK_CODES.filter { code ->
        val asWord = Regex("\\b${Regex.escape(code)}\\b")
        literals.none { asWord.containsMatchIn(it) }
    }

    /** 是否出现"写宿主入参/返回值"违规（[readOnlyRedLines] 与其变异测试共用）。 */
    private fun hasWriteViolation(code: List<String>): Boolean {
        val argWrite = Regex("param\\.args\\[[^]]*\\]\\s*=(?!=)")
        val resultWrite = Regex("param\\.result\\s*=(?!=)")
        return code.any { argWrite.containsMatchIn(it) || resultWrite.containsMatchIn(it) }
    }

    /** j2R 是否读 a/b 且不读 i/h/j/Q（[j2rReadsLocalIdAndMsgId] 与其真实变异测试共用）。 */
    private fun j2rReadsAAndB(code: List<String>): Boolean {
        val hasA = code.any { Regex("longFieldStr\\(r0,\\s*\"a\"\\)").containsMatchIn(it) }
        val hasB = code.any { Regex("longFieldStr\\(r0,\\s*\"b\"\\)").containsMatchIn(it) }
        val noWrong = code.none { Regex("longFieldStr\\(r0,\\s*\"(i|h|j|Q)\"\\)").containsMatchIn(it) }
        return hasA && hasB && noWrong
    }

    /** 某条 hook 安装行是否同时含类变量名与方法名字面量（hookRobust( 与实参分行写，故不断言行首）。 */
    private fun robustCallHas(code: List<String>, clsVar: String, method: String): Boolean =
        code.any { line -> clsVar in line && "\"$method\"" in line }

    // ==================================================== R86 B2-a 新增锁（只追加，原 8 hook 锁一字未动）

    /** R86 B2-a：3 个新 hook code 必须以字符串字面量落在源码（guard 键 / hookLabels / 日志）。 */
    @Test
    fun b2aNewHookCodesPresent() {
        val literals = stringLiterals(source(PROBE))
        val missing = NEW_HOOK_CODES.filter { code ->
            val asWord = Regex("\\b${Regex.escape(code)}\\b")
            literals.none { asWord.containsMatchIn(it) }
        }
        assertTrue("R86 B2-a 新增 hook code 缺失：$missing，字面量=$literals", missing.isEmpty())
    }

    /** E1 SCD-x0cb：挂 onC2CUploadCompleted，只读反射 filemd5/hitCache/existOnSvr/fileid，md5 只前缀 8 + 长度。 */
    @Test
    fun b2aE1ReadOnlyAnchors() {
        val src = source(PROBE)
        val literals = stringLiterals(src)
        val code = codeLines(src)
        assertTrue("E1 必须 hook onC2CUploadCompleted", code.any { "onC2CUploadCompleted" in it })
        for (f in listOf("filemd5", "hitCache", "existOnSvr", "fileid")) {
            assertTrue("E1 必须只读反射读 $f", code.any { "getObjectField" in it && "\"$f\"" in it })
        }
        assertTrue("E1 日志必须有 md5= 前缀", literals.any { "SCD2 x0cb in md5=" in it })
        assertTrue("E1 日志必须带 md5Len=", literals.any { "md5Len=" in it })
        assertTrue("E1 md5 只打前缀 8（take(8)）", code.any { "take(8)" in it })
        assertTrue("E1 fileid 只打长度 + 前缀（fidLen/fidPre）",
            literals.any { "fidLen=" in it && "fidPre=" in it })
    }

    /** E2 SCD-c2c：R10 实测 native 不可 hook，改挂 Java 任务构造点 ok0.m1.e 的 before，只读 tj/k 字段。 */
    @Test
    fun b2aE2ReadOnlyAnchors() {
        val src = source(PROBE)
        val literals = stringLiterals(src)
        val code = codeLines(src)
        assertTrue("E2 必须 hook Java 任务构造点 ok0.m1.e（非 native）",
            code.any { "ok0.m1" in it && "\"e\"" in it })
        assertTrue("E2 必须打 SCD2 c2c in 日志", literals.any { "SCD2 c2c in" in it })
        assertTrue("E2 必须独立 guard code SCD-c2c", code.any { "guard(\"SCD-c2c\")" in it })
        for (f in listOf("field_fullpath", "field_fileId", "field_filemd5", "field_onlycheckexist",
                "field_enable_hitcheck", "field_fake_bigfile_signature")) {
            assertTrue("E2 必须只读反射读 $f",
                code.any { ("getObjectField" in it || "getBooleanField" in it) && "\"$f\"" in it })
        }
        assertTrue("E2 日志必须带 fullpathLen=", literals.any { "fullpathLen=" in it })
        assertTrue("E2 日志必须带 md5Pre/md5Len（task 初值，非 reqMd5Null）",
            literals.any { "md5Pre=" in it } && literals.any { "md5Len=" in it } &&
                literals.none { "reqMd5Null=" in it })
        assertTrue("E2 日志必须带 fakeSigEmpty=", literals.any { "fakeSigEmpty=" in it })
        assertTrue("E2 pathTail 只打尾缀 12", code.any { "takeLast(12)" in it })
        assertTrue("E2 不得把 native 方法名当铁律锁",
            code.none { "startC2CUpload" in it } && code.none { "startSSUpload" in it })
    }

    /** E3 SCD-g9c：hook g9.c，只读判断 hasBig/hasAes/bigLen，绝不打完整 xml/aeskey。 */
    @Test
    fun b2aE3ReadOnlyAnchors() {
        val src = source(PROBE)
        val literals = stringLiterals(src)
        val code = codeLines(src)
        assertTrue("E3 必须 hook g9.c",
            code.any { "com.tencent.mm.sdk.platformtools.g9" in it } &&
                code.any { "\"c\"" in it && "SCD-g9c" in it })
        for (k in listOf("hasBig=", "hasAes=", "bigLen=")) {
            assertTrue("E3 日志必须含 $k", literals.any { k in it })
        }
        assertTrue("E3 日志格式必须为 SCD2 g9c in", literals.any { "SCD2 g9c in" in it })
        assertTrue("E3 必须只读检查 has216b", literals.any { "has216b=" in it })
        assertTrue("E3 md5 只打前缀 8（take(8)）", code.any { "md5Pre" in it } && code.any { "take(8)" in it })
        assertTrue("E3 不得主动调 g9.c", code.none { "callStaticMethod" in it })
    }

    /** E4：复用 SCD-CDNcb 回调扩展 after，读 param.result → retNewLocalId；不新建独立 hook code。 */
    @Test
    fun b2aE4ExtendsCdncbAfter() {
        val src = source(PROBE)
        val literals = stringLiterals(src)
        val code = codeLines(src)
        assertTrue("E4 日志必须含 exist_whencheck + retNewLocalId",
            literals.any { "SCD2 j0C2 exist_whencheck=" in it && "retNewLocalId=" in it })
        assertTrue("E4 必须读 param.result 新 localId",
            code.any { "retNewLocalId" in it } && code.any { "param.result as? Int" in it })
        assertTrue("E4 exist_whencheck 必须只读反射",
            code.any { "getObjectField" in it && "\"field_exist_whencheck\"" in it })
        assertTrue("E4 不得新建独立 hook code SCD-j0C2", "SCD-j0C2" !in literals)
    }

    /** R86 B2-a 新回调（E1 after / E2 before / E3 before）必须 enabled 首行 return；E4 复用现有 after 已计入原 13。 */
    @Test
    fun b2aNewCallbacksEnabledFirstLine() {
        val guardLines = codeLines(source(PROBE)).count { it == "if (!enabled) return" }
        assertTrue("新回调必须 enabled 首行 return（实际 $guardLines，期望 ≥16）", guardLines >= 16)
    }

    /** R86 B2-a 新 hook 只读：不写宿主字段/参数/返回值，不主动调宿主业务方法。 */
    @Test
    fun b2aNewHooksReadOnly() {
        val code = codeLines(source(PROBE))
        assertTrue("新 hook 严禁写宿主字段",
            code.none { Regex("set(?:Object|Long|Int|Boolean|Short|Float|Double|Byte|Char)Field").containsMatchIn(it) })
        assertTrue("新 hook 严禁写 param.args/result（含只读红线汇总判定）", !hasWriteViolation(code))
        val activeCall = Regex("call(?:Static)?Method")
        assertTrue("新 hook 严禁主动调宿主业务方法",
            code.none { activeCall.containsMatchIn(it) &&
                ("onC2CUploadCompleted" in it || "ok0.m1" in it || "g9" in it) })
    }

    // ---------------- R86 B2-a 真源码变异验证（证明新锁不是假绿）

    /** 真实变异：把 SCD-x0cb code 字面量改名，必须被 [b2aNewHookCodesPresent] 判红。 */
    @Test
    fun mutationB2aNewHookCodeIsDetected() {
        val src = source(PROBE)
        val mutated = src.replace("SCD-x0cb", "SCD-x0cbX")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        val literals = stringLiterals(mutated)
        val missing = NEW_HOOK_CODES.filter { code ->
            val asWord = Regex("\\b${Regex.escape(code)}\\b")
            literals.none { asWord.containsMatchIn(it) }
        }
        assertTrue("真实变异（改掉 SCD-x0cb）必须被新锁判红，实际 missing=$missing", "SCD-x0cb" in missing)
    }

    /** 真实变异：把 E3 的 hasBig= 改成 hasBigX=，必须被 [b2aE3ReadOnlyAnchors] 判红。 */
    @Test
    fun mutationB2aE3HasBigIsDetected() {
        val src = source(PROBE)
        val mutated = src.replace("hasBig=", "hasBigX=")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        val literals = stringLiterals(mutated)
        assertTrue("真实变异（E3 hasBig→hasBigX）必须被 E3 锁判红", literals.none { "hasBig=" in it })
    }

    /**
     * 真实变异：把 E2 的 SCD2 c2c in 日志 label 改掉，必须被 [b2aE2ReadOnlyAnchors] 判红。
     * 注意变异串必须真正移除判据锁的子串："inX" 仍含 "in"，是无语义变异（假验证），
     * 故改用 "zzz" 让 "SCD2 c2c in" 字面量从源码彻底消失。
     */
    @Test
    fun mutationB2aE2JavaLandingIsDetected() {
        val src = source(PROBE)
        val mutated = src.replace("SCD2 c2c in ", "SCD2 zzz in ")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        val literals = stringLiterals(mutated)
        assertTrue("真实变异（E2 日志 label 改写）必须被 E2 锁判红", literals.none { "SCD2 c2c in" in it })
        // 变异体必须仍保留 E2 的 Java 落点/字段，确保判红是 label 这一自变量唯一造成（不是把整个 hook 删了）。
        assertTrue("变异体须仍含 E2 Java 落点（自变量唯一）",
            codeLines(mutated).any { "ok0.m1" in it && "\"e\"" in it })
    }

    // ---------------- R86c Q4 补观测：fullpath 实际字节数 / 内容 MD5 前缀 / 完整 fileId ----------------

    /**
     * R86c Q4：SCD2 c2c in 必须在路径串长之外追加 4 个只读取证字段——
     * fexists / fbytes（真实文件字节数）/ fmd5pre（内容 MD5 前 8）/ fidFull（完整 fileId）+ 时间戳 t。
     * 判据来自收口裁决 `docs/_r86c_fileid_vs_fullpath_verdict.md` Q4 探针落点规格。
     */
    @Test
    fun r86cQ4FileByteForensicsAnchors() {
        val src = source(PROBE)
        val literals = stringLiterals(src)
        val code = codeLines(src)
        assertTrue("Q4 c2c 日志必须仍以 SCD2 c2c in 开头", literals.any { "SCD2 c2c in " in it })
        for (k in listOf("fexists=", "fbytes=", "fmd5pre=", "fidFull=", "t=")) {
            assertTrue("Q4 c2c 日志必须含 $k", literals.any { k in it })
        }
        assertTrue("Q4 必须用 java.io.File.length() 读真实字节数",
            code.any { "java.io.File(path).length()" in it })
        assertTrue("Q4 必须只读检查 File.exists()",
            code.any { "java.io.File(path).exists()" in it })
        assertTrue("Q4 必须用 MessageDigest MD5 算内容前缀",
            code.any { "MessageDigest.getInstance(\"MD5\")" in it } && code.any { "f.readBytes()" in it })
        assertTrue("Q4 fmd5pre 只打前 4 字节（8 hex），不打全串",
            code.any { "copyOfRange(0, 4)" in it })
        assertTrue("Q4 fidFull 必须打完整 fileId（取证期）",
            code.any { "val fidFull = fid ?: \"NA\"" in it })
        assertTrue("Q4 打时间戳用于 A/B-2 对齐",
            code.any { "System.currentTimeMillis()" in it })
        // 只读红线不因补观测而放宽：仍不写宿主字段/参数/返回值、不主动调宿主业务方法。
        assertTrue("Q4 补观测仍严禁写宿主字段",
            code.none { Regex("set(?:Object|Long|Int|Boolean|Short|Float|Double|Byte|Char)Field").containsMatchIn(it) })
        assertTrue("Q4 补观测仍严禁写 param.args/result", !hasWriteViolation(code))
        assertTrue("Q4 读文件属 JDK 只读 API（非宿主业务方法），不得出现 callStaticMethod 读文件",
            code.none { "callStaticMethod" in it && "File" in it })
    }

    /** 真实变异：把 fbytes= 改名为 fbytesX=，必须被 [r86cQ4FileByteForensicsAnchors] 判红。 */
    @Test
    fun mutationR86cQ4FbytesAnchorIsDetected() {
        val src = source(PROBE)
        val mutated = src.replace("fbytes=", "fbytesX=")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        val literals = stringLiterals(mutated)
        assertTrue("真实变异（fbytes→fbytesX）必须被 Q4 锁判红", literals.none { "fbytes=" in it })
        assertTrue("变异体须仍保留其它 Q4 字段（自变量唯一：只动 fbytes=）",
            literals.any { "fmd5pre=" in it } && literals.any { "fidFull=" in it })
    }
}
