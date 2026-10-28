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

    /**
     * `GroupedAdapter` 的「配置指纹」。
     *
     * ⚠️ 存在理由（R31 真机踩坑）：指纹相同 ⇒ rebuild 直接短路返回、**一行都不重算**。
     * 折叠/展开时会话条数、顺序、分组、筛选态全都没变，只有 [pinFolded] 变了；
     * 少了这一项，日志会打 `pinFold(view) folded=true -> rebuild`，而列表纹丝不动。
     *
     * @param pinFolded `null` 表示「还没扫到」，与 true/false 必须是**三个不同**的串
     */
    fun configStamp(
        n: Int,
        enabled: Boolean,
        filterId: Long,
        showUnread: Boolean,
        membersHash: Int,
        groupsHash: Int,
        pinFolded: Boolean?,
    ): String = "$n|$enabled|$filterId|$showUnread|$membersHash|$groupsHash|$pinFolded"

    // ---------------------------------------------------------------- R32 快路径决策

    /**
     * 折叠态翻转时的重建动作（快路径决策，零 Android 依赖，JVM 直接可测）。
     *
     * R32 体验问题的核心判据：折叠/展开必须走**即时路径**（一帧内完成），
     * 不能和「收新消息」共用 250ms 去抖；同时尽量复用快照，只在必要时重读一次。
     */
    enum class FoldAction {
        /** 没翻转（或重复报同一方向）⇒ 不动 */
        NOOP,

        /**
         * 翻转了，且 inner adapter 的 `count` **未变** ⇒ 折叠入口不是 inner 的真实
         * item（是 header / 独立 View），快照下标未动，可复用缓存的 snaps，只重算 layout。
         */
        RELAYOUT_REUSE,

        /**
         * 翻转了，且 `count` **变了** ⇒ 折叠入口是 inner 的真实 item，快照下标整体
         * 偏移，必须重读一次快照（但仍避开 rebuildModels 的 SQL 与 nameOf 查表）。
         */
        RELAYOUT_REREAD,
    }

    /**
     * 折叠态翻转时的重建决策。
     *
     * @param applied 上次真正应用进 layout 的折叠态（`null` = 从未应用）
     * @param detected 刚扫到的折叠态（扫文字，恒为 true/false）
     * @param cachedCount 上次完整重建时读到的会话条数（snaps.size）
     * @param currentCount 当前 inner adapter 的 `count`
     *
     * ⚠️ 三态不混同：`applied == detected` 判 NOOP 时，`null` 与任何布尔都不相等
     * （Kotlin `null == false` 为 false）⇒ `null`（从未应用）恒触发重建，不会和
     * `false`（已应用展开）混同。
     */
    fun foldAction(
        applied: Boolean?,
        detected: Boolean,
        cachedCount: Int,
        currentCount: Int,
    ): FoldAction {
        if (applied == detected) return FoldAction.NOOP
        return if (cachedCount == currentCount) FoldAction.RELAYOUT_REUSE
        else FoldAction.RELAYOUT_REREAD
    }
}
