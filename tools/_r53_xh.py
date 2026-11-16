"""R53 竞品（微X）操作点观测：一条命令跑完一条探针。

存在理由：微X 的 APK 是 native 壳（业务代码在 assets 下 7 个熵 8.00 的密文里，
`classes.dex` 只剩 `WxCoreLoader` + `InMemoryDexClassLoader` 的装载逻辑），
静态反编译成本极高。它和我们在同一个微信进程里，所以改从宿主侧观测。

两条探针（都在 `HookListProbe`，纯只读，不 hook 不改数据）：

  prefs 用 Xposed 的 `XSharedPreferences` 读**微X 自己**的配置（它的 dex 里引用了这个类），
        顺带列微信 databases/、shared_prefs/、files/ 三个目录。
  hook  反射扫 `XposedBridge` 的全局 hook 表，列出**别的模块**已挂的 Member。
        默认只列 `com.fkzhang.*`（微X 包名根）。
  row   dump 会话列表 adapter 链 + 每个可见行的
        `getItemViewType / View 类 / tag 类 / item 类`。

用法：
    python tools/_r53_xh.py prefs
    python tools/_r53_xh.py hook
    python tools/_r53_xh.py hook com.tencent.mm.ui.conversation
    python tools/_r53_xh.py row

⛔ 前置：微信必须在前台（后台广播不投递，`am broadcast` 照样回 completed）。
   本脚本会先 `am start LauncherUI` 再发探针。
⛔ 日志 5000 行环形覆盖：脚本先 `logclear`，只抓 `XH ` 前缀的行。
"""
import os
import re
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
PKG = "com.lx.wxgroup"
HOST = "com.tencent.mm"
ENV = dict(os.environ, MSYS_NO_PATHCONV="1")


def adb(*args):
    r = subprocess.run([ADB] + list(args), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore", env=ENV)
    return (r.stdout or "") + (r.stderr or "")


def bc(*extras):
    """发广播。

    ⛔ 值必须再包一层单引号：设备端 `sh` 会二次解析，`(` 直接语法报错、`>` 被当输出重定向
    （广播照样回 result=0，但探针一行没跑，**没有任何报错**）。
    ⛔ 要包的是 `--es` 的**值**，不是 key：`am broadcast` 的语法是 `--es <key> <value>`，
    紧跟在 `--es` 后面的那个 token 是 key。之前写成 `cmd[i-1] == "--es"` 包错对象，
    只是当前几个值（xh/hook/row/包名）恰好没有特殊字符才没炸。
    ⇒ 这里改成「凡是不以 `-` 开头的 token 都是值」，一律包引号。
    """
    cmd = ["am", "broadcast", "-a", PKG + ".action.PROBE"]
    cmd += list(extras)
    cmd += ["-p", HOST]
    parts = []
    for tok in cmd:
        if tok.startswith("-"):
            parts.append(tok)
        else:
            parts.append("'" + tok.replace("'", "'\\''") + "'")
    return adb("shell", " ".join(parts))


def front():
    adb("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    time.sleep(5)


def run(sub, extra=None):
    front()
    bc("--es", "op", "logclear")
    time.sleep(1)
    args = ["--es", "op", "xh", "--es", "sub", sub]
    if extra:
        args += ["--es", "val", extra]
    bc(*args)
    time.sleep(3)
    adb("pull", DBG, "_cache/_r53_xh.txt")
    try:
        txt = open("_cache/_r53_xh.txt", encoding="utf-8", errors="ignore").read()
    except OSError:
        print("日志没拉下来：先确认 `adb devices` 有设备、微信在前台")
        return
    lines = [l for l in txt.splitlines() if " XH " in l]
    if not lines:
        print("没有 XH 输出。可能原因：模块没装/没重启生效；或探针抛异常")
        print("--- 日志尾部 ---")
        print("\n".join(txt.splitlines()[-8:]))
        return
    for l in lines:
        print(re.sub(r"^.*? XH ", "", l))


if __name__ == "__main__":
    sub = sys.argv[1] if len(sys.argv) > 1 else "hook"
    if sub == "prefsx":
        # 不经模块：LSPosed 必然有 root，直接读微X 自己的 prefs。
        # 比 sub=prefs 少一层不确定性（XSharedPreferences 在 Android 11+ 可能读不到）。
        # ⚠️ 只读，不改任何文件。
        print(adb("shell", "su -c 'ls -l /data/data/com.fkzhang.wechatxposed/shared_prefs'"))
        for name in ("com.fkzhang.wechatxposed_preferences.xml",):
            print(adb("shell", "su -c 'cat /data/data/com.fkzhang.wechatxposed/shared_prefs/%s'" % name))
        raise SystemExit(0)
    if sub == "all":
        # 设备回来后一条命令跑完三条探针（要求：APK 已装、微信已冷启两次、停在会话列表）
        for s in ("prefs", "hook", "row"):
            print("\n" + "=" * 26 + " " + s + " " + "=" * 26)
            run(s)
        raise SystemExit(0)
    run(sub, sys.argv[2] if len(sys.argv) > 2 else None)
