# R38 · 朋友圈防删除：判据重设计与分期开发计划

> 状态：**设计文档**（只读离线设计 lane `r38-plan-lane`，Pro 档）。唯一产物 = 本文件。
> 时间：2026-11-02 ｜ 设备 `467cf5bc` ｜ 微信 8.0.48/2588 GP ｜ 模块 `com.lx.wxgroup`
> 硬约束：不执行 adb / 不跑 gradle / 不改 `app/src/main` 任何文件 / 不 commit / 不装包。
> 引用口径：主会话事实归集 `docs/_r38_disproved.md`（真机日志为二手引用，视为可信原始材料）；上一版设计 `docs/_r37_graytag_datasource.md`（窗口闸门 C1~C6 / G1~G5 仍有效）；定案台账 `docs/派发审计台账.md:2304/:2447/:2505-2516`（R37-20 八项判据）。
> 铁律：结论落「文件:行」；凡属推断显式标「**假设**」并附 1 条可证伪的探针命令。
> ⛔ R31h 红线：`sns_visible_src`（`T_SRC`）补位台账**不得**作「被删」判据（曾导致正常动态全被插灰标）。
> ⛔ 互斥结果（`blocked`/`restored`、`syncDel`/`restored`）写成**优先级分支**，不写成 AND 列表。

> ⚠️ **2026-11-03 订正（R39 真机实证，见 `docs/_r39_sourcetype_verdict.md`）**：
> 1. §1-2 措辞需补：「`2→0`」与「`10→8`」**两条路径都存在**，取决于初始值是否带相册位。
> 2. §1-3 的排除**仍然成立且不得推翻**——它排除的是「行字段做**删除事件**判据」。
>    R39 新增的是「行字段做**当前可见状态**判据」：`sourceType & 2 == 0` ⇒ 确定不可见（5/5 同屏对照 + verity 真实删除双验证）。
>    两者是不同命题，**不可混用**：`&2==0` 不能用来判断「刚刚被删」（全库 36,653 条存量，占 12%）。
> 3. **P3（可见侧注入）结论不变，不可砍**。R39 实测「`src=2` 的行刷新后仍可见、`src=0/8` 的不显示」，
>    反向印证 §1-4「改库 ≠ 改界面」：界面由响应决定，注入是唯一改界面的路。
> 4. 新增约束：清位有 3~7 分钟延迟（删除后行短期保持 `2`），且微信会自行修回人工写入的非法值，
>    故一切基于 `setsrc` 的实验结论需慎用。

> ⚠️ **2026-11-03 次轮订正（复核 P0/P1 落地，见 `docs/_r38_review.md`）**：
> - **P0-1**：第 0 期 gate ④ 改为「`respIds ⊆ upsertIds` **集合包含**」，删除「与 `fixed=N` 同量级」——量纲差 ~100 倍，字面永不达标，会把四期链整体锁死。
> - **P0-2**：新增 **§3.2.1 有效范围声明**（存档/判删/注入都只覆盖首屏响应窗口，翻页历史不进）；第 3 期目标句改为「解决『删了**在首屏**看不见』，仅限顶部近期窗口」。
> - **P1-1**：§4 行 7 由 `D ∧ A` 改为 **`A ∧ L ∧ ¬R`**（原写法让方向 B 被方向 A 的 C1 反向否决，堵死假设 B 证伪后的退路）；行 9 随之改为「A 差 G4」。
> - **P1-2**：§5.5 增补「**假设被证伪 ≠ gate 失败**」通道（每条假设在 §6.1 都有登记备选，就地切换继续，不停当期）。
> - **P1-3**：P3 保留 —— 原顾虑「翻页也许可见」已由 R39 证伪（`&2==0` 翻页也读不出），「翻页可见」不构成替代方案。
> - **P1-4**：已由 R39 真机闭环（5/5 同屏对照 + verity 真实删除双路径）⇒ 立项前提成立。
> - **P1-5**：第 3 期读端**两处同改**（`SnsTag.kt:679` + `SnsDeletedTag.kt:1690`），gate 增第 ⑦ 条「双 sweep 口径一致、无闪烁」。

> ⚠️ **2026-11-05 订正（R41 判据重构，见 `docs/_r41_arch.md`）**：C1 已真机证伪（`0/2`，结构性死结，`docs/_r40_p0_gate.md:262-269`）。
> 本文件 §3~§5 的判据与分期已被 `docs/_r41_arch.md` **替代**：主判据由「方向 B 相册差集」改为 **H 状态迁移**（`曾 ∈ respIds ∧ 双轮缺席 ∧ 当前行 &2==0`），
> C1~C6 窗口闸门由 `&2==0` 状态闸门吸收；快照侧补档由「可选」变「必需」（P0-2 确诊 25% 覆盖）；**补位 `reFixAfterClear` 第 1 期起默认关闭**（不改界面且干扰 `&2` 状态确认）。
> §1 已证伪清单、§2 可用资料、§6 假设登记**仍有效**；§4 判据表与 §5 分期以 `_r41_arch.md` 为准，勿再按本文件执行。

---

## §1 已证伪清单（直接引用，不重新取证）

> 第 1~13 条直接引用 `docs/_r38_disproved.md:18-34` 与 `docs/派发审计台账.md:2509-2516`，**不重新取证**。
> 第 14~17 条为本次只读 Grep 从代码注释里新发现的否证项，单独列出并给出代码行依据。
> 每条格式：`曾经的做法/假设 → 否证证据（文件:行 或 真机日志）→ 结论：永久排除`。

| # | 曾经的做法 / 假设 | 否证证据（文件:行 或 真机日志） | 结论 |
|---|---|---|---|
| 1 | 「好友删动态会发 DELETE，挂 DELETE 就能防」 | 三轮受控实验：`blocked` 恒 `1→1`、`SNS blocked delete` 0 行、关开关对照 `SNS delete seen (off)` 也 0 行（`docs/_r38_disproved.md:22`；打点代码 `SnsGuard.kt:432`/`:461`/`:469`）⇒ **一条 DELETE 都没有** | **永久排除**：DELETE 拦截 / 归档链路对「好友删动态」结构性收不到事件 |
| 2 | 「好友删动态只把 `sourceType` 从 10 改成 8」 | **两条路径都存在**：初始 `2` → `0`（`docs/派发审计台账.md:2510`、R39 verity 真实删除），初始 `10` → `8`（R39 同屏对照第 4 条）。R37 早期只观测到前者 | **已订正**（R39）：不是方向错，而是取决于初始值是否带相册位（bit3） |
| 3 | 「用行字段（`sourceType`/`serverExtFlag`/`localFlag`/`subType`）判删除」 | 同作者 10 条完全同形；全表 `8`=29,685 / `0`=16,330，信噪比约 1:5000（`docs/_r38_disproved.md:24`；`docs/派发审计台账.md:2511`） | **永久排除**：这一**类**里没有信号，不是「还没找到」 |
| 4 | 「补回 `sourceType` bit1 就能让被删动态继续可见」 | 时间线列表按**同步响应**构建，补位只改库；22:34 实证 `src=10` 但界面 `has_verity=False`（`docs/_r38_disproved.md:25`；`docs/派发审计台账.md:2512`） | **永久排除**：「改库 = 改界面」；补位不能作为可见性方案 |
| 5 | 「H3b：`x2.M/N/O/P/Q/U` 是删除动作」 | 6 方法全挂、三轮零命中（含 `onGYNetEnd errType=0` 的成功同步）⇒ F1 成立（`docs/_r38_disproved.md:26`；`docs/派发审计台账.md:2513`） | **永久排除**：H3b / `syncDel` 接口事件作判据（连带 R37 判据表第 1/2 行的 `S`/`S2` 信号作废） |
| 6 | 「时间线响应里带删除列表 / 墓碑」 | `docs/conclusions/moments.md:227` 静态取证：无 `deletedIds` / tombstone 形态（`docs/_r38_disproved.md:27`；`docs/派发审计台账.md:2516`） | **永久排除**：从响应结构里读「删除通知」 |
| 7 | 「列表级差集（不在响应里 = 被删）」 | 已有事故 `SNS-INJECT attempt=24 ok=24` 全是正常动态；根因是分页（200 条候选里 190 条不在当前页）（`docs/_r38_disproved.md:28`；代码铁证 `SnsTimelineMerge.kt:159-163`） | **永久排除**：以「本地全表 / 补位台账」为基准的无窗口差集 |
| 8 | 「`snstag dump` 判动态是否存在」 | 只采样 `bound` WeakHashMap 里的旧 View，与当前屏幕无关（多次误判）（`docs/_r38_disproved.md:29`） | **永久排除**：存在性一律按 id 查库，不用 dump |
| 9 | 「用『自己删自己的动态』验灰标 / 防删」 | 微信先把行删掉（拦不到）再补一条级联 `SNS blocked delete`（`snap=false`），没有可打标对象（`docs/_r38_disproved.md:30`；`SnsGuard.kt:432` 的 `snap=` 字段） | **永久排除**：自删场景不可作端到端验收样本 |
| 10 | 「发完立刻删」验端到端 | 我方还没拉到行、服务端已标记删除，等于「没存在过」（`docs/_r38_disproved.md:31`） | **永久排除**：验收必须改成「她发 → 我方刷出并确认看见 → 她再删」 |
| 11 | 「R31h：把补位台账当删除判据」 | 事故：正常动态全被插灰标（`docs/_r38_disproved.md:32`；`SnsTimelineMerge.kt:159-163`；`docs/_r37_graytag_datasource.md:506`/`:575`） | **永久排除**（红线）：`sns_visible_src` / `fixed_orig` / `T_SRC` 永不作纳入判据 |
| 12 | 「`msync sync` 强制同步能带回删除状态」 | 三轮强制同步后 `src` 仍为 2（`docs/_r38_disproved.md:33`） | **永久排除**：靠 `msync sync` 制造同步轮次；只能等微信自己那轮 |
| 13 | 「手势下拉必然触发同步」 | `MSYNC watch` 60s 窗口 `observed=0`；只有微信后台定时同步 / 退出重进首屏才真正同步（`docs/_r38_disproved.md:34`；探针 `EntryReceiver.kt:695-701`） | **永久排除**：把手势下拉当同步已发生的证据；判读前必须确认 `onGYNetEnd` 真发生 |

