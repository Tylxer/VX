package com.lx.wxgroup.probe

import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import java.util.LinkedHashMap

/**
 * R73 · 真机观察「微X图片转发为何清晰」的**只读探针**（前缀 = `MIO`，tag 沿用 [LogX] 的 `WXGroup`）。
 *
 * 目的：坐实微X图片转发拿到清晰大图的机制——是否触发原生 CDN check/下载副作用、
 * `j2.O` before 读到 cdninfo（`r0.u`）是否非空、路径从哪来。回答方案
 * `docs/_r73_microx_img_observe.md` 的 Q1/Q2/Q3，验证我方 P3 方向。本探针**不改一行宿主代码**。
 *
 * ## 🔴 只读纪律（最高优先级，改动本文件前先读；方案 §8.1）
 *
 * 1. 所有 `before/after` **只打日志**：绝不写 `param.args[...]`、绝不写 `param.result`。
 * 2. **绝不主动调用任何宿主业务方法**。唯一例外是纯 getter / 反射读字段（[LogX] 之外只调
 *    `XposedHelpers.getObjectField/getBooleanField/getIntField/getLongField`、`q0.getType()`），
 *    且**单独 runCatching**，拿不到打 NA。**严禁**在回调里调 `b1.R5/j6/o2/z3/Y2`、`mq/k.B8`、
 *    `qf4/j0.C2`、`q1.a/b`、`l1.a` 替宿主做事——那会引入下载/DB/发送副作用，破坏只读前提。
 * 3. `enabled=false` 时回调第一行就 return（零反射、零字符串拼接）。hook 常驻安装，靠广播
 *    `op=microimg sub=on|off` 控制日志量（默认 false，冷启静默）。
 * 4. 每个 hook 的安装用 [guard] 包住，单钩失败只记 FAIL + 一条 WARN，**不崩微信、不影响其它 hook**；
 *    回调体同样全包 runCatching。
 * 5. 日志裁剪：String `take(80)`、路径 `take(120)`；`byte[]` 只打 `.size` 不打内容；不刷屏。
 * 6. 幂等 [installed] 保证只装一次；[status] 回显供 F2 前置校验。
 *
 * ## 开关
 *
 * hook 常驻安装，靠 [enabled] 控制日志量（默认 false，冷启静默）。广播
 * `op=microimg sub=on|off`（无 sub=status 回显状态）即时开关——由 EntryReceiver 接线 lane 接入。
 *
 * 字段签名 / descriptor / code_off 全部照方案 §2.1 的 dex 原文，UNKNOWN 与核对法见方案 §8.3：
 * U1(R5.s0 语义)/U2(C2 双同签名 hookAllMethods)/U3(m.a~f 字段)/U4(PString.value)/U5(q0 双 5 参重载)/
 * U6(l1 a()Z vs a()Lk1;)/U7(m.<init> OPTIONAL 不挂)/U8(多目标并发)/U9(r0.u READ_FAIL ≠ EMPTY)。
 *
 * 安装范式照抄 `probe/ImageNetSceneProbe.kt:40-217`。
 */
object MicroXImageObserveProbe {

    /** 只有 enabled 时回调才打日志；hook 本身常驻。广播可即时翻转。 */
    @Volatile
    var enabled = false

    /** 幂等：install 只跑一次。 */
    @Volatile
    private var installed = false

    /** 每个落点的安装结果（key = hook code D1..F3），供 `op=microimg`（无 sub=status）回显。 */
    private val hookStatus: MutableMap<String, Boolean> =
        Collections.synchronizedMap(LinkedHashMap<String, Boolean>())

    /** hook code → 人读标签，仅用于 status() 行的 `...` 部分，不参与判据。 */
    private val hookLabels = mapOf(
        "D1" to "b1.R5",
        "D2" to "b1.j6",
        "D3" to "mq/k.B8",
        "D4" to "qf4/j0.C2",
        "D5" to "j2.O",
        "E1" to "hl0.q0.<init>",
        "E2" to "hl0.l1.a()Z",
        "E3" to "hl0.q1.a",
        "E4" to "hl0.q1.b",
        "E5" to "hl0.q1.f",
        "F1" to "b1.C4",
        "F2" to "b1.z3",
        "F3" to "qf4.o4.O8",
    )

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
        val cl = lpparam.classLoader

