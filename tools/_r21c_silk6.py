"""R21c 取证（六）：voice2 的分片结构。

## 上一版的判据错误（必须记住）
`SnsCdnProbe.scanDir`（`SnsCdnProbe.kt:575-590`）里：
```kotlin
for (f in list) {
    if (f.isDirectory) continue   // ← 子目录被跳过、不打日志
    files++
    ...
}
```
⇒ `CDN ls <dir> files=0` **有三种含义**，探针区分不了：
  ① 目录不存在  ② 目录存在但为空  ③ **目录存在但里面全是子目录**

微信 `voice2/` 正是第 ③ 种：老式布局是 `voice2/<msgId/100>/msg_<msgId>.amr`，
所以 `voice2` 本身 `files=0` 完全正常 —— 我上一版把它读成「不存在」，**推论错了**。

## 本脚本
直接去猜分片目录，问探针「那里面有哪些文件」。
msgId 分片：`voice2/<msgId/100>/`，文件名候选 `msg_<msgId>.amr` / `msg_<msgId>` / `<msgId>.amr`

被救下 6 条：19289041 / 19289057 / 19289088 / 19289117 / 19289824 / 19290094
分片 = 192890 / 192890 / 192890 / 192891 / 192898 / 192900
对照 2 条  ：19289165 → 192891, 19289006 → 192890
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr.txt"

HASH = "89476c7fc82a9f977bc8a901d68b7d37"

# voice2 的两个可能根（scoped storage 后微信多放外部私有目录）
VROOTS = [
    "/sdcard/Android/data/com.tencent.mm/MicroMsg/%s/voice2" % HASH,
    "/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg/%s/voice2" % HASH,
    "/data/user/0/com.tencent.mm/MicroMsg/%s/voice2" % HASH,
]

TARGETS = {  # msgId -> 类型
    19289041: "RESCUED", 19289057: "RESCUED", 19289088: "RESCUED",
    19289117: "RESCUED", 19289824: "RESCUED", 19290094: "RESCUED",
    19289165: "CTRL", 19289006: "CTRL",
}


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def am(extra, wait=2.8):
    sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE %s -p com.tencent.mm" % extra)
    time.sleep(wait)


def read_dbg():
    sh("pull", DBG, LOCAL)
    try:
        return open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        return []


def cdn_ls(d, n=300):
    am("--es op cdn --es sub ls --es val '%s' --ei n %d" % (d, n))
    lines = read_dbg()
    idx = None
    for i, l in enumerate(lines):
        if "CDN ls " in l and d in l:
            idx = i
    if idx is None:
        return None
    items = []
    for l in lines[idx + 1:]:
        mm = re.search(r"CDN f (.+?) size=(-?\d+)", l)
        if mm:
            items.append((mm.group(1).rsplit("/", 1)[-1], int(mm.group(2))))
    return items


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(1.5)

    shards = sorted({mid // 100 for mid in TARGETS})
    print("要查的分片目录（voice2/<msgId/100>）：%s" % shards)

    found_root = None
    for vr in VROOTS:
        print("\n" + "=" * 76)
        print("根目录候选: %s" % vr)
        print("=" * 76)
        any_hit = False
        for sd in shards:
            d = "%s/%d" % (vr, sd)
            items = cdn_ls(d, 300)
            if items is None:
                print("  %-60s 探针无响应" % d)
                continue
            if not items:
                print("  %-60s 空（或无权限）" % d)
                continue
            any_hit = True
            names = [x[0] for x in items]
            print("  %-60s **%d 个文件**" % (d, len(names)))
            print("       样本: %s" % ", ".join(names[:8]))
            # 各 msgId 命中
            for mid, kind in sorted(TARGETS.items()):
                if mid // 100 != sd:
                    continue
                hit = [x for x in names if str(mid) in x]
                tag = "🔴被救下" if kind == "RESCUED" else "🟢对照"
                print("       %s msgId=%-9d %s" % (tag, mid,
                      ("✅ " + hit[0]) if hit else "❌ 无对应文件"))
        if any_hit:
            found_root = vr
            break

    print("\n" + "=" * 76)
    print("结论")
    print("=" * 76)
    if found_root:
        print("  ✅ voice2 根 = %s" % found_root)
        print("  （voice2 本身 files=0 是正常的 —— 里面全是 <msgId/100> 分片子目录）")
    else:
        print("  ❌ 三个根目录 × %d 个分片全空。" % len(shards))
        print("  ⇒ 分片规则可能不是 msgId/100。下一手：给探针加 `sub=lsi`（列子目录名），")
        print("     因为 scanDir 的 `if (f.isDirectory) continue` 让现有探针**看不见目录**。")


if __name__ == "__main__":
    main()
