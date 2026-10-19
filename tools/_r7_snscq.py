#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R7 朋友圈评论标签探针 —— 任意 sub，**不改变当前页面**。

用法:
    python tools/_r7_snscq.py stat
    python tools/_r7_snscq.py types     # 看最后 bind 的 itemView 子树类名分布
    python tools/_r7_snscq.py list
    python tools/_r7_snscq.py pending
    python tools/_r7_snscq.py mark 1234567890

为什么要它（和 `_r6_snsc.py` 的区别）:
    `_r6_snsc.py` 的 `front()` 会 `am start LauncherUI`，把朋友圈顶掉 ——
    但朋友圈标签这类功能必须**停在 ImproveSnsTimelineUI 上滚**才有意义。
    所以这个脚本只做「广播 + 拉日志」，不碰前台页面。

    ⚠️ 唯一的例外是微信**完全在后台**时广播不投递。
       这时先手动点回微信，或者传 `--front` 走一次 LauncherUI。
"""
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOC = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_cache\r7_snscq.txt"
PKG = "com.tencent.mm"
ACTION = "com.lx.wxgroup.action.PROBE"


def sh(*a, timeout=120):
    return subprocess.run(
        [ADB, *a], capture_output=True, text=True,
        encoding="utf-8", errors="ignore", timeout=timeout,
    ).stdout


def bc(*e, timeout=120):
    # ⚠️ 值一律用单引号包：设备 sh 会二次解析，空格/括号不加引号会被拆断
    return sh("shell", "am", "broadcast", "-a", ACTION, *e, "-p", PKG, timeout=timeout)


def pull(n=60, grep="SNSC"):
    """只拉尾巴（日志 390KB+，绝不整读）

    ⚠️ grep 只用来**定位起点**：probe 返回的是多行字符串，只有首行带 "SNSC" 前缀，
    后续 `appendLine` 的行没有前缀。所以命中第一行后要**连后面的行一起打**，
    否则 `pending` / `types` 这类多行输出只剩个标题，等于没探。
    """
    sh("pull", DBG, LOC)
    try:
        with open(LOC, encoding="utf-8", errors="ignore") as f:
            lines = f.read().splitlines()
    except FileNotFoundError:
        print("(日志还没落盘，可能微信没起来)")
        return
    if grep:
        # 从**日志尾部往前**收集连续包含 grep 的行块 = 最后一条 probe 输出。
        # ⚠️ 不能「取最后一次命中的单行」：probe 输出是多行，前面几行才是主体
        # （`types` 的类名清单、`pending` 的字段说明），只取最后一行等于没探。
        end = len(lines)
        while end > 0 and grep not in lines[end - 1]:
            end -= 1
        if end == 0:
            print(f"(日志里没有 {grep})")
            return
        start = end - 1
        while start > 0 and grep in lines[start - 1]:
            start -= 1
        lines = lines[start:end]
        lines = lines[-n:]
    else:
        lines = lines[-n:]
    for x in lines:
        print(x)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = [a for a in sys.argv[1:] if a.startswith("--")]
    sub = args[0] if args else "stat"
    extra = args[1:] if len(args) > 1 else []

    if "--front" in flags:
        sh("shell", "am", "start", "-n", f"{PKG}/.ui.LauncherUI")
        time.sleep(1.5)

    # 清日志：避免旧轮次的 stat 混进来（环形日志 5000 行，跨轮次极易误读）
    if "--keep" not in flags:
        bc("--es", "op", "logclear")

    # 带 long 参数的 sub：id 是 getLongExtra → 必须 --el（不是 --es），否则 probe 收到 0 直接返回
    if sub in ("mark", "seed") and extra:
        e = ["--el", "id", extra[0]]
        if sub == "seed":
            # 渲染键是字符串 → --es key（形如 1792410895:wxid_xxx:2）
            e += ["--es", "key", extra[1] if len(extra) > 1 else ""]
        bc("--es", "op", "snsc", "--es", "sub", sub, *e)
    else:
        bc("--es", "op", "snsc", "--es", "sub", sub)

    time.sleep(2.5)
    print(f"=== sub={sub} ===")
    pull(80)


if __name__ == "__main__":
    main()
