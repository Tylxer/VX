# R37 装包前交叉复核（第二视角 · 纯静态）

- 目标：微信 **8.0.48 / 2588 GP 版**，设备 `467cf5bc`，模块包 `com.lx.wxgroup`
- 复核区间：`git diff 334dfad 6e2cad2 -- wxgroup/app/src/main`（10 文件 / +2320 / -96）
- 约束：不碰 adb / 设备 / 不改源码 / 不 assembleDebug / 不改 `app/src/test/**`
- 判定口径：P0 = 会崩微信 / 静默失效 / 误伤正常功能（装包前必修）；P1 = 有明确风险但当前不出事；P2 = 代码质量；OK = 查过没问题

---

## 1. 会不会崩微信（最高优先）

**严重度 = OK**（写入宿主对象的路径全部有兜底；另有 1 条 P1 与 2 条 P2 附在末尾）

### 1.1 逐个 hook 回调清点（本次 diff 涉及的 10 个文件）

| 回调 | 行号 | 最外层兜底 | rethrow | 结论 |
|---|---|---|---|---|
| `SnsDeletedTag.bindHook.afterHookedMethod` | `SnsDeletedTag.kt:339-349` | `try{onBind}catch(t:Throwable)`（343-348） | 无（345-348 只 `warnOnce`） | ✅ |
| `SnsDeletedTag` setAdapter 回调 | `SnsDeletedTag.kt:293-299` | 体内 `runCatching{}.onFailure{}`（294-298） | 无 | ✅ |
| `SnsTag.bindHook.afterHookedMethod`（同链路，非 diff 但同源） | `SnsTag.kt:246-262` | `runCatching{onBind}`（250）+ `finally` 复位 depth（259-261） | 无 | ✅ |
| `ForceForward` 长按陷阱 `beforeHookedMethod` | `ForceForward.kt:181-193` | 体内 `runCatching`（183） | 无 | ✅ |
| `ForceForward` popup 注入 `afterHookedMethod` | `ForceForward.kt:202-204` → `tryInject` | `tryInject` 首行 `runCatching`（209-210） | 无 | ✅ |
| `HookEntry` Application.attach | `HookEntry.kt:91-96` | `runCatching{}.onFailure{}`（92-95） | 无 | ✅ |
| `SnsDeletedTag.sweeper.run()`（非 hook，但每 2 s 主线程跑） | `SnsDeletedTag.kt:1090-1095` | `runCatching{sweep()}.onFailure`（1092-1093） | 无 | ✅ |

⇒ **没有一处漏 try/catch**。第 9 条钩子确实挂在全应用公共入口（`androidx` 的 `w1.bindViewHolder(c3,I)`，
`_r35_review_tag.md:660-666` 逐个落到 dex，真机 `hooks=10` 佐证），但 `onBind` 的第一件事就是
前缀过滤并在 return 前不改任何状态（`SnsDeletedTag.kt:355-356`），异常面被 343 那层兜住。

### 1.2 catch 有没有吞掉诊断计数

- `binds++` 在 **try 之外**（`SnsDeletedTag.kt:342`）⇒ 「一次都没进来」和「进来就崩」不会混成同一个数 ✅
- `missed++` / `lastMiss=`（367-368、377-378）在 `onBind` 体内、位于 `ensureSweep()`/`paint()` 之前 ⇒
  解析失败这条诊断路径不受 catch 影响 ✅
- 唯一被吞的情形：异常发生在 `on()`（361）或 `itemViewOf`（365）之前 ⇒ 只有 `binds` 涨、无 `lastMiss`。
  可接受（`binds` 涨而 `snsBinds` 不涨本身就定位到「前缀过滤之前就炸了」）。**P2**

### 1.3 `ForceForward` 新增的原生转发路径

`execute()` 跑在后台单线程 `exec`（`ForceForward.kt:716`，KDoc 694-699 明写「目标数可能几百个，逐个发是
串行 IO，卡主线程会 ANR」），即使漏网也只是池线程死，不崩微信。逐段清点：

| 函数 | 行号 | try/catch 覆盖 |
|---|---|---|
| `nativeForward`（分派） | 900-913 | `loader ?: return fail(CLASS)`（904-905）；`subTypeOf` 是纯函数，无反射 ⇒ 无需 catch ✅ |
| `nativeForwardText` | 936-999 | 四段：943/946（CLASS）、955/962（METHOD）、966/971（CONSTRUCT，**含 `attachForwardInfo`**）、974/996（SEND）✅ |
| `nativeForwardImage` | 1015-1100 | 四段：1030/1042（CLASS）、1047/1064（METHOD）、1068/1073（CONSTRUCT，**含 `attachForwardInfo`**）、1076/1097（SEND）✅ |
| `attachForwardInfo`（写 `q1.f(m)` + 造 `hl0/m`） | 1109-1119 | 自身无 catch，但两个调用点 967 / 1069 都落在 try 内 ✅ |
| `runBatch` 的每个发送动作 | 764-772、786-791 | `runCatching{nativeForward}`、`runCatching{send}` 各自兜 ✅ |
| `snapshotOf` / `msgRefOf`（G2 读链） | 491-600 | 逐条 `runCatching`（526-600 内每一跳）✅ |

⇒ 写微信内部对象（写 `q1.h`、构造 `hl0/m`、直接入队 `q1.a()/q1.b()`）**每一段都在 try 内，失败只返回
`FailStage`，由 `runBatch` 回落降级重发**。OK。

### 1.4 附带的 P1 / P2

- **P1** `ForceForward.kt:974-982`（文本原生）：`runCatching { callMethod(q1, "a") }.getOrNull()` 把
  「`q1.a()` 抛异常」与「`q1.a()` 返回 null」折叠成同一条路径 ⇒ 若 `q1.a()` 是「先入队再返回」的实现，
  异常后会再调 `q1.b()`（978）⇒ **同一条消息发两次**。
  当前 `q1.a()` 的语义（`_r29_g1_forward_forensics.md` §3.1 ①：`q1.a()` 只造 NetScene、`l1.a()` 才入队）
  不支持这个假设，但**代码里没有注释/单测锁住「`q1.a()` 不发送」**。
  ⇒ 默认关闭下不触发；开闸门后属于第一批要验的。修法：区分 `isFailure` 与 `getOrNull() == null`。
