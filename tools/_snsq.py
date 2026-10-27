"""查归档详情 + 可选恢复可见性。

用法：
    python tools/_snsq.py [dump|show <snsId>|stat] [--restart]

    --restart  强制重启微信。**装了新包之后第一次必须加**，否则跑的是旧模块代码。
"""
import os
import re
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
# ⛔ 从 __file__ 推导，别硬编码主工作区绝对路径：并行 worktree 下会把 _sns_dbg.txt
#    写回主工作区，两个 lane 互相覆盖对方的取证文件。
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
HOST = "com.tencent.mm"
LOCAL = os.path.join(ROOT, "_sns_dbg.txt")


def sh(*args):
    r = subprocess.run([ADB] + list(args), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def _sq(v):
    """设备端 sh 二次解析 ⇒ 值必须包单引号（内部 `'` 转 `'\\''`）。"""
    s = str(v)
    return "''" if s == "" else "'" + s.replace("'", "'\\''") + "'"


_VALUE_FLAGS = ("--es", "--esn", "--esa", "--ei", "--el", "--ef")


def bc(*extra):
    args = []
    want_val = False
    for e in extra:
        if want_val:
            args.append(_sq(e))
            want_val = False
            continue
        args.append(e)
        if e in _VALUE_FLAGS:
            want_val = True
    return sh("shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE",
              *args, "-p", HOST)


def dbg_size():
    """日志字节数 —— 用来判「探针到底执行了没有」。"""
    m = re.search(r"(\d+)", sh("shell", "stat", "-c", "%s", DBG))
    return int(m.group(1)) if m else -1


def dbg_tail(n=400):
    return sh("shell", "tail", "-c", str(n), DBG)


def read_dbg(tag):
    """tag='' 时返回全部行（dump 的多行输出没有前缀，grep 会漏）"""
    sh("pull", DBG, LOCAL)
    try:
        txt = open(LOCAL, encoding="utf-8", errors="ignore").read()
    except OSError:
        return []
    return [l for l in txt.splitlines() if not tag or tag in l]


sh("start-server")
sh("wait-for-device")

# ── 为什么不再默认 force-stop ────────────────────────────────────────────
# 旧版每次都 `force-stop` + 重启微信（~11s，且会打断正在用手机的人），
# 注释理由是「LogX 有 5000 行上限，写满后静默停写，只能重启归零」。
# **那条理由本身就是错的**：LogX 写满后是**环形覆盖**（清空从头写 + 留
# `---- log rotated ----` 标记，见 LogX.kt），从来不「停写」。
# 而且 `op=logclear` 探针早就能清文件并归零计数（LogX.reset()）。
# ⇒ 清日志不需要重启进程，脚本比功能旧了一代。
#
# ⛔ 但去掉重启**丢了一个隐含保证**：旧流程的 `am start` 必定把微信拉到前台，
#    而后台时广播**不投递**（项目既有结论）。新流程必须自己补这道验证，
#    否则会安安静静打印几分钟前的旧日志 —— 那正是「对着旧输出下结论」的温床。
args = [x for x in sys.argv[1:] if not x.startswith("--")]
restart = "--restart" in sys.argv
if restart:
    print(">> 重启微信（装新包后必须做一次，否则跑的是旧模块代码）")
    sh("shell", "am", "force-stop", HOST)
    time.sleep(2)
    sh("shell", "am", "start", "-n", HOST + "/.ui.LauncherUI")
    time.sleep(9)

# ── 投递闸门①：logclear 必须真的执行 ──
bc("--es", "op", "logclear")
time.sleep(0.6)
if "PROBE logclear 已清" not in dbg_tail(600):
    print("!! logclear 没有执行：微信多半在后台，或模块未加载。")
    print("!! 先 `am start -n com.tencent.mm/.ui.LauncherUI` 切回微信再跑。")
    print("!! 本次**不会**打印任何日志，免得你对着过期数据下结论。")
    sys.exit(2)

cmd = args[0] if args else "dump"
extra = []
if cmd in ("show", "one", "del", "simdel") and len(args) > 1:
    extra = ["--el", "id", args[1]]
elif cmd == "dump":
    extra = ["--ei", "n", "8"]

before = dbg_size()
bc("--es", "op", "snsg", "--es", "sub", cmd, *extra)
time.sleep(6)

# ── 投递闸门②：探针必须产生新日志 ──
after = dbg_size()
if before >= 0 and after == before:
    print("!! 探针**没有输出**（日志大小未变 %dB）：微信可能在后台，或模块未响应。" % before)
    print("!! 本次**不会**打印旧日志。")
    sys.exit(3)

# 多数 sub 的输出没有统一前缀（dump/one 是逐行 LogX.i），一律读全文
for l in read_dbg("")[-45:]:
    print(l[:230])
