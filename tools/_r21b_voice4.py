"""R21b 取证（四）：完整取出一条语音消息的 revoke_extra / orig_extra 并解码

探针日志把每列截断到 60 字符，所以用 `substr(hex(x), off, 50)` 分页取回。
hex 后纯 ASCII，不会有换行/分隔符干扰。
"""
import base64
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_sns.txt"

FS = "\u0002"
ES = "\u0001"


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def bc(c):
    sh("shell", c)


def probe_sql(sql):
    bc('am broadcast -a com.lx.wxgroup.action.PROBE --es op label --es sub sql '
       '--es sql "%s" -p com.tencent.mm' % sql)
    time.sleep(1.6)


def rows_after_last_pull():
    """返回 {(db, rows)} 之后的 LABEL 行文本（只取 wxgroup.db 那批）"""
    sh("pull", DBG, LOCAL)
    lines = open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    out = []
    grab = False
    for l in lines:
        if "LABEL sql db=" in l:
            grab = "wxgroup.db rows=" in l
            continue
        if grab and "LABEL   " in l:
            out.append(l.split("LABEL", 1)[1].strip())
        elif grab and "LABEL raw err" in l:
            grab = False
    return out


def fetch_hex(col, total_chars):
    """分页取 hex()，total_chars 是原字符串长度（hex 后 ×2）"""
    hexlen = total_chars * 2
    got = []
    off = 1
    while off <= hexlen:
        probe_sql("select substr(hex(%s),%d,50) from rescued "
                  "where orig_type=34 order by msg_id desc limit 1" % (col, off))
        rs = rows_after_last_pull()
        chunk = ""
        for r in rs:
            m = re.match(r"substr\(hex\(%s\),\d+,50\)=(.*)" % re.escape(col), r)
            if m:
                chunk = m.group(1).strip()
                break
        if not chunk:
            break
        got.append(chunk)
        off += 50
    h = "".join(got)
    try:
        return bytes.fromhex(h).decode("utf-8", "replace")
    except Exception:
        return "<hex decode fail len=%d>" % len(h)


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
        typ = p[1]
        try:
            v = base64.b64decode(p[2]).decode("utf-8", "replace") if p[2] else ""
        except Exception:
            v = "<bin>"
        print("  %-14s [%s] len=%-4d %s" % (k, typ, len(v), v[:120].replace("\n", "\\n")))


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(2)
    bc("am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear -p com.tencent.mm")
    time.sleep(1)

    # 先取长度
    probe_sql("select length(revoke_extra),length(orig_extra) from rescued "
              "where orig_type=34 order by msg_id desc limit 1")
    rs = rows_after_last_pull()
    rl = ol = 0
    for r in rs:
        m = re.search(r"length\(revoke_extra\)=(\d+) \| length\(orig_extra\)=(\d+)", r)
        if m:
            rl, ol = int(m.group(1)), int(m.group(2))
            break
    print("长度 revoke=%d orig=%d" % (rl, ol))
    if not rl:
        print("取长度失败，最近行：", rs[:3])
        return

    rev = fetch_hex("revoke_extra", rl)
    decode(rev, "revoke_extra")
    orig = fetch_hex("orig_extra", ol)
    decode(orig, "orig_extra")


if __name__ == "__main__":
    main()
