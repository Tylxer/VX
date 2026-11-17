package com.lx.wxgroup.feature

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.Bundle
import android.view.View
import com.lx.wxgroup.core.ContactSqlSource
import com.lx.wxgroup.data.GroupConvPlan
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 路线 E：分组行点击 → 复用微信「折叠的群聊」页（`ConvBoxServiceConversationUI`）。
 *
 * 为什么不自己画一个分组会话页：分组里装的是**微信自己的会话**，会话行（头像 / 未读角标 /
 * 时间 / 消息摘要 / 右滑菜单）全部是宿主 `d2.getView` 里按 `rconversation` 游标画的。
 * 自己画一套等于把宿主那套按版本硬编码重写一遍，每换一版就可能错位；而宿主本来就有
 * 「按 parentRef 取一撮会话」的页面（折叠的群聊），复用它只需要换掉它的数据源与标题。
 *
 * 静态结论（`docs/_r55b_convbox.md`，classes4.dex 已核实）：
 *  - 本页唯一读的 extra 是 `Contact_User`（String），只落进 `superUsername` 字段，
 *    **不参与 SQL**；真正查库的 `t0.N()` 把 parentRef 写死成 `"conversationboxservice"`。
 *  - 因此「传 extra 就查到我们的组」是不成立的，必须 hook `t0.N()` 换数据源。
 *  - `com.tencent.mm.ui.w8.W(android.database.Cursor)V` 是 **public**（flags=0x1），
 *    `com.tencent.mm.ui.conversation.t0.N()V` 也是 **public**（flags=0x1），两者都可
 *    `XposedHelpers.callMethod`。
 *
 * 四处 hook（详见各函数注释）：
 *  0. `com.tencent.wcdb.database.SQLiteDatabase.rawQueryWithFactory` —— 自建捕获 EnMicroMsg.db 句柄
 *     （`AntiRevoke.wxDb()` 冷启必为 null，见 [convDb]）。⚠️ 不能挂 `rawQuery`：
 *     宿主的会话列表查询链 `t0.N → s4.t → z84.z.k → z84.e.s` 只调 `rawQueryWithFactory`；
 *  A. `ConvBoxServiceConversationFmUI.onActivityCreated` after —— 认领 extra，算成员 / 组名，设标题；
 *  B. `com.tencent.mm.ui.conversation.t0.N()` before —— 用组内成员换掉 Cursor；
 *  C. `ConvBoxServiceConversationFmUI.onDestroy` before —— 复位，防止污染微信原生「折叠的群聊」页。
 *
 * ⛔ 全程只读：不改 `rconversation` / `rcontact` 一个字节（不写 parentRef、不写 nickname）。
 * ⛔ 每个 hook 体全包 runCatching：异常只打日志，绝不改变宿主返回值 / 绝不崩宿主。
 */
object GconvPage {

    private const val TAG = "K7 gpage"

    private const val HOST = "com.tencent.mm"
    private const val PAGE = "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI"

    /** 宿主单聊页（8.0.48 实测存在；`com.tencent.mm.chatting.ui.ChattingUI` 不存在）。 */
    private const val CHATTING = "com.tencent.mm.ui.chatting.ChattingUI"

    /** 内部 Fragment：标题、数据源、生命周期都在它身上 */
    private const val FMUI =
        "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI\$ConvBoxServiceConversationFmUI"

    /** 本页唯一被读取的 Intent extra（原文常量，见 r55b §1.3） */
    private const val EXTRA_USER = "Contact_User"

    private const val TABLE = "rconversation"

    /** 宿主的 wcdb 实现类（`AntiRevoke.wxDb()` 给的也是它的实例） */
    private const val WCDB = "com.tencent.wcdb.database.SQLiteDatabase"

    /** 消息主库文件名：`rconversation` / `rcontact` / `message` 都在这一个库里 */
    private const val DB_MARK = "EnMicroMsg.db"

    /**
     * 认输上限：这么多发 `rawQueryWithFactory` 里都没命中就卸钩（抄 `ContactSqlSource.CAPTURE_MAX_CALLS` 的做法）。
     *
     * 为什么是 3000 而不是 `ContactSqlSource` 的 20000，也不是复核建议的 300：
     * `rawQueryWithFactory` 是**全进程所有查询**的唯一落点（比 `rawQuery` 还热一层），
     * 启动阶段几百上千发是常态，而「主界面首次会话列表查询」可能排在其中几百发之后
     * ⇒ 300 会在真正那一发之前认输，P0 又回到原点。3000 足够盖住启动期，又能在
     * 「换版本后判据永远不命中」时把钩子从热路径上摘掉。
     */
    private const val CAPTURE_MAX_CALLS = 3_000

    /**
     * 页面暗号前缀。
     *
     * ⛔ **不能**再用 `GroupConvPlan.usernameOf(gid)`（`wxgrp_<hex>`）当 extra：
     * `ConvBoxServiceConversationFmUI.onPause()`（classes4.dex code=0x564a44）会拿 `superUsername`
     * 去调 `s4.b0(String)`（`updateUnreadByTalker`，code=0x4a4930）：它先 `s4.p(username)` 取行，
     * 取到就 UPDATE —— 清 `unReadCount/unReadMuteCount/UnReadInvite/atCount`、抹 `attrflag` 的
     * `0x100000` 位（另一条分支还经 `s4.Z` 动 `flag`）。`wxgrp_<hex>` 在 `rconversation` 里是
     * **真实存在的行**（R54 的伪会话），于是每次退出分组页宿主都会回写我们自己的行，
     * 直接破「零数据写入」红线。
     *
     * 哨兵不对应任何 `rconversation.username` ⇒ `s4.p(哨兵)` 恒 null ⇒ `b0` 第一句就 return，零写入。
     */
    private const val SENTINEL = "gconv_view:"

    /**
     * 当前页认领的组 id。`0` = 不是我们的页（微信原生「折叠的群聊」），Hook B 直接放行。
     *
     * 用单例字段而不是逐实例 Map：同一时刻只可能有一个该页在前台，且 [reset] 在实例
     * onDestroy 时清零。写入顺序固定为「先成员 / 组名、后 gid」，读侧先看 gid
     * ⇒ 不会读到半套状态。
     */
    @Volatile
    private var activeGid = 0L

    @Volatile
    private var activeMembers: List<String> = emptyList()

    /** 标题用的组名；Hook B 拿到真实行数后会再刷一次标题（行数只有查完才知道） */
    @Volatile
    private var activeName: String = ""

    private var activePage = WeakReference<Activity>(null)

    /** Hook B 里补刷标题要用它调 `setMMTitle`（Activity.setTitle 会被宿主自己的 ActionBar 覆盖掉） */
    private var activeFragment = WeakReference<Any>(null)

    /**
     * 本页那个 adapter 实例（`ConvBoxServiceConversationFmUI.adapter`，类型 d2）。
     *
     * 为什么必须有它：`t0` 是**所有**「折叠的群聊」页共用的 Adapter 类。按返回键回上一个
     * 原生折叠页时，宿主的生命周期顺序是「原生页 onResume（调 N）→ 我们的页 onDestroy」，
     * 只靠全局 `activeGid` 会把我们的成员集合喂进**原生页**。用实例身份比对，谁家的 adapter
     * 只吃谁家的数据。
     */
    private var activeAdapter = WeakReference<Any>(null)

    /**
     * 我方最后一次喂进 adapter 的游标。`hookW` 靠它辨认「宿主自己查出来的游标」并换回我方这条。
     * 用 WeakReference：宿主 `onDestroy` 时 `w8.k()` 会 close 它，我们不再持有强引用。
     */
    private var activeCursor = WeakReference<Cursor>(null)

