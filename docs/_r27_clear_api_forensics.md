# R27 清位 API 取证（第三轮 · 2026-10-26 23:39~23:47）

## 1. 实验设计

| 项 | 值 |
|---|---|
| 探针模式 | `sub=catch --es table SnsInfo --ei sec 600` |
| 覆盖 | `SQLiteDatabase` 的 `update/updateWithOnConflict/delete/insert/insertWithOnConflict/insertOrThrow/replace/replaceOrThrow/execSQL/rawQuery/rawQueryWithFactory/query` + `SQLiteStatement` 的 `executeUpdateInsert/executeUpdateDelete/execute`（共 46 hooks） |
| 预算 | `allWrites=true` ⇒ `MAX_LINES_CATCH=1500`，不按关键字限流，只按表名 `SnsInfo` 过滤 |
| 时间 | 23:39:48 开窗（到 23:49:48） |
| 靶子 | verity「9」`snsId=-3405726322886831589`，`t=1793029040`（23:37:20），`type=2`，`src=2` |
| 前置 | 23:39:26 `srcfix` 记账，ledger 3019→3021；顺带把「6/7/8」补回 `src=2` |

## 2. 时间线（已确证部分）

```
23:37:20  verity 发「9」                   t=1793029040
23:38:59  dump                             9 → src=2   ← 落库，可见
23:39:26  srcfix                           ledger=3021，6/7/8 补回 2
23:39:48  catch 开窗（46 hooks / 600s / 表=SnsInfo）
23:40:5x  用户：「删完」
23:41:50  dump                             9 → src=2   ← 删除通知尚未落库
23:41:5x  退出重进朋友圈（keyevent 4 → 发现 → 朋友圈）
23:42:05  rawQuery  select snsId from SnsInfo where (sourceType & 2 != 0)
23:42:21  rawQuery  select *,rowid from SnsInfo where (sourceType & 2 != 0)
23:42:21  rawQuery  select *,rowid from SnsInfo where snsId=-3405732352698666482
23:42:21  update    SnsInfo | ... localFlag=2 sourceType=2 ...   ← 针对 hnk03nn（type=15 未删）
23:42:21  update    StarSnsInfo | ... sourceType=2 ...            ← 收藏表同步
23:42:22  delete    SnsWsFoldGroupDetail | groupId=? -3405768558946217460   ←「6」
23:42:22  delete    SnsWsFoldGroupDetail | groupId=? -3405753000984047083   ←「7」
23:42:22  delete    SnsWsFoldGroupDetail | groupId=? -3405736005952990706   ←「8」
23:42:22  delete    SnsWsFoldGroupDetail | groupId=? -1726322886831589 对应「9」
23:42:22  rawQuery  SELECT rowid, * FROM SnsInfo WHERE (SnsInfo.sourceType & 2)  ← 清位后重建列表
23:42:57  dump                             9 → src=0, 8 → src=0   ← 已清
```

## 3. 硬结论

### 3.1 ⭐ 删除通知是「按 snsId 逐条精确处理」的
`SnsWsFoldGroupDetail` 的 4 条 delete 分别对应 6/7/8/9 —— **正好是我们曾补位、且服务端确认已删的那 4 条**。
⇒ 微信手里有一份「哪些 snsId 已被删」的精确清单，不是批量模糊清。
⇒ 这也解释了前一阶段「清位不是每轮刷新都跑」：**通知消费完就不再有**，重复刷新不会二次清位。

### 3.2 ~~被删 4 条在清位瞬间**零 SQL 写入**~~ ⛔ 已被 §4 推翻
窗口内对 `SnsInfo` 的写只有 1 条（`sourceType=2`，打给未删的 hnk03nn 那条）。
被删 4 条没有任何 `update` / `execSQL` / `SQLiteStatement.execute*` 记录。

**这个结论是错的**：不是没写，是探针去重把第 2 条起的写入全部吞掉了，
而 `detail.take(110)` 恰好在 `sourceType` 之前截断。见 §4。

### 3.3 ⚠️ 证据缺口（本轮的失误，必须承认）
23:42:22 → 23:42:57 之间的日志我**没有完整采样**：
- 拉日志时用 `dev.py q 'SNS ' 400` + `head -25`，catch 全量模式下 400 行只覆盖数秒，23:42:22 之后的记录被截断。
- 23:44 我执行了 `logclear`，模块日志文件被清；`adb shell logcat -d` 缓冲区也只剩 23:45 之后的 14 行。
⇒ **无法排除清位发生在 23:42:22~57 之间**。3.2 只能表述为「清位瞬间零写入」的**待复核**结论，不能当定论。

