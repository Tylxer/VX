# R27-3 · M3 朋友圈「防删除」来源位修复（srcfix）Hook 方案设计

> 版本：微信 8.0.48 (2588 GP)。包名 `com.lx.wxgroup`。
> 本文件只做**设计**，不碰 `wxgroup/` 下任何代码。台账登记 R27-3。
> 纪律：任何「谁调用谁」给 `invoke-*` 地址 + 所在方法；判不准写「证据不足」并列取证动作，不猜。
> 前置知识：`docs/_r27_m3_timeline_source.md`（v2，已定案）+ 21:20 干净对照实验（好友 verity 公开动态「5」，src=2 → 好友删后同行 src=0，行还在、content=337B、localFlag=2 未变，全程零 DELETE 日志）。

---

## 0. 结论摘要（TL;DR）

[DONE]

- **根因**：好友删动态 = 微信走 `updateFilterTimeLine` 把该行 `sourceType` 的 **bit1(2) 清成 0**（`UPDATE SnsInfo SET sourceType = sourceType & -3`），行/内容/`localFlag` 全不动。时间线查询 `sourceType & 2 != 0` ⇒ 该行从此不显示。
- **推荐方案 = 方案 (a) 预防性快照对账**（§1）：持久化「曾经可见（bit1=1）的行」台账；发现「台账里可见、库里现被清 bit1」的行就 `sourceType | 2` 补回。**方案 (b) 即时监控只作触发信号**（批量 UPDATE 拿不到逐行 snsId，§2.6）。
- **误伤硬保证**：`src=4`（自己私密）与 `src=0`（死来源）**从未 bit1=1**，结构性进不了台账 ⇒ 物理上补不到它们（§4.1）。
- **触发**：同步响应后 / 导出轮 / 冷启进朋友圈，多触发 + 幂等对账；补位可被下一轮过滤再清，靠每轮对账稳定（§3）。
- **回滚**：关开关即停（微信下一轮刷新自己清掉），或按记录原值反向 `& ~2`（§5）。
- **落地改动**：新增 `SnsTimelineSourceFix.kt` + `wxgroup.db` 加一张可见台账表 + `updateFlag` 同款 `updateSourceType` 反射写库 + 探针 `op=snsg sub=srcstat|srcfix`。不动 `SnsGuard` 现有 delete/execSQL 拦截逻辑。

> **修订注记（team-lead 两批新取证后追加，§1.5 / §2.7 / §2.8 / §7）**：
> 1. `src=0` 语义定死 =「死来源 = 已删除」⇒ 当前态判据收紧为 `sourceType == 0` 精确匹配（挡住 `src=4` 私密），§1.5。
> 2. 「拦 DELETE 只能保一时」——行会被一条未覆盖路径静默抹掉 ⇒ 补位必须**扩成「复活」抽象**：行在补位（`sourceType | 2`）、行没重放（从 `sns_archive` 整行 INSERT，复用现有 `SnsRestoreLogic`/`insertRow`，只把可见性信号从 `localFlag bit16` 换成 `sourceType`），§7。
> 3. `c3.N` 的 `limit 10` 查询是「本地有无时间线数据」的存在性检查，**不是差集/去重**，不会把补位行清掉；真正每轮撤销补位的是 `updateFilterTimeLine`，§2.8。



---

## 1. 判据设计：区分「本来 0」与「2→0」

[DONE]

### 1.1 核心判据（唯一可信信号）

**「这行曾经 bit1=1（曾经时间线可见）」是唯一能区分「2→0（要补）」与「本来 0/4（绝不补）」的信号。**

判据落地为一张**持久化可见快照台账**（存模块自己的 `wxgroup.db`，不写微信库）：

> 只要某一轮观察到 `SnsInfo` 某行 `sourceType & 2 != 0`，就把 `(snsId, 当时的 sourceType, 首次可见时间)` upsert 进台账。

之后任何时刻，若发现：
- 库中该 `snsId` 的行**还在**（EXISTS）；
- 且当前 `sourceType & 2 == 0`；
- 且台账里该 `snsId` 记录为「曾经可见」（`& 2 != 0`）；

⇒ 判定为「2→0（被过滤/被删）」，执行补位 `UPDATE SnsInfo SET sourceType = sourceType | 2 WHERE snsId=?`。

**为什么这是硬保证（不是"小心"）**：
- `src=4`（自己私密）和 `src=0`（死来源）**从未** `& 2 != 0`，**永远进不了台账**，所以**永远**不满足「台账里曾经可见」，补位逻辑**物理上够不着它们**。
- 这是台账白名单的**结构性排除**，不依赖任何"判断小心"。

### 1.2 三个候选方案对比

