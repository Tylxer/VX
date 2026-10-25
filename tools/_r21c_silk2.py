"""R21c 取证（二·订正）：语音 silk 文件还在不在。

## 为什么上一版失败
`adb shell ls /data/user/0/com.tencent.mm/...` 恒返回 **Permission denied** ——
那是微信的私有目录，shell 用户无权读。模块跑在**微信进程内**，才有那个权限。

## 正解
走已有探针 `op=cdn --es sub ls --es val <目录>`（`SnsCdnProbe.doLs`，用微信进程权限 listFiles）。
输出格式（`SnsCdnProbe.kt:472-483` 逐字）：
```
CDN ls <dir> files=N bytes=M
CDN f <绝对路径> size=123
```

## 判据
6 条被救下的语音（`type=34`，A 已排除）的 `msgSvrId`：
  6139184963485905836 / 3941829116377846651 / 4749556186668879079 /
  1083697330173103688 / 2129905180187988684 / 6524856252354654119

- 文件**缺失** + 对照组（未撤回的正常语音）**存在** ⇒ 根因 C 成立
- 文件**都在** ⇒ C 排除，回到 B（字段污染）
- 对照组也不在 ⇒ 路径判断错，看 `CDN ls` 的 files=N 和样本文件名

## 微信语音目录（真机待确认，按可能性排序）
`/data/user/0/com.tencent.mm/MicroMsg/<hash>/voice2/`         ← 老版本主目录
`/data/user/0/com.tencent.mm/MicroMsg/<hash>/voice2/<id/100>/` ← 老版本分片
`/data/user/0/com.tencent.mm/MicroMsg/<hash>/voice/`
先 ls 父目录 `MicroMsg/<hash>/` 看有哪些候选子目录，比盲猜快。
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr.txt"

HASH = "89476c7fc82a9f977bc8a901d68b7d37"
ROOT = "/data/user/0/com.tencent.mm/MicroMsg/%s" % HASH

VOICE = [
    (19289041, "6139184963485905836"),
    (19289057, "3941829116377846651"),
    (19289088, "4749556186668879079"),
    (19289117, "1083697330173103688"),
    (19289824, "2129905180187988684"),
    (19290094, "6524856252354654119"),
]


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def am(op, extra, wait=2.5):
    cmd = ("am broadcast -a com.lx.wxgroup.action.PROBE --es op %s %s -p com.tencent.mm"
           % (op, extra))
    sh("shell", cmd)
    time.sleep(wait)


def cdn_ls(d, n=500):
    """走微信进程权限列目录，返回 (files数, [(名字, size)])"""
    am("cdn", "--es sub ls --es val '%s' --ei n %d" % (d, n))
    sh("pull", DBG, LOCAL)
    try:
        lines = open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        return 0, []
    nfiles, items = 0, []
    # 只认**最后一次** CDN ls（异步广播可能混入上一轮残留）
    idx = None
    for i, l in enumerate(lines):
        if "CDN ls " + d in l or ("CDN ls " in l and d in l):
            idx = i
    if idx is None:
        return 0, []
    m = re.search(r"files=(\d+)", lines[idx])
    nfiles = int(m.group(1)) if m else 0
    for l in lines[idx + 1:]:
        mm = re.search(r"CDN f (.+?) size=(\d+)", l)
        if not mm:
            if "CDN " in l and "CDN f " not in l:
                break
            continue
        items.append((mm.group(1).rsplit("/", 1)[-1], int(mm.group(2))))
    return nfiles, items


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(1.5)
    am("logclear", "--es x 1", 1)

    print("=" * 74)
    print("[1] 先列 MicroMsg/<hash>/ 看有哪些子目录（比盲猜快）")
    print("=" * 74)
    n, items = cdn_ls(ROOT, 200)
    print("  %s  files=%d" % (ROOT, n))
    names = [x[0] for x in items]
    for nm in sorted(names)[:40]:
        mark = "  ← 语音候选" if "voice" in nm.lower() or nm.lower() in ("silk", "amr") else ""
        if mark or len(names) <= 40:
            print("    %s%s" % (nm, mark))

    # 选目录：优先 voice2，其次 voice，再次任何含 voice 的
    cand_dirs = [d for d in [ROOT + "/voice2", ROOT + "/voice"] ]
    for nm in names:
        if "voice" in nm.lower() and (ROOT + "/" + nm) not in cand_dirs:
            cand_dirs.append(ROOT + "/" + nm)

    print("\n" + "=" * 74)
    print("[2] 逐个候选目录 ls，找被救下那 6 条的 msgSvrId")
    print("=" * 74)
    targets = {svr: mid for mid, svr in VOICE}
    any_found = False
    for d in cand_dirs:
        n, items = cdn_ls(d, 500)
        if n == 0:
            print("  %-70s files=0（不存在或空）" % d)
            continue
        print("  %-70s files=%d" % (d, n))
        names = [x[0] for x in items]
        # 命名规律探测
        digits = [x for x in names if x.split(".")[0].isdigit()]
        print("     纯数字文件名 %d 个；样本: %s"
              % (len(digits), ", ".join(names[:5])))
        hit = [svr for svr in targets if any(svr in x for x in names)]
        if hit:
            any_found = True
            print("     ✅ 命中 %d 条: %s" % (len(hit), ", ".join(hit)))
        else:
            print("     ❌ 这 6 条的 msgSvrId 一个都不在这个目录")

    print("\n" + "=" * 74)
    print("[3] 对照组：消息库里最近几条 type=34 的正常语音")
    print("=" * 74)
    am("label", "--es sub sql --es sql "
       "'select msgId,msgSvrId from message where type=34 order by msgId desc limit 8'")
    sh("pull", DBG, LOCAL)
    try:
        lines = open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        lines = []
    block, ctrl = False, []
    for l in lines:
        if "LABEL sql db=" in l:
            block = "EnMicroMsg.db" in l
            continue
        if "no such table" in l:
            continue
        if block and "LABEL" in l and "msgId=" in l:
            print("  " + l.strip()[:150])
            kv = dict(re.findall(r"(\w+)=([^=\s]+)", l))
            if "msgSvrId" in kv:
                ctrl.append(kv["msgSvrId"])

    if ctrl:
        print("\n  对照组文件存在性（在上面命中的那个目录里找）：")
        best = None
        for d in cand_dirs:
            n, items = cdn_ls(d, 500)
            if n:
                best = (d, [x[0] for x in items])
                break
        if best:
            d, names = best
            print("   对照目录 = %s（files=%d）" % (d, len(names)))
            ok = sum(1 for s in ctrl if any(s in x for x in names))
            for s in ctrl:
                print("     %-22s %s" % (s, "✅ 在" if any(s in x for x in names) else "❌ 不在"))
            print("\n  ⇒ 对照 %d/%d 存在" % (ok, len(ctrl)))
            print("  判据：对照存在 & 被救下的 6 条缺失 ⇒ **根因 C 成立**")
            print("        对照也不在 ⇒ 路径判断错（看 files=%d 里的真实文件名）" % len(names))
        else:
            print("    ⚠️ 所有候选目录 files=0，无法对照")


if __name__ == "__main__":
    main()
