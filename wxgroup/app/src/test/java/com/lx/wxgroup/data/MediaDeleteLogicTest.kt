package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaDeleteLogic] 的判据回归测试（零 Android 依赖，直接跑 JVM）。
 *
 * 用例矩阵来源：`docs/_r49_revokeimg_reference.md:315-343`（Q8 七组用例矩阵 + 变异验证点）；
 * 形态样本沿用 `VoiceGuardLogicTest.kt:20-28` 的真机 26 位 hex 与 `MediaGuardLogic.kt:40-55` 的 32 位 md5。
 *
 * ⛔ 每条用例都针对一个「看似合理的错误实现」（详见 `docs/_r51_media_delete_logic.md` S3 矩阵）：
 * 白名单用 `contains`、子查询/清理形态被拦、26/32 长度统一成一个正则、TTL 边界写成 `<=`、
 * `shouldBlock` 恒 false / 恒 true、`verdict` 把三态写成并列布尔。用例必须能区分正确实现与这些错误实现。
 */
class MediaDeleteLogicTest {

    private val MD5_A = "0123456789abcdef0123456789abcdef"          // 32 位 md5（图片 token）
    private val MD5_B = "fedcba9876543210fedcba9876543210"
    private val MISS32 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"           // 32 位，但不在保护集
    private val HEX26 = "46142710252654b6378ddb4103"                 // 26 位语音 imgPath（真机样本）
    private val HEX31 = "0123456789abcdef0123456789abcde"            // 31 位
    private val HEX33 = "0123456789abcdef0123456789abcdef0"          // 33 位
    private val HEX34 = "46142710252654b6378ddb4103deadbeef"         // 34 位
    private val BAD = "not-a-hex"
    private val NUM = "19289041"

    private val PROTECTED = setOf(MD5_A, MD5_B)

    /** 窗口内基准：now-issued=4000 < ttl=10000 */
    private fun block(
        table: String?,
        w: String?,
        args: Array<out Any?>?,
        protected: Set<String> = PROTECTED,
        now: Long = 5_000L,
        issued: Long = 1_000L,
        ttl: Long = 10_000L,
    ): Boolean = MediaDeleteLogic.shouldBlock(table, w, args, protected, now, issued, ttl)

    private fun cls(
        table: String?,
        w: String?,
        args: Array<out Any?>?,
        protected: Set<String> = emptySet(),
    ): DeleteClass = MediaDeleteLogic.classifyDelete(table, w, args, protected)

    // ---------------------------------------------------------------- 1. 表名白名单

    @Test
    fun targetTablesAreRecognized() {
        assertTrue(MediaDeleteLogic.isTargetTable("ImgInfo2"))
        assertTrue(MediaDeleteLogic.isTargetTable("imginfo2"))
        assertTrue(MediaDeleteLogic.isTargetTable("VIDEOINFO2"))
        assertTrue(MediaDeleteLogic.isTargetTable("WxFileIndex2"))
        assertTrue(MediaDeleteLogic.isTargetTable("wxfileindex2"))
        assertTrue(MediaDeleteLogic.isTargetTable("  ImgInfo2  "))
    }

    @Test
    fun nonTargetTablesAreRejected() {
        // ⛔ voiceinfo 必须不命中：语音由既有 VoiceGuard 管，两个回调不能重复拦
        assertFalse(MediaDeleteLogic.isTargetTable("voiceinfo"))
        assertFalse(MediaDeleteLogic.isTargetTable("VOICEINFO"))
        assertFalse(MediaDeleteLogic.isTargetTable("message"))
        assertFalse(MediaDeleteLogic.isTargetTable("rconversation"))
        assertFalse(MediaDeleteLogic.isTargetTable("ImgInfo2X"))
        assertFalse(MediaDeleteLogic.isTargetTable("imgInfo2_backup"))
        assertFalse(MediaDeleteLogic.isTargetTable(null))
        assertFalse(MediaDeleteLogic.isTargetTable(""))
        assertFalse(MediaDeleteLogic.isTargetTable("   "))
    }

    @Test
    fun quotedAndWhitespaceTableVariantsAreRecognized() {
        assertTrue(MediaDeleteLogic.isTargetTable("\"ImgInfo2\""))
        assertTrue(MediaDeleteLogic.isTargetTable("[ImgInfo2]"))
        assertTrue(MediaDeleteLogic.isTargetTable("`VIDEOINFO2`"))
        assertTrue(MediaDeleteLogic.isTargetTable("  WxFileIndex2  "))
    }

    // ---------------------------------------------------------------- 2. classifyDelete 四态

    @Test
    fun singleEqWithArgIsBlockable() {
        assertEquals(DeleteClass.BLOCKABLE, cls("ImgInfo2", "msglocalid=?", arrayOf(MD5_A)))
        assertEquals(DeleteClass.BLOCKABLE, cls("ImgInfo2", "bigImgPath = ?", arrayOf(MD5_B)))
    }

