package com.lx.wxgroup.probe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R86c · B5 干预实验探针回归锁（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 判据来自 `docs/_r86c_b5_fix_note.md` §2 的降级判据：B5 验证「三字段能否启动 checkExist 秒传探测：C2 第二次真实回包 exist false→true 且 md5Len 0→>0」，md5 前缀只记录不裁决身份、不声称命中原图。
 * C2 只读打 `field_exist_whencheck` / `field_filemd5` + 类名/字段名长度，不调 j6、不发消息、不写宿主字段。
 * 本测试只读源码文本（去注释后的代码行 [codeLines]），不实例化探针、不依赖运行时类
 * （故编译期不需要 Xposed/Android 类路径）。
 *
 * 锁的层次：
 * 1. [probeShapeMatchesContract] 锁 object + install/turn/arm/status 签名 + install 不 hook；
 * 2. [taskFieldsCompleteWithB5Fields] 锁 tj/k 字段全集**含 B5 三字段** + fileId 真写入不清空；
 * 3. [b5ThreeFieldsWritten] 锁 field_fullpath / enable_hitcheck / fake_sig 三字段真写入；
 * 4. [fullpathValueDynamicNoHardcode] 锁独立方法 resolveFullpath + 禁止任何固定字节/MD5 指纹常量；
 * 5. [logPrefixAndC2FieldNamesLocked] 前缀 + 字段名 + 日志只打前缀/长度 + C2 shape 只读自检；
 * 6. [listenerMethodsLocked] 锁 Ltj/i 三方法 C2/I/z + Proxy shape；
 * 7. [c2CallbackReadOnly] 锁 C2 只读、不调 j6/send、默认静默（enabled guard）；
 * 8. [noNativeHook] 锁不 hook native 上传入口（startC2CUpload/startSSUpload，R10 死路）；
 * 9. [wiringPresent] 锁 HookEntry install + EntryReceiver b6step + FileSender armFromForward；
 * 10. 真实源码变异（[mutation*]）证明以上锁不是假绿。
 * 11. [idViaSourceValidationPresent] 锁 fileId 来源强制校验（mid/thumb 标注非原图锚点、fail-closed 带 idVia）。
 * 12. [scanBoundedToImage2WithTimeBudget] 锁主路径限 image2/ 子目录 + elapsedRealtime 时间预算 + 超时 fail-closed。
 * 13. [criterionNoCa47f83eIdentity] 锁判据字面不得含 ca47f83e 作原图身份（q4b:84 =【UNKNOWN】）。
 * 14. [noteMd5ClaimConsistentWithSource] 锁文本一致性：源码无内容摘要计算时 note 不得声称内容 MD5 准入。
 * 15. [wiringPresent] 增正/反断言锁 armFromForward 新调用串、禁 ctx.sourceChat/contextChatTalker 回归；
 *     [dualTalkerWiringContract] 锁双 talker 拆分（field_talker=taskTalker、签名含 taskTalker/msgId、Q2 按 msgId 选参）。
 *
 * ## 真实源码变异记录（每做一次变异跑一次相关单测确认判红，恢复后绿）
 *
 * - M1 删 field_fullpath 写入 ⇒ [mutationDeleteFullpathFieldIsDetected] 判红。
 * - M2 删 field_enable_hitcheck 写入 ⇒ [mutationDeleteEnableHitFieldIsDetected] 判红。
 * - M3 删 field_fake_bigfile_signature 写入 ⇒ [mutationDeleteFakeSigFieldIsDetected] 判红。
 * - M4 清空 field_fileId（写空串）⇒ [mutationClearFileIdIsDetected] 判红。
 * - M5 C2 改成调 j6 ⇒ [mutationC2CallsJ6IsDetected] 判红。
 * - M6 改挂 native 入口（findAndHookMethod startC2CUpload）⇒ [mutationNativeHookIsDetected] 判红。
 * - M7 TASK_FIELDS 漏 field_fullpath ⇒ [mutationTaskFieldDroppedIsDetected] 判红。
 * - M8 写死原图指纹常量（REF_MD5_PRE=cff4f5a5）⇒ [mutationHardcodedFingerprintIsDetected] 判红。
 * - M9 Proxy 只处理两个方法（删 z 分支）⇒ [mutationProxyTwoMethodsIsDetected] 判红。
 * - M10 读错 C2 字段名 ⇒ [mutationWrongC2FieldNameIsDetected] 判红。
 * - M11 删 map md5 键 ⇒ [mutationMapKeyRemovedIsDetected] 判红。
 * - M12 删 mid/thumb「非原图锚点」WARN ⇒ [mutationIdViaMidWarnRemovedIsDetected] 判红。
 * - M13 删 fail-closed 日志的 idVia 校验 ⇒ [mutationIdViaFailClosedRemovedIsDetected] 判红。
 * - M14 取消 image2/ 子目录限制 ⇒ [mutationImage2ScopeRemovedIsDetected] 判红。
 * - M15 取消 elapsedRealtime 时间预算 ⇒ [mutationTimeBudgetRemovedIsDetected] 判红。
 * - M16 注入 ca47f83e 指纹常量 ⇒ [mutationCa47f83eInjectedIsDetected] 判红。
 * - M17 注入「内容 MD5 准入」声称 ⇒ [mutationMd5ClaimInjectedIsDetected] 判红。
 * - M18 FileSender 把新 armFromForward 调用串改回 ctx.sourceChat ⇒ [mutationSourceChatTalkerRegressionIsDetected] 判红。
 * - M19 arm 把 put(field_talker, taskTalker) 改回 talker ⇒ [mutationFieldTalkerFallbackToSourceIsDetected] 判红。
 *
 * 每轮只改一处、跑对应测试确认红，再改回跑确认绿；最终全量绿。
 */