| 方案 | 可行性 | 改动面 | 每轮开销 | 误伤风险 | 结论 |
|---|---|---|---|---|---|
| **(a) 预防性快照对账（推荐）** | 高（复用 `SnsGuard.listCurrentSns` 分页扫表 + `GroupDb` 台账 + 同款 `updateFlag` 写库路径） | 中：新增 1 个 feature 文件 + 台账表 + 探针 | 每轮一次「可见行」分页扫描 + 一次「补位候选」比对；可限最近 N 条/页 | **最低**：src=4/0 结构性进不了台账；广告可再用 `& 128` 排除 | ✅ 主方案 |
| **(b) 即时监控（hook 写路径）** | 中（只能当触发信号，§2.6 已证批量 UPDATE 拿不到逐行 snsId） | 小：execSQL 加一个 UPDATE 匹配分支 | 0 新增常驻（复用已挂 execSQL） | 单独用**不可判**（不知道清了哪些行） | 作为 (a) 的**触发加速器**，不单独成案 |
| **(c) hook `updateFilterTimeLine` 拦截** | 低（拦了它 = 时间线永不刷新可见性，破坏微信正常「先清后补」循环，会导致已删/转私密的动态永远卡在时间线） | 中 | 低 | **高**：一刀切拦清位，无法区分「被删」与「正常被移出时间线」 | ❌ 否决 |

### 1.3 方案 (a) 的精确算法（对账 = 两步）

```
对账轮（在每次同步响应后 / 导出轮 / 冷启进朋友圈后触发，§3）：

A. 快照步（增补台账）：
   分页扫 SnsInfo where (sourceType & 2 != 0)，把每行 snsId + sourceType upsert 进台账
   （台账主键 snsId；不覆盖「首次可见时间」，只更新最新 sourceType 与 last_seen）。

B. 补位步（找出 2→0 并补回）：
   扫台账里「曾经可见」且「上次快照之后可能被清」的行（或直接扫 SnsInfo where sourceType & 2 == 0），
   对每一行：
     if 库中该 snsId 行 EXISTS 且 台账记录 sourceType & 2 != 0：
         UPDATE SnsInfo SET sourceType = sourceType | 2 WHERE snsId=?   （复用 updateFlag 同款反射，列改成 sourceType）
         记录本次补位的原值（sourceType）到台账/回滚表，供 §5 回滚。
```

**关键点：台账是跨轮持久的**。第一次见到某行 src=2 时登记；它被清成 0 后，下一轮 B 步靠「历史台账」把它认出来并补回——**不要求「过滤前那一刻刚好做过快照」**，只要「它可见期间的任意一轮做过快照」即可。这消除了快照与过滤之间的竞态。

### 1.4 为什么台账能自证（稳定性判据）

- 补位写的是 `sourceType | 2`（幂等），下次过滤再清、再对账再补 ⇒ 功能在「被删动态」上持续成立。
- 反向自证：**从未进台账的 src=0/4 行，无论对账跑多少轮，B 步都命中不到** ⇒ 用「用户自己的私密动态」做反向对照即可证明不误伤（§6）。
- 台账本身的真阳性率可用「补位后该行重新出现在时间线界面」验证（§6 主判据）。

### 1.5 判据修订（team-lead 新取证 A）：当前态用 `sourceType == 0` 精确匹配，不用 `& 2 == 0`

[DONE]

`src=0` 语义基本定死：**`SnsInfo.isDeadSource()` = `sourceType == 0` =「死来源」= 已删除**。配合实测：`src=2` 活、`src=0` 死、`src=4` 自己私密。

⇒ 补位步（§1.3 B）对「当前态」的判断，从 `sourceType & 2 == 0` **收紧为 `sourceType == 0`**：

- `sourceType & 2 == 0` 会把 `src=4`（`4 & 2 == 0`）也纳入候选 —— 那是隐私事故。
- `sourceType == 0` 只命中「死来源」，`src=4`（`4 != 0`）天然被排除。

于是「误伤防护」变成**两道独立且都是硬保证**：

| 防线 | 判据 | 挡住什么 |
|---|---|---|
| 台账白名单（§1.1） | 该 snsId 曾经 `sourceType & 2 != 0` | 从没可见过的 `src=0`（verity 旧动态） |
| 当前态精确匹配（本修订） | 当前 `sourceType == 0` | `src=4`（自己私密，4≠0）、以及任何非 0 死来源 |

注意：**两道都要**。单用 `== 0` 无法区分「本来 0」与「2→0」（verity 的旧 src=0 动态和被删的「5」现在都是 0）；单用台账白名单又挡不住「台账里误录进 src=4」这种理论脏数据。两道叠加，任何一条都能单独兜住。



---

## 2. 微信改 `sourceType` 走的路径（dex 静态取证）

[DONE]

