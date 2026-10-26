r"""R28 头像取证 步骤5：抓 dex 自带的「混淆类 → 原名/原方法/原签名」映射表。

为什么会有这东西：
    微信的 hook 埋点框架（本版是 `y40/a.d(...)`，类似 hellhound/Matrix 的字节码插桩）
    在插桩点会把被 hook 方法的**原始类名 / 原始方法名 / 原始签名**以 const-string
    形式留在字节码里，形如：

        const-string v12, "com/tencent/mm/modelavatar/AvatarStorage"
        const-string v13, "getAvatarFromFile"
        const-string v14, "(Ljava/lang/String;Ljava/lang/String;Z)Landroid/graphics/Bitmap;"
        const-string v15, "android/graphics/Bitmap_EXEC_"
        const-string v16, "createBitmap"
        const-string v17, "(Landroid/graphics/Bitmap;IIII)Landroid/graphics/Bitmap;"
        invoke-static/range {v10..v17}, Ly40/a;.d:(...)V

    ⇒ 这等于官方反混淆表：**混淆类 x.e == AvatarStorage.getAvatarFromFile**。
       比靠 log tag 猜强一个数量级，属于① 字面证据。

用法：
    python tools/_r28_map.py com/tencent/mm/modelavatar/AvatarStorage
    python tools/_r28_map.py --dex=classes7.dex com/tencent/mm/modelavatar
"""
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _r28_strref import Dex, DEXDIR  # noqa: E402


def main():
    args = sys.argv[1:]
    dexfilter = None
    if args and args[0].startswith("--dex="):
        dexfilter = args[0][6:]
        args = args[1:]
    if not args:
        raise SystemExit(__doc__)
    target = args[0]

    for path in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        if dexfilter and dexfilter not in os.path.basename(path):
            continue
        d = Dex(path)
        for cn, _di, mn, desc, acc, kind, code in d.iter_methods():
            if not code:
                continue
            strs = []
            for si in d.code_const_strings(code):
                if si < len(d.strings):
                    strs.append(d.strings[si])
            # ⚠️ `target not in strs` 是**精确相等**匹配，不是子串！
            #    `strs` 是 list，`x in list` 走 __eq__ 逐项比较。
            #    传 'com/tencent/mm/modelavatar' 想做前缀匹配会一条都命中不了（踩过）。
            if not any(target in s for s in strs):
                continue
            # 只保留 target 出现位置前后 8 条，避免噪声
            print("--- %s  %s->%s%s  [static]" % (os.path.basename(path), cn, mn, desc)
                  if acc & 0x0008 else
                  "--- %s  %s->%s%s" % (os.path.basename(path), cn, mn, desc))
            for i, s in enumerate(strs):
                if target in s:            # 这里是 str 的 in ⇒ 子串匹配，OK
                    lo = max(0, i - 1)
                    hi = min(len(strs), i + 9)
                    for j in range(lo, hi):
                        mark = ">>" if j == i else "  "
                        print("    %s [%d] %r" % (mark, j, strs[j]))
                    print("    ---")


if __name__ == "__main__":
    main()
