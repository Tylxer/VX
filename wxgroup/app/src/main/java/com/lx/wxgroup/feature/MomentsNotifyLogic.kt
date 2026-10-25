package com.lx.wxgroup.feature

/**
 * 朋友圈自动导出失败「弹窗告知」的**纯判据**（零 Android 依赖 → 可直接跑 JVM 单测）。
 *
 * 抽出来的理由跟 [SnsTagLogic] 一样：失败原因分类、弹/不弹决策、去重 latch 状态机
 * 这些判据写错了要上真机跑一轮才看得出（弹错 / 漏弹 / 反复弹都是对用户的骚扰），
 * 放在这里可以先跑 JVM 单测锁住。
 *
 * ⚠️ 本文件**禁止 import 任何 android.* / xposed.* / 项目内其它类**，否则单测加载不了。
 *
 * ## 三层决策（都要过才真正弹窗）
 * 1. **原因层** [isNotifiable]：哪类失败值得弹（持久性故障弹；瞬时/单条偶发不弹）。
 * 2. **去重层** [Latch]：同一原因只弹一次，直到出现一次成功导出才复位。
 * 3. **触发源层**（在 `MomentsExport` 接线，不在这里）：手动探针 `sub=export` 不弹，只回摘要。
 */
object MomentsNotifyLogic {

    /** 失败原因分类（覆盖 `MomentsExport.runBlocking` 里全部失败路径） */
    enum class Reason {
        /** GroupDb 尚未就绪 —— 微信启动早期的瞬时状态，不弹 */
        DB_NOT_READY,

        /** 导出根路径已存在但不是目录 —— 持久性，弹 */
        NOT_DIR,

        /** 导出根路径不存在且创建失败 —— 持久性，弹 */
        MKDIRS,

        /** 导出根路径不可写 —— 持久性，弹 */
        READONLY,

        /** 导出根路径剩余空间低于下限 —— 持久性，弹 */
        SPACE,

        /** 单条动态的导出子目录创建失败 —— 循环内单条失败，默认不弹（见 [Kind.ESCALATE]） */
        MKDIRS_ITEM,

        /** 导出中途剩余空间不足，中止本轮 —— 持久性，弹 */
        SPACE_ABORT,

        /** 写单个文本文件失败 —— 与 [MKDIRS_ITEM] 同构的单条失败，默认不弹（见 [Kind.ESCALATE]） */
        WRITE,

        /** 整轮导出抛异常 —— 未知故障，弹 */
        THROW,
    }

    /** 弹窗文案（标题 + 正文）。正文刻意不带超长路径，路径细节留在日志里 */
    data class Message(val title: String, val body: String)

    private const val TITLE = "朋友圈自动导出失败"

    /**
     * 原因 → 弹窗文案。
     *
     * 正文只给「人读得懂的一句话」，不带超长路径（对话框会变形）；
     * 精确的 root / free 字节数在 `MomentsExport` 的 w 日志里。
     */
    fun messageFor(reason: Reason): Message = when (reason) {
        Reason.DB_NOT_READY -> Message(TITLE, "数据库尚未就绪，稍后会自动重试。")
        Reason.NOT_DIR -> Message(TITLE, "导出路径不是文件夹，请检查路径设置。")
        Reason.MKDIRS -> Message(TITLE, "无法创建导出目录，请检查路径是否可写。")
        Reason.READONLY -> Message(TITLE, "导出目录不可写，请检查存储权限。")
        Reason.SPACE -> Message(TITLE, "存储空间不足，已停止导出，请清理后重试。")
        Reason.MKDIRS_ITEM -> Message(TITLE, "创建导出子目录失败，已跳过该条。")
        Reason.SPACE_ABORT -> Message(TITLE, "导出中途空间不足，已中止本轮导出。")
        Reason.WRITE -> Message(TITLE, "写入导出文件失败，已跳过该条。")
        Reason.THROW -> Message(TITLE, "导出过程发生异常，已跳过本轮。")
    }

