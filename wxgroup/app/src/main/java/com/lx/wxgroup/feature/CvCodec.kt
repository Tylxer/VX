package com.lx.wxgroup.feature

import android.content.ContentValues
import java.util.Base64

/**
 * ContentValues ↔ 字符串 的编解码（「恢复默认显示」完整重放用）。
 *
 * ## 为什么需要它
 * 真机实测：微信真撤回时写的字段不止 `type` + `content`，还有 `lvbuffer`（两次都有）、
 * `isSend` / `reserved`（按撤回方不同二选一）。只重放 type+content 会让那一行处于
 * 「撤回形态的 type + 普通消息的残留状态」的混合态，微信渲染不出任何东西（整页空白）。
 *
 * 所以拦下撤回时把微信想写的 **整个 ContentValues**（除 msgId 外）编码存进台账，
 * 「恢复默认显示」开启时原样重放 —— 伪装行和微信自己写的一字不差。
 *
 * ## 编码格式（纯逻辑，JVM 可单测）
 * 每个字段三段：`Base64(key) \u0002 类型标记 \u0002 Base64(value)`，字段间 `\u0001`。
 * **全部 Base64** 而不是裸文本：value 是任意字符串（content 里什么都有可能），
 * 裸文本没法跟分隔符共处；Base64 后格式自描述、不可能歧义。
 */
object CvCodec {

    private const val FIELD_SEP = '\u0002'
    private const val ENTRY_SEP = '\u0001'

    // 类型标记：Base64 只管字节，类型得单独记，解码时才知道还原成什么
    private const val T_NULL = "N"
    private const val T_STRING = "S"
    private const val T_INT = "I"
    private const val T_LONG = "L"
    private const val T_BOOL = "Bo"
    private const val T_DOUBLE = "D"
    private const val T_FLOAT = "F"
    private const val T_SHORT = "Sh"
    private const val T_BYTE = "By"
    private const val T_BYTES = "B"

    /** [ContentValues] 里可能出现的所有类型 → ContentValues.put 对应的重载 */
    private fun typeNameOf(v: Any?): String = when (v) {
        null -> T_NULL
        is String -> T_STRING
        is Int -> T_INT
        is Long -> T_LONG
        is Boolean -> T_BOOL
        is Double -> T_DOUBLE
        is Float -> T_FLOAT
        is Short -> T_SHORT
        is Byte -> T_BYTE
        is ByteArray -> T_BYTES
        else -> T_STRING // 兜底：toString 存下来，好过丢字段
    }

    /** 从 ContentValues 编码，跳过 [skip] 里的 key（msgId 是 where 条件，不能进 extras） */
    fun encodeFrom(cv: ContentValues, skip: Set<String> = emptySet()): String {
        val map = LinkedHashMap<String, Any?>()
        for (k in cv.keySet()) {
            if (k !in skip) map[k] = cv.get(k)
        }
        return encode(map)
    }

    fun encode(map: Map<String, Any?>): String = map.entries.joinToString(ENTRY_SEP.toString()) {
        val typeName = typeNameOf(it.value)
        val raw: ByteArray = when (val v = it.value) {
            null -> ByteArray(0)
            is ByteArray -> v
            else -> v.toString().toByteArray(Charsets.UTF_8)
        }
        val b64 = b64encode(raw)
        "${b64encode(it.key.toByteArray(Charsets.UTF_8))}$FIELD_SEP$typeName$FIELD_SEP$b64"
    }

    fun decode(s: String?): Map<String, Any?> {
        if (s.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for (entry in s.split(ENTRY_SEP)) {
            val parts = entry.split(FIELD_SEP)
            if (parts.size != 3) continue // 坏段直接丢，不能让一行坏数据炸整个开关
            val key = unb64(parts[0])?.toString(Charsets.UTF_8) ?: continue
            out[key] = when (parts[1]) {
                T_NULL -> null
                T_STRING -> String(unb64(parts[2]) ?: continue, Charsets.UTF_8)
                T_INT -> (unb64(parts[2]) ?: continue).toString(Charsets.UTF_8).toIntOrNull() ?: continue
                T_LONG -> (unb64(parts[2]) ?: continue).toString(Charsets.UTF_8).toLongOrNull() ?: continue
                T_BOOL -> (unb64(parts[2]) ?: continue).toString(Charsets.UTF_8).toBooleanStrictOrNull() ?: continue
                T_DOUBLE -> (unb64(parts[2]) ?: continue).toString(Charsets.UTF_8).toDoubleOrNull() ?: continue
                T_FLOAT -> (unb64(parts[2]) ?: continue).toString(Charsets.UTF_8).toFloatOrNull() ?: continue
                T_SHORT -> (unb64(parts[2]) ?: continue).toString(Charsets.UTF_8).toShortOrNull() ?: continue
                T_BYTE -> (unb64(parts[2]) ?: continue).toString(Charsets.UTF_8).toByteOrNull() ?: continue
                T_BYTES -> unb64(parts[2]) ?: continue
                else -> continue
            }
        }
        return out
    }

    /** 解码结果填进 ContentValues（只填 [ContentValues] 认识的类型） */
    fun decodeInto(s: String?, cv: ContentValues) {
        for ((k, v) in decode(s)) {
            when (v) {
                null -> cv.putNull(k)
                is String -> cv.put(k, v)
                is Int -> cv.put(k, v)
                is Long -> cv.put(k, v)
                is Boolean -> cv.put(k, v)
                is Double -> cv.put(k, v)
                is Float -> cv.put(k, v)
                is Short -> cv.put(k, v)
                is Byte -> cv.put(k, v)
                is ByteArray -> cv.put(k, v)
            }
        }
    }

    /** java.util.Base64（API 24+）：不用 android.util.Base64，纯 JVM 单测才跑得起来 */
    private fun b64encode(b: ByteArray): String =
        Base64.getEncoder().withoutPadding().encodeToString(b)

    private fun unb64(s: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(s) }.getOrNull()
}
