"""R21b 取证（三）：把语音消息的 extra 快照解出来看字段构成

CvCodec 格式：`Base64(key) \\u0002 类型标记 \\u0002 Base64(value)`，字段间 \\u0001。

目的：语音（orig_type=34）被伪装时写了哪些字段、缺了哪些。
语音播放依赖的候选字段：content（是 `wxid:时长:0` 这种索引串，不是路径）、
lvbuffer、reserved、imgPath、msgSvrId、status。
"""
import base64
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_sns.txt"

FIELD_SEP = "\u0002"
ENTRY_SEP = "\u0001"


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def bc(c):
    sh("shell", c)


def b64d(s):
    try:
        return base64.b64decode(s).decode("utf-8", "replace")
    except Exception:
        return "<!b64:" + s[:20] + ">"


def decode(enc):
    out = []
    for entry in enc.split(ENTRY_SEP):
        if not entry:
            continue
        parts = entry.split(FIELD_SEP)
        if len(parts) < 3:
            out.append("  <bad entry len=%d>" % len(parts))
            continue
        key = b64d(parts[0])
        typ = parts[1]
        raw = b64d(parts[2]) if parts[2] else ""
        out.append("  %-12s [%s] %s" % (key, typ, raw[:110].replace("\n", "\\n")))
    return out


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(2)
    bc("am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear -p com.tencent.mm")
    time.sleep(1)

    # 一次只取一条语音的两个快照，避免日志被 5000 行环覆盖
    sql = ("select revoke_extra,orig_extra,orig_content from rescued "
           "where orig_type=34 order by msg_id desc limit 1")
    bc('am broadcast -a com.lx.wxgroup.action.PROBE --es op label --es sub sql '
       '--es sql "%s" -p com.tencent.mm' % sql)
    time.sleep(3)
    sh("pull", DBG, LOCAL)

    txt = open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    # 探针打印是 `LABEL    <row>`，row 是 list 的 toString → [a, b, c]
    row = None
    for l in txt:
        if "LABEL   [" in l or ("LABEL" in l and "revoke_extra" in l):
            row = l
            break
    if not row:
        cand = [l for l in txt if l.startswith("15:") and "LABEL" in l and "|" not in l]
        print("未找到目标行。最近 LABEL 行：")
        for l in cand[-6:]:
            print("  " + l[:300])
        return

    print("原始行：\n  " + row[:400])
    print("\n=== 解析 ===")
    # 形如 LABEL   [<revoke>, <orig>, <content>]
    body = row.split("LABEL", 1)[1].strip()
    body = body.strip()
    if body.startswith("["):
        body = body[1:]
    if body.endswith("]"):
        body = body[:-1]
    # 三个字段用 ", " 分隔，但 Base64 里不会有逗号
    parts = body.split(", ")
    labels = ["revoke_extra", "orig_extra", "orig_content"]
    for i, p in enumerate(parts[:3]):
        print("\n-- %s --" % labels[i])
        p = p.strip()
        if p in ("null", "NULL", ""):
            print("  (NULL)")
        elif i < 2:
            for line in decode(p):
                print(line)
        else:
            print("  " + p[:200])


if __name__ == "__main__":
    main()
