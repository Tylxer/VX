"""R21c 判据：某条语音的音频文件此刻是否还在磁盘上。

## 为什么需要「对照组」

直接对一个 imgPath 做 `find` 得到 `hits=0` **不能**下结论 —— 可能是：
  a. 文件真的没了
  b. `--es table` 路径写错，扫了个空目录
  c. 探针本身有 bug

所以本脚本**必须在同一遍扫描里**带上一个「已知一定存在」的对照组：
两次扫描的 `visited` / `dirs` 相同 ⇒ 覆盖范围一致 ⇒ `hits=0` 才是真结论。

## 用法

    python tools/_r21c_voice_file_exists.py <imgPath> [对照imgPath]

不给对照组时用 `DEFAULT_CONTROL`（真机上验证过存在的样本）。
⚠️ 换账号 / 清了语音缓存后对照组可能失效 —— 那时脚本会明确报
「对照组也找不到 ⇒ 本次结论无效」，不会给出误导性的结论。

## 输出判据

    find '<实验>' ... hits=0 visited=N dirs=M
    find '<对照>' ... hits=1 visited=N dirs=M
    ⇒ 文件已不存在（两条 visited/dirs 必须一致）

## 已知路径规则（`VoiceBackupLogic` 有单测）

    voice2/<md5(imgPath)[0:2]>/<md5(imgPath)[2:4]>/msg_<imgPath>.amr
"""
import re
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vfe.txt"

# 真机验证过的对照样本（2026-10-25，账号 89476c7f…）
DEFAULT_CONTROL = "50095010242638d35faef1f106"

ACCOUNT = "89476c7fc82a9f977bc8a901d68b7d37"


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def find(img, wait=9.0):
    """整条 am 作**一个字符串**传 sh（见 CLAUDE.md 通用坑）"""
    cmd = ("am broadcast -a com.lx.wxgroup.action.PROBE "
           "--es op label --es sub find "
           "--es table MicroMsg/%s/voice2 "
           "--es sql %s -p com.tencent.mm" % (ACCOUNT, img))
    sh("shell", cmd)
    time.sleep(wait)


def pull():
    sh("pull", DBG, LOCAL)
    try:
        return open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        return []


def parse(lines, img):
    """取最后一次该 imgPath 的 find 结果行 + 命中行"""
    head = None
    hits = []
    for ln in lines:
        if "LABEL find '%s'" % img in ln:
            head = ln
            hits = []
        elif head is not None and "LABEL   HIT" in ln:
            hits.append(ln.strip())
    return head, hits


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    target = sys.argv[1]
    control = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_CONTROL

    sh("shell", "am start -n com.tencent.mm/.ui.LauncherUI")
    time.sleep(2)
    sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear "
                "-p com.tencent.mm")
    time.sleep(1)

    print("[1/2] 对照组 %s （应 hits=1）" % control)
    find(control)
    print("[2/2] 实验组 %s" % target)
    find(target)

    lines = pull()
    c_head, c_hits = parse(lines, control)
    t_head, t_hits = parse(lines, target)

    print("\n" + "=" * 72)
    for tag, head, hits in (("对照", c_head, c_hits), ("实验", t_head, t_hits)):
        print("%s组: %s" % (tag, (head or "**未取到结果行**（探针没跑？）").strip()[:150]))
        for h in hits:
            print("        " + h[:160])

    def nums(head):
        if not head:
            return None
        m = re.search(r"visited=(\d+) dirs=(\d+)", head)
        return (int(m.group(1)), int(m.group(2))) if m else None

    nc, nt = nums(c_head), nums(t_head)
    print("\n" + "=" * 72)
    print("判据")
    print("=" * 72)
    if nc is None or nt is None:
        print("  ⛔ 结果行缺失 ⇒ 本次结论**无效**（先查探针 / 前台 / 窗口）")
        return
    if nc != nt:
        print("  ⛔ 两次扫描范围不同 %s vs %s ⇒ 本次结论**无效**，重跑" % (nc, nt))
        return
    if not c_hits:
        print("  ⛔ 对照组也 hits=0 ⇒ 对照组已失效（换样本），本次结论**无效**")
        return
    print("  ✅ 扫描范围一致 visited=%d dirs=%d，对照组命中 %d 个" % (nc[0], nc[1], len(c_hits)))
    if t_hits:
        print("  ⇒ 实验组**文件仍存在**（%d 个）：" % len(t_hits))
        for h in t_hits:
            print("       " + h[:160])
    else:
        print("  ⇒ 实验组**文件已不存在** —— 微信把磁盘音频删掉了")


if __name__ == "__main__":
    main()
