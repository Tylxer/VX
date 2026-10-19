#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R4 6.3 红包取证：真发 0.01 红包 → 停在「开」界面 → dump Activity 类名 + View 树。

⚠️ 取证轮**刻意关掉** redpacket.auto_open：自动点会把「开」按钮秒掉，
   就拿不到按钮文案了。等锚点确认后再开开关做验证轮。

用法：
    python _r4_hb.py          # 取证轮（关开关，等人操作 100s，然后 dump）
    python _r4_hb.py --wait 0 # 只清日志不等待（用于第二次 dump 已停留的界面）
"""
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOC = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_cache\hb_dbg.txt"

WAIT = 100
for a in sys.argv[1:]:
    if a.startswith("--wait="):
        v = a[7:]
        WAIT = int(v) if v.isdigit() else 0
    if a == "--nowait":
        WAIT = 0


def sh(*a, t=300):
    return subprocess.run([ADB, *a], capture_output=True, text=True,
                          encoding="utf-8", errors="ignore", timeout=t).stdout


def bc(*extra):
    sh("shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE",
       *extra, "-p", "com.tencent.mm")


def main():
    sh("shell", "am start -n com.tencent.mm/.ui.LauncherUI")
    time.sleep(3)
    bc("--es", "op", "logclear")
    time.sleep(1)
    # 取证轮：关自动拆，避免按钮被秒掉
    bc("--es", "op", "kvset", "--es", "key", "redpacket.auto_open", "--es", "val", "false")
    time.sleep(2)
    print("已关闭 redpacket.auto_open（取证需要留住「开」按钮）")

    if WAIT:
        print("\n" + "=" * 60)
        print("现在请你在手机上：")
        print("  1) 打开任意一个群（或小号）→ 发一个 0.01 元红包")
        print("  2) 自己点开这个红包，停在「开 / 拆红包」那一步 —— 先别点开")
        print(f"  3) 保持界面不动，脚本 {WAIT} 秒后自动 dump")
        print("=" * 60 + "\n")
        time.sleep(WAIT)

    bc("--es", "op", "redpacket", "--es", "sub", "stat")
    time.sleep(2)
    bc("--es", "op", "redpacket", "--es", "sub", "tree")
    time.sleep(3)
    sh("pull", DBG, LOC)

    txt = open(LOC, encoding="utf-8", errors="ignore").read()
    lines = txt.splitlines()
    print("\n=== REDPK / REDPACKET 日志（%d 行）===" %
          len([l for l in lines if "REDP" in l or "REDPACKET" in l]))
    for l in lines:
        if "REDP" in l or "REDPACKET" in l:
            print("   ", l[:220])

    print("\n=== 当前 Activity（dumpsys）===")
    print("   ", sh("shell", "dumpsys window | grep mCurrentFocus").strip()[:140])


if __name__ == "__main__":
    main()