- **P2** `attachForwardInfo`（1109）自身不兜异常，正确性依赖两个调用点的 try ⇒ 将来新增第三个调用点就会漏。
- **P2** `ForceForward.kt:813-816` 的注释仍写「真机长按路径 `snapshotNow` 的 kind 恒 UNKNOWN（G2）→ 恒 SKIP」，
  与 G2 已接通（491-600）后的现状矛盾（详见 §2.4）⇒ 注释漂移，会误导真机判读。

---

## 2. 原生转发默认关闭闸门是否真关得住

**严重度 = OK**（写路径全被闸门挡住）；**附 1 条 P1**（闸门语义边界）+ **1 条 P2**

### 2.1 全部调用点 × 闸门前置

| 被调用者 | 调用点行号 | 前置闸门 | 结论 |
|---|---|---|---|
| `nativeState()` | `ForceForward.kt:720`（`execute` → `planFor`）、`:1261`（`nativerdy` 探针） | **自身内含闸门** 861 | ✅ |
| `nativeProbe()` | `:862` | 只在 861 的 `if (!Kv.getBool(...)) return UNKNOWN` 之后 | ✅ |
| `nativeForward()` | `:764` | 只有 `plan.strategy == NATIVE` 才进；而 NATIVE 只在 `nativeState()==AVAILABLE` 时产生（`ForwardLogic.kt:261`） | ✅ |
| `attachForwardInfo()` | `:967` / `:1069` | 在 `nativeForwardText`/`nativeForwardImage` 体内，二者只由 `nativeForward` 调 | ✅ |
| `nativeForwardText/Image` | `:910` / `:911` | 同上 | ✅ |
| `snapshotNow()` / `snapshotOf()` / `msgRefOf()` | `:468` / `:477` / `:491` / `:526` | **无闸门**（只读反射） | ⚠️ 见 2.4 |

⇒ 会**写**宿主对象的路径（`q1.h` 直写、`hl0/m` 构造并挂 `q1.f`、`q1.a()/q1.b()` 入队）**100% 在闸门之后**。
漏网关的调用点：0 个。

### 2.2 有没有「读 Key 失败时默认 true」

- `Kv.getBool`（`Kv.kt:35-38`）**没有默认值形参**，只做 `v == "true" || v == "1"`；
- `getString`（`Kv.kt:28-32`）读失败 / 库未开 ⇒ 回落 `Prefs.defaultOf(key)`（29-31）；
- `Prefs.defaultOf`（`Keys.kt:434`）= `byKey[key]?.def`，本 key 登记为 `"false"`（`Keys.kt:308-311`）。

⇒ **fail-closed**：读不到就是关。全仓无 `getBool(key, true)` 这类写法（已 grep `Kv.getBool` 全部调用点）。✅

### 2.3 Key 真的在「聊天」域吗

- `Keys.FORWARD_NATIVE = "chat.forward_native"`（`Keys.kt:108`），**不是**被拦下的 `forward.native` ✅
- `Prefs.ALL` 登记：`PrefSpec(Keys.FORWARD_NATIVE, PrefKind.SWITCH, "false", "聊天", …)`（`Keys.kt:308-311`）
- `PrefsVisibilityTest.keyPrefixMatchesDomainGrouping`（`PrefsVisibilityTest.kt:142-159`）的映射表里
  `"chat" to "聊天"`（144）⇒ 前缀 `chat` 期望 domain `聊天`，与实际登记一致 ✅

### 2.4 ⚠️ P1：闸门只管「写」，G2 快照链不受闸门控制 ⇒ 默认关也会真发消息

`snapshotOf`（491-512）无条件调 `msgRefOf`（526-600），也就是说**每次长按**（主线程）都会跑一整条
反射链：`getMethod("getListView")` / `getMethod("m")` / `getItem(int)` / `getMsgId` / `getType` /
`getContent` / `getObjectField("field_imgPath")`/`("field_talker")` / `getLongField("field_msgSvrId")` /
`F0`·`G0`·`getChildAdapterPosition`·`getChildLayoutPosition` 候选链。

后果（**不是崩溃**，全程 `runCatching`）：
- `snapshotNow()` 返回的 `kind` 从「恒 `UNKNOWN`」变成 `TEXT` / `IMAGE` / …；
- 而 `ForwardLogic.isCovered`（`ForwardLogic.kt:51-54`）排除 `UNKNOWN`，`planFor`（255-263）在
  `nativeState==UNKNOWN` 时按 `hasText`/`hasFile` 走 **RESEND_TEXT / RESEND_FILE**；
- ⇒ **闸门关着，「强制转发」也会真的把消息发出去**（`resendText` → `TextSender.send`，
  `ForceForward.kt:1134-1136`）。R36 之前这条恒 SKIP、只弹「未实际发送」。

这与 `Keys.kt:96-105` 的 KDoc「关着 = 与引入这条链路之前的行为**完全一致**，一律走降级重发」在字面上
不一致（它指的是「不跑原生」，不是「不发送」）⇒ 真机上「我没开原生怎么也发出去了」极易被误判成 bug。
**装包必须预期到这个变化**（见 §9 C1）。

- **P2**：`getMethod` 未命中会构造 `NoSuchMethodException`（带栈），文本/图片各 4~5 个候选 × 每次长按，
  且发生在主线程长按回调里。可接受但不为零。

---

## 3. VersionGate 删条目会不会误伤当前版本

**严重度 = P1**（残留一条 fail-**close**，当前不出事，不阻塞装包）

### 3.1 白名单现状

删除后只剩 1 条（`VersionGate.kt:49-54`）：

| 字段 | 值 | 非 0 / 非 null |
|---|---|---|
| channel | `GOOGLE_PLAY` | — |
| versionName | `"8.0.48"` | ✅ |
| versionCode | `2588L` | ✅ 非 0 |
| signatureSha256 | `"0fe4ff85…3c7c"` | ✅ 非 null |

⇒ 「每条是否都有非 0 vc + 非 null sha」：**是**（唯一一条满足）。

### 3.2 真机 8.0.48 / 2588 走哪条

