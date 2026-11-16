# R54 分组条 · 修复轮装包前独立复核结论

## ① verdict

**需先修 1 条（P1 · 事务回滚）再装包。**

- 首轮复核 `docs/_r54_view_review.md` 的 P0 实质危害——「两个入口并发写同一 wcdb 连接」与「主线程一次 sync 全表读 3120 行」——**已彻底闭环**：全仓生产调用只剩 `postSync/postClean/postStat`，全部 post 到 `gconv-store` 单一后台线程（`GroupConvStore.kt:59-61`），主线程不再有任何微信主库读写。这一条不再构成阻塞。
- 但 P0-1 的第 3 条「两表写包一个事务、失败整段回滚」**代码上未兑现**：`execLog()` 吞掉每条 SQL 的异常只打日志（`GroupConvStore.kt:425-432`）⇒ `inTransaction()` 的 `block()` 不会抛 ⇒ `setTransactionSuccessful` 仍被调用（`:413`）⇒ 单条 SQL 失败时半行照样提交。
- 进一步独立核实（比原推演更重）：这种半行**无法被下一次 sync 自愈**。`readExisting()` 按 username 取两表并集（`:303-308`）、`plan()` 对已存在的 username 只产 `Update`（`GroupConvPlan.kt:72-80`），而 `repairSql/renameSql` 都是 `UPDATE`（`GroupConvPlan.kt:114-120`），对缺失的那一行影响 0 行 ⇒ 缺行永远补不回来；`GroupConvStore.kt:83-85` 注释「下一次 due 自然补齐失败的行」不成立。
- 定级 **P1** 而非 P0：并发/主线程卡顿已消除、半行只落在 `wxgrp_` 我方命名空间（`ownWhere()` 双保险）、正常路径 SQL 全成功时不触发、`clean()` 可幂等清理，不是「确定掉帧 / 写坏宿主数据」。定级不是 P2：P0 明确保证落空、半行可持久残留、且日志 `FAIL` 与实际部分落库不一致会误导排查。改法约 10 行、零架构变更，装包前补上成本极低。
- 另 1 条 P2（ON→OFF 热切换 listener 残留，健壮性，验收 SOP 冷启已规避）与 5 项「需真机验证」，见 ③。其余 9 条全部通过。
- 本 verdict 是「修复轮的 P0 验收项字面未闭环」，不是「并发/卡顿没修好」。若决定带病上机做验收，须遵守 ③ 末尾的上机纪律，且正式发布前必须补修该条。

## ② 逐条 1~10 结论 + 证据行号

### 1. 独立构建验证

**通过。** 按「先编译、后 junit」的顺序独立跑：

- `cd wxgroup && ./gradlew compileDebugUnitTestKotlin assembleDebug --offline -q --console=plain` ⇒ **GRADLE_EXIT=0**。
- 回仓库根 `bash tools/_junit.sh` ⇒ **OK (919 tests)** / **JUNIT_EXIT=0**（基线 `OK (919 tests)`）。
- 顺序纪律成立：`_junit.sh:36-38` 只跑 `app/build/tmp/kotlin-classes/debugUnitTest` 下**已编译**的 `*Test.class`（`:44-45` 枚举、`:52-53` JUnitCore），脚本自己不编译。

额外独立核实「919 不是旧基线假判据」：`GroupConvPlanTest.kt` 是 `??` 未跟踪文件，`git diff` 看不到；但编译产物里确有 `app/build/tmp/kotlin-classes/debugUnitTest/com/lx/wxgroup/data/GroupConvPlanTest.class`，单独跑 `bash tools/_junit.sh com.lx.wxgroup.data.GroupConvPlanTest` ⇒ **OK (18 tests)** / EXIT=0；全量目录下共 45 个 `*Test.class`。⇒ 919 已含本轮新增的 18 例，是真通过而非「编译没跑到、只看旧 class」。

### 2. 并发闭环（原 P0）

**通过，P0 实质已消除。**