### 2.1 结论（置信度：高 —— 清位路径已完整取证到 wcdb execSQL）

「好友删动态」时 `sourceType` 2→0 的完整链路是：**时间线/预加载网络响应 → `updateFilterTimeLine` 清 bit1 → wcdb `execSQL`**。具体：

| 层 | 方法 | 动作 | 证据（invoke 地址 + 所在方法） |
|---|---|---|---|
| 触发 1 | `c3.N`（`NetSceneSnsTimeLine` handleNormalResp） | 调 `d2.f8`/`d2.U7`/`d2.j8` | classes3.dex：`c3.N` 内 `invoke-virtual d2->j8 @0x04c4`、`d2->f8 @0x0529`、`d2->U7 @0x0543`、`d2->j8 @0x0558`、`d2->f8 @0x0584` |
| 触发 2 | `q2.onGYNetEnd`（`NetSceneSnsPreTimeLine`，tag `MicroMsg.NetSceneSnsPreTimeLine`） | 调 `d2.f8`/`d2.U7`/`d2.j8` | classes3.dex：`q2.onGYNetEnd` 内 `invoke-virtual d2->f8 @0x0426`、`d2->U7 @0x043c`、`d2->j8 @0x0451`、`d2->f8 @0x047b` |
| 清位入口 | `d2.f8`（`updateFilterTimeLineAll`）/ `d2.U7`（`updateFilterTimeLineAbove`）/ `d2.j8`（`updateFilterTimeLineBelow`） | 各自拼 WHERE 后调 `d2.S7` | classes3.dex：三个方法内 `invoke-virtual d2->S7`（`U7@0x002c`、`f8@0x000d`、`j8@0x002c`） |
| 拼 SQL | `d2.S7`（`updateFilterTimeLine`，签名 `S7(Ljava/lang/String;)V`） | `UPDATE SnsInfo SET sourceType = sourceType & -3 <where>` | classes3.dex：`const-string v3, 'UPDATE SnsInfo SET sourceType = sourceType & '`（string[37346]）@0x000a；`const/4 v3, -3` @0x000f；`invoke-virtual k2->j('SnsInfo', sql) @0x001e` |
| 执行 | `k2.j` → `z84/z.j` → `z84/z$$f.a` → `z84/e.e` | 最终调 wcdb `execSQL` | classes3.dex `k2.j` 内 `invoke-super z84/z->j @0x002e`；classes10.dex `z84/z.j` 内 `new-instance z84/z$$f` + `invoke-virtual z84/z$$f->a @0x0029`；`z84/z$$f.a` 内 `invoke-virtual z84/e->e @0x0009`；`z84/e.e` 内 `invoke-virtual com.tencent.wcdb.database.SQLiteDatabase->execSQL(Ljava/lang/String;)V @0x0015` |

### 2.2 关键发现一：`d2.S7` 清的是 **bit1（2）**

`d2.S7` 拼的 SQL 是 `UPDATE SnsInfo SET sourceType = sourceType & -3`。
`-3 = 0xFFFFFFFD = ~2`，即 `sourceType & ~2` ⇒ **只清 bit1，其它位全保留**。
（`src=6`(2|4) → `4`；`src=2` → `0`；`src=130`(128|2) → `128`。**`src=4`/`src=0` 不受影响**。）

字节码实锤：`const/4 v3, -3`（11n 格式，`(unit>>12)&0xF = 0xD = -3` 有符号 4 位），后面紧跟 `StringBuilder.append(int)`。
`_r27_srcfix.py` 已修正 const/4 解码后输出 `0x000f const/4 v3, -3`，与 string[37346] 尾部的 `'& '` 拼接，语义闭环。

### 2.3 关键发现二：清位 WHERE 已自带「只清当前可见行」的白名单

`d2.S7` 的三个调用者拼的 WHERE **都含 `(sourceType & 2 != 0) AND (snsId != 0)`**：

- `d2.f8`（All）：`S7(' where (sourceType & 2 != 0) AND (snsId != 0) ')`（classes3.dex `f8` 内 const-string @0x000b）
- `d2.U7`（Above）/ `d2.j8`（Below）：`S7(' where ' + <Q2/a3 分页键> + ' and (sourceType & 2 != 0) and (snsId != 0) ')`（@0x0020 同款 const-string）

⇒ **微信自己只在「这行当时 bit1=1（可见）」时才清它。** `src=4`（自己私密）和 `src=0`（死来源）从不进这个 UPDATE 的命中集。这是「误伤防护」的**官方背书**：清位动作天然只发生在「曾经可见」的行上。

### 2.4 关键发现三：这条 UPDATE **能被现有 `execSQL` 钩子看见**，只是没被匹配