class B6CheckExistProbeTest {

    companion object {
        private const val PROBE = "app/src/main/java/com/lx/wxgroup/probe/B6CheckExistProbe.kt"
        private const val HOOK_ENTRY = "app/src/main/java/com/lx/wxgroup/HookEntry.kt"
        private const val ENTRY_RECEIVER = "app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt"
        private const val FILE_SENDER = "app/src/main/java/com/lx/wxgroup/core/FileSender.kt"

        /** 修正 lane 产物 note（工作根 docs/，非 wxgroup/docs/）：文本一致性锁读取它。 */
        private const val FIX_NOTE = "docs/_r86c_b5_fix_note.md"

        /** dex 实证 tj/k 写入字段全集 + B5 补三字段，顺序无关。 */
        private val EXPECTED_TASK_FIELDS = setOf(
            "d", "f", "field_mediaId", "field_fileId", "field_aesKey", "field_filemd5",
            "field_talker", "field_fileType", "field_priority", "field_svr_signature",
            "field_onlycheckexist", "field_trysafecdn",
            "field_fullpath", "field_enable_hitcheck", "field_fake_bigfile_signature"
        )

        /** generalize 真机证伪的固定指纹：禁止作为常量进代码（只作 note 历史样本佐证）。 */
        private val FORBIDDEN_FINGERPRINTS = listOf("45522", "148929", "cff4f5a5", "484bc088", "0ef205c1")
    }

    /** 契约：object 单例 + 四个函数签名必须逐字存在；install 不 hook 任何方法。 */
    @Test
    fun probeShapeMatchesContract() {
        val code = codeLines(source(PROBE))
        assertTrue("探针必须是 object 单例", code.any { "object B6CheckExistProbe" in it })
        assertTrue("install 签名须逐字符合契约", code.any { "fun install(lpparam: XC_LoadPackage.LoadPackageParam)" in it })
        assertTrue("turn 签名须逐字符合契约", code.any { "fun turn(on: Boolean)" in it })
        assertTrue(
            "arm 签名须逐字符合契约（双 talker + msgId 默认参）",
            code.any { "fun arm(talker: String, msgSvrId: Long, taskTalker: String = talker, msgId: Long = 0L)" in it }
        )
        assertTrue("status 签名须逐字符合契约", code.any { "fun status(): List<String>" in it })
        assertTrue("install 必须幂等（installed guard）", code.any { "if (installed) return" in it })
        assertTrue(
            "install 不得 hook 任何方法（只用 findCls 找类）",
            code.none { "hookMethod" in it || "hookAllMethods" in it || "findAndHookMethod" in it }
        )
    }

    /** tj/k 字段全集完整（含 B5 三字段）、fileId 真写入不清空。 */
    @Test
    fun taskFieldsCompleteWithB5Fields() {
        val src = source(PROBE)
        assertTrue("tj/k 字段集合必须恰好是 B5 全集（含 field_fullpath 等三字段）", taskFieldsValid(src))
        assertTrue("必须真实写入 field_fileId", fileIdWriteValid(src))
        val code = codeLines(src)
        assertTrue(
            "绝不能用任何 setter 写 field_fileId 之外的方式清空 fileId",
            code.none { "setObjectField" in it && "field_fileId" in it && "\"\"" in it }
        )
    }