    @Test
    fun singleEqInlineLiteralIsBlockable() {
        // 不带 whereArgs：值内联进 whereClause，且从路径形态里抽 md5
        assertEquals(
            DeleteClass.BLOCKABLE,
            cls("ImgInfo2", "bigImgPath = 'THUMBNAIL_DIRPATH://th_$MD5_A'", null),
        )
        assertEquals(DeleteClass.BLOCKABLE, cls("ImgInfo2", "thumbImgPath = '$MD5_B'", null))
    }

    @Test
    fun filenameEqForVideoinfo2IsBlockable() {
        assertEquals(DeleteClass.BLOCKABLE, cls("videoinfo2", "filename=?", arrayOf(MD5_A)))
        assertEquals(DeleteClass.BLOCKABLE, cls("videoinfo2", "filename = '$MD5_B'", null))
    }

    @Test
    fun nullWhereClauseIsBulkOrUnsafe() {
        // delete("T", null, null) = 删全表，绝不拦
        for (t in listOf("ImgInfo2", "videoinfo2", "WxFileIndex2")) {
            assertEquals(DeleteClass.BULK_OR_UNSAFE, cls(t, null, arrayOf(MD5_A)))
        }
        assertEquals(DeleteClass.BULK_OR_UNSAFE, cls("ImgInfo2", null, null))
    }

    @Test
    fun comparisonOperatorsAreBulkOrUnsafe() {
        for (c in listOf(
            "msglocalid < ?", "msglocalid > ?", "msglocalid <= ?",
            "msglocalid >= ?", "msglocalid != ?", "msglocalid <> ?",
        )) {
            assertEquals(DeleteClass.BULK_OR_UNSAFE, cls("ImgInfo2", c, arrayOf(MD5_A)))
        }
    }

    @Test
    fun likeAndIsNullAreBulkOrUnsafe() {
        for (c in listOf("fileName LIKE ?", "bigImgPath IS NULL", "bigImgPath IS NOT NULL")) {
            assertEquals(DeleteClass.BULK_OR_UNSAFE, cls("ImgInfo2", c, arrayOf(MD5_A)))
        }
    }

    @Test
    fun subqueryInSelectIsBulkOrUnsafe() {
        // ⛔ 保守性红线：IN (SELECT …) 是删会话/清空记录，拦它会破坏微信 GC
        val c = "msglocalid IN (SELECT msgId FROM message WHERE talker IN (?))"
        assertEquals(DeleteClass.BULK_OR_UNSAFE, cls("ImgInfo2", c, arrayOf(MD5_A)))
        assertEquals(DeleteClass.BULK_OR_UNSAFE, cls("ImgInfo2", c, null))
    }

    @Test
    fun inLiteralListIsBulkOrUnsafe() {
        // 有意收紧：IN 字面量多值也放行（比 VoiceGuardLogic 对 voiceinfo 更保守）
        assertEquals(
            DeleteClass.BULK_OR_UNSAFE,
            cls("ImgInfo2", "msglocalid IN (?,?)", arrayOf(MD5_A, MD5_B)),
        )
        assertEquals(
            DeleteClass.BULK_OR_UNSAFE,
            cls("videoinfo2", "filename IN (?, ?, ?)", null),
        )
    }

    @Test
    fun otherTableIsOtherTableClass() {
        assertEquals(DeleteClass.OTHER_TABLE, cls("voiceinfo", "filename=?", arrayOf(MD5_A)))
        assertEquals(DeleteClass.OTHER_TABLE, cls("message", "filename=?", arrayOf(MD5_A)))
        assertEquals(DeleteClass.OTHER_TABLE, cls(null, "filename=?", arrayOf(MD5_A)))
    }

    @Test
    fun andOrMultiConditionIsBulkOrUnsafe() {
        assertEquals(
            DeleteClass.BULK_OR_UNSAFE,
            cls("ImgInfo2", "msglocalid=? AND status=?", arrayOf(MD5_A)),
        )
        assertEquals(
            DeleteClass.BULK_OR_UNSAFE,
            cls("ImgInfo2", "msglocalid=? OR 1=1", arrayOf(MD5_A)),
        )
    }

    @Test
    fun unknownColumnIsUnknown() {
        // status/talker 不是「能抽出 token 的键列」⇒ 不确定 ⇒ 放行
        assertEquals(DeleteClass.UNKNOWN, cls("ImgInfo2", "status=?", arrayOf(MD5_A)))
        assertEquals(DeleteClass.UNKNOWN, cls("ImgInfo2", "talker=?", arrayOf(MD5_A)))
    }

    @Test
    fun missingArgForPlaceholderIsUnknown() {
        assertEquals(DeleteClass.UNKNOWN, cls("ImgInfo2", "msglocalid=?", null))
        assertEquals(DeleteClass.UNKNOWN, cls("ImgInfo2", "msglocalid=?", arrayOf<Any?>(null)))
    }

