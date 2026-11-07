package com.lx.wxgroup.probe

import com.lx.wxgroup.feature.SnsGuard
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * R44 · Route C 真机验证探针 —— **主动让微信自己去下载朋友圈视频**
 * （`op=moments sub=dispatch sub2=<snsId>`）。
 *
 * ## 为什么需要它
 *
 * 目标是「全后台无感知」保存特定好友的朋友圈视频（不拉起界面、不闪屏）。
 * 后台同步与滚动都**不下视频**，裸 HTTP 只拿到**密文**（解密在微信 native 层）。
 * ⇒ 唯一的路是**让微信自己的下载器去下**，我们只负责把它叫起来。
 *
 * ## 链路（全部静态取证已钉死）
 *
 * ```
 * f4#ea()                       // getSnsDownloadManager → l0 单例（懒加载，反射可调）
 *   → l0#d(vm3, I, m0, x7)Z     // addDownLoadSns：门面，21 units，只转调 e
 *       → l0#e(...)             // 补 "" "" 0xa → 转调 f
 *           → l0#f(...)         // 判 vm3 空 → 转调 h
 *               → l0#h(...)     // addDownLoadSnsImpl：算路径、判 sd 卡、入 l0.l 队列
 *                   → l0#z(...) // tryStartNetscene：new Ljf3/l(SnsDownloadSight)
 *                               //   → c3(SnsCdnDownloadBase) → native 解密 + 落盘明�� MP4
 * ```
 *
 * ⭐ **不需要 aeskey**：`l0` 内部从 `vm3` 自己取（这是 R44-01「Route C 唯一硬门槛」的答案）。
 * ⭐ **不需要自己 parse protobuf**：`TimeLineObject#parseFrom(content)` 得到活对象，
 *    媒体在 `ContentObj.h`（`LinkedList<vm3>`，proto field 8）。
 * ⭐ **不需要 UI**：`l0.a` 那个 Handler 来自 `f4#Na()`（SnsCore 级），不是 Activity 的。
 *
 * ## 字段语义（`vm3`，已双证：proto field 序 + 真机 dump）
 *
 * | 字段 | proto | 语义 |
 * |---|---|---|
 * | `d` | 8.5.1 | **mediaId**（20 位数字）—— ⛔ 不是 URL |
 * | `e` | 8.5.2 | 媒体子类型 int |
 * | `g` | 8.5.4 | **视频主 URL（/20202/）** |
 * | `i` | 8.5.6 | 封面 URL（/20250/） |
 * | `h` | 8.5.5 | 与 `g` 成对的 int |
 *
 * ## 用法
 *
 * ```
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
 *   --es op moments --es sub dispatch --es sub2 <snsId> [--es sub3 probe|real]
 * ```
 *
 * - `sub3=probe`（默认）：**只读到 vm3 就停**，打 url/mediaId/媒体列表长度，**不触发下载**。
 *   用于确认「能不能拿到 vm3」这个前提。
 * - `sub3=real`：**真的调 `l0#d`**。只有 probe 全绿之后才用。
 *
 * ⛔ 三红线（写死在本文件里，不许绕过）：
 * 1. **进入**：`sub3=real` 显式要求 + `vm3.g`（URL）非空 + 落盘文件不存在 + 空间足够；
 * 2. **返回**：`vm3 == null` / `g` 空 / mediaId 前缀是 `Locall_path`|`pre_temp_sns_pic`
 *    ⇒ 直接 return，**不发网络**；
 * 3. **复位**：本探针**单条**（一次广播一个 snsId），无游标、无共享 flag ⇒ 天然不串味。
 */
object SnsDispatchProbe {

    private const val TAG = "SNSPUSH"

    private const val CLS_F4 = "com.tencent.mm.plugin.sns.model.f4"
    private const val CLS_L0 = "com.tencent.mm.plugin.sns.model.l0"
    private const val CLS_X7 = "com.tencent.mm.storage.x7"
    private const val CLS_TLO = "com.tencent.mm.protocal.protobuf.TimeLineObject"

    private const val CLS_M0 = "bf3.m0"

    /** `l0#d` 的第 3 参是 `bf3/m0`（SnsDecodeElment）；先试空构造，失败就用 null。 */
    private const val MIN_FREE_BYTES = 200L * 1024 * 1024

    @Volatile
    private var loader: ClassLoader? = null

    fun prepare(lpparam: XC_LoadPackage.LoadPackageParam) {
        loader = lpparam.classLoader
    }

