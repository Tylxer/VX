"""场景脚本公共库（tools/_*.py 共用，被 `dev.py loop <场景>` 调用）

只封装 adb 操作与断言，**不含业务逻辑**。非交互：需要人工配合的步骤用 `wait_manual()`，
它打印提示后 sleep 等待（脚本可能被编排器非交互调用，不能用 input()）。

约定：
- 日志统一拉到工作区根 `_sns.txt`（与 dev.py 的 `q` 子命令一致）。
- 改配置一律走广播 kvset（设备无 sqlite3），**发之前必须 ensure_foreground()**（后台不投递）。
- 断言优先用 ui()（uiautomator 文本），截图是最后手段。
"""
import os
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL_DBG = os.path.join(ROOT, "_sns.txt")


def sh(*args):
    r = subprocess.run([ADB] + list(args), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def bc(*extra):
    """发模块探针广播。⚠️ 微信在后台时广播不投递。"""
    sh("shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE",
       *extra, "-p", "com.tencent.mm")


def kvset(key, val):
    bc("--es", "op", "kvset", "--es", "key", key, "--es", "val", str(val))


def kvget(key=None):
    if key:
        bc("--es", "op", "kvget", "--es", "key", key)
    else:
        bc("--es", "op", "kvget")


def logclear():
    """清落盘日志（LogX 写满是环形覆盖，不需要重启微信）"""
    bc("--es", "op", "logclear")


def ensure_foreground():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(3)


def top():
    out = sh("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{[^ ]+ u0 ([^ ]+)", out)
    return m.group(1) if m else "?"


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/_nav.xml")
    return sh("shell", "cat", "/sdcard/_nav.xml")


def attr(node, name):
    m = re.search(name + r'="([^"]*)"', node)
    return m.group(1) if m else ""


def nodes(xml, text):
    """返回所有 text/content-desc 命中（含子串）的节点，按 y 排序"""
    out = []
    for n in re.findall(r"<node[^>]*>", xml):
        t = attr(n, "text")
        d = attr(n, "content-desc")
        if text not in t and text not in d:
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attr(n, "bounds"))
        if not m:
            continue
        x1, y1, x2, y2 = (int(v) for v in m.groups())
        out.append({"y": y1, "text": t or d, "bounds": attr(n, "bounds"),
                    "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2})
    out.sort(key=lambda n: n["y"])
    return out


def ui(text):
    """文本断言（替代截图）。打印命中节点，返回节点列表。"""
    hits = nodes(dump(), text)
    if not hits:
        print("  ui: 未找到 '%s'" % text)
    for h in hits[:6]:
        print("  ui: y=%-5d %-22s %s" % (h["y"], h["bounds"], h["text"][:24]))
    return hits


def tap_text(text, idx=0):
    hits = nodes(dump(), text)
    if not hits:
        return False
    h = hits[min(idx, len(hits) - 1)]
    sh("shell", "input", "tap", str(h["cx"]), str(h["cy"]))
    return True


def tap_xy(x, y):
    sh("shell", "input", "tap", str(x), str(y))


def home():
    """退后台（验证通知/响铃类功能**必须**先退后台，否则什么都抓不到）"""
    sh("shell", "input", "keyevent", "3")


def wait_manual(msg, sec=15):
    print("  ⚠️ 人工步骤：%s（等 %ds）" % (msg, sec))
    time.sleep(sec)


def pull_dbg():
    sh("pull", DBG, LOCAL_DBG)
    return LOCAL_DBG


def grep(pattern, n=20):
    """只打印匹配行且去重 —— 落盘日志 390KB+，绝不能整读"""
    try:
        txt = open(LOCAL_DBG, encoding="utf-8", errors="ignore").read()
    except OSError:
        return []
    seen, out = set(), []
    for line in txt.splitlines():
        if re.search(pattern, line):
            key = re.sub(r"^\d\d:\d\d:\d\d\.\d+ ", "", line)
            if key in seen:
                continue
            seen.add(key)
            out.append(line)
    for line in out[-n:]:
        print("  " + line[:200])
    return out


def step(msg):
    print("[step] " + msg)