    // ---------------------------------------------------------------- 微信库句柄

    /**
     * 自己抓到的 `EnMicroMsg.db` 句柄。命中判据见 [captureHook]。
     *
     * 为什么需要它（P0）：`AntiRevoke.wxDb()` 返回的 `msgDb` **只在 `updateWithOnConflict`
     * 且 `table=="message"` 时**被赋值（`AntiRevoke.kt:515-516`），另一条捕获路径
     * （`AntiRevokeHint`）也只认 message 查询。于是「冷启 → 没收到任何消息 → 直接点分组」
     * 这条最常见的路径上 `wxDb()` 恒 null ⇒ 查不到库 ⇒ 页面退化成「我们的标题 + 原生列表」。
     */
    @Volatile
    private var capDb: Any? = null

    private val capUnhooks =
        Collections.synchronizedList(ArrayList<XC_MethodHook.Unhook>())

    /** 已认输：钩子已卸，别再装（[installDbCapture] 的入口闸门） */
    @Volatile
    private var capGaveUp = false

    /** 路径判据不匹配的 warn 只打一次（这个钩子在热路径上，不能每次查询刷一行） */
    @Volatile
    private var capPathWarned = false

    /** 钩子被调用的次数，到 [CAPTURE_MAX_CALLS] 认输 */
    private val capCalls = AtomicInteger(0)

    /**
     * 查询句柄，三级回退（顺序 = 可靠性从高到低）：
     *
     *  ① [capDb]：本类自己捕获的（`SQL 含 rconversation` + `getPath()` 以 `EnMicroMsg.db` 结尾 双判据）；
     *  ② [ContactSqlSource.mainDbOrNull]：bootstrap 阶段 `ContactSqlSource` 捕获的**同一个库**
     *     （`SQL 含 rcontact` + 同样的路径判据；真机冷启约 3 秒就可用）。
     *     ⚠️ 它和 `AntiRevoke.lastDb` **不是一类东西**：前者按 `getPath()` 以 `EnMicroMsg.db` 结尾认库，
     *     后者是「任意表最近一次 update 的库」，在上面查 `rconversation` 会 `no such table`。
     *  ③ [AntiRevoke.wxDb]：只在收到过 `message` 表 update 后才有值（冷启必为 null）。
     *
     * ⚠️ 三个都没有时**必须**返回 null 让上层走失败路径（[open] / [claim] 都有闸门），
     * **绝不能**退到 `AntiRevoke` 的 `lastDb`（`AntiRevoke.kt:819-826` 有这条教训）。
     */
    private fun convDb(): Any? =
        capDb
            ?: ContactSqlSource.mainDbOrNull()
            ?: AntiRevoke.wxDb()

    // ---------------------------------------------------------------- 点击入口