    /**
     * @param arg 第一个参数：snsId 字符串；传 `list` 时走候选列举模式
     * @param mode `probe`（默认，只读）/ `real`（真下发）
     */
    fun run(arg: String?, mode: String?): String {
        val cl = loader ?: return "$TAG no classLoader"
        // 候选列举：真机第一步不知道哪个 snsId 是视频动态，必须先列出来挑样本
        if (arg?.trim()?.lowercase() == "list") return listRecent(cl, mode)
        // 目录诊断：`f4#F8` 到底指向哪、分桶目录长什么样。
        // ⛔ 必须由模块自己读 —— `adb shell` 对 `MicroMsg/<hash>` 是 **Permission denied**，
        //    但模块跑在微信进程内，读自己 app 的 external dir 毫无障碍。
        if (arg?.trim()?.lowercase() == "dir") return dumpDirs(cl, mode)
        // `l0` 字段结构诊断：`l0.l` 是不是队列、`h`/`z` 到底在哪一步返回 —— 靠**实测**字段，
        // 不靠反汇编里对字段名的推断（R44 已因推断字段踩过两次）。
        if (arg?.trim()?.lowercase() == "l0dump") return dumpL0(cl)
        val snsId = arg?.trim()?.toLongOrNull() ?: 0L
        if (snsId == 0L) return "$TAG bad snsId='${arg ?: ""}'（要 64 位 Long，可为负；或 list 列举候选）"
        // ⚠️ 用 startsWith 而不是 ==：`real-looper` 是新加的实验档，写 == 会把它误判成 probe
        val real = (mode ?: "probe").trim().lowercase().startsWith("real")

        // ---- 1. 取 SnsInfo 行（只读，复用既有只读通道）----
        val row = findRow(snsId)
            ?: return "$TAG row miss snsId=$snsId（库里没有这条，或本页扫不到）"
        val content = row.content
        if (content == null || content.isEmpty()) {
            return "$TAG content empty snsId=$snsId type=${row.type}"
        }

        // ---- 2. content → TimeLineObject（活对象，不用自己解析）----
        val tlo = parseTlo(cl, content)
            ?: return "$TAG parseFrom null snsId=$snsId bytes=${content.size}"

        // ---- 3. ContentObj.h = LinkedList<vm3> ----
        val contentObj = runCatching { XposedHelpers.getObjectField(tlo, "ContentObj") }.getOrNull()
            ?: return "$TAG ContentObj null snsId=$snsId type=${row.type}"
        val medias = runCatching {
            XposedHelpers.getObjectField(contentObj, "h") as? List<*>
        }.getOrNull()
        if (medias == null || medias.isEmpty()) {
            return "$TAG no media snsId=$snsId type=${row.type}"
        }

        val sb = StringBuilder()
        sb.append("$TAG snsId=$snsId type=${row.type} medias=${medias.size} real=$real")

        // ---- 4. 逐条媒体打印（probe 模式到此为止）----
        for ((i, m) in medias.withIndex()) {
            if (m == null) continue
            sb.append(
                "\n  [$i] mediaId=${str(m, "d")} subType=${intOf(m, "e")} " +
                    "url=${str(m, "g")?.take(70) ?: "-"} " +
                    "cover=${str(m, "i")?.take(50) ?: "-"}"
            )
        }
        // 目标选择与 `list` 共用同一份判据，避免「列出来的挑不中」
        val picked = pickVideo(medias)
            ?: return sb.append("\n  ⇒ 没有可下载的媒体（URL 空或全是本地伪媒体）").toString()
        val firstVideo = picked.obj
        val firstUrl = picked.url
        val firstMediaId = picked.mediaId

        // ---- 5. 红线 2：返回边界 ----
        sb.append("\n  pick mediaId=$firstMediaId")
        sb.append("\n  url=$firstUrl")
        // 已落盘判据：所有候选根扫一遍（根可能不是 F8 给的那个，见 snsRoots 注释）
        val exist0 = findSight(cl, firstMediaId)
        for ((rootTag, root) in snsRoots(cl)) {
            sb.append("\n  root[$rootTag]=${root.path} exists=${root.isDirectory}")
        }
        sb.append("\n  cacheHit=${exist0?.let { "${it.first}:${it.second.path} (${it.second.length()})" } ?: "-"}")
        if (exist0 != null && exist0.second.length() > 0) {
            return sb.append("\n  ⇒ 已落盘，不需要下载（real 模式也不会重复下发）").toString()
        }
        // 从落盘目录往上找第一个**已存在**的祖先，拿它的可用空间（目录还没建时 usableSpace=0）
        val free: File? = exist0?.second?.let { d ->
            var q: File? = d.parentFile
            while (q != null && !q.exists()) q = q.parentFile
            q
        } ?: run {
            // 还没命中过文件 ⇒ 用第一个存在的根判空间
            var q: File? = snsRoots(cl).firstOrNull { it.second.isDirectory }?.second
            while (q != null && !q.exists()) q = q.parentFile
            q
        }
        if (free != null && free.usableSpace < MIN_FREE_BYTES) {
            return sb.append("\n  ⇒ 空间不足 ${free.usableSpace / 1048576}MB < 200MB，不下发").toString()
        }
        if (!real) {
            return sb.append("\n  ⇒ probe 模式（未下发）。确认上面 url 是 /20202/ 后，用 sub3=real 再跑一次").toString()
        }

        // ---- 6. 真下发：f4#ea() → l0#d(vm3, vm3.e, m0, x7.c) ----
        val l0 = getDownloadManager(cl)
            ?: return sb.append("\n  ⇒ f4#ea() 与 f4.E 都取不到 DownloadManager（见日志）").toString()

        val x7c = runCatching {
            XposedHelpers.getStaticObjectField(XposedHelpers.findClass(CLS_X7, cl), "c")
        }.getOrNull()
        // m0：`bf3/m0` **没有无参构造**（真机实测 ctors 只有 `(List)` 和 `(vm3)`）。
        // 文档 §1.3 说的「`<init>()` 空 LinkedList 构造」是**错的**。
        // ⇒ 传 `(vm3)` 那个构造，它是 m0 对**单个媒体**的自然包装，且非 null 更重要
        //   （m0 会落进 `Ljf3/a.h`(DownloadItem)，下游 `c3` 是否读它我们没验过）。
        val m0 = runCatching {
            val c = XposedHelpers.findClass(CLS_M0, cl)
            val ctor = c.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 1 && it.parameterTypes[0].simpleName == "vm3"
            }
            if (ctor != null) {
                ctor.isAccessible = true
                ctor.newInstance(firstVideo)
            } else null
        }.getOrNull()