    /**
     * 弹窗倾向分类。**每个 [Reason] 必须落且仅落入一类。**
     *
     * ⚠️ 刻意让 [kindOf] 写成 `when` **表达式**（而不是布尔表达式 / 黑名单）：
     * 将来新增第 10 个 [Reason] 时编译器会**逼**你在这里三选一，
     * 不可能悄悄落到「默认弹」上。旧写法 `reason != DB_NOT_READY && reason != MKDIRS_ITEM`
     * 是黑名单 —— 新枚举默认「弹」，却没人提醒你去决定它该不该弹（专审 §1 附带风险）。
     */
    enum class Kind {
        /** 永不弹窗：瞬时 / 会自愈的状态，弹了只是骚扰 */
        SILENT,

        /** 总是值得弹：路径 / 权限 / 空间 / 整轮异常，用户不处理就不会自己好 */
        ALWAYS,

        /**
         * 单条失败：**默认不弹**，同因失败越过阈值才升级为可弹（见 [Escalation]）。
         *
         * 为什么不直接按「哪个枚举」判「持久 / 偶发」：[Reason.WRITE] 与 [Reason.MKDIRS_ITEM]
         * 在「循环内单条 / 不阻断整轮 / 下轮自动重试 / 可由同一根因引起」四个维度上完全同构，
         * 一个弹一个不弹没有判据支撑（专审 §1）。⇒ 二者归入同一类，改由「**这轮它持不持久**」决定。
         */
        ESCALATE,
    }

    fun kindOf(reason: Reason): Kind = when (reason) {
        Reason.DB_NOT_READY -> Kind.SILENT
        Reason.NOT_DIR -> Kind.ALWAYS
        Reason.MKDIRS -> Kind.ALWAYS
        Reason.READONLY -> Kind.ALWAYS
        Reason.SPACE -> Kind.ALWAYS
        Reason.SPACE_ABORT -> Kind.ALWAYS
        Reason.THROW -> Kind.ALWAYS
        Reason.MKDIRS_ITEM -> Kind.ESCALATE
        Reason.WRITE -> Kind.ESCALATE
    }

    /**
     * 原因层决策：这类失败**值不值得**弹。
     *
     * - [Kind.SILENT]（[Reason.DB_NOT_READY]）：微信启动早期数据库还没就绪，几秒后自动恢复，
     *   弹窗只会骚扰（5 分钟一跳，恢复后自然就不报了）。
     * - [Kind.ESCALATE]（[Reason.MKDIRS_ITEM] / [Reason.WRITE]）：循环内单条失败，
     *   多是坏昵称 / 个别文件被占用等偶发，不阻断整轮，er 计数里已可见；
     *   **只有同因失败越过 [Escalation] 阈值**（`escalated=true`）才升级为值得弹。
     * - 其余 [Kind.ALWAYS] 是**持久性故障**（路径 / 权限 / 空间 / 整轮异常），
     *   用户不处理就不会自己好，必须弹。
     *
     * @param escalated 本笔失败是否已越过 [Escalation] 升级阈值（只对 [Kind.ESCALATE] 生效）
     */
    fun isNotifiable(reason: Reason, escalated: Boolean = false): Boolean = when (kindOf(reason)) {
        Kind.SILENT -> false
        Kind.ALWAYS -> true
        Kind.ESCALATE -> escalated
    }

    /**
     * 跨轮去重 latch：同一原因**只弹一次**，直到出现一次成功导出才复位。
     *
     * 语义跟旧的 `pathWarned` / `spaceWarned` 不同：那俩是**轮内日志去重**（每轮开头重置），
     * 这里是**跨轮弹窗去重**（不复位就一直按住）。导出节奏 5 分钟一跳 + 10 分钟最小间隔，
     * 一个持久性故障若每轮都弹等于每小时骚扰用户一次，必须按住。
     *
     * 线程安全：runBlocking 单航班（`running` AtomicBoolean 挡并发），但 onTick 可能
     * 换线程触发，latch 上 synchronized 兜底，开销可忽略。
     */
    class Latch {
        private val fired = HashSet<Reason>()

