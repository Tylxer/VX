package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnsDeletedTagLogic] 的回归测试（R30 §6.1「时间行追加 `[删除已拦截]`」）。
 *
 * ## 为什么必须有
 * 标记是追加在一个**复用**的 `TextView` 上的。判据错了会出两类界面故障：
 * - **重复**（hook 重入 / 再次 bind 同一项）⇒ `[删除已拦截] [删除已拦截]`；
 * - **串味**（复用残留）⇒ 普通动态被标成已删，看起来像「防删除功能乱标」。
 * 两者都只在**滚动之后**出现，真机复现代价高 ⇒ 在 JVM 里先锁死。
 *
 * ## R35-6B 补强
 * 本轮补的是**边界**：`snsIdOf` 的形状闸门、`isTagged` 的 0/负数、`appendTag` 的幂等、
 * `parseSnsId` 的符号与超长、`brief` 的截断与 UTF-16 长度、`[isTimeLike]` 的整串匹配收紧。
 * 每条断言都带中文 message，失败时不用翻源码就知道破的是哪条语义。
 */
class SnsDeletedTagLogicTest {

    // ---------------------------------------------------------------- 文案追加

    @Test
    fun appendsMarkAfterNormalText() {
        assertEquals("10分钟前 [删除已拦截]", SnsDeletedTagLogic.appendTag("10分钟前"))
    }

    @Test
    fun appendsMarkAfterDayText() {
        assertEquals("昨天 [删除已拦截]", SnsDeletedTagLogic.appendTag("昨天"))
    }

    /** 微信自己的文案尾部可能带空格（不同版本/不同语言）⇒ 不能拼出双空格 */
    @Test
    fun trimsTrailingSpaceBeforeAppending() {
        assertEquals("昨天 [删除已拦截]", SnsDeletedTagLogic.appendTag("昨天   "))
    }

