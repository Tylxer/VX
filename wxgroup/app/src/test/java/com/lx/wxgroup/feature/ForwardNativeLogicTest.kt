package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「原生转发」（G1）纯判据层 [ForwardNativeLogic] 的单测。
 *
 * 锁的是三类**错了只能在真机上看出来、且很难查**的判据：
 * 1. `q1.h` 子类型号（写错 ⇒ 消息照发、只是没转发标记，用户以为功能好了）；
 * 2. `hl0/m`(ForwardInfo) 六字段的取值与缺省（填错 ⇒ 挂上去是个空壳，或走进错误分派）；
 * 3. 失败分阶段 + 回落策略（判错 ⇒ 该永久回落的每条都白试一次，或该回落的干脆不发）。
 *
 * 数据来源纪律：**常量全部来自 `docs/_r29_g1_forward_forensics.md` 的①档字节码**
 * （`q1.h` 值表 §2.4、`hl0/m.toString` 字段语义 §2.1），不是「看起来合理」编出来的。
 * `hl0/m.c/d/e` 三个 String 字段的缺省（"" / null / null）取自 `m.<init>`。
 *
 * 变异点（每条用例对应的「改坏判据」）写在各用例的注释里，详见 `docs/_r36_forward.md` §5。
 */
class ForwardNativeLogicTest {

    private fun snap(
        kind: ForwardLogic.Kind,
        text: String = "",
        localPath: String = "",
        fileExists: Boolean = false,
        sourceChat: String = "",
        msgId: Long = 0L,
        msgSvrId: Long = 0L,
    ) = ForwardLogic.Snapshot(
        kind = kind,
        text = text,
        localPath = localPath,
        fileExists = fileExists,
        sourceChat = sourceChat,
        msgId = msgId,
        msgSvrId = msgSvrId,
    )

    // ------------------------------------------------------------ q1.h 子类型

    @Test
    fun subTypeValuesMatchForensicsTable() {
        // ① G1 §2.4 值表。变异点：把任一常量改一位数字（如 13→14）⇒ 本条变红。
        assertEquals(2, ForwardNativeLogic.SUBTYPE_RESEND)
        assertEquals(4, ForwardNativeLogic.SUBTYPE_TEXT_FORWARD)
        assertEquals(5, ForwardNativeLogic.SUBTYPE_TEXT_PLAIN)
        assertEquals(13, ForwardNativeLogic.SUBTYPE_IMAGE_FORWARD)
        assertEquals(19, ForwardNativeLogic.SUBTYPE_MULTI_IMAGE)
        assertEquals(0, ForwardNativeLogic.SUBTYPE_NONE)
    }

    @Test
    fun subTypeOfMapsTextAndImageOnly() {
        // 变异点：subTypeOf 里加一个 `Kind.VIDEO -> IMAGE_FORWARD` ⇒ 视频被送进原生链（真机撞壁）
        assertEquals(
            ForwardNativeLogic.SubType.TEXT_FORWARD,
            ForwardNativeLogic.subTypeOf(ForwardLogic.Kind.TEXT)
        )
        assertEquals(
            ForwardNativeLogic.SubType.IMAGE_FORWARD,
            ForwardNativeLogic.subTypeOf(ForwardLogic.Kind.IMAGE)
        )
        assertEquals(
            ForwardNativeLogic.SubType.MULTI_IMAGE,
            ForwardNativeLogic.subTypeOf(ForwardLogic.Kind.IMAGE, multiImage = true)
        )
        for (k in listOf(
            ForwardLogic.Kind.VIDEO, ForwardLogic.Kind.VOICE, ForwardLogic.Kind.FILE,
            ForwardLogic.Kind.CARD, ForwardLogic.Kind.LOCATION, ForwardLogic.Kind.LINK,
            ForwardLogic.Kind.UNKNOWN,
        )) {
            assertEquals("kind=$k 不该走原生", ForwardNativeLogic.SubType.NONE, ForwardNativeLogic.subTypeOf(k))
        }
    }

