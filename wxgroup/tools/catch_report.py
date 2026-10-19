#!/usr/bin/env python3
"""分析 catch 日志：按 (方法, 表) 聚合，找免打扰相关的写操作"""

import re
import sys
import os
import collections

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "_cache", "dbg.txt")
PATH = os.path.normpath(PATH)

MUTE_KW = ["mute", "silence", "silent", "disturb", "notify", "quiet", "dnd",
           "nosound", "noshow", "屏蔽", "免打扰", "静音", "fold", "settings"]

LINE = re.compile(
    r"^(?P<ts>\d\d:\d\d:\d\d\.\d+) SNS SNS (?P<cls>[\w.]+)\.(?P<m>\w+) "
    r"\[(?P<tag>[^\]]+)\] thread=(?P<th>[^ ]+) :: db=(?P<db>[^|]+)\| "
    r"(?P<table>[^|]*)\| (?P<sql>.*)$"
)


def main():
    if not os.path.exists(PATH):
        print("!! 日志不存在:", PATH)
        return
    rows = []
    with open(PATH, errors="replace") as f:
        for line in f:
            line = line.rstrip()
            if " SNS SNS " not in line:
                continue
            mm = LINE.match(line)
            if mm:
                rows.append(mm.groupdict())
            else:
                rows.append({"raw": line})

    ok = [r for r in rows if "raw" not in r]
    raw = [r for r in rows if "raw" in r]
    print(f"=== 解析 {len(ok)} 条写操作记录，{len(raw)} 条格式不符 ===")

    agg = collections.Counter()
    for r in ok:
        agg[(r["m"], r["table"].strip())] += 1
    print("\n=== (方法, 表) 聚合 ===")
    for (m, t), n in agg.most_common(60):
        print(f"  {n:4d}  {m:26s} {t}")

    print("\n=== 含免打扰关键词的记录 ===")
    hit = 0
    for r in ok:
        hay = (r["table"] + " " + r["sql"]).lower()
        if any(k in hay for k in MUTE_KW):
            hit += 1
            print(f"  {r['ts']} {r['m']} {r['table'].strip()} :: {r['sql'][:220]}")
    if hit == 0:
        print("  (无)")

    print("\n=== 遍历所有写操作的表名（去重） ===")
    tables = sorted({r["table"].strip() for r in ok if r["table"].strip()})
    print("  " + ", ".join(tables))

    print("\n=== 非 @chatroom 关键词的写（说明过滤生效范围） ===")
    nochat = [r for r in ok if "@chatroom" not in r["sql"] and "@chatroom" not in r["table"]]
    print(f"  共 {len(nochat)} 条不含 @chatroom")
    for r in nochat[:20]:
        print(f"  {r['ts']} {r['m']} {r['table'].strip()} :: {r['sql'][:160]}")

    if raw:
        print("\n=== 未解析样本（前 5） ===")
        for r in raw[:5]:
            print("  " + r["raw"][:200])


if __name__ == "__main__":
    main()
