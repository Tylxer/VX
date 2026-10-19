"""R2 第 3 步：装新 APK + 重启微信 + 确认新代码生效

用法: python tools/_r2_install.py

⚠️ 本机教训：不依赖 dev.sh install（它的 apk_path 是过时的，会静默装不上）。
   直接 adb install -r，然后用「时间戳对比」+「新探针文案」双判据确认。

⚠️ adb install -r 后主进程常不重载模块：force-stop + am start 要**来两次**才加载新代码。
   判据：日志里有没有新的 installed 行。
"""
import sys
import time

from _lib import *

APK = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\wxgroup\app\build\outputs\apk\debug\app-debug.apk"


def install():
    step("1. adb install -r")
    out = sh("install", "-r", APK)
    print(out)
    if "Success" not in out:
        print("!! install 失败，终止")
        sys.exit(1)


def verify_installed_time():
    step("2. 校验设备上的 APK 时间戳（唯一可靠的判据）")
    path = sh("shell", "pm path com.lx.wxgroup").strip()
    print("pm path:", path)
    p = path.split("package:", 1)[-1].strip()
    print(sh("shell", f"ls -la '{p}'"))


def cold_restart(times=2):
    for i in range(times):
        step(f"3.{i+1} force-stop + am start（第 {i+1} 次）")
        sh("shell", "am force-stop com.tencent.mm")
        time.sleep(2)
        sh("shell", "am start -n com.tencent.mm/.ui.LauncherUI")
        time.sleep(12)


def main():
    install()
    verify_installed_time()
    cold_restart(2)

    step("4. 等模块 bootstrap 落日志")
    ensure_foreground()
    time.sleep(6)

    # ⚠️ 顺序不能反：installed 行是启动时写的，先 logclear 会把判据洗掉
    step("5. 先拉日志确认新代码生效（找本轮新增的 installed 行）")
    pull_dbg()
    for kw in ("SAFR", "GROUPNAME", "LabelSource", "Xposed", "WXGroup"):
        print("\n" + "=" * 70)
        print("### 关键词:", kw)
        grep(kw, n=25)


if __name__ == "__main__":
    main()
