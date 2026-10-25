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
import com.lx.wxgroup.core.FileSender
import com.lx.wxgroup.core.FileSenderB
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.core.TextSender
import com.lx.wxgroup.ui.Panel
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
 * - 菜单注入、目标选择（K3 三 Tab 选择器 [ForwardPicker]：好友·群聊 / 模块分组 /
 *   微信标签）、目标展开去重、排除当前会话、策略选择、批量执行与汇总 toast
 *   —— **全部接通**，可上机验证；
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

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 批量执行的单线程池。
     *
     * 单线程是刻意的：转发要**一个一个发**，并发发只会被微信自己的频率限制拦，
     * 还让「第几条失败」的日志彻底失去意义。daemon 线程，不拖住微信退出。
     */
    private val exec: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "wxg-forward").apply { isDaemon = true }
        }
    }

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
     * K3 三 Tab 选择器（[ForwardPicker]）选转发目标。
     *
     * 三个 Tab（好友·群聊 / 模块分组 / 微信标签）可混选；分组与标签在 [ForwardPicker]
     * 内部展开并去重（同一人被多个分组/标签选中只发一次），回调拿到的已经是最终
     * wxid 列表。当前会话（[currentChatTalker]）作为排除集传进去：列表里不显示，
     * 展开结果里也不会出现（转发回原会话没意义）。
     */
    private fun openTargetPicker(snap: ForwardLogic.Snapshot) {
        val sourceChat = snap.sourceChat.ifBlank { currentChatTalker() }
        val exclude = if (sourceChat.isBlank()) emptySet() else setOf(sourceChat)
        LogX.i("FF picker open exclude=$sourceChat kind=${snap.kind} textLen=${snap.text.length}")
        Panel.open(
            ForwardPicker(
                title = "转发到多个",
                exclude = exclude,
            ) { result -> execute(snap, result) }
        )
    }

    /**
     * 选策略 → **后台线程**逐目标执行 → 汇总 toast。
     *
     * 为什么不留在主线程：目标数可能几百个（分组 / 标签展开后），逐个发是串行 IO，
     * 卡住主线程会让微信 ANR。执行一律丢给 [exec]（单线程 daemon），
     * toast 再 post 回主线程。
     *
     * 失败处理（需求「单条失败不中断整批」）：每个目标各自 `runCatching`，
     * 抛异常 / 返回 false 都只计 failed，循环继续；**不加「连续失败 N 条中止」**
     * （那是 6.5 群发的规则，本任务不继承）。
     *
     * v1 的诚实边界：两条发送链路都未接通（见 [nativeForward] / [resendText]），
     * 非 SKIP 分支全部记 failed；**sent==0 一律加「未实际发送」后缀**（含 SKIP：
     * 真机长按路径 kind 恒 UNKNOWN，恒走 SKIP 且 failed 恒 0，文案必须诚实）。
     * 链路接通后这里只需把对应分支换成真调用，计数与文案不用动。
     */
    private fun execute(snap: ForwardLogic.Snapshot, picked: ForwardPicker.Result) {
        runCatching {
            val targets = picked.wxids
            if (targets.isEmpty()) {
                // 正常走不到（ForwardPicker.confirm 已挡一道），留着防探针/将来别的入口
                LogX.w("FF execute: empty targets picked=${picked.picked}")
                val c0 = HostContext.activity() ?: HostContext.app()
                if (c0 != null) UiKit.toast(c0, "没有可转发的目标（分组为空或全部被排除）")
                return
            }
            val plan = ForwardLogic.planFor(snap, nativeState())
            lastPlan = plan.strategy.name
            LogX.i(
                "FORWARD plan strategy=${plan.strategy} reason=${plan.reason} " +
                    "picked=${picked.picked} targets=${targets.size} " +
                    "kind=${snap.kind} textLen=${snap.text.length}"
            )
            exec.execute { runBatch(snap, plan, targets) }
        }.onFailure { LogX.e("FF execute failed", it) }
    }

    /** 批量执行的线程体：逐个目标发，单条失败只计数不中断 */
    private fun runBatch(
        snap: ForwardLogic.Snapshot,
        plan: ForwardLogic.Plan,
        targets: List<String>,
    ) {
        var sent = 0
        var degraded = 0
        var skipped = 0
        var failed = 0
        for (t in targets) {
            if (plan.strategy == ForwardLogic.Strategy.SKIP) {
                skipped++
                continue
            }
            val ok = runCatching {
                when (plan.strategy) {
                    ForwardLogic.Strategy.NATIVE -> nativeForward(snap, t)
                    ForwardLogic.Strategy.RESEND_TEXT -> resendText(snap, t)
                    ForwardLogic.Strategy.RESEND_FILE -> resendFile(snap, t)
                    ForwardLogic.Strategy.SKIP -> false
                }
            }.onFailure {
                LogX.w("FORWARD target threw ${t.take(24)}: ${it.javaClass.simpleName}")
            }.getOrDefault(false)
            if (ok) {
                sent++
                // 走降级的都算「已作为普通消息发送，无转发标记」
                if (plan.strategy != ForwardLogic.Strategy.NATIVE) degraded++
            } else {
                failed++
            }
        }
        val msg = ForwardLogic.summary(sent, degraded, skipped, failed)
        LogX.i("FORWARD done $msg plan=${plan.strategy} targets=${targets.size}")
        mainHandler.post {
            val ctx = HostContext.activity() ?: HostContext.app() ?: return@post
            // sent==0 一律加后缀（含 SKIP）：真机长按路径 snapshotNow 的 kind 恒
            // UNKNOWN（G2）→ planFor 首行 isCovered(UNKNOWN)=false 恒 SKIP，
            // failed 也恒 0。若把 SKIP 排除在后缀条件外，toast 只剩「已发送 0 个
            // 目标 · 跳过 N」，看着像成功了 —— 没发出去就必须说「未实际发送」。
            val text = if (sent == 0) {
                "$msg（发送链路未接通，未实际发送，详见日志 FORWARD）"
            } else {
                // 需求点名的降级提示：只要有一条走了重发分支就必须说清楚没有转发标记
                if (degraded > 0) "$msg · ${ForwardLogic.TOAST_DEGRADED}" else msg
            }
            UiKit.toast(ctx, text)
        }
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
     * ✅ **已接通（2026-10-18，S9 端到端验证）**：走 [com.lx.wxgroup.core.TextSender.send]
     * （`hl0/r1.b(talker)` → `q1.e(content)` → `q1.b()`，真机落库验证通过）。
     * 链路缺失 / 反射失败时返回 false，上层按「失败」计数（重发文本没有重试，单条失败不中断）。
     */
    private fun resendText(snap: ForwardLogic.Snapshot, target: String): Boolean {
        return TextSender.send(target, snap.text)
    }

    /**
     * 降级重发文件（无转发标记）。
     *
     * ✅ **A 档已接通（2026-10-20，静态取证；待真机）**：走 [FileSender.send]，
     * 链路 = `hl0/r1.a(o1.IMAGE|VIDEO)` → `q1.g(path)` → `q1.h(talker)` →
     * `q1.f(m)` → `q1.a()` → `MsgRetransmitUI.g6(l1)` → `l1.a()`，
     * 与微信自己的 `qf4/o4.O8`（发图）逐条同构。证据见 `docs/取证结论.md`「K8-file」段。
     *
     * ⛔ **只支持图片/视频**：`qf4/o4` 里没有「发普通文件（appmsg type=6）」的公开方法，
     * `hl0/o1` 的 APPMSG 常量在 classes7 全文零引用 ⇒ 普通文件（[ForwardLogic.Kind.FILE]）
     * 明确返回 false，**不静默假装成功**。要补需走 `pluginsdk/model/app/u0` + `WXFileObject`
     * + CDN 上传（参数 14 个），属独立的取证任务。
     */
    private fun resendFile(snap: ForwardLogic.Snapshot, target: String): Boolean {
        // 与 ForwardLogic.canResendFile 一一对应：判 true 的种类这里必须能映射出
        // 一个真能发的 FileSender.Kind，否则就是「计划层放行、发送层静默拒绝」的缺口。
        val kind = when (snap.kind) {
            ForwardLogic.Kind.IMAGE -> FileSender.Kind.IMAGE
            // 视频走 modelimage/o3 那条链，尚未取证完整（见 ForwardLogic.canResendFile 注释）
            ForwardLogic.Kind.VIDEO -> FileSender.Kind.VIDEO
            else -> FileSender.Kind.UNSUPPORTED
        }
        if (kind != FileSender.Kind.IMAGE) {
            LogX.w(
                "FORWARD resend-file unsupported kind=${snap.kind} " +
                    "(仅 IMAGE 已取证；视频待 o3.j() 取证，普通文件待 B 档), target=${target.take(24)}"
            )
            return false
        }
        if (snap.localPath.isBlank() || !snap.fileExists) {
            LogX.w(
                "FORWARD resend-file no local file kind=${snap.kind} " +
                    "path='${snap.localPath.take(80)}' exists=${snap.fileExists}"
            )
            return false
        }
        val ok = FileSender.send(kind, target, snap.localPath)
        LogX.i("FORWARD resend-file kind=${snap.kind} ok=$ok target=${target.take(24)}")
        return ok
    }

    // ------------------------------------------------------------ 探针 / 自检

    /**
     * 广播探针（`op=forward`）：
     * - `stat`  状态与计数（popups/injected/convSkipped/misses/lastKind/lastPlan）
     * - `on` / `off`  运行时开关（**真机反向对照用**，不写库、不进设置）
     * - `dump`  最近一次聊天消息菜单的文案清单（认人判据取证）
     * - `pick`  不走长按，直接打开选择器跑一遍「选目标→展开→策略→汇总」（半自动验收）
     * - `filerdy`  文件重发（A 档）链路就绪性：`resendFile.ready=` + 可发种类清单。**不发消息**，
     *              真机首验先跑这个再跑正式菜单，避免把「链路没 prepare」误判成「发送失败」
     * - `filebrdy` 普通文件（B 档 `qf4/r5`）探针就绪性。**不发消息**
     * - `fileb`    普通文件（B 档）**真发**。⚠️ 会真的发消息，必须带 `--es talker` + `--es val`
     */
    fun probe(sub: String, talker: String = "", path: String = ""): String = runCatching {
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
            "filerdy" ->
                // A 档文件重发链路就绪性（不发消息，只报状态）：真机首验先跑这个
                "resendFile.ready=${FileSender.ready()} " +
                    "kinds=${ForwardLogic.Kind.entries.filter { ForwardLogic.canResendFile(it) }}"
            "filebrdy" ->
                // B 档（普通文件 qf4/r5）探针就绪性：不发消息。
                // ready 返回 "ok" 才算真就绪；其余返回 "missing:<第一个缺失项>"，一眼定位。
                "fileb.ready=${FileSenderB.ready()} expectType=${FileSenderB.TYPE_FILE_APPMSG}"
            "fileb" ->
                // B 档探针：真发一个普通文件。⚠️ 会真的发消息，只在真机取证时用。
                // 用法：op=forward sub=fileb --es talker <wxid> --es val <本地绝对路径>
                //
                // ⚠️ 本分支会复制文件 + 入网，**必须由调用方投后台线程**（见 EntryReceiver）。
                "fileb " + FileSenderB.probe(talker, path)
            "msgsnap" -> msgsnapReport()
            else -> "subs=stat,on,off,dump,pick,filerdy,filebrdy,fileb,msgsnap"
        }
    }.getOrElse { "error=${it.javaClass.simpleName}" }

    // ------------------------------------------------------------ G2 探针（消息种类识别链）

    /**
     * G2 真机探针：一次抓全「长按的这条消息是什么」。
     *
     * 链路（全部为 `docs/_r16_g2_msgsnap.md` 里**反汇编坐实**的锚点，无一处猜测）：
     *
     * ```
     * pressedView()（长按最深 View，已有）
     *   → itemRoot = 向上 ≤12 层找「像单条消息根」的 View
     *   → 再向上 ≤12 层找列表 View，用 [listKindOf] **按继承链**判定形态：
     *        "recycler" = 继承链上有 ChattingRecyclerView（运行时是 ScrollControlRecyclerView）
     *        "listview" = 继承链上有 ChattingListView    （运行时是 ScrollControlListView / view.l）
     *     同时记录 MMChattingListView（外层容器）；parent 链没筛到时兜底调它的
     *     `getListView()`（`iget-object T:u3; return`）直接拿列表对象 ⇒ 更硬
     *   → 按 listKind 分派取 position：
     *        recycler ⇒ 候选链 [F0, G0, getChildAdapterPosition, getChildLayoutPosition]
     *                   不中再退化 A0scan（[getFirstVisiblePosition() ±50] 窗口遍历，比对 c3.d）
     *        listview ⇒ getPositionForView(v)（ListView 继承来的真实实现）
     *   → getBaseAdapter().getItem(position)   ⇒ Object（实际是 h9，需强转）
     *   → h9.getMsgId() / h9.getType()         ⇒ 未混淆 getter
     *   → field_isSend / field_imgPath         ⇒ 字段在父类 jj/m7，沿链反射
     * ```
     *
     * ⚠️ 链路图第 2 步（找列表）**不能**用 `javaClass.name.contains("ChattingListView")`：
     * 运行时对象是**子类**，类名里不含基类名（见 [listKindOf] 的 KDoc）。曾经因为这个
     * `chatList` 恒 null、`listKind` 恒 `"none"`，整条分派逻辑变死代码（R16-V2 报，P0）。
     *
     * ⚠️ 三处易踩的坑（都已实测，别改回去）：
     * 1. `ChattingListView` **没有** `getPositionForView` —— 它只继承 `ListView` 的同名方法；
     *    只有 `ChattingRecyclerView` 自己声明了 `(View)I`。但 `ChattingRecyclerView` 那个
     *    声明是**恒返回 -1 的空桩**（反汇编仅 `const/4 -1` + `return` 两条指令），
     *    所以 **RecyclerView 形态不能走 `getPositionForView`**；
     *    只有 listview 形态才走 `getPositionForView`（`ListView` 继承来的真实实现）。
     *    ⚠️ 而且 `androidx.recyclerview.widget.RecyclerView` 当前被 R8 混淆，
     *    `getChildAdapterPosition` 已更名为 `F0`、`getChildLayoutPosition` 更名 `G0`
     *    （classes12.dex 反汇编实证：`(View)I` 签名只有 F0/G0 两个方法）。反射定位必须走
     *    混淆名候选链 [F0, G0, getChildAdapterPosition, getChildLayoutPosition]，不能只试原名。
     * 2. 取 adapter **只有混淆名 `m()`**（`ChattingListView` / `ChattingRecyclerView` 都是），
     *    没有 `getAdapter`；未混淆的是 `MMChattingListView.getBaseAdapter()` ⇒ 优先用它。
     * 3. `t3.getItem(I)` 返回 **`Object`**（不是 `h9`）；`eb4/q` 才返回具体 `h9`。
     *    `getBaseAdapter()` 的静态类型是 `t3` ⇒ 拿回来是 `Any`，**按名字反射取值**，
     *    不做 `as? h9` 强转（`h9` 是微信类，模块里没有也不需要编译期引用）。
     *
     * 全程 `runCatching` 单步兜底，**每一步失败都留可见痕迹**（`list=none` / `pos=-1` /
     * `adapter=none` / `h9=none`），绝不静默吞成「看起来正常但全是 none」。
     *
     * ⚠️ **TTL 坑（真机取证必看）**：生产用的 [pressedView] 带 `BURST_TTL_MS=10s` 过期，
     * 而 adb 发广播到模块收到**经常超过 10 秒**（`am start` + sleep + adb 往返）。
     * 若直接用 [pressedView]，「长按 → 发广播」的取证会稳定拿到 null，
     * 表现成 `view=none`，极易被误判成「adapter 链不通」。
     * 所以探针走 [staleViewForProbe]：**忽略 TTL** 读最近一次长按的 View，
     * 并额外打 `ageMs=` 标明它有多陈旧（真机上这个值会明显 >10000，属正常）。
     */
    private fun msgsnapReport(): String {
        val v = staleViewForProbe()
        val ageMs = synchronized(pressLock) {
            if (burstStartAt == 0L) -1L else System.currentTimeMillis() - burstStartAt
        }
        val talker = currentChatTalker()
        val viewName = v?.javaClass?.name?.substringAfterLast('.') ?: "none"
        // ⚠️ 陈旧 View 若已从窗口摘下，getPositionForView 必然 -1 —— 这跟「链路不通」是两件事，
        //    必须单独标出来，否则真机上会被误判成 adapter 链失败。
        val attached = if (v == null) "n/a" else runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 19) v.isAttachedToWindow else true
        }.getOrNull().toString()

        var node: View? = v
        var chatList: View? = null
        // ⚠️ 必须把「继承链命中」和「类名像列表」分开存。
        //    原因：从 itemRoot 往上走时，**内层**可能先遇到一个类名含 "Recycler" 的容器
        //    （消息卡片里内嵌的横向列表之类）。若让 `unknown` 也能占住 chatList，
        //    真正的聊天列表还没走到就被抢先占位，pos 恒 -1 却 listKind != "none"
        //    ⇒ diagnose() 会误报「列表找到了但定位失败」，比 list=none 更难查。
        //    ⇒ 继承链命中（recycler/listview）优先级最高，`unknown` 只作最后兜底。
        var chatListLoose: View? = null
        var outer: View? = null
        var guard = 0
        while (node != null && guard++ < 12) {
            val n = node.javaClass.name
            // ⛔ 不要用 n.contains("ChattingListView"/"ChattingRecyclerView") 找列表！
            //    运行时对象是**子类**，类名里不含这两个基类名：
            //      ScrollControlRecyclerView  (super = ChattingRecyclerView)
            //      ScrollControlListView      (super = ChattingListView)
            //      ui.chatting.view.l         (super = ScrollControlListView -> ChattingListView)
            //    实测三者 contains("ChattingListView") 与 contains("ChattingRecyclerView") 全为 false
            //    ⇒ 曾导致 chatList 恒 null、listKind 恒 "none"、整个分派逻辑是死代码（R16-V2 报）。
            //    改为按继承链判定（见下方 listKindOf）。
            if (chatList == null) {
                when (listKindOf(node)) {
                    "recycler", "listview" -> chatList = node
                    "unknown" -> if (chatListLoose == null) chatListLoose = node
                }
            }
            if (n.contains("MMChattingListView")) {
                // ⚠️ 这个 contains 是**安全**的（已实测 MMChattingListView 全 dex 无子类），
                //    但为了防御未来加子类，用继承链再确认一次。
                outer = node
                break
            }
            node = node.parent as? View
        }
        // 兜底路径 1：parent 链没筛到时，用 MMChattingListView.getListView()
        // （`iget-object T:u3; return`）直接拿列表对象 —— 这比靠类名在 parent 链里猜更硬
        // （T 字段在 l(Context) 里赋值，就是上面那三个子类之一）。
        if (chatList == null && outer != null) {
            chatList = runCatching {
                outer.javaClass.getMethod("getListView").invoke(outer) as? View
            }.getOrNull()
        }
        // 兜底路径 2：只有前两条都失败，才用「类名像列表」的那个（日志里会被标成 unknown）。
        if (chatList == null) chatList = chatListLoose
        val listKind = if (chatList == null) "none" else listKindOf(chatList)

        // position：按 listKind 分派定位。
        //  ⚠️ ChattingRecyclerView.getPositionForView 是恒 -1 的空桩（反汇编实证），
        //    recycler 形态必须走 RecyclerView 自身定位，不能用它。
        //
        //  ⚠️ RecyclerView 在当前微信（8.0.48 / 2588 GP）的 dex 里被 R8 混淆：
        //    `getChildAdapterPosition` 已更名为 `F0`，`getChildLayoutPosition` 更名为 `G0`
        //    （classes12.dex 反汇编实证：`(View)I` 签名只有 F0/G0 两个方法）。
        //    所以候选链是 [F0, G0, getChildAdapterPosition, getChildLayoutPosition]，
        //    混淆名在前（当前版本可用）、原名在后（未混淆版本兜底）。
        var pos = -1
        var posVia = "none"
        var posFail = "none"
        if (chatList != null && v != null) {
            // `unknown`（找到了像列表的对象但继承链不认识）时：类名里带 Recycler 的按 recycler 试，
            // 否则按 listview 试 —— 都比直接放弃有信息量，且 posFail/posVia/listCls 会留下完整痕迹。
            val effKind = when {
                listKind == "recycler" -> "recycler"
                listKind == "listview" -> "listview"
                listKind == "unknown" && chatList.javaClass.name.contains("Recycler") -> "recycler"
                else -> "listview"
            }
            if (effKind == "recycler") {
                // ① 先找 item 根 View：从 v 沿 parent 链向上，直到 parent == chatList。
                var itemRoot: View? = null
                var n: View? = v
                var g2 = 0
                while (n != null && g2++ < 12) {
                    if (n.parent === chatList) { itemRoot = n; break }
                    n = n.parent as? View
                }
                if (itemRoot != null) {
                    // ② 混淆名候选链：逐个 getMethod，第一个能拿到且 invoke >= 0 的胜出。
                    //    顺序很重要：F0/G0 在前（当前版本），原名在后（未来版本兜底）。
                    //    ⚠️ getMethod 期异常（NoSuchMethodException）与 invoke 期异常必须分开捕获：
                    //       invoke 抛 ClassCastException（常被 InvocationTargetException 包着）
                    //       说明传进去的 itemRoot 不是 RecyclerView 的直接 child（I0 里 check-cast
                    //       LayoutParams 失败），是另一种失败，不能和「方法名不存在」混成 noMethod。
                    val candidates = arrayOf("F0", "G0", "getChildAdapterPosition", "getChildLayoutPosition")
                    var allMissing = true
                    var sawClassCast = false
                    for (name in candidates) {
                        val m = runCatching {
                            chatList.javaClass.getMethod(name, View::class.java)
                        }.getOrNull()
                        if (m == null) continue
                        allMissing = false
                        val r = runCatching { m.invoke(chatList, itemRoot) as Int }
                        val p = r.getOrNull() ?: -1
                        if (p >= 0) { pos = p; posVia = name; break }
                        // 展开 cause 链找 ClassCastException（invoke 会包进 InvocationTargetException）
                        var cause: Throwable? = r.exceptionOrNull()
                        while (cause != null) {
                            if (cause is ClassCastException) { sawClassCast = true; break }
                            cause = cause.cause
                        }
                    }
                    // ⚠️ 只有**最终没拿到 pos** 才记失败原因。
                    //    否则会出现自相矛盾的日志：F0 抛 classCast → G0 成功拿到 5
                    //    ⇒ `pos=5 posVia=G0 posFail=classCast`，diagnose() 会当成失败来判读。
                    if (pos < 0) {
                        if (allMissing) posFail = "noMethod"
                        else if (sawClassCast) posFail = "classCast"
                    }
                } else {
                    posFail = "itemRootNotFound"
                }
                // ③ A0 扫描降级为最后兜底：仅当候选链「全部没拿到方法」（noMethod）时才跑。
                //    扫描用 getFirstVisiblePosition() 起点 + ±50 窗口，避免几千条的 O(N) 全扫
                //    （这段跑在主线程长按回调里，全扫有 ANR 风险）。
                //    ⚠️ 绝不调用 ChattingRecyclerView.v(I)（它忽略形参、恒返回第一个可见项，空壳），
                //       只直接 A0(pos) 取 ViewHolder 比对其 d(itemView)。
                if (itemRoot != null && pos < 0 && posFail == "noMethod") {
                    var scanHit = -1
                    runCatching {
                        val a0 = chatList.javaClass.getMethod("A0", Int::class.javaPrimitiveType)
                        val first = runCatching {
                            chatList.javaClass.getMethod("getFirstVisiblePosition").invoke(chatList) as Int
                        }.getOrNull() ?: -1
                        val count = runCatching {
                            chatList.javaClass.getMethod("getCount").invoke(chatList) as Int
                        }.getOrNull() ?: 0
                        // ⚠️ getFirstVisiblePosition 非 LinearLayoutManager 时返回 -1 ⇒ 必须判 >=0，
                        //    否则窗口起点算错。拿不到时退化成从头扫但限 100 次。
                        val from: Int
                        val to: Int
                        if (first >= 0) {
                            // ⛔ 起点 = first（可见区第一个），**不再往前减 50**。
                            //    原写法 `first - 50` 是错的（R17-V3 报，P1）：A0(i) =
                            //    findViewHolderForAdapterPosition(i)，它只在本 position 有
                            //    attach 的 ViewHolder 时返回非 null，**不可见的 position 返回
                            //    null 是常态**。从 first-50 起扫，第一个 i 几乎必然落在
                            //    ViewHolder 已被回收的区间 ⇒ 原 `?: break` 第一次迭代就退出，
                            //    扫描永远扫不到真正可见的长按 item（只有列表停在顶部、
                            //    from 被 coerce 到 0 时才可能命中）。
                            //    长按的 item 必然可见 ⇒ 从 first 起扫是充分且最省的。
                            from = first
                            to = minOf(first + 50, count - 1)
                        } else {
                            from = 0
                            to = minOf(100, count - 1)
                        }
                        for (i in from..to) {
                            // ⛔ null 要 `continue` 不能 `break`（同 P1）：
                            //    A0 返回 null 的原因不止「I 字段早退（layout 进行中）」，
                            //    「该 position 的 ViewHolder 已回收」也是 null，而那恰恰是
                            //    窗口里大多数 position 的常态。break 会让扫描在第二个 position
                            //    就死掉。这里就 51~101 次反射，continue 完的开销可忽略。
                            val vh = runCatching { a0.invoke(chatList, i) }.getOrNull() ?: continue
                            val iv = XposedHelpers.getObjectField(vh, "d") as? View
                            if (iv === itemRoot) { scanHit = i; break }
                        }
                    }
                    if (scanHit >= 0) { pos = scanHit; posVia = "A0scan"; posFail = "none" }
                }
                // 候选链有方法但都返回 -1，或 A0 扫描也没命中
                if (pos < 0 && posFail == "none") posFail = "returnedMinusOne"
            } else {
                // listview 形态：getPositionForView（ListView 继承来的真实实现）。
                //  ⚠️ 失败要留原因，否则日志是 `pos=-1 posVia=none posFail=none`，
                //    「方法没找到」和「方法找到了但返回 -1」两种情况分不开。
                val m = runCatching {
                    chatList.javaClass.getMethod("getPositionForView", View::class.java)
                }.getOrNull()
                if (m != null) {
                    pos = runCatching { m.invoke(chatList, v) as Int }.getOrNull() ?: -1
                    posVia = "getPositionForView"
                    if (pos < 0) posFail = "returnedMinusOne"
                } else {
                    posFail = "noMethod"
                }
            }
        }

        // adapter：优先 MMChattingListView.getBaseAdapter()（未混淆名），退回 m()
        var adapter: Any? = null
        var adapterVia = "none"
        if (outer != null) {
            val m = runCatching { outer.javaClass.getMethod("getBaseAdapter") }.getOrNull()
            if (m != null) {
                adapter = runCatching { m.invoke(outer) }.getOrNull()
                if (adapter != null) adapterVia = "getBaseAdapter"
            }
        }
        if (adapter == null && chatList != null) {
            val m = runCatching { chatList.javaClass.getMethod("m") }.getOrNull()
            if (m != null) {
                adapter = runCatching { m.invoke(chatList) }.getOrNull()
                if (adapter != null) adapterVia = "m()"
            }
        }

        var msgId = "none"
        var type = "none"
        var isSend = "none"
        var imgPath = "none"
        var h9Name = "none"
        if (adapter != null && pos >= 0) {
            val getItem = runCatching {
                adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType)
            }.getOrNull()
            val got = runCatching { getItem?.invoke(adapter, pos) }.getOrNull()
            if (got != null) {
                h9Name = got.javaClass.name.substringAfterLast('.')
                // ⚠️ 未混淆 getter：getMsgId()J / getType()I（继承自 jj/m7）
                msgId = runCatching {
                    got.javaClass.getMethod("getMsgId").invoke(got)
                }.getOrNull()?.toString() ?: "none"
                type = runCatching {
                    got.javaClass.getMethod("getType").invoke(got)
                }.getOrNull()?.toString() ?: "none"
                // ⚠️ 字段声明在父类 jj/m7（PRIVATE），XposedHelpers 沿类链找
                isSend = runCatching {
                    XposedHelpers.getObjectField(got, "field_isSend")
                }.getOrNull()?.toString() ?: "none"
                imgPath = runCatching {
                    XposedHelpers.getObjectField(got, "field_imgPath") as? String
                }.getOrNull()?.take(100) ?: "none"
            }
        }

        return "FORWARD MSGSNAP talker=$talker view=$viewName ageMs=$ageMs attached=$attached " +
            "list=$listKind listCls=${chatList?.javaClass?.name ?: "none"} " +
            "pos=$pos posVia=$posVia posFail=$posFail " +
            "adapter=${adapter?.javaClass?.name ?: "none"} " +
            "adapterVia=$adapterVia h9=$h9Name msgId=$msgId type=$type isSend=$isSend " +
            "imgPath=$imgPath menu=${lastChatMenuTexts.joinToString("|")}"
    }

    /**
     * 判定一个 View 是不是聊天列表，以及是哪种形态 —— **按继承链判定，不靠类名字符串**。
     *
     * 为什么不能用 `javaClass.name.contains("ChattingListView")`：
     * 运行时对象是子类，类名里**不含**基类名。实测（DEX `class_defs` 的 super 链）：
     * - `ScrollControlRecyclerView` super = `ChattingRecyclerView`
     * - `ScrollControlListView`     super = `ChattingListView`
     * - `ui.chatting.view.l`        super = `ScrollControlListView` → `ChattingListView`
     *
     * 三者 `contains("ChattingListView")` / `contains("ChattingRecyclerView")` 全为 `false`
     * ⇒ 曾导致 `chatList` 恒 null、`listKind` 恒 `"none"`，整条定位链路是死代码（R16-V2 报，P0）。
     *
     * @return `"recycler"` / `"listview"` / `"unknown"`（像列表但两条继承链都不认识，日志会带类名）/ 
     *         `"notList"`（不是列表）
     */
    private fun listKindOf(view: View?): String {
        if (view == null) return "notList"
        var c: Class<*>? = view.javaClass
        while (c != null) {
            when (c.name) {
                "com.tencent.mm.pluginsdk.ui.tools.ChattingRecyclerView" -> return "recycler"
                "com.tencent.mm.pluginsdk.ui.tools.ChattingListView" -> return "listview"
            }
            c = c.superclass
        }
        // 继承链上没有那两个类 ⇒ 区分「完全不是列表」与「是列表但类名不认识（unknown）」
        val nm = view.javaClass.name
        return when {
            // ⛔ 必须**先排除** MMChattingListView（R17-V3 报，P2）：
            //    它是 MMSnapDownView 系的外壳（super=MMPullDownView），本身**不是列表**，
            //    但类名里含 "ListView" 子串 ⇒ 会被下面那行判成 "unknown"，
            //    于是在「严格命中失败」时它可能抢先占住 chatListLoose。
            //    虽然兜底顺序把 getListView() 排在 loose 之前（通常能救回来），
            //    但让一个外壳占住「可疑列表」位没有任何价值，只会让 listCls 传出误导性类名。
            //    ⇒ 这一类直接判 notList。用 FQCN 精确比，避免误伤未来同前缀的真列表。
            nm == "com.tencent.mm.ui.chatting.view.MMChattingListView" -> "notList"
            nm.contains("Recycler") -> "unknown"
            nm.contains("ListView") || nm.contains("AbsListView") -> "unknown"
            else -> "notList"
        }
    }

    /**
     * 探针专用：**忽略 TTL** 读最近一次长按抓到的 View。
     *
     * 与 [pressedView] 的唯一区别就是跳过 `BURST_TTL_MS` 过期判断。
     * 生产路径**必须**继续用 [pressedView]（过期就当作没有长按，避免拿陈旧 View 去发错消息）；
     * 探针只是取证，陈旧 View 反而有用 —— 但它是否仍挂在窗口上要看 `isAttachedToWindow`。
     */
    private fun staleViewForProbe(): View? = synchronized(pressLock) {
        burstDeepest?.get()
    }

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