    @Test
    fun nonExtractableTokenIsUnknown() {
        assertEquals(DeleteClass.UNKNOWN, cls("ImgInfo2", "msglocalid=?", arrayOf(BAD)))
        assertEquals(DeleteClass.UNKNOWN, cls("ImgInfo2", "msglocalid=?", arrayOf(NUM)))
    }

    @Test
    fun protectedMissIsUnknown() {
        assertEquals(DeleteClass.UNKNOWN, cls("ImgInfo2", "msglocalid=?", arrayOf(MD5_A), setOf(MISS32)))
    }

    @Test
    fun protectedHitIsBlockable() {
        assertEquals(DeleteClass.BLOCKABLE, cls("ImgInfo2", "msglocalid=?", arrayOf(MD5_A), setOf(MD5_A)))
        // 大写归一后仍命中
        assertEquals(
            DeleteClass.BLOCKABLE,
            cls("ImgInfo2", "msglocalid=?", arrayOf(MD5_A.uppercase()), setOf(MD5_A)),
        )
    }

    @Test
    fun multipleWhereArgsIsBulkOrUnsafe() {
        assertEquals(
            DeleteClass.BULK_OR_UNSAFE,
            cls("ImgInfo2", "msglocalid=?", arrayOf(MD5_A, MD5_B)),
        )
    }

    // ---------------------------------------------------------------- 3. extractTokens

    @Test
    fun extractTokensFromArgsAndInline() {
        // args + whereClause 内联双通道
        assertEquals(
            setOf(MD5_A, MD5_B),
            MediaDeleteLogic.extractTokens("ImgInfo2", "bigImgPath = '$MD5_B'", arrayOf(MD5_A)),
        )
    }

    @Test
    fun extractTokensUppercaseNormalized() {
        assertEquals(
            setOf(MD5_A),
            MediaDeleteLogic.extractTokens("ImgInfo2", "msglocalid=?", arrayOf(MD5_A.uppercase())),
        )
    }

    @Test
    fun extractTokensRejects26HexOnImageTable() {
        // ⛔ 26 位 token 不能命中图片表（32 位口径）
        assertEquals(
            emptySet<String>(),
            MediaDeleteLogic.extractTokens("ImgInfo2", "msglocalid=?", arrayOf(HEX26)),
        )
    }

    @Test
    fun extractTokensRejects32HexOnVoiceTable() {
        // ⛔ 32 位 token 不能命中语音表（26 位口径）
        assertEquals(
            emptySet<String>(),
            MediaDeleteLogic.extractTokens("voiceinfo", "filename=?", arrayOf(MD5_A)),
        )
    }

    @Test
    fun extractTokensVoiceTableUses26() {
        assertEquals(
            setOf(HEX26),
            MediaDeleteLogic.extractTokens("voiceinfo", "filename=?", arrayOf(HEX26)),
        )
    }

    @Test
    fun extractTokensRejectsWrongLengthAndIllegalChars() {
        for (v in listOf(HEX31, HEX33, HEX34, BAD, NUM)) {
            assertEquals(
                emptySet<String>(),
                MediaDeleteLogic.extractTokens("ImgInfo2", "x", arrayOf(v)),
            )
        }
    }

    @Test
    fun extractTokensHandlesNullArgs() {
        assertEquals(
            emptySet<String>(),
            MediaDeleteLogic.extractTokens("ImgInfo2", "msglocalid=?", arrayOf<Any?>(null, "  ", "xyz")),
        )
    }

    @Test
    fun extractTokensIgnoresUnknownTable() {
        assertEquals(
            emptySet<String>(),
            MediaDeleteLogic.extractTokens("message", "x", arrayOf(MD5_A)),
        )
        assertEquals(
            emptySet<String>(),
            MediaDeleteLogic.extractTokens(null, "x", arrayOf(MD5_A)),
        )
    }

    // ---------------------------------------------------------------- 4. isRevocationWindow

    @Test
    fun revocationWindowInsideIsTrue() {
        assertTrue(MediaDeleteLogic.isRevocationWindow(5_000L, 1_000L, 10_000L))
    }

    @Test
    fun revocationWindowAtTtlIsFalse() {
        // ⛔ 等于 TTL 不算在窗口内（严格 <）
        assertFalse(MediaDeleteLogic.isRevocationWindow(11_000L, 1_000L, 10_000L))
    }

    @Test
    fun revocationWindowJustBeforeTtlIsTrue() {
        assertTrue(MediaDeleteLogic.isRevocationWindow(10_999L, 1_000L, 10_000L))
    }

    @Test
    fun revocationWindowClockRollbackIsFalse() {
        assertFalse(MediaDeleteLogic.isRevocationWindow(500L, 1_000L, 10_000L))
    }

    @Test
    fun revocationWindowNonPositiveTtlIsFalse() {
        assertFalse(MediaDeleteLogic.isRevocationWindow(5_000L, 1_000L, 0L))
        assertFalse(MediaDeleteLogic.isRevocationWindow(5_000L, 1_000L, -1L))
    }

