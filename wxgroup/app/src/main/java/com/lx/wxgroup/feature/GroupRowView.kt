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
 *   等接口打通只改 `bindAvatar()` 一处。
 * - **A4-b** 第二行是 `成员名：消息内容`。
 * - **A4-c** 整组没有消息时：头像退回中性色占位，第二行显示前 3 个成员名
 *   （「妈妈、爸爸、姐姐」），时间列留空。
 *
 * 高度跟微信会话行对齐（实测 72dp），否则混排时会明显突出来一块。
 *
 * ---
 *
 * ## R32：为什么必须复用（**别改回「每次 build 都建树」**）
 *
 * 旧实现 `build()` 每次调用都 `new` 一整棵树：1 个 `LinearLayout` 行 + 1 个
 * `FrameLayout` 头像框 + 1 个 `ImageView` + 4 个 `TextView` + 一堆
 * `LinearLayout.LayoutParams` / `FrameLayout.LayoutParams` / `GradientDrawable` /
 * 2 个 `ViewOutlineProvider` 匿名对象 / 2 个 listener 匿名对象。
 * 而 `ConvFilter.GroupedAdapter.getView` 的分组行分支**完全忽略 convertView**，
 * 于是每次 `notifyDataSetChanged()`（置顶/折叠、收发消息、`setGroup` 都会触发）
 * 所有可见分组行都被整体重建：
 *
 * 1. 文字重新测量 + 重新布局（一次 `requestLayout` 全树）；
 * 2. 头像重新走 `AvatarLoader.bind`（内存 miss 时再排一次磁盘解码）；
 * 3. 所有 `LayoutParams` / `Drawable` / 监听器对象重新分配 → GC 压力 + 长帧。
 *
 * 视觉上就是「折叠/展开置顶聊天时分组行闪一下、跳一下」，体感上那一帧也变长。
 *
 * 所以这里改成 ViewHolder 模式：
 * - [create] 只建骨架，并把 holder 存进 `row.tag`；
 * - [bind] 只改值（`setText` / `setImageDrawable` / `setVisibility`），
 *   **不 `addView` / `removeView` / `new LayoutParams` / `new Listener`**；
 * - 点击/长按 listener **只在 [create] 里创建一次**，回调里读 `holder.gid` 与
 *   `holder.onTap` 的**当前值**，所以 `bind` 换人时不会点错分组。
 *
 * [build] 保留为「create + bind」的兼容壳，行为与旧实现完全一致（每次新建），
 * 供尚未改成复用的调用点继续使用。
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
         * **不在这里现读 Kv**：`bind()` 是「每行、每次渲染」都跑的热路径，
         * 而 `Kv.getString` 是一次 SQLite 查询 —— 放这儿等于把列表滚动变成查库。
         */
        val showUnread: Boolean = true,
        /** A4-c：整组没消息时展示的成员名（最多 3 个） */
        val memberNames: List<String> = emptyList(),
    )

    /**
     * 复用骨架上的可变状态。**只在 [create] 里 new 一次**，[bind] 只改这里的值。
     *
     * 放在 `row.tag` 里 —— 除了本文件，没有任何地方读写 `row.tag`。
     */
    private class Holder(
        val avatarImage: ImageView,
        val avatarLetters: TextView,
        val avatarBg: GradientDrawable,
        val title: TextView,
        val subtitle: TextView,
        val time: TextView,
        val badge: TextView,
    ) {
        /**
         * 当前这一行代表哪个分组。
         *
         * ⚠️ **点击回调里读的是这个字段**，所以 [bind] 必须**每次**更新它，
         * 否则复用的行会点到上一个分组。
         */
        var gid: Long = -1L
        var onTap: () -> Unit = {}
        var onLongTap: () -> Unit = {}

        /**
         * 头像当前绑定的 wxid。`null` 表示「还没绑过」，用来和 `""`（本来就没头像）区分。
         *
         * 注意 `avatarImage.tag` 是 [AvatarLoader] 自己的复用校验锚，不在这里抢着用。
         */
        var avatarWxid: String? = null
        /** 首字层当前的首字种子 / 是否中性色，用来判断要不要重设文字与背景色 */
        var avatarSeed: String? = null
        var avatarNeutral: Boolean? = null
    }

    /**
     * 建骨架：**只在没有可复用 convertView 时调**。
     *
     * 里面所有「结构」和「监听器」都只做一次；返回的 View 的 `tag` 就是 [Holder]。
     */
    fun create(ctx: Context): View {
        val d = ctx.resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val avatarSize = (48 * d).toInt()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            minimumHeight = (72 * d).toInt()
        }

        // ---- 左：头像（A4-a / A4-c）----
        // 首字兜底层：首字 + 稳定色块（背景色/文字在 bind 里填）
        val lettersBg = GradientDrawable().apply { cornerRadius = avatarSize / 2f }
        val lettersView = TextView(ctx).apply {
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(avatarSize, avatarSize)
            background = lettersBg
        }

        // 真头像层：默认 GONE，命中后由 AvatarLoader 回调切 VISIBLE
        val imageView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(avatarSize, avatarSize)
        }

        val avatarBox = FrameLayout(ctx).apply {
            clipToOutline = true
            // 用固定 size 而非 view.width/height：布局前的 transient 态宽高可能还是 0
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, avatarSize, avatarSize, avatarSize / 2f)
                }
            }
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                rightMargin = (12 * d).toInt()
            }
            addView(imageView)
            addView(lettersView)
        }
        row.addView(avatarBox)

        // ---- 中：两行文字（weight=1 占满剩余宽度）----
        val center = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(ctx).apply {
            textSize = 16f
            setTextColor(0xFF191919.toInt())
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // ⛔ 不放「置顶」角标：置顶分组的区分靠位置（排最前），加标签反而脏。
        // 字段 m.pinned 仍用于 ConvLayout 排序，别删。
        titleRow.addView(titleView)
        center.addView(titleRow)

        val subtitleView = TextView(ctx).apply {
            textSize = 13f
            setTextColor(0xFF8A8A8A.toInt())
            setSingleLine(true)
        }
        center.addView(subtitleView)
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
        // 时间/角标现在常驻，靠 visibility 开关（GONE 不参与布局，效果等价于旧实现的「不 addView」）
        val timeView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(0xFFB0B0B0.toInt())
            visibility = View.GONE
        }
        right.addView(timeView)
        val badgeView = createBadge(ctx, d)
        right.addView(badgeView)
        row.addView(right)

        val h = Holder(
            avatarImage = imageView,
            avatarLetters = lettersView,
            avatarBg = lettersBg,
            title = titleView,
            subtitle = subtitleView,
            time = timeView,
            badge = badgeView,
        )
        row.tag = h

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
        //
        // ⚠️ listener **只在这里建一次**：回调里读 holder 的当前 gid / onTap，
        // 行被复用到别的分组时不需要重新 setOnClickListener。
        row.setOnClickListener { v -> v.post { (v.tag as? Holder)?.onTap?.invoke() } }

        // A5：长按分组行 → 我们自己的菜单（置顶/重命名/管理成员/标记已读/删除）。
        // 不复用微信的会话长按菜单：那个菜单是 PopupWindow 且没有可挂的入口
        // （见 ConvMenu 的头注释），分组行本来也是我们自己的 View，直接自己弹。
        //
        // 返回值必须是 true：否则长按会继续冒泡到 ListView 的
        // OnItemLongClickListener，微信会把这个位置当成会话去弹「删除该聊天」。
        row.setOnLongClickListener { v ->
            (v.tag as? Holder)?.onLongTap?.invoke()
            true
        }

        return row
    }

    /**
     * 把 [m] 绑到 [create] 建出来的 View 上。
     *
     * **只改值**：不 `addView` / `removeView`、不 `new LayoutParams`、不 `new Listener`。
     * 值没变的字段直接跳过（`setText` 本身会判等，但 `setVisibility` / 头像请求不会，
     * 后者还会重排磁盘解码任务，所以必须自己判）。
     */
    fun bind(view: View, m: Model, onTap: () -> Unit, onLongTap: () -> Unit) {
        val h = view.tag as? Holder ?: return // 不是本类建的骨架，画不了

        // ⚠️ gid / 回调必须**每次**写：复用的行换了分组时，
        // 即使 Model 完全相等也不能点到上一个 gid。
        h.gid = m.id
        h.onTap = onTap
        h.onLongTap = onLongTap

        // 这里**故意不做**「m == lastModel 就整体跳过」的短路：
        // 时间列文案依赖当前时间（跨零点后「今天 23:59」要变成「昨天」），
        // 整体跳过会让它一直停在旧值；而逐字段判等已经把「没变就别 setText」覆盖了。
        bindAvatar(h, m)

        if (h.title.text?.toString() != m.name) h.title.text = m.name
        val sub = subtitle(m)
        if (h.subtitle.text?.toString() != sub) h.subtitle.text = sub

        // ---- 右列 ----
        if (m.timeMs > 0L) {
            val label = timeLabel(m.timeMs)
            if (h.time.text?.toString() != label) h.time.text = label
            h.time.visibility = View.VISIBLE
        } else {
            h.time.visibility = View.GONE
        }

        // 开关关掉就不画角标（判据由 ConvFilter 在 rebuild 时算好传进来，这里不读配置）
        if (m.showUnread && m.unread > 0) {
            val label = if (m.unread > 99) "99+" else m.unread.toString()
            if (h.badge.text?.toString() != label) h.badge.text = label
            // 给真机断言留个锚：uiautomator 看不到背景色，`dev.py ui 未读` 全靠这行
            // （验证「显示未读」开关时，开关关掉它就应该整个消失）
            h.badge.contentDescription = "未读${m.unread}"
            h.badge.visibility = View.VISIBLE
        } else {
            h.badge.visibility = View.GONE
            h.badge.contentDescription = null
        }
    }

    /** 兼容壳：等价于 `create + bind`，保留给尚未改成 convertView 复用的调用点。 */
    fun build(ctx: Context, m: Model, onTap: () -> Unit, onLongTap: () -> Unit): View {
        val v = create(ctx)
        bind(v, m, onTap, onLongTap)
        return v
    }

    /**
     * convertView 是不是本类 [create] 建的骨架。
     *
     * 复用前**必须**先过这一关：`Holder` 是 private，外部拿不到类型，
     * 直接把微信的会话行 convertView 传进 [bind] 会画不出来（`bind` 里 tag 校验失败会静默返回）。
     */
    fun isGroupRow(v: View?): Boolean = v?.tag is Holder

    /**
     * 头像绑定：`FrameLayout`（圆形裁剪）叠两层——
     * - 底层 [ImageView]：真头像（`AvatarLoader` 反射微信 AvatarStorage 取，`CENTER_CROP`）
     * - 顶层 [TextView]：首字 + 由名字派生的稳定色（**兜底，逻辑原样保留**）
     *
     * 首字层默认可见、图片层默认隐藏；`AvatarLoader` 命中真头像后隐藏首字层。
     * 拿不到真头像时首字层一直可见，配色规则（[colorOf]）一字不改 —— 那是专门修过的
     * 「同一个名字永远同色」稳定性 bug。
     *
     * 复用要点：**换人时先把首字层顶回可见**，否则新分组没有真头像时，
     * 上一行的头像会留在屏幕上（串脸）。
     */
    private fun bindAvatar(h: Holder, m: Model) {
        val seed = m.lastSender.ifBlank { m.name }
        val neutral = m.lastSender.isBlank()
        val wxid = m.lastSenderWxid

        if (h.avatarSeed != seed || h.avatarNeutral != neutral) {
            h.avatarLetters.text = seed.trim().firstOrNull()?.toString() ?: "组"
            h.avatarBg.setColor(if (neutral) 0xFFB0B0B0.toInt() else colorOf(seed))
            h.avatarSeed = seed
            h.avatarNeutral = neutral
        }

        // wxid 没变 → 图还是对的人，不重新请求（`null` 与 `""` 要区分开）
        if (h.avatarWxid == wxid) return
        h.avatarWxid = wxid

        // 换人：清掉旧图 + 把 AvatarLoader 的 tag 锚置空（作废还在路上的异步回调），
        // 先退回首字兜底，命中真头像后由回调再切回来。
        h.avatarImage.tag = null
        h.avatarImage.setImageDrawable(null)
        h.avatarImage.visibility = View.GONE
        h.avatarLetters.visibility = View.VISIBLE

        if (wxid.isNotBlank()) {
            // 内存缓存（主线程）→ 磁盘解码（后台，tag 校验防错位）→ 拿不到保持首字兜底
            AvatarLoader.bind(h.avatarImage, wxid) {
                h.avatarLetters.visibility = View.GONE
                h.avatarImage.visibility = View.VISIBLE
            }
        }
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

    /** 同一个名字永远同一个颜色（否则每次 getView 颜色都在跳） */
    private fun colorOf(seed: String): Int {
        val h = seed.hashCode()
        val hue = (h and 0x7FFFFFFF) % 360
        return android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.85f))
    }

    /** 未读角标：样式一次性建好，文字由 [bind] 填（`99+` 截断见 bind） */
    private fun createBadge(ctx: Context, d: Float): TextView {
        return TextView(ctx).apply {
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
            visibility = View.GONE
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