`check()`（`VersionGate.kt:73-91`）：
1. `whitelist.firstOrNull { versionName == "8.0.48" }` → 命中（74）；
2. `matched.versionCode(2588) != 0L && != 2588` → false，不 reject（80）；
3. `matched.sha != null && currentSignature != sha` → 相等，不 reject（86）；
4. → `Verdict.Ok`（90）。

真机已有取证：`_Alog1.txt:4` `gate ok: gp`；`docs/_archive_rounds.md:422`「设备 467cf5bc … 微信
8.0.48 (2588) / gp 版，模块 gate ok」。⇒ **不会出现「本来能过、现在被 DISABLED」**。删除的那条是
`8.0.72`，与当前版本无关。✅

### 3.3 fail-open 分支是否消除

失败的两条前置同时为假（`vc==0` **且** `sha==null`）才会整段短路 ⇒ 现在不存在这样的条目
⇒ **fail-open 已消除**。✅ 这正是本次删除的目的，修到位了。

### 3.4 残留的反向风险（fail-close，不崩但要能立刻识别）

`VersionGate.hostSignatureSha256`（`VersionGate.kt:100-108`）是 `runCatching{}.getOrNull()`，
`GET_SIGNATURES` 失败 / `signatures` 空 ⇒ 返回 **null** ⇒ 86 行 `sha != null && null != sha` 为真 ⇒
`Reject("signature mismatch: null")` ⇒ **整模块一行桩都不打**（`HookEntry.kt:117-120`）。
已在 `docs/_r35_pend_decisions.md:37` 记录为已知缺口。当前真机已证能取到签名 ⇒ 不出事；
但**换 ROM / 换微信安装来源 / 多签名**时可能翻转。严重度 **P1**（fail-close，不崩微信、不误伤）。

### 3.5 P2：坑还留着入口

`Target` 的 `versionCode: Long = 0L` / `signatureSha256: String? = null` 默认参数仍在
（`VersionGate.kt:26-33`），下次有人加条目时仍可重开同一个 fail-open。
`VersionGate.kt:55-68` 的⛔注释已写明「不要用 `vc=0` 隐式表达临时放行」，注释到位，代码未收紧 ⇒ P2。

---

## 4. `SnsDeletedTag` 的 8 条 P1 是否真修到位

对照 `docs/_r35_review_tag.md:1042-1056` 的原始描述逐条核。**严重度 = OK**（8 条全部落到代码，
没有一条是「只改文案」）。

| # | 内容 | 落点（现行文件行号） | 判定 |
|---|---|---|---|
| 1 | `bindHook` 的 try/catch | `SnsDeletedTag.kt:339-349`（`binds++` 342 在 try 外；`try{onBind}catch(t:Throwable)` 343-348，只 `warnOnce` 不 rethrow）；另 `hookSetAdapter` 293-299、`sweeper` 1090-1095 各自有兜 | ✅ 修到位（详见 §1.1/§1.2） |
| 2 | `hookedSigs` 去重键含 `declaringClass` | `SnsDeletedTag.kt:245`：`"${m.declaringClass.name}#${m.name}(${形参})"` —— 与 `SnsTag.kt:160` 同口径 | ✅ 修到位 |
| 3 | `Thread{}.start()` 包 try/catch + 异常时复位 | `SnsDeletedTag.kt:1203-1218`：外层 `try{}catch(t:Throwable){ setLoading.set(false); warnOnce }`（1215-1217）+ 线程体内 `finally{ setLoading.set(false) }`（1210-1213）**两层都在**；`SnsTag` 的同缺陷本轮也修了（`SnsTag.kt:631-661`） | ✅ 修到位（双层，不是只加一层） |
| 4 | `apply()` 本类 id 优先，**渲染仍用入参 `tagged`** | `SnsDeletedTag.kt:769-784`：`prev.id != 0L` 时**不覆盖**本类 `Binding`（773-775，只在 `prev==null \|\| prev.id==0` 时才采纳外部 id）；不一致打 `id clash`（776-781）；渲染 `paint(itemView, b, tagged)`（783）用的是**入参** `tagged` | ✅ 修到位 ⇒ `snstag fake` 探针路径（设的是 `SnsTag.forceSet`）**没被修坏** |
| 5 | `isTimeLike` 排除纯时长（`00:15`） | `SnsDeletedTagLogic.kt:172` `DURATION_RE = ^\d{1,2}:\d{2}$`；`isTimeLike` 200-206：长度闸门 20（175/202）、排除 `:` 开头（203）、排除纯时长（204）、三条正则全改**整串匹配**（157/160/163） | ✅ 修到位 |
| 6 | `snsId=0` 兜底的复用失效 | `Binding.pos`（`SnsDeletedTag.kt:152`）+ `invalidateIfReused`（168-176：pos 变 ⇒ 先 `restore` 后 `remove`）；`onBind` 373 在**解析之前**调用；`retryPaint` 447 写 pos；`apply` 的 0 分支 790-797 用的是已做过复用判定的 `own` | ✅ 修到位（且用的是 RecyclerView 自己给的 pos，不是猜的阈值） |
| 7 | `hits` / `painted` 双计数 | 声明 `SnsDeletedTag.kt:98-101`；`paint` 813-814（`hits`，在 `timeTextOf` **之前**）/ 836（`painted`，只在 `tv.text = want` 真执行后）；`stat()` 1255-1259 两个都打；KDoc 的看数顺序改成看 `painted`（1241-1253） | ✅ 修到位 |
| 8 | 本类 `forceSet`（与 `SnsTag.setForce` 双写） | `SnsDeletedTag.kt:1162` `forceSet`、1168-1172 `forceTag`、1175-1179 `deleted()` = `archived ∪ forceSet`；`SnsTag` 侧新增 `setForce`（`SnsTag.kt:714-717`）同时写两边，`refresh`(230) / `markVisible`(738) / `unmark`(749) / `fakeMark`(764) **全部改走 `setForce`** | ✅ 修到位（R36 补的另一半，接线齐全） |

### 附：本轮真机要留意的两个副作用

