package com.lx.wxgroup.feature

/**
 * R49 阶段二 · R-C「复用写时拦截」结构化写事件探针（W1~W5）。
 *
 * ## 要回答的唯一问题
 * 进好友主页时微信发的 `& -9` 清 bit3 raw SQL（WHERE=userName + 64 个含 bit3 的
 * sourceType 白名单 + snsId 区间、**无 createTime 窗口**）是否**行级命中三天外行**。
 * 阶段一已证「写事件存在、before 不空转」，但缺「行级命中」与「界面能否显示」两项观测；
 * 本探针只解决「行级命中」，界面能否显示留给真机人工判读。
 *
 * ## ⛔ 决定性陷阱（为什么不能靠库快照差分）
 * `SnsSqlRewrite.kt:46` 已把 `& -9` 改写为 `& -1`（no-op）：SQL 照常执行、库值不变
 * ⇒ 「进主页前后用 `op=sql` 做库快照差分」**必然假阴性**。必须在 **before 钩子里直接
 * 观测 SQL 原文/参数**（[SnsWriteProbe.captureExecSql] / [SnsWriteProbe.captureUpdate]），
 * 且拿到的必须是**改写前**的原文 —— 接在 `SnsBlockProbe.noteSql` 首行，那里的 `sql`
 * 是 `SnsGuard.onSnsSql` 传进来的原始局部变量（`SnsSqlRewrite` 只改 `param.args[0]`）。
 *
 * ## 只读性 / 独立性（红线）
 * - 探针**不写微信库**、不改 `ContentValues`、不改 SQL、不改 `param.args`、不改返回值；
 *   行级命中判定只在 `op=writeprobe` 导出时用 `SnsGuard.runReadOnlySql`（select 白名单）查。
 * - 开关 `Keys.SNS_WRITE_PROBE` 是**第三个独立开关**，默认 false；与 `antirevoke.sns` /
 *   `antirevoke.sns_block` 零耦合，不改 R42 写时拦截 / bit3 写回 / `[已删除]\n` 前缀。
 * - ⛔ 本文件不 import、不修改 `SnsSqlRewrite.kt` 的 `& -9 → & -1` 改写逻辑。
 *
 * ## 零 Android 依赖
 * [SnsWriteProbeCore] 是纯逻辑（零 Android/Xposed 引用，单测直接测）；[SnsWriteProbe]
 * 是桥接层，一律用**全限定名**调用 Android 侧，故本文件 `import` 区为空、整文件
 * 无任何 Android/Xposed import，Core 的 `.class` 可在纯 JVM 单测里独立加载。
 */
// ================================================================== 纯逻辑核心（零 Android 依赖）
object SnsWriteProbeCore {

    /** 环形账本封顶（LogX 5000 行环形 / K7 perf 每 250ms 刷屏 ⇒ before 内零日志，只在导出时打） */
    const val RING = 400

    /** `sub=dump` 明细封顶行数 */
    const val MAX_DUMP = 60

    enum class Kind { UPDATE, EXECSQL }

    /** `parseClear9` 结果。`rewritten=true` = 已被 `SnsSqlRewrite` 改成 `& -1` 的 no-op 态。 */
    data class Clear9(
        val table: String,
        val userName: String?,
        val sourceTypes: List<Int>,
        val lo: Long?,
        val hi: Long?,
        val rewritten: Boolean,
        val rawSql: String,
    )

    /** 行级命中条件（T0 = max(createTime) - 3*86400，10 位 Unix 秒）。 */
    data class HitCond(val t0Sec: Long, val lo: Long?, val hi: Long?, val userName: String?)

    /** 一行的最小投影，供纯函数 [hitOlder3d] 复算条数。 */
    data class Row(val createTime: Long, val snsId: Long, val userName: String?)

