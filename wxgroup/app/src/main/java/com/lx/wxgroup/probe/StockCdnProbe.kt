package com.lx.wxgroup.probe

import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.Collections
import java.util.LinkedHashMap

/**
 * R78 · 验证裁决 (b) 能否成立的**只读前置探针**（前缀 = `SCD`，logcat tag 沿用 [LogX] 的 `WXGroup`）。
 *
 * 判据设计与 dex 锚点全部来自 `docs/_r78_m0l0_miss_verdict.md`（Pro lane，§Q4.2），本文件**只落码不改判据**。
 * R77 把回调角色挂错：`qf4/m0.l0` 是 CGI `d3.F8().c(...)` 的 `fv/e0` 回调，不是 CDN 完成回调；
 * R78 已裁决真回调 = `qf4/j0.C2`（CDN 完成）+ `b1.j6`（checkExist 成功分支落新记录），且 `j2.R()` 的
 * localId 在 `r0.a/b` 而非 `i/h/j/Q`。落点：① `SCD-CDNcb` 看 checkExist 成功/失败与是否产字节；
 * ② `SCD-b1j6` 看 `PString.value` 大图路径出参 + 新 localId；③ `SCD-j2R` 读 `r0.a/b/e/f/g`；
 * ④ `SCD-m1e` 看 addSendTask 与 listener 身份；⑤ `SCD-B6` 样本身份闸门（`216b1f7c`）。
 * 本探针**不改一行宿主代码**。
 *
 * ## 🔴 只读纪律（最高优先级，改动本文件前先读）
 *
 * 1. 所有 `before/after` **只打日志**：绝不写 `param.args[...]`、绝不写 `param.result`。
 * 2. **绝不主动调用任何宿主业务方法**。唯一允许的"读"是纯 getter / 反射读字段
 *    （`XposedHelpers.getObjectField/getBooleanField/getLongField` + `java.lang.reflect.Field.get`），
 *    且**单独 runCatching**，拿不到打 NA。严禁在回调里调 `b1.R5/j6/o2/z3/Y2`、`mq/k.B8`、
 *    `ok0/m1.e`、`qf4/m0.l0` 替宿主做事——那会引入下载/DB/发送副作用，破坏只读前提。
 * 3. `enabled=false` 时回调第一行就 return（零反射、零字符串拼接）。hook 常驻安装，靠广播
 *    `op=stockcdn sub=on|off`（无 sub=status 回显）控制日志量（默认 false，冷启静默）。
 * 4. 每个 hook 用 [guard] 包住，单钩失败只记 FAIL + 一条 WARN，**不崩微信、不影响其它 hook**；
 *    回调体同样全包 runCatching。
 * 5. 日志裁剪：普通 String `take(80)`、路径 `take(120)`；cdnbigimgurl 只打"是否存在 + 值长度"，
 *    绝不打完整 key；不刷屏。
 * 6. 幂等 [installed] 保证只装一次；[status] 回显供 F2 前置校验。
 *
 * ## 签名兜底
 *
 * 签名若与真机不符（混淆名/描述符漂移），先 `hookAllMethods(类, 方法名, cb)` 全挂并在回调里
 * 打 `method sig`（`msig=`），**不硬失败**；`hookAllMethods` 一个都挂不上才由 guard 记 FAIL。
 * 只用 XposedHelpers 硬编码混淆名，不依赖 DexKit；安装范式照抄 `probe/MicroXImageObserveProbe.kt`。
 */
object StockCdnProbe {

    /** 路径样值标记：dumpStringFields 给疑似本地大图路径的字段打此后缀，供真机判据一眼识别。 */
    private const val PATH_MARK = "*PATH*"

    /** 只有 enabled 时回调才打日志；hook 本身常驻。广播可即时翻转。 */
    @Volatile
    var enabled = false

    /** 幂等：install 只跑一次。 */
    @Volatile
    private var installed = false

    /** 每个落点的安装结果（key = hook code SCD-B6..SCD-j2R），供 `op=stockcdn` 回显。 */
    private val hookStatus: MutableMap<String, Boolean> =
        Collections.synchronizedMap(LinkedHashMap<String, Boolean>())

