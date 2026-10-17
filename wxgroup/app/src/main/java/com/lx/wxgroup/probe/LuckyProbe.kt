package com.lx.wxgroup.probe

import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.feature.AntiRevoke
import com.lx.wxgroup.feature.AntiRevokeHint
import com.lx.wxgroup.feature.LuckyLogic
import de.robv.android.xposed.XposedHelpers

/**
 * 6.3 红包域的**取证探针**（不是功能实现）。
 *
 * ## 为什么先交付探针而不是 hook
 * `docs/取证结论.md` 里**没有任何红包锚点**（全文无 红包 / lucky / redpacket /
 * wxpay / hb）。本项目的铁律是不猜微信 API，所以 `LuckyLogic` 里所有「微信长什么样」
 * 的知识（`redpacket.type_codes` / `redpacket.text_markers`）默认留空 ——
 * 没有它们引擎恒输出 `Skip`，也就是**不会有任何动作**。把这两个空填上的唯一合法途径
 * 就是取证，而这就是本探针存在的理由。
 *
 * ## 用法（真机，`op=lucky`）
 * ```
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op lucky --es sub cfg
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op lucky --es sub rows  --ei n 12
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op lucky --es sub types --ei n 300
 * ```
 * 取证步骤：**先 sub=rows 看一眼基线 → 让别人发一个 0.01 的红包 → 再 sub=rows**，
 * 多出来的那几行就是红包消息，从里面抄 `type` 低位与正文特征。
 *
 * ## 数据来源没有猜的成分
 * 走的是 `AntiRevoke` 已经**在 8.0.48 上实证过**的那条路：`updateWithOnConflict`
 * hook 里捕获的 `message` 库实例 + `rawQuery` 反射（见 `AntiRevoke.msgQueryHandle`）。
 * 表名 `message` 与列 `msgId/type/talker/content/isSend` 都是未混淆的实证结论。
 *
 * ⚠️ 全程只读 SQL + runCatching，任何一个环节失败都只返回一行说明，绝不崩微信。
 */
object LuckyProbe {

    /** 单个格子的显示宽度：够看清特征，又不至于一条日志刷掉 LogX 一大片 */
    private const val VALUE_MAX = 48

    /** rows 一次最多拉几行：多了读不动，少了看不见红包 */
    private const val ROWS_MAX = 40

    /** types 统计窗口的行数上限 */
    private const val TYPES_MAX = 2000

    // ------------------------------------------------------------------ 入口

    /**
     * @param sub cfg | rows | types
     * @param n   行数（rows / types 用）
     */
    fun command(sub: String, n: Int): List<String> = when (sub.trim().lowercase()) {
        "rows" -> rows(n.coerceIn(1, ROWS_MAX))
        "types" -> types(n.coerceIn(1, TYPES_MAX))
        else -> cfg()
    }

    // ------------------------------------------------------------------ sub=cfg

    /**
     * 当前配置 + 引擎现状。
     *
     * 重点看最后一行：两条识别判据都空 → 引擎当前**不可能**有任何动作。
     * 这是刻意的安全默认值，不是 bug。
     */
    fun cfg(): List<String> {
        val out = ArrayList<String>()
        out.add("LUCKY cfg decide=${read(Keys.REDPACKET_DECIDE)} autoOpen=${read(Keys.REDPACKET_AUTO_OPEN)}")
        out.add("LUCKY cfg delayMs=${read(Keys.REDPACKET_DELAY_MS)} maxAttempts=${read(Keys.REDPACKET_MAX_ATTEMPTS)}")
        out.add("LUCKY cfg allow=${compact(Keys.REDPACKET_ALLOW)} exclude=${compact(Keys.REDPACKET_EXCLUDE)}")
        val types = LuckyLogic.typeCodes(read(Keys.REDPACKET_TYPE_CODES))
        val markers = LuckyLogic.entries(read(Keys.REDPACKET_TEXT_MARKERS))
        out.add("LUCKY cfg typeCodes=$types textMarkers=$markers")
        out.add(
            if (types.isEmpty() && markers.isEmpty()) {
                "LUCKY cfg 两条识别判据都未配置（待取证）→ LuckyLogic 恒 Skip，不会有任何动作"
            } else {
                "LUCKY cfg 已配置 typeCodes=${types.size} textMarkers=${markers.size}" +
                    "（textMarkers 命中只算 SUSPECT，仍不会触发后续流程）"
            }
        )
        return out
    }

    private fun read(key: String): String = runCatching { Kv.getString(key) }.getOrDefault("(err)")

    /** 名单类配置可能很长，日志里只留条数与前几项 */
    private fun compact(key: String): String {
        val set = LuckyLogic.entries(runCatching { Kv.getString(key) }.getOrDefault(""))
        if (set.isEmpty()) return "(空)"
        return set.take(3).joinToString("|") + if (set.size > 3) " …共${set.size}项" else ""
    }

    // ------------------------------------------------------------------ 查询

    /** 一次只读查询的结果。失败时 [error] 非空，[names] 为空 */
    private class Select(
        val names: List<String>,
        val rows: List<List<String>>,
        val error: String? = null,
    ) {
        companion object {
            fun fail(msg: String) = Select(emptyList(), emptyList(), msg)
        }
    }

