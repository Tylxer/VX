package com.lx.wxgroup.core

import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers

/**
 * 真·群「消息免打扰」setter —— 直接复用微信自己的开关链路（发 oplog CGI），
 * 替代 AutoDndGroup 原来的降级实现（只把通知改写到静音通道、不动微信开关）。
 *
 * ## 取证依据（2026-10-18，微信 8.0.48/2588 `gp` 版，classes4.dex 反编译实证）
 *
 * 链路来自 `ChatroomInfoUI.onPreferenceTreeClick` 的 `room_notify_new_msg` 分支
 * （classes4.dex @0x22c794 起）：
 *
 * ```
 * 0440: v0 = this.I                       // I = 免打扰状态（true=已免打扰）
 * 0442: v0 ^= 1
 * 0443: this.I = v0                       // 翻转
 * 0449: v0 ^= 1                           // ⚠️ 又翻回去 = 操作【前】的状态
 * 044c: svc   = d94.n0.c(mz.e.class)      // 服务定位（运行时类型 lz/e，implements mz/e）
 * 0456: fac   = svc.x8(chatroomId)        // -> q64/f 工厂
 * 045c: req   = fac.c(chatroomId, v0)     // -> com.tencent.mm.roomsdk.model.factory.a
 * 0460: req.b()                           // 发 oplog CGI
 * ```
 *
 * ### ✅ `c(String,int)` 的 int = 目标状态，但语义是「**接收新消息通知**」= `!免打扰`
 *
 * **0 = 开启免打扰，1 = 取消免打扰。**（⚠️ 反直觉，写反就正好反向操作）
 *
 * 实证（2026-10-18 真机，群「74960钻粉①群」=`20000134088@chatroom`）。
 * 四组数据全部自洽，**不依赖字节码推断**：
 *
 * | 时刻 | 事件 | UI 开关（用户目视） | `S` 字段 |
 * |---|---|---|---|
 * | 02:21:57 | 手动点第 1 次 → `c(id, 1)` | 开→关 | — |
 * | 02:22:02 | 手动点第 2 次 → `c(id, 0)` | 关→开 | — |
 * | 02:22:50 | 点完两次复查 | 开 | `false` |
 * | 02:26:11 | **探针发 `c(id,1)`** | — | — |
 * | 02:26:51 | 复查 + 用户目视确认 | **关** | `true` |
 * | 02:38:23 | 再复查 | 关 | `true` |
 * | 02:39:5x | **用户手动打开开关** | **开** | — |
 * | 02:40:00 | 复查 | 开 | `false` |
 *
 * 读法：`S=false ↔ UI 开（已免打扰）`、`S=true ↔ UI 关（未免打扰）`。
 * ⇒ `S` 是「接收通知」标志（反相）；int 与 `S` 同向 ⇒ **int = 目标的「接收通知」值**。
 * 所以发 `1` 得到的是"未免打扰"（当时本来就是关，看着像没生效），`0` 才是开启免打扰。
 *
 * ⚠️ 历史坑（别改回）：
 * - v1 按字节码（`0449: v0 ^= 1`）推成"传操作前状态" → `if (cur) 1 else 0`，**传反**。
 * - v2 推成"传目标状态、1=免打扰" → `if (mute) 1 else 0`，**还是传反**；
 *   当时误把 `S` 当免打扰标志，"发 1 后 S 变 true"被错判成生效。
 * - v3（当前）：`if (mute) 0 else 1`。由「用户手动开开关 + 目视确认」这条
 *   不依赖任何推断的锚点定死。
 *
 * **教训**：只靠"发完看某个字段变了"来定语义**不可靠** —— 字段本身方向可能就是反的。
 * 必须先用「用户手动操作到已知 UI 状态 → 读字段」标定方向，再验证 setter。
 *
 * 工厂实现 `ln0/a.c`（classes4.dex @0x6bba48）内部把它映射成
 * `jn0/k(chatroomId, x)`：`p2 != 0 -> x = 1`、`p2 == 0 -> x = 2`。
 * ⚠️ 真机命中的工厂运行时类型是 **`uj.a`**（不是 `ln0.a`），`uj.a.c` 的映射
 * 尚未反编译 —— 但既然上面是端到端实测，以实测为准。
 *
 * ### ✅ 状态字段 `contact.S`：同样**反相**（true = 未免打扰，见上表）
 * `ChatroomInfoUI.N6()`（classes4.dex @0x22e1b0）刷新 UI 时：
 * `I != 0` → `MMBaseAccessibilityConfig.setValue("is_mute", 非空串)` +
 * `setTitleMuteIconVisibility(0)` + SharedPreferences `room_notify_new_msg=true`。
 * `I` 有两条赋值路径（`j!=0` 读 `contact.S`、`j==0` 读 `vj0.e2.K`），真机走的是 `S`。
 * ⚠️ `vj0.e2.K` 实测**恒 false**（发 CGI 前后都不变），跟免打扰不同步，只作兜底。
 *
 * ### 失败静默的两个坑
 * 1. `lz/e.x8` 在 chatroomId **不含 `@`** 时会返回兜底空实现 `q64/g`
 *    （`c()/b()` 全是 no-op，不报错）。本类检测并报 `fallback`。
 * 2. `x8` 拿不到任何工厂时同样返回 `q64/g`。
 *
 * ### 边界
 * - 只**发 CGI**（服务端生效），不写本地库 —— 本地状态由服务端 sync 推回，
 *   避免直接改微信 DB（CONFLICT / 字段语义均有风险）。
 * - ⚠️ 实测 sync 延迟约 **数十秒**（02:26:11 发 CGI，02:26:51 才读到 S=true，
 *   3 秒时读还是旧值）。所以：
 *   - 功能侧**不要**用发完立即读 [isMuted] 来判成败（会误判失败）；
 *   - 也不要拿"立刻复查没变"当 bug。
 *   - 连发两次相同目标时，第二次会因为在延迟窗口内读到旧值而重复发 —— 幂等，无害。
 * - 混淆类名/方法名（`d94.n0` / `mz.e` / `x8` / `ln0.a`）绑定当前取证版本，
 *   微信升级后需重新取证（模块整体受 VersionGate 门禁保护）。
 */
