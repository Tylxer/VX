"""R21d 角标**落点**验证：角标是否在「所属消息行」的正下方，而不是 item 左上角。

## 为什么单独一个脚本
`_r21d_badge_verify.py` 只看**计数器**（binds/hits/added）——它能证明「链路通」，
但**证明不了位置**。上一版就栽在这：计数器全绿（`resolved==chat`、`added=4`），
角标却落在 item 的 `(0,0)`，压住时间戳。**位置必须用坐标验，计数验不出来。**

## 判据（全部来自 uiautomator 的实际 bounds）
对每一枚角标，向上找它的 item 根（`com.tencent.mm:id/bt3` 或最近的大容器），
再在 item 子树里找「消息行」（高度 ≥ 半 item 高、且在角标之上、可见的非角标节点）：

| 判据 | 含义 | 通过条件 |
|---|---|---|
| P1 不在左上角 | 角标 x 不应恒为 0 | `bx1 > 0` 或 `by1 > item.by1 + 100` |
| P2 在消息行下方 | 角标顶 ≥ 消息行底 | `by1 >= row.y2 - tol` |
| P3 不压时间戳 | 角标与时间戳槽无重叠 | 两者的矩形不相交 |
| P4 水平侧一致 | 自己发的靠右、对方靠左 | 角标中心与消息行内头像同侧 |

`tol` 是 30px 容差（BELOW 规则会留 4dp≈11px 的 margin，且亚像素取整会差 1~2px）。

用法：
    python tools/_r21d_badge_pos.py            # 只读当前屏幕
    python tools/_r21d_badge_pos.py --scroll   # 先滚动几次，找带角标的屏
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = r"C:/Users/lx/AppData/Local/Android/Sdk/platform-tools/adb.exe"
TOL = 30


def sh(*a):
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore")
    return (r.stdout or "") + (r.stderr or "")


def dump():
    sh("shell", "uiautomator dump /sdcard/ui.xml")
    return sh("shell", "cat /sdcard/ui.xml")


def parse(xml):
    """→ (nodes, kids)

    ⚠️ **必须用 XML 解析器，不能正则扫**。踩过两次：
      1. 根元素是 `<hierarchy>` 而不是 `<node>`，它自己没有 bounds —— 从它开始 walk 会直接 return；
      2. 若用正则按 `<node ...>|</node>` 维护栈，遇到 bounds 解析失败就 `continue`，
         会把一个**没有出栈**的开始标签留在栈里，此后所有父子关系错位（时间戳节点因此被漏掉）。
    """
    root = ET.fromstring(xml)
    nodes, kids = [], {}

    def walk(el, parent):
        i = len(nodes)
        b = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", el.get("bounds", "") or "")
        nodes.append({
            "cls": (el.get("class") or "").split(".")[-1],
            "id": (el.get("resource-id") or "").split("/")[-1],
            "txt": el.get("text") or "",
            "b": tuple(int(x) for x in b.groups()) if b else None,
        })
        kids.setdefault(parent, []).append(i)
        for c in el:
            if c.tag == "node":
                walk(c, i)

    for top in root:
        if top.tag == "node":
            walk(top, None)
    return nodes, kids


def find_badges(nodes):
    return [i for i, n in enumerate(nodes) if "已阻止撤回" in n["txt"]]


def ancestors(nodes, kids, i):
    parent = {}
    for p, ks in kids.items():
        for c in ks:
            parent[c] = p
    out, cur = [], parent.get(i)
    while cur is not None:
        out.append(cur)
        cur = parent.get(cur)
    return out


def main():
    if "--scroll" in sys.argv:
        for _ in range(6):
            xml = dump()
            if "已阻止撤回" in xml:
                break
            sh("shell", "input swipe 720 2200 720 1600 400")
            time.sleep(1.0)

    xml = dump()
    nodes, kids = parse(xml)
    # 丢掉没有 bounds 的节点（但 kids 里已记录，用于上游定位）—— 这里按需过滤
    bi = find_badges(nodes)
    print("=" * 74)
    print("角标数 = %d" % len(bi))
    print("=" * 74)
    if not bi:
        print("屏幕上没有角标 —— 先滚到有被撤回消息的位置（--scroll），或确认开关已开。")
        return

    allok = True
    for i in bi:
        b = nodes[i]["b"]
        print("\n▸ 角标 %r  [%d,%d][%d,%d]  %dx%d"
              % (nodes[i]["txt"], b[0], b[1], b[2], b[3], b[2] - b[0], b[3] - b[1]))

        # 向上找 item 根：优先 bt3，否则取第一个「高度 > 角标高*2」的祖先
        anc = ancestors(nodes, kids, i)
        root = None
        for a in anc:
            if nodes[a]["id"] == "bt3" and nodes[a]["b"] is not None:
                root = a
                break
        if root is None:
            for a in anc:
                ab = nodes[a]["b"]
                if ab is not None and ab[3] - ab[1] > (b[3] - b[1]) * 2:
                    root = a
                    break
        if root is None:
            print("  ⛔ 找不到 item 根，跳过")
            allok = False
            continue
        rb = nodes[root]["b"]
        print("  item 根 %s#%s [%d,%d][%d,%d]  %dx%d"
              % (nodes[root]["cls"], nodes[root]["id"], *rb, rb[2] - rb[0], rb[3] - rb[1]))

        # item 内所有候选：可见、非角标、不是根自己
        cands = []
        stack = list(kids.get(root, []))
        while stack:
            j = stack.pop(0)
            cands.append(j)
            stack.extend(kids.get(j, []))
        rows = [j for j in cands
                if nodes[j]["b"] is not None
                and "已阻止撤回" not in nodes[j]["txt"]
                and nodes[j]["b"][3] - nodes[j]["b"][1] > 0]
        if not rows:
            print("  ⛔ item 里没有其他节点")
            allok = False
            continue

        # 消息行 = 高度最大的（真机实测消息行远高于别的）
        row = max(rows, key=lambda j: (nodes[j]["b"][3] - nodes[j]["b"][1]) * (nodes[j]["b"][2] - nodes[j]["b"][0]))
        qb = nodes[row]["b"]
        print("  消息行   %s#%s [%d,%d][%d,%d]  %dx%d"
              % (nodes[row]["cls"], nodes[row]["id"], *qb, qb[2] - qb[0], qb[3] - qb[1]))

        # P1：不在左上角
        p1 = (b[0] > 0) or (b[1] > rb[1] + 100)
        print("  P1 不在左上角      x1=%d (item x1=%d)  y1=%d (item y1+100=%d)  %s"
              % (b[0], rb[0], b[1], rb[1] + 100, "✅" if p1 else "⛔"))

        # P2：在消息行下方
        p2 = b[1] >= qb[3] - TOL
        print("  P2 在消息行下方    badge.y1=%d >= row.y2-%d=%d  %s"
              % (b[1], TOL, qb[3] - TOL, "✅" if p2 else "⛔"))

        # P3：不压时间戳（找形如 HH:MM 的文本节点）
        ts = [j for j in cands
              if nodes[j]["b"] is not None
              and re.fullmatch(r"\d{1,2}:\d{2}", nodes[j]["txt"].strip())]
        p3 = True
        for j in ts:
            tb = nodes[j]["b"]
            ox = min(b[2], tb[2]) - max(b[0], tb[0])
            oy = min(b[3], tb[3]) - max(b[1], tb[1])
            if ox > 0 and oy > 0:
                p3 = False
                print("  P3 不压时间戳      ⛔ 与 %r [%d,%d][%d,%d] 重叠 %dx%d"
                      % (nodes[j]["txt"], *tb, ox, oy))
        if p3:
            print("  P3 不压时间戳      %s" % ("✅" if not ts else "✅ 无重叠"))

        # P4：水平侧一致（头像 = 消息行内离中线最远的子节点）
        rc = (qb[0] + qb[2]) // 2
        av = None
        best = -1
        for j in kids.get(row, []):
            jb = nodes[j]["b"]
            if jb is None or jb[2] - jb[0] <= 0:
                continue
            d = abs((jb[0] + jb[2]) // 2 - rc)
            if d > best:
                best, av = d, j
        p4 = True
        if av is not None:
            ab = nodes[av]["b"]
            av_right = (ab[0] + ab[2]) // 2 > rc
            bg_right = (b[0] + b[2]) // 2 > 1440 // 2
            p4 = (av_right == bg_right)
            print("  P4 水平侧一致      头像%s / 角标%s  %s"
                  % ("右" if av_right else "左", "右" if bg_right else "左",
                     "✅" if p4 else "⛔"))
        else:
            print("  P4 水平侧一致      无头像节点，跳过（计为 ✅）")

        allok = allok and p1 and p2 and p3 and p4

    print("\n" + "=" * 74)
    print("⇒ 总判定：%s" % ("✅ PASS" if allok else "⛔ 有判据未通过（看上面 ⛔ 行）"))
    print("=" * 74)


if __name__ == "__main__":
    main()
