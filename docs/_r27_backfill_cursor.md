# R27 · 图片回填的跨轮进度记忆（修 R26 复核中危项 §8(b)）

## 1. 问题

`MomentsExport.backfillImages` 每轮都 `SnsScanCursor()` 从最新 (`ct = sid = Long.MAX_VALUE`) 重扫，
配额 `MAX_BACKFILL_PER_RUN = 200` 行。后果（R26 复核 §8(b)）：

1. 归档行每轮徒劳重试（图早已取不回，仍占配额）；
2. **队头阻塞**：最新 200 条只要持续补不到（`imgDone` 不成立 / 缓存里没图），
   更旧但图还取得到的行**永远轮不到**；
3. KDoc 写的「补不完的下一轮继续」是假话，实际是「补不完的重头再来」。

## 2. 选型：方案 1（持久化低水位游标）

| 方案 | 判定 |
|---|---|
| **1. 持久化低水位游标（选中）** | 唯一能真正解决队头阻塞的方案：本轮扫到哪儿落盘，下轮从那儿继续，扫到底回绕。改动集中在 `backfillImages` 一个函数 + 两个纯函数，每轮代价仍有界。 |
| 2. 持续 miss 的行跳 N 轮（内存记 miss 次数） | **不能解决问题**。占满配额的是「已补好 / 没有 txt」的行（`st.rows++` 在判 `imgDone` 之前就加了），不是 miss 的行；跳 miss 行挡不住队头。且内存计数在进程重启后清零。 |
| 3. 回填改最旧优先 | 只是把饥饿对象从「旧的」换成「新的」。而最新那批**才是唯一图还可能在缓存里的**（防撤回语义要求趁新留存），反向优先等于先补最补不到的一批。 |

持久化用现成的 `Kv`（`GroupDb` 的 pref 表，纯字符串，7×24 可用），不新引依赖。
`SnsScanCursor` 直接复用（加一个 `seek()`），没有另造游标类型。

## 3. 改动点

### 3.1 纯逻辑（可进 JVM 单测）— `app/src/main/java/com/lx/wxgroup/feature/MomentsLogic.kt:307-360`

| 符号 | 行 | 作用 |
|---|---|---|
| `FILL_SRC_CURRENT` / `FILL_SRC_ARCHIVE` | 315-316 | 源编号用 `Int`，因为本文件禁止 import 项目内类（`SnsScanCursor.Src` 进不来） |
| `data class FillCursor(src, ct, sid)` | 325 | 低水位游标值对象 |
| `encodeFillCursor` | 328 | 落盘格式 `src:ct:sid`（人类可读，adb 查库直接看懂） |
| `parseFillCursor` | 336-347 | **判据**：任何脏值 → `null`（= 从最新开始），不抛异常、不猜；`ct` 必须 > 0，`sid` 吃负号 |
| `fillCursorAfterRound` | 358-359 | **判据**：`exhausted`（两源都扫到底）→ `null` 回绕；否则保留 `last` |

### 3.2 接线 — `app/src/main/java/com/lx/wxgroup/feature/MomentsExport.kt`

| 位置 | 行 | 改动 |
|---|---|---|
| `SnsScanCursor.seek()` | 135-148 | 新增；`ct <= 0` 直接忽略，保持「从最新开始」 |
| `backfillImages` KDoc | 1225-1237 | 「限次」→「限次 + 跨轮轮转」，写清回绕规则 |
| `backfillImages` 恢复游标 | 1246-1250 | `Kv.getString(MOMENTS_FILL_CURSOR)` → `parseFillCursor` → `cur.seek(...)`；读失败只告警 |
| `backfillImages` 配额判定位置 | 1271-1273 | 从 `advanceTo` **之后**挪到**之前**：否则游标停在一条没处理的行上，下轮键集严格 `<` 会把它跳过一整圈 |
| `backfillImages` 两处结束分支 | 1263-1296 | 记 `wrapped = true`（两源都扫到底），用于回绕 |
| `backfillImages` 落游标 | 1299-1309 | 一轮只写一次；`null` → 写空串（= 清空） |
| 聚合日志 | 1311-1317 | 加 `from=<C|A|NEW>:<ct>` / `next=...` / `wrapped=`，**仍然只有一行**，没新增明细行（不挤占 5000 行环形缓冲） |
| `fillSrcCode` / `fillSrc` / `fillSrcName` | 1322-1337 | `Int` ↔ `Src` 转换 + 日志展示 |
| `stat()` | 1722 | 加 `fillCursor=`（`NEW` = 下一轮从最新开始），真机看它有没有在往前推 |

### 3.3 配置键 — `app/src/main/java/com/lx/wxgroup/data/Keys.kt:221-229`

