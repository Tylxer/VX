"""R21c 全链路判据：撤回一条语音后，三个动作是否都跑对了。

## 为什么需要它（别再手打 adb）

上一轮踩过的坑全部固化在这里：
- 探针**必须带 `--es op <功能>`**，缺了整条广播进不到分支（卡过半小时）
- 整条 `am` 命令作**一个字符串**传 `sh`（否则 SQL 里的括号会被 sh 解释成语法错）
- 广播在**后台不投递**，必须先 `am start` 把微信拉到前台

## 三条判据（注意：1 和 3 是**互斥的两条路径**，不是并列要求）

| # | 日志特征 | 含义 |
|---|---|---|
| 1 | `VFG blocked file delete img=<hex>` | `File.delete` hook **真命中了**，删除被拦下 ⇒ **文件还在，restore 不该动作** |
| 2 | `VB backup ok img=<hex> bytes=N` | 备份**在删除之前**拿到了文件（两种情况都需要） |
| 3 | `VB restore ok img=<hex> bytes=N` | 判据 1 **没**命中（走 native unlink 等）时，靠备份补回原位 |

⛔ **判据 1 命中时判据 3 必然为空，那是正确行为不是失败** ——
`restore` 的闸门是 `shouldRestore(备份在 && 原位不在)`，文件没被删 ⇒ 正确 no-op。
所以总判是 **`1 或 3 至少一个成立`**（= 文件最终在原位），而不是两个都要。

真正的最终判据只能人工验：**去微信里点那条被撤回的语音能不能播放**。

## 用法

    python tools/_r21c_voice_chain.py            # 只看当前日志（撤回动作由你手动做）
    python tools/_r21c_voice_chain.py --clear    # 先清日志，再等你撤回
"""
import re
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_vchain.txt"

PKG = "com.tencent.mm"
ACTION = "com.lx.wxgroup.action.PROBE"


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def clear_log():
    """广播在后台不投递 ⇒ 先 am start 拉前台"""
    sh("shell", "am start -n %s/.ui.LauncherUI" % PKG)
    time.sleep(2)
    # ⚠️ 整条 am 作一个字符串传（CLAUDE.md：值用单引号，防 sh 解释）
    sh("shell", "am broadcast -a %s --es op logclear -p %s" % (ACTION, PKG))
    time.sleep(1)


def pull():
    sh("pull", DBG, LOCAL)
    try:
        return open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        return []


