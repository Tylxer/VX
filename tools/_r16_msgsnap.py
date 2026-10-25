#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""R16 G2 消息种类识别链 —— 真机 dump 脚本（一次抓完）。

==============================================================================
✅ 模块侧探针**已接线完毕**（2026-10-24，主会话）：`ForceForward.probe()` 已有
   `sub=msgsnap` 分支 + `msgsnapReport()`，`compileDebugKotlin` EXIT=0。
   ⇒ 装新包后本脚本即可用。装的是旧包时只会报 MSGSNAP-NOT-FOUND（脚本不崩）。
==============================================================================

## 0. 模块侧现状（读这里，别照旧示意改代码）

`wxgroup/.../feature/ForceForward.kt` 里已有：

    "msgsnap" -> msgsnapReport()

`msgsnapReport()` 的实际链路（**全部经 DEX 反汇编坐实**，见 `docs/_r16_g2_msgsnap.md`
§1.2/§1.3 与 §7.1 逐条核对表）：

    staleViewForProbe()（忽略 TTL 读最近一次长按 View）
      → 向上 12 层找 ChattingListView / ChattingRecyclerView / MMChattingListView
      → 按 listKind 分派定位：
          listview → getPositionForView(v)          ⇒ position
          recycler → 混淆名候选链 F0/G0/getChildAdapterPosition/getChildLayoutPosition(item根View)
                     （退回 A0(I) 扫描 ViewHolder.d）⇒ position
      → MMChattingListView.getBaseAdapter()（退回 m()）  ⇒ adapter
      → adapter.getItem(position)          ⇒ Object（实际是 h9）
      → getMsgId() / getType()             ⇒ 未混淆 getter
      → field_isSend / field_imgPath       ⇒ 字段在父类 jj/m7，沿链反射

⚠️ 三个已验证的坑（别往回改）：
  1. `ChattingListView` **没有** `getPositionForView`（只有 `ChattingRecyclerView` 声明），
     而且 `ChattingRecyclerView` 那个声明是**恒 -1 的空桩**。所以 **recycler 形态必须走
     RecyclerView 自身的混淆名候选链 F0/G0（原名 getChildAdapterPosition/getChildLayoutPosition
     已被 R8 混淆）**，不能用 `getPositionForView`；只有 listview 形态走它。
  2. 取 adapter 只有混淆名 `m()`，**没有** `getAdapter`；未混淆的是 `getBaseAdapter()`。
  3. `t3.getItem(I)` 返回 **`Object`** 而非 `h9`，所以不能 `as? h9`，只能按名字反射。

| 广播                              | 作用                          | 必须打印的**逐字**格式 |
|---|---|---|
| `op=forward sub=msgsnap`          | ★ 一次抓全长按上下文          | `FORWARD MSGSNAP talker=... view=... ageMs=N attached=... list=... pos=N posVia=... posFail=... adapter=... adapterVia=... h9=... msgId=... type=... isSend=... imgPath=... menu=...` |
| `op=forward sub=dump`（已有）     | 最近一次聊天消息菜单文案清单  | `lastChatMenuTexts(N)=文案+文案` |

⛔ **`ageMs` / `attached` 不是装饰**：`pressedView()` 带 10 秒 TTL，而「手长按 → adb 发广播」
经常超过 10 秒。探针忽略 TTL 并报出陈旧程度与是否仍挂窗口 —— 判读时必须先看这两个，
`attached=false` 时的 `pos=-1` 是**取证太慢的假失败**，不是 adapter 链不通。

## 1. 用法

    python tools/_r16_msgsnap.py --help
    python tools/_r16_msgsnap.py                    # 默认：拉前台→清日志→人工长按→抓 msgsnap→解析
    python tools/_r16_msgsnap.py --dry-run          # 只打计划，一条 adb 都不发
    python tools/_r16_msgsnap.py --no-manual        # 不等人，假定已经长按过（复跑用）

## 2. 已知坑（照 _r9_cdn.py / _r8_rows.py 范式，别改回去）

1. **adb 不在 PATH**，必须绝对路径；Bash 里直接调会反复重启 daemon。
2. **微信在后台不投递广播** ⇒ 发探针前先 `am start` 拉前台并校验 `topResumedActivity`。
3. 整条 `am` 命令要作为**一个字符串**传给设备 `sh`（列表传参会被二次解析到空）；
   值一律**单引号**包。kvset 的参数名是 `val` 不是 `value`。