    /** B5 三字段真写入（独立方法给值）。 */
    @Test
    fun b5ThreeFieldsWritten() {
        val src = source(PROBE)
        assertTrue("B5 三字段必须全部写入", b5FieldsValid(src))
        assertTrue("field_fullpath 必须由独立方法 resolveFullpath 给值", codeLines(src).any { "resolveFullpath(u, map)" in it })
        assertTrue("resolveFullpath 必须是独立方法（便于单测/替换）", codeLines(src).any { "private fun resolveFullpath(" in it })
    }

    /** 取值动态化：禁用任何固定字节/MD5 指纹常量（generalize 真机证伪）。 */
    @Test
    fun fullpathValueDynamicNoHardcode() {
        val src = source(PROBE)
        assertTrue("禁止写死任何固定字节/MD5 指纹常量", noHardcodedFingerprint(src))
        val code = codeLines(src)
        assertTrue("主路径必须走数据根正则动态定位", code.any { "RE_DATA_ROOT" in it && "find" in it })
        assertTrue("必须有唯一候选命中数判断（==1）", code.any { "hits.size == 1" in it })
        assertTrue("必须有安全回退小占位", code.any { "createTempFile" in it })
        assertTrue("主路径/回退全失败必须 fail-closed", code.any { "no_fullpath (fail-closed)" in it })
    }

    /** 前缀/字段名常量 + 日志只打长度/前缀 + C2 shape 只读自检。 */
    @Test
    fun logPrefixAndC2FieldNamesLocked() {
        val src = source(PROBE)
        val literals = stringLiterals(src)
        assertTrue("C2 字段名拼写必须正确（防读错字段白跑）", c2FieldsValid(src))
        assertTrue(
            "C2 日志须为 B6X C2 exist=? md5Pre=? md5Len=?",
            literals.any { it.startsWith("B6X C2 exist=") && "md5Pre=" in it && "md5Len=" in it }
        )
        assertTrue(
            "arm sent 日志须带 fidLen/aesLen/md5Len/existHook（只打长度/布尔）",
            literals.any {
                it.startsWith("B6X arm sent ") && "fidLen=" in it && "aesLen=" in it &&
                    "md5Len=" in it && "existHook=" in it
            }
        )
        assertTrue(
            "status 日志格式须为 B6X STATUS enabled/installed/classes",
            literals.any {
                it.startsWith("B6X STATUS ") && "enabled=" in it && "installed=" in it && "classes=" in it
            }
        )
        assertTrue("md5 只打前缀 8（take(8)）", codeLines(src).any { "md5?.take(8)" in it })
        assertTrue(
            "fileId/aesKey/md5 不得整串进日志（只打长度/前缀）",
            codeLines(src).none {
                it.startsWith("LogX") && Regex("\\$\\{(fileId|aesKey|xmlMd5)\\}").containsMatchIn(it)
            }
        )
        // b6step【基本排除】→【完全排除】：C2 必须打 args[3] 类名 + Ltj/f 全部 String 字段名/长度。
        val h = handlerRegion(src)
        assertTrue("C2 必须调 logC2Shape 只读自检", "\"C2\" ->" in h && "logC2Shape(f)" in h)
        assertTrue("C2 shape 日志必须含类名 + String 字段名/长度", literals.any {
            it.startsWith("B6X C2 shape ") && "cls=" in it && "strFields=" in it
        })
        assertTrue("logC2Shape 必须只字段名/长度不打值（declaredFields + length）",
            codeLines(src).any { "declaredFields" in it } && codeLines(src).any { "?.length ?: -1" in it })
    }

