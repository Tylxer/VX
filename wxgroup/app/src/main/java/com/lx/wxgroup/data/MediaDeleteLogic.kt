package com.lx.wxgroup.data

/**
 * 「撤回图片/视频/媒体索引表 DELETE 要不要拦」的**零 Android 依赖纯判据**（A/B 两条路线共用）。
 *
 * ## 本类不做什么（⛔ 边界）
 * - **不接线**：不被任何 feature 包下的类引用，不注册 hook、不读 [Kv]、不改 `param.result`、
 *   不写微信库、不改 ContentValues、不生成/拼接任何 SQL。落地即零行为变化。
 * - **不替代 [VoiceGuardLogic]**：白名单刻意**不含 `voiceinfo`**。语音仍由既有
 *   `VoiceGuard`（`VoiceGuard.kt:136` 已挂 wcdb `delete`）管辖，避免两个回调重复拦同一张表。
 * - 只解析 `delete(table, whereClause, whereArgs)` 的三个入参；`whereArgs` 按 `Array<out Any?>?`
 *   接收（wcdb 有 `String[]`/`Object[]` 两种重载，见 `VoiceGuard.kt:165-167`）。
 *
 * ## 为什么必须保守（⛔ 保守性红线，承自 `VoiceGuardLogic.kt:23-26,145-157`）
 * 媒体表同时服务微信正常清理（删过期媒体 / 空间不足 GC）。**无条件拦 delete 会让微信 GC 失效、
 * 存储只增不减**，比「图片打不开」严重得多。所以：
 * - `whereClause == null`（删全表）、`<` `>` `LIKE` `IS NULL` 清理形态、
 *   `IN (SELECT …)` 整会话删除 **一律放行**（[DeleteClass.BULK_OR_UNSAFE]）；
 * - 只认「单列精确 `=` + 键列命中 + 抽得出合法 token + 命中已保护集合」这一种
 *   [DeleteClass.BLOCKABLE]，且还要叠加撤回期窗口（[shouldBlock]）；
 * - **任何不确定一律返回 false（放行）**：键列未取证、占位符取不到值、token 抽不出、
 *   token 长度不对、窗口不成立 ⇒ [DeleteClass.UNKNOWN] / false。
 *
 * ## 两套 token 长度（⛔ 不能用一个正则）
 * `VoiceGuardLogic.kt:48` 语音 imgPath 是 **26 位 hex**；`MediaGuardLogic.kt:40-55` 图片 token 是
 * **32 位 md5**。`MediaGuardLogic.kt:31-36` 已证「长度随消息类型变是已证实的事实」。
 * 故 [extractTokens] 按表分派长度：26 位 token 命中不了图片表，32 位 token 命中不了语音表。
 *
 * ## 取证状态
 * - 表结构 / 删除形态：`docs/_r35_revoke_img_anchor.md:36-64,121-143`。
 * - ⛔ `WxFileIndex2` 的键列 **[需真机]**（`docs/_r49_revokeimg_reference.md:313` 明写「待定」）。
 *   本类只给候选键列，命中不了就 [DeleteClass.UNKNOWN] ⇒ 放行，不写成事实。
 * - ⛔ 撤回删媒体表究竟走 `delete()` / `execSQL` / 预编译语句 **[需真机]**，本判据不假定通道。
 */

/** 一次 DELETE 的**互斥**归因（四态，自上而下首个命中，不是并列布尔）。 */
enum class DeleteClass {
    /** 单条、键列命中、token 合法、（完整模式下）命中已保护集合 —— 唯一可拦形态。 */
    BLOCKABLE,

    /** 表命中但为删全表 / 清理 / 批量 / 子查询 / 多值形态 —— 保守性红线，必须放行。 */
    BULK_OR_UNSAFE,

    /** 非白名单表（含 `voiceinfo`、`null`）—— 交给既有 `VoiceGuard`，本 lane 不管。 */
    OTHER_TABLE,

