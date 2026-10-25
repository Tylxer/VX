"""R21c 取证（八·最关键）：`voiceinfo` 表里那几条还在不在。

## 为什么这条比查文件更可能命中

lane voice-root 在 `classes4.dex` 找到的真实 SQL：
```sql
DELETE FROM voiceinfo WHERE FileName IN (SELECT imgPath FROM message WHERE talker IN ...
```
`voiceinfo` 是**语音下载/续传表**，主键 `filename`，与 `message.imgPath` 等值对应。

关键推理：**我们根本没改过 message 行**（`restore_view=false` ⇒ `applyOne()` 在 :264 直接 return）。
既然 type/content/imgPath 全是原样，那「播不了」唯一合理的解释就是**播放器依赖的另一处数据没了** ——
最可能就是 `voiceinfo` 里那条记录被撤回流程删掉了（或者状态被改了）。

播放语音时微信要做的：`message.imgPath` → 查 `voiceinfo` 拿下载状态/格式 → 打开文件。
**任一环断掉都是「播不了」，但只有 voiceinfo 断了才会表现为「行在、气泡在、点了没声」。**

## 判据
| 观察 | 结论 |
|---|---|
| 被救下的 `voiceinfo` 行**缺失**、对照**存在** | **根因 D 成立**：撤回流程删了 voiceinfo 行 |
| 都被救下的行在、但状态字段与对照不同 | 状态被改 ⇒ 同样是 D |
| 两组的行数与字段完全一致 | D 排除，回到「文件本体」 |

## 工具
`label --es sub sql` 会遍历全部库，只认 `LABEL sql db=.../EnMicroMsg.db` 之后的块。
列长度用 `length()`，避免 60 字符截断。
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr.txt"

RESCUED = [19289041, 19289057, 19289088, 19289117, 19289824, 19290094]
CTRL = [19289165, 19289006]

IMGPATH = {
    19289041: "46142710252654b6378ddb4103",
    19289057: "09142810252654b6378349f100",
    19289088: "16142910252654b63783b00103",
    19289117: "55142910252654b6378d32f100",
    19289824: "33145810252654b63780c01104",
    19290094: "5615081025263e6e1a38aef106",
    19289165: "05143110252654b6378e583103",
    19289006: "59142610252654b6378236f102",
}


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


def sql(q, wait=3.5):
    am("--es op label --es sub sql --es sql '%s'" % q, wait)
    return parse(read_dbg())


def parse(lines):
    out, block = [], False
    for l in lines:
        if "LABEL sql db=" in l:
            block = "EnMicroMsg.db" in l
            continue
        if "no such table" in l:
            continue
        if block and "LABEL" in l:
            out.append(l.strip())
    return out


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(1.5)

    print("=" * 78)
    print("[1] voiceinfo 表结构（确认列名，别猜）")
    print("=" * 78)
    for l in sql("pragma table_info(voiceinfo)", 3.5):
        print("  " + l[:180])

    print("\n" + "=" * 78)
    print("[2] 这 8 条 imgPath 在 voiceinfo 里有没有行")
    print("=" * 78)
    paths = "','".join(IMGPATH.values())
    for l in sql("select FileName from voiceinfo where FileName in ('%s')" % paths, 3.5):
        print("  " + l[:180])

    print("\n" + "=" * 78)
    print("[3] 逐条对照：每人查一次（避免 IN 列表被截断）")
    print("=" * 78)
    found = {}
    for mid, p in IMGPATH.items():
        rows = sql("select FileName,length(FileName) l from voiceinfo "
                   "where FileName='%s'" % p, 2.2)
        hit = [r for r in rows if p in r or "l=26" in r]
        found[mid] = bool(hit)
        kind = "被救下" if mid in RESCUED else "对照"
        print("  %-10s %-6s %-30s %s"
              % (mid, kind, p, "✅ voiceinfo 有行" if hit else "❌ **voiceinfo 无行**"))
        for h in hit:
            print("        " + h[:170])

    print("\n" + "=" * 78)
    print("[4] 判据")
    print("=" * 78)
    r_in = [m for m in RESCUED if found.get(m)]
    c_in = [m for m in CTRL if found.get(m)]
    print("  被救下在 voiceinfo 里的: %d/%d  %s" % (len(r_in), len(RESCUED), r_in))
    print("  对照  在 voiceinfo 里的: %d/%d  %s" % (len(c_in), len(CTRL), c_in))
    print()
    if len(c_in) == len(CTRL) and len(r_in) == 0:
        print("  🔴 **根因 D 成立**：对照都有 voiceinfo 行、被救下的全没有")
        print("     ⇒ 微信撤回时删的是 `voiceinfo` 那一行（不是文件），")
        print("        播放器查下载记录失败 ⇒ 点了没反应")
        print("     ⇒ 修复方向：`record()` 时把 voiceinfo 行一起快照进台账，")
        print("        恢复时 INSERT 回去。**比 hook 文件删除便宜得多**。")
    elif len(c_in) == len(CTRL) and len(r_in) == len(RESCUED):
        print("  ⚪ D 排除：两组都有 voiceinfo 行 ⇒ 问题不在这张表")
        print("     回到「文件本体」：需要 VFS API 判存在（voice-root §6）")
    elif not c_in:
        print("  ⚠️ 对照组也没有 voiceinfo 行 ⇒ 这张表可能不按 imgPath 存（或语音不走它）")
        print("     需要先看 [1] 的表结构和 [3] 的实际输出，别硬套判据")
    else:
        print("  ⚠️ 结果混合：被救下 %d/%d、对照 %d/%d —— 需要人工看上面明细"
              % (len(r_in), len(RESCUED), len(c_in), len(CTRL)))


if __name__ == "__main__":
    main()
