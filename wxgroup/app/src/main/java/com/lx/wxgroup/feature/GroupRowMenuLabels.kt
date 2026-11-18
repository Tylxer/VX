package com.lx.wxgroup.feature

/**
 * 分组行长按菜单文案的纯映射（R60）。
 *
 * gconv ON（分组行走微信原生「折叠的群聊」页）时，末项对齐微X 的「解散聊天分组」；
 * OFF 时保留旧文案「删除分组」。两项语义等价：都只是取消归组、成员不丢
 * （`db.deleteGroup` 只删分组表，伪会话由 `GroupConvStore` 下一轮 sync 清掉）。
 *
 * ⛔ OFF 分支必须逐字保留旧文案 —— 这是红线①「GCONV_MODE=false 零差异」的一部分：
 * `GroupRowMenu` 在 gconv=0 的旧自绘分组行上照样会弹，直接改字面量会让已关掉新模式
 * 的用户看到新文案（→ `_r55_microx_click_longclick.md` Q6）。
 *
 * 抽成不依赖 Android 的纯函数，便于 JVM 单测固化这两条互斥分支。
 */
object GroupRowMenuLabels {
    const val DELETE = "删除分组"
    const val DISSOLVE = "解散聊天分组"

    /** 菜单末项 / 确认框标题文案 */
    fun delete(gconvOn: Boolean): String = if (gconvOn) DISSOLVE else DELETE

    /** 删除完成后的 toast 文案 */
    fun deleted(gconvOn: Boolean): String = if (gconvOn) "已解散聊天分组" else "已删除分组"

    // --------------------------------------------------------------- R61 菜单项集合判据

    /**
     * R61：原生菜单里「我方追加哪几项」的纯判据（零 Android 依赖，供 JVM 单测固化）。
     *
     * - `gid == null`（真实 wxid）⇒ 追加 `加入分组 + 标记已读`（注入顺序逐字保持现状）；
     * - `gid != null`（伪会话 `wxgrp_<hex>`）⇒ **只**追加 `解散聊天分组`，
     *   不插 `加入分组`/`标记已读`（B4：假 id 永不传给 findFriend/GroupPicker）。
     *   R62 起不再拦截宿主危险项「删除该聊天 / 不显示该聊天」，交回微信原生执行。
     *
     * 两条分支互斥、无第三条取值。这是把「按 gid 分流决定菜单项集合」从 Hook 代码里
     * 抽出来的可自证锚点 —— ConvMenuInject.addItems 必须与此保持一致。
     */
    enum class MenuItem(val label: String) {
        DISSOLVE("解散聊天分组"),
        JOIN("加入分组"),
        READ("标记已读"),
    }

    data class MenuSet(val items: List<MenuItem>)

    fun itemSet(gid: Long?): MenuSet = if (gid == null) {
        MenuSet(listOf(MenuItem.JOIN, MenuItem.READ))
    } else {
        MenuSet(listOf(MenuItem.DISSOLVE))
    }
}
