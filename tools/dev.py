#!/usr/bin/env python3
"""真机回路编排器 —— 目的是少烧 token、少等编译。

用法（工作区根目录）：
  python tools/dev.py loop <场景名> [--skip-build] [--grep 正则] [--n 40]
      场景名 = tools/_<场景名>.py 里的 <场景名>，如 snssim / snsfake / snsmark
      例：python tools/dev.py loop snssim --grep 'SNS|SNSG|SNSTAG'
      全流程：编译 → 安装 → 修 LSPosed apk_path → 跑场景 → 拉日志 → 只打印匹配行

  python tools/dev.py q <正则> [n]
      只拉日志并过滤（不编译）。例：python tools/dev.py q 'SNS_TAG' 30

  python tools/dev.py ui <文本片段> [包含上下文的额外关键字]
      uiautomator 文本断言，替代截图（截图是最贵的单项 token 开销）。
      输出命中的节点 text + bounds，按 y 排序 —— 位置关系可量化，
      比截图更适合自动判断（如「标签 y 是否小于作者名 y」）。
      例：python tools/dev.py ui 已删除

  python tools/dev.py restart
      双次 force-stop + 拉起，确保模块重载（也顺手把 LogX 5000 行计数归零）

约定：日志文件 390KB+，绝不能用 Read 工具直接读，必须走这里过滤。
"""
import os
import re
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ROOT = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21"
PY = r"C:\Users\lx\.workbuddy\binaries\python\versions\3.13.12\python.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL_DBG = os.path.join(ROOT, "_dbg_pull.txt")
PKG = "com.lx.wxgroup"
HOST = "com.tencent.mm"


def sh(*args, quiet=True):
    r = subprocess.run([ADB] + list(args), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    out = (r.stdout or "") + (r.stderr or "")
    if not quiet:
        print(out.strip()[:400])
    return out


def bc(*extras, quiet=True):
    return sh("shell", "am", "broadcast", "-a", PKG + ".action.PROBE", *extras,
              "-p", HOST, quiet=quiet)


def restart():
    """双次 force-stop：本机实测第一次拉起来常是旧代码；顺带把 LogX 计数归零。"""
    sh("start-server")
    sh("wait-for-device")
    sh("shell", "am", "force-stop", HOST)
    time.sleep(2)
    sh("shell", "am", "start", "-n", HOST + "/.ui.LauncherUI")
    time.sleep(5)
    sh("shell", "am", "force-stop", HOST)
    time.sleep(2)
    sh("shell", "am", "start", "-n", HOST + "/.ui.LauncherUI")
    time.sleep(8)


def pull_dbg():
    sh("pull", DBG, LOCAL_DBG)
    try:
        with open(LOCAL_DBG, encoding="utf-8", errors="ignore") as f:
            return f.read()
    except OSError:
        return ""


def show(txt, pat, n):
    lines = txt.splitlines()
    if pat:
        rx = re.compile(pat)
        lines = [l for l in lines if rx.search(l)]
    # 去重：真机日志常有大量同内容重复行
    seen, out = set(), []
    for l in lines:
        k = l[19:] if len(l) > 19 else l  # 去掉时间戳前缀
        if k in seen:
            continue
        seen.add(k)
        out.append(l)
    for l in out[-n:]:
        print(l[:240])
    print("--- 共 %d 行（去重后），显示最后 %d 行 ---" % (len(out), min(n, len(out))))


def cmd_ui(text, extra=None):
    sh("shell", "uiautomator", "dump", "/sdcard/_u.xml")
    time.sleep(1)
    tmp = os.path.join(ROOT, "_ui.xml")
    sh("pull", "/sdcard/_u.xml", tmp)
    try:
        with open(tmp, encoding="utf-8", errors="ignore") as f:
            xml = f.read()
    except OSError:
        print("uiautomator dump 失败")
        return
    nodes = []
    for m in re.finditer(r'<node[^>]*>', xml):
        s = m.group(0)
        tm = re.search(r'text="([^"]*)"', s)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
        if not tm or not bm:
            continue
        t = tm.group(1)
        if text not in t:
            continue
        if extra and extra not in t:
            continue
        y = int(bm.group(2))
        nodes.append((y, t, bm.group(0)))
    nodes.sort()
    if not nodes:
        print("未找到含 '%s' 的节点（当前界面可能不对）" % text)
        return
    for y, t, b in nodes[:40]:
        print("y=%-5d %s  %s" % (y, b, t[:60]))
    print("--- 命中 %d 个 ---" % len(nodes))


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        return
    cmd = a[0]
    if cmd == "restart":
        restart()
        print("微信已重启（LogX 计数归零）")
    elif cmd == "q":
        pat = a[1] if len(a) > 1 else ""
        n = int(a[2]) if len(a) > 2 else 40
        show(pull_dbg(), pat, n)
    elif cmd == "ui":
        cmd_ui(a[1], a[2] if len(a) > 2 else None)
    elif cmd == "loop":
        name = a[1]
        pat = None
        n = 40
        skip = "--skip-build" in a
        if "--grep" in a:
            pat = a[a.index("--grep") + 1]
        if "--n" in a:
            n = int(a[a.index("--n") + 1])
        if not skip:
            print(">> build")
            r = subprocess.run(["sh", "dev.sh", "build"], cwd=os.path.join(ROOT, "wxgroup"),
                               capture_output=True, text=True, encoding="utf-8", errors="ignore")
            out = (r.stdout or "") + (r.stderr or "")
            print(out.strip()[-600:])
            if "BUILD FAILED" in out or "e: " in out:
                print("!! 编译失败，中止")
                return
            print(">> install")
            r = subprocess.run(["sh", "dev.sh", "install"], cwd=os.path.join(ROOT, "wxgroup"),
                               capture_output=True, text=True, encoding="utf-8", errors="ignore")
            print(((r.stdout or "") + (r.stderr or "")).strip()[-300:])
            print(">> lspfix")
            subprocess.run([PY, os.path.join(ROOT, "tools", "_lspfix.py")],
                           capture_output=True, text=True, encoding="utf-8", errors="ignore")
        print(">> 场景 " + name)
        r = subprocess.run([PY, os.path.join(ROOT, "tools", "_%s.py" % name)],
                           capture_output=True, text=True, encoding="utf-8", errors="ignore")
        print(((r.stdout or "") + (r.stderr or "")).strip()[-1500:])
        print(">> 日志")
        show(pull_dbg(), pat, n)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
