"""R21c 验收（补）：用**已有对照行**验证「第 2 段是毫秒」。

## 为什么不走 join
上一版 `_r21c_verify_hint.py` 试了两种 join（`on v.MsgLocalId=m.msgId` 和
`on v.FileName=m.imgPath`），**都返回 0 行**。原因未查清（可能是 SQL 走广播时
引号/空格被处理掉，也可能是 join 语法在这条探针路径上不支持）。

⇒ **不猜，改成分两步查再本地算**：
1. 从 `voiceinfo` 取对照行的 `TotalLen`（已知：19289165→40973、19289006→35827）
2. 从 `message` 取这两条的 `content`
3. Python 本地算 `TotalLen / 秒数` = 字节/秒

## 判据
silk 语音码率常识区间 **500~4000 B/s**（约 4~32 kbps，微信语音典型 ~10 kbps）。
- 落在区间内 ⇒ **第 2 段是毫秒**成立
- 若单位是秒（30478 秒 = 8.5 小时），`TotalLen/30478 ≈ 1.2 B/s` ⇒ 荒谬

## 参考：已知的被救下 6 条 content（其 voiceinfo 已被删，只能用同群正常语音旁证）
19289041 → 30478 / 19289057 → 12424 / 19289088 → 19648
19289117 → 2684  / 19289824 → 20785 / 19290094 → 2860
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr.txt"

# 从 `_r21c_voiceinfo3.py` 实测拿到的对照行（有 voiceinfo 记录的正常语音）
CTRL = {19289165: 40973, 19289006: 35827}


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


def voice_seconds(content):
    if not content:
        return None
    parts = content.split(":")
    if len(parts) < 2:
        return None
    try:
        ms = int(parts[1])
    except ValueError:
        return None
    return max(1, (ms + 500) // 1000) if ms > 0 else None


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(1.5)
    sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear -p com.tencent.mm")
    time.sleep(1)

    ids = ",".join(str(i) for i in CTRL)
    print("=" * 78)
    print("[1] 取对照语音的 content（msgId in %s）" % ids)
    print("=" * 78)
    rows = emicro(sql_new("select msgId,content,length(content) cl from message "
                          "where msgId in (%s)" % ids))
    got = {}
    for r in rows:
        print("  " + r[:170])
        kv = dict(re.findall(r"(\w+)=([^=\s|]+)", r))
        if "msgId" in kv and "content" in kv:
            got[int(kv["msgId"])] = kv["content"]

    print("\n" + "=" * 78)
    print("[2] 算码率：TotalLen(字节) / 秒数 = B/s")
    print("=" * 78)
    print("  %-10s %-30s %-10s %-8s %-10s %s"
          % ("msgId", "content", "TotalLen", "秒数", "B/s", "判定"))
    ok = 0
    n = 0
    for mid, total_len in CTRL.items():
        c = got.get(mid)
        if not c:
            print("  %-10s 未取到 content" % mid)
            continue
        sec = voice_seconds(c)
        if not sec:
            print("  %-10s content=%s 解析不出时长" % (mid, c))
            continue
        n += 1
        bps = total_len / sec
        okk = 500 <= bps <= 4000
        if okk:
            ok += 1
        print("  %-10s %-30s %-10d %-8d %-10.0f %s"
              % (mid, c, total_len, sec, bps, "✅ 合理" if okk else "⚠️ 异常"))

    print("\n" + "=" * 78)
    print("[3] 结论")
    print("=" * 78)
    if n == 0:
        print("  ⚠️ 无有效样本。单位判据仍只靠 lane 的 §3「量级排除法」（判据 1/2/3）——")
        print("     那条也够用（30478 按秒 = 8.46 小时，物理不可能），但缺端到端对账。")
    else:
        print("  有效样本 %d 条，码率合理 %d 条" % (n, ok))
        if ok == n:
            print("  ✅ **「content 第 2 段是毫秒」端到端成立**")
            print("     码率 %.0f~%.0f B/s（约 %.1f~%.1f kbps），正是微信 silk 语音的常识区间"
                  % (min(CTRL[m] / voice_seconds(got[m]) for m in got if m in CTRL),
                     max(CTRL[m] / voice_seconds(got[m]) for m in got if m in CTRL),
                     min(CTRL[m] / voice_seconds(got[m]) for m in got if m in CTRL) * 8 / 1000,
                     max(CTRL[m] / voice_seconds(got[m]) for m in got if m in CTRL) * 8 / 1000))
            print("     若单位是秒，比值会是 ~1 B/s ⇒ 荒谬。单位判据闭环。")
        else:
            print("  ⚠️ 有样本异常，见明细（可能含头/未下全）")


if __name__ == "__main__":
    main()
