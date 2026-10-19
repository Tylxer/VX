package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MuteHuntLogic] 的判据回归测试。
 *
 * 锁的是「开关对照法」这条取证链路的正确性：筛查、只读 SQL 构造、快照差分。
 * ⚠️ **这里没有任何一个用例声称某个字段就是微信的免打扰开关** ——
 * 所有表名/列名关键词都只是 dump 范围的启发式，锚点只能来自真机 `mute1 → 手动开关 → mute2` 的 diff。
 */
class MuteHuntLogicTest {

    private val TARGET = "49543624181@chatroom"

    private fun row(
        locator: String,
        vararg cells: Pair<String, String>,
    ) = MuteHuntLogic.Row(locator, cells.map { MuteHuntLogic.Cell(it.first, it.second) })

    private fun table(
        table: String,
        vararg rows: MuteHuntLogic.Row,
        db: String = "/data/user/0/com.tencent.mm/MicroMsg/md5/EnMicroMsg.db",
    ) = MuteHuntLogic.TableSnap(db, table, rows.toList())

    private fun snapOf(vararg tables: MuteHuntLogic.TableSnap) =
        MuteHuntLogic.Snap(TARGET, 0L, tables.toList())

    // ---------------------------------------------------------------- 表名筛查

    @Test
    fun strongHintWinsOverWeak() {
        assertEquals("strong:chatroom", MuteHuntLogic.tableHit("chatroom"))
        // 多个关键词同时命中时取列表里靠前的那个（只是输出里的标注，不影响是否进候选）
        assertEquals("strong:chatroom", MuteHuntLogic.tableHit("ChatRoomMuteSetting"))
        assertTrue(MuteHuntLogic.isCandidate("rcontact"))
        // 弱提示也进候选（只是输出里会标 weak）
        assertEquals("weak:contact", MuteHuntLogic.tableHit("rcontact"))
        assertEquals("weak:contact", MuteHuntLogic.tableHit("ContactStatus"))
        assertEquals("weak:setting", MuteHuntLogic.tableHit("UserSetting"))
    }

    @Test
    fun noHitIsNotACandidate() {
        assertNull(MuteHuntLogic.tableHit("message"))
        assertNull(MuteHuntLogic.tableHit(""))
        assertFalse(MuteHuntLogic.isCandidate("SnsInfo"))
    }

    @Test
    fun screenIsCaseInsensitive() {
        assertEquals("strong:chatroom", MuteHuntLogic.tableHit("CHATROOM_TOOL"))
        assertEquals("weak:contact", MuteHuntLogic.tableHit("Contact"))
    }

    // ---------------------------------------------------------------- 列名筛查

    @Test
    fun columnHitsPickSwitchishNames() {
        val cols = listOf(
            "username", "nickname", "contactLabelIds",
            "muteFlag", "isSilent", "notifyStatus", "switchStatus", "lvbuff",
        )
        assertEquals(listOf("muteFlag", "isSilent", "notifyStatus", "switchStatus"), MuteHuntLogic.columnHits(cols))
    }

    @Test
    fun columnHitsMayBeEmpty() {
        assertEquals(emptyList<String>(), MuteHuntLogic.columnHits(listOf("username", "nickname")))
        assertEquals(emptyList<String>(), MuteHuntLogic.columnHits(emptyList()))
    }

    @Test
    fun parseTablesAcceptsSeparators() {
        assertEquals(listOf("a", "b"), MuteHuntLogic.parseTables("a,b"))
        assertEquals(listOf("a", "b"), MuteHuntLogic.parseTables(" a; b "))
        assertEquals(emptyList<String>(), MuteHuntLogic.parseTables(null))
        assertEquals(listOf("rcontact"), MuteHuntLogic.parseTables("rcontact"))
    }

    // ---------------------------------------------------------------- SQL 构造

    @Test
    fun exactSelectUsesAllColumnsAndReadsOnly() {
        val sql = MuteHuntLogic.selectSql(
            "chatroom", listOf("chatroomname", "memberCount"), TARGET,
            fuzzy = false, includeRowid = true, limit = 10,
        )!!
        assertTrue(sql.startsWith("select rowid as \"__rid\", * from \"chatroom\" where ("))
        assertTrue(sql.contains("lower(trim(cast(coalesce(\"chatroomname\", '') as text))) = '49543624181@chatroom'"))
        assertTrue(sql.contains(" or "))
        assertTrue(sql.endsWith(" limit 10"))
    }