**本次从代码里新发现的否证项（14~17）：**

| # | 曾经的做法 / 假设 | 否证证据（文件:行） | 结论 |
|---|---|---|---|
| 14 | 「`sourceType` 的**任何**变化可归因于删除」 | 两个写入主体并存：微信清位 + 模块全表补位；`srcstat fixed=500 pages=30`、`re-fix fixed=2118`（`docs/_r38_disproved.md:46` F5；补位实现 `SnsTimelineSourceFix.kt:558-559`，清位实现 `SnsTimelineSrcLogic.kt:75`） | **永久排除**：快照/存档的触发**不得**依赖 `sourceType` 数值或变化量（直接决定 §3 存档侧只能用「响应集合」而非行字段） |
| 15 | 「往相册响应 `jc5.f` / `h3.N` 注入可让被删动态在相册页显示」 | 相册 UI 是**读库渲染**（`qg3/i` 的 `d:List` 元素是 `SnsInfo` 存储实体），注入响应对 UI 无增量；代码已注释禁用 `SnsAlbumMerge.install`（`HookEntry.kt:273-285`） | **永久排除**：相册「响应注入」这条路。⚠️ 不排除方向 B 的**相册接口集合差集**（只读响应集合，是另一回事） |
| 16 | 「挂 `execSQL` 即可拦到微信清位 / 观察到同步」 | R27 真机取证：清位真实通道是 `update("SnsInfo", ContentValues{sourceType=0})` → `updateWithOnConflict` 整行 upsert，每轮首屏同步都跑，`execSQL` 一次都不出现 ⇒ 原 `onExecSqlAfter` 一直空挂（`SnsGuard.kt:227-233`） | **永久排除**：把 `execSQL` 当同步/清位观察点。⇒ §3 快照采集点必须落在 `updateWithOnConflict`（`:241-246`）或响应列表 `respIds`（`SnsTimelineMerge.kt:143`） |
| 17 | 「空响应 / 心跳包也能拿来做差集基准」 | `c3.N` 每秒一次被调且传入 `zb5.f` 常为空列表；往空列表注入会让 `attempt` 从 4 涨到 56（`SnsTimelineMerge.kt:145-150`）⇒ 空响应下的「缺失」无信息量 | **永久排除**：空响应整轮跳过（写成 §4 窗口闸门 `C6`/`G1`，`docs/_r37_graytag_datasource.md:193`/`:357`） |

## §2 可用资料（能用来做什么，不只是罗列）

> 每条按「**事实（文件:行） → 在 R38 重设计里能用来做什么**」写。凡属推断标「**假设**」。

### 2.1 删除语义定案 —— 决定「判据只能建在响应集合上」

- **事实**：好友删动态 = `UPDATE SnsInfo SET sourceType = sourceType & -3`（`-3 = ~2`，只清 bit1，`2→0`），**行与内容全留、零 DELETE**。`docs/派发审计台账.md:2510`（R37-20 八项判据）、`docs/_r38_disproved.md:42`（F1/F2）。
- **代码三处认领**：
  - `SnsTimelineSrcLogic.kt:7-8`/`:75` 写明 SQL 形态 `... WHERE (sourceType & 2 != 0) AND (snsId != 0)`；`:81-90` `CLEAR_SQL_RE` + `isSourceClearSql` 只认 `& -3`（宽松吃空格、严格拒绝模块自己的 `| 2` 补位 SQL，防无限递归，`:77-79`）。
  - `SnsGuard.kt` 的 **update 通道**：`:183` 必须挂 `after`（`before` 补位会被同一条 SQL 立刻清掉），`:204` `isSourceClearSql(sql)` 认领，`:216-220` 调 `SnsTimelineSourceFix.reFixAfterClear(db)` 并打 `SNS srcguard re-fix after clear fixed=…`。
  - `SnsTimelineSourceFix.kt:22-24`（`-3 = ~2` 行/content/localFlag 全不动）、`:530-531` `reFixAfterClear` 补位分支（受 `enabled()` 总闸）、`:558-559` 补位 SQL `set sourceType = sourceType | FIX_BITS where … (sourceType & 2) = 0 and (sourceType & 4) = 0`。
  - 时间线可见门槛：`MomentsLogic.kt:80` `bit1(2)` 进时间线，查询一律带 `sourceType & 2 != 0`。
- **能用来做什么**：既然「被删」的唯一表现是**该 id 退出时间线响应集合**、而行字段既同形（§1-3）又不可归因（§1-14），R38 的**存档与判删都必须建在「响应集合 id + 轮次」上**，不得再扫行字段、不得再读 `sourceType`。这是 §3 架构的第一块地基。

### 2.2 同步 / 清位时机 —— 决定「一轮」是什么、快照何时取

- **事实**：`updateFilterTimeLine` 每轮同步**先全表清 bit1 → 再由 classifytimeline 只回填本批返回行**；被删那条不在响应里 ⇒ 停在 0。
- **代码依据**：`SnsTimelineSourceFix.kt:510-514`（原文：「`updateFilterTimeLine` 每轮都先把所有可见行清位、再由 classifytimeline 把『服务器仍然返回的』补回来；被删的那条不在响应里，于是停在 0」）、`SnsGuard.kt:196`（清位每轮 sync 都跑一次）、`SnsGuard.kt:227-231`（**真实落库通道**：`update("SnsInfo", ContentValues{… sourceType=0 …})` → `updateWithOnConflict` 整行 upsert，每轮首屏同步都跑，`execSQL` 一次都不出现）、`:233`（挂 `updateWithOnConflict` 而非 `update`）、`:238-239`/`:241-246`（静默 `UPDATE_FIX_QUIET_MS=350ms` 合并同批写、`snsUpdateGuard` after 钩）、`docs/_r27_clear_api_forensics.md`。
- **能用来做什么**：**「一轮同步」的可观测单位 = 一次非空 `zb5.f` 响应**（或一次 `updateWithOnConflict` 批次）。这直接定义 §3 判删的「两轮窗口」和 §5 第 0 期的采样节拍。⚠️ 配套事实 F4（`docs/_r38_disproved.md:45`）：模块补位是「贴脸反应」，错过即永久丢 ⇒ **快照必须在响应到达那一刻取，不能事后扫库补**；这也是 §1-16 排除 `execSQL` 观察点后的必然落点（`SnsGuard.kt:241` / `SnsTimelineMerge.kt:143` 的 `respIds`）。

### 2.3 归档链路 —— 证明「可见侧必须换注入源」

- **链路**：`SnsGuard.kt:424 readRow`（快照必须同步取）→ `:427 blocked.incrementAndGet()` → `:432` `SNS blocked delete snsId=… snap=… flag=… total=…` → `:433 saveAsync(row)`；`execSQL` 同形 `:464 readRow` / `:467 blocked++` / `:469` `SNS blocked delete-sql` / `:470 saveAsync(row)`；`saveAsync` 定义在 `:642` → `GroupDb.archiveSns` 在 `data/GroupDb.kt:417`（`:420-435` 存 `content`/`attr_buf`/`post_buf` 等完整快照，`:436` `CONFLICT_IGNORE` 首条为准）。
- **两个早退点**（决定「零成功归档」）：
  1. `SnsGuard.kt:643-646` `if (row == null)` → `SNS archive skipped: no snapshot`；
  2. `SnsGuard.kt:649-651` `val ok = GroupDb.current()?.archiveSns(row) ?: false`，`ok=false` 时不 `archived++`，只打 `SNS archived snsId=… ok=false`。
- **现状**：归档**唯一入口**在 DELETE 通道，而本场景零 DELETE（§1-1）⇒ **当前版本零成功归档**；`sub=ls` 里 archive 8 条是 5~8 月老数据（`docs/_r38_disproved.md:47` F6、`docs/派发审计台账.md:2514`）。
- **能用来做什么**：证明「继续从 archive 注入」拿到的只能是几个月前的老数据、且新增永远为 0。R38 可见侧**必须**把注入源从 `listArchiveIds` 换成新快照表（§3 可见侧）。`saveAsync` 的同步取快照写法（`:421-424`「漏档是永久的」）可直接复用为快照表的写入范式。

