"""模拟微信真实两步删除 → 验证「拦截 + 恢复可见 + 归档 + 标签自动贴回」全链路"""
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
    return sh("shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE",
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


def u2s(u):
    u = int(u)
    return u - (1 << 64) if u >= (1 << 63) else u


sh("start-server")
sh("wait-for-device")
sh("shell", "am", "force-stop", "com.tencent.mm")   # 清 LogX 的 5000 行计数
time.sleep(2)
sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
time.sleep(9)
for k in ("antirevoke.sns", "antirevoke.sns_deleted_tag"):
    bc("--es", "op", "kvset", "--es", "key", k, "--es", "val", "true")
time.sleep(1)

if "ImproveSnsTimelineUI" not in top():
    xml = dump_ui()
    if not tap_node(xml, "发现"):
        sh("shell", "input", "tap", "900", "3050")
    time.sleep(4)
    tap_node(dump_ui(), "朋友圈")
    time.sleep(9)
print("act:", top())

sh("shell", "rm", "-f", DBG)
time.sleep(1)

# 1. 拿屏幕上第 0 条动态
bc("--es", "op", "snstag", "--es", "sub", "dump")
time.sleep(3)
sh("pull", DBG, ROOT + r"\_sns_dbg.txt")
txt = open(ROOT + r"\_sns_dbg.txt", encoding="utf-8", errors="ignore").read()
ids = re.findall(r"u=(\d{15,20}) hit=\S+ txt=([^ ]*)", txt)
print("屏幕上的动态:")
for i, (u, t) in enumerate(ids):
    print("  [%d] %s  %s" % (i, u, t))
if not ids:
    raise SystemExit("!! 没解析出 id")

u, label = ids[0]
s = u2s(u)
print("\n模拟删除 [0] u=%s s=%d  %s" % (u, s, label))

# 2. 模拟微信两步删除
bc("--es", "op", "snsg", "--es", "sub", "simdel", "--el", "id", str(s))
time.sleep(4)

# 3. 查 DB 里的 localFlag（18 = 恢复成功；2 = 恢复失败）
bc("--es", "op", "snsg", "--es", "sub", "show", "--el", "id", str(s))
time.sleep(4)

# 4. 等 delSet 刷新（TTL 5s）+ sweep（2s）把标签贴回来
time.sleep(9)
bc("--es", "op", "snstag", "--es", "sub", "stat")
time.sleep(2)
bc("--es", "op", "snstag", "--es", "sub", "dump")
time.sleep(3)
sh("shell", "screencap", "-p", "/sdcard/_sns_sim.png")
sh("pull", "/sdcard/_sns_sim.png", ROOT + r"\_sns_sim.png")
sh("pull", DBG, ROOT + r"\_sns_dbg.txt")

print("\n--- 日志 ---")
txt = open(ROOT + r"\_sns_dbg.txt", encoding="utf-8", errors="ignore").read()
for l in txt.splitlines():
    if ("SNS" in l and "SNSDMP" not in l) or "u=" in l:
        print(l[:170])
print("\n期望：SNS blocked delete ... flag=18 ；SNSG show 显示 18 -> 0 ；截图里 [0] 那条带 [已删除]")
