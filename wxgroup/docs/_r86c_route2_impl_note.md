# R86c 转发图片清晰度 —— 第4轮最终规格落码 note

> 执行者：`r86c-route2-impl`
> 档位：Pro（Hook 链路重构 + 反射字段读写 + 跨线程 ConcurrentHashMap 隔离 + 字节判据 + 变异单测）
> 唯一权威规格：`../docs/_r86c_r0e_route2_verdict.md` 文首「第4轮修订 R1-R4」与 §3.1~§3.7
> 纪律：只改代码/单测/文档；禁碰 adb、装机、设备开关、git commit/push。
> 落盘策略：骨架 → 每完成一文件即写盘 → 三闸各留结论 → `[DONE ALL]`。被中断则留已完成部分，从断点续跑。

## 0. 改动清单

### 已删除（M1 死码）
- `app/src/main/java/com/lx/wxgroup/core/J2R0eOverrideHook.kt`（368 行；j2.R after + `resolveOriginLocal` 依赖 r0 String 含 `/MicroMsg/`，独立复核判 M1 BLOCKER，结构不可修）—— **已物理删除**（未跟踪文件，`git ls-files` 无记录，直接删）。
- `app/src/test/java/com/lx/wxgroup/core/J2R0eOverrideHookTest.kt`（13 例旧结构文本断言，假绿温床）—— **已物理删除**。

### 源码改动
- [x] `core/ForwardCdnFieldHook.kt` —— 已重写：单槽 `pending` → `ConcurrentHashMap<String, Pending(ts)>`；新增 `hevcEnabled` 新 Key 默认值 + `turn()/status()`；新增 `markTarget(wxid)`（仅登记 key）；`arm()` 只保留只读探针链；`inject()` 改为 `field_talker` keyed + TTL + `remove(key,p)` 原子消费 + 模糊态 9554B/0ef205c1 门禁 + `root/image2` 两级分桶有限枚举 + 命中数==1 才写 `field_fullpath`；`clear(wxid)` 精确清；只读探针 `probeEnabled/turnProbe/probeR86c/r0Fields/idh/md5PrefixOf/sigOf` 保留。
- [x] `core/FileSender.kt` —— `markTarget(wxid)`（**目标会话**，在 `arm` 之前）；`queued=false` 与 `onFailure` 均改 `clear(wxid)`；`arm(ctx.sourceChat,...)` 保留；`q1.j/hl0.m/q1.a()/g6/l1.a()` 序列逐字节未动。
- [x] `HookEntry.kt` —— 删 J2R0e import 与 install 整块；保留 `ForwardCdnFieldHook.install(lpparam)`。
- [x] `data/Keys.kt` —— `FORWARD_R0E_HEVC` 替换为 `FORWARD_TASK_FULLPATH_HEVC = "chat.forward_task_fullpath_hevc"`（默认 false），注释改为 m1.e before / field_fullpath / 45522B / cff4f5a5；只留一个 Key。
- [x] `entry/EntryReceiver.kt` —— 删 `r0e` 分支，新增 `hevc`（`on|off|stat` → `turn()/status()`）；保留 `r86c`/`stockcdn` 只读探针分支。
- [x] `app/src/test/.../ForwardCdnFieldHookTest.kt` —— 按裁决 §3.6 全部反向断言重写，含五项真实文本变异判红用例。
- [x] `app/src/test/.../probe/R86cQ4bProbeTest.kt` —— **仅维护块边界锚点**（`resolveOriginFullpath` 已删、`clear()` 改 `clear(wxid)`，故 r0Block 改切到 `sigOf` 前、armBlock 改切到 `turnProbe` 前）；16 锚点 + 4 硬锁断言内容一字未动，探针链仍真实受锁。

## 1. 进入/返回/复位三档自检

