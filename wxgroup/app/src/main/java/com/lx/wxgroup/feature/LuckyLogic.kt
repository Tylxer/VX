package com.lx.wxgroup.feature

import java.util.Collections

/**
 * 需求 6.3「自动拆红包」的**纯判据 / 决策引擎** —— 零 Android / Xposed 依赖，
 * JVM 单测覆盖（`LuckyLogicTest`）。
 *
 * ## 一、本轮边界（⚠️ 红线，改动前先读懂）
 *
 * 红包涉及**资金**，微信对这块的风控也是最严的，而本项目的铁律是**不猜微信 API**。
 * 开工前查过 `docs/取证结论.md`（105 行，全文 Grep 无 红包 / lucky / redpacket /
 * wxpay / hb 任一命中）——**红包域至今零锚点**。所以本引擎的定位是：
 *
 * 1. **只产出决策，不产生任何副作用**：不点击、不调用微信的领取方法、不反射任何
 *    微信类、不碰网络。调用方拿到 [Verdict] 之后能做的只有「记一行日志」。
 *    真要自动领取，必须先取证出锚点（类名 / 方法名 / 字段名），见文末 HOOK_TODO。
 * 2. **「微信长什么样」全部走配置**（[Cfg]），取证前配置项一律为空 —— 配置项为空时
 *    [classify] 恒返回 [Match.NONE]，[decide] 恒返回 [Verdict.Skip]。
 *    即：**没取证 = 绝不动作**，而不是拿猜出来的 type / 文案去误判。
 * 3. 与同包 `RedPacketLogic` 是**两层、互不引用**：
 *    `RedPacketLogic` 是 UI 层的按钮文案判定（领取界面已弹出之后），
 *    本类是**消息层**的识别与决策。两者都遵守同一个保守性原则：**误判比漏判更糟**。
 *
 * ## 二、决策怎么读（[Verdict] 四个分支）
 *
 * | 分支 | 含义 | 调用方该做什么 |
 * |---|---|---|
 * | [Verdict.Skip] | 不是红包 / 开关关 / 自己发的 / 不在名单范围 / 已处理过 | 什么都不做 |
 * | [Verdict.Wait] | 认定为候选红包，请在 `delayMs` 毫秒后**再问一次引擎** | 排一个延迟，**不做任何红包动作** |
 * | [Verdict.Observe] | 只命中**弱判据**（文案特征），够不上强判据 | 记一行日志，到此为止 |
 * | [Verdict.Manual] | 重试次数打满，或冷却期内且判定无法自动恢复 | 交回用户手动，模块不再插手 |
 *
 * ## 三、四条判据各自为什么这么定
 *
 * - **消息类型**([typeCodes])：微信 `message.type` 的高位是它自己加的标志位，比较低
 *   16 位即可 —— 这条是 `AntiRevoke` 在 8.0.48 上实证过的（type=268445458 → 低位
 *   10002），不是猜的。但**红包具体是哪个低位目前没有实证**，所以默认值留空。
 *   查到之后回填 `redpacket.type_codes`，[Match.HIT] 才可能产生。
 * - **文案特征**(Cfg.markers)：同一份没取证就填的表 = 猜，所以默认空。
 *   命中它只算 [Match.SUSPECT]（记录），**永远不够触发 Wait**。
 * - **延迟**([clampDelay])：下限 [MIN_DELAY_MS] 的目的是**防 0ms 秒抢** ——
 *   真 Marketing 到无可挑剔地拟人也有 200ms 以上的反应时间，0ms 是风控最容易识别的
 *   特征。上限 [MAX_DELAY_MS] 防止用户填出一个「红包早被抢完」的大数。
 * - **幂等 + 退避**([Ledger])：同一个红包只决策一次；失败后指数退避并设冷却。
 *   刻意只放内存：丢一轮只会让某个红包被多看一次，落盘反而留下难以解释的状态。
 */
