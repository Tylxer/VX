"""R21c 取证（七·决定性）：查 imgPath —— 语音文件的**真实路径就在这一列**。

## 依据（lane voice-root 静态取证，classes4.dex 里一条真实 SQL）
```sql
DELETE FROM voiceinfo WHERE FileName IN (SELECT imgPath FROM message WHERE talker IN ...
```
`voiceinfo`（语音续传/下载表，主键 `filename`）与 `message.imgPath` 做等值 join
⇒ **语音消息的本地文件路径就存在 `message.imgPath`**（与图片共用这一列）。

这比盲猜 `voice2/<msgSvrId>` 强得多 —— 拿到了就直接是绝对路径。

## 本脚本一次判完 B / C
对 6 条被救下 + 对照（未撤回）各取 `imgPath / status / flag / msgSvrId`：

| 观察 | 结论 |
|---|---|
| `imgPath` 非空 且 文件存在 | C 排除、B 排除 ⇒ 问题在别处（要查 lvbuffer / 解码） |
| `imgPath` 非空 但 文件不存在 | **C 成立**（文件被删，引用还在） |
| `imgPath` 为空 | **B 变体成立**（微信单独清了 imgPath，那条 update 不带 type 所以没被我们拦） |
| 对照也 imgPath 为空 | 说明这一版微信语音不用 imgPath（这列判断失效），要换方案 |

## 工具坑
- 探针每列截断 60 字符 ⇒ imgPath 可能超长，本脚本用 `length(imgPath)` 辅助判断完整性
- 用 `substr(imgPath,1,55)` 取前 55 字符（足够看出目录结构），完整值用 length 判
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


def am(extra, wait=3.0):
    sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE %s -p com.tencent.mm" % extra)
    time.sleep(wait)


def read_dbg():
    sh("pull", DBG, LOCAL)
    try:
        return open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        return []


def sql(q, wait=3.0):
    am("--es op label --es sub sql --es sql '%s'" % q, wait)


def parse_emicro(lines):
    """只取 EnMicroMsg.db 那一块的 LABEL 行"""
    out = []
    block = False
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
    sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear -p com.tencent.mm")
    time.sleep(1)

    allids = RESCUED + CTRL
    ids = ",".join(str(i) for i in allids)

    print("=" * 78)
    print("[1] imgPath / status / flag / msgSvrId（决定 B vs C）")
    print("=" * 78)
    sql("select msgId,type,length(imgPath) il,substr(imgPath,1,40) ip,"
        "status,flag,msgSvrId from message where msgId in (%s)" % ids)
    lines = read_dbg()
    rows = {}
    for l in parse_emicro(lines):
        print("  " + l[:240])
        kv = dict(re.findall(r"(\w+)=([^=\s]+)", l))
        if "msgId" in kv:
            rows[kv["msgId"]] = kv

    print("\n" + "=" * 78)
    print("[2] 判据表格")
    print("=" * 78)
    print("  %-10s %-6s %-8s %-14s %-8s %s" % ("msgId", "type", "imgLen", "status/flag", "kind", "imgPath 前 40"))
    for mid in allids:
        r = rows.get(str(mid))
        if not r:
            print("  %-10s 未取到" % mid)
            continue
        kind = "被救下" if mid in RESCUED else "对照"
        print("  %-10s %-6s %-8s %-14s %-8s %s"
              % (mid, r.get("type", "?"), r.get("il", "?"),
                 "%s/%s" % (r.get("status", "?"), r.get("flag", "?")),
                 kind, r.get("ip", "?")))

    print("\n" + "=" * 78)
    print("[3] 汇总判断")
    print("=" * 78)
    def ilen(mid):
        r = rows.get(str(mid))
        if not r:
            return None
        v = r.get("il")
        if v in (None, "NULL", ""):
            return 0
        try:
            return int(v)
        except ValueError:
            return None

    res_len = [(m, ilen(m)) for m in RESCUED]
    ctrl_len = [(m, ilen(m)) for m in CTRL]
    print("  被救下 imgPath 长度: %s" % res_len)
    print("  对照   imgPath 长度: %s" % ctrl_len)

    res_nonnull = [m for m, n in res_len if n and n > 0]
    ctrl_nonnull = [m for m, n in ctrl_len if n and n > 0]

    if not ctrl_nonnull:
        print("\n  ⚠️ 对照组 imgPath 也全是空/NULL ⇒ **imgPath 不是这一版微信存语音路径的列**，")
        print("     本判据失效 ⇒ 换方向：查 voiceinfo 表（FileName 主键）。")
    elif len(res_nonnull) == len(RESCUED):
        print("\n  ✅ 6 条 imgPath 全非空（对照也非空）")
        print("     ⇒ **B 变体（imgPath 被清）不成立**")
        print("     ⇒ C 还需一步：那 6 个路径对应的文件在不在？")
    else:
        miss = [m for m in RESCUED if m not in res_nonnull]
        print("\n  🔴 被救下里有 %d 条 imgPath 为空: %s" % (len(miss), miss))
        print("     ⇒ **B 变体成立**：微信单独发了一条「只清 imgPath」的 update，")
        print("        它不带 type=10000 所以没被 AntiRevoke 拦下。")
        print("     ⇒ 修复方向：`queryOriginal()` 的 listOf(...) 扩进 imgPath/msgSvrId/status/flag")

    print("\n" + "=" * 78)
    print("[4] 若有非空 imgPath，逐个查文件存在性")
    print("=" * 78)
    paths = []
    for mid in allids:
        r = rows.get(str(mid))
        if r and r.get("ip") and r.get("ip") != "NULL":
            paths.append((mid, r["ip"]))
    if not paths:
        print("  没有短路径可直接用（imgPath 被 substr 截断了）⇒ 用 voiceinfo 表反查")
        sql("select FileName,msgId from voiceinfo order by rowid desc limit 8", 3.5)
        for l in parse_emicro(read_dbg()):
            print("  " + l[:200])
    else:
        print("  （imgPath 被截断到 40 字符，不一定能直接 ls。")
        print("   下一步：用完整 imgPath 走 cdn sub=ls，或直接 pull 到本地判存在）")
        for mid, p in paths:
            print("  msgId=%-10d %s" % (mid, p))


if __name__ == "__main__":
    main()