- **P2（重要，别误判）**：#2 把去重键加上 `declaringClass` 之后，install 时 `ki4/i0` 那层（R35 复核
  `_r35_review_tag.md:6.1.4` 记的「up=1 贡献 0 条，全被去重跳过」）现在会**补挂** ⇒
  `SNS_DELTAG installed hooks=N` 的 N 会从真机旧值 **10 变大**（预期约 **14** = 8 + 4 + `w1` 1 条 +
  `setAdapter` 1 条，[推测] 精确值；「会变大」是确定的）。
  ⇒ **真机看到 `hooks=14` 不是异常**，别拿文档里的 10 去对账。
- **P2**：#4 的 `id clash`、#7 的 `painted` 都还没有真机基线值，本轮要采（见 §9 C6 / B1）。

---

## 5. 线程 / 并发 / 生命周期

**严重度 = P1**（1 条主线程同步查库）+ 其余 **OK**

### 5.1 `sweep()` 跑在哪个线程

**主线程**。`ensureSweep()`（`SnsDeletedTag.kt:1099-1104`）用的是
`mainHandler.postDelayed(sweeper, SWEEP_MS)`（1102，`SWEEP_MS = 2000`，72）。
`sweeper.run()`（1090-1095）第一行就 `sweepScheduled.set(false)`（1091）再调 `sweep()`，
跑完再 `ensureSweep()`（1094）⇒ 置位/清位成对，异常路径也会复位（`runCatching` 1092 + 1094 无条件执行）✅

### 5.2 `sweep()` 里有没有主线程 DB 读 / 文件 IO

- `sweep()`（1120-1132）→ `deleted()`（1175）→ `archived()`（1182）。`archived()` 的首屏同步分支
  （1188-1193）只在 `!firstLoad` 时进；而 `firstLoad` 在**首次 `onBind`** 就置 true（1190）⇒
  **sweep 走的是后台分支**（1196-1219），不在主线程查库 ✅
- `paint()`（812-838）⇒ `dumpTree`（1058-1066）：每个根 View 类只 `LogX.i` 一次（`treeDumped` 1059-1060），
  内容是同步文件 IO，最多 60 条路径 / 3000 字符 ⇒ 一次性抖动。**P2**
- `stat()` 里的 `on()`（1255）→ `Kv.getBool` → SQLite 读，也是主线程；但 `on()` 有 30 s 缓存
  （408-414，`CFG_TTL_MS = 30000`）⇒ 实际每 30 s 一次读。**P2**

### 5.3 ⚠️ P1：`deleted()` 的首屏同步查库确实发生在主线程

调用链：`onBind`（353，**主线程**）→ 397 `paint(view, b, deleted())` → `deleted()`（1175）→
`archived()`（1182）→ 1188-1193 **首次** `loadIds()` 同步查库 → `GroupDb.snsTagIds()`
（`GroupDb.kt:537-548`，只查 `sns_id` 一列、无 BLOB、`.use{}` 关游标）。

- 只发生**一次**（`firstLoad` 1190 置位；`GroupDb.current() == null` 时不置位，留着下次再试，1190）；
- 单列查询，代价 = O(T_SNS 行数)。`T_SNS` 是「`SnsGuard` 拦下的物理 DELETE 归档」，行数取决于用户
  历史删了多少条朋友圈；**行数未知** ⇒ 若已积累很大（>10k）会有一次几十 ms 级的主线程停顿。
- 已知且被注释接受（`SnsDeletedTag.kt:1186-1187`：「首屏必须同步拿一次，否则进朋友圈第一屏一个标签都没有」）。
⇒ **P1**：不阻塞装包，但装包后第一次进朋友圈若有可感知卡顿，嫌疑点就是它。
 想消除的话：把首屏这次也放后台 + `firstLoad` 后置，代价是第一屏无标（现在的设计取舍，不建议动）。

### 5.4 `bound: WeakHashMap` 里有没有存强引用

- `Binding`（148-153）只有 `tvId: Int` / `hint: String` / `pos: Int` / `id: Long`
  ⇒ **不含任何 View / holder / data 引用**，不会钉住 WeakHashMap 的 key ✅
- KDoc（144-146）明确写了这条禁令，`pos` 只存 `Int` 也是为此（146）✅
- `snapshot()`（1135-1145）迭代时滤掉 null key / null value（`e.key ?: continue` 1139、`e.value ?: continue` 1140）
  ⇒ WeakHashMap referent 被回收时不会 NPE ✅
- `ensureSweep()`（1100）在锁内判 `bound.isEmpty()`，`sweep` 内 remove（1127）也在锁内 ⇒ 并发安全 ✅

### 5.5 `AtomicBoolean` 置位后异常路径不复位的「永久静默失效」

| 标志 | 位置 | 复位路径 | 结论 |
|---|---|---|---|
| `setLoading` | 85 | 线程体内 `finally`（1210-1213）+ `start()` 失败的外层 catch（1215-1217） | ✅ 双层，无卡死路径 |
| `sweepScheduled` | 86 | `sweeper.run()` 首行（1091），在 `runCatching` 之外 | ✅ |
| `installed` | 87 | 只在 `base` 找到后才 CAS（200-205），找不到**不占位**（避免浪费一次性安装机会） | ✅ 设计正确；但 `hooks==0` 时已置 true ⇒ 进程内不再重试（209-210 只打一条 warn）⇒ 版本漂移 = 永久静默失效。**P2**（`_r35_review_tag.md:6.3.4` 已记） |
| `retryLeft` | 421 | 只减不增，12 次预算扣完不再排队（435-436） | ✅ 不会堆 Runnables |

### 5.6 内存

- `ForwardLogic.Snapshot.raw`（`ForwardLogic.kt:218`）持有 `h9` 消息对象；`snap` 被
  `openTargetPicker` 的 lambda 捕获（`ForceForward.kt:690`）⇒ 选择器打开期间持有该消息对象。
  页关闭即释放，**P2**（不是泄漏，但别在 picker 里长期挂）。
- `dumped` / `treeDumped` / `dataPathCache` / `viewFieldCache` / `timeMethodCache` / `probeLeft` / `dataTally`
  全部以**类名字符串**为键 ⇒ 上界 = 类数量，不会无限涨 ✅

---

## 6. `ForwardPicker` 补跑会不会卡 UI

**严重度 = OK**

新增就一行：`ForwardPicker.kt:183` `ChatroomSource.runIfNeeded()`（放在 `loadTabs` 内、`db` 就绪检查之后）。