    /**
     * 跑一条只读 SQL。**拿不到消息库返回 null**（上层据此提示「先让微信收发一条消息」），
     * 查询本身出错返回 [Select.fail]。
     */
    private fun select(sql: String): Select? {
        val handle = runCatching { AntiRevoke.msgQueryHandle() }.getOrNull() ?: return null
        val (db, m) = handle
        // ⚠️ BYPASS 前缀：模块自己的诊断 SQL 必须绕过「隐藏撤回提示」的 SQL 改写，
        //    否则探针要查的行可能被自己屏蔽掉（AntiRevoke.dumpRecent 踩过同一处）
        val cur = runCatching { m.invoke(db, AntiRevokeHint.BYPASS + sql, null) }.getOrNull()
            ?: return Select.fail("rawQuery invoke failed")
        return try {
            val count = XposedHelpers.callMethod(cur, "getColumnCount") as? Int ?: 0
            val names = ArrayList<String>(count)
            for (i in 0 until count) {
                names += XposedHelpers.callMethod(cur, "getColumnName", i) as? String ?: "col$i"
            }
            val rows = ArrayList<List<String>>()
            while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                val line = ArrayList<String>(count)
                for (i in 0 until count) line += value(cur, i)
                rows += line
            }
            Select(names, rows)
        } catch (t: Throwable) {
            Select.fail(t.javaClass.simpleName + ": " + t.message)
        } finally {
            // 游标必须关：读失败也要关，否则泄漏
            runCatching { XposedHelpers.callMethod(cur, "close") }
        }
    }

    /** 一个格子读成字符串。wcdb 的 Cursor 不是 android 的，全程反射 + 逐级降级 */
    private fun value(cur: Any, i: Int): String {
        val isNull = runCatching { XposedHelpers.callMethod(cur, "isNull", i) as? Boolean }.getOrNull()
        if (isNull == true) return "<null>"
        runCatching { XposedHelpers.callMethod(cur, "getString", i) as? String }
            .getOrNull()?.let { return it }
        runCatching { XposedHelpers.callMethod(cur, "getLong", i) }.getOrNull()?.let { return it.toString() }
        runCatching { XposedHelpers.callMethod(cur, "getDouble", i) }.getOrNull()?.let { return it.toString() }
        return "<blob?>"
    }

    private fun cell(row: List<String>, names: List<String>, col: String): String {
        val i = names.indexOf(col)
        return if (i >= 0 && i < row.size) row[i] else ""
    }

    /** 去掉换行并把长度压到显示宽度 */
    private fun one(s: String): String {
        val flat = s.replace('\n', ' ').replace('\r', ' ')
        return if (flat.length <= VALUE_MAX) flat else flat.take(VALUE_MAX) + "…"
    }

    // ------------------------------------------------------------------ sub=rows

    /**
     * 最近 n 条 message 行的**全字段** dump（取证主力）。
     *
     * 每行先打关键列（msgId / type 低位 / talker），再把其余非空列逐列打一行。
     * 命中已配置的 typeCodes 标 `<<HIT>>`，命中 textMarkers 标 `<<SUSPECT>>`。
     */
    private fun rows(n: Int): List<String> {
        val result = select("select * from message order by msgId desc limit $n")
            ?: return listOf("LUCKY rows: 拿不到消息库（AntiRevoke 还没抓到 message 库的写，先让微信收发一条消息再试）")
        result.error?.let { return listOf("LUCKY rows query failed: $it") }
        if (result.rows.isEmpty()) return listOf("LUCKY rows: empty")

        val names = result.names
        val out = ArrayList<String>()
        out.add("LUCKY rows cols(${names.size}): ${names.joinToString("|")}")

        val markers = LuckyLogic.entries(runCatching { Kv.getString(Keys.REDPACKET_TEXT_MARKERS) }.getOrDefault(""))
        val codes = LuckyLogic.typeCodes(runCatching { Kv.getString(Keys.REDPACKET_TYPE_CODES) }.getOrDefault(""))

        for ((idx, row) in result.rows.withIndex()) {
            val msgId = cell(row, names, "msgId")
            val type = cell(row, names, "type")
            val isSend = cell(row, names, "isSend")
            val talker = cell(row, names, "talker")
            val low = type.toLongOrNull()?.toInt()?.let { LuckyLogic.lowType(it) } ?: -1
            val tag = when {
                codes.isNotEmpty() && low in codes -> "<<HIT>>"
                markers.isNotEmpty() && row.any { v -> markers.any { v.contains(it, true) } } -> "<<SUSPECT>>"
                else -> ""
            }
            out.add("LUCKY #${idx + 1} msgId=$msgId type=$type low=$low isSend=$isSend talker=${one(talker)} $tag")
            for (i in names.indices) {
                val v = row.getOrNull(i).orEmpty()
                if (v.isBlank() || v == "<null>" || v == "<blob?>") continue
                out.add("LUCKY   ${names[i]}=${one(v)}")
            }
        }
        return out
    }

    // ------------------------------------------------------------------ sub=types

    /**
     * 最近 n 条消息里 `type` **低位**的分布（按条数倒序）。
     *
     * 取证用法：发红包前后各跑一次，多出来 / 条数变了的那个低位就是候选。
     * 只看低位是因为微信 type 的高位是它自己加的标志位（`AntiRevoke` 实证）。
     */
    private fun types(n: Int): List<String> {
        val result = select(
            "select (type & 65535) as lt, count(*) as c from " +
                "(select type from message order by msgId desc limit $n) group by lt order by c desc"
        ) ?: return listOf("LUCKY types: 拿不到消息库（先让微信收发一条消息）")
        result.error?.let { return listOf("LUCKY types query failed: $it") }
        if (result.rows.isEmpty()) return listOf("LUCKY types: empty")

        val out = ArrayList<String>()
        out.add("LUCKY types window=$n 共 ${result.rows.size} 种低位")
        for (row in result.rows) {
            out.add("LUCKY types low=${cell(row, result.names, "lt")} count=${cell(row, result.names, "c")}")
        }
        return out
    }
}
