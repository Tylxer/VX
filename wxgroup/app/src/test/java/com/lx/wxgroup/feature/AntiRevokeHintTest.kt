package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AntiRevokeHint.inject] 的 SQL 改写测试。
 *
 * 用例全部来自 **8.0.48 真机探针抓到的真实 SQL**（不是编的）：
 * 聊天页分页是嵌套子查询形式，朴素的 `indexOf("order by")` 会插进子查询里，
 * 结果就是「先 LIMIT 16 再过滤」—— 撤回提示照样显示，而且分页语义被改坏。
 * 这类错误在手机上表现为「开关开了没效果」，很难定位，所以在 JVM 上锁死。
 *
 * `inject` 只做纯字符串处理，不碰 Android API，所以能直接跑单测。
 */
class AntiRevokeHintTest {

    /** 一段假的条件片段：单测只关心「插在哪儿」，不关心条件内容 */
    private val COND = SqlHideCond.notIn(listOf(11L, 22L))

    private fun rewrite(sql: String): String =
        SqlHideCond.inject(sql, COND) ?: throw AssertionError("inject 返回 null: $sql")

    /** 条件片段在结果里的位置（`AND (NOT (` 或 `WHERE (NOT (`） */
    private fun condAt(out: String): Int {
        val i = out.indexOf("(/*wr*/ msgId NOT IN")
        assertTrue("结果里没有过滤条件: $out", i >= 0)
        return i
    }

    @Test
    fun `嵌套子查询：条件必须插在外层 ORDER BY 之前，不能进子查询`() {
        val sql = "SELECT * FROM ( SELECT * FROM message WHERE talker= 'wxid_xlrbelf6hv0w22'  " +
            "ORDER BY createTime DESC LIMIT 16) ORDER BY createTime ASC"
        val out = rewrite(sql)

        val c = condAt(out)
        val limitAt = out.indexOf("LIMIT 16")
        val outerOrderAt = out.lastIndexOf("ORDER BY createTime ASC")

        assertTrue("外层没有 WHERE，应该新建 WHERE 而不是 AND", out.contains("WHERE (/*wr*/"))
        assertTrue("条件必须落在 LIMIT 16 之后（即子查询外面）", c > limitAt)
        assertTrue("条件必须落在外层 ORDER BY 之前", c < outerOrderAt)
        // 子查询的 where 不能被污染
        assertTrue(
            "子查询里的 WHERE talker 不能被改坏",
            out.contains("WHERE talker= 'wxid_xlrbelf6hv0w22'  ORDER BY createTime DESC LIMIT 16")
        )
    }

    @Test
    fun `简单查询带 order by：用 AND 追加在 ORDER BY 之前`() {
        val sql = "select * from message where talker= 'wxid_x' order by createTime DESC limit 1"
        val out = rewrite(sql)

        assertTrue("已有 WHERE，应该用 AND 追加", out.contains("AND (/*wr*/"))
        val c = condAt(out)
        assertTrue(c > out.indexOf("where talker="))
        assertTrue(c < out.indexOf("order by createTime DESC"))
    }

    @Test
    fun `没有 order by：追加到末尾`() {
        val out = rewrite("SELECT * FROM message WHERE msgId=?")
        assertTrue(out.startsWith("SELECT * FROM message WHERE msgId=? AND (/*wr*/"))
    }

    @Test
    fun `聚合查询：追加到末尾且保留原有条件`() {
        val sql = "SELECT COUNT(msgId) FROM message WHERE talker= 'wxid_x' " +
            "AND createTime >= 0 AND createTime <= 0"
        val out = rewrite(sql)
        assertTrue(out.startsWith(sql))
        assertTrue(out.endsWith("AND (/*wr*/ msgId NOT IN (11,22))"))
    }

    @Test
    fun `尾部有分号：条件插在分号之前`() {
        val out = rewrite("SELECT * FROM message WHERE talker='x' AND flag=0;")
        assertTrue("分号必须还在最后", out.endsWith(";"))
        val c = condAt(out)
        assertTrue("条件必须在分号之前", c < out.lastIndexOf(';'))
    }

    @Test
    fun `单引号里的括号不算子查询`() {
        // talker 字面量里带 '(' —— 朴素扫描会把后面全当子查询，就找不到最外层位置了
        val sql = "select * from message where talker='a(b' order by createTime desc"
        val out = rewrite(sql)
        assertTrue(out.contains("AND (/*wr*/"))
        assertTrue(condAt(out) < out.indexOf("order by createTime desc"))
    }

    @Test
    fun `改写后的括号必须配对`() {
        val cases = listOf(
            "SELECT * FROM ( SELECT * FROM message WHERE talker= 'x' ORDER BY createTime DESC LIMIT 16) ORDER BY createTime ASC",
            "select * from message where talker= 'x' order by createTime DESC limit 1",
            "SELECT * FROM message WHERE msgId=?",
            "SELECT COUNT(msgId) FROM message WHERE talker= 'x' AND createTime >= 0;",
        )
        for (sql in cases) {
            val out = rewrite(sql)
            assertEquals(
                "括号不配对：$out",
                out.count { it == '(' },
                out.count { it == ')' }
            )
        }
    }

    @Test
    fun `子查询投影不是星号时放弃改写`() {
        // 内层 SELECT msgId 的话，外层 WHERE 引用 type/content 会 "no such column"
        // —— 宁可这条查询不隐藏提示，也不能让微信一开聊天页就崩
        val out = SqlHideCond.inject(
            "SELECT * FROM ( SELECT msgId FROM message WHERE talker='x') ORDER BY msgId", COND
        )
        org.junit.Assert.assertNull("应该放弃改写", out)
    }

