package com.lx.wxgroup.feature

/**
 * R42 · 朋友圈「已删除」灰标（line 样式）的**纯判据**（零 Android / 零 Xposed 依赖 → 可跑 JVM 单测）。
 *
 * 存在的意义：这条判据写错的两个方向都是**静默**的 ——
 * - 该记的不记（时间线位丢失却没落台账）⇒ 好友删了动态却永远看不到灰标，要等真机复现；
 * - 不该记的记了（自己删的、或根本没丢时间线位）⇒ 正常动态被插上「已删除」灰标。
 * 抽到纯 JVM 里才能用单测守住，改判据先跑单测、真机只做最后一公里。
 *
 * ⚠️ 本文件**禁止 import 任何 android.* / xposed.* **，否则单测加载不了。
 *
 * ## 判据来源（不是新设计，是既定结论的纯函数化）
 * - 位语义 `bit1(2)` = 朋友圈时间线可见（[SnsBitLogic.BIT_TIMELINE]）；
 * - 「删除」在时间线上的表现 = 微信把 `sourceType` 的时间线位清掉（`10→8` / `2→0`）；
 * - 「排除自己」= `SnsInfo.userName == 当前登录 wxid`（`login_weixin_username`）。
 */
object SnsDeletedTagLogic {

    /** 灰标样式。line 是唯一实现样式；off 用于对照（不渲染）。 */
    enum class Style {
        /** 正文下方单独一行灰色小字「该动态已被作者删除」 */
        LINE,

        /** 关闭（对照用，不渲染灰标） */
        OFF,
    }

    /**
     * 探针 `op=snsdel sub=style --es val on|off` 的取值解析（持久化是布尔串）。
     *
     * 因为 `Keys.SNSDEL_STYLE` 登记为 `PrefKind.SWITCH`，库里存的是 `"true"`/`"false"`，
     * 所以这里吃布尔串：`"true"` / `"1"`（去空白、忽略大小写）⇒ [Style.LINE]；其余 ⇒ [Style.OFF]。
     * 同时**兼容** `"line"` / `"on"`（等同开），避免旧写法 `line` 或探针 `on` 被误关。
     *
     * ⚠️ 默认**开**：未设置 / 空 / 空白 ⇒ [Style.LINE]。这是「双兜底」——
     * 即使 PrefSpec 漏登记、Kv 读到空串，功能也**不会静默失效**
     * （灰标默认显示，宁可多标、不能装完等于没装）。
     */
    fun parseStyle(v: String?): Style {
        if (v.isNullOrBlank()) return Style.LINE
        return when (v.trim().lowercase()) {
            "true", "1", "line", "on" -> Style.LINE
            else -> Style.OFF
        }
    }

    /**
     * 这次 `sourceType` 写入是否**丢掉了时间线可见位**（bit1）。
     *
     * - `old` = 写库前库里的 `sourceType`（`null` = 查不到 ⇒ 证明不了是丢位，不算）；
     * - `new` = 微信这次**想写入**的值（写 0 路径是 0，降位路径是 2/8 等）。
     *
     * ⚠️ 只盯 bit1，不盯 bit3：`10→2` 丢的是相册位（bit3），时间线仍可见 ⇒ **不算**「删除」，
     * 不该记灰标。反过来 `10→8` / `2→0` 丢时间线位 ⇒ 算。
     */
    fun isTimelineLoss(old: Int?, new: Int): Boolean =
        old != null && (old and SnsBitLogic.BIT_TIMELINE) != 0 && (new and SnsBitLogic.BIT_TIMELINE) == 0

    /**
     * 作者是不是「自己」。
     *
     * ⚠️ `self` 为空（取不到登录 wxid）时恒 false —— 这时**不排除任何作者**（降级），
     * 由调用方在 `stat` 里明示 `self=?`，绝不静默。
     */
    fun isSelf(author: String?, self: String?): Boolean =
        self != null && self.isNotEmpty() && author != null && author == self

