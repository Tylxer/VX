# R52 反查性能修复 —— 装包前交叉复核报告

- 复核方：r52-perf-review（独立第二视角，只读）
- 日期：2026-11-11
- 复核对象：`MediaTableGuard.kt`（untracked 全文）+ `RevokeRescue.kt:128`（tracked 单行改动）
- 结论来源：直接读源码，非实现者自述

---

## 0. 一句话结论

**可以装包。P0 = 0 条。**

核心根因（`instr` 全表扫 84 万行 TEXT）已彻底移除：全仓 `grep -n "instr("` 返回 **0 匹配**，
三条反查 SQL 全部带 `limit` 且分别命中主键 / `serverImgInfoIndex` / 主键逆向扫描，不存在任何
无索引全表扫路径。97s 级阻塞从结构上被消除。

---

## 1. C1~C14 逐条判定

| 项 | 结论 | 证据行号 | 说明 |
|----|------|----------|------|
| C1 全表扫删净 | **PASS** | 全仓 `grep "instr("` = 0 匹配；`MediaTableGuard.kt:454/478-479/514-515` | 三条 SQL 均带 `limit`：`message where msgId=? limit 1`、`ImgInfo2 where msgSvrId=? limit 16`、`ImgInfo2 order by id desc limit 500`。无 `LIKE`/无 TEXT 列无索引 `=`。 |
| C2 主路径语义 | **PASS** | `MediaTableGuard.kt:454`；`RevokeRescue.kt:78/128`；`AntiRevoke.kt:631/708-710` | `msgId` 调用链：`updateWithOnConflict` ContentValues 的 `msgId`(=`message.msgId`) → `guardMessage` `values.getAsLong("msgId")` → `RevokeRescue.record(id,…)` → `MediaTableGuard.invalidate(msgId)`。`select msgSvrId from message where msgId=?` 的 `msgId` 即 `message.msgId`（INTEGER PRIMARY KEY），非 `msgSvrId`、非 `ImgInfo2.id`。与真机日志 `msgId=19649033` 同源。 |
| C3 兜底触发 | **PASS** | `MediaTableGuard.kt:449/486-491/499-507/513-527` | 四种情况（查不到→`svr<=0`；svr<=0；svr 查询异常→`svrReason`+svr 保持 0；行未过 token 校验→`out` 空）全部**落到** via=recent。仅 `tokens.isEmpty()`(:430) 与 `handle==null`(:435) 提前 `return emptySet()`，这两者是全局前置条件（无 token 可查 / 无句柄可查），不属 svr 路径分支，不构成漏兜底。 |
| C4 归一化一致 | **PASS** | `MediaTableGuard.kt:486/522` 与 `MediaDeleteLogic.kt:251-257/284-311` | 主路径与兜底均 `MediaDeleteLogic.normalizeId(getLong(0))`；消费侧 `shouldBlock→classifyDelete→idMatches(extractRowIds, protectedRowIds)`，`extractRowIds` 内部也走 `normalizeId`。两边同函数，前导 0 / `Long`/`Int`/`String` 混型归一一致（`normalizeId` 只去 `+` 前缀、保留原数字串，不做 `toLong()`）。 |
| C5 防串号 | **PASS** | `MediaTableGuard.kt:544-549`；`MediaGuardLogic.kt:98-99`；`VoiceFileGuard.kt:129/151` | `thumbMatchesToken` 先 `thumb.lowercase()` 再 `tokens.any { t.contains(it) }`；token 侧 `snapshotLiveTokens()` 已 `k.lowercase()`(:414)，且池 key 来自 `MediaGuardLogic.hexToken`（`:99` 统一 `lowercase()`），为**裸 32 位小写 md5**。`thumbImgPath` 形态 `THUMBNAIL_DIRPATH://th_<md5>`（`AntiRevoke.kt:213-214`、真机日志），`contains(裸md5)` 命中。无误杀（svr 路径已按 msgSvrId 收窄 + md5 精确子串）、无漏杀（md5 唯一）。 |
| C6 COVERING INDEX 权衡 | **PASS** | `MediaTableGuard.kt:478-479/514-515` | 两条 SELECT 都带 `thumbImgPath`（破坏 covering 需回表），但**校验没被省掉**：两路径都调 `thumbMatchesToken`(:488/524)。回表行数被 `limit 16` / `while out.size<16` / `limit 500` / `while scanned<500` 双重约束。 |
| C7 反射安全 | **PASS** | `MediaTableGuard.kt:445-446/516`；`AntiRevoke.kt:889-896`；`AntiRevoke.kt:954`、`RevokeRescue.kt:689` | `bindArgs(v)` 复用 `AntiRevoke.kt:889-896` 的 `m.parameterTypes[1] == Array<String>` 分支（`arrayOf(v)` vs `arrayOf<Any>(v)`）。兜底 `m.invoke(db, sql, null)`：`null` 对 `String[]`/`Object[]` 引用形参均合法（Java 反射 `Method.invoke` 接受 null 引用参数），项目内 `dumpRecent`(:954)、`countNegRows`(:689) 已用同款 `null` 传参，WCDB 对无绑定参数的 `rawQuery` 传 null 为已证路径，不会 `IAE`/`NPE`。 |
| C8 不阻塞宿主 | **PASS**（具体 ms 值 `[需真机]`） | `MediaTableGuard.kt:428-542` | 三条查询：PK 查找 + 索引 seek（≤16 回表）+ 主键逆向扫描 ≤500 行。执行计划 `order by id desc limit 500` 无 `USE TEMP B-TREE FOR ORDER BY` ⇒ SQLite 走 rowid 逆向扫描，LIMIT 截断到 500 行，**不是全表扫**。最坏 ~ms 级，结构上不可能再出现 97s。真实 `cost` 数值留待真机打点验收。 |
| C9 零回归 param.result | **PASS** | `grep -c "param.result"` = 4：`MediaTableGuard.kt:54`(注释)/`:312`(注释)/`:314`(代码 `= null`)/`:317`(代码 `= FAKE_ROWS`) | 代码赋值仍恰 2 处（delete→`FAKE_ROWS`=1，execSQL→`null`）。放行分支（:268/272/277/283/296/306）全部裸 `return`，不 `setResult`、不吞异常（异常由 :343-346 统一 catch 记日志）。 |
| C10 探针/账本保留 | **PASS** | `MediaTableGuard.kt:203-212/221-229/268-329/551-600`；`EntryReceiver.kt:703-716` | `op=mtg sub=stat|reset` 处理器未动(:708-716)。`resetLedger()` 只清 `seen/matched/blocked/skipped/events/logAt/obsLogAt`，未碰 `enabled/cachedTokens/cachedRowIds/gen/lastIssuedMs/mediaPoolRef`(:221-229)。6 条静默 return 各带 `observe()`（disabled/args-null/sql-not-delete|delete-unparsed/non-target/tokens-empty/shouldBlock-false）。`obsLogAt`(:137) 与 `logAt`(:134) 分离、`seen/matched/blocked/skipped/events` 账本、`MTG delete … reason=`(:587-589)/`MTG stat … verdict=`(:206-211)、60s 节流 `LOG_TTL_MS=60000`(:75)、`MAX_LOG_KEYS=64`(:74) 全在。 |
| C11 异常降级 | **PASS** | `MediaTableGuard.kt:452-467/476-497/513-533` | 三段查询各自 try/catch；cursor 各自 `finally { runCatching close }`(:461-463/491-493/527-529)。一个失败不影响另一个（svr 失败→svr<=0→落 recent；recent 失败→out 空→MISS）。`rows=0`/异常/句柄缺失均 `emptySet()` 保守放行 + 每分支 MISS 日志(:431/436/470/504/539/540)，不静默、不退化为全拦、不凑假 id。 |
| C12 capped/queried 语义 | **P2（可接受，建议清理）** | `MediaTableGuard.kt:440-441/558` | `queried = tokens.size`（实际就是 token 数，非「已封顶」）、`capped = false`（**硬编码恒假**，因 `snapshotLiveTokens()` 无封顶）。仅出现在最终 `MISS total` 日志(:558)。`capped=false` 会误导后续判读「存在封顶机制且未触发」，实为无封顶。非正确性问题，P2。 |
| C13 签名/调用形态 | **PASS** | `MediaTableGuard.kt:193/198`；`RevokeRescue.kt:128` | `grep "MediaTableGuard.invalidate"` 全仓仅 `RevokeRescue.kt:128` 一处；`invalidate(msgId: Long = 0L)` 默认值不破坏其它调用点（无其它调用点）。`lookupRowIds(tokens, msgId)` 位置参数与签名 `(tokens: Set<String>, msgId: Long)` 对齐。 |
| C14 残留风险 | **[需真机]** | — | 见 §3 P1 清单，全部标注需真机，不给假结论。 |

