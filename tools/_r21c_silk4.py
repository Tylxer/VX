"""R21c 取证（四）：语音文件到底在哪。

## 已排除
- `/data/user/0/com.tencent.mm/MicroMsg/<hash>/` 根目录 178 个条目**全是文件，无子目录**
  ⇒ 该目录下没有 `voice2/`、没有 `voice/`
- `/data/user/0/com.tencent.mm/MicroMsg/<hash>/voice2`  → files=0
- `/data/user/0/com.tencent.mm/MicroMsg/<hash>/voice`   → files=0
- `adb shell ls` 各种路径 → Permission denied（shell 无权，必须走微信进程内探针）

## 本脚本两条腿
### 腿 1：候选目录轮询（含外部存储，Android 11+ scoped storage 下 adb 读不到、进程内能读）
### 腿 2：**查 `WxFileIndex.db`** —— 微信自己的文件索引库，根目录里就有这个文件。
        语音文件的绝对路径很可能直接记在里面。这是最短路径。

## 判据（不变）
6 条被救下语音的 msgSvrId：
  6139184963485905836 / 3941829116377846651 / 4749556186668879079 /
  1083697330173103688 / 2129905180187988684 / 6524856252354654119
对照组（未撤回）：17994308397865652 / 6689996705112806292

- 被救下的缺失 + 对照组存在 ⇒ 根因 C（silk 被删）成立
- 都在 ⇒ C 排除，回 B（字段污染）
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr.txt"

HASH = "89476c7fc82a9f977bc8a901d68b7d37"

# 微信语音/媒体目录候选（按可能性排序）
CAND = [
    "/data/user/0/com.tencent.mm/MicroMsg/%s/prpl_media" % HASH,
    "/data/user/0/com.tencent.mm/MicroMsg/%s/voice2" % HASH,
    "/sdcard/Android/data/com.tencent.mm/MicroMsg/%s/voice2" % HASH,
    "/sdcard/tencent/MicroMsg/%s/voice2" % HASH,
    "/storage/emulated/0/tencent/MicroMsg/%s/voice2" % HASH,
    "/data/user/0/com.tencent.mm/files",
    "/data/user/0/com.tencent.mm/cache",
    "/sdcard/tencent/MicroMsg",
    "/sdcard/Android/data/com.tencent.mm/MicroMsg",
]

SVRS = ["6139184963485905836", "3941829116377846651", "4749556186668879079",
        "1083697330173103688", "2129905180187988684", "6524856252354654119"]
CTRL = ["17994308397865652", "6689996705112806292"]


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def am(extra, wait=3.0):
    cmd = ("am broadcast -a com.lx.wxgroup.action.PROBE %s -p com.tencent.mm" % extra)
    sh("shell", cmd)
    time.sleep(wait)


def read_dbg():
    sh("pull", DBG, LOCAL)
    try:
        return open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        return []


def cdn_ls(d, n=60):
    am("--es op cdn --es sub ls --es val '%s' --ei n %d" % (d, n))
    lines = read_dbg()
    idx = None
    for i, l in enumerate(lines):
        if "CDN ls " in l and d in l:
            idx = i
    if idx is None:
        return None  # 探针没回话
    m = re.search(r"files=(\d+)", lines[idx])
    nf = int(m.group(1)) if m else -1
    items = []
    for l in lines[idx + 1:]:
        mm = re.search(r"CDN f (.+?) size=(-?\d+)", l)
        if mm:
            items.append((mm.group(1).rsplit("/", 1)[-1], int(mm.group(2))))
    return nf, items


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(1.5)

    print("=" * 76)
    print("腿 1：候选目录轮询")
    print("=" * 76)
    alive = []
    for d in CAND:
        r = cdn_ls(d, 40)
        if r is None:
            print("  %-64s 探针无响应" % d)
            continue
        nf, items = r
        if nf <= 0:
            print("  %-64s files=%d" % (d, nf))
            continue
        names = [x[0] for x in items]
        hit = [s for s in SVRS if any(s in x for x in names)]
        print("  %-64s files=%-4d 样本=%s" % (d, nf, ", ".join(names[:4])))
        if hit:
            print("        ✅ 命中 msgSvrId %d 条: %s" % (len(hit), ", ".join(hit)))
        alive.append((d, nf, names))

    print("\n" + "=" * 76)
    print("腿 2：查 WxFileIndex.db（微信文件索引库）")
    print("=" * 76)
    # 该库在 MicroMsg/<hash>/ 下。label 探针遍历所有库，所以能查到
    am("--es op label --es sub tables", 4.0)
    lines = read_dbg()
    tok = [l for l in lines if "WxFileIndex" in l or "fileindex" in l.lower()]
    if tok:
        print("  探针提到的 WxFileIndex 相关行：")
        for l in tok[-12:]:
            print("    " + l.strip()[:170])
    else:
        print("  tables 输出里没提到 WxFileIndex（可能在遍历范围外）")

    print("\n  尝试直接查它的表结构 —— 先看有哪些表：")
    am("--es op label --es sub sql --es sql 'select name from sqlite_master where type=\"table\"'", 3.5)
    lines = read_dbg()
    block = False
    tables = []
    for l in lines:
        if "LABEL sql db=" in l:
            # 只认非 EnMicroMsg 的库（WxFileIndex 是独立库）
            block = "WxFileIndex" in l
            if block:
                print("    " + l.strip()[:170])
            continue
        if block and "no such table" in l:
            continue
        if block and "LABEL" in l:
            print("    " + l.strip()[:170])
    if not any("WxFileIndex" in l for l in lines):
        print("    （label 探针没遍历到 WxFileIndex.db —— 它可能不在探针的库清单里）")

    print("\n" + "=" * 76)
    print("小结")
    print("=" * 76)
    if alive:
        print("  有内容的目录 %d 个：%s" % (len(alive), ", ".join(d for d, _, _ in alive)))
        for d, nf, names in alive:
            h = [s for s in SVRS if any(s in x for x in names)]
            c = [s for s in CTRL if any(s in x for x in names)]
            if h or c:
                print("    %s：被救下命中 %d / 对照命中 %d" % (d, len(h), len(c)))
    else:
        print("  ⚠️ 所有候选目录 files=0 ⇒ 语音文件路径判断全错，"
              "需要从 DEX 静态找（搜 `voice2` 字符串的引用点）")


if __name__ == "__main__":
    main()
