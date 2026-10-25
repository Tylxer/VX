"""R21c 验收：`HintTextLogic` 的语音时长单位（毫秒）真机对账。

## 为什么要这一步
lane `hint-content` 的 §3 用「量级排除法」论证 `content` 第 2 段是**毫秒**
（`30478` 若按秒 = 8.46 小时，不可能；按毫秒 = 30.5 秒，合理）。但那是**间接**论证。
本脚本做端到端对账：把 6 条语音的 `content` 第 2 段，与 `voiceinfo.TotalLen`
（字节数）交叉比对。

## 对账逻辑
silk 音频码率约 **6~24 kbps**（微信语音常见 ~10kbps）。
- `TotalLen=35827` 字节，若 30 秒 ⇒ 35827/30 ≈ 1194 B/s ≈ **9.5 kbps** ⇒ 合理
- 若时长单位是秒（30478 秒 = 8.5 小时）⇒ 35827/30478 ≈ 1.2 B/s ⇒ **荒谬**

⇒ 只要 `TotalLen / 秒数` 落在 500~4000 B/s 区间，就证明「第 2 段是毫秒」。

## 但被救下的 6 条 voiceinfo 已被删（根因 D）
⇒ 拿不到它们的 TotalLen。所以用**同群的其他正常语音**做旁证：
取该 group 里任意几条 `type=34` 且 voiceinfo 有行的，验证
`TotalLen / (content第2段/1000)` 的比值区间。

本脚本还会把 6 条被救下的 `content` 直接喂给 Python 复刻的 `HintTextLogic.summary`，
验证产出的文案符合预期（不用等装包）。
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr.txt"

RESCUED = {
    19289041: "wxid_3dmyz2iqavq822:30478:0",
    19289057: "wxid_3dmyz2iqavq822:12424:0",
    19289088: "wxid_3dmyz2iqavq822:19648:0",
    19289117: "wxid_3dmyz2iqavq822:2684:0",
    19289824: "wxid_3dmyz2iqavq822:20785:1:",
    19290094: "wxid_xlrbelf6hv0w22:2860:1:",
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


def sql_new(q, wait=3.2):
    n0 = len(pull_lines())
    sh("shell", 'am broadcast -a com.lx.wxgroup.action.PROBE --es op label '
                '--es sub sql --es sql "%s" -p com.tencent.mm' % q)
    time.sleep(wait)
    return pull_lines()[n0:]


def emicro(lines):
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


# ---------------- Python 复刻 HintTextLogic.summary（只复刻语音分支） ----------------
MAX_TEXT = 30


def voice_seconds(content):
    if not content or not content.strip():
        return None
    parts = content.split(":")
    if len(parts) < 2:
        return None
    try:
        ms = int(parts[1])
    except ValueError:
        return None
    if ms <= 0:
        return None
    return max(1, (ms + 500) // 1000)


def voice_summary(content):
    s = voice_seconds(content)
    return "[语音]" if s is None else "[语音 %d秒]" % s


def build_hint(who, summary):
    base = "%s 尝试撤回一条消息（已阻止）" % who
    return base if not summary.strip() else "%s：%s" % (base, summary)


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(1.5)
    sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear -p com.tencent.mm")
    time.sleep(1)

    print("=" * 78)
    print("[A] 复刻 HintTextLogic，对 6 条被救下语音产出文案（纯本地，不等装包）")
    print("=" * 78)
    for mid, content in RESCUED.items():
        sec = voice_seconds(content)
        summ = voice_summary(content)
        line = build_hint('"张三"', summ)
        print("  msgId=%-10d content=%-32s" % (mid, content))
        print("      第2段=%s ⇒ %s 秒" % (content.split(":")[1], sec))
        print("      summary=%s" % summ)
        print("      最终文案=%s" % line)
        # 检查：不含发送者 wxid（判据 #8）
        leak = "wxid_" in line
        print("      %s 不泄漏发送者" % ("❌ **泄漏了**" if leak else "✅"))
        print()

    print("=" * 78)
    print("[B] 旁证：正常语音的 TotalLen / 秒数 ⇒ 码率，验证「第2段是毫秒」")
    print("=" * 78)
    rows = emicro(sql_new(
        "select m.msgId,m.content,v.TotalLen,v.FileNowSize,v.Status "
        "from message m join voiceinfo v on v.MsgLocalId=m.msgId "
        "where m.type=34 order by m.msgId desc limit 8", 4.0))
    if not rows:
        print("  ⚠️ join 没查到（可能 voiceinfo 用 FileName 关联，不是 MsgLocalId）")
        print("     改用 FileName 关联再试：")
        rows = emicro(sql_new(
            "select m.msgId,m.content,v.TotalLen from message m join voiceinfo v "
            "on v.FileName=m.imgPath where m.type=34 order by m.msgId desc limit 8", 4.0))

    print("  %-10s %-30s %-10s %-10s %s" % ("msgId", "content", "TotalLen", "秒数", "B/s"))
    ok = 0
    total = 0
    for r in rows:
        print("  " + r[:170])
        kv = dict(re.findall(r"(\w+)=([^=\s|]+)", r))
        c = kv.get("content")
        tl = kv.get("TotalLen")
        if not c or not tl:
            continue
        try:
            tl_i = int(tl)
        except ValueError:
            continue
        sec = voice_seconds(c)
        if not sec:
            continue
        total += 1
        bps = tl_i / sec
        verdict = "✅ 合理(500~4000)" if 500 <= bps <= 4000 else "⚠️ 异常"
        if 500 <= bps <= 4000:
            ok += 1
        print("  %-10s %-30s %-10d %-10d %.0f  %s"
              % (kv.get("msgId", "?"), c, tl_i, sec, bps, verdict))

    print("\n" + "=" * 78)
    print("[C] 单位判据结论")
    print("=" * 78)
    if total == 0:
        print("  ⚠️ 没拿到可对账的样本 ⇒ 单位判据仍只有「量级排除法」支撑（lane 的 §3 判据 1/2/3）")
    else:
        print("  样本 %d 条，其中码率落在合理区间的 %d 条" % (total, ok))
        if ok == total:
            print("  ✅ **「第 2 段是毫秒」实证成立**：所有样本 TotalLen/秒数 都在 500~4000 B/s")
            print("     （若单位是秒，该比值会是 ~1 B/s，荒谬）")
        else:
            print("  ⚠️ 部分样本异常，需看明细 —— 可能个别语音含额外头/未完整下载")


if __name__ == "__main__":
    main()
