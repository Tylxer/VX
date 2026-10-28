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
 * ## ⛔ 为什么**不用** downloadType 当闸门（R31g 真机实测推翻）
 *
 * 原设计「只认 `downloadType == 1`（微信下压缩图的档位）」。真机 8.0.48 实测
 * `HDIMG installed=true seen=14 fired=0 skipOther=14` —— 微信自己建的 14 次 t1
 * **全部**被这道闸门挡掉，`fired` 一次没起跳。dex 取证（`tools/_r29_g2_clsdump.py`）：
 *
 * ```
 * inst direct -><init>(JJILcom/tencent/mm/modelbase/t0;)V
 * inst direct -><init>(JJILcom/tencent/mm/modelbase/t0;I)V
 * ```
 *
 * 参数序列确实是 `(long, long, int, ...)`，第三参落字段 `o`，而 `doScene` 里
 * `iget o` 只喂给日志 `'doscene id:%d comp:%d off:%d total:%d ...'` —— 所以
 * **取值逻辑没错，错的是「微信传 1」这个假设**。不能再赌一个常量。
 *
 * 现在的起跳判据只有两条硬事实：**`msgId != 0`** 且 **`done` 集合首次收录该 msgId**。
 * 递归防护改由「`done` 去重 + `selfFiring` 线程标记」承担，不依赖档位：
 * 我们自己 `fire` 时复用同一个 msgId ⇒ 必然被 `done` 挡成 `skipDup`。
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

    /**
     * 旧闸门用的「微信下压缩图档位」。**已降级为纯日志标签，不再参与判据** ——
     * 真机实测微信传的不是这个值（见类 KDoc）。留着是为了日志里能一眼看出档位分布。
     */
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
    private val skipOther = AtomicInteger(0) // ⚠️ 已不拦截，仅统计「档位不是 TYPE_THUMB」的次数
    private val fail = AtomicInteger(0)

    /**
     * 「这条 t1 构造是我们自己 fire 出来的」廉价快判，**只在同步路径上有效**。
     *
     * ⛔ R31h 真机反证：`seen=2 fired=1 ok=1 skipDup=1` —— 我们只 fire 了 1 次，
     *    却看见 2 次 t1 构造，且第 2 次落到了 `done` 去重里（skipDup 计数）。
     *    说明 `svc.c()` 内部建 t1 **不在 pool 线程上**，ThreadLocal 没盖住。
     *    ⇒ **真正兜住递归的是 [done]，不是这个标记。** 它的价值只剩「同一线程内
     *    少一次 synchronized 和计数污染」，绝不可当成唯一防线。
     *    （若哪天 [done] 的口径变了，递归就无兜底 —— 改 done 前先想这一步。）
     */
    private val selfFiring = ThreadLocal.withInitial { false }

    /** 档位分布只打一次：key = "参数个数:第一个Int"。用来一次性看清微信到底传什么值 */
    private val sigSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 「看见了构造但拿不到 msgId」的形状（每种只打一条）。
     * ⛔ 存在的唯一理由：这条路径原来是静默 return，导致「图片到达却没 fire」查不出原因。
     */
    private val missSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 「参数解析出来的 id 与实例字段对不上」的形状（每种 sig 只打一条）。
     * ⛔ 存在的唯一理由：那是「微信又换了 t1 构造形态」的唯一信号 —— 不记下来，
     *    下次真机出现「下载下错图 / svc.c 恒 false」时没有任何线索可查。
     */
    private val mismatchSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 拿不到 msgId 的累计次数（探针用） */
    private val noMsgId = AtomicInteger(0)

    @Volatile private var lastErr = ""
    @Volatile private var lastTalker = ""

    /**
     * 进程内去重：一个 msgId 只尝试一次。有上限防泄漏。
     *
     * ⛔ **这是递归的唯一硬兜底**（[selfFiring] 跨线程无效，见其注释）：
     * 我们 fire 出的 t1 构造即便在别的线程被看见，msgId 也必然已在集合里
     * ⇒ `add` 返回 false ⇒ `skipDup`，循环终止。
     *
     * 所以 `done` 的 key 语义**必须是「微信传进来那个 msgId」**，不能换成 localId 或
     * 别的派生值 —— 换成派生值就与我们 fire 出来的那次对不上，递归无界。
     *
     * 淘汰（>1024）后旧 msgId 能被再次 fire，此时靠 [fire] 里的 `ImgInfo.t==1`
     * 判重兜住（微信自己的口径），代价最多一次无效任务投递。
     */
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
            "skipOther=${skipOther.get()} noMsgId=${noMsgId.get()} fail=${fail.get()} " +
            "lastTalker=$lastTalker lastErr=$lastErr"

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
        LogX.i("$TAG installed $C_NETSCENE ctor x${set.size} (开关 ${Keys.CHAT_AUTO_HD_IMG})")
    }

    private val ctorHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // 关着的时候唯一开销就是这一次 Kv 读取，别在这里做任何重活
            if (!Kv.getBool(Keys.CHAT_AUTO_HD_IMG)) return
            // 我们自己发起的下载会二次进这里 —— 线程标记直接放过，别污染计数
            if (selfFiring.get() == true) return
            seen.incrementAndGet()
            val args = param.args ?: return
            var argLocal = 0L
            var argMsg = 0L
            var type = -1
            var nLong = 0
            for (a in args) {
                when {
                    a is Long && nLong == 0 -> { argLocal = a; nLong = 1 }
                    a is Long && nLong == 1 -> { argMsg = a; nLong = 2 }
                    a is Int && type < 0 -> type = a   // 第三个参数 = downloadType（落字段 o）
                }
            }
            // ⛔ R31h 真机（2026-10-28）：撤回后点开时微信建的是 `(0, msgId)` 形态 —— 第一参
            //    localId 为 0，说明「第一参=localId、第二参=msgId」并非所有构造都成立。
            //    `t1` 的实例字段在构造结束时必然已赋值（dex dump：`h/i/j`=localId、`q`=msgId）。
            //    ⚠️ 原来的 `if (msgId == 0L) return` 是**静默丢弃**：真机上有一条被撤回的图片
            //    到达时压根没 fire，日志里连一行都没有 —— 无头案的教科书案例。
            val fldLocal = instLong(param.thisObject, "h").let { if (it > 0) it else 0L }
            val fldMsg = instLong(param.thisObject, "q").let { if (it > 0) it else 0L }
            // ⛔ R34 复核 P2③：**字段优先，参数兜底**。
            //    字段语义（`h/i/j`=localId、`q`=msgId）是 dex dump 直接取证确认的；
            //    而「第一参=localId、第二参=msgId」这个**参数位置语义已经被真机推翻过一次**
            //    （撤回后点开时微信建的是 `(0, msgId)`）。既然一个是取证、一个是假设，
            //    就该让取证的那个说了算 —— 否则参数顺序一旦相反，两个参数都 > 0 ⇒
            //    字段兜底永远不启用 ⇒ 拿着互换的 id 去下，静默下错。
            val localId = if (fldLocal > 0) fldLocal else argLocal
            val msgId = if (fldMsg > 0) fldMsg else argMsg
            // ⚠️ 两者都拿到却对不上 ⇒ 说明当前形态下「参数位置」的语义与假设不符。
            //    这本身不致命（已按字段取值），但它是「微信又换构造形态了」的唯一信号，
            //    必须留痕。按 sig 去重（最多 12 条）防刷屏 —— t1 构造在热路径上。
            if ((fldLocal > 0 && argLocal > 0 && fldLocal != argLocal) ||
                (fldMsg > 0 && argMsg > 0 && fldMsg != argMsg)
            ) {
                if (mismatchSeen.size < 12 && mismatchSeen.add("${args.size}:$type")) {
                    LogX.w(
                        "$TAG id arg/fld mismatch n=${args.size} type=$type " +
                            "args=$argLocal/$argMsg fld=$fldLocal/$fldMsg -> use fld",
                    )
                }
            }
            // 档位分布取证：每种 (参数个数, 首个Int) 只打一条，最多 12 条。零装包成本，
            // 下次真机就能看清微信各场景传的档位 —— 不再靠猜常量。
            if (type != TYPE_THUMB) skipOther.incrementAndGet()
            val sig = "${args.size}:$type"
            if (sigSeen.size < 12 && sigSeen.add(sig)) {
                LogX.i(
                    "$TAG sig n=${args.size} type=$type " +
                        "o=${instInt(param.thisObject, "o")} h=${instLong(param.thisObject, "h")} " +
                        "i=${instLong(param.thisObject, "i")} j=${instLong(param.thisObject, "j")} " +
                        "q=${instLong(param.thisObject, "q")} args=$argLocal/$argMsg",
                )
            }
            if (msgId == 0L) {
                // 参数和字段都拿不到 msgId ⇒ 这条无论如何 fire 不了，必须留下证据（不再静默）
                if (missSeen.size < 8 && missSeen.add(sig)) {
                    LogX.w(
                        "$TAG noMsgId n=${args.size} type=$type args=$argLocal/$argMsg " +
                            "fld=$fldLocal/$fldMsg cls=${param.thisObject?.javaClass?.name}",
                    )
                }
                noMsgId.incrementAndGet()
                return
            }
            synchronized(done) { if (!done.add(msgId)) { skipDup.incrementAndGet(); return } }
            // ⚠️ t1 构造在主线程：下载服务入队会触发 NetScene 投递，绝不能在主线程跑
            runCatching {
                pool.execute {
                    selfFiring.set(true)
                    try {
                        runCatching { fire(localId, msgId) }
                            .onFailure {
                                fail.incrementAndGet()
                                lastErr = it.javaClass.simpleName
                                // ⛔ R34 复核 P2④：失败必须把 msgId 从 `done` 里摘掉。
                                //    `done.add` 发生在 fire **之前**，不摘的话这一次失败
                                //    （网络抖动 / 反射漂移 / svc.c 拒收）就会让这张图在
                                //    **本次微信进程内永远不再自动下载** —— 微信后续重试、
                                //    用户再点开时建 t1 都只会落进 skipDup。
                                //    ⇒ 摘掉让它可被重试；真正的判重仍由 `ImgInfo.t==1`
                                //    和 `done` 的「同一时刻去重」两头兜住，不会递归。
                                synchronized(done) { done.remove(msgId) }
                                LogX.w("$TAG fire err ${it.javaClass.simpleName}: ${it.message} (retryable)")
                            }
                    } finally {
                        selfFiring.set(false)
                    }
                }
            }.onFailure { LogX.w("$TAG submit err ${it.javaClass.simpleName}") }
        }
    }

    private fun instInt(o: Any?, name: String): Int =
        o?.let { runCatching { XposedHelpers.getIntField(it, name) }.getOrNull() } ?: -999

    private fun instLong(o: Any?, name: String): Long =
        o?.let { runCatching { XposedHelpers.getLongField(it, name) }.getOrNull() } ?: -999L

    /**
     * 从 ImgInfo 里捞出任意一个「像图片路径」的字符串字段（纯日志用）。
     *
     * ⚠️ **不写死字段名**：ImgInfo 的字段全被混淆成 `e`/`f`/`g` 这种短名，写死就是赌，
     *    赌错只表现为「日志里是 null」，反而更容易误判成「没有 imgPath」。
     *    这里遍历所有 String 字段，取第一个带 `://` 或长度落在 24..40 的值。
     *    只在 fire 时跑一次（每张图一次），开销可忽略。
     */
    private fun imgPathOf(img: Any?): String? {
        if (img == null) return null
        return runCatching {
            img.javaClass.declaredFields.asSequence()
                .filter { it.type == String::class.java }
                .mapNotNull { f ->
                    runCatching { f.isAccessible = true; f.get(img) as? String }.getOrNull()
                }
                .firstOrNull { it.isNotBlank() && (it.contains("://") || it.length in 24..40) }
        }.getOrNull()
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
        // ⚠️ 把 `t` 与 imgPath 带进日志：`t==1` 是「原图已下完」的微信口径，
        //    没有这两个值就没法判「是我们没下」还是「早就有了」，只能靠现象猜。
        val imgT = if (img != null) runCatching { XposedHelpers.getIntField(img, "t") }.getOrNull() else null
        val imgPath = imgPathOf(img)
        if (imgT == 1) { already.incrementAndGet(); return }

        val allowRaw = Kv.getList(Keys.CHAT_AUTO_HD_ALLOW)
        val excludeRaw = Kv.getList(Keys.CHAT_AUTO_HD_EXCLUDE)
        // ⛔ R31g 真机：`seen=20 fired=11 skipScope=11 ok=0 fail=0`，
        //    `lastErr=talker:NullPointerException` —— 11 次全被名单判定拦在
        //    `ListResolver.inScope` 第 121 行（`talker` 为空直接 return false），
        //    一次都没走到 `svc.c()`。而 `talkerOf()` 在本版跳 NPE 是**另一件事**：
        //    名单为空时它根本不该被调用（语义就是「不过滤」）。
        //    ⇒ 名单为空短路，省掉一次反射查询，也避开那个 NPE。
        if (allowRaw.isNotEmpty() || excludeRaw.isNotEmpty()) {
            val (groups, labels) = buckets(allowRaw, excludeRaw)
            val talker = talkerOf(msgId, cl)
            lastTalker = talker.orEmpty()
            // ⛔ R34 复核 P2②：talker 拿不到时**不能一律拒绝**。
            //    `ListResolver.inScope` 对空 talker 返回 false（宁可漏判，与 NotifyMute 同口径），
            //    但那在**纯排除模式**下会把语义整个反过来：用户配的是「排除这几个、其余都下」，
            //    talkerOf 一失败（R31g 真机已见 `talker:NullPointerException`）就变成
            //    「谁都不下」，且日志里只有 skipScope 在涨 —— 静默全挡。
            //    ⇒ 纯排除模式（allowRaw 为空）下拿不到 talker 就**放行**，语义才是「默认生效」。
            // ⛔ 但**允许名单模式必须保持拦截**：那边 talker 为空放行 = 「只想对某群生效」
            //    退化成「对全部会话生效」，是另一个方向的语义反转。
            val talkerUnknown = talker.isNullOrBlank()
            if (talkerUnknown && allowRaw.isEmpty()) {
                LogX.w(
                    "$TAG talker unknown -> pass (exclude-only) msgId=$msgId " +
                        "exclude=${excludeRaw.size} err=$lastErr",
                )
            } else if (!ListResolver.inScope(
                    talker = talker,
                    allowRaw = allowRaw,
                    excludeRaw = excludeRaw,
                    groups = groups,
                    labels = labels,
                )
            ) {
                // ⚠️ 判定口径与 NotifyMute 完全一致（排除优先 / 允许空 = 全部生效 / 空分组不退化）
                skipScope.incrementAndGet()
                // ⛔ 同 P2④，但**只针对「没判出来」而不是「判出来不在范围」**：
                //    allow 模式下 talker 取不到，我们并不知道这条到底在不在范围里
                //    （是一次取数失败，不是一个判定结果）—— 不留它永久占位。
                //    真被判定为「不在名单里」的那些（talker 拿到了）**照旧占位**，
                //    否则每条不在范围内的消息都会被反复做名单展开。
                if (talkerUnknown) synchronized(done) { done.remove(msgId) }
                return
            }
        }

        val listener = newListener(cl) ?: throw IllegalStateException("listener null")
        val r = XposedHelpers.callMethod(
            svc, "c",
            localId, msgId, TYPE_HD,
            java.lang.Long.valueOf(msgId), 0, listener,
        )
        if (r == true) {
            ok.incrementAndGet()
            LogX.i("$TAG fired msgId=$msgId localId=$localId t=$imgT img=${imgPath?.take(36)} talker=$lastTalker")
        } else {
            fail.incrementAndGet()
            lastErr = "svc.c returned $r"
            // ⛔ 同 P2④：`svc.c` 返回非 true 也是一次失败（不抛异常，走不到外层 onFailure），
            //    同样要把 msgId 摘出 `done`，否则这一次拒收会永久挡住后续重试。
            synchronized(done) { done.remove(msgId) }
            LogX.w("$TAG svc.c rejected msgId=$msgId -> $r (retryable)")
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
        }.onFailure {
            // ⚠️ 只记类名不够（NPE 有无数种来源），带上**首个非反射栈帧**，
            //    下次真机日志能直接指到是 `b1.a9()` 还是 `h9.u3()` 炸的。
            val f = it.stackTrace.firstOrNull { s -> s.className.startsWith("com.tencent.") }
            lastErr = "talker:${it.javaClass.simpleName}@" +
                (f?.let { s -> "${s.className.substringAfterLast('.')}.${s.methodName}" } ?: "?")
        }.getOrNull()
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
