package com.lx.wxgroup.feature

import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.Prefs
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 需求 6.4「朋友圈」——**自动导出**的 hook 侧。
 *
 * ## ⚠️ 需求语义（用户 2026-10-25 定案，改代码前先读这段）
 * 本功能是**搭配定时刷新朋友圈实现防撤回**的：
 * 在动态**当前还看得见**的时候就把它的图留存下来（**预防性留存**），
 * **不是**等动态被删了再从归档里抢救。
 *
 * ⇒ 数据源因此必须是**微信库里当前的 `SnsInfo`**（[SnsGuard.listCurrentSns]），
 * 不是模块自己的归档表 `sns_archive`。这不是口味问题，是被实测逼出来的：
 * 朋友圈的图存在 sns 磁盘缓存 `…/cache/<hash32>/sns/…/snst_xxx`，放的是
 * **当前可见**动态的图；而 `sns_archive` 里是**已经被删掉**的动态 ——
 * R19 实测归档的 7 个 mediaId 在设备 72 个缓存文件里 **0 命中**，
 * 扫归档时 `imgCopied` 恒为 0 是数据源选错了，不是取图链路坏了。
 *
 * ## 数据链
 * ```
 * ① 微信库 SnsInfo（当前可见，localFlag & 16 != 0）  ← 主数据源（防撤回语义）
 *      → SnsGuard.listCurrentSns 键集分页（一次一页，不把 BLOB 全读进内存）
 * ② 模块库 sns_archive（SnsGuard 拦 DELETE 时归档的整行快照）  ← 补扫（已删的也要）
 *      → GroupDb.listSnsArchiveBefore 键集分页
 * 两者按 snsId 去重（[SnsScanCursor.first]），①扫完才走②
 *   → MomentsLogic.renderText 渲染 text 文本
 *   → MomentsLogic.exportDir 拼 <root>/昵称(wxid)/yyyy-MM-dd-HHmm/
 *   → <snsId>.txt（tmp + rename 原子落盘）
 * ```
 * ⚠️ ①拿不到（微信这次启动还没碰过朋友圈库 ⇒ 句柄未捕获）**不是故障**：
 * 优雅降级成只扫②（= 2026-10-25 之前的行为），只打日志、不弹窗。
 *
 * 去重靠扫导出目录里已存在的 `<snsId>.txt`（[MomentsLogic.parseExportedId]）；
 * 名单命中靠 [MomentsLogic.listHit]；空间下限靠 [MomentsLogic.MIN_FREE_BYTES]。
 *
 * ## ⚠️ 图片（原图）导出：命名规则已取证（R15），**旧 md5 路线已于 R20 物理删除**
 * 2026-10-24 真机取证已定案：缓存目录
 * `/sdcard/Android/data/com.tencent.mm/cache/<hash32>/sns/<hex1>/<hex2>/`，
 * 文件名是 `snsb_` / `snsu_` / `snst_` + mediaId 派生（**不是 md5**），
 * 早先的「`MD5(mediaId)` 分桶」公式命中率仅 0.68% ⇒ **公式路线废弃**，正解为索引直扫。
 *
 * ⛔ **已删于 R20（A8 收敛）**：本文件原有的两条旧 md5 路线遗留探针
 * （`logImgProbe`：`mediaKeys → md5sOf → imageCandidates → pickExisting`，只打日志；
 * `tryCopyImages`：同一条 md5 链路 + 真拷贝，只服务探针 `sub=imgtry`）已连同
 * `MomentsLogic` 侧 6 个符号一起物理删除，`sub=img` / `sub=imgtry` 探针同步下架。
 * 判死依据：旧 md5 路线三次真机证伪（R15 分桶 0.68% / R16 全盘 find 零结果 /
 * R19 文件名是 20 位数字串不含 md5）。正解见 docs/_r19_a4_probe.md，
 * 证据见 docs/_r20_a8_del.md。
 *
 * 新链路见 [buildSnsIndex] / [copyImagesForRow] / [probeSnsCache]：
 * 建文件名索引 → [MomentsLogic.snsNameCandidates] 出候选名 → [MomentsLogic.matchSnsFileName] 查表。
 *
 * [COPY_IMAGES] 因此**恒为 false**（真机复核未做，见常量处的说明），
 * 这里只把线索打出来 —— **严禁猜规则硬写**。
 * 文本文件里已经带了媒体 URL 段（renderText 的「---- 媒体 ----」），内容不丢。
 *
 * ## 失败策略（需求 6.4「失败只弹窗」+ 决策 #11，2026-10-24 定案）
 * 路径不存在 / 建不成功 / 写不进 / 空间不足 / 写文件失败 / 整轮异常 →
 * **弹窗告知**（`UiKit.confirm`），同时仍打一行日志、绝不崩微信。
 *
 * 两个约束在接线里落实（见 [MomentsNotify] / [MomentsNotifyLogic]）：
 * - **去重**：同一失败原因**只弹一次**，直到出现一次成功导出才复位
 *   （[MomentsNotifyLogic.Latch]）。导出 5 分钟一跳 + 10 分钟最小间隔，持久性故障
 *   每轮都失败，若每轮都弹等于每小时骚扰用户一次。
 * - **后台弹窗风险收窄**：微信在后台时没有前台 Activity，弹 Dialog 会
 *   BadTokenException。按 [EntryReceiver.withForeground] 的路子先把微信拉起
 *   （NEW_TASK）+ 延迟 1.5s **再次校验**，仍拿不到 Activity 就放弃并记日志，
 *   **不无限重试**。风险（后台周期任务可能在用户聊天时弹出）已被决策 #11
 *   明确接受。
 *
 * 哪些情形**不弹**（详见 [MomentsNotifyLogic.isNotifiable]）：
 * - `db not ready`：微信启动早期数据库未就绪，几秒后自动恢复，弹窗纯骚扰；
 * - 循环内单条目录 `mkdirs` 失败：偶发（坏昵称等），不阻断整轮，er 计数已可见；
 * - 手动探针 `sub=export`（诊断触发，走 [runBlocking]）：只回摘要，不弹。
 */
/**
 * 一轮导出的**扫行游标**：先扫「当前 SnsInfo」，扫完再补扫「归档」。
 *
 * ⛔ 刻意做成**纯逻辑类**（不碰 Android / 不碰数据库 / 不碰 [GroupDb]）：
 * 本文件里唯一能进 JVM 单测的就是它。[MomentsExport] 自己是 object，
 * 静态初始化带着 Handler / Kv / Prefs 一串 Android 依赖，单元测试碰不得。
 *
 * ⚠️ 它管的两件事都是**真机跑满一轮才暴露**的错：
 * - 跨源去重漏了 ⇒ 同一条动态导出两遍（归档里也有同一条）；
 * - 切源写错 ⇒ 当前源扫完就 `break`，归档永远不扫（等于把已删动态的导出丢了）。
 *
 * 见 `MomentsExportScanTest`。
 */
internal class SnsScanCursor {

    /** 行来源：① 当前 SnsInfo（主）② 归档（补） */
    enum class Src { CURRENT, ARCHIVE }

    var src: Src = Src.CURRENT
        private set

    /** 键集游标：下一页取 `(createTime, snsId)` 字典序更小的行；`MAX_VALUE` = 从最新开始 */
    var ct: Long = Long.MAX_VALUE
        private set

    var sid: Long = Long.MAX_VALUE
        private set

    private val seen = HashSet<Long>()

    /**
     * 推进键集游标。
     *
     * ⚠️ **被去重跳过的行也必须推进**：游标不跟着走，下一页还是同一页 ⇒ 死循环。
     */
    fun advanceTo(createTime: Long, snsId: Long) {
        ct = createTime
        sid = snsId
    }

    /**
     * 这个 snsId 本轮该处理吗（`false` = 磁盘上已导出过 / 本轮已处理过）。
     *
     * ⚠️ snsId 是 **Long 且可能为负**（真机 `-3447438253133393222`）。
     * 任何 `toInt()` 都会把不同的动态截断成同一个 key，其中一条被静默吞掉
     * （归档那边的老坑，见 `SnsGuard.readRow` 的 KDoc）。
     */
    fun first(snsId: Long, exported: Set<Long>): Boolean =
        snsId !in exported && seen.add(snsId)

