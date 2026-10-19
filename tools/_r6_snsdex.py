#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R6 静态取证：朋友圈评论相关常量（离线，不碰设备）。

目标（对应 SnsCommentLogic.DELETED_FLAG_MASK / attrBuf 字段号）：
  1. SnsComment 表 DDL          → 列集合（isSilence / commentflag 是否真存在）
  2. SnsObject / SnsComment protobuf 类的 f=字段号常量 → attrBuf 顶层字段号
  3. 引用 commentflag / isSilence 的读写代码 → 位语义候选

用法：
    python tools/_r6_snsdex.py types  SnsComment      # 类型名正则
    python tools/_r6_snsdex.py strs   commentflag     # 字符串池正则
    python tools/_r6_snsdex.py refs   commentflag     # 谁 const-string 引用了它
    python tools/_r6_snsdex.py fields Lxxx;           # 某类的 static final int 常量 + 初值
    python tools/_r6_snsdex.py methods Lxxx;          # 某类方法签名
"""
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEXDIR = os.path.join(ROOT, "_cache", "dex")


def dex_files():
    outs = []
    for n in os.listdir(DEXDIR):
        if n.endswith(".dex"):
            outs.append(n)
    # classes.dex, classes2.dex, ... 自然序
    def key(n):
        m = re.search(r"classes(\d*)\.dex", n)
        k = m.group(1)
        return (1, 0) if k == "" else (0, int(k))
    return sorted(outs, key=key)


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
            out.append((
                self.type_names[c] if c < len(self.type_names) else "?",
                self.strings[n] if n < len(self.strings) else "?",
                p,
            ))
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

    # ---------------- 字段
    def field_ids(self):
        """field_ids: class_idx(ushort) type_idx(ushort) name_idx(uint)"""
        out = []
        for i in range(self.field_size):
            c, t, n = struct.unpack_from("<HHI", self.d, self.field_off + 8 * i)
            out.append((
                self.type_names[c] if c < len(self.type_names) else "?",
                self.type_names[t] if t < len(self.type_names) else "?",
                self.strings[n] if n < len(self.strings) else "?",
            ))
        return out

    def class_defs(self):
        """(cls_desc, class_data_off)"""
        out = []
        for ci in range(self.cls_size):
            base = self.cls_off + 32 * ci
            cls_idx = struct.unpack_from("<I", self.d, base)[0]
            data_off = struct.unpack_from("<I", self.d, base + 24)[0]
            desc = self.type_names[cls_idx] if cls_idx < len(self.type_names) else "?"
            out.append((desc, data_off))
        return out

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

    def _each_method(self):
        """遍历 (cls_desc, method_idx, code_off)"""
        for cls_desc, data_off in self.class_defs():
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
                    if code_off:
                        yield cls_desc, prev, code_off

    def refs_multi(self, targets):
        hits = {t: [] for t in targets}
        for cls_desc, mi, code_off in self._each_method():
            found = self._code_find(code_off, targets)
            if not found:
                continue
            mcls, mname = self.methods[mi][0], self.methods[mi][1]
            for t in found:
                hits[t].append((cls_desc, mcls, mname))
        return hits


# ------------------------------------------------------------------ 子命令

def cmd_types(pat):
    rx = re.compile(pat)
    for n in dex_files():
        try:
            dex = Dex(os.path.join(DEXDIR, n))
        except Exception as e:
            print("  ! %s %s" % (n, e))
            continue
        hits = [t for t in dex.type_names if rx.search(t)]
        if hits:
            print("\n=== %s (%d) ===" % (n, len(hits)))
            for t in sorted(set(hits))[:120]:
                print("   ", t)
            if len(set(hits)) > 120:
                print("    ...(截断)")
        del dex


def cmd_strs(pat):
    rx = re.compile(pat)
    for n in dex_files():
        try:
            dex = Dex(os.path.join(DEXDIR, n))
        except Exception as e:
            print("  ! %s %s" % (n, e))
            continue
        hits = [(i, s) for i, s in enumerate(dex.strings) if rx.search(s)]
        if hits:
            print("\n=== %s (%d) ===" % (n, len(hits)))
            for i, s in hits[:60]:
                print("   [%d] %r" % (i, s[:200]))
            if len(hits) > 60:
                print("    ...(截断)")
        del dex


def cmd_refs(key):
    total = 0
    for n in dex_files():
        try:
            dex = Dex(os.path.join(DEXDIR, n))
        except Exception as e:
            print("  ! %s %s" % (n, e))
            continue
        idxs = [i for i, s in enumerate(dex.strings) if s == key]
        if not idxs:
            del dex
            continue
        hits = dex.refs_multi(set(idxs))
        for si in idxs:
            refs = hits.get(si, [])
            if not refs:
                continue
            print("\n=== %s string[%d]=%r  引用 %d 处 ===" % (n, si, key, len(refs)))
            seen = set()
            for cls, mcls, mname in refs:
                k = (cls, mname)
                if k in seen:
                    continue
                seen.add(k)
                print("   %s\n       -> %s#%s" % (cls, mcls, mname))
                total += 1
                if total > 200:
                    print("   ...(截断)")
                    return
        del dex


def cmd_fields(clsdesc):
    """列出某类的所有字段 + 若为 <clinit> 里赋的 int 常量则打印可能的初值"""
    if not clsdesc.startswith("L"):
        clsdesc = "L" + clsdesc.replace(".", "/") + ";"
    for n in dex_files():
        dex = Dex(os.path.join(DEXDIR, n))
        if clsdesc not in dex.type_names:
            del dex
            continue
        fids = dex.field_ids()
        mine = [(i, f) for i, f in enumerate(fids) if f[0] == clsdesc]
        if not mine:
            del dex
            continue
        print("\n=== %s :: %s 字段 %d 个 ===" % (n, clsdesc, len(mine)))
        for i, (c, t, nm) in mine:
            print("   [%d] %-40s %s" % (i, nm, t))
        # 类定义里的 static values 初值
        for desc, data_off in dex.class_defs():
            if desc != clsdesc or data_off == 0:
                continue
            off = data_off
            sf, off = dex._uleb(off)
            inf, off = dex._uleb(off)
            dm, off = dex._uleb(off)
            vm, off = dex._uleb(off)
            sfi = 0
            for _ in range(sf + inf):
                diff, off = dex._uleb(off)
                sfi += diff
                acc, off = dex._uleb(off)
                yield_note = None
                if acc & 0x8 and acc & 0x10 and sfi < len(fids):
                    print("   static-final: [%d] %s" % (sfi, fids[sfi][2]))
            break
        del dex


def cmd_methods(clsdesc):
    if not clsdesc.startswith("L"):
        clsdesc = "L" + clsdesc.replace(".", "/") + ";"
    for n in dex_files():
        dex = Dex(os.path.join(DEXDIR, n))
        if clsdesc not in dex.type_names:
            del dex
            continue
        print("\n=== %s :: %s ===" % (n, clsdesc))
        k = 0
        for mi, (mcls, mname, _p) in enumerate(dex.methods):
            if mcls != clsdesc:
                continue
            print("   ", dex.sig(mi))
            k += 1
            if k > 300:
                print("    ...(截断)")
                break
        del dex


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return
    cmd, arg = sys.argv[1], sys.argv[2]
    if cmd == "types":
        cmd_types(arg)
    elif cmd == "strs":
        cmd_strs(arg)
    elif cmd == "refs":
        cmd_refs(arg)
    elif cmd == "fields":
        cmd_fields(arg)
    elif cmd == "methods":
        cmd_methods(arg)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
