r"""R28 头像取证 步骤4：字符串常量 → 哪些类的哪些方法引用了它（const-string 反查）。

用于把 log tag / 路径模板锚到真实类：
    "MicroMsg.AvatarStorage"  ⇒ 真正的 AvatarStorage 类
    "/tencent/MicroMsg/avatar/" ⇒ 拼头像路径的类
    "getAvatarPath -> path: " ⇒ 取路径方法所在类

用法：
    python tools/_r28_strref.py "MicroMsg.AvatarStorage"
    python tools/_r28_strref.py "avatar/" "/tencent/MicroMsg/avatar"
"""
import glob
import io
import os
import re
import struct
import sys

DEXDIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "_cache", "dex")
_OP_RE = re.compile(rb"[\x1a\x1b]")


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
        ret_idx, = struct.unpack_from("<I", b, self.prooff + 4 + 12 * pi)
        ret = self.types[ret_idx] if ret_idx < len(self.types) else "?"
        params_off, = struct.unpack_from("<I", b, self.prooff + 8 + 12 * pi)
        params = []
        if params_off:
            n, = struct.unpack_from("<I", b, params_off)
            for i in range(n):
                t, = struct.unpack_from("<H", b, params_off + 4 + 2 * i)
                params.append(self.types[t] if t < len(self.types) else "?")
        return "(" + "".join(params) + ")" + ret

    def iter_methods(self):
        """yield (class_name, def_index, method_name, desc, access, kind, code_off)"""
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
            # ⚠️ 踩过的坑：`encoded_method` 的 code_off **只在非 abstract/native 时存在**，
            #    一旦某个类的 class_data 解析错位（本批 dex 里 `DynamicBackgroundNative`
            #    这类含多个 native 重载的类就会错位），后续 method_idx 会一直累加到同一个
            #    方法上，症状是「同一个 (J)V 出现 8 次、code_off=0x111 这种未对齐的垃圾值」。
            #    ⇒ 这里对 code_off 做**合法性校验**，不合法的当成无代码，避免把垃圾区当指令扫。
            flen = len(b)
            for kind, cnt in (("direct", dm), ("virtual", vm)):
                idx = 0
                for _ in range(cnt):
                    diff, p = uleb(b, p)
                    acc, p = uleb(b, p)
                    if acc & 0x100:      # ACC_NATIVE
                        code = 0
                    else:
                        code, p = uleb(b, p)
                    idx += diff
                    if idx >= self.mthsz or code and not self._code_ok(code, flen):
                        if idx >= self.mthsz:
                            continue
                        code = 0
                    moff = self.mthoff + 8 * idx
                    _c, proto_idx, name_idx = struct.unpack_from("<HHI", b, moff)
                    if name_idx >= self.strsz:
                        continue
                    yield (cname, i, self.strings[name_idx],
                           self._proto(proto_idx), acc, kind, code)

    def _code_ok(self, code_off, flen):
        """code_item 合法性：4 字节对齐 + 头部在文件内 + insns_size 有界且区间在文件内"""
        if code_off & 3 or code_off + 16 > flen:
            return False
        b = self.b
        _regs, _ins, _outs, _tries = struct.unpack_from("<4H", b, code_off)
        insns_size, = struct.unpack_from("<I", b, code_off + 12)
        if insns_size == 0 or insns_size > 0xFFFF:
            return False
        return code_off + 16 + insns_size * 2 <= flen

    def code_const_strings(self, code_off):
        """提取 code_item 里的 const-string / const-string/jumbo 字符串索引

        ⚠️ 全量扫描 15 个 dex 的 code 区是千万级循环，逐 2 字节 struct.unpack_from
        会把分钟级变成十分钟级。这里改走 **C 级 bytes 正则** 找 opcode 字节，
        再按对齐（opcode 一定在偶数偏移）过滤，最后用 int.from_bytes 读操作数。
        """
        b = self.b
        if not code_off:
            return []
        insns_size, = struct.unpack_from("<I", b, code_off + 12)
        start = code_off + 16
        insns = b[start:start + insns_size * 2]
        out = []
        n = len(insns)
        for m in _OP_RE.finditer(insns):
            k = m.start()
            if k & 1:                           # 未对齐 → 是数据不是 opcode
                continue
            if insns[k] == 0x1A:                # const-string，21c，2 code units
                if k + 4 <= n:
                    out.append(int.from_bytes(insns[k + 2:k + 4], "little"))
            else:                               # const-string/jumbo，31c，3 code units
                if k + 6 <= n:
                    out.append(int.from_bytes(insns[k + 2:k + 6], "little"))
        return out


def main():
    args = sys.argv[1:]
    dexfilter = None
    clsfilter = None
    if args and args[0].startswith("--dex="):
        dexfilter = args[0][6:]
        args = args[1:]
    if args and args[0].startswith("--cls="):
        clsfilter = args[0][6:]
        args = args[1:]
    pats = args
    if not pats:
        raise SystemExit(__doc__ + "\n\n可选：--dex=classes7  --cls=modelavatar（类名子串）")
    for path in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        if dexfilter and dexfilter not in os.path.basename(path):
            continue
        d = Dex(path)
        hits = []
        for cn, _di, mn, desc, acc, kind, code in d.iter_methods():
            if not code:
                continue
            if clsfilter and clsfilter not in cn:
                continue
            for si in d.code_const_strings(code):
                if si >= len(d.strings):
                    continue
                s = d.strings[si]
                if any(p in s for p in pats):
                    hits.append((cn, mn, desc, acc, kind, s))
        if hits:
            print("=== %s  (%d)" % (os.path.basename(path), len(hits)))
            for cn, mn, desc, acc, kind, s in hits:
                st = "static" if acc & 0x0008 else "inst  "
                print("   %s %-7s %s->%s%s" % (st, kind, cn, mn, desc))
                print("        str=%r" % s)


if __name__ == "__main__":
    main()
