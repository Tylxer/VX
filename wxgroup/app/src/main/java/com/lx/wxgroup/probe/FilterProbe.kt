package com.lx.wxgroup.probe

import android.view.View
import android.widget.ListView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.util.LogX

/**
 * M4-C 定位探针（只打日志）：做「按组过滤会话」之前先确认三件事。
 *
 *  1. `HeaderViewListAdapter` 里包的到底是什么 adapter，`getItem` 能不能拿到会话对象
 *     —— 能拿到才谈得上按 wxid 过滤。
 *  2. 会话对象的 wxid 字段（M4-B 已知是 `r4.field_username`，这里再确认一次，
 *     顺便看有没有更好拿的）。
 *  3. **微信自己的 `mOnItemClickListener` 长什么样** —— 这决定了能不能安全地
 *     把 adapter 换成过滤版：如果它是拿位置去自己的列表里取会话，那位置被过滤
 *     打乱之后点击就会打开错的聊天，必须做位置重映射。
 *
 * 不改变任何行为，跑完即卸。
 */
object FilterProbe {

    private const val LIST_CLASS = "com.tencent.mm.ui.conversation.ConversationListView"

    @Volatile
    private var dumped = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.widget.ListView", lpparam.classLoader, "setAdapter",
                android.widget.ListAdapter::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        if (v.javaClass.name != LIST_CLASS) return
                        if (dumped) return
                        dumped = true
                        // 会话是异步填进来的，等几秒再 dump
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            runCatching { dump(v as ListView) }
                                .onFailure { LogX.w("FilterProbe: dump failed ${it.javaClass.simpleName}") }
                        }, 4000)
                    }
                }
            )
            LogX.i("FilterProbe: hooked ListView.setAdapter")
        }.onFailure { LogX.w("FilterProbe: hook failed: ${it.javaClass.simpleName}") }
    }

    private fun dump(list: ListView) {
        val adapter = list.adapter
        LogX.i("FP list=${list.javaClass.simpleName} adapter=${adapter?.javaClass?.name}")

        // ---- 1) 谁在包谁 ----
        val wrapped = runCatching { XposedHelpers.getObjectField(adapter, "mAdapter") }.getOrNull()
        val wrapCls = wrapped?.javaClass?.name
        val wrapCount = runCatching {
            XposedHelpers.callMethod(wrapped, "getCount") as Int
        }.getOrDefault(-1)
        LogX.i("FP wrapped=$wrapCls count=$wrapCount")

        if (wrapped != null) {
            // 会话 adapter 是不是 Filterable（微信搜索会用到，包一层之后要转发）
            val filterable = wrapped is android.widget.Filterable
            val vtc = runCatching {
                XposedHelpers.callMethod(wrapped, "getViewTypeCount") as Int
            }.getOrDefault(-1)
            LogX.i("FP wrappedFilterable=$filterable viewTypeCount=$vtc")
        }

        val headers = runCatching {
            XposedHelpers.callMethod(adapter, "getHeadersCount") as Int
        }.getOrDefault(-1)
        val footers = runCatching {
            XposedHelpers.callMethod(adapter, "getFootersCount") as Int
        }.getOrDefault(-1)
        LogX.i("FP headers=$headers footers=$footers total=${adapter?.count}")

        // ---- 1b) 会话对象长什么样 ----
        if (wrapped != null && headers >= 0) {
            for (i in headers until minOf(headers + 3, adapter!!.count)) {
                val item = runCatching { adapter.getItem(i) }.getOrNull() ?: continue
                val sb = StringBuilder("FP item[$i] ${item.javaClass.name}")
                var cls: Class<*>? = item.javaClass
                var guard = 0
                while (cls != null && cls != java.lang.Object::class.java && guard++ < 3) {
                    for (f in cls.declaredFields) {
                        if (f.type != String::class.java) continue
                        val s = runCatching {
                            f.isAccessible = true
                            f.get(item) as? String
                        }.getOrNull() ?: continue
                        if (s.isNotEmpty() && sb.length < 260) sb.append(" ${f.name}='${s.take(22)}'")
                    }
                    cls = cls.superclass
                }
                LogX.i(sb.toString())
            }
        }

        // ---- 3) 点击监听（最重要） ----
        val listener = runCatching { XposedHelpers.getObjectField(list, "mOnItemClickListener") }
            .getOrNull()
        if (listener == null) {
            LogX.i("FP onItemClick=null")
        } else {
            val methods = runCatching { listener.javaClass.declaredMethods }
                .getOrDefault(emptyArray())
                .joinToString(",") { m ->
                    m.name + "(" + m.parameterTypes.joinToString("/") { it.simpleName } + ")"
                }
            LogX.i("FP onItemClick=${listener.javaClass.name} methods=$methods")
            // 它有没有自己握着 adapter 或列表
            var cls: Class<*>? = listener.javaClass
            var guard = 0
            while (cls != null && cls != java.lang.Object::class.java && guard++ < 3) {
                for (f in runCatching { cls!!.declaredFields }.getOrDefault(emptyArray())) {
                    if (f.type.isPrimitive) continue
                    val o = runCatching { f.isAccessible = true; f.get(listener) }.getOrNull() ?: continue
                    LogX.d("FP   listener.${f.name}:${f.type.simpleName}")
                }
                cls = cls.superclass
            }
        }

        // 长按监听也看一眼（微信会话列表长按走它）
        val long = runCatching { XposedHelpers.getObjectField(list, "mOnItemLongClickListener") }
            .getOrNull()
        LogX.i("FP onItemLongClick=${long?.javaClass?.name}")
    }
}
