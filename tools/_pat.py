"""场景：关闭拍一拍（chat.disable_pat）—— 需求 6.1

用法: python tools/_pat.py         开开关 → 提示人工双击头像 → 断言没有「拍了拍」
      python tools/_pat.py --off   关开关做**反向对照**（必须做，否则分不清生效与否）

⏳ 功能未实现：`chat.disable_pat` 键已在 Keys.kt，无功能代码读取。
   本脚本的导航 + 开关 + 断言已经可用，功能上线后直接生效。

判据：双击聊天页对方头像后，界面上**不出现**「拍了拍」；
     反向对照（--off）时必须出现，否则说明开关根本没接上。
"""
import sys
import time

from _lib import *

OFF = "--off" in sys.argv
VAL = "false" if OFF else "true"

step("拉起微信 + 清日志")
ensure_foreground()
logclear()

step("开关 chat.disable_pat → %s" % VAL)
kvset("chat.disable_pat", VAL)
time.sleep(2)
kvget("chat.disable_pat")
time.sleep(1)

step("进一个聊天页")
xml = dump()
if not tap_text("会话") :
    tap_xy(180, 3050)          # 底部 tab 兜底坐标：微信
time.sleep(3)
tap_text("V", 0) or tap_xy(400, 400)   # ⚠️ 换成你要测的那个会话
time.sleep(4)
print("  act:", top())

wait_manual("双击对方头像（拍一拍触发手势），开开关时应无反应", 12)

step("断言：界面上有没有「拍了拍」")
hits = ui("拍了拍")
print("  期望=%s  实际=%s" % ("关开关→有 / 开开关→无",
                              "有" if hits else "无"))

pull_dbg()
print("--- 日志 ---")
grep(r"pat|PAT|disable_pat", 20)
print("done")