### 进入（全部满足才写 `task.field_fullpath`）
1. `hevcEnabled==true`：`install()` 冷启读 `Keys.FORWARD_TASK_FULLPATH_HEVC`（默认 false）；`op=hevc sub=on` 运行时翻转；before 首行 `if (!hevcEnabled) return`（off 时零反射/零 IO/零写）。
2. before 回调在 `ok0/m1.e(tj/k)Z`：`findClass("ok0.m1")` + 精确签名 `tj.k` + `Boolean.TYPE`；复用**已挂载** hook，未新增第二个 hook。
3. `param.args?.getOrNull(0)` 取到 task `tj/k`。
4. `getObjectField(task,"field_talker")` 非空（非 blank）。
5. `pendingMap[field_talker]` 命中 → TTL 内（`now - p.ts <= 2000L`）→ `pendingMap.remove(field_talker, p)` 原子竞争成功。
6. 模糊态门禁：`task.field_fullpath` 原文件 `exists() && length()==9554L && md5PrefixOfFile()=="0ef205c1"`。
7. 取根来自 `task.field_fullpath` 的 `/MicroMsg/<32hex>/` 正则；`root+"image2/"` 两级分桶有限枚举 `*.jpg_hevc`；`length()==45522L && md5PrefixOfFile()=="cff4f5a5"` 命中数 **==1**。
8. 只写 `task.field_fullpath` 一个字段（全 hook 仅 1 处 `setObjectField`）；不写 fileId/aesKey/originPath/r0.e/r0.f；不碰 q1.j / P3AdmitGate / FLOOR / copyP3Temp / chosenP3Src / q1.a() 调用序 / b1.u8 / DB。

### 返回（任一不满足立即 return）
开关 off / 无 task / talker 空 / pending 未命中 / TTL 超 / 并发已被别人消费 / 原态门禁不过 / hevc 命中 0 或 >1 / 任何反射异常。
**返回时绝不写 task 任何字段，绝不 remove 别人的 pending 条目**：未命中直接 `?: return`；TTL/消费只用 `remove(field_talker, p)` 条件删除自己那条；字节判定全程 `runCatching`；`inject` 外层回调也全包 `runCatching`。

### 复位
- 广播 `op=hevc sub=off` 立即停写（只翻内存 `@Volatile`，不写库、不持久化）。
- TTL 到期自动失效（条件 remove 自己条目）。
- `FileSender` 在 `queued=false` 与 `onFailure` 两条失败路径按 `wxid` 精确 `clear(wxid)`，不清全表、不误删并发目标。
- 冷启按新 Key 默认值复位为关。
- `send()` 外层 `runCatching` 不受 inject 异常影响；`arm()` 异常只丢本次探针。
- 不写临时文件、不 cleanup hook、不 onGYNetEnd 钩子——直接指向已存在的 hevc 文件。

## 2. 五项变异判红结果

单测内 5 个变异用例以真实文本变异 + 判定函数判红 + 自变量唯一断言实现；另做**真实文件变异**（改源文件 → `compileDebugUnitTestKotlin` → 定向单测 → 还原 → 重编译）逐项复核：

| # | 变异 | 编译退出码 | 定向单测退出码 | 结果 |
|---|---|---|---|---|
| M1 | `REF_MD5_PRE` `"cff4f5a5"` → `"deadbeef"` | 0 | 1 | 13 tests / 4 Failures → **判红** |
| M2 | hook 点 `findClass("ok0.m1")` → `findClass("...modelimage.j2")` | 0 | 1 | 13 tests / 4 Failures → **判红** |
| M3 | 删命中数==1：`hits.size == 1` → `hits.isNotEmpty()` | 0 | 1 | 13 tests / 4 Failures → **判红** |
| M4 | 删 `field_talker` key 命中改无条件写（去掉 `pendingMap[field_talker] ?: return` 与条件 remove） | 0 | 1 | 13 tests / 4 Failures → **判红** |
| M5 | 删模糊态门禁（9554B/0ef205c1 两行改 `Unit`） | 0 | 1 | 13 tests / 4 Failures → **判红** |
| 还原 | `shutil.copy2` 还原 + 重编译 | 0 | 0 | **OK (13 tests)** |

五项均真改源码文本且各自至少 1 条测试变红（实测各 4 条），无等价变异；变异锚点断言 `occur(...)==1` 保证自变量唯一。临时备份文件已删除，工作树已还原。

## 3. 三闸结果

工作目录 `wxgroup/`，runner 路径 `../tools/`（已核实，非 `wxgroup/tools`）。