        /** 该原因还没弹过 → true（调用方弹完记得 [markFired]） */
        @Synchronized
        fun shouldFire(reason: Reason): Boolean = reason !in fired

        /** 标记该原因已弹过（此后 [shouldFire] 恒 false，直到 [resetAll]） */
        @Synchronized
        fun markFired(reason: Reason) {
            fired.add(reason)
        }

        /** 出现一次成功导出：把所有原因复位，下次再故障重新弹 */
        @Synchronized
        fun resetAll() {
            fired.clear()
        }

        /** 快照（单测断言用） */
        @Synchronized
        fun firedReasons(): Set<Reason> = HashSet(fired)
    }

    /**
     * 「同因失败」升级状态机：per-item 原因（[Kind.ESCALATE]）**默认不弹**，
     * 越过阈值才升级为「值得弹」。跨轮、零 Android 依赖、可单测。
     *
     * ## 判据（替代「按枚举名判持久/偶发」）
     * > 本轮同因失败 ≥ [THIS_ROUND_THRESHOLD] 次，**或**已是连续第 [CONSECUTIVE_ROUNDS] 轮出现
     * > ⇒ 不是偶发，是系统性的 ⇒ 值得弹一次。
     *
     * ## 用法（一轮三段）
     * [beginRound] → 每笔失败 [note]（返回「本次是否已升级」）→ [endRound]。
     *
     * ⚠️ 调用方漏了 [beginRound] ⇒ 「本轮计数」退化成「**累计**计数」（跨轮不清零）。
     *    ⛔ 注意方向：这是**更激进**不是更保守 —— 累计数比本轮数**更容易**达到
     *    [THIS_ROUND_THRESHOLD] ⇒ ESCALATE 类原因会**提前升级、提前弹**，不是「不会误弹」。
     *    （R20 `gate-review` 报 F-2，原文注释写反了：旧文是「更保守，不会误弹」。）
     *
     * 线程安全：同 [Latch]，synchronized 兜底（开销可忽略）。
     */
    class Escalation {

        companion object {
            /** 同一轮里同因失败到这个数，说明不是偶发 */
            const val THIS_ROUND_THRESHOLD = 3

            /** 连续出现这么多轮（含本轮）也算不是偶发 */
            const val CONSECUTIVE_ROUNDS = 2
        }

        /** 本轮各原因的失败次数 */
        private val thisRound = HashMap<Reason, Int>()

        /** 各原因**此前**连续出现故障的轮数（不含正在跑的这一轮） */
        private val streaks = HashMap<Reason, Int>()

        @Synchronized
        fun beginRound() {
            thisRound.clear()
        }

        /** 记一笔该原因的本轮失败。@return 这一笔**是否已升级**到「值得弹」 */
        @Synchronized
        fun note(reason: Reason): Boolean {
            val n = (thisRound[reason] ?: 0) + 1
            thisRound[reason] = n
            return n >= THIS_ROUND_THRESHOLD ||
                (streaks[reason] ?: 0) >= CONSECUTIVE_ROUNDS - 1
        }

        /**
         * 一轮结束：本轮出现过的 streak +1，没出现过的清零。
         *
         * ⚠️ 与 clean 无关 —— per-item 失败不算 [Decision.countsAsFailure]，
         * 所以整轮可能仍判定 clean；若这里跟着 clean 清零，就永远攒不到连续轮数。
         */
        @Synchronized
        fun endRound() {
            for (r in thisRound.keys) streaks[r] = (streaks[r] ?: 0) + 1
            for (r in streaks.keys.toSet()) if (r !in thisRound) streaks.remove(r)
        }