object LuckyLogic {

    // ================================================================== 常量

    /** 延迟下限（ms）。低于它就是「机器秒抢」，是风控最爱的特征 */
    const val MIN_DELAY_MS = 200

    /** 延迟上限（ms）。再晚红包多半已经没了 */
    const val MAX_DELAY_MS = 10_000

    /** 默认延迟（ms）：人类点开红包再手贱点的量级 */
    const val DEFAULT_DELAY_MS = 800

    /** 默认最大决策次数：达到就交回用户手动 */
    const val DEFAULT_MAX_ATTEMPTS = 3

    /** 第 1 次失败后等 1s，之后翻倍 */
    const val BACKOFF_BASE_MS = 1_000L

    /** 退避封顶（ms） */
    const val BACKOFF_MAX_MS = 30_000L

    /** 幂等台账的行存活时长：超过就没用了（红包早过期了）*/
    const val DEDUP_TTL_MS = 10L * 60_000L

    /** 幂等台账的最大行数。到顶丢掉最老的一半，防止长时间挂机内存涨 */
    const val DEDUP_MAX_ROWS = 512

    /** 名单分隔符：半角逗号、全角逗号、顿号、任意空白（含换行）。与 `MuteAtLogic.aliases` 同款 */
    private val LIST_SPLIT = Regex("""[,，、\s]+""")

    // ================================================================== 输入

    /**
     * 一条待判定的消息快照。
     *
     * ⚠️ **字段全部由调用方填，本类不假设微信内部结构**：没有 `msg looking like ...`
     * 之类的方法，也不 import 任何 `android.*` / `xposed.*`。取证出锚点后，hook 侧负责
     * 把真实取值取来塞进来。
     *
     * @param id       消息唯一标识（取证到稳定的 msgId / svrId 就填；拿不到留空，
     *                 引擎会用 [keyOf] 合成一个够用的去重键）
     * @param talker   会话 talker（`wxid_xxx` / `xxx@chatroom`）
     * @param type     微信 `message.type` 的原始值（含高位标志）
     * @param content  正文 / xml，拿不到传 null
     * @param isSend   是不是自己发的（自己发的红包不处理）
     * @param createMs 消息创建时间（epoch millis），只用于合成去重键
     */
    data class Signal(
        val id: String = "",
        val talker: String = "",
        val type: Int = 0,
        val content: String? = null,
        val isSend: Boolean = false,
        val createMs: Long = 0L,
    )

    /**
     * 生效中的配置。全部来自 `Keys`（见 Keys.kt 红包域），hook 侧用 Kv 读出来再传。
     *
     * @param types       强判据：`type` 低位白名单；**空集合 = 这条判据关闭**
     * @param markers     弱判据：正文子串；**空集合 = 这条判据关闭**
     * @param allow       参与范围，空 = 全开
     * @param exclude     排除范围，优先于 allow
     */
    data class Cfg(
        val enabled: Boolean = false,
        val types: Set<Int> = emptySet(),
        val markers: Set<String> = emptySet(),
        val delayMs: Int = DEFAULT_DELAY_MS,
        val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        val allow: Set<String> = emptySet(),
        val exclude: Set<String> = emptySet(),
    )

    // ================================================================== 输出

    /**
     * 决策结果。**全部是数据，没有任何 execute / apply 之类的方法** —— 这条是刻意的：
     * 让「自动拆红包」在代码结构上就**没有能产出副作用的地方**。详见类注释第二节。
     */
    sealed class Verdict {

        /** 不处理。reason 只用于打日志 */
        data class Skip(val reason: String) : Verdict()

        /**
         * 认定为候选红包，请在 [delayMs] 毫秒后带着同一个 [key] 再来问一次引擎。
         *
         * @param attempt 这是第几次决策（1 起算），只用于日志与上限判断
         */
        data class Wait(val key: String, val delayMs: Int, val attempt: Int) : Verdict()

