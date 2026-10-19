"""R2 第 0 步：只读取证批（不改代码、不写微信库）

用法: python tools/_r2_probe.py

目的（两条，缺一不可）：
  1. rcontact 的**真实列名** —— 群名桥（core/GroupNameSource）要用它按
     `username=<xxx>@chatroom` 取群显示名。取证前不许猜列名。
  2. 朋友圈图片文件名线索 —— 判定 COPY_IMAGES 能不能开、原图怎么对上。

⚠️ 全部是只读：`sel` 走 probe 的 sql 白名单（只放行 select/explain/只读 pragma），
   `pragma table_info` 也在白名单内。不 kvset、不删任何东西、不重启微信。
"""
import sys
import time

from _lib import *

# ---------------------------------------------------------------- 1. 前置
step("1. 拉起微信（后台广播不投递）+ 清日志")
ensure_foreground()
logclear()
time.sleep(1)

# ---------------------------------------------------------------- 2. rcontact 列名
step("2. rcontact 列名（pragma table_info）")
# 整条 am 命令作一个字符串 + 单引号包值：SQL 里带括号和下划线，走 Python 列表传参会被设备 sh 二次解析
sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE --es op label "
            "--es sub sql --es sql 'pragma table_info(rcontact)' -p com.tencent.mm")
time.sleep(3)

# ---------------------------------------------------------------- 3. 群名取值验证
# 先看 rcontact 里 chatroom 行到底有没有 conRemark / nickname 有值
step("3. 群显示名样本（前 5 个 @chatroom）")
sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE --es op label "
            "--es sub sql --es sql 'select username,conRemark,nickname from rcontact "
            "where username like \"%@chatroom\" limit 5' -p com.tencent.mm")
time.sleep(3)

# ---------------------------------------------------------------- 4. 朋友圈图片线索
step("4. 朋友圈图片线索（moments sub=img）")
sh("shell", "am broadcast -a com.lx.wxgroup.action.PROBE --es op moments "
            "--es sub img --ei n 3 -p com.tencent.mm")
time.sleep(3)

# ---------------------------------------------------------------- 5. 拉日志
step("5. 拉日志并筛关键字")
pull_dbg()
for kw in ("LABEL", "MOMENT", "refused", "error"):
    print("\n" + "=" * 70)
    print("### 关键词:", kw)
    grep(kw, n=40)