    /**
     * 当前源这一页**空了**或**拿不到**（[Src.CURRENT] 拿不到微信库句柄也算）→ 换下一个源。
     *
     * @return true = 还有下一个源（游标已复位到该源最新一页）；false = 两个源都扫完了
     */
    fun nextSource(): Boolean {
        if (src != Src.CURRENT) return false
        src = Src.ARCHIVE
        ct = Long.MAX_VALUE
        sid = Long.MAX_VALUE
        return true
    }
}

object MomentsExport {

    private const val TAG = "MOMENT"

    /**
     * 图片导出总闸。**false = 不上真机跑**。
     *
     * R15 起代码链路已就位（protobuf 取 mediaId + SNS 磁盘缓存索引直扫 + 候选名匹配 +
     * 四态计数，见 [mediaIdsOf] / [buildSnsIndex] / [copyOneFromCache]）。
     * mediaId 来源已于 R15-I2 接通（通用线格式，见 [MomentsLogic.mediaIdsFromContent]），
     * 但**真机复核还没做**，`imgCopied` 到底能不能 >0 未经验证，**保持 false**。
     */
    const val COPY_IMAGES = false

    private const val CFG_TTL_MS = 3_000L

    /** 例行判定间隔（导出本身还有 10 分钟的最小间隔，见 MomentsLogic.exportDue） */
    private const val TICK_MS = 5 * 60_000L
    private const val FIRST_TICK_MS = 30_000L

    /** 每页行数 / 单轮最多处理行数（BLOB 一页一页读，内存有界） */
    private const val PAGE = 100
    private const val MAX_ROWS_PER_RUN = 500

    /** 扫目录收集已导出 id 的上限（防异常大的目录把内存吃穿） */
    private const val SCAN_MAX_IDS = 20_000

    /** 每写这么多个文件查一次剩余空间 */
    private const val SPACE_CHECK_EVERY = 50

    // ⛔ 已删于 R20（A8 收敛）：IMG_PROBE_MAX —— 旧 md5 路线已三次真机证伪
    //    （R15 分桶 0.68% / R16 全盘 find 零结果 / R19 文件名是 20 位数字串不含 md5）。
    //    正解见 docs/_r19_a4_probe.md。证据见 docs/_r20_a8_del.md。

    // ---- 原图下载（url 路线，见 MomentsLogic.kindOfUrl）----
    private const val DL_CONNECT_MS = 10_000
    private const val DL_READ_MS = 30_000
    /** 单个媒体上限 20MB：朋友圈原图/短视频到不了这个量级，超了多半是下错了东西 */
    private const val MAX_MEDIA_BYTES = 20L * 1024 * 1024
    private const val DL_BUF = 8 * 1024
    private const val DL_UA = "Mozilla/5.0 (Linux; Android 14; PJD110) AppleWebKit/537.36 Mobile"

    private data class Config(
        val autoExport: Boolean,
        val root: String,
        val list: List<String>,
    )

    @Volatile
    private var config = Config(false, Prefs.DEFAULT_EXPORT_PATH, emptyList())

    @Volatile
    private var cfgAt = 0L

    /** 上次导出时刻（Kv 持久化；0 = 从没导出过 → 第一轮 tick 就跑） */
    @Volatile
    private var lastRunAt = 0L

    @Volatile
    private var lastRunAtRead = 0L

    private val tickScheduled = AtomicBoolean(false)

    /** 单航班：上一轮没跑完，绝不并发再起一轮（写同一棵树会互相踩） */
    private val running = AtomicBoolean(false)

    private val runs = AtomicLong(0)
    private val written = AtomicLong(0)
    private val skipped = AtomicLong(0)
    private val listMiss = AtomicLong(0)
    private val errs = AtomicLong(0)
    // ⛔ 已删于 R20（A8 收敛）：imgProbeLogged / imgSeq —— 旧 md5 路线已三次真机证伪
    //    （R15 分桶 0.68% / R16 全盘 find 零结果 / R19 文件名是 20 位数字串不含 md5）。
    //    正解见 docs/_r19_a4_probe.md。证据见 docs/_r20_a8_del.md。

    // ---- 取图四态（方案 Q5：互斥穷尽，禁止静默吞）----
    /** 没找到目录：`cache/` 下扫不到 32 位 hash，或 `<hash>/sns` 不存在 */
    private val imgRootMiss = AtomicLong(0)
    /** 目录在但索引里没有该 mediaId 的文件 */
    private val imgFileMiss = AtomicLong(0)
    /** 文件找到了但读/拷抛异常（含超过 [MAX_MEDIA_BYTES]） */
    private val imgReadFail = AtomicLong(0)
    /** tmp + rename 成功 */
    private val imgCopied = AtomicLong(0)

    /**
     * 一条动态**解析不出任何 mediaId**（protobuf 里没有媒体 / 结构断裂）。
     *
     * ⚠️ 必须**独立于四态**：四态口径是「按 mediaId」，而这一项是「按动态」。
     * 混进 [imgFileMiss] 会让方案 Q5 判据 #5「四态之和 = 已尝试 mediaId 总数」失真
     * （V1 P2-1）。这条恰好也是 P0 的现场观测点：`imgNoMedia` 恒等于已导出动态数
     * ⇒ protobuf 链路没通。
     */
    private val imgNoMedia = AtomicLong(0)

    /**
     * 失败弹窗去重 latch（跨轮，见 [MomentsNotifyLogic.Latch]）。
     *
     * ⚠️ 取代旧的 `pathWarned` / `spaceWarned`：那俩是**轮内日志去重**（每轮开头重置，
     * 同一原因只打一行日志），不是「只弹一次窗」。弹窗需要**跨轮**按住，语义不同，
     * 所以换成独立 latch —— 失败原因→文案、弹/不弹决策在 [MomentsNotifyLogic]，
     * 真正上主线程弹窗在 [MomentsNotify]。
     */
    // ⛔ R19：`Latch` 换成 `Gate`。差别不只是名字 ——
    //    `Gate` 在 latch 之外还管「per-item 失败计数 + 连续故障轮数」的升级判据
    //    （专审 §2①：`WRITE` 弹 / `MKDIRS_ITEM` 不弹的划分站不住，改用阈值升级），
    //    并把「多轮只弹一次 / 未送达下轮重试」的接线搬进纯函数 `onFailure`
    //    （专审 §5.4：原来这些行为靠调用方手写三行，改坏了 13 条单测一条都不红）。
    private val notifyGate = MomentsNotifyLogic.Gate()

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    // ------------------------------------------------------------------ 安装