    @Test
    fun revocationWindowRejectsMixedEpochUnits() {
        // 13 位 ms 与 10 位 s 混用 ⇒ 单位不自洽 ⇒ 放行
        assertFalse(MediaDeleteLogic.isRevocationWindow(1_793_015_486_000L, 1_793_015_486L, 600_000L))
        // 两个都是 13 位 ms、间隔 1 秒 ⇒ 正常在窗口内（证明真 epoch ms 不被误伤）
        assertTrue(MediaDeleteLogic.isRevocationWindow(1_793_015_486_000L, 1_793_015_485_000L, 600_000L))
    }

    @Test
    fun looksLikeEpochMillisDistinguishesSeconds() {
        assertTrue(MediaDeleteLogic.looksLikeEpochMillis(1_793_015_486_000L))
        assertFalse(MediaDeleteLogic.looksLikeEpochMillis(1_793_015_486L))
    }

    // ---------------------------------------------------------------- 5. shouldBlock 组合入口

    @Test
    fun shouldBlockFullChain() {
        assertTrue(block("ImgInfo2", "msglocalid=?", arrayOf(MD5_A)))
        assertTrue(block("videoinfo2", "filename=?", arrayOf(MD5_B)))
    }

    @Test
    fun shouldBlockRejectsNonTargetTable() {
        assertFalse(block("voiceinfo", "filename=?", arrayOf(MD5_A)))
        assertFalse(block("message", "msglocalid=?", arrayOf(MD5_A)))
    }

    @Test
    fun shouldBlockRejectsBulkForms() {
        assertFalse(block("ImgInfo2", null, arrayOf(MD5_A)))
        assertFalse(
            block(
                "ImgInfo2",
                "msglocalid IN (SELECT msgId FROM message WHERE talker IN (?))",
                arrayOf(MD5_A),
            ),
        )
        assertFalse(block("ImgInfo2", "fileName LIKE ?", arrayOf(MD5_A)))
    }

    @Test
    fun shouldBlockRejectsEmptyProtected() {
        assertFalse(block("ImgInfo2", "msglocalid=?", arrayOf(MD5_A), protected = emptySet()))
    }

    @Test
    fun shouldBlockRejectsOutsideWindow() {
        assertFalse(block("ImgInfo2", "msglocalid=?", arrayOf(MD5_A), now = 11_000L)) // TTL 到点
        assertFalse(block("ImgInfo2", "msglocalid=?", arrayOf(MD5_A), now = 500L))   // 时钟回拨
    }

    @Test
    fun shouldBlockRejectsTokenMiss() {
        assertFalse(block("ImgInfo2", "msglocalid=?", arrayOf(MD5_A), protected = setOf(MISS32)))
    }

    @Test
    fun shouldBlockRejects26HexOnImageTable() {
        // 即便保护集里放了这个 26 位 token，图片表 32 位口径也抽不出 ⇒ 放行
        assertFalse(block("ImgInfo2", "msglocalid=?", arrayOf(HEX26), protected = setOf(HEX26)))
    }

    @Test
    fun shouldBlockAllUncertainPathsReturnFalse() {
        assertFalse(block(null, "msglocalid=?", arrayOf(MD5_A)))
        assertFalse(block("ImgInfo2", "", arrayOf(MD5_A)))
        assertFalse(block("ImgInfo2", "msglocalid=?", arrayOf(BAD)))
        // WxFileIndex2 键列 [需真机]：未知列 ⇒ UNKNOWN ⇒ 放行
        assertFalse(block("WxFileIndex2", "unknown_col=?", arrayOf(MD5_A)))
    }

    // ---------------------------------------------------------------- 6. verdict 三态互斥

    @Test
    fun verdictNoEvent() {
        assertEquals(Verdict.NO_EVENT, MediaDeleteLogic.verdict(0L, 0L))
        assertEquals(Verdict.NO_EVENT, MediaDeleteLogic.verdict(0L, 1L))   // 脏数据，保守归此
        assertEquals(Verdict.NO_EVENT, MediaDeleteLogic.verdict(-1L, 0L))
    }

    @Test
    fun verdictHookOkButNotMatched() {
        assertEquals(Verdict.HOOK_OK_BUT_NOT_MATCHED, MediaDeleteLogic.verdict(5L, 0L))
        assertEquals(Verdict.HOOK_OK_BUT_NOT_MATCHED, MediaDeleteLogic.verdict(1L, -2L))
    }

    @Test
    fun verdictRealEvent() {
        assertEquals(Verdict.REAL_EVENT, MediaDeleteLogic.verdict(5L, 3L))
        assertEquals(Verdict.REAL_EVENT, MediaDeleteLogic.verdict(1L, 1L))
        assertEquals(Verdict.REAL_EVENT, MediaDeleteLogic.verdict(3L, 9L))
    }

