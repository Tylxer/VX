package com.lx.wxgroup.probe

import android.os.Handler
import android.os.Looper
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.util.LogX
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * S5① 取证探针：定位「微信撤回消息」到底走哪个方法处理。
 *
 * ## 为什么不猜类名
 * 撤回相关的类在 8.0.48 里全是混淆名（`va4.m4` 这种），猜不出来；而 DexKit 的
 * `libdexkit.so` 在本机一直加载不到（见 HookEntry 里的注释），所以改用
 * **行为取证**：不管处理类叫什么，撤回最终一定会落到两件必然发生的事上 ——
 *
 * 1. **写库**：把原消息改成「xxx 撤回了一条消息」（type 10000）或把它删掉。
 *    → hook WCDB / SQLite 的 update / delete / insert / execSQL，
 *      只看涉及 `message` 等表的调用，把调用栈打出来，栈里就有处理类。
 * 2. **渲染**：聊天页一定会 `TextView.setText("...撤回了一条消息")`。
 *    → hook `TextView.setText/append`，命中「撤回」即打栈，反向定位提示消息从哪来。
 *
 * 两条互为交叉验证：写库那条给出**处理入口**，渲染那条给出**提示消息**的落点。
 *
 * ## 用法（装一次 APK 可反复开关）
 * ```
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op revoke --es sub on --ei sec 120 -p com.tencent.mm
 *   # 在任意聊天里发一条消息再撤回（自己撤回即可，链路与对方撤回同一条）
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op revoke --es sub off -p com.tencent.mm
 * adb pull /sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt
 * ```
 * 日志行前缀 `RV`。`sub=stat` 看当前装了几个钩 / 命中多少种调用点。
 *
 * ⚠️ 三条纪律：
 * 1. **窗口到期会自动 flush + 卸载全部钩子**（钩子挂在写库和 TextView 上，
 *    残留会永久拖慢微信）；`off` 也能随时卸。
 * 2. hook 了写库热路径，**只在取证期间开**，取完证一定 `off`。
 * 3. 取证钩子内部全部 try/catch，出错只记一行、绝不向上抛 —— 不能影响宿主。
 */
object RevokeHuntProbe {

    private const val LOG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"

    /**
     * 栈深度。取证要的是「混淆类之上那个稳定的中间层」（一般埋在 12–20 层），
     * 12 层只能看到 wcdb 自己的帧，必须取深一点。
     */
    private const val FRAMES = 28

    /** 单次窗口最多打印多少条明细，防止日志把磁盘写满（挂了查询钩子后种类会变多） */
    private const val MAX_LINES = 320

    /** 窗口上限：钩子挂在写库路径上，开太久会一直拖着微信 */
    private const val MAX_WINDOW_SEC = 180

    private var loader: ClassLoader? = null

    private val unhooks = Collections.synchronizedList(mutableListOf<XC_MethodHook.Unhook>())

    @Volatile
    private var until = 0L

    /** key = 签名 + 参数摘要 + 调用点，value = 命中次数（同一调用点只打一次，后续累加） */
    private val counts = ConcurrentHashMap<String, Int>()

    /** 被过滤掉（不感兴趣）的调用总数，用来判断「探针装了但没命中」还是「压根没调用」 */
    private val passed = AtomicInteger()

    private val printed = AtomicInteger()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 到期自动 flush + 卸载：忘记发 off 也不会把钩子留在宿主里 */
    private val expireTask = Runnable {
        runCatching {
            flushInternal()
            uninstall()
            own("RV window expired: all hooks removed")
        }
    }

    private val NOISE = arrayOf(
        "de.robv.android.xposed",
        "com.lx.wxgroup",
        "java.lang.reflect",
        "dalvik.system",
        "android.os.Handler",
        "com.android.internal.os",
        "kotlin.coroutines",
        "kotlinx.coroutines",
    )

    /** 命中即无条件记录（不区分哪张表）——撤回的特征串 */
    private val KEYWORDS = listOf("revoke", "撤回", "revokemsg", "replacemsg")

