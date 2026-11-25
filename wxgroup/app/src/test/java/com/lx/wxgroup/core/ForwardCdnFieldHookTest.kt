package com.lx.wxgroup.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R86c 第4轮最终规格回归锁（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 只读源码字符串，不实例化、不依赖运行时类。所有断言读**去注释后的代码行**（[codeLines]），
 * 避免 KDoc 里引用的死字符串造成假绿。
 *
 * 锁的层次：
 * 1. 契约：object 单例 + install/arm/markTarget/turn/status/clear(wxid)
 * 2. hook 点：唯一 m1.e before（不新增 j2.R hook），before 首行开关闸门
 * 3. 反 M1：取根来自 task.field_fullpath，不依赖 r0 String 字段含 /MicroMsg/
 * 4. 判据：REF_MD5_PRE=cff4f5a5、禁 xml .msg.img.$md5、0ef205c1 只作门禁、命中数==1
 * 5. 隔离：field_talker keyed + ConcurrentHashMap + 条件 remove，无 ThreadLocal/finally remove
 * 6. FileSender：markTarget(wxid) 在 arm 前、clear(wxid) 覆盖两条失败路径、不用 sourceChat 当 key
 * 7. 五项真实文本变异各自判红（判定函数与主测共用，自变量唯一）
 */
class ForwardCdnFieldHookTest {

    companion object {
        private const val HOOK = "app/src/main/java/com/lx/wxgroup/core/ForwardCdnFieldHook.kt"
        private const val FILE_SENDER = "app/src/main/java/com/lx/wxgroup/core/FileSender.kt"
        private const val HOOK_ENTRY = "app/src/main/java/com/lx/wxgroup/HookEntry.kt"
        private const val KEYS = "app/src/main/java/com/lx/wxgroup/data/Keys.kt"
        private const val ENTRY_RECEIVER = "app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt"
    }

    // ------------------------------------------------------ 1. 契约

    @Test
    fun objectAndApiContractPresent() {
        val code = codeLines(source(HOOK))
        assertTrue("必须是 object 单例", code.any { "object ForwardCdnFieldHook" in it })
        assertTrue("install 签名", code.any { "fun install(lpparam: XC_LoadPackage.LoadPackageParam)" in it })
        assertTrue("arm 签名保留供 FileSender/探针用",
            code.any { "fun arm(talker: String, msgSvrId: Long, imgLocalId: Long)" in it })
        assertTrue("新增 markTarget(wxid)", code.any { "fun markTarget(wxid: String)" in it })
        assertTrue("新增 turn(on)", code.any { "fun turn(on: Boolean)" in it })
        assertTrue("新增 status()", code.any { "fun status():" in it })
        assertTrue("clear 改为 keyed 签名", code.any { "fun clear(wxid: String)" in it })
        assertTrue("旧无参 clear 必须删除", code.none { it == "fun clear()" })
    }

    // ------------------------------------------------------ 2. hook 点

    /** 唯一 hook 点 = 复用已挂载的 ok0/m1.e(tj/k)Z before；不新增第二个 hook。 */
    @Test
    fun hookPointIsM1eBeforeOnly() {
        val code = codeLines(source(HOOK))
        assertTrue("必须 hook m1.e before", hookIsM1e(code))
        assertTrue("不得新增 j2.R hook（唯一 hook 点）",
            code.none { "findClass(\"com.tencent.mm.modelimage.j2\"" in it })
        assertTrue("before 首行必须是独立开关闸门", code.any { "if (!hevcEnabled) return" in it })
        assertTrue("after 不得写任何东西", code.none { "afterHookedMethod" in it })
        assertTrue("只写 task 一个字段（field_fullpath）", setFields(code).size == 1)
        assertTrue("唯一写字段必须是 field_fullpath",
            setFields(code).all { "field_fullpath" in it })
        assertTrue("不写 fileId/aesKey/originPath",
            code.none { "field_fileId" in it } && code.none { "field_aesKey" in it } &&
                code.none { "originPath" in it })
    }

