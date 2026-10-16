"""验证「[已删除]标签」整条链路：进朋友圈 → dump 解析明细 → 强制打标 → 截图"""
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


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/_nav.xml")
    return sh("shell", "cat", "/sdcard/_nav.xml")


def attr(n, name):
    m = re.search(name + r'="([^"]*)"', n)
    return m.group(1) if m else ""


def tap_node(xml, text, idx=0):
    hits = []
    for n in re.findall(r"<node[^>]*>", xml):
        if attr(n, "text") == text or attr(n, "content-desc") == text:
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attr(n, "bounds"))
            if m:
                x1, y1, x2, y2 = (int(v) for v in m.groups())
                hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
    if not hits:
        return False
    x, y = hits[min(idx, len(hits) - 1)]
    sh("shell", "input", "tap", str(x), str(y))
    return True


def top():
    out = sh("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{[^ ]+ u0 ([^ ]+)", out)
    return m.group(1) if m else "?"


def bc(*extra):
    sh("shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE",
       *extra, "-p", "com.tencent.mm")


def scroll(times=2):
    """往上滚几屏，让更多 item 被 bind"""
    for _ in range(times):
        sh("shell", "input", "swipe", "700", "2600", "700", "900", "400")
        time.sleep(2)


sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
time.sleep(4)
for k in ("antirevoke.sns", "antirevoke.sns_deleted_tag"):
    bc("--es", "op", "kvset", "--es", "key", k, "--es", "val", "true")
time.sleep(1)

xml = dump()
if not tap_node(xml, "发现"):
    sh("shell", "input", "tap", "900", "3050")
time.sleep(4)
xml = dump()
tap_node(xml, "朋友圈")
time.sleep(9)
print("act:", top())

scroll(2)
bc("--es", "op", "snstag", "--es", "sub", "stat")
time.sleep(2)
bc("--es", "op", "snstag", "--es", "sub", "dump")
time.sleep(2)
print("--- 强制打标 ---")
bc("--es", "op", "snstag", "--es", "sub", "mark")
time.sleep(3)
sh("shell", "screencap", "-p", "/sdcard/_sns_mark.png")
sh("pull", "/sdcard/_sns_mark.png", ROOT + r"\_sns_mark.png")
bc("--es", "op", "snstag", "--es", "sub", "stat")
time.sleep(2)
sh("pull", DBG, ROOT + r"\_sns.txt")
print("done")
