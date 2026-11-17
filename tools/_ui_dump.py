"""临时：dump 当前界面，只列有文字的节点（dev.py ui 只能按文本过滤，看全貌不方便）。

用法：python tools/_ui_dump.py [n]
"""
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
TMP = os.path.join(os.environ.get("TEMP", "/tmp"), "_uidump.xml")


def adb(*args):
    return subprocess.run([ADB] + list(args), capture_output=True, text=True,
                          encoding="utf-8", errors="ignore").stdout


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 60
    # ⛔ 先删旧文件：dump 失败时 adb 不报错，pull 会静默拿到**上一次**的 XML，
    # 表现就是「界面明明变了，dump 出来还是老内容」（本轮真实踩到）。
    adb("shell", "rm", "-f", "/sdcard/_u.xml")
    out = adb("shell", "uiautomator", "dump", "/sdcard/_u.xml")
    if "dumped to" not in out:
        print("dump 失败:", out.strip()[:200])
        return
    time.sleep(0.3)
    adb("pull", "/sdcard/_u.xml", TMP)
    xml = open(TMP, encoding="utf-8", errors="ignore").read()
    rows = []
    for m in re.finditer(r"<node[^>]*>", xml):
        s = m.group(0)
        tm = re.search(r'text="([^"]*)"', s)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
        cm = re.search(r'class="([^"]*)"', s)
        rm = re.search(r'resource-id="([^"]*)"', s)
        if not tm or not bm:
            continue
        t = tm.group(1).strip()
        if not t:
            continue
        rows.append((int(bm.group(2)), int(bm.group(1)), t,
                     (cm.group(1) if cm else "").split(".")[-1],
                     (rm.group(1) if rm else "")))
    rows.sort()
    print("有文字节点 %d 个" % len(rows))
    for y, x, t, c, r in rows[:n]:
        print("y=%-5d x=%-4d %-14s %s" % (y, x, c[:14], t[:48]))


if __name__ == "__main__":
    main()
