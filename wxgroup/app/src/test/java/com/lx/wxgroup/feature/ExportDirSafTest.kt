package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R28：`ExportDirSaf.docIdToPath` 的解析回归。
 *
 * ## 为什么必须单测
 * `ACTION_OPEN_DOCUMENT_TREE` 只给 `content://` Uri，**没有标准 API 能反查文件路径**，
 * 只能按系统文档提供器的约定解析 docId。这段是纯字符串逻辑、无 Android 依赖，
 * 属于「能测就必须测」的部分 —— 解析错了会把导出目录指到一个静默的错误位置。
 *
 * ⚠️ 解析结果在调用侧还会再过「存在 / 是目录 / 真能建文件」三道回验，
 * 本测试只覆盖解析本身。
 */
class ExportDirSafTest {

    @Test
    fun primary_with_relative_path() {
        assertEquals(
            "/storage/emulated/0/Documents/WXGroup",
            ExportDirSaf.docIdToPath("primary:Documents/WXGroup")
        )
    }

    @Test
    fun primary_root_returns_base_only() {
        assertEquals("/storage/emulated/0", ExportDirSaf.docIdToPath("primary:"))
    }

    @Test
    fun default_export_dir_roundtrip() {
        // 默认导出路径（Prefs.DEFAULT_EXPORT_PATH）对应的 docId 必须能解析回来
        assertEquals(
            "/storage/emulated/0/Documents/WXGroup/moments",
            ExportDirSaf.docIdToPath("primary:Documents/WXGroup/moments")
        )
    }

    @Test
    fun null_or_blank_is_null() {
        assertNull(ExportDirSaf.docIdToPath(null))
        assertNull(ExportDirSaf.docIdToPath(""))
        assertNull(ExportDirSaf.docIdToPath("   "))
    }

    @Test
    fun no_colon_is_null() {
        assertNull(ExportDirSaf.docIdToPath("Documents/WXGroup"))
    }

    @Test
    fun empty_volume_is_null() {
        assertNull(ExportDirSaf.docIdToPath(":Documents"))
    }

    @Test
    fun sdcard_volume_is_rejected() {
        // ⛔ 外置 SD 卡的 docId 是卷标，没有可靠路径映射 ⇒ 必须返回 null 走「不支持」提示，
        //    绝不能猜一个路径（猜错 = 导出静默落到错位置）
        assertNull(ExportDirSaf.docIdToPath("ABCD-1234:Documents/WXGroup"))
        assertNull(ExportDirSaf.docIdToPath("1234-ABCD:"))
    }

    @Test
    fun volume_match_is_case_insensitive() {
        assertEquals("/storage/emulated/0/Documents", ExportDirSaf.docIdToPath("PRIMARY:Documents"))
    }

    @Test
    fun trailing_slash_is_trimmed() {
        assertEquals(
            "/storage/emulated/0/Documents/WXGroup",
            ExportDirSaf.docIdToPath("primary:Documents/WXGroup/")
        )
    }
}