    /** 表命中、形状像单条，但键列未取证 / 值缺失 / token 抽不出 / 未命中保护集 —— 放行。 */
    UNKNOWN,
}

/** 阶段 0 真机归因的**互斥**三态（对应「压根没删 / 挂对但判据不识别 / 有真实删除」）。 */
enum class Verdict {
    NO_EVENT,
    HOOK_OK_BUT_NOT_MATCHED,
    REAL_EVENT,
}

object MediaDeleteLogic {

    /** 13 位 epoch 毫秒的最低量级（≈2001-09-09）；10 位 Unix 秒均低于此值。 */
    const val EPOCH_MS_MIN = 1_000_000_000_000L

    /** ⛔ 不含 `voiceinfo`：语音由既有 `VoiceGuard` 管，避免两个回调重复拦。 */
    private val TARGET_TABLES = setOf("imginfo2", "videoinfo2", "wxfileindex2")

    private data class TableSpec(val keyColumns: Set<String>, val tokenLen: Int)

    /** 语音口径：仅用于 [extractTokens] 表达「32 位不能命中语音表」；[isTargetTable] 恒 false。 */
    private val VOICE_SPEC = TableSpec(
        keyColumns = setOf("filename", "msglocalid"),
        tokenLen = 26,
    )

    /** `ImgInfo2` 列：`docs/_r35_revoke_img_anchor.md:41-48` DDL + 任务给定 8 列。
     * ⛔ `status/iscomplete/hashdthumb` 是标量列、值非 hex token，故意不列入键列。 */
    private val IMG_SPEC = TableSpec(
        keyColumns = setOf(
            "id", "msgsvrid", "msglocalid", "bigimgpath", "thumbimgpath",
            "origimgmd5", "midimgpath", "hevcpath", "originsourcemd5",
        ),
        tokenLen = 32,
    )

    private val VIDEO_SPEC = TableSpec(
        keyColumns = setOf("filename", "msglocalid", "msgid", "msgsvrid"),
        tokenLen = 32,
    )

    /** ⛔ 键列 [需真机]：`docs/_r49_revokeimg_reference.md:313`「`WxFileIndex2` 待定」。
     * 给候选集只为让「抽不出 token ⇒ [DeleteClass.UNKNOWN] ⇒ 放行」这条保守路径可表达。 */
    private val WXFILE_SPEC = TableSpec(
        keyColumns = setOf("msgid", "msglocalid", "filename", "msgsvrid"),
        tokenLen = 32,
    )

    /**
     * 清理/批量/子查询形态。`\b` 防把列名里的 `in`/`or` 片段误判；`,`/`;` 防多值与多语句。
     * ⛔ 有意收紧：`IN` 字面量列表（如 `IN (?,?)`）也归 [DeleteClass.BULK_OR_UNSAFE]，
     * 只认单个 `=`（`docs/_r35_revoke_img_anchor.md:126-135` 已知删除全是 `IN (SELECT…)`，
     * 单条走泛型 `delete from %s where %s=%s` `:132`）。
     */
    private val UNSAFE = Regex(
        """(?i)\bselect\b|\blike\b|\bis\s+(not\s+)?null\b|\bin\b|!=|<>|<=|>=|<|>|\band\b|\bor\b|,|;""",
    )

    /**
     * 单列精确 `=`。配合 `matchEntire` 隐含全匹配，故正则内不写 `^`/`$`（避开 Kotlin 字符串
     * 模板对 `$` 的转义）。group1 = 列名（可选引号/方括号包裹），group2 = 右值。
     */
    private val SINGLE_EQ = Regex(
        """\s*[\["'`]?([A-Za-z_][A-Za-z0-9_]*)[\]"'`]?\s*=\s*(.+?)\s*""",
    )

    private fun normalizeTable(table: String?): String? {
        if (table == null) return null
        val t = table.trim().trim('"', '`', '[', ']', '\'')
        return t.lowercase().ifEmpty { null }
    }

