"""R21d 静态取证：`Lcom/tencent/mm/storage/h9;->x1` 的 msgId 范围校验常量。

## 为什么需要这个
2026-10-25 真机 crash（`fr4.b: msgId not in the reasonable scope`）的抛出点经
`tools/_r4_dexref.py classes4.dex "msgId not in the reasonable scope"` 反查确定为：

    Lcom/tencent/mm/storage/h9;  ->  Lcom/tencent/mm/storage/h9;#x1

`h9` 是 `Lte0/y0` 的子类（消息行对象），`x1` 里抛异常。我们要知道它比较了什么常量，
才能决定「提示行的 msgId 能不能用正数」「用哪一段正数才不与真实消息撞号」。

## 做法
DEX 的 code_item 里，`x1` 的字节码会含 `const-wide`（5 位宽版本，opcode 0x16/0x17/0x18）
或 `const`（0x12/0x13/0x14）。本脚本：
1. 解析 `_cache/dex/classes4.dex` 的 class_defs → 找 `Lcom/tencent/mm/storage/h9;`
2. 在它的 class_data 里找 method `x1`（无重载）→ 拿 code_off
3. 反汇编一段，把 `const*` / `cmp-long` / `if-*` 附近的常量捞出来

⚠️ 不依赖外部 dexdump（本机没有），纯 struct 解析。
⚠️ 已知坑（写死在这里）：`class_defs` 下标 ≠ `type_ids` 下标，要用 class_defs 遍历的 def_index
   去算 `class_defs_off + 32*def_index`。用 class_idx 会指到别的类。
"""
import os
import struct
import sys

DEX = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "_cache", "dex", "classes4.dex")
TARGET_CLASS = "Lcom/tencent/mm/storage/h9;"
TARGET_METHOD = "x1"


def uleb(data, off):
    r = s = 0
    while True:
        b = data[off]
        r |= (b & 0x7F) << s
        off += 1
        s += 7
        if not (b & 0x80):
            return r, off


def read_strings(data, hdr):
    string_ids_size, string_ids_off = hdr["string_ids_size"], hdr["string_ids_off"]
    out = [None] * string_ids_size
    for i in range(string_ids_size):
        so = struct.unpack_from("<I", data, string_ids_off + 4 * i)[0]
        # string_data_item: uleb utf16_size, 然后 MUTF-8
        n, p = uleb(data, so)
        e = p
        while data[e] != 0:
            e += 1
        out[i] = data[p:e].decode("utf-8", "replace")
    return out