    /** `cast(... as text)` 是刻意的：SQLite 的字符串函数作用在 BLOB 上不会转文本，不加就永远匹配不到 */
    @Test
    fun exactSelectCastsToTextSoBlobsCanMatch() {
        val sql = MuteHuntLogic.selectSql(
            "t", listOf("lvbuff"), TARGET, fuzzy = false, includeRowid = false, limit = 3,
        )!!
        assertTrue(sql.contains("cast(coalesce(\"lvbuff\", '') as text)"))
        assertFalse(sql.startsWith("select rowid"))
    }

    @Test
    fun fuzzySelectEscapesWildcards() {
        val sql = MuteHuntLogic.selectSql(
            "t", listOf("c"), "a%b_c\\d", fuzzy = true, includeRowid = false, limit = 5,
        )!!
        assertTrue(sql.contains("like '%a\\%b\\_c\\\\d%' escape '\\'"))
    }

    @Test
    fun selectSqlRejectsNoColumnsAndClampsLimit() {
        assertNull(MuteHuntLogic.selectSql("t", emptyList(), TARGET, false, true, 10))
        assertTrue(MuteHuntLogic.selectSql("t", listOf("c"), TARGET, false, false, limit = 0)!!.endsWith(" limit 1"))
        assertTrue(MuteHuntLogic.selectSql("t", listOf("c"), TARGET, false, false, limit = 999)!!.endsWith(" limit 50"))
    }

    @Test
    fun identifiersAndLiteralsAreQuoted() {
        // 表名/列名来自 pragma，理论上不会带引号，但拼 SQL 一律转义，避免表里有怪名字把查询打歪
        assertEquals("\"a\"\"b\"", MuteHuntLogic.quoteIdent("a\"b"))
        assertEquals("'a''b'", MuteHuntLogic.quoteLit("a'b"))
    }

    // ---------------------------------------------------------------- 值压扁

    @Test
    fun compactKeepsShortValuesUntouched() {
        assertEquals("abc", MuteHuntLogic.compact("abc"))
        assertEquals("", MuteHuntLogic.compact(""))
    }

    @Test
    fun compactLongValueKeepsHeadLengthAndTail() {
        val long = "H".repeat(3000) + "M".repeat(3000) + "T".repeat(20)
        val out = MuteHuntLogic.compact(long, max = 1000)
        assertTrue(out.length < long.length)
        assertTrue(out.contains("len=${long.length}"))
        assertTrue(out.startsWith("H"))
        assertTrue(out.endsWith("T".repeat(20)))
    }

    // ---------------------------------------------------------------- 差分

    @Test
    fun valueChangeIsTheAnchorWeWant() {
        val a = snapOf(
            table(
                "chatroom",
                row(
                    "rid=7",
                    "chatroomname" to TARGET,
                    "memberCount" to "3",
                    "muteFlag" to "0",
                ),
            )
        )
        val b = snapOf(
            table(
                "chatroom",
                row(
                    "rid=7",
                    "chatroomname" to TARGET,
                    "memberCount" to "3",
                    "muteFlag" to "1",
                ),
            )
        )
        val d = MuteHuntLogic.diff(a, b)
        assertEquals(1, d.changes.size)
        assertEquals(1, d.valueChanges().size)
        val c = d.valueChanges().first()
        assertEquals(MuteHuntLogic.Kind.VALUE, c.kind)
        assertEquals("chatroom", c.table)
        assertEquals("muteFlag", c.col)
        assertEquals("0", c.before)
        assertEquals("1", c.after)
        assertEquals("rid=7", c.locator)
        // 「确实把所有列都比过了」而不是只比了几个 —— 没有这个数就不敢相信零变化
        assertEquals(1, d.tablesCompared)
        assertEquals(1, d.rowsCompared)
        assertEquals(3, d.cellsCompared)
    }

    @Test
    fun identicalSnapshotsGiveZeroChanges() {
        val t = table("rcontact", row("rid=1", "username" to TARGET, "alias" to "x"))
        val d = MuteHuntLogic.diff(snapOf(t), snapOf(t))
        assertTrue(d.changes.isEmpty())
        assertEquals(0, d.valueChanges().size)
        assertEquals(1, d.rowsCompared)
        assertEquals(2, d.cellsCompared)
    }

