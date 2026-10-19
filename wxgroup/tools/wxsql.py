#!/usr/bin/env python3
"""微信库只读取证小工具：dump 指定表整行 / 任意只读 SQL

用法：
  python3 wxsql.py row rconversation 45396655401@chatroom
  python3 wxsql.py sql "select * from rconversation where username='45396655401@chatroom'"
  python3 wxsql.py prefs

⚠️ 全部走 op=label 的只读通道（isReadOnly 白名单把关），绝不写微信的库。
"""

import subprocess, sys, time, os

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
PKG = "com.tencent.mm"
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "_cache")
CACHE = os.path.normpath(CACHE)


def adb_run(args, timeout=60, conv=True):
    env = dict(os.environ)
    if conv:
        env["MSYS_NO_PATHCONV"] = "1"
    r = subprocess.run([ADB] + args, capture_output=True, text=True, timeout=timeout, env=env)
    return r


def broadcast(extras):
    args = ["shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE", "-p", PKG]
    for k, v in extras.items():
        args += ["--es", k, v]
    print("  >>> " + " ".join(args))
    r = adb_run(args, timeout=30)
    out = r.stdout.strip()
    if out:
        print(out)
    return out


def pull():
    os.makedirs(CACHE, exist_ok=True)
    dst = os.path.join(CACHE, "dbg.txt")
    r = adb_run(["pull", "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt", dst], timeout=40)
    print("  >>> pull ->", dst, "|", r.stdout.strip() or r.stderr.strip())
    return dst


def tail(path, prefix=None, since=None, limit=200):
    if not os.path.exists(path):
        print("!! 日志文件不存在:", path)
        return
    lines = []
    with open(path, errors="replace") as f:
        for line in f:
            line = line.rstrip()
            if prefix and prefix not in line:
                continue
            if since and len(line) > 17 and line[11:17] < since:
                continue
            lines.append(line)
    for line in lines[-limit:]:
        print(line)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]

    if cmd == "row":
        table = sys.argv[2]
        user = sys.argv[3]
        broadcast({"op": "label", "sub": "sql",
                   "sql": f'"select * from {table} where username=\'{user}\'"'})
    elif cmd == "rowby":
        # 通用：第一个参数当 where 字段
        table, col, val = sys.argv[2], sys.argv[3], sys.argv[4]
        broadcast({"op": "label", "sub": "sql",
                   "sql": f'"select * from {table} where {col}=\'{val}\'"'})
    elif cmd == "sql":
        broadcast({"op": "label", "sub": "sql", "sql": f'"{sys.argv[2]}"'})
    elif cmd == "prefs":
        user = sys.argv[2] if len(sys.argv) > 2 else None
        e = {"op": "label", "sub": "muteprefs"}
        if user:
            e["user"] = user
        broadcast(e)
    else:
        print("未知命令:", cmd)
        print(__doc__)
        return

    time.sleep(int(sys.argv[-1]) if sys.argv[-1].isdigit() else 6)
    path = pull()
    print("--- 日志尾部（LABEL/MUTE） ---")
    tail(path, prefix=None, limit=60)


if __name__ == "__main__":
    main()