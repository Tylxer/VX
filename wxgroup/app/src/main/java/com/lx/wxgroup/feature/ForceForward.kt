package com.lx.wxgroup.feature

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.ui.Panel
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 需求 6.6「强制转发」的 hook 侧：聊天页长按消息菜单里追加一项「转发到多个（模块）」。
 *
 * 纯判据（菜单归类 / 目标展开 / 降级策略 / 汇总文案）全部在 [ForwardLogic]（零 Android
 * 依赖、有单测）；这里只做 UI / 反射 / hook。
 *
 * ## 入口判据（挂错地方 = 最贵误判）
 * 与 [ConvMenuInject] 共用同一套注入点：hook `PopupWindow.showAsDropDown /
 * showAtLocation` 的 after。微信的长按菜单是自绘 PopupWindow（会话列表菜单已真机取证），
 * 聊天消息菜单按同一范式处理，但**必须先过 [ForwardLogic.classifyMenu] 确认是
 * CHAT_MSG 才注入**：
 * - 认成 CONV_LIST → 明确不注入（那是 [ConvMenuInject] 的地盘，需求 6.6 不管会话列表）；
 * - 认成 NONE → 静默放弃（别的浮层，绝不多手）。
 *
 * ## v1 边界（诚实声明，详见 [nativeForward] / [resendText] 与报告「取证缺口」）
 * - 菜单注入、目标选择（K3 选择器两段：好友·群聊 / 模块分组）、目标展开去重、
 *   排除当前会话、策略选择、汇总 toast —— **全部接通**，可上机验证；
 * - 「发出去」这一步**未接通**：原生转发锚点未取证（G1）、降级发送（K8）项目里
 *   根本不存在（G3）。未取证前两条发送分支恒记 failed —— **严禁猜微信内部
 *   类名/方法名**（猜错 = 崩微信）。
 *
 * ## 复用 [ConvMenuInject] 的三条硬经验（都踩过坑，别破）
 * 1. show 的瞬间菜单项往往还没挂上 → 主线程轮询重试（0/40/80…ms，最多 8 次）；
 * 2. 容器是 AdapterView，`addView` 抛 UnsupportedOperationException →
 *    ListView 走 `addFooterView(v, null, false)`（isSelectable=false 必须，否则
 *    同时触发微信 OnItemClick，footer 位置 getItem 为 null 可能崩）；
 * 3. 加进去的 View 必须是 `AbsListView.LayoutParams`（setupChild 无条件强转，
 *    普通 LP → ClassCastException 崩微信）。窗口固定高度，还要让 box/父容器/
 *    popup 一起长高再 `popup.update(w, h+need)`。
 */
object ForceForward {

    /** 注入项的 tag（按 tag 查重，PopupWindow 复用实例时先摘旧的） */
    private const val TAG_FORWARD = "wxgroup.menu.forward"

    /** 菜单项文案。含「转发」（与原生项并存时 UI 断言数 2）+「模块」角标（需求 6.6） */
    private const val LABEL_FORWARD = "转发到多个（模块）"

    /** 微信绿（模块项用绿色与原生黑色项区分） */
    private const val COLOR_MODULE = 0xFF07C160.toInt()

    /** 长按后多久内的 PopupWindow 才理会（秒级窗口，避免误伤其它浮层） */
    private const val LONG_PRESS_WINDOW_MS = 4000L

    /** 一次长按沿 View 链触发多个 performLongClick，间隔超过它算新一轮 */
    private const val BURST_GAP_MS = 500L

    /** 长按快照的保质期（点菜单项时还要靠它拿消息视图） */
    private const val BURST_TTL_MS = 10_000L

    /** View 树遍历的节点预算（防超大气泡把微信卡住） */
    private const val WALK_BUDGET = 400

    /** 提取文本的最大长度（转发超大文本不现实） */
    private const val MAX_TEXT = 4000

    /** 当前聊天页的会话 wxid（微信沿用多年的 intent extra 约定，见 [com.lx.wxgroup.core.ChatOpener]） */
    private const val EXTRA_CHAT_USER = "Chat_User"

    // ------------------------------------------------------------ 状态