    // ------------------------------------------------------ 3. 反 M1（裁决 §3.6 第1条）

    /** 新实现不得依赖 r0 String 字段含 /MicroMsg/ 定位；取根来自 task.field_fullpath。 */
    @Test
    fun noR0StringMicroMsgLocate() {
        val code = codeLines(source(HOOK))
        assertTrue("不得对 r0/ImgInfo2 String 字段 contains(\"/MicroMsg/\") 定位（直接反 M1）",
            code.none { "contains(\"/MicroMsg/\")" in it })
        assertTrue("取根须来自 field_fullpath 正则",
            code.any { "Regex(\"/MicroMsg/[0-9a-fA-F]{32}/\").find(fullpath)" in it })
        assertTrue("fullpath 须从 task（param.args[0]）读，不是 j2.R 的 param.result",
            code.any { "getObjectField(task, \"field_fullpath\")" in it } &&
                code.none { "param.result" in it })
        assertTrue("不得写 r0 字段",
            code.none { Regex("set(?:Object|Long|Int|Boolean)Field\\(\\s*r0").containsMatchIn(it) })
        assertTrue("arm 不得解析 localPath/originPath（M1 同源 skip）",
            code.none { "localPath" in it } && code.none { "resolveOriginFullpath" in it })
    }

    // ------------------------------------------------------ 4. 字节判据（裁决 §3.6 第2、3条）

    /** 目标 MD5 硬编码 cff4f5a5；禁 xml .msg.img.$md5；0ef205c1 只作门禁不作参照。 */
    @Test
    fun refMd5AndBlurGateSemantics() {
        val code = codeLines(source(HOOK))
        assertTrue("REF_MD5_PRE 必须是 cff4f5a5", refMd5Ok(code))
        assertTrue("禁用 xml .msg.img.\$md5 作内容参照",
            code.none { ".msg.img." in it && "md5" in it })
        assertTrue("目标长度判据 45522L", code.any { "TARGET_LEN = 45522L" in it })
        assertTrue("模糊态门禁 9554L/0ef205c1 存在",
            code.any { "BLUR_LEN = 9554L" in it } &&
                code.any { "BLUR_MD5_PRE = \"0ef205c1\"" in it })
        assertTrue("0ef205c1 只作前置门禁（origin 校验）",
            code.any { "md5PrefixOfFile(origin) != BLUR_MD5_PRE" in it })
        assertTrue("cff4f5a5 作目标参照（admitTarget）",
            code.any { "md5PrefixOfFile(f) == REF_MD5_PRE" in it })
        assertTrue("日志只打 take(24)/takeLast(16)，不打完整值",
            code.any { "field_talker.take(24)" in it } && code.any { "target.takeLast(16)" in it })
    }

    /** 命中数 ==1 才写；不引用 P3AdmitGate/FLOOR/q1.j/b1.u8。 */
    @Test
    fun hitCountOneAndNoForbiddenRefs() {
        val code = codeLines(source(HOOK))
        assertTrue("命中数必须 ==1（0 或 >1 不写）", hitCountOneOk(code))
        assertTrue("不引用 P3AdmitGate", code.none { "P3AdmitGate" in it })
        assertTrue("不引用 FLOOR", code.none { "FLOOR" in it })
        assertTrue("不引用 q1.j", code.none { "q1.j" in it })
        assertTrue("不引用 b1.u8", code.none { "b1.u8" in it })
        assertTrue("不引用 DB", code.none { "execSQL" in it } && code.none { "SQLiteDatabase" in it })
    }

    // ------------------------------------------------------ 5. 隔离（裁决 §3.6 第4条）

