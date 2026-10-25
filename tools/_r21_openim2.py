"""R21 问题2 取证（第二轮）：@openim 的 type 反例排查

第一轮结论（tools/_r21_openim.py）：
  @openim 254 条 → type=0:43 / type=1:68 / type=4:139 / type=5:2 / type=513:2
  普通联系人    → type=4:16233 / type=3:2405 / type=0:2344 / type=1:**仅 2** / 其余零散

疑点：type=1 在 @openim 里有 68 条且 nickname 全是真人；其中 `Anthony Meng` 带
conRemark「温州桁斯蒙总」—— 备注只能在通讯录里加，通常意味着**是好友**。
若 type=1 也含好友，那「type=4 才是好友」的判据就不成立。

本轮只做只读取证，不设计最终判据（判据交 friend-src / Pro 档）。
"""
import time

from _lib import bc, ensure_foreground, grep, logclear, pull_dbg, step
from _lib import sh


def probe_sql(sql):
    cmd = ("am broadcast -a com.lx.wxgroup.action.PROBE "
           "--es op label --es sub sql "
           '--es sql "%s" -p com.tencent.mm' % sql.replace('"', '\\"'))
    sh("shell", cmd)
    time.sleep(2)


def main():
    step("0. 前台 + 清日志 + 开窗口")
    ensure_foreground()
    logclear()
    bc("--es", "op", "label", "--es", "sub", "on")
    time.sleep(2)
    bc("--es", "op", "label", "--es", "sub", "stat")
    time.sleep(3)

    step("1. @openim 按 (type, 有无备注) 交叉")
    probe_sql("select type, case when conRemark is null or conRemark='' then 0 else 1 end, count(*) "
              "from rcontact where username like '%@openim' group by 1,2")

    step("2. @openim 的 verifyFlag 分布")
    probe_sql("select type,verifyFlag,count(*) from rcontact where username like '%@openim' group by 1,2")

    step("3. type=1 但有备注的 @openim 样本（反例）")
    probe_sql("select username,nickname,conRemark,type from rcontact "
              "where username like '%@openim' and type=1 and conRemark is not null and conRemark<>''")

    step("4. type=513 / type=5 的样本")
    probe_sql("select username,nickname,conRemark,type from rcontact "
              "where username like '%@openim' and type in (5,513)")

    step("5. 普通联系人里 type=1 的那 2 条是谁（基线反证）")
    probe_sql("select username,nickname,conRemark,type from rcontact "
              "where username not like '%@%' and type=1")

    step("6. rcontact 有哪些列（判据可用的候选字段）")
    probe_sql("pragma table_info(rcontact)")

    step("7. 拉日志")
    pull_dbg()
    grep(r"LABEL sql db=", 6)
    grep(r"LABEL\s+  ", 90)


if __name__ == "__main__":
    main()
