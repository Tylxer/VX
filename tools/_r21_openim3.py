"""R21 问题2 取证（第三轮）：deleteFlag / 会话交叉 —— 判「谁真的是好友」

前两轮结论：
  @openim 254 → type=0:43(无备注) / type=1:68(12 条有备注) / type=4:139(全无备注) /
                type=5:2 / type=513:2
  verifyFlag **全 0** ⇒ 无区分力
  rcontact 有 deleteFlag / chatroomFlag / uiType / encryptUsername 等列

本轮目的：找**可执行**的好友判据。候选：
  A. deleteFlag=0 且曾在会话表出现
  B. 备注非空
  C. type 位域

⚠️ 只读取证。判据选型交 friend-src（Pro 档）。
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

    step("1. @openim 的 deleteFlag 分布")
    probe_sql("select type,deleteFlag,count(*) from rcontact where username like '%@openim' group by 1,2")

    step("2. 普通联系人 deleteFlag 基线")
    probe_sql("select type,deleteFlag,count(*) from rcontact where username not like '%@%' group by 1,2")

    step("3. chatroomFlag 分布（@openim）")
    probe_sql("select type,chatroomFlag,count(*) from rcontact where username like '%@openim' group by 1,2")

    step("4. 有备注的 @openim 都长啥样（全量 12 条的 type/deleteFlag）")
    probe_sql("select type,deleteFlag,username,conRemark from rcontact "
              "where username like '%@openim' and conRemark is not null and conRemark<>''")

    step("5. @openim 是否出现在 rconversation（有会话≈聊过天）")
    probe_sql("select count(*) from rconversation where username like '%@openim'")

    step("6. rconversation 表结构（确认列名）")
    probe_sql("pragma table_info(rconversation)")

    step("7. 拉日志")
    pull_dbg()
    grep(r"LABEL\s+  ", 90)


if __name__ == "__main__":
    main()
