#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R5 #2 红包「自然触发」验收脚本（双向对照）

用法:
    python tools/_r5_redpacket.py --wait=120     # 打开开关，轮询直到拆开或超时
    python tools/_r5_redpacket.py --phase=off    # 关闭开关，反向对照
    python tools/_r5_redpacket.py --nowait       # 只 dump 当前 stat / tree

判据（先写死，避免现场扯皮）：
    开关 ON  → 自然点开红包后，stat 里 **opened 必须 > 0**（dispatched>0 但 opened=0 = 失败）
    开关 OFF → 同样操作不应出现 dispatched / opened；
              `sub=dry`（只扫不点）仍能命中，证明扫描链路本身活着

⚠️ 只看 opened，不看 dispatched：
    dispatched 只代表"触摸事件派发出去了"，performClick 那次翻车就是 dispatched=6/opened=0。
"""
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOC = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_cache\r5_hb.txt"

WAIT = 120
PHASE = "on"
for a in sys.argv[1:]:
    if a.startswith("--wait="):
        WAIT = int(a[7:])
    elif a == "--nowait":
        WAIT = 0
    elif a.startswith("--phase="):
        PHASE = a[8:]


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


def dump(tag, keys=("REDPACKET", "LUCKY")):
    txt = pull()
    print("\n=== %s ===" % tag)
    n = 0
    for l in txt.splitlines():
        if any(k in l for k in keys):
            print("   ", l[:240])
            n += 1
    if n == 0:
        print("    (无 %s 日志)" % "/".join(keys))
        for l in txt.splitlines()[-6:]:
            print("   |", l[:180])


def counters():
    """从日志里取**最后一条** stat 的计数。返回 dict 或 None。"""
    txt = pull()
    last = None
    for l in txt.splitlines():
        if "REDPACKET stat" in l:
            last = l
    if not last:
        return None
    out = {}
    for tok in last.split():
        if "=" in tok:
            k, _, v = tok.partition("=")
            out[k] = v
    return out


def focus():
    f = sh("shell", "dumpsys window | grep mCurrentFocus")
    return f.strip()[:150]


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(3)

    val = "true" if PHASE == "on" else "false"
    bc("--es", "op", "logclear")
    time.sleep(1)
    bc("--es", "op", "kvset", "--es", "key", "redpacket.auto_open", "--es", "val", val)
    time.sleep(2)
    bc("--es", "op", "redpacket", "--es", "sub", "reset")
    time.sleep(1)
    bc("--es", "op", "redpacket", "--es", "sub", "stat")
    time.sleep(2)
    dump("开关设置为 %s 后的 stat" % val)

    if WAIT <= 0:
        bc("--es", "op", "redpacket", "--es", "sub", "tree")
        time.sleep(3)
        dump("当前界面 tree")
        return

    if PHASE == "on":
        print("\n>>> 现在请：在任意群发一个 0.01 元红包 → 点开 → 停在「开」按钮界面")
        print(">>> 模块应自己点掉；脚本每 4 秒查一次，拆开就立刻报告（最多等 %d 秒）" % WAIT)
    else:
        print("\n>>> 反向对照：开关已关。同样发一个 0.01 红包 → 点开 → 停在「开」按钮界面")
        print(">>> 预期：模块**不动**，界面上「开」一直在（最多等 %d 秒）" % WAIT)

    ok = False
    for i in range(WAIT // 4):
        time.sleep(4)
        bc("--es", "op", "redpacket", "--es", "sub", "stat")
        time.sleep(1)
        c = counters() or {}
        op = c.get("opened", "0")
        dp = c.get("dispatched", "0")
        fnd = c.get("found", "0")
        top = c.get("top", "?")
        short = top.rsplit(".", 1)[-1]
        print("  [+%3ds] found=%-2s dispatched=%-2s opened=%-2s top=%s"
              % ((i + 1) * 4, fnd, dp, op, short))
        if PHASE == "on" and op not in ("0", "", "?"):
            ok = True
            print("\n*** 拆开成功：opened=%s ***" % op)
            break
        if PHASE != "on" and (dp not in ("0", "") or op not in ("0", "")):
            ok = True
            print("\n*** 反向对照失败：开关关着却动了 dispatched=%s opened=%s ***" % (dp, op))
            break

    time.sleep(2)
    dump("结果（%s）" % ("开关 ON" if PHASE == "on" else "开关 OFF 反向对照"))
    print("\n当前界面:", focus())

    if PHASE == "on":
        print("\n>>> 结论:", "PASS 自动拆开" if ok else "FAIL 未拆开（看上面 opened 是否一直 0）")
    else:
        print("\n>>> 结论:", "FAIL 开关关着还动了" if ok else "PASS 开关关闭时无动作（反向对照成立）")


if __name__ == "__main__":
    main()
