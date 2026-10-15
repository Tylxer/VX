package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CvCodec] 的编解码 roundtrip。
 *
 * ⚠️ 只测纯 Map 的 encode/decode：`encodeFrom` / `decodeInto` 碰 android.content.ContentValues，
 * JVM 单测里是 "not mocked"，上真机才有意义。
 */
class CvCodecTest {

    @Test
    fun `roundtrip 各类型全保留`() {
        val src = linkedMapOf<String, Any?>(
            "content" to "\"verity\" 撤回了一条消息",
            "type" to 268445456,
            "isSend" to 0,
            "msgSvrId" to 7146824139L,
            "flag" to true,
            "score" to 3.14,
            "nothing" to null,
        )
        assertEquals(src, CvCodec.decode(CvCodec.encode(src)))
    }

    @Test
    fun `字符串里含分隔符也不歧义`() {
        val src = mapOf(
            "a" to "x\u0001y\u0002z",   // 正好是分隔符本身
            "b" to "quote \" back\\slash",
            "c" to "emoji 🙃 换行\n制表\t",
            "\u0001\u0002" to "key 也可能是分隔符",
        )
        assertEquals(src, CvCodec.decode(CvCodec.encode(src)))
    }

    @Test
    fun `byteArray 原样保留（lvbuffer 是二进制）`() {
        val blob = byteArrayOf(0, 1, 2, -1, -128, 127, 0x0A, 0x0D)
        val src = mapOf<String, Any?>("lvbuffer" to blob, "reserved" to "txt")
        val out = CvCodec.decode(CvCodec.encode(src))
        assertTrue(out["lvbuffer"] is ByteArray)
        assertEquals(blob.toList(), (out["lvbuffer"] as ByteArray).toList())
        assertEquals("txt", out["reserved"])
    }

    @Test
    fun `空 map 与 null 输入都返回空`() {
        assertEquals(emptyMap<String, Any?>(), CvCodec.decode(CvCodec.encode(emptyMap())))
        assertEquals(emptyMap<String, Any?>(), CvCodec.decode(null))
        assertEquals(emptyMap<String, Any?>(), CvCodec.decode(""))
        assertEquals(emptyMap<String, Any?>(), CvCodec.decode("garbage-not-base64"))
        assertEquals(emptyMap<String, Any?>(), CvCodec.decode("only\u0001seps\u0001\u0002"))
    }

    @Test
    fun `坏段被丢掉其余照常解码`() {
        val good = CvCodec.encode(mapOf("type" to 10000))
        val broken = "!!!bad-entry!!!" + '\u0001' + good
        assertEquals(mapOf<String, Any?>("type" to 10000), CvCodec.decode(broken))
    }
}
