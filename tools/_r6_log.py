#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""R6 取证：拉模块日志并按正则筛（避免整读 390KB+）。"""
import re
import subprocess
import sys

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOC = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_cache\r6_full.txt"


def main():
    pat = sys.argv[1] if len(sys.argv) > 1 else "SNSC"
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 40
    r = subprocess.run([ADB, "pull", DBG, LOC],
                       capture_output=True, text=True, encoding="utf-8", errors="ignore")
    print(r.stdout.strip() or r.stderr.strip())
    try:
        with open(LOC, encoding="utf-8", errors="ignore") as f:
            lines = f.read().splitlines()
    except FileNotFoundError:
        print("拉取失败")
        return
    print(f"总行数={len(lines)}")
    hits = [x for x in lines if re.search(pat, x)]
    print(f"命中={len(hits)}")
    for x in hits[-n:]:
        print(x)


if __name__ == "__main__":
    main()
