#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""R28-转发取证：**谁引用了某个类** —— 解析 code_item 里的类型/方法引用，反查归属类+方法。

为什么不 grep 反汇编：多数 dex 没有现成的全量 dis（跑一次 dexdump 几分钟、几百 MB）。
这里直接解析 dex 二进制，只把「引用了目标类的指令」所在的 method/类打出来。

覆盖的指令（够用了，不做全指令翻译，避免误判）：
  - 0x22 new-instance / 0x1c const-class / 0x1f check-cast / 0x20 instance-of
    （21c/22c 格式：op,AA  BBBB → BBBB 是 type_idx）
  - invoke-* (35c: 6e/6f/70/71/72/73/74/75/76/77/78) 用 method_idx 反查**声明类**是否为目标，
    并额外打印被调用的方法名（用于「定位 xxx 方法定义」）

⚠️ 由于只扫上述 opcode 的定长/半定长格式，不做全指令流解码，理论上可能有极少量漏判；
   用 portion control：结果以 "xxx.jar 的file" 为单位给候选，实际结论仍需用 dexdump 复核。

用法：
    python tools/_r28fw_whoref.py 'Lcom/tencent/mm/autogen/events/ExtNetSceneSendMsgEvent;'
"""
import glob
import io
import os
import struct
import sys

DEXDIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "_cache", "dex")

# 需要读 type_idx 的 opcode（16-bit unit: op(8) | AA(8) | BBBB(16)）
TYPE_OPS = {0x22: "new-instance", 0x1c: "const-class",
            0x1f: "check-cast", 0x20: "instance-of"}
# 35c invoke-*: op(8) | G(4)|F(4) | method_idx(16) | regs(16)
INVOKE_OPS = {0x6e: "invoke-virtual", 0x6f: "invoke-super", 0x70: "invoke-direct",
              0x71: "invoke-static", 0x72: "invoke-interface",
              0x74: "invoke-virtual/range", 0x75: "invoke-super/range",
              0x76: "invoke-direct/range", 0x77: "invoke-static/range",
              0x78: "invoke-interface/range"}
# 对 weired 的 field 暂不处理


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
        self.b = io.open(path, "rb").read()
        (self.strsz, self.stroff, self.tysz, self.tyoff,
         self.prosz, self.prooff, self.fldsz, self.fldoff,
         self.mthsz, self.mthoff, self.cdsz, self.cdoff) = struct.unpack_from("<12I", self.b, 0x38)
        self.strings = []
        for k in range(self.strsz):
            off, = struct.unpack_from("<I", self.b, self.stroff + 4 * k)
            _, p = uleb(self.b, off)
            e = p
            while self.b[e] != 0:
                e += 1
            self.strings.append(self.b[p:e].decode("utf-8", "replace"))
        self.types = [self.strings[struct.unpack_from("<I", self.b, self.tyoff + 4 * k)[0]]
                      for k in range(self.tysz)]

    def _proto(self, pi):
        b = self.b
        _s, ret_idx, params_off = struct.unpack_from("<III", b, self.prooff + 12 * pi)
        ret = self.types[ret_idx] if ret_idx < len(self.types) else "?"
        params = []
        if params_off:
            n, = struct.unpack_from("<I", b, params_off)
            for i in range(n):
                t, = struct.unpack_from("<H", b, params_off + 4 + 2 * i)
                params.append(self.types[t] if t < len(self.types) else "?")
        return "(" + "".join(params) + ")" + ret

    def method_info(self, midx):
        moff = self.mthoff + 8 * midx
        cls_idx, pi, name_idx = struct.unpack_from("<HHI", self.b, moff)
        return (self.types[cls_idx], self.strings[name_idx], self._proto(pi))

    def code_map(self):
        """[(code_off, class_name, method_name, proto, insns_off, insns_size)]"""
        b = self.b
        out = []
        for i in range(self.cdsz):
            base = self.cdoff + 32 * i
            class_idx, _a, _s = struct.unpack_from("<III", b, base)
            cname = self.types[class_idx]
            cdata, = struct.unpack_from("<I", b, base + 24)
            if cdata == 0:
                continue
            p = cdata
            sf, p = uleb(b, p)
            inf, p = uleb(b, p)
            dm, p = uleb(b, p)
            vm, p = uleb(b, p)
            for _ in range(sf + inf):
                _, p = uleb(b, p)
                _, p = uleb(b, p)
            for cnt in (dm, vm):
                idx = 0
                for _ in range(cnt):
                    diff, p = uleb(b, p)
                    acc, p = uleb(b, p)
                    code, p = uleb(b, p)
                    idx += diff
                    if code == 0 or idx >= self.mthsz:
                        continue
                    moff = self.mthoff + 8 * idx
                    cls_i, pi, name_idx = struct.unpack_from("<HHI", b, moff)
                    # code_item: registers(2) ins(2) outs(2) tries(2) debug(4) insns_size(4) insns
                    insns = code + 16
                    size, = struct.unpack_from("<I", b, code + 12)
                    out.append((insns, size, cname, self.strings[name_idx],
                                self._proto(pi), acc))
        return out

    def find_refs(self, target_idx, hint=None):
        b = self.b
        hits = []
        for insns, size, cname, mname, proto, acc in self.code_map():
            n = size
            off = insns
            end = insns + 2 * n
            while off < end:
                low, = struct.unpack_from("<H", b, off)
                op = low & 0xFF
                if op in TYPE_OPS:
                    idx, = struct.unpack_from("<H", b, off + 2)
                    if idx == target_idx:
                        hits.append((cname, mname, proto, TYPE_OPS[op], off, hint))
                elif op in INVOKE_OPS:
                    idx, = struct.unpack_from("<H", b, off + 2)
                    if idx < self.mthsz:
                        dc, dn, dp = self.method_info(idx)
                        if dc == self.types[target_idx]:
                            hits.append((cname, mname, proto,
                                         "%s->%s%s" % (INVOKE_OPS[op], dn, dp), off, hint))
                elif op == 0x00:  # nop / packed switch payload 会被跳过即可
                    pass
                # 定长步进：绝大多数指令是 2/4/6/8 字节。为了不误判，统一按 2 字节扫：
                # 这可能把 payload 中的随机数据读成指令，但 TYPE/INVOKE 的 idx 精确匹配目标
                # type_idx（通常很大且唯一），误报概率极低；复核时再抽原文即可。
                off += 2
        return hits


def main():
    want = sys.argv[1]
    if len(sys.argv) > 2 and sys.argv[2] == "--all-dex":
        files = sorted(glob.glob(os.path.join(DEXDIR, "*.dex")))
    else:
        files = sorted(glob.glob(os.path.join(DEXDIR, "*.dex")))
    if not want.startswith("L"):
        want = "L" + want.replace(".", "/") + ";"
    total = 0
    for path in files:
        d = Dex(path)
        if want not in d.types:
            continue
        tidx = d.types.index(want)
        hits = d.find_refs(tidx)
        if hits:
            print("=== %s  (type_idx=%d, %d hits)" % (os.path.basename(path), tidx, len(hits)))
            seen = set()
            for cname, mname, proto, kind, off, _ in hits:
                key = (cname, mname, kind)
                if key in seen:
                    continue
                seen.add(key)
                print("   %s  \\\n      %s->%s%s   @0x%x" % (kind, cname, mname, proto, off))
            total += len(seen)
    if total == 0:
        print("NO code reference found for %s" % want)


if __name__ == "__main__":
    main()
