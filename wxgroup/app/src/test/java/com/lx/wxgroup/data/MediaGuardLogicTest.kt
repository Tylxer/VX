package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaGuardLogic] 判据单测。
 *
 * 重点覆盖两条**真机事故**：
 * 1. `imgPath` 带 `THUMBNAIL_DIRPATH://th_` 前缀时旧实现整个拒收（VFG 零日志）；
 * 2. token 下限 16 位会把**纯数字串**当合法 token，`contains` 误拦无关文件。
 */
class MediaGuardLogicTest {

    private val md5A = "0c947a1dd158f52fcca431bb84954d5b"
    private val md5B = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val now = 1_000_000L

    // ---------------------------------------------------------------- hexToken

    @Test
    fun hexTokenAcceptsThumbnailPrefix() {
        assertEquals(md5A, MediaGuardLogic.hexToken("THUMBNAIL_DIRPATH://th_$md5A"))
    }

    @Test
    fun hexTokenAcceptsImagePathAndBare() {
        assertEquals(md5A, MediaGuardLogic.hexToken("/image2/ab/cd/$md5A"))
        assertEquals(md5A, MediaGuardLogic.hexToken(md5A))
        assertEquals(md5A, MediaGuardLogic.hexToken("  $md5A  "))
    }

    /** ⛔ 修复核心：16 位纯数字不是 token（否则时间戳/序号会被 contains 命中） */
    @Test
    fun hexTokenRejectsNumericOnlyString() {
        assertNull(MediaGuardLogic.hexToken("202510280001234567"))
        assertNull(MediaGuardLogic.hexToken("/image2/ab/cd/1793015486"))
    }

    @Test
    fun hexTokenRejectsTooShort() {
        assertNull(MediaGuardLogic.hexToken(md5A.dropLast(1)))      // 31 位
        assertNull(MediaGuardLogic.hexToken("th_abcdef"))           // 6 位
        assertNull(MediaGuardLogic.hexToken(""))
        assertNull(MediaGuardLogic.hexToken(null))
        assertNull(MediaGuardLogic.hexToken("THUMBNAIL_DIRPATH://th_"))
    }

    /** 大小写必须归一：真机文件名是小写 md5，大写 imgPath 不转小写会静默漏保护 */
    @Test
    fun hexTokenNormalizesCase() {
        assertEquals(md5A, MediaGuardLogic.hexToken(md5A.uppercase()))
    }

    /**
     * ⛔ 修复核心（R34 P2①）：串里混进**更长的 hex**（sha1 40 / aes key 64）时，
     * 必须取 md5 那个 32 位段，而不是「最长段」。
     *
     * 取最长段 ⇒ 拿回来 64 位 ⇒ `contains` 对真实文件名永不命中 ⇒ 文件照删且**零日志**
     * （既不是 SKIP 也不是 blocked）—— 这正是本次要消灭的静默失效。
     */
    @Test
    fun hexTokenPrefersMd5OverLongerHexSegment() {
        val sha1 = "a".repeat(40)
        val key64 = "b".repeat(64)
        // 长段在 md5 之后
        assertEquals(md5A, MediaGuardLogic.hexToken("THUMBNAIL_DIRPATH://th_${md5A}_$key64"))
        // 长段在 md5 之前（「最长段」实现取到的是它）
        assertEquals(md5A, MediaGuardLogic.hexToken("/image2/ab/cd/${key64}/${md5A}"))
        assertEquals(md5A, MediaGuardLogic.hexToken("$sha1/$md5A"))
        // 真机形态 + 尾巴上挂一段更长的 hex
        assertEquals(md5A, MediaGuardLogic.hexToken("/image2/ab/cd/${md5A}hd?k=$key64"))
    }

    /** 没有 32 位段时才退回「最长 ≥32 段」—— 固化这条兜底，别让它变成隐式行为 */
    @Test
    fun hexTokenFallsBackToOverLongSegmentWhenNoMd5() {
        val sha1 = "a".repeat(40)
        val got = MediaGuardLogic.hexToken("THUMBNAIL_DIRPATH://th_$sha1")
        assertEquals(sha1, got)
        // 兜底拿到的不是 md5 ⇒ 调用方必须据此告警（见 VoiceFileGuard.protectMedia）
        assertFalse(MediaGuardLogic.isMd5(got))
        assertTrue(MediaGuardLogic.isMd5(md5A))
    }

    /**
     * ⛔ 下界必须顶到 21 以上：项目自己记录过 `msgSvrId` 是 **19 位**
     * （`docs/_r21c_voice_root.md` 的 `6139184963485905836`），这类纯数字串一旦被当成
     * token，`contains` 就会误拦无关文件（比漏保护严重得多）。
     *
     * 只放 18 位样本（老用例那样）锁不住 —— 那会把 19 位 msgSvrId 放过去。
     */
    @Test
    fun hexTokenRejectsNineteenDigitMsgSvrId() {
        assertNull(MediaGuardLogic.hexToken("6139184963485905836"))          // 19 位
        assertNull(MediaGuardLogic.hexToken("/image2/ab/cd/6139184963485905836"))
        assertNull(MediaGuardLogic.hexToken("20251028000123456789"))         // 20 位
    }

    // ---------------------------------------------------------------- inMediaDir