    /** 本功能**没有任何 hook**：纯后台轮询 + 落盘。签名留着是为接线一致 */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching { refresh() }
            .onFailure { LogX.w("$TAG export refresh failed: ${it.javaClass.simpleName}") }
        ensureTick(FIRST_TICK_MS)
        LogX.i("$TAG export installed enabled=${config.autoExport} images=$COPY_IMAGES")
    }

    fun refresh() {
        config = Config(
            autoExport = Kv.getBool(Keys.MOMENTS_AUTO_EXPORT),
            root = Kv.getString(Keys.MOMENTS_EXPORT_PATH).ifBlank { Prefs.DEFAULT_EXPORT_PATH },
            list = Kv.getList(Keys.MOMENTS_EXPORT_LIST),
        )
        cfgAt = android.os.SystemClock.elapsedRealtime()
        lastRunAtRead = 0L
    }

    private fun cfg(): Config {
        if (android.os.SystemClock.elapsedRealtime() - cfgAt > CFG_TTL_MS) refresh()
        return config
    }

    // ------------------------------------------------------------------ 调度

    private val ticker = object : Runnable {
        override fun run() {
            tickScheduled.set(false)
            runCatching { onTick() }
                .onFailure { LogX.w("$TAG export tick err: ${it.javaClass.simpleName}") }
            ensureTick(TICK_MS)
        }
    }

    private fun ensureTick(delay: Long) {
        if (tickScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(ticker, delay)
        }
    }

    private fun onTick() {
        val c = cfg()
        if (!c.autoExport) return
        val now = System.currentTimeMillis()
        if (now - lastRunAtRead > CFG_TTL_MS) {
            lastRunAt = Kv.getString(Keys.MOMENTS_LAST_EXPORT_AT).toLongOrNull() ?: 0L
            lastRunAtRead = now
        }
        if (!MomentsLogic.exportDue(lastRunAt, now)) return
        runAsync()
    }

    /**
     * 起一轮导出（后台守护线程，单航班）。
     *
     * ⚠️ 整个线程体必须包 runCatching：后台线程里逃逸出去的异常没人接，
     * 会走默认 UncaughtExceptionHandler 把微信杀掉。
     */
    fun runAsync() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                runCatching { LogX.i(runBlocking()) }
                    .onFailure {
                        LogX.w("$TAG export failed: ${it.javaClass.simpleName}: " +
                            "${(it.cause ?: it).message?.take(80)}")
                        // 整轮异常 = 未知故障，值得弹窗（按 THROW 原因去重）
                        notify(MomentsNotifyLogic.Reason.THROW)
                    }
            } finally {
                // ⚠️ finally 复位：漏了它一次异常之后就再也不会导出（标志永久卡住）
                running.set(false)
            }
        }.apply { isDaemon = true; name = "moments-export" }.start()
    }

    // ------------------------------------------------------------------ 导出一轮

    /**
     * 记一笔「本轮发生了该类值得弹窗的故障」，并按跨轮 [notifyLatch] 决定是否真的弹。
     *
     * ## ⛔ 返回值语义（R18 复核修正，2026-10-24）
     * `@return` **本轮是否发生了该类故障**，与「这次有没有真的弹窗」**无关**。
     *
     * 早先的写法是「返回有没有弹窗」，直接导致一个 P0：
     * ```
     * 第1轮：弹了        → 返回 true  → clean=false，latch 记住
     * 第2轮：latch 已记住 → 返回 false → clean=true  → resetAll() 把 latch 清了
     * 第3轮：又弹        → 返回 true  → ...
     * ```
     * ⇒ 同一持久性故障变成**每隔一轮弹一次**，正好是 latch 要防的东西。
     * 根因：把「本轮没弹」误当成「本轮成功」。判定成功必须看**有没有故障**，
     * 不能看**有没有弹窗** —— 去重是「要不要通知」，成功与否是「有没有出错」，两件事。
     *
     * ⚠️ 注意 `DB_NOT_READY` / `MKDIRS_ITEM` 仍返回 `false`：它们不算失败
     * （前者是启动早期瞬时态，后者是循环内单条偶发），
     * 这样 `db not ready` 的早退不会 reset latch，语义与 [MomentsNotifyLogic.isNotifiable] 一致。
     */
    private fun notify(reason: MomentsNotifyLogic.Reason): Boolean =
        // ⛔ R19：委派给 `Gate.onFailure`（纯函数，无 Android 依赖，可单测）。
        //    这里**不许**再手写 decide/markFired —— P0（每隔一轮弹一次）和 F2
        //   （受理未送达也占 latch）都出在手写接线上，而单测**覆盖不到**接线层
        //   （专审 §5.4：把 `MomentsExport.notify()` 改坏，13 条单测一条都不红）。
        //   sender 只做「投递」，送达结果由 Gate 内部回传决定要不要占 latch。
        notifyGate.onFailure(reason) { msg, cb -> MomentsNotify.notify(msg, cb) }

    /**
     * 同步跑一轮（探针在 io 线程里直接调）。返回一行摘要。
     *
     * 失败处理：每处失败都打日志 + 经 [notify] 弹窗（按原因去重）。
     * 日志去重用局部布尔（一轮一个，防循环内几百行刷屏）；弹窗去重用跨轮 [notifyGate]。
     * `db not ready` 直接返回不弹（见 [MomentsNotifyLogic.isNotifiable]）。
     */
    private fun runBlocking(): String {
        // ⛔ R19：一轮的**第一条语句**（在 5 条提前 return 之前）—— 清空本轮 per-item 失败计数。
        //    放在这里而不是结尾：早退路径走不到结尾，若放结尾会让计数跨轮累积。
        notifyGate.beginRound()
        // ⛔ R20 F-1：早退必须调 `abortRound()` 补上 streak 结算。
        //    这 5 条 return 绕过 `endRound(clean)`，而 `endRound` 干**两件**事：
        //    ① 结算 escalation streak ② clean 时 resetAll。
        //    早退**只该跳过 ②**（早退 = 失败轮，不该清 latch），**必须补上 ①**
        //    —— 否则 ESCALATE 类原因的 streak 恒 0 ⇒ 永久漏弹，且不崩、无日志、单测不红。
        //    包装成局部函数是为了让 5 个 return 点都只写一行、不会有人漏改。
        fun bail(msg: String): String {
            notifyGate.abortRound()
            return msg
        }
        val db = GroupDb.current() ?: return bail("$TAG export skip: db not ready")
        val c = cfg()
        val root = c.root
        val rootDir = File(root)

        // —— 路径检查：任何一步不满足都打日志 + 弹窗（去重见 notify），然后跳过 ——
        var pathLogged = false
        var spaceLogged = false
        var clean = true
        if (rootDir.exists() && !rootDir.isDirectory) {
            if (!pathLogged) {
                pathLogged = true
                LogX.w("$TAG export skip: $root is not a directory")
            }
            if (notify(MomentsNotifyLogic.Reason.NOT_DIR)) clean = false
            return bail("$TAG export skip: not-dir $root")
        }
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            if (!pathLogged) {
                pathLogged = true
                LogX.w("$TAG export skip: cannot create $root")
            }
            if (notify(MomentsNotifyLogic.Reason.MKDIRS)) clean = false
            return bail("$TAG export skip: mkdirs $root")
        }
        if (!rootDir.canWrite()) {
            if (!pathLogged) {
                pathLogged = true
                LogX.w("$TAG export skip: not writable $root")
            }
            if (notify(MomentsNotifyLogic.Reason.READONLY)) clean = false
            return bail("$TAG export skip: readonly $root")
        }
        if (rootDir.usableSpace < MomentsLogic.MIN_FREE_BYTES) {
            if (!spaceLogged) {
                spaceLogged = true
                LogX.w("$TAG export skip: free=${rootDir.usableSpace} < ${MomentsLogic.MIN_FREE_BYTES}")
            }
            if (notify(MomentsNotifyLogic.Reason.SPACE)) clean = false
            return bail("$TAG export skip: space ${rootDir.usableSpace}")
        }

        runs.incrementAndGet()
        // 索引**单轮建一次**（方案 Q2b）：真机 1029 个文件一轮 listFiles 是毫秒级，
        // 但每条动态重建会放大几百倍。开关关着就不建，别白扫盘。
        val snsIdx = if (COPY_IMAGES) buildSnsIndex() else null
        val exported = scanExportedIds(rootDir)
        val nickCache = HashMap<String, String>()
        val cur = SnsScanCursor()
        var rows = 0
        var w = 0
        var sk = 0
        var lm = 0
        var er = 0
        var sinceSpace = 0
        var stop = false
        // 对账用：两个源各扫到多少行 / 当前源降级过几次（真机一眼看出数据源对不对）
        var curRows = 0
        var archRows = 0
        var curNoDb = 0

        while (rows < MAX_ROWS_PER_RUN && !stop) {
            // ① 当前 SnsInfo（防撤回语义的主数据源）→ 扫完/拿不到 → ② 归档
            // ⛔ R20b（复核 §1-8）：这里原本是 `runCatching{...}.getOrNull()`，把
            //    「正常降级（返回 null）」与「反射/查询抛异常」混成同一个 null，且异常
            //    **零日志**。而下面 `page == null` 分支打的日志写死成
            //    `sns db not captured` —— 若 null 其实来自异常，这条日志就是**误导**
            //    （把「查询坏了」说成「句柄没拿到」），现场会往完全错的方向排查。
            //    ⇒ 补 `.onFailure` 留痕，两种 null 来源从此在日志里可区分。
            //    仍保留 `.getOrNull()` 的降级语义（异常 → 退回扫归档，不中断本轮）。
            val page: List<GroupDb.SnsRow>? = when (cur.src) {
                SnsScanCursor.Src.CURRENT ->
                    runCatching { SnsGuard.listCurrentSns(cur.ct, cur.sid, PAGE) }
                        .onFailure {
                            LogX.w(
                                "$TAG export source=current THREW ${it.javaClass.simpleName}: " +
                                    "${(it.cause ?: it).message?.take(80)} -> archive only"
                            )
                        }
                        .getOrNull()
                SnsScanCursor.Src.ARCHIVE ->
                    runCatching { db.listSnsArchiveBefore(cur.ct, cur.sid, PAGE) }.getOrNull()
            }
            if (page == null) {
                // 只有 CURRENT 源会返回 null：微信这次启动还没碰过朋友圈库（句柄未捕获）。
                // ⚠️ 这是**预期内的降级**，不是故障 —— 只打日志、不弹窗、不判本轮失败。
                curNoDb++
                if (curNoDb <= 1) {
                    LogX.i("$TAG export source=current UNAVAILABLE (sns db not captured) -> archive only")
                }
                if (!cur.nextSource()) break
                continue
            }
            if (page.isEmpty()) {
                if (!cur.nextSource()) break
                continue
            }
            if (cur.src == SnsScanCursor.Src.CURRENT) curRows += page.size else archRows += page.size
            for (row in page) {
                rows++
                // ⚠️ 游标必须在任何 continue 之前推进（去重跳过也要推，否则死循环）
                cur.advanceTo(row.createTime, row.snsId)
                // 去重：磁盘已导出 + 本轮已处理（含跨源：归档里可能有同一条）
                if (!cur.first(row.snsId, exported)) {
                    sk++
                    continue
                }
                val nick = nickOf(row.userName, nickCache)
                if (!MomentsLogic.listHit(c.list, row.userName, nick)) {
                    lm++
                    continue
                }
                val dir = File(MomentsLogic.exportDir(root, nick, row.userName, MomentsLogic.millisOf(row.createTime)))
                if (!dir.exists() && !dir.mkdirs()) {
                    er++
                    if (!pathLogged) {
                        pathLogged = true
                        LogX.w("$TAG mkdirs failed: ${dir.path}")
                    }
                    // 单条目录创建失败：偶发（坏昵称等），不弹（见 isNotifiable），er 计数已可见
                    continue
                }
                val txt = File(dir, MomentsLogic.textFileName(row.snsId))
                // 扫描有上限（SCAN_MAX_IDS），兜底再问一次磁盘
                if (txt.exists()) {
                    exported.add(row.snsId)
                    sk++
                    continue
                }
                if (sinceSpace >= SPACE_CHECK_EVERY) {
                    sinceSpace = 0
                    if (dir.usableSpace < MomentsLogic.MIN_FREE_BYTES) {
                        if (!spaceLogged) {
                            spaceLogged = true
                            LogX.w("$TAG export abort: free=${dir.usableSpace} < ${MomentsLogic.MIN_FREE_BYTES}")
                        }
                        if (notify(MomentsNotifyLogic.Reason.SPACE_ABORT)) clean = false
                        stop = true
                        break
                    }
                }
                sinceSpace++
                val item = MomentsLogic.Item(
                    snsId = row.snsId,
                    wxid = row.userName,
                    nick = nick,
                    createTime = row.createTime,
                    type = row.type,
                    sourceType = row.sourceType,
                    localFlag = row.localFlag,
                    content = row.content,
                )
                val tmp = File(dir, txt.name + ".tmp")
                val ok = runCatching {
                    tmp.writeText(MomentsLogic.renderText(item))
                    tmp.renameTo(txt)
                }.getOrDefault(false)
                if (ok) {
                    exported.add(row.snsId)
                    w++
                    written.incrementAndGet()
                    // ⛔ R20（A8 收敛）：这里原来调 `logImgProbe(row, nick)`（旧 md5 探针），已删。
                    // 图片：跟文本同一轮、同一目录落盘（文本去重 = 图片去重，见方案 Q6）
                    if (snsIdx != null) copyImagesForRow(snsIdx, row, dir)
                } else {
                    er++
                    errs.incrementAndGet()
                    runCatching { tmp.delete() }
                    if (!pathLogged) {
                        pathLogged = true
                        LogX.w("$TAG write failed: ${txt.path}")
                    }
                    if (notify(MomentsNotifyLogic.Reason.WRITE)) clean = false
                }
            }
            if (page.size < PAGE) break
        }
        skipped.addAndGet(sk.toLong())
        listMiss.addAndGet(lm.toLong())

        val now = System.currentTimeMillis()
        Kv.putString(Keys.MOMENTS_LAST_EXPORT_AT, now.toString())
        lastRunAt = now
        lastRunAtRead = now
        // 本轮 clean（无任何「值得弹窗」的故障）→ 成功导出，复位所有原因的弹窗 latch。
        // ⛔ R19：改调 `Gate.endRound(clean)` —— 它除了复位 latch，还要结算
        //    「连续故障轮数」（per-item 阈值升级判据的计数基础）。
        // ⚠️ 上面 5 条提前 return **绕过**这里（早退 = 失败轮，不该 reset latch），
        //    但它们各自走了 `bail()` ⇒ 调 `abortRound()` 补上 streak 结算（R20 F-1）。
        //    ⇒ 「绕过 endRound」现在**只剩「不 reset」这一半**，另一半已补齐。
        notifyGate.endRound(clean)
        return "$TAG export done rows=$rows written=$w skipped=$sk listMiss=$lm err=$er root=$root"
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 扫导出目录，收集已导出的 snsId 集合（去重的依据）。
     *
     * 深度优先遍历，带guard：目录被用户改坏（环状符号链接之类）也不会死循环。
     */
    private fun scanExportedIds(root: File): MutableSet<Long> {
        val out = HashSet<Long>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 200_000) {
            guard++
            val d = stack.removeLast()
            val kids = runCatching { d.listFiles() }.getOrNull() ?: continue
            for (k in kids) {
                if (k.isDirectory) {
                    stack.addLast(k)
                } else {
                    val id = MomentsLogic.parseExportedId(k.name)
                    if (id != null) {
                        out.add(id)
                        if (out.size >= SCAN_MAX_IDS) return out
                    }
                }
            }
        }
        return out
    }

    /**
     * wxid → 显示名。微信规则：有备注显示备注，否则昵称。
     * friend_cache 由通讯录同步维护，查不到就返回 null（目录退化成纯 wxid）。
     */
    private fun nickOf(wxid: String?, cache: MutableMap<String, String>): String? {
        val w = wxid?.trim().orEmpty()
        if (w.isEmpty()) return null
        cache[w]?.let { return it }
        val f = runCatching { GroupDb.current()?.findFriend(w) }.getOrNull()
        val remark = f?.remark?.trim().orEmpty()
        val nickname = f?.nickname?.trim().orEmpty()
        val n = when {
            remark.isNotEmpty() -> remark
            nickname.isNotEmpty() -> nickname
            else -> null
        }
        if (n != null) cache[w] = n
        return n
    }

    // ⛔ 已删于 R20（A8 收敛）：logImgProbe() / imgRoots() —— 旧 md5 路线已三次真机证伪
    //    （R15 分桶 0.68% / R16 全盘 find 零结果 / R19 文件名是 20 位数字串不含 md5）。
    //    正解见 docs/_r19_a4_probe.md。证据见 docs/_r20_a8_del.md。
    //    ⚠️ `MomentsLogic.imageRootCandidates()` 本身**没删**：`MomentsLogicTest` 有 4 条活
    //    @Test 在测它，删了测试直接红。

    // -------------------------------------------------- SNS 磁盘缓存直读（R15 · Q2b）

    /**
     * 本次导出作用域内的文件名索引。
     *
     * 为什么不在每条动态上重建：真机 sns 下 1029 个文件 / 19 个桶目录，一轮 `listFiles`
     * 是毫秒级，但每条动态重建就是几百倍放大，导出会被 IO 拖死。
     *
     * 为什么要「索引直扫」而不是算分桶公式：`MD5(mediaId)[2]/[8]` 在真机 1029 个文件上
     * 只命中 0.68%（方案 Q2 已作废），且 mediaId 长度实测有 8 种（20~28），
     * 任何定长截断公式都会在边界翻车。
     */
    private class SnsIndex(
        /** 文件名 → File（只有白名单前缀的进表，`wxid_` 头像等已被剔除） */
        val files: Map<String, File>,
        /** 文件名集合（匹配只需要名字，[MomentsLogic.matchSnsFileName] 就吃这个） */
        val names: Set<String>,
        /** 是否真的扫到了某个 `<hash32>/sns` 目录；false ⇒ 取图一律归 imgRootMiss */
        val rootFound: Boolean,
    )

    /** 单张图的取图结果（方案 Q5 四态，互斥穷尽） */
    private enum class ImgOutcome { ROOT_MISS, FILE_MISS, READ_FAIL, COPIED }

    /**
     * 单张图的取图结果 + **命中的缓存文件名**。
     *
     * 带 [hit] 只是因为对账日志要打 `hit=<文件名|NONE>`；四态判定仍只看 [outcome]。
     * 之所以不让调用方自己再匹配一遍：那会把索引全表扫两次（真机 1029 个文件）。
     */
    private class ImgResult(val outcome: ImgOutcome, val hit: String?)

    /**
     * 建朋友圈图片缓存索引：`<ext>/cache/<hash32>/sns/<hex1>/<hex2>/<fileName>`。
     *
     * 只收 [MomentsLogic.isSnsMediaName] 为真的名字（`snsb_`/`snsu_`/`snst_`，
     * 排除 `snstblur_`/`wxid_`/`sight_`），所以后续匹配天然不会命中头像和视频。
     *
     * ⚠️ 每一层 [File.listFiles] 都**显式判空 + 打一行 w 日志**（V1 P2-2）：
     * 返回 null 表示「读不了这个目录」（不存在 / 无权限 / IO 出错），跟「空目录」
     * 是两件事。旧写法 `runCatching{...}.getOrNull().orEmpty()` 把两者混成空，
     * 真机上会表现成「明明有文件却全部 imgFileMiss」，且无从定位 —— 项目禁止静默吞。
     */
    private fun buildSnsIndex(): SnsIndex {
        val files = HashMap<String, File>(2048)
        val empty = SnsIndex(emptyMap(), emptySet(), false)

        val extRoot = externalRoot()
        if (extRoot == null) {
            LogX.w("$TAG sns index: external storage unavailable")
            return empty
        }
        val cacheRoot = File(extRoot, "cache")
        if (!cacheRoot.isDirectory) {
            LogX.w("$TAG sns index: no cache dir ${cacheRoot.path}")
            return empty
        }

        val cacheList = cacheRoot.listFiles()
        if (cacheList == null) {
            LogX.w("$TAG sns index: listFiles failed at cache layer path=${cacheRoot.path}")
            return empty
        }
        val hashDirs = cacheList.filter { it.isDirectory && isHex32(it.name) }
        if (hashDirs.isEmpty()) {
            LogX.w("$TAG sns index: no 32-hex dir under ${cacheRoot.path}")
            return empty
        }

        var rootFound = false
        var skipped = 0
        var listFail = 0
        for (h in hashDirs) {
            val sns = File(h, "sns")
            if (!sns.isDirectory) continue
            rootFound = true
            // 两级桶：<hex1>/<hex2>/<file>
            val b1s = sns.listFiles()
            if (b1s == null) {
                listFail++
                LogX.w("$TAG sns index: listFiles failed at sns layer path=${sns.path}")
                continue
            }
            for (b1 in b1s) {
                if (!b1.isDirectory) continue
                val b2s = b1.listFiles()
                if (b2s == null) {
                    listFail++
                    LogX.w("$TAG sns index: listFiles failed at b1 layer path=${b1.path}")
                    continue
                }
                for (b2 in b2s) {
                    if (!b2.isDirectory) continue
                    val fs = b2.listFiles()
                    if (fs == null) {
                        listFail++
                        LogX.w("$TAG sns index: listFiles failed at b2 layer path=${b2.path}")
                        continue
                    }
                    for (f in fs) {
                        if (!f.isFile) continue
                        if (!MomentsLogic.isSnsMediaName(f.name)) continue
                        if (files.containsKey(f.name)) {
                            skipped++
                            continue
                        }
                        files[f.name] = f
                    }
                }
            }
        }
        if (!rootFound) {
            LogX.w("$TAG sns index: no sns dir under ${cacheRoot.path}")
            return empty
        }
        LogX.i(
            "$TAG sns index built files=${files.size} dupSkipped=$skipped " +
                "hashDirs=${hashDirs.size} listFail=$listFail"
        )
        return SnsIndex(files, files.keys, true)
    }

    private fun isHex32(s: String): Boolean {
        if (s.length != 32) return false
        for (c in s) {
            val ok = (c in '0'..'9') || (c in 'a'..'f')
            if (!ok) return false
        }
        return true
    }

    private fun externalRoot(): String? = runCatching {
        android.os.Environment.getExternalStorageDirectory().absolutePath +
            "/Android/data/com.tencent.mm"
    }.getOrNull()

    /**
     * 从缓存把一张图拷到 [dst]（先写 `.tmp` 再 rename，[dst] 已存在先删）。
     *
     * 四态**互斥穷尽**：每个调用必落入且仅落入 [ImgOutcome] 的一项，
     * 并且每一项都在调用方显式计数 + 打一行日志（项目硬规矩：禁止 `?: return` 静默吞）。
     *
     * 返回 [ImgResult]：除四态外带上**命中的文件名**（对账日志要 `hit=<名|NONE>`）。
     */
    private fun copyOneFromCache(idx: SnsIndex, mediaId: String, dst: File): ImgResult {
        if (!idx.rootFound) return ImgResult(ImgOutcome.ROOT_MISS, null)
        if (mediaId.isEmpty()) return ImgResult(ImgOutcome.FILE_MISS, null)

        var src: File? = null
        var hitName: String? = null
        for (cand in MomentsLogic.snsNameCandidates(mediaId)) {
            val n = MomentsLogic.matchSnsFileName(idx.names, cand)
            if (n != null) {
                src = idx.files[n]
                hitName = n
                break
            }
        }
        val s = src ?: return ImgResult(ImgOutcome.FILE_MISS, null)
        if (s.length() > MAX_MEDIA_BYTES) {
            // 缓存里 snsb_ 最大 1.5MB，到这个量级多半读错了文件
            LogX.w("$TAG img too large name=$hitName bytes=${s.length()} > $MAX_MEDIA_BYTES")
            return ImgResult(ImgOutcome.READ_FAIL, hitName)
        }

        val tmp = File(dst.path + ".tmp")
        try {
            val parent = dst.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                LogX.w("$TAG img mkdirs failed dir=${parent.path}")
                return ImgResult(ImgOutcome.READ_FAIL, hitName)
            }
            // 真机踩过：dst 已存在时 renameTo 会失败，必须先删
            if (dst.exists() && !dst.delete()) {
                LogX.w("$TAG img cannot replace dst=${dst.name}")
                return ImgResult(ImgOutcome.READ_FAIL, hitName)
            }
            var n = 0L
            FileInputStream(s).use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(DL_BUF)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        n += r
                        if (n > MAX_MEDIA_BYTES) {
                            LogX.w("$TAG img abort oversized name=$hitName bytes>$MAX_MEDIA_BYTES")
                            runCatching { tmp.delete() }
                            return ImgResult(ImgOutcome.READ_FAIL, hitName)
                        }
                    }
                }
            }
            if (!tmp.renameTo(dst)) {
                runCatching { tmp.delete() }
                LogX.w("$TAG img rename failed name=$hitName dst=${dst.name}")
                return ImgResult(ImgOutcome.READ_FAIL, hitName)
            }
            return ImgResult(ImgOutcome.COPIED, hitName)
        } catch (t: Throwable) {
            // 半成品必须清掉，否则下次 exists() 会把它当成「已拷过」
            runCatching { tmp.delete() }
            LogX.w("$TAG img copy threw name=$hitName ${t.javaClass.simpleName}")
            return ImgResult(ImgOutcome.READ_FAIL, hitName)
        }
    }

    /**
     * 一条动态的 mediaId 列表（`i40.h` 里每个媒体对象一个 `vm3.d`）。
     *
     * **已接通**（R15-I2）：走 [MomentsLogic.mediaIdsFromContent] 的通用线格式解析
     * （`content` 字段6 → ContentObj，ContentObj 字段8 → 媒体列表，元素字段1 → `d`），
     * 按字段号手走字节，**不反射 `vm3.parseFrom`、不依赖混淆类名**（升级不会失效）。
     *
     * **契约**：返回 `vm3.d` **原值**（不砍前缀、不截断），顺序 = 字节顺序。
     * `content` 为空 / 结构断裂 / 无该字段 ⇒ `emptyList()`，不抛异常
     * （上层 [copyImagesForRow] 用 [imgNoMedia] 显式计数，绝不静默）。
     */
    private fun mediaIdsOf(row: GroupDb.SnsRow): List<String> =
        MomentsLogic.mediaIdsFromContent(row.content)

    /**
     * 把一条动态能取到的图从 SNS 磁盘缓存拷进它的导出目录。
     *
     * 语义（方案 Q3）：原图优先、逐级回退 —— [MomentsLogic.snsNameCandidates] 已按
     * `snsb_`(原图) > `snsu_`(压缩) > `snst_`(缩略) 排好序，第一个命中的就是最想要的那张。
     * 三者都没有 ⇒ `imgFileMiss`，**不回退 CDN url**（图片 CDN 裸 HTTP 恒 400，见方案 Q3）。
     *
     * 每个 mediaId 都打一行 `img mid` 对账日志（含 `raw` / `pre` / `cand0` / `hit`），
     * 真机上一眼就能确认 `fliterPre` **只砍了一次**（方案 Q5 判据 #1，V1 明确要求）。
     */
    private fun copyImagesForRow(idx: SnsIndex, row: GroupDb.SnsRow, dir: File) {
        val ids = mediaIdsOf(row)
        if (ids.isEmpty()) {
            // 动态级口径：解析不出 mediaId ⇒ 独立计数，不混进按 mediaId 计的四态（V1 P2-1）
            imgNoMedia.incrementAndGet()
            LogX.i("$TAG img noMediaId snsId=${row.snsId} (protobuf 无媒体/结构断裂)")
            return
        }
        for ((slot, mid) in ids.withIndex()) {
            val dst = File(dir, MomentsLogic.imageFileName(row.snsId, slot, "jpg"))
            val res = runCatching { copyOneFromCache(idx, mid, dst) }.getOrElse {
                // copyOneFromCache 自己已兜异常；这里只防意料外的运行时异常逃出去
                LogX.w("$TAG img unexpected ${it.javaClass.simpleName} snsId=${row.snsId} #$slot")
                ImgResult(ImgOutcome.READ_FAIL, null)
            }
            // ⚠️ 必须**每条都打**（含 NONE）：这是现场判定「只砍一次」的唯一证据。
            // 格式定死，勿加字段（真机对账脚本按列取）：
            //   MOMENT img mid snsId=.. raw=.. pre=.. cand0=.. hit=..
            val pre = MomentsLogic.fliterPre(mid)
            val cand0 = MomentsLogic.snsNameCandidates(mid).firstOrNull() ?: "NONE"
            LogX.i(
                "$TAG img mid snsId=${row.snsId} raw=$mid pre=$pre " +
                    "cand0=$cand0 hit=${res.hit ?: "NONE"}"
            )
            countImg(res.outcome, row.snsId, slot, dst)
        }
    }

    /** 四态计数 + 一行日志（互斥穷尽：`when` 不给 else 分支外的静默出口） */
    private fun countImg(outcome: ImgOutcome, snsId: Long, slot: Int, dst: File) {
        when (outcome) {
            ImgOutcome.ROOT_MISS -> {
                imgRootMiss.incrementAndGet()
                LogX.w("$TAG img rootMiss snsId=$snsId #$slot")
            }
            ImgOutcome.FILE_MISS -> {
                imgFileMiss.incrementAndGet()
                LogX.i("$TAG img fileMiss snsId=$snsId #$slot")
            }
            ImgOutcome.READ_FAIL -> {
                imgReadFail.incrementAndGet()
                LogX.w("$TAG img readFail snsId=$snsId #$slot dst=${dst.name}")
            }
            ImgOutcome.COPIED -> {
                imgCopied.incrementAndGet()
                LogX.i("$TAG img copied snsId=$snsId #$slot dst=${dst.name}")
            }
        }
    }

    // ⛔ 已删于 R20（A8 收敛）：tryCopyImages() —— 旧 md5 路线已三次真机证伪
    //    （R15 分桶 0.68% / R16 全盘 find 零结果 / R19 文件名是 20 位数字串不含 md5）。
    //    正解见 docs/_r19_a4_probe.md。证据见 docs/_r20_a8_del.md。
    //    原 `op=moments sub=imgtry` 探针一并下架；新链路对账请用 `sub=snsprobe`。

    /**
     * **新链路探针**：`sub=snsprobe` 专用，**完全不依赖 [COPY_IMAGES]**。
     *
     * 存在的理由：`COPY_IMAGES` 必须等真机验完才敢开，但「验」本身不能依赖那个开关 ——
     * 否则 `false` 时 [copyImagesForRow] 根本不跑，protobuf 与索引匹配无从验证。
     * 本探针对最近 n 条归档跑一遍**只读**的完整链路，把每步结果打出来：
     *
     * ```
     * MOMENT snsprobe idx rootFound=.. names=..
     * MOMENT snsprobe snsId=.. ids=.. raw=.. pre=.. cand0=.. hit=..
     * MOMENT snsprobe sum rows=.. noMedia=.. withIds=.. hit=.. miss=..
     * ```
     *
     * ⚠️ 只读：**不拷贝任何文件**（`hit` 只表示「索引里匹配到了文件名」）。
     * 判据：
     * - `ids=0` 且 `noMedia` 接近 `rows` ⇒ **字段号错**（改 `MomentsLogic.PROTO_FIELD_*`）
     * - `ids>0` 但 `hit=NONE` ⇒ mediaId 拿到了但文件名不匹配（看 `raw`/`pre` 与真机文件名对比）
     * - `hit=<name>` ⇒ **链路通**，可以开 `COPY_IMAGES` 上 M1
     */
    /** 探针专用：content 头部 hex（判字段号用，不参与业务路径） */
    private fun hexHead(b: ByteArray, n: Int): String {
        val m = minOf(n, b.size)
        val sb = StringBuilder(m * 2)
        for (i in 0 until m) sb.append("%02x".format(b[i].toInt() and 0xff))
        return sb.toString()
    }

    /**
     * 探针专用：枚举 **顶层** protobuf 字段号（只扫一层，不递归）。
     *
     * 为什么要有它：判断「字段号错」必须知道 content 顶层**实际有哪些字段**，
     * 而不是猜 `PROTO_FIELD_CONTENT_OBJ=6` 对不对。格式 `6:2,10:2` = 字段号:wiretype。
     */
    private fun topFieldNums(b: ByteArray, maxOut: Int = 40): String {
        val seen = LinkedHashSet<String>()
        var i = 0
        var guard = 0
        while (i < b.size && guard++ < 400 && seen.size < maxOut) {
            val t = MomentsLogic.readVarintPublic(b, i) ?: break
            var pos = t.second
            val tag = t.first
            if (tag == 0L) break
            val f = (tag ushr 3).toInt()
            val wt = (tag and 0x07L).toInt()
            seen.add("$f:$wt")
            when (wt) {
                0 -> pos = (MomentsLogic.readVarintPublic(b, pos) ?: break).second
                1 -> pos += 8
                2 -> {
                    val lv = MomentsLogic.readVarintPublic(b, pos) ?: break
                    pos = lv.second
                    val len = lv.first
                    if (len < 0 || len > (b.size - pos).toLong()) break
                    pos += len.toInt()
                }
                5 -> pos += 4
                else -> break
            }
            if (pos > b.size) break
            i = pos
        }
        return seen.joinToString(",")
    }

    /**
     * 探针专用：完整扫一遍线格式，报告**扫描终止位置与原因**。
     *
     * 为什么要有它：`protoSegments` 结构断裂时 `return emptyList()`，
     * 从外部看不出是「扫完了但没这个字段」还是「中途断了」。
     * 输出 `ok=扫完 / break@偏移量` 可区分这两种。
     */
    private fun scanEnd(b: ByteArray): String {
        var i = 0
        var guard = 0
        while (i < b.size && guard++ < 4000) {
            val t = MomentsLogic.readVarintPublic(b, i) ?: return "varintFail@$i"
            var pos = t.second
            val tag = t.first
            if (tag == 0L) return "tag0@$i"
            val wt = (tag and 0x07L).toInt()
            when (wt) {
                0 -> pos = (MomentsLogic.readVarintPublic(b, pos) ?: return "varintFail@$pos").second
                1 -> pos += 8
                2 -> {
                    val lv = MomentsLogic.readVarintPublic(b, pos) ?: return "lenVarintFail@$pos"
                    pos = lv.second
                    val len = lv.first
                    if (len < 0 || len > (b.size - pos).toLong()) return "lenOverrun@$pos(len=$len)"
                    pos += len.toInt()
                }
                5 -> pos += 4
                else -> return "badWiretype$wt@$i"
            }
            if (pos > b.size) return "overrun@$pos"
            i = pos
        }
        return if (i >= b.size) "ok(${b.size})" else "guardOut@$i"
    }

    /**
     * 探针专用：取 `b` 里字段 [field] 的第一个段，**输出它内部的顶层字段号**（只一层）。
     *
     * 用来定位「mediaList 到底在哪一层」：R19 实测 content 顶层就有字段 8，
     * 而 `mediaIdsFromContent` 是在字段 6 内部找字段 8 —— 两者必须实测区分。
     *
     * @return `-` = 该字段不存在；`[]` = 存在但内部扫不出字段。
     */
    private fun nestedTop(b: ByteArray, field: Int): String {
        val seg = MomentsLogic.protoSegments(b, field).firstOrNull() ?: return "-"
        return "[" + topFieldNums(seg, 10) + "]"
    }

    /**
     * 探针专用：字段 6 内各 wiretype=2 字段的**内容预览**（找 mediaId 在哪）。
     *
     * R19 实测：字段 6 内部是 `1:5,2:5,3:2,4:2,5:2,6:2,7:0,9:0,10:0,11:0`
     * —— **没有字段 8**，所以 `PROTO_FIELD_MEDIA_LIST=8` 恒取不到（这就是 ids=0 的真因）。
     * mediaId 的特征是 20~28 位字符串 ⇒ 用预览逐个认。
     *
     * 输出形如 `3=len20:15033764437568787118;4=len12:(非文本)`。
     */
    private fun in6dump(content: ByteArray): String {
        val obj = MomentsLogic.protoSegments(content, 6).firstOrNull() ?: return "-"
        val sb = StringBuilder()
        for (f in intArrayOf(3, 4, 5, 6, 9, 10, 11)) {
            val seg = MomentsLogic.protoSegments(obj, f).firstOrNull() ?: continue
            val s = runCatching { String(seg, Charsets.UTF_8) }.getOrNull()
            val printable = s != null && s.all { it.code in 32..126 }
            val prev = if (printable) s!!.take(22) else "bin:" + hexHead(seg, 6)
            if (sb.isNotBlank()) sb.append(';')
            sb.append("$f=len${seg.size}:$prev")
            if (sb.length > 150) break
        }
        return if (sb.isEmpty()) "empty" else sb.toString()
    }

    private fun probeSnsCache(limit: Int): List<String> {
        val out = ArrayList<String>(limit + 3)
        val idx = buildSnsIndex()
        out.add(
            "$TAG snsprobe idx rootFound=${idx.rootFound} names=${idx.names.size} " +
                "files=${idx.files.size}"
        )
        val db = GroupDb.current()
        if (db == null) {
            out.add("$TAG snsprobe no db")
            return out
        }
        val rows = runCatching { db.listSnsArchive(limit) }.getOrElse {
            out.add("$TAG snsprobe listArchive failed ${it.javaClass.simpleName}")
            return out
        }
        if (rows.isEmpty()) {
            out.add("$TAG snsprobe no archived rows")
            return out
        }

        var noMedia = 0
        var withIds = 0
        var hitRows = 0
        for (row in rows) {
            val ids = mediaIdsOf(row)
            if (ids.isEmpty()) {
                noMedia++
                // content 非空却解不出 ⇒ 最可能是字段号错。
                // ⛔ 只打长度**不足以**判断字段号（R19 真机实测：7 条全长 841~3122B
                //    却一条 mediaId 都解不出，光看长度无法区分「字段号错」与「样本无图」）。
                //    ⇒ 追加**头部 hex + 顶层字段号枚举**，让判据可自证。
                val cb = row.content
                // ⛔ seg6 / scan 是判断根因的关键（R19）：
                //    `protoSegments` 结构断裂时 **return emptyList()**（整段作废，含已收集到的字段6），
                //    而 `topFieldNums` 是 **break**（保留已收集）。两者不一致 ⇒
                //    「topFields 里有 6」但「protoSegments(6) 为空」是完全可能的，
                //    症状就是 ids=0 且看起来字段号没错。
                // ⛔ 单行日志有 295 字符硬上限（R19 实测），`head`/`topFields` 会把
                //    真正关键的 `in6`/`top8` 挤掉 ⇒ 本轮只留关键字段，次要的先去掉。
                out.add(
                    "$TAG snsprobe snsId=${row.snsId} ids=0 content=${cb?.size ?: -1}B " +
                        "seg6=${if (cb == null) "-" else MomentsLogic.protoSegments(cb, 6).size} " +
                        "scan=${if (cb == null) "-" else scanEnd(cb)} " +
                        // ⛔ 层级取证（R19 核心疑点）：content **顶层就有字段 8**，
                        //    而 mediaIdsFromContent 是在**字段6 内部**找字段8。
                        //    若 mediaList 实际在顶层 ⇒ 层级错了，这才是 ids=0 的真因。
                        "in6=${if (cb == null) "-" else nestedTop(cb, 6)} " +
                        "d6=${if (cb == null) "-" else in6dump(cb)}"
                )
                continue
            }
            withIds++
            var rowHit = false
            for ((slot, mid) in ids.withIndex()) {
                val pre = MomentsLogic.fliterPre(mid)
                val cand0 = MomentsLogic.snsNameCandidates(mid).firstOrNull() ?: "NONE"
                val hitName = MomentsLogic.matchSnsFileName(idx.names, cand0)
                    ?: MomentsLogic.snsNameCandidates(mid)
                        .firstNotNullOfOrNull { MomentsLogic.matchSnsFileName(idx.names, it) }
                if (hitName != null) rowHit = true
                out.add(
                    "$TAG snsprobe snsId=${row.snsId} ids=${ids.size} #$slot " +
                        "raw=$mid pre=$pre cand0=$cand0 hit=${hitName ?: "NONE"}"
                )
            }
            if (rowHit) hitRows++
        }
        out.add(
            "$TAG snsprobe sum rows=${rows.size} noMedia=$noMedia withIds=$withIds hit=$hitRows"
        )
        return out
    }

    // ------------------------------------------------------------------ 原图下载

    /**
     * 从 content 里的 CDN url **直接下载**原图（探针 `sub=imgdl`）。
     *
     * 为什么不再找本地缓存：见 [MomentsLogic.imageRootCandidates] —— 2026-10-18 设备实测
     * 4 个 md5 全盘 find 都搜不到，朋友圈的图**从未落盘**，`picked=NONE` 是必然结果，
     * 继续调目录规则是白费。
     *
     * ⚠️ url 带 `token` / `sign` / `svrnonce` ⇒ **有时效**。这里读的是 `sns_archive`
     * 归档里的老 url，**下载失败是预期结果，不是 bug**（新鲜 url 已 curl 验过 HTTP 200）。
     * 真要长期用，得在动态刚抓到时就下 —— 那是 [COPY_IMAGES] 打开时的事。
     */
    private fun downloadMedia(limit: Int): String {
        val db = GroupDb.current() ?: return "$TAG imgdl no db"
        val rows = runCatching { db.listSnsArchive(limit) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return "$TAG imgdl no archived rows"

        val outDir = File(cfg().root, "_imgdl")
        if (!outDir.exists() && !outDir.mkdirs()) return "$TAG imgdl mkdirs failed: ${outDir.path}"
        if (outDir.usableSpace < MomentsLogic.MIN_FREE_BYTES) {
            return "$TAG imgdl abort: free=${outDir.usableSpace} < ${MomentsLogic.MIN_FREE_BYTES}"
        }

        var rowsHit = 0
        var tried = 0
        var ok = 0
        var bytes = 0L
        for (row in rows) {
            val urls = MomentsLogic.mediaUrls(MomentsLogic.extractUrls(row.content))
            if (urls.isEmpty()) continue
            var rowOk = 0
            for ((i, u) in urls.withIndex()) {
                tried++
                val tmp = File(outDir, "tmp_${row.snsId}_$i")
                val r = downloadOne(u, tmp)
                // ⚠️ 别加「https 失败降级 http 重试」：2026-10-18 实测无效。
                // 同一条动态里 `251/20302/stodownload`(http) → 200 / 2.8MB，
                // 而 `251/20304/stodownload` 的 https 与 http **都是 000 或 400**。
                // ⇒ 失败的是资源本身（另一路编号 / 已过期），不是协议，重试白费一次超时。
                if (r.size <= 0) {
                    runCatching { tmp.delete() }
                    LogX.w("$TAG imgdl fail snsId=${row.snsId} #$i reason=${r.size} code=${r.code}")
                    continue
                }
                val kind = MomentsLogic.kindOfUrl(u)
                val dst = File(outDir, MomentsLogic.fileNameFor(row.snsId, i, kind, r.type))
                // dst 已存在时 renameTo 会失败（真机踩过「删了才成功」），先删
                if (dst.exists() && !dst.delete()) {
                    runCatching { tmp.delete() }
                    LogX.w("$TAG imgdl cannot replace dst=${dst.name}")
                    continue
                }
                if (!tmp.renameTo(dst)) {
                    runCatching { tmp.delete() }
                    LogX.w("$TAG imgdl rename failed dst=${dst.name}")
                    continue
                }
                ok++
                rowOk++
                bytes += r.size
            }
            if (rowOk > 0) rowsHit++
        }
        return "$TAG imgdl rows=${rows.size} rowsHit=$rowsHit tried=$tried ok=$ok " +
            "bytes=$bytes out=${outDir.path}"
    }

    /** 单个媒体的下载结果。`size<=0` 表示失败，取值即失败原因（见下方注释） */
    private class Dl(val size: Long, val type: String?, val code: Int)

    /**
     * 下一个 url 到临时文件。
     *
     * @return [Dl]，失败时 `size` 为负：`-1`=HTTP 非 2xx、`-2`=超过 [MAX_MEDIA_BYTES]、
     *         `-3`=抛异常（超时 / DNS / SSL）。失败时**调用方**负责删半成品。
     */
    private fun downloadOne(url: String, tmp: File): Dl {
        // 非 http(s) 一律别试：真机踩过 `ProtocolException`（BLOB 里会抽出非 http 的串），
        // 既浪费一次超时又污染日志
        if (!url.startsWith("http://") && !url.startsWith("https://")) return Dl(-4, null, -1)
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = DL_CONNECT_MS
                readTimeout = DL_READ_MS
                instanceFollowRedirects = true
                // 实测：对 shmmsns 图片无效（照样 400），但对 stodownload 类无害，
                // 留着是为了换 CDN 时少一轮取证
                setRequestProperty("User-Agent", DL_UA)
                setRequestProperty("Referer", "http://weixin.qq.com/")
            }
            val code = conn.responseCode
            if (code !in 200..299) return Dl(-1, null, code)

            val ct = conn.contentType
            // 先按声明长度挡一道；声明为 -1（分块传输）时只能边下边数
            val declared = conn.contentLengthLong
            if (declared > MAX_MEDIA_BYTES) return Dl(-2, ct, code)

            var n = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(DL_BUF)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        n += r
                        if (n > MAX_MEDIA_BYTES) return Dl(-2, ct, code)
                    }
                }
            }
            Dl(n, ct, code)
        } catch (t: Throwable) {
            LogX.w("$TAG imgdl threw ${t.javaClass.simpleName}")
            Dl(-3, null, -1)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ------------------------------------------------------------------ 诊断

    /**
     * 把最近 n 条归档的**完整** CDN url 打出来（探针 `sub=imgurl`，只读不下载）。
     *
     * 为什么需要单独一条：已删的旧探针 `logImgProbe` 里的 url 被 `take(48)` 截断，只看得到
     * `http://wxapp.tc.qq.com/251/20302/stodownload?enc` 这种半截，
     * **无法判断整条地址还能不能直接用**（后半段才是鉴权参数）。
     * 而 md5 那条路已被实测证伪（图没落盘），下载 url 成了唯一可行方向。
     *
     * 输出刻意分成三行，避免一行太长被日志环形缓冲截断：
     * 1. 汇总：几条动态 / 几个非头像 url / 几个头像 url
     * 2. 完整 url（可能是 `&` 连接的超长串，这里**不截断**，改由 n 控制条数）
     */
    private fun logImgUrls(limit: Int): String {
        val rows = GroupDb.current()?.listSnsArchive(limit).orEmpty()
        if (rows.isEmpty()) return "$TAG imgurl no archived rows"

        var all = 0
        var keep = 0
        var avatar = 0
        for (row in rows) {
            val urls = MomentsLogic.extractUrls(row.content)
            val real = urls.filterNot { MomentsLogic.isAvatarUrl(it) }
            all += urls.size
            keep += real.size
            avatar += urls.size - real.size
            // 每条动态打一行：snsId + 完整地址（多条用空格隔开，便于直接复制去 curl）
            LogX.i(
                "$TAG imgurl snsId=${row.snsId} total=${urls.size} real=${real.size} " +
                    "urls=[${real.joinToString(" ")}]"
            )
        }
        return "$TAG imgurl rows=${rows.size} urls=$all real=$keep avatar=$avatar"
    }

    fun stat(): String = runCatching {
        val c = cfg()
        "$TAG export stat enabled=${c.autoExport} images=$COPY_IMAGES running=$running " +
            "lastRunAt=$lastRunAt runs=${runs.get()} written=${written.get()} " +
            "skipped=${skipped.get()} listMiss=${listMiss.get()} err=${errs.get()} " +
            "imgRootMiss=${imgRootMiss.get()} imgFileMiss=${imgFileMiss.get()} " +
            "imgReadFail=${imgReadFail.get()} imgCopied=${imgCopied.get()} " +
            "imgNoMedia=${imgNoMedia.get()} " +
            "list=${c.list.size} root=${c.root}"
    }.getOrElse { "$TAG export stat error=${it.javaClass.simpleName}" }

    /**
     * 探针入口（`op=moments`）：
     * - `stat` 状态；`path` 当前路径与可写性/剩余空间
     * - `export` 立刻起一轮（绕过最小间隔；开关只挡自动轮，不挡手动诊断）
     * - `snsprobe` 新链路只读对账（原 `img` / `imgtry` 两个旧 md5 探针已于 R20 删除）
     * - `scan` 重扫导出目录，报告已导出条数
     */
    fun probe(sub: String, n: Int = 5): String = runCatching {
        when (sub.lowercase()) {
            "stat" -> stat()
            "path" -> {
                val c = cfg()
                val d = File(c.root)
                "$TAG path root=${c.root} exists=${d.exists()} dir=${d.isDirectory} " +
                    "writable=${d.canWrite()} free=${d.usableSpace} " +
                    "min=${MomentsLogic.MIN_FREE_BYTES}"
            }
            "export" -> {
                runAsync()
                "$TAG export triggered running=$running"
            }
            // ⛔ 已删于 R20（A8 收敛）：`img` / `imgtry` 两个旧 md5 路线探针分支
            //    （R15 分桶 0.68% / R16 全盘 find 零结果 / R19 文件名是 20 位数字串不含 md5）。
            //    正解见 docs/_r19_a4_probe.md。证据见 docs/_r20_a8_del.md。
            // R15-M1：**新链路只读探针**，不依赖 COPY_IMAGES（`false` 时也能验 protobuf + 索引匹配）
            "snsprobe" -> probeSnsCache(MomentsLogic.clampProbeN(n)).joinToString("\n")
            // R15：只读诊断 —— 索引能不能建起来、建了多少条（不取图、不写盘）
            "snsidx" -> {
                val idx = buildSnsIndex()
                "$TAG snsidx rootFound=${idx.rootFound} names=${idx.names.size} " +
                    "sample=${idx.names.take(3)}"
            }
            // ⚠️ 2026-10-18 实测：md5 找本地缓存全 NONE，图根本没下载到本机。
            // 所以加这条**只读**探针，把 content 里的完整 CDN url 原样打出来
            // （已删的旧探针 `logImgProbe` 里的 url 被 take(48) 截断了，看不出能不能直接 GET）。
            // 下一轮据此判断「下载原图」方案是否可行，不用改代码。
            "imgurl" -> logImgUrls(MomentsLogic.clampProbeN(n))
            // 真下载（联网）。n 建议小一点（默认 3），别一口气把几十条都下了
            "imgdl" -> downloadMedia(MomentsLogic.clampProbeN(n))
            "scan" -> {
                val ids = scanExportedIds(File(cfg().root))
                "$TAG scan exported=${ids.size} sample=${ids.take(5)}"
            }
            else -> "$TAG export subs=stat|path|export|snsidx|imgurl|imgdl|scan"
        }
    }.getOrElse { "$TAG export probe error=${it.javaClass.simpleName}" }
}
