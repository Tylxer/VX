"""场景：朋友圈定时刷新 / 自动加载 / 自动导出（moments.*）—— 需求 6.4

用法: python tools/_moments.py           开三项 → 触发一次刷新 → 查导出目录
      python tools/_moments.py --off      反向对照
      python tools/_moments.py --ls       只查导出目录

✅ 这一组**可全自动**（不涉及发消息/花钱），功能上线后价值最高：
   导出目录的文件数就是硬判据，不用看截图。

⏳ 功能未实现：moments.* 键已在 Keys.kt，无功能代码。
   默认路径 `/storage/emulated/0/Android/data/com.tencent.mm/files/WechatXposed/moments`
   （v6.3 预留：若用户取不到 Android/data 下的文件，再追加 SAF 授权入口，**未实测前不改默认路径**）。
"""
import sys
import time

from _lib import *

OFF = "--off" in sys.argv
LS = "--ls" in sys.argv
VAL = "false" if OFF else "true"
EXPORT_DIR = "/storage/emulated/0/Android/data/com.tencent.mm/files/WechatXposed/moments"


def count_exported():
    out = sh("shell", "find", EXPORT_DIR, "-type", "f")
    n = len([l for l in out.splitlines() if l.strip()])
    print("  导出文件数 = %d" % n)
    return n


if LS:
    count_exported()
    raise SystemExit(0)

step("拉起微信 + 清日志")
ensure_foreground()
logclear()

step("开关 → %s" % VAL)
kvset("moments.auto_refresh", VAL)
kvset("moments.auto_load", VAL)
kvset("moments.auto_export", VAL)
kvset("moments.refresh_interval_h", "3")
time.sleep(2)
kvget()
time.sleep(1)

print("  导出前基线:")
n0 = count_exported()

step("进朋友圈（触发自动加载 / 刷新）")
xml = dump()
tap_text("发现") or tap_xy(900, 3050)
time.sleep(4)
tap_text("朋友圈")
time.sleep(12)
print("  act:", top())

step("导出后：")
n1 = count_exported()
print("  新增 = %d" % (n1 - n0))

pull_dbg()
print("--- 日志 ---")
grep(r"moments|MOMENT|export|EXPORT", 25)
print("done")
