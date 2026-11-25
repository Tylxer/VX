package com.lx.wxgroup.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R76 P3「字节落盘」ForceForward 侧接线回归锁（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 与 [R76P3FileSenderTest]（FileSender 侧，另一 lane）互补，只锁接线事实、不做根因推理：
 * 1. `resendFile` 在 :2232 处给 `FileSender.send` 传真实第 6 参 `bigImgPath`（前 5 参不变）；
 * 2. `prepareBigImg` 下载姿势照抄 AutoHdImg（F8/O8/o2/t==1/TYPE_HD/listener）；
 * 3. 三门槛 + 独立临时文件命名 + `.part`→flush→rename 写入纪律；
 * 4. 清理三档（0/0 回包成功删 / false 返回删 / 无硬编码 cache 根）；
 * 5. `Keys.FORWARD_P3_BIGIMG` 名与默认值 + `EntryReceiver` 的 `p3`/`p3on`/`p3off` 分支。
 * 6. 【P0-1 必修】异步落盘结果消费：`fv.e0.q`/`l0` 成功回调回读 `o2(localId)` → `p3SrcOf`
 *    → 三门槛 → 写 `@Volatile p3BigImgPath` + 置 `p3BigImgReady`；`resendFile` 两态判定
 *    （ready+门槛用新路径，否则回退快照），绝不写坏路径。
 * 7. 【P1 应修】清理关联：发送时登记 tempPath→localId（短 TTL Map），hook 用 j2 long 字段
 *    关联删除；原字段扫描降级 best-effort 且 `temp cleaned` 降为 debug（不作成功判据）。
 *
 * 防假绿：所有源码断言一律走 [codeLines]（去注释行判定），注释里的历史签名/死字符串
 * 不得制造绿。变异用例对**真实源码**做内存变异后跑同一套判据，确认会红（非纸面声明）。
 *
 * 变异映射（本 lane 已离线跑过，见回报）：
 * - M1：准入门禁 `P3AdmitGate.admit(srcFile, false)` 改成 `..., true)`（把来路不明的绝对路径强制 strict shape）
 *   ⇒ [threeThresholdsAndPartDiscipline] 红；
 * - M2：send 去掉第 6 参 ⇒ [resendFilePassesSixthArg] 红；
 * - M3：删 `p3off` 分支 ⇒ [entryReceiverOpsLocked] 红；
 * - M4：回调不置 `p3BigImgReady = true` ⇒ [p0AsyncResultConsumed] 红；
 * - M5：回调不监听 `q`/`l0` ⇒ [p0AsyncResultConsumed] 红；
 * - M6：`resendFile` 去掉两态回退（恒用快照）⇒ [p0AsyncResultConsumed] 红；
 * - M7：清理去掉 `i/h/j/Q` 关联 ⇒ [p1CleanupAssociation] 红；
 * - M8：`temp cleaned` 升回 info ⇒ [p1CleanupAssociation] 红。
 */
class R76P3WireTest {

    companion object {
        private const val FF = "app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt"
        private const val KEYS = "app/src/main/java/com/lx/wxgroup/data/Keys.kt"
        private const val ENTRY = "app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt"
    }

    // ------------------------------------------------ 1) 第 6 参传递（去注释判定）

    /** :2232 处 send 必须是真实 6 参，且前 5 参取值一字未动。 */
    @Test
    fun resendFilePassesSixthArg() {
        assertTrue(
            "resendFile 必须给 FileSender.send 传第 6 参 bigImgPath，前 5 参不变（codeLines 去注释）",
            sixthArgOk(codeLines(source(FF)))
        )
    }

    // ------------------------------------------------ 2) 下载姿势照抄

