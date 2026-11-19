package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R73 · 转发选人器好友昵称不更新 —— 回归锁（零 Android / SQLite / Xposed 依赖）。
 *
 * 真机铁证：好友 `wxid_xlrbelf6hv0w22` 的 rcontact 已是 `nickname=路过人间, conRemark=, alias=`，
 * 但重启微信、打开对话框后转发选人器仍显示旧昵称 `verity` ⇒ 纯我方 friend_cache 缓存缺口。
 *
 * 修法：在 [ContactSqlSource.refreshFriendNames] 直读 rcontact，按本地 wxid 取交集、
 * 保留每行原 type，用 upsertFriends(CONFLICT_REPLACE) 覆盖；[ForwardPicker.loadTabs]
 * 在读 `db.allFriends()` 之前同步调用它，保证本次打开即新昵称。
 *
 * 验证分两层（与 R73LatestMsgFixTest 同款模式）：
 * 1. 纯逻辑等价复刻：锁住「新 nickname 覆盖旧值」「type 保留」「只保留交集」
 *    「displayName 优先级不变」四种语义，以及 R73-t 节流的「冷却跳过 / 过期放行 / 首次放行 /
 *    失败重试」四种语义，不依赖 Android / SQLite。
 * 2. 生产源码字符串断言：直接读 ContactSqlSource.kt / ForwardPicker.kt，锁住调用点、交集过滤、
 *    type 保留、节流外壳、红线不破。
 *
 * ⚠️ 测试有效性分层（同复核 docs/_r73_picker_name_review.md §6.2 的结论）：逻辑复刻层是手写等价副本，
 * 不引用生产代码；真正随生产变异变红的是源码断言层。逻辑复刻用例只证明「算法思路自洽」。
 *
 * 变异有效性（本代理未跑 gradle，由主会话统一执行；以下为预期）：
 * - M1 把 `type = type` 改回 `WxidKind.typeOf(...)` 固定推导
 *   ⇒ [typeKeptFromLocalCache]（逻辑复刻）+ [refreshMethodPreservesType]（源码断言）变红；
 * - M2 去掉 `typeByWxid[wxid] ?: continue`（不过交集，把 rcontact 全表写进缓存）
 *   ⇒ [onlyIntersectionIsWritten] + [refreshMethodFiltersByLocalWxids] 变红；
 * - M3 把 `nickname = textAt(ciNick)` 改回沿用本地旧 nickname
 *   ⇒ [newNicknameOverridesOld] 变红（源码层 [refreshMethodReadsRcontactColumns] 也会因列读取消失而变红）；
 * - M4 删掉 ForwardPicker 的 `ContactSqlSource.refreshFriendNames()` 调用
 *   ⇒ [pickerCallsRefreshBeforeReadingCache] 变红；
 * - M5 把该调用挪到 `db.allFriends()` 之后（本次打开仍显示旧值）
 *   ⇒ [pickerCallsRefreshBeforeReadingCache] 的顺序断言变红；
 * - M6 删掉 `ChatroomSource.runIfNeeded()`（破坏既有群聊补跑）
 *   ⇒ [pickerKeepsChatroomSupplement] 变红。
 * - M7 删掉开头冷却判断（`if (last > 0L && age < COOLDOWN) ... return`）
 *   ⇒ [refreshMethodHasCooldownThrottle] 变红（body 里不再有冷却常量引用 / `cooling down age=` 日志）；
 * - M8 把「仅 written>0 更新时间戳」改成无条件更新（含失败路径）
 *   ⇒ [refreshMethodHasCooldownThrottle] 变红（`"if (written > 0) lastFriendNamesRefreshAt"` 消失），
 *      [throttleAllowsRetryAfterFailedRefresh]（逻辑复刻）也变红（副本同步改成无条件更新时）。
 */
class R73PickerNameRefreshTest {

    // ---------------------------------------------------------- 1) 纯逻辑等价复刻

