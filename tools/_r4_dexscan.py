#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R4 拍一拍静态取证：从微信 base.apk 的 dex 里搜「拍了拍」等关键词，定位实现类。

用法：
    python _r4_dexscan.py             # 解压 dex + 搜关键词，打印命中的 dex 与偏移
    python _r4_dexscan.py --dump      # 额外用 dexdump 反汇编命中的 dex 找引用方法（慢）
"""
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK = os.path.join(ROOT, "_cache", "mm.apk")
DEXDIR = os.path.join(ROOT, "_cache", "dex")

# 拍一拍相关关键词（UTF-8 搜；dex string pool 里是 MUTF-8，ASCII 部分一致）
KEYS = ["拍了拍", "拍一拍", "patted", "doubletap", "double_tap"]


def main():
    if not os.path.exists(APK):
        print("缺 APK:", APK)
        return
    os.makedirs(DEXDIR, exist_ok=True)
    z = zipfile.ZipFile(APK)
    dex_entries = [n for n in z.namelist() if n.endswith(".dex")]
    print("dex entries:", dex_entries)
    hits = {}
    for n in dex_entries:
        out = os.path.join(DEXDIR, n.replace("/", "_"))
        if not os.path.exists(out):
            with z.open(n) as f, open(out, "wb") as w:
                while True:
                    b = f.read(1 << 20)
                    if not b:
                        break
                    w.write(b)
        size = os.path.getsize(out)
        data = open(out, "rb").read()
        found = {}
        for k in KEYS:
            kb = k.encode("utf-8")
            pos, offs = 0, []
            while True:
                i = data.find(kb, pos)
                if i < 0:
                    break
                offs.append(i)
                pos = i + 1
                if len(offs) >= 20:
                    break
            if offs:
                found[k] = offs
        print("\n%s  %.1f MB  命中=%s" % (n, size / 1048576, {k: len(v) for k, v in found.items()}))
        for k, offs in found.items():
            print("   %s @ %s" % (k, offs[:10]))
        if found:
            hits[out] = found
    if not hits:
        print("\n⚠️ 所有 dex 都没命中 —— 字符串可能在资源/其它 dex/运行时动态生成")


if __name__ == "__main__":
    main()
