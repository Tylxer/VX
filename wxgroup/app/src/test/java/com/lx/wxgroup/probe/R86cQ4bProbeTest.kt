package com.lx.wxgroup.probe

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R86c Q4b 三只读探针落码回归锁（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 规格 `docs/_r86c_probe_landing_spec.md` §4.5（:410-433）：16 个字符串锚点 + 4 项硬锁，
 * 每个锚点都有真实源码变异验证（改一处 → 判定函数必须翻红，否则视为假绿）。
 * 本测试只读三个源码文件的文本，不实例化探针、不依赖宿主类路径。
 * 所有断言读**去注释后的代码行**（[codeLines]）或其字符串字面量（[stringLiterals]），
 * 避免 KDoc / 行注释里引用的死字符串造成假绿。
 */
class R86cQ4bProbeTest {

    companion object {
        private const val FS = "app/src/main/java/com/lx/wxgroup/probe/StockCdnProbe.kt"
        private const val R0 = "app/src/main/java/com/lx/wxgroup/core/ForwardCdnFieldHook.kt"
        private const val RX = "app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt"
    }

    // ===================================================================== 16 锚点

    /** 锚点1：`"R86C"` 统一前缀 —— 三个探针块都必须有 R86C 前缀日志；改 SCD2 ⇒ FAIL。 */
    @Test
    fun a01_unifiedR86CPrefix() {
        val fs = fsBlock(fsSrc()); val r0 = r0Block(r0Src()); val rx = rxBlock(rxSrc())
        assertTrue("绿态：三块都必须含 R86C 前缀", prefixOk(fs, r0, rx))
        assertTrue(
            "变异（三块 R86C→SCD2）必须让前缀判定翻红",
            !prefixOk(fs.replace("R86C", "SCD2"), r0.replace("R86C", "SCD2"), rx.replace("R86C", "SCD2"))
        )
    }

    /** 锚点2：`"R86C FS cand"` / `"R86C FS verdict"` —— P-FS 候选行/汇总行。 */
    @Test
    fun a02_fsCandidateAndVerdictLines() {
        val fs = fsBlock(fsSrc())
        assertTrue("绿态：候选行 + 汇总行都必须在", fsLogAnchorsOk(fs))
        assertTrue("变异（R86C FS cand 改名）必须翻红",
            !fsLogAnchorsOk(fs.replace("R86C FS cand ", "R86C FS xyz ")))
        assertTrue("变异（R86C FS verdict 改名）必须翻红",
            !fsLogAnchorsOk(fs.replace("R86C FS verdict ", "R86C FS vvvv ")))
    }

    /** 锚点3：`"R86C R0ID"` / `"R86C R0 verdict"` / `"R86C R0 field="` —— P-R0ID/P-R0 行。 */
    @Test
    fun a03_r0IdVerdictFieldLines() {
        val r0 = r0Block(r0Src())
        assertTrue("绿态：R0ID/verdict/field 三行都必须在", r0LogAnchorsOk(r0))
        assertTrue("变异（R86C R0ID 改名）必须翻红",
            !r0LogAnchorsOk(r0.replace("R86C R0ID ", "R86C R0XX ")))
        assertTrue("变异（R86C R0 verdict 改名）必须翻红",
            !r0LogAnchorsOk(r0.replace("R86C R0 verdict ", "R86C R0 vvvv ")))
        assertTrue("变异（field= 改名）必须翻红",
            !r0LogAnchorsOk(r0.replace("field=\$f", "fld=\$f")))
    }

    /** 锚点4：`"cff4f5a5"` 内容 md5 判据（FS + R0 两处）。 */
    @Test
    fun a04_contentMd5Criteria() {
        val fs = fsBlock(fsSrc()); val r0 = r0Block(r0Src())
        assertTrue("绿态：两探针都必须用 cff4f5a5", md5CriteriaOk(fs, r0))
        assertTrue("变异（FS 改 cff4f5a6）必须翻红", !md5CriteriaOk(fs.replace("cff4f5a5", "cff4f5a6"), r0))
        assertTrue("变异（R0 改 cff4f5a6）必须翻红", !md5CriteriaOk(fs, r0.replace("cff4f5a5", "cff4f5a6")))
    }

