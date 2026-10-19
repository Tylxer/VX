package com.lx.wxgroup.feature

import android.os.SystemClock
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 朋友圈图片 CDN 留存（R9 落地）。
 *
 * ## 干什么
 * 把批量下载通路的开门点 `dk.a()Z` 强制 true，让微信**自己**把朋友圈图片下到磁盘；
 * 我们只在 `ok0.x0#onC2CDownloadCompleted` 里读 `picCachePath`（出参），再在宿主进程内
 * 把文件拷到外部目录 `/storage/emulated/0/Android/data/com.tencent.mm/files/WechatXposed/moments/_imgdl`。
 * 不发 HTTP、不解 aeskey。
 *
 * ## 三个钩子
 *  H1 dk.a()Z                        开门（after 覆盖 result=true，短路 allowBatchImageDownload && dk.j 两道门）
 *  H2 ok0.x0#n(Ltj/g;)I              请求侧（可选；只数 l1 全量，供 stat 的 req 字段）
 *  H3 ok0.x0#onC2CDownloadCompleted  结果侧（★主落点；读 errorCode/picCachePath，只入队不 IO）
 *
 * ## 三条铁律
 *  1. 回调里不抛异常（runCatching 包到底）。
 *  2. 回调里不做 IO —— CDN 回调线程被阻塞会反噬微信的下载调度；H3 回调里只做
 *     AtomicInteger 自增 / volatile 读 / `System.currentTimeMillis()` / pool.submit，
 *     绝不打 LogX（同步文件 IO）、绝不读文件。
 *  3. 硬依赖（C_GATE / C_ENGINE / C_RESULT）任一 findClass 失败 ⇒ 整体不装；
 *     软依赖（C_REQ）失败 ⇒ 只跳过 H2，H1+H3 照常。
 */
object SnsCdnImage {

    private const val TAG = "SNSCDN"
    private const val CFG_TTL_MS = 3000L

    /** WiFi 判定缓存窗口（见 wifiOnly()：避免每张图都跑 3 次 binder IPC） */
    private const val WIFI_TTL_MS = 3000L

    // ---- 8.0.48/2588 静态取证值 ----
    private const val C_GATE = "com.tencent.mm.plugin.sns.ui.dk"
    private const val C_ENGINE = "ok0.x0"
    private const val C_REQ = "tj.g"
    private const val C_RESULT = "com.tencent.mars.cdn.CdnLogic\$C2CDownloadResult"

    private const val EXPORT_DIR =
        "/storage/emulated/0/Android/data/com.tencent.mm/files/WechatXposed/moments/_imgdl"

    private val breaker = SnsCdnBreaker()

    /** 单线程消费者：读文件头 / magic / 拷贝全在这里，天然串行，避开并发 fd 上限 */
    private val pool = Executors.newSingleThreadExecutor { r ->
        val t = Thread(r, "sns-cdn-copy")
        t.isDaemon = true
        t
    }

    /** 本次会话开始时刻：mtime 必须大于它才算本次新落盘（§4.5-1，防把历史缓存当新下载） */
    private val sessionStart = System.currentTimeMillis()

    private val gateCalls = AtomicInteger()
    private val gateTrueOrigin = AtomicInteger()
    private val cbTotal = AtomicInteger()
    private val cbErr = AtomicInteger()
    private val cbNoPath = AtomicInteger()
    private val batchReqTotal = AtomicInteger()
    private val copySeq = AtomicInteger()

    @Volatile private var cfg = Cfg(false, false)
    @Volatile private var cfgAt = 0L
    @Volatile private var reqClassOk = true
    @Volatile private var parts = ""

    private data class Cfg(val enabled: Boolean, val rewritePath: Boolean)

    // ---------------------------------------------------------------- 配置 / 熔断

