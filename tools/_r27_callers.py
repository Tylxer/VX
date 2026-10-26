#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R27 专用：跨 dex 搜索「谁调用了某个方法」—— 找 invoke-* 指令的调用者 + 指令偏移。
用于坐实调用者链（纪律：只认 invoke 地址 / 调用者链）。

用法：
    python tools/_r27_callers.py --class Lcom/tencent/mm/plugin/sns/storage/SnsInfo; --method isDeadSource
    python tools/_r27_callers.py --class Lcom/tencent/mm/plugin/sns/storage/d2; --method t5
"""
import argparse
import glob
import os
import struct

DEXDIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "_cache", "dex")


def uleb(b, i):
    x = s = 0
    while True:
        v = b[i]
        i += 1
        x |= (v & 0x7F) << s
        if not (v & 0x80):
            return x, i
        s += 7


INVOKE_OPS = {0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78}


def scan_dex(path, target_cls, target_name):
    b = open(path, "rb").read()
    if b[:4] != b"dex\n":
        return []
    (ss, so, ts, to, ps, po, fs, fo, ms, mo, cs, co) = struct.unpack_from("<12I", b, 0x38)

    def get_string(idx):
        if idx >= ss:
            return None
        off, = struct.unpack_from("<I", b, so + 4 * idx)
        _, p = uleb(b, off)
        end = p
        while end < len(b) and b[end] != 0:
            end += 1
        return b[p:end].decode("utf-8", "replace")

    strings = [get_string(k) for k in range(ss)]
    types = [strings[struct.unpack_from("<I", b, to + 4 * k)[0]] for k in range(ts)]

    methods = []
    for k in range(ms):
        ci, pi, ni = struct.unpack_from("<HHI", b, mo + 8 * k)
        cls = types[ci] if ci < len(types) else "?"
        nm = strings[ni] if ni < len(strings) else "?"
        methods.append((cls, nm))

    target_idxs = {i for i, (c, n) in enumerate(methods) if n == target_name and c == target_cls}
    if not target_idxs:
        return []

    out = []
    for k in range(cs):
        base = co + 32 * k
        cidx = struct.unpack_from("<I", b, base)[0]
        cdata = struct.unpack_from("<I", b, base + 24)[0]
        clsdesc = types[cidx] if cidx < len(types) else "?"
        if not cdata:
            continue
        pos = cdata
        sf, pos = uleb(b, pos)
        inf, pos = uleb(b, pos)
        dm, pos = uleb(b, pos)
        vm, pos = uleb(b, pos)

        def rf(n, pos):
            idx = 0
            for _ in range(n):
                d, pos = uleb(b, pos)
                idx += d
                _, pos = uleb(b, pos)
            return pos

        def rm(n, pos):
            idx = 0
            res = []
            for _ in range(n):
                d, pos = uleb(b, pos)
                idx += d
                fl, pos = uleb(b, pos)
                code = 0
                if not (fl & 0x100):
                    code, pos = uleb(b, pos)
                res.append((idx, fl, code))
            return res, pos

        pos = rf(sf, pos)
        pos = rf(inf, pos)
        dms, pos = rm(dm, pos)
        vms, pos = rm(vm, pos)

        for midx, fl, code in dms + vms:
            if not code or code + 16 > len(b):
                continue
            regs, ins, outs, tries = struct.unpack_from("<4H", b, code)
            insns_size, = struct.unpack_from("<I", b, code + 12)
            start = code + 16
            end = start + insns_size * 2
            if end > len(b):
                end = len(b)
            insns = b[start:end]
            n = len(insns) // 2
            hits = []
            i = 0
            while i < n:
                unit = struct.unpack_from("<H", insns, i * 2)[0]
                op = unit & 0xFF
                if op in INVOKE_OPS:
                    if i * 2 + 3 < len(insns):
                        mi = struct.unpack_from("<H", insns, i * 2 + 2)[0]
                        if mi in target_idxs:
                            hits.append(hex(i))
                        i += 3
                    else:
                        i += 1
                else:
                    i += 1
            if hits:
                mc, mn = methods[midx] if midx < len(methods) else ("?", "?")
                out.append((clsdesc, mc, mn, hits))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--class", dest="cls", required=True)
    ap.add_argument("--method", dest="meth", required=True)
    args = ap.parse_args()
    cls = args.cls if args.cls.startswith("L") else "L" + args.cls.replace(".", "/") + ";"
    found = 0
    for p in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        hits = scan_dex(p, cls, args.meth)
        if hits:
            for clsdesc, mc, mn, addrs in hits:
                print("%s: %s -> %s#%s  invoke@%s" % (os.path.basename(p), clsdesc, mc, mn, addrs))
                found += 1
    if not found:
        print("NO-CALLERS %s#%s" % (cls, args.meth))


if __name__ == "__main__":
    main()
