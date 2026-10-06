package com.lx.wxgroup.feature

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.data.Friend
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.WxidKind
import com.lx.wxgroup.util.LogX
import java.util.Collections
import java.util.WeakHashMap

/**
 * M4-B：聊天列表长按会话 → 微信自己的会话菜单里多一项「加入分组 / 移出分组」。
 *
 * ## 为什么要绕这么大一圈
 *
 * 微信这个会话菜单是一个 PopupWindow，但**它没有任何可以挂 hook 的入口**：
 * 实测 `ListPopupWindow.show` / `PopupWindow.showAsDropDown`(1/3/4 参) /
 * `showAtLocation` / `Dialog.show` / `WindowManager*.addView` 全都装了 hook，
 * 长按菜单照弹，一个都没命中（微信要么覆写了不走 super，要么自己造窗口）。
 *
 * 所以反过来做：**先拿窗口，再往窗口里塞我们的行**。
 *  1. 长按经过 `View.performLongClick`（所有长按的必经之路，不依赖任何微信类名），
 *     此时能拿到被长按的行、以及行上挂的会话 tag —— 里面有 wxid。
 *  2. 延迟一点等菜单弹出来，然后反射 `WindowManagerGlobal.mViews` 枚举当前所有窗口，
 *     认「里面有一行写着『删除该聊天』之类文本的非主窗口」就是它。
 *  3. 把菜单里那个 ListView **包进一层竖向 LinearLayout**，我们的条目放在列表上方。
 *
 * 第 3 步为什么不直接往菜单里 addView：菜单条目容器是个 **AdapterView**
 * （实测 addView 抛 UnsupportedOperationException），条目由 adapter 提供。
 * 往 adapter 里插数据会**打乱微信自己的 position 索引**（它会按位置分发动作，
 * 插一条之后「点标为未读」可能变成「删除该聊天」），所以绝不能动 adapter，
 * 只能在列表外面套一层。这样微信的菜单一行代码都不用改，索引映射原样保持。
 *
 * ## 几个已经踩过的坑
 *  - 菜单是**异步**弹出的（长按 → 震动 → 弹窗），必须延迟枚举，不能同步找。
 *  - 菜单窗口尺寸是弹之前按内容算死的，加了一行之后窗口不会自己长高，
 *    要顺手把 WindowManager.LayoutParams.height 补上，否则新条目会被裁掉。
 *  - 一次长按会让整条 View 链上的每个 View 都走一遍 performLongClick，必须时间去重。
 */
object ConvMenu {

    /** 菜单弹出是异步的，等它出现再注入 */
    private const val INJECT_DELAY_MS = 300L

    /** 一次长按会沿 View 链触发多次 performLongClick */
    private const val DEDUPE_MS = 700L

    /** 同一个会话短时间内重复长按（长按会被上层 View 转发），只处理一次 */
    private const val SAME_TARGET_MS = 3000L

    /** 菜单条目的文案特征：任一条命中即认为找到了会话菜单 */
    private val MENU_HINTS = arrayOf(
        "删除该聊天", "不显示该聊天", "标为未读", "标记未读",
        "置顶该聊天", "取消置顶", "置顶聊天",
    )

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 菜单窗口 -> 我们插进去的那一条（WeakHashMap 不持有宿主窗口） */
    private val injected = Collections.synchronizedMap(WeakHashMap<View, TextView>())

    /** 标记「这个 View 是我们套上去的包装层」，避免重复包装 */
    private val WRAP_TAG = "WXGroupMenuWrap"

    @Volatile
    private var lastAt = 0L

    @Volatile
    private var lastWxid = ""

    @Volatile
    private var lastWxidAt = 0L

