package com.lx.wxgroup.feature

/**
 * 朋友圈**评论**防撤回 / `[已删除]` 标签的**纯逻辑**（零 Android 依赖 → 可直接跑 JVM 单测）。
 *
 * 跟 [SnsTagLogic] 同一套路：判据都是靠真机取证试出来的，改错一次就要重上一轮真机
 * （≈2 分钟 + 大量排查），所以判据必须先在这里锁住，真机只做最后一公里验证。
 *
 * ⚠️ 本文件**禁止 import 任何 android.* / xposed.* **，否则单测加载不了。
 *
 * ## 真机取证事实（8.0.48 / code 2588，不要推翻）
 * - 朋友圈是独立库 `MicroMsg/<md5>/SnsMicroMsg.db`；`SnsComment` **16 列**。
 * - 列：`snsID / parentID / isRead / createTime / talker / type / isSend /
 *   curActionBuf / refActionBuf / commentSvrID / clientId / commentflag /
 *   isSilence / springWishFlag / isReminding / msgRelevanceType`。
 *   **没有 content 列** —— 评论正文在 `curActionBuf` BLOB 里。
 * - 删评论 = `execSQL("delete from SnsComment where snsID=? and commentSvrID=?")`，
 *   线程 `[D]#worker`。
 * - ⚠️ **未实证**：删评论时微信有没有先重写 `SnsInfo.attrBuf`
 *   （实测顺序 update SnsInfo → replace SnsInfo → delete SnsComment）。
 *   所以 attrBuf 还原一律走 [planAttr] 的保守判据，拿不到证据就**不写**。
 */
object SnsCommentLogic {

    const val TABLE = "SnsComment"
    const val COL_SNS_ID = "snsID"
    const val COL_PARENT_ID = "parentID"
    const val COL_SVR_ID = "commentSvrID"

    /** 我们自己归档表的列类型（不是微信的 schema，微信侧全靠列名取） */
    enum class Kind { INT, TEXT, BLOB }

    data class Col(val name: String, val kind: Kind)

    /** 实测 16 列，顺序即 `pragma table_info` 的返回顺序 */
    val SCHEMA: List<Col> = listOf(
        Col("snsID", Kind.INT),
        Col("parentID", Kind.INT),
        Col("isRead", Kind.INT),
        Col("createTime", Kind.INT),
        Col("talker", Kind.TEXT),
        Col("type", Kind.INT),
        Col("isSend", Kind.INT),
        Col("curActionBuf", Kind.BLOB),
        Col("refActionBuf", Kind.BLOB),
        Col("commentSvrID", Kind.INT),
        Col("clientId", Kind.TEXT),
        Col("commentflag", Kind.INT),
        Col("isSilence", Kind.INT),
        Col("springWishFlag", Kind.INT),
        Col("isReminding", Kind.INT),
        Col("msgRelevanceType", Kind.INT),
    )

    fun columns(): List<String> = SCHEMA.map { it.name }
    fun blobColumns(): List<String> = SCHEMA.filter { it.kind == Kind.BLOB }.map { it.name }
    fun textColumns(): List<String> = SCHEMA.filter { it.kind == Kind.TEXT }.map { it.name }
    fun intColumns(): List<String> = SCHEMA.filter { it.kind == Kind.INT }.map { it.name }

    // ------------------------------------------------------------------ id

    /** 一条评论的复合主键：`(snsID, commentSvrID)` */
    data class Id(val snsId: Long, val svrId: Long)

    fun key(snsId: Long, svrId: Long): String = "$snsId:$svrId"
    fun key(id: Id): String = key(id.snsId, id.svrId)

    // ------------------------------------------------------------------ SQL 识别

    private val FROM_TBL = Regex("""from\s+SnsComment\b""", RegexOption.IGNORE_CASE)
    private val SNS_ASSIGN = Regex("""snsID\s*=""", RegexOption.IGNORE_CASE)
    private val SVR_ASSIGN = Regex("""commentSvrID\s*=""", RegexOption.IGNORE_CASE)

    /** `snsID = 123` / `snsID = '123'` / `snsID = ?`；`snsID < ?` 这类**匹配不到**（有意为之） */
    private val SNS_EQ = Regex("""snsID\s*=\s*('?(-?\d{15,20})'?|\?)""", RegexOption.IGNORE_CASE)
    private val SVR_EQ = Regex("""commentSvrID\s*=\s*('?(-?\d{1,19})'?|\?)""", RegexOption.IGNORE_CASE)