    @Test
    fun rowOrderDoesNotMatter() {
        val r1 = row("rid=1", "c" to "a")
        val r2 = row("rid=2", "c" to "b")
        val d = MuteHuntLogic.diff(
            snapOf(table("t", r1, r2)),
            snapOf(table("t", r2, r1)),
        )
        // 按 locator 对齐，而不是按返回顺序 —— 微信开关设置时可能顺带改写别的表导致顺序变化
        assertTrue(d.changes.isEmpty())
        assertEquals(2, d.rowsCompared)
    }

    @Test
    fun rowAddAndRemoveAreReported() {
        val d = MuteHuntLogic.diff(
            snapOf(table("t", row("rid=1", "c" to "a"))),
            snapOf(table("t", row("rid=2", "c" to "b"))),
        )
        assertEquals(2, d.changes.size)
        assertEquals(setOf(MuteHuntLogic.Kind.ROW_ADDED, MuteHuntLogic.Kind.ROW_REMOVED), d.changes.map { it.kind }.toSet())
        assertEquals(0, d.rowsCompared) // 没有能对齐的行
    }

    @Test
    fun tableAddAndRemoveAreReported() {
        val d = MuteHuntLogic.diff(
            snapOf(table("only_before", row("rid=1", "c" to "a"))),
            snapOf(table("only_after", row("rid=1", "c" to "a"))),
        )
        assertEquals(1, d.tablesAdded)
        assertEquals(1, d.tablesRemoved)
        assertEquals(2, d.tablesCompared)
        assertTrue(d.changes.all { it.kind == MuteHuntLogic.Kind.TABLE_ADDED || it.kind == MuteHuntLogic.Kind.TABLE_REMOVED })
    }

    @Test
    fun columnAddAndRemoveAreReported() {
        val d = MuteHuntLogic.diff(
            snapOf(table("t", row("rid=1", "a" to "1"))),
            snapOf(table("t", row("rid=1", "a" to "1", "b" to "2"))),
        )
        assertEquals(1, d.changes.size)
        assertEquals(MuteHuntLogic.Kind.COL_ADDED, d.changes.first().kind)
        assertEquals("b", d.changes.first().col)
        assertEquals("2", d.changes.first().after)
        assertNull(d.changes.first().before)
        assertEquals(2, d.cellsCompared)
    }

    @Test
    fun sameTableInTwoDbsAreComparedSeparately() {
        val other = "/data/user/0/com.tencent.mm/MicroMsg/md5/Other.db"
        val d = MuteHuntLogic.diff(
            snapOf(table("t", row("rid=1", "c" to "a"), db = other)),
            snapOf(table("t", row("rid=1", "c" to "b"))),
        )
        // 同名表在不同库里必须各算各的，否则「哪个库」这个关键信息就丢了
        assertEquals(1, d.tablesAdded)
        assertEquals(1, d.tablesRemoved)
        assertEquals(2, d.tablesCompared)
        assertTrue(d.valueChanges().isEmpty())
    }

    @Test
    fun multipleTablesAreDiffedAtOnce() {
        val a = snapOf(
            table("rcontact", row("rid=1", "username" to TARGET, "contactExtra" to "old")),
            table("chatroom", row("rid=2", "chatroomname" to TARGET, "muteFlag" to "0")),
        )
        val b = snapOf(
            table("rcontact", row("rid=1", "username" to TARGET, "contactExtra" to "new")),
            table("chatroom", row("rid=2", "chatroomname" to TARGET, "muteFlag" to "1")),
        )
        val d = MuteHuntLogic.diff(a, b)
        assertEquals(2, d.tablesCompared)
        assertEquals(2, d.valueChanges().size)
        assertEquals(
            setOf("contactExtra" to "rcontact", "muteFlag" to "chatroom"),
            d.valueChanges().map { it.col!! to it.table }.toSet(),
        )
    }

    @Test
    fun snapCountsRowsAcrossTables() {
        val s = snapOf(
            table("a", row("rid=1", "c" to "1"), row("rid=2", "c" to "2")),
            table("b", row("rid=1", "c" to "1")),
        )
        assertEquals(3, s.rowCount())
        assertEquals(TARGET, s.target)
    }

    @Test
    fun emptyDiffWhenNoTables() {
        val d = MuteHuntLogic.diff(snapOf(), snapOf())
        assertEquals(0, d.tablesCompared)
        assertTrue(d.changes.isEmpty())
        assertTrue(d.valueChanges().isEmpty())
    }
}
