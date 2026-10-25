"""R21c 取证（五）：按 DEX 路径模板找 voice2。

## 静态已确定（`_cache/dex/classes10.dex` 字符串常量，逐字）
微信 8.0.48 的路径模板有三套变量：
- `${data}`   = `/data/user/0/com.tencent.mm`
- `${sdTo}`   = 外部存储主目录
- `${storage}`/`${sdFrom}` = 其他存储
- `${accountSalt}` = 32 位 md5 串（= 我们已知的 `89476c7fc82a9f977bc8a901d68b7d37`）
- **`${accountSd}`  = 另一个变量，与 accountSalt 不同！**

模板（逐字）：
```
$${sdTo}/MicroMsg/${accountSd}/${dir}
$${sdTo}/MicroMsg/${accountSd}/voice2      ← 语音目录
$${sdTo}/MicroMsg/${accountSd}/video
(${sdTo}/MicroMsg/${accountSd}/attachment
```
⇒ **语音在 `${sdTo}/MicroMsg/<accountSd>/voice2`，而 `<accountSd>` ≠ 数据库目录名的 hash。**

## 本脚本
1. 列 `${sdTo}/MicroMsg` 下的全部子目录（`sdTo` 候选：`/sdcard`、`/storage/emulated/0`、`/sdcard/Android/data/com.tencent.mm`）
2. 对每个 32 位 hex 目录名，试 `voice2`
3. 命中后用 6 条 msgSvrId + 2 条对照判断文件存在性

⚠️ 上一版踩过的坑：`SnsCdnProbe.doLs` 对**不存在的目录**回 `files=0`，
对**存在但空**的目录也回 `files=0`，两者不可区分 ⇒ 一律以「能不能列出条目」为准。
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr.txt"

SVRS = ["6139184963485905836", "3941829116377846651", "4749556186668879079",
        "1083697330173103688", "2129905180187988684", "6524856252354654119"]
CTRL = ["17994308397865652", "6689996705112806292"]

SD_TO = [
    "/sdcard",
    "/storage/emulated/0",
    "/sdcard/Android/data/com.tencent.mm",
    "/storage/emulated/0/Android/data/com.tencent.mm",
]


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def am(extra, wait=3.0):
    sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE %s -p com.tencent.mm" % extra)
    time.sleep(wait)


def read_dbg():
    sh("pull", DBG, LOCAL)
    try:
        return open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        return []


def cdn_ls(d, n=200):
    am("--es op cdn --es sub ls --es val '%s' --ei n %d" % (d, n))
    lines = read_dbg()
    idx = None
    for i, l in enumerate(lines):
        if "CDN ls " in l and d in l:
            idx = i
    if idx is None:
        return None, []
    items = []
    for l in lines[idx + 1:]:
        mm = re.search(r"CDN f (.+?) size=(-?\d+)", l)
        if mm:
            items.append((mm.group(1).rsplit("/", 1)[-1], int(mm.group(2))))
    return len(items), items


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(1.5)

    voice_dirs = []
    for base in SD_TO:
        root = base + "/MicroMsg"
        n, items = cdn_ls(root, 300)
        print("=" * 76)
        print("%s  → 列出 %s 条" % (root, n))
        if not items:
            print("   （空 / 不存在 / 无权限）")
            continue
        names = sorted({x[0] for x in items})
        for nm in names[:30]:
            print("    " + nm)
        if len(names) > 30:
            print("    ... 共 %d 个" % len(names))

        # 对 32 位 hex 目录尝试 voice2
        hexdirs = [x for x in names if re.fullmatch(r"[0-9a-fA-F]{32}", x)]
        print("  32 位 hex 目录 %d 个: %s" % (len(hexdirs), ", ".join(hexdirs[:5])))
        for h in hexdirs:
            vd = "%s/%s/voice2" % (root, h)
            nv, vi = cdn_ls(vd, 200)
            if vi:
                print("    ✅ %s → %d 个文件" % (vd, nv))
                voice_dirs.append((vd, [x[0] for x in vi]))
            else:
                print("    ✗  %s → 空" % vd)

    print("\n" + "=" * 76)
    print("判据：6 条被救下 vs 2 条对照")
    print("=" * 76)
    if not voice_dirs:
        print("❌ 没找到任何有文件的 voice2 目录。")
        print("   ⇒ 换方向：从 DEX 找 voice2 的实际拼接代码（classes10 的字符串只给模板，")
        print("      变量 ${accountSd} 的取值要静态回溯）。")
        return

    for vd, names in voice_dirs:
        print("\n目录 %s（%d 文件）" % (vd, len(names)))
        digits = [x for x in names if x.split(".")[0].isdigit()]
        print("  纯数字文件名 %d 个，样本: %s" % (len(digits), ", ".join(names[:6])))
        hit = [s for s in SVRS if any(s in x for x in names)]
        c = [s for s in CTRL if any(s in x for x in names)]
        print("  ── 被救下的 6 条：命中 %d" % len(hit))
        for s in SVRS:
            print("     %-22s %s" % (s, "✅ 在" if any(s in x for x in names) else "❌ 不在"))
        print("  ── 对照组 2 条：命中 %d" % len(c))
        for s in CTRL:
            print("     %-22s %s" % (s, "✅ 在" if any(s in x for x in names) else "❌ 不在"))
        print()
        if len(c) == len(CTRL) and len(hit) == 0:
            print("  ⇒ **根因 C 成立**：对照都在、被救下的全缺 ⇒ 微信撤回时删了 silk 文件")
        elif len(c) == len(CTRL) and len(hit) > 0:
            print("  ⇒ **C 排除**：被救下的文件也都在 ⇒ 问题在库字段（回 B）")


if __name__ == "__main__":
    main()
