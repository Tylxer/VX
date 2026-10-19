package com.lx.wxgroup.core

import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * S9 发送接口「不依赖 UI 发文本消息」的真实发送器。
 *
 * 群发（[com.lx.wxgroup.feature.MassSendPage]）与强制转发降级重发
 * （[com.lx.wxgroup.feature.ForceForward.resendText]）共用的最后一公里。
 *
 * ## 取证依据（2026-10-18 真机端到端验证，微信 8.0.48/2588 `gp` 版）
 *
 * 方法名已混淆，入口靠「日志 tag / CGI 路径 / 类结构」反推，并经**只读反射探针
 * 全绿 + 真发落库**两路交叉验证（见 `probe/SendProbe.kt` 与 `_cache/_s9_candidates.md`）：
 *
 * ```
 * q1 = hl0/r1.b(talker)     // PUBLIC STATIC FINAL 工厂，返回 builder
 * q1.e(content)             // 设正文（链式 builder）
 * q1.b()                    // executeByPPC 跨进程异步发送
 * ```
 *
 * 实测（`op=send sub=test --es val filehelper`）：
 * - 字段读回 `a=filehelper`（talker 已由工厂预设）、`d=1`（type=文本，默认值）；
 * - `message` 表落库 `type=1 isSend=1 content=<正文>`，真实投递成功。
 *
 * 所以最小序列**只需三步**，无需再调 `h(talker)` / `i(type)` —— 工厂返回时已设好。
 *
 * ## 发送线程
 * 真机是在后台线程调 `q1.b()` 且落库成功（与 RealMuteSetter 那个「必须主线程发 CGI」
 * 不同 —— 本链路是 executeByPPC 跨进程异步，不依赖主线程）。调用方保持各自的后台线程即可。
 *
 * ## 边界（诚实声明）
 * - `q1.b()` 返回 void、且是异步 PPC：**不抛异常 ≠ 服务端一定成功**。本类只保证
 *   「构造并投递了请求」，不保证送达（失败重试 / 对账由上层状态机负责）。
 * - 只在主进程 prepare 一次（见 HookEntry）；多进程（tools 等）不加载本模块，无需处理。
 */
object TextSender {

    @Volatile
    private var loader: ClassLoader? = null

    /** 只保存 classLoader，不挂钩子 —— 与其它取证/业务类一致 */
    fun prepare(lpparam: XC_LoadPackage.LoadPackageParam) {
        loader = lpparam.classLoader
    }

    /** 发送链路是否可用（模块是否已 prepare）。不可用时上层应判 NO_CHANNEL 而非 FAIL。 */
    fun ready(): Boolean = loader != null

    /**
     * 发一条纯文本消息。
     *
     * @return true=请求已投递（不等于服务端一定送达）；false=链路缺失或反射失败
     */
    fun send(wxid: String, content: String): Boolean {
        val cl = loader ?: run {
            LogX.w("TextSender no classLoader (模块未 prepare？)")
            return false
        }
        if (wxid.isBlank()) {
            LogX.w("TextSender blank wxid")
            return false
        }
        if (content.isBlank()) {
            LogX.w("TextSender blank content (wxid=${wxid.take(24)})")
            return false
        }
        return runCatching {
            val r1 = XposedHelpers.findClass("hl0.r1", cl)
            val q1 = XposedHelpers.callStaticMethod(r1, "b", wxid)
            if (q1 == null) {
                LogX.w("TextSender r1.b(${wxid.take(12)}) -> null")
                return@runCatching false
            }
            XposedHelpers.callMethod(q1, "e", content)
            XposedHelpers.callMethod(q1, "b")
            LogX.i("TextSender sent wxid=${wxid.take(24)} len=${content.length}")
            true
        }.onFailure {
            LogX.w("TextSender send failed ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }
}
