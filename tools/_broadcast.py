"""场景：群发 —— 需求 6.5

用法: python tools/_broadcast.py                  **dry-run**：只设配置 + 打印当前状态，不发
      python tools/_broadcast.py --really --targets=filehelper
                                                  真发（--targets 是**目标 wxid**）

🔴 **本脚本是全部场景里唯一会造成外部副作用的**：会真的把消息发出去。
   三重闸门，缺一不可：
     1. 不加 --really → 只 dry-run
     2. --targets 必须显式给（不给就拒绝真发）；≤ 3 个
     3. 发送间隔强制 ≥ 5 秒（风控缓解）

✅ 功能已接通（S9 发送链路真机验证通过 + MassSendPage 广播驱动入口）。
   探针入口：`op=broadcast sub=stat|start|pause|resume|stop`
   （start 与 UI「开始群发」按钮**共用** MassSendPage.launchRun，跑的是同一条链路）

验收点：进度 n/总数、单条失败**重试 1 次 → 仍失败则跳过继续**（不中断）、
       全部结束弹「完成 n/m」、连续失败 5 条自动中止、目标去重。

⚠️ 本脚本会**修改** broadcast.targets / last_text / interval_s 三个配置，
   跑完**不会自动还原**用户原配置 —— 测完请手动改回，或先用 kvget 记下原值。
   （本脚本不自动还原是刻意的：自动还原需要把原值再写一遍，反而多一次写库风险。）
"""
import json
import sys
import time

from _lib import *

REALLY = "--really" in sys.argv
targets = ""
for a in sys.argv[1:]:
    if a.startswith("--targets="):
        targets = a[10:]

step("拉起微信 + 清日志")
ensure_foreground()
logclear()

step("写下原配置（测完请手动改回）")
for k in ("broadcast.targets", "broadcast.last_text", "broadcast.interval_s"):
    kvget(k)
time.sleep(2)

if not REALLY:
    print("  🔴 dry-run：未加 --really，**不会发任何消息**。")
    print("     真要发：python tools/_broadcast.py --really --targets=<wxid>")
    time.sleep(1)
    bc("--es", "op", "broadcast", "--es", "sub", "stat")
    time.sleep(2)
    pull_dbg()
    grep(r"MASSSEND|kv broadcast", 20)
    print("done")
    raise SystemExit(0)

if not targets:
    print("  🔴 --really 必须显式给 --targets=<wxid>（不给目标就真发太危险，拒绝执行）")
    raise SystemExit(1)

tlist = [t.strip() for t in targets.split(",") if t.strip()]
if len(tlist) > 3:
    print("  🔴 --targets=%d 个，超过安全上限 3，拒绝执行。" % len(tlist))
    raise SystemExit(1)

# ⚠️ 值里含 " [ ] 等字符，必须用**单引号包裹整条 shell 命令**，
#    否则设备的 sh 会把 JSON 拆断（表现为只存进最后一个字符，如 "d"）。
#    Python 列表传参没用 —— adb shell 的参数会被设备 sh 二次解析。
def bc_raw(shell_cmd):
    """把整条 am 命令作为**一个字符串**发到设备 sh（保留值里的引号）"""
    sh("shell", shell_cmd)

step("配置群发参数（间隔强制 ≥5s）")
bc_raw("am broadcast -a com.lx.wxgroup.action.PROBE --es op kvset "
       "--es key broadcast.targets --es val '%s' -p com.tencent.mm"
       % json.dumps(tlist, ensure_ascii=False))
bc_raw("am broadcast -a com.lx.wxgroup.action.PROBE --es op kvset "
       "--es key broadcast.last_text --es val '自动化场景脚本测试消息' -p com.tencent.mm")
bc_raw("am broadcast -a com.lx.wxgroup.action.PROBE --es op kvset "
       "--es key broadcast.interval_s --es val '5' -p com.tencent.mm")
time.sleep(2)
bc("--es", "op", "broadcast", "--es", "sub", "stat")
time.sleep(2)
pull_dbg()
print("--- 配置确认 ---")
grep(r"MASSSEND|kvset", 20)

step("启动群发（targets=%s）" % ",".join(tlist))
bc("--es", "op", "broadcast", "--es", "sub", "start")
time.sleep(max(10, len(tlist) * 8))

bc("--es", "op", "broadcast", "--es", "sub", "stat")
time.sleep(2)
pull_dbg()
print("--- 日志 ---")
grep(r"MASSSEND|TextSender", 30)
print("done")
