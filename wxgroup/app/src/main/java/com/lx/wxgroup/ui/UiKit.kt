package com.lx.wxgroup.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.lx.wxgroup.util.LogX

/**
 * 模块所有界面的公共构造器。
 *
 * 三条硬约束（跟 GroupPicker 一致，别破）：
 * 1. **不打包任何 xml layout** —— 模块 APK 的资源 id 与宿主不冲突但也没意义，
 *    纯代码构造最省心，也让模块体积保持在 2MB 以内。
 * 2. Context 必须是宿主的 Activity（Dialog 需要窗口 token）。
 * 3. 不硬编码前景色：微信可能是深色主题，写死黑色会变成黑底黑字。
 *    只有「次要信息」才用中性灰 #888888（深浅主题下都还看得见）。
 */
object UiKit {

    // 注意不能写 const val：0xFF888888 超过 Int 上限是 Long，.toInt() 是函数调用，
    // 而 const val 的初始化式只允许常量表达式
    private val GRAY = 0xFF888888.toInt()

    fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    /**
     * 宿主主题的前景色。
     *
     * 微信有深色模式，写死黑字会变成黑底黑字。治本的办法是跟宿主主题要
     * android.R.attr.textColorPrimary —— 主题切了它跟着变。
     */
    fun fgColor(ctx: Context): Int {
        val tv = TypedValue()
        val ok = runCatching { ctx.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true) }
            .getOrDefault(false)
        if (ok && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data
        }
        return Color.BLACK
    }

    /** 次要文字色：中性灰，深浅主题下都还能看清 */
    fun muted(): Int = GRAY

    /**
     * 宿主窗口的背景色。叠在列表上层的自定义条必须用它，
     * 否则深色主题下是一道白条，浅色主题下是一道黑条。
     */
    fun bgColor(ctx: Context): Int {
        val tv = TypedValue()
        val ok = runCatching { ctx.theme.resolveAttribute(android.R.attr.windowBackground, tv, true) }
            .getOrDefault(false)
        if (!ok) return Color.WHITE
        if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data
        }
        if (tv.resourceId != 0) {
            runCatching {
                val d = ctx.resources.getDrawable(tv.resourceId, ctx.theme)
                if (d is android.graphics.drawable.ColorDrawable) return d.color
            }
        }
        return Color.WHITE
    }

    /** 圆角实心块，chip / 分组色块都用它。radiusPx 传像素（用 dp() 换算） */
    fun pill(fill: Int, stroke: Int = 0, strokeWidthPx: Int = 2, radiusPx: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            if (stroke != 0) setStroke(strokeWidthPx, stroke)
            cornerRadius = radiusPx
        }

    /** 10% 中性灰：chip 未选中底色，深浅主题都只是一层很淡的叠加 */
    fun softBg(): Int = 0x1A888888

    /** 微信绿，模块里所有「主操作」统一用它 */
    fun brandGreen(): Int = 0xFF07C160.toInt()

    fun screenHeight(ctx: Context): Int = ctx.resources.displayMetrics.heightPixels

    fun screenWidth(ctx: Context): Int = ctx.resources.displayMetrics.widthPixels

    fun label(
        ctx: Context,
        text: String,
        sp: Float,
        bold: Boolean = false,
        gray: Boolean = false,
        padDp: Int = 8,
    ): TextView = TextView(ctx).apply {
        this.text = text
        textSize = sp
        if (bold) setTypeface(null, Typeface.BOLD)
        if (gray) setTextColor(GRAY)
        val p = dp(ctx, padDp)
        setPadding(p, p / 2, p, p / 2)
    }

    /**
     * 固定高度的 ListView。
     *
     * 为什么不用 weight：AlertDialog 的内容区是 WRAP_CONTENT，
     * LinearLayout 的 weight 在没有确定高度时会把 0 高度原样传下去，列表直接不显示。
     * 给一个按屏幕比例算出来的像素高度最稳。
     */
    fun listView(ctx: Context, heightRatio: Float): ListView = ListView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (screenHeight(ctx) * heightRatio).toInt()
        )
        dividerHeight = dp(ctx, 1)
        isFastScrollEnabled = true
        // 列表项里有可点击控件时，必须让 ListView 自己处理选择，否则行点击会被子控件吃掉
        isFocusable = true
    }

    /** 弹窗宽度拉到屏幕的 95%，窄对话框装不下好友列表 */
    fun widen(dialog: Dialog, ctx: Context) {
        runCatching {
            dialog.window?.apply {
                setLayout((screenWidth(ctx) * 0.95f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }.onFailure { LogX.w("UiKit.widen failed: ${it.javaClass.simpleName}") }
    }

    fun toast(ctx: Context, msg: String) {
        runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
            .onFailure { LogX.w("toast failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 让 [edit] 在「被点击 / 拿到焦点」时主动拉起输入法。
     *
     * 为什么需要这个：模块所有页面都长在 [Panel] 的 AlertDialog 里，而 AlertDialog 骨架
     * 默认给窗口打 `FLAG_ALT_FOCUSABLE_IM`（替代焦点模式）。R21 已在
     * `Panel.openInternal()` 里把这个 flag 清掉，但**历史沉淀是**：只要还有任何一条路径
     * 走到带该 flag 的窗口（或系统版本行为差异导致窗口属性没及时刷新），
     * `showSoftInput()` 就会被 InputMethodManager 直接丢弃 —— 表现为控件
     * `focused=true` 却 `mInputShown=false`、`sessionRequested=false`。
     * 所以这里不只依赖系统的「聚焦自动弹」链路，而是在点击/聚焦时显式请求一次，
     * 让 `clearFlags` 之后系统**才会接受**的这个请求真正生效。
     *
     * 参数用 `0`（不是 `SHOW_IMPLICIT`）：`SHOW_IMPLICIT` 只是「非用户直接发起」的
     * 语义标记，对微信这类宿主已被系统视为前台的应用没有实际差别；传 0 兼容性最广
     * （`SHOW_IMPLICIT` 是 API 11+，虽然现在都满足，但没必要引入分支）。
     *
     * 返回 [edit] 本身，便于 `.let { ... }` 链式接在构造之后。
     */
    fun bindSoftInput(edit: EditText): EditText {
        val kick = { v: EditText ->
            runCatching {
                val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(v, 0)
            }.onFailure { LogX.w("UiKit showSoftInput failed: ${it.javaClass.simpleName}") }
        }
        // ⚠️ `setOnClickListener` / `setOnFocusChangeListener` 是**覆盖式**的：
        // 调用方若在 bindSoftInput 之后又设了同位置的 listener，本助手就会被静默顶掉
        // （与 `ListView.adapter` 同一类坑）。四个调用点当前都只用 addTextChangedListener，
        // 所以安全；新增调用点前先确认它不在这两个位置上挂东西。
        edit.setOnClickListener { v -> kick(v as EditText) }
        edit.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) kick(v as EditText) }
        return edit
    }

    /**
     * 清掉窗口上的 `FLAG_ALT_FOCUSABLE_IM`（= 替代焦点模式）。
     *
     * `AlertDialog` 骨架默认就给窗口打这个 flag，AOSP 语义下带它的窗口**不会被自动拉起 IME**
     * —— 典型表现是输入框 `focused=true` 却 `mInputShown=false`、`sessionRequested=false`，
     * 点了没反应。
     *
     * ⚠️ **必须对每个自建 Dialog 单独调用**：`Panel` 里清的是 `Panel` 自己那个 dialog，
     * 管不到 `BatchPicker`（裸 `AlertDialog.Builder`）和 `GroupDetailPage`（裸 `Dialog`）。
     * 少一处就是一处的搜索框不弹输入法。
     *
     * 传 Dialog 而不是 Window：`getWindow()` 在 `create()` / `new Dialog()` 之后即非空，
     * 但 `show()` 之前窗口还没 addView 到 WMS ⇒ 此时改 flag 最干净，不会有
     * 「先带 flag 加窗、再清 flag」的竞态。
     */
    fun clearAltFocusableIm(dialog: Dialog, tag: String = "") {
        runCatching {
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }.onFailure {
            LogX.w("UiKit clearAltFocusableIm$tag failed: ${it.javaClass.simpleName}")
        }
    }

    /**
     * 后台线程干完活回主线程。
     *
     * 为什么统一在这里：`Handler(Looper.getMainLooper())` 每个类各建一个的写法
     * 已经出现在 6 个文件里，而**回调里改 UI/弹窗必须回主线程**这条约束很容易漏
     * （漏了的表现是偶发 `CalledFromWrongThreadException` 或 toast 不显示）。
     * 已经在主线程时直接执行，省一次消息循环往返。
     */
    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching(block).onFailure { LogX.w("runOnMain threw: ${it.javaClass.simpleName}") }
            return
        }
        runCatching {
            Handler(Looper.getMainLooper()).post {
                runCatching(block)
                    .onFailure { LogX.w("runOnMain threw: ${it.javaClass.simpleName}") }
            }
        }.onFailure { LogX.w("runOnMain post failed: ${it.javaClass.simpleName}") }
    }

    /** 单行输入对话框：新建分组 / 重命名都用它 */
    fun input(
        ctx: Context,
        title: String,
        initial: String,
        hint: String,
        onOk: (String) -> Unit,
    ) {
        val pad = dp(ctx, 20)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(ctx, 8), pad, 0)
        }
        val edit = EditText(ctx).apply {
            setText(initial)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            textSize = 15f
            setSelection(initial.length)
        }
        root.addView(edit)
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", null)
            .create()
            .apply {
                // ⛔ R21：这个 dialog 不走 Panel，Panel 清的 flag 管不到它 ——
                // 不清这里，新建分组时点输入框同样不弹键盘（此前一直如此，只是没人报）。
                clearAltFocusableIm(this, " input")
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val v = edit.text?.toString()?.trim().orEmpty()
                        if (v.isEmpty()) {
                            toast(ctx, "名称不能为空")
                        } else {
                            dismiss()
                            onOk(v)
                        }
                    }
                }
                show()
            }
    }

    fun confirm(ctx: Context, title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> onYes() }
            .show()
    }
}