- **立即返回，不等待**：`ChatroomSource.runIfNeeded()`（`ChatroomSource.kt:122-148`）同步段只有
  `CAS`（124）+ 建线程（126-144）。全表扫 `syncBlocking`（177+）在 `wxg-chatroom` **daemon 线程**（144）里跑 ⇒
  **不占主线程**，2.4 s 的扫描不会造成卡顿/ANR ✅
- **启动失败有兜底**：`Thread{}.start()` 被 `runCatching` 包住（125），失败时 `loading.set(false)`（146），
  与 §4 #3 同一条纪律 ✅
- **节流**：与 `PickerTabs.all()` 里那次（`PickerTabs.kt:52`）共用同一把 `loading` CAS
  ⇒ 同一进程内重复调用不会重复全表扫 ✅
- **回调刷新在哪个线程 / 会不会 NPE**：`notifyRooms`（`ChatroomSource.kt:160-169`）用
  `mainHandler.post` 且每个回调 `runCatching` 包住（165-166）——但 **本轮 `ForwardPicker` 根本没注册回调**
  （`addRoomListener` 156 无调用点；`ForwardPicker.kt:175-181` 注释明说「补跑完成后的原地刷新未做」）
  ⇒ **不存在「页面已退出后回调 NPE」** ✅
- **代价（已声明）**：本次打开选择器可能仍看不到新出现的群聊段，要**下次打开**才可见（`ForwardPicker.kt:180-182`）。
  真机别把这个当成 bug。
- 线程前提：`loadTabs` 里本来就有同步 DB 读（`db.allFriends()`，`ForwardPicker.kt:186`）⇒ 说明它已不在
  主线程关键路径上，`runIfNeeded()` 放在它前面不改变这一点 ✅

⇒ 不卡 UI、不 ANR、不 NPE。**OK**

---

## 7. 反射符号存在性（NoSuchMethodError 风险）

**严重度 = P1**（3 处 ② 档，全部只在开闸门后触发）+ 其余 **OK**

### 7.1 `SnsDeletedTag` / `SnsDeletedTagLogic`（R35 灰标）

| 符号（字符串字面量） | 位置 | 档 | 出处 | 反射失败的保护 |
|---|---|---|---|---|
| `com.tencent.mm.view.recyclerview.WxRecyclerAdapter` | `SnsDeletedTag.kt:64` | ① | `_r35_sns_tag.md:46-52`（dex 实测） | `findClass` 失败 → 200-203 打 warn + `return 0`，**且不占 `installed`** |
| `com.tencent.mm.view.recyclerview.WxRecyclerView` | `SnsDeletedTag.kt:67` | ① | 同上 | 283-285 warn + `return 0` |
| `setAdapter`（按名找，最多 6 层父类） | `SnsDeletedTag.kt:306-316` | ① | 同上 | 找不到 → 287-290 warn + `return 0` |
| `c1/d1/a1/b1/i0/k0/l0/n0` + `w1.bindViewHolder` | 形状匹配选出的，无硬编码名（228-264） | ①（逐个落到 dex，`_r35_review_tag.md:6.1.3` 表） | 同上 | **按形状不按名字** ⇒ 混淆名漂移天然不影响；名字全变只会 `hooks=0` ⇒ 不打标（fail-close） |
| 字段 `E` / `D`、getter `z()`、字段 `data` | 574 / 608 / 637 / 653 | ① | `_r35_sns_tag.md` dex（`m0.E` `iput` 证据；`data` 未混淆） | 每一步 `runCatching`（574-611、635-639） |
| `k()` → `oh3/p`，`J0()` → 时间原文 | `SnsDeletedTag.kt:1044-1045` | **②**（混淆名，最后兜底） | `_r35_sns_tag.md` dex | `runCatching`（1044/1045）；失败只丢 `hint` ⇒ 第 ③ 级解析失效，仍有 ②/④ 级，**不影响打标** |
| `getPublishTimeText()`（无参、返回 TextView） | `SnsDeletedTag.kt:1020` | ①（dex 实测只声明在 `Llh3/l1;`，9 层父类） | `_r35_sns_tag.md` | `runCatching`（1018-1023 / 957）；失败降级到 ③④ 级并 `logOnce`（963） |
| `com.tencent.mm.plugin.sns.` 前缀 | `SnsDeletedTag.kt:70` | ① | dex | 纯前缀，漂移只会导致不打标 |

### 7.2 `ForceForward` 原生转发（R36 G1，重点项）

| 符号 | 位置 | 档 | 出处 | 保护 |
|---|---|---|---|---|
| `hl0.r1` | 944 / 1031 / 1263 | ① | `_r29_g1_forward_forensics.md` §5.1 | try/catch → `FailStage.CLASS` |
| **`hl0.r1.b(String)`** | 956 | ① | 同上 §5.1（PUBLIC STATIC FINAL） | try/catch → `METHOD` |
| `hl0.r1.a(hl0/o1)` | 1048 | ① | 同上 §5.2（`qf4/o4.O8` 字节码） | try/catch → `METHOD`/`CONSTRUCT` |
| `hl0.m`（ForwardInfo）+ `newInstance` | 945 / 1032 / 1111 | ① | 同上 §2.1（`toString` 明文钉死） | try/catch → `CLASS` / `CONSTRUCT` |
| **`hl0.m` 构造 + 字段 `a..f`** | 1111-1117 | ① | 同上 §2.1 | 调用点 try/catch → `CONSTRUCT` |
| `hl0.o1` + `valueOf("IMAGE")` | 1033 / 1035 | ① | 同上 §2.3（`<clinit>` 明文） | try/catch → `CLASS` |
| `hl0.o1.values()[1]`（兜底） | 1037 | **②** | 见 §7.3-① | try/catch → `CONSTRUCT` |
| `q1.e(String)`（设正文） | 961 | ① | 同上 §2.2 | try/catch → `METHOD` |
| `q1.f(hl0/m)`（挂 ForwardInfo） | 1118 | ① | 同上 §2.2 | 调用点 try/catch → `CONSTRUCT` |
| `q1.g(String)` / `q1.h(String)` | 1056 / 1057 | ① | 同上 §5.2（地址表 8457ac / 8457b2） | try/catch → `METHOD` |
| **`q1.h` int 字段直写（=4 / =13）** | 968 / 1070 | ① | 同上 §2.4 值表（`t81/g.l` + `qf4/o4.O8/Q8` + `MsgRetransmitUI.z6` 三处交叉） | try/catch → `CONSTRUCT`，且**挂 ForwardInfo/设 h 失败就不裸发**（KDoc 933-935） |
| `q1` 字段 `i/j/k/l/n/o/p` 直写 | 1055-1063 | ① | 同上 §5.2（地址表逐条对齐 `FileSender`） | try/catch → `METHOD` |
| `q1.a()` → `l1` | 975 / 1077 | ① | 同上 §3.1 | try/catch → `SEND` |
| `l1.a()` → Boolean | 985 / 1087 | ① | 同上 §3.1 | try/catch → `SEND` |
| `q1.b()`（executeByPPC 兜底） | 978 | ① | 同上 §5.1 + `TextSender` 真机验证过 | try/catch → `SEND` |
| `com.tencent.mm.ui.transmit.MsgRetransmitUI` + `g6(l1)` | 1084-1085 | ① | 同上 §3.1/§6 | **单独** `runCatching`（1083-1086），失败只记 warn、不影响真正 execute |
| `Q1_I_IMAGE = 4` / `Q1_O_RES_ID = 0x7f0803d6` | 1135 / 1137 | ①（随 `FileSender` 同值） | 同上 §5.2 | 写错只影响 `q1.o`（上报资源 id），不发错消息 |

