#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R4 静态取证：定位「哪个类的哪个方法引用了某个字符串常量」。

为什么不用 dexdump：整个 dex 反汇编一次要几分钟、输出几百 MB；这里只做
二进制最小解析（string_ids + class_defs + code_item 里的 const-string 指令），
几秒出结果。

用法：
    python _r4_dexref.py classes4.dex patted
    python _r4_dexref.py classes7.dex 拍一拍
"""
import os
import struct
import sys

DEXDIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                      "_cache", "dex")


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
        self.type_names = [self.strings[struct.unpack_from("<I", h, self.type_off + 4 * i)[0]]
                           for i in range(self.type_size)]
        self.methods = self._methods()

    # ---------------- uleb128
    def _uleb(self, off):
        r = 0
        s = 0
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
        """method_ids: class_idx(ushort) proto_idx(ushort) name_idx(uint)"""
        out = []
        for i in range(self.meth_size):
            c, p, n = struct.unpack_from("<HHI", self.d, self.meth_off + 8 * i)
            out.append((self.type_names[c] if c < len(self.type_names) else "?",
                        self.strings[n] if n < len(self.strings) else "?", p))
        return out

    def _proto(self, pi):
        """proto_ids: shorty_idx(4) return_type_idx(4) parameters_off(4)"""
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
        """方法签名：Lcls;->name(args)Ret"""
        cls, name, pi = self.methods[mi]
        args, ret = self._proto(pi)
        return "%s->%s(%s)%s" % (cls, name, "".join(args), ret)

    def invokes(self, targets):
        """扫所有 code_item，找 invoke-* 引用 targets(method_idx) 的调用点"""
        out = []
        for ci in range(self.cls_size):
            base = self.cls_off + 32 * ci
            cls_idx = struct.unpack_from("<I", self.d, base)[0]
            data_off = struct.unpack_from("<I", self.d, base + 24)[0]
            if data_off == 0:
                continue
            cls_name = self.type_names[cls_idx] if cls_idx < len(self.type_names) else "?"
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
                    found = self._code_invoke(code_off, targets)
                    for t in found:
                        out.append((cls_name, self.sig(prev)))
        return out

    def _code_invoke(self, code_off, targets):
        d = self.d
        try:
            insns_size = struct.unpack_from("<I", d, code_off + 12)[0]
        except struct.error:
            return []
        p = code_off + 16
        if p + insns_size * 2 > len(d):
            return []
        out = []
        for i in range(insns_size):
            lo = d[p + 2 * i]
            # 35c: invoke-virtual/super/direct/static/interface, invoke-custom, polymorphic
            # 3rc: invoke-*/range
            if (0x6E <= lo <= 0x72) or lo in (0xFA, 0xFC) or \
               (0x74 <= lo <= 0x78) or lo in (0xFB, 0xFD):
                if i + 1 < insns_size:
                    v = struct.unpack_from("<H", d, p + 2 * i + 2)[0]
                    if v in targets:
                        out.append(v)
        return out

    def find_string(self, key, exact=True):
        return [i for i, s in enumerate(self.strings) if (key == s if exact else key in s)]

    def refs(self, str_idx):
        return self.refs_multi({str_idx}).get(str_idx, [])

    def refs_multi(self, targets):
        """一次遍历找多个 string_id 的引用（避免每个关键词全量扫一遍 dex）"""
        hits = {t: [] for t in targets}
        for ci in range(self.cls_size):
            base = self.cls_off + 32 * ci
            cls_idx = struct.unpack_from("<I", self.d, base)[0]
            data_off = struct.unpack_from("<I", self.d, base + 24)[0]
            if data_off == 0:
                continue
            cls_name = self.type_names[cls_idx] if cls_idx < len(self.type_names) else "?"
            off = data_off
            sf, off = self._uleb(off)
            inf, off = self._uleb(off)
            dm, off = self._uleb(off)
            vm, off = self._uleb(off)
            # 跳过 fields
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
                    found = self._code_find(code_off, targets)
                    for t in found:
                        mcls, mname = self.methods[prev] if prev < len(self.methods) else ("?", "?")
                        hits[t].append((cls_name, mcls, mname))
        return hits

    def _code_find(self, code_off, targets):
        d = self.d
        try:
            insns_size = struct.unpack_from("<I", d, code_off + 12)[0]
        except struct.error:
            return []
        p = code_off + 16
        if p + insns_size * 2 > len(d):
            return []
        out = []
        for i in range(insns_size):
            lo = d[p + 2 * i]
            if lo == 0x1A and i + 1 < insns_size:
                v = struct.unpack_from("<H", d, p + 2 * i + 2)[0]
                if v in targets:
                    out.append(v)
            elif lo == 0x1B and i + 2 < insns_size:
                v = struct.unpack_from("<I", d, p + 2 * i + 2)[0]
                if v in targets:
                    out.append(v)
        return out


def cmd_methods(dex, clsdesc, verbose=False):
    """列出类的所有方法签名（含参数/返回类型）"""
    hit = [i for i, t in enumerate(dex.type_names) if t == clsdesc]
    if not hit:
        print("类不存在:", clsdesc)
        return
    print("=== %s ===" % clsdesc)
    for mi, (mcls, mname, _p) in enumerate(dex.methods):
        if mcls != clsdesc:
            continue
        print("   %s" % dex.sig(mi))


def cmd_callers(dex, target_sig_prefix):
    """扫所有 code_item，找 invoke-* 引用某方法的调用点"""
    targets = set()
    for mi in range(dex.meth_size):
        s = dex.sig(mi)
        if target_sig_prefix in s:
            targets.add(mi)
    if not targets:
        print("没找到方法:", target_sig_prefix)
        return
    print("目标 method_idx:", sorted(targets))
    for mi in sorted(targets):
        print("   ", dex.sig(mi))
    callers = dex.invokes(targets)
    seen = set()
    print("\n=== 调用点（%d 处）===" % len(callers))
    for cls, sig in callers:
        k = (cls, sig)
        if k in seen:
            continue
        seen.add(k)
        print("   %s\n       -> %s" % (cls, sig))


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "refs"
    if cmd == "methods":
        dex = Dex(os.path.join(DEXDIR, sys.argv[2]))
        for c in sys.argv[3:]:
            cmd_methods(dex, c if c.startswith("L") else "L" + c.replace(".", "/") + ";")
        return
    if cmd == "callers":
        dex = Dex(os.path.join(DEXDIR, sys.argv[2]))
        cmd_callers(dex, sys.argv[3])
        return
    name = cmd if len(sys.argv) > 1 else "classes4.dex"
    if len(sys.argv) > 1:
        name = sys.argv[1]
    key = sys.argv[2] if len(sys.argv) > 2 else "patted"
    path = os.path.join(DEXDIR, name)
    if not os.path.exists(path):
        print("缺 dex:", path)
        return
    dex = Dex(path)
    idxs = dex.find_string(key, exact=False)
    print("字符串池 %d 条；命中 %r -> %d 个 string_id: %s" % (len(dex.strings), key, len(idxs), idxs[:20]))
    allrefs = dex.refs_multi(set(idxs[:20]))
    for si in idxs[:20]:
        print("\n--- string[%d] = %r" % (si, dex.strings[si][:120]))
        refs = allrefs.get(si, [])
        if not refs:
            print("    (无 const-string 直接引用)")
        seen = set()
        for cls, mcls, mname in refs:
            k = (cls, mname)
            if k in seen:
                continue
            seen.add(k)
            print("    %s  ->  %s#%s" % (cls, mcls, mname))
        if len(seen) > 30:
            print("    ...(截断)")
    return
    key = sys.argv[2] if len(sys.argv) > 2 else "patted"
    path = os.path.join(DEXDIR, name)
    if not os.path.exists(path):
        print("缺 dex:", path)
        return
    dex = Dex(path)
    idxs = dex.find_string(key, exact=False)
    print("字符串池 %d 条；命中 %r -> %d 个 string_id: %s" % (len(dex.strings), key, len(idxs), idxs[:20]))
    allrefs = dex.refs_multi(set(idxs[:20]))
    for si in idxs[:20]:
        print("\n--- string[%d] = %r" % (si, dex.strings[si][:120]))
        refs = allrefs.get(si, [])
        if not refs:
            print("    (无 const-string 直接引用)")
        seen = set()
        for cls, mcls, mname in refs:
            k = (cls, mname)
            if k in seen:
                continue
            seen.add(k)
            print("    %s  ->  %s#%s" % (cls, mcls, mname))
        if len(seen) > 30:
            print("    ...(截断)")


if __name__ == "__main__":
    main()
