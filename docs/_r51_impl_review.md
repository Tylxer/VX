# R51「被撤回图片可打开」装包前独立交叉复核报告

> lane：装包前独立交叉复核（Pro 类：Hook 链路 / 反射 / 异步并发复核）
> 模式：**全只读** —— 不改任何源码/测试/配置/其它报告；不碰 adb / 真机 / Gradle / 装包 / kvset / git add/commit。唯一写入文件＝本报告。
> 范围：仅 R51 本轮 5 个源码文件 + 1 个新单测（`MediaDeleteLogic.kt` / `MediaTableGuard.kt` / `MediaDeleteLogicTest.kt` / `HookEntry.kt` / `RevokeRescue.kt` / `Keys.kt`）。R49 阶段二既有源码（`SnsWriteProbe.kt` / `SnsGuard.kt` / `SnsBlockProbe.kt` / `EntryReceiver.kt`）与一批文档明确排除。`Keys.kt` 的 diff 同时含 R49 `SNS_WRITE_PROBE` 与 R51 `ANTIREVOKE_MEDIA_DELETE`，逐行区分、只审 R51 部分。
> 结论口径：逐条 PASS / FAIL + `文件:行号` 锚点 + 风险；不确定标 `[推断]` / `[需真机]`。
> 裁定铁律：C1 FAIL ⇒ 必须判「不可以装包」。

## 复核项清单（C1~C11 / X1~X3）

- C1 ⭐⭐ callback 顺序与 execSQL 语义（FAIL ⇒ 判「不可以装包」）
- C2 try/catch 兜底
- C3 反射取 private 池
- C4 默认关零开销
- C5 R42 / 语音现网零影响
- C6 before 内零查库 / 零重入
- C7 限流 / 去重 / 封顶
- C8 判据正确性
- C9 单测是真验证吗
- C10 三档边界 + 回滚
- C11 装包安全性
- X1 两份报告 `[DONE] ALL` 自报与磁盘一致性
- X2 独立 `git diff --stat -- app/src/main` / `git status --short` 真实改动清单
- X3 装包前必改项（P0/P1）

## 二元结论

**可以装包**（C1 PASS、C1~C11 全 PASS、无 P0 必改项、现网语义零影响；完整依据见文末「最终二元结论」小节）。

[START]

## C1 ⭐⭐ callback 顺序与 execSQL 语义 —— **PASS**

> ⛔ 裁定口径：本条 FAIL ⇒ 必须判「不可以装包」。独立复核裁 **PASS**，不触发该分支。

### 1.1 同一宿主方法上的多 before callback 与注册顺序（源码级锚点）

`handleLoadPackage` 内 install 顺序（`HookEntry.kt`）：
- `:224` `VoiceGuard.install(lpparam)` → 挂 `delete`（`VoiceGuard.kt:136`，另 `:133` 挂死代码 `deleteWithOnConflict`）
- `:225` `MediaTableGuard.install(lpparam)` → 挂 `delete`（`MediaTableGuard.kt:127`）+ `execSQL`（`:130`）
- `:243` `SnsGuard.install(lpparam)` → 挂 `delete`（`SnsGuard.kt:149`）+ `execSQL`（`:152`）

`XposedBridge.hookAllMethods` 把 callback 追加进该方法的 callbacks 数组，`handleHookedMethod` 按**注册顺序**依次调用 before `[推断]`（rovo89 XposedBridge / LSPosed 兼容层契约，本轮不真机取证）。故：

| 方法 | before callback 顺序（先 → 后） |
|---|---|
| `delete` | VoiceGuard → **MediaTableGuard(新)** → SnsGuard |
| `execSQL` | **MediaTableGuard(新)** → SnsGuard.sqlGuard（R42） |

⚠️ 关键事实：**`execSQL` 上新 callback 注册在 R42 的 `SnsGuard.sqlGuard` 之前**（`:225` < `:243`）；`delete` 上新 callback 夹在 VoiceGuard 与 SnsGuard 之间。实现报告 `docs/_r51_media_table_guard_impl.md:129` 自述「本 lane callback 先于 SnsGuard 注册」与此一致 ✓。

### 1.2 会不会因一个新 callback 抛异常导致既有 callback 不执行 —— 不会

- `deleteGuard.beforeHookedMethod`（`MediaTableGuard.kt:177-186`）：整段 `try { onBefore(...) } catch (t: Throwable) { runCatching { throttledLog(...) } }`
- `execSqlGuard.beforeHookedMethod`（`:188-196`）：同款
- `onBefore`（`:209-260`）：**整段再包一层** `try {...} catch (t: Throwable) { runCatching { throttledLog(...) } }`
- 两处 catch 均**吞异常、不 `throw`**，连节流日志都再 `runCatching` 包住（`:183, :193, :258`）

⇒ 无论按项目铁律「回调抛异常会阻断后续 before callback」，还是按 rovo89 原生「catch 后 `param.setResult(null); returnEarly=false; continue`」语义，新 callback 都**不会**把异常交给 Xposed 框架，既有 callback 不会因它而中断。反射/解析/账本全部在 try 内（详见 C2）。

### 1.3 `param.result = null` 能否阻止 void `execSQL` 方法体 —— 能（`[推断]` + `[需真机]`）

- 实现（`MediaTableGuard.kt:242-249`）：`execSQL` 命中 `param.result = null`；`delete` 命中 `param.result = FAKE_ROWS(1)`（`:78`）。
- 机制：Kotlin `param.result = null` 编译为 `XC_MethodHook.MethodHookParam.setResult(null)`；`setResult()` 无条件把 `returnEarly` 置 `true`（rovo89 `XposedBridge.MethodHookParam` 契约；LSPosed 兼容层对应 `BeforeHookCallback.returnAndSkip(null)`）。`returnEarly=true` ⇒ 跳过原方法体，且**跳过剩余 before callbacks**。对 `void` 方法，`null` 返回值被框架忽略、无类型转换异常 ⇒ **确实能阻止方法体执行** `[推断]`。
- ⛔ 与 `XC_MethodReplacement` 的差异：`MethodReplacement` 是 `XC_MethodHook` 子类，在 before 里**无条件** `setResult(replace(...))` 并 return —— 本质同为 `returnEarly=true` 跳过原方法，故对「跳过方法体」目标**等价**；差异在 ① Replacement 无条件替换、会覆盖同方法其它 hook 的识别/计数（实现报告 `:136` 已正确否决它）；② 返回值由 `replace` 计算而非固定；③ `MethodHook + setResult` 是条件性跳过、且会中断后续 before callbacks。本轮用 `MethodHook` 以保留条件化能力，选择正确 ✓。
- `[需真机]`：LSPosed 8.0.48/2588 GP 具体版本对 void 方法 `setResult(null)` 是否确实跳过、以及微信是否重试删除 / 抛异常，未取证（实现报告 S1.4 `:137` 列 4 条）。**即便该写法在真机不生效，后果也只是「execSQL 通道漏拦、图片修复不生效」，原方法照常执行、除设 result 外无任何副作用 ⇒ 安全方向（放行），不会破坏微信**。

### 1.4 ⛔ 需纠正的一处报告推断（不推翻 PASS）

