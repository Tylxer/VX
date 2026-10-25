"""R21c 验收（补 · v3）：端到端对账「voice content 第 2 段 = 毫秒」。

## 相对 v2 改了什么
v2 的 content 查询返空，两个可疑点一起修：
1. `length(content) cl` —— **别名带空格**在广播传 SQL 时可能被切碎 ⇒ 去掉，只查 `msgId,content`。
2. `n0 = len(pull_lines())` 紧跟广播，**落盘有竞态** ⇒ 改成「广播后轮询 pull，直到出现新的
   `LABEL sql db=` 行或超时」，再取增量。这是本轮反复踩到的坑（假阳性/假阴性都出现过）。

## 判据
`silk` 语音码率常识区间 **500~4000 B/s**（约 4~32 kbps，微信语音典型 ~10 kbps）。
- `TotalLen / 秒数` 落在区间内 ⇒ **第 2 段是毫秒**成立
- 若单位是秒（`30478` 秒 = 8.5 小时），比值 ≈ 1.2 B/s ⇒ 荒谬

## 对照数据来源
`tools/_r21c_voiceinfo3.py` 真机实测（按 `MsgLocalId` 查 `voiceinfo`，2/2 命中）：
    19289165 → TotalLen=40973
    19289006 → TotalLen=35827
注意：这两条是**同群正常语音**（voiceinfo 有行），不是被救下的 6 条
（那 6 条 voiceinfo 行已被微信撤回流程删掉，查不到 TotalLen）。
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr.txt"

CTRL = {19289165: 40973, 19289006: 35827}
# 被救下 6 条的 content（已知，用于文案复刻；无 TotalLen 可对账）
RESCUED = {19289041: "30478", 19289057: "12424", 19289088: "19648",
           19289117: "2684", 19289824: "20785", 19290094: "2860"}


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


def sql_new(q, wait=8.0):
    """广播后轮询 pull，等新的 `LABEL sql db=` 行出现（最多 wait 秒）。"""
    n0 = len(pull_lines())
    sh("shell", 'am broadcast -a com.lx.wxgroup.action.PROBE --es op label '
                '--es sub sql --es sql "%s" -p com.tencent.mm' % q)
    deadline = time.time() + wait
    while time.time() < deadline:
        time.sleep(0.8)
        lines = pull_lines()
        delta = lines[n0:]
        if any("LABEL sql db=" in l for l in delta):
            time.sleep(0.4)                 # 让同一批后续行落完
            return pull_lines()[n0:]
    return pull_lines()[n0:]


def emicro(lines):
    """只保留 EnMicroMsg.db 那一块的行。"""
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
    """复刻 HintTextLogic.voiceSeconds。"""
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

    ids = ",".join(str(i) for i in CTRL)
    print("=" * 78)
    print("[1] 取对照语音的 content —— select msgId,content  (msgId in %s)" % ids)
    print("=" * 78)
    raw = sql_new("select msgId,content from message where msgId in (%s)" % ids)
    rows = emicro(raw)
    got = {}
    for r in rows:
        print("  " + r[:180])
        kv = dict(re.findall(r"(\w+)=([^=\s|]+)", r))
        if "msgId" in kv and "content" in kv:
            got[int(kv["msgId"])] = kv["content"]
    if not got:
        print("  ⚠️ 仍为空。原始增量行数=%d，前 15 行：" % len(raw))
        for l in raw[:15]:
            print("    | " + l[:160])

    print("\n" + "=" * 78)
    print("[2] 码率对账：TotalLen(字节) / 秒数 = B/s   （合理区间 500~4000）")
    print("=" * 78)
    print("  %-10s %-26s %-10s %-7s %-9s %s"
          % ("msgId", "content", "TotalLen", "秒数", "B/s", "判定"))
    ok = n = 0
    bps_list = []
    for mid, total_len in CTRL.items():
        c = got.get(mid)
        if not c:
            print("  %-10s —                      未取到 content" % mid)
            continue
        sec = voice_seconds(c)
        if not sec:
            print("  %-10s %-26s 解析不出时长" % (mid, c))
            continue
        n += 1
        bps = total_len / sec
        bps_list.append(bps)
        good = 500 <= bps <= 4000
        ok += 1 if good else 0
        print("  %-10s %-26s %-10d %-7d %-9.0f %s"
              % (mid, c, total_len, sec, bps, "✅ 合理" if good else "⚠️ 异常"))

    print("\n" + "=" * 78)
    print("[3] 结论")
    print("=" * 78)
    if n == 0:
        print("  ⚠️ 无有效样本 —— 端到端对账未闭环。")
        print("     单位判据仍只靠 lane §3「量级排除法」+ 主会话 Python 复刻验证：")
        print("     30478 按秒 = 8.46 小时（物理不可能），按毫秒 = 30.5 秒。")
    else:
        print("  有效样本 %d 条，码率合理 %d 条" % (n, ok))
        print("  实际 B/s: %s" % ", ".join("%.0f" % b for b in bps_list))
        if ok == n:
            print("  ✅ **「content 第 2 段是毫秒」端到端成立**")
            print("     码率 %.0f~%.0f B/s（约 %.1f~%.1f kbps），符合微信 silk 语音常识区间。"
                  % (min(bps_list), max(bps_list), min(bps_list) * 8 / 1000, max(bps_list) * 8 / 1000))
            print("     若单位是秒，比值会是 ~1 B/s ⇒ 荒谬。单位判据闭环。")
        else:
            print("  ⚠️ 有样本异常，见明细（可能含头大/未下全，需要更多样本）")

    print("\n" + "=" * 78)
    print("[4] 参考：被救下 6 条（无 TotalLen，仅列文案复刻结果）")
    print("=" * 78)
    for mid, c in RESCUED.items():
        sec = voice_seconds(c)
        print("  %-10s content=%-8s → [语音 %s秒]" % (mid, c, sec))


if __name__ == "__main__":
    main()
