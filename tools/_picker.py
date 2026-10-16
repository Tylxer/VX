"""场景：K3 自建选择器接入点巡检 —— 需求 D3/D4 + 6.5 + 6.6

用法: python tools/_picker.py            逐个入口打开并断言弹窗出现
      python tools/_picker.py --list     只打印待接入的入口清单

背景：K3 选择器是自建弹窗（微信进程内直接读通讯录，**不走广播、不拉起微信原生选择界面**）。
入口共 5 处，已接入 1 处（会话长按菜单「加入分组 ›」），**还剩 4 处**没接：
   ① 分组详情 → 添加成员        ② 群发 → 选择目标
   ③ 强制转发 → 选择目标        ④ 朋友圈自动导出名单
   （⑤ 会话长按 → 加入分组：已完成）

脚本做的事：打开模块主界面 → 依次点进各入口 → UI 断言弹窗是否出现、是不是 K3。
⚠️ 断言只认「弹窗出现了」，具体列表内容要人工看或用 UI 断言搜人名。
"""
import sys
import time

from _lib import *

ENTRIES = [
    ("分组管理 → 分组 → 添加成员", "添加成员"),
    ("群发 → 选择目标", "选择目标"),
    ("强制转发 → 选择目标", "转发"),
    ("朋友圈 → 自动导出名单", "导出名单"),
]

if "--list" in sys.argv:
    for i, (d, _) in enumerate(ENTRIES, 1):
        print("  待接入 %d: %s" % (i, d))
    print("  已完成  : 会话长按 → 加入分组")
    raise SystemExit(0)

step("拉起微信 + 清日志")
ensure_foreground()
logclear()

step("打开模块主界面（微信右上角「+」→「分组模块」）")
xml = dump()
tap_text("+") or tap_xy(1340, 200)
time.sleep(3)
tap_text("分组模块")
time.sleep(4)
print("  act:", top())

for desc, kw in ENTRIES:
    step("巡检：%s" % desc)
    if tap_text(kw):
        time.sleep(3)
        hits = ui(kw)
        print("  → %s：%s" % (desc, "弹窗出现 ✅" if hits else "未出现 ❌（未接入）"))
        sh("shell", "input", "keyevent", "4")   # 返回
        time.sleep(2)
    else:
        print("  → 找不到入口「%s」，跳过（可能页面路径变了）" % kw)

pull_dbg()
print("--- 日志 ---")
grep(r"Picker|picker|K3|选择", 25)
print("done")
