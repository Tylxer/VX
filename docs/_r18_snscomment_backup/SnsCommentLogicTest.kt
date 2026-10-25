package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnsCommentLogic] 的判据回归测试。
 *
 * 用例里的表名、列名、SQL 形态全部来自 **8.0.48 真机取证**（取证结论 #2 / #3，
 * 以及 SnsGuard 头注释里留档的那条删评论语句），没有编造成分。
 * 判据错了就要重上一轮真机，所以必须先在这里锁住。
 */
class SnsCommentLogicTest {

    /** 真机抓到的删评论语句（execSQL 形态，线程 [D]#worker） */
    private val REAL_SQL =
        "delete from SnsComment where snsID = -3442401033659805004 and commentSvrID = 1"

    /** 同一个 snsId 的两种写法：DB/归档里有符号，ImproveInfo.toString 里无符号 */
    private val REAL_SNS = -3442401033659805004L
    private val REAL_SNS_UNSIGNED = "15004343040049746612"

    // ------------------------------------------------------------------ schema

    @Test
    fun schemaMatchesRealTableInfo() {
        val expect = listOf(
            "snsID", "parentID", "isRead", "createTime", "talker", "type", "isSend",
            "curActionBuf", "refActionBuf", "commentSvrID", "clientId", "commentflag",
            "isSilence", "springWishFlag", "isReminding", "msgRelevanceType",
        )
        assertEquals(16, expect.size)
        assertEquals(expect, SnsCommentLogic.columns())
        // 正文在这两个 BLOB 里 —— SnsComment **没有 content 列**
        assertEquals(listOf("curActionBuf", "refActionBuf"), SnsCommentLogic.blobColumns())
        assertEquals(listOf("talker", "clientId"), SnsCommentLogic.textColumns())
        assertTrue(SnsCommentLogic.columns().contains("msgRelevanceType"))
        assertFalse(SnsCommentLogic.columns().contains("content"))
    }

    // ------------------------------------------------------------------ execSQL 形态

    @Test
    fun matchRealSqlLiteral() {
        val id = SnsCommentLogic.matchDeleteSql(REAL_SQL)!!
        assertEquals(REAL_SNS, id.snsId)
        assertEquals(1L, id.svrId)
    }

    @Test
    fun matchParameterizedSql() {
        val id = SnsCommentLogic.matchDeleteSql(
            "delete from SnsComment where snsID=? and commentSvrID=?",
            arrayOf<Any>("-3442401033659805004", 1),
        )!!
        assertEquals(REAL_SNS, id.snsId)
        assertEquals(1L, id.svrId)
    }

    /** ⚠️ 占位符顺序反过来也要取对 —— 按「前面有几个 ?」取下标，不能取第一个数字 */
    @Test
    fun matchReversedPlaceholderOrder() {
        val id = SnsCommentLogic.matchDeleteSql(
            "delete from SnsComment where commentSvrID=? and snsID=?",
            arrayOf<Any>(7, "-3442401033659805004"),
        )!!
        assertEquals(REAL_SNS, id.snsId)
        assertEquals(7L, id.svrId)
    }

    @Test
    fun matchUnsignedAndQuoted() {
        val id = SnsCommentLogic.matchDeleteSql(
            "DELETE FROM SnsComment WHERE snsID='15004343040049746612' AND commentSvrID='7'",
        )!!
        assertEquals(REAL_SNS, id.snsId) // 无符号写法必须落到同一个位模式
        assertEquals(REAL_SNS_UNSIGNED, java.lang.Long.toUnsignedString(id.snsId))
        assertEquals(7L, id.svrId)
    }

    @Test
    fun rejectsOtherTables() {
        assertNull(SnsCommentLogic.matchDeleteSql("delete from SnsInfo where snsId=?"))
        assertNull(SnsCommentLogic.matchDeleteSql("delete from SnsCommentDetail where snsID=? and commentSvrID=?"))
        assertNull(SnsCommentLogic.matchDeleteSql(null))
        assertNull(SnsCommentLogic.matchDeleteSql("select * from SnsComment where snsID=? and commentSvrID=?"))
    }

    /** `createTime < ?` 这类清缓存不能拦 —— 否则 SnsMicroMsg.db 只涨不删 */
    @Test
    fun rejectsCleanupStatements() {
        assertNull(SnsCommentLogic.matchDeleteSql("delete from SnsComment where createTime < ?"))
        assertNull(SnsCommentLogic.matchDeleteSql("delete from SnsComment where snsID < ?"))
    }

    /** 只定位到 snsID（删整条动态的全部评论 / 清理）→ 放行 */
    @Test
    fun rejectsBulkDeleteOfWholePost() {
        val sql = "delete from SnsComment where snsID = -3442401033659805004"
        assertNull(SnsCommentLogic.matchDeleteSql(sql))
    }

    // ------------------------------------------------------------------ delete() 形态

    @Test
    fun matchDeleteCall() {
        val id = SnsCommentLogic.matchDeleteCall(
            "SnsComment",
            "snsID=? and commentSvrID=?",
            arrayOf<Any>("-3442401033659805004", 1),
        )!!
        assertEquals(REAL_SNS, id.snsId)
        assertEquals(1L, id.svrId)
    }

    @Test
    fun matchDeleteCallRejects() {
        assertNull(SnsCommentLogic.matchDeleteCall("SnsInfo", "snsId=?", arrayOf<Any>(REAL_SNS)))
        assertNull(SnsCommentLogic.matchDeleteCall("SnsComment", "snsID < ?", arrayOf<Any>(REAL_SNS)))
        assertNull(SnsCommentLogic.matchDeleteCall("SnsComment", null, null))
    }

    @Test
    fun snsIdOfWhereClause() {
        assertEquals(REAL_SNS, SnsCommentLogic.snsIdOf("snsId=?", arrayOf<Any>(REAL_SNS)))
        assertEquals(REAL_SNS, SnsCommentLogic.snsIdOf("snsID = -3442401033659805004", null))
        assertNull(SnsCommentLogic.snsIdOf("createTime=?", arrayOf<Any>(1)))
    }

    // ------------------------------------------------------------------ key

    @Test
    fun keyStableAcrossSignedness() {
        val a = SnsCommentLogic.key(REAL_SNS, 3L)
        val b = SnsCommentLogic.key(SnsCommentLogic.Id(REAL_SNS, 3L))
        assertEquals(a, b)
        assertEquals("-3442401033659805004:3", a)
    }

    // ------------------------------------------------------------------ attrBuf 判据

    private fun buf(n: Int, fill: Int = 0x41): ByteArray = ByteArray(n) { fill.toByte() }

    @Test
    fun planAttrKeepWhenUnchanged() {
        val old = buf(300)
        val v = SnsCommentLogic.planAttr(old, old.copyOf())
        assertEquals(SnsCommentLogic.Act.KEEP, v.act)
        assertEquals(300, v.oldSize)
        assertEquals(300, v.newSize)
    }