### 2.4 注入链路 —— 唯一能改界面的通道，也是「旧动态乱插」的发生地

- **链路**：`SnsTimelineMerge.kt:13`（方案 A Response-Merge：把「服务端已不返回、本地还在」的动态注入时间线响应列表）→ `:145-152`（⛔ 空响应不注入）→ `:172` `val all = gdb.listArchiveIds(500)` → `:173-181` 轮转游标（`archiveCursor`，防第 13 条起饥饿）→ `:182` `SnsTimelineMergeLogic.pickMissing(rotated, respIds, MAX_PER_ROUND)` → `:187-198` attrBuf 两来源（先微信 `SnsInfo` 行、行没了才吃 archive 快照）→ `:281` `enabled()`。
- **口径订正**：`MAX_PER_ROUND = 12`（`SnsTimelineMerge.kt:54`）。⚠️ `docs/派发审计台账.md:2515` 与派发简述写「每轮注入 8 条」是旧数据；**代码实际 12**。结论不变（注入候选来自 `listArchiveIds`），但第 0 期判读 `SNS-INJECT` 日志时以 `:54` 为准。
- **R31h 铁证就在本文件**：`:159-163` 明写「绝不能用 `listSrcFixedIds`（补位台账 `fixed_orig`）……200 条台账里必然有 190 条不在当前页」，`attempt=24 ok=24` 全是正常动态。
- **能用来做什么**：这是全项目**唯一**作用于「同步响应」的通道（§1-4 已证改库不动界面）。R38 可见侧复用 `pickMissing`/`respIds`/空响应闸，但把候选从 `listArchiveIds` 换成「快照表 ∩ 当前时间线窗口」（§3），并沿用 `:159-163` 的纪律：候选必须是「上一轮窗口内确实出现过」的 id，不是全表。

### 2.5 探针 —— 第 0 期纯取证的全部手段

| 探针 | 入口（文件:行） | 在 R38 里能用来做什么 |
|---|---|---|
| `op=snsg sub=stat\|ls\|one\|show\|srcmap\|sqlspy\|setsrc\|del` | `EntryReceiver.kt:345`（`snsg` 分支）、`:362` `setsrc`、`:373` `srcmap`、`:379-380` `sqlspy`、`:591` `SNSG unknown sub=`；清单 `docs/_r38_disproved.md:69` | `stat` 看 `blocked/archived` 是否仍恒 0（验 §1-1）；`ls`/`one`/`show` 看快照表与 archive 内容；`srcmap` 看 sourceType 分布（只作排除，不作判据）；`sqlspy` 抓真实清位 SQL 验 §1-16；`setsrc`/`del` 造受控样本 |
| `op=snstag sub=stat\|dump\|fake` | `EntryReceiver.kt:599`/`:612`；清单 `docs/_r38_disproved.md:70` | `stat` 看打标计数、`fake` 造灰标 UI 样本。⛔ `dump` **不能判存在性**（§1-8），只作 UI 打点取证 |
| `op=msync sub=stat\|sync\|watch` | `EntryReceiver.kt:695-701`（`:695` `ready=true` 且 `disabled=-`；`:696` `MSYNC OK scene=256 type=214` 才算投递成功）；清单 `docs/_r38_disproved.md:71` | `watch` 固定 60s 窗口，用于**确认 `onGYNetEnd` 真发生**（§1-13 的纪律）；`sync` 已知带不回删除状态（§1-12），仅作对照 |
| `op=sql --es q '…'` | 清单 `docs/_r38_disproved.md:72`（**必须整串包一层引号**，否则被 `adb shell` 二次解析截断） | 按 id 点查 `SnsInfo` 行在不在（存在性唯一口径）；查快照表窗口字段；**本 lane 不执行，仅写入第 0 期命令** |
| `SNSSYNCDEL`（`x2` 六方法旁听） | `feature/SnsSyncDelProbe.kt`；清单 `docs/_r38_disproved.md:73`（`KV debug.sns_syncdel_probe`） | 已因 §1-5 作废判据用途；第 0 期只保留「确认三轮仍零命中」的旁听，防误以为钩子没装 |
| `tools/dev.py` | `tools/dev.py`、`wxgroup/tools/dev.py`（Glob 确认存在） | 第 1 期起构建/装包/抓日志用；本 lane 只读，**不执行** |
| `wxgroup/_vis.py` + `uiautomator dump` | `wxgroup/_vis.py`（Glob 确认）；清单 `docs/_r38_disproved.md:74`（先 `cp` 成 `_vis.xml` 再在 `wxgroup/` 内跑） | 屏幕取证：验「界面到底有没有那条动态」（§1-4 的 `has_verity`），是可见侧验收的唯一硬证据 |
| `op=snsa sub=stat`（注入统计） | `SnsTimelineMerge.kt:284` | 看 `attempt/ok/emptyBuf`，验注入是否仍在污染（§1-7 事故口径） |

### 2.6 开关语义 —— 「模块关闭」对照只需一条命令

- **`antirevoke.sns` 是四处 `sourceType` 写入的总闸**：`Keys.kt:141` 定义、`:380` `PrefSpec(…, "false", "防撤回", "防止朋友圈动态撤回")`；读取点 `SnsTimelineSourceFix.kt:531`（`reFixAfterClear`）、`:367`（`patchAlbumBit`，据 `docs/_r38_disproved.md:64`）、`:478`（`albumfix`）、`:1106`（`enabled()`），另有 `SnsTimelineMerge.kt:281`、`SnsTag.kt:226`、`SnsDeletedTag.kt:509`、`SnsGuard.kt:155`、`SnsAlbumMerge.kt:249` 同口径。
- **关开关行为**：`SnsTimelineSourceFix.kt:110` `SNS srcfix switch off (antirevoke.sns=false)：只报状态，不执行`；`:626` 特别注明「关开关**不拦回滚**：停止保护 != 禁止收拾现场」。
- **能用来做什么**：`kvset antirevoke.sns false` ⇒ 四处 `sourceType` 写入全静默（`docs/_r38_disproved.md:76`）⇒ 第 0 期做**正反对照**时，只需这一条命令即可让「模块补位」这个第二写入主体消失，从而把「响应集合」的观察变成单主体、可归因。⚠️ 对照纪律（`docs/_r38_disproved.md:102`）：关开关后**必须先验证模块确实静默**，再下结论。

---

## §3 架构分层（本次重设计的核心）

### 3.0 先回答：「存档」与「判删」能不能解耦？

**能，但只解耦了一半，而且不是字面意义的那一半——结论必须说准，否则会重演 R31h。**

- **能解耦的是「写入触发」**：F1 已定案「好友删动态零 DELETE、响应无墓碑、H3b 零命中」（`docs/_r38_disproved.md:42`/`:48`/`:50`）。因此**任何「等删除事件再存档」的方案都永远收不到事件**——现有归档链路的两个早退点（`SnsGuard.kt:643`/`:649`）不是 bug，是这条路结构性走不通的铁证（§2.3）。把存档改成「每轮同步顺手存所见」，就把「有没有被删」这件事从存档链路里**彻底拿掉**。
- **不能解耦的是「窗口证据」**：判删本质仍是**缺席推断**（某 id 从响应集合里退出），而「退出」只能相对「上一轮它确实进来过」成立。所以判删侧必须读存档侧产出的**轮次窗口账本**。两层通过「快照表 + 窗口账本」耦合，而**不再通过「删除事件」耦合**。

⇒ 一句话：**从「事件耦合」改为「时序 / 窗口耦合」**。这也是为什么 §1-7 的列表级差集被否证、而本架构仍可用差集：差集的**基准**从「本地全表 / 补位台账」换成「上一轮同窗口响应集合」，基准一换，190/200 的分页噪声就从「被删」变成「窗口外、放弃」。

### 3.1 对派发建议架构的一处**必要修正**（自行判断，不照抄）

> 派发建议「存档侧：每轮同步看到 `src & 2 != 0` 的行就 upsert 一份快照」。**这一条不能照抄，必须改。**

- `src & 2 != 0` 是**行字段**。F5（`docs/_r38_disproved.md:46`）已定案「`sourceType` 的**任何**变化都不可归因」：微信清位 + 模块全表补位两个写入主体并存（`SnsTimelineSrcLogic.kt:75` vs `SnsTimelineSourceFix.kt:558-559`）；开着开关时被删的行会被补回 `10`（`docs/_r38_disproved.md:45` F4）。⇒ 用 `src & 2 != 0` 扫库，既会把「被删但被补位」的行当成存活证据，又把「模块补位」这个第二主体当成了判据输入——**这正是 R31h 污染路径的变体**（`SnsTimelineMerge.kt:159-163`）。
- 更根本的：「本轮它在响应里本身就是窗口证据」这句**只在「存档成员资格 = 同步响应集合」时成立**。库里 `src & 2 != 0` 的行可能三个月前就在，与「本轮响应」毫无关系，不构成窗口证据。