    /**
     * `execSQL(sql)` / `execSQL(sql, bindArgs)` 形态：是不是在删**具体一条**评论。
     *
     * @return [Id]；null = 不是「删 SnsComment 的某一条」，放行
     */
    fun matchDeleteSql(sql: String?, bindArgs: Array<*>? = null): Id? =
        match(sql, bindArgs, SNS_EQ, SVR_EQ, SNS_ASSIGN, SVR_ASSIGN) {
            it.trimStart().startsWith("delete", ignoreCase = true) && FROM_TBL.containsMatchIn(it)
        }

    /** `delete(table, whereClause, whereArgs)` 形态（若微信哪天换成这种写法） */
    fun matchDeleteCall(table: String?, whereClause: String?, whereArgs: Array<*>?): Id? {
        if (table == null || !table.equals(TABLE, ignoreCase = true)) return null
        return match(whereClause, whereArgs, SNS_EQ, SVR_EQ, SNS_ASSIGN, SVR_ASSIGN) { true }
    }

    /**
     * 只要 snsID（不管有没有 commentSvrID）—— 给 attrBuf 追踪用：
     * 追踪阶段关心的是「这条动态被写了」，不是「删了哪条评论」。
     */
    fun snsIdOf(whereClause: String?, whereArgs: Array<*>?): Long? {
        if (whereClause == null) return null
        val m = SNS_EQ.find(whereClause) ?: return null
        m.groupValues.getOrNull(2)?.let { if (it.isNotEmpty()) SnsTagLogic.parseSnsId(it)?.let { v -> return nonZero(v) } }
        val a = SNS_ASSIGN.find(whereClause) ?: return null
        return nonZero(argAt(whereClause, a.range.first, whereArgs))
    }

    private fun match(
        s: String?,
        args: Array<*>?,
        snsRe: Regex,
        svrRe: Regex,
        snsAssign: Regex,
        svrAssign: Regex,
        headOk: (String) -> Boolean,
    ): Id? {
        if (s == null || !headOk(s)) return null
        val a = snsRe.find(s) ?: return null
        val b = svrRe.find(s) ?: return null
        val snsAt = snsAssign.find(s)?.range?.first ?: a.range.first
        val svrAt = svrAssign.find(s)?.range?.first ?: b.range.first
        val sns = a.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
            ?.let { SnsTagLogic.parseSnsId(it) } ?: argAt(s, snsAt, args)
        val svr = b.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
            ?.let { it.toLongOrNull() } ?: argAt(s, svrAt, args)
        if (nonZero(sns) == null) return null
        // ⚠️ 定位不到 commentSvrID = 「删这条动态的全部评论」或清缓存 —— 跟 SnsGuard 的
        //    `snsId=` 硬约束同源，**一律放行**，否则 SnsMicroMsg.db 只涨不删。
        if (svr == null) return null
        return Id(sns!!, svr)
    }

    /** snsId 恒为非零 64 位值；0 = 没解析出来，按「不命中」处理 */
    private fun nonZero(v: Long?): Long? = if (v == null || v == 0L) null else v

    /**
     * 参数占位符取值：数一下 [at] 之前有几个 `?`，那个数就是它在 args 里的下标。
     * `localFlag=? and snsId=?` 里 snsId 前面有 1 个 `?` → 取 args[1]。
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
            is String -> SnsTagLogic.parseSnsId(v)
            else -> null
        }
    }

    // ------------------------------------------------------------------ attrBuf 差量

    enum class Act {
        /** 不写：证据不足或风险太大（默认降级路径） */
        SKIP,

        /** 当前 attrBuf 跟快照一致 —— 说明微信**没重写**，不用动（这就是取证结论 #4 的正面证据） */
        KEEP,

