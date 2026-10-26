package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VoiceGuardLogic] 的判据回归测试。
 *
 * 用例样本全部来自 **8.0.48 真机抓到的真实数据**（`docs/_r21c_voice_root.md`，不是编的）：
 * - 6 条被救语音的 `msgId`：19289041 / 19289057 / 19289088 / 19289117 / 19289824 / 19290094；
 * - 对应 `message.imgPath`（**26 位 hex** VFS 虚拟路径，不是文件系统路径；team-lead 描述
 *   写成「24 位」是笔误，8 个真机样本实测全是 26 位）。
 *
 * 判据错一处就要重上一轮真机，所以先在这里钉死 12 类形态。
 */
class VoiceGuardLogicTest {

    private val IMG_A = "46142710252654b6378ddb4103" // msgId 19289041
    private val IMG_B = "09142810252654b6378349f100" // msgId 19289057
    private val MSG_A = "19289041"
    private val MSG_B = "19289057"

    /** 不在受保护集里的 26 位 hex，专当「不命中」样本 */
    private val MISS = "ffffffffffffffffffffffffff"

    private val rescued = setOf(IMG_A, IMG_B, MSG_A, MSG_B)

    // ---------------------------------------------------------------- 1. 表名

    @Test
    fun nonVoiceinfoTableIsNotBlocked() {
        assertFalse(VoiceGuardLogic.shouldBlock("message", "FileName=?", arrayOf(IMG_A), rescued))
        assertFalse(VoiceGuardLogic.shouldBlock("rconversation", "FileName=?", arrayOf(IMG_A), rescued))
        assertFalse(VoiceGuardLogic.shouldBlock(null, "FileName=?", arrayOf(IMG_A), rescued))
    }

    // ---------------------------------------------------------------- 2. 删全表

    @Test
    fun nullWhereClauseIsNotBlocked() {
        // delete("voiceinfo", null, null) = 删全表（整体清理），绝不拦
        assertFalse(VoiceGuardLogic.shouldBlock("voiceinfo", null, null, rescued))
        assertFalse(VoiceGuardLogic.shouldBlock("voiceinfo", null, arrayOf(IMG_A), rescued))
    }

    // ---------------------------------------------------------------- 3/4. FileName =

    @Test
    fun fileNameEqHit() {
        assertTrue(VoiceGuardLogic.shouldBlock("voiceinfo", "FileName=?", arrayOf(IMG_A), rescued))
        assertTrue(VoiceGuardLogic.shouldBlock("voiceinfo", "FileName = ?", arrayOf(IMG_B), rescued))
    }

    @Test
    fun fileNameEqMiss() {
        assertFalse(VoiceGuardLogic.shouldBlock("voiceinfo", "FileName=?", arrayOf(MISS), rescued))
    }

    // ---------------------------------------------------------------- 5. FileName IN

    @Test
    fun fileNameInPartialHit() {
        val args = arrayOf(MISS, IMG_B, "deadbeefdeadbeefdeadbeef00")
        assertTrue(VoiceGuardLogic.shouldBlock("voiceinfo", "FileName IN (?,?,?)", args, rescued))
    }

    // ---------------------------------------------------------------- 6. MsgLocalId

    @Test
    fun msgLocalIdEqHit() {
        assertTrue(VoiceGuardLogic.shouldBlock("voiceinfo", "MsgLocalId=?", arrayOf(MSG_A), rescued))
        assertTrue(VoiceGuardLogic.shouldBlock("voiceinfo", "MsgLocalId = ?", arrayOf(MSG_B), rescued))
    }

    @Test
    fun msgLocalIdInHit() {
        assertTrue(
            VoiceGuardLogic.shouldBlock("voiceinfo", "MsgLocalId IN (?,?)", arrayOf("1", MSG_A), rescued)
        )
    }

    // ---------------------------------------------------------------- 7. 非参数化（值内联进 whereClause）

    @Test
    fun inlineHexMatchedWhenArgsNull() {
        assertTrue(VoiceGuardLogic.shouldBlock("voiceinfo", "FileName = $IMG_A", null, rescued))
        assertTrue(
            VoiceGuardLogic.shouldBlock("voiceinfo", "FileName IN ($IMG_A, $MISS)", null, rescued)
        )
    }