---

## 2. 三档清单

### P0（阻断装包）

无。

### P1（可装包，但需真机确认）

1. **via=svr 对新图的实际命中率** `[需真机]`：依赖 `message.msgSvrId` 恒非 0 且 `ImgInfo2.msgSvrId` 对该图有值。已知 `ImgInfo2` 全表 `nosvr=260099/842935 ≈ 30.9%` 无 msgSvrId（老图），但最近 12 行 11 行有（新图 ~92%）。命中率多少、miss 时 recent 兜底能否补上，待真机 `MTG id-lookup via=svr|via=recent` 行确认。
2. **via=recent 的 500 行是否覆盖 2 分钟撤回窗口** `[需真机]`：撤回发生时该图刚写入，按 `id desc` 必在最新若干行内，500 行理论足够；但真机需确认「撤回链中删除与反查的先后」以及极端高并发下 500 行窗口是否被更快的新图挤出。
3. **`message.msgSvrId` 是否恒非 0** `[需真机]`：若某些消息 svr=0，主路径跳过、全靠 recent。
4. **`ImgInfo2.msgSvrId` 对所有图是否都有值** `[需真机]`：老图缺值 → 主路径 miss → 依赖 recent。
5. **execSQL 跳过写法** `[需真机]`（遗留，非本轮引入）：`param.result = null` 对 void `execSQL` 是否真跳过且微信不重试。
6. **微信撤回删媒体表走的通道** `[需真机]`（遗留）：delete()/execSQL/预编译语句。

