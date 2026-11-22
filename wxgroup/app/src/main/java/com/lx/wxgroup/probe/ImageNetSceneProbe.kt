package com.lx.wxgroup.probe

import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.LinkedHashMap

/**
 * R73 · 只读取证探针 `ImageNetSceneProbe`（tag = `INP`）。
 *
 * 目的：落锤「`FORWARD resend-file ok=true` 但对端没收到图片、本机提示发送失败」——
 * 图片真正的发送体是 `com.tencent.mm.modelimage.j2`（NetSceneUploadMsgImg, getType()=110），
 * 入队 `l1.a()=true` 之后的异步结果落在 `j2.onGYNetEnd`，本探针抓它的 3 个 int 形参 +
 * errMsg，以及 j2 构造器里的 `ori` 实际路径（本次疑点：`th_<md5>hd` 无扩展名 HD 缩略图
 * 被当原图上传）。
 *
 * ## 🔴 只读纪律（最高优先级，改动本文件时先读这段）
 *
 * 1. 所有 `before/after` **只打日志**：绝不写 `param.args[...]`、绝不写 `param.result`。
 * 2. **绝不主动调用任何宿主业务方法**。唯一例外：`getType()` —— 只读反射、单独 runCatching
 *    包住，拿不到就跳过，绝不影响主日志。R73 补打的 `m.a` / `q1.B` 同样是**只读字段读取**
 *    （getLongField / getObjectField），不调用宿主方法、不写任何字段。
 * 3. 不写 Kv、不持久化、不下载、不改消息表、不模拟 UI。冷启恢复静默是期望行为。
 * 4. 每个 hook 的安装用 `runCatching` 包住，单钩失败只记 FAIL + 一条 WARN，
 *    不崩微信、不整体禁用其它 hook。
 * 5. `enabled=false` 时回调第一行就 return（零反射零字符串拼接）。
 *    日志只打路径尾段 take(120) / errMsg take(60) / String 参数 take(80)，别刷屏。
 *
 * ## 开关
 *
 * hook 常驻安装，靠 [enabled] 控制日志量（默认 false，冷启静默）。广播
 * `op=inp sub=on|off`（无 sub=status）即时开关——详见 EntryReceiver 分支注释。
 *
 * 锚点来源：`docs/_r73_image_send_fail_forensics.md` §1.2 / §2.2 / §5.2；hook 范式照抄
 * `probe/HostCacheRefreshProbe.kt` 与 `core/MomentsSyncTrigger.kt:310-368`。
 */
object ImageNetSceneProbe {

    /** 只有 enabled 时回调才打日志；hook 本身常驻。广播可即时翻转。 */
    @Volatile
    var enabled = false

    /** 幂等：install 只跑一次。 */
    @Volatile
    private var installed = false

    /** 每个落点的安装结果，供 `op=inp`（无 sub）回显"哪些挂上了"。 */
    private val hookStatus: MutableMap<String, Boolean> =
        Collections.synchronizedMap(LinkedHashMap<String, Boolean>())

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
        val cl = lpparam.classLoader

        // j2 类先动态找（不写死 dex code_off）。失败只打一条 WARN，不整体禁用：
        // H1/H2 各自 guard 会记 FAIL，但其它 hook / 生产链不受影响。
        val j2cls: Class<*>? = runCatching {
            XposedHelpers.findClass("com.tencent.mm.modelimage.j2", cl)
        }.onFailure {
            LogX.w("INP hook j2-class FAIL ${it.javaClass.simpleName}")
        }.getOrNull()