    /** 锚点5：`45522L` 体积判据（FS + R0 两处）；改 9554L ⇒ FAIL。 */
    @Test
    fun a05_sizeCriteria() {
        val fs = fsBlock(fsSrc()); val r0 = r0Block(r0Src())
        assertTrue("绿态：两探针都必须用 45522L", sizeCriteriaOk(fs, r0))
        assertTrue("变异（FS 45522L→9554L）必须翻红", !sizeCriteriaOk(fs.replace("45522L", "9554L"), r0))
        assertTrue("变异（R0 45522L→9554L）必须翻红", !sizeCriteriaOk(fs, r0.replace("45522L", "9554L")))
    }

    /** 锚点6：`".jpg_hevc"` / `"/MicroMsg/"` / `"image2"` 扫描范围。 */
    @Test
    fun a06_scanScope() {
        val fs = fsBlock(fsSrc())
        assertTrue("绿态：三个扫描范围串都必须在", scanScopeOk(fs))
        assertTrue("变异（.jpg_hevc→.png）必须翻红", !scanScopeOk(fs.replace(".jpg_hevc", ".png")))
        assertTrue("变异（/MicroMsg/→/WeChat/）必须翻红", !scanScopeOk(fs.replace("/MicroMsg/", "/WeChat/")))
        assertTrue("变异（image2→image3）必须翻红", !scanScopeOk(fs.replace("image2", "image3")))
    }

    /** 锚点7：`"field_fullpath"`（P-FS root 来源）改 field_thumbpath ⇒ FAIL。 */
    @Test
    fun a07_fsRootSource() {
        val fs = fsBlock(fsSrc())
        assertTrue("绿态：root 必须来自 field_fullpath + MicroMsg 正则", fsRootSourceOk(fs))
        assertTrue("变异（field_fullpath→field_thumbpath）必须翻红",
            !fsRootSourceOk(fs.replace("field_fullpath", "field_thumbpath")))
        assertTrue("变异（正则 32hex 改 16hex）必须翻红",
            !fsRootSourceOk(fs.replace("[0-9a-fA-F]{32}", "[0-9a-fA-F]{16}")))
    }

    /** 锚点8：`"a3"` / `"o2"` / `"z3"` 反射方法名；o2↔a3 互换必须 FAIL。 */
    @Test
    fun a08_reflectMethodNames() {
        val r0 = r0Block(r0Src())
        assertTrue("绿态：a3(talker,msgSvrId)/o2(imgLocalId)/z3 都必须在", reflectNamesOk(r0))
        val swapped = r0
            .replace("callMethod(o8, \"a3\", talker, msgSvrId)", "callMethod(o8, \"o2\", talker, msgSvrId)")
            .replace("callMethod(o8, \"o2\", imgLocalId)", "callMethod(o8, \"a3\", imgLocalId)")
        assertTrue("变异（o2↔a3 互换）必须翻红：两查询键不可换", !reflectNamesOk(swapped))
        assertTrue("变异（z3→z4）必须翻红",
            !reflectNamesOk(r0.replace("callMethod(o8, \"z3\"", "callMethod(o8, \"z4\"")))
    }

    /** 锚点9：`listOf("e","f","g","j")` 四路 z3；漏一路 ⇒ FAIL。 */
    @Test
    fun a09_fourWayZ3Fields() {
        val r0 = r0Block(r0Src())
        assertTrue("绿态：四路 z3 必须在", fourWayZ3Ok(r0))
        assertTrue("变异（漏 j 路）必须翻红",
            !fourWayZ3Ok(r0.replace("listOf(\"e\", \"f\", \"g\", \"j\")", "listOf(\"e\", \"f\", \"g\")")))
    }

