package com.lx.wxgroup.feature

import android.os.SystemClock
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * R37 U1 取证探针：旁听 `NetSceneSnsSync`（`x2`，scene 256，`mmsnssync`）的**删除动作**。
 *
 * ## 只为一件事存在：裁决 U1
 * 灰标对「好友删他自己的动态」永远打不上标（P4 已证：归档只挂在 DELETE 通道，
 * 而好友删动态**不走 DELETE**）。设计侧唯一的候选主判据是
 * 「`x2#U`(`updateSnsDel`) / `x2#O`(`processSnsDelAction`) 被调用」——
 * 那是**服务端下发的正向删除事件**（`docs/_r37_graytag_datasource.md` §2 H3b）。
 * 但它有个未证伪的前提 **F1/U1**：静态取证只证明「整条动态被删时走它」，
 * **没证明范围覆盖「好友」删他自己的动态**（也可能只对自删触发）。
 *
 * ⇒ 本类就是为跑这一次受控实验而生：**只打日志，不做任何判断。**
 *
 * ## 为什么 6 个方法一起挂（不要自己缩减）
 * 真机 dump 的 `x2` 方法表里 `M/N/O/P/Q/U` **同签名同族**（`classes3.dex`）：
 * ```
 * Lcom/tencent/mm/plugin/sns/model/x2;
 *   M(Lq54/f95;Lq54/e95;JJLjava/lang/String;Lcom/tencent/mm/sdk/platformtools/o3;)Z
 *   N / O / P / Q / U    ← 同上签名
 * ```
 * 已定案的两条：`N` = `setCommentDeleted`（`commentflag=1`，某人删自己在该动态下的评论）、
 * `U` = `updateSnsDel`（`commentflag=2`，**整条动态被删**）——
 * 出处 `docs/conclusions/comment.md:52-57`、`docs/_r37_graytag_datasource.md` §2 H3b。
 * 其余四个语义未定，但同族同签名 ⇒ **一次全挂**，删除动作发生时哪个被调用一目了然。
 * ⛔ 只挂一两个 = 「挂错一个 → 再装一次包 → 再冷启」的往返（每次约 10 分钟），
 *    本轮只有一次装包机会，宁可多打几条日志。
 *
 * ## ⛔ 范围纪律（本轮刻意不做的事）
 * - **不写库、不加表、不做判据、不打标签**；
 * - 不碰 `SnsDeletedTag` / `SnsDeletedTagLogic` / `GroupDb` / `SnsGuard` 的任何逻辑；
 * - 不解析参数语义（`a`/`b` 只原样打印，不猜哪个是 snsId —— 猜错会污染判读）。
 *
 * ## 为什么挂在 after 而不是 before
 * 我们只是**旁听**，不需要也不该影响返回值。挂 after 且从不写 `param.result`，
 * 对微信是零副作用。
 */
object SnsSyncDelProbe {

    /** 与 `SnsTimelineSourceFix.kt:389` 的 `SYNC_SCENE` 同值；那边是 private，这里自己定义一份 */
    private const val SYNC_SCENE = "com.tencent.mm.plugin.sns.model.x2"

    /**
     * 同族 6 个方法名（真机 dump 已核，见类注释）。
     *
     * ⚠️ 这是**混淆名**，会随微信版本漂移。漂移时的失效形态被**故意**设计成可见：
     * 一个都找不到 ⇒ 打 `SNSSYNCDEL no method (cls missing or sign drift)`，
     * 且 `installed hooks=0` ⇒ 让人一眼看出是「类名漂了」而不是「这次没有删除动作」。
     */
    private val METHODS = arrayOf("M", "N", "O", "P", "Q", "U")

    /** 目标签名是 6 参；少一个多一个都说明签名漂了，宁可不挂也不挂错 */
    private const val ARITY = 6

    /**
     * 同一方法名的日志节流窗口。
     *
     * ⚠️ 必须限频：`LogX` 是 **5000 行环形缓冲**
     * （`SnsTimelineSrcLogic.kt:65-69`），而同步可能在 worker 上连打几十次 ——
     * 不限频会把真正的删除事件**自己挤掉**，那这次取证就白跑了。
     */
    private const val THROTTLE_MS = 200L

    private const val CFG_TTL_MS = 3000L

