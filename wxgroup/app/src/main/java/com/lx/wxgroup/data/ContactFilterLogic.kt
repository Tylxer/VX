package com.lx.wxgroup.data

/**
 * 「哪些 wxid 算好友」的**纯判据**（零 Android / 零 Xposed 依赖 → 可直接跑 JVM 单测）。
 *
 * 存在的意义：这批规则是靠真机取证 + 踩坑试出来的（尤其 R21 的 `@openim` 企业微信好友），
 * 改错一次就要重上一轮真机。抽成纯函数后，改判据先跑单测，真机只做最后一公里验证。
 *
 * ⚠️ 本文件**禁止 import 任何 android.* / xposed.* / 本包内带 Android 依赖的类**，
 *    否则 JVM 单测加载不了（同 feature/SnsTagLogic.kt 的约束）。
 *
 * ## 为什么按 username 形态判，而不按 rcontact.type 判
 * 真机实测：普通好友主力散在 `type=4(16233) + type=3(2405) + type=0(2344)`，
 * `@openim` 好友散在 `type=0/1/4/5/513` 五个值 —— `type` 既不能当白名单也不能当黑名单。
 * 而 username 的命名惯例（`@chatroom`/`gh_`/`@openim`…）跨版本稳定，才是可靠的判据。
 */
object ContactFilter {

    /** 分类结果，语义与 [GroupDb.WxidType] 一一对应，但零 Android 依赖 */
    enum class Kind { FRIEND, CHATROOM, OFFICIAL_ACCOUNT, UNKNOWN }

    /** 群聊 */
    private const val SUFFIX_CHATROOM = "@chatroom"

    /** 企业微信好友（进通讯录 UI 渲染的坑，见 needsRcontactSupplement） */
    private const val SUFFIX_OPENIM = "@openim"

    /** 陌生人：群成员但非好友 / 未通过验证的人，不是可分组的联系人 */
    private const val SUFFIX_STRANGER = "@stranger"

    /** 占位假用户 */
    private const val SUFFIX_FAKEUSER = "@fakeuser"

    /** 服务号 / 应用号（公众号体系） */
    private const val FRAGMENT_APP = "@app"

    /** 公众号 / 服务号 */
    private const val PREFIX_GH = "gh_"

    /** 早期注册的 wxid_ 前缀（这里原始 id） */
    private const val PREFIX_WXID = "wxid_"

    /** 微信团队 / 文件传输助手 / 系统号等，不属于可分组的联系人 */
    private val SYSTEM_IDS = setOf(
        "weixin", "filehelper", "newsapp", "fmessage", "floatbottle",
        "medianote", "qqmail", "feedsapp", "voicevoipapp", "blogapp",
        "masssendapp", "shakeapp", "lbsapp", "qqfriend", "readerapp",
        "notifymessage", "qmessage", "helper_entry", "officialaccounts",
        "notification_messages", "brandcontact", "contacthelper",
    )

    fun classify(wxid: String): Kind = when {
        wxid.isBlank() -> Kind.UNKNOWN
        wxid.endsWith(SUFFIX_CHATROOM) -> Kind.CHATROOM
        wxid.startsWith(PREFIX_GH) -> Kind.OFFICIAL_ACCOUNT
        wxid in SYSTEM_IDS -> Kind.UNKNOWN
        wxid.contains(FRAGMENT_APP) -> Kind.UNKNOWN
        wxid.endsWith(SUFFIX_STRANGER) -> Kind.UNKNOWN
        wxid.endsWith(SUFFIX_FAKEUSER) -> Kind.UNKNOWN
        // @openim（企业微信好友）/ wxid_ 前缀 / 老用户的自定义微信号 —— 都是好友
        else -> Kind.FRIEND
    }

    /** 是不是「可分组的好友」形态（含 @openim 与普通好友，排除群聊/公众号/系统号/陌生人） */
    fun isFriendShape(wxid: String): Boolean = classify(wxid) == Kind.FRIEND

    fun isOfficialAccount(wxid: String): Boolean = classify(wxid) == Kind.OFFICIAL_ACCOUNT

    fun isChatroom(wxid: String): Boolean = classify(wxid) == Kind.CHATROOM

    /**
     * 是否需要靠 rcontact 直读补进 friend_cache。
     *
     * ⛔ 只对「通讯录 UI 不渲染、但又是真好友」的形态返回 true。当前只有企业微信好友
     * `@openim` 满足（微信把它们放在独立入口，不进 AddressLiveList，所以 UI 对象图扫描
     * 永远捞不到，只能直读 rcontact 补）。普通好友（无后缀 / wxid_ 前缀）走 UI 扫描即可。
     */
    fun needsRcontactSupplement(wxid: String): Boolean = wxid.endsWith(SUFFIX_OPENIM)

    /**
     * 一条 rcontact 行的最小投影（只保留判据可能关心的字段）。
     * 零 Android 依赖的纯数据类，JVM 单测可以直接构造真机样本。
     */
    data class RcontactRow(
        val username: String,
        val nickname: String = "",
        val conRemark: String = "",
        val type: Int = 0,
        val verifyFlag: Int = 0,
        val deleteFlag: Int = 0,
        val chatroomFlag: Int = 0,
    )

    /**
     * rcontact 直读补录的判据：这条行要不要收进 friend_cache？
     *
     * ## 判据：**只看 username 形态 == `@openim`**，其余字段全部**显式忽略**。
     * 真机 8.0.48 三轮取证（`tools/_r21_openim*.py` → `_sns.txt`），每个候选信号都被证伪：
     *
     * - `type`：`@openim` 254 条散在 `0(43)/1(68)/4(139)/5(2)/513(2)`，且 `type=1` 里有 12 条
     *   带人工备注（「比亚迪王洪宇」等，备注只能在通讯录加好友流程里加 ⇒ 是好友）。
     *   ⇒ type 既不是好友白名单（type=0 只有 43 条，会漏掉 type=1 的 68 条），也不是黑名单
     *   （type=4 的 139 条无备注但无法证明它们不是好友）。**忽略**。
     * - `verifyFlag`：`@openim` 254 条**全 0** ⇒ 零判别力。**忽略**。
     * - `deleteFlag`：`@openim` 254 条**全 0** ⇒ 筛不出「已删好友」。**忽略**。
     * - `conRemark`：仅 12 条有备注。若按「有备注才算好友」筛，type=0 的 43 条（含 Lynsey）
     *   会被误删 ⇒ 验收判据「搜 Lynsey」直接失败。**忽略**（不能当必要条件）。
     * - `chatroomFlag`：`@openim` 254 条**全 0** ⇒ 不是群聊，无过滤作用。**忽略**。
     *
     * ## 为什么不用 rconversation 交叉
     * `rconversation` 命中 `@openim` 仅 **69/254**（聊过天）。双刃：会漏掉「加了但没聊过」的
     * 好友（254−69=185 条里可能有真好友，Lynsey 若没聊过就被漏 ⇒ 验收判据失败）；也可能引入
     * 「没加好友但聊过」的外部会话。两个方向都错，故**不交叉**。
     *
     * ## 代价权衡
     * 误收（把「外部会话」当好友）的代价 = 选择器里多一个名字，用户可跳过，微信也会在
     * 「加非好友进群」时拒绝；漏收的代价 = 真好友搜不到（本 bug 本身）。宁多不漏。
     */
    fun shouldSupplementFromRcontact(row: RcontactRow): Boolean =
        needsRcontactSupplement(row.username)
}
