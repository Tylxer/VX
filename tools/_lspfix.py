"""
修复「LSPosed 里模块 apk_path 陈旧」导致模块不注入的问题。

根因（实测）：这台设备上每次 `adb install -r` 都会把 APK 放到**新的随机目录**
`/data/app/~~<random>==/com.lx.wxgroup-<random>==/base.apk`，而 LSPosed 的
`modules_config.db` 里 `modules.apk_path` 不会自动跟着更新 → lspd 按旧路径
加载模块 APK，路径已不存在 → **模块完全不注入，logcat 里一条日志都没有**。

实测有效的解法：在 LSPosed 管理器里把模块的「启用模块」开关**关一次再开一次**，
让管理器重新写这一行（apk_path 会一起刷新）。

⚠️ 绝对不要直接改写 modules_config.db —— 它是 WAL 模式，只覆盖主 db 会留下
旧的 -wal，Android 端判定 "database disk image is malformed"，lspd 守护进程
崩溃后不再注入任何进程（这个坑以前踩过，极难查）。
"""
import re
import subprocess
import sys
import time

ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
MANAGER = "org.lsposed.manager"
MODULE = "com.lx.wxgroup"


def sh(*args):
    r = subprocess.run(
        [ADB] + list(args), capture_output=True, text=True, encoding="utf-8", errors="ignore"
    )
    return (r.stdout or "") + (r.stderr or "")


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/_lf.xml")
    return sh("shell", "cat", "/sdcard/_lf.xml")


def attr(node, name):
    m = re.search(name + r'="([^"]*)"', node)
    return m.group(1) if m else ""


def center(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def tap(x, y):
    sh("shell", "input", "tap", str(x), str(y))


def find_switch(xml):
    """「启用模块」那一行右边的 Switch"""
    for n in re.findall(r"<node[^>]*>", xml):
        if attr(n, "checkable") == "true":
            return n
    return None


def find_module_row(xml):
    for n in re.findall(r"<node[^>]*>", xml):
        if attr(n, "text") == "WXGroup" and attr(n, "clickable") == "true":
            return n
    # Compose 界面里文字节点可能是父节点，退而求其次找任意含该文本的 clickable 祖先
    for n in re.findall(r"<node[^>]*>", xml):
        if attr(n, "text") == "WXGroup":
            return n
    return None


def main():
    sh("shell", "monkey", "-p", MANAGER, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(5)

    xml = dump()
    if "启用模块" not in xml:
        row = find_module_row(xml)
        if row is None:
            print("FAIL: 管理器里找不到 WXGroup 模块行")
            return 1
        c = center(attr(row, "bounds"))
        if not c:
            print("FAIL: 模块行没有 bounds")
            return 1
        tap(*c)
        time.sleep(4)
        xml = dump()

    sw = find_switch(xml)
    if sw is None:
        print("FAIL: 找不到「启用模块」开关")
        return 1
    c = center(attr(sw, "bounds"))
    tap(*c)          # 关
    time.sleep(2.5)
    xml = dump()
    sw = find_switch(xml)
    if sw is None:
        print("FAIL: 关闭后找不到开关")
        return 1
    tap(*center(attr(sw, "bounds")))   # 开
    time.sleep(3.5)

    xml = dump()
    sw = find_switch(xml)
    ok = sw is not None and attr(sw, "checked") == "true"
    print("OK: 模块已启用" if ok else "WARN: 开关状态=%s" % (attr(sw, "checked") if sw else "n/a"))

    # 让微信冷启动重新加载模块
    sh("shell", "am", "force-stop", "com.tencent.mm")
    time.sleep(2)
    sh("shell", "am", "start", "-n", "com.tencent.mm/.ui.LauncherUI")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