    /** 当前比快照大 = 期间来了新评论，写回会把新评论一起抹掉 → 放弃 */
    @Test
    fun planAttrSkipWhenCurrentGrows() {
        val v = SnsCommentLogic.planAttr(buf(300), buf(400))
        assertEquals(SnsCommentLogic.Act.SKIP, v.act)
    }

    @Test
    fun planAttrSkipWhenMissing() {
        val v1 = SnsCommentLogic.planAttr(null, buf(300))
        assertEquals(SnsCommentLogic.Act.SKIP, v1.act)
        val v2 = SnsCommentLogic.planAttr(buf(300), null)
        assertEquals(SnsCommentLogic.Act.SKIP, v2.act)
        val v3 = SnsCommentLogic.planAttr(ByteArray(0), buf(300))
        assertEquals(SnsCommentLogic.Act.SKIP, v3.act)
    }

    @Test
    fun planAttrRestoreOnSmallShrink() {
        val v = SnsCommentLogic.planAttr(buf(300), buf(260))
        assertEquals(SnsCommentLogic.Act.RESTORE, v.act)
        assertEquals(40, v.oldSize - v.newSize)
    }

    /** 缩容 2K 以上不是「删一条评论」的量级（可能是整条动态被重写）→ 放弃 */
    @Test
    fun planAttrSkipOnHugeShrink() {
        val v = SnsCommentLogic.planAttr(buf(5000), buf(100))
        assertEquals(SnsCommentLogic.Act.SKIP, v.act)
    }

    // ------------------------------------------------------------------ 正文候选 / 对账

    /** 造一个 protobuf 线格式的片段：tag(1 字节) + length(1 字节) + UTF-8 正文 */
    private fun pb(field: Int, s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return byteArrayOf(((field shl 3) or 2).toByte(), b.size.toByte()) + b
    }

    @Test
    fun textCandidatesFromCurActionBuf() {
        val blob = pb(1, "wxid_abcd1234") + pb(2, "这是一条测试评论") + byteArrayOf(0x18, 0x01)
        val c = SnsCommentLogic.textCandidates(blob)
        assertTrue("应有正文：$c", c.contains("这是一条测试评论"))
        assertTrue("应有 talker：$c", c.contains("wxid_abcd1234"))
    }

    @Test
    fun textCandidatesRejectsGarbage() {
        val a = SnsCommentLogic.textCandidates(null)
        assertTrue(a.isEmpty())
        val b = SnsCommentLogic.textCandidates(ByteArray(0))
        assertTrue(b.isEmpty())
        // 全是不可见字节：抽不出任何东西
        val c = SnsCommentLogic.textCandidates(byteArrayOf(0x00, 0x01, 0x02))
        assertTrue(c.isEmpty())
    }

    @Test
    fun textHitIsStrict() {
        val cands = SnsCommentLogic.textCandidates(pb(2, "这是一条测试评论"))
        assertTrue(SnsCommentLogic.textHit(cands, listOf("这是一条测试评论")))
        assertTrue(SnsCommentLogic.textHit(cands, listOf("这是一条测试评论啊"))) // 包含，够长才算
        assertFalse(SnsCommentLogic.textHit(cands, listOf("评论")))      // 2 字碎片，不算
        assertFalse(SnsCommentLogic.textHit(cands, listOf("别的动态")))
        assertFalse(SnsCommentLogic.textHit(cands, emptyList()))
        assertFalse(SnsCommentLogic.textHit(emptyList(), listOf("这是一条测试评论")))
    }

    // ------------------------------------------------------------------ 「已删除」形态判定
    //
    // ⚠️ 三种形态（整行消失 / 内容清空 / 置 flag）到底哪一种是 8.0.48 的真实行为，
    // 目前**没有实证**（取证结论「朋友圈」#9）。所以这里把三种都锁住，
    // 真机探针 `snsc sub=form` 跑完就知道是哪一种，判据不用改。

    private fun state(present: Boolean, content: String?) =
        SnsCommentLogic.CommentState(present, content)

    /** 形态 A：整行消失 */
    @Test
    fun formMissingWhenRowGone() {
        assertEquals(SnsCommentLogic.DelForm.MISSING, SnsCommentLogic.classify(state(false, null)))
        assertEquals(SnsCommentLogic.DelForm.MISSING, SnsCommentLogic.classify(state(false, "还在的正文")))
        assertTrue(SnsCommentLogic.isDeleted(state(false, null)))
    }

    /** 形态 B：内容清空 —— 空串 / 全空格 / 只剩标点 */
    @Test
    fun formEmptyWhenContentBlank() {
        assertEquals(SnsCommentLogic.DelForm.EMPTY, SnsCommentLogic.classify(state(true, "")))
        assertEquals(SnsCommentLogic.DelForm.EMPTY, SnsCommentLogic.classify(state(true, "   ")))
        assertEquals(SnsCommentLogic.DelForm.EMPTY, SnsCommentLogic.classify(state(true, "\t\n ")))
        assertEquals(SnsCommentLogic.DelForm.EMPTY, SnsCommentLogic.classify(state(true, "。。。")))
        assertEquals(SnsCommentLogic.DelForm.EMPTY, SnsCommentLogic.classify(state(true, "?!...")))
    }

    /** ⚠️ 边界：`content == null` 是「抽不出正文」，不是「空」→ 必须判 ALIVE（不误标） */
    @Test
    fun contentNullMeansUnknownNotDeleted() {
        assertEquals(SnsCommentLogic.DelForm.ALIVE, SnsCommentLogic.classify(state(true, null)))
        assertFalse(SnsCommentLogic.isDeleted(state(true, null)))
        assertFalse(SnsCommentLogic.isBlankish(null))
    }

    /** ⚠️ 纯 emoji 评论是正常内容，不能被当成「内容被清空」 */
    @Test
    fun emojiOnlyIsRealContent() {
        assertFalse(SnsCommentLogic.isBlankish("😀😀"))
        assertEquals(SnsCommentLogic.DelForm.ALIVE, SnsCommentLogic.classify(state(true, "😀")))
    }

    /** 正常评论不误标：有正文 */
    @Test
    fun normalCommentStaysAlive() {
        val st = state(true, "这是一条正常评论")
        assertEquals(SnsCommentLogic.DelForm.ALIVE, SnsCommentLogic.classify(st))
        assertFalse(SnsCommentLogic.isDeleted(st))
        // 一个字也得算内容
        assertEquals(SnsCommentLogic.DelForm.ALIVE, SnsCommentLogic.classify(state(true, "好")))
    }

    /**
     * ⛔ **`FLAGGED` 形态已删除** —— 2026-10-19 静态取证定案：删单条评论是**物理 DELETE**
     * （`s1.Q0` → `delete from SnsComment where snsID=? and commentSvrID=? and type in (8,16)`），
     * 行直接没了，没有 flag 可读；`commentflag` 只承载「某人删光自己评论」/「整条动态被删」，
     * 且业务代码零读取。详见 `docs/取证结论.md` §「朋友圈评论」。
     *
     * 这条用例锁住「枚举里不能再出现第四种形态」，防止有人把 mask 那套加回来。
     */
    @Test
    fun delFormHasExactlyThreeStates() {
        assertEquals(
            setOf(
                SnsCommentLogic.DelForm.ALIVE,
                SnsCommentLogic.DelForm.MISSING,
                SnsCommentLogic.DelForm.EMPTY,
            ),
            SnsCommentLogic.DelForm.entries.toSet(),
        )
    }

