# -*- coding: utf-8 -*-
"""用 _r7_improve_verify.py 里已验证的 Dex 解码器（官方格式表）做反查"""
import os, importlib.util
_here = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("rv", os.path.join(_here, "_r7_improve_verify.py"))
rv = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(rv)

DEXDIR = rv.DEXDIR
_CACHE = {}

def load(name="classes3.dex"):
    if name not in _CACHE:
        _CACHE[name] = rv.Dex(os.path.join(DEXDIR, name))
    return _CACHE[name]

def dex_names():
    return sorted(f for f in os.listdir(DEXDIR) if f.endswith(".dex"))

def methods(d, cls):
    """[(name, code_off)] —— 注意 Dex.class_methods 返回的是 (method_idx, code_off)"""
    return [(d.methods[mi][1], co) for mi, co in d.class_methods(cls)]

def decode(d, cls, mname, lo=0, hi=10**9):
    out = []
    for mi, co in d.class_methods(cls):
        if d.methods[mi][1] != mname:
            continue
        res, _info = d.decode(co)
        for addr, w, op, txt, tgt in res:
            if lo <= addr <= hi:
                out.append((addr, txt, tgt))
    return out

def all_ins(d):
    """产出 (cls, mname, addr, text)"""
    for cname, sup, cdata in d.classes:
        try:
            ms = d.class_methods(cname)
        except Exception:
            continue
        for mi, co in ms:
            nm = d.methods[mi][1]
            try:
                res, _info = d.decode(co)
                for addr, w, op, txt, tgt in res:
                    yield cname, nm, addr, txt
            except Exception:
                continue