- 全 dex 只有**一条** sourceType UPDATE SQL：`classes3.dex string[37346] = 'UPDATE SnsInfo SET sourceType = sourceType & '`（`_r27_srcfix.py strs` 实测，仅此一条）。
- 它经 `k2.j → z84/z.j → z84/z$$f.a → z84/e.e → wcdb SQLiteDatabase.execSQL`（§2.1），而 `SnsGuard.install` 已常驻 hook `execSQL`。
- 现状：`SnsGuard.onExecSql` 只拿 SQL 去 `SnsSql.matchDeleteSql`（只匹配 DELETE 形态），**UPDATE 直接被忽略** ⇒ 所以 21:20 对照实验「零日志」——不是没走本地写，是走了 UPDATE 我们没看。

### 2.5 与「谁补位」的关系：`i2.onGYNetEnd`（classifytimeline）重新打位

- 清位（`updateFilterTimeLine`）与补位（`NetSceneSnsClassifyTimeLine.onGYNetEnd` 里 `addSourceFlag(2)`，见 `_r27_m3_timeline_source.md` Q2b）是**一套「先清后补」的过滤循环**：
  1. 时间线/预加载响应到达 → `updateFilterTimeLine` 把当前可见行的 bit1 全清；
  2. classifytimeline 响应把**服务端仍归类进时间线**的行 `addSourceFlag(2)` 补回；
  3. 被好友删除的行（服务端不再归类）停在 `src=0`。
- 含义：**单次 `sourceType | 2` 补位可能被下一轮「先清后补」再清掉**。补位必须做成**每轮幂等的对账**，不是一次性写。这是 §3 触发时机的核心约束。

### 2.6 若要走「新增钩子」这条路，最省钱的落点

| 候选钩子 | 是否已挂 | 开销 | 判定 |
|---|---|---|---|
| `SQLiteDatabase.execSQL` | **已常驻**（SnsGuard） | 0（只加一个 UPDATE 字符串匹配） | ✅ 首选：加 `sql.contains("UPDATE SnsInfo SET sourceType")` 分支即可「看见」清位动作（但它是**批量** UPDATE，SQL 里没有逐行 snsId，见下） |
| `d2.S7`（updateFilterTimeLine） | 否 | 低（SNS 插件专用类，非全表写路径） | 可选：只拿到「发生了一次过滤」的信号 + WHERE 串，仍无逐行 snsId |
| `update`/`updateWithOnConflict`/`SQLiteStatement` | 否 | 中（微信**所有表**写路径，不能常驻） | ❌ 不推荐：为拿逐行 snsId 给全库写加开销，不值 |

⚠️ **为什么「即时监控」(方案 b) 单独不够**：清位是**批量 UPDATE**（`... & -3 WHERE (sourceType & 2 != 0) ...`），无论是 execSQL 还是 `d2.S7`，SQL/参数里都**没有逐个 snsId**。要知道「具体哪些行 2→0」，必须在过滤**前**有「哪些行可见」的快照，过滤后再比对。⇒ 方案 b 只能当**触发信号**，判据主体必须是方案 a（预防性快照对账）。

### 2.7 新取证（team-lead B）：拦 DELETE 只能保一时 —— 行会从另一条路径消失

[DONE]

**现象**：`-3405840021235297608`（用户 19:51 删自己私密动态那条）当时 DELETE 被拦 3 次、`localFlag` 补成 18、`SNS1 ... EXISTS`；到 21:35 变 `SNS1 ... GONE`。期间 `blocked` 无新增（微信重启归零，现 `blocked=1`）。

**结论（置信度：高）**：**行后来被一条我们没挂的路径静默抹掉了**。候选：整表 `REPLACE` / 临时表 swap / 服务端状态覆盖 / 清理 SQL（`delete ... where createTime < ?` 之类）。这些**不走 `SQLiteDatabase.delete`**，且即使走 `execSQL` 的 DELETE 形态，也可能不匹配 `SnsSql.matchDeleteSql`。

**对方案的影响**：
1. 补位（§1）的前提「行还在但 `sourceType == 0`」对「自己删除」这条路**可能无行可补**（行都没了）。
2. ⇒ 设计必须**扩一层：行消失后的重放**（从归档把整行 INSERT 回 SnsInfo）。见 §7。

**证据不足 + 取证动作**：那条「静默抹掉」的具体路径**尚未取证**（候选是上面的四种，没坐实是哪一个）。取证动作：
- 在现有 `execSQL` 钩子加一个「SQL 含 `SnsInfo` 且非 DELETE 匹配」的**临时诊断日志**，把整条 SQL 打出来（只打 SnsInfo 相关，开销可忽略），复现「自己删除 → 21:35 行消失」看是哪条 SQL 干的；
- 或挂 `insertWithOnConflict` 的**临时**钩子（拿到一次 SnsInfo 相关调用就卸载），抓 `REPLACE` 形态。