    @Test
    fun verdictStatesAreMutuallyExclusive() {
        assertEquals(
            listOf(Verdict.NO_EVENT, Verdict.HOOK_OK_BUT_NOT_MATCHED, Verdict.REAL_EVENT),
            listOf(
                MediaDeleteLogic.verdict(0L, 0L),
                MediaDeleteLogic.verdict(5L, 0L),
                MediaDeleteLogic.verdict(5L, 3L),
            ),
        )
        assertEquals(3, Verdict.values().size)
    }

    // ---------------------------------------------------------------- 7. R52 数值主键 id 通道（甲路线）
    // 决定性证据：ImgInfo2 where=id=?（数值主键），旧 extractTokens 只认 26/32 位 md5 ⇒ 放行删行。
    // ⛔ 判据对着「用户能否看到完整大图」写，不拿 seen/matched/blocked/skipped 当结论。

    private val ROW_ID = "875556"
    private val ROW_IDS = setOf(ROW_ID)

    private fun clsId(
        table: String?,
        w: String?,
        args: Array<out Any?>?,
        protectedRowIds: Set<String> = emptySet(),
        protectedTokens: Set<String> = emptySet(),
    ): DeleteClass = MediaDeleteLogic.classifyDelete(table, w, args, protectedTokens, protectedRowIds)

    private fun blockId(
        table: String?,
        w: String?,
        args: Array<out Any?>?,
        protectedRowIds: Set<String> = emptySet(),
        protectedTokens: Set<String> = emptySet(),
        now: Long = 5_000L,
        issued: Long = 1_000L,
        ttl: Long = 10_000L,
    ): Boolean = MediaDeleteLogic.shouldBlock(
        table, w, args, protectedTokens, now, issued, ttl, protectedRowIds,
    )

    // ---- A 组 normalizeId（类型边界）----

    @Test
    fun a1NormalizeIdAcceptsNumericTypes() {
        // 真机 Object[] 里是 Long/Int；只认 String 会让修复等于没修（M3）
        assertEquals("875556", MediaDeleteLogic.normalizeId(875556L))
        assertEquals("875556", MediaDeleteLogic.normalizeId(875556))
        assertEquals("875556", MediaDeleteLogic.normalizeId("875556"))
    }

    @Test
    fun a4NormalizeIdTrimsAndStripsLeadingPlus() {
        assertEquals("875556", MediaDeleteLogic.normalizeId(" 875556 "))
        assertEquals("875556", MediaDeleteLogic.normalizeId("+875556"))
        assertEquals("875556", MediaDeleteLogic.normalizeId("  +875556  "))
    }

    @Test
    fun a6NormalizeIdNullIsNull() {
        assertTrue(MediaDeleteLogic.normalizeId(null) == null)
    }

    @Test
    fun a7NormalizeIdRejectsIllegalValues() {
        for (bad in listOf<Any?>(
            "-1", "875556.0", "875556abc", "", "   ", "+", "+ 875556",
            "-0", "0x10", "1_000", "１２３",
        )) {
            assertTrue("bad=$bad", MediaDeleteLogic.normalizeId(bad) == null)
        }
    }

    @Test
    fun a8NormalizeIdDoesNotCallToLong() {
        // Long.MAX_VALUE 与超 Long 的更长数字串都按原串返回，不溢出、不抛异常
        assertEquals("9223372036854775807", MediaDeleteLogic.normalizeId(Long.MAX_VALUE))
        assertEquals("9223372036854775808", MediaDeleteLogic.normalizeId("9223372036854775808"))
        assertEquals(
            "99999999999999999999999999999999",
            MediaDeleteLogic.normalizeId("99999999999999999999999999999999"),
        )
    }

    @Test
    fun a9NormalizeIdKeepsLeadingZeros() {
        // 归一口径：不去前导 0（C9「前导 0 不命中」依赖此，否则静默误命中）
        assertEquals("00875556", MediaDeleteLogic.normalizeId("00875556"))
    }

    // ---- B 组 extractRowIds / isNumericKeyDelete（形状边界）----

    @Test
    fun b1IdEqualsWithLongArgExtractsRowId() {
        // ⭐ 决定性证据那条：ImgInfo2 where=id=? + Object[] 里的 Long
        assertEquals(setOf("875556"), MediaDeleteLogic.extractRowIds("ImgInfo2", "id=?", arrayOf(875556L)))
        assertTrue(MediaDeleteLogic.isNumericKeyDelete("ImgInfo2", "id=?"))
    }

    @Test
    fun b2IdEqualsWithStringArgExtractsRowId() {
        assertEquals(setOf("875556"), MediaDeleteLogic.extractRowIds("ImgInfo2", "id=?", arrayOf("875556")))
    }

    @Test
    fun b3NullArgElementIsSkipped() {
        assertEquals(emptySet<String>(), MediaDeleteLogic.extractRowIds("ImgInfo2", "id=?", arrayOf<Any?>(null)))
    }