| 闸 | 命令 | 退出码 | 关键输出摘要 |
|---|---|---|---|
| 准备编译 | `python ../tools/gradle_run.py compileDebugUnitTestKotlin` | 0 | `BUILD SUCCESSFUL in 15s`（arm 调整后重编译） |
| 闸1 定向 | `bash ../tools/_junit.sh com.lx.wxgroup.core.ForwardCdnFieldHookTest` | 0 | `OK (13 tests)` |
| 闸2 全量 | `bash ../tools/_junit.sh` | 0 | `OK (1190 tests)` |
| 闸3 构建 | `python ../tools/gradle_run.py assembleDebug` | 0 | `BUILD SUCCESSFUL in 16s`（未跑 `build`） |

首跑全量曾 14 Failures，全部来自 `R86cQ4bProbeTest`：根因是该测试用源码函数边界切块，`resolveOriginFullpath` 物理删除与 `clear()` 改签名导致 `r0Block`/`armBlock` 切片为空（探针链本身仍在）。已恢复 arm 中 `if (probeEnabled) probeR86c(...)` 与只读 `a3` 取证调用的相邻结构，并把测试块边界更新到 `sigOf` / `turnProbe` 前；断言内容未动。修复后全量 1190/1190 OK。

## 4. UNKNOWN 与诚实边界

- 写后肉眼清晰仍是 **【推断·中】**（B2/R79 竞争理论未消解）；本轮只做静态落码与 JVM 回归，未真机 A→B→A 终审。
- `field_talker` 值对应 **【真机实证·中】**（step1b `:4059 field_toUser=filehelper` 与 FileSender `wxid=filehelper` 间接闭合；V2 m1.e 探针未直接打印完整值，日志只打 take(24)）。
- 同 `field_talker` 并发多图 **【UNKNOWN 未闭合】**：keyed map 无法逐帧区分，当前架构前提是单目标/单 talker 串行，由主会话自裁；若必须支持并发多图则按裁决门槛0 改判 NO-GO。
- 模块转发当刻 `cff4f5a5` hevc 是否在场且唯一 **【UNKNOWN 未闭合】**（q4b :293）。
- 「45522B 是 9554B 的视觉同内容清晰版」 **【推断·中】**。
- 本轮未碰 adb / 装机 / 设备开关 / git commit·push；判据只对肉眼与文件字节，不用 `hit`/`UploadHitCacheType`。
- 用户级长期规则：真机测试前必须交叉验证 —— 本任务的三闸（含五项真实文件变异复核）+ 后续独立 Pro 复核即为交叉验证，不跳过。

[DONE ALL]

---

# 修正轮（依据独立复核 `docs/_r86c_route2_review.md` 的 NO-GO）

> 执行者：`r86c-route2-fix2`；档位 Pro；模型 HY4-FLASH-FREE 用户临时规则（不写入任何文件）。
> 处置范围：复核 B1（TTL BLOCKER）、B2（重试帧 × 一次性消费 MAJOR）、M1（同步全树 MD5 阻塞 MAJOR）、
> Q2.8-4（墙钟 TTL MINOR）、Q2.8-5（Key 未注册 MINOR）。**未碰 adb / 装机 / 设备开关 / git commit；未跑真机。**

## 5. 三项改动做法与理由

### ① TTL BLOCKER（Q1 / B1）
- `ForwardCdnFieldHook.kt:50` `TTL_MS` `2000L → 15000L`。复核逐行亲核 6 段间隔含 6345ms / 10071ms，
  真机迟到帧确属本次转发 ⇒ 2s 必超时清零 ⇒ 零生效。取 15s = >10.071s×1.5，并为同转发 host 重传
  两帧间隔 10.858s 留余量；**不用 30/60s**（残留窗口过长）。
- 时钟：`markTarget` 与 TTL 判定 `:146/:170` 均由 `System.currentTimeMillis()` 改
  `SystemClock.elapsedRealtime()`（新增 `import android.os.SystemClock`），防 NTP 校准 / 手改系统时间
  造成假通过（负间隔）或假超时（暴增间隔）。已全仓核实 `SystemClock` 在同模块多处使用，无依赖问题。

### ② 重试帧 × 一次性消费 MAJOR（Q2.8-2 / B2）—— 选方向 (b)
**选择 (b)：按 `(talker, 原图 basename)` 有界重登记（先条件消费、写入成功后再按同 basename 有界重登记 + 次数上限）。**