    /**
     * 这次「摘键成功」要不要落一笔「已删除」台账。
     *
     * 三个条件同时成立：
     * 1. 丢了时间线位（[isTimelineLoss]）；
     * 2. 作者不是自己（[isSelf] 为 false —— 含「取不到 self 的降级」）；
     *
     * ⚠️ 取不到 self 时 [isSelf] 恒 false ⇒ 落台账，但调用方必须在 `stat` 里报 `self=?`，
     * 让用户知道「排除自己」这一层失效了，而不是以为生效。
     */
    fun shouldRecord(old: Int?, new: Int, author: String?, self: String?): Boolean =
        isTimelineLoss(old, new) && !isSelf(author, self)

    // ------------------------------------------------------------------ 缓存判据（照抄 VoiceGuardLogic 模式）

    /**
     * 「已删清单」缓存是否可命中。三条同时成立（与 VoiceGuardLogic.cacheUsable 同模式）：
     * 1. `cachedSize > 0` —— 空集永不算命中，避免吃掉紧随其后的新记录；
     * 2. `now - cachedAt < ttlMs` —— 没过期；
     * 3. `cachedGen == gen` —— 构建之后没有新的 record/purge（gen 自增）。
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
     * 一次构建后 `cachedAt` 该记成什么：空集记成「立即过期」，下次必然重查。
     * （照抄 VoiceGuardLogic.stampAfterBuild，见其 KDoc 关于「不用 0 表示过期」的理由。）
     */
    fun stampAfterBuild(now: Long, builtSize: Int, ttlMs: Long): Long =
        if (builtSize > 0) now else now - ttlMs

    // ------------------------------------------------------------------ ImproveInfo 解析（沿用 SnsTag 旧版的既定正则）

    /** 解析结果：snsId 统一为**有符号 Long**（与 SQLite 里的位模式一致） */
    class Info(val snsId: Long, val name: String?)

    /**
     * `toString()` 是功能性格式：`ImproveInfo(name=贸易商陈健峰, snsID='15033729556253184615', …)`。
     * ⚠️ `snsID` 允许负号 —— 有符号写法也要能吃下。
     */
    private val ID_RE = Regex("""snsID\s*=\s*'?(-?\d{15,20})""")
    private val NAME_RE = Regex("""[^A-Za-z]name\s*=\s*'?([^,'\n]{1,32})""")

    fun parseInfo(s: String?): Info? {
        if (s == null) return null
        val m = ID_RE.find(s) ?: return null
        val id = parseSnsId(m.groupValues[1]) ?: return null
        val name = NAME_RE.find(s)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        return Info(id, name)
    }

    /**
     * 形状校验：必须是**纯数字**（允许负号）。这是最后一道防线 —— 取错字段时几乎不可能
     * 同时满足这个形状，「打错标签」就基本不会发生。
     */
    fun parseSnsId(raw: String): Long? {
        if (raw.isEmpty() || raw.length > 20) return null
        var i = 0
        if (raw[0] == '-') {
            i = 1
            if (raw.length == 1) return null
        }
        while (i < raw.length) {
            val ch = raw[i]
            if (ch < '0' || ch > '9') return null
            i++
        }
        return runCatching {
            if (raw[0] == '-') raw.toLong() else java.lang.Long.parseUnsignedLong(raw)
        }.getOrNull()
    }

    /**
     * 作者名字对账。**严格**：拿不到名字 / 读不到文字 / 名字太短 → 一律不采信。
     * 打错标签（把没删的标成已删除）比不打标签糟糕得多，宁可让 missed 涨。
     */
    fun nameOk(name: String?, texts: List<String>): Boolean {
        val n = name ?: return false
        if (n.length < 2) return false
        if (texts.isEmpty()) return false
        return texts.any { t ->
            t.length >= 2 && (t.contains(n) || (t.length >= 3 && n.contains(t)))
        }
    }

    // ------------------------------------------------------------------ 偏移对账（纯函数，供单测）

