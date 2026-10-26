#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R27 专用：反汇编单个方法，输出带**指令偏移**的 invoke-* / const-string / 字段访问等，
用于给出「谁调用了谁」的 invoke 地址（纪律要求：只认 invoke 地址 / 调用者链）。

用法：
    python tools/_r27_disasm.py --class Lcom/tencent/mm/plugin/sns/model/w5; --method onSceneEnd
    python tools/_r27_disasm.py --class Lcom/tencent/mm/plugin/sns/model/c3; --method onGYNetEnd --grep invoke
"""
import argparse
import glob
import io
import os
import struct

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


class Dex:
    def __init__(self, path):
        self.path = path
        self.b = io.open(path, "rb").read()
        assert self.b[:3] == b"dex", path
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from("<12I", self.b, 0x38)
        self.strings = self._strings()
        self.types = self._types()
        self.classes = self._classes()

    def _strings(self):
        out = []
        for k in range(self.string_ids_size):
            off, = struct.unpack_from("<I", self.b, self.string_ids_off + 4 * k)
            _, p = uleb(self.b, off)
            end = p
            while self.b[end] != 0:
                end += 1
            out.append(self.b[p:end].decode("utf-8", "replace"))
        return out

    def _types(self):
        return [self.strings[struct.unpack_from("<I", self.b, self.type_ids_off + 4 * k)[0]]
                for k in range(self.type_ids_size)]

    def _classes(self):
        out = {}
        for k in range(self.class_defs_size):
            base = self.class_defs_off + 32 * k
            class_idx, access, _super, _ifaces, _src, _ann, cdata_off, _sv = struct.unpack_from(
                "<8I", self.b, base)
            out[self.types[class_idx]] = (access, cdata_off)
        return out

    def method_id(self, idx):
        if idx >= self.method_ids_size:
            return None
        ci, pi, ni = struct.unpack_from("<HHI", self.b, self.method_ids_off + 8 * idx)
        if ci >= len(self.types) or ni >= len(self.strings):
            return None
        return self.types[ci], self.strings[ni], pi

    def field_id(self, idx):
        if idx >= self.field_ids_size:
            return None
        ci, ti, ni = struct.unpack_from("<HHI", self.b, self.field_ids_off + 8 * idx)
        if ci >= len(self.types) or ni >= len(self.strings):
            return None
        return self.types[ci], self.types[ti], self.strings[ni]

    def type_id(self, idx):
        return self.types[idx] if idx < len(self.types) else "?"

    def proto_str(self, pi):
        if pi >= self.proto_ids_size:
            return "?"
        shorty, ret, params_off = struct.unpack_from("<III", self.b, self.proto_ids_off + 12 * pi)
        ret_t = self.type_id(ret)
        args = []
        if params_off:
            n = struct.unpack_from("<I", self.b, params_off)[0]
            for k in range(n):
                ti = struct.unpack_from("<H", self.b, params_off + 4 + 2 * k)[0]
                args.append(self.type_id(ti))
        return "%s(%s)%s" % (ret_t, "".join(args), "")

    def class_data(self, cls):
        if cls not in self.classes:
            return None
        _, cdata_off = self.classes[cls]
        b = self.b
        pos = cdata_off
        sf, pos = uleb(b, pos)
        inf, pos = uleb(b, pos)
        dm, pos = uleb(b, pos)
        vm, pos = uleb(b, pos)

        def read_fields(n, pos):
            out = []
            idx = 0
            for _ in range(n):
                d, pos = uleb(b, pos)
                idx += d
                fl, pos = uleb(b, pos)
                out.append((idx, fl))
            return out, pos

        def read_methods(n, pos):
            out = []
            idx = 0
            for _ in range(n):
                d, pos = uleb(b, pos)
                idx += d
                fl, pos = uleb(b, pos)
                code = 0
                if not (fl & 0x100):
                    code, pos = uleb(b, pos)
                out.append((idx, fl, code))
            return out, pos

        sfields, pos = read_fields(sf, pos)
        ifields, pos = read_fields(inf, pos)
        dmethods, pos = read_methods(dm, pos)
        vmethods, pos = read_methods(vm, pos)
        return sfields, ifields, dmethods, vmethods

    def insns(self, code_off):
        b = self.b
        regs, ins, outs, tries = struct.unpack_from("<4H", b, code_off)
        insns_size, = struct.unpack_from("<I", b, code_off + 12)
        start = code_off + 16
        return regs, ins, outs, tries, b[start:start + insns_size * 2]


# opcode name tables (subset sufficient for tracing)
OP_NAMES = {}
for k, v in {
    0x00: "nop", 0x0e: "return-void", 0x0f: "return", 0x10: "return-wide",
    0x11: "return-object", 0x12: "const/4", 0x13: "const/16", 0x14: "const",
    0x15: "const/high16", 0x16: "const-wide/16", 0x17: "const-wide/32",
    0x18: "const-wide", 0x19: "const-wide/high16", 0x1a: "const-string",
    0x1b: "const-string/jumbo", 0x1c: "const-class", 0x1d: "monitor-enter",
    0x1e: "monitor-exit", 0x1f: "check-cast", 0x20: "instance-of",
    0x21: "array-length", 0x22: "new-instance", 0x23: "new-array",
    0x24: "filled-new-array", 0x25: "filled-new-array/range",
    0x26: "fill-array-data", 0x27: "throw", 0x28: "goto", 0x29: "goto/16",
    0x2a: "goto/32", 0x2b: "packed-switch", 0x2c: "sparse-switch",
    0x2d: "cmpl-float", 0x2e: "cmpg-float", 0x2f: "cmpl-double",
    0x30: "cmpg-double", 0x31: "cmp-long", 0x32: "if-eq", 0x33: "if-ne",
    0x34: "if-lt", 0x35: "if-ge", 0x36: "if-gt", 0x37: "if-le",
    0x38: "if-eqz", 0x39: "if-nez", 0x3a: "if-ltz", 0x3b: "if-gez",
    0x3c: "if-gtz", 0x3d: "if-lez", 0x44: "aget", 0x45: "aget-wide",
    0x46: "aget-object", 0x47: "aget-boolean", 0x48: "aget-byte",
    0x49: "aget-char", 0x4a: "aget-short", 0x4b: "aput", 0x4c: "aput-wide",
    0x4d: "aput-object", 0x4e: "aput-boolean", 0x4f: "aput-byte",
    0x50: "aput-char", 0x51: "aput-short", 0x52: "iget", 0x53: "iget-wide",
    0x54: "iget-object", 0x55: "iget-boolean", 0x56: "iget-byte",
    0x57: "iget-char", 0x58: "iget-short", 0x59: "iput", 0x5a: "iput-wide",
    0x5b: "iput-object", 0x5c: "iput-boolean", 0x5d: "iput-byte",
    0x5e: "iput-char", 0x5f: "iput-short", 0x60: "sget", 0x61: "sget-wide",
    0x62: "sget-object", 0x63: "sget-boolean", 0x64: "sget-byte",
    0x65: "sget-char", 0x66: "sget-short", 0x67: "sput", 0x68: "sput-wide",
    0x69: "sput-object", 0x6a: "sput-boolean", 0x6b: "sput-byte",
    0x6c: "sput-char", 0x6d: "sput-short", 0x6e: "invoke-virtual",
    0x6f: "invoke-super", 0x70: "invoke-direct", 0x71: "invoke-static",
    0x72: "invoke-interface", 0x74: "invoke-virtual/range",
    0x75: "invoke-super/range", 0x76: "invoke-direct/range",
    0x77: "invoke-static/range", 0x78: "invoke-interface/range",
    0x7b: "neg-int", 0x7c: "not-int", 0x7d: "neg-long", 0x7e: "not-long",
    0x7f: "neg-float", 0x80: "neg-double", 0x81: "int-to-long",
    0x82: "int-to-float", 0x83: "int-to-double", 0x84: "long-to-int",
    0x85: "long-to-float", 0x86: "long-to-double", 0x87: "float-to-int",
    0x88: "float-to-long", 0x89: "float-to-double", 0x8a: "double-to-int",
    0x8b: "double-to-long", 0x8c: "double-to-float", 0x8d: "int-to-byte",
    0x8e: "int-to-char", 0x8f: "int-to-short", 0x90: "add-int",
    0x91: "sub-int", 0x92: "mul-int", 0x93: "div-int", 0x94: "rem-int",
    0x95: "and-int", 0x96: "or-int", 0x97: "xor-int", 0x98: "shl-int",
    0x99: "shr-int", 0x9a: "ushr-int", 0x9b: "add-long", 0x9c: "sub-long",
    0x9d: "mul-long", 0x9e: "div-long", 0x9f: "rem-long", 0xa0: "and-long",
    0xa1: "or-long", 0xa2: "xor-long", 0xa3: "shl-long", 0xa4: "shr-long",
    0xa5: "ushr-long", 0xb0: "add-int/2addr", 0xb1: "sub-int/2addr",
    0xb2: "mul-int/2addr", 0xb3: "div-int/2addr", 0xb4: "rem-int/2addr",
    0xb5: "and-int/2addr", 0xb6: "or-int/2addr", 0xb7: "xor-int/2addr",
    0xb8: "shl-int/2addr", 0xb9: "shr-int/2addr", 0xba: "ushr-int/2addr",
    0xbb: "add-long/2addr", 0xbc: "sub-long/2addr", 0xbd: "mul-long/2addr",
    0xbe: "div-long/2addr", 0xbf: "rem-long/2addr", 0xc0: "and-long/2addr",
    0xc1: "or-long/2addr", 0xc2: "xor-long/2addr", 0xc3: "shl-long/2addr",
    0xc4: "shr-long/2addr", 0xc5: "ushr-long/2addr", 0xd0: "add-int/lit16",
    0xd1: "rsub-int", 0xd2: "mul-int/lit16", 0xd3: "div-int/lit16",
    0xd4: "rem-int/lit16", 0xd5: "and-int/lit16", 0xd6: "or-int/lit16",
    0xd7: "xor-int/lit16", 0xd8: "add-int/lit8", 0xd9: "rsub-int/lit8",
    0xda: "mul-int/lit8", 0xdb: "div-int/lit8", 0xdc: "rem-int/lit8",
    0xdd: "and-int/lit8", 0xde: "or-int/lit8", 0xdf: "xor-int/lit8",
    0xe0: "shl-int/lit8", 0xe1: "shr-int/lit8", 0xe2: "ushr-int/lit8",
}.items():
    OP_NAMES[k] = v


def dump_method(dex, cls, meth_name, grep):
    cd = dex.class_data(cls)
    if cd is None:
        print("NO-CLASS-DATA %s" % cls)
        return
    _, _, dmethods, vmethods = cd
    for tag, ms in (("direct", dmethods), ("virtual", vmethods)):
        for idx, fl, code in ms:
            m = dex.method_id(idx)
            if m is None or m[1] != meth_name:
                continue
            if not code:
                print("%s %s#%s NO-CODE" % (tag, cls, meth_name))
                continue
            regs, ins, outs, tries, insns = dex.insns(code)
            print("=== %s#%s (%s) code=0x%x insns_units=%d tries=%d ===" %
                  (cls, meth_name, tag, code, len(insns) // 2, tries))
            # walk instructions
            u = insns
            n = len(u) // 2
            i = 0
            while i < n:
                off = i  # in code units from insns start
                unit = struct.unpack_from("<H", u, i * 2)[0]
                op = unit & 0xFF
                name = OP_NAMES.get(op, "op-0x%02x" % op)
                aa = (unit >> 8) & 0xFF
                line = None
                if op in (0x6e, 0x6f, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78):
                    # 35c
                    idx_m = struct.unpack_from("<H", u, i * 2 + 2)[0]
                    mi = dex.method_id(idx_m)
                    tgt = "%s->%s" % (mi[0], mi[1]) if mi else "?@%d" % idx_m
                    if op in (0x74, 0x75, 0x76, 0x77, 0x78):
                        # 3rc: AA in first unit = count, next unit = CCCC first reg
                        first = struct.unpack_from("<H", u, i * 2 + 2)[0]
                        regs_str = "{v%d..v%d}" % (first, first + aa - 1)
                    else:
                        a = aa >> 4
                        b = aa & 0xF
                        regs = ["v%d" % ((b + k) & 0xF) for k in range(a + 1)]
                        regs_str = "{%s}" % ",".join(regs)
                    line = "%s %s %s" % (name, tgt, regs_str)
                    i += 3
                elif op in (0x1a,):
                    idx = struct.unpack_from("<H", u, i * 2 + 2)[0]
                    s = dex.strings[idx] if idx < len(dex.strings) else "?"
                    line = "const-string v%d, %r" % (aa, s)
                    i += 2
                elif op in (0x1b,):
                    idx = struct.unpack_from("<I", u, i * 2 + 2)[0]
                    s = dex.strings[idx] if idx < len(dex.strings) else "?"
                    line = "const-string/jumbo v%d, %r" % (aa, s)
                    i += 3
                elif op in (0x1c,):
                    idx = struct.unpack_from("<H", u, i * 2 + 2)[0]
                    line = "const-class v%d, %s" % (aa, dex.type_id(idx))
                    i += 2
                elif op in (0x22,):
                    idx = struct.unpack_from("<H", u, i * 2 + 2)[0]
                    line = "new-instance v%d, %s" % (aa, dex.type_id(idx))
                    i += 2
                elif op in (0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
                            0x59, 0x5a, 0x5b, 0x5c, 0x5d, 0x5e, 0x5f,
                            0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66,
                            0x67, 0x68, 0x69, 0x6a, 0x6b, 0x6c, 0x6d):
                    idx = struct.unpack_from("<H", u, i * 2 + 2)[0]
                    f = dex.field_id(idx)
                    if f:
                        line = "%s v%d, v%d, %s->%s:%s" % (name, aa, (unit >> 12) & 0xF, f[0], f[2], f[1])
                    else:
                        line = "%s v%d, v%d, ?@%d" % (name, aa, (unit >> 12) & 0xF, idx)
                    i += 2
                elif op in (0x12, 0x13, 0x14, 0x15):
                    line = "%s v%d, 0x%x" % (name, aa, (unit >> 8) & 0xFF if op == 0x12 else 0)
                    if op == 0x13:
                        line = "const/16 v%d, %d" % (aa, struct.unpack_from("<h", u, i * 2 + 2)[0])
                    elif op == 0x14:
                        line = "const v%d, 0x%08x" % (aa, struct.unpack_from("<I", u, i * 2 + 2)[0])
                    elif op == 0x15:
                        line = "const/high16 v%d, 0x%x" % (aa, struct.unpack_from("<H", u, i * 2 + 2)[0])
                    i += (1 if op == 0x12 else 2 if op == 0x13 else 3)
                elif op in (0x0e,):
                    line = "return-void"
                    i += 1
                elif op in (0x0f, 0x11):
                    line = "%s v%d" % (name, aa)
                    i += 1
                elif op in (0x10,):
                    line = "return-wide v%d" % aa
                    i += 1
                elif op in (0x28,):
                    line = "goto %+d" % struct.unpack_from("<b", u, i * 2 + 2)[0]
                    i += 1
                elif op in (0x29,):
                    line = "goto/16 %+d" % struct.unpack_from("<h", u, i * 2 + 2)[0]
                    i += 2
                elif op in (0x2a,):
                    line = "goto/32 %+d" % struct.unpack_from("<i", u, i * 2 + 2)[0]
                    i += 3
                elif op in (0x32, 0x33, 0x34, 0x35, 0x36, 0x37):
                    line = "%s v%d, v%d, %+d" % (name, aa, (unit >> 12) & 0xF, struct.unpack_from("<h", u, i * 2 + 2)[0])
                    i += 2
                elif op in (0x38, 0x39, 0x3a, 0x3b, 0x3c, 0x3d):
                    line = "%s v%d, %+d" % (name, aa, struct.unpack_from("<h", u, i * 2 + 2)[0])
                    i += 2
                else:
                    # unknown length: can't reliably advance; bail to avoid desync
                    i += 1
                if line and (not grep or grep in line):
                    print("   0x%04x  %s" % (off, line))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--class", dest="cls", required=True)
    ap.add_argument("--method", dest="meth", required=True)
    ap.add_argument("--grep", dest="grep", default=None)
    args = ap.parse_args()
    cls = args.cls if args.cls.startswith("L") else "L" + args.cls.replace(".", "/") + ";"
    found = False
    for p in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        d = Dex(p)
        if cls in d.classes:
            print("## defined in %s" % os.path.basename(p))
            dump_method(d, cls, args.meth, args.grep)
            found = True
    if not found:
        print("NOT-DEFINED %s" % cls)


if __name__ == "__main__":
    main()
