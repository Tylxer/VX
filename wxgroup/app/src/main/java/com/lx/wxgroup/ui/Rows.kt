package com.lx.wxgroup.ui

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.PrefKind
import com.lx.wxgroup.data.PrefSpec
import com.lx.wxgroup.data.Prefs
import com.lx.wxgroup.util.LogX

/**
 * K2：设置项渲染器。
 *
 * 存在的意义就一句话：**加一个配置项 = 在 `Prefs.ALL` 里加一行**，
 * 页面、备份、默认值、数值钳制全自动，不用碰任何 UI 代码。
 *
 * 行分两类：
 * - [pref] 由 `PrefSpec` 驱动，读写直接落 `Kv`（模块唯一数据源），不需要回调；
 *   只有 `ENTER`（进下一级页，比如名单）需要外部给行为，因为那不是配置值。
 * - [action] 普通的「点进去」行，主界面六行和域里的特殊行用它。
 */
object Rows {

    // ------------------------------------------------------------------ 通用件

    /** 跟随宿主主题的行点击反馈（水波纹），不自己画背景以免深浅主题下露馅 */
    private fun applyRowBg(ctx: Context, v: View) {
        runCatching {
            val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            val resId = ta.getResourceId(0, 0)
            ta.recycle()
            if (resId != 0) v.setBackgroundResource(resId)
        }.onFailure { LogX.d("row bg fallback: ${it.javaClass.simpleName}") }
    }

    private fun baseRow(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val h = UiKit.dp(ctx, 14)
        setPadding(h, UiKit.dp(ctx, 11), h, UiKit.dp(ctx, 11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        applyRowBg(ctx, this)
    }

    private fun titleTv(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 16f
        setTextColor(UiKit.fgColor(ctx))
        setSingleLine(true)
    }

    private fun summaryTv(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(UiKit.muted())
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun arrow(ctx: Context): TextView = TextView(ctx).apply {
        text = "›"
        textSize = 20f
        setTextColor(UiKit.muted())
        setPadding(UiKit.dp(ctx, 6), 0, 0, 0)
    }

    /** 细分隔线：设置项之间不画线会分不清行边界 */
    private fun divider(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(0x1F888888)
    }

    // ------------------------------------------------------------------ 行

    /**
     * 「点进去」行：色块 + 标题 + 副标题 + 右箭头。主界面六行用它。
     *
     * @param color 左侧色块的填充色
     */
    fun action(
        ctx: Context,
        title: String,
        subtitle: String,
        color: Int,
        onClick: () -> Unit,
    ): View {
        val row = baseRow(ctx)

        val dotSize = UiKit.dp(ctx, 40)
        val dot = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                rightMargin = UiKit.dp(ctx, 12)
            }
            background = UiKit.pill(color, radiusPx = UiKit.dp(ctx, 6).toFloat())
        }

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(titleTv(ctx, title))
            if (subtitle.isNotBlank()) addView(summaryTv(ctx, subtitle))
        }

        row.addView(dot)
        row.addView(texts)
        row.addView(arrow(ctx))
        row.setOnClickListener { onClick() }
        return row
    }

