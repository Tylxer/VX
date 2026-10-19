#!/usr/bin/env python3
"""微信库只读取证助手（稳健版）

为什么单独写：`--es sql` 里的 SQL 经 adb → 设备 sh 会再分词，带空格/逗号就断。
这里统一把 SQL 包成**带字面引号**的单个 argv，并对日志做**分段读取**
（探针单行截断在 ~600 字符，长结果必须切块取）。

用法：
  python3 dbq.py sql "select ..."
  python3 dbq.py cols chatroom          # 分段列出全部列名
  python3 dbq.py tables                  # 列出全部表名（分段）
  python3 dbq.py like <子串>             # 列出名字含子串的表/列

固定值：op=label（只读通道，isReadOnly 白名单把关），日志文件 wxgroup_dbg.txt
"""

import subprocess, sys, time, os, re

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
PKG = "com.tencent.mm"
CACHE = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "_cache"))
LOG = os.path.join(CACHE, "dbg.txt")


def adb(args, timeout=60):
    env = dict(os.environ)
    env["MSYS_NO_PATHCONV"] = "1"
    return subprocess.run([ADB] + args, capture_output=True, text=True, timeout=timeout, env=env)


def ensure_fg():
    """微信不在前台时广播不投递 —— 发命令前先拉起"""
    r = adb(["shell", "dumpsys", "activity", "activities"], timeout=40).stdout
    if "com.tencent.mm" not in r.split("topResumedActivity")[-1][:200]:
        adb(["shell", "am", "start", "-n", f"{PKG}/.ui.LauncherUI"], timeout=30)
        time.sleep(2)


def bc_sql(sql, wait=3):
    """发一条只读 SQL。SQL 会被包上字面双引号，避免设备端 sh 分词。"""
    ensure_fg()
    adb(["shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE",
         "--es", "op", "label", "--es", "sub", "sql",
         "--es", "sql", f'"{sql}"'], timeout=30)
    time.sleep(wait)


def pull():
    os.makedirs(CACHE, exist_ok=True)
    adb(["pull", "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt", LOG], timeout=40)
    if not os.path.exists(LOG):
        return []
    with open(LOG, errors="replace") as f:
        return [l.rstrip("\n") for l in f]


def rows_from(lines):
    """抽 LABEL 结果行（形如 `<ts> Thread-N LABEL   k=v | k=v`）"""
    out = []
    for l in lines:
        m = re.search(r" LABEL\s{2,}(.+)$", l)
        if m:
            out.append(m.group(1))
    return out


def cmd_sql(sql):
    bc_sql(sql, wait=3)
    lines = pull()
    rs = rows_from(lines)
    if not rs:
        print("(无结果 —— 可能被 isReadOnly 拒绝或 SQL 报错；看下面原始行)")
        for l in lines[-8:]:
            print("  " + l[:220])
    for r in rs[-40:]:
        print(r)


def cmd_cols(table):
    """分段列出列名。

    ⚠️ 探针单行输出会截断长结果（实测 group_concat 到第 29 列就被切）。
    所以每段只取 5 列，并且**先取长度**再取内容 —— 长度对不上就说明被截了。
    """
    cols = []
    off = 0
    while off < 80:
        q = (f"select group_concat(name,'|') from "
             f"(select name from pragma_table_info('{table}') limit 5 offset {off})")
        bc_sql(q, wait=2)
        rs = rows_from(pull())
        got = None
        for r in reversed(rs):
            if "=" in r:
                got = r.split("=", 1)[1].strip()
                break
        if not got or got == "-":
            break
        cols += [c for c in got.split("|") if c]
        off += 5
    print(f"=== {table} 共 {len(cols)} 列 ===")
    for i, c in enumerate(cols):
        print(f"  {i:3d}  {c}")


def cmd_colcount(table):
    """只取列数 —— 用来判断分段是否取全"""
    bc_sql(f"select count(*) from pragma_table_info('{table}')", wait=2)
    for r in rows_from(pull())[-2:]:
        print(r)


def cmd_tables():
    names = []
    off = 0
    while off < 400:
        q = (f"select group_concat(name,'|') from "
             f"(select name from sqlite_master where type='table' limit 8 offset {off})")
        bc_sql(q, wait=2)
        rs = rows_from(pull())
        got = None
        for r in reversed(rs):
            if "=" in r:
                got = r.split("=", 1)[1]
                break
        if not got or got == "-":
            break
        names += got.split("|")
        off += 8
    print(f"=== 共 {len(names)} 张表 ===")
    for n in names:
        print("  " + n)


def cmd_like(sub):
    q = (f"select group_concat(name,'|') from (select m.name n from sqlite_master m "
         f"where m.type='table' and lower(m.name) like '%{sub}%' limit 20)")
    bc_sql(q, wait=3)
    print("--- 表名命中 ---")
    for r in rows_from(pull())[-3:]:
        print(r[:400])
    q2 = (f"select group_concat(m.name||'.'||p.name,'|') from sqlite_master m "
          f"join pragma_table_info(m.name) p where m.type='table' "
          f"and lower(p.name) like '%{sub}%' limit 25")
    bc_sql(q2, wait=3)
    print("--- 列名命中 ---")
    for r in rows_from(pull())[-3:]:
        print(r[:500])


def main():
    c = sys.argv[1] if len(sys.argv) > 1 else ""
    if c == "colcount":
        cmd_colcount(sys.argv[2])
    elif c == "sql":
        cmd_sql(sys.argv[2])
    elif c == "cols":
        cmd_cols(sys.argv[2])
    elif c == "tables":
        cmd_tables()
    elif c == "like":
        cmd_like(sys.argv[2].lower())
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