### 2.8 确认（team-lead C）：`limit 10` 那条查询是「本地有没有时间线数据」的存在性检查，不是差集/去重

[DONE]

`c3.N` 里 `select snsId from SnsInfo where (sourceType & 2 != 0) limit 10`（string[73121]）的上下文（classes3.dex `c3.N`）：

```
0x0188 markStartTimeMs('checkLocalData')
0x0196 'getTimeLineCountWithLimit'  0x019d 'getCheckTimeLineDataCountSql'
0x01aa const-string 'select snsId from SnsInfo where (sourceType & 2 != 0) limit 10'
0x01ae k2.g(sql, null, 2) → Cursor   0x01b2 moveToNext()
0x01c1 if-ge v(count), v10(=10)      0x01c3 if-lez v9
```

⇒ 它是 `getTimeLineCountWithLimit` / `getCheckTimeLineDataCountSql`：**数一下本地时间线可见行（上限 10）是否非空**，用来在 `checkLocalData` 里分支（本地有数据 vs 本地空）。**不做与响应的差集、不清行、不去重**。

⇒ 结论：**补位后的行不会被这条查询当成「本地多出来的」清掉**。真正会「下一轮自动撤销补位」的是 §2.5 的 `updateFilterTimeLine`（每轮 `& -3` 清位），不是这条 limit-10。补位必须每轮对账重补（§3.2）。



---

## 3. 触发时机

[DONE]

### 3.1 结论：多触发点 + 幂等对账，任一触发都跑同一套「快照+补位」

补位必须是**幂等对账**（§1.3），所以「多触发」无害，只有「迟/漏触发」才影响体验。按优先级：

| 触发点 | 落点（现成设施） | 时机 | 说明 |
|---|---|---|---|
| ① 同步响应后（推荐，最及时） | hook `x2.onGYNetEnd`（`MomentsSyncTrigger` 已有 `watch` 逻辑，可改造成常驻轻钩，或复用 `c3.N`/`i2.onGYNetEnd`） | 每次 mmsnssync / 时间线响应落库后 | 直接对齐 §2.5「先清后补」循环结束点，补完即生效，且下一轮刷新前不再被清 |
| ② 每次导出轮 | `MomentsExport` 导出主循环里加一次对账调用 | 定时导出（防撤回已搭定时刷新） | 兜底；成本与导出同量级 |
| ③ 冷启动首次进朋友圈 | `SnsTimeLineUI` 生命周期 / 已有 `timelineCls` 捕获点 | 进朋友圈时 | 冷启后 DB 路径（`d2.t5`）按 `& 2` 过滤，必须在渲染前补完 |
| ④ 检测到变更时立即 | execSQL 加 `UPDATE SnsInfo SET sourceType` 匹配 → 触发一次对账（§2.6） | 清位刚发生 | 最及时，但注意**同轮内 classify 还没补完**，须延迟到响应结束（投一个 post/短延迟）再对账，否则可能把「本将被补回的幸存行」误判成「被删」 |

### 3.2 「补完可能被下一轮再清掉」的稳定性设计（自证判据）

- **不追求「一次补位永久有效」**，追求「每轮对账后，被删动态都在时间线里」。
- 台账持久化（跨进程重启）⇒ 即使某轮漏触发，下一轮 B 步仍能把「曾经可见、现被清」的行补回。
- 稳定性自证（§6 稳定性对照）：连续下拉刷新 3 次，被保动态**每次都还在**；若第 2 次消失，说明对账没在每次响应后跑，需检查触发点 ① 是否常驻。

### 3.3 触发频率与开销控制

- 对账是「扫 sourceType 列」的轻操作（`listCurrentSns` 已有键集分页，单页 ≤100 行；补位只 UPDATE 命中的少数行）。
- 防抖：同一轮内多触发（①+④ 同到）合并为一次，用 `AtomicBoolean` + `post` 到后台单线程，避免主线程扫表。
- 冷启首进朋友圈的 ③ 用「限最近 N 条」（如最近 200 行，`order by createTime desc`）扫，不扫全表。



---

## 4. 误伤与边界

[DONE]

### 4.1 `src=4`（自己私密）绝不补 —— 结构性硬保证

判据层保证（不是"小心"）：补位 B 步的准入条件是「台账里该 snsId 的 `sourceType & 2 != 0`」。`src=4` 的 `4 & 2 == 0`，**永远进不了台账**（A 步只扫 `& 2 != 0`），所以 B 步**物理上命中不到它**。

再加一道**运行时双保险**（可选，建议做）：B 步对命中行再判一次 `当前 sourceType & 4 == 0` 才补 —— 若某行当前 `& 4 != 0`（私密位在），跳过补位。两道独立，任何一道都能挡下。

