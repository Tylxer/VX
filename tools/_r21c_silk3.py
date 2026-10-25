"""R21c 取证（三）：看清 MicroMsg/<hash>/ 下那 178 个条目到底是什么。

上一版 `_r21c_silk2.py` 打印逻辑写错（`if mark or len(names) <= 40`），
178 个条目一个名字都没打出来，只看到 `voice2`/`voice` 两个候选目录 files=0。

本脚本：把 178 个名字**全打出来**，同时单独判断哪些是目录、哪些含 voice/audio/silk 字样。
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr.txt"

HASH = "89476c7fc82a9f977bc8a901d68b7d37"
ROOT = "/data/user/0/com.tencent.mm/MicroMsg/%s" % HASH


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def cdn_ls(d, n=500):
    cmd = ("am broadcast -a com.lx.wxgroup.action.PROBE --es op cdn "
           "--es sub ls --es val '%s' --ei n %d -p com.tencent.mm" % (d, n))
    sh("shell", cmd)
    time.sleep(3)
    sh("pull", DBG, LOCAL)
    try:
        lines = open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        return 0, []
    # 只认最后一次匹配该目录的 CDN ls
    idx = None
    for i, l in enumerate(lines):
        if "CDN ls " in l and d in l:
            idx = i
    if idx is None:
        return 0, []
    nfiles = 0
    m = re.search(r"files=(\d+)", lines[idx])
    if m:
        nfiles = int(m.group(1))
    items = []
    for l in lines[idx + 1:]:
        mm = re.search(r"CDN f (.+?) size=(-?\d+)", l)
        if not mm:
            continue
        items.append((mm.group(1).rsplit("/", 1)[-1], int(mm.group(2))))
    return nfiles, items


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(1.5)
    sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear -p com.tencent.mm")
    time.sleep(1)

    n, items = cdn_ls(ROOT, 400)
    print("=" * 74)
    print("%s  files=%d  实际取到 %d 条" % (ROOT, n, len(items)))
    print("=" * 74)
    if not items:
        print("❌ 没取到条目。可能：探针没跑 / 日志截断 / n 太小")
        return

    # 目录通常 size 很小（4096 或 0），文件 size 是真实字节
    names = sorted(x[0] for x in items)
    print("\n--- 全部 %d 个名字 ---" % len(names))
    for nm in names:
        print("  " + nm)

    print("\n--- 关键词筛选 ---")
    for kw in ["voice", "audio", "silk", "amr", "sound", "media", "msg"]:
        hit = [x for x in names if kw in x.lower()]
        if hit:
            print("  '%s' 命中 %d: %s" % (kw, len(hit), ", ".join(hit[:10])))

    # 目标 msgSvrId
    VOICE = ["6139184963485905836", "3941829116377846651", "4749556186668879079",
             "1083697330173103688", "2129905180187988684", "6524856252354654119",
             "17994308397865652", "6689996705112806292"]  # 后两个是未撤回对照
    print("\n--- msgSvrId 直接在这 178 个名字里找 ---")
    for svr in VOICE:
        hit = [x for x in names if svr in x]
        print("  %-22s %s" % (svr, ("✅ " + hit[0]) if hit else "❌ 不在根目录"))


if __name__ == "__main__":
    main()
