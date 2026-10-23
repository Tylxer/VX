#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按 dexdump 反汇编切片，精确解出「第 N 个形参落在哪个 v 寄存器」。

用法：
    python tools/_r13_sigmap.py _cache/_r13_r5j.txt
    python tools/_r13_sigmap.py _cache/_r13_u0B.txt --json

为什么要这个工具：
    DEX 的实参寄存器编号规则是 `v(registers-ins) .. v(registers-1)`，但有两个**极易踩的坑**：

    坑 1：**`ins` 包含隐式 `this`**。实例方法的 `ins = 1 + 形参占位总数`，静态方法则没有这个 1。
         踩过的实例：`qf4.r5.j` 是 `PUBLIC FINAL`（非 static），`ins=14`、13 个形参，
         ⇒ `v17` 是 `this`，形参从 `v18` 起。若漏算 `this`，全部形参整体偏移 1 位，
         于是「第 4 参」会被误当成「第 5 参」——而且**不会报错**，只是结论悄悄错掉。

    坑 2：**long/double 占 2 个寄存器**，序号连续但不占两个形参位。

    判据来源：
    - access 标志（`0x0009 PUBLIC STATIC` 的 STATIC 位 = 0x8）决定有没有 this；
    - registers / ins 从 dexdump 头部读，且**必须从完整反汇编回溯**（见 find_meta 的说明）。