    /** 行没了 > 一切：即便正文还正常也必须是 MISSING */
    @Test
    fun missingOutranksEverything() {
        assertEquals(SnsCommentLogic.DelForm.MISSING, SnsCommentLogic.classify(state(false, "正文")))
    }

    // ------------------------------------------------------------------ [已删除] 文本拼装

    @Test
    fun labelNullForAliveComment() {
        assertNull(SnsCommentLogic.labelText(SnsCommentLogic.DelForm.ALIVE))
        assertNull(SnsCommentLogic.labelText(SnsCommentLogic.DelForm.ALIVE, "张三"))
    }

    @Test
    fun labelPlainWithoutNick() {
        for (f in listOf(
            SnsCommentLogic.DelForm.MISSING,
            SnsCommentLogic.DelForm.EMPTY,
        )) {
            assertEquals("[已删除]", SnsCommentLogic.labelText(f))
            assertEquals("[已删除]", SnsCommentLogic.labelText(f, null))
            assertEquals("[已删除]", SnsCommentLogic.labelText(f, "   ")) // 空白昵称 = 没有昵称
        }
    }

    @Test
    fun labelWithNick() {
        assertEquals("张三：[已删除]", SnsCommentLogic.labelText(SnsCommentLogic.DelForm.EMPTY, "张三"))
        assertEquals("张三：[已删除]", SnsCommentLogic.labelText(SnsCommentLogic.DelForm.MISSING, " 张三 "))
    }

    /** ⚠️ 拼装结果**不能**带残留正文 —— 需求要的是「显示 [已删除] 而不是残留内容」 */
    @Test
    fun labelNeverLeaksResidualContent() {
        val s = SnsCommentLogic.labelText(SnsCommentLogic.DelForm.EMPTY, "张三")!!
        assertFalse(s.contains("这是被抹掉的正文"))
        assertTrue(s.endsWith("[已删除]"))
    }

    // ------------------------------------------------------------------ 诊断输出