**修正后的存档侧**：成员资格只由 **`respIds`（本轮响应 id 集合）** 决定；快照内容仍按 id 从库点查（`SnsGuard.kt:424 readRow` 范式，存 `content`/`createTime`/`userName`/`attr_buf`/图片路径），但 `sourceType` **不参与任何判定**（只保留 `& 4` 私密位作排除闸门，`docs/_r37_graytag_datasource.md:516-518` 允许的保守用法）。
**依据**：`respIds` 已在 `SnsTimelineMerge.kt:143` 收集，零新增钩子即可复用（`docs/_r37_graytag_datasource.md:398-401` C-2）；响应集合天然带本轮 `[ctBot, ctTop]` 窗口（`:143` 同时收集 `CreateTime`）。

> **假设 A**：`respIds` 与 `updateFilterTimeLine` 实际回填的行集合一致（即响应集合能代表「服务端本批返回行」）。
> **可证伪探针（REV-2，P0-1 订正）**：`op=snsg --es sub win`（第 0 期新探针）看 `WIN gate round=R respN=M upsertN=K subset=<bool> miss=[…]`。
> `subset` = 本轮 `respIds` **是否集合包含于**同期 `updateWithOnConflict("SnsInfo",…)` 批次 id（窗口 3000ms，`SnsGuard.kt:241` / `:250-290`）。
> ⛔ **不再用**「`fixed=N` 与 `respIds=M` 同量级」——两者量纲差 ~100 倍（`N`≈全台账 2118 行 vs `M`≈单批 10~20），字面永不达标（`_r38_review.md:190-198`）。
> 判读纪律：单轮 `subset=false` **不构成证伪**（微信只 upsert 变化的行）；须在目标 id **首次出现的那一轮**比对。
> 连续多轮 `subset=false` 且 `miss` 里的 id 在库中**确实存在** ⇒ A 被推翻，存档成员资格改挂 `updateWithOnConflict` 批次。

### 3.2 三层分层

```
┌─ 存档侧 Snapshot Writer（每轮同步，无删除事件依赖）─────────────────┐
│ 触发：非空 respIds 且判定为「独立同步轮次」（见 3.4 风险①）           │
│ 动作：对每个 id upsert 快照表（sns_id 主键，首条为准，不追加）        │
│ 落账：同时记本轮 W_k = [ctBot_k, ctTop_k] 到窗口账本                  │
│ 不读 sourceType；不读补位台账；不等 DELETE                            │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ 快照表 + 窗口账本（唯一的层间耦合）
┌─ 判删侧 Window Arbiter（下一轮同步，优先级分支）────────────────────┐
│ 见 3.3 的分支伪码：restored 优先 → 窗口闸门 → 双轮缺席 → 观察态      │
│ 输出：疑似删除（默认「不打标」，见 §4）；窗口未过一律「放弃(静默)」   │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ 已确认「疑似删除」的 id 集合（可选）
┌─ 可见侧 Injector（可选，复用 Response-Merge）────────────────────────┐
│ 候选 = 快照表 ∩ 疑似删除 ∩ 当前窗口 [ctBot,ctTop] ∩ pickMissing      │
│ 注入回 zb5.f 响应列表（SnsTimelineMerge.kt:13），限流 MAX_PER_ROUND   │
│ 不写库、不改 sourceType、空响应不注入（:145-152）                     │
└──────────────────────────────────────────────────────────────────────┘
```

### 3.2.1 本功能的**有效范围**（P0-2 强制声明）

> 复核 `_r38_review.md:224-234` 提出、且 R39 真机已证实：朋友圈是**混合渲染** —— 首屏与下拉刷新走网络响应（`c3.N` → `q54.zb5.f`），**翻页走 `d2.t5` 数据库查表**（`conclusions/moments.md:219-224`）。
> R39 补充实证：`&2==0` 的行翻页**读不出来**（5/5 同屏对照），所以「翻页能看」不等于「被删也能看」。

⇒ 三个动作各自的硬边界：

| 动作 | 数据来源 | 覆盖到 | **覆盖不到** |
|---|---|---|---|
| 存档（第 1 期） | 本轮 `respIds` | 只在首屏/刷新那批响应里出现过的动态 | 用户滚过的历史动态（翻页查表，从不进响应） |
| 判删（第 2 期） | 「上一轮 `respIds`」差集 | 同上 + 两轮窗口覆盖 | 同上 |
| 注入（第 3 期） | 快照表 ∩ 窗口过滤 | 同上，且须 `id.ct ∈ [ctBot, ctTop]` | 同上 |

> **一句话**：本功能解决的是「好友刚发不久就删、刷新后首屏看不见」，**不是**「翻遍三个月朋友圈找回所有被删动态」。这个范围必须写进产品文案，不能承诺后者。

**旁路（复核建议 ②，可选、第 1 期加）**：按当轮窗口 `[ctBot, ctTop]` 从 `SnsInfo` 点查补档，**只存内容、⛔ 不读 `sourceType`**。存档可以「过富」，判据仍只信 `respIds` —— 这样不碰 R31h 红线，又能让「翻页可见但从未进响应」的历史动态进快照表。该旁路**只影响第 3 期能注入什么**，不影响第 2 期判据。

### 3.3 判删侧的互斥优先级（**分支，不是 AND 列表**）

> 硬约束：`blocked`/`restored`、`syncDel`/`restored` 这类互斥结果必须写成优先级分支。
> §1-5 已废 `syncDel`（H3b），故本设计**不再引入** `syncDel` 信号；`restored`（重现）保留为最高优先级撤销分支（沿用 `docs/_r37_graytag_datasource.md:592`/`:577-586` 的语义，但换掉其信号源）。

```
对每个「上一轮窗口内出现过」的 id：
  if (id ∈ 本轮 respIds)                    → 分支0：撤销（restored）：confirmed=0、revoked_at=now，不再判删
  else if (¬窗口闸门全过)                   → 分支1：放弃(静默)：不记 ev_rounds、不进观察态、不打标
  else if (连续 ≥2 个独立轮次缺席 ∧ 本地行仍在) → 分支2：疑似删除（升级打标须交叉校验，见 §4）
  else                                      → 分支3：观察态（只计数，不打标）
```

- 分支 0 先于一切：`restored` 与「疑似删除」互斥，出现重现即判「之前判错了」（`docs/_r37_graytag_datasource.md:553-554` `confirmed` 与 `revoked_at` 互斥）。
- 分支 1 是**绝对闸门**：窗口没钉住 = 该信号**不存在**，不是「弱证据」（`docs/_r37_graytag_datasource.md:574-575`），绝不「先记一笔」——那正是 R31h 的污染路径。
- 沿用 `C5`/`G3`「本地行仍在」按 id 点查（§1-8 存在性口径）：行没了 = 物理删除，归 `T_SNS` 那条路，**不进本判据**，避免与 `blocked` 语义打架（`docs/_r37_graytag_datasource.md:192`/`:359`）。

### 3.4 三项论证

**① 能否绕开 R31h —— 能，但附一条等价红线。**
R31h 根因（`SnsTimelineMerge.kt:159-163`；`docs/_r37_graytag_datasource.md:506`/`:575`）：拿「补位台账 `fixed_orig` / `sns_visible_src`」当删除判据，台账里全是模块自己补过位的正常动态 ⇒ 正常动态全被插灰标。新架构的判据成员资格 = 「上一轮 `respIds` 里出现过」+「两轮窗口都覆盖其 `createTime`」+「下一轮缺席」，**补位台账完全不进入任何判据路径**，`sourceType` 也不作纳入判据 ⇒ 结构上绕开。
⛔ **等价红线（必须写进第 1 期验收）**：**快照表不得退化成「补位台账 2.0」**——若实现时偷懒用「扫库 `src & 2 != 0`」填快照表（即 §3.1 被否的写法），就会原样重演 R31h。判读口径：第 0 期探针里快照表成员必须能逐条对应到某轮 `respIds`。

**② 能否解决「B 产品痛点：删了看不见」—— 能，但有前提。**
现状：界面按同步响应构建（§1-4，补位改库无效），注入源 `listArchiveIds` 又零成功归档（F6，`docs/_r38_disproved.md:47`）⇒ 删了确实看不见。新架构可见侧从**快照表**注入回 `zb5.f`（`SnsTimelineMerge.kt:13` 方案 A，全项目唯一能改界面的通道），且快照是「本轮响应里见过的」自然新鲜，不像 archive 是 5~8 月老数据；`attrBuf`/`content`/图片路径按 id 从库点查（`:187-198` 两来源范式）。
**前提**：注入必须按**当前时间线窗口过滤**（`id.ct ∈ [ctBot_k, ctTop_k]`，且窗口闸门过），否则就是把「两个月前的旧动态乱插」——这正是 §1-7 事故 `attempt=24 ok=24` 全是正常动态的**另一面**（当年是拿台账全量当候选）。并保留 `:145-152` 空响应闸与 `:54` `MAX_PER_ROUND` 限流。

**③ 新增风险（派发未列全，补齐）**

