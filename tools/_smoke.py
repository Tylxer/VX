"""场景：新功能冒烟测试（装完模块必跑，确认没把微信搞崩）

用法: python tools/_smoke.py

做四件事：
  1. 启动微信，等 10 秒（hook 安装期）
  2. 拉日志，逐个确认「新功能 installed」行
  3. 发 quiet / snsc / redpacket 三个探针，确认探针通（能回状态）
  4. UI 断言微信还在正常界面（没崩、没卡死）

⚠️ 冒烟只证明「不崩、探针通」，**不证明功能正确**。功能正确性要靠各功能自己的
   场景脚本 + 反向对照。
"""
import time

from _lib import *

step("拉起微信")
ensure_foreground()

step("先看安装行（⚠️ 必须在 logclear **之前**拉，清掉就没了）")
time.sleep(10)
pull_dbg()
print("--- 安装行 ---")
grep(r"install|installed|failed", 30)

step("清日志再跑探针（避免旧日志干扰）")
logclear()
time.sleep(2)
print("  act:", top())

step("探针：6.1 静音四项")
# sub=all（未知 sub 同路）→ 四项探针各打一遍；只查一项时用 sub=at|pat|dnd|grp
bc("--es", "op", "quiet", "--es", "sub", "all")
time.sleep(3)

step("探针：评论防撤回 / 评论标签")
bc("--es", "op", "snsc", "--es", "sub", "stat")
time.sleep(3)

step("探针：红包")
bc("--es", "op", "redpacket", "--es", "sub", "stat")
time.sleep(3)

step("UI 断言：微信界面还活着")
# ⚠️ 断言前必须先回主页：上一步的探针若把微信留在朋友圈/聊天页，主页文案「微信」「通讯录」
# 当然找不到，会假报失败（2026-10-24 真机踩到）。连按两次返回再断言。
sh("shell", "input", "keyevent", "4")
time.sleep(1)
sh("shell", "input", "keyevent", "4")
time.sleep(2)
ensure_foreground()
time.sleep(2)
print("  act:", top())
ui("微信")
ui("通讯录")

pull_dbg()
print("--- 安装行 ---")
grep(r"install|installed|failed", 30)
print("--- 探针响应 ---")
grep(r"QUIET|SNSCG|SNSC |REDPK", 20)
print("--- 异常 ---")
grep(r"Skipped \d+ frames|ANR|FATAL|died", 10)
print("done")
