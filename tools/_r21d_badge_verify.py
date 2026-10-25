"""R21d 角标真机验收（方案 1：在原文气泡上画「已阻止撤回 · <摘要>」）。

⛔ **本脚本不改任何东西**，只做三件事：拉日志 / 发广播 / 打印判定。
   装包由主会话统一做（`sh wxgroup/dev.sh install`），本脚本**不装包**。

## 为什么需要这个脚本
角标是一条 **onBindViewHolder → msgId → 反查 rescued 表 → 在 itemView 上 addView** 的链。
链上任一环断掉，用户看到的都是同一个现象：**就是没有角标**。分不清是哪一环。
所以 `HintBadge.stat()` 留了 6 个计数器，必须**按顺序**看（这是 §7 排查法的落地）：

| 计数器 | 含义 | 为 0 说明 |
|---|---|---|
| `binds` | 钩子被调用次数 | **钩子没挂上**（q3 类名/方法名变了，或 install 没跑到） |
| `chat` | 其中前缀匹配的 | 前缀限定太窄/太宽（`com.tencent.mm.pluginsdk.ui.tools.`） |
| `resolved` | getItem→msgId 成功的 | **pos 坐标错位** 或 item 不是 h9（广告行/日期分隔条） |
| `hits` | msgId 命中 rescued 集的 | 数据源问题（rescued 表空 / msgId 对不上） |
| `added` | 真的 addView 上去的 | 宿主 View 找错（itemViewOf 反射失败） |
| `masked`/`verifyFail` | 被判据抑制的 | 伪装态 msgType / 文本对账不过 |

**判据 `resolved ≈ chat`**：两者差得多 ⇒ 大量 item 解析不出 msgId，
最可能是 **pos 坐标系差 1 位**（RecyclerView 坐标 vs 数据下标）。

## 前置（缺一个结果就是假的）
1. 已装新 APK，且 `restart` 两次（判据：日志出现 `HintBadge installed`）
2. **`antirevoke.msg` 必须为 true** —— 当前是 `false`（止血时关的），
   不开的话 rescued 表不会新增，`hits` 恒 0，会被误读成「钩子坏了」
3. 微信在前台，且**打开了那个有被撤回消息的会话**（角标只在绑定那一刻画）
4. 测前 `op=logclear` 清一次（日志 5000 行环形覆盖，不清理会读到旧行）

## 用法
```
python tools/_r21d_badge_verify.py stat      # 只看计数器（最快，先跑这个）
python tools/_r21d_badge_verify.py dump     # 逐条列屏幕上的 msgId + 命中 + 落点
python tools/_r21d_badge_verify.py fake <u>  # 人工给某条 msgId 造角标（验样式，不依赖库）
python tools/_r21d_badge_verify.py unfake
python tools/_r21d_badge_verify.py all       # stat + dump
```
`<u>` 取 `dump` 输出里的 `u=` 值（无符号；负数 msgId 用无符号形式传，避开 `--el` 溢出）。
"""
import re
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\_r21d_badge.txt"


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def lines():
    sh("pull", DBG, LOCAL)
    try:
        return open(LOCAL, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        return []


def bc(extra):
    """发探针广播。

    ⚠️ 整条 am 命令作为一个字符串传给 sh，值用单引号 —— 否则 adb shell 会在
       宿主侧先拆一次，带空格的 sql / 带引号的值会被吃掉。
    ⚠️ 微信在后台不投递广播，本脚本假设调用前微信已在前台。
    """
    cmd = ("am broadcast -a com.lx.wxgroup.action.PROBE --es op hintbadge "
           "%s -p com.tencent.mm" % extra)
    return sh("shell", cmd)


def fire(extra, tag, wait=3.0, tries=2):
    """发一条广播并把新增的 HINTBADGE 行打印出来。

    ⚠️ **一命令一广播、不重试内容**（`_r21d_verify_clean.py` 踩过：带重试的
       query 会发好几遍广播，几组结果混在一起分不清哪行是哪次的）。
       这里的 tries 只用于「日志还没刷出来」的情况，且会去重。
    """
    seen = set()
    for _ in range(tries):
        n0 = len(lines())
        bc(extra)
        time.sleep(wait)
        delta = [l for l in lines()[n0:] if tag in l]
        fresh = [l for l in delta if l not in seen]
        if fresh:
            for l in fresh:
                seen.add(l)
                print("  | " + l.rstrip()[:300])
            return fresh
    print("  (无 %s 输出 —— 先确认微信在前台、已装新包、日志没被旧行覆盖)" % tag)
    return []


STAT_RE = re.compile(
    r"enabled=(\w+).*?msg=(\w+).*?hint=(\w+).*?rescued=(\d+).*?bound=(\d+).*?"
    r"added=(\d+).*?binds=(\d+).*?chat=(\d+).*?resolved=(\d+).*?hits=(\d+).*?"
    r"missed=(\d+).*?verifyFail=(\d+).*?masked=(\d+).*?lastMiss=(\S+)"
)


def do_stat():
    print("=" * 78)
    print("[stat] 计数器")
    print("=" * 78)
    got = fire("--es sub stat", "HINTBADGE stat")
    if not got:
        return None
    m = STAT_RE.search(got[-1])
    if not m:
        print("  ⚠️ stat 行格式没匹配上，原始行见上")
        return None
    k = ("enabled msg hint rescued bound added binds chat resolved hits "
         "missed verifyFail masked lastMiss").split()
    d = dict(zip(k, m.groups()))

    print("\n  %-11s %-9s %s" % ("项", "值", "判定"))
    print("  " + "-" * 62)

    def row(key, note, ok):
        print("  %-11s %-9s %s %s" % (key, d[key], "✅" if ok else "⛔", note))
        return ok

    allok = []
    allok.append(row("enabled", "总开关（Config.showHint）", d["enabled"] == "true"))
    allok.append(row("msg", "防撤回总开关（必须 true，否则 rescued 不增长）", d["msg"] == "true"))
    allok.append(row("rescued", "已救消息缓存条数", int(d["rescued"]) > 0))
    allok.append(row("binds", "钩子被调用（0 = 钩子没挂上）", int(d["binds"]) > 0))
    if int(d["binds"]) > 0:
        allok.append(row("chat", "前缀匹配（0 = 限定太窄）", int(d["chat"]) > 0))
    if int(d["chat"]) > 0:
        allok.append(row("resolved", "getItem→msgId 成功数", int(d["resolved"]) > 0))
        # 核心判据：resolved 与 chat 差得多 ⇒ pos 坐标系错位
        ratio = int(d["resolved"]) / max(int(d["chat"]), 1)
        print("  %-11s %-9s %s  resolved/chat=%.2f %s"
              % ("（比值）", "", "✅" if ratio > 0.5 else "⛔", ratio,
                 "" if ratio > 0.5 else "← 差得多说明 pos 错位或 item 不是 h9"))
    if "hits" in d:
        allok.append(row("hits", "msgId 命中 rescued（0 = 数据源/msgId 对不上）",
                         int(d["hits"]) > 0 or int(d["added"]) > 0))
    allok.append(row("added", "真的 addView 上去（0 = 宿主 View 找错）", int(d["added"]) > 0))
    print("  " + "-" * 62)
    print("  masked=%s verifyFail=%s missed=%s lastMiss=%s"
          % (d["masked"], d["verifyFail"], d["missed"], d["lastMiss"]))
    print("\n  ⇒ 总判定：%s" % ("✅ PASS" if all(allok) else "⛔ 未通过（看上面 ⛔ 行）"))
    return d


def do_dump():
    print("=" * 78)
    print("[dump] 屏幕上的 item（msgId / 命中 / 落点）")
    print("=" * 78)
    fire("--es sub dump", "HINTBADGE dump")
    print("\n  看 dump 要用三条对账（这是唯一能证明「pos 没错位」的方法）：")
    print("  ① 同一行 `txt=` 里的文字，应该就是屏幕上那条消息的正文 —— 对不上就是错位")
    print("  ② `hit=true` 的条数，应该等于屏幕上「被撤回过的消息」条数（多了/少了都错）")
    print("  ③ `host=` 应该是气泡那层的 ViewGroup（LinearLayout/FrameLayout），")
    print("     如果出现 `RecyclerView` ⇒ itemViewOf 反射选错了层")


def do_fake(raw):
    print("=" * 78)
    print("[fake] 人工给 msgId=%s 造角标（验样式，不依赖 rescued 表）" % raw)
    print("=" * 78)
    fire("--es sub fake --es id '%s'" % raw, "HINTBADGE fake")
    print("\n  ⇒ 屏幕上有角标出现 = 注入链路通（itemViewOf + ensure + addView 都对）")
    print("     仍然没有 = 问题在 View 注入，与 pos/msgId 无关，先修注入")
    print("  ⇒ 验完记得跑 `unfake` 摘掉")


def do_unfake():
    print("[unfake]")
    fire("--es sub unfake", "HINTBADGE unfake")


def main():
    args = sys.argv[1:]
    mode = args[0] if args else "stat"
    if mode == "stat":
        do_stat()
    elif mode == "dump":
        do_dump()
    elif mode == "fake":
        if len(args) < 2:
            print("用法: python tools/_r21d_badge_verify.py fake <msgId无符号>")
            return
        do_fake(args[1])
    elif mode == "unfake":
        do_unfake()
    elif mode == "all":
        d = do_stat()
        if d:
            do_dump()
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
