package com.lx.wxgroup.feature

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.lx.wxgroup.core.ChatOpener
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.GroupMember
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.ui.Panel
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

/**
 * M3 独立分组页：一个分组的成员都在这里。
 *
 * 为什么是「独立页」而不是在会话列表里内联展开：
 * 内联展开要做 ListView 嵌套 ListView，手势冲突 + 复用失效，成员一多就卡。
 * 独立页是普通全屏 Dialog，几百人也是一条 ListView 平铺，性能可控。
 *
 * 交互（用户拍板）：
 *  - 点成员 -> 直接打开和他的单聊
 *  - 长按成员 -> 移出本组 / 加入其他分组
 */
object GroupDetailPage {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun open(context: Context?, groupId: Long, groupName: String) {
        mainHandler.post {
            runCatching { openInternal(context, groupId, groupName) }
                .onFailure { LogX.e("DETAIL open failed", it) }
        }
    }

    private fun openInternal(context: Context?, groupId: Long, groupName: String) {
        val ctx = HostContext.activity() ?: context ?: HostContext.app()
        if (ctx == null) {
            LogX.w("DETAIL: no host context")
            return
        }
        val db = GroupDb.current() ?: run {
            LogX.w("DETAIL: db not ready")
            return
        }
        val all = db.membersOf(groupId).toMutableList()
        LogX.i("DETAIL open group=$groupId name=$groupName members=${all.size}")

        val pad = UiKit.dp(ctx, 14)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        // ---- 顶栏 ----
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, UiKit.dp(ctx, 10), pad, UiKit.dp(ctx, 10))
        }
        val close = TextView(ctx).apply {
            text = "✕"
            textSize = 17f
            setTextColor(UiKit.fgColor(ctx))
            setPadding(0, 0, UiKit.dp(ctx, 14), 0)
        }
        val title = TextView(ctx).apply {
            text = "$groupName · ${all.size} 人"
            textSize = 16f
            setTextColor(UiKit.fgColor(ctx))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val manage = TextView(ctx).apply {
            text = "管理"
            textSize = 14f
            setTextColor(UiKit.brandGreen())
            setPadding(UiKit.dp(ctx, 12), 0, 0, 0)
        }
        bar.addView(close)
        bar.addView(title)
        bar.addView(manage)
        root.addView(bar)

        // ---- 搜索 ----
        // R21：见 UiKit 中该助手的 KDoc
        val search = UiKit.bindSoftInput(EditText(ctx).apply {
            hint = "搜索成员"
            textSize = 14f
            setTextColor(UiKit.fgColor(ctx))
            setHintTextColor(UiKit.muted())
            setPadding(pad, UiKit.dp(ctx, 8), pad, UiKit.dp(ctx, 8))
            background = UiKit.pill(UiKit.softBg(), radiusPx = UiKit.dp(ctx, 8).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = pad
                rightMargin = pad
                bottomMargin = UiKit.dp(ctx, 8)
            }
        })
        root.addView(search)

        // ---- 成员列表 ----
        val adapter = MemberAdapter(ctx, all)
        // ⚠️ 同 PickerPage：apply 块内 this.adapter 赋值不可靠，显式落到局部变量再设
        val list = ListView(ctx)
        list.adapter = adapter
        list.dividerHeight = UiKit.dp(ctx, 1)
        list.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        // 行内控件会抢焦点，必须让 ListView 自己接管
        list.isFocusable = true
        root.addView(list)

        // ---- 底部 ----
        // dialog 先建出来（还没 setContentView / show）：下面「群发消息」的回调要用它 dismiss。
        val dialog = Dialog(ctx)
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, UiKit.dp(ctx, 8), pad, UiKit.dp(ctx, 10))
        }
        val addBtn = button(ctx, "+ 添加成员", UiKit.brandGreen()) {
            BatchPicker.show(groupId, groupName)
        }
        val sendBtn = button(ctx, "群发消息", UiKit.softBg(), darkText = true) {
            sendToGroup(ctx, dialog, db, groupId, groupName)
        }
        bottom.addView(addBtn)
        bottom.addView(sendBtn)
        root.addView(bottom)

        dialog.setContentView(root)
        // ⛔ R21：裸 Dialog 不在 Panel 栈里，Panel 清的 flag 管不到它 ⇒ 单独清，
        // 否则「搜索成员」框点了没反应（能拿到焦点，IME 不出现）。
        UiKit.clearAltFocusableIm(dialog, " GroupDetailPage")
        dialog.setOnDismissListener { GroupBar.refreshAll() }
        close.setOnClickListener { dialog.dismiss() }
        manage.setOnClickListener { MemberPage.show(groupId, groupName) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString().orEmpty())
            }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val m = adapter.getItem(position)
            if (!ChatOpener.open(ctx, m.wxid)) {
                UiKit.toast(ctx, "打不开聊天页：${m.wxid}")
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val m = adapter.getItem(position)
            memberMenu(ctx, db, groupId, m, adapter)
            true
        }
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    /**
     * 「群发消息」按钮：把**当前分组**作为发送目标送进群发页。
     *
     * ## 为什么是「预填目标」而不是在这里直接发
     * 群发是**全程后台**的任务（关掉弹窗也继续跑、再打开能看到进度），它的
     * 运行态、风控闸门、暂停/继续、结果对账全在 [MassSendPage] 的单例里。
     * 在这里另起一套发送循环 = 同一件事两份实现，两边迟早不一致。
     * ⇒ 只做**填目标 + 跳转**，把「发什么、发不发」交给那一页决定。
     *
     * ## 文案要说清「不是立刻发」
     * 用户点「群发消息」的心理预期是「点了就发出去」。这里只填目标，
     * 于是必须同时**关掉当前页、打开群发页**，让用户看到那个还需要
     * 填内容 + 点「开始群发」的页面 —— 否则就是「点了没反应」。
     * 填完给的那句 toast 是补充说明，不是唯一反馈。
     *
     * ## ⚠️ 绝不静默覆盖已有目标
     * `broadcast.targets` 是群发页的长期配置，用户可能在里面精心选了 30 个分组 / 标签 / 好友。
     * 一个从「成员列表」进来的按钮如果直接把它改成一个分组，就是**静默的数据损失**
     * （用户下次群发才发现目标全没了）。所以：空着就直接填，
     * 已经是它就直接跳，**有别的内容必须先问一句**。
     */
    private fun sendToGroup(ctx: Context, dialog: Dialog, db: GroupDb, groupId: Long, groupName: String) {
        val picked = "${MassSendLogic.PREFIX_GROUP}$groupId"
        val members = runCatching { db.memberWxids(groupId).size }.getOrDefault(0)
        if (members == 0) {
            UiKit.toast(ctx, "「$groupName」还没有成员")
            return
        }
        val cur = runCatching { Kv.getList(Keys.BROADCAST_TARGETS) }.getOrDefault(emptyList())

        // 空着 / 已经是它 —— 不用问，直接进
        if (cur.isEmpty() || cur == listOf(picked)) {
            applyTargetAndOpen(ctx, dialog, picked, groupId, members, groupName, replaced = 0)
            return
        }

        AlertDialog.Builder(ctx)
            .setTitle("替换发送目标？")
            .setMessage(
                "群发页当前已选 ${cur.size} 个目标。\n" +
                    "点「设为当前分组」会把它们全部换成「$groupName」($members 人)。"
            )
            .setPositiveButton("设为当前分组") { _, _ ->
                applyTargetAndOpen(ctx, dialog, picked, groupId, members, groupName, replaced = cur.size)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyTargetAndOpen(
        ctx: Context,
        dialog: Dialog,
        picked: String,
        groupId: Long,
        members: Int,
        groupName: String,
        replaced: Int,
    ) {
        Kv.putList(Keys.BROADCAST_TARGETS, listOf(picked))
        LogX.i(
            "DETAIL send-to-group group=$groupId members=$members replaced=$replaced " +
                "-> 目标已预填，跳转群发页"
        )
        dialog.dismiss()
        Panel.open(MassSendPage)
        UiKit.toast(ctx, "目标已设为「$groupName」($members 人)，填好消息内容后点开始")
    }

    private fun memberMenu(ctx: Context,
        db: GroupDb,
        groupId: Long,
        member: GroupMember,
        adapter: MemberAdapter,
    ) {
        val name = member.displayName?.takeIf { it.isNotBlank() } ?: member.wxid
        AlertDialog.Builder(ctx)
            .setTitle(name)
            .setItems(arrayOf("打开聊天", "移出本组", "加入其他分组")) { _, which ->
                when (which) {
                    0 -> if (!ChatOpener.open(ctx, member.wxid)) {
                        UiKit.toast(ctx, "打不开聊天页：$name")
                    }
                    1 -> {
                        val n = db.removeMember(groupId, member.wxid)
                        LogX.i("DETAIL remove group=$groupId wxid=${member.wxid} rows=$n")
                        adapter.remove(member)
                        // 成员关系变了 ⇒ 重读 cachedMembers 再重算（refresh() 不重读成员，筛选态下会无效）
                        ConvFilter.refreshMembers()
                    }
                    2 -> {
                        val friend = db.findFriend(member.wxid)
                        if (friend == null) {
                            UiKit.toast(ctx, "好友缓存里没有他，先去通讯录刷一次")
                        } else {
                            GroupPicker.show(friend)
                        }
                    }
                }
            }
            .show()
    }

    private fun button(
        ctx: Context,
        text: String,
        fill: Int,
        darkText: Boolean = false,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setTextColor(if (darkText) UiKit.fgColor(ctx) else Color.WHITE)
        gravity = Gravity.CENTER
        background = UiKit.pill(fill, radiusPx = UiKit.dp(ctx, 8).toFloat())
        setPadding(UiKit.dp(ctx, 10), UiKit.dp(ctx, 10), UiKit.dp(ctx, 10), UiKit.dp(ctx, 10))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = UiKit.dp(ctx, 8)
        }
        setOnClickListener { onClick() }
    }

    private class MemberAdapter(
        private val ctx: Context,
        private val source: MutableList<GroupMember>,
    ) : BaseAdapter() {

        private var shown: List<GroupMember> = source.toList()

        fun filter(keyword: String) {
            shown = if (keyword.isBlank()) {
                source.toList()
            } else {
                val k = keyword.trim()
                source.filter {
                    (it.displayName?.contains(k, true) == true) || it.wxid.contains(k, true)
                }
            }
            notifyDataSetChanged()
        }

        fun remove(member: GroupMember) {
            source.removeAll { it.wxid == member.wxid }
            shown = shown.filter { it.wxid != member.wxid }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): GroupMember = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val m = getItem(position)
            val row = (convertView as? LinearLayout) ?: newRow()
            row.findViewById<TextView>(1).apply {
                text = m.displayName?.takeIf { it.isNotBlank() } ?: m.wxid
            }
            row.findViewById<TextView>(2).apply {
                text = m.wxid
            }
            return row
        }

        private fun newRow(): LinearLayout {
            val pad = UiKit.dp(ctx, 14)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, UiKit.dp(ctx, 10), pad, UiKit.dp(ctx, 10))
            }
            val badge = TextView(ctx).apply {
                id = 1
                textSize = 15f
                setTextColor(UiKit.fgColor(ctx))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sub = TextView(ctx).apply {
                id = 2
                textSize = 11f
                setTextColor(UiKit.muted())
            }
            row.addView(badge)
            row.addView(sub)
            return row
        }
    }
}