`docs/_r51_media_table_guard_impl.md:22`、`:128`、`:270` 把「某 callback 置 `param.result` 后 `returnEarly=true` 会阻止原方法体，但**其余 before callback 仍会跑完**」列为 `[推断]` —— **该推断与 Xposed 实际机制相反**：`returnEarly=true` 时框架 `break`，剩余 before callbacks 被跳过（见 1.3）。实现报告据此得出的「必须用 try/catch 保证不抛异常」结论本身是对的，且靠**表名白名单三方互斥**兜住了这个更激进的行为：
- `MediaDeleteLogic.kt:64` 白名单 `{imginfo2, videoinfo2, wxfileindex2}`，**不含 `voiceinfo`**
- `VoiceGuard.kt:173-174` 只认 `voiceinfo`
- `SnsGuard.kt:879-889` 只对含 `SnsInfo` 且 `& -9` 清位形态的 SQL 改写 `param.args[0]`，对 `DELETE FROM 媒体表` 零动作

⇒ MediaTableGuard 只在 `DELETE FROM 媒体表` 时置 result；此时被跳过的 VoiceGuard / SnsGuard callback 对该 SQL 本就零动作，**现网语义零影响** ✓。

### 1.5 风险
- `[需真机]` 验证 LSPosed 该版本 `setResult(null)` 对 void 的行为与 callback 实际顺序（实现报告 S6 `:270-271` 已列）。
- 未来若在 `execSQL` 上新增对「媒体表 DELETE」有识别/计数需求的 callback，会被 `returnEarly` 静默跳过 —— 属设计约束，非本轮缺陷。
- ⛔ 结论：**C1 PASS**，不触发「FAIL ⇒ 不可以装包」。

[DONE] C1

## C2 try/catch 兜底 —— **PASS**

| 复核点 | 结论 | 锚点 |
|---|---|---|
| before 整段逻辑是否完全包在 try/catch | ✅ **双层**：两个 callback 顶层各一层 + `onBefore` 内再一层 | `MediaTableGuard.kt:177-196`（callback）、`:209-260`（onBefore） |
| 含反射 / 字符串解析 / 账本写入 | ✅ 字符串解析 `fromDelete`/`fromExecSql`（`:263-299`）、账本 `seen/matched/skipped/blocked/noteEvent`（`:214,223,229,237,250,224`）全在 `onBefore` 的 try 内；**反射根本不在 before**（`resolvePool` 只在 `install()` `:123` 调，`snapshotLiveTokens` 只在 `invalidate()` `:159` 调） | `MediaTableGuard.kt:209-260, 307-331` |
| catch 是否吞异常后继续放行、不 `throw` | ✅ 两个 callback 的 catch 与 `onBefore` 的 catch 都只 `runCatching { throttledLog(...) }`，**不 throw、不设 `param.result`** ⇒ 异常路径一律「这次不拦 = 放行」 | `:182-184, :192-194, :256-259` |
| 有无任何路径抛到 Xposed 框架 | ✅ before 路径零外泄（`catch(Throwable)` 全覆盖，日志再 `runCatching` 包）；`install()` 里的 `LogX.w/i`（`:118, :138-142`）不在 try 内，但整体被 `HookEntry.kt:223-226` 的 `runCatching{}` 包住，属启动期一次性、不抛到框架 | `HookEntry.kt:223-226`；`MediaTableGuard.kt:113-143` |

补充：`install()` 内 `findClass`（`:114-116`）、`refresh`（`:121`）、`resolvePool`（`:308-314`）、两个 `hookAllMethods`（`:127-132`）、`snapshotLiveTokens` 遍历（`:325-329`）、`invalidate()` 的 `refresh`（`:160`）全部各自 `runCatching` 兜底。

**风险**：极低。唯一理论路径是 `LogX`/`Kv` 内部抛 `Error`（非 `Throwable` 之外），`catch(Throwable)` 已覆盖。

[DONE] C2

## C3 反射取 private 池 —— **PASS**

### 3.1 字段真实形态（磁盘核实，非轻信自报）
`VoiceFileGuard.kt:109`：`private val protectedMedia = ConcurrentHashMap<String, Long>()` —— 字段名 `protectedMedia` ✅、`private` ✅、**实例字段**（`object` 内，非 `static`）✅、类型 `ConcurrentHashMap<String,Long>` 是 `Map<String,Long>` 子类型 ✅。KDoc `:108` 注明「图片/视频 imgPath → 放行截止时刻」；`:151` `protectedMedia[t] = now + ttlMs`（`now` 为 `elapsedRealtime()` `:150`）⇒ value 是**过期时刻**，与 `snapshotLiveTokens` 的 `v >= now` 口径一致 ✅。

### 3.2 反射写法逐项裁定
`MediaTableGuard.kt:307-318` `resolvePool()`：
- `VoiceFileGuard::class.java.getDeclaredField("protectedMedia")` + `isAccessible=true` ✅ 编译期确定的类，**不可能反射到错误的类**
- `f.get(VoiceFileGuard)`：字段是实例字段，`VoiceFileGuard` 作为 object 单例实例传入，正确 ✅（若为 static 字段传实例亦兼容）
- `as? Map<String, Long>`：**安全转换，不会 ClassCastException** ✅；类型不符/混淆改名 ⇒ 返回 `null` ⇒ 走降级
- 全程 `runCatching{}.getOrNull()`，失败打 `pool=reflect-failed`（`:316`）✅

### 3.3 过期引用 / 空集 / 并发修改
- **过期引用**：`VoiceFileGuard.kt:151, :153` 对该字段**只有 put / `entries.removeIf`，从不重新赋值整个 Map** ⇒ install 期拿到的引用进程内永久有效 ✅（这是本项最关键的核实点）
- **空集**：`RevokeRescue.kt:122` `VoiceFileGuard.protectMedia(orig.imgPath)` 在 `:128` `MediaTableGuard.invalidate()` **之前**（经 `Read RevokeRescue.kt:95-147` 逐行确认）⇒ 快照时池里已有本次 token ✅；`snapshotLiveTokens`（`:321-331`）只收 `v >= now` 存活 key 并 `lowercase()` 归一 ✅
- **并发修改**：`ConcurrentHashMap` 弱一致迭代器不抛 `ConcurrentModificationException` ✅；且遍历外包 `runCatching`（`:325-329`），遍历只在 `invalidate()`（非 before 热路径）✅
- before 只读 `@Volatile cachedTokens`（`:93, :227`），**不碰池、不反射** ✅

### 3.4 反射失败降级对现网的影响
`mediaPoolRef=null` ⇒ `snapshotLiveTokens` 恒返回 `emptySet()` ⇒ `cachedTokens` 空 ⇒ `onBefore:228-231` `tokens.isEmpty()` → `skipped++` → return，**不设 `param.result`** ⇒ 只记账、永不拦、不改任何 SQL/DB ⇒ **对现网零影响** ✅。

**风险**：
- `[需真机]` 混淆 / 安全策略导致 `getDeclaredField` 失败（已安全降级，仅影响本功能生效，不破坏微信）。
- `[需真机]` `protectedMedia` 的 key（`MediaGuardLogic.hexToken` 抽出的 32 位 md5）与撤回 SQL `where` 里 `extractTokens` 抽出的 token 是否真能对上 —— 属功能取证，对不上只会 `UNKNOWN` 放行，安全方向。

[DONE] C3

## C4 默认关零开销 —— **PASS**（附 1 个 `[推断]` 窗口风险）

