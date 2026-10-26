package com.lx.wxgroup.data

/**
 * 「防撤回语音后播放失败」的**纯判据**（零 Android / 零 Xposed 依赖 → 可直接跑 JVM 单测）。
 *
 * ## 根因（DEX 反汇编定案，见 `docs/_r21c_voice_sqlform.md` §1/§2）
 * 微信撤回语音时做两步（**顺序已实证，别写反**）：
 * 1. `UPDATE message SET ... WHERE msgId=?`（`j9.va`）—— 改写成撤回形态；
 * 2. 之后才 `executor.submit` 一个 `deleteLocalFile` runnable，异步执行
 *    `SQLiteDatabase.delete("voiceinfo", "FileName= ?", [imgPath])` —— 删语音下载记录。
 *
 * 我们的 [com.lx.wxgroup.feature.AntiRevoke] 只拦了第 1 步，第 2 步的 DELETE 没拦：
 * `message` 行保住了（气泡还在），但 `voiceinfo` 里那条下载记录没了，播放器按 `imgPath`
 * 去 `voiceinfo` 拿 `Status/TotalLen/NetOffset` 查不到 ⇒ 点了没反应。
 *
 * ⚠️ 两个**已证伪**的历史误判（别再写回去）：
 * - ~~撤回删 voiceinfo 走 `FileName IN (SELECT imgPath FROM message ...)` 子查询~~
 *   ⇒ 那条子查询属于 `deleteMsgByTalkerSync`，只被「删会话/清空记录」调用，与撤回无关。
 * - ~~DELETE 先于 UPDATE~~ ⇒ 反汇编里 DELETE 的 runnable 是在 UPDATE 之后才排程的。
 *
 * 本类只回答一个问题：**这一次 voiceinfo 的 DELETE 要不要拦下来**。
 *
 * ## ⛔ 不能无条件拦 voiceinfo 的 DELETE
 * voiceinfo 也用于微信正常语音清理（删过期语音、空间不足 GC）。无条件拦会让微信 GC 失效、
 * 存储只增不减 —— 那是比播放失败严重得多的问题。所以判据必须是「只拦命中已救语音的删除」，
 * 且只认 `=` / `IN` 两种**精确**形态，其余（`<` `>` `LIKE` `IS NULL` 等清理形态）一律放行。
 *
 * ## 匹配维度
 * voiceinfo 有两列能定位到具体语音：
 * - `FileName`   = message.imgPath（26 位 hex 的 VFS 虚拟路径，**不是文件系统路径**）
 * - `MsgLocalId` = message.msgId
 * 所以 `rescuedImgs`（调用方传入的受保护标识集合）同时装 imgPath hex 串 + msgId 十进制串，
 * 且约定统一为**小写**（msgId 是纯数字，lowercase 无副作用）。
 */
object VoiceGuardLogic {

    /** 只认这张表（大小写不敏感） */
    private const val TABLE = "voiceinfo"

    /**
     * imgPath 形态：**26 位 hex**。前后都要是「非 hex 字符」（边界断言），
     * 这样才能：① 25/27 位不误认；② 嵌在更长 hex 串（如 32 位 MD5）里的 26 位前缀不误认。
     *
     * ⚠️ team-lead 任务描述写「24 位」，但真机 8 个独立样本（6 条被救语音 + 2 条对照）实测
     * **全是 26 位 hex**（`docs/_r21c_voice_root.md` 里的 FileName / imgPath），24 是笔误。
     * 本实现按实测 26 位匹配；若后续真机发现 imgPath 长度可变，需改成范围匹配（见设计文档 §6）。
     */
    private val HEX = Regex("""(?<![0-9a-fA-F])[0-9a-fA-F]{26}(?![0-9a-fA-F])""")