    private fun specFor(normalized: String?): TableSpec? = when (normalized) {
        "voiceinfo" -> VOICE_SPEC
        "imginfo2" -> IMG_SPEC
        "videoinfo2" -> VIDEO_SPEC
        "wxfileindex2" -> WXFILE_SPEC
        else -> null
    }

    /**
     * 抽取「恰好 [len] 位、前后均非 hex」的段，小写归一。
     * 边界断言是关键：`VoiceGuardLogic.kt:48` 同款，25/27/31/33/34 位、以及嵌在更长 hex
     * 串里的前缀都抽不出。用普通字符串（非 `"""`）因为 `{len}` 需要 Kotlin 模板插值。
     */
    private fun collectHex(s: String, len: Int, out: MutableSet<String>) {
        val re = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{$len}(?![0-9a-fA-F])")
        for (m in re.findAll(s)) out.add(m.value.lowercase())
    }

    // ------------------------------------------------------------------ J1 六个可单测纯函数

    /**
     * 表名是否属于本 lane 的媒体表白名单。
     * 归一化：去首尾空白 → 去 `"`, `` ` ``, `[`, `]`, `'` 引号变体 → 小写 → 精确命中集合。
     * ⛔ `voiceinfo` / `null` / 空串 / `ImgInfo2X` / `imgInfo2_backup` 一律 false。
     */
    fun isTargetTable(table: String?): Boolean {
        val t = normalizeTable(table) ?: return false
        return t in TARGET_TABLES
    }

    /**
     * 把一次 DELETE 归为 [DeleteClass] 四态之一（互斥）。
     *
     * @param protectedTokens 空集（默认）= **纯形状诊断模式**：只判形状，不要求命中保护集，
     *                        供 A/B 两路线在不查保护池时单独复用；非空集 = **完整判定模式**：
     *                        [DeleteClass.BLOCKABLE] 还要求抽出的 token 命中该集合。
     */
    fun classifyDelete(
        table: String?,
        whereClause: String?,
        whereArgs: Array<out Any?>?,
        protectedTokens: Set<String> = emptySet(),
        protectedRowIds: Set<String> = emptySet(),
    ): DeleteClass {
        // 1. 最便宜判断最先做：非白名单表（含 voiceinfo / null）⇒ 交给既有 VoiceGuard
        if (!isTargetTable(table)) return DeleteClass.OTHER_TABLE
        // 2. whereClause == null = delete("T", null, null) 删全表 ⇒ 整体清理，绝不拦
        val w = whereClause?.trim()
        if (w == null || w.isEmpty()) return DeleteClass.BULK_OR_UNSAFE
        // 3. ⛔ 保守性红线：子查询 / LIKE / IS NULL / 比较符 / IN / AND/OR / 多值 / 多语句全部放行
        if (UNSAFE.containsMatchIn(w)) return DeleteClass.BULK_OR_UNSAFE
        if ((whereArgs?.size ?: 0) > 1) return DeleteClass.BULK_OR_UNSAFE
        // 4. 表 spec（键列白名单 + token 长度）
        val spec = specFor(normalizeTable(table)) ?: return DeleteClass.UNKNOWN
        // 5. 只认单列精确 `=`；其余（无 `=`/多条件）一律 UNKNOWN
        val eq = SINGLE_EQ.matchEntire(w) ?: return DeleteClass.UNKNOWN
        val column = eq.groupValues[1].lowercase()
        if (column !in spec.keyColumns) return DeleteClass.UNKNOWN
        // 6. 占位符数量必须与 whereArgs 对齐，否则「拿不到值」⇒ 不确定 ⇒ 放行
        val placeholders = eq.groupValues[2].count { it == '?' }
        if (placeholders > 0 && (whereArgs?.size ?: 0) != placeholders) return DeleteClass.UNKNOWN
        // 7. ⭐ R52 id 通道（甲路线）：数值主键单条 `=` 删除，且候选行 id 命中反查快照
        //    ⇒ 可拦。⛔ 放在全部保守闸门（:163-180）之后、token 抽取（:181）之前：
        //    既不绕过任何 UNSAFE 判据，也不改动下方 token 通道逐字逻辑。
        if (isNumericKeyDelete(table, w) &&
            idMatches(extractRowIds(table, w, whereArgs), protectedRowIds)
        ) {
            return DeleteClass.BLOCKABLE
        }
        // 8. 抽不出合法 token（含 26/32 长度错配、非法字符、null args）⇒ 放行
        val tokens = extractTokens(table, w, whereArgs)
        if (tokens.isEmpty()) return DeleteClass.UNKNOWN
        // 9. 完整模式：未命中已保护集合 ⇒ 放行
        if (protectedTokens.isNotEmpty()) {
            val normalized = protectedTokens.mapTo(HashSet(protectedTokens.size)) { it.lowercase() }
            if (tokens.none { it in normalized }) return DeleteClass.UNKNOWN
        }
        return DeleteClass.BLOCKABLE
    }

