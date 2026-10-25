"""R21b 取证（六）：一次性取回 extra 快照（不循环清日志）

思路：探针日志截断的是**日志行**（60 字符），不是 SQL 结果。
所以让 SQL 自己做「拆段 + 编号」，把一行拆成多行输出 —— 每段 <60 字符即可整体读回。

`select substr(hex(x),1,40) union all select substr(hex(x),41,40) ...`
用递推表生成足够多的段号即可，一条 SQL 输出全部。
"""
import base64
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_sns.txt"
FS, ES = "\u0002", "\u0001"


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def bc(c):
    sh("shell", c)


def run_sql(sql, wait=2.5):
    bc('am broadcast -a com.lx.wxgroup.action.PROBE --es op label --es sub sql '
       '--es sql "%s" -p com.tencent.mm' % sql)
    time.sleep(wait)
    sh("pull", DBG, LOCAL)
    lines = open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    # 只取最后一块 wxgroup.db 的结果
    blocks = []
    cur = None
    for l in lines:
        if "LABEL sql db=" in l:
            cur = [] if "wxgroup.db" in l else None
            if cur is not None:
                blocks.append(cur)
            continue
        if cur is not None and "LABEL   " in l:
            cur.append(l.split("LABEL", 1)[1].strip())
    if not blocks:
        return []
    return blocks[-1]


def split_expr(col, total, step=36):
    """生成 union all 的拆段 SQL 主体"""
    parts = []
    off = 1
    while off <= total * 2:
        parts.append("select %d as o, substr(hex(%s),%d,%d) as c" % (off, col, off, step))
        off += step
    return " union all ".join(parts)


def fetch(col, total):
    sql = ("select '" + col + "||'||o||'||'||c from ("
           + split_expr(col, total) + ") t order by o")
    rows = run_sql(sql, 3.5)
    segs = {}
    for r in rows:
        # 行形如 `colname||1||ABCD...`
        m = re.match(re.escape(col) + r"\|\|(\d+)\|\|(.*)", r)
        if m:
            segs[int(m.group(1))] = m.group(2).strip()
    h = "".join(segs[k] for k in sorted(segs))
    print("  %s: 段=%d hex=%d 期望=%d %s"
          % (col, len(segs), len(h), total * 2, "OK" if len(h) == total * 2 else "MISMATCH"))
    try:
        return bytes.fromhex(h).decode("utf-8", "replace")
    except Exception as e:
        return "<fail %s>" % e


def decode(enc, title):
    print("\n== %s ==" % title)
    for entry in enc.split(ES):
        if not entry:
            continue
        p = entry.split(FS)
        if len(p) < 3:
            print("  <bad len=%d>" % len(p))
            continue
        try:
            k = base64.b64decode(p[0]).decode("utf-8", "replace")
        except Exception:
            k = "?"
        try:
            v = base64.b64decode(p[2]).decode("utf-8", "replace") if p[2] else ""
        except Exception:
            v = "<bin>"
        print("  %-14s [%s] len=%-4d %s" % (k, p[1], len(v), v[:130].replace("\n", "\\n")))


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(2)
    bc("am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear -p com.tencent.mm")
    time.sleep(1)

    rows = run_sql("select length(orig_extra),length(revoke_extra) from rescued "
                   "where orig_type=34 order by msg_id desc limit 1")
    print("长度行：", rows)
    if not rows:
        return
    m = re.search(r"length\(orig_extra\)=(\d+) \| length\(revoke_extra\)=(\d+)", rows[0])
    if not m:
        print("解析失败")
        return
    ol, rl = int(m.group(1)), int(m.group(2))
    print("orig=%d revoke=%d" % (ol, rl))

    decode(fetch("orig_extra", ol), "orig_extra")
    decode(fetch("revoke_extra", rl), "revoke_extra")


if __name__ == "__main__":
    main()
