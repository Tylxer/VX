#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""R28-转发取证：判断某类型/字符串出现在哪些 dex 的 string 池里，以便只 dump 需要的 dex。

用法：
    python tools/_r28fw_where.py 'Lcom/tencent/mm/autogen/events/ExtNetSceneSendMsgEvent;'
    python tools/_r28fw_where.py 'Retr_Msg_Id' FowardMsg forward
"""
import glob
import io
import os
import struct
import sys

DEXDIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "_cache", "dex")


def uleb(b, i):
    x = 0
    s = 0
    while True:
        v = b[i]
        i += 1
        x |= (v & 0x7F) << s
        if not (v & 0x80):
            return x, i
        s += 7


def main():
    pats = sys.argv[1:]
    if not pats:
        raise SystemExit(__doc__)
    for path in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        b = io.open(path, "rb").read()
        strsz, stroff = struct.unpack_from("<II", b, 0x38)
        blob = []
        hit = set()
        for k in range(strsz):
            off, = struct.unpack_from("<I", b, stroff + 4 * k)
            _, p = uleb(b, off)
            e = p
            while b[e] != 0:
                e += 1
            s = b[p:e].decode("utf-8", "replace")
            blob.append(s)
            for q in pats:
                if q in s:
                    hit.add((q, s))
        if hit:
            print("=== %s" % os.path.basename(path))
            for q, s in sorted(hit)[:20]:
                print("   [%s] %s" % (q, s[:160]))


if __name__ == "__main__":
    main()
