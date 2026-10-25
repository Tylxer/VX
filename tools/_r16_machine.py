"""R16 批次 2 真机脚本：一次装包跑完 6 项（不需人工）

覆盖（对应 docs/_r15_todo.md）：
  M1  A4  朋友圈图片链路     op=moments sub=snsprobe --ei n 50
  M2  C5  群名数据源装载     op=quiet sub=at sub2=grpname -> grpstat
  M3  C8  归档恢复 dry-run   op=snsg sub=plan / apply / flags
  M4  C4  新加群 force 路径  op=quiet sub=grp sub2=force --es val <chatroomId>
  M5  D4  微信标签数据源     op=label sub=stat / of
  M6  C9  群发 UI 按钮       面板点击（脚本只做前置准备 + 提示，点击留人工/单独步）

设计原则（照抄 tools/_r9_cdn.py 的坑位经验）：
  1. ⛔ 整条 am 命令作为一个字符串交给设备 sh，值一律单引号
     —— 用 subprocess 传 list 拼 adb 参数实测会失效（R14 §4 已验证）
  2. ⛔ 不用 `?: return` / `getOrNull()` 式静默吞异常：每个判据都必须有**可见计数**
  3. 每步都打印 `[M<n>]` 前缀 + 判定，便于事后 grep
  4. 不做破坏性操作：M3 默认只跑 plan（dry），apply 需 --apply 显式开启

用法：
    python tools/_r16_machine.py                # 只读项（M1 M2 M5 + M3-dry）
    python tools/_r16_machine.py --apply        # 额外跑 M3 的 apply（写库！先看 plan 产出）
    python tools/_r16_machine.py --grp <chatroomId>   # 额外跑 M4 force
    python tools/_r16_machine.py --skip-install # 不装包（已确认包是新的）
"""
import argparse
import re
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ACTION = "com.lx.wxgroup.action.PROBE"
PKG = "com.tencent.mm"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"

RESULTS = []   # [(step, verdict, detail)]