    /**
     * 从「偏移 → 是否通过护栏」的候选序列里，按传入顺序返回**第一个**通过护栏的偏移；
     * 全不通过 ⇒ null。⛔ 顺序即优先级，调用方按 `0, 1, -1, 2, -2` 传入。
     *
     * ⚠️ 生产锚定已改用 [anchorOffset]：本函数「逐格取第一个通过护栏」在同一个人两条相邻
     * 动态时会把 `d=0`（错位后指向相邻那条）当成自己那条 ⇒ 串位误报（2026-11-07）。
     * 这里保留它只作为「按优先级取第一个」的底层纯判据 + 原有 4 条守门单测，
     * `SnsDeletedTag.resolve()` 不再调用它。
     */
    fun pickOffset(candidates: List<Pair<Int, Boolean>>): Int? {
        for ((d, ok) in candidates) {
            if (ok) return d
        }
        return null
    }

    // ------------------------------------------------------------------ 窗口锚定 / 渲染决策（误报修复，纯函数，供单测）

    /**
     * 一次绑定的窗口锚定决策（修「相邻同名动态串位」）。
     *
     * - `passing` = 按 `0,1,-1,2,-2` 顺序排列的、通过身份护栏（[nameOk]）的偏移列表；
     * - `learned` = 已校准的「封面头导致的全局固定错位常数」（`null` = 尚未校准）。
     *
     * 返回这一格该用的偏移；`null` = 无法可靠锚定（保守，不打标）。
     *
     * 背景：封面头让 `onBindViewHolder` 的 `pos` 与 `getData()` 下标存在一个**全局固定**错位 O。
     * 旧逻辑用 [pickOffset] 逐格「取第一个通过护栏的偏移」，而 [nameOk] 只认作者名 ——
     * 同一好友连续两条动态时 `d=0`（错位后恰指向相邻那条）与 `d=O`（本条自己）都通过护栏，
     * `0` 排在前面 ⇒ 新发未删动态被锚到相邻「已删除」动态的 snsId ⇒ 查表命中 ⇒ 误挂灰标。
     *
     * 互斥三分支（顺序即优先级，不并列）：
     * 1. `learned` 非空且本次仍通过护栏 ⇒ 沿用 `learned`（同名相邻窗口里抵御 `d=0` 的假命中）；
     * 2. 否则 `passing` 恰好一个 ⇒ 采信它（非歧义，可拿来校准错位常数）；
     * 3. 否则（歧义且无 `learned`）⇒ `null`（漏标好过打错标）。
     */
    fun anchorOffset(passing: List<Int>, learned: Int?): Int? {
        if (learned != null && learned in passing) return learned
        return passing.singleOrNull()
    }

    /**
     * 一次绑定「是否触发 resweep 重扫」的纯判据（B1 冷启首屏灰标补标修复）。
     *
     * - `before` = 校准前的错位常数（`resolve()` 里读到的 `learned`，null = 尚未校准）；
     * - `after`  = 校准后的错位常数（`offsets[key]`；`d==0` 时校准块 `remove` ⇒ 仍为 null）。
     *
     * 只有「本次绑定把错位常数**首次**从 null 校准成**非零**值」才触发重扫，让本轮之前被
     * 保守放弃（HIDE）的同屏 item 有机会在 `resweep` 里重新 `resolve` 补标。
     * 其余（已校准 / 校准出 0 / 校准失败）都不触发 —— 避免无谓重绘，也避免 `resweep` 里
     * `resolve` 再触发 `resweep` 造成递归（真正的防重入由 `resweeping` 布尔兜底）。
     *
     * ⛔ `after != 0` 是刻意的：`d==0` 不缓存（校准块 `remove`），「把 0 当错位常数缓存」是
     * 旧误报放大器，绝不能因 `after=0` 就触发重扫扩散成新错标。
     */
    fun shouldResweep(before: Int?, after: Int?): Boolean =
        before == null && after != null && after != 0

    /** 一次绑定的渲染决策：要么插灰标（SHOW），要么把灰标摘掉（HIDE）。 */
    enum class MarkAction {
        /** 插灰标（命中已删清单） */
        SHOW,

        /** 摘灰标：调用方必须 removeMark，清掉复用 View 上的残留 */
        HIDE,
    }

