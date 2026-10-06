package com.lx.wxgroup.core

import com.lx.wxgroup.data.Friend
import com.lx.wxgroup.data.WxidKind
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 把一个微信联系人实体（8.0.48 实测是 com.tencent.mm.storage.h4）读成 [Friend]。
 *
 * 性能考虑：通讯录有两千多个联系人，逐个反射全部字段会明显卡顿，
 * 所以字段列表按 Class 缓存 —— 类结构只反射一次，之后全是直接 get。
 */
object ContactReader {

    private val fieldsCache = ConcurrentHashMap<Class<*>, List<Field>>()

    // ConcurrentHashMap 不允许 null 值，所以「这个方法不存在」用一个哨兵对象表示
    private val NO_METHOD = Any()
    private val keyGetterCache = ConcurrentHashMap<Class<*>, Any>()

    /** @return null 表示这不是联系人实体，或读不出 wxid */
    fun read(obj: Any): Friend? {
        if (obj.javaClass.name != Anchors848.CONTACT_CLASS) return null

        val values = readStrings(obj)
        val wxid = values[Anchors848.FIELD_USERNAME]?.takeIf { it.isNotBlank() }
            ?: primaryKeyOf(obj)
            ?: return null

        return Friend(
            wxid = wxid,
            nickname = values[Anchors848.FIELD_NICKNAME].orEmpty(),
            remark = values[Anchors848.FIELD_REMARK].orEmpty(),
            alias = values[Anchors848.FIELD_ALIAS].orEmpty(),
            type = WxidKind.typeOf(wxid),
        )
    }

    /** 收集对象及其父类里所有非空 String 字段 */
    private fun readStrings(obj: Any): Map<String, String> {
        val fields = stringFieldsOf(obj.javaClass)
        val out = HashMap<String, String>(fields.size * 2)
        for (f in fields) {
            val v = runCatching { f.get(obj) as? String }.getOrNull()
            if (!v.isNullOrEmpty()) out.putIfAbsent(f.name, v)
        }
        return out
    }

    private fun stringFieldsOf(cls: Class<*>): List<Field> = fieldsCache.getOrPut(cls) {
        val out = ArrayList<Field>(48)
        var c: Class<*>? = cls
        var guard = 0
        while (c != null && c != java.lang.Object::class.java && guard++ < 5) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.type != String::class.java) continue
                runCatching { f.isAccessible = true }
                out.add(f)
            }
            c = c.superclass
        }
        out
    }

    /**
     * 兜底取 wxid：getPrimaryKeyValue() 方法名未混淆，是比字段名更稳的锚点。
     * 换版本后即使字段被重排，只要这个方法还在就能用。
     */
    private fun primaryKeyOf(obj: Any): String? {
        val cls = obj.javaClass
        val cached = keyGetterCache.getOrPut(cls) {
            runCatching { cls.getMethod(Anchors848.METHOD_PRIMARY_KEY) }.getOrNull() ?: NO_METHOD
        }
        if (cached === NO_METHOD) return null
        val method = cached as java.lang.reflect.Method
        return runCatching { method.invoke(obj) as? String }.getOrNull()
    }
}
