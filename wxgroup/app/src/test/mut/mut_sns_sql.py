"""R42 方案 B 变异验证：逐个改 SnsSqlRewrite.kt -> 只跑 SnsSqlRewriteTest -> 还原。

用法（cwd 必须是 wxgroup/）：
    python app/src/test/mut/mut_sns_sql.py

⛔ 与 _mut.py 同样的坑：PATH 必须是干净的（本会话 PATH 里有带空格/残缺引号的条目，
   AGP 会把它拼进测试 worker 的 -Djava.library.path ⇒ JVM 起不来 ⇒「0 条变红」的假象）。
"""
import io, os, subprocess, sys, hashlib

LOGIC = 'app/src/main/java/com/lx/wxgroup/feature/SnsSqlRewrite.kt'

MUTS = [
    ("M1 rewrite 不切 WHERE（整条 SQL 都替换）",
     "        val cut = whereIndex(sql)                 // -1 = 没有 WHERE",
     "        val cut = -1                              // MUT"),
    ("M2 shouldBlock 不切 SET（WHERE 里的同名表达式也当成可拦）",
     "        return CLEAR_BIT3_ASSIGN.containsMatchIn(setPart(sql))",
     "        return CLEAR_BIT3_ASSIGN.containsMatchIn(sql)"),
    ("M3 掩码写成 -3（把时间线位也削掉）",
     '    const val NOOP_MASK = "-1"',
     '    const val NOOP_MASK = "-3"'),
    ("M4 去掉「必须是 UPDATE」前提",
     "        if (!isUpdateOfSnsInfo(sql)) return false",
     "        if (false) return false"),
    ("M5 去掉 -9\\b 的词边界（-90/-93 会被误改）",
     '        """(sourceType\\s*=\\s*\\(?\\s*sourceType\\s*&\\s*\\(?\\s*)-9\\b""",',
     '        """(sourceType\\s*=\\s*\\(?\\s*sourceType\\s*&\\s*\\(?\\s*)-9""",'),
    ("M6 不识别引号（'where x' 里的词被当成 WHERE 子句）",
     "                c == '\\'' || c == '\"' || c == '`' -> quote = c",
     "                c == '\\'' || c == '\"' || c == '`' -> quote = null"),
]

src = io.open(LOGIC, encoding='utf-8').read()
sha = hashlib.sha256(src.encode('utf-8')).hexdigest()

env = dict(os.environ)
env['JAVA_HOME'] = 'G:/Program Files/Microsoft/jdk-21.0.7.6-hotspot'
env['ANDROID_HOME'] = 'C:/Users/lx/AppData/Local/Android/Sdk'
env['PATH'] = 'C:/Windows/system32;C:/Windows;C:/Windows/System32/Wbem'

GRADLE = ['gradlew.bat', 'cleanTestDebugUnitTest', 'testDebugUnitTest', '--rerun-tasks',
          '--tests', 'com.lx.wxgroup.feature.SnsSqlRewriteTest']

red = 0
try:
    for name, old, new in MUTS:
        assert src.count(old) == 1, (name, src.count(old))
        io.open(LOGIC, 'w', encoding='utf-8', newline='').write(src.replace(old, new))
        with io.open('_mut_b_out.txt', 'w', encoding='utf-8', errors='replace') as fh:
            subprocess.run(GRADLE, env=env, stdout=fh, stderr=fh, cwd='.', shell=False)
        out = io.open('_mut_b_out.txt', encoding='utf-8', errors='replace').read()
        fails = sorted({l.strip() for l in out.splitlines() if 'FAILED' in l and 'Task' not in l})
        print('=== %s' % name)
        for f in fails:
            print('    RED %s' % f)
        if not fails:
            print('    ⚠️ 0 条变红（变异未被发现）')
            red += 0
        else:
            red += 1
        sys.stdout.flush()
        io.open(LOGIC, 'w', encoding='utf-8', newline='').write(src)
finally:
    io.open(LOGIC, 'w', encoding='utf-8', newline='').write(src)

now = hashlib.sha256(io.open(LOGIC, encoding='utf-8').read().encode('utf-8')).hexdigest()
print('restored sha256 match =', now == sha)
print('mutations caught = %d/%d' % (red, len(MUTS)))