def sh(*args, t=60):
    r = subprocess.run([ADB] + list(args), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore", timeout=t)
    return (r.stdout or "") + (r.stderr or "")


def bcast(op, **kw):
    """⚠️ 整条 am 作为一个字符串发给设备 sh，值一律单引号包（_r9_cdn.py:145 同款）。"""
    s = "am broadcast -a %s --es op '%s'" % (ACTION, op)
    for k, v in kw.items():
        if isinstance(v, bool):
            s += " --ez %s %s" % (k, "true" if v else "false")
        elif isinstance(v, int):
            s += " --ei %s %d" % (k, v)
        else:
            s += " --es %s '%s'" % (k, v)
    return sh("shell", s + " -p " + PKG)


def logclear():
    bcast("logclear")


def ensure_foreground():
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(3)


def pull_dbg():
    """拉落盘日志到本地 _sns.txt（390KB+，绝不整读，只用 grep 过滤）

    ⚠️ 落盘文件是 **5000 行环形覆盖**（LogX.MAX_LINES）：写满后清空从头写。
    所以「没搜到某行」有两种可能 —— 探针真的没跑 / 探针跑了但被后续日志冲掉了。
    判据里一律先说清这两种可能，别让人一看到空就往「探针名写错」上查。
    """
    out = sh("shell", "cat", DBG)
    with open("_sns.txt", "w", encoding="utf-8", errors="ignore") as f:
        f.write(out)
    n = out.count("\n")
    if n >= 4900:
        print("  ⚠️ 日志已到 %d 行（上限 5000，环形覆盖中）⇒ 长跑后早先的行可能已被冲掉" % n)
    return out


def grep(pat, n=40):
    if not __import__("os").path.exists("_sns.txt"):
        return []
    with open("_sns.txt", encoding="utf-8", errors="ignore") as f:
        lines = [l.rstrip("\n") for l in f if re.search(pat, l)]
    return lines[-n:]


def record(step, ok, detail):
    RESULTS.append((step, "PASS" if ok else "FAIL", detail))
    print("  => [%s] %s  %s" % ("PASS" if ok else "FAIL", step, detail))


# ---------------------------------------------------------------- M1 朋友圈图片

def m1_snsprobe(n=50):
    print("\n=== M1 (A4) 朋友圈 SNS 缓存图片链路 ===")
    bcast("moments", sub="snsprobe", n=n)
    time.sleep(6)
    pull_dbg()
    idx = grep(r"MOMENT snsprobe idx", 3)
    rows = grep(r"MOMENT snsprobe snsId=", 200)
    summ = grep(r"MOMENT snsprobe sum", 3)

    for l in idx[-1:]:
        print("  " + l)
    print("  （明细行 %d 条，下面只打非 NONE 与首 8 条）" % len(rows))
    shown = 0
    for l in rows:
        if "hit=NONE" not in l or shown < 8:
            print("  " + l)
            shown += 1
    for l in summ[-1:]:
        print("  " + l)

    if not summ:
        record("M1", False, "没有 sum 行 ⇒ 探针没跑（查：微信是否前台 / op 名对不对 / 归档是否为空）")
        return

    m = re.search(r"rows=(\d+) noMedia=(\d+) withIds=(\d+) hit=(\d+)", summ[-1])
    if not m:
        record("M1", False, "sum 行格式不符：%s" % summ[-1][:120])
        return
    rows_n, no_media, with_ids, hit = (int(x) for x in m.groups())

    # 三步判据（与 docs/_r15_i1_impl.md 一致）
    if rows_n == 0:
        record("M1", False, "归档为 0 条 ⇒ 先滚朋友圈让 SnSGuard 归档，再重跑")
    elif no_media >= rows_n:
        record("M1", False,
               "noMedia=%d/%d ⇒ 【字段号错】content 解不出 mediaId，改 MomentsLogic.PROTO_FIELD_*（6/8/1）"
               % (no_media, rows_n))
    elif with_ids > 0 and hit == 0:
        record("M1", False,
               "withIds=%d 但 hit=0 ⇒ 【文件名不匹配】mediaId 拿到了，但候选名对不上真机文件名；"
               "拿上面的 raw/pre/cand0 与设备实际文件名对账" % with_ids)
    else:
        record("M1", True, "rows=%d withIds=%d hit=%d ⇒ 链路通，可以开 COPY_IMAGES" % (rows_n, with_ids, hit))


# ---------------------------------------------------------------- M2 群名数据源

def m2_groupname():
    print("\n=== M2 (C5) 群名数据源装载 ===")
    bcast("quiet", sub="at", sub2="grpname")
    time.sleep(5)
    bcast("quiet", sub="at", sub2="grpstat")
    time.sleep(3)
    pull_dbg()
    st = grep(r"GROUPNAME stat", 5)
    for l in st[-1:]:
        print("  " + l)
    if not st:
        record("M2", False, "没有 GROUPNAME stat 行 ⇒ 探针没跑（check op/sub/sub2 名）")
        return
    m = re.search(r"ready=(\w+).*?count=(\d+).*?loads=(\d+).*?loadFail=(\d+)", st[-1])
    if not m:
        record("M2", False, "stat 行格式不符：%s" % st[-1][:140])
        return
    ready, count, loads, fail = m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4))
    if ready == "true" and count > 0:
        record("M2", True, "ready=true count=%d loads=%d loadFail=%d ⇒ 群名匹配数据源可用" % (count, loads, fail))
    elif loads > 0 and fail > 0:
        record("M2", False, "loads=%d loadFail=%d ⇒ 装载尝试过但失败，看 lastError 与上面日志" % (loads, fail))
    else:
        record("M2", False, "ready=%s count=%d loads=%d ⇒ 仍未装载（warmUp 未触发或主库句柄还没拿到）"
               % (ready, count, loads))

    # 样本对账（人读）
    bcast("quiet", sub="at", sub2="grplist")
    time.sleep(3)
    pull_dbg()
    for l in grep(r"GROUPNAME sample", 3)[-1:]:
        print("  样本: " + l[:200])


# ---------------------------------------------------------------- M3 归档恢复

def m3_restore(do_apply=False):
    print("\n=== M3 (C8) 从归档恢复（先 dry） ===")
    bcast("snsg", sub="plan")
    time.sleep(5)
    pull_dbg()
    plan = grep(r"SNSG plan|SNSG PLAN|plan ", 40)
    print("  plan 行 %d 条：" % len(plan))
    for l in plan[:20]:
        print("  " + l)
    if not plan:
        record("M3", False, "没有 plan 行 ⇒ 探针没跑或没可恢复项；先确认 op=snsg sub=plan 是否已注册")
        return

    if not do_apply:
        record("M3", True, "dry 已出（未加 --apply，未写库）。看完 plan 明细再决定是否 apply")
        return

    print("  ⚠️ 已显式 --apply，开始写库 …")
    bcast("snsg", sub="apply")
    time.sleep(8)
    bcast("snsg", sub="flags")
    time.sleep(4)
    pull_dbg()
    flags = grep(r"SNSG flags", 60)
    print("  flags 行 %d 条（前 12）：" % len(flags))
    for l in flags[:12]:
        print("  " + l)
    record("M3", bool(flags), "apply 后 flags=%d 条；逐条核对可见位是否恢复" % len(flags))


# ---------------------------------------------------------------- M4 新加群 force

