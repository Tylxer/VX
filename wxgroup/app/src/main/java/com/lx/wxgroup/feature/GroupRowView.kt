package com.lx.wxgroup.feature

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lx.wxgroup.core.AvatarLoader
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
        /** A4-a：最新消息那个成员的 wxid（用于真头像加载，[ConvFilter] 从 snaps 里带出来） */
        val lastSenderWxid: String = "",
        /** A4-b：最后一条消息的内容 */
        val lastMsg: String = "",
        val timeMs: Long = 0L,
        /** A7：组内未读数求和 */
        val unread: Int = 0,
        /**
         * 是否渲染未读角标（=`chat.show_unread` 开关，`Keys.CHAT_SHOW_UNREAD`）。
         *
         * ⚠️ 由 [com.lx.wxgroup.feature.ConvFilter] 在 `rebuild()` 里**读一次**后传进来，
         * **不在这里现读 Kv**：`build()` 是「每行、每次渲染」都跑的热路径，
         * 而 `Kv.getString` 是一次 SQLite 查询 —— 放这儿等于把列表滚动变成查库。
         */
        val showUnread: Boolean = true,
        /** A4-c：整组没消息时展示的成员名（最多 3 个） */
        val memberNames: List<String> = emptyList(),
    )

    fun build(ctx: Context, m: Model, onTap: () -> Unit, onLongTap: () -> Unit): View {
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
        row.addView(avatar(ctx, d, seed, m.lastSender.isBlank(), m.lastSenderWxid))

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
        // 开关关掉就不画角标（判据由 ConvFilter 在 rebuild 时算好传进来，这里不读配置）
        if (m.showUnread && m.unread > 0) {
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

        // A5：长按分组行 → 我们自己的菜单（置顶/重命名/管理成员/标记已读/删除）。
        // 不复用微信的会话长按菜单：那个菜单是 PopupWindow 且没有可挂的入口
        // （见 ConvMenu 的头注释），分组行本来也是我们自己的 View，直接自己弹。
        //
        // 返回值必须是 true：否则长按会继续冒泡到 ListView 的
        // OnItemLongClickListener，微信会把这个位置当成会话去弹「删除该聊天」。
        row.setOnLongClickListener { onLongTap(); true }
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
     * 头像：`FrameLayout`（圆形裁剪）叠两层——
     * - 底层 [ImageView]：真头像（`AvatarLoader` 反射微信 AvatarStorage 取，`CENTER_CROP`）
     * - 顶层 [TextView]：首字 + 由名字派生的稳定色（**兜底，逻辑原样保留**）
     *
     * 首字层默认可见、图片层默认隐藏；`AvatarLoader` 命中真头像后隐藏首字层。
     * 拿不到真头像时首字层一直可见，配色规则（[colorOf]）一字不改 —— 那是专门修过的
     * 「同一个名字永远同色」稳定性 bug。
     */
    private fun avatar(ctx: Context, d: Float, seed: String, neutral: Boolean, wxid: String): View {
        val size = (48 * d).toInt()
        val letters = seed.trim().firstOrNull()?.toString() ?: "组"

        // 首字兜底层：首字 + 稳定色块 + 中性灰，逻辑与旧实现逐字一致
        val lettersView = TextView(ctx).apply {
            text = letters
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                setColor(if (neutral) 0xFFB0B0B0.toInt() else colorOf(seed))
                cornerRadius = size / 2f
            }
        }

        // 真头像层：默认 GONE，命中后由 AvatarLoader 回调切 VISIBLE
        val imageView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(size, size)
        }

        val container = FrameLayout(ctx).apply {
            clipToOutline = true
            // 用固定 size 而非 view.width/height：布局前的 transient 态宽高可能还是 0
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, size, size, size / 2f)
                }
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = (12 * d).toInt()
            }
            addView(imageView)
            addView(lettersView)
        }

        // 绑定头像：内存缓存（主线程）→ 磁盘解码（后台，tag 校验防错位）→ 拿不到保持首字兜底
        AvatarLoader.bind(imageView, wxid) {
            lettersView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
        }
        return container
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
            // 给真机断言留个锚：uiautomator 看不到背景色，`dev.py ui 未读` 全靠这行
            // （验证「显示未读」开关时，开关关掉它就应该整个消失）
            contentDescription = "未读${n}"
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
