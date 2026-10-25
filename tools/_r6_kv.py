#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R6 配置读写小工具。

⚠️ 两个已踩过的坑（改之前先看）：
1. kvset 的参数名是 **`val`** 不是 `value` —— 用错会静默写入空串
   （`PROBE kvset antirevoke.comment= -> now=`）。
2. adb shell 的参数会被**设备 sh 二次解析**：Python 列表传参没用，
   必须把**整条 am 命令作为一个字符串**发过去，值再用单引号包。
   代码里自己拼引号送进列表参数 → 值变空。

用法:
    python tools/_r6_kv.py get antirevoke.comment
    python tools/_r6_kv.py set antirevoke.comment true
    python tools/_r6_kv.py on          # 一次开两个评论开关
    python tools/_r6_kv.py off
"""
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOC = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_cache\r6_kv.txt"
PKG = "com.tencent.mm"
ACTION = "com.lx.wxgroup.action.PROBE"


def sh(*a, timeout=120):
    return subprocess.run(
        [ADB, *a], capture_output=True, text=True,
        encoding="utf-8", errors="ignore", timeout=timeout,
    ).stdout


def raw(shell_cmd, timeout=120):
    """整条 am 命令作为一个字符串发到设备 sh（唯一正确姿势）"""
    return sh("shell", shell_cmd, timeout=timeout)


def kvset(k, v):
    return raw(f"am broadcast -a {ACTION} --es op kvset "
               f"--es key {k} --es val '{v}' -p {PKG}")


def kvget(k):
    return raw(f"am broadcast -a {ACTION} --es op kvget "
               f"--es key {k} -p {PKG}")


def probe(op, **kw):
    s = f"am broadcast -a {ACTION} --es op {op}"
    for k, v in kw.items():
        s += f" --es {k} '{v}'"
    return raw(s + f" -p {PKG}")


def front():
    sh("shell", "am", "start", "-n", f"{PKG}/.ui.LauncherUI")


def tail(n=25, grep=None):
    sh("pull", DBG, LOC)
    try:
        with open(LOC, encoding="utf-8", errors="ignore") as f:
            lines = f.read().splitlines()
    except FileNotFoundError:
        print("(日志未落盘)")
        return
    if grep:
        lines = [x for x in lines if grep in x]
    for x in lines[-n:]:
        print(x)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    front()
    time.sleep(1.2)

    if cmd == "get":
        for k in sys.argv[2:]:
            kvget(k)
            time.sleep(0.9)
        time.sleep(1.5)
        print("\n=== KV 回读 ===")
        tail(20, "PROBE kv")
    elif cmd == "set":
        k, v = sys.argv[2], sys.argv[3]
        kvset(k, v)
        time.sleep(1.0)
        kvget(k)
        time.sleep(1.5)
        print("\n=== KV 回读 ===")
        tail(20, "PROBE kv")
    elif cmd == "on":
        for k in ("antirevoke.comment", "antirevoke.comment_deleted_tag"):
            kvset(k, "true")
            time.sleep(0.9)
        for k in ("antirevoke.comment", "antirevoke.comment_deleted_tag"):
            kvget(k)
            time.sleep(0.9)
        time.sleep(1.5)
        print("\n=== 回读（应为 true / true）===")
        tail(25, "PROBE kv")
    elif cmd == "off":
        for k in ("antirevoke.comment", "antirevoke.comment_deleted_tag"):
            kvset(k, "false")
            time.sleep(0.9)
        time.sleep(1.5)
        print("\n=== 回读（应为 false / false）===")
        tail(25, "PROBE kv")
    elif cmd == "snsc":
        # ⛔ 2026-10-24 N7：评论域代码已物理删除，op=snsc 探针已移除。
        #    这里保留分支只为让老命令行不报「未知命令」；实际 probe 只会返回
        #    「OP snsc 已移除」的告警。取证方法论见 tools/_r6_snsc.py 横幅。
        sub = sys.argv[2] if len(sys.argv) > 2 else "stat"
        probe("snsc", sub=sub)
        time.sleep(2.0)
        print(f"\n=== SNSC {sub} ===")
        tail(40, "SNSC")
    else:
        print(f"未知命令 {cmd}")


if __name__ == "__main__":
    main()