## 4. ⛔ 根因修正（23:52 定案）：是探针自己去重吞了证据

前一轮「清位零写入」的结论**是错的**，根因在 `SnsHuntProbe` 自己的去重逻辑，不在微信。

### 4.1 代码事实（`SnsHuntProbe.kt` 主路径）

```kotlin
val detail = "db=" + dbPath.take(60) + " | " + head.take(headMax) + " | " + tailStr.take(240)
val keyLen = if (allWrites) 110 else 60
val key = "$sig [$tag] ${detail.take(keyLen)}"
val n = counts.merge(key, 1) { a, b -> a + b } ?: 1
if (n == 1 && printed.get() < maxLines()) { ... 打印 ... }
```

`dbPath` = `/data/user/0/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a90`（56 字符），
所以 `detail` 的前 62 字符恒为 `db=<路径> | `，`detail.take(110)` 只剩 **48 字符**给 SQL/表名：

```
db=/data/user/0/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a90 | SnsInfo | subType=0 localPrivate=0 pravited=0
                                                                                                    ↑ 到这里就截断了
```

**`sourceType` 在截断点之后** ⇒ `update SnsInfo ... sourceType=2` 与 `sourceType=0`
的 `key` 完全相同 ⇒ `n` 变成 2、3、4… ⇒ `n == 1` 不成立 ⇒ **除第一条外全部静默丢弃**。

### 4.2 后果

- 23:42 那次窗口里唯一打印出的 `update SnsInfo sourceType=2`，正是该 key 的**首次**出现。
- 之后所有 `update SnsInfo`（极可能包含把 `sourceType` 写 0 的清位语句）全被吞。
- 我据此得出「清位不走 SQL / 走 native」，**结论方向完全相反**。
- 同族风险：所有用 `sub=catch` 做过的历史取证（群免打扰开关落点等）都需要重新审视——
  凡「同一调用点、不同 values」的写入，第二条起都可能被漏记。

### 4.3 修法（已改）

`allWrites`（catch 模式）下把 `values`(`tailStr`) 摘要纳入 key：

```kotlin
val key = if (allWrites) {
    "$sig [$tag] " + head.take(80) + "|" + tailStr.take(160).hashCode()
} else {
    "$sig [$tag] ${detail.take(keyLen)}"
}
```

`update SnsInfo` 的 `head` 就是表名，`tailStr` 是 values 拼接，`sourceType=2` 与 `0`
hash 不同 ⇒ 两条都会打印。相同 values 仍去重（避免刷屏）。

## 6. ⭐ 真机验证（23:54，修复后一次命中）

改完探针、装包、双冷启后，23:54:10 重开 `catch table=SnsInfo sec=600`，
23:54:25 点「发现 → 朋友圈」触发首屏同步。结果：

### 6.1 探针修复生效
同一调用点 `SQLiteDatabase.update` 打印 **98 条**（修复前同一 key 只打 1 条），
`SnsInfo` / `StarSnsInfo` 各 23 条 update + 23 条 updateWithOnConflict（成对）。

### 6.2 清位语句被完整抓到

```
23:54:35.476  update  SnsInfo | subType=0 localPrivate=0 pravited=0 localFlag=2 sourceType=0 ...
23:54:35.485  update  SnsInfo | ... sourceType=0 ...
23:54:35.495  update  SnsInfo | ... sourceType=0 ...
23:54:35.504  update  SnsInfo | ... sourceType=0 ...
```

- **4 条 `sourceType=0`，间隔 10ms**，正好对应我们补过位的 6/7/8/9 四条。
- 同批还有 `sourceType=2` × 16、`sourceType=10` × 4（未删的正常行）。
- 实际执行入口是 `SQLiteDatabase.update` → `updateWithOnConflict`，**不是 `execSQL`**。
- values 里 `localFlag=2` 未变 ⇒ 印证 `localFlag` 与可见性无关。

### 6.3 由此推翻的旧结论

| 旧结论 | 真值 |
|---|---|
| 「清位不走 SQL，走 native」 | ❌ 走 `update("SnsInfo", {sourceType=0})` |
| 「清位不是每轮刷新都跑，只在有删除通知那轮跑」 | ✅ **这条原来是对的**（见 §10 修正） |
| 「`onExecSqlAfter` 钩子挂不上，方案 B 空挂」 | ❌ 钩子本身没挂错，是挂错了 API 层（`execSQL` → `update`/`updateWithOnConflict`） |

### 6.4 机制定论