    @Test
    fun subTypeValueOfNoneIsZero() {
        // 变异点：subTypeValue(NONE) 返回 13 ⇒ NONE 也被当成图片转发去设 q1.h
        assertEquals(0, ForwardNativeLogic.subTypeValue(ForwardNativeLogic.SubType.NONE))
        assertEquals(4, ForwardNativeLogic.subTypeValue(ForwardNativeLogic.SubType.TEXT_FORWARD))
        assertEquals(13, ForwardNativeLogic.subTypeValue(ForwardNativeLogic.SubType.IMAGE_FORWARD))
        assertEquals(19, ForwardNativeLogic.subTypeValue(ForwardNativeLogic.SubType.MULTI_IMAGE))
    }

    @Test
    fun supportsNativeIsTextAndImageOnly() {
        assertTrue(ForwardNativeLogic.supportsNative(ForwardLogic.Kind.TEXT))
        assertTrue(ForwardNativeLogic.supportsNative(ForwardLogic.Kind.IMAGE))
        assertFalse(ForwardNativeLogic.supportsNative(ForwardLogic.Kind.VIDEO))
        assertFalse(ForwardNativeLogic.supportsNative(ForwardLogic.Kind.LINK))
        assertFalse(ForwardNativeLogic.supportsNative(ForwardLogic.Kind.UNKNOWN))
    }

    @Test
    fun planForDoesNotRouteUnsupportedKindsToNative() {
        // ★ 防「锚点可用就一律 NATIVE」：名片/位置/链接/视频没有原生链路，
        //   送进去只会撞壁，且失败原因会写成 METHOD，看着像「锚点失效」误导排查。
        // 变异点：planFor 里去掉 supportsNative 判据 ⇒ 本条变红
        val videoWithText = ForwardLogic.planFor(
            snap(ForwardLogic.Kind.VIDEO, text = "视频标题"),
            ForwardLogic.NativeState.AVAILABLE
        )
        assertEquals(ForwardLogic.Strategy.RESEND_TEXT, videoWithText.strategy)

        val linkNoMaterial = ForwardLogic.planFor(
            snap(ForwardLogic.Kind.LINK),
            ForwardLogic.NativeState.AVAILABLE
        )
        assertEquals(ForwardLogic.Strategy.SKIP, linkNoMaterial.strategy)

        // 反证：TEXT / IMAGE 在 AVAILABLE 下仍走原生（别把上面的判据做成「永不原生」）
        assertEquals(
            ForwardLogic.Strategy.NATIVE,
            ForwardLogic.planFor(snap(ForwardLogic.Kind.TEXT, text = "hi"), ForwardLogic.NativeState.AVAILABLE).strategy
        )
        assertEquals(
            ForwardLogic.Strategy.NATIVE,
            ForwardLogic.planFor(
                snap(ForwardLogic.Kind.IMAGE, localPath = "/x.jpg", fileExists = true),
                ForwardLogic.NativeState.AVAILABLE
            ).strategy
        )
    }

    // ------------------------------------------------------------ ForwardInfo（hl0/m）

    @Test
    fun forwardInfoOfFillsMsgIdSvrIdAndTalker() {
        // 真机形状：文本消息，msgId=19289041，msgSvrId=6139184963485905836（项目真实样本量级）
        val fi = ForwardNativeLogic.forwardInfoOf(
            snap(
                ForwardLogic.Kind.TEXT, text = "hi", sourceChat = "wxid_src",
                msgId = 19289041L, msgSvrId = 6139184963485905836L,
            )
        )
        assertEquals(19289041L, fi.msgId)                       // m.b
        assertEquals("6139184963485905836", fi.svrId)           // m.e
        assertEquals("wxid_src", fi.talker)                     // m.f
        // 拿不到的三个保持 m.<init> 默认值（变异点：把 fromImgId 默认改成 0 ⇒ 本条变红）
        assertEquals(ForwardNativeLogic.NO_IMG_ID, fi.fromImgId)
        assertEquals(-1L, fi.fromImgId)                         // m.a
        assertEquals("", fi.snsLocalId)                         // m.c（是 "" 不是 null）
        assertNull(fi.uuid)                                     // m.d
    }