| 复核点 | 结论 | 锚点 |
|---|---|---|
| `antirevoke.media_delete` 默认 `false` | ✅ `PrefSpec(Keys.ANTIREVOKE_MEDIA_DELETE, PrefKind.SWITCH, "false", "防撤回", "实验：…")` | `Keys.kt` diff `@@ -435,6 +454,7` |
| 新开关独立于 `antirevoke.msg` | ✅ 本类只读 `Keys.ANTIREVOKE_MEDIA_DELETE`（`:147, :172`），**从不读** `Keys.ANTIREVOKE_MSG` | `MediaTableGuard.kt:146-148` |
| 关时 before 真的零额外动作 | ✅ `onBefore` 第一行 `if (!enabled) return`（`:212`）；之后才有 `seen++`/`param.args`/解析/日志/反射。`enabled` 是 `@Volatile` 纯内存字段（`:80-81`），`Kv.getBool` 只在 `refresh()`（`:146-148`）由 `install()`（`:121`）/ `invalidate()`（`:160`）调用 ⇒ 关时**无字符串解析、无对象分配、无日志、无反射、无查库** ✅ | `MediaTableGuard.kt:80-81, 146-148, 209-214` |
| 有无 3s TTL 缓存导致「关了仍拦」 | ✅ **没有 3s TTL**。对比：现网 `VoiceGuard.cfg()`（`VoiceGuard.kt:151-154`）是 `elapsedRealtime()-cfgAt > 3_000` 就 `refresh()`（在 before 里查库）；R51 刻意改为纯 `@Volatile` 字段、只在 install/invalidate 刷新，避免了 before 查库 ✅ | `VoiceGuard.kt:146-154` vs `MediaTableGuard.kt:121,146-148,160` |
| 「hook 仍常驻并做解析」的漏网 | ✅ 无。`hookAllMethods` 注册无条件执行（`:127-132`），但 callback 第一行即 return，不做任何解析 ✅ | `MediaTableGuard.kt:127-132, 212` |

⚠️ **但存在另一种「关了仍拦」窗口（非 3s TTL，是事件驱动刷新）**：`enabled` 仅在 `install()` / `invalidate()` 刷新，而 `invalidate()` 只由 `RevokeRescue.record()` 图片分支触发 ⇒ 关闭开关后若不再发生撤回，`enabled` 仍为旧值 `true`，在 `WINDOW_MS=600_000`（`:70`，10 分钟）窗口内若恰有匹配 delete 仍可能被拦。实现报告 `docs/_r51_media_table_guard_impl.md:237`、`:171`、`:279` 已**如实写明**此路径（「关后要等下一次撤回的 `record()→invalidate()` 或进程重启才刷成 false」）。上限受 10 分钟窗口约束，且属「拦截」而非「放行」方向的安全问题、默认关、拦条件极窄 ⇒ **不构成 FAIL**，标 `[推断]` + `[需真机]` 验证「关开关即时生效」。

**风险**：中低。真机若要求「关开关立即零拦」，需触发一次撤回或重启微信；属设计取舍（换来了 before 零查库），非现网破坏。

[DONE] C4

## C5 R42 / 语音现网零影响 —— **PASS**（附 1 处如实区分）

### 5.1 五个现网文件本轮改动（独立 `git status --short` 核实）
| 文件 | 工作区状态 | 本轮 R51 是否改动 |
|---|---|---|
| `SnsSqlRewrite.kt`（`feature/`） | 未出现在 `git status` ⇒ **零改动**；`:46` `-9` 正则原样 | ⛔ 零改动 ✅ |
| `VoiceGuard.kt` | 未出现在 `git status` ⇒ 零改动 | ⛔ 零改动 ✅ |
| `VoiceGuardLogic.kt` | 未出现在 `git status` ⇒ 零改动；`:23-24, :153` GC 红线原样 | ⛔ 零改动 ✅ |
| `VoiceFileGuard.kt` | 未出现在 `git status` ⇒ 零改动；`:109, :251-252, :306` 原样 | ⛔ 零改动 ✅ |
| `SnsGuard.kt` | ⚠️ **`M` +7**，但 diff 内容是 R49 阶段二 `snsBlockGuard` before 里加 `SnsWriteProbe.captureUpdate`（`git diff` 输出 `@@ -296,6 +296,13`） | ⛔ **R51 本轮零改动**；该 +7 属 R49 阶段二既有未提交改动，按任务硬约束明确排除 ✅ |

（注：`EntryReceiver.kt +11` / `SnsBlockProbe.kt +8` / 新建 `SnsWriteProbe.kt` 同为 R49 阶段二，一并排除。）

### 5.2 新钩子的副作用面
- 只设 `param.result`：`execSQL` → `null`（`:245`）、`delete` → `FAKE_ROWS(1)`（`:248, :78`）；**不改 ContentValues / SQL 原文 / `param.args` / 除「跳过删除」外任何返回值** ✅（`fromDelete`/`fromExecSql` 纯只读解析，`:262-299`）
- **无新增对微信库的 insert/update/delete/execSQL** ✅；账本 `seen/matched/blocked/skipped/events/logAt` 全为进程内结构（`:99-109`）
- 表名白名单**不含 `voiceinfo`** ✅（`MediaDeleteLogic.kt:64`），与 `VoiceGuard`（只认 voiceinfo，`VoiceGuard.kt:173-174`）天然互斥；与 `SnsGuard`（朋友圈独立库 `SnsInfo/SnsComment`）无交集 ✅
- 未挂死代码 `deleteWithOnConflict`（`:126-127` 注释明写不挂）✅；未重复实现 `File.delete`（`VoiceFileGuard.kt:251-252`）✅

⚠️ 唯一新增的共享面：`execSQL` 上多一个 before callback，与 `SnsGuard.sqlGuard` 共存 —— 已在 **C1** 裁定：注册顺序 MediaTableGuard → SnsGuard，置 result 时跳过后续 callback，但 SnsGuard 对 `DELETE FROM 媒体表` 零动作 ⇒ 零影响 ✅。

**风险**：低。装包是全量工作区，`SnsGuard.kt` 的 R49 +7 等既有改动会一并进入 APK（默认关探针），提示主会话知悉（见 X2/X3）。

[DONE] C5

## C6 before 内零查库 / 零重入 —— **PASS**

### 6.1 `onBefore` 全路径逐动作（`MediaTableGuard.kt:209-260`）
`if (!enabled) return`（`:212`）→ `seen.incrementAndGet()`（`:214`）→ `param.args`（`:215`）→ `fromDelete`/`fromExecSql` **纯字符串解析**（`:218, :262-299`）→ `MediaDeleteLogic.isTargetTable`（`:222`）→ `matched`（`:223`）→ `noteEvent` 写 `ConcurrentHashMap`（`:224, :336-347`）→ 读 `@Volatile cachedTokens`（`:227`）→ `SystemClock.elapsedRealtime()`（`:232`）→ `MediaDeleteLogic.shouldBlock` 纯函数（`:233`）→ 设 `param.result`（`:245/248`）→ `blocked`（`:250`）→ `throttledLog`（`:251`）。

⇒ **无任何 DB 查询 / `runReadOnlySql` / 反射查库 / 文件 IO / 耗时操作 / 高频日志** ✅。`Kv.getBool`（查模块自有库）只在 `install()`/`invalidate()` 的 `refresh()` 里（`:121, :160`），**不在 before** ✅。