    /**
     * 一次绑定的渲染决策（纯判据，互斥两分支，对着用户可见事实写）。
     *
     * 只有「样式开 + 解析出 snsId + snsId 非 0 + 命中已删清单」才 [MarkAction.SHOW]；
     * **其余所有情形一律 [MarkAction.HIDE]** —— 调用方对 HIDE 必须 `removeMark`，
     * 于是「解析失败 / 没命中 / 样式关 / snsId=0」这些复用负路径都会清掉遗留灰标，
     * 不会把上一条（已删动态）的灰标带到这一条（新发未删动态）上。
     */
    fun markDecision(styleOn: Boolean, resolved: Boolean, snsId: Long, inDeleted: Boolean): MarkAction =
        if (styleOn && resolved && snsId != 0L && inDeleted) MarkAction.SHOW else MarkAction.HIDE

    // ------------------------------------------------------------------ 渲染候选筛选（纯函数，供单测）

    /** `holder → itemView` 候选 View 的纯描述（零 Android 依赖，供 [pickItemViewIndex] 单测） */
    class ItemViewCandidate(
        val declaredInAndroidX: Boolean,
        val parentContainsRecyclerView: Boolean,
        val isViewGroup: Boolean,
        val area: Long,
    )

    /**
     * `holder → itemView` 候选链的筛选规则（纯函数）。
     *
     * 优先级（命中即返回下标，-1 = 无候选）：
     * 1. 声明在 `androidx.recyclerview.widget.*`（ViewHolder 本体）上的 View 字段；
     * 2. 父链含 RecyclerView 的 View；
     * 3. 面积最大的 ViewGroup；
     * 4. ⛔ 任意 ViewGroup（修复：宁取 ViewGroup，不取 ImageView 之类非容器 View）；
     * 5. 任意 View（最后兜底，可能仍是非容器，由调用方 `ensureMark` 记 warning 并跳过）。
     */
    fun pickItemViewIndex(cands: List<ItemViewCandidate>): Int {
        if (cands.isEmpty()) return -1
        cands.indexOfFirst { it.declaredInAndroidX }.let { if (it >= 0) return it }
        cands.indexOfFirst { it.parentContainsRecyclerView }.let { if (it >= 0) return it }
        cands.withIndex()
            .filter { it.value.isViewGroup }
            .maxByOrNull { it.value.area }
            ?.let { return it.index }
        cands.indexOfFirst { it.isViewGroup }.let { if (it >= 0) return it }
        return 0
    }

    /** itemView 抽象树节点（零 Android 依赖，供 [bodyTextPath] 单测）。`text != null` = 文本节点。 */
    class SnsNode(val text: String?, val children: List<SnsNode> = emptyList()) {
        /** 是否文本节点（由 TextView 映射而来） */
        val isText: Boolean get() = text != null

        /** 是否容器节点（由 ViewGroup 且有子节点映射而来） */
        val isContainer: Boolean get() = text == null && children.isNotEmpty()
    }

    /**
     * 找「正文 TextView」在 itemView 树中的路径（纯函数）。
     *
     * 规则（保守，对应朋友圈 item 实测结构）：
     * 1. 内容容器 = 根的第一个 ViewGroup 子节点（昵称是根的第 0 个裸 TextView 子节点，不是
     *    ViewGroup，被自然跳过）；
     * 2. 在内容容器子树里 DFS 找第一个非空文本节点；
     * 3. 返回「从根到该文本节点」的子节点下标路径；找不到返回 null（调用方退回加到根末尾）。
     *
     * 返回路径的最后一位是正文文本节点在父容器里的下标，前面的位逐层下钻；调用方据此拿到
     * 父容器，并在 `path.last() + 1` 处紧跟正文插入标记。
     */
    fun bodyTextPath(root: SnsNode): List<Int>? {
        val ci = root.children.indexOfFirst { it.isContainer }
        if (ci < 0) return null
        return dfsFirstText(root.children[ci], listOf(ci))
    }

    private fun dfsFirstText(n: SnsNode, path: List<Int>): List<Int>? {
        if (!n.text.isNullOrBlank()) return path
        n.children.forEachIndexed { i, c ->
            dfsFirstText(c, path + i)?.let { return it }
        }
        return null
    }
}