    /** Ltj/i 三方法 C2/I/z + handler 三分支齐全（Proxy 形状锁）。 */
    @Test
    fun listenerMethodsLocked() {
        val src = source(PROBE)
        assertTrue("Ltj/i 接口方法清单必须为 C2/I/z", listenerMethodsValid(src))
        assertTrue("listener 类名常量必须为 tj.i（Ltj/i; 的 Java 名，非 ltj.i）", "LISTENER_CLS = \"tj.i\"" in src)
        assertTrue("禁止错误类名 ltj.i 回归", "\"ltj.i\"" !in codeLines(src))
        val h = handlerRegion(src)
        assertTrue("handler 必须处理 C2", "\"C2\" ->" in h)
        assertTrue("handler 必须处理 I（返回默认值）", "\"I\" ->" in h)
        assertTrue("handler 必须处理 z（返回默认值）", "\"z\" ->" in h)
        assertTrue(
            "C2 返回 int（0），I/z 返回 null，不打日志",
            "\"I\" -> null" in h && "\"z\" -> null" in h && "return@InvocationHandler" in h
        )
    }

    /** C2 只读红线 + 默认静默 + 下发链仍在 arm（不混进 C2）。 */
    @Test
    fun c2CallbackReadOnly() {
        val src = source(PROBE)
        val h = handlerRegion(src)
        assertTrue(
            "C2 回调只准反射读字段打日志：不得写宿主字段、不得主动调宿主方法",
            "setObjectField" !in h && "setIntField" !in h && "setBooleanField" !in h &&
                "callMethod" !in h && "callStaticMethod" !in h
        )
        assertTrue("C2 回调绝不调 j6 / send / FileSender / 改 m.a", "j6" !in h && "send" !in h && "FileSender" !in h && "m.a" !in h)
        assertTrue("C2 回调首行必须 enabled guard（默认静默、零反射）", "if (!enabled) return@InvocationHandler" in h)
        val code = codeLines(src)
        assertTrue("探针代码里不得出现 j6 调用（禁用功能）", code.none { "j6" in it })
        assertTrue("探针代码里不得出现 FileSender", code.none { "FileSender" in it })
        assertTrue(
            "arm 必须 enabled=false 首行 return（零动作）",
            code.any { "if (!enabled)" in it } &&
                code.any { "B6X arm skip reason=disabled" in it } &&
                code.any { "return@runCatching" in it }
        )
        assertTrue("下发 B8 必须在 dispatch 内", code.any { "\"B8\"" in it && "callMethod" in it })
        assertTrue("兜底 m1.e 必须在 dispatch 内", code.any { "\"e\"" in it && "callMethod" in it })
    }

    /** 不 hook native 上传入口（startC2CUpload/startSSUpload acc=0x109，R10 死路）。 */
    @Test
    fun noNativeHook() {
        assertTrue("探针不得 hook native 上传入口", noNativeHookValid(source(PROBE)))
    }

    /** 接线：HookEntry install + EntryReceiver 的 op=b6step 广播入口 + FileSender armFromForward。 */
    @Test
    fun wiringPresent() {
        val hookEntry = codeLines(source(HOOK_ENTRY))
        val receiver = codeLines(source(ENTRY_RECEIVER))
        assertTrue("HookEntry 必须 runCatching 安装 B6CheckExistProbe", hookEntry.any { "B6CheckExistProbe.install(lpparam)" in it })
        assertTrue("EntryReceiver 必须有 \"b6step\" -> 分支", receiver.any { "\"b6step\" ->" in it })
        assertTrue("b6step on 必须调 B6CheckExistProbe.turn(", receiver.any { "B6CheckExistProbe.turn(" in it })
        assertTrue("b6step on 必须调 B6CheckExistProbe.arm(", receiver.any { "B6CheckExistProbe.arm(" in it })
        assertTrue("b6step stat 必须回显 status()", receiver.any { "B6CheckExistProbe.status()" in it })
        assertTrue(
            "b6step 必须读 talker/svrid 两个 extra",
            receiver.any { "getStringExtra(\"talker\")" in it } && receiver.any { "firstLong(intent, \"svrid\")" in it }
        )
        val sender = source(FILE_SENDER)
        assertTrue(
            "FileSender 必须以源 talker + 目标 wxid + msgId 调 armFromForward（禁 ctx.sourceChat 回归）",
            fileSenderArmWiringValid(sender)
        )
    }

    /** 双 talker 拆分契约：field_talker 用目标 wxid、签名含 taskTalker/msgId、Q2 按 msgId 选参（A2/A3/A4）。 */
    @Test
    fun dualTalkerWiringContract() {
        assertTrue("源 talker / 目标 wxid 拆分接线必须落码", dualTalkerWiringValid(source(PROBE)))
    }

