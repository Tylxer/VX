package com.lx.wxgroup.feature

import com.lx.wxgroup.data.ConvLayout

/**
 * 「折叠置顶聊天」跟随逻辑的纯判据（零 Android 依赖，JVM 直接可测）。
 *
 * 与 [GroupBar.syncPinFold] 无关：那套只服务于关掉「启用分组行」开关后的旧机制，
 * 默认配置下是死代码。这里判的是 **ConvFilter 插入的分组行**该不该在折叠态消失。
 *
 * 判据分两路，[detectPinFolded] 是**数据层兜底**：ConvFilter 优先用「扫已布局文字」
 * （最接近用户看到的事实），扫不到时退回这里。
 */
object PinFoldLogic {

    /**
     * 从会话快照推导「折叠置顶聊天」状态（数据层兜底）。
     *
     * 前提（真机需证伪，见 docs/_r31_pinfold.md）：
     * 折叠后微信把 N 条置顶会话换成一条「N个置顶聊天」item，它仍是 inner adapter
     * 里的**真实 item**（不是 ListView header），但反射读不到 `field_username`
     * ⇒ [com.lx.wxgroup.core.ConvReader.username] 返回空串 ⇒ 以
     * `ConvEntry("", unreadable=false)` 进快照。
     *
     * 判据（收紧到「顶部首条」，避免误伤）：
     *  - 顶部首条是「空 wxid 且 unreadable=false」的项，且后面还有能读到 wxid 的会话 ⇒ 折叠
     *  - 其余一律 false（= 展开 / 判不出，调用方按展开处理）
     *
     * 判错后果（刻意偏向「不折叠」）：
     *  - 假阳性（展开判成折叠）⇒ pinned 分组行被藏掉，用户进不了置顶分组。靠「首条 +
     *    后续有真实会话佐证」排除：正常会话列表顶部首条要么是真实 wxid、要么是
     *    `unreadable=true` 的占位，几乎不可能踩中。
     *  - 假阴性（折叠判成展开）⇒ 分组行照旧停在顶部（= 现状），可接受，下次 rebuild 重判。
     *  - 全局字段读失败（`field_username` 改名导致整列空）⇒ 后面没有真实 wxid，返回 false，
     *    不会把整列空误判成折叠。
     *
     * @return true=折叠 / false=展开或判不出
     */
    fun detectPinFolded(convs: List<ConvLayout.ConvEntry>): Boolean {
        val first = convs.firstOrNull() ?: return false
        if (first.unreadable) return false          // 顶部是 item==null 占位 ⇒ 判不出按展开
        if (first.wxid.isNotBlank()) return false   // 顶部是真实会话 ⇒ 展开
        // 顶部是「真实 item 但读不到 username」：疑似折叠入口，需后续真实会话佐证
        return convs.drop(1).any { it.wxid.isNotBlank() }
    }

    /**
     * 给定折叠态，pinned 分组行该不该插入。
     * 折叠态（true）⇒ 收进「N个置顶聊天」，不插入；展开 / 判不出（false / null）⇒ 插入。
     */
    fun shouldInsertPinned(pinFolded: Boolean?): Boolean = pinFolded != true
}
