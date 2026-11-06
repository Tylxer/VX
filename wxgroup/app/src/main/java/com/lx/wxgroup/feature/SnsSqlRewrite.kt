package com.lx.wxgroup.feature

/**
 * R42 方案 B · 微信 `execSQL` 清 bit3 语句的**改写**（纯字符串，不 import 任何 Android / Xposed 类）。
 *
 * ## 背景（真机实测，见 `docs/_r42_bit_paths.md` §8.3）
 * 方案 A（写时拦截 `updateWithOnConflict`）能保住时间线，但位型停在 `10` 之后，
 * 进好友主页时微信会执行这条裸 SQL 把 bit3 削掉：
 * ```
 * UPDATE SnsInfo SET sourceType = sourceType & -9
 *  WHERE SnsInfo.userName="<好友>" AND (sourceType in (8,264,10,...))   -- 64 个值全含 bit3，含 10
 *    AND (snsId != 0) AND (snsId > A) [AND (snsId < B)]                 -- 按 id 区间分页
 * ```
 * ⇒ 主页可见（bit3）丢失。
 *
 * ## 为什么改写「值表达式」而不是「WHERE 恒假」/「跳过执行」
 * 1. `execSQL` 是 `void`，三种做法在 Java 侧都「成功」（不抛异常），**无法靠返回值区分**；
 * 2. 但 SQLite 的 `changes()` 仍然是可观测的：改写值 ⇒ `changes()` = 命中行数（与原语句**完全一致**）；
 *    改 WHERE ⇒ `changes()` = 0；跳过执行 ⇒ `changes()` 保留**上一条语句**的陈旧值。
 *    微信按 `snsId` 区间翻页，一旦哪天真用 `changes()` 推进游标，后两者会把游标卡住（空转/重试风暴）；
 * 3. 改写值只动 SET 里的一个常量，**WHERE 一个字节都不碰** ⇒ 命中集合、`changes()`、后续分页三者全同。
 *
 * ## 改写方式：`& -9` → `& -1`（而不是把整个表达式换成 `sourceType`）
 * SQLite 整数是 64 位补码，`x & -1 == x` 对任意整数成立 ⇒ 语义上就是「赋回原值」。
 * 只替换常量而不是整段表达式，是为了**不动括号结构**：
 * `(sourceType & (-9))` 这类带括号的写法，替换常量后仍然是合法的 `(sourceType & (-1))`，
 * 换成 `sourceType` 则会留下多余右括号、把整条 SQL 变成语法错误（那会让微信直接抛异常）。
 *
 * ## 边界（⛔ 不许放宽）
 * - 只认 **`UPDATE` + 表名 `SnsInfo` + SET 里的 `sourceType = [ ( ] sourceType & [ ( ] -9`**；
 * - WHERE 里的 `sourceType = sourceType & -9`（过滤条件）**不是**清位，绝不能改写 —— 改写它会让
 *   WHERE 恒真、命中集合暴涨（[whereIndex] 就是为这条而存在的）；
 * - `& -3`（清 bit1）**不改写**：真机从未出现过（`sqlClear` 一直为 0），无样本可判副作用，
 *   只保留识别 + 告警（见 [SnsBitLogic.sqlClear]），不在这里做任何动作；
 * - 非 `SnsInfo` / 非 `UPDATE` / 非清位写（`sourceType | 10`、`sourceType = -9`）一律原样返回。
 */
object SnsSqlRewrite {

    /**
     * no-op 掩码。`x & -1 == x`（64 位补码），所以这个表达式等价于「把 sourceType 赋回原值」。
     */
    const val NOOP_MASK = "-1"

    /** SET 里的清位赋值：只捕获到常量前的片段（含原有空格/括号），常量本身留给替换 */
    private val CLEAR_BIT3_ASSIGN = Regex(
        """(sourceType\s*=\s*\(?\s*sourceType\s*&\s*\(?\s*)-9\b""",
        RegexOption.IGNORE_CASE,
    )