    /** fileId 来源强制校验：mid/thumb 标注「非原图锚点」，fail-closed 日志带 idVia（BLOCKER-2 处置）。 */
    @Test
    fun idViaSourceValidationPresent() {
        val src = source(PROBE)
        assertTrue("fileId 来源校验必须落码（mid/thumb 非原图锚点 + fail-closed 带 idVia）", idViaSourceValid(src))
    }

    /** 主路径性能：限定 image2/ 子目录 + elapsedRealtime 时间预算 + 超时 fail-closed。 */
    @Test
    fun scanBoundedToImage2WithTimeBudget() {
        val src = source(PROBE)
        assertTrue("主路径须限 image2/ + 时间预算 + 超时放弃", scanBoundedValid(src))
    }

    /** 判据字面不得含 ca47f83e 作原图身份（q4b:84 =【UNKNOWN】）。 */
    @Test
    fun criterionNoCa47f83eIdentity() {
        assertTrue("源码判据字面不得含 ca47f83e 作原图身份", ca47f83eExcludedValid(source(PROBE)))
        val note = source(FIX_NOTE)
        assertTrue("fix note 必须声明 ca47f83e 非原图身份锚点", "ca47f83e" in note && "非原图身份" in note)
    }

    /** 文本一致性：源码无内容摘要计算时，note 不得声称「内容 MD5 准入」（撤回原 note §1.2/§1.3 虚假表述）。 */
    @Test
    fun noteMd5ClaimConsistentWithSource() {
        val src = source(PROBE)
        assertTrue("源码与 note 的 MD5 准入声称须一致", md5ClaimConsistent(src))
        if (!sourceComputesDigest(src)) {
            assertTrue(
                "源码无内容摘要计算时，fix note 必须含撤回标记 MD5_CLAIM_RETRACTED",
                "MD5_CLAIM_RETRACTED" in source(FIX_NOTE)
            )
        }
    }

    // ------------------------------------------------------ 真实源码变异验证（证明锁不是假绿）

    /** M1：删 field_fullpath 写入，必须判红。 */
    @Test
    fun mutationDeleteFullpathFieldIsDetected() {
        val mutated = source(PROBE).replace("put(task, \"field_fullpath\", fullpath)", "")
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("删 field_fullpath 写入必须判红", b5FieldsValid(mutated))
    }

    /** M2：删 field_enable_hitcheck 写入，必须判红。 */
    @Test
    fun mutationDeleteEnableHitFieldIsDetected() {
        val mutated = source(PROBE).replace("put(task, \"field_enable_hitcheck\", true)", "")
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("删 field_enable_hitcheck 写入必须判红", b5FieldsValid(mutated))
    }

    /** M3：删 field_fake_bigfile_signature 写入，必须判红。 */
    @Test
    fun mutationDeleteFakeSigFieldIsDetected() {
        val mutated = source(PROBE).replace("put(task, \"field_fake_bigfile_signature\", \"\")", "")
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("删 field_fake_bigfile_signature 写入必须判红", b5FieldsValid(mutated))
    }

    /** M4：清空 field_fileId（写空串），必须判红。 */
    @Test
    fun mutationClearFileIdIsDetected() {
        val mutated = source(PROBE).replace("put(task, \"field_fileId\", fileId)", "put(task, \"field_fileId\", \"\")")
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("清空 field_fileId 必须判红", fileIdWriteValid(mutated))
    }

    /** M5：C2 改成调 j6，必须判红。 */
    @Test
    fun mutationC2CallsJ6IsDetected() {
        val h = handlerRegion(source(PROBE))
        assertTrue("变异 needle 必须在 handler 内", "logC2Shape(f)" in h)
        val mutated = source(PROBE).replace("logC2Shape(f)", "logC2Shape(f); XposedHelpers.callMethod(f, \"j6\")")
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("C2 改成调 j6 必须判红", c2ReadOnlyValid(mutated))
    }

    /** M6：改挂 native 入口，必须判红。 */
    @Test
    fun mutationNativeHookIsDetected() {
        val mutated = source(PROBE).replace(
            "fun install(lpparam: XC_LoadPackage.LoadPackageParam) {",
            "fun install(lpparam: XC_LoadPackage.LoadPackageParam) { XposedHelpers.findAndHookMethod(\"startC2CUpload\", lpparam.classLoader, \"x\")"
        )
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("改挂 native 入口必须判红", noNativeHookValid(mutated))
    }