    /** hook code → 人读标签，仅用于 status() 行的 `...` 部分，不参与判据。 */
    private val hookLabels = mapOf(
        "SCD-B6" to "MsgRetransmitUI.B6",
        "SCD-m1e" to "ok0/m1.e",
        "SCD-CDNcb" to "qf4/j0.C2",
        "SCD-s0b" to "modelimage/s0.b",
        "SCD-b1j6" to "modelimage/b1.j6",
        "SCD-j2R" to "modelimage/j2.R",
        "SCD-f2C2" to "modelimage/f2.C2",
        "SCD-B8" to "mq/k.B8",
    )

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
        val cl = lpparam.classLoader

        // 类先动态找（不写死 dex code_off）。失败只记 FAIL，其它 hook 不受影响。
        val b6cls = findCls("com.tencent.mm.ui.transmit.MsgRetransmitUI", cl)  // SCD-B6
        val m1cls = findCls("ok0.m1", cl)                                      // SCD-m1e
        val cdncls = findCls("qf4.j0", cl)                                     // SCD-CDNcb（真 CDN 回调）
        val s0cls = findCls("com.tencent.mm.modelimage.s0", cl)                // SCD-s0b
        val b1cls = findCls("com.tencent.mm.modelimage.b1", cl)                // SCD-b1j6（新记录路径）
        val j2cls = findCls("com.tencent.mm.modelimage.j2", cl)                // SCD-j2R
        val f2cls = findCls("com.tencent.mm.modelimage.f2", cl)                // SCD-f2C2（上传完成回调重建带 URL 消息）
        val mqkcls = findCls("mq.k", cl)                                       // SCD-B8（服务端 checkExist）

