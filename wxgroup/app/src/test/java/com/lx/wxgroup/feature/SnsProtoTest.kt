package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R15「朋友圈图片 mediaId 走 protobuf 通用线格式」的解析回归测试。
 *
 * ## 为什么全部**手工构造**字节
 * 真机 BLOB 抓不到（要 adb + 登录态），且**一旦抓来当 fixture 就永远只是那一个版本**。
 * 这里按 protobuf 规范手工拼字节，测的是「字段号 + 线格式」这条不变契约，
 * 微信升级换了混淆类名也照样有效（V1 明确要求）。
 *
 * ## 链路（方案 Q1，DEX 已闭环）
 * ```
 * content --字段6(wt2)--> ContentObj --字段8(wt2, repeated)--> 媒体对象 --字段1(wt2)--> d = mediaId
 * ```
 * tag = `(field shl 3) or wiretype`，wiretype 2 = length-delimited。
 */
class SnsProtoTest {

    // ------------------------------------------------------------ 构造工具

    /** `field:<varint>`（wiretype 0） */
    private fun varint(field: Int, v: Long): ByteArray {
        val out = ArrayList<Byte>()
        out.add(((field shl 3) or 0).toByte())
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) {
                out.add(x.toByte())
                break
            }
            out.add(((x and 0x7F) or 0x80).toByte())
            x = x ushr 7
        }
        return out.toByteArray()
    }

    /** `field:<len><body>`（wiretype 2） */
    private fun bytes(field: Int, body: ByteArray): ByteArray =
        byteArrayOf(((field shl 3) or 2).toByte(), body.size.toByte()) + body

    private fun str(field: Int, s: String): ByteArray = bytes(field, s.toByteArray(Charsets.UTF_8))

    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    /** 一个媒体对象（`vm3`）：只有字段 1 = d */
    private fun media(id: String): ByteArray = str(1, id)

    /** 一条动态的 content：字段6 → ContentObj，ContentObj 里放字段8 的媒体列表 */
    private fun content(vararg ids: String): ByteArray =
        bytes(6, cat(*ids.map { bytes(8, media(it)) }.toTypedArray()))

    // ------------------------------------------------------------ 1. 正常

    @Test
    fun singleMediaIdIsParsed() {
        val b = content("15038644089917813396")
        assertEquals(listOf("15038644089917813396"), MomentsLogic.mediaIdsFromContent(b))
    }

    @Test
    fun mediaIdIsReturnedVerbatim() {
        // 契约：**原值**。不砍前 2 位、不跳前导 0、不加 snsb_ 前缀
        val raw = "15038644089917813396"
        val got = MomentsLogic.mediaIdsFromContent(content(raw))
        assertEquals(raw, got.single())
        // 反证：带前导 0 的输入会触发 fliterPre 加工，若哪天 mediaId 被误当 fliterPre 处理则挂
        val leadingZero = "0015038873180398883448"
        assertEquals(leadingZero, MomentsLogic.mediaIdsFromContent(content(leadingZero)).single())
        assertTrue(MomentsLogic.fliterPre(leadingZero) != leadingZero)
    }

    // ------------------------------------------------------------ 2. 多媒体 + 顺序

    @Test
    fun threeMediaIdsKeepByteOrder() {
        val b = content("1111111111", "2222222222", "3333333333")
        assertEquals(
            listOf("1111111111", "2222222222", "3333333333"),
            MomentsLogic.mediaIdsFromContent(b)
        )
    }

    @Test
    fun orderFollowsBytesNotSorting() {
        // 故意让字典序与字节序相反：结果必须跟字节序走（slot 序号靠它）
        val b = content("999", "111")
        assertEquals(listOf("999", "111"), MomentsLogic.mediaIdsFromContent(b))
    }

    // ------------------------------------------------------------ 3. 空 / 非法（不抛）

    @Test
    fun nullAndEmptyReturnEmpty() {
        assertTrue(MomentsLogic.mediaIdsFromContent(null).isEmpty())
        assertTrue(MomentsLogic.mediaIdsFromContent(ByteArray(0)).isEmpty())
    }

    @Test
    fun truncatedBytesReturnEmpty() {
        val full = content("15038644089917813396")
        // 砍掉尾部一半 ⇒ 长度字段声明超出剩余字节，必须安全返回空
        for (cut in 1 until full.size) {
            val part = full.copyOfRange(0, full.size - cut)
            // 不断言一定空（有可能刚好切在合法边界），只断言**不抛异常**
            MomentsLogic.mediaIdsFromContent(part)
        }
        // 明确的最短截断：只有 tag 没有长度
        assertTrue(MomentsLogic.mediaIdsFromContent(byteArrayOf(0x32)).isEmpty())
        // tag + 长度声明 200 但实际只有 3 字节 ⇒ 越界
        assertTrue(
            MomentsLogic.mediaIdsFromContent(byteArrayOf(0x32, 0xC8.toByte(), 1, 2, 3)).isEmpty()
        )
    }

    @Test
    fun brokenWireFormatReturnsEmpty() {
        // 字段号 0 非法 tag
        assertTrue(MomentsLogic.mediaIdsFromContent(byteArrayOf(0x00, 0x01)).isEmpty())
        // 字段 6 / wiretype 2，但长度 varint 本身被截断（0x80 表示还有后续）
        assertTrue(MomentsLogic.mediaIdsFromContent(byteArrayOf(0x32, 0x80.toByte())).isEmpty())
        // group wiretype 3 不支持
        assertTrue(MomentsLogic.mediaIdsFromContent(byteArrayOf(0x33, 0x34)).isEmpty())
    }

    @Test
    fun missingFieldsReturnEmpty() {
        // 没有字段 6
        assertTrue(MomentsLogic.mediaIdsFromContent(bytes(7, str(1, "x"))).isEmpty())
        // 有字段 6 但 ContentObj 里没有字段 8
        assertTrue(MomentsLogic.mediaIdsFromContent(bytes(6, str(1, "notMediaList"))).isEmpty())
        // 有字段 8，但元素里没有字段 1
        assertTrue(
            MomentsLogic.mediaIdsFromContent(bytes(6, bytes(8, str(2, "other")))).isEmpty()
        )
        // 字段 8 元素里字段 1 是空串 ⇒ 跳过
        assertTrue(MomentsLogic.mediaIdsFromContent(content("")).isEmpty())
    }

    // ------------------------------------------------------------ 4. 干扰字段

    @Test
    fun skipsOtherFieldsAndStillFindsContentObj() {
        // 顶层混入：字段3/wt0(varint)、字段7/wt2(string)、字段9/wt1(fixed64)、字段11/wt5(fixed32)
        val fixed64 = byteArrayOf(0x49, 1, 2, 3, 4, 5, 6, 7, 8)      // field9 wt1
        val fixed32 = byteArrayOf(0x5D, 1, 2, 3, 4)                  // field11 wt5
        val b = cat(
            varint(3, 12345),
            str(7, "noise-noise-noise"),
            fixed64,
            fixed32,
            content("15038644089917813396"),
        )
        assertEquals(listOf("15038644089917813396"), MomentsLogic.mediaIdsFromContent(b))
    }

    @Test
    fun skipsOtherFieldsInsideContentObjAndMedia() {
        // ContentObj 里除字段 8 还有字段 2(wt0) / 字段 5(wt2)
        val obj = cat(
            varint(2, 7),
            str(5, "desc"),
            bytes(8, media("15038644089917813396")),
            bytes(8, media("25038644089917813397")),
        )
        val b = bytes(6, obj)
        assertEquals(
            listOf("15038644089917813396", "25038644089917813397"),
            MomentsLogic.mediaIdsFromContent(b)
        )
    }

    @Test
    fun mediaWithExtraFieldsStillReadsId() {
        // 真实 vm3 有 42 个字段：字段 1=d 之前/之后都有别的字段
        val m = cat(
            varint(2, 4),                 // 例如 D=4（视频）
            str(1, "15038644089917813396"),
            varint(3, 20261024),
            str(5, "https://shmmsns.qpic.cn/mmsns/xxxx/0"),
        )
        val b = bytes(6, bytes(8, m))
        assertEquals(listOf("15038644089917813396"), MomentsLogic.mediaIdsFromContent(b))
    }

    // ------------------------------------------------------------ protoSegments / protoString

    @Test
    fun protoSegmentsReturnsAllMatchingSegments() {
        val b = cat(bytes(8, "a".toByteArray()), bytes(9, "z".toByteArray()), bytes(8, "b".toByteArray()))
        val segs = MomentsLogic.protoSegments(b, 8)
        assertEquals(2, segs.size)
        assertEquals("a", String(segs[0], Charsets.UTF_8))
        assertEquals("b", String(segs[1], Charsets.UTF_8))
    }

    @Test
    fun protoSegmentsRejectsBadFieldNumber() {
        assertTrue(MomentsLogic.protoSegments(str(1, "x"), 0).isEmpty())
        assertTrue(MomentsLogic.protoSegments(str(1, "x"), -1).isEmpty())
    }

    @Test
    fun protoStringFirstOnlyAndNullOnMissing() {
        val b = cat(str(1, "first"), str(1, "second"))
        assertEquals("first", MomentsLogic.protoString(b, 1))
        assertNull(MomentsLogic.protoString(b, 2))
        assertNull(MomentsLogic.protoString(ByteArray(0), 1))
    }

    @Test
    fun protoStringHandlesUtf8() {
        val s = "朋友圈 test"
        assertEquals(s, MomentsLogic.protoString(str(4, s), 4))
    }

    // ------------------------------------------------------------ 5. 端到端：解析 → 候选名

    @Test
    fun endToEndCandidatesFromParsedId() {
        val raw = "0015038873180398883448"
        val ids = MomentsLogic.mediaIdsFromContent(content(raw))
        val mid = ids.single()

        // fliterPre 只跳前导 0，原样保留其余
        assertEquals("15038873180398883448", MomentsLogic.fliterPre(mid))

        // 候选按 原图 > 压缩 > 缩略，且每个前缀都带 pre / 原值两个键
        val cands = MomentsLogic.snsNameCandidates(mid)
        assertEquals("snsb_15038873180398883448", cands[0])
        assertEquals("snsb_0015038873180398883448", cands[1])
        assertEquals("snsu_15038873180398883448", cands[2])
        assertEquals("snst_15038873180398883448", cands[4])

        // 真机文件名 = snsb_ + fliterPre(raw) ⇒ 候选 0 精确命中
        assertEquals(
            "snsb_15038873180398883448",
            MomentsLogic.matchSnsFileName(setOf("snsb_15038873180398883448"), cands[0])
        )
    }

    @Test
    fun endToEndAlreadyTrimmedIdStillHits() {
        // V1 结论：上游即使给的是已砍过的串，双候选也能兜住（原值进候选）
        val trimmed = "15038873180398883448"
        val mid = MomentsLogic.mediaIdsFromContent(content(trimmed)).single()
        val cands = MomentsLogic.snsNameCandidates(mid)
        assertTrue(cands.contains("snsb_$trimmed"))
        assertEquals(
            "snsb_15038873180398883448",
            MomentsLogic.matchSnsFileName(setOf("snsb_15038873180398883448"), "snsb_$trimmed")
        )
    }

    @Test
    fun endToEndMultipleIdsMapToDistinctFiles() {
        val b = content("0015038873180398883448", "0015038873180398883449")
        val ids = MomentsLogic.mediaIdsFromContent(b)
        val names = ids.map { "snsb_" + MomentsLogic.fliterPre(it) }
        assertEquals(listOf("snsb_15038873180398883448", "snsb_15038873180398883449"), names)
        // 两个 id 不能映射到同一个文件（slot 序号才不会互相覆盖）
        assertEquals(names.size, names.toSet().size)
    }

    // ------------------------------------------------------------ 6. 字段路径（R19 真机修正）

    /**
     * **R19 真机实测路径**：`content → 8 → 5 → 1`。
     *
     * ⛔ 这不是猜的：2026-10-25 用 `op=snsg sub=mpath`（递归扫 protobuf 定位
     * ≥18 位纯数字叶子）实测得 `p=1(20),8>5>1(20),8>5>29(19),8>5>32(19)`，
     * 且用设备真实缓存文件名交叉验证过 —— 顶层 `1(20)` 在 41 个缓存文件名里
     * **0 命中**，而 `8>5>1(20)` **4 个精确命中**（`snst_15039549883662348966` 等）。
     *
     * 旧的 `6 → 8 → 1`（R15 定案）在这台设备上**恒取不到**（字段 6 内没有字段 8），
     * 表现就是探针一直报 `ids=0`。
     */
    private fun contentV2(vararg ids: String): ByteArray =
        bytes(8, cat(*ids.map { bytes(5, media(it)) }.toTypedArray()))

    @Test
    fun realDevicePath8_5_1IsParsed() {
        // 真机实测值：snsu_15039549883662348966 命中设备缓存文件
        val b = contentV2("15039549883662348966")
        assertEquals(listOf("15039549883662348966"), MomentsLogic.mediaIdsFromContent(b))
    }

    @Test
    fun realDevicePathMultipleIdsKeepOrder() {
        // 真机同一条动态解出 3 个 mediaId（snsId=-3407194190689520977 ids=3 hit=3）
        val b = contentV2(
            "15039549883662348966",
            "15039549883678929573",
            "15039549883688563348",
        )
        assertEquals(
            listOf(
                "15039549883662348966",
                "15039549883678929573",
                "15039549883688563348",
            ),
            MomentsLogic.mediaIdsFromContent(b),
        )
    }

    @Test
    fun legacyPath6_8_1StillWorksAsFallback() {
        // 旧路径必须仍然能用（抗版本漂移；现有的 23 处用例造的都是旧结构）
        val b = content("15038644089917813396")
        assertEquals(listOf("15038644089917813396"), MomentsLogic.mediaIdsFromContent(b))
    }

    @Test
    fun newPathWinsWhenBothPresent() {
        // 两条路径同时存在时以**新路径**为准（否则真机上会取到旧路径的空/错结果）
        val b = cat(
            bytes(6, cat(bytes(8, media("00000000000000000001")))),
            bytes(8, cat(bytes(5, media("15039549883662348966")))),
        )
        assertEquals(listOf("15039549883662348966"), MomentsLogic.mediaIdsFromContent(b))
    }

    @Test
    fun newPathEmptyFallsBackToLegacy() {
        // 新路径存在但**解不出** id ⇒ 回退旧路径，不能因为主路径为空就放弃
        val b = cat(
            bytes(8, cat(bytes(5, str(2, "not-an-id")))), // 字段5 里没有字段1
            bytes(6, cat(bytes(8, media("15038644089917813396")))),
        )
        assertEquals(listOf("15038644089917813396"), MomentsLogic.mediaIdsFromContent(b))
    }
}
