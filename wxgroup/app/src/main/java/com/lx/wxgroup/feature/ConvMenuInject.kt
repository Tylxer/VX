package com.lx.wxgroup.feature

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ContextMenu
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.core.ConvReader
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
 * ## 微信的菜单到底是什么（真机 dump 实锤，别再走弯路）
 *
 * 它不是 AOSP ContextMenu，是**微信自己画的 PopupWindow**，菜单项就是 `TextView`：
 *
 * ```
 * android.widget.PopupWindow$PopupDecorView 601x884      ← 独立 window
 *  └ va4.m4  601x884           （混淆类，菜单外壳）
 *     └ va4.j3  525x808        （混淆类，装菜单项的容器）
 *        ├ TextView '标为未读'
 *        ├ TextView '取消置顶'
 *        ├ TextView '不显示该聊天'
 *        └ TextView '删除该聊天'
 * ```
 *
 * 三条硬证据：
 *  - `MenuBuilder.add` 全挂钩子，**零命中**（所以不是 Menu/MenuItem 那套）；
 *  - `View.createContextMenu` 沿 View 链被调用 16 层，每层 `size` 都是 0
 *    （微信压根没往 ContextMenu 里放过东西，我们 add 出来的那个是**另起一个**
 *    系统菜单，跟微信这个毫无关系）；
 *  - 顶层窗口差集dump 出来的就是上面那棵树。
 *
 * ## 为什么之前的版本会让微信菜单消失（已翻案）
 *
 * 元凶是 **K7 对 `OnItemLongClickListener` 的包装**（不是注入本身）：
 * 微信 `conversation.z2` 内部会回头调 `list.getOnItemLongClickListener()`，
 * 拿到的就是我们的 wrapper，于是它的菜单逻辑不再执行。
 * 把 `chat.conv_longclick_remap` 置 false（不包装）后，微信 4 项菜单立刻恢复。
 * → 现在默认不包装，长按链路零干预。
 *
 * ## 现在的做法
 *
 * 1. `View.performLongClick`（长按链路第一站，早于微信 show 菜单）记下目标 wxid；
 * 2. `PopupWindow.showAsDropDown/showAtLocation` 的 **after** 里认出会话菜单容器，
 *    照着现有菜单项克隆两行 `TextView` 追加进去，再 `popup.update()` 重排高度。
 *
 * ## 三个必须防的坑
 *
 * 1. **别在 show 之前注入**：菜单项的高（实测 202px）是测量后才有的，
 *    before 阶段 `sample.height` 还是 0，克隆出来的行会矮一大截。
 * 2. **重复添加**：同一个 PopupWindow 可能 show 两次 → 按 tag 查重。
 * 3. **别误伤别的浮层**：只在「刚长按过会话」的 4 秒窗口内动手，且要求容器里
 *    同时命中 ≥2 个微信菜单文案才算数。文案判据失效时只会**不注入**（安全降级）。
 *
 * ⚠️ 本方案**必须真手长按**验证：adb 的 `input` 长按在这台设备上会被识别成点击。
 */