### 6.2 冷启宿主类未解析的 NPE 风险
- `install()` `:114-116` `findClass(WCDB_DB, lpparam.classLoader)` 包 `runCatching{}.getOrNull()`；`cls==null` ⇒ `LogX.w` + `return`（`:117-120`）✅
- `resolvePool()` 用 `VoiceFileGuard::class.java`（模块自身 object，类加载器必然就绪）✅
- `refresh()` 包 `runCatching`（`:121, :160`）✅
⇒ 冷启无 NPE 路径 ✅。

### 6.3 `installed` 日志只打一次
`LogX.i(... "MTG installed hooks=... enabled=... pool=... switch=...")`（`:138-142`）在 `install()` 末尾打一次；`handleLoadPackage` 每次进程启动调一次 install ✅。TAG 用消息前缀 `MTG`（`:61`），符合 `dev.py q 'MTG'` 验挂惯例（`LogX.kt:19` logcat TAG 固定 `WXGroup`）✅。

### 6.4 零重入
设 `param.result` 跳过原方法体 ⇒ 不会进入 `SQLiteDatabase.delete`/`execSQL` 内部再触发同方法 hook ✅；`fromExecSql` 不生成/拼接 SQL、不改 `param.args`（`:275-298`）⇒ 不会二次触发 ✅。账本只是 `AtomicLong`/`ConcurrentHashMap` 累加，无状态破坏。

**风险**：低。唯一非零开销是 `noteEvent` 的一次 `ConcurrentHashMap` 写（仅命中目标表后，媒体表 delete 低频）。

[DONE] C6

## C7 限流 / 去重 / 封顶 —— **PASS**

| 项 | 实现 | 锚点 |
|---|---|---|
| 账本封顶 | `MAX_EVENTS = 64`（事件明细去重表 `events` 超过后**不再新增 key**，只留累计计数） | `MediaTableGuard.kt:73, :345` |
| 日志封顶 | `MAX_LOG_KEYS = 64`、`LOG_TTL_MS = 60_000`（60s 按 key 节流） | `:74-75, :349-357` |
| 去重键 | `events` = `(d\|x)\|table.lowercase()\|where.take(32)`；`logAt` = 传入 key（`"cb-del"`/`"cb-sql"`/`"err"`/`"block"`） | `:337-338, :350-355` |
| 四计数语义 | `seen`=钩子被调次数（仅 enabled 时）；`matched`=命中目标表；`blocked`=真拦；`skipped`=命中目标表后按保守判据放行 ⇒ **`matched = blocked + skipped` 互斥可复算**，`seen >= matched` | `:101-104, :214, :223, :229, :237, :250` |
| `verdict` 三态互斥 | `when { seen<=0 → NO_EVENT; matched<=0 → HOOK_OK_BUT_NOT_MATCHED; else → REAL_EVENT }` **单值返回、非并列布尔**，与项目「互斥判据别写成并列」一致 | `MediaDeleteLogic.kt:267-271`；报告 S1.4 `:89-94` |
| K7 perf 刷屏下是否吃掉 5000 行环形日志 | 新日志**全部**走 `throttledLog`（60s + 64 key 封顶，最坏 ≈ 64 条/60s）+ `LogX` 自带 1s dedup（`LogX.kt:21`）+ `MAX_LINES=5000` 环形覆盖（`:22, :15`）⇒ 不会刷屏吃掉环形缓冲 ✅ | `MediaTableGuard.kt:349-357`；`LogX.kt:15,21-22` |

⚠️ 小瑕疵（非 FAIL）：`throttledLog` 的封顶只对**新 key** 生效（`:354` `if (last == null && logAt.size >= MAX_LOG_KEYS) return`），已有 key 每 60s 仍可再打；但 64 个 key 上限 ⇒ 最坏约 1 条/秒，可接受 ✅。另：开关关时 `onBefore` 第一行即 return，账本四计数**全部不增长**（`:212`），连 `events`/`logAt` 都不写 ✅。

**风险**：低。

[DONE] C7

## C8 判据正确性 —— **PASS**（附 `[需真机]` 取证项）

| 复核点 | 结论 | 锚点 |
|---|---|---|
| `classifyDelete` 四态真互斥 | ✅ `enum class DeleteClass{BLOCKABLE, BULK_OR_UNSAFE, OTHER_TABLE, UNKNOWN}` + 8 步 early-return **单值返回**，非并列布尔；顺序 `OTHER_TABLE → BULK_OR_UNSAFE → UNKNOWN → BLOCKABLE` | `MediaDeleteLogic.kt:36-49, :157-189` |
| `whereClause == null` 删全表放行 | ✅ `w==null \|\| w.isEmpty()` ⇒ `BULK_OR_UNSAFE` | `:166-167` |
| `<` `>` `LIKE` `IS NULL` `IN (SELECT …)` 一律放行 | ✅ `UNSAFE` 正则含 `\bselect\b\|\blike\b\|\bis\s+(not\s+)?null\b\|\bin\b\|!=\|<>\|\)<=\|>=\|<\|\>\|\band\b\|\bor\b\|,\|;`，命中即 `BULK_OR_UNSAFE`；另 `whereArgs.size>1` 也放行 | `:102-104, :169-170` |
| `ImgInfo2` 键列 vs 真机 24 列 | ✅ 点名的 `msglocalid` / `bigImgPath` / `thumbImgPath` 全部在 `IMG_SPEC.keyColumns`（`:77-80`），lowercase 归一后与真机列名一致；`id/msgSvrId/origImgMD5/midImgPath` 亦对得上。`hevcpath`/`originsourcemd5` 为多列候选（不在真机 24 列），只增不减地保守、不误拦 | `:76-82`；任务给定 pragma 24 列 |
| 26/32 位 token 按表分派 | ✅ `VOICE_SPEC.tokenLen=26`（`:69-72`）、`IMG/VIDEO/WXFILE=32`（`:76-94`）；`collectHex` 带 `(?<!hex)`/`(?!hex)` 边界断言（`:133-136`）；`isTargetTable("voiceinfo")` 恒 false ⇒ 语音表永不进 `classifyDelete` 的 token 抽取 | `:64, :69-94, :133-136, :145-148` |
| TTL 边界（等于 TTL 不算窗口内） | ✅ `isRevocationWindow` `:232` `return delta < ttlMs` **严格 `<`**；`:227-231` 另含 `ttl<=0`、时钟回拨、单位不自洽、溢出四道 false 闸门 | `:226-233` |
| 13 位 ms vs 10 位 s | ✅ `EPOCH_MS_MIN=1_000_000_000_000L`（`:61`）+ `looksLikeEpochMillis`（`:216`）+ `:229` 单位自洽闸门；`MediaTableGuard` 侧 `nowMs`/`lastIssuedMs` 统一 `SystemClock.elapsedRealtime()`（`:232, :158`）符合报告 `:400` 单位纪律 | `MediaDeleteLogic.kt:61, :216, :229`；`MediaTableGuard.kt:67-70, :158, :232` |

保守性红线承自 `VoiceGuardLogic.kt:23-24, :153`（「无条件拦 delete 会破坏微信 GC」）✅；`status/iscomplete/hashdthumb` 标量列故意不列入键列（`:75`）✅；`IN` 字面量列表也收紧为 `BULK_OR_UNSAFE`（`:98-100, :121`）比既有语音判据更保守 ✅。