### 4.2 广告（`bit7=128`）要不要补

- 广告在时间线的查询是 `sourceType & 128`（string[73075]/[73076]），与 `& 2` 是**两条独立通道**。
- 纯广告 `src=128` 从不 `& 2 != 0`，**不会进台账**，天然不补。
- 唯一交叠是 `src=130`（128|2，广告+时间线位）。这种行**本来就在时间线里显示**（满足 `& 2`）。它若被清成 `128`，按当前判据会被补回。
- **决策**：默认**不排除**（130 行本来用户就看得见，补回只是维持原状）；若产品要求「广告绝不保护」，在 A 步快照与 B 步补位各加 `& 128 == 0` 过滤即可，改动 2 行。**建议默认不排除，留开关。**

### 4.3 已经不在库里的行（真删了）不要乱造

- 补位是 **UPDATE**（`WHERE snsId=?`），**不是 INSERT**。行没了 → `UPDATE` 影响 0 行 → 记日志跳过。**物理上不可能"造"出行**。
- 与 `SnsGuard` 已有的 `SnsRestoreLogic.insertRow`（从归档 INSERT 回 SnsInfo）**解耦**：本轮补位**不做** INSERT 回填，避免「从归档造行」的隐私风险。若未来要「连被物理删的也救回来」，单独走归档恢复链路（默认关）。

### 4.4 补位写库失败 / 部分成功

- 每次补位前，把「原 sourceType」写进台账/回滚表（`sns_id, orig_source_type, fixed_at`）。
- `UPDATE` 返回受影响行数：`0` = 行没了或值未变（跳过）；`>0` = 成功（记回滚日志）。
- 全程 `runCatching` + 单行日志（复用 `SnsGuard.updateFlag` 的失败告警风格）；失败只告警，**不中断整轮对账**（其余行继续）。
- 反射失败（`update` 重载分流）沿用 `updateFlag` 的 `Array<String>` vs `Array<Object>` 分流写法，避免「补位静默失效」。



---

## 5. 回滚

[DONE]

补位是**写微信库**（`UPDATE SnsInfo SET sourceType = sourceType | 2`）。回滚分三层，从轻到重：

### 5.1 关开关即停（最低成本，推荐默认回滚路径）

- 开关复用 `Keys.ANTIREVOKE_SNS`（SnsGuard 已有）。
- 关掉后：对账**停止补位**。但**已补的行不会自动还原** —— 下一轮微信自己的 `updateFilterTimeLine` 会在时间线刷新时把它们**重新清掉**（§2.5「先清后补」循环天然帮你还原），即「关开关 → 下一次刷新 → 被删动态消失」。这是**零额外代码**的回滚，且语义正确（恢复微信原状）。

### 5.2 显式反向 UPDATE（一键还原）

- 用 §4.4 记录的原值做精确回滚：`UPDATE SnsInfo SET sourceType = <orig_source_type> WHERE snsId=?`。
- 若台账里没有原值（老记录），退化为 `sourceType = sourceType & ~2`（清 bit1），等价于撤销本次补位。
- 探针：`op=snsg sub=srcfix rollback`（或 `sub=srcrollback`），遍历台账「已补位」清单逐行还原，返回受影响行数。

### 5.3 不删台账、不删行

- 台账在 `wxgroup.db`（模块自己的库），**不动微信库结构**，无 schema 迁移风险。
- 回滚**不 DELETE 微信库任何行**（补位本来就是 UPDATE，回滚也只是 UPDATE，行内容/`localFlag`/BLOB 全程未动）。

### 5.4 可逆性等级

| 操作 | 可逆 | 依据 |
|---|---|---|
| 补位 `sourceType \| 2` | ✅ 可逆 | 记录原值；反向 `& ~2` 或还原原值 |
| 关开关 | ✅ 自动可逆 | 微信下一轮刷新自己清掉（§5.1） |
| 卸载/重装 | ✅ 全还原 | 微信库只被改过 `sourceType` 一个客户端位，且会被微信自身刷新逻辑覆盖 |



---

## 6. 真机验证设计

[DONE]

判据一律对着「用户能看见的界面」或「库里的行值」。现成样本（21:20 干净对照实验后）：

| 样本 | snsId | 当前库里 src | 用途 |
|---|---|---|---|
| 好友 verity 被删的公开动态「5」 | `-3405809214430834153` | **0**（曾 2→0，行还在，content=337B） | **回放样本**：补位后应重现时间线 |
| 用户自己的私密动态 | `-3405840164296256840` 等（src=4） | 4 | **反向对照**：绝不能补 |
| verity 更早的 src=0 旧动态 | `-3405824697470283239` 等 | 0（从未可见） | **反向对照**：绝不能补（从没进过台账） |

### 6.1 分步操作 + 期望

