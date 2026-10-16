"""场景：自动拆红包（redpacket.auto_open）—— 需求 6.3

用法: python tools/_redpacket.py [--off]

⚠️ **半自动 + 要花钱**：发红包/领红包无法脚本化，脚本负责开关 + 提示 + 断言。
   建议用最小金额（0.01）自收发测。

需求边界（别实现歪）：**不是**自动点开红包消息，而是**用户手动点开红包、
领取界面弹出时**自动点击「拆红包 / 领取」。不 hook 消息接收、不在后台触发。
"""
import sys
import time

from _lib import *

OFF = "--off" in sys.argv
VAL = "false" if OFF else "true"

step("拉起微信 + 清日志")
ensure_foreground()
logclear()

step("开关 redpacket.auto_open → %s" % VAL)
kvset("redpacket.auto_open", VAL)
time.sleep(2)
kvget("redpacket.auto_open")
time.sleep(1)

wait_manual("让对方发个红包（0.01 即可）→ 你手动点开红包消息，"
            "看领取界面是否自动被拆开（不点也会自己开）", 25)

step("断言：领取界面上还有没有「拆红包」按钮（自动开的话它会被秒掉）")
ui("拆红包")
ui("已领取")
ui("已存入零钱")

pull_dbg()
print("--- 日志 ---")
grep(r"redpacket|RED|红包", 20)
print("done  ⚠️ 反向对照：python tools/_redpacket.py --off")