object ConvMenuInject {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1) 长按目标：记录「刚才长按的是哪个会话」（PopupWindow 注入要用它拿 wxid）
        installWxidTrap()
        // 2) ★ 真正的注入点：微信的会话菜单就是它自己画的 PopupWindow
        installPopupInject()
        // 3) 诊断钩子（默认不装）
        if (diag) installDiag(lpparam)
    }

    // ------------------------------------------------------- ① 长按目标 wxid

    /** 最近一次长按命中的会话 wxid —— 注入菜单时用它定位「操作谁」 */
    @Volatile
    private var lastWxid: String = ""

    @Volatile
    private var lastName: String = ""

    @Volatile
    private var lastAt: Long = 0L

    /**
     * 在长按**最开始**记下目标会话。
     *
     * ⚠️ 不能用 `View.createContextMenu` 记：AOSP 那条兜底发生在微信自己的菜单
     * show 之**后**（z2 返回 false 才轮到它），等它跑到，菜单早弹完了。
     * `View.performLongClick` 是整个长按链路的第一站，一定早于微信 show 菜单。
     */
    private fun installWxidTrap() {
        runCatching {
            val cb = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val v = param.thisObject as? View ?: return
                        val list = findListView(v) ?: return
                        val pos = runCatching { list.getPositionForView(v) }.getOrDefault(-1)
                        if (pos < 0) return
                        // 非会话行（header / 分组行 / 不是我们包装过的列表）返回 null
                        val wxid = ConvFilter.wxidAt(list, pos) ?: fallbackWxid(list, pos) ?: return
                        lastWxid = wxid
                        lastName = runCatching { GroupDb.current()?.findFriend(wxid)?.nickname }
                            .getOrNull()?.takeIf { it.isNotBlank() }
                            ?: titleOf(v).takeIf { it.isNotBlank() }
                            ?: wxid
                        lastAt = System.currentTimeMillis()
                        LogX.d("CMI target wxid=$wxid pos=$pos name=$lastName")
                    }
                }
            }
            val n = hookAll(View::class.java, "performLongClick", cb)
            LogX.i("CMI installed wxid trap (View.performLongClick x$n)")
        }.onFailure { LogX.e("CMI wxid trap install failed", it) }
    }

    /**
     * wxid 兜底：[ConvFilter.wxidAt] 依赖 K7 的包装 adapter，包装没装上
     * （adapter 结构不匹配 / 反射失败）时它恒返回 null，注入就永远不触发且零报错。
     * 这里直接问微信的 adapter 要 username，作为降级路径。
     */
    private fun fallbackWxid(list: ListView, pos: Int): String? = runCatching {
        val item = list.adapter?.getItem(pos) ?: return null
        ConvReader.username(item)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ------------------------------------------------------- ② PopupWindow 注入

    /**
     * 微信会话菜单里那些**必现**的中文项，用来认出「这是个会话菜单」。
     *
     * ⚠️ 判据是文案，微信改字就失效 —— 但失效只会导致**不注入**（安全降级），
     * 不会误伤别的浮层：要求同一个父容器里同时命中 ≥2 个才算。
     * 「置顶该聊天 / 取消置顶」随状态二选一，所以只放稳定项。
     */
    private val MENU_WORDS = setOf(
        "标为未读", "标记未读", "标记未读消息",
        "不显示该聊天", "删除该聊天",
    )

    private const val TAG_JOIN = "wxgroup.menu.join"
    private const val TAG_READ = "wxgroup.menu.read"

    private fun installPopupInject() {
        runCatching {
            val cb = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = tryInject(param)
            }
            val n1 = hookAll(android.widget.PopupWindow::class.java, "showAsDropDown", cb)
            val n2 = hookAll(android.widget.PopupWindow::class.java, "showAtLocation", cb)
            LogX.i("CMI installed popup inject (showAsDropDown=$n1 showAtLocation=$n2)")
        }.onFailure { LogX.e("CMI popup inject install failed", it) }
    }

    /**
     * 在菜单 **show 之后**注入。
     *
     * 为什么不放在 before：菜单项的高是**测量后**才有的（实测 202px），
     * show 之前 `sample.height` 还是 0，克隆出来的行会矮一大截。
     * show 之后加完再 `popup.update()` 让窗口按新高度重排即可。
     */
    private fun tryInject(param: MethodHookParam) {
        runCatching {
            if (!Kv.getBool(Keys.CHAT_CONV_MENU_ENABLED)) return
            // 只在「刚刚长按过会话」的 4 秒窗口内动手，避免误伤微信其它浮层
            if (lastWxid.isBlank() || System.currentTimeMillis() - lastAt > 4000) return
            val popup = param.thisObject as? PopupWindow ?: return
            LogX.d("CMI popup show content=${runCatching { popup.contentView?.javaClass?.simpleName }.getOrNull()}")
            // 公开 API 优先（Android 16 上字段名仍在，但反射能不用就不用）
            val cv = runCatching { popup.contentView }.getOrNull()
                ?: runCatching { XposedHelpers.getObjectField(popup, "mContentView") as? View }
                    .getOrNull()
                ?: return
            val wxid = lastWxid
            val name = lastName
            // ⚠️ show 的瞬**菜单项往往还没挂上去**（实测 show 后马上找，容器是空的）。
            // 所以不能只试一次，要沿主线程轮询几帧，直到认出菜单容器为止。
            injectWhenReady(popup, cv, wxid, name, 0)
        }.onFailure { LogX.w("CMI tryInject threw: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * 轮询等菜单项就位再注入。
     *
     * 实测：长按后 60ms show，此时 contentView 里还没有那 4 个 TextView；
     * 700ms 后 dump 才看得到完整结构。所以这里按 0/40/80/… 逐帧重试。
     */
    private fun injectWhenReady(
        popup: PopupWindow, cv: View, wxid: String, name: String, attempt: Int
    ) {
        runCatching {
            // 菜单已经被关掉了就别再往里加（否则会加到下次复用的实例上）
            if (runCatching { popup.isShowing }.getOrDefault(true).not()) return
            val box = findMenuBox(cv)
            val sample = box?.let { sampleItem(it) }
            if (box == null || sample == null) {
                if (attempt >= 8) {
                    LogX.i("CMI popup box NOT found after $attempt tries, tree:" )
                    val lines = mutableListOf<String>()
                    dumpTree(cv, 0, lines)
                    for (l in lines.take(25)) LogX.i("CMI TREE $l")
                    return
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { injectWhenReady(popup, cv, wxid, name, attempt + 1) }
                }, if (attempt == 0) 40 else 80)
                return
            }
            // ⚠️ 不能「见到旧 tag 就直接 return」：微信很可能复用同一个 PopupWindow
            // 实例给下一个会话，那样菜单里留下的会是**上一次**那个人的项（加错人）。
            // 摘旧项由 [addItems] 按宿主类型处理（ListView 用 removeFooterView）。
            val mine = addItems(box, sample, popup, wxid, name)
            // show 的 after 回调里窗口往往还没 traversal，sample.height 可能仍是 0。
            // post 一帧再量真实行高并 update，保证和原生项等高。
            box.post {
                runCatching {
                    val h = sample.height
                    if (h > 0) {
                        for (tv in mine) {
                            tv.layoutParams?.let { it.height = h }
                            tv.requestLayout()
                        }
                    }
                    box.requestLayout()
                    // ⚠️ 菜单窗口和容器很可能是**固定高度**（实测 601x884，列表 525x808
                    // 刚好被 4 项 202px 填满），只加不改尺寸的话新项会被裁在外面看不见。
                    val need = if (h > 0) h * mine.size else 0
                    grow(box, need)
                    val w = runCatching { popup.width }.getOrDefault(-2)
                    val ph = runCatching { popup.height }.getOrDefault(-2)
                    if (need > 0 && ph > 0) {
                        // PopupWindow.update 只接受 >0 的尺寸，负数会被忽略
                        popup.update(if (w > 0) w else -2, ph + need)
                    } else {
                        popup.update(if (w > 0) w else -2, -2)
                    }
                    LogX.i(
                        "CMI relayout need=$need box=${box.width}x${box.height} " +
                            "child=${box.childCount} " +
                            "join.attached=${mine.firstOrNull()?.parent != null} " +
                            "join.h=${runCatching { mine.firstOrNull()?.height }.getOrNull()} " +
                            "popup=${w}x${ph} showing=${runCatching { popup.isShowing }.getOrNull()}"
                    )
                }.onFailure { LogX.w("CMI popup relayout failed: ${it.javaClass.simpleName}") }
            }
        }.onFailure {
            LogX.w("CMI injectWhenReady threw: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 让菜单容器**连同它的父容器**一起长高 [need] 像素。
     *
     * 只改 ListView 自己没用：父容器（实测 va4.m4）也是固定高度，
     * 列表长高了也会被父容器裁掉，所以沿 parent 往上一起加。
     */
    private fun grow(v: View, need: Int) {
        if (need <= 0) return
        var cur: View? = v
        var guard = 0
        while (cur != null && guard++ < 4) {
            val lp = cur.layoutParams
            if (lp != null && lp.height > 0) {
                lp.height += need
                cur.requestLayout()
            }
            cur = cur.parent as? View
        }
    }

    /** 取证用：把一棵 View 树打成文本（深度/行数都有限，不会刷爆日志） */
    private fun dumpTree(v: View, depth: Int, out: MutableList<String>) {
        if (depth > 8 || out.size >= 40) return
        val sb = StringBuilder()
        repeat(depth) { sb.append("  ") }
        sb.append(v.javaClass.name)
            .append('#').append(if (v.id == View.NO_ID) "-" else Integer.toHexString(v.id))
            .append(' ').append(v.width).append('x').append(v.height)
            .append(" child=").append((v as? ViewGroup)?.childCount ?: 0)
        val t = (v as? TextView)?.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) sb.append(" txt='").append(t.take(20)).append('\'')
        out.add(sb.toString())
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                runCatching { v.getChildAt(i)?.let { dumpTree(it, depth + 1, out) } }
            }
        }
    }

    /** 找到装菜单项的那个父容器（实测是混淆类 `va4.j3`） */
    private fun findMenuBox(root: View): ViewGroup? {
        var found: ViewGroup? = null
        fun walk(v: View, depth: Int) {
            if (found != null || depth > 8) return
            if (v is ViewGroup) {
                var hits = 0
                for (i in 0 until v.childCount) {
                    val c = runCatching { v.getChildAt(i) }.getOrNull() ?: continue
                    val t = (c as? TextView)?.text?.toString()?.trim()
                    // 要求**同一个父容器**里同时命中 ≥2 个，才算认出会话菜单
                    if (t != null && MENU_WORDS.any { w -> t == w || t.contains(w) }) hits++
                }
                if (hits >= 2) {
                    found = v
                    return
                }
                for (i in 0 until v.childCount) {
                    val c = runCatching { v.getChildAt(i) }.getOrNull() ?: continue
                    walk(c, depth + 1)
                }
            }
        }
        walk(root, 0)
        return found
    }

    /** 拿一个现成的菜单项当样式样本（取命中的最后一个） */
    private fun sampleItem(box: ViewGroup): TextView? {
        var last: TextView? = null
        for (i in 0 until box.childCount) {
            val c = runCatching { box.getChildAt(i) }.getOrNull() ?: continue
            val t = (c as? TextView)?.text?.toString()?.trim()
            if (t != null && MENU_WORDS.any { w -> t == w || t.contains(w) }) last = c as TextView
        }
        return last
    }

    /**
     * 把两项塞进菜单容器。
     *
     * ⚠️ 实测容器是 **AdapterView**（`addView` 直接抛
     * `UnsupportedOperationException: addView(View) is not supported in AdapterView`）
     * —— 菜单项是 adapter 生成的，不能直接加子 View。所以按能力分三路：
     *
     * 1. 是 `ListView` → 走 `addFooterView`（ListView 自己会把 adapter 包成
     *    HeaderViewListAdapter，不用我们碰微信的 adapter）；
     * 2. 别的 AdapterView → 加到它的**父容器**里（排在列表下方）；
     * 3. 普通 ViewGroup → 直接 addView。
     */
    private fun addItems(
        box: ViewGroup, sample: TextView, popup: PopupWindow,
        wxid: String, name: String
    ): List<TextView> {
        val ctx = sample.context
        val joined = runCatching { GroupDb.current()?.groupsOf(wxid) }
            .getOrDefault(null) ?: emptyList()
        val joinLabel = if (joined.isEmpty()) "加入分组" else "分组管理（已加入 ${joined.size} 个）"
        val join = newItem(ctx, sample, joinLabel, TAG_JOIN) {
            runCatching { popup.dismiss() }
            onJoin(wxid, name)
        }
        val read = newItem(ctx, sample, "标记已读", TAG_READ) {
            runCatching { popup.dismiss() }
            onMarkRead(ctx, wxid)
        }
        val mine = listOf(join, read)

        val h0 = runCatching { sample.height }.getOrDefault(0)
        val h = if (h0 > 0) h0 else ViewGroup.LayoutParams.WRAP_CONTENT
        val kind: String
        when {
            box is ListView -> {
                kind = "footer"
                // 复用同一个 PopupWindow 时先把上一次的摘掉，否则越加越多
                for (t in arrayOf(TAG_JOIN, TAG_READ)) {
                    runCatching { box.findViewWithTag<View>(t)?.let { box.removeFooterView(it) } }
                }
                for (v in mine) {
                    // 必须是 AbsListView.LayoutParams，见 [newItem] 的注释
                    v.layoutParams = android.widget.AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, h
                    )
                }
                // ⚠️ 第三参数 isSelectable=false：否则点我们的项会**同时**触发微信
                // 自己的 OnItemClickListener（它按 position 取 adapter item，
                // footer 位置上拿到的是 null，很可能 NPE 把微信带崩）。
                // 不可选中只是不派发 item click，View 自己的 OnClickListener 照常收到。
                box.addFooterView(join, null, false)
                box.addFooterView(read, null, false)
            }

            box is android.widget.AdapterView<*> -> {
                val p = box.parent
                if (p is ViewGroup && p !is android.widget.AdapterView<*>) {
                    kind = "parent"
                    for (t in arrayOf(TAG_JOIN, TAG_READ)) {
                        runCatching { p.findViewWithTag<View>(t)?.let { p.removeView(it) } }
                    }
                    for (v in mine) {
                        v.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, h
                        )
                    }
                    p.addView(join)
                    p.addView(read)
                } else {
                    throw UnsupportedOperationException("no host for AdapterView box")
                }
            }

            else -> {
                kind = "addView"
                for (t in arrayOf(TAG_JOIN, TAG_READ)) {
                    runCatching { box.findViewWithTag<View>(t)?.let { box.removeView(it) } }
                }
                // 先 addView 再改 lp：自定义 ViewGroup 会在 addView 里按自己的规则
                // 生成 LayoutParams，直接塞外面 new 的容易不匹配。
                box.addView(join)
                box.addView(read)
                if (h0 > 0) {
                    for (v in mine) {
                        v.layoutParams?.let { it.height = h0 }
                        v.requestLayout()
                    }
                }
            }
        }
        LogX.i(
            "CMI popup injected wxid=$wxid name=$name joined=${joined.size} kind=$kind " +
                "box=${box.javaClass.name} isListView=${box is ListView} " +
                "adapter=${runCatching { (box as? android.widget.AdapterView<*>)?.adapter?.javaClass?.name }.getOrNull()} " +
                "parent=${runCatching { box.parent?.javaClass?.name }.getOrNull()} " +
                "sampleH=${runCatching { sample.height }.getOrDefault(-1)}"
        )
        return mine
    }

    /** 照着现有菜单项克隆一行：宽度撑满、等高、同字号同颜色同内边距 */
    private fun newItem(
        ctx: Context, sample: TextView, label: String, tag: String, onClick: () -> Unit
    ): TextView {
        val tv = TextView(ctx)
        tv.text = label
        tv.tag = tag
        runCatching { tv.gravity = sample.gravity }
        runCatching { tv.setTextColor(sample.textColors) }
        runCatching {
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sample.textSize)
        }
        runCatching {
            sample.background?.constantState?.newDrawable()?.let { tv.background = it }
        }
        tv.setPadding(sample.paddingLeft, sample.paddingTop, sample.paddingRight, sample.paddingBottom)
        // ⚠️ 这里**不要**预设 LayoutParams：加进 ListView 时必须是
        // `AbsListView.LayoutParams`（`AbsListView.setupChild` 会无条件强转，
        // 塞普通的会 ClassCastException 直接崩微信）。由调用方按宿主类型决定。
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { runCatching(onClick) }
        return tv
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

        // 1b) ★ 关键：谁往菜单里 add 项、从哪调用的。
        //     微信的原生 4 项（标为未读/置顶/不显示/删除）在 createContextMenu 链上
        //     抓不到（size 恒 0），所以必须从「所有 add 入口」反查它走的是哪条路。
        runCatching {
            val cls = XposedHelpers.findClass(
                "com.android.internal.view.menu.MenuBuilder", lpparam.classLoader
            )
            val cb = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val menu = param.thisObject ?: return
                        val title = param.args?.lastOrNull()?.toString().orEmpty()
                        LogX.i(
                            "CMI diag ADD #${seq()} menu=${id(menu)} title='$title' " +
                                "size=${sizeOf(menu)} from ${caller(5)}"
                        )
                    }
                }
            }
            val n = hookAll(cls, "addInternal", cb) + hookAll(cls, "add", cb)
            LogX.i("CMI diag: MenuBuilder add hooked ($n)")
        }.onFailure {
            LogX.e("CMI diag add hook failed: ${it.javaClass.simpleName}: ${it.message}")
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

        // 3) ★ 长按后 700ms 抓「屏幕上新出现的浮层」。
        //
        //    实测微信那 4 项菜单**不走 MenuBuilder.add**（零命中），所以它不是
        //    AOSP ContextMenu，而是微信自己的浮层。用「顶层子 View 差集」把它揪出来：
        //    长按前记下 DecorView 的直接子 View，长按后 700ms 再看，多出来的那个
        //    就是菜单容器 —— 拿到类名后再决定往哪注入。
        //    这比装 70 个 hook（MenuHuntProbe）轻得多，微信不会卡。
        runCatching {
            val cb = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { baseRoots = windowRoots() }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        Handler(Looper.getMainLooper()).postDelayed({
                            runCatching { dumpAdded() }
                        }, 700)
                    }
                }
            }
            val n = hookAll(View::class.java, "performLongClick", cb)
            LogX.i("CMI diag: View.performLongClick hooked ($n)")
        }.onFailure {
            LogX.e("CMI diag performLongClick hook failed: ${it.javaClass.simpleName}")
        }
    }

    /**
     * 长按前拍的基线：**所有窗口**的 root View。
     *
     * ⚠️ 不能只拍 DecorView：微信的菜单/浮层是 PopupWindow，它挂在**独立 window**
     * 的 `PopupDecorView` 上，永远不是 DecorView 的子 View —— 只比 DecorView 的子
     * View，diff 恒为 0，会被误读成「长按后没有任何浮层」。
     */
    @Volatile
    private var baseRoots: List<View> = emptyList()

    /**
     * 当前进程所有窗口的 root View（含 PopupWindow / Dialog）。
     *
     * 取 `WindowManagerGlobal.getInstance().mViews`；拿不到（隐藏 API 被拦）就退成
     * 空列表，日志里会显示 `baseline=0`，能一眼看出来是这条路没走通，而不是没浮层。
     */
    private fun windowRoots(): List<View> {
        val out = ArrayList<View>()
        runCatching {
            val g = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(
                    "android.view.WindowManagerGlobal", View::class.java.classLoader
                ), "getInstance"
            )
            val views = XposedHelpers.getObjectField(g, "mViews")
            if (views is Iterable<*>) {
                for (v in views) (v as? View)?.let(out::add)
            }
        }.onFailure {
            LogX.w("CMI diag windowRoots failed: ${it.javaClass.simpleName}")
        }
        return out
    }

    /** 把「长按后新出现的窗口」连同它的子树打出来 */
    private fun dumpAdded() {
        val now = windowRoots()
        val base = baseRoots
        // ⚠️ 必须用引用比较（`===`）：identityHashCode 有碰撞可能，会把新窗口
        // 误判成旧的（假阴性），直接毁掉这次取证。
        val added = now.filter { v -> base.none { it === v } }
        LogX.i(
            "CMI diag TOP #${seq()} windows=${now.size} baseline=${base.size} added=${added.size}"
        )
        for (v in added) {
            val lines = mutableListOf<String>()
            walkV(v, 0, lines)
            // 合并成几行再写：LogX 每次 appendText 都是一次 open/write/close，
            // 且全模块共用 5000 行上限 —— 逐行写会把它一次性耗光。
            for (chunk in lines.chunked(5)) {
                LogX.i("CMI ADDED #${seq()} " + chunk.joinToString(" | "))
            }
        }
    }

    private fun walkV(v: View, depth: Int, out: MutableList<String>) {
        if (depth > 10 || out.size >= 40) return
        val sb = StringBuilder()
        repeat(depth) { sb.append("  ") }
        sb.append(v.javaClass.name)
            .append('#').append(if (v.id == View.NO_ID) "-" else Integer.toHexString(v.id))
            .append(' ').append(v.width).append('x').append(v.height)
            .append(" vis=").append(v.visibility)
        val t = (v as? android.widget.TextView)?.text?.toString()
        if (!t.isNullOrEmpty()) sb.append(" txt='").append(t.take(24)).append('\'')
        out.add(sb.toString())
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                runCatching { v.getChildAt(i)?.let { walkV(it, depth + 1, out) } }
            }
        }
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

    /**
     * 取调用栈里**第一个非框架帧**，用来看清「谁在 add 菜单项」。
     *
     * 过滤掉 Xposed 桥接、反射、以及我们这个模块自己的帧 —— 这些帧每层都出现，
     * 会把真正有信息量的那一层挤出窗口（上一轮探针就栽在这）。
     */
    private fun caller(limit: Int): String = runCatching {
        val st = Throwable().stackTrace
        val skip = listOf(
            "de.robv.android.xposed", "java.lang.reflect", "com.lx.wxgroup",
            "android.os.Handler", "android.os.Looper", "android.app.ActivityThread",
        )
        st.asSequence()
            .filterNot { f -> skip.any { f.className.startsWith(it) } }
            .filterNot { f -> f.className == "android.view.View" && f.methodName == "createContextMenu" }
            .take(limit)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    }.getOrDefault("?")

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
