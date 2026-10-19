#!/usr/bin/env python3
"""mute 免打扰取证：mute1 -> 用户手动开关 -> mute2"""

import subprocess, sys, time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
PKG = "com.tencent.mm"

def adb(*args):
    cmd = [ADB] + list(args)
    print("  >>>", " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    if r.stdout.strip():
        print(r.stdout.strip())
    if r.stderr.strip() and "daemon" not in r.stderr.lower():
        print("  [stderr]", r.stderr.strip(), file=sys.stderr)
    return r.stdout

def broadcast(extras):
    args = [ADB, "shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE", "-p", PKG]
    for k, v in extras.items():
        args += ["--es", k, v]
    print("  >>> " + " ".join(args[1:]))
    r = subprocess.run(args, capture_output=True, text=True, timeout=30)
    out = r.stdout.strip()
    if out:
        print(out)
    return out

def pull_log():
    r = subprocess.run(
        [ADB, "pull", "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt", "/tmp/wxgroup_mute.txt"],
        capture_output=True, text=True, timeout=30
    )
    print("  >>> pull:", r.stdout.strip())

def grep_log(pat):
    with open("/tmp/wxgroup_mute.txt", errors="replace") as f:
        for line in f:
            if pat in line:
                print(line.rstrip())

def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    user = sys.argv[2] if len(sys.argv) > 2 else "45396655401@chatroom"

    if cmd == "tables":
        broadcast({"op": "label", "sub": "mutetables"})
        time.sleep(12)
        pull_log()
        grep_log("MUTE")

    elif cmd == "mute1":
        broadcast({"op": "label", "sub": "mute1", "user": user})
        time.sleep(12)
        pull_log()
        grep_log("MUTE")
        print("\n=== 下一步：去微信里手动打开这个群的「消息免打扰」，然后跑 mute2 ===")

    elif cmd == "mute2":
        broadcast({"op": "label", "sub": "mute2", "user": user})
        time.sleep(12)
        pull_log()
        grep_log("MUTE")

    elif cmd == "diff":
        broadcast({"op": "label", "sub": "mutediff"})
        time.sleep(3)
        pull_log()
        grep_log("MUTE")

    elif cmd == "clear":
        broadcast({"op": "logclear"})
        print("日志已清空")

    else:
        print("用法: python3 mute_hunt.py tables|mute1|mute2|diff|clear [群wxid]")

if __name__ == "__main__":
    main()