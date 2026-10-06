package com.lx.wxgroup.probe

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.core.ContactBinder
import com.lx.wxgroup.core.ContactListRef
import com.lx.wxgroup.feature.GroupPicker
import com.lx.wxgroup.util.LogX
import java.util.Collections

/**
 * 通讯录条目长按 → 弹出「加入分组」。
 *
 * 为什么用 View.performLongClick 而不是给条目挂 OnLongClickListener：
 * 微信没有对通讯录条目调用过 setOnLongClickListener（实测 hook 它一次都没触发），
 * 而 performLongClick 是所有长按的必经之路，且不依赖任何微信的类名。
 *
 * 注意：一次长按会让整条 View 链上的每个 View 都走一遍 performLongClick，
 * 所以必须做时间窗去重，否则会连弹好几个框。
 */
object MenuProbe {

    // 一次长按会沿 View 链向上依次调用 performLongClick，且间隔可能超过几百毫秒，
    // 所以既要时间窗、也要认人（同一个人短时间内只弹一次）
    private const val DEDUPE_MS = 1200L
    private const val SAME_TARGET_MS = 3000L

    private val seenOutside = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var lastAt = 0L

    @Volatile
    private var lastWxid = ""

    @Volatile
    private var lastWxidAt = 0L

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val v = param.thisObject as? View ?: return
                    onLongPress(v)
                }
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.view.View", lpparam.classLoader, "performLongClick", callback
            )
            LogX.i("MenuProbe: View.performLongClick() hooked")
        }.onFailure {
            LogX.w("MenuProbe: hook performLongClick failed: ${it.javaClass.simpleName}")
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.view.View", lpparam.classLoader, "performLongClick",
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, callback
            )
        }.onFailure { LogX.d("MenuProbe: performLongClick(x,y) not hooked") }
    }

    private fun onLongPress(v: View) {
        val rv = ContactListRef.listView ?: return
        if (!ContactListRef.contains(v)) {
            if (seenOutside.add(v.javaClass.name)) {
                LogX.d("LONGPRESS outside contacts: ${v.javaClass.name}")
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastAt < DEDUPE_MS) return
        lastAt = now

        val friend = ContactBinder.resolve(v)
        if (friend == null) {
            LogX.i("LONGPRESS unresolved (not in friend_cache yet?)")
            return
        }
        LogX.i("LONGPRESS -> ${friend.wxid} | ${friend.displayName}")
        if (friend.wxid == lastWxid && now - lastWxidAt < SAME_TARGET_MS) return
        lastWxid = friend.wxid
        lastWxidAt = now
        GroupPicker.show(friend)
    }
}
