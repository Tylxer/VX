package com.lx.wxgroup.feature

/**
 * 朋友圈删除语句的**纯字符串识别**（不碰 Xposed，能在 JVM 单测里跑）。
 *
 * 真机抓到的两种形态：
 * ```
 * delete(table="SnsInfo", whereClause="snsId=?", whereArgs=[-3447438253133393222])   ← 主线程
 * execSQL("delete from SnsComment where snsID = -3442401033659805004 and ...")       ← worker
 * ```
 *
 * ## ⚠️ 两条硬约束（都是踩过的坑，别放宽）
 *
 * 1. **必须 `snsId=` 而不是「出现过 snsId」**。
 *    微信会按时间/按 id 范围清理过期动态（`createTime < ?`、`snsId < ?`），
 *    只看单词就拦会让这些清理全部失效 —— SnsMicroMsg.db（真机 766MB）只涨不删。
 *
 * 2. **参数占位符要按序号取，不能取第一个数字**。
 *    `where localFlag=? and snsId=?` 里第一个参数是 localFlag，
 *    取错照样拦下来、还会把归档 id 记错。所以数一下 `snsId=` 前面有几个 `?`。
 */
object SnsSql {

    private val FROM_SNSINFO = Regex("""from\s+SnsInfo\b""", RegexOption.IGNORE_CASE)

    /** `snsId = 123` 或 `snsId = ?`（参数化时捕获到 `?`，再回退去 bindArgs 里取） */
    private val SNSID_EQ = Regex("""snsId\s*=\s*(-?\d+|\?)""", RegexOption.IGNORE_CASE)
    private val SNSID_ASSIGN = Regex("""snsId\s*=""", RegexOption.IGNORE_CASE)

    /**
     * `execSQL(sql)` / `execSQL(sql, bindArgs)` 形态：是不是在删 SnsInfo，是的话返回 snsId。
     *
     * @return snsId；null = 不是删 SnsInfo，放行
     */
    fun matchDeleteSql(sql: String?, bindArgs: Array<*>? = null): Long? {
        if (sql == null) return null
        val s = sql.trimStart()
        if (!s.startsWith("delete", ignoreCase = true)) return null
        if (!FROM_SNSINFO.containsMatchIn(s)) return null
        val m = SNSID_EQ.find(s) ?: return null
        // 字面量优先；`snsId=?` 这种参数化的再去 bindArgs 里按序号取
        m.groupValues.getOrNull(1)?.toLongOrNull()?.let { return nonZero(it) }
        return nonZero(argAt(s, m.range.first, bindArgs))
    }

    /**
     * `delete(table, whereClause, whereArgs)` 形态。
     *
     * @return snsId；null = 不是删单条 SnsInfo，放行
     */
    fun matchDeleteCall(table: String?, whereClause: String?, whereArgs: Array<*>?): Long? {
        if (table == null || !table.equals("SnsInfo", ignoreCase = true)) return null
        if (whereClause == null) return null
        // ⚠️ 必须是「snsId = …」；`snsId < ?` / `createTime < ?` 这类清理一律不拦
        val m = SNSID_ASSIGN.find(whereClause) ?: return null
        SNSID_EQ.find(whereClause)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?.let { return nonZero(it) }
        return nonZero(argAt(whereClause, m.range.first, whereArgs))
    }

    /** snsId 恒为非零 64 位值，0 说明没解析出来 —— 按「不命中」处理，放行 */
    private fun nonZero(v: Long?): Long? = if (v == null || v == 0L) null else v

    // ------------------------------------------------------------------ 反插 SQL（从归档恢复用）

    /** `?,?,?` —— n 个占位符 */
    fun placeholders(n: Int): String = if (n <= 0) "" else Array(n) { "?" }.joinToString(",")

    /**
     * 反插一行的 INSERT（全占位符，值走 bindArgs，**不拼字符串**）。
     *
     * @param cols 要写的列名，顺序必须跟 bindArgs 一致（用
     *             `SnsRestoreLogic.columns()` / `SnsRestoreLogic.insertPuts` 拿，别手写）
     * @param conflict [SnsRestoreLogic.CONFLICT_IGNORE]（=4）→ `OR IGNORE`；
     *                 [SnsRestoreLogic.CONFLICT_REPLACE]（=5）→ `OR REPLACE`。
     *                 写微信的 `SnsInfo` **只准传 4**，详见 [SnsRestoreLogic.conflictFor]。
     *
     * ⚠️ `insertWithOnConflict` 选不上时才走这条 `execSQL` 退路；两条路写的是同一批列、同一个冲突语义。
     */
    fun insertSql(table: String, cols: List<String>, conflict: Int): String {
        if (cols.isEmpty()) return ""
        val keyword = when (conflict) {
            SnsRestoreLogic.CONFLICT_IGNORE -> "INSERT OR IGNORE INTO"
            SnsRestoreLogic.CONFLICT_REPLACE -> "INSERT OR REPLACE INTO"
            else -> "INSERT INTO"
        }
        return "$keyword $table (${cols.joinToString(",")}) VALUES (${placeholders(cols.size)})"
    }

    /**
     * 参数化占位符取值：数一下 [at] 之前有几个 `?`，那个数就是它在 args 里的下标。
     *
     * 例：`localFlag=? and snsId=?` —— `snsId` 前面有 1 个 `?`，所以取 args[1]。
     */
    private fun argAt(s: String, at: Int, args: Array<*>?): Long? {
        if (args == null) return null
        var idx = 0
        var i = 0
        var inStr = false
        while (i < at && i < s.length) {
            val ch = s[i]
            when {
                // ⚠️ 字符串字面量里的 `?` 不是占位符（`content='?'`），别数进去
                ch == '\'' -> inStr = !inStr
                ch == '?' && !inStr -> idx++
            }
            i++
        }
        if (idx >= args.size) return null
        return when (val v = args[idx]) {
            is Long -> v
            is Int -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }
}
