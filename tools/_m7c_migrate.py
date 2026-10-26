"""M7 后置验证：export_path 默认值改成公共目录 + 旧路径迁移

三条判据：
1. kvset 旧私有路径 -> 冷启 -> 日志出现 `MOMENT export path migrated:`
2. `op=moments sub=path` 的 root = Documents/WXGroup/moments 且 writable=true
3. kvget 回读 = 新默认值（证明真写回库，不是只改内存）

⚠️ adb shell 会二次解析参数：路径无空格，但中文/特殊字符值须单引号包裹，写完必须回读。
"""
import time
import _lib as L

OLD = "/storage/emulated/0/Android/data/com.tencent.mm/files/WechatXposed/moments"
NEW = "/storage/emulated/0/Documents/WXGroup/moments"


def coldstart(times=1, tag=""):
    for i in range(times):
        L.sh("shell", "am", "force-stop", "com.tencent.mm")
        time.sleep(1.5)
        L.sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
        time.sleep(6)
    L.step("coldstart x%d done %s" % (times, tag))


def main():
    L.step("=== 0. 装包后冷启两次（模块常不重载） ===")
    coldstart(2)

    L.step("=== 1. 把 export_path 设回旧私有路径（复现老用户） ===")
    L.logclear()
    L.kvset("moments.export_path", OLD)
    time.sleep(1.5)
    L.kvget("moments.export_path")
    time.sleep(1.5)
    L.pull_dbg()
    print("--- kvget(设置后) ---")
    L.grep(r"kv ", 6)

    L.step("=== 2. 冷启，看是否自动迁移 ===")
    L.logclear()
    coldstart(1, "for-migrate")
    L.bc("--es", "op", "moments", "--es", "sub", "path")
    time.sleep(2.5)
    L.pull_dbg()
    print("--- migrated / path ---")
    L.grep(r"path migrated|MOMENT path|MOMENT export path", 12)

    L.step("=== 3. kvget 回读，证明写回库 ===")
    L.kvget("moments.export_path")
    time.sleep(2)
    L.pull_dbg()
    print("--- kvget(迁移后) ---")
    L.grep(r"kv ", 6)


main()
