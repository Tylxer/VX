# R53 探针复核

结论：可装包 / 需先修（P0=0，P1=1，P2=3）

## P0（会导致探针失效或影响宿主稳定）

无。三条路径（hook/row/prefs）整体被 `HookListProbe.run` 的 `runCatching` 包裹（HookListProbe.kt:46-52），
`EntryReceiver` 侧再经 `io{}` 的 `runCatching(block)`（EntryReceiver.kt:89-95）兜底，没有能炸到宿主主线程或进程的裸路径。
反射、`getAll`、`getItem` 等每个危险调用点都是 `runCatching { }.getOrNull()`，异常只会变成一行 `ERR`/`null` 日志。

## P1（会给出错误数据 / 误导判据）

### P1-1 `prefsDump` 的 `getAll()` 可能因跨 uid 权限静默返回空 → 误判「微X 没配置」
- 位置：HookListProbe.kt:64-83。
- `XSharedPreferences(String pkg)` 读的是**另一个包**（`com.fkzhang.wechatxposed` / `com.fkzhang.xposed`）的
  shared_prefs。微信进程 uid 是 `com.tencent.mm`，该文件在对方 data 目录、属对方 uid。
  - 经典 Xposed：`getAll()` 要求目标模块先调过 `makeWorldReadable()`；Android 7+/11+ 之后 world-readable
    文件基本被 SELinux 卡死，所以直接开文件读经常是**空结果、不抛异常**。
  - LSPosed：`XSharedPreferences` 是重实现，可能走特权通道绕过 uid 边界 —— **这点我不能确定**，按题要求标「不确定」。
- 危害：`getAll()` 读不到时返回**空 map 而不是异常**，探针会打印 `size=0`，容易被误读成「微X 没写配置」，
  而实际是「没权限读」。
- 兜底（已有）：第 71 行会先打 `file=... exists=...`。但注意 `File.exists()` 对跨 uid 路径在「文件不存在」和
  「无权限 stat」两种情况下**都返回 false**，所以 `exists=false` 本身有歧义，只能提示「需要人工确认」。
- 建议：真机先跑一次 `prefs`，如果 `exists=false` 且 `size=0`，优先怀疑权限而非「没配置」；必要时改走
  `XSharedPreferences.makeWorldReadable()`（旧版 API）或让微X 的 prefs 文件名显式传入 `XSharedPreferences(pkg, fileName)`。
  判定为 P1 是因为它直接污染 prefs 子命令的判据，但已有 `exists=` 兜底可识别。

## P2（可读性、边界、注释）

### P2-1 Python `bc()` 引号方向反了：包的是 key 不是 value
- 位置：tools/_r53_xh.py:52-55。
- `am broadcast` 语法是 `--es <key> <value>`。代码 `cmd[i-1] == "--es"` 命中的是 `--es` **后面紧跟的那个 token**，
  即 **key**（op/sub/val），不是 value（xh/hook/com.fkzhang…）。注释说「给值包单引号防止 `>`/`(` 静默失败」，
  实际保护没落到 value 上。
- 现状无害：当前所有 value 都是安全的包名/短串（`xh`/`hook`/`row`/`prefs`/`com.fkzhang`/`com.tencent.mm.ui.conversation`），
  不含 shell 特殊字符，所以跑得通。但一旦传进带 `(`/`>`/空格的过滤串，value 不会被引号保护 → 静默失败。
- 修法（不强求）：把判定改成「当前 token 是 value」即 `cmd[i-1]` 之前还要再往前看一位，或直接对 `extras` 里
  每个 `--es` 的「下一项」单独包引号，而不是在拼好的 `cmd` 上倒推。

### P2-2 `rowDump` 超时后 `out` 的并发读写没有内存屏障
- 位置：HookListProbe.kt:150-193。
- `out` 是普通 `ArrayList`：主线程的 runnable 写、io 线程读。
  - `latch.countDown()`→`await()` 正常返回时存在 happens-before，可见性没问题。
  - 但 `await(3s)` **超时**时（主线程卡顿，恰是探针最可能遇到的场景）没有 happens-before：io 线程可能读到
    空/半截的 `out`，且主线程 runnable 若仍在往 `out` 追加，会与 io 线程的遍历并发 → 可能
    `ConcurrentModificationException`（被 `io{}` 的 runCatching 吞掉，表现为「PROBE io failed」+ 丢输出）。
- 降级本身可接受（诊断探针、重试即可），但更稳的写法可考虑：`out` 换成线程安全集合（如
  `java.util.concurrent.CopyOnWriteArrayList`）或 `Collections.synchronizedList`；或者干脆在超时后不再返回
  `out` 而返回一个「超时」标记行，避免两边同时摸同一对象。

### P2-3 `take(n)` 对负数 n 会抛异常 + hook 表字段名启发式脆弱
- 位置：HookListProbe.kt:77、:92、:139；:116。
- `n` 来自 `getIntExtra("n", 60)`，用户手动传 `--ei n -1` 时 `List.take(-1)` 抛 `IllegalArgumentException`。
  - prefsDump 里的两处被外层 `runCatching` 兜住（67-82、89-95）；
  - hookDump 的 `hits.take(n)`（:139）会一路冒到 `run()` 的 runCatching，结果是**整条 hookDump 输出变成一行 ERR**。
  - 边界概率低（脚本从不传 n），记 P2。
