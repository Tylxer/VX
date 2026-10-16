"""只读检查 LSPosed 里本模块的注册状态，不做任何写操作。"""
import sqlite3, os, sys

# db 路径由 dev.sh 以 argv[1] 传入（各 worktree 拉到自己树里的 _cache）；
# 缺省时退回主工作区路径（兼容脱离 dev.sh 手工直跑）。
p = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_cache\_m.db"
pkg = os.environ["PKGNAME"]
apkp = os.environ["APKPATH"]
host = os.environ["HOSTPKG"]

# 只读打开，避免产生 -wal 文件
try:
    con = sqlite3.connect("file:%s?mode=ro" % p.replace("\\", "/"), uri=True)
except Exception:
    con = sqlite3.connect(p)
cur = con.cursor()

ok = True
cur.execute("SELECT apk_path FROM modules WHERE module_pkg_name=?", (pkg,))
r = cur.fetchone()
if r is None:
    print("[FAIL] modules 表里没有 %s —— LSPosed 还没扫描到它，去管理器里看一眼" % pkg)
    ok = False
else:
    same = (r[0] == apkp)
    print("[%s] apk_path %s" % ("OK" if same else "STALE", r[0]))
    if not same:
        print("      实际应为 %s" % apkp)
    ok = ok and same

cur.execute("SELECT enabled FROM modules_state WHERE module_pkg_name=? AND user_id=0", (pkg,))
r = cur.fetchone()
en = (r[0] == 1) if r else False
print("[%s] enabled = %s" % ("OK" if en else "FAIL", r[0] if r else "无记录"))
ok = ok and en

cur.execute("SELECT 1 FROM scope WHERE module_pkg_name=? AND app_pkg_name=? AND user_id=0", (pkg, host))
has = cur.fetchone() is not None
print("[%s] scope 含 %s" % ("OK" if has else "FAIL", host))
ok = ok and has

print("==> %s" % ("全部就绪" if ok else "需要手工处理：打开手机上的 LSPosed 管理器勾选本模块"))
con.close()
sys.exit(0 if ok else 1)
