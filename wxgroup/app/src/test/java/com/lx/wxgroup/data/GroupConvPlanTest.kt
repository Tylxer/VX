package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GroupConvPlan] 的判据 + 安全回归测试。
 *
 * 判据来自 `docs/_r54_gconv_design.md` §10（阶段0 真机定案）：写两表出分组行、
 * 组名写 `rcontact.nickname`、`flag=2^62+time`。这里把「username 编解码 / flag 逐位 /
 * 差量计划 / SQL 拼装」全钉成纯 JVM 用例 —— 这些一旦错，真机表现是「点开错组」或
 * 「删到宿主真实会话」，而 hook 代码只能上真机验。
 */
class GroupConvPlanTest {

    private fun d(gid: Long, name: String = "g$gid", time: Long = gid * 1000, order: Int = 0) =
        GroupConvPlan.Desired(gid, name, time, order)

    /** 与 desired 完全一致的一条现存行（flag/time/nickname 全对） */
    private fun ex(x: GroupConvPlan.Desired) = GroupConvPlan.Existing(
        GroupConvPlan.usernameOf(x.groupId),
        GroupConvPlan.flagOf(x.timeMs),
        x.timeMs,
        x.name,
    )

    private fun assertSafeSql(sql: String) {
        assertFalse("SQL 不该含分号（多语句）: $sql", sql.contains(";"))
        assertFalse("SQL 不该含 drop: $sql", sql.lowercase().contains("drop"))
    }

    private fun assertHas(sql: String, vararg parts: String) {
        for (p in parts) assertTrue("SQL 缺 [$p]: $sql", sql.contains(p))
    }

    /** 字面量内部的每个单引号都必须成对（''），否则注入串能闭合字符串 */
    private fun assertAllQuotesPaired(q: String) {
        assertTrue("字面量必须以单引号包裹: $q", q.length >= 2 && q.startsWith("'") && q.endsWith("'"))
        val inner = q.substring(1, q.length - 1)
        var i = 0
        while (i < inner.length) {
            if (inner[i] == '\'') {
                assertTrue("内部单引号必须成对转义: $q", i + 1 < inner.length && inner[i + 1] == '\'')
                i += 2
            } else i++
        }
    }

    // ------------------------------------------------------------ username 编解码

    @Test
    fun username_groupId_roundtrip_boundaries() {
        for (g in listOf(0L, 1L, 42L, -1L, -42L, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 1)) {
            val u = GroupConvPlan.usernameOf(g)
            assertEquals("往返 $g", g, GroupConvPlan.groupIdOf(u))
            assertEquals("username 长度恒 22: $u", 22, u.length)
            assertTrue(u.startsWith(GroupConvPlan.PREFIX))
        }
    }

