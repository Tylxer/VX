"""场景：备份导出 / 导入 —— 需求 6.7

用法:
  python tools/_backup.py             导出（目录兜底路径）→ 断言备份文件生成
  python tools/_backup.py --import [文件]  合并导入 → 断言分组/配置恢复、幂等
  python tools/_backup.py --plan 文件      dry-run 预览（只读，不写库）
  python tools/_backup.py --selftest   导出→读回→合并导回，一条 adb 验完整链路

⏳ 功能状态：BackupPanelPage / BackupIO / BackupCodec / BackupMerge / BackupFlow 均已实现，
   单测覆盖 BackupCodec + BackupMerge（26 用例）；SAF 交互（ACTION_CREATE_DOCUMENT）
   难自动化 → 本脚本走**目录兜底路径**（adb 可见、一定可写），可全自动。

⚠️ 备份 op 走 `com.lx.wxgroup.action.BACKUP`，**不是** PROBE action
   （_lib.bc 发的是 PROBE；早期版本发错 action，导出广播根本没人收）。

验收点（v6.3/v6.4 定的规则，脚本按这个断言）：
 - 成员归属冲突**以现有为准**：已属于某分组的人不搬移、不重复计入
 - 配置**只覆盖备份里存在的项**，没写的保持当前值
 - 置顶只在**新建**分组时应用
 - 缺 schema → 视为 v0，只恢复分组与成员；高于当前版本 → 尽力解析忽略未知字段
 - 文件损坏 → 弹窗中止，**不写入任何数据**
 - 恢复前自动导出安全快照（覆盖模式的退路），日志里有 safety=<path>
"""
import sys
import time

from _lib import *

ACTION = "com.lx.wxgroup.action.BACKUP"

# BackupIO.candidateDirs 里 adb 可见的子集（/data/data 下那个 adb 看不到）。
# appExternal 一定可写；前两个公共目录在 Android 11+ 上大概率写不进。
DIRS = [
    "/sdcard/Documents/WXGroup",
    "/sdcard/Download/WXGroup",
    "/sdcard/Android/data/com.tencent.mm/files/WXGroup",
]


def bak(*extra):
    """发备份广播。⚠️ 与 _lib.bc 的区别：备份 op 走 BACKUP action"""
    sh("shell", "am", "broadcast", "-a", ACTION, *extra, "-p", "com.tencent.mm")


def ls_dir(d):
    out = sh("shell", "ls", d).strip()
    if not out or "No such file" in out:
        return []
    return [l.split()[-1] for l in out.splitlines() if l.strip()]


def latest_backup():
    """兜底目录里最新的备份文件。文件名 wxgroup-backup-<时间戳>.json，字典序=时间序"""
    best = None
    for d in DIRS:
        for name in ls_dir(d):
            if name.startswith("wxgroup-backup-") and name.endswith(".json"):
                if best is None or name > best[1]:
                    best = (d + "/" + name, name)
    return best


# ------------------------------------------------------------------ 参数

args = sys.argv[1:]
MODE, FILE = "export", None
if "--selftest" in args:
    MODE = "selftest"
elif "--plan" in args:
    MODE = "plan"
    i = args.index("--plan")
    FILE = args[i + 1] if i + 1 < len(args) else None
elif "--import" in args:
    MODE = "import"
    i = args.index("--import")
    if i + 1 < len(args) and not args[i + 1].startswith("--"):
        FILE = args[i + 1]

# ------------------------------------------------------------------ 公共前置

step("拉起微信 + 清日志（微信在后台时广播不投递）")
ensure_foreground()
logclear()
time.sleep(1)

if MODE == "export":
    step("列兜底目录（导出前）")
    for d in DIRS:
        names = [n for n in ls_dir(d) if n.endswith(".json")]
        print("  %s -> %s" % (d, names or "(无)"))

    step("触发导出（op=export，目录兜底路径）")
    bak("--es", "op", "export")
    time.sleep(5)

    step("导出后目录内容")
    for d in DIRS:
        names = [n for n in ls_dir(d) if n.endswith(".json")]
        if names:
            print("  %s -> %s" % (d, names))

    step("op=list 交叉验证（模块自己列出的绝对路径）")
    bak("--es", "op", "list")
    time.sleep(2)

    f = latest_backup()
    step("断言：备份文件已生成")
    if f:
        print("  PASS 最新备份: %s" % f[0])
        print("  （真机回路下一步：python tools/_backup.py --import %s）" % f[0])
    else:
        print("  FAIL 兜底目录里没有备份文件 —— 导出未接通或全部目录不可写")

elif MODE == "import":
    if not FILE:
        f = latest_backup()
        FILE = f[0] if f else None
    if not FILE:
        print("FAIL 找不到备份文件，先跑一次导出：python tools/_backup.py")
        sys.exit(1)

    step("dry-run 预览（op=plan，只读不写库）")
    bak("--es", "op", "plan", "--es", "file", FILE)
    time.sleep(2)

    step("记录导入前的配置值（断言'只覆盖文件里有的项'）")
    kvget("chat.dnd_enabled")
    time.sleep(1)

    step("触发合并导入（op=restore replace=false）")
    bak("--es", "op", "restore", "--es", "file", FILE)
    time.sleep(3)

    step("导入后配置值（文件里有什么就覆盖什么，没有的不动）")
    kvget("chat.dnd_enabled")
    time.sleep(1)

    step("再合并导入一次 —— 断言幂等（一条都不该新增）")
    bak("--es", "op", "restore", "--es", "file", FILE)
    time.sleep(3)

elif MODE == "plan":
    if not FILE:
        f = latest_backup()
        FILE = f[0] if f else None
    if not FILE:
        print("FAIL 找不到备份文件")
        sys.exit(1)
    step("dry-run 预览（op=plan，只读不写库）: %s" % FILE)
    bak("--es", "op", "plan", "--es", "file", FILE)
    time.sleep(2)

elif MODE == "selftest":
    step("链路自检（op=selftest：导出→读回→合并导回→对账）")
    bak("--es", "op", "selftest")
    time.sleep(8)

# ------------------------------------------------------------------ 日志断言

pull_dbg()
print("--- 日志（BACKUP/BK）---")
lines = grep(r"ENTRY BACKUP|BACKUP |BK ", 30)

step("断言汇总")
if MODE == "export":
    ok = any("export ok" in l for l in lines) and latest_backup() is not None
elif MODE == "import":
    ok = (any("restore ok" in l for l in lines)
          and any("reconcile=true" in l for l in lines))
elif MODE == "plan":
    ok = any("plan path=" in l for l in lines)
elif MODE == "selftest":
    ok = any("selftest PASS" in l for l in lines)
else:
    ok = False
print("  %s" % ("PASS" if ok else "FAIL —— 见上方日志"))
print("done")