    /** 生产 Friend 的等价复刻（displayName 优先级：备注 > 昵称 > 微信号 > wxid） */
    private data class StubFriend(
        val wxid: String,
        val nickname: String = "",
        val remark: String = "",
        val alias: String = "",
        val type: Int = 0,
    ) {
        val displayName: String
            get() = remark.takeIf { it.isNotBlank() }
                ?: nickname.takeIf { it.isNotBlank() }
                ?: alias.takeIf { it.isNotBlank() }
                ?: wxid
    }

    /** rcontact 一行（只读 nickname/conRemark/alias + 主键 username）的等价复刻 */
    private data class StubRow(
        val username: String,
        val nickname: String,
        val conRemark: String,
        val alias: String,
    )

    /**
     * 生产 refreshFriendNames 的等价复刻：
     * 本地 wxid→type 建映射，rcontact 行按交集过滤，只覆盖昵称三列，type 取本地原值，
     * 最后用 CONFLICT_REPLACE 语义按 wxid 写回（同 wxid 新对象整行替换旧对象）。
     */
    private fun mergeRefresh(
        cache: List<StubFriend>,
        rows: List<StubRow>,
        replaceInto: MutableMap<String, StubFriend> =
            LinkedHashMap(cache.associateBy { it.wxid }),
    ): Map<String, StubFriend> {
        val typeByWxid = cache.associateBy({ it.wxid }, { it.type })
        for (r in rows) {
            val type = typeByWxid[r.username] ?: continue // 交集：本地没有的行一律不写
            replaceInto[r.username] = StubFriend(
                wxid = r.username,
                nickname = r.nickname,
                remark = r.conRemark,
                alias = r.alias,
                type = type, // 保留本地原 type，不串改
            )
        }
        return replaceInto
    }

    @Test
    fun newNicknameOverridesOld() {
        // 真机场景：本地缓存还是 verity，rcontact 已是 路过人间，备注/微信号为空
        val before = StubFriend("wxid_xlrbelf6hv0w22", nickname = "verity", type = 0)
        val row = StubRow("wxid_xlrbelf6hv0w22", nickname = "路过人间", conRemark = "", alias = "")
        val after = mergeRefresh(listOf(before), listOf(row))["wxid_xlrbelf6hv0w22"]!!

        assertEquals("verity", before.displayName)
        assertEquals("新 nickname 必须覆盖旧值", "路过人间", after.displayName)
        assertEquals("nickname 列应被新值覆盖", "路过人间", after.nickname)
    }

    @Test
    fun typeKeptFromLocalCache() {
        // 群聊行：rcontact 里也有 username/nickname，但本地 type=CHATROOM(2) 不能被改成 FRIEND(0)
        val chatroom = StubFriend("1234567890@chatroom", nickname = "旧群名", type = 2)
        val row = StubRow("1234567890@chatroom", nickname = "新群名", conRemark = "", alias = "")
        val after = mergeRefresh(listOf(chatroom), listOf(row))["1234567890@chatroom"]!!

        assertEquals("type 必须保留本地原值(CHATROOM)", 2, after.type)
        assertEquals("rcontact 的新 nickname 仍要刷新", "新群名", after.nickname)
    }

    @Test
    fun onlyIntersectionIsWritten() {
        val local = listOf(StubFriend("wxid_known", nickname = "老李", type = 0))
        val rows = listOf(
            StubRow("wxid_known", nickname = "老李新", conRemark = "", alias = ""), // 交集，更新
            StubRow("gh_official@app", nickname = "公众号", conRemark = "", alias = ""), // 非好友，丢弃
            StubRow("9999@chatroom", nickname = "陌生群", conRemark = "", alias = ""), // 非好友，丢弃
        )
        val after = mergeRefresh(local, rows)

        assertEquals("只写交集行，不新增任何非好友", 1, after.size)
        assertEquals("老李新", after["wxid_known"]!!.nickname)
        assertNull("公众号不得进缓存", after["gh_official@app"])
        assertNull("非本地群聊不得进缓存", after["9999@chatroom"])
    }

