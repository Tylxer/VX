"""R21 问题2 取证：rcontact 里 @openim 各 type 的语义

只读，不写任何库。走 LabelHuntProbe 的 sub=sql 通道（isReadOnly 只放行 select）。

判据目的：`@openim` 254 条里哪些**真的是好友**（该出现在群发/批量加好友选择器里）。
已知：ContactScanner 只扫通讯录 UI 对象图，@openim 不在 UI 渲染 ⇒ 源头就没有，
所以必须换数据源。换之前得先知道「哪些该留」，否则会把企业微信的外部会话也扫进来。
"""
import re
import time

from _lib import (bc, ensure_foreground, grep, logclear, pull_dbg, step, top)


def probe_sql(sql, note=""):
    """整条 am 命令作**一个字符串**传 sh —— 分包传参在含 --es sql "..." 时会被 shell 拆错。"""
    from _lib import sh
    cmd = ("am broadcast -a com.lx.wxgroup.action.PROBE "
           "--es op label --es sub sql "
           '--es sql "%s" -p com.tencent.mm' % sql.replace('"', '\\"'))
    sh("shell", cmd)
    time.sleep(2)


def main():
    step("0. 前台 + 清日志")
    ensure_foreground()
    logclear()

    step("1. 开探针窗口")
    bc("--es", "op", "label", "--es", "sub", "on")
    time.sleep(2)

    step("2. stat 触发库捕获")
    bc("--es", "op", "label", "--es", "sub", "stat")
    time.sleep(3)

    step("3. @openim 的 type 分布")
    probe_sql("select type,count(*) from rcontact where username like '%@openim' group by type",
              "dist")

    step("4. 普通好友基线（对照）")
    probe_sql("select type,count(*) from rcontact where username not like '%@%' group by type",
              "base")

    step("5. @openim 明细（前 20 行，看 nickname/conRemark/verifyFlag 特征）")
    probe_sql("select type,username,nickname,conRemark,verifyFlag from rcontact where username like '%@openim'",
              "detail")

    step("6. 拉日志")
    pull_dbg()
    for pat in [r"LABEL\s+sql db=", r"LABEL\s+  "]:
        print("  --- %s ---" % pat)
        grep(pat, 60)
    print("  top=%s" % top())


if __name__ == "__main__":
    main()
