package com.lx.wxgroup.probe

import android.database.Cursor
import android.widget.ListAdapter
import com.lx.wxgroup.core.ConvReader
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.LinkedHashMap

/**
 * R66 · 统一只读取证探针 `HostCacheRefreshProbe`（tag = `HCR`）。
 *
 * 目的：一次装包同时观测两件事——
 *  (A) 微X 建组时微信内部发生了什么（通知 / 游标 / SQL / 数据源）；
 *  (B) 我方 `wxgrp_` 行的通知链路会不会被 `a94.e.P(username)` 过滤门 pass 掉。
 *
 * ## 🔴 只读纪律（最高优先级，改动本文件时先读这段）
 *
 * 1. 所有 `before/after` **只打日志**：绝不写 `param.args[...]`、绝不写 `param.result`。
 * 2. **绝不主动调用任何宿主方法**（尤其 `s4.b` / `p3.f` / `t0.N`）。
 *    ⇒ H4 的 `pass=` **不是**通过主动调 `a94.e.P(user)` 取得的：本探针改用**被动 hook**
 *    `a94.e.P` 的实现类（H4b），在 `HCR P` 行里给出真实返回值；`HCR i3.s` 行只打
 *    `pass=?`，并把 `HCR P` 行作为过滤门的证据。这是为了严守「只观测不修复」的约束，
 *    与 R65 §3 P4 字面要求的唯一一处有意偏离，已回报 team-lead。
 * 3. 不提供任何「真调」入口（没有 sub=notify / sub=call 之类分支）。
 * 4. 每个 hook 的安装用 `runCatching` 包住，混淆名在当前版本找不到只打一条
 *    `HCR hook <name> FAIL`，**不整体禁用**、不崩微信。
 *
 * ## 开关
 *
 * hook 常驻安装，靠 [enabled] 控制日志量；H5/H6 在热路径，`enabled=false` 时
 * 第一行就 return（零反射零字符串拼接）。广播 `op=hcr sub=on|off|status` 即时开关，
 * **不写 Kv、不持久化**——详见 EntryReceiver 分支注释（冷启即恢复静默是探针的期望行为）。
 *
 * 落点来源：`docs/_r65_host_cache_refresh_probe.md` §3 P1~P6 + R65 追加的 P7（标题来源）
 * + `docs/_r64_microx_instant_group.md` §4b P1/P2/P3/P5，去重后 H1~H8 + P7。
 */
object HostCacheRefreshProbe {

    /** 只有 enabled 时回调才打日志；hook 本身常驻。广播可即时翻转。 */
    @Volatile
    var enabled = false

    /** 每个落点的安装结果，供 `op=hcr`（无 sub）回显"哪些挂上了"。 */
    private val hookStatus: MutableMap<String, Boolean> =
        Collections.synchronizedMap(LinkedHashMap<String, Boolean>())

    @Volatile
    private var installed = false

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
        val cl = lpparam.classLoader