    /** prepareBigImg 必须照抄 AutoHdImg 的 F8/O8/o2/t==1/TYPE_HD/listener。 */
    @Test
    fun prepareBigImgCopiesAutoHdDownload() {
        val code = codeLines(source(FF))
        assertTrue(
            "d3.F8() 取 DownloadImgService（AutoHdImg.kt:416 姿势）",
            code.any { "XposedHelpers.callStaticMethod(core, \"F8\")" in it }
        )
        assertTrue(
            "d3.O8().o2(localId) 判重（AutoHdImg.kt:420-421 姿势）",
            code.any { "XposedHelpers.callStaticMethod(core, \"O8\")" in it } &&
                code.any { "\"o2\", localId" in it }
        )
        assertTrue(
            "t==1 表示原图已下完 ⇒ 跳过下载（AutoHdImg.kt:426 口径）",
            code.any { "imgT == 1" in it }
        )
        assertTrue(
            "TYPE_HD 必须与 AutoHdImg 同值 0，照抄 svc.c 六参调用",
            code.any { "private const val TYPE_HD = 0" in it } &&
                code.any { "svc, \"c\", localId, msgId, TYPE_HD" in it } &&
                code.any { "java.lang.Long.valueOf(msgId), 0, listener" in it }
        )
    }

    // ------------------------------------------------ 3) 三门槛 + 写入纪律

    /** 准入三门槛 + 独立临时文件命名 + .part→flush→rename，必须真实落在代码里。 */
    @Test
    fun threeThresholdsAndPartDiscipline() {
        assertTrue(
            "三门槛 + .part→rename 纪律必须全在代码里（codeLines 去注释）",
            thresholdsAndPartOk(codeLines(source(FF)))
        )
    }

    /** cacheDir 必须取宿主 application，绝不硬编码 /data/data/<pkg>/cache。 */
    @Test
    fun noHardcodedCacheRoot() {
        val code = codeLines(source(FF))
        assertTrue("必须走 HostContext.app().cacheDir", code.any { "HostContext.app()" in it })
        assertFalse("绝不能硬编码 /data/data/", code.any { "/data/data/" in it })
    }

    // ------------------------------------------------ 4) 清理三档

    /** 0/0 成功回包删 + send 返回 false 删，挂点必须都在。 */
    @Test
    fun cleanupThreeTiersLocked() {
        val code = codeLines(source(FF))
        assertTrue(
            "成功档：onGYNetEnd 回 0/0 才删，挂 j2.onGYNetEnd",
            code.any { "it.name == \"onGYNetEnd\"" in it } &&
                code.any { "errType != 0 || errCode != 0" in it }
        )
        assertTrue(
            "返回档：send false ⇒ runCatching { File(bigImgPath).delete() }",
            code.any { "runCatching { File(bigImgPath).delete() }" in it }
        )
        assertTrue(
            "成功入队的临时文件挂进 p3PendingTemps 等 0/0",
            code.any { "p3PendingTemps[bigImgPath] = true" in it }
        )
    }

    // ------------------------------------------------ 4b) P0-1 异步落盘结果消费（必修缺口）

    /**
     * P0-1：下载成功回调必须回读 ImgInfo2 大图路径并写 @Volatile ready/path，
     * resendFile 必须按「ready + 三门槛」两态决定 bigImgPath（就绪用新路径，否则回退快照）。
     */
    @Test
    fun p0AsyncResultConsumed() {
        assertTrue(
            "P0-1：@Volatile ready + path 字段、q/l0 回调回读、两态判定必须全在（codeLines 去注释）",
            p0AsyncConsumedOk(codeLines(source(FF)))
        )
    }

    // ------------------------------------------------ 4c) P1 清理关联（应修）

    /** P1：tempPath→localId 短 TTL Map + j2 long 字段关联删除，字段扫描降 best-effort/debug。 */
    @Test
    fun p1CleanupAssociation() {
        assertTrue(
            "P1：短 TTL Map 登记 + j2 long 字段关联 + bestEffort debug 降级必须全在",
            p1CleanupOk(codeLines(source(FF)))
        )
    }

