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

    // ------------------------------------------------------------------ R89 会话列表过滤

    /** 形态 I：主列表全量，8.0.48 真机探针逐字（606 字符，列清单会漂，判据不比对列） */
    private val I_SQL =
        "select unReadCount, status, isSend, conversationTime, username, content, msgType, flag, " +
            "digest, digestUser, attrflag, editingMsg, atCount, unReadMuteCount, UnReadInvite, hasTodo, " +
            "hbMarkRed, remitMarkRed, parentRef from rconversation where " +
            "((parentRef is null) or (parentRef in ('', 'message_fold'))) and " +
            "( 1 != 1  or rconversation.username like '%@im.chatroom' " +
            "or rconversation.username like '%@chatroom' " +
            "or rconversation.username like '%@openim' " +
            "or rconversation.username not like '%@%' )  and " +
            "rconversation.username != 'qmessage' and rconversation.username != 'appbrand_notify_message' " +
            "order by flag desc"

    /** 形态 C：与 I 同前缀，但 WHERE 多 `username in (...)`，且无 order by */
    private val C_SQL =
        "select unReadCount, status, isSend, conversationTime, username, content, msgType, flag, " +
            "digest, digestUser, attrflag, editingMsg, atCount, unReadMuteCount, UnReadInvite, hasTodo, " +
            "hbMarkRed, remitMarkRed, parentRef from rconversation where " +
            "((parentRef is null) or (parentRef in ('', 'message_fold'))) and " +
            "rconversation.username in ('a','b')"

    /** 形态 F：未读聚合，JOIN 版（逗号 join rcontact + unReadCount > 0） */
    private val F_JOIN_SQL =
        "select rconversation.unReadCount, rconversation.username from rconversation, rcontact " +
            "where rconversation.unReadCount > 0 AND rconversation.username = rcontact.username " +
            "AND (rconversation.parentRef is null or parentRef = '' ) AND ( type & 512 ) == 0 " +
            "AND rcontact.username != 'officialaccounts'"

    /** 形态 F：未读聚合，union 版（`wx^_^_%` + union + inner join rcontact） */
    private val F_UNION_SQL =
        "SELECT rconversation.username, rconversation.unReadCount, rconversation.conversationTime " +
            "from rconversation where rconversation.username like 'wx^_^_%' ESCAPE '^' " +
            "union SELECT rconversation.username, rconversation.unReadCount " +
            "from rconversation inner join rcontact on rconversation.username = rcontact.username " +
            "WHERE rcontact.username != 'officialaccounts'"

    private fun rewriteConv(sql: String, ids: List<String> = listOf("wxgrp_a", "wxgrp_b")): String =
        SqlHideCond.rewriteConvSql(sql, ids) ?: throw AssertionError("rewriteConvSql 返回 null: $sql")

    @Test
    fun `I 主列表原文：改写成功且 order by flag desc 仍在末尾`() {
        assertTrue("I 应该被判为会话列表查询", SqlHideCond.isConvSelect(I_SQL))
        val out = rewriteConv(I_SQL)
        assertTrue("注入条件必须带表名限定", out.contains("rconversation.username NOT IN ('wxgrp_a','wxgrp_b')"))
        assertTrue("order by flag desc 必须仍在末尾：$out", out.trimEnd().endsWith("order by flag desc"))
    }

    @Test
    fun `I 主列表原文的 OR 都在括号内：不用整段括号化，只 AND 追加`() {
        val out = rewriteConv(I_SQL)
        // I 的 WHERE 里所有 or 都被 (…) 包住，inject 不会把整段原条件再括号化
        assertFalse("I 的原文不该被整段括号化", out.contains("WHERE ((parentRef is null) or"))
    }

    @Test
    fun `C 批量明细：带 username in 且无 order by，改写成功`() {
        assertTrue(SqlHideCond.isConvSelect(C_SQL))
        val out = rewriteConv(C_SQL, listOf("wxgrp_x"))
        assertTrue(out.contains("rconversation.username NOT IN ('wxgrp_x')"))
        // 原来的 in 不能被误伤（它跟注入的 NOT IN 是两段）
        assertTrue(out.contains("username in ('a','b')"))
    }

    @Test
    fun `F 未读聚合 JOIN 版：改写成功且条件是 rconversation username not in`() {
        assertTrue(SqlHideCond.isConvSelect(F_JOIN_SQL))
        val out = rewriteConv(F_JOIN_SQL)
        assertTrue("JOIN 版必须用表名限定，否则 username 歧义", out.contains("rconversation.username NOT IN ('wxgrp_a','wxgrp_b')"))
    }

    @Test
    fun `F 未读聚合 union 版：改写成功且条件是 rconversation username not in`() {
        assertTrue(SqlHideCond.isConvSelect(F_UNION_SQL))
        val out = rewriteConv(F_UNION_SQL)
        assertTrue(out.contains("rconversation.username NOT IN ('wxgrp_a','wxgrp_b')"))
    }

    @Test
    fun `A 点查 username 等值问号：不改写`() {
        assertFalse(SqlHideCond.isConvSelect("SELECT * FROM rconversation WHERE username=?"))
        org.junit.Assert.assertNull(
            SqlHideCond.rewriteConvSql("SELECT * FROM rconversation WHERE username=?", listOf("wxgrp_a"))
        )
    }

    @Test
    fun `B 点查 username 等值加 unReadCount：不改写`() {
        assertFalse(
            SqlHideCond.isConvSelect(
                "select unReadCount, parentRef from rconversation where username = 'gh_xxx' AND unReadCount > 0"
            )
        )
    }

    @Test
    fun `E 点查 msgCount：不改写`() {
        assertFalse(SqlHideCond.isConvSelect("select msgCount from rconversation where username='x'"))
    }

    @Test
    fun `我方三种查询：不改写`() {
        assertFalse(SqlHideCond.isConvSelect("select username,conversationTime from rconversation"))
        assertFalse(
            SqlHideCond.isConvSelect(
                "select username,flag,conversationTime from rconversation where substr(username,1,6)='wxgrp_'"
            )
        )
        assertFalse(
            SqlHideCond.isConvSelect(
                "select count(*) from rconversation where substr(username,1,6)='wxgrp_'"
            )
        )
    }

    @Test
    fun `G 折叠页：本轮不改写`() {
        assertFalse(
            SqlHideCond.isConvSelect(
                "select * from rconversation where parentRef = 'conversationboxservice' " +
                    "and (unReadCount > 0 or flag > 0) order by flag desc, conversationTime desc"
            )
        )
    }

    @Test
    fun `空 id 集：notInWxids 恒真，rewriteConvSql 返回 null`() {
        val cond = SqlHideCond.notInWxids(emptyList())
        assertTrue("空集必须收口成恒真", cond.contains("1=1"))
        assertFalse("空集不能出现 NOT IN 空括号", cond.contains("NOT IN"))
        org.junit.Assert.assertNull(
            "空 id 集等价于不过滤，rewriteConvSql 应返回 null",
            SqlHideCond.rewriteConvSql(I_SQL, emptyList())
        )
    }

    @Test
    fun `单引号转义：id 里的引号翻倍`() {
        val cond = SqlHideCond.notInWxids(listOf("wxgrp_a'b"))
        assertTrue(cond.contains("'wxgrp_a''b'"))
    }

    @Test
    fun `超 200 条截断：第 201 个 id 不进入条件`() {
        val ids = (0 until 250).map { "wxgrp_$it" }
        val cond = SqlHideCond.notInWxids(ids)
        assertTrue("第 1 个 id 必须在", cond.contains("'wxgrp_0'"))
        assertTrue("第 200 个 id 必须在", cond.contains("'wxgrp_199'"))
        assertFalse("第 201 个 id 必须被截断", cond.contains("'wxgrp_200'"))
    }

    @Test
    fun `超 4096 字符放弃`() {
        val big = "select * from rconversation where ((parentRef is null) or (parentRef in ('', 'message_fold')))" +
            " and content like '" + "a".repeat(4096) + "'"
        assertFalse(SqlHideCond.isConvSelect(big))
    }

    @Test
    fun `已含 MARK 不重复注入`() {
        val already = "select * from rconversation where ((parentRef is null) or (parentRef in ('', 'message_fold')))" +
            " and /*wr*/ rconversation.username NOT IN ('wxgrp_z') order by flag desc"
        assertFalse(SqlHideCond.isConvSelect(already))
        org.junit.Assert.assertNull(
            SqlHideCond.rewriteConvSql(already, listOf("wxgrp_a"))
        )
    }

    @Test
    fun `带 BYPASS 前缀直接放行`() {
        assertFalse(
            SqlHideCond.isConvSelect(
                AntiRevokeHint.BYPASS + "select username from rconversation where ((parentRef is null) or (parentRef in ('', 'message_fold')))"
            )
        )
    }

    // ------------------------------------------------------------------ R89 顶层 union 分段注入

    /** F union 版实测原文（8.0.48 逐字；第二分支 `inner join rcontact` 正是未读主查询） */
    private val F_UNION_REAL_SQL =
        "SELECT rconversation.username, rconversation.unReadCount, rconversation.conversationTime " +
            "from rconversation where rconversation.username like 'wx^_^_%' ESCAPE '^' " +
            "union SELECT rconversation.username, rconversation.unReadCount, rconversation.conversationTime " +
            "from rconversation inner join rcontact WHERE rconversation.username=rcontact.username " +
            "AND unReadCount > 0 AND (parentRef is null or parentRef = '' ) " +
            "and ( rcontact.usernameFlag in ( 4 , 2 , 65536 , 0 )  ) " +
            "and rconversation.username not in ('') and rconversation.username not in ('gh_70c4b93987c4')"

    private fun countOcc(s: String, needle: String): Int {
        var n = 0
        var i = s.indexOf(needle)
        while (i >= 0) {
            n++
            i = s.indexOf(needle, i + needle.length)
        }
        return n
    }

    @Test
    fun `F union 实测原文：两个分支各注入一处，union 不吞，ESCAPE 保留`() {
        assertTrue(SqlHideCond.isConvSelect(F_UNION_REAL_SQL))
        val out = rewriteConv(F_UNION_REAL_SQL, listOf("wxgrp_a", "wxgrp_b"))
        // 两个分支各有且仅有一处 NOT IN（第一分支 + 第二分支 inner join rcontact）
        assertEquals(2, countOcc(out, "rconversation.username NOT IN ("))
        // union 出现次数与原文相同（关键字没被吞）
        assertEquals(countOcc(F_UNION_REAL_SQL, "union"), countOcc(out, "union"))
        // 第一分支的 ESCAPE '^' 仍在
        assertTrue(out.contains("ESCAPE '^'"))
    }

    @Test
    fun `内层 union 不是顶层：走单段 inject，union 留在子查询原位`() {
        val sql = "select * from rconversation where ((parentRef is null) or (parentRef in ('', 'message_fold'))) " +
            "and username in (select username from rconversation union select username from rconversation)"
        assertTrue(SqlHideCond.isConvSelect(sql))
        val out = rewriteConv(sql)
        // 没有按顶层切：只出现 1 处 NOT IN
        assertEquals(1, countOcc(out, "rconversation.username NOT IN ("))
        // 原 union 仍在内层原位
        assertTrue(out.contains("in (select username from rconversation union select username from rconversation)"))
        assertEquals(1, countOcc(out, "union"))
    }

    @Test
    fun `union all 版能正确切分，all 不丢`() {
        val sql = "SELECT rconversation.username from rconversation " +
            "where rconversation.username like 'wx^_^_%' ESCAPE '^' " +
            "union all SELECT rconversation.username from rconversation inner join rcontact " +
            "on rconversation.username = rcontact.username WHERE rcontact.username != 'officialaccounts'"
        assertTrue(SqlHideCond.isConvSelect(sql))
        val out = rewriteConv(sql)
        // 两分支各一处 NOT IN
        assertEquals(2, countOcc(out, "rconversation.username NOT IN ("))
        // union all 关键字整体保留（含 all 与中间空格）
        assertTrue(out.contains("union all"))
    }

    @Test
    fun `段数超过 4 返回 null`() {
        val sql = "select * from rconversation where ((parentRef is null) or (parentRef in ('', 'message_fold'))) " +
            "union select * from rconversation " +
            "union select * from rconversation " +
            "union select * from rconversation " +
            "union select * from rconversation"
        org.junit.Assert.assertNull(
            SqlHideCond.rewriteConvSql(sql, listOf("wxgrp_a", "wxgrp_b"))
        )
    }
}
