r"""R28 头像取证 步骤1：跨全部 dex 扫描含关键字的字符串常量，并统计出现数。

只读，不跑 gradle/adb。
用法：
    python tools/_r28_astr.py avatar
    python tools/_r28_astr.py avatar Avatar _hd usericon
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
    return b, strings, (strsz, stroff, tysz, tyoff, prosz, prooff,
                        fldsz, fldoff, mthsz, mthoff, cdsz, cdoff)


def main():
    kws = sys.argv[1:] or ["avatar"]
    for path in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        b, strings, hdr = load(path)
        hits = {}
        for s in strings:
            low = s.lower()
            for k in kws:
                if k.lower() in low:
                    hits.setdefault(s, 0)
                    hits[s] += 1
                    break
        if not hits:
            continue
        print("=== %s  (%d 条命中, 字符串池共 %d)" % (os.path.basename(path), len(hits), len(strings)))
        for s in sorted(hits, key=lambda x: (-hits[x], len(x))):
            print("   %s" % s)


if __name__ == "__main__":
    main()
