package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

/**
 * R85 路线 A「严格判据止血」回归锁（JUnit4 / 纯 JVM / 零 Android·Xposed 依赖）。
 *
 * 直接调 [P3AdmitGate.admit]（与 [NativeMenuGate] 同文件、同范式），在 [TemporaryFolder] 造真文件，
 * 覆盖 Pro 文档 §4.3 的 T1~T10 行为级用例 + M1~M4 不变量断言 + 源码锁 S1。
 *
 * 真机事实（决定了下面的数值）：`7ccddbfb...2ac7f.jpg` = 15906B 且无 th_ 兄弟 ⇒ below-floor；
 * 真原图 335644B / 882950B 远大于 [P3AdmitGate.FLOOR] ⇒ 放行；缩略图 4320B / 15906B ⇒ thumb-sibling。
 */
class R85P3AdmitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------ T1~T5 兄弟比 / 前缀

    /**
     * T1：同 md5 的 th_ 兄弟与候选字节全同 ⇒ thumb-sibling（identical 分支）。
     * 样本取 200000B（> FLOOR）：admit 短路顺序 below-floor(3) 在 thumb-sibling(6) 之前，
     * 候选必须先过 floor 才能真正走到兄弟比判据；15906B 会被 below-floor 先拦（那是 T8 的语义）。
     */
    @Test
    fun bareMd5ThumbWithEqualThSiblingRejected() {
        val dir = tmp.newFolder("t1")
        val md = "7ccddbfb97a8b10e4e26765247a2ac7f"
        val candidate = File(dir, "$md.jpg")
        val sibling = File(dir, "th_$md.jpg")
        makeImage(candidate, 200000)
        makeImage(sibling, 200000)
        assertEquals("thumb-sibling", P3AdmitGate.admit(candidate, false))
    }

    /** T2：候选 200000B + 兄弟 54000B（比 3.7 < 8，字节不同）⇒ thumb-sibling；锁住 SIBLING_RATIO=8 的语义。 */
    @Test
    fun bareMd5ThumbWithSmallerThSiblingRejected() {
        val dir = tmp.newFolder("t2")
        val md = "7ccddbfb97a8b10e4e26765247a2ac7f"
        val candidate = File(dir, "$md.jpg")
        val sibling = File(dir, "th_$md.jpg")
        makeImage(candidate, 200000)
        makeImage(sibling, 54000)
        assertEquals("thumb-sibling", P3AdmitGate.admit(candidate, false))
    }

    /** T3：真原图 335644B + th_ 15906B（比 21 > 8）⇒ 放行，回归保护（不得误杀 B/R83 成功态）。 */
    @Test
    fun realOriginalWithThSiblingAdmitted() {
        val dir = tmp.newFolder("t3")
        val md = "216b1f7ccd59b1268349fd0eb3c46801"
        val candidate = File(dir, "$md.jpg")
        makeImage(candidate, 335644)
        makeImage(File(dir, "th_$md.jpg"), 15906)
        assertNull(P3AdmitGate.admit(candidate, false))
    }

    /** T4：335644B 且无 th_ 兄弟 ⇒ 放行（防 C1 过杀）。 */
    @Test
    fun realOriginalWithoutThSiblingAdmitted() {
        val dir = tmp.newFolder("t4")
        val candidate = File(dir, "216b1f7ccd59b1268349fd0eb3c46801.jpg")
        makeImage(candidate, 335644)
        assertNull(P3AdmitGate.admit(candidate, false))
    }

    /** T5：th_ 前缀任意大小（这里取 300000B，远超 floor）⇒ 仍被 th-prefix 挡，先于其它判据。 */
    @Test
    fun thPrefixAlwaysRejected() {
        val dir = tmp.newFolder("t5")
        val f = File(dir, "th_7ccddbfb97a8b10e4e26765247a2ac7f.jpg")
        makeImage(f, 300000)
        assertEquals("th-prefix", P3AdmitGate.admit(f, false))
    }

    // ------------------------------------------------ T6~T9 shape / magic / floor / no-exist

    /** T6：缓存形态 `/cache/_uuid.jpg` strict=true ⇒ bad-shape；strict=false 不受此项约束。 */
    @Test
    fun badShapeRejectedWhenStrict() {
        val dir = tmp.newFolder("t6")
        val f = File(dir, "_a1b2c3d4e5f6.jpg")
        makeImage(f, P3AdmitGate.FLOOR.toInt() + 1) // 合法 JPEG magic + 过 floor，确保只被 shape 挡
        assertEquals("bad-shape", P3AdmitGate.admit(f, true))
        assertNull("strict=false 不查 shape，须放行", P3AdmitGate.admit(f, false))
    }

    /** T7：名义 .jpg 实为 PNG magic（放在 image2 桶内以过 shape）strict=true ⇒ magic-mismatch。 */
    @Test
    fun magicMismatchRejected() {
        val md = "aa11bb22cc33dd44ee55ff6600112233"
        val f = File(tmp.newFolder("t7"), "image2/${md.substring(0, 2)}/${md.substring(2, 4)}/$md.jpg")
        makeImage(f, P3AdmitGate.FLOOR.toInt() + 1, PNG_MAGIC)
        assertEquals("magic-mismatch", P3AdmitGate.admit(f, true))
        assertNull("strict=false 不查 magic，须放行", P3AdmitGate.admit(f, false))
    }

    /**
     * T8：无兄弟 50000B ⇒ below-floor；下限本身（<= FLOOR）同样拒绝；
     * 刚好大于下限（FLOOR+1）放行（边界）。
     *
     * 注：Pro 文档表格写「65536B 放行（边界）」，但 §4.2 锁定的条件是 `length <= 64*1024` 拒绝，
     * 65536 == 64*1024 故必须拒绝；这里按锁定的实现把放行边界取为 FLOOR+1，并显式锁住 FLOOR 自身的拒绝。
     */
    @Test
    fun belowFloorRejected() {
        val md = "bb22cc33dd44ee55ff6600112233445566"
        val small = File(tmp.newFolder("t8a"), "$md.jpg")
        makeImage(small, 50000)
        assertEquals("below-floor", P3AdmitGate.admit(small, false))

        val atFloor = File(tmp.newFolder("t8b"), "cc33dd44ee55ff660011223344556677.jpg")
        makeImage(atFloor, P3AdmitGate.FLOOR.toInt())
        assertEquals("below-floor", P3AdmitGate.admit(atFloor, false))

        val aboveFloor = File(tmp.newFolder("t8c"), "dd33dd44ee55ff660011223344556677.jpg")
        makeImage(aboveFloor, P3AdmitGate.FLOOR.toInt() + 1)
        assertNull("刚好大于下限须放行", P3AdmitGate.admit(aboveFloor, false))
    }

    /** T9：不存在文件 ⇒ no-exist（短路第一档，后面所有检查都不该跑到）。 */
    @Test
    fun nonexistentFileRejected() {
        val f = File(tmp.newFolder("t9"), "ee44ee55ff6600112233445566778899.jpg")
        assertEquals("no-exist", P3AdmitGate.admit(f, true))
    }

    // ------------------------------------------------ T10 只读 + 源码锁 S1

    /** T10：admit 跑前后目录文件集合/字节完全一致，且 gate 源码无写文件/写宿主字段操作。 */
    @Test
    fun admitIsReadOnly() {
        val dir = tmp.newFolder("t10")
        val md = "ff55ff6600112233445566778899001122"
        val f = File(dir, "$md.jpg")
        val sibling = File(dir, "th_$md.jpg")
        makeImage(f, 15906)
        makeImage(sibling, 15906)

        val before = snapshot(dir)
        P3AdmitGate.admit(f, true)
        P3AdmitGate.admit(f, false)
        assertEquals("admit 不得增/删/改名文件", before.first, snapshot(dir).first)
        assertEquals("admit 不得改动文件字节", before.second, snapshot(dir).second)

        val gateBody = functionBody(codeLines(source(FF)), GATE_SIG)
        assertTrue("gate 函数体必须可读到", gateBody.isNotEmpty())
        assertTrue(
            "gate 不得写宿主字段 / param",
            gateBody.none { it.contains("setObjectField") || Regex("param\\.(result|args)").containsMatchIn(it) }
        )
        assertTrue(
            "gate 不得写文件 / 删文件 / 改名（只读判据）",
            gateBody.none { line ->
                listOf("FileOutputStream", ".delete(", ".renameTo(", ".createNewFile(", ".write(")
                    .any { it in line }
            }
        )
    }

    /** S1：6 处调用点全部经 P3AdmitGate.admit，strict 分布 2 true / 4 false，无残留 15KB 门槛字面量。 */
    @Test
    fun allSixCallSitesUseP3Admit() {
        val code = codeLines(source(FF))
        val admitLines = code.filter { "P3AdmitGate.admit(" in it }
        assertEquals("必须恰好 6 处调用点全部收敛到 gate", 6, admitLines.size)
        assertEquals("listener + p3OriginFromMd5 两处 strict=true", 2, admitLines.count { ", true)" in it })
        assertEquals("p3SrcOf + copyP3Temp 准入/复核 + p3ReadyPath 四处 strict=false", 4, admitLines.count { ", false)" in it })
        assertFalse("不得残留旧 >15KB 门槛字面量（FLOOR=64KB 除外）", code.any { "15 * 1024" in it })
        assertFalse("不得残留旧 exists && length 三门槛", code.any { "f.exists() && f.length()" in it })
    }

    // ------------------------------------------------ M1~M4 不变量断言（语义变异防漂移）

    /** M1/M2：兄弟比 8 锁定；删分支或 drift 到 1，T1/T2（3.7×）会被放行。 */
    @Test
    fun siblingRatioLockedAgainstDrift() {
        assertEquals("SIBLING_RATIO 必须为 8（M1 删分支 / M2 改 8→1 都应判红）", 8, P3AdmitGate.SIBLING_RATIO)
    }

    /** M5：下限 64KB 锁定；若退回 15KB，T1（15906B）与 T8（50000B）会被放行。 */
    @Test
    fun floorLockedAgainstDrift() {
        assertEquals("FLOOR 必须为 64*1024（M5 退回 15KB 必须判红）", 64 * 1024L, P3AdmitGate.FLOOR)
    }

    /** M3：strictShape 必须真正参与判据——同一缓存文件 strict=true 红、false 绿。 */
    @Test
    fun shapeStrictnessIsRealDiscriminator() {
        val f = File(tmp.newFolder("m3"), "_no_bucket.jpg")
        makeImage(f, P3AdmitGate.FLOOR.toInt() + 1)
        assertEquals("strict=true 必须有 bad-shape；跳过 shape 检查的变异会放行", "bad-shape", P3AdmitGate.admit(f, true))
        assertNull("strict=false 必须放行，证明该码仅由 strictShape 引入", P3AdmitGate.admit(f, false))
    }

    /** M4：magic 检查必须真正参与判据——image2 桶内名义 .jpg 实为 PNG 时 strict=true 红、false 绿。 */
    @Test
    fun magicCheckIsRealDiscriminator() {
        val md = "ee55ff66001122334455667788990011"
        val f = File(tmp.newFolder("m4"), "image2/${md.substring(0, 2)}/${md.substring(2, 4)}/$md.jpg")
        makeImage(f, P3AdmitGate.FLOOR.toInt() + 1, PNG_MAGIC)
        assertEquals("strict=true 必须有 magic-mismatch；删 magic 检查的变异会放行", "magic-mismatch", P3AdmitGate.admit(f, true))
        assertNull("strict=false 必须放行，证明该码仅由 strictShape 引入", P3AdmitGate.admit(f, false))
    }

    // ------------------------------------------------ 工具

    companion object {
        const val FF = "app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt"
        const val GATE_SIG = "object P3AdmitGate"
        val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
    }

    /** 造真文件：写 magic 后用 0 补足到 size（size 必须 >= magic.size）。 */
    private fun makeImage(file: File, size: Int, magic: ByteArray = JPEG_MAGIC) {
        require(size >= magic.size)
        file.parentFile?.mkdirs()
        val chunk = ByteArray(8192)
        FileOutputStream(file).use { out ->
            out.write(magic)
            var remaining = size - magic.size
            while (remaining > 0) {
                val n = minOf(remaining, chunk.size)
                out.write(chunk, 0, n)
                remaining -= n
            }
        }
    }

    /** 目录快照：(名字→长度) 列表 + 每个文件的字节哈希列表。 */
    private fun snapshot(dir: File): Pair<List<Pair<String, Long>>, List<Int>> {
        val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        return files.map { it.name to it.length() } to files.map { it.readBytes().contentHashCode() }
    }

    /** 与 R83OriginByMd5Test 同构：兼容 gradle cwd = 仓库根 / wxgroup / wxgroup/app 三种情况。 */
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

    /** 只留代码行（去注释/文档行），避免 KDoc 里的历史串造成假绿。 */
    private fun codeLines(src: String): List<String> = src.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        .toList()

    /** 从签名行起按大括号配平取函数体（含签名行）。 */
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
