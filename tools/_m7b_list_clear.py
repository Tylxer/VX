"""M7-b · 清空朋友圈导出名单，验证「公共目录里真能落出文件」。

背景：M7 本体（N6 备份）已 PASS —— 备份走 `Documents/WXGroup`。
本脚本只处理**朋友圈导出**那一半：目录已 kvset 到公共区且可写，
但上一轮 `written=0 / listMiss=7`，根因是名单只剩一个人（落浔张桂凤），
而归档 7 条属于 V（6 条）+ 一个 rcontact 查不到的号（1 条）。

判据（对着用户能看见的事实）：
  1. `kvget moments.export_list` 回读 = `[]`（空名单 = 不限制）
  2. `MOMENT export done` 的 written>0 且 listMiss=0
  3. `sub=scan` 的 exported>0
  4. 用户在文件管理器「内部存储/Documents/WXGroup/moments」能看到文件
"""
import time

import _lib as L

PUB = "/storage/emulated/0/Documents/WXGroup/moments"

L.step("清日志")
L.logclear()

L.step("拉起微信到前台（后台广播不投递）")
L.ensure_foreground()
print("  top =", L.top())

L.step("记录名单原值（万一要回滚）")
L.bc("--es", "op", "kvget", "--es", "key", "moments.export_list")
time.sleep(1.5)

L.step("kvset moments.export_list = [] （空 = 不限制，全部导出）")
# ⛔ adb shell 二次解析：方括号/引号必须再包一层单引号
L.kvset("moments.export_list", "'[]'")
time.sleep(1.5)

L.step("回读复核")
L.kvget("moments.export_list")
time.sleep(1.5)

L.step("确认导出目录仍是公共区")
L.bc("--es", "op", "moments", "--es", "sub", "path")
time.sleep(2)

L.step("触发导出")
L.bc("--es", "op", "moments", "--es", "sub", "export")
time.sleep(12)

L.step("scan：新目录里落了几个")
L.bc("--es", "op", "moments", "--es", "sub", "scan")
time.sleep(2)

L.pull_dbg()
print("\n---- 日志 ----")
L.grep(r"MOMENT|moments\.export_list", 30)
print("\n回滚名单：kvset moments.export_list = '[\"wxid_xij8e08lqr3e22\"]'")
