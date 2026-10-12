package com.lx.wxgroup.feature

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ContextMenu
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.core.MarkRead
import com.lx.wxgroup.data.Friend
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.WxidKind
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

/**
 * A15（注入版）：往**微信自己的会话长按菜单**里加一行，而不是自己弹对话框。
 *
 * ## 注入点
 *
 * 全量 hook 点探针（[com.lx.wxgroup.probe.MenuHuntProbe]）真手长按抓到的链路：
 *
 * ```
 * View.performLongClick(x,y)
 *  └ View.showContextMenu(x,y)
 *     └ AbsListView.showContextMenuForChild(ConversationFolderItemView)
 *        └ DecorView.showContextMenuForChildInternal
 *           └ ContextMenuBuilder.showPopup
 *              └ View.createContextMenu(ContextMenuBuilder)   ★ 这里
 * ```
 *
 * 也就是说微信 8.0.48 的会话菜单就是**标准 AOSP ContextMenu**（`z2` 返回 false，
 * 由 `performLongPress` 里的 `if (!handled) showContextMenuForChild(...)` 兜底弹出），
 * 微信在 `createContextMenu` 回调里 `add` 自己的菜单项。
 *
 * 所以只要在 **after** 里往同一个 `ContextMenu` 多 add 两项即可 ——
 * 整条链路不含任何微信混淆类名，只依赖 framework API。
 *
 * ## 与旧自弹版（feature/ConvMenu.kt，已删）的区别
 *
 *  - 不消费长按事件：K7 的长按包装原样透传，微信的删除/置顶/免打扰全部保留；
 *  - 不需要在长按回调里做 position 换算（只用来取 wxid，见 [ConvFilter.wxidAt]）；
 *  - 样式、位置、动画完全跟微信一致。
 *
 * ## 三个必须防的坑
 *
 * 1. **重复添加**：`createContextMenu` 在一次长按里会被调用多次（实测一次长按
 *    出现 8 次调用：View 链 + 微信自己的多次触发），且 `ContextMenuBuilder` 是
 *    DecorView 复用的对象。→ add 之前先用 [menu].findItem 按**唯一 itemId** 查重，
 *    已经有了就不再加。
 * 2. **itemId 不能撞车**：`MenuBuilder.findItem/removeItem` 只按 itemId 匹配，
 *    微信的项 id 都是小整数，所以我们用 `0x5A47xx` 这种生僻值。
 * 3. **加错人**：会话顺序会随新消息重排，而 adapter 的快照可能过期 ——
 *    取 wxid 一律走 [ConvFilter.wxidAt]（它实时读微信 adapter 的 `getItem`），
 *    不要自己缓存 position→wxid。
 *
 * ⚠️ 本方案**必须真手长按**验证：adb 的 `input` 长按触发不了微信原生菜单
 * （但能触发我们自己弹的 Dialog，这也是旧方案能用 adb 自测的原因）。
 */
object ConvMenuInject {

    /** 我们自己菜单项的 groupId：用生僻值避免和微信的分组撞车 */
    private const val GROUP_ID = 0x5A47

    /** ⚠️ itemId 必须唯一且生僻：MenuBuilder 的 findItem/removeItem 只认 itemId */
    private const val ID_JOIN = 0x5A4701
    private const val ID_READ = 0x5A4702

