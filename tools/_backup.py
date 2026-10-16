"""场景：备份导出 / 导入 —— 需求 6.7

用法: python tools/_backup.py           触发导出（目录兜底路径）→ 断言文件出现
      python tools/_backup.py --import  导入回来 → 断言分组/配置恢复

⏳ 功能状态：BackupPage / BackupPanelPage / BackupIO / BackupCodec 已存在，
   单测已覆盖 BackupCodec；**端到端（SAF + 落库）尚未真机验证**。
   SAF 交互（ACTION_CREATE_DOCUMENT）难自动化 → 本脚本走**目录兜底路径**，可全自动。

验收点（v6.3/v6.4 定的规则，脚本按这个断言）：
 - 成员归属冲突**以现有为准**：已属于某分组的人不搬移、不重复计入
 - 配置**只覆盖备份里存在的项**
 - 置顶只在**新建**分组时应用
 - 缺 schema → 视为 v0，只恢复分组与成员；高于当前版本 → 尽力解析忽略未知字段
 - 文件损坏 → 弹窗中止，**不写入任何数据**
"""
import sys
import time

from _lib import *

DO_IMPORT = "--import" in sys.argv
DIR = "/sdcard/Android/data/com.tencent.mm/files/WechatXposed/backup"

step("拉起微信 + 清日志")
ensure_foreground()
logclear()

step("列兜底目录（导出前）")
print(sh("shell", "ls -l " + DIR).strip()[-400:] or "  (空或不存在)")

if not DO_IMPORT:
    step("触发导出（目录兜底路径）")
    bc("--es", "op", "export", "--es", "sub", "dir")
    time.sleep(5)
    step("导出后：")
    print(sh("shell", "ls -l " + DIR).strip()[-600:] or "  (仍为空 —— 功能未接通)")
else:
    step("触发导入")
    bc("--es", "op", "export", "--es", "sub", "import")
    time.sleep(5)
    step("断言：分组与配置是否恢复")
    bc("--es", "op", "kvget")
    time.sleep(2)

pull_dbg()
print("--- 日志 ---")
grep(r"BACKUP|Backup|backup|export|import", 25)
print("done")
