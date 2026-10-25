"""R21c 取证（二）：语音 silk 文件还在不在。

## 判据
6 条被救下的语音，`type` 仍是 34（A 已排除）。若 `voice2/` 下对应 `msgSvrId`
的音频文件**已被微信在撤回流程里删掉** ⇒ 行在、文件没了 ⇒ 点播放失败 = 真 bug（根因 C）。

## msgSvrId（真机实测，2026-10-25 15:24）
| msgId    | msgSvrId             | content                        |
|----------|----------------------|--------------------------------|
| 19289041 | 6139184963485905836  | wxid_3dmyz2iqavq822:30478:0    |
| 19289057 | 3941829116377846651  | wxid_3dmyz2iqavq822:12424:0    |
| 19289088 | 4749556186668879079  | wxid_3dmyz2iqavq822:19648:0    |
| 19289117 | 1083697330173103688  | wxid_3dmyz2iqavq822:2684:0     |
| 19289824 | 2129905180187988684  | wxid_3dmyz2iqavq822:20785:1:   |
| 19290094 | 6524856252354654119  | wxid_xlrbelf6hv0w22:2860:1:    |

## 微信语音文件两种命名（都要试）
1. **裸 msgSvrId**：`voice2/<msgSvrId>` 或 `voice2/<msgSvrId>.silk` / `.amr`
2. **老版本按 msgId 分片**：`voice2/<msgId/100>/msg_<msgId>.amr`（几百条一个子目录）

## 必须同时做的**对照**（否则判据不成立）
只查「文件不在」推不出「被删」—— 也可能本来就在别的目录。
⇒ 必须再找一条**没被撤回的正常语音**，确认它在预期位置存在。
对照样本从消息库现取：`select msgId,msgSvrId from message where type=34 order by msgId desc limit 5`
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr.txt"

HASH = "89476c7fc82a9f977bc8a901d68b7d37"

VOICE = [
    (19289041, 6139184963485905836),
    (19289057, 3941829116377846651),
    (19289088, 4749556186668879079),
    (19289117, 1083697330173103688),
    (19289824, 2129905180187988684),
    (19290094, 6524856252354654119),
]


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def am(op, extra=""):
    return sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE --es op %s %s "
                       "-p com.tencent.mm" % (op, extra))


def ls(d):
    """返回 (存在?, 条目列表)"""
    out = sh("shell", "ls -la '%s' 2>&1" % d)
    if "No such file" in out or "Permission denied" in out or not out.strip():
        return False, [out.strip()[:120]]
    return True, [l for l in out.splitlines() if l.strip()]


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(1.5)

    print("=" * 72)
    print("[0] 先定位 voice2 到底在哪（三个候选路径）")
    print("=" * 72)
    candidates = [
        "/data/user/0/com.tencent.mm/MicroMsg/%s/voice2" % HASH,
        "/data/data/com.tencent.mm/MicroMsg/%s/voice2" % HASH,
        "/sdcard/Android/data/com.tencent.mm/MicroMsg/%s/voice2" % HASH,
        "/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg/%s/voice2" % HASH,
    ]
    found = None
    for c in candidates:
        ok, lines = ls(c)
        print("  %-70s %s" % (c, "✅ 存在 %d 条" % (len(lines) - 1) if ok else "❌ " + lines[0]))
        if ok and found is None:
            found = c

    if not found:
        print("\n⚠️ 四个候选都没有 → 换策略：全盘搜 voice2 目录")
        out = sh("shell", "find /data/user/0/com.tencent.mm -type d -name voice2 2>/dev/null")
        print(out[:600])
        m = re.search(r"(/\S*voice2)", out)
        if m:
            found = m.group(1)
            print("  ⇒ 找到 %s" % found)
    if not found:
        print("\n❌ 找不到 voice2，本判据无法完成。需要 root 或换路径。")
        return

    print("\n" + "=" * 72)
    print("[1] 被救下的 6 条：msgSvrId 对应文件在不在")
    print("=" * 72)
    _, entries = ls(found)
    names = set()
    for l in entries[1:]:
        parts = l.split()
        if parts:
            names.add(parts[-1])
    print("  voice2 下共 %d 个条目，样本：" % len(names))
    for n in sorted(names)[:8]:
        print("    " + n)
    # 找出命名规律：有没有纯数字文件名
    digits = [n for n in names if n.split(".")[0].isdigit()]
    print("  纯数字文件名 %d 个 ⇒ 命名规律 = %s"
          % (len(digits), "裸 msgSvrId" if digits else "需看样本"))

    print()
    hit = 0
    for mid, svr in VOICE:
        cand = [str(svr), str(svr) + ".silk", str(svr) + ".amr", str(svr) + ".slk"]
        got = [c for c in cand if c in names]
        # 老式分片：voice2/<msgId/100>/msg_<msgId>.amr
        alt = "msg_%d.amr" % mid
        alt_dir = "%s/%d" % (found, mid // 100)
        alt_ok = False
        if not got:
            ok2, e2 = ls(alt_dir)
            alt_ok = ok2 and any(alt == x.split()[-1] for x in e2[1:])
        status = "✅ 文件在: " + ",".join(got) if got else (
            "✅ 老式分片在" if alt_ok else "❌ **文件不存在**")
        if got or alt_ok:
            hit += 1
        print("  msgId=%-10d msgSvrId=%-20d %s" % (mid, svr, status))

    print("\n  ⇒ 6 条里文件存在 %d 条 / 缺失 %d 条" % (hit, len(VOICE) - hit))

    print("\n" + "=" * 72)
    print("[2] 对照组：找几条**没被撤回**的正常语音，确认它们文件在")
    print("=" * 72)
    am("label", "--es sub sql --es sql "
       "'select msgId,msgSvrId,type from message where type=34 order by msgId desc limit 6'")
    time.sleep(2.5)
    sh("pull", DBG, LOCAL)
    try:
        lines = open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        lines = []
    block = False
    ctrl = []
    for line in lines:
        if "LABEL sql db=" in line:
            block = "EnMicroMsg.db" in line
            continue
        if "no such table" in line:
            continue
        if block and "LABEL" in line and "msgId=" in line:
            print("  " + line.strip()[:150])
            kv = dict(re.findall(r"(\w+)=([^=\s]+)", line))
            if "msgSvrId" in kv:
                ctrl.append(int(kv["msgSvrId"]))
    if ctrl:
        print()
        for svr in ctrl:
            ok = any(str(svr) in n for n in names)
            print("  对照 msgSvrId=%-20d %s" % (svr, "✅ 文件在" if ok else "❌ 文件也不在"))
        n_ok = sum(1 for s in ctrl if any(str(s) in n for n in names))
        print("\n  ⇒ 对照组 %d/%d 存在" % (n_ok, len(ctrl)))
        print("  判据：对照存在 & 被救下的缺失 ⇒ **根因 C 成立（微信撤回时删了 silk 文件）**")
        print("        对照也不在           ⇒ 说明文件根目录判断错了，重查路径")
    else:
        print("  ⚠️ 没取到对照样本（可能这 6 条就是最近的全部语音）")


if __name__ == "__main__":
    main()
