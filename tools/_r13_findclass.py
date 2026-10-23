#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""跨全部 dex 定位某个类**真正定义**在哪个 dex，并打印 superclass / access / 有无无参构造。

为什么需要它：
    `grep 'Lcom/.../m;'` 在**每个** dex 里都能命中，因为 `type_ids` 记录的是「被引用」的类型，
    任何引用它的 dex 都会有这条字符串。要判断「定义在哪」，只能看 `class_defs`。
    这条坑在 R11 就踩过（`_r4_dexref.py` 用 `type_names` 造成假多重命中）。

用法：
    python tools/_r13_findclass.py Lcom/tencent/mm/pluginsdk/model/app/m;
    python tools/_r13_findclass.py com.tencent.mm.pluginsdk.model.app.m     # 也接受点号
"""

import glob
import io
import os
import struct
import sys


DEXDIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "_cache", "dex")


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
        # ⚠️ dex header 的 id 区**不是连续的 6 组**！标准布局是：
        #     0x38 string_ids_size, 0x3c string_ids_off
        #     0x40 type_ids_size,   0x44 type_ids_off
        #     0x48 proto_ids_size,  0x4c proto_ids_off
        #     0x50 field_ids_size,  0x54 field_ids_off
        #     0x58 method_ids_size, 0x5c method_ids_off
        #     0x60 class_defs_size, 0x64 class_defs_off
        #   每组是 (size, off) 共 8 字节，所以 6 组正好是 48 字节 = "<12I"。
        #   但**必须在 0x38 起读**，且不能把 0x30 的 data_size 等混进来。
        #   踩过的坑：写成 `unpack_from("<12I", b, 0x38)` 看起来对，实际是对的；
        #   真正的坑是下面 methods_of() 里把 class_data 的字段区跳过了却没累加 method_idx diff。
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from("<12I", self.b, 0x38)
        self.strings = self._strings()
        self.types = self._types()

    def _strings(self):
        out = []
        b = self.b
        for i in range(self.string_ids_size):
            off = struct.unpack_from("<I", b, self.string_ids_off + 4 * i)[0]
            _, p = uleb(b, off)          # utf16 size，跳过
            e = b.index(b"\x00", p)
            out.append(b[p:e].decode("utf-8", "replace"))
        return out

    def _types(self):
        return [self.strings[struct.unpack_from("<I", self.b, self.type_ids_off + 4 * i)[0]]
                for i in range(self.type_ids_size)]

    def class_defs(self):
        """yield (def_index, class_idx, access, super_idx, name)

        ⚠️ **`def_index`（class_defs 数组下标）与 `class_idx`（type_ids 下标）是两个不同的东西。**
        走 `class_data` 必须用 `def_index` 去算 `class_defs_off + 32 * def_index`；
        用 `class_idx` 会指到别的类上，症状是「方法数明显偏少、构造函数报错」——
        本轮就因此误判过 `WXFileObject` 没有无参构造（它其实有 `()V`）。
        """
        b = self.b
        for i in range(self.class_defs_size):
            base = self.class_defs_off + 32 * i
            class_idx, access, super_idx = struct.unpack_from("<III", b, base)
            name = self.types[class_idx] if class_idx < len(self.types) else "?"
            yield i, class_idx, access, super_idx, name

    def methods_of(self, def_index):
        """yield (name, proto_desc, access)；参数是 **class_defs 下标**，不是 class_idx
        class_data_item 布局（DEX 规范）：
            uleb128 static_fields_size
            uleb128 instance_fields_size
            uleb128 direct_methods_size
            uleb128 virtual_methods_size
            encoded_field[static_fields_size]     ← 每项 2 个 uleb: field_idx_diff, access_flags
            encoded_field[instance_fields_size]   ← 同上
            encoded_method[direct_methods_size]   ← 每项 3 个 uleb: method_idx_diff, access_flags, code_off
            encoded_method[virtual_methods_size]  ← 同上
        """
        b = self.b
        base = self.class_defs_off + 32 * def_index
        class_data_off = struct.unpack_from("<I", b, base + 24)[0]
        if class_data_off == 0:
            return []
        p = class_data_off
        sf, p = uleb(b, p)
        inf, p = uleb(b, p)
        dm, p = uleb(b, p)
        vm, p = uleb(b, p)
        # 跳过 static + instance 字段：每项 **两个** uleb
        for _ in range(sf + inf):
            _, p = uleb(b, p)      # field_idx_diff
            _, p = uleb(b, p)      # access_flags

        out = []
        for cnt in (dm, vm):
            idx = 0
            for _ in range(cnt):
                diff, p = uleb(b, p)
                acc, p = uleb(b, p)
                _code, p = uleb(b, p)
                idx += diff
                if idx >= self.method_ids_size:
                    continue
                moff = self.method_ids_off + 8 * idx
                _cls, proto_idx, name_idx = struct.unpack_from("<HHI", b, moff)
                if name_idx >= self.string_ids_size:
                    continue
                name = self.strings[name_idx]
                desc = self._proto(proto_idx)
                out.append((name, desc, acc))
        return out

    def _proto(self, proto_idx):
        b = self.b
        if proto_idx >= self.proto_ids_size:
            return "(?)"
        shorty, ret_idx, params_off = struct.unpack_from(
            "<III", b, self.proto_ids_off + 12 * proto_idx)
        ret = self.types[ret_idx] if ret_idx < len(self.types) else "?"
        params = []
        if params_off:
            p = params_off
            n, p = uleb(b, p)
            for _ in range(n):
                t, p = uleb(b, p)
                params.append(self.types[t] if t < len(self.types) else "?")
        return "(" + "".join(params) + ")" + ret


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    want = sys.argv[1]
    if not want.startswith("L"):
        want = "L" + want.replace(".", "/") + ";"
    if not want.endswith(";"):
        want += ";"

    found = False
    for path in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        d = Dex(path)
        for def_index, class_idx, access, super_idx, name in d.class_defs():
            if name != want:
                continue
            found = True
            sup = d.types[super_idx] if super_idx < len(d.types) else "?"
            print("DEFINED in %s" % os.path.basename(path))
            print("  class = %s" % name)
            print("  super = %s" % sup)
            print("  access = 0x%04x" % access)
            ms = d.methods_of(def_index)
            ctors = [(n, de, a) for (n, de, a) in ms if n == "<init>"]
            if not ctors:
                print("  <init> : ⚠️ 无（不可能，除非解析失败）")
            for n, de, a in ctors:
                flag = ""
                if de == "()V":
                    flag = "   ★ 无参构造"
                print("  <init> %s%s" % (de, flag))
            if not any(de == "()V" for _, de, _ in ctors):
                print("  ⛔ 没有无参构造 ⇒ 不能用 XposedHelpers.newInstance() 直接造")
            print("  methods(%d): %s" % (len(ms), ", ".join(n for n, _, _ in ms[:24])))
    if not found:
        print("NOT-DEFINED in any dex: %s" % want)
        print("（注意：这在 grep 里也可能「命中很多 dex」—— 那是 type_ids 的引用，不是定义）")


if __name__ == "__main__":
    main()
