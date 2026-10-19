package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnsCdnCheck] 的魔数 / 落盘目录判断回归测试。
 *
 * 魔数表 7 行逐条正例 + 长度不足 / WAV / 非 WEBP RIFF 等反例；dirReject 5 条拒绝逐条 + 放行。
 */
class SnsCdnCheckLogicTest {

    // ---------------------------------------------------------------- 魔数表正例

    @Test
    fun jpeg() {
        val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals("jpeg", SnsCdnCheck.magicKind(head))
        assertTrue(SnsCdnCheck.looksPlainImage(head))
    }

    @Test
    fun png() {
        val head = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
        )
        assertEquals("png", SnsCdnCheck.magicKind(head))
        assertTrue(SnsCdnCheck.looksPlainImage(head))
    }

    @Test
    fun gif() {
        val head = byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte(), 0x39.toByte(), 0x61.toByte())
        assertEquals("gif", SnsCdnCheck.magicKind(head))
        assertTrue(SnsCdnCheck.looksPlainImage(head))
    }

    @Test
    fun bmp() {
        val head = byteArrayOf(0x42.toByte(), 0x4D.toByte(), 0x36.toByte(), 0x00.toByte())
        assertEquals("bmp", SnsCdnCheck.magicKind(head))
        assertTrue(SnsCdnCheck.looksPlainImage(head))
    }

    @Test
    fun webp() {
        // RIFF + offset8 WEBP
        val head = byteArrayOf(
            0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte(), // RIFF
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // size
            0x57.toByte(), 0x45.toByte(), 0x42.toByte(), 0x50.toByte()  // WEBP
        )
        assertEquals("webp", SnsCdnCheck.magicKind(head))
        assertTrue(SnsCdnCheck.looksPlainImage(head))
    }

    @Test
    fun heicVariants() {
        fun ftyp(brand: ByteArray): ByteArray {
            val head = ByteArray(12)
            head[4] = 0x66.toByte(); head[5] = 0x74.toByte(); head[6] = 0x79.toByte(); head[7] = 0x70.toByte()
            for (i in 0..3) head[8 + i] = brand[i]
            return head
        }
        assertEquals("heic", SnsCdnCheck.magicKind(ftyp(byteArrayOf(0x68.toByte(), 0x65.toByte(), 0x69.toByte(), 0x63.toByte()))))
        assertEquals("heic", SnsCdnCheck.magicKind(ftyp(byteArrayOf(0x6D.toByte(), 0x69.toByte(), 0x66.toByte(), 0x31.toByte()))))
        assertEquals("heic", SnsCdnCheck.magicKind(ftyp(byteArrayOf(0x68.toByte(), 0x65.toByte(), 0x69.toByte(), 0x78.toByte()))))
    }

    // ---------------------------------------------------------------- 反例 / 边界

    @Test
    fun emptyAndTooShortReturnUnknown() {
        assertEquals("unknown", SnsCdnCheck.magicKind(ByteArray(0)))
        assertEquals("unknown", SnsCdnCheck.magicKind(byteArrayOf(0x89.toByte(), 0x50.toByte())))
        assertFalse(SnsCdnCheck.looksPlainImage(ByteArray(0)))
        assertFalse(SnsCdnCheck.looksPlainImage(byteArrayOf(0x89.toByte(), 0x50.toByte())))
    }

    @Test
    fun wavRiffIsUnknownNotWebp() {
        // RIFF + offset8 WAVE（WAV 头）→ 必须 unknown，不能误判 webp
        val head = byteArrayOf(
            0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte(), // RIFF
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // size
            0x57.toByte(), 0x41.toByte(), 0x56.toByte(), 0x45.toByte()  // WAVE
        )
        assertEquals("unknown", SnsCdnCheck.magicKind(head))
        assertFalse(SnsCdnCheck.looksPlainImage(head))
    }

    @Test
    fun riffNonWebpOffset8IsUnknown() {
        // RIFF + offset8 非 WEBP → unknown
        val head = byteArrayOf(
            0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte(), // RIFF
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // size
            0x41.toByte(), 0x56.toByte(), 0x49.toByte(), 0x20.toByte()  // AVI(空格)
        )
        assertEquals("unknown", SnsCdnCheck.magicKind(head))
    }

    @Test
    fun pngPrefixTooShortIsUnknown() {
        // 只有 89 50 4E 47（PNG 前 4 字节，不足 8）→ unknown
        val head = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        assertEquals("unknown", SnsCdnCheck.magicKind(head))
        assertFalse(SnsCdnCheck.looksPlainImage(head))
    }

    // ---------------------------------------------------------------- dirReject

    @Test
    fun dirRejectEmpty() {
        assertEquals("empty", SnsCdnCheck.dirReject(null))
        assertEquals("empty", SnsCdnCheck.dirReject(""))
    }

    @Test
    fun dirRejectParentCache() {
        assertEquals("parent-cache", SnsCdnCheck.dirReject("/data/data/com.tencent.mm/cache"))
        assertEquals("parent-cache", SnsCdnCheck.dirReject("/data/data/com.tencent.mm/cache/"))
    }

    /**
     * R11 复核 S-3：调用方 `SnsCdnImage.collect()` 传的是**完整文件路径**
     * （`picCachePath`），不是目录。若只做目录精确匹配，cache 根目录下直接躺的散文件
     * 就绕过了「父目录」这道防线。这里锁死「父目录是 cache 根」也要拒绝。
     */
    @Test
    fun dirRejectFileDirectlyUnderCacheRoot() {
        assertEquals("parent-cache", SnsCdnCheck.dirReject("/data/data/com.tencent.mm/cache/x.jpg"))
    }

    @Test
    fun dirRejectSelfExport() {
        assertEquals("self-export", SnsCdnCheck.dirReject("/data/data/com.tencent.mm/cache/r9cdn/sns/1.jpg"))
        assertEquals("self-export", SnsCdnCheck.dirReject("/data/data/com.tencent.mm/cache/r9i3/sns/2.jpg"))
        assertEquals("self-export", SnsCdnCheck.dirReject("/data/data/com.tencent.mm/files/WechatXposed/moments/_imgdl/3.jpg"))
    }

    @Test
    fun dirRejectNotSnsDir() {
        assertEquals("not-sns-dir", SnsCdnCheck.dirReject("/data/data/com.tencent.mm/cache/other/1.jpg"))
    }

    @Test
    fun dirRejectAllowsValidSnsDirs() {
        // 微信图片缓存目录（含 /sns 或 /image）→ 放行
        assertNull(SnsCdnCheck.dirReject("/data/data/com.tencent.mm/cache/sns/abc.jpg"))
        assertNull(SnsCdnCheck.dirReject("/data/data/com.tencent.mm/MicroMsg/image/def.jpg"))
    }
}
