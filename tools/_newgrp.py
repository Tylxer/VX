"""场景：新加群自动免打扰（chat.auto_dnd_new_group）—— 需求 6.1

用法: python tools/_newgrp.py [--off]

⚠️ **半自动**：进群（被邀请 / 扫码）无法脚本化，脚本负责开关 + 提示 + 断言。

⏳ 功能未实现：键已在 Keys.kt，无功能代码。另有已记载的不确定性：
   「进群事件不一定有稳定 hook 点，定位不到会漏设」→ 备选退路是定期扫描群列表补设。

四条判定（实现时按这个验）：
  新群 = 进群瞬间设置；自建群 = 群主是自己（**不设**）；存量群 = 只管新加的；
  手动关掉后**不会再自动打开**。
"""
import sys
import time

from _lib import *

OFF = "--off" in sys.argv
VAL = "false" if OFF else "true"

step("拉起微信 + 清日志")
ensure_foreground()
logclear()

step("开关 chat.auto_dnd_new_group → %s" % VAL)
kvset("chat.auto_dnd_new_group", VAL)
time.sleep(2)

wait_manual("让别人拉你进一个群（或扫码进群）。⚠️ 自建群不算 —— 群主是自己应当不设", 30)

step("进刚加的那个群 → 打开聊天信息看「消息免打扰」")
xml = dump()
tap_text("会话") or tap_xy(180, 3050)
time.sleep(3)
tap_text("群聊")
time.sleep(4)
print("  act:", top())

step("断言：该群的免打扰是否被自动打开")
ui("消息免打扰")

pull_dbg()
print("--- 日志 ---")
grep(r"dnd|DND|new_group|auto_dnd", 20)
print("done")