| 风险 | 机理 | 对策（落到代码/纪律） |
|---|---|---|
| **快照表膨胀** | `c3.N` 每秒一次心跳（`SnsTimelineMerge.kt:145`），若把每个非空响应都当独立轮次，会记大量重复轮次；表按轮追加会膨胀 | 只认「非空 且 `respIds` 与上一轮不同」为独立轮次（顶锚/集合变化为准）；快照表按 `sns_id` 主键 **upsert 不追加**（`GroupDb.kt:417`/`:436` 首条为准范式）；设保留上限 + 轮次 TTL |
| **注入污染** | 窗口外 id 混入 ⇒ 旧动态乱插（§1-7）；或把「微信清位但行还在」的 SnsInfo 内容 resurrect，与「防删除」语义冲突 | 候选四重交集（快照表 ∩ 疑似删除 ∩ 当前窗口 ∩ `pickMissing`）；`id.ct` 必须落在 `[ctBot,ctTop]` 内（`docs/_r37_graytag_datasource.md:190-191` C2/C3、`:358` G2）；限流 `MAX_PER_ROUND`（`SnsTimelineMerge.kt:54`）；单轮命中 > `MAX_CONFIRM_PER_ROUND` 整轮放弃（`docs/_r37_graytag_datasource.md:603`） |
| **与微信自身 upsert 冲突** | 清位是 `updateWithOnConflict` 整行 upsert、每轮首屏都跑（`SnsGuard.kt:227-231`）；模块补位在 after + 350ms（`:238-239`）。快照若挂在库写回调会读到「写 0 前 / 补位后」两种状态；注入的响应元素可能被下一轮 upsert 又清掉 ⇒ 「删了又出现又消失」抖动 | 快照写入挂**响应到达**（`SnsTimelineMerge.kt:143`），不挂库写回调；注入只加响应元素、**不写库**（`:13` 方案 A 语义）；重现走分支 0 `revoked_at` 撤销（`docs/_r37_graytag_datasource.md:553-554`） |
| **④ 缺席推断固有误报（补充）** | 权限变更 / 对方设可见性 / 分页边界抖动都会让 id 缺席，并非删除（`docs/_r37_graytag_datasource.md:361` G5、`:372-373` G6/G7） | §4 默认「不打标」：纯缺席推断行必须写明代价才允许开；双轮独立确认（`:191` C4 / `:360` G4）；交叉校验升级 |

> **假设 B**：两轮窗口覆盖闸门在真机上**可满足**（这是整个架构的生死数据）。`docs/_r37_graytag_datasource.md:205-206`/`:513` 已指出 C1「顶锚未变」在活跃朋友圈上大概率不满足。
> **可证伪探针**：`adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op msync --es sub watch`（60s 窗口），配合 `op=sql --es q 'select snsId,createTime from SnsInfo where … order by createTime desc limit 20'` 连续记 5 轮 `ctTop/ctBot`；若 5 轮里 `ctTop` 变化 ≥1 次（常态）⇒ 双轮窗口可满足率低 ⇒ 主判据须降为「兜底交叉校验」，改以方向 B 相册接口差集（G1~G5）为主、或把「两轮」放宽为「两轮内顶锚变化但 ct 区间仍覆盖」的松弛闸门（代价须在 §6 登记）。

---

## §4 判据表（信号组合 → 三态）

> **执行方式：按行号从上到下短路，命中即停**（优先级分支，不是 AND 列表）。结果三态：`打标` / `不打标` / `放弃(静默)`。
> 「放弃(静默)」= 不写 `ev_rounds`、不进观察态、不打日志结论；「不打标」= 可计数/观察，但绝不落灰标。
> 信号定义：
> - `R` restored：该 id 在本轮 `respIds` 重新出现（`SnsTimelineMerge.kt:143`）。
> - `B` blocked：本地物理 DELETE 被拦、行在 `T_SNS`（`GroupDb.kt:537`，现状，覆盖「自己删自己」）。
> - `W` 窗口闸门全过：非空响应（C6/G1）+ 顶部覆盖（C3）+ 底部覆盖（C2/G2）+ 顶锚未变（C1）+ 本地行仍在（C5/G3）。
> - `D` 疑似删除：连续 ≥2 个**独立轮次**缺席 ∧ `L` ∧ `W`（§3.3 分支 2，新架构主判据，**缺席推断**）。
> - `A` 方向 B：好友相册接口差集 ∧ G1~G5 全过（`docs/_r37_graytag_datasource.md:353-361`，需交互）。
> - `T` 方向 A：时间线同窗口差集 ∧ C1~C6 全过（`docs/_r37_graytag_datasource.md:184-193`，兜底）。
> - `L` 本地行仍在（按 id 点查，§1-8 口径）；`P` `isPrivate`(`sourceType & 4`) 或 `snsId==0`；`X` 单轮命中 > `MAX_CONFIRM_PER_ROUND`(建议 20)；`S/S2` syncDel（H3b）。

| 行 | 信号组合 | 结果 | 依据（文件:行）与代价 |
|---|---|---|---|
| **0** | **`R`（任一时刻，无论其它信号）** | **撤销（不打标）** | **互斥项优先级最高**：`revoked_at=now`、`confirmed=0`，不再判删。`docs/_r37_graytag_datasource.md:592`/`:553-554`/`:577-582`。⛔ `R` 与 `B`、与「疑似删除」均互斥，出现即以 `R` 为准，绝不写成 `B ∧ R` |
| 1 | `P`（私密 / 哨兵 id） | 放弃(静默) | 绝对排除闸门，**只作排除不作纳入**；`docs/_r37_graytag_datasource.md:602`/`:516-518`、`SnsDeletedTagLogic.kt:81-82` |
| 2 | `X`（单轮命中 > 20） | 放弃(静默) + warn | 防「一夜几百条」重演；`docs/_r37_graytag_datasource.md:603`（`SnsTimelineSourceFix.kt:415-419` 512 条事故、`GroupDb.kt:521` 3447 条事故）；正常删除是个位数事件 |
| 3 | `S` 或 `S2`（`x2.U/O` 命中） | 放弃(静默) | §1-5 H3b 已废、三轮零命中（含 `errType=0`）；出现即视为噪声，**永不采信**。`docs/_r38_disproved.md:26` |
| **4** | **`¬W`：窗口闸门任一未过**（空响应 C6/G1、顶部未覆盖 C3、底部未覆盖 C2/G2、顶锚变 C1） | **放弃(静默)** | **窗口闸门单独成行**。**未过闸门一律「放弃」而不是「打标」**，不记 `ev_rounds`、不进观察态。`docs/_r37_graytag_datasource.md:184-193`/`:574-575`（「窗口没钉住 = 信号不存在，不是弱证据」）、`SnsTimelineMerge.kt:145-152` |
| 5 | `¬L`（本地行已不在 / 按 id 点查不存在） | 放弃(静默) → 归 `T_SNS` 路 | C5/G3；行没了 = 物理删除，不进本判据，避免与 `blocked` 语义打架。`docs/_r37_graytag_datasource.md:192`/`:359` |
| 6 | **`D` 单独**（双轮缺席 ∧ `L` ∧ `W`，无交叉校验） | **不打标**（观察态，仅计数） | **纯缺席推断默认「不打标」**。代价：删了不提示（B 产品痛点仍在）、但零误报；这是 §1-7 分页假消失事故后的保守默认值 |
| 7 | **`A ∧ L ∧ ¬R`**（方向 B 相册差集 G1~G5 全过 ∧ 本地行仍在 ∧ 未重现） | **打标** | **REV-1（复核 P1-1）**：原写 `D ∧ A`。但 `D` 内含 `W`（含 C1「顶锚未变」，活跃朋友圈大概率不满足）⇒ 方向 B 本来比方向 A **更可靠**（`_r37_graytag_datasource.md:341`），却被方向 A 的闸门反向否决，判定方向**倒转**，且**堵死了假设 B 证伪后「改走方向 B」的退路**（§6.1 B 行的备选在表内无路径）。方向 B 的窗口可信度由它**自己的** G1~G5 保证（单作者集合、无顶部插入，`:353-361`），**不需要再过 C1**。<br>代价不变：需用户访问该好友主页 ⇒ **半自动**；没访问过的作者永远回到行 6「不打标」（`:372` G6） |
| 8 | `D ∧ T`（方向 A 时间线差集 C1~C6 全过） | 不打标（仅计数） | C1 顶锚未变在活跃朋友圈大概率不满足（`docs/_r37_graytag_datasource.md:205-206`/`:513`）⇒ 定位兜底，不单独采信；同 `:598` 行 6 裁决 |
| 9 | `A` 部分成立（G1~G3 过、但 **G4 双轮未满**，即单次缺席） | 不打标（观察态） | 单信号单次缺席 = 抖动 / 首屏未加载完（G4 需 ≥2 次，`docs/_r37_graytag_datasource.md:360`）。与行 7 的分界：**行 7 = A 全过，行 9 = A 差 G4** |
| 10 | `B ∧ ¬R` | **打标**（沿用现状） | 覆盖「自己删自己的动态」，与新判据**并集**、语义不变；`docs/_r37_graytag_datasource.md:566`/`:600`、`GroupDb.kt:537` |
| 11 | `B ∧ D`（同 id 既有物理删除又有缺席） | **打标**（只记一次，不叠加） | 打标是布尔量不是计数；`docs/_r37_graytag_datasource.md:583`（`B ∧ S` 同理） |
| 12 | `T_SRC` / `fixed_orig` / `sns_visible_src` / `listSrcFixedIds` 出现在候选里 | 放弃(静默) | ⛔ **R31h 红线**：补位台账永不作纳入判据；`SnsTimelineMerge.kt:159-163`、`docs/_r37_graytag_datasource.md:506`/`:575` |
| 13 | 可见侧注入命中（快照表 ∩ 当前窗口） | 不打标（仅注入，不改判据） | 可见侧与判删侧解耦：注入只解决「看得见」，**不产出「被删」结论**，避免注入反过来污染判据。`SnsTimelineMerge.kt:13`/`:182` |
| 14 | 其它 / 单轮首次缺席 | 不打标（观察态） | 保守公理：只接受有正向证据才打标；`docs/_r37_graytag_datasource.md:16`、`:191` C4 / `:360` G4 双轮确认 |

