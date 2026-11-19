# -*- coding: utf-8 -*-
"""R73-L2b 菜单容器定位判据的变异验证。

⚠️ Python 等价复刻，非 Kotlin 实跑 —— 本脚本**不碰 gradle / 不碰设备 / 不改 Kotlin 源码**。
它按 ForwardLogic.kt:findMenuBoxCandidate 的逻辑用 Python 等价重写一份，把 8 个用例
（原 5 个 + R73-L2b 新增 3 个）的树与期望结果也等价复刻，然后逐个套 3 个变异体，
看对应用例是否变红。每个变异体都必须是「有语义的」：至少有一条用例变红，否则视为
假验证并直接 assert 失败。

运行：python app/src/test/mut/mut_menu_box.py
输出：控制台 + 同目录 mut_menu_box_out.txt；报告同时以注释块附在本文件末尾。
"""
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

# 与 ForwardLogic.CHAT_MENU_WORDS 完全一致（严格相等口径）
CHAT_MENU_WORDS = {
    "复制", "转发", "收藏", "删除", "撤回", "引用", "多选", "提醒",
    "稍后处理", "投诉", "删除消息", "转发到朋友", "发送给朋友",
    "查找聊天记录", "搜一搜", "翻译", "保存图片", "添加表情",
}
CONV_MENU_WORDS = {"标为未读", "标记未读", "不显示该聊天", "删除该聊天", "置顶该聊天", "取消置顶"}


@dataclass
class Node:
    """等价 ForwardLogic.MenuNode。"""
    id: str
    text: Optional[str] = None
    is_container: bool = False
    children: List["Node"] = field(default_factory=list)


def N(id: str, text: Optional[str] = None, container: bool = False, children=()) -> Node:
    return Node(id=id, text=text, is_container=container, children=list(children))


def item(id: str, word: str) -> Node:
    """一个 ListView item：LinearLayout 包 TextView（真机形状）。"""
    return N(id, container=True, children=[N(id + "-tv", text=word)])


def hits(node: Node, words) -> List[str]:
    """子树内命中词的文本节点 id（DFS 顺序），等价 Kotlin 的 hits()。"""
    out = []
    t = node.text.strip() if node.text else None
    if t and t in words:
        out.append(node.id)
    for c in node.children:
        out.extend(hits(c, words))
    return out


def find_menu_box(roots, words, min_hits: int, mode: str, tie: str):
    """等价 findMenuBoxCandidate。

    mode="new"       -> 命中数优先，同数按 tie 裁决（新判据）
    mode="depth_only"-> 只比深度、不比命中数（R73 旧判据，变异①）
    min_hits         -> 下界（正常值 2；改成 1 是变异②）
    tie="deep"/"shallow" -> 同命中数取最深 / 取最浅（变异③）
    """
    best_box = best_sample = None
    best_hits, best_depth = -1, -1

    def walk(node: Node, depth: int):
        nonlocal best_box, best_sample, best_hits, best_depth
        if not node.is_container:
            return
        for c in node.children:
            walk(c, depth + 1)
        hs = hits(node, words)
        if mode == "depth_only":
            if len(hs) >= min_hits and depth > best_depth:
                best_box, best_sample = node.id, hs[-1]
                best_hits, best_depth = len(hs), depth
            return
        if len(hs) < min_hits:
            return
        tie_wins = (depth > best_depth) if tie == "deep" else (depth < best_depth)
        if len(hs) > best_hits or (len(hs) == best_hits and tie_wins):
            best_box, best_sample = node.id, hs[-1]
            best_hits, best_depth = len(hs), depth

    for r in roots:
        walk(r, 0)
    return (best_box, best_sample) if best_depth >= 0 else None


# ---------------------------------------------------------------- 用例（树 + 期望）
def text_row(prefix: str, words: List[str]) -> List[Node]:
    return [N("%sc%d" % (prefix, i), text=w) for i, w in enumerate(words)]


def build_cases():
    cases = []  # (name, roots, expected(box, sample))

    # 原 5 个用例
    list_view = N("list", container=True, children=[
        item("it0", "复制"), item("it1", "转发"), item("it2", "删除")
    ])
    cases.append(("findsListViewWithNestedItemViews",
                  [N("popup", container=True, children=[N("frame", container=True, children=[list_view])])],
                  ("list", "it2-tv")))
    cases.append(("findsPlainMenuWithDirectTextChildren",
                  [N("popup", container=True, children=[N("menu", container=True, children=[
                      N("t0", text="复制"), N("t1", text="转发"), N("t2", text="收藏")
                  ])])],
                  ("menu", "t2")))
    cases.append(("rejectsConvListMenu",
                  [N("conv", container=True, children=[
                      N("c0", text="删除该聊天"), N("c1", text="不显示该聊天"), N("c2", text="标为未读")
                  ])],
                  None))
    cases.append(("rejectsNonMenuOverlay",
                  [N("panel", container=True, children=[
                      N("x0", text="发送"), N("x1", text="表情"), N("x2", text="语音输入")
                  ])],
                  None))
    cases.append(("needsAtLeastTwoHits_single",
                  [N("one", container=True, children=[N("t0", text="复制")])],
                  None))
    cases.append(("needsAtLeastTwoHits_two",
                  [N("two", container=True, children=[N("t0", text="复制"), N("t1", text="转发")])],
                  ("two", "t1")))

    # R73-L2b 新增 3 个用例
    row1 = N("row1", container=True, children=text_row("r1", ["复制", "转发", "收藏", "删除", "多选"]))
    row2 = N("row2", container=True, children=text_row("r2", ["引用", "提醒", "翻译", "搜一搜"]))
    menu = N("menu", container=True, children=[row1, N("divider", text="分隔线"), row2])
    cases.append(("PicksMenuBodyForMultiRowGrid",
                  [N("popup", container=True, children=[menu])],
                  ("menu", "r2c3")))

    list_view2 = N("list", container=True, children=[
        item("it0", "复制"), item("it1", "转发"), item("it2", "删除")
    ])
    shell = N("shell", container=True, children=[list_view2])
    cases.append(("BreaksHitTieByDepth",
                  [N("popup", container=True, children=[shell])],
                  ("list", "it2-tv")))

    big = N("big", container=True, children=[
        N("b0", text="复制"), N("b1", text="转发"), N("b2", text="收藏"),
        N("b3", text="删除"), N("b4", text="多选")
    ])
    deep_tree = N("outer", container=True, children=[N("mid", container=True, children=[
        N("deep", container=True, children=[N("d0", text="引用"), N("d1", text="提醒")])
    ])])
    cases.append(("PrefersMoreHitsOverDeeperContainer",
                  [big, deep_tree],
                  ("big", "b4")))
    return cases


