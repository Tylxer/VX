package com.lx.wxgroup.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.util.LogX

/**
 * 模块里的一个「页面」。
 *
 * 只要求给出标题和内容 View —— 返回、滚动、遮罩、异常兜底都由 [Panel] 统一处理，
 * 所以新建一个二级页的成本就是实现这两个东西。
 *
 * ⚠️ `build()` 每次返回新 View，不做复用。页面数据变了调用 [Panel.refresh]
 * 整页重建即可，本模块页面都很小，重建开销可以忽略，换来的是「不用维护刷新逻辑」。
 */
interface PanelPage {
    val title: String
    fun build(ctx: Context): View
}

/**
 * K1：模块**唯一**的弹窗容器，自带页面栈。
 *
 * 为什么不沿用「每个页各弹一个 AlertDialog、返回时 dismiss 再重开」（旧项目做法）：
 * 1. 那样做**没有返回栈** —— 二级页只能「关闭」，回不到主界面，用户明确要求能返回。
 * 2. dismiss + show 会闪一下，连续进出像在抽搐。
 * 3. 返回键、遮罩、异常兜底要在每个页各写一遍。
 *
 * 现在的形态：一个 Dialog 承载，内容区整体替换，栈深决定底栏按钮是「返回」还是「关闭」。
 *
 * 三条硬约束（沿用 UiKit）：不打包 xml、Context 必须是宿主 Activity、
 * 不硬编码前景色。
 */
object Panel {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val stack = ArrayList<PanelPage>()

    private var dialog: AlertDialog? = null
    private var ctx: Context? = null
    private var body: LinearLayout? = null
    private var backBtn: Button? = null

    /** 内容区最大高度（屏幕占比）。超过就滚动，不让弹窗顶到状态栏 */
    private const val BODY_RATIO = 0.62f

    fun isOpen(): Boolean = dialog?.isShowing == true

    /** 当前栈深，1 = 在主界面 */
    fun depth(): Int = stack.size

    // ------------------------------------------------------------- 对外操作

    /** 打开面板（栈清空后压入首页）。已开着会先关掉重开，避免两个面板叠在一起 */
    fun open(page: PanelPage) {
        mainHandler.post {
            runCatching { openInternal(page) }
                .onFailure { LogX.e("Panel.open failed", it) }
        }
    }

    /** 进下一级页 */
    fun push(page: PanelPage) {
        mainHandler.post {
            runCatching {
                if (dialog == null) {
                    // 面板没开（比如从广播直接 push）就当作打开首页
                    openInternal(page)
                    return@runCatching
                }
                stack.add(page)
                render()
                LogX.d("Panel push ${page.title} depth=${stack.size}")
            }.onFailure { LogX.e("Panel.push failed", it) }
        }
    }

    /** 返回上一页；已在首页则关闭整个面板 */
    fun pop() {
        mainHandler.post {
            runCatching {
                if (stack.size <= 1) {
                    closeInternal()
                    return@runCatching
                }
                stack.removeAt(stack.lastIndex)
                render()
                LogX.d("Panel pop depth=${stack.size}")
            }.onFailure { LogX.e("Panel.pop failed", it) }
        }
    }

    /** 数据变了重画当前页（改开关后副标题要跟着变时用） */
    fun refresh() {
        mainHandler.post { runCatching { render() }.onFailure { LogX.e("Panel.refresh failed", it) } }
    }

    /** 关掉整个面板 */
    fun close() {
        mainHandler.post { closeInternal() }
    }

    // ------------------------------------------------------------- 内部实现

    private fun openInternal(page: PanelPage) {
        val c = HostContext.activity() ?: HostContext.app()
        if (c == null) {
            LogX.w("Panel: no host context")
            return
        }
        closeInternal()
        stack.clear()
        stack.add(page)
        ctx = c

        val scroll = ScrollView(c).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (UiKit.screenHeight(c) * BODY_RATIO).toInt()
            )
            isFillViewport = false
        }
        val b = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(b)
        body = b

        val d = AlertDialog.Builder(c)
            .setTitle(page.title)
            .setView(scroll)
            // 按钮的真实行为在 setOnShowListener 里覆盖成 pop()
            .setNeutralButton("关闭", null)
            .create()
        d.setCanceledOnTouchOutside(true)

        // 返回键：先走页面栈，栈里只剩首页才让 Dialog 自己关掉
        d.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                pop()
                true
            } else {
                false
            }
        }
        d.setOnShowListener {
            backBtn = d.getButton(AlertDialog.BUTTON_NEUTRAL)
            // 覆盖默认行为：AlertDialog 给按钮设了自己的 listener 会 dismiss 整个 Dialog，
            // 这里换成 pop()，才能「返回上一页」而不是「整个关掉」
            backBtn?.setOnClickListener { pop() }
            render()
            // ⛔ R21：清 FLAG_ALT_FOCUSABLE_IM 必须放在这里、且**在 render() 之后**。
            //
            // AOSP `AlertController.installContent()` 里有：
            //   if (mView == null || !canTextInput(mView)) mWindow.setFlags(FLAG_ALT_FOCUSABLE_IM, ...)
            // 即「内容里找不到可输入控件 ⇒ 这个窗口不需要 IME ⇒ 打上替代焦点模式」。
            //
            // 而 installContent() 是在 show() → dispatchOnCreate() → AlertDialog.onCreate() 里跑的，
            // **早于** OnShowListener（后者由 show() 末尾的 sendShowMessage() 触发）。
            // Panel 的页面是**延迟渲染**的（render() 才 addView 真页面），show() 那一刻 scroll
            // 里还是空的 ⇒ canTextInput=false ⇒ flag 被重新置上。
            // ⚠️ 所以在 `d.show()` **之前**清是无效的，会被这一步覆盖（R21 复核实测推翻）。
            UiKit.clearAltFocusableIm(d, " Panel")
            // 兜底：万一某版本在 addView 之后还会再 setFlags，下一帧再清一次（幂等，无副作用）
            d.window?.decorView?.post { UiKit.clearAltFocusableIm(d, " Panel(post)") }
        }
        d.setOnDismissListener {
            dialog = null
            body = null
            backBtn = null
            ctx = null
            stack.clear()
        }

        dialog = d
        d.show()
        UiKit.widen(d, c)
        runCatching { d.window?.setDimAmount(0.4f) }
            .onFailure { LogX.w("Panel dim failed: ${it.javaClass.simpleName}") }
        LogX.i("Panel open ${page.title}")
    }

    private fun render() {
        val c = ctx ?: return
        val b = body ?: return
        val page = stack.lastOrNull() ?: return
        dialog?.setTitle(page.title)
        b.removeAllViews()
        runCatching { b.addView(page.build(c)) }.onFailure {
            // 单个页面画不出来不能让整个面板消失，更不能把微信搞崩
            LogX.e("Panel build failed: ${page.title}", it)
            b.addView(UiKit.label(c, "「${page.title}」加载失败，详见日志", 14f, gray = true))
        }
        backBtn?.text = if (stack.size > 1) "返回" else "关闭"
    }

    private fun closeInternal() {
        runCatching { dialog?.dismiss() }
            .onFailure { LogX.w("Panel dismiss failed: ${it.javaClass.simpleName}") }
        dialog = null
        body = null
        backBtn = null
        ctx = null
        stack.clear()
    }
}