⚠️ `[需真机]` 已知盲区（报告如实标注、均走「放行」安全方向）：
- `WxFileIndex2` 键列未取证（`MediaDeleteLogic.kt:89-94` 明标「待定」）⇒ 该表基本走 `UNKNOWN` 放行；
- 撤回删媒体表究竟走 `delete()` / `execSQL` / 预编译 `SQLiteStatement` 未取证（`MediaDeleteLogic.kt:33`）；
- 若真机证实撤回用 `IN (?,?)` 字面量或 `IN (SELECT…)` 单条形态，本判据会漏拦（有意保守）。

**风险**：低（功能覆盖不全，非误拦，不破坏微信 GC）。

[DONE] C8

## C9 单测是真验证吗 —— **PASS**

### 9.1 46 例真实存在（命令输出）
`Grep '^\s*@Test' MediaDeleteLogicTest.kt` 返回 **46 行**（`:52,62,76,86,92,102,108,117,127,134,142,155,162,174,181,187,193,198,208,218,227,235,244,253,261,271,279,293,298,304,309,314,320,328,336,342,348,361,366,372,377,383,394,401,407,414`）；`wc -l` = **426 行** ✅ 与报告 S3 的 46 条用例矩阵逐条同名 ✅。与自报「46 例 / 426 行」完全一致，无夸大。

### 9.2 四组点名用例是否真有（逐条核断言，均精确 `assertEquals`/`assertTrue`/`assertFalse`）
| 点名组 | 用例（行号） | 断言形态 |
|---|---|---|
| `voiceinfo` 必须不命中 | `nonTargetTablesAreRejected` `:62-74`（`isTargetTable("voiceinfo"/"VOICEINFO")==false`）、`otherTableIsOtherTableClass` `:155-160`（`cls("voiceinfo")==OTHER_TABLE`）、`shouldBlockRejectsNonTargetTable` `:342-346`（`block("voiceinfo")==false`） | 三处独立覆盖，均精确枚举/布尔 |
| 子查询放行 | `subqueryInSelectIsBulkOrUnsafe` `:134-140`（`IN (SELECT…)` ⇒ `BULK_OR_UNSAFE`）、`shouldBlockRejectsBulkForms` `:348-359`（组合入口同形态 ⇒ false） | 精确到 `DeleteClass`/布尔 |
| token 长度分派 | `extractTokensRejects26HexOnImageTable` `:235`、`extractTokensRejects32HexOnVoiceTable` `:244`、`extractTokensVoiceTableUses26` `:253`、`extractTokensRejectsWrongLengthAndIllegalChars` `:261`、`shouldBlockRejects26HexOnImageTable` `:377` | 精确 `emptySet`/`setOf`/布尔 |
| TTL 边界 | `revocationWindowAtTtlIsFalse` `:298-302`（`<=` 会变红）、`revocationWindowJustBeforeTtlIsTrue` `:304`、`shouldBlockRejectsOutsideWindow` `:366-370` | 精确布尔 + 边界值 |
| （额外）13 位 ms / 10 位 s | `revocationWindowRejectsMixedEpochUnits` `:320-326`、`looksLikeEpochMillisDistinguishesSeconds` `:328-332` | 精确布尔 |

### 9.3 6 组变异是否各自有对应用例变红（静态核对，非轻信自报）
报告 `docs/_r51_media_delete_logic.md:186-193` 输出 `BASELINE cases=46 GREEN=46 RED=0`，`M1=2 / M2=4 / M3=4 / M4=1 / M5=3 / M6=2`，`MUTATION_GROUPS=6 ALL_RED=True`。逐组对回磁盘用例：
- **M1 白名单恒真** ⇒ `nonTargetTablesAreRejected`（断言 voiceinfo 为 false）、`otherTableIsOtherTableClass`（断言 OTHER_TABLE）必红 ⇒ 2 ✅
- **M2 删 UNSAFE 的 select/like/is null/in/and/or/,/;** ⇒ `likeAndIsNullAreBulkOrUnsafe`、`subqueryInSelectIsBulkOrUnsafe`、`inLiteralListIsBulkOrUnsafe`（第二例 `IN (?, ?, ?)` 无 `=` ⇒ `SINGLE_EQ` 不匹配 ⇒ 变 UNKNOWN 而断言 BULK ⇒ 红）、`andOrMultiConditionIsBulkOrUnsafe` 必红 ⇒ 4 ✅
- **M3 `{len}`→`{26,32}`** ⇒ 4 条 token 长度用例必红 ⇒ 4 ✅
- **M4 `shouldBlock` 恒 false** ⇒ 仅 `shouldBlockFullChain` 必红 ⇒ 1 ✅
- **M5 `verdict` 判据变量互换/并列** ⇒ 3 条 verdict 用例必红 ⇒ 3 ✅
- **M6 `<`→`<=`** ⇒ `revocationWindowAtTtlIsFalse`、`shouldBlockRejectsOutsideWindow`（`now=11000,issued=1000,ttl=10000` ⇒ `delta==ttl`，`<=` 会让 `block` 返回 true）必红 ⇒ 2 ✅

⇒ **6/6 组各自变红、无 0 条组**；脚本 `:334` `assert old in BASE` 防假变异、`:340-343` 自检 baseline 全绿 + 精确断言、`:344` 如实标注「未设 M0 等价变异对照组」（项目先例有）、`:345/:402` 如实标注「Python 复刻 ≠ Kotlin 编译」⇒ **不是等价变异假验证，自报与磁盘一致、无夸大** ✅。主会话已补跑 `compileDebugUnitTestKotlin` BUILD SUCCESSFUL + `tools/_junit.sh` OK (864 tests = 818 基线 + 46 新) ✅ 与自报一致。

**风险**：低。唯一如实标注的边界是判据 lane 禁跑 gradle、变异是 Python 静态复刻（已由主会话编译/单测补齐）。

[DONE] C9

## C10 三档边界 + 回滚 —— **PASS**（附 1 处文档表述张力）

### 10.1 三档是否都有设计说明
实现报告 `docs/_r51_media_table_guard_impl.md` S4（`:220-247`）：
- **① 进入**（刚撤回那一刻）：`:228` 完整链路（`AntiRevoke` 拦 UPDATE → `RevokeRescue.record()` → `protectMedia` → `MediaTableGuard.invalidate()` → 媒体表 DELETE 命中 → `param.result` 跳过）✅
- **② 返回**（隔几分钟点开 / 切页回来 / 下拉刷新 / 冷启）：`:229`，且 **②-a TTL（时间维度）`:230` 与 ②-b 冷启（进程维度）`:231` 各自单列、不与「切页回来」合并** ✅
- **③ 复位**：`:233-246` 逐条独立成表（9 行），每行有「与返回档的本质差异」列 —— 关 `antirevoke.media_delete` / 关 `antirevoke.msg` / 保护池 10 分钟 TTL / imgAnchor 30 分钟 TTL / 进程内账本冷启清零 / hook 随进程死才卸 / 微信重试删除 / 清缓存 / **已被删的 DB 行·文件不可回滚** ✅ ⛔ **未写成「同返回」** ✅

判据报告 `docs/_r51_media_delete_logic.md` 5.1/5.2/5.3（`:353-382`）同样三档、TTL 与冷启单列、复位逐条 ✅。

