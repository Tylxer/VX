# -*- coding: utf-8 -*-
"""R15 分桶公式真机反推：从 sns 缓存里真实存在的 <hex1>/<hex2>/<name> 出发，
反推 <name> 里的 mediaId 应该用哪个 md5 下标做分桶。

用法： python tools/_r15_bucket.py
输出： 各候选公式命中率；以及 md5[2]/md5[8] 那条的落空样本明细
"""
import subprocess, hashlib, re, sys, collections

ADB = 'C:/Users/lx/AppData/Local/Android/Sdk/platform-tools/adb.exe'
BASE = '/sdcard/Android/data/com.tencent.mm/cache'


def sh(args, timeout=120):
    r = subprocess.run([ADB] + args, capture_output=True, text=True,
                       timeout=timeout, errors='replace')
    return r.stdout


def sns_root():
    """找 cache/<hash32>/sns 根"""
    out = sh(['shell', 'ls', BASE])
    hits = []
    for ln in out.splitlines():
        ln = ln.strip()
        if not re.fullmatch(r'[0-9a-f]{32}', ln):
            continue
        sub = sh(['shell', 'ls', '%s/%s' % (BASE, ln)])
        if 'sns' in sub.split():
            hits.append('%s/%s/sns' % (BASE, ln))
    return hits


def walk(root):
    """返回 [(hex1, hex2, fname)]"""
    res = []
    for h1 in sh(['shell', 'ls', root]).split():
        h1 = h1.strip()
        if not re.fullmatch(r'[0-9a-f]', h1):
            continue
        for h2 in sh(['shell', 'ls', '%s/%s' % (root, h1)]).split():
            h2 = h2.strip()
            if not re.fullmatch(r'[0-9a-f]', h2):
                continue
            for f in sh(['shell', 'ls', '%s/%s/%s' % (root, h1, h2)]).split():
                res.append((h1, h2, f.strip()))
    return res


def mediaid_of(fname):
    """snsb_/snsu_/snst_ 前缀 + 后面一段数字；sight_ 另有 &enc 后缀，这里跳过"""
    m = re.match(r'^sns[b|u|t]+_(\d+)', fname)
    if not m:
        return None
    return m.group(1)


def candidates(mid):
    """各种可能被当成 mediaId 的串"""
    c = {'raw': mid}
    c['nosplit'] = mid
    return c


def main():
    roots = sns_root()
    print('sns roots:', roots)
    if not roots:
        print('!! 没找到 sns 目录')
        return 1

    files = []
    for r in roots:
        files += walk(r)
    print('文件总数:', len(files))

    # 按前缀分类统计
    bypref = collections.Counter()
    for _, _, f in files:
        bypref[f.split('_')[0]] += 1
    print('前缀分布:', dict(bypref))

    # 对每个 20 位文件名，反推它「应该」在哪个桶
    rows = []   # (h1, h2, mid, md5_32)
    for h1, h2, f in files:
        mid = mediaid_of(f)
        if not mid:
            continue
        rows.append((h1, h2, mid))

    print('可解析 mediaId 的文件数:', len(rows))

    # 候选公式：
    #  A: md5(mid)[2] / md5(mid)[8]     ← 当前方案文档写法
    #  B: md5(mid)[0] / md5(mid)[1]     ← 上一轮实测写法
    #  C: md5(mid)[2] / md5(mid)[1]
    #  D: md5(mid)[0] / md5(mid)[8]
    def hx(s):
        return hashlib.md5(s.encode()).hexdigest()

    forms = {
        'A md5[2]/[8]': lambda m: (hx(m)[2], hx(m)[8]),
        'B md5[0]/[1]': lambda m: (hx(m)[0], hx(m)[1]),
        'C md5[2]/[1]': lambda m: (hx(m)[2], hx(m)[1]),
        'D md5[0]/[8]': lambda m: (hx(m)[0], hx(m)[8]),
    }

    for name, fn in forms.items():
        ok = 0
        for h1, h2, mid in rows:
            try:
                a, b = fn(mid)
            except Exception:
                continue
            if a == h1 and b == h2:
                ok += 1
        print('%-16s 命中 %4d/%4d = %6.2f%%' % (name, ok, len(rows),
                                               100.0 * ok / max(1, len(rows))))

    # 明细：B 公式落空的样本
    print('\n--- B 落空样本 (最多 15) ---')
    miss = []
    for h1, h2, mid in rows:
        a, b = forms['B md5[0]/[1]'](mid)
        if not (a == h1 and b == h2):
            miss.append((h1, h2, mid, a + b, len(mid)))
    print('落空数:', len(miss))
    for h1, h2, mid, bhex, mlen in miss[:15]:
        print('  真实 %s/%s  mid=%-26s len=%d  B算=%s' % (h1, h2, mid, mlen, bhex))

    # 明细：A 命中样本 (最多 10) ---
    print('\n--- A 命中样本 (最多 10) ---')
    n = 0
    for h1, h2, mid in rows:
        a, b = forms['A md5[2]/[8]'](mid)
        if a == h1 and b == h2:
            n += 1
            if n <= 10:
                print('  真实 %s/%s  mid=%s' % (h1, h2, mid))
    print('A 命中总数:', n)

    # ===== 最终规则验证：MD5(mid[:20])[:2] =====
    print('\n===== 最终规则 MD5(mid[:20]) 前两位 =====')
    stat = {}
    tot_ok = tot = 0
    for pref in ('snsb', 'snst', 'snsu'):
        ok = c = 0
        for h1, h2, f in files:
            if not f.startswith(pref + '_'):
                continue
            mid = mediaid_of(f)
            if not mid:
                continue
            c += 1
            d = hx(mid[:20])
            if d[0] == h1 and d[1] == h2:
                ok += 1
        stat[pref] = (ok, c)
        tot_ok += ok
        tot += c
    for pref, (ok, c) in stat.items():
        print('  %-5s 命中 %4d/%4d = %6.2f%%' % (pref, ok, c, 100.0 * ok / max(1, c)))
    print('  --- 合计 %d/%d = %6.2f%% ---' % (tot_ok, tot, 100.0 * tot_ok / max(1, tot)))

    # 反例检查：不加 [:20] 的情况
    ok = 0
    for h1, h2, mid in rows:
        d = hx(mid)
        if d[0] == h1 and d[1] == h2:
            ok += 1
    print('  对照：不截断 MD5(mid) 前两位命中 %d/%d = %.2f%%'
          % (ok, len(rows), 100.0 * ok / max(1, len(rows))))

    # 文件名长度分布
    ldist = collections.Counter()
    for _, _, f in files:
        mid = mediaid_of(f)
        if mid:
            ldist[len(mid)] += 1
    print('  mediaId 长度分布:', dict(sorted(ldist.items())))

    return 0


if __name__ == '__main__':
    sys.exit(main())
