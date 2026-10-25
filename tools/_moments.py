"""场景：朋友圈定时刷新 / 自动加载 / 自动导出（moments.*）—— 需求 6.4

用法: python tools/_moments.py           开三项 → 进朋友圈 → 甩底 → 导出 → 查目录
      python tools/_moments.py --off      反向对照（全关，预期零新增文件）
      python tools/_moments.py --ls       只查导出目录
      python tools/_moments.py --img      图片对账探针（SNS 磁盘缓存直读链路）

✅ 这一组**可全自动**（不涉及发消息/花钱），导出目录的文件数就是硬判据，不用看截图。

判据链（按顺序读，缺一环就定位到哪一层断了）：
  1. `op=moments sub=stat` 有 `MOMENT stat` 行            → 模块加载了（钩子/轮询活着）
  2. `sub=env` 的 charging/wifi 至少一个为 true           → 环境判定拿得到（否则定时刷新永不触发）
  3. `sub=act` timeline=com.tencent...ImproveSnsTimelineUI → 时间线类已捕获（自动拉起的前提）
  4. `sub=load` 后 `MOMENT autoload grow a -> b`          → 自动加载真的拉到了更多
     （grow 来自 onBind 里 getData().size 变大的自观测，是最硬的信号）
  5. `sub=export` 后目录文件数增加                        → 文本导出落地
     ⚠️ 图片（原图）留存的**正解**是 SNS 磁盘缓存直读（R19 真机验通 `hit=6`），
     用 --img 跑 `sub=snsidx` + `sub=snsprobe` 对账。
     ⛔ 旧的 md5 候选路线（`sub=img` / `sub=imgtry`）**已于 R20 物理删除** ——
     三次真机证伪（R15 分桶 0.68% / R16 全盘 find 零结果 / R19 文件名是 20 位数字串不含 md5）。
     见 `docs/_r19_a4_probe.md` 与 `docs/_r20_a8_del.md`。

⏳ 定时刷新的「显式 Intent 重进时间线是否真拉服务端」也是待取证项：
   自动触发路径要等 60s 一拍 + 充电/WiFi，场景里用 `sub=refresh` 手动验证同一链路。
"""
import sys
import time

from _lib import *

OFF = "--off" in sys.argv
LS = "--ls" in sys.argv
IMG = "--img" in sys.argv
VAL = "false" if OFF else "true"
EXPORT_DIR = "/storage/emulated/0/Android/data/com.tencent.mm/files/WechatXposed/moments"


def count_exported():
    out = sh("shell", "find", EXPORT_DIR, "-type", "f")
    n = len([l for l in out.splitlines() if l.strip()])
    print("  导出文件数 = %d" % n)
    return n


def moments(sub, n=None):
    args = ["--es", "op", "moments", "--es", "sub", sub]
    if n is not None:
        args += ["--ei", "n", str(n)]
    bc(*args)


if LS:
    count_exported()
    raise SystemExit(0)

if IMG:
    # ⛔ R20（A8 收敛）：这里原来打的是 `sub=img`（旧 md5 候选对账），该探针已物理删除
    #    （见 docs/_r20_a8_del.md）。空调用会静默无输出，改成新链路的两条只读探针。
    step("图片对账：① 缓存索引能否建起来 ② mediaId 能否命中文件名")
    ensure_foreground()
    logclear()
    moments("snsidx")
    time.sleep(3)
    moments("snsprobe", 20)
    time.sleep(5)
    pull_dbg()
    print("--- 索引 ---")
    grep(r"MOMENT snsidx", 5)
    print("--- 逐条对账 ---")
    grep(r"MOMENT snsprobe", 30)
    print(
        "判据：names>0 才有得比；hit=<文件名> = 链路通；\n"
        "      ids=0 且 noMedia≈rows = mediaId 路径错（改 MomentsLogic.PROTO_FIELD_*）；\n"
        "      ⚠️ names=0 通常是微信冷启清空了 sns 缓存 —— 先进一次朋友圈上滑重建，不是链路坏了。"
    )
    raise SystemExit(0)

step("拉起微信 + 清日志")
ensure_foreground()
logclear()

step("开关 → %s" % VAL)
kvset("moments.auto_refresh", VAL)
kvset("moments.auto_load", VAL)
kvset("moments.auto_export", VAL)
kvset("moments.refresh_interval_h", "3")
time.sleep(2)

step("模块存活 + 环境判定")
moments("stat")
time.sleep(2)
moments("env")
time.sleep(2)

print("  导出前基线:")
n0 = count_exported()

step("进朋友圈（让钩子看见时间线 + 捕获 Activity 类）")
xml = dump()
tap_text("发现") or tap_xy(900, 3050)
time.sleep(4)
tap_text("朋友圈")
time.sleep(10)
print("  act:", top())

step("时间线捕获情况（定时刷新自动拉起的前提）")
moments("act")
time.sleep(2)

if not OFF:
    step("甩一次底（验证自动加载链路：预期 grow 日志）")
    moments("load")
    time.sleep(4)
    moments("stat")
    time.sleep(2)

    step("强制导出一轮（绕过 10 分钟最小间隔）")
    moments("export")
    time.sleep(8)

step("导出后：")
n1 = count_exported()
print("  新增 = %d" % (n1 - n0))

pull_dbg()
print("--- 日志 ---")
grep(r"MOMENT", 30)
print("done")