    /**
     * 真读配置（**唯一做 SQLite 读的地方**）。
     *
     * ⛔ 只允许在**非 CDN 回调线程**调用：`Kv.getBool` → `GroupDb.getPref` →
     * `readableDatabase.query`（`data/GroupDb.kt:995`）是**真实 SQLite 磁盘读**。
     * 写进 `enabled()` 会让每张图的 CDN 回调都背一次磁盘 IO —— 与「回调里不做 IO」同量级违规
     * （2026-10-20 R11 复核 B-1，此前确实这么写过，已修）。
     *
     * 现在的调用方：`install()`（装钩子时一次）、`stateLine()`（探针主动查询时）、
     * `resetBreaker()`（探针主动调用时）。**都不在热路径**。
     */
    private fun refreshCfg() {
        val prev = cfg
        val next = Cfg(Kv.getBool(Keys.SNS_CDN_IMG), Kv.getBool(Keys.SNS_CDN_REWRITE_PATH))
        // 开关「关→开」边沿 ⇒ 清熔断
        if (!prev.enabled && next.enabled) breaker.reset()
        cfg = next
        cfgAt = SystemClock.elapsedRealtime()
    }

    /**
     * 热路径用的配置读取：**只读 @Volatile 内存字段，TTL 到期也不在这里读 Kv**。
     *
     * ⚠️ 与 `SnsGuard.cfg()`（`SnsGuard.kt:122`）的差异：那个类在 TTL 到期时就地 refresh，
     * 因为它的钩子跑在正常业务线程；本类钩子跑在 **CDN 回调线程**，一秒可能几十次，
     * 就地 refresh = 把 SQLite 读压进回调 ⇒ 不可接受。
     * 所以 TTL 到期这里**只是标记「该刷了」并异步丢给 pool**，本次仍用旧值（最长一次回调的延迟）。
     */
    private fun cfg(): Cfg {
        if (SystemClock.elapsedRealtime() - cfgAt > CFG_TTL_MS) {
            cfgAt = SystemClock.elapsedRealtime()   // 先占位，避免并发线程连续触发刷新
            runCatching { pool.execute { runCatching { refreshCfg() } } }
        }
        return cfg
    }

    /**
     * ⚠️ 顺序：先读配置（内存），再判熔断 —— 顺序反了会短路「重开开关」。
     * 本方法在 CDN 回调热路径上，**只允许访问 volatile / AtomicInteger**，不许 IO。
     */
    private fun enabled(): Boolean {
        if (!cfg().enabled) return false
        if (breaker.blocked(SystemClock.elapsedRealtime())) return false
        // 只在 WiFi 下放行（方案 §5.4：开门后走的是微信非默认代码路径，流量/风控面都要收）
        if (!wifiOnly()) return false
        return true
    }

    /**
     * WiFi 判定。⚠️ 方案 §5.4 要求，V1-B2 指出原骨架引用的 `WifiOnly.ok()` 全仓不存在。
     * 与 `feature/MomentsAuto.kt:239` 的 private `wifi()` 同款逻辑，但**不强行共用**
     * （那边语义是「刷新条件」，这边是「下载放行条件」，耦合方向不同）。
     *
     * ⚠️ `HostContext.app()` 返回 **`Context?`**（`core/HostContext.kt:63`）—— 直接链式调用
     * 会编译失败（nullable receiver），必须先 `?:` 兜底。
     * 任何异常一律 false（判不出来就不放行）。
     *
     * ⚠️ **必须带 TTL 缓存**：本方法在 `onC2CDownloadCompleted` 里被**每张图**调用一次，
     * 而 `getSystemService` + `activeNetwork` + `getNetworkCapabilities` 全是 binder IPC。
     * 每张图 3 次跨进程调用会直接反噬微信的下载调度（铁律 2「回调里不做 IO」的实质）。
     * 缓存窗口与配置 TTL 一致，WiFi 断开后最长 3 秒内停止放行，可接受。
     *
     * ⚠️ 用 `SystemClock.elapsedRealtime()`（单调）而不是 `System.currentTimeMillis()`：
     * 后者受系统时间回拨影响，时间被调小后 `now - wifiAt` 变负 ⇒ 判定**永久**命中缓存
     * ⇒ WiFi 断开也不停止放行。同项目 `SnsGuard.kt:121` 用的也是 elapsedRealtime。
     */
    @Volatile private var wifiVerdict = false
    @Volatile private var wifiAt = 0L