    // ---------------------------------------------------------------- 8. 子查询

    @Test
    fun subqueryIsNotBlocked() {
        // 真机定案的真实 SQL 形态：FileName IN (SELECT imgPath FROM message ...)
        // 从 whereClause 拿不到具体 imgPath ⇒ 保守放弃（不拦）
        val clause = "FileName IN (SELECT imgPath FROM message WHERE talker IN (?))"
        assertFalse(VoiceGuardLogic.shouldBlock("voiceinfo", clause, arrayOf("wxid_x"), rescued))
        assertFalse(VoiceGuardLogic.shouldBlock("voiceinfo", clause, null, rescued))
    }

    // ---------------------------------------------------------------- 9. 空受保护集

    @Test
    fun emptyRescuedIsNotBlocked() {
        assertFalse(VoiceGuardLogic.shouldBlock("voiceinfo", "FileName=?", arrayOf(IMG_A), emptySet()))
        assertFalse(VoiceGuardLogic.shouldBlock("voiceinfo", "MsgLocalId=?", arrayOf(MSG_A), emptySet()))
    }

    // ---------------------------------------------------------------- 10. 抽不出 hex

    @Test
    fun noHexExtractableIsNotBlocked() {
        assertFalse(VoiceGuardLogic.shouldBlock("voiceinfo", "FileName=?", arrayOf("not-a-hex"), rescued))
        assertFalse(VoiceGuardLogic.shouldBlock("voiceinfo", "FileName = 'abc'", null, rescued))
    }

    // ---------------------------------------------------------------- 11. 大小写

    @Test
    fun uppercaseHexHandled() {
        val upper = IMG_A.uppercase()
        assertEquals(listOf(IMG_A), VoiceGuardLogic.hexTokensFrom(upper))
        assertTrue(VoiceGuardLogic.shouldBlock("voiceinfo", "FileName=?", arrayOf(upper), rescued))
        assertTrue(VoiceGuardLogic.shouldBlock("voiceinfo", "FileName = $upper", null, rescued))
    }

    // ---------------------------------------------------------------- 12. 长度不对的 hex

    @Test
    fun wrongLengthHexNotRecognized() {
        // 25 位 / 27 位都不该被当成 26 位 imgPath
        assertEquals(emptyList<String>(), VoiceGuardLogic.hexTokensFrom("46142710252654b6378ddb410"))
        assertEquals(emptyList<String>(), VoiceGuardLogic.hexTokensFrom("46142710252654b6378ddb4103a"))
        assertFalse(
            VoiceGuardLogic.shouldBlock("voiceinfo", "FileName = 46142710252654b6378ddb410", null, rescued)
        )
        // 34 位 hex 串里的 26 位前缀也不能被抽出来
        assertEquals(emptyList<String>(), VoiceGuardLogic.hexTokensFrom("46142710252654b6378ddb4103deadbeef"))
    }

    // ---------------------------------------------------------------- 清理形态不拦（关键保守性）

    @Test
    fun cleanupOperatorsAreNotBlocked() {
        // < / > / LIKE / IS NULL 都是微信清理语音的形态，绝不能拦
        assertFalse(VoiceGuardLogic.shouldBlock("voiceinfo", "MsgLocalId < ?", arrayOf(MSG_A), rescued))
        assertFalse(VoiceGuardLogic.shouldBlock("voiceinfo", "FileName LIKE ?", arrayOf(IMG_A), rescued))
        assertFalse(VoiceGuardLogic.shouldBlock("voiceinfo", "FileName IS NULL", null, rescued))
        assertFalse(VoiceGuardLogic.shouldBlock("voiceinfo", "FileName != ?", arrayOf(IMG_A), rescued))
    }

    // ---------------------------------------------------------------- hexTokensFrom 工具

    @Test
    fun hexTokensFromExtractsMultiple() {
        assertEquals(listOf(IMG_A, IMG_B), VoiceGuardLogic.hexTokensFrom("FileName IN ($IMG_A, $IMG_B)"))
    }

