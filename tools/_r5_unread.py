#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R5 #1 验收：「显示未读」开关（`chat.show_unread`）—— 双向对照。

判据：给角标加了 `contentDescription="未读N"`，所以 `ui("未读")` 能直接断言
      （uiautomator 看不到背景色，没这行就只能靠截图，而截图是最后手段）。

用法：
    python _r5_unread.py            # 完整双向对照（开 → 有角标；关 → 无角标）
    python _r5_unread.py --only on  # 只测开关打开
"""
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
APK = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\wxgroup\app\build\outputs\apk\debug\app-debug.apk"
XML = "/sdcard/_nav.xml"

ONLY = "on" if "--only" in sys.argv else "both"
NOINSTALL = "--noinstall" in sys.argv


def sh(*a, t=300):
    return subprocess.run([ADB, *a], capture_output=True, text=True,
                          encoding="utf-8", errors="ignore", timeout=t).stdout


def bc(*extra):
    sh("shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE",
       *extra, "-p", "com.tencent.mm")


def ui(text):
    """返回命中的节点数 + 打印前几个"""
    sh("shell", "uiautomator", "dump", XML)
    xml = sh("shell", "cat", XML)
    hits = []
    import re
    for n in re.findall(r"<node[^>]*>", xml):
        if text in n:
            hits.append(n)
    print("      ui(%s) -> 命中 %d 个" % (text, len(hits)))
    for h in hits[:4]:
        d = re.search(r'content-desc="([^"]*)"', h)
        t = re.search(r'text="([^"]*)"', h)
        print("        bounds=%s text=%s desc=%s" % (
            (re.search(r'bounds="([^"]*)"', h) or [None, "?"])[1],
            t.group(1) if t else "", d.group(1) if d else ""))
    return len(hits)


def refresh():
    """触发 ConvFilter.rebuild，并确保停在会话列表**顶部**（分组行在第 1 屏）。"""
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(3)
    # ⚠️ 三个坑（都真机踩过）：
    # ① **不要向下滑**（1600→700）：分组行是列表最上面几行，一滑就出屏幕 → 假阴性。
    # ② **也不要连续向上猛滑**：微信在顶部继续上拉会触发「下拉」手势，把「最近使用的小程序」
    #    整块拉出来（ConvFilter 注释里专门说过这个耦合，所以它还原锚点时刻意还原原锚点而非 0）。
    # ③ 小程序面板**一旦被拉出来会盖住会话列表**，`am start` 也推不掉，必须 BACK。
    # 所以：先按一次 BACK 收掉任何浮层，再 am start 回到会话列表，不再做手势。
    sh("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(4)


def dump_list(limit=18):
    """两边都是 0 时用它看清「列表里到底有没有分组行」——没有分组就不会有角标"""
    import re
    sh("shell", "uiautomator", "dump", XML)
    xml = sh("shell", "cat", XML)
    print("      --- 会话列表可见节点（按 y 排序，前 %d 个）---" % limit)
    rows = []
    for n in re.findall(r"<node[^>]*>", xml):
        t = re.search(r'text="([^"]*)"', n)
        d = re.search(r'content-desc="([^"]*)"', n)
        b = re.search(r'bounds="\[(\d+),(\d+)\]', n)
        tv = t.group(1) if t else ""
        dv = d.group(1) if d else ""
        if not tv and not dv:
            continue
        y = int(b.group(2)) if b else 0
        if y > 2400:      # 底部 tab 区，跳过
            continue
        rows.append((y, tv, dv))
    rows.sort()
    for y, tv, dv in rows[:limit]:
        print("        y=%-5d text=%-26s desc=%s" % (y, tv[:26], dv[:22]))
    if not rows:
        print("        (空 —— 可能不在会话列表页)")
    return rows


def set_switch(v):
    bc("--es", "op", "kvset", "--es", "key", "chat.show_unread", "--es", "val", v)
    time.sleep(2)


def main():
    if NOINSTALL:
        print("[step] --noinstall：跳过安装，直接用设备上已装的包")
    else:
        print("[step] 装 APK + 重启两次（装完第一次常是旧代码）")
        print("   ", sh("install", "-r", APK).strip()[:120])
        sh("shell", "am", "force-stop", "com.tencent.mm")
        time.sleep(2)
        sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
        time.sleep(5)
        sh("shell", "am", "force-stop", "com.tencent.mm")
        time.sleep(2)
        sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
        time.sleep(5)

    print("\n[实验组] chat.show_unread = true")
    set_switch("true")
    refresh()
    n_on = ui("未读")

    if ONLY == "both":
        print("\n[对照组] chat.show_unread = false")
        set_switch("false")
        refresh()
        n_off = ui("未读")

        print("\n========== 结论 ==========")
        print("  开关开：角标 %d 个" % n_on)
        print("  开关关：角标 %d 个" % n_off)
        if n_on > 0 and n_off == 0:
            print("  ✅ 双向对照通过：开关真的生效了")
        elif n_on == 0 and n_off == 0:
            print("  ⚠️ 两边都没有角标 —— 可能分组行没渲染 / 分组内无未读 / 模块没加载")
            print("     最常见原因：**你还没建分组**（分组行是我们自己画的，没分组就没有行、没有角标）")
            dump_list()
        elif n_off > 0:
            print("  ❌ 开关关了角标还在 —— 没接上（检查 rebuild 的 showUnread 与 stamp）")
        else:
            print("  ❌ 开关开了反而没有 —— 判据反了")
    else:
        print("\n开关开：角标 %d 个" % n_on)


if __name__ == "__main__":
    main()