    /** 锚点10：`listOf("e","f","g","j","l")` P-R0ID 五字段；漏 l ⇒ FAIL。 */
    @Test
    fun a10_fiveR0IdFields() {
        val r0 = r0Block(r0Src())
        assertTrue("绿态：P-R0ID 五字段必须在", fiveR0FieldsOk(r0))
        assertTrue("变异（漏 l 字段）必须翻红",
            !fiveR0FieldsOk(r0.replace("listOf(\"e\", \"f\", \"g\", \"j\", \"l\")",
                "listOf(\"e\", \"f\", \"g\", \"j\")")))
    }

    /** 锚点11：`callMethod(o8,"z3",raw,"","",b)` 四参模板；删第4参/改参序 ⇒ FAIL。 */
    @Test
    fun a11_z3FourArgTemplate() {
        val r0 = r0Block(r0Src())
        assertTrue("绿态：z3 四参模板必须在", z3FourArgOk(r0))
        assertTrue("变异（删第4参 b）必须翻红",
            !z3FourArgOk(r0.replace("callMethod(o8, \"z3\", raw, \"\", \"\", b)",
                "callMethod(o8, \"z3\", raw, \"\", \"\")")))
        assertTrue("变异（参序 raw↔空串）必须翻红",
            !z3FourArgOk(r0.replace("callMethod(o8, \"z3\", raw, \"\", \"\", b)",
                "callMethod(o8, \"z3\", \"\", raw, \"\", b)")))
    }

    /** 锚点12：`takeLast(16)` / `take(80)` 裁剪纪律；改全量打印 ⇒ FAIL。 */
    @Test
    fun a12_trimDiscipline() {
        val fs = fsBlock(fsSrc()); val r0 = r0Block(r0Src())
        assertTrue("绿态：FS tail16 + R0 tail16/take80 必须在", trimOk(fs, r0))
        assertTrue("变异（FS takeLast(16)→64）必须翻红", !trimOk(fs.replace("takeLast(16)", "takeLast(64)"), r0))
        assertTrue("变异（R0 take(80)→800）必须翻红", !trimOk(fs, r0.replace("take(80)", "take(800)")))
    }

    /** 锚点13：`System.identityHashCode` 对象身份；改 toString().hashCode() ⇒ FAIL。 */
    @Test
    fun a13_identityHashCode() {
        val r0 = r0Block(r0Src())
        assertTrue("绿态：System.identityHashCode 必须在", identityOk(r0))
        assertTrue("变异（换成 toString().hashCode()）必须翻红",
            !identityOk(r0.replace("System.identityHashCode(o)", "o.toString().hashCode()")))
    }

    /** 锚点14：`if (!r86cEnabled) return` / `if (probeEnabled)` 默认关隔离；删/改 || ⇒ FAIL。 */
    @Test
    fun a14_defaultOffIsolation() {
        val fs = fsBlock(fsSrc()); val arm = armBlock(r0Src())
        assertTrue("绿态：两处默认关闸门都必须在", defaultOffOk(fs, arm))
        assertTrue("变异（FS 首行 guard 改 &&）必须翻红",
            !defaultOffOk(fs.replace("if (!r86cEnabled) return", "if (!r86cEnabled && !enabled) return"), arm))
        assertTrue("变异（R0 gated 改 if(true)）必须翻红",
            !defaultOffOk(fs, arm.replace("if (probeEnabled)", "if (true)")))
    }

    /** 锚点15：`"r86c"` / sub on|off|stat op 契约；改 op 名 ⇒ FAIL。 */
    @Test
    fun a15_opContract() {
        val rx = rxBlock(rxSrc())
        assertTrue("绿态：r86c 分支 + on/off/stat + 双开关翻转都必须在", opContractOk(rx))
        assertTrue("变异（op 名 r86c→r86x）必须翻红", !opContractOk(rx.replace("\"r86c\" ->", "\"r86x\" ->")))
        assertTrue("变异（stat 缺省值改 none）必须翻红", !opContractOk(rx.replace("?: \"stat\"", "?: \"none\"")))
    }

