"""场景：从归档恢复（sns_archive → SnsInfo）—— 6.4 导出的前置

用法: python tools/_restore.py           列归档 + 尝试恢复
      python tools/_restore.py --check   只列归档与当前可见位，不写库

⏳ 功能未实现：探针 `snsg sub=restore` 还没有（只有 flags / fixall / simdel / dump / cmts）。
   本脚本先把「恢复前基线 → 恢复 → 验证行回来了」这三步固化，探针上线即可用。

⚠️ 恢复是**反插回微信的 SnsInfo 表**，属于写对方数据库 → 恢复前务必先跑一次 --check
   看清楚归档里都有什么，且一次只恢复一条验证。
"""
import sys
import time

from _lib import *

CHECK = "--check" in sys.argv
target = None
for a in sys.argv[1:]:
    if a.startswith("--id="):
        target = a[5:]

step("拉起微信 + 清日志")
ensure_foreground()
logclear()

step("归档基线（sns_archive 全量）")
bc("--es", "op", "snsg", "--es", "sub", "dump", "--ei", "n", "20")
time.sleep(3)

step("归档里每条的**当前** localFlag（⚠️ dump 里的 flag 是快照，别当依据）")
bc("--es", "op", "snsg", "--es", "sub", "flags")
time.sleep(3)

if CHECK:
    pull_dbg()
    print("--- 日志（--check，未写库）---")
    grep(r"SNSG|SNSDMP|restore", 30)
    print("done")
    raise SystemExit(0)

if not target:
    print("  ⚠️ 未指定 --id=<snsId>，只做了基线。指定后才会调 restore。")
else:
    step("恢复 %s" % target)
    bc("--es", "op", "snsg", "--es", "sub", "restore", "--el", "id", target)
    time.sleep(4)
    step("验证：这条在 SnsInfo 里回来了吗")
    bc("--es", "op", "snsg", "--es", "sub", "show", "--el", "id", target)
    time.sleep(3)

step("进朋友圈看视觉结果")
xml = dump()
tap_text("发现") or tap_xy(900, 3050)
time.sleep(4)
tap_text("朋友圈")
time.sleep(8)

pull_dbg()
print("--- 日志 ---")
grep(r"SNSG|restore|SNSDMP", 25)
print("done")
