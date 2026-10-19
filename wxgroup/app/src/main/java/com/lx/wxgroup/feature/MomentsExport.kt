package com.lx.wxgroup.feature

import com.lx.wxgroup.core.HostContext
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
    private val imgProbeLogged = AtomicInteger(0)

    /**
     * 图片对账日志的自增序号。
     *
     * ⚠️ 必须带序号：LogX 有 1 秒去重，同一条动态连着探两次会吐出**完全相同**的一行，
     * 第二行被吞 → 对账时凭空少一条候选，还查不出原因。
     */
    private val imgSeq = AtomicLong(0)

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
     * 2026-10-18 起升级为**可判死**的形式：不再只吐候选串，而是把
     * 「md5 → 每个候选路径的 exists → 最终选中」全打出来。
     * 这样下一轮真机上**不用改一行代码**就能判断缓存目录规则对不对
     * （结论词：`picked=` 后面不是 `NONE` 就说明规则命中）。
     */
    private fun logImgProbe(row: GroupDb.SnsRow, nick: String?) {
        val urls = MomentsLogic.extractUrls(row.content)
        val keys = MomentsLogic.mediaKeys(row.snsId, urls)
        val md5s = MomentsLogic.md5sOf(keys)
        val roots = imgRoots()
        imgProbeLogged.incrementAndGet()

        val detail = StringBuilder()
        var pickedCount = 0
        for (m in md5s) {
            val cands = MomentsLogic.imageCandidates(m, roots)
            val picked = MomentsLogic.pickExisting(cands) { File(it).exists() }
            if (picked != null) pickedCount++
            detail.append(" {md5=").append(m)
                .append(" cand=").append(cands.size)
                .append(" picked=").append(picked?.let { File(it).name } ?: "NONE")
                .append('}')
        }

        LogX.i(
            "$TAG img #${imgSeq.incrementAndGet()} snsId=${row.snsId} " +
                "user=${row.userName?.take(12)} nick=${nick?.take(8)} " +
                "md5=${md5s.size} picked=$pickedCount roots=${roots.size}" +
                detail +
                " keys=[${keys.take(8).joinToString(",").take(120)}]" +
                " urls=[${urls.filterNot { MomentsLogic.isAvatarUrl(it) }.take(2)
                    .joinToString(",") { it.take(48) }}]"
        )
    }

    /**
     * 图片缓存根目录（**2026-10-18 设备实测：沿这条路走不通**，见下）。
     *
     * ⚠️ 不再用 `filesDir`：实测朋友圈缓存**不在** `/data/.../files` 下。
     *
     * 内部库目录与外部缓存目录的哈希**不是同一个**（实测内部 `89476c7f…`、
     * 外部 `bfa6fa81…` / `22139c91…` 两个），不能用一个推另一个，只能把外部
     * `MicroMsg/` 下的 32 位 hash 子目录全列出来逐个试。
     *
     * ⚠️⚠️ **实测结论：`picked=` 全是 `NONE`，且这是必然的**。
     * 4 个真机取到的 md5 在 `/sdcard` + `/data/user/0/com.tencent.mm` 全盘 find
     * 一个都搜不到；`image/` 最近写入是 2 天前的公众号配图，近两天 `/sdcard`
     * 没有任何图片落盘 ⇒ **朋友圈的图从未下载到本机**。
     * 所以不要再花时间调这里的目录规则。原图留存应改为
     * 「从 content 的 CDN url 直接下载」，见 [MomentsLogic.imageRootCandidates] 的注释。
     */
    private fun imgRoots(): List<String> {
        val extRoot = runCatching {
            android.os.Environment.getExternalStorageDirectory().absolutePath +
                "/Android/data/com.tencent.mm"
        }.getOrNull() ?: return emptyList()

        val hashes = runCatching {
            java.io.File("$extRoot/MicroMsg").listFiles()
                ?.filter { it.isDirectory && it.name.length == 32 }
                ?.map { it.name }
                .orEmpty()
        }.getOrDefault(emptyList())

        if (hashes.isEmpty()) {
            // 没扫到 hash 也要给出一个根，让探针能明确报「目录都不存在」而不是静默空转
            LogX.w("$TAG img no hash dir under $extRoot/MicroMsg")
            return MomentsLogic.imageRootCandidates(extRoot, null)
        }
        val out = LinkedHashSet<String>()
        for (h in hashes) out.addAll(MomentsLogic.imageRootCandidates(extRoot, h))
        return out.toList()
    }

    /**
     * 试拷一条动态的原图（探针 `sub=imgtry` 专用，**不依赖 [COPY_IMAGES]**）。
     *
     * 存在的理由：正式开关要等真机验完才敢开，但验证本身又不该改代码重编。
     * 这个分支直接对最近 n 条归档跑一遍候选匹配 + 真拷贝，把结果打日志。
     */
    private fun tryCopyImages(limit: Int): String {
        val db = GroupDb.current() ?: return "$TAG imgtry no db"
        val roots = imgRoots()
        if (roots.isEmpty()) return "$TAG imgtry no cache root (filesDir null?)"
        val rows = runCatching { db.listSnsArchive(limit) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return "$TAG imgtry no archived rows"

        val outDir = File(cfg().root, "_imgtry")
        if (!outDir.exists() && !outDir.mkdirs()) {
            return "$TAG imgtry mkdirs failed: ${outDir.path}"
        }
        if (outDir.usableSpace < MomentsLogic.MIN_FREE_BYTES) {
            return "$TAG imgtry abort: free=${outDir.usableSpace} < ${MomentsLogic.MIN_FREE_BYTES}"
        }

        var rowsHit = 0
        var copied = 0
        var tried = 0
        for (row in rows) {
            val urls = MomentsLogic.extractUrls(row.content)
            val md5s = MomentsLogic.md5sOf(MomentsLogic.mediaKeys(row.snsId, urls))
            if (md5s.isEmpty()) continue
            var rowCopied = 0
            for ((i, m) in md5s.withIndex()) {
                tried++
                val picked = MomentsLogic.pickExisting(
                    MomentsLogic.imageCandidates(m, roots)
                ) { File(it).exists() } ?: continue
                val ext = MomentsLogic.extOf(picked)
                val dst = File(outDir, MomentsLogic.imageFileName(row.snsId, i, ext))
                val ok = runCatching {
                    val tmp = File(dst.path + ".tmp")
                    if (dst.exists()) dst.delete()   // 见下方说明：不删就会 rename 失败
                    File(picked).copyTo(tmp, overwrite = true)
                    tmp.renameTo(dst)
                }.getOrDefault(false)
                if (ok) {
                    copied++
                    rowCopied++
                } else {
                    // 失败必须清掉半成品，否则下一次 exists() 会把它当成「已拷过」
                    runCatching { File(dst.path + ".tmp").delete() }
                    LogX.w("$TAG imgtry copy failed md5=$m src=$picked")
                }
            }
            if (rowCopied > 0) rowsHit++
        }
        return "$TAG imgtry rows=${rows.size} rowsHit=$rowsHit md5Tried=$tried copied=$copied " +
            "roots=${roots.size} out=${outDir.path}"
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
     * 为什么需要单独一条：`logImgProbe` 里的 url 被 `take(48)` 截断，只看得到
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
                // ⚠️ n 来自广播 --ei，必须钳位后再进 SQL LIMIT（OOM 防护，见 clampProbeN）
                val nn = MomentsLogic.clampProbeN(n)
                val rows = GroupDb.current()?.listSnsArchive(nn).orEmpty()
                for (r in rows) logImgProbe(r, nickOf(r.userName, HashMap()))
                "$TAG img probe rows=${rows.size} n=$nn"
            }
            "imgtry" -> tryCopyImages(MomentsLogic.clampProbeN(n))
            // ⚠️ 2026-10-18 实测：md5 找本地缓存全 NONE，图根本没下载到本机。
            // 所以加这条**只读**探针，把 content 里的完整 CDN url 原样打出来
            // （`logImgProbe` 里的 url 被 take(48) 截断了，看不出能不能直接 GET）。
            // 下一轮据此判断「下载原图」方案是否可行，不用改代码。
            "imgurl" -> logImgUrls(MomentsLogic.clampProbeN(n))
            // 真下载（联网）。n 建议小一点（默认 3），别一口气把几十条都下了
            "imgdl" -> downloadMedia(MomentsLogic.clampProbeN(n))
            "scan" -> {
                val ids = scanExportedIds(File(cfg().root))
                "$TAG scan exported=${ids.size} sample=${ids.take(5)}"
            }
            else -> "$TAG export subs=stat|path|export|img|imgtry|imgurl|imgdl|scan"
        }
    }.getOrElse { "$TAG export probe error=${it.javaClass.simpleName}" }
}