- 字段名启发式（:116 `contains("Hooked") || contains("hook")`）依赖 LSPosed 内部命名。
  当前 LSPosed 的 `sHookedMethodCallbacks`/`sHookedConstructorCallbacks` 都含 `Hooked` 能命中；
  但若某版把字段改名为不含小写 `hook` 的形如 `sMethodHooks`（"Hooks" 不含 "hook"），会漏读。
  属「不确定」，仅提示，不判 FAIL。

## 逐条核对

1. **反射读 hook 表** — PASS
   - 证据：HookListProbe.kt:113-124。
   - 值类型不写死（`v is Map<*,*>` + `k is Member`），同时覆盖 LSPosed `Map<Member, MethodHookable>` 与
     旧版 `Map<Member, Unhook>`；方法/构造器分开存也无所谓——遍历的是全部 `declaredFields`，两个 `*Hooked*`
     字段都会被读到，不存在只读一半。
   - `f.isAccessible=true` 读 `XposedBridge` 私有静态字段：`XposedBridge` 非 boot classpath 类，Android 9+ 的
     hidden-api 限制只针对 boot 类，判断正确，不受限。第 109 行顺带打 `cls.classLoader` 可佐证加载位置。

2. **rowDump 线程模型** — PASS（附 P2-2 备注）
   - 证据：EntryReceiver.kt:181-189（`io{}` 起普通 `Thread`）+ HookListProbe.kt:150-193。
   - 无死锁：`rowDump` 在 io 线程跑，`mainHandler.post` 只是投递到主线程，`latch.await` 阻塞的是 io 线程，
     不碰主线程。
   - `latch.countDown()` 在 :189、`runCatching` 之外：但 `runCatching` 内部异常会被它自己吞掉、不会往外抛，
     所以 `countDown()` 恒会执行，**不存在漏 countDown**。
   - 降级见 P2-2。

3. **getItemViewType / getItem 越界** — PASS
   - 证据：HookListProbe.kt:176-187。
   - `pos = firstVisiblePosition + i`，`i < childCount`，落在可见区间内；`HeaderViewListAdapter` 自行处理 header
     偏移，header 行 `getItemViewType` 返回 -1、`getItem` 返回 null，均为预期而非异常。两处都已 `runCatching`+
     `getOrNull` 兜底，即便尾部 footer 场景 pos 偶发到 count 也不会崩。

4. **prefsDump** — 部分不确定（见 P1-1），代码本身 PASS
   - 证据：HookListProbe.kt:64-98。
   - `getFile()` 在经典 Xposed 与 LSPosed 均为公开方法，即便某版没有也被 `runCatching` 兜住，足够安全。
   - `all.entries.take(n)` 对 `Map<String, *>` 合法：`entries` 是 `Set<Entry<String, *>>`，`take(n)` 得
     `List<Entry<String, *>>`，`for((k,v) in …)` 解构后 `v: Any?`，`v.toString()` 合法，类型推断没问题。
   - `getAll()` 跨 uid 行为见 P1-1（不确定）。

5. **EntryReceiver 分支 + LogX 线程安全** — PASS
   - 证据：EntryReceiver.kt:181-189 + LogX.kt:68-94。
   - `io{}` 是普通 `Thread`（EntryReceiver.kt:89-95），子线程里 `LogX.i` 安全：`Log.i`/`XposedBridge.log` 本身
     线程安全，落盘路径 `file()` 整体在 `synchronized(lock)` 内（LogX.kt:70），5000 行环形覆盖逻辑
     （LogX.kt:22 常量 + :83-88 轮转）也在同一锁内，无竞态。
   - `now()` 每次 new 一个 `SimpleDateFormat`，无共享实例，无 SimpleDateFormat 非线程安全问题。

6. **Python 脚本 bc()** — 部分 FAIL（P2-1）
   - `cmd[i-1] == "--es"` 对首元素安全：有 `i > 0` 短路（_r53_xh.py:53），`cmd[i-1]` 在 i=0 时不会被求值，无越界。
   - 引号方向错：包的是 key 不是 value，见 P2-1。
   - `subprocess.run` 传 list 给 adb.exe：`line` 是含空格与单引号的**单个** list 元素，Windows 下 `list2cmdline`
     只对双引号/反斜杠做转义，**单引号原样保留**并透传到设备端 sh，由设备 shell 解释，不会丢；
     `MSYS_NO_PATHCONV=1`（:36）也正确防了 Git Bash 路径改写。PASS。
   - 附带：ADB 路径硬编码到 `C:\Users\lx\...`（:32），个人工具可接受，不算 bug。

## 附：本轮未验证项（设备断连，未跑真机）
- prefs 子命令的真实行为（LSPosed 是否绕过跨 uid）—— 见 P1-1，建议真机先跑 `prefs` 看 `exists=`。
- `row` 子命令里 `HeaderViewListAdapter` 的 `mHeaderViewInfos` 反射（HookListProbe.kt:169-173）在微信 8.0.48
  上字段是否仍存在/仍是该名——已 `runCatching` 兜底，读不到只会少一行 `headerInfos`，不影响其余输出。
