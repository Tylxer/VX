#!/bin/sh
# 开发回路脚本（Git Bash 下运行）
#   sh dev.sh build     编译 debug APK（实测增量 14s，不是瓶颈，别费劲优化它）
#   sh dev.sh install   安装 + 同步 LSPosed 模块路径 + 确保启用
#   sh dev.sh restart   杀微信进程让它重新加载模块
#   sh dev.sh log       只看本模块日志
#   sh dev.sh all       build + install + restart
#
# 真机回路的耗时大头是 install + LSPosed apk_path 修复 + 场景脚本里的 sleep，
# 所以最有效的提速是**减少回路轮次**：把改动攒齐一次上机，别改一处上一轮。
# 一条命令跑完整回路（含日志过滤）：python tools/dev.py loop <场景名> --grep '关键字'
#
# ⚠️ 真机脚本放 tools/（进版本库），不要放 _cache/（整个被 .gitignore 忽略，会丢）。
#
# 用法前先修 PATH（WorkBuddy 的 Bash 工具 PATH 是坏的）：
#   export PATH="/usr/bin:/bin:/mingw64/bin:/usr/local/bin:$PATH"

# ⚠️ 绝对不要在这里拼 $PATH！本机 PATH 里有被 Git Bash 转坏、带空格的条目
# （…\Program Files\WorkBuddy\resources\vendor\PortableGit\Program）。
# Gradle 启动 test worker 时会把整个 PATH 塞进 -Djava.library.path，
# 参数在空格处断裂 → worker 报 ClassNotFoundException: Files\WorkBuddy\...\Program
# （表现为：编译全过、只有 test 任务失败，极难查）。
export PATH="/usr/bin:/bin:/mingw64/bin:/usr/local/bin:C:/Windows/System32:C:/Windows"
export JAVA_HOME="C:/Users/lx/WorkBuddy/2026-09-29-20-23-21/_cache/jdk21"
export GRADLE_USER_HOME="C:/Users/lx/WorkBuddy/2026-09-29-20-23-21/_cache/gradlehome"

ADB="C:/Users/lx/AppData/Local/Android/Sdk/platform-tools/adb.exe"
# 仓库根从脚本自身位置推导（dev.sh 位于 <root>/wxgroup/）：兼容 git worktree，
# 并行开发时每路工作树跑自己树里的 dev.sh，构建/安装自己树产出的 APK。
# JAVA_HOME / GRADLE_USER_HOME 仍指向主工作区 _cache：JDK 与依赖缓存跨树共享是有意的。
ROOT="$(cd "$(dirname "$0")/.." && pwd -W)"
PY="C:/Users/lx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
PKG=com.lx.wxgroup
HOST=com.tencent.mm
APK="$ROOT/wxgroup/app/build/outputs/apk/debug/app-debug.apk"

case "$1" in
build)
    cd "$ROOT/wxgroup" && sh gradlew :app:assembleDebug --no-daemon --console=plain 2>&1 | grep -E "^e:|BUILD|FAILED" | head -30
    ;;
install)
    "$ADB" install -r "$APK" 2>&1 | tail -2
    sh "$0" sync
    ;;
sync)
    # 只读检查：确认 LSPosed 里本模块的 apk_path / enabled / scope 是否正确。
    # ⚠️ 绝对不要写回这个 db！LSPosed 用 WAL 模式，只覆盖主 db 而留下旧 -wal
    # 会让 Android 端判定 "database disk image is malformed"，lspd 守护进程
    # 崩溃后不再注入任何进程（模块的日志会彻底消失，极难查）。
    # 实测 LSPosed 会在 APK 安装时自动更新 apk_path，所以通常无需手工干预；
    # 真要改就用手机上的 LSPosed 管理器勾选。
    P=$("$ADB" shell pm path $PKG | tr -d '\r' | sed 's/^package://')
    echo "当前 APK 路径: $P"
    # ⚠️ LSPosed 的 db 是 **WAL 模式**，主 db 往往还没 checkpoint（mtime 停在几天前），
    #    新数据全在 -wal 里。只 cp 主 db ⇒ 读到的是 WAL 之前的旧内容 ⇒ apk_path **必然误报 STALE**。
    #    2026-10-24 实测：主 db mtime = 10-19 23:36，-wal 416KB mtime = 本次安装时刻；
    #    带三件套回放后 apk_path/enabled/scope 全部正确 —— 假警报，白折腾一次「去管理器勾选」。
    #    ⇒ 必须 db + -wal + -shm 三件套一起 cp、一起 pull，本地 sqlite 才会回放 WAL。
    "$ADB" shell "su -c 'cp /data/adb/lspd/config/modules_config.db /sdcard/_m.db && cp /data/adb/lspd/config/modules_config.db-wal /sdcard/_m.db-wal && cp /data/adb/lspd/config/modules_config.db-shm /sdcard/_m.db-shm && chmod 666 /sdcard/_m.db*'" >/dev/null 2>&1
    for f in _m.db _m.db-wal _m.db-shm; do "$ADB" pull "/sdcard/$f" "$ROOT/_cache/$f" >/dev/null 2>&1; done
    PKGNAME=$PKG APKPATH="$P" HOSTPKG=$HOST "$PY" "$ROOT/tools/_check.py" "$ROOT/_cache/_m.db"
    ;;
test)
    # JVM 单测：只跑纯 Kotlin 逻辑（不依赖 Android/Xposed 的部分，如 SQL 改写）。
    # ⚠️ PATH 不能有空格，否则 gradle 把整个 PATH 塞进 -Djava.library.path 后
    # 参数在空格处断裂，test worker 报 ClassNotFoundException（见用户级记忆）。
    cd "$ROOT/wxgroup" && sh gradlew :app:testDebugUnitTest --no-daemon --console=plain 2>&1 \
        | grep -E "^e:|BUILD|FAILED|tests? completed|AntiRevokeHint" | head -30
    ;;
restart)
    # 这台设备没有 pidof，pkill/killall 也杀不掉微信（有守护拉起），
    # 只有 am force-stop 有效；再用 monkey 把它拉回前台。
    "$ADB" shell am force-stop $HOST 2>/dev/null
    sleep 2
    "$ADB" shell "monkey -p $HOST -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
    sleep 8
    echo "微信已重启"
    ;;
log)
    "$ADB" logcat -v time -s WXGroup:* 2>&1
    ;;
panel)
    "$ADB" shell am broadcast -a $PKG.action.PANEL -p $HOST 2>&1 | tail -3
    ;;
all)
    sh "$0" build && sh "$0" install && sh "$0" restart
    ;;
*)
    echo "用法: sh dev.sh build|install|sync|restart|log|panel|all"
    ;;
esac
