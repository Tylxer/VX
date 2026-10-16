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

    // ------------------------------------------------------------------ 诊断输出

    @Test
    fun hexHeadOnly() {
        assertEquals("(null)", SnsCommentLogic.hex(null))
        assertEquals("(empty)", SnsCommentLogic.hex(ByteArray(0)))
        assertEquals("010203", SnsCommentLogic.hex(byteArrayOf(1, 2, 3)))
        assertEquals("010203040506", SnsCommentLogic.hex(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertEquals("0102", SnsCommentLogic.hex(byteArrayOf(1, 2, 3), 2))
    }
}
