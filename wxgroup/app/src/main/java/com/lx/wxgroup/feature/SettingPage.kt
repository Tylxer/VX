package com.lx.wxgroup.feature

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.lx.wxgroup.data.PrefSpec
import com.lx.wxgroup.data.Prefs
import com.lx.wxgroup.ui.PanelPage
import com.lx.wxgroup.ui.Rows

/**
 * 通用配置页 —— K2 渲染器的直接消费者。
 *
 * 一个域的二级页通常不用单独写文件：传个域名，该域在 `Prefs.ALL` 里登记过的
 * 配置项会全部自动渲染出来（开关、数值、时间、文本、路径各按各的控件画）。
 * 域里那些**不是配置项**的操作（「分组管理」「开始群发」）用 [Row] 补进来。
 *
 * 这样以后加功能要新开一页的成本是：在 `Prefs.ALL` 加几行 + 在这里 new 一个实例。
 *
 * @param domain 传了就渲染该域的配置项；传 null 表示本页只有自定义行（如备份页）
 * @param head 插在配置项**前面**的行（一般是入口类操作）
 * @param tail 插在配置项**后面**的行（一般是主操作按钮）
 */
class SettingPage(
    override val title: String,
    private val domain: String? = null,
    private val head: List<Row> = emptyList(),
    private val tail: List<Row> = emptyList(),
    private val footnote: String? = null,
    private val onEnter: (PrefSpec) -> Boolean = { false },
) : PanelPage {

    /** 一个自定义行。`asButton = true` 时画成绿色主操作按钮而不是普通行 */
    data class Row(
        val title: String,
        val subtitle: () -> String = { "" },
        val asButton: Boolean = false,
        val onClick: () -> Unit,
    )

    override fun build(ctx: Context): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (head.isNotEmpty()) {
            for (r in head) root.addView(render(ctx, r))
            root.addView(Rows.section(ctx, "设置"))
        }

        val specs = domain?.let { Prefs.byDomain(it) }.orEmpty()
        if (specs.isNotEmpty()) {
            root.addView(Rows.prefGroup(ctx, specs, onEnter))
        } else if (head.isEmpty()) {
            root.addView(Rows.note(ctx, "本页暂无可配置项"))
        }

        for (r in tail) root.addView(render(ctx, r))

        if (!footnote.isNullOrBlank()) root.addView(Rows.note(ctx, footnote))
        return root
    }

    private fun render(ctx: Context, r: Row): View =
        if (r.asButton) {
            Rows.primaryButton(ctx, r.title, r.onClick)
        } else {
            Rows.link(ctx, r.title, r.subtitle(), r.onClick)
        }
}
