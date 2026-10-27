#!/usr/bin/env python3
"""真机回路编排器 —— 目的是少烧 token、少等编译。

用法（工作区根目录）：
  python tools/dev.py loop <场景名> [--skip-build] [--grep 正则] [--n 40]
      场景名 = tools/_<场景名>.py 里的 <场景名>，如 snssim / snsfake / snsmark
      例：python tools/dev.py loop snssim --grep 'SNS|SNSG|SNSTAG'
      全流程：编译 → 安装 → 修 LSPosed apk_path → 跑场景 → 拉日志 → 只打印匹配行

  python tools/dev.py q <正则> [n]
      只拉日志并过滤（不编译）。例：python tools/dev.py q 'SNS_TAG' 30

  python tools/dev.py probe <op> [sub] [sub2] [--val V] [--q SQL] [--grep P] [--n 40] [--wait 3] [--clear]
      一条命令跑完一次探针：清日志 → 发广播 → 等 → 拉日志 → 过滤。
      例：python tools/dev.py probe snsg srcfix cols --val -3405721048722501111 --grep SNSCOL
      例：python tools/dev.py probe snsg srcfix rank --grep SRCRANK --clear
      例：python tools/dev.py probe sql --q "select snsId,type from SnsInfo limit 5" --grep SQLX
      ⭐ 取证默认走这个；只有「要改代码加新探针」才需要编译装包。

      ⛔ 值会自动包单引号（设备 sh 会二次解析，不包就 syntax error）。
      ⛔ `op=sql` 用 `--q`，不是 `--val`（模块读的是 extra `q`）。
      ⛔ 微信在后台时 AMS 照样回 `Broadcast completed` 但**不投递**。本命令比对
         日志文件大小，没增长就报错并拒绝打印旧日志 —— 别对着过期数据下结论。
      ⛔ **写库探针**（`op=snsg sub=srcfix` 的 `album/hold/clean`、`snstag fake/mark`、
         `kvset`、`setsrc`）：`srcfix` 的 `sub2` 一旦拼错会掉进 `else -> run()`
         （`SnsTimelineSourceFix.run()`，含 snapshot 写台账 + 补位写库）。
         只想要状态就用 `--grep` 类只读探针，别用 `srcfix stat`。

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
# 仓库根从 __file__ 推导：并行开发时每路是一个 git worktree（独立目录），
# 各树里的 dev.py 必须操作自己那棵树的路径，不能写死主工作区。
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
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


def _sq(v):
    """设备端 `sh` 会**二次解析** adb 发过去的参数。

    ⛔ 不包单引号时：`select count(*)` → `/system/bin/sh: syntax error: unexpected '('`
    （广播压根没发出去）；`... where a='x'` 里那对单引号会把后面的 `-p 包名` 一起吞掉，
    实测 `pkg=adb_keys`。两种都是**静默**失败的假阴性。
    内部单引号按 POSIX 写法转成 `'\\''`。
    """
    s = str(v)
    if s == "":
        return "''"
    return "'" + s.replace("'", "'\\''") + "'"


# 这些开关后面紧跟的**值**需要转义；开关本身不转义
_VALUE_FLAGS = ("--es", "--esn", "--esa", "--ei", "--el", "--ef")


def bc(*extras, quiet=True):
    args = []
    want_val = False
    for e in extras:
        if want_val:
            args.append(_sq(e))
            want_val = False
            continue
        args.append(e)
        if e in _VALUE_FLAGS:
            want_val = True
    return sh("shell", "am", "broadcast", "-a", PKG + ".action.PROBE", *args,
              "-p", HOST, quiet=quiet)


def dbg_size():
    """日志文件字节数。用来判「探针到底执行了没有」—— 比 `Broadcast completed` 可靠得多。

    `am broadcast` 的 completed 只证明 AMS 收下了广播；微信在后台时它**不投递**，
    旧实现会安安静静打印几分钟前的旧日志，让人对着过期数据下结论。
    """
    r = sh("shell", "stat", "-c", "%s", DBG)
    m = re.search(r"(\d+)", r)
    return int(m.group(1)) if m else -1


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


def clear_log():
    """清掉落盘日志（模块内 LogX.reset），顺便把计数归零。"""
    bc("--es", "op", "logclear")
    time.sleep(1)


def loaded():
    """
    探测模块是否真的注入了当前微信进程，**并且跑的是最新代码**。

    判据是**日志有没有反应**，不是 `dev.sh sync` 的 `[STALE] apk_path` ——
    实测 STALE 时模块照样正常加载，照着 sync 无脑跑 lspfix 每轮白花 30 秒。
    做法：删掉日志文件 → 发一条必然打日志的广播 → 看文件有没有被重新写出来。

    ⚠️ **光看「文件被写回」会骗人**（2026-10-17 踩到）：旧版模块收到 `logclear`
    也会打一行 `ENTRY PROBE unknown op=logclear` 把文件写回来 → 检测误判"已加载"，
    结果整轮真机都在跑旧代码，日志一片 `unknown op=...`。
    **正确判据**：文件里出现 `logclear` 且**不含** `unknown op=logclear`。
    """
    sh("shell", "rm", "-f", DBG)
    sh("shell", "am", "start", "-n", HOST + "/.ui.LauncherUI")
    time.sleep(2)
    bc("--es", "op", "logclear")
    time.sleep(2)
    txt = pull_dbg()
    if "unknown op=logclear" in txt:
        print("   ⚠️ 模块加载的是**旧代码**（logclear 返回 unknown op）")
        return False
    return "logclear" in txt


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
    # ⚠️ 行宽上限 240 → 1600：抓 SQL 时 240 会在 `sourceType in (...)` 中间截断，
    #    而 SQL 的**后半段**（createTime 窗口 / LIMIT）恰恰是判据所在。
    #    截断一度让人误以为「条件已经看全了」，白跑一轮。
    for l in out[-n:]:
        print(l[:1600])
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


def cmd_probe(a):
    """一条命令跑一次探针：可选清日志 → 发广播 → 等 → 拉日志 → 过滤。

    存在理由：手工拼 `adb shell am broadcast --es op … -p com.tencent.mm` 再单独
    `dev.py q` 是本轮最大的重复劳动（半小时拼了几十次），而且踩过三个坑：
      · `kvset` 的参数名是 `key`/`val`，不是 `k`/`v`（栽过两次）
      · `--el` 只收 signed long，无符号 id 会静默丢弃
      · 值里含空格/负数时被 shell 二次解析
    ⇒ 这里把参数形状固定成一种写法，探针名和参数跟着代码走，不再靠记忆。
    """
    kv, pos, i = {}, [], 0
    while i < len(a):
        x = a[i]
        if x in ("--val", "--grep", "--n", "--wait", "--q"):
            if i + 1 >= len(a):
                print("%s 缺值" % x)
                return
            kv[x[2:]] = a[i + 1]
            i += 2
            continue
        if x == "--clear":
            kv["clear"] = "1"
            i += 1
            continue
        pos.append(x)
        i += 1
    if not pos:
        print("用法：dev.py probe <op> [sub] [sub2] "
              "[--val V] [--q SQL] [--grep P] [--n 40] [--wait 3] [--clear]")
        return
    # 先清日志再发：LogX 有 5000 行上限，不清的话新探针输出可能被旧行挤掉
    if kv.get("clear"):
        bc("--es", "op", "logclear")
        time.sleep(0.4)
    ex = ["--es", "op", pos[0]]
    if len(pos) > 1:
        ex += ["--es", "sub", pos[1]]
    if len(pos) > 2:
        ex += ["--es", "sub2", pos[2]]
    if "val" in kv:
        ex += ["--es", "val", kv["val"]]
    # ⛔ `op=sql` 读的是 extra **`q`**，不是 `val`（EntryReceiver.kt）。
    #    只发 val 会让模块收到空 q，返回「只允许 select/pragma/with（收到 ''）」。
    if "q" in kv:
        ex += ["--es", "q", kv["q"]]
    # 行数上限也要真的传给模块，否则 --n 只截本地显示
    if "n" in kv:
        ex += ["--ei", "n", kv["n"]]

    before = dbg_size()
    r = bc(*ex)
    # 广播未投递是静默的（后台不投递 / 包名错），必须显式判
    if "Broadcast completed" not in r:
        print("!! 广播未投递（微信在后台? 先 am start）:\n%s" % r.strip()[:300])
        return
    time.sleep(float(kv.get("wait", "3")))
    # ⛔ 第二道闸：`Broadcast completed` 只证明 AMS 收下了广播，证不了模块执行了。
    #    微信在后台时 AMS 照收不误（result=0），模块一次都不跑。旧实现此时会
    #    安安静静打印几分钟前的旧日志 —— 这是比「判据写错」更阴的陷阱：
    #    数据是真的，只是属于上一次。
    after = dbg_size()
    if before >= 0 and after == before:
        print("!! 探针**没有输出**（日志文件大小未变 %dB）：微信多半在后台，"
              "或模块未重载。先 am start -n com.tencent.mm/.ui.LauncherUI 再试。\n"
              "!! 下面**不会**打印旧日志，免得你对着过期数据下结论。" % before)
        return
    show(pull_dbg(), kv.get("grep") or "", int(kv.get("n", "40")))


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        return
    cmd = a[0]
    if cmd == "probe":
        cmd_probe(a[1:])
        return
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
            print(">> 模块加载检测（5 秒，比无脑 lspfix 的 30 秒便宜）")
            if loaded():
                print("   已加载，跳过 lspfix")
            else:
                print("   未加载 → lspfix")
                subprocess.run([PY, os.path.join(ROOT, "tools", "_lspfix.py")],
                               capture_output=True, text=True, encoding="utf-8", errors="ignore")
                if not loaded():
                    # ⚠️ 实测：`force-stop + am start` 第一次大概率还是旧代码，
                    # **再来一次**才真正重载。所以这里双 force-stop 再试一次。
                    print("   双 force-stop 重试（第一次重载常是旧代码）")
                    sh("shell", "am", "force-stop", HOST)
                    time.sleep(2)
                    sh("shell", "am", "force-stop", HOST)
                    time.sleep(2)
                    sh("shell", "am", "start", "-n", HOST + "/.ui.LauncherUI")
                    time.sleep(3)
                    if not loaded():
                        print("!! 重试后仍未加载，后续日志不可信")
        print(">> 场景 " + name)
        clear_log()
        r = subprocess.run([PY, os.path.join(ROOT, "tools", "_%s.py" % name)],
                           capture_output=True, text=True, encoding="utf-8", errors="ignore")
        print(((r.stdout or "") + (r.stderr or "")).strip()[-1500:])
        print(">> 日志")
        show(pull_dbg(), pat, n)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
