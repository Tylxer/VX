#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R5 #5 朋友圈导出端到端验收
用法: python _r5_export.py [--n 3]

判据（先写死）：
    `moments sub=path`      → root 存在且可写
    `moments sub=imgdl`     → 视频应真落盘（.mp4）；图片 CDN 恒 400 已结案，**图片存不了属预期**
    落盘后用 adb ls 复核文件真实存在且 size>0（不只看日志里的 "ok"）
"""
import re
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOC = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_cache\r5_export.txt"

N = 3
for a in sys.argv[1:]:
    if a.startswith("--n="):
        N = int(a[4:])


def sh(*a):
    return subprocess.run(
        [ADB, *a], capture_output=True, text=True,
        encoding="utf-8", errors="ignore", timeout=300,
    ).stdout


def bc(*e):
    sh("shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE",
       *e, "-p", "com.tencent.mm")


def pull():
    sh("pull", DBG, LOC)
    try:
        return open(LOC, encoding="utf-8", errors="ignore").read()
    except OSError:
        return ""


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(3)
    bc("--es", "op", "logclear")
    time.sleep(1)

    bc("--es", "op", "moments", "--es", "sub", "stat")
    time.sleep(2)
    bc("--es", "op", "moments", "--es", "sub", "path")
    time.sleep(3)
    txt = pull()
    root = None
    for l in txt.splitlines():
        if "root=" in l:
            m = re.search(r"root=(\S+)", l)
            if m:
                root = m.group(1)
            print("   ", l[:220])
    print(">>> 导出根: %s" % root)

    print("\n=== 触发下载 imgdl n=%d ===" % N)
    bc("--es", "op", "moments", "--es", "sub", "imgdl", "--ei", "n", str(N))
    time.sleep(25)
    txt = pull()
    for l in txt.splitlines():
        if "MOMENT" in l or "IMGDL" in l or "download" in l.lower():
            print("   ", l[:230])

    if root:
        print("\n=== 落盘复核（adb ls）===")
        out = sh("shell", "su -c 'find %s -type f -printf \"%%s %%p\\\\n\" 2>/dev/null | head -25'" % root)
        print(out.strip() or "    (find 无输出，试 ls)")
        if not out.strip():
            print(sh("shell", "ls -laR %s" % root).strip()[:1500])


if __name__ == "__main__":
    main()
