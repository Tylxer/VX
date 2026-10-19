# -*- coding: utf-8 -*-
"""
R3：验证朋友圈原图「url 下载」路线（op=moments sub=imgdl）。

背景：md5 找本地缓存那条路已被设备实测证伪（4 个 md5 全盘 find 搜不到），
      content 里的完整 CDN url 是本轮唯一可行方向（新鲜 url 已 curl 验过 200）。

流程：装包 → 双冷启动（确保模块重载）→ 清日志 → 拉起微信 →
      先 imgurl（只读，看归档里有没有真实媒体 url）→ 再 imgdl（真下载）→
      拉日志 + 列出落盘文件。
"""
import subprocess, time, sys

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
APK = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\wxgroup\app\build\outputs\apk\debug\app-debug.apk"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOC = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_cache\dbg.txt"


def sh(*a, timeout=300):
    r = subprocess.run([ADB, *a], capture_output=True, text=True, timeout=timeout)
    return r.stdout.strip()


def bc(action, **kv):
    args = ["shell", "am", "broadcast", "-a", action]
    for k, v in kv.items():
        # n 是 int，必须用 --ei（用 --es 传数字探针解析不到）
        args += ["--ei" if k == "n" else "--es", k, str(v)]
    args += ["-p", "com.tencent.mm"]
    return sh(*args)


def loggrep(kw, n=25, width=190):
    sh("pull", DBG, LOC)
    txt = open(LOC, encoding="utf-8", errors="ignore").read()
    hit = [l for l in txt.splitlines() if kw in l]
    for l in hit[-n:]:
        print("   ", l[:width])
    return len(hit)


def step(s):
    print("\n=== %s ===" % s)


def main():
    only_probe = "--nobuild" in sys.argv

    if not only_probe:
        step("1. 安装 APK")
        print("   ", sh("install", "-r", APK))

        step("2. 双次冷启动（LSPosed 第一次常是旧代码）")
        for i in range(2):
            sh("shell", "am", "force-stop", "com.tencent.mm")
            time.sleep(2)
            sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
            time.sleep(11)
        sh("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        time.sleep(1)
        sh("shell", "input", "keyevent", "KEYCODE_MENU")
        time.sleep(1)
        sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
        time.sleep(4)
    else:
        sh("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        time.sleep(1)
        sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
        time.sleep(3)

    step("3. 确认前台 + 清日志")
    print("   focus:", sh("shell", "dumpsys window | grep mCurrentFocus"))
    bc("com.lx.wxgroup.action.PROBE", op="logclear")
    time.sleep(2)

    step("4. 模块加载判据")
    bc("com.lx.wxgroup.action.PROBE", op="moments", sub="stat")
    time.sleep(3)
    loggrep("MOMENT", 12)

    step("5. imgurl（只读：看归档里有没有真实媒体 url）")
    bc("com.lx.wxgroup.action.PROBE", op="moments", sub="imgurl", n="3")
    time.sleep(4)
    n1 = loggrep("imgurl", 10)
    print("   命中行数:", n1)

    step("6. imgdl（真下载，联网）")
    bc("com.lx.wxgroup.action.PROBE", op="moments", sub="imgdl", n="3")
    time.sleep(25)
    loggrep("MOMENT imgdl", 20)

    step("7. 落盘文件")
    print(sh("shell", "su -c 'ls -la /sdcard/WxGroupExport/_imgdl/ 2>/dev/null || ls -la /sdcard/WxGroup/*/_imgdl/ 2>/dev/null || echo NO_DIR'"))

    step("8. 全盘找 _imgdl")
    print(sh("shell", "su -c 'find /sdcard -type d -name _imgdl 2>/dev/null | head -5'"))


if __name__ == "__main__":
    main()