    /**
     * 关心的表。撤回不只是改 `message`：会话摘要（`conversation`/`rcontact`）
     * 和群聊表也会跟着动，一并放进白名单才看得到完整链路。
     */
    private val TABLE_HINTS = listOf("message", "rcontact", "conversation", "chatroom")

    private val SQL_CLASSES = listOf(
        "com.tencent.wcdb.database.SQLiteDatabase",
        "com.tencent.wcdb.database.SQLiteStatement",
        "android.database.sqlite.SQLiteDatabase",
    )

    /**
     * 只挂能拿到「表名 / SQL」的入口。
     *
     * ⚠️ `SQLiteStatement.executeInsert/executeUpdateDelete` **故意不挂**：
     * 它们走预编译语句，调用时只有 long handle 拿不到 SQL，而且是最热的执行路径，
     * 挂了白拖慢宿主、还给不出信息。撤回那一步一定会走 `update*` / `delete` / `execSQL`
     * 这类带表名的入口，够用了。
     */
    private val SQL_METHODS = listOf(
        "update", "updateWithOnConflict",
        "delete",
        "insert", "insertWithOnConflict", "insertOrThrow",
        "replace", "replaceOrThrow",
        "execSQL",
        // 「显示撤回提示」要隐藏历史提示，落点只能在读取侧（聊天页用自绘控件，
        // TextView.setText 抓不到）。先取证微信到底用什么 SQL 读 message 表。
        "rawQuery", "rawQueryWithFactory", "query",
    )

    /** 只打 SQL、不打调用栈的方法：查询是极热路径，28 帧栈会把日志刷爆 */
    private val NO_STACK_SIGS = listOf("rawQuery", "rawQueryWithFactory", "query")

    /** 平时只留 classLoader，不打任何钩子 */
    fun prepare(lpparam: XC_LoadPackage.LoadPackageParam) {
        loader = lpparam.classLoader
    }

    // ------------------------------------------------------------------ 命令

    fun command(sub: String, sec: Int) {
        when (sub) {
            "on" -> {
                val ms = sec.coerceIn(10, MAX_WINDOW_SEC) * 1000L
                // 装钩放后台线程：十几次 findClass + hook 写库热路径会触发 ART 去优化，
                // 放在广播主线程有 ANR 风险
                Thread {
                    install()
                    mainHandler.post {
                        until = System.currentTimeMillis() + ms
                        counts.clear()
                        passed.set(0)
                        printed.set(0)
                        mainHandler.removeCallbacks(expireTask)
                        mainHandler.postDelayed(expireTask, ms)
                        own("RV on: window=${ms}ms hooks=${unhooks.size}")
                    }
                }.start()
            }

            "off" -> {
                flushInternal()
                uninstall()
                own("RV off: unhooked")
            }

            "flush" -> flushInternal()
            "stat" -> own(
                "RV stat hooks=${unhooks.size} kinds=${counts.size} " +
                    "passed=${passed.get()} windowLeft=${(until - System.currentTimeMillis()).coerceAtLeast(0)}ms"
            )
            "clear" -> runCatching { File(LOG).delete() }
            else -> own("RV unknown sub=$sub")
        }
    }

    // ------------------------------------------------------------------ 安装

    private fun install() {
        val cl = loader
        if (cl == null) {
            own("RV install: no classLoader")
            return
        }
        uninstall()

        val missing = ArrayList<String>()
        for (c in SQL_CLASSES) {
            for (m in SQL_METHODS) all(c, m, sql = true, missing)
        }
        all("android.widget.TextView", "setText", sql = false, missing)
        all("android.widget.TextView", "append", sql = false, missing)

        if (missing.isEmpty()) {
            own("RV install done: ${unhooks.size} hooks, all candidate classes present")
        } else {
            own("RV install done: ${unhooks.size} hooks, missing=${missing.joinToString(",")}")
        }
    }

    private fun uninstall() {
        mainHandler.removeCallbacks(expireTask)
        synchronized(unhooks) {
            for (u in unhooks) runCatching { u.unhook() }
            unhooks.clear()
        }
        until = 0L
        counts.clear()
        passed.set(0)
        printed.set(0)
        stopWriter()
    }

