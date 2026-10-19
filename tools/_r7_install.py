"""R7 快速上机：装包 + 两次重启重载 + 进朋友圈滚屏。

⚠️ 必须在 Python 里调 adb（Bash 里调会反复重启 daemon）。
⚠️ `dev.sh install` 会静默装旧包 → 这里只用 `adb install -r` + pm path 时间戳校验。
⚠️ 装新包常不重载 → force-stop + am start **做两次**。
"""
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ROOT = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21"
APK = ROOT + r"\wxgroup\app\build\outputs\apk\debug\app-debug.apk"


def sh(*a, t=120):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore", timeout=t)
    return ((r.stdout or "") + (r.stderr or "")).strip()


sh("start-server")
sh("wait-for-device")

print("=== 1. 安装 ===")
print(sh("install", "-r", APK, t=240))

print("=== 2. 校验（pm path 时间戳必须 >= 本地 APK）===")
p = sh("shell", "pm", "path", "com.lx.wxgroup").replace("package:", "").strip()
print("remote =", p)
print(sh("shell", "ls", "-la", p))

print("=== 3. 两次重启重载 ===")
for i in (1, 2):
    sh("shell", "am", "force-stop", "com.tencent.mm")
    time.sleep(2)
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(9)
    print(f"  round {i} done")

print("=== 4. 清日志 ===")
sh("shell", "rm", "-f", "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt")
sh("shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE",
   "--es", "op", "logclear", "-p", "com.tencent.mm")
time.sleep(1)

print("=== 5. 进朋友圈（ImproveSnsTimelineUI 不可 export，必须走 UI 点击）===")
import re  # noqa: E402


def top():
    out = sh("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{[^ ]+ u0 ([^ ]+)", out)
    return m.group(1) if m else "?"


def dump_ui():
    sh("shell", "uiautomator", "dump", "/sdcard/_nav.xml")
    return sh("shell", "cat", "/sdcard/_nav.xml")


def _attr(n, name):
    m = re.search(name + r'="([^"]*)"', n)
    return m.group(1) if m else ""


def tap_node(xml, text, idx=0):
    hits = []
    for n in re.findall(r"<node[^>]*>", xml):
        if _attr(n, "text") == text or _attr(n, "content-desc") == text:
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", _attr(n, "bounds"))
            if m:
                x1, y1, x2, y2 = (int(v) for v in m.groups())
                hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
    if not hits or idx >= len(hits):
        return False
    sh("shell", "input", "tap", str(hits[idx][0]), str(hits[idx][1]))
    return True


sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
time.sleep(3)
if "ImproveSnsTimelineUI" not in top():
    xml = dump_ui()
    if not tap_node(xml, "发现"):
        sh("shell", "input", "tap", "900", "3050")  # 底栏第 3 个 tab 的兜底坐标
    time.sleep(4)
    xml = dump_ui()
    tap_node(xml, "朋友圈")
    time.sleep(9)
print("act:", top())

screens = int(sys.argv[1]) if len(sys.argv) > 1 else 8
print(f"=== 6. 滚 {screens} 屏 ===")
for i in range(screens):
    sh("shell", "input", "swipe", "720", "2200", "720", "900", "320")
    time.sleep(1.1)
print("ok")
