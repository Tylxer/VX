package com.lx.wxgroup.core

import android.os.Handler
import android.os.Looper
import com.lx.wxgroup.util.LogX
import java.lang.reflect.Method

/**
 * 朋友圈**后台**同步触发器（R25）。
 *
 * ## 为什么要有它
 *
 * `MomentsAuto` 原来的「定时刷新」是靠 `startActivity` 把朋友圈 Activity 拉起来实现的，
 * 有两个躲不掉的副作用：
 *
 * 1. ⛔ **必须先手动进一次朋友圈** —— `timelineCls` 只能靠 lifecycle 回调捕获，
 *    用户没进过就是 null，`doRefresh` 直接 return；
 * 2. ⛔ **一到点就把朋友圈顶到前台**，抢走用户正在用的界面。
 *
 * 需求「后台定时执行、不需要我进入朋友圈」等价于：**绕开 UI，直接投递微信自己的同步
 * NetScene**。本文件只干这一件事，不碰 UI、不碰 tick 调度。
 *
 * ## 取证链（classes3.dex，全部静态取证，没有一个常量是猜的）
 *
 * ```
 * Lcom/tencent/mm/plugin/sns/model/x2;     super = com.tencent.mm.modelbase.k1
 *   <init>(I)V          ← 唯一构造，单 int 参数（语义 = scene）
 *   doScene(q,s0)I      ← 请求体 pb5.e = 全局单例 hl0/x1.a.a()
 *   getType() = 214
 *   CGI = "/cgi-bin/micromsg-bin/mmsnssync"（<init> 里 const-string 写死）
 *   interfaces: com.tencent.mm.network.j0
 * ```
 *
 * 唯一生产调用点 = 微信自己的事件监听器（不是 UI）：
 *
 * ```
 * l93/q4.callback:(Lcom/tencent/mm/sdk/event/IEvent;)Z   super = com.tencent.mm.sdk.event.n
 *   002e: iget-object v9, v9, SnsSyncEvent.g:Lgj/ru;
 *   0030: iget         v9, v9, Lgj/ru;.a:I
 *   0032: const/4  v2, #int 2
 *   0034: const/16 v7, #int 256                ★ scene = 256
 *   0074: new-instance v2, x2
 *   0076: invoke-direct {v2, v7}, x2.<init>:(I)V
 *   0079: invoke-virtual {v9, v2}, modelbase/p1.f:(modelbase/k1;)Z
 * ```
 *
 * ⚠️ **为什么认定 scene=256 而不是别的**：这个方法里 `v7` 只有一处赋值（`const/16 v7,#256`），
 * 而两条分支（`event.g.a == 2` 与否）走到最后 `new x2(v7)` 时 `v7` 始终是 256。
 * 全 APK 也只有这一个 dex 有 `x2.<init>` 调用（其余 dex 逐个 dexdump 确认无命中）
 * ⇒ 这就是微信标准的「朋友圈同步」入口，不是某条冷门旁路。
 *
 * 投递链路（同一份字节码）：
 * ```
 * Ly70/k1;.i:()Ly70/k1;      // 静态方法
 * Ly70/k1;.n:()Ly70/w;       // 静态方法 ⚠️ 注意 ins={} 为空，不是实例方法
 * Ly70/w;.b  →  com.tencent.mm.modelbase.p1
 * p1.f(k1)Z                  // 入队，返回 boolean
 * ```
 *
 * ⚠️ 两个易错点：
 * - `n()` 是 **static**（`invoke-static {}` 的寄存器列表为空），按实例方法调会
 *   `NoSuchMethodError`。这一点从 insns 的 `{}` 直接可证。
 * - `f()` 不是 `g()`。`FileSender` 里那条 `modelbase/p1.g` 是**别的重载**（用在发送消息场景），
 *   别照抄到这里。
 *
 * ## 失败处理原则
 *
 * 反射链上任何一环失败（类找不到 / 方法签名变了 / 账号未就绪）都必须**留日志并退 EMBRYO，
 * 不能重试到刷屏**。微信升级换类名是常态，`disabled` 是为了让自动轮安静下来，
 * 但**不阻断探针**（`force=true` 仍可查最新失败原因），否则真机上失去诊断能力。
 */
