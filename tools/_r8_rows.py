#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""R8-L3 延迟探针 —— 滚屏后按时间序列探 `op=snsc sub=ids`

## 为什么要有它
解决 P0 `rows=0` 归因：分不清是
  (A) `onBind` 之后评论行还没上树、扫太早 ⇒ 随延迟**上升**；
  (B) 当前屏这条动态本来就没评论 ⇒ **恒 0**。
本脚本固定「滚完最后一屏」为 t0，按可配延迟序列（默认 0/0.5/1/2/5s）反复发
`sub=ids` + `sub=stat`，把 `rows=` / `preciseIds=` 打成一张表，一眼看出是 A 还是 B。

## 用法
    python tools/_r8_rows.py                                  # 滚 3 屏 + 默认延迟序列
    python tools/_r8_rows.py --screens 6 --delays 0,0.3,1,3,5
    python tools/_r8_rows.py --no-scroll                      # 不滚，只探当前屏
    python tools/_r8_rows.py --dry-run                        # 静态自测，**不连设备**
    python tools/_r8_rows.py --out _cache/r8_rows.txt         # 结果另存

## 已知坑（真机踩过，别改回去）
1. **微信在后台不投递广播** ⇒ 发探针前必须先 `am start LauncherUI` 并校验
   `topResumedActivity`，不在朋友圈就走「发现 → 朋友圈」点回去。
2. 整条 `am` 命令**作为一个字符串**传给 `sh`，值用**单引号**包
   （设备 sh 会二次解析，空格/括号不加引号会被拆断）。
3. Bash 里直接调 adb 会反复重启 daemon ⇒ **一律用 python subprocess 调 adb**，
   且用绝对路径（PATH 里不一定有）。
4. `onBind` 同时被多任务面板等一堆 adapter 调用，`lastItemView` 只认
   `ImproveRecyclerView`，所以 `rows=0` 也可能是**看错了对象**（真机踩过，
   见 SnsCommentTag.kt:726）⇒ 表里有 `item=` 列，先看它是不是朋友圈那条列表。
