package com.lx.wxgroup.feature

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.core.TextSender
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.PrefSpec
import com.lx.wxgroup.data.Prefs
import com.lx.wxgroup.ui.Panel
import com.lx.wxgroup.ui.PanelPage
import com.lx.wxgroup.ui.Rows
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 需求 6.5「群发」（主界面第六行）。
 *
 * 页面两种形态，由 [job] 的 [MassSendLogic.Phase] 决定：
 *  - **配置态**（IDLE / DONE / ABORTED）：群发域的三项配置（目标 / 内容 / 间隔）
 *    交给 K2 渲染器画（加配置项 = `Prefs.ALL` 加一行，本文件不用动），
 *    底下是「开始群发」主操作按钮 + 上次结果。
 *  - **发送中**（RUNNING / PAUSED）：进度 `n / 总数` + 当前对象 + 进度条 +
 *    「暂停 / 继续」「结束」，**自动逐条推进**（后台线程驱动，见 [loop]）。
 *
 * ## 为什么是 object 而不是 class
 * 群发是**全程后台**的：用户关掉弹窗去正常用微信，任务继续跑；再打开面板要能看到进度。
 * 所以任务状态必须活过 `Panel` 的重建 —— 用单例承载，[Panel.refresh] 只是重画。
 *
 * ## 后台纪律（需求点名）
 * 不打开聊天界面、不跳转、不抢前台；后台期间**没有任何前台提示**（无通知 / 悬浮窗 / 角标），
 * 进度只在面板打开时可见（[publish] 里 `Panel.isOpen()` 且栈深未变才刷新）。
 *
 * ## 发送链路
 * ✅ S9 已接通（2026-10-18 端到端验证）：[MassSendRunner.send] 走
 * [com.lx.wxgroup.core.TextSender.send] 真发；链路缺失时返回
 * [MassSendLogic.Outcome.NO_CHANNEL]（不重试、不计连续失败、不触发风控闸门），
 * 此时 [showResult] 如实弹「完成 0/N · 未发送 N」并把目标清单落盘供用户手动发送。
 * **绝不在 UI 上出现「已发送成功」这类假象。**
 */
object MassSendPage : PanelPage {

    override val title = "群发"

    /** 分组项在选择器里的 id 前缀，用来跟好友 wxid 区分开 */
    private const val GROUP_PREFIX = "g:"

    private const val FOOTNOTE =
        "全自动后台执行，全程不抢前台；进度只在打开本面板时可见。" +
            "单条失败重试 1 次后跳过继续，连续失败 5 条自动中止。"

    /** 目标清单落盘目录（微信分区存储下只有自己的 external cache 能写） */
    private val DUMP_DIRS = arrayOf(
        "/sdcard/Android/data/com.tencent.mm/cache",
        "/storage/emulated/0/Android/data/com.tencent.mm/cache",
    )

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 任务状态。后台线程与主线程都会动它 → volatile */
    @Volatile
    private var job: MassSendLogic.Job = MassSendLogic.Job()

    /** 后台线程是否还活着（防重复启动） */
    @Volatile
    private var looping = false

    /** 本次群发的消息内容（开跑时快照，之后改配置不影响正在跑的任务） */
    @Volatile
    private var text: String = ""

    /** 开跑时的页面栈深：[publish] 只在栈深没变（用户没翻到别的页）时才重画 */
    @Volatile
    private var startDepth: Int = 0

    private val lock = Object()

    // ------------------------------------------------------------------ 页面

