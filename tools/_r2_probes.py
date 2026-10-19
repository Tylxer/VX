"""R2 第 3 步b：群名数据源 + 朋友圈原图 两组只读探针

用法: python tools/_r2_probes.py

探针清单（全部只读，除 grpname 会触发一次后台查库）：
  1. op=quiet sub=at sub2=grpstat   —— GroupNameSource 状态（ready/count/lastError）
  2. op=quiet sub=at sub2=grpname   —— 强制重装一次再打状态（后台线程查库）
  3. op=quiet sub=at sub2=grplist   —— 前 8 个群名样本（验 pick 取值）
  4. op=moments sub=img   --ei n 3  —— 朋友圈图片候选命中情况（看 picked 是不是全 NONE）
  5. op=moments sub=imgtry --ei n 3 —— 真复制 3 张试试（不依赖 COPY_IMAGES 开关）

⚠️ 值里带 [] "" 的必须把整条 am 命令作为一个字符串传（见项目记忆「设备 sh 引号坑」）。
   本脚本没有这种值，但统一用 bc_raw 风格更稳。
"""
import time

from _lib import *

RESTART_WAIT = 3


def probe(label, *extra):
    """发一条 PROBE 广播并等日志落盘"""
    print("\n" + "-" * 70)
    print(">>", label)
    bc(*extra)
    time.sleep(RESTART_WAIT)


def main():
    step("0. 拉起微信（后台广播不投递）")
    ensure_foreground()
    time.sleep(1)

    step("1. 群名数据源：当前状态")
    probe("grpstat", "--es", "op", "quiet", "--es", "sub", "at", "--es", "sub2", "grpstat")

    step("2. 群名数据源：强制预热一次")
    probe("grpname", "--es", "op", "quiet", "--es", "sub", "at", "--es", "sub2", "grpname")
    time.sleep(4)   # 后台线程查 23054 行 rcontact，给足时间

    step("3. 群名数据源：再打一次状态（看 ready/count）")
    probe("grpstat2", "--es", "op", "quiet", "--es", "sub", "at", "--es", "sub2", "grpstat")

    step("4. 群名样本（核对 conRemark/nickname）")
    probe("grplist", "--es", "op", "quiet", "--es", "sub", "at", "--es", "sub2", "grplist")

    step("5. 朋友圈原图候选命中（只读，不复制）")
    probe("moments img", "--es", "op", "moments", "--es", "sub", "img", "--ei", "n", "3")

    step("6. 朋友圈原图真复制 3 张（写模块自己的目录）")
    probe("moments imgtry", "--es", "op", "moments", "--es", "sub", "imgtry", "--ei", "n", "3")

    step("7. 拉日志")
    pull_dbg()
    for kw in ("GROUPNAME", "MOMENT", "SAFR", "error"):
        print("\n" + "=" * 70)
        print("### 关键词:", kw)
        grep(kw, n=45)


if __name__ == "__main__":
    main()
