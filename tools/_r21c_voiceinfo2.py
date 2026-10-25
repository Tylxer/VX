"""R21c 取证（八·修正版）：`voiceinfo` 表里那几条还在不在。

## ⚠️ 上一版两个 bug（必须先讲清楚，否则会误判）
1. **SQL 引号被咬断**：`--es sql 'select ... where FileName='xxx''` ——
   内层单引号把外层单引号截断，shell 传下去变成双引号标识符，
   SQLite 报 `unrecognized token: "09142810..."`（把 hex 当标识符）。
   ⇒ **改法**：外层用双引号（hex 里没有 `$`，安全）。
2. **日志残留造成假阳性**：上一版没 `logclear`，`parse()` 把**上一轮** query
   （15:31:42 那次 imgPath 查询）的行也当成本轮结果 ⇒ 8 条全判「voiceinfo 有行」，
   **[4] 的「D 排除」是假的，作废。**
   ⇒ **改法**：查询前记下日志当前行数 `n0`，pull 后只读 `lines[n0:]`。
      比每查一次就 logclear 快得多（上一轮试过每段清日志，直接超时）。

## 为什么查 voiceinfo
`voice-root` 在 `classes4.dex` 找到的真实 SQL：
```sql
DELETE FROM voiceinfo WHERE FileName IN (SELECT imgPath FROM message WHERE talker IN ...
```
`voiceinfo` 是语音下载/续传表，主键 `filename`，与 `message.imgPath` 等值对应。

关键前提：`restore_view=false` ⇒ `applyOne()` 在 `RevokeRescue.kt:264` 直接 return
⇒ 我们**从未写过 message 行**（type/content/imgPath/status/flag 全原样，已实测）。
那「播不了」必是**别处数据没了** —— 最可能就是 voiceinfo 那条记录。

## 判据
| 观察 | 结论 |
|---|---|
| 对照有、被救下**全没有** | **根因 D 成立**（撤回删了 voiceinfo 行） |
| 两组都有 | D 排除，回「文件本体」（需 VFS API） |
| 对照也没有 | 这张表不按 imgPath 存 ⇒ 先看表结构，别硬套 |
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


def pull_lines():
    sh("pull", DBG, LOCAL)
    try:
        return open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        return []


def sql_new(q, wait=2.6):
    """执行 SQL，**只返回本次新增的日志行**（消除残留假阳性）。"""
    n0 = len(pull_lines())
    # ⚠️ 外层双引号：SQL 里含单引号字面量，用单引号包会咬断
    cmd = ('am broadcast -a com.lx.wxgroup.action.PROBE --es op label '
           '--es sub sql --es sql "%s" -p com.tencent.mm' % q)
    sh("shell", cmd)
    time.sleep(wait)
    lines = pull_lines()
    return lines[n0:]


def kv(line):
    return dict(re.findall(r"(\w+)=([^=\s|]+)", line))


def blocks(lines):
    """按库切块：返回 [(dbname, [row lines])]"""
    out, cur, rows = [], None, []
    for l in lines:
        if "LABEL sql db=" in l:
            if cur:
                out.append((cur, rows))
            m = re.search(r"db=(\S+)", l)
            cur, rows = (m.group(1) if m else "?"), []
            continue
        if "no such table" in l or "raw err" in l:
            continue
        if cur and "LABEL" in l:
            rows.append(l.strip())
    if cur:
        out.append((cur, rows))
    return out


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(1.5)
    sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear -p com.tencent.mm")
    time.sleep(1)

    print("=" * 78)
    print("[1] voiceinfo 表结构（哪几列、在哪个库）")
    print("=" * 78)
    for db, rows in blocks(sql_new("pragma table_info(voiceinfo)")):
        if not rows:
            continue
        print("  db=%s" % db)
        for r in rows[:20]:
            print("    " + r[:170])

    print("\n" + "=" * 78)
    print("[2] 逐条查（每人一次，避免 IN 列表 + 引号问题）")
    print("=" * 78)
    found = {}
    detail = {}
    for mid, p in IMGPATH.items():
        rows_all = blocks(sql_new("select FileName,length(FileName) l "
                                  "from voiceinfo where FileName='%s'" % p, 2.2))
        hit_rows = []
        for db, rows in rows_all:
            if "EnMicroMsg" not in db:
                continue
            for r in rows:
                # ⚠️ 必须真的含那一串 hex，不能只看长度（上一版就错在这）
                if p in r:
                    hit_rows.append(r)
        found[mid] = bool(hit_rows)
        detail[mid] = hit_rows
        kind = "被救下" if mid in RESCUED else "对照"
        print("  %-10s %-6s %-30s %s"
              % (mid, kind, p, "✅ 有行" if hit_rows else "❌ 无行"))
        for h in hit_rows:
            print("        " + h[:170])

    print("\n" + "=" * 78)
    print("[3] 判据（本轮是真数据，上一版的结论已作废）")
    print("=" * 78)
    r_in = [m for m in RESCUED if found.get(m)]
    c_in = [m for m in CTRL if found.get(m)]
    print("  被救下在 voiceinfo 里有行: %d/%d  %s" % (len(r_in), len(RESCUED), r_in))
    print("  对照  在 voiceinfo 里有行: %d/%d  %s" % (len(c_in), len(CTRL), c_in))
    print()
    if len(c_in) == len(CTRL) and len(r_in) == 0:
        print("  🔴 **根因 D 成立**：对照都有、被救下的全没有")
        print("     ⇒ 撤回删的是 voiceinfo 那一行，播放器查下载记录失败")
        print("     ⇒ 修复：record() 时把 voiceinfo 行一起快照，恢复时 INSERT 回去")
    elif len(c_in) == len(CTRL) and len(r_in) == len(RESCUED):
        print("  ⚪ **D 排除**：两组都有 voiceinfo 行 ⇒ 问题不在这张表")
        print("     ⇒ 回「文件本体」：需要 VFS API 判存在（等 voice-root §6）")
    elif not c_in:
        print("  ⚠️ 对照组也没行 ⇒ 这张表不按 imgPath 存，或 name 不是 FileName")
        print("     先看 [1] 表结构再定判据，别硬套")
    else:
        print("  ⚠️ 混合结果（被救下 %d/%d、对照 %d/%d）⇒ 看上面明细人工判"
              % (len(r_in), len(RESCUED), len(c_in), len(CTRL)))


if __name__ == "__main__":
    main()
