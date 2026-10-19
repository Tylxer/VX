#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R5 #6 旧功能补验收：被@静音 / 免打扰时段 / 新加群自动免打扰
代码 2026-10-17 就位，一直没上真机。本脚本做「静态自检 + 场景等待」两段。

用法:
    python _r5_quiet.py --nowait          # 只跑静态自检（开关/数据源/判定），不停留
    python _r5_quiet.py --wait=90         # 自检后等你构造 @ 场景，90 秒后收日志
    python _r5_quiet.py --dnd-now         # 把免打扰时段设成「覆盖当前时间」，验证时段判定

判据（先写死）：
    被@静音   → `quiet sub=at sub2=status` 的 matched 在 @ 发生后应 > 0，且消息照收
    免打扰时段 → 时段覆盖当前时刻时 `quiet sub=dnd sub2=status` 判定为 in-window
    新加群     → `quiet sub=grp sub2=status` 钩子存活
"""
import subprocess
import sys
import time
from datetime import datetime, timedelta

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOC = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_cache\r5_quiet.txt"

WAIT = 0
DND_NOW = False
for a in sys.argv[1:]:
    if a.startswith("--wait="):
        WAIT = int(a[7:])
    elif a == "--dnd-now":
        DND_NOW = True


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


def dump(tag, keys=("QUIET", "MUTEAT", "DND", "GRP")):
    txt = pull()
    print("\n=== %s ===" % tag)
    n = 0
    for l in txt.splitlines():
        if any(k in l for k in keys):
            print("   ", l[:240])
            n += 1
    if n == 0:
        print("    (无 %s 日志)" % "/".join(keys))


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(3)
    bc("--es", "op", "logclear")
    time.sleep(1)

    # ---- 静态自检 ----
    bc("--es", "op", "kvset", "--es", "key", "chat.mute_at_enabled", "--es", "val", "true")
    time.sleep(1)
    bc("--es", "op", "kvset", "--es", "key", "chat.auto_dnd_new_group", "--es", "val", "true")
    time.sleep(1)

    if DND_NOW:
        now = datetime.now()
        s = (now - timedelta(minutes=5)).strftime("%H:%M")
        e = (now + timedelta(hours=2)).strftime("%H:%M")
        print(">>> 免打扰时段设为 %s ~ %s（覆盖当前时刻）" % (s, e))
        bc("--es", "op", "kvset", "--es", "key", "chat.dnd_enabled", "--es", "val", "true")
        time.sleep(1)
        bc("--es", "op", "kvset", "--es", "key", "chat.dnd_start", "--es", "val", s)
        time.sleep(1)
        bc("--es", "op", "kvset", "--es", "key", "chat.dnd_end", "--es", "val", e)
        time.sleep(1)

    bc("--es", "op", "quiet", "--es", "sub", "at", "--es", "sub2", "status")
    time.sleep(2)
    bc("--es", "op", "quiet", "--es", "sub", "at", "--es", "sub2", "scope")
    time.sleep(2)
    bc("--es", "op", "quiet", "--es", "sub", "at", "--es", "sub2", "grpstat")
    time.sleep(2)
    bc("--es", "op", "quiet", "--es", "sub", "at", "--es", "sub2", "grplist")
    time.sleep(2)
    bc("--es", "op", "quiet", "--es", "sub", "dnd", "--es", "sub2", "status")
    time.sleep(2)
    bc("--es", "op", "quiet", "--es", "sub", "grp", "--es", "sub2", "status")
    time.sleep(2)
    dump("静态自检")

    if WAIT <= 0:
        return

    print("\n>>> 现在请：让另一个账号在群里 @ 你（或用小号发一条 @ 你的消息）")
    print(">>> 等待 %d 秒..." % WAIT)
    for i in range(WAIT // 10):
        time.sleep(10)
        print("    ...%ds" % ((i + 1) * 10))
    bc("--es", "op", "quiet", "--es", "sub", "at", "--es", "sub2", "status")
    time.sleep(2)
    dump("被@场景结果")


if __name__ == "__main__":
    main()
