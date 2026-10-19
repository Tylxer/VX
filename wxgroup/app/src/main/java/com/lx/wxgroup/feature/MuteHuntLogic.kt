package com.lx.wxgroup.feature

/**
 * 「群消息免打扰」开关的存储锚点取证 —— **纯判据**（JVM 可单测，不碰微信与 Android API）。
 *
 * ## 背景（为什么要有这个文件）
 *
 * 现有「新加群自动免打扰」（[AutoDndGroup]）是**降级实现**：只把该群的通知改写到自建静音
 * 通道（不响铃不震动），**并没有打开微信自己的「消息免打扰」开关**，所以用户在微信界面上
 * 看不到任何变化。根因写在 `AutoDndGroup.kt:40-41`：写微信库 / 调微信设置接口**没有锚点**。
 *
 * ## 定位手段：开关对照法（不是猜字段名）
 *
 * 1. `mute1` dump 目标群在**所有候选表**里的整行 → 快照 A
 * 2. 用户在微信里手动打开这个群的「消息免打扰」
 * 3. `mute2` 再 dump 一次 → 快照 B，与 A 逐字段 diff
 * 4. 输出「哪个库 / 哪张表 / 哪个列 / 从什么值变成什么值」= 硬锚点
 *
 * 本文件放这条链路上的纯函数：候选表筛查、只读 SQL 构造、快照差分。
 * 反射读库在 `probe/LabelHuntProbe.kt`（复用已有探针，不另造一套）。
 *
 * ## ⚠️ 明确不是什么
 *
 * - **本文件里没有「锚点」**：下面所有表名/列名关键词都只是**筛查用的启发式提示词**
 *   （决定「dump 哪些表」），**没有一个来自真机实证**。真正的锚点只能来自
 *   `mute1 → 手动开关 → mute2` 的 diff 结果；diff 出来之前它们一律不构成结论。
 * - **本文件没有任何写操作判据**：本轮只取证，实现要等 diff 出锚点之后另行评估。
 */
object MuteHuntLogic {

    // ---------------------------------------------------------------- 筛查提示词

    /**
     * 表名**强提示**：命中基本就该进候选表清单。
     *
     * 全部是「看名字猜作用」的启发式，**没有一个来自实证**。故意放得宽：
     * 宁可多 dump 几张表，也不要因为关键词没押中而得出「微信库里没有这张表」的假结论。
     */
    val STRONG_TABLE_HINTS = listOf(
        "chatroom", "room", "mute", "silence", "silent", "quiet",
        "disturb", "notify", "notice", "switch", "dnd", "interrupt", "shield",
    )

    /**
     * 表名**弱提示**：单独命中不足以说明这表存的是群设置，但值得看一眼行数。
     *
     * 弱提示的存在是为了让 `mutetables` 的输出不要只围着「名字像」的那几张表转 ——
     * 微信完全可能把开关塞在一张叫 `Conversation`-ish 的表里。
     */
    val WEAK_TABLE_HINTS = listOf(
        "contact", "conversation", "session", "setting", "config", "option",
        "pref", "flag", "status", "group", "remark", "extinfo", "extra",
    )

    /**
     * 列名提示：对**全库所有表**做列级筛查时用。
     *
     * 这是比表名筛查更可靠的一路 —— 微信的表名混淆得比列名更随意，而「能表示开关」的
     * 列名来来回回就这几个词根。
     */
    val COLUMN_HINTS = listOf(
        "mute", "silence", "silent", "quiet", "disturb", "notify", "notice",
        "switch", "dnd", "interrupt", "shield", "ring", "vibrat", "exempt",
    )

    /** 一条 where 里最多拼多少个列（防 SQLite 表达式深度上限，也防 SQL 长到看不清） */
    const val MAX_MATCH_COLS = 120

    /** 单个格子（列值）最多保留多少字符；超了保留「头+长度+尾」，仍能看出变化位置 */
    const val MAX_KEEP = 4000

    /** 一张表最多 dump 多少行 */
    const val MAX_ROWS_PER_TABLE = 20

    // ---------------------------------------------------------------- 筛查

