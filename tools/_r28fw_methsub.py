#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""R28-转发取证：跨全部 dex 找 **签名里含某个子串** 的类（例如谁监听 SendMsgEvent）。

用法：
    python tools/_r28fw_methsub.py 'Lcom/tencent/mm/autogen/events/SendMsgEvent;'
    python tools/_r28fw_methsub.py 'Lgj/or;'
"""
import glob
import io
import os
import struct
import sys

DEXDIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "_cache", "dex")
ACC_STATIC = 0x0008


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


class Dex:
    def __init__(self, path):
        self.b = io.open(path, "rb").read()
        (self.strsz, self.stroff, self.tysz, self.tyoff,
         self.prosz, self.prooff, self.fldsz, self.fldoff,
         self.mthsz, self.mthoff, self.cdsz, self.cdoff) = struct.unpack_from("<12I", self.b, 0x38)
        self.strings = []
        for k in range(self.strsz):
            off, = struct.unpack_from("<I", self.b, self.stroff + 4 * k)
            _, p = uleb(self.b, off)
            e = p
            while self.b[e] != 0:
                e += 1
            self.strings.append(self.b[p:e].decode("utf-8", "replace"))
        self.types = [self.strings[struct.unpack_from("<I", self.b, self.tyoff + 4 * k)[0]]
                      for k in range(self.tysz)]

    def _proto(self, pi):
        b = self.b
        if pi >= self.prosz:
            return "(?)"
        _s, ret_idx, params_off = struct.unpack_from("<III", b, self.prooff + 12 * pi)
        ret = self.types[ret_idx] if ret_idx < len(self.types) else "?"
        params = []
        if params_off:
            n, = struct.unpack_from("<I", b, params_off)
            for i in range(n):
                t, = struct.unpack_from("<H", b, params_off + 4 + 2 * i)
                params.append(self.types[t] if t < len(self.types) else "?")
        return "(" + "".join(params) + ")" + ret

    def scan(self, subs):
        b = self.b
        hits = []
        for i in range(self.cdsz):
            base = self.cdoff + 32 * i
            class_idx, _acc, _sup = struct.unpack_from("<III", b, base)
            cname = self.types[class_idx] if class_idx < len(self.types) else "?"
            cdata, = struct.unpack_from("<I", b, base + 24)
            if cdata == 0:
                continue
            p = cdata
            sf, p = uleb(b, p)
            inf, p = uleb(b, p)
            dm, p = uleb(b, p)
            vm, p = uleb(b, p)
            for _ in range(sf + inf):
                _, p = uleb(b, p)
                _, p = uleb(b, p)
            for kind, cnt in (("direct", dm), ("virtual", vm)):
                idx = 0
                for _ in range(cnt):
                    diff, p = uleb(b, p)
                    acc, p = uleb(b, p)
                    _code, p = uleb(b, p)
                    idx += diff
                    if idx >= self.mthsz:
                        continue
                    moff = self.mthoff + 8 * idx
                    _c, pi, name_idx = struct.unpack_from("<HHI", b, moff)
                    if name_idx >= self.strsz:
                        continue
                    desc = self._proto(pi)
                    if any(s in desc for s in subs):
                        hits.append((cname, self.strings[name_idx], desc, acc, kind))
        return hits


def main():
    subs = sys.argv[1:]
    if not subs:
        raise SystemExit(__doc__)
    for path in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        d = Dex(path)
        hits = d.scan(subs)
        if hits:
            print("=== %s  (%d)" % (os.path.basename(path), len(hits)))
            for cn, mn, desc, acc, kind in hits:
                print("   %-6s %s %s -> %s%s" % (
                    kind, "static" if acc & ACC_STATIC else "inst", cn, mn, desc))


if __name__ == "__main__":
    main()