    /** 锚点16：`md5PrefixOfFile` 复用现成口径（与 SCD fmd5pre 一致）；换自算 MD5 ⇒ FAIL。 */
    @Test
    fun a16_reuseMd5PrefixOfFile() {
        val r0 = r0Block(r0Src())
        assertTrue("绿态：必须复用 md5PrefixOfFile，且探针块内不自算 MD5", md5ReuseOk(r0))
        assertTrue("变异（换成新 MD5 实现）必须翻红",
            !md5ReuseOk(r0.replace("md5PrefixOfFile(file)",
                "MessageDigest.getInstance(\"MD5\").digest(file.readBytes()).take(4).joinToString(\"\")")))
    }

    // ===================================================================== 4 硬锁

    /** 硬锁①：三探针块不出现 set*Field / param.args[..] = / callMethod(...,"j6"|"R5"|"B8"|"send"...)。 */
    @Test
    fun lock1_probesReadOnlyStatic() {
        val fs = fsBlock(fsSrc()); val r0 = r0Block(r0Src()); val rx = rxBlock(rxSrc())
        assertTrue("绿态：三探针块均只读", probesReadOnlyOk(fs, r0, rx))
        assertTrue("变异（FS 写 setObjectField）必须翻红",
            !probesReadOnlyOk(fs.replace("getObjectField(task, \"field_fullpath\")",
                "setObjectField(task, \"field_fullpath\", \"x\")"), r0, rx))
        assertTrue("变异（R0 调 callMethod o8,\"j6\"）必须翻红",
            !probesReadOnlyOk(fs, r0.replace("callMethod(o8, \"a3\"", "callMethod(o8, \"j6\""), rx))
        assertTrue("变异（写 param.args[0]）必须翻红",
            !probesReadOnlyOk(fs.replace("val task = param.args?.getOrNull(0)",
                "param.args[0] = null\n            val task = param.args?.getOrNull(0)"), r0, rx))
    }

    /** 硬锁②：arm() 中 gated 调用必须位于 a3 之前（skip 时仍采到数）。 */
    @Test
    fun lock2_gatedCallBeforeA3() {
        val arm = armBlock(r0Src())
        assertTrue("绿态：gated 调用必须在 a3 之前", gatedBeforeA3Ok(arm))
        assertTrue("变异（删 gated 调用）必须翻红",
            !gatedBeforeA3Ok(arm.replace("            if (probeEnabled) probeR86c(talker, msgSvrId, imgLocalId, o8)\n", "")))
        assertTrue("变异（gated 移到 a3 之后）必须翻红",
            !gatedBeforeA3Ok(arm.replace(
                "            if (probeEnabled) probeR86c(talker, msgSvrId, imgLocalId, o8)\n" +
                    "            val r0 = XposedHelpers.callMethod(o8, \"a3\", talker, msgSvrId)",
                "            val r0 = XposedHelpers.callMethod(o8, \"a3\", talker, msgSvrId)\n" +
                    "            if (probeEnabled) probeR86c(talker, msgSvrId, imgLocalId, o8)")))
    }

    /** 硬锁③：SCD-r86cfs 必须独立 guard，P-FS 日志不得并入 SCD-c2c / SCD-m1e callback。 */
    @Test
    fun lock3_fsIndependentGuard() {
        val src = fsSrc()
        assertTrue("绿态：SCD-r86cfs 独立 guard 且不污染 c2c/m1e", fsIndependentGuardOk(src))
        assertTrue("变异（把 R86C 日志塞进 SCD-c2c callback）必须翻红",
            !fsIndependentGuardOk(src.replace("\"SCD2 c2c in ",
                "\"R86C FS cand SCD2 c2c in ")))
        assertTrue("变异（删掉 SCD-r86cfs guard）必须翻红",
            !fsIndependentGuardOk(src.replace("guard(\"SCD-r86cfs\")", "guard(\"SCD-r86cfsX\")")))
    }

