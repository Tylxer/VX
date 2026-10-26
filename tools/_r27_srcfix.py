#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R27-3 (srcfix) 专用：sourceType 修改路径取证。

相比 _r27_disasm.py / _r27_callers.py 的改进：
1. 完整 opcode 长度表（不再靠「未知长度 i+=1」猜测，杜绝 desync 假阳性）；
2. 正确解码 const/4、const/16、const、invoke 寄存器；
3. 调用者搜索基于「方法体线性扫描 + 完整长度解码」，跨全 dex。

用法：
    python tools/_r27_srcfix.py disasm  Lcom/tencent/mm/plugin/sns/storage/d2; S7
    python tools/_r27_srcfix.py callers Lcom/tencent/mm/plugin/sns/storage/d2; S7
    python tools/_r27_srcfix.py strs "UPDATE SnsInfo SET sourceType"
"""
import glob
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


def sleb(b, i):
    x = 0
    s = 0
    while True:
        v = b[i]
        i += 1
        x |= (v & 0x7F) << s
        s += 7
        if not (v & 0x80):
            if v & 0x40:
                x |= -(1 << s)
            return x, i


# opcode -> (fmt_name, instr_len_in_code_units)
# 只有「长度 > 1」的需要显式列出；未列出的按 1 处理。
def _op_len(op):
    if op == 0x00:  return 1  # nop (but payload may follow; keep 1)
    if op == 0x01:  return 1  # move
    if op in (0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d):  return 1
    if op in (0x0e, 0x0f, 0x10, 0x11):  return 1
    if op == 0x12:  return 1  # const/4 (11n)
    if op == 0x13:  return 2  # const/16 (21s)
    if op == 0x14:  return 3  # const (31i)
    if op == 0x15:  return 2  # const/high16 (21h)
    if op == 0x16:  return 2  # const-wide/16 (21s)
    if op == 0x17:  return 3  # const-wide/32 (31i)
    if op == 0x18:  return 5  # const-wide (51l)
    if op == 0x19:  return 2  # const-wide/high16 (21h)
    if op in (0x1a, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x21, 0x22, 0x23):  return 2
    if op == 0x1b:  return 3
    if op in (0x24, 0x25):  return 3
    if op == 0x26:  return 4  # fill-array-data (has payload)
    if op == 0x27:  return 1
    if op in (0x28,):  return 1
    if op == 0x29:  return 2
    if op == 0x2a:  return 3
    if op in (0x2b, 0x2c):  return 3  # packed/sparse switch (has payload)
    if op in (0x2d, 0x2e, 0x2f, 0x30, 0x31):  return 2
    if op in (0x32, 0x33, 0x34, 0x35, 0x36, 0x37):  return 2
    if op in (0x38, 0x39, 0x3a, 0x3b, 0x3c, 0x3d):  return 2
    if op in (0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x4b, 0x4c, 0x4d, 0x4e, 0x4f, 0x50, 0x51):  return 2
    if op in (0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x5b, 0x5c, 0x5d, 0x5e, 0x5f):  return 2
    if op in (0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x6b, 0x6c, 0x6d):  return 2
    if op in (0x6e, 0x6f, 0x70, 0x71, 0x72):  return 3
    if op == 0x73:  return 3  # invoke-polymorphic (not used in practice but 35c)
    if op in (0x74, 0x75, 0x76, 0x77, 0x78):  return 3
    if op in (0x7b, 0x7c, 0x7d, 0x7e, 0x7f, 0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0x8f):  return 1
    if op in (0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f, 0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5):  return 2
    if op in (0xb0, 0xb1, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xbb, 0xbc, 0xbd, 0xbe, 0xbf, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5):  return 1
    if op in (0xd0, 0xd1, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7):  return 2
    if op in (0xd8, 0xd9, 0xda, 0xdb, 0xdc, 0xdd, 0xde, 0xdf, 0xe0, 0xe1, 0xe2):  return 2
    if op in (0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa, 0xfb, 0xfc, 0xfd, 0xfe, 0xff):  return 1  # payload/odd — 保守 1
    return 1


OP_NAMES = {
    0x0e: "return-void", 0x0f: "return", 0x10: "return-wide", 0x11: "return-object",
    0x12: "const/4", 0x13: "const/16", 0x14: "const", 0x15: "const/high16",
    0x16: "const-wide/16", 0x17: "const-wide/32", 0x18: "const-wide", 0x19: "const-wide/high16",
    0x1a: "const-string", 0x1b: "const-string/jumbo", 0x1c: "const-class",
    0x22: "new-instance", 0x1f: "check-cast", 0x20: "instance-of",
    0x6e: "invoke-virtual", 0x6f: "invoke-super", 0x70: "invoke-direct",
    0x71: "invoke-static", 0x72: "invoke-interface",
    0x74: "invoke-virtual/range", 0x75: "invoke-super/range",
    0x76: "invoke-direct/range", 0x77: "invoke-static/range", 0x78: "invoke-interface/range",
    0x52: "iget", 0x54: "iget-object", 0x59: "iput", 0x5b: "iput-object",
    0x60: "sget", 0x62: "sget-object", 0x67: "sput", 0x69: "sput-object",
    0x95: "and-int", 0x96: "or-int", 0x97: "xor-int", 0x91: "sub-int",
    0x90: "add-int", 0xd5: "and-int/lit16", 0xd6: "or-int/lit16", 0xdd: "and-int/lit8",
    0x28: "goto", 0x29: "goto/16", 0x2a: "goto/32",
    0x32: "if-eq", 0x33: "if-ne", 0x34: "if-lt", 0x35: "if-ge", 0x36: "if-gt", 0x37: "if-le",
    0x38: "if-eqz", 0x39: "if-nez", 0x3a: "if-ltz", 0x3b: "if-gez", 0x3c: "if-gtz", 0x3d: "if-lez",
    0x1d: "monitor-enter", 0x1e: "monitor-exit", 0x21: "array-length",
    0x23: "new-array", 0x24: "filled-new-array", 0x25: "filled-new-array/range",
    0x26: "fill-array-data", 0x27: "throw", 0x2b: "packed-switch", 0x2c: "sparse-switch",
    0x44: "aget", 0x45: "aget-wide", 0x46: "aget-object", 0x4b: "aput", 0x4c: "aput-wide", 0x4d: "aput-object",
    0x7b: "neg-int", 0x7c: "not-int", 0x7d: "neg-long", 0x7e: "not-long",
    0x81: "int-to-long", 0x84: "long-to-int", 0x8d: "int-to-byte", 0x8e: "int-to-char", 0x8f: "int-to-short",
    0xb0: "add-int/2addr", 0xb1: "sub-int/2addr", 0xb5: "and-int/2addr", 0xb6: "or-int/2addr",
}


class Dex:
    def __init__(self, path):
        self.path = path
        self.b = open(path, "rb").read()
        assert self.b[:4] == b"dex\n", path
        (self.ss, self.so, self.ts, self.to, self.ps, self.po,
         self.fs, self.fo, self.ms, self.mo, self.cs, self.co) = struct.unpack_from("<12I", self.b, 0x38)
        self.strings = self._strings()
        self.types = self._types()
        self.methods = self._methods()
        self.classes = self._classes()

    def _strings(self):
        out = []
        for k in range(self.ss):
            off, = struct.unpack_from("<I", self.b, self.so + 4 * k)
            _, p = uleb(self.b, off)
            end = p
            while self.b[end] != 0:
                end += 1
            out.append(self.b[p:end].decode("utf-8", "replace"))
        return out

    def _types(self):
        return [self.strings[struct.unpack_from("<I", self.b, self.to + 4 * k)[0]] for k in range(self.ts)]

    def _methods(self):
        out = []
        for k in range(self.ms):
            ci, pi, ni = struct.unpack_from("<HHI", self.b, self.mo + 8 * k)
            out.append((self.types[ci] if ci < len(self.types) else "?",
                        self.strings[ni] if ni < len(self.strings) else "?", pi))
        return out

    def _classes(self):
        out = {}
        for k in range(self.cs):
            base = self.co + 32 * k
            cidx = struct.unpack_from("<I", self.b, base)[0]
            cdata = struct.unpack_from("<I", self.b, base + 24)[0]
            out[self.types[cidx]] = cdata
        return out

    def method_id(self, idx):
        if idx >= self.ms:
            return None
        return self.methods[idx]

    def field_id(self, idx):
        if idx >= self.fs:
            return None
        ci, ti, ni = struct.unpack_from("<HHI", self.b, self.fo + 8 * idx)
        return (self.types[ci] if ci < len(self.types) else "?",
                self.strings[ni] if ni < len(self.strings) else "?")

    def type_id(self, idx):
        return self.types[idx] if idx < len(self.types) else "?"

    def sig(self, mi):
        cls, name, pi = self.methods[mi]
        if pi >= self.ps:
            return "%s->%s(?)" % (cls, name)
        shorty, ret, params_off = struct.unpack_from("<III", self.b, self.po + 12 * pi)
        ret_t = self.type_id(ret)
        args = []
        if params_off:
            n = struct.unpack_from("<I", self.b, params_off)[0]
            for k in range(n):
                ti = struct.unpack_from("<H", self.b, params_off + 4 + 2 * k)[0]
                args.append(self.type_id(ti))
        return "%s->%s(%s)%s" % (cls, name, "".join(args), ret_t)

    def class_methods(self, cls):
        """返回 [(method_idx, name, code_off)]，code_off=0 表示 abstract/native"""
        if cls not in self.classes:
            return None
        pos = self.classes[cls]
        if pos == 0:
            return []
        sf, pos = uleb(self.b, pos)
        inf, pos = uleb(self.b, pos)
        dm, pos = uleb(self.b, pos)
        vm, pos = uleb(self.b, pos)

        def rf(n, pos):
            idx = 0
            for _ in range(n):
                d, pos = uleb(self.b, pos)
                idx += d
                _, pos = uleb(self.b, pos)
            return pos

        def rm(n, pos):
            idx = 0
            res = []
            for _ in range(n):
                d, pos = uleb(self.b, pos)
                idx += d
                fl, pos = uleb(self.b, pos)
                code = 0
                if not (fl & 0x100):
                    code, pos = uleb(self.b, pos)
                res.append((idx, code))
            return res, pos

        pos = rf(sf, pos)
        pos = rf(inf, pos)
        dms, pos = rm(dm, pos)
        vms, pos = rm(vm, pos)
        out = []
        for midx, code in dms + vms:
            if midx >= self.ms:
                continue
            _, name, _ = self.methods[midx]
            out.append((midx, name, code))
        return out

    def insns(self, code_off):
        regs, ins, outs, tries = struct.unpack_from("<4H", self.b, code_off)
        n, = struct.unpack_from("<I", self.b, code_off + 12)
        return regs, ins, outs, tries, self.b[code_off + 16:code_off + 16 + n * 2]


def disasm_method(dex, cls, meth):
    cms = dex.class_methods(cls)
    if cms is None:
        print("NO-CLASS %s" % cls)
        return
    for midx, name, code in cms:
        if name != meth:
            continue
        if code == 0:
            print("%s#%s NO-CODE (abstract/native)" % (cls, meth))
            continue
        regs, ins, outs, tries, u = dex.insns(code)
        print("=== %s#%s code=0x%x insns_units=%d ===" % (cls, meth, code, len(u) // 2))
        n = len(u) // 2
        i = 0
        while i < n:
            off = i
            unit = struct.unpack_from("<H", u, i * 2)[0]
            op = unit & 0xFF
            name = OP_NAMES.get(op, "op-0x%02x" % op)
            line = None
            if op in (0x6e, 0x6f, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78):
                if i + 2 < n:
                    if op in (0x74, 0x75, 0x76, 0x77, 0x78):
                        cnt = (unit >> 8) & 0xFF
                        mi = struct.unpack_from("<H", u, i * 2 + 2)[0]
                        reg0 = struct.unpack_from("<H", u, i * 2 + 4)[0]
                        tgt = dex.sig(mi) if mi < dex.ms else "?@%d" % mi
                        line = "%s {v%d..v%d} %s" % (name, reg0, reg0 + cnt - 1, tgt)
                    else:
                        a = (unit >> 12) & 0xF
                        b = (unit >> 8) & 0xF
                        mi = struct.unpack_from("<H", u, i * 2 + 2)[0]
                        tgt = dex.sig(mi) if mi < dex.ms else "?@%d" % mi
                        regs_s = ",".join("v%d" % ((b + k) & 0xF) for k in range(a + 1))
                        line = "%s {%s} %s" % (name, regs_s, tgt)
            elif op in (0x1a,):
                idx = struct.unpack_from("<H", u, i * 2 + 2)[0]
                line = "const-string v%d, %r" % ((unit >> 8) & 0xFF, dex.strings[idx] if idx < dex.ss else "?")
            elif op in (0x1b,):
                idx = struct.unpack_from("<I", u, i * 2 + 2)[0]
                line = "const-string/jumbo v%d, %r" % ((unit >> 8) & 0xFF, dex.strings[idx] if idx < dex.ss else "?")
            elif op in (0x1c,):
                idx = struct.unpack_from("<H", u, i * 2 + 2)[0]
                line = "const-class v%d, %s" % ((unit >> 8) & 0xFF, dex.type_id(idx))
            elif op in (0x22,):
                idx = struct.unpack_from("<H", u, i * 2 + 2)[0]
                line = "new-instance v%d, %s" % ((unit >> 8) & 0xFF, dex.type_id(idx))
            elif op in (0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
                        0x59, 0x5a, 0x5b, 0x5c, 0x5d, 0x5e, 0x5f,
                        0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66,
                        0x67, 0x68, 0x69, 0x6a, 0x6b, 0x6c, 0x6d):
                idx = struct.unpack_from("<H", u, i * 2 + 2)[0]
                f = dex.field_id(idx)
                a = (unit >> 8) & 0xFF
                b = (unit >> 12) & 0xF
                if f:
                    line = "%s v%d, v%d, %s->%s" % (name, a, b, f[0], f[1])
                else:
                    line = "%s v%d, v%d, ?@%d" % (name, a, b, idx)
            elif op == 0x12:
                a = (unit >> 8) & 0xF
                bb = (unit >> 12) & 0xF
                lit = bb if bb < 8 else bb - 16  # signed 4-bit
                line = "const/4 v%d, %d" % (a, lit)
            elif op == 0x13:
                a = (unit >> 8) & 0xFF
                lit = struct.unpack_from("<h", u, i * 2 + 2)[0]
                line = "const/16 v%d, %d" % (a, lit)
            elif op == 0x14:
                a = (unit >> 8) & 0xFF
                lit = struct.unpack_from("<I", u, i * 2 + 2)[0]
                line = "const v%d, 0x%08x (%d)" % (a, lit, lit if lit < 0x80000000 else lit - 0x100000000)
            elif op == 0x15:
                a = (unit >> 8) & 0xFF
                lit = struct.unpack_from("<H", u, i * 2 + 2)[0]
                line = "const/high16 v%d, 0x%04x" % (a, lit)
            elif op in (0x16, 0x17):
                a = (unit >> 8) & 0xFF
                lit = struct.unpack_from("<q", u, i * 2 + 2)[0]
                line = "const-wide/16or32 v%d, %d" % (a, lit)
            elif op in (0x0e, 0x0f, 0x10, 0x11):
                line = name
            elif op in (0x28,):
                line = "goto %+d" % struct.unpack_from("<b", u, i * 2 + 2)[0]
            elif op in (0x29,):
                line = "goto/16 %+d" % struct.unpack_from("<h", u, i * 2 + 2)[0]
            elif op in (0x2a,):
                line = "goto/32 %+d" % struct.unpack_from("<i", u, i * 2 + 2)[0]
            elif op in (0x32, 0x33, 0x34, 0x35, 0x36, 0x37):
                a = (unit >> 8) & 0xFF
                b = (unit >> 12) & 0xF
                line = "%s v%d, v%d, %+d" % (name, a, b, struct.unpack_from("<h", u, i * 2 + 2)[0])
            elif op in (0x38, 0x39, 0x3a, 0x3b, 0x3c, 0x3d):
                a = (unit >> 8) & 0xFF
                line = "%s v%d, %+d" % (name, a, struct.unpack_from("<h", u, i * 2 + 2)[0])
            else:
                line = name
            print("   0x%04x  %s" % (off, line))
            i += _op_len(op)


def find_callers(target_cls, target_name):
    """找调用 target_cls#target_name 的 invoke 点。

    ⚠️ 只扫**定义了目标类**的那个 dex（SNS 插件类都在 classes3.dex 里）。
    跨 dex 调用在 WeChat 里基本不存在（各插件自含）。
    """
    results = []
    for path in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        dex = Dex(path)
        target_idxs = {i for i, (c, n, _p) in enumerate(dex.methods) if c == target_cls and n == target_name}
        if not target_idxs:
            continue
        if target_cls not in dex.classes:
            continue
        # 扫所有类的所有方法体
        for clsname in list(dex.classes.keys()):
            cms = dex.class_methods(clsname)
            if not cms:
                continue
            for midx, name, code in cms:
                if code == 0:
                    continue
                try:
                    regs, ins, outs, tries, u = dex.insns(code)
                except Exception:
                    continue
                n = len(u) // 2
                hits = []
                i = 0
                while i < n:
                    unit = struct.unpack_from("<H", u, i * 2)[0]
                    op = unit & 0xFF
                    if op in (0x6e, 0x6f, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78):
                        if i + 2 < n:
                            # 35c 与 3rc 的 method_idx 都在第 2 个 code unit（i*2+2）
                            mi = struct.unpack_from("<H", u, i * 2 + 2)[0]
                            if mi in target_idxs:
                                hits.append(i)
                    i += _op_len(op)
                if hits:
                    results.append((os.path.basename(path), clsname, name, midx, hits))
    return results


def cmd_disasm(argv):
    cls = argv[0]
    if not cls.startswith("L"):
        cls = "L" + cls.replace(".", "/") + ";"
    meth = argv[1] if len(argv) > 1 else None
    for path in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        dex = Dex(path)
        if cls in dex.classes:
            if meth:
                disasm_method(dex, cls, meth)
            else:
                for midx, name, code in dex.class_methods(cls):
                    print("   %s" % dex.sig(midx))


def cmd_callers(argv):
    cls = argv[0]
    if not cls.startswith("L"):
        cls = "L" + cls.replace(".", "/") + ";"
    meth = argv[1]
    res = find_callers(cls, meth)
    if not res:
        print("NO-CALLERS %s#%s" % (cls, meth))
        return
    seen = set()
    for dexfile, caller_cls, caller_meth, midx, hits in res:
        k = (caller_cls, caller_meth)
        if k in seen:
            continue
        seen.add(k)
        print("%s: %s#%s  invoke@[%s]" % (dexfile, caller_cls, caller_meth,
                                           ",".join(hex(h) for h in hits)))


def cmd_strs(argv):
    key = argv[0]
    for path in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        dex = Dex(path)
        for i, s in enumerate(dex.strings):
            if key in s:
                print("%s string[%d] = %r" % (os.path.basename(path), i, s))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    cmd = sys.argv[1]
    argv = sys.argv[2:]
    if cmd == "disasm":
        cmd_disasm(argv)
    elif cmd == "callers":
        cmd_callers(argv)
    elif cmd == "strs":
        cmd_strs(argv)
    else:
        print("unknown cmd", cmd)