    @Test
    fun inMediaDirMatchesOnlyMediaDirs() {
        assertTrue(MediaGuardLogic.inMediaDir("/sdcard/MicroMsg/x/image2/ab/cd/$md5A"))
        assertTrue(MediaGuardLogic.inMediaDir("/sdcard/MicroMsg/x/video2/ab/cd/$md5A"))
        assertTrue(MediaGuardLogic.inMediaDir("/sdcard/MicroMsg/x/video/$md5A.mp4"))
        // 语音目录不是媒体（走 protectMedia 会重复保护，而语音有自己的 protect 路径）
        assertFalse(MediaGuardLogic.inMediaDir("/sdcard/MicroMsg/x/voice2/ab/cd/msg_$md5A.amr"))
        // 前缀相似的目录不能误命中
        assertFalse(MediaGuardLogic.inMediaDir("/sdcard/myimage2/ab/cd/$md5A"))
        // 目录本身（无尾斜杠）不是文件，不该命中
        assertFalse(MediaGuardLogic.inMediaDir("/sdcard/MicroMsg/image2"))
        assertFalse(MediaGuardLogic.inMediaDir(null))
    }

    // ---------------------------------------------------------------- mediaHit

    @Test
    fun mediaHitCoversAllSiblingFileNames() {
        val pool = mapOf(md5A to now + 1000)
        val dir = "/sdcard/MicroMsg/x/image2/ab/cd/"
        // 压缩图 / 原图 / 缩略图 / 临时文件 —— 全是同一张图，都要保护
        assertEquals(md5A, MediaGuardLogic.mediaHit("$dir$md5A", pool, now))
        assertEquals(md5A, MediaGuardLogic.mediaHit("${dir}${md5A}hd", pool, now))
        assertEquals(md5A, MediaGuardLogic.mediaHit("${dir}th_$md5A", pool, now))
        assertEquals(md5A, MediaGuardLogic.mediaHit("${dir}${md5A}_tmp.jpg", pool, now))
    }

    @Test
    fun mediaHitRejectsOtherImageOutsideDirAndExpired() {
        val dir = "/sdcard/MicroMsg/x/image2/ab/cd/"
        // 别的图，不能命中
        assertNull(MediaGuardLogic.mediaHit("$dir$md5B", mapOf(md5A to now + 1000), now))
        // 同一张图但不在媒体目录
        assertNull(MediaGuardLogic.mediaHit("/sdcard/MicroMsg/x/voice2/ab/cd/$md5A", mapOf(md5A to now + 1000), now))
        // 已过期
        assertNull(MediaGuardLogic.mediaHit("$dir$md5A", mapOf(md5A to now - 1), now))
        // 空池
        assertNull(MediaGuardLogic.mediaHit("$dir$md5A", emptyMap(), now))
        assertNull(MediaGuardLogic.mediaHit(null, mapOf(md5A to now + 1000), now))
    }

    /**
     * ⛔ 判据必须作用在**文件名**上，不是整条路径。
     *
     * 用整路径 `contains` 时：桶目录（2 位 hex）无所谓，但一旦 token 出现在**目录段**
     * （比如微信把某张图放在以 md5 命名的子目录里），就会把该目录下**所有文件**都拦住。
     * 老用例全是「token 只在文件名里」，两种实现都绿 —— 这条专门补上区分度。
     */
    @Test
    fun mediaHitIgnoresTokenAppearingOnlyInDirectoryPart() {
        val pool = mapOf(md5A to now + 1000)
        // token 只出现在目录段，文件名里没有 ⇒ 不该命中
        assertNull(MediaGuardLogic.mediaHit("/sdcard/MicroMsg/x/image2/$md5A/other.jpg", pool, now))
        assertNull(MediaGuardLogic.mediaHit("/sdcard/MicroMsg/x/image2/$md5A/th_$md5B", pool, now))
        // 反证：同一个 token 出现在文件名里就该命中
        assertEquals(md5A, MediaGuardLogic.mediaHit("/sdcard/MicroMsg/x/image2/$md5A/$md5A", pool, now))
    }

    /** 「到期那一刻」仍算存活：与 [MediaGuardLogic.anyAlive] 的 `>=` 口径必须一致 */
    @Test
    fun mediaHitTreatsExpiryAtNowAsAlive() {
        val dir = "/sdcard/MicroMsg/x/image2/ab/cd/"
        assertEquals(md5A, MediaGuardLogic.mediaHit("$dir$md5A", mapOf(md5A to now), now))
        // 差 1 毫秒就过期
        assertNull(MediaGuardLogic.mediaHit("$dir$md5A", mapOf(md5A to now - 1), now))
    }

    /** 多张同时受保护时，各命中各的（不能因为池里有别人就放行） */
    @Test
    fun mediaHitMatchesEachTokenIndependently() {
        val pool = mapOf(md5A to now + 1000, md5B to now + 1000)
        val dir = "/sdcard/MicroMsg/x/image2/ef/01/"
        assertEquals(md5A, MediaGuardLogic.mediaHit("$dir$md5A", pool, now))
        assertEquals(md5B, MediaGuardLogic.mediaHit("${dir}${md5B}hd", pool, now))
    }

    // ---------------------------------------------------------------- anyAlive

    @Test
    fun anyAliveTracksExpiry() {
        assertFalse(MediaGuardLogic.anyAlive(emptyMap(), now))
        assertFalse(MediaGuardLogic.anyAlive(mapOf(md5A to now - 1), now))
        assertTrue(MediaGuardLogic.anyAlive(mapOf(md5A to now + 1), now))
        assertTrue(MediaGuardLogic.anyAlive(mapOf(md5A to now - 1, md5B to now + 1), now))
        // ⛔ `until == now` 边界：`>=` 与 `>` 两种实现在这里才分得开。
        //    必须与 mediaHit 的「until < now 才过期」同口径，否则会出现
        //    「anyAlive 说活着、mediaHit 却不命中」⇒ hasMedia 永不复位。
        assertTrue(MediaGuardLogic.anyAlive(mapOf(md5A to now), now))
    }
}