| 步 | 操作 | 期望（界面 + 库） |
|---|---|---|
| 0 | 先跑一次对账（快照步），确认台账里有「5」这条的「曾经可见」记录（它删之前 src=2 已被看到） | `op=snsg sub=srcstat` 报台账含 `-3405809214430834153` |
| 1 | 触发补位（`op=snsg sub=srcfix` 或等同步响应后自动对账） | 库：`snsg one -3405809214430834153` 变 `src=2`；日志 `SNS srcfix snsId=... 0 -> 2 rows=1` |
| 2 | 下拉刷新朋友圈 | 界面：verity 的「5」**重新出现**（主判据） |
| 3 | 稳定性：连续下拉刷新 3 次 | 每次刷新后「5」**都在**（证明对账每轮都补、没被下一轮清掉后漏补） |
| 4 | 冷启：杀微信重进朋友圈 | 「5」**仍在**（证明 DB 位修复在冷启 `d2.t5` 路径生效） |
| 5 | 反向 1：看自己的私密动态（src=4） | **仍不显示**在主时间线（私密位没被动） |
| 6 | 反向 2：verity 早期 src=0 旧动态 | **仍不显示**（台账从未有它们，补位够不着） |
| 7 | 回滚：`sub=srcfix rollback` 或关开关后刷新 | 「5」**消失**，库变回 `src=0` |

### 6.2 反向对照的必要性

- 步 5 证「src=4 私密不被补」（误伤防护的硬保证落地）。
- 步 6 证「从没可见过的 src=0 不被补」（判据是"曾经可见"，不是"现在是 0"就补）。
- 两者共同证明：补位不是无差别 `| 2`，而是台账白名单驱动。

### 6.3 如果步 2/3 失败（补了又被清、或根本没补）怎么定位

- `op=snsg sub=srcstat` 看台账：若「5」**不在台账** ⇒ 快照步没在它被删前抓到它（触发点漏了），需检查触发点 ①/② 是否常驻。
- 若台账有、库又变回 0 ⇒ 对账没在每次同步后跑（触发点 ③/④ 缺位），按 §3.2 修。
- 若 `sub=srcfix` 报 `rows=0` ⇒ 行已真删（走 SnsGuard 拦 DELETE 之外的路），或反射写失败（看 `SNS update sourceType failed` 日志）。



---

## 7. 「复活」抽象：补位（行在）+ 重放（行没）合成一条链路

[DONE]

### 7.1 结论：两者必须合成一个「复活」抽象，三态判定

由 §2.7「拦 DELETE 只能保一时」+ §2.5「清位循环」推出，对账每轮对每个「曾可见」的 snsId 做**三态判定**：

| 库中状态 | 动作 | 落点 |
|---|---|---|
| 行在 + `sourceType & 2 != 0` | **SKIP**（已可见，不动） | — |
| 行在 + `sourceType == 0`（死来源） | **补位**：`UPDATE SnsInfo SET sourceType = sourceType \| 2 WHERE snsId=?` | §1 判据 + 新增 `updateSourceType` |
| 行**没了**（ABSENT） | **重放**：从 `sns_archive` 整行 INSERT 回 SnsInfo，且 `sourceType \| 2` | 复用现有 `SnsRestoreLogic` + `SnsGuard.insertRow` |

**好消息：重放这条路 90% 已存在**（不是从零造）：
- `SnsRestoreLogic.classify(liveFlag)` 已经做了「行没 → INSERT / 行在可见 → SKIP / 行在不可见 → SET_FLAG」三态（`SnsRestoreLogic.kt:357`）。
- `SnsGuard.restore(snsId)` / `restoreBatch(limit, dry)` / `insertRow(db, row)` 已经落库（`SnsGuard.kt:1209/1263/1529`），含 `insertWithOnConflict(CONFLICT_IGNORE=4)` + `execSQL` 退路 + 存在性预查 + 倒序回滚。
- 探针已有 `op=snsg sub=restore / sub=restorebatch / sub=cols`。

**坏消息：现有重放判据 key 在错误的位上**（`localFlag & 16`，R26 已证 8.0.48 无用）。所以「复活」抽象要做的是**把现有三态判据的可见性信号从 `localFlag bit16` 换成 `sourceType`**，而不是重写 INSERT 机制。

### 7.2 team-lead 三个子问题的答案（全部已有真机证据，不猜）

**Q1：`GroupDb.SnsRow` ↔ `SnsInfo` 列对应够不够 INSERT？缺哪些列？有没有 NOT NULL 约束？**

