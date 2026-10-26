"""M7 · 把朋友圈导出根目录从「微信私有 Android/data」改到「公共 Documents/WXGroup/moments」。

背景：用户在文件管理器里根本进不去 `Android/data/com.tencent.mm/...`（Android 11+ 限制），
M7 判 FAIL。本脚本**不改代码** —— `MomentsExport.cfg()` 每轮都重读 `moments.export_path`，
所以只要 kvset 就能立刻换目录，能证明「公共目录可写 + 用户可见」再谈改默认值。

判据（对着用户能看见的事实）：
  1. `sub=path` 的 root 变成新目录且 writable=true
  2. `sub=export` 后 `MOMENT export done` 的 written>0（不是 skipped=全部）
  3. `sub=scan` 的 exported>0
  4. 用户在文件管理器「内部存储/Documents/WXGroup/moments」能看到文件
"""
import time

import _lib as L

PUB = "/storage/emulated/0/Documents/WXGroup/moments"
OLD = "/storage/emulated/0/Android/data/com.tencent.mm/files/WechatXposed/moments"

L.step("清日志（LogX 是环形覆盖的 5000 行）")
L.logclear()

L.step("拉起微信到前台（后台广播不投递）")
L.ensure_foreground()
print("  top =", L.top())

L.step("先记下当前值")
L.bc("--es", "op", "kvget", "--es", "key", "moments.export_path")
time.sleep(1.5)

L.step("kvset moments.export_path = %s" % PUB)
# ⛔ adb shell 会二次解析参数：值必须再包一层单引号
L.kvset("moments.export_path", "'" + PUB + "'")
time.sleep(1.5)

L.step("回读复核（kvset 回显不算数）")
L.kvget("moments.export_path")
time.sleep(1.5)

L.step("path 探针：看 root 换没换、可不可写")
L.bc("--es", "op", "moments", "--es", "sub", "path")
time.sleep(2)

L.step("触发一轮导出")
L.bc("--es", "op", "moments", "--es", "sub", "export")
time.sleep(10)

L.step("scan：新目录里到底落了几个")
L.bc("--es", "op", "moments", "--es", "sub", "scan")
time.sleep(2)

L.step("path 再探一次（确认目录真被建出来）")
L.bc("--es", "op", "moments", "--es", "sub", "path")
time.sleep(2)

L.pull_dbg()
print("\n---- 日志 ----")
L.grep(r"MOMENT|kvset|kvget|moments\.export_path", 40)
print("\n若 writable=false / mkdirs 失败：把值改回 %s 即可恢复" % OLD)
