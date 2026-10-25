"""R21 验证：Dialog 窗口到底带不带 FLAG_ALT_FOCUSABLE_IM

复核报告主张：
  ① Panel 在 show() 前清 flag 无效 —— AlertController.installContent() 在 show() 内
     会重新 setFlags，因为 installContent 跑了两次、第二次时 Panel 内容还没渲染。
  ② BatchPicker / GroupPicker / Rows.input / UiKit.input 四处「清 flag」是多余改动 ——
     它们 setView(root) 时 root 里已有 EditText，canTextInput 命中 ⇒ 本来就不打 flag。
  ③ GroupDetailPage 用裸 Dialog，本来就没有这个 flag。

本脚本用 `dumpsys window` 读真实窗口的 `fl=` 字段来判 ①②③ —— 不靠推断。

用法：python tools/_r21_flagcheck.py <case>
  case = panel | batch | groupdetail | input
"""
import re
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"


def sh(*args):
    r = subprocess.run([ADB] + list(args), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def windows():
    """返回所有 window 的 (标题线索, fl= 行) —— 只取本次关心的几个"""
    out = sh("shell", "dumpsys", "window", "windows")
    blocks = re.split(r"\n\s*Window #\d+", out)
    res = []
    for b in blocks:
        m = re.search(r"Window\{[0-9a-f]+ u0 ([^}]+)\}", b)
        fl = re.search(r"\bfl=([^\n]*)", b)
        if m and fl:
            res.append((m.group(1).strip()[:60], fl.group(1).strip()))
    return res


def focus_state():
    out = sh("shell", "dumpsys", "input_method")
    shown = re.search(r"mInputShown=(\w+)", out)
    req = re.findall(r"sessionRequested=(\w+)", out)
    return (shown.group(1) if shown else "?"), req[:4]


def main():
    case = sys.argv[1] if len(sys.argv) > 1 else "panel"
    print("=== case=%s —— 请先在手机上把目标界面打开，再回车 ===" % case)
    ws = windows()
    print("\n--- 含 fl= 的窗口（重点看 ALT_FOCUSABLE_IM 在不在）---")
    for title, fl in ws:
        hit = "ALT_FOCUSABLE_IM" in fl
        mark = "  ⛔有ALT" if hit else ""
        print("  %-45s %s%s" % (title, fl[:110], mark))
    shown, req = focus_state()
    print("\n--- IME ---")
    print("  mInputShown=%s  sessionRequested=%s" % (shown, req))


if __name__ == "__main__":
    main()