        // ---- SCD-B6 MsgRetransmitUI.B6(String,int,t0)V @0x62c98c —— CDN 参数解析 / task 构造 ----
        // cdnbigimgurl 只打是否 + 长度；task(tj/k) 在 B6 内构造，入参/this 找不到则打 NA（m1e 处可靠再打）。
        guard("SCD-B6") {
            hookRobust(
                "SCD-B6", b6cls, "B6",
                sig("B6", listOf("java.lang.String", "int", "com.tencent.mm.modelbase.t0"), "void"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching {
                            val msig = sigOf(param)
                            val a = param.args
                            val s0 = (a?.getOrNull(0) as? String ?: "null").take(80)
                            val i1 = a?.getOrNull(1) as? Int ?: -1
                            val p2 = a?.getOrNull(2)?.javaClass?.simpleName ?: "null"
                            LogX.i("SCD B6 in msig=$msig s0=$s0 i1=$i1 p2=$p2")

                            // r0.u 里的 cdnbigimgurl：只打"是否+长度"，绝不打完整 key。
                            val r0 = findObjectOfType(a, param.thisObject, "com.tencent.mm.modelimage.r0")
                            val u = runCatching { XposedHelpers.getObjectField(r0, "u") as? String }.getOrNull()
                            val hasBig = u?.contains("cdnbigimgurl") == true
                            val bigLen = Regex("cdnbigimgurl=\"([^\"]*)\"")
                                .find(u ?: "")?.groupValues?.getOrNull(1)?.length ?: -1
                            LogX.i("SCD B6 r0.u hasCdnBigImgUrl=$hasBig bigUrlLen=$bigLen uLen=${u?.length ?: -1}")

                            // 样本身份闸门（R78 §Q4.2）：r0.u 是否含坏图 md5 216b1f7c；只打布尔，不打完整 key。
                            val hasBigImgUrl = u?.contains("216b1f7c") == true
                            LogX.i("SCD B6 r0.u hasBigImgUrl=$hasBigImgUrl")

                            // task(tj/k) 字段：B6 内构造，before 时多为空 → NA 属正常，m1e 处再可靠取。
                            val task = findObjectOfType(a, param.thisObject, "tj.k")
                            logTaskFields("B6", task)
                        }.onFailure { LogX.w("SCD B6 in err: ${it.javaClass.simpleName}") }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching { LogX.i("SCD B6 out") }
                            .onFailure { LogX.w("SCD B6 out err: ${it.javaClass.simpleName}") }
                    }
                }
            )
        }

        // ---- SCD-m1e ok0/m1.e(tj/k)Z @0x7b52b4 —— CDN 任务真正下发点（门槛① addSendTask）----
        guard("SCD-m1e") {
            hookRobust(
                "SCD-m1e", m1cls, "e",
                sig("e", listOf("tj.k"), "boolean"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching {
                            val msig = sigOf(param)
                            val task = param.args?.getOrNull(0)
                            LogX.i(
                                "SCD m1e in msig=$msig addSendTask " +
                                    "task=${task?.javaClass?.simpleName ?: "null"}"
                            )
                            logTaskFields("m1e", task)

                            // R78 §Q4.2：补打 listener 身份（tj/k.f，确认是否 qf4/j0）与路径字段（只读、take 120）。
                            val listenerName = runCatching {
                                XposedHelpers.getObjectField(task, "f")?.javaClass?.name?.take(120) ?: "null"
                            }.getOrDefault("NA")
                            val fullpath = runCatching {
                                (XposedHelpers.getObjectField(task, "field_fullpath") as? String)?.take(120)
                                    ?: "null"
                            }.getOrDefault("NA")
                            val midimgpath = runCatching {
                                (XposedHelpers.getObjectField(task, "field_midimgpath") as? String)?.take(120)
                                    ?: "null"
                            }.getOrDefault("NA")
                            LogX.i(
                                "SCD m1e in task listener.f=$listenerName " +
                                    "field_fullpath=$fullpath field_midimgpath=$midimgpath"
                            )
                        }.onFailure { LogX.w("SCD m1e in err: ${it.javaClass.simpleName}") }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching {
                            val ret = (param.result as? Boolean)?.toString() ?: "NA"
                            LogX.i("SCD m1e out addSendTask ret=$ret")
                        }.onFailure { LogX.w("SCD m1e out err: ${it.javaClass.simpleName}") }
                    }
                }
            )
        }

        // ---- SCD-CDNcb qf4/j0.C2(String,int,Ltj/e,Ltj/f,boolean)I @0x84307c —— 真 CDN 完成回调 ----
        // R78 §Q4.2：qf4/j0 是 MsgRetransmitUI.B6 挂进 tj/k.f 的 Ltj/i listener（接口实现），
        // 故直接 hookAllMethods 挂全 C2（签名漂移兜底）。看 checkExist 成功/失败与是否产字节。
        guard("SCD-CDNcb") {
            val set = XposedBridge.hookAllMethods(reqCls(cdncls, "SCD-CDNcb class"), "C2", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching {
                            val msig = sigOf(param)
                            val a = param.args
                            val s0 = (a?.getOrNull(0) as? String ?: "null").take(80)
                            val i1 = a?.getOrNull(1) as? Int ?: -1
                            val b4 = a?.getOrNull(4) as? Boolean ?: false
                            LogX.i("SCD CDNcb in msig=$msig s0=$s0 i1=$i1 b4=$b4")
                            LogX.i("SCD CDNcb in eIntFields=${dumpIntFields(a?.getOrNull(2), "e")}")
                            LogX.i("SCD CDNcb in fStrFields=${dumpStringFields(a?.getOrNull(3), "f")}")
                        }.onFailure { LogX.w("SCD CDNcb in err: ${it.javaClass.simpleName}") }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching {
                            val ret = (param.result as? Int) ?: -1
                            LogX.i("SCD CDNcb out ret=$ret")
                        }.onFailure { LogX.w("SCD CDNcb out err: ${it.javaClass.simpleName}") }
                    }
                }
            )
            if (set.isEmpty()) error("C2 not found (all overloads)")
            LogX.i("SCD hook SCD-CDNcb allMethods C2 count=${set.size}")
        }

        // ---- SCD-s0b modelimage/s0.b(r0)String @0x2b429c —— 返回 ImgInfo2.e（大图本地路径）----
        guard("SCD-s0b") {
            hookRobust(
                "SCD-s0b", s0cls, "b",
                sig("b", listOf("com.tencent.mm.modelimage.r0"), "java.lang.String"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching {
                            val path = (param.result as? String ?: "null").take(120)
                            LogX.i("SCD s0b ret path=$path")
                        }.onFailure { LogX.w("SCD s0b out err: ${it.javaClass.simpleName}") }
                    }
                }
            )
        }

        // ---- SCD-b1j6 modelimage/b1.j6(String,[B,I,J,Z,String,PString,PInt,PInt)J @0x2a74f8 ----
        // R78 §Q4.2：checkExist 成功分支用 j6 落新记录（不是 R5）。关键观测点 = PString.value 大图路径出参。
        guard("SCD-b1j6") {
            hookRobust(
                "SCD-b1j6", b1cls, "j6",
                sig(
                    "j6",
                    listOf(
                        "java.lang.String", "[B", "int", "long", "boolean", "java.lang.String",
                        "com.tencent.mm.pointers.PString",
                        "com.tencent.mm.pointers.PInt",
                        "com.tencent.mm.pointers.PInt",
                    ),
                    "long",
                ),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching {
                            val id = (param.result as? Long) ?: -1L
                            // PString.value：反射只读，单独 runCatching；读不到打 NA。
                            val path = runCatching {
                                XposedHelpers.getObjectField(param.args?.getOrNull(5), "value") as? String
                            }.getOrDefault("NA")?.take(120) ?: "NA"
                            LogX.i("SCD b1j6 ret newLocalId=$id path=$path")
                        }.onFailure { LogX.w("SCD b1j6 out err: ${it.javaClass.simpleName}") }
                    }
                }
            )
        }

        // ---- SCD-j2R modelimage/j2.R() @0x2ad864 —— 终局观察点：after 读 r0 的 localId/msgId 与路径 ----
        // R78 §Q4.2：r0.a : J = localId、r0.b : J = msgId；旧的 i/h 是整型状态、j/Q 非 J 型恒 NA，已删。
        guard("SCD-j2R") {
            hookRobust(
                "SCD-j2R", j2cls, "R",
                sig("R", emptyList(), "com.tencent.mm.modelimage.r0"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching {
                            val r0 = param.result
                            val la = longFieldStr(r0, "a")
                            val lb = longFieldStr(r0, "b")
                            LogX.i("SCD j2R after localIds=[a=$la b=$lb]")
                            val se = strFieldStr(r0, "e", 120)
                            val sf = strFieldStr(r0, "f", 120)
                            val sg = strFieldStr(r0, "g", 120)
                            LogX.i("SCD j2R after paths=[e=$se f=$sf g=$sg]")
                        }.onFailure { LogX.w("SCD j2R out err: ${it.javaClass.simpleName}") }
                    }
                }
            )
        }

        // ---- SCD-f2C2 modelimage/f2.C2(String,int,Ltj/e,Ltj/f,boolean)I @0x2aa888 ----
        // R79 §2.3：f2 是 Ltj/i 接口实现，C2 是"上传完成回调重建带 URL 消息"的唯一真机未观测点；
        // 直接 hookAllMethods 挂全 C2（签名漂移兜底），回调打 msig。只读：不写 param、不调宿主方法。
        guard("SCD-f2C2") {
            val set = XposedBridge.hookAllMethods(reqCls(f2cls, "SCD-f2C2 class"), "C2", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val msig = sigOf(param)
                        val a = param.args
                        val s0 = (a?.getOrNull(0) as? String ?: "null").take(200)
                        val i1 = a?.getOrNull(1) as? Int ?: -1
                        val b4 = a?.getOrNull(4) as? Boolean ?: false
                        LogX.i("SCD f2C2 in msig=$msig s0=$s0 i1=$i1 b4=$b4")
                        // Ltj/f 的 int / String 字段：重点 field_exist_whencheck / field_UploadHitCacheType，
                        // 以及带 url/hdlength/aeskey/CDN 的 String 字段（dump 全量，逐字段只读）。
                        LogX.i("SCD f2C2 in fIntFields=${dumpIntFields(a?.getOrNull(3), "f")}")
                        LogX.i("SCD f2C2 in fStrFields=${dumpStringFields(a?.getOrNull(3), "f")}")
                        val existWhenCheck = runCatching {
                            XposedHelpers.getObjectField(a?.getOrNull(3), "field_exist_whencheck")
                                ?.toString() ?: "null"
                        }.getOrDefault("NA")
                        val uploadHitCacheType = runCatching {
                            XposedHelpers.getObjectField(a?.getOrNull(3), "field_UploadHitCacheType")
                                ?.toString() ?: "null"
                        }.getOrDefault("NA")
                        LogX.i(
                            "SCD f2C2 in f field_exist_whencheck=$existWhenCheck " +
                                "field_UploadHitCacheType=$uploadHitCacheType"
                        )
                    }.onFailure { LogX.w("SCD f2C2 in err: ${it.javaClass.simpleName}") }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val ret = (param.result as? Int) ?: -1
                        LogX.i("SCD f2C2 out ret=$ret")
                        // 上传完成回调重建带 URL 消息的直接证据：只打是否命中 + 长度，不打完整 xml/key。
                        val hit = msgUrlHit(
                            listOf(param.thisObject, param.args?.getOrNull(2), param.args?.getOrNull(3)),
                            param.args?.getOrNull(0) as? String,
                        )
                        if (hit != null) {
                            LogX.i("SCD f2C2 MSG_HAS_URL hit=${hit.first} len=${hit.second}")
                        }
                    }.onFailure { LogX.w("SCD f2C2 out err: ${it.javaClass.simpleName}") }
                }
            })
            if (set.isEmpty()) error("C2 not found (all overloads)")
            LogX.i("SCD hook SCD-f2C2 allMethods C2 count=${set.size}")
        }

        // ---- SCD-B8 mq/k.B8(Ltj/k)Z @B6 0x01cc —— 服务端 checkExist 观测点 ----
        // R79 §1.1 步②：B8 入参 task(tj/k) 带 fileId/aesKey/filemd5，出参 boolean 即 checkExist 结果。
        guard("SCD-B8") {
            hookRobust(
                "SCD-B8", mqkcls, "B8",
                sig("B8", listOf("tj.k"), "boolean"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching {
                            val msig = sigOf(param)
                            LogX.i("SCD B8 in msig=$msig")
                            LogX.i("SCD B8 in taskFields=${dumpStringFields(param.args?.getOrNull(0), "task")}")
                        }.onFailure { LogX.w("SCD B8 in err: ${it.javaClass.simpleName}") }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching {
                            val ret = (param.result as? Boolean)?.toString() ?: "NA"
                            LogX.i("SCD B8 out ret=$ret")
                        }.onFailure { LogX.w("SCD B8 out err: ${it.javaClass.simpleName}") }
                    }
                }
            )
        }

        val ok = hookStatus.values.count { it }
        LogX.i("SCD installed ok=$ok fail=${hookStatus.size - ok} enabled=$enabled")
    }

    // ------------------------------------------------------------------ 开关 / 状态

    fun turn(on: Boolean) {
        enabled = on
        LogX.i("SCD enabled=$on")
    }

    /** 无 sub 时回显：enabled/installed/ok/fail + 每 hook 一行 `SCD hook <code> OK|FAIL ...`。 */
    fun status(): List<String> {
        val snap = synchronized(hookStatus) { hookStatus.toList() }
        val ok = snap.count { it.second }
        val lines = ArrayList<String>(snap.size + 2)
        lines.add("SCD STATUS enabled=$enabled installed=$installed ok=$ok fail=${snap.size - ok}")
        for ((code, success) in snap) {
            val label = hookLabels[code] ?: "?"
            lines.add("SCD hook $code ${if (success) "OK" else "FAIL"} $label")
        }
        if (snap.isEmpty()) lines.add("SCD hook <none> (install() 还没跑？)")
        return lines
    }

    // ------------------------------------------------------------------ 内部工具

    /** 只读找类；失败返回 null（由各 guard 的 reqCls 记 FAIL）。 */
    private fun findCls(name: String, cl: ClassLoader): Class<*>? =
        runCatching { XposedHelpers.findClass(name, cl) }
            .onFailure { LogX.w("SCD hook class $name FAIL ${it.javaClass.simpleName}") }
            .getOrNull()

    /** 构造"方法名 + 形参类名 + 返回类型"的精确选择器（不写死 dex code_off）。 */
    private fun sig(name: String, paramTypeNames: List<String>, returnTypeName: String?): (Method) -> Boolean =
        { m ->
            m.name == name &&
                m.parameterTypes.map { it.name } == paramTypeNames &&
                (returnTypeName == null || m.returnType.name == returnTypeName)
        }

    /**
     * 精确选择器命中则 hookMethod；否则 hookAllMethods(类, 方法名, cb) 全挂兜底 + 回调打 msig，
     * 签名漂移不硬失败；一个都挂不上才 error（由 guard 记 FAIL + WARN）。
     */
    private fun hookRobust(
        code: String,
        cls: Class<*>?,
        name: String,
        selector: (Method) -> Boolean,
        cb: XC_MethodHook,
    ) {
        val c = reqCls(cls, "$code class")
        val exact = c.declaredMethods.firstOrNull(selector)
        if (exact != null) {
            XposedBridge.hookMethod(exact, cb)
            LogX.i("SCD hook $code exact sig=${sigOfMethod(exact)}")
        } else {
            val set = XposedBridge.hookAllMethods(c, name, cb)
            if (set.isEmpty()) error("$name not found (all overloads)")
            LogX.i("SCD hook $code fallbackAll name=$name count=${set.size}")
        }
    }

    /** 回调里打印实际命中的 method sig（签名漂移时定位用），take(80)。 */
    private fun sigOf(p: XC_MethodHook.MethodHookParam): String =
        runCatching { p.method.toString().takeLast(80) }.getOrDefault("NA")

    private fun sigOfMethod(m: Method): String =
        runCatching { m.toString().takeLast(80) }.getOrDefault("NA")

    private fun reqCls(c: Class<*>?, what: String): Class<*> = c ?: error("$what class missing")

    /** tj/k 任务字段（只读）：fileId / md5 / onlycheckexist。 */
    private fun logTaskFields(tag: String, task: Any?) {
        val fid = runCatching {
            (XposedHelpers.getObjectField(task, "field_fileId") as? String)?.take(80) ?: "null"
        }.getOrDefault("NA")
        val md5 = runCatching {
            (XposedHelpers.getObjectField(task, "field_filemd5") as? String)?.take(80) ?: "null"
        }.getOrDefault("NA")
        val only = runCatching {
            XposedHelpers.getBooleanField(task, "field_onlycheckexist").toString()
        }.getOrDefault("NA")
        LogX.i("SCD $tag task fileId=$fid md5=$md5 onlycheckexist=$only")
    }

    /**
     * 只读遍历对象 String 字段（含父类，深度 ≤4），找路径样值：以 / 开头且含 image2 或以 .jpg/.png 结尾，
     * 命中打 `*PATH*`。每个字段读取单独 runCatching，取不到跳过；路径 take(120)。
     */
    private fun dumpStringFields(obj: Any?, label: String): String {
        if (obj == null) return "{$label=null}"
        val parts = ArrayList<String>()
        var c: Class<*>? = obj.javaClass
        var depth = 0
        while (c != null && c != Any::class.java && depth++ < 4) {
            for (f in c.declaredFields) {
                if (f.type != String::class.java) continue
                val v = runCatching {
                    f.isAccessible = true
                    f.get(obj) as? String
                }.getOrNull() ?: continue
                if (v.isEmpty()) continue
                val pathLike = v.startsWith("/") &&
                    (v.contains("image2/") || v.endsWith(".jpg") || v.endsWith(".png"))
                parts.add("${f.name}=${v.take(120)}${if (pathLike) PATH_MARK else ""}")
            }
            c = c.superclass
        }
        return "{$label=" + parts.joinToString(",") + "}"
    }

    /**
     * 只读遍历对象 int/long 字段（含父类，深度 ≤4），打印字段名=值；每个字段单独 runCatching，
     * 取不到跳过。最多 16 个字段防刷屏（R78 §Q4.2：dump Ltj/e 的 field_status/field_finishedLength 等）。
     */
    private fun dumpIntFields(obj: Any?, label: String): String {
        if (obj == null) return "{$label=null}"
        val parts = ArrayList<String>()
        var c: Class<*>? = obj.javaClass
        var depth = 0
        outer@ while (c != null && c != Any::class.java && depth++ < 4) {
            for (f in c.declaredFields) {
                if (f.type != java.lang.Integer.TYPE && f.type != java.lang.Long.TYPE) continue
                val v = runCatching {
                    f.isAccessible = true
                    f.get(obj)
                }.getOrNull() ?: continue
                parts.add("${f.name}=$v")
                if (parts.size >= 16) break@outer
            }
            c = c.superclass
        }
        return "{$label=" + parts.joinToString(",") + "}"
    }

    /** 只读：在入参数组（含其对象字段）与 this 字段里找指定类名的对象，找不到返回 null。 */
    private fun findObjectOfType(args: Array<Any?>?, thisObj: Any?, typeName: String): Any? {
        args?.forEach { o ->
            if (o == null) return@forEach
            val n = o.javaClass.name
            if (n == typeName || n.startsWith("$typeName$")) return o
            // 再扫一层入参对象的字段（如 t0 里挂的 r0）。
            var c: Class<*>? = o.javaClass
            var d = 0
            while (c != null && c != Any::class.java && d++ < 2) {
                for (f in c.declaredFields) {
                    if (f.type.name != typeName && !f.type.name.startsWith("$typeName$")) continue
                    val v = runCatching {
                        f.isAccessible = true
                        f.get(o)
                    }.getOrNull()
                    if (v != null) return v
                }
                c = c.superclass
            }
        }
        if (thisObj == null) return null
        var c: Class<*>? = thisObj.javaClass
        var depth = 0
        while (c != null && c != Any::class.java && depth++ < 4) {
            for (f in c.declaredFields) {
                if (f.type.name != typeName && !f.type.name.startsWith("$typeName$")) continue
                val v = runCatching {
                    f.isAccessible = true
                    f.get(thisObj)
                }.getOrNull()
                if (v != null) return v
            }
            c = c.superclass
        }
        return null
    }

    /**
     * R79 §2.3：在对象的 String 字段（含父类，深度 ≤4）与额外字符串里找"重建带 URL 消息"的证据键。
     * 只读：每个字段单独 runCatching，取不到跳过；只返回 (字段名 take(80), 值长度)，
     * 绝不返回/打印完整 xml 或 aeskey。命中键：`<msg>` / `cdnbigimgurl` / `aeskey`。
     */
    private fun msgUrlHit(objs: List<Any?>, raw: String?): Pair<String, Int>? {
        val keys = listOf("<msg>", "cdnbigimgurl", "aeskey")
        if (raw != null && keys.any { raw.contains(it, true) }) return "arg0" to raw.length
        for (obj in objs) {
            if (obj == null) continue
            var c: Class<*>? = obj.javaClass
            var depth = 0
            while (c != null && c != Any::class.java && depth++ < 4) {
                for (f in c.declaredFields) {
                    if (f.type != String::class.java) continue
                    val v = runCatching {
                        f.isAccessible = true
                        f.get(obj) as? String
                    }.getOrNull() ?: continue
                    if (keys.any { v.contains(it, true) }) return f.name.take(80) to v.length
                }
                c = c.superclass
            }
        }
        return null
    }

    private fun longFieldStr(obj: Any?, name: String): String =
        runCatching { XposedHelpers.getLongField(obj, name).toString() }.getOrDefault("NA")

    private fun strFieldStr(obj: Any?, name: String, limit: Int): String =
        runCatching { (XposedHelpers.getObjectField(obj, name) as? String)?.take(limit) ?: "null" }
            .getOrDefault("NA")

    /** 统一安装守卫：单钩失败只记 FAIL + 一条 WARN，绝不整体禁用。 */
    private fun guard(code: String, block: () -> Unit) {
        val ok = runCatching(block)
        hookStatus[code] = ok.isSuccess
        if (ok.isFailure) {
            val t = ok.exceptionOrNull()
            LogX.w("SCD hook $code FAIL ${t?.javaClass?.simpleName}: ${t?.message?.take(80)}")
        }
    }
}