    /** 每个方法名最近一次打日志的时刻（`elapsedRealtime`） */
    private val lastLogAt = ConcurrentHashMap<String, Long>()

    /** 已打过 `first` 那行的方法名（每个方法名只打一次） */
    private val firstDone = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var on = true
    @Volatile private var cfgAt = 0L

    @Volatile private var installed = false

    private fun enabled(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - cfgAt > CFG_TTL_MS) {
            // ⛔ 读配置必须兜底：Kv 依赖 GroupDb，库没开时不能抛异常把微信搞崩。
            //    读失败按「开」处理（本轮就是要采），但失败也不能刷屏 ⇒ 不打日志。
            on = runCatching { Kv.getBool(Keys.DEBUG_SNS_SYNCDEL_PROBE) }.getOrDefault(true)
            cfgAt = now
        }
        return on
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        val cls = runCatching {
            XposedHelpers.findClass(SYNC_SCENE, lpparam.classLoader)
        }.getOrNull()
        if (cls == null) {
            LogX.w("SNSSYNCDEL no method (cls missing or sign drift)")
            return
        }
        val targets = runCatching {
            cls.declaredMethods.filter {
                it.name in METHODS && it.parameterTypes.size == ARITY
            }
        }.getOrNull()
        if (targets.isNullOrEmpty()) {
            LogX.w("SNSSYNCDEL no method (cls missing or sign drift)")
            return
        }
        val names = ArrayList<String>(targets.size)
        var hooked = 0
        for (m in targets) {
            val ok = runCatching { XposedBridge.hookMethod(m, hook) }.getOrNull()
            if (ok != null) {
                hooked++
                names += m.name
            } else {
                LogX.w("SNSSYNCDEL hook ${m.name} failed")
            }
        }
        if (hooked == 0) {
            LogX.w("SNSSYNCDEL no method (cls missing or sign drift)")
            return
        }
        installed = true
        LogX.i("SNSSYNCDEL installed hooks=$hooked names=[${names.joinToString(",")}]")
    }

    private val hook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // ⚠️ 这一段对每次调用都会跑 —— 关着的时候必须只有这一行开销。
            //    ⛔ 整段包 runCatching：钩子里逃逸的异常会走 UncaughtExceptionHandler 把微信杀掉。
            runCatching { onCalled(param) }
                .onFailure { LogX.w("SNSSYNCDEL hook err: ${it.javaClass.simpleName}") }
        }
    }

    private fun onCalled(param: XC_MethodHook.MethodHookParam) {
        if (!enabled()) return
        val name = param.method?.name ?: return
        val args = param.args ?: return

        // 首次命中：额外打一行签名，供复核「挂的是不是我们以为的那个方法」。
        // ⛔ 这也是签名漂移的唯一自检手段 —— 真机 grep "SNSSYNCDEL first" 就能看到实参类型。
        //    ⚠️ `param.method` 在 Xposed API 里是 `Member`，取参数表必须转 `Method`。
        if (firstDone.add(name)) {
            val sig = runCatching {
                (param.method as? Method)?.parameterTypes
                    ?.joinToString(",") { it.simpleName ?: it.name } ?: "?"
            }.getOrDefault("?")
            LogX.i("SNSSYNCDEL first m=$name sig=$sig")
        }

        // ⛔ 必须 `as? Long ?: -1`：args 里混有对象与 String，
        //    直接 as Long 会在签名漂移/装箱变化时抛 ClassCastException 并把微信搞崩。
        val a = if (args.size > 2) (args[2] as? Long ?: -1L) else -1L
        val b = if (args.size > 3) (args[3] as? Long ?: -1L) else -1L

        // ⚠️ 节流只在**真的要打**时才更新时刻。写成 `put` 无条件更新的话，
        //    一次连打 50 次时只有第 1 次能过闸（后续每次都是跟「上一次调用」比），
        //    反而把整轮事件全吞掉 —— 那就不是节流，是静默失效。
        val now = SystemClock.elapsedRealtime()
        val last = lastLogAt[name] ?: 0L
        if (last != 0L && now - last < THROTTLE_MS) return
        lastLogAt[name] = now

        LogX.i("SNSSYNCDEL m=$name a=$a b=$b")
    }
}