def main():
    data = open(DEX, "rb").read()
    # header
    hdr = dict(
        string_ids_size=struct.unpack_from("<I", data, 56)[0],
        string_ids_off=struct.unpack_from("<I", data, 60)[0],
        type_ids_size=struct.unpack_from("<I", data, 64)[0],
        type_ids_off=struct.unpack_from("<I", data, 68)[0],
        proto_ids_off=struct.unpack_from("<I", data, 76)[0],
        field_ids_off=struct.unpack_from("<I", data, 84)[0],
        method_ids_size=struct.unpack_from("<I", data, 88)[0],
        method_ids_off=struct.unpack_from("<I", data, 92)[0],
        class_defs_size=struct.unpack_from("<I", data, 96)[0],
        class_defs_off=struct.unpack_from("<I", data, 100)[0],
    )
    strings = read_strings(data, hdr)

    def type_name(tidx):
        sidx = struct.unpack_from("<I", data, hdr["type_ids_off"] + 4 * tidx)[0]
        return strings[sidx]

    # 找目标类的 def_index
    target_def = None
    target_class_idx = None
    for i in range(hdr["class_defs_size"]):
        off = hdr["class_defs_off"] + 32 * i
        class_idx = struct.unpack_from("<I", data, off)[0]
        if type_name(class_idx) == TARGET_CLASS:
            target_def = i
            target_class_idx = class_idx
            data_off = struct.unpack_from("<I", data, off + 24)[0]
            break
    if target_def is None:
        print("❌ 未找到 %s" % TARGET_CLASS)
        return 1
    print("✅ %s  def_index=%d class_idx=%d class_data_off=%d"
          % (TARGET_CLASS, target_def, target_class_idx, data_off))
    if data_off == 0:
        print("   class_data 为空")
        return 1

    # 解析 class_data_item
    p = data_off
    sf, p = uleb(data, p)
    inf, p = uleb(data, p)
    dm, p = uleb(data, p)
    vm, p = uleb(data, p)
    print("  static_fields=%d instance_fields=%d direct_methods=%d virtual_methods=%d"
          % (sf, inf, dm, vm))

    def skip_fields(p, n):
        for _ in range(n):
            _, p = uleb(data, p)
            _, p = uleb(data, p)
        return p

    p = skip_fields(p, sf)
    p = skip_fields(p, inf)

    found = []
    for kind, n in (("direct", dm), ("virtual", vm)):
        midx = 0
        for _ in range(n):
            diff, p = uleb(data, p)
            midx += diff
            acc, p = uleb(data, p)
            code_off, p = uleb(data, p)
            m_off = hdr["method_ids_off"] + 8 * midx
            m_class = struct.unpack_from("<H", data, m_off)[0]
            m_proto = struct.unpack_from("<H", data, m_off + 2)[0]
            m_name_idx = struct.unpack_from("<I", data, m_off + 4)[0]
            name = strings[m_name_idx]
            if name == TARGET_METHOD:
                found.append((kind, midx, acc, code_off, m_proto))

    if not found:
        print("❌ 未找到方法 %s" % TARGET_METHOD)
        return 1

    for kind, midx, acc, code_off, m_proto in found:
        print("\n" + "=" * 76)
        print("方法 %s.%s（%s, access=0x%04x, code_off=%d）"
              % (TARGET_CLASS, TARGET_METHOD, kind, acc, code_off))
        print("=" * 76)
        if code_off == 0:
            print("  抽象方法，无 code")
            continue
        # code_item
        registers = struct.unpack_from("<H", data, code_off)[0]
        ins = struct.unpack_from("<H", data, code_off + 2)[0]
        outs = struct.unpack_from("<H", data, code_off + 4)[0]
        tries = struct.unpack_from("<H", data, code_off + 6)[0]
        insns_size = struct.unpack_from("<I", data, code_off + 12)[0]
        print("  registers=%d ins=%d outs=%d tries=%d insns_size=%d(units)"
              % (registers, ins, outs, tries, insns_size))
        base = code_off + 16
        code = data[base: base + insns_size * 2]

        # 扫 const / const-wide / cmp / if，把常量列出来
        consts_wide, consts_32, cmps = [], [], []
        i = 0
        n = insns_size
        while i < n:
            u = struct.unpack_from("<H", code, i * 2)[0]
            op = u & 0xFF
            if op == 0x16:          # const/4  vA, #+B  (4bit)
                a, b = (u >> 8) & 0xF, (u >> 12) & 0xF
                v = b - 16 if b >= 8 else b
                consts_32.append((i, v, "const/4"))
                i += 1
            elif op == 0x12:        # const/4
                a, b = (u >> 8) & 0xF, (u >> 12) & 0xF
                v = b - 16 if b >= 8 else b
                consts_32.append((i, v, "const/4"))
                i += 1
            elif op == 0x13:        # const/16
                v = struct.unpack_from("<h", code, i * 2 + 2)[0]
                consts_32.append((i, v, "const/16"))
                i += 2
            elif op == 0x14:        # const
                v = struct.unpack_from("<i", code, i * 2 + 2)[0]
                consts_32.append((i, v, "const"))
                i += 3
            elif op == 0x15:        # const/high16
                v = struct.unpack_from("<h", code, i * 2 + 2)[0] << 16
                consts_32.append((i, v, "const/high16"))
                i += 2
            elif op == 0x17:        # const-wide/16
                v = struct.unpack_from("<h", code, i * 2 + 2)[0]
                consts_wide.append((i, v, "const-wide/16"))
                i += 2
            elif op == 0x18:        # const-wide/32
                v = struct.unpack_from("<i", code, i * 2 + 2)[0]
                consts_wide.append((i, v, "const-wide/32"))
                i += 3
            elif op == 0x19:        # const-wide
                lo = struct.unpack_from("<I", code, i * 2 + 2)[0]
                hi = struct.unpack_from("<I", code, i * 2 + 4)[0]
                v = (hi << 32) | lo
                if v >= 1 << 63:
                    v -= 1 << 64
                consts_wide.append((i, v, "const-wide"))
                i += 5
            elif op == 0x1a:        # const-string
                i += 2
            elif op == 0x1b:        # const-string/jumbo
                i += 3
            elif 0x2b <= op <= 0x2c:  # cmp-floating
                i += 2
            elif op == 0x31:        # cmp-long
                cmps.append((i, "cmp-long"))
                i += 2
            else:
                i += 1

        print("  const-wide 常量（%d 个）:" % len(consts_wide))
        for off, v, k in consts_wide:
            print("    @%-5d %-14s %d  (0x%X)" % (off, k, v, v & 0xFFFFFFFFFFFFFFFF))
        print("  const 常量（%d 个，过滤 0/1/-1 后）:" % len(consts_32))
        for off, v, k in consts_32:
            print("    @%-5d %-12s %d" % (off, k, v))
        print("  cmp-long 次数: %d" % len(cmps))

    return 0


if __name__ == "__main__":
    sys.exit(main())
