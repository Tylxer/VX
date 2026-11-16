#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R55 路线 E 真机取证小工具（分组页 = 复用微信 ConvBoxServiceConversationUI）。

用法：
    python tools/_r55_page.py state          # 前台 Activity + 标题 + 页内会话行数与前后几行标题
    python tools/_r55_page.py list           # 只打主列表可见标题（找分组行用）
    python tools/_r55_page.py tap 123        # 按标题子串点一行（点标题所在行的垂直中心）
    python tools/_r55_page.py tap 123 --idx 1  # 同名多行时点第 2 个（0 基）

为什么要这个脚本：
  · 会话标题是宿主自绘 View（`NoMeasuredTextView`），`uiautomator` 的 `text` 一般空，
    真正带文字的是 `com.tencent.mm:id/l7a` 节点（R54 定案）⇒ 必须按 resource-id 抽，不能按 text 抽。
  · 判定「进没进那个页」只看 `dumpsys activity` 的 `topResumedActivity` 类名，别看界面长得像不像。
  · 标题节点 id = `pkn`（ActionBar 中心 TextView，R55 Q1 取证）；页内会话行根 id = `cqz`。
    这两个 id 是 8.0.48/2588 的实测值，换版本要重新 dump 确认。

坑（都在这里固化）：
  · `adb pull` 设备路径会被 MSYS 当本地路径改写 ⇒ 必须 `export MSYS_NO_PATHCONV=1`（调用方负责）。
  · 本机 `adb` 每次调用可能打 `daemon not running; starting now`，命令仍生效，别当失败。
"""
import os
import re
import subprocess
import sys
import time
import xml.sax.saxutils as sx

ADB = (
    os.environ.get("ADB")
    or os.path.join(
        os.path.expanduser("~"), "AppData", "Local", "Android", "Sdk",
        "platform-tools", "adb.exe",
    )
)
HOST = "com.tencent.mm"
PAGE = "ConvBoxServiceConversationUI"
REMOTE = "/sdcard/_r55_page.xml"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOCAL = os.path.join(ROOT, "_cache", "_r55_page.xml")

TITLE_ID = "com.tencent.mm:id/pkn"
ROW_ID = "com.tencent.mm:id/cqz"
TXT_ID = "com.tencent.mm:id/l7a"

NODE = re.compile(r"<node\b[^>]*>")


def sh(*args, timeout=40):
    return subprocess.run(
        [ADB, "shell", " ".join(args)],
        capture_output=True, text=True, encoding="utf-8", errors="ignore", timeout=timeout,
    ).stdout.strip()


def top_activity():
    out = sh("dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{\S+ \S+ (\S+) ", out)
    if not m:
        m = re.search(r"mResumedActivity.*? (\S+/\S+) ", out)
    return m.group(1) if m else "?"


def dump():
    os.makedirs(os.path.dirname(LOCAL), exist_ok=True)
    sh("uiautomator", "dump", REMOTE)
    subprocess.run([ADB, "pull", REMOTE, LOCAL],
                   capture_output=True, text=True, encoding="utf-8", errors="ignore")
    if not os.path.exists(LOCAL):
        print("!! uiautomator dump 失败（既没拿到文件）")
        return []
    with open(LOCAL, "r", encoding="utf-8", errors="ignore") as f:
        xml = f.read()
    nodes = []
    for tag in NODE.findall(xml):
        def attr(name):
            m = re.search(name + r'="([^"]*)"', tag)
            return m.group(1) if m else ""
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        nodes.append({
            "id": attr("resource-id"),
            "cls": attr("class"),
            "text": attr("text"),
            "desc": attr("content-desc"),
            "xy": tuple(int(x) for x in b.groups()) if b else None,
        })
    return nodes


def by_id(nodes, rid):
    return [n for n in nodes if n["id"] == rid]


def label(n):
    return n["text"] or n["desc"]


def cmd_state():
    act = top_activity()
    print("top = %s" % act)
    nodes = dump()
    if not nodes:
        return
    t = [label(n) for n in by_id(nodes, TITLE_ID) if label(n)]
    print("title(pkn) = %s" % (t[0] if t else "<无 pkn 节点>"))
    rows = by_id(nodes, ROW_ID)
    if rows:
        print("页内会话行 cqz = %d" % len(rows))
    titles = [label(n) for n in by_id(nodes, TXT_ID) if label(n)]
    print("l7a 标题共 %d 个：" % len(titles))
    for s in titles[:12]:
        print("   " + s)
    print("在目标页 = %s" % ("是" if PAGE in act else "否"))


def cmd_list():
    act = top_activity()
    print("top = %s" % act)
    nodes = dump()
    if not nodes:
        return
    titles = [label(n) for n in by_id(nodes, TXT_ID) if label(n)]
    print("可见标题 %d 个：" % len(titles))
    for i, s in enumerate(titles):
        print("  [%d] %s" % (i, s))


def cmd_tap(sub, idx=0):
    nodes = dump()
    if not nodes:
        return
    hits = [n for n in by_id(nodes, TXT_ID) if sub in label(n) and n["xy"]]
    if not hits:
        print("未找到标题含 '%s' 的节点（当前界面可能不对）" % sub)
        return
    if idx >= len(hits):
        print("命中 %d 个，idx=%d 越界" % (len(hits), idx))
        return
    x1, y1, x2, y2 = hits[idx]["xy"]
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    print("命中 %d 个；点 [%d] '%s' @(%d,%d)" % (len(hits), idx, label(hits[idx]), cx, cy))
    sh("input", "tap", str(cx), str(cy))
    time.sleep(1.5)
    print("top = %s" % top_activity())


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        return
    cmd = a[0]
    if cmd == "state":
        cmd_state()
    elif cmd == "list":
        cmd_list()
    elif cmd == "tap":
        if len(a) < 2:
            print("用法：tap <标题子串> [--idx N]")
            return
        idx = 0
        if "--idx" in a:
            idx = int(a[a.index("--idx") + 1])
        cmd_tap(a[1], idx)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
