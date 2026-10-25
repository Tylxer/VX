"""R21c 验收闭环 v5：voice `content` 第 2 段 = 毫秒（端到端码率对账）。

## v4 为什么只拿到 1/2 条
v4 里 `19289165` 查不到 content，根因是**微信进程被冷启动**：
15:41:42 的日志出现 `main package loaded, process=com.tencent.mm` ——
`am start` 拉起冷启动时 receiver 还没注册，此时发出的广播**静默丢弃**
（已知坑：「微信后台不投递，先 am start」；但 am start 到 receiver 就绪之间还有几秒空窗）。

⇒ v5 加两道保险：
1. **warmup**：`am start` 后轮询日志，等到 `db ready` / `host version` 出现才算进程就绪（最多 40s）。
2. **每条查询重试 3 次**：拿不到 LABEL 行就重发（每次重发前再确认进程活着）。

## 判据
silk 语音码率常识区间 **500~4000 B/s**（约 4~32 kbps，微信语音典型 ~10 kbps）。
`TotalLen / (第2段/1000)` 落在区间 ⇒ 毫秒成立；若按秒算 = `TotalLen/第2段` ≈ 1~2 B/s ⇒ 荒谬。
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr6.txt"

CTRL = {19289165: 40973, 19289006: 35827}


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def lines():
    sh("pull", DBG, LOCAL)
    try:
        return open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        return []


def warmup(timeout=40):
    """等微信进程真正就绪（出现 db ready / host version 这类启动行）。"""
    n0 = len(lines())
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(1.5)
        delta = lines()[n0:]
        if any(("db ready" in l) or ("host version" in l) for l in delta):
            time.sleep(1.0)
            return True
    # 进程本来就活着时不会有启动行 ⇒ 用 pidof 兜底
    pid = sh("shell", "pidof", "com.tencent.mm").strip()
    return bool(pid)


def query(q, tries=3, wait=10.0):
    for _ in range(tries):
        n0 = len(lines())
        sh("shell", 'am broadcast -a com.lx.wxgroup.action.PROBE --es op label '
                    '--es sub sql --es sql "%s" -p com.tencent.mm' % q)
        deadline = time.time() + wait
        while time.time() < deadline:
            time.sleep(0.8)
            delta = [l for l in lines()[n0:] if "package loaded" not in l]
            if any("LABEL" in l for l in delta):
                time.sleep(0.4)
                return [l for l in lines()[n0:] if "package loaded" not in l]
        print("    (未拿到 LABEL，重试)")
        warmup(15)
    return []


def main():
    print("warmup:", "ok" if warmup() else "failed")
    print("=" * 80)
    print("[1] 逐条取 content")
    print("=" * 80)
    got = {}
    for mid in CTRL:
        for l in query("select msgId,content from message where msgId=%d" % mid):
            if "LABEL" in l and "msgId=" in l:
                print("  | " + l.rstrip()[:190])
                kv = dict(re.findall(r"(\w+)=([^=|\s]+)", l))
                if "content" in kv:
                    got[mid] = kv["content"]
        if mid not in got:
            print("  (msgId=%d 仍无 content)" % mid)

    print("\n" + "=" * 80)
    print("[2] 码率对账")
    print("=" * 80)
    print("  %-10s %-32s %-8s %-8s %-11s %-10s %s"
          % ("msgId", "content", "TotalLen", "第2段", "B/s(毫秒)", "B/s(按秒)", "判定"))
    ok = n = 0
    for mid, total_len in CTRL.items():
        c = got.get(mid)
        if not c:
            continue
        parts = c.split(":")
        if len(parts) < 2:
            continue
        try:
            v = int(parts[1])
        except ValueError:
            continue
        if v <= 0:
            continue
        n += 1
        bps_ms = total_len / (v / 1000.0)
        bps_s = total_len / float(v)
        good = 500 <= bps_ms <= 4000
        ok += 1 if good else 0
        print("  %-10s %-32s %-8d %-8d %-11.0f %-10.2f %s"
              % (mid, c, total_len, v, bps_ms, bps_s, "✅" if good else "⚠️"))

    print("\n" + "=" * 80)
    print("[3] 结论")
    print("=" * 80)
    if n and ok == n:
        print("  ✅ **「content 第 2 段 = 毫秒」端到端成立**（%d/%d 条码率落在 500~4000 B/s）" % (ok, n))
        print("     按秒算只有 ~1 B/s，比任何音频编码低两个数量级 ⇒ 单位排除。")
    elif n:
        print("  ⚠️ %d/%d 合理" % (ok, n))
    else:
        print("  ⚠️ 无样本")


if __name__ == "__main__":
    main()
