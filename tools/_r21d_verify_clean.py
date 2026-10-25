"""R21d 干净校验：负数行是否已清零 + crash 是否已停。

## 为什么要重写（上一版输出被搅乱的两个原因）
1. `query()` 带 4 次重试，但**每次重试都会再发一次广播**；上一轮的 LABEL 行还留在
   增量窗口里时判定「没拿到」，就会叠着发出 2~4 条广播 ⇒ 4 个线程同时写日志 ⇒
   4 组 `count(*)` 混在一起（上一轮看到 7784822/7784941/7784950/7784961 就是这个）。
2. `label sub=sql` 会遍历**所有已打开的库**，非 EnMicroMsg 的库都回
   `no such table: message` ⇒ 噪音行占了 90%。

## 本脚本的约束
- **每条查询只发一次广播**，不重试（失败就如实报失败，不叠发）。
- 只保留 `db=...EnMicroMsg.db` 那个块里的数据行。
- 查询语句里**不出现聚合别名**（`length(x) cl` 这类会让探针整条不出行）。
"""
import os
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "_r21d_chk.txt")


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


def open_window(sec=240):
    n0 = len(lines())
    sh("shell", 'am broadcast -a com.lx.wxgroup.action.PROBE --es op label '
                '--es sub on --ei sec %d -p com.tencent.mm' % sec)


def one(q, wait=12.0):
    """发一次广播，等到 EnMicroMsg 块出现即返回该块的纯数据行。绝不重试。"""
    n0 = len(lines())
    sh("shell", 'am broadcast -a com.lx.wxgroup.action.PROBE --es op label '
                '--es sub sql --es sql "%s" -p com.tencent.mm' % q)
    deadline = time.time() + wait
    while time.time() < deadline:
        time.sleep(0.7)
        delta = lines()[n0:]
        if any("no db instance" in l for l in delta):
            return None, "no db instance"
        if any("LABEL sql db=" in l for l in delta):
            time.sleep(0.5)
            delta = lines()[n0:]
            break
    else:
        return None, "timeout"

    out, block = [], False
    for l in delta:
        if "LABEL sql db=" in l:
            block = "EnMicroMsg.db" in l
            continue
        if l.strip().endswith("LABEL") or "raw err" in l:
            continue
        if block and "LABEL" in l:
            out.append(l.split("LABEL", 1)[1].strip())
    return out, "ok"


def main():
    open_window()
    time.sleep(1.5)
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(3)

    checks = [
        ("负数行数（应为 0）", "select count(*) from message where msgId<0"),
        ("负数行明细", "select msgId,type,talker from message where msgId<0"),
        ("当前 max(msgId)", "select max(msgId) from message where msgId>0"),
        ("最老 msgId", "select min(msgId) from message where msgId>0"),
    ]
    for title, q in checks:
        rows, st = one(q)
        print("=" * 78)
        print("[%s]  %s" % (title, q))
        print("  状态: %s" % st)
        for r in (rows or []):
            print("  | " + r[:170])
        if rows == []:
            print("  (0 行)")
        print()

    # crash 是否还在：看最近 30 分钟 crash buffer 里最后一次出现的时刻
    o = sh("shell", "logcat", "-b", "crash", "-d", "-t", "600")
    ts = [l.split()[1] for l in o.splitlines()
          if "msgId not in the reasonable scope" in l and l[:2] == "10"]
    print("=" * 78)
    print("[crash 时间线]  'msgId not in the reasonable scope' 出现次数=%d" % len(ts))
    if ts:
        print("  首次 %s   末次 %s" % (ts[0], ts[-1]))
    # 当前 pid / 存活
    pid = sh("shell", "pidof com.tencent.mm").strip()
    print("  当前微信 pid=%s" % (pid or "(未运行)"))


if __name__ == "__main__":
    main()
