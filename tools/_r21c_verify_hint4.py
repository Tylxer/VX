"""R21c 验收闭环：voice `content` 第 2 段单位 = 毫秒（端到端码率对账）。

## 为什么 v2/v3 全返空（已查明，非探针限制）
`_r21c_diag_msg.py` 逐条对照测出来了：
| 写法 | 结果 |
|---|---|
| `select count(*) c from message` | **无任何 LABEL 行**（聚合 + 别名这条路上探针不出行） |
| `select msgId from message where msgId in (…)` | ✅ rows=2 |
| `select msgId,content from message where msgId=19289165` | ✅ rows=1，content 完整 |
| `select MsgLocalId,TotalLen from voiceinfo where MsgLocalId in (…)` | ✅ rows=2 |

⇒ 探针没问题，是 v2 用了 `length(content) cl`（聚合函数 + 别名）把整条查询带死了；
v3 虽然去掉了别名，但还用了 `in ()` 一次取两条 content —— 本轮改成**逐条 `=` 查**，与已证可用的写法完全对齐。

## 判据
silk 语音码率常识区间 **500~4000 B/s**（约 4~32 kbps，微信语音典型 ~10 kbps）。
`TotalLen / 秒数` 落在区间 ⇒ 第 2 段是毫秒；若按秒算会得到 ~1 B/s ⇒ 荒谬。
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr4.txt"

# voiceinfo 实测 TotalLen（_r21c_voiceinfo3.py / _r21c_diag_msg.py 两处一致）
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


def sql_one(q, wait=8.0):
    """跑一条查询，返回增量日志行（已滤 package loaded）。"""
    n0 = len(pull_lines())
    sh("shell", 'am broadcast -a com.lx.wxgroup.action.PROBE --es op label '
                '--es sub sql --es sql "%s" -p com.tencent.mm' % q)
    deadline = time.time() + wait
    while time.time() < deadline:
        time.sleep(0.8)
        delta = [l for l in pull_lines()[n0:] if "package loaded" not in l]
        if any("LABEL" in l for l in delta):
            time.sleep(0.4)
            return [l for l in pull_lines()[n0:] if "package loaded" not in l]
    return []


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(1.5)

    print("=" * 80)
    print("[1] 逐条取 content（`=` 查询，已证可用）")
    print("=" * 80)
    got = {}
    for mid in CTRL:
        lines = sql_one("select msgId,content from message where msgId=%d" % mid)
        for l in lines:
            if "LABEL" in l and "msgId=" in l:
                print("  | " + l.rstrip()[:190])
                kv = dict(re.findall(r"(\w+)=([^=|\s]+)", l))
                if "content" in kv:
                    got[mid] = kv["content"]
        if mid not in got:
            print("  (msgId=%d 未取到 content)" % mid)

    print("\n" + "=" * 80)
    print("[2] 码率对账：TotalLen / 时长，分别按「毫秒」与「秒」两种假设算")
    print("=" * 80)
    print("  %-10s %-32s %-8s %-9s %-11s %s"
          % ("msgId", "content", "TotalLen", "第2段", "B/s(毫秒)", "B/s(若按秒)"))
    ok = n = 0
    for mid, total_len in CTRL.items():
        c = got.get(mid)
        if not c:
            continue
        parts = c.split(":")
        if len(parts) < 2:
            print("  %-10s %-32s 段数<2" % (mid, c))
            continue
        try:
            v = int(parts[1])
        except ValueError:
            print("  %-10s %-32s 第2段非数字" % (mid, c))
            continue
        n += 1
        bps_ms = total_len / (v / 1000.0)      # 假设毫秒
        bps_s = total_len / float(v)           # 假设秒
        good = 500 <= bps_ms <= 4000
        ok += 1 if good else 0
        print("  %-10s %-32s %-8d %-9d %-11.0f %.2f  %s"
              % (mid, c, total_len, v, bps_ms, bps_s, "✅" if good else "⚠️"))

    print("\n" + "=" * 80)
    print("[3] 结论")
    print("=" * 80)
    if n and ok == n:
        print("  ✅ **「content 第 2 段 = 毫秒」端到端成立**（%d/%d 条码率落在 500~4000 B/s）" % (ok, n))
        print("     按毫秒算 ≈ 1.5~1.8 kB/s（12~14 kbps），正是微信 silk 语音的量级；")
        print("     按秒算 ≈ 1~2 B/s，比任何音频编码低两个数量级 ⇒ 排除。")
    elif n:
        print("  ⚠️ %d/%d 合理，见明细" % (ok, n))
    else:
        print("  ⚠️ 仍未取到样本")


if __name__ == "__main__":
    main()