`MOMENTS_FILL_CURSOR = "moments.fill_cursor"`，**刻意不进 `Prefs.ALL`**（纯运行状态，不该出现在设置页 / 备份里），与 `MOMENTS_LAST_EXPORT_AT` 同规格。

### 3.4 单测 — `app/src/test/java/com/lx/wxgroup/feature/MomentsBackfillCursorTest.kt`（新增，12 条）

用真机量级的值（`ct = 1_770_000_000_000`、`sid = -3447438253133393222`）：

- 编解码往返（`encodeParseRoundTripKeepsNegativeSidAndMillisCt`）
- `ct` 不被截断成 Int（`parseKeepsCtAsMillisNotTruncatedToInt`）
- `sid` 负号保真（`parseKeepsNegativeSid`）
- 两个源都能解析（`parseBothSources`）
- 14 种脏值一律 `null`（`parseRejectsDirtyValuesAsRestartFromNewest`）
- 配额截断保留位置 / 扫到底回绕 / `last=null` 不崩（3 条 `afterRound*`）
- `seek` 恢复位置与源（`seekRestoresPositionAndSource`）
- `seek` 忽略 `ct<=0`（`seekIgnoresNonPositiveCt`）
- 恢复到归档后归档扫完必须结束本轮（`seekToArchiveDoesNotSwitchBackToCurrent`）
- 从当前源中段恢复，切源时归档仍从最新开始（`seekToCurrentStillSwitchesToArchiveFromItsNewest`）

## 4. 变异验证（4 个变异，全部变红）

| # | 变异 | 结果 |
|---|---|---|
| 1 | `parseFillCursor` 的 `ct` 改 `toInt()`（截断毫秒时间戳） | **红 3 条**：`encodeParseRoundTrip...` / `parseKeepsNegativeSid` / `parseKeepsCtAsMillisNotTruncatedToInt` |
| 2 | `seek` 去掉 `if (ct <= 0L) return` 守门 | **红 1 条**：`seekIgnoresNonPositiveCt` |
| 3 | `fillCursorAfterRound` 恒返回 `last`（不回绕） | **红 1 条**：`afterRoundWrapsToNewestWhenBothSourcesDrained` |
| 4 | `parseFillCursor` 放过 `ct <= 0` 脏值 | **红 1 条**：`parseRejectsDirtyValuesAsRestartFromNewest` |

完成后已逐条还原并用 `grep` 复核（`ct` 用 `toLongOrNull`、`ct <= 0L` 守门在、`fillCursorAfterRound` 带回绕分支）。

跑测方式（**没跑 gradle**，用 gradle 缓存里的 kotlinc 直接编这两个纯文件 + 单测，
`-Xfriend-paths` 让 `internal` 的 `SnsScanCursor` 可见）：

```
kotlinc(2.0.21) MomentsLogic.kt MomentsExport.kt Keys.kt  →  12 tests, OK
```

⚠️ 该临时脚本已删除；正式编译/全量单测仍由主会话的 gradle 负责。

## 5. 真机怎么验

`op=moments sub=imgfill`（或等定时轮）连打两轮，看聚合行的三个新字段：

```
MOMENT img backfill rows=200 ... from=NEW:- next=C:1770000000000 wrapped=false
MOMENT img backfill rows=200 ... from=C:1770000000000 next=C:1769999000000 wrapped=false
```

- `next` 的 `ct` 必须比 `from` **更小**（在往更旧走）→ 轮转生效；
- 一直走到 `wrapped=true`、`next=NEW` → 回绕完成，下一轮又从最新开始；
- `sub=estat` 里的 `fillCursor=` 与上面 `next` 一致（游标确实落了盘）；
- 反向判据：若 `from` 连续多轮都是 `NEW` 而 `rows=200`，说明游标没写进去（`Kv` 写失败/`db not ready`）。

## 6. 边界与已知取舍

- **主循环不写游标**：新动态必须优先，主循环永远从最新开始（防撤回语义），游标只是回填的进度记忆。
- **`imgfill` 探针共用同一份游标**：手动补图 = 从上次停的地方接着补，语义一致；但它也会推进游标。
- **恢复到归档位置的那几轮只扫归档**：归档行大多 `noTxt` / `miss`，代价有界（200 行/轮），扫完回绕回当前源。
- **CURRENT 句柄未捕获时会跳进 ARCHIVE**（既有降级路径）：此时从归档位置继续，回绕后重新覆盖当前源；不丢数据，最多多绕一圈。
- 未触碰 `SnsGuard.kt`、`imgDone` 判据、四态计数口径：`copied/already/miss/noMedia` 的语义与「四态之和 = 尝试数」不变，新增的只是「下一轮从哪儿扫」。