"""

import re
import sys
import json


WIDE = ("J", "D")


def parse_desc(desc):
    """"(Ljava/lang/String;IJZ)V" -> ["Ljava/lang/String;", "I", "J", "Z"]"""
    assert desc.startswith("("), desc
    i, end = 1, desc.index(")")
    out = []
    while i < end:
        c = desc[i]
        if c == "[":
            j = i
            while desc[j] == "[":
                j += 1
            if desc[j] == "L":
                j = desc.index(";", j) + 1
            else:
                j += 1
            out.append(desc[i:j])
            i = j
        elif c == "L":
            j = desc.index(";", i) + 1
            out.append(desc[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return out


def short(t):
    """把描述符缩成人类可读的类型名。"""
    dim = 0
    while t.startswith("["):
        dim += 1
        t = t[1:]
    if t.startswith("L"):
        base = t[1:-1].replace("/", ".")
    elif t == "Z":
        base = "boolean"
    elif t == "B":
        base = "byte"
    elif t == "S":
        base = "short"
    elif t == "C":
        base = "char"
    elif t == "I":
        base = "int"
    elif t == "J":
        base = "long"
    elif t == "F":
        base = "float"
    elif t == "D":
        base = "double"
    elif t == "V":
        base = "void"
    else:
        base = t
    return base + "[]" * dim


def find_meta(addr, full_path):
    """从完整反汇编里回溯出 `[addr]` 这个方法的元数据块。

    ⚠️ 为什么必须回溯：dexdump 的方法元数据（name/type/access/registers/ins）打印在
    `|[addr] Class.method:(sig)` 这行**之前**，而按行切片通常从 `|[` 行开始切，
    于是元数据被切掉了。若不回溯，就会误读成文件里**下一个**方法的 registers/ins
    （实测踩过：r5.j 真值 registers=31/ins=13，误读成 28）。
    """
    needle = "[%x]" % addr
    lines = open(full_path, encoding="utf-8", errors="ignore").read().splitlines()
    hit = None
    for i, l in enumerate(lines):
        if needle in l and "|[" in l:
            hit = i
            break
    if hit is None:
        raise SystemExit("在 %s 里找不到 %s" % (full_path, needle))

    # 从 hit 往回收集最近的元数据块（遇 name 即停，再往上取到 code/registers/ins）
    block = {}
    for l in range(hit - 1, max(-1, hit - 40), -1):
        s = lines[l]
        for key, pat in (
            ("name", r"name\s*:\s*'([^']+)'"),
            ("type", r"type\s*:\s*'(\([^)]*\)\S+)'"),
            ("access", r"access\s*:\s*(0x[0-9a-fA-F]+)"),
            ("registers", r"registers\s*:\s*(\d+)"),
            ("ins", r"\bins\s*:\s*(\d+)"),
        ):
            if key not in block:
                mm = re.search(pat, s)
                if mm:
                    block[key] = mm.group(1)
        if "name" in block and "registers" in block and "ins" in block:
            break
    return block


def analyse(path, full_path=None):
    raw = open(path, encoding="utf-8", errors="ignore").read()
    lines = raw.splitlines()

    header = lines[0]
    m = re.search(r"^([0-9a-f]+):.*\]\s+([\w.$]+)\.([\w<>$]+):(\([^)]*\)\S+)", header)
    if not m:
        raise SystemExit("第一行不像 dexdump 方法头：%r" % header[:160])
    addr, cls, meth, desc = int(m.group(1), 16), m.group(2), m.group(3), m.group(4)

    reg = ins = None
    meta_is_static = False
    if full_path:
        meta = find_meta(addr, full_path)
        if "registers" in meta and "ins" in meta:
            reg, ins = int(meta["registers"]), int(meta["ins"])
        if "access" in meta:
            # STATIC 位 = 0x0008
            meta_is_static = bool(int(meta["access"], 16) & 0x8)

    if reg is None:
        # 退路：切片里若有元数据则用（可能是下一个方法的，仅作提示）
        for l in lines:
            if reg is None:
                mm = re.search(r"registers\s*:\s*(\d+)", l)
                if mm:
                    reg = int(mm.group(1))
                    continue
            if reg is not None and ins is None:
                mm = re.search(r"\bins\s*:\s*(\d+)", l)
                if mm:
                    ins = int(mm.group(1))
                    break
        print("⚠️ 未提供 --full，registers/ins 可能取自下一个方法，结论不可信\n")

    params = parse_desc(desc)
    ret = desc[desc.index(")") + 1:]

    # ★ ins 含隐式 this：非 static 方法要先扣掉 1
    is_static = meta_is_static
    n_this = 0 if is_static else 1
    n_params_units = sum(2 if p in WIDE else 1 for p in params)
    assert n_this + n_params_units == ins, (
        "ins 对不上：this(%d) + 形参占位(%d) = %d，但 dex 里 ins=%d"
        % (n_this, n_params_units, n_this + n_params_units, ins))

    base = reg - ins
    rows = []
    if n_this:
        rows.append({
            "n": 0, "type": "this", "desc": cls, "reg": "v%d" % base, "wide": False,
        })
    v = base + n_this
    for idx, t in enumerate(params, 1):
        n = 2 if t in WIDE else 1
        vrange = "v%d" % v if n == 1 else "v%d/v%d" % (v, v + 1)
        rows.append({
            "n": idx,
            "type": short(t),
            "desc": t,
            "reg": vrange,
            "wide": n == 2,
        })
        v += n

    assert v == reg, "寄存器不收敛：算到 v%d，registers=%d" % (v, reg)

    return {
        "class": cls,
        "method": meth,
        "desc": desc,
        "ret": short(ret),
        "registers": reg,
        "ins": ins,
        "params": rows,
    }


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = [a for a in sys.argv[1:] if a.startswith("--")]
    if not args:
        raise SystemExit(__doc__)
    path = args[0]
    full = None
    for f in flags:
        if f.startswith("--full="):
            full = f.split("=", 1)[1]
    if full is None and len(args) > 1:
        full = args[1]
    info = analyse(path, full)

    if "--json" in flags:
        print(json.dumps(info, ensure_ascii=False, indent=2))
        return

    print("%s.%s%s" % (info["class"], info["method"], info["desc"]))
    print("registers=%d  ins=%d  → 实参占用 v%d..v%d"
          % (info["registers"], info["ins"],
             info["registers"] - info["ins"], info["registers"] - 1))
    print()
    print("  %-4s %-34s %-10s %s" % ("#", "类型", "寄存器", "备注"))
    for r in info["params"]:
        note = "wide, 占 2 位" if r["wide"] else ""
        print("  %-4d %-34s %-10s %s" % (r["n"], r["type"], r["reg"], note))
    print("\n返回：%s" % info["ret"])


if __name__ == "__main__":
    main()
