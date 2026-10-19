package com.lx.wxgroup.feature

/**
 * 需求 6.5「群发」的**纯判据**（零 Android 依赖 → 可直接跑 JVM 单测）。
 *
 * 放进这里的东西遵循一条原则：**错了要上一轮真机才能发现的判据**，
 * 必须能在没连手机的时候被锁住。UI / 反射 / hook / 落盘全部留在 [MassSendPage]。
 *
 * ## 三块内容
 * 1. **目标去重展开**：选择器选出来的 id（裸 wxid / `g:<分组id>` / `t:<标签id>`）
 *    展开成去重后的具体的人，并记录**来源**（供展示与日志对账）。
 * 2. **间隔随机**：`0 ~ 设定秒` → 毫秒。随机源由调用方注入（[intervalMs] 的 `unit`），
 *    所以生产用 `Math.random()`、单测用固定值，同一输入序列必得同一结果。
 * 3. **发送状态机**：[reduce] 是**纯函数**（`Job + Event -> Job`），
 *    不碰线程、不碰时间、不碰 IO —— 单测可以完全锁住「重试 1 次 / 连续失败 5 条中止 /
 *    汇总 n/m」这几条需求口径。
 *
 * ## ⚠️ 关于「未发送」与「失败」的区分（诚实降级的关键）
 * 单条结果有三种而不是两种（发送链路由 [com.lx.wxgroup.core.TextSender] 提供，S9 已接通）：
 *  - [Outcome.OK]      真的发出去了；
 *  - [Outcome.FAIL]    链路存在，但这一条没发出去 → 走「重试 1 次 → 跳过 → 连续 5 条中止」；
 *  - [Outcome.NO_CHANNEL] 链路压根不存在 → **不是这一条的失败**，不重试、不计入失败、
 *    不触发风控闸门（否则任务会在第 5 条就"自动中止"，把"没接口"伪装成"被风控"）。
 *
 * 结果由 [Summary.unsent] 单独计数，结束时弹窗如实说「未发送 N 条 / 一条都没发出去」。
 *
 * ⚠️ 本文件**禁止 import 任何 android.* / xposed.* **，否则单测加载不了。
 */
object MassSendLogic {

    // ------------------------------------------------------------------ 常量

    /** 模块分组节点 id 前缀（选的是分组，发送前要展开成人） */
    const val PREFIX_GROUP = "g:"

    /** 微信标签节点 id 前缀 */
    const val PREFIX_LABEL = "t:"

    /** 单条最多尝试几次 = 首次 1 次 + 重试 1 次（需求：失败重试 1 次） */
    const val MAX_ATTEMPTS = 2

    /** 连续失败多少条自动中止整个任务（需求的风控闸门） */
    const val MAX_CONSECUTIVE_FAIL = 5

    /** [Item.lastError] 的取值：该条是因为「没有发送链路」而没发出去，不是失败 */
    const val NO_CHANNEL_MARK = "no-channel"

    /** 一条都没发出去时的诚实提示（文案放逻辑层，便于单测锁住） */
    const val HINT_NO_CHANNEL = "当前版本没有可用的发送接口，一条都没有发出去"

    /** 单批次目标数**不限**（需求明确），所以这里刻意没有批量上限 */

    // ------------------------------------------------------------------ 目标展开

    data class Target(
        val wxid: String,
        val name: String = "",
        /** 这个人是被哪些选中项带进来的（`g:3` / `t:12` / 直接选择），供展示与对账 */
        val sources: List<String> = emptyList(),
    )

    /** 分组 id → 成员 wxid；标签 id → 成员 wxid */
    data class Buckets(
        val groups: Map<String, List<String>> = emptyMap(),
        val labels: Map<String, List<String>> = emptyMap(),
    )