### 7.3 ⛔ ② 档单独警告（装包后才会暴露）

1. **`hl0.o1.values()[1]`**（`ForceForward.kt:1037`）—— 只在 `valueOf("IMAGE")` 失败时才走。
   下标 1 是**猜的**：若 `hl0/o1` 的常量顺序不是 `[0]=TEXT / [1]=IMAGE`，就会选出**错的消息类别** ⇒
   `r1.a(o1)` 造出的 builder 类型不对 ⇒ 图片原生转发整条失败、回落降级。
   ⇒ 不崩、不误发，但表现为「图片转发永远走降级」且日志 `stage=CONSTRUCT`，**极易被误判成锚点失效**。
   真机识别：日志出现 `FORWARD native image o1.IMAGE n/a`（1040）就是它。
2. **`m.e`（forwardMsgSvrId）= `field_msgSvrId` 的十进制字符串**（`ForwardNativeLogic.kt:108` / 127-132）
   —— 字段 `e` 是 `String` 而源是 `J`(long)，**格式是推断的**。错了最多服务端忽略
   （`t81/g.l` 的 `h==4` 分支只读 `m.b`，不读 `m.e`）。类 KDoc 22-29 已标②。
3. **`m.a`（forwardFromImgId）恒 `-1`**（`ForwardNativeLogic.kt:100-101`）—— 没有①档的 CDN imgId
   获取路径 ⇒ 图片会**重传**（与降级重发一致），转发标记靠 `h=13` + `m.b` 承担。类 KDoc 24-26 已标②。

⚠️ 2 / 3 在 **`chat.forward_native` 默认关闭**状态下完全不触发；1 只在开闸门且 `valueOf("IMAGE")` 失败时触发。

### 7.4 「反射失败只 warn 不崩」的保护是否齐全

- 全部反射调用都在 `try/catch (t: Throwable)` 或 `runCatching` 内 ✅
- 特别注意 `XposedHelpers.findClass` 抛的是 `ClassNotFoundError`（是 **`Error` 不是 `Exception`**）：
  `nativeProbe`（`ForceForward.kt:869-870`）的 KDoc 专门写了「别换成 `catch (Exception)`」，
  代码用的 `runCatching`（877-882，抓 `Throwable`）✅；`SnsDeletedTag` 侧同理（200、283）。
- 结构性失败（`CLASS`/`METHOD`/`CONSTRUCT`）会被 `ForwardNativeLogic.isStructural`（187-190）判为
  「本批次剩下的目标也不必再试」⇒ `runBatch` 777 把 `nativeUsable` 置 false ⇒ **不会每个目标重复白试** ✅

### 7.5 `ForwardLogic` 新增判据的副作用（顺带确认）

`ForwardLogic.kt:261` 新增 `&& ForwardNativeLogic.supportsNative(snap.kind)`：只有 `TEXT`/`IMAGE`
才允许进原生链（其余种类即便 `nativeState==AVAILABLE` 也走降级）⇒ 这是**收窄**，不会误伤 ✅

---

## 8. 会不会误伤正常功能

**严重度 = OK**（3 条 P2 附在末尾）

### 8.1 新增 hook 会不会挂到非目标界面

会「进入」，但**不会打标、不会改任何状态**：

- 第 9 条钩子挂在 `androidx` 的 `w1.bindViewHolder`（`_r35_review_tag.md:660-666`，真机 `hooks=10` 佐证）
  ⇒ 全微信所有 RecyclerView 都会进 `bindHook`；
- 但 `onBind` 第一件事就是 `adapter.javaClass.name.startsWith("com.tencent.mm.plugin.sns.")` 判定，
  不满足直接 `return`（`SnsDeletedTag.kt:355-356`），**return 前不改任何状态、不写 `bound`、不 paint**；
- 朋友圈限定 = 只认 `com.tencent.mm.plugin.sns.` 前缀（70），dex 佐证朋友圈 adapter
  `com.tencent.mm.plugin.sns.ui.improve.component.u1` 前缀不被误杀（`_r35_review_tag.md:686-689`）。

⇒ 不打错标、不误伤其它界面。**OK**

- **P2**：所有 `com.tencent.mm.plugin.sns.*` 的 adapter（相册网格/评论列表/图片选择器/SNS 设置页）都会
  完整跑一遍 `dataOf`（549-569）与 `probePath`（572-582），首次进入触发全量字段反射探测 +
  `dumpFields` 两行日志，每 holder 类最多 `PROBE_MAX=12` 次（507）⇒ 每个这类页面一次性 5~15 ms 抖动。
  `_r35_review_tag.md:690-695` 已记为 P2，本轮未变。
