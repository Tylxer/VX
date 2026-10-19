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

    // ------------------------------------------------------------ 渲染侧匹配键

    /**
     * **渲染侧**（UI 层）识别一条评论用的键。
     *
     * ## 为什么不用 `commentSvrID`
     * 真机实测（见 `docs/取证结论.md` ⑩ 段）：
     * UI 层评论 proto `q54/q95` 里**两个 long 字段 `q` / `r` 恒为 0**，
     * 微信根本没把 `commentSvrID` 带到 UI 层。所以按 svrId 匹配行**不可行**。
     *
     * 改用微信**自己**的判重键（`s1.q1` 的 `isExistComment` 就是这么查的）：
     * ```
     * select count(*) from SnsComment where snsID=? and createTime=? and talker=?
     * ```
     * UI 侧对应的 proto 字段全部实测非 0：`d`=talker、`i`=createTime、`g`=type。
     *
     * @param createTime 评论创建时间（秒）—— UI 与 DB **同单位，无需换算**
     * @param talker     评论者 wxid
     * @param type       1=评论 / 2=回复（加进来是为了区分同一人同秒的评论与回复）
     */
    data class RenderKey(
        val createTime: Long,
        val talker: String,
        val type: Int,
    ) {
        /** 规范化：talker 去空白；空串视为「未知」，不可用于匹配 */
        fun normalized(): RenderKey? {
            val t = talker.trim()
            if (createTime <= 0L || t.isEmpty()) return null
            return RenderKey(createTime, t, type)
        }
    }

    fun renderKey(createTime: Long, talker: String?, type: Int): RenderKey? =
        RenderKey(createTime, talker ?: "", type).normalized()

    fun keyOf(k: RenderKey): String = "${k.createTime}:${k.talker}:${k.type}"

    /**
     * 诊断用：`key` 与集合里某条**只有 `type` 不同**吗？
     *
     * 用途：判定「键语义错位」还是「本来就没删这条」。若为 true，说明
     * `(createTime, talker)` 完全对得上 —— 同一条评论确实在归档里，只是 `type` 值不同
     * （例如 DB 存 `8/16` 而 UI 给 `2`）。这时**绝不能打标**（type 不同就该当成不同评论），
     * 但要计数报警，否则"全量漏标"和"本来就没删"在日志里长得一模一样。
     *
     * @param key       UI 侧读到的渲染键，形如 `"1792410895:wxid_x:2"`
     * @param archived  归档侧的全部渲染键
     */
    fun typeOnlyMismatch(key: String, archived: Collection<String>): Boolean {
        val cut = key.lastIndexOf(':')
        if (cut <= 0) return false
        val head = key.substring(0, cut + 1)   // "1792410895:wxid_x:"
        return archived.any { it != key && it.startsWith(head) && !it.substring(cut + 1).contains(':') }
    }

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

    // ------------------------------------------------------------------ 「已删除」形态判定

    /** 需求 6.2 第 5 项：被删评论的位置显示的文本 */
    const val LABEL_DELETED = "[已删除]"

    /**
     * 一条评论「已被删除」的形态 —— **只有两种，已静态取证定案**。
     *
     * ⚠️ 曾经还有第三种 `FLAGGED`（「行还在、正文也在，只是被打上删除标记位」）。
     * **2026-10-19 静态取证证明它在 8.0.48 不存在，已删除这个枚举值**，证据见
     * `docs/取证结论.md` §「朋友圈评论」：
     * - 删单条评论 = `s1.Q0` 里拼接的**物理 DELETE**：
     *   `delete from SnsComment where snsID=? and commentSvrID=? and type in (8,16)`
     *   ⇒ 行直接没了，没有 flag 可读。
     * - 唯一写 `commentflag` 的两条 SQL 都不承载「单条评论被删」语义：
     *   `setCommentDeleted` → `commentflag=1 where snsID=? and talker='<user>'`（某人删光自己的评论）
     *   `updateSnsDel`      → `commentflag=2 where snsID=?`（整条动态被删）
     * - `commentflag` 列名全 APK 只有 WCDB 实体类 `Ljj/t9` 的 ORM 管道引用，**业务代码零读取**。
     */
    enum class DelForm {
        /** 正常评论 —— 不打标 */
        ALIVE,

        /** 形态 A：整行消失 —— 按 (snsID, commentSvrID) 查不到这一行了（**删除的唯一实际形态**） */
        MISSING,

        /** 形态 B：内容清空 —— 行还在，但正文是空 / 全空白 / 只剩标点 */
        EMPTY,
    }

    /**
     * 一条评论在某个时刻的可观测状态。
     *
     * @param present 本地库里这一行还在吗（`SnsComment` 按 (snsID, commentSvrID) 点查）
     * @param content 抽出来的正文；⚠️ `null` = **抽不出来（未知）**，不等于「空」
     */
    data class CommentState(
        val present: Boolean,
        val content: String?,
    )

    /**
     * 正文是不是「被清空了」：null / 空串 / 全空白 / 只剩标点。
     *
     * ⚠️ `null` 一律返回 **false**（未知 ≠ 空）—— 抽不出正文时不能当作「已删除」的证据。
     * ⚠️ 纯 emoji 评论是**正常内容**，不能判成空（所以 OTHER_SYMBOL / SURROGATE 算有内容）。
     */
    private val MEANINGFUL_CATEGORIES = setOf(
        CharCategory.OTHER_SYMBOL, // emoji / 图形符号
        CharCategory.SURROGATE,    // emoji 的高低代理项（BMP 外的符号）
    )

    fun isBlankish(s: String?): Boolean {
        if (s == null) return false
        if (s.isBlank()) return true
        return s.none { it.isLetterOrDigit() || it.category in MEANINGFUL_CATEGORIES }
    }

    /**
     * 判定一条评论处于哪种形态。规则刻意保守（打错标签比不打标签糟糕得多）：
     *
     * | 输入 | 判定 | 理由 |
     * |---|---|---|
     * | 行没了 | MISSING | 这是最硬的证据（**删除的唯一实际形态**，见 [DelForm] 注释） |
     * | 行在、抽不出正文 | ALIVE | 没有证据，不打标 |
     * | 行在、正文空/空白/纯标点 | EMPTY | 正文被抹掉了 |
     * | 其余 | ALIVE | |
     *
     * ⚠️ 没有 `mask` 参数了：`commentflag` 已被静态取证证明**不承载「单条评论被删」语义**，
     * 详见 [DelForm] 的注释。不要再加回来。
     */
    fun classify(st: CommentState): DelForm {
        if (!st.present) return DelForm.MISSING
        val c = st.content ?: return DelForm.ALIVE
        if (isBlankish(c)) return DelForm.EMPTY
        return DelForm.ALIVE
    }

    /** 是不是「已被删除的评论」（三种形态任一） */
    fun isDeleted(st: CommentState): Boolean =
        classify(st) != DelForm.ALIVE

    /**
     * `[已删除]` 文本拼装。
     *
     * - [DelForm.ALIVE] → **null**，表示「不打标」（调用方必须按 null 处理，不能当空串塞进 UI）
     * - 有昵称 → `<昵称>：[已删除]`；昵称为空/全空白 → 只出 `[已删除]`
     * - ⚠️ **一律不保留残留正文**：需求要的就是「显示 [已删除] 而不是残留内容」
     */
    fun labelText(form: DelForm, nick: String? = null): String? {
        if (form == DelForm.ALIVE) return null
        val n = nick?.trim()?.takeIf { it.isNotEmpty() } ?: return LABEL_DELETED
        return "$n：$LABEL_DELETED"
    }

    // ------------------------------------------------------------------ 差集判定（需求 6.2 主判据）
    //
    // 背景（主 agent 真机取证）：评论**不在** SnsInfo.attrBuf 里，而在 SnsMicroMsg.db 的
    // SnsComment 表（16 列）。SnsCommentGuard 已经把被拦下的删除按
    // `(snsID, commentSvrID)` 归档好了（真机 op=snsc sub=stat → archived>0）。
    // ⇒ 「哪条被删了」= **归档里有、当前表里查不到的差集**，别的判据现在都不可信。
    //
    // 标识统一用 **commentSvrID**：跟 SnsCommentGuard.archivedIds() 的形状
    // （`snsId -> Set<commentSvrID>`）以及归档表主键 PRIMARY KEY(snsID, commentSvrID) 一致。
    // clientId 只是顺带存的定位辅助，不参与判定。

    /**
     * 差集结果。
     *
     * @param deleted 判为「已删除」的 commentSvrID —— 只有这些允许打 `[已删除]`
     * @param kept    归档里有、当前表里**仍在**的 —— 一律不打标（删了又回来了 / 拦截误报）
     * @param unknown 证据不足、**不敢判**的 —— 一律不打标（宁可漏标不可误标）
     */
    data class DeletedDiff(
        val deleted: Set<Long> = emptySet(),
        val kept: Set<Long> = emptySet(),
        val unknown: Set<Long> = emptySet(),
    ) {
        val size: Int get() = deleted.size + kept.size + unknown.size
    }

    /**
     * ⚠️⚠️ **「判定已删」的唯一可替换点**。
     *
     * 阶段 0 取证还差最后一步：真删一条评论后，SnsComment 里那行到底是**消失**还是
     * **被标记**（`snsc sub=form` 跑完才知道）。取证回来**只改这一个函数体**，
     * 调用方（SnsCommentTag 等）一行都不用动 —— 签名和 [DeletedDiff] 的语义保持不变，
     * 形态 B/C 只要把「行还在」的那部分再过一遍 [classify] 即可。
     *
     * 当前实现 = 形态 A「整行消失」：纯差集。
     *
     * @param archived   归档里记下的 commentSvrID（删之前的快照）
     * @param present    当前 SnsComment 表同一 snsID 下查到的 commentSvrID；
     *                   ⚠️ **null = 没查成**（库没就绪 / 查询抛异常 / 表不存在），
     *                   **不等于「查到 0 条」** —— 这两者必须区分开
     * @param trustEmpty `present` 为空时是否仍可信。默认 **false**：归档非空而表里一条都
     *                   查不到，更像「库没抓到 / snsID 变了 / 这次查询根本没执行」，
     *                   而不是「评论全被删了」。整批退回 [DeletedDiff.unknown]，
     *                   否则一次误判就是**满屏正常评论被打成已删除**（事故级）。
     *                   调用方能确定「这张表我确实查过、确实一条没有」时才传 true。
     */
    fun diffDeleted(
        archived: Set<Long>,
        present: Set<Long>?,
        trustEmpty: Boolean = false,
    ): DeletedDiff {
        if (archived.isEmpty()) return DeletedDiff()
        // 查询没成 → 一条都不敢判
        if (present == null) return DeletedDiff(unknown = archived)
        // 快照整批为空且不被信任 → 一条都不敢判
        if (present.isEmpty() && !trustEmpty) return DeletedDiff(unknown = archived)

        val deleted = HashSet<Long>()
        val kept = HashSet<Long>()
        for (id in archived) {
            if (id in present) kept.add(id) else deleted.add(id)
        }
        return DeletedDiff(deleted = deleted, kept = kept)
    }

    /** [diffDeleted] 的简写版：只要「已删」的那部分（调用方最常用的形状） */
    fun deletedIds(
        archived: Set<Long>,
        present: Set<Long>?,
        trustEmpty: Boolean = false,
    ): Set<Long> = diffDeleted(archived, present, trustEmpty).deleted

    /**
     * 批量版：按 snsID 分组跑 [diffDeleted]。
     *
     * @param archived 归档：`snsId -> commentSvrID 集合`（就是 `SnsCommentGuard.archivedIds()` 的形状）
     * @param live     取「当前表里某个 snsID 下的全部 commentSvrID」的回调（纯函数注入 → 好造单测）；
     *                 返回 null = 这个 snsID 这次没查成（只影响这一组，不连坐别的组）
     *
     * ⚠️ 参数顺序**故意**把 `live` 放最后：Kotlin 的尾随 lambda 只能落在最后一个参数上，
     *    放中间调用方就写不成 `diffDeletedAll(archived) { ... }`（编译报
     *    "No value passed for parameter 'live'"）。函数类型参数放最后也是 Kotlin 惯例。
     */
    fun diffDeletedAll(
        archived: Map<Long, Set<Long>>,
        trustEmpty: Boolean = false,
        live: (Long) -> Set<Long>?,
    ): Map<Long, DeletedDiff> {
        if (archived.isEmpty()) return emptyMap()
        val out = HashMap<Long, DeletedDiff>(archived.size)
        for ((snsId, svrs) in archived) {
            // ⚠️ live 是外部注入的（真机上要查库），抛了就按「没查成」处理 → 漏标而不是崩
            out[snsId] = diffDeleted(svrs, runCatching { live(snsId) }.getOrNull(), trustEmpty)
        }
        return out
    }

    /**
     * 形态 B（正文被清空）的备用入口：**行还在**时用 [classify] 逐条过一遍。
     *
     * 现在主判据是 [diffDeleted]（差集：归档有、表里查不到 ⇒ 物理删除）。
     * 这个入口留给「行还在但正文被抹掉」的场景 —— 静态取证已确认**删除走物理 DELETE**，
     * 但微信可能在某些路径下只清正文（例如服务端只下发清空），届时换成先走这个再取差集。
     * 放在这里是为了让「换判据」有现成的落点。
     */
    fun deletedByState(archived: Map<Long, CommentState>): Set<Long> {
        val out = HashSet<Long>()
        for ((svr, st) in archived) {
            if (isDeleted(st)) out.add(svr)
        }
        return out
    }

    // ------------------------------------------------------------------ 打标：幂等 + 文案拼接

    /**
     * 打标去重账本：同一条评论 `(snsID, commentSvrID)` **只放行一次**。
     *
     * 朋友圈评论行会反复重绘（滑动 / 下拉刷新 / ViewHolder 复用），不打这个账本
     * 就会出现 `[已删除][已删除][已删除]`。
     *
     * ⚠️ 只记 `(snsID, commentSvrID)` 两个 Long 拼成的字符串，不持有任何 View / 上下文。
     */
    class TagLedger {
        private val done = HashSet<String>()

        /** @return true = 第一次（允许打标，并记账）；false = 已经打过了 */
        fun firstTime(snsId: Long, svrId: Long): Boolean = done.add(key(snsId, svrId))

        fun firstTime(id: Id): Boolean = firstTime(id.snsId, id.svrId)

        /** 只查不记 */
        fun marked(id: Id): Boolean = key(id) in done

        fun clear() = done.clear()

        val size: Int get() = done.size
    }

    /** 当前文案里已经带过 `[已删除]` 了吗（幂等第二道闸：View 被复用 / 重绘时不重复拼） */
    fun alreadyLabeled(text: String?): Boolean = text != null && text.contains(LABEL_DELETED)

    /** 一次打标请求 */
    data class TagRequest(
        val id: Id,
        val form: DelForm,
        /** 该评论行**当前**的文案（用于幂等判断）；null = 没拿到，无法判断有没有打过 */
        val existing: String? = null,
        val nick: String? = null,
        /**
         * 正文对账结果：`true` = 归档正文跟屏幕上这条对上了；`false` = **明确对不上**；
         * `null` = 没得对（归档里没存下正文 / 屏幕上取不到文案）。
         *
         * ⚠️ `false` 一律不打标 —— 定位错了就会把 `[已删除]` 打到**别人的正常评论**上（事故）。
         * ⚠️ `null` 放行：差集判定本身已经够硬（归档里确实拦下过这条的删除），
         *    只是没法再做一次正文对账而已。
         */
        val match: Boolean? = null,
        /** true = 用 `[已删除]` **替换**原文（需求要的就是这个：显示标签，不留残留内容） */
        val replace: Boolean = true,
    )

    /**
     * 打标总闸门：返回这一行**最终要显示的文案**；**null = 不打标**（调用方必须按 null 处理，
     * 不能当空串塞进 UI）。
     *
     * 不打标的情形（全部是「宁可漏标」，没有一条会误伤正常评论）：
     * 1. [DelForm.ALIVE] —— 就是一条正常评论
     * 2. [TagRequest.match] == false —— 定位不可信，怕打到别人身上
     * 3. 已经打过（账本命中 / 文案里已经有 `[已删除]`）—— 幂等
     * 4. [labelText] 返回 null（只有 ALIVE 会，已在 1 拦掉）
     */
    fun tagText(req: TagRequest, ledger: TagLedger? = null): String? {
        if (req.form == DelForm.ALIVE) return null                 // ① 正常评论
        if (req.match == false) return null                        // ② 定位不可信
        if (alreadyLabeled(req.existing)) return null              // ③-a 打过了
        if (ledger != null && !ledger.firstTime(req.id)) return null // ③-b 打过了
        val label = labelText(req.form, req.nick) ?: return null
        if (req.replace) return label                              // 替换：标签即全文
        val base = req.existing?.trim()?.takeIf { it.isNotEmpty() } ?: return label
        // 追加模式：朋友圈评论行的原文本来就长「昵称：内容」，昵称前缀已经在 existing 里了。
        // 直接拼 [labelText] 会变成「小明：原文 小明：[已删除]」—— 昵称重复一遍，没法看。
        val tail = if (startsWithNick(base, req.nick)) LABEL_DELETED else label
        return "$base $tail"                                       // 追加模式
    }

    /**
     * `base` 开头是不是已经是「昵称 + 冒号」了（追加模式下决定要不要再拼一遍昵称）。
     *
     * 半角 / 全角冒号都认；昵称缺失、全空白、或压根对不上 → false（保守：走原来的完整标签）。
     */
    private fun startsWithNick(base: String, nick: String?): Boolean {
        val n = nick?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (!base.startsWith(n)) return false
        val rest = base.drop(n.length)
        val c = rest.firstOrNull() ?: return true   // base 就是个光秃秃的昵称
        return c == '：' || c == ':'
    }

    // ------------------------------------------------------------------ 执行层接线（纯判据）
    //
    // 这一节全是**给 hook 用的接线件**，本身不碰 View / DB / 线程：
    // 1. [normalizeArchived]  归档输出 → [diffDeletedAll] 的输入（形状本来就一致，只做清洗）
    // 2. [parseRow]           整行文案 → (昵称, 原文)，认不出返回 null
    // 3. [planTags]           差集 + 当前行 + 已打标集合 → List<TagOp>（可测、可回放、幂等）

    /**
     * 归档输出 → [diffDeletedAll] 的输入。
     *
     * `SnsCommentGuard.archivedIds()` 的真实形状就是 `Map<Long, Set<Long>>`
     * （key = `snsID`，value = 被拦下的 `commentSvrID` 集合，主键 `PRIMARY KEY(snsID, commentSvrID)`），
     * **跟 [diffDeletedAll] 的入参完全一致，不需要换形状**。这里只做两件清洗：
     *
     * 1. 丢掉 `commentSvrID <= 0` 的条目 —— 0 / 负数不是有效服务端 id，留着会让
     *    [key] 撞车（一批没同步的评论都记成 `snsId:0`），直接把正常评论打上标签。
     * 2. 丢掉「清洗后为空」的 snsID，免得下游对一个空组跑差集、白占额度。
     *
     * 保守方向：只删不加。调用方拿到的永远是真子集。
     */
    fun normalizeArchived(raw: Map<Long, Set<Long>>?): Map<Long, Set<Long>> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<Long, Set<Long>>(raw.size)
        for ((snsId, svrs) in raw) {
            if (svrs.isEmpty()) continue
            val keep = svrs.filterTo(LinkedHashSet<Long>(svrs.size)) { it > 0L }
            if (keep.isNotEmpty()) out[snsId] = keep
        }
        return out
    }

    /** 从整行文案里拆出来的「昵称 + 原文」（拆得出来就一定两个都有，否则返回 null） */
    data class RowText(val nick: String, val body: String)

    /** 昵称最长认多少字符（朋友圈昵称远不到这个长度；超了说明认错了分隔符） */
    private const val MAX_NICK = 32

    /**
     * 朋友圈评论行文案 → `(昵称, 原文)`。
     *
     * 真机上评论行是 `昵称：内容`，但**没有完整取证**（可能有表情前缀、可能整行只有内容），
     * 所以这里**一律保守**：认不出就返回 null，调用方按「跳过打标」处理 —— 宁可漏标。
     *
     * 认不出的情形：
     * - null / 全空白
     * - 已经带 `[已删除]`（没什么可拆的，且必然已经打过了）
     * - 找不到分隔冒号（半角 / 全角都认）
     * - 分隔符位置 > [MAX_NICK]，或分隔符前是空的
     * - [nickHint] 非空且跟分隔符前的部分**对不上** —— 这是最强的一道闸：
     *   定位到的行如果连昵称都不是同一个人，绝对不能把标签打上去
     *
     * @param nickHint 已知昵称（比如归档里的 `talker` 对应到的昵称）；null = 没有先验，只按冒号拆
     */
    fun parseRow(text: String?, nickHint: String? = null): RowText? {
        val raw = text ?: return null
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.contains(LABEL_DELETED)) return null

        val at = t.indexOfFirst { it == '：' || it == ':' }
        if (at < 0 || at > MAX_NICK) return null

        val head = t.substring(0, at).trim()
        val body = t.substring(at + 1).trim()
        if (head.isEmpty() || body.isEmpty()) return null

        val hint = nickHint?.trim()?.takeIf { it.isNotEmpty() }
        if (hint != null && !nickMatches(head, hint)) return null
        // hint 对得上时，昵称按 hint 归一（去掉可能的表情前缀），否则用屏幕上拆出来的
        return RowText(nick = hint ?: head, body = body)
    }

    /** [parseRow] 的简写：只要原文那部分 */
    fun splitOriginal(text: String?, nickHint: String? = null): String? =
        parseRow(text, nickHint)?.body

    /**
     * 分隔符前的部分是不是这个昵称。
     *
     * 允许「表情/符号前缀 + 昵称」（真机上有人昵称带 emoji），但**前缀里不许有字母或数字**：
     * `🌹小明` 认，`不是小明的谁：` 不认。多认一个非字母前缀只会多一分误标风险。
     */
    private fun nickMatches(head: String, nick: String): Boolean {
        if (head == nick) return true
        if (!head.endsWith(nick)) return false
        val lead = head.dropLast(nick.length)
        return lead.isNotEmpty() && lead.none { it.isLetterOrDigit() }
    }

    /** 一条打标计划的结果 */
    enum class TagAction {
        /** 可以改文案 */
        APPLY,

        /** 不动（幂等命中 / 证据不足 / 认不出） */
        SKIP,
    }

    /**
     * 一条打标操作。
     *
     * @param text   [TagAction.APPLY] 时这一行**最终要显示**的文案；SKIP 时为 null
     * @param reason SKIP 的原因（`already-tagged` / `already-labeled` / `dup` / `unparsed` /
     *               `alive` / `gate`），只进日志，不参与判断
     */
    data class TagOp(
        val id: Id,
        val action: TagAction,
        val text: String? = null,
        val reason: String = "",
    ) {
        val key: String get() = SnsCommentLogic.key(id)
    }

    /** 计划里的一条候选行（一行 = 屏幕上的一次 bind） */
    data class TagRow(
        val id: Id,
        /** 这一行**当前**的整行文案；null = 取不到（→ 认不出 → 跳过） */
        val existing: String? = null,
        val nick: String? = null,
        /**
         * 该条评论的形态。默认 [DelForm.MISSING]（= 差集判出来的那一支）；
         * 阶段 0 取证回来若确认是「行还在但正文清空 / 置 flag」，由调用方按 [classify] 结果传。
         */
        val form: DelForm = DelForm.MISSING,
    )

    /** [planTags] 产出的、真正要落地的 key 集合（喂给下一次 [planTags] 的 `tagged` 即天然幂等） */
    fun appliedKeys(ops: List<TagOp>): Set<String> =
        ops.filter { it.action == TagAction.APPLY }.mapTo(LinkedHashSet<String>()) { it.key }

    /**
     * 打标计划：**纯函数**，不读库、不改 View、不改任何状态 —— 同样的输入永远同样的输出，
     * 单测可以整批复放。
     *
     * 只对 `diff.deleted` 里的 `commentSvrID` 产生候选（`kept` / `unknown` 一条都不碰），
     * 且在以下任一情况**降级为 SKIP**：
     * 1. 这批里已经打过（`tagged` 命中，或文案里已有 `[已删除]`）
     * 2. 同一批里同一个 id 出现第二次（RecyclerView 复用 → 只认第一次）
     * 3. [TagRow.form] 是 [DelForm.ALIVE]（差集说是删了、状态说还活着 → 信后者，不标）
     * 4. [parseRow] 认不出「昵称：原文」（宁可漏标）
     * 5. [tagText] 总闸门拒绝（理论上到不了，兜底）
     *
     * 输出按 [key] 排序 → 回放顺序稳定。
     *
     * @param diff   某个 snsID 的差集结果（`diffDeletedAll(...)[snsId]`）
     * @param rows   当前这批要处理的行（可以有同一 id 的多行）
     * @param tagged 已知「已经打过标」的 key 集合（[key] 形状，跨批持久化由调用方负责）
     * @param trustRowNick 行上的 [TagRow.nick] 是否可用于强校验。默认 true；归档里没有昵称时传 false，
     *                     避免因为拿不到昵称就整批漏标
     */
    fun planTags(
        diff: DeletedDiff,
        rows: List<TagRow>,
        tagged: Set<String> = emptySet(),
        trustRowNick: Boolean = true,
    ): List<TagOp> {
        if (diff.deleted.isEmpty() || rows.isEmpty()) return emptyList()

        val out = ArrayList<TagOp>()
        val seen = HashSet<String>()
        for (row in rows.sortedBy { key(it.id) }) {
            if (row.id.svrId !in diff.deleted) continue
            val k = key(row.id)
            if (!seen.add(k)) {
                out += TagOp(row.id, TagAction.SKIP, reason = "dup")
                continue
            }
            if (k in tagged) {
                out += TagOp(row.id, TagAction.SKIP, reason = "already-tagged")
                continue
            }
            if (alreadyLabeled(row.existing)) {
                out += TagOp(row.id, TagAction.SKIP, reason = "already-labeled")
                continue
            }
            if (row.form == DelForm.ALIVE) {
                out += TagOp(row.id, TagAction.SKIP, reason = "alive")
                continue
            }
            val nickHint = if (trustRowNick) row.nick else null
            val rt = parseRow(row.existing, nickHint)
            if (rt == null) {
                out += TagOp(row.id, TagAction.SKIP, reason = "unparsed")
                continue
            }
            val text = tagText(
                TagRequest(
                    id = row.id,
                    form = row.form,
                    existing = row.existing,
                    nick = rt.nick,
                    replace = true,
                ),
            )
            if (text == null) out += TagOp(row.id, TagAction.SKIP, reason = "gate")
            else out += TagOp(row.id, TagAction.APPLY, text = text, reason = "delete-diff")
        }
        return out
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

    // ------------------------------------------------------------------ attrBuf 条目级差量（方案 A 安全写回前提）
    //
    // ⚠️⚠️ **attrBuf 的 protobuf 结构尚未取证**（取证清单 T4/5.4 未完成）：
    //   到底哪个字段号是「一条评论条目」、条目是 message 还是 string，**全都没有实证**。
    //
    //   所以这一组函数**默认一律保守**：
    //   - [ATTR_COMMENT_ENTRY_FIELD] == null（未取证）→ [attrEntryCount] 恒返回 null、
    //     [diffAttr] 的 safeToRestore 恒 false。生产代码调「单参版本」时永远走这条保守路径。
    //   - 只有显式传入一个**待验证的字段号**（真机取证后才允许）才会真正去数条目。
    //
    //   ⛔ 铁律：绝不能在这里「猜」微信内部格式。下面这套估算**只走 protobuf 通用线格式**
    //      （tag + wiretype + length），不假设任何微信私有语义；它数出来的结果**仅供取证比对**，
    //      未取证前**任何写回决策都必须走 planAttr 的字节数判据**，不得据此 RESTORE。

    /**
     * ⛔ **待取证**：attrBuf 里「一条评论条目」所在的**顶层重复字段号**。
     *
     * null = 未取证 → [attrEntryCount] 不估算、[diffAttr] 判不安全。
     * 取证（`op=sns sub=sample` + 字节解析）后填上字段号，两函数即生效，判据一行不用改。
     */
    val ATTR_COMMENT_ENTRY_FIELD: Int? = null

    /** 估算至少要 2 字节（1 字节 tag + 1 字节内容）；低于此值不可能是正常 protobuf */
    private const val MIN_ATTR_ESTIMATE_BYTES = 2

    /**
     * attrBuf 条目级差量结果。
     *
     * @param oldEntries 旧快照条目数；null = 无法估算
     * @param newEntries 当前 attrBuf 条目数；null = 无法估算
     * @param shrinkBy 条目减少数（= old - new，可能为负表示变多）；无法估算时为 **-1**
     * @param safeToRestore 是否**可以**把 old 整块写回。⚠️ 只有在两侧条目都能可靠估算、
     *        且 new 的条目**全部**能在 old 里找到（= 只少没变、没有新评论插入）时才 true。
     *        默认（字段号未取证）恒 false —— 写坏 attrBuf 会搞坏整条动态的评论列表。
     */
    data class AttrDiff(
        val oldEntries: Int?,
        val newEntries: Int?,
        val shrinkBy: Int,
        val safeToRestore: Boolean,
    )

    /** 读一个 protobuf varint；返回 (值, 下一个下标)；越界 / 超 64 位返回 null */
    private fun readVarint(b: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var i = start
        while (i < b.size && shift < 64) {
            val x = b[i].toInt() and 0xFF
            value = value or ((x and 0x7F).toLong() shl shift)
            i++
            if (x and 0x80 == 0) return value to i
            shift += 7
        }
        return null
    }

    /**
     * 走 protobuf **通用线格式**，收集顶层 `entryField`（wiretype=2，length-delimited）
     * 每次出现的那段原始字节。
     *
     * @return 条目字节列表；**无法可靠扫描**（结构断裂 / 出现 group / 字段号为 0）时返回 null
     *
     * ⚠️ 这不是解析微信格式：它只按 protobuf 规范跳字节，不解释任何字段含义。
     */
    private fun topLevelEntries(b: ByteArray, entryField: Int): List<ByteArray>? {
        if (entryField <= 0) return null
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < b.size) {
            val t = readVarint(b, i) ?: return null
            var pos = t.second
            val tag = t.first
            if (tag == 0L) return null // 字段号 0 非法
            val field = (tag ushr 3).toInt()
            when (tag and 0x07L) {
                0L -> pos = (readVarint(b, pos) ?: return null).second
                1L -> pos += 8                                   // fixed64
                2L -> {
                    val lv = readVarint(b, pos) ?: return null
                    pos = lv.second
                    val len = lv.first
                    if (len < 0 || len > (b.size - pos).toLong()) return null
                    val end = pos + len.toInt()
                    if (field == entryField) out += b.copyOfRange(pos, end)
                    pos = end
                }
                5L -> pos += 4                                   // fixed32
                else -> return null                              // 3/4 = group，不支持
            }
            if (pos > b.size) return null
            i = pos
        }
        return out
    }

    private fun entriesOrNull(blob: ByteArray?, entryField: Int): List<ByteArray>? {
        if (blob == null || blob.size < MIN_ATTR_ESTIMATE_BYTES) return null
        return topLevelEntries(blob, entryField)
    }

    /**
     * 估算 attrBuf 里的评论条目数。
     *
     * @param entryField 条目字段号；默认取 [ATTR_COMMENT_ENTRY_FIELD]（**当前为 null = 未取证**）
     * @return 条目数；**无法估算时一律 null**（字段号未取证 / blob 为空 / 过短 / 结构断裂 / 字段号非法）
     *
     * ⚠️ 返回 null 表示「宁可不写回」——调用方拿到 null 必须放弃写回，不得当成 0。
     */
    fun attrEntryCount(blob: ByteArray?, entryField: Int? = ATTR_COMMENT_ENTRY_FIELD): Int? {
        val f = entryField ?: return null
        return entriesOrNull(blob, f)?.size
    }

    /**
     * 条目级差量：判断能不能安全地把 [old] 整块写回替代 [new]。
     *
     * 规则（全部成立才 `safeToRestore=true`）：
     * 1. 字段号已指定（默认 null → 第一步就 false）；
     * 2. 两侧都能可靠估算条目数；
     * 3. 条目**减少**（old > new）；
     * 4. [new] 的每一条都能在 [old] 里找到（= 只少没变，没有新评论插进来）。
     *
     * ⚠️ 保守优先：字段号未取证时恒 false；任一侧 null/过短/断裂时恒 false。
     * 写坏 attrBuf 会把整条动态的可见评论列表搞坏，代价远大于「漏还原」。
     */
    fun diffAttr(
        old: ByteArray?,
        new: ByteArray?,
        entryField: Int? = ATTR_COMMENT_ENTRY_FIELD,
    ): AttrDiff {
        val f = entryField
        if (f == null) return AttrDiff(null, null, -1, false)
        val o = entriesOrNull(old, f) ?: return AttrDiff(null, null, -1, false)
        val n = entriesOrNull(new, f) ?: return AttrDiff(null, null, -1, false)
        val shrink = o.size - n.size
        val onlyShrunk = shrink >= 1 && isMultisetSubset(n, o)
        return AttrDiff(o.size, n.size, shrink, onlyShrunk)
    }

    /** [small] 的每一条（按出现次数计）都能在 [big] 里找到 —— 用 ISO-8859-1 串做字节 key */
    private fun isMultisetSubset(small: List<ByteArray>, big: List<ByteArray>): Boolean {
        if (small.isEmpty()) return true
        val pool = HashMap<String, Int>(big.size)
        for (e in big) {
            val k = String(e, Charsets.ISO_8859_1)
            pool[k] = (pool[k] ?: 0) + 1
        }
        for (e in small) {
            val k = String(e, Charsets.ISO_8859_1)
            val c = pool[k] ?: return false
            if (c <= 0) return false
            pool[k] = c - 1
        }
        return true
    }

    // ------------------------------------------------------------------ 方案 B：「归档有、界面无」判定

    /**
     * 方案 B 的降级判据：归档的正文候选里，哪些**没有**在界面现有文案里命中
     * （= 疑似被删 / 未渲染）。
     *
     * @param archivedCands 每条已删评论一个候选列表（`textCandidates(curActionBuf)` 的产物）
     * @param visibleTexts 界面 itemView 现有 TextView 文案
     * @return 未命中的候选组（保持入参顺序）；用 [textHit] 的**严格规则**（短碎片不认）
     *
     * 保守约定：
     * - 条目**抽不出候选**（空 List）→ 无法对账 → **不判定为被删**，跳过；
     * - [visibleTexts] 为空 → 界面**没有任何文案可对账**（评论可能折叠 / 未渲染）→
     *   一律返回空（全部「待确认」），**绝不当成"全都被删"**。
     *   （⚠️ 文档 6.3 说「返回集可带标志」，但本函数签名固定为 List<List<String>>，
     *    无法携带标志位，故用「直接不返回」表达"待确认"。）
     */
    fun missingComments(
        archivedCands: List<List<String>>,
        visibleTexts: List<String>,
    ): List<List<String>> {
        if (archivedCands.isEmpty()) return emptyList()
        if (visibleTexts.isEmpty()) return emptyList()
        val out = ArrayList<List<String>>()
        for (g in archivedCands) {
            if (g.isEmpty()) continue
            if (!textHit(g, visibleTexts)) out += g
        }
        return out
    }

    // ------------------------------------------------------------------ 定位增强（boolean → 下标）

    /**
     * 从 [cands] 定位到**唯一**命中的 [texts] 下标。
     *
     * @return 唯一命中行下标；**歧义（命中多行）返回 -1**、无命中 -1、短碎片不认（-1）
     *
     * 复用 [textHit] 的严格规则：宁可返回 -1（不打标）也不猜错行。
     */
    fun firstHitIndex(cands: List<String>, texts: List<String>): Int {
        if (cands.isEmpty() || texts.isEmpty()) return -1
        var first = -1
        var hits = 0
        for ((i, t) in texts.withIndex()) {
            if (textHit(cands, listOf(t))) {
                hits++
                if (first < 0) first = i
            }
        }
        return if (hits == 1) first else -1
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
