"""R21c 取证：6 条被救下的语音消息**此刻**在微信消息库里长什么样。

## 为什么这一条能直接判 A/B

`rescued` 台账里 `orig_type=34`（语音）共 6 条。问题是「播放失败」到底是
  A. 「恢复默认显示」开着 → 我们把它改写成撤回形态 → 播不了（**预期行为**）
  B. 开关关着 → 它是原文形态，但文案/字段被污染（真 bug）
  C. 库里还是原文，但微信撤回时把 voice2/ 下的 silk 文件删了（真 bug）

**判据是消息库里那一行的 `type`**：
- `type == 34`                                    ⇒ A 已排除（没被改写成撤回形态）
- `type == 268445456`（0x10000000|10000）          ⇒ A 成立，本轮不改代码
- `type == 34` 但 voice2 文件不存在                ⇒ C 成立

## 顺带取回的字段
`msgSvrId` —— 语音文件在 `voice2/` 下的文件名就是它（`msgSvrId` 十进制），
没有它没法判 C。

## 工具坑
- `label --es sub sql` 会**遍历全部库**（16 个），只有 EnMicroMsg.db 有 `message` 表，
  其余报 `no such table` 是正常噪声 ⇒ 只认 `LABEL sql db=.../EnMicroMsg.db rows=N` 之后的行。
- 探针每列截断到 60 字符 ⇒ 只取短列（type/isSend/talker），别取 lvbuffer。
"""
import re
import subprocess
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_rv.txt"

# 台账里 orig_type=34 的 6 条（前 5 条在同一个群，第 6 条是单聊）
VOICE_IDS = [19289041, 19289057, 19289088, 19289117, 19289824, 19290094]


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def am(op, extra=""):
    """整条 am 命令作**一个字符串**传 sh，值用单引号（见 CLAUDE.md 通用坑）"""
    cmd = ("am broadcast -a com.lx.wxgroup.action.PROBE --es op %s %s -p com.tencent.mm"
           % (op, extra))
    return sh("shell", cmd)


def sql(q, wait=2.5):
    am("label", "--es sub sql --es sql '%s'" % q)
    time.sleep(wait)


def pull_lines(tag):
    sh("pull", DBG, LOCAL)
    try:
        return open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        return []


def main():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(2)
    am("logclear")
    time.sleep(1)

    ids = ",".join(str(i) for i in VOICE_IDS)

    print("[1] 这 6 条语音**此刻**在消息库里的形态")
    # 只取短列：type 决定判据，msgSvrId 是 voice2 文件名，content 是 索引串
    sql("select msgId,type,isSend,msgSvrId,content from message "
        "where msgId in (%s)" % ids)

    print("[2] 当前开关状态（kv 表）")
    for k in ["antirevoke.msg", "antirevoke.restore_view", "antirevoke.show_hint"]:
        am("kvget", "--es key '%s'" % k)
        time.sleep(0.8)

    time.sleep(2)
    lines = pull_lines("rv")

    # ---- 解析：只认 EnMicroMsg.db 那一块，绕开 15 条 no such table 噪声
    print("\n" + "=" * 70)
    print("原始输出（筛过噪声）")
    print("=" * 70)
    block = False
    rows = {}
    for line in lines:
        if "LABEL sql db=" in line:
            block = "EnMicroMsg.db" in line
            if block:
                print("  " + line.strip()[:160])
            continue
        if "no such table" in line:
            continue
        if block and "LABEL" in line:
            print("  " + line.strip()[:200])
            # 探针行形如 `LABEL   msgId=19289041 type=34 ...` 或列名=值列表
            kv = dict(re.findall(r"(\w+)=([^=\s]+)", line))
            if "msgId" in kv:
                rows[kv["msgId"]] = kv

    print("\n" + "=" * 70)
    print("判据结论")
    print("=" * 70)
    REVOKE_TYPE = 268445456  # 0x10000000 | 10000
    for mid in VOICE_IDS:
        r = rows.get(str(mid))
        if not r:
            print("  msgId=%-10s 未取到（可能日志被截断，重跑本脚本）" % mid)
            continue
        t = r.get("type", "?")
        verdict = ("A 成立：被改写成撤回形态 ⇒ 本轮不改代码"
                   if t == str(REVOKE_TYPE) else
                   "A 排除：仍是语音形态 type=34 ⇒ 往 C（silk 文件丢失）方向查"
                   if t == "34" else
                   "⚠️ 未知形态 type=%s" % t)
        print("  msgId=%-10s type=%-10s isSend=%-2s msgSvrId=%-22s"
              % (mid, t, r.get("isSend", "?"), r.get("msgSvrId", "?")))
        print("      %s" % verdict)
        print("      content=%s" % r.get("content", "?")[:60])


if __name__ == "__main__":
    main()
