#!/usr/bin/env python3
"""mute 免打扰取证 v2 —— catch 模式 + 门禁自检"""

import subprocess, sys, time, os

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
PKG = "com.tencent.mm"
CACHE = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "_cache"))


def adb_run(args, timeout=90):
    env = dict(os.environ)
    env["MSYS_NO_PATHCONV"] = "1"
    return subprocess.run([ADB] + args, capture_output=True, text=True, timeout=timeout, env=env)


def bc(extras):
    args = ["shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE", "-p", PKG]
    for k, v in extras.items():
        args += ["--es", k, v]
    r = adb_run(args, timeout=30)
    return r.stdout.strip()


def pull():
    os.makedirs(CACHE, exist_ok=True)
    dst = os.path.join(CACHE, "dbg.txt")
    adb_run(["pull", "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt", dst], timeout=40)
    return dst


def show(path, pats, since=None, limit=80):
    n = 0
    with open(path, errors="replace") as f:
        for line in f:
            line = line.rstrip()
            if since and len(line) > 17 and line[11:17] < since:
                continue
            if any(p in line for p in pats):
                print("  " + line)
                n += 1
                if n >= limit:
                    break
    if n == 0:
        print("  (无匹配)")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""

    if cmd == "gate":
        # 门禁：装钩子后看 missing，确认 SQLiteStatement 类/方法挂上了
        print(">>> logclear")
        bc({"op": "logclear"})
        print(">>> catch 15s（无 key）")
        bc({"op": "sns", "sub": "catch", "sec": "15"})
        time.sleep(5)
        print(">>> 拉日志看 install 结果")
        p = pull()
        show(p, ["install done", "hook-fail", "catch ON", "stat"])

    elif cmd == "stmt":
        # 门禁2：短窗 catch 无 key，看有没有 [STMT] 行、SQL 是不是 (sql?)
        print(">>> logclear + catch 20s")
        bc({"op": "logclear"})
        bc({"op": "sns", "sub": "catch", "sec": "20"})
        print(">>> 请在手机上发一条消息 / 切一下开关，等窗口结束前回来")
        time.sleep(22)
        p = pull()
        show(p, ["[STMT]", "sql?"])
        print("--- stat ---")
        bc({"op": "sns", "sub": "stat"})
        time.sleep(3)
        p = pull()
        show(p, ["SNS stat"])

    elif cmd == "catch":
        sec = sys.argv[2] if len(sys.argv) > 2 else "600"
        key = sys.argv[3] if len(sys.argv) > 3 else None
        e = {"op": "sns", "sub": "catch", "sec": sec}
        if key:
            e["table"] = key
        bc({"op": "logclear"})
        print(bc(e))
        print(f"\n=== catch 已开 {sec}s，key={key or '(全部)'} ===")

    elif cmd == "read":
        since = sys.argv[2] if len(sys.argv) > 2 else None
        p = pull()
        print(f"=== 读日志 since={since} ===")
        show(p, ["SNS SNS"], since=since, limit=100)

    elif cmd == "off":
        print(bc({"op": "sns", "sub": "off"}))

    else:
        print(__doc__)


if __name__ == "__main__":
    main()