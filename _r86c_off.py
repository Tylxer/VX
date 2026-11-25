# -*- coding: utf-8 -*-
"""R86c: 自算指定方法的 dex code_off / 指令数（禁止照抄历史偏移）。
用法： python _r86c_off.py <类名子串> <方法名>
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

cls_key = sys.argv[1]
meth_name = sys.argv[2]

for dn, dx in adnd.load_all():
    for cls, sup, sig, code_off in dis.iter_methods(dx):
        if cls_key not in cls:
            continue
        if ("->%s(" % meth_name) not in sig:
            continue
        insns = 0
        regs = ins = outs = 0
        if code_off:
            regs, ins, outs = struct.unpack_from("<HHH", dx.d, code_off)
            insns = struct.unpack_from("<I", dx.d, code_off + 12)[0]
        print("%-14s cls=%s" % (dn, cls))
        print("   sig      = %s" % sig)
        print("   code_off = 0x%06x   regs=%d ins=%d outs=%d insns=%d(0x%x)"
              % (code_off, regs, ins, outs, insns, insns))
        print("   abs byte offset of instruction i = 0x%x + 16 + 2*i"
              % code_off)
