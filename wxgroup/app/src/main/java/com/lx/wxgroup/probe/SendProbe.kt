package com.lx.wxgroup.probe

import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.util.LogX

/**
 * S9 发送接口「不依赖 UI 发文本消息」的**只读反射探针**。
 *
 * ## 背景（2026-10-18 静态逆向 dexdump，见 `_cache/_s9_candidates.md`）
 * 微信 8.0.48 方法名已混淆，发送入口靠「日志 tag / CGI 路径 / 类结构」反推得到：
 * - 发送总门面 `qf4/o4`：`W8(toUser,content,type,flag,msgSourceId)` / `H8(content,toUser)` / `V8(toUser,content,type,flag)`，
 *   服务经 `mo2/w0.a() : mo2/l0`（PUBLIC STATIC）取，实现类即 `qf4/o4`。
 * - 零服务定位的静态工厂链（**最适合 Xposed 反射**）：
 *   `hl0/r1.b(String talker) : hl0/q1`（PUBLIC STATIC FINAL）
 *   `hl0/q1`（builder，字段 a=toUser / c=content / d=type / h=状态位 全 PUBLIC）：
 *     `e(String):q1`（设 content）、`h(String):q1`（设 talker）、`i(int):q1`（设 type）、
 *     `b():void`（executeByPPC 跨进程异步发送）、`a():hl0/l1`（构造入队）。
 * - 底层兑现 `hl0/q0` = NetSceneSendMsg（extends modelbase/k1，CGI /cgi-bin/micromsg-bin/newsendmsg）。
 *
 * ## 为什么先只读、不真发
 * 反射调微信发消息类 = 高风险：签名错 / 字段语义错可能崩微信或发错消息。
 * 按项目铁律「真机测试前先只读取证」，本探针**只反射枚举**，验证：
 * ① 类在运行时 classloader 找得到；② 方法/字段签名匹配得上；③ 字段类型符合预期。
 * **不发送任何消息、不写任何库、不 hook 任何方法**（零副作用）。
 *
 * ## 用法
 * ```
 * # 只读反射枚举（安全，先跑这个）
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
 *      --es op send --es sub probe
 * # 发一条测试消息（**真发**，只在 probe 全绿、交叉验证后才用）
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
 *      --es op send --es sub test --es val <wxid 目标>
 * ```
 */
object SendProbe {

    @Volatile
    private var loader: ClassLoader? = null

    /** 只保存 classLoader，不打钩子 —— 与其它取证探针一致 */
    fun prepare(lpparam: XC_LoadPackage.LoadPackageParam) {
        loader = lpparam.classLoader
    }

    fun command(sub: String, arg: String?) {
        val cl = loader ?: run { own("no classLoader (模块未 prepare？)"); return }
        when (sub.lowercase()) {
            "probe" -> Thread({ reflectProbe(cl) }, "wxgroup-sendprobe").start()
            "test" -> Thread({ testSend(cl, arg) }, "wxgroup-sendtest").start()
            // 直接走**正式代码路径** [TextSender.send]（不是本探针的 testSend），
            // 用于验证 TextSender 接入后的真实链路。arg = 目标 wxid。
            "txt" -> Thread({
                val ok = com.lx.wxgroup.core.TextSender.send(
                    arg ?: "", SEND_TEST_BODY + " (TextSender)"
                )
                own("txt TextSender.send -> $ok target=${(arg ?: "").take(24)}")
            }, "wxgroup-sendtxt").start()
            else -> own("unknown sub=$sub (probe|test|txt)")
        }
    }

    // ---------------------------------------------------------------- 只读反射

    private fun reflectProbe(cl: ClassLoader) {
        own("==== S9 reflect probe ====")

        // 1) 发送服务门面 qf4/o4
        probeClass(cl, "qf4.o4", listOf("W8", "H8", "V8"))

        // 2) 服务定位 mo2/w0.a() -> mo2/l0
        probeStatic(cl, "mo2.w0", "a", "()Lmo2/l0;")

        // 3) 静态工厂 hl0/r1.b(String) -> q1
        probeStatic(cl, "hl0.r1", "b", "(String) -> q1")

        // 4) builder hl0/q1（字段 + 方法）
        probeClass(cl, "hl0.q1", listOf("e", "h", "i", "b", "a"))
        probeFields(cl, "hl0.q1", mapOf("a" to "String", "c" to "String", "d" to "int", "h" to "int"))

        // 5) 底层兑现 hl0/q0 = NetSceneSendMsg
        probeClass(cl, "hl0.q0", emptyList())

        own("==== S9 reflect probe done ====")
    }