        /** 当前比快照少内容 —— 判定为「已抹掉这条评论」，写回快照 */
        RESTORE,
    }

    data class Verdict(
        val act: Act,
        val reason: String,
        val oldSize: Int,
        val newSize: Int,
    )

    /** attrBuf 实测 235B~1069B，低于 16B 基本不可能是正常的可见列表 */
    private const val MIN_BLOB = 16

    /** 删掉一条评论带来的缩容最多几十字节；超过 2K 说明不是同一件事，放弃 */
    private const val MAX_SHRINK = 2048

    /**
     * 拿不拿到「删之前」的 attrBuf 快照，决定能不能还原。规则刻意保守：
     *
     * | 情况 | 判定 | 理由 |
     * |---|---|---|
     * | 缺一边 | SKIP | 没有对照，写任何东西都是猜 |
     * | 两边一样 | KEEP | 微信压根没重写 attrBuf → 只要行还在，UI 自然还显示 |
     * | 当前比快照**大** | SKIP | 期间来了新评论，覆盖会把新评论也抹了 |
     * | 缩容 > 2K | SKIP | 不是「删一条评论」的量级（可能是整条动态被重写） |
     * | 小幅缩容 | RESTORE | 快照里有、当前没有 → 写回快照 |
     */
    fun planAttr(old: ByteArray?, new: ByteArray?): Verdict {
        val o = old?.size ?: 0
        val n = new?.size ?: 0
        if (old == null || new == null) return Verdict(Act.SKIP, "missing blob", o, n)
        if (o < MIN_BLOB || n < MIN_BLOB) return Verdict(Act.SKIP, "blob too small", o, n)
        if (old.contentEquals(new)) return Verdict(Act.KEEP, "attrBuf unchanged", o, n)
        if (n > o) return Verdict(Act.SKIP, "current larger than snapshot", o, n)
        if (o - n > MAX_SHRINK) return Verdict(Act.SKIP, "shrink too large", o, n)
        return Verdict(Act.RESTORE, "snapshot has more entries", o, n)
    }

    // ------------------------------------------------------------------ 正文候选 / 对账

    private const val MAX_CANDIDATES = 8

    /**
     * 从 `curActionBuf` 里抽出**可读文本片段**，只为「这条评论是不是我要找的那条」做对账用。
     *
     * ⚠️ **这不是 protobuf 解析**：微信这个 BLOB 的 schema 我们没取证，这里只是拿
     * protobuf 的通用线格式（tag + length + UTF-8 字节）做切片——tag/length 字节都 < 0x20，
     * 天然就是分隔符。抽出来的东西**只用于对账，不当正文展示**。
     *
     * 已知噪声：长度字节 ≥ 32 时会变成一个可见 ASCII 字符粘在片段开头，
     * 所以 [textHit] 用的是「包含」而不是「相等」。
     */
    fun textCandidates(blob: ByteArray?, minLen: Int = 2): List<String> {
        if (blob == null || blob.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        val run = ArrayList<Byte>()
        fun flush() {
            if (run.size >= minLen) {
                val s = run.toByteArray().toString(Charsets.UTF_8).trim()
                val okLen = s.length >= minLen
                val okChar = s.all { it.isLetterOrDigit() || it in ' '..'~' || it.code > 0x7F }
                if (okLen && okChar && s.any { it.isLetterOrDigit() }) out += s
            }
            run.clear()
        }
        for (b in blob) {
            val v = b.toInt() and 0xFF
            if (v < 0x20 || v == 0x7F) flush() else run += b
        }
        flush()
        return out.take(MAX_CANDIDATES)
    }

    /**
     * 评论行 View 的文案 vs 正文候选。**严格**：短碎片、完全对不上都不算命中。
     * 打错标签（把没删的评论标成已删除）比不打标签糟糕得多，宁可让 missed 涨。
     */
    fun textHit(cands: List<String>, texts: List<String>): Boolean {
        if (cands.isEmpty() || texts.isEmpty()) return false
        for (c in cands) {
            if (c.length < 2) continue
            for (t in texts) {
                val s = t.trim()
                if (s.length < 2) continue
                if (s == c) return true
                // 只在中文字数够多时才认「包含」关系，避免一两字碎片恒真
                if (s.length >= 4 && (s.contains(c) || c.contains(s))) return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ 诊断输出

    /** BLOB 头部 hex，probe 输出用（不落整块，日志扛不住） */
    fun hex(b: ByteArray?, n: Int = 8): String {
        if (b == null) return "(null)"
        if (b.isEmpty()) return "(empty)"
        val take = minOf(n, b.size)
        val sb = StringBuilder(take * 2)
        for (i in 0 until take) sb.append(String.format("%02x", b[i].toInt() and 0xFF))
        return sb.toString()
    }
}
