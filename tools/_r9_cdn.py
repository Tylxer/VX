#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""R9-P2 朋友圈图片 CDN 落盘验证 —— 验 docs/conclusions/moments_cdn.md 的 I-1 / I-2 / I-3 / I-4。

==============================================================================
⚠️ 本脚本依赖「模块侧尚未实现」的一组临时探针，见下面第 0 节。
   没有探针时脚本**不会失败**（I-1/I-2 会打 UNKNOWN 并告诉你缺什么），
   但它给出不了可用结论 —— 十二.7 是要真机结论的，探针必须先补。
==============================================================================

## 0. 需要模块侧新增什么探针（照抄给下一轮，字面量别改）

文件：`wxgroup/app/src/main/java/com/lx/wxgroup/probe/SnsCdnProbe.kt`（新建）
注册：`entry/EntryReceiver.kt` 的 `ACTION_PROBE` 分支加一条

    "cdn" -> {
        val sub = intent.getStringExtra("sub") ?: "stat"
        val v = intent.getStringExtra("val")
        val n = intent.getIntExtra("n", 20)
        // ⚠️ 凡是「读目录 / 拷文件」的一律走已有的 io { } 后台线程
        //    （BroadcastReceiver 的 onReceive 在主线程，列几千个文件会 ANR）
        io { com.lx.wxgroup.probe.SnsCdnProbe.command(sub, v, n) }
    }

| 广播（均为 op=cdn）                | 作用                              | 必须打印的**逐字**格式（LogX.i，脚本靠它匹配）        |
|---|---|---|
| `sub=hook val=on|off`              | 开/关 hook 集（ok0.x0#n / #onC2CDownloadCompleted / jf3.f#k / dk#a） | `CDN hook on parts=n,cb,k,dk` / `CDN hook off` |
| `sub=dk`                           | ★I-1：真调 `dk.a()` 并打**返回值**  | `CDN dk ret=<true|false> nativeOk=<true|false> staticJ=<true|false>`<br>（nativeOk = 反射 `CdnLogic.allowBatchImageDownload()`；reflect 失败打 `unknown`） |
| `sub=force val=true|false|off`     | ★I-2：`dk.a()` before 里 `result=true` | `CDN force <val>` |
| `sub=cbon val=on|off`              | 记录 `ok0.x0#onC2CDownloadCompleted` 回调 | `CDN cbon on` / `CDN cbon off` |
| `sub=reqmp val=on|off`             | 记录 `ok0.x0#n` 入参（批量请求全量） | `CDN req feedId=<id> n=<k> img=<imageCachePath> key=<fileKey>` |
| `sub=paths`                        | 打印**已观测到**的落盘目录          | `CDN paths pic=<C2CDownloadResult.picCachePath 的父目录，无则 none>`<br>`CDN path <dir> files=<n> bytes=<sum>` |
| `sub=ls val=<dir>`                 | ★用**微信进程权限**列目录（/data/data 私有目录只能靠它） | `CDN ls <dir> files=<n> bytes=<sum>`<br>`CDN f <file> size=<bytes>`（最多 n 行） |
| `sub=export val=<dir> n=<N>`       | ★把 <dir> 下最近 N 个文件拷到 `/sdcard/Android/data/com.tencent.mm/cache/r9cdn/`<br>（模块跑在微信进程里，有私有目录读权限；拷出来脚本才能 pull + 验 magic） | `CDN export <dst> src=<src> size=<bytes>` |
| `sub=decode val=<file>`            | ★I-4 反证：模块侧 `BitmapFactory.decodeFile`（inJustDecodeBounds）判明文 | `CDN decode <file> w=<n> h=<n> ok=<0|1>`（ok=1 且 w>0 ⇒ 明文；探针未实现则脚本打 UNKNOWN(需探针)） |
| `sub=imgpath val=<dir>|off`        | ★I-3：hook `jf3.f#k` 改写 `BatchSnsReqImageData.imageCachePath` | `CDN imgpath <dir>` / `CDN imgpath off` |
| `sub=stat`                         | 探针自身状态（**2026-10-20 加 proc/pid 两个字段**，供 I-0 判进程归属） | `CDN stat hooked=<0|1> proc=<进程名> pid=<n> hitK=<n> hitCB=<n> last=<iso>` |
| `sub=reset`                        | 清探针计数器 + `SnsCdnImage` 熔断（**改判据前后必须清**，否则前后差值无意义） | `CDN reset ok` |
| `sub=img`                          | `SnsCdnImage` 成品侧状态（H1/H3 是否装上、熔断态、回调计数） | `CDN img on=<0|1> parts=<n,cb,...> streak=<n> tripped=<0|1> cb=<n> err=<n> nopath=<n> req=<n>`<br>（`SnsCdnImage` 未装时打 `CDN img off`） |

约定：日志前缀统一 `CDN `（**改了脚本就抓不到**），落
`/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt`，logcat tag `WXGroup`。

参数口径（照 `EntryReceiver.kt` 现有取值）：整数参数（`n`）走 adb `--ei`（模块侧 `getIntExtra`），
字符串参数（`sub`/`val`）走 `--es`（`getStringExtra`）。
`sub=ls` **必须先打** `CDN ls <dir> files= bytes=` 汇总行、**再打** `CDN f` 明细行 ——
脚本只取**最后一条汇总行**作 files/bytes 主数据、汇总行**之后**的 `CDN f` 作本次文件名（这样才不受累积日志影响）。

## 1. 用法

    python tools/_r9_cdn.py --help
    python tools/_r9_cdn.py                         # 默认的**只读**跑：不写任何开关
    python tools/_r9_cdn.py --apply --force         # 真把批量开关强制 true，验 I-2 增量
    python tools/_r9_cdn.py --apply --force --i3    # 再加 I-3（改写 imageCachePath）
    python tools/_r9_cdn.py --dry-run               # 只打计划，**一条 adb 都不发**

## 2. 已知坑（照 tools/_r8_rows.py / _r6_kv.py 的范式，别改回去）

1. **adb 不在 PATH**，必须绝对路径；Bash 里直接调会反复重启 daemon
   ⇒ 一律 python `subprocess` 调 `tools/../platform-tools/adb.exe`。
2. 朋友圈入口 `ImproveSnsTimelineUI` **不可 export**，`am start -n` 会静默停在
   `LauncherUI` ⇒ 必须走 UI 点击（发现 → 朋友圈），见 `ensure_foreground()`。
3. **微信在后台不投递广播** ⇒ 发探针前先 `am start` 拉前台并校验 `topResumedActivity`。
4. 整条 `am` 命令要作为**一个字符串**传给设备 `sh`（列表传参会被二次解析到空）；
   值一律**单引号**包。kvset 的参数名是 `val` 不是 `value`。
