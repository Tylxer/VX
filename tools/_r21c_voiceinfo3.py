"""R21c 取证（九·交叉验证）：`voiceinfo` 那 6 行是「被删」还是「换了键」。

## 上一轮结论（`_r21c_voiceinfo2.py`，2026-10-25 15:34）
按 `FileName = message.imgPath`（24 hex）查：
- 6 条被救下语音：**0/6 无行**
- 2 条正常对照：**2/2 有行**
⇒ 初步判定「根因 D：撤回删了 voiceinfo 行」。

## 但这条结论有一个未排除的替代解释
`voiceinfo` 的主键是 `FileName`，同时还有 `MsgLocalId`（对应 message.msgId）与 `MsgId`。
「按 FileName 查不到」有两种可能：
- **D-1**：整行被删（真的没了）
- **D-2**：行还在，但 `FileName` 被改成了别的值（imgPath 变了 / 行被 clone 成新名）
⇒ **必须按 `MsgLocalId` 再查一遍**。两种查法都为空才是「整行被删」。

## 最硬的判据：邻域空缺
不去猜哪个键，直接看 **MsgLocalId 的连续段**：
```
select MsgLocalId from voiceinfo where MsgLocalId between 19289000 and 19290120 order by MsgLocalId
```
这 6 条被救下的 msgId 是 19289041/19289057/19289088/19289117/19289824/19290094，
对照是 19289006/19289165。
- 若结果里**恰好跳过**这 6 个、但包含 19289006/19289165 ⇒ **D-1 定案（整行被删）**
- 若这 6 个**都在**结果里 ⇒ 上一轮按 FileName 查的判据错，D 被推翻
- 若整段全空 ⇒ 说明 voiceinfo 不按这个 id 段存，得换思路

## 工具坑（都已踩过）
- SQL 里的单引号会咬断外层引号 ⇒ **外层用双引号**
- 日志残留造成假阳性 ⇒ **记 n0，只读新增行**
- 探针每列截断 60 字符 ⇒ 只取短列
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr.txt"

RESCUED = [19289041, 19289057, 19289088, 19289117, 19289824, 19290094]
CTRL = [19289165, 19289006]


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


def sql_new(q, wait=3.0):
    n0 = len(pull_lines())
    sh("shell", 'am broadcast -a com.lx.wxgroup.action.PROBE --es op label '
                '--es sub sql --es sql "%s" -p com.tencent.mm' % q)
    time.sleep(wait)
    return pull_lines()[n0:]


def emicro_rows(lines):
    """只取 EnMicroMsg.db 那一块的 LABEL 数据行"""
    out, block = [], False
    for l in lines:
        if "LABEL sql db=" in l:
            block = "EnMicroMsg.db" in l
            continue
        if "no such table" in l or "raw err" in l:
            continue
        if block and "LABEL" in l:
            out.append(l.strip())
    return out


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(1.5)
    sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear -p com.tencent.mm")
    time.sleep(1)

    print("=" * 78)
    print("[1] 邻域空缺判据：voiceinfo 里 19289000~19290120 的 MsgLocalId")
    print("=" * 78)
    rows = emicro_rows(sql_new(
        "select MsgLocalId,FileName,Status,TotalLen,FileNowSize from voiceinfo "
        "where MsgLocalId between 19289000 and 19290120 order by MsgLocalId", 4.0))
    present = {}
    for r in rows:
        print("  " + r[:170])
        kv = dict(re.findall(r"(\w+)=([^=\s|]+)", r))
        if "MsgLocalId" in kv:
            try:
                present[int(kv["MsgLocalId"])] = kv
            except ValueError:
                pass

    print("\n  共取到 %d 行" % len(present))

    print("\n" + "=" * 78)
    print("[2] 逐个核：被救下 6 条 vs 对照 2 条")
    print("=" * 78)
    for mid in RESCUED + CTRL:
        kind = "被救下" if mid in RESCUED else "对照"
        hit = present.get(mid)
        print("  %-10s %-6s %s" % (mid, kind,
              ("✅ 在 voiceinfo: " + str(hit)) if hit else "❌ **不在 voiceinfo**"))

    r_in = [m for m in RESCUED if m in present]
    c_in = [m for m in CTRL if m in present]

    print("\n" + "=" * 78)
    print("[3] 结论")
    print("=" * 78)
    print("  被救下在 voiceinfo: %d/%d" % (len(r_in), len(RESCUED)))
    print("  对照  在 voiceinfo: %d/%d" % (len(c_in), len(CTRL)))
    print()
    if len(present) == 0:
        print("  ⚠️ 整段一条没取到 ⇒ voiceinfo 不按 MsgLocalId 存这个区间。")
        print("     不能下结论。需要换成按 FileName like 前缀 / 看该表总行数。")
    elif len(c_in) == len(CTRL) and len(r_in) == 0:
        print("  🔴 **D-1 定案：voiceinfo 整行被删**")
        print("     - 按 FileName（imgPath）查：0/6")
        print("     - 按 MsgLocalId（msgId）查：0/6")
        print("     - 两条对照两种查法都在")
        print("     ⇒ 微信撤回流程删掉了 voiceinfo 那一行。")
        print("       播放器要按 imgPath 查下载记录拿 Status/TotalLen 才能播，")
        print("       查不到 ⇒ 点了没反应。")
        print("     ⇒ **message 行我们保住了，voiceinfo 行没保住。**")
    elif len(r_in) > 0:
        print("  ⚯ 被救下有几条在 voiceinfo ⇒ 上一轮「按 FileName 查 0 行」的判据不成立。")
        print("     真实情况是「行在但 FileName 不同」⇒ 属 D-2（键被改），要重新设计判据。")
    else:
        print("  ⚠️ 对照不全在 ⇒ 数据不足，看明细。")


if __name__ == "__main__":
    main()
