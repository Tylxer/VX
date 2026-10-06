package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份 JSON 编解码的兼容性测试。
 *
 * 只测 [BackupCodec] —— 它刻意不依赖任何 Android API，所以能在 JVM 上跑。
 * 覆盖的是「换手机 / 升级模块 / 拿到旧文件」这几种真实场景，
 * 这些错了在手机上很难复现，但用户会直接丢数据。
 */
class BackupCodecTest {

    private fun samplePayload(): BackupPayload = BackupPayload(
        schema = BackupFormat.SCHEMA,
        format = BackupFormat.TAG,
        module = BackupFormat.MODULE,
        version = BackupFormat.SCHEMA,
        exported_at = com.google.gson.JsonPrimitive(BackupCodec.isoTime(1_763_000_000_000L)),
        kv = mapOf(
            "chat.dnd_enabled" to "true",
            "moments.refresh_interval_h" to "3",
        ),
        groups = listOf(
            BackupGroup(
                name = "家人", color = 1, sort_order = 0, pinned = 1,
                members = listOf(
                    BackupMember(wxid = "wxid_a", display_name = "妈妈", wxid_type = 0),
                    BackupMember(wxid = "wxid_b", display_name = "爸爸", wxid_type = 0),
                ),
            ),
            BackupGroup(
                name = "同事", color = 2, sort_order = 1, pinned = 0,
                members = listOf(BackupMember(wxid = "wxid_c", display_name = "小王")),
            ),
        ),
    )

    // ------------------------------------------------------------ 当前格式

    @Test
    fun 当前格式往返不丢数据() {
        val json = BackupCodec.toJson(samplePayload())
        val snap = BackupCodec.parse(json).snapshot

        assertNotNull("当前版本的备份必须能解析出来", snap)
        assertEquals(BackupFormat.SCHEMA, snap!!.schema)
        assertEquals(2, snap.groupCount)
        assertEquals(3, snap.memberCount)
        assertEquals("true", snap.settings["chat.dnd_enabled"])
        assertEquals("3", snap.settings["moments.refresh_interval_h"])
        assertTrue("带配置段才算完整备份", snap.hasSettings)
        assertEquals("家人", snap.groups[0].name)
        assertTrue("置顶标记要能还原", snap.groups[0].pinned)
        assertEquals("妈妈", snap.groups[0].members[0].displayName)
    }

    @Test
    fun 导出时间既能读字符串也能读毫秒() {
        val iso = BackupCodec.parse(BackupCodec.toJson(samplePayload())).snapshot!!.exportedAt
        assertTrue("ISO 时间要能还原成毫秒", iso > 1_700_000_000_000L)

        val legacy = """
            {"format":"wxgroup-backup","version":1,"exported_at":1700000000000,
             "groups":[{"name":"旧组","members":[{"wxid":"wxid_z"}]}]}
        """.trimIndent()
        assertEquals(1_700_000_000_000L, BackupCodec.parse(legacy).snapshot!!.exportedAt)
    }

    // -------------------------------------------------------- 旧版本（v0）

    @Test
    fun 旧备份缺schema时只恢复分组配置不动() {
        // v3 时代导出的文件：没有 schema、没有 kv，只有分组
        val legacy = """
            {"format":"wxgroup-backup","version":1,"exported_at":1700000000000,
             "groups":[{"name":"家人","color":1,"sort_order":0,
                        "members":[{"wxid":"wxid_a","display_name":"妈妈","wxid_type":0}]}]}
        """.trimIndent()
        val snap = BackupCodec.parse(legacy).snapshot

        assertNotNull("旧文件也该能读", snap)
        assertEquals(0, snap!!.schema)
        assertEquals(1, snap.groupCount)
        assertEquals(1, snap.memberCount)
        assertTrue("v0 没有配置段", snap.settings.isEmpty())
        assertEquals("v0 一律不动配置", false, snap.hasSettings)
    }

