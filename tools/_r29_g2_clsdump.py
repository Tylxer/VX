#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""R29-G2 取证：dump 指定类（按类名子串）的 instance/static 字段 + 方法签名。
走 class_defs，字段与方法的 uleb 解析分开（字段无 code_off；方法对 abstract/native 特殊处理）。
只读取证工具，不改业务代码。

用法：
    python tools/_r29_g2_clsdump.py 'ui/chatting/viewitems/s0;'
    python tools/_r29_g2_clsdump.py 'ui/chatting/viewitems/f0;'
    python tools/_r29_g2_clsdump.py 'storage/h9;' --meth  getMsgId getType
"""
import glob
import io
import os
import struct
import sys

DEXDIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "_cache", "dex")

ACC_STATIC = 0x0008
ACC_NATIVE = 0x0100
ACC_ABSTRACT = 0x0400


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
        self.path = path
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

    def dump(self, cls_substr, meth_filter=None):
        b = self.b
        for i in range(self.cdsz):
            base = self.cdoff + 32 * i
            class_idx, acc, sup_idx = struct.unpack_from("<III", b, base)
            cname = self.types[class_idx] if class_idx < len(self.types) else "?"
            if cls_substr not in cname:
                continue
            sup = self.types[sup_idx] if sup_idx < len(self.types) else "?"
            print("== %s  [class_defs#%d] super=%s" % (cname, i, sup))
            cdata, = struct.unpack_from("<I", b, base + 24)
            if cdata == 0:
                continue
            p = cdata
            sf, p = uleb(b, p)
            inf, p = uleb(b, p)
            dm, p = uleb(b, p)
            vm, p = uleb(b, p)
            # static fields
            idx = 0
            for _ in range(sf):
                diff, p = uleb(b, p)
                facc, p = uleb(b, p)
                idx += diff
                foff = self.fldoff + 8 * idx
                _c, t, n = struct.unpack_from("<HHI", b, foff)
                tn = self.types[t] if t < len(self.types) else "?"
                nn = self.strings[n] if n < len(self.strings) else "?"
                print("   [static fld] %s : %s" % (nn, tn))
            # instance fields
            idx = 0
            for _ in range(inf):
                diff, p = uleb(b, p)
                facc, p = uleb(b, p)
                idx += diff
                foff = self.fldoff + 8 * idx
                _c, t, n = struct.unpack_from("<HHI", b, foff)
                tn = self.types[t] if t < len(self.types) else "?"
                nn = self.strings[n] if n < len(self.strings) else "?"
                print("   [inst  fld] %s : %s" % (nn, tn))
            # methods
            for kind, cnt in (("direct", dm), ("virtual", vm)):
                idx = 0
                for _ in range(cnt):
                    diff, p = uleb(b, p)
                    macc, p = uleb(b, p)
                    if macc & (ACC_NATIVE | ACC_ABSTRACT):
                        code = 0
                    else:
                        code, p = uleb(b, p)
                    idx += diff
                    if idx >= self.mthsz:
                        continue
                    moff = self.mthoff + 8 * idx
                    _c, pi, n = struct.unpack_from("<HHI", b, moff)
                    mn = self.strings[n] if n < len(self.strings) else "?"
                    desc = self._proto(pi)
                    if meth_filter and not any(f in mn for f in meth_filter):
                        continue
                    st = "static" if macc & ACC_STATIC else "inst  "
                    print("   %s %-7s ->%s%s" % (st, kind, mn, desc))


def main():
    args = sys.argv[1:]
    meth_filter = None
    if "--meth" in args:
        k = args.index("--meth")
        meth_filter = args[k + 1:]
        args = args[:k]
    pats = args
    if not pats:
        raise SystemExit(__doc__)
    for path in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        d = Dex(path)
        for p in pats:
            d.dump(p, meth_filter)


if __name__ == "__main__":
    main()
