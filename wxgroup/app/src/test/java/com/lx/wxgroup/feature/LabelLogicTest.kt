package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LabelLogic] 的判据回归测试。
 *
 * ⚠️ 真实分隔符**还没取证**（见 `docs/标签数据源取证方案.md`），所以这里的用例
 * 用的是**候选分隔符集合**，覆盖的是「拿到串之后怎么切、未知 id 怎么处理」。
 * 真机抓到真实值后：`LabelLogic.SEPARATORS` 收敛成单一常量 + 这里补一条对应用例。
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
    fun trimsAndDropsEmptySegments() {
        // 前后空白、连续分隔符（微信写 ",1,2," 这种首尾带分隔符很常见）
        assertEquals(listOf("1", "2"), LabelLogic.splitIds(" ,1,,2, "))
    }

    @Test
    fun dedupKeepsFirstOrder() {
        assertEquals(listOf("7", "3", "9"), LabelLogic.splitIds("7,3,7,9,3"))
    }

    /**
     * 制表与换行也是分隔符（SEPARATORS 里列了，但一直没人测）。
     * 微信把整串按行写、或用 `\t` 分隔都是可能的，漏了这两个就会把整串当成一个 id。
     */
    @Test
    fun tabAndNewlineAlsoSplit() {
        assertEquals(listOf("1", "2", "3"), LabelLogic.splitIds("1\t2\t3"))
        assertEquals(listOf("1", "2"), LabelLogic.splitIds("1\r\n2"))
        assertEquals(listOf("1", "2"), LabelLogic.splitIds("1\n2\r"))
        // 混排：逗号 + 换行 + 制表一起出现
        assertEquals(listOf("1", "2", "3"), LabelLogic.splitIds("1,\t2\n3"))
        // 前后包换行（微信把整串包了空白的情况）
        assertEquals(listOf("12"), LabelLogic.splitIds("\n 12 \t"))
    }

    /**
     * 超长串（200+ id）不炸且结果正确：一次通讯录同步可能灌进几百个 id，
     * 顺带确认去重后条数对得上、顺序还是首次出现的顺序。
     */
    @Test
    fun longStringHoldsUp() {
        val raw = (1..250).joinToString(",") { it.toString() }
        val ids = LabelLogic.splitIds(raw)
        assertEquals(250, ids.size)
        assertEquals("1", ids.first())
        assertEquals("250", ids.last())

        // 250 个后面再重复一遍 → 去重后仍是 250，且顺序不变
        val dup = LabelLogic.splitIds("$raw,$raw")
        assertEquals(250, dup.size)
        assertEquals("1", dup.first())
        assertEquals("250", dup.last())

        // 前后各塞一串空段也不该影响
        assertEquals(250, LabelLogic.splitIds(",,,$raw,,,").size)
    }

    // ---------------------------------------------------------------- 形状检查

    @Test
    fun plausibleIdRejectsSeparators() {
        assertTrue(LabelLogic.isPlausibleId("12345"))
        assertFalse(LabelLogic.isPlausibleId(""))
        assertFalse(LabelLogic.isPlausibleId("1,2"))
        assertFalse(LabelLogic.isPlausibleId(" "))
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
}
