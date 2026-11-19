package com.lx.wxgroup.feature

import com.lx.wxgroup.data.HintTextLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R73-wxid 回归锁：转发正文不带群聊「发送者 wxid:\n」前缀。
 *
 * 真机铁证（2026-11-20，路过人间会话最新一条我方发出消息 msgId=19725726）：
 * `content` hex = 77786964...3A0A... = `wxid_jv8035ypcigc11:\n人民币暴跌明天大a肯定红`（33 字符）。
 * 群聊消息 h9 content 自带该前缀（私聊没有），原生转发与降级重发都读 snap.text，
 * 若原样发出对端就会看到一串 wxid。修法：snapshotOf 对最终 text 复用 R30 已上线的
 * [HintTextLogic.stripSender] 剥前缀。
 */
class ForwardTextStripTest {

    // ---------------------------------------------------------- 1) 真机样本行为锁

    @Test
    fun stripsRealGroupWxidPrefix() {
        val raw = "wxid_jv8035ypcigc11:\n人民币暴跌明天大a肯定红" // 真机 content，33 字符
        assertEquals(33, raw.length)
        val stripped = HintTextLogic.stripSender(raw)
        assertEquals("人民币暴跌明天大a肯定红", stripped)
        assertEquals(12, stripped.length)
        assertFalse("剥离后不得残留 wxid", stripped.contains("wxid"))
    }

    @Test
    fun privateChatTextUnchanged() {
        // 私聊文本没有前缀，stripSender 必须原样返回
        val text = "人民币暴跌明天大a肯定红"
        assertEquals(text, HintTextLogic.stripSender(text))
    }

    @Test
    fun doesNotStripPlainColonPrefix() {
        // 用户真实以 abc: 开头的正文不能被误剥（stripSender 判据收紧到 wxid_ 字面量）
        assertEquals("abc: 你好", HintTextLogic.stripSender("abc: 你好"))
        assertEquals("看 wxid_abc: 这个", HintTextLogic.stripSender("看 wxid_abc: 这个"))
    }

    // ---------------------------------------------------------- 2) 生产落点源码断言

    @Test
    fun snapshotOfAppliesStripSender() {
        val src = source()
        val body = functionBody("private fun snapshotOf")
        assertTrue(
            "snapshotOf 必须对最终 text 应用 stripSender：实际=($body)",
            "HintTextLogic.stripSender(rawText)" in body
        )
        // 落点必须覆盖两条路共用的 snap.text：rawText 经 strip 后才进 Snapshot
        assertTrue("rawText 中间变量应存在", "val rawText =" in body)
        assertTrue(
            "Snapshot(text=...) 必须用剥离后的 text",
            "text = text" in body
        )
    }

    @Test
    fun stripSenderRegexIsTightened() {
        val src = source("app/src/main/java/com/lx/wxgroup/data/HintTextLogic.kt")
        assertTrue(
            "stripSender 正则必须收紧到 wxid_ 字面量",
            "wxid_[0-9A-Za-z_\\-]+" in src
        )
    }

    // ---------------------------------------------------------- 工具

    private fun source(rel: String = "app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt"): String {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        for (i in 0 until 6) {
            if (dir == null) break
            candidates += File(dir, rel)
            dir = dir.parentFile
        }
        return candidates.firstOrNull { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?: throw AssertionError("找不到源码：$rel（工作目录=${System.getProperty("user.dir")}）")
    }

    private fun functionBody(signature: String): String {
        val lines = source().lineSequence().toList()
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
}
