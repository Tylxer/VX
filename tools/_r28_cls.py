r"""R28 头像取证 步骤3：跨全部 dex 列出 **定义**的类名（class_defs）里含关键字的类。

用法：
    python tools/_r28_cls.py avatar
    python tools/_r28_cls.py modelavatar          # 列出整个包
"""
import glob
import io
import os
import struct
import sys

DEXDIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "_cache", "dex")


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


def load(path):
    b = io.open(path, "rb").read()
    assert b[:3] == b"dex", path
    (strsz, stroff, tysz, tyoff, prosz, prooff, fldsz, fldoff,
     mthsz, mthoff, cdsz, cdoff) = struct.unpack_from("<12I", b, 0x38)
    strings = []
    for k in range(strsz):
        off, = struct.unpack_from("<I", b, stroff + 4 * k)
        _, p = uleb(b, off)
        e = p
        while b[e] != 0:
            e += 1
        strings.append(b[p:e].decode("utf-8", "replace"))
    types = [strings[struct.unpack_from("<I", b, tyoff + 4 * k)[0]] for k in range(tysz)]
    return b, strings, types, (cdsz, cdoff)


def main():
    kw = (sys.argv[1] if len(sys.argv) > 1 else "avatar").lower()
    for path in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        b, strings, types, (cdsz, cdoff) = load(path)
        out = []
        for i in range(cdsz):
            base = cdoff + 32 * i
            class_idx, acc, sup_idx = struct.unpack_from("<III", b, base)
            name = types[class_idx] if class_idx < len(types) else "?"
            if kw in name.lower():
                sup = types[sup_idx] if sup_idx < len(types) else "?"
                out.append((name, sup, i))
        if out:
            print("=== %s  (%d)" % (os.path.basename(path), len(out)))
            for name, sup, i in out:
                print("   [%5d] %s" % (i, name))
                print("          super=%s" % sup)


if __name__ == "__main__":
    main()
