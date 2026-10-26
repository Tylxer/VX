#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""R28-转发(FoRward)取证专用：跨全部 dex 按**正则**找类定义，可选打印全部方法签名。

⚠️ 文件名带 fw 前缀是为了和队友的 tools/_r28_cls.py（头像取证）避免互相覆盖。

用法：
    python tools/_r28fw_cls.py MsgRetransmitUI
    python tools/_r28fw_cls.py 'Lhl0/r1;' --methods
    python tools/_r28fw_cls.py 'hl0/' --methods max 200
"""
import glob
import io
import os
import re
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
        self.path = path
        self.b = io.open(path, "rb").read()
        assert self.b[:3] == b"dex", path
        (self.strsz, self.stroff, self.tysz, self.tyoff,
         self.prosz, self.prooff, self.fldsz, self.fldoff,
         self.mthsz, self.mthoff, self.cdsz, self.cdoff) = struct.unpack_from("<12I", self.b, 0x38)
        self.strings = self._strings()
        self.types = self._types()

    def _strings(self):
        out = []
        b = self.b
        for k in range(self.strsz):
            off, = struct.unpack_from("<I", b, self.stroff + 4 * k)
            _, p = uleb(b, off)
            e = p
            while b[e] != 0:
                e += 1
            out.append(b[p:e].decode("utf-8", "replace"))
        return out

    def _types(self):
        return [self.strings[struct.unpack_from("<I", self.b, self.tyoff + 4 * k)[0]]
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

    def class_defs(self):
        b = self.b
        for i in range(self.cdsz):
            base = self.cdoff + 32 * i
            class_idx, access, sup_idx = struct.unpack_from("<III", b, base)
            name = self.types[class_idx] if class_idx < len(self.types) else "?"
            yield i, access, sup_idx, name

    def methods_of(self, def_index):
        b = self.b
        base = self.cdoff + 32 * def_index
        class_data_off = struct.unpack_from("<I", b, base + 24)[0]
        if class_data_off == 0:
            return []
        p = class_data_off
        sf, p = uleb(b, p)
        inf, p = uleb(b, p)
        dm, p = uleb(b, p)
        vm, p = uleb(b, p)
        for _ in range(sf + inf):
            _, p = uleb(b, p)
            _, p = uleb(b, p)
        out = []
        for cnt in (dm, vm):
            idx = 0
            for _ in range(cnt):
                diff, p = uleb(b, p)
                acc, p = uleb(b, p)
                _code, p = uleb(b, p)
                idx += diff
                if idx >= self.mthsz:
                    continue
                moff = self.mthoff + 8 * idx
                _c, proto_idx, name_idx = struct.unpack_from("<HHI", b, moff)
                if name_idx >= self.strsz:
                    continue
                out.append((self.strings[name_idx], self._proto(proto_idx), acc))
        return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = set(a for a in sys.argv[1:] if a.startswith("--"))
    if not args:
        raise SystemExit(__doc__)
    pat = re.compile(args[0], re.I)
    nmax = 60
    if "max" in args[1:]:
        nmax = int(args[args.index("max") + 1])
    shown = 0
    for path in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        d = Dex(path)
        for def_index, access, sup_idx, name in d.class_defs():
            if not pat.search(name):
                continue
            shown += 1
            if shown > nmax:
                print("... 超过 %d 个，收窄正则再看" % nmax)
                return
            sup = d.types[sup_idx] if sup_idx < len(d.types) else "?"
            print("[%s] %s" % (os.path.basename(path), name))
            print("    super=%s access=0x%04x" % (sup, access))
            if "--methods" in flags:
                for n, de, a in d.methods_of(def_index):
                    print("      %s%s%s" % ("static " if (a & ACC_STATIC) else "", n, de))
    if shown == 0:
        print("NO class matched: %s" % args[0])


if __name__ == "__main__":
    main()