    /**
     * 运行时开关（**不是设置项** —— 需求 6.6 明确「不加开关，默认常开」）。
     * 存在的唯一目的是真机反向对照：`op=forward sub=off` 关掉再复现一次。
     */
    @Volatile
    var enabled: Boolean = true
        private set

    private val popupsSeen = AtomicLong(0)
    private val injected = AtomicInteger(0)
    private val convSkipped = AtomicInteger(0)
    private val misses = AtomicInteger(0)

    @Volatile
    private var lastKind: String = "-"

    @Volatile
    private var lastPlan: String = "-"

    /** 最近一次被判定为聊天消息菜单的文案清单（探针 dump 用） */
    @Volatile
    private var lastChatMenuTexts: List<String> = emptyList()

    /** 一次长按 burst 里最深处的 View（子 View 先跑 performLongClick） */
    private val pressLock = Any()
    private var burstStartAt = 0L
    private var burstDeepest: WeakReference<View>? = null

    @Volatile
    private var lastPressAt = 0L

    // ------------------------------------------------------------ install

    /** 签名与其它 feature 的 install 保持一致（lpparam 预留，当前 hook 的都是 android 框架类） */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1) 长按链路第一站记目标视图（注入判据与点击时取原料都要用）
        installLongPressTrap()
        // 2) ★ 注入点：微信的长按菜单就是它自己画的 PopupWindow
        installPopupInject()
    }

    /**
     * 在长按**最开始**记下目标视图。
     *
     * ⚠️ 与 [ConvMenuInject] 的 wxid trap 同理：`View.performLongClick` 是整个长按
     * 链路的第一站，一定早于微信 show 菜单。一次长按会让 View 链上多个 View 依次
     * 触发，**最深处的先跑**，所以每个 burst 只认第一个。
     */
    private fun installLongPressTrap() {
        runCatching {
            val cb = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val v = param.thisObject as? View ?: return
                        val now = System.currentTimeMillis()
                        synchronized(pressLock) {
                            if (now - burstStartAt > BURST_GAP_MS) {
                                burstStartAt = now
                                burstDeepest = WeakReference(v)
                            }
                        }
                        lastPressAt = now
                    }
                }
            }
            val n = hookAll(View::class.java, "performLongClick", cb)
            LogX.i("FF installed long-press trap (View.performLongClick x$n)")
        }.onFailure { LogX.e("FF long-press trap install failed", it) }
    }

    private fun installPopupInject() {
        runCatching {
            val cb = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = tryInject(param)
            }
            val n1 = hookAll(PopupWindow::class.java, "showAsDropDown", cb)
            val n2 = hookAll(PopupWindow::class.java, "showAtLocation", cb)
            LogX.i("FF installed popup inject (showAsDropDown=$n1 showAtLocation=$n2)")
        }.onFailure { LogX.e("FF popup inject install failed", it) }
    }

    // ------------------------------------------------------------ 注入

    private fun tryInject(param: MethodHookParam) {
        runCatching {
            if (!enabled) return
            // 只在「刚刚长按过」的窗口内动手，避免误伤微信其它浮层（+菜单、表情面板…）
            if (lastPressAt <= 0L || System.currentTimeMillis() - lastPressAt > LONG_PRESS_WINDOW_MS) return
            val popup = param.thisObject as? PopupWindow ?: return
            val cv = runCatching { popup.contentView }.getOrNull()
                ?: runCatching { XposedHelpers.getObjectField(popup, "mContentView") as? View }.getOrNull()
                ?: return
            popupsSeen.incrementAndGet()
            // show 的瞬间菜单项往往还没挂上去 → 轮询等就位再归类、再注入
            injectWhenReady(popup, cv, 0)
        }.onFailure { LogX.w("FF tryInject threw: ${it.javaClass.simpleName}") }
    }

    /**
     * 轮询等菜单项就位，过 [ForwardLogic.classifyMenu] 归类后注入。
     *
     * 先归类再找容器：认成 CONV_LIST 明确撤退（会话列表菜单不归本功能管）；认成
     * NONE 才重试（项可能还没挂上）；CHAT_MSG 才注入。
     */
    private fun injectWhenReady(popup: PopupWindow, cv: View, attempt: Int) {
        runCatching {
            if (!runCatching { popup.isShowing }.getOrDefault(true)) return
            when (ForwardLogic.classifyMenu(collectTexts(cv))) {
                ForwardLogic.MenuKind.CONV_LIST -> {
                    // 会话列表菜单：不是我们的菜，记一笔就撤（探针要能区分「没动」和「没认出」）
                    convSkipped.incrementAndGet()
                    lastKind = "CONV_LIST"
                    return
                }
                ForwardLogic.MenuKind.NONE -> {
                    if (attempt >= 8) {
                        misses.incrementAndGet()
                        lastKind = "MISS"
                        LogX.i("FF no chat menu after $attempt tries")
                        return
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching { injectWhenReady(popup, cv, attempt + 1) }
                    }, if (attempt == 0) 40 else 80)
                    return
                }
                ForwardLogic.MenuKind.CHAT_MSG -> Unit
            }
            lastKind = "CHAT_MSG"
            lastChatMenuTexts = collectTexts(cv)
            val found = findMenuBox(cv)
            if (found == null) {
                misses.incrementAndGet()
                lastKind = "MISS"
                LogX.w("FF classified CHAT_MSG but no box found")
                return
            }
            val (box, sample) = found
            val mine = addItems(box, sample, popup)
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
                    val need = if (h > 0) h * mine.size else 0
                    grow(box, need)
                    val w = runCatching { popup.width }.getOrDefault(-2)
                    val ph = runCatching { popup.height }.getOrDefault(-2)
                    if (need > 0 && ph > 0) {
                        popup.update(if (w > 0) w else -2, ph + need)
                    } else {
                        popup.update(if (w > 0) w else -2, -2)
                    }
                    LogX.i(
                        "FF injected label=$LABEL_FORWARD need=$need " +
                            "box=${box.javaClass.name} child=${box.childCount} " +
                            "popup=${w}x${ph} attached=${mine.firstOrNull()?.parent != null}"
                    )
                }.onFailure { LogX.w("FF relayout failed: ${it.javaClass.simpleName}") }
            }
        }.onFailure {
            LogX.w("FF injectWhenReady threw: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 收集整棵子树里的 TextView 文案（去空白、限预算） */
    private fun collectTexts(root: View): List<String> {
        val out = ArrayList<String>(16)
        val budget = intArrayOf(WALK_BUDGET)
        fun walk(v: View) {
            if (budget[0]-- <= 0) return
            if (v is TextView) {
                val t = runCatching { v.text?.toString()?.trim() }.getOrNull()
                if (!t.isNullOrEmpty()) out.add(t)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    runCatching { v.getChildAt(i)?.let(::walk) }
                }
            }
        }
        runCatching { walk(root) }
        return out
    }

    /**
     * 找装菜单项的那个父容器 + 样式样本。
     *
     * 判据：**同一个父容器**里同时命中 ≥2 个聊天菜单文案，且走**严格相等**
     * （与 [ForwardLogic.classifyMenu] 的计数口径一致 —— contains 会把
     * 「删除该聊天」错认成「删除」，那正是最贵误判的入口）。样本取容器内最后一个
     * 命中项，保证样式来源就是菜单自己的行。
     */
    private fun findMenuBox(root: View): Pair<ViewGroup, TextView>? {
        var found: ViewGroup? = null
        var sample: TextView? = null
        val budget = intArrayOf(WALK_BUDGET)
        fun walk(v: View) {
            if (budget[0]-- <= 0 || found != null) return
            if (v is ViewGroup) {
                var hits = 0
                for (i in 0 until v.childCount) {
                    val c = runCatching { v.getChildAt(i) }.getOrNull() ?: continue
                    val t = (c as? TextView)?.text?.toString()?.trim()
                    if (t != null && t in ForwardLogic.CHAT_MENU_WORDS) hits++
                }
                if (hits >= 2) {
                    found = v
                    for (i in 0 until v.childCount) {
                        val c = runCatching { v.getChildAt(i) }.getOrNull() ?: continue
                        if (c !is TextView) continue
                        val t = runCatching { c.text?.toString()?.trim() }.getOrNull()
                        if (!t.isNullOrEmpty() && t in ForwardLogic.CHAT_MENU_WORDS) sample = c
                    }
                    return
                }
                for (i in 0 until v.childCount) {
                    runCatching { v.getChildAt(i)?.let(::walk) }
                }
            }
        }
        runCatching { walk(root) }
        val box = found ?: return null
        val s = sample ?: return null
        return box to s
    }

    /**
     * 把「转发到多个（模块）」塞进菜单容器。三路能力分发照搬 [ConvMenuInject]
     * （ListView → addFooterView；别的 AdapterView → 加到父容器；普通 ViewGroup →
     * 直接 addView），差异只有文案/颜色/点击行为。
     */
    private fun addItems(box: ViewGroup, sample: TextView, popup: PopupWindow): List<TextView> {
        val ctx = sample.context
        val item = newItem(ctx, sample, LABEL_FORWARD, TAG_FORWARD) {
            runCatching { popup.dismiss() }
            onForwardClick()
        }
        val mine = listOf(item)

        val h0 = runCatching { sample.height }.getOrDefault(0)
        val h = if (h0 > 0) h0 else ViewGroup.LayoutParams.WRAP_CONTENT
        val kind: String
        when {
            box is ListView -> {
                kind = "footer"
                // 复用同一个 PopupWindow 时先把上一次的摘掉，否则越加越多
                runCatching { box.findViewWithTag<View>(TAG_FORWARD)?.let { box.removeFooterView(it) } }
                item.layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h
                )
                // ⚠️ isSelectable=false：否则点我们的项会同时触发微信 OnItemClickListener
                box.addFooterView(item, null, false)
            }

            box is AdapterView<*> -> {
                val p = box.parent
                if (p is ViewGroup && p !is AdapterView<*>) {
                    kind = "parent"
                    runCatching { p.findViewWithTag<View>(TAG_FORWARD)?.let { p.removeView(it) } }
                    item.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, h
                    )
                    p.addView(item)
                } else {
                    throw UnsupportedOperationException("no host for AdapterView box")
                }
            }

            else -> {
                kind = "addView"
                runCatching { box.findViewWithTag<View>(TAG_FORWARD)?.let { box.removeView(it) } }
                box.addView(item)
                if (h0 > 0) {
                    item.layoutParams?.let { it.height = h0 }
                    item.requestLayout()
                }
            }
        }
        injected.incrementAndGet()
        LogX.i("FF item added kind=$kind box=${box.javaClass.name} sampleH=$h0")
        return mine
    }

    /** 照着现有菜单项克隆一行：同字号同内边距，文字用模块绿区分原生项 */
    private fun newItem(
        ctx: android.content.Context, sample: TextView, label: String, tag: String, onClick: () -> Unit
    ): TextView {
        val tv = TextView(ctx)
        tv.text = label
        tv.tag = tag
        // ⚠️ 单行 + 末尾省略：[LABEL_FORWARD] 9 字 vs 原生 2-4 字项，容器高度按
        // 原生项量好（addItems 的 sample.height），不限行数会换行后被垂直裁切
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
        runCatching { tv.gravity = sample.gravity }
        tv.setTextColor(COLOR_MODULE)
        runCatching {
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sample.textSize)
        }
        runCatching {
            sample.background?.constantState?.newDrawable()?.let { tv.background = it }
        }
        tv.setPadding(sample.paddingLeft, sample.paddingTop, sample.paddingRight, sample.paddingBottom)
        // ⚠️ 不预设 LayoutParams：加进 ListView 时必须是 AbsListView.LayoutParams，
        // 由调用方按宿主类型决定（见 addItems）。
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { runCatching(onClick) }
        return tv
    }

    /** 让菜单容器连同父容器一起长高 [need] 像素（父容器也是固定高度，不改会被裁掉） */
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

    // ------------------------------------------------------------ 点击 → 选目标 → 发送

    private fun onForwardClick() {
        runCatching {
            val snap = snapshotNow()
            LogX.i(
                "FF forward clicked kind=${snap.kind} textLen=${snap.text.length} " +
                    "chat=${snap.sourceChat.take(24)}"
            )
            openTargetPicker(snap)
        }.onFailure { LogX.e("FF forward click failed", it) }
    }

    /**
     * 从长按 burst 里最深处的 View 提取消息文本（**只走 View 树，不碰微信内部对象**）。
     *
     * 拿得到文本 = 将来降级重发（RESEND_TEXT）的原料；拿不到就按 SKIP 处理。
     * 消息种类（msgType）锚点未取证（G2），这里**不猜**，恒 UNKNOWN。
     */
    private fun snapshotNow(): ForwardLogic.Snapshot {
        val text = pressedView()?.let { extractText(it) }.orEmpty()
        return ForwardLogic.Snapshot(
            kind = ForwardLogic.Kind.UNKNOWN,
            text = text,
            localPath = "",
            fileExists = false,
            sourceChat = currentChatTalker(),
        )
    }

    private fun pressedView(): View? = synchronized(pressLock) {
        val ref = burstDeepest ?: return null
        if (System.currentTimeMillis() - burstStartAt > BURST_TTL_MS) return null
        ref.get()
    }

    /** 取子树里最长的「像消息内容」的文本（时间戳/纯数字/菜单词都算噪声） */
    private fun extractText(root: View): String {
        var best = ""
        val budget = intArrayOf(WALK_BUDGET)
        fun walk(v: View) {
            if (budget[0]-- <= 0) return
            if (v is TextView) {
                val t = runCatching { v.text?.toString()?.trim() }.getOrNull().orEmpty()
                if (t.length >= 2 && !isNoiseText(t) && t.length > best.length) best = t.take(MAX_TEXT)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    runCatching { v.getChildAt(i)?.let(::walk) }
                }
            }
        }
        runCatching { walk(root) }
        return best
    }

    private fun isNoiseText(t: String): Boolean {
        if (t.matches(Regex("""\d{1,2}:\d{2}"""))) return true          // 时间戳
        if (t.all { it.isDigit() }) return true                          // 纯数字（未读数等）
        if (t in ForwardLogic.CHAT_MENU_WORDS || t in ForwardLogic.CONV_MENU_WORDS) return true
        if (t.length <= 2) return true                                   // 太短不像正文
        return false
    }

    /** 当前聊天页的会话 wxid（读 Activity intent 的公开 extra，拿不到就空串） */
    private fun currentChatTalker(): String {
        val a = HostContext.activity() ?: return ""
        return runCatching { a.intent?.getStringExtra(EXTRA_CHAT_USER) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.trim().orEmpty()
    }

    /**
     * K3 选择器（[PickerPage]）选转发目标。
     *
     * 需求 6.6 要三个 Tab（好友·群聊 / 模块分组 / 微信标签）。**微信标签 Tab 未实现**：
     * 标签 id→名、标签→成员两个锚点都没有（G5），`t:` 前缀展开暂不可用 —— 宁可少一段，
     * 不猜数据源。分组段 id 带 `g:` 前缀，发送前由 [ForwardLogic.resolveTargets] 展开。
     */
    private fun openTargetPicker(snap: ForwardLogic.Snapshot) {
        val ctx = HostContext.activity() ?: HostContext.app()
        if (ctx == null) {
            LogX.w("FF picker: no host context")
            return
        }
        val db = GroupDb.current()
        if (db == null) {
            LogX.w("FF picker: db not ready")
            return
        }
        val friends = runCatching { db.allRealFriends() }.getOrDefault(emptyList())
        val groups = runCatching { db.listGroups() }.getOrDefault(emptyList())
        val sections = ArrayList<PickerPage.Section>(2)
        if (friends.isNotEmpty()) {
            sections.add(
                PickerPage.Section(
                    "好友·群聊",
                    friends.map { PickerPage.Item(it.wxid, it.displayName, it.wxid) }
                )
            )
        }
        if (groups.isNotEmpty()) {
            sections.add(
                PickerPage.Section(
                    "模块分组",
                    groups.map {
                        PickerPage.Item(
                            id = "${ForwardLogic.PREFIX_GROUP}${it.id}",
                            title = it.name,
                            subtitle = "分组 · ${db.countMembers(it.id)} 人",
                        )
                    }
                )
            )
        }
        if (sections.isEmpty()) {
            UiKit.toast(ctx, "好友缓存为空，先去通讯录滚一遍再回来")
            LogX.w("FF picker: no sections (friends=${friends.size} groups=${groups.size})")
            return
        }
        LogX.i("FF picker open sections=${sections.size} friends=${friends.size} groups=${groups.size}")
        Panel.open(
            PickerPage(
                title = "转发到多个",
                sections = sections,
                multi = true,
                hint = "分组与好友可混选，发送前会展开成具体的人并去重；当前会话自动排除",
            ) { ids -> execute(snap, ids) }
        )
    }

    /**
     * 展开目标 → 选策略 → 逐目标执行 → 汇总 toast。
     *
     * v1 的诚实边界：两条发送链路都未接通（见 [nativeForward] / [resendText]），
     * 非 SKIP 分支全部记 failed；**sent==0 一律加「未实际发送」后缀**（含 SKIP：
     * 真机长按路径 kind 恒 UNKNOWN，恒走 SKIP 且 failed 恒 0，文案必须诚实）。
     * 链路接通后这里只需把对应分支换成真调用，计数与文案不用动。
     */
    private fun execute(snap: ForwardLogic.Snapshot, picked: List<String>) {
        runCatching {
            val db = GroupDb.current()
            val buckets = buildBuckets(db)
            val sourceChat = snap.sourceChat.ifBlank { currentChatTalker() }
            val exclude = if (sourceChat.isBlank()) emptySet() else setOf(sourceChat)
            val targets = ForwardLogic.resolveTargets(picked, buckets, exclude)
            val ctx = HostContext.activity() ?: HostContext.app()
            if (targets.isEmpty()) {
                LogX.i("FF no targets after resolve picked=${picked.size} exclude=$sourceChat")
                if (ctx != null) UiKit.toast(ctx, "没有可转发的目标（分组为空或全部被排除）")
                return
            }
            val plan = ForwardLogic.planFor(snap, nativeState())
            lastPlan = plan.strategy.name
            LogX.i(
                "FORWARD plan strategy=${plan.strategy} reason=${plan.reason} " +
                    "targets=${targets.size} kind=${snap.kind} textLen=${snap.text.length} " +
                    "exclude=$sourceChat"
            )
            var sent = 0
            var degraded = 0
            var skipped = 0
            var failed = 0
            when (plan.strategy) {
                ForwardLogic.Strategy.NATIVE ->
                    for (t in targets) {
                        if (nativeForward(snap, t)) sent++ else failed++
                    }
                ForwardLogic.Strategy.RESEND_TEXT ->
                    for (t in targets) {
                        if (resendText(snap, t)) { sent++; degraded++ } else failed++
                    }
                ForwardLogic.Strategy.RESEND_FILE ->
                    for (t in targets) {
                        if (resendFile(snap, t)) { sent++; degraded++ } else failed++
                    }
                ForwardLogic.Strategy.SKIP -> skipped = targets.size
            }
            val msg = ForwardLogic.summary(sent, degraded, skipped, failed)
            LogX.i("FORWARD done $msg plan=${plan.strategy}")
            if (ctx != null) {
                // sent==0 一律加后缀（含 SKIP）：真机长按路径 snapshotNow 的 kind 恒
                // UNKNOWN（G2）→ planFor 首行 isCovered(UNKNOWN)=false 恒 SKIP，
                // failed 也恒 0。若把 SKIP 排除在后缀条件外，toast 只剩「已发送 0 个
                // 目标 · 跳过 N」，看着像成功了 —— 没发出去就必须说「未实际发送」。
                val full = if (sent == 0) {
                    "$msg（发送链路未接通，未实际发送，详见日志 FORWARD）"
                } else msg
                UiKit.toast(ctx, full)
            }
        }.onFailure { LogX.e("FF execute failed", it) }
    }

    /**
     * 原生转发链路状态。v1 恒为 UNKNOWN：锚点（G1）未取证前不声称可用。
     * 取证接通后这里改成运行时探测结果（AVAILABLE / REJECTED）。
     */
    private fun nativeState(): ForwardLogic.NativeState = ForwardLogic.NativeState.UNKNOWN

    /**
     * 原生转发（保留「转发」标记）。
     *
     * ⚠️ **v1 恒 false**：需要「msgId + 目标 talker → 微信原生转发入口」的真实
     * 类名/方法名（取证缺口 G1）。接通前一律返回 false 让上层走降级。
     * **严禁猜微信内部 API** —— 猜错就是崩微信。
     */
    private fun nativeForward(snap: ForwardLogic.Snapshot, target: String): Boolean {
        LogX.w("FORWARD native anchor not located yet (G1), target=${target.take(24)}")
        return false
    }

    /**
     * 降级重发文本（无转发标记）。
     *
     * ⚠️ **v1 恒 false**：项目里没有任何「发消息」能力（K8 缺口 G3）——
     * [com.lx.wxgroup.core.ChatOpener] 只是打开会话、[com.lx.wxgroup.core.MarkRead]
     * 只改未读数，都不算发送；写消息库不产生真实投递。接通前恒 false。
     */
    private fun resendText(snap: ForwardLogic.Snapshot, target: String): Boolean {
        LogX.w("FORWARD resend-text unavailable (K8 send link missing, G3), target=${target.take(24)}")
        return false
    }

    /** 降级重发文件。同 [resendText]：v1 恒 false（G3）。 */
    private fun resendFile(snap: ForwardLogic.Snapshot, target: String): Boolean {
        LogX.w("FORWARD resend-file unavailable (K8 send link missing, G3), target=${target.take(24)}")
        return false
    }

    // ------------------------------------------------------------ 数据

    /** 分组 id → 成员 wxid。标签表（`t:`）留空：数据源未取证（G5） */
    private fun buildBuckets(db: GroupDb?): ForwardLogic.Buckets {
        if (db == null) return ForwardLogic.Buckets()
        val groups = HashMap<String, List<String>>()
        for (g in runCatching { db.listGroups() }.getOrDefault(emptyList())) {
            val members = runCatching { db.memberWxids(g.id) }.getOrDefault(emptySet())
            if (members.isNotEmpty()) groups[g.id.toString()] = members.toList()
        }
        return ForwardLogic.Buckets(groups = groups, labels = emptyMap())
    }

    // ------------------------------------------------------------ 探针 / 自检

    /**
     * 广播探针（`op=forward`）：
     * - `stat`  状态与计数（popups/injected/convSkipped/misses/lastKind/lastPlan）
     * - `on` / `off`  运行时开关（**真机反向对照用**，不写库、不进设置）
     * - `dump`  最近一次聊天消息菜单的文案清单（认人判据取证）
     * - `pick`  不走长按，直接打开选择器跑一遍「选目标→展开→策略→汇总」（半自动验收）
     */
    fun probe(sub: String): String = runCatching {
        when (sub.lowercase()) {
            "stat" ->
                "enabled=$enabled popups=${popupsSeen.get()} injected=${injected.get()} " +
                    "convSkipped=${convSkipped.get()} misses=${misses.get()} " +
                    "lastKind=$lastKind lastPlan=$lastPlan"
            "on" -> {
                enabled = true
                LogX.i("FF probe enabled=true")
                "enabled=true"
            }
            "off" -> {
                enabled = false
                LogX.i("FF probe enabled=false")
                "enabled=false"
            }
            "dump" -> "lastChatMenuTexts(${lastChatMenuTexts.size})=${lastChatMenuTexts.joinToString("|")}"
            "pick" -> {
                LogX.i("FF probe pick (synthetic snapshot)")
                openTargetPicker(
                    ForwardLogic.Snapshot(
                        kind = ForwardLogic.Kind.TEXT,
                        text = "[模块自检] 强制转发链路合成消息",
                        sourceChat = currentChatTalker(),
                    )
                )
                "picker opened (synthetic snapshot)"
            }
            else -> "subs=stat,on,off,dump,pick"
        }
    }.getOrElse { "error=${it.javaClass.simpleName}" }

    // ------------------------------------------------------------ 基础设施

    /**
     * 按名字 hook 一个类里声明的**所有**同名重载。
     *
     * ⚠️ 与 [ConvMenuInject] 同款原因：我们用的 Xposed API（`de.robv.android.xposed:api`）
     * 里没有 `XposedHelpers.hookAllMethods`（那是 api-82 的扩展），只能自己遍历
     * `declaredMethods` + `XposedBridge.hookMethod`。
     */
    private fun hookAll(cls: Class<*>, name: String, cb: XC_MethodHook): Int {
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name != name) continue
            runCatching {
                XposedBridge.hookMethod(m, cb)
                n++
            }.onFailure { LogX.w("FF hook ${cls.simpleName}.$name failed: ${it.javaClass.simpleName}") }
        }
        return n
    }
}
