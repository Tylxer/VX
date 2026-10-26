#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""回溯反汇编缓存中某行所属的方法/类。
用法: python _r28_encl.py <cache_file> <line1> [line2 ...]
"""
import sys, io

def find_encl(path, target, maxback=6000):
    with io.open(path, 'r', encoding='utf-8', errors='replace') as f:
        # 二分不可行(顺序文件)，直接顺序扫描到 target
        buf = []
        n = 0
        last_meth = None
        last_cls = None
        for line in f:
            n += 1
            s = line.rstrip('\n')
            if '] com.' in s or '] L' in s:
                # 形如: 3dcdf0:                                        |[3dcdf0] com.xxx.y:(...)V
                idx = s.find('|[')
                if idx >= 0:
                    last_meth = s[idx+2:].strip()
            if s.startswith('Class descriptor'):
                last_cls = s
            if n == target:
                return n, last_cls, last_meth
            if n > target:
                return None, None, None
    return None, None, None

if __name__ == '__main__':
    path = sys.argv[1]
    for t in sys.argv[2:]:
        n, cls, meth = find_encl(path, int(t))
        print('LINE %s' % t)
        print('  class  : %s' % (cls or '?'))
        print('  method : %s' % (meth or '?'))
