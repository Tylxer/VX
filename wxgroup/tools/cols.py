#!/usr/bin/env python3
"""列出表的完整列名（分段取，绕开日志行截断）"""

import subprocess, sys, time, os, re

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
PKG = "com.tencent.mm"
CACHE = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "_cache"))


def adb_run(args, timeout=60):
    env = dict(os.environ); env["MSYS_NO_PATHCONV"] = "1"
    return subprocess.run([ADB] + args, capture_output=True, text=True, timeout=timeout, env=env)


def bc(extras):
    a = ["shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE", "-p", PKG]
    for k, v in extras.items():
        a += ["--es", k, v]
    return adb_run(a, timeout=30).stdout.strip()


def pull():
    os.makedirs(CACHE, exist_ok=True)
    dst = os.path.join(CACHE, "dbg.txt")
    adb_run(["pull", "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt", dst], timeout=40)
    return dst


def main():
    table = sys.argv[1] if len(sys.argv) > 1 else "rconversation"

    # 分段列表名（每段 8 个，避免行被截断）
    cols = []
    for off in range(0, 64, 8):
        sql = f"select name from pragma_table_info('{table}') limit 8 offset {off}"
        bc({"op": "label", "sub": "sql", "sql": f'"{sql}"'})
        time.sleep(2)
        p = pull()
        cur = []
        with open(p, errors="replace") as f:
            for line in f:
                if "LABEL   name=" in line:
                    cur.append(line.split("name=", 1)[1].strip())
        if not cur:
            break
        cols += cur
    # 去重保序
    seen = set(); out = []
    for c in cols:
        if c not in seen:
            seen.add(c); out.append(c)
    print(f"=== {table} 共 {len(out)} 列 ===")
    for i, c in enumerate(out):
        print(f"  {i:3d}  {c}")


if __name__ == "__main__":
    main()