    @Test
    fun displayNamePriorityUnchanged() {
        // rcontact conRemark 非空时，展示仍走「备注 > 昵称」，本次只换值不改优先级
        val before = StubFriend("wxid_b", nickname = "小名", remark = "备注A")
        val row = StubRow("wxid_b", nickname = "小名新", conRemark = "备注B", alias = "wxid_b_alias")
        val after = mergeRefresh(listOf(before), listOf(row))["wxid_b"]!!

        assertEquals("备注优先于昵称，优先级未改", "备注B", after.displayName)
    }

    @Test
    fun upsertReplacesWholeRow() {
        // CONFLICT_REPLACE 语义：同 wxid 的新 Friend 整行替换，旧对象不再留在结果里
        val before = StubFriend("wxid_c", nickname = "旧", remark = "旧备注", alias = "旧号", type = 0)
        val row = StubRow("wxid_c", nickname = "新", conRemark = "", alias = "新号")
        val after = mergeRefresh(listOf(before), listOf(row))["wxid_c"]!!

        assertEquals("新", after.nickname)
        assertEquals("旧备注也应被 conRemark 覆盖", "", after.remark)
        assertEquals("新号", after.alias)
        assertFalse("结果里不应残留旧 nickname", after.nickname == "旧")
    }

    // ---------------------------------------------------------- 1b) R73-t 节流逻辑复刻

    /**
     * 生产「进程级冷却」的等价复刻（不引用 Android / 生产代码，纯 JVM）。
     * - [now] 模拟 `SystemClock.elapsedRealtime()`，由用例手动推进；
     * - [lastSuccessAt] 模拟 `@Volatile lastFriendNamesRefreshAt`，只在 written>0 时更新；
     * - [call] 模拟一次 `refreshFriendNames()`：冷却期内直接跳过（不跑刷新），否则执行一次，
     *   若 written>0 推进时间戳。
     */
    private class ThrottleReplica(private val cooldown: Long = 30_000L) {
        var now: Long = 0L
        var lastSuccessAt: Long = 0L
        var refreshRuns: Int = 0
            private set

        private fun skipped(): Boolean = lastSuccessAt > 0L && now - lastSuccessAt < cooldown

        /** @return true = 本次真正执行了刷新；false = 冷却跳过 */
        fun call(written: Int): Boolean {
            if (skipped()) return false
            refreshRuns++
            if (written > 0) lastSuccessAt = now
            return true
        }
    }

    @Test
    fun throttleSkipsWithinCooldown() {
        val s = ThrottleReplica()
        s.now = 1_000L
        assertTrue("首次调用必须执行刷新", s.call(written = 100))
        assertEquals(1_000L, s.lastSuccessAt)

        s.now = 1_000L + 5_000L // 刚刷过 5s，<30s
        assertFalse("冷却期内应跳过", s.call(written = 100))
        assertEquals("跳过不应真的跑刷新", 1, s.refreshRuns)
        assertEquals("跳过不应推进时间戳", 1_000L, s.lastSuccessAt)
    }

    @Test
    fun throttleAllowsAfterCooldown() {
        val s = ThrottleReplica()
        s.now = 1_000L
        s.call(written = 100)
        s.now = 1_000L + 30_001L // >cooldown
        assertTrue("age 超过冷却期应放行", s.call(written = 100))
        assertEquals(2, s.refreshRuns)
        assertEquals("放行且成功后应推进时间戳", 31_001L, s.lastSuccessAt)
    }

    @Test
    fun throttleAllowsOnFirstOpen() {
        val s = ThrottleReplica()
        s.now = 12_345L
        assertTrue("首次打开（时间戳=0）必须放行同步刷新", s.call(written = 100))
        assertEquals("首次应真跑一次", 1, s.refreshRuns)
        assertEquals(12_345L, s.lastSuccessAt)
    }

