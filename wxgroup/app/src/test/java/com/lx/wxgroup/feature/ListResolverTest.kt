package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ListResolver] 的单测。
 *
 * 重点锁三件事：
 * 1. **`g:` / `t:` 展开正确**，且查不到时静默吞掉（与群发口径一致）；
 * 2. **「空配置」与「展开后为空」不能混** —— 这是上次一轮差点做成反向失效的地方；
 * 3. 前缀常量与两个发送方同值（防有人改了群发那边忘了这边）。
 */
class ListResolverTest {

    private val groups = mapOf(
        "3" to listOf("wxid_a", "wxid_b"),
        "7" to listOf("wxid_c"),
        "9" to emptyList<String>(),        // 空分组：真实存在但没成员
    )
    private val labels = mapOf(
        "12" to listOf("wxid_b", "wxid_d"),
    )

    // ------------------------------------------------------------------ 展开

    @Test
    fun expand_bare_wxid_kept_as_is() {
        val r = ListResolver.expand(listOf("wxid_a", "wxid_z"), groups, labels)
        assertEquals(setOf("wxid_a", "wxid_z"), r.ids)
        assertFalse(r.unrestricted)
        assertFalse(r.impossible)
    }

    @Test
    fun expand_group_prefix_expands_to_members() {
        val r = ListResolver.expand(listOf("g:3"), groups, labels)
        assertEquals(setOf("wxid_a", "wxid_b"), r.ids)
    }

    @Test
    fun expand_label_prefix_expands_to_members() {
        val r = ListResolver.expand(listOf("t:12"), groups, labels)
        assertEquals(setOf("wxid_b", "wxid_d"), r.ids)
    }

    @Test
    fun expand_group_and_label_dedupe_same_person() {
        // wxid_b 同时在分组 3 和标签 12 里，只能出现一次
        val r = ListResolver.expand(listOf("g:3", "t:12"), groups, labels)
        assertEquals(setOf("wxid_a", "wxid_b", "wxid_d"), r.ids)
    }

    @Test
    fun expand_direct_and_group_dedupe_same_person() {
        val r = ListResolver.expand(listOf("wxid_a", "g:3"), groups, labels)
        assertEquals(setOf("wxid_a", "wxid_b"), r.ids)
    }

    @Test
    fun expand_unknown_prefix_key_is_silently_dropped() {
        // 分组被删了 / 标签还没同步：不抛异常，只是展开不出人
        val r = ListResolver.expand(listOf("g:999", "t:888"), groups, labels)
        assertTrue(r.ids.isEmpty())
        assertTrue(r.impossible)
    }

    @Test
    fun expand_without_buckets_swallows_all_prefixed() {
        // 调用方拿不到分组数据时的行为：前缀条目全被吞，裸 wxid 保留
        val r = ListResolver.expand(listOf("g:3", "wxid_a"))
        assertEquals(setOf("wxid_a"), r.ids)
    }

    @Test
    fun expand_trims_and_drops_blank_entries() {
        val r = ListResolver.expand(listOf("  wxid_a  ", "", "   ", "g:3 "), groups, labels)
        assertEquals(setOf("wxid_a", "wxid_b"), r.ids)
        // raw 里保留 trim 后的非空条目，供诊断
        assertEquals(listOf("wxid_a", "g:3"), r.raw)
    }

    @Test
    fun expand_does_not_write_garbage_into_ids() {
        // 分组成员里混入空白（库脏数据）时不能进集合
        val dirty = mapOf("5" to listOf("wxid_x", "  ", ""))
        val r = ListResolver.expand(listOf("g:5"), dirty, labels)
        assertEquals(setOf("wxid_x"), r.ids)
    }

    // ------------------------------------------------------- 三态语义（关键）

    @Test
    fun empty_config_is_unrestricted() {
        val r = ListResolver.expand(emptyList(), groups, labels)
        assertTrue(r.unrestricted)
        assertFalse(r.impossible)
        assertTrue(r.ids.isEmpty())
    }

