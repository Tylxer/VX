package com.lx.wxgroup.feature

import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 聊天「自动加载原图」—— 收到图片消息就后台把原图（HD）下下来，不用手点「查看原图」。
 *
 * ## 为什么挂 `t1.<init>`（NetSceneGetMsgImg 的构造函数）而不是找消息入库点
 *
 * 微信收到图片消息后会**自己**下一个压缩图，那条路必经 `t1`（`NetSceneGetMsgImg`），
 * 而 `t1` 的构造参数里**直接带着** `localId / msgId / downloadType` —— 等于微信把
 * 我们需要的三个入参喂到嘴边。反过来，自己去 hook「消息入库」要赌存储层的方法签名，
 * 赌错就是静默失效（本项目最大的坑，见 `开发回路速查.md`）。
 *
 * ⛔ **防递归**：我们下原图时也会 `new t1(..., downloadType=0, ...)`，会再次进这个 hook。
 * 所以只认 `downloadType == [TYPE_THUMB]`（微信下压缩图用的档位）作为起跳点。
 *
 * ## 判重
 * 用微信自己的口径：`ImgInfo.t == 1` 表示原图已下完（`AutoGetBigImgLogic` 里
 * 打日志 "already has hd thumb" 用的就是这一个字段）。加上进程内 `done` 集合双保险。
 *
 * ## 风险（已知，接受）
 * 原图走真实 CDN、实打实耗流量，且下载完成后微信会**写自己的库**
 * （`ImgInfo.t=1`、`MsgInfo` 更新）—— 即本功能**不是只读**。所以默认关闭，
 * 且必须支持允许/排除名单来收敛范围。
 */
object AutoHdImg {

    private const val TAG = "HDIMG"

    // ---- 类名（8.0.48/2588，来自 dex 取证；漂移时 install 会打 warn 而不是崩）----
    private const val C_NETSCENE = "com.tencent.mm.modelimage.t1"   // NetSceneGetMsgImg
    private const val C_CORE = "com.tencent.mm.modelimage.d3"       // 图片子核心
    private const val C_SERVICE = "com.tencent.mm.modelimage.m"     // DownloadImgService
    private const val C_FOUNDATION = "com.tencent.mm.plugin.messenger.foundation.b1"
    private const val C_LISTENER = "fv.e0"                          // IOnSceneEnd 族

    /** 微信自己下压缩图用的 downloadType —— 只有它才是我们的起跳信号 */
    private const val TYPE_THUMB = 1

    /** 我们要下的原图档位（与 `AutoGetBigImgLogic` 里写死的 0 一致） */
    private const val TYPE_HD = 0

    @Volatile private var installed = false