- **P2**：`matchesBindShape`（257-264）的形状「形参[0] 是 ViewHolder + 形参[1] 是 int」不唯一；
  本版 dex 上只有 bind 类方法满足，将来若出现 `onViewAttachedToWindow(holder, pos)` 这类就会被误挂。
  `_r35_review_tag.md:696-699` 已记。

### 8.2 `SnsDeletedTagLogic.ID_RE` 两侧边界

`SnsDeletedTagLogic.kt:54`：
```
(?<![\w])snsID\s*=\s*'?(-?\d{15,20})(?![\w])
```
- 左边界 `(?<![\w])`（50-51 的 KDoc 说明）：挡住 `oldsnsID=` 里的 `d` ✅
- 右边界 `(?![\w])`：既挡「后面还有数字」（21 位那种会一路回溯全失败 ⇒ 整体不匹配，而不是退而取前 15 位），
  也挡「数字后接字母」（`1504316024693044480a` ⇒ null）✅
- 闭合引号情形 `snsID='1503…'`：`'?` 吃掉左引号，数字后是 `'`（非 `\w`）⇒ `(?![\w])` 通过 ✅
- 二道闸 `parseSnsId`（113-128）再验一次纯数字 + 长度 ≤ 20 ✅

⇒ **两侧边界都到位**。**OK**

- **P2**：`snsIdOf`（102-107）取的是 `find()` 的**第一个**匹配；若 `toString()` 里先出现嵌套对象的
  `snsID=`，会取到外层那个。形状校验挡住了绝大多数，但不是 100%。

### 8.3 `Keys.kt` 新增 Key 对备份/迁移的兼容

- 导出：`Kv.exportAll()`（`Kv.kt:71-72`）遍历 `Prefs.ALL` ⇒ 新 key 自动进备份文件，**无需改备份代码** ✅
- 导入：`Kv.importSome()`（`Kv.kt:80-88`）只写 `Prefs.spec(k) != null` 的 key ⇒ 旧备份（无此 key）导入后
  该 key **保持当前值**，不会写入污染 ✅
- 旧备份恢复后新 Key 有没有默认值：有。`Kv.getString`（28-32）在库未开/读失败时回落
  `Prefs.defaultOf`（`Keys.kt:434`）= `byKey[key]?.def` = `"false"`（`Keys.kt:308-311` 登记）⇒ **默认关** ✅
- 已 grep 确认 `BackupCodec` / `BackupFlow` / `BackupMerge` 里**没有**重复的 key 清单需要同步更新 ✅

⇒ **OK**（fail-closed，旧备份恢复后原生转发保持关闭）

### 8.4 其它

- `ForwardPicker` 补跑（§6）：`runIfNeeded` 只多跑一次后台全表扫，不改 UI、不注册回调 ⇒ 不误伤 ✅
- `VersionGate` 删条目：只影响 8.0.72，不影响当前 8.0.48（§3）✅

---

## 9. 装包后首要观察点排序

按「崩微信风险 → 静默失效 → 功能不对」排序。**按顺序看，前一条不过就别往后走。**

### A 组：崩微信风险（冷启后 30 秒内）

| # | 看什么 | 出现 / 不出现说明什么 | 出处 |
|---|---|---|---|
| A1 | 冷启日志必须有 `host version=8.0.48 code=2588` + `gate ok: gp`，**且不许出现 `DISABLED:`** | 出现 `DISABLED: signature mismatch: null` ⇒ `hostSignatureSha256` 取不到签名（`VersionGate.kt:100-108`）⇒ **整模块一行桩都不打**（不崩，但全部功能失效）。出现 `version 8.0.48 not whitelisted` ⇒ 删条目误伤（不应发生） | `HookEntry.kt:112-120` |
| A2 | `SNS_DELTAG installed hooks=N`，**N ≥ 3** | `hooks=0` ⇒ 形状匹配全没命中 ⇒ 灰标永久静默失效（且 `installed` 已置 true，进程内不再重试，209-210）。⚠️ N 预期**约 14**，不是文档旧值 10（见 §4 附注），**别当异常** | `SnsDeletedTag.kt:209` |
| A3 | 微信任意列表 + 朋友圈各滚一遍 → `grep "SNS_DELTAG bind err"` **必须为空** | 出现 ⇒ `bindHook` 兜住了逃逸异常（不崩，但说明有系统性错误），异常类名就是线索 | `SnsDeletedTag.kt:347` |
| A4 | 微信进程没死（冷启 30 s 内无 crash/tombstone） | 兜底失效的直接信号 | — |
| A5 | `grep "FORWARD native threw"` 与 `grep "FORWARD fallback threw"` 为空 | 出现 ⇒ 原生/降级的 `runCatching` 兜住了异常（后台线程，不死微信，但说明反射语义不对） | `ForceForward.kt:766-768` / 788-790 |

### B 组：静默失效（进朋友圈后）

| # | 看什么 | 判读 | 出处 |
|---|---|---|---|
| B1 | `SNS_DELTAG stat` 的**看数顺序**：`binds>0`（钩子活着）→ `sns>0`（前缀过滤对）→ `resolved≈sns`（解析通，差得多看 `lastMiss`）→ **`painted>0`**（这才算画上了）。`hits>0 但 painted=0` ⇒ 清单/符号对、但没落到界面 → 立刻跳 B2 | `painted` 是唯一验收判据 | `SnsDeletedTag.kt:1241-1259` |
| B2 | `missedTime>0` 且 `tv=0` ⇒ 四级解析全挂 → 看 `SNS_DELTAG tree …`（一次性 View 树 dump）与 `SNS_DELTAG fields/vals`（一次性字段 dump） | 直接照着树/字段改解析，不用再猜 | `SnsDeletedTag.kt:1058-1066` / 705-728 |
| B3 | `deleted=N` 应等于已删归档条数 | N=0 且确实删过 ⇒ `archived()` 首屏同步查库（1188-1193）没成，或 `GroupDb not open`（1226-1229） | `SnsDeletedTag.kt:1255` |
| B4 | `grep "id refresh thread failed"` 为空 | 出现 ⇒ 线程没起来；此时 `setLoading` 已复位（1215-1217），清单仍在刷新，只是这一拍失败 | `SnsDeletedTag.kt:1217` / `SnsTag.kt:659` |
| B5 | `grep "no snsId on holder"` 的 `fail=N/seen=N` | `fail==seen` ⇒ 该类全失败（解析链路断）；`fail << seen` ⇒ 只是首条失败（正常，重试会补） | `SnsDeletedTag.kt:686` |

