#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R6 #3 评论「[已删除]」取证脚本 —— 只读 + 开/关开关，**不写库**。

用法:
    python tools/_r6_snsc.py stat          # 看探针装载与开关状态
    python tools/_r6_snsc.py on            # 打开「防撤回-评论」（antirevoke.comment）
    python tools/_r6_snsc.py off           # 关掉
    python tools/_r6_snsc.py form          # ⭐ 核心：对比「删前快照」与「当前行」→ 定形态
    python tools/_r6_snsc.py list          # 看归档了哪些 (snsId, commentSvrID)

为什么要它：
    `SnsCommentLogic.DELETED_FLAG_MASK` 现在是 null —— 没人知道 `commentflag`
    哪一位代表「已删」。填错会把**正常评论**全标成 [已删除]，比不做更糟。
    这个脚本采到的数据是填那个常量的唯一依据。

判据（`form` 的输出怎么读）：
    form=GONE      → 整行没了 ⇒ 只能靠「归档里有、表里查不到」的差集判据
    form=CLEARED   → 行还在但 content 空了 ⇒ 判据看 content 是否空白
    form=FLAGGED   → 行还在且 content 也在，但 commentflag 变了 ⇒ **这才是位掩码路线**
    form=LIVE      → 跟删前一样 ⇒ 我们拦得太早（或这条压根没被删）
"""
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOC = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_cache\r6_snsc.txt"
PKG = "com.tencent.mm"
ACTION = "com.lx.wxgroup.action.PROBE"


def sh(*a, timeout=120):
    return subprocess.run(
        [ADB, *a], capture_output=True, text=True,
        encoding="utf-8", errors="ignore", timeout=timeout,
    ).stdout


def bc(*e, timeout=120):
    # ⚠️ 值一律用单引号包：设备 sh 会二次解析，空格/括号不加引号会被拆断
    return sh("shell", "am", "broadcast", "-a", ACTION,
              *e, "-p", PKG, timeout=timeout)


def front():
    """微信在后台时广播不投递 —— 先拉前台"""
    return sh("shell", "am", "start", "-n", f"{PKG}/.ui.LauncherUI")


def q(n=40, grep=None):
    """只拉最后 n 行模块日志（日志 390KB+，绝不整读）"""
    sh("pull", DBG, LOC)
    try:
        with open(LOC, encoding="utf-8", errors="ignore") as f:
            lines = f.read().splitlines()
    except FileNotFoundError:
        print("(日志还没落盘，可能微信没起来)")
        return
    if grep:
        lines = [x for x in lines if grep in x]
    for x in lines[-n:]:
        print(x)


def kvset(k, v):
    bc("--es", "op", "kvset", "--es", "key", k, "--es", "value", v)


def kvget(k):
    bc("--es", "op", "kvget", "--es", "key", k)


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "stat"
    front()
    time.sleep(1.5)

    if cmd == "on":
        kvset("antirevoke.comment", "true")
        time.sleep(0.8)
        kvget("antirevoke.comment")
        print("\n✅ 已开「防撤回-评论」。现在去朋友圈**真删一条自己发的评论**，")
        print("   删完跑： python tools/_r6_snsc.py form")
    elif cmd == "off":
        kvset("antirevoke.comment", "false")
        time.sleep(0.8)
        kvget("antirevoke.comment")
    elif cmd == "form":
        bc("--es", "op", "snsc", "--es", "sub", "form")
    elif cmd == "list":
        bc("--es", "op", "snsc", "--es", "sub", "list")
    elif cmd == "stat":
        bc("--es", "op", "snsc", "--es", "sub", "stat")
    else:
        print(f"未知命令 {cmd}")
        return

    time.sleep(2.0)
    print("\n=== 探针输出 ===")
    q(60, "SNSC")


if __name__ == "__main__":
    main()