**「开」纯缺席推断的代价（若要把行 6 升级为打标，必须逐条登记）：**
1. 误报方向是「错打标」——权限变更、对方设可见性、屏蔽、分页边界抖动都会被当成删除（`docs/_r37_graytag_datasource.md:361` G5、`:372-373` G6/G7）；这正是 §1-7 事故（24/24 全是正常动态）的复发路径。
2. 因此**默认不开**；只有第 2 期真机数据证明 `D` 的精确率 ≥ 阈值、且 `R` 撤销分支（第 0 行）与 `X` 限流（第 2 行）都生效后，才允许把行 6 改为「打标」，且必须在 §6 登记为新的**假设**并附探针。
3. 行 7 是唯一「开」的口子，且已写明半自动代价；**不允许**出现「窗口没钉住但先记一笔」的中间态（`:575` 明写那是 R31h 污染路径）。

---

## §5 分期开发计划（重点）

> **本 lane 只读**：以下「改动面」**只描述、不落地**（不改 `app/src/main`、不装包、不 commit），由后续开发 lane 执行。
> 切分：**前置第 0 期（纯取证，无功能代码）+ 功能 3 期**。每期**独立验收、独立回滚**；上一期 gate 不过，**不得进入下一期**。
> 总纪律（每期都适用）：`docs/_r38_disproved.md:99` LogX 5000 行环形 ⇒ 大动作前 `logclear`；`:100` 装包后冷启两次、判包用 `pm path` + `lastUpdateTime`；`:101` 构建一律 `--rerun-tasks`（否则 FROM-CACHE 假绿）；`:102` 开/关对照**成对做**，关开关后先验证模块确实静默再下结论；`:98` 广播前确认 `mCurrentFocus` 是 `com.tencent.mm`。
> 受控实验唯一合法样本（§1-9/§1-10）：**「她发 → 我方刷出并确认看见 → 她再删」**，不用自删、不用发完立刻删。

### 第 0 期 · 纯取证期（**先验证判据成立，再落功能代码**）

| 项 | 内容 |
|---|---|
| **目标** | 用**探针**在真机上验证「存档 + 判删」判据成立，即 §3 的**假设 A/B/C**。**不写任何功能代码**：不改判据、不改注入源、不改读端、不建表。只加日志/探针字段。 |
| **改动面（描述）** | **新建** `feature/SnsRoundLedger.kt`（纯内存轮次账本，零 DB 写入）；`SnsTimelineMerge.kt:143`（`respIds` + `CreateTime` 已有，**零新增钩子**复用，`docs/_r37_graytag_datasource.md:398-401` C-2）在空响应闸之后加**一行** `SnsRoundLedger.onResponse(...)`；`SnsGuard.kt` 的 `onSnsUpdateAfter`（现有 after 钩已能读到 `snsId`，`:250-290` 反射缓存）加**一行** `SnsRoundLedger.onUpsert(id)`，**不动 `reFixAfterClear` 时机 / 不动 `lastUpdateZeroAt` 节流**；`EntryReceiver.kt:345` `snsg` 分支新增 `sub=win` / `sub=winreset`；`GroupDb.kt` **完全不动**。 |
| **新增探针字段** | `win.round`（独立轮次号）、`win.ctTop`/`win.ctBot`、`win.respN`、`win.upsertN`、**`win.subset`**（`respIds ⊆ upsertIds`，P0-1 核心）、`win.dup`（被判为心跳重复而跳过的轮次数）、`win.c1`（C1 可满足率）；C1~C6 在 §6.1 假设 B/D 的探针里逐条读。命令：`adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op snsg --es sub win` |
| **验收判据（真机怎么测）** | ① 按合法样本做「她发→看见→她删」，配 `op=msync --es sub watch` 确认 `onGYNetEnd` 真发生（§1-13）；② **看哪行日志**：`SNS-INJECT first hit: respIds=M`（`SnsTimelineMerge.kt:155`）、`SNS srcguard re-fix after clear fixed=N`（`SnsGuard.kt:220`）、新 `sub=win` 输出、`MSYNC OK scene=256 type=214`（`EntryReceiver.kt:696`）；③ `op=sql --es q '…'` 按 id 点查行在不在（§1-8 唯一存在性口径）；④ **正反对照**：开 `antirevoke.sns` / `kvset antirevoke.sns false`（§2.6）各做一遍，结论应一致；「删」/「不删」各做一遍。 |
| **通过门槛（量化 gate）** | ① 5 轮独立同步里 **C1（`ctTop` 未变）可满足 ≥1 次**（验假设 B 的生死数据，见 `sub=win` 的 `WIN c1` 行）；② 被删 id 自删后第 1 个独立轮次起缺席且连续 **≥2 轮**（验判删）；③ **正常动态被判缺席 = 0 次**（否则判据不成立，退回 §6 重设计）；④ **假设 A（P0-1 订正）**：`WIN gate` 行 `subset=true`（**集合包含**：本轮 `respIds ⊆` 同期 `updateWithOnConflict("SnsInfo",…)` 批次 id）在 **≥3 个独立轮次**成立。<br>⛔ **不再用**「`respIds` 与 `fixed=N` 同量级」——`N`≈全台账 2118 行、`M`≈单批 10~20，**量纲差 ~100 倍，字面永不达标**，会把四期链整体锁死（`_r38_review.md:190-198`）。<br>⑤ **翻页覆盖率（P0-2 新增，非 gate）**：`sub=win` 输出「库里 `createTime desc limit 60` ∩ 累计 `respIds` 并集」的覆盖率。**它不是门槛，是「有效范围」的事实依据**——预期偏低即印证「存档 = 看到才存」，须写进 §3 的范围声明。<br>**分流**：①②③ 任一不达标 ⇒ **停在第 0 期，退回 §6 重设计**；④ 不达标 ⇒ **不算 gate 失败**，走 §6.1 假设 A 已登记的备选（存档成员改挂 `updateWithOnConflict` 批次），**继续第 1 期**。 |
| **回滚方式** | 本期只加日志/探针字段 ⇒ 去掉字段即完全回退，零功能影响；`antirevoke.sns` 开关状态不变。 |
| **预估风险** | `MSYNC watch` 60s 窗口可能 `observed=0`（§1-13）⇒ 改用「退出重进首屏」触发真同步；日志环形覆盖（`docs/_r38_disproved.md:99`）⇒ 每轮前 `logclear`；`c3.N` 心跳空响应（`SnsTimelineMerge.kt:145`）⇒ 必须按「非空且 respIds 变化」判独立轮次，否则 `win.round` 被灌水。<br>**⚠️ 两个会造成假阴性的实现陷阱（实现时已踩过）**：① `onUpsert` 采样点**必须早于** `sourceType` 判定（`SnsGuard.kt:316`）——正常行写 `2`/`10` 会在 `:325-348` 提前 return，采样晚一步就只能收到「写 0」的行，`upsertN` 恒空、`subset` 恒 false ⇒ **A 被误判证伪**；② `snsIdOf`（`:298-307`）只认**长度为 1** 的 `whereArgs` 数组 ⇒ 批量 `WHERE snsId IN (?,?)` 采不到，`upsertN` 会偏小。判读纪律：`subset=false` 时**先**点查 `miss` 里的 id 在库中是否存在，存在即为采样盲区，不得直接判 A 证伪（详见 `docs/_r40_p0_verify.md` §2）。 |

### 第 1 期 · 存档侧（Snapshot Writer + 快照表）