微信落库是**「服务器对象整行 upsert」**：`sourceType` 的值由服务端数据决定，
服务端对已删条目返回 0，微信就把整行（含我们补的 2）覆盖成 0。
⇒ 补位只能事后修正，**只要服务端再次带回该 snsId 就会被覆盖** ⇒ 方案 B 必须挂在每一轮 update 之后。

## 7. 方法论修正（下轮必须遵守）

1. **catch 全量模式下禁止 `head -N` 截断**：`allWrites` 每轮朋友圈加载产生上千行，必须直接落文件再筛，不能在线 head。
2. **靶子被清后立刻拉日志**：dump 发现 `src` 变 0 的那一秒，立即 `dev.py q` 拉最后 2000 行并存到 `.tmp/clear_<ts>.log`，再离线分析。
3. **不要在中途 logclear**：取证窗口期内任何 `logclear` 都会毁掉回溯能力。
4. **采样必须早于动作**：删完到拉日志之间不得超过 5 秒；否则 1500 行预算会被新 SQL 冲掉。

## 8. 结论与下一步

清位 API **已钉死**：`SQLiteDatabase.update` → `updateWithOnConflict`，表 `SnsInfo`，
values 里 `sourceType=0`，每轮首屏同步对服务端已删的每条各写一次。
不再需要 verity 配合做任何实验。

两条可选路线：

### 路线 B（补位下沉到 update 之后）
- 改 `SnsGuard` 的钩子目标：`execSQL` → `SQLiteDatabase.updateWithOnConflict`
  （判断 `table == "SnsInfo"` 且 values 含 `sourceType`）。
- 在 **after** 回调里对台账中"曾可见但当前 0"的行立即补回 2。
- 效果：每轮同步落地后库里 `src` 都是 2 ⇒ **断网 / 冷启走读库路径可见**。
- 代价小（单点改动 + 已有 `SnsTimelineSourceFix` 台账与分块 UPDATE 逻辑可直接复用）。
- ⛔ 天花板不变：联网刷新时界面吃响应对象，仍看不到。

### 路线 A（Response-Merge）
- 在时间线响应处理处，把台账里「曾可见、但响应中没有」的动态注入列表。
- 这是唯一能让「下拉刷新后仍可见」成立的路。
- 风险高：需反射构造 `SnsObject` 并塞进响应列表。

**建议顺序**：先做 B（改动小、能立刻救断网场景、且能顺便验证"列表到底读不读库"），
再上 A。两者共用同一张台账表 `sns_visible_src`。

## 9. 遗留
- `SnsGuard.kt` 里现在挂在 `execSQL` 上的 `reFixAfterClear` 是**空挂**，需要迁到 `updateWithOnConflict`。
- 探针 `SnsHuntProbe` 的 STMT 路径（`SQLiteStatement`）也是 `n == 1` 去重，
  但那里只有 SQL 模板、拿不到 bind 值，模板相同即同一语句，去重是合理的，无需改。

## 10. 方案 B 实现与真机验证（2026-10-26 00:10）

### 10.1 代码改动（`feature/SnsGuard.kt`）
- `install()` 新增 `hookAllMethods(cls, "updateWithOnConflict", snsUpdateGuard)`，
  常驻钩子从 2 个变 3 个（delete / execSQL / updateWithOnConflict）。
- 新增 `snsUpdateGuard`（只实现 `afterHookedMethod`）+ `onSnsUpdateAfter`，
  三道闸：`args[0] == "SnsInfo"` → `sourceType == 0` → 1500ms 节流。
- 新增 `sourceTypeOf()`：**反射**调 `getAsInteger("sourceType")`，不 `as? ContentValues`
  （真机确认微信传入的是 `android.content.ContentValues`，但反射更稳且能按 class 缓存 Method）。
- 取不到值时打**一次** `LogX.w`，绝不允许再静默失效。
- 新增常量 `UPDATE_FIX_MIN_GAP_MS = 1500L`。

### 10.2 独立复核（deepseek-v4-pro）
7 项 PASS，1 项非阻断 RISK：`updateFlag` 这类"只改 localFlag"的 update 也会命中
`table == SnsInfo`，取不到 `sourceType` 时会消耗掉那一次告警。

### 10.3 真机结果
1. ✅ 钩子挂载并触发，真机日志：
   ```
   00:09:52.377 SNS update hook: no sourceType on android.content.ContentValues (re-fix disabled)
   ```
   证实 ① 钩子确实挂在 `updateWithOnConflict` 上并被执行；② 微信有"只改部分字段"的 update
   （RISK 应验，非阻断）。