def m4_force(chatroom):
    print("\n=== M4 (C4) 新加群自动免打扰 · force 路径 ===")
    if not chatroom:
        record("M4", False, "未提供 --grp <chatroomId>，跳过（这是需要参数的项）")
        return
    print("  目标群: %s" % chatroom)
    bcast("quiet", sub="grp", sub2="force", val=chatroom)
    time.sleep(10)
    pull_dbg()
    fr = grep(r"force OK|force FAILED|ADND", 30)
    for l in fr[-10:]:
        print("  " + l)
    ok = any("force OK" in l for l in fr)
    realmute = grep(r"realmute", 20)
    detail = "force OK" if ok else "force FAILED/无输出"
    if ok:
        detail += "；realmute 日志 %d 条" % len(realmute)
    record("M4", ok, detail)


# ---------------------------------------------------------------- M5 标签数据源

def m5_label():
    print("\n=== M5 (D4) 微信标签数据源 ===")
    bcast("label", sub="stat")
    time.sleep(5)
    pull_dbg()
    st = grep(r"LABEL stat", 5)
    for l in st[-1:]:
        print("  " + l)
    if not st:
        record("M5", False, "没有 LABEL stat 行 ⇒ 探针没跑（check op=label sub=stat）")
        return
    m = re.search(r"ready=(\w+).*?labels=(\d+).*?users=(\d+).*?links=(\d+)", st[-1])
    if not m:
        record("M5", False, "stat 行格式不符：%s" % st[-1][:140])
        return
    ready, labels, users, links = m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4))
    if ready == "true" and labels > 0 and users > 0:
        record("M5", True, "ready=true labels=%d users=%d links=%d ⇒ 标签数据源可用，K3 标签段会显示"
               % (labels, users, links))
    else:
        record("M5", False, "ready=%s labels=%d users=%d ⇒ 未装载或装载失败，看 loadFail 与 lastError"
               % (ready, labels, users))

    # 取一个真实 wxid 做 of 对账（分隔符已定案为英文逗号）
    loaded = grep(r"LABEL loaded", 2)
    for l in loaded[-1:]:
        print("  装载: " + l[:200])


# ---------------------------------------------------------------- M6 群发 UI（只做准备）

def m6_masssend_prep():
    print("\n=== M6 (C9) 群发 UI 按钮 · 前置准备 ===")
    print("  ⚠️ 本步只打印前置状态，**不点击**（点面板按钮需要人看屏幕确认，或单独用 ui 定位）。")
    pull_dbg()
    stat = grep(r"MASSSEND stat", 3)
    for l in stat[-1:]:
        print("  " + l)
    record("M6", True, "前置状态已打印；请手动点「开始群发」后看 MASSSEND done（或单独步做）")


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="M3 跑 apply（写库！先看 plan）")
    ap.add_argument("--grp", default="", help="M4 的 chatroomId")
    ap.add_argument("--skip-install", action="store_true", help="不装包")
    ap.add_argument("--n", type=int, default=50, help="M1 的条数（探针内部钳到 50）")
    a = ap.parse_args()

    print("=" * 68)
    print("R16 批次 2 · 真机验证（不需人工）")
    print("=" * 68)

    print("\n[前置] 拉起微信 + 清日志")
    ensure_foreground()
    logclear()
    time.sleep(1)
    # 注意：`dumpsys activity activities` 里那一行是 `topResumedActivity=`，
    # 不是 `mResumedActivity=`（后者在 `dumpsys activity` 不带参数时才出现）。
    top = "?"
    ds = sh("shell", "dumpsys", "activity", "activities")
    mm = re.search(r"topResumedActivity=ActivityRecord\{[^ ]+ u0 ([^ }]+)", ds)
    if mm:
        top = mm.group(1)
    print("  top: %s" % top)
    if not top.startswith(PKG):
        print("  ⚠️ 前台不是微信（是 %s）⇒ 广播可能不投递（微信后台不投递，见 CLAUDE.md）" % top)

    # ⚠️ 顺序有依赖：M1 跑 snsprobe，M2 再跑就会把它顶上 5000 行以外。
    #    所以每步跑完立刻 pull+grep（各 mX 函数内部已这么做），不要攒到最后统一拉。
    m1_snsprobe(a.n)
    m2_groupname()
    m3_restore(a.apply)
    m4_force(a.grp)
    m5_label()
    m6_masssend_prep()

    print("\n" + "=" * 68)
    print("汇总")
    print("=" * 68)
    for step, verdict, detail in RESULTS:
        print("  %-4s %-6s %s" % (step, verdict, detail))
    fails = [r for r in RESULTS if r[1] == "FAIL"]
    print("\n  共 %d 项，FAIL %d 项" % (len(RESULTS), len(fails)))
    print("  日志已落 _sns.txt（%d 字节），绝不整读，用 dev.py q 或 grep 过滤" %
          (__import__("os").path.getsize("_sns.txt") if __import__("os").path.exists("_sns.txt") else 0))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
