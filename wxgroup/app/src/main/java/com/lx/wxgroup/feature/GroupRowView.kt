package com.lx.wxgroup.feature

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

/**
 * A4：分组行的 View —— **长成微信会话行的样子**，混在会话里不突兀。
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │ (头像)  分组名                      09:41 ③  │
 * │         妈妈：今晚回来吃饭吗                  │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * 三条需求约束：
 * - **A4-a** 头像是「最新消息那个成员」的头像，不是文件夹图标。
 *   微信头像加载接口还没定位到（S1 遗留项），这里先用**昵称首字 + 稳定色块**占位，
 *   等接口打通只改 `avatarView()` 一处。
 * - **A4-b** 第二行是 `成员名：消息内容`。
 * - **A4-c** 整组没有消息时：头像退回中性色占位，第二行显示前 3 个成员名
 *   （「妈妈、爸爸、姐姐」），时间列留空。
 *
 * 高度跟微信会话行对齐（实测 72dp），否则混排时会明显突出来一块。
 */
object GroupRowView {

    /** 渲染一行分组所需的一切。由 `ConvFilter` 从 DB + 会话对象算好后传进来，这里只管画。 */
    data class Model(
        val id: Long,
        val name: String,
        val pinned: Boolean = false,
        val memberCount: Int = 0,
        /** A4-a：最新消息那个成员的名字（用于取首字和展示） */
        val lastSender: String = "",
        /** A4-b：最后一条消息的内容 */
        val lastMsg: String = "",
        val timeMs: Long = 0L,
        /** A7：组内未读数求和 */
        val unread: Int = 0,
        /** A4-c：整组没消息时展示的成员名（最多 3 个） */
        val memberNames: List<String> = emptyList(),
    )

    fun build(ctx: Context, m: Model, onTap: () -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        val pad = (12 * d).toInt()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            minimumHeight = (72 * d).toInt()
        }

        // ---- 左：头像（A4-a / A4-c）----
        val seed = m.lastSender.ifBlank { m.name }
        row.addView(avatar(ctx, d, seed, m.lastSender.isBlank()))

        // ---- 中：两行文字（weight=1 占满剩余宽度）----
        val center = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(TextView(ctx).apply {
            text = m.name
            textSize = 16f
            setTextColor(0xFF191919.toInt())
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (m.pinned) {
            title.addView(TextView(ctx).apply {
                text = "置顶"
                textSize = 11f
                setTextColor(0xFF07C160.toInt())
                setPadding((6 * d).toInt(), 0, 0, 0)
            })
        }
        center.addView(title)

        center.addView(TextView(ctx).apply {
            text = subtitle(m)
            textSize = 13f
            setTextColor(0xFF8A8A8A.toInt())
            setSingleLine(true)
        })
        row.addView(center)

        // ---- 右：时间 + 未读角标（A4-c 没消息时时间列留空）----
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        if (m.timeMs > 0L) {
            right.addView(TextView(ctx).apply {
                text = timeLabel(m.timeMs)
                textSize = 12f
                setTextColor(0xFFB0B0B0.toInt())
            })
        }
        if (m.unread > 0) {
            right.addView(unreadBadge(ctx, d, m.unread))
        }
        row.addView(right)

        // 点击走 **View.OnClickListener**，不是 ListView.onItemClick。
        // 实测（8.0.48）：微信 ConversationListView 的 `mOnItemClickListener` 是 null
        // （真机日志证据：`wrapListeners` 只打出 `wrap long inner=...`，点击那条从不出现），
        // 所以 hook `performItemClick` 一次都不会触发 —— 点击只能由行自己扛。
        //
        // ⚠️ 必须 post 到下一帧再回调。`onTap` 会 `setGroup` → `rebuild` →
        // `notifyDataSetChanged`；若在**触摸事件处理过程中**改数据集，ListView 的
        // 触摸状态机走不完，且列表项从 10 条骤减到 3 条会让内容高度小于视口，
        // 触发 overscroll —— 微信把 overscroll 解释成「下拉打开小程序」，
        // 实测现象就是：点一下分组行，界面跳到小程序面板（功能虽生效但体验全毁）。
        row.setOnClickListener { row.post { onTap() } }
        return row
    }

    /** A4-b / A4-c 的第二行文案 */
    private fun subtitle(m: Model): String {
        val sender = m.lastSender
        val msg = m.lastMsg
        return when {
            // A4-c：整组没消息 → 前 3 个成员名
            sender.isBlank() && m.memberNames.isNotEmpty() ->
                m.memberNames.take(3).joinToString("、")
            // 有消息但摘要读不到（字段没定位到）→ 至少显示成员名
            sender.isNotBlank() && msg.isBlank() -> sender
            sender.isBlank() && msg.isBlank() -> "分组 · ${m.memberCount} 位成员"
            else -> "$sender：$msg"
        }
    }

    /**
     * 头像占位：首字 + 由名字派生的稳定色。
     * 真实头像接口打通后只改这里。
     */
    private fun avatar(ctx: Context, d: Float, seed: String, neutral: Boolean): View {
        val size = (48 * d).toInt()
        val letters = seed.trim().firstOrNull()?.toString() ?: "组"
        return TextView(ctx).apply {
            text = letters
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = (12 * d).toInt()
            }
            background = GradientDrawable().apply {
                setColor(if (neutral) 0xFFB0B0B0.toInt() else colorOf(seed))
                cornerRadius = size / 2f
            }
        }
    }

    /** 同一个名字永远同一个颜色（否则每次 getView 颜色都在跳） */
    private fun colorOf(seed: String): Int {
        val h = seed.hashCode()
        val hue = (h and 0x7FFFFFFF) % 360
        return android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.85f))
    }

    private fun unreadBadge(ctx: Context, d: Float, n: Int): TextView {
        return TextView(ctx).apply {
            text = if (n > 99) "99+" else n.toString()
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            val pad = (5 * d).toInt()
            setPadding(pad, (2 * d).toInt(), pad, (2 * d).toInt())
            background = GradientDrawable().apply {
                setColor(0xFFFA5151.toInt())
                cornerRadius = 99f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * d).toInt() }
        }
    }

    /** 跟微信会话列表一致的时间格式：今天 HH:mm、昨天「昨天」、更早 M月d日 */
    private fun timeLabel(ms: Long): String {
        val now = Calendar.getInstance()
        val t = Calendar.getInstance().apply { timeInMillis = ms }
        val sameYear = now.get(Calendar.YEAR) == t.get(Calendar.YEAR)
        val dayDiff = ((now.timeInMillis - ms) / 86_400_000L).toInt()
        return when {
            dayDiff == 0 && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR) ->
                "%02d:%02d".format(t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE))
            dayDiff <= 1 -> "昨天"
            sameYear -> "%d月%d日".format(t.get(Calendar.MONTH) + 1, t.get(Calendar.DAY_OF_MONTH))
            else -> "%d/%d/%d".format(t.get(Calendar.YEAR), t.get(Calendar.MONTH) + 1, t.get(Calendar.DAY_OF_MONTH))
        }
    }
}
