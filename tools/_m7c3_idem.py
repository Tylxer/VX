"""M7c-3：迁移幂等性 + 端到端一次真实导出

判据：
A. 再次触发 refresh（sub=stat）不应出现第二条 `path migrated` —— 证明写回库有效、不反复重写
B. scan 应能数到文件；export 应 written>0 或 skipped=已有
C. auto_export / export_list 现状留档（避免静默全量导出）
"""
import time
import _lib as L

L.ensure_foreground()
L.logclear()

L.step("A. 连打两次 stat（间隔 > TTL 3s），看是否重复迁移")
L.bc("--es", "op", "moments", "--es", "sub", "stat")
time.sleep(4.5)
L.bc("--es", "op", "moments", "--es", "sub", "stat")
time.sleep(2.5)
L.pull_dbg()
print("--- 迁移出现次数 ---")
L.grep(r"path migrated", 6)
print("--- stat ---")
L.grep(r"MOMENT cfg|MOMENT stat", 6)

L.step("B. kvget 两个关键配置现状")
L.kvget("moments.export_path")
time.sleep(0.8)
L.kvget("moments.export_list")
time.sleep(0.8)
L.kvget("moments.auto_export")
time.sleep(2.0)
L.pull_dbg()
print("--- kv ---")
L.grep(r"kv ", 8)
