"""M7c-2：细看 kvset -> 迁移 的全过程（不过滤日志）"""
import time
import _lib as L

OLD = "/storage/emulated/0/Android/data/com.tencent.mm/files/WechatXposed/moments"

L.ensure_foreground()
L.logclear()
L.step("kvset -> OLD")
L.kvset("moments.export_path", OLD)
time.sleep(2.0)
L.pull_dbg()
print("=== 全量日志（kvset 之后 2s） ===")
L.grep(r".", 40)