    @Test
    fun 旧备份里带kv也当作没带() {
        // 万一某个中间版本写了 kv 但没写 schema，仍按 v0 处理，配置不动
        val legacy = """
            {"format":"wxgroup-backup","kv":{"chat.dnd_enabled":"true"},
             "groups":[{"name":"家人","members":[{"wxid":"wxid_a"}]}]}
        """.trimIndent()
        val snap = BackupCodec.parse(legacy).snapshot!!

        assertEquals(0, snap.schema)
        assertTrue(snap.settings.isEmpty())
    }

    // ------------------------------------------------------------ 高版本

    @Test
    fun 高版本备份尽力解析未知字段忽略() {
        val future = """
            {"schema":99,"module":"wxgroup","module_version":"9.9",
             "future_block":{"whatever":[1,2,3]},
             "kv":{"chat.dnd_enabled":"true","some_new_key":"x"},
             "groups":[{"name":"家人","pinned":1,"new_field":123,
                        "members":[{"wxid":"wxid_a","display_name":"妈妈","extra":true}]}]}
        """.trimIndent()
        val res = BackupCodec.parse(future)

        assertNotNull("高版本不拒绝（决策 #10：尽力解析）", res.snapshot)
        val snap = res.snapshot!!
        assertEquals(99, snap.schema)
        assertEquals("家人", snap.groups[0].name)
        assertEquals("wxid_a", snap.groups[0].members[0].wxid)
        assertEquals("true", snap.settings["chat.dnd_enabled"])
    }

    // -------------------------------------------------------- 坏文件 / 异常

    @Test
    fun 损坏文件给出原因不返回半成品() {
        val res = BackupCodec.parse("{ this is not json ]")
        assertNull("损坏文件不能解析出快照", res.snapshot)
        assertNotNull("要给 UI 一个能弹窗的原因", res.error)
    }

    @Test
    fun 不是本模块的文件要认出来() {
        val res = BackupCodec.parse("""{"app":"other","groups":[]}""")
        assertNull(res.snapshot)
        assertEquals("这不是本模块的备份文件", res.error)
    }

    @Test
    fun 空文件与空分组都要拦住() {
        assertNotNull(BackupCodec.parse("").error)
        val noGroups = """{"schema":1,"module":"wxgroup","kv":{},"groups":[]}"""
        // 空分组列表是合法的（用户一个组都没有），只有字段缺失才算坏
        val res = BackupCodec.parse(noGroups)
        assertNotNull(res.snapshot)
        assertEquals(0, res.snapshot!!.groupCount)

        val missing = """{"schema":1,"module":"wxgroup"}"""
        assertNotNull(BackupCodec.parse(missing).error)
    }

    // ---------------------------------------------------------- 字段兼容

    @Test
    fun 成员字段名wxid与username都认() {
        val json = """
            {"schema":1,"module":"wxgroup",
             "groups":[{"name":"家人","members":[
                {"username":"wxid_a","display_name":"妈妈","type":2},
                {"wxid":"wxid_b"}]}]}
        """.trimIndent()
        val snap = BackupCodec.parse(json).snapshot!!

        assertEquals("wxid_a", snap.groups[0].members[0].wxid)
        assertEquals(2, snap.groups[0].members[0].wxidType)
        assertEquals("妈妈", snap.groups[0].members[0].displayName)
        assertEquals("没写名字就退回 wxid", "wxid_b", snap.groups[0].members[1].displayName)
    }

    @Test
    fun 脏数据被清洗而不是崩掉() {
        val json = """
            {"schema":1,"module":"wxgroup",
             "kv":{"":"ignored","chat.dnd_enabled":null,"moments.refresh_interval_h":"3"},
             "groups":[
                {"name":"  ","members":[{"wxid":"wxid_x"}]},
                {"name":"有效组","members":[{"wxid":"  "},{"wxid":"wxid_y"}]}]}
        """.trimIndent()
        val snap = BackupCodec.parse(json).snapshot!!

        assertEquals("空名分组被丢掉", 1, snap.groupCount)
        assertEquals("有效组", snap.groups[0].name)
        assertEquals("空 wxid 成员被丢掉", 1, snap.groups[0].members.size)
        assertEquals("空 key / null 值被丢掉", 1, snap.settings.size)
    }
}
