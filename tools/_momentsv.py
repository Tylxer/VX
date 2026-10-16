"""moments 复核修复项的真机补充验证（d71aa34 之后跑）

覆盖 _moments.py 没验的四件事：
  1. 清空导出目录后 export → written>0（写盘通路，前面被旧残留文件全 skip 了）
  2. 紧接着再 export → written=0 skipped>0（幂等，snId 命中即跳过）
  3. img 探针带 --ei n 999999 → 钳位生效（rows<=50，不崩不 OOM）
  4. snsg ls 带 --ei n 999999 → BLOCKING-1 修复生效（limit=50 回显，微信不崩）
  5. 滚几下时间线后再 load → 预期 grow / fling 日志（lastRv 捕获 + 自动加载链路）

用法: python tools/_momentsv.py
"""
import sys
import time

from _lib import *

EXPORT_DIR = "/storage/emulated/0/Android/data/com.tencent.mm/files/WechatXposed/moments"
BIG_N = 999999


def moments(sub, n=None):
    args = ["--es", "op", "moments", "--es", "sub", sub]
    if n is not None:
        args += ["--ei", "n", str(n)]
    bc(*args)


def snsg(sub, n=None):
    args = ["--es", "op", "snsg", "--es", "sub", sub]
    if n is not None:
        args += ["--ei", "n", str(n)]
    bc(*args)


step("拉起微信 + 清日志")
ensure_foreground()
logclear()

step("① 清空导出目录（删本次测试产物，只删 WechatXposed/moments 下的文件）")
out = sh("shell", "find", EXPORT_DIR, "-type", "f")
files = [l.strip() for l in out.splitlines() if l.strip()]
print("  待删 %d 个: %s" % (len(files), files[:8]))
for f in files:
    sh("shell", "rm", "-f", f)
n0 = len([l for l in sh("shell", "find", EXPORT_DIR, "-type", "f").splitlines() if l.strip()])
print("  清空后文件数 = %d（预期 0）" % n0)

step("② 强制导出一轮（预期 written>0）")
moments("export")
time.sleep(10)
n1 = len([l for l in sh("shell", "find", EXPORT_DIR, "-type", "f").splitlines() if l.strip()])
print("  导出后文件数 = %d（预期 >0）" % n1)

step("③ 再导一轮（幂等：预期 written=0 skipped>0）")
moments("export")
time.sleep(10)
n2 = len([l for l in sh("shell", "find", EXPORT_DIR, "-type", "f").splitlines() if l.strip()])
print("  二次导出后文件数 = %d（预期与上次相等 = %d）" % (n2, n1))

step("④ img 探针带 n=%d（钳位修复：预期 rows<=50 且不崩）" % BIG_N)
moments("img", BIG_N)
time.sleep(4)

step("⑤ snsg ls 带 n=%d（BLOCKING-1 修复：预期 limit=50 回显）" % BIG_N)
snsg("ls", BIG_N)
time.sleep(4)

step("⑥ 滚时间线 3 次 + load（自动加载链路）")
for i in range(3):
    # 1440x3200：从屏幕中下部往中上部划（朋友圈信息流是竖向 RecyclerView）
    sh("shell", "input", "swipe", "720", "2200", "720", "900", "300")
    time.sleep(2)
moments("load")
time.sleep(4)
moments("stat")
time.sleep(3)

pull_dbg()
print("--- 日志 ---")
grep(r"MOMENT|SNSLS", 40)
print("done")