    /** 空文案：只留标记，且**不能有前导空格**（否则 TextView 里会看到缩进） */
    @Test
    fun emptyBecomesMarkOnly() {
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag(""))
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag("   "))
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag(null))
    }

    @Test
    fun nullHasNoLeadingSpace() {
        assertFalse(SnsDeletedTagLogic.appendTag(null).startsWith(" "))
    }

    // ------------------------------------------------------------------- 幂等

    /** ⛔ 防 hook 重入：第二次追加必须原样返回 */
    @Test
    fun isIdempotentWhenAlreadyTagged() {
        assertEquals(
            "10分钟前 [删除已拦截]",
            SnsDeletedTagLogic.appendTag("10分钟前 [删除已拦截]"),
        )
    }

    /** 连调两次 == 调一次（把「先追加再被重入」这个真实序列走一遍） */
    @Test
    fun appendTwiceEqualsAppendOnce() {
        val once = SnsDeletedTagLogic.appendTag("3分钟前")
        val twice = SnsDeletedTagLogic.appendTag(once)
        assertEquals(once, twice)
        assertEquals(1, Regex(Regex.escape(SnsDeletedTagLogic.MARK)).findAll(twice).count())
    }

    /** 用户内容里恰好含同样文字也不该被当成"已经标过"以外的特殊处理 —— 幂等优先 */
    @Test
    fun taggedInputWithTrailingSpaceStillIdempotent() {
        assertEquals(
            "昨天 [删除已拦截]",
            SnsDeletedTagLogic.appendTag("昨天 [删除已拦截]   "),
        )
    }

    // --------------------------------------------------------------- snsId 集合

    @Test
    fun taggedIdsDropsZeroAndDuplicates() {
        val set = SnsDeletedTagLogic.taggedIds(listOf(0L, 11L, 11L, 22L, 0L))
        assertEquals(setOf(11L, 22L), set)
    }

    @Test
    fun taggedIdsOnEmptyInput() {
        assertTrue(SnsDeletedTagLogic.taggedIds(emptyList()).isEmpty())
    }

    /** `snsId=0` 是无效项，永远不标（与 `pickMissing` 同口径） */
    @Test
    fun zeroSnsIdIsNeverTagged() {
        assertFalse(SnsDeletedTagLogic.isTagged(0L, setOf(0L, 11L)))
    }

    @Test
    fun isTaggedBySetMembership() {
        val tagged = SnsDeletedTagLogic.taggedIds(listOf(11L))
        assertTrue(SnsDeletedTagLogic.isTagged(11L, tagged))
        assertFalse(SnsDeletedTagLogic.isTagged(12L, tagged))
        assertFalse(SnsDeletedTagLogic.isTagged(11L, emptySet()))
    }

    // =============================================================== isTagged 边界

    /**
     * `0` 表示「未知」，不是合法 id —— 即使集合里显式塞了 0 也不能命中。
     * 放宽这条 ⇒ 取不到 id 的动态会被统统打上灰标（**打错位置**）。
     */
    @Test
    fun `id 为 0：即使集合里含 0 也不命中`() {
        assertFalse("0 表示未知，不是合法 id", SnsDeletedTagLogic.isTagged(0L, setOf(0L)))
        assertFalse("0 表示未知，不是合法 id", SnsDeletedTagLogic.isTagged(0L, setOf(0L, -1L, 11L)))
        assertFalse("0 表示未知，不是合法 id", SnsDeletedTagLogic.isTagged(0L, emptySet()))
    }

    /**
     * 负数与 0 **不同待遇**：归档/DB 里的位模式是有符号 Long，高位为 1 就是负数，
     * 那是**合法** id（见 `SnsTagLogicTest.signedAndUnsignedAreSameBits`）。
     */
    @Test
    fun `负数 id 是合法值：命中集合就算标记`() {
        val neg = java.lang.Long.parseUnsignedLong("15033764437568787118")
        assertTrue("负数只是有符号解释，仍是合法 id", SnsDeletedTagLogic.isTagged(neg, setOf(neg)))
        assertTrue("负数必须在 Long.MIN 侧也能命中", SnsDeletedTagLogic.isTagged(-1L, setOf(-1L, 11L)))
        assertFalse("负数不在集合里就不命中", SnsDeletedTagLogic.isTagged(-1L, setOf(11L)))
    }

    @Test
    fun `空集合：任何 id 都不命中`() {
        assertFalse(SnsDeletedTagLogic.isTagged(11L, emptySet()))
        assertFalse(SnsDeletedTagLogic.isTagged(-11L, emptySet()))
        assertFalse(SnsDeletedTagLogic.isTagged(0L, emptySet()))
    }

    // =============================================================== snsIdOf 闸门

    /** 真实样本（8.0.48 抓的），里面同时有 `rowid` / `createTime` 等其它数字字段 */
    private val REAL_INFO = "ImproveInfo(name=贸易商陈健峰, snsID='15033729556253184615', " +
        "ad:false, time:29分钟前 rowid=256862, createTime=1792160219"

    @Test
    fun `真实样本：取到的是 snsID 字段本身`() {
        val id = SnsDeletedTagLogic.snsIdOf(REAL_INFO)
        assertNotNull("真实 ImproveInfo 样本必须能取到 snsID", id)
        assertEquals(
            "取到的必须是 snsID 字段的位模式（无符号写法）",
            "15033729556253184615",
            java.lang.Long.toUnsignedString(id!!),
        )
    }

    @Test
    fun `15 位下边界：可取`() {
        assertEquals(
            "15 位是 ID_RE 的下限，必须能取",
            123456789012345L,
            SnsDeletedTagLogic.snsIdOf("snsID='123456789012345'"),
        )
    }

    @Test
    fun `20 位上边界：可取`() {
        val id = SnsDeletedTagLogic.snsIdOf("snsID='12345678901234567890'")
        assertNotNull("20 位是 ID_RE 的上限，必须能取", id)
        assertEquals(
            "20 位无符号值不能溢出",
            "12345678901234567890",
            java.lang.Long.toUnsignedString(id!!),
        )
    }

    @Test
    fun `14 位：太短，闸门必须挡住`() {
        assertNull(
            "14 位不到 15 位下限，取它会把别的字段误当 snsID",
            SnsDeletedTagLogic.snsIdOf("snsID='12345678901234'"),
        )
    }

    @Test
    fun `没有 snsID 前缀的长数字：不取`() {
        assertNull(
            "定位靠字面量 snsID=，光有 20 位数字不算",
            SnsDeletedTagLogic.snsIdOf("Foo(id=12345678901234567890, rowid=123456789012345678)"),
        )
    }

    @Test
    fun `snsID=0 与 snsID=-1：都不是合法 id`() {
        assertNull("snsID=0 表示未知，位数也不够", SnsDeletedTagLogic.snsIdOf("snsID=0"))
        assertNull("snsID=-1 位数不够", SnsDeletedTagLogic.snsIdOf("snsID=-1"))
        assertNull("snsID= 后面没有数字", SnsDeletedTagLogic.snsIdOf("snsID="))
    }

    /**
     * 误收反例：一个串里同时有别的**长数字**字段（`rowid` / `createTime` / 别的 id）时，
     * 实现靠的是字面量 `snsID=`，不是「找最长的数字串」⇒ 不会抓错。
     */
    @Test
    fun `别的长数字字段不干扰：只认 snsID 字面前缀`() {
        val s = "Foo(rowid=123456789012345678, createTime=1792160219, snsID='15043160246930444808')"
        val id = SnsDeletedTagLogic.snsIdOf(s)
        assertNotNull("snsID= 存在就该取到", id)
        assertEquals(
            "rowid 也是 18 位数字，但不能被当成 snsID",
            "15043160246930444808",
            java.lang.Long.toUnsignedString(id!!),
        )
    }

    @Test
    fun `snsID 后面紧跟非数字：不取`() {
        assertNull(
            "引号后直接是非数字 ⇒ 形状不符",
            SnsDeletedTagLogic.snsIdOf("snsID='x15043160246930444808'"),
        )
    }

    @Test
    fun `20 位全 9：超出无符号 Long 上界，解析失败返回 null`() {
        assertNull(
            "99999999999999999999 > 2^64-1，parseUnsignedLong 会抛，必须吞掉而不是返回错值",
            SnsDeletedTagLogic.snsIdOf("snsID='99999999999999999999'"),
        )
    }

    @Test
    fun `null 与空串：不取`() {
        assertNull(SnsDeletedTagLogic.snsIdOf(null))
        assertNull(SnsDeletedTagLogic.snsIdOf(""))
        assertNull(SnsDeletedTagLogic.snsIdOf("   "))
    }

    /** `toString()` 抛异常的对象不能把打标链路带崩（实现里是 `runCatching`） */
    @Test
    fun `toString 抛异常：吞掉并返回 null`() {
        assertNull("toString 抛异常必须被 runCatching 吞掉", SnsDeletedTagLogic.snsIdOf(Boom()))
    }

    private class Boom {
        override fun toString(): String = throw RuntimeException("boom")
    }

    // -------------------------------------------------- snsIdOf 已发现的疑似缺陷

    /**
     * ⚠️ 疑似缺陷 #1（R35-6B 发现，**不改实现**，留给 SnsDeletedTag 那条 lane 定夺）
     *
     * - 输入：`snsID='123456789012345678901'`（21 位）
     * - 期望：`null`（21 位不可能是 snsID —— 无符号 Long 最多 20 位）
     * - 实际：`12345678901234567890`（正则 `\d{15,20}` 贪婪吃掉前 20 位，`find` 无锚定）
     * - 疑似：`SnsDeletedTagLogic.kt:36` 的 `ID_RE` 缺右侧边界（可加 `(?!\d)` 或 `'?` 收尾）
     * - 危害：拿到一个**看着合法但其实是错的** id ⇒ 打错标签，正是本类 KDoc 要防的形态。
     */
    @Test
    fun `21 位超长 id 应判非法`() {
        assertNull(
            "21 位超过无符号 Long 上限，不能截断后当成合法 id",
            SnsDeletedTagLogic.snsIdOf("snsID='123456789012345678901'"),
        )
    }

    /**
     * ⚠️ 疑似缺陷 #2（与 #1 同源）
     *
     * - 输入：`snsID='1504316024693044480a'`（数字串后面挂了字母）
     * - 期望：`null`（形状校验要求纯数字）
     * - 实际：`1504316024693044480`（正则只取前 19 位数字，尾部的 `a` 被忽略）
     * - 疑似：同 `SnsDeletedTagLogic.kt:36` 的 `ID_RE` 缺右侧边界。
     */
    @Test
    fun `数字串后跟字母应判非法`() {
        assertNull(
            "尾部有非数字 ⇒ 形状校验不通过",
            SnsDeletedTagLogic.snsIdOf("snsID='1504316024693044480a'"),
        )
    }

    /**
     * ⚠️ 疑似缺陷 #3（前缀子串命中）
     *
     * - 输入：`Foo(oldsnsID='111111111111111111', snsID='15043160246930444808')`
     * - 期望：`15043160246930444808`
     * - 实际：`111111111111111111` —— `oldsnsID` 里**含**子串 `snsID`，`find` 取最左 ⇒ 抓到前一个
     * - 疑似：同 `SnsDeletedTagLogic.kt:36`，`ID_RE` 缺左侧词边界（可加 `(?<![A-Za-z])`）
     * - 说明：当前微信的 toString 里没有 `…snsID` 这种字段，**不是现网问题**，
     *   只是正则的隐含假设没被锁住，版本漂移后可能踩到。
     */
    @Test
    fun `字段名尾部匹配 snsID 时不能误收`() {
        val s = "Foo(oldsnsID='111111111111111111', snsID='15043160246930444808')"
        assertEquals(
            "oldsnsID 里的 snsID 子串不应被当成 snsID 字段",
            "15043160246930444808",
            java.lang.Long.toUnsignedString(SnsDeletedTagLogic.snsIdOf(s)!!),
        )
    }

    // =============================================================== parseSnsId

    @Test
    fun `空串与纯空格：都判非法`() {
        assertNull("空串没有数字", SnsDeletedTagLogic.parseSnsId(""))
        assertNull("纯空格不是数字", SnsDeletedTagLogic.parseSnsId("   "))
        assertNull("首尾空格包着数字也要 trim 前先判非法", SnsDeletedTagLogic.parseSnsId(" 123 "))
    }

    @Test
    fun `负号：有符号 15 位可解析`() {
        assertEquals(
            "有符号写法要能吃下（DB/归档是有符号）",
            -123456789012345L,
            SnsDeletedTagLogic.parseSnsId("-123456789012345"),
        )
        assertNull("单独一个负号没有数字", SnsDeletedTagLogic.parseSnsId("-"))
        assertNull("正号写法不吃（与 ID_RE 口径一致）", SnsDeletedTagLogic.parseSnsId("+123456789012345"))
    }

    @Test
    fun `无符号 20 位：解析成同一个位模式`() {
        val v = SnsDeletedTagLogic.parseSnsId("15033764437568787118")
        assertNotNull("20 位无符号值在 2^64-1 以内，可解析", v)
        assertEquals(
            "与有符号写法必须同 bits（否则标签永远打不上）",
            SnsDeletedTagLogic.parseSnsId("-3412979636140764498"),
            v,
        )
        assertEquals("15033764437568787118", java.lang.Long.toUnsignedString(v!!))
    }

    @Test
    fun `超长 21 位：parseSnsId 自己会挡住`() {
        assertNull("超过 20 位直接判非法", SnsDeletedTagLogic.parseSnsId("123456789012345678901"))
        assertNull("20 位全 9 超 2^64-1，解析抛异常 ⇒ null", SnsDeletedTagLogic.parseSnsId("99999999999999999999"))
    }

    @Test
    fun `前导 0：位数以内按数值解析`() {
        assertEquals(
            "前导 0 不影响数值",
            150431602469304448L,
            SnsDeletedTagLogic.parseSnsId("0150431602469304448"),
        )
        assertNull(
            "前导 0 把长度顶到 21 位 ⇒ 判非法",
            SnsDeletedTagLogic.parseSnsId("015043160246930444808"),
        )
    }

    @Test
    fun `混进非数字字符：判非法`() {
        assertNull(SnsDeletedTagLogic.parseSnsId("123abc4567890123"))
        assertNull(SnsDeletedTagLogic.parseSnsId("sns_table_256863"))
        assertNull(SnsDeletedTagLogic.parseSnsId("15043160246930444808L"))
    }

    // =============================================================== appendTag

    @Test
    fun `连调两次等于调一次（换一种输入复验）`() {
        val once = SnsDeletedTagLogic.appendTag("昨天")
        val twice = SnsDeletedTagLogic.appendTag(once)
        assertEquals("重入不能叠出第二个标记", once, twice)
        assertEquals("昨天 [删除已拦截]", twice)
    }

    @Test
    fun `空串 纯空白 null：都只剩标记`() {
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag(""))
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag("   "))
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag("\n\t "))
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag(null))
    }

    @Test
    fun `原文就等于标记：原样返回，不再追加`() {
        assertEquals(
            "text 本身 == MARK 时不能拼成两个",
            SnsDeletedTagLogic.MARK,
            SnsDeletedTagLogic.appendTag(SnsDeletedTagLogic.MARK),
        )
    }

    /**
     * 幂等闸是 `contains` 而不是 `endsWith` ⇒ 标记在**中间**时同样原样返回。
     * 这不是理想语义（真遇到这种串标记位置会怪），但**重复追加更糟**，先把现状锁住。
     */
    @Test
    fun `标记出现在中间：也按已标记处理，不重复追加`() {
        assertEquals(
            "contains 判定 ⇒ 中间命中同样原样返回",
            "前缀 [删除已拦截] 后缀",
            SnsDeletedTagLogic.appendTag("前缀 [删除已拦截] 后缀"),
        )
    }

    /** `trimEnd` 只削尾部 ⇒ 前导空白会被**保留**（现状锁定，别误改成 trim） */
    @Test
    fun `前导空白保留 尾部空白削掉`() {
        assertEquals(
            "实现是 trimEnd，前导空格不该被吃掉",
            " [删除已拦截]",
            SnsDeletedTagLogic.appendTag(" [删除已拦截] "),
        )
        assertEquals(
            "换行也算尾部空白，要削掉",
            "昨天 [删除已拦截]",
            SnsDeletedTagLogic.appendTag("昨天\n"),
        )
    }

    @Test
    fun `普通文案：中间只隔一个空格`() {
        assertEquals("3小时前 [删除已拦截]", SnsDeletedTagLogic.appendTag("3小时前"))
        assertFalse(
            "不能出现双空格",
            SnsDeletedTagLogic.appendTag("3小时前   ").contains("  "),
        )
    }

    // =============================================================== brief

    @Test
    fun `超长：截到 max 并补省略号`() {
        val out = SnsDeletedTagLogic.brief("x".repeat(100))
        assertEquals("80 + 3 = 83", 83, out.length)
        assertTrue("必须以省略号结尾", out.endsWith("..."))
        assertEquals("保留的是前 80 个字符", "x".repeat(80) + "...", out)
    }

    @Test
    fun `长度正好等于 max：不截断`() {
        assertEquals("不超过就不动，也不该加省略号", "abc", SnsDeletedTagLogic.brief("abc", 3))
        assertEquals("abcd", SnsDeletedTagLogic.brief("abcd", 4))
        assertEquals("abc...", SnsDeletedTagLogic.brief("abcd", 3))
    }

    @Test
    fun `max 为 0：只剩省略号，但空串仍是空串`() {
        assertEquals("3 > 0 ⇒ 截到 0 个字符再加省略号", "...", SnsDeletedTagLogic.brief("abc", 0))
        assertEquals("空串长度 0 <= 0 ⇒ 走不截断分支", "", SnsDeletedTagLogic.brief("", 0))
    }

    @Test
    fun `空串与 null：空串原样 null 出 null 字样`() {
        assertEquals("空串不该变成省略号", "", SnsDeletedTagLogic.brief(""))
        assertEquals("null 走 `s ?: \"null\"` 分支", "null", SnsDeletedTagLogic.brief(null))
    }

    @Test
    fun `换行：短串原样保留，超长时可能截在换行之后`() {
        assertEquals("a\nb", SnsDeletedTagLogic.brief("a\nb"))
        val long = "第一行\n第二行" + "x".repeat(50)
        val out = SnsDeletedTagLogic.brief(long, 20)
        assertEquals(23, out.length)
        assertTrue("换行符不能被当成截断边界", out.contains("\n"))
    }

    /**
     * 中文：截断按 **UTF-16 code unit** 数，不是字点数，更不是显示宽度。
     * 10 个汉字 = 10 个 code unit ⇒ `max=10` 正好切 10 个**字**。
     */
    @Test
    fun `中文：按 UTF-16 长度截断`() {
        val src = "朋友圈动态内容展示".repeat(5) // 9 字 × 5 = 45 个 code unit
        val out = SnsDeletedTagLogic.brief(src, 10)
        assertEquals("10 个 code unit + 3 个省略号 = 13", 13, out.length)
        assertEquals("朋友圈动态内容展示朋...", out)
        assertEquals("整段没超过 max 时一个字都不该少", src, SnsDeletedTagLogic.brief(src, 45))
    }

    /**
     * Emoji 是**代理对**（2 个 code unit）。`max=3` 会切在第二个 emoji 的**中间**
     * （留下半个代理对）。这里只锁「不崩 + 长度对」，**不锁字形** ——
     * 假设「长度 == 显示宽度」是错的，但假设「截断不越界」是对的。
     */
    @Test
    fun `emoji：按代理对截断，只锁长度不锁字形`() {
        val face = "\uD83D\uDE00" // 1 个 emoji = 2 个 code unit
        val src = face.repeat(10) // 20 个 code unit
        assertEquals("先确认 emoji 是代理对，否则这条用例没意义", 2, face.length)
        val out = SnsDeletedTagLogic.brief(src, 3)
        assertEquals("截 3 个 unit（1 个完整 emoji + 半个高代理）+ 3 个省略号 = 6", 6, out.length)
        assertTrue("第一个 emoji 必须完整", out.startsWith(face))
        assertTrue(out.endsWith("..."))
    }

    // =============================================================== isTimeLike

    /**
     * 标准相对时间：**整串**命中才放行。这是收紧后的核心语义。
     */
    @Test
    fun `相对时间说法：整串命中才算时间行`() {
        for (s in listOf("刚刚", "10秒前", "10分钟前", "3小时前", "2天前", "3周前", "2个月前", "昨天", "前天")) {
            assertTrue("「$s」是标准相对时间文案，必须判为时间行", SnsDeletedTagLogic.isTimeLike(s))
        }
    }

    @Test
    fun `绝对日期：中文与数字两种写法都算`() {
        for (s in listOf("10月28日", "2026年10月28日", "2026-10-28", "2026/10/28")) {
            assertTrue("「$s」是绝对日期，必须判为时间行", SnsDeletedTagLogic.isTimeLike(s))
        }
    }

    /**
     * ⛔ 视频时长徽章。它跟「今天发的动态显示 `HH:MM`」字面完全无法区分 ⇒ 一律否掉。
     * 取舍：漏掉今天那几条（顶多不打标）好过把灰标打到时长徽章上（**打错位置**）。
     */
    @Test
    fun `视频时长徽章：一律不算时间行`() {
        for (s in listOf("00:15", "1:23", "12:34", "03:20")) {
            assertFalse(
                "「$s」是视频时长徽章，与今天的 HH:MM 无法区分，只能一律否掉",
                SnsDeletedTagLogic.isTimeLike(s),
            )
        }
    }

    @Test
    fun `以冒号开头的残串：不算`() {
        assertFalse(":30 是残串/分隔符，不是时间行", SnsDeletedTagLogic.isTimeLike(":30"))
        assertFalse(SnsDeletedTagLogic.isTimeLike("30:"))
    }

    @Test
    fun `空串 纯空白 null：都不算`() {
        assertFalse(SnsDeletedTagLogic.isTimeLike(""))
        assertFalse(SnsDeletedTagLogic.isTimeLike("   "))
        assertFalse(SnsDeletedTagLogic.isTimeLike("\n\t"))
        assertFalse(SnsDeletedTagLogic.isTimeLike(null))
    }

    /** 200 字正文：朋友圈时间行不可能这么长 ⇒ 长度闸门 + 整串匹配双保险 */
    @Test
    fun `超长正文：不算时间行`() {
        val body = ("昨天，我和朋友去看了一场电影，散场后又吃了夜宵，回家已经很晚了").repeat(4)
        assertTrue("用例本身要先够长，否则测不到闸门", body.length > 100)
        assertFalse("正文再怎么含时间词也不该当成时间行", SnsDeletedTagLogic.isTimeLike(body))
    }

    /**
     * 长度闸门（[TIME_LIKE_MAX = 20]）的**专属**守门用例：
     * 形状上完全像相对时间（`\d+分钟前`），只是数字长到离谱。
     * 只靠正则挡不住（正则对数字位数没有上限）⇒ 删掉长度闸门这条就会变红。
     */
    @Test
    fun `形状像相对时间但超 20 字：被长度闸门挡住`() {
        val s = "12345678901234567890分钟前"
        assertEquals("用例长度必须真的超过闸门", 23, s.length)
        assertFalse("时间行不会是 23 字，长度闸门必须挡住", SnsDeletedTagLogic.isTimeLike(s))
    }

    /**
     * `28`：纯数字，既不是时长形态（没冒号）也不是任何时间说法 ⇒ false。
     * 锁住它是为了挡住「放宽成『含数字就算』」这种退化。
     */
    @Test
    fun `纯数字 28：既不是时长也不是时间说法`() {
        assertFalse("28 只是个数字，没有任何时间语义", SnsDeletedTagLogic.isTimeLike("28"))
        assertFalse(SnsDeletedTagLogic.isTimeLike("2026"))
    }

    /**
     * 混合形态 `昨天 10:30` —— **本测试断言 false**，理由：
     * 1. 判据是**整串**匹配，`昨天 10:30` 既不是相对时间也不是日期样式 ⇒ 天然 false；
     * 2. 语义上也站得住：朋友圈时间行的展示是「昨天」「10月28日」「3小时前」这类**单一**说法，
     *    `昨天 10:30` 这种拼法出现在评论/详情页，不是列表页时间行；
     * 3. 取向一致 —— 认不出来就**不打标**（漏），也好过打到别的 View 上（**错**）。
     * ⚠️ 若将来确认列表页真的会显示这种拼法，应该改的是**这条用例 + 正则**，
     *    而不是把整串匹配退回去（退回「包含即可」会把 `昨天我去看了…` 这类正文全放进来）。
     */
    @Test
    fun `混合形态 昨天 10点30：按整串匹配判 false`() {
        assertFalse(
            "「昨天 10:30」不是整串时间说法；认不出来就不打标，好过打错位置",
            SnsDeletedTagLogic.isTimeLike("昨天 10:30"),
        )
    }

    /** 只是「包含」时间词的正文 —— 老判据（包含即可）会把灰标打到正文上 */
    @Test
    fun `正文中包含时间词：不算时间行`() {
        assertFalse("「刚刚到家」是正文", SnsDeletedTagLogic.isTimeLike("刚刚到家"))
        assertFalse("「昨天我去看了电影」是正文", SnsDeletedTagLogic.isTimeLike("昨天我去看了电影"))
        assertFalse("「3小时前发布的通知」是正文", SnsDeletedTagLogic.isTimeLike("3小时前发布的通知"))
    }

    @Test
    fun `首尾空白：trim 之后照常判定`() {
        assertTrue("时间行文案可能带首尾空格", SnsDeletedTagLogic.isTimeLike("  昨天  "))
        assertTrue(SnsDeletedTagLogic.isTimeLike(" 10月28日"))
        assertFalse("trim 之后是时长徽章", SnsDeletedTagLogic.isTimeLike(" 00:15 "))
        assertFalse("trim 之后是空串", SnsDeletedTagLogic.isTimeLike("   "))
    }

    // =========================================================== nameOf（R37-1C）

    /**
     * `nameOf` 提供的是「View ↔ 数据对账」唯一的锚。
     *
     * 为什么只能用作者名：时间文本在绑定回调那一刻通常**还是空串**（渲染是 suspend 的），
     * 拿它当锚等于没有锚（R37 根因里 `missedTime` 涨到 43 就是从这儿来的）。
     * 取不到名字时返回 `null`，调用方按「不可利用」处理 ⇒ 比返回一个错名字安全得多。
     */

    @Test
    fun `真实样本：取到的是 ImproveInfo 的 name 字段`() {
        assertEquals(
            "真实样本必须能取到作者名，否则整条对账链路没有锚",
            "贸易商陈健峰",
            SnsDeletedTagLogic.nameOf(REAL_INFO),
        )
    }

    @Test
    fun `null 与空串：都取不到`() {
        assertNull("null 对象直接返回 null", SnsDeletedTagLogic.nameOf(null))
        assertNull("空串里没有 name=", SnsDeletedTagLogic.nameOf(""))
        assertNull("纯空格里没有 name=", SnsDeletedTagLogic.nameOf("   "))
    }

    @Test
    fun `没有 name 字段的对象：取不到`() {
        assertNull(
            "只有 snsID / rowid 这类字段时不该硬凑一个名字出来",
            SnsDeletedTagLogic.nameOf("Foo(id=1, snsID='123456789012345678', rowid=256862)"),
        )
    }

    /**
     * ⛔ 前缀闸门：`nickname=` / `displayname=` 是**另一个字段**，同样以 `name=` 结尾。
     * 让它们抢先命中 ⇒ 拿去比对的名字不再代表 `ImproveInfo.name` ⇒ 对账判据失真；
     * 失真方向是随机的，可能一个标签都不打，也可能**相邻动态全过**。
     */
    @Test
    fun `nickname 字段不能被当成 name`() {
        assertNull(
            "nickname= 里的 name 前缀是字母，不该被当成独立 name 字段",
            SnsDeletedTagLogic.nameOf("Foo(nickname=李四, id=1)"),
        )
        assertNull(
            "displayname= 同理",
            SnsDeletedTagLogic.nameOf("Foo(displayname=王五, id=1)"),
        )
    }

    @Test
    fun `同时有 nickname 与 name：取真正的 name`() {
        assertEquals(
            "真正的 name 字段带前导空格，必须优先于 nickname",
            "赵六",
            SnsDeletedTagLogic.nameOf("Foo(nickname=李四, name=赵六, id=1)"),
        )
    }

    @Test
    fun `引号写法与尾随空格：取到并 trim`() {
        assertEquals(
            "name='李四' 的引号要被吃掉",
            "李四",
            SnsDeletedTagLogic.nameOf("ImproveInfo(name='李四', snsID='123456789012345678')"),
        )
        assertEquals(
            "值后面的空格要 trim 掉，否则跟界面文本比对时永远不相等",
            "李四",
            SnsDeletedTagLogic.nameOf("ImproveInfo(name=李四 , snsID='123456789012345678')"),
        )
        assertEquals(
            "等号两侧有空格也要能吃下",
            "李四",
            SnsDeletedTagLogic.nameOf("ImproveInfo(name = 李四, snsID='123456789012345678')"),
        )
    }

    @Test
    fun `name 后面直接是逗号：取不到，不能返回空串`() {
        assertNull(
            "空名字必须落到 null（不可利用），返回空串会被当成合法名字",
            SnsDeletedTagLogic.nameOf("ImproveInfo(name=, snsID='123456789012345678')"),
        )
    }

    /** 英文昵称同理 —— 前缀闸门按「前一个字符是不是字母」判，不是按值的内容判 */
    @Test
    fun `英文昵称：正常取到`() {
        assertEquals("John", SnsDeletedTagLogic.nameOf("ImproveInfo(name=John, snsID='123456789012345678')"))
    }

    /** 反射/toString 可能抛 ⇒ 必须吞掉，不能把打标链路带崩（实现里是 `runCatching`） */
    @Test
    fun `nameOf 遇到 toString 抛异常：吞掉并返回 null`() {
        assertNull("toString 抛异常必须被 runCatching 吞掉", SnsDeletedTagLogic.nameOf(Boom()))
    }

    /** 任意对象（没有 name 字段、且类型完全无关）都不能抛异常，只返回 null */
    @Test
    fun `任意对象：不抛异常，取不到就返回 null`() {
        assertNull(SnsDeletedTagLogic.nameOf(42))
        assertNull(SnsDeletedTagLogic.nameOf(listOf(1, 2, 3)))
        assertNull(SnsDeletedTagLogic.nameOf("plain text without any field"))
    }

    /** 名字上限 32 字：超长时截断（宁可截短也不能让正则吃掉后面的字段） */
    @Test
    fun `超长名字：被 32 字上限截断`() {
        val long = "x".repeat(40)
        val got = SnsDeletedTagLogic.nameOf("ImproveInfo(name=$long, snsID='123456789012345678')")
        assertNotNull("40 字名字应该能取到（截断后）", got)
        assertEquals("超过 32 字的部分必须被截掉", 32, got!!.length)
        assertEquals("截断保留的是前 32 个字", "x".repeat(32), got)
    }

    /**
     * 现状锁定：正则要求 `name` 前面**有一个非字母字符** ⇒ 串首裸 `name=…`（前面没有字符）
     * 取不到。真机样本里 name 前面总有 `(` 或空格，所以不影响现网。
     * ⚠️ 别为了「修」这条把前缀删掉 —— 删了就会让 `nickname=` 抢先命中（见上一条用例）。
     */
    @Test
    fun `现状锁定：串首裸 name 无前导字符时取不到`() {
        assertNull(
            "裸 name= 前面没有字符，不满足前缀闸门（真机样本里 name 前总有 ( 或空格）",
            SnsDeletedTagLogic.nameOf("name=李四"),
        )
        assertEquals("前面有空格就正常了", "李四", SnsDeletedTagLogic.nameOf(" name=李四"))
    }

    // ========================================================= matchName（R37-1C）

    /**
     * `matchName` 是「候选数据 ↔ itemView 子树可见文本」的**唯一**校验闸门。
     *
     * 本轮（R37-1）给它加了**正向判定的长度上界**。为什么必须有：
     * `visibleTexts` 收的是子树里**所有**可见 TextView 的文本，不区分角色 —— 正文、转发来源、
     * 点赞文案都在里面。没有上界时「正文里恰好提到邻居的名字」就会让邻居的候选命中；
     * 若正确候选因**备注名**恒不命中，邻居就成了唯一命中 ⇒ 这一格绑上邻居的 snsId ⇒
     * **这条动态被打错标签**（R31h 同款）。作者昵称 TextView 不会很长，正文会 ⇒ 用上界把
     * 长正文排除在对账语料外。
     */

    /**
     * ⭐ 本轮修的核心形态：正文里提到候选名字。
     * 23 字 > 上界 16 ⇒ 必须 false。放行的话，邻居的 snsId 会被绑到这条动态上 ⇒ **画错**。
     */
    @Test
    fun `长正文提到候选名字：被长度上界挡掉`() {
        val body = "今天和李四一起吃饭，聊了很久关于孩子上学的事情"
        assertEquals("用例自身校验：正文必须够长才测得到上界", 23, body.length)
        assertTrue("用例自身校验：正文必须真的含这个名字", body.contains("李四"))
        assertFalse(
            "23 字正文里提到「李四」不是昵称行，必须被上界挡掉（否则打错标签）",
            SnsDeletedTagLogic.matchName("李四", listOf(body)),
        )
    }

    /** 转发来源（“转发 张三 的动态 …”）同样不是昵称，必须挡掉 */
    @Test
    fun `转发来源提到候选名字：同样被上界挡掉`() {
        val body = "转发 张三 的动态：这是一条很长的朋友圈正文内容"
        assertEquals("用例自身校验：转发文案必须够长才测得到上界", 24, body.length)
        assertFalse(
            "转发来源行提到「张三」不是昵称行，必须被上界挡掉",
            SnsDeletedTagLogic.matchName("张三", listOf(body)),
        )
    }

    /** 200 字正文：上界在极端长度下也要成立 */
    @Test
    fun `200 字正文含名字：被上界挡掉`() {
        val body = "李四" + "内容".repeat(100)
        assertTrue("用例自身校验：必须含名字且远超上界", body.contains("李四") && body.length > 16)
        assertFalse(
            "超长正文提到名字，长度上界必须生效",
            SnsDeletedTagLogic.matchName("李四", listOf(body)),
        )
    }

    @Test
    fun `正常昵称：精确命中`() {
        assertTrue("昵称行就是名字本身", SnsDeletedTagLogic.matchName("李四", listOf("李四")))
        assertTrue("昵称后面跟了别的文案也能命中", SnsDeletedTagLogic.matchName("李四", listOf("李四 昨天")))
        assertTrue("昵称前面加了别的内容也能命中", SnsDeletedTagLogic.matchName("李四", listOf("小明 李四")))
    }

    /**
     * 长度上界**只挡长正文，不挡长昵称**：`n.length + 8` 对 2~3 字的名字太紧，
     * 靠 `NAME_TEXT_MAX = 16` 兜底 ⇒ 8 字前缀（「来自深圳的」）下仍能命中。
     */
    @Test
    fun `昵称带前后缀：仍要命中`() {
        assertTrue(
            "「来自深圳的陈健峰」只有 8 字，界面前缀不该把昵称挡掉",
            SnsDeletedTagLogic.matchName("陈健峰", listOf("来自深圳的陈健峰")),
        )
        assertTrue(
            "数据名比界面名长一截（ImproveInfo.name=贸易商陈健峰）时也要命中",
            SnsDeletedTagLogic.matchName("贸易商陈健峰", listOf("来自深圳的贸易商陈健峰")),
        )
    }

    /**
     * 兜底常量的**专属**守门用例：文本长度 13 > `n.length + 8`（= 11）但 ≤ 16。
     * 只靠 `n.length + 8` 这条会被误排除 ⇒ 删掉 16 兜底这条就会变红。
     */
    @Test
    fun `文本比 name 加 8 更长但不超过 16：靠 NAME_TEXT_MAX 兜底命中`() {
        val t = "来自广东深圳南山区的陈健峰"
        assertEquals("用例自身校验：必须真的超出 n+8 但不超过 16", 13, t.length)
        assertTrue(
            "兜底值 16 就是为了让这种长昵称仍能命中",
            SnsDeletedTagLogic.matchName("陈健峰", listOf(t)),
        )
    }

    @Test
    fun `可见文本为空：不通过`() {
        assertFalse(
            "文本还没渲染出来时必须判不过（退化成「没文本就算过」会整体绕开校验）",
            SnsDeletedTagLogic.matchName("李四", emptyList()),
        )
    }

    @Test
    fun `null 名字与单字名字：都不采信`() {
        assertFalse("null 名字不可利用", SnsDeletedTagLogic.matchName(null, listOf("李四")))
        assertFalse("单字名字不采信", SnsDeletedTagLogic.matchName("李", listOf("李四")))
        assertFalse("空名字不采信", SnsDeletedTagLogic.matchName("", listOf("李四")))
    }

    /** 可见文本只有 1 个字也不采信：太短的碎片会让**任何**候选都能通过 */
    @Test
    fun `单字可见文本：不采信`() {
        assertFalse(
            "1 字碎片不能参与对账（去掉 t.length >= 2 闸门这条就会变红）",
            SnsDeletedTagLogic.matchName("李四", listOf("李")),
        )
    }

    /** 子树里有多个 TextView，其中**一条**短文本命中即可 */
    @Test
    fun `多条可见文本：有一条短文本命中即可`() {
        assertTrue(
            "昵称行与长正文共存时，昵称行那条要能命中",
            SnsDeletedTagLogic.matchName("李四", listOf("李四", "今天和李四一起吃饭，聊了很久关于孩子上学的事情")),
        )
        assertTrue(
            "顺序无关：长正文在前也不影响",
            SnsDeletedTagLogic.matchName("李四", listOf("今天和李四一起吃饭，聊了很久关于孩子上学的事情", "李四")),
        )
    }

    /** 反向包含：界面显示的是**截断/短写**的名字，数据名更长 */
    @Test
    fun `反向包含：文本够长时可用`() {
        assertTrue(
            "界面被 ellipsize 截短成「陈健峰」时也要能对上",
            SnsDeletedTagLogic.matchName("贸易商陈健峰", listOf("陈健峰")),
        )
    }

    /**
     * ⛔ 反向判定的 3 字闸门：`t.length >= 3 && n.contains(t)`。
     * 去掉闸门 ⇒ 一两个字的碎片能让**任何**候选通过 ⇒ 等于没有校验（正是 R37 要防的形态）。
     */
    @Test
    fun `反向包含：文本只有 2 字时不采信`() {
        assertFalse(
            "「健峰」是「陈健峰」的子串，但 2 字碎片不能当证据（3 字闸门）",
            SnsDeletedTagLogic.matchName("陈健峰", listOf("健峰")),
        )
        assertFalse(
            "「李四」是「张三李四」的子串，同理不能采信",
            SnsDeletedTagLogic.matchName("张三李四", listOf("李四")),
        )
        assertTrue(
            "反例：3 字就应当放行，否则反向判定形同虚设",
            SnsDeletedTagLogic.matchName("贸易商陈健峰", listOf("陈健峰")),
        )
    }

    /** 备注名场景：界面显示「李总」而数据名是「李四」⇒ 对不上，宁可不打标 */
    @Test
    fun `备注名与数据名不一致：不命中`() {
        assertFalse(
            "对不上就不打标（漏标好过把邻居的删除状态画到这条上）",
            SnsDeletedTagLogic.matchName("李四", listOf("李总")),
        )
        assertFalse(
            "界面昵称中间插字（李总四）也不是子串 ⇒ 双向包含都救不了，同样不打标",
            SnsDeletedTagLogic.matchName("李四", listOf("李总四")),
        )
    }

    /**
     * ⚠️ 疑似缺陷 #4（R37-1C 发现，**不改实现**，留给 SnsDeletedTag 那条 lane 定夺）
     *
     * - 输入：`matchName("李四", ["今天和李四一起吃饭"])`（9 字）
     * - 任务书期望：`false`（长正文提到名字 ⇒ 应被上界挡掉）
     * - 实际：`true` —— 上界是 `maxOf(n.length + 8, NAME_TEXT_MAX)`，`NAME_TEXT_MAX = 16`
     *   的兜底让 **≤16 字**的正文全部落在上界之内，9 字正文自然也命中
     * - 缺口：真正被挡掉的只有 **>16 字**的正文。10~16 字的短正文（如
     *   「今天和李四一起吃饭」「转发 张三 的动态」）**仍会让邻居候选命中**
     * - 危害：与 #1 同方向 —— 若正确候选因备注名恒不命中，邻居成为唯一命中 ⇒ **打错标签**
     * - 可选修法（需与真机 `texts=` 采样一起定）：把兜底 16 降到 8~10，
     *   或改成「按 TextView 角色/资源 id 取昵称行」而不是拿子树全文当语料
     *
     * 这里先按**现状**锁住行为（断言 true），避免把“期望”写成一条永远红的用例。
     */
    @Test
    fun `现状锁定：9 字正文提到名字时仍会命中（疑似缺陷 见文档）`() {
        assertTrue(
            "现状：上界兜底 16 让 9 字正文落在界内 ⇒ 命中（这是疑似缺陷，不是期望语义）",
            SnsDeletedTagLogic.matchName("李四", listOf("今天和李四一起吃饭")),
        )
        assertTrue(
            "「转发 张三 的动态」9 字同理仍命中",
            SnsDeletedTagLogic.matchName("张三", listOf("转发 张三 的动态")),
        )
    }

    // ======================================================== pickWindow（R37-1C）

    /** 记录 `ok` 被问到的下标，用来验证窗口顺序与越界跳过 */
    private fun probe(pos: Int, size: Int, ok: (Int) -> Boolean): Pair<List<Int>, SnsDeletedTagLogic.WindowPick> {
        val seen = ArrayList<Int>()
        val pick = SnsDeletedTagLogic.pickWindow(pos, size) { i -> seen.add(i); ok(i) }
        return seen to pick
    }

    /**
     * ⭐ 本文件最该守住的一行：**唯一命中**。
     * `matchName` 是双向包含 ⇒ 同一作者的相邻动态互相都能通过校验；
     * 「取第一个命中」会把邻居的 snsId 用在这一格上 ⇒ 把相邻条目的删除状态画到错误的动态上
     * （R31h 事故同款）。宁可 `hits != 1` 返回 `-1` 让这一格不打标。
     */
    @Test
    fun `窗口内两个命中：返回 -1 而不是第一个命中`() {
        val (_, pick) = probe(3, 10) { it == 4 || it == 2 }
        assertEquals("两个命中 ⇒ 这一格不打标（取第一个命中就是 R31h 事故成因）", -1, pick.index)
        assertEquals("命中数要如实报出来，供诊断用", 2, pick.hits)
    }

    /** 顺序无关的复验：命中落在 `pos+1` 与 `pos-2`（窗口里一前一后） */
    @Test
    fun `命中在 pos+1 与 pos-2：仍要判多命中`() {
        val (seen, pick) = probe(5, 10) { it == 6 || it == 3 }
        assertEquals("窗口顺序必须是 pos, pos+1, pos-1, pos+2, pos-2", listOf(5, 6, 4, 7, 3), seen)
        assertEquals("命中数与顺序无关，仍是 2", 2, pick.hits)
        assertEquals("多命中 ⇒ -1，不能返回先问到的 pos+1", -1, pick.index)
    }

    @Test
    fun `窗口内三个命中：也是 -1`() {
        val (_, pick) = probe(4, 10) { it == 3 || it == 4 || it == 5 }
        assertEquals(-1, pick.index)
        assertEquals(3, pick.hits)
    }

    @Test
    fun `唯一命中在 pos+1：返回该绝对下标`() {
        val (_, pick) = probe(3, 10) { it == 4 }
        assertEquals("唯一命中要返回**绝对**下标", 4, pick.index)
        assertEquals(1, pick.hits)
    }

    @Test
    fun `唯一命中在 pos-2：返回该绝对下标`() {
        val (_, pick) = probe(5, 10) { it == 3 }
        assertEquals("唯一命中在窗口末位也要正确返回", 3, pick.index)
        assertEquals(1, pick.hits)
    }

    @Test
    fun `唯一命中就在 pos 自己：直接返回 pos`() {
        val (_, pick) = probe(5, 10) { it == 5 }
        assertEquals(5, pick.index)
        assertEquals(1, pick.hits)
    }

    @Test
    fun `零命中：-1 与 hits 0`() {
        val (_, pick) = probe(5, 10) { false }
        assertEquals("没有候选通过 ⇒ 不打标", -1, pick.index)
        assertEquals(0, pick.hits)
    }

    /** `pos = 0`：负偏移下标必须跳过，不能越界访问 */
    @Test
    fun `pos 为 0：只访问 0 1 2`() {
        val (seen, pick) = probe(0, 5) { false }
        assertEquals("pos-1 / pos-2 是负数，必须跳过", listOf(0, 1, 2), seen)
        assertEquals(-1, pick.index)
        assertEquals(0, pick.hits)
    }

    /** `pos = size - 1`：`pos+1` / `pos+2` 必须跳过，不能抛异常 */
    @Test
    fun `pos 为最后一个：pos+1 与 pos+2 必须跳过`() {
        val (seen, pick) = probe(4, 5) { false }
        assertEquals("越界下标不能被问到（否则 ok 里取 data[i] 会抛）", listOf(4, 3, 2), seen)
        assertEquals(-1, pick.index)
        assertEquals(0, pick.hits)
    }

    @Test
    fun `size 为 0：一个下标都不访问`() {
        val (seen, pick) = probe(0, 0) { true }
        assertTrue("空列表不该访问任何下标", seen.isEmpty())
        assertEquals(-1, pick.index)
        assertEquals(0, pick.hits)
    }

    @Test
    fun `size 为 1 且命中：返回 0`() {
        val (seen, pick) = probe(0, 1) { true }
        assertEquals(listOf(0), seen)
        assertEquals(0, pick.index)
        assertEquals(1, pick.hits)
    }

    @Test
    fun `size 为 1 且 pos 越界：不命中`() {
        val (seen, pick) = probe(7, 1) { true }
        assertTrue("pos 超出 size ⇒ 窗口全越界，一个都不问", seen.isEmpty())
        assertEquals(-1, pick.index)
        assertEquals(0, pick.hits)
    }

    /** 负 pos（header 偏移算歪时出现）：负下标跳过，仍能命中 0 */
    @Test
    fun `pos 为负：负下标跳过，仍能命中 0`() {
        val (seen, pick) = probe(-1, 5) { it == 0 }
        assertEquals("只有 0 和 1 在界内", listOf(0, 1), seen)
        assertEquals(0, pick.index)
        assertEquals(1, pick.hits)
    }

    @Test
    fun `pos 为负且更极端：窗口整体落在界外`() {
        val (seen, pick) = probe(-5, 3) { true }
        assertTrue("pos=-5 时 pos+2=-3 仍在界外", seen.isEmpty())
        assertEquals(-1, pick.index)
        assertEquals(0, pick.hits)
    }

    @Test
    fun `窗口整体命中 5 个：hits 5 且 index 为 -1`() {
        val (seen, pick) = probe(2, 5) { true }
        assertEquals("size=5 时窗口覆盖全部下标", listOf(2, 3, 1, 4, 0), seen)
        assertEquals(-1, pick.index)
        assertEquals(5, pick.hits)
    }

    /** `ok` 不许有副作用：窗口内每个下标都会被调用**一次**（不重复、不遗漏） */
    @Test
    fun `ok 对每个在界下标只调用一次`() {
        val (seen, pick) = probe(3, 10) { false }
        assertEquals("窗口内下标互不相同，每个只能问一次", seen.toSet().size, seen.size)
        assertEquals(5, seen.size)
        assertEquals(-1, pick.index)
    }
}
