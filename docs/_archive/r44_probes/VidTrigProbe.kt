package com.lx.wxgroup.probe

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.util.LogX
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * R44 · 朋友圈视频全后台获取 —— 最小验证探针（`op=moments sub=vidtrig sub2=on|off|stat`）。
 *
 * 目的：确认普通朋友圈视频到底走哪条触发链，并顺手钉死 aeskey 的字段号。
 * ⛔ 只挂 hook + 打日志，**不改任何参数、不 return 替代值、不阻断原方法**。
 *
 * ## 两个 hook 点（均为 before，只读）
 *
 * ### F · `Lid3/f0#f(SnsInfo, vm3, x7)V`
 * 类 `id3.f0` = `com.tencent.mm.plugin.sns.ad.timeline.helper.AdMediaDownloadHelper`，
 * 方法 `f` = `triggerDownloadMediaSight`。反汇编确认它内部完成：
 * 「取 mediaId → 算路径 → postDelayed 投递下载任务」。
 * before 打：snsId、mediaId（`vm3.d`）、调用栈前 3 帧。
 * ⭐ 栈是**本轮核心判据**：区分「广告调用」还是「普通朋友圈视频调用」。
 *
 * ### R · `Lok0/x0#r(...)I`
 * CDN 传输引擎 `startURLDownload`。before 打 url / aeskey / fileKey / savePath。
 * ⭐ 这条直接钉死 aeskey：拿打出来的 aes 与 content 里
 *   `8.5.19` / `8.5.26` / `8.5.33` 三个 32-hex 候选对比，就知道是哪个字段。
 *
 * ## 精确签名（逐字，从反汇编取）
 * - `id3.f0.f(com.tencent.mm.plugin.sns.storage.SnsInfo, q54.vm3, com.tencent.mm.storage.x7) : void`
 * - `ok0.x0.r(String, String, String, int, String, String, int, boolean) : int`
 *
 * `r` 的 8 个形参语义（由调用方 `ok0/j#invoke` 的 iget 顺序钉死，见
 * `_cache/_r14_ok0j_invoke.txt`）：
 * ```
 *   [0] mediaId   (field_mediaId)    [4] aesKey  (field_aesKey)
 *   [1] url       (field u)          [5] authKey (field_authKey) = fileKey
 *   [2] savePath  (field_fullpath)   [6] totalLen (field_totalLen, int)
 *   [3] fileType  (field_fileType)   [7] 0 (boolean)
 * ```
 *
 * `SnsInfo` 的 snsId 字段在父类 `Ljj/ca` 上（反汇编 `Ljj/ca;.field_snsId:J`）。
 * `vm3` 的 mediaId 字段（反汇编 `Lq54/vm3;.d:Ljava/lang/String;`）。
 */
object VidTrigProbe {

    private const val CLS_F0 = "id3.f0"
    private const val CLS_X0 = "ok0.x0"

    /**
     * R44-2 · `DownloadManager`（`com.tencent.mm.plugin.sns.model.l0`）。
     *
     * 为什么加这一组：`id3/f0#f` 与 `ok0/x0#r` 在真实播放时**零命中**（R44 真机），
     * 说明普通视频不走那条链。反汇编找到 `l0`：
     * - `d/e/f/h` = `addDownLoadSns` / `addDownLoadSnsImpl`（普通媒体下载队列）
     * - `i`       = **`addDownloadStreamVideoTask`**（视频流任务，`ok0/m2#e` 提交）
     * - `g`       = `addDownLoadSnsAd`（广告专用）
     *
     * ⭐ 本轮要判的唯一岔路：**普通好友视频走 `d`（经 f/h）还是走 `i`**。
     * 判据 = 各方法的命中计数 + 调用栈里有没有 `ad` 字样。
     */
    private const val CLS_L0 = "com.tencent.mm.plugin.sns.model.l0"

    private const val CLS_SNSINFO = "com.tencent.mm.plugin.sns.storage.SnsInfo"
    private const val CLS_VM3 = "q54.vm3"
    private const val CLS_X7 = "com.tencent.mm.storage.x7"

    /** SnsInfo.snsId 字段（在父类 `Ljj/ca` 上，XposedHelpers.findField 会沿父类链找） */
    private const val FIELD_SNSID = "field_snsId"

    /** vm3.mediaId 字段 */
    private const val FIELD_MEDIAID = "d"

    @Volatile
    private var loader: ClassLoader? = null

    private val unhooks = Collections.synchronizedList(mutableListOf<XC_MethodHook.Unhook>())

    private val countF = AtomicInteger()
    private val countR = AtomicInteger()