    /** 不带色块的简洁行（域内的特殊操作，比如「分组管理」） */
    fun link(ctx: Context, title: String, subtitle: String = "", onClick: () -> Unit): View {
        val row = baseRow(ctx)
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(titleTv(ctx, title))
            if (subtitle.isNotBlank()) addView(summaryTv(ctx, subtitle))
        }
        row.addView(texts)
        row.addView(arrow(ctx))
        row.setOnClickListener { onClick() }
        return row
    }

    /** 灰底分组小标题，用来把一页里的项分区 */
    fun section(ctx: Context, text: String): View = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(UiKit.muted())
        val h = UiKit.dp(ctx, 14)
        setPadding(h, UiKit.dp(ctx, 8), h, UiKit.dp(ctx, 4))
    }

    /** 说明文字，放页脚 */
    fun note(ctx: Context, text: String): View = TextView(ctx).apply {
        this.text = text
        textSize = 11f
        setTextColor(UiKit.muted())
        val h = UiKit.dp(ctx, 14)
        setPadding(h, UiKit.dp(ctx, 6), h, UiKit.dp(ctx, 10))
    }

    /** 主操作按钮（微信绿），群发「开始」这类 */
    fun primaryButton(ctx: Context, text: String, onClick: () -> Unit): View = TextView(ctx).apply {
        this.text = text
        textSize = 16f
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER
        setPadding(0, UiKit.dp(ctx, 12), 0, UiKit.dp(ctx, 12))
        background = UiKit.pill(UiKit.brandGreen(), radiusPx = UiKit.dp(ctx, 6).toFloat())
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val side = UiKit.dp(ctx, 14)
        lp.setMargins(side, UiKit.dp(ctx, 8), side, UiKit.dp(ctx, 8))
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    /**
     * 由 `PrefSpec` 生成一行设置项。读写直接走 `Kv`。
     *
     * @param onEnter `ENTER` 类行的点击处理，返回 true 表示已处理；
     *                返回 false 会提示「暂未接入」，方便后接的功能先占位。
     */
    fun pref(ctx: Context, spec: PrefSpec, onEnter: (PrefSpec) -> Boolean = { false }): View {
        val row = baseRow(ctx)
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(titleTv(ctx, spec.title))
        }

        when (spec.kind) {
            PrefKind.SWITCH -> {
                val sw = Switch(ctx).apply {
                    // 先置位再挂监听：否则 setChecked 会立刻回调一次，把默认值写进库
                    isChecked = Kv.getBool(spec.key)
                    setOnCheckedChangeListener { _, on ->
                        Kv.putBool(spec.key, on)
                        LogX.i("PREF ${spec.key} = $on")
                    }
                }
                row.addView(texts)
                row.addView(sw)
                // 整行点击也切换开关：开关按钮本身很小，只点它太难中
                row.setOnClickListener { sw.isChecked = !sw.isChecked }
                return row
            }

            PrefKind.ENTER -> {
                val sum = summaryTv(ctx, summaryOf(spec))
                texts.addView(sum)
                row.addView(texts)
                row.addView(arrow(ctx))
                row.setOnClickListener {
                    if (!onEnter(spec)) UiKit.toast(ctx, "「${spec.title}」暂未接入")
                }
                return row
            }

            else -> {
                val sum = summaryTv(ctx, summaryOf(spec))
                texts.addView(sum)
                row.addView(texts)
                row.addView(arrow(ctx))
                row.setOnClickListener {
                    edit(ctx, spec) {
                        Kv.putString(spec.key, it)
                        // 行内更新摘要，不整页重建 —— 重建会让列表滚动位置跳回顶部
                        sum.text = summaryOf(spec)
                    }
                }
                return row
            }
        }
    }

    /** 一整块设置项（含行间分隔线） */
    fun prefGroup(
        ctx: Context,
        specs: List<PrefSpec>,
        onEnter: (PrefSpec) -> Boolean = { false },
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        for (s in specs) {
            addView(pref(ctx, s, onEnter))
            addView(divider(ctx))
        }
    }

    // ------------------------------------------------------------------ 取值与编辑

    private fun summaryOf(spec: PrefSpec): String = when (spec.kind) {
        PrefKind.SWITCH -> ""
        PrefKind.NUMBER -> "${Kv.getInt(spec.key)}${spec.unit ?: ""}"
        PrefKind.TIME -> Kv.getString(spec.key)
        PrefKind.TEXT -> {
            val v = Kv.getString(spec.key).trim()
            if (v.isEmpty()) "未填写" else v.lineSequence().firstOrNull()?.take(24) ?: "未填写"
        }
        PrefKind.PATH -> Kv.getString(spec.key)
        PrefKind.ENTER -> {
            val n = Kv.getList(spec.key).size
            if (n == 0) "未设置" else "$n 人"
        }
    }

    /**
     * 弹出对应控件编辑一项。改完回调新值（已按 kind 校验过）。
     * 时间用系统 TimePickerDialog，其余用输入框 —— 本模块不打包 xml，
     * 自己拼一个时间滚轮不划算。
     */
    private fun edit(ctx: Context, spec: PrefSpec, onOk: (String) -> Unit) {
        when (spec.kind) {
            PrefKind.TIME -> {
                val cur = Kv.getString(spec.key)
                val h = cur.substringBefore(":", "0").toIntOrNull() ?: 0
                val m = cur.substringAfter(":", "00").toIntOrNull() ?: 0
                TimePickerDialog(ctx, { _, hour, minute ->
                    onOk(String.format("%02d:%02d", hour, minute))
                    LogX.i("PREF ${spec.key} = ${Kv.getString(spec.key)}")
                }, h.coerceIn(0, 23), m.coerceIn(0, 59), true).show()
            }

            PrefKind.NUMBER -> {
                textEditor(
                    ctx = ctx,
                    title = spec.title,
                    initial = Kv.getInt(spec.key).toString(),
                    hint = "范围 ${spec.min ?: 0} – ${spec.max ?: "不限"}",
                    numbersOnly = true,
                    multiline = false,
                    resetTo = spec.def,
                ) { raw ->
                    val n = raw.toIntOrNull()
                    if (n == null) {
                        UiKit.toast(ctx, "请输入数字")
                        return@textEditor
                    }
                    // 越界不报错，钳到最近边界（决策 #6）
                    val clamped = Prefs.clamp(spec.key, n)
                    if (clamped != n) UiKit.toast(ctx, "已调整为 $clamped${spec.unit ?: ""}")
                    onOk(clamped.toString())
                }
            }

            PrefKind.TEXT -> {
                textEditor(
                    ctx = ctx,
                    title = spec.title,
                    initial = Kv.getString(spec.key),
                    hint = spec.title,
                    numbersOnly = false,
                    multiline = true,
                    resetTo = null,
                    onOk = onOk,
                )
            }

            PrefKind.PATH -> {
                textEditor(
                    ctx = ctx,
                    title = "导出${spec.title}",
                    initial = Kv.getString(spec.key),
                    hint = Prefs.DEFAULT_EXPORT_PATH,
                    numbersOnly = false,
                    multiline = false,
                    resetTo = Prefs.DEFAULT_EXPORT_PATH,
                    // R28：需求 6.4 预留的「用系统文件选择器授权导出目录」入口。
                    // 授权成功后 ExportDirSaf 已把路径写进 kv，这里再回调一次 onOk
                    // 只为让行内摘要立刻刷新（写的是同一个值，无副作用）。
                    extraButton = "用系统文件选择器挑选…" to {
                        com.lx.wxgroup.feature.ExportDirSaf.launch(ctx) { msg ->
                            UiKit.toast(ctx, msg)
                            onOk(Kv.getString(spec.key))
                        }
                    },
                    onOk = onOk,
                )
            }

            else -> Unit
        }
    }

    private fun textEditor(
        ctx: Context,
        title: String,
        initial: String,
        hint: String,
        numbersOnly: Boolean,
        multiline: Boolean,
        resetTo: String?,
        /**
         * R28：输入框下方额外挂一个按钮（`文案` to `点击动作`）。
         *
         * ⚠️ 走自定义 View 而不是 AlertDialog 的按钮位：三个位（确定/取消/恢复默认）
         * 已被占满，而 AlertDialog 没有第四个标准位。
         */
        extraButton: Pair<String, () -> Unit>? = null,
        onOk: (String) -> Unit,
    ) {
        val pad = UiKit.dp(ctx, 20)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, UiKit.dp(ctx, 8), pad, 0)
        }
        val edit = EditText(ctx).apply {
            setText(initial)
            this.hint = hint
            inputType = if (numbersOnly) {
                InputType.TYPE_CLASS_NUMBER
            } else {
                InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            }
            if (multiline) {
                maxLines = 6
                minLines = 4
                setSingleLine(false)
                gravity = Gravity.TOP
            } else {
                maxLines = 1
            }
            textSize = 15f
            setSelection(initial.length)
        }
        root.addView(edit)

        // R28：额外按钮（目前只有 PATH 用：调系统文件选择器挑导出目录）
        val extraBtn = extraButton?.let { (label, _) ->
            Button(ctx).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = UiKit.dp(ctx, 8) }
                root.addView(this)
            }
        }

        val b = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", null)
        if (resetTo != null) b.setNeutralButton("恢复默认", null)

        b.create().apply {
            // ⛔ R21：不走 Panel 的 dialog 必须单独清 ALT_FOCUSABLE_IM，否则输入框点了不弹键盘
            UiKit.clearAltFocusableIm(this, " Rows.input")
            // 额外按钮：先把输入框里的手改值丢掉再跳系统选择器 —— 不 dismiss 的话
            // 系统选择器的回传会被这个还活着的 dialog 盖住。
            extraBtn?.setOnClickListener {
                dismiss()
                extraButton!!.second()
            }
            setOnShowListener {
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    dismiss()
                    onOk(edit.text?.toString()?.trim().orEmpty())
                }
                if (resetTo != null) {
                    getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        dismiss()
                        onOk(resetTo)
                    }
                }
            }
            show()
        }
    }
}