    /**
     * W1 结构化写事件记录。
     * `hitOlder3d` / `hitSample` 在 before 阶段恒为 null（**before 禁查库**），
     * 只在 `op=writeprobe` 导出阶段回填（[snapshot] 返回浅拷贝，回填即写回账本对象）。
     */
    data class WriteEvent(
        val ts: Long,
        val kind: Kind,
        val sql: String?,
        val cvKeys: List<String>,
        val cvSourceType: String?,
        val table: String?,
        val where: String?,
        val snsIds: String?,
        val userName: String?,
        val caller: String?,
        var hitOlder3d: Long? = null,
        var hitSample: String? = null,
    ) {
        /** W1 去重键：`kind|sql|userName`（⛔ 不能去掉 userName，否则不同好友的同形 SQL 会被合并） */
        val dedupKey: String get() = "${kind.name}|${sql.orEmpty()}|${userName.orEmpty()}"

        fun line(): String = buildString {
            append("ts=").append(ts).append(" kind=").append(kind.name)
            append(" table=").append(table ?: "-")
            append(" sql=").append((sql ?: "-").take(200))
            append(" cvKeys=").append(if (cvKeys.isEmpty()) "-" else cvKeys.joinToString(","))
            append(" cvSourceType=").append(cvSourceType ?: "-")
            append(" where=").append((where ?: "-").take(120))
            append(" snsIds=").append(snsIds ?: "-")
            append(" userName=").append(userName ?: "-")
            append(" caller=").append(caller ?: "-")
            append(" hitOlder3d=").append(hitOlder3d?.toString() ?: "未查(before禁查库)")
            append(" hitSample=").append(hitSample ?: "-")
        }
    }

