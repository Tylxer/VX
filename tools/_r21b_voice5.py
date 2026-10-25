"""R21b 取证（五）：稳定取回 extra 快照并解码（修正分页错位）

上一版问题：探针是异步广播，`pull` 拿到的日志里**同一轮可能混入上一轮的结果**，
按 `rows=` 切块会错位 ⇒ 拼接出垃圾。

修正：每段单独 pull，只认「这一段时间戳之后」的行；并用 hex 长度自校验
（hex 总长必须 == 原字符串长度 × 2，否则重试）。
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


def tail_since(ts):
    sh("pull", DBG, LOCAL)
    lines = open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    return [l for l in lines if l[:8] >= ts]


def ask(sql, wait=2.0):
    bc('am broadcast -a com.lx.wxgroup.action.PROBE --es op label --es sub sql '
       '--es sql "%s" -p com.tencent.mm' % sql)
    time.sleep(wait)
    return tail_since(stamp())


def stamp():
    # 用本地时间做下界不可靠（设备与 PC 有时差），统一用「清日志 + 只认最后一块」
    return "00:00:00"


def one_row(sql, tries=3):
    """执行 sql 并返回 wxgroup.db 那一块的第一个数据行（探针日志被截 60 字符）"""
    for _ in range(tries):
        bc("am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear -p com.tencent.mm")
        time.sleep(0.6)
        ask(sql, 2.2)
        lines = open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
        hit = None
        grab = False
        for l in lines:
            if "LABEL sql db=" in l:
                grab = "wxgroup.db rows=1" in l
                continue
            if grab and "LABEL   " in l:
                hit = l.split("LABEL", 1)[1].strip()
                break
        if hit:
            return hit
    return None


def fetch(col, total):
    hexlen = total * 2
    parts = []
    off = 1
    while off <= hexlen:
        sql = ("select substr(hex(%s),%d,40) from rescued "
               "where orig_type=34 order by msg_id desc limit 1" % (col, off))
        row = one_row(sql)
        if not row:
            print("   [warn] offset %d 取不到" % off)
            break
        m = re.match(r"substr\(hex\(%s\),\d+,40\)=(.*)" % re.escape(col), row)
        if not m:
            print("   [warn] 解析失败: %s" % row[:80])
            break
        chunk = m.group(1).strip()
        parts.append(chunk)
        off += 40
    h = "".join(parts)
    print("   hex 长度=%d 期望=%d %s" % (len(h), hexlen, "OK" if len(h) == hexlen else "MISMATCH"))
    try:
        return bytes.fromhex(h).decode("utf-8", "replace")
    except Exception as e:
        return "<hex fail: %s>" % e


def decode(enc, title):
    print("\n== %s ==" % title)
    for entry in enc.split(ES):
        if not entry:
            continue
        p = entry.split(FS)
        if len(p) < 3:
            print("  <bad len=%d raw=%r>" % (len(p), entry[:40]))
            continue
        try:
            k = base64.b64decode(p[0]).decode("utf-8", "replace")
        except Exception:
            k = "?"
        typ = p[1]
        try:
            v = base64.b64decode(p[2]).decode("utf-8", "replace") if p[2] else ""
        except Exception:
            v = "<bin %d>" % len(p[2])
        print("  %-14s [%s] len=%-4d %s" % (k, typ, len(v), v[:130].replace("\n", "\\n")))


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(2)
    row = one_row("select length(orig_extra),length(revoke_extra) from rescued "
                  "where orig_type=34 order by msg_id desc limit 1")
    print("长度行：", row)
    if not row:
        return
    m = re.search(r"length\(orig_extra\)=(\d+) \| length\(revoke_extra\)=(\d+)", row)
    if not m:
        print("长度解析失败")
        return
    ol, rl = int(m.group(1)), int(m.group(2))
    print("orig=%d revoke=%d" % (ol, rl))

    print("\n取 orig_extra …")
    decode(fetch("orig_extra", ol), "orig_extra")
    print("\n取 revoke_extra …")
    decode(fetch("revoke_extra", rl), "revoke_extra")


if __name__ == "__main__":
    main()
