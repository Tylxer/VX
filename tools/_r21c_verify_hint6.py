"""R21c 验收闭环 v6：voice `content` 第 2 段 = 毫秒（端到端码率对账）。

## 前 4 版为什么反复返空的真正根因（本轮最大工具坑，已定案）
设备日志原文：
```
15:42:07.762 Thread-6  LABEL sql: no db instance (先 sub=on 开窗口)
```
`LabelHuntProbe` 的 `sub=sql` **不自己开库**，它查的是一个「已收集到的 db 实例」。
而这个实例池是靠 **`sub=on` 挂 `SQLiteDatabase.open` 之类的钩子**在微信开库那一刻抓到的 ——
**微信每次冷启动（`package loaded`）进程重建，实例池就空了**，必须重新 `sub=on` 等微信开库。

⇒ 前几版（v2/v3/v4/v5）都是「查询本身没问题，但窗口没开」：
- 某几次能出结果，是因为窗口碰巧还开着（`_r21c_diag_msg.py` 的 q2/q3/q4 就是这种情况）；
- `am start` 触发冷启动后窗口清零 ⇒ 之后一直 `no db instance`。

⚠️ 与「广播在后台不投递」是**两个不同的坑**，别混。

## 正确姿势
1. `sub=on --ei sec 120` 开窗口（挂钩子，等微信开库）
2. 等日志出现 `LABEL db ready` / 实例数 > 0（或干脆 `am start` 后再等一轮）
3. 再 `sub=sql` 查询 → 出结果
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr8.txt"

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


def bc(extra):
    return sh("shell",
              "am broadcast -a com.lx.wxgroup.action.PROBE --es op label %s "
              "-p com.tencent.mm" % extra)


def query(q, tries=4, wait=10.0):
    """跑一条 sql，返回增量 LABEL 行；遇 no db instance 自动重开窗口再试。"""
    for _ in range(tries):
        n0 = len(lines())
        bc('--es sub sql --es sql "%s"' % q)
        deadline = time.time() + wait
        while time.time() < deadline:
            time.sleep(0.8)
            delta = [l for l in lines()[n0:] if "package loaded" not in l]
            if any("no db instance" in l for l in delta):
                print("    → no db instance，重开窗口")
                open_window()
                break
            if any("LABEL sql db=" in l for l in delta):
                time.sleep(0.4)
                return [l for l in lines()[n0:] if "package loaded" not in l]
    return []


def open_window(sec=180):
    """开实例收集窗口，并等微信真正开库（最多 30s）。"""
    n0 = len(lines())
    bc("--es sub on --ei sec %d" % sec)
    time.sleep(1.2)
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    deadline = time.time() + 30
    while time.time() < deadline:
        time.sleep(1.5)
        delta = lines()[n0:]
        hits = [l for l in delta if "LABEL" in l and
                ("ready" in l or "seen" in l or "db=" in l or "n=" in l)]
        if hits:
            for l in hits[-3:]:
                print("    win | " + l.rstrip()[:170])
            return True
    print("    (窗口开启，但未见就绪行，继续试)")
    return False


def main():
    print("=" * 80)
    print("[0] 开 db 实例窗口")
    print("=" * 80)
    open_window()

    print("\n" + "=" * 80)
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
            print("  (msgId=%d 无 content)" % mid)

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
        print("  ✅ **「content 第 2 段 = 毫秒」端到端成立**（%d/%d 条）" % (ok, n))
        print("     按秒算 ~1 B/s，比任何音频编码低两个数量级 ⇒ 单位排除。")
    elif n:
        print("  ⚠️ %d/%d 合理" % (ok, n))
    else:
        print("  ⚠️ 无样本")


if __name__ == "__main__":
    main()