# ---------------------------------------------------------------- 变异体
# (名字, mode, min_hits, tie, 期望至少变红的用例)
MUTANTS = [
    ("M1 退化为旧「只比深度不比命中数」", "depth_only", 2, "deep",
     ["PicksMenuBodyForMultiRowGrid", "PrefersMoreHitsOverDeeperContainer"]),
    ("M2 minHits 从 2 改 1", "new", 1, "deep",
     ["needsAtLeastTwoHits_single"]),
    ("M3 同命中数取最浅（不取最深）", "new", 2, "shallow",
     ["BreaksHitTieByDepth", "PicksMenuBodyForMultiRowGrid"]),
]


def run(cases, mode="new", min_hits=2, tie="deep"):
    red = []
    for name, roots, expected in cases:
        got = find_menu_box(roots, CHAT_MENU_WORDS, min_hits, mode, tie)
        if got != expected:
            red.append((name, got, expected))
    return red


def main():
    out = []
    out.append("=== R73-L2b 变异验证（Python 等价复刻，非 Kotlin 实跑）===")
    cases = build_cases()

    base = run(cases)
    out.append("baseline（正确判据）: 用例 %d 条, 红 %d 条" % (len(cases), len(base)))
    for name, got, exp in base:
        out.append("  RED %s got=%s expected=%s" % (name, got, exp))
    assert not base, "baseline 自身不干净，先修复刻: %s" % (base,)

    caught = 0
    for name, mode, min_hits, tie, must_red in MUTANTS:
        red = run(cases, mode=mode, min_hits=min_hits, tie=tie)
        out.append("")
        out.append("=== %s (mode=%s, minHits=%d, tie=%s)" % (name, mode, min_hits, tie))
        for case_name, got, exp in red:
            out.append("  RED %-42s got=%-18s expected=%s" % (case_name, str(got), str(exp)))
        if not red:
            out.append("  ⚠️ 0 条变红 —— 假验证！")
        # 每个变异体必须有语义，且指定用例必须变红
        red_names = {r[0] for r in red}
        missing = [c for c in must_red if c not in red_names]
        out.append("  语义检查: 红=%d 条; 指定用例缺失=%s" % (len(red), missing or "无"))
        assert red, "%s 零红 => 假验证" % name
        assert not missing, "%s 未让指定用例变红: %s" % (name, missing)
        caught += 1

    out.append("")
    out.append("结论: 变异体 %d/%d 全部被捕获（每个 ≥1 条红且指定用例变红）" % (caught, len(MUTANTS)))
    text = "\n".join(out)
    print(text)

    # 输出落盘到同目录
    import os
    log = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mut_menu_box_out.txt")
    with open(log, "w", encoding="utf-8") as fh:
        fh.write(text + "\n")
    print("\n[written] " + log)


if __name__ == "__main__":
    main()

# ============================================================================
# 运行输出（Python 等价复刻，非 Kotlin 实跑；同时落盘于 mut_menu_box_out.txt）
# 命令：python app/src/test/mut/mut_menu_box.py
# ----------------------------------------------------------------------------
# === R73-L2b 变异验证（Python 等价复刻，非 Kotlin 实跑）===
# baseline（正确判据）: 用例 9 条, 红 0 条
#
# === M1 退化为旧「只比深度不比命中数」 (mode=depth_only, minHits=2, tie=deep)
#   RED PicksMenuBodyForMultiRowGrid               got=('row1', 'r1c4')   expected=('menu', 'r2c3')
#   RED PrefersMoreHitsOverDeeperContainer         got=('deep', 'd1')     expected=('big', 'b4')
#   语义检查: 红=2 条; 指定用例缺失=无
#
# === M2 minHits 从 2 改 1 (mode=new, minHits=1, tie=deep)
#   RED needsAtLeastTwoHits_single                 got=('one', 't0')      expected=None
#   语义检查: 红=1 条; 指定用例缺失=无
#
# === M3 同命中数取最浅（不取最深） (mode=new, minHits=2, tie=shallow)
#   RED findsListViewWithNestedItemViews           got=('popup', 'it2-tv') expected=('list', 'it2-tv')
#   RED findsPlainMenuWithDirectTextChildren       got=('popup', 't2')    expected=('menu', 't2')
#   RED PicksMenuBodyForMultiRowGrid               got=('popup', 'r2c3')  expected=('menu', 'r2c3')
#   RED BreaksHitTieByDepth                        got=('popup', 'it2-tv') expected=('list', 'it2-tv')
#   语义检查: 红=4 条; 指定用例缺失=无
#
# 结论: 变异体 3/3 全部被捕获（每个 ≥1 条红且指定用例变红）
# ============================================================================
