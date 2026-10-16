"""场景：强制转发 —— 需求 6.6

用法:
  python tools/_forward.py [--type=文本|图片|视频|语音|文件|名片|位置|链接]
  python tools/_forward.py --reverse     主断言后自动做反向对照（关注入再长按一次）
  python tools/_forward.py --selftest    不走长按：op=forward pick 直接验收选择器→执行链

机制（v1 已按 PopupWindow 注入范式实现，与旧版注释不同）：
  注入点 = hook `PopupWindow.showAsDropDown/showAtLocation` 的 after（与 ConvMenuInject
  同一套），但必须先过 `ForwardLogic.classifyMenu` 确认是**聊天消息菜单**（CHAT_MSG）
  才注入 —— 挂错到会话列表是最贵误判，判据有单测锁住（ForwardLogicTest）。
  ⚠️ adb 长按触发不了微信菜单（被识别成点击），必须真手长按。

验收点：
  1. 一律追加、允许重复：菜单里出现两个「转发」（黑色原生 + 绿色模块，带「模块」角标）
  2. 点模块项 → 模块面板弹出「转发到多个」选择器（好友·群聊 / 模块分组两段）
  3. 选目标点确定 → 日志 `FORWARD plan` / `FORWARD done`；v1 发送链路未接通，
     toast 应明说「未实际发送」（待取证项 G1/G3，见报告）
  4. 反向对照：`op=forward sub=off` 后再长按 → 只剩 1 个「转发」

v1 已知边界（不是缺陷，是待取证，全部落在日志里）：
  - 原生转发锚点未取证（G1）→ 恒走降级分支
  - K8 发送能力项目里不存在（G3）→ 降级恒记 failed，不实际发送
  - 消息种类锚点未取证（G2）→ kind 恒 UNKNOWN，有文本才不 SKIP
  - 微信标签 Tab 未实现（G5）→ 选择器只有两段
"""
import sys
import time

from _lib import *

mtype = "文本"
reverse = "--reverse" in sys.argv
selftest = "--selftest" in sys.argv
for a in sys.argv[1:]:
    if a.startswith("--type="):
        mtype = a[7:]


def fprobe(sub):
    """发 op=forward 探针广播（微信在前台才投递，发完等 1s 落日志）"""
    bc("--es", "op", "forward", "--es", "sub", sub)
    time.sleep(1)


step("拉起微信 + 清日志")
ensure_foreground()
logclear()

step("探针基线：ForceForward 状态")
fprobe("stat")

if selftest:
    step("自检：跳过长按，用 op=forward pick 直接打开选择器（合成快照）")
    fprobe("pick")
    time.sleep(3)
    print("  act:", top())
    hits = ui("转发到多个")
    print("  → 选择器标题：%s" % ("出现 ✅" if hits else "未出现 ❌"))
    ui("已选")
    wait_manual("在选人器里勾选目标并点「确定」（好友缓存为空就先到通讯录滚一遍）", 20)
    pull_dbg()
    print("--- 日志 ---")
    grep(r"FF |FORWARD|forward", 25)
    print("done")
    raise SystemExit(0)

step("进一个聊天页（测 %s 类型）" % mtype)
xml = dump()
tap_text("会话") or tap_xy(180, 3050)
time.sleep(3)
tap_text("V", 0) or tap_xy(400, 400)
time.sleep(4)
print("  act:", top())

wait_manual("长按一条「%s」消息（约 1 秒），弹出菜单后不要点" % mtype, 10)

step("断言：菜单里有没有模块追加的「转发」（应与原生项并存）")
hits = ui("转发")
print("  命中 %d 个「转发」—— 期望 2（原生 + 模块）：%s"
      % (len(hits), "✅" if len(hits) == 2 else "❌（期望恰好 2，看看是不是多击了或菜单类型不对）"))
badge = ui("模块")
print("  「模块」角标：%s" % ("在 ✅" if badge else "不在 ❌"))

step("探针复检：injected 计数应 +1、lastKind=CHAT_MSG")
fprobe("stat")

if reverse:
    step("反向对照：关掉模块注入后再长按一次")
    fprobe("off")
    wait_manual("先点空白处关掉上一个菜单，再长按一条「%s」消息，弹出后不要点" % mtype, 12)
    hits2 = ui("转发")
    print("  命中 %d 个「转发」—— 期望 1（只剩原生）：%s"
          % (len(hits2), "✅" if len(hits2) == 1 else "❌"))
    fprobe("on")
    print("  （已恢复 op=forward sub=on）")

pull_dbg()
print("--- 日志 ---")
grep(r"FF |FORWARD|forward|CMI", 25)
print("done")
