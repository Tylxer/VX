package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LabelLogic] 的判据回归测试。
 *
 * ⚠️ `contactLabelIds` 的真实分隔符仍待下一轮真机确认，所以这里锁住当前的兼容解析：
 * 逗号、首尾/连续分隔符、空白、中文顿号都必须可用。真机拿到原始串后再收敛
 * `LabelLogic.SEPARATORS`，并保留真实形态的回归用例。
 */
class LabelLogicTest {

    private fun indexOf(vararg pairs: Pair<String, String?>) =
        LabelLogic.Index().apply { putAll(mapOf(*pairs)) }

    // ---------------------------------------------------------------- 切分

    @Test
    fun emptyAndBlankGiveNoIds() {
        assertEquals(emptyList<String>(), LabelLogic.splitIds(null))
        assertEquals(emptyList<String>(), LabelLogic.splitIds(""))
        assertEquals(emptyList<String>(), LabelLogic.splitIds("   "))
        assertEquals(emptyList<String>(), LabelLogic.splitIds(",,"))
        assertEquals(emptyList<String>(), LabelLogic.splitIds("，、"))
    }

    @Test
    fun singleId() {
        assertEquals(listOf("12"), LabelLogic.splitIds("12"))
        assertEquals(listOf("12"), LabelLogic.splitIds(" 12 "))
    }

    @Test
    fun multiIdAcrossCandidateSeparators() {
        // 逗号（最可能）、分号、竖线、空格都要能切
        assertEquals(listOf("1", "2", "3"), LabelLogic.splitIds("1,2,3"))
        assertEquals(listOf("1", "2", "3"), LabelLogic.splitIds("1;2;3"))
        assertEquals(listOf("1", "2", "3"), LabelLogic.splitIds("1|2|3"))
        assertEquals(listOf("1", "2", "3"), LabelLogic.splitIds("1 2 3"))
        // 微信很可能在逗号后面带空格
        assertEquals(listOf("1", "2", "3"), LabelLogic.splitIds("1, 2,3"))
    }

    @Test
    fun requiredCompatibleShapes() {
        assertEquals(listOf("2", "3", "4"), LabelLogic.splitIds("2,3,4"))
        assertEquals(listOf("2", "3"), LabelLogic.splitIds(",2,3,"))
        assertEquals(listOf("2", "3"), LabelLogic.splitIds("2 3"))
        assertEquals(listOf("2", "3"), LabelLogic.splitIds("2、3"))
        assertEquals(listOf("2", "3"), LabelLogic.splitIds("，，2、、、3，，"))
    }

    @Test
    fun trimsAndDropsEmptySegments() {
        // 前后空白、连续分隔符（微信写 ",1,2," 这种首尾带分隔符很常见）
        assertEquals(listOf("1", "2"), LabelLogic.splitIds(" ,1,,2, "))
    }

    @Test
    fun dedupKeepsFirstOrder() {
        assertEquals(listOf("7", "3", "9"), LabelLogic.splitIds("7,3,7,9,3"))
    }

    // ---------------------------------------------------------------- 形状检查

    @Test
    fun plausibleIdRejectsSeparators() {
        assertTrue(LabelLogic.isPlausibleId("12345"))
        assertFalse(LabelLogic.isPlausibleId(""))
        assertFalse(LabelLogic.isPlausibleId("1,2"))
        assertFalse(LabelLogic.isPlausibleId(" "))
        assertFalse(LabelLogic.isPlausibleId("1、2"))
    }

    @Test
    fun indexReplaceAndSnapshot() {
        val ix = indexOf("1" to "同事")
        ix.replaceAll(mapOf("2" to "家人", "3" to null))
        assertFalse(ix.has("1"))
        assertEquals(2, ix.size())
        assertEquals("家人", ix.nameOf("2"))
        assertTrue(ix.has("3"))
        assertNull(ix.nameOf("3"))
        assertEquals(mapOf("2" to "家人", "3" to null), ix.snapshot())
    }

    // ---------------------------------------------------------------- id → 名字

    @Test
    fun unknownIdIsNotAnEmptyName() {
        val ix = indexOf("1" to "同事")
        // 未知 id：name 为 null，且 has() 必须是 false —— 两者要能分开
        assertNull(ix.nameOf("999"))
        assertFalse(ix.has("999"))
        // 已知 id 但名字为空：has() 为真，name 为 null（三态的关键是 has）
        ix.put("2", null)
        assertTrue(ix.has("2"))
        assertNull(ix.nameOf("2"))
        assertEquals(2, ix.size())
    }

    @Test
    fun namesOfDropsUnknownIds() {
        val ix = indexOf("1" to "同事", "2" to "家人")
        // 3 未收录 → 只返回已知的两个，绝不返回 null 占位
        assertEquals(listOf("同事", "家人"), LabelLogic.namesOf("1,2,3", ix))
        assertEquals(emptyList<String>(), LabelLogic.namesOf("", ix))
        assertEquals(emptyList<String>(), LabelLogic.namesOf(null, ix))
        assertEquals(emptyList<String>(), LabelLogic.namesOf("999", ix))
    }

    @Test
    fun resolveKeepsUnknownForDiagnostics() {
        val ix = indexOf("1" to "同事")
        val r = LabelLogic.resolve("1,999", ix)
        assertEquals(2, r.size)
        assertEquals(LabelLogic.Resolved("1", "同事"), r[0])
        assertEquals(LabelLogic.Resolved("999", null), r[1])
        assertEquals(1, LabelLogic.unknownCount("1,999", ix))
        assertEquals(0, LabelLogic.unknownCount("1", ix))
        // 空串不产生任何 Resolved（空串不是有效 id）
        assertEquals(0, LabelLogic.unknownCount("", ix))
    }

    @Test
    fun orderFollowsTheRawString() {
        val ix = indexOf("1" to "同事", "2" to "家人", "3" to "客户")
        assertEquals(listOf("客户", "同事"), LabelLogic.namesOf("3,1", ix))
    }

    /** 未知 id 在 namesOf 中被丢弃，但在 resolve / unknownCount 中必须可见（取数链断了要留痕） */
    @Test
    fun knownButEmptyNameIsNotDroppedByResolve() {
        val ix = indexOf("2" to null, "3" to "浙医药")
        assertEquals(listOf("浙医药"), LabelLogic.namesOf("2,3", ix))
        assertEquals(2, LabelLogic.resolve("2,3", ix).size)
        assertEquals(1, LabelLogic.unknownCount("2,3", ix))
    }
}
