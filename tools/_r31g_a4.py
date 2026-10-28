#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R31g 批次 A.4：进朋友圈 → 下拉刷新一次 → 拉日志判「正常动态有没有被误标」。

⛔ 与 `_snstest.py` 的区别：本脚本**不改任何开关**（`_snstest.py` 会把
`antirevoke.sns` / `sns_deleted_tag` 强制 kvset true）。A.4 要验的正是
「当前配置下会不会误标真实动态」，中途改配置就把判据污染了。

用法：
    python tools/_r31g_a4.py            # 进朋友圈 + 下拉刷新 + 拉日志
    python tools/_r31g_a4.py --no-nav   # 只拉日志（已经手动停在朋友圈页时）

判据（人工看输出）：
    · `SNSTAG` 里不应出现「本来正常、被标已删」的条目
    · `SNS albumfix ... already=N` 中 already 应当等于「可见条数」，fixed 应为 0
      （fixed>0 说明又在给可见动态盖已删章 —— 上一轮事故的同款症状）
"""
import os
import re
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ROOT = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"


def sh(*args):
    r = subprocess.run([ADB] + list(args), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def attr(n, name):
    m = re.search(name + r'="([^"]*)"', n)
    return m.group(1) if m else ""


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/_a4.xml")
    return sh("shell", "cat", "/sdcard/_a4.xml")


def tap_text(xml, text, idx=0):
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


def main():
    if "--no-nav" not in sys.argv:
        print("start:", top())
        sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
        time.sleep(3)
        if "SnsTimeLineUI" not in top():
            xml = dump()
            if not tap_text(xml, "发现"):
                sh("shell", "input", "tap", "900", "3050")   # 底部 tab 兜底
            time.sleep(3)
            xml = dump()
            if not tap_text(xml, "朋友圈"):
                print("!! 没找到「朋友圈」入口，当前页:", top())
                return
            time.sleep(6)
        print("after 朋友圈:", top())

    # 下拉刷新：从顶部往下拖。两次（一次可能只带动回弹）
    print(">> 下拉刷新")
    for _ in range(2):
        sh("shell", "input", "swipe", "720", "900", "720", "2400", "300")
        time.sleep(3)
    time.sleep(4)

    # 停止滚动，回到顶部截一张，便于肉眼比对标记
    sh("shell", "input", "swipe", "720", "1600", "720", "600", "200")
    time.sleep(1)
    sh("shell", "screencap", "-p", "/sdcard/_a4.png")
    sh("pull", "/sdcard/_a4.png", os.path.join(ROOT, "_a4.png"))
    sh("pull", DBG, os.path.join(ROOT, "_a4.txt"))

    txt = ""
    try:
        with open(os.path.join(ROOT, "_a4.txt"), encoding="utf-8", errors="ignore") as f:
            txt = f.read()
    except OSError:
        pass
    keys = ("SNS", "SNSTAG", "albumfix", "srcstat", "BADGE")
    lines = [l for l in txt.splitlines() if any(k in l for k in keys)]
    seen, out = set(), []
    for l in lines:
        k = l[19:] if len(l) > 19 else l
        if k in seen:
            continue
        seen.add(k)
        out.append(l)
    for l in out[-40:]:
        print(l[:400])
    print("--- SNS 相关 %d 行；截图 _a4.png ---" % len(out))


if __name__ == "__main__":
    main()