- 全仓 Grep `GroupConvStore.`：生产调用只有 `EntryReceiver.kt:212-214`（`op=gcs` → `postSync(force = true)` / `postClean()` / `postStat()`）、`ConvFilter.kt:653`（`kickGconvSync()` → `postSync()`）、`ConvFilter.kt:1509`（`onDataChanged` 去抖回调 → `postSync()`）。`ConvFilter.kt:1819`、`Keys.kt:65`、`Keys.kt:79` 是 KDoc 文字，不是调用。⇒ **没有任何外部入口直接调 `sync/clean/stat`**。
- 三个 post 方法 `GroupConvStore.kt:87-106`、`109-117`、`120-128` 都以 `worker.execute{}` 投递；`worker = Executors.newSingleThreadExecutor { Thread(r, "gconv-store") }`（`:59-61`）⇒ 三处入口 + `refresh()`(`:639`)/`install()`(`:926`) 的兜底全部落到**同一个 worker**。`sync()`/`clean()`/`stat()` 仅在 worker 的 lambda 内被调（`:99`、`:112`、`:123`）。
- 主线程不再有全表读/写库：`AntiRevoke.wxDb()` 在 gconv 生产侧只出现在 `GroupConvStore.kt:140`/`:193`/`:230`（全在 worker 内），`ConvFilter.kt` 全文已无该调用（本轮把判空下沉进 Store）。模块库 `GroupDb.listGroups()`/`memberWxids()` 在 `postSync` 的 worker lambda 内（`:97-99`）。
- `postSync` 的 `due` 判定（`:93-94`）、`lastSyncMs` 推进（`:96`）、`sync` 调用（`:99`）全在 `worker.execute` lambda（`:88-101`）内 ⇒ 节流状态只在 worker 线程读写；`@Volatile`（`:69-70`）在单线程下无害。
- **需真机验证**：wcdb 单连接上 `rawQuery`/`execSQL` 的实际线程安全性与一次 sync 的真机耗时（静态判不了；现已单线程，风险显著降低）。该项不影响「并发闭环成立」的结论。

### 3. 节流哨兵（原 P1-C）

**通过。**

- `GroupConvStore.kt:93-94`：`force || lastSyncMs == Long.MIN_VALUE || (now - lastSyncMs >= SYNC_THROTTLE_MS)`，`SYNC_THROTTLE_MS = 60_000L`（`:72`），初值 `Long.MIN_VALUE`（`:69-70`）。
- **首次必触发**：`lastSyncMs == Long.MIN_VALUE` 先短路命中，且短路发生在减法之前 ⇒ 避免 `now - Long.MIN_VALUE` 的 Long 溢出（溢出成负数会把首次又吞掉）✓ 补强正确。
- **之后 60s 节流正常**：`lastSyncMs = now`（`:96`）在 `sync()`（`:99`）之前推进，窗口内 `now - lastSyncMs < 60_000` ⇒ due=false。`lastSyncMs` 此后为正常正数，减法不溢出。
- **`op=gcs sync` 的 `force=true` 绕过节流**：`EntryReceiver.kt:212` `postSync(force = true)` ⇒ `force` 短路命中 ⇒ due=true ✓。且 force 执行时同样走 `lastSyncMs = now`（`:96`）⇒ 手动 gcs 会推进节流时间戳，兑现首轮 P0-1 第 4 条「`op=gcs` 也更新同一份节流状态，避免手动后自动 sync 立刻二次触发」。
- **`refresh()`/`install()` 兜底不会因节流互相吞掉首次**：两者（`:639`→`kickGconvSync :651-653`、`:926`）都是 `postSync()`（force=false）。第一个到达 worker 的任务必因 MIN_VALUE 哨兵触发并推进时间戳，其余在 60s 内被吞——这正是节流本意，**不会导致首次漏触发**。副作用：`install()` 已同步后立刻改分组、再由 `kvset` 触发的 `refresh()` 兜底会被吞，新组要等下次 due 或手动 force；验收 SOP 第 4 步 `op=gcs sub=sync`（force）已覆盖，不阻塞。
- ⚠️ 但 `:83-85` 注释「失败留到下一次去抖重试 / 下一次 due 自然补齐失败的行」与第 4 条的半行不自愈冲突，属注释与事实不符，见 P1-1 配套。

### 4. 事务是否真能回滚（重点）

**推演成立，P0-1 第 3 条「失败整段回滚」字面未兑现；并发现半行无法自愈。**

