"""只读检查 LSPosed 里本模块的注册状态，不做任何写操作。"""
import sqlite3, os, sys

# db 路径由 dev.sh 以 argv[1] 传入（各 worktree 拉到自己树里的 _cache）；
# 缺省时退回主工作区路径（兼容脱离 dev.sh 手工直跑）。
p = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_cache\_m.db"
pkg = os.environ["PKGNAME"]
apkp = os.environ["APKPATH"]
host = os.environ["HOSTPKG"]

# ⚠️ 必须用**普通可写**模式打开，不能用 mode=ro：
#    LSPosed 的 db 是 WAL 模式，新数据全在 -wal 里，只读模式无法回放 WAL ⇒
#    读出来的是旧内容（症状：apk_path 恒 STALE，实际配置是对的）。
#    dev.sh sync 已把 db/-wal/-shm 三件套一起 pull 下来，这里打开即自动回放。
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
