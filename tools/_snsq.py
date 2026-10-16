"""查归档详情 + 可选恢复可见性。用法： python _snsq.py [dump|show <snsId>|stat]"""
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


def bc(*extra):
    return sh("shell", "am", "broadcast", "-a", "com.lx.wxgroup.action.PROBE",
              *extra, "-p", "com.tencent.mm")


def read_dbg(tag):
    """tag='' 时返回全部行（dump 的多行输出没有前缀，grep 会漏）"""
    sh("pull", DBG, ROOT + r"\_sns_dbg.txt")
    try:
        txt = open(ROOT + r"\_sns_dbg.txt", encoding="utf-8", errors="ignore").read()
    except OSError:
        return []
    return [l for l in txt.splitlines() if not tag or tag in l]


sh("start-server")
sh("wait-for-device")
# ⚠️ LogX 有 5000 行上限，写满后静默停写 → 必须重启微信进程（模块重载，lines 归零）
sh("shell", "am", "force-stop", "com.tencent.mm")
time.sleep(2)
sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
time.sleep(9)
sh("shell", "rm", "-f", DBG)
time.sleep(1)

cmd = sys.argv[1] if len(sys.argv) > 1 else "dump"
extra = []
if cmd in ("show", "one", "del", "simdel") and len(sys.argv) > 2:
    extra = ["--el", "id", sys.argv[2]]
elif cmd == "dump":
    extra = ["--ei", "n", "8"]
bc("--es", "op", "snsg", "--es", "sub", cmd, *extra)
time.sleep(6)
# 多数 sub 的输出没有统一前缀（dump/one 是逐行 LogX.i），一律读全文
for l in read_dbg("")[-45:]:
    print(l[:230])