- `inTransaction()`（`:398-422`）的意图：`begin.invoke(db)`(`:410`) → `begun = true`(`:411`) → `block()`(`:412`) → `success.invoke(db)`(`:413`) → finally `if (begun) end.invoke(db)`(`:416-420`)。block 抛异常时不标记成功、finally 仍 end ⇒ 整段回滚。结构本身正确。
- 但 `execLog()`（`:425-432`）是 `val e = runCatching { exec(db, sql) }.exceptionOrNull()`，失败只 `say(out, "op=.. FAIL ..")`（`:430`），**不抛出、不返回失败态**。`sync()` 的 `inTransaction` block（`:151-175`）与 `clean()` 的 block（`:205-208`）里每条写 SQL 都经 `execLog` ⇒ `block()` 几乎不可能抛 ⇒ `success.invoke` 无条件调用 ⇒ 事务被标记成功 ⇒ **单条 SQL 失败（insert 撞唯一索引、列名漂移、约束失败）时半行照样提交**。
- ⇒ `GroupConvPlan.kt:60`「让 Store 一次事务按序执行」与 `docs/_r54_gconv_design.md:297`「两表必须同一个事务」的原子性假设没兑现；`docs/_r54_view_review.md:276` 的 P0-1 第 3 条「事务内失败整段回滚，避免半行」**未兑现**。
- **额外独立发现：半行无法自愈。** `readExisting()`（`:284-312`）把 `rconversation`/`rcontact` 我方行按 username 取**并集**（`:303-304`），缺的一侧补空（`:307-308` `convMap[u] ?: (0L to 0L)`、`ctMap[u] ?: ""`）；`plan()`（`GroupConvPlan.kt:67-83`）对 `want ∩ have` 只产 `Update`（`:72-80`），只有 `(want - have)` 才产 `Insert`（`:81`）。于是「contact 有、conv 缺」或「conv 有、contact 缺」的半行，其 username 已在 `have` 里 ⇒ 永远只产 `Update`；Store 处理 Update（`:159-163`）跑 `repairSql`（`update rconversation`，缺行影响 0 行）+ `renameSql`（`update rcontact`，缺行影响 0 行）⇒ **缺失的那一行永远补不回来**。而 Insert 分支（`:164-172`）恰是 `deleteConv`/`deleteContact`/`insertConv`/`insertContact` 四条独立 SQL，任一失败即产生上述半行。⇒ `:83-85` 注释「`sync` 是全量对账，下一次 due 自然补齐失败的行」不成立，全量对账兜不住半行，只能靠 `clean()` 全删重建。
- 排查陷阱：`execLog` 打的 `FAIL` 行（`:430`）与「该组其实已部分落库」同时出现，日志会误导为「没写进去」。
- `begin.invoke` 抛异常时：`begun = true` 在其后（`:410-411`），抛异常 ⇒ `begun` 保持 false ⇒ finally `if (begun)` 为 false ⇒ 不调 `endTransaction` ✓ 正确（未 begin 不该 end）。`txMethod()`（`:383-388`）每次 `db.javaClass.methods.firstOrNull`，**无字段缓存 Method** ✓ 符合「绝不跨库缓存 Method」。
- 降级路径（`:402-407`）：三个事务方法任一反射失败 ⇒ 打 WARN 后 `block()` 无事务直写，此时半行风险更大但不静默 ✓。
- 定级 **P1**（理由见 ①），最小改法见 ③ P1-1。

### 5. ON→OFF 热切换的 listener 残留

**推演成立；定级 P2（健壮性，SOP 冷启已规避，非必然阻塞）。**

