"""场景：强制转发 —— 需求 6.6

用法: python tools/_forward.py [--type=文本|图片|视频|语音|文件|名片|位置|链接]

⚠️ **半自动**：长按消息这一步要人工（微信长按菜单是**自绘 PopupWindow**，
   跟会话长按菜单一样，没有可挂的 hook 入口 → 只能靠坐标点击）。
   脚本负责导航到聊天页 + 提示长按 + 断言菜单里出现**模块的**「转发」。

验收点：
 - 一律追加、允许重复：菜单里出现两个「转发」（黑色原生 + 绿色模块，带「模块」角标）
 - 混合降级：① 先走原生转发链路（保留「转发」标记）；② 被拒则降级为内容重发
   （K8 发送），并 Toast「已作为普通消息发送，无转发标记」
 - 转发目标 = K3 选择器三 Tab（好友·群聊 / 模块分组 / 微信标签），可多选
 - **不加开关，默认常开**
"""
import sys
import time

from _lib import *

mtype = "文本"
for a in sys.argv[1:]:
    if a.startswith("--type="):
        mtype = a[7:]

step("拉起微信 + 清日志")
ensure_foreground()
logclear()

step("进一个聊天页（测 %s 类型）" % mtype)
xml = dump()
tap_text("会话") or tap_xy(180, 3050)
time.sleep(3)
tap_text("V", 0) or tap_xy(400, 400)
time.sleep(4)
print("  act:", top())

wait_manual("长按一条「%s」消息（约 1 秒），弹出菜单后不要点" % mtype, 10)

step("断言：菜单里有没有模块追加的「转发」（应与原生项并存）")
hits = ui("转发")
print("  命中 %d 个「转发」—— 期望 2（原生 + 模块）" % len(hits))
ui("模块")

pull_dbg()
print("--- 日志 ---")
grep(r"forward|FORWARD|转发", 25)
print("done")