| 项 | 内容 |
|---|---|
| **目标** | 每轮独立同步把「本轮 `respIds` 里出现过的 id」upsert 进快照表（内容/时间/作者/图片路径），**不依赖删除事件、不读 `sourceType`**；窗口账本落库。**判删侧、可见侧、读端一律不动。** |
| **改动面（描述）** | `data/GroupDb.kt` 新增 `T_SNS_SNAP` 表（`sns_id` 主键 upsert、首条为准，范式 `:417`/`:436`）；`SnsTimelineMerge.kt:143`/`:155` 附近调用写入；`SnsGuard.kt:424 readRow` 复用取 `content`/`attr_buf`；`data/Keys.kt` 新增**独立开关**（与 `Keys.kt:141` `antirevoke.sns` 解耦，便于单独回滚）。⛔ **不得**用「扫库 `src & 2 != 0`」填表（§3.1 已否）。 |
| **新增探针字段** | `snap.upsertN`、`snap.tableSize`、`snap.roundNo`、`snap.srcIgnored`（显式记录「未读 sourceType」）。`op=snsg sub=stat` 增加 `snap=` 段。 |
| **验收判据** | ① `stat` 的 `snap.upsertN` 随真同步轮次增长、`snap.tableSize` 单调不爆；② `op=sql` 查快照表成员**能逐条对应到某轮 `respIds`**（§3.4① 等价红线，防退化成「补位台账 2.0」）；③ **正反对照**：`kvset antirevoke.sns false`（模块补位静默）下快照表成员不变 ⇒ 证明不吃 `sourceType`/补位；`archive` 表仍零新增（`SnsGuard.kt:649`/`:651` 的 `ok` 恒 false）⇒ 证明没走 DELETE 通道；④ **界面无任何变化**：`wxgroup/_vis.py` dump 前后一致（本期不该改界面）。 |
| **回滚方式** | 独立开关关掉即停写；删 `T_SNS_SNAP` 表即回退。现有 `SnsTimelineMerge.kt:172` 注入仍走 archive，行为零变化。 |
| **预估风险** | 快照表膨胀（§3.4①：心跳轮次）⇒ 必须复用第 0 期「独立轮次」判定 + upsert 不追加 + 保留上限；与微信 `updateWithOnConflict` 时序冲突（§3.4③，`SnsGuard.kt:227-231`）⇒ 写入挂**响应到达**而非库写回调；`GroupDb.kt:521` 3447 条事故前车之鉴 ⇒ 限流。 |

### 第 2 期 · 判删侧（Window Arbiter + 判据表落地）

| 项 | 内容 |
|---|---|
| **目标** | 落地 §4 判据表，输出「疑似删除」集合（**默认不打标**，行 6），接入第 0 行 `R` 撤销分支。读端**暂不改**（只写观察态，不产出灰标）。 |
| **改动面（描述）** | `data/GroupDb.kt` 新增观察态表，字段直接复用 `docs/_r37_graytag_datasource.md:535-547`（`sns_id`/`ev_kind`/`ev_rounds`/`win_ok`/`confirmed`/`revoked_at`）；新增 `feature/SnsWinArbiter.kt` 实现 §3.3 优先级分支；`SnsTimelineMerge.kt:143` `respIds` 复用做差集；`SnsDeletedTag.kt:1690` → `GroupDb.kt:537 snsTagIds` 读端**本期不动**。 |
| **新增探针字段** | `win.ok`（C1~C6 逐条）、`miss.rounds`、`suspect.n`、`revoked.n`、**`abandon.n`（放弃计数必须显式落库**，`docs/_r37_graytag_datasource.md:547`）。 |
| **验收判据** | ① 合法样本删后，`sub=win` 显示目标 id `miss.rounds≥2` 且 `win.ok` 全 1 ⇒ 进 `suspect.n`；② **关键反例**：正常动态 `suspect.n` 必须为 **0**（否则即 R31h 复发，**立即回滚**）；③ 未过窗口闸门的轮次 `abandon.n` 增长而 `suspect.n` 不动（证明「放弃而非打标」）；④ **正反对照**：开/关 `antirevoke.sns` 各一遍，结论一致（判据不吃 `sourceType`）；⑤ `SNS-INJECT attempt/ok` 无新增（`SnsTimelineMerge.kt:284`）⇒ 证明没把观察态误当注入候选；⑥ 目标 id 重现时 `revoked.n` +1、`confirmed` 回 0（第 0 行分支生效）。 |
| **回滚方式** | 观察态表独立、读端未改 ⇒ 停写即完全回退，灰标行为零变化。 |
| **预估风险** | 窗口闸门 C1 可满足率低（假设 B，`docs/_r37_graytag_datasource.md:205-206`）⇒ `abandon` 大、`suspect` 恒 0（保守但不解决痛点，属可接受降级，须在 §6 登记）；`ev_rounds` 被心跳重复轮次灌水 ⇒ 强制复用第 1 期独立轮次判定。 |

### 第 3 期 · 可见侧（Injector）+ 交叉校验（可选）

| 项 | 内容 |
|---|---|
| **目标** | 把注入源从 `listArchiveIds` 换成「快照表 ∩ 疑似删除 ∩ 当前窗口」，解决 **「删了在首屏看不见」——仅限顶部近期窗口**（§3.2.1 有效范围；R39 已证被删动态翻页也读不出，所以「翻页可见」不构成竞争方案）；**可选**接入方向 B 相册差集（§4 行 7 `A ∧ L ∧ ¬R`）把行 6 升级为打标。 |
| **改动面（描述）** | `SnsTimelineMerge.kt:172`（`listArchiveIds(500)` → 新候选源）、`:182`（`pickMissing` 前加 `id.ct ∈ [ctBot,ctTop]` 窗口过滤）、`:54`（`MAX_PER_ROUND` 限流**保留**）、`:145-152`（空响应闸**保留**）、`:187-198`（attrBuf 来源优先快照表）；`docs/_r37_graytag_datasource.md:535` 观察态表 `confirmed` 落库；**读端必须两处同改**（P1-5）：`SnsDeletedTag.kt:1690` **与** `SnsTag.kt:679` —— 或抽 `GroupDb.snsDeletedIds()` 供两边 `loadIds` 共用（只改一处会让两条 2s sweep 口径分叉 ⇒ 灰标闪烁，`SnsTag.kt:695-702` 自证）；方向 B `jc5.f` 为**可选半自动**路径（需交互）。⛔ 不改 `sourceType`、不写库（`:13` 方案 A 语义）。 |
| **新增探针字段** | `inject.src=snap\|archive`、`inject.winFiltered`（被窗口过滤掉几条）、`inject.ok`、`tag.confirmed`。 |
| **验收判据** | ① 合法样本删后**界面出现那条动态**——`wxgroup/_vis.py` + `uiautomator dump` 对比 `has_verity`（§1-4 唯一硬证据，也是 §2.5 屏幕取证用途）；② `SNS-INJECT` 日志 `inject.src=snap`、`attempt/ok` 为**个位数且全为目标 id**（不是 §1-7 的 `24/24`）；③ **正反对照 1**：`kvset antirevoke.sns false` 后界面仍出现 ⇒ 注入不依赖补位；④ **正反对照 2**：临时去掉窗口过滤 ⇒ 出现「两个月前旧动态乱插」 ⇒ 证明过滤器必要（复现 §1-7 事故的另一面）；⑤ 正常动态被注入 **0 条**；⑥ 若开行 7，`tag.confirmed` 只在 `A ∧ L ∧ ¬R` 时 +1，且 `R` 出现即撤销；⑦ **双 sweep 口径一致（P1-5 新增）**：`SnsTag`（`SnsTag.kt:679`）与 `SnsDeletedTag`（`SnsDeletedTag.kt:1690`）两条 2s sweep 在「开关 on / 开关 off / 功能未启用」三种状态下读出的灰标集合**完全相同**，且连续 10 次 `_vis.py` dump 无闪烁。 |
| **回滚方式** | 候选源改回 `SnsTimelineMerge.kt:172` `listArchiveIds` 即回到第 2 期行为；可见侧独立开关；`confirmed` 不落库则灰标零变化。 |
| **预估风险** | 注入污染（§3.4②）、与微信自身 upsert 冲突导致「删了又出现又消失」抖动（§3.4③，靠第 0 行 `R` 撤销）、缺席推断固有误报（§3.4④）；方向 B **需用户访问好友主页 ⇒ 半自动**，覆盖不到的作者回到行 6「不打标」（代价已在 §4 行 7 写明）。 |

### 5.5 跨期依赖与 gate 一览

```
第0期（纯取证，验证 A/B/C）──gate: C1 可满足≥1轮 ∧ 被删id缺席≥2轮 ∧ 正常动态误判=0 ∧ subset=true≥3轮──▶ 第1期（存档侧）
第1期（快照表，界面零变化）──gate: 成员可逐条对应 respIds ∧ archive 零新增──▶ 第2期（判删侧）
第2期（观察态，读端未改）──gate: 正常动态 suspect.n=0 ∧ 开/关对照一致──▶ 第3期（可见侧）
任一 gate 不过 ⇒ 停在当前期，退回 §6 重设计；每期回滚都只影响本期新增对象。
```

> ⛔ **「假设被证伪」≠「gate 失败」**（`_r38_review.md:207-211` 订正）。
> 假设 A~G 每一条在 §6.1 都**已登记证伪后的改法**（例：A 证伪 ⇒ 存档成员改挂 `updateWithOnConflict` 批次；B 证伪 ⇒ 主判据降为兜底、改走方向 B）。
> 因此正确动作是「**就地切换已登记的备选方案 + 登记代价 ⇒ 继续下一期**」，而不是「停在当期重设计」。
> 只有 §5 第 0 期 gate 的 ①②③（C1 可满足 / 被删缺席 / 正常动态零误判）**没有备选**，它们不过才真的停。

---

## §6 未解项与假设

> 逐条：`假设内容 / 为什么只能假设 / 证伪探针 / 证伪后计划怎么改`。
> 本节所有命令**仅写入文档，本 lane 不执行**（只读约束）。

### 6.1 假设清单（A~G）

