#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""从既有 dexdump -d 全量反汇编里抽出**单个类**的完整块（含字节码），落盘供 grep/awk。

为什么不用 `dexdump -d` 重跑：classes7.dex 全量 ~230MB，跑一次几分钟；
_cache/_r11_dex7_dis.txt 已经是全量结果，直接切片更快。

用法：
    python tools/_r28fw_extract.py Lhl0/q1;
    python tools/_r28fw_extract.py Lhl0/q1; Lhl0/m; Lhl0/r1; Lhl0/l1;
       → 每个类写到 _cache/_r28fw_<类名>.txt
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIS = [
    os.path.join(ROOT, "_cache", "_r11_dex7_dis.txt"),
    os.path.join(ROOT, "_cache", "_s9_classes2_dis.txt"),
    os.path.join(ROOT, "_cache", "_x2_full.txt"),
    os.path.join(ROOT, "_cache", "_s9_classes7_dis.txt"),
]


def extract(descs):
    """先扫一遍所有可用的 dis 文件，按 `Class descriptor  : 'X'` 定位并切片"""
    for desc in descs:
        if not desc.startswith("L"):
            desc = "L" + desc.replace(".", "/") + ";"
        target = "  Class descriptor  : '%s'\n" % desc
        hit = False
        for path in DIS:
            if not os.path.exists(path):
                continue
            start = None
            end = None
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                prev_blank = False
                ln = 0
                for line in f:
                    ln += 1
                    if line == target:
                        start = ln
                        continue
                    if start is not None and line.startswith("  Class descriptor  :"):
                        end = ln
                        break
                if start is None:
                    continue
                end = end if end else ln
            out = os.path.join(
                ROOT, "_cache", "_r28fw_%s.txt" % desc[1:-1].replace("/", "_"))
            n = 0
            with open(path, "r", encoding="utf-8", errors="ignore") as f, \
                    open(out, "w", encoding="utf-8") as o:
                for i, line in enumerate(f, 1):
                    if i < start:
                        continue
                    if i >= end:
                        break
                    o.write(line)
                    n += 1
            print("OK %s -> %s (%d lines, src=%s)" % (
                desc, os.path.basename(out), n, os.path.basename(path)))
            hit = True
            break
        if not hit:
            print("MISS %s (不在已知 dis 里，需重跑 dexdump)" % desc)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    extract(sys.argv[1:])
