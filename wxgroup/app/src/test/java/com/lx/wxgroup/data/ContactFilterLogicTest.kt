package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContactFilter] 的判据回归测试。
 *
 * 用例全部来自 **8.0.48 真机抓到的真实数据**（不是编的）：
 * - `rcontact` 带 `@` 的后缀分布：`@app 933`、`@chatroom 841`、`@openim 254`、
 *   `@stranger 59`、`@fakeuser 1`；
 * - `@openim` 好友样本：`25984981937809637@openim / Lynsey`、`…/奥春科技`、`…/诚信赢天下` 等。
 *
 * R21 根因是「friend_cache 里缺 @openim」，判据错一处就要重上一轮真机，所以先在这里钉死。
 * ⚠️ [WxidKind.typeOf] 已委托本类的 classify()，只做 Kind→WxidType 的平凡映射，
 * 因此本测试 = WxidKind 判据的完整覆盖（映射本身不值得单测）。
 */
class ContactFilterLogicTest {

    // ---------------------------------------------------------------- 企业微信好友（R21 核心）

    @Test
    fun openimIsFriend() {
        // 真机样本：@openim 全是真实个人好友，type 散在 0/1/4/5/513 但不影响判定
        assertEquals(ContactFilter.Kind.FRIEND, ContactFilter.classify("25984981937809637@openim"))
        assertEquals(ContactFilter.Kind.FRIEND, ContactFilter.classify("25984983464234051@openim"))
        assertTrue(ContactFilter.isFriendShape("25984981937809637@openim"))
        // 企业微信好友必须走 rcontact 直读补（UI 通讯录不渲染）
        assertTrue(ContactFilter.needsRcontactSupplement("25984981937809637@openim"))
    }

    // ---------------------------------------------------------------- 群聊 / 公众号 / 系统号

    @Test
    fun chatroomIsNotFriend() {
        assertEquals(ContactFilter.Kind.CHATROOM, ContactFilter.classify("1234567890@chatroom"))
        assertTrue(ContactFilter.isChatroom("1234567890@chatroom"))
        assertFalse(ContactFilter.isFriendShape("1234567890@chatroom"))
        assertFalse(ContactFilter.needsRcontactSupplement("1234567890@chatroom"))
    }

    @Test
    fun officialAccountIsNotFriend() {
        // gh_ 前缀：订阅号 → OFFICIAL_ACCOUNT
        assertEquals(ContactFilter.Kind.OFFICIAL_ACCOUNT, ContactFilter.classify("gh_1234567890ab"))
        assertTrue(ContactFilter.isOfficialAccount("gh_1234567890ab"))
        assertFalse(ContactFilter.isFriendShape("gh_1234567890ab"))
        // @app 后缀：服务号/应用号 → UNKNOWN（与原 WxidKind 行为一致，同样不算好友）
        assertEquals(ContactFilter.Kind.UNKNOWN, ContactFilter.classify("wx123@app"))
        assertFalse(ContactFilter.isFriendShape("wx123@app"))
    }

    @Test
    fun systemIdsAreNotFriend() {
        for (id in listOf("weixin", "filehelper", "newsapp", "officialaccounts", "qmessage")) {
            assertEquals("$id 应是 UNKNOWN", ContactFilter.Kind.UNKNOWN, ContactFilter.classify(id))
            assertFalse(ContactFilter.isFriendShape(id))
        }
    }

    // ---------------------------------------------------------------- 陌生人 / 假用户

    @Test
    fun strangerAndFakeuserAreNotFriend() {
        // 陌生人 = 群成员但非好友 / 未通过验证的人，不分组
        assertEquals(ContactFilter.Kind.UNKNOWN, ContactFilter.classify("12345@stranger"))
        assertFalse(ContactFilter.isFriendShape("12345@stranger"))
        assertFalse(ContactFilter.needsRcontactSupplement("12345@stranger"))
        // 假用户 = 占位
        assertEquals(ContactFilter.Kind.UNKNOWN, ContactFilter.classify("12345@fakeuser"))
        assertFalse(ContactFilter.isFriendShape("12345@fakeuser"))
    }

    // ---------------------------------------------------------------- 普通好友

    @Test
    fun plainWxidIsFriend() {
        // 早期注册的 wxid_ 前缀
        assertEquals(ContactFilter.Kind.FRIEND, ContactFilter.classify("wxid_abcdef123"))
        assertTrue(ContactFilter.isFriendShape("wxid_abcdef123"))
        // 老用户自定义微信号（无前缀、无后缀）
        assertEquals(ContactFilter.Kind.FRIEND, ContactFilter.classify("zhangsan1990"))
        assertTrue(ContactFilter.isFriendShape("zhangsan1990"))
        // 普通好友不需要 rcontact 直读补（UI 扫描就够）
        assertFalse(ContactFilter.needsRcontactSupplement("wxid_abcdef123"))
        assertFalse(ContactFilter.needsRcontactSupplement("zhangsan1990"))
    }

