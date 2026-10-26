#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R21c 独立复核：验证子代理 voice-sql-form 的三条可机检结论。

复核项（全部只读 dex，不碰 adb / gradle）：
    V1  com.tencent.wcdb.database.SQLiteDatabase 是否**真的没有** deleteWithOnConflict
        （子代理 §3.1 的核心主张；若有，则 VoiceGuard 的两 hook 不是死代码）
    V2  Lem0/e1 是否真有方法 f(String)Z 且其字符串表含 'FileName= ?' + 'voiceinfo'
    V3  z84/z 是否真有 delete(String,String,String[])I
    V4  字符串 'DELETE FROM voiceinfo WHERE FileName IN' 在哪个 dex、被哪些类引用

用法：python tools/_r21c_verify_sql.py
"""

import glob
import io
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEXDIR = os.path.join(ROOT, "_cache", "dex")

sys.path.insert(0, HERE)
from _r13_findclass import Dex  # noqa: E402  复用已验证的解析器


def type_of(desc):
    """Lcom/tencent/wcdb/database/SQLiteDatabase; -> com.tencent.wcdb.database.SQLiteDatabase"""
    if desc and desc.startswith("L") and desc.endswith(";"):
        return desc[1:-1].replace("/", ".")
    return desc


def find_class(dexes, dotted):
    """返回 [(dex_name, def_index)] —— 只看 class_defs（定义），不看 type_ids（引用）"""
    hits = []
    for name, d in dexes:
        for i in range(d.class_defs_size):
            base = d.class_defs_off + 32 * i
            cls_idx, = struct.unpack_from("<I", d.b, base)
            full = type_of(d.types[cls_idx]) if cls_idx < len(d.types) else ""
            if full == dotted:
                hits.append((name, d, i))
    return hits


def main():
    files = sorted(glob.glob(os.path.join(DEXDIR, "classes*.dex")))
    if not files:
        print("ERROR: no dex in %s" % DEXDIR)
        return 2
    dexes = []
    for p in files:
        dexes.append((os.path.basename(p), Dex(p)))
    print("loaded %d dex\n" % len(dexes))

    # ---------- V1 ----------
    print("=" * 70)
    print("V1  SQLiteDatabase 有无 deleteWithOnConflict")
    print("=" * 70)
    hits = find_class(dexes, "com.tencent.wcdb.database.SQLiteDatabase")
    if not hits:
        print("  !! 未找到类定义")
    for name, d, i in hits:
        ms = d.methods_of(i)
        del_like = sorted(
            {m[0] for m in ms if "delete" in m[0].lower()})
        print("  dex=%s  def_index=%d  方法总数=%d" % (name, i, len(ms)))
        print("  名字含 delete 的方法：")
        for m in del_like:
            print("    - %s" % m)
        has = "deleteWithOnConflict" in {m[0] for m in ms}
        print("  >>> deleteWithOnConflict 存在？ %s" % ("YES（子代理结论错）" if has else "NO（与子代理一致）"))
        print("  所有写库相关方法：")
        for m in ms:
            if m[0] in ("delete", "update", "updateWithOnConflict", "execSQL",
                        "insert", "insertWithOnConflict"):
                print("    %s%s" % (m[0], m[1]))

    # ---------- V2 ----------
    print()
    print("=" * 70)
    print("V2  Lem0/e1 的 f(String)Z 与 'FileName= ?' 字符串")
    print("=" * 70)
    hits = find_class(dexes, "em0.e1")
    if not hits:
        print("  !! 未找到类定义 em0.e1")
    for name, d, i in hits:
        ms = d.methods_of(i)
        print("  dex=%s  方法=%s" % (name, [(m[0] + m[1]) for m in ms]))
        has_f = any(m[0] == "f" for m in ms)
        print("  >>> 有 f 方法？ %s" % has_f)
        strs = set(d.strings)
        for want in ("FileName= ?", "FileName=?", "voiceinfo"):
            print("  >>> 字符串 %-16r 在此 dex？ %s" % (want, want in strs))

    # ---------- V3 ----------
    print()
    print("=" * 70)
    print("V3  z84/z 有无 delete(String,String,String[])I")
    print("=" * 70)
    hits = find_class(dexes, "z84.z")
    if not hits:
        print("  !! 未找到类定义 z84.z")
    for name, d, i in hits:
        ms = d.methods_of(i)
        m2 = [m for m in ms if m[0] == "delete"]
        print("  dex=%s  delete 重载：%s" % (name, [m[1] for m in m2]))

    # ---------- V4 ----------
    print()
    print("=" * 70)
    print("V4  子查询 SQL 字符串在哪个 dex（只证明字符串存在，不证明谁调用）")
    print("=" * 70)
    needle = "DELETE FROM voiceinfo WHERE FileName IN"
    for name, d in dexes:
        for s in d.strings:
            if needle in s:
                print("  %s : %r" % (name, s))

    return 0


if __name__ == "__main__":
    sys.exit(main())
