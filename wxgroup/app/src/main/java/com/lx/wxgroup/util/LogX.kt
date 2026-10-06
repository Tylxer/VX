package com.lx.wxgroup.util

import android.util.Log
import de.robv.android.xposed.XposedBridge

object LogX {

    private const val TAG = "WXGroup"

    fun d(msg: String) {
        Log.d(TAG, msg)
        bridgeLog("D $msg")
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        bridgeLog("I $msg")
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        bridgeLog("W $msg")
    }

    fun e(msg: String, throwable: Throwable? = null) {
        Log.e(TAG, msg, throwable)
        bridgeLog("E $msg")
        if (throwable != null) {
            bridgeLog(throwable)
        }
    }

    // 每个入口都要能吞掉异常：日志组件本身绝不能成为崩溃源
    private fun bridgeLog(msg: String) {
        runCatching { XposedBridge.log("$TAG/$msg") }
    }

    private fun bridgeLog(throwable: Throwable) {
        runCatching { XposedBridge.log(throwable) }
    }
}