    @Test
    fun b4NullWhereArgsIsSkipped() {
        assertEquals(emptySet<String>(), MediaDeleteLogic.extractRowIds("ImgInfo2", "id=?", null))
    }

    @Test
    fun b5InlineNumericLiteralIsExtracted() {
        // ⭐ execSQL(String sql) 无 bindArgs 形态（M7 专杀）：双通道之「whereClause 内联右值」
        assertEquals(setOf("875556"), MediaDeleteLogic.extractRowIds("ImgInfo2", "id=875556", null))
        assertEquals(setOf("875556"), MediaDeleteLogic.extractRowIds("ImgInfo2", "id='875556'", null))
        assertEquals(setOf("875556"), MediaDeleteLogic.extractRowIds("ImgInfo2", "id = +875556", null))
    }

    @Test
    fun b6OtherNumericKeyColumnsAreCovered() {
        assertEquals(setOf("19640720"), MediaDeleteLogic.extractRowIds("ImgInfo2", "msglocalid=?", arrayOf(19640720L)))
        assertEquals(setOf("875556"), MediaDeleteLogic.extractRowIds("ImgInfo2", "msgSvrId=?", arrayOf(875556L)))
        assertEquals(setOf("875556"), MediaDeleteLogic.extractRowIds("ImgInfo2", "msgid=?", arrayOf(875556L)))
    }

    @Test
    fun b7MultipleWhereArgsAreRejected() {
        // ⭐ 多参数一律放行（M4）：⛔ 不许「取第一个」
        assertEquals(emptySet<String>(), MediaDeleteLogic.extractRowIds("ImgInfo2", "id=?", arrayOf(875556L, 875557L)))
        assertEquals(DeleteClass.BULK_OR_UNSAFE, clsId("ImgInfo2", "id=?", arrayOf(875556L, 875557L), ROW_IDS))
        assertFalse(blockId("ImgInfo2", "id=?", arrayOf(875556L, 875557L), ROW_IDS))
        // ⛔ isNumericKeyDelete(table, whereClause) 签名不含 whereArgs，无法感知多参数形状；
        //    多参数保守性由 extractRowIds 的 size>1 ⇒ ∅ 与 classifyDelete:170 双保险承担。
    }

    @Test
    fun b8InFormsAreRejected() {
        for (w in listOf("id IN (?,?)", "id IN (SELECT id FROM message)", "id in (875556,875557)")) {
            assertEquals("w=$w", emptySet<String>(), MediaDeleteLogic.extractRowIds("ImgInfo2", w, arrayOf(875556L)))
            assertFalse("w=$w", MediaDeleteLogic.isNumericKeyDelete("ImgInfo2", w))
        }
    }

    @Test
    fun b9LikeAndIsNullAreRejected() {
        for (w in listOf("thumbImgPath LIKE ?", "id IS NULL", "id IS NOT NULL", "bigImgPath LIKE '%abc%'")) {
            assertEquals("w=$w", emptySet<String>(), MediaDeleteLogic.extractRowIds("ImgInfo2", w, arrayOf(MD5_A)))
            assertFalse("w=$w", MediaDeleteLogic.isNumericKeyDelete("ImgInfo2", w))
        }
    }

    @Test
    fun b10NullWhereClauseIsRejected() {
        assertEquals(emptySet<String>(), MediaDeleteLogic.extractRowIds("ImgInfo2", null, null))
        assertEquals(emptySet<String>(), MediaDeleteLogic.extractRowIds("ImgInfo2", null, arrayOf(875556L)))
        assertFalse(MediaDeleteLogic.isNumericKeyDelete("ImgInfo2", null))
        assertEquals(DeleteClass.BULK_OR_UNSAFE, clsId("ImgInfo2", null, null, ROW_IDS))
    }

    @Test
    fun b11EmptyWhereClauseIsRejected() {
        for (w in listOf("", "   ")) {
            assertEquals("w=$w", emptySet<String>(), MediaDeleteLogic.extractRowIds("ImgInfo2", w, null))
            assertFalse("w=$w", MediaDeleteLogic.isNumericKeyDelete("ImgInfo2", w))
        }
    }

    @Test
    fun b12NonTargetTablesAreRejected() {
        for (t in listOf("message", "ImgInfo2X", "imgInfo2_backup", "voiceinfo")) {
            assertEquals("t=$t", emptySet<String>(), MediaDeleteLogic.extractRowIds(t, "id=?", arrayOf(875556L)))
            assertFalse("t=$t", MediaDeleteLogic.isNumericKeyDelete(t, "id=?"))
        }
    }