### 10.2 三条硬要求是否写明
| 要求 | 结论 | 锚点 |
|---|---|---|
| 进程内账本冷启清零 | ✅ `seen/matched/blocked/skipped` 4 个 `AtomicLong` + `events`(≤64) + `logAt`(≤64) + `cachedTokens` + `gen` + `lastIssuedMs` 全进程内，冷启整体清零、`verdict` 回 `NO_EVENT` | impl `:231, :241`；判据 `:357, :375-381` |
| hook 随进程死才卸 | ✅ `install()` 无 unhook；关开关 / TTL 过期 / 清缓存都不卸载，只空转；仅进程死亡才真正消失 | impl `:242, :377` |
| 已被删的 DB 行 / 文件不可回滚 | ✅ 单列一行，明写「只能在 before 阻止删除发生、无任何 restore 能力」、图片侧无 backup/restore | impl `:245`；判据 `:370` |

### 10.3 回滚路径可执行性
impl `:255-260`：① 删 `HookEntry.kt` 注册行 + import（对应真实 `+2`）② 关开关（默认已 false）③ 删 `MediaTableGuard.kt` + 本报告 2 个新文件 ④ 清 `RevokeRescue.kt` 1 行 + `Keys.kt` 1 常量 + 1 `PrefSpec` ⑤「不用冷启」⑥ 不残留微信库写入、不碰 `SnsSqlRewrite.kt`。逐条对回 `git diff` ⇒ **可执行** ✅。判据 lane 的 `MediaDeleteLogic.kt`/`MediaDeleteLogicTest.kt` 为零行为变化纯函数、不在「2 个新文件」清单内（可保留），合理 ✅。

⚠️ **表述张力（非 FAIL）**：`:259, :262, :294` 的「⛔ 不用冷启」仅在**「只关开关、不删源码」**语义下成立（before 空转、账本进程内随进程死消失）；若要**移除 hook 注册**（删 `HookEntry` 行），需重新编译 + 重装 + 微信进程重启 —— 这与同报告 `:242`「hook 随进程死才卸」一致，文档把两者并列略含混。标 `[推断]`，属文档表述，不影响装包安全。

**风险**：低（文档层面）。真机回滚时建议区分「运行时降级（关开关，不冷启）」与「彻底移除代码（需重装+重启）」两种语义。

[DONE] C10

## C11 装包安全性 —— **PASS**（附崩溃最小排查 / 回滚步骤）

### 11.1 `install()` 位置与加载瞬间崩溃面
- `MediaTableGuard.install(lpparam)` 在 `HookEntry.kt:225`，处于 `handleLoadPackage` 体内 `:223-226` 的 `runCatching{}` 块（与 `VoiceGuard.install` 同块），其外层再有 `.onFailure { LogX.e(...) }`（`:226`）✅
- `install()` 内部逐段 `runCatching`：`findClass`（`:114-116`）、`refresh`（`:121`）、`resolvePool`（`:308-314`）、两个 `hookAllMethods`（`:127-132`）、`ok.isEmpty` 兜底（`:133-136`）；`LogX.i` 在 install 末尾（`:138`）但整体被 `HookEntry` 的 `runCatching` 包住 ✅
⇒ **模块加载瞬间没有会崩微信的裸逻辑** ✅。

### 11.2 开关默认关是否意味着「装上去即使钩子有 bug 也不影响微信」
✅ 基本成立：`enabled=false` 时 `onBefore` 第一行 `if (!enabled) return`（`:212`），不设 `param.result`、不改 `param.args`、不写库、不解析、不记日志、账本不增长；两个 callback 双层 try/catch 兜底 ⇒ 即使判据有 bug 也不会外泄 ✅。**边界**：`install()` 期仍会执行 `findClass` / `resolvePool` 反射 / `hookAllMethods` 注册（均 runCatching），这些是启动期一次性、失败只降级打日志，不会崩 ✅。

### 11.3 ⛔ hook 注册在开关关时仍执行 ⇒ 风险评估
`hookAllMethods(cls, "delete", deleteGuard)`（`:127`）与 `hookAllMethods(cls, "execSQL", execSqlGuard)`（`:130`）**无条件执行**，与开关值无关。风险：
- `execSQL` 是微信极热路径 ⇒ 每次调用多一个 before callback（一次 `@Volatile` 读 + return），**开销小但非零**，`[需真机]` 观察冷启后卡顿/日志；
- 若宿主类方法签名在该版本变化导致 `hookAllMethods` 抛异常 ⇒ 被 `runCatching` 吞 + `LogX.e`，不崩、只是本功能不挂 ✅；
- `returnEarly` 语义下拦时会跳过后续 before callbacks（见 C1），已由表名白名单互斥兜住 ✅。

⇒ 该风险为**性能/可观测性**层面，非崩溃/现网语义层面，标 `[需真机]`，**不构成 FAIL**。

### 11.4 装包后若微信崩溃：最小排查与回滚步骤（⛔ 不含改代码，仅排查/回滚）
1. `dev.py q 'MTG'` 看 `installed hooks=delete,execSQL enabled=false pool=ok|reflect-failed switch=antirevoke.media_delete` 是否出现、是否只打一次；`dev.py q 'VG|VFG|AR'` 复核真机基线三件（`AR installed` / `VG installed hooks=delete enabled=true` / `VFG installed hooks=delete,deleteOnExit`）是否齐全 —— 用来区分「崩溃发生在 MTG install 之前/之后」。
2. 若崩溃在冷启瞬间：检查是否伴随 `pool=reflect-failed` / `refresh failed` / `cb-del|cb-sql err`；这些路径都已降级或吞异常，正常不应导致崩溃。
3. **运行时降级**：关 `antirevoke.media_delete`（`kvset`）⇒ before 空转。注意 C4 的事件驱动刷新窗口 —— 必要时触发一次撤回或重启微信才把 `enabled` 刷成 `false`。
4. **彻底回滚**（按 impl 报告 S5）：删 `HookEntry.kt` 2 行（import + 注册）、`RevokeRescue.kt` 1 行、`Keys.kt` 2 行（常量 + PrefSpec）、移除 `MediaTableGuard.kt`（+ 报告），重新编译装包，**微信进程重启**（hook 随进程死才卸）—— 与 C10 的「彻底移除需重装+重启」一致。
5. ⛔ 全程不写微信库、不改任何 DB 行、不做 native 操作；回滚后 `antirevoke.msg=true` 的既有链路逐字原样。

### 11.5 装包范围提示
本次 `adb install` 是**全量工作区**：除 R51 的 5 源码 + 1 单测外，R49 阶段二既有未提交改动（`SnsGuard.kt +7` / `SnsBlockProbe.kt +8` / `EntryReceiver.kt +11` / 新建 `SnsWriteProbe.kt` / `SnsWriteProbeTest.kt`）也会编译进 APK，均为默认关探针。按任务硬约束**排除出本轮复核**，但提示主会话知悉（见 X2/X3）。

**风险**：中低（热路径多一个 callback + 装包含其它 lane 既有改动）。

[DONE] C11

## 复核汇总表（C1~C11）

