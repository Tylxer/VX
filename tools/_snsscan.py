"""滚屏扫描：看归档里的已删动态能否被真实命中（hits>0）+ 每屏 dump 解析明细"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ROOT = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"


def sh(*args):
    r = subprocess.run([ADB] + list(args), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def bc(*extra):
    sh("shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE",
       *extra, "-p", "com.tencent.mm")


def top():
    out = sh("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{[^ ]+ u0 ([^ ]+)", out)
    return m.group(1) if m else "?"


def scroll(px=1800):
    sh("shell", "input", "swipe", "700", "2600", "700", str(2600 - px), "350")
    time.sleep(2)


sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
time.sleep(3)
# 已经在朋友圈就不要再点导航
if "ImproveSnsTimelineUI" not in top():
    xml = sh("shell", "cat", "/sdcard/_nav.xml")
    print("warn: not in sns timeline, act=", top())
time.sleep(1)

bc("--es", "op", "snstag", "--es", "sub", "stat")
time.sleep(1)
print("baseline stat 见日志")

for i in range(14):
    scroll()
    bc("--es", "op", "snstag", "--es", "sub", "stat")
    time.sleep(1)

bc("--es", "op", "snstag", "--es", "sub", "dump")
time.sleep(2)
sh("shell", "screencap", "-p", "/sdcard/_sns_scan.png")
sh("pull", "/sdcard/_sns_scan.png", ROOT + r"\_sns_scan.png")
sh("pull", DBG, ROOT + r"\_sns.txt")
print("act:", top())
print("done")
