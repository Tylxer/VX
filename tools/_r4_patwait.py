#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R4 拍一拍取证：等待用户手指双击 verity 头像，然后自动收日志。

背景：adb 的 `input tap` 双击在聊天页头像上触发不了 GestureDetector
（同一手法在文本气泡上能触发，说明不是间隔问题，是头像 View 不接收注入事件），
所以这一步交给用户手指双击，本脚本只负责等 + 收。

用法：
    python _r4_patwait.py [等待秒数，默认 150]
"""
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOC = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_cache\pat_dbg.txt"

WAIT = int(sys.argv[1]) if len(sys.argv) > 1 else 150


def sh(*a, t=180):
    return subprocess.run([ADB, *a], capture_output=True, text=True, timeout=t).stdout


def main():
    print(f"等待用户双击 {WAIT} 秒 ...")
    time.sleep(WAIT)

    print("focus:", sh("shell", "dumpsys window | grep mCurrentFocus").strip()[:110])
    sh("pull", DBG, LOC)
    txt = open(LOC, encoding="utf-8", errors="ignore").read()

    lines = [l for l in txt.splitlines() if "PATDBG" in l]
    if not lines:
        print("\n[结果] 没有任何 PATDBG —— 双击没触发，或界面不在聊天页")
        print("\n=== 日志尾部 10 行（看微信在干什么）===")
        for l in txt.splitlines()[-10:]:
            print("   ", l[:180])
        return

    print(f"\n[结果] 抓到 {len(lines)} 行 PATDBG")

    # 关键：dbltap-listener 的类名
    for l in lines:
        if "dbltap-listener=" in l:
            cls = l.split("dbltap-listener=")[1].strip()
            verdict = "微信自己的类 ✅ 可用" if cls.startswith("com.tencent.mm") else \
                      ("第三方/文本控件 ❌ 不是拍一拍" if cls.startswith("com.tencent.") else "android 默认 ❌")
            print(f"\n  dbltap-listener = {cls}")
            print(f"  判定：{verdict}")

    # 栈里前 14 帧（微信自己的类名才是锚点）
    print("\n=== 栈（前 14 帧）===")
    for l in lines[:14]:
        print("   ", l[:190])


if __name__ == "__main__":
    main()