### P2（建议改进，不阻断）

1. `MediaTableGuard.kt:441` `capped = false` 恒值 + `:440` `queried = tokens.size`：`capped` 字段有误导性。修法：删掉 `capped` 字段（或改成真实封顶语义，当前 `snapshotLiveTokens()` 无封顶），`MISS total` 日志去掉 `capped=`。
2. `MediaTableGuard.kt:93` `LOOKUP_BUDGET_MS = 20L` 已定义但**零引用**（仅 :87 注释提及）：死常量。修法：删除，或注释标注「保留仅为历史对照」。
3. `MediaTableGuard.kt:453/477` 绑定 Long 型 id 用 `msgId.toString()`/`svr.toString()`（String 绑定）：依赖 SQLite 隐式数值强转。已由真机 explain plan 证实走索引、且项目内 `RevokeRescue.queryOriginal` 同款惯例，**不影响正确性**；若追求稳妥可改传 `Long`。属可选。

---

## 3. 装包后真机验收最小判据

1. **硬指标（本轮唯一性能判据）**：真机 `grep 'MTG id-lookup'` 的 `cost=` 必须从 **97166ms 降到 100ms 以内**。
   - 主路径成功：`MTG id-lookup via=svr msgId=… svr=… rows=… ids=[876171] cost=<100`
   - 兜底：`MTG id-lookup via=recent scanned=… rows=… ids=[…] cost=<100`
2. **功能主判据（不变）**：用户点开被撤回的图片**能否看到完整大图**（`HDIMG fired … SERVERID://…` + 界面可看）。
3. ⛔ `seen/matched/blocked/rows` 只作**定位辅助**，不作为成功判据（成功 = cost 达标 + 大图可看）。

---

## 4. 三档边界复核结论（进入 / 返回 / 复位）

- **进入**：`invalidate(msgId)` 由 `RevokeRescue.record()` 图片/视频分支（`RevokeRescue.kt:121-129`）调用，处于 `protectMedia(orig.imgPath)` 之后、`[GT]ColdPool` 同步链路。进入时序正确。
- **返回**：`lookupRowIds` 三种出口（tokens 空 / handle null / 正常反查）均返回 `Set<String>`（空集=保守放行），无异常外泄。
- **复位**：`resetLedger()` 只清账本，`gen` 自增在 `invalidate()` 内，**不存在「滚回顶部 / `pos <= prev` 即复位」这类联想**。实现中未发现将滚动/位置判断误当复位触发条件的代码。✅ 无该历史教训复发。

---

## 5. 回滚路径

- **现状**：`git status` 显示 `MediaTableGuard.kt` 为 `??`（untracked），`RevokeRescue.kt` 为 `M`（tracked，diff 仅 `+1` 行）。
- **`git revert` 不会删 MediaTableGuard.kt**：untracked 文件不在任何提交中，`git revert`/`git checkout` 都碰不到它；需要**手动**把文件内容退回上一版（上一版内容不在 git，需从 `_cache`/实现报告/上一轮快照恢复）。
- **「移除本轮新增块」是可行的**，需移除/回改以下段：
  1. `MediaTableGuard.kt:91-92` 新增常量 `MAX_ROWS_PER_TOKEN` / `MAX_RECENT_SCAN`。
  2. `MediaTableGuard.kt:193` `invalidate(msgId: Long = 0L)` → 回改 `invalidate()`；`:198` `lookupRowIds(tokens, msgId)` → 回改 `lookupRowIds(tokens)`。
  3. `MediaTableGuard.kt:428-542` 整个两段式 `lookupRowIds` 主体 + `bindArgs`(:445-446) → 恢复旧 R52 甲单段 `instr` 实现（旧体不在 git，需外部快照）。
  4. `MediaTableGuard.kt:544-549` `thumbMatchesToken`（本轮新增，旧版靠 SQL `instr` 匹配，无此函数）→ 删除。
  5. `RevokeRescue.kt:128` `MediaTableGuard.invalidate(msgId)` → 同步回改 `MediaTableGuard.invalidate()`。
- **注意**：回滚方向（退回 `instr` 全表扫）会**重新引入 97s 阻塞**，仅作为「本方案真机不成立」时的兜底，不建议主动执行；更优做法是保留两段式、只按真机数据调 `MAX_RECENT_SCAN` / 主路径细节。

---

[DONE] ALL
