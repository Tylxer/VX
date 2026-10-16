"""场景：群发 —— 需求 6.5

用法: python tools/_broadcast.py              **dry-run**：只开开关 + 打印将要做什么，不发
      python tools/_broadcast.py --really      真的发（还要显式给目标数）

🔴 **本脚本是全部场景里唯一会造成外部副作用的**：会真的把消息发出去。
   三重闸门，缺一不可：
     1. 不加 --really → 只 dry-run
     2. --targets 必须 ≤ 3（多了不给发）
     3. 发送间隔强制 ≥ 5 秒（风控缓解）

⏳ 功能未实现：broadcast.* 键已在 Keys.kt，无功能代码。

验收点：进度 n/总数、单条失败**重试 1 次 → 仍失败则跳过继续**（不中断）、
       全部结束弹「完成 n/m」、连续失败 5 条自动中止、目标去重。
"""
import sys
import time

from _lib import *

REALLY = "--really" in sys.argv
targets = 1
for a in sys.argv[1:]:
    if a.startswith("--targets="):
        targets = int(a[10:])

step("拉起微信 + 清日志")
ensure_foreground()
logclear()

step("开关/参数（间隔强制 ≥5s）")
kvset("broadcast.interval_s", "5")
kvset("broadcast.last_text", "自动化场景脚本测试消息")
time.sleep(2)

if not REALLY:
    print("  🔴 dry-run：未加 --really，**不会发任何消息**。")
    print("     真要发：python tools/_broadcast.py --really --targets=1")
    kvget()
    time.sleep(2)
    pull_dbg()
    grep(r"broadcast|BROADCAST", 20)
    print("done")
    raise SystemExit(0)

if targets > 3:
    print("  🔴 --targets=%d 超过安全上限 3，拒绝执行。" % targets)
    raise SystemExit(1)

step("真发（targets=%d）" % targets)
bc("--es", "op", "broadcast", "--es", "sub", "start", "--ei", "n", str(targets))
time.sleep(10)

pull_dbg()
print("--- 日志 ---")
grep(r"broadcast|BROADCAST|send|SEND", 30)
print("done")