| 项 | 裁定 | 一句话依据（锚点） |
|---|---|---|
| C1 ⭐⭐ callback 顺序 / execSQL 语义 | **PASS** | 注册序 `HookEntry.kt:224→225→243`；双层 try/catch 不外泄（`MediaTableGuard.kt:177-196,209-260`）；`param.result=null`→`setResult`→`returnEarly=true` 能跳过 void 方法体 `[推断]`；靠白名单互斥兜住 returnEarly 跳过后续 callback ⇒ 零影响。报告 `:128` 推断有误但不改结论 |
| C2 try/catch 兜底 | **PASS** | callback + `onBefore` 双层 `catch(Throwable)`，吞异常不 throw、不设 result ⇒ 放行 |
| C3 反射取 private 池 | **PASS** | `VoiceFileGuard.kt:109` 字段核实（private 实例字段、CHM、只 put/remove 不重赋值）；`as?` 安全无 CCE；`protectMedia` 在 `invalidate` 之前（`RevokeRescue.kt:122 vs :128`）；失败降级只记账永不拦 |
| C4 默认关零开销 | **PASS** | `Keys.kt` PrefSpec `"false"`；`onBefore:212` 第一行即 return；无 3s TTL、无 before 查库；⚠️ 事件驱动刷新存在 ≤10min 窗口（已如实记录） |
| C5 R42 / 语音现网零影响 | **PASS** | 5 个现网文件 R51 零改动（`SnsGuard.kt +7` 属 R49 阶段二、已排除）；只设 `param.result`；白名单不含 `voiceinfo` |
| C6 before 零查库 / 零重入 | **PASS** | 全路径纯内存/字符串解析，无 DB/反射/IO；`findClass` runCatching；`installed` 只打一次 |
| C7 限流 / 去重 / 封顶 | **PASS** | 64/64/60s + `matched=blocked+skipped` 可复算 + `verdict` when 单值互斥；LogX 1s dedup + 5000 环形 |
| C8 判据正确性 | **PASS** | 四态互斥；null/比较符/LIKE/IS NULL/IN(SELECT) 全放行；`ImgInfo2` 三个点名键列对得上；26/32 分派；TTL 严格 `<`；13/10 位闸门 |
| C9 单测是真验证吗 | **PASS** | Grep 得 46 个 `@Test`、426 行；四组点名用例齐全且精确断言；6 组变异 2/4/4/1/3/2 静态对回用例必红、无 0 条组 |
| C10 三档边界 + 回滚 | **PASS** | 进入/返回/复位齐备、TTL 与冷启单列、复位不写「同返回」；账本冷启清零 / hook 随进程死才卸 / 不可回滚均写明；回滚可执行；⚠️「不用冷启」措辞张力 |
| C11 装包安全性 | **PASS** | `install` 在 `handleLoadPackage` 的 `runCatching` 内、内部逐段兜底；默认关空转；hook 常驻注册为热路径小开销 `[需真机]`；给出崩溃排查/回滚步骤 |

---

## X1 两份报告 `[DONE] ALL` 自报与磁盘一致性

| 核对项 | 结果 |
|---|---|
| `[DONE] ALL` 是否真实落盘 | ✅ `_r51_media_table_guard_impl.md:297`、`_r51_media_delete_logic.md:432` 均存在；`[START]` 分别在 `:38` / `:15`，分步 `[DONE] S0~S6` / `[DONE] S0~S5` 齐备 |
| 自报「46 例 / 426 行 / 6 组变异 2/4/4/1/3/2 / baseline 46 绿」 | ✅ 与磁盘一致（Grep 46 个 `@Test`、`wc -l` 426；报告 `:186-193` 输出与 S3 矩阵逐条同名）；无夸大 |
| 自报「+2 / +1 / +2 / 新建 / 新建」 | ✅ 与 `git diff --numstat` 一致（HookEntry +2、RevokeRescue +1、Keys R51 部分 +2）；`MediaTableGuard.kt` 358 行、`MediaDeleteLogic.kt` 272 行与自报一致 |
| 自报「`Keys.kt` 共 20++ 里只有 2 行是本 lane，其余是其它并行 lane」 | ✅ **如实区分、未夸大**（impl `:200`：`EntryReceiver.kt`/`SnsGuard.kt`/`SnsBlockProbe.kt` 的改动明确标为其它 lane、非本 lane 所写）。逐行核对 diff：`ANTIREVOKE_MEDIA_DELETE` const + PrefSpec 属 R51，`SNS_WRITE_PROBE` KDoc 块 + const 属 R49 ✅ |
| 自报「判据 lane 产物仅 3 个文件、未改任何既有源码」 | ✅ 与 `git status` 一致（未跟踪 `MediaDeleteLogic.kt`、`MediaDeleteLogicTest.kt`、本报告；无既有源码 M） |
| 自报「未跑 gradle / 未装包 / 未 git add/commit」「Python 复刻 ≠ Kotlin 编译」 | ✅ 如实标注（logic `:4, :183, :345, :402, :420`）；主会话已补跑编译与 864 tests，与自报不冲突 |

⛔ **两处需指出的偏差（属文档推断/表述，非源码缺陷，不推翻 `[DONE] ALL`）**：
1. **技术推断错误**：impl `:22, :128, :270` 称「某 callback 置 `param.result` 后 `returnEarly=true` 会阻止原方法体，但**其余 before callback 仍会跑完**」—— 与 Xposed 实际机制相反（置 result 会 `break`，跳过剩余 before callbacks），详见 **C1.4**。实现侧靠双层 try/catch + 表名白名单互斥兜住，现网零影响，但该表述应纠正。
2. **表述张力**：impl `:259, :262, :294`「⛔ 不用冷启」与 `:242`「hook 随进程死才卸」并列，易把「只关开关的运行时降级」与「删源码彻底回滚（需重装+重启）」混为一谈，详见 **C10.3**。

⇒ **X1 结论：两份 `[DONE] ALL` 自报与磁盘一致，数字/行数/零改动声明均无夸大；仅上述 2 处文档推断/表述需纠正。**

[DONE] X1

---

## X2 独立只读 `git diff --stat -- app/src/main` / `git status --short` 的真实改动清单

### 2.1 命令输出（本 lane 独立实跑，全只读）
```
$ git rev-parse --show-toplevel
C:/Users/lx/WorkBuddy/2026-09-29-20-23-21          ← git 根在顶层，项目在 wxgroup/ 子目录

$ git diff --stat -- app/src/main
（无输出）                                          ← 任务原样命令；路径不含 wxgroup/ 前缀故为空

$ git diff --stat -- wxgroup/app/src/main
 .../HookEntry.kt    |  2 ++
 .../data/Keys.kt    | 20 ++++++++++++++++++++
 .../entry/EntryReceiver.kt  | 11 +++++++++++
 .../feature/RevokeRescue.kt |  1 +
 .../feature/SnsBlockProbe.kt |  8 ++++++++
 .../feature/SnsGuard.kt     |  7 +++++++
 6 files changed, 49 insertions(+)

$ git diff --numstat -- wxgroup/app/src/main
2 0 HookEntry.kt / 20 0 Keys.kt / 11 0 EntryReceiver.kt / 1 0 RevokeRescue.kt / 8 0 SnsBlockProbe.kt / 7 0 SnsGuard.kt

$ git diff --cached --stat
（无输出）  ← 无暂存内容

$ git status --short
 M docs/_r48_autoplay_conclusion.md
 M "docs/社交审计封禁.md"
 M wxgroup/app/src/main/java/com/lx/wxgroup/HookEntry.kt
 M wxgroup/app/src/main/java/com/lx/wxgroup/data/Keys.kt
 M wxgroup/app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt
 M wxgroup/app/src/main/java/com/lx/wxgroup/feature/RevokeRescue.kt
 M wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsBlockProbe.kt
 M wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsGuard.kt
?? docs/_r49_*（一批）/ _r50_*（一批）/ _r51_impl_review.md / _r51_media_delete_logic.md / _r51_media_table_guard_impl.md
?? wxgroup/app/src/main/java/com/lx/wxgroup/data/MediaDeleteLogic.kt
?? wxgroup/app/src/main/java/com/lx/wxgroup/feature/MediaTableGuard.kt
?? wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsWriteProbe.kt
?? wxgroup/app/src/test/java/com/lx/wxgroup/data/MediaDeleteLogicTest.kt
?? wxgroup/app/src/test/java/com/lx/wxgroup/feature/SnsWriteProbeTest.kt
```