object MomentsSyncTrigger {

    private const val TAG = "MSYNC"

    /** NetSceneSnsSync：`<init>(I)V`，`getType()=214`，CGI `/cgi-bin/micromsg-bin/mmsnssync` */
    private const val CLS_SYNC = "com.tencent.mm.plugin.sns.model.x2"

    /** 拿网络派发器要用的宿主类（微信自己的 `Ly70/k1;`） */
    private const val CLS_CORE = "y70.k1"

    /**
     * 同步场景值。取证见类注释：微信 `SnsSyncListener` 两条分支都传 256。
     *
     * ⚠️ 这是**唯一**取证到的值。不要为了「顺手试试」去枚举其它 scene ——
     * 未知的 scene 可能让服务端返回我们不认识的响应体。
     */
    private const val SCENE_SYNC = 256

    /** 从后台线程切到主线程执行投递时的最长等待秒数（超时只影响这一轮，不影响后续） */
    private const val AWAIT_MAIN_SEC = 5L

    private val lock = Any()

    @Volatile
    private var classLoader: ClassLoader? = null

    @Volatile
    private var syncCls: Class<*>? = null

    @Volatile
    private var syncCtor: java.lang.reflect.Constructor<*>? = null

    @Volatile
    private var coreCls: Class<*>? = null

    @Volatile
    private var nMethod: Method? = null

    @Volatile
    private var fMethod: Method? = null

    @Volatile
    private var bField: java.lang.reflect.Field? = null

    /** 上次失败原因；null = 没失败过 */
    @Volatile
    private var disabledReason: String? = null

    private val ok = java.util.concurrent.atomic.AtomicLong(0)
    private val failed = java.util.concurrent.atomic.AtomicLong(0)

    /** 上次成功投递的时刻（毫秒） */
    @Volatile
    private var lastOkAt = 0L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // ---------------------------------------------------------------- 安装 / 状态

    /**
     * 在 bootstrap（宿主 attach 之后、微信开始干活之前）备好句柄。
     *
     * @return 是否全部备齐。备不齐时自动调用会被 [disabledReason] 挡住，
     *   但探针仍能 force 查看原因。
     */
    fun install(cl: ClassLoader): Boolean {
        classLoader = cl
        return runCatching { resolve(cl) }
            .onFailure { disabledReason = "resolve: ${it.javaClass.simpleName}" }
            .getOrDefault(false)
    }

