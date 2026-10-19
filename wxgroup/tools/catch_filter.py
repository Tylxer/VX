#!/usr/bin/env python3
"""从 catch 日志里剔除常规噪音，突出**设置类**写入"""

import re, os, collections

PATH = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "_cache", "dbg.txt"))

# 高频常规表（消息流/头像/联系人同步），它们会打满 MAX_LINES 掩盖真正的线索
NOISE_TABLES = {
    "message", "rconversation", "img_flag", "rcontact", "getcontactinfov2",
    "ImgInfo2", "ChatroomFinderClubInfo", "NewUserBehaviourCache", "bizinfo",
    "ContactCmdBuf", "C2CPreDownloadDBInfo", "ActiveInfo", "DupCheckInfo",
    "snsExtInfo3", "WechatSessionHistory", "FriendUser", "GroupSolitatire",
}

# 语句层只保留"看起来像设置/开关"的
SETTING_KW = ["mute", "silent", "notify", "switch", "flag", "setting", "config",
              "state", "enable", "pref", "disturb", "quiet", "room"]

LOG = re.compile(
    r"^(?P<ts>\d\d:\d\d:\d\d\.\d+) SNS SNS (?P<cls>[\w.]+)\.(?P<m>\w+) "
    r"\[(?P<tag>[^\]]+)\] thread=(?P<th>[^ ]+) :: (?P<rest>.*)$"
)


def main():
    rows = []
    with open(PATH, errors="replace") as f:
        for line in f:
            line = line.rstrip()
            if " SNS SNS " not in line or " at " in line:
                continue
            mm = LOG.match(line)
            if mm:
                rows.append(mm.groupdict())

    print(f"总记录 {len(rows)} 条\n")

    # 1. 表名统计（含语句层）
    tab = collections.Counter()
    for r in rows:
        rest = r["rest"]
        m = re.match(r"db=[^|]*\| (.+)$", rest)
        body = m.group(1) if m else rest
        t = body.split("|")[0].strip()[:60]
        tab[r["m"] + " :: " + t] += 1

    print("=== 剔除常规噪音后的写入 ===")
    shown = 0
    for k, n in tab.most_common():
        low = k.lower()
        if any(nt.lower() in low for nt in NOISE_TABLES):
            continue
        print(f"  {n:4d}  {k}")
        shown += 1
        if shown > 40:
            break
    if shown == 0:
        print("  (无)")

    print("\n=== 语句层全部 SQL（去重）===")
    seen = set()
    for r in rows:
        if "[STMT]" not in r["tag"] and "STMT" not in r["tag"] and "[STMT]" not in r["m"]:
            # tag 形如 [STMT] 或 [TBL]
            pass
        if "STMT" not in r["tag"]:
            continue
        rest = r["rest"]
        if rest in seen:
            continue
        seen.add(rest)
        print(f"  {r['m']}: {rest[:170]}")

    print("\n=== 含设置关键词的任意写入 ===")
    hit = 0
    for r in rows:
        low = r["rest"].lower()
        if any(k in low for k in SETTING_KW):
            if any(nt.lower() in low for nt in NOISE_TABLES):
                continue
            print(f"  {r['ts']} {r['m']} :: {r['rest'][:200]}")
            hit += 1
            if hit > 30:
                break
    if hit == 0:
        print("  (无)")


if __name__ == "__main__":
    main()