    private fun wifiOnly(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - wifiAt <= WIFI_TTL_MS) return wifiVerdict
        val v = runCatching {
            val ctx = HostContext.app() ?: return@runCatching false
            val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return@runCatching false
            val n = cm.activeNetwork ?: return@runCatching false
            val cap = cm.getNetworkCapabilities(n) ?: return@runCatching false
            cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        }.getOrDefault(false)
        wifiVerdict = v
        wifiAt = now
        return v
    }

    // ---------------------------------------------------------------- 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val cl = lpparam.classLoader

            // 硬依赖：任一缺失整体不装（H1+H3 = MVP 的命脉）
            XposedHelpers.findClass(C_GATE, cl)
            XposedHelpers.findClass(C_ENGINE, cl)
            XposedHelpers.findClass(C_RESULT, cl)

            hookGate(cl)     // H1 ★
            hookResult(cl)   // H3 ★

            // 软依赖：C_REQ 只服务 H2，失败只降级自身，H1+H3 照常
            if (runCatching { XposedHelpers.findClass(C_REQ, cl) }.isSuccess) {
                hookBatchRequest(cl) // H2（可选）
            } else {
                reqClassOk = false
                LogX.w("$TAG C_REQ($C_REQ) missing, H2 skipped")
            }

            LogX.i("$TAG installed parts=$parts req=$reqClassOk")
        }.onFailure { LogX.w("$TAG install failed: ${it.javaClass.simpleName}") }

        // 初始读一次配置（install 跑在 Application.attach 之后、业务线程上，可安全做 SQLite 读）。
        // 不做这一步的话，`cfg` 初值是 false，要等第一次 TTL 到期才会异步刷新 —— 表现为
        // 「开关打开但前几秒不生效」，探针 sub=img 也可能读到 on=0 而误判。
        runCatching { refreshCfg() }
    }

    /**
     * H1：`dk#a()Z` 强制 true，短路 `allowBatchImageDownload() && dk.j` 两道门。
     *
     * ⚠️ 用 `hookAllMethods` 而不是 `findAndHookMethod(c, "a", cb)`：
     * 后者的三参重载会让 Xposed 去 hook **名为 `a` 且无参**的那个 —— 但 `dk` 里若还有
     * `a(int)` / `a(String)` 之类重载，行为取决于 Xposed 内部匹配顺序，不可控。
     * 这里显式过滤「**无参数**」的那一个（真正的 `a()Z`），其余重载一律跳过，
     * 避免误伤（这是探针 `SnsCdnProbe.dkHook()` 已验证的写法）。
     *
     * ⚠️ `dk.a()Z` 是 `public static`（access_flags=0x9）⇒ `param.thisObject == null`，
     * 且 `param.result` 是装箱的 `Boolean`。
     */
    private fun hookGate(cl: ClassLoader) {
        val c = XposedHelpers.findClass(C_GATE, cl)
        var n = 0
        XposedBridge.hookAllMethods(c, "a", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // 只认无参重载（真正的 a()Z），其它同名重载原样放行
                if (!param.args.isNullOrEmpty()) return
                runCatching {
                    val origin = param.result as? Boolean
                    gateCalls.incrementAndGet()
                    if (origin == true) gateTrueOrigin.incrementAndGet() // I-1 判据
                    if (enabled()) param.result = true                    // 短路两道门
                }
            }
        }).also { n = it.size }
        if (n <= 0) LogX.w("$TAG C_GATE.a not hooked (no impl)")
        addPart("dk")
    }

    /** H3：`ok0.x0#onC2CDownloadCompleted(String, C2CDownloadResult)V` —— ★主落点，只入队 */
    private fun hookResult(cl: ClassLoader) {
        val engine = XposedHelpers.findClass(C_ENGINE, cl)
        val resCls = XposedHelpers.findClass(C_RESULT, cl)
        XposedHelpers.findAndHookMethod(
            engine, "onC2CDownloadCompleted", String::class.java, resCls,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // ⛔ 跑在微信 CDN 回调线程上：只读字段 + 入队，不 IO、不打 LogX、不抛
                    runCatching {
                        if (!enabled()) return@runCatching
                        val r = param.args[1] ?: return@runCatching
                        val err = XposedHelpers.getIntField(r, "errorCode")
                        val path = XposedHelpers.getObjectField(r, "picCachePath") as? String
                        val size = XposedHelpers.getLongField(r, "fileSize")
                        val recved = XposedHelpers.getLongField(r, "recvedBytes")
                        val idx = XposedHelpers.getIntField(r, "picIndex")
                        val feedId = XposedHelpers.getObjectField(r, "batchPicFeedId") as? String
                        val httpCode = XposedHelpers.getIntField(r, "httpStatusCode")
                        val retry = XposedHelpers.getObjectField(r, "batchImageNeedRetry") as? Array<*>
                        cbTotal.incrementAndGet()
                        // ⚠️ 必须用**单调钟**，且必须与 enabled() 里 breaker.blocked() 传的时钟**同域**：
                        // 熔断器内部拿 now 与 tripAt 比较，若一处墙钟一处单调钟，
                        // 「熔断后是否冷却完」会算成永远不成立（或永远成立）—— 静默错乱。
                        val now = SystemClock.elapsedRealtime()
                        if (err != 0) {
                            cbErr.incrementAndGet()
                            if (breaker.hit(now)) {
                                pool.execute { LogX.w("$TAG breaker tripped err=$err http=$httpCode") }
                            }
                            return@runCatching
                        }
                        breaker.ok()
                        if (path.isNullOrEmpty()) {
                            cbNoPath.incrementAndGet()
                            return@runCatching
                        }
                        // ★ 只入队：stat / magic / copy 全在单线程 pool 里做
                        pool.submit {
                            runCatching { collect(path, feedId, idx, size, recved, retry?.size ?: 0) }
                                .onFailure { LogX.e("$TAG collect", it) }
                        }
                    }
                }
            }
        )
        addPart("cb")
    }

    /** H2：`ok0.x0#n(Ltj/g;)I`（可选）—— 只数 l1 全量，供 stat 的 req 字段 */
    private fun hookBatchRequest(cl: ClassLoader) {
        val engine = XposedHelpers.findClass(C_ENGINE, cl)
        val reqCls = XposedHelpers.findClass(C_REQ, cl)
        XposedHelpers.findAndHookMethod(
            engine, "n", reqCls,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val req = param.args[0] ?: return@runCatching
                        val arr = XposedHelpers.getObjectField(req, "l1") as? Array<*> ?: return@runCatching
                        batchReqTotal.addAndGet(arr.size)
                    }
                }
            }
        )
        addPart("n")
    }

    // ---------------------------------------------------------------- 消费者线程（pool，允许 IO）

    private fun collect(path: String, feedId: String?, idx: Int, size: Long, recved: Long, retrySize: Int) {
        // 目录资格（I-2）
        val reject = SnsCdnCheck.dirReject(path)
        if (reject != null) {
            LogX.i("$TAG reject path=$path reason=$reject")
            return
        }
        val src = File(path)
        if (!src.exists() || src.length() <= 0L) {
            LogX.i("$TAG empty path=$path")
            return
        }
        // mtime 必须晚于会话开始（§4.5-1）
        if (src.lastModified() <= sessionStart) {
            LogX.i("$TAG stale path=$path mtime=${src.lastModified()}")
            return
        }
        // 读文件头 16 字节判明文（I-4）
        val kind = SnsCdnCheck.magicKind(readHead(src))
        if (kind == "unknown") {
            LogX.i("$TAG cipher path=$path")
            return
        }
        // 大小对账（§4.1 判据④）
        //
        // ⚠️ 2026-10-20 R11 复核 S-4：原实现对 `size>0 && recved>0 && recved != size` 直接
        // **拒收**。但 `fileSize` 与 `recvedBytes` 的口径**尚未真机确认**（方案标 🟡）：
        // 若 fileSize=原始大小、recvedBytes=解密后大小，两者恒不等是**正常现象**，
        // 硬拒会把好图全丢掉 ⇒ 表现为「装了但什么都没发生」的静默失效，极难排查。
        //
        // ⇒ 改为**不拒收、只标注**：魔法数已经过了（说明确实是图片），完整性交给
        //    「文件实际长度 vs fileSize」这个更可靠的判据（落盘大小是客观事实），
        //    并把两个字段都打进日志，让真机 I-2 一次就把口径看清。
        val truncFlag = when {
            size <= 0L || recved <= 0L -> "unknown"     // 字段缺失，无法判断
            recved == size -> "exact"
            else -> "diff"                              // 口径可能不同，仅标注
        }

        val dstDir = File(EXPORT_DIR)
        if (!dstDir.exists() && !dstDir.mkdirs()) {
            LogX.w("$TAG mkdirs failed $EXPORT_DIR")
            return
        }
        val seq = copySeq.incrementAndGet()
        val name = "${sanitize(feedId)}_${idx}_${System.currentTimeMillis()}_${seq}.$kind"
        val dst = File(dstDir, name)
        runCatching { src.copyTo(dst, overwrite = false) }
            .onSuccess {
                val got = it.length()
                // 完整性用「实际落盘字节 vs fileSize」判，比字段对比可靠
                val intact = if (size > 0L) (got == size).toString() else "unknown"
                LogX.i(
                    "$TAG file $name src=$path size=$got kind=$kind " +
                        "recved=$recved fileSize=$size intact=$intact sizeflag=$truncFlag retry=$retrySize"
                )
            }
            .onFailure {
                LogX.w("$TAG copy failed src=$path err=${it.javaClass.simpleName}")
            }
    }

    private fun readHead(f: File): ByteArray = runCatching {
        val buf = ByteArray(16)
        val input = FileInputStream(f)
        val n = input.use { it.read(buf) }
        if (n <= 0) ByteArray(0) else buf.copyOf(n)
    }.getOrDefault(ByteArray(0))

    private fun sanitize(s: String?): String {
        if (s.isNullOrBlank()) return "feed"
        return s.replace(Regex("[^0-9A-Za-z._-]"), "_").take(32)
    }

    private fun addPart(p: String) {
        parts = if (parts.isEmpty()) p else "$parts,$p"
    }

    // ---------------------------------------------------------------- 探针接口（契约 3）

    /**
     * 清熔断 + 清计数，纯内存，可任意线程调。
     * ⚠️ 这里**顺便做一次真刷新**：探针 sub=reset 的用途是「改完开关/判据后重来」，
     * 若还拿着 TTL 内的旧配置，会出现「清了熔断但开关还是旧的」的错判。
     */
    fun resetBreaker() {
        breaker.reset()
        gateCalls.set(0)
        gateTrueOrigin.set(0)
        cbTotal.set(0)
        cbErr.set(0)
        cbNoPath.set(0)
        batchReqTotal.set(0)
        runCatching { refreshCfg() }   // SQLite 读，但这是探针主动调用，不在热路径
    }

    /** 一行状态串，以 `CDN img ` 开头，供探针 sub=img 原样输出 */
    fun stateLine(): String {
        // ⚠️ 先真刷新配置，否则探针看到的是 TTL 内的旧值（`on=` 会骗人）
        runCatching { refreshCfg() }
        val on = if (enabled()) 1 else 0
        val streak = breaker.streak()
        val tripped = if (breaker.rawTripped()) 1 else 0
        val cb = cbTotal.get()
        val err = cbErr.get()
        val nopath = cbNoPath.get()
        val req = batchReqTotal.get()
        return "CDN img on=$on parts=$parts streak=$streak tripped=$tripped cb=$cb err=$err nopath=$nopath req=$req"
    }
}