    /** M7：TASK_FIELDS 漏 field_fullpath，必须判红。 */
    @Test
    fun mutationTaskFieldDroppedIsDetected() {
        val mutated = source(PROBE).replace("\"field_fullpath\", \"field_enable_hitcheck\"", "\"field_enable_hitcheck\"")
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("TASK_FIELDS 漏 field_fullpath 必须判红", taskFieldsValid(mutated))
    }

    /** M8：写死原图指纹常量，必须判红。 */
    @Test
    fun mutationHardcodedFingerprintIsDetected() {
        val mutated = source(PROBE).replace(
            "private const val MAX_SCAN_FILES = 1024",
            "private const val MAX_SCAN_FILES = 1024\n    private const val REF_MD5_PRE = \"cff4f5a5\""
        )
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("写死原图指纹常量必须判红", noHardcodedFingerprint(mutated))
    }

    /** M9：Proxy 只处理两个方法（删 z 分支），必须判红。 */
    @Test
    fun mutationProxyTwoMethodsIsDetected() {
        val mutated = source(PROBE).replace("\"z\" -> null", "\"Z\" -> null")
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("Proxy 只处理两个方法必须判红", listenerMethodsValid(mutated))
    }

    /** M10：读错字段名（exist_whencheck），必须判红。 */
    @Test
    fun mutationWrongC2FieldNameIsDetected() {
        val mutated = source(PROBE).replace("\"field_exist_whencheck\"", "\"field_exist_whenchek\"")
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("读错字段名必须判红", c2FieldsValid(mutated))
    }

    /** M11：删掉 map 的 md5 键，必须判红。 */
    @Test
    fun mutationMapKeyRemovedIsDetected() {
        val needle = "\".msg.img.\\\$md5\""
        assertTrue("变异 needle 必须能在源码里找到", needle in source(PROBE))
        val mutated = source(PROBE).replace(", $needle", "")
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("删 map md5 键必须判红", mapKeysValid(mutated))
    }

    /** M12：删 mid/thumb「非原图锚点」WARN，必须判红。 */
    @Test
    fun mutationIdViaMidWarnRemovedIsDetected() {
        val mutated = source(PROBE).replace("非原图锚点（不当 big 用）", "（旧稿身份锚点）")
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("删 mid/thumb 非原图锚点 WARN 必须判红", idViaSourceValid(mutated))
    }

    /** M13：删 fail-closed 日志的 idVia 校验，必须判红。 */
    @Test
    fun mutationIdViaFailClosedRemovedIsDetected() {
        val needle = "no_fullpath (fail-closed) idVia=\$idVia"
        assertTrue("变异 needle 必须能在源码里找到", needle in source(PROBE))
        val mutated = source(PROBE).replace(needle, "no_fullpath (fail-closed)")
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("删 fail-closed 的 idVia 校验必须判红", idViaSourceValid(mutated))
    }

    /** M14：取消 image2/ 子目录限制（回退到全数据根扫描），必须判红。 */
    @Test
    fun mutationImage2ScopeRemovedIsDetected() {
        val needle = "File(root, \"image2\")"
        assertTrue("变异 needle 必须能在源码里找到", needle in source(PROBE))
        val mutated = source(PROBE).replace(needle, "File(root)")
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("取消 image2/ 限制必须判红", scanBoundedValid(mutated))
    }

    /** M15：取消 elapsedRealtime 时间预算，必须判红。 */
    @Test
    fun mutationTimeBudgetRemovedIsDetected() {
        val needle = "SystemClock.elapsedRealtime()"
        assertTrue("变异 needle 必须能在源码里找到", needle in source(PROBE))
        val mutated = source(PROBE).replace(needle, "System.currentTimeMillis()")
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("取消时间预算必须判红", scanBoundedValid(mutated))
    }

    /** M16：注入 ca47f83e 指纹常量（当原图身份），必须判红。 */
    @Test
    fun mutationCa47f83eInjectedIsDetected() {
        val needle = "private const val SCAN_TIME_BUDGET_MS = 250L"
        assertTrue("变异 needle 必须能在源码里找到", needle in source(PROBE))
        val mutated = source(PROBE).replace(
            needle,
            "$needle\n    private const val REF_MD5_PRE = \"ca47f83e\""
        )
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("注入 ca47f83e 指纹必须判红", ca47f83eExcludedValid(mutated))
    }