5. 落盘日志 5000 行环形覆盖 ⇒ 测前先 `op=logclear`。
6. `/data/data/com.tencent.mm` adb shell **读不到**（且微信非 debuggable，
   `run-as` 也大概率拒绝）⇒ 列目录优先走模块 `sub=ls`，magic bytes 优先走 `sub=export`。
"""
import argparse
import os
import re
import subprocess
import sys
import time

# ---------------------------------------------------------------- 常量
ADB = r"C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PKG = "com.tencent.mm"
# 主进程名（与 core/VersionGate.kt:18 HOST_MAIN_PROCESS 一致）。I-0 判据用它。
MAIN_PROC = "com.tencent.mm"
ACTION = "com.lx.wxgroup.action.PROBE"
DBG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
LOCAL_DBG = os.path.join(ROOT, "_dbg_pull.txt")            # 沿用 _r8_rows.py:44
TMPDIR = os.path.join(ROOT, "_cache", "r9_cdn")            # pull 下来验 magic 的临时目录

SNS_UI = "ImproveSnsTimelineUI"                            # 朋友圈页（不可 export，只能点）
SWIPE = ("720", "2200", "720", "900", "320")               # 抄 _r7_install.py:96

# 落盘目录候选（⑫.7-I-2 说的 "picCachePath"）。真值以 `op=cdn sub=paths` 打出来的为准，
# 这里只是「探针还没上时脚本也能自己找一圈」的兜底清单。
#
# ⚠️ B-7：这份清单只放**图片缓存的具体子目录**，⛔ 绝不放父目录。
#    父目录（如 `.../cache`）一旦被选中，I-2 会把整个微信缓存（表情/语音/视频/其他业务的图）
#    的文件数算进差值 ⇒ 差值与「朋友圈图片是否落盘」无关 ⇒ 必然假阳性。
# ⛔ 也绝不放 `.../cache/r9cdn`：那是**我们自己** `sub=export` 拷出来的落点，
#    把它算进 I-2 等于「自己写文件、自己证明微信落了盘」，是最恶劣的循环论证。
DIR_CANDIDATES = [
    "/data/data/com.tencent.mm/cache/sns",
    "/data/user/0/com.tencent.mm/cache/sns",
    "/data/data/com.tencent.mm/cache/snsimage",
    "/sdcard/Android/data/com.tencent.mm/cache/sns",
    "/sdcard/tencent/MicroMsg/sns",
]

# ⛔ 父目录/自产目录黑名单（双保险：即使有人往 DIR_CANDIDATES 里加了，resolve_dir 也会拒）
DIR_FORBIDDEN = (
    "/data/data/com.tencent.mm/cache",
    "/data/user/0/com.tencent.mm/cache",
    "/sdcard/Android/data/com.tencent.mm/cache",
    "/data/data/com.tencent.mm",
    "/sdcard",
    "/data",
)
DIR_FORBIDDEN_PREFIX = ("/data/data/com.tencent.mm/cache/r9cdn", "/data/data/com.tencent.mm/cache/r9i3")

# magic bytes 判据（B-6：补 bmp/heic；webp 必须同时校验 RIFF + offset8 处 WEBP）
MAGIC = [
    ("ffd8ff", "jpeg"),
    ("89504e47", "png"),
    ("47494638", "gif"),
    ("424d", "bmp"),
    ("0000001866747970", "heic"),                 # ftyp 开头，brand 进一步看 heic/heix/mif1
    ("0000002066747970", "heic"),
]
# webp 需要 12 字节：RIFF + 第 8~12 字节 == "WEBP"（只判 RIFF 会把 WAV 误判成 webp）
HEIC_BRANDS = ("heic", "heix", "mif1", "hevc", "hevx", "msf1")

# ---------------------------------------------------------------- adb 封装
def sh(*a, t=120):
    """⚠️ 一律 python subprocess 调 adb（Bash 里调会重启 daemon）。"""
    r = subprocess.run([ADB] + list(a), capture_output=True, text=True,
                       encoding="utf-8", errors="ignore", timeout=t)
    return ((r.stdout or "") + (r.stderr or "")).strip()


def sh_raw(*a, t=120):
    """原始 bytes（magic bytes 要走这条，text=True 会把二进制吃成乱码）。"""
    r = subprocess.run([ADB] + list(a), capture_output=True, timeout=t)
    return r.stdout or b""


def bcast(op, **kw):
    """⚠️ 整条 am 作为一个字符串发给设备 sh，值一律单引号包（见头部坑 4）。

    参数按类型选择 adb 开关（与 EntryReceiver.kt 取值方式对齐）：
      int  → --ei（模块 getIntExtra）
      bool → --ez（模块 getBooleanExtra）
      str  → --es（模块 getStringExtra）
    """
    s = "am broadcast -a %s --es op '%s'" % (ACTION, op)
    for k, v in kw.items():
        if isinstance(v, bool):
            s += " --ez %s %s" % (k, "true" if v else "false")
        elif isinstance(v, int):
            s += " --ei %s %d" % (k, v)
        else:
            s += " --es %s '%s'" % (k, v)
    return sh("shell", s + " -p " + PKG, t=60)


def cdn(sub, val=None, n=None):
    """给模块侧 op=cdn 探针发命令（探针没上时日志里不会有 `CDN ` 行，脚本会报 UNKNOWN）。"""
    kw = {"sub": sub}
    if val is not None:
        kw["val"] = val
    if n is not None:
        kw["n"] = n
    return bcast("cdn", **kw)


# ---------------------------------------------------------------- UI 导航（抄 _r8_rows.py）
def top():
    out = sh("shell", "dumpsys activity activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{[^ ]+ u0 ([^ ]+)", out)
    return m.group(1) if m else "?"


def dump_ui():
    sh("shell", "uiautomator dump /sdcard/_nav.xml")
    return sh("shell", "cat /sdcard/_nav.xml")


def tap_text(xml, text, idx=0):
    hits = []
    for n in re.findall(r"<node[^>]*>", xml or ""):
        for name in ("text", "content-desc"):
            m = re.search(name + r'="([^"]*)"', n)
            if not m or m.group(1) != text:
                continue
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
            if b:
                x1, y1, x2, y2 = (int(v) for v in b.groups())
                hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
            break
    if not hits or idx >= len(hits):
        return False
    sh("shell", "input tap %d %d" % hits[idx])
    return True


def ensure_foreground(nav=True):
    """⚠️ 微信在后台不投递广播 —— 必须先拉前台。"""
    sh("shell", "am start -n %s/.ui.LauncherUI" % PKG)
    time.sleep(3)
    t = top()
    if SNS_UI in t or not nav:
        return t
    xml = dump_ui()
    if not tap_text(xml, "发现"):
        sh("shell", "input tap 900 3050")          # 底栏第 3 个 tab 的兜底坐标
    time.sleep(4)
    tap_text(dump_ui(), "朋友圈")
    time.sleep(9)
    return top()


def scroll(n):
    for _ in range(max(0, n)):
        sh("shell", "input swipe " + " ".join(SWIPE))
        time.sleep(1.1)


# ---------------------------------------------------------------- 日志
def pull():
    sh("pull", DBG, LOCAL_DBG)
    try:
        with open(LOCAL_DBG, encoding="utf-8", errors="ignore") as f:
            return f.read().splitlines()
    except OSError:
        return []


def logclear():
    return bcast("logclear")


def last_cdn(lines, key):
    """捞最后一条以 `CDN <key>` 开头的日志（没有返回 None + 提示）。

    ⚠️ key 是**裸 key**（如 `"ls "` / `"force"` / `"imgpath"` / `"dk ret="`），
       函数内部自行拼 `CDN ` 前缀 —— 调用方不要再传 `"CDN ..."`。
    """
    for i in range(len(lines) - 1, -1, -1):
        if lines[i].startswith("CDN " + key) or ("CDN " + key) in lines[i]:
            return lines[i]
    return None



# ---------------------------------------------------------------- 目录快照
def _parse_ls(out):
    """解析 toybox `ls -l`：`-rw------- 1 u0_a1 u0_a1 <size> 2026-10-20 10:00 <name>`。

    ⚠️ size 在**第 5 个**字段（不是最后一个）；目录行首字符是 d，跳过；
       名字可能带空格，所以从第 8 个字段开始 join 回去。
    """
    ent = []
    for ln in (out or "").splitlines():
        m = re.match(r"^\s*(\S)\S*\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\S+\s+\S+\s+(.+)$", ln)
        if not m or m.group(1) == "d":
            continue
        ent.append((m.group(3).strip(), int(m.group(2))))
    return ent


def dir_scan_shell(d):
    """口径 A：adb shell 直接 ls（/data/data 大概率被拒）。"""
    out = sh("shell", "ls -l '%s' 2>/dev/null" % d, t=60)
    ok = bool(out) and "Permission denied" not in out and "No such file" not in out
    return (ok, [(os.path.join(d, n), s) for n, s in _parse_ls(out)]) if ok else (False, [])


def dir_scan_runas(d):
    """口径 B：`run-as com.tencent.mm`（微信非 debuggable ⇒ 通常 NG，但要诚实报出来）。"""
    out = sh("shell", "run-as %s ls -l '%s' 2>/dev/null" % (PKG, d), t=60)
    ok = bool(out) and "Permission denied" not in out and "not debuggable" not in out
    if not ok:
        return (False, [])
    ent = [(n if n.startswith("/") else os.path.join(d, n), s) for n, s in _parse_ls(out)]
    return (True, ent)


def dir_scan_module(d, n=200):
    """口径 C：模块侧 `op=cdn sub=ls`（跑在微信进程里，私有目录也能列）—— **唯一靠谱的那条**。

    格式：`CDN ls <dir> files=<n> bytes=<sum>` + 每个文件一行 `CDN f <file> size=<bytes>`

    ⚠️ B-1 修复：files/bytes 主数据取**最后一条汇总行**（`CDN ls ... files= bytes=`），
       `CDN f` 明细只取**该汇总行之后**的那些（本次命令新产生的），只用于拿文件名。
       这样不受累积日志影响 —— 目录没变化时 diff 就是 0，不会再误报「可落盘」。

    返回 (ok, ent, files, bytes)：ent 是 (name, size) 列表（名字来自 CDN f），
    files/bytes 是**汇总行**的权威数值（不靠数 CDN f 行，也不靠累积日志）。
    """
    cdn("ls", val=d, n=n)
    time.sleep(1.5)
    lines = pull()
    sum_idx = None
    # ⚠️ **不能用 startswith**（2026-10-24 真机踩到）：日志行实际是
    #    `01:07:31.842 Thread-140 CDN ls /data/... files=0 bytes=0`，
    #    前面有时间+线程前缀 ⇒ `startswith("CDN ls ")` 恒 False ⇒ sum_idx 恒 None
    #    ⇒ 又被上面那个 4 元组 return 变成 ValueError，双重复合故障。
    #    跟 `last_cdn()` 一样用**子串匹配**（那边写对了，这边写错了）。
    for i in range(len(lines) - 1, -1, -1):
        if "CDN ls " in lines[i]:
            sum_idx = i
            break
    # ⚠️ **三个 return 的元素个数必须一致**（2026-10-24 真机踩到）：
    #    早期分支返回 4 元组而调用处按 5 个解包 ⇒ `ValueError: not enough values to unpack`，
    #    把真正的原因（模块根本没打出 `CDN ls` 汇总行）掩盖成一句看不懂的 ValueError。
    #    ⇒ 协议改动时**所有 return 一起改**，别只改成功的那条。
    if sum_idx is None:
        return (False, [], 0, 0, "")        # 日志里没有 `CDN ls ` 汇总行
    m_sum = re.search(r"files=(\d+) bytes=(\d+)", lines[sum_idx])
    if not m_sum:
        return (False, [], 0, 0, lines[sum_idx].strip())
    files = int(m_sum.group(1))
    bytes_sum = int(m_sum.group(2))
    ent = []
    for ln in lines[sum_idx + 1:]:
        m = re.search(r"CDN f (.+?) size=(\d+)", ln)   # 同上：不能用 `^` 锚行首
        if m:
            ent.append((m.group(1).strip(), int(m.group(2))))
    return (True, ent, files, bytes_sum, lines[sum_idx].strip())


SNAP_EMPTY = {"method": "-", "files": 0, "bytes": 0, "names": [], "ev": "-"}


def snapshot(d, n=200):
    """对一个目录做一次快照，返回 {method, files, bytes, names, ev}。

    依次试 模块 ls / shell ls / run-as ls，**并报告哪种口径可行**（⑫.7-I-2 要的口径结论）。
    ev = 取数口径 + 汇总行原文（供 verdict 里 I-2 的证据列，见 B-12）。
    """
    for method, fn in (("module:op=cdn sub=ls", dir_scan_module),
                       ("shell:ls", dir_scan_shell),
                       ("shell:run-as", dir_scan_runas)):
        try:
            if fn is dir_scan_module:
                ok, ent, files, bytes_sum, sumline = fn(d, n)
            else:
                ok, ent = fn(d)
                files, bytes_sum, sumline = len(ent), sum(s for _, s in ent), ""
        except Exception as e:                      # 超时/设备掉线不该让用户看 traceback
            # ⚠️ 只打 `e.__class__.__name__` 是**没有信息量**的（2026-10-24 真机踩到）：
            #    打了 5 行「ValueError」却不知道哪个 int() 炸的，只能靠猜。
            #    必须带上消息本体和目录，否则等于没报错。
            print("    %s 异常：%s: %s  (dir=%s)" % (method, e.__class__.__name__, e, d))
            continue
        if ok:
            names = sorted([p for p, _ in ent])
            ev = "%s" % method + ((" | " + sumline) if sumline else "")
            return {"method": method, "files": files,
                    "bytes": bytes_sum, "names": names, "ev": ev}
    return dict(SNAP_EMPTY)


def probe_access(d):
    """只判「哪种口径能读」，不列内容（速度快，用来给用户报口径）。"""
    ok_methods = []
    if dir_scan_shell(d)[0]:
        ok_methods.append("shell:ls")
    if dir_scan_runas(d)[0]:
        ok_methods.append("shell:run-as")
    return ok_methods or ["全部失败（需模块 op=cdn sub=ls）"]


# ---------------------------------------------------------------- magic bytes（I-4）
def head_bytes_device(path, k=16):
    """设备侧取头 k 字节：先用 `od`，没有就用 `xxd`。返回 (口径, bytes|None)。"""
    out = sh("shell", "od -An -tx1 -N %d '%s' 2>/dev/null" % (k, path), t=60)
    if not out:
        out = sh("shell", "xxd -l %d -p '%s' 2>/dev/null" % (k, path), t=60)
        if out:
            return ("device:xxd", bytes.fromhex(out.replace(" ", "")))
        return ("none", None)
    try:
        return ("device:od", bytes.fromhex(re.sub(r"\s+", "", out)))
    except ValueError:
        return ("none", None)


def head_bytes(path, k=16):
    """取文件头 k 字节：优先 pull 到本地读（最准），逐步降级到设备侧 od。

    顺序：adb pull → exec-out cat → exec-out run-as cat → device od
    ⚠️ B-10 修复：取字节前**先删本地临时文件**，避免 pull 失败时读到上次的旧文件造成 magic 假阳性。
    """
    os.makedirs(TMPDIR, exist_ok=True)
    local = os.path.join(TMPDIR, re.sub(r"[^0-9A-Za-z._-]", "_", os.path.basename(path)) or "x")
    if os.path.exists(local):
        try:
            os.remove(local)
        except OSError:
            pass
    subprocess.run([ADB, "pull", path, local], capture_output=True, text=True,
                   encoding="utf-8", errors="ignore", timeout=120)
    if os.path.exists(local) and os.path.getsize(local) > 0:
        with open(local, "rb") as f:
            return ("adb pull", f.read(k))
    # ⚠️ B-11 修复：`exec-out` 的参数直接 execve，**不经 shell** ⇒ 单引号不会被剥离。
    #    旧写法传 "'%s'" % path，设备上实际执行 `cat "'/x/y.jpg'"`（带字面引号）⇒ 必然失败、
    #    且两条降级路径**静默失效**。这里必须传**裸路径**。
    #    （只有走 `sh -c` 真正经过 shell 时才需要引号。）
    raw = sh_raw("exec-out", "cat", path, t=60)
    if raw:
        return ("exec-out cat", raw[:k])
    raw = sh_raw("exec-out", "run-as", PKG, "cat", path, t=60)
    if raw and b"not debuggable" not in raw and b"Permission denied" not in raw:
        return ("exec-out run-as cat", raw[:k])
    return head_bytes_device(path, k)


def magic_of(head):
    h = (head or b"").hex()
    for pref, name in MAGIC:
        if h.startswith(pref):
            if name == "heic":
                # ftyp 的 brand 是 bytes 8~12，得进一步核对才算 heic
                brand = (head or b"")[8:12].decode("latin-1", "ignore")
                if brand not in HEIC_BRANDS:
                    continue
            return (name, h)
    # webp 需单独判：RIFF + offset8 == "WEBP"
    if h.startswith("52494646") and len(head or b"") >= 12 and (head or b"")[8:12] == b"WEBP":
        return ("webp", h)
    return ("unknown/可能仍加密", h)


# ---------------------------------------------------------------- I-0 / I-1 / I-2 / I-3 / I-4
def run_i0(apply_mode=False):
    """I-0：`ok0/x0`（CdnTransportEngine）跑在哪个进程 —— 全方案的**前置生死项**。

    ⚠️ **只读模式下 `hooked=0` 是预期值，不是 FAIL**（2026-10-24 真机踩到）：
    未加 `--apply` 时脚本**故意不发** `sub=hook val=on`，所以钩子必然没装。
    旧版把这种 `hooked=0` 一律判 FAIL 并提示「findClass 失败」，还连带用
    「I-0 未过 ⇒ I-1~I-4 不可采信」把 I-1 的**有效**结论（`dk.a()` 是纯反射，
    不依赖任何 hook）一起废掉 —— 白跑一轮。
    ⇒ 未 --apply 时判 **SKIP**，只有「已 apply 却仍 hooked=0」才判 FAIL。


    `HookEntry` 只在 `processName == "com.tencent.mm"` 装钩子（`VersionGate.isMainProcess`）。
    若 CDN 回调实际发生在别的进程（`:push` / `:tools` / `:appbrand` …），H3 永远不触发，
    后面 I-1~I-4 全部无从谈起。

    ⚠️ 判据的**局限**（必须如实报告，别急着下结论）：
      探针 `sub=stat` 跑在**广播被投递的那个进程**里，而广播由 `am broadcast -p com.tencent.mm`
      发出，通常落在主进程 ⇒ 这个 `proc` 字段**只能证明「广播到达主进程」**，
      **不能证明「CDN 回调也在主进程」**。
      ⇒ 真判据是「`hitK` / `hitCB` 有没有涨」：涨了 = 回调确实在装钩子的进程里。
      `proc` 字段的价值是**排除误判**：若 `proc` 不是主进程，说明连广播都没到主进程，
      那么 `hooked=1` 本身就不可信。
    """
    cdn("stat")
    time.sleep(1.5)
    lines = pull()
    hit = last_cdn(lines, "stat hooked=")
    if not hit:
        return ("UNKNOWN", "日志无 `CDN stat` ⇒ 探针未装/版本旧（见文件头第 0 节）")

    m_proc = re.search(r"proc=(\S+)", hit)
    m_hook = re.search(r"hooked=(\d)", hit)
    proc = m_proc.group(1) if m_proc else "unknown"
    hooked = m_hook.group(1) if m_hook else "?"

    ev = hit.strip()
    if hooked != "1":
        if not apply_mode:
            return ("SKIP", "%s ⇒ 只读模式未装 hook（hooked=0 是**预期**的）；"
                            "要观测回调增量须加 --apply。**这不代表类名/进程有问题**" % ev)
        return ("FAIL", "%s ⇒ 已 --apply 却仍 hooked=0，才是真的装钩失败，"
                        "先看 note 字段/类名是否变（findClass 失败）" % ev)
    if proc != MAIN_PROC:
        return ("FAIL", "%s ⇒ 广播落在了 %s 而非主进程，hooked/计数都不可信" % (ev, proc))
    return ("PASS", "%s ⇒ 广播到达主进程且钩子已装（回调是否也在主进程，靠 hitK/hitCB 增量为准）" % ev)


def run_i0_after_scroll(before_hitk, before_hitcb):
    """滚屏后用命中计数判「CDN 回调确实落在装钩子的进程里」—— I-0 的**真正判据**。"""
    cdn("stat")
    time.sleep(1.5)
    lines = pull()
    hit = last_cdn(lines, "stat hooked=")
    if not hit:
        return ("UNKNOWN", "滚屏后无 `CDN stat`")
    k = re.search(r"hitK=(\d+)", hit)
    cb = re.search(r"hitCB=(\d+)", hit)
    dk_now = int(k.group(1)) if k else 0
    dcb_now = int(cb.group(1)) if cb else 0
    dk_delta = dk_now - before_hitk
    dcb_delta = dcb_now - before_hitcb
    ev = "%s（增量 hitK=+%d hitCB=+%d）" % (hit.strip(), dk_delta, dcb_delta)
    if dk_delta > 0 or dcb_delta > 0:
        return ("PASS", ev + " ⇒ CDN 回调确实在装钩子的进程里，I-0 成立")
    return ("FAIL", ev + " ⇒ 零回调：可能是进程不对，也可能只是没触发下载，需结合 sub=img/日志判断")


def run_i1():
    """I-1：`dk.a()` 的运行时返回值。

    ⚠️ **必须**模块侧新增 `op=cdn sub=dk` 打印 `CDN dk ret=<bool>` —— 原因见 ⑫.3：
       `allowBatchImageDownload()` 是 native（⑫.3），只有让它「真跑一次并打印」才拿得到返回值。
    """
    cdn("dk")
    time.sleep(1.5)
    lines = pull()
    hit = last_cdn(lines, "dk ret=")
    if not hit:
        return ("UNKNOWN", "日志无 `CDN dk ret=` ⇒ 模块侧缺 op=cdn sub=dk（见文件头第 0 节）")
    m = re.search(r"ret=(true|false)", hit)
    return (m.group(1) if m else "UNKNOWN", hit.strip())


def _dir_forbidden(d):
    """B-7：判断候选目录是否为「父目录 / 我们自己产的目录」⇒ 一律不得用于 I-2。"""
    dd = d.rstrip("/")
    if dd in tuple(x.rstrip("/") for x in DIR_FORBIDDEN):
        return "父目录（会把整个缓存算进差值）"
    for pre in DIR_FORBIDDEN_PREFIX:
        if dd.startswith(pre.rstrip("/")):
            return "自产目录（export/imgpath 落点，循环论证）"
    return None


def resolve_dir(candidates):
    """决定 I-2 看哪个目录。

    优先级：① 模块 `op=cdn sub=paths` 报的 picCachePath（真值）；
            ② 候选清单里「可读 **且** files>0」的那个。

    ⚠️ B-7 修复（旧版两个假阳性来源）：
      1. 旧版只要 `method != "-"` 就选，**0 个文件也选** ⇒ 差值是 0→0，看起来「没新增」，
         也可能把空目录当有效目录，结论无从归因。现在要求 `files > 0` 才有资格。
      2. 旧版候选里含父目录 `.../cache` 与我们自己的 `.../r9cdn` ⇒ 前者把整个微信缓存算进来、
         后者是**我们拷出去的文件**，两者都会让 I-2 得出与事实相反的结论。现在由 `_dir_forbidden` 硬拒。
    返回 (dir, 说明)；说明里会列出**每个候选的入选/排除原因**，供 verdict 打印、便于人工复核。
    """
    notes = []
    cdn("paths")
    time.sleep(1.5)
    hit = last_cdn(pull(), "paths ")
    if hit:
        m = re.search(r"pic=(\S+)", hit)
        if m and m.group(1) != "none":
            d = m.group(1)
            why = _dir_forbidden(d)
            if why:
                notes.append("%s ⇒ 拒（%s）" % (d, why))
            else:
                # B-7：模块 paths 报出的「真值」也必须 files>0 才有资格，空目录一样不给结论
                s = snapshot(d)
                if s["method"] != "-" and s["files"] > 0:
                    notes.append("%s ⇒ 选（模块 CDN paths，files=%d，口径 %s）"
                                 % (d, s["files"], s["method"]))
                    return (d, "模块 CDN paths", notes)
                notes.append("%s ⇒ 拒（模块 paths 报出但 %s）" % (
                    d, "三种口径读不到" if s["method"] == "-" else "files=0"))
        else:
            notes.append("模块 paths: pic=none")
    else:
        notes.append("模块 paths: 无 CDN paths 行（探针未装或未命中）")

    for d in candidates:
        why = _dir_forbidden(d)
        if why:
            notes.append("%s ⇒ 拒（%s）" % (d, why))
            continue
        s = snapshot(d)
        if s["method"] == "-":
            notes.append("%s ⇒ 拒（三种口径都读不到）" % d)
            continue
        if s["files"] <= 0:
            # 空目录不算「可读即可用」——I-2 要的是增量，空目录给不出任何证据
            notes.append("%s ⇒ 拒（可读但 files=0，口径 %s）" % (d, s["method"]))
            continue
        notes.append("%s ⇒ 选（files=%d，口径 %s）" % (d, s["files"], s["method"]))
        return (d, "候选探测（method=%s）" % s["method"], notes)

    notes.append("结论：候选全部被排除，I-2 无法归因 ⇒ 应打 UNKNOWN 而不是硬选一个")
    return (None, "候选均空/被排除", notes)


def do_force(on):
    """I-2 的开关：hook `dk.a()` 强制返回 true（⑫.5 说的两道门一起绕）。"""
    cdn("force", val="true" if on else "off")
    time.sleep(1.0)
    return last_cdn(pull(), "force") or "(未返回 CDN force 行 ⇒ 模块侧缺 sub=force)"


def run_i3(target):
    """I-3：改 `BatchSnsReqImageData.imageCachePath` 到 <target>，看那里有没有新文件。"""
    sh("shell", "mkdir -p '%s'" % target, t=60)
    cdn("imgpath", val=target)
    time.sleep(1.0)
    setline = last_cdn(pull(), "imgpath") or "(无 CDN imgpath 行 ⇒ 模块侧缺 sub=imgpath)"
    before = snapshot(target)
    return (setline, before)


def pick_samples(after, before, n):
    """挑「新增文件」做 magic 采样。

    ⛔ B-3 修复：**无新增 ⇒ 返回空列表**，绝不回退老文件 ——
       把上次就存在的旧图当「本次落盘证据」是必然假阳性。老文件只有在调用方
       显式传 --allow-stale 时才允许看（且 verdict 标注「非本次新增」）。
    """
    new = [x for x in after["names"] if x not in set(before["names"])]
    return new[:n]


def run_i4(target_dir, samples, allow_export=False):
    """I-4：magic bytes + decodeFile 反证判定。

    ⚠️ 私有目录 pull 不下来 ⇒ 先让模块 `op=cdn sub=export` 拷到可读 cache 目录，再取字节。
       export 会写 /sdcard ⇒ 必须在 --apply 下才允许（B-4）。
    """
    outs = []
    exported = {}
    if samples and allow_export:
        cdn("export", val=target_dir, n=len(samples))
        time.sleep(2.0)
        for ln in pull():
            m = re.match(r"^CDN export (\S+) src=(\S+) size=(\d+)\s*$", ln)
            if m:
                exported[m.group(2)] = m.group(1)
    for s in samples:
        path = exported.get(s, s)
        try:
            method, head = head_bytes(path)
        except Exception as e:
            outs.append({"file": s, "hex": "-", "kind": "?", "method": "异常 %s" % e.__class__.__name__,
                         "decode": "UNKNOWN(取字节异常)"})
            continue
        kind, hx = magic_of(head)
        decode = _decode_probe(path)
        outs.append({"file": os.path.basename(s), "hex": hx, "kind": kind,
                     "method": method, "path": path, "decode": decode})
    return outs


def _decode_probe(path):
    """I-4 反证：让模块侧 `op=cdn sub=decode` 用 `BitmapFactory.decodeFile` 判明文。

    解析 `CDN decode <file> w=<n> h=<n> ok=<0|1>`：
      ok=1 且 w>0 ⇒ 明文可用图；ok=0 ⇒ 密文（待解密）；
      没打该行 ⇒ 探针未实现，返回 UNKNOWN(需探针)（不直接判 NO，见 B-2）。
    """
    cdn("decode", val=path)
    time.sleep(1.0)
    hit = last_cdn(pull(), "decode ")
    if not hit:
        return "UNKNOWN(需探针 op=cdn sub=decode)"
    m = re.search(r"ok=(\d+).*?w=(\d+)", hit)
    if not m:
        return "UNKNOWN(%s)" % hit.strip()
    ok, w = int(m.group(1)), int(m.group(2))
    if ok == 1 and w > 0:
        return "明文(decodeFile ok,w=%d)" % w
    return "密文(待解密)"


# ---------------------------------------------------------------- main
def build_parser():
    ap = argparse.ArgumentParser(
        prog="tools/_r9_cdn.py",
        description="R9-P2：验 ⑫.7 的 I-1(dk.a) / I-2(落盘) / I-3(imageCachePath) / I-4(magic bytes)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=("默认只跑**只读**步骤（不 hook、不改开关）；要验 I-2 增量/I-3 必须加 --apply。\n"
                "依赖模块侧 op=cdn 探针，清单见本文件头部第 0 节。\n\n"
                "例：\n"
                "  python tools/_r9_cdn.py --help\n"
                "  python tools/_r9_cdn.py\n"
                "  python tools/_r9_cdn.py --apply --force --screens 6\n"
                "  python tools/_r9_cdn.py --apply --force --i3\n"))
    ap.add_argument("--apply", action="store_true",
                    help="允许写操作：op=cdn sub=hook on / cbon on / force / imgpath")
    ap.add_argument("--force", action="store_true",
                    help="I-2：把 dk.a() 强制 true（需 --apply）")
    ap.add_argument("--i3", action="store_true",
                    help="I-3：改写 BatchSnsReqImageData.imageCachePath（需 --apply）")
    ap.add_argument("--i3-dir", default="/sdcard/Android/data/com.tencent.mm/cache/r9i3",
                    help="I-3 的落盘目标目录，默认 %(default)s")
    ap.add_argument("--screens", type=int, default=3, help="滚几屏触发下载，默认 3")
    ap.add_argument("--dir", help="直接指定落盘目录，跳过候选探测")
    ap.add_argument("--top", type=int, default=5, help="打印前 N 个文件名，默认 5")
    ap.add_argument("--sample", type=int, default=5, help="magic 采样文件数，默认 5")
    ap.add_argument("--allow-stale", action="store_true",
                    help="I-2 无新增时也允许对老文件采样（verdict 会标注「非本次新增」；默认关）")
    ap.add_argument("--settle", type=float, default=2.0, help="发完广播到 pull 的等待，默认 2.0s")
    ap.add_argument("--no-nav", action="store_true", help="不导航，假定已在朋友圈页")
    ap.add_argument("--no-scroll", action="store_true", help="不滚屏")
    ap.add_argument("--out", default=LOCAL_DBG, help="结果落盘，默认 %s" % LOCAL_DBG)
    ap.add_argument("--dry-run", action="store_true", help="只打计划，一条 adb 都不发")
    return ap


def plan_of(a):
    p = []
    p.append("1. 拉前台（必要时 发现 → 朋友圈），校验 %s" % SNS_UI)
    p.append("2. op=logclear（日志 5000 行环形，测前必须清）")
    if a.apply:
        p.append("3. op=cdn sub=hook val=on + sub=cbon val=on   [写]")
        p.append("   op=cdn sub=reset（清计数/熔断，防上一轮累积污染差值）")
        p.append("   op=cdn sub=img（看 SnsCdnImage 的 H1/H3 是否装上）")
    else:
        p.append("3. 跳过 hook/强制（未加 --apply，只读）")
    p.append("4. I-0：op=cdn sub=stat → 看 hooked= / proc= / pid=（**前置生死项**）")
    p.append("   · 这里只判「广播到达的进程对不对」，**不能**证明 CDN 回调也在该进程")
    p.append("5. I-1：op=cdn sub=dk → 抓 `CDN dk ret=<bool>`")
    p.append("6. I-2：%s → before snapshot" % (a.dir or "用 op=cdn sub=paths 解析 picCachePath"))
    if a.force and a.apply:
        p.append("6b. op=cdn sub=force val=true                 [写]")
    if a.no_scroll:
        p.append("7. （--no-scroll）跳过滚屏 ⇒ 第 8 步 hitK/hitCB 增量必为 0，I-0 只能给 UNKNOWN")
    else:
        p.append("7. 滚 %d 屏：input swipe %s" % (a.screens, " ".join(SWIPE)))
    p.append("8. I-0 复判：再 pull 一次 stat，算 hitK/hitCB 增量（>0 才算回调落在本进程）")
    p.append("9. I-2：after snapshot，与前值做差")
    if a.i3:
        p.append("10. I-3：op=cdn sub=imgpath val=%s → 看该目录有没有新文件  [写]" % a.i3_dir)
    else:
        p.append("10. I-3 改写 imageCachePath：跳过（未加 --i3）")
    p.append("11. I-4：新增文件先 op=cdn sub=export 拷到可读目录，再取头 16 字节判 magic + sub=decode 反证")
    if a.allow_stale:
        p.append("    ⚠️ --allow-stale：无新增时也采样老文件（verdict 标注非本次新增）")
    p.append("12. 打印 verdict 块，并追加到 %s" % a.out)
    return p


def main():
    a = build_parser().parse_args()

    if a.dry_run:
        print("[dry-run] 计划（**未执行任何 adb**）：")
        for line in plan_of(a):
            print("  " + line)
        print("\n⚠️ 依赖模块侧 op=cdn 探针：`probe/SnsCdnProbe.kt` 已实现（2026-10-20 R10），"
              "接线在 `EntryReceiver.kt` 的 `\"cdn\"` 分支。**装包后**才可用；未装新包时只会得到 UNKNOWN。"
              "清单见文件头第 0 节。")
        return 0

    if a.force and not a.apply:
        print("⚠️ --force 需要 --apply，本次按只读跑（不强制开关）")
    if a.i3 and not a.apply:
        print("⚠️ --i3 需要 --apply，本次按只读跑（不改 imageCachePath）")
    if a.i3:
        DIR_CANDIDATES.insert(0, a.i3_dir)

    print("=== 1. 拉前台 ===")
    act = ensure_foreground(nav=not a.no_nav)
    print("  top:", act)
    if SNS_UI not in act:
        print("  ⚠️ 不在朋友圈页 —— 微信在后台时广播**不投递**，结果不可信")

    print("=== 2. logclear ===")
    logclear()

    print("=== 3. 探针状态 ===")
    if a.apply:
        cdn("hook", val="on")
        cdn("cbon", val="on")
        time.sleep(a.settle)
        lines = pull()
        print(" ", last_cdn(lines, "hook ") or "(无 CDN hook 行 ⇒ 模块侧缺 op=cdn sub=hook)")
        # 2026-10-20：开工前清计数 + 熔断，否则「前后差值」会把上一轮的累积算进来（R9 的 B-1 教训）
        cdn("reset")
        time.sleep(a.settle)
        lines = pull()
        print(" ", last_cdn(lines, "reset") or "(无 CDN reset 行 ⇒ 探针版本旧)")
        # 成品侧（SnsCdnImage）状态：判 H1/H3 到底装没装上
        cdn("img")
        time.sleep(a.settle)
        lines = pull()
        print(" ", last_cdn(lines, "img") or "(无 CDN img 行 ⇒ SnsCdnImage 未装)")
    else:
        print("  只读模式：未装 hook（要观测请先加 --apply）")

    print("=== 4. I-0 进程归属（前置生死项）===")
    i0, i0ev = run_i0(apply_mode=a.apply)
    print("  %s\n  证据：%s" % (i0, i0ev))
    # 记下滚屏前的命中计数，滚屏后用增量做 I-0 的**真正判据**
    lines = pull()
    st = last_cdn(lines, "stat hooked=")
    _k = re.search(r"hitK=(\d+)", st or "")
    _c = re.search(r"hitCB=(\d+)", st or "")
    hitk0 = int(_k.group(1)) if _k else 0
    hitcb0 = int(_c.group(1)) if _c else 0
    if i0 == "FAIL":
        print("  ⚠️ I-0 未过 —— 按方案 §7，此后的 **I-2~I-4**（依赖回调/落盘）结论不可采信，"
              "先查进程/类名。")
        print("     ✋ 但 **I-1 不受影响**：`dk.a()` 是纯反射调用，不依赖任何 hook。")

    print("=== 5. I-1 dk.a() ===")
    i1, i1ev = run_i1()
    print("  ret = %s\n  证据：%s" % (i1, i1ev))

    print("=== 6. I-2 落盘目录 ===")
    dir_notes = []
    if a.dir:
        target, how = a.dir, "--dir 指定"
        why = _dir_forbidden(a.dir)
        if why:
            dir_notes.append("%s ⇒ ⚠️ 人工指定但是%s，I-2 结论不可信" % (a.dir, why))
    else:
        target, how, dir_notes = resolve_dir(DIR_CANDIDATES)
    print("  dir = %s   (%s)" % (target, how))
    # B-7：把每个候选的入选/排除原因都打出来，便于人工复核「为什么选了这个目录」
    for n in dir_notes:
        print("    · %s" % n)
    if not target:
        print("  ⚠️ 没有任何可下落盘目录，I-2/I-4 只能给 UNKNOWN")
        before = after = dict(SNAP_EMPTY)
        i3line, i3before, i3_ran = ("(未启用 --i3)", dict(SNAP_EMPTY), False)
        samples, i4, stale = [], [], False
        # ⚠️ **但滚屏和 I-0 复判仍要做**（2026-10-24 真机踩到）：
        #    I-0 的真正判据是 `hitK/hitCB` 增量，它只依赖钩子命中，**不依赖落盘目录**。
        #    旧版在这里直接跳过滚屏 ⇒ I-0 永远停在「广播到达主进程」这一半，
        #    真正判据一次都没跑到。
        if a.apply and a.force:
            print("=== 6. 强制 dk.a()=true（仍要强制：否则滚屏不触发批量下载）===")
            print(" ", do_force(True).strip())
        print("=== 7. 滚 %d 屏（仍要滚：I-0 复判不依赖目录）===" % a.screens)
        if not a.no_scroll:
            scroll(a.screens)
        time.sleep(a.settle)
        print("=== 8. I-0 复判（滚屏后回调增量）===")
        if a.no_scroll:
            i0b, i0bev = "SKIP", "--no-scroll ⇒ 没有滚屏就不会触发新下载，增量判据不成立"
        else:
            i0b, i0bev = run_i0_after_scroll(hitk0, hitcb0)
        print("  %s\n  证据：%s" % (i0b, i0bev))
        # ★ 滚屏后再问一次 paths：真正触发了下载的话，探针会观测到 picCachePath，
        #   这比 DIR_CANDIDATES 猜目录靠谱得多（候选全不存在时这是唯一出路）
        print("=== 8b. 滚屏后 paths（找真实落盘目录）===")
        cdn("paths")
        time.sleep(1.5)
        for ln in pull():
            if "CDN path" in ln:
                print("   ", ln.strip()[:200])
    else:
        print("  可用取数口径：%s" % ", ".join(probe_access(target)))
        before = snapshot(target)
        print("  before: files=%d bytes=%d via %s" % (before["files"], before["bytes"], before["method"]))

        if a.apply and a.force:
            print("=== 6. 强制 dk.a()=true ===")
            print(" ", do_force(True).strip())

        print("=== 7. 滚 %d 屏 ===" % a.screens)
        if not a.no_scroll:
            scroll(a.screens)
        time.sleep(a.settle)

        # I-0 的真正判据：滚屏后看 hitK/hitCB 有没有增量（回调是否落在装钩子的进程里）
        print("=== 8. I-0 复判（滚屏后回调增量）===")
        if a.no_scroll:
            i0b, i0bev = "SKIP", "--no-scroll ⇒ 没有滚屏就不会触发新下载，增量判据不成立"
        else:
            i0b, i0bev = run_i0_after_scroll(hitk0, hitcb0)
        print("  %s\n  证据：%s" % (i0b, i0bev))

        print("=== 9. I-2 after ===")
        after = snapshot(target)
        print("  after : files=%d bytes=%d via %s" % (after["files"], after["bytes"], after["method"]))
        print("  差量  : files=%+d bytes=%+d" % (after["files"] - before["files"],
                                               after["bytes"] - before["bytes"]))
        for nm in after["names"][:a.top]:
            print("    · %s" % nm)
        if not after["names"][:a.top]:
            print("    (目录为空 / 读不到)")

        i3line = "(未启用 --i3)"
        i3before = None
        i3_ran = bool(a.apply and a.i3)
        if i3_ran:
            print("=== 10. I-3 改写 imageCachePath ===")
            i3line, i3before = run_i3(a.i3_dir)
            print(" ", i3line.strip())
            if not a.no_scroll:
                scroll(a.screens)
            time.sleep(a.settle)
            i3after = snapshot(a.i3_dir)
            print("  %s: %d → %d  files" % (a.i3_dir, i3before["files"], i3after["files"]))
            i3line = "%s；%s files=%d→%d" % (i3line.strip(), a.i3_dir,
                                             i3before["files"], i3after["files"])

        print("=== 11. I-4 magic bytes ===")
        samples = pick_samples(after, before, a.sample)
        stale = False
        if not samples and a.allow_stale:
            samples = after["names"][:a.sample]
            stale = True
            print("  ⚠️ 无新增，--allow-stale：采样老文件（非本次新增）")
        i4 = run_i4(target, samples, allow_export=a.apply)
        if not samples:
            print("  (无新增样本 → I-4 判 NO-NEW-FILES)")
        if not i4:
            print("  (无样本)")
        for r in i4:
            print("  %-40s %-22s %-16s %s  [%s]" % (
                r["file"][:40], r["hex"], r["kind"], r["decode"], r["method"]))

    # ------------------------------------------------------------ verdict
    # B-2：任一种图片格式（jpeg/png/gif/webp/bmp/heic）都算明文，不能只认 jpeg。
    PLAIN_KINDS = ("jpeg", "png", "gif", "webp", "bmp", "heic")
    plains = [r for r in i4 if r["kind"] in PLAIN_KINDS]

    if not samples:
        i4verdict, i4ev = "NO-NEW-FILES", "I-2 无新增文件 ⇒ 无本次落盘样本（%s）" % (
            "若需看老文件请显式 --allow-stale" if not stale else "已 --allow-stale 仍无样本")
    elif i4 and not plains:
        i4verdict, i4ev = "NO", "样本 %s magic=%s ⇒ %s（decode=%s）" % (
            i4[0]["file"], i4[0]["hex"], i4[0]["kind"], i4[0]["decode"])
    elif plains:
        i4verdict, i4ev = "YES", "样本 %s magic=%s decode=%s" % (
            plains[0]["file"], plains[0]["hex"], plains[0]["decode"])
    else:
        i4verdict, i4ev = "UNKNOWN", "无样本（I-2 没新增文件，或目录读不到）"

    if i0 == "FAIL":
        concl = ("⛔ **I-0 未过（生死项）：%s** ⇒ 本轮依赖回调/落盘的 I-2~I-4 结论不可采信，"
                 "先修进程/类名问题再重跑。✋ **I-1（`dk.a()`）不受影响**（纯反射，不依赖 hook）" % i0ev)
    elif i0 == "SKIP":
        concl = ("⏸ **I-0 跳过**：%s ⇒ 加 `--apply` 重跑才能拿到回调归属；"
                 "本轮 I-1 仍有效，I-2~I-4 待定" % i0ev)
    elif i1 == "UNKNOWN":
        concl = "待补探针：模块侧缺 op=cdn sub=dk/sub=ls（见文件头第 0 节），本轮无结论"
    elif after["files"] > before["files"]:
        concl = "批量通路**可落盘**（%s 增加了 %d 个文件）；%s" % (
            target, after["files"] - before["files"],
            "且为明文图（%s），直接拷走即可" % plains[0]["kind"] if i4verdict == "YES"
            else ("但**不是明文图**，仍需解密" if i4verdict == "NO" else "magic 未取到，待补"))
    else:
        concl = "本轮未见落盘（目录 %s 文件数 %d → %d）；%s" % (
            target, before["files"], after["files"],
            "开关若未强制 true 请加 --apply --force 再跑" if not (a.apply and a.force)
            else "批量通路大概率仍不通（需结合 I-1 看是真的下了还是没触发）")

    # I-0 复判（真判据）零回调时给结论加限定 —— 别让「没落盘」被误读成「通路不通」：
    # 也可能是**回调根本没进这个进程**，那是完全不同的根因（改 hook 位置，而不是改逻辑）。
    if i0b == "FAIL" and not concl.startswith("⛔"):
        concl = "⚠️ I-0 复判零回调（hitK/hitCB 均无增量）⇒ 回调可能不在本进程，" \
                "结论需降级看待。%s" % concl

    v = []
    v.append("==== R9-CDN VERDICT ====")
    v.append("I-0 回调进程归属       : %-7s (证据：%s)" % (i0, i0ev))
    if i0b is not None:
        v.append("I-0 滚屏后回调增量     : %-7s (证据：%s)" % (i0b, i0bev))
    else:
        v.append("I-0 滚屏后回调增量     : SKIP    (无落盘目录 / 未滚屏 ⇒ 未跑)")
    v.append("I-1 dk.a()            : %-7s (证据：%s)" % (i1, i1ev))
    v.append("I-2 落盘文件数(前→后)  : %d → %d   (目录：%s；口径：%s；汇总：%s)" % (
        before["files"], after["files"], target or "-",
        after["method"], after["ev"]))
    # B-7：把每个候选目录的入选/排除原因也写进 verdict，便于人工复核「为什么选这个目录」
    if dir_notes:
        v.append("I-2 候选目录复核       :")
        for n in dir_notes:
            v.append("      · %s" % n)
    v.append("I-3 imageCachePath 改写: %-7s (证据：%s)" % (
        "Ran" if i3_ran else "SKIP", i3line.strip()))
    v.append("I-4 明文图             : %-7s (样本：%s)" % (i4verdict, i4ev))
    v.append("结论：%s" % concl)
    block = "\n".join(v)

    print()
    print(block)

    try:
        with open(a.out, "a", encoding="utf-8") as f:
            f.write("\n=== R9-CDN %s apply=%s force=%s i3=%s screens=%d ===\n"
                    % (time.strftime("%H:%M:%S"), a.apply, a.force, a.i3, a.screens))
            f.write(block + "\n")
        print("落盘:", a.out)
    except OSError as e:
        print("⚠️ 写落盘文件失败:", e)
    return 0


if __name__ == "__main__":
    sys.exit(main())

# [DONE]
