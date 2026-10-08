package com.lx.wxgroup.feature

import com.lx.wxgroup.ui.Panel

/**
 * 分组管理的**兼容入口**。真正的界面已经搬到 [GroupManagerPage]（页面栈内的一页）。
 *
 * 保留 `show()` 是因为 GroupBar / GroupPicker / EntryReceiver 几处都在调它 ——
 * 那些入口是从微信自己的界面直接唤起，作为**独立面板**打开（栈里只有它一页，
 * 「关闭」即退出）。从模块主界面进来则走 `Panel.push(GroupManagerPage())`，
 * 那样「返回」是回到主界面，而不是关掉整个面板。
 */
object GroupManager {

    fun show() {
        Panel.open(GroupManagerPage())
    }
}
