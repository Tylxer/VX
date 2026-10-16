"""端到端验证：dump 取屏幕上某条动态的 id → 精确模拟「这条被删」→ 看标签落在哪一条上"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ROOT = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
TARGET = "1"   # 取 dump 里的第几条（0 = 第一条）


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


def dump_ui():
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


sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
time.sleep(3)
for k in ("antirevoke.sns", "antirevoke.sns_deleted_tag"):
    bc("--es", "op", "kvset", "--es", "key", k, "--es", "val", "true")
time.sleep(1)

if "ImproveSnsTimelineUI" not in top():
    xml = dump_ui()
    if not tap_node(xml, "发现"):
        sh("shell", "input", "tap", "900", "3050")
    time.sleep(4)
    xml = dump_ui()
    tap_node(xml, "朋友圈")
    time.sleep(9)
print("act:", top())

# 1. dump 当前屏幕上解析出来的 id（带作者名，方便人工对账）
sh("shell", "rm", "-f", DBG)
bc("--es", "op", "snstag", "--es", "sub", "dump")
time.sleep(3)
sh("pull", DBG, ROOT + r"\_sns_dump.txt")
txt = open(ROOT + r"\_sns_dump.txt", encoding="utf-8", errors="ignore").read()
ids = re.findall(r"u=(\d{15,20}) hit=\S+ txt=([^ ]*)", txt)
print("屏幕上的动态:")
for i, (u, t) in enumerate(ids):
    print("  [%d] %s  %s" % (i, u, t))
if not ids:
    print("!! 没解析出任何 id，先看 stat")
    raise SystemExit(1)

pick_i = min(int(TARGET), len(ids) - 1)
pick_u, pick_txt = ids[pick_i]
print("\n选中 [%d] %s %s" % (pick_i, pick_u, pick_txt))

# 2. 精确模拟这一条被删
bc("--es", "op", "snstag", "--es", "sub", "fake", "--es", "id", pick_u)
time.sleep(3)
sh("shell", "screencap", "-p", "/sdcard/_sns_fake.png")
sh("pull", "/sdcard/_sns_fake.png", ROOT + r"\_sns_fake.png")

# 3. 再 dump 一次看 hit 标记
bc("--es", "op", "snstag", "--es", "sub", "dump")
time.sleep(3)
bc("--es", "op", "snstag", "--es", "sub", "stat")
time.sleep(2)
sh("pull", DBG, ROOT + r"\_sns_dump2.txt")
txt2 = open(ROOT + r"\_sns_dump2.txt", encoding="utf-8", errors="ignore").read()
print("\n--- fake 之后 ---")
for line in txt2.splitlines():
    if "u=" in line or "stat" in line:
        print(line.strip()[:140])
print("期望：[%d] 那条 hit=true，且截图里标签在「%s」上" % (pick_i, pick_txt))