- `ConvFilter.kt:1796`：`if (gconvEnabled && rowGid == null && cv?.getTag(GCONV_ROW_TAG_KEY) != null) { cv = null }`。`gconvEnabled` 由 `rebuild()`（`:1243`）热更新，`kvset` 会调 `ConvFilter.refresh()` 触发重绘（`docs/_r54_gconv_design.md:508`、`docs/_r54_view_review.md:234-236`）。
- ON 时命中行被 `v.setTag(KEY, gid)`（`:1834`）打标并覆盖 `setOnClickListener`（`:1839`）/ `setOnLongClickListener`（`:1840-1843`），该 View 随 `wrapped.getView(r, cv, parent)`（`:1801`）回收进 RecycleBin。ON→OFF 热切换后 `gconvEnabled=false` ⇒ `:1796` 前置短路、不清理；`:1821` 的接管块也整块跳过、不会用宿主 listener 覆盖 ⇒ 残留的我方 listener 留在被复用的普通行上。
- B 方案自身的前提正是「微信复用 convertView 时不一定重设 listener」（`docs/_r54_view_review.md:74-78`，标注「需真机验证」）⇒ 在该前提下，残留 listener 会误开 `GroupDetailPage`/`GroupRowMenu`。推演自洽。
- **「去掉 `gconvEnabled` 前置」更安全且不破坏 OFF 零差异**：改为 `rowGid == null && cv?.getTag(GCONV_ROW_TAG_KEY) != null`。OFF 时 `rowGid` 因 `:1780` 的 `if (gconvEnabled) ... else null` 恒为 null（前半恒真），而 OFF 正常场景回收池里**没有**带我方 tag 的 View（OFF 时从不打 tag）⇒ `cv?.getTag(KEY) != null` 恒 false ⇒ 不触发 `cv = null`，cv 维持 `:1789` 的类型校验结果，**零差异保持**；唯一命中的就是 ON→OFF 热切换的残留 View（正是要清的）。这与 B 方案「源头阻断污染 View 被复用」的本意自洽——带残留 listener 的 View 每轮都被 `cv=null` 挡掉，永不回贴到真实行上。
- 代价：`cv?.getTag(int)` 在 OFF 每个复用行多一次稀疏表查找，热路径开销微小。
- **需真机验证**：微信复用 convertView 时是否重设 per-row listener（与上条 B 同前提）；SOP 第 3/7 步要求冷启，冷启后回收池为空，故不必然触发。

### 6. B 的 tag 方案正确性

**通过。**

- `GCONV_ROW_TAG_KEY = 0x47525643`（`ConvFilter.kt:93`）：`(0x47525643 >>> 24) = 0x47 = 71 >= 2` ✓ 满足 AOSP `View.setTag(int, Object)` 对 key 高字节 `>= 2` 的要求；未用 `generateViewId()`（可能 < `0x02000000` 抛 `IllegalArgumentException`），未用无 key 的 `v.setTag(obj)`（会覆盖微信自己的 tag）。
- `v.setTag(GCONV_ROW_TAG_KEY, gid)`（`:1834`）位于 `GroupConvPlan.groupIdOf(username)?.let { gid -> ... }`（`:1828`）命中分支内 ⇒ **只在命中行打标** ✓。
- `cv = null`（`:1796-1798`）只在污染场景触发：普通行 View 无我方 tag ⇒ `getTag(KEY) == null` 不命中；命中行复用成命中行 `rowGid != null` 不命中；类型不符已被 `convertView?.takeIf { viewTypeOf(it) == getItemViewType(position) }`（`:1789`）挡掉 ⇒ **不误伤正常回收** ✓。
- `rowGid`（`:1780-1782` `groupIdOf(ConvReader.username(wrapped.getItem(r)))`）与命中分支（`:1825-1828` `wrapped.getItem(r)`）用的是**同一个 `r`**（`:1774` `layout.convIndexOf(position)`），没有一处用 position、一处用 r ⇒ **不会错位** ✓。
- **需真机验证**：`0x47525643` 是否与该宿主 View 已有的 keyed tag 冲突（自定义魔数，撞车概率极低）。

### 7. G+H 的 `effEnabled`

**通过，无 OFF 回归。**

