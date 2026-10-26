package com.lx.wxgroup.feature

import com.lx.wxgroup.core.ChatroomSource
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.util.LogX

/**
 * K3 选择器的**四个 Tab 统一构建器**（R24）。
 *
 * ## 为什么单独抽出来
 *
 * `MainPage` 与 `MassSendPage` 各有一份「把分组 / 标签 / 好友拼成 sections」的代码，
 * R24 又要加「群聊」Tab。如果两边各写一遍，四类数据源的**空值处理与排序**必然漂移
 * （一边过滤了空名、另一边没过滤），而这种漂移只会在真机上表现为「某个入口少一群人」，
 * 极难发现。
 *
 * 所以四个 Tab 的构建**只有这一份实现**，两个调用方只决定「传不传」。
 *
 * ## 空 Tab 一律不产出
 *
 * 与既有口径一致：`PickerPage` 的段为空就隐藏、`LabelSource.ready()` 为 false 就不显示标签段、
 * `ForwardPicker` 的空 Tab 直接不出现。理由：**宁可少一个 Tab，不给用户一个点进去是空白的页**。
 *
 * ⇒ 极端情况下（好友缓存全空、没分组、没标签、没群聊）会返回空列表，
 * 调用方必须处理（既有代码是 toast「好友缓存为空，先去通讯录滚一遍再回来」）。
 */
object PickerTabs {

    /** Tab 名：与用户原话「好友 / 群聊 / 我的分组 / 微信标签」逐字一致 */
    const val NAME_FRIEND = "好友"
    const val NAME_ROOM = "群聊"
    const val NAME_GROUP = "我的分组"
    const val NAME_LABEL = "微信标签"

    /**
     * 构建全部四个 Tab（按固定顺序：好友 → 群聊 → 我的分组 → 微信标签）。
     *
     * 顺序是**刻意的**：好友/群聊是「具体的人」，日常用得最多，排前面；
     * 分组/标签是「批量索引」，属于进阶用法。用户要勾具体的人时不用先跨过两个批量 Tab。
     *
     * @param groups  是否产出「我的分组」Tab。传 false 的场景见 [allowGroupTab] 的 KDoc
     * @param labels  是否产出「微信标签」Tab，同上
     */
    fun all(
        db: GroupDb?,
        groups: Boolean = true,
        labels: Boolean = true,
    ): List<PickerPage.Tab> {
        // F1：首次打开任何名单功能时补跑群聊全表扫。runIfNeeded() 立即返回（起 daemon
        // 线程、自带 loading CAS 节流），不会阻塞 UI；扫完经 ChatroomSource 监听器回调
        // PickerPage 原地刷新，把「群聊」Tab 补出来。
        ChatroomSource.runIfNeeded()

        val out = ArrayList<PickerPage.Tab>(4)
        friendTab(db)?.let { out += it }
        roomTab(db)?.let { out += it }
        if (groups) groupTab(db)?.let { out += it }
        if (labels) labelTab()?.let { out += it }
        return out
    }

    /**
     * 「我的分组」「微信标签」能不能作为 Tab 出现。
     *
     * ⚠️ R24 起用户要求**四个 Tab 对所有名单场景开放**，所以生产调用方目前**总是**
     * 传 true（默认值）。本函数留作显式开关，服务于两类将来场景：
     * - 若某处新增了「只认裸 wxid」的消费方，它必须传 false；
     * - 排查「名单里出现 g:/t: 但下游不认识」时，可临时关掉快速定位。
     *
     * 下游消费方若**不支持**前缀展开，名单里存了 `g:3` 就是静默失效
     * （拿 talker 去比 `g:3` 永不命中）。当前三个消费方（群发 / 免打扰 / 朋友圈导出）
     * 是否都支持，见 [ListResolver] 与各消费方的接入代码。
     */
    fun allowGroupTab(): Boolean = true

    // ------------------------------------------------------------------ 四个 Tab

    /** 好友：只含 `FRIEND`，排除公众号 / 群聊 / 系统号 */
    private fun friendTab(db: GroupDb?): PickerPage.Tab? {
        val friends = runCatching { db?.allRealFriends() }.getOrNull().orEmpty()
        if (friends.isEmpty()) return null
        val items = friends.map { PickerPage.Item(it.wxid, it.displayName, it.wxid) }
        return PickerPage.Tab(NAME_FRIEND, listOf(PickerPage.Section(NAME_FRIEND, items)))
    }

    /**
     * 群聊：`wxid_type = CHATROOM`。
     *
     * ⚠️ 这些行由 [com.lx.wxgroup.core.ChatroomSource] 扫 `rcontact` 补进来，
     * **不是** `ContactSync` 扫通讯录得到的（通讯录 tab 里没有群聊）。
     * 所以这里为空时先看 `ChatroomSource.stat()`，别怀疑「用户没群」。
     *
     * 副标题给「群聊」二字而不是 wxid：群 wxid 是 `xxx@chatroom` 形式，对用户无信息量，
     * 而搜索时 [PickerPage.Adapter.filter] 会匹配 subtitle，写 wxid 反而让搜 `chatroom`
     * 命中全部群 —— 那不是用户要的。
     */
    private fun roomTab(db: GroupDb?): PickerPage.Tab? {
        val rooms = runCatching { db?.allChatrooms() }.getOrNull().orEmpty()
        if (rooms.isEmpty()) return null
        val items = rooms.map {
            // ⚠️ 必须传「原始群名」而不是 `it.displayName`（R24 复核 S1）：
            // `Friend.displayName` 在 remark/nickname 都空时**已经回落到 wxid**，
            // 那是个非空串，`displayNameOf` 会原样返回 ⇒ 无名群显示成
            // `19501172869@chatroom`，剥后缀的兜底永远走不到。
            // 走 GroupNameLogic.pick 才能在「两名皆空」时拿到 null，进而退成群号。
            PickerPage.Item(
                id = it.wxid,
                title = GroupNameLogic.displayNameOf(
                    it.wxid,
                    GroupNameLogic.pick(it.remark, it.nickname),
                ),
                subtitle = "群聊",
            )
        }
        LogX.i("PICKER rooms=${items.size}")
        return PickerPage.Tab(NAME_ROOM, listOf(PickerPage.Section(NAME_ROOM, items)))
    }

    /** 我的分组：id 带 `g:` 前缀 */
    private fun groupTab(db: GroupDb?): PickerPage.Tab? {
        val groups = runCatching { db?.listGroups() }.getOrNull().orEmpty()
        if (groups.isEmpty()) return null
        val items = groups.map {
            PickerPage.Item(
                id = "${ListResolver.PREFIX_GROUP}${it.id}",
                title = it.name,
                subtitle = "分组 · ${runCatching { db?.countMembers(it.id) }.getOrNull() ?: 0} 人",
            )
        }
        return PickerPage.Tab(NAME_GROUP, listOf(PickerPage.Section(NAME_GROUP, items)))
    }

    /** 微信标签：id 带 `t:` 前缀；数据来自 [LabelSource] 的只读缓存 */
    private fun labelTab(): PickerPage.Tab? {
        if (!LabelSource.ready()) return null
        val items = runCatching { LabelSource.allLabels() }.getOrNull().orEmpty()
            .filter { !it.name.isNullOrBlank() }
            .map {
                PickerPage.Item(
                    id = "${ListResolver.PREFIX_LABEL}${it.id}",
                    title = it.name!!,
                    subtitle = "微信标签 · ${it.memberCount} 人",
                )
            }
        if (items.isEmpty()) return null
        return PickerPage.Tab(NAME_LABEL, listOf(PickerPage.Section(NAME_LABEL, items)))
    }
}
