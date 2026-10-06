package com.lx.wxgroup.core

import android.view.View

/**
 * 当前通讯录列表的引用（RecyclerView + Adapter），由 UiProbe 在认出通讯录 Adapter 时写入。
 *
 * M2-B 的长按菜单要靠它判断「这次长按发生在通讯录列表里」，
 * 并由此反查条目位置与数据。用引用而不是类名匹配，是因为同一个 WxRecyclerView
 * 在会话页、朋友圈等处也会出现，只有这个实例才是通讯录。
 */
object ContactListRef {

    @Volatile
    var listView: View? = null
        private set

    @Volatile
    var adapter: Any? = null
        private set

    fun set(listView: View, adapter: Any) {
        this.listView = listView
        this.adapter = adapter
    }

    fun clear() {
        listView = null
        adapter = null
    }

    /** 判断 view 是否在通讯录列表内部（向上找祖先，最多 12 层） */
    fun contains(view: View): Boolean {
        val target = listView ?: return false
        if (view === target) return false
        var p = view.parent
        var depth = 0
        while (p is View && depth++ < 12) {
            if (p === target) return true
            p = p.parent
        }
        return false
    }
}