object RealMuteSetter {

    // ---- setter 链路 ----

    /** 服务定位器：`d94.n0.c(Class) -> d94.m` */
    private const val CLS_N0 = "d94.n0"

    /** 服务接口（运行时实现 = `lz/e`） */
    private const val CLS_MZ_E = "mz.e"

    /** 工厂获取：`x8(String chatroomId) -> q64/f` */
    private const val M_X8 = "x8"

    /** 兜底空实现类名：命中即代表这次调用是 no-op */
    private const val CLS_Q64_G = "q64.g"

    /**
     * 已知的真工厂实现（真机命中 `uj.a`；`ln0.a` 是反编译看到的另一个实现）。
     * 拿到白名单外的类不直接判失败（可能只是新版换了实现），但**必须打 warn** ——
     * 否则微信升级换成别的 no-op 兜底时会变成"日志正常、什么都没发生"。
     */
    private val KNOWN_FACTORIES = setOf("uj.a", "ln0.a")

    /** 构造请求：`c(String chatroomId, int state) -> roomsdk/model/factory/a` */
    private const val M_C = "c"

    /** 发 CGI：`b()V` */
    private const val M_B = "b"

    // ---- 读状态链路 ----

    /** 另一个服务定位器：`y70.k1.s(Class) -> d80/a` */
    private const val CLS_K1 = "y70.k1"

    /** 会话/联系人服务键 */
    private const val CLS_D3 = "oo2.d3"

    /** `z8() -> com.tencent.mm.storage.q4` */
    private const val M_Z8 = "z8"

    /** `q4.n(String username, boolean) -> com.tencent.mm.storage.h4` */
    private const val M_N = "n"