        val subType = intOf(firstVideo, "e")
        sb.append("\n  dispatch l0=${l0.javaClass.simpleName} subType=$subType m0=${m0 != null} x7=${x7c ?: "null"}")
        // m0 为什么失败？把构造器签名与异常都打出来，别只留一个 false
        if (m0 == null) {
            val info = runCatching {
                val c = XposedHelpers.findClass(CLS_M0, cl)
                "ctors=" + c.declaredConstructors.joinToString(";") {
                    "(" + it.parameterTypes.joinToString(",") { p -> p.simpleName } + ")"
                }
            }.getOrElse { "clsErr=${it.javaClass.simpleName}" }
            sb.append("\n  ⚠️ m0 仍为 null: $info")
        }
        // 下发前先看队列里已有什么（判断「入队但不出队」的直接证据）
        sb.append("\n  下发前 l0.l=${queueOf(l0)} l0.e=${queueOf(l0, "e")}")

        // ⭐ R44 决定性诊断：直接调 `l0#f`（8 参，`d` 最终转调到的那个），看它**自己的返回值**。
        //    `d` 返回 true 但队列为空 ⇒ 一定是 `f` 或 `h` 提前 return 了。
        //    `f` 返回 false = 没通过前置判据；`f` 返回 true 但队列仍空 = 卡在 `h`（sd 卡/本地伪媒体）。
        sb.append("\n  l0#f 直调=${callF(l0, firstVideo, subType, m0, x7c)}")

        // ⛔ 必须用 Method 对象显式 invoke，不能用 XposedHelpers.callMethod：
        //    后者靠**实参运行时类型**匹配重载，而 m0/x7c 可能为 null ⇒ 无法推断 ⇒ 必抛异常。
        //    用 declaredMethods（不是 methods）：`d` 是 l0 自己声明的，含继承反而可能撞上
        //    父类的同名 4 参方法，`sig=` 一打就会看到选错。
        val cands = runCatching {
            l0.javaClass.declaredMethods.filter { it.name == "d" && it.parameterTypes.size == 4 }
        }.getOrNull().orEmpty()
        if (cands.isEmpty()) {
            return sb.append("\n  ⇒ l0 里找不到 d(4 参)，无法下发；请把 l0 的方法清单打出来核对")
                .toString()
        }
        // 多个候选时优先「第 1 参是 vm3 类」的那个（addDownLoadSns 的签名特征）
        val m = cands.firstOrNull { it.parameterTypes[0].simpleName == "vm3" } ?: cands[0]
        m.isAccessible = true
        sb.append("\n  l0#d n=${cands.size} sig=" +
            m.parameterTypes.joinToString(",") { it.simpleName })