    private class Target(val wxid: String, val name: String)

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.view.View", lpparam.classLoader, "performLongClick",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching { onLongPress(param.thisObject as? View ?: return) }
                    }
                }
            )
            LogX.i("ConvMenu: View.performLongClick hooked")
        }.onFailure { LogX.w("ConvMenu: hook failed: ${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------ 长按

    private fun onLongPress(v: View) {
        // 只认聊天列表里的长按（通讯录有自己的 MenuProbe）
        val list = GroupBar.conversationList() ?: return
        if (v.rootView !== list.rootView) return
        val row = cr0Ancestor(v) ?: return

        val now = System.currentTimeMillis()
        if (now - lastAt < DEDUPE_MS) return
        lastAt = now

        val wxid = wxidOf(row) ?: run {
            LogX.d("ConvMenu: no wxid on row=${row.javaClass.simpleName}")
            return
        }
        if (wxid == lastWxid && now - lastWxidAt < SAME_TARGET_MS) return
        lastWxid = wxid
        lastWxidAt = now

        val name = resolveName(wxid, row)
        LogX.i("ConvMenu: longpress wxid=$wxid name=$name row=${row.javaClass.simpleName}")
        logCandidates(row, list)

        mainHandler.postDelayed({
            runCatching { inject(Target(wxid, name)) }
                .onFailure { LogX.w("ConvMenu: inject failed: ${it.javaClass.simpleName}") }
        }, INJECT_DELAY_MS)
    }

    /**
     * 展示名：优先库里同步到的好友档案（带备注），其次行上**可见**的最大字号文本，
     * 最后退回 wxid。
     */
    private fun resolveName(wxid: String, row: View): String {
        val fromDb = runCatching { GroupDb.current()?.findFriend(wxid)?.displayName }
            .getOrNull()
        if (!fromDb.isNullOrBlank()) return fromDb

        val fromTag = titleText(row)
        if (fromTag.isNotBlank()) return fromTag

        return wxid
    }

    /**
     * 行标题。
     *
     * 这里踩过两个坑：
     *
     *  1. **会话标题不是 TextView**。微信的会话名是一个自绘 View（实测 id `l7a`），
     *     拿不到 `.text`；行里真正的 TextView 只有时间、以及**复用残留**——按
     *     「找 TextView」的思路只会抓到残留文本（实测抓到过「折叠置顶聊天」，
     *     那是另一个折叠行留在 tag 上的旧值，字号还比标题大）。
     *     所以改读**无障碍文本**（和 uiautomator 看到的同源），自绘 View 的
     *     可访问文本就是这么暴露出来的。
     *  2. 残留文本要滤掉，而且得滤「折叠置顶聊天」这类微信自己的行文案 ——
     *     把它们当成人名存进分组就脏了。
     */
    private fun titleText(row: View): String {
        var hit = ""
        fun walk(v: View, depth: Int) {
            if (depth > 14 || hit.isNotEmpty()) return
            if (v !is ViewGroup) {
                val t = a11yText(v)
                if (t.isNotEmpty() && !isChromeText(t)) hit = t
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    v.getChildAt(i)?.let { walk(it, depth + 1) }
                }
            }
        }
        runCatching { walk(row, 0) }
        return hit
    }

    /** 取一个 View 对外暴露的可访问文本（等价于 uiautomator dump 里看到的 text） */
    @Suppress("DEPRECATION")
    private fun a11yText(v: View): String = runCatching {
        val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
        v.onInitializeAccessibilityNodeInfo(info)
        val t = (info.text ?: info.contentDescription)?.toString()?.trim().orEmpty()
        info.recycle()
        if (t == "null") "" else t
    }.getOrDefault("")

    /** 微信自己的行文案（不是人名），不能拿来当初会话名 */
    private fun isChromeText(t: String): Boolean =
        t.contains("置顶聊天") || t.contains("个置顶") || t.contains("该聊天")

    /** 诊断：把行里的可访问文本候选 + 会话对象的 String 字段打出来，便于核对取名依据 */
    private fun logCandidates(row: View, list: android.widget.ListView) {
        runCatching {
            val sb = StringBuilder("ConvMenu cand:")
            fun walk(v: View, depth: Int) {
                if (depth > 14 || sb.length > 700) return
                val t = a11yText(v)
                if (t.isNotEmpty()) {
                    val id = runCatching {
                        if (v.id == View.NO_ID) "?" else v.resources.getResourceName(v.id)
                            .substringAfterLast('/')
                    }.getOrDefault("?")
                    sb.append(" [$id:${v.javaClass.simpleName}]'${t.take(14)}'")
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) v.getChildAt(i)?.let { walk(it, depth + 1) }
                }
            }
            walk(row, 0)
            // 会话对象的 String 字段：field_username 用来交叉验证 tag.l 是不是 wxid
            runCatching {
                val pos = list.getPositionForView(row)
                val item = list.adapter?.getItem(pos)
                if (item != null) {
                    sb.append(" item=${item.javaClass.simpleName}")
                    var cls: Class<*>? = item.javaClass
                    var guard = 0
                    while (cls != null && cls != java.lang.Object::class.java && guard++ < 3) {
                        for (f in cls.declaredFields) {
                            if (f.type != String::class.java) continue
                            val s = runCatching {
                                f.isAccessible = true
                                f.get(item) as? String
                            }.getOrNull() ?: continue
                            if (s.isNotEmpty()) sb.append(" ${f.name}='${s.take(20)}'")
                        }
                        cls = cls.superclass
                    }
                }
            }
            LogX.d(sb.toString())
        }
    }

    /**
     * 会话行的 tag 里带 wxid。
     *
     * 8.0.48 实测：`ConversationFolderItemView` 的 tag 是 `com.tencent.mm.ui.conversation.n3`，
     * 字段 `l`（String）就是会话 wxid。字段名是混淆名、跨版本会变，所以留了一条
     * 「扫所有 String 字段找像 wxid 的」兜底。
     */
    private fun wxidOf(row: View): String? {
        val tag = runCatching { row.tag }.getOrNull() ?: return null

        runCatching {
            val f = tag.javaClass.getDeclaredField("l")
            f.isAccessible = true
            val s = f.get(tag) as? String
            if (s != null && plausible(s)) return s
        }

        var cls: Class<*>? = tag.javaClass
        var guard = 0
        while (cls != null && cls != java.lang.Object::class.java && guard++ < 3) {
            for (f in runCatching { cls!!.declaredFields }.getOrDefault(emptyArray())) {
                if (f.type != String::class.java) continue
                val s = runCatching {
                    f.isAccessible = true
                    f.get(tag) as? String
                }.getOrNull() ?: continue
                if (strict(s)) return s
            }
            cls = cls.superclass
        }
        return null
    }

    /** 非空、无空白、无中文 —— 判断 tag 上那个字段像不像一个 id */
    private fun plausible(s: String): Boolean =
        s.isNotBlank() && s.length in 3..64 && s.none { it.isWhitespace() || isCjk(it) }

    /** 兜底扫描要更严：必须带下划线或已知前缀，免得把昵称当 wxid */
    private fun strict(s: String): Boolean {
        if (!plausible(s)) return false
        return s.startsWith("wxid_") || s.startsWith("gh_") || s.endsWith("@chatroom") ||
            (s.contains('_') && s.all { it.isLetterOrDigit() || it == '_' || it == '-' })
    }

    private fun isCjk(c: Char): Boolean =
        Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    private fun cr0Ancestor(v: View): View? {
        var cur: View? = v
        var guard = 0
        while (cur != null && guard++ < 8) {
            if (isCr0(cur)) return cur
            cur = cur.parent as? View
        }
        return null
    }

    private fun isCr0(v: View): Boolean = runCatching {
        val id = v.id
        if (id == 0) return false
        v.resources.getResourceName(id).endsWith(":id/cr0")
    }.getOrDefault(false)

    // ------------------------------------------------------------ 注入菜单

    private fun inject(target: Target) {
        val list = GroupBar.conversationList() ?: return
        val mainRoot = list.rootView

        val windows = windowRoots()
        for (win in windows) {
            if (win === mainRoot) continue          // 主窗口里全是会话，没有菜单
            val anchor = findMenuAnchor(win) ?: continue
            addItem(win, anchor, target)
            return
        }
        LogX.d("ConvMenu: menu window not found (windows=${windows.size})")
    }

    /** 反射 WindowManagerGlobal.mViews 拿当前所有窗口的根 View */
    private fun windowRoots(): List<View> = runCatching {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val inst = cls.getMethod("getInstance").invoke(null)
        val f = cls.getDeclaredField("mViews")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (f.get(inst) as? List<Any?>)?.mapNotNull { it as? View } ?: emptyList()
    }.getOrElse { emptyList() }

    /** 在窗口里找那条「删除该聊天」之类的原生条目 */
    private fun findMenuAnchor(win: View): TextView? {
        var hit: TextView? = null
        fun walk(v: View, depth: Int) {
            if (hit != null || depth > 10) return
            if (v is TextView) {
                val t = v.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty() && MENU_HINTS.any { t.contains(it) }) {
                    hit = v
                    return
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) v.getChildAt(i)?.let { walk(it, depth + 1) }
            }
        }
        runCatching { walk(win, 0) }
        return hit
    }

    private fun addItem(win: View, anchor: TextView, target: Target) {
        val db = GroupDb.current() ?: return
        val joined = runCatching { db.groupsOf(target.wxid) }.getOrDefault(emptyList())
        val label = if (joined.isEmpty()) "加入分组" else "移出分组"

        val existing = injected[win]
        if (existing != null && existing.parent != null) {
            existing.text = label
            existing.setOnClickListener { onItemClick(win, target) }
            LogX.i("ConvMenu: item reused -> $label")
            return
        }

        val itemH = anchor.height.takeIf { it > 0 } ?: dp(win.context, 56)
        val item = buildItem(win, anchor, label, itemH) { onItemClick(win, target) }

        val lv = listAncestor(anchor)
        val lvParent = lv?.parent as? ViewGroup
        val inserted = if (lv != null && lvParent != null && !isAdapterView(lvParent)) {
            wrapAbove(lvParent, lv, item, itemH)
        } else {
            // 兜底：容器不是 AdapterView 的话直接插到最前面
            val container = anchor.parent as? ViewGroup
            container != null && runCatching { container.addView(item, 0) }.isSuccess
        }
        if (!inserted) {
            LogX.w("ConvMenu: cannot insert item (anchor=${anchor.javaClass.simpleName})")
            return
        }
        injected[win] = item
        growWindow(win, itemH)
        LogX.i(
            "ConvMenu: item=$label inserted h=$itemH " +
                "list=${lv?.javaClass?.simpleName} parent=${lv?.parent?.javaClass?.simpleName}"
        )
    }

    /** 造一条和原生条目同款的行：样式全部从原生条目身上抄，微信换主题我们也跟着变 */
    private fun buildItem(
        win: View,
        anchor: TextView,
        label: String,
        itemH: Int,
        onClick: () -> Unit,
    ): TextView {
        val ctx = win.context
        val res = ctx.resources
        return TextView(ctx).apply {
            text = label
            // 原生条目的 textSize 本来就是像素，直接按 PX 设回去，省掉 px->sp 的换算
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, anchor.textSize)
            setTextColor(anchor.currentTextColor)
            typeface = anchor.typeface
            gravity = if (anchor.gravity != 0) anchor.gravity else Gravity.CENTER
            setPadding(
                anchor.paddingLeft, anchor.paddingTop,
                anchor.paddingRight, anchor.paddingBottom,
            )
            // 背景是 Drawable 实例，两个 View 共用会互相串状态，必须克隆一份
            anchor.background?.let { bg ->
                background = runCatching {
                    bg.constantState?.newDrawable(res) ?: bg
                }.getOrDefault(bg)
            }
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, itemH
            )
            setOnClickListener { onClick() }
        }
    }

    /** 从 anchor 往上找它所在的那个列表（AdapterView / RecyclerView 都算） */
    private fun listAncestor(anchor: View): View? {
        var cur: View? = anchor.parent as? View
        var guard = 0
        while (cur != null && guard++ < 8) {
            if (isAdapterView(cur)) return cur
            cur = cur.parent as? View
        }
        return null
    }

    private fun isAdapterView(v: View): Boolean =
        v is AdapterView<*> || v.javaClass.name.contains("RecyclerView")

    /**
     * 把列表包进一层竖向 LinearLayout，我们的条目放在列表上方。
     *
     * 关键：**不动列表的 adapter**。往 adapter 里插数据会打乱微信自己的 position
     * 索引（它按位置分发「标为未读 / 删除该聊天」），插一条就可能让「标为未读」
     * 变成「删除该聊天」。套一层则微信那边完全无感。
     */
    private fun wrapAbove(parent: ViewGroup, list: View, item: View, itemH: Int): Boolean {
        // 已经包过了：把 item 更新掉即可
        if (parent.tag == WRAP_TAG) {
            val col = parent.getChildAt(0) as? ViewGroup ?: return false
            val old = col.getChildAt(0)
            if (old !== item) {
                col.removeViewAt(0)
                col.addView(item, 0, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, itemH
                ))
            }
            return true
        }
        return runCatching {
            val idx = parent.indexOfChild(list)
            val listLp = list.layoutParams
            parent.removeView(list)

            val col = LinearLayout(list.context).apply {
                orientation = LinearLayout.VERTICAL
            }
            col.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, itemH
            ))
            // 列表吃掉剩余高度：窗口总高不变时它会矮一截，所以下面还要把窗口撑高
            col.addView(list, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))

            if (listLp != null) parent.addView(col, idx, listLp) else parent.addView(col, idx)
            col.tag = WRAP_TAG
            true
        }.onFailure { LogX.w("ConvMenu: wrap failed: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
    }

    /**
     * 菜单窗口的高度是弹出来之前按内容算死的，加一行之后不会自己长高，
     * 新条目会把列表挤矮（等于裁掉最后一条）—— 手动把窗口高度补上。
     */
    private fun growWindow(win: View, byPx: Int) {
        runCatching {
            val lp = win.layoutParams as? WindowManager.LayoutParams ?: return@runCatching
            LogX.i("ConvMenu: win lp=${lp.width}x${lp.height} laid=${win.width}x${win.height}")
            val h = if (lp.height > 0) lp.height else win.height
            if (h > 0 && byPx > 0) {
                lp.height = h + byPx
                win.context.getSystemService(WindowManager::class.java)
                    ?.updateViewLayout(win, lp)
                LogX.i("ConvMenu: window grown $h -> ${lp.height}")
            } else {
                win.requestLayout()
            }
        }.onFailure { LogX.d("ConvMenu: growWindow skipped: ${it.javaClass.simpleName}") }
    }

    private fun onItemClick(win: View, target: Target) {
        LogX.i("ConvMenu: clicked wxid=${target.wxid}")
        dismissMenu(win)
        // 先让微信的菜单收干净再弹我们自己的框，免得两个窗口抢焦点
        mainHandler.postDelayed({
            val db = GroupDb.current()
            val friend = db?.findFriend(target.wxid)
                ?: Friend(
                    wxid = target.wxid,
                    nickname = target.name,
                    type = WxidKind.typeOf(target.wxid),
                )
            GroupPicker.show(friend)
        }, 180)
    }

    /** 菜单是 PopupWindow 的 PopupDecorView，它自己处理 BACK 键 → 走它的关闭逻辑最干净 */
    private fun dismissMenu(win: View) {
        runCatching {
            win.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            win.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }.onFailure { LogX.d("ConvMenu: dismiss failed: ${it.javaClass.simpleName}") }
    }

    private fun dp(ctx: android.content.Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
