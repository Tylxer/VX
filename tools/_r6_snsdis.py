#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R6 静态取证：按方法签名反汇编其 const-string 序列（离线，不碰设备）。

用途：判定「delete from SnsComment where snsID = 」+「 and commentSvrID = 」
是不是同一个方法里拼接出来的 —— 是 ⇒ 删单条评论 = 物理 DELETE 行。

用法:
    python tools/_r6_snsdis.py <dex> <类描述> <方法名>
    python tools/_r6_snsdis.py classes3.dex Lcom/tencent/mm/plugin/sns/storage/s1; Q0
"""
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEXDIR = os.path.join(ROOT, "_cache", "dex")


class Dex:
    def __init__(self, path):
        self.d = open(path, "rb").read()
        h = self.d
        assert h[:4] == b"dex\n", "not a dex: " + path
        self.str_size, self.str_off = struct.unpack_from("<II", h, 0x38)
        self.type_size, self.type_off = struct.unpack_from("<II", h, 0x40)
        self.proto_size, self.proto_off = struct.unpack_from("<II", h, 0x48)
        self.field_size, self.field_off = struct.unpack_from("<II", h, 0x50)
        self.meth_size, self.meth_off = struct.unpack_from("<II", h, 0x58)
        self.cls_size, self.cls_off = struct.unpack_from("<II", h, 0x60)
        self.strings = self._strings()
        self.type_names = [
            self.strings[struct.unpack_from("<I", h, self.type_off + 4 * i)[0]]
            for i in range(self.type_size)
        ]
        self.methods = self._methods()

    def _uleb(self, off):
        r, s = 0, 0
        while True:
            b = self.d[off]
            off += 1
            r |= (b & 0x7F) << s
            if b & 0x80 == 0:
                break
            s += 7
        return r, off

    def _strings(self):
        out = []
        for i in range(self.str_size):
            off = struct.unpack_from("<I", self.d, self.str_off + 4 * i)[0]
            n, off = self._uleb(off)
            out.append(self.d[off:off + n].decode("utf-8", "ignore"))
        return out

    def _methods(self):
        out = []
        for i in range(self.meth_size):
            c, p, n = struct.unpack_from("<HHI", self.d, self.meth_off + 8 * i)
            out.append((self.type_names[c] if c < len(self.type_names) else "?",
                        self.strings[n] if n < len(self.strings) else "?", p))
        return out

    def _proto(self, pi):
        _, ret, params_off = struct.unpack_from("<III", self.d, self.proto_off + 12 * pi)
        ret_t = self.type_names[ret] if ret < len(self.type_names) else "?"
        args = []
        if params_off:
            n = struct.unpack_from("<I", self.d, params_off)[0]
            for k in range(n):
                ti = struct.unpack_from("<H", self.d, params_off + 4 + 2 * k)[0]
                args.append(self.type_names[ti] if ti < len(self.type_names) else "?")
        return args, ret_t

    def sig(self, mi):
        cls, name, pi = self.methods[mi]
        args, ret = self._proto(pi)
        return "%s->%s(%s)%s" % (cls, name, "".join(args), ret)

    def find_code(self, clsdesc, mname):
        """返回 (method_idx, code_off) 列表"""
        out = []
        for ci in range(self.cls_size):
            base = self.cls_off + 32 * ci
            cls_idx = struct.unpack_from("<I", self.d, base)[0]
            desc = self.type_names[cls_idx] if cls_idx < len(self.type_names) else "?"
            if desc != clsdesc:
                continue
            data_off = struct.unpack_from("<I", self.d, base + 24)[0]
            if data_off == 0:
                continue
            off = data_off
            sf, off = self._uleb(off)
            inf, off = self._uleb(off)
            dm, off = self._uleb(off)
            vm, off = self._uleb(off)
            for _ in range(sf + inf):
                _, off = self._uleb(off)
                _, off = self._uleb(off)
            for group in (dm, vm):
                prev = 0
                for _ in range(group):
                    diff, off = self._uleb(off)
                    prev += diff
                    _, off = self._uleb(off)
                    code_off, off = self._uleb(off)
                    if code_off == 0:
                        continue
                    mcls, nm, _p = self.methods[prev]
                    if nm == mname:
                        out.append((prev, code_off))
        return out

    def dis(self, code_off):
        """只解出「有语义的」指令：const-string / const / invoke / sget / iput 等"""
        d = self.d
        insns_size = struct.unpack_from("<I", d, code_off + 12)[0]
        p = code_off + 16
        i = 0
        out = []
        while i < insns_size:
            lo = d[p + 2 * i]
            u16 = lambda k: struct.unpack_from("<H", d, p + 2 * (i + k))[0]
            u32 = lambda k: struct.unpack_from("<I", d, p + 2 * (i + k))[0]
            try:
                if lo == 0x1A:  # const-string vAA, string@BBBB
                    si = u16(1)
                    out.append((i, "const-string", "[%d] %r" % (si, self.strings[si][:160])))
                    i += 2
                elif lo == 0x1B:  # const-string/jumbo
                    si = u32(1)
                    out.append((i, "const-string/jumbo", "[%d] %r" % (si, self.strings[si][:160])))
                    i += 3
                elif lo in (0x6E, 0x6F, 0x70, 0x71, 0x72):
                    mi = u16(1)
                    out.append((i, "invoke", self.sig(mi) if mi < len(self.methods) else "?"))
                    i += 3
                elif 0x74 <= lo <= 0x78:
                    mi = u16(1)
                    out.append((i, "invoke/range", self.sig(mi) if mi < len(self.methods) else "?"))
                    i += 3
                elif lo == 0x14:  # const vAA, #+BBBB
                    out.append((i, "const", "%d" % struct.unpack_from("<h", d, p + 2 * i + 2)[0]))
                    i += 2
                elif lo == 0x15:  # const/high16
                    out.append((i, "const/high16", "%d" % (struct.unpack_from("<h", d, p + 2 * i + 2)[0] << 16)))
                    i += 2
                elif lo == 0x16:  # const-wide/16
                    out.append((i, "const-wide/16", "%d" % struct.unpack_from("<h", d, p + 2 * i + 2)[0]))
                    i += 2
                elif lo == 0x17:  # const-wide/32
                    out.append((i, "const-wide/32", "%d" % struct.unpack_from("<i", d, p + 2 * i + 2)[0]))
                    i += 3
                elif lo == 0x18:  # const-wide
                    out.append((i, "const-wide", "%d" % struct.unpack_from("<q", d, p + 2 * i + 2)[0]))
                    i += 5
                elif lo == 0x19:  # const-wide/high16
                    out.append((i, "const-wide/high16", "%d" % (struct.unpack_from("<h", d, p + 2 * i + 2)[0] << 48)))
                    i += 2
                elif lo in (0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69):
                    fi = u16(1)
                    out.append((i, "sget/sput", str(fi)))
                    i += 2
                elif lo in (0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x5B):
                    fi = u16(1)
                    out.append((i, "iget/iput", str(fi)))
                    i += 2
                else:
                    # 宽度表（简化）：大多数 opcode 是 1 或 2 或 3 units
                    i += _width(lo)
            except (struct.error, IndexError):
                break
        return out


_W1 = set()
_W2 = set()
_W3 = set()
for _op in [0x0E, 0x0F, 0x10, 0x11, 0x12, 0x0A, 0x0B, 0x0C, 0x0D, 0x01, 0x04, 0x07,
            0x02, 0x05, 0x08, 0x03, 0x06, 0x09, 0x13, 0x00, 0x1D, 0x1E, 0x27, 0x21,
            0x22, 0x23, 0x24, 0x25, 0x26, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x28, 0x0E]:
    _W1.add(_op)


def _width(lo):
    """返回指令占多少个 16-bit code unit（只覆盖常见 opcode；不认识就当 1）"""
    if lo in (0x01, 0x04, 0x07, 0x02, 0x05, 0x08, 0x03, 0x06, 0x09, 0x0A, 0x0B, 0x0C,
              0x0D, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x00, 0x0E, 0x1D, 0x1E, 0x21,
              0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x2C,
              0x2D, 0x1F, 0x20):
        return 1
    if 0x2E <= lo <= 0x3D:
        return 2
    if 0x44 <= lo <= 0x51:
        return 2
    if 0x90 <= lo <= 0xAF:
        return 2
    if 0xD0 <= lo <= 0xE2:
        return 2
    if lo in (0x1A, 0x14, 0x15, 0x16, 0x19, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65,
              0x66, 0x67, 0x68, 0x69, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
              0x59, 0x5A, 0x5B, 0x1C, 0x20, 0x22, 0x23):
        return 2
    if lo in (0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78, 0x1B,
              0x17, 0x6D, 0x73, 0x79, 0xFC, 0xFD):
        return 3
    if lo in (0x18, 0xFA, 0xFB):
        return 5 if lo == 0x18 else 4
    if lo == 0x24 or lo == 0x25:
        return 5
    if 0x2C == lo:
        return 2
    return 2


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return
    dexname, clsdesc, mname = sys.argv[1], sys.argv[2], sys.argv[3]
    dex = Dex(os.path.join(DEXDIR, dexname))
    hits = dex.find_code(clsdesc, mname)
    if not hits:
        print("没找到 %s->%s" % (clsdesc, mname))
        return
    for mi, code_off in hits:
        print("=" * 70)
        print(dex.sig(mi))
        print("=" * 70)
        for i, op, arg in dex.dis(code_off):
            print("  [%4d] %-18s %s" % (i, op, arg))


if __name__ == "__main__":
    main()
