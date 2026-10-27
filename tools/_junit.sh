#!/bin/bash
# 直接跑某个 JUnit4 测试类（**绕过 gradle test 任务**）。
#
# 为什么需要它：本机 gradle 的 `testDebugUnitTest` 极不稳定 ——
#   - worker 随机 `finished with non-zero exit value 1`（JVM 启动即死，管道被关闭）
#   - XML 结果延迟刷盘（跑完 ls 是 0 个，10 秒后才出现）
#   - `--tests` 过滤会让主类被含空格路径拆碎（ClassNotFoundException: Files\...\PortableGit\Program）
# ⇒ 判据不能建在 exit code 和 XML 上。这里用 gradle **只做编译**，
#   再用 java + JUnitCore 跑，输出与退出码都可信（实测 13 例 0.038 秒）。
#
# 用法：
#   bash tools/_junit.sh                                  # ⭐ 跑**全部**测试类（推送前闸门用这个）
#   bash tools/_junit.sh com.lx.wxgroup.feature.SomeTest  # 只跑指定类（调试用，可传多个）
#   退出码 0 = 全过；非 0 = 有失败（stderr 里带失败详情）
#
# ⛔ 别再把默认值当成「单测通过」：这个脚本原默认只跑 SnsTimelineMergeTest 一个类（13 例），
#    而仓库里有 41 个测试类。用单类结果当「全过」是假判据 —— 推送/装包前的闸门必须是全量。
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT/wxgroup" || exit 1

JAVA="G:/Program Files/Microsoft/jdk-21.0.7.6-hotspot/bin/java.exe"
GC=/c/Users/lx/.gradle/caches/modules-2/files-2.1

JUNIT=$(ls $GC/junit/junit/4.13.2/*/junit-4.13.2.jar 2>/dev/null | head -1)
HAM=$(ls $GC/org.hamcrest/hamcrest-core/1.3/*/hamcrest-core-1.3.jar 2>/dev/null | head -1)
KSTD=$(ls $GC/org.jetbrains.kotlin/kotlin-stdlib/2.0.21/*/kotlin-stdlib-2.0.21.jar 2>/dev/null | head -1)
# BackupMergeTest 等用 gson 解析 JSON，主 classpath 必须带它 —— 缺了会报
# `NoClassDefFoundError: com/google/gson/JsonSyntaxException`（看着像 17 个测试挂了，
# 实际只是 jar 没上 CP）。版本跟 app/build.gradle.kts 的 implementation 保持一致。
GSON=$(ls $GC/com.google.code.gson/gson/2.11.0/*/gson-2.11.0.jar 2>/dev/null | head -1)
for j in "$JUNIT" "$HAM" "$KSTD" "$GSON"; do
  [ -f "$j" ] || { echo "missing jar: $j" >&2; exit 2; }
done

MAIN=app/build/tmp/kotlin-classes/debug
TEST=app/build/tmp/kotlin-classes/debugUnitTest
[ -d "$TEST" ] || { echo "no test classes: $TEST（先编译）" >&2; exit 2; }

if [ $# -ge 1 ]; then
  CLASSES="$*"
else
  # 枚举全部测试类（排除内部类 $）。类名 = 相对路径去掉 .class、'/' 换 '.'
  CLASSES=$(cd "$TEST" && find . -name '*Test.class' ! -name '*$*' \
            | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort | tr '\n' ' ')
  if [ -z "${CLASSES// /}" ]; then
    echo "no *Test.class under $TEST（先 compileDebugUnitTestKotlin）" >&2
    exit 2
  fi
fi

CP="$MAIN;$TEST;$(cygpath -w "$JUNIT");$(cygpath -w "$HAM");$(cygpath -w "$KSTD");$(cygpath -w "$GSON")"
"$JAVA" -cp "$CP" org.junit.runner.JUnitCore $CLASSES