    private fun resolve(cl: ClassLoader): Boolean {
        val s = Class.forName(CLS_SYNC, false, cl)
        val ctor = s.getDeclaredConstructor(Int::class.javaPrimitiveType)
        ctor.isAccessible = true

        val k = Class.forName(CLS_CORE, false, cl)
        // ⚠️ n() 是 static 且无参（字节码 `invoke-static {}, Ly70/k1;.n:()Ly70/w;`）
        val n = k.declaredMethods.firstOrNull {
            it.name == "n" && it.parameterTypes.isEmpty()
        } ?: throw NoSuchMethodException("$CLS_CORE.n()")
        n.isAccessible = true

        val wCls = n.returnType
        val b = wCls.declaredFields.firstOrNull { it.name == "b" }
            ?: throw NoSuchFieldException("${wCls.name}.b")
        b.isAccessible = true

        val p1Cls = b.type
        // f(NetScene)Z —— 注意 parameters 里写的是 NetScene 的**父类** k1
        val f = p1Cls.declaredMethods.firstOrNull {
            it.name == "f" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0].isAssignableFrom(s) &&
                it.returnType == Boolean::class.javaPrimitiveType
        } ?: p1Cls.methods.firstOrNull {
            it.name == "f" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0].isAssignableFrom(s)
        } ?: throw NoSuchMethodException("${p1Cls.name}.f(${s.name})Z")
        f.isAccessible = true

        synchronized(lock) {
            syncCls = s
            syncCtor = ctor
            coreCls = k
            nMethod = n
            bField = b
            fMethod = f
            disabledReason = null
        }
        return true
    }

    /** 能否自动同步（句柄已就绪且没被判死） */
    fun ready(): Boolean = disabledReason == null && syncCtor != null && fMethod != null

    fun stat(): String =
        "$TAG ready=${ready()} ok=${ok.get()} failed=${failed.get()} " +
            "rcv=${responses.get()} watching=${watchUnhook != null} " +
            "lastOkAt=${lastOkAt.takeIf { it > 0 } ?: "-"} " +
            "disabled=${disabledReason ?: "-"}"

    // ---------------------------------------------------------------- 触发

    /**
     * 投递一次朋友圈同步。返回一个单行结果串，供日志与探针直接打印。
     *
     * ⚠️ 必须在主线程：网络层的入队最终要走到 host looper，且
     *    [HostContext] 里的 Activity 状态也是在主线程维护的。
     *
     * @param force true 时绕过 [disabledReason] 闸门（探针专用），
     *   **但失败仍会写回 [disabledReason]** —— 自动轮照样停，防止每 60 秒刷一行。
     */
    fun syncNow(force: Boolean = false): String {
        // 投递 NetScene 要与网络层自己的线程模型一致 —— 微信是在主线程的
        // event callback 里投的。本函数对调用方屏蔽这件事：
        // 主线程直连；非主线程（如 `op=moments` 的 io 探针）切过去同步等结果。
        // ⚠️ 只在**后台线程**里才会 await，主线程走的是直连分支，不存在自锁。
        if (!isMainThread()) {
            val out = java.util.concurrent.atomic.AtomicReference<String>(null)
            val gate = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                runCatching { out.set(unsafeSync(force)) }
                    .onFailure { out.set("$TAG FAIL dispatch-to-main: ${it.javaClass.simpleName}") }
                gate.countDown()
            }
            runCatching { gate.await(AWAIT_MAIN_SEC, java.util.concurrent.TimeUnit.SECONDS) }
            return out.get() ?: "$TAG FAIL main-thread timeout (${AWAIT_MAIN_SEC}s)"
        }
        return unsafeSync(force)
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** 结果的 shape：`OK` 或 `FAIL <原因>`，供调用方做统计而不必解析字符串 */
    fun syncOk(result: String): Boolean = result.startsWith("$TAG OK")

    private fun unsafeSync(force: Boolean): String {
        if (!force && !ready()) {
            return "$TAG skip disabled=${disabledReason ?: "not installed"}"
        }
        return try {
            doSync()
        } catch (t: Throwable) {
            failed.incrementAndGet()
            disabledReason = t.describe()
            LogX.w("$TAG sync failed: $disabledReason")
            "$TAG FAIL ${t.describe()}"
        }
    }
    private fun doSync(): String {
        val cl = classLoader ?: run {
            disabledReason = "no classLoader"
            return "$TAG FAIL no classLoader"
        }
        if (syncCtor == null || fMethod == null) {
            runCatching { resolve(cl) }
                .onFailure {
                    disabledReason = "resolve: ${it.javaClass.simpleName}"
                    return "$TAG FAIL ${it.describe()}"
                }
        }
        val ctor = syncCtor!!
        val scene = ctor.newInstance(SCENE_SYNC)

        // 账号就绪检查：微信自己的 SnsSyncListener 也是这么做的
        // （`Ly70/k1;.a:()Z`，为 false 时它直接 return "account was ready" 之前就返回）
        val core = coreCls
        if (core != null) {
            val armed = runCatching {
                core.declaredMethods.firstOrNull { it.name == "a" && it.parameterTypes.isEmpty() }
                    ?.apply { isAccessible = true }?.invoke(null) as? Boolean
            }.getOrNull()
            if (armed == false) {
                // 不是失败，是「还没到时候」。不写 disabledReason，下一轮还能试。
                LogX.i("$TAG account not ready yet, skip this round")
                return "$TAG SKIP account-not-ready"
            }
        }

        val n = nMethod!!
        val w = n.invoke(null)
        val p1 = bField!!.get(w) ?: run {
            disabledReason = "dispatcher null (y70/w.b)"
            return "$TAG FAIL dispatcher null"
        }
        val queued = fMethod!!.invoke(p1, scene) as? Boolean ?: false
        if (!queued) {
            failed.incrementAndGet()
            // 入队被拒通常意味着已经在飞 / 未登录。这类要**允许重试**，
            // 所以不写 disabledReason —— 写死了一辈子恢复不了。
            LogX.w("$TAG queue rejected (already flying or offline)")
            return "$TAG REJECT queue returned false"
        }
        ok.incrementAndGet()
        lastOkAt = System.currentTimeMillis()
        disabledReason = null
        val msg = "$TAG OK scene=$SCENE_SYNC type=214 cgi=mmsnssync"
        LogX.i(msg)
        return msg
    }

    /**
     * 装一个**临时**钩子监听 `x2.onGYNetEnd`，用来证明「请求真发出去了、服务端真回了」。
     *
     * ## 为什么必须有它
     *
     * `p1.f(k1)Z` 返回 true 只表示**入队成功**，不代表网络上真的走了一遭 ——
     * 队列满 / 离线 / 被去重都会让它返回 false，而返回 true 之后也可能被
     * 中途丢弃。缺少这一层验证时，「 delivery OK」很容易被误当成「数据已同步」，
     * 于是真机上出现"日志一片 OK 但导出始终没新东西"的假象，且无从定位。
     *
     * `onGYNetEnd(IIILjava/lang/String;Lnetwork/t0;[B)V` 是 NetScene 的统一回包，
     * 前三个 int 是 errType / errCode，第 4 个是错误串。它被调用说明：
     * 请求真的到了服务端并回来了。
     *
     * @param windowMs 监听窗口，到点自动摘钩
     */
    fun watch(windowMs: Long = 60_000L): String {
        return runCatching {
            if (!ready()) resolve(classLoader!!)
            val s = syncCls!!
            val m = s.declaredMethods.firstOrNull {
                it.name == "onGYNetEnd" && it.parameterTypes.size == 6 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            } ?: return "$TAG watch: onGYNetEnd not found"

            // 幂等：重复 watch 只续窗口，不重复挂钩（重复挂同一条回调会让它打 N 遍）
            unwatchRun?.let { mainHandler.removeCallbacks(it) }
            if (watchUnhook == null) {
                watchUnhook = de.robv.android.xposed.XposedBridge.hookMethod(
                    m, responseHook
                )
            }
            unwatchRun = Runnable { stopWatch() }
            mainHandler.postDelayed(unwatchRun!!, windowMs)
            "$TAG watch on window=${windowMs}ms method=${m.name}/6"
        }.onFailure { LogX.w("$TAG watch failed: ${it.javaClass.simpleName}") }
            .getOrDefault("$TAG watch failed")
    }

    private fun stopWatch() {
        watchUnhook?.let { runCatching { it.unhook() } }
        watchUnhook = null
        unwatchRun = null
        LogX.i("$TAG watch off observed=${responses.get()}")
    }

    /** 探针用的同步摘钩入口 */
    fun stopWatchText(): String {
        stopWatch()
        return "$TAG unwatched observed=${responses.get()}"
    }

    private val responses = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile
    private var watchUnhook: de.robv.android.xposed.XC_MethodHook.Unhook? = null

    @Volatile
    private var unwatchRun: Runnable? = null

    private val responseHook = object : de.robv.android.xposed.XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                val a = param.args
                // (IIILjava/lang/String;Lnetwork/t0;[B)V
                val t = a?.getOrNull(0) as? Int ?: -1
                val c = a?.getOrNull(1) as? Int ?: -1
                val msg = a?.getOrNull(3) as? String ?: ""
                responses.incrementAndGet()
                LogX.i("$TAG onGYNetEnd errType=$t errCode=$c errMsg='${msg.take(60)}'")
            }
        }
    }

    /** 后台线程安全调用入口：把投递切到主线程 */
    fun post(block: (String) -> Unit) {
        mainHandler.post { runCatching { block(syncNow()) } }
    }

    private fun Throwable.describe(): String =
        "${javaClass.simpleName}: ${message?.take(80) ?: "-"}"
}