    /** M17：源码注入「内容 MD5 准入」声称（而源码并不计算内容摘要），必须判红。 */
    @Test
    fun mutationMd5ClaimInjectedIsDetected() {
        val needle = "private const val SCAN_TIME_BUDGET_MS = 250L"
        assertTrue("变异 needle 必须能在源码里找到", needle in source(PROBE))
        val mutated = source(PROBE).replace(
            needle,
            "$needle\n    private const val MD5_CLAIM = \"内容 MD5 准入\""
        )
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("注入内容 MD5 准入声称必须判红", md5ClaimConsistent(mutated))
    }

    /** M18：FileSender 把新 armFromForward 调用串改回 ctx.sourceChat，必须判红。 */
    @Test
    fun mutationSourceChatTalkerRegressionIsDetected() {
        val needle = "B6CheckExistProbe.armFromForward(ctx.srcTalkerPrefix.trimEnd(':'), ctx.msgSvrId, wxid, ctx.srcMsgId)"
        assertTrue("变异 needle 必须在 FileSender 里找到", needle in source(FILE_SENDER))
        val mutated = source(FILE_SENDER).replace(
            needle,
            "B6CheckExistProbe.armFromForward(ctx.sourceChat, ctx.msgSvrId)"
        )
        assertTrue("变异必须真正改动源码文本", mutated != source(FILE_SENDER))
        assertFalse("把源 talker 改回 ctx.sourceChat 必须判红", fileSenderArmWiringValid(mutated))
    }

