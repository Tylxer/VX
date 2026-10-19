#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R4 拍一拍拦截**真机验收**：装 APK → 开 chat.disable_pat → 等人双击头像 → 看 blocked 计数。

⚠️ adb install 后主进程常不重载模块 → 脚本会 force-stop + 拉起，且**拉两次**
   （判据：日志里出现 `QUIET PAT installed`，没出现就说明跑的还是旧代码）。

用法：
    python _r4_patverify.py            # 装 + 开开关 + 等 90s 让人双击
    python _r4_patverify.py --off      # 反向对照：关开关，双击应该照常触发
    python _r4_patverify.py --nowait   # 只装 + 打印 status/cls，不等
"""
import os
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
APK = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\wxgroup\app\build\outputs\apk\debug\app-debug.apk"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOC = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_cache\patverify.txt"

OFF = "--off" in sys.argv
NOW = "--nowait" in sys.argv
VAL = "false" if OFF else "true"
WAIT = 90


def sh(*a, t=300):
    return subprocess.run([ADB, *a], capture_output=True, text=True,
                          encoding="utf-8", errors="ignore", timeout=t).stdout


def bc(*extra):
    sh("shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE",
       *extra, "-p", "com.tencent.mm")


def main():
    step("装 APK")
    print("   ", sh("install", "-r", APK).strip()[:200])

    step("时间戳交叉判据（装上去的到底是不是新包）")
    out = sh("shell", "pm", "path", "com.lx.wxgroup").strip()
    print("   ", out[:160])
    if "package:" in out:
        p = out.split("package:")[1].strip()
        print("   ", sh("shell", "ls -la " + p).strip()[:160])
    print("   本地 APK 时间: %.0f" % os.path.getmtime(APK))

    step("重启微信（装完第一次大概率还是旧代码，拉两次）")
    sh("shell", "am", "force-stop", "com.tencent.mm")
    time.sleep(2)
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(6)
    sh("shell", "am", "force-stop", "com.tencent.mm")
    time.sleep(2)
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(6)

    step("清日志 + 设 chat.disable_pat=%s" % VAL)
    bc("--es", "op", "logclear")
    time.sleep(1)
    bc("--es", "op", "kvset", "--es", "key", "chat.disable_pat", "--es", "val", VAL)
    time.sleep(2)

    step("锚点自检：运行时能不能找到 zx2.j#d")
    bc("--es", "op", "quiet", "--es", "sub", "pat", "--es", "sub2", "cls")
    time.sleep(2)
    bc("--es", "op", "quiet", "--es", "sub", "pat", "--es", "sub2", "status")
    time.sleep(2)
    sh("pull", DBG, LOC)
    txt = open(LOC, encoding="utf-8", errors="ignore").read()
    for l in txt.splitlines():
        if "QUIET" in l or "PATDBG" in l or "PAT" in l:
            print("   ", l[:200])

    if NOW:
        return

    print("\n" + "=" * 60)
    print(("【反向对照：开关已关】" if OFF else "【实验组：开关已开】") +
          "请回到 verity 会话，双击 TA 的头像")
    print(("  期望：照常出现「你拍了拍 verity」" if OFF
           else "  期望：**不再出现**「你拍了拍 verity」（拦截生效）"))
    print(f"  脚本 {WAIT} 秒后自动收日志")
    print("=" * 60 + "\n")
    time.sleep(WAIT)

    bc("--es", "op", "quiet", "--es", "sub", "pat", "--es", "sub2", "status")
    time.sleep(2)
    sh("pull", DBG, LOC)
    txt = open(LOC, encoding="utf-8", errors="ignore").read()
    print("=== 收尾日志（QUIET / PATDBG）===")
    for l in txt.splitlines():
        if "QUIET" in l or "PATDBG" in l:
            print("   ", l[:200])


def step(m):
    print("\n[step] " + m)


if __name__ == "__main__":
    main()
