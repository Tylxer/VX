package com.lx.wxgroup.feature

import com.lx.wxgroup.data.GroupConvPlan
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.InvocationTargetException

/**
 * R67 b2：新建分组走宿主原生 insert API `s4.I(r4)`，让宿主自己发 `b(2, s4, username)`
 * 通知，实现「不冷启即时显示分组行」（对比 execSQL 直写 rconversation 只能冷启可见）。
 *
 * 取证与落点设计见 `docs/_r67_b2_native_insert.md`（dex 结论主会话已独立复核）：
 * - `s4.I(Lcom/tencent/mm/storage/r4;)J`（classes4.dex `0x4a5528`）写 `rconversation`
 *   成功后**自己发 `b(2, s4, username)`**（事件码 2 = insert），返回 rowid、失败返回 -1 且不发通知。
 * - `r4` 构造 = `new r4()` + 7 个 setter（`B1/T0/b1/r1/j1/Q0/n1`，全在父类 `jj.y1`），
 *   字段按 dirty-flag 机制只写已 setter 的列；`msgType` 是 **String**（`n1("1")`），
 *   `flag` 会被 `s4.I` 经 `po2.a.c` 重算成 `2^62|time`（与 R54 置顶模板一致）。
 * - 单例链 `vj0.h8.b().v()`（`b()` static → `vj0.e` → `.v()` → `s4` 实例，声明类型 `o8`）。
 *
 * 本类只封装反射链，**不写库、不碰删除**（删除仍走 `GroupConvPlan.delete*Sql` execSQL，
 * 宿主 delete API 会连带删消息/清空聊天记录，红线 §7.4）。每个反射点都 `runCatching`，
 * 失败只打 `LogX.w`（带具体哪个环节）并返回 null，由 [GroupConvStore] 降级回 execSQL
 * 直写（现状路径）。整套混淆名绑 8.0.48 / 2588，已由 [com.lx.wxgroup.core.VersionGate]
 * 在打钩前拦截，跨版本升级必须重跑探针反查混淆名。
 */
object NativeConvInsert {

    const val TAG = "GCONV_NATIVE"

    // ---- 混淆名集中定义（绑 2588，便于 VersionGate / 交叉复核审查）----
    private const val CLS_H8 = "vj0.h8"                          // 单例容器（classes7.dex）
    private const val CLS_R4 = "com.tencent.mm.storage.r4"       // 会话行对象（classes4.dex）

    // vj0.h8 里字段 b（静态单例字段）与方法 b()（静态方法）同名 —— callStaticMethod 走的是方法。
    private const val M_H8_B = "b"   // static：vj0.h8.b() → vj0.e（内部已走 d().a）
    private const val M_E_V = "v"    // vj0.e.v() → s4 实例（声明类型 o8，实际是 s4）
    private const val M_S4_I = "I"   // s4.I(r4):long，写库 + 自动 b(2) 通知

    // 7 个 setter（全在父类 jj.y1，列表顺序即 §2.3 调用顺序）
    private const val SET_USERNAME = "B1"      // field_username = username
    private const val SET_CONV_TIME = "T0"     // field_conversationTime = timeMs
    private const val SET_FLAG = "b1"          // field_flag = 2^62 | time
    private const val SET_STATUS = "r1"        // field_status = 3
    private const val SET_IS_SEND = "j1"       // field_isSend = 1
    private const val SET_CHAT_MODE = "Q0"     // field_chatmode = 1
    private const val SET_MSG_TYPE = "n1"      // field_msgType = "1"（String！）

    /** msgType 必须是 String "1"（§2.5），传整数 1 会反射抛 IllegalArgumentException。 */
    private const val MSG_TYPE_TEXT = "1"

    /** 宿主 classLoader（install 时存）。⛔ 绝不能用模块自己的 loader，那找不到宿主 vj0.h8/s4/r4。 */
    @Volatile
    private var hostCl: ClassLoader? = null

    /** 装机时读一次的开关结果：`chat.gconv_native_insert ∧ chat.gconv_enabled`。默认 false。 */
    @Volatile
    private var enabled = false