    /**
     * 只认 `FileName` / `MsgLocalId` 两列的 `=` / `IN`。
     * ⚠️ `\b` 用在列名后与 `in` 前后：保证不误认 `FileNameX`、`index`、`into`；
     * 也保证 `!=` / `<` / `>` / `LIKE` / `IS NULL` 这些**清理形态**不命中。
     */
    private val EQ_OR_IN = Regex(
        """(?:filename|msglocalid)\b\s*(?:=|\bin\b)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 子查询形态（`IN (SELECT ...)`）一律放行。
     * ⚠️ 放行的理由是**「这条路径不该拦」**，不是「读不出值」：唯一用子查询删 voiceinfo 的
     * 是 `deleteMsgByTalkerSync`（删会话/清空记录），拦它会破坏微信 GC。
     * 撤回不走子查询（见类注释），所以这条不会误伤撤回场景。
     */
    private val SUBQUERY = Regex("""\bselect\b""", RegexOption.IGNORE_CASE)

    /**
     * 从字符串里抽出所有 26 位 hex 片段（imgPath 形态），统一**小写**返回。
     *
     * - 25 位 / 27 位等长度不对的不抽；
     * - 嵌在更长 hex 串里的 26 位前缀不抽；
     * - 大小写都认（真机是小写，防大写变体）。
     */
    fun hexTokensFrom(s: String?): List<String> {
        if (s.isNullOrEmpty()) return emptyList()
        return HEX.findAll(s).map { it.value.lowercase() }.toList()
    }

    // ------------------------------------------------------------ 缓存判据（纯函数，可单测）

    /**
     * 受保护集合的缓存**是否可命中**。
     *
     * 命中条件三条同时成立：
     * 1. `cachedSize > 0` —— ⛔ **空集永不算命中**。空集语义 =「当前什么都没保护」，
     *    缓存它的唯一作用只是省一次查询（很便宜），而唯一后果是**吃掉紧随其后的
     *    `record()` 写入** ⇒ 漏拦一条语音（代价最大）。
     * 2. `now - cachedAt < ttlMs` —— 没过期。
     * 3. `cachedGen == gen` —— ⛔ **构建之后没有新的 `record()`**。
     *
     * ## 为什么必须有第 3 条（`gen` 版本号）
     * 只有前两条时仍有一个**真实且常见**的漏洞：3 秒内连续撤回两条语音。
     * ```
     * t=0.0  撤回 A → record(A)
     * t=0.1  DELETE(A) → miss ⇒ 建 {A} ⇒ 非空 ⇒ 缓存到 t=3.1
     * t=1.0  撤回 B → record(B)                 ← 缓存已陈旧
     * t=1.1  DELETE(B) → 命中 t=0.1 的 {A} ⇒ B 不在里面 ⇒ 放行 ⇒ B 播放失败
     * ```
     * 连撤两条是常见动作，且复现的正是本 bug 本身。
     * ⇒ 用 `gen`（`record()` 时自增）把「缓存构建之后是否有新记录」变成可判条件：
     * 只要 `record()` 发生过，`gen` 变了，旧缓存立即不可用。
     *
     * @param cachedGen 缓存构建时记录的版本号
     * @param gen       当前版本号
     */
    fun cacheUsable(
        now: Long,
        cachedAt: Long,
        cachedSize: Int,
        ttlMs: Long,
        cachedGen: Long,
        gen: Long,
    ): Boolean = cachedSize > 0 && cachedGen == gen && now - cachedAt < ttlMs

    /**
     * 一次构建后 `cachedAt` 该记成什么。
     *
     * - 非空集 ⇒ 记 `now`（正常缓存 [ttlMs]）；
     * - **空集 ⇒ 记 `now - ttlMs`**（等价于「立即过期」），这样下一次调用必然 miss 并重新查库。
     *   ⚠️ 不用常量 `0L` 表示过期：设备刚启动 `uptime < ttlMs` 时 `now - 0` 会意外命中。
     *   （当前 `cachedSize > 0` 已能挡住空集，本函数是**双保险**：万一有人删掉那条短路，
     *   空集 stamp 仍恒为「已过期」。）
     */
    fun stampAfterBuild(now: Long, builtSize: Int, ttlMs: Long): Long =
        if (builtSize > 0) now else now - ttlMs

    /**
     * 这次 voiceinfo 的 DELETE 要不要拦。
     *
     * @param table        delete 的第 1 参（表名）
     * @param whereClause  delete 的第 2 参；`null` = 删全表（整体清理，绝不拦）
     * @param whereArgs    delete 的第 3 参；wcdb 有 String[]/Object[] 两个重载，调用方统一转 String[]
     * @param rescuedImgs  受保护标识集合（imgPath hex 串 + msgId 十进制串，小写），
     *                     调用方负责「只装最近 N 秒内刚 record 过的」这个时间窗
     *
     * @return true = 拦（调用方 `param.result` 伪装成功）；false = 放行
     */
    fun shouldBlock(
        table: String?,
        whereClause: String?,
        whereArgs: Array<String>?,
        rescuedImgs: Set<String>,
    ): Boolean {
        // 1. 只认 voiceinfo（热路径最便宜的判断最先做）
        if (table == null || !table.equals(TABLE, ignoreCase = true)) return false
        // 2. whereClause == null = delete("voiceinfo", null, null) 删全表 ⇒ 整体清理，不拦
        if (whereClause == null) return false
        // 3. 没有受保护标识 ⇒ 没什么可拦
        if (rescuedImgs.isEmpty()) return false
        // 4. 子查询形态一律放行。**这不是「拿不到值只好放弃」，是有意的正确性要求**：
        //    唯一用子查询删 voiceinfo 的是 deleteMsgByTalkerSync（删会话/清空记录），
        //    拦它会破坏微信的正常 GC（存储只增不减，比播放失败严重得多）。
        //    撤回走的是单行参数化 FileName= ?，不经过这里。
        if (SUBQUERY.containsMatchIn(whereClause)) return false
        // 5. 只认 FileName/MsgLocalId 的 = / IN；其余一律放行（清理形态）
        if (!EQ_OR_IN.containsMatchIn(whereClause)) return false

        // 6. 候选值：whereArgs 全量 + whereClause 里的内联 hex（非参数化形态）
        val protected = rescuedImgs.mapTo(HashSet(rescuedImgs.size)) { it.lowercase() }
        val candidates = HashSet<String>()
        if (whereArgs != null) {
            for (a in whereArgs) {
                if (a.isNullOrEmpty()) continue
                candidates.add(a.lowercase())
                candidates.addAll(hexTokensFrom(a))
            }
        }
        candidates.addAll(hexTokensFrom(whereClause))

        return candidates.any { it in protected }
    }
}