- 三处用法符合 `docs/_r54_gconv_design.md:536-539`：定义 `val effEnabled = enabled && !gconvEnabled`（`ConvFilter.kt:1248`）；喂 `configStamp`（`:1269-1277`，`enabled = effEnabled` 在 `:1271`）；light 路径 `if (filterId == NONE && db != null && effEnabled)`（`:1312`）。
- `:1326` 零改动分支 `db == null || !enabled` **保持原 `enabled`**（未改成 effEnabled）✓；`:1344` `if (groups.isEmpty() || gconvEnabled)` 用 `gconvEnabled` 本身 ✓。
- 翻转 `GCONV_MODE`：OFF→ON 时 `effEnabled` 从 `enabled`（`CHAT_GROUP_ROW_ENABLED` 默认 `"true"`）变 `false` ⇒ `configStamp` 改变 ⇒ 破 `:1299` 的 `configStamp == lastStamp` 短路 ⇒ 触发全量重建，消除 H「字段热 / layout 冷」半生效态 ✓；同时 gconv ON 时 light 路径 `effEnabled=false` ⇒ 不跑 `rebuildModels` 的 O(组数×2900) 空跑，消除 G ✓。
- `PinFoldLogic.configStamp` 全仓只在 `:1269` 被**调用**，其签名/实现未被改动（本轮 diff 不含该文件）✓ 符合「后者不应被改」。
- **OFF 无 `effEnabled != enabled` 回归**：`gconvEnabled=false` ⇒ `effEnabled = enabled && true = enabled` ⇒ `:1271`/`:1312` 与改前逐字等价 ✓。
- 边界记录：`enabled` 本身为 false 时，OFF 与 ON 的 `effEnabled` 都是 false ⇒ 翻转 `GCONV_MODE` 不改 `configStamp`、不破短路；但此形态下 layout 本就走 `:1326` 零改动分支、没有分组行，H 半生效态不存在 ⇒ 不构成回归。

### 8. OFF 回滚零差异（生命线）

**通过。**

- `kickGconvSync()`（`:651-653`）首行 `if (!runCatching { Kv.getBool(Keys.GCONV_MODE) }.getOrDefault(false)) return` ⇒ OFF 不 post。
- `onDataChanged` 同步块（`:1509`）`if (gconvEnabled) GroupConvStore.postSync()` ⇒ OFF 不 post。
- `rowGid`（`:1780-1782`）`if (gconvEnabled) ... else null` ⇒ 短路为 null；`:1796` `gconvEnabled && ...` ⇒ 不命中；`:1821` `if (gconvEnabled) { 接管 }` ⇒ 整块跳过。
- `:1344` `if (groups.isEmpty() || gconvEnabled)` = `|| false` ⇒ 与原 `groups.isEmpty()` 逐字等价。
- `:1248` `effEnabled == enabled`（见第 7 条）；`:1326` 保持原 `enabled`。
- `AntiRevoke.wxDb()`：`ConvFilter.kt` 全文已无该调用，gconv 生产侧仅 `GroupConvStore.kt:140`/`:193`/`:230`（都在 worker 内）；OFF 时连 `postSync` 都不发 ⇒ **完全不被调** ✓（比修复前更干净，修复前 `:1483` 还有一次 `&&` 短路判空）。
- `getItemViewType`（`ConvFilter.kt:1905`）、`getViewTypeCount`（`:1912`）、`PinFoldLogic.configStamp` 实现均不在本轮任何 diff hunk 内 ⇒ 未改动 ✓。
- `git diff --stat -- app/src/main` 只有 `Keys.kt +31` / `EntryReceiver.kt +44` / `ConvFilter.kt +144 -5`；5 处删除都是等价替换（`val cv`→`var cv`、`return ...`→`val v` + `return v`、`groups.isEmpty()`、`enabled`→`effEnabled`×2）；`git status --short` 无 ` D` 行 ⇒ 无组件删除。
- 唯一非零差异：`rebuild()` 每次多一次 `Kv.getBool(Keys.GCONV_MODE)`（`:1243`），量级可忽略，P2 观察项（`docs/_r54_view_review.md:188` 已记录）。

### 9. 红线

**通过。**

