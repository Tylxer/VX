package com.lx.wxgroup.feature

import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.Prefs
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 需求 6.4「朋友圈」——**自动导出**的 hook 侧。
 *
 * ## 数据链（全部落在已验证的资产上）
 * ```
 * sns_archive（SnsGuard 拦 DELETE 时归档的整行快照）
 *   → GroupDb.listSnsArchiveBefore 键集分页（一次一页，不把 BLOB 全读进内存）
 *   → MomentsLogic.renderText 渲染 text 文本
 *   → MomentsLogic.exportDir 拼 <root>/昵称(wxid)/yyyy-MM-dd-HHmm/
 *   → <snsId>.txt（tmp + rename 原子落盘）
 * ```
 * 去重靠扫导出目录里已存在的 `<snsId>.txt`（[MomentsLogic.parseExportedId]）；
 * 名单命中靠 [MomentsLogic.listHit]；空间下限靠 [MomentsLogic.MIN_FREE_BYTES]。
 *
 * ## ⚠️ 图片（原图）导出：命名规则**未取证**，本版不做
 * 微信磁盘上朋友圈缓存文件的命名规则还没取证（url 的 md5？snsId+序号？不知道）。
 * 所以 [COPY_IMAGES] 恒为 false，这里**只把线索打出来**：
 * 每条导出的动态会顺带 log 一行 `MOMENT img ... keys=[...] urls=[...]`
 * （候选串由 [MomentsLogic.mediaKeys] 生成），拿到真实文件名后对账，
 * 对上哪种规则再把复制逻辑写进来 —— **严禁猜规则硬写**。
 * 文本文件里已经带了媒体 URL 段（renderText 的「---- 媒体 ----」），内容不丢。
 *
 * ## 失败策略（任务约定，压过需求原文的「弹窗」）
 * 路径不存在 / 建不成功 / 写不进 / 空间不足 → **静默跳过 + 一行日志**，绝不崩微信。
 * （需求 6.4 写的是「失败只弹窗」，但后台线程弹 Dialog 要 Activity 上下文、
 *   会把微信撩到前台，比不弹更糟；这一条分歧已写进报告，等主代理定夺。）
 */
object MomentsExport {

    private const val TAG = "MOMENT"

    /**
     * 图片导出总闸。**false = 未取证，不导出图片**。
     *
     * 取证方式：`op=moments sub=img` 打出候选串，再跟真机上朋友圈缓存目录里的
     * 真实文件名对账；对上之后把规则写进 [copyImages] 并打开这里。
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

    /** 每轮最多给几条动态打图片候选日志（对账用，别把日志刷爆） */
    private const val IMG_PROBE_MAX = 3

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
    private val imgProbeLogged = AtomicInteger(0)

    @Volatile
    private var pathWarned = false