    @Test
    fun hexTokensFromNullAndEmpty() {
        assertEquals(emptyList<String>(), VoiceGuardLogic.hexTokensFrom(null))
        assertEquals(emptyList<String>(), VoiceGuardLogic.hexTokensFrom(""))
        assertEquals(emptyList<String>(), VoiceGuardLogic.hexTokensFrom("no hex here"))
    }

    // -------------------------------------------------- 缓存判据（R21c：空集不缓存 + gen 失效）

    @Test
    fun nonEmptyCacheIsUsableWithinTtl() {
        // 非空 + gen 未变 + TTL 内 ⇒ 可命中（保留抗重试的正收益）
        assertTrue(
            VoiceGuardLogic.cacheUsable(
                now = 1_000, cachedAt = 0, cachedSize = 3, ttlMs = 3_000,
                cachedGen = 7, gen = 7
            )
        )
    }

    @Test
    fun nonEmptyCacheExpiresAfterTtl() {
        // 边界：TTL 到点就过期（严格小于，不是 <=）
        assertFalse(
            VoiceGuardLogic.cacheUsable(
                now = 3_000, cachedAt = 0, cachedSize = 3, ttlMs = 3_000,
                cachedGen = 7, gen = 7
            )
        )
    }

    @Test
    fun emptyCacheIsNeverUsable() {
        // ⛔ 本判据核心①：空集即使 cachedAt 极新也不算命中。
        // 回归保护：若有人把 `cachedSize > 0` 删掉，这条立刻红。
        assertFalse(
            VoiceGuardLogic.cacheUsable(
                now = 1, cachedAt = 0, cachedSize = 0, ttlMs = 3_000,
                cachedGen = 7, gen = 7
            )
        )
        assertFalse(
            VoiceGuardLogic.cacheUsable(
                now = 0, cachedAt = 0, cachedSize = 0, ttlMs = 3_000,
                cachedGen = 7, gen = 7
            )
        )
    }

    @Test
    fun cacheIsStaleAfterNewRecord() {
        // ⛔ 本判据核心②（真机 bug 的直接护栏）：3 秒内连撤两条语音。
        // 缓存建于 gen=7（含 A），随后 record(B) 让 gen→8。
        // 第二条 DELETE 必须 miss（否则 B 被放行 ⇒ 播放失败）。
        assertFalse(
            "record 之后旧缓存必须失效，否则连撤第二条会漏拦",
            VoiceGuardLogic.cacheUsable(
                now = 1_100, cachedAt = 100, cachedSize = 1, ttlMs = 3_000,
                cachedGen = 7, gen = 8
            )
        )
    }

    @Test
    fun emptyBuildStampsAlreadyExpired() {
        // ⚠️ 这里断言的是**精确值** now - ttlMs，不能只断言 cacheUsable==false ——
        //    后者被 cachedSize=0 短路保证，与 stamp 值无关，等于没钉住（复核指出的假绿）。
        val stamped = VoiceGuardLogic.stampAfterBuild(now = 10_000, builtSize = 0, ttlMs = 3_000)
        assertEquals(7_000L, stamped)
    }

    @Test
    fun stampAfterBuildAvoidsUptimeZeroTrap() {
        // ⚠️ 不能用常量 0L 表示「过期」：设备刚启动 uptime 很小时 now-0 会意外命中。
        // 同样断言精确值而非 `stamped < 0`。
        val stamped = VoiceGuardLogic.stampAfterBuild(now = 100, builtSize = 0, ttlMs = 3_000)
        assertEquals(-2_900L, stamped)
    }

    @Test
    fun nonEmptyBuildStampsNow() {
        // 非空集合照常缓存：stamp 精确等于 now
        assertEquals(10_000L, VoiceGuardLogic.stampAfterBuild(now = 10_000, builtSize = 2, ttlMs = 3_000))
        assertTrue(
            VoiceGuardLogic.cacheUsable(
                now = 10_500, cachedAt = 10_000, cachedSize = 2, ttlMs = 3_000,
                cachedGen = 1, gen = 1
            )
        )
    }
}