    override fun build(ctx: Context): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val j = job
        if (j.phase == MassSendLogic.Phase.RUNNING || j.phase == MassSendLogic.Phase.PAUSED) {
            buildRunning(ctx, root, j)
            return root
        }
        buildConfig(ctx, root, j)
        return root
    }

    /** 配置态：三项配置 + 开始按钮 + 上次结果 */
    private fun buildConfig(ctx: Context, root: LinearLayout, j: MassSendLogic.Job) {
        // visible()：hidden 的项保留定义但不渲染（见 PrefSpec.hidden）
        val specs = Prefs.visible("群发")
        if (specs.isEmpty()) {
            root.addView(Rows.note(ctx, "群发域暂无可配置项"))
        } else {
            root.addView(Rows.prefGroup(ctx, specs, ::onEnterSpec))
        }

        root.addView(Rows.primaryButton(ctx, "开始群发") { start(ctx) })

        if (j.phase == MassSendLogic.Phase.DONE || j.phase == MassSendLogic.Phase.ABORTED) {
            val s = MassSendLogic.summary(j)
            root.addView(Rows.section(ctx, "上次结果"))
            root.addView(Rows.note(ctx, s.message()))
            if (s.noChannelHit()) root.addView(Rows.note(ctx, MassSendLogic.HINT_NO_CHANNEL))
        }
        root.addView(Rows.note(ctx, FOOTNOTE))
    }

    /** 发送中：进度 / 当前对象 / 进度条 / 暂停·继续 / 结束 */
    private fun buildRunning(ctx: Context, root: LinearLayout, j: MassSendLogic.Job) {
        val total = j.items.size
        val done = MassSendLogic.doneCount(j)
        val paused = j.phase == MassSendLogic.Phase.PAUSED

        root.addView(Rows.section(ctx, if (paused) "已暂停" else "发送中"))
        root.addView(UiKit.label(ctx, "$done / $total", 20f, bold = true))

        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = if (total == 0) 1 else total
            progress = done
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val side = UiKit.dp(ctx, 14)
                setMargins(side, UiKit.dp(ctx, 4), side, UiKit.dp(ctx, 4))
            }
        }
        root.addView(bar)

        val cur = MassSendLogic.current(j)
        if (cur != null) {
            val n = cur.name.ifBlank { cur.wxid }
            root.addView(UiKit.label(ctx, "当前：$n", 14f))
        }

        val s = MassSendLogic.summary(j)
        root.addView(
            UiKit.label(
                ctx,
                "成功 ${s.success} · 失败 ${s.failed} · 未发送 ${s.unsent}",
                12f, gray = true
            )
        )

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionButton(ctx, if (paused) "继续" else "暂停") { togglePause() })
        row.addView(actionButton(ctx, "结束") { stopByUser() })
        root.addView(row)

        root.addView(Rows.note(ctx, "后台执行中：可以关掉本面板照常用微信，再打开就能看到进度"))
    }

    private fun actionButton(ctx: Context, label: String, onClick: () -> Unit): View =
        Button(ctx).apply {
            text = label
            // 不硬编码前景色：微信深色主题下写死黑字会变成黑底黑字
            setTextColor(UiKit.fgColor(ctx))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                setMargins(UiKit.dp(ctx, 6), UiKit.dp(ctx, 4), UiKit.dp(ctx, 6), UiKit.dp(ctx, 4))
            }
        }

    // ------------------------------------------------------------------ 目标选择

    /**
     * 「选择发送目标」行：走 K3 选择器，**混合选**模块分组 + 微信标签 + 好友。
     * 与转发选择器同一套构建方式（分组 / 标签为空就不显示那段，宁可少一段不显示空段）。
     */
    private fun onEnterSpec(spec: PrefSpec): Boolean {
        if (spec.key != Keys.BROADCAST_TARGETS) return false
        val db = GroupDb.current() ?: return false
        val sections = ArrayList<PickerPage.Section>()

        val groups = runCatching { db.listGroups() }.getOrDefault(emptyList()).map {
            PickerPage.Item(
                id = "$GROUP_PREFIX${it.id}",
                title = it.name,
                subtitle = "分组 · ${runCatching { db.countMembers(it.id) }.getOrDefault(0)} 人",
            )
        }
        if (groups.isNotEmpty()) sections.add(PickerPage.Section("我的分组", groups))

        labelSection()?.let { sections.add(it) }

        val friends = runCatching { db.allRealFriends() }.getOrDefault(emptyList())
            .map { PickerPage.Item(it.wxid, it.displayName, it.wxid) }
        if (friends.isNotEmpty()) sections.add(PickerPage.Section("好友", friends))

        if (sections.isEmpty()) return false

        Panel.push(
            PickerPage(
                title = spec.title,
                sections = sections,
                initial = Kv.getList(spec.key),
                hint = "分组、微信标签与好友可混选，发送时会展开成具体的人并去重",
            ) { ids ->
                Kv.putList(spec.key, ids)
                LogX.i("PREF ${spec.key} = ${ids.size} 项")
            }
        )
        return true
    }

    /** 微信标签段：id 带 `t:` 前缀。无标签 / 缓存没灌进来时返回 null（不显示该段） */
    private fun labelSection(): PickerPage.Section? {
        if (!LabelSource.ready()) return null
        val items = runCatching { LabelSource.allLabels() }.getOrDefault(emptyList())
            .filter { !it.name.isNullOrBlank() }
            .map {
                PickerPage.Item(
                    id = "${MassSendLogic.PREFIX_LABEL}${it.id}",
                    title = it.name!!,
                    subtitle = "微信标签 · ${it.memberCount} 人",
                )
            }
        return if (items.isEmpty()) null else PickerPage.Section("微信标签", items)
    }

    private fun buildBuckets(db: GroupDb?): MassSendLogic.Buckets {
        val groups = HashMap<String, List<String>>()
        if (db != null) {
            for (g in runCatching { db.listGroups() }.getOrDefault(emptyList())) {
                val m = runCatching { db.memberWxids(g.id) }.getOrDefault(emptySet())
                if (m.isNotEmpty()) groups[g.id.toString()] = m.toList()
            }
        }
        return MassSendLogic.Buckets(
            groups = groups,
            labels = runCatching { LabelSource.membersByLabelId() }.getOrDefault(emptyMap()),
        )
    }

    // ------------------------------------------------------------------ 控制

    private fun start(ctx: Context) {
        runCatching {
            if (looping) {
                UiKit.toast(ctx, "已经有一个群发任务在跑")
                return
            }
            val db = GroupDb.current()
            if (db == null) {
                UiKit.toast(ctx, "数据库还没准备好")
                return
            }
            val body = Kv.getString(Keys.BROADCAST_LAST_TEXT).trim()
            if (body.isEmpty()) {
                UiKit.toast(ctx, "还没填消息内容")
                return
            }
            val picked = Kv.getList(Keys.BROADCAST_TARGETS)
            if (picked.isEmpty()) {
                UiKit.toast(ctx, "还没选发送目标")
                return
            }
            launchRun(db, body, picked)?.let { UiKit.toast(ctx, it) }
        }.onFailure { LogX.e("MASSSEND start failed", it) }
    }

    /**
     * 真正启动（UI 按钮与广播探针共用）。
     *
     * @return null = 已成功启动；非 null = 失败原因（调用方决定 toast 还是打日志）
     */
    private fun launchRun(db: GroupDb, body: String, picked: List<String>): String? {
        val targets = MassSendLogic.resolveTargets(picked, buildBuckets(db)) { wxid ->
            runCatching { db.findFriend(wxid)?.displayName }.getOrNull().orEmpty()
        }
        if (targets.isEmpty()) {
            return "目标展开后为空（分组或标签里可能没有人）"
        }
        text = body
        startDepth = Panel.depth()
        job = MassSendLogic.start(targets)
        val j = job
        LogX.i(
            "MASSSEND start picked=${picked.size} targets=${j.items.size} " +
                "interval<=${Kv.getInt(Keys.BROADCAST_INTERVAL_S)}s " +
                "textLen=${body.length} pending=${MassSendRunner.pendingAnchors()}"
        )
        Panel.refresh()
        runLoop()
        return null
    }

    private fun togglePause() {
        synchronized(lock) {
            val ev = if (job.phase == MassSendLogic.Phase.PAUSED) {
                MassSendLogic.Event.Resume
            } else {
                MassSendLogic.Event.Pause
            }
            job = MassSendLogic.reduce(job, ev)
            lock.notifyAll()
        }
        LogX.i("MASSSEND ${job.phase}")
        Panel.refresh()
    }

    private fun stopByUser() {
        synchronized(lock) {
            job = MassSendLogic.reduce(job, MassSendLogic.Event.Stop)
            lock.notifyAll()
        }
        LogX.i("MASSSEND stopped by user")
        Panel.refresh()
    }

    /**
     * 后台执行循环：派发 → 发送 → 记结果 → 随机间隔 → 下一条。
     *
     * 全程在一个后台线程里，**不碰任何 UI**（要刷界面一律 [publish] 回主线程）。
     * 暂停时挂起在 [lock] 上（不空转）。
     */
    private fun runLoop() {
        if (looping) return
        looping = true
        Thread({
            runCatching {
                val sec = Kv.getInt(Keys.BROADCAST_INTERVAL_S)
                while (true) {
                    synchronized(lock) {
                        while (job.phase == MassSendLogic.Phase.PAUSED) lock.wait(500)
                    }
                    if (job.phase != MassSendLogic.Phase.RUNNING) break

                    job = MassSendLogic.reduce(job, MassSendLogic.Event.Dispatch)
                    val cur = MassSendLogic.current(job)
                    if (cur == null) break

                    val outcome = MassSendRunner.send(cur.wxid, text)
                    job = MassSendLogic.reduce(
                        job, MassSendLogic.Event.Result(cur.wxid, outcome)
                    )
                    publish()

                    if (job.phase != MassSendLogic.Phase.RUNNING) break
                    val wait = MassSendLogic.intervalMs(sec, Math.random())
                    if (wait > 0) Thread.sleep(wait)
                }
                looping = false
                publish()
                report()
            }.onFailure {
                looping = false
                LogX.e("MASSSEND loop crashed", it)
            }
        }, "wxgroup-masssend").start()
    }

    /** 只在面板开着、且用户没翻到别的页时才重画（后台期间不产生任何前台提示） */
    private fun publish() {
        mainHandler.post {
            runCatching {
                if (Panel.isOpen() && Panel.depth() == startDepth) Panel.refresh()
            }.onFailure { LogX.w("MASSSEND refresh failed: ${it.javaClass.simpleName}") }
        }
    }

    /** 收尾：落盘降级清单 + 弹「完成 n/m」 */
    private fun report() {
        val j = job
        val s = MassSendLogic.summary(j)
        LogX.i("MASSSEND done ${s.message()} phase=${j.phase}")
        val path = dumpPlan(j)
        mainHandler.post {
            runCatching {
                val ctx = HostContext.activity() ?: HostContext.app() ?: return@post
                showResult(ctx, s, path)
            }.onFailure { LogX.e("MASSSEND result dialog failed", it) }
        }
    }

    private fun showResult(ctx: Context, s: MassSendLogic.Summary, path: String?) {
        val sb = StringBuilder(s.message())
        if (s.noChannelHit()) sb.append("\n\n").append(MassSendLogic.HINT_NO_CHANNEL)
        else if (s.nothingSent()) sb.append("\n\n未实际发送任何消息")
        if (path != null) sb.append("\n\n目标清单已导出：\n").append(path)
        runCatching {
            AlertDialog.Builder(ctx)
                .setTitle("群发结束")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show()
        }.onFailure { LogX.w("MASSSEND dialog n/a: ${it.javaClass.simpleName}") }
    }

    /**
     * 降级产物落盘：把「消息内容 + 去重目标清单」写成 txt，
     * 供用户复制到微信自带群发助手 / 手动发送。写失败只记日志，不影响汇总弹窗。
     */
    private fun dumpPlan(j: MassSendLogic.Job): String? {
        if (j.items.isEmpty()) return null
        val targets = j.items.map { MassSendLogic.Target(it.wxid, it.name, it.sources) }
        val content = MassSendLogic.renderPlan(text, targets)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "wxgroup_masssend_$stamp.txt"
        for (dir in DUMP_DIRS) {
            val ok = runCatching {
                val d = File(dir)
                if (!d.exists() && !d.mkdirs()) return@runCatching false
                File(d, name).writeText(content)
                true
            }.getOrDefault(false)
            if (ok) return "$dir/$name"
        }
        LogX.w("MASSSEND dump failed: no writable dir")
        return null
    }

    // ------------------------------------------------------------------ 探针（广播驱动）

    /**
     * 广播探针（`op=broadcast`）：给真机脚本一个**不依赖 UI** 的驱动入口。
     *
     * 为什么需要它：群发的规则（去重展开 / 重试 / 风控闸门）在 [MassSendLogic] 有单测，
     * 但「完整链路真的跑一遍」必须上真机 —— 而 UI 路径要点开面板、选目标、填内容、点开始，
     * adb 驱动不了。这里复用与按钮完全相同的 [launchRun]，把「跑一轮」变成一条广播。
     *
     * ⚠️ **会真的发消息**。调用方（`tools/_broadcast.py`）自带三重闸门（--really / --targets≤3 /
     * 间隔 ≥5s）；本函数自身只做「配置是否齐全」的检查，不做目标数限制 ——
     * 因为目标来源是用户自己在设置里选的，UI 按钮路径本来也没有目标数上限。
     *
     * sub：
     * - `stat`  只读状态（默认）：`looping` / 当前 job 进度 / 发送链路是否就绪 / 配置摘要
     * - `start` 启动一轮（用当前 Kv 里的 targets + last_text + interval）
     * - `pause` / `resume` / `stop`  控制正在跑的任务
     */
    fun probe(sub: String): String = runCatching {
        when (sub.lowercase()) {
            "start" -> {
                if (looping) return@runCatching "already running"
                val db = GroupDb.current() ?: return@runCatching "no db"
                val body = Kv.getString(Keys.BROADCAST_LAST_TEXT).trim()
                if (body.isEmpty()) return@runCatching "empty text (设置 群发 消息内容)"
                val picked = Kv.getList(Keys.BROADCAST_TARGETS)
                if (picked.isEmpty()) return@runCatching "no targets (设置 群发 选择发送目标)"
                val err = launchRun(db, body, picked)
                err ?: "started picked=${picked.size} interval<=${Kv.getInt(Keys.BROADCAST_INTERVAL_S)}s"
            }
            "pause" -> {
                togglePause(); "phase=${job.phase}"
            }
            "resume" -> {
                togglePause(); "phase=${job.phase}"
            }
            "stop" -> {
                stopByUser(); "phase=${job.phase}"
            }
            else -> {
                val s = MassSendLogic.summary(job)
                "looping=$looping phase=${job.phase} sent=${s.success}/${s.total} " +
                    "failed=${s.failed} skipped=${s.skipped} unsent=${s.unsent} " +
                    "textLen=${Kv.getString(Keys.BROADCAST_LAST_TEXT).trim().length} " +
                    "targets=${Kv.getList(Keys.BROADCAST_TARGETS).size} " +
                    "interval<=${Kv.getInt(Keys.BROADCAST_INTERVAL_S)}s " +
                    MassSendRunner.pendingAnchors()
            }
        }
    }.getOrElse { "error=${it.javaClass.simpleName}: ${it.message}" }

    // ------------------------------------------------------------------ 发送

    /**
     * 真正的发送（群发的最后一公里）。
     *
     * ✅ **S9 已接通（2026-10-18 端到端验证）**：走 [com.lx.wxgroup.core.TextSender.send]
     * （`hl0/r1.b(talker)` → `q1.e(content)` → `q1.b()`，真机落库验证通过）。
     * 链路可用时返回 OK / FAIL（走重试 1 次 → 连续 5 条中止），
     * 链路缺失（模块未 prepare）时返回 NO_CHANNEL（不重试、不计失败、不触发风控闸门）。
     */
    private object MassSendRunner {

        /** 开跑时打进日志，真机一眼能看出发送链路是否就绪 */
        fun pendingAnchors(): String =
            "S9.send=${if (TextSender.ready()) "READY" else "NO_CHANNEL"}"

        fun send(wxid: String, body: String): MassSendLogic.Outcome {
            if (!TextSender.ready()) {
                LogX.w("MASSSEND no send channel (TextSender not prepared) wxid=${wxid.take(24)}")
                return MassSendLogic.Outcome.NO_CHANNEL
            }
            return if (TextSender.send(wxid, body)) {
                MassSendLogic.Outcome.OK
            } else {
                LogX.w("MASSSEND send failed wxid=${wxid.take(24)} len=${body.length}")
                MassSendLogic.Outcome.FAIL
            }
        }
    }
}
