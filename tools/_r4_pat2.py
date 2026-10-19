#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R4 拍一拍取证第二轮：从**结果侧**确认双击到底触发了什么。

第一轮结论：GestureDetector 只抓到 android 系统自己的 ScrollGestureListener，
=> 微信头像双击**不走** GestureDetector.OnDoubleTapListener，手势侧抓不到。
本轮改走结果侧：
  1. 查 message 表里有没有新插入的「拍了拍」系统消息（确认真的触发了 / 拿到 XML 形态）
  2. 打印全部 PATDBG（确认只有系统那一个 listener）

用法：python _r4_pat2.py
"""
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOC = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_cache\pat2_dbg.txt"


def sh(*a, t=180):
    return subprocess.run([ADB, *a], capture_output=True, text=True,
                          encoding="utf-8", errors="ignore", timeout=t).stdout


def bc_raw(shell_cmd):
    """整条 am 命令作为一个字符串，值用单引号包住（设备 sh 二次解析会把引号/括号拆断）"""
    sh("shell", shell_cmd)


def sql(q, tag):
    print(f"\n=== {tag} ===")
    sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear -p com.tencent.mm")
    time.sleep(1)
    bc_raw("am broadcast -a com.lx.wxgroup.action.PROBE --es op label "
           "--es sub sql --es sql '" + q + "' -p com.tencent.mm")
    time.sleep(3)
    sh("pull", DBG, LOC)
    txt = open(LOC, encoding="utf-8", errors="ignore").read()
    for l in txt.splitlines()[-25:]:
        print("   ", l[:300])


def main():
    print("focus:", sh("shell", "dumpsys window | grep mCurrentFocus").strip()[:110])

    sh("pull", DBG, LOC)
    txt = open(LOC, encoding="utf-8", errors="ignore").read()
    lines = [l for l in txt.splitlines() if "PATDBG" in l]
    print(f"\n=== PATDBG 共 {len(lines)} 行，去重后的 listener 类 ===")
    seen = []
    for l in lines:
        if "dbltap-listener=" in l:
            c = l.split("dbltap-listener=")[1].strip()
            if c not in seen:
                seen.append(c)
    for c in seen:
        print("   ", c)

    # 1) 全库最近 6 条（带内容，看双击后落了什么）
    sql("select msgId,talker,type,isSend,createTime,substr(content,1,140) as c "
        "from message order by createTime desc limit 6",
        "全库最近 6 条消息（带内容）")

    # 2) type=10000 系统消息（拍一拍是系统消息）
    sql("select msgId,talker,type,createTime,substr(content,1,220) as c "
        "from message where type=10000 order by createTime desc limit 5",
        "最近 5 条 type=10000 系统消息")


if __name__ == "__main__":
    main()