    /** 硬锁④：status() 回显必须含 `R86C FS enabled=`。 */
    @Test
    fun lock4_statusEchoesR86cEnabled() {
        val src = fsSrc()
        assertTrue("绿态：status() 必须回显 R86C FS enabled=", statusEchoOk(src))
        assertTrue("变异（status 回显改名）必须翻红",
            !statusEchoOk(src.replace("R86C FS enabled=", "SCD FS enabled=")))
    }

    // ===================================================================== 判定函数（主测 + 变异共用）

    private fun prefixOk(fs: String, r0: String, rx: String): Boolean =
        listOf(fs, r0, rx).all { b -> stringLiterals(b).any { it.startsWith("R86C") } }

    private fun fsLogAnchorsOk(fs: String): Boolean {
        val l = stringLiterals(fs)
        return l.any { "R86C FS cand " in it } && l.any { "R86C FS verdict " in it }
    }

    private fun r0LogAnchorsOk(r0: String): Boolean {
        val l = stringLiterals(r0)
        val c = codeLines(r0)
        return l.any { "R86C R0ID " in it } &&
            l.any { "R86C R0 verdict " in it } &&
            c.any { "R86C R0" in it && "field=\$f" in it }
    }

    private fun md5CriteriaOk(fs: String, r0: String): Boolean =
        stringLiterals(fs).any { it == "cff4f5a5" } && stringLiterals(r0).any { it == "cff4f5a5" }

    private fun sizeCriteriaOk(fs: String, r0: String): Boolean =
        codeLines(fs).any { "45522L" in it } && codeLines(r0).any { "45522L" in it }

    private fun scanScopeOk(fs: String): Boolean {
        val l = stringLiterals(fs)
        return l.any { ".jpg_hevc" in it } && l.any { "/MicroMsg/" in it } && l.any { "image2" in it }
    }

    private fun fsRootSourceOk(fs: String): Boolean {
        val l = stringLiterals(fs)
        return l.any { "field_fullpath" in it } && l.any { "/MicroMsg/[0-9a-fA-F]{32}/" in it }
    }

    private fun reflectNamesOk(r0: String): Boolean {
        val c = codeLines(r0)
        return c.any { "callMethod(o8, \"a3\", talker, msgSvrId)" in it } &&
            c.any { "callMethod(o8, \"o2\", imgLocalId)" in it } &&
            c.any { "callMethod(o8, \"z3\"" in it }
    }

    private fun fourWayZ3Ok(r0: String): Boolean =
        codeLines(r0).any { "listOf(\"e\", \"f\", \"g\", \"j\")" in it }

    private fun fiveR0FieldsOk(r0: String): Boolean =
        codeLines(r0).any { "listOf(\"e\", \"f\", \"g\", \"j\", \"l\")" in it }

    private fun z3FourArgOk(r0: String): Boolean =
        codeLines(r0).any { "callMethod(o8, \"z3\", raw, \"\", \"\", b)" in it }

    private fun trimOk(fs: String, r0: String): Boolean =
        codeLines(fs).any { "takeLast(16)" in it } &&
            codeLines(r0).any { "takeLast(16)" in it } &&
            codeLines(r0).any { "take(80)" in it }

    private fun identityOk(r0: String): Boolean =
        codeLines(r0).any { "System.identityHashCode" in it }

    private fun defaultOffOk(fs: String, arm: String): Boolean =
        codeLines(fs).any { it == "if (!r86cEnabled) return" } &&
            codeLines(arm).any { "if (probeEnabled) probeR86c(talker, msgSvrId, imgLocalId, o8)" in it }

    private fun opContractOk(rx: String): Boolean {
        val l = stringLiterals(rx)
        val c = codeLines(rx)
        return l.any { it == "r86c" } && l.any { it == "stat" } &&
            l.any { "R86C OP on" in it } && l.any { "R86C OP off" in it } && l.any { "R86C OP stat" in it } &&
            c.any { "turnR86c(true)" in it } && c.any { "turnR86c(false)" in it } &&
            c.any { "turnProbe(true)" in it } && c.any { "turnProbe(false)" in it }
    }

