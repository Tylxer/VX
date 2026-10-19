"""R2 第 3 步c：装新包 + 验 imgurl 探针（url 完整度）

用法: python tools/_r2_imgurl.py

背景：md5 找本地缓存这条路已被实测证伪（图没下载到本机）。
      唯一可行方向是 content 里的完整 CDN url，但旧探针里 url 被 take(48) 截断。
      本轮加了 sub=imgurl 打完整地址，这里验证它。
"""
import time

from _lib import *

APK = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\wxgroup\app\build\outputs\apk\debug\app-debug.apk"


def main():
    step("1. 装新包")
    print(sh("install", "-r", APK))

    step("2. 两次冷启动（本机已知：一次大概率还是旧代码）")
    for i in range(2):
        sh("shell", "am force-stop com.tencent.mm")
        time.sleep(2)
        sh("shell", "am start -n com.tencent.mm/.ui.LauncherUI")
        time.sleep(11)

    step("3. 确认新 subs 文案出现（旧代码会回 subs=stat|path|export|img|imgtry|scan）")
    ensure_foreground()
    bc("--es", "op", "moments", "--es", "sub", "xxx")
    time.sleep(3)

    step("4. imgurl 探针：取 3 条，打完整 url")
    bc("--es", "op", "moments", "--es", "sub", "imgurl", "--ei", "n", "3")
    time.sleep(4)

    step("5. 拉日志")
    pull_dbg()
    for kw in ("imgurl", "subs=", "SAFR", "GROUPNAME"):
        print("\n" + "=" * 70)
        print("### 关键词:", kw)
        grep(kw, n=20)


if __name__ == "__main__":
    main()