    @Test
    fun configured_but_expands_empty_is_impossible_not_unrestricted() {
        // ⚠️ 这条是本次的核心回归：空分组 ≠ 不限制
        val r = ListResolver.expand(listOf("g:9"), groups, labels)
        assertFalse("配了东西就不能算不限制", r.unrestricted)
        assertTrue("展开为空必须被识别成谁都不命中", r.impossible)
        assertEquals(listOf("g:9"), r.unresolvedKeys())
    }

    @Test
    fun unresolvedKeys_only_lists_prefixed_entries() {
        val r = ListResolver.expand(listOf("wxid_a", "g:3", "t:12"))
        // 裸 wxid 不是「待解析」，不进 unresolvedKeys
        assertEquals(listOf("g:3", "t:12"), r.unresolvedKeys())
    }

    // ------------------------------------------------------------------ 作用域

    @Test
    fun inScope_blank_talker_always_false() {
        assertFalse(ListResolver.inScope(null, emptyList(), emptyList()))
        assertFalse(ListResolver.inScope("", emptyList(), emptyList()))
        assertFalse(ListResolver.inScope("   ", emptyList(), emptyList()))
    }

    @Test
    fun inScope_empty_allow_means_everyone() {
        assertTrue(ListResolver.inScope("wxid_anyone", emptyList(), emptyList()))
    }

    @Test
    fun inScope_allow_list_limits_to_members() {
        val allow = listOf("wxid_a", "wxid_b")
        assertTrue(ListResolver.inScope("wxid_a", allow, emptyList()))
        assertFalse(ListResolver.inScope("wxid_c", allow, emptyList()))
    }

    @Test
    fun inScope_exclude_wins_over_allow() {
        val allow = listOf("wxid_a")
        val exclude = listOf("wxid_a")
        assertFalse("排除优先，即使同时在允许名单里", ListResolver.inScope("wxid_a", allow, exclude))
    }

    @Test
    fun inScope_group_allow_expands() {
        val allow = listOf("g:3")
        assertTrue(ListResolver.inScope("wxid_a", allow, emptyList(), groups, labels))
        assertTrue(ListResolver.inScope("wxid_b", allow, emptyList(), groups, labels))
        assertFalse(ListResolver.inScope("wxid_c", allow, emptyList(), groups, labels))
    }

    @Test
    fun inScope_label_exclude_expands() {
        val exclude = listOf("t:12")
        assertFalse(ListResolver.inScope("wxid_b", emptyList(), exclude, groups, labels))
        assertTrue(ListResolver.inScope("wxid_a", emptyList(), exclude, groups, labels))
    }

    @Test
    fun inScope_empty_group_allow_blocks_everyone() {
        // ⚠️ 反向失效防线：允许名单 = 一个空分组时，不能退化成「允许所有人」
        val allow = listOf("g:9")
        assertFalse(ListResolver.inScope("wxid_a", allow, emptyList(), groups, labels))
        assertFalse(ListResolver.inScope("wxid_anyone", allow, emptyList(), groups, labels))
    }

    @Test
    fun inScope_exclude_only_keeps_others() {
        val exclude = listOf("wxid_bad")
        assertFalse(ListResolver.inScope("wxid_bad", emptyList(), exclude))
        assertTrue(ListResolver.inScope("wxid_good", emptyList(), exclude))
    }

    // ------------------------------------------------------------- 常量对齐

    @Test
    fun prefix_matches_senders() {
        // 三个前缀必须同值，否则「存进去的 id」和「读出来的 id」对不上
        assertEquals(MassSendLogic.PREFIX_GROUP, ListResolver.PREFIX_GROUP)
        assertEquals(MassSendLogic.PREFIX_LABEL, ListResolver.PREFIX_LABEL)
        assertEquals(ForwardLogic.PREFIX_GROUP, ListResolver.PREFIX_GROUP)
        assertEquals(ForwardLogic.PREFIX_LABEL, ListResolver.PREFIX_LABEL)
    }

    // --------------------------------------------------------- 顺序稳定性

    @Test
    fun expand_preserves_selection_order() {
        val r = ListResolver.expand(listOf("wxid_z", "g:3", "t:12"), groups, labels)
        assertEquals(listOf("wxid_z", "wxid_a", "wxid_b", "wxid_d"), r.ids.toList())
    }
}
