#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""R8-L3 归档库灌数器 —— 把「当前时间线上真实存在」的评论行灌进 sns_comment_archive.db

## 为什么要有它
解决 P0 `del=0`：设备上归档库只有 1 行历史 seed，其 `snsID` 不在**当前时间线**上，
⇒ `delMap[snsId]` 恒空 ⇒ `SNSC_FUNNEL del=0` ⇒ 精确路径那段代码**从来没被执行过**。
本脚本把屏幕上/DB 里真实读到的 `(snsID, createTime, talker, type)` 灌进去，
让 `del` 这一环 >0，才谈得上验后面的 `rows` / `got`。

## 用法
    python tools/_r8_seed.py --sns 1792413691 --key 1792413691:wxid_estojklxtfag22:2
    python tools/_r8_seed.py --json _cache/r8_seed.json        # 批量灌多行
    python tools/_r8_seed.py --dump                            # 只打印本地副本内容
    python tools/_r8_seed.py --sns X --key K --dry-run         # 静态自测，**不连设备**
    python tools/_r8_seed.py --sns X --key K --refresh         # 灌完顺手刷一次 delMap

## 已知坑（真机踩过，别改回去）
1. 设备**没有 sqlite3** ⇒ 只能 `adb pull` 到本地用 python sqlite3 改，再 `push` 回去。
   **绝不直接改设备上的库**（改坏了归档是唯一副本，没有第二份）。
2. snsId 是 **unsigned 64**，但 sqlite / Java 侧都按 signed long 存取：
   写库前一律 `u - 2**64`（u >= 2**63 时），否则 >2^63 的 id 直接 OverflowError，
   取出来也和 Java 的 getLong 对不上。拼 `am --el` 同理（不然整条广播不送达）。
3. push 之前先 `adb shell cp` 到 `.bak` 备份，并打印备份路径。
4. push 完必须 `chown` 回原属主（默认 `u0_a363`，⚠️ **待真机 `ls -l` 核一次**），
   否则微信打不开库，`archived=0` 会伪装成「没删过评论」。