    @Volatile
    private var spaceWarned = false

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
                    }
            } finally {
                // ⚠️ finally 复位：漏了它一次异常之后就再也不会导出（标志永久卡住）
                running.set(false)
            }
        }.apply { isDaemon = true; name = "moments-export" }.start()
    }

    // ------------------------------------------------------------------ 导出一轮

    /** 同步跑一轮（探针在 io 线程里直接调）。返回一行摘要 */
    private fun runBlocking(): String {
        val db = GroupDb.current() ?: return "$TAG export skip: db not ready"
        val c = cfg()
        val root = c.root
        val rootDir = File(root)

        // —— 路径检查：任何一步不满足都静默跳过 + 一行日志 ——
        pathWarned = false
        spaceWarned = false
        if (rootDir.exists() && !rootDir.isDirectory) {
            if (!pathWarned) {
                pathWarned = true
                LogX.w("$TAG export skip: $root is not a directory")
            }
            return "$TAG export skip: not-dir $root"
        }
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            if (!pathWarned) {
                pathWarned = true
                LogX.w("$TAG export skip: cannot create $root")
            }
            return "$TAG export skip: mkdirs $root"
        }
        if (!rootDir.canWrite()) {
            if (!pathWarned) {
                pathWarned = true
                LogX.w("$TAG export skip: not writable $root")
            }
            return "$TAG export skip: readonly $root"
        }
        if (rootDir.usableSpace < MomentsLogic.MIN_FREE_BYTES) {
            if (!spaceWarned) {
                spaceWarned = true
                LogX.w("$TAG export skip: free=${rootDir.usableSpace} < ${MomentsLogic.MIN_FREE_BYTES}")
            }
            return "$TAG export skip: space ${rootDir.usableSpace}"
        }

        runs.incrementAndGet()
        imgProbeLogged.set(0)
        val exported = scanExportedIds(rootDir)
        val nickCache = HashMap<String, String>()
        var ct = Long.MAX_VALUE
        var sid = Long.MAX_VALUE
        var rows = 0
        var w = 0
        var sk = 0
        var lm = 0
        var er = 0
        var sinceSpace = 0
        var stop = false

        while (rows < MAX_ROWS_PER_RUN && !stop) {
            val page = runCatching { db.listSnsArchiveBefore(ct, sid, PAGE) }.getOrNull()
            if (page.isNullOrEmpty()) break
            for (row in page) {
                rows++
                ct = row.createTime
                sid = row.snsId
                if (exported.contains(row.snsId)) {
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
                    if (!pathWarned) {
                        pathWarned = true
                        LogX.w("$TAG mkdirs failed: ${dir.path}")
                    }
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
                        if (!spaceWarned) {
                            spaceWarned = true
                            LogX.w("$TAG export abort: free=${dir.usableSpace} < ${MomentsLogic.MIN_FREE_BYTES}")
                        }
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
                    if (imgProbeLogged.get() < IMG_PROBE_MAX) logImgProbe(row, nick)
                } else {
                    er++
                    errs.incrementAndGet()
                    runCatching { tmp.delete() }
                    if (!pathWarned) {
                        pathWarned = true
                        LogX.w("$TAG write failed: ${txt.path}")
                    }
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

    /**
     * 图片对账探针日志（[COPY_IMAGES]=false 时唯一的图片线索出口）。
     *
     * 把 [MomentsLogic.mediaKeys] 生成的**全部候选串**和 BLOB 里的 URL 都打出来，
     * 真机上跟朋友圈缓存目录的真实文件名比一比，对上哪种规则就按哪种写复制逻辑。
     */
    private fun logImgProbe(row: GroupDb.SnsRow, nick: String?) {
        val urls = MomentsLogic.extractUrls(row.content)
        val keys = MomentsLogic.mediaKeys(row.snsId, urls)
        imgProbeLogged.incrementAndGet()
        LogX.i(
            "$TAG img snsId=${row.snsId} user=${row.userName?.take(12)} nick=${nick?.take(8)} " +
                "keys=[${keys.take(8).joinToString(",")}] " +
                "urls=[${urls.take(3).joinToString(",") { it.take(64) }}]"
        )
    }

    // ------------------------------------------------------------------ 诊断

    fun stat(): String = runCatching {
        val c = cfg()
        "$TAG export stat enabled=${c.autoExport} images=$COPY_IMAGES running=$running " +
            "lastRunAt=$lastRunAt runs=${runs.get()} written=${written.get()} " +
            "skipped=${skipped.get()} listMiss=${listMiss.get()} err=${errs.get()} " +
            "list=${c.list.size} root=${c.root}"
    }.getOrElse { "$TAG export stat error=${it.javaClass.simpleName}" }

    /**
     * 探针入口（`op=moments`）：
     * - `stat` 状态；`path` 当前路径与可写性/剩余空间
     * - `export` 立刻起一轮（绕过最小间隔；开关只挡自动轮，不挡手动诊断）
     * - `img` 把最近 n 条归档的图片候选串打出来（对账用）
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
            "img" -> {
                val rows = GroupDb.current()?.listSnsArchive(n).orEmpty()
                for (r in rows) logImgProbe(r, nickOf(r.userName, HashMap()))
                "$TAG img probe rows=${rows.size}"
            }
            "scan" -> {
                val ids = scanExportedIds(File(cfg().root))
                "$TAG scan exported=${ids.size} sample=${ids.take(5)}"
            }
            else -> "$TAG export subs=stat|path|export|img|scan"
        }
    }.getOrElse { "$TAG export probe error=${it.javaClass.simpleName}" }
}