- **无新增宿主 hook**：本轮 `ConvFilter.kt` diff 新增代码里的 View 操作只有 `v.setTag`（`:1834`）、`v.setOnClickListener`（`:1839`）、`v.setOnLongClickListener`（`:1840`），都是 View 级 API。既有的 `XposedBridge.hookAllMethods`（`:434`）、`XposedHelpers.findAndHookMethod`（`:461`/`:490`）、`XposedHelpers.callMethod(recycler, "setViewTypeCount")`（`:827`）都在 diff hunk 之外，非本轮新增。
- **无 `setText` 碰标题**：`setText` 只出现在注释（`:1818`/`:1820`，写明「标题是宿主自绘 View、不碰标题、不 setText」），实际代码无 `setText` 调用 ✓。
- **未删组件**：`GroupedAdapter` 定义在 `ConvFilter.kt:1057`（非独立文件）、`GroupRowView.kt`、`ConvLayout.kt` 均存在（`find` 命中）；`git status` 无删除行 ✓。
- **`GroupConvPlan` 宽窄口径与 SQL 生成**：`ownWhere()` 宽口径（`GroupConvPlan.kt:89` `substr(username,1,6)='wxgrp_'`）、`groupIdOf`/`isOurs` 窄口径（`:30-42` 要求能反解出合法 gid）与 `ConvFilter.kt:1781`/`:1828`、`GroupConvStore.kt:176-180` 的用法自洽；`GroupConvPlanTest.kt` 18 例（含 7 个 `sql_*` 用例与 `assertSafeSql`/`assertAllQuotesPaired` 注入护栏）全过。⚠️ 该文件是 `??` 未跟踪、**无 git 历史可比对**，「相对上一版未变」静态判不了；只能确认「当前实现与 `docs/_r54_gconv_design.md` §10.3 定案一致且单测覆盖」，如实标注，不硬下结论。
- **`GCONVSTORE ` 每行前缀保留**：`TAG = "GCONVSTORE"`（`GroupConvStore.kt:50`）+ `say()`（`:495-497`）给每行加 `"$TAG $msg"`；`postSync/postClean/postStat` 的失败行（`:103`/`:114`/`:125`）手写同前缀 ✓ 验收脚本按它过滤。

### 10. 工具链一致性

**`dev.py q GCONVSTORE` 过滤仍能命中；`WAIT=2.5` 需真机验证。**

- `tools/_r54_gconv.py`：`WAIT = 2.5`（`:43`），`gcs` 分支 `tag = "GCONVSTORE"`（`:93`），`bc()` 发广播 → `time.sleep(WAIT)`（`:116`）→ `pull_log()`（`:117`）；`pull_log()` 调 `tools/dev.py q GCONVSTORE n`（`:77-80`）。
- `tools/dev.py` 的 `cmd == "q"` → `show(pull_dbg(), pat, n)`（`:314-317`）；`show()`（`:164-183`）用 `re.compile(pat).search(整行)` 过滤（`:168`），再按去时间戳的 `l[19:]` 去重（`:169-176`）。`LogX.i()`（`LogX.kt:42-46`）→ `file()`（`:68-94`）用 `synchronized(lock)`（`:70`）把日志写进**同一个** `/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt`（`:26`），行格式 `"$ts ${Thread.currentThread().name} $msg"`（`:90`）。⇒ Store 结果行由 `gconv-store` worker 线程打印时，`msg` 内容仍是 `GCONVSTORE ...`、仍落在同一文件、仍被 `q` 的正则命中。**线程改动不影响过滤** ✓。去重键 `l[19:]` 会截进线程名，不同线程的相同内容不会互删，对 `q GCONVSTORE` 无害。
- **`WAIT=2.5` 够不够：需真机验证。** `op=gcs` 改异步 post 后，`am broadcast` 返回只证明 `postSync` 投递成功（`EntryReceiver.kt:212` 立即返回），**不再代表 sync 跑完**。worker 里一次 sync 要全表读 3120 行（约 9000 次反射）+ 读写微信主库；且 worker 是单线程，若队列里排了 `kickGconvSync()`/`onDataChanged` 的 `postSync` 任务需先等。静态判不了真机耗时 ⇒ 建议验收时若 `dev.py q GCONVSTORE` 没看到 `sync done own=`，先隔几秒再拉一次（或把 `WAIT` 提到 5s），不要因 2.5s 无日志就判 sync 没跑；`dev.py q` 侧重「拉现有日志」，`cmd_probe` 的 `dbg_size` 增长闸门（`dev.py:273-289`）可辅助判「有没有输出」。

## ③ P0 / P1 / P2 清单（精确修改建议，不改源码）

### P0：0 条

首轮 P0 的实质（两入口并发、主线程全表读写库）已由单一后台线程 + 全入口 post 闭环，见 ② 第 2 条。本轮不存在新的 P0 级危害。

### P1（1 条，本轮 verdict 的唯一阻塞项）

**P1-1 · 事务「失败整段回滚」未兑现，且半行无法自愈**