5. 表主键 `(snsID, commentSvrID)`，用 `INSERT OR IGNORE`（对齐 `CONFLICT_IGNORE` 首条为准）。
"""
import argparse
import json
import os
import sqlite3
import subprocess
import sys
import time

# ---------------------------------------------------------------- 常量（来源见注释）
ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 设备归档库路径：SnsCommentGuard.kt:945 注释里写死，跟 wxgroup.db 同目录
DEV_DB = "/data/data/com.tencent.mm/databases/sns_comment_archive.db"
# ⚠️ 属主 uid：只在 docs/并行开发轮次计划.md:462 的轮次计划里出现过（u0_a363），
#    **没有真机 `ls -l` 实证**。首次上机前务必 `adb shell ls -l <DEV_DB>` 核一次，
#    不一致就用 --owner 覆盖。
OWNER = "u0_a363"

TABLE = "sns_comment_archive"
LOCAL_DB = os.path.join(ROOT, "_cache", "sns_comment_archive.db")

PKG = "com.tencent.mm"
ACTION = "com.lx.wxgroup.action.PROBE"

# 实测 16 列（SnsCommentLogic.kt:36-53，顺序即 pragma table_info 顺序）+ 我们自己的两列
COLS = [
    "snsID", "parentID", "isRead", "createTime", "talker", "type", "isSend",
    "curActionBuf", "refActionBuf", "commentSvrID", "clientId", "commentflag",
    "isSilence", "springWishFlag", "isReminding", "msgRelevanceType",
    "archived_at", "guard_act",
]
GUARD_ACT = "SEED:py"   # 便于将来按 guard_act 精准清理这批测试数据


# ---------------------------------------------------------------- adb 封装
def sh(*a, t=120):
    """一律用 subprocess 调 adb（Bash 里直接调会反复重启 daemon）。"""
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore", timeout=t)
    return ((r.stdout or "") + (r.stderr or "")).strip()


def bc(*extra):
    """发探针广播。⚠️ 整条 am 命令作为一个字符串传给 sh，值用单引号包。"""
    cmd = "am broadcast -a %s %s -p %s" % (
        ACTION, " ".join(extra), PKG,
    )
    return sh("shell", cmd)


def to_signed(u):
    """unsigned 64 → signed long。--el / sqlite 都只认 signed。"""
    u = int(u) & 0xFFFFFFFFFFFFFFFF
    return u - (1 << 64) if u >= (1 << 63) else u


# ---------------------------------------------------------------- 参数 → 行
def parse_key(key):
    """`createTime:talker:type` → (createTime, talker, type)

    ⚠️ 传的必须是**屏幕上真实读到的渲染键**（`probe("ids")` 抄一行 `key=`），
    见 SnsCommentTag.kt:1527。传任意值只能验证「路径有没有被执行」，打不上属预期。
    """
    p = key.split(":")
    if len(p) < 3:
        raise ValueError("key 形如 createTime:talker:type ，实际=%r" % key)
    return int(p[0]), ":".join(p[1:-1]), int(p[-1])


def mkrow(sns, create_time, talker, ctype, svr=1):
    """拼一行完整 18 列。commentSvrID 默认 1（对齐 probe 的 svr 默认值）。"""
    r = {c: None for c in COLS}
    r["snsID"] = to_signed(sns)
    r["createTime"] = int(create_time)
    r["talker"] = talker
    r["type"] = int(ctype)
    r["commentSvrID"] = to_signed(svr)
    r["clientId"] = ""
    r["archived_at"] = int(time.time() * 1000)
    r["guard_act"] = GUARD_ACT
    return r


def load_json(path):
    """批量文件：[{"sns":..,"key":..,"svr":..}] 或 [{全列名}]"""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    rows = []
    for it in data:
        if "key" in it:
            ct, tk, ty = parse_key(it["key"])
            rows.append(mkrow(it.get("sns", it.get("snsID", 0)), ct, tk, ty,
                              it.get("svr", it.get("commentSvrID", 1))))
        else:
            r = mkrow(it.get("snsID", it.get("sns")),
                      it.get("createTime"), it.get("talker"),
                      it.get("type", 2), it.get("commentSvrID", 1))
            for k, v in it.items():
                if k in COLS and v is not None:
                    r[k] = to_signed(v) if k in ("snsID", "commentSvrID") else v
            rows.append(r)
    return rows


# ---------------------------------------------------------------- 本地 sqlite 改写
def count(cur):
    cur.execute("select count(*) from %s" % TABLE)
    return cur.fetchone()[0]


def dump_rows(cur, sns=None, n=20):
    if sns is None:
        cur.execute("select snsID,createTime,talker,type,commentSvrID,guard_act "
                    "from %s order by rowid desc limit ?" % TABLE, (n,))
    else:
        cur.execute("select snsID,createTime,talker,type,commentSvrID,guard_act "
                    "from %s where snsID=? limit ?" % TABLE, (to_signed(sns), n))
    return cur.fetchall()


def inject(path, rows):
    """本地改库。返回 (before, after, 实际插入的行)。"""
    con = sqlite3.connect(path)
    cur = con.cursor()
    before = count(cur)
    sql = "insert or ignore into %s (%s) values (%s)" % (
        TABLE, ",".join(COLS), ",".join("?" * len(COLS)))
    got = []
    for r in rows:
        cur.execute(sql, [r[c] for c in COLS])
        if cur.rowcount > 0:
            got.append(r)
    con.commit()
    after = count(cur)
    con.close()
    return before, after, got


# ---------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(
        description="把真实评论行灌进 sns_comment_archive.db（解决 P0 del=0）")
    ap.add_argument("--sns", help="snsId（unsigned 64，脚本内部转 signed）")
    ap.add_argument("--key", help="createTime:talker:type，如 1792413691:wxid_xxx:2")
    ap.add_argument("--svr", default="1", help="commentSvrID，默认 1（主键的一半）")
    ap.add_argument("--json", help="批量灌：JSON 数组文件")
    ap.add_argument("--db", default=DEV_DB, help="设备库路径，默认 %s" % DEV_DB)
    ap.add_argument("--owner", default=OWNER,
                    help="chown 属主，默认 %s（⚠️ 待真机核实）" % OWNER)
    ap.add_argument("--local", default=LOCAL_DB, help="本地工作副本路径")
    ap.add_argument("--refresh", action="store_true", help="灌完发 sub=refresh 重载 delMap")
    ap.add_argument("--dump", action="store_true", help="只打印本地副本内容，不改不连设备")
    ap.add_argument("--dry-run", action="store_true", help="只打印计划，**不执行任何 adb**")
    a = ap.parse_args()

    # ---- --dump：纯本地，不碰设备
    if a.dump:
        if not os.path.exists(a.local):
            print("(本地副本不存在: %s)" % a.local)
            return 1
        con = sqlite3.connect(a.local)
        print("=== %s ===" % a.local)
        for r in dump_rows(con.cursor()):
            print("  snsID=%d ct=%s talker=%s type=%s svr=%s act=%s" % r)
        con.close()
        return 0

    # ---- 组装要灌的行
    rows = []
    if a.json:
        rows += load_json(a.json)
    if a.sns:
        if not a.key:
            print("❌ --sns 必须配 --key（渲染键 createTime:talker:type）")
            return 2
        ct, tk, ty = parse_key(a.key)
        rows.append(mkrow(a.sns, ct, tk, ty, a.svr))
    if not rows:
        ap.print_help()
        return 2

    print("=== 待灌入 %d 行 ===" % len(rows))
    for r in rows:
        print("  snsID=%d createTime=%s talker=%s type=%s svr=%s"
              % (r["snsID"], r["createTime"], r["talker"], r["type"], r["commentSvrID"]))

    if a.dry_run:
        print("\n[dry-run] 计划（未执行任何 adb）：")
        print("  1. adb pull  %s  ->  %s" % (a.db, a.local))
        print("  2. sqlite3 本地 insert or ignore %d 行 (guard_act=%s)" % (len(rows), GUARD_ACT))
        print("  3. adb shell cp %s %s.bak.<ts>" % (a.db, a.db))
        print("  4. adb push    %s  ->  %s" % (a.local, a.db))
        print("  5. adb shell chown %s:%s %s" % (a.owner, a.owner, a.db))
        if a.refresh:
            print("  6. 广播 op=snsc sub=refresh")
        print("\n⚠️ --owner=%s 系计划文档里抄的，**首次上机前用 adb shell ls -l 核一次**" % a.owner)
        return 0

    # ---- 1. 拉到本地副本
    os.makedirs(os.path.dirname(a.local), exist_ok=True)
    print("\n=== 1. pull ===")
    print(sh("pull", a.db, a.local))

    # ---- 2. 本地改
    print("=== 2. 本地注入 ===")
    before, after, got = inject(a.local, rows)
    print("  行数: %d -> %d (新增 %d)" % (before, after, len(got)))
    if not got:
        print("  ⚠️ 一行都没插进去 —— 主键 (snsID, commentSvrID) 撞了，"
              "换个 --svr 再试（CONFLICT_IGNORE 首条为准）")

    # ---- 3. 备份设备原库
    bak = "%s.bak.%s" % (a.db, time.strftime("%m%d_%H%M%S"))
    print("=== 3. 备份 ===")
    print("  cp ->", bak)
    print(" ", sh("shell", "cp %s %s && ls -l %s" % (a.db, bak, bak)))

    # ---- 4. push + chown
    print("=== 4. push + chown ===")
    print(" ", sh("push", a.local, a.db))
    print(" ", sh("shell", "chown %s:%s %s && chmod 660 %s && ls -l %s"
                  % (a.owner, a.owner, a.db, a.db, a.db)))

    # ---- 5. 可选：重载 delMap
    if a.refresh:
        print("=== 5. refresh ===")
        time.sleep(1)
        print(bc("--es", "op", "'snsc'", "--es", "sub", "'refresh'"))

    print("\n=== 核对 ===")
    print("  灌入 %d 行；下一步滚到该动态，看 `op=snsc sub=stat` 的 "
          "SNSC_FUNNEL del= 是否 >0" % len(got))
    return 0


if __name__ == "__main__":
    sys.exit(main())