    @Test
    fun b14ScalarColumnIsRejected() {
        // status 是标量列：不在 NUMERIC_KEY_COLUMNS，也不在 IMG_SPEC.keyColumns ⇒ UNKNOWN/放行
        assertEquals(emptySet<String>(), MediaDeleteLogic.extractRowIds("ImgInfo2", "status=?", arrayOf(1L)))
        assertFalse(MediaDeleteLogic.isNumericKeyDelete("ImgInfo2", "status=?"))
        assertEquals(DeleteClass.UNKNOWN, clsId("ImgInfo2", "status=?", arrayOf(1L), ROW_IDS))
    }

    @Test
    fun b15PathColumnGoesToTokenChannelOnly() {
        // 路径列右值是 md5，不能混入数值通道
        assertEquals(emptySet<String>(), MediaDeleteLogic.extractRowIds("ImgInfo2", "bigImgPath=?", arrayOf(MD5_A)))
        assertFalse(MediaDeleteLogic.isNumericKeyDelete("ImgInfo2", "bigImgPath=?"))
        assertEquals(emptySet<String>(), MediaDeleteLogic.extractRowIds("ImgInfo2", "thumbImgPath=?", arrayOf(MD5_B)))
    }

    @Test
    fun b15bPathColumnWithNumericRightValueStillRejected() {
        // ⭐ M5 专杀：把 bigimgpath 扩进 NUMERIC_KEY_COLUMNS 会让这里从 ∅ 变成 {"875556"} ⇒ RED
        assertEquals(emptySet<String>(), MediaDeleteLogic.extractRowIds("ImgInfo2", "bigImgPath=?", arrayOf(875556L)))
        assertFalse(MediaDeleteLogic.isNumericKeyDelete("ImgInfo2", "bigImgPath=?"))
        assertEquals(DeleteClass.UNKNOWN, clsId("ImgInfo2", "bigImgPath=?", arrayOf(875556L), ROW_IDS))
        assertFalse(blockId("ImgInfo2", "bigImgPath=?", arrayOf(875556L), ROW_IDS))
    }

    @Test
    fun b16ComparisonAndMultiConditionAreRejected() {
        for (w in listOf(
            "id>?", "id<?", "id<>?", "id!=?", "id<=?", "id>=?",
            "id=? OR id=?", "id=? AND x=?", "id=?;DROP", "id IS NULL",
        )) {
            assertEquals("w=$w", emptySet<String>(), MediaDeleteLogic.extractRowIds("ImgInfo2", w, arrayOf(875556L)))
            assertFalse("w=$w", MediaDeleteLogic.isNumericKeyDelete("ImgInfo2", w))
        }
    }

    @Test
    fun b16bOrConditionWithNumericArgsStillRejected() {
        // ⭐ M4 专杀：挪走 UNSAFE 检查后 "id=? OR id=?" 会被 SINGLE_EQ 误当单列 =
        assertEquals(
            emptySet<String>(),
            MediaDeleteLogic.extractRowIds("ImgInfo2", "id=? OR id=?", arrayOf(875556L, 875557L)),
        )
        assertEquals(
            DeleteClass.BULK_OR_UNSAFE,
            clsId("ImgInfo2", "id=? OR id=?", arrayOf(875556L, 875557L), ROW_IDS),
        )
    }

    @Test
    fun b17QuotedColumnNamesAreRecognized() {
        // 复用既有 SINGLE_EQ 的引号/方括号处理，⛔ 不另写正则
        for (w in listOf("[id]=?", "`id`=?", "\"id\"=?", "'id'=?", "  id  =  ?  ")) {
            assertEquals("w=$w", setOf("875556"), MediaDeleteLogic.extractRowIds("ImgInfo2", w, arrayOf(875556L)))
            assertTrue("w=$w", MediaDeleteLogic.isNumericKeyDelete("ImgInfo2", w))
        }
    }

    // ---- C 组 idMatches / classifyDelete / shouldBlock（组合边界）----

    @Test
    fun c0IdMatchesIntersectionSemantics() {
        assertTrue(MediaDeleteLogic.idMatches(setOf("875556"), setOf("875556")))
        assertTrue(MediaDeleteLogic.idMatches(setOf("1", "875556"), setOf("875556", "2")))
        assertFalse(MediaDeleteLogic.idMatches(emptySet(), setOf("875556")))
        assertFalse(MediaDeleteLogic.idMatches(setOf("875556"), emptySet()))
        assertFalse(MediaDeleteLogic.idMatches(emptySet(), emptySet()))
        assertFalse(MediaDeleteLogic.idMatches(setOf("1"), setOf("2")))
    }

    @Test
    fun c1IdEqualsHitIsBlockable() {
        // ⭐ 决定性证据那条修复后必须能拦（判据前提：用户能看到完整大图）
        assertEquals(DeleteClass.BLOCKABLE, clsId("ImgInfo2", "id=?", arrayOf(875556L), ROW_IDS))
        assertTrue(blockId("ImgInfo2", "id=?", arrayOf(875556L), ROW_IDS))
    }