    // ---- l0（DownloadManager）五个入口的独立计数，互不污染 ----
    private val countLd = AtomicInteger()
    private val countLe = AtomicInteger()
    private val countLf = AtomicInteger()
    private val countLh = AtomicInteger()
    private val countLi = AtomicInteger()
    private val countLg = AtomicInteger()

    fun prepare(lpparam: XC_LoadPackage.LoadPackageParam) {
        loader = lpparam.classLoader
    }

    fun command(sub2: String) {
        when (sub2.trim().lowercase()) {
            "on" -> install()
            "off" -> uninstall()
            // 真机是 on→play→off→on 的多轮流；计数不清零会把上一轮的命中混进本轮
            "reset" -> {
                countF.set(0); countR.set(0)
                countLd.set(0); countLe.set(0); countLf.set(0)
                countLg.set(0); countLh.set(0); countLi.set(0)
                LogX.i("VIDTRIG counters reset")
            }
            "stat" -> LogX.i(
                "VIDTRIG stat F=${countF.get()} R=${countR.get()} " +
                    "Ld=${countLd.get()} Le=${countLe.get()} Lf=${countLf.get()} " +
                    "Lg=${countLg.get()} Lh=${countLh.get()} Li=${countLi.get()} " +
                    "hooks=${unhooks.size}"
            )
            else -> LogX.i("VIDTRIG unknown sub2=$sub2（on|off|stat|reset）")
        }
    }

    @Synchronized
    private fun install() {
        val cl = loader
        if (cl == null) {
            LogX.w("VIDTRIG no classLoader（模块未 prepare）")
            return
        }
        uninstall()
        var f = false
        var r = false

        runCatching {
            val snsInfoCls = XposedHelpers.findClass(CLS_SNSINFO, cl)
            val vm3Cls = XposedHelpers.findClass(CLS_VM3, cl)
            val x7Cls = XposedHelpers.findClass(CLS_X7, cl)
            val u = XposedHelpers.findAndHookMethod(
                CLS_F0, cl, "f", snsInfoCls, vm3Cls, x7Cls, f0Hook,
            )
            synchronized(unhooks) { unhooks.add(u) }
            f = true
        }.onFailure {
            LogX.w(
                "VIDTRIG hook id3.f0#f failed: ${it.javaClass.simpleName}: " +
                    "${(it.message ?: "").take(80)}"
            )
        }

        runCatching {
            val u = XposedHelpers.findAndHookMethod(
                CLS_X0, cl, "r",
                String::class.java, String::class.java, String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java, String::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                rHook,
            )
            synchronized(unhooks) { unhooks.add(u) }
            r = true
        }.onFailure {
            LogX.w(
                "VIDTRIG hook ok0.x0#r failed: ${it.javaClass.simpleName}: " +
                    "${(it.message ?: "").take(80)}"
            )
        }

        var l0 = 0
        l0 += hookL0(cl, "d", countLd, "Ld")
        l0 += hookL0(cl, "e", countLe, "Le")
        l0 += hookL0(cl, "f", countLf, "Lf")
        l0 += hookL0(cl, "g", countLg, "Lg")
        l0 += hookL0(cl, "h", countLh, "Lh")
        l0 += hookL0(cl, "i", countLi, "Li")

        LogX.i("VIDTRIG on hooks=${unhooks.size} f=$f r=$r l0=$l0")
    }

