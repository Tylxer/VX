package com.lx.wxgroup.feature

/**
 * SnsTag / 将来的评论标签共用的**纯逻辑**（零 Android 依赖 → 可直接跑 JVM 单测）。
 *
 * 存在的意义：这些判据是靠真机取证试出来的，改错一次就要重上一轮真机（≈2 分钟 +
 * 大量排查 token）。抽出来之后，改判据先跑单测，真机只做最后一公里验证。
 *
 * ⚠️ 本文件**禁止 import 任何 android.* / xposed.* **，否则单测加载不了。
 */
object SnsTagLogic {

    /** 解析结果：snsId 统一为**有符号 Long**（与 SQLite/归档里的位模式一致） */
    class Info(val snsId: Long, val name: String?)

    /**
     * `toString()` 是**功能性格式**（名字/时间/rowid 都在里面），比猜混淆字段名稳。
     * ⚠️ `snsID` 允许负号 —— 有符号写法也要能吃下。
     */
    private val ID_RE = Regex("""snsID\s*=\s*'?(-?\d{15,20})""")
    private val NAME_RE = Regex("""[^A-Za-z]name\s*=\s*'?([^,'\n]{1,32})""")

    fun parseInfo(s: String?): Info? {
        if (s == null) return null
        val m = ID_RE.find(s) ?: return null
        val id = parseSnsId(m.groupValues[1]) ?: return null
        val name = NAME_RE.find(s)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        return Info(id, name)
    }

    /**
     * 形状校验：必须是**纯数字**（允许负号）。这是最后一道防线 —— 取错字段时几乎
     * 不可能同时满足这个形状，「打错标签」（比不打标签糟糕得多）就基本不会发生。
     */
    fun parseSnsId(raw: String): Long? {
        if (raw.isEmpty() || raw.length > 20) return null
        var i = 0
        if (raw[0] == '-') {
            i = 1
            if (raw.length == 1) return null
        }
        while (i < raw.length) {
            val ch = raw[i]
            if (ch < '0' || ch > '9') return null
            i++
        }
        return runCatching {
            if (raw[0] == '-') raw.toLong() else java.lang.Long.parseUnsignedLong(raw)
        }.getOrNull()
    }

    /**
     * 作者名字对账。**严格**：拿不到名字 / 读不到文字 / 名字太短 → 一律不采信。
     * 打错标签（把没删的标成已删除）比不打标签糟糕得多，宁可让 missed 涨。
     */
    fun nameOk(name: String?, texts: List<String>): Boolean {
        val n = name ?: return false
        if (n.length < 2) return false
        if (texts.isEmpty()) return false
        return texts.any { t ->
            t.length >= 2 && (t.contains(n) || (t.length >= 3 && n.contains(t)))
            //                                  ↑ 反向包含只在文本够长时才算，
            //                                    否则一两字的名字碎片会恒真
        }
    }
}