    /**
     * 展开 + 去重：同一人被多个分组 / 标签同时选中时**只发一次**（需求明确要求），
     * 但把全部来源都记下来。顺序按**选择顺序**稳定输出（先选的分组里的人排前面）。
     *
     * @param exclude 排除集（群发没有「排除当前会话」的需求，参数留作通用能力，默认空）
     * @param nameOf  由调用方（能查好友缓存的那层）补展示名；纯逻辑侧默认拿不到名字
     */
    fun resolveTargets(
        picked: List<String>,
        buckets: Buckets = Buckets(),
        exclude: Set<String> = emptySet(),
        nameOf: (String) -> String = { "" },
    ): List<Target> {
        val src = LinkedHashMap<String, LinkedHashSet<String>>()
        for (raw in picked) {
            val id = raw.trim()
            if (id.isBlank()) continue
            when {
                id.startsWith(PREFIX_GROUP) -> {
                    val key = id.removePrefix(PREFIX_GROUP)
                    for (w in buckets.groups[key].orEmpty()) note(src, w, id, exclude)
                }
                id.startsWith(PREFIX_LABEL) -> {
                    val key = id.removePrefix(PREFIX_LABEL)
                    for (w in buckets.labels[key].orEmpty()) note(src, w, id, exclude)
                }
                else -> note(src, id, "直选", exclude)
            }
        }
        return src.map { (w, from) -> Target(w, nameOf(w), from.toList()) }
    }

    private fun note(
        out: LinkedHashMap<String, LinkedHashSet<String>>,
        wxid: String,
        from: String,
        exclude: Set<String>,
    ) {
        val w = wxid.trim()
        if (w.isBlank() || w in exclude) return
        out.getOrPut(w) { LinkedHashSet() }.add(from)
    }

    // ------------------------------------------------------------------ 间隔

    /**
     * `0 ~ maxSec 秒` 的随机间隔 → 毫秒。
     *
     * @param maxSec 设定秒数；≤0 一律返回 0（间隔为 0 是合法配置，边界要锁住）
     * @param unit   随机源，取值 `[0,1)`；越界钳制、NaN 当 0，保证结果永远落在 `[0, maxSec*1000]`
     */
    fun intervalMs(maxSec: Int, unit: Double): Long {
        val hi = maxSec.coerceAtLeast(0)
        if (hi <= 0) return 0L
        val u = if (unit.isNaN()) 0.0 else unit.coerceIn(0.0, 1.0)
        return (u * hi * 1000.0).toLong()
    }

    // ------------------------------------------------------------------ 状态机

    enum class Phase { IDLE, RUNNING, PAUSED, DONE, ABORTED }

    enum class ItemState { PENDING, SENDING, RETRYING, SUCCESS, FAILED, SKIPPED }

    /** 单条发送结果。三种而不是两种，理由见类注释 */
    enum class Outcome { OK, FAIL, NO_CHANNEL }

    data class Item(
        val wxid: String,
        val name: String = "",
        val sources: List<String> = emptyList(),
        val state: ItemState = ItemState.PENDING,
        /** 已尝试次数（[MAX_ATTEMPTS] 封顶） */
        val attempts: Int = 0,
        val lastError: String = "",
    )

    data class Job(
        val items: List<Item> = emptyList(),
        /** 当前推进到第几条（下标）。完成后等于 [items].size */
        val cursor: Int = 0,
        val phase: Phase = Phase.IDLE,
        /** 连续失败计数；成功或「无链路」都会清零/不计 */
        val consecutiveFail: Int = 0,
        /** 中止 / 手动结束的原因，展示给用户的文案 */
        val note: String = "",
    ) {
        val total: Int get() = items.size
    }

    sealed class Event {
        data class Start(val targets: List<Target>) : Event()
        /** 派发当前这条（PENDING → SENDING，重试时 → RETRYING） */
        object Dispatch : Event()
        data class Result(val wxid: String, val outcome: Outcome, val reason: String = "") : Event()
        object Pause : Event()
        object Resume : Event()
        /** 用户点「结束」 */
        object Stop : Event()
    }

    /** 开跑：把目标铺成 [Item] 列表。目标为空 → 直接 DONE（不会卡在 RUNNING） */
    fun start(targets: List<Target>): Job = reduce(Job(), Event.Start(targets))

    /** 当前对象（第 cursor 条）；游标越界返回 null */
    fun current(job: Job): Item? = job.items.getOrNull(job.cursor)

    /** 已完成条数（用于 `n / 总数`）。钳到 total，避免游标越界时显示 `129 / 128` */
    fun doneCount(job: Job): Int = minOf(job.cursor, job.items.size)

