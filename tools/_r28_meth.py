r"""R28 头像取证 步骤2：跨全部 dex 扫描 **定义**了某个方法名的类（走 class_defs，不是 type_ids 引用）。

输出：dex / 类名 / 是否 static / 完整签名 / 是 direct 还是 virtual。
用于把 "attachAvatar" "getAvatarPath" "getAvatarBitmap" 这类名字锚到真实类上。

用法：
    python tools/_r28_meth.py attachAvatar
    python tools/_r28_meth.py getAvatarPath getAvatarBitmap loadAvatar
"""
import glob
import io
import os
import struct
import sys

DEXDIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "_cache", "dex")

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
        (self.strsz, self.stroff, self.tysz, self.tyoff, self.prosz, self.prooff,
         self.fldsz, self.fldoff, self.mthsz, self.mthoff,
         self.cdsz, self.cdoff) = struct.unpack_from("<12I", self.b, 0x38)
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

    def iter_class_methods(self):
        """yield (class_name, method_name, desc, access, kind)"""
        b = self.b
        for i in range(self.cdsz):
            base = self.cdoff + 32 * i
            class_idx, _acc, _sup = struct.unpack_from("<III", b, base)
            cname = self.types[class_idx] if class_idx < len(self.types) else "?"
            cdata_off, = struct.unpack_from("<I", b, base + 24)
            if cdata_off == 0:
                continue
            p = cdata_off
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
                    _c, proto_idx, name_idx = struct.unpack_from("<HHI", b, moff)
                    if name_idx >= self.strsz:
                        continue
                    yield (cname, self.strings[name_idx], self._proto(proto_idx), acc, kind)


def main():
    names = set(sys.argv[1:])
    if not names:
        raise SystemExit(__doc__)
    for path in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        d = Dex(path)
        hits = []
        for cn, mn, desc, acc, kind in d.iter_class_methods():
            if mn in names:
                hits.append((cn, mn, desc, acc, kind))
        if hits:
            print("=== %s  (%d)" % (os.path.basename(path), len(hits)))
            for cn, mn, desc, acc, kind in hits:
                st = "static" if acc & ACC_STATIC else "inst  "
                print("   %s %-5s %s%s" % (st, kind, cn, "->" + mn + desc))


if __name__ == "__main__":
    main()