        // H1 通知入口：k74.y0.b(int, k74.y0, Object)。s4 继承它（未覆写），
        // hook 父类即覆盖子类 s4.b —— R65 P1 的精确落点。
        guard("H1-s4.b") {
            XposedHelpers.findAndHookMethod(
                "k74.y0", cl, "b",
                Int::class.javaPrimitiveType, "k74.y0", Any::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching {
                            val code = param.args.getOrNull(0) as? Int ?: -1
                            val user = str(param.args.getOrNull(2))
                            LogX.i("HCR s4.b event=$code user=$user")
                            LogX.i("HCR s4.b stack ${hostStack(8)}")
                        }.onFailure { LogX.w("HCR s4.b err: ${it.javaClass.simpleName}") }
                    }
                }
            )
        }

        // H2 通知到主 adapter：p3.e(int, k74/y0, Object)。
        guard("H2-p3.e") {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mm.ui.conversation.p3", cl, "e",
                Int::class.javaPrimitiveType, "k74.y0", Any::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching {
                            val code = param.args.getOrNull(0) as? Int ?: -1
                            val user = str(param.args.getOrNull(2))
                            LogX.i("HCR p3.e event=$code user=$user")
                        }.onFailure { LogX.w("HCR p3.e err: ${it.javaClass.simpleName}") }
                    }
                }
            )
        }

        // H3 核心证据：p3.f():La94/e; —— 重查是否发生、游标里有没有我方行。
        guard("H3-p3.f") {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mm.ui.conversation.p3", cl, "f",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching { inspectCursor(param.result) }
                            .onFailure { LogX.w("HCR p3.f err: ${it.javaClass.simpleName}") }
                    }
                }
            )
        }

        // H4 过滤门：i3.s(Object, int, a94/a, boolean)。
        // ⚠️ 不主动调 a94.e.P；pass 的真值由 H4b 被动 hook 在 HCR P 行给出。
        guard("H4-i3.s") {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mm.ui.i3", cl, "s",
                Any::class.java, Int::class.javaPrimitiveType, "a94.a",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        runCatching {
                            val code = param.args.getOrNull(1) as? Int ?: -1
                            val user = str(param.args.getOrNull(0))
                            LogX.i(
                                "HCR i3.s event=$code user=$user pass=? " +
                                    "(只读探针不主动调 a94.e.P；真值见 HCR P 行)"
                            )
                        }.onFailure { LogX.w("HCR i3.s err: ${it.javaClass.simpleName}") }
                    }
                }
            )
        }

        // H4b（H4 的辅助观察，仍只读）：a94.e.P(Object) 的实现类。
        // R65 说实现类为 a94.g / a94.k / z84/c，逐个 runCatching，挂不上只 WARN。
        // 被动记录 P 的真实布尔返回值 —— 这才是过滤门被 pass 与否的直接证据。
        hookP("a94.g", cl)
        hookP("a94.k", cl)
        hookP("z84.c", cl)

        // H5 getCount / getItem：热路径，enabled=false 必须立刻 return。
        // p3 可能只继承 i3 的方法（hookAllMethods 只看本类声明），先试 p3 再回落 i3。
        val countHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!enabled) return
                runCatching {
                    LogX.i("HCR count=${param.result as? Int ?: -1}")
                }.onFailure { /* 热路径：连 WARN 都不打，免得刷屏 */ }
            }
        }
        val itemHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!enabled) return
                val i = param.args.getOrNull(0) as? Int ?: return
                if (i >= 4) return // 只前 4 项，热路径少拼字符串
                runCatching {
                    LogX.i("HCR item[$i]=${str(ConvReader.username(param.result))}")
                }.onFailure { /* 同上，静默 */ }
            }
        }
        guard("H5-p3.count") { hookFirst(listOf("com.tencent.mm.ui.conversation.p3", "com.tencent.mm.ui.i3"), "getCount", countHook, cl) }
        guard("H5-p3.item") { hookFirst(listOf("com.tencent.mm.ui.conversation.p3", "com.tencent.mm.ui.i3"), "getItem", itemHook, cl) }

        // H6 notifyDataSetChanged（after）：复用 AutoDndGroup 的识别口径
        // （类名 hint + 内容判定），但本探针不能改那个文件、其方法是 private，
        // 故在 isConversationAdapter() 里等价实现；热路径、离线零开销。
        guard("H6-notify") {
            XposedHelpers.findAndHookMethod(
                "android.widget.BaseAdapter", cl, "notifyDataSetChanged",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        val ad = param.thisObject as? ListAdapter ?: return
                        if (!isConversationAdapter(ad)) return
                        runCatching {
                            LogX.i("HCR notify ${param.thisObject.javaClass.name}")
                        }.onFailure { /* 静默 */ }
                    }
                }
            )
        }

        // H7 游标层是否被换：w8.W(Cursor) before。GconvPage.kt:833 的现成参考，
        // 那里 hookAllMethods("W") —— 这里照做，只读入参游标行数。
        guard("H7-w8.W") {
            val cls = XposedHelpers.findClass("com.tencent.mm.ui.w8", cl)
            val set = XposedBridge.hookAllMethods(cls, "W", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    runCatching {
                        val cur = param.args.getOrNull(0) as? Cursor
                        LogX.i("HCR w8.W count=${cur?.count ?: -1} cursor=${cur?.javaClass?.simpleName}")
                    }.onFailure { /* 静默 */ }
                }
            })
            if (set.isEmpty()) error("no W")
        }

        // H8 写库原文：SQLiteDatabase.execSQL / insertWithOnConflict before。
        // 宿主用的是 wcdb（AntiRevoke.kt:362 实证），android 原生类作为兜底也试。
        guard("H8-sql") {
            val names = listOf(
                "com.tencent.wcdb.database.SQLiteDatabase",
                "android.database.sqlite.SQLiteDatabase"
            )
            var hooked = 0
            for (name in names) {
                val cls = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull() ?: continue
                hooked += runCatching {
                    XposedBridge.hookAllMethods(cls, "execSQL", sqlHook("execSQL")).size
                }.getOrDefault(0)
                hooked += runCatching {
                    XposedBridge.hookAllMethods(cls, "insertWithOnConflict", sqlHook("insert")).size
                }.getOrDefault(0)
            }
            if (hooked == 0) error("no SQLiteDatabase hook point")
        }

        // P7（R65 追加）：标题来源。q4.n(String, boolean)=ContactStorage.get，
        // 先查内存缓存 q4.i:p4（命中直返），未命中才回查 rcontact 读 nickname。
        // 只打印「我方 wxgrp_ 行取到的 nick 是不是我写进去的组名」——不折腾 cacheHit 布尔。
        // ⚠️ findAndHookMethod 只按方法名+参数类型匹配，返回类型不必知道。
        val p7 = runCatching {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mm.storage.q4", cl, "n",
                String::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        val u = param.args.getOrNull(0) as? String ?: return
                        if (!u.startsWith("wxgrp_") && !u.startsWith("wx__")) return
                        runCatching {
                            val ret = param.result
                            LogX.i(
                                "HCR q4.n user=$u nick=${nickOf(ret).take(12)} " +
                                    "ret=${ret?.javaClass?.simpleName ?: "null"}"
                            )
                        }.onFailure { /* 静默 */ }
                    }
                }
            )
        }
        hookStatus["P7-q4.n"] = p7.isSuccess
        if (p7.isFailure) {
            val t = p7.exceptionOrNull()
            LogX.w("HCR hook P7-q4.n FAIL ${t?.javaClass?.simpleName}: ${t?.message?.take(80)}")
            // 退路：hook p3.F(String)（getView 取标题的上游），同样只读打 nick。
            guard("P7-p3.F") {
                XposedHelpers.findAndHookMethod(
                    "com.tencent.mm.ui.conversation.p3", cl, "F", String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!enabled) return
                            val u = param.args.getOrNull(0) as? String ?: return
                            if (!u.startsWith("wxgrp_") && !u.startsWith("wx__")) return
                            runCatching {
                                val ret = param.result
                                LogX.i(
                                    "HCR p3.F user=$u nick=${nickOf(ret).take(12)} " +
                                        "ret=${ret?.javaClass?.simpleName ?: "null"}"
                                )
                            }.onFailure { /* 静默 */ }
                        }
                    }
                )
            }
        }

        val ok = hookStatus.values.count { it }
        LogX.i("HCR installed ok=$ok fail=${hookStatus.size - ok} enabled=$enabled")
    }

    // ------------------------------------------------------------------ 开关 / 状态

    fun turn(on: Boolean) {
        enabled = on
        LogX.i(if (on) "HCR PROBE ON" else "HCR PROBE OFF")
    }

    /** 无 sub 时回显：enabled + 各 hook 安装成功/失败清单。 */
    fun status(): List<String> {
        val snap = synchronized(hookStatus) { hookStatus.toList() }
        val ok = snap.count { it.second }
        val lines = ArrayList<String>(snap.size + 2)
        lines.add("HCR STATUS enabled=$enabled installed=$installed ok=$ok fail=${snap.size - ok}")
        for ((name, success) in snap) {
            lines.add("HCR hook $name ${if (success) "OK" else "FAIL"}")
        }
        if (snap.isEmpty()) lines.add("HCR hook <none> (install() 还没跑？)")
        return lines
    }

    // ------------------------------------------------------------------ 内部工具

    /** 统一安装守卫：单钩失败只记 FAIL + 一条 WARN，绝不整体禁用。 */
    private fun guard(name: String, block: () -> Unit) {
        val ok = runCatching(block)
        hookStatus[name] = ok.isSuccess
        if (ok.isFailure) {
            val t = ok.exceptionOrNull()
            LogX.w("HCR hook $name FAIL ${t?.javaClass?.simpleName}: ${t?.message?.take(80)}")
        }
    }

    /** 依次尝试多个候选类（p3 未声明则回落父类 i3），第一个挂成功的胜出。 */
    private fun hookFirst(names: List<String>, method: String, cb: XC_MethodHook, cl: ClassLoader) {
        for (name in names) {
            val cls = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull() ?: continue
            val set = runCatching { XposedBridge.hookAllMethods(cls, method, cb) }.getOrDefault(emptySet())
            if (set.isNotEmpty()) return
        }
        error("no $method in ${names.joinToString("/")}")
    }

    /** 被动 hook `a94.e.P(Object)` 的实现类；结果只在 HCR P 行给出。 */
    private fun hookP(clsName: String, cl: ClassLoader) {
        val key = "H4b-P.$clsName"
        val ok = runCatching {
            val cls = XposedHelpers.findClass(clsName, cl)
            val set = XposedBridge.hookAllMethods(cls, "P", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled) return
                    if (param.args.size != 1) return
                    runCatching {
                        val user = str(param.args[0])
                        val pass = param.result as? Boolean
                        LogX.i("HCR P user=$user pass=${pass?.toString() ?: "?"} impl=$clsName")
                    }.onFailure { /* 静默 */ }
                }
            })
            if (set.isEmpty()) error("no P")
        }
        hookStatus[key] = ok.isSuccess
        if (ok.isFailure) {
            val t = ok.exceptionOrNull()
            LogX.w("HCR hook $key FAIL ${t?.javaClass?.simpleName}: ${t?.message?.take(80)}")
        }
    }

    private val sqlHook = { method: String ->
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled) return
                runCatching {
                    val sql = when (method) {
                        "execSQL" -> param.args.getOrNull(0) as? String
                        else -> {
                            val table = param.args.getOrNull(0) as? String
                            val values = param.args.getOrNull(2)
                            buildString {
                                append("insert table=").append(table)
                                append(" values=").append(values?.toString()?.take(120) ?: "null")
                            }
                        }
                    } ?: return
                    if (!sql.contains("rconversation", ignoreCase = true)) return
                    LogX.i("HCR sql $method ${sql.take(160)}")
                }.onFailure { /* 静默 */ }
            }
        }
    }

    /**
     * H3：只读遍历 `p3.f()` 返回的游标。`getString` 取值 + 记原 position 后 restore，
     * 绝不改变游标最终停留位置；拿不到 username 列就只打 cursor count，不抛异常。
     */
    private fun inspectCursor(result: Any?) {
        val cur = result as? Cursor
        if (cur == null) {
            LogX.i("HCR p3.f result=null sqlLen=-1 cursor=-1")
            return
        }
        val sqlLen = sqlLenOf(cur)
        val count = runCatching { cur.count }.getOrDefault(-1)
        val idx = runCatching { cur.getColumnIndex("username") }.getOrDefault(-1)
        if (idx < 0) {
            LogX.i("HCR p3.f sqlLen=$sqlLen cursor=$count userCol=? (只读打 count，不遍历)")
            return
        }
        val origin = runCatching { cur.position }.getOrDefault(0)
        var own = 0
        var mx = 0
        var scanned = 0
        try {
            for (i in 0 until count) {
                if (!runCatching { cur.moveToPosition(i) }.getOrDefault(false)) continue
                val u = runCatching { cur.getString(idx) }.getOrNull() ?: continue
                scanned++
                if (u.startsWith("wxgrp_")) own++
                else if (u.startsWith("wx__")) mx++
            }
        } finally {
            runCatching { cur.moveToPosition(origin) }
        }
        LogX.i("HCR p3.f sqlLen=$sqlLen cursor=$count scan=$scanned wxgrp=$own wx__=$mx")
    }

    /** 尽力从游标对象里找一个含 "select" 的 String 字段，取长度；找不到返回 -1。 */
    private fun sqlLenOf(cur: Cursor): Int {
        return runCatching {
            var c: Class<*> = cur.javaClass
            repeat(4) {
                for (f in c.declaredFields) {
                    if (f.type != String::class.java) continue
                    f.isAccessible = true
                    val v = f.get(cur) as? String ?: continue
                    if (v.length > 20 && v.contains("select", ignoreCase = true)) return v.length
                }
                c = c.superclass ?: return -1
            }
            -1
        }.getOrDefault(-1)
    }

    /**
     * 会话 adapter 识别：与 `AutoDndGroup.isConversationAdapter` 同口径
     * （hint 类名 + 内容判定），只是那两个成员是 private、且本轮不许改那个文件，
     * 故在此等价实现（不是重复造轮子，是复刻判定逻辑）。
     */
    private fun isConversationAdapter(ad: ListAdapter): Boolean {
        val cls = ad.javaClass.name
        if (cls.startsWith("com.lx.wxgroup")) return false
        if (cls == "com.tencent.mm.ui.conversation.p3") return true
        if (cls.contains("com.tencent.mm.ui.conversation")) return true
        val n = runCatching { ad.count }.getOrDefault(0)
        if (n < 20) return false
        return runCatching {
            val limit = if (n < 12) n else 12
            for (i in 0 until limit) {
                val item = ad.getItem(i) ?: continue
                val u = ConvReader.username(item)
                if (u.endsWith("@chatroom")) return@runCatching true
            }
            false
        }.getOrDefault(false)
    }

    /** P7：从 q4.n / p3.F 的返回值里只读抠出 nickname（String 直接取，否则找含 nick 的 String 字段）。 */
    private fun nickOf(v: Any?): String {
        v ?: return "<null>"
        if (v is String) return v
        return runCatching {
            var c: Class<*> = v.javaClass
            repeat(4) {
                for (f in c.declaredFields) {
                    if (!f.name.contains("nick", ignoreCase = true)) continue
                    if (f.type != String::class.java) continue
                    f.isAccessible = true
                    val s = f.get(v) as? String
                    if (!s.isNullOrEmpty()) return s
                }
                c = c.superclass ?: return ""
            }
            ""
        }.getOrDefault("")
    }

    /** 过滤 Xposed/LSPosed 桥接帧 + 本模块帧，取宿主侧前 N 层（判"谁发起通知"）。 */
    private fun hostStack(max: Int): String {
        val frames = Thread.currentThread().stackTrace
        val first = frames.indexOfFirst { f ->
            val n = f.className
            !n.startsWith("de.robv.android.xposed") &&
                !n.startsWith("org.lsposed") &&
                !n.contains("LSPosed") &&
                !n.startsWith("com.lx.wxgroup") &&
                !n.startsWith("java.lang.reflect")
        }
        if (first < 0) return "<no host frame>"
        return frames.drop(first).take(max).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}"
        }
    }

    private fun str(v: Any?): String = when (v) {
        null -> "null"
        is String -> v
        else -> v.toString().take(120)
    }
}
