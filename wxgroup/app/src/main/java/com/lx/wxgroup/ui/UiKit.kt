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
