"""moments 补充真机验证（rv 自愈捕获 + 钳位修复回归）

覆盖 _moments.py 没验的事：
  1. 进朋友圈（fresh timeline，create 钩子的正确考场）→ stat 的 rv= 字段
  2. img / snsg ls 带 n=999999 → 钳位生效（rows/limit<=50，不崩）
  3. 滚时间线 + load → 预期 bind fallback / fling / grow 日志
  4. 写盘通路：默认 root 在 Android/data 下 shell 数不了文件（Permission denied），
     判据用 export done 的 skipped=N（scanExportedIds 命中的既有文件数）

用法: python tools/_momentsv.py
"""
import time

from _lib import *

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

step("进朋友圈（fresh timeline：让 create 钩子有舞台）")
dump()
tap_text("发现") or tap_xy(900, 3050)
time.sleep(4)
tap_text("朋友圈")
time.sleep(12)
print("  act:", top())

step("stat（看 rv= 字段是否捕获）")
moments("stat")
time.sleep(3)

step("① img 探针 n=%d（钳位：预期 rows<=50）" % BIG_N)
moments("img", BIG_N)
time.sleep(4)

step("② snsg ls n=%d（钳位：预期 limit=50）" % BIG_N)
snsg("ls", BIG_N)
time.sleep(4)

step("③ 滚时间线 3 次 + load（rv 捕获：预期 fallback/smoothScrollBy/grow）")
for i in range(3):
    sh("shell", "input", "swipe", "720", "2200", "720", "900", "300")
    time.sleep(2)
moments("load")
time.sleep(4)
moments("stat")
time.sleep(3)

step("④ 强制导出一轮（写盘通路：skipped=N 即 scanExportedIds 命中的既有文件数）")
moments("export")
time.sleep(10)

pull_dbg()
print("--- 日志 ---")
grep(r"MOMENT|SNSLS", 40)
print("done")
