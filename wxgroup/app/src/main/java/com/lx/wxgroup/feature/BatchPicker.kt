package com.lx.wxgroup.feature

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.Friend
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

/**
 * M2-C 的核心：批量勾选加人（需求 Q2=b）。
 *
 * 长按一个一个加只适合零星补人，几百人的分组必须走这个页面。
 *
 * 三个必须注意的点：
 * 1. **必须用 ListView + BaseAdapter 复用行**，不能像 GroupPicker 那样把每个 CheckBox 都 addView 进去 ——
 *    2245 个好友一次性 inflate 会直接卡死甚至 OOM。ListView 只创建屏幕上看得见的那十几个行。
 * 2. **CheckBox 必须关掉 focusable/clickable**，否则它会吃掉触摸事件，
 *    ListView 的 onItemClick 永远不触发（经典的「点了没反应」坑）。关掉之后整行可点。
 * 3. 过滤（搜索）只影响显示，**不改 selected 集合** —— 已勾选的人被搜索过滤掉后
 *    再清掉搜索框，勾选状态必须还在，否则用户会觉得数据丢了。
 */
object BatchPicker {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun show(groupId: Long, groupName: String) {
        mainHandler.post {
            runCatching { showInternal(groupId, groupName) }
                .onFailure { LogX.e("BatchPicker failed", it) }
        }
    }

    private fun showInternal(groupId: Long, groupName: String) {
        val ctx = HostContext.activity() ?: HostContext.app()
        if (ctx == null) {
            LogX.w("BatchPicker: no host context")
            return
        }
        val db = GroupDb.current() ?: run {
            LogX.w("BatchPicker: db not ready")
            return
        }

        val t0 = System.currentTimeMillis()
        val all = db.allRealFriends()
        val loadMs = System.currentTimeMillis() - t0
        if (all.isEmpty()) {
            UiKit.toast(ctx, "好友缓存为空，先去通讯录滚一遍再回来")
            LogX.w("BatchPicker: friend_cache empty")
            return
        }

        // 已有成员预先勾上，这样打开页面看到的就是当前状态，不会「加完才发现重复」
        val selected = LinkedHashSet(db.memberWxids(groupId))
        val before = selected.toSet()
        val byWxid = HashMap<String, Friend>(all.size * 2)
        for (f in all) byWxid[f.wxid] = f

        LogX.i("BATCH open group=$groupId($groupName) friends=${all.size} preselect=${before.size} load=${loadMs}ms")

        val adapter = FriendAdapter(ctx, all.toMutableList(), selected)

        val counter = UiKit.label(ctx, "", 13f, bold = true)
        val search = EditText(ctx).apply {
            hint = "搜索昵称 / 备注"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            textSize = 14f
        }

        val btnAll = android.widget.Button(ctx).apply { text = "全选可见" }
        val btnNone = android.widget.Button(ctx).apply { text = "清空选择" }
        val btnInvert = android.widget.Button(ctx).apply { text = "反选可见" }
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnAll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnInvert, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnNone, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        // ⚠️ 同 PickerPage：apply 块内 this.adapter 赋值不可靠，显式落到局部变量再设
        val list = UiKit.listView(ctx, 0.5f)
        list.adapter = adapter

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiKit.dp(ctx, 16)
            setPadding(p, UiKit.dp(ctx, 8), p, 0)
        }
        root.addView(counter)
        root.addView(search)
        root.addView(btnRow)
        root.addView(list)

        fun refreshCounter() {
            counter.text = "已选 ${selected.size} 人 · 可见 ${adapter.count} / 共 ${all.size}"
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("批量添加 → $groupName")
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", null)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            val f = adapter.getItem(position)
            if (selected.contains(f.wxid)) selected.remove(f.wxid) else selected.add(f.wxid)
            adapter.notifyDataSetChanged()
            refreshCounter()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString().orEmpty())
                refreshCounter()
            }
        })

        btnAll.setOnClickListener {
            for (i in 0 until adapter.count) selected.add(adapter.getItem(i).wxid)
            adapter.notifyDataSetChanged()
            refreshCounter()
        }
        btnNone.setOnClickListener {
            selected.clear()
            adapter.notifyDataSetChanged()
            refreshCounter()
        }
        btnInvert.setOnClickListener {
            for (i in 0 until adapter.count) {
                val wxid = adapter.getItem(i).wxid
                if (selected.contains(wxid)) selected.remove(wxid) else selected.add(wxid)
            }
            adapter.notifyDataSetChanged()
            refreshCounter()
        }

        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val t1 = System.currentTimeMillis()
                val toAdd = selected.mapNotNull { byWxid[it] }
                val toRemove = before - selected
                val added = db.addMembers(groupId, toAdd)
                val removed = db.removeMembers(groupId, toRemove)
                val ms = System.currentTimeMillis() - t1
                val msg = "新增 $added 人 / 移出 $removed 人（${ms}ms）"
                LogX.i("BATCH applied group=$groupId: $msg")
                UiKit.toast(ctx, msg)
                dialog.dismiss()
            }
        }

        refreshCounter()
        dialog.show()
        UiKit.widen(dialog, ctx)
    }

    private class FriendAdapter(
        private val ctx: Context,
        private val all: MutableList<Friend>,
        private val selected: Set<String>,
    ) : BaseAdapter() {

        /** 当前可见（过滤后）的数据。过滤只影响它，不影响 selected。 */
        private val visible = ArrayList<Friend>(all)

        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): Friend = visible[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? FriendRow) ?: FriendRow(ctx)
            val f = getItem(position)
            row.bind(f, selected.contains(f.wxid))
            return row
        }

        fun filter(keyword: String) {
            val kw = keyword.trim()
            visible.clear()
            if (kw.isEmpty()) {
                visible.addAll(all)
            } else {
                val lower = kw.lowercase()
                for (f in all) {
                    if (f.displayName.lowercase().contains(lower) ||
                        f.wxid.lowercase().contains(lower)
                    ) visible.add(f)
                }
            }
            notifyDataSetChanged()
        }
    }

    private class FriendRow(ctx: Context) : LinearLayout(ctx) {
        private val box = CheckBox(ctx).apply {
            // 关掉焦点与点击：让触摸事件冒泡到 ListView，整行都能切换勾选
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
        }
        private val name = TextView(ctx).apply { textSize = 15f }
        private val sub = TextView(ctx).apply {
            textSize = 11f
            setTextColor(0xFF888888.toInt())
        }

        init {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val p = UiKit.dp(ctx, 12)
            setPadding(p, UiKit.dp(ctx, 6), p, UiKit.dp(ctx, 6))
            addView(
                box,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            val textCol = LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(name)
                addView(sub)
            }
            addView(textCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }

        fun bind(friend: Friend, checked: Boolean) {
            box.isChecked = checked
            name.text = friend.displayName
            sub.text = friend.wxid
        }
    }
}