    @Test
    fun hexHeadOnly() {
        assertEquals("(null)", SnsCommentLogic.hex(null))
        assertEquals("(empty)", SnsCommentLogic.hex(ByteArray(0)))
        assertEquals("010203", SnsCommentLogic.hex(byteArrayOf(1, 2, 3)))
        assertEquals("010203040506", SnsCommentLogic.hex(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertEquals("0102", SnsCommentLogic.hex(byteArrayOf(1, 2, 3), 2))
    }

    // ================================================================== 6.2 attrBuf 条目级差量
    //
    // ⚠️⚠️ **合成样例，待真机字节替换**：
    //   attrBuf 的 protobuf 结构**尚未取证**（取证清单 5.4），这里用 `pb()` 手工拼的
    //   通用线格式字节只是**替身**，用来锁住「保守策略」的行为；取证到真实 attrBuf 字节后，
    //   把这些 `attrBlob(...)` 换成真机 `op=sns sub=sample` 拿到的字节。
    //
    //   本组测试的核心断言不是「估得准」，而是「**未取证时宁可报 null / 不安全**」：
    //   猜微信内部格式是项目铁律禁止的。

    /** 拼一个「顶层字段 + 若干重复条目字段」的合成 attrBuf 替身 */
    private fun attrBlob(entryField: Int, vararg entries: String): ByteArray {
        var b = pb(1, "hdr") // 顶层里加一个无关字段，验证扫描器能跳过它
        for (e in entries) b += pb(entryField, e)
        return b
    }

    /** ⛔ 核心保守断言：字段号**未取证**（`ATTR_COMMENT_ENTRY_FIELD == null`）→ 恒不估算 */
    @Test
    fun attrEntryCountNullUntilForensics() {
        assertNull(SnsCommentLogic.ATTR_COMMENT_ENTRY_FIELD)
        // 即便给一个「看起来很正常」的 blob，默认调用也必须返回 null（不认识结构宁可不写回）
        val blob = attrBlob(3, "c1", "c2", "c3")
        assertNull(SnsCommentLogic.attrEntryCount(blob))
        assertNull(SnsCommentLogic.attrEntryCount(null))
    }

    /** 显式传入**待验证**字段号时才真去数条目（仅取证比对用，不作为生产写回依据） */
    @Test
    fun attrEntryCountCountsRepeatedFieldWhenFieldGiven() {
        val blob = attrBlob(3, "c1", "c2", "c3", "c4")
        assertEquals(4, SnsCommentLogic.attrEntryCount(blob, entryField = 3))
        assertEquals(0, SnsCommentLogic.attrEntryCount(pb(1, "hdr"), entryField = 3)) // 该字段一次没出现
    }

    /** 空 / 过短 / 结构断裂 / 非法字段号 → 一律 null（无法估算） */
    @Test
    fun attrEntryCountNullOnEmptyOrGarbage() {
        val f = 3
        assertNull(SnsCommentLogic.attrEntryCount(null, f))
        assertNull(SnsCommentLogic.attrEntryCount(ByteArray(0), f))
        assertNull(SnsCommentLogic.attrEntryCount(byteArrayOf(0x1A), f))      // 只有 tag，没有 length
        assertNull(SnsCommentLogic.attrEntryCount(byteArrayOf(0x1A, 0x05), f)) // 声明 len=5 但后面没字节
        assertNull(SnsCommentLogic.attrEntryCount(byteArrayOf(0x1A, 0x80.toByte()), f)) // length varint 未终止
        assertNull(SnsCommentLogic.attrEntryCount(attrBlob(3, "c1"), entryField = 0)) // 字段号 0 非法
    }

    /** ⛔ 字段号未取证 → diffAttr 的 safeToRestore 恒 false，shrinkBy = -1 */
    @Test
    fun diffAttrUnsafeUntilForensics() {
        val old = attrBlob(3, "c1", "c2", "c3")
        val new = attrBlob(3, "c1", "c2")
        val d = SnsCommentLogic.diffAttr(old, new)
        assertNull(d.oldEntries)
        assertNull(d.newEntries)
        assertEquals(-1, d.shrinkBy)
        assertFalse(d.safeToRestore)
    }

    /** 减少 1 条、且 new 是 old 的子集（只少没变）→ 可以安全写回 */
    @Test
    fun diffAttrSafeOnSingleShrink() {
        val d = SnsCommentLogic.diffAttr(
            attrBlob(3, "c1", "c2", "c3"),
            attrBlob(3, "c1", "c2"),
            entryField = 3,
        )
        assertEquals(3, d.oldEntries)
        assertEquals(2, d.newEntries)
        assertEquals(1, d.shrinkBy)
        assertTrue(d.safeToRestore)
    }

    /** ⚠️ 减少的同时**有新增**（新评论插入）→ 覆盖会抹掉新评论 → 必须 unsafe */
    @Test
    fun diffAttrUnsafeWhenNewEntryInserted() {
        // c4 是新评论：条数没减（shrink=0）
        val d1 = SnsCommentLogic.diffAttr(
            attrBlob(3, "c1", "c2", "c3"),
            attrBlob(3, "c2", "c3", "c4"),
            entryField = 3,
        )
        assertEquals(0, d1.shrinkBy)
        assertFalse(d1.safeToRestore)

        // 净减 1 条，但 new 里混进了 old 没有的 c5 → 仍 unsafe
        val d2 = SnsCommentLogic.diffAttr(
            attrBlob(3, "c1", "c2", "c3", "c4"),
            attrBlob(3, "c3", "c4", "c5"),
            entryField = 3,
        )
        assertEquals(4, d2.oldEntries)
        assertEquals(3, d2.newEntries)
        assertEquals(1, d2.shrinkBy)
        assertFalse(d2.safeToRestore)
    }

    /** 任一侧无法估算（null / 空 / 过短）→ 保守返回 unsafe，且条目数为 null、shrink=-1 */
    @Test
    fun diffAttrUnsafeWhenCannotEstimate() {
        val good = attrBlob(3, "c1", "c2")
        val dn = SnsCommentLogic.diffAttr(null, good, entryField = 3)
        assertNull(dn.oldEntries)
        assertNull(dn.newEntries)
        assertEquals(-1, dn.shrinkBy)
        assertFalse(dn.safeToRestore)

        val de = SnsCommentLogic.diffAttr(good, ByteArray(0), entryField = 3)
        assertFalse(de.safeToRestore)

        // 当前比快照**大**（不是缩容）→ 不是「删了一条」→ unsafe
        val dg = SnsCommentLogic.diffAttr(
            attrBlob(3, "c1"),
            attrBlob(3, "c1", "c2", "c3"),
            entryField = 3,
        )
        assertEquals(-2, dg.shrinkBy)
        assertFalse(dg.safeToRestore)
    }

    // ================================================================== 6.3 方案 B「归档有、界面无」
    //
    // ⚠️ 合成样例，待真机字节替换（正文候选由 `pb()` 合成）。

    private fun candsOf(text: String): List<String> = SnsCommentLogic.textCandidates(pb(2, text))

    /** 全部命中 → 返回空 */
    @Test
    fun missingCommentsEmptyWhenAllHit() {
        val out = SnsCommentLogic.missingComments(
            listOf(candsOf("这是一条测试评论"), candsOf("另一条很长的评论")),
            listOf("这是一条测试评论", "另一条很长的评论"),
        )
        assertTrue(out.isEmpty())
    }

    /** 部分命中 → 只返回未命中的那一组 */
    @Test
    fun missingCommentsReturnsOnlyMissed() {
        val missed = candsOf("这条界面看不到")
        val out = SnsCommentLogic.missingComments(
            listOf(candsOf("这是一条测试评论"), missed),
            listOf("这是一条测试评论"),
        )
        assertEquals(1, out.size)
        assertEquals(missed, out[0])
    }

    /** 2 字碎片（`textHit` 严格边界）不算命中 → 进返回集 */
    @Test
    fun missingCommentsTreatsTwoCharFragmentAsMiss() {
        val out = SnsCommentLogic.missingComments(
            listOf(candsOf("这是一条测试评论")),
            listOf("评论"),
        )
        assertEquals(1, out.size)
    }

    /** 空输入 → 空返回 */
    @Test
    fun missingCommentsEmptyInput() {
        assertTrue(SnsCommentLogic.missingComments(emptyList(), listOf("任意文案")).isEmpty())
        assertTrue(SnsCommentLogic.missingComments(listOf(candsOf("某条评论")), emptyList()).isEmpty())
        // 候选组本身为空（抽不出正文）→ 无法对账 → 不判定为被删
        assertTrue(SnsCommentLogic.missingComments(listOf(emptyList()), listOf("任意文案")).isEmpty())
    }

    /** ⚠️ 界面文案为空（评论折叠/未渲染）= 无法对账 → 一律「待确认」，绝不判成被删 */
    @Test
    fun missingCommentsAllPendingWhenNoVisibleText() {
        val out = SnsCommentLogic.missingComments(
            listOf(candsOf("评论甲"), candsOf("评论乙")),
            emptyList(),
        )
        assertTrue(out.isEmpty())
    }

    // ================================================================== 6.4 定位增强（boolean → 下标）

    /** 唯一命中 → 正确下标 */
    @Test
    fun firstHitIndexUniqueHit() {
        val i = SnsCommentLogic.firstHitIndex(
            candsOf("这是一条测试评论"),
            listOf("别的文案", "这是一条测试评论"),
        )
        assertEquals(1, i)
    }

    /** ⚠️ 歧义：匹配到多行 → -1（不猜） */
    @Test
    fun firstHitIndexAmbiguous() {
        val i = SnsCommentLogic.firstHitIndex(
            candsOf("这是一条测试评论"),
            listOf("这是一条测试评论", "这是一条测试评论啊"),
        )
        assertEquals(-1, i)
    }

    /** 无命中 → -1（含空输入） */
    @Test
    fun firstHitIndexNoHit() {
        assertEquals(-1, SnsCommentLogic.firstHitIndex(candsOf("这是一条测试评论"), listOf("完全不搭边的东西")))
        assertEquals(-1, SnsCommentLogic.firstHitIndex(emptyList(), listOf("任意")))
        assertEquals(-1, SnsCommentLogic.firstHitIndex(candsOf("这是一条测试评论"), emptyList()))
    }

    /** 短碎片 → 不认（沿用 `textHit` 严格规则）→ -1 */
    @Test
    fun firstHitIndexRejectsShortFragment() {
        assertEquals(-1, SnsCommentLogic.firstHitIndex(candsOf("这是一条测试评论"), listOf("评论")))
        assertEquals(-1, SnsCommentLogic.firstHitIndex(candsOf("这是一条测试评论"), listOf("评", "论")))
    }

    // ================================================================== 6.2 差集判定（[已删除] 主判据）
    //
    // 取证前提（主 agent 真机）：评论不在 SnsInfo.attrBuf，而在 SnsMicroMsg.db 的 SnsComment 表。
    // 「哪条被删了」= 归档里有、当前表里查不到的差集；标识用 **commentSvrID**
    // （跟 SnsCommentGuard.archivedIds() / 归档表主键 PRIMARY KEY(snsID, commentSvrID) 一致）。
    //
    // 下面凡标「构造」的集合都是**人工构造**（真机只取证到 snsID / commentSvrID=1 与
    // type∈{1,2}、commentflag∈{0,2}、两个 actionBuf 实测为 null，没有抓到过完整的评论列表）。

    private fun cid(svr: Long, sns: Long = REAL_SNS) = SnsCommentLogic.Id(sns, svr)

    /** 真机：snsID=-3442401033659805004 下 commentSvrID=1 被 delete；表里还剩 2、3 → 只判 1 */
    @Test
    fun diffDeletedRealDeviceRow() {
        val d = SnsCommentLogic.diffDeleted(archived = setOf(1L), present = setOf(2L, 3L))
        assertEquals(setOf(1L), d.deleted)
        assertEquals(emptySet<Long>(), d.kept)
        assertEquals(emptySet<Long>(), d.unknown)
        assertEquals(setOf(1L), SnsCommentLogic.deletedIds(setOf(1L), setOf(2L, 3L)))
    }

    /**
     * ⚠️ **最要紧的一条**：归档里有、但表里**仍在**的评论一律不打标。
     * 「把正常评论打成已删除」是事故，这里锁死它。
     * （构造：present 里的 2/3 只是凑数的邻居评论，1 是拦下过删除但后来又回来了的）
     */
    @Test
    fun diffDeletedNeverMarksStillPresentRow() {
        val d = SnsCommentLogic.diffDeleted(archived = setOf(1L, 2L, 3L), present = setOf(1L, 2L, 3L))
        assertEquals(emptySet<Long>(), d.deleted)
        assertEquals(setOf(1L, 2L, 3L), d.kept)
        assertEquals(emptySet<Long>(), d.unknown)
    }

    /** 部分命中：归档 3 条、表里剩 2 条 → 只判消失的那 1 条 */
    @Test
    fun diffDeletedPartial() {
        val d = SnsCommentLogic.diffDeleted(archived = setOf(1L, 2L, 3L), present = setOf(1L, 3L))
        assertEquals(setOf(2L), d.deleted)
        assertEquals(setOf(1L, 3L), d.kept)
    }

    /** 查询没成（null）≠ 查到 0 条：全部退回 unknown，一个都不判 */
    @Test
    fun diffDeletedAllUnknownWhenQueryFailed() {
        val d = SnsCommentLogic.diffDeleted(archived = setOf(1L, 2L), present = null)
        assertEquals(emptySet<Long>(), d.deleted)
        assertEquals(emptySet<Long>(), d.kept)
        assertEquals(setOf(1L, 2L), d.unknown)
    }

    /**
     * 快照整批为空、且调用方没打包票 → 退回 unknown。
     * 真机 `op=snsc sub=stat` 出现过 archived=0 / 库没抓到的情况，这时候「表里一条没有」
     * 更像没查成，而不是评论全被删了 —— 否则一次误判就是满屏正常评论被打成已删除。
     */
    @Test
    fun diffDeletedEmptySnapshotNeedsTrust() {
        val d1 = SnsCommentLogic.diffDeleted(setOf(1L, 2L), emptySet())
        assertEquals(setOf(1L, 2L), d1.unknown)
        assertEquals(emptySet<Long>(), d1.deleted)

        val d2 = SnsCommentLogic.diffDeleted(setOf(1L, 2L), emptySet(), trustEmpty = true)
        assertEquals(setOf(1L, 2L), d2.deleted)
        assertEquals(emptySet<Long>(), d2.unknown)
    }

    /** 归档为空 → 空结果（没有归档就没有「删过」的证据） */
    @Test
    fun diffDeletedEmptyArchived() {
        val d = SnsCommentLogic.diffDeleted(emptySet(), setOf(1L, 2L))
        assertEquals(0, d.size)
        assertEquals(emptySet<Long>(), d.deleted)
    }

    /** 批量：按 snsID 分组；某组没查成只影响该组，不连坐 */
    @Test
    fun diffDeletedAllPerSnsIdIsolated() {
        val archived = mapOf(
            REAL_SNS to setOf(1L, 2L),
            // 构造：第二个 snsId 用真机那个数 +1，只为验证分组互不干扰
            (REAL_SNS + 1) to setOf(5L),
        )
        val out = SnsCommentLogic.diffDeletedAll(archived) { sns ->
            when (sns) {
                REAL_SNS -> setOf(2L)          // 1 消失了 → 判已删
                else -> null                   // 没查成 → 整组 unknown
            }
        }
        assertEquals(setOf(1L), out[REAL_SNS]!!.deleted)
        assertEquals(setOf(2L), out[REAL_SNS]!!.kept)
        assertEquals(setOf(5L), out[REAL_SNS + 1]!!.unknown)
        assertEquals(emptySet<Long>(), out[REAL_SNS + 1]!!.deleted)
    }

    /** live 回调抛异常 → 按「没查成」处理（漏标），不能把异常放出去崩掉打标流程 */
    @Test
    fun diffDeletedAllSwallowsLiveFailure() {
        val out = SnsCommentLogic.diffDeletedAll(mapOf(REAL_SNS to setOf(1L))) {
            error("db closed") // 构造：模拟真机上查库抛异常
        }
        assertEquals(setOf(1L), out[REAL_SNS]!!.unknown)
        assertEquals(emptySet<Long>(), out[REAL_SNS]!!.deleted)
    }

    @Test
    fun diffDeletedAllEmptyArchived() {
        assertTrue(SnsCommentLogic.diffDeletedAll(emptyMap()) { setOf(1L) }.isEmpty())
    }

    /**
     * 批量边界：某个 snsID 下归档为空（拦到过帖子级事件但没拦到评论级删除）→ 空结果，不炸；
     * `trustEmpty` 必须**逐组下传**给 [SnsCommentLogic.diffDeleted]，不能只在某个分支生效。
     */
    @Test
    fun diffDeletedAllEmptyGroupAndTrustEmpty() {
        val archived = mapOf(
            REAL_SNS to emptySet<Long>(),
            (REAL_SNS + 1) to setOf(7L),
        )
        val trusted = SnsCommentLogic.diffDeletedAll(archived, trustEmpty = true) { emptySet() }
        assertEquals(0, trusted[REAL_SNS]!!.size)                    // 归档为空 → 一条都不判
        assertEquals(setOf(7L), trusted[REAL_SNS + 1]!!.deleted)     // 打包票相信空表 → 判已删

        val untrusted = SnsCommentLogic.diffDeletedAll(archived) { emptySet() }
        assertEquals(setOf(7L), untrusted[REAL_SNS + 1]!!.unknown)   // 没打包票 → 退回 unknown
        assertEquals(emptySet<Long>(), untrusted[REAL_SNS + 1]!!.deleted)
    }

    /**
     * 形态 B 备用入口：只有「行在但正文被清空」才判，正常正文一律不判。
     *
     * ⛔ 原先这里靠 `flag` + `mask` 判 FLAGGED，静态取证已证明那条路不存在（见 [delFormHasExactlyThreeStates]），
     * 现在只剩「正文空」这一条判据。
     */
    @Test
    fun deletedByStateOnlyJudgesBlankContent() {
        val m = mapOf(
            1L to SnsCommentLogic.CommentState(present = true, content = "正常评论"),
            2L to SnsCommentLogic.CommentState(present = true, content = ""),
            3L to SnsCommentLogic.CommentState(present = false, content = null),
            4L to SnsCommentLogic.CommentState(present = true, content = null), // 抽不出 ≠ 空
        )
        // 2 是正文空 → EMPTY；3 是行没了 → MISSING；1、4 正常/未知 → 不判
        assertEquals(setOf(2L, 3L), SnsCommentLogic.deletedByState(m))
    }

    // ================================================================== 6.2 打标：幂等 + 文案拼接

    /** 同一条只放行一次（评论行会反复重绘，不打账本就会 [已删除][已删除][已删除]） */
    @Test
    fun tagLedgerOnceOnly() {
        val led = SnsCommentLogic.TagLedger()
        val id = cid(1L)
        assertTrue(led.firstTime(id))
        assertFalse(led.firstTime(id))
        assertTrue(led.marked(id))
        assertEquals(1, led.size)
        led.clear()
        assertEquals(0, led.size)
        assertTrue(led.firstTime(id))
    }

    /** 不同 (snsID, commentSvrID) 互不占用 */
    @Test
    fun tagLedgerKeyedByBothFields() {
        val led = SnsCommentLogic.TagLedger()
        assertTrue(led.firstTime(cid(1L)))
        assertTrue(led.firstTime(cid(2L)))          // 同 snsId、不同 svrId
        assertTrue(led.firstTime(SnsCommentLogic.Id(REAL_SNS + 1, 1L))) // 同 svrId、不同 snsId
        assertEquals(3, led.size)
    }

    @Test
    fun alreadyLabeledDetectsMarker() {
        assertTrue(SnsCommentLogic.alreadyLabeled("小明：[已删除]"))
        assertTrue(SnsCommentLogic.alreadyLabeled("[已删除]"))
        assertFalse(SnsCommentLogic.alreadyLabeled("小明：这是一条正常评论"))
        assertFalse(SnsCommentLogic.alreadyLabeled(null))
    }

    /** ⚠️ 误标闸门 1：ALIVE（正常评论）永远不打标 */
    @Test
    fun tagTextRejectsAliveComment() {
        assertNull(
            SnsCommentLogic.tagText(
                SnsCommentLogic.TagRequest(cid(2L), SnsCommentLogic.DelForm.ALIVE, "小明：正常评论", "小明"),
            )
        )
    }

    /** ⚠️ 误标闸门 2：正文对账明确对不上 → 不打标（怕打到别人的正常评论上） */
    @Test
    fun tagTextRejectsMismatch() {
        assertNull(
            SnsCommentLogic.tagText(
                SnsCommentLogic.TagRequest(
                    cid(1L), SnsCommentLogic.DelForm.MISSING, "小红：别的评论", "小明", match = false,
                ),
            )
        )
        // 对上了 / 没得对（null）→ 放行
        assertEquals(
            "小明：[已删除]",
            SnsCommentLogic.tagText(
                SnsCommentLogic.TagRequest(cid(1L), SnsCommentLogic.DelForm.MISSING, null, "小明", match = true),
            ),
        )
        assertEquals(
            "小明：[已删除]",
            SnsCommentLogic.tagText(
                SnsCommentLogic.TagRequest(cid(1L), SnsCommentLogic.DelForm.MISSING, null, "小明", match = null),
            ),
        )
    }

    /** 幂等 A：账本命中 → 第二次返回 null */
    @Test
    fun tagTextIdempotentByLedger() {
        val led = SnsCommentLogic.TagLedger()
        val req = SnsCommentLogic.TagRequest(cid(1L), SnsCommentLogic.DelForm.MISSING, null, "小明")
        assertEquals("小明：[已删除]", SnsCommentLogic.tagText(req, led))
        assertNull(SnsCommentLogic.tagText(req, led))
    }

    /** 幂等 B：View 被复用、文案里已经有 [已删除] → 不再拼第二次 */
    @Test
    fun tagTextIdempotentByExistingText() {
        assertNull(
            SnsCommentLogic.tagText(
                SnsCommentLogic.TagRequest(cid(1L), SnsCommentLogic.DelForm.MISSING, "小明：[已删除]", "小明"),
            )
        )
    }

    /** 替换模式（默认）= 标签即全文，不留残留正文；追加模式才带上原文 */
    @Test
    fun tagTextReplaceVsAppend() {
        val base = SnsCommentLogic.TagRequest(
            cid(1L), SnsCommentLogic.DelForm.MISSING, "小明：这条评论的原文内容", "小明",
        )
        assertEquals("小明：[已删除]", SnsCommentLogic.tagText(base))
        assertEquals(
            "小明：这条评论的原文内容 [已删除]",
            SnsCommentLogic.tagText(base.copy(replace = false)),
        )
        // 追加模式但原文为空白 → 退化成替换，不会出现 " [已删除]"（有昵称，所以带昵称）
        assertEquals(
            "小明：[已删除]",
            SnsCommentLogic.tagText(base.copy(existing = "   ", replace = false)),
        )
    }

    /**
     * 追加模式的昵称去重：原文开头已经是「\<昵称\>：」时，尾巴只补 `[已删除]`，
     * 不能拼成「小明：原文 小明：[已删除]」。
     */
    @Test
    fun tagTextAppendDoesNotRepeatNickPrefix() {
        val mk = { existing: String?, nick: String? ->
            SnsCommentLogic.TagRequest(cid(1L), SnsCommentLogic.DelForm.MISSING, existing, nick, replace = false)
        }
        // 全角冒号（微信的标准写法）→ 只补标签
        assertEquals(
            "小明：这条评论的原文内容 [已删除]",
            SnsCommentLogic.tagText(mk("小明：这条评论的原文内容", "小明")),
        )
        // 半角冒号也算，同样不重复
        assertEquals(
            "小明:原文 [已删除]",
            SnsCommentLogic.tagText(mk("小明:原文", "小明")),
        )
        // 原文就是个光秃秃的昵称 → 也不重复
        assertEquals("小明 [已删除]", SnsCommentLogic.tagText(mk("小明", "小明")))
        // 原文带的昵称跟我们要打的这条**不是同一个** → 保守拼完整标签（宁可不省略）
        assertEquals(
            "小红：别的评论 小明：[已删除]",
            SnsCommentLogic.tagText(mk("小红：别的评论", "小明")),
        )
        // 昵称缺失 → 追加模式退化成要么原文+裸标签、要么裸标签
        assertEquals("原文 [已删除]", SnsCommentLogic.tagText(mk("原文", null)))
    }

    /** 昵称缺失 / 全空白 → 只出 `[已删除]` */
    @Test
    fun tagTextNickFallback() {
        assertEquals(
            "[已删除]",
            SnsCommentLogic.tagText(SnsCommentLogic.TagRequest(cid(1L), SnsCommentLogic.DelForm.MISSING)),
        )
        assertEquals(
            "[已删除]",
            SnsCommentLogic.tagText(
                SnsCommentLogic.TagRequest(cid(1L), SnsCommentLogic.DelForm.EMPTY, "   ", "  "),
            ),
        )
    }

    /**
     * 端到端（真机 snsID + commentSvrID=1）：
     * 差集判已删 → 打标一次 → 重绘时不再打第二次。
     */
    @Test
    fun tagEndToEndRealDeviceId() {
        val archived = setOf(1L)
        val present = setOf(2L, 3L) // 构造：表里剩下的邻居评论
        val deleted = SnsCommentLogic.deletedIds(archived, present)
        assertEquals(setOf(1L), deleted)

        val led = SnsCommentLogic.TagLedger()
        // ⚠️ CommentState.present = 「这一行还在吗」；1 已不在 present 里 → false → MISSING
        val form = SnsCommentLogic.classify(
            SnsCommentLogic.CommentState(present = 1L in present, content = null),
        )
        assertEquals(SnsCommentLogic.DelForm.MISSING, form)

        val req = SnsCommentLogic.TagRequest(cid(1L), form, "小明：真机评论", "小明")
        assertEquals("小明：[已删除]", SnsCommentLogic.tagText(req, led))
        // 第二次（重绘）：账本 + 文案两道闸都会拦
        assertNull(SnsCommentLogic.tagText(req.copy(existing = "小明：[已删除]"), led))
    }

    // ================================================================== 执行层接线
    //
    // 归档形状 = `Map<snsID, Set<commentSvrID>>`（见 SnsCommentGuard.Archive.ids 的
    // `arrayOf("snsID", "commentSvrID")` + 主键 PRIMARY KEY(snsID, commentSvrID)），
    // 跟 diffDeletedAll 入参一致 → 适配器只做清洗，不换形状。

    @Test
    fun normalizeArchivedIsIdentityForGuardOutput() {
        val raw = mapOf(REAL_SNS to setOf(1L, 2L), -2L to setOf(7L))
        assertEquals(raw, SnsCommentLogic.normalizeArchived(raw))
    }

    @Test
    fun normalizeArchivedDropsInvalidIdsAndEmptyGroups() {
        val raw = mapOf(
            REAL_SNS to setOf(0L, -3L, 4L), // 0 / 负数不是有效 commentSvrID
            -2L to emptySet<Long>(),        // 空组直接丢
            -9L to setOf(1L),
        )
        assertEquals(
            mapOf(REAL_SNS to setOf(4L), -9L to setOf(1L)),
            SnsCommentLogic.normalizeArchived(raw),
        )
        // null / 空 → 空 map（不是 null，下游不用再判）
        assertEquals(emptyMap<Long, Set<Long>>(), SnsCommentLogic.normalizeArchived(null))
        assertEquals(emptyMap<Long, Set<Long>>(), SnsCommentLogic.normalizeArchived(emptyMap()))
    }

    @Test
    fun normalizeArchivedFeedsDiffDeletedAll() {
        val archived = SnsCommentLogic.normalizeArchived(mapOf(REAL_SNS to setOf(1L, 2L, 0L)))
        val all = SnsCommentLogic.diffDeletedAll(archived) { setOf(2L) }
        assertEquals(setOf(1L), all.getValue(REAL_SNS).deleted)
        assertEquals(setOf(2L), all.getValue(REAL_SNS).kept)
        assertTrue(all.getValue(REAL_SNS).unknown.isEmpty())
    }

    // ------------------------------------------------------------------ parseRow

    @Test
    fun parseRowSplitsNickAndBody() {
        val r = SnsCommentLogic.parseRow("小明：今天天气不错")
        assertEquals("小明", r?.nick)
        assertEquals("今天天气不错", r?.body)
        // 半角冒号也认
        assertEquals("小明", SnsCommentLogic.parseRow("小明: hi")?.nick)
        assertEquals("hi", SnsCommentLogic.parseRow("小明: hi")?.body)
        // 昵称对得上 hint 时用 hint 归一
        assertEquals("小明", SnsCommentLogic.parseRow("小明：内容", "小明")?.nick)
    }

    @Test
    fun parseRowAllowsEmojiPrefixButNotLettersInPrefix() {
        // 昵称带 emoji 前缀 → 认（前缀里没有字母数字）
        assertEquals("小明", SnsCommentLogic.parseRow("🌹小明：内容", "小明")?.nick)
        // 前缀里有字母 → 不是同一个人 → 拒
        assertNull(SnsCommentLogic.parseRow("abc小明：内容", "小明"))
        // 完全不同的昵称 → 拒
        assertNull(SnsCommentLogic.parseRow("小红：内容", "小明"))
    }

    @Test
    fun parseRowRefusesWhenUnrecognizable() {
        assertNull(SnsCommentLogic.parseRow(null))
        assertNull(SnsCommentLogic.parseRow("   "))
        // 没有分隔冒号
        assertNull(SnsCommentLogic.parseRow("小明 今天天气不错"))
        // 冒号在很后面（> MAX_NICK）→ 更像是正文里的冒号，不是昵称分隔
        assertNull(SnsCommentLogic.parseRow("长".repeat(40) + "：内容"))
        // 冒号前为空
        assertNull(SnsCommentLogic.parseRow("：只有内容"))
        // 冒号后为空
        assertNull(SnsCommentLogic.parseRow("小明："))
        // 已经打过标 → 没什么可拆
        assertNull(SnsCommentLogic.parseRow("小明：[已删除]"))
    }

    @Test
    fun splitOriginalReturnsBodyOnly() {
        assertEquals("内容", SnsCommentLogic.splitOriginal("小明：内容"))
        assertNull(SnsCommentLogic.splitOriginal("认不出来的文案"))
    }

    // ------------------------------------------------------------------ planTags

    private fun diff(vararg deleted: Long) =
        SnsCommentLogic.DeletedDiff(deleted = deleted.toSet())

    private fun row(svr: Long, existing: String?, nick: String? = null) =
        SnsCommentLogic.TagRow(cid(svr), existing = existing, nick = nick)

    @Test
    fun planTagsAppliesOnlyToDeletedIds() {
        val ops = SnsCommentLogic.planTags(
            SnsCommentLogic.DeletedDiff(
                deleted = setOf(1L),
                kept = setOf(2L),
                unknown = setOf(3L),
            ),
            listOf(row(1L, "小明：被删的"), row(2L, "小红：还在的"), row(3L, "小刚：不知道的")),
        )
        assertEquals(1, ops.size)
        assertEquals(1L, ops[0].id.svrId)
        assertEquals(SnsCommentLogic.TagAction.APPLY, ops[0].action)
        assertEquals("小明：[已删除]", ops[0].text)
    }

    @Test
    fun planTagsIsIdempotentAcrossRuns() {
        val d = diff(1L)
        val rows = listOf(row(1L, "小明：原文"))
        val first = SnsCommentLogic.planTags(d, rows)
        assertEquals(SnsCommentLogic.TagAction.APPLY, first.single().action)

        // 第二次：把第一次的结果当「已打标集合」传回去 → 全 SKIP
        val tagged = SnsCommentLogic.appliedKeys(first)
        val second = SnsCommentLogic.planTags(d, rows, tagged)
        assertEquals(SnsCommentLogic.TagAction.SKIP, second.single().action)
        assertEquals("already-tagged", second.single().reason)

        // 第三次：只靠屏幕上文案已经带 [已删除] 也要拦住（账本丢了也不会重复打）
        val third = SnsCommentLogic.planTags(d, listOf(row(1L, "小明：[已删除]")))
        assertEquals(SnsCommentLogic.TagAction.SKIP, third.single().action)
        assertEquals("already-labeled", third.single().reason)
    }

    @Test
    fun planTagsRejectsDuplicateRowsForSameId() {
        val ops = SnsCommentLogic.planTags(
            diff(1L),
            listOf(row(1L, "小明：原文"), row(1L, "小明：原文")),
        )
        assertEquals(1, ops.count { it.action == SnsCommentLogic.TagAction.APPLY })
        assertEquals(1, ops.count { it.reason == "dup" })
    }

    @Test
    fun planTagsSkipsUnparsableAndAliveAndEmptyDiff() {
        // 认不出文案 → 宁可漏标
        val unparsed = SnsCommentLogic.planTags(diff(1L), listOf(row(1L, "没有冒号的文案")))
        assertEquals(SnsCommentLogic.TagAction.SKIP, unparsed.single().action)
        assertEquals("unparsed", unparsed.single().reason)

        // 差集说删了、状态说还活着 → 信状态
        val alive = SnsCommentLogic.planTags(
            diff(1L),
            listOf(SnsCommentLogic.TagRow(cid(1L), "小明：原文", "小明", SnsCommentLogic.DelForm.ALIVE)),
        )
        assertEquals("alive", alive.single().reason)

        // 差集空 / 只 unknown → 一条 op 都不产生
        assertTrue(SnsCommentLogic.planTags(SnsCommentLogic.DeletedDiff(), listOf(row(1L, "小明：x"))).isEmpty())
        assertTrue(
            SnsCommentLogic.planTags(
                SnsCommentLogic.DeletedDiff(unknown = setOf(1L)),
                listOf(row(1L, "小明：x")),
            ).isEmpty(),
        )
    }

    @Test
    fun planTagsSkipsWhenRowNickContradicts() {
        // 行是别人的文案（昵称对不上）→ 定位不可信 → 不标
        val ops = SnsCommentLogic.planTags(diff(1L), listOf(row(1L, "小红：别人的评论", "小明")))
        assertEquals(SnsCommentLogic.TagAction.SKIP, ops.single().action)
        assertEquals("unparsed", ops.single().reason)

        // 归档里没昵称（trustRowNick=false）→ 不强校验，照常打
        val noNick = SnsCommentLogic.planTags(
            diff(1L),
            listOf(row(1L, "小红：内容", "小明")),
            trustRowNick = false,
        )
        assertEquals(SnsCommentLogic.TagAction.APPLY, noNick.single().action)
        assertEquals("小红：[已删除]", noNick.single().text)
    }

    @Test
    fun planTagsIsDeterministic() {
        val d = diff(1L, 2L)
        val rows = listOf(row(2L, "小红：b"), row(1L, "小明：a"))
        assertEquals(
            SnsCommentLogic.planTags(d, rows).map { it.key },
            SnsCommentLogic.planTags(d, rows.reversed()).map { it.key },
        )
    }

    /** 端到端：归档 → 清洗 → 差集 → 计划 → 回放（第二轮全 SKIP） */
    @Test
    fun planTagsEndToEndFromGuardShape() {
        val archived = SnsCommentLogic.normalizeArchived(mapOf(REAL_SNS to setOf(1L, 2L)))
        val all = SnsCommentLogic.diffDeletedAll(archived) { setOf(2L) }
        val ops = SnsCommentLogic.planTags(
            all.getValue(REAL_SNS),
            listOf(row(1L, "小明：真机评论", "小明"), row(2L, "小红：还在", "小红")),
        )
        assertEquals(listOf("$REAL_SNS:1"), ops.map { it.key })
        assertEquals("小明：[已删除]", ops.single().text)

        val replay = SnsCommentLogic.planTags(
            all.getValue(REAL_SNS),
            listOf(row(1L, "小明：[已删除]", "小明")),
            SnsCommentLogic.appliedKeys(ops),
        )
        assertEquals(SnsCommentLogic.TagAction.SKIP, replay.single().action)
    }

    // ---------------------------------------- 渲染键（RenderKey）与 type 错位诊断
    //
    // 背景：UI 层评论 proto 不带 commentSvrID（实测 q/r 两个 long 恒 0），
    // 只能用 (createTime, talker, type) 认评论。见 docs/取证结论.md ⑩ 段。

    @Test
    fun renderKey_normalizesTalker() {
        val k = SnsCommentLogic.renderKey(1792410895L, "  wxid_abc  ", 2)
        assertEquals("1792410895:wxid_abc:2", SnsCommentLogic.keyOf(k!!))
    }

    @Test
    fun renderKey_rejectsZeroTime() {
        // createTime<=0 = 不可用 → null（宁可漏标，不可误标）
        assertNull(SnsCommentLogic.renderKey(0L, "wxid_abc", 2))
        assertNull(SnsCommentLogic.renderKey(-1L, "wxid_abc", 2))
    }

    @Test
    fun renderKey_rejectsBlankTalker() {
        // 没有 talker 就无法无歧义定位 → null
        assertNull(SnsCommentLogic.renderKey(1792410895L, null, 2))
        assertNull(SnsCommentLogic.renderKey(1792410895L, "   ", 2))
        assertNull(SnsCommentLogic.renderKey(1792410895L, "", 2))
    }

    @Test
    fun typeOnlyMismatch_detectsDifferentType() {
        // (time,talker) 相同、type 不同 → 键语义可能错位，必须能识别出来
        assertTrue(
            SnsCommentLogic.typeOnlyMismatch(
                "1792:wxid_a:2",
                listOf("1792:wxid_a:8", "1800:wxid_b:1"),
            )
        )
    }

    @Test
    fun typeOnlyMismatch_falseWhenExactlyPresent() {
        // 集合里就有完全相同的键 → 不是错位（说明语义一致）
        assertFalse(
            SnsCommentLogic.typeOnlyMismatch("1792:wxid_a:2", listOf("1792:wxid_a:2"))
        )
    }

    @Test
    fun typeOnlyMismatch_falseWhenNothingAlike() {
        // 完全对不上 → 就是"本来没删这条"，不是错位
        assertFalse(
            SnsCommentLogic.typeOnlyMismatch("1792:wxid_a:2", listOf("1800:wxid_b:1"))
        )
    }

    @Test
    fun typeOnlyMismatch_doesNotConfusePrefixTalker() {
        // talker 前缀相同但不同人（wxid_a vs wxid_ab）→ 不算错位，防前缀误判
        assertFalse(
            SnsCommentLogic.typeOnlyMismatch("1792:wxid_a:2", listOf("1792:wxid_ab:5"))
        )
    }

    @Test
    fun typeOnlyMismatch_ignoresMalformedKey() {
        // 没有冒号的畸形键 → 不能抛异常
        assertFalse(SnsCommentLogic.typeOnlyMismatch("bad", listOf("1792:wxid_a:2")))
    }
}
