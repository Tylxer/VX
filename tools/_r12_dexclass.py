r"""静态读取 dex：找类的方法表 + 方法体指令，用于核实「某方法有没有副作用」。

⚠️ 为什么不用 dexdump：classes7.dex 全量反汇编 = 233MB / 281 万行，
   读不动、也搜不动。这里只解析需要的类，输出 cc 级摘要。
⚠️ method_ids 的 method_idx 高位可能是 proto_idx 越界（特殊 method handle）——
   一律加边界检查，宁可少输出也不要崩。

用法：
    python tools/_r12_dexclass.py --class Lcom/tencent/mm/plugin/sns/ui/dk; --method a
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
        """method_idx -> (class, name, proto_idx)，带边界检查"""
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

    def class_data(self, cls):
        """返回 (static_fields, instance_fields, direct_methods, virtual_methods)；
        每项是 [(idx, flags, code_off)]"""
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
                if not (fl & 0x100):  # ACC_NATIVE = 0x100
                    code, pos = uleb(b, pos)
                out.append((idx, fl, code))
            return out, pos

        sfields, pos = read_fields(sf, pos)
        ifields, pos = read_fields(inf, pos)
        dmethods, pos = read_methods(dm, pos)
        vmethods, pos = read_methods(vm, pos)
        return sfields, ifields, dmethods, vmethods

    def code_insns(self, code_off):
        """返回 (registers, ins, outs, insns_bytes, tries_bytes)"""
        b = self.b
        regs, ins, outs, tries = struct.unpack_from("<4H", b, code_off)
        insns_size, = struct.unpack_from("<I", b, code_off + 12)
        start = code_off + 16
        insns = b[start:start + insns_size * 2]
        return regs, ins, outs, tries, insns

    def code_strings(self, code_off):
        """粗粒度提取 code_item 里引用的字符串常量（const-string / const-string/jumbo）"""
        regs, ins, outs, tries, insns = self.code_insns(code_off)
        out = []
        for k in range(0, len(insns) - 1, 2):
            unit, = struct.unpack_from("<H", insns, k)
            op = unit & 0xFF
            if op in (0x1A, 0x1B):  # const-string / const-string/jumbo
                # 取紧随其后的 16 位索引（jumbo 是 32 位，这里只取低 16 位近似）
                if k + 3 < len(insns):
                    idx, = struct.unpack_from("<H", insns, k + 2)
                    if idx < len(self.strings):
                        out.append(self.strings[idx])
        return out

    def code_invoke_methods(self, code_off):
        """粗粒度提取被调用的 method（invoke-*，35c/3rc）"""
        regs, ins, outs, tries, insns = self.code_insns(code_off)
        out = []
        for k in range(0, len(insns) - 1, 2):
            unit, = struct.unpack_from("<H", insns, k)
            op = unit & 0xFF
            if op in (0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78):
                if k + 3 < len(insns):
                    idx, = struct.unpack_from("<H", insns, k + 2)
                    m = self.method_id(idx)
                    if m:
                        out.append(m)
        return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--class", dest="cls", required=True)
    ap.add_argument("--method", dest="meth", default=None)
    args = ap.parse_args()

    hits = []
    for p in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        d = Dex(p)
        if args.cls in d.classes:   # 只认 class_defs（真正定义），不是 type_ids 引用
            hits.append(d)

    if not hits:
        print("NOT-DEFINED %s" % args.cls)
        return

    for d in hits:
        print("=== %s  定义于 %s" % (args.cls, os.path.basename(d.path)))
        cd = d.class_data(args.cls)
        if cd is None:
            print("  （无 class_data）")
            continue
        sfields, ifields, dmethods, vmethods = cd
        print("  static_fields=%d instance_fields=%d direct_methods=%d virtual_methods=%d"
              % (len(sfields), len(ifields), len(dmethods), len(vmethods)))

        print("  -- 静态字段 --")
        for idx, fl in sfields:
            f = d.field_id(idx)
            if f:
                print("     %-8s %-42s %s" % (hex(fl), f[1], f[2]))
            else:
                print("     %-8s <bad idx %d>" % (hex(fl), idx))
        print("  -- 实例字段 --")
        for idx, fl in ifields:
            f = d.field_id(idx)
            if f:
                print("     %-8s %-42s %s" % (hex(fl), f[1], f[2]))
            else:
                print("     %-8s <bad idx %d>" % (hex(fl), idx))

        for tag, ms in (("direct", dmethods), ("virtual", vmethods)):
            print("  -- %s 方法 --" % tag)
            for idx, fl, code in ms:
                m = d.method_id(idx)
                name = m[1] if m else str(idx)
                if args.meth and args.meth != name:
                    continue
                codestr = "code=0x%x" % code if code else "NO-CODE"
                print("     %-10s %s (%s)" % (hex(fl), name, codestr))
                if code and args.meth:
                    strs = d.code_strings(code)
                    invs = d.code_invoke_methods(code)
                    regs, ins, outs, tries, insns = d.code_insns(code)
                    print("        registers=%d ins=%d outs=%d tries=%d insns_units=%d"
                          % (regs, ins, outs, tries, len(insns) // 2))
                    if strs:
                        print("        const-string: %s" % strs)
                    if invs:
                        print("        invokes:")
                        for c, n, _p in invs:
                            print("           %s->%s" % (c, n))


if __name__ == "__main__":
    main()