    /** 进度 0~1（进度条用）。total 为 0 时返回 0 */
    fun ratio(job: Job): Float {
        val t = job.items.size
        return if (t == 0) 0f else doneCount(job).toFloat() / t.toFloat()
    }

    /**
     * 状态机的**唯一入口**：纯函数，`Job + Event -> Job`。
     * 相同输入序列必得相同结果（不读时间、不读随机、不碰线程）。
     */
    fun reduce(job: Job, event: Event): Job = when (event) {
        is Event.Start -> {
            val items = dedup(event.targets)
            if (items.isEmpty()) {
                Job(items = emptyList(), phase = Phase.DONE, note = "没有可发送的目标")
            } else {
                Job(items = items, cursor = 0, phase = Phase.RUNNING)
            }
        }

        Event.Dispatch -> dispatch(job)

        is Event.Result -> onResult(job, event)

        Event.Pause -> if (job.phase == Phase.RUNNING) job.copy(phase = Phase.PAUSED) else job

        Event.Resume -> if (job.phase == Phase.PAUSED) job.copy(phase = Phase.RUNNING) else job

        Event.Stop -> stop(job)
    }

    private fun dedup(targets: List<Target>): List<Item> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Item>(targets.size)
        for (t in targets) {
            val w = t.wxid.trim()
            if (w.isBlank() || !seen.add(w)) continue
            out.add(Item(wxid = w, name = t.name, sources = t.sources))
        }
        return out
    }

    private fun dispatch(job: Job): Job {
        if (job.phase != Phase.RUNNING) return job
        val i = job.cursor
        val it = job.items.getOrNull(i) ?: return finish(job)
        if (it.state != ItemState.PENDING) return job
        val st = if (it.attempts == 0) ItemState.SENDING else ItemState.RETRYING
        return job.copy(items = replaced(job.items, i, it.copy(state = st)))
    }

    private fun onResult(job: Job, ev: Event.Result): Job {
        if (job.phase != Phase.RUNNING) return job
        val i = job.items.indexOfFirst {
            it.wxid == ev.wxid && (it.state == ItemState.SENDING || it.state == ItemState.RETRYING)
        }
        if (i < 0) return job
        val it = job.items[i]
        val attempts = it.attempts + 1
        return when (ev.outcome) {
            Outcome.OK -> {
                val items = replaced(job.items, i, it.copy(state = ItemState.SUCCESS, attempts = attempts))
                advance(job.copy(items = items, consecutiveFail = 0))
            }

            Outcome.NO_CHANNEL -> {
                // 链路不存在：不是这一条的失败 → 不重试、不计连续失败、不触发闸门
                val items = replaced(
                    job.items, i,
                    it.copy(state = ItemState.SKIPPED, attempts = attempts, lastError = NO_CHANNEL_MARK)
                )
                advance(job.copy(items = items))
            }

            Outcome.FAIL -> {
                if (attempts < MAX_ATTEMPTS) {
                    // 还有一次重试机会：留在原位，游标不动、连续失败也不算
                    val items = replaced(
                        job.items, i,
                        it.copy(state = ItemState.PENDING, attempts = attempts, lastError = ev.reason)
                    )
                    job.copy(items = items)
                } else {
                    val items = replaced(
                        job.items, i,
                        it.copy(state = ItemState.FAILED, attempts = attempts, lastError = ev.reason)
                    )
                    val cf = job.consecutiveFail + 1
                    if (cf >= MAX_CONSECUTIVE_FAIL) {
                        abort(job.copy(items = items, consecutiveFail = cf), "连续失败 $cf 条，已自动中止")
                    } else {
                        advance(job.copy(items = items, consecutiveFail = cf))
                    }
                }
            }
        }
    }

    /** 游标前移；到底就收尾 */
    private fun advance(job: Job): Job {
        val c = job.cursor + 1
        return if (c >= job.items.size) finish(job.copy(cursor = c)) else job.copy(cursor = c)
    }

    private fun finish(job: Job): Job =
        if (job.phase == Phase.DONE || job.phase == Phase.ABORTED) job else job.copy(phase = Phase.DONE)

    /** 风控闸门：剩下的 PENDING 一律标 SKIPPED，整体 ABORTED */
    private fun abort(job: Job, reason: String): Job = job.copy(
        items = skipRest(job.items),
        phase = Phase.ABORTED,
        note = reason,
    )

    /** 用户「结束」：剩下的 PENDING 标 SKIPPED，整体按正常结束处理（也要弹汇总） */
    private fun stop(job: Job): Job {
        if (job.phase == Phase.DONE || job.phase == Phase.ABORTED) return job
        return job.copy(
            items = skipRest(job.items, onlyPending = true),
            phase = Phase.DONE,
            note = "已手动结束",
        )
    }

    private fun skipRest(items: List<Item>, onlyPending: Boolean = false): List<Item> =
        items.map {
            val wip = it.state == ItemState.PENDING ||
                (!onlyPending && (it.state == ItemState.SENDING || it.state == ItemState.RETRYING))
            if (wip) it.copy(state = ItemState.SKIPPED) else it
        }

    private fun replaced(items: List<Item>, i: Int, v: Item): List<Item> {
        val out = ArrayList(items)
        out[i] = v
        return out
    }

    // ------------------------------------------------------------------ 汇总

    data class Summary(
        val total: Int = 0,
        val success: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
        /** 因「没有发送链路」而没发出去的条数（[Outcome.NO_CHANNEL]） */
        val unsent: Int = 0,
        val phase: Phase = Phase.IDLE,
        val note: String = "",
    ) {
        /** 需求口径的「完成 n/m」：n = 实际成功数，m = 目标总数 */
        fun headline(): String = "完成 $success/$total"

        /** 弹窗正文：完成 n/m + 失败数 + 未发送数 + 中止原因 */
        fun message(): String {
            val sb = StringBuilder(headline())
            if (failed > 0) sb.append(" · 失败 $failed")
            if (skipped > 0) sb.append(" · 跳过 $skipped")
            if (unsent > 0) sb.append(" · 未发送 $unsent")
            if (note.isNotBlank()) sb.append("\n").append(note)
            return sb.toString()
        }

        /** 一条都没发出去 → 弹窗必须追加「未实际发送」的诚实说明（绝不能看起来像成功） */
        fun nothingSent(): Boolean = success == 0 && total > 0

        /** 有目标是因为「没有发送链路」而没发出去 → 追加 [HINT_NO_CHANNEL] */
        fun noChannelHit(): Boolean = unsent > 0
    }

    fun summary(job: Job): Summary {
        var ok = 0
        var bad = 0
        var skipped = 0
        var unsent = 0
        for (it in job.items) {
            when (it.state) {
                ItemState.SUCCESS -> ok++
                ItemState.FAILED -> bad++
                ItemState.SKIPPED -> if (it.lastError == NO_CHANNEL_MARK) unsent++ else skipped++
                // 还没轮到 / 正在发（收尾后才允许汇总，这里按跳过保守计数）
                ItemState.PENDING, ItemState.SENDING, ItemState.RETRYING -> skipped++
            }
        }
        return Summary(
            total = job.items.size,
            success = ok,
            failed = bad,
            skipped = skipped,
            unsent = unsent,
            phase = job.phase,
            note = job.note,
        )
    }

    // ------------------------------------------------------------------ 降级产物

    /**
     * 降级产物：把「消息内容 + 去重后的目标清单」渲染成一份纯文本，
     * 供用户复制到微信自带群发助手 / 手动发送（落盘由 [MassSendPage] 做，这里只负责内容）。
     */
    fun renderPlan(text: String, targets: List<Target>): String {
        val sb = StringBuilder()
        sb.append("# 群发清单（模块生成，供手动发送或微信自带群发助手使用）\n")
        sb.append("# 共 ${targets.size} 个目标（已按 wxid 去重）\n\n")
        sb.append("---- 消息内容 ----\n").append(text).append("\n\n")
        sb.append("---- 目标列表 ----\n")
        targets.forEachIndexed { i, t ->
            val n = t.name.ifBlank { t.wxid }
            sb.append("${i + 1}. $n <${t.wxid}>\n")
        }
        return sb.toString()
    }
}