- 位置：`GroupConvStore.kt:398-422`（`inTransaction`）、`:425-432`（`execLog`）、`:151-175`（`sync` 的事务 block）、`:205-208`（`clean` 的事务 block）、`:83-85`（误导性注释）。
- 事实：`execLog` 吞掉每条 SQL 异常只打日志 ⇒ `block()` 不抛 ⇒ `setTransactionSuccessful` 无条件调用 ⇒ 单条 SQL 失败时半行提交；`readExisting` 并集语义 + `plan` 的 Update-only + `repairSql/renameSql` 都是 `UPDATE` ⇒ 缺失的那一行永远补不回来（详见 ② 第 4 条）。
- **最小改法（二选一，仅建议，不改源码）**：
  - (a) 让失败在事务内可传播：给 `execLog` 增加一个失败收集参数（如 `failures: MutableList<Throwable>`）或改为返回 `Throwable?`；`inTransaction` 的 `block` 末尾若收集器非空则 `throw` 一个汇总异常 ⇒ 跳过 `:413` 的 `success.invoke` ⇒ finally `:416-420` 的 `endTransaction` 回滚。外层 `sync()`/`clean()` 已有 `runCatching{}.getOrElse { say(out, "FATAL ...") }`（`:141-187`、`:194-224`），**不需要新增异常处理路径**，异常会被现有兜底打 FATAL 并原样返回日志行。
  - (b) 等价做法：让 `inTransaction` 向 block 提供一个「事务版 exec」，该 exec 在失败时直接抛；非事务上下文（`:402-407` 的降级直写）仍保持只打日志的现行行为，用参数区分即可。
  - ⚠️ 硬约束：`setTransactionSuccessful` 必须只在**所有** SQL 成功时调用；`clean()` 的 block 与 `sync()` 同样适用，不能只改一处。`txMethod` 每次重扫方法表、不缓存 `Method` 的现状保持不变。
- **配套（一并处理）**：把 `:83-85` 的注释「`sync` 是全量对账，下一次 due 自然补齐失败的行」改成与事实一致的表述——半行不会被 `plan` 自愈，失败需 `clean()` 全删重建或修好事务后回滚重来。
- **验证建议**：单测侧用 fake db 反射桩补一条「事务内任一 `execLog` 失败 ⇒ 不调 `setTransactionSuccessful`、`endTransaction` 仍被调用」的用例；真机侧制造一条必然失败的 insert（如同名 username 不先删），看 `sync done` 前是否出现 `FATAL`/回滚、`stat` 的 `convOwn` 与 `contactOwn` 是否成对。
- **定级理由**：不是 P0——并发/主线程卡顿已消除、半行只落 `wxgrp_` 我方命名空间、正常路径不触发、`clean()` 可兜底；不是 P2——P0-1 明确保证落空、半行可持久残留、`FAIL` 日志与实际部分落库不一致会误导排查。

### P2（不阻塞，含健壮性与记录项）

- **P2-1 · ON→OFF 热切换 listener 残留**（`ConvFilter.kt:1796`）：把清理条件改为 `rowGid == null && cv?.getTag(GCONV_ROW_TAG_KEY) != null`（去掉 `gconvEnabled` 前置）。OFF 正常场景 `cv` 不带我方 tag ⇒ 不触发、零差异保持；命中的正是热切换残留 View，与 B 方案「源头阻断污染 View 被复用」自洽。验收 SOP 冷启已规避，属健壮性（② 第 5 条）。
- **P2-2 · `:83-85` 注释与 `plan` 事实不符**：同 P1-1 配套，可与 P1-1 一次改完。
- **记录项（不需改码）**：① `rebuild()` 每次多一次 `Kv.getBool(GCONV_MODE)`（`ConvFilter.kt:1243`），量级可忽略；② `op=gcs sync` 的 `force=true` 会推进 `lastSyncMs`（`GroupConvStore.kt:96`），使 60s 内 `refresh()` 兜底被吞，SOP 第 4 步 force 已覆盖；③ `GroupConvPlan.kt` 是未跟踪文件、无 git 历史可比对，「宽窄口径与 SQL 生成相对上一版未变」静态判不了，只能确认当前实现与 §10.3 定案一致且单测覆盖。

### 需真机验证（静态判不了，不硬下结论）