    /** field_talker keyed + ConcurrentHashMap + 条件 remove；无 ThreadLocal / TargetToken / finally remove。 */
    @Test
    fun keyedIsolationNoThreadLocal() {
        val code = codeLines(source(HOOK))
        assertTrue("源码须出现 getObjectField(task,\"field_talker\") 与 pendingMap[field_talker] 命中",
            keyedGateOk(code))
        assertTrue("跨线程容器必须是 ConcurrentHashMap",
            code.any { "ConcurrentHashMap<String, Pending>" in it })
        assertTrue("关键状态必须是 @Volatile", code.count { it == "@Volatile" } >= 4)
        assertTrue("TTL 判定存在（单调时钟 elapsedRealtime）", monotonicClockOk(code))
        assertTrue("未命中直接 return",
            code.any { "pendingMap[field_talker] ?: return" in it })
        assertTrue("一次性条件 remove（不误删他人条目）",
            code.any { "pendingMap.remove(field_talker, p)" in it })
        assertTrue("绝不出现 ThreadLocal / TargetToken / finally remove",
            code.none { "ThreadLocal" in it || "TargetToken" in it } &&
                code.none { "finally" in it && "remove" in it })
        assertTrue("clear(wxid) 只 remove 指定 key，不清全表",
            code.any { "fun clear(wxid: String)" in it } &&
                code.any { "pendingMap.remove(wxid)" in it } &&
                code.none { "pendingMap.clear()" in it })
    }

    // ------------------------------------------------------ 6. FileSender / 接线（裁决 §3.6 第5条）

    /** markTarget(wxid) 必须在 arm 前，clear(wxid) 覆盖两条失败路径，绝不能用 sourceChat 当 key。 */
    @Test
    fun fileSenderWiringKeyed() {
        val fs = codeLines(source(FILE_SENDER))
        val mark = fs.indexOfFirst { "ForwardCdnFieldHook.markTarget(wxid)" in it }
        val arm = fs.indexOfFirst { "ForwardCdnFieldHook.arm(" in it }
        assertTrue("FileSender 必须调 markTarget(wxid)", mark >= 0)
        assertTrue("markTarget(wxid) 必须在 arm(...) 之前", arm >= 0 && mark < arm)
        assertTrue("⛔ 绝不能把 ctx.sourceChat 当 key",
            fs.none { "markTarget(ctx.sourceChat)" in it })
        assertTrue("arm 仍用 ctx.sourceChat 查源消息（探针用），不改",
            fs.any { "ForwardCdnFieldHook.arm(ctx.sourceChat, ctx.msgSvrId, ctx.imgLocalId)" in it })
        assertTrue("clear(wxid) 覆盖 queued=false 与 onFailure 两条失败路径",
            fs.count { "ForwardCdnFieldHook.clear(wxid)" in it } >= 2)
        assertTrue("不再有旧无参 clear()", fs.none { "ForwardCdnFieldHook.clear()" in it })
        assertTrue("arm 条件仍是 imgLocalId>0 && msgSvrId>0",
            fs.any { "ctx.imgLocalId > 0L && ctx.msgSvrId > 0L" in it })
        assertTrue("q1.a()/g6/l1.a() 序列保留",
            fs.any { "callMethod(q1, \"a\")" in it } &&
                fs.any { "\"g6\", l1" in it } &&
                fs.any { "callMethod(l1, \"a\")" in it })
    }

