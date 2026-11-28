package com.lx.wxgroup.feature

import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.InvocationTargetException

/**
 * R89 阶段2 · lane A：会话列表「查询层隐藏」Hook。
 *
 * 目标：把「已分进任意分组」的会话从主列表里隐藏 —— 在微信读 `rconversation` 的三条
 * 查询（主列表全量 I / 批量明细 C / 未读聚合 F）上，读取侧追加
 * `rconversation.username NOT IN (...)`，被分组的会话行就不会被查出来。
 *
 * SQL 判据与改写本体在 [SqlHideCond]（纯字符串、可 JVM 单测），本类只负责：装钩、
 * 维护「已分组 wxid」内存快照、被 SQLite 拒绝时熔断、成员变更后补发宿主刷新通知。
 * 开关 [Keys.GCONV_SQL_HIDE]（lane B 在 Keys.kt 定义）装机读一次，运行期 [turn] 可做
 * 内存 A/B 翻转（不写 Kv，方便阶段3 正反对照）。
 *
 * ## ⚠️ 热路径绝不读库（本类最核心的一条）
 * `GroupDb.allMemberWxids()` 是**全表查询、无缓存**，绝不能出现在 rawQueryWithFactory
 * 的回调里 —— 否则会话列表每次查询都变成一次全表扫 + 一次重入（递归 ANR 同款，
 * 见 [AntiRevokeHint] 类注释的 `Kv.getBool` 血案）。分组快照 [grouped] 只在冷启后台
 * 线程 [refreshGrouped] 与 [onMembersChanged]（成员变更，非 SQL 热路径）里刷新，
 * 回调里只读这个不可变快照。
 *
 * ## ⚠️ 4 参版内部转发 5 参版 ⇒ 一次查询连进两发
 * 微信只用 `com.tencent.wcdb.database.SQLiteDatabase.rawQueryWithFactory`
 * （全 15 dex 无子类，`hookAllMethods` 覆盖完整，`rawQuery` 与 AOSP SQLiteDatabase 无需 hook）。
 * 4 参版方法体会转发 5 参版 ⇒ 一次逻辑查询会先后进两次 before/after：
 * - 4 参版 before 改写、5 参版 before 拿到的已是改写后 SQL，靠 [SqlHideCond] 的
 *   `/*wr*/` MARK 在 `isConvSelect` 直接短路，**不会二次注入**；
 * - after 要还原/熔断，得认清「改的是外层那一发」。所以这里把被改写的 `MethodHookParam`
 *   实例一并记进 ThreadLocal，after 里按**对象同一性**匹配，只有命中自己改写的那一发
 *   才清状态 / 还原 / 熔断（内层转发的 after 直接放行）。
 *
 * ## 熔断
 * 改写过的 SQL 一旦被 SQLite 判为语法错误，就永久停用本进程的改写（`disabled=true`）：
 * 宁可功能失效，也不能让微信每次开列表都崩。after 里逐字还原 `param.args[1]`，
 * 是防宿主重试时再撞一次改写后的坏 SQL；真正的兜底是 `disabled`（此后 before 直接返回）。
 */
object ConvSqlHide {

    private const val TAG = "HIDE"

    // ---- 混淆名（绑 8.0.48/2588，与 HostConvDelete 同款 2 步单例链）----
    private const val CLS_H8 = "vj0.h8"   // 单例容器（classes7.dex）
    private const val M_H8_B = "b"        // static：vj0.h8.b() → vj0.e
    private const val M_E_V = "v"         // vj0.e.v() → s4 实例（声明类型 o8）
    private const val M_S4_B = "b"        // s4.b(int,y0,Object)：只发事件
    private const val EVENT_UPDATE = 3    // event=3 = 会话更新（宿主自发 s4.b event=3，真机证据）

    /** 宿主 classLoader（install 时存）。⛔ 绝不能用模块自己的 loader，那找不到宿主 vj0.h8/s4。 */
    @Volatile
    private var hostCl: ClassLoader? = null

    /** 开关：装机读一次 [Keys.GCONV_SQL_HIDE]；[turn] 可内存翻转，不写 Kv。 */
    @Volatile
    private var enabled = false

    /** 熔断：改写过的 SQL 被拒绝后本进程永久停用改写（微信不崩，功能失效）。 */
    @Volatile
    private var disabled = false

    /** install 幂等：重复调用只装一次钩子（HookEntry.bootstrap 只调一次，这里是兜底）。 */
    private var installed = false

    /** 已分组 wxid 不可变快照，整体替换引用；热路径只读它，绝不读库。 */
    @Volatile
    private var grouped: Set<String> = emptySet()

    /** 快照版本：成功发布一次快照 +1；0 = 还没成功加载过（首次 refresh 据此不通知）。 */
    @Volatile
    private var groupedVersion = 0L

    /** 命中计数（改写过多少次 SQL），status 回显用。 */
    @Volatile
    private var hitCount = 0L

    /** 重入守卫：漏 finally 会让该线程后续查询全静默（AntiRevokeHint.kt:41-51 的 ANR 血案）。 */
    private val inHook = ThreadLocal<Boolean>()

    /** 记下改写前的原文，after 里据此「逐字还原」。 */
    private val origSql = ThreadLocal<String>()

    /** 记下「被改写的是哪一发 invocation」，after 按对象同一性匹配（4→5 转发时尤其关键）。 */
    private val rewrittenParam = ThreadLocal<XC_MethodHook.MethodHookParam>()

    /** refreshGrouped 的差集结果，onMembersChanged 据此补发 event=3。 */
    private data class Diff(val added: Set<String>, val removed: Set<String>)

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
        hostCl = lpparam.classLoader
        // 装钩前先把开关读进来（读 Kv 要查模块库，钩子装好后它会进一次 rawQueryWithFactory）
        enabled = runCatching { Kv.getBool(Keys.GCONV_SQL_HIDE) }.getOrDefault(false)

        runCatching {
            val cls = XposedHelpers.findClass(
                "com.tencent.wcdb.database.SQLiteDatabase", lpparam.classLoader
            )
            XposedBridge.hookAllMethods(cls, "rawQueryWithFactory", hook())
        }.onFailure { LogX.w("$TAG hook-fail rawQueryWithFactory ${it.javaClass.simpleName}") }

        LogX.i("$TAG installed (enabled=$enabled)")

        // 冷启后台读一次快照：install 同步路径不能读库（全表查询），丢一次性后台线程。
        Thread({ refreshGrouped() }, "ConvSqlHide-refresh").apply {
            isDaemon = true
            start()
        }
    }

    /** 内存 A/B 翻转，不写 Kv（阶段3 正反对照用）。 */
    fun turn(on: Boolean) {
        enabled = on
        LogX.i("$TAG turn enabled=$on")
    }

    /** 回显：enabled / 熔断 / 快照版本+条数 / 命中计数。 */
    fun status(): List<String> {
        val g = grouped
        return listOf(
            "enabled=$enabled",
            "disabled=$disabled",
            "snapshot v=$groupedVersion n=${g.size}",
            "hits=$hitCount",
        )
    }

    /**
     * 成员变更后调用（lane B 在 add/remove 成员提交后回调）：
     * 同步刷新快照，再对差集（added + removed）逐个补发 `s4.b(3, s4, wxid)` 让宿主
     * 即时重查对应会话，被分组/解除分组的会话行不冷启即隐藏/恢复。
     *
     * ⚠️ 首次 refresh 不通知：install 的冷启快照与本方法都可能触发「从空集到全集」，
     * 若那次就把全部历史成员当新增逐个补发 event=3，冷启会无意义刷一遍全列表。
     * 用「刷新前 groupedVersion==0」识别首次，首次只建基线、不发通知。
     */
    fun onMembersChanged() {
        val first = groupedVersion == 0L
        val diff = refreshGrouped()
        if (first) return
        // 功能关着（或已熔断）时快照照刷（运行期 turn(true) 能立刻用），但不碰宿主：
        // 隐藏没生效，补发 event=3 只会让列表白重查一遍。
        if (!enabled || disabled) return
        for (wxid in diff.added + diff.removed) notifyConvUpdated(wxid)
    }

    // ------------------------------------------------------------------ 快照

    /**
     * 重读全表生成新快照，整体替换 [grouped] 引用（热路径读的始终是同一个不可变 Set）。
     *
     * - 成功：算 added/removed 差集 → 发布新快照 + `groupedVersion++`。
     * - 读库异常：发布 `emptySet()`（**宁可暂时显示，绝不残留隐藏**）+ WARN；
     *   版本不增、差集置空 —— 一次读失败不能误发一批 event=3，也不能算「首次成功」。
     */
    private fun refreshGrouped(): Diff {
        val newSet = runCatching { GroupDb.current()?.allMemberWxids() ?: emptySet() }
            .onFailure { LogX.w("$TAG grouped read failed: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (newSet == null) {
            grouped = emptySet()
            LogX.w("$TAG grouped cleared on read failure (show-all)")
            return Diff(emptySet(), emptySet())
        }
        val old = grouped
        val added = newSet - old
        val removed = old - newSet
        grouped = newSet
        groupedVersion++
        LogX.i("$TAG grouped v=$groupedVersion n=${newSet.size} +${added.size} -${removed.size}")
        return Diff(added, removed)
    }

    // ------------------------------------------------------------------ 通知宿主

    /**
     * 补发会话更新通知：`s4.b(3, s4, username)`（第二参传 s4 自己，对齐宿主 `y0.b(3,s4,user)`）。
     * 反射链任一步失败只打 [LogX.w] 并返回，绝不抛给调用方。
     */
    private fun notifyConvUpdated(username: String) {
        val cl = hostCl ?: run {
            LogX.w("$TAG no host classLoader user=$username")
            return
        }
        val s4 = runCatching { s4Instance(cl) }
            .onFailure { LogX.w("$TAG s4 单例链 fail: ${why(it)} user=$username") }
            .getOrNull() ?: return
        runCatching { XposedHelpers.callMethod(s4, M_S4_B, EVENT_UPDATE, s4, username) }
            .onFailure { LogX.w("$TAG s4.b(3,s4,$username) fail: ${why(it)}") }
    }

    /** 单例链 `vj0.h8.b().v()`：2 步拿到 s4 实例（声明类型 o8，实际是 s4）。 */
    private fun s4Instance(classLoader: ClassLoader): Any? {
        val h8cls = XposedHelpers.findClass(CLS_H8, classLoader)
        val vj0e = XposedHelpers.callStaticMethod(h8cls, M_H8_B) ?: return null
        return XposedHelpers.callMethod(vj0e, M_E_V)
    }

    /** 拆掉 InvocationTargetException 这层壳（真正原因在 cause 里），与 HostConvDelete 同款。 */
    private fun why(t: Throwable): String {
        val c = (t as? InvocationTargetException)?.targetException ?: t.cause ?: t
        val m = c.message?.replace('\n', ' ')?.replace('\r', ' ')
        return if (m.isNullOrBlank()) c.javaClass.name else m
    }

    // ------------------------------------------------------------------ Hook

    /** 惰性构造并缓存：object 初始化阶段 new XC_MethodHook 会让无 Xposed 的 JVM 单测抛初始化异常。 */
    @Volatile
    private var hookRef: XC_MethodHook? = null

    private fun hook(): XC_MethodHook {
        val h = hookRef
        if (h != null) return h
        val made = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled || disabled) return
                if (inHook.get() == true) return
                inHook.set(true)
                try {
                    // 出错一律放弃改写、绝不影响宿主
                    runCatching { rewrite(param) }
                        .onFailure { runCatching { LogX.w("$TAG err ${it.javaClass.simpleName}") } }
                } finally {
                    inHook.set(false)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                // 只有「被我们改写的那一发」才继续（4→5 转发时内层 after 先跑、直接放行）
                val rp = rewrittenParam.get() ?: return
                if (rp !== param) return
                rewrittenParam.remove()
                val orig = origSql.get() ?: return
                origSql.remove()
                if (!param.hasThrowable()) return
                val th = param.throwable ?: return
                val msg = th.message ?: ""
                // ⛔ 只认「结构类」错误才熔断（对齐 AntiRevokeHint.kt:229-232）。
                // database is locked / disk I/O / busy 这类瞬态异常与改写无关，
                // 一次锁竞争就永久停用功能是不可接受的（R89 复核 V3）。
                val structural = msg.contains("syntax error") || msg.contains("no such column") ||
                    msg.contains("misuse") || msg.contains("ambiguous column") ||
                    msg.contains("no such table")
                if (!structural) {
                    LogX.w(
                        "$TAG rewrite threw non-structural error, keep enabled: " +
                            "${th.javaClass.simpleName}: ${msg.replace('\n', ' ').take(120)}"
                    )
                    return
                }
                // 逐字还原，别让宿主重试时再撞一次改写后的坏 SQL；真正兜底是 disabled
                runCatching { param.args?.let { if (it.size > 1) it[1] = orig } }
                disabled = true
                LogX.w(
                    "$TAG disabled: rewritten SQL rejected: " +
                        "${th.javaClass.simpleName}: ${msg.replace('\n', ' ').take(160)}"
                )
            }
        }
        hookRef = made
        return made
    }

    private fun rewrite(param: XC_MethodHook.MethodHookParam) {
        val sql = param.args?.getOrNull(1) as? String ?: return
        // 三级短路：没分组零开销 / 不是会话查询 / 改写入口拒绝（空集或非目标形态）
        val g = grouped
        if (g.isEmpty()) return
        if (!SqlHideCond.isConvSelect(sql)) return
        val out = SqlHideCond.rewriteConvSql(sql, g) ?: return
        if (out == sql) return // 未变化不改
        origSql.set(sql)
        rewrittenParam.set(param)
        param.args[1] = out
        hitCount++
        LogX.i("$TAG hit n=${g.size} len=${sql.length}->${out.length}")
    }
}