    /**
     * 从 `whereArgs` **与** `whereClause` 内联字面量双通道抽 token，按表分派 26/32 位长度。
     *
     * ⛔ 只做**正则解析**，不生成/拼接任何 SQL（无注入面）。`whereArgs` 里的 `null` / 空串 /
     * 非 hex 串静默跳过，不抛异常。表无 spec（`message` 等）返回空集。
     * 26 位 hex 在图片表（32 位口径）抽不出；32 位 hex 在 `voiceinfo`（26 位口径）抽不出。
     */
    fun extractTokens(
        table: String?,
        whereClause: String?,
        whereArgs: Array<out Any?>?,
    ): Set<String> {
        val spec = specFor(normalizeTable(table)) ?: return emptySet()
        val out = LinkedHashSet<String>()
        if (whereArgs != null) {
            for (a in whereArgs) {
                val s = a?.toString() ?: continue
                collectHex(s, spec.tokenLen, out)
            }
        }
        if (whereClause != null) collectHex(whereClause, spec.tokenLen, out)
        return out
    }

    // ------------------------------------------------------------------ R52 数值主键 id 通道（甲路线）

    /**
     * ⛔ 刻意**不含** `bigimgpath` / `thumbimgpath` 等路径列：它们走 md5 token 通道，
     * 不能混入数值比对（否则 32 位 hex 被当数字串，`:24-27` 两套 token 长度口径被破坏）。
     */
    private val NUMERIC_KEY_COLUMNS = setOf("id", "msglocalid", "msgsvrid", "msgid")

    /** 去掉右值外层成对引号/方括号（`'x'` / `"x"` / `` `x` `` / `[x]`），供内联数值右值归一。 */
    private fun stripQuotes(rhs: String): String {
        val t = rhs.trim()
        if (t.length < 2) return t
        val paired = (t[0] == '\'' && t.last() == '\'') ||
            (t[0] == '"' && t.last() == '"') ||
            (t[0] == '`' && t.last() == '`') ||
            (t[0] == '[' && t.last() == ']')
        return if (paired) t.substring(1, t.length - 1).trim() else t
    }

    /**
     * 把 `whereArgs` 单元素归一成**十进制数字串**。
     *
     * - 接受 `Long` / `Int` / `String`（真机 `Object[]` 里是数值类型，决定性证据那条）；
     * - 去首尾空白、去前导 `+`；
     * - 拒绝负数 / 小数 / 空 / 非数字 / `null` / 任何不确定 ⇒ `null`；
     * - ⛔ **不做 `toLong()`**：`Long.MAX_VALUE` 甚至更长的数字串都按原串返回，防溢出/异常。
     */
    fun normalizeId(v: Any?): String? {
        val s = v?.toString()?.trim() ?: return null
        val digits = if (s.startsWith("+")) s.substring(1) else s
        if (digits.isEmpty()) return null
        if (!digits.all { it in '0'..'9' }) return null
        return digits
    }

