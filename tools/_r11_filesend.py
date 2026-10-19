#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R11 静态取证：为 6.6「强制转发」的 `resendFile`（K8 文件发送）找锚点。

## 为什么单独写这个脚本

`_r4_dexref.py` 的 `cmd_methods` 遍历的是 **type_ids**（被引用的类型），
不是 **class_defs**（真正定义的类）。一个类名会同时出现在引用它的 dex 和
定义它的 dex 里 ⇒ 直接 `grep type_names` 会得到多个「命中」，误判成多份定义。

本脚本只认 `class_defs`，并且一次把「定义在哪个 dex + 全部方法签名」打全。

## 用法

    # 1. 定位类定义
    python tools/_r11_filesend.py where Lhl0/r1; Lq1;

    # 2. 列某类的全部方法（含参数/返回类型）
    python tools/_r11_filesend.py methods Lhl0/r1;

    # 3. 找调用点（谁在用这个方法）
    python tools/_r11_filesend.py callers 'Lhl0/r1;->b(Ljava/lang/String;)Lq1;'

## 结论去向

有结论后写进 `docs/取证结论.md`（不许只留在对话里），
脚本只负责产出证据，不做判断。
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _r4_dexref import Dex, DEXDIR  # noqa: E402


def defined_classes(dex):
    """返回本 dex **真正定义**的类名集合（走 class_defs，不是 type_ids）。"""
    out = {}
    for ci in range(dex.cls_size):
        base = dex.cls_off + 32 * ci
        cls_idx = struct.unpack_from("<I", dex.d, base)[0]
        if cls_idx < len(dex.type_names):
            out[dex.type_names[cls_idx]] = base
    return out


def superclass_of(dex, base):
    si = struct.unpack_from("<I", dex.d, base + 8)[0]
    return dex.type_names[si] if si and si < len(dex.type_names) else "-"


def all_dex():
    for f in sorted(os.listdir(DEXDIR)):
        if f.endswith(".dex"):
            yield f, os.path.join(DEXDIR, f)


def cmd_where(descs):
    """每个类名定义在哪个 dex（可能有多个 dex 各定义一份不同版本的类）。"""
    for desc in descs:
        print("=== %s ===" % desc)
        found = 0
        for f, p in all_dex():
            try:
                d = Dex(p)
            except Exception as e:
                print("  %s 解析失败: %s" % (f, e))
                continue
            dc = defined_classes(d)
            if desc in dc:
                found += 1
                print("  ✅ 定义于 %s  super=%s" % (f, superclass_of(d, dc[desc])))
        if not found:
            print("  ❌ 没有任何 dex 定义该类（可能类名写错，或这是被引用但未定义的类型）")
    print()


def cmd_methods(descs):
    for desc in descs:
        print("=== %s 的全部方法 ===" % desc)
        found = 0
        for f, p in all_dex():
            try:
                d = Dex(p)
            except Exception:
                continue
            dc = defined_classes(d)
            if desc not in dc:
                continue
            found += 1
            print("  [%s] super=%s" % (f, superclass_of(d, dc[desc])))
            n = 0
            for mi, (mcls, mname, _pi) in enumerate(d.methods):
                if mcls != desc:
                    continue
                print("     %s" % d.sig(mi))
                n += 1
            if n == 0:
                print("     (该类在本 dex 里无 method_ids 条目)")
            print("     合计 %d 个方法" % n)
        if not found:
            print("  ❌ 未找到定义")
        print()


def cmd_callers(prefix):
    """扫所有 dex 找 invoke-* 引用 `prefix` 的调用点。"""
    print("=== 调用点：%s ===" % prefix)
    total = 0
    for f, p in all_dex():
        try:
            d = Dex(p)
        except Exception:
            continue
        targets = set()
        for mi in range(d.meth_size):
            if prefix in d.sig(mi):
                targets.add(mi)
        if not targets:
            continue
        print("  [%s] 命中 method_idx %d 个" % (f, len(targets)))
        for mi in sorted(targets):
            print("     目标: %s" % d.sig(mi))
        seen = set()
        for cls, sig in d.invokes(targets):
            k = (cls, sig)
            if k in seen:
                continue
            seen.add(k)
            total += 1
            print("     调用方: %s\n              -> %s" % (cls, sig))
    if total == 0:
        print("  ❌ 无调用点")
    print("  （去重后 %d 处）" % total)


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    cmd = sys.argv[1]
    args = sys.argv[2:]
    if cmd == "where":
        cmd_where(args)
    elif cmd == "methods":
        cmd_methods(args)
    elif cmd == "callers":
        cmd_callers(args[0])
    else:
        print("未知子命令:", cmd)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