    // ------------------------------------------------------------------ 正则（IGNORE_CASE + \s*：吃大小写/空格变体）
    /** SET 里的清 bit3 赋值；`-9`=原文清位，`-1`=已被改写为 no-op。⛔ 只认这两个，不认 `-3`。 */
    private val CLEAR9_SET = Regex(
        """sourceType\s*=\s*\(?\s*sourceType\s*&\s*\(?\s*(-9|-1)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SNSID_GT = Regex("""snsId\s*>\s*(-?\d+)""", RegexOption.IGNORE_CASE)
    private val SNSID_LT = Regex("""snsId\s*<\s*(-?\d+)""", RegexOption.IGNORE_CASE)
    private val SNSID_EQ = Regex("""snsId\s*=\s*(-?\d+)""", RegexOption.IGNORE_CASE)
    private val SRC_IN = Regex("""sourceType\s+in\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
    private val USER_NAME = Regex("""userName\s*=\s*['"]([^'"]*)['"]""", RegexOption.IGNORE_CASE)
    private const val TABLE = "SnsInfo"

    // ------------------------------------------------------------------ W2 纯函数：SQL 解析

    /**
     * 解析 `UPDATE SnsInfo SET sourceType = sourceType & -9 ...` 目标写事件。
     * - 返回 null = 非目标（NULL/空、非 UPDATE、非 SnsInfo、无清位赋值、`& -3`、SELECT/DELETE）。
     * - `& -1`（`SnsSqlRewrite` 改写后的 no-op）**必须返回非 null 且 `rewritten=true`**：
     *   识别成「已改写」而不是「无写事件」，这是防假阴性的关键。
     */
    fun parseClear9(sql: String?): Clear9? {
        if (sql.isNullOrBlank()) return null
        val s = sql.trimStart()
        if (!s.startsWith("update", ignoreCase = true)) return null
        // 词边界：`updateXxx` 不算 UPDATE
        val kw = s.takeWhile { !it.isWhitespace() }
        if (!kw.equals("update", ignoreCase = true)) return null
        if (!s.contains(TABLE, ignoreCase = true)) return null
        // 清位赋值必须出现在**顶层 WHERE 之前**（WHERE 里的同名表达式是过滤条件，不是写事件）
        val cut = topLevelWhereIndex(s)
        val setPart = if (cut < 0) s else s.substring(0, cut)
        val m = CLEAR9_SET.find(setPart) ?: return null
        // 用匹配段本身判「是否已被改写成 & -1」，而不是捕获组下标：
        // 这样正则形态变化时不会因 groupValues 越界崩，判据仍可单测。
        val rewritten = m.value.contains("-1")
        val userName = parseUserName(s)
        val lo = SNSID_GT.find(s)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val hi = SNSID_LT.find(s)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val sourceTypes = SRC_IN.find(s)?.groupValues?.getOrNull(1)
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        return Clear9(TABLE, userName, sourceTypes, lo, hi, rewritten, sql)
    }

    /** SQL 顶层 WHERE 子句（含 where 关键字本身）；没有则 null。 */
    fun whereClause(sql: String): String? {
        val i = topLevelWhereIndex(sql)
        return if (i < 0) null else sql.substring(i)
    }

    /** 找顶层（不在引号/括号里）的 `where` 起点，词边界校验；没有则 -1。 */
    private fun topLevelWhereIndex(sql: String): Int {
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

    fun parseUserName(s: String?): String? =
        if (s == null) null else USER_NAME.find(s)?.groupValues?.getOrNull(1)

    /** 从 SQL/where 抠 snsId：区间优先返回 `"lo..hi"`，否则等值字面量。 */
    fun parseSnsId(s: String?): String? {
        if (s == null) return null
        SNSID_GT.find(s)?.let { lo ->
            val hi = SNSID_LT.find(s)?.groupValues?.getOrNull(1)?.toLongOrNull()
            return rangeText(lo.groupValues[1].toLongOrNull(), hi)
        }
        return SNSID_EQ.find(s)?.groupValues?.getOrNull(1)
    }

    fun rangeText(lo: Long?, hi: Long?): String? {
        if (lo == null && hi == null) return null
        return "${lo ?: "*"}..${hi ?: "*"}"
    }

    // ------------------------------------------------------------------ W2 纯函数：行级命中判据

    /**
     * 三天外判据：只认 **10 位 Unix 秒** `[1e9,1e10)` 且严格 `< t0Sec`。
     * - 13 位毫秒（`>=1e12`）直接 false（⛔ 防秒/毫秒混淆：把毫秒当秒会恒 `< T0` 误命中）；
     * - `createTime == T0` **不算**三天外（严格 `<`，⛔ 不是 `<=`）。
     */
    fun isOlder3dSec(createTime: Long, t0Sec: Long): Boolean {
        if (createTime < 1_000_000_000L || createTime >= 10_000_000_000L) return false
        return createTime < t0Sec
    }

    /** snsId 开区间：`snsId > lo`（严格）、`snsId < hi`（严格）；null 边界=不限。 */
    fun inOpenRange(snsId: Long, lo: Long?, hi: Long?): Boolean {
        if (lo != null && snsId <= lo) return false
        if (hi != null && snsId >= hi) return false
        return true
    }

    /** target 空=不限定；否则必须相等（userName 不匹配 ⇒ 不命中）。 */
    fun userMatches(rowUser: String?, targetUser: String?): Boolean =
        targetUser.isNullOrEmpty() || rowUser == targetUser

    /** 单行命中判据：三天外 && 区间内 && userName 匹配。 */
    fun rowHit(createTime: Long, snsId: Long, rowUser: String?, cond: HitCond): Boolean =
        isOlder3dSec(createTime, cond.t0Sec) &&
            inOpenRange(snsId, cond.lo, cond.hi) &&
            userMatches(rowUser, cond.userName)

    /** 批量命中条数（判据的纯函数镜像；导出查库用等价 SQL）。 */
    fun hitOlder3d(rows: List<Row>, cond: HitCond): Long =
        rows.count { rowHit(it.createTime, it.snsId, it.userName, cond) }.toLong()

    // ------------------------------------------------------------------ W2 纯函数：只读 SQL 构造（防注入 + BLOB 用 hex）

    /** `'`→`''` 转义（userName 拼进 SQL 前必用）。 */
    fun sqlQuote(s: String): String = "'" + s.replace("'", "''") + "'"

    /**
     * 生成只读命中统计 SQL。
     * - 数字（T0 / lo / hi）是 Long，直接拼；userName 走 [sqlQuote] 转义（防注入）。
     * - ⛔ BLOB 判空一律 `hex(content)<>''`，**禁用 `cast(... as text)`**。
     * - 无内部 `;`，过 `SnsGuard.runReadOnlySql` 的 select 白名单。
     */
    fun buildHitCountSql(cond: HitCond): String {
        val sb = StringBuilder(180)
        sb.append("select count(*),min(snsId) from SnsInfo where createTime < ").append(cond.t0Sec)
        if (cond.lo != null) sb.append(" and snsId > ").append(cond.lo)
        if (cond.hi != null) sb.append(" and snsId < ").append(cond.hi)
        if (!cond.userName.isNullOrEmpty()) sb.append(" and userName=").append(sqlQuote(cond.userName))
        sb.append(" and hex(content)<>'' and hex(attrBuf)<>''")
        return sb.toString()
    }

    // ------------------------------------------------------------------ W3 互斥归因（⛔ 不并列）

    /**
     * 三态互斥（if / else if / else）：
     * - `seen == 0` ⇒ 无观测机会（钩子没被调用 / 没有写事件经过）；
     * - `seen > 0 && matched == 0` ⇒ 挂对了但判据不识别（有写事件，但没认出目标 SQL）；
     * - `matched > 0` ⇒ 有真实写事件。
     */
    fun verdict(seen: Long, matched: Long): String =
        if (seen == 0L) "无观测机会(seen=0)"
        else if (matched == 0L) "挂对了但判据不识别(seen>0,matched=0)"
        else "有真实写事件(matched>0)"

    // ------------------------------------------------------------------ 账本（环形 + 去重 + 封顶 + 进程内冷启清零）
    private val events = ArrayList<WriteEvent>(RING)
    private val dedup = HashSet<String>()
    private val seen = java.util.concurrent.atomic.AtomicLong(0)
    private val matched = java.util.concurrent.atomic.AtomicLong(0)

    /** 总开关。进程内默认 false ⇒ 冷启（进程重建）天然清零；`arm` 之前的写事件不追溯。 */
    @Volatile
    private var armed = false

    fun isArmed(): Boolean = armed
    fun setArmed(v: Boolean) { armed = v }

    /** seen/matched 计数读回（导出用）。 */
    fun counts(): Pair<Long, Long> = seen.get() to matched.get()

    /**
     * 记一条写事件。seen 每次 +1（不论去重）；`parseClear9` 非 null ⇒ matched +1。
     * 去重用 [WriteEvent.dedupKey]；超 [RING] 环形淘汰并同步移除 dedup 键。
     * @return 是否真入账（false = 被去重丢弃）。
     */
    fun record(ev: WriteEvent): Boolean {
        seen.incrementAndGet()
        if (parseClear9(ev.sql) != null) matched.incrementAndGet()
        val key = ev.dedupKey
        var added = false
        synchronized(events) {
            if (dedup.add(key)) {
                events.add(ev)
                while (events.size > RING) {
                    val old = events.removeAt(0)
                    dedup.remove(old.dedupKey)
                }
                added = true
            }
        }
        return added
    }

    /** 浅拷贝快照：元素与账本**同引用**，导出回填 hitOlder3d/hitSample 即写回账本。 */
    fun snapshot(): List<WriteEvent> = synchronized(events) { ArrayList(events) }

    /** 账本摘要 + 三态归因 + 三档边界提示（逐档不省略，⛔ 复位≠返回）。 */
    fun stat(): List<String> {
        val s = seen.get()
        val m = matched.get()
        val snap = synchronized(events) { ArrayList(events) }
        val c9 = snap.count { parseClear9(it.sql) != null }
        val rw = snap.count { parseClear9(it.sql)?.rewritten == true }
        val exec = snap.count { it.kind == Kind.EXECSQL }
        val dk = synchronized(events) { dedup.size }
        return listOf(
            "WP stat armed=$armed seen=$s matched=$m verdict=${verdict(s, m)}",
            "WP ledger size=${snap.size}/$RING dedupKeys=$dk clear9=$c9 rewritten=$rw execSql=$exec",
            "WP 档①进入: 进好友主页触发 & -9 raw SQL; ⛔ 库快照差分必假阴性(已改写 & -1), 只看 before 原文账本",
            "WP 档②返回: 切页/刷新/同步 账本继续追加不重置; 冷启账本进程内结构⇒全清零(预期,非bug),须重新 arm",
            "WP 档③复位: 关 sns.write_probe 账本停止增长(明细保留供末次 dump); 磁盘已写的位不回滚; arm 前不追溯; ⛔ 复位≠返回",
        )
    }

    /** 明细（封顶 [MAX_DUMP] 行）。 */
    fun dump(): List<String> {
        val snap = synchronized(events) { ArrayList(events) }
        val out = ArrayList<String>(snap.size + 1)
        out += "WP dump total=${snap.size} show=${(snap.size).coerceAtMost(MAX_DUMP)} (封顶 $MAX_DUMP)"
        val from = (snap.size - MAX_DUMP).coerceAtLeast(0)
        for (i in from until snap.size) out += "WP " + snap[i].line()
        return out
    }

    /** 清账本 + 计数（⛔ 不改 armed：复位档里「关开关」是 disarm，单独做）。 */
    fun reset() {
        synchronized(events) {
            events.clear()
            dedup.clear()
        }
        seen.set(0)
        matched.set(0)
    }
}

// ================================================================== Android 桥接（全限定名，零 import）
object SnsWriteProbe {

    private val KEY = com.lx.wxgroup.data.Keys.SNS_WRITE_PROBE

    /** 热路径唯一动作：纯内存 boolean 判断（关时零额外动作）。 */
    fun isArmed(): Boolean = SnsWriteProbeCore.isArmed()

    /**
     * `SnsGuard.snsBlockGuard.before`（`updateWithOnConflict`）调用。
     * ⚠️ 调用方已用 `isArmed()` 短路，本函数只在 armed 时执行；非 SnsInfo 立即返回。
     * before 内**零查库、零日志、零改写**：只读 `param.args` 抽字段后 [SnsWriteProbeCore.record]。
     */
    fun captureUpdate(args: Array<Any?>?) {
        if (args == null || args.size < 2) return
        val table = args[0] as? String ?: return
        if (table != "SnsInfo") return
        val where = args.getOrNull(2) as? String
        val cv = args.getOrNull(1)
        SnsWriteProbeCore.record(
            SnsWriteProbeCore.WriteEvent(
                ts = System.currentTimeMillis(),
                kind = SnsWriteProbeCore.Kind.UPDATE,
                sql = null,
                cvKeys = cvKeys(cv),
                cvSourceType = cvSourceType(cv),
                table = table,
                where = where,
                snsIds = SnsWriteProbeCore.parseSnsId(where),
                userName = SnsWriteProbeCore.parseUserName(where),
                caller = callerOf(),
            )
        )
    }

    /**
     * `SnsBlockProbe.noteSql` 首行调用。`sql` 是**改写前的原文**
     * （`SnsGuard.onSnsSql` 的原始局部变量；`applySqlRewrite` 只改 `param.args[0]`）。
     * ⚠️ 放在 noteSql 第一行 `sqlSns++` 之前，保证 seen 覆盖所有含 SnsInfo 的 execSQL。
     */
    fun captureExecSql(sql: String?) {
        if (sql.isNullOrBlank()) return
        if (!sql.contains("SnsInfo", ignoreCase = true)) return
        val c9 = SnsWriteProbeCore.parseClear9(sql)
        SnsWriteProbeCore.record(
            SnsWriteProbeCore.WriteEvent(
                ts = System.currentTimeMillis(),
                kind = SnsWriteProbeCore.Kind.EXECSQL,
                sql = sql,
                cvKeys = emptyList(),
                cvSourceType = null,
                table = "SnsInfo",
                where = SnsWriteProbeCore.whereClause(sql),
                snsIds = c9?.let { SnsWriteProbeCore.rangeText(it.lo, it.hi) },
                userName = c9?.userName,
                caller = callerOf(),
            )
        )
    }

    // ------------------------------------------------------------------ 导出（op=writeprobe，只读微信库）

    /**
     * `sub=stat|dump|reset|arm|disarm`。
     * - stat/dump：行级命中判定在**导出阶段**用 `SnsGuard.runReadOnlySql`（select 白名单）查，
     *   ⛔ before 内零查库；BLOB 判空走 `hex()`（[SnsWriteProbeCore.buildHitCountSql]）。
     * - arm/disarm：只写模块自有 KV（`Kv.putBool(KEY,...)`）+ 改内存 armed，
     *   ⛔ **不写微信库、不调 `SnsGuard.refresh()`、不碰任何 `ANTIREVOKE_*` key**。
     */
    fun export(sub: String): List<String> = when (sub.lowercase()) {
        "stat" -> {
            val t0 = resolveT0()
            val (done, total) = fillHits(t0)
            val out = ArrayList(SnsWriteProbeCore.stat())
            out += if (t0 == null)
                "WP hit: 跳过行级命中(snsDb 未捕获,先刷一次朋友圈); 账本观测不受影响"
            else
                "WP hit: t0=$t0 events=$done hitOlder3dTotal=$total (只读 select,库值不变)"
            out += "WP switch kv=${kvArmed()} memArmed=${SnsWriteProbeCore.isArmed()} " +
                "(冷启 memArmed 恒 false, 须重新 arm)"
            out
        }
        "dump" -> {
            fillHits(resolveT0())
            SnsWriteProbeCore.dump()
        }
        "reset" -> {
            SnsWriteProbeCore.reset()
            listOf("WP reset: 账本+计数已清零 (armed 不变=${SnsWriteProbeCore.isArmed()})")
        }
        "arm" -> {
            com.lx.wxgroup.data.Kv.putBool(KEY, true)
            SnsWriteProbeCore.setArmed(true)
            listOf("WP arm: sns.write_probe=true (模块自有 KV); 内存 armed=true; ⛔ 未碰微信库/R42 开关")
        }
        "disarm" -> {
            com.lx.wxgroup.data.Kv.putBool(KEY, false)
            SnsWriteProbeCore.setArmed(false)
            listOf("WP disarm: sns.write_probe=false; 内存 armed=false; 账本停止增长(明细保留)")
        }
        else -> listOf("WP usage: op=writeprobe sub=stat|dump|reset|arm|disarm")
    }

    private fun kvArmed(): Boolean = com.lx.wxgroup.data.Kv.getBool(KEY)

    /** T0 = max(createTime) - 3*86400（10 位秒）；snsDb 未捕获返回 null。 */
    private fun resolveT0(): Long? {
        val rows = com.lx.wxgroup.feature.SnsGuard.runReadOnlySql(
            "select max(createTime) from SnsInfo", 1
        )
        val r = rows.firstOrNull { it.startsWith("SQLX r") } ?: return null
        val mx = r.substringAfter("SQLX r1 ").trim().toLongOrNull() ?: return null
        return mx - 3L * 86400L
    }

    /** 导出阶段行级命中回填：对每条 `& -9`/`& -1` 事件查一次只读 count，写回账本对象。 */
    private fun fillHits(t0: Long?): Pair<Int, Long> {
        if (t0 == null) return 0 to 0L
        val snap = SnsWriteProbeCore.snapshot()
        var done = 0
        var total = 0L
        for (ev in snap) {
            val c9 = SnsWriteProbeCore.parseClear9(ev.sql) ?: continue
            // userName 解析不到 ⇒ 保守跳过（不加 userName 条件会查全表、高估命中），不查库
            if (c9.userName.isNullOrEmpty()) {
                ev.hitSample = "userName解析失败,跳过"
                continue
            }
            val cond = SnsWriteProbeCore.HitCond(t0, c9.lo, c9.hi, c9.userName)
            val rows = com.lx.wxgroup.feature.SnsGuard.runReadOnlySql(
                SnsWriteProbeCore.buildHitCountSql(cond), 1
            )
            val (cnt, sample) = parseCountRow(rows)
            ev.hitOlder3d = cnt
            ev.hitSample = sample
            total += cnt
            done++
        }
        return done to total
    }

    /** 解析 `SQLX r1 <count> | <minSnsId>`。 */
    private fun parseCountRow(rows: List<String>): Pair<Long, String?> {
        val r = rows.firstOrNull { it.startsWith("SQLX r") } ?: return 0L to null
        val parts = r.substringAfter("SQLX r1 ").split("|").map { it.trim() }
        val cnt = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val sample = parts.getOrNull(1)?.takeIf { it.isNotEmpty() && it != "null" }
        return cnt to sample
    }

    // ------------------------------------------------------------------ ContentValues 反射（只读，取不到就留空）
    private fun cvKeys(cv: Any?): List<String> = runCatching {
        if (cv == null) return emptyList()
        val m = cv.javaClass.getMethod("keySet")
        @Suppress("UNCHECKED_CAST")
        (m.invoke(cv) as? Set<String>)?.toList() ?: emptyList()
    }.getOrDefault(emptyList())

    private fun cvSourceType(cv: Any?): String? = runCatching {
        if (cv == null) return null
        val m = cv.javaClass.getMethod("get", String::class.java)
        m.invoke(cv, "sourceType")?.toString()
    }.getOrNull()

    /** 调用者签名（能取到就取；只在 armed 时调，非热路径）。 */
    private fun callerOf(): String? = runCatching {
        Throwable().stackTrace.firstOrNull { f ->
            val n = f.className
            !n.startsWith("com.lx.wxgroup.feature.SnsWriteProbe") &&
                !n.startsWith("com.lx.wxgroup.feature.SnsGuard") &&
                !n.startsWith("com.lx.wxgroup.feature.SnsBlockProbe") &&
                !n.startsWith("de.robv.android.xposed") &&
                !n.startsWith("java.lang.reflect") &&
                !n.startsWith("kotlin.") &&
                !n.startsWith("dalvik.")
        }?.let { "${it.className}.${it.methodName}" }
    }.getOrNull()
}