        /** 足够可疑但判据不够强 —— 只记录，不进入后续流程 */
        data class Observe(val key: String, val note: String) : Verdict()

        /** 交回用户手动：重试次数打满 */
        data class Manual(val reason: String) : Verdict()
    }

    /** 识别强度 */
    enum class Match {
        /** 命中强判据（正在取证的类型清单）*/
        HIT,

        /** 只命中弱判据（文案特征）—— 够不上处理，只值得记一行 */
        SUSPECT,

        /** 什么都没命中 / 判据未配置 */
        NONE,
    }

    // ================================================================== 解析

    /**
     * 名单解析（TEXT 型配置项，裸文本不是 JSON —— 用 `Kv.getList` 读会得到空列表，
     * 这是 `MuteAtLogic` 头上点过的坑）。
     *
     * 前后空白去掉、空段丢弃、重复项去重。元素既可以是 talker（wxid / chatroom），
     * 也可以是群显示名 —— 匹配时两者任一相等即命中（见 [inScope]）。
     */
    fun entries(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val out = LinkedHashSet<String>()
        for (part in raw.split(LIST_SPLIT)) {
            val t = part.trim()
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    /**
     * type 低位清单解析（TEXT 型配置项，形如 `2001, 436207665`）。非法段忽略。
     *
     * ⚠️ 解析出来的是**低位**：微信 type 高位是它自己的标志位，`AntiRevoke` 实证
     * `type=268445458` 的低位是 `10002`。比较时统一走 [lowType]。
     */
    fun typeCodes(raw: String?): Set<Int> {
        if (raw.isNullOrBlank()) return emptySet()
        val out = LinkedHashSet<Int>()
        for (part in raw.split(LIST_SPLIT)) {
            val v = part.trim().toIntOrNull() ?: continue
            if (v > 0) out.add(v)
        }
        return out
    }

    /** type 的低 16 位（同 `AntiRevoke` 的 `type & 0xFFFF` 实证写法，不是新猜的规则）*/
    fun lowType(type: Int): Int = type and 0x0000FFFF

    /**
     * 延迟钳制：**下限优先**。
     *
     * 为什么不是 `coerceIn(min, max)` 就完事 —— 写 `coerceIn` 本身没问题，这里特意把
     * 「0 和负数也一定被拉到 [MIN_DELAY_MS]」单独摊开说清楚：填 0 是用户最容易的一个
     * 手误，而 0 恰好是最危险的值（秒抢）。非法值（负数）同样按 [MIN_DELAY_MS] 处理。
     */
    fun clampDelay(rawMs: Int): Int = when {
        rawMs < MIN_DELAY_MS -> MIN_DELAY_MS
        rawMs > MAX_DELAY_MS -> MAX_DELAY_MS
        else -> rawMs
    }

    /**
     * 第 `fails` 次失败之后要等多久：1s → 2s → 4s … 封顶 [BACKOFF_MAX_MS]。
     * `fails <= 0` 表示没失败过，立即可以再来。
     */
    fun backoffMs(fails: Int): Long {
        if (fails <= 0) return 0L
        val shift = minOf(fails - 1, 20) // 20 位足够，再往上没有意义且接近溢出
        return minOf(BACKOFF_BASE_MS shl shift, BACKOFF_MAX_MS)
    }

    // ================================================================== 识别

    /**
     * 这条消息像不像红包。
     *
     * 强判据（type 低位）优先于弱判据（文案）：type 是微信自己写进库的枚举，比正文
     * 稳定得多；而正文里出现「红包」两个字的场景太多（有人发一句「发个红包呗」）。
     *
     * ⚠️ **两条判据都未配置时恒返回 [Match.NONE]** —— 这就是「没取证就绝不动作」。
     */
    fun classify(s: Signal, cfg: Cfg): Match {
        val hit = cfg.types.isNotEmpty() && lowType(s.type) in cfg.types
        if (hit) return Match.HIT
        val text = s.content
        if (cfg.markers.isNotEmpty() && !text.isNullOrBlank()) {
            if (cfg.markers.any { text.contains(it, ignoreCase = true) }) return Match.SUSPECT
        }
        return Match.NONE
    }

    /**
     * 名单范围判定（全开 / 仅名单内 / 排除名单）。优先级：
     * 1. talker 空白 → 不在范围。**认不出是哪个会话就别管** —— 比误判安全；
     * 2. 命中 [exclude] → 不在范围（排除名单优先，哪怕同时写在 allow 里）；
     * 3. [allow] 为空 → 全开；非空 → 必须在 allow 里才算。
     */
    fun inScope(
        talker: String?,
        groupName: String?,
        allow: Set<String>,
        exclude: Set<String>,
    ): Boolean {
        val room = talker?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (hit(room, groupName, exclude)) return false
        if (allow.isEmpty()) return true
        return hit(room, groupName, allow)
    }

    private fun hit(room: String, groupName: String?, names: Set<String>): Boolean {
        if (names.isEmpty()) return false
        if (room in names) return true
        val name = groupName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return name in names
    }

    // ================================================================== 幂等 + 退避

    /**
     * 幂等台账 + 失败退避（纯内存，进程重启即清空）。
     *
     * ⚠️ 用 `Collections.synchronizedMap(HashMap())` 而不是 ConcurrentHashMap：
     * 真机上 ConcurrentHashMap 的 null value 直接崩过（NPE）。这里**绝不 put null**，
     * 「有没有见过」一律靠 `containsKey` / `get` 判空。
     *
     * 台账丢失只会让某个红包被**多看一次**（一个红包最多被处理两次），
     * 而选择不落盘是为了不留下一堆难以解释的历史状态。
     */
    class Ledger(
        private val ttlMs: Long = DEDUP_TTL_MS,
        private val maxRows: Int = DEDUP_MAX_ROWS,
    ) {

        private class Row(var seenAt: Long, var fails: Int, var nextAt: Long)

        private val rows: MutableMap<String, Row> = Collections.synchronizedMap(HashMap())

        /** 第一次见到返回 true 并记账；已经见过返回 false（幂等的唯一判据）*/
        fun first(key: String, nowMs: Long): Boolean {
            if (key.isBlank()) return false
            synchronized(rows) {
                if (rows.containsKey(key)) return false
                pruneLocked(nowMs)
                rows[key] = Row(nowMs, 0, 0L)
                return true
            }
        }

        /** 累计失败次数（没见过就是 0）*/
        fun failsOf(key: String): Int = synchronized(rows) { rows[key]?.fails ?: 0 }

        /**
         * 记一次失败，返回累计失败次数，并把下次可重试时刻推到 `nowMs + [backoffMs]`。
         *
         * 什么时候算「失败」由调用方定义（取证期唯一可判的失败是「延迟到点后复查，
         * 界面上已经没有这个红包了」）。
         */
        fun noteFail(key: String, nowMs: Long): Int {
            if (key.isBlank()) return 0
            return synchronized(rows) {
                val row = rows[key] ?: Row(nowMs, 0, 0L).also { rows[key] = it }
                row.fails += 1
                row.nextAt = nowMs + backoffMs(row.fails)
                row.fails
            }
        }

        /** 还要等多少毫秒才能再试（0 = 现在就能）*/
        fun cooldownLeft(key: String, nowMs: Long): Long =
            synchronized(rows) { maxOf(0L, (rows[key]?.nextAt ?: 0L) - nowMs) }

        /** 丢掉过期的行；行数超上限时再丢掉最老的一半 */
        fun prune(nowMs: Long) {
            synchronized(rows) { pruneLocked(nowMs) }
        }

        fun size(): Int = synchronized(rows) { rows.size }

        fun clear() = synchronized(rows) { rows.clear() }

        private fun pruneLocked(nowMs: Long) {
            val dead = ArrayList<String>()
            for ((k, v) in rows) if (nowMs - v.seenAt > ttlMs) dead += k
            for (k in dead) rows.remove(k)
            if (rows.size > maxRows) {
                val oldest = rows.entries.sortedBy { it.value.seenAt }.map { it.key }
                val drop = (oldest.size - maxRows / 2).coerceAtLeast(0)
                for (i in 0 until drop) rows.remove(oldest[i])
            }
        }
    }

    // ================================================================== 决策

    /**
     * 这条消息的去重键。
     *
     * 取证到稳定的 msgId / svrId 就用它；拿不到（现在就是这个情况）用
     * `talker + createMs + content.hashCode()` 合成 —— 够唯一的内存去重，
     * 且**不会**因为 id 缺失就把两条不同的消息混成一个。
     */
    fun keyOf(s: Signal): String {
        val id = s.id.trim()
        if (id.isNotEmpty()) return id
        val room = s.talker.trim()
        if (room.isEmpty()) return ""
        return "$room#${s.createMs}#${(s.content ?: "").hashCode()}"
    }

    /**
     * 唯一决策入口。顺序即语义，**不要调**（见下面每一步的理由）。
     *
     * @return 一定是 [Verdict] 之一，**没有任何副作用**
     */
    fun decide(s: Signal, cfg: Cfg, ledger: Ledger, nowMs: Long): Verdict {
        if (!cfg.enabled) return Verdict.Skip("off")
        // 自己发的红包：抢自己发的没有意义，且容易被自己的「已领取」状态绕进去
        if (s.isSend) return Verdict.Skip("self")
        val key = keyOf(s)
        if (key.isBlank()) return Verdict.Skip("no-key")
        if (!inScope(s.talker, null, cfg.allow, cfg.exclude)) return Verdict.Skip("scope")

        return when (classify(s, cfg)) {
            Match.NONE -> Verdict.Skip("not-lucky")
            // 弱判据不进幂等台账：它够不上「处理」，不该占掉那一次机会
            Match.SUSPECT -> Verdict.Observe(key, "suspect")
            Match.HIT -> {
                val fails = ledger.failsOf(key)
                if (fails >= cfg.maxAttempts) return Verdict.Manual("attempts=$fails")
                val cool = ledger.cooldownLeft(key, nowMs)
                if (cool > 0) return Verdict.Wait(key, cool.toInt(), fails + 1)
                if (!ledger.first(key, nowMs)) return Verdict.Skip("dup")
                Verdict.Wait(key, clampDelay(cfg.delayMs) + backoffMs(fails).toInt(), fails + 1)
            }
        }
    }

    // ================================================================== HOOK_TODO
    //
    // **本轮没有 hook（有意如此）**。取证结论里没有任何红包锚点，所以：
    //
    //  1. 不许硬编 `com.tencent.mm.plugin.luckymoney.*` 之类的类名当实现 ——
    //     那是从别的项目/别的版本抄来的猜测，装上去大概率「找不到类」，
    //     更糟的情况是找**错**了类，在真机上表现为微信崩溃或路由到别的界面。
    //  2. 开工的正确顺序：
    //     probe(op=lucky, sub=rows) 取证 → 确定红包消息的 type 低位与正文特征
    //     → 回填 Keys.REDPACKET_TYPE_CODES / TEXT_MARKERS → 把结论写进 docs/取证结论.md
    //     → **那时候**才写 hook，且 hook 拿到的值必须先塞进 [Signal] 再问 [decide]。
    //  3. hook 落地时，凡是拿到 [Verdict.Wait] 之后能做的事，也必须先回答一个问题：
    //     「这一步有没有可能替用户把红包领了？」—— 在拿到明确的领取锚点之前，
    //     答案必须落在「记录」上（[Verdict.Observe] 的语义就是这个）。
}
