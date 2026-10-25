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


def getkv(key):
    """读当前配置值（`PROBE kv <key> = <val>` 形态）。

    ⚠️ 设备无 sqlite3 ⇒ 只能走广播；读不到返回 None（调用方须跳过复位，别写坏配置）。
    """
    import re
    bc("--es", "op", "kvget", "--es", "key", key)
    time.sleep(1.5)
    m = re.search(r"PROBE kv %s = (\S+)" % re.escape(key), pull())
    return m.group(1) if m else None


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

    # 记住用户原值，结束/异常时复位（2026-10-24 修：原版无条件置 true 且不复位，
    # 会把用户本来就关着的功能偷偷打开，真机上很难察觉）
    restore = {}

    def setkv(key, val):
        if key not in restore:
            restore[key] = getkv(key)
        bc("--es", "op", "kvset", "--es", "key", key, "--es", "val", str(val))
        time.sleep(1)

    try:
        # ---- 静态自检 ----
        setkv("chat.mute_at_enabled", "true")
        setkv("chat.auto_dnd_new_group", "true")

        if DND_NOW:
            now = datetime.now()
            s = (now - timedelta(minutes=5)).strftime("%H:%M")
            e = (now + timedelta(hours=2)).strftime("%H:%M")
            print(">>> 免打扰时段设为 %s ~ %s（覆盖当前时刻）" % (s, e))
            setkv("chat.dnd_enabled", "true")
            setkv("chat.dnd_start", s)
            setkv("chat.dnd_end", e)

        bc("--es", "op", "quiet", "--es", "sub", "at", "--es", "sub2", "status")
        time.sleep(2)
        bc("--es", "op", "quiet", "--es", "sub", "at", "--es", "sub2", "scope")
        time.sleep(2)
        bc("--es", "op", "quiet", "--es", "sub", "at", "--es", "sub2", "grpstat")
        time.sleep(2)
        bc("--es", "op", "quiet", "--es", "sub", "at", "--es", "sub2", "grplist")
        time.sleep(2)
        # ⚠️ status 不含 active；时段判定**只能**看 sub2=window（2026-10-24 修）
        bc("--es", "op", "quiet", "--es", "sub", "dnd", "--es", "sub2", "status")
        time.sleep(2)
        bc("--es", "op", "quiet", "--es", "sub", "dnd", "--es", "sub2", "window")
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
    finally:
        # 无论正常结束还是 Ctrl-C，都把改过的键复位
        if restore:
            print("\n=== 复位本次改动的配置 ===")
            for k, v in restore.items():
                if v is None:
                    print("    %s 原值未知，跳过（请手工确认）" % k)
                    continue
                bc("--es", "op", "kvset", "--es", "key", k, "--es", "val", str(v))
                time.sleep(1)
                print("    %s -> %s（原值）" % (k, v))


if __name__ == "__main__":
    main()
