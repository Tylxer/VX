# -*- coding: utf-8 -*-
"""R86c: 反向追踪 B6 方法中指定 v 寄存器的来源（move/const/iget/invoke-result）。
用法： python _r86c_reg.py <类子串> <方法名> <v寄存器...>
"""
import importlib.util
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


adnd = load("adnd", os.path.join(HERE, "_r35_adnd_dex.py"))
dis = load("dis", os.path.join(HERE, "_r35_sns_dis.py"))

cls_key, meth_name = sys.argv[1], sys.argv[2]
targets = [int(x) for x in sys.argv[3:]]

# 取 code_off
found = None
for dn, dx in adnd.load_all():
    for cls, sup, sig, co in dis.iter_methods(dx):
        if cls_key in cls and ("->%s(" % meth_name) in sig:
            found = (dn, dx, co)
            break
    if found:
        break
if not found:
    print("NOT FOUND")
    sys.exit(1)
dn, dx, co = found
print("dex=%s code_off=0x%x sig=%s" % (dn, co, sig))

# 逐指令记录：对每条指令，产出 (i, 文字, 目的寄存器列表, 来源表达式)
MOVERS = {0x01: "move", 0x02: "move/from16", 0x07: "move-object",
          0x08: "move-object/from16", 0x12: "const/4", 0x13: "const/16",
          0x14: "const", 0x1a: "const-string", 0x1b: "const-string/jumbo",
          0x1c: "const-class", 0x0a: "move-result", 0x0b: "move-result-wide",
          0x0c: "move-result-object"}

records = []  # (i, text, dest_regs, src_desc)
for i, lo, u0, u1, u2 in dis.walk(dx, co):
    txt = dis.render(dx, i, lo, u0, u2 if False else u1, u2) or ("op=0x%02x u0=0x%04x u1=0x%04x" % (lo, u0, u1))
    dest, src = None, None
    if lo in (0x01, 0x02, 0x07, 0x08):  # 12x / 22x move
        if lo in (0x01, 0x07):  # 12x: dest=(u0>>8)&f, src=(u0>>12)&f
            dest, src = (u0 >> 8) & 0xF, (u0 >> 12) & 0xF
        else:  # 22x: dest=(u0>>8)&ff, src=u1
            dest, src = (u0 >> 8) & 0xFF, u1
        txt = "v%d <- v%d  (%s)" % (dest, src, MOVERS[lo])
    elif lo == 0x12:  # const/4: dest=(u0>>8)&f, lit=(u0>>12)&f
        dest, src = (u0 >> 8) & 0xF, (u0 >> 12) & 0xF
        txt = "v%d <- #%d  (const/4)" % (dest, src)
    elif lo == 0x13:  # const/16: dest=(u0>>8)&ff, lit=u1
        dest, src = (u0 >> 8) & 0xFF, u1
        txt = "v%d <- #%d  (const/16)" % (dest, src)
    elif lo in (0x1a, 0x1b):  # const-string
        dest = (u0 >> 8) & 0xFF
        s = dx.s(u1) if lo == 0x1A else dx.s(u1 | (u2 << 16))
        txt = "v%d <- '%s'  (const-string)" % (dest, s)
        src = "STR"
    elif lo in (0x0a, 0x0b, 0x0c):  # move-result
        dest = (u0 >> 8) & 0xFF
        src = "RESULT"
    # 字段读
    if 0x52 <= lo <= 0x58:  # iget*
        dest = (u0 >> 8) & 0xF
        obj = (u0 >> 12) & 0xF
        c, n, t = dis.fdesc(dx, u1)
        txt = "v%d <- iget %s->%s : %s (obj v%d)" % (dest, c, n, t, obj)
        src = "FIELD"
    records.append((i, txt, dest, src))

# 正向状态：每个寄存器当前的值描述
state = {}
for i, txt, dest, src in records:
    if dest is None:
        continue
    state[dest] = txt

# 反向：从 target 出发，打印每个 target 在各时刻的写入（取最后一次有效写入前的所有写入）
for t in targets:
    print("\n===== v%d 的写入序列 =====" % t)
    seq = [(i, txt) for i, txt, dest, src in records if dest == t]
    for i, txt in seq:
        print("  i=0x%04x  %s" % (i, txt))
    if not seq:
        print("  (无直接写入)")