    @Test
    fun c1bInlineLiteralHitIsBlockableEndToEnd() {
        // execSQL(String sql) 无 bindArgs 形态整链（B5 的组合层）
        assertEquals(DeleteClass.BLOCKABLE, clsId("ImgInfo2", "id=875556", null, ROW_IDS))
        assertTrue(blockId("ImgInfo2", "id=875556", null, ROW_IDS))
    }

    @Test
    fun c2EmptyProtectedRowIdsIsUnknown() {
        assertEquals(DeleteClass.UNKNOWN, clsId("ImgInfo2", "id=?", arrayOf(875556L), emptySet()))
        assertFalse(blockId("ImgInfo2", "id=?", arrayOf(875556L), emptySet()))
    }

    @Test
    fun c3RowIdMissIsUnknown() {
        assertEquals(DeleteClass.UNKNOWN, clsId("ImgInfo2", "id=?", arrayOf(875556L), setOf("875557")))
        assertFalse(blockId("ImgInfo2", "id=?", arrayOf(875556L), setOf("875557")))
    }

    @Test
    fun c4OutsideRevocationWindowIsFalse() {
        // delta = 600000，严格 < ttl 不成立 ⇒ 窗口外（⛔ 新增 id 通道不得旁路 :252）
        assertFalse(
            blockId(
                "ImgInfo2", "id=?", arrayOf(875556L), ROW_IDS,
                now = 601_000L, issued = 1_000L, ttl = 600_000L,
            ),
        )
    }

    @Test
    fun c5NonTargetTableIsFalse() {
        assertFalse(blockId("message", "id=?", arrayOf(875556L), ROW_IDS))
        assertEquals(DeleteClass.OTHER_TABLE, clsId("message", "id=?", arrayOf(875556L), ROW_IDS))
    }

    @Test
    fun c6NonPositiveTtlAndClockRollbackAreFalse() {
        assertFalse(blockId("ImgInfo2", "id=?", arrayOf(875556L), ROW_IDS, ttl = 0L))
        assertFalse(blockId("ImgInfo2", "id=?", arrayOf(875556L), ROW_IDS, now = 500L, issued = 1_000L))
    }

    @Test
    fun c7EmptyTokensButNonEmptyRowIdsStillBlocks() {
        // ⭐⭐ 专抓「只改 classifyDelete、忘记把 shouldBlock:251 改成双空集闸门」（最可能漏改 / M2）
        // 全命名参数直接调用，模拟 lane B 的 protectedRowIds = cachedRowIds 调用形态
        val hit = MediaDeleteLogic.shouldBlock(
            table = "ImgInfo2",
            whereClause = "id=?",
            whereArgs = arrayOf(875556L),
            protectedTokens = emptySet(),
            nowMs = 5_000L,
            tokenIssuedMs = 1_000L,
            ttlMs = 10_000L,
            protectedRowIds = ROW_IDS,
        )
        assertTrue(hit)
        assertEquals(
            DeleteClass.BLOCKABLE,
            MediaDeleteLogic.classifyDelete(
                table = "ImgInfo2",
                whereClause = "id=?",
                whereArgs = arrayOf(875556L),
                protectedTokens = emptySet(),
                protectedRowIds = ROW_IDS,
            ),
        )
    }

    @Test
    fun c8TokenChannelRegressionStillBlockable() {
        // ⛔ 回归：新增 id 通道不得破坏 R51 既有 token 通道（用原 helper，零改动）
        assertEquals(DeleteClass.BLOCKABLE, cls("ImgInfo2", "msglocalid=?", arrayOf(MD5_A), setOf(MD5_A)))
        assertTrue(block("ImgInfo2", "msglocalid=?", arrayOf(MD5_A), setOf(MD5_A)))
        assertEquals(DeleteClass.BLOCKABLE, cls("ImgInfo2", "bigImgPath=?", arrayOf(MD5_B), setOf(MD5_B)))
    }

    @Test
    fun c9LeadingZeroMisalignmentDoesNotMatch() {
        // 归一口径不一致 ⇒ 静默漏（契约纪律最后一条）
        assertEquals(DeleteClass.UNKNOWN, clsId("ImgInfo2", "id=?", arrayOf(875556L), setOf("0875556")))
        assertFalse(blockId("ImgInfo2", "id=?", arrayOf(875556L), setOf("0875556")))
    }

    @Test
    fun c10DuplicateAndExtraRowIdsAreHarmless() {
        assertEquals(DeleteClass.BLOCKABLE, clsId("ImgInfo2", "id=?", arrayOf(875556L), setOf("875556", "999")))
        assertEquals(DeleteClass.BLOCKABLE, clsId("ImgInfo2", "id=?", arrayOf(875556L), setOf("875556", "875556")))
    }

    @Test
    fun c11VerdictUnchanged() {
        assertEquals(Verdict.REAL_EVENT, MediaDeleteLogic.verdict(1L, 1L))
        assertEquals(Verdict.NO_EVENT, MediaDeleteLogic.verdict(0L, 0L))
    }
}