    private val SNSID_GT = Regex("""snsId\s*>\s*(-?\d+)""", RegexOption.IGNORE_CASE)
    private val SNSID_LT = Regex("""snsId\s*<\s*(-?\d+)""", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------ 判定

    /**
     * 这条 SQL 是否该被改写（= 真机 `& -9` 清位形态，且清位发生在 **SET 子句**里）。
     *
     * ⚠️ 与 [SnsBitLogic.sqlClear] 的分工是**故意不同**的，不能合并：
     * - 那边是「形态识别」：只扫描全文，WHERE 里的同名表达式也算，`DELETE`/`SELECT` 也算；
     *   —— 用途是**记账**（`sqlClear9`），宁可多记一条，也不许漏掉一条没见过的模板。
     * - 这里是「能不能动手」：必须 `UPDATE SnsInfo` + 清位在 SET 里；
     *   —— 用途是**改 SQL**，识别错了代价不对等（见类注释里的三条边界）。
     * 所以「识别到但 shouldBlock=false」（如 WHERE 里出现同一表达式）是**正常状态**，不是 bug。
     */
    fun shouldBlock(sql: String?): Boolean {
        if (sql.isNullOrBlank()) return false
        if (!isUpdateOfSnsInfo(sql)) return false
        return CLEAR_BIT3_ASSIGN.containsMatchIn(setPart(sql))
    }

    /**
     * 返回改写后的 SQL；未命中/不该拦时**逐字返回原文**（调用方用 `out == sql` 判断有没有改动，
     * 相等 ⇒ 没改，原样交给微信）。
     *
     * @return `null` 入参返回 `""`（调用方拿到 null 不会走到这里，仅为可空契约收敛）
     */
    fun rewrite(sql: String?): String {
        if (sql == null) return ""
        if (!shouldBlock(sql)) return sql
        val cut = whereIndex(sql)                 // -1 = 没有 WHERE
        val head = if (cut < 0) sql else sql.substring(0, cut)
        val tail = if (cut < 0) "" else sql.substring(cut)
        val out = CLEAR_BIT3_ASSIGN.replace(head) { m -> m.groupValues[1] + NOOP_MASK }
        return out + tail
    }

    /**
     * 这条 SQL 的 **snsId 分页区间**（`"<lo>..<hi>"`；没有上界时是 `"<lo>..*"`）。
     *
     * 用途：判断微信是否会**对同一批行反复重试** —— 同一个 key 的计数 > 1 就是重试证据。
     * 抠不出下界时返回 null（只计数、不参与区间统计）。
     */
    fun rangeKey(sql: String?): String? {
        if (sql.isNullOrBlank()) return null
        val lo = SNSID_GT.find(sql)?.groupValues?.getOrNull(1) ?: return null
        val hi = SNSID_LT.find(sql)?.groupValues?.getOrNull(1) ?: "*"
        return "$lo..$hi"
    }

    // ------------------------------------------------------------------ 内部

    /** SET 子句（`where` 之前的部分）；没有 WHERE 时就是整条语句 */
    private fun setPart(sql: String): String {
        val cut = whereIndex(sql)
        return if (cut < 0) sql else sql.substring(0, cut)
    }

    /** 语句首个关键字必须是 `update`（保守：宁可漏改写，也不许在 SELECT 上动刀） */
    private fun isUpdateOfSnsInfo(sql: String): Boolean {
        val s = sql.trimStart()
        if (!s.startsWith("update", ignoreCase = true)) return false
        val kw = s.takeWhile { !it.isWhitespace() }
        if (!kw.equals("update", ignoreCase = true)) return false
        return s.contains("SnsInfo", ignoreCase = true)
    }

    /**
     * 找**顶层**（不在引号里、不在括号里）的 `where` 起点；没有则 -1。
     *
     * ⚠️ 三条都必须做到，缺一条就会改错行：
     * - 引号：`content='where x'` 里的词不是子句；
     * - 括号：`(a where ...)` 不存在，但子查询 `... (select ... where ...)` 存在；
     * - 词边界：`somewhere` / `wherever` 不是关键字。
     */
    private fun whereIndex(sql: String): Int {
        var i = 0
        var depth = 0
        var quote: Char? = null
        while (i < sql.length) {
            val c = sql[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' || c == '`' -> quote = c
                c == '(' -> depth++
                c == ')' -> if (depth > 0) depth--
                depth == 0 && (c == 'w' || c == 'W') && wordAt(sql, i, "where") -> return i
            }
            i++
        }
        return -1
    }

    private fun wordAt(s: String, at: Int, word: String): Boolean {
        if (at + word.length > s.length) return false
        if (!s.regionMatches(at, word, 0, word.length, ignoreCase = true)) return false
        if (at > 0 && isWordChar(s[at - 1])) return false
        val end = at + word.length
        return end >= s.length || !isWordChar(s[end])
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
}