    /**
     * 是否「数值主键单条 `=` 删除」**形状**（决定走 id 通道还是 token 通道）。
     *
     * 复用与 [classifyDelete] 同源的闸门：[isTargetTable] / [UNSAFE] / [SINGLE_EQ] /
     * [NUMERIC_KEY_COLUMNS]。`whereClause == null` / 空 / 批量 / 比较符 / LIKE / IN /
     * 多条件 / 子查询 / 非数值键列 ⇒ false。⛔ 签名无 `whereArgs`，不解析它。
     */
    fun isNumericKeyDelete(table: String?, whereClause: String?): Boolean {
        if (!isTargetTable(table)) return false
        val w = whereClause?.trim() ?: return false
        if (w.isEmpty()) return false
        if (UNSAFE.containsMatchIn(w)) return false
        val eq = SINGLE_EQ.matchEntire(w) ?: return false
        return eq.groupValues[1].lowercase() in NUMERIC_KEY_COLUMNS
    }

    /**
     * 抽候选行 id（**双通道**：`whereArgs` 归一 **与** `whereClause` 内联数值右值），
     * 仅当「白名单表 + 单列精确 `=` + 列 ∈ [NUMERIC_KEY_COLUMNS]」时才有输出，否则空集。
     *
     * 保守闸门与 [classifyDelete] `:163-180` 同源：`whereClause == null` / 空 /
     * [UNSAFE] 形态（`<` `>` `LIKE` `IS NULL` `IN` 比较符 多条件 多语句 子查询）/
     * `whereArgs.size > 1` / 占位符与 args 数量不齐 ⇒ 空集（⛔ 不许「取第一个」）。
     * `execSQL(String sql)` 无 bindArgs 的内联形态 `id=875556` 由通道 2 覆盖。
     */
    fun extractRowIds(
        table: String?,
        whereClause: String?,
        whereArgs: Array<out Any?>?,
    ): Set<String> {
        if (!isTargetTable(table)) return emptySet()
        val w = whereClause?.trim() ?: return emptySet()
        if (w.isEmpty()) return emptySet()
        if (UNSAFE.containsMatchIn(w)) return emptySet()
        if ((whereArgs?.size ?: 0) > 1) return emptySet()
        val eq = SINGLE_EQ.matchEntire(w) ?: return emptySet()
        if (eq.groupValues[1].lowercase() !in NUMERIC_KEY_COLUMNS) return emptySet()
        val placeholders = eq.groupValues[2].count { it == '?' }
        if (placeholders > 0 && (whereArgs?.size ?: 0) != placeholders) return emptySet()

        val out = LinkedHashSet<String>()
        // 通道 1：whereArgs 单元素归一（null / 非数字静默跳过）
        if (whereArgs != null) {
            for (a in whereArgs) {
                val id = normalizeId(a) ?: continue
                out.add(id)
            }
        }
        // 通道 2：whereClause 内联数值右值（id=875556 / id='875556'）；`id=?` 归一为 null 不会误加
        val inline = normalizeId(stripQuotes(eq.groupValues[2]))
        if (inline != null) out.add(inline)
        return out
    }

    /** 候选行 id 与反查快照是否有交集；⛔ 任一为空 ⇒ false（不确定即放行）。 */
    fun idMatches(candidateIds: Set<String>, protectedRowIds: Set<String>): Boolean {
        if (candidateIds.isEmpty() || protectedRowIds.isEmpty()) return false
        return candidateIds.any { it in protectedRowIds }
    }

    /** 是否像 13 位 epoch 毫秒（≥ [EPOCH_MS_MIN]）；10 位 Unix 秒 / 相对小时间均为 false。 */
    fun looksLikeEpochMillis(v: Long): Boolean = v >= EPOCH_MS_MIN