> **【二次复审措辞订正】** 上文初稿曾写"消费时机后置到写入成功之后"，**与代码不符**：实际 `:193` 条件 `remove` 发生在 `:195` `findTargetHevc` / `:196` 写字段**之前**，是"**先消费、写成功后再重登记**"，不是"写成功后才消费"。影响：若首帧 `findTargetHevc` 返回 null（0 命中/歧义/门禁不过），`:195 ?: return` 在已 remove 后提前返回、**不重登记** ⇒ 重传帧无 pending 不注入；该路径本就无有效 hevc 可写，影响有限（复核判【推断·中】，非 BLOCKER）。

- **为什么不选 (a)**：单纯"后置消费 + 同 talker 计次"不区分 basename，同 talker 并发不同图会互相串味
  （B 的帧消耗 A 的重试预算、甚至被 A 的 pending 误注入）。(b) 用 basename 把"同转发 host 重传"与
  "不同图"天然区分，兼容真机证据（19b9 两次 m1.e 的 `field_fullpath` basename 同为 19b98c、localId 同为
  886522、其间无新 queued、第二次 9554B/0ef205c1 仍是新 task）。
- **上限**：`MAX_HITS = 3`（:58）= 首帧 + 2 次重试；真机观测最多 2 帧，留 1 次余量。第 4 帧触发
  `p.hits >= MAX_HITS`（:187）→ 条件 `remove` 并停写，fail-closed。每次成功写入后按同 basename 重登记
  （:201-203，ts 续期、hits+1），**TTL 每次命中续期 15s > 重传间隔 10.858s** ⇒ 最后一次上传也写 hevc。
- **防串味论证（四道闸）**：
  1. **key 闸**：隔离 key 仍是目标会话 `field_talker`（第4轮已验证 M1 坑绕开），跨会话上传根本不命中。
  2. **basename 闸**：仅当 `p.basename == File(fullpath).name` 才视为同转发重传；同 talker 并发**不同图**
     （basename 不等）在 :182 立即 fail-closed `return`——不枚举、不写、不消费，也不消耗重试预算。
  3. **字节闸不放宽**：每次注入（含重试帧）都重过 9554B/0ef205c1 模糊门禁 + 45522B/cff4f5a5 唯一判据
     （:195 → findTargetHevc），命中数 !=1 一律不写；重试不享受任何豁免。
  4. **原子 + 有界消费**：仍用条件 `remove(field_talker, p)`（:193），并发竞争只有一个胜者写，败者不动
     他人条目；重登记带 hits，达上限即清除，TTL 到期亦清 ⇒ **不是"永久不消费"**，无长期残留，
     无"无关上传反复触发昂贵枚举"（不同 basename 在枚举前就被闸 2 挡掉）。
- 残留 UNKNOWN（诚实边界，二次复审收敛）：同 talker **串行不同图**（前一张重传窗口内又 markTarget 新图，
  key 被覆盖 → `Pending.basename` 归 null → 第 2 道 basename 闸被整段跳过）仍可能串味：A 的重传帧可消耗 B
  的预算并把 basename 重登记为 bnA，B 后续帧因 bnA≠bnB 全部 fail-closed ⇒ B 零生效。机制【源码实证·高】、
  触发【推断·中】、真机无此场景 ⇒ 二次复审维持 MAJOR/【UNKNOWN 未闭合】，不升级 BLOCKER。
  **订正**：本节初稿第 2 道闸"basename 天然区分不同图"的论证**对 markTarget 覆盖场景过强**，仅在
  `p.basename != null` 时成立。本修正只保证"同 basename 有界重试"与"不同 basename fail-closed"，不扩张该前提。
- 另据二次复审：同目录优先**收窄了"全局命中数==1"语义**——同目录恰 1 命中时不再校验全树是否另有第 2 个
  （:229 兜底因 `hits.isEmpty()` 为假不执行）；两候选若同长度同 MD5 前 8 则内容按判据等价，影响【推断·低】。

### ③ findTargetHevc 同步全树 MD5 阻塞 MAJOR（Q2.8-3 / M1）
- `findTargetHevc`（:215-251）改为 **原图同目录优先**：`origin.parentFile.listFiles()` 扫本目录的
  `*.jpg_hevc`，`admitTarget` 命中即纳入；**同目录 0 命中**才走 `root/image2` 两级分桶兜底，并设
  `MAX_SCAN_FILES = 1024`（:61）文件上限 + 命中 >1 早停。
