package com.lx.wxgroup.data

/**
 * 好友快照。
 *
 * 这是「微信 rcontact 表的一条记录」在本地的镜像，只做只读缓存：
 * 昵称/备注会被用户改动，所以每次同步整体覆盖，不做增量判断。
 *
 * 主键是 wxid（Contact.username）。昵称会重名、备注会被改，都不可做主键。
 */
data class Friend(
    val wxid: String,
    val nickname: String = "",
    val remark: String = "",
    val alias: String = "",
    val type: Int = GroupDb.WxidType.FRIEND,
) {
    /** 展示优先级：备注 > 昵称 > 微信号 > wxid。备注为空说明用户没给他设备注。 */
    val displayName: String
        get() = remark.takeIf { it.isNotBlank() }
            ?: nickname.takeIf { it.isNotBlank() }
            ?: alias.takeIf { it.isNotBlank() }
            ?: wxid

    /** 是否值得纳入分组管理：公众号/群聊/系统号都应该被排除 */
    val isRealFriend: Boolean
        get() = type == GroupDb.WxidType.FRIEND
}

/**
 * wxid 的形态决定了它是什么东西 —— 规则收敛到 [ContactFilter]（纯判据，可单测）。
 * 这里只做「Kind → GroupDb.WxidType 的 Int」映射，判据本身不进这个带 Android 依赖的文件。
 */
object WxidKind {
    fun typeOf(wxid: String): Int = when (ContactFilter.classify(wxid)) {
        ContactFilter.Kind.CHATROOM -> GroupDb.WxidType.CHATROOM
        ContactFilter.Kind.OFFICIAL_ACCOUNT -> GroupDb.WxidType.OFFICIAL_ACCOUNT
        ContactFilter.Kind.FRIEND -> GroupDb.WxidType.FRIEND
        ContactFilter.Kind.UNKNOWN -> GroupDb.WxidType.UNKNOWN
    }
}

/** 一次同步的统计结果，用来验证「wxid 唯一且稳定」这个核心假设 */
data class SyncStats(
    val scanned: Int,      // 对象图里扫到的 Contact 实例数
    val unique: Int,       // 按 wxid 去重后的数量
    val blank: Int,        // wxid 为空被丢弃的数量
    val friend: Int,       // 真好友
    val official: Int,     // 公众号
    val chatroom: Int,     // 群聊
    val unknown: Int,      // 系统号等
    val elapsedMs: Long,
)
