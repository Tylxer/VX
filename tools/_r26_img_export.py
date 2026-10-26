"""R26 · 朋友圈图片导出 —— 真机判据脚本

判据（对着用户能看见的文件写，不用内部计数器当判据）：
  P1  导出目录里出现 `img_<snsId>_<slot>.jpg` 实体文件（find 得到，size>0）
  P2  文件名里的 snsId 能与同目录的 `<snsId>.txt` 配对
  P3  文件头是 JPEG（ffd8ff），且 size 落在缓存源量级（不是 0 字节壳）
  P4  反向对照：断掉 sns 缓存源后重跑，不应再有新 img 出现

用法：
  python tools/_r26_img_export.py base     # 记基线清单
  python tools/_r26_img_export.py fill     # 触发回填 + 对比
  python tools/_r26_img_export.py check    # 只列现状
"""
import os
import re
import subprocess
import sys
import time

import _lib as L

ADB = L.ADB
ROOT = "/sdcard/Documents/WXGroup/moments"
BASE_FILE = os.path.join(L.ROOT, "_r26_img_before.txt")


def sh_raw(*args):
    r = subprocess.run([ADB] + list(args), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def list_imgs():
    """全树列出 img_*.jpg，输出 `size<TAB>path`（不依赖中文路径，用 -name 过滤）"""
    out = sh_raw("shell", "find", ROOT, "-type", "f", "-name", "img_*.jpg")
    rows = []
    for ln in out.splitlines():
        p = ln.strip()
        if not p.startswith("/"):
            continue
        st = sh_raw("shell", "stat", "-c", "%s", "'" + p + "'").strip()
        size = int(st) if st.isdigit() else -1
        rows.append((size, p))
    return sorted(rows)


def magic_of(path):
    """前 3 字节 hex"""
    out = sh_raw("shell", "od", "-An", "-tx1", "-N3", "'" + path + "'").strip()
    return "".join(out.split()).lower()


def siblings(path):
    """同目录的 txt basename（不含扩展名）集合"""
    d = os.path.dirname(path)
    out = sh_raw("shell", "ls", "'" + d + "'")
    return {x[:-4] for x in out.split() if x.endswith(".txt")}


def report(rows, tag):
    print("[%s] img 文件数 = %d" % (tag, len(rows)))
    ok_magic = 0
    for size, p in rows:
        mg = magic_of(p)
        jpg = mg.startswith("ffd8ff")
        if jpg:
            ok_magic += 1
        base = os.path.basename(p)
        sns = base[len("img_"):].rsplit("_", 1)[0] if base.startswith("img_") else "?"
        pair = sns in siblings(p)
        print("  %-9s magic=%-6s pair=%-5s %s" % (size, mg or "?", pair, p))
    print("[%s] magic=JPEG 的 %d/%d" % (tag, ok_magic, len(rows)))
    return ok_magic


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "check"
    L.ensure_foreground()

    if mode == "check":
        report(list_imgs(), "now")
        return

    if mode == "base":
        rows = list_imgs()
        with open(BASE_FILE, "w", encoding="utf-8") as f:
            for size, p in rows:
                f.write("%s\t%s\n" % (size, p))
        print("baseline saved: %d files -> %s" % (len(rows), BASE_FILE))
        report(rows, "base")
        return

    if mode == "fill":
        before = {}
        if os.path.exists(BASE_FILE):
            for ln in open(BASE_FILE, encoding="utf-8"):
                if "\t" in ln:
                    s, p = ln.rstrip("\n").split("\t", 1)
                    before[p] = s
        L.logclear()
        L.bc("--es", "op", "moments", "--es", "sub", "stat")
        time.sleep(2)
        L.bc("--es", "op", "moments", "--es", "sub", "imgfill")
        time.sleep(25)
        L.pull_dbg()
        print("--- 回填日志 ---")
        L.grep(r"img backfill|sns index built|export stat", 12)

        rows = list_imgs()
        new = [(s, p) for s, p in rows if p not in before]
        print("\n=== 新增 img 文件 %d 个 ===" % len(new))
        report(rows, "after")
        if new:
            print("\n--- 新增明细 ---")
            for s, p in new:
                print("  %-9s %s" % (s, p))
        return

    print("usage: base | fill | check")


main()