    @Test
    fun forwardInfoOfDegradesToInitDefaultsWhenNothingCaptured() {
        // ★ 真机上「长按没抓到消息对象」的形状：msgId=0、svrId=0、talker 空。
        // 纪律：拿不到就退回 m.<init> 默认，**不填 0 之外的占位**（不猜）。
        val fi = ForwardNativeLogic.forwardInfoOf(snap(ForwardLogic.Kind.TEXT, text = "hi"))
        assertEquals(0L, fi.msgId)
        assertNull("svrId 拿不到必须是 null，不能填 \"0\"", fi.svrId)
        assertEquals("", fi.talker)
    }

    @Test
    fun svrIdStringOfRejectsNonPositive() {
        assertEquals("123", ForwardNativeLogic.svrIdStringOf(123L))
        assertNull(ForwardNativeLogic.svrIdStringOf(0L))
        // 变异点：条件写成 `>= 0` ⇒ 0 会变成 "0" 这个假值被挂上去
        assertNull(ForwardNativeLogic.svrIdStringOf(-1L))
    }

    @Test
    fun hasForwardMarkNeedsMsgIdOrSvrId() {
        val withId = ForwardNativeLogic.forwardInfoOf(snap(ForwardLogic.Kind.TEXT, msgId = 7L))
        assertTrue(ForwardNativeLogic.hasForwardMark(withId))
        val withSvr = ForwardNativeLogic.forwardInfoOf(snap(ForwardLogic.Kind.TEXT, msgSvrId = 9L))
        assertTrue(ForwardNativeLogic.hasForwardMark(withSvr))
        val empty = ForwardNativeLogic.forwardInfoOf(snap(ForwardLogic.Kind.TEXT))
        assertFalse("两个都没有 ⇒ 挂上去是空壳，日志必须能标出来", ForwardNativeLogic.hasForwardMark(empty))
        // 变异点：判据改成 `msgId > 0 || svrId != null` ⇒ 空串 svrId 也算有标记
        val blankSvr = ForwardNativeLogic.ForwardInfo(msgId = 0L, svrId = "  ")
        assertFalse(ForwardNativeLogic.hasForwardMark(blankSvr))
    }

    // ------------------------------------------------------------ 失败分阶段

    @Test
    fun structuralStagesAreClassMethodConstructOnly() {
        // 变异点：把 SEND 加进结构性 ⇒ 一次网络抖动后整批都不再试原生
        assertTrue(ForwardNativeLogic.isStructural(ForwardNativeLogic.FailStage.CLASS))
        assertTrue(ForwardNativeLogic.isStructural(ForwardNativeLogic.FailStage.METHOD))
        assertTrue(ForwardNativeLogic.isStructural(ForwardNativeLogic.FailStage.CONSTRUCT))
        assertFalse(ForwardNativeLogic.isStructural(ForwardNativeLogic.FailStage.SEND))
        assertFalse(ForwardNativeLogic.isStructural(ForwardNativeLogic.FailStage.MATERIAL))
        assertFalse(ForwardNativeLogic.isStructural(ForwardNativeLogic.FailStage.NONE))
    }

    @Test
    fun nativeStateDowngradesOnlyOnStructuralFailure() {
        assertEquals(
            ForwardLogic.NativeState.REJECTED,
            ForwardNativeLogic.nativeStateAfter(
                ForwardNativeLogic.FailStage.CLASS, ForwardLogic.NativeState.AVAILABLE
            )
        )
        assertEquals(
            ForwardLogic.NativeState.REJECTED,
            ForwardNativeLogic.nativeStateAfter(
                ForwardNativeLogic.FailStage.CONSTRUCT, ForwardLogic.NativeState.AVAILABLE
            )
        )
        // 入队失败可能只是这一次的状态问题 ⇒ 下一个目标照样值得试原生
        assertEquals(
            ForwardLogic.NativeState.AVAILABLE,
            ForwardNativeLogic.nativeStateAfter(
                ForwardNativeLogic.FailStage.SEND, ForwardLogic.NativeState.AVAILABLE
            )
        )
    }

