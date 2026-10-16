"""场景：被 @ 静音 + 免打扰时段（chat.mute_at_* / chat.dnd_*）—— 需求 6.1

用法: python tools/_mute.py [--off] [--at|--dnd]   默认两个都测

⚠️ **必须退后台**（input keyevent 3）：微信前台不响铃不震动，前台测什么都抓不到
   （这个坑白跑过三轮）。脚本会自动退后台并等你对端发消息。

⏳ 功能未实现：键已在 Keys.kt，无功能代码读取（NotifyMute 只做了通知静音那一块）。

判据：响铃/震动消失，但**角标、消息、通知栏全部照常** —— 断言只认这三条副作用，
     声音本身没法自动断言，靠日志里有没有 mute 命中。
"""
import sys
import time

from _lib import *

OFF = "--off" in sys.argv
VAL = "false" if OFF else "true"
DO_AT = "--dnd" not in sys.argv
DO_DND = "--at" not in sys.argv

step("拉起微信 + 清日志")
ensure_foreground()
logclear()

step("开关 → %s" % VAL)
if DO_AT:
    kvset("chat.mute_at_enabled", VAL)
    kvset("chat.at_aliases", "V")            # 被 @ 的别名，按需改
if DO_DND:
    kvset("chat.dnd_enabled", VAL)
    kvset("chat.dnd_start", "23:00")
    kvset("chat.dnd_end", "07:00")           # 跨天
time.sleep(2)
kvget()
time.sleep(1)

step("退后台（前台不响铃，测不出来）")
home()
time.sleep(2)
print("  act:", top())

wait_manual("用另一个号给这台发一条 @你的群消息 / 普通消息", 25)

step("断言：消息与角标仍照常（只是没声音）")
ui("@")
ui("有人@我")

pull_dbg()
print("--- 日志 ---")
grep(r"NOTIFY|MUTE|mute|at_alias|dnd", 25)
print("done  ⚠️ 记得做反向对照：python tools/_mute.py --off")
