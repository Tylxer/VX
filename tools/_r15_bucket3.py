# -*- coding: utf-8 -*-
"""⛔ 已作废（R16-F3，2026-10-24）—— 本脚本的 `fliter_pre` 用的是**错误的** `i = 2`。

真实语义是 **从下标 0 起**跳前导 '0'（`t0#s` 的 `[19] const/4 v3,#0`），本脚本 `[19]` 处
写成了 `const/4 v3,#2`，导致 `K2/K3/K5` 三个候选键全错、`MD5(fliterPre)[0]/[1] = 4/1029`
这个结论**不可信**。**分桶公式路线早已整体废弃**（真机 1029 文件实测 `y5#e` 公式只中
0.68%），现行正解 = 索引直扫，见 `MomentsLogic.buildSnsIndex()`。
**保留本文件仅为追溯历史输出，不要据此重算任何结论。**
（正确实现见 `wxgroup/.../MomentsLogic.kt` 的 `fliterPre`，`i = 0`，606 单测覆盖。）

--- 以下为原文（含错误语义，勿信）---

R15 分桶前置串还原：`Lbf3/t0;->s(String)` = fliterPre

反汇编逻辑（classes3.dex, code_off=0x216004）：
  [ 7] if-nez v6 -> +8            ; v6 = 入参，null 则返回 ""
  [15] length() -> v2
  [19] v3 = #2                    ; ⛔ 错：真机是 #0
  [20] if-ge v3, v2 -> abs 34     ; if (v2 <= 2) goto substring(3)?? —— 注意 if-ge 是 >=
  [22] charAt(v3) -> v4           ; 取第 3 个字符        ⛔ 错：取第 1 个
  [26] v5 = #48 ('0')
  [28] if-eq v4, v5 -> abs 31     ; 若是 '0' -> v3++ 继续
  [30] goto abs 34                ; 否则跳出
  [31] v3++                       ; 跳过前导 0
  [33] goto abs 20
  [34] substring(v3)

⛔ 原文「真实语义 = 从下标 2 开始、跳过连续的 '0'」**是错的**，正确是「从下标 0 开始」。

本脚本用真机 1021 个文件名反推：分桶键到底用的是
  K1 = mediaId              (原样)
  K2 = fliterPre(mediaId)   (去前2位 + 跳前导0)     ⛔ 语义错
  K3 = fliterPre 的 [:20]
  K4 = mediaId[:20]
并检验分桶 <hex1>/<hex2> = MD5(K)[0]/MD5(K)[1]
"""
import json, hashlib, re, os, collections

CACHE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), '_cache')
DUMP = os.path.join(CACHE, '_r15_bucket_files.json')


def hx(s):
    return hashlib.md5(s.encode()).hexdigest()


def fliter_pre(s):
    """⛔ 已作废：这里复刻的是**错误的** `i = 2`。真实语义是 `i = 0`（从下标 0 起跳前导 '0'）。
    正确实现见 `wxgroup/app/src/main/.../MomentsLogic.kt` 的 `fliterPre`。
    保留原文仅为追溯历史输出，**不要用本函数重算任何结论**。
    """
    if not s:
        return ''
    i = 2  # ⛔ 错，应为 0
    while i < len(s) and s[i] == '0':
        i += 1
    return s[i:]


def main():
    files = json.load(open(DUMP, encoding='utf-8'))
    rows = []
    for h1, h2, f in files:
        m = re.match(r'^(snsb_|snsu_|snst_|sight_|snstblur_src_)(\d+)', f)
        if not m:
            continue
        rows.append((h1, h2, m.group(1).rstrip('_'), m.group(2)))

    print('样本数:', len(rows))

    keys = {
        'K1 mediaId 原样':      lambda m: m,
        'K2 fliterPre(mid)':    lambda m: fliter_pre(m),
        'K3 fliterPre[:20]':    lambda m: fliter_pre(m)[:20],
        'K4 mid[:20]':          lambda m: m[:20],
        'K5 fliterPre 去0后[:20]': lambda m: fliter_pre(m).lstrip('0')[:20],
    }

    print('\n===== 分桶 <h1><h2> = MD5(K)[0..1] =====')
    for name, fn in keys.items():
        ok = 0
        for h1, h2, pref, mid in rows:
            k = fn(mid)
            if not k:
                continue
            d = hx(k)
            if d[0] == h1 and d[1] == h2:
                ok += 1
        print('  %-26s 命中 %4d/%4d = %6.2f%%' %
              (name, ok, len(rows), 100.0 * ok / len(rows)))

    # 看 fliterPre 产出的长度分布
    print('\n===== fliterPre(mid) 长度分布 =====')
    dist = collections.Counter()
    for h1, h2, pref, mid in rows:
        dist[len(fliter_pre(mid))] += 1
    print(' ', dict(sorted(dist.items())))

    print('\n===== mediaId 原长分布 =====')
    dist2 = collections.Counter(len(mid) for _, _, _, mid in rows)
    print(' ', dict(sorted(dist2.items())))

    # 逐条对照：20 位的样本 fliterPre 后是多少
    print('\n===== 20 位 mediaId 的 fliterPre 前 6 位对照（前 8 条）=====')
    n = 0
    for h1, h2, pref, mid in rows:
        if len(mid) == 20 and n < 8:
            print('  mid=%s  fliterPre=%s (len %d)' % (mid, fliter_pre(mid), len(fliter_pre(mid))))
            n += 1

    print('\n===== 长 mediaId 的 fliterPre（前 8 条）=====')
    n = 0
    for h1, h2, pref, mid in rows:
        if len(mid) > 20 and n < 8:
            print('  mid=%s (len %d)\n     fliterPre=%s (len %d)'
                  % (mid, len(mid), fliter_pre(mid), len(fliter_pre(mid))))
            n += 1

    return 0


if __name__ == '__main__':
    main()