    @Test
    fun failResultCarriesStage() {
        val r = ForwardNativeLogic.NativeResult.fail(ForwardNativeLogic.FailStage.SEND)
        assertFalse(r.ok)
        assertEquals(ForwardNativeLogic.FailStage.SEND, r.stage)
        assertTrue(ForwardNativeLogic.NativeResult.ok().ok)
        assertEquals(ForwardNativeLogic.FailStage.NONE, ForwardNativeLogic.NativeResult.ok().stage)
    }

    // ------------------------------------------------------------ 原生失败后的回落

    @Test
    fun fallbackPrefersTextThenFileThenSkip() {
        // 变异点：去掉文本分支 ⇒ 有正文的文本消息在原生失败后什么都不发（真机只剩「失败 N」）
        assertEquals(
            ForwardLogic.Strategy.RESEND_TEXT,
            ForwardNativeLogic.fallbackStrategy(snap(ForwardLogic.Kind.TEXT, text = "今晚八点开会"))
        )
        assertEquals(
            ForwardLogic.Strategy.RESEND_FILE,
            ForwardNativeLogic.fallbackStrategy(
                snap(ForwardLogic.Kind.IMAGE, localPath = "/storage/x.jpg", fileExists = true)
            )
        )
        // 有路径但文件不在 ⇒ 与 planFor 同口径，不能判 RESEND_FILE（sender 里会静默失败）
        assertEquals(
            ForwardLogic.Strategy.SKIP,
            ForwardNativeLogic.fallbackStrategy(
                snap(ForwardLogic.Kind.IMAGE, localPath = "/gone.jpg", fileExists = false)
            )
        )
        // 都没原料
        assertEquals(
            ForwardLogic.Strategy.SKIP,
            ForwardNativeLogic.fallbackStrategy(snap(ForwardLogic.Kind.TEXT))
        )
    }

    @Test
    fun fallbackNeverChoosesFileForKindsWithoutChannel() {
        // 视频/语音/普通文件的本地文件**发不出去**（canResendFile 逐个锁死），
        // 回落若判 RESEND_FILE ⇒ resendFile 里逐目标返回 false，toast 只剩「失败 N」不说原因。
        // 变异点：fallbackStrategy 里去掉 canResendFile 判据 ⇒ 本条变红
        for (k in listOf(
            ForwardLogic.Kind.VIDEO, ForwardLogic.Kind.VOICE, ForwardLogic.Kind.FILE,
        )) {
            assertEquals(
                "kind=$k 没有文件发送链路，不该回落成 RESEND_FILE",
                ForwardLogic.Strategy.SKIP,
                ForwardNativeLogic.fallbackStrategy(snap(k, localPath = "/x.bin", fileExists = true))
            )
        }
    }

    @Test
    fun fallbackMatchesPlanForDegradedBranch() {
        // 同一条快照：「原生不可用」与「原生可用但失败了」必须落到同一个策略
        // —— 否则真机上会出现「关掉原生反而发得出去」的怪现象。
        for (s in listOf(
            snap(ForwardLogic.Kind.TEXT, text = "hi"),
            snap(ForwardLogic.Kind.IMAGE, localPath = "/x.jpg", fileExists = true),
            snap(ForwardLogic.Kind.LINK, text = "标题"),
            snap(ForwardLogic.Kind.FILE, localPath = "/x.pdf", fileExists = true),
        )) {
            assertEquals(
                "kind=${s.kind}",
                ForwardLogic.planFor(s, ForwardLogic.NativeState.REJECTED).strategy,
                ForwardNativeLogic.fallbackStrategy(s)
            )
        }
    }
}
