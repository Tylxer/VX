"""临时：真机交互小工具（走 subprocess 调 adb，避开 Git Bash 的路径转换）。

用法：
  python tools/_ui.py back
  python tools/_ui.py tap 720 1200
  python tools/_ui.py swipe 720 2400 720 800 [300]
  python tools/_ui.py shot name     # 存到 %TEMP%/name.png，打印路径
  python tools/_ui.py key HOME
"""
import os
import subprocess
import sys

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")


def run(*args):
    return subprocess.run([ADB] + list(args), capture_output=True,
                          encoding="utf-8", errors="ignore")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    if cmd == "back":
        run("shell", "input", "keyevent", "4")
        print("back")
    elif cmd == "key":
        run("shell", "input", "keyevent", sys.argv[2])
        print("key", sys.argv[2])
    elif cmd == "tap":
        run("shell", "input", "tap", sys.argv[2], sys.argv[3])
        print("tap", sys.argv[2], sys.argv[3])
    elif cmd == "swipe":
        dur = sys.argv[6] if len(sys.argv) > 6 else "300"
        run("shell", "input", "swipe", sys.argv[2], sys.argv[3],
            sys.argv[4], sys.argv[5], dur)
        print("swipe", sys.argv[2:6], dur)
    elif cmd == "shot":
        name = sys.argv[2] if len(sys.argv) > 2 else "shot"
        path = os.path.join(os.environ.get("TEMP", "/tmp"), name + ".png")
        r = run("exec-out", "screencap", "-p")
        # run() 用 text 模式会破坏二进制，重来一遍用 bytes
        r = subprocess.run([ADB, "exec-out", "screencap", "-p"],
                           capture_output=True)
        with open(path, "wb") as f:
            f.write(r.stdout)
        print(path, len(r.stdout), "bytes")
    else:
        print("未知命令", cmd)


if __name__ == "__main__":
    main()