    /** @param sql true = 写库类（按表/SQL 过滤），false = TextView（按文本过滤） */
    private fun all(clsName: String, method: String, sql: Boolean, missing: MutableList<String>): Boolean {
        val cl = loader ?: return false
        val cls = runCatching { XposedHelpers.findClass(clsName, cl) }.getOrNull()
        if (cls == null) {
            if (missing.size < 20) missing.add("$clsName.$method(?)")
            return false
        }
        // api-82 里是 XposedBridge.hookAllMethods（XposedHelpers 上没有同名方法）
        val set = runCatching {
            XposedBridge.hookAllMethods(cls, method, callback("$clsName.$method", sql))
                .toCollection(mutableListOf())
        }.onFailure {
            own("RV  hook-fail $clsName.$method ${it.javaClass.simpleName}")
        }.getOrNull()
        if (set.isNullOrEmpty()) {
            missing.add("$clsName.$method")
            return false
        }
        synchronized(unhooks) { unhooks.addAll(set) }
        return true
    }

    private fun callback(sig: String, sql: Boolean) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                if (System.currentTimeMillis() > until) return
                if (sql) recordSql(sig, param) else recordText(sig, param)
            }.onFailure {
                // 取证钩子本身绝不能影响宿主：出错只记一行、不上抛
                runCatching { own("RV  cb-error $sig ${it.javaClass.simpleName}") }
            }
        }
    }

    // ------------------------------------------------------------------ 记录

    /**
     * 写库记录。
     *
     * ⚠️ **先看第一个参数再决定要不要拼完整参数**：写库是热路径，
     * 无条件 `args.joinToString` 会把每次调用的开销放大好几倍（ContentValues
     * 的 toString 尤其贵）。第一个参数对 update/delete/insert 就是表名、
     * 对 execSQL 就是 SQL 语句，够判断了；只有它没命中时才兜底扫后续参数找关键词。
     */
    private fun recordSql(sig: String, param: XC_MethodHook.MethodHookParam) {
        val args = param.args
        // ⚠️ 不能只看 args[0]：`rawQueryWithFactory(CursorFactory, String sql, ...)`
        // 第一个参数是 factory。SQL / 表名是「第一个 CharSequence 参数」，逐个方法
        // 特殊太脆，统一取第一个 CharSequence 最稳。
        val head = args?.firstOrNull { it is CharSequence }?.toString() ?: ""
        val headLower = head.lowercase()
        val tableHit = TABLE_HINTS.any { headLower.contains(it) }
        var kw = KEYWORDS.firstOrNull { headLower.contains(it) }

        // ⚠️ 先廉价判「是不是目标表」，不是就走人 —— 写库是热路径，
        // 无条件拼参数（ContentValues.toString 很贵）会明显拖慢微信。
        if (!tableHit && kw == null) {
            passed.incrementAndGet()
            return
        }

        // 到这一步才拼参数：撤回是靠 values 里的 type=10000 / content=…撤回了一条消息
        // 才认得出来的，只打表名等于没打。
        val tail = StringBuilder()
        if (args != null) {
            for (i in 1 until args.size) {
                val v = args[i] ?: continue
                val s = when (v) {
                    is Array<*> -> v.joinToString(",") { it?.toString()?.take(30) ?: "null" }
                    else -> v.toString().take(200)
                }
                tail.append(s).append(' ')
                if (tail.length > 300) break
            }
        }
        val tailStr = tail.toString()
        if (kw == null) kw = KEYWORDS.firstOrNull { tailStr.lowercase().contains(it) }
        // 查询类：SQL 本身就是要取的信息，截断到 400 才看得到 where / order / limit
        val isQuery = NO_STACK_SIGS.any { sig.contains(it) }
        val headMax = if (isQuery) 400 else 60
        val detail = head.take(headMax) + " | " + tailStr.take(300)

        val frames = if (isQuery) emptyList() else frames()
        // ⚠️ key 里带上参数摘要：只按前几帧聚合会把「同一处调用改不同表 / 不同内容」
        // 全并成一条，撤回那次可能就被别的写库调用吃掉了。
        val key = "$sig [${if (kw != null) "KW" else "TBL"}] ${detail.take(40)} <- " +
            frames.take(6).joinToString(" <- ")
        val n = counts.merge(key, 1) { a, b -> a + b } ?: 1
        if (n == 1 && printed.get() < MAX_LINES) {
            printed.incrementAndGet()
            val tag = if (kw != null) "[KW:$kw]" else "[TBL]"
            own("RV SQL $sig $tag thread=${Thread.currentThread().name} :: $detail")
            if (!isQuery) own("RV   at " + frames.joinToString(" <- "))
        }
    }

    private fun recordText(sig: String, param: XC_MethodHook.MethodHookParam) {
        val v = param.args?.firstOrNull()
        val txt = (v as? CharSequence)?.toString() ?: return
        if (!txt.contains("撤回")) return
        // 带上 View 身份：同一个 Fragment 里可能有多处渲染撤回提示，
        // 只打文本分不清是谁渲染的
        val view = param.thisObject as? View
        val who = if (view == null) "-" else {
            val id = view.id
            "${view.javaClass.name}#" + if (id == View.NO_ID) "-" else "0x" + Integer.toHexString(id)
        }
        val frames = frames()
        val key = "$sig \"${txt.take(20)}\" <- " + frames.take(6).joinToString(" <- ")
        val n = counts.merge(key, 1) { a, b -> a + b } ?: 1
        if (n == 1 && printed.get() < MAX_LINES) {
            printed.incrementAndGet()
            own("RV TEXT \"${txt.take(60)}\" view=$who thread=${Thread.currentThread().name}")
            own("RV   at " + frames.joinToString(" <- "))
        }
    }

    private fun frames(): List<String> {
        val out = mutableListOf<String>()
        for (st in Throwable().stackTrace) {
            if (out.size >= FRAMES) break
            val s = st.toString()
            if (NOISE.any { s.contains(it) }) continue
            out.add(s)
        }
        return out
    }

    private fun flushInternal() {
        val entries = counts.entries.sortedByDescending { it.value }
        own("RV FLUSH kinds=${entries.size} passed=${passed.get()} " +
            "windowLeft=${(until - System.currentTimeMillis()).coerceAtLeast(0)}ms")
        for (e in entries.take(60)) own("RV   ${e.value}x ${e.key}")
    }

    // ------------------------------------------------------------------ 落盘

    /** 后台线程批量写：取证窗口内可能命中几百个调用点，同步 appendText 会拖出 ANR */
    private val queue = ConcurrentLinkedQueue<String>()

    @Volatile
    private var writer: Thread? = null

    private fun own(msg: String) {
        runCatching {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            queue.add("$ts RV $msg")
        }
        startWriter()
    }

    @Synchronized
    private fun startWriter() {
        val t = writer
        if (t != null && t.isAlive) return
        val nt = Thread({
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    Thread.sleep(400)
                    drain()
                }.onFailure { return@Thread }
            }
        }, "rv-log").apply { isDaemon = true }
        writer = nt
        nt.start()
    }

    private fun stopWriter() {
        // drain 要重试：队列可能攒了几批，只排一次会丢日志
        repeat(3) { drain() }
        writer?.let { runCatching { it.interrupt() } }
        writer = null
    }

    private fun drain() {
        if (queue.isEmpty()) return
        val sb = StringBuilder()
        while (sb.length < 16384) {
            val s = queue.poll() ?: break
            sb.append(s).append('\n')
        }
        runCatching {
            val f = File(LOG)
            f.parentFile?.mkdirs()
            f.appendText(sb.toString())
        }.onFailure {
            // 文件写不进去时至少落 LogX，否则会误以为「探针没命中」
            runCatching { android.util.Log.e("WXGroupRV", "write failed: ${it.javaClass.simpleName}") }
            runCatching { LogX.w("RV write failed: ${it.javaClass.simpleName}") }
        }
    }
}