        /** 快照（单测断言用） */
        @Synchronized
        fun thisRoundCount(reason: Reason): Int = thisRound[reason] ?: 0

        /** 快照：该原因此前连续故障轮数（单测断言用） */
        @Synchronized
        fun consecutiveRounds(reason: Reason): Int = streaks[reason] ?: 0
    }

    /**
     * 一次故障的处理结果。
     *
     * @param countsAsFailure 这一笔**算不算本轮失败**（决定本轮 clean 与否）
     * @param doNotify 这一笔**要不要真的去弹窗**（受原因层 + 去重层共同决定）
     */
    data class Decision(val countsAsFailure: Boolean, val doNotify: Boolean)

    /**
     * **一次故障的完整决策**（纯函数，无副作用，可单测）。
     *
     * ## ⛔ 这个函数存在的理由：把「算不算失败」与「弹不弹」彻底解耦
     * R18 复核时发现的自引入 P0：早先接线用「有没有弹窗」当「本轮是否成功」的判据，
     * 于是在 latch 已触发的那一轮 `clean` 误为 `true` ⇒ `resetAll()` 清空 latch
     * ⇒ 同一持久性故障变成**每隔一轮弹一次**。
     *
     * 正确语义：
     * - `countsAsFailure` 只看**故障本身值不值得算失败**（[isNotifiable]），与去重无关。
     * - `doNotify` 才看去重（[Latch.shouldFire]）。
     *
     * ⚠️ 本函数**不改 latch**（保持纯函数）。占用 latch 由调用方在**送达成功后**做，
     * 因为「弹窗没送出去」不算「已经通知过」。
     *
     * ⚠️ `countsAsFailure` 目前**等价于** [isNotifiable]（不是独立的第二套判据，见专审 §2）。
     * 二者将来若分家，必须同步修订 `MomentsExport` 里 `clean` 的语义
     * （现在的 `clean` 实为「本轮没有值得弹窗的故障」，不是「本轮导出成功」）。
     *
     * @param escalated 本笔失败是否已越过 [Escalation] 阈值（只对 [Kind.ESCALATE] 的原因有效）
     */
    fun decide(latch: Latch, reason: Reason, escalated: Boolean = false): Decision {
        if (!isNotifiable(reason, escalated)) return Decision(countsAsFailure = false, doNotify = false)
        return Decision(countsAsFailure = true, doNotify = latch.shouldFire(reason))
    }

    /**
     * **一次故障的完整接线**（零 Android 依赖 ⇒ JVM 单测跑得到）。
     *
     * ## 为什么这个函数必须存在（专审 §5.4）
     * 之前 13 条多轮用例是对 `MomentsExport.notify()` 的**仿真**：调用方手写
     * `if (d.doNotify) { if (sender(...)) latch.markFired(r) }`。仿真副本会跟真接线漂移 ——
     * 把 `MomentsExport.notify()` 改坏（markFired 挪到 if 外、`return true` 改回「返回有没有弹」），
     * 一条测试都不会红，而 R18 的 P0 和 F1 恰恰住在那层没被覆盖的真接线里。
     * ⇒ 把接线搬进纯逻辑层，**上线跑的就是测试跑的那份代码**。
     *
     * ## 返回值语义（沿用上一个 P0 的修复）
     * `@return` **本轮是否发生故障**（= `Decision.countsAsFailure`），与「这次有没有真的弹出去」无关。
     *
     * @param sender 真正去投弹窗的函数：入参 = 文案 + **送达回调**；返回 = **是否受理**。
     *   送达回调 `delivered=true` 才占用 latch；`false`（没送出去 / 异步放弃）不占 ⇒ 下轮重试。
     */
    fun onFailure(
        latch: Latch,
        reason: Reason,
        escalated: Boolean = false,
        sender: (Message, onOutcome: (delivered: Boolean) -> Unit) -> Boolean,
    ): Boolean {
        val d = decide(latch, reason, escalated)
        if (!d.countsAsFailure) return false
        if (d.doNotify) {
            sender(messageFor(reason)) { delivered -> if (delivered) latch.markFired(reason) }
        }
        return true
    }

