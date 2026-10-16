package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * [MomentsLogic] 的回归测试。
 *
 * 覆盖的是**需求里写死、改错了要重上真机**的那几件事：
 * 日期目录格式 `yyyy-MM-dd-HHmm`、`昵称(wxid)` 目录名、下次刷新时刻的算法、
 * 导出名单命中判定、BLOB 正文抽取。
 *
 * 用例里的 snsId 用的是真机抓到的那个值（见 SnsGuard 头注释）。
 */
class MomentsLogicTest {

    private val SH = ZoneId.of("Asia/Shanghai")

    /** 真机的 snsId（SnsGuard 头注释里的那条） */
    private val REAL_SNS = -3447438253133393222L

    // ------------------------------------------------------------------ 日期目录

    @Test
    fun dayDirIsRequirementFormat() {
        // 2026-06-10 05:16 (UTC+8) = epoch 1770... 用 ZonedDateTime 反推，避免手算时区
        val ms = java.time.ZonedDateTime.of(2026, 6, 10, 5, 16, 0, 0, SH)
            .toInstant().toEpochMilli()
        assertEquals("2026-06-10-0516", MomentsLogic.dayDir(ms, SH))
    }

    @Test
    fun dayDirPadsAndRollsOverMidnight() {
        val a = java.time.ZonedDateTime.of(2026, 1, 2, 3, 4, 0, 0, SH).toInstant().toEpochMilli()
        assertEquals("2026-01-02-0304", MomentsLogic.dayDir(a, SH))
        val b = java.time.ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, SH).toInstant().toEpochMilli()
        assertEquals("2026-12-31-2359", MomentsLogic.dayDir(b, SH))
    }

    @Test
    fun dayDirNeverThrowsOnJunk() {
        // 0 / 负数 / 极大值都不能抛：导出时崩在目录名上，整批都出不来
        assertTrue(MomentsLogic.dayDir(0L, SH).matches(Regex("""\d{4}-\d{2}-\d{2}-\d{4}""")))
        assertTrue(MomentsLogic.dayDir(-1L, SH).isNotEmpty())
        assertTrue(MomentsLogic.dayDir(Long.MAX_VALUE / 2, SH).isNotEmpty())
    }

    // ------------------------------------------------------------------ 用户目录

    @Test
    fun userDirIsNickThenWxid() {
        assertEquals("张三(wxid_abc123)", MomentsLogic.userDir("张三", "wxid_abc123"))
    }

    @Test
    fun userDirCleansIllegalChars() {
        // 昵称里带 '/' ':' 会让 mkdirs 静默失败（真机上表现为导出目录空空如也）
        assertEquals("A_B_C(wxid_x)", MomentsLogic.userDir("A/B:C", "wxid_x"))
        // 制表符在 ILLEGAL 正则里（目录名带 tab 会惹麻烦）→ 归成下划线
        assertEquals("张_三(wxid_x)", MomentsLogic.userDir("张\t三", "wxid_x"))
        // 结尾的点在 Windows/FAT 上会出事
        assertEquals("abc(wxid_x)", MomentsLogic.userDir("abc.", "wxid_x"))
    }

    @Test
    fun userDirFallsBackWhenOneSideMissing() {
        assertEquals("wxid_only", MomentsLogic.userDir(null, "wxid_only"))
        assertEquals("wxid_only", MomentsLogic.userDir("   ", "wxid_only"))
        assertEquals("只有昵称", MomentsLogic.userDir("只有昵称", null))
        assertEquals(MomentsLogic.UNKNOWN_USER, MomentsLogic.userDir(null, null))
        assertEquals(MomentsLogic.UNKNOWN_USER, MomentsLogic.userDir("///", ":::"))
    }

    @Test
    fun userDirTruncatesLongNick() {
        val long = "a".repeat(100)
        val dir = MomentsLogic.userDir(long, "wxid_x")
        assertTrue(dir.startsWith("aaaa"))
        assertTrue(dir.length <= 32 + 1 + 6 + 1)
        assertTrue(dir.endsWith("(wxid_x)"))
    }

    @Test
    fun exportDirJoinsThreeLevels() {
        val ms = java.time.ZonedDateTime.of(2026, 6, 10, 5, 16, 0, 0, SH).toInstant().toEpochMilli()
        assertEquals(
            "/sdcard/moments/张三(wxid_abc)/2026-06-10-0516",
            MomentsLogic.exportDir("/sdcard/moments/", "张三", "wxid_abc", ms, SH)
        )
        // root 带不带尾斜杠都一样
        assertEquals(
            "/sdcard/moments/张三(wxid_abc)/2026-06-10-0516",
            MomentsLogic.exportDir("/sdcard/moments", "张三", "wxid_abc", ms, SH)
        )
    }

    // ------------------------------------------------------------------ 文件名 ↔ snsId

    @Test
    fun textFileNameRoundTrip() {
        val name = MomentsLogic.textFileName(REAL_SNS)
        assertEquals("-3447438253133393222.txt", name)
        assertEquals(REAL_SNS, MomentsLogic.parseExportedId(name))
    }

    @Test
    fun parseExportedIdRejectsJunk() {
        assertNull(MomentsLogic.parseExportedId("IMG_20260610.jpg"))
        assertNull(MomentsLogic.parseExportedId("123456789012345678901.txt")) // 21 位
        assertNull(MomentsLogic.parseExportedId("-"))
        assertNull(MomentsLogic.parseExportedId(""))
        // 无符号写法不认：导出与归档统一用有符号，混用会导致重复导出
        assertNull(MomentsLogic.parseExportedId("15004343040049746612.txt"))
    }

    @Test
    fun createTimeSecondsVsMillis() {
        assertEquals(1_700_000_000_000L, MomentsLogic.millisOf(1_700_000_000L))
        // 已经是毫秒就别再乘
        assertEquals(1_700_000_000_000L, MomentsLogic.millisOf(1_700_000_000_000L))
        assertEquals(0L, MomentsLogic.millisOf(0L))
        assertEquals(0L, MomentsLogic.millisOf(-5L))
    }

    // ------------------------------------------------------------------ BLOB 正文

    @Test
    fun extractTextPicksLongestReadableRun() {
        val blob = (
            byteArrayOf(0x0A, 0x12, 0x00, 0x01) +
                "今天天气不错".toByteArray(Charsets.UTF_8) +
                byteArrayOf(0x00, 0x02, 0x7F) +
                "abc".toByteArray(Charsets.UTF_8) +
                byteArrayOf(0x00)
            )
        assertEquals("今天天气不错", MomentsLogic.extractText(blob))
    }

    @Test
    fun extractTextPrefersNonUrlOnSameLength() {
        val blob = (
            "http://wx.qlogo.cn/a/b".toByteArray(Charsets.UTF_8) +
                byteArrayOf(0x00) +
                "1234567890123456789".toByteArray(Charsets.UTF_8) + // 同长度的非 URL
                byteArrayOf(0x00)
            )
        assertEquals("1234567890123456789", MomentsLogic.extractText(blob))
    }

    @Test
    fun extractTextEmptyWhenNoReadableRun() {
        assertEquals("", MomentsLogic.extractText(null))
        assertEquals("", MomentsLogic.extractText(byteArrayOf()))
        assertEquals("", MomentsLogic.extractText(byteArrayOf(0x00, 0x01, 0x02)))
        // 纯标点不算正文
        assertEquals("", MomentsLogic.extractText("！！！...".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun extractUrlsFromBlob() {
        val blob = (
            "\u0001正文\u0002https://mmbiz.qpic.cn/mmbiz_jpg/abc/640?wx_fmt=jpeg\u0000" +
                "http://wx.qlogo.cn/mmhead/x_y"
            ).toByteArray(Charsets.UTF_8)
        val urls = MomentsLogic.extractUrls(blob)
        assertTrue(urls.any { it.startsWith("https://mmbiz.qpic.cn") })
        assertTrue(urls.any { it.startsWith("http://wx.qlogo.cn") })
        // 结尾标点要被剃掉，否则对不上真实文件名
        assertTrue(urls.none { it.endsWith(",") || it.endsWith(".") })
    }

    @Test
    fun mediaKeysIncludeSignedUnsignedAndUrlTail() {
        val keys = MomentsLogic.mediaKeys(REAL_SNS, listOf("https://x.cn/p/abcdef?w=1"))
        assertTrue(keys.contains("-3447438253133393222"))
        // ⚠️ 无符号值**不要手算**（手写常量极易错，原版这里就写错过一个）。
        // 2^64 - 3447438253133393222 = 14999305820576158394（> Long.MAX_VALUE，不能写 L 字面量）
        assertEquals("14999305820576158394", java.lang.Long.toUnsignedString(REAL_SNS))
        assertTrue(keys.contains("abcdef"))
        assertTrue(keys.contains(MomentsLogic.md5Hex("https://x.cn/p/abcdef?w=1")))
    }

    @Test
    fun md5HexIs32Chars() {
        assertEquals(32, MomentsLogic.md5Hex("abc").length)
        assertEquals(MomentsLogic.md5Hex("abc"), MomentsLogic.md5Hex("abc"))
        assertFalse(MomentsLogic.md5Hex("abc") == MomentsLogic.md5Hex("abd"))
    }

    // ------------------------------------------------------------------ text 文本

    @Test
    fun renderTextHasHeaderAndBody() {
        val item = MomentsLogic.Item(
            snsId = REAL_SNS, wxid = "wxid_abc", nick = "张三",
            createTime = 1_700_000_000L, type = 1, sourceType = 0, localFlag = 16,
            content = "\u0000今天天气不错\u0001".toByteArray(Charsets.UTF_8),
        )
        val t = MomentsLogic.renderText(item, SH)
        assertTrue(t.contains("snsId=-3447438253133393222"))
        assertTrue(t.contains("作者=张三(wxid_abc)"))
        assertTrue(t.contains("今天天气不错"))
        assertTrue(t.contains("类型=1"))
        // 1_700_000_000 = 2023-11-14 22:13:20 **UTC** = 2023-11-15 06:13 (UTC+8)。
        // ⚠️ 别把 UTC 时刻当成本地时间 —— 之前这条断言就是这么错的（差 8 小时）。
        // 2023-11-15 06:13 (UTC+8)。⚠️ JUnit4 的 assertTrue 是 (message, condition) 顺序，
        // 写成 assertTrue(x) { msg } 会被解析成 (String, boolean) → 编译不过
        assertTrue("实际文本:\n$t", t.contains("时间=2023-11-15 06:13"))
    }

    @Test
    fun renderTextDegradesWhenBodyUnreadable() {
        val item = MomentsLogic.Item(
            snsId = 1L, wxid = null, nick = null, createTime = 0L,
            type = 0, sourceType = 0, localFlag = 0, content = byteArrayOf(0x00, 0x01),
        )
        val t = MomentsLogic.renderText(item, SH)
        assertTrue(t.contains("作者=unknown"))
        assertTrue(t.contains("时间=未知"))
        assertTrue(t.contains("(正文未能解析"))
    }

    // ------------------------------------------------------------------ 导出名单

    @Test
    fun emptyListMatchesEverything() {
        assertTrue(MomentsLogic.listHit(emptyList(), "wxid_a", "张三"))
        assertTrue(MomentsLogic.listHit(emptyList(), null, null))
    }

    @Test
    fun listHitByWxidAndByNick() {
        val list = listOf("wxid_a", "李四")
        assertTrue(MomentsLogic.listHit(list, "wxid_a", "张三"))
        assertTrue(MomentsLogic.listHit(list, "wxid_z", "李四"))
        assertFalse(MomentsLogic.listHit(list, "wxid_z", "王五"))
    }

    @Test
    fun listHitAcceptsNickParenWxidForm() {
        val list = listOf("李四(wxid_b)")
        assertTrue(MomentsLogic.listHit(list, "wxid_b", null))
        assertTrue(MomentsLogic.listHit(list, null, "李四"))
        assertFalse(MomentsLogic.listHit(list, "wxid_c", "张三"))
    }

    @Test
    fun listHitIsCaseInsensitiveAndTrimmed() {
        assertTrue(MomentsLogic.listHit(listOf(" wxid_A "), "wxid_a", null))
        assertTrue(MomentsLogic.listHit(listOf("WXID_A"), "wxid_a", null))
    }

    @Test
    fun listHitFalseWhenIdentityUnknown() {
        // 作者都拿不到时不能往 unknown 目录里塞，宁可漏
        assertFalse(MomentsLogic.listHit(listOf("wxid_a"), null, null))
        assertFalse(MomentsLogic.listHit(listOf("wxid_a"), "", " "))
    }

    // ------------------------------------------------------------------ 调度

    @Test
    fun clampHoursBounds() {
        assertEquals(1, MomentsLogic.clampHours(0))
        assertEquals(1, MomentsLogic.clampHours(-5))
        assertEquals(24, MomentsLogic.clampHours(999))
        assertEquals(3, MomentsLogic.clampHours(3))
    }

    @Test
    fun envOkIsOrOfChargingAndWifi() {
        assertTrue(MomentsLogic.envOk(charging = true, wifi = false))
        assertTrue(MomentsLogic.envOk(charging = false, wifi = true))
        assertFalse(MomentsLogic.envOk(charging = false, wifi = false))
    }

    private val NOW = 1_700_000_000_000L
    private val H = 3 * 3_600_000L

    @Test
    fun nextRefreshAtNeverDoneFiresNow() {
        assertEquals(NOW, MomentsLogic.nextRefreshAt(0L, 3, false, false, NOW))
    }

    @Test
    fun nextRefreshAtBeforeDueReturnsDue() {
        val last = NOW - 1_000L
        assertEquals(last + H, MomentsLogic.nextRefreshAt(last, 3, false, false, NOW))
    }

    @Test
    fun nextRefreshAtDueAndEnvOkFiresNow() {
        val last = NOW - H
        assertEquals(NOW, MomentsLogic.nextRefreshAt(last, 3, charging = true, wifi = false, now = NOW))
        assertEquals(NOW, MomentsLogic.nextRefreshAt(last, 3, charging = false, wifi = true, now = NOW))
    }

    @Test
    fun nextRefreshAtDueButEnvBadRetriesLater() {
        val last = NOW - H
        val next = MomentsLogic.nextRefreshAt(last, 3, false, false, NOW)
        assertEquals(NOW + MomentsLogic.RETRY_MS, next)
        // 到期状态不会因为重试而丢失：下一次判定仍然立刻到期
        assertTrue(NOW + MomentsLogic.RETRY_MS >= MomentsLogic.dueAt(last, 3))
    }

    @Test
    fun nextRefreshAtClampsInterval() {
        val last = NOW - 1_000L
        assertEquals(last + 24 * 3_600_000L, MomentsLogic.nextRefreshAt(last, 999, false, false, NOW))
        assertEquals(last + 3_600_000L, MomentsLogic.nextRefreshAt(last, 0, false, false, NOW))
    }

    // ------------------------------------------------------------------ 自动加载 / 导出节奏

    @Test
    fun loadMoreDueOnlyNearEnd() {
        val now = 10_000L
        // 贴底（最后 3 条之内）才触发
        assertTrue(MomentsLogic.loadMoreDue(pos = 27, size = 30, 0L, now, 0))
        assertTrue(MomentsLogic.loadMoreDue(pos = 29, size = 30, 0L, now, 0))
        // 差一条都不行：pos=26 距末尾还有 3 条
        assertFalse(MomentsLogic.loadMoreDue(pos = 26, size = 30, 0L, now, 0))
        assertFalse(MomentsLogic.loadMoreDue(pos = 0, size = 30, 0L, now, 0))
    }

    @Test
    fun loadMoreDueRejectsJunkPositions() {
        assertFalse(MomentsLogic.loadMoreDue(pos = 0, size = 0, 0L, 100L, 0))
        assertFalse(MomentsLogic.loadMoreDue(pos = -1, size = 30, 0L, 100L, 0))
    }

    @Test
    fun loadMoreDueThrottlesAndCapsSession() {
        val now = 10_000L
        // 节流：1.5s 内的第二次不触发
        assertFalse(MomentsLogic.loadMoreDue(29, 30, now - 500, now, 0))
        assertTrue(MomentsLogic.loadMoreDue(29, 30, now - 1_600, now, 0))
        // 每轮上限：达到 30 次后哪怕贴底+过了节流也不再触发
        assertFalse(MomentsLogic.loadMoreDue(29, 30, now - 9_000, now, 30))
        assertTrue(MomentsLogic.loadMoreDue(29, 30, now - 9_000, now, 29))
    }

    @Test
    fun exportDueFirstRunImmediateThenSpaced() {
        val now = 1_000_000L
        // 从没导出过 → 立刻
        assertTrue(MomentsLogic.exportDue(0L, now))
        assertTrue(MomentsLogic.exportDue(-1L, now))
        // 10 分钟内不重复跑
        assertFalse(MomentsLogic.exportDue(now - 60_000L, now))
        // 到点就跑
        assertTrue(MomentsLogic.exportDue(now - MomentsLogic.EXPORT_MIN_INTERVAL_MS, now))
        assertTrue(MomentsLogic.exportDue(now - MomentsLogic.EXPORT_MIN_INTERVAL_MS - 1, now))
    }
}
