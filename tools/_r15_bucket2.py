# -*- coding: utf-8 -*-
"""R15 分桶公式穷举反推。

已知：真机 <hex1>/<hex2>/<snsb_|snst_|snsu_><数字> 共 1021 个。
目标：找出「被哈希的串 S」和「取的是 md5(S) 的哪两位」，使命中率 100%。

关键前提验证：同一 mediaId 的 snsb_/snst_/snsu_ 是否落在同一个桶。
  - 若落在同一桶 ⇒ 桶由 mediaId 派生（与文件名前缀无关）
  - 若不同桶     ⇒ 桶由「完整文件名」派生
"""
import subprocess, hashlib, re, sys, collections, json, os

ADB = 'C:/Users/lx/AppData/Local/Android/Sdk/platform-tools/adb.exe'
BASE = '/sdcard/Android/data/com.tencent.mm/cache'
CACHE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), '_cache')
DUMP = os.path.join(CACHE, '_r15_bucket_files.json')


def sh(args, timeout=180):
    r = subprocess.run([ADB] + args, capture_output=True, text=True,
                       timeout=timeout, errors='replace')
    return r.stdout


def collect():
    """返回 [(h1, h2, fname)]，带本地缓存避免重复抓"""
    if os.path.exists(DUMP):
        with open(DUMP, 'r', encoding='utf-8') as f:
            data = json.load(f)
        print('用缓存', DUMP, len(data), '条')
        return [tuple(x) for x in data]

    out = sh(['shell', 'ls', BASE])
    roots = []
    for ln in out.splitlines():
        ln = ln.strip()
        if re.fullmatch(r'[0-9a-f]{32}', ln):
            if 'sns' in sh(['shell', 'ls', '%s/%s' % (BASE, ln)]).split():
                roots.append('%s/%s/sns' % (BASE, ln))
    print('sns roots:', roots)

    res = []
    for root in roots:
        for h1 in sh(['shell', 'ls', root]).split():
            if not re.fullmatch(r'[0-9a-f]', h1):
                continue
            for h2 in sh(['shell', 'ls', '%s/%s' % (root, h1)]).split():
                if not re.fullmatch(r'[0-9a-f]', h2):
                    continue
                for f in sh(['shell', 'ls', '%s/%s/%s' % (root, h1, h2)]).split():
                    res.append([h1, h2, f.strip()])
    with open(DUMP, 'w', encoding='utf-8') as f:
        json.dump(res, f)
    print('抓取', len(res), '个文件，已缓存')
    return [tuple(x) for x in res]


def hx(s):
    if isinstance(s, str):
        s = s.encode('utf-8', 'surrogateescape')
    return hashlib.md5(s).hexdigest()


def num_of(fname):
    m = re.match(r'^(sns[bunt]+|snstblur)_(\d+)', fname)
    return m.group(2) if m else None


def main():
    files = collect()
    rows = [(h1, h2, f, num_of(f)) for h1, h2, f in files]
    rows = [r for r in rows if r[3]]
    print('可解析文件:', len(rows))

    # ===== 前提验证：同一 mediaId 是否同桶 =====
    bymedia = collections.defaultdict(set)
    for h1, h2, f, num in rows:
        bymedia[num].add((h1, h2))
    multi = {k: v for k, v in bymedia.items() if len(v) > 1}
    print('\n[前提验证] 同一 mediaId 出现在多个桶的个数:', len(multi), '/', len(bymedia))
    for k, v in list(multi.items())[:10]:
        print('   mid=%s → 桶 %s' % (k, sorted(v)))

    # 同一 mediaId 有几种前缀
    bypref = collections.defaultdict(set)
    for h1, h2, f, num in rows:
        bypref[num].add(f.split('_')[0])
    print('   同 mid 多前缀样本:', sum(1 for v in bypref.values() if len(v) > 1))

    # ===== 穷举：串 S 的构造 × 取位 =====
    def variants(fname, num):
        pre = fname.split('_')[0]
        v = {
            'num': num,
            'num[:20]': num[:20],
            'fname': fname,
            'pre_num': pre + '_' + num,
            'strip00': num[2:] if num.startswith('00') else num,
            '0_num': '0' + num,
            '00_num': '00' + num,
            'num_lstrip0': num.lstrip('0'),
        }
        return v

    # 先看每类串用 [2]/[8] 与 [0]/[1] 的命中
    print('\n===== 穷举：哈希串 × 取位 =====')
    keynames = None
    results = []
    for key in ['num', 'num[:20]', 'fname', 'pre_num', 'strip00', '0_num', '00_num', 'num_lstrip0']:
        for i, j in [(0, 1), (2, 8), (0, 8), (2, 1), (1, 2)]:
            ok = 0
            for h1, h2, f, num in rows:
                s = variants(f, num)[key]
                try:
                    d = hx(s)
                except Exception:
                    continue
                if d[i] == h1 and d[j] == h2:
                    ok += 1
            pct = 100.0 * ok / len(rows)
            results.append((pct, ok, key, i, j))
            if keynames is None:
                keynames = True
    results.sort(reverse=True)
    for pct, ok, key, i, j in results[:12]:
        print('  S=%-14s 取位[%d]/[%d]  命中 %4d/%4d = %6.2f%%' % (key, i, j, ok, len(rows), pct))

    return 0


if __name__ == '__main__':
    sys.exit(main())
