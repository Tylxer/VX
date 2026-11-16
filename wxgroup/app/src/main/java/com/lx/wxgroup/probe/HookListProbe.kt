package com.lx.wxgroup.probe

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.HeaderViewListAdapter
import android.widget.TextView
import com.lx.wxgroup.feature.GroupBar
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 竞品（微X）操作点观测探针（R53，**纯只读**）。
 *
 * ## 为什么要有它
 *
 * 微X 的 APK 是 native 壳：`classes.dex` 只有 `WxCoreLoader` + `InMemoryDexClassLoader`
 * 的装载逻辑，业务代码在 `assets/` 下 7 个熵 8.00 的密文里，中文串一个都搜不到
 * （实测 `classes.dex` 2.4MB，搜「分组 / 会话 / 标签」全 0 命中）。静态反编译成本极高。
 *
 * 但**它和我们跑在同一个微信进程里**。不管它是替换 adapter、hook 宿主方法还是改数据，
 * 只要挂了 hook，就必然落在 LSPosed 的全局注册表；只要往列表里插行，就必然体现在
 * adapter 的 `getCount / getItem / getItemViewType` 上。所以从宿主侧观测比反编译划算。
 *
 * ## 两个子命令
 *
 * - `hook`（默认）：反射扫 `de.robv.android.xposed.XposedBridge` 里所有静态 `*Hooked*`
 *   字段（LSPosed 可能是 `Map<Member, MethodHookable>`，旧版是 `Map<Member, Unhook>`，
 *   所以按「值是不是 Map、key 是不是 Member」来认，不写死类型名），把已挂的 `Member`
 *   全列出来，按包名前 3 段聚合。默认只列 `com.fkzhang.*`（微X 的包名根），
 *   用 `--es val <子串>` 换成任意过滤条件。
 * - `row`：dump 会话列表 adapter 链（含 `HeaderViewListAdapter` 的 header 数/包装层数）
 *   + 每个可见行的 `getItemViewType / View 类 / tag 类 / item 类`。
 *   判据：header 位置 `getItemViewType` 恒 `-1`；伪行若来自 hook `getView`，`getItem` 会
 *   返回 null 但 view 非空；若来自数据源插入，`getItem` 会给出一个非 `r4` 的对象。
 *
 * ⛔ 不 hook 任何方法、不改 adapter、不写任何数据。全部 runCatching 包裹。
 */
object HookListProbe {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun run(sub: String, filter: String, n: Int): List<String> = runCatching {
        when (sub) {
            "row" -> rowDump(n)
            "prefs" -> prefsDump(n)
            else -> hookDump(filter, n)
        }
    }.getOrElse { listOf("ERR ${it.javaClass.simpleName}: ${it.message}") }

    // ------------------------------------------------------------ ③ 微X 的配置存哪