    // ------------------------------------------------ 5) 开关 + 广播分支

    /** Keys key 名 + 默认开（P3 是修复，可反向对照）。 */
    @Test
    fun keysSwitchLocked() {
        assertTrue(
            "Keys key 名与默认值（默认 true）必须锁定",
            keysOk(codeLines(source(KEYS)))
        )
    }

    /** EntryReceiver 的 p3 / p3on / p3off 分支必须真实在 when(op) 里。 */
    @Test
    fun entryReceiverOpsLocked() {
        assertTrue(
            "EntryReceiver 必须有 p3/p3on/p3off 三个 op 分支",
            entryOpsOk(codeLines(source(ENTRY)))
        )
    }

    // ------------------------------------------------ 变异验证（对真实源码做内存变异，跑同一判据）

    /**
     * M1：把准入门禁从 `admit(srcFile, false)` 改成 `admit(srcFile, true)`（对来路不明的绝对路径
     * 强制 strict shape 是错误行为），三门槛 + 写入纪律判据必须判红。
     */
    @Test
    fun mutationRelaxedThresholdIsDetected() {
        val code = codeLines(source(FF))
        val mutated = code.map {
            it.replace("P3AdmitGate.admit(srcFile, false)", "P3AdmitGate.admit(srcFile, true)")
        }
        assertTrue("变异必须真正改动源码", mutated != code)
        assertFalse("准入门禁被强制 strict shape 必须被判红", thresholdsAndPartOk(mutated))
    }

    /** M2：删掉第 6 参，send 判据必须判红。 */
    @Test
    fun mutationDroppedSixthArgIsDetected() {
        val mutated = codeLines(source(FF)).map { it.replace("snap, bigImgPath)", "snap)") }
        assertFalse("send 去掉第 6 参必须被判红", sixthArgOk(mutated))
    }

    /** M3：删掉 p3off 分支，广播分支判据必须判红。 */
    @Test
    fun mutationDroppedP3OffOpIsDetected() {
        val mutated = codeLines(source(ENTRY)).filterNot { it == "\"p3off\" -> {" }
        assertFalse("删掉 p3off 分支必须被判红", entryOpsOk(mutated))
    }

    /** M4：回调不置 ready=true（改回 false），P0-1 判据必须判红。 */
    @Test
    fun mutationReadyNeverSetIsDetected() {
        val mutated = codeLines(source(FF)).map { it.replace("p3BigImgReady = true", "p3BigImgReady = false") }
        assertFalse("回调不置 ready=true 必须被判红", p0AsyncConsumedOk(mutated))
    }

    /** M5：回调不监听 q/l0 完成回调，P0-1 判据必须判红。 */
    @Test
    fun mutationNoCompletionCallbackIsDetected() {
        val mutated = codeLines(source(FF)).map {
            it.replace("if (name == \"q\" || name == \"l0\") {", "if (false) {")
        }
        assertFalse("不监听 q/l0 完成回调必须被判红", p0AsyncConsumedOk(mutated))
    }

    /** M6：resendFile 去掉两态回退（恒用快照），P0-1 判据必须判红。 */
    @Test
    fun mutationNoTwoStateFallbackIsDetected() {
        val mutated = codeLines(source(FF)).map {
            it.replace("val chosenP3Src = p3ReadyPath ?: p3BigImgSrc", "val chosenP3Src = p3BigImgSrc")
        }
        assertFalse("resendFile 去掉两态回退必须被判红", p0AsyncConsumedOk(mutated))
    }

    /** M7：清理去掉 i/h/j/Q 关联，P1 判据必须判红。 */
    @Test
    fun mutationNoLocalIdAssociationIsDetected() {
        val mutated = codeLines(source(FF)).map {
            it.replace("arrayOf(\"i\", \"h\", \"j\", \"Q\")", "arrayOf<String>()")
        }
        assertFalse("清理去掉 localId 关联必须被判红", p1CleanupOk(mutated))
    }