| 假设 | 内容 | 为什么只能假设 | 可证伪探针（命令） | 证伪后计划怎么改 |
|---|---|---|---|---|
| **A** | `respIds`（`SnsTimelineMerge.kt:143`）能代表「服务端本批返回行」，与 `updateFilterTimeLine` 回填集合一致（§3.1） | 一个是响应对象列表、一个是库 upsert 批次（`SnsGuard.kt:227-231`），二者是否同一集合**静态不可证**；`classifytimeline` 混淆链成本超收益（`HookEntry.kt:282`） | **REV-2（P0-1 订正）**：`op=snsg --es sub win` → 看 `WIN gate … subset=`，即本轮 `respIds ⊆` 同期 upsert 批次 id（**集合包含，非数量比较**）；单轮 `false` 不算证伪（微信只写变化的行），须在目标 id 首次出现那一轮判 | 存档成员资格改挂 `updateWithOnConflict` 批次（`SnsGuard.kt:241`）；**第 1 期**改动面换锚点，§3 三层与 §4 判据表不变，仅 `Snapshot Writer` 触发点改写 |
| **B** | 两轮窗口覆盖闸门在真机上**可满足**（§3.4 生死数据） | ❌ **2026-11-03 真机证伪**（`docs/_r40_p0_gate.md`）：同进程相邻两轮 `ctTop` **两次都变**，`C1 = 0/2 = 0%`。且是**结构性**的——微信首屏节流要求两轮间隔 ≥5 分钟，而 5~39 分钟的窗口里朋友圈必然出现新动态 ⇒ C1 越等越不可能满足 | `op=snsg --es sub win` 看 `WIN c1 ctTopUnchanged=x/y`（已实测，无需再跑） | **按本条登记的备选执行**：主判据降为**兜底交叉校验**（§4 行 8 定位，默认不打标），改以**方向 B 相册差集（G1~G5）为主**（§4 行 7 `A ∧ L ∧ ¬R`）。**代价**：方向 B 需用户访问好友主页 ⇒ 半自动；未访问过的作者永远落行 6「不打标」。§5 第 2 期目标句与 gate 须据此重写（登记为 R41） |
| **C** | 从快照表注入回响应能**改界面**（`has_verity=True`）（§3.4②） | ⚠️ **已有间接证据（复核 P2-9/§6 订正）**：`派发审计台账.md:2515` 第 6 条把「**删了又出现**」归因为注入、`docs/_r38_disproved.md:91`（§4 第 4 条）称「**旧动态乱插**」是**已观察到的副作用** —— 两者都只有在「注入的响应元素真被渲染」时才可能发生 ⇒ C 大概率成立。仍缺的是**正面**证据：`attempt=24 ok=24`（§1-7）只证明「注入的能从库读回 attrBuf」，没证明渲染 | `adb shell uiautomator dump /sdcard/win.xml && cp /sdcard/win.xml wxgroup/_vis.xml && cd wxgroup && python _vis.py` → 对比第 3 期注入前后目标 id 的 `has_verity`（`docs/_r38_disproved.md:74`）。**成本一行探针**，建议提前到第 0 期做，把 C 从「第 3 期生死假设」降为「已验事实」 | **可见侧作废/降级**：第 3 期取消或降级，B 痛点改走「提示 + 快照导出查看」而非界面复活；§3 三层退化为两层（存档+判删），§5 删除第 3 期，§4 行 13 删除 |
| **D** | `R`（重现）可作可靠撤销锚点（§4 第 0 行） | `respIds` 只在 `c3.N` 被调用时收集，而 `c3.N` 每秒心跳且常空（`SnsTimelineMerge.kt:145-150`）；某轮空响应漏采 id ⇒ 假 `restored` 或假缺席 | `adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op snsg --es sub win` → 看 `win.respN` 是否与非空轮次一致，并用 `op=sql` 按 id 核对 | 撤销分支加「连续 **2 个非空轮次**都出现」才判 `R`；或改用「本地行被服务端回填」作交叉锚点——⚠️ 后者会重新引入 `sourceType`，**须先评估是否触碰 R31h 红线**，倾向不用；§4 第 0 行加前置条件 |
| **E** | 「疑似删除」**默认不打标**（§4 行 6）在产品上可接受 | 这是**产品取舍**不是技术事实：B 痛点要「删了能看见」，而「看得见」（行 13 注入）与「打灰标」是两件事，用户是否接受需产品确认 | 无纯技术探针；用第 3 期真机 demo + 用户确认；技术侧替代观测为 `tag.confirmed` 与 `inject.ok` 比值（`SnsDeletedTag.kt` / `SnsTimelineMerge.kt:284`） | 若必须打标：**强制走行 7 `A ∧ L ∧ ¬R` 交叉校验**（半自动代价已在 §4 写明），⛔ **不得**直接把行 6 改成「打标」——否则复发 §1-7 分页假消失事故；§4 行 6/7 重写代价段 |
| **F** | 快照表与微信自身 `updateWithOnConflict` 整行 upsert **无写冲突**（§3.4③） | 微信侧（`SnsGuard.kt:227-231`）与模块响应注入侧（`SnsTimelineMerge.kt:13`）在同一轮里的**先后顺序静态不可判定** | 第 0 期 `sqlspy`（`EntryReceiver.kt:379-380`）抓同一轮 SQL 时序 + `sub=win` 的 `win.round` 时间戳对齐 | 快照写入改为「响应回调内同步取 + 内存暂存、批末落盘」，避开微信写库窗口；若仍冲突，第 3 期注入降级为**只读提示**（不注入响应） |
| **G** | `A ∧ L ∧ ¬R`（方向 B 相册差集）能构成**独立**交叉校验（§4 行 7） | 方向 B 需用户主动访问好友主页（`docs/_r37_graytag_datasource.md:372` G6），且 G1~G5 全过的可满足率**未验证**（`:380-387` 只给探针未给结果） | `docs/_r37_graytag_datasource.md:380-387` 的 G2 可满足率探针（看「响应 ct 区间覆盖该 id 的 ct 且响应无此 id」是否成立） | **行 7 关闭**，`D` 永远保持「不打标」，产品侧只保留可见侧注入（看得见不提示）；§4 行 7 代价说明重写，§5 第 3 期「可选交叉校验」子项取消 |

### 6.2 已知缺口 / 口径未解项（非假设，待订正或核实）

| # | 未解项 | 现状 | 处理 |
|---|---|---|---|
| 1 | **`MAX_PER_ROUND` 8 vs 12** | 代码 `SnsTimelineMerge.kt:54` = **12**；`docs/派发审计台账.md:2515` 与派发简述写「每轮注入 8 条」（§2.4 已订正） | 本文按 `:54` 的 12 为准；建议主会话订正台账 `:2515`，避免第 0 期判读 `SNS-INJECT` 日志时用错基准 |
| 2 | **`SnsTimelineSourceFix.kt:367 patchAlbumBit` 未直接读到** | 该行号来自 `docs/_r38_disproved.md:64` 二手引用；本次只读 Grep 的 `ANTIREVOKE_SNS` 命中为 `:478`/`:531`/`:1106`，未覆盖 `:367` | §2.6 按二手引用标注；**第 1 期实现前须核一次该行**（只读 `Read` 即可），不影响任何判据结论 |
| 3 | **微信混淆内部链不深入** | `updateFilterTimeLine` / `classifytimeline` / `qg3/i` / `jc5.f` 均为混淆名，静态取证成本超收益（`HookEntry.kt:282`） | 本设计一律以「响应集合 `respIds`」为**黑箱边界**（假设 A），不深入其内部；若 A 被证伪（§6.1），再决定是否破边界挂 `updateWithOnConflict` 批次 |

### 6.3 假设与分期的对应关系（便于 gate 追踪）

- 第 0 期 gate 直接验 **A / B / C / D / F**（`W` 全过率、`respIds` 一致性、界面可见性、撤销可靠性、写时序）。
- 第 2 期 gate 兜住 **B / D**（正常动态 `suspect.n=0`、开/关对照一致）。
- 第 3 期 gate 兜住 **C / E / G**（界面真出现、`confirmed` 只在 `D∧A` 时 +1）。
- 任一假设被证伪 ⇒ 按本表「证伪后计划怎么改」就地调整，**不跨期带病推进**；每期回滚只影响本期新增对象（§5.5）。

---

**红线复核（定稿自检）**：
- ✅ R31h：`sns_visible_src`/`T_SRC`/`fixed_orig` 在 §1-11、§3.4①、§4 行 12 三处明令**永不作纳入判据**；§2.4 保留 `SnsTimelineMerge.kt:159-163` 铁证。
- ✅ 互斥项不并列：§3.3 写成优先级分支、§4 第 0 行 `R` 短路最高、§4 行 11 注「只记一次不叠加」；全文未出现 `blocked/restored`、`syncDel/restored` 的 AND 列表（`syncDel` 已在 §1-5 作废、§4 行 3 显式写「永不采信」）。
- ✅ 窗口闸门独立成行（§4 行 4），未过一律「放弃(静默)」而非「打标」。
- ✅ 纯缺席推断默认「不打标」（§4 行 6），「开」的代价写在 §4 表后与行 7。
- ✅ 第 0 期 = 纯取证期，先验证判据再落功能代码（§5）。
- ✅ 分步落盘：§1 → §2 → §3 → §4 → §5 → §6 逐节 append。

[DONE]

