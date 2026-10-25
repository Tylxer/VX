# ⛔ 本脚本涉及的功能已于 2026-10-20 停止、2026-10-24 **物理删除代码**（N7）。
#    `op=snsc` 探针已移除（`EntryReceiver` 里只留一行「已移除」告警），
#    因此下面所有 `op=snsc ...` 命令**现在只会打出一条提示，不会产出数据**。
#    保留本文件的目的：**它的取证方法论仍有效**（怎么对比删前快照与当前行、
#    form 的几种形态怎么判），将来若重启评论域需求可直接复用。
#    备份的原实现见 `docs/_r18_snscomment_backup/`。
"""场景：评论防撤回 + 评论 [已删除] 标签（需求 6.2 第 4/5 项）

用法: python tools/_cmt.py [--off]        --off = 关开关做反向对照
      python tools/_cmt.py --probe         只跑 DB 取证（不碰开关）

⏳ 功能未实现：`antirevoke.comment` / `antirevoke.comment_deleted_tag` 两个键已在 Keys.kt，
   但没有功能代码读取，探针 `op=snsc` 也还没有。脚本现在能跑通的部分：开关、导航、
   评论表取证；探针返回 unknown 属预期，功能上线后本脚本直接生效。

⚠️ 开工前必须先实证：删评论时微信有没有重写 SnsInfo.attrBuf（决定工作量差一个量级）。
"""
import sys
import time

from _lib import *

OFF = "--off" in sys.argv
PROBE_ONLY = "--probe" in sys.argv
VAL = "false" if OFF else "true"

step("拉起微信 + 清日志")
ensure_foreground()
logclear()

if not PROBE_ONLY:
    step("开关 chat → %s" % VAL)
    for k in ("antirevoke.comment", "antirevoke.comment_deleted_tag"):
        kvset(k, VAL)
    time.sleep(2)

step("评论表取证（SnsComment schema + 样本）")
bc("--es", "op", "snsg", "--es", "sub", "cmts", "--ei", "n", "3")
time.sleep(3)

step("评论探针（未实现时返回 unknown，属预期）")
bc("--es", "op", "snsc", "--es", "sub", "stat")
time.sleep(2)

step("进朋友圈（评论在动态详情页里）")
xml = dump()
tap_text("发现") or tap_xy(900, 3050)
time.sleep(4)
tap_text("朋友圈")
time.sleep(8)
print("  act:", top())

wait_manual("挑一条有评论的动态 → 点进详情 → 删一条评论（或 --probe 跳过）",
            0 if PROBE_ONLY else 20)

step("断言：评论是否还在 / 有没有 [已删除] 标签")
ui("已删除")

pull_dbg()
print("--- 日志 ---")
grep(r"SNSC|SNSG|CMT|comment", 25)
print("done")
