package com.lx.wxgroup.ui

import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import com.lx.wxgroup.data.GroupMember

/**
 * 微信群聊同款的「复合头像」：把前几位成员的头像拼在一起。
 *
 * 拼法跟微信一致：
 *  1 人 → 整张；2 人 → 左右各半；3 人 → 左半整列 + 右侧上下两张；4 人及以上 → 2×2。
 *
 * 头像本体目前用「成员首字 + 由名字哈希出的稳定色块」—— 和微信默认头像
 * 观感一致，且不依赖任何宿主接口。接真实头像时只需在 tile() 里换成
 * ImageView 并填入 bitmap，外层拼接逻辑不用动。
 */
object AvatarCompose {

    /** 微信默认头像的常用色，取哈希保证同一个成员每次颜色一致 */
    private val PALETTE = intArrayOf(
        0xFF7EB6E8.toInt(), 0xFF8CC7A1.toInt(), 0xFFF0B37E.toInt(),
        0xFFE88E8E.toInt(), 0xFFB49BE0.toInt(), 0xFF79C7C5.toInt(),
        0xFFE5B45C.toInt(), 0xFF9AA7C7.toInt(),
    )

    private fun colorOf(seed: String): Int {
        val h = seed.hashCode() and 0x7FFFFFFF
        return PALETTE[h % PALETTE.size]
    }

    private fun nameOf(m: GroupMember): String {
        val n = m.displayName?.trim().orEmpty()
        return if (n.isNotEmpty()) n else m.wxid
    }

    /** 复合头像容器：48dp 圆角方块（微信会话行头像就是这个尺寸和圆角） */
    fun build(ctx: android.content.Context, members: List<GroupMember>): View {
        val size = UiKit.dp(ctx, 48)
        val radius = UiKit.dp(ctx, 6).toFloat()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.pill(0xFFD8D8D8.toInt(), radiusPx = radius)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = UiKit.dp(ctx, 12)
            }
        }
        if (Build.VERSION.SDK_INT >= 21) {
            root.outlineProvider = ViewOutlineProvider.BACKGROUND
            root.clipToOutline = true
        }

        val names = members.map(::nameOf)
        when {
            names.isEmpty() -> root.addView(tile(ctx, "组", "组", 1f, LinearLayout.VERTICAL))
            names.size == 1 -> root.addView(tile(ctx, names[0], names[0], 1f, LinearLayout.VERTICAL))
            names.size == 2 -> {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(size, 0, 1f)
                }
                row.addView(tile(ctx, names[0], names[0], 1f, LinearLayout.HORIZONTAL))
                row.addView(tile(ctx, names[1], names[1], 1f, LinearLayout.HORIZONTAL))
                root.addView(row)
            }
            names.size == 3 -> {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(size, 0, 1f)
                }
                row.addView(tile(ctx, names[0], names[0], 1f, LinearLayout.HORIZONTAL))
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, size, 1f)
                }
                col.addView(tile(ctx, names[1], names[1], 1f, LinearLayout.VERTICAL))
                col.addView(tile(ctx, names[2], names[2], 1f, LinearLayout.VERTICAL))
                row.addView(col)
                root.addView(row)
            }
            else -> {
                for (r in 0..1) {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(size, 0, 1f)
                    }
                    row.addView(tile(ctx, names[r * 2], names[r * 2], 1f, LinearLayout.HORIZONTAL))
                    row.addView(tile(ctx, names[r * 2 + 1], names[r * 2 + 1], 1f, LinearLayout.HORIZONTAL))
                    root.addView(row)
                }
            }
        }
        return root
    }

    /** 单块头像。接真实头像时把这里换成 ImageView + setImageBitmap 即可 */
    private fun tile(
        ctx: android.content.Context,
        seed: String,
        label: String,
        weight: Float,
        axis: Int,
    ): View {
        val lp = if (axis == LinearLayout.VERTICAL) {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight)
        } else {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
        }
        return TextView(ctx).apply {
            text = label.take(1)
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(colorOf(seed))
            layoutParams = lp
        }
    }
}