    private fun md5ReuseOk(r0: String): Boolean {
        val c = codeLines(r0)
        return c.any { "md5PrefixOfFile(file)" in it } && c.none { "MessageDigest.getInstance" in it }
    }

    private fun probesReadOnlyOk(fs: String, r0: String, rx: String): Boolean {
        fun one(b: String): Boolean {
            val c = codeLines(b)
            if (c.any { line -> listOf("setObjectField", "setIntField", "setBooleanField").any { it in line } }) {
                return false
            }
            if (c.any { Regex("param\\.args\\[[^]]*\\]\\s*=(?!=)").containsMatchIn(it) }) return false
            if (c.any { Regex("callMethod\\([^)]*\"(j6|R5|B8|send)\"").containsMatchIn(it) }) return false
            return true
        }
        return one(fs) && one(r0) && one(rx)
    }

    private fun gatedBeforeA3Ok(arm: String): Boolean {
        val gated = arm.indexOf("if (probeEnabled) probeR86c(")
        val a3 = arm.indexOf("callMethod(o8, \"a3\", talker, msgSvrId)")
        return gated >= 0 && a3 >= 0 && gated < a3
    }

    private fun fsIndependentGuardOk(src: String): Boolean =
        "guard(\"SCD-r86cfs\")" in src &&
            "R86C" !in c2cBlock(src) && "R86C" !in m1eBlock(src)

    private fun statusEchoOk(src: String): Boolean = "R86C FS enabled=" in statusBlock(src)

    // ===================================================================== 源码读取 / 分块 / 工具

    private fun fsSrc() = source(FS)
    private fun r0Src() = source(R0)
    private fun rxSrc() = source(RX)

    /** 从若干候选父目录向上找源码；兼容 gradle cwd = 仓库根 / wxgroup / wxgroup/app。 */
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

    /** 取 [from, to) 之间的源码片段；标记缺失返回空串（让判定函数自然翻红）。 */
    private fun slice(src: String, from: String, to: String): String {
        val i = src.indexOf(from)
        if (i < 0) return ""
        val j = src.indexOf(to, i + from.length)
        return if (j > i) src.substring(i, j) else ""
    }

    private fun fsBlock(src: String) = slice(src, "guard(\"SCD-r86cfs\")", "guard(\"SCD-g9c\")")
    // R86c 第4轮：resolveOriginFullpath 已物理删除、clear() 改为 clear(wxid: String)，
    // 块边界锚点随重构前移：r0Block 到 sigOf 前（仍含 probeR86c/r0Fields/idh），
    // armBlock 到 turnProbe 前（仍含完整 arm 取证链）。断言内容一字未动。
    private fun r0Block(src: String) =
        slice(src, "private fun probeR86c(", "private fun sigOf(")
    private fun rxBlock(src: String) = slice(src, "\"r86c\" -> {", "\"b6step\" -> {")
    private fun armBlock(src: String) =
        slice(src, "fun arm(talker: String, msgSvrId: Long, imgLocalId: Long)", "fun turnProbe(")
    private fun c2cBlock(src: String) = slice(src, "guard(\"SCD-c2c\")", "guard(\"SCD-r86cfs\")")
    private fun m1eBlock(src: String) = slice(src, "guard(\"SCD-m1e\")", "guard(\"SCD-CDNcb\")")
    private fun statusBlock(src: String) = slice(src, "fun status()", "private fun findCls(")

    /** 只留代码行（去注释/文档行），避免 KDoc 里的死字符串造成假绿。 */
    private fun codeLines(src: String): List<String> = src.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        .toList()

    /** 取代码行中的字符串字面量内容（去外层引号）。 */
    private fun stringLiterals(src: String): List<String> {
        val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        return codeLines(src).flatMap { line ->
            literal.findAll(line).map { it.groupValues[1] }.toList()
        }
    }
}