    private fun probeClass(cl: ClassLoader, name: String, methods: List<String>) {
        val cls = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull()
        if (cls == null) {
            own("CLASS $name MISSING")
            return
        }
        val sb = StringBuilder("CLASS $name ok super=${cls.superclass?.name}")
        for (m in methods) {
            val sigs = runCatching { cls.declaredMethods }
                .getOrNull().orEmpty()
                .filter { it.name == m }
                .joinToString(";") { mth ->
                    "(${mth.parameterTypes.joinToString(",") { it.simpleName }})->${mth.returnType.simpleName}"
                }
            sb.append(" | $m[${if (sigs.isEmpty()) "MISSING" else sigs}]")
        }
        own(sb.toString())
    }

    private fun probeStatic(cl: ClassLoader, name: String, method: String, expect: String) {
        val cls = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull()
        if (cls == null) {
            own("STATIC $name MISSING (class)")
            return
        }
        val ms = runCatching { cls.declaredMethods }
            .getOrNull().orEmpty().filter { it.name == method }
        if (ms.isEmpty()) {
            own("STATIC $name.$method MISSING (method)")
            return
        }
        for (m in ms) {
            val params = m.parameterTypes.joinToString(",") { it.simpleName }
            own("STATIC $name.$method($params)->${m.returnType.simpleName} [expect $expect]")
        }
    }

    private fun probeFields(cl: ClassLoader, name: String, fields: Map<String, String>) {
        val cls = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull() ?: return
        val declared = runCatching { cls.declaredFields }.getOrNull().orEmpty()
        val sb = StringBuilder("FIELDS $name")
        for ((fn, expect) in fields) {
            val f = declared.firstOrNull { it.name == fn }
            sb.append(" | $fn=${if (f == null) "MISSING" else f.type.simpleName + (if (f.type.simpleName == expect) "" else "(≠$expect)")}")
        }
        own(sb.toString())
    }

    // ---------------------------------------------------------------- 真发（测试）

    /**
     * 真发一条测试消息。**只在 reflectProbe 全绿、且交叉验证后才用**。
     * 走 H3 最小序列：`hl0/r1.b(talker)` → `q1.e(content)` → `q1.b()`。
     */
    private fun testSend(cl: ClassLoader, target: String?) {
        if (target.isNullOrBlank()) {
            own("test: need --es val <wxid>")
            return
        }
        own("test: preparing send to ${target.take(24)} ...")
        runCatching {
            val r1 = XposedHelpers.findClass("hl0.r1", cl)
            val q1 = XposedHelpers.callStaticMethod(r1, "b", target) ?: run {
                own("test: r1.b(${target.take(12)}) returned null")
                return@runCatching
            }
            val q1Cls = XposedHelpers.findClass("hl0.q1", cl)
            // 设内容（e(String) 返回 this，链式，但 callMethod 拿返回值更稳）
            val afterE = XposedHelpers.callMethod(q1, "e", SEND_TEST_BODY)
            own("test: q1.e() -> ${afterE?.javaClass?.name ?: "null"}")
            // 读回字段确认（a=toUser c=content d=type）
            val toUser = XposedHelpers.getObjectField(q1, "a")
            val content = XposedHelpers.getObjectField(q1, "c")
            val type = XposedHelpers.getIntField(q1, "d")
            own("test: q1 fields a=$toUser c=${(content as? String)?.take(24)} d=$type")
            // 真发
            XposedHelpers.callMethod(q1, "b")
            own("test: q1.b() called (async/PPC, 结果需看目标会话是否收到)")
        }.onFailure {
            own("test: FAILED ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private const val SEND_TEST_BODY = "[模块自检] S9 发送链路测试"

    private fun own(msg: String) {
        LogX.i("SEND $msg")
    }
}