    /** HookEntry 只保留 ForwardCdnFieldHook.install；Keys 只留新 Key；EntryReceiver hevc 分支替换 r0e。 */
    @Test
    fun wiringAndBroadcastSwitches() {
        val he = codeLines(source(HOOK_ENTRY))
        assertTrue("保留 ForwardCdnFieldHook.install", he.any { "ForwardCdnFieldHook.install(lpparam)" in it })
        assertTrue("J2R0eOverrideHook 接线删除", he.none { "J2R0eOverrideHook" in it })

        val keys = codeLines(source(KEYS))
        assertTrue("新 Key = chat.forward_task_fullpath_hevc",
            keys.any { "FORWARD_TASK_FULLPATH_HEVC = \"chat.forward_task_fullpath_hevc\"" in it })
        assertTrue("旧 Key FORWARD_R0E_HEVC 必须删除（不留两个 Key）",
            keys.none { "FORWARD_R0E_HEVC" in it })

        val er = codeLines(source(ENTRY_RECEIVER))
        assertTrue("新增 hevc 广播分支", er.any { "\"hevc\" ->" in it })
        assertTrue("删除 r0e 分支", er.none { "\"r0e\" ->" in it })
        assertTrue("hevc 调 turn/status",
            er.any { "ForwardCdnFieldHook.turn(true)" in it } &&
                er.any { "ForwardCdnFieldHook.status()" in it })
        assertTrue("保留 r86c 探针分支（probeEnabled 依赖）", er.any { "\"r86c\" ->" in it })
        assertTrue("保留 stockcdn r0= 回显（probeEnabled）",
            er.any { "StockCdnProbe.r86cEnabled" in it })
    }

    // ------------------------------------------------------ 7. 五项真实文本变异各自判红

    /** M1：改 REF_MD5_PRE → deadbeef 必须判红。 */
    @Test
    fun mutationRefMd5ToDeadbeefIsRed() {
        val src = source(HOOK)
        val old = "REF_MD5_PRE = \"cff4f5a5\""
        assertTrue("变异锚点须唯一出现", occur(src, old) == 1)
        val mutated = src.replace(old, "REF_MD5_PRE = \"deadbeef\"")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        val mc = codeLines(mutated)
        assertTrue("改 REF_MD5_PRE→deadbeef 必须判红", !refMd5Ok(mc))
        assertTrue("自变量唯一：hook 点未变", hookIsM1e(mc))
        assertTrue("自变量唯一：命中数==1 未变", hitCountOneOk(mc))
    }

    /** M2：改 hook 点 m1.e → j2.R 必须判红。 */
    @Test
    fun mutationHookPointToJ2RIsRed() {
        val src = source(HOOK)
        val old = "findClass(\"ok0.m1\", cl)"
        assertTrue("变异锚点须唯一出现", occur(src, old) == 1)
        val mutated = src.replace(old, "findClass(\"com.tencent.mm.modelimage.j2\", cl)")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        val mc = codeLines(mutated)
        assertTrue("改 hook 点 m1.e→j2.R 必须判红", !hookIsM1e(mc))
        assertTrue("自变量唯一：REF_MD5_PRE 未变", refMd5Ok(mc))
        assertTrue("自变量唯一：命中数==1 未变", hitCountOneOk(mc))
    }

    /** M3：删命中数==1 判断（放宽为 isNotEmpty）必须判红。 */
    @Test
    fun mutationDropHitCountOneIsRed() {
        val src = source(HOOK)
        val old = "if (hits.size == 1) hits.first() else null"
        assertTrue("变异锚点须唯一出现", occur(src, old) == 1)
        val mutated = src.replace(old, "if (hits.isNotEmpty()) hits.first() else null")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        val mc = codeLines(mutated)
        assertTrue("删命中数==1 必须判红", !hitCountOneOk(mc))
        assertTrue("自变量唯一：模糊态门禁未变", hasBlurGate(mc))
        assertTrue("自变量唯一：key 命中未变", keyedGateOk(mc))
    }

    /** M4：删 field_talker key 命中改为无条件写必须判红。 */
    @Test
    fun mutationDropTalkerKeyIsRed() {
        val src = source(HOOK)
        val a = "val p = pendingMap[field_talker] ?: return"
        val b = "if (!pendingMap.remove(field_talker, p)) return"
        assertTrue("变异锚点 a 须唯一出现", occur(src, a) == 1)
        assertTrue("变异锚点 b 须唯一出现", occur(src, b) == 1)
        var mutated = src.replace(a, "val p = Pending(System.currentTimeMillis())")
        mutated = mutated.replace(b, "Unit")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        val mc = codeLines(mutated)
        assertTrue("删 field_talker key 命中改无条件写必须判红", !keyedGateOk(mc))
        assertTrue("自变量唯一：hook 点未变", hookIsM1e(mc))
        assertTrue("自变量唯一：模糊态门禁未变", hasBlurGate(mc))
    }

