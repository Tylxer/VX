package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HintTextLogic] 的判据回归测试。
 *
 * 用例的 type 分布 / 语音 content 样本来自 **8.0.48 真机抓到的真实数据**（不是编的）：
 * - `rescued` 台账 `orig_type`：`1`(文本)×57 / `3`(图片)×9 / `34`(语音)×6 / `47`(表情)×1 /
 *   `805306417`×4 / `822083633`×1；
 * - 语音 `content`：`wxid_3dmyz2iqavq822:30478:0`、`:12424:0`、`:20785:1:`。
 *
 * 用户报障「提示行带上撤回内容，便于识别那一条被撤回」，改错一处判据就要重上一轮真机，
 * 所以在这里钉死。
 */
class HintTextLogicTest {

    private val WHO = "\"张三\""

    // ---------------------------------------------------------------- 文本

    @Test
    fun textSummaryShown() {
        assertEquals("你好", HintTextLogic.summary(HintTextLogic.TYPE_TEXT, "你好"))
        assertEquals(
            "$WHO 尝试撤回一条消息（已阻止）：你好",
            HintTextLogic.buildHint(WHO, HintTextLogic.summary(HintTextLogic.TYPE_TEXT, "你好"))
        )
    }

    @Test
    fun textBlankOrNullGivesEmpty() {
        // 空串 = 无内容可展示 → buildHint 只给基础句，不带冒号
        assertEquals("", HintTextLogic.summary(HintTextLogic.TYPE_TEXT, null))
        assertEquals("", HintTextLogic.summary(HintTextLogic.TYPE_TEXT, ""))
        assertEquals("", HintTextLogic.summary(HintTextLogic.TYPE_TEXT, "   "))
        assertEquals("$WHO 尝试撤回一条消息（已阻止）", HintTextLogic.buildHint(WHO, ""))
    }

    @Test
    fun textTruncatedAtLimit() {
        val long = "一".repeat(40)
        val s = HintTextLogic.summary(HintTextLogic.TYPE_TEXT, long)
        assertEquals(31, s.length) // 30 + 省略号 1
        assertTrue(s.startsWith("一".repeat(30)))
        assertTrue(s.endsWith("…"))
    }

    @Test
    fun textNewlineCollapsedToSingleLine() {
        // 提示行是单行：换行 / 制表符 / 连续空格必须折叠，且不含换行
        val s = HintTextLogic.summary(HintTextLogic.TYPE_TEXT, "第一行\n第二行\r\n第三行")
        assertEquals("第一行 第二行 第三行", s)
        assertFalse(s.contains('\n'))
    }

    // ---------------------------------------------------------------- 语音（单位论证见 §3）

    @Test
    fun voiceDurationIsMilliseconds() {
        // 30478 若按毫秒 ≈ 30 秒（按秒则是 8.5 小时，物理不可能）→ 锁毫秒单位
        assertEquals(
            "[语音 30秒]",
            HintTextLogic.summary(HintTextLogic.TYPE_VOICE, "wxid_3dmyz2iqavq822:30478:0")
        )
    }

    @Test
    fun voiceFourSegmentVariant() {
        // 段数不固定（:20785:1: 是 4 段形态），第 2 段仍是时长
        assertEquals("[语音 21秒]", HintTextLogic.summary(HintTextLogic.TYPE_VOICE, ":20785:1:"))
        assertEquals("[语音 12秒]", HintTextLogic.summary(HintTextLogic.TYPE_VOICE, ":12424:0"))
    }

    @Test
    fun voiceDegradesGracefully() {
        assertEquals("[语音]", HintTextLogic.summary(HintTextLogic.TYPE_VOICE, null))
        assertEquals("[语音]", HintTextLogic.summary(HintTextLogic.TYPE_VOICE, ""))
        assertEquals("[语音]", HintTextLogic.summary(HintTextLogic.TYPE_VOICE, "no-colon"))
        assertEquals("[语音]", HintTextLogic.summary(HintTextLogic.TYPE_VOICE, "wxid:abc:0"))
        assertEquals("[语音]", HintTextLogic.summary(HintTextLogic.TYPE_VOICE, "wxid:0:0"))
        assertEquals("[语音]", HintTextLogic.summary(HintTextLogic.TYPE_VOICE, ":"))
    }

