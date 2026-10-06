package com.lx.wxgroup.core

import android.content.Context
import android.content.Intent
import com.lx.wxgroup.util.LogX

/**
 * 拉起微信的单聊页面。
 *
 * 模块自己没有 Activity，也不好从通讯录列表里模拟点击，
 * 所以只能显式启动宿主的 ChattingUI：
 *  - 类名 `com.tencent.mm.ui.chatting.ChattingUI`：8.0.48 实测存在
 *    （adb 试启动 com.tencent.mm.chatting.ui.ChattingUI 报 class does not exist，别用）
 *  - extra `Chat_User` 放 wxid，这是微信内部沿用了很多年的约定
 *
 * 微信以后改名的话，这里会抛 ActivityNotFoundException，
 * 上层要 toast 提示而不是静默失败 —— 静默失败最难排查。
 */
object ChatOpener {

    private const val HOST_PACKAGE = "com.tencent.mm"
    private const val CHATTING_UI = "com.tencent.mm.ui.chatting.ChattingUI"
    private const val EXTRA_CHAT_USER = "Chat_User"
    private const val EXTRA_CHAT_MODE = "Chat_Mode"

    fun open(context: Context?, wxid: String): Boolean {
        if (context == null || wxid.isBlank()) {
            LogX.w("CHAT open failed: ctx=${context != null} wxid=$wxid")
            return false
        }
        return runCatching {
            val intent = Intent().apply {
                setClassName(HOST_PACKAGE, CHATTING_UI)
                putExtra(EXTRA_CHAT_USER, wxid)
                putExtra(EXTRA_CHAT_MODE, 1)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            LogX.i("CHAT open wxid=$wxid")
            true
        }.onFailure {
            LogX.e("CHAT open failed wxid=$wxid", it)
        }.getOrDefault(false)
    }
}