        // H1 — j2.onGYNetEnd:(IIILjava/lang/String;Lcom/tencent/mm/network/t0;[B)V
        // 照抄 MomentsSyncTrigger:314-319：扫描 declaredMethods 找 6 参且前 3 参均 int 的方法，
        // 不预设哪个是 errType（j2 自身日志把第 2/3 个标 errtype/errcode，但混淆形参序号可能错位，
        // 故 3 个 int 全打）。
        guard("H1-onGYNetEnd") {
            if (j2cls == null) error("j2-class missing")
            val m = j2cls.declaredMethods.firstOrNull {
                it.name == "onGYNetEnd" && it.parameterTypes.size == 6 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            } ?: error("onGYNetEnd not found")
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val a = param.args
                        val a0 = a?.getOrNull(0) as? Int ?: -1
                        val a1 = a?.getOrNull(1) as? Int ?: -1
                        val a2 = a?.getOrNull(2) as? Int ?: -1
                        val msg = a?.getOrNull(3) as? String ?: ""
                        LogX.i("INP onGYNetEnd a0=$a0 a1=$a1 a2=$a2 errMsg='${msg.take(60)}'")
                        // getType() 只读反射：单独 runCatching，拿不到就省略，绝不影响主日志。
                        runCatching {
                            val t = XposedHelpers.callMethod(param.thisObject, "getType")
                            LogX.i("INP onGYNetEnd getType=$t")
                        }
                    }.onFailure { LogX.w("INP onGYNetEnd err: ${it.javaClass.simpleName}") }
                }
            })
        }

        // H2 — j2 构造器 ori（可选但强烈建议）。
        // 19 参构造器里 ori 是第 4 个 String 类参数（j2 日志串
        // "dkupimg init uploadsrc:%d from:%s to:%s ori:%s ... cdn:%s thumb:%s" 的顺序），
        // 但为稳妥：所有 String 参数按 index 全打（主会话自己认哪个是 ori），
        // 并额外按启发式（以 / 开头或含 image2/）挑一行 ori 方便对照。
        guard("H2-j2init") {
            if (j2cls == null) error("j2-class missing")
            val set = XposedBridge.hookAllConstructors(j2cls, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val a = param.args ?: return
                        var ori: String? = null
                        for (i in a.indices) {
                            val s = a[i] as? String ?: continue
                            LogX.i("INP j2init str[$i]=${s.take(80)}")
                            if (ori == null && (s.startsWith("/") || s.contains("image2/"))) {
                                ori = s
                            }
                        }
                        ori?.let { LogX.i("INP j2init ori=${it.take(120)} cdn=<?> thumb=<?> oriTail=${it.takeLast(48)}") }

                        // R73 决定性修复坐实（Q1-R）：补打 m.a 与 q1.B —— 判别"重传本地 vs 服务端引用"的唯一标准。
                        // j2 构造器 19 参里含 hl0/m（来自 q1.t，即 q1.f(m) 挂的转发信息）；构造器 before
                        // 时 thisObject 字段多为默认值，故 param.args 优先、再退回 thisObject 字段类型扫描。
                        // 全 runCatching、只读、取不到打 NA，绝不影响上面已有的 str[i]/ori 日志。
                        val ma = runCatching {
                            val m = findObjectOfType(a, param.thisObject, "hl0.m")
                            if (m != null) XposedHelpers.getLongField(m, "a") else null
                        }.getOrNull()
                        LogX.i("INP j2init m.a=${ma ?: "NA"}")

                        val qb = runCatching {
                            val q1 = findObjectOfType(a, param.thisObject, "hl0.q1")
                            if (q1 != null) {
                                val b = XposedHelpers.getObjectField(q1, "B")
                                (b as? Enum<*>)?.name
                                    ?: b?.javaClass?.simpleName
                            } else null
                        }.getOrNull()
                        LogX.i("INP j2init q1.B=${qb ?: "NA"}")
                    }.onFailure { LogX.w("INP j2init err: ${it.javaClass.simpleName}") }
                }
            })
            if (set.isEmpty()) error("no constructor")
        }

        val ok = hookStatus.values.count { it }
        LogX.i("INP installed ok=$ok fail=${hookStatus.size - ok} enabled=$enabled")
    }

    // ------------------------------------------------------------------ 开关 / 状态

    fun turn(on: Boolean) {
        enabled = on
        LogX.i("INP enabled=$on")
    }

    /** 无 sub 时回显：enabled + 挂上的 hook 名清单 + 各 hook 成功/失败。 */
    fun status(): List<String> {
        val snap = synchronized(hookStatus) { hookStatus.toList() }
        val ok = snap.count { it.second }
        val hooked = snap.filter { it.second }.map { it.first }
        val lines = ArrayList<String>(snap.size + 2)
        lines.add(
            "INP enabled=$enabled installed=$installed hooks=$hooked " +
                "ok=$ok fail=${snap.size - ok}"
        )
        for ((name, success) in snap) {
            lines.add("INP hook $name ${if (success) "OK" else "FAIL"}")
        }
        if (snap.isEmpty()) lines.add("INP hook <none> (install() 还没跑？)")
        return lines
    }

    // ------------------------------------------------------------------ 内部工具

    /**
     * 只读：在构造器入参数组与实例字段（含父类，深度 ≤4）里找指定类名的对象。
     *
     * 构造器 `before` 时 `thisObject` 字段多为默认值（null/0），因此**入参优先** ——
     * `hl0/m` 是 j2 19 参构造器里的转发信息对象（来自 q1.t），一定在 `param.args` 里；
     * `hl0/q1` 本身可能不在入参，退而扫描 j2 字段（Q1-R 记载字段名为 `N` 之类，混淆名不可靠，
     * 故按"字段类型 == hl0.q1"扫描，不写死字段名）。两路都找不到返回 null ⇒ 调用方打 NA。
     */
    private fun findObjectOfType(args: Array<Any?>?, thisObj: Any?, typeName: String): Any? {
        args?.forEach { o ->
            if (o == null) return@forEach
            val n = o.javaClass.name
            if (n == typeName || n.startsWith("$typeName$")) return o
        }
        if (thisObj == null) return null
        var c: Class<*>? = thisObj.javaClass
        var depth = 0
        while (c != null && c != Any::class.java && depth++ < 4) {
            for (f in c.declaredFields) {
                if (f.type.name != typeName) continue
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

    /** 统一安装守卫：单钩失败只记 FAIL + 一条 WARN，绝不整体禁用。 */
    private fun guard(name: String, block: () -> Unit) {
        val ok = runCatching(block)
        hookStatus[name] = ok.isSuccess
        if (ok.isFailure) {
            val t = ok.exceptionOrNull()
            LogX.w("INP hook $name FAIL ${t?.javaClass?.simpleName}: ${t?.message?.take(80)}")
        }
    }
}
