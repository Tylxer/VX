package com.lx.wxgroup.feature

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.Friend
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.util.LogX

/**
 * 长按通讯录好友后弹出的「加入分组」选择框。
 *
 * 三条硬约束：
 * 1. **全部用代码构造 View**，不打包 xml layout —— 避免资源 id 与宿主冲突
 * 2. 必须用宿主的 Activity 上下文 —— 模块自己那个 uid 的 Context 弹窗会 BadTokenException
 * 3. 只能在主线程显示 —— 长按回调就在主线程，仍包一层 Handler 保险
 *
 * Q1=b 多标签模型：这里是多选（一个好友可以同时进多个分组）。
 */
object GroupPicker {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun show(friend: Friend) {
        mainHandler.post {
            runCatching { showInternal(friend) }
                .onFailure { LogX.e("GroupPicker failed", it) }
        }
    }

    private fun showInternal(friend: Friend) {
        val ctx = HostContext.activity() ?: HostContext.app()
        if (ctx == null) {
            LogX.w("GroupPicker: no host context")
            return
        }
        val db = GroupDb.current() ?: run {
            LogX.w("GroupPicker: db not ready")
            return
        }

        val groups = db.listGroups()
        val joined = db.groupsOf(friend.wxid).map { it.id }.toSet()

        val pad = dp(ctx, 20)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(ctx, 12), pad, dp(ctx, 8))
        }

        root.addView(TextView(ctx).apply {
            text = friend.displayName
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(ctx, 4))
        })
        root.addView(TextView(ctx).apply {
            text = friend.wxid
            textSize = 12f
            setPadding(0, 0, 0, dp(ctx, 10))
            setTextColor(Color.parseColor("#888888"))
        })

        val nameInput = EditText(ctx).apply {
            hint = "新分组名称（可留空）"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            textSize = 15f
        }
        root.addView(nameInput)

        val listLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val boxes = LinkedHashMap<Long, CheckBox>()
        for (g in groups) {
            val cb = CheckBox(ctx).apply {
                text = "${g.name}（${db.countMembers(g.id)}）"
                isChecked = g.id in joined
                textSize = 15f
                setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8))
            }
            boxes[g.id] = cb
            listLayout.addView(cb)
        }
        if (groups.isEmpty()) {
            listLayout.addView(TextView(ctx).apply {
                text = "还没有分组，在上面的输入框里填一个名字即可新建"
                textSize = 14f
                setTextColor(Color.parseColor("#888888"))
                setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10))
            })
        }
        val scroll = ScrollView(ctx).apply { addView(listLayout) }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 280))
        )

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("加入分组")
            .setView(root)
            .setNegativeButton("取消", null)
            // M2-C：进分组管理页（新建/改名/删组/批量加人）。
            // AlertDialog 的按钮默认点了就 dismiss，不用自己 dismiss。
            .setNeutralButton("管理分组") { _, _ -> GroupManager.show() }
            .setPositiveButton("确定", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                apply(ctx, db, friend, nameInput.text?.toString()?.trim().orEmpty(), boxes)
                dialog.dismiss()
            }
        }
        dialog.show()
        LogX.i("PICKER shown for ${friend.wxid} (${friend.displayName}), groups=${groups.size}")
    }

    private fun apply(
        ctx: android.content.Context,
        db: GroupDb,
        friend: Friend,
        newGroupName: String,
        boxes: Map<Long, CheckBox>,
    ) {
        var target: Long? = null
        if (newGroupName.isNotEmpty()) {
            val id = db.createGroup(newGroupName)
            if (id > 0) target = id
        }
        var added = 0
        var removed = 0
        for ((gid, box) in boxes) {
            if (box.isChecked) {
                if (db.addMember(gid, friend.wxid, friend.displayName, friend.type)) added++
            } else {
                if (db.removeMember(gid, friend.wxid)) removed++
            }
        }
        if (target != null && db.addMember(target, friend.wxid, friend.displayName, friend.type)) {
            added++
        }
        val msg = "已更新：加 $added 组 / 移 $removed 组"
        LogX.i("PICKER applied ${friend.wxid}: $msg")
        runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
            .onFailure { LogX.w("PICKER toast failed: ${it.javaClass.simpleName}") }
    }

    private fun dp(ctx: android.content.Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
