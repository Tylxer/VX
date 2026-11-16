#!/usr/bin/env python3
"""R54 阶段0：伪会话写库探针的 PC 侧一键入口。

用法：
  python tools/_r54_gconv.py stat
  python tools/_r54_gconv.py trial
  python tools/_r54_gconv.py rename 新组名
  python tools/_r54_gconv.py clean
  python tools/_r54_gconv.py sql "select username,flag from rconversation where substr(username,1,6)='wxgrp_'"

生产侧（阶段2 写库 Store，`op=gcs`；与探针 `op=gconv` 分离以免 sub 冲突）：
  python tools/_r54_gconv.py gcs stat
  python tools/_r54_gconv.py gcs sync
  python tools/_r54_gconv.py gcs clean

为什么要自己发广播而不用 `dev.py probe`：
  `dev.py probe` 的 `--q` 固定映射到 extra `q`（那是 `op=sql` 朋友圈探针用的），
  而 `op=gconv` 读的是 extra `sql` / `val`，没有对应开关。硬用会把 SQL 丢进 `q`，
  广播照样回 `completed`、探针却一行不跑 —— 上一轮栽过，别再猜。

引号坑（都在这里固化）：
  `adb shell` 会二次解析参数，值必须再包一层单引号；
  `>` / `(` / `,` 在值里只有被单引号包住才不会被 sh 当重定向或语法。
"""
import os
import shutil
import subprocess
import sys
import time

# 本会话的 bash 里 adb 不在 PATH（实测 `adb: command not found`），
# 优先用 ADB 环境变量，其次 PATH，最后回落到默认 SDK 路径。
ADB = (
    os.environ.get("ADB")
    or shutil.which("adb")
    or os.path.join(
        os.path.expanduser("~"), "AppData", "Local", "Android", "Sdk",
        "platform-tools", "adb.exe",
    )
)
ACT = "com.lx.wxgroup.action.PROBE"
HOST = "com.tencent.mm"
WAIT = 2.5
# 生产侧 op=gcs 改成 post 到后台单线程后是异步落日志：一次 sync 要全表读 3120 行
# 再写微信主库，2.5s 不一定够 ⇒ 给 gcs 单独放宽到 5s。
# 若仍看不到 `sync done own=`，隔几秒手动再 `python tools/dev.py q GCONVSTORE 40` 拉一次。
WAIT_GCS = 5.0


def sh(*args):
    return subprocess.run(
        [ADB, "shell", " ".join(args)],
        capture_output=True, text=True, encoding="utf-8", errors="ignore",
    ).stdout.strip()


def bc(extra, op="gconv"):
    """拼一条广播命令。extra = [sub, (flag, key, value)...]，每个 extra 是三元组。

    `am broadcast --es <key> <value>`：flag 和 key 原样，只有 value 包单引号。
    之前按二元组 (key, val) 解析，`--es val 新名字` 会被读成 key='--es'、val='val'，
    剩一个孤儿 token ⇒ IndexError。
    `op` 区分阶段0 探针（gconv）与阶段2 生产 Store（gcs）。
    """
    sub = extra[0]
    parts = [
        "am", "broadcast", "-a", ACT, "-p", HOST,
        "--es", "op", op, "--es", "sub", "'" + sub + "'",
    ]
    rest = extra[1:]
    i = 0
    while i + 2 < len(rest):
        flag, key, value = rest[i], rest[i + 1], rest[i + 2]
        parts += [flag, key, "'" + value.replace("'", "'\\''") + "'"]
        i += 3
    return sh(*parts)


def pull_log(tag="GCONV", n=40):
    r = subprocess.run(
        [sys.executable, "tools/dev.py", "q", tag, str(n)],
        capture_output=True, text=True, encoding="utf-8", errors="ignore",
    )
    return r.stdout.strip()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    sub = sys.argv[1]
    if sub == "gcs":
        # 生产侧 GroupConvStore：日志每行带 GCONVSTORE 前缀。
        if len(sys.argv) < 3 or sys.argv[2] not in ("sync", "clean", "stat"):
            print("gcs 需要 sub: sync|clean|stat")
            return
        op, extra, tag = "gcs", [sys.argv[2]], "GCONVSTORE"
    elif sub in ("stat", "trial", "rename", "clean", "sql"):
        op, extra, tag = "gconv", [sub], "GCONV"
        if sub == "rename":
            if len(sys.argv) < 3:
                print("rename 需要新组名")
                return
            extra += ["--es", "val", sys.argv[2]]
        elif sub == "sql":
            if len(sys.argv) < 3:
                print("sql 需要 SQL")
                return
            extra += ["--es", "sql", sys.argv[2]]
    else:
        print("未知 sub:", sub)
        return

    sh("am", "broadcast", "-a", ACT, "-p", HOST, "--es", "op", "logclear")
    time.sleep(0.5)
    # 微信在后台时广播不投递（AMS 仍回 completed），先拉前台
    sh("am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(1.2)
    bc(extra, op)
    time.sleep(WAIT_GCS if op == "gcs" else WAIT)
    print(pull_log(tag))


if __name__ == "__main__":
    main()