        // 类先动态找（不写死 dex code_off）。失败只记 FAIL，其它 hook 不受影响。
        val b1cls = findCls("com.tencent.mm.modelimage.b1", cl)      // D1 D2 F1 F2
        val j2cls = findCls("com.tencent.mm.modelimage.j2", cl)       // D5
        val b8cls = findCls("mq.k", cl)                               // D3
        val c2cls = findCls("qf4.j0", cl)                             // D4
        val q0cls = findCls("hl0.q0", cl)                             // E1
        val l1cls = findCls("hl0.l1", cl)                             // E2
        val q1cls = findCls("hl0.q1", cl)                             // E3 E4 E5
        val o8cls = findCls("qf4.o4", cl)                             // F3

        // ---- D1 b1.R5 (String,String,int,int,int,PString,PInt,PInt)J @2a710c ----
        guard("D1") {
            val c = reqCls(b1cls, "b1")
            val m = req(
                findMethod(
                    c, "R5",
                    listOf(
                        "java.lang.String", "java.lang.String", "int", "int", "int",
                        "com.tencent.mm.pointers.PString",
                        "com.tencent.mm.pointers.PInt",
                        "com.tencent.mm.pointers.PInt",
                    ),
                    "long",
                ), "R5"
            )
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val a = param.args
                        val s0 = (a?.getOrNull(0) as? String ?: "null").take(80)
                        val s1 = (a?.getOrNull(1) as? String ?: "null").take(80)
                        val i2 = a?.getOrNull(2) as? Int ?: -1
                        val i3 = a?.getOrNull(3) as? Int ?: -1
                        val i4 = a?.getOrNull(4) as? Int ?: -1
                        // U1: s0 语义 UNKNOWN，只按原样打，判据不依赖它。
                        LogX.i("MIO R5 in s0=$s0 s1=$s1 i2=$i2 i3=$i3 i4=$i4")
                    }.onFailure { LogX.w("MIO R5 in err: ${it.javaClass.simpleName}") }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val id = (param.result as? Long) ?: -1L
                        LogX.i("MIO R5 out imgLocalId=$id")
                        // 加分项（只读宿主已写入的输出指针，U4：取不到打 NA）。
                        val a = param.args
                        val path = ptrStr(a?.getOrNull(5), 120)
                        val w = ptrStr(a?.getOrNull(6), 40)
                        val h = ptrStr(a?.getOrNull(7), 40)
                        LogX.i("MIO R5 outPtr path=$path w=$w h=$h")
                    }.onFailure { LogX.w("MIO R5 out err: ${it.javaClass.simpleName}") }
                }
            })
        }

        // ---- D2 b1.j6 (String,byte[],int,long,boolean,String,PString,PInt,PInt)J @2a74f8 ----
        guard("D2") {
            val c = reqCls(b1cls, "b1")
            val m = req(
                findMethod(
                    c, "j6",
                    listOf(
                        "java.lang.String", "[B", "int", "long", "boolean", "java.lang.String",
                        "com.tencent.mm.pointers.PString",
                        "com.tencent.mm.pointers.PInt",
                        "com.tencent.mm.pointers.PInt",
                    ),
                    "long",
                ), "j6"
            )
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val a = param.args
                        val s0 = (a?.getOrNull(0) as? String ?: "null").take(80)
                        // byte[] 只打 size，严禁打内容。
                        val dataLen = (a?.getOrNull(1) as? ByteArray)?.size ?: -1
                        val i2 = a?.getOrNull(2) as? Int ?: -1
                        val svrId = a?.getOrNull(3) as? Long ?: -1L
                        val z4 = a?.getOrNull(4) as? Boolean ?: false
                        val s5 = (a?.getOrNull(5) as? String ?: "null").take(80)
                        LogX.i("MIO j6 in s0=$s0 dataLen=$dataLen i2=$i2 svrId=$svrId z4=$z4 s5=$s5")
                    }.onFailure { LogX.w("MIO j6 in err: ${it.javaClass.simpleName}") }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val id = (param.result as? Long) ?: -1L
                        LogX.i("MIO j6 out imgLocalId=$id")
                        val a = param.args
                        val path = ptrStr(a?.getOrNull(6), 120)
                        val w = ptrStr(a?.getOrNull(7), 40)
                        val h = ptrStr(a?.getOrNull(8), 40)
                        LogX.i("MIO j6 outPtr path=$path w=$w h=$h")
                    }.onFailure { LogX.w("MIO j6 out err: ${it.javaClass.simpleName}") }
                }
            })
        }

        // ---- D3 mq/k.B8 (Ltj/k;)Z @78f618 ----
        guard("D3") {
            val c = reqCls(b8cls, "mq/k")
            val m = req(findMethod(c, "B8", listOf("tj.k"), "boolean"), "B8")
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val o = param.args?.getOrNull(0)
                        val only = boolFieldStr(o, "field_onlycheckexist")
                        val trySafe = boolFieldStr(o, "field_trysafecdn")
                        LogX.i("MIO B8 in onlycheckexist=$only trysafecdn=$trySafe")
                    }.onFailure { LogX.w("MIO B8 in err: ${it.javaClass.simpleName}") }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val ret = (param.result as? Boolean)?.toString() ?: "NA"
                        // ⚠️ B8 返回 true 只表任务投递成功，≠ CDN 命中（命中看 D4）。
                        LogX.i("MIO B8 out ret=$ret")
                    }.onFailure { LogX.w("MIO B8 out err: ${it.javaClass.simpleName}") }
                }
            })
        }

        // ---- D4 qf4/j0.C2 (String,int,tj/e,tj/f,boolean)I @84307c ----
        // U2: 两个同签名实现（904-unit 84307c / 63-unit 短实现）→ hookAllMethods 全挂，
        // before 额外打 param.method.toString().takeLast(80)；判据以 existWhenCheck + D1/D2 为准。
        guard("D4") {
            val c = reqCls(c2cls, "qf4/j0")
            val set = XposedBridge.hookAllMethods(c, "C2", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val msig = runCatching { param.method.toString().takeLast(80) }.getOrDefault("NA")
                        LogX.i("MIO C2 method=$msig")
                        val a = param.args
                        val exist = boolFieldStr(a?.getOrNull(3), "field_exist_whencheck")
                        val s0 = (a?.getOrNull(0) as? String ?: "null").take(80)
                        val i1 = a?.getOrNull(1) as? Int ?: -1
                        val z4 = a?.getOrNull(4) as? Boolean ?: false
                        LogX.i("MIO C2 in existWhenCheck=$exist s0=$s0 i1=$i1 z4=$z4")
                    }.onFailure { LogX.w("MIO C2 in err: ${it.javaClass.simpleName}") }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val ret = (param.result as? Int)?.toString() ?: "NA"
                        LogX.i("MIO C2 out ret=$ret")
                    }.onFailure { LogX.w("MIO C2 out err: ${it.javaClass.simpleName}") }
                }
            })
            if (set.isEmpty()) error("C2 not found")
        }

        // ---- D5 j2.O (Lcom/tencent/mm/modelimage/r0;I)Z @2acc44 —— 终局判据 ----
        guard("D5") {
            val c = reqCls(j2cls, "j2")
            val m = req(
                findMethod(c, "O", listOf("com.tencent.mm.modelimage.r0", "int"), "boolean"),
                "O"
            )
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val r0 = param.args?.getOrNull(0)
                        // U9: 读到 null → EMPTY（进判据）；读字段抛异常 → READ_FAIL（归需补验，不进空值判据）。
                        val read = runCatching { XposedHelpers.getObjectField(r0, "u") as? String }
                        val uVal = when {
                            read.isFailure -> {
                                LogX.w("MIO j2O r0.u READ_FAIL ${read.exceptionOrNull()?.javaClass?.simpleName}")
                                "READ_FAIL"
                            }
                            read.getOrNull() == null -> "EMPTY"
                            else -> read.getOrNull()!!.take(120)
                        }
                        LogX.i("MIO j2O in r0.u=$uVal")
                    }.onFailure { LogX.w("MIO j2O in err: ${it.javaClass.simpleName}") }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        // ret 仅参考，不作判据：分支方向以 before 的 r0.u 为准。
                        val ret = (param.result as? Boolean)?.toString() ?: "NA"
                        LogX.i("MIO j2O out ret=$ret")
                    }.onFailure { LogX.w("MIO j2O out err: ${it.javaClass.simpleName}") }
                }
            })
        }

        // ---- E1 hl0.q0.<init> (String,String,int,int,long)V @711560 ----
        // U5: 两个 5 参重载 (String,String,IIJ)@711560 与 (String,String,IIObject)@711010 →
        // hookAllConstructors + 第 5 参 is Long 精确过滤（并校验构造器第 5 参声明类型 == long）。
        guard("E1") {
            val c = reqCls(q0cls, "hl0/q0")
            val set = XposedBridge.hookAllConstructors(c, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    if (!isLongCtor(param)) return
                    runCatching {
                        val fid = param.args?.getOrNull(4) as? Long ?: -1L
                        LogX.i("MIO q0Ctor in th=${Thread.currentThread().name} forwardMsgId=$fid")
                    }.onFailure { LogX.w("MIO q0Ctor in err: ${it.javaClass.simpleName}") }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    if (!isLongCtor(param)) return
                    runCatching {
                        // getType() 纯 getter，R72 已证安全；单独 runCatching，拿不到打 NA。
                        val t = runCatching { XposedHelpers.callMethod(param.thisObject, "getType") }
                            .getOrDefault("NA")
                        LogX.i("MIO q0Ctor out type=$t")
                    }.onFailure { LogX.w("MIO q0Ctor out err: ${it.javaClass.simpleName}") }
                }
            })
            if (set.isEmpty()) error("q0 ctor not found")
        }

        // ---- E2 hl0.l1.a()Z @710360 ----
        // U6: 同名 getter a()Lk1; 不是它 → 按 returnType==Boolean.TYPE && 无参精确选。
        guard("E2") {
            val c = reqCls(l1cls, "hl0/l1")
            val m = req(
                c.declaredMethods.firstOrNull {
                    it.name == "a" && it.returnType == java.lang.Boolean.TYPE &&
                        it.parameterTypes.isEmpty()
                }, "l1.a()Z"
            )
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        LogX.i("MIO l1.a queued=true th=${Thread.currentThread().name}")
                    }.onFailure { LogX.w("MIO l1.a err: ${it.javaClass.simpleName}") }
                }
            })
        }

        // ---- E3 hl0.q1.a()Lhl0/l1; @712168 ----
        guard("E3") {
            val c = reqCls(q1cls, "hl0/q1")
            val m = req(findMethod(c, "a", emptyList(), "hl0.l1"), "q1.a()Lhl0/l1;")
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val sub = intFieldStr(param.thisObject, "h")
                        LogX.i("MIO q1.a subtype=$sub via=a")
                    }.onFailure { LogX.w("MIO q1.a err: ${it.javaClass.simpleName}") }
                }
            })
        }

        // ---- E4 hl0.q1.b()V @712388 ----
        guard("E4") {
            val c = reqCls(q1cls, "hl0/q1")
            val m = req(findMethod(c, "b", emptyList(), "void"), "q1.b()V")
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val sub = intFieldStr(param.thisObject, "h")
                        LogX.i("MIO q1.b subtype=$sub via=b")
                    }.onFailure { LogX.w("MIO q1.b err: ${it.javaClass.simpleName}") }
                }
            })
        }

        // ---- E5 hl0.q1.f(Lhl0/m;)Lhl0/q1; @7122c4 ----
        // U3: m.a~f 混淆名可能随版本变 → 取不到打 NA（零日志不反推 m.a=-1）。
        guard("E5") {
            val c = reqCls(q1cls, "hl0/q1")
            val m = req(findMethod(c, "f", listOf("hl0.m"), "hl0.q1"), "q1.f(Lhl0/m;)")
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val mo = param.args?.getOrNull(0)
                        val a = longFieldStr(mo, "a")
                        val b = longFieldStr(mo, "b")
                        val cs = objFieldStr(mo, "c")
                        val ds = objFieldStr(mo, "d")
                        val es = objFieldStr(mo, "e")
                        val fs = objFieldStr(mo, "f")
                        LogX.i("MIO q1.f m[a=$a b=$b c=$cs d=$ds e=$es f=$fs]")
                    }.onFailure { LogX.w("MIO q1.f err: ${it.javaClass.simpleName}") }
                }
            })
        }

        // ---- F1 b1.C4 (String,boolean)String @2a563c ----
        guard("F1") {
            val c = reqCls(b1cls, "b1")
            val m = req(
                findMethod(c, "C4", listOf("java.lang.String", "boolean"), "java.lang.String"),
                "C4"
            )
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val uri = (param.args?.getOrNull(0) as? String ?: "null").take(120)
                        val z = param.args?.getOrNull(1) as? Boolean ?: false
                        LogX.i("MIO C4 in uri=$uri z=$z")
                    }.onFailure { LogX.w("MIO C4 in err: ${it.javaClass.simpleName}") }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val path = (param.result as? String ?: "null").take(120)
                        LogX.i("MIO C4 out path=$path")
                    }.onFailure { LogX.w("MIO C4 out err: ${it.javaClass.simpleName}") }
                }
            })
        }

        // ---- F2 b1.z3 (String,String,String,boolean)String @2a6ae4 ----
        // ⚠️ z3 内部 Y2 会触发 DB 只读查询；hook 只打印，绝不主动调用 z3/Y2。
        guard("F2") {
            val c = reqCls(b1cls, "b1")
            val m = req(
                findMethod(
                    c, "z3",
                    listOf("java.lang.String", "java.lang.String", "java.lang.String", "boolean"),
                    "java.lang.String",
                ), "z3"
            )
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val a = param.args
                        val s0 = (a?.getOrNull(0) as? String ?: "null").take(120)
                        val p1 = (a?.getOrNull(1) as? String ?: "null").take(80)
                        val p2 = (a?.getOrNull(2) as? String ?: "null").take(80)
                        val z3 = a?.getOrNull(3) as? Boolean ?: false
                        LogX.i("MIO z3 in s0=$s0 prefix=$p1 suffix=$p2 isThumb=$z3")
                    }.onFailure { LogX.w("MIO z3 in err: ${it.javaClass.simpleName}") }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val path = (param.result as? String ?: "null").take(120)
                        LogX.i("MIO z3 out path=$path")
                    }.onFailure { LogX.w("MIO z3 out err: ${it.javaClass.simpleName}") }
                }
            })
        }

        // ---- F3 qf4/o4.O8 (Context,String,String,int,String,String,hl0/m,ScanCodeInfo)V @845720 ----
        guard("F3") {
            val c = reqCls(o8cls, "qf4/o4")
            val m = req(
                findMethod(
                    c, "O8",
                    listOf(
                        "android.content.Context", "java.lang.String", "java.lang.String", "int",
                        "java.lang.String", "java.lang.String", "hl0.m",
                        "com.tencent.mm.modelscan.ScanCodeInfo",
                    ),
                    "void",
                ), "O8"
            )
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val a = param.args
                        // 第 5 参 = args[4] = localPath（0-based）。
                        val lp = (a?.getOrNull(4) as? String ?: "null").take(120)
                        val s5 = (a?.getOrNull(5) as? String ?: "null").take(80)
                        LogX.i("MIO O8 in localPath=$lp s5=$s5")
                    }.onFailure { LogX.w("MIO O8 in err: ${it.javaClass.simpleName}") }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        // void 返回，打一行确认正常结束（非异常）。
                        LogX.i("MIO O8 out")
                    }.onFailure { LogX.w("MIO O8 out err: ${it.javaClass.simpleName}") }
                }
            })
        }

        val ok = hookStatus.values.count { it }
        LogX.i("MIO installed ok=$ok fail=${hookStatus.size - ok} enabled=$enabled")
    }

    // ------------------------------------------------------------------ 开关 / 状态

    fun turn(on: Boolean) {
        enabled = on
        LogX.i("MIO enabled=$on")
    }

    /** 无 sub 时回显：enabled/installed/ok/fail + 每 hook 一行 `MIO hook <code> OK|FAIL ...`。 */
    fun status(): List<String> {
        val snap = synchronized(hookStatus) { hookStatus.toList() }
        val ok = snap.count { it.second }
        val lines = ArrayList<String>(snap.size + 2)
        lines.add("MIO STATUS enabled=$enabled installed=$installed ok=$ok fail=${snap.size - ok}")
        for ((code, success) in snap) {
            val label = hookLabels[code] ?: "?"
            lines.add("MIO hook $code ${if (success) "OK" else "FAIL"} $label")
        }
        if (snap.isEmpty()) lines.add("MIO hook <none> (install() 还没跑？)")
        return lines
    }

    // ------------------------------------------------------------------ 内部工具

    /** 只读找类；失败返回 null（由各 guard 的 reqCls 记 FAIL）。 */
    private fun findCls(name: String, cl: ClassLoader): Class<*>? =
        runCatching { XposedHelpers.findClass(name, cl) }
            .onFailure { LogX.w("MIO hook class $name FAIL ${it.javaClass.simpleName}") }
            .getOrNull()

    /** 按 descriptor 的形参类名 + 返回类型精确找方法（不写死 dex code_off）。 */
    private fun findMethod(
        cls: Class<*>,
        name: String,
        paramTypeNames: List<String>,
        returnTypeName: String? = null,
    ): Method? = cls.declaredMethods.firstOrNull { m ->
        m.name == name &&
            m.parameterTypes.map { it.name } == paramTypeNames &&
            (returnTypeName == null || m.returnType.name == returnTypeName)
    }

    private fun req(m: Method?, what: String): Method = m ?: error("$what not found")

    private fun reqCls(c: Class<*>?, what: String): Class<*> = c ?: error("$what class missing")

    /** U5: q0 构造器第 5 参声明类型 == long 且实参 is Long，才算目标 @711560 重载。 */
    private fun isLongCtor(p: XC_MethodHook.MethodHookParam): Boolean {
        val declaredOk = runCatching {
            val ts = when (val m = p.method) {
                is Constructor<*> -> m.parameterTypes
                is Method -> m.parameterTypes
                else -> null
            }
            ts?.size == 5 && ts[4] == java.lang.Long.TYPE
        }.getOrDefault(false)
        return declaredOk && (p.args?.getOrNull(4) is Long)
    }

    private fun boolFieldStr(obj: Any?, name: String): String = runCatching {
        XposedHelpers.getBooleanField(obj, name).toString()
    }.getOrDefault("NA")

    private fun intFieldStr(obj: Any?, name: String): String = runCatching {
        XposedHelpers.getIntField(obj, name).toString()
    }.getOrDefault("NA")

    private fun longFieldStr(obj: Any?, name: String): String = runCatching {
        XposedHelpers.getLongField(obj, name).toString()
    }.getOrDefault("NA")

    private fun objFieldStr(obj: Any?, name: String): String = runCatching {
        (XposedHelpers.getObjectField(obj, name)?.toString() ?: "null").take(40)
    }.getOrDefault("NA")

    /** PString/PInt.value（U4，标准字段名 value），取不到打 NA。 */
    private fun ptrStr(ptr: Any?, limit: Int): String = runCatching {
        (XposedHelpers.getObjectField(ptr, "value")?.toString() ?: "null").take(limit)
    }.getOrDefault("NA")

    /** 统一安装守卫：单钩失败只记 FAIL + 一条 WARN，绝不整体禁用。 */
    private fun guard(code: String, block: () -> Unit) {
        val ok = runCatching(block)
        hookStatus[code] = ok.isSuccess
        if (ok.isFailure) {
            val t = ok.exceptionOrNull()
            LogX.w("MIO hook $code FAIL ${t?.javaClass?.simpleName}: ${t?.message?.take(80)}")
        }
    }
}
