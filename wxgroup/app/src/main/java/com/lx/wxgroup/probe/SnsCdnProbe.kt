package com.lx.wxgroup.probe

import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * R9-P2 朋友圈图片 CDN 落盘验证探针（`op=cdn`）。
 *
 * 目的：验证 docs/取证结论.md ⑫.7 的 I-1（`dk.a()` 开关返回值）/ I-2（图片是否落盘、
 * 落哪）/ I-3（改写 `BatchSnsReqImageData.imageCachePath` 能否换目录）/ I-4（落盘字节
 * 是不是明文 jpg）。日志前缀统一 `CDN `（脚本 tools/_r9_cdn.py 靠它匹配，改一个字符就抓不到）。
 *
 * ## 微信侧事实（静态已取证，见 docs/_r10_cdn_api.md / _r10_cdn_chain.md）
 * - 开门点 `com.tencent.mm.plugin.sns.ui.dk.a()Z`（真实类名 `SnsMultiLineImageLineMgr`）：
 *   `return CdnLogic.allowBatchImageDownload() && dk.j`。两道门，任一假即 `return false`。
 * - 批量请求入口 `ok0.x0#n(Ltj/g;)I`（真实类名 `CdnTransportEngine`）：入参 `tj/g` 字段
 *   `i1`(String feedId) / `j1`(int feedPicCount) / `l1`([BatchSnsReqImageData)。
 * - 结果回调 `ok0.x0#onC2CDownloadCompleted(String, CdnLogic$C2CDownloadResult)V`：
 *   结果字段 `picCachePath`(String 落盘路径) / `errorCode`(I) / `fileSize`(J) / `picIndex`(I)。
 * - `BatchSnsReqImageData` 的**唯一构造点**是 `jf3.f#k([String)Integer`（SnsCdnDownloadBase
 *   的 doInBackground），但它是方法体**局部变量**，反射拿不到 ⇒ 改写 imageCachePath 只能
 *   在 `ok0.x0#n` 的 before 里反射 `tj/g.l1` 数组元素（`jf3.f#k` 降为计数探针）。
 * - ⛔ 所有 `CdnLogic.start*Download` / `allowBatchImageDownload` 都是 native（acc=0x109），
 *   Xposed 不可直接 hook，只能 hook 调用方 / 回调，或反射调 native 拿返回值。
 *
 * ## 类名都是混淆名（`ok0.x0` / `jf3.f` / `dk`），可能随小版本变
 * ⇒ 每个 hook 点独立降级：按名 `findClass`，失败只打 `CDN hook off` + 把原因写进
 * `sub=stat` 的 note，**绝不**因为找不到类而抛异常崩微信。
 *
 * ## 热路径纪律
 * `onC2CDownloadCompleted` 每张图都会调，回调里**只累加 AtomicInteger / 存最近一条到
 * volatile 字段**，不写 LogX；LogX 只在 `sub=stat` / `sub=paths` / `sub=ls` 这类被主动
 * 查询时才打。`ok0.x0#n` 是每次批量请求调一次（远低于回调），但 `sub=reqmp` 的记录默认
 * 关闭，只有显式开启才打日志。
 *
 * ## 用法（tools/_r9_cdn.py 会自动发这些广播，此处仅列子命令）
 * ```
 * sub=hook val=on|off       开关整套 hook
 * sub=dk                    ★真调一次 dk.a()，打返回值 + native allowBatchImageDownload() + dk.j
 * sub=force val=true|off    让 dk.a() 返回 true（I-2 增量）
 * sub=cbon val=on|off       记录 onC2CDownloadCompleted 回调
 * sub=reqmp val=on|off      记录 ok0.x0#n 批量请求入参（读不到时打 CDN reqerr reason=<why> n=<k>）
 * sub=paths                 打印已观测落盘目录
 * sub=ls val=<dir>          用微信进程权限列目录
 * sub=export val=<dir> n=N  把最近 N 个文件拷到可读 cache 目录（I-4 验 magic）
 * sub=imgpath val=<dir>|off I-3 改写 imageCachePath
 * sub=reset                 清本探针计数器 + 熔断（SnsCdnImage.resetBreaker）
 * sub=img                   打 SnsCdnImage.stateLine() 状态行（已自带 `CDN img ` 前缀）
 * sub=stat                  自身状态（追加 hitN/hitPicE/hitPicNE/hitDir/hitNoField，见 doStat）
 * ```
 */
object SnsCdnProbe {

    // ---------------------------------------------------------------- 类名 / 字段名

    /** `CdnTransportEngine`（批量请求入口 + 结果回调中枢）的混淆名 */
    private const val CLS_ENGINE = "ok0.x0"

    /** `SnsCdnDownloadBase`（BatchSnsReqImageData 唯一构造点）的混淆名 */
    private const val CLS_SNS_DL = "jf3.f"

    /** `SnsMultiLineImageLineMgr`（批量开关开门点，包名未混淆、类名混淆为 dk） */
    private const val CLS_DK = "com.tencent.mm.plugin.sns.ui.dk"

    /** mars CDN 逻辑类（未混淆，反射调 native `allowBatchImageDownload()` 用） */
    private const val CLS_CDN_LOGIC = "com.tencent.mars.cdn.CdnLogic"

    // tj/g 字段（批量请求载体）
    private const val F_REQ_FEED_ID = "i1"
    private const val F_REQ_PIC_COUNT = "j1"
    private const val F_REQ_IMAGES = "l1"

    // C2CDownloadResult 字段
    private const val F_RES_PIC_PATH = "picCachePath"

    // BatchSnsReqImageData 字段
    private const val F_IMG_PATH = "imageCachePath"
    private const val F_IMG_KEY = "fileKey"

    /** 结果回调的落盘目录：`/sdcard/Android/data/com.tencent.mm/cache/r9cdn/`（sub=export 的落点） */
    private const val EXPORT_DIR = "/sdcard/Android/data/com.tencent.mm/cache/r9cdn"

    // ---------------------------------------------------------------- 状态

    private var loader: ClassLoader? = null

    private val unhooks = Collections.synchronizedList(mutableListOf<XC_MethodHook.Unhook>())

    /** `sub=force`：让 `dk.a()` 返回 true（I-2 增量） */
    @Volatile
    private var forceDk = false

    /** `sub=cbon`：记录 onC2CDownloadCompleted 回调（默认为关，回调是热路径） */
    @Volatile
    private var cbonOn = false

    /** `sub=reqmp`：记录 ok0.x0#n 批量请求入参 */
    @Volatile
    private var reqmpOn = false

    /** `sub=imgpath`：I-3 改写目标目录（null = 不改写） */
    @Volatile
    private var imgPathDir: String? = null

    /** `jf3.f#k` 命中计数（确认构造点是否活跃） */
    private val hitK = AtomicInteger()

    /** `ok0.x0#onC2CDownloadCompleted` 命中计数 */
    private val hitCB = AtomicInteger()

    /**
     * `ok0.x0#n` **进入次数**（每次 `beforeHookedMethod` +1，不论后面读到还是读不到 `l1`）。
     *
     * ⚠️ 这是原来的观测缺口：`sub=reqmp` 一条 `CDN req` 都没打时，**无法区分**
     * 「`ok0.x0#n` 从来没被调用（类名漂移 / 走了别的批量下载路径）」与
     * 「调用了但 `l1` 读不到或为空」。有 `hitN` 之后：`hitN=0` ⇒ 前者，`hitN>0` ⇒ 后者。
     * ⚠️ `hookAllMethods(cls,"n")` 会挂同名**所有重载**，所以 `hitN` 也可能包含其它
     * `n(...)` 重载（_request侧地名冲突_），真机解读时要结合 `reqerr` 的 reason 一起看。
     */
    private val hitN = AtomicInteger()

    /** 回调里 `C2CDownloadResult.picCachePath` 是 **null 或空串** 的次数 */
    private val hitPicE = AtomicInteger()

    /** 回调里 `C2CDownloadResult.picCachePath` **非空** 的次数 */
    private val hitPicNE = AtomicInteger()

    /**
     * 回调里 `picCachePath` **字段本身就取不到**（`NoSuchFieldError`）的次数。
     *
     * 与 [hitPicE] 必须分开计：`picCachePath` 在 Java 层只有 `<init>` 里写过一次空串、
     * 0 条读 ⇒ 真值由 native（Mars）用 JNI 填 ⇒ **空串是「字段存在但 Mars 没填」**，
     * 而 NoSuchFieldError 是「字段名漂移」。两者下一步完全不同（前者是 native 侧问题，
     * 后者要重新静态确认字段名）。
     */
    private val hitNoField = AtomicInteger()

    /** reqerr 节流：同类原因最多打前 [REQERR_FIRST] 次，之后只在计数为 2 的幂时补一条（热路径别刷屏） */
    private const val REQERR_FIRST = 3
    private val reqErrCount = ConcurrentHashMap<String, AtomicInteger>()

    /** 最近一次回调的时间戳（stat 的 last 字段） */
    @Volatile
    private var lastCbAt = 0L

    /** 最近一次回调的 `picCachePath` 父目录（sub=paths 的 pic 字段） */
    @Volatile
    private var lastPicParent: String? = null

    /** 已观测到的落盘目录（回调 picCachePath 父目录 + 请求 imageCachePath 父目录） */
    private val observedDirs = ConcurrentHashMap.newKeySet<String>()

    /** hook 安装失败原因（sub=stat 时以 note 打出，解释「为什么 parts 缺了」） */
    @Volatile
    private var installNote = ""

    // ---------------------------------------------------------------- 入口

    /**
     * 存 classLoader（若 F3 接线方便可调用；不调用也能用 [HostContext.app] 兜底）。
     */
    fun prepare(lpparam: XC_LoadPackage.LoadPackageParam) {
        loader = lpparam.classLoader
    }

    /**
     * 命令分发。⚠️ 调用方（EntryReceiver 的 op=cdn 分支）已把它包在后台线程里跑，
     * 本方法自身也保证不抛异常、不 ANR（所有文件 IO 都在这里同步做，目录可能很大，
     * 但只在用户主动发广播时触发，且已按上限截断）。
     */
    fun command(sub: String, v: String?, n: Int) {
        runCatching {
            when (sub) {
                "hook" -> if (v == "on") install() else uninstallAndLog()
                "dk" -> doDk()
                "force" -> {
                    forceDk = v == "true"
                    own("CDN force " + (v ?: "off"))
                }
                "cbon" -> {
                    cbonOn = v == "on"
                    own("CDN cbon " + (if (cbonOn) "on" else "off"))
                }
                "reqmp" -> {
                    reqmpOn = v == "on"
                    own("CDN reqmp " + (if (reqmpOn) "on" else "off"))
                }
                "paths" -> doPaths()
                "ls" -> doLs(v, n)
                // 只列子目录名 —— 补 `scanDir` 丢弃子目录造成的盲区（见 doLsSubdirs KDoc）
                "lsi" -> doLsSubdirs(v, n)
                "decode" -> doDecode(v)
                "export" -> doExport(v, n)
                "imgpath" -> {
                    imgPathDir = if (v == "off" || v.isNullOrBlank()) null else v
                    own("CDN imgpath " + (imgPathDir ?: "off"))
                }
                "reset" -> doReset()
                "img" -> own(
                    runCatching { com.lx.wxgroup.feature.SnsCdnImage.stateLine() }
                        .getOrDefault("CDN img off")
                )
                "stat" -> doStat()
                else -> own("CDN unknown sub=$sub")
            }
        }.onFailure {
            runCatching { LogX.w("CDN command($sub) failed: ${it.javaClass.simpleName}") }
        }
    }

    // ---------------------------------------------------------------- hook 安装

    private fun resolveLoader(): ClassLoader? {
        loader?.let { return it }
        return runCatching { HostContext.app()?.classLoader }.getOrNull()
    }

    /**
     * 装整套 hook。每个点独立 try：按名找类失败只记 missing，不抛、不崩。
     * parts 按 n,cb,k,dk 顺序（与契约 `CDN hook on parts=n,cb,k,dk` 一致）。
     */
    private fun install() {
        val cl = resolveLoader()
        if (cl == null) {
            installNote = "no classLoader"
            own("CDN hook off (no classLoader)")
            return
        }
        uninstallQuiet()

        val parts = ArrayList<String>()
        val missing = ArrayList<String>()

        // ok0.x0#n —— 批量请求入口（reqmp 记录 + imgpath 改写 l1）
        if (hookMethod(cl, CLS_ENGINE, "n", reqHook(), missing, "n")) parts.add("n")
        // ok0.x0#onC2CDownloadCompleted —— 结果回调（cbon 记录 + 收集 picCachePath）
        if (hookMethod(cl, CLS_ENGINE, "onC2CDownloadCompleted", cbHook(), missing, "cb")) parts.add("cb")
        // jf3.f#k —— BatchSnsReqImageData 构造点（降为计数探针，局部变量拿不到）
        if (hookMethod(cl, CLS_SNS_DL, "k", kHook(), missing, "k")) parts.add("k")
        // dk.a —— 批量开关（force 强制 true）
        if (hookMethod(cl, CLS_DK, "a", dkHook(), missing, "dk")) parts.add("dk")

        if (parts.isEmpty()) {
            installNote = missing.joinToString(",")
            own("CDN hook off (missing: $installNote)")
        } else {
            installNote = if (missing.isEmpty()) "" else "partial:${missing.joinToString(",")}"
            own("CDN hook on parts=${parts.joinToString(",")}")
        }
    }

    private fun hookMethod(
        cl: ClassLoader,
        clsName: String,
        method: String,
        cb: XC_MethodHook,
        missing: MutableList<String>,
        tag: String,
    ): Boolean {
        val cls = runCatching { XposedHelpers.findClass(clsName, cl) }.getOrNull()
        if (cls == null) {
            if (missing.size < 10) missing.add("$tag($clsName not found)")
            return false
        }
        val set = runCatching {
            XposedBridge.hookAllMethods(cls, method, cb).toCollection(mutableListOf())
        }.getOrNull()
        if (set.isNullOrEmpty()) {
            if (missing.size < 10) missing.add("$tag($clsName.$method no impl)")
            return false
        }
        synchronized(unhooks) { unhooks.addAll(set) }
        return true
    }

    private fun uninstallQuiet() {
        synchronized(unhooks) {
            for (u in unhooks) runCatching { u.unhook() }
            unhooks.clear()
        }
    }

    private fun uninstallAndLog() {
        uninstallQuiet()
        own("CDN hook off")
    }

    // ---------------------------------------------------------------- hook 回调

    /**
     * `dk.a()Z`：`sub=force true` 时在 before 里 `result=true`，直接绕过两道门。
     * ⚠️ 只对无参重载（真正的 a()Z）设置，避免误伤同名其它重载。
     */
    private fun dkHook() = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!forceDk) return
            runCatching {
                if (param.args.isEmpty()) param.result = true
            }
        }
    }

    /**
     * `ok0.x0#onC2CDownloadCompleted` —— **热路径**：每张图都调。
     * 只累加计数 + 存最近 picCachePath 父目录，**不写 LogX**。
     */
    private fun cbHook() = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching {
                hitCB.incrementAndGet()
                lastCbAt = System.currentTimeMillis()
                val result = param.args.getOrNull(1) ?: return
                // ⚠️ 关键区分：字段**不存在**（NoSuchFieldError，类名/字段名漂移）
                // vs 字段存在但值是 null/空串（由 native Mars 用 JNI 填，这次没填）。
                // 老代码用 getOrNull() 把两者都吞成 null ⇒ `pic=none` 无法归因。
                val (state, raw) = readField(result, F_RES_PIC_PATH)
                val pic = raw as? String
                if (pic.isNullOrEmpty()) {
                    if (state == ReadState.NO_FIELD) hitNoField.incrementAndGet()
                    else hitPicE.incrementAndGet()
                    return
                }
                hitPicNE.incrementAndGet()
                val parent = File(pic).parent ?: pic
                lastPicParent = parent
                observedDirs.add(parent)
            }
        }
    }

    /**
     * `ok0.x0#n(Ltj/g;)I` —— 批量请求入口（每次批量下载一次，远低于回调）。
     * 职责：imgpath 开启时改写 l1 元素的 imageCachePath；reqmp 开启时记录入参；
     * 无条件收集 imageCachePath 父目录（供 sub=paths）。
     */
    private fun reqHook() = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            hitN.incrementAndGet()
            runCatching {
                val req = param.args.getOrNull(0)
                if (req == null) {
                    ownReqErr("no-arg0")
                    return
                }
                val (state, raw) = readField(req, F_REQ_IMAGES)
                if (state == ReadState.NO_FIELD) {
                    ownReqErr("l1-nofield")
                    return
                }
                if (raw == null) {
                    // 字段在、值确实是 null（或反射取值抛了别的异常）
                    ownReqErr("l1-null")
                    return
                }
                val arr = raw as? Array<*>
                if (arr == null) {
                    // ⚠️ 静态取证说 l1 是 Array，真机若不是 ⇒ dex 版本不符，这是关键信号
                    ownReqErr("l1-not-array cls=${raw.javaClass.name}")
                    return
                }
                if (arr.isEmpty()) {
                    ownReqErr("l1-empty")
                    return
                }
                val target = imgPathDir
                for (el in arr) {
                    if (el == null) continue
                    if (target != null) rewriteImagePath(el, target)
                    collectImageDir(el)
                }
                if (reqmpOn) logReq(req, arr)
            }.onFailure { ownReqErr("ex:" + (it.javaClass.simpleName ?: "?")) }
        }
    }

    /**
     * `jf3.f#k([String)Integer` —— BatchSnsReqImageData 唯一构造点。
     * ⚠️ 该对象是方法体局部变量，反射拿不到，所以这里只计数，不做改写。
     */
    private fun kHook() = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            hitK.incrementAndGet()
        }
    }

    // ---------------------------------------------------------------- I-3 / 入参

    /** 把单个 BatchSnsReqImageData 的 imageCachePath 改写到 target 目录（保留原文件名） */
    private fun rewriteImagePath(el: Any, target: String) {
        val orig = readString(el, F_IMG_PATH) ?: return
        val name = orig.substringAfterLast('/')
        val newPath = if (name.isEmpty()) target else "$target/$name"
        writeObject(el, F_IMG_PATH, newPath)
    }

    /** 收集 imageCachePath 的父目录到观测集合 */
    private fun collectImageDir(el: Any) {
        val p = readString(el, F_IMG_PATH) ?: return
        observedDirs.add(File(p).parent ?: p)
    }

    /** `sub=reqmp`：记录批量请求入参（每张图一条） */
    private fun logReq(req: Any, arr: Array<*>) {
        val feedId = readString(req, F_REQ_FEED_ID) ?: "-"
        val count = readInt(req, F_REQ_PIC_COUNT)
        for (el in arr) {
            if (el == null) continue
            val img = readString(el, F_IMG_PATH) ?: "-"
            val key = readString(el, F_IMG_KEY) ?: "-"
            own("CDN req feedId=$feedId n=$count img=$img key=$key")
        }
    }

    // ---------------------------------------------------------------- sub 命令

    /**
     * `sub=dk`（I-1）：真调一次 `dk.a()` 打返回值，并反射 native
     * `CdnLogic.allowBatchImageDownload()` 与静态 `dk.j`。三者全 unknown 说明类名漂移。
     */
    private fun doDk() {
        val cl = resolveLoader()
        if (cl == null) {
            own("CDN dk ret=unknown nativeOk=unknown staticJ=unknown (no classLoader)")
            return
        }
        val ret = runCatching {
            val cls = XposedHelpers.findClass(CLS_DK, cl)
            XposedHelpers.callStaticMethod(cls, "a") as? Boolean
        }.getOrNull()
        own(
            "CDN dk ret=${bool(ret)} nativeOk=${reflectNativeAllow(cl)} " +
                "staticJ=${readDkJ(cl)}"
        )
    }

    /** 反射调 native `CdnLogic.allowBatchImageDownload()`（失败打 unknown） */
    private fun reflectNativeAllow(cl: ClassLoader): String = runCatching {
        val cls = XposedHelpers.findClass(CLS_CDN_LOGIC, cl)
        bool(XposedHelpers.callStaticMethod(cls, "allowBatchImageDownload") as? Boolean)
    }.getOrDefault("unknown")

    /** 读静态布尔 `dk.j`（第二道门） */
    private fun readDkJ(cl: ClassLoader): String = runCatching {
        val cls = XposedHelpers.findClass(CLS_DK, cl)
        XposedHelpers.getStaticBooleanField(cls, "j").toString()
    }.getOrDefault("unknown")

    /** `sub=paths`：先打最近 picCachePath 父目录，再逐个观测目录打 files/bytes */
    private fun doPaths() {
        own("CDN paths pic=${lastPicParent ?: "none"}")
        val dirs = observedDirs.toList().sorted()
        for (d in dirs) {
            val (files, bytes) = scanDir(d)
            own("CDN path $d files=$files bytes=$bytes")
        }
    }

    /** `sub=ls`：用微信进程权限列目录（/data/data 私有目录只能靠它） */
    private fun doLs(dir: String?, n: Int) {
        val d = dir?.trim()
        if (d.isNullOrEmpty()) {
            own("CDN ls <dir> files=0 bytes=0 (no dir)")
            return
        }
        val (files, bytes, list) = scanDir(d)
        own("CDN ls $d files=$files bytes=$bytes")
        val limit = n.coerceIn(1, 500)
        var shown = 0
        for (f in list.sortedByDescending { it.lastModified() }) {
            if (shown >= limit) break
            shown++
            own("CDN f ${f.absolutePath} size=${f.length()}")
        }
    }

    /**
     * `sub=lsi`：只列**子目录名**（[doLs] 的盲区补丁）。
     *
     * ⚠️ 为什么必须单独一个子命令：[scanDir] 里有 `if (f.isDirectory) continue`，
     * ⇒ `CDN ls <dir> files=0` 有**三种**互不相同的含义，探针区分不了：
     * ① 目录不存在 ② 目录存在但为空 ③ **目录存在但里面全是子目录**。
     * 微信 `voice2/` 正是第 ③ 种（老式布局 `voice2/<msgId/100>/msg_<msgId>.amr`），
     * 于是「voice2 files=0」被误读成「voice2 不存在」—— 2026-10-25 真机踩过，
     * 白跑四轮脚本才定位到这一行 `continue`。
     *
     * 输出（**逐字**，脚本靠它匹配）：
     * ```
     * CDN lsi <dir> subdirs=N
     * CDN d <绝对路径>/<目录名>        ← 无 size，靠 `CDN d ` 前缀与 `CDN f ` 区分
     * ```
     */
    private fun doLsSubdirs(dir: String?, n: Int) {
        val d = dir?.trim()
        if (d.isNullOrEmpty()) {
            own("CDN lsi <dir> subdirs=0 (no dir)")
            return
        }
        // ⚠️ 这里**不**用 scanDir —— 它会把子目录丢掉，正是本子命令要解决的东西。
        //    listFiles() 失败（无权限 / 不存在）返回 null，与「空目录」的 emptyList 区分开。
        val list = runCatching { File(d).listFiles() }.getOrNull()
        if (list == null) {
            own("CDN lsi $d subdirs=0 (unreadable)")
            return
        }
        val dirs = list.filter { it.isDirectory }.sortedBy { it.name }
        own("CDN lsi $d subdirs=${dirs.size}")
        val limit = n.coerceIn(1, 500)
        for (f in dirs.take(limit)) {
            own("CDN d ${f.absolutePath}")
        }
        if (dirs.size > limit) own("CDN lsi truncated=${dirs.size - limit}")
    }


    /**
     * `sub=decode`：I-4 的**强判据**反证 —— 用 `BitmapFactory.decodeFile` 判文件是不是**明文可用图**。
     *
     * 为什么需要它（magic bytes 不够）：magic 只能证明「头几个字节像图片」，证明不了「能解码」。
     * 而本功能的最终问题是「要不要自己解 aeskey」—— 只要能 decode 出宽高，就说明**微信已解好、直接拷走即可**。
     * 用 `inJustDecodeBounds=true` 只读头部、不分配像素内存，避免 OOM。
     *
     * 输出（**逐字**，脚本 `tools/_r9_cdn.py` 的 `_decode_probe` 靠它匹配）：
     *   `CDN decode <file> w=<n> h=<n> ok=<0|1>`
     * ⚠️ 读不到 / 解不出 ⇒ `w=0 h=0 ok=0`（**不是**不打日志，否则脚本无法区分「探针没跑」与「解不出」）。
     */
    private fun doDecode(file: String?) {
        val f = file?.trim()
        if (f.isNullOrEmpty()) {
            own("CDN decode <none> w=0 h=0 ok=0")
            return
        }
        val (w, h) = runCatching {
            val opts = android.graphics.BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeFile(f, opts)
            opts.outWidth to opts.outHeight
        }.getOrElse { 0 to 0 }
        val ok = if (w > 0 && h > 0) 1 else 0
        own("CDN decode $f w=$w h=$h ok=$ok")
    }

    /**
     * `sub=export`：把 dir 下最近 n 个文件拷到可读 cache 目录（I-4 验 magic）。
     * 输出 `CDN export <dst> src=<src> size=<bytes>`，src 必须是 ls/快照里的完整路径。
     */
    private fun doExport(dir: String?, n: Int) {
        val d = dir?.trim()
        if (d.isNullOrEmpty()) {
            own("CDN export (no dir)")
            return
        }
        val dstDir = File(EXPORT_DIR)
        runCatching { dstDir.mkdirs() }
        val (_, _, list) = scanDir(d)
        val limit = n.coerceIn(1, 100)
        var done = 0
        for (f in list.sortedByDescending { it.lastModified() }) {
            if (done >= limit) break
            val dst = File(dstDir, f.name)
            val ok = runCatching { f.copyTo(dst, overwrite = true) }.isSuccess
            if (ok) {
                done++
                own("CDN export ${dst.absolutePath} src=${f.absolutePath} size=${f.length()}")
            }
        }
    }

    /** `sub=stat`：自身状态（hooked / 进程 / 命中计数 / 最近回调时间 + 安装失败原因 note） */
    private fun doStat() {
        val hooked = if (unhooks.isNotEmpty()) 1 else 0
        val proc = currentProcessName()
        val pid = android.os.Process.myPid()
        val lastIso = if (lastCbAt == 0L) "-"
        else SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(lastCbAt))
        // ⚠️ 行首必须是 `CDN stat hooked=`（脚本 tools/_r9_cdn.py 用 last_cdn(lines,"stat hooked=")
        //    子串匹配），新字段一律追加在行尾，别插到 hooked= 前面。
        own(
            "CDN stat hooked=$hooked proc=$proc pid=$pid hitK=${hitK.get()} hitCB=${hitCB.get()} " +
                "last=$lastIso hitN=${hitN.get()} hitPicE=${hitPicE.get()} hitPicNE=${hitPicNE.get()} " +
                "hitDir=${observedDirs.size} hitNoField=${hitNoField.get()}"
        )
        if (installNote.isNotEmpty()) own("CDN stat note=$installNote")
    }

    /** `sub=reset`：清本探针自身计数器 + 熔断（SnsCdnImage 未装时静默忽略） */
    private fun doReset() {
        hitK.set(0)
        hitCB.set(0)
        hitN.set(0)
        hitPicE.set(0)
        hitPicNE.set(0)
        hitNoField.set(0)
        reqErrCount.clear()
        lastCbAt = 0L
        lastPicParent = null
        observedDirs.clear()
        installNote = ""
        runCatching { com.lx.wxgroup.feature.SnsCdnImage.resetBreaker() }
        own("CDN reset ok")
    }

    // ---------------------------------------------------------------- 内部工具

    /** 一次 listFiles，统计（文件数, 字节和, 文件列表），目录遍历失败返回空 */
    private fun scanDir(d: String): Triple<Int, Long, List<File>> {
        val list = runCatching { File(d).listFiles()?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
        var files = 0
        var bytes = 0L
        val out = ArrayList<File>()
        for (f in list) {
            if (f.isDirectory) continue
            files++
            bytes += runCatching { f.length() }.getOrDefault(0L)
            out.add(f)
        }
        return Triple(files, bytes, out)
    }

    private fun readString(obj: Any?, field: String): String? =
        readField(obj, field).second as? String

    private fun readInt(obj: Any?, field: String): Int =
        if (obj == null) 0
        else runCatching { XposedHelpers.getIntField(obj, field) }.getOrDefault(0)

    private fun readObject(obj: Any?, field: String): Any? = readField(obj, field).second

    /** 字段读取的**归因状态**：OK 取到了 / NO_FIELD 字段不存在 / OTHER 其它失败（类型转换、非法访问等） */
    private enum class ReadState { OK, NO_FIELD, OTHER }

    /**
     * 读实例字段并保留**失败原因**（原来的 `getOrNull()` 会把 NoSuchFieldError 和空值混为一谈）。
     * XposedHelpers 找不到字段时抛 `NoSuchFieldError`（可能包在 InvocationTargetException 里），
     * 所以要沿 cause 链找一遍。
     */
    private fun readField(obj: Any?, field: String): Pair<ReadState, Any?> {
        if (obj == null) return ReadState.OTHER to null
        return runCatching { ReadState.OK to XposedHelpers.getObjectField(obj, field) }
            .getOrElse { (if (hasNoFieldCause(it)) ReadState.NO_FIELD else ReadState.OTHER) to null }
    }

    private fun hasNoFieldCause(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            if (e is NoSuchFieldError) return true
            e = e.cause
        }
        return false
    }

    /**
     * `CDN reqerr reason=<具体原因> n=<累计次数>` —— 同类原因只打前 [REQERR_FIRST] 次，
     * 之后按 2 的幂稀疏补打（保证高频时也能看出「还在持续发生」，但不刷屏）。
     */
    private fun ownReqErr(reason: String) {
        val c = reqErrCount.computeIfAbsent(reason) { AtomicInteger() }.incrementAndGet()
        if (c <= REQERR_FIRST || Integer.bitCount(c) == 1) own("CDN reqerr reason=$reason n=$c")
    }

    private fun writeObject(obj: Any, field: String, value: Any?) {
        runCatching { XposedHelpers.setObjectField(obj, field, value) }
    }

    private fun bool(v: Boolean?): String = if (v == null) "unknown" else v.toString()

    /**
     * 进程名三级降级：ActivityThread（反射，`@hide` API）→ /proc/self/cmdline → unknown
     *
     * ⚠️ `android.app.ActivityThread` 是 **`@hide` API**，编译期**不可见**
     * （直接用会报 `Unresolved reference 'ActivityThread'`，本文件已踩过一次）。
     * 只能在运行期反射调用 —— 模块跑在宿主进程里，这个类一定存在。
     *
     * ⚠️ 第二级必须判空串：`/proc/self/cmdline` 读失败时 `readBytes()` 会抛（已 runCatching 兜住），
     * 但读到空内容会返回 `""`，此时**不能**当成有效进程名，要继续降级到 unknown。
     */
    private fun currentProcessName(): String {
        val byReflect = runCatching {
            val cls = Class.forName("android.app.ActivityThread")
            cls.getMethod("currentProcessName").invoke(null) as? String
        }.getOrNull()
        if (!byReflect.isNullOrBlank()) return byReflect

        val byCmdline = runCatching {
            String(File("/proc/self/cmdline").readBytes())
                .substringBefore('\u0000')
                .trim()
        }.getOrNull()
        if (!byCmdline.isNullOrBlank()) return byCmdline

        return "unknown"
    }

    private fun own(msg: String) = runCatching { LogX.i(msg) }
}