    /**
     * 宿主（微信）的 ClassLoader。
     * ⛔ 绝不能用 `AutoHdImg::class.java.classLoader` —— 那是**模块**的 loader，
     * 找不到 `com.tencent.mm.*` 任何一个类（静默全失效，日志里只看到一堆 fail）。
     */
    @Volatile private var hostCl: ClassLoader? = null

    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wxg-hdimg").apply { isDaemon = true }
    }

    // ---- 计数器（只看得到现象时，这些是唯一线索）----
    private val seen = AtomicInteger(0)      // 看见过多少次 t1 构造
    private val fired = AtomicInteger(0)     // 真正发起过多少次下载调用
    private val ok = AtomicInteger(0)        // 服务返回 true
    private val already = AtomicInteger(0)   // 已有原图（ImgInfo.t==1）
    private val skipScope = AtomicInteger(0) // 名单没命中
    private val skipDup = AtomicInteger(0)   // 进程内去重命中
    private val skipOther = AtomicInteger(0) // 非压缩图档位（含我们自己下的原图）
    private val fail = AtomicInteger(0)

    @Volatile private var lastErr = ""
    @Volatile private var lastTalker = ""

    /** 进程内去重：一个 msgId 只尝试一次（避免同一张图被反复建任务）。有上限防泄漏 */
    private val done = object : LinkedHashSet<Long>(128) {
        override fun add(element: Long): Boolean {
            val r = super.add(element)
            if (size > 1024) {
                val it = iterator()
                it.next()
                it.remove()
            }
            return r
        }
    }

    fun stat(): String =
        "$TAG installed=$installed seen=${seen.get()} fired=${fired.get()} ok=${ok.get()} " +
            "already=${already.get()} skipScope=${skipScope.get()} skipDup=${skipDup.get()} " +
            "skipOther=${skipOther.get()} fail=${fail.get()} lastTalker=$lastTalker lastErr=$lastErr"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val t1 = runCatching { XposedHelpers.findClass(C_NETSCENE, cl) }.getOrNull()
        if (t1 == null) {
            // ⛔ 类名漂移必须看得见：静默失败的话「开关开了没反应」会变成无头案
            LogX.w("$TAG class missing $C_NETSCENE (disabled)")
            return
        }
        val set = runCatching { XposedBridge.hookAllConstructors(t1, ctorHook) }
            .onFailure { LogX.e("$TAG hook ctor failed", it) }
            .getOrNull()
        if (set.isNullOrEmpty()) {
            LogX.w("$TAG no ctor hooked (disabled)")
            return
        }
        hostCl = cl
        installed = true
        LogX.i("$TAG installed $C_NETSCENE ctor x${set.size} (默认关闭：${Keys.CHAT_AUTO_HD_IMG})")
    }

    private val ctorHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // 关着的时候唯一开销就是这一次 Kv 读取，别在这里做任何重活
            if (!Kv.getBool(Keys.CHAT_AUTO_HD_IMG)) return
            seen.incrementAndGet()
            val args = param.args ?: return
            var localId = 0L
            var msgId = 0L
            var type = -1
            var nLong = 0
            for (a in args) {
                when {
                    a is Long && nLong == 0 -> { localId = a; nLong = 1 }
                    a is Long && nLong == 1 -> { msgId = a; nLong = 2 }
                    a is Int && type < 0 -> type = a   // 第一个 int 就是 downloadType
                }
            }
            // ⛔ 只借「微信自己下压缩图」这一脚。type==0 是我们自己下的原图 → 必须放过
            if (type != TYPE_THUMB) { skipOther.incrementAndGet(); return }
            if (msgId == 0L) return
            synchronized(done) { if (!done.add(msgId)) { skipDup.incrementAndGet(); return } }
            // ⚠️ t1 构造在主线程：下载服务入队会触发 NetScene 投递，绝不能在主线程跑
            runCatching {
                pool.execute {
                    runCatching { fire(localId, msgId) }
                        .onFailure {
                            fail.incrementAndGet()
                            lastErr = it.javaClass.simpleName
                            LogX.w("$TAG fire err ${it.javaClass.simpleName}: ${it.message}")
                        }
                }
            }.onFailure { LogX.w("$TAG submit err ${it.javaClass.simpleName}") }
        }
    }

    /**
     * 发起一次原图下载。全程反射 + runCatching：微信任一层漂移都只丢这一次，不崩。
     */
    private fun fire(localId: Long, msgId: Long) {
        val cl = hostCl ?: throw IllegalStateException("hostCl null (install 没成功)")
        fired.incrementAndGet()

        val core = XposedHelpers.findClass(C_CORE, cl)
        val svc = XposedHelpers.callStaticMethod(core, "F8")            // DownloadImgService
            ?: throw IllegalStateException("d3.F8() null")

        // 判重：已有原图就不下（微信 AutoGetBigImgLogic 同款口径）
        val storage = XposedHelpers.callStaticMethod(core, "O8")         // ImgInfoStorage
        val img = storage?.let { runCatching { XposedHelpers.callMethod(it, "o2", localId) }.getOrNull() }
        if (img != null) {
            val t = runCatching { XposedHelpers.getIntField(img, "t") }.getOrNull()
            if (t == 1) { already.incrementAndGet(); return }
        }

        val allowRaw = Kv.getList(Keys.CHAT_AUTO_HD_ALLOW)
        val excludeRaw = Kv.getList(Keys.CHAT_AUTO_HD_EXCLUDE)
        val (groups, labels) = buckets(allowRaw, excludeRaw)
        val talker = talkerOf(msgId, cl)
        lastTalker = talker.orEmpty()
        // ⚠️ 判定口径与 NotifyMute 完全一致（排除优先 / 允许空 = 全部生效 / 空分组不退化）
        if (!ListResolver.inScope(
                talker = talker,
                allowRaw = allowRaw,
                excludeRaw = excludeRaw,
                groups = groups,
                labels = labels,
            )
        ) {
            skipScope.incrementAndGet()
            return
        }

        val listener = newListener(cl) ?: throw IllegalStateException("listener null")
        val r = XposedHelpers.callMethod(
            svc, "c",
            localId, msgId, TYPE_HD,
            java.lang.Long.valueOf(msgId), 0, listener,
        )
        if (r == true) {
            ok.incrementAndGet()
            LogX.i("$TAG fired msgId=$msgId localId=$localId talker=$talker")
        } else {
            fail.incrementAndGet()
            lastErr = "svc.c returned $r"
            LogX.w("$TAG svc.c rejected msgId=$msgId -> $r")
        }
    }

    // ------------------------------------------------------------ 名单展开（g:/t:）

    private const val SCOPE_TTL_MS = 60_000L

    @Volatile private var bucketsAt = 0L
    @Volatile private var bucketsGroups: Map<String, List<String>> = emptyMap()
    @Volatile private var bucketsLabels: Map<String, List<String>> = emptyMap()

    /**
     * 名单里出现 `g:` / `t:` 前缀时才查库展开，否则直接空表 —— 与 `NotifyMute.scopeBuckets()`
     * 同款「按需 + TTL」策略：名单里没有分组就绝不碰 SQLite（这条路径跑在下载线程上，
     * 但查库仍是几十次查询，能省则省）。
     */
    private fun buckets(
        allow: List<String>,
        exclude: List<String>,
    ): Pair<Map<String, List<String>>, Map<String, List<String>>> {
        val need = (allow + exclude).any {
            val s = it.trim()
            s.startsWith(ListResolver.PREFIX_GROUP) || s.startsWith(ListResolver.PREFIX_LABEL)
        }
        if (!need) return emptyMap<String, List<String>>() to emptyMap()
        val now = android.os.SystemClock.elapsedRealtime()
        if (bucketsAt != 0L && now - bucketsAt < SCOPE_TTL_MS) return bucketsGroups to bucketsLabels
        synchronized(this) {
            if (bucketsAt != 0L && now - bucketsAt < SCOPE_TTL_MS) return bucketsGroups to bucketsLabels
            val g = LinkedHashMap<String, List<String>>()
            runCatching {
                val db = com.lx.wxgroup.data.GroupDb.current()
                if (db != null) {
                    for (grp in db.listGroups()) {
                        runCatching { g[grp.id.toString()] = db.memberWxids(grp.id).toList() }
                    }
                }
            }.onFailure { lastErr = "buckets:${it.javaClass.simpleName}" }
            val l = runCatching { LabelSource.membersByLabelId() }
                .getOrDefault(emptyMap())
            bucketsGroups = g
            bucketsLabels = l
            bucketsAt = now
            return g to l
        }
    }

    /** msgId → MsgInfo → talker。拿不到就返回 null（`inScope` 会按「不在范围」处理） */
    private fun talkerOf(msgId: Long, cl: ClassLoader?): String? {
        if (cl == null) return null
        return runCatching {
            val f = XposedHelpers.findClass(C_FOUNDATION, cl)
            val st = XposedHelpers.callStaticMethod(f, "a9") ?: return null
            val mi = XposedHelpers.callMethod(st, "u3", msgId) ?: return null
            runCatching { XposedHelpers.callMethod(mi, "getTalker") as? String }.getOrNull()
                ?: runCatching { XposedHelpers.getObjectField(mi, "field_talker") as? String }.getOrNull()
                ?: runCatching { XposedHelpers.getObjectField(mi, "talker") as? String }.getOrNull()
        }.onFailure { lastErr = "talker:${it.javaClass.simpleName}" }.getOrNull()
    }

    /**
     * `m.c(...)` 的最后一个参数不能为 null（源码里直接 `return false` 并打 "listener is null"）。
     * 用动态代理造一个空实现 —— 只要 `fv.e0` 是接口就成立，不是接口时这里会抛、被 runCatching 记下。
     */
    private fun newListener(cl: ClassLoader?): Any? {
        if (cl == null) return null
        val iface = runCatching { XposedHelpers.findClass(C_LISTENER, cl) }.getOrNull() ?: return null
        if (!iface.isInterface) {
            lastErr = "listener not interface"
            return null
        }
        return Proxy.newProxyInstance(cl, arrayOf(iface)) { _, m, _ ->
            when (m?.name) {
                "toString" -> "wxg-hdimg"
                "hashCode" -> 1
                "equals" -> false
                else -> when (m?.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        }
    }
}