    @Test
    fun blankIsUnknown() {
        assertEquals(ContactFilter.Kind.UNKNOWN, ContactFilter.classify(""))
        assertEquals(ContactFilter.Kind.UNKNOWN, ContactFilter.classify("   "))
        assertFalse(ContactFilter.isFriendShape(""))
    }

    // ---------------------------------------------------------------- 边界：形态优先级

    /**
     * 形态判据的优先级必须稳定：一个 wxid 只可能命中一条分支。
     * 重点防「@app 片段误伤」：wxid 里出现 "@app" 子串但其实是别的形态时，不能被误判成好友。
     */
    @Test
    fun appFragmentDoesNotLeak() {
        // 含 @app 片段的任何 wxid 都归 UNKNOWN（原 WxidKind 的 contains("@app") 语义）
        assertEquals(ContactFilter.Kind.UNKNOWN, ContactFilter.classify("foo@appbar"))
        assertFalse(ContactFilter.isFriendShape("foo@appbar"))
    }

    /** @openim 不能被 @app 片段误伤（"@openim" 不含 "@app" 子串，两条分支天然互斥） */
    @Test
    fun openimNotConfusedWithApp() {
        assertEquals(ContactFilter.Kind.FRIEND, ContactFilter.classify("x@openim"))
        assertTrue(ContactFilter.isFriendShape("x@openim"))
    }

    // ---------------------------------------------------------------- rcontact 直读补录判据（数据驱动）

    private fun row(
        username: String,
        type: Int = 0,
        conRemark: String = "",
        verifyFlag: Int = 0,
        deleteFlag: Int = 0,
        chatroomFlag: Int = 0,
    ) = ContactFilter.RcontactRow(
        username = username,
        conRemark = conRemark,
        type = type,
        verifyFlag = verifyFlag,
        deleteFlag = deleteFlag,
        chatroomFlag = chatroomFlag,
    )

    /**
     * 真机分布：type=0:43(0备注) / type=1:68(12备注) / type=4:139(0备注) / type=5:2 / type=513:2。
     * 判据只看 username 形态，type 是噪声 ⇒ 五个 type **全收**。
     */
    @Test
    fun supplementAcceptsAllOpenimTypes() {
        for (type in listOf(0, 1, 4, 5, 513)) {
            assertTrue(
                "type=$type 的 @openim 应被补录",
                ContactFilter.shouldSupplementFromRcontact(row("25984981937809637@openim", type = type))
            )
        }
    }

    /**
     * 硬回归：12 条带备注的 type=1 **必须全收**（备注只能在通讯录加好友流程里加 ⇒ 是好友）。
     * 这里取 3 条真机样本作代表（完整 12 条见 docs/_r21_friend_src.md §3.3 Q1）。
     */
    @Test
    fun supplementKeepsType1WithRemark() {
        assertTrue(
            ContactFilter.shouldSupplementFromRcontact(
                row("25984983447461634@openim", type = 1, conRemark = "温州桁斯蒙总")
            )
        )
        assertTrue(
            ContactFilter.shouldSupplementFromRcontact(
                row("25984981764931153@openim", type = 1, conRemark = "比亚迪王洪宇")
            )
        )
        assertTrue(
            ContactFilter.shouldSupplementFromRcontact(
                row("25984984056701487@openim", type = 1, conRemark = "宝理工程塑料的Fuji Sakai")
            )
        )
    }

    /** type=4 无备注（139 条）也收：type 不是好友判据，排除它们只会误删好友，收益只是少一个名字 */
    @Test
    fun supplementKeepsType4WithoutRemark() {
        assertTrue(ContactFilter.shouldSupplementFromRcontact(row("25984981937809637@openim", type = 4)))
    }

    /** Lynsey 样本：type=0、无备注、无会话 —— 也必须收（验收判据 #2） */
    @Test
    fun supplementKeepsLynsey() {
        assertTrue(ContactFilter.shouldSupplementFromRcontact(row("25984981937809637@openim", type = 0)))
    }

    /** verifyFlag/deleteFlag/chatroomFlag 全 0 的现状下判据不变；非 0 也不影响（这些字段被忽略） */
    @Test
    fun supplementIgnoresNoiseFlags() {
        val base = row("25984981937809637@openim", type = 4)
        assertTrue(ContactFilter.shouldSupplementFromRcontact(base.copy(verifyFlag = 1, deleteFlag = 1, chatroomFlag = 1)))
        // 反例：形态不对，即使 flags 全 0 也不收
        assertFalse(ContactFilter.shouldSupplementFromRcontact(row("123@chatroom")))
        assertFalse(ContactFilter.shouldSupplementFromRcontact(row("123@stranger")))
        assertFalse(ContactFilter.shouldSupplementFromRcontact(row("gh_abcdef")))
        assertFalse(ContactFilter.shouldSupplementFromRcontact(row("wxid_abcdef")))
    }
}