    /** M19：arm 把 field_talker 改回源 talker，必须判红。 */
    @Test
    fun mutationFieldTalkerFallbackToSourceIsDetected() {
        val needle = "put(task, \"field_talker\", taskTalker)"
        assertTrue("变异 needle 必须在探针源码里找到", needle in source(PROBE))
        val mutated = source(PROBE).replace(needle, "put(task, \"field_talker\", talker)")
        assertTrue("变异必须真正改动源码文本", mutated != source(PROBE))
        assertFalse("field_talker 回退成源 talker 必须判红", dualTalkerWiringValid(mutated))
    }

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
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: throw AssertionError("找不到源码：$rel（工作目录=${System.getProperty("user.dir")}）")
    }

    /** 只留代码行（去注释/文档行），避免 KDoc 里引用的字符串造成假绿。 */
    private fun codeLines(src: String): List<String> = src.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        .toList()

    /** 取代码行中的全部字符串字面量内容（去掉外层引号）。 */
    private fun stringLiterals(src: String): List<String> {
        val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        return codeLines(src).flatMap { line -> literal.findAll(line).map { it.groupValues[1] }.toList() }
    }

    /** 抽取 `private val <name> = listOf(...)` 里的字符串字面量。 */
    private fun listLiteral(src: String, name: String): List<String> {
        val m = Regex("$name\\s*=\\s*listOf\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL).find(src)
            ?: throw AssertionError("找不到 $name 的 listOf 定义")
        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    /** InvocationHandler lambda 到 newProxyInstance 之间的源码（C2 回调体）。 */
    private fun handlerRegion(src: String): String {
        val s = src.indexOf("InvocationHandler {")
        if (s < 0) throw AssertionError("找不到 InvocationHandler lambda")
        val e = src.indexOf("newProxyInstance", s)
        if (e < 0) throw AssertionError("找不到 newProxyInstance")
        return src.substring(s, e)
    }

    private fun c2FieldsValid(src: String): Boolean =
        listLiteral(src, "C2_READ_FIELDS").toSet() == setOf("field_exist_whencheck", "field_filemd5")

    private fun taskFieldsValid(src: String): Boolean = listLiteral(src, "TASK_FIELDS").toSet() == EXPECTED_TASK_FIELDS

    private fun fileIdWriteValid(src: String): Boolean = codeLines(src).any {
        "put(task, \"field_fileId\", fileId)" in it && "put(task, \"field_fileId\", \"\")" !in it
    }

    /** FileSender 接线：新调用串存在，且 ctx.sourceChat/contextChatTalker 不得再作 armFromForward 的 talker 入参。 */
    private fun fileSenderArmWiringValid(src: String): Boolean {
        val code = codeLines(src)
        val newCall = "B6CheckExistProbe.armFromForward(ctx.srcTalkerPrefix.trimEnd(':'), ctx.msgSvrId, wxid, ctx.srcMsgId)"
        val badTalker = Regex("B6CheckExistProbe\\.armFromForward\\((ctx\\.sourceChat|contextChatTalker)")
        return code.any { newCall in it } && code.none { badTalker.containsMatchIn(it) }
    }

    /** 双 talker 拆分（A2/A3/A4）：field_talker=taskTalker + 签名默认参 + 函数体转发 + Q2 按 msgId 选参。 */
    private fun dualTalkerWiringValid(src: String): Boolean {
        val code = codeLines(src).joinToString("\n")
        val sigForward = "fun armFromForward(talker: String, msgSvrId: Long, taskTalker: String = talker, msgId: Long = 0L)"
        val sigArm = "fun arm(talker: String, msgSvrId: Long, taskTalker: String = talker, msgId: Long = 0L)"
        return "put(task, \"field_talker\", taskTalker)" in code &&
            sigForward in code && sigArm in code &&
            "arm(talker, msgSvrId, taskTalker, msgId)" in code &&
            "if (m == \"a3\") msgSvrId else (if (msgId > 0L) msgId else msgSvrId)" in code
    }

    private fun b5FieldsValid(src: String): Boolean {
        val code = codeLines(src)
        return code.any { "put(task, \"field_fullpath\", fullpath)" in it } &&
            code.any { "put(task, \"field_enable_hitcheck\", true)" in it } &&
            code.any { "put(task, \"field_fake_bigfile_signature\", \"\")" in it }
    }

    private fun noHardcodedFingerprint(src: String): Boolean {
        val code = codeLines(src).joinToString("\n")
        return FORBIDDEN_FINGERPRINTS.none { it in code }
    }

    private fun noNativeHookValid(src: String): Boolean {
        val code = codeLines(src).joinToString("\n")
        return "startC2CUpload" !in code && "startSSUpload" !in code &&
            "findAndHookMethod" !in code && "hookAllMethods" !in code
    }

    private fun c2ReadOnlyValid(src: String): Boolean {
        val h = handlerRegion(src)
        return "setObjectField" !in h && "setIntField" !in h && "setBooleanField" !in h &&
            "callMethod" !in h && "callStaticMethod" !in h &&
            "j6" !in h && "send" !in h && "FileSender" !in h && "m.a" !in h &&
            "if (!enabled) return@InvocationHandler" in h
    }

    private fun mapKeysValid(src: String): Boolean {
        val keys = listLiteral(src, "MAP_KEYS")
        return listOf("cdnbigimgurl", "aeskey", "md5").all { suffix -> keys.any { suffix in it } }
    }

    private fun listenerMethodsValid(src: String): Boolean {
        if (listLiteral(src, "LISTENER_METHODS") != listOf("C2", "I", "z")) return false
        val h = handlerRegion(src)
        return listOf("\"C2\" ->", "\"I\" ->", "\"z\" ->").all { it in h }
    }

    /** 源码是否真做内容摘要计算（MessageDigest / .digest(），只扫代码行不扫 KDoc。 */
    private fun sourceComputesDigest(src: String): Boolean =
        Regex("MessageDigest|\\.digest\\(").containsMatchIn(codeLines(src).joinToString("\n"))

    /** fileId 来源强制校验：big/map 之外的降级锚点必须标注「非原图锚点」，fail-closed 日志必须带 idVia。 */
    private fun idViaSourceValid(src: String): Boolean {
        val code = codeLines(src).joinToString("\n")
        return "idVia != \"map\" && idVia != \"regex:big\"" in code &&
            "非原图锚点" in code &&
            "no_fullpath (fail-closed) idVia=\$idVia" in code
    }

    /** 主路径扫描边界：限 image2/ 子目录 + elapsedRealtime 预算 + 超时 fail-closed。 */
    private fun scanBoundedValid(src: String): Boolean {
        val code = codeLines(src).joinToString("\n")
        return "File(root, \"image2\")" in code &&
            "SystemClock.elapsedRealtime()" in code &&
            "!timedOut && hits.size == 1" in code
    }

    /** 判据字面不得出现 ca47f83e（含 KDoc），q4b:84 =【UNKNOWN】，不得当原图身份。 */
    private fun ca47f83eExcludedValid(src: String): Boolean = "ca47f83e" !in src

    /** 文本一致性：源码没算内容摘要就不能声称「内容 MD5 准入」。 */
    private fun md5ClaimConsistent(src: String): Boolean {
        val code = codeLines(src).joinToString("\n")
        val claimsAdmission = "内容 MD5 准入" in code || "内容摘要准入" in code
        return sourceComputesDigest(src) || !claimsAdmission
    }
}