1. wcdb 单连接上 worker 线程 `rawQuery`/`execSQL` 是否抛 `SQLiteException`（现已单线程，风险显著降低）；一次 sync 的真机耗时是否真的不占主线程。
2. 微信复用 convertView 时是否重设 per-row `OnClick`/`OnLongClick` listener（B 方案与 P2-1 的共同前提）。
3. `tools/_r54_gconv.py` 的 `WAIT=2.5` 是否够 worker 线程 sync 跑完并被 `dev.py q GCONVSTORE` 拉到。
4. `View.setTag(int, Object)` 的 `0x47525643` 是否与该宿主 View 已有的 keyed tag 冲突。
5. `GroupDetailPage.open()` / `GroupRowMenu.show()` 在 `ConversationFolderItemView` 上的实际可用性（`docs/_r54_gconv_design.md:514` 已列为未验证）。

### 上机纪律（若决定带病上机做验收）

- 验收中一旦 `dev.py q GCONVSTORE` 出现任何 `op=... FAIL`，或 `stat` 的 `convOwn != contactOwn` / `own` 与分组数不符 ⇒ **立即停止**：先 `op=gcs sub=clean` 再 `op=gcs sub=sync`（force），确认两表行数成对；不得据该轮下「分组行是否正确」的结论。
- 正式发布前必须补修 P1-1。

## ④ 实际读过的文件清单

**git 变更面**
- `git status --short`（确认 `GroupConvStore.kt`/`GroupConvPlan.kt`/`GroupConvProbe.kt`/`GroupConvPlanTest.kt` 为 `??` 未跟踪、无 ` D` 删除行）
- `git diff -- Keys.kt EntryReceiver.kt ConvFilter.kt`（全文）、`git diff --stat -- app/src/main`

**源码（全文）**
- `wxgroup/app/src/main/java/com/lx/wxgroup/feature/GroupConvStore.kt`（1-498）
- `wxgroup/app/src/main/java/com/lx/wxgroup/data/GroupConvPlan.kt`（1-132）
- `wxgroup/app/src/main/java/com/lx/wxgroup/probe/GroupConvProbe.kt`（1-599）
- `wxgroup/app/src/test/java/com/lx/wxgroup/data/GroupConvPlanTest.kt`（1-350）
- `wxgroup/app/src/main/java/com/lx/wxgroup/util/LogX.kt`（1-143）

**源码（区段 / 检索）**
- `wxgroup/app/src/main/java/com/lx/wxgroup/feature/ConvFilter.kt`：`:88-99`、`:628-659`、`:918-931`、`:1236-1327`、`:1325-1364`、`:1490-1517`、`:1768-1852`；另全文件 Grep 定位 `GroupConvStore.`/`AntiRevoke.wxDb`/`getItemViewType`/`getViewTypeCount`/`configStamp`/`setText`/`findAndHookMethod`/`class GroupedAdapter`
- `wxgroup/app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt`：diff 全文 + Grep `GroupConvStore.`
- `wxgroup/app/src/main/java/com/lx/wxgroup/data/Keys.kt`：diff 全文 + Grep
- 全 `app/src` Grep：`GroupConvStore.`、`AntiRevoke.wxDb`、`GCONVSTORE`
- 编译产物：`find app/build/tmp/kotlin-classes/debugUnitTest -name '*Test.class'`（45 个）、`GroupConvPlanTest.class` 存在性

**工具脚本（全文）**
- `tools/_junit.sh`（1-54）、`tools/dev.py`（1-373）、`tools/_r54_gconv.py`（1-121）

**设计 / 首轮复核（全文）**
- `docs/_r54_gconv_design.md`（1-572，含 §10.4）
- `docs/_r54_view_review.md`（1-345，含 P0-1 第 3 条）

**独立跑过的命令**
- `./gradlew compileDebugUnitTestKotlin assembleDebug --offline -q --console=plain` ⇒ GRADLE_EXIT=0
- `bash tools/_junit.sh` ⇒ OK (919 tests) / JUNIT_EXIT=0
- `bash tools/_junit.sh com.lx.wxgroup.data.GroupConvPlanTest` ⇒ OK (18 tests) / EXIT=0

**未读 / 未验证**：`PinFoldLogic.configStamp` 实现体、`ConvLayout.kt`/`ConvReader.kt`/`GroupDb.kt`/`AntiRevoke.kt` 实现体（首轮复核已验，本轮只确认未被改动）、`wxgroup/tools/dev.py` 全文、任何 dex/微信宿主字节码；未连设备、未跑 adb、未跑 `git commit`、未改 `app/src/main`。

## ⑤

[DONE]