        // ⭐ R44 关键实验（2026-11-08 真机）：`mode` 传 `real-looper` 时，改到
        //    **微信自己的 Handler（`f4#Na` 那个 o3）** 上执行一次 `l0#d`。
        //
        // 为什么：真机实测 `real`（裸 Thread，无 Looper）返回 `true` 但 40s 不落盘；
        // 反汇编里 `l0#h` 对 `Looper.myLooper()` 有两分支 —— 有 Looper 走
        // `MessageQueue.addIdleHandler`，无线程 Looper 走 `l0#l`（checkIdleFile）+ `postDelayed`。
        // 「兜底也能下」只是推断，本次实验就是判它的。
        val onLooper = (mode ?: "").trim().lowercase().contains("looper")
        if (onLooper) {
            val handler = getSnsHandler(cl)
            if (handler == null) {
                return sb.append("\n  ⇒ f4#Na 取不到 o3 Handler，无法在 Looper 线程执行").toString()
            }
            sb.append("\n  改投 f4#Na Handler（有无 Looper：本线程=${android.os.Looper.myLooper() != null}）")
            val done = java.util.concurrent.CountDownLatch(1)
            var retObj: Any? = null
            var err: Throwable? = null
            val run = Runnable {
                runCatching { m.invoke(l0, firstVideo, subType, m0, x7c) }
                    .onSuccess { retObj = it }
                    .onFailure { err = it }
                done.countDown()
            }
            val posted = runCatching {
                val pm = handler.javaClass.methods.firstOrNull {
                    it.name == "post" && it.parameterTypes.size == 1
                }
                pm?.invoke(handler, run) as? Boolean
            }.getOrNull()
            sb.append("\n  post=$posted（Handler 内 Looper=${runCatching {
                XposedHelpers.callMethod(handler, "getLooper")
            }.getOrNull()}）")
            runCatching { done.await(10, java.util.concurrent.TimeUnit.SECONDS) }
            sb.append("\n  l0#d(looper) returned=${retObj ?: "null"} err=${err?.javaClass?.simpleName ?: "-"}")
        } else {
            val ret = runCatching {
                m.invoke(l0, firstVideo, subType, m0, x7c)
            }.onFailure {
                LogX.w(
                    "$TAG l0#d threw ${it.javaClass.simpleName}: " +
                        "${(it.cause ?: it).message?.take(100)}"
                )
            }
            sb.append("\n  l0#d returned ${ret.getOrNull() ?: "EXCEPTION"}")
        }
        sb.append("\n  ⇒ 看日志里 DownloadManager 的 addDownLoadSns / tryStartNetscene / SnsCdnDownloadBase")
        // ⭐ 判「入队但没出队」：`l0.h` 末尾是 `l0.l.size()>0 → l0#z`，
        //    `z` 由 addIdleHandler / postDelayed **异步**驱动 ⇒ 队列非空就是铁证。
        sb.append("\n  下发后 l0.l=${queueOf(l0)} l0.e=${queueOf(l0, "e")}")
        // ⭐ `l0#z()` = tryStartNetscene，**真正发网络**那一步，且是 public 无参 `()V`。
        //    真机实测：`d`/`f` 都返回 true，但 `l`/`e` 两个队列全程 size=0 且不落盘
        //    ⇒ 请求被判「已处理」却没真的发出去。手动再踢一脚 `z` 是最快的验证。
        sb.append("\n  手动 l0#z()=${callZ(l0)}")
        sb.append("\n  z 之后 l0.l=${queueOf(l0)} l0.e=${queueOf(l0, "e")}")
        // ⚠️ 裸 Thread 没有 Looper ⇒ l0#h 走 checkIdleFile+postDelayed 兜底，这条路**未真机验证**。
        //    所以下发后多轮复查落盘，别让人对着前一刻的「没有」下结论。
        //    轮询而不是单次 sleep：15s 视频实测 1.3MB，网络好时几秒就完，慢时可能十几秒。
        for (wait in intArrayOf(3, 6, 12)) {
            runCatching { Thread.sleep(wait * 1000L) }
            val hit = findSight(cl, firstMediaId)
            sb.append("\n  落盘[+${wait}s]=${hit?.let { "${it.first}:${it.second.name} len=${it.second.length()}" } ?: "无"}")
            if (hit != null && hit.second.length() > 0) break
        }
        return sb.toString()
    }

    /**
     * 直调 `l0#f(vm3,int,m0,x7,String,String,String,int)Z` —— `d` 链上的**第一道真实判据**。
     *
     * 为什么要单独调：真机实测 `l0#d` 返回 `true` 但 `l0.l` 队列全程为 0 ⇒
     * `d` 的返回值**不能**当作「已入队」。`f` 的返回值才有意义（`f` 开头就判 `vm3` 空）。
     * 顺带把 8 个实参的形态打出来，对照反汇编看是哪条分支。
     */
    private fun callF(l0: Any, vm3: Any, subType: Int, m0: Any?, x7: Any?): String {
        val cands = runCatching {
            l0.javaClass.declaredMethods.filter { it.name == "f" }
        }.getOrNull().orEmpty()
        if (cands.isEmpty()) return "找不到 f（候选 ${cands.size}）"
        val m = cands.firstOrNull {
            it.parameterTypes.size == 8 && it.parameterTypes[0].simpleName == "vm3"
        } ?: cands.minByOrNull { it.parameterTypes.size }!!
        return runCatching {
            m.isAccessible = true
            val ret = if (m.parameterTypes.size == 8) {
                m.invoke(l0, vm3, subType, m0, x7, "", "", "", 10)
            } else {
                return "f 的签名为 (" +
                    m.parameterTypes.joinToString(",") { it.simpleName } + ")，不是预期的 8 参"
            }
            "ret=$ret sig=(${m.parameterTypes.joinToString(",") { it.simpleName }})"
        }.getOrElse {
            "抛 ${it.javaClass.simpleName}: ${((it.cause ?: it).javaClass.simpleName)}:" +
                "${((it.cause ?: it).message ?: "").take(80)}"
        }
    }

    /**
     *
     * 依据（`_r44_chain.md` §1.1）：`l0#h` 把请求放进 `l0.l`，末尾判 `size()>0` 才调 `l0#z`；
     * `z` 从 `l.removeLast()` 取。⇒ **下发后队列非空 = 入了队但 `z` 没跑**（或跑了一半失败）。
     * 这是区分「没入队」和「入队没出队」的唯一直接证据，比猜 Looper 靠谱。
     */
    private fun queueOf(l0: Any, field: String = "l"): String = runCatching {
        val q = XposedHelpers.getObjectField(l0, field)
        val n = XposedHelpers.callMethod(q, "size") as? Int ?: -1
        val head = if (n > 0) {
            runCatching {
                (XposedHelpers.callMethod(q, "peekLast") as Any?)?.javaClass?.simpleName
            }.getOrNull()
        } else null
        "size=$n head=${head ?: "-"}"
    }.getOrElse { "读取失败(${it.javaClass.simpleName})" }

    /** `l0#z()` = tryStartNetscene（`()V`）。真机 l0dump 已确认它是 public 无参。 */
    private fun callZ(l0: Any): String = runCatching {
        val m = l0.javaClass.declaredMethods.firstOrNull {
            it.name == "z" && it.parameterTypes.isEmpty()
        } ?: return@runCatching "找不到 z()"
        m.isAccessible = true
        m.invoke(l0)
        "ok"
    }.getOrElse { "抛 ${(it.cause ?: it).javaClass.simpleName}: ${((it.cause ?: it).message ?: "").take(60)}" }

    /** `f4#Na` = getUIHandler，返回 `f4.d`（`o3`）。取法同其余 getter，显式 invoke。 */    private fun getSnsHandler(cl: ClassLoader): Any? = runCatching {
        val f4 = XposedHelpers.findClass(CLS_F4, cl)
        val m = f4.declaredMethods.firstOrNull { it.name == "Na" && it.parameterTypes.isEmpty() }
            ?: return@runCatching null
        m.isAccessible = true
        m.invoke(null)
    }.onFailure {
        LogX.w("$TAG f4#Na failed ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}")
    }.getOrNull()

    // ------------------------------------------------------------------ 工具

    /**
     * 列举最近 N 条**带视频**的动态，供真机挑样本（`arg=list`）。
     *
     * 只看 `ContentObj.h` 里有没有 URL 非空、且 mediaId 不是本地伪媒体的媒体 ——
     * 与 [run] 里选目标的判据**同一份代码**（[pickVideo]），避免「列出来的挑不中」。
     */
    private fun listRecent(cl: ClassLoader, limitArg: String?): String {
        // 借 mode 参数复用成「列举条数」：`--es sub3 20` 这类写法不新增键
        val want = limitArg?.trim()?.toIntOrNull()?.coerceIn(1, 60) ?: 12
        val sb = StringBuilder("$TAG list want=$want")
        var ct = Long.MAX_VALUE
        var sid = Long.MAX_VALUE
        var scanned = 0
        var hit = 0
        while (hit < want && scanned < 3000) {
            val page = runCatching { SnsGuard.listCurrentSns(ct, sid, 50) }.getOrNull()
                ?: return sb.append("\n  ⇒ listCurrentSns 返回 null（SNS 库没挂上？）").toString()
            if (page.isEmpty()) break
            for (r in page) {
                scanned++
                ct = r.createTime
                sid = r.snsId
                val content = r.content ?: continue
                if (content.isEmpty()) continue
                val tlo = parseTlo(cl, content) ?: continue
                val contentObj = runCatching { XposedHelpers.getObjectField(tlo, "ContentObj") }
                    .getOrNull() ?: continue
                val medias = runCatching { XposedHelpers.getObjectField(contentObj, "h") as? List<*> }
                    .getOrNull() ?: continue
                val picked = pickVideo(medias) ?: continue
                hit++
                sb.append(
                    "\n  [${r.snsId}] t=${r.createTime} user=${r.userName} type=${r.type} " +
                        "medias=${medias.size} mediaId=${picked.mediaId} " +
                        "url=${picked.url.take(46)}"
                )
            }
            if (page.size < 50) break
        }
        if (hit == 0) {
            sb.append("\n  ⇒ 扫描 $scanned 条，没有带 URL 的视频媒体")
            sb.append("\n     ⚠️ 可能原因：① 视频动态还没同步进库；② 该动态媒体在 ContentObj 之外")
        } else {
            sb.append("\n  ⇒ 挑一条用 --es sub2 <snsId> --es sub3 probe 先看，再 real")
        }
        return sb.toString()
    }

    /** `content` → `TimeLineObject`；失败返回 null（parseFrom 可能抛，统一兜住）。 */
    private fun parseTlo(cl: ClassLoader, content: ByteArray): Any? = runCatching {
        val cls = XposedHelpers.findClass(CLS_TLO, cl)
        val obj = XposedHelpers.newInstance(cls)
        val m = cls.getMethod("parseFrom", ByteArray::class.java)
        m.isAccessible = true
        m.invoke(obj, content)
    }.onFailure {
        LogX.w("$TAG parseFrom failed ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}")
    }.getOrNull()

    /**
     * `l0` 运行时字段/方法结构（`arg=l0dump`）。
     *
     * ⛔ 为什么需要：`_r44_chain.md` 的「`l0.l` = 待下载 LinkedList」来自**反汇编读字段名**，
     * 而本轮已实测 `l0#f` 返回 true、`l0#d` 返回 true，`l0.l` 却全程 `size=0`。
     * 两种解释无法区分：(a) 真没入队；(b) `l0.l` 根本不是队列。
     * ⇒ 把 `l0` 的**所有字段的运行时类型**打出来，用类型认队列，不用名字猜。
     */
    private fun dumpL0(cl: ClassLoader): String {
        val sb = StringBuilder("$TAG l0dump")
        val l0 = getDownloadManager(cl) ?: return sb.append("\n  ⇒ 取不到 l0").toString()
        sb.append("\n  cls=${l0.javaClass.name}")
        val fields = runCatching { l0.javaClass.declaredFields }.getOrNull().orEmpty()
        sb.append("\n  字段数=${fields.size}")
        for (f in fields.sortedBy { it.name }) {
            val v: Any? = runCatching { f.isAccessible = true; f.get(l0) }.getOrNull()
            val t = f.type.simpleName
            // ⛔ 全走 **Java 层**判断 + 反射取值：Kotlin 会把 `java.util.Collection/Map`
            //    映射成 Kotlin 版（`size` 是属性 vs `size()` 是方法），写 `is Collection<*>`
            //    必然产生 "Function invocation 'size()' expected" 的歧义，编译不过。
            val isColl = v != null && java.util.Collection::class.java.isInstance(v)
            val isMap = v != null && java.util.Map::class.java.isInstance(v)
            val extra: String = when {
                v == null -> "null"
                isMap -> {
                    val n = runCatching {
                        java.util.Map::class.java.getMethod("size").invoke(v) as Int
                    }.getOrNull()
                    "map size=$n keys=" + runCatching {
                        (java.util.Map::class.java.getMethod("keySet").invoke(v) as Iterable<*>)
                            .take(3).joinToString(",") { it.toString().take(16) }
                    }.getOrElse { "-" }
                }
                isColl -> {
                    val n = runCatching {
                        java.util.Collection::class.java.getMethod("size").invoke(v) as Int
                    }.getOrNull()
                    val e0 = runCatching {
                        (java.util.Collection::class.java.getMethod("iterator").invoke(v)
                            as java.util.Iterator<*>).let { if (it.hasNext()) it.next() else null }
                            ?.javaClass?.simpleName
                    }.getOrNull()
                    "size=$n elem=${e0 ?: "-"}"
                }
                v is android.os.Handler -> "handler=${v.javaClass.simpleName}"
                else -> v.toString().take(40)
            }
            sb.append("\n   ${f.name}:$t = $extra")
        }
        // 方法清单：确认 d/e/f/g/h/i 的真实参数个数，反汇编的 8 参 f 是否唯一
        val ms = runCatching { l0.javaClass.declaredMethods }.getOrNull().orEmpty()
        for (name in listOf("d", "e", "f", "g", "h", "i", "l", "z")) {
            val hit = ms.filter { it.name == name }
            if (hit.isEmpty()) continue
            sb.append("\n   fn $name: " + hit.joinToString(" | ") {
                "(" + it.parameterTypes.joinToString(",") { p -> p.simpleName } + ")" +
                    ":" + it.returnType.simpleName
            })
        }
        return sb.toString()
    }

    private class Pick(val obj: Any, val mediaId: String?, val url: String)

    /**
     * 目录诊断（`arg=dir`，`mode` 可选传 mediaId 指定要算分桶的那条）。
     *
     * 要一次解掉两个疑问：
     * 1. `f4#F8` 返回的根**是否已经包含末尾的 `sns`** —— 我们此前直接再拼 `"sns"` 一层，
     *    打印出来是 `<hash>/sns/sns/d/a/...`，看着像重复，但**没有证据**（adb 无权读该目录）。
     * 2. 分桶目录实际长什么样、里面有没有 `sight_*` 文件（这决定「已落盘」判据能否成立）。
     *
     * 做法：把 F8 / F8+sns / F8+../.. 三个候选目录都列举一次，用**真实存在性**反证，
     * 不再靠猜。
     */
    private fun dumpDirs(cl: ClassLoader, mediaIdArg: String?): String {
        val sb = StringBuilder("$TAG dir")
        val f8 = f8Raw(cl) ?: return sb.append("\n  ⇒ F8 取不到根").toString()
        sb.append("\n  F8raw=$f8")
        val cands = snsRoots(cl)
        if (cands.isEmpty()) return sb.append("\n  ⇒ 无候选根").toString()
        for ((tag, d) in cands) {
            sb.append("\n  [$tag] ${d.path} exists=${d.isDirectory}")
            if (!d.isDirectory) continue
            val kids = runCatching { d.listFiles() }.getOrNull().orEmpty()
            sb.append(" 子项=${kids.size} 例=${kids.take(6).joinToString(",") { it.name }}")
        }

        // 指定 mediaId 时，算它的分桶目录并列出内容（这是「已落盘」判据的真实依据）
        val mid = mediaIdArg?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "dir" }
        if (mid != null && mid.all { it.isDigit() }) {
            val md5 = md5Of(mid)
            sb.append("\n  mediaId=$mid md5=$md5 分桶=${md5.substring(0, 1)}/${md5.substring(1, 2)}")
            for ((tag, base) in cands) {
                val dir = File(File(base, md5.substring(0, 1)), md5.substring(1, 2))
                sb.append("\n   [$tag] 分桶=${dir.path} exists=${dir.isDirectory}")
                if (!dir.isDirectory) continue
                val kids = runCatching { dir.listFiles() }.getOrNull().orEmpty()
                sb.append(" 文件=${kids.size} 例=" +
                    kids.take(8).joinToString(",") { "${it.name}(${it.length()})" })
            }
            sb.append("\n  findSight=" + (findSight(cl, mid)?.let { "${it.first}:${it.second.path}" }
                ?: "未命中"))
        }
        return sb.toString()
    }

    private fun md5Of(s: String): String = runCatching {
        java.security.MessageDigest.getInstance("MD5")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /**
     * 从媒体列表里挑第一个「有 URL 且非本地伪媒体」的 —— 与 `list` 和 `run` 共用。
     * ⛔ URL 判据是 `g`（不是 `d`；`d` 是 mediaId）。
     */
    private fun pickVideo(medias: List<*>): Pick? {
        for (m in medias) {
            if (m == null) continue
            val mid = str(m, "d")
            val url = str(m, "g")
            if (!url.isNullOrEmpty() && !isLocalPseudo(mid)) return Pick(m, mid, url)
        }
        return null
    }

    private fun findRow(snsId: Long): com.lx.wxgroup.data.GroupDb.SnsRow? {
        var ct = Long.MAX_VALUE
        var sid = Long.MAX_VALUE
        var scanned = 0
        while (scanned < 3000) {
            val page = runCatching { SnsGuard.listCurrentSns(ct, sid, 50) }.getOrNull() ?: return null
            if (page.isEmpty()) return null
            for (r in page) {
                scanned++
                ct = r.createTime
                sid = r.snsId
                if (r.snsId == snsId) return r
            }
            if (page.size < 50) return null
        }
        return null
    }

    private fun str(o: Any, field: String): String? =
        runCatching { XposedHelpers.getObjectField(o, field) as? String }.getOrNull()

    /**
     * 取 `l0` 单例：先 `f4#ea()`，失败退化为读 `f4.E` 静态字段。
     *
     * ⛔ 为什么要退化：`XposedHelpers.callStaticMethod` **内部不 setAccessible**，
     * 非 public 方法会抛 `IllegalAccessException` 并被内部 catch 成 `null` —— 症状是
     * 「返回 null 且没有异常日志」，极易被误读成「微信没初始化好」。
     * 而 `f4#ea` 内部就是「懒加载后写回 `E`」，所以直接读 `E` 拿到的是同一个对象。
     */
    private fun getDownloadManager(cl: ClassLoader): Any? {
        val f4 = runCatching { XposedHelpers.findClass(CLS_F4, cl) }.getOrNull() ?: return null
        // 1) 正向：反射调 ea()（public 时最省事，且能触发懒加载初始化）
        runCatching {
            val m = f4.declaredMethods.firstOrNull { it.name == "ea" && it.parameterTypes.isEmpty() }
                ?: return@runCatching null
            m.isAccessible = true
            m.invoke(null)
        }.onFailure {
            LogX.w("$TAG f4#ea invoke failed ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}")
        }.getOrNull()?.let { return it }
        // 2) 退化：直接读 f4.E 静态字段
        val e = runCatching { XposedHelpers.getStaticObjectField(f4, "E") }
            .onFailure {
                LogX.w("$TAG f4.E read failed ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}")
            }.getOrNull()
        if (e == null) LogX.w("$TAG 两种方式都取不到 l0（ea 非 public 且 E 为空）")
        return e
    }

    private fun intOf(o: Any, field: String): Int =
        runCatching { XposedHelpers.getIntField(o, field) }.getOrDefault(0)

    /** 微信用这两个前缀标记「本地伪媒体」（没有真实 CDN 文件），见 `l0#h` 的 startsWith 判据。 */
    private fun isLocalPseudo(mediaId: String?): Boolean {
        val s = mediaId ?: return true
        return s.startsWith("Locall_path") || s.startsWith("pre_temp_sns_pic")
    }

    /**
     * `f4#F8`（getAccSnsPath）原始值。末尾带 `/`，直接 `File()` 包没问题。
     * ⛔ 不用 `callStaticMethod`：它内部不 setAccessible，非 public 会静默成 null。
     */
    private fun f8Raw(cl: ClassLoader): String? = runCatching {
        val f4 = XposedHelpers.findClass(CLS_F4, cl)
        val m = f4.declaredMethods.firstOrNull { it.name == "F8" && it.parameterTypes.isEmpty() }
            ?: return@runCatching null
        m.isAccessible = true
        m.invoke(null) as? String
    }.onFailure {
        LogX.w("$TAG f4#F8 failed ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}")
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * SNS 媒体缓存的**全部候选根**（真机 2026-11-08 实测得出，别凭 F8 猜）。
     *
     * ⛔ 关键事实：`f4#F8` 返回 `.../MicroMsg/<hash>/sns/`，而真机上 **这个目录并不存在**；
     *    `sight_*` 实际落在 **`.../cache/<hash>/sns/<x>/<y>/`**。
     *    两者只差 `MicroMsg` ↔ `cache` 这一段，hash 相同 ⇒ 用 F8 换根推导，别硬编码 hash。
     *
     * ⚠️ 两个根都要扫：「已落盘」判据必须是**任一命中**，否则会在微信把文件写到
     *    另一个根时误判「没下」，导致重复下发。
     */
    private fun snsRoots(cl: ClassLoader): List<Pair<String, File>> {
        val f8 = f8Raw(cl) ?: return emptyList()
        val out = ArrayList<Pair<String, File>>(3)
        out += "F8" to File(f8)
        val cache = f8.replace("/MicroMsg/", "/cache/")
        if (cache != f8) out += "cache" to File(cache)
        return out
    }

    /**
     * 真机文件名形如：
     * `sight_15049717216742806078` + `a2319f43372974c153bc2256e103aaec`(32 hex) + `&enc1370298124_V2`
     *
     * ⇒ 判据：`sight_` 之后必须以 mediaId 开头，**且紧随其后是恰好 32 位 hex**（或到此结束）。
     *    ⛔ 不能只 `startsWith("sight_$mediaId")`：媒体 id 位数不一时，
     *    短的会成为长的前缀 ⇒ 错配到别人的视频。
     */
    private fun isSightOf(name: String, mediaId: String): Boolean {
        if (!name.startsWith("sight_")) return false
        val rest = name.substring(6)
        if (!rest.startsWith(mediaId)) return false
        val tail = rest.substring(mediaId.length)
        if (tail.isEmpty() || tail.startsWith("&")) return true // 无 hex 后缀的旧格式
        if (tail.length < 32) return false
        return tail.take(32).all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' }
    }

    /** 在某个根下按 `MD5(mediaId)` 分桶找这条媒体的 sight 文件；找不到返回 null。 */
    private fun sightFileIn(root: File, mediaId: String): File? {
        val md5 = md5Of(mediaId)
        if (md5.length < 2) return null
        val dir = File(File(root, md5.substring(0, 1)), md5.substring(1, 2))
        val kids = runCatching { dir.listFiles() }.getOrNull() ?: return null
        return kids.firstOrNull { it.isFile && isSightOf(it.name, mediaId) }
    }

    /**
     * 全候选根扫一遍，返回（根标签, 文件）。**已落盘判据与下发后复核共用这一个函数**，
     * 保证「事前判存在」和「事后验落盘」口径一致。
     */
    private fun findSight(cl: ClassLoader, mediaId: String?): Pair<String, File>? {
        if (mediaId.isNullOrEmpty()) return null
        for ((tag, root) in snsRoots(cl)) {
            sightFileIn(root, mediaId)?.let { return tag to it }
        }
        return null
    }
}