    /** M8：temp cleaned 升回 info（不作 best-effort 降级），P1 判据必须判红。 */
    @Test
    fun mutationBestEffortLogNotDowngradedIsDetected() {
        val mutated = codeLines(source(FF)).map {
            it.replace("LogX.d(\"FORWARD P3 temp cleaned bestEffort 0/0", "LogX.i(\"FORWARD P3 temp cleaned bestEffort 0/0")
        }
        assertFalse("bestEffort 清理日志升回 info 必须被判红", p1CleanupOk(mutated))
    }

    // ------------------------------------------------ 判据（纯函数，输入 codeLines 结果）

    /** :2232 send 必须 6 参且第 6 参名 bigImgPath、前 5 参逐字不变。 */
    private fun sixthArgOk(code: List<String>): Boolean {
        val line = code.firstOrNull { "val ok = FileSender.send(" in it } ?: return false
        val args = line.substringAfter("FileSender.send(").substringBefore(")")
        if (args.split(",").size != 6) return false
        return args.replace(" ", "") ==
            "kind,target,snap.localPath,snap.imgLocalId,snap,bigImgPath"
    }

    /**
     * 三门槛 + 命名 + .part→flush→rename 写入纪律。
     *
     * R85 迁移：两处旧字面量三门槛已收敛到 P3AdmitGate —— 准入门禁改认
     * `P3AdmitGate.admit(srcFile, false)`，复制后复核改认 `P3AdmitGate.admit(temp, false)`；
     * strictShape=false 对应「记录里来路不明的绝对路径不强制 shape」。.part 写入纪律断言不变。
     */
    private fun thresholdsAndPartOk(code: List<String>): Boolean =
        code.any { "P3AdmitGate.admit(srcFile, false)" in it } &&
            code.any { "UUID.randomUUID().toString().replace(\"-\", \"\")" in it } &&
            code.any { "File(app.cacheDir, \"\$uuid.part\")" in it } &&
            code.any { "File(app.cacheDir, \"_\$uuid.jpg\")" in it } &&
            code.any { "out.flush()" in it } &&
            code.any { "part.renameTo(temp)" in it } &&
            code.any { "P3AdmitGate.admit(temp, false)" in it }

    /** Keys key 名 + 默认 true。 */
    private fun keysOk(code: List<String>): Boolean =
        code.any { it == "const val FORWARD_P3_BIGIMG = \"chat.forward_p3_bigimg\"" } &&
            code.any {
                it == "Keys.FORWARD_P3_BIGIMG, PrefKind.SWITCH, \"true\", \"聊天\","
            }

    /** EntryReceiver 三个 op 分支 + 真实写开关。 */
    private fun entryOpsOk(code: List<String>): Boolean =
        code.any { it == "\"p3\" -> {" } &&
            code.any { it == "\"p3on\" -> {" } &&
            code.any { it == "\"p3off\" -> {" } &&
            code.any { "Kv.putBool(Keys.FORWARD_P3_BIGIMG, true)" in it } &&
            code.any { "Kv.putBool(Keys.FORWARD_P3_BIGIMG, false)" in it }

