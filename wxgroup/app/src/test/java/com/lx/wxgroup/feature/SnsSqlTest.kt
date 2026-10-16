package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnsSqlTest {

    private val ID = -3447438253133393222L

    // ---------------------------------------------------------- delete(table, where, args)

    @Test
    fun `真机抓到的删动态调用要认出来`() {
        assertEquals(
            ID,
            SnsSql.matchDeleteCall("SnsInfo", "snsId=?", arrayOf<Any>(ID))
        )
    }

    @Test
    fun `whereArgs 是 String 数组也能认`() {
        assertEquals(
            ID,
            SnsSql.matchDeleteCall("SnsInfo", "snsId=?", arrayOf<Any>(ID.toString()))
        )
    }

    @Test
    fun `whereArgs 为空时从 where 里解析字面量`() {
        assertEquals(
            ID,
            SnsSql.matchDeleteCall("SnsInfo", "snsId=-3447438253133393222", null)
        )
    }

    @Test
    fun `按时间清理不能当成删动态拦掉`() {
        assertNull(SnsSql.matchDeleteCall("SnsInfo", "createTime < ?", arrayOf<Any>(1700000000)))
        assertNull(SnsSql.matchDeleteCall("SnsInfo", "createTime<?", arrayOf<Any>(1700000000)))
    }

    @Test
    fun `按 snsId 范围的清理也不能拦`() {
        // ⚠️ 只出现过 snsId 单词不算 —— 这是按 id 范围的批量清理，
        // 拦了会让 766MB 的 SnsMicroMsg.db 只涨不删
        assertNull(SnsSql.matchDeleteCall("SnsInfo", "snsId < ?", arrayOf<Any>(ID)))
        assertNull(SnsSql.matchDeleteCall("SnsInfo", "snsId>?", arrayOf<Any>(ID)))
        assertNull(SnsSql.matchDeleteSql("delete from SnsInfo where snsId < 123"))
    }

    @Test
    fun `where 里 snsId 不在第一个参数时要按占位符序号取`() {
        // `localFlag=? and snsId=?`：snsId 是第 2 个占位符，取 args[1]。
        // 取第一个数字会拿到 localFlag，照样拦下来但归档 id 是错的
        assertEquals(
            ID,
            SnsSql.matchDeleteCall("SnsInfo", "localFlag=? and snsId=?", arrayOf<Any>(2, ID))
        )
    }

    @Test
    fun `64 位 snsId 不能被截断`() {
        assertEquals(ID, SnsSql.matchDeleteCall("SnsInfo", "snsId=?", arrayOf<Any>(ID)))
        assertEquals(ID, SnsSql.matchDeleteSql("delete from SnsInfo where snsId = $ID"))
    }

    @Test
    fun `别的表不认`() {
        assertNull(SnsSql.matchDeleteCall("SnsComment", "snsId=?", arrayOf<Any>(ID)))
        assertNull(SnsSql.matchDeleteCall("message", "msgId=?", arrayOf<Any>(1L)))
        assertNull(SnsSql.matchDeleteCall(null, "snsId=?", arrayOf<Any>(ID)))
    }

    @Test
    fun `表名大小写不敏感`() {
        assertEquals(ID, SnsSql.matchDeleteCall("snsinfo", "snsId=?", arrayOf<Any>(ID)))
    }

    @Test
    fun `snsId 为 0 不算命中`() {
        assertNull(SnsSql.matchDeleteCall("SnsInfo", "snsId=?", arrayOf<Any>(0L)))
    }

    // ---------------------------------------------------------- execSQL(sql)

    @Test
    fun `delete from SnsInfo 的裸 SQL 要认出来`() {
        assertEquals(
            ID,
            SnsSql.matchDeleteSql("delete from SnsInfo where snsId = -3447438253133393222")
        )
    }

    @Test
    fun `删 SnsComment 的 SQL 不能认成 SnsInfo`() {
        assertNull(
            SnsSql.matchDeleteSql(
                "delete from SnsComment where snsID = -3442401033659805004 and commentSvrID = 1"
            )
        )
    }

    @Test
    fun `非 delete 语句不认`() {
        assertNull(SnsSql.matchDeleteSql("select * from SnsInfo where snsId = 1"))
        assertNull(SnsSql.matchDeleteSql("update SnsInfo set localFlag=2 where snsId = 1"))
        assertNull(SnsSql.matchDeleteSql(null))
    }

    @Test
    fun `SQL 大小写与多余空白都不影响`() {
        assertEquals(
            123L,
            SnsSql.matchDeleteSql("  DELETE  FROM  snsinfo  WHERE  SNSID  =  123  ")
        )
    }

    @Test
    fun `SnsInfo 出现在别处不算（比如子查询里）`() {
        // 只有 `from SnsInfo` 才算删这张表；`delete from X where snsId in (select ... from SnsInfo)`
        // 这种不该被当成删 SnsInfo
        assertNull(
            SnsSql.matchDeleteSql(
                "delete from SnsMedia where snsId in (select snsId from SnsInfo)"
            )
        )
    }

    @Test
    fun `参数化 execSQL 要从 bindArgs 里取 id`() {
        // SQL 里没有字面量数字，只能靠第二个参数 —— 第一版就是漏了这个，
        // 导致 execSQL 分支对参数化写法完全失效
        assertEquals(
            ID,
            SnsSql.matchDeleteSql("delete from SnsInfo where snsId=?", arrayOf<Any>(ID))
        )
        assertEquals(
            ID,
            SnsSql.matchDeleteSql(
                "delete from SnsInfo where localFlag=? and snsId=?", arrayOf<Any>(2, ID)
            )
        )
    }

    @Test
    fun `没有 snsId 条件的整表清理不拦`() {
        assertNull(SnsSql.matchDeleteSql("delete from SnsInfo"))
        assertNull(SnsSql.matchDeleteSql("delete from SnsInfo where createTime < 1"))
    }
}
