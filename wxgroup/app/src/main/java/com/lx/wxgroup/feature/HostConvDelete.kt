package com.lx.wxgroup.feature

import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.InvocationTargetException

/**
 * R70 A′：删除分组时补发宿主删除通知 `s4.b(5, s4, "wxgrp_<hex>")`，触发宿主会话列表
 * 重查（`p3.f`）让分组行**不冷启即时消失**。
 *
 * 取证与落点设计见 `docs/_r70_microx_delete.md`（dex 结论主会话已独立复核）：
 * - `k74/y0.b(int,k74/y0,Object)`（通知入口本体，classes4.dex `0x6941e8`）反汇编只有
 *   `new k74/w0` + `k74.t0.d(w0)` + `k74.t0.c()` ⇒ **只投递事件总线，不写库、不删消息**。
 *   这是本方案满足红线（不连带删消息 / 清空聊天记录）的核心依据；绝不调宿主 delete API
 *   `s4.k/n/l`（那才是真删 SQL）。
 * - 宿主 `s4.k(String)` 删单行（classes4.dex `0x4a5d3c`）删到行才发 `b(5, s4, username)`，
 *   payload 用**具体 username**；`s4.l()` 全清才用空串 `b(5, s4, "")`。空串会走
 *   `p3.N=true + i3.r(null,1)` 全清分支（副作用大），故删单个组行**绝不用空串**。
 * - 单例链 `vj0.h8.b().v()`（与 [NativeConvInsert] 同款 2 步，拿 `s4` 实例，声明类型 o8）。
 *
 * 本类只封装反射链、**只发事件不删数据**：删行仍走 `GroupConvPlan.deleteConvSql/deleteContactSql`
 * 的 execSQL，由 [GroupConvStore] 在事务提交后调用本类。每个反射点都 `runCatching`，失败只打
 * `LogX.w` 并返回，绝不让通知失败影响删行主流程。整套混淆名绑 8.0.48 / 2588 —— 模块级
 * [com.lx.wxgroup.core.VersionGate] 已在 HookEntry.kt 全局拦，本类不另造门禁。
 */
object HostConvDelete {

    const val TAG = "GCONV_DELNOTIFY"

    // ---- 混淆名集中定义（绑 2588，便于 VersionGate / 交叉复核审查）----
    private const val CLS_H8 = "vj0.h8"      // 单例容器（classes7.dex）
    private const val M_H8_B = "b"           // static：vj0.h8.b() → vj0.e（内部已走 d().a）
    private const val M_E_V = "v"            // vj0.e.v() → s4 实例（声明类型 o8，实际是 s4）
    private const val M_S4_B = "b"           // s4.b(int,y0,Object)：继承 k74/y0.b，只发事件
    private const val EVENT_DELETE = 5       // 事件码 5 = delete（宿主 s4.k 删单行同款）

    /** 宿主 classLoader（install 时存）。⛔ 绝不能用模块自己的 loader，那找不到宿主 vj0.h8/s4。 */
    @Volatile
    private var hostCl: ClassLoader? = null

    /** 装机时读一次的开关结果：`chat.gconv_delete_notify ∧ chat.gconv_enabled`。默认 false。 */
    @Volatile
    private var enabled = false

    /**
     * 由 HookEntry 在 install 序列里调一次（与 [NativeConvInsert] 同款）：存宿主 CL + 读开关。
     * 开关装机读一次 ⇒ 切换后冷启微信生效，与既有 gconv 开关语义一致。
     */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hostCl = lpparam.classLoader
        enabled = runCatching {
            Kv.getBool(Keys.GCONV_DELETE_NOTIFY) && Kv.getBool(Keys.GCONV_MODE)
        }.getOrDefault(false)
    }

    /** 开关：`chat.gconv_delete_notify ∧ chat.gconv_enabled`（install 时读一次的结果）。默认 false。 */
    fun isEnabled(): Boolean = enabled

    /**
     * 补发删除通知：`s4.b(5, s4, username)`（第二参传 s4 自己，对齐宿主 `y0.b(5,s4,user)` 的写法；
     * username 是 String 作为 Object 传入）。
     *
     * @param username 已由 `GroupConvPlan.usernameOf` 生成的 `wxgrp_<16hex>`（具体值，**非空串**）。
     * 反射链任一步失败只打 `LogX.w` 并返回，绝不抛给调用方、绝不影响删行主流程。
     */
    fun notifyDeleted(username: String) {
        val cl = hostCl ?: run {
            LogX.w("$TAG no host classLoader（install 未调）user=$username")
            return
        }
        val s4 = runCatching { s4Instance(cl) }
            .onFailure { LogX.w("$TAG s4 单例链 fail: ${why(it)}") }
            .getOrNull() ?: return
        runCatching { XposedHelpers.callMethod(s4, M_S4_B, EVENT_DELETE, s4, username) }
            .onFailure { LogX.w("$TAG s4.b(5,s4,$username) fail: ${why(it)}") }
    }

    // ------------------------------------------------------------------ 反射底层

    /** 单例链 `vj0.h8.b().v()`：2 步拿到 `s4` 实例（声明类型 o8，实际是 s4）。 */
    private fun s4Instance(classLoader: ClassLoader): Any? {
        val h8cls = XposedHelpers.findClass(CLS_H8, classLoader)
        val vj0e = XposedHelpers.callStaticMethod(h8cls, M_H8_B) ?: return null
        return XposedHelpers.callMethod(vj0e, M_E_V)
    }

    /** 拆掉 InvocationTargetException 这层壳（真正原因在 cause 里），与 NativeConvInsert 同款。 */
    private fun why(t: Throwable): String {
        val c = (t as? InvocationTargetException)?.targetException ?: t.cause ?: t
        val m = c.message?.replace('\n', ' ')?.replace('\r', ' ')
        return if (m.isNullOrBlank()) c.javaClass.name else m
    }
}