2. ⚠️ 但 `re-fix after update` **未出现** —— 因为该轮微信压根没写 `sourceType=0`。
3. 🔑 关键观察：00:10:29 手动 `srcfix` 把 4 条补回 `2` 后，00:10:58 进朋友圈复查，
   **6 条全为 `src=2`，一条都没被清**。

### 10.4 结论修正（推翻 §6.3 那一行）
"每轮同步都整行 upsert" 是**过度概括**。准确表述：

> 微信只在**服务端这一轮确实返回了该 snsId** 时才做整行 upsert；
> 普通进入/刷新（无新内容）不碰这些行。
> ⇒ 补位一旦成功，只要不再有带回该 snsId 的同步，就能**长期保持**。

这对方案 B 是**好消息**：钩子只需在"真发生覆盖"时补一次，日常零负担。

### 10.5 待完成的最后一环
需要一次"带回已删内容"的同步来闭环验证钩子补位。
靶子现成：verity「10」`snsId=-3405721048722501111`（当前 `src=2`，台账已记）。
删掉它 → 同步带回 `sourceType=0` → 预期日志出现
`SNS srcguard re-fix after update fixed=N`，且 dump 复查 `src=2`。

## 11. 方案 B v2（延迟合并补位）· 真机闭环（2026-10-27 00:29~00:40）

### 11.1 v1 为什么失败

v1 是「第一条写 0 命中 → 立刻 `reFixAfterClear` + 1500ms 节流」。真机 00:18 实测：

```
00:18:24.771  SNS srcguard re-fix after update fixed=1   ← 只补回 1 条
00:19:00.194  -3405721048722501111  src=0                ← 被同批后续 update 覆盖
00:19:00.196  -3405726322886831589  src=0
00:19:00.200  -3405736005952990706  src=0
```

一轮同步连写 3 条 `sourceType=0`（间隔 10ms），节流把第 2、3 条挡掉；第 1 条补回后又被后面的整行 upsert 覆盖。
**⇒ 节流思路本身是错的：不是「少补几次」，而是「必须等这一批写完再补」。**

### 11.2 v2 实现（`feature/SnsGuard.kt`）

| 项 | 做法 |
|---|---|
| 触发 | `updateWithOnConflict` after → `args[0]=="SnsInfo"` && `sourceType==0` && `!bypass` |
| 不立刻补 | 记 `lastUpdateZeroAt`，`fixScheduled` CAS 抢单航班，`postDelayed(350ms)` |
| 静默判定 | `runDeferredFix` 每 350ms 复查 `lastUpdateZeroAt`，仍在写就继续等（上限 2000ms） |
| 补位后自检 | 若补位期间又有写 0（`lastUpdateZeroAt > startedAt`）→ 再排一轮 |
| 线程 | `FixWorker` = Kotlin `object` + `Handler(HandlerThread("sns-srcfix"))`，全局唯一 |
| 防卡死 | `postDelayed` 失败时回滚 `fixScheduled`（复核 RISK 3，已加固） |

### 11.3 真机结果（全部 PASS）

| 场景 | 结果 |
|---|---|
| 进朋友圈首屏同步（3 条被清） | `00:29:33.892 sns-srcfix re-fix after burst fixed=5 waited=0ms cost=52ms` → dump 三条 `0 → 2` ✅ |
| 静置 80 秒 | 8 条全 `src=2`，无二次清位 ✅ |
| 下拉刷新（带回服务端 0） | 仍 `src=2`，无新清位 ✅ |
| 恢复网络后同步再清 2 条 | `00:37:20.177 re-fix after burst fixed=2 waited=350ms cost=50ms` → 全 `src=2` ✅（**延迟分支真实生效**） |
| 断网冷启 + 进朋友圈 | 界面依次出现 `verity 10 · 48分钟前`、`verity 9 · 58分钟前`、`verity 8 · 昨天` ✅ |

### 11.4 边界的最终确认

同一时刻（00:36 联网 vs 00:39 断网），库里都是 `src=2`：

- **联网**：界面「1分钟前 → 47分钟前 → 昨天」之间**没有** verity 三条 ⇒ 时间线吃响应对象。
- **断网冷启**：三条全部显示 ⇒ 读库路径吃 DB。

**⇒ 方案 B 的价值边界 = 断网 / 冷启 / 离线浏览；联网刷新可见必须靠方案 A（Response-Merge）。**

### 11.5 复核（deepseek-v4-pro，`.tmp/review_fixb2.md`）
7 项：5 PASS、2 RISK（`== startedAt` 毫秒边界可自愈；`postDelayed` 异常未回滚 → 已加固）。