    @Test
    fun usernameOf_is_fixed_width_lowercase_hex() {
        assertEquals("wxgrp_0000000000000000", GroupConvPlan.usernameOf(0))
        assertEquals("wxgrp_0000000000000001", GroupConvPlan.usernameOf(1))
        assertEquals("wxgrp_ffffffffffffffff", GroupConvPlan.usernameOf(-1))
        assertEquals("wxgrp_8000000000000000", GroupConvPlan.usernameOf(Long.MIN_VALUE))
        assertEquals("wxgrp_7fffffffffffffff", GroupConvPlan.usernameOf(Long.MAX_VALUE))
        val hex = GroupConvPlan.usernameOf(-42).removePrefix(GroupConvPlan.PREFIX)
        assertEquals(16, hex.length)
        assertTrue("hex 必须全小写", hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun groupIdOf_rejects_foreign_prefix_empty_bad_hex_and_overflow() {
        assertEquals(1L, GroupConvPlan.groupIdOf("wxgrp_0000000000000001"))
        assertEquals(-1L, GroupConvPlan.groupIdOf("wxgrp_ffffffffffffffff"))
        assertEquals(Long.MIN_VALUE, GroupConvPlan.groupIdOf("wxgrp_8000000000000000"))
        for (bad in listOf(
            "wx__c_0000000000000001",   // 竞品 / 微信内部前缀，不是本方
            "wxid_abc",                  // 真实个人
            "WXGRP_0000000000000001",   // 大小写不符
            "wxgrp_",                    // 空 hex
            "wxgrp_zzzz",                // 非法 hex
            "wxgrp_t1",                  // trial 探测行，非法 hex
            "wxgrp_GG",
            "wxgrp_10000000000000000",  // 2^64，溢出
            "",
        )) {
            assertNull("[$bad] 必须反解失败", GroupConvPlan.groupIdOf(bad))
        }
        assertNull(GroupConvPlan.groupIdOf(null))
    }

    @Test
    fun isOurs_rejects_foreign_empty_null_bad_hex() {
        assertTrue(GroupConvPlan.isOurs("wxgrp_0000000000000001"))
        assertTrue(GroupConvPlan.isOurs("wxgrp_ffffffffffffffff"))
        for (bad in listOf("wx__c_x", "", "wxgrp_", "wxgrp_zzzz", "wxgrp_t1", "wxid_a", "WXGRP_0000000000000001")) {
            assertFalse("[$bad] 不该算本方可处理行", GroupConvPlan.isOurs(bad))
        }
        assertFalse(GroupConvPlan.isOurs(null))
        // 与反解自洽：能反解出 id 的才算 isOurs
        for (g in listOf(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val u = GroupConvPlan.usernameOf(g)
            assertEquals(GroupConvPlan.isOurs(u), GroupConvPlan.groupIdOf(u) != null)
            assertTrue(GroupConvPlan.isOurs(u))
        }
    }

    // ------------------------------------------------------------ flag

    @Test
    fun flagOf_exact_pin_bit() {
        assertEquals(1L shl 62, GroupConvPlan.PIN_BASE)
        assertEquals(4611686018427387904L, GroupConvPlan.PIN_BASE)
        // 真机逐位吻合（§5.1）：4611687812833690843 ↔ 1794406302939
        assertEquals(4611687812833690843L, GroupConvPlan.flagOf(1794406302939L))
        assertEquals(GroupConvPlan.PIN_BASE, GroupConvPlan.flagOf(0))   // time=0 仍属置顶区
        for (t in listOf(1L, 999L, 1794406302939L, 253402300799999L)) {
            assertEquals("逐位 t=$t", GroupConvPlan.PIN_BASE + t, GroupConvPlan.flagOf(t))
        }
    }

    // ------------------------------------------------------------ 空组 fallback（默认置顶，R68）

    @Test
    fun effectiveTimeMs_emptyGroup_fallsBackToCreatedAt() {
        val createdAt = 1794406302939L   // 2026-ish 毫秒时间戳，与 conversationTime 同量纲
        // 空组 / 成员都无会话 ⇒ fallback 必须是 createdAt，不是 0L。
        // 变异 M8：把 effectiveTimeMs 的 `?: fallbackMs` 改回 `?: 0L` ⇒ 这两条断言变红。
        assertEquals(createdAt, GroupConvPlan.effectiveTimeMs(emptyList(), createdAt))
        assertEquals(createdAt, GroupConvPlan.effectiveTimeMs(listOf(), createdAt))
        // 有会话时间的成员：取组内最新一条，已有内容的组行为逐字不变。
        assertEquals(900L, GroupConvPlan.effectiveTimeMs(listOf(100L, 900L, 400L), createdAt))
        assertEquals(5L, GroupConvPlan.effectiveTimeMs(listOf(5L), createdAt))
        // createdAt=0 脏数据：退化为 0、不崩（仍属置顶区但排最末）。
        assertEquals(0L, GroupConvPlan.effectiveTimeMs(emptyList(), 0L))
    }

    @Test
    fun effectiveTimeMs_emptyGroup_flagLandsInTopPinRegion() {
        val createdAt = 1794406302939L
        val t = GroupConvPlan.effectiveTimeMs(emptyList(), createdAt)
        val flag = GroupConvPlan.flagOf(t)
        // flag > PIN_BASE ⇒ 在置顶区内；createdAt 时间戳远大于微X 行 conversationTime=1 ⇒ 排在前面。
        // 变异 M8（fallback 改回 0L）与 M9（flagOf 去掉 PIN_BASE）中任一都会让这两条变红。
        assertTrue("空组 flag 必须 > PIN_BASE: $flag", flag > GroupConvPlan.PIN_BASE)
        assertTrue("空组 flag 必须大于微X time=1 的置顶行: $flag", flag > GroupConvPlan.flagOf(1))
        // 对照旧行为：time=0 时 flag==PIN_BASE，置顶区内部按 time 降序排最末 ⇒ 落进折叠区。
        assertEquals(GroupConvPlan.PIN_BASE, GroupConvPlan.flagOf(0))
        assertTrue("createdAt fallback 的 flag 必须严格大于 0L fallback 的 flag",
            flag > GroupConvPlan.flagOf(0L))
        // 不溢出：PIN_BASE(2^62) + createdAt(~1.79e12) 远小于 Long.MAX_VALUE。
        assertEquals(GroupConvPlan.PIN_BASE + createdAt, flag)
        assertTrue("flag 不该溢出", flag > 0 && flag < Long.MAX_VALUE)
    }

    // ------------------------------------------------------------ SQL 字面量

    @Test
    fun quote_escapes_single_quote_injection() {
        assertEquals("'abc'", GroupConvPlan.quote("abc"))
        assertEquals("''", GroupConvPlan.quote(""))
        assertEquals("'a''b'", GroupConvPlan.quote("a'b"))
        assertEquals("''''", GroupConvPlan.quote("'"))
        val inj = "x'; drop table rconversation; --"
        val q = GroupConvPlan.quote(inj)
        assertEquals("'x''; drop table rconversation; --'", q)
        assertAllQuotesPaired(q)   // 所有单引号成对 ⇒ 注入串被完整包在一个字面量里，闭不了串
    }

    // ------------------------------------------------------------ plan 四种 Op 分支

    @Test
    fun plan_delete_when_existing_not_desired() {
        val ops = GroupConvPlan.plan(emptyList(), listOf(ex(d(1, "A", 100))))
        assertEquals(1, ops.size)
        assertEquals(GroupConvPlan.Op.Delete(GroupConvPlan.usernameOf(1)), ops[0])
    }

    @Test
    fun plan_insert_when_desired_not_existing() {
        val a = d(2, "B", 200)
        val ops = GroupConvPlan.plan(listOf(a), emptyList())
        assertEquals(1, ops.size)
        assertEquals(GroupConvPlan.Op.Insert(a), ops[0])
    }

    @Test
    fun plan_flag_time_update_wins_over_rename() {
        val a = d(3, "NEW", 300)
        val base = ex(a).copy(nickname = "OLD")
        // 仅 flag 漂移
        assertEquals(
            listOf(GroupConvPlan.Op.Update(a, "flag/time")),
            GroupConvPlan.plan(listOf(a), listOf(base.copy(flag = 0))),
        )
        // 仅 time 漂移（flag 仍对）——靠 || 命中，改成 && 就会漏判
        assertEquals(
            listOf(GroupConvPlan.Op.Update(a, "flag/time")),
            GroupConvPlan.plan(listOf(a), listOf(base.copy(conversationTime = 0))),
        )
        // flag/time 与 name 同时不符：只产一条 Update，reason 必须是 flag/time（优先级）
        val both = base.copy(flag = 0, conversationTime = 0, nickname = "OLD")
        val ops = GroupConvPlan.plan(listOf(a), listOf(both))
        assertEquals(1, ops.size)
        assertEquals(GroupConvPlan.Op.Update(a, "flag/time"), ops[0])
    }

    @Test
    fun plan_rename_update() {
        val a = d(4, "NEW", 400)
        val e = ex(a).copy(nickname = "OLD")   // flag/time 全对，只有名字不符
        assertEquals(
            listOf(GroupConvPlan.Op.Update(a, "rename")),
            GroupConvPlan.plan(listOf(a), listOf(e)),
        )
    }

    @Test
    fun plan_consistent_produces_no_op() {
        val a = d(5, "OK", 500)
        assertTrue(GroupConvPlan.plan(listOf(a), listOf(ex(a))).isEmpty())
    }

    @Test
    fun plan_empty_group_timeZero_stays_pinned() {
        // R68 后真实空组 timeMs=createdAt（见 effectiveTimeMs_* 用例）；这里保留 time=0
        // 覆盖 createdAt=0 脏数据 / 宿主旧行：仍在置顶区（flag=PIN_BASE），且 clean/自愈判据不变。
        val a = d(6, "空组", 0)   // timeMs = 0：createdAt=0 脏数据或宿主遗留旧行
        assertEquals(GroupConvPlan.PIN_BASE, GroupConvPlan.flagOf(0))
        assertTrue(GroupConvPlan.plan(listOf(a), listOf(ex(a))).isEmpty())
        // 置顶位被清掉（flag 掉到 0）⇒ 必须判成 flag/time 重修
        val drift = ex(a).copy(flag = 0)
        assertEquals(
            listOf(GroupConvPlan.Op.Update(a, "flag/time")),
            GroupConvPlan.plan(listOf(a), listOf(drift)),
        )
    }

    @Test
    fun plan_output_order_is_deterministic() {
        // desired: 1,2,9 插入；4,5 更新；7 一致(无 op)
        // existing: 3,8 删除；4,5 更新(flag 漂移)；7 一致
        val desired = listOf(d(1), d(2), d(4), d(5), d(7), d(9))
        val existing = listOf(
            ex(d(3)),                    // 多余 → Delete
            ex(d(4)).copy(flag = 0),     // flag 漂移 → Update
            ex(d(5)).copy(flag = 0),     // flag 漂移 → Update
            ex(d(7)),                    // 一致 → 无 op
            ex(d(8)),                    // 多余 → Delete
        )
        val ops = GroupConvPlan.plan(desired, existing)

        // 分组顺序：Delete → Update → Insert
        assertEquals(7, ops.size)
        assertTrue(ops[0] is GroupConvPlan.Op.Delete)
        assertTrue(ops[1] is GroupConvPlan.Op.Delete)
        assertTrue(ops[2] is GroupConvPlan.Op.Update)
        assertTrue(ops[3] is GroupConvPlan.Op.Update)
        assertTrue(ops[4] is GroupConvPlan.Op.Insert)
        assertTrue(ops[5] is GroupConvPlan.Op.Insert)
        assertTrue(ops[6] is GroupConvPlan.Op.Insert)

        fun u(op: GroupConvPlan.Op) = when (op) {
            is GroupConvPlan.Op.Delete -> op.username
            is GroupConvPlan.Op.Update -> GroupConvPlan.usernameOf(op.d.groupId)
            is GroupConvPlan.Op.Insert -> GroupConvPlan.usernameOf(op.d.groupId)
        }
        assertEquals(
            listOf(
                GroupConvPlan.usernameOf(3), GroupConvPlan.usernameOf(8),   // Delete 组内字典序
                GroupConvPlan.usernameOf(4), GroupConvPlan.usernameOf(5),   // Update 组内
                GroupConvPlan.usernameOf(1), GroupConvPlan.usernameOf(2),   // Insert 组内
                GroupConvPlan.usernameOf(9),
            ),
            ops.map { u(it) },
        )
        // 输入顺序反转，输出逐字节不变 ⇒ 确定性
        assertEquals(ops, GroupConvPlan.plan(desired.reversed(), existing.reversed()))
    }

    // ------------------------------------------------------------ SQL 生成

    @Test
    fun sql_ownWhere_exact_and_no_like_wildcard() {
        assertEquals("substr(username,1,6)='wxgrp_'", GroupConvPlan.ownWhere())
        assertFalse("不该用 LIKE（_ 是单字符通配）", GroupConvPlan.ownWhere().lowercase().contains("like"))
    }

    @Test
    fun sql_insertConv_is_single_minimal_insert() {
        val a = GroupConvPlan.Desired(3, "组A", 1794406302939L, 0)
        val sql = GroupConvPlan.insertConvSql(a)
        assertTrue(sql, sql.startsWith("insert into rconversation"))
        assertFalse("不该是 INSERT OR REPLACE", sql.lowercase().contains("or replace"))
        assertHas(
            sql,
            "username", "flag", "conversationTime", "msgType", "status", "chatmode", "isSend",
            "wxgrp_0000000000000003",
            "4611687812833690843",   // 真机 flag 逐位值（变异 PIN_BASE 会红）
            "1794406302939",
            "1,3,1,1",
        )
        // content / digest 保持空：不写这两列（竞品无灰色摘要）
        assertFalse("不该写 content", sql.contains("content"))
        assertFalse("不该写 digest", sql.contains("digest"))
        assertSafeSql(sql)
    }

    @Test
    fun sql_insertContact_writes_type_showHead_nickname_with_escaped_quote() {
        val a = GroupConvPlan.Desired(5, "O'Brien 组", 0, 0)
        val sql = GroupConvPlan.insertContactSql(a)
        assertTrue(sql, sql.startsWith("insert into rcontact"))
        assertHas(sql, "username", "type", "showHead", "nickname",
            "wxgrp_0000000000000005", ",0,87,", "'O''Brien 组")
        assertAllQuotesPaired(GroupConvPlan.quote(a.name))
        assertSafeSql(sql)
    }

    @Test
    fun sql_repair_and_rename_are_scoped() {
        val a = GroupConvPlan.Desired(6, "N6", 555, 0)
        val repair = GroupConvPlan.repairSql(a)
        assertTrue(repair, repair.startsWith("update rconversation"))
        assertHas(repair, "set flag=", "conversationTime=", "${GroupConvPlan.flagOf(555)}",
            "555", GroupConvPlan.ownWhere(), "wxgrp_0000000000000006")
        assertSafeSql(repair)

        val rename = GroupConvPlan.renameSql(a)
        assertTrue(rename, rename.startsWith("update rcontact"))
        assertHas(rename, "set nickname=", "'N6'", GroupConvPlan.ownWhere(),
            "wxgrp_0000000000000006")
        assertSafeSql(rename)
    }

    @Test
    fun sql_delete_is_scoped_to_own_namespace() {
        val u = GroupConvPlan.usernameOf(4)
        for (sql in listOf(GroupConvPlan.deleteConvSql(u), GroupConvPlan.deleteContactSql(u))) {
            assertTrue(sql, sql.startsWith("delete from r"))
            assertTrue("必须有 where", sql.contains(" where "))
            assertHas(sql, GroupConvPlan.ownWhere(), "substr(username,1,6)='wxgrp_'", "username='$u'")
            assertSafeSql(sql)
        }
        // 即便传入非本方 / 带注入的 username，SQL 仍恒带 ownWhere 双保险 ⇒ 删不到 wxgrp_ 之外
        val evil = "wx__c_evil' or '1'='1"
        val evilSql = GroupConvPlan.deleteConvSql(evil)
        assertHas(evilSql, "substr(username,1,6)='wxgrp_'", "''")   // 单引号被转义
        assertAllQuotesPaired(GroupConvPlan.quote(evil))
    }

    // ---------------------------------------------------------------- 变异验证（mutation check）
    //
    // 本 lane 不跑 gradle（主 agent 统一编译 / 跑单测），以下 9 组变异按断言逐条静态核验：
    // 每组都是「非等价变异」—— 改完生产代码后明确至少有一条断言从绿变红；
    // 凡 0 条红的等价变异（只改空白 / 注释 / 与断言同源的表达式）一律不列入。
    //
    // M1  PIN_BASE 4611686018427387904L → 1L
    //     ⇒ flagOf_exact_pin_bit（硬编码 `1L shl 62` / `4611687812833690843L`）、
    //       sql_insertConv_is_single_minimal_insert（硬编码真机 flag `4611687812833690843`）变红。
    // M2  plan 里 `e.flag != flagOf(..) || e.conversationTime != ..` 的 `||` → `&&`
    //     ⇒ plan_flag_time_update_wins_over_rename 的「仅 flag 漂移」「仅 time 漂移」两个子断言变红
    //       （&& 要求两列同时不符，单边漂移被漏判成 no-op）。
    // M3  plan 的 `return deletes + updates + inserts` → `inserts + deletes + updates`
    //     ⇒ plan_output_order_is_deterministic 的分组顺序断言（ops[0] is Delete ...）变红。
    // M4  plan 的 when 把 `rename` 分支挪到 `flag/time` 之前
    //     ⇒ plan_flag_time_update_wins_over_rename 的 `both` 子断言（期望 reason="flag/time"）变红。
    // M5  quote 删掉 `.replace("'", "''")`
    //     ⇒ quote_escapes_single_quote_injection、sql_insertContact_...（`'O''Brien 组`）变红，
    //       且 assertAllQuotesPaired 直接失败。
    // M6  ownWhere() 的 `substr(username,1,6)` 里 6 → 5
    //     ⇒ sql_ownWhere_exact_and_no_like_wildcard（精确串断言）、
    //       sql_repair_and_rename_are_scoped / sql_delete_is_scoped_to_own_namespace
    //       （断言含 `substr(username,1,6)='wxgrp_'`）变红。
    // M7  deleteConvSql 去掉 `ownWhere() and`，只留 `username=`
    //     ⇒ sql_delete_is_scoped_to_own_namespace 的 `assertHas(sql, ownWhere(), ...)` 变红
    //       （这正是「绝不能删到非 wxgrp_ 行」的护栏）。
    // M8  GroupConvPlan.effectiveTimeMs 的 `memberConversationTimes.maxOrNull() ?: fallbackMs`
    //     把 `?: fallbackMs` 改回 `?: 0L`（即 R68 空组 fallback 回退到旧行为）
    //     ⇒ effectiveTimeMs_emptyGroup_fallsBackToCreatedAt 的「空组 == createdAt」两条断言变红；
    //       effectiveTimeMs_emptyGroup_flagLandsInTopPinRegion 的
    //       `flag > PIN_BASE` / `flag > flagOf(1)` / `flag > flagOf(0)` 变红（time=0 掉回折叠区）。
    // M9  flagOf 去掉 PIN_BASE（改成 `return conversationTimeMs`，即不置顶）
    //     ⇒ flagOf_exact_pin_bit（硬编码 1L shl 62 / 真机 flag）、
    //       sql_insertConv_is_single_minimal_insert（硬编码 4611687812833690843）、
    //       effectiveTimeMs_emptyGroup_flagLandsInTopPinRegion 的 `flag > PIN_BASE` 变红。
    //
    // 说明：M1 / M6 / M9 之所以能杀，是因为对应用例刻意硬编码了真机数值 / 精确 SQL 串 /
    // 与 PIN_BASE 的大小关系，而不是全靠生产代码里的同源表达式算期望值 —— 否则就是 0 条红的
    // 等价变异，已排除。M8 靠把 fallback 规则抽成纯函数 effectiveTimeMs 才成为可测的非等价变异。
}