    /** M5：删模糊态门禁必须判红。 */
    @Test
    fun mutationDropBlurGateIsRed() {
        val src = source(HOOK)
        val a = "if (!origin.exists() || origin.length() != BLUR_LEN) return@runCatching null"
        val b = "if (md5PrefixOfFile(origin) != BLUR_MD5_PRE) return@runCatching null"
        assertTrue("变异锚点 a 须唯一出现", occur(src, a) == 1)
        assertTrue("变异锚点 b 须唯一出现", occur(src, b) == 1)
        val mutated = src.replace(a, "Unit").replace(b, "Unit")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        val mc = codeLines(mutated)
        assertTrue("删模糊态门禁必须判红", !hasBlurGate(mc))
        assertTrue("自变量唯一：REF_MD5_PRE 未变", refMd5Ok(mc))
        assertTrue("自变量唯一：key 命中未变", keyedGateOk(mc))
    }

    // ------------------------------- 修正轮：TTL / 单调时钟 / 有界重试维度（Q1 + Q2.8-2）

    /** 正向锁：TTL=15000L + elapsedRealtime + 有界重试 + 快速扫描四项修正均须在场。 */
    @Test
    fun fixRoundInvariantsPresent() {
        val code = codeLines(source(HOOK))
        assertTrue("TTL 必须是 15000L（覆盖 6.3s/10.1s 迟到帧）", ttlOk(code))
        assertTrue("TTL 必须走单调时钟 elapsedRealtime", monotonicClockOk(code))
        assertTrue("有界重试处置必须在场（basename 限定 + 成功后续期 + 上限）", retryBoundedOk(code))
        assertTrue("同目录优先 + 全树扫描上限必须在场", fastScanOk(code))
    }

    /** M6：TTL 15000L → 2000L（退回 BLOCKER）必须判红。 */
    @Test
    fun mutationTtlTo2000IsRed() {
        val src = source(HOOK)
        val old = "TTL_MS = 15000L"
        assertTrue("变异锚点须唯一出现", occur(src, old) == 1)
        val mutated = src.replace(old, "TTL_MS = 2000L")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        val mc = codeLines(mutated)
        assertTrue("TTL 改回 2000L 必须判红", !ttlOk(mc))
        assertTrue("自变量唯一：单调时钟未变", monotonicClockOk(mc))
        assertTrue("自变量唯一：有界重试未变", retryBoundedOk(mc))
        assertTrue("自变量唯一：命中数==1 未变", hitCountOneOk(mc))
    }

    /** M7：elapsedRealtime → currentTimeMillis（墙钟回拨/跳变）必须判红。 */
    @Test
    fun mutationMonotonicToWallClockIsRed() {
        val src = source(HOOK)
        val old = "SystemClock.elapsedRealtime() - p.ts > TTL_MS"
        assertTrue("变异锚点须唯一出现", occur(src, old) == 1)
        val mutated = src.replace(old, "System.currentTimeMillis() - p.ts > TTL_MS")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        val mc = codeLines(mutated)
        assertTrue("elapsedRealtime 改回 currentTimeMillis 必须判红", !monotonicClockOk(mc))
        assertTrue("自变量唯一：TTL 值未变", ttlOk(mc))
        assertTrue("自变量唯一：有界重试未变", retryBoundedOk(mc))
    }