- 归档写入 **13 列**（`SnsRestoreLogic.KNOWN`，`SnsRestoreLogic.kt:101`）：`snsId/userName/createTime/type/sourceType/localFlag/subType/serverExtFlag/head/stringSeq/content/attrBuf/postBuf`。这是 `SnsGuard.readRow` 真机读过的同 13 列，一一对应。
- `SnsInfo` 实测 **18 列**，缺的 5 列 = `localPrivate/likeFlag/pravited/withTa/withTaHasOther`（`UNARCHIVED`，`SnsRestoreLogic.kt:126`）。**归档里没存，故意不写，由 SQLite 填默认/NULL**（写就是编数据）。
- **NOT NULL 约束：无**。2026-10-18 `snsg sub=cols` 实测 18 列 `notNull` **全为 false**，`BLOCKING none`（`SnsRestoreLogic.blockingColumns` 护栏预期恒空）。
- ⇒ **够 INSERT**。`insertRow` 只给 13 列，缺列不报错。

**Q2：INSERT OR REPLACE 还是 INSERT OR IGNORE？会不会撞主键/索引？**

- **必须用 `INSERT OR IGNORE`（= `insertWithOnConflict` 第 4 参 `CONFLICT_IGNORE=4`），绝不用 REPLACE(5)**。理由（`SnsRestoreLogic.conflictFor` + `SnsGuard.kt:1709` 注释）：
  - REPLACE 撞主键会**先把用户活行删掉再插**，等于「恢复」变「覆盖用户数据」。
  - IGNORE 撞约束只是静默不写，最坏「这条没恢复成功」。
- **但 `SnsInfo.snsId` 上没有唯一约束**（真机 DDL 实证，6 个索引 `unique` 全 0），所以 IGNORE 拦不住重复插入 ⇒ **真正的去重防线是「写之前查 `ABSENT` 才插」**（`SnsRestoreLogic.undoableInserts` / `readFlag` 三态预查），这层已存在。

**Q3：插进去的行微信认不认？缺列会不会被 `d2` 查询排除？**

- 时间线查询的过滤是 `(sourceType & 2 != 0) AND (snsId != 0) AND stringSeq > ...`（§2.3）。重放时只要 `sourceType |= 2`、`snsId` 有效、`stringSeq` 正确，就**通过**。
- 缺的 5 列**不在任何时间线 WHERE 里**，不会被它排除。
- 已有危险行护栏（`SnsRestoreLogic.rejectReason`，`SnsRestoreLogic.kt:294`）：`snsId==0` / `createTime<=0` / `content+attrBuf 全空` / `userName 空` 的行**拒绝恢复**，避免空壳/白块。

### 7.3 复活抽象的最小改动（不重写 INSERT，只换判据信号 + 补一个 sourceType 写库）

| 改动 | 内容 | 风险 |
|---|---|---|
| `SnsRestoreLogic` 新增/扩展 | 加「sourceType 视角」的三态：`classifyBySrc(库中 sourceType?)` → `SKIP / SET_SRC(=2) / INSERT`；`insertPuts` 里 `sourceType` 改成 `row.sourceType \| 2`（原来原样写，src=0 插回去还是不可见） | 中：纯逻辑类，单测可锁 |
| `SnsGuard` 新增 `updateSourceType(db, snsId, src)` | 同 `updateFlag` 的反射写法，列从 `localFlag` 换成 `sourceType`，值 `\| 2` | 低（照抄 updateFlag） |
| 台账落点 | §1 的「可见快照台账」**升级为全行快照**：A 步扫 `& 2 != 0` 时用 `readRow` 全 13 列 `archiveSns` 进 `sns_archive`（首条为准），既记「曾经可见」又存了内容供重放 | 中：复用 `archiveSns` + `listCurrentSns` 分页 |
| 探针 | `op=snsg sub=srcfix`（补位）+ 复用 `sub=restore/restorebatch`（重放）+ `sub=srcstat`（台账） | 低 |

⚠️ **关键设计点（防重放被再删）**：§2.7 说行会被「未覆盖路径」再删。所以重放和补位一样，**必须每轮对账重跑**（幂等），不是一次性。重放行的 `sourceType` 必须是 `| 2`（否则插回去 src=0 仍不可见），且重放走 `ABSENT` 预查去重（避免 snsId 无唯一约束导致重复行）。

### 7.4 重放的风险与回滚（延续 §5）

- 重放写的是 `INSERT`（造行），回滚 = 按 `snsId` 删掉「本次确认新插」的行（`undoInsert`，已有，且只删预检 `ABSENT` 的，见 `SnsRestoreLogic.undoableInserts`）。
- 因 `snsId` 无唯一约束，回滚删除有连带删活行的风险 ⇒ 已用 `undoableInserts` 白名单卡死（只删「写之前确认不在库里」的）。
- 关开关即停：重放停止；已重放的行下次刷新可能再被「未覆盖路径」删掉（恢复微信原状）。



---

[DONE] ALL