    @Test
    fun throttleAllowsRetryAfterFailedRefresh() {
        val s = ThrottleReplica()
        s.now = 1_000L
        assertTrue("首次成功刷新", s.call(written = 100))
        assertEquals(1_000L, s.lastSuccessAt)

        s.now = 40_000L // 已过冷却期
        assertTrue("过冷却期后执行刷新", s.call(written = 0)) // 但 upsert 失败
        assertEquals("失败不应推进时间戳", 1_000L, s.lastSuccessAt)

        s.now = 41_000L
        assertTrue("上次失败未更新时间戳 ⇒ 仍按 1000 算 age，必须允许重试", s.call(written = 100))
        assertEquals("重试成功应推进时间戳", 41_000L, s.lastSuccessAt)

        // 再加一个「从未成功过、一路失败」的场景：时间戳恒为 0，每次都放行
        val fresh = ThrottleReplica()
        fresh.now = 100L
        assertTrue(fresh.call(written = 0))
        fresh.now = 200L
        assertTrue("从未成功过时永不进入冷却，仍可重试", fresh.call(written = 0))
        assertEquals(0L, fresh.lastSuccessAt)
        assertEquals(2, fresh.refreshRuns)
    }

    // ---------------------------------------------------------- 2) 生产源码断言

    @Test
    fun refreshMethodReadsRcontactColumns() {
        val body = csqlFunctionBody("fun refreshFriendNames")
        assertTrue("必须直读已捕获的主库句柄",
            "mainDbOrNull()" in body)
        assertTrue("SQL 必须按本地 wxid 收敛 rcontact 行",
            "where username in (\$csv)" in body)
        assertTrue("必须只读 4 列（username/nickname/conRemark/alias）",
            "select username,nickname,conRemark,alias from \$TABLE" in body)
        assertTrue(" nickname 必须取 rcontact 新值", "nickname = textAt(ciNick)" in body)
        assertTrue(" remark 必须取 rcontact conRemark", "remark = textAt(ciRemark)" in body)
        assertTrue(" alias 必须取 rcontact alias", "alias = textAt(ciAlias)" in body)
        assertTrue("刷新结果必须 upsertFriends 覆盖写回", "groupDb.upsertFriends(refreshed)" in body)
        assertTrue("全程 runCatching 不崩宿主", "runCatching" in body)
    }

    @Test
    fun refreshMethodFiltersByLocalWxids() {
        val body = csqlFunctionBody("fun refreshFriendNames")
        assertTrue("必须先读本地缓存建 wxid→type 映射", "groupDb.allFriends()" in body)
        assertTrue("交集守卫必须在（本地没有的 wxid 不写）", "typeByWxid[wxid] ?: continue" in body)
        assertTrue("必须只更新本地已有行，禁止 INSERT 新行", "insert(" !in body && " CONFLICT_REPLACE" !in body.uppercase())
    }

    @Test
    fun refreshMethodPreservesType() {
        val body = csqlFunctionBody("fun refreshFriendNames")
        assertTrue("type 必须取本地原值", "type = type," in body)
        assertFalse("禁止用 WxidKind.typeOf 固定推导覆盖本地 type", "WxidKind.typeOf" in body)
        assertFalse("禁止用 GroupDb.WxidType.FRIEND 固定值串改群聊", "type = GroupDb.WxidType.FRIEND" in body)
    }

