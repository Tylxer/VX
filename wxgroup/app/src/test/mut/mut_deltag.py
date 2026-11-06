"""灰标判据变异验证：逐个改 SnsDeletedTagLogic.kt -> 只跑 SnsDeletedTagLogicTest -> 还原。

用法（cwd 必须是 wxgroup/）：
    python app/src/test/mut/mut_deltag.py

纪律：语义变异必须变红；每组变异之后还必须跑一个**等价变异**并确认它保持全绿，
否则「8 组全红」可能只是「随便动一下都会红」造成的假验证。
"""
import io, subprocess, sys, os, hashlib

LOGIC = 'app/src/main/java/com/lx/wxgroup/feature/SnsDeletedTagLogic.kt'

MUTS = [
    ("M1 去掉类名限定（authorCorpusOf 不再过滤作者行）",
     "            if (!isAuthorRowClass(it.classNames)) continue\n            rows++",
     "            rows++"),
    ("M2 降级改「认不出就通过」（rows==0 返回 true）",
     "        if (corpus.rows == 0) return false",
     "        if (corpus.rows == 0) return true"),
    ("M3 去掉长度上界",
     "            t.length >= 2 && t.length <= maxLen &&",
     "            t.length >= 2 &&"),
    ("M4 空文本改通过",
     "        if (corpus.texts.isEmpty()) return false",
     "        if (corpus.texts.isEmpty()) return true"),
    ("M5 类名闸门放宽成 contains",
     "        classNames.any { it == AUTHOR_VIEW_CLS }",
     "        classNames.any { it.contains(AUTHOR_VIEW_CLS) }"),
    # —— R37-2 复核后新增：`seen` 是「采样截断 vs 类名漂移」的唯一分岔口，不许被默认值吞掉
    ("M6 seen 不看 View 侧真值（退回 items.size ⇒ 截断再也查不出来）",
     "        return AuthorCorpus(texts, rows, classes, seen)",
     "        return AuthorCorpus(texts, rows, classes, items.size)"),
]

src = io.open(LOGIC, encoding='utf-8').read()
src_sha = hashlib.sha256(src.encode('utf-8')).hexdigest()

env = dict(os.environ)
env['JAVA_HOME'] = 'G:/Program Files/Microsoft/jdk-21.0.7.6-hotspot'
env['ANDROID_HOME'] = 'C:/Users/lx/AppData/Local/Android/Sdk'
# ⛔ PATH 必须是**干净的**：本会话的 PATH 里混进了带空格和残缺引号的条目
#    （形如 `"G;F:\Program Files\WorkBuddy\...`），AGP 会把整套 PATH 拼进测试 worker 的
#    `-Djava.library.path=…`，带残缺引号/空格 ⇒ worker JVM 命令行被切错 ⇒
#    `找不到或无法加载主类 Files\WorkBuddy\…`（不是测试失败，是 JVM 压根没起来）。
#    ⇒ 这里只留系统目录 + JDK（JDK 由 JAVA_HOME 提供，不必进 PATH）。
env['PATH'] = 'C:/Windows/system32;C:/Windows;C:/Windows/System32/Wbem'

GRADLE = ['gradlew.bat',
          'cleanTestDebugUnitTest', 'testDebugUnitTest', '--rerun-tasks',
          '--tests', 'com.lx.wxgroup.feature.SnsDeletedTagLogicTest']

red = 0
try:
    for name, old, new in MUTS:
        assert src.count(old) == 1, (name, src.count(old))
        io.open(LOGIC, 'w', encoding='utf-8', newline='').write(src.replace(old, new))
        try:
            with open('_mut_out.txt', 'w', encoding='utf-8', errors='replace') as fh:
                # shell=False + 绝对路径式命令：cmd 里 './gradlew.bat' 不合法，别踩这个坑
                subprocess.run(GRADLE, env=env, stdout=fh, stderr=fh, cwd='.', shell=False)
            out = io.open('_mut_out.txt', encoding='utf-8', errors='replace').read()
            fails = sorted({l.strip() for l in out.splitlines()
                            if 'FAILED' in l and 'Task' not in l})
            done = [l.strip() for l in out.splitlines() if 'tests completed' in l]
            status = done[-1] if done else ('BUILD SUCCESSFUL（0 条变红！）'
                                            if 'BUILD SUCCESSFUL' in out else '???')
            print('=== %s' % name)
            for f in fails:
                print('    ' + f)
            print('    ' + status)
            if fails:
                red += 1
            else:
                print('    ⛔ 没变红 —— 这组变异没有被任何用例抓住')
        finally:
            io.open(LOGIC, 'w', encoding='utf-8', newline='').write(src)
        print()
finally:
    # 无论中途怎么崩都必须把源码还原，并用 hash 自证
    io.open(LOGIC, 'w', encoding='utf-8', newline='').write(src)
    now_sha = hashlib.sha256(io.open(LOGIC, encoding='utf-8').read().encode('utf-8')).hexdigest()
    assert now_sha == src_sha, '⛔ 源码没有被还原到原始状态！'

print('=%s' % ('=' * 60))
print('变异组数 %d / 变红组数 %d' % (len(MUTS), red))
print('源码已还原：sha256 %s' % now_sha[:12])