    /**
     * 撤回期窗口：半开区间 `0 <= nowMs - tokenIssuedMs < ttlMs`。
     *
     * - `ttlMs <= 0` / `nowMs < tokenIssuedMs`（时钟回拨）⇒ false；
     * - **等于 TTL 不算在窗口内**（严格 `<`，沿用 `VoiceGuardLogic.kt:114`）；
     * - 13 位 ms 与 10 位 s 混用（[looksLikeEpochMillis] 不一致）⇒ false，防静默延长窗口；
     * - 减法溢出 ⇒ false。任何不确定一律 false。
     */
    fun isRevocationWindow(nowMs: Long, tokenIssuedMs: Long, ttlMs: Long): Boolean {
        if (ttlMs <= 0L) return false
        if (nowMs < tokenIssuedMs) return false
        if (looksLikeEpochMillis(nowMs) != looksLikeEpochMillis(tokenIssuedMs)) return false
        val delta = nowMs - tokenIssuedMs
        if (delta < 0L) return false // Long 溢出兜底
        return delta < ttlMs
    }

    /**
     * 组合入口：唯一「要不要拦」的出口。
     * `isTargetTable && classifyDelete(...)==BLOCKABLE && token∈protectedTokens && isRevocationWindow`；
     * ⭐ R52 起另有**独立**的 id 通道：`classifyDelete(...)` 判到数值主键命中 `protectedRowIds`
     * 时直接成立（token 通道逐字保留，两条通道互不串味）。
     * ⛔ 任何不确定一律 false（放行）：双保护集皆空 / 非白名单表 / 批量或未知形态 / 窗口外 /
     * token 抽不出 / token 未命中 / 候选 id 未命中。
     */
    fun shouldBlock(
        table: String?,
        whereClause: String?,
        whereArgs: Array<out Any?>?,
        protectedTokens: Set<String>,
        nowMs: Long,
        tokenIssuedMs: Long,
        ttlMs: Long,
        protectedRowIds: Set<String> = emptySet(),
    ): Boolean {
        if (!isTargetTable(table)) return false
        // ⭐ 双空集闸门：token 通道与 id 通道各自独立，两者皆空才短路（否则 id 通道被空
        //    protectedTokens 静默短路 ⇒ 单测看似绿、真机恒放行，见 C7 / M2）。
        if (protectedTokens.isEmpty() && protectedRowIds.isEmpty()) return false
        if (!isRevocationWindow(nowMs, tokenIssuedMs, ttlMs)) return false
        if (classifyDelete(table, whereClause, whereArgs, protectedTokens, protectedRowIds) != DeleteClass.BLOCKABLE) {
            return false
        }
        // ⭐ id 通道出口：classifyDelete 已确认候选行 id 命中 protectedRowIds；id 通道不产出
        //    26/32 位 token，下面的 token 复检对它恒为空，必须提前返回。
        //    ⛔ protectedRowIds 为空时此支恒 false ⇒ 原 token 通道逐字不变。
        if (protectedRowIds.isNotEmpty() &&
            idMatches(extractRowIds(table, whereClause, whereArgs), protectedRowIds)
        ) return true
        val tokens = extractTokens(table, whereClause, whereArgs)
        if (tokens.isEmpty()) return false
        val normalized = protectedTokens.mapTo(HashSet(protectedTokens.size)) { it.lowercase() }
        return tokens.any { it in normalized }
    }

    /**
     * 三态互斥归因（用 `when` 单值返回，⛔ 不写成并列布尔）：
     * `seen<=0` ⇒ [Verdict.NO_EVENT]；`seen>0,matched<=0` ⇒ [Verdict.HOOK_OK_BUT_NOT_MATCHED]；
     * 否则 ⇒ [Verdict.REAL_EVENT]。
     */
    fun verdict(seen: Long, matched: Long): Verdict = when {
        seen <= 0L -> Verdict.NO_EVENT
        matched <= 0L -> Verdict.HOOK_OK_BUT_NOT_MATCHED
        else -> Verdict.REAL_EVENT
    }
}
