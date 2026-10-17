import re
import sys

# 用法: python _find.py <dump.xml> <text>    -> 打印该文本的点击坐标 "x y"
path, target = sys.argv[1], sys.argv[2]
s = open(path, encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s):
    if m.group(1) == target:
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
