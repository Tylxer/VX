"""R2 第 3 步d：SAF 链路验证

用法: python tools/_r2_saf.py

验证什么：
  1. HostContext.activity() 能不能拿到微信当前 Activity（拿不到 = SAF 一定弹不出来）
  2. ActivityResultRelay.launch() 能不能真把系统文件选择器弹出来
  3. onActivityResult 能不能把结果截回来（这一步需要**人工点一下选择器**）

⚠️ 路由注意：`sub=saf` 挂在 `backup()` 里，必须发 **ACTION_BACKUP**
   （`com.lx.wxgroup.action.BACKUP`），不是 ACTION_PROBE。
   发成 PROBE 会落到 `ENTRY PROBE unknown op=...`，看着像「探针没实现」。

⚠️ 第 5 步需要人操作：脚本会停下来等你点。点完（或按返回取消）再回车。
"""
import time

from _lib import *

APK = r"C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\wxgroup\app\build\outputs\apk\debug\app-debug.apk"
ACTION_BACKUP = "com.lx.wxgroup.action.BACKUP"


def bc_bk(*extra):
    """备份专用广播（走 ACTION_BACKUP 路由）"""
    sh("shell", "am", "broadcast", "-a", ACTION_BACKUP, *extra, "-p", "com.tencent.mm")


def focus():
    return sh("shell", "dumpsys window | grep mCurrentFocus").strip()


def main():
    step("1. 装新包 + 双冷启动")
    print(sh("install", "-r", APK))
    for _ in range(2):
        sh("shell", "am force-stop com.tencent.mm")
        time.sleep(2)
        sh("shell", "am start -n com.tencent.mm/.ui.LauncherUI")
        time.sleep(11)

    step("2. 唤醒屏幕 + 拉微信到前台")
    sh("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(1)
    sh("shell", "input", "keyevent", "KEYCODE_MENU")
    time.sleep(1)
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(4)
    print("focus:", focus())

    step("3. SAFR 状态（只读，不弹窗）")
    bc_bk("--es", "op", "saf")
    time.sleep(3)
    pull_dbg()
    grep("SAFR", n=10)

    step("4. 真发起一次系统的「保存文件」选择器")
    print("\n   >>> 接下来屏幕上应该弹出系统文件保存界面。")
    print("   >>> 弹出 = 链路通；没弹 = HostContext 或 startActivityForResult 有问题。\n")
    bc_bk("--es", "op", "saf", "--es", "sub2", "launch")
    time.sleep(6)
    print("focus after launch:", focus())
    pull_dbg()
    grep("SAFR probe", n=10)

    step("5. 人工确认")
    input("   看到系统文件保存界面了吗？按回车继续（随后自动取消）...")

    step("6. 取消：返回键关掉选择器 + 清挂起回调")
    sh("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(2)
    bc_bk("--es", "op", "saf", "--es", "sub2", "cancel")
    time.sleep(2)

    step("7. 最终日志（含 onActivityResult 是否截到）")
    pull_dbg()
    for kw in ("SAFR", "BK "):
        print("\n" + "=" * 70)
        print("### 关键词:", kw)
        grep(kw, n=20)


if __name__ == "__main__":
    main()
