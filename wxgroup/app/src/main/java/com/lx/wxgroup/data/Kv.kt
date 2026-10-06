package com.lx.wxgroup.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 配置项读写（K4）。
 *
 * 三个约定：
 * 1. **默认值只在 `Prefs.ALL` 里定义一次**。这里读不到就回落到默认值，
 *    所以新加开关不用改备份代码，也不用改这里。
 * 2. 值一律字符串落地（开关 "true"/"false"），备份文件人类可读、
 *    adb 查库一眼能看懂当前状态 —— 这是当初不存 SharedPreferences 的原因。
 * 3. 数值写入前先经 `Prefs.clamp` 钳制到合法范围（决策 #6：越界不弹错，钳到边界）。
 *
 * 注意：本对象依赖 `GroupDb.open()` 已被调用。没开库时全部读操作返回默认值，
 * 写操作静默丢弃 —— hook 场景下宁可退化也不能抛异常把微信搞崩。
 */
object Kv {

    private val gson = Gson()
    private val listType = object : TypeToken<List<String>>() {}.type

    private fun db(): GroupDb? = GroupDb.current()

    // ------------------------------------------------------------------ 读

    fun getString(key: String): String {
        val d = db() ?: return Prefs.defaultOf(key)
        return runCatching { d.getPref(key, Prefs.defaultOf(key)) }
            .getOrDefault(Prefs.defaultOf(key))
    }

    /** "true"/"1" 都算开，兼容旧写法 */
    fun getBool(key: String): Boolean {
        val v = getString(key)
        return v == "true" || v == "1"
    }

    fun getInt(key: String): Int =
        getString(key).toIntOrNull() ?: Prefs.defaultOf(key).toIntOrNull() ?: 0

    /** 名单类配置（允许名单 / 排除名单 / 导出名单），存 JSON 数组 */
    fun getList(key: String): List<String> {
        val raw = getString(key)
        if (raw.isBlank()) return emptyList()
        return runCatching { gson.fromJson<List<String>>(raw, listType) }
            .getOrNull()?.filter { it.isNotBlank() } ?: emptyList()
    }

    // ------------------------------------------------------------------ 写

    fun putString(key: String, value: String) {
        db()?.setPref(key, value)
    }

    fun putBool(key: String, value: Boolean) = putString(key, if (value) "true" else "false")

    /** 数值写入前钳制到 spec 的 [min, max]，越界不报错 */
    fun putInt(key: String, value: Int) = putString(key, Prefs.clamp(key, value).toString())

    fun putList(key: String, value: List<String>) = putString(key, gson.toJson(value))

    // ------------------------------------------------------- 备份导出 / 导入

    /**
     * 全量导出当前配置（K5 备份引擎用）。
     * 遍历 `Prefs.ALL` 而不是查 kv 全表：这样导出的 key 必然是已定义的，
     * 不会把已废弃的历史 key 带进备份文件。
     */
    fun exportAll(): Map<String, String> =
        Prefs.ALL.associate { it.key to getString(it.key) }

    /**
     * 导入配置 —— **只覆盖传入的项**，没写的保持当前值（决策 #10）。
     *
     * 未登记过的 key 直接忽略：可能是更高版本备份里的新配置，
     * 写进来只会污染当前库（高版本备份走「尽力解析、忽略未知字段」策略）。
     */
    fun importSome(values: Map<String, String>): Int {
        var n = 0
        for ((k, v) in values) {
            if (Prefs.spec(k) == null) continue
            putString(k, v)
            n++
        }
        return n
    }
}
