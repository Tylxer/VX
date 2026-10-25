"""R21c 诊断：为什么 `select ... from message where msgId in (...)` 返空。

对着 `_r21c_voiceinfo3.py` 抄同样的 sql_new/emicro_rows，逐条打原始增量行，
不做任何过滤，看探针到底回了什么。

测 4 个查询，从简到繁：
  q1  select count(*) c from message
  q2  select msgId from message where msgId in (19289165,19289006)
  q3  select msgId,content from message where msgId=19289165
  q4  select MsgLocalId,TotalLen from voiceinfo where MsgLocalId in (19289165,19289006)
"""
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vr3.txt"

QUERIES = [
    ("q1", "select count(*) c from message"),
    ("q2", "select msgId from message where msgId in (19289165,19289006)"),
    ("q3", "select msgId,content from message where msgId=19289165"),
    ("q4", "select MsgLocalId,TotalLen from voiceinfo where MsgLocalId in (19289165,19289006)"),
]


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


def main():
    base = len(pull_lines())
    print("基线行数 =", base)
    for tag, q in QUERIES:
        n0 = len(pull_lines())
        out = sh("shell",
                 'am broadcast -a com.lx.wxgroup.action.PROBE --es op label '
                 '--es sub sql --es sql "%s" -p com.tencent.mm' % q)
        print("\n" + "=" * 76)
        print("[%s] %s" % (tag, q))
        print("  broadcast: %s" % out.strip().replace("\n", " / ")[:140])
        print("-" * 76)
        for _ in range(8):
            time.sleep(0.8)
            delta = pull_lines()[n0:]
            delta = [l for l in delta if "package loaded" not in l]
            if delta:
                for l in delta:
                    print("  | " + l.rstrip()[:190])
                break
        else:
            print("  (8 秒内无任何新增日志)")


if __name__ == "__main__":
    main()
