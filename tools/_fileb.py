#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B 档（普通文件 `qf4/r5`）探针的真机验收场景。

用法（由 `python tools/dev.py loop fileb` 调用，也可单独跑）：

    # 只查就绪，不发消息（安全，推荐第一步）
    python tools/_fileb.py ready

    # 真发一个文件（会在目标会话里真的出现一条文件消息）
    python tools/_fileb.py send <talker> <本地路径>

## 判据（来自 docs/取证结论.md §「B 档探针规格」）

1. `ready` 必须打出 `fileb.ready=ok`。
   - `missing:<类名>` ⇒ 该版本类名变了，先别发；
   - `missing:qf4.r5.j(13参签名不匹配)` ⇒ 微信版本漂移，`j` 的签名变了。
2. `send` 打出 `dispatched ... NOT-SENT-YET` **只代表反射没抛异常**。
   `r5.j` 是**异步 void**（`r5.c` 先投 `hn4/t0` 线程池复制文件再入网），
   函数正常返回时消息可能还没落库、甚至已经失败但走了别的回调分支。
   ⚠️ **唯一有效成功判据是查库**：`message` 表该会话最新一条 `type=1090519089`、`isSend=1`。
3. 日志里若出现 `checkArgs fail` 或 `copyFile failed`，是**参数/路径**问题，不是链路问题。

## 为什么参数用命令行而不是写死

`talker` 和路径每次都不一样，写死会导致「以为在测 A 实际在测 B」。
必须显式传，且脚本会先做本地存在性检查 —— 假路径发给微信只会静默丢弃。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _lib import bc, ensure_foreground, grep, logclear, pull_dbg, step  # noqa: E402


def wait_log(pat, want, sec=6):
    """轮询落盘日志直到出现（或不出现）某个模式。

    微信写日志有延迟，发完广播立刻 pull 常常是空的 ——
    固定 sleep 要么太短要么白等，轮询最划算。
    """
    for _ in range(sec * 2):
        time.sleep(0.5)
        pull_dbg()
        hits = grep(pat, n=6)
        if bool(hits) == want:
            return hits
    pull_dbg()
    return grep(pat, n=6)


def do_ready():
    step("查 B 档就绪（不发消息）")
    # ⚠️ 微信在后台不投递广播
    ensure_foreground()
    logclear()
    bc("--es", "op", "forward", "--es", "sub", "filebrdy")
    hits = wait_log(r"fileb\.ready=", True)
    if not hits:
        print("!! 没有任何 fileb.ready 输出")
        print("   常见原因：模块没加载 / 微信版本不匹配 / 走的是旧 APK")
        return 1
    ok = any("fileb.ready=ok" in h for h in hits)
    step("ready=%s" % ("ok" if ok else "NOT ok"))
    return 0 if ok else 1


def do_send(talker, path):
    step("真发文件：talker=%s" % talker)
    if not os.path.isfile(path):
        print("!! 本地找不到文件：%s" % path)
        print("   ⚠️ 别用设备路径冒充本地路径 —— 这里检查的是**电脑上**的文件。")
        return 1

    ensure_foreground()
    logclear()
    # 设备侧路径：Windows 路径原样传给微信没有意义，这里要求传**手机上**的路径。
    # 若传的是本地 Windows 路径，先 adb push 到 /sdcard/Download/
    dev_path = path
    if not path.startswith("/"):
        dev_path = "/sdcard/Download/" + os.path.basename(path)
        step("push 到设备：%s" % dev_path)
        import subprocess
        from _lib import ADB
        r = subprocess.run([ADB, "push", path, dev_path],
                           capture_output=True, text=True,
                           encoding="utf-8", errors="ignore")
        if "1 file pushed" not in (r.stdout or "") + (r.stderr or ""):
            print("!! push 失败：%s" % ((r.stdout or "") + (r.stderr or "")).strip()[:200])
            return 1

    step("发广播（异步，会真的产生一条消息）")
    bc("--es", "op", "forward", "--es", "sub", "fileb",
       "--es", "talker", talker, "--es", "val", dev_path)

    hits = wait_log(r"fileb (dispatched|error)|FORWARD fileb", True, sec=8)
    if not hits:
        print("!! 没有 FORWARD fileb 输出（广播没到？模块没加载？）")
        return 1

    print()
    print("=" * 60)
    print("⚠️ 下面这些输出**不代表发送成功**，只代表反射调用没抛异常。")
    print("   唯一有效判据：查 message 表该会话最新一条 type=1090519089 / isSend=1")
    print("=" * 60)
    print()
    grep(r"checkArgs fail|copyFile failed|errFile2Uri", n=10)
    step("再等 8 秒看有没有 AppMsgLogic 的 summerbig 行（u0.D 走到了）")
    time.sleep(8)
    pull_dbg()
    hits2 = grep(r"MicroMsg\.AppMsgLogic|summerbig content url", n=10)
    if hits2:
        step("已进 u0.D ⇒ 链路走通，剩下的靠查库确认落库 type")
    else:
        step("没有 summerbig 行 ⇒ 可能还没到 u0.D，或日志 tag 变了")
    return 0


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        return 0
    cmd = a[0]
    if cmd == "ready":
        return do_ready()
    if cmd == "send":
        if len(a) < 3:
            print("用法：python tools/_fileb.py send <talker> <路径>")
            return 1
        return do_send(a[1], a[2])
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main())