    /** `vj0.e2.K(h4) -> boolean` 读群免打扰状态 */
    private const val CLS_E2 = "vj0.e2"
    private const val M_K = "K"

    /** 一次调用的结果（供探针打印 / 功能侧判成败） */
    data class Outcome(
        /** 是否把 CGI 发出去了（不等于服务端一定成功） */
        val ok: Boolean,
        val detail: String,
        /** 传给 `c()` 的 int（取证/排错用） */
        val stateInt: Int = -1,
        /** 工厂运行时类名（取证用：命中 q64.g 就是没生效） */
        val factoryClass: String? = null,
        /** 发出前读到的当前状态（null=没读出来/取证模式跳过） */
        val before: Boolean? = null,
        /**
         * 是否**真的**把 CGI 发出去了。
         * ⚠️ 与 [ok] 的区别：`ok=true` 只代表"调用链没抛异常"，也可能是
         * "已是目标状态直接 skip"（没发）。功能侧判"要不要记账/要不要重试"必须看这个。
         */
        val sent: Boolean = false,
    )

    /** 拿联系人对象 `com.tencent.mm.storage.h4`（两种读状态的公共前置） */
    fun contactOf(cl: ClassLoader, chatroomId: String): Any? {
        return runCatching {
            val svc = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(CLS_K1, cl), "s", XposedHelpers.findClass(CLS_D3, cl)
            ) ?: return@runCatching null
            val q4 = XposedHelpers.callMethod(svc, M_Z8) ?: return@runCatching null
            XposedHelpers.callMethod(q4, M_N, chatroomId, false)
        }.onFailure { LogX.w("RealMute contactOf failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /**
     * ✅ 主读法：联系人字段 `S`（真机实测，2026-10-18）。
     *
     * 实证：对 `20000134088@chatroom` 发 `c(id, 1)` 后 40 秒复查，
     * `S` 从 false 变 true，而 `vj0.e2.K` 始终是 false —— 说明服务端 sync 回来
     * 只更新 `S`，`K` 那条路跟免打扰不同步（`I` 的两个来源里，运行时走的是 `S`）。
     *
     * ⚠️ 早期版本把 [isMuted] 实现成走 `K`，后果是：K 恒 false →
     * 关免打扰时被误判成"已经是未免打扰"直接 skip，**永远发不出去**。已修正。
     *
     * @return true=已免打扰，false=未免打扰，null=读取失败（反射链路断了）
     */
    fun isMuted(cl: ClassLoader, chatroomId: String): Boolean? {
        val s = isMutedByField(cl, chatroomId)
        if (s != null) return s
        // 兜底：S 读不到时退回 K（比返回 null 强，至少有个值）
        LogX.w("RealMute isMuted: field S unavailable, fallback to vj0.e2.K")
        return isMutedByK(cl, chatroomId)
    }

    /**
     * 读联系人字段 `S`（`N6()` 里 `j!=0` 那条分支用 `S != 0`）。
     *
     * ⚠️ **`S` 是反相的**：`S != 0` = 未免打扰（接收通知），`S == 0` = 已免打扰。
     * 已按真机标定取反（见 [isMuted] 的实测表），返回值统一成"true=已免打扰"。
     */
    fun isMutedByField(cl: ClassLoader, chatroomId: String): Boolean? {
        val h4 = contactOf(cl, chatroomId) ?: return null
        return runCatching {
            // findField 会沿父类找；失败抛 NoSuchFieldError，由 onFailure 兜住
            val f = XposedHelpers.findField(h4.javaClass, "S")
            f.isAccessible = true
            val raw = f.get(h4)
            LogX.d("RealMute field S raw=$raw")
            when (raw) {
                is Boolean -> !raw     // S 反相
                is Number -> raw.toInt() == 0
                else -> {
                    LogX.w("RealMute field S type=${raw?.javaClass?.simpleName}")
                    null
                }
            }
        }.onFailure { LogX.w("RealMute isMutedByField failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /**
     * 另一种读法 `vj0.e2.K(h4)`（对应 `N6()` 里 `j==0` 那条分支）。
     * ⚠️ 实测跟免打扰**不同步**（发 CGI 后它没变），仅作兜底/比对，别当主判据。
     */
    fun isMutedByK(cl: ClassLoader, chatroomId: String): Boolean? {
        val h4 = contactOf(cl, chatroomId)
            ?: run { LogX.w("RealMute isMutedByK: no contact"); return null }
        return runCatching {
            val raw = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(CLS_E2, cl), M_K, h4
            )
            when (raw) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                else -> {
                    LogX.w("RealMute isMutedByK: K returned ${raw?.javaClass?.simpleName}")
                    null
                }
            }
        }.onFailure { LogX.w("RealMute isMutedByK failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /**
     * 把某个群设为 / 取消「消息免打扰」。
     *
     * @param mute true=开启免打扰，false=关闭免打扰
     * @param forceStateInt **取证专用**：直接指定传给 `c()` 的 int，跳过"读状态→判定"。
     *   正常功能调用不要传（传 null 走安全路径）。
     */
    fun setMuted(
        cl: ClassLoader,
        chatroomId: String,
        mute: Boolean,
        forceStateInt: Int? = null,
    ): Outcome {
        // 1) 定传给 c() 的 int = 目标的「接收通知」值（0=开启免打扰 / 1=取消，见 KDoc）
        //    ⚠️ 是 !mute 不是 mute —— 写反就正好反向操作，已踩过两次
        //    cur 只用于「已经是目标状态就别发」和日志，读失败不阻断（映射不依赖它）
        val cur = if (forceStateInt != null) null else isMuted(cl, chatroomId)
        val stateInt = forceStateInt ?: run {
            if (cur == mute) {
                return Outcome(true, "already ${if (mute) "muted" else "unmuted"} — skip", -1, null, cur)
            }
            if (mute) 0 else 1
        }

        // 2) 服务定位 -> 工厂
        val svc = runCatching {
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(CLS_N0, cl),
                "c",
                XposedHelpers.findClass(CLS_MZ_E, cl)
            )
        }.onFailure { LogX.w("RealMute svc failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return Outcome(false, "service resolve failed (d94.n0.c / mz.e)", stateInt, null, cur)

        val factory = runCatching { XposedHelpers.callMethod(svc, M_X8, chatroomId) }
            .onFailure { LogX.w("RealMute x8 failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return Outcome(false, "x8() failed", stateInt, null, cur)

        val facName = factory.javaClass.name
        // 兜底空实现：c()/b() 都是 no-op，必须当成失败报出来
        if (facName == CLS_Q64_G) {
            return Outcome(
                false,
                "fallback factory q64.g — x8() 未拿到真工厂（chatroomId 含 '@' 吗？）",
                stateInt, facName, cur
            )
        }
        if (facName !in KNOWN_FACTORIES) {
            LogX.w("RealMute unknown factory=$facName — 微信升级换了实现？按真工厂继续，结果待验证")
        }

        // 3) 构造请求 + 发 CGI
        val req = runCatching { XposedHelpers.callMethod(factory, M_C, chatroomId, stateInt) }
            .onFailure { LogX.w("RealMute c() failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return Outcome(false, "c() failed", stateInt, facName, cur)

        // b() 不抛异常 ≠ CGI 真发出去了。把返回值打出来，便于判断（boolean/int/void）
        val bret = runCatching { XposedHelpers.callMethod(req, M_B) }.onFailure {
            LogX.w("RealMute b() failed: ${it.javaClass.simpleName}")
            return Outcome(
                false, "b() failed: ${it.javaClass.simpleName}", stateInt, facName, cur
            )
        }.getOrNull()
        LogX.i("RealMute b() ret=${bret?.javaClass?.simpleName} val=$bret req=${req.javaClass.name}")

        return Outcome(
            true,
            "cgi sent: before=$cur -> ${if (mute) "mute" else "unmute"} bRet=$bret",
            stateInt, facName, cur, sent = true
        )
    }
}