    /**
     * 点分组行 → 打开宿主「折叠的群聊」页，extra 带哨兵当我们的暗号。
     *
     * 与微信自己点折叠群聊一致：同 task 内 push，默认不加 `FLAG_ACTIVITY_NEW_TASK`；
     * 只有调用方不是 Activity 时才补（`v.context` 必是 Activity，这里只是兜底）。
     *
     * ⛔ `exported=false` 不影响：模块代码跑在微信进程、同一 UID，显式 setClassName 可直启。
     * ⛔ 失败必须回退 [GroupDetailPage]：否则换一版微信（类名/清单变化）点击就完全无反应，
     *    相对 R54 的「点 → 分组详情页」是功能回归。
     *
     * ⛔ **入口就要判库**（不能等 [claim]）：没有库句柄时 [claim] 会拒绝认领，但 `startActivity`
     *    仍然是成功的 ⇒ 用户点分组却跳进一个装着**别人会话**的原生「折叠的群聊」页，
     *    比 R54 的「点 → 分组详情页」还差。所以这里先判一次，最坏情况退回 R54 老入口。
     */
    fun open(context: Context?, gid: Long) {
        if (context == null) {
            LogX.w("$TAG open: null context, gid=$gid")
            return
        }
        // 冷启库句柄还没就绪：不进「别人的页」（详见上面的注释）
        if (convDb() == null) {
            LogX.w("$TAG open: no db yet, fallback to GroupDetailPage gid=$gid")
            runCatching { GroupDetailPage.open(context, gid, groupNameOf(gid)) }
                .onFailure { LogX.w("$TAG open fallback failed: ${it.javaClass.simpleName}") }
            return
        }
        val mark = SENTINEL + hexOf(gid)
        runCatching {
            val intent = Intent().apply {
                setClassName(HOST, PAGE)
                putExtra(EXTRA_USER, mark)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            LogX.i("$TAG open gid=$gid mark=$mark")
        }.onFailure {
            // 类名 / 清单变了会走到这里（ActivityNotFoundException 等），必须留痕 + 回退
            LogX.w("$TAG open failed gid=$gid: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
            runCatching { GroupDetailPage.open(context, gid, groupNameOf(gid)) }
                .onFailure { e ->
                    LogX.w("$TAG fallback failed gid=$gid: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                }
        }
    }

    /** `wxgrp_<hex>` 里那段 hex（复用 [GroupConvPlan.usernameOf] 的编码，不自己写一套） */
    private fun hexOf(gid: Long): String =
        GroupConvPlan.usernameOf(gid).removePrefix(GroupConvPlan.PREFIX)

    /**
     * 哨兵反解：先剥 [SENTINEL] 前缀，再按 `wxgrp_` 那条既有逻辑反解。
     *
     * 只认自己的暗号：原生页 extra 是空 / `"conversationboxservice"` ⇒ null ⇒ [claim] 照旧放行。
     */
    private fun gidOfMark(raw: String?): Long? {
        if (raw == null || !raw.startsWith(SENTINEL)) return null
        val hex = raw.removePrefix(SENTINEL)
        if (hex.isEmpty()) return null
        return GroupConvPlan.groupIdOf(GroupConvPlan.PREFIX + hex)
    }

    /** 组名兜底：查不到就显示 `wxgrp_<hex>`（⛔ 绝不能把哨兵当组名显示给用户） */
    private fun groupNameOf(gid: Long): String =
        runCatching { GroupDb.current()?.listGroups()?.firstOrNull { it.id == gid }?.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: GroupConvPlan.usernameOf(gid)

    // ---------------------------------------------------------------- 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookPageCreated(lpparam)
        hookAdapterRefresh(lpparam)
        hookPageDestroy(lpparam)
        installDbCapture(lpparam)
        cl = lpparam.classLoader
    }

    // ------------------------------------------------- R55d 追踪埋点（只读，不改行为）

    /** 由 `install()` 保存，供探针在运行时挂「纯日志」钩子用（不能依赖 `param` 里的 loader）。 */
    @Volatile
    private var cl: ClassLoader? = null

    private val traceLast = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile
    private var traceInstalled = false

    /**
     * 只打日志、不改返回值的追踪钩子 —— 用来**经验性**回答「宿主那条周期性刷新走的是哪条链」。
     *
     * 存在理由：真机实测我方替换完成后 3 秒 adapter 的游标就被换成空游标，
     * 而 `hookB`（`t0.N()`）**一次都没 fire** ⇒ 刷新不从 `N()` 走。
     * 与其猜，不如把候选方法全挂上 after-hook 看谁在那一刻被调。
     *
     * ⛔ 这里**只许** `LogX.i`，不许 `param.result = ...`、不许改字段进 state。
     *    同一方法 400ms 内只打一行，避免把 5000 行环形缓冲刷爆。
     */
    internal fun traceInstall(): List<String> {
        if (traceInstalled) return listOf("$TAG trace already installed")
        val ldr = cl ?: return listOf("$TAG trace: classLoader 未就绪（模块还没 install）")
        traceInstalled = true
        val out = mutableListOf<String>()
        val targets = listOf(
            "com.tencent.mm.ui.conversation.t0" to listOf("q", "L", "A", "N"),
            "com.tencent.mm.ui.w8" to listOf("q", "L", "A", "W", "getCount"),
            "com.tencent.mm.storage.s4" to listOf("t")
        )
        val cb = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.method.declaringClass.simpleName + "." + param.method.name
                val now = System.currentTimeMillis()
                val last = traceLast.put(key, now) ?: 0L
                if (now - last < 400L) return
                // 只有 W(Cursor) 能看游标身份，其它方法看不出来源
                val extra = runCatching {
                    if (param.method.name == "W" && param.args.size == 1) {
                        val cur = param.args[0] as? Cursor
                        " rows=${cur?.let { if (it.isClosed) -2 else it.count } ?: -1} curId=${System.identityHashCode(cur)}"
                    } else ""
                }.getOrDefault("")
                // 光看「谁被调」不够：要找出**谁在周期性调 q()** ⇒ 打调用栈（只对 q 打，避免刷屏）
                val st = runCatching {
                    if (param.method.name != "q") ""
                    else " st=" + Thread.currentThread().stackTrace.drop(2).take(7)
                        .joinToString("<-") { it.className.substringAfterLast('.') + "." + it.methodName }
                }.getOrDefault("")
                LogX.i(
                    "$TAG trace $key tid=${Thread.currentThread().name} " +
                        "own=${param.thisObject === activeAdapter.get()}$extra$st"
                )
            }
        }
        for ((cn, names) in targets) {
            val c = runCatching { XposedHelpers.findClass(cn, ldr) }.getOrNull()
            if (c == null) {
                out.add("$TAG trace: $cn not found")
                continue
            }
            for (n in names) {
                val set = runCatching { XposedBridge.hookAllMethods(c, n, cb) }.getOrNull()
                out.add("$TAG trace hook $cn.$n n=${set?.size ?: 0}")
            }
        }
        // 点击链探针：`AdapterView.performItemClick(View,int,long)` 是「行被点中」的唯一入口。
        // 它 fire 了但界面没跳 ⇒ 问题在宿主的 handler 里；没 fire ⇒ 触摸根本没到 ListView。
        runCatching {
            val av = XposedHelpers.findClass("android.widget.AdapterView", ldr)
            val set = XposedBridge.hookAllMethods(av, "performItemClick", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pos = param.args.getOrNull(1) as? Int ?: -1
                    val item = runCatching {
                        val ad = XposedHelpers.callMethod(param.thisObject, "getAdapter")
                        XposedHelpers.callMethod(ad, "getItem", pos)
                    }.getOrNull()
                    LogX.i(
                        "$TAG trace click pos=$pos item=${item?.javaClass?.simpleName ?: "null"} " +
                            "own=${param.thisObject === (activeFragment.get()?.let {
                                runCatching { XposedHelpers.getObjectField(it, "appbrandMessageLV") }.getOrNull()
                            })}"
                    )
                }
            })
            out.add("$TAG trace hook AdapterView.performItemClick n=${set?.size ?: 0}")
        }.onFailure { out.add("$TAG trace click hook fail: ${it.javaClass.simpleName}") }
        LogX.i("$TAG trace installed")
        return out
    }

    // ------------------------------------------------- R55g finish 追踪（只读，不改行为）

    /** 只读 finish 追踪的节流表：同一路 + 同一调用栈 300ms 内只打一行。 */
    private val finishTraceLast = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile
    private var finishTraceInstalled = false

    /**
     * 只打日志、绝不改返回值的 finish 追踪钩子 —— 用来**经验性**回答：
     * 「按一次 back 后 task 里 ConvBox 与 ChattingUI 一起消失」到底是**谁**调了
     * `ConvBoxServiceConversationUI.finish()`，以及它是不是从 `Activity.finish` 基类路径下来的。
     *
     * 挂两路（两路都要，因为 finish 可能不经 ConvBox 自己的 override）：
     *  a) `com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI.finish` 自身 override
     *     （`own`）—— 直接被打到说明有人显式调了 ConvBox 的 finish；
     *  b) `android.app.Activity.finish` 基类（`base`）—— 回调里**先判** `thisObject` 类名是否
     *     以 `com.tencent.mm` 开头，只对微信的 Activity 打印（否则会把全系统的 finish 刷进日志）。
     *     它同时兜住「不经 ConvBox override、直接 super.finish() / 反射调 Activity.finish」那条路。
     *
     * ⛔ 这里**只许** `LogX.i`：不许 `param.result = ...`、不许 setAccessible 之外的任何反射、
     *    绝不改变宿主调用行为、绝不吞异常后改变返回值。
     */
    internal fun finishTraceInstall(): List<String> {
        if (finishTraceInstalled) return listOf("GCONV_FIN already installed")
        val ldr = cl ?: return listOf("GCONV_FIN classLoader 未就绪（模块还没 install）")
        finishTraceInstalled = true
        val out = mutableListOf<String>()

        // a) ConvBox 自身的 finish
        runCatching {
            val c = XposedHelpers.findClass(PAGE, ldr)
            if (c == null) {
                out.add("GCONV_FIN hook own: $PAGE not found")
            } else {
                val set = XposedBridge.hookAllMethods(c, "finish", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = logFinish("own", param)
                })
                out.add("GCONV_FIN hook own $PAGE.finish n=${set.size}")
            }
        }.onFailure {
            out.add("GCONV_FIN hook own fail: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
        }

        // b) Activity.finish 基类，只对微信的 Activity 打印
        runCatching {
            val set = XposedBridge.hookAllMethods(
                Activity::class.java, "finish",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val self = param.thisObject ?: return
                        if (!self.javaClass.name.startsWith(HOST)) return
                        logFinish("base", param)
                    }
                }
            )
            out.add("GCONV_FIN hook base android.app.Activity.finish n=${set.size}")
        }.onFailure {
            out.add("GCONV_FIN hook base fail: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
        }

        LogX.i("GCONV_FIN installed")
        return out
    }

    /**
     * 打一行 finish 命中日志（只读）。`self` = 正在 finish 的那个 Activity 短类名，
     * `trace` = 跳过钩子自身 / Xposed 桥 / 反射 / VM 帧后、从「被 hook 的 finish」往上的
     * 调用方链（`<-` 连接，短类名.方法名）。
     */
    private fun logFinish(which: String, param: XC_MethodHook.MethodHookParam) {
        runCatching {
            val trace = callerTrace()
            val key = "$which|$trace"
            val now = System.currentTimeMillis()
            val last = finishTraceLast.put(key, now) ?: 0L
            if (now - last < 300L) return@runCatching
            val self = param.thisObject?.javaClass?.name?.substringAfterLast('.') ?: "null"
            LogX.i("GCONV_FIN $which self=$self $trace tid=${Thread.currentThread().name}")
        }.onFailure {
            LogX.w("GCONV_FIN $which log fail: ${it.javaClass.simpleName}")
        }
    }

    /**
     * 从当前线程栈取「调用方」帧：`dropWhile` 跳过我们自己的类、Xposed 桥、反射与 VM 帧，
     * 剩下第一帧就是被 hook 的 finish，往后就是它的调用方。
     */
    private fun callerTrace(): String =
        Thread.currentThread().stackTrace
            .dropWhile {
                it.className.startsWith("com.lx.wxgroup") ||
                    it.className.startsWith("de.robv.android.xposed") ||
                    it.className.startsWith("java.lang.reflect") ||
                    it.className.startsWith("java.lang.Thread") ||
                    it.className.startsWith("dalvik.system") ||
                    it.className.startsWith("java.lang.StackTraceElement")
            }
            .take(12)
            .joinToString("<-") { it.className.substringAfterLast('.') + "." + it.methodName }

    // ------------------------------------------------- 捕获 EnMicroMsg.db（P0）

    /**
     * 装句柄捕获钩子。抄 `core/ContactSqlSource.kt:90-175` 的成熟套路，只把判据换成
     * `rconversation` + `EnMicroMsg.db`。
     *
     * ⛔ **必须挂 `rawQueryWithFactory`，不能挂 `rawQuery`**（R55 复核逐层核实，classes4/10.dex）：
     * ```
     * t0.N() 0x5841a4 → s4.t(...) 0x4a3e5c      （invoke-interface k74.i0->k）
     *   → z84.z.k 0x7b63dc → z84.z.g 0x7b6308   （db 句柄就是 z84.z 实例）
     *   → z84.e.s 0x7b2a88                      （两条分支都只调 rawQueryWithFactory）
     *   → SQLiteDatabase.rawQueryWithFactory 0x4bbf48(4参) / 0x4bbf70(5参)
     * ```
     * `rawQuery`（0x4bbef8）自己就是转发到 `rawQueryWithFactory`，是**上层入口而不是宿主实际调用点**
     * ⇒ 挂 `rawQuery` 等于挂在没人走的路上（上一轮 `capDb` 恒 null 的直接原因）。
     * 换挂后覆盖面只增不减（所有 `rawQuery` 调用方也会被转发进来），所以删掉 `rawQuery` 那一发。
     *
     * 为什么这两道判据都成立、而不是 `lastDb` 那种猜测：
     *  ① `sql` 里已经含 `rconversation` —— 这是**宿主自己**发起的查询，如果这个实例没有这张表，
     *     宿主这条 SQL 自己就会 `no such table`，不可能跑到我们的 before；
     *  ② 路径以 `EnMicroMsg.db` 结尾 —— 排除「别的库里恰好也有同名表」。
     * 两道都是「宿主已证明」而非「我们猜」，所以与 `AntiRevoke.kt:819-826` 禁止的
     * 「最近一次任意表 update 的库」完全不是一类东西。
     *
     * 拿到即卸钩（后续开销归零）；拿不到也不影响功能，[convDb] 会退回
     * `ContactSqlSource.mainDb` / `AntiRevoke.wxDb()`。
     */
    private fun installDbCapture(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (capDb != null || capGaveUp) return
        val cls = runCatching { XposedHelpers.findClass(WCDB, lpparam.classLoader) }.getOrNull()
        if (cls == null) {
            LogX.w("$TAG db capture: $WCDB not found")
            return
        }
        val set = runCatching { XposedBridge.hookAllMethods(cls, "rawQueryWithFactory", captureHook) }
            .getOrNull()
        if (set.isNullOrEmpty()) {
            LogX.w("$TAG db capture: no rawQueryWithFactory overload hooked")
            return
        }
        synchronized(capUnhooks) { capUnhooks.addAll(set) }
        LogX.i("$TAG db capture installed rawQueryWithFactory x${set.size}")
    }

    private val captureHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                // 已拿到 / 已认输 → 卸掉自己（这几行就是卸载前的全部开销）
                if (capDb != null || capGaveUp) {
                    unhookCapture()
                    return
                }
                if (capCalls.incrementAndGet() > CAPTURE_MAX_CALLS) {
                    capGaveUp = true
                    unhookCapture()
                    LogX.w("$TAG db capture gave up after $CAPTURE_MAX_CALLS calls (rconversation 未命中)")
                    return
                }
                // ⚠️ 签名（classes10.dex，两个重载都已核实）：
                //    rawQueryWithFactory(CursorFactory, String sql, Object[] bindArgs, String editTable)
                //    rawQueryWithFactory(CursorFactory, String sql, Object[] bindArgs, String editTable, CancellationSignal)
                // ⇒ **SQL 在 args[1]**，args[0] 是 CursorFactory（上一轮取 args[0] 只对 `rawQuery` 成立）
                val sql = param.args?.getOrNull(1) as? String ?: return
                // ① 只认「宿主自己在查 rconversation」的这一发
                if (!sql.contains(TABLE)) return
                val db = param.thisObject ?: return
                // ② 再认文件：rconversation 只存在于 EnMicroMsg.db
                val path = runCatching { XposedHelpers.callMethod(db, "getPath") as? String }.getOrNull()
                if (path == null) {
                    // 判据②拿不到 ⇒ **不认领**（宁可走 ContactSqlSource.mainDb 那条已验证的路，
                    // 也不要一个可能认错的库：别的库里恰好有同名表就会查错数据）
                    LogX.w(
                        "$TAG db capture: getPath failed on ${db.javaClass.name}, " +
                            "skip sql=${sql.take(60)}"
                    )
                    return
                }
                if (!path.endsWith(DB_MARK)) {
                    if (!capPathWarned) {
                        capPathWarned = true
                        LogX.w("$TAG db capture: path mismatch path=$path (expect *$DB_MARK)")
                    }
                    return
                }
                capDb = db
                LogX.i("$TAG db captured path=$path")
                unhookCapture()
            }.onFailure {
                // 捕获是附加收益，绝不能因为它把微信自己的查询搞挂
                LogX.w("$TAG db capture err: ${it.javaClass.simpleName}: ${it.message?.take(60)}")
            }
        }
    }

    private fun unhookCapture() {
        val list = synchronized(capUnhooks) {
            if (capUnhooks.isEmpty()) return
            ArrayList(capUnhooks).also { capUnhooks.clear() }
        }
        for (u in list) runCatching { u.unhook() }
    }

    // ------------------------------------------------- Hook A：认领页面 + 组名/标题

    /**
     * `onActivityCreated` after：此时 `initView()` 已跑完（标题被设成「折叠的群聊」），
     * 我们在它之后覆盖标题；数据加载还要等 `onResume()` 调 `adapter.N()`，见 Hook B。
     */
    private fun hookPageCreated(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                FMUI, lpparam.classLoader, "onActivityCreated", Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { claim(param.thisObject) }
                            .onFailure {
                                LogX.w("$TAG hookA fail: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
                            }
                    }
                }
            )
            LogX.i("$TAG hookA installed")
        }.onFailure {
            LogX.w("$TAG hookA install fail: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
        }
    }

    private fun claim(fragment: Any) {
        val act = runCatching { XposedHelpers.callMethod(fragment, "getActivity") as? Activity }.getOrNull()
        if (act == null) {
            LogX.w("$TAG claim: no activity")
            return
        }
        val raw = runCatching { act.intent?.getStringExtra(EXTRA_USER) }.getOrNull()
        val gid = gidOfMark(raw)
        if (gid == null) {
            // 微信原生「折叠的群聊」：把状态清干净，Hook B 会原样放行
            reset()
            LogX.d("$TAG native page, extra=$raw")
            return
        }

        // ⛔ P0：拿不到库句柄就**不要认领**这一页。
        //    否则就是「标题是我们的 + 列表是微信原生的」错位态，用户会以为分组数据错了；
        //    整页保持原生「折叠的群聊」至少是自洽的（一眼看出功能没生效），且不动任何数据。
        if (convDb() == null) {
            reset()
            LogX.w("$TAG claim: no db, treat as native page (gid=$gid)")
            return
        }

        // ⛔ adapter 拿不到就**不要认领**：Hook B 的实例比对（`thisObject !== activeAdapter.get()`）
        //    恒不成立 ⇒ 只会得到「标题是我们的 + 列表是原生的」错位态，比整页原生更难排查。
        //    这里连标题都不设（reset 后 return，后面没有 applyTitle）。
        val adapter = runCatching { XposedHelpers.getObjectField(fragment, "adapter") }
            .onFailure { LogX.w("$TAG claim: adapter field missing: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (adapter == null) {
            reset()
            LogX.w("$TAG claim: adapter missing, treat as native page (gid=$gid)")
            return
        }

        val db = GroupDb.current() ?: runCatching { GroupDb.open(act) }.getOrNull()
        if (db == null) {
            reset()
            LogX.w("$TAG claim: db not ready, gid=$gid")
            return
        }

        // 成员集合是 Hook B 的唯一输入；空集合也必须认领（页面显示空列表，而不是退回原生列表）
        val members = runCatching { db.memberWxids(gid).filter { it.isNotBlank() } }.getOrElse {
            LogX.w("$TAG claim: memberWxids failed: ${it.javaClass.simpleName}")
            emptyList()
        }
        val name = runCatching {
            db.listGroups().firstOrNull { it.id == gid }?.name
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: GroupConvPlan.usernameOf(gid)

        activeMembers = members
        activeName = name
        activePage = WeakReference(act)
        activeFragment = WeakReference(fragment)
        activeAdapter = WeakReference(adapter)
        activeGid = gid            // 最后写 gid：Hook B 先读它，读到即代表上面几个字段已就绪

        // 标题先只给组名：(N) 由 Hook B 拿到游标后补（同一行数不必在主线程查两遍）
        applyTitle(act, fragment, name, null)

        // ⛔ 不能只等 Hook B 的 `t0.N()` 来装数据。真机链路（21:10）是：
        //    `initView` 里的 `w8.getCount` → `q()` → `L()` → `N()` 早就跑过一次，
        //    但那次发生在 `onActivityCreated` 内部、**早于**本 after-hook ⇒ 当时 `activeGid`
        //    还是 0，Hook B 按设计放行；之后 `onResume` 是否再调一次 `N()` 受 `isFirstOnResume`
        //    影响（静态分不出来），**实测没有触发** ⇒ 页面就停在原生空态「暂无群聊」。
        //    这里 activeMembers/Name/Page/Fragment/Adapter 已全部就绪，主动换一次数据。
        // ⛔ 直接调 `replaceData`，【不要】反射调 `adapter.N()`：那会绕回我们自己的 Hook B。
        // ⛔ Hook B 必须保留：从聊天页返回等后续刷新仍要靠它接管。
        val reloaded = runCatching { replaceData(adapter) }
            .onFailure {
                LogX.w("$TAG claim: direct reload fail: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
            }
            .getOrDefault(false)
        LogX.i("$TAG claim gid=$gid name=$name members=${members.size}")
        // 放在 claim 行之后：真机日志顺序为 claim → reload → direct reload
        LogX.i("$TAG claim direct reload=$reloaded gid=$gid")
        // 必须在数据装好之后挂：见 [installRowClick] 的注释（宿主 handler 读它自己的缓存，会空转）
        installRowClick(fragment)
    }

    // ------------------------------------------------------------- 行点击（R55f）

    /** 已挂过我方点击的 ListView（同一个 fragment 只挂一次，避免每次刷新重复 set）。 */
    private var clickList = WeakReference<Any>(null)

    /**
     * 自挂行点击。
     *
     * 为什么宿主自己的不行（真机 22:12 定论）：点行后
     * `AdapterView.performItemClick pos=0 item=r4 own=true` 确实 fire 了，
     * 说明点击到了、`getItem()` 也拿得到行对象，**是宿主 handler 执行完不跳转** ——
     * 它的跳转逻辑读的是 `q()` 内部建的那份行缓存/状态，我们只换字段 `f`（游标）绕过了它。
     * ⇒ 用游标里的 `username` 自己起 `ChattingUI`（与本页原生行为等价）。
     *
     * 边界：
     * - 只在本页（已认领）挂；native 折叠页不受影响（`claim` 没跑就不会到这里）。
     * - 游标已被关（宿主 `x1.run` 会关）或位置越界 ⇒ 直接返回，不崩。
     * - 行 talker 若是**我方的伪会话**（`wxgrp_*`）⇒ 不进聊天，转开分组页（防止 A 组套 B 组）。
     */
    private fun installRowClick(fragment: Any) {
        val lv = runCatching { XposedHelpers.getObjectField(fragment, "appbrandMessageLV") }.getOrNull()
            ?: return run { LogX.w("$TAG click: appbrandMessageLV missing") }
        if (clickList.get() === lv) return
        clickList = WeakReference(lv)
        val ok = runCatching {
            XposedHelpers.callMethod(
                lv, "setOnItemClickListener",
                android.widget.AdapterView.OnItemClickListener { _, view, position, _ ->
                    onRowClick(view, position)
                }
            )
        }.onFailure {
            LogX.w("$TAG click: setOnItemClickListener fail: ${it.javaClass.simpleName}: ${it.message?.take(60)}")
        }.isSuccess
        LogX.i("$TAG click installed ok=$ok")
    }

    private fun onRowClick(view: View?, position: Int) {
        // 复位边界：`reset()` 把 gid 归零后，这个监听器还挂在该 View 上（View 生命周期比认领长），
        // 不判就会用已经清空的 activeMembers 去查库、甚至误开聊天。
        if (activeGid <= 0L) return
        val ctx = view?.context
        if (ctx == null) {
            LogX.w("$TAG click: null context pos=$position")
            return
        }
        val talker = runCatching {
            val cur = ensureCursor() ?: return@runCatching null
            if (!cur.moveToPosition(position)) return@runCatching null
            cur.getString(cur.getColumnIndexOrThrow("username"))
        }.getOrNull()
        if (talker.isNullOrBlank()) {
            LogX.w("$TAG click: no talker pos=$position")
            return
        }
        // 我方的伪会话不进聊天（否则会开到一个空聊天页）
        val gid = GroupConvPlan.groupIdOf(talker)
        if (gid != null) {
            LogX.i("$TAG click pos=$position own-group gid=$gid → gpage")
            runCatching { open(ctx, gid) }
            return
        }
        // 启动形状：**同任务、不加 flag**（行 View 的 context 就是本页 Activity）。
        //
        // R59 已定案的部分（真机 extras 对比，同机同版本 8.0.48）：
        //   原生 `ChatroomContactUI → ChattingUI` 传
        //   `{Chat_Mode=1, chat_from_scene_for_group_chats=4, specific_chat_from_scene=10,
        //     finish_direct=true}`；我方原先只有 `{Chat_User, Chat_Mode=1}`。
        //   缺 `finish_direct` 的后果：ChattingUI 返回时发
        //   `startActivityForResult(LauncherUI, FLAG_ACTIVITY_CLEAR_TOP)`（栈
        //   `FragmentActivity.startActivityFromFragment ← a.b ← ChattingUI.startActivityForResult`），
        //   **AMS 侧**把中间的 ConvBox 一起清掉（所以 `Activity.finish` 钩子抓不到它）。
        //   本轮只补 `finish_direct` 做最小验证；另外两个 scene 键先不动（它们更可能是埋点/动效）。
        val ok = runCatching {
            val i = Intent().apply {
                setClassName(HOST, CHATTING)
                putExtra("Chat_User", talker)
                putExtra("Chat_Mode", 1)
                putExtra("finish_direct", true)
                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        }.onFailure {
            LogX.w("$TAG click: start ChattingUI fail: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
        }.isSuccess
        LogX.i("$TAG click pos=$position talker=$talker ok=$ok")
    }

    /**
     * 标题：优先 `BaseConversationFmUI.setMMTitle(String)`（走宿主自己的 ActionBar 更新链），
     * 失败再退到 `Activity.setTitle`。`updateTitle` 内部会拿字符串当 username 查显示名，
     * 查不到原样返回，所以「美妆 (3)」这种自定义串是安全的。
     */
    private fun applyTitle(act: Activity, fragment: Any?, name: String, rows: Int?) {
        // P2：WeakReference 可能已失效 / 页面正在销毁 —— 此时设标题没意义，还可能踩壳
        if (act.isFinishing || act.isDestroyed) {
            LogX.w("$TAG title skip: page finishing name=$name")
            return
        }
        val title = if (rows == null || rows < 0) name else "$name ($rows)"
        val viaFragment = fragment != null && runCatching {
            XposedHelpers.callMethod(fragment, "setMMTitle", title)
            true
        }.getOrDefault(false)
        if (!viaFragment) {
            runCatching { act.title = title }
                .onFailure { LogX.w("$TAG title fail: ${it.javaClass.simpleName}") }
        }
    }

    // ------------------------------------------------- Hook B：换数据源

    /**
     * `t0.N()` before：原生体是
     * `cursor = s4.t(5, null, "conversationboxservice", -1)` + `W(cursor)` +
     * `listener.a(null)`（→ `setShowView`）+ `notifyDataSetChanged()`，
     * 我们完全替掉它：按**我们组的成员**查 `rconversation`，再喂回同一个 adapter。
     *
     * 为什么保留 `W(Cursor)` 而不是往上塞 `List<r4>`：`d2.getView` 只认游标
     * （行文字 / 时间 / 未读全是 `Y(r4, Cursor)` 从当前行取的），换 List 等于重写渲染。
     *
     * 为什么查 `rconversation` 而不是只信内存分组：分组里存的是 wxid（好友 / 群），
     * 这些 wxid 是否真有会话、最后一条是什么，只有宿主会话表知道。
     *
     * 失败即放行（不 setResult）：宁可显示微信原生的「折叠的群聊」列表，也不能空白 / 闪退。
     */
    private fun hookAdapterRefresh(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mm.ui.conversation.t0", lpparam.classLoader, "N",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (activeGid <= 0L) return          // 原生页 / 未认领：原样放行（不加日志，太吵）
                        // 只接管「本页」的 adapter：同类的其它实例（原生折叠页）必须放行
                        if (param.thisObject !== activeAdapter.get()) {
                            LogX.w("$TAG hookB skip: adapter mismatch gid=$activeGid")
                            return
                        }
                        LogX.i("$TAG hookB fire gid=$activeGid")
                        val ok = runCatching { replaceData(param.thisObject) }
                            .onFailure {
                                LogX.w("$TAG hookB fail: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
                            }
                            .getOrDefault(false)
                        if (ok) param.result = null          // 整体替掉原生查询
                    }
                }
            )
            LogX.i("$TAG hookB installed")
        }.onFailure {
            LogX.w("$TAG hookB install fail: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
        }
        hookCursorSwap(lpparam)
    }

    /**
     * R55d 定案：**只钩 `t0.N()` 不够** —— 真机调用栈实证宿主的刷新绕开了它：
     * `AdapterView$AdapterDataSetObserver.onChanged` → `w8.getCount` → `w8.q` → `W(宿主游标)`
     * （我方 `notifyDataSetChanged()` 之后 0.6 秒就发生），把 adapter 的游标换回 0 行版本。
     *
     * ⇒ 在**唯一的汇合点** `w8.W(Cursor)` 上做「换回我方游标」：
     * 宿主自己查完之后必然走这里，把入参临时替换掉，等于让它的每一次刷新都喂我方数据。
     *
     * 三条边界：
     * - 未认领（`activeGid<=0`）/ 不是本页 adapter ⇒ 原样放行（原生折叠页不受影响）。
     * - 入参就是我方游标（自己 `replaceData` 调进来的）⇒ 放行，**不递归**。
     * - 入参为 null ⇒ 放行（宿主走错误分支时别硬塞）。
     */
    private fun hookCursorSwap(lpparam: XC_LoadPackage.LoadPackageParam) {
        // ② `w8.W(Cursor)` —— 宿主每次查完必然走这里（栈：`AdapterDataSetObserver.onChanged`
        //    → `getCount` → `q` → `W`）。把入参换成我方游标，等于让它的每次刷新都喂我方数据。
        //
        //    ⛔ 走过的弯路（2026-11-16 真机崩过两次，别再试）：
        //    ① 只钩 `t0.N()`：挡不住 —— 刷新根本不走 `N()`（`hookB` 全程 0 次 fire）。
        //    ② 另钩 `w8.q()` 并用 `param.result=null` 跳过原生查询：**必崩**
        //       `NullPointerException: Cursor.moveToPosition on null`
        //       （`w8.getItem` ← `d2.getView`）：`q()` 不只是查库，它还负责把 `f` 置好 /
        //       维护 `c/d` 状态位；整段跳过会让 `f` 留在 null，第一次布局就炸。
        //    ⇒ 只允许在 `W()` 这一层「换入参」，**不改控制流**。
        runCatching {
            val cls = XposedHelpers.findClass("com.tencent.mm.ui.w8", lpparam.classLoader)
            val set = XposedBridge.hookAllMethods(cls, "W", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (activeGid <= 0L) return
                    if (param.thisObject !== activeAdapter.get()) return
                    val inCur = param.args.getOrNull(0) as? Cursor ?: return
                    // 我方游标可能已被宿主上一轮 `q()` 里的清理逻辑关掉 ⇒ 现查一条活的
                    val cur = ensureCursor() ?: return
                    if (inCur === cur) return
                    param.args[0] = cur
                    LogX.i(
                        "$TAG hookW swap gid=$activeGid hostRows=${rowsOf(inCur)} " +
                            "mineRows=${rowsOf(cur)} hostCurId=${System.identityHashCode(inCur)}"
                    )
                }
            })
            LogX.i("$TAG hookW installed n=${set.size}")
        }.onFailure {
            LogX.w("$TAG hookW install fail: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
        }
    }

    /**
     * 取一条**活着的**我方游标：`activeCursor` 还在就直接用；已被关掉（宿主 `q()` 里的 `k()`
     * 会 close 字段 `f`，也就是关掉我们塞进去的那条）就按当前组重查一条并登记。
     *
     * 真机踩过：不判 `isClosed` 就反复注入死游标 ⇒ 界面乱跳、`mineRows=-2` 刷屏。
     */
    private fun ensureCursor(): Cursor? {
        val cached = activeCursor.get()
        if (cached != null && !cached.isClosed) return cached
        val fresh = query(selectSql(activeMembers)) as? Cursor ?: return null
        activeCursor = WeakReference(fresh)
        return fresh
    }

    /** 游标行数，已关闭 / 抛异常一律 -1（只给日志用）。 */
    private fun rowsOf(c: Cursor): Int = runCatching { if (c.isClosed) -2 else c.count }.getOrDefault(-1)

    private fun replaceData(adapter: Any): Boolean {
        val members = activeMembers
        val sql = selectSql(members)
        val cursor = query(sql) ?: return false
        val w = wMethod(adapter) ?: run {
            closeQuietly(cursor)
            LogX.w("$TAG reload fail: W not found on ${adapter.javaClass.name}")
            return false
        }
        // ⛔ P1-3：`w8.W(Cursor)` 方法体只有 `f = cursor; i = -1`，**不 close 旧游标**
        //    （code=0x598e78）；只有 `w8.k()` 会 close，而它只被 `d2.p()` / `d2.m0()`
        //    （onDestroy，只关最后一个）调到 ⇒ 每次 onResume 都漏一个 CursorWindow。
        //    ⛔ 必须直接读 public 字段 `f`，**不能用** `w8.q()`：`f == null` 时 `q()` 会调
        //    `L()` → `N()`，递归回到我们自己的 hook。
        val old = runCatching { XposedHelpers.getObjectField(adapter, "f") }
            .onFailure { LogX.w("$TAG hookB: adapter.f missing: ${it.javaClass.simpleName}") }
            .getOrNull()
        runCatching { w.invoke(adapter, cursor) }.onFailure {
            closeQuietly(cursor)
            throw it
        }
        if (old != null && old !== cursor) closeQuietly(old)
        // 必须在 `notifyDataSetChanged()` **之前**登记：观察者回调会立刻 `getCount()` → `q()`
        // → `W(宿主游标)`，那时 `hookW` 要靠这条引用把入参换回来。
        activeCursor = WeakReference(if (cursor is Cursor) cursor else null)

        val rows = intOf(cursor, "getCount")
        // ⛔ R55d 定案（真机 21:39 / 21:42 追踪实证）：`W()` 只做 `f=cursor; i=-1` 就返回，
        //    **`i` 是 count 缓存**。`getCount()` 看到 `i<0` 立刻走 `q()` → 真查库 → `W(空游标)`
        //    ⇒ 替换后 3 秒内游标就被换回宿主那条 0 行的，界面回到「暂无群聊」（tip 又变 VISIBLE）。
        //    ⇒ 只调 `W()` 等于只换了一半：必须把 `i` 补成真实行数，`getCount()` 才不会重查。
        runCatching { XposedHelpers.setIntField(adapter, "i", rows ?: -1) }
            .onFailure {
                LogX.w("$TAG reload: set cache i fail: ${it.javaClass.simpleName}: ${it.message?.take(60)}")
            }
        XposedHelpers.callMethod(adapter, "notifyDataSetChanged")

        // 诊断：分清「游标有行」与「adapter 承认有行」。
        val cnt = runCatching { XposedHelpers.callMethod(adapter, "getCount") as? Int }.getOrNull()
        LogX.i("$TAG reload count cursorRows=${rows ?: -1} adapterCount=${cnt ?: -1}")
        // P1-2：原生 `t0.N()` 的 `listener.a(null)` 会走到 `setShowView`，
        // 控制 `emptyTipTv` 与 ListView 的可见性；整体替掉 N() 后这句就不执行了 ⇒ 自己补一次。
        showView(rows)
        val act = activePage.get()
        if (act != null && activeName.isNotEmpty()) {
            applyTitle(act, activeFragment.get(), activeName, rows)
        }
        LogX.i(
            "$TAG reload gid=$activeGid rows=${rows ?: -1} " +
                "oldCursor=${when { old == null -> "none"; old === cursor -> "same"; else -> "closed" }} " +
                "sqlLen=${sql.length} newCurId=${System.identityHashCode(cursor)}"
        )
        return true
    }

    /**
     * 补原生 `i0.a(Object)` → `access$100(count)` → `setShowView(count)` 这一跳。
     *
     * 签名已从 dex 核实（classes4.dex，见 `docs/_r55_fix.md`）：
     * `Lcom/tencent/mm/ui/conversation/ConvBoxServiceConversationUI$ConvBoxServiceConversationFmUI;`
     * `setShowView(I)V  flags=0x2(private) code=0x564b44` —— **只有 int 一个重载，没有 boolean 版**，
     * 方法体只有 4 个 `View.setVisibility` ⇒ 语义就是「count<=0 显示空态，否则显示列表」。
     */
    private fun showView(rows: Int?) {
        // 行数取不到时不改可见性：宁可维持现状，也不要按 0/1 猜
        val n = rows ?: return
        val frag = activeFragment.get() ?: return
        // `setShowView(I)V`（private，classes4.dex code=0x564b44）语义是正常的：
        //   0003: if-gtz v3, +13   // ⚠️ Dalvik `if-gtz` = 「n > 0 就跳」
        //   n > 0  → 0x0010 → emptyTipTv=GONE、appbrandMessageLV=VISIBLE
        //   n <= 0 → 落空   → emptyTipTv=VISIBLE、appbrandMessageLV=GONE
        // （R55 一度把它读成反过来，浪费了一轮；真机可见的「暂无群聊」其实是另一个 view `dqm`，
        //   不是 `emptyTipTv`，所以换可见性治不了它。）
        runCatching {
            val m = frag.javaClass.getDeclaredMethod("setShowView", Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(frag, n)
        }.onFailure {
            LogX.w("$TAG showView: setShowView fail: ${it.javaClass.simpleName}: ${it.message?.take(60)}")
        }
        // 诊断：把 ListView 的真实状态打出来，分清「没设置上」「设置上了但没布局」「绑的不是我们的 adapter」
        runCatching {
            val list = XposedHelpers.getObjectField(frag, "appbrandMessageLV")
            val ownAdapter = XposedHelpers.getObjectField(frag, "adapter")
            val ad = XposedHelpers.callMethod(list, "getAdapter")
            val vis = XposedHelpers.callMethod(list, "getVisibility") as? Int ?: -1
            val cnt = XposedHelpers.callMethod(list, "getCount") as? Int ?: -1
            val child = XposedHelpers.callMethod(list, "getChildCount") as? Int ?: -1
            val tip = XposedHelpers.getObjectField(frag, "emptyTipTv")
            val tipVis = XposedHelpers.callMethod(tip, "getVisibility") as? Int ?: -1
            LogX.i(
                "$TAG showView n=$n listVis=$vis listCount=$cnt child=$child " +
                    "tipVis=$tipVis adapterOurs=${ad === ownAdapter} adapterCls=${ad?.javaClass?.simpleName}"
            )
        }.onFailure {
            LogX.w("$TAG showView: probe fail: ${it.javaClass.simpleName}: ${it.message?.take(60)}")
        }
    }

    /**
     * R55d 诊断：把当前认领的 FmUI 的**所有 View 字段**dump 出来（只读，不改任何状态）。
     *
     * 存在理由：真机 `showView n=1 listVis=0 listCount=1 child=0 adapterOurs=true`，
     * 但视图树里只有 `TextView dqm '暂无群聊'`、**没有任何 ListView 节点**。要分清三件事：
     *   ① `appbrandMessageLV` 是死字段（页面真正渲染用的是另一个字段或另一个 ListView）；
     *   ② 它是活字段但从未 attach 到窗口（`at=false` ⇒ 孤儿，adapter 有数据也不显示）；
     *   ③ 它 attach 了、但被某个 GONE 容器藏着。
     * 判据：`at=false` 或 `par=-` ⇒ 孤儿；`at=true` 且 `vis=0` 还看不见 ⇒ 被盖住/尺寸为 0。
     */
    internal fun dumpViews(): List<String> {
        val frag = activeFragment.get() ?: return listOf("$TAG views: no active fragment")
        val out = mutableListOf<String>()
        out.add("$TAG views frag=${frag.javaClass.name} gid=$activeGid")
        var c: Class<*>? = frag.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (!View::class.java.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                val v = runCatching { f.get(frag) }.getOrNull() as? View
                if (v == null) {
                    out.add("$TAG views ${c.simpleName}.${f.name} = null")
                    continue
                }
                val id = runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault("-")
                val par = v.parent?.javaClass?.simpleName ?: "-"
                val ch = (v as? android.view.ViewGroup)?.childCount ?: -1
                val cnt = (v as? android.widget.ListView)?.count ?: -1
                out.add(
                    "$TAG views ${c.simpleName}.${f.name} cls=${v.javaClass.simpleName} id=$id " +
                        "vis=${v.visibility} at=${v.isAttachedToWindow} par=$par cnt=$cnt ch=$ch " +
                        "wh=${v.width}x${v.height}"
                )
            }
            c = c.superclass
        }
        // adapter 内部状态（`w8` 字段名已核实：`f`=游标、`i`=count 缓存）。
        // 判据：`curRows=1` 而 `count=0` ⇒ 缓存脏；`curRows=0/closed` ⇒ **游标被宿主刷回去了**
        // （说明刷新走的是我们没钩的那条路，例如 `L()` / `q()`）。
        val ad = activeAdapter.get()
        if (ad == null) {
            out.add("$TAG views adapter = null")
        } else {
            val cur = runCatching { XposedHelpers.getObjectField(ad, "f") as? Cursor }.getOrNull()
            val cacheI = runCatching { XposedHelpers.getObjectField(ad, "i") }.getOrNull()
            val count = runCatching { XposedHelpers.callMethod(ad, "getCount") }.getOrNull()
            val curRows = if (cur == null) -1 else runCatching { cur.count }.getOrElse { -1 }
            // R55d：`getView` 走字段 `f`（游标）渲染，但 `getItem(pos)` 走的是另一份行缓存
            // （`c`，`A(List)` 建出来的）。两者不一致时表现就是「列表看得见、点行没反应」。
            val cSize = runCatching {
                (XposedHelpers.getObjectField(ad, "c") as? Collection<*>)?.size ?: -9
            }.getOrDefault(-10)
            val item0 = runCatching { XposedHelpers.callMethod(ad, "getItem", 0) }.getOrNull()
            // 「列表看得见、点行没反应」的三种可能：① ListView 没装 OnItemClickListener；
            // ② 行 View 自己没挂 click 监听；③ 两者都有但 handler 里读不到数据。
            val listClick = runCatching {
                val lv = XposedHelpers.getObjectField(frag, "appbrandMessageLV")
                XposedHelpers.callMethod(lv, "getOnItemClickListener") != null
            }.getOrDefault(false)
            val rowClick = runCatching {
                val lv = XposedHelpers.getObjectField(frag, "appbrandMessageLV")
                val c0 = XposedHelpers.callMethod(lv, "getChildAt", 0) as? View
                when {
                    c0 == null -> "noChild"
                    c0.hasOnClickListeners() -> "row=1"
                    else -> "row=0"
                }
            }.getOrDefault("-")
            out.add(
                "$TAG views adapter cls=${ad.javaClass.simpleName} count=$count cacheI=$cacheI " +
                    "curRows=$curRows closed=${cur?.isClosed ?: "null"} " +
                    "curId=${if (cur == null) 0 else System.identityHashCode(cur)} " +
                    "cSize=$cSize item0=${item0?.javaClass?.simpleName ?: "null"} " +
                    "listClick=$listClick $rowClick"
            )
        }
        return out
    }

    /**
     * `com.tencent.mm.ui.w8.W(android.database.Cursor)V`（public，非 static）。
     * 不按名字硬调 `callMethod("W", cursor)` 的原因：显式按「一个参数且是 Cursor 子类」筛，
     * 再 setAccessible 兜住将来的可见性变化。
     */
    private fun wMethod(adapter: Any): Method? = runCatching {
        adapter.javaClass.methods.firstOrNull {
            it.name == "W" && it.parameterTypes.size == 1 &&
                Cursor::class.java.isAssignableFrom(it.parameterTypes[0])
        }?.apply { isAccessible = true }
    }.getOrNull()

    // ------------------------------------------------- Hook C：复位

    /** 离开页面即清零：否则下一次微信原生「折叠的群聊」会被我们的成员集合污染。 */
    private fun hookPageDestroy(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                FMUI, lpparam.classLoader, "onDestroy",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching { reset() }
                            .onFailure { LogX.w("$TAG hookC fail: ${it.javaClass.simpleName}") }
                    }
                }
            )
            LogX.i("$TAG hookC installed")
        }.onFailure {
            LogX.w("$TAG hookC install fail: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
        }
    }

    private fun reset() {
        activeGid = 0L
        activeMembers = emptyList()
        activeName = ""
        activePage = WeakReference(null)
        activeFragment = WeakReference(null)
        activeAdapter = WeakReference(null)
        // 复位边界：`hookW` 只在 `activeCursor` 非空时才换游标，页面退出必须清掉，
        // 否则下一个原生折叠页（未认领，但 `activeGid` 归零已挡住）之外的路径可能拿到旧游标。
        activeCursor = WeakReference<Cursor>(null)
    }

    // ---------------------------------------------------------------- SQL / 游标

    /**
     * 只读 `rconversation`，条件 = 组内成员的 username。
     *
     * `substr(username,1,6)<>'wxgrp_'` 排除我方伪会话行（分组行本身就在 `rconversation` 里，
     * 不排掉就会「分组页里再出现一行分组」）。用 substr 而非 LIKE：`_` 是单字符通配。
     * 成员为空时给 `1=0`：`in ()` 是语法错误，而空列表必须是一张**空表**而不是报错。
     *
     * ⚠️ 成员 wxid 按 [GroupConvPlan.quote] 转义，避免昵称里的引号闭合字符串。
     */
    private fun whereClause(members: List<String>): String {
        if (members.isEmpty()) return "1=0"
        val sb = StringBuilder("username in (")
        members.forEachIndexed { i, w ->
            if (i > 0) sb.append(',')
            sb.append(GroupConvPlan.quote(w))
        }
        sb.append(") and substr(username,1,6)<>'wxgrp_'")
        return sb.toString()
    }

    private fun selectSql(members: List<String>): String =
        "select * from $TABLE where ${whereClause(members)} order by flag desc, conversationTime desc"

    /**
     * 用宿主自己的 wcdb 句柄建游标（**不能**用模块的 AOSP SQLiteDatabase：
     * [convDb] 给的是 `com.tencent.wcdb.database.SQLiteDatabase`，强转恒 null）。
     *
     * Method 每次现找、不缓存：AOSP 的 Method 拿去调 wcdb 实例会抛 IllegalArgumentException
     * （与 `core/ChatroomSource.kt` / [GroupConvStore] 同一条教训）。
     */
    private fun query(sql: String): Any? {
        // 失败原因四种分开打，真机上按一行就能定位（no db / no rawQuery method / rawQuery threw）
        val db = convDb() ?: run {
            LogX.w("$TAG query fail: no db")
            return null
        }
        val method = rawQueryMethod(db) ?: run {
            LogX.w("$TAG query fail: no rawQuery method on ${db.javaClass.name}")
            return null
        }
        return runCatching { method.invoke(db, sql, null) }
            .onFailure {
                // invoke 把目标异常包成 InvocationTargetException，真正的原因在 cause 里
                LogX.w(
                    "$TAG query fail: rawQuery threw: ${it.javaClass.simpleName}: " +
                        "${(it.cause ?: it).message?.take(80)}"
                )
            }
            .getOrNull()
    }

    private fun rawQueryMethod(db: Any): Method? = runCatching {
        db.javaClass.methods.firstOrNull {
            it.name == "rawQuery" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                (it.parameterTypes[1] == Array<String>::class.java ||
                    it.parameterTypes[1] == Array<Any>::class.java)
        }?.apply { isAccessible = true }
    }.getOrNull()

    private fun intOf(cursor: Any, name: String, vararg args: Any): Int? =
        runCatching { XposedHelpers.callMethod(cursor, name, *args) as? Int }.getOrNull()

    private fun closeQuietly(cursor: Any) {
        runCatching { XposedHelpers.callMethod(cursor, "close") }
    }
}
