package com.lx.wxgroup.data

/**
 * 备份 SAF 结果的判定（**零 Android 依赖，可跑 JVM 单测**）。
 *
 * 存在的理由：`onActivityResult` 里的分支一旦写错，后果是「用户取消 → 却拿 null
 * 去写文件 → 崩」或者「导入到一半走了导出分支 → 覆盖错文件」。这类错在真机上
 * 只表现为一次 toast，很难复现。所以把判定抽成纯函数，用单测把每种组合钉死。
 */
object BackupSafLogic {

    /** 与 [com.lx.wxgroup.core.ActivityResultRelay] 的 requestCode 对应 */
    const val REQ_EXPORT = 0x575801
    const val REQ_IMPORT = 0x575802

    /** Activity.RESULT_OK 的字面量 —— 纯逻辑文件不能 import android.app.Activity */
    private const val RESULT_OK = -1

    enum class Action {
        /** 用户选了导出位置且带了 Uri → 写文件 */
        DO_EXPORT,

        /** 用户选了导入文件且带了 Uri → 读文件 */
        DO_IMPORT,

        /** 用户取消（resultCode != RESULT_OK）→ 什么都不做，只提示 */
        CANCELLED,

        /** resultCode OK 但没 Uri（某些系统选择器会这样）→ 视为失败，不能当成功 */
        NO_URI,

        /** 不是我们的 requestCode → 完全不插手 */
        IGNORE,
    }

    /**
     * @param requestCode onActivityResult 的第一个参数
     * @param resultCode  onActivityResult 的第二个参数
     * @param hasUri      结果 Intent 里有没有 data
     */
    fun decide(requestCode: Int, resultCode: Int, hasUri: Boolean): Action {
        val ours = when (requestCode) {
            REQ_EXPORT -> Action.DO_EXPORT
            REQ_IMPORT -> Action.DO_IMPORT
            else -> return Action.IGNORE
        }
        // ⚠️ 先判 resultCode 再判 Uri：用户取消时很多 ROM 仍然回一个非空 Intent，
        //    只看 hasUri 会把「取消」当成「选好了」。
        if (resultCode != RESULT_OK) return Action.CANCELLED
        if (!hasUri) return Action.NO_URI
        return ours
    }

    /** 给用户看的文案（UI 与日志共用，避免两处各写一份） */
    fun message(action: Action): String = when (action) {
        Action.DO_EXPORT -> "正在导出…"
        Action.DO_IMPORT -> "正在读取…"
        Action.CANCELLED -> "已取消"
        Action.NO_URI -> "没有拿到文件位置，请重试"
        Action.IGNORE -> ""
    }

    /**
     * SAF 的初始文件名要做一次清洗。
     *
     * 为什么需要：`ACTION_CREATE_DOCUMENT` 的 `EXTRA_TITLE` 里若出现 `/` `\` `:` `*` `?`
     * 这些字符，部分 ROM（尤其是国内的）会直接**静默失败**，用户看到的是「点了没反应」。
     * 我们自己的文件名是 `wxgroup-backup-<时间戳>.json`，正常情况下不含这些字符，
     * 但名称由系统时区/语言拼接，清洗一遍成本极低。
     */
    fun sanitizeFileName(raw: String): String {
        if (raw.isBlank()) return "wxgroup-backup.json"
        val cleaned = raw.map { ch ->
            if (ch in ILLEGAL || ch.code < 0x20) '_' else ch
        }.joinToString("")
        val trimmed = cleaned.trim().trimEnd('.')
        // ⚠️ 清成 `___` 也算不可用：用户拿到一个叫 `___` 的文件根本认不出是什么，
        //    而且下次导入时 `startsWith("wxgroup-backup-")` 也筛不到它。
        //    所以「只剩分隔符/下划线」一律回退到标准名。
        if (trimmed.isEmpty() || trimmed.all { it == '_' || it == '-' || it.isWhitespace() }) {
            return "wxgroup-backup.json"
        }
        return trimmed
    }

    private val ILLEGAL = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /**
     * 从 SAF 返回的 Uri 里猜一个可读名字（只用于列表展示与日志，不用于落盘）。
     *
     * 不解析 provider 内部结构，只取最后一段并做一次 URL 解码级别的清理。
     */
    fun displayNameOf(uriString: String?): String {
        val s = uriString?.trim().orEmpty()
        if (s.isEmpty()) return "(未知文件)"
        val last = s.substringAfterLast('/')
        if (last.isEmpty()) return "(未知文件)"
        val noQuery = last.substringBefore('?')
        return if (noQuery.isEmpty()) "(未知文件)" else noQuery
    }
}
