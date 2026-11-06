"""灰标判据变异验证：逐个改 SnsDeletedTagLogic.kt -> 只跑 SnsDeletedTagLogicTest -> 还原。

用法（cwd 必须是 wxgroup/）：
    python app/src/test/mut/mut_deltag.py

纪律：语义变异必须变红；每组变异之后还必须跑一个**等价变异**并确认它保持全绿，
否则「8 组全红」可能只是「随便动一下都会红」造成的假验证。
"""
import io, subprocess, sys, os, hashlib

LOGIC = 'app/src/main/java/com/lx/wxgroup/feature/SnsDeletedTagLogic.kt'

MUTS = [
    # —— N1/N6 合并治理：错位常数可信度（Learned/reconcile/consistent/vote/anchorOffset/shouldResweep/calibrate）
    ("M1 reconcile 去掉头部签名校验（旧常数永不失效 ⇒ N1 复活）",
     "if (stored != null && stored.headerSig == headerSig) stored else null",
     "if (stored != null) stored else null"),
    ("M2 consistent 符号反转（0 与 headerSig 不再互斥 ⇒ 毒样本 0 可落票）",
     "fun consistent(offset: Int, headerSig: Int): Boolean = offset == -headerSig",
     "fun consistent(offset: Int, headerSig: Int): Boolean = offset == headerSig"),
    ("M3 vote 不累票（同偏移票数不 +1 ⇒ 重确认计数失效）",
     "return Learned(offset, minOf(stored.votes + 1, 2), headerSig)",
     "return Learned(offset, stored.votes, headerSig)"),
    ("M4 anchorOffset 分支 1 无脑沿用（learned 不在 passing 也沿用 ⇒ 过期常数乱锚）",
     "if (learned != null && learned.offset in passing) return learned.offset",
     "if (learned != null) return learned.offset"),
    ("M5 anchorOffset 保守退回 firstOrNull（歧义窗口不再保守 null ⇒ 相邻同名串位）",
     "return passing.singleOrNull()",
     "return passing.firstOrNull()"),
    ("M6 shouldResweep 重新加 after!=0（0 不再触发 ⇒ N6 复活）",
     "before == null && after != null",
     "before == null && after != null && after.offset != 0"),
    ("M7 calibrate 永不投票（无歧义窗口不落票 ⇒ resweep 永不触发）",
     "val after = if (passing.size == 1) vote(learned, d, headerSig) else learned",
     "val after = learned"),
    # —— B1/B2 回归：头部签名观测 observeHeader（`==` 复位判据 + `pos+1` 前移）
    ("M8 observeHeader 复位判据 == 改回 <=（封面重绑被误判成头部变多 ⇒ 签名前移）",
     "return if (pos == prev) pos + 1 else prev",
     "return if (pos <= prev) pos + 1 else prev"),
    ("M9 observeHeader 前移 pos+1 改回 -1（头部变多后退化失明 ⇒ 签名未知）",
     "return if (pos == prev) pos + 1 else prev",
     "return if (pos == prev) -1 else prev"),
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


def run_once():
    with open('_mut_out.txt', 'w', encoding='utf-8', errors='replace') as fh:
        # shell=False + 绝对路径式命令：cmd 里 './gradlew.bat' 不合法，别踩这个坑
        subprocess.run(GRADLE, env=env, stdout=fh, stderr=fh, cwd='.', shell=False)
    out = io.open('_mut_out.txt', encoding='utf-8', errors='replace').read()
    fails = sorted({l.strip() for l in out.splitlines()
                    if 'FAILED' in l and 'Task' not in l})
    done = [l.strip() for l in out.splitlines() if 'tests completed' in l]
    status = done[-1] if done else ('BUILD SUCCESSFUL（0 条变红！）'
                                    if 'BUILD SUCCESSFUL' in out else '???')
    return fails, status


try:
    for name, old, new in MUTS:
        assert src.count(old) == 1, (name, src.count(old))
        io.open(LOGIC, 'w', encoding='utf-8', newline='').write(src.replace(old, new))
        try:
            fails, status = run_once()
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

    # —— 等价变异对照：语义完全等价，必须保持全绿（证明「变红」不是「随便改都会红」）
    EQ_NAME = 'EQ shouldResweep De Morgan 等价改写（必须全绿）'
    EQ_OLD = "before == null && after != null"
    EQ_NEW = "!(before != null || after == null)"
    assert src.count(EQ_OLD) == 1, (EQ_NAME, src.count(EQ_OLD))
    io.open(LOGIC, 'w', encoding='utf-8', newline='').write(src.replace(EQ_OLD, EQ_NEW))
    try:
        fails, status = run_once()
        print('=== %s' % EQ_NAME)
        for f in fails:
            print('    ' + f)
        print('    ' + status)
        eq_green = not fails
        print('    %s' % ('✓ 等价变异保持全绿' if eq_green else '⛔ 等价变异变红 —— 判据可能在守等价改写'))
    finally:
        io.open(LOGIC, 'w', encoding='utf-8', newline='').write(src)
    print()
finally:
    # 无论中途怎么崩都必须把源码还原，并用 hash 自证
    io.open(LOGIC, 'w', encoding='utf-8', newline='').write(src)
    now_sha = hashlib.sha256(io.open(LOGIC, encoding='utf-8').read().encode('utf-8')).hexdigest()
    assert now_sha == src_sha, '⛔ 源码没有被还原到原始状态！'

print('=%s' % ('=' * 60))
print('语义变异组数 %d / 变红组数 %d' % (len(MUTS), red))
print('等价变异 EQ：%s' % ('全绿 ✓' if eq_green else '变红 ✗'))
print('源码已还原：sha256 %s' % now_sha[:12])
