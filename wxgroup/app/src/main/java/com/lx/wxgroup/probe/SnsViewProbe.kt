package com.lx.wxgroup.probe

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.util.LogX
import java.lang.reflect.Field

/**
 * 朋友圈界面的 View 结构取证（为 6.2 两个「[已删除]标签」项做准备）。
 *
 * ## 要回答三个问题
 * 1. 时间线的 **item 容器**是哪个类、结构长什么样；
 * 2. **能不能从 item 上反查出 snsId** —— 这是标签可不可做的分水岭。
 *    微信常把数据对象塞进 `view.setTag()`，所以这里对每个节点的 tag 做反射，
 *    找名字含 `snsid` 的 Long 字段；找得到就能把「item ↔ 动态」对上。
 * 3. 文字内容在哪个 TextView 里（真要打标签，最省事的做法就是往它上面追加）。
 *
 * 用法：`adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
 *         --es op snsv --es sub tree [--ei n 240]`
 */
object SnsViewProbe {

    private const val MAX_DEPTH = 26

    fun tree(maxLines: Int) {
        val act = runCatching { HostContext.activity() }.getOrNull()
        if (act == null) {
            LogX.i("SVP no activity")
            return
        }
        val dec = runCatching { act.window?.decorView }.getOrNull()
        if (dec == null) {
            LogX.i("SVP no decorView")
            return
        }
        LogX.i("SVP activity=${act.javaClass.name}")
        val out = StringBuilder()
        var lines = 0
        fun walk(v: View, depth: Int) {
            if (depth > MAX_DEPTH || lines >= maxLines) return
            val pad = "  ".repeat(depth)
            val id = runCatching { "0x" + Integer.toHexString(v.id) }.getOrDefault("?")
            val txt = (v as? TextView)?.text?.toString()?.take(40)
            val tag = tagInfo(v)
            val size = runCatching { "${v.width}x${v.height}" }.getOrDefault("?")
            out.append("SVP $pad${v.javaClass.simpleName} #$id $size")
            if (!txt.isNullOrBlank()) out.append(" txt=\"$txt\"")
            if (tag != null) out.append(" TAG[$tag]")
            out.append('\n')
            lines++
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    runCatching { v.getChildAt(i) }?.getOrNull()?.let { walk(it, depth + 1) }
                }
            }
        }
        walk(dec, 0)
        LogX.i("SVP tree ($lines lines)\n$out")
    }

    /**
     * 时间线列表（ImproveRecyclerView）的 **adapter / 数据对象** 取证。
     *
     * 为什么需要它：新版时间线 `ImproveSnsTimelineUI` 上**根本没有**带
     * `TimeLineObject` 的 tag（唯一 sns 包 tag 是 `qh`，挂在 MaskImageView 上，
     * 里面只有 `sns_table_xxx` 表名），所以「item ↔ 动态」只能从 adapter 的
     * `getItem(position)` 拿。这个探针一次性回答：
     * ① ImproveRecyclerView 的继承链（是不是 androidx RecyclerView）；
     * ② adapter 的类名与「按位置取数据」的方法名；
     * ③ 取第 0/1/2 个 item，扒它的字段找 snsId（含 `Id` 这种无符号十进制 String）。
     *
     * `--es sub recycler [--ei n 40]`
     */
    fun recycler(maxFields: Int) {
        val dec = runCatching { HostContext.activity()?.window?.decorView }.getOrNull()
        if (dec == null) {
            LogX.i("SVPR no decorView")
            return
        }
        val rv = findFirst(dec) { it.javaClass.name.contains("ImproveRecyclerView") }
        if (rv == null) {
            LogX.i("SVPR no ImproveRecyclerView")
            return
        }
        val sb = StringBuilder()
        var data: List<*>? = null
        sb.append("SVPR rv=${rv.javaClass.name}\n")
        var c: Class<*>? = rv.javaClass.superclass
        var up = 0
        while (c != null && up < 8) {
            sb.append("SVPR   ^ ${c.name}\n")
            c = c.superclass
            up++
        }
        // 跟 adapter / position / holder 沾边的方法签名
        for (m in rv.javaClass.methods) {
            val n = m.name
            if (!n.contains("Adapter", true) && !n.contains("Position", true)) continue
            sb.append("SVPR   m $n(${m.parameterTypes.joinToString { it.simpleName }})" +
                " -> ${m.returnType.simpleName}\n")
        }
        val ad = runCatching {
            rv.javaClass.methods.firstOrNull { it.name == "getAdapter" && it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }?.invoke(rv)
        }.getOrNull()
        sb.append("SVPR adapter=${ad?.javaClass?.name}\n")
        if (ad != null) {
            for (m in ad.javaClass.methods) {
                if (m.parameterTypes.size > 2) continue
                if (m.returnType == Void.TYPE) continue
                sb.append("SVPR   am ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" +
                    " -> ${m.returnType.simpleName}\n")
            }
            // 绑定方法（SnsTag 就靠它拿 item ↔ snsId），单独列出来确认
            for (m in ad.javaClass.methods) {
                if (!m.name.contains("ind", true)) continue
                sb.append("SVPR   bind ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" +
                    " -> ${m.returnType.simpleName}\n")
            }
            // getData() 就是数据列表本体（外层作用域要用，所以是 var）
            data = runCatching {
                ad.javaClass.methods.firstOrNull { it.name == "getData" && it.parameterTypes.isEmpty() }
                    ?.apply { isAccessible = true }?.invoke(ad)
            }.getOrNull() as? List<*>
            if (data is List<*>) {
                sb.append("SVPR getData() size=${data.size}\n")
                sb.append("SVPR   elemClass=${data.firstOrNull()?.javaClass?.name}\n")
                for (p in 0 until minOf(2, data.size)) {
                    val o = data[p] ?: continue
                    sb.append("SVPR data[$p] = ${o.javaClass.name}\n")
                    dumpFields(sb, o, maxFields)
                }
            } else {
                sb.append("SVPR getData() = ${data?.javaClass?.name ?: "null"}\n")
            }
            // 「按位置取 item」的候选方法名（混淆过，逐个试）
            for (name in listOf("getItem", "i1", "j1", "k1", "g0")) {
                val m = ad.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        it.returnType != Void.TYPE
                } ?: continue
                m.isAccessible = true
                val o = runCatching { m.invoke(ad, 0) }.getOrElse { null }
                sb.append("SVPR $name(0) -> ${o?.javaClass?.name ?: "null"}\n")
                if (o != null && o !is List<*>) dumpFields(sb, o, maxFields)
            }
        }
        // 每个可见子 View：类名 / 高度 / 作者文本 / ViewHolder
        //
        // ⚠️ `RecyclerView.getChildAdapterPosition(View)` 在**微信自备的 androidx 副本里被改名了**
        // （实测 NoSuchMethodException；能查到的只有 getAdapter/setAdapter 和
        //   `getVerticalScrollbarPosition`），所以这里只能自己找「child → 数据」的路子。
        val g = rv as? ViewGroup
        sb.append("SVPR childCount=${g?.childCount}\n")
        val vhOf = ad?.javaClass?.methods?.firstOrNull {
            it.name == "Z0" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == View::class.java && it.returnType != Void.TYPE
        }
        if (g != null) {
            for (i in 0 until minOf(6, g.childCount)) {
                val ch = runCatching { g.getChildAt(i) }.getOrNull() ?: continue
                val author = runCatching { firstText(ch) }.getOrNull()
                sb.append("SVPR   child[$i] ${ch.javaClass.simpleName} " +
                    "h=${ch.height} author=\"$author\"\n")
                var pc: Class<*>? = ch.javaClass.superclass
                var pu = 0
                while (pc != null && pu < 4) {
                    sb.append("SVPR     ^ ${pc.name}\n")
                    pc = pc.superclass
                    pu++
                }
                if (vhOf == null || ad == null) continue
                val vh = runCatching {
                    vhOf.isAccessible = true
                    vhOf.invoke(ad, ch)
                }.getOrNull() ?: continue
                sb.append("SVPR     vh=${vh.javaClass.name}\n")
                // 位置类字段（微信自备的 androidx 副本里 getChildAdapterPosition 被改名了，
                // 只能直接读 ViewHolder 的 int 字段）
                var vc: Class<*>? = vh.javaClass
                var vup = 0
                while (vc != null && vup < 4) {
                    for (f in vc.declaredFields) {
                        if (f.type != Int::class.javaPrimitiveType) continue
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        val r0 = runCatching { f.isAccessible = true; f.get(vh) }.getOrNull()
                        sb.append("SVPR       ${vc.simpleName}.${f.name} = $r0\n")
                    }
                    vc = vc.superclass
                    vup++
                }
                // ViewHolder 里有没有直接持有数据对象（data 元素类型）
                if (data is List<*> && data.isNotEmpty()) {
                    val elem = data.first()?.javaClass
                    var vc2: Class<*>? = vh.javaClass
                    var vup2 = 0
                    while (vc2 != null && vup2 < 4) {
                        for (f in vc2.declaredFields) {
                            if (f.type != elem) continue
                            val r0 = runCatching {
                                f.isAccessible = true
                                f.get(vh)?.toString()?.take(60)
                            }.getOrNull()
                            sb.append("SVPR       ITEM ${vc2.simpleName}.${f.name} = $r0\n")
                        }
                        vc2 = vc2.superclass
                        vup2++
                    }
                }
            }
        }
        LogX.i("SVPR dump\n$sb")
    }

    private fun findFirst(v: View, pred: (View) -> Boolean): View? {
        if (pred(v)) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = runCatching { v.getChildAt(i) }.getOrNull() ?: continue
                findFirst(c, pred)?.let { return it }
            }
        }
        return null
    }

    /** 扒对象字段（含父类），业务对象再往里挖一层（snsId 常嵌在 ImproveInfo 里） */
    private fun dumpFields(sb: StringBuilder, o: Any, maxFields: Int, depth: Int = 0) {
        var c: Class<*>? = o.javaClass
        var up = 0
        var n = 0
        while (c != null && up < 5 && n < maxFields) {
            for (f in c.declaredFields) {
                if (n >= maxFields) break
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                val v = runCatching {
                    f.isAccessible = true
                    f.get(o)
                }.getOrElse { "<err>" }
                val s = when (v) {
                    null -> "null"
                    is ByteArray -> "byte[${v.size}]"
                    is String -> "\"${v.take(28)}\""
                    else -> v.toString().take(120)
                }
                val pad = "  ".repeat(depth)
                sb.append("SVPR     $pad${c.simpleName}.${f.name} : ${f.type.simpleName} = $s\n")
                n++
                if (v != null && depth < 2 && isBusiness(v)) dumpFields(sb, v, maxFields, depth + 1)
            }
            c = c.superclass
            up++
        }
    }

    /** 子树里第一个非空 TextView 文本（用来跟 adapter 数据对上号） */
    private fun firstText(v: View): String? {
        if (v is TextView) {
            val t = v.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) return t.take(16)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = runCatching { v.getChildAt(i) }.getOrNull() ?: continue
                firstText(c)?.let { return it }
            }
        }
        return null
    }

    /** 挖进去只看业务对象：java/android/kotlin/集合/String 一律不进 */
    private fun isBusiness(v: Any): Boolean {
        val n = v.javaClass.name
        if (v is String || v is Number || v is Boolean || v is Char || v is ByteArray ||
            v is Iterable<*> || v is Map<*, *>
        ) return false
        return !n.startsWith("java.") && !n.startsWith("android.") && !n.startsWith("kotlin.") &&
            !n.startsWith("dalvik.")
    }

    /** tag 里有 snsId 就返回 `类名=snsId`，否则返回类名（或 null 表示没 tag） */
    private fun tagInfo(v: View): String? {
        val tag = runCatching { v.getTag() }.getOrNull() ?: return null
        val cls = tag.javaClass
        val id = findSnsId(tag, cls)
        val brief = runCatching { tag.toString().take(70) }.getOrDefault("")
        return if (id != null) "${cls.simpleName} snsId=$id :: $brief" else "${cls.simpleName} :: $brief"
    }

    /**
     * 在 tag 对象里找 snsId：字段名含 `snsid` 且类型是 Long/Integer。
     *
     * ⚠️ 微信字段名可能是 `snsId` / `field_snsId` / 混淆名，
     * 所以按**名字+类型**找，不按确切名字找；往上最多翻 3 层父类。
     */
    private fun findSnsId(obj: Any, cls: Class<*>): Long? {
        var c: Class<*>? = cls
        var up = 0
        while (c != null && up < 4) {
            for (f in c.declaredFields) {
                if (!f.name.contains("snsid", ignoreCase = true)) continue
                if (f.type != java.lang.Long::class.java && f.type != Long::class.javaPrimitiveType &&
                    f.type != java.lang.Integer::class.java && f.type != Int::class.javaPrimitiveType
                ) continue
                val v = runCatching {
                    f.isAccessible = true
                    when (val x = f.get(obj)) {
                        is Long -> x
                        is Int -> x.toLong()
                        else -> null
                    }
                }.getOrNull()
                if (v != null && v != 0L) return v
            }
            c = c.superclass
            up++
        }
        return null
    }

    /**
     * 把 item 上挂的 tag 对象的**所有字段**打出来 —— 用来找 snsId 到底藏在哪个字段里
     * （可能是混淆名，也可能嵌在子对象里）。
     *
     * `--es sub tagdump [--es cls g2]`
     */
    fun tagDump(clsSimple: String, maxFields: Int) {
        val dec = runCatching { HostContext.activity()?.window?.decorView }.getOrNull()
        if (dec == null) {
            LogX.i("SVPD no decorView")
            return
        }
        val sb = StringBuilder()
        var done = false
        fun walk(v: View, depth: Int) {
            if (done || depth > MAX_DEPTH) return
            val tag = runCatching { v.getTag() }.getOrNull()
            if (tag != null && (clsSimple.isBlank() || tag.javaClass.simpleName == clsSimple)) {
                sb.append("SVPD host=${v.javaClass.name} tag=${tag.javaClass.name}\n")
                var n = 0
                fun dumpObj(obj: Any, depth: Int) {
                    if (depth > 2 || n >= maxFields) return
                    var c: Class<*>? = obj.javaClass
                    var up = 0
                    while (c != null && up < 4 && n < maxFields) {
                        for (f in c.declaredFields) {
                            if (n >= maxFields) break
                            val v2 = runCatching {
                                f.isAccessible = true
                                f.get(obj)
                            }.getOrElse { "<err>" }
                            val s = when (v2) {
                                null -> "null"
                                is ByteArray -> "byte[${v2.size}]"
                                is String -> "\"${v2.take(30)}\""
                                else -> v2.toString().take(46)
                            }
                            sb.append("SVPD ${"   ".repeat(depth)}${c.simpleName}.${f.name} : " +
                                "${f.type.simpleName} = $s\n")
                            n++
                            // 非基础类型往下再挖一层（snsId 就藏在 TimeLineObject 里）
                            if (v2 != null && depth < 2 && !isPlain(v2)) dumpObj(v2, depth + 1)
                        }
                        c = c.superclass
                        up++
                    }
                }
                dumpObj(tag, 0)
                done = true
                return
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    runCatching { v.getChildAt(i) }?.getOrNull()?.let { walk(it, depth + 1) }
                }
            }
        }
        walk(dec, 0)
        LogX.i(if (done) "SVPD dump\n$sb" else "SVPD no tag matched cls=$clsSimple")
    }

    /**
     * **全量**列出当前界面里所有「被 setTag 了朋友圈对象」的节点，逐个打出解析出的 Id。
     *
     * 与 [tagDump] 的区别：[tagDump] 命中第一个就 `done=true` 收工，只能看一个样本；
     * 这里不走短路，用来回答两个决定性问题：
     * ① 同一个 item 里**有几个**节点被 setTag（>1 就会一条动态打两个标签）；
     * ② `Id` 是不是**所有** item 都是无符号十进制（有没有负号 / 短位写法）。
     *
     * `--es sub ids [--ei n 60]`
     */
    fun ids(maxLines: Int) {
        val dec = runCatching { HostContext.activity()?.window?.decorView }.getOrNull()
        if (dec == null) {
            LogX.i("SVPI no decorView")
            return
        }
        val sb = StringBuilder()
        var n = 0
        var hits = 0
        fun walk(v: View, depth: Int) {
            if (depth > MAX_DEPTH || n >= maxLines) return
            val tag = runCatching { v.getTag() }.getOrNull()
            if (tag != null && tag.javaClass.name.startsWith("com.tencent.mm.plugin.sns.")) {
                hits++
                val id = runCatching { idOf(tag) }.getOrNull()
                sb.append("SVPI d=$depth host=${v.javaClass.simpleName}" +
                    "(w=${v.width},h=${v.height}) tag=${tag.javaClass.simpleName} id=$id\n")
                n++
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    runCatching { v.getChildAt(i) }?.getOrNull()?.let { walk(it, depth + 1) }
                }
            }
        }
        walk(dec, 0)
        LogX.i("SVPI hits=$hits lines=$n\n$sb")
    }

    /** tag 对象 → TimeLineObject.Id（跟 SnsTag 的解析逻辑保持一致） */
    private fun idOf(tag: Any): String {
        var c: Class<*>? = tag.javaClass
        var up = 0
        while (c != null && up < 4) {
            for (f in c.declaredFields) {
                if (f.type.name.contains("TimeLineObject")) {
                    f.isAccessible = true
                    val tl = f.get(tag) ?: return "tlNull"
                    var c2: Class<*>? = tl.javaClass
                    var up2 = 0
                    while (c2 != null && up2 < 4) {
                        for (g in c2.declaredFields) {
                            if (g.type == String::class.java && g.name == "Id") {
                                g.isAccessible = true
                                return (g.get(tl) as? String) ?: "null"
                            }
                        }
                        c2 = c2.superclass
                        up2++
                    }
                    return "noIdField:${tl.javaClass.simpleName}"
                }
            }
            c = c.superclass
            up++
        }
        return "noTLField"
    }

    /** 往下挖一层时跳过这些：挖进去只会打出 java 内部实现，没有业务字段 */
    private fun isPlain(v: Any): Boolean {
        val n = v.javaClass.name
        return v is String || v is Number || v is Boolean || v is Char || v is ByteArray ||
            n.startsWith("java.") || n.startsWith("android.") || n.startsWith("kotlin.") ||
            n.startsWith("dalvik.") || v is Iterable<*> || v is Map<*, *>
    }

    /** 只打带 snsId 的节点：确认「item ↔ 动态」这条链到底成不成立 */
    fun tagged(maxLines: Int) {
        val dec = runCatching { HostContext.activity()?.window?.decorView }.getOrNull()
        if (dec == null) {
            LogX.i("SVPT no decorView")
            return
        }
        val sb = StringBuilder()
        var n = 0
        fun walk(v: View, depth: Int) {
            if (depth > MAX_DEPTH || n >= maxLines) return
            val tag = runCatching { v.getTag() }.getOrNull()
            if (tag != null) {
                val id = findSnsId(tag, tag.javaClass)
                if (id != null) {
                    sb.append("SVPT ${v.javaClass.simpleName} d=$depth snsId=$id " +
                        "tag=${tag.javaClass.name}\n")
                    n++
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    runCatching { v.getChildAt(i) }?.getOrNull()?.let { walk(it, depth + 1) }
                }
            }
        }
        walk(dec, 0)
        LogX.i("SVPT tagged nodes=$n\n$sb")
    }
}