    /**
     * 这张表进不进候选清单。
     *
     * @return 命中原因（`strong:<关键字>` / `weak:<关键字>`），不进候选返回 null
     */
    fun tableHit(name: String): String? {
        val l = name.lowercase()
        for (h in STRONG_TABLE_HINTS) if (l.contains(h)) return "strong:$h"
        for (h in WEAK_TABLE_HINTS) if (l.contains(h)) return "weak:$h"
        return null
    }

    fun isCandidate(name: String): Boolean = tableHit(name) != null

    /** 这组列里有哪些命中「开关类」列名词根 */
    fun columnHits(cols: Collection<String>): List<String> =
        cols.filter { c ->
            val l = c.lowercase()
            COLUMN_HINTS.any { l.contains(it) }
        }

    fun parseTables(raw: String?): List<String> =
        raw.orEmpty().split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    // ---------------------------------------------------------------- SQL 构造

    fun quoteIdent(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    fun quoteLit(s: String): String = "'" + s.replace("'", "''") + "'"

    /**
     * LIKE 的通配符转义：`%` `_` 与转义符本身都要挡掉，
     * 否则 wxid 里一旦出现这些字符，模糊兜底就变成了另一条查询。
     */
    fun likePattern(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            if (c == '\\' || c == '%' || c == '_') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    /**
     * 拼「这张表里跟目标群有关的行」的**只读** select。
     *
     * 三个刻意的取舍：
     * - **不用任何预设字段名**：列清单来自 `pragma table_info`（实测），把**所有列**都拼进
     *   `where` 做 or 匹配。这样即使开关存在某个我们连名字都猜不到的列里也能被查到。
     * - **`cast(... as text)`**：微信大量把设置塞进 BLOB（序列化 proto / 自定义 buffer），
     *   而 SQLite 的字符串函数作用在 BLOB 上不会转成文本，`lower(blob)=...` 恒假。
     *   加了 cast 才能让 BLOB 里的 wxid 也命中。
     * - **`rowid` 可选**：`WITHOUT ROWID` 的表取 rowid 会直接报错，调用方需要能关掉它
     *   （关掉后由行序号兜底定位）。
     *
     * @param fuzzy true 用 `like '%x%'` 兜底（列值是序列化串、wxid 被包在里面时用）
     * @param includeRowid 是否多查一个 `rowid as __rid` 作为行的稳定标识
     * @return null 表示没给列（无法构造）
     */
    fun selectSql(
        table: String,
        cols: List<String>,
        target: String,
        fuzzy: Boolean,
        includeRowid: Boolean,
        limit: Int,
    ): String? {
        if (cols.isEmpty()) return null
        val use = cols.take(MAX_MATCH_COLS)
        val cond = if (fuzzy) {
            val v = quoteLit("%" + likePattern(target.lowercase()) + "%")
            use.joinToString(" or ") { "lower(cast(${quoteIdent(it)} as text)) like $v escape '\\'" }
        } else {
            val v = quoteLit(target.lowercase())
            use.joinToString(" or ") {
                "lower(trim(cast(coalesce(${quoteIdent(it)}, '') as text))) = $v"
            }
        }
        val proj = if (includeRowid) "rowid as \"__rid\", *" else "*"
        val lim = limit.coerceIn(1, 50)
        return "select $proj from ${quoteIdent(table)} where ($cond) limit $lim"
    }

    /**
     * 长值压成「头 + 长度 + 尾」。
     *
     * 不是单纯截断：logcat 单行上限 ~4096 字符，而 diff 的关键恰恰常在长 BLOB/串的中段，
     * 留尾巴才能看出「变了、大概在哪个位置」。
     */
    fun compact(v: String, max: Int = MAX_KEEP): String {
        if (v.length <= max) return v
        return v.take(1500) + "|<...len=" + v.length + "...>|" + v.takeLast(500)
    }

    // ---------------------------------------------------------------- 快照 / 差分

    /** 一个格子＝列名 + 已压扁的字符串视图（值永远是 `getString`/hex 的产物） */
    data class Cell(val col: String, val view: String)

    /**
     * 一行。
     *
     * @param locator 行标识：`__rid`（有 rowid 时的真 rowid）或 `#<序号>`（无 rowid 兜底）。
     *   两次快照之间微信不会重排 rowid，所以它是可靠的行对齐依据。
     */
    data class Row(val locator: String, val cells: List<Cell>)

    /** 某库某表里 dump 出来的行（没查到行的表不进快照） */
    data class TableSnap(val dbPath: String, val table: String, val rows: List<Row>)

    /** 一次完整快照 */
    data class Snap(val target: String, val atMs: Long, val tables: List<TableSnap>) {
        fun rowCount(): Int = tables.sumOf { it.rows.size }
    }

    fun tableKey(t: TableSnap): String = t.dbPath + "::" + t.table

    enum class Kind {
        /** 同行同列的值变了 —— **这就是要找的东西** */
        VALUE,

        ROW_ADDED, ROW_REMOVED, COL_ADDED, COL_REMOVED, TABLE_ADDED, TABLE_REMOVED,
    }

    data class Change(
        val dbPath: String,
        val table: String,
        val locator: String,
        val kind: Kind,
        val col: String?,
        val before: String?,
        val after: String?,
    )

    data class Diff(
        val changes: List<Change>,
        val tablesCompared: Int,
        val rowsCompared: Int,
        val cellsCompared: Int,
        val tablesAdded: Int,
        val tablesRemoved: Int,
    ) {
        fun valueChanges(): List<Change> = changes.filter { it.kind == Kind.VALUE }
    }

    /**
     * A（开关前）与 B（开关后）逐字段差分。
     *
     * 两侧都按 `(dbPath, table)` → `locator` → 列名的层级对齐，**行的先后顺序不影响结果**
     * （微信完全可能在开关时改写别的表导致返回顺序变化，按序号比就会满屏假变更）。
     *
     * @return 差分结果；`cellsCompared` 让你能确认「确实把所有列都比过了」而不是只比了几个
     */
    fun diff(before: Snap, after: Snap): Diff {
        val a = LinkedHashMap<String, TableSnap>()
        for (t in before.tables) a[tableKey(t)] = t
        val b = LinkedHashMap<String, TableSnap>()
        for (t in after.tables) b[tableKey(t)] = t

        val allKeys = LinkedHashSet<String>().apply { addAll(a.keys); addAll(b.keys) }
        val changes = ArrayList<Change>()
        var rows = 0
        var cells = 0
        var added = 0
        var removed = 0

        for (k in allKeys) {
            val t1 = a[k]
            val t2 = b[k]
            if (t1 == null) {
                added++
                changes += Change(t2!!.dbPath, t2.table, "-", Kind.TABLE_ADDED, null, null, null)
                continue
            }
            if (t2 == null) {
                removed++
                changes += Change(t1.dbPath, t1.table, "-", Kind.TABLE_REMOVED, null, null, null)
                continue
            }
            val m1 = LinkedHashMap<String, Row>().apply { t1.rows.forEach { put(it.locator, it) } }
            val m2 = LinkedHashMap<String, Row>().apply { t2.rows.forEach { put(it.locator, it) } }
            val locs = LinkedHashSet<String>().apply { addAll(m1.keys); addAll(m2.keys) }
            for (loc in locs) {
                val r1 = m1[loc]
                val r2 = m2[loc]
                if (r1 == null) {
                    changes += Change(t1.dbPath, t1.table, loc, Kind.ROW_ADDED, null, null, null)
                    continue
                }
                if (r2 == null) {
                    changes += Change(t1.dbPath, t1.table, loc, Kind.ROW_REMOVED, null, null, null)
                    continue
                }
                rows++
                val c1 = LinkedHashMap<String, String>().apply { r1.cells.forEach { put(it.col, it.view) } }
                val c2 = LinkedHashMap<String, String>().apply { r2.cells.forEach { put(it.col, it.view) } }
                val names = LinkedHashSet<String>().apply { addAll(c1.keys); addAll(c2.keys) }
                for (col in names) {
                    val v1 = c1[col]
                    val v2 = c2[col]
                    cells++
                    when {
                        v1 == null ->
                            changes += Change(t1.dbPath, t1.table, loc, Kind.COL_ADDED, col, null, v2)
                        v2 == null ->
                            changes += Change(t1.dbPath, t1.table, loc, Kind.COL_REMOVED, col, v1, null)
                        v1 != v2 ->
                            changes += Change(t1.dbPath, t1.table, loc, Kind.VALUE, col, v1, v2)
                    }
                }
            }
        }
        return Diff(changes, allKeys.size, rows, cells, added, removed)
    }
}