    /**
     * 由 HookEntry 在 install 序列里调一次（与 [NativeConvAvatar] 同款）：存宿主 CL + 读开关。
     * 开关装机读一次 ⇒ 切换后冷启微信生效，与既有 gconv 开关语义一致。
     */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hostCl = lpparam.classLoader
        enabled = runCatching {
            Kv.getBool(Keys.GCONV_NATIVE_INSERT) && Kv.getBool(Keys.GCONV_MODE)
        }.getOrDefault(false)
    }

    /**
     * b2 开关：`chat.gconv_native_insert ∧ chat.gconv_enabled`（install 时读一次的结果）。
     * 默认 false（`Prefs.ALL` 里 def="false"），关着 = 现状 execSQL 直写，零行为变化。
     */
    fun isEnabled(): Boolean = enabled

    /** 2^62 置顶位 + 时间（与 [GroupConvPlan.flagOf] 同值）；`s4.I` 会重算成 `2^62|time`，值不变。 */
    fun flagOf(timeMs: Long): Long = GroupConvPlan.PIN_BASE + timeMs

    /**
     * 7 个 setter 及值（纯函数，供单测与反射链共用；列表顺序 = 反射调用顺序）。
     *
     * ⚠️ 必填字段（§7.3）：username / conversationTime / flag / status / isSend / chatmode / msgType
     * 缺一个都可能渲染异常，所以这里集中一处、绝不散落各 setter；`msgType` 是 String。
     */
    fun nativeSetters(username: String, timeMs: Long): List<Pair<String, Any>> = listOf(
        SET_USERNAME to username,
        SET_CONV_TIME to timeMs,
        SET_FLAG to flagOf(timeMs),
        SET_STATUS to 3,
        SET_IS_SEND to 1,
        SET_CHAT_MODE to 1,
        SET_MSG_TYPE to MSG_TYPE_TEXT,
    )

    /**
     * 走宿主原生 insert：单例链 → `new r4()` → 7 setter → `s4.I(r4)`，返回 rowid。
     * 宿主 CL 用 [install] 存的 [hostCl]（⛔ 不用模块自己的 loader）。
     *
     * @param username 已由 [GroupConvPlan.usernameOf] 生成的 `wxgrp_<16hex>`。
     * @param groupId  保留参数（username 已编码 gid，暂用于日志溯源）。
     * @return 插入成功返回非负 rowid；hostCl 未装、任一步反射失败、或 `s4.I` 返回 -1 均返回
     *         null（触发调用方降级 execSQL）。
     */
    fun insertConv(username: String, timeMs: Long, groupId: Long): Long? {
        val cl = hostCl ?: run {
            LogX.w("$TAG no host classLoader（install 未调）gid=$groupId user=$username")
            return null
        }
        val s4 = runCatching { s4Instance(cl) }
            .onFailure { LogX.w("$TAG s4 单例链 fail: ${why(it)}") }
            .getOrNull() ?: return null

        val r4 = runCatching { buildR4(cl, username, timeMs) }
            .onFailure { LogX.w("$TAG r4 构造 fail: ${why(it)}") }
            .getOrNull() ?: return null

        return runCatching { XposedHelpers.callMethod(s4, M_S4_I, r4) as? Long }
            .onFailure { LogX.w("$TAG s4.I(r4) fail gid=$groupId user=$username: ${why(it)}") }
            // s4.I DB 层插入失败返回 -1 且不发通知（§1.2 0034/0036）——归一化成 null 触发降级，
            // 否则 -1 会绕过 GroupConvStore 的 `rowid == null` 降级判断，造成 rcontact 孤儿半行。
            .getOrNull()?.takeIf { it >= 0L }
    }

    // ------------------------------------------------------------------ 反射底层

    /** 单例链 `vj0.h8.b().v()`：2 步拿到 `s4` 实例（声明类型 o8，实际是 s4）。 */
    private fun s4Instance(classLoader: ClassLoader): Any? {
        val h8cls = XposedHelpers.findClass(CLS_H8, classLoader)
        val vj0e = XposedHelpers.callStaticMethod(h8cls, M_H8_B) ?: return null
        return XposedHelpers.callMethod(vj0e, M_E_V)
    }

    /** `new r4()` + 7 setter。任一 setter 失败打具体环节并返回 null（§7.3 必填字段全 setter）。 */
    private fun buildR4(classLoader: ClassLoader, username: String, timeMs: Long): Any? {
        val r4cls = XposedHelpers.findClass(CLS_R4, classLoader)
        val r4 = XposedHelpers.newInstance(r4cls)
        for ((setter, value) in nativeSetters(username, timeMs)) {
            val e = runCatching { XposedHelpers.callMethod(r4, setter, value) }.exceptionOrNull()
            if (e != null) {
                LogX.w("$TAG setter $setter($value) fail: ${why(e)}")
                return null
            }
        }
        return r4
    }

    /** 拆掉 InvocationTargetException 这层壳（真正原因在 cause 里），与 GroupConvStore.msgOf 同款。 */
    private fun why(t: Throwable): String {
        val c = (t as? InvocationTargetException)?.targetException ?: t.cause ?: t
        val m = c.message?.replace('\n', ' ')?.replace('\r', ' ')
        return if (m.isNullOrBlank()) c.javaClass.name else m
    }
}
