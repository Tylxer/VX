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
    }
}