    @Test
    fun pickerCallsRefreshBeforeReadingCache() {
        val body = fpFunctionBody("private fun loadTabs")
        val callAt = body.indexOf("ContactSqlSource.refreshFriendNames()")
        val readAt = body.indexOf("db.allFriends()")
        assertTrue("ForwardPicker 必须调用 ContactSqlSource.refreshFriendNames()", callAt >= 0)
        assertTrue("loadTabs 必须仍读 db.allFriends()", readAt >= 0)
        assertTrue("刷新必须在读缓存之前（本次打开即新昵称），实际 callAt=$callAt readAt=$readAt",
            callAt in 0 until readAt)
        assertTrue("同步调用须有 runCatching 保护",
            "runCatching { ContactSqlSource.refreshFriendNames() }" in body)
        assertTrue("KDoc 须注明 <100ms 性能假设", "<100ms" in body)
    }

    @Test
    fun pickerKeepsChatroomSupplement() {
        val body = fpFunctionBody("private fun loadTabs")
        assertTrue("不得破坏既有群聊补跑 ChatroomSource.runIfNeeded()",
            "ChatroomSource.runIfNeeded()" in body)
    }

    @Test
    fun refreshMethodHasCooldownThrottle() {
        val src = csqlSource()
        assertTrue("必须有 30s 冷却常量 FRIEND_NAMES_REFRESH_COOLDOWN_MS = 30_000L",
            "FRIEND_NAMES_REFRESH_COOLDOWN_MS = 30_000L" in src)
        assertTrue("必须有 @Volatile 时间戳字段 lastFriendNamesRefreshAt = 0L",
            "private var lastFriendNamesRefreshAt = 0L" in src)
        val lines = src.lineSequence().toList()
        val fieldIdx = lines.indexOfFirst { "private var lastFriendNamesRefreshAt = 0L" in it }
        assertTrue("找不到时间戳字段", fieldIdx >= 0)
        assertTrue("时间戳字段必须标 @Volatile", lines[fieldIdx - 1].trim() == "@Volatile")

        val body = csqlFunctionBody("fun refreshFriendNames")
        assertTrue("开头必须用冷却常量做判断", "FRIEND_NAMES_REFRESH_COOLDOWN_MS" in body)
        assertTrue("冷却跳过必须打 age 日志", "cooling down age=" in body)
        assertTrue("冷却判断必须含「上次成功过」守卫(last > 0L)", "last > 0L" in body)
        assertTrue("仅 written>0 时才更新时间戳",
            "if (written > 0) lastFriendNamesRefreshAt" in body)
        assertTrue("必须用 synchronized 串行防多实例重复刷", "synchronized(refreshNamesLock)" in body)
        assertTrue("时间戳必须取单调时钟 elapsedRealtime", "SystemClock.elapsedRealtime()" in body)
    }

    // ---------------------------------------------------------- 工具

    private fun csqlSource(): String = readSource(
        "app/src/main/java/com/lx/wxgroup/core/ContactSqlSource.kt"
    )

    private fun fpSource(): String = readSource(
        "app/src/main/java/com/lx/wxgroup/feature/ForwardPicker.kt"
    )

    private fun readSource(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        for (i in 0 until 6) {
            if (dir == null) break
            val f = File(dir, rel)
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir.parentFile
        }
        throw AssertionError("找不到源码：$rel（工作目录=${System.getProperty("user.dir")}）")
    }

    /** 从指定源码取以 [signature] 开头函数的大括号包体（含声明行，按首个 '{' 到配平 '}'）。 */
    private fun functionBody(src: String, signature: String): String {
        val lines = src.lineSequence().toList()
        val start = lines.indexOfFirst { it.trimStart().startsWith(signature) }
        assertTrue("未找到函数：$signature", start >= 0)
        var depth = 0
        val sb = StringBuilder()
        for (i in start until lines.size) {
            val line = lines[i]
            sb.append(line).append('\n')
            for (c in line) {
                if (c == '{') depth++
                if (c == '}') depth--
            }
            if (depth <= 0) break
        }
        return sb.toString()
    }

    private fun csqlFunctionBody(signature: String): String = functionBody(csqlSource(), signature)

    private fun fpFunctionBody(signature: String): String = functionBody(fpSource(), signature)
}