    @Test
    fun voiceDoesNotLeakSender() {
        // 群聊歧义：语音 content 第 1 段是「发语音的人」的 wxid，可能与「谁撤回的」不是同一人。
        // 摘要只带时长、绝不带 wxid，避免把发送者误认成撤回者。
        val s = HintTextLogic.summary(HintTextLogic.TYPE_VOICE, "wxid_alice:30478:0")
        assertFalse(s.contains("wxid_alice"))
        assertEquals("[语音 30秒]", s)
    }

    // ---------------------------------------------------------------- 纯类型标签

    @Test
    fun mediaTypeLabels() {
        assertEquals("[图片]", HintTextLogic.summary(HintTextLogic.TYPE_IMAGE, null))
        assertEquals("[视频]", HintTextLogic.summary(HintTextLogic.TYPE_VIDEO, null))
        assertEquals("[表情]", HintTextLogic.summary(HintTextLogic.TYPE_EMOJI, null))
        assertEquals("[位置]", HintTextLogic.summary(HintTextLogic.TYPE_LOCATION, null))
        assertEquals("[名片]", HintTextLogic.summary(HintTextLogic.TYPE_CARD, null))
    }

    @Test
    fun systemTypeHasNoContent() {
        assertEquals("", HintTextLogic.summary(HintTextLogic.TYPE_SYSTEM, null))
    }

    // ---------------------------------------------------------------- 未知 type

    @Test
    fun unknownTypePlaceholderDoesNotCrash() {
        // team-lead 实证的 805306417 / 822083633，以及随便一个真未知值 → 占位文案
        assertEquals("[消息]", HintTextLogic.summary(805306417, null))
        assertEquals("[消息]", HintTextLogic.summary(822083633, null))
        assertEquals("[消息]", HintTextLogic.summary(999999, "whatever"))
    }

    // ---------------------------------------------------------------- 应用消息（type=49，XML）

    @Test
    fun appFileSummary() {
        val xml = "<msg><appmsg><type>6</type><title>report.docx</title></appmsg></msg>"
        assertEquals("[文件 report.docx]", HintTextLogic.summary(HintTextLogic.TYPE_APP, xml))
    }

    @Test
    fun appQuoteSummary() {
        val xml =
            "<msg><appmsg><type>57</type><title>回复正文</title>" +
                "<refermsg><type>1</type><content>被引用的原文</content></refermsg></appmsg></msg>"
        assertEquals("[引用 被引用的原文]", HintTextLogic.summary(HintTextLogic.TYPE_APP, xml))
    }

    @Test
    fun appUnknownSubtypeOrBlank() {
        assertEquals("[消息]", HintTextLogic.summary(HintTextLogic.TYPE_APP, null))
        assertEquals("[消息]", HintTextLogic.summary(HintTextLogic.TYPE_APP, ""))
        assertEquals(
            "[消息]",
            HintTextLogic.summary(HintTextLogic.TYPE_APP, "<msg><appmsg><type>99</type></appmsg></msg>")
        )
    }

    // ---------------------------------------------------------------- who / buildHint

    @Test
    fun whoExtraction() {
        assertEquals("\"张三\"", HintTextLogic.whoFrom("\"张三\" 撤回了一条消息"))
        assertNull(HintTextLogic.whoFrom(null))
        assertNull(HintTextLogic.whoFrom(""))
        assertNull(HintTextLogic.whoFrom("   "))
        // 光秃秃的「撤回了一条消息」抠不出谁
        assertNull(HintTextLogic.whoFrom("撤回了一条消息"))
    }

    @Test
    fun buildHintWithoutSummary() {
        assertEquals("$WHO 尝试撤回一条消息（已阻止）", HintTextLogic.buildHint(WHO, ""))
        assertEquals("$WHO 尝试撤回一条消息（已阻止）", HintTextLogic.buildHint(WHO, "   "))
    }

    // ---------------------------------------------------------------- 角标文案（R21d 方案 1）