4. 落盘日志 5000 行环形覆盖 ⇒ 测前先 `op=logclear`。
5. **长按必须是真手长按**：adb `input` 长按在这台设备上会被识别成点击（`ConvMenuInject.kt:74` 实锤）。
"""

import argparse
import os
import re
import subprocess
import sys
import time

# ---------------------------------------------------------------- 常量
ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PKG = "com.tencent.mm"
ACTION = "com.lx.wxgroup.action.PROBE"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL_DBG = os.path.join(ROOT, "_sns.txt")   # 与 _lib.py 的 LOCAL_DBG 一致

# 探针日志前缀（模块侧 LogX.i 打，脚本靠它匹配）。改了模块侧前缀脚本就抓不到。
MSGSNAP_PREFIX = "FORWARD MSGSNAP "


# ---------------------------------------------------------------- adb 封装
def sh(*a, t=120):
    """⚠️ 一律 python subprocess 调 adb（Bash 里调会重启 daemon）。"""
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore", timeout=t)
    return ((r.stdout or "") + (r.stderr or "")).strip()


def bcast(op, **kw):
    """⚠️ 整条 am 作为一个字符串发给设备 sh，值一律单引号包（见头部坑 3）。

    参数按类型选择 adb 开关（与 EntryReceiver.kt 取值方式对齐）：
      int  -> --ei（getIntExtra）
      bool -> --ez（getBooleanExtra）
      str  -> --es（getStringExtra）
    """
    s = "am broadcast -a %s --es op '%s'" % (ACTION, op)
    for k, v in kw.items():
        if isinstance(v, bool):
            s += " --ez %s %s" % (k, "true" if v else "false")
        elif isinstance(v, int):
            s += " --ei %s %d" % (k, v)
        else:
            s += " --es %s '%s'" % (k, v)
    return sh("shell", s + " -p " + PKG, t=60)


def forward(sub):
    """给模块侧 op=forward 探针发命令（sub 未实现时日志里不会有 MSGSNAP 行，脚本会报缺探针）。"""
    return bcast("forward", sub=sub)


# ---------------------------------------------------------------- 前台 / 日志
def top():
    out = sh("shell", "dumpsys activity activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{[^ ]+ u0 ([^ ]+)", out)
    return m.group(1) if m else "?"


def ensure_foreground():
    """⚠️ 微信在后台不投递广播 —— 必须先拉前台。"""
    sh("shell", "am start -n %s/.ui.LauncherUI" % PKG)
    time.sleep(3)
    return top()


def pull():
    sh("pull", DBG, LOCAL_DBG)
    try:
        with open(LOCAL_DBG, encoding="utf-8", errors="ignore") as f:
            return f.read().splitlines()
    except OSError:
        return []


def logclear():
    return bcast("logclear")


def last_msgsnap(lines):
    """捞最后一条 `FORWARD MSGSNAP ` 开头的日志（带时间+线程前缀，用子串匹配，不锚行首）。

    ⚠️ 同 _r9_cdn.py 的教训：日志行实际是 `HH:MM:SS.mmm Thread-N FORWARD MSGSNAP ...`，
       `startswith` 恒 False。这里用 `in` 匹配。
    返回 None 表示没抓到（脚本据此报缺探针，而不是静默通过）。
    """
    for i in range(len(lines) - 1, -1, -1):
        if MSGSNAP_PREFIX in lines[i]:
            return lines[i]
    return None


def parse_msgsnap(line):
    """解析 `FORWARD MSGSNAP talker=.. view=.. ageMs=.. attached=.. list=.. pos=.. posVia=..
    adapter=.. adapterVia=.. h9=.. msgId=.. type=.. isSend=.. imgPath=.. menu=..`

    所有字段缺了就打 `<missing>`，**不静默当 none** —— 一眼看出是模块没打还是字段真为空。

    ⚠️ `ageMs` / `attached` / `posVia` / `adapterVia` / `h9` 是模块侧后加的字段。
    老 APK 上没有这些字段时会显示 `<missing>`，**这本身不是失败**，
    只是无法用它来区分「View 陈旧」和「链路不通」。
    """
    fields = {
        "talker": "<missing>", "view": "<missing>", "ageMs": "<missing>",
        "attached": "<missing>", "list": "<missing>", "listCls": "<missing>",
        "pos": "<missing>",
        "posVia": "<missing>", "posFail": "<missing>",
        "adapter": "<missing>", "adapterVia": "<missing>",
        "h9": "<missing>", "msgId": "<missing>", "type": "<missing>",
        "isSend": "<missing>", "imgPath": "<missing>", "menu": "<missing>",
    }
    # 从 MSGSNAP 前缀之后开始切
    tail = line[line.index(MSGSNAP_PREFIX) + len(MSGSNAP_PREFIX):]
    for name in fields:
        m = re.search(r"%s=(\S+)" % name, tail)
        if m:
            fields[name] = m.group(1)
    return fields


def _verdict_line(fields):
    """把解析结果拼成 verdict 一行（供主会话直接抄走）。"""
    return (
        "talker=%s view=%s ageMs=%s attached=%s list=%s listCls=%s pos=%s posVia=%s posFail=%s "
        "adapter=%s adapterVia=%s h9=%s msgId=%s type=%s isSend=%s imgPath=%s menu=%s"
        % (fields["talker"], fields["view"], fields["ageMs"], fields["attached"],
           fields["list"], fields["listCls"], fields["pos"], fields["posVia"], fields["posFail"],
           fields["adapter"],
           fields["adapterVia"], fields["h9"], fields["msgId"], fields["type"],
           fields["isSend"], fields["imgPath"], fields["menu"])
    )


def diagnose(fields):
    """按「先排除假失败」的顺序给判读，返回 (ok, 说明) 列表。

    ⛔ 顺序不能颠倒：`view=none` / `attached=false` 这类**取证环境问题**
       必须在「adapter 链不通」之前判掉，否则会把环境问题误诊成代码 bug。
    """
    out = []
    if fields["view"] in ("<missing>", "none"):
        return [(False, "view=none ⇒ 没抓到长按 View。先确认：① 长按是真手长按（adb `input` 长按"
                       "在这台设备会被识别成点击）；② 模块已装且 long-press trap 已 install。")]
    if fields["attached"] == "false":
        return [(False, "attached=false ⇒ View 已从窗口摘下（ageMs=%s）。这是**取证太慢**造成的假失败，"
                       "不是 adapter 链不通 ⇒ 重做长按，缩短「长按→发广播」间隔后再跑。"
                       % fields["ageMs"])]
    # ⛔ pos 是否已拿到 —— 供下面 recycler 分支短路用（R17-V3 追问 + 2026-10-24 输入构造验证）。
    #    模块侧已用 `if (pos < 0)` 保证「pos>=0 时 posFail 必为 none」，所以正常日志不会矛盾；
    #    但 `diagnose()` 是对**任意历史日志**做判读的，老 APK / 手工构造的日志可能造出
    #    `pos=5 posFail=classCast` 这种禁形。那种组合**以 pos 为准**。
    try:
        _pos_ok = int(fields["pos"]) >= 0
    except (ValueError, TypeError):
        _pos_ok = False
    if fields["list"] == "none":
        return [(False, "attached 正常但 list=none ⇒ 向上 12 层没找到聊天列表"
                       "（按继承链判定 ChattingRecyclerView/ChattingListView 的子类）。"
                       "可能不在聊天页，或层级 >12。"
                       "⚠️ 若这条日志的 `listCls` 字段（老 APK 无）显示了真实的列表类名，"
                       "说明是**判定逻辑没覆盖到那个子类**，不是没找到 —— 请把类名报回来更新 `listKindOf`。")]
    if fields["list"] == "unknown":
        return [(False, "list=unknown ⇒ 找到了列表对象，但它的继承链上既没有 "
                       "`ChattingRecyclerView` 也没有 `ChattingListView`（微信又换了实现类）。"
                       "**实际类名 = %s**（listCls 字段）⇒ 把类名加进 `ForceForward.listKindOf` "
                       "的 when 分支即可。" % fields["listCls"])]
    if fields["list"] == "recycler":
        # ⛔ posFail 四态化（F5 修，2026-10-24）：按失败阶段分别判读，别再混成一个 recyclerStub。
        pf = fields["posFail"]
        # ⛔ 禁形短路：pos 已拿到就以 pos 为准，别被 posFail 带偏（R17-V3 后补，2026-10-24）。
        #    位置必须在**本分支内**：原先写在外层 `out.append(...)` + 继续往下走，
        #    但本分支所有路径都是 `return`，append 的内容会被整个丢弃 ⇒ 短路形同不存在
        #    （我自己 2026-10-24 输入构造验证抓到的自引入缺陷 4）。
        #    能走到这里的矛盾组合只可能来自旧 APK 或手工改过的日志，正常包不会产生。
        if _pos_ok and pf not in ("none", "<missing>"):
            return [(True, "list=recycler 且 pos=%s 已拿到（posVia=%s），但 posFail=%s 与之矛盾 ⇒ "
                           "**以 pos 为准**：pos 是本次真正反射取回的值，posFail 是旧版 APK 的"
                           "残留字段或日志构造错误，不影响本条取证结果。"
                           "（正常包不会产生这个组合，模块侧 `if (pos < 0)` 已挡住。）"
                           % (fields["pos"], fields["posVia"], pf))]
        if pf == "noMethod":
            return [(False, "list=recycler 且 posFail=noMethod ⇒ 候选方法名 F0/G0/"
                           "getChildAdapterPosition/getChildLayoutPosition 全部不存在 ⇒ **代码 bug**："
                           "dex 版本又变了或被进一步混淆，要更新 ForceForward 的候选链。")]
        if pf == "classCast":
            return [(False, "list=recycler 且 posFail=classCast ⇒ 传进去的 itemRoot 不是 "
                           "RecyclerView 的直接 child（I0 里 check-cast LayoutParams 失败，"
                           "被 InvocationTargetException 包着）。检查 itemRoot 上溯逻辑，"
                           "或改走 A0(pos)/c3.d 比对。")]
        if pf == "itemRootNotFound":
            return [(False, "list=recycler 且 posFail=itemRootNotFound ⇒ 定位链路断在第一步："
                           "长按 View 向上 12 层没找到 parent===chatList 的节点（item 根 View）。"
                           "可能长按的不是列表内 item，或层级 >12。")]
        if pf == "returnedMinusOne":
            return [(False, "list=recycler 且 posFail=returnedMinusOne ⇒ 方法调通了但都返回 -1："
                           "ViewHolder 已被回收/移除，或 itemRoot 不是真正的 item 根 View。"
                           "看 view=%s 是否仍 attached、itemRoot 是否挂在 RecyclerView 上。" % fields["view"])]
        if pf in ("<missing>",):
            return [(False, "list=recycler 且 posFail=<missing> ⇒ 装的是**旧 APK**（没有 posFail 字段）。"
                           "旧包走 recyclerStub 语义，先升级模块再复跑。")]
        # pf == "none" 且 pos>=0 属正常，落到后面通用判读
        if pf == "none" and fields["pos"] not in ("-1", "<missing>"):
            pass
        else:
            # ⛔ 文案已按 posFail 四态重写（R17-V3 报，P2：旧文案还写着
            #    「getPositionForView 恒 -1 属已知预期」，那是 recyclerStub 时代的说法，
            #    现在 recycler 形态根本不调 getPositionForView，四态也已分开判读）。
            #    能走到这里 = posFail 是个**未预期**的值（四态 + none + <missing> 都被上面拦掉了），
            #    所以直接报「未知 posFail」比套旧语义更诚实。
            return [(False, "list=recycler 且 pos=%s（posVia=%s posFail=%s）⇒ "
                            "posFail 是**未预期值**（预期 none/noMethod/classCast/"
                            "itemRootNotFound/returnedMinusOne）⇒ 模块与脚本版本不匹配，"
                            "或日志被手工改过。先对比 APK 版本，别急着改代码。"
                            % (fields["pos"], fields["posVia"], fields["posFail"]))]
    if fields["pos"] in ("-1", "<missing>"):
        # ⚠️ listview 形态现在也会带 posFail（listview 分支补的），所以这里要把两种失败原因分开说，
        #    不能一律归因成「getPositionForView 没命中」。
        pf = fields["posFail"]
        if pf == "noMethod":
            return [(False, "list=%s 且 posFail=noMethod ⇒ `getPositionForView(View)` 反射不到。"
                           "listview 形态本该从 `android.widget.ListView` 继承到它，"
                           "反射不到说明 chatList 其实**不是** ListView 系（listKindOf 判错了）"
                           "⇒ 看 listCls 的实际类名。" % fields["list"])]
        if pf == "returnedMinusOne":
            return [(False, "list=%s 且 posFail=returnedMinusOne ⇒ `getPositionForView` 调通了但返回 -1。"
                           "多半这条 View 不在该列表里（或已被移除）⇒ 看 view=%s 是不是列表内的子 View、"
                           "attached=%s 是否为 true。" % (fields["list"], fields["view"], fields["attached"]))]
        return [(False, "list=%s 找到了但 pos=-1（posVia=%s posFail=%s）⇒ 定位没命中，"
                       "多半这条 View 不在该列表里。看 view=%s 是不是列表内的子 View。"
                       % (fields["list"], fields["posVia"], pf, fields["view"]))]
    if fields["adapter"] == "none":
        return [(False, "pos=%s 拿到了但 adapter=none ⇒ getBaseAdapter()/m() 都失败。"
                       "看 posVia=%s 与 list=%s 是否配对。" % (fields["pos"], fields["posVia"], fields["list"]))]
    if fields["msgId"] in ("none", "<missing>"):
        return [(False, "adapter=%s 拿到了但 msgId=none（h9=%s）⇒ getItem(pos) 返回的不是消息对象，"
                       "或 getMsgId 反射失败。" % (fields["adapter"], fields["h9"]))]

    ok = [(True, "msgId=%s type=%s isSend=%s imgPath=%s ⇒ adapter 链**通了**，"
                 "这一条可以拿去对账 msgType 常量表" % (fields["msgId"], fields["type"],
                                                  fields["isSend"], fields["imgPath"]))]
    if fields["type"] == "none":
        ok.append((False, "但 type=none ⇒ getType 反射失败，需换个取法"))
    if fields["imgPath"] == "none":
        ok.append((True, "imgPath=none：若这条不是图片/视频消息属正常；若是图片则 §3.6 的假设要推翻"))
    return ok


# ---------------------------------------------------------------- 计划 / 主流程
def plan_of(a):
    p = []
    p.append("1. 拉前台（am start LauncherUI），校验 topResumedActivity")
    p.append("2. op=logclear（日志 5000 行环形，测前必须清）")
    if a.no_manual:
        p.append("3. --no-manual：不等人，假定已经长按过")
    else:
        p.append("3. ⚠️ 人工步骤：在聊天页**真手长按一条消息**，长按完立刻回车"
                 "（不要干等 —— View 会被回收，attached=false 就白跑）")
    p.append("4. op=forward sub=msgsnap（探针已在 ForceForward.msgsnapReport 接线完毕）")
    p.append("5. pull 日志，抓最后一条 `FORWARD MSGSNAP `")
    p.append("6. 打印 verdict：talker/view/ageMs/attached/list/pos/posVia/adapter/"
             "adapterVia/h9/msgId/type/isSend/imgPath/menu + 判读")
    return p


def main():
    a = build_parser().parse_args()

    if a.dry_run:
        print("[dry-run] 计划（**未执行任何 adb**）：")
        for line in plan_of(a):
            print("  " + line)
        print("\n✅ 模块侧 op=forward sub=msgsnap 探针已接线（ForceForward.msgsnapReport）。"
              "装新包后本脚本即可用；装旧包只会报 MSGSNAP-NOT-FOUND。")
        return 0

    print("=== 1. 拉前台 ===")
    act = ensure_foreground()
    print("  top:", act)
    if "LauncherUI" not in act and "ChattingUI" not in act:
        print("  ⚠️ 不在微信前台 —— 广播可能不投递，先手动切回微信")

    print("=== 2. logclear ===")
    logclear()
    time.sleep(1.0)

    if not a.no_manual:
        print("=== 3. 人工长按 ===")
        print("  ⚠️ 请在聊天页**真手长按一条消息**（adb input 长按会被识别成点击）")
        print("  ⏱ 长按完请立刻回车，别等 —— 等太久 View 会被回收（attached=false 就白跑了）")
        try:
            input("  长按好了按回车继续…")
        except EOFError:
            # 非交互环境（如重定向/管道）：退回定长等待
            print("  （非交互，改为等 %ds）" % a.manual_sec)
            time.sleep(a.manual_sec)

    print("=== 4. 发 msgsnap 探针 ===")
    forward("msgsnap")
    time.sleep(a.settle)
    # 兜底也问一次 stat，看 lastKind/lastPlan 佐证「菜单是否被认成 CHAT_MSG」
    forward("stat")
    time.sleep(a.settle)

    print("=== 5. pull 日志 ===")
    lines = pull()
    print("  日志行数:", len(lines))

    hit = last_msgsnap(lines)
    if hit is None:
        print("\n==== MSGSNAP-NOT-FOUND ====")
        print("  日志里没有 `FORWARD MSGSNAP ` 行 ⇒ 装的是旧 APK（分支未接）或模块未加载")
        print("  先确认 pm path 时间戳 ≥ 本地 APK，且日志里有 FF installed long-press trap。")
        print("  ⚠️ 也可能被 5000 行环形覆盖冲掉了：重跑并立刻 pull。")
        # 顺带把最近几条 FORWARD 行打出来，帮主会话判断「是没装还是没长按」
        fw = [x for x in lines if "FORWARD " in x]
        print("  最近 FORWARD 行（%d 条）：" % len(fw))
        for x in fw[-6:]:
            print("    " + x[:200])
        return 1

    print("\n==== MSGSNAP RAW ====")
    print("  " + hit[:300])

    fields = parse_msgsnap(hit)
    v = []
    v.append("==== R16-MSGSNAP VERDICT ====")
    v.append("当前会话 talker     : %s" % fields["talker"])
    v.append("长按最深 View 类名  : %s" % fields["view"])
    v.append("View 陈旧程度 ageMs : %s（真机上 >10000 属正常：探针不吃 TTL）" % fields["ageMs"])
    v.append("View 是否挂窗口     : %s（false ⇒ pos 必然 -1，属取证太慢的假失败）" % fields["attached"])
    v.append("列表形态            : %s（listview=ChattingListView / recycler=ChattingRecyclerView / none=没找到列表 / unknown=找到了但类名不认识）" % fields["list"])
    v.append("列表实际类名        : %s（★ 判 listKind 失配时看这里）" % fields["listCls"])
    v.append("position            : %s（via %s / posFail %s）" % (fields["pos"], fields["posVia"], fields["posFail"]))
    v.append("adapter 类名        : %s（via %s）" % (fields["adapter"], fields["adapterVia"]))
    v.append("取到的消息对象      : %s（期望 h9）" % fields["h9"])
    v.append("msgId               : %s" % fields["msgId"])
    v.append("msgType             : %s" % fields["type"])
    v.append("isSend              : %s（1=自己发 / 0=别人发 / none=没拿到）" % fields["isSend"])
    v.append("本地图片路径 imgPath: %s" % fields["imgPath"])
    v.append("消息菜单文案清单    : %s" % fields["menu"])

    # ⛔ 判读按「先排除假失败」的顺序来（view=none / attached=false 都属环境问题）
    v.append("")
    v.append("---- 判读 ----")
    for ok, msg in diagnose(fields):
        v.append("%s %s" % ("✅" if ok else "❌", msg))
    block = "\n".join(v)

    print()
    print(block)

    try:
        with open(a.out, "a", encoding="utf-8") as f:
            f.write("\n=== R16-MSGSNAP %s ===\n" % time.strftime("%H:%M:%S"))
            f.write(block + "\n")
        print("落盘:", a.out)
    except OSError as e:
        print("⚠️ 写落盘文件失败:", e)
    return 0


def build_parser():
    ap = argparse.ArgumentParser(
        prog="tools/_r16_msgsnap.py",
        description="R16 G2：真机抓长按消息的 msgId/msgType/本地路径/isSend + 菜单文案",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=("默认拉前台→清日志→人工长按→抓 msgsnap→解析。\n"
                "依赖模块侧 op=forward sub=msgsnap 探针，见文件头第 0 节。\n\n"
                "例：\n"
                "  python tools/_r16_msgsnap.py --help\n"
                "  python tools/_r16_msgsnap.py\n"
                "  python tools/_r16_msgsnap.py --no-manual --dry-run\n"))
    ap.add_argument("--manual-sec", type=int, default=20, help="人工长按等待秒数，默认 %(default)s")
    ap.add_argument("--no-manual", action="store_true", help="不等人，假定已经长按过（复跑用）")
    ap.add_argument("--settle", type=float, default=2.0, help="发完广播到 pull 的等待，默认 %(default)s")
    ap.add_argument("--out", default=LOCAL_DBG, help="结果落盘，默认 %s" % LOCAL_DBG)
    ap.add_argument("--dry-run", action="store_true", help="只打计划，一条 adb 都不发")
    return ap


if __name__ == "__main__":
    sys.exit(main())
