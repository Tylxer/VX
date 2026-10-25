"""R21b 取证（二）：查台账里被救下的语音消息 + 当前「恢复默认显示」开关状态

语音播放失败的两种可能：
  A. 「恢复默认显示」开着 → 我们把消息改写成撤回形态 → 语音当然播不了（这是**预期行为**）
  B. 「恢复默认显示」关着 → 语音是原文形态，但库里某些字段被污染 → 真 bug

必须先分清 A/B，否则修错方向。
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_sns.txt"


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def bc(cmd):
    sh("shell", cmd)


def probe(op, extra=""):
    bc('am broadcast -a com.lx.wxgroup.action.PROBE --es op %s %s -p com.tencent.mm'
       % (op, extra))
    time.sleep(2)


def pull_grep(patterns, n=100):
    sh("pull", DBG, LOCAL)
    try:
        txt = open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        return
    seen = set()
    out = []
    for line in txt:
        if any(p in line for p in patterns):
            key = re.sub(r"^\d\d:\d\d:\d\d\.\d+ ", "", line)
            if key in seen:
                continue
            seen.add(key)
            out.append(line)
    for l in out[-n:]:
        print("  " + l[:250])


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(2)
    probe("logclear")
    time.sleep(1)

    print("[1] 读开关：ANTIREVOKE_RESTORE_VIEW / hint / enabled")
    for k in ["antirevoke_restore_view", "antirevoke_hint", "antirevoke_enabled"]:
        probe("kvget", "--es key %s" % k)
    time.sleep(2)
    pull_grep(["KV"], 40)

    print("\n[2] 台账 rescued 里 type=34（语音）的记录 + 总数 + 类型分布")
    probe("label", "--es sub on")
    time.sleep(2)
    probe("label", "--es sub stat")
    time.sleep(2)
    for sql in [
        "select count(*) from rescued",
        "select orig_type,count(*) from rescued group by orig_type",
        "select msg_id,orig_type,talker,substr(orig_content,1,40) oc,revoke_content,"
        "hint_msg_id,length(revoke_extra) rl,length(orig_extra) ol from rescued "
        "where orig_type=34 limit 10",
    ]:
        bc('am broadcast -a com.lx.wxgroup.action.PROBE --es op label --es sub sql '
           '--es sql "%s" -p com.tencent.mm' % sql.replace('"', '\\"'))
        time.sleep(2)
    time.sleep(2)
    pull_grep(["LABEL sql db=", "LABEL   "], 120)


if __name__ == "__main__":
    main()
