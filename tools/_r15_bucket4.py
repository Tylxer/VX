# -*- coding: utf-8 -*-
"""⛔ 已作废（R16-F3，2026-10-24）—— 第 6 行把 `fliterPre` 写成 `s[2:]` 再跳前导 '0'，**是错的**。

真实语义：**从下标 0 起**跳前导 '0'（`t0#s` 的 `[19] const/4 v3,#0`）。
本脚本的反推前提因此不成立，结论不可信。**分桶公式路线已整体废弃**（实测只中 0.68%），
现行正解 = 索引直扫，见 `MomentsLogic.buildSnsIndex()`。
**保留本文件仅为追溯历史输出，不要据此重算任何结论。**
正确实现见 `wxgroup/.../MomentsLogic.kt` 的 `fliterPre`（`i = 0`）。

--- 以下为原文（含错误语义，勿信）---

R15 决定性实验：分桶到底用哪个公式。

已知
  - 文件名 = "snst_" + fliterPre(vm3.d)   （t0#V 反汇编确认）
  - fliterPre(s) = s[2:] 再跳过前导 '0'   （t0#s 反汇编确认）  ⛔ 错：不是 s[2:]
  - 桶 = <hex1>/<hex2>

未知：桶是 MD5(?) 的哪两位？

策略：从文件名数字 N 反推候选 mediaId M（满足 fliterPre(M)==N），
      对每个候选检验 MD5(M)[2]/[8] 是否等于桶。
      同时对照 MD5(N)[0]/[1]。
"""
import json, hashlib, re, os, collections

CACHE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), '_cache')
DUMP = os.path.join(CACHE, '_r15_bucket_files.json')


def hx(s):
    return hashlib.md5(s.encode()).hexdigest()


def fliter_pre(s):
    """⛔ 已作废：这里的 `i = 2` 是错的，真实语义是 `i = 0`。
    正确实现见 `wxgroup/app/src/main/.../MomentsLogic.kt` 的 `fliterPre`。
    保留原文仅为追溯历史输出，**不要用本函数重算任何结论**。
    """
    if not s:
        return ''
    i = 2  # ⛔ 错，应为 0
    while i < len(s) and s[i] == '0':
        i += 1
    return s[i:]


def load():
    files = json.load(open(DUMP, encoding='utf-8'))
    rows = []
    for h1, h2, f in files:
        m = re.match(r'^(snsb_|snsu_|snst_|sight_|snstblur_src_)(\d+)', f)
        if not m:
            continue
        rows.append((h1, h2, m.group(2)))
    return rows


def main():
    rows = load()
    print('样本:', len(rows))

    # 先确认 fliterPre 的自洽：用候选 M 反推 N
    # mediaId 常见格式 = "15" + 18位   或 "15" + "0" + ...
    # 构造候选：把 N 前面补 "15" + 若干 "0"，总长 20
    def cands(n):
        out = []
        # 20 位：前 2 位 '15'，剩余 = ('0' * pad) + n
        pad = 20 - 2 - len(n)
        if pad >= 0:
            out.append('15' + '0' * pad + n)
            out.append('150' + '0' * (pad - 1) + n if pad >= 1 else None)
        out.append('15' + n)
        out.append(n)
        return [x for x in out if x]

    print('\n===== 候选 mediaId 是否满足 fliterPre(M)==N（自检）=====')
    for idx in range(4):
        good = tot = 0
        for h1, h2, n in rows:
            c = cands(n)
            if idx >= len(c):
                continue
            tot += 1
            if fliter_pre(c[idx]) == n:
                good += 1
        if tot:
            print('  候选#%d  fliterPre 自洽 %d/%d = %.2f%%'
                  % (idx, good, tot, 100.0 * good / max(1, tot)))

    print('\n===== 分桶公式对照 =====')
    tests = [
        ('MD5(N)[0]/[1]', lambda n, c: (hx(n)[0], hx(n)[1])),
        ('MD5(N)[2]/[8]', lambda n, c: (hx(n)[2], hx(n)[8])),
    ]
    for ci in range(3):
        for name, fn in [('MD5(M%d)[0]/[1]' % ci, 'a'), ('MD5(M%d)[2]/[8]' % ci, 'b')]:
            ok = tot = 0
            for h1, h2, n in rows:
                cs = cands(n)
                if ci >= len(cs):
                    continue
                m = cs[ci]
                d = hx(m)
                tot += 1
                a, b = (d[0], d[1]) if fn == 'a' else (d[2], d[8])
                if a == h1 and b == h2:
                    ok += 1
            if tot:
                print('  %-18s 命中 %4d/%4d = %6.2f%%'
                      % (name, ok, tot, 100.0 * ok / max(1, tot)))

    for name, fn in tests:
        ok = 0
        for h1, h2, n in rows:
            a, b = fn(n, None)
            if a == h1 and b == h2:
                ok += 1
        print('  %-18s 命中 %4d/%4d = %6.2f%%'
              % (name, ok, len(rows), 100.0 * ok / max(1, len(rows))))

    # 关键：检查 MD5(N)[0:2] 与 MD5(M0)[2]/[8] 是否在样本上等价
    print('\n===== 等价性检查（前 10 条明细）=====')
    n = 0
    for h1, h2, nm in rows:
        cs = cands(nm)
        m0 = cs[0] if cs else nm
        d_n = hx(nm)
        d_m = hx(m0)
        eq = (d_n[0] == d_m[2] and d_n[1] == d_m[8])
        if n < 10:
            print('  N=%-20s M0=%-20s 桶=%s/%s  MD5(N)[0:2]=%s  MD5(M0)[2]/[8]=%s  %s'
                  % (nm, m0, h1, h2, d_n[:2], d_m[2] + d_m[8], 'SAME' if eq else 'DIFF'))
        n += 1

    return 0


if __name__ == '__main__':
    main()
