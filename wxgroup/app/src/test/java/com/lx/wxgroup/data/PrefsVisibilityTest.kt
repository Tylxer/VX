package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Prefs` 登记表与**渲染过滤**的回归测试。
 *
 * 为什么需要这个文件：模块里有一类「空壳」—— 配置项定义着、备份里也带着，
 * 但**当前没有任何消费点**（改了不会有任何效果）。它有两种错误做法：
 *
 * 1. **画出来** ⇒ 用户看到一个输入框，填了以为生效，实际什么都不做（"说能做、实际不做"）；
 * 2. **连同定义一起删** ⇒ 旧备份文件里带着这个 key，`BackupFlow.knownKeys`
 *    会把它当未知 key **丢掉**，用户换机时静默丢配置。
 *
 * 唯一正确的形态是第三种：**保留在 `Prefs.ALL` 里、但标记 `hidden` 不渲染**。
 * 本文件就是把这个三方约束（定义在 / 备份在 / 不渲染）锁死 ——
 * 因为这三条分别由三个不同的文件保证，改任一文件都可能在别处破功：
 *
 * - 「定义在」    ← `Prefs.ALL` / `spec()` / `defaultOf()`
 * - 「备份在」    ← `BackupFlow.kt` 的 `knownKeys` 与 `Kv.exportAll()` 都遍历 `ALL`
 * - 「不渲染」    ← `Prefs.visible()`，由 `SettingPage` / `MainPage` / `MassSendPage` 消费
 */
class PrefsVisibilityTest {

    /** 当前已知的 hidden 项 —— 将来真接了消费点，把它从这里删掉并去掉 hidden 即可 */
    private val expectedHidden = setOf(
        Keys.REDPACKET_ALLOW,
        Keys.REDPACKET_EXCLUDE,
    )

    // ------------------------------------------------------------ 三方约束

    /**
     * 核心不变量：hidden 的项**必须仍然登记在 `Prefs.ALL` 里**。
     *
     * 这条如果破了（比如有人"清理"时把那两行删了），备份兼容性就没了；
     * 而丢掉配置这件事在真机上**完全没有症状**，只有换机时才发现。
     */
    @Test
    fun hiddenKeysAreStillRegistered() {
        val allKeys = Prefs.ALL.map { it.key }.toSet()
        for (k in expectedHidden) {
            assertTrue("$k 被标了 hidden，但已经不在 Prefs.ALL 里了 —— 旧备份会丢这个 key", k in allKeys)
            assertNotNull("Prefs.spec($k) 必须仍能查到（探针/导入都靠它判已登记）", Prefs.spec(k))
        }
    }

    /** hidden 的项**必须不出现在任何域的渲染结果里** */
    @Test
    fun hiddenKeysAreNotRenderedInAnyDomain() {
        val domains = Prefs.ALL.map { it.domain }.toSet()
        assertTrue("域列表不该为空", domains.isNotEmpty())
        for (d in domains) {
            val rendered = Prefs.visible(d).map { it.key }
            for (k in expectedHidden) {
                assertFalse("hidden 的 $k 不该出现在域「$d」的渲染列表里", k in rendered)
            }
        }
    }

    /**
     * `byDomain` 答的是「这个域登记了哪些 key」（口径是全集），
     * `visible` 答的是「这个域该画哪些」（口径是子集）——
     * 两者对 hidden 项必须恰好差这一项。这条防的是「有人顺手把 visible 改成别名」。
     */
    @Test
    fun byDomainKeepsHiddenButVisibleDropsIt() {
        val domain = Prefs.spec(Keys.REDPACKET_ALLOW)!!.domain
        val all = Prefs.byDomain(domain).map { it.key }
        val shown = Prefs.visible(domain).map { it.key }
        for (k in expectedHidden) {
            assertTrue("byDomain 口径是全集，应包含 $k", k in all)
            assertFalse("visible 口径是渲染，不该包含 $k", k in shown)
        }
        assertTrue("visible 必须是 byDomain 的子集", all.containsAll(shown))
    }

    // ------------------------------------------------------------ hidden 语义本身

    /** 默认必须是 false —— 否则新加的配置项会悄悄不显示，排查起来非常费劲 */
    @Test
    fun hiddenDefaultsToFalse() {
        val plain = PrefSpec("x.y", PrefKind.SWITCH, "false", "调试", "标题")
        assertFalse("PrefSpec.hidden 默认值必须是 false", plain.hidden)
    }

    /** hidden 只影响渲染，**不影响默认值与钳制**（否则 Kv 读默认值会读出空串） */
    @Test
    fun hiddenDoesNotAffectDefaultsOrClamp() {
        for (k in expectedHidden) {
            val spec = Prefs.spec(k)!!
            assertTrue("$k 是 hidden", spec.hidden)
            assertEquals("hidden 不该改默认值", spec.def, Prefs.defaultOf(k))
        }
        // 数值钳制与 hidden 无关，拿一个正常项对照一下，确保没被误伤
        val n = Keys.REDPACKET_MAX_ATTEMPTS
        assertFalse("REDPACKET_MAX_ATTEMPTS 不该是 hidden", Prefs.spec(n)!!.hidden)
        assertEquals("钳制照常生效", 4, Prefs.clamp(n, 99))
        assertEquals("钳制照常生效", 1, Prefs.clamp(n, -5))
    }

    // ------------------------------------------------------------ 反面：不许留别的空壳

    /**
     * **反向守卫**：除了 `expectedHidden` 里那几个，`Prefs.ALL` 里不该再有别的 hidden 项。
     *
     * 为什么要有这条：hidden 是个"眼不见"的旋钮，一旦被滥用，
     * 就变成"功能没做完但先把开关藏起来"的合法借口 —— 那比空壳更糟，
     * 因为连"设置页有这一项"这个线索都没了。
     * 新加 hidden **必须**同时改这里的白名单，逼一次显式决策。
     */
    @Test
    fun noUnexpectedHiddenPrefs() {
        val actual = Prefs.ALL.filter { it.hidden }.map { it.key }.toSet()
        assertEquals(
            "hidden 白名单变动了。新增一条 hidden 前先问：它真的『不能删定义』吗？" +
                "如果只是功能没做完，应该把 Prefs.ALL 那一行删掉，而不是藏起来。",
            expectedHidden, actual
        )
    }

    /** 每个 key 必须唯一（重复登记会让 `spec()` 静默取到后一条） */
    @Test
    fun keysAreUnique() {
        val all = Prefs.ALL.map { it.key }
        assertEquals("Prefs.ALL 里有重复 key：${all.groupBy { it }.filter { it.value.size > 1 }.keys}", all.size, all.toSet().size)
    }

    /**
     * 每个 key 必须以 `<域>.<项>` 形式命名，且**域名与 spec.domain 不冲突**
     * （前缀用来给人看，domain 用来分组渲染，两者错位会让人找错地方）。
     */
    @Test
    fun keyPrefixMatchesDomainGrouping() {
        val knownDomainPrefixes = mapOf(
            "chat" to "聊天",
            "antirevoke" to "防撤回",
            "redpacket" to "红包",
            "moments" to "朋友圈",
            "sns" to "朋友圈",   // 朋友圈图片下载走 sns.* 前缀，但归朋友圈域
            "broadcast" to "群发",
            "debug" to "调试",
        )
        for (spec in Prefs.ALL) {
            val prefix = spec.key.substringBefore('.', "")
            assertTrue("${spec.key} 缺少 `<域>.` 前缀", prefix.isNotEmpty() && spec.key.contains('.'))
            val expect = knownDomainPrefixes[prefix]
            assertNotNull("${spec.key} 的前缀 `$prefix` 不在已知域里，加新域请同时更新本测试", expect)
            assertEquals("${spec.key} 的前缀与 domain 不一致", expect, spec.domain)
        }
    }
}