### C 组：功能不对（按需触发）

| # | 操作 | 看什么 | 说明 |
|---|---|---|---|
| C1 | **默认（闸门关）**长按一条文本消息 → 菜单「转发到多个」→ 确认 | `FORWARD plan strategy=` 应是 **`RESEND_TEXT`**（不是 SKIP，也不是 NATIVE） | ⚠️ **本批最大的行为变化**：R36 之前恒 SKIP「未实际发送」，现在会**真的把消息发出去**（`resendText` → `TextSender.send`）。别误判成 bug，也别在不想发消息的场合乱试 |
| C2 | 开闸门前先跑 `op=forward sub=nativerdy` | `hl0.r1=yes hl0.m=yes hl0.o1=yes state=AVAILABLE` | 三个锚点任一为 `no` ⇒ **别开闸门**（开了也只能回落降级） |
| C3 | 开闸门后转发**文本** | `FORWARD native text via=l1.a()` 或 `via=q1.b()`，且 `marked=true msgId>0` | 出现 `FORWARD native fail stage=METHOD/CONSTRUCT` 且紧跟 `→ 回落` ⇒ 原生没通但降级补上了（不是事故） |
| C4 | 开闸门后转发**图片** | ① `FORWARD native image o1.IMAGE n/a`（1040）⇒ §7.3-① 那条②档踩了；② `stage=MATERIAL` ⇒ 图片本地文件没抓到（`field_imgPath` 空）；③ `queued … marked=…` ⇒ 成功 | 图片 `m.a` 恒 -1 ⇒ 会重传，属预期 |
| C5 | 首次打开转发选择器 | `ChatroomSource 命中补跑` 或 `跳过补跑` 出现一条 | **本次打开可能仍看不到群聊段**（刷新未接），**第二次打开**应可见 |
| C6 | `snstag fake <id>` | 界面出现灰标 ⇒ 验 §4 #4「渲染仍用入参 `tagged`」没被修坏。`grep "id clash"` 若出现 ⇒ 两条链 id 不一致，以本类 id 为准 | `SnsDeletedTag.kt:783` / 778-780 |
| C7 | 朋友圈快速滚动后停下 | 任一**未删**动态上不出现 `[删除已拦截]`；已删那条仍在（`painted` 不回落） | 验 §4 #6 复用失效 |

### 观察点一句话版

`gate ok: gp` → `hooks≈14` → `bind err` 为空 → `painted>0` → `strategy=RESEND_TEXT`（默认关也发） →
再决定是否 `kvset chat.forward_native true`。

---

## 结论

# 能否装包 = **GO-WITH-CONDITIONS**

**没有发现 P0。** 9 个问题逐条结论：

| # | 问题 | 严重度 |
|---|---|---|
| 1 | 会不会崩微信 | **OK**（7 处 hook 回调全部有最外层 try/catch、无 rethrow、诊断计数在 try 之外；原生写路径 4 段 try 全覆盖）+ 1 条 **P1**（`ForceForward.kt:974-982` 折叠了「抛异常」与「返回 null」，理论上可能双发） |
| 2 | 原生转发默认关闸门 | **OK**（写路径 0 处漏网；读 Key 失败 fail-closed；`chat.forward_native` 确在聊天域）+ 1 条 **P1**（G2 快照链不受闸门控制 ⇒ 默认关也会真发消息） |
| 3 | VersionGate 删条目 | **P1**（残留一条 fail-**close**：签名取不到 ⇒ 整模块失效；真机已证当前能取到）+ fail-open 已真正消除 |
| 4 | 8 条 P1 是否修到位 | **OK**（8 条全部落到代码行号，无一条只改文案）+ 1 条 **P2**（`hooks` 数字会从 10 变约 14，别当异常） |
| 5 | 线程/并发/生命周期 | **P1**（`deleted()` 首屏同步查库在主线程，只一次；`T_SNS` 行数未知）+ 其余 **OK**（无强引用、无标志卡死、无主线程 sweep 查库） |
| 6 | ForwardPicker 补跑 | **OK**（立即返回、daemon 线程、CAS 节流、没注册回调 ⇒ 无 NPE） |
| 7 | 反射符号存在性 | **P1**（3 处②档：`hl0.o1.values()[1]` / `m.e` 格式 / `m.a` 恒 -1；全部只在开闸门后触发）+ ①档全部有保护 |
| 8 | 误伤正常功能 | **OK**（前缀过滤在回调首行、`ID_RE` 两侧边界到位、新 Key 备份兼容 fail-closed）+ 3 条 **P2** |
| 9 | 观察点排序 | 见上节 A/B/C 三组 |

### 装包必须带上的 4 个条件

1. **别开 `chat.forward_native`** —— 保持默认 `false`。要开必须先跑 C2（`nativerdy` 三锚点全 `yes`），
   再按 C3/C4 验，验不过就保持关。
2. **预期到「默认关也会真发消息」**（§2.4 / C1）—— 长按「转发到多个」现在会走 `RESEND_TEXT` /
   `RESEND_FILE` 真的发出去，这是 R36 接通降级重发的正常结果，**别当成 bug**，也别在不想发消息的场合乱试。
3. **`SNS_DELTAG installed hooks=N` 的 N 预期约 14**（不是文档旧值 10）—— 见 §4 附注，别拿旧数去对账。
4. **装完先看 A 组 5 条**（`gate ok: gp` / `hooks≥3` / `bind err` 为空 / 微信没崩 / `native threw` 为空），
   过了再看 B 组的 `painted>0`，最后才碰 C 组的功能验证。

### 已知的「不是问题但要认得」的提示

- 首次打开转发选择器可能仍看不到群聊段，第二次打开才可见（`ForwardPicker.kt:180-182` 已声明未做原地刷新）。
- 首次进朋友圈若有一次可感知卡顿，嫌疑点是 `archived()` 的首屏同步查库（`SnsDeletedTag.kt:1188-1193`）。
- `missedTime>0` 且 `tv=0` 时不要猜，直接看 `SNS_DELTAG tree` / `fields` 那两行一次性 dump。

[DONE]