    @Test
    fun badgeVoiceWithSeconds() {
        // 用户最终选定的格式：第 3 种，`已阻止撤回 · 语音 30秒`（**不带方括号**）
        // 语音 content 第 2 段是**毫秒**（已端到端闭环：1745 B / 1734 B 两条相差 0.6%，
        // 恒定码率 silk ⇒ 30000ms 换成 30 秒）
        //
        // ⚠️ 这里必须断言「不带方括号」：`summary()` 给独立提示行输出的原值是
        // `[语音 30秒]`（为了在一整句话里跳出来），而用户明确淘汰了带方括号的第 1 种
        // 格式（原话：方括号像系统占位符）。所以 badgeText 会剥掉外层方括号。
        assertEquals(
            "已阻止撤回 · 语音 30秒",
            HintTextLogic.badgeText(HintTextLogic.TYPE_VOICE, "wxid_abc:30000:0"),
        )
        // 反向对照：summary 本身仍是带括号的（不许因为改角标把独立行文案改坏）
        assertEquals(
            "[语音 30秒]",
            HintTextLogic.summary(HintTextLogic.TYPE_VOICE, "wxid_abc:30000:0"),
        )
    }

    @Test
    fun badgeTextWithContent() {
        assertEquals("已阻止撤回 · 你好", HintTextLogic.badgeText(HintTextLogic.TYPE_TEXT, "你好"))
        // 非文本消息：summary 给 `[图片]`，角标剥成 `图片`
        assertEquals("已阻止撤回 · 图片", HintTextLogic.badgeText(HintTextLogic.TYPE_IMAGE, null))
        assertEquals("已阻止撤回 · 视频", HintTextLogic.badgeText(HintTextLogic.TYPE_VIDEO, null))
        assertEquals("已阻止撤回 · 名片", HintTextLogic.badgeText(HintTextLogic.TYPE_CARD, null))
    }

    @Test
    fun badgeStripsOnlyOuterBrackets() {
        // `[文件 report.docx]` → `文件 report.docx`
        val xml = "<msg><appmsg><type>6</type><title>report.docx</title></appmsg></msg>"
        assertEquals("已阻止撤回 · 文件 report.docx", HintTextLogic.badgeText(HintTextLogic.TYPE_APP, xml))
        // 内层括号不动：`[引用 [图片]]` 只剥一层 → `引用 [图片]`
        val quote =
            "<msg><appmsg><type>57</type><title>回复正文</title>" +
                "<refermsg><type>3</type><content></content></refermsg></appmsg></msg>"
        val s = HintTextLogic.badgeText(HintTextLogic.TYPE_APP, quote)
        assertTrue("实际=「$s」", s.startsWith("已阻止撤回 · "))
        assertFalse("不该出现双层括号：$s", s.contains("[[") || s.endsWith("]]"))
    }

    /**
     * ⚠️ 摘要为空时必须退化成纯前缀，**不能**输出 `已阻止撤回 · ` ——
     * 末尾悬空的分隔符在界面上看着像渲染失败。
     */
    @Test
    fun badgeEmptySummaryDegrades() {
        assertEquals("已阻止撤回", HintTextLogic.badgeText(HintTextLogic.TYPE_SYSTEM, null))
        assertEquals("已阻止撤回", HintTextLogic.badgeText(HintTextLogic.TYPE_TEXT, ""))
        assertEquals("已阻止撤回", HintTextLogic.badgeText(HintTextLogic.TYPE_TEXT, "   "))
        assertEquals("已阻止撤回", HintTextLogic.badgeText(HintTextLogic.TYPE_TEXT, null))
    }

    @Test
    fun badgeNeverTrailingSeparator() {
        // 穷举所有已知 type + 各种空内容，断言永不出现悬空分隔符
        val types = intArrayOf(
            HintTextLogic.TYPE_TEXT, HintTextLogic.TYPE_IMAGE, HintTextLogic.TYPE_VOICE,
            HintTextLogic.TYPE_CARD, HintTextLogic.TYPE_VIDEO, HintTextLogic.TYPE_EMOJI,
            HintTextLogic.TYPE_LOCATION, HintTextLogic.TYPE_APP, HintTextLogic.TYPE_SYSTEM,
            -1, 0, 99999,
        )
        for (t in types) {
            for (c in arrayOf(null, "", "   ")) {
                val s = HintTextLogic.badgeText(t, c)
                assertTrue("type=$t content=$c => 「$s」有悬空分隔符", !s.endsWith(" · "))
                assertTrue("type=$t content=$c => 「$s」为空", s.isNotBlank())
                assertTrue("type=$t content=$c => 「$s」缺前缀", s.startsWith("已阻止撤回"))
            }
        }
    }
}