    /**
     * 排序号。MenuBuilder 是**按 order 插入**的（order 小的排前面，
     * 实际排序键是 `(category << 16) | order`），微信自己的项 order 基本都是 0，
     * 所以给个大的让它落在菜单末尾。
     */
    private const val ORDER_TAIL = 1000

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.view.View",
                lpparam.classLoader,
                "createContextMenu",
                ContextMenu::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!diag) return
                        runCatching {
                            val menu = param.args?.getOrNull(0) as? ContextMenu ?: return
                            val v = param.thisObject as? View ?: return
                            LogX.d(
                                "CMI chain in #${seq()} menu=${id(menu)} " +
                                    "${v.javaClass.simpleName} size=${sizeOf(menu)}"
                            )
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val v = param.thisObject as? View ?: return
                            val menu = param.args?.getOrNull(0) as? ContextMenu ?: return
                            inject(v, menu)
                            if (diag) {
                                LogX.d(
                                    "CMI chain out #${seq()} menu=${id(menu)} " +
                                        "${v.javaClass.simpleName} size=${sizeOf(menu)}"
                                )
                            }
                        }.onFailure {
                            LogX.w("CMI threw: ${it.javaClass.simpleName}: ${it.message}")
                        }
                    }
                }
            )
            LogX.i("CMI installed (View.createContextMenu)")
        }.onFailure { LogX.e("CMI install failed", it) }

        // ---- 诊断钩子：看清菜单从创建到显示的完整生命周期 ----
        if (diag) installDiag(lpparam)
    }

    /**
     * 按名字 hook 一个类里声明的**所有**同名重载。
     *
     * ⚠️ 我们用的 Xposed API（`de.robv.android.xposed:api`）里**没有**
     * `XposedHelpers.hookAllMethods` —— 那是 `api-82` 的扩展（XposedHelpers 的
     * 另一批静态方法）。所以自己遍历 `declaredMethods` + `XposedBridge.hookMethod`。
     */
    private fun hookAll(cls: Class<*>, name: String, cb: XC_MethodHook): Int {
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name != name) continue
            runCatching {
                XposedBridge.hookMethod(m, cb)
                n++
            }.onFailure {
                LogX.w("CMI hook ${cls.simpleName}.$name failed: ${it.javaClass.simpleName}")
            }
        }
        return n
    }

    /**
     * 诊断开关（运行时配置 `chat.menu_diag`，默认关）。
     *
     * 开着时每次长按会 dump 菜单全部内容（几十行日志）。用途：查清
     * 「菜单里到底有哪些项、微信自己的项是什么时候进去的、会不会被 clear」。
     *
     * ⚠️ 只在 install 时读一次，改完配置要重启微信才生效。取证完记得关掉。
     */
    @Volatile
    var diag: Boolean = false

    /** 日志序号：LogX 有「1 秒内相同内容」的去重，不带序号会被吞掉 */
    private val seqNo = java.util.concurrent.atomic.AtomicInteger(0)
    private fun seq(): Int = seqNo.incrementAndGet()
    private fun id(o: Any): String = Integer.toHexString(System.identityHashCode(o))

    private fun installDiag(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1) ContextMenuBuilder.showPopup 结束时：菜单已经填完，正要显示
        runCatching {
            val cls = XposedHelpers.findClass(
                "com.android.internal.view.menu.ContextMenuBuilder", lpparam.classLoader
            )
            hookAll(cls, "showPopup", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val menu = param.thisObject as? ContextMenu ?: return
                        val tag = "#${seq()} menu=${id(menu)}"
                        LogX.i("CMI diag showPopup done $tag -> ${dump(menu)}")
                        // 300ms 后再看一眼：菜单已经显示在屏幕上。
                        // ⚠️ 注意 ContextMenuBuilder 由 DecorView 复用、dismiss 后不清空
                        // （下次 show 前才 clearAll），所以两次内容相同**不能**证明
                        // 「没人动过菜单」，只能证明这 300ms 内没被重建。
                        Handler(Looper.getMainLooper()).postDelayed({
                            runCatching { LogX.i("CMI diag settle $tag -> ${dump(menu)}") }
                        }, 300)
                    }
                }
            })
            LogX.i("CMI diag: ContextMenuBuilder.showPopup hooked")
        }.onFailure {
            LogX.e("CMI diag showPopup hook failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // 2) MenuBuilder 的增删接口：谁在清菜单 / 谁在按 group 隐藏
        //    ⚠️ MenuBuilder 自己不实现 ContextMenu（只有子类 ContextMenuBuilder 实现），
        //    所以这里**不能**强转 ContextMenu，否则对所有普通 MenuBuilder 会静默跳过。
        runCatching {
            val cls = XposedHelpers.findClass(
                "com.android.internal.view.menu.MenuBuilder", lpparam.classLoader
            )
            val cb = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val menu = param.thisObject ?: return
                        LogX.d(
                            "CMI diag ${param.method.name} #${seq()} menu=${id(menu)} " +
                                "args=${param.args?.joinToString()} size=${sizeOf(menu)}"
                        )
                    }
                }
            }
            for (m in arrayOf("clearAll", "removeGroup", "removeItem", "setGroupVisible")) {
                runCatching { hookAll(cls, m, cb) }
            }
            LogX.i("CMI diag: MenuBuilder mutations hooked")
        }.onFailure {
            LogX.e("CMI diag MenuBuilder hook failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun inject(view: View, menu: ContextMenu) {
        val list = findListView(view)
        if (list == null) {
            LogX.d("CMI skip: not in a ListView (view=${view.javaClass.name})")
            return
        }
        // getPositionForView 会自己沿 parent 上溯到列表的直接 child，传内层子 View 也行
        val pos = runCatching { list.getPositionForView(view) }.getOrDefault(-1)
        if (pos < 0) {
            LogX.d("CMI skip: position<0 (view=${view.javaClass.name})")
            return
        }
        val wxid = ConvFilter.wxidAt(list, pos)
        if (wxid.isNullOrBlank()) {
            // 不是会话行（header / 我们插的分组行 / 不是被包装的列表）
            // —— 聊天页消息、通讯录、朋友圈的长按都会落在这里
            LogX.d("CMI skip: not a conversation row pos=$pos (view=${view.javaClass.name})")
            return
        }
        // 开关放最后判：一次长按这个方法会进来好几次，只有真要注入时才付一次读配置开销
        if (!Kv.getBool(Keys.CHAT_CONV_MENU_ENABLED)) return
        // 重复添加防护（详见类注释「坑 1」）
        if (menu.findItem(ID_JOIN) != null || menu.findItem(ID_READ) != null) return

        val db = GroupDb.current() ?: run {
            LogX.w("CMI db not ready")
            return
        }
        val joined = runCatching { db.groupsOf(wxid) }.getOrDefault(emptyList())
        // 昵称优先用库里的（titleOf 抓的是行内无障碍文本，可能抓到消息摘要）
        val name = runCatching { db.findFriend(wxid)?.nickname }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: titleOf(view).takeIf { it.isNotBlank() }
            ?: wxid
        LogX.i("CMI hit view=${view.javaClass.name} pos=$pos wxid=$wxid joined=${joined.size}")

        val joinLabel = if (joined.isEmpty()) "加入分组" else "分组管理（已加入 ${joined.size} 个）"
        menu.add(GROUP_ID, ID_JOIN, ORDER_TAIL, joinLabel)
            .setOnMenuItemClickListener {
                onJoin(wxid, name)
                true
            }
        menu.add(GROUP_ID, ID_READ, ORDER_TAIL + 1, "标记已读")
            .setOnMenuItemClickListener {
                onMarkRead(view.context, wxid)
                true
            }
        if (diag) LogX.i("CMI diag after add menu=${id(menu)} -> ${dump(menu)}")
    }

    // ------------------------------------------------------------------ 诊断工具

    /**
     * 菜单里当前的项数（取不到返回 -1）。
     *
     * ⚠️ 不能用 `javaClass.getDeclaredField("mItems")` —— `mItems` 声明在父类
     * `MenuBuilder` 上，`getDeclaredField` 只搜当前类，对 `ContextMenuBuilder`
     * 必然抛 NoSuchFieldException。用 [XposedHelpers.getObjectField]，它沿父类链找。
     */
    private fun sizeOf(menu: Any): Int = runCatching {
        (XposedHelpers.getObjectField(menu, "mItems") as? List<*>)?.size ?: -1
    }.getOrDefault(-1)

    /** 把菜单全部项 dump 成一行：`size=3 [#0 grp=0 id=1 '删除该聊天'] [#1 ...]` */
    private fun dump(menu: Menu): String {
        val sb = StringBuilder("size=${sizeOf(menu)}")
        for (i in 0 until 40) {
            // getItem 越界抛 IndexOutOfBoundsException，getOrNull 后 break 即可
            val it = runCatching { menu.getItem(i) }.getOrNull() ?: break
            sb.append(" [#").append(i)
                .append(" grp=").append(Integer.toHexString(it.groupId))
                .append(" id=").append(Integer.toHexString(it.itemId))
                .append(" vis=").append(if (it.isVisible) 1 else 0)
                .append(" '").append(it.title).append("']")
        }
        return sb.toString()
    }

    private fun onJoin(wxid: String, name: String) {
        val db = GroupDb.current() ?: return
        val friend = db.findFriend(wxid) ?: Friend(
            wxid = wxid,
            nickname = name,
            type = WxidKind.typeOf(wxid),
        )
        LogX.i("CMI join wxid=$wxid name=$name")
        // GroupPicker 是带勾选态的通用选择器，加入/移出都走它
        GroupPicker.show(friend)
    }

    private fun onMarkRead(ctx: Context, wxid: String) {
        val n = MarkRead.clear(setOf(wxid))
        LogX.i("CMI markread wxid=$wxid cleared=$n")
        // 只改内存里的会话对象，重启微信会恢复（A7 的已知边界）
        runCatching {
            UiKit.toast(
                ctx,
                if (n > 0) "已标为已读（仅本次生效，重启微信会恢复）" else "没有未读消息"
            )
        }.onFailure { LogX.w("CMI toast failed: ${it.javaClass.simpleName}") }
    }

    /** 从被长按的 View 往上找它所在的 ListView（会话行多半是内层子 View） */
    private fun findListView(v: View): ListView? {
        var p = v.parent
        var guard = 0
        while (p != null && guard++ < 12) {
            if (p is ListView) return p
            p = p.parent
        }
        return null
    }

    // ------------------------------------------------------------------ 会话名

    /**
     * 从行 View 上取会话标题。
     *
     * 踩过的坑：**会话标题不是 TextView**。微信的会话名是自绘 View（实测 id `l7a`），
     * 拿不到 `.text`；行里真正的 TextView 只有时间、以及**复用残留** —— 按「找 TextView」
     * 的思路只会抓到残留文本（实测抓到过「折叠置顶聊天」，那是另一个折叠行留在 tag
     * 上的旧值）。所以改读**无障碍文本**（与 uiautomator dump 同源），自绘 View 的可访问
     * 文本就是这么暴露出来的。
     *
     * ⚠️ 抓到的可能是消息摘要，所以只用于**显示/日志**，写库优先用 `db.findFriend`。
     */
    fun titleOf(row: View): String {
        var hit = ""
        fun walk(v: View, depth: Int) {
            if (depth > 14 || hit.isNotEmpty()) return
            val t = a11yText(v)
            if (t.isNotEmpty() && !isChromeText(t)) hit = t
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) v.getChildAt(i)?.let { walk(it, depth + 1) }
            }
        }
        runCatching { walk(row, 0) }
        return hit
    }

    /** 取一个 View 对外暴露的可访问文本（等价于 uiautomator dump 里看到的 text） */
    private fun a11yText(v: View): String = runCatching {
        val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
        v.onInitializeAccessibilityNodeInfo(info)
        val t = (info.text ?: info.contentDescription)?.toString()?.trim().orEmpty()
        info.recycle()
        if (t == "null") "" else t
    }.getOrDefault("")

    /** 微信自己的行文案（不是人名），不能拿当会话名 */
    private fun isChromeText(t: String): Boolean =
        t.contains("置顶聊天") || t.contains("个置顶") || t.contains("该聊天")
}