    /**
     * 挂 `l0` 的一个方法。**用 `declaredMethods` 按名枚举 + 逐个 hook**，
     * 不用 `findAndHookMethod(name, 参数类型...)` —— 因为 `d/e/f/g/h` 的前两参是
     * `vm3`+`x7`，后面跟的 String/int 个数各不相同（`d`=4 参、`e`=5、`f`=8、`h`=9），
     * 写死签名极易错，且错了只在真机日志里表现为「hook failed」，白跑一轮。
     * 同名重载可能多个，**全部挂上**（本探针只观测，挂满不漏）。
     *
     * @return 成功挂上的数量
     */
    private fun hookL0(cl: ClassLoader, name: String, counter: AtomicInteger, tag: String): Int {
        val cands = runCatching {
            XposedHelpers.findClass(CLS_L0, cl).declaredMethods.filter { it.name == name }
        }.onFailure {
            LogX.w("VIDTRIG l0#$name setup failed ${it.javaClass.simpleName}")
        }.getOrDefault(emptyList())
        if (cands.isEmpty()) {
            LogX.w("VIDTRIG hook l0#$name failed: no such method")
            return 0
        }
        var n = 0
        for (m in cands) {
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        counter.incrementAndGet()
                        val a = param.args
                        // d/e/f/g/h 的第 0 参是 vm3；i 的第 0 参是 String(mediaId)
                        val media = argMedia(a.getOrNull(0))
                        // ⚠️ 第 3 参只有 d/e/f/g/h 是 x7；i 的第 3 参是 int（无场景参）
                        //    ⇒ 只对非 int 才当场景打，否则会打出一串 "0"/"1" 废字段
                        val sc = sceneOf(a.getOrNull(3))
                        LogX.i(
                            "VIDTRIG $tag n=${a.size} media=$media scene=$sc " +
                                "caller=${caller()}"
                        )
                    }.onFailure {
                        LogX.w("VIDTRIG $tag err ${it.javaClass.simpleName}")
                    }
                }
            }
            val ok = runCatching {
                val u = XposedBridge.hookMethod(m, hook)
                synchronized(unhooks) { unhooks.add(u) }
                true
            }.onFailure {
                // ⚠️ parameterTypes 本身也可能抛（类型类未解析）⇒ 再包一层，别让日志炸出去
                val n = runCatching { m.parameterTypes.size }.getOrDefault(-1)
                LogX.w(
                    "VIDTRIG hook l0#$name($n) failed: " +
                        "${it.javaClass.simpleName}: ${(it.message ?: "").take(60)}"
                )
            }.getOrDefault(false)
            if (ok) n++
        }
        return n
    }

    /** `l0#i` 的第 0 参本身就是 mediaId 字符串；其余方法第 0 参是 `vm3` 对象。 */
    private fun argMedia(o: Any?): String = when {
        o == null -> "-"
        o is String -> if (o.length <= 24) o else o.take(24)
        else -> mediaIdOf(o)
    }

    private fun sceneOf(o: Any?): String {
        if (o == null) return "-"
        // 第 3 参只有 d/e/f/g/h 是 x7 场景对象；i 的第 3 参是 int（没有场景参）
        // ⇒ 非 String 且非 x7 的（纯数字/布尔）直接标成 "-"，别打成 "0"/"1" 当场景误读
        if (o is Number || o is Boolean) return "-"
        // x7 的 toString() 就是场景串（timeline / snssight / …）
        return runCatching { o.toString().take(24) }.getOrNull() ?: "-"
    }

    private fun uninstall() {
        synchronized(unhooks) {
            for (u in unhooks) runCatching { u.unhook() }
            unhooks.clear()
        }
    }

    // ---------------------------------------------------------------- hook F

    private val f0Hook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                countF.incrementAndGet()
                val snsInfo = param.args.getOrNull(0)
                val vm3 = param.args.getOrNull(1)
                val id = snsIdOf(snsInfo)
                val media = mediaIdOf(vm3)
                LogX.i("VIDTRIG F id=$id media=$media caller=${caller()}")
            }.onFailure {
                LogX.w("VIDTRIG F err ${it.javaClass.simpleName}: ${it.message?.take(60)}")
            }
        }
    }

    // ---------------------------------------------------------------- hook R

    private val rHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                countR.incrementAndGet()
                val a = param.args
                val url = a.getOrNull(1) as? String
                val aes = a.getOrNull(4) as? String
                val fk = a.getOrNull(5) as? String
                val save = a.getOrNull(2) as? String
                LogX.i(
                    "VIDTRIG R url=${url?.take(80) ?: "-"} aes=${aes ?: "-"} " +
                        "fileKey=${fk ?: "-"} save=${save ?: "-"}"
                )
            }.onFailure {
                LogX.w("VIDTRIG R err ${it.javaClass.simpleName}: ${it.message?.take(60)}")
            }
        }
    }

    // ---------------------------------------------------------------- 字段读取

    private fun snsIdOf(snsInfo: Any?): String {
        if (snsInfo == null) return "-"
        val v = runCatching { XposedHelpers.getObjectField(snsInfo, FIELD_SNSID) }.getOrNull()
        return v?.toString() ?: "-"
    }

    private fun mediaIdOf(vm3: Any?): String {
        if (vm3 == null) return "-"
        val v = runCatching { XposedHelpers.getObjectField(vm3, FIELD_MEDIAID) }.getOrNull()
        return (v as? String) ?: (v?.toString() ?: "-")
    }

    // ---------------------------------------------------------------- 调用栈

    private fun caller(): String {
        val frames = ArrayList<String>(3)
        for (st in Throwable().stackTrace) {
            if (frames.size >= 3) break
            val cn = st.className
            if (cn.startsWith("com.lx.wxgroup") ||
                cn.startsWith("de.robv.android.xposed") ||
                cn.startsWith("java.lang.reflect") ||
                cn.startsWith("dalvik.system") ||
                cn.startsWith("android.os.Handler") ||
                cn == "id3.f0" ||
                cn.contains("AdMediaDownloadHelper")
            ) {
                continue
            }
            frames.add(cn.substringAfterLast('.') + "." + st.methodName)
        }
        return if (frames.isEmpty()) "-" else frames.joinToString(" -> ")
    }
}
