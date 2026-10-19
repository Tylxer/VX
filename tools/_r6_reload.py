#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
重载微信模块（新包常不重载 → force-stop + am start 做两次）并验证装的是新包。

⚠️ 判据不是 `adb install` 回 Success，也不是 dev.sh 打印 [OK] —— 那两样都有过
   静默装旧包的记录。唯一可靠判据是：设备上 APK 的 mtime >= 本地 APK 编译时间。

用法:
    python tools/_r6_reload.py <本地APK路径>
"""
import os
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
PKG = "com.tencent.mm"
MOD = "com.lx.wxgroup"


def sh(*a, timeout=180):
    return subprocess.run(
        [ADB, *a], capture_output=True, text=True,
        encoding="utf-8", errors="ignore", timeout=timeout,
    ).stdout.strip()


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else \
        r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\wxgroup\app\build\outputs\apk\debug\app-debug.apk"
    local_mtime = os.path.getmtime(apk)
    print(f"本地 APK: {apk}")
    print(f"本地 mtime: {time.strftime('%H:%M:%S', time.localtime(local_mtime))}")

    path = sh("shell", f"pm path {MOD}").replace("package:", "").strip()
    print(f"设备 path: {path}")
    if not path:
        print("❌ 模块未安装")
        return

    # 设备 mtime（秒级）
    out = sh("shell", f"stat -c %Y {path}").strip()
    try:
        dev_mtime = float(out)
    except ValueError:
        print(f"⚠️ stat 输出无法解析: {out!r}，退回 ls")
        dev_mtime = 0.0

    fmt = "%m-%d %H:%M:%S"
    print(f"设备 mtime: {time.strftime(fmt, time.localtime(dev_mtime)) if dev_mtime else '(未知)'}")
    ok = dev_mtime >= local_mtime - 2  # 允许 2 秒文件系统粒度
    print(f"{'✅ 装的是新包' if ok else '❌ 装的可能是旧包（设备 mtime 早于本地）'}")
    if not ok:
        print("   → 重新 adb install -r 后再跑本脚本")

    for i in (1, 2):
        print(f"--- 第 {i} 次 force-stop + start ---")
        sh("shell", f"am force-stop {PKG}")
        time.sleep(1.5)
        sh("shell", f"am start -n {PKG}/.ui.LauncherUI")
        time.sleep(4)

    print("\n--- 等模块日志出现 installed ---")
    DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
    time.sleep(3)
    txt = sh("shell", f"tail -40 {DBG}")
    for line in txt.splitlines():
        if "install" in line.lower() or "SNSC" in line:
            print(line)


if __name__ == "__main__":
    main()