    @Test
    fun `count 函数的括号不能当成子查询`() {
        val out = SqlHideCond.inject("select count(*) from message where talker='x'", COND)
        org.junit.Assert.assertNotNull("count(*) 不该被误判成子查询", out)
        assertTrue(out!!.contains("AND (/*wr*/"))
    }

    @Test
    fun `字符串字面量里的关键字不能当成子句边界`() {
        // 搜索聊天记录时会出现这种 SQL。若把 '%limit%' 里的 limit 当成子句边界，
        // 条件会被插进引号里 —— 引号成对所以不报语法错，只是搜索永远 0 行
        val sql = "select * from message where content LIKE '%limit%' order by createTime desc"
        val out = rewrite(sql)
        assertTrue("条件必须落在 LIKE 之后、order by 之前", condAt(out) > out.indexOf("%limit%'"))
        assertTrue(condAt(out) < out.indexOf("order by createTime desc"))
        assertTrue("引号必须仍然成对", out.count { it == '\'' } % 2 == 0)
    }

    @Test
    fun `按主键点查不改写`() {
        // 引用原文 / 转发 / 通知跳转靠它取行，过滤掉会让那些入口取不到消息
        assertTrue(SqlHideCond.isMessageSelect("SELECT * FROM message WHERE msgId=?") == false)
        assertTrue(SqlHideCond.isMessageSelect("SELECT * FROM message WHERE msgSvrId=?") == false)
    }

    @Test
    fun `模块自己的诊断 SQL 带 BYPASS 前缀时不改写`() {
        assertTrue(
            SqlHideCond.isMessageSelect(AntiRevokeHint.BYPASS + "select msgId from message") == false
        )
    }

    @Test
    fun `子查询投影 distinct 星号或别名点星号都放行`() {
        org.junit.Assert.assertNotNull(
            SqlHideCond.inject("SELECT * FROM ( SELECT DISTINCT * FROM message WHERE talker='x') ORDER BY msgId", COND)
        )
        org.junit.Assert.assertNotNull(
            SqlHideCond.inject("SELECT * FROM ( SELECT t.* FROM message t) ORDER BY msgId", COND)
        )
    }

    @Test
    fun `嵌套子查询里层投影不是星号时要拦住`() {
        // 外层看起来是 SELECT *，但里层只投影 msgId，外层 WHERE 仍然引用不到 type
        org.junit.Assert.assertNull(
            SqlHideCond.inject(
                "SELECT * FROM ( SELECT * FROM ( SELECT msgId FROM message WHERE talker='x')) ORDER BY msgId", COND
            )
        )
    }

    @Test
    fun `group by 与 union 也算子句边界`() {
        val out = rewrite("SELECT talker FROM message GROUP BY talker")
        assertTrue(condAt(out) < out.indexOf("GROUP BY talker"))
        assertTrue(out.contains("WHERE (/*wr*/"))
    }

    @Test
    fun `提示行清单为空时条件是恒真，不能生成 NOT IN 空括号`() {
        // `msgId NOT IN ()` 是 SQLite 语法错误 —— 一旦进到真实查询里会触发熔断，
        // 把整个「隐藏提示条」永久废掉。空清单必须收口成恒真条件。
        val cond = SqlHideCond.notIn(emptyList())
        assertFalse(cond.contains("NOT IN"))
        assertTrue(cond.contains("1=1"))
        val out = SqlHideCond.inject(
            "SELECT * FROM message WHERE talker='x' ORDER BY createTime", cond
        ) ?: throw AssertionError("inject 返回 null")
        assertTrue(out.contains("AND (/*wr*/ 1=1)"))
    }

    @Test
    fun `原 WHERE 里有 OR 时必须整段括号化：否则 AND 优先级会吃掉条件`() {
        // `where a or b` 直接 AND 追加会变成 `a OR (b AND cond)` —— 语法完全合法、
        // 不触发熔断，提示条却照样显示，是最难查的一类静默失效。
        val out = rewrite("select * from message where talker='x' or talker='y' order by createTime")
        assertTrue(
            "原条件必须先整段括号化再 AND：$out",
            out.contains("WHERE (talker='x' or talker='y') AND (/*wr*/")
        )
    }

    @Test
    fun `字符串字面量里的 order by 不能当子句边界`() {
        val out = rewrite("select * from message where content like '%order by%' order by createTime")
        val c = condAt(out)
        assertTrue(c > out.indexOf("'%order by%'"))
        assertTrue(c < out.lastIndexOf("order by createTime"))
    }

    @Test
    fun `子查询带别名时也能插到外层`() {
        val out = rewrite(
            "SELECT * FROM ( SELECT * FROM message WHERE talker='x' LIMIT 16) t ORDER BY createTime"
        )
        assertTrue(condAt(out) > out.indexOf("LIMIT 16"))
    }

    @Test
    fun `group by 加 having`() {
        val out = rewrite("SELECT talker FROM message GROUP BY talker HAVING count(*) > 1")
        assertTrue(condAt(out) < out.indexOf("GROUP BY talker"))
        assertTrue(out.contains("HAVING count(*) > 1"))
    }

    @Test
    fun `带 join 的查询不改写：否则列名歧义会触发熔断`() {
        assertFalse(
            SqlHideCond.isMessageSelect(
                "SELECT m.* FROM message m INNER JOIN rcontact c ON c.username=m.talker"
            )
        )
    }
}