    /**
     * `classes.dex` 里出现 `de/robv/android/xposed/XSharedPreferences` ⇒ 微X 用它读自己的配置。
     * 这个 API 是 Xposed 提供的**跨进程读模块 prefs** 的通道，我们也能调：
     * 它按「模块包名 + prefs 文件名」直接去对方 shared_prefs 目录下的 xml 里读。
     *
     * 顺带列三个目录（微X 已经把 FKZ_* 库塞进了微信的 databases/ 里，说明它并不老实待在
     * 自己的 data 目录），用来确认「除了 prefs 还有没有别的落点」。
     */
    private fun prefsDump(n: Int): List<String> {
        val out = ArrayList<String>()
        val lim = n.coerceIn(1, 200)
        for (pkg in listOf("com.fkzhang.wechatxposed", "com.fkzhang.xposed")) {
            runCatching {
                val cls = Class.forName("de.robv.android.xposed.XSharedPreferences")
                val sp = cls.getConstructor(String::class.java).newInstance(pkg)
                val file = runCatching { cls.getMethod("getFile").invoke(sp) }.getOrNull() as? java.io.File
                out.add(
                    "prefs $pkg file=$file exists=${file?.exists()} " +
                        "canRead=${file?.canRead()} len=${file?.length()}"
                )
                @Suppress("UNCHECKED_CAST")
                val all = runCatching {
                    cls.getMethod("getAll").invoke(sp) as? Map<String, *>
                }.getOrNull().orEmpty()
                out.add("prefs $pkg size=${all.size}")
                // ⚠️ size=0 有歧义：既可能是「微X 真的没写配置」，也可能是「跨 uid 读不到」。
                // 经典 Xposed 要目标模块先 makeWorldReadable()，Android 7+ 基本被 SELinux 卡死，
                // 这种情况下 getAll() 返回**空 map 而不是异常**。所以这里必须把歧义写进日志，
                // 免得把「没权限」读成「没配置」。
                if (all.isEmpty()) {
                    out.add(
                        "prefs $pkg verdict=EMPTY-OR-DENIED " +
                            "（exists=false 同时意味着「不存在」和「无权限 stat」；" +
                            "用 tools/_r53_xh.py prefsx 走 su 对照，别据此下结论）"
                    )
                }
                for ((k, v) in all.entries.take(lim)) {
                    out.add("  $k=${v.toString().take(80)}")
                }
            }.onFailure {
                out.add("prefs $pkg ERR ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        for (dir in listOf(
            "/data/user/0/com.tencent.mm/databases",
            "/data/user/0/com.tencent.mm/shared_prefs",
            "/data/user/0/com.tencent.mm/files"
        )) {
            runCatching {
                val fs = java.io.File(dir).listFiles()
                out.add("dir $dir n=${fs?.size ?: -1}")
                fs?.sortedByDescending { it.length() }?.take(lim)?.forEach {
                    out.add("  ${it.name} ${it.length()}B")
                }
            }.onFailure { out.add("dir $dir ERR ${it.javaClass.simpleName}") }
        }
        return out
    }

    // ------------------------------------------------------------ ① 全局 hook 表

    private fun hookDump(filter: String, n: Int): List<String> {
        val out = ArrayList<String>()
        val lim = n.coerceIn(1, 200)
        val cls = runCatching {
            // ⚠️ 必须先用**直接引用**：LSPosed 把 XposedBridge 放在模块 classloader 可见的位置，
            // 但 `Class.forName` 走的是调用方 loader 且会触发初始化，实测在这里抛
            // ClassNotFoundException（我们的 hook 明明工作正常）。直接引用由编译器生成
            // 常量池引用，走的是同一套解析路径，稳。
            de.robv.android.xposed.XposedBridge::class.java
        }.getOrNull() ?: runCatching {
            Class.forName("de.robv.android.xposed.XposedBridge")
        }.getOrNull() ?: return listOf("ERR XposedBridge 不可见（直接引用与 Class.forName 都失败）")

        val ver = runCatching { cls.getMethod("getXposedVersion").invoke(null) }.getOrNull()
        out.add("ver=$ver loader=${cls.classLoader}")

        val members = ArrayList<Member>()
        val fieldInfo = ArrayList<String>()
        for (f in cls.declaredFields) {
            if (!Modifier.isStatic(f.modifiers)) continue
            val nm = f.name
            val byName = nm.contains("Hooked") || nm.contains("hook")
            val v = runCatching { f.isAccessible = true; f.get(null) }.getOrNull()
            // 先按字段名认；名字对不上时，只要值是「key 为 Member 的 Map」也收
            // （LSPosed 某版可能把字段改名成不含 hook 的形态）
            val isHookMap = v is Map<*, *> && v.keys.any { it is Member }
            if (!byName && !isHookMap) continue
            if (v is Map<*, *>) {
                fieldInfo.add("$nm=${v.size}")
                for (k in v.keys) if (k is Member) members.add(k)
            } else {
                fieldInfo.add("$nm:${v?.javaClass?.simpleName}")
            }
        }
        out.add("fields=[${fieldInfo.joinToString(",")}] members=${members.size}")
        if (members.isEmpty()) {
            out.add("verdict=读不到任何已挂 hook ⇒ LSPosed 可能按模块隔离 classloader（此时以 sub=row 为准）")
            return out
        }

        val byPkg = members.groupingBy { pkgOf(it.declaringClass.name) }
            .eachCount().entries.sortedByDescending { it.value }.take(12)
        out.add("byPkg=" + byPkg.joinToString(" ") { "${it.key}=${it.value}" })

        val hits = if (filter.isBlank()) {
            members.filter { it.declaringClass.name.startsWith("com.fkzhang") }
        } else {
            members.filter {
                it.declaringClass.name.contains(filter, true) || it.name.contains(filter, true)
            }
        }
        out.add("hit=${hits.size} filter='${filter}'")
        for (m in hits.take(lim)) {
            out.add("  ${m.declaringClass.name}#${m.name}")
        }
        return out
    }

    private fun pkgOf(name: String): String =
        name.split('.').take(3).joinToString(".")

    // ------------------------------------------------------------ ② adapter 链 + 行

    private fun rowDump(n: Int): List<String> {
        val lim = n.coerceIn(1, 200)
        // 主线程写、io 线程读：正常路径靠 latch 的 happens-before；但 await 超时时**没有**屏障，
        // 两边同时摸同一个 ArrayList 会并发修改。所以写进局部 list，最后一次性交接给 holder，
        // 超时就直接报 TIMEOUT，不再去读那个可能正在被追加的对象。
        // （局部变量不能标 @Volatile；这里也不需要——超时后根本不读 holder。）
        val holder = arrayOfNulls<List<String>>(1)
        val latch = CountDownLatch(1)
        mainHandler.post {
            val buf = ArrayList<String>()
            runCatching { dumpRowsInto(buf, lim) }
            holder[0] = buf
            latch.countDown()
        }
        if (!latch.await(3, TimeUnit.SECONDS)) {
            return listOf("TIMEOUT 主线程 3s 内没跑完（会话列表可能正在滚动/重建），重试一次即可")
        }
        return holder[0] ?: listOf("EMPTY 主线程跑完了但没产出")
    }

    private fun dumpRowsInto(out: MutableList<String>, lim: Int) {
        val list = GroupBar.conversationList()
        if (list == null) {
            out.add("no conversation list（会话列表没被我们的 hook 记住）")
            return
        }
        val a = list.adapter
        out.add(
            "adapter=${a?.javaClass?.name} count=${a?.count} " +
                "first=${list.firstVisiblePosition} children=${list.childCount}"
        )
        out.add("listHeaders=${list.headerViewsCount} listFooters=${list.footerViewsCount}")
        if (a is HeaderViewListAdapter) {
            val w = runCatching { a.wrappedAdapter }.getOrNull()
            out.add("wrapped=${w?.javaClass?.name} wrappedCount=${w?.count}")
            val infos = runCatching {
                val f = HeaderViewListAdapter::class.java.getDeclaredField("mHeaderViewInfos")
                f.isAccessible = true
                f.get(a) as? List<*>
            }.getOrNull()
            out.add("headerInfos=${infos?.size} classes=${infos?.map { it?.javaClass?.simpleName }}")
        }
        val limit = minOf(list.childCount, lim)
        for (i in 0 until limit) {
            val pos = list.firstVisiblePosition + i
            val v = list.getChildAt(i) ?: continue
            val vt = runCatching { a?.getItemViewType(pos) }.getOrNull()
            val item = runCatching { a?.getItem(pos) }.getOrNull()
            out.add(
                "pos=$pos vt=$vt view=${v.javaClass.simpleName} " +
                    "tag=${v.tag?.javaClass?.name} item=${item?.javaClass?.name} " +
                    "user=${itemUsername(item)} txt=${firstText(v)}"
            )
        }
    }

    /**
     * 会话对象（`com.tencent.mm.storage.r4`）上的 wxid。
     *
     * 为什么要它：如果某一行显示的标题（美妆/日化…）在 `rcontact` 里查不到，
     * 但这一行底层仍然是个真实会话对象，那**末端的 user 就是原始 wxid**
     * —— 直接决定「行是真实的、只是标题被改」还是「整行是造出来的」。
     * 字段名在混淆包里会变，所以按候选名逐个试，再退无参方法。
     */
    private fun itemUsername(item: Any?): String {
        if (item == null) return "null"
        for (name in listOf("field_username", "username")) {
            var c: Class<*>? = item.javaClass
            var guard = 0
            while (c != null && guard++ < 4) {
                val f = runCatching { c!!.getDeclaredField(name) }.getOrNull()
                if (f != null) {
                    val v = runCatching { f.isAccessible = true; f.get(item)?.toString() }.getOrNull()
                    if (!v.isNullOrBlank()) return v
                }
                c = c.superclass
            }
        }
        for (m in listOf("getUsername", "d", "b")) {
            val r = runCatching { item.javaClass.getMethod(m).invoke(item) }.getOrNull() as? String
            if (!r.isNullOrBlank()) return r
        }
        return "?"
    }

    private fun firstText(v: View): String {
        if (v is TextView) {
            val t = v.text?.toString().orEmpty()
            if (t.isNotBlank()) return t.take(14)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val t = firstText(v.getChildAt(i))
                if (t.isNotBlank()) return t
            }
        }
        return ""
    }
}