    /**
     * P0-1 判据：字段 / 回调 / 回读 / 两态 四段全在才算绿。
     * 回调方法名 q/l0 与 errType/errCode 参数位 source from fv.e0 dex（见 ForceForward 注释，
     * _s9dump/classes7.txt:432207 + r34 anchor :163 + m.T/onSceneEnd 反汇编），非猜测。
     */
    private fun p0AsyncConsumedOk(code: List<String>): Boolean {
        // ① @Volatile 消费位：ready + path 两个字段必须存在
        val volReady = code.any { "private var p3BigImgReady = false" in it }
        val volPath = code.any { "private var p3BigImgPath: String? = null" in it }
        if (!volReady || !volPath) return false
        // ② listener 绑定本轮 id + 只监听 q/l0 完成回调 + errType/errCode 在 args[5]/[6] 且 0/0
        val bindOk = code.any {
            "private fun newP3Listener(cl: ClassLoader, localId: Long, msgId: Long)" in it
        }
        val callbacksOk = code.any { "if (name == \"q\" || name == \"l0\") {" in it }
        val errGateOk = code.any { "a?.getOrNull(5) as? Int" in it } &&
            code.any { "a?.getOrNull(6) as? Int" in it } &&
            code.any { "errType == 0 && errCode == 0" in it }
        val idGateOk = code.any { "cbLocal == p3BigImgLocalId && cbMsg == p3BigImgMsgId" in it }
        if (!bindOk || !callbacksOk || !errGateOk || !idGateOk) return false
        // ③ 成功回调回读 O8().o2(localId) → p3SrcOf → 过三门槛 → 先写 path 后置 ready
        val rereadOk = code.any { "XposedHelpers.callStaticMethod(core, \"O8\")" in it } &&
            code.any { "XposedHelpers.callMethod(it, \"o2\", localId)" in it } &&
            code.any { "val path = p3SrcOf(img)" in it }
        val pathIdx = code.indexOfFirst { it == "p3BigImgPath = path" }
        val readyIdx = code.indexOfFirst { it == "p3BigImgReady = true" }
        val writeOrderOk = pathIdx >= 0 && readyIdx >= 0 && pathIdx < readyIdx
        val logOk = code.any { "FORWARD P3 bigimg ready=true path=" in it }
        if (!rereadOk || !writeOrderOk || !logOk) return false
        // ④ resendFile 两态：p3ReadyPath 取 p3BigImgPath（受 ready 与三门槛约束），
        //    最终 copyP3Temp(chosenP3Src)，chosenP3Src = 新路径 ?: 旧快照
        val twoStateOk = code.any { "val p3ReadyPath = if (p3BigImgReady) {" in it } &&
            code.any { "val chosenP3Src = p3ReadyPath ?: p3BigImgSrc" in it } &&
            code.any { "copyP3Temp(chosenP3Src)" in it } &&
            code.any { "FORWARD P3 bigimg ready=\$p3BigImgReady path=" in it }
        return twoStateOk
    }

    /** P1 判据：短 TTL Map 登记 + j2 long 字段关联删除 + bestEffort 日志降 debug。 */
    private fun p1CleanupOk(code: List<String>): Boolean {
        // ① 短 TTL Map（CHM）+ 发送时登记 tempPath→(localId, now)
        val mapOk = code.any {
            "private val p3TempByIdentity = ConcurrentHashMap<String, Pair<Long, Long>>()" in it
        } && code.any {
            "p3TempByIdentity[bigImgPath] = snap.imgLocalId to android.os.SystemClock.elapsedRealtime()" in it
        }
        // ② hook 用 j2 long 字段 i/h/j/Q 取 localId 关联命中即删（短 TTL 判定）
        val assocOk = code.any { "arrayOf(\"i\", \"h\", \"j\", \"Q\")" in it } &&
            code.any { "XposedHelpers.getLongField(obj, it)" in it } &&
            code.any { "p3TempByIdentity.remove(path) != null" in it } &&
            code.any { "now - at <= 120_000L" in it }
        // ③ 原字段扫描降 best-effort：日志必须是 LogX.d 且带 bestEffort 字样
        val bestEffortOk = code.any {
            "LogX.d(\"FORWARD P3 temp cleaned bestEffort 0/0" in it
        }
        // ④ 并发安全：p3PendingTemps / p3TempByIdentity 均为 CHM（无 ThreadLocal 跨线程）
        val concurrentOk = code.any { "ConcurrentHashMap<String, Boolean>()" in it }
        return mapOk && assocOk && bestEffortOk && concurrentOk
    }

    // ------------------------------------------------ 工具（与 R76P3FileSenderTest 同构）

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