    /**
     * 失败通知的**状态宿主**：去重 [Latch] + per-item 升级 [Escalation]。
     *
     * `MomentsExport` 一轮导出的正确用法：
     * ```
     * notifyGate.beginRound()                       // 轮开头
     * ...  if (notifyGate.onFailure(reason) { msg, cb -> MomentsNotify.notify(msg, cb) }) clean = false
     * notifyGate.endRound(clean)                    // 轮结尾（早退的那几条 return 不调它）
     * ```
     *
     * ⚠️ 「多轮只弹一次」「未送达下轮重试」这些行为从此由**这份代码**保证，
     * 而不是由接线处手写的三行保证。
     */
    class Gate {

        private val latch = Latch()
        private val escalation = Escalation()

        /** 一轮导出的开头调用：清空本轮计数 */
        @Synchronized
        fun beginRound() = escalation.beginRound()

        /**
         * 记一笔故障（并顺手升级 per-item 计数）+ 投递通知。
         *
         * @return 本轮是否发生故障（调用方用它算 `clean`）
         */
        @Synchronized
        fun onFailure(
            reason: Reason,
            sender: (Message, onOutcome: (delivered: Boolean) -> Unit) -> Boolean,
        ): Boolean = MomentsNotifyLogic.onFailure(latch, reason, escalation.note(reason), sender)

        /**
         * **早退专用**：只结算连续故障轮数，**绝不** reset latch。
         *
         * ## 为什么需要它（R20 `gate-review` 报 F-1）
         * `MomentsExport.runBlocking()` 有 5 条提前 `return`，它们绕过 [endRound]。
         * 后果有**两半**，旧注释只写了半边：
         * ① 不 reset latch —— 这是我们想要的（早退 = 失败轮）；
         * ② **escalation 结算也被跳过了** —— 这一半之前没人意识到。
         *
         * 当前无害的原因：那 5 条早退对应的 Reason 全是 SILENT / ALWAYS，
         * `isNotifiable` **根本不读 `escalated`** ⇒ streak 冻在 0 也无行为差异。
         * 但它是一颗**静默地雷**：将来任何人往早退分支加一个 ESCALATE 原因
         * （`WRITE` / `MKDIRS_ITEM`）⇒ streak 恒 0 ⇒ 永远升不了级 ⇒ **永久漏弹**，
         * 且不崩、无日志、单测不红 —— 最难查的那种。
         *
         * ⇒ 早退处请调本方法而不是什么都不调。
         */
        @Synchronized
        fun abortRound() {
            escalation.endRound()
        }

        /**
         * 一轮导出的结尾调用：结算连续故障轮数；**且仅**在本轮干净时复位 latch。
         *
         * ⚠️ 早退（`db not ready` / 路径不可写 / 起步空间不足等）**不该**调这个方法 ——
         * 早退 = 失败轮，不该 resetAll（这也是 `MomentsExport` 里 5 条 `return` 绕过结尾的现状）。
         *
         * ⚠️ 但早退**应该**调 [abortRound] 补上结算，理由见那里的说明
         * （R20 F-1：跳过结算会让 ESCALATE 原因的 streak 恒 0）。
         */
        @Synchronized
        fun endRound(clean: Boolean) {
            escalation.endRound()
            if (clean) latch.resetAll()
        }

        /** 快照（单测断言用） */
        fun firedReasons(): Set<Reason> = latch.firedReasons()

        /** 快照：该原因此前连续故障轮数（单测断言用） */
        fun consecutiveRounds(reason: Reason): Int = escalation.consecutiveRounds(reason)
    }
}
