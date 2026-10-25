"""R21b 取证：语音消息（type=34）在 message 表里长什么样 + 防撤回改了什么

两个待查问题：
  ① 防撤回语音后播放失败 —— 需要知道语音消息的「播放能力」挂在哪几个字段上
     （猜测候选：`content` 里的 xml / `lvbuffer` / `reserved` / `imgPath`）
  ② 「撤回提示」要能识别是哪条 —— 需要知道撤回时微信写了什么、我们插的提示行长什么样

全程只读（rawQuery 走 AntiRevokeHint.BYPASS，避免被提示条隐藏逻辑改写）。
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
    """整条 am 命令作一个字符串传 sh（分包在含 --es 值时会被拆错）"""
    sh("shell", cmd)


def probe(op, extra=""):
    bc('am broadcast -a com.lx.wxgroup.action.PROBE --es op %s %s -p com.tencent.mm'
       % (op, extra))
    time.sleep(2)


def pull_grep(patterns, n=80):
    sh("pull", DBG, LOCAL)
    try:
        txt = open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        print("  (拉日志失败)")
        return
    seen = set()
    for line in txt:
        if any(p in line for p in patterns):
            key = re.sub(r"^\d\d:\d\d:\d\d\.\d+ ", "", line)
            if key in seen:
                continue
            seen.add(key)
            print("  " + line[:230])


def main():
    print("[0] 清日志 + 前台")
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(2)
    probe("logclear")
    time.sleep(1)

    print("[1] 列最近 40 条普通消息（含 type，找 type=34 语音）")
    probe("ardump", "--es sub all --ei n 40")
    time.sleep(3)
    pull_grep(["ARDUMP"], 60)

    print("\n[2] 列最近 40 条撤回相关（type 低 16 位 = 10000/10002）")
    probe("ardump", "--ei n 40")
    time.sleep(3)
    pull_grep(["ARDUMP"], 60)


if __name__ == "__main__":
    main()
