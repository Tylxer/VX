package com.lx.wxgroup.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R46 · [MomentsLogic.shouldDecryptVideo] 的判据回归。
 *
 * R45 定案：视频 CDN 回来的前 128KB 是密文，key 来自 `SnsInfo.content` protobuf
 * `f8→f5→f25`。`f25>0` 必须解密；`f25==0` 是**服务端声明的合法明文**（全库 2148 条，
 * 占 8.76%），对它 XOR 会直接毁文件。`X-encflag` 绝不参与判据。
 *
 * 本判据在 R45 是 `downloadVideo` 里一行裸 `if (encKey > 0L)`，**零单测覆盖**，
 * 等价变异可全绿通过（`docs/_r44_chain.md` §五 待办 2）。这里把边界锁住。
 *
 * 变异验证逐组结论（真跑单测后的红条数）见 `docs/_r44_chain.md` R46 节与派发台账。
 */
class MomentsVideoGateTest {

    /** 典型 key（真机实测 3805912539 / 2965254400）⇒ 解密 */
    @Test
    fun positiveKeyDecrypts() {
        assertTrue(MomentsLogic.shouldDecryptVideo(3805912539L))
        assertTrue(MomentsLogic.shouldDecryptVideo(2965254400L))
    }

    /** f25==0 ⇒ 合法明文，禁止 XOR */
    @Test
    fun zeroKeyIsPlaintext() {
        assertFalse(MomentsLogic.shouldDecryptVideo(0L))
    }

    /** 异常负值一律不解（宁可存密文，不写坏文件） */
    @Test
    fun negativeKeyNeverDecrypts() {
        assertFalse(MomentsLogic.shouldDecryptVideo(-1L))
        assertFalse(MomentsLogic.shouldDecryptVideo(Long.MIN_VALUE))
    }

    /** 边界：最小正 key 也要解 */
    @Test
    fun smallestPositiveKeyDecrypts() {
        assertTrue(MomentsLogic.shouldDecryptVideo(1L))
    }

    /** 上界不溢出、不改变语义 */
    @Test
    fun maxLongDecrypts() {
        assertTrue(MomentsLogic.shouldDecryptVideo(Long.MAX_VALUE))
    }
}
