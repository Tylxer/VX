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
 * wxid 的形态决定了它是什么东西 —— 这套规则来自微信的命名惯例，跨版本稳定。
 */
object WxidKind {
    /** 普通好友：早期注册的 wxid_ 前缀，或后来改成可自定义微信号（这里是原始 id） */
    private const val PREFIX_WXID = "wxid_"
    /** 公众号 / 服务号 */
    private const val PREFIX_GH = "gh_"
    /** 群聊 */
    private const val SUFFIX_CHATROOM = "@chatroom"
    /** 微信团队 / 文件传输助手 / 系统号等，不属于可分组的联系人 */
    private val SYSTEM_IDS = setOf(
        "weixin", "filehelper", "newsapp", "fmessage", "floatbottle",
        "medianote", "qqmail", "feedsapp", "voicevoipapp", "blogapp",
        "masssendapp", "shakeapp", "lbsapp", "qqfriend", "readerapp",
        "notifymessage", "qmessage", "helper_entry", "officialaccounts",
        "notification_messages", "brandcontact", "contacthelper",
    )

    fun typeOf(wxid: String): Int = when {
        wxid.isBlank() -> GroupDb.WxidType.UNKNOWN
        wxid.endsWith(SUFFIX_CHATROOM) -> GroupDb.WxidType.CHATROOM
        wxid.startsWith(PREFIX_GH) -> GroupDb.WxidType.OFFICIAL_ACCOUNT
        wxid in SYSTEM_IDS -> GroupDb.WxidType.UNKNOWN
        wxid.contains("@app") -> GroupDb.WxidType.UNKNOWN
        wxid.startsWith(PREFIX_WXID) -> GroupDb.WxidType.FRIEND
        // 老用户的原始 id 就是自定义微信号，没有 wxid_ 前缀
        else -> GroupDb.WxidType.FRIEND
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