- **判据不削弱**：结尾仍是 `if (hits.size == 1) hits.first() else null`，0 或 >1 一律不写；
  9554B/0ef205c1 门禁与 45522B/cff4f5a5 参照逐字未动。
- **扫描耗时上界**：同目录命中 = O(单桶文件数，通常几十) < 10ms；兜底全树 ≤ 1024 文件，每文件仅
  `isFile/endsWith/length()`（µs 级 stat），**仅长度恰为 45522B 者**才做 45KB MD5（约 0.1–0.2ms/个），
  上界 ≈ 1024×(stat+MD5) **< ~0.3s（保守取 < 1s）**。真机探针 5.76s/9.13s 含逐候选 `LogX.i` 日志，
  本 hook 无逐文件日志，不构成同量级阻塞；且同目录命中场景根本不触发兜底。

### MINOR
- `Keys.kt` PrefSpec 默认表（FORWARD_P3_BIGIMG 之后）注册 `FORWARD_TASK_FULLPATH_HEVC`，
  `PrefKind.SWITCH`、`"false"`、聊天组，保持默认关；冷启 UI/备份可见，运行时翻转不持久化语义不变。

## 6. 新增变异判红（TTL 2 项 + 重试 1 项）

沿用 M1~M5 的"真实文本变异 + 判定函数翻红 + 自变量唯一"机制，新增 3 个变异用例（单测内文本变异，
与既有五项同构，`occur(...)==1` 保证锚点唯一）：

| # | 变异 | 判红断言 | 自变量唯一校验 |
|---|---|---|---|
| M6 | `TTL_MS = 15000L` → `2000L` | `!ttlOk(mc)` | monotonicClockOk / retryBoundedOk / hitCountOneOk 不变 |
| M7 | `SystemClock.elapsedRealtime() - p.ts > TTL_MS` → `System.currentTimeMillis() ...` | `!monotonicClockOk(mc)` | ttlOk / retryBoundedOk 不变 |
| M8 | 删同 basename 重登记行（退回首帧即消费） | `!retryBoundedOk(mc)` | ttlOk / monotonicClockOk / keyedGateOk 不变 |

另加正向锁 `fixRoundInvariantsPresent`：TTL=15000L + elapsedRealtime + 有界重试 + 快速扫描四项须同时在场。
定向单测由 13 例增至 **17 例全绿**（即 M1~M8 八个变异各自成功翻红、正向锁通过）。
同步修复：原 `keyedGateOk` 用宽泛子串匹配，被新增的 `pendingMap.remove` 处数稀释导致 M4 假绿，已收紧为
精确条件消费行 `if (!pendingMap.remove(field_talker, p)) return`，M4 恢复判红（无回退）。

## 7. 修正轮三闸结果

工作目录 `wxgroup/`，runner `../tools/`。

| 闸 | 命令 | 退出码 | 结果 |
|---|---|---|---|
| 准备编译 | `python ../tools/gradle_run.py compileDebugUnitTestKotlin` | 0 | BUILD SUCCESSFUL |
| 闸1 定向 | `bash ../tools/_junit.sh com.lx.wxgroup.core.ForwardCdnFieldHookTest` | 0 | OK (17 tests) |
| 闸2 全量 | `bash ../tools/_junit.sh` | 0 | OK (1194 tests) |
| 闸3 构建 | `python ../tools/gradle_run.py assembleDebug` | 0 | BUILD SUCCESSFUL（未跑 build） |

全量由上轮 1190 → 1194（+4：fixRoundInvariantsPresent / M6 / M7 / M8）。**三闸全绿。**

## 8. 修正轮诚实边界
- 本轮**只做静态落码 + JVM 三闸**，未跑真机；复核转正条件③（一轮 probe-off/hevc-on 只读间隔观测确认
  登记→消费 < 新 TTL）由主会话在装机前安排，本轮不执行。
- 写后肉眼清晰仍为【推断·中】；同 talker 并发多图串行覆盖前提仍【UNKNOWN 未闭合】，见 §5② 末尾。

[DONE FIX-ALL]
