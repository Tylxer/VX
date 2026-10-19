r"""跑单测 / 编译：精简 PATH（去掉带空格的脏条目）+ 指定 JAVA_HOME。

⚠️ 本机 PATH 里有形如 `...\Program Files\...` 的脏条目，
   Gradle test worker 会把它们当 classpath 切 → `ClassNotFoundException: Files\\WorkBuddy\\...`。
   ⇒ 必须把 PATH 里**含空格**的项整个剔除，而不是简单清空（清空会连 grep/git 都没了）。
"""
import os
import subprocess
import sys

GRADLE = r"C:\Users\lx\.workbuddy\binaries\gradle\gradle-8.9\bin\gradle.bat"
JAVA_HOME = r"G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot"
ROOT = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\wxgroup"


def clean_path() -> str:
    keep = []
    for p in os.environ.get("PATH", "").split(os.pathsep):
        if not p.strip():
            continue
        # 带空格的条目 = 脏条目（Gradle 会按空格切）
        if " " in p:
            continue
        keep.append(p)
    return os.pathsep.join(keep)


task = sys.argv[1] if len(sys.argv) > 1 else "testDebugUnitTest"
env = dict(os.environ)
env["JAVA_HOME"] = JAVA_HOME
env["PATH"] = clean_path()

# ⚠️ 不用 shell 管道（Windows 下 gradle.bat + 管道会丢输出），
#    直接捕获全量输出再在 Python 里过滤。
r = subprocess.run(
    [GRADLE, "--offline", task],
    cwd=ROOT, env=env, capture_output=True, text=True,
    encoding="utf-8", errors="ignore",
)
out = (r.stdout or "") + (r.stderr or "")
print(f"EXIT={r.returncode}")

keys = ("^e:", "FAILED", "BUILD ", "tests completed", "error:", "Exception")
for line in out.splitlines():
    if any(k.strip() in line for k in keys):
        print(line)
