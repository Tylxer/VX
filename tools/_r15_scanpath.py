# -*- coding: utf-8 -*-
"""在所有 dex 里搜 getMediaFilePath / SnsPathHelper 的实现，看是否有多个版本，
以及各自的 charAt 常参是多少。"""
import sys, os, re
_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(_HERE), '_cache'))
from _r14_walk import Dex, OPS, DEXDIR, norm, regs

TARGETS = ['getMediaFilePath', 'getMediaDownloadPath']


def regs35c(hi8, u2):
    a = hi8 >> 4
    g = hi8 & 0xF
    nibs = [(u2 >> 0) & 0xF, (u2 >> 4) & 0xF, (u2 >> 8) & 0xF, (u2 >> 12) & 0xF]
    out = []
    for i in range(a):
        out.append("v%d" % (nibs[i] if i < 4 else g))
    return "{" + ",".join(out) + "}"


def scan():
    dexes = sorted(f for f in os.listdir(DEXDIR) if f.endswith('.dex'))
    print('dex 数:', len(dexes))
    hits = []
    for dn in dexes:
        try:
            d = Dex(os.path.join(DEXDIR, dn))
        except Exception as e:
            print('  !! %s 打不开 %s' % (dn, e))
            continue
        try:
            for cls, mi, code_off, acc in d.each_method():
                if code_off == 0:
                    continue
                try:
                    # 找方法体里的 const-string 是否等于目标
                    has = False
                    insns = []
                    for i, op, u1, u2, u4, w0hi in d.each_insn(code_off):
                        insns.append((i, op, u1, u2, u4, w0hi))
                        if op in (0x1B, 0x1A):
                            s = d.string(u4 if op == 0x1B else u1)
                            if s in TARGETS:
                                has = True
                    if has:
                        hits.append((dn, cls, d.mname(mi), d.msig(mi), insns, d, code_off))
                except Exception:
                    pass
        finally:
            pass
    print('命中方法数:', len(hits))
    for dn, cls, mn, sig, insns, d, code_off in hits:
        print('\n=== %s  %s->%s  %s' % (dn, cls, mn, sig))
        for i, op, u1, u2, u4, w0hi in insns:
            nm = OPS.get(op, 'op%02x' % op)
            extra = ''
            if op in (0x1A,):
                extra = repr(d.string(u1))
            elif op in (0x1B,):
                extra = repr(d.string(u4))
            elif op in (0x12,):
                extra = '#%d' % (u1 >> 12)
            elif op in (0x13, 0x16):
                extra = '#%d' % (u1 if u1 < 32768 else u1 - 65536)
            elif 0x6E <= op <= 0x72:
                extra = regs35c(w0hi, u2)
            if 'charAt' in str(extra) or 'charAt' in nm:
                pass
            print('  [%4d] %-20s %-10s %s' % (i, nm, regs(op, w0hi, u1, u4), extra))
    return 0


if __name__ == '__main__':
    sys.exit(scan())