"""
import argparse
import os
import re
import subprocess
import sys
import time

# ---------------------------------------------------------------- 常量（来源见注释）
ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PKG = "com.tencent.mm"
ACTION = "com.lx.wxgroup.action.PROBE"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL_DBG = os.path.join(ROOT, "_dbg_pull.txt")     # 沿用 dev.py:36 的落盘路径

SNS_UI = "ImproveSnsTimelineUI"                     # 朋友圈页（不可 export，只能点）
# 滚屏手势：抄 tools/_r7_install.py:96（y 2200→900，320ms）
SWIPE = ("720", "2200", "720", "900", "320")


# ---------------------------------------------------------------- adb 封装
def sh(*a, t=120):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore", timeout=t)
    return ((r.stdout or "") + (r.stderr or "")).strip()


def bc(*extra):
    """⚠️ 整条 am 命令作为一个字符串传给 sh，值一律单引号包。"""
    return sh("shell", "am broadcast -a %s %s -p %s" % (ACTION, " ".join(extra), PKG))


def probe(sub):
    return bc("--es", "op", "'snsc'", "--es", "sub", "'%s'" % sub)


def top():
    out = sh("shell", "dumpsys activity activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{[^ ]+ u0 ([^ ]+)", out)
    return m.group(1) if m else "?"


def dump_ui():
    sh("shell", "uiautomator dump /sdcard/_nav.xml")
    return sh("shell", "cat /sdcard/_nav.xml")


def tap_text(xml, text, idx=0):
    """按 text/content-desc 命中节点后点中心（自己实现，不改 _r7_install.py）。"""
    hits = []
    for n in re.findall(r"<node[^>]*>", xml or ""):
        for name in ("text", "content-desc"):
            m = re.search(name + r'="([^"]*)"', n)
            if not m or m.group(1) != text:
                continue
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
            if b:
                x1, y1, x2, y2 = (int(v) for v in b.groups())
                hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
            break
    if not hits or idx >= len(hits):
        return False
    sh("shell", "input tap %d %d" % hits[idx])
    return True


def ensure_foreground(nav=True):
    """⚠️ 微信在后台不投递广播 —— 必须先拉前台。"""
    sh("shell", "am start -n %s/.ui.LauncherUI" % PKG)
    time.sleep(3)
    t = top()
    if SNS_UI in t or not nav:
        return t
    xml = dump_ui()
    if not tap_text(xml, "发现"):
        sh("shell", "input tap 900 3050")      # 底栏第 3 个 tab 的兜底坐标
    time.sleep(4)
    tap_text(dump_ui(), "朋友圈")
    time.sleep(9)
    return top()


def pull():
    sh("pull", DBG, LOCAL_DBG)
    try:
        with open(LOCAL_DBG, encoding="utf-8", errors="ignore") as f:
            return f.read().splitlines()
    except OSError:
        return []


def tail_block(lines, key):
    """从日志尾部往前捞出「最后一条 key 开头/包含 key 的连续块」。

    ⚠️ 不能只取最后一行：probe 输出是多行，主体在前面几行
    （`ids` 的 `key=` 行、`stat` 的 SNSC_FUNNEL 行），只取最后一行等于没探。
    """
    end = len(lines)
    while end > 0 and key not in lines[end - 1]:
        end -= 1
    if end == 0:
        return []
    start = end - 1
    while start > 0 and key in lines[start - 1]:
        start -= 1
    return lines[start:end]


def _i(pat, s, d=0):
    m = re.search(pat, s or "")
    return int(m.group(1)) if m else d


def _s(pat, s, d=""):
    m = re.search(pat, s or "")
    return m.group(1) if m else d


def parse(lines):
    """解析最后一次 `ids` + `stat` 的输出。"""
    idb = tail_block(lines, "SNSC_TAGIDS")
    stb = tail_block(lines, "SNSC_TAG stat")
    head = idb[0] if idb else ""
    stat = " ".join(stb)
    keys = [x for x in idb if " key=" in x]
    return {
        "item": _s(r"item=(\S+)", head, "-"),
        "rows": _i(r"rows=(\d+)", head),
        "keys": len(keys),
        "key0": _s(r"key=(\S+)", keys[0]) if keys else "",
        "ids": _i(r"ids=(\d+)", stat),        # preciseIds 计数器
        "precise": _s(r"precise=(\w+)", stat, "-"),
        "posts": _i(r"posts=(\d+)", stat),
        "del": _i(r"del=(\d+)", stat),        # SNSC_FUNNEL del：灌数是否生效
        "frows": _i(r"rows=(\d+)", stat),     # SNSC_FUNNEL rows
    }


def main():
    ap = argparse.ArgumentParser(
        description="滚屏后按延迟序列探 op=snsc sub=ids（区分「扫太早」还是「本无评论」）")
    ap.add_argument("--screens", type=int, default=3, help="滚几屏，默认 3")
    ap.add_argument("--delays", default="0,0.5,1,2,5",
                    help="延迟序列（秒，相对滚完最后一屏），默认 0,0.5,1,2,5")
    ap.add_argument("--settle", type=float, default=2.0,
                    help="发完广播到 pull 之间的等待，默认 2.0s")
    ap.add_argument("--out", default=LOCAL_DBG, help="结果落盘文件，默认 %s" % LOCAL_DBG)
    ap.add_argument("--no-scroll", action="store_true", help="不滚屏，只探当前屏")
    ap.add_argument("--no-nav", action="store_true", help="不导航，假定已在朋友圈页")
    ap.add_argument("--dry-run", action="store_true", help="只打印计划，**不执行任何 adb**")
    a = ap.parse_args()

    delays = [float(x) for x in a.delays.split(",") if x.strip()]

    if a.dry_run:
        print("[dry-run] 计划（未执行任何 adb）：")
        print("  1. 拉前台 %s（必要时 发现 → 朋友圈）" % SNS_UI)
        print("  2. logclear")
        if not a.no_scroll:
            print("  3. 滚 %d 屏：input swipe %s" % (a.screens, " ".join(SWIPE)))
        print("  4. t0 = 滚完；对每个延迟 %s 发 sub=ids + sub=stat，等 %.1fs 后 pull"
              % (delays, a.settle))
        print("  5. 打表并追加到 %s" % a.out)
        print("\n判读：rows 随延迟上升 ⇒ 扫太早（时机问题）；恒 0 ⇒ 该动态本无评论；"
              "item 不是朋友圈列表 ⇒ 看错对象")
        return 0

    print("=== 1. 拉前台 ===")
    act = ensure_foreground(nav=not a.no_nav)
    print("  top:", act)
    if SNS_UI not in act:
        print("  ⚠️ 不在朋友圈页 —— 广播**不会投递**，结果不可信")

    print("=== 2. logclear ===")
    bc("--es", "op", "'logclear'")

    if not a.no_scroll:
        print("=== 3. 滚 %d 屏 ===" % a.screens)
        for i in range(a.screens):
            sh("shell", "input swipe " + " ".join(SWIPE))
            time.sleep(1.1)

    print("=== 4. 延迟序列探针 ===")
    t0 = time.monotonic()
    table = []
    for d in delays:
        left = d - (time.monotonic() - t0)
        if left > 0:
            time.sleep(left)
        probe("ids")
        probe("stat")
        time.sleep(a.settle)
        r = parse(pull())
        r["delay"] = d
        table.append(r)
        print("  t=%-5ss item=%-28s rows=%-3d keys=%-3d ids=%-4d del=%d"
              % (d, r["item"][:28], r["rows"], r["keys"], r["ids"], r["del"]))

    hdr = "%-7s %-28s %-5s %-5s %-6s %-5s %s" % (
        "delay", "item", "rows", "keys", "ids", "del", "key0")
    print("\n=== 5. 时间序列对照表 ===")
    print(hdr)
    print("-" * len(hdr))
    for r in table:
        print("%-7s %-28s %-5d %-5d %-6d %-5d %s"
              % (r["delay"], r["item"][:28], r["rows"], r["keys"],
                 r["ids"], r["del"], r["key0"]))

    nz = [r["rows"] for r in table]
    if all(v == 0 for v in nz):
        verdict = "恒 0 ⇒ 要么是「该动态本无评论」，要么是 item 看错对象（先看 item 列）"
    elif nz[0] == 0 and max(nz) > 0:
        verdict = "随延迟上升 ⇒ **扫太早**（onBind 后评论行还没上树），要延后/重扫"
    else:
        verdict = "首拍就有 rows ⇒ 时机不是瓶颈，去看 del / 键匹配"
    print("\n判读:", verdict)

    with open(a.out, "a", encoding="utf-8") as f:
        f.write("\n=== R8 rows probe %s screens=%d ===\n"
                % (time.strftime("%H:%M:%S"), a.screens))
        f.write(hdr + "\n")
        for r in table:
            f.write("%-7s %-28s %-5d %-5d %-6d %-5d %s\n"
                    % (r["delay"], r["item"][:28], r["rows"], r["keys"],
                       r["ids"], r["del"], r["key0"]))
        f.write("verdict: %s\n" % verdict)
    print("落盘:", a.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