    /** M8：删有界重试重登记（退回首帧即消费）必须判红。 */
    @Test
    fun mutationDropBoundedRetryIsRed() {
        val src = source(HOOK)
        val old = "pendingMap[field_talker] = Pending(SystemClock.elapsedRealtime(), bn, p.hits + 1)"
        assertTrue("变异锚点须唯一出现", occur(src, old) == 1)
        val mutated = src.replace(old, "Unit")
        assertTrue("变异必须真正改动源码文本", mutated != src)
        val mc = codeLines(mutated)
        assertTrue("删有界重试重登记（退回首帧消费）必须判红", !retryBoundedOk(mc))
        assertTrue("自变量唯一：TTL 值未变", ttlOk(mc))
        assertTrue("自变量唯一：单调时钟未变", monotonicClockOk(mc))
        assertTrue("自变量唯一：key 命中未变", keyedGateOk(mc))
    }

    // ------------------------------------------------------ 判定函数（主测 + 变异共用）

    private fun hookIsM1e(code: List<String>): Boolean =
        code.any { "findClass(\"ok0.m1\"" in it } &&
            code.any { "m.name == \"e\"" in it } &&
            code.any { "java.lang.Boolean.TYPE" in it }

    private fun refMd5Ok(code: List<String>): Boolean =
        code.any { "REF_MD5_PRE = \"cff4f5a5\"" in it }

    private fun hitCountOneOk(code: List<String>): Boolean =
        code.any { "hits.size == 1" in it }

    private fun keyedGateOk(code: List<String>): Boolean =
        code.any { "getObjectField(task, \"field_talker\")" in it } &&
            code.any { "val p = pendingMap[field_talker] ?: return" in it } &&
            code.any { "if (!pendingMap.remove(field_talker, p)) return" in it }

    private fun hasBlurGate(code: List<String>): Boolean =
        code.any { "origin.length() != BLUR_LEN" in it } &&
            code.any { "md5PrefixOfFile(origin) != BLUR_MD5_PRE" in it }

    /** TTL 必须 = 15000L（覆盖真机 10.071s 迟到帧；2000L 必判红）。 */
    private fun ttlOk(code: List<String>): Boolean =
        code.any { "TTL_MS = 15000L" in it }

    /** TTL 判定必须走单调时钟（currentTimeMillis 墙钟回拨/跳变必判红）。 */
    private fun monotonicClockOk(code: List<String>): Boolean =
        code.any { "SystemClock.elapsedRealtime() - p.ts > TTL_MS" in it }

    /**
     * 有界重试处置：basename 限定（防串味）+ 上限早停 + 条件原子消费 + 成功后同 basename 重登记。
     * 删重登记（退回首帧消费）必判红；三处缺一即不通过。
     */
    private fun retryBoundedOk(code: List<String>): Boolean =
        code.any { "p.basename != bn" in it } &&
            code.any { "p.hits >= MAX_HITS" in it } &&
            code.any { "pendingMap.remove(field_talker, p)" in it } &&
            code.any { "pendingMap[field_talker] = Pending(" in it }

    /** 快速扫描：同目录优先命中 + 全树兜底扫描上限（MaxScanFiles），避免阻塞 [D]#worker。 */
    private fun fastScanOk(code: List<String>): Boolean =
        code.any { "MAX_SCAN_FILES" in it } &&
            code.any { "origin.parentFile?.listFiles()" in it } &&
            code.any { "scanned >= MAX_SCAN_FILES" in it }

    private fun setFields(code: List<String>): List<String> = code.filter {
        Regex("set(?:Object|Long|Int|Boolean|Short|Float|Double|Byte|Char)Field").containsMatchIn(it)
    }

    // ------------------------------------------------------ 工具（零 Android 依赖）

    /** 子串出现次数（用于确保变异锚点唯一，防止等价变异/假验证）。 */
    private fun occur(src: String, needle: String): Int = src.split(needle).size - 1

    /** 从若干候选父目录向上找源码文件；兼容 gradle cwd = 仓库根 / wxgroup / wxgroup/app。 */
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
}