### 2.2 真实改动文件清单（按归属分类）
**A. R51 本轮授权范围 —— 恰好 5 个源码 + 1 个单测 ✅**
| 文件 | 状态 | 内容 |
|---|---|---|
| `data/MediaDeleteLogic.kt` | ?? 新建 272 行 | R51 判据 |
| `feature/MediaTableGuard.kt` | ?? 新建 358 行 | R51 接线 |
| `HookEntry.kt` | M +2 | R51：1 import + 1 `install()` |
| `feature/RevokeRescue.kt` | M +1 | R51：图片/视频分支 `invalidate()` |
| `data/Keys.kt` | M（+20 中的 R51 部分 +2） | R51：`ANTIREVOKE_MEDIA_DELETE` const + `PrefSpec("false")` |
| `test/.../MediaDeleteLogicTest.kt` | ?? 新建 426 行 / 46 例 | R51 单测 |

**B. ⛔ R49 阶段二既有未提交改动 —— 明确排除，不算进本轮**
`entry/EntryReceiver.kt +11`（`writeprobe` 分支）、`feature/SnsBlockProbe.kt +8`、`feature/SnsGuard.kt +7`、`feature/SnsWriteProbe.kt`（新建）、`test/.../SnsWriteProbeTest.kt`（新建），以及 `Keys.kt` 中 `SNS_WRITE_PROBE` 的 KDoc+const（+18 行）。

**C. 其它文档（非源码、非本轮）**
一批 `?? docs/_r49_*` / `_r50_*` 文档、两份 R51 报告、本报告，以及 2 个已跟踪修改的旧文档（`_r48_autoplay_conclusion.md`、`社交审计封禁.md`）—— 均非本轮 R51 源码。

### 2.3 结论
- **没有超出授权的 5 个源码文件（±1 单测）** ✅；R51 与 R49 在 `Keys.kt` 中逐行区分清楚 ✅。
- ⚠️ 任务原样命令 `git diff --stat -- app/src/main` 输出为空是**路径前缀问题**（git 根在顶层、项目在 `wxgroup/`），已补充带前缀的真实输出，未隐瞒。
- ⚠️ 装包是全量工作区：B 类 R49 阶段二改动会一并编译进 APK（默认关探针），按任务硬约束排除复核，仅提示主会话知悉。

[DONE] X2

---

## X3 装包前必改项清单（P0 / P1）

### ⛔ P0（阻断装包）：**没有**
C1 裁 **PASS**，不触发「FAIL ⇒ 不可以装包」分支；11 项全 PASS；无会导致微信崩溃 / 破坏现网 R42 / 语音拦截 / 微信 GC 的源码缺陷。

### P1（非阻断；只列风险与建议，⛔ 不指挥主会话改代码）
| # | 优先级 | 项 | 类型 |
|---|---|---|---|
| 1 | P1 | impl 报告 `:22, :128, :270` 对 `returnEarly` 的 `[推断]`（「其余 before 仍会跑完」）与 Xposed 机制不符 ⇒ 建议把表述纠正为「置 result 会跳过后续 before callbacks，靠双层 try/catch + 白名单互斥兜住」 | 文档推断（C1.4 / X1） |
| 2 | P1 | `[需真机]` 验证 `param.result = null` 对 void `execSQL` 是否确实跳过方法体、微信是否重试删除 / 抛类型转换异常（impl S1.4 4 条）。若不生效 ⇒ execSQL 通道漏拦（安全方向，不破坏微信） | 真机取证（C1.3） |
| 3 | P1 | `enabled` 仅 `install()`/`invalidate()` 刷新 ⇒ 关闭开关后存在受 `WINDOW_MS=600_000` 约束（≤10 分钟）的「仍可能拦」窗口；真机验证「关开关即时生效」或明确接受该设计取舍 | 行为验证（C4） |
| 4 | P1 | impl S5「⛔ 不用冷启」与「hook 随进程死才卸」表述张力 ⇒ 建议区分「运行时降级（关开关，不冷启）」与「彻底移除代码（需重装 + 微信进程重启）」 | 文档表述（C10.3） |
| 5 | P1 | 本次 `adb install` 为全量工作区，含 R49 阶段二既有探针改动（默认关）⇒ 主会话知悉并确认属预期内装包内容 | 装包范围（X2 / C11.5） |
| 6 | P1 | `[需真机]` 阶段 0 取证：撤回删媒体表走 `delete()` / `execSQL` / 预编译 `SQLiteStatement`、`WxFileIndex2` 键列、拦住 DB 行后 UI 是否真显示大图 —— 均为功能覆盖取证，走「放行」安全方向，非装包阻断 | 真机取证（C8） |

[DONE] X3

---

## 最终二元结论

# 可以装包

**依据**：
1. **C1 ⭐⭐ PASS**：callback 注册顺序明确（`HookEntry.kt:224→225→243`）；新 callback 双层 `try/catch(Throwable)` 吞异常、不外泄到 Xposed 框架；`param.result = null` 经 `setResult → returnEarly=true` 依框架契约**确实能阻止 void `execSQL` 方法体执行**（与 `XC_MethodReplacement` 的「跳过原方法」语义等价，但保留条件化能力）；置 result 会跳过后续 before callbacks，由**表名白名单三方互斥 + 双层 try/catch** 兜住 ⇒ 不触发「FAIL ⇒ 不可以装包」。报告一处 `[推断]` 表述有误，已在 C1.4/X1/P1-1 记录，不改结论。
2. **无 P0 必改项**（X3）：11 项全 PASS。
3. **现网语义零影响**：默认关（`PrefSpec "false"`）+ 关时 before 仅一个 boolean 判断（零解析/零分配/零日志/零反射/零查库）+ 不改 `ContentValues`/SQL 原文/`param.args`/除「跳过删除」外任何返回值 + 不新增微信库写 + 白名单不含 `voiceinfo` + 反射失败降级「只记账永不拦」+ 限流去重封顶 ⇒ 现网 R42 朋友圈写时拦截 / 语音拦截 / 微信 GC 全部不受影响。
4. 保留 `[需真机]` 取证项与 P1 建议（execSQL void 行为、删表通道、`WxFileIndex2` 键列、UI 显示、开关即时生效、装包含 R49 既有改动），均属功能覆盖 / 文档表述 / 可观测性层面，非装包阻断。

> 复核全程只读：未改任何源码 / 测试 / 配置 / 其它报告；未碰 adb / 真机 / Gradle / 装包 / kvset / git add/commit；唯一写入文件为本报告。好友一律用昵称，无裸 wxid。

[DONE] ALL

