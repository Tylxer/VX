package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAF 结果判定的单测。
 *
 * 这个文件的每一条都对应真机上一种「看起来成功、实际错」的组合：
 * 用户取消却被当成选好了、requestCode 串号、文件名含非法字符导致静默失败。
 */
class BackupSafLogicTest {

    private val ok = -1          // Activity.RESULT_OK
    private val cancelled = 0    // Activity.RESULT_CANCELED 及任意非 OK 值

    // ---------------------------------------------------------- decide：正常路径

    @Test
    fun 导出且成功且有uri走导出() {
        assertEquals(
            BackupSafLogic.Action.DO_EXPORT,
            BackupSafLogic.decide(BackupSafLogic.REQ_EXPORT, ok, true)
        )
    }

    @Test
    fun 导入且成功且有uri走导入() {
        assertEquals(
            BackupSafLogic.Action.DO_IMPORT,
            BackupSafLogic.decide(BackupSafLogic.REQ_IMPORT, ok, true)
        )
    }

    // ---------------------------------------------------------- decide：四种异常组合

    @Test
    fun 用户取消时即使resultCode是OK也不导出() {
        // 真机踩过的形态：部分 ROM 取消时仍回 RESULT_OK，只是 data 为空
        assertEquals(
            BackupSafLogic.Action.NO_URI,
            BackupSafLogic.decide(BackupSafLogic.REQ_EXPORT, ok, false)
        )
    }

    @Test
    fun 用户取消且无uri判定为取消() {
        assertEquals(
            BackupSafLogic.Action.CANCELLED,
            BackupSafLogic.decide(BackupSafLogic.REQ_EXPORT, cancelled, false)
        )
    }

    @Test
    fun 取消时即便带了uri也算取消() {
        // ⚠️ 这条是重点：先判 resultCode 再判 uri。反过来写就会「用户点了取消
        //    结果模块把备份写进了某个残留 uri」。两种 requestCode 都要锁。
        assertEquals(
            BackupSafLogic.Action.CANCELLED,
            BackupSafLogic.decide(BackupSafLogic.REQ_IMPORT, cancelled, true)
        )
    }

    @Test
    fun 非零非负一的其他resultCode一律视为取消() {
        for (code in listOf(1, 2, -2, 100)) {
            assertEquals(
                "resultCode=$code 必须当取消",
                BackupSafLogic.Action.CANCELLED,
                BackupSafLogic.decide(BackupSafLogic.REQ_EXPORT, code, true)
            )
        }
    }

    @Test
    fun 别人的requestCode完全不插手() {
        // 微信自己也会用 onActivityResult（选图、扫码、授权…）。
        // 只要不是我们的区间就必须 IGNORE，否则会抢别人的结果甚至走错分支。
        for (code in listOf(0, 1, 2, 100, 1000, 0x575800, 0x575803, 0x7FFFFFFF)) {
            assertEquals(
                "requestCode=$code 不该被我们处理",
                BackupSafLogic.Action.IGNORE,
                BackupSafLogic.decide(code, ok, true)
            )
        }
    }

    @Test
    fun 导出与导入的requestCode不重叠() {
        // 防的是以后有人手抖把两个常量写成同一个值 —— 那会让导出走导入分支
        assertTrue(BackupSafLogic.REQ_EXPORT != BackupSafLogic.REQ_IMPORT)
    }

    // ---------------------------------------------------------- message

    @Test
    fun 每个动作都有非空文案且IGNORE为空() {
        for (a in BackupSafLogic.Action.values()) {
            val m = BackupSafLogic.message(a)
            if (a == BackupSafLogic.Action.IGNORE) {
                assertTrue("IGNORE 不该有文案", m.isEmpty())
            } else {
                assertTrue("$a 缺文案", m.isNotEmpty())
            }
        }
    }

    // ---------------------------------------------------------- sanitizeFileName

    @Test
    fun 正常文件名原样保留() {
        val name = "wxgroup-backup-20261018-214000.json"
        assertEquals(name, BackupSafLogic.sanitizeFileName(name))
    }

    @Test
    fun 非法字符替换为下划线() {
        // 这些字符会让部分 ROM 的 CREATE_DOCUMENT 静默失败（用户看到「点了没反应」）
        assertEquals(
            "a_b_c_d_e_f_g_h_i_j.json",
            BackupSafLogic.sanitizeFileName("a/b\\c:d*e?f\"g<h>i|j.json")
        )
    }

    @Test
    fun 控制字符被替换() {
        assertEquals("a_b.json", BackupSafLogic.sanitizeFileName("a\u0000b.json"))
    }

    @Test
    fun 空白输入有兜底名() {
        assertEquals("wxgroup-backup.json", BackupSafLogic.sanitizeFileName(""))
        assertEquals("wxgroup-backup.json", BackupSafLogic.sanitizeFileName("   "))
    }

    @Test
    fun 全非法字符被清空后有兜底名() {
        assertEquals("wxgroup-backup.json", BackupSafLogic.sanitizeFileName("///"))
    }

    @Test
    fun 结尾的点被去掉() {
        // Windows 与部分 MTP 实现会把结尾的 '.' 吃掉，导致文件名与预期不符
        assertEquals("abc", BackupSafLogic.sanitizeFileName("abc..."))
    }

    // ---------------------------------------------------------- displayNameOf

    @Test
    fun 从uri末段取展示名() {
        assertEquals(
            "wxgroup-backup-1.json",
            BackupSafLogic.displayNameOf("content://com.android.providers.downloads.documents/document/1/wxgroup-backup-1.json")
        )
    }

    @Test
    fun 带query的uri去掉query() {
        assertEquals(
            "abc.json",
            BackupSafLogic.displayNameOf("content://x/abc.json?foo=1")
        )
    }

    @Test
    fun 空uri有兜底名() {
        assertEquals("(未知文件)", BackupSafLogic.displayNameOf(null))
        assertEquals("(未知文件)", BackupSafLogic.displayNameOf(""))
        assertEquals("(未知文件)", BackupSafLogic.displayNameOf("content://x/"))
    }
}