def main():
    if "--clear" in sys.argv:
        print("清日志中（撤回之前）…")
        clear_log()
        print("✅ 日志已清。现在去微信里：先点播放一次 → 让好友再发一条语音 → 点播放 → 让对方撤回")
        print("   撤回完再跑本脚本（不带 --clear）。")
        return

    lines = pull()
    if not lines:
        print("⛔ 取不到日志（%s）。检查：设备连着吗？debug 文件写了吗？" % DBG)
        sys.exit(1)

    # ---------------------------------------------------------------- 抽事件
    def find_all(pat):
        out = []
        for ln in lines:
            m = re.search(pat, ln)
            if m:
                out.append((ln, m))
        return out

    installed = find_all(r"VFG installed hooks=(\S+)")
    blocked = find_all(r"VFG blocked file delete img=([0-9a-f]{26})")
    bak_ok = find_all(r"VB backup ok img=([0-9a-f]{26}) bytes=(\d+)")
    bak_skip = find_all(r"VB skip: source missing img=([0-9a-f]{26})")
    bak_fail = find_all(r"VB backup failed img=(\S+)")
    res_ok = find_all(r"VB restore ok img=([0-9a-f]{26}) bytes=(\d+)")
    res_fail = find_all(r"VB restore failed img=(\S+)")
    rr_voice = find_all(r"RRV record msgId=(\d+) talker=\S+ ok=(\w+) origType=(\d+)")

    print("=" * 72)
    print("R21c 语音防删全链路判据")
    print("=" * 72)

    # ---------------------------------------------------------------- 装没装上
    print("\n[0] hook 装上没")
    if installed:
        for ln, m in installed:
            print("    ✅ %s" % ln.strip()[:120])
    elif blocked:
        # ⚠️ 清日志之后 `VFG installed` 是**不在了**的（安装日志在清空前）。
        #    但只要 `VFG blocked` 出现过，就证明 hook 一定装上了 —— 别误报。
        print("    ✅ 安装日志已被清空，但下面判据 1 命中了 ⇒ hook 确实在工作")
    else:
        print("    ⛔ 没有 `VFG installed` —— hook 没装（或日志被清后还没冷启动微信）")

    # ---------------------------------------------------------------- 语音撤回事件
    print("\n[1] 被拦下的语音撤回（RRV record，type=34 才算语音）")
    voice_ids = set()
    for ln, m in rr_voice:
        if m.group(2) == "true" and m.group(3) == "34":
            voice_ids.add(m.group(1))
            print("    ✅ %s" % ln.strip()[:130])
    if not voice_ids:
        print("    ⚠️ 日志里没有成功的语音撤回记录（可能你没让好友发语音，或撤回没被拦到）")

    # ---------------------------------------------------------------- 三条判据
    print("\n[判据 1] File.delete hook 命中（VFG blocked file delete）")
    if blocked:
        for ln, m in blocked:
            print("    ✅ img=%s" % m.group(1))
    else:
        print("    ⛔ 没有命中记录")
        print("       ⇒ 要么微信走 native unlink（VoiceFileGuard 无效，只能靠 backup+restore）")
        print("       ⇒ 要么 hook 没装（看 [0]）")

    print("\n[判据 2] 备份成功（VB backup ok）")
    if bak_ok:
        for ln, m in bak_ok:
            print("    ✅ img=%s bytes=%s" % (m.group(1), m.group(2)))
    else:
        print("    ⛔ 没有成功备份")
    if bak_skip:
        for ln, m in bak_skip:
            print("    ⚠️ img=%s 撤回时文件本来就不在（没播放过？已过期？）" % m.group(1))
    if bak_fail:
        for ln, m in bak_fail:
            print("    ⛔ 备份失败：%s" % ln.strip()[:140])

    print("\n[判据 3] 延时还原成功（VB restore ok）")
    print("         ⚠️ 只在判据 1 **没**命中时才需要它；1 命中 ⇒ 这里空是正确行为")
    if res_ok:
        for ln, m in res_ok:
            print("    ✅ img=%s bytes=%s" % (m.group(1), m.group(2)))
    else:
        if blocked:
            print("    ○ 空（正常）：删除已被 hook 拦住，文件还在原位，restore 正确 no-op")
        else:
            print("    ⛔ 空且判据 1 也没命中 ⇒ 删除走了 hook 看不到的路径，且没人补")
            print("       ⇒ 需要检查 1s/5s/15s 三次是否都没赶上删除时刻")
    if res_fail:
        for ln, m in res_fail:
            print("    ⛔ 还原失败：%s" % ln.strip()[:140])

    # ---------------------------------------------------------------- 总判
    print("\n" + "=" * 72)
    # ⛔ 判据 1 与判据 3 互斥：1 命中 = 文件没被删（restore 该 no-op）；1 未命中 = 靠 3 补回。
    #    所以总判是「1 或 3 至少一个成立」，不是两个都要。
    closed = bool(blocked) or bool(res_ok)
    if closed:
        if blocked:
            print("✅ 链路闭环（路径 A）：File.delete 被拦住，文件从未离开原位。")
        else:
            print("✅ 链路闭环（路径 B）：删除没拦住，但备份把它补回了原位。")
        print("   最后一步只能你验：**去微信里点那条被撤回的语音，能不能播放**。")
    else:
        print("⛔ 链路没闭环：删除既没拦住、也没补回，文件大概率已丢。")
        print("   先看上面标 ⛔ 的项，别急着点播放。")
    print("=" * 72)


if __name__ == "__main__":
    main()
