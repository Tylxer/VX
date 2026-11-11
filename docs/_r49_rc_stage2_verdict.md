# R49 阶段二独立裁定报告（lane H · 只读推理）

> 性质：独立判据推理 lane，全只读。不修改源码/测试/配置/其它报告；不碰 adb / 真机 / Gradle / 装包 / kvset。
> 所有结论附 `文件:行号`；不确定项标 `[推断]` / `[需真机]`。
> 真机原始证据由主会话提供，本 lane 未复跑，仅做判据推理。

## 待答清单
- [ ] Q1 `0 条 & -9` 能否推出 R-C 证伪（互斥替代解释表 + 判别方法，禁止只给自评最优）
- [ ] Q2 阴性对照的正确解读（谁更该触发 `& -9`；0 条支持还是削弱 R-C；源码/文档锚点）
- [ ] Q3 判据是否仍缺决定性观测（最小下一步 / 是否必须进 veirty 主页 / 二元触发条件）
- [ ] Q4 `seen=11` vs `ledger size=2` 差异（去重口径）/ `verdict()` 三态核对 / 措辞是否误导
- [ ] Q5 `sourceType=10` 的 UPDATE 归因（模块保位 / 微信正常落库 / R42 A 层）
- [ ] Q6 反向对照设计（关两个开关能区分 Q1 哪几条，具体步骤 + 各解释下预期）
- [ ] Q7 三档边界（进入 / 返回 / 复位，逐档写，复位档不写"同返回"）
- [ ] Q8 最终裁定表（三态映射 + 什么等于证死 + 仍测不到的盲区）

[START]

## Q1 · 「0 条 `& -9`」能否推出 R-C 证伪

### 裁定（先给结论）
**不能。** 本轮「0 条 `& -9`」**不足以**判 R-C 证伪，三条独立理由：

1. **观测对象错位（范畴错误）**：本轮进的是 **Buudaooo（全部可见，阴性对照）**——`docs/_r49_rc_probe_verdict.md:309`（P-11「昵称B 全部可见，建正常写事件基线」）、`docs/_r49_rc_stage1_result.md:394`。R-C 的适用场景是「三天可见 ⇒ 服务端截断三天外 SnsObject」（`docs/_r49_rc_stage1_result.md:66-68`、`:78-83`）。阴性对照**不是 R-C 的触发对象**；用它的 0 条去判 R-C 死，等于在没触发条件的地方找触发结果。
2. **两个独立判据 + A 层计数一致为 0**：WP `matched=0 / clear9=0 / rewritten=0`（`SnsWriteProbe.kt:255-258`、`:307-313`）；`BLOCK w2 sqlClear9=0 sqlClear=0 sqlBlock9=0 blkOff=0`（`SnsBlockProbe.kt:573-574`、`:741-746`，其判据 `SnsBitLogic.sqlClear` 只认原文 `-9`，`SnsBitLogic.kt:186-188`）；`BLOCK stat hit=0 blocked=0`（`SnsBlockProbe.kt:728`）⇒ A 层也未触发任何拦。⇒ 窗口内**整个「清位/降位类写」都是 0**，不是「探针把 `& -9` 吞了」。`BLOCK upd seen=10 zero=0 nosrc=0`（`SnsBlockProbe.kt:738`）说明 10 次 `updateWithOnConflict` 全是非零、带 `sourceType` 键的正常写（见 Q5）。
3. **R42 硬结论的取证语境不普适**：「进一次好友主页就执行 6 条」的全部真机样本都在「**发图 → 进主页（2→10）→ 删除 → 再进主页**」的删除时序里（`docs/_r42_bit_paths.md:162`、`:167`、`:204`、`:211`）；`docs/_r42_bit_paths.md:83` 明载「`sqlSrc=0` ⇒ 清 bit3 走的是 `update` 写 `2`，**不是 `& -9`**，那条裸 SQL 这次一次都没执行」，`:128` 明载方案 A 阶段「它当时根本没执行」。⇒ 「6 条」是**条件触发**的约数，不是「任意好友任意浏览必发」。

⛔ 因此 0 条的合法读数只有一句：**在「全部可见 + 普通浏览」这一条件下，目标清位写事件未被观测到**。外推成「R-C 在任何条件下都没有写可拦」不成立。

### 互斥替代解释表（5 条，按根因所在层互斥）
> 互斥口径：五条根因分落「触发条件层 / 路径层 / 判据层 / 顺序层 / 版本·时序层」，对同一组复现实验给出**两两不同**的观测签名（下表第 3 列），故可用一次设计好的观测区分，不是同一解释的五种说法。⛔ 不评「哪条最优」，逐条给判别。

| # | 解释（根因层） | 若成立，独有观测签名 | 判别方法（最小观测，均需保持 `sns.write_probe=arm`） | 锚点 |
|---|---|---|---|---|
| **(a)** | **触发条件层**：`& -9` 语义是「服务端这次没返回、本地还有 bit3 ⇒ 清掉 bit3」，只在**删除/私密/服务端截断**时发。Buudaooo「全部可见」服务端全量下发、无「未返回的本地行」⇒ **本就不该触发，0 条是预期**（与 R-C 生死无关）。 | 换到 **veirty（三天可见）**主页即出现 `& -9`；且 Buudaooo 侧无论关开关、翻页、刷新、冷启**仍恒 0 条**。 | ① `sub=reset` → 进 veirty 主页首屏 → `stat/dump` 看 `matched/clear9` 是否 >0；② 对 Buudaooo 重复关开关 + 翻页 + 下拉，确认恒 0。 | `docs/_r42_bit_paths.md:79`（「服务端未返回、本地有 bit3 → 清 bit3」「10→2」25 条）、`:83`、`:114`；`docs/conclusions/moments.md:16-18`（只关 B 层 ⇒ `src 10→2`）；`SnsSqlRewrite.kt:8-13`（KDoc「进好友主页时微信会执行这条裸 SQL 把 bit3 削掉」）；`docs/_r49_rc_lifeline_review.md:85-87` |
| **(b)** | **路径层**：R42 的 6 条 `& -9` 是**删除动态流程**或**方案 B 开启（`-9→-1` 改写生效）时**才发的，与「纯浏览相册」不是同一代码路径；本轮既没删动态、又只是浏览阴性对照好友 ⇒ 0 条。 | 只有执行「删一条动态」或「方案 B 消融（只关 `antirevoke.sns_sqlblock_off`）后再进主页」才出现 `& -9`；单纯换好友（veirty 但不删）仍 0 条。 | ① P-10 阳性对照：请 veirty 删一条动态（`docs/_r49_rc_probe_verdict.md:308`），看 `BLOCK drop blocked` / `sqlClear9` 是否增长；② 方案 B 消融对照（`docs/_r42_bit_paths.md:219-222`）后再进主页看 `& -9`。 | `docs/_r42_bit_paths.md:162`、`:167`、`:204`、`:211`（全部样本在「删除→再进主页」时序）；`:127-128`、`:132`（方案 A 时裸 SQL 未执行、需构造「位型停在 10」场景）；`:211`、`:217`（方案 B 下 `sqlClear9=8 sqlBlock9=8`）；`docs/conclusions/moments.md:9` |
| **(c)** | **判据层**：`parseClear9` 漏识别（`-1` 兜底虽经源码复核、单测覆盖，但**真机未验**）；或 8.0.48/2588 把 SQL 形态改了（常量/绑定参数/括号变体），使 `parseClear9` 返回 null ⇒ `matched=0` 是假阴性。 | **两个独立判据分歧**：`BLOCK sqlClear9>0` 或 `BLOCK w2 sql-clear9` 打出原文 / `blkOff>0`，但 WP `matched=0`；或 `dump` 里出现一条 `UPDATE ... sourceType ...` 却没被 `parseClear9` 认出。若两判据**同时为 0**，则本条不成立（排除）。 | ① 同一窗口交叉读 `op=writeprobe sub=dump` 与 `dev.py q 'BLOCK w2 sql-clear9'`（`SnsBlockProbe.kt:591-595`），比对原文与 WP 明细；② 若 `BLOCK` 侧有原文而 WP 无 `matched`，即定位为判据漏识别 `[需真机]`。注意两判据都只认 `-9` 形态，若微信整条换形态会**一起漏**，需人工看 `BLOCK w2 sql-unmatched`（`SnsBlockProbe.kt:601-604`）。 | `SnsWriteProbe.kt:98-101`（`CLEAR9_SET` 认 `-9\|-1`）、`:117-138`（`parseClear9` 要求 `update`+`SnsInfo`+SET 段清位）、`:131`（`rewritten` 判据）；`SnsWriteProbeTest.kt:79-89`、`:146-154`（`-1` 兜底单测，`docs/_r49_rc_impl_review.md:224`）；`SnsSqlRewrite.kt:45-48`、`SnsBitLogic.kt:188` |
| **(d)** | **顺序层**：`SnsSqlRewrite` 的 `-9→-1` 改写发生在探针捕获**之前**，探针记到 `-1` 且 `rewritten` 也没加 ⇒ 误判「无 `& -9`」。 | **已由源码否定（低可能性）**：若真有 6 条被改写后捕获成 `-1`，`parseClear9` 的兜底会认、`matched≥1`、`rewritten≥1`、`clear9≥1`；实测三者**皆 0**，且 `BLOCK sqlClear9=0`（`SnsBitLogic` 只认原文 `-9`）⇒ 不是「记到 `-1` 没认出」，而是「目标 SQL 根本没进 `noteSql`」。 | 源码顺序核对 + 真机自证：① `SnsGuard.kt:844` `val sql = a[0] as? String`（局部不可变引用）；`:889` 先 `applySqlRewrite(param, sql)`；`:891-898` 后 `noteSql(sql = sql, ...)`；`applySqlRewrite` 只 `param.args[0] = out`（`:916`），**不碰局部 `sql`**；`noteSql` 首行 `captureExecSql(sql)`（`:564-567`）在 `sqlSns++`（`:568`）之前 ⇒ 探针拿到改写前原文。② 真机自证：关两个 R42 开关后（无改写）若仍 0 条且 `rewritten=0`，彻底排除。 | `SnsGuard.kt:844`、`:879-898`、`:907-921`；`SnsBlockProbe.kt:560-568`；`SnsWriteProbe.kt:98-101`、`:131`、`:308`；`docs/_r49_rc_impl_review.md:101-123`（C1 PASS）、`:285`（「`SnsSqlRewrite.kt:46` 是正则定义、真正写回在 `SnsGuard.kt:916`」）；`SnsBitLogic.kt:198`（KDoc 明说不认 `-1` 是为保「`-9` 有没有执行」可分辨） |
| **(e)** | **版本·时序层**：微信 8.0.48/2588 GP 与 R42 取证时的版本/开关态差异；或首屏不发、需**翻页/下拉刷新/后台同步/冷启再进**才发第二组。 | 同一好友同一动作下，`stat` 在**首屏 0 条**但翻页/下拉/冷启后 `matched>0`（时序依赖）；或 `op=kvget` 回读开关态与 R42 取证态不一致；或日志出现「只有上界 `snsId < B`」等新分页形态（`rangeKey` 记 `?`）。 | ① 同一 veirty 会话逐档跑：首屏 → 逐屏翻页 → 下拉刷新 → 后台同步 → 冷启再进（`docs/_r49_rc_probe_verdict.md:456` 返回档），每档前 `sub=reset` + `op=logclear` + `stat/dump`；② `op=kvget` 回读 `antirevoke.sns`/`antirevoke.sns_block`/`antirevoke.sns_sqlblock_off` 并与 `docs/_r49_rc_impl_review.md:139-145` 口径核对；③ 看 `rng=?`（`docs/_r42_bit_paths.md:227`）。 | `docs/_r49_rc_probe_verdict.md:456-457`；`docs/_r42_bit_paths.md:227`、`:211`（6/8 条口径差异，`docs/_r49_rc_lifeline_review.md:26`）；`docs/conclusions/moments.md:256`（8.0.48）；`SnsGuard.kt:195-199`（开关态决定 `sqlBlockEnabled`）；`SnsWriteProbe.kt:315-316` |

### Q1 附加：为什么「两个独立探针一致」反而不能当证死
`BLOCK`（`SnsBlockProbe` / `SnsBitLogic.sqlClear`）与 `WP`（`SnsWriteProbe.parseClear9`）**挂载点相同**（都在 `execSQL` before 的 `noteSql` 链上，`SnsGuard.kt:860-898`、`SnsBlockProbe.kt:560-568`），只是正则不同。二者一致为 0 只能证明「**这条链路上没有 `-9` 形态的 execSQL**」，不能证明：
- 其它通道（native 层 / `SQLiteStatement` / `updateWithOnConflict` 写 `2`）没有等价清位（`docs/_r42_bit_paths.md:83`、`:128`）；
- 三天可见场景下也不会有（`_r49_rc_lifeline_review.md:87` 已标注该证据「未证明 6 条 `& -9` 在三天可见态下也这么滚」`[需真机]`）。

⇒ 交叉印证的价值是**排除 (c)/(d)**（探针彼此独立地漏同一个形态的概率低），**不是**给 R-C 判死。

[DONE] Q1

## Q2 · 阴性对照的正确解读

### 2.1 先把 `& -9` 的语义钉死（题面前提核对）
R42 记载的语义成立，且有多处独立锚点：

| 锚点 | 原文 / 事实 |
|---|---|
| `docs/_r42_bit_paths.md:79` | 「**服务端未返回、本地有 bit3 → 清 bit3**」「`10 → 2`」「**25 条**」 |
| `docs/_r42_bit_paths.md:114` | 「`10 → 2`｜进主页**对账清 bit3**」 |
| `docs/_r42_bit_paths.md:87-88` | 「被清的 25 条只能是『服务端这次没返回』」「**不存在只保留最近 N 条的分页策略**」 |
| `docs/conclusions/moments.md:16-18` | 「只关 B 层再进主页 ⇒ 主页消失、`src 10→2`」「**A 层看不见 SQL 通道的清位**」 |
| `SnsSqlRewrite.kt:8-13` | KDoc：「进好友主页时微信会执行这条裸 SQL 把 bit3 削掉」，WHERE=`userName` + 64 个含 bit3 白名单 + `snsId` 区间、**无 createTime** |
| `docs/_r49_rc_lifeline_review.md:85` | 复核结论：「清位的触发语义**天生就是冲着『服务端没下发的行』去的**」 |

⇒ 语义可复述为：**本地相册位（bit3）是「曾被好友主页相册拉取过」的缓存标记；服务端本次响应里若不再出现该行，微信就把这枚本地标记清掉**。`& -9` 只是这条语义的**一种执行通道**（另一条是 `updateWithOnConflict` 写 `2`，见 `docs/_r42_bit_paths.md:83`、`:128`）。

### 2.2 谁更该触发 `& -9`（二元回答）
| 好友 | 服务端下发情况 | 「未返回但本地有 bit3」的行 | 预期 `& -9` |
|---|---|---|---|
| **veirty（三天可见，实验组）** | **截断**：三天外 SnsObject 不下发（`docs/_r49_rc_stage1_result.md:66-68`） | **多**：阶段一实测三天外 262,154 行、其中 **bit3=60,251 行**（`docs/_r49_rc_stage1_result.md:50-53`） | ✅ **最该触发**（语义的正面命中场景） |
| **Buudaooo（全部可见，阴性对照）** | **全量下发**：本地带 bit3 的行服务端都还在返回 | **≈0**（不存在「这次没返回」的行） | ❌ **本就不该触发** |

⇒ **答案：三天可见好友更该触发；全部可见好友不该触发。** 这与 `& -9` 的 WHERE 形态自洽——它按 `userName` + `sourceType in (64 个全含 bit3 的值)` + `snsId` 区间批量清位（`SnsSqlRewrite.kt:10-13`），本就是「按好友对账」而非「按时间窗」。

### 2.3 阴性对照 0 条：支持还是削弱 R-C（分两层，⛔ 不许含糊）
**第一层 · 对「R-C 可实现 / 证伪」的生死判定 ⇒ 中性，既不支持也不削弱。**
- 它**不支持**「可实现」：本轮根本没观测到任何目标写事件，`hitOlder3dTotal=0`、`matched=0`（`SnsWriteProbe.kt:312-313`、`:418`），没有任何「拦住写 ⇒ 界面显示」的证据（`docs/_r49_rc_probe_verdict.md:468` V-4 的条件一条都没满足）。
- 它也**不削弱 / 更不能判死**：阴性对照**不是 R-C 的适用对象**（`docs/_r49_rc_probe_verdict.md:309` P-11 明确它是「排除探针失效假阴性」的基线，不是判 R-C 的样本）。判死要求「进入/返回/复位**全时序窗口**内 S 上 `onSCnt=0` ∧ 对照都抓到写 ∧ 六钩子齐全」（`docs/_r49_rc_probe_verdict.md:466` V-2、`:283`），本轮连实验组主页都没进，条件缺一大半。
- ⇒ 用 0 条判 R-C 死是**范畴错误**：在「不该触发」的地方没触发，不能推出「该触发的地方也不会触发」。

**第二层 · 对 lane G 翻转生死假设所依赖的那条硬结论 ⇒ 削弱（必须诚实登记）。**
- 翻转的核心前提是「进好友主页**每进一次**执行 6 条 `& -9`、WHERE 无时间窗 ⇒ 三天外行上必有写事件」（`docs/_r49_rc_lifeline_review.md:11`、`:128`、`:150`；`docs/conclusions/moments.md:24-25`）。本轮反例证明这条前提是**条件触发**而非无条件：在全部可见 + 无删除 + 普通浏览下，`& -9` 为 0，连 A 层都 `hit=0 blocked=0`。
- ⇒ 「6 条必发」须降级为「**在服务端未返回本地行时按好友对账批量发一组（约 6/8 条）**」（条数口径差异见 `docs/_r49_rc_lifeline_review.md:26`、`docs/_r42_bit_paths.md:211/217`）。
- ⛔ 但这个削弱**不把 R-C 打回「A 空转证伪」档**：因为「服务端未返回」正是三天可见的定义，条件在实验组里**恰好成立**（`docs/_r49_rc_lifeline_review.md:85`、`:173`）。降级后的因果链仍然是「服务端不下发 ⇒ 触发本地清位写 ⇒ before 不空转」，只是把「必发」改成「在触发条件下发」。

### 2.4 一句话裁定
> **阴性对照 0 条是「预期」，不是「反例」**：它把「每进主页必发 6 条」降级为条件触发，**削弱的是硬结论的普适性**，而**不削弱 R-C 的可行性、更不构成证伪**；R-C 的生死仍完全悬在「veirty 主页里到底发不发、命中不命中三天外行」上（Q3）。`[需真机]`

[DONE] Q2

## Q3 · 判据是否仍缺决定性观测 / 最小下一步 / 是否必须进 veirty

### 3.1 直接回答（三问三答）
| 问 | 答 |
|---|---|
| **判据是否仍缺决定性观测？** | **是，缺且缺得很多。** 本轮只证明了「在全部可见好友的普通浏览下，目标清位写未被观测到」，这连 R-C 的触发条件都没碰到。 |
| **现有数据已足够判死吗？** | **否，远远不够。** 见 3.2 的 7 条缺口；按 `docs/_r49_rc_probe_verdict.md:466`（V-2）、`:283` 的证死条件，本轮缺一大半。 |
| **必须进 veirty（三天可见）主页吗？** | **必须。** 这是唯一能让「服务端截断 ⇒ 本地有未返回的 bit3 行」成立的实验组（`docs/_r49_rc_stage1_result.md:66-68`、`:78-83`）；`docs/_r49_rc_probe_verdict.md:435`（S3 Step 3）也把「进入『昵称A』主页 → 逐屏翻页」列为阶段二决定性判据的唯一触发动作。 |

### 3.2 现有数据缺失清单（逐条，⛔ 不猜）
1. **触发条件未成立**：观测对象是阴性对照 Buudaooo（`docs/_r49_rc_probe_verdict.md:309`），不是实验组；「服务端未返回」这一 `& -9` 的唯一触发语义（`docs/_r42_bit_paths.md:79`）在全部可见下不成立。
2. **⛔ 阳性对照（探针自证）未做**：P-10（请实验组删一条动态，`docs/_r49_rc_probe_verdict.md:308`）没跑 ⇒ 无法排除「探针挂点/判据本身失效」的假阴性；`docs/_r49_rc_probe_verdict.md:469`（V-5）明定「抓不到写 ⇒ 证据不足，不得记成没有写事件」。本轮两个探针一致为 0，恰恰因为没有阳性对照锚，不能自证有效。
3. **无逐条 WHERE 结构化展开**：现成 `stat()` 只给最热**一条**区间（`SnsBlockProbe.kt:609-621`）；阶段二要求的 6 条 `userName`/`in(...)`/`snsId gt/lt` 逐条展开（`docs/_r49_rc_lifeline_review.md:221`）本轮未产出。
4. **无行级命中**：`hitOlder3dTotal=0`（`SnsWriteProbe.kt:418`）只是「账本里没有 `& -9` 事件可查」的副产物，不是「区间 ∩ S_A = ∅」的实测；`execSQL` 是 void、`changes()` 不可观测（`SnsSqlRewrite.kt:17-19`），只能靠 `fillHits` 的只读 count（`SnsWriteProbe.kt:456-480`）判定。
5. **无「拦住写 ⇒ 界面显示」**：P-14 翻页 `older3dShown` / `firstScreenHasOlder`（`docs/_r49_rc_probe_verdict.md:312`）完全没测；而这是 P1.6、V-4 判「可实现」的必要条件（`:468`）。
6. **无三档边界**：返回（切页/下拉/后台同步/冷启）与复位（关两开关/关探针/改回全可见/清缓存）逐档观测缺失（`docs/_r49_rc_probe_verdict.md:455-457`）。
7. **⭐ 样本有效性未复核（本 lane 独立发现）**：`docs/conclusions/moments.md:270-275` 明载 R42 的昵称 **verity**（wxid `wxid_xlrbelf6hv0w22`）名下 3 条动态清一色 `type=2 src=0`「**死来源，本来就不进时间线**」，并立规矩「验证朋友圈可见性必须先用 `snsg sub=dump` 确认 `src & 2 != 0`」。本轮实验组名为 **veirty**（题面），与 R42 的 verity 是否为同一人/同一 wxid **未坐实** `[推断][需真机]`。若 veirty 的三天外行 `sourceType` 多为 `2/0`（不在 64 值含 bit3 白名单，`SnsSqlRewrite.kt:11`、`docs/未完成项与计划.md:324` 转引自 `docs/_r49_rc_lifeline_review.md:46`），则**即使进主页也可能 0 命中且不能判死**（R2 G2，`docs/_r49_rc_lifeline_review.md:63`）。⇒ 进主页前必须补一条按 `<wxidA>` 的只读 `op=sql`：`select sourceType,count(*) from SnsInfo where userName='<wxidA>' and createTime < <T0> group by sourceType`（`docs/_r49_rc_lifeline_review.md:97`、`:274`）。

### 3.3 最小下一步（按优先级，不扩面）
| 步 | 动作（全只读观测，本 lane 不执行） | 产出 | 为什么是「最小」 |
|---|---|---|---|
| **N0** | `op=kvget` 回读开关态；`op=sql` 补 veirty 的 `S_A` 行数 / `snsId` 范围 / `sourceType` 分布（上 3.2 第 7 条）；`op=snsg sub=stat` 确认 `snsDb=true` | T 按人重算 + 白名单归属 | 排除「S_A 本就不在命中集合」导致的假 0（`docs/_r49_rc_lifeline_review.md:96-98`、`:274`） |
| **N1 ⭐** | `sub=reset` → 保持 `arm` → 进 **veirty** 主页首屏 → **逐屏翻页** → `sub=stat` + `sub=dump` + `op=snsg sub=blockstat` + `dev.py q 'BLOCK w2 sql-clear9'` | `matched / clear9 / rewritten / hitOlder3dTotal`、6 条 WHERE 原文、两个独立判据是否分歧 | 判「证伪」的最小充分观测（配合 N2 对照）；也是判「可实现」的入口 |
| **N2** | P-10 阳性对照：请 veirty 删一条动态后再进主页，看账本是否抓到 `want=0` / `& -9` / `blkOff` | 探针有效性自证 | 唯一能排除「探针失效假阴性」的锚（`docs/_r49_rc_probe_verdict.md:308`、`:469`） |
| **N3** | `dev.py ui <三天外动态文本片段>` 翻页条数，首屏与翻页分离 | `older3dShown` / `firstScreenHasOlder` | 判「可实现」必需（P1.6，`docs/_r49_rc_probe_verdict.md:312`、`:468`）；首屏空不得当失败（`:470`） |

⇒ **最少还要观测：N1（veirty 主页全时序 `& -9` 有无 + 行级命中）+ N2（阳性对照自证）**；若要判「可实现」再加 N3。

### 3.4 ⭐ 二元触发条件（可复算，⛔ 不引入主观权衡）
> 记 `S_A` = veirty 的三天外行集合；`win` = 进入/逐屏翻页/返回/复位**全时序窗口**；`selfOK` = 阳性对照抓到写 ∧ 阴性对照正常 ∧ 六钩子齐全（`SnsGuard.kt:186-188` 转引 `docs/_r49_rc_probe_verdict.md:298`）∧ 投递自证通过（`docs/_r49_rc_probe_verdict.md:330`）。

- **判「R-C 可实现」** = 「N0 中 `S_A` 非空且 `sourceType ∈ 64 值白名单`」 ∧ 「N1 `win` 内 `matched>0`（或 `clear9>0`/`rewritten>0`）」 ∧ 「`hitOlder3dTotal>0`（行级命中三天外行）」 ∧ 「`selfOK`」 ∧ 「N3 翻页 `older3dShown>0`（**首屏允许为空**）」 ∧ 「三档边界可复现」。锚点 `docs/_r49_rc_probe_verdict.md:468`（V-4）、`:284`。
- **判「R-C 证伪（证死·强证据）」** = 「N0 `S_A` 非空且白名单归属成立」 ∧ 「N1 `win` 内 `matched=0 ∧ clear9=0 ∧ rewritten=0 ∧ BLOCK sqlClear9=0`」 ∧ 「`hitOlder3dTotal=0`（6 条 `snsId` 区间 ∩ `S_A` = ∅）」 ∧ 「`selfOK`」 ∧ 「Q1 的 (c)/(d) 已由源码 + 双判据排除、(a)/(b)/(e) 已由换好友/换删除动作/翻页·冷启逐档排除」。锚点 `docs/_r49_rc_probe_verdict.md:466-467`（V-2/V-3；**理由须按 `docs/_r49_rc_lifeline_review.md:216` 换掉「空转」措辞**）、`:283`。
- **两者皆不满足 ⇒ 「证据不足」**：本轮即落此档（`docs/_r49_rc_probe_verdict.md:285`、`:474`）。

⛔ **特别明确**：「现有数据足够判死」的触发条件**不存在**。0 条 `& -9` 出现在「全部可见 + 无删除 + 普通浏览」这一**不该触发**的条件下，逻辑上等价于「没做实验」；唯一能判死的是「**在 veirty 场景且探针自证有效下的 0 条**」。

[DONE] Q3

## Q4 · `seen=11` vs `ledger size=2` / `verdict()` 三态核对 / 措辞误导

### 4.1 差异是否合理（去重口径）⇒ **合理，且可逐项对上**
| 量 | 源码口径 | 本轮值 |
|---|---|---|
| `seen` | `record()` **每次调用无条件 +1**，且发生在去重之前（`SnsWriteProbe.kt:281-283`）；被 `dedup.add(key)` 判为重复而**丢弃的事件也计数**（`:284-295` 返回 `added=false` 但 `seen` 已自增） | **11** |
| `ledger size` | `stat()` 取 `snapshot().size`（`:306`、`:300`），是 `events` 里**去重后**的条数（`:287` 只有 `dedup.add(key)` 成功才 `events.add(ev)`） | **2** |
| `dedupKeys` | `dedup.size`（`:310`），去重键集合大小 | **2** |
| `dedupKey` | `kind|sql|userName`（`:78-79`），`sql`/`userName` 为 null 时按 `orEmpty()` 取空串 | — |

逐项还原（`sub=dump` 全部 2 条）：
- **UPDATE 通道**：`captureUpdate` 构造 `sql = null`（`:364`）；`where=snsId=?` 使 `parseSnsId` 抠不出字面量（`SNSID_EQ` 只认 `snsId=\d+`，`:104`、`:179-186`）⇒ `snsIds=-`；`parseUserName(where)` 也取不到 ⇒ `userName=-`（`:370`）。⇒ 所有 `updateWithOnConflict` 事件的 `dedupKey` 恒为 **`UPDATE||`**（`kind=UPDATE` + `sql` 空 + `userName` 空）⇒ **10 次调用折叠成 1 条**，恰与 `BLOCK upd seen=10`（`SnsBlockProbe.kt:738`）吻合 `[推断]`。
- **EXECSQL 通道**：`CREATE TABLE IF NOT EXISTS StarSnsInfo (...)` 因 `captureExecSql` 的 `sql.contains("SnsInfo", ignoreCase=true)`（`:383`）**子串误命中**（表名 `StarSnsInfo` 含 `SnsInfo`）被记 1 次；`parseClear9` 因不以 `update` 开头直接返回 null（`:120-123`）⇒ 不贡献 `matched`。去重后 1 条。

⇒ **11 = 10（UPDATE 事件）+ 1（EXECSQL 事件）**；**2 = 1（UPDATE 折叠）+ 1（CREATE TABLE）**。差异是「**事件次数 vs 去重后唯一键数**」的口径差异，不是数据丢失。

**两点诚实标注 `[推断]`**：
1. `BLOCK w2 sqlSns=7`（`SnsBlockProbe.kt:568`、`:741-746`）是 `SnsBlockProbe` 的**进程级全局累计**（`docs/_r49_rc_probe_verdict.md:205`、`:137`；`SnsBlockProbe.kt:28` 纯内存、进程死才清零），**不随 WP `sub=reset` 清零**（`SnsWriteProbe.kt:330-338` 的 `reset()` 只清 WP 自己的 `events/dedup/seen/matched`）⇒ 与 WP 窗口内 `execSql=1` **不同源、不必逐一对齐**，其中 6 条可能是 `arm` 之前（冷启初始化）累计。
2. `seen` **不是纯「SnsInfo 表写事件数」**：`CREATE TABLE StarSnsInfo` 是子串误命中噪声；`table` 字段被硬编码成 `"SnsInfo"`（`:392`）也会掩盖真实表名。⇒ `seen>0` 只能读作「有若干次写方法回调经过探针」，不能读作「有 11 次目标表写」。

### 4.2 `verdict()` 三态实现核对（`SnsWriteProbe.kt:249-258`）
```kotlin
fun verdict(seen: Long, matched: Long): String =
    if (seen == 0L) "无观测机会(seen=0)"
    else if (matched == 0L) "挂对了但判据不识别(seen>0,matched=0)"
    else "有真实写事件(matched>0)"
```
- `if / else if / else` **严格互斥三态**，单测锁住 `seen==0` 优先（不被 `matched>0` 拉到第三态）：`docs/_r49_rc_impl_review.md:131`、`SnsWriteProbeTest.kt:338-343`。
- `matched` 口径：`record()` 里 `parseClear9(ev.sql) != null` 才 +1（`:283`）。⚠️ **UPDATE 通道 `sql=null` 永不贡献 `matched`**——`docs/_r49_rc_impl_review.md:136` 已登记此口径风险：「若某窗口只发生 `updateWithOnConflict` 而无目标 `execSQL`，`seen>0 && matched=0` 会显示『判据不识别』，需结合 `execSql=` 判读」。
- ⇒ 三态在**实现层面没错**（互斥、优先级正确），问题在**语义映射过窄**。

### 4.3 措辞在此场景下是否会误导 ⇒ **会，且误导方向很危险**
第二态把 `seen>0 && matched=0` **唯一映射**成「挂对了但判据不识别」，隐含前提是「**目标 SQL 已经来了，只是 `parseClear9` 没认出来**」。但本轮证据指向的是**另一种情况**：

| 判据 | 本轮实测 | 含义 |
|---|---|---|
| `dump` 全部 2 条 | `CREATE TABLE ... StarSnsInfo` + 全字段 `cvSourceType=10` UPDATE | 账本里**没有任何 `UPDATE ... SET sourceType = sourceType & -9` 形态** |
| `clear9=0 rewritten=0`（`:313`、`SnsWriteProbeCore.stat()` `:307-308`） | 账本中 `parseClear9 != null` 的条数 = 0、其中 `rewritten=true` 的条数 = 0 | **连 `-1`（改写后）都没出现** |
| `BLOCK w2 sqlClear9=0 sqlClear=0`（`SnsBlockProbe.kt:573-574`、`:741-746`） | 独立判据（`SnsBitLogic.sqlClear` 只认原文 `-9`，`SnsBitLogic.kt:186-188`）也为 0 | 原文不是 `-9` 形态，Q1(c)/(d) 被排除 |

⇒ 真实读数应是 **(ii)「目标清位 SQL 本窗口未发生 / 未进这条链路」**，而不是 (i)「判据漏识别」。措辞的直接危害：
1. 把主会话/复核者引向**错误的下一步**——去改 `parseClear9` 正则或怀疑 `-1` 兜底（而 Q1(d) 已由源码否定、Q1(c) 已被双判据排除），**掩盖了「该换到 veirty 主页」这个唯一正确动作**（Q3/N1）。
2. `seen` 里混了 `StarSnsInfo` 子串噪声（4.1）⇒ 连「挂对了」这个断言都偏强：钩子确实回调了，但回调里没有目标 SQL。
3. 与 `stat()` 自己输出的 `clear9=0 rewritten=0 execSql=1`（`:313`）**自相矛盾**：账本明明能自证「没有目标 SQL」，`verdict` 却说「判据不识别」。

### 4.4 是否需要改措辞 ⇒ **建议改**（仅判据建议，⛔ 本 lane 不改代码、不指挥主会话改）
- **最小建议**：把第二态改为中性描述，例如「观测到 $seen 条写事件但未识别到目标清位 SQL（**须查 `dump` 区分『判据漏识别』与『目标 SQL 未发生』**）」，去掉「判据不识别」的定向归因。
- **更可判的建议**：复用 `stat()` 已有的 `clear9`/`rewritten`/`execSql`（`:313`）把第二态细分为两个可分辨子态：
  - `matched=0 ∧ clear9=0 ∧ rewritten=0` ⇒ 「**目标清位 SQL 未观测到**（本窗口无 `& -9`/`& -1`）」← 本轮应落此态；
  - `matched=0 ∧ (clear9>0 ∨ rewritten>0 ∨ dump 里有未识别的清位形态)` ⇒ 「**判据漏识别 / SQL 形态变异**」；
  - `matched>0` ⇒ 有真实写事件。
- **根因层建议（不作为本轮改动要求）**：`captureExecSql` 的 `contains("SnsInfo")`（`:383`）宜改为表名精确匹配，避免 `StarSnsInfo` 等子串把噪声计入 `seen`；并让 `seen` 按 `kind` 分桶输出，使「有回调」与「有目标 SQL」不再混为一谈。
- ⛔ **边界声明**：以上均为「措辞/判据建议」，本 lane 为只读推理，**不修改 `SnsWriteProbe.kt`、不修改任何配置、不指挥主会话改代码**；主会话是否采纳由用户决定。当前 `verdict()` 逻辑本身（三态互斥、优先级）无实现缺陷，故**不影响本轮数据的可用性**——只是解读时必须结合 `stat()` 的 `clear9/rewritten/execSql` 与 `dump` 明细，⛔ 不得单看 `verdict` 一行下结论。

[DONE] Q4

## Q5 · `sourceType=10` 的 UPDATE 归因

### 5.1 复述被判对象（`sub=dump` 第 2 条，原样）
```
ts=1794155713443 kind=UPDATE table=SnsInfo sql=- cvKeys=subType,localPrivate,pravited,localFlag,sourceType,withTa,attrBuf,postBuf,serverExtFlag,userName,stringSeq,head,type,snsId,withTaHasOther,content,likeFlag,createTime cvSourceType=10 where=snsId=? snsIds=- userName=-
```
形态要点：`kind=UPDATE`（`captureUpdate` 通道，`SnsWriteProbe.kt:354-374`）、`sql=null`（该通道本就无 SQL，`:364`）、**18 个键的全套 ContentValues**、`cvSourceType=10`、`where=snsId=?` 单行定位。

### 5.2 裁定：**微信正常落库**（不是模块自己写的保位，也不是 R42 A 层写回）
逐条给源码/文档锚点：

| # | 判据 | 锚点 |
|---|---|---|
| 1 | **探针读的是微信原始入参，早于 A 层任何改写**。`snsBlockGuard.beforeHookedMethod` 里 `if (SnsWriteProbe.isArmed()) { captureUpdate(param.args) }` 在**最前面**（`:302-305`），其后才是 `:306` `runCatching { onSnsUpdateBefore(param) }`；复核明确「探针短路插入在 before 最前面，原 `onSnsUpdateBefore` 逐字保留」 | `SnsGuard.kt:297-308`；`docs/_r49_rc_impl_review.md:144`、`:147`（探针「只读 `param.args`，不写回」） |
| 2 | **A 层保位只改一个键的值，不会新增 18 个键**：`keep = old \| (2\|8)`（即 `old \| 10`），动作是 `api.putInt!!.invoke(values, "sourceType", keep)` —— 一次 `put`，且 `docs/conclusions/moments.md:7-8`、`:17-18` 明定 A 层只在 `sourceType==0` / 纯降位（`10→8`、`10→2`）时触发；`onSnsUpdateBefore` 读到没碰 `sourceType` 就「只计数」早退 | `SnsGuard.kt:459`、`:474`、`:550-556`；`SnsGuard.kt:363-373`；`docs/conclusions/moments.md:7-9`、`:16-19` |
| 3 | **`10` 是加位后的满值，不是清位值**：清位/降位值是 `8 / 2 / 0`（`docs/_r42_bit_paths.md:9`、`:79`）；`10 = bit1(2) + bit3(8)` 是「时间线 + 好友主页相册同时可见」。R42 实测「22:00:26 进主页 `2→10` ✅ **加位放行**（不误拦）」、moments 明写「纯文字动态进主页后**同样会从 `2` 变 `10`**」「bit3 只取决于是否被好友主页相册列表拉取过」 | `docs/_r42_bit_paths.md:208`、`:9`；`docs/conclusions/moments.md:11-12`、`:21-22`；`docs/_r49_rc_stage1_result.md:52`（最新 3 条 `sourceType` 有 10/2） |
| 4 | **全套 18 键 = 一条完整 SnsObject 落库**：与 `SnsInfo` 列清单一致，`where=snsId=?` 单行定位，是典型 `updateWithOnConflict("SnsInfo", cv, "snsId=?", …)` 的 replace/新增；`& -9` 对账清位则是 `UPDATE ... SET sourceType = sourceType & -9 WHERE userName=… AND sourceType in (64 值) AND snsId > A` 的裸 SQL（`execSQL` 通道），二者形态完全不同 | `docs/conclusions/moments.md:205`；`SnsSqlRewrite.kt:10-13`；`SnsGuard.kt:34-36`（微信真实通道是 `updateWithOnConflict`） |
| 5 | **`BLOCK` 计数反证模块未介入**：`BLOCK stat hit=0 blocked=0 nonZero=10 skipOff=0`、`BLOCK drop seen=4 blocked=0 allow=4` ⇒ A 层**零拦截**、10 次非零 `sourceType` 写全部放行。若这是模块保位写回，应出现 `blocked>0` 或 `afterShape` 的 `rw`（`shapeRewritten`） | `SnsBlockProbe.kt:728`、`:732`、`:734-736`；`SnsGuard.kt:743-749`（after 半场读回 keep 的判据） |

### 5.3 ⭐ 为什么 `keep = old | 10` 这个数值巧合**不能**当归因
题面点名的 `keep = old | 10` 在源码里是 `keep = old | (2|8)`（`SnsGuard.kt:459`，`2|8 = 10`），数值上确实等于本轮 `cvSourceType=10`。但：
- 它是 **A 层在 `onSnsUpdateBefore` 里 `put` 回 ContentValues 的「改写后」值**（`SnsGuard.kt:550-556`），而探针在 `before` **最前面**读参数（第 1 条），**看不到 put 之后的值**；
- A 层触发条件是「`sourceType==0` / 降位写」，本轮是「全字段正常落库 + `10` 满值」，条件相反；
- `BLOCK blocked=0` 证明 A 层这次根本没动手（第 5 条）。

⇒ **「数值相同」≠「同一个写」**；归因必须看「谁在哪个半场、写了几个键」，不能看值。

### 5.4 残留不确定与下一步观测建议（⛔ 只读判据，不改代码）
- `[推断]` 不能 100% 排除「微信把此前（含 `arm` 之前）已被模块写回的库值读出来、原样塞进本次 ContentValues」；但 `captureUpdate` 读的是**本次入参**、与历史库值无关，且 `blocked=0` ⇒ 可能性低。
- **下一步观测建议（用于彻底坐实，非本轮结论前提）**：
  1. 在 Q6 的「关两个 R42 开关」对照组里，若这条全字段 `cvSourceType=10` UPDATE **仍出现** ⇒ 证明它与模块开关/A 层无关，确为微信自写；
  2. 看 `op=snsg sub=blockstat` 的 `shape=rw:` 计数与最近 10 个 `BLOCK ev` Event 行（`SnsBlockProbe.kt:732`、`:747-749`）：若 `rw=0` 且 `Event` 无 `want=0/8/2` 拦截记录，进一步排除 A 层保位；
  3. `BLOCK upd seen=10 zero=0 nosrc=0`（`:738`）说明 10 次 UPDATE 全带 `sourceType` 键、无 `0` 值写 ⇒ 与「浏览相册时微信把动态标成 `2→10`」一致。

[DONE] Q5

## Q6 · 反向对照设计（关两个 R42 开关能区分 Q1 哪几条）

### 6.1 硬结论的源码坐实 + ⛔ 关开关 ≠ 关探针
```
SnsGuard.kt:195   val block = Kv.getBool(Keys.ANTIREVOKE_SNS) || Kv.getBool(Keys.ANTIREVOKE_SNS_BLOCK)
SnsGuard.kt:199   sqlBlockEnabled = block && !Kv.getBool(Keys.ANTIREVOKE_SNS_SQLBLOCK_OFF)
```
- `block` 是 **OR**（`:195`）⇒ ⛔ **只关一个无效**：`SnsBlockProbe.switch()` 只切 `ANTIREVOKE_SNS_BLOCK`（`SnsBlockProbe.kt:711-720`、`:717`），只要 `antirevoke.sns` 仍 true，`block` 仍 true ⇒ 改写照旧（`docs/_r49_rc_lifeline_review.md:135`）。「两个都关」成立（`docs/_r49_rc_probe_verdict.md:126`、`docs/_r49_rc_lifeline_review.md:136`）。
- 两个都 false ⇒ `block=false` ⇒ `sqlBlockEnabled=false` ⇒ `onSnsSql` 落 **`SqlBlock.OFF` 原样执行**（`SnsGuard.kt:888`；`blkOff` 计数 `SnsBlockProbe.kt:581-584`），同时 A 层摘键/`keep` 写回停（`docs/_r49_rc_probe_verdict.md:126`；`SnsGuard.kt:516`「开关关闭 ⇒ 只记账，绝不写任何字节」）。

⛔ **本设问的陷阱，先分开两件事**：
| | 关两个 R42 开关 | 关探针 |
|---|---|---|
| 键 | `antirevoke.sns` + `antirevoke.sns_block` | `sns.write_probe`（**第三个独立开关**，`SnsWriteProbe.kt:22-24`、`:344`） |
| 入口 | `op=kvset`（`EntryReceiver.kt:176-183`，参数名 `key`/`val`），触发 `SnsGuard.refresh()` 热生效 | `op=writeprobe sub=disarm`（`SnsWriteProbe.kt:436-439`） |
| 写哪里 | **模块配置库**（非微信库） | 模块自有 KV + 进程内 `armed` |
| 效果 | 停 `-9→-1` 改写 + 停 A 层保位；**微信 SQL 原样执行** | 账本停止增长、明细保留（`:316`、`:439`） |
| 耦合 | **零耦合**：`arm/disarm` 不调 `SnsGuard.refresh()`、不碰任何 `ANTIREVOKE_*` key（`docs/_r49_rc_impl_review.md:147`、`:173`） | 同左 |

⇒ **对照组必须保持 `sns.write_probe = arm`**。若误把「关探针」当对照组，两处 `if (SnsWriteProbe.isArmed())` 短路（`SnsGuard.kt:302`、`SnsBlockProbe.kt:564`）会让 `seen` 恒 0、`verdict` 落第一态「无观测机会」（`SnsWriteProbe.kt:255-256`），得到的是「探针没记」而不是「没写事件」——与 `docs/_r49_rc_impl_review.md:131-134` 登记的口径风险完全一致。

### 6.2 关两个开关「改变什么 / 不改变什么」（判据设计的前提）
- ✅ **改变**：① 不再 `-9→-1` 改写（`OFF` 原样执行，库里 bit3 会被微信真清，`blkOff` 可见）；② A 层摘键/`keep` 写回停；③ `BLOCK w2 sql-clear9` 的 tag 从「已拦」变「**未拦·开关关**」（`SnsBlockProbe.kt:581-584`），`BLOCK` 侧拿到的仍是**微信原文**（`SnsBlockProbe.kt:591`「原文仍是微信原本要执行的那条」）⇒ 原文形态最干净。
- ⛔ **不改变**：**「微信是否发出 `& -9` SQL」**。改写只发生在 `execSQL` 的 before、只改 `param.args[0]` 的字符串常量，**不阻止执行、WHERE 一字节不碰、命中集合与 `changes()` 全同**（`SnsSqlRewrite.kt:16-21`、`SnsGuard.kt:62`、`docs/_r49_rc_lifeline_review.md:128`、`:150`）。⇒ 关开关**不产生也不消灭写事件**；关后若仍 0 条，**绝不能**说「关开关把 SQL 关掉了」。
- ⚠️ 副作用：关后 `& -9` 原样执行会真改微信库（bit3 被清），而**库位是磁盘态、不随开关回滚**（Q7）。⇒ S6 必须恢复开关，且复位档要单独记录库位。

### 6.3 具体步骤（⛔ 本 lane 只读推理，不执行；`kvset` 交主会话/操作员，写的是模块配置库）
| 步 | 动作 | 观测 / 自证 |
|---|---|---|
| S0 | `op=kvget` 回读 `antirevoke.sns` / `antirevoke.sns_block`（本轮均 true）；`op=snsg sub=stat` 记 `blockEnabled/sqlBlock/albumFix` | 开关快照（`docs/_r49_rc_probe_verdict.md:128`、`:341`） |
| S1 | **保持探针 armed**：`op=writeprobe sub=stat` 确认 `memArmed=true`；`sub=reset` 清账本 | 防止「关了探针」误读（6.1） |
| S2 | 关两个开关：`op=kvset --es key antirevoke.sns --es val 0` 与 `--es key antirevoke.sns_block --es val 0`；`op=kvget` 回读 false；`op=snsg sub=stat` 确认 `blockEnabled=false sqlBlock=false` | 自变量唯一：`docs/_r49_rc_probe_verdict.md:225`（A1 互斥归因） |
| S3 | 微信前台 → 进 **veirty（三天可见）** 主页首屏 → **逐屏翻页**（⛔ 不能用 Buudaooo，Q2/Q3）→ `sub=stat`/`sub=dump` + `op=snsg sub=blockstat` + `dev.py q 'BLOCK w2 sql-clear9'` | 原文最干净窗口下的双判据 |
| S4 | P-10 阳性对照：请 veirty **删一条动态** → 再进主页 → 重跑 S3 观测（`docs/_r49_rc_probe_verdict.md:308`） | 引入「删除动作」自变量，区分 (b) |
| S5 | 逐档：下拉刷新 / 后台同步 / 冷启再进，每档前 `op=logclear` + `sub=reset`（`docs/_r49_rc_probe_verdict.md:456`） | 引入「时序」自变量，区分 (e) |
| S6 | **恢复两个开关为 true**，`op=kvget` 回读；可选 `sub=disarm` | 复位档：库位不回滚（Q7）、避免持久改现网态 |

⛔ 计数口径提醒：`BLOCK` 的计数是**进程级全局累计**（`SnsBlockProbe.kt:28`、`:568`；`docs/_r49_rc_probe_verdict.md:205`），**不随 WP `sub=reset` 清零** ⇒ 报告须记「前后差值」而非绝对值（Q4 §4.1 标注 1）。

### 6.4 各解释下分别看到什么（Q1 (a)~(e) 逐条）
| 解释 | 关两开关组的预期观测 | 能否区分 | 机理 |
|---|---|---|---|
| **(a)** 触发条件型 | 进 **veirty** 出现 `& -9`（WP `matched>0`、`clear9>0`、`rewritten=0`；`BLOCK sqlClear9>0`、`blkOff>0`、tag=「未拦·开关关」原文可查）；Buudaooo 侧关前关后**恒 0** | ✅ **强区分** | 关开关去掉改写后，`BLOCK` 原文与 WP 账本在同一条 `-9` 上对齐；「换好友才出现」正是 (a) 的独有签名（Q1 表） |
| **(b)** 删除流程 / 方案 B 路径 | S3 仍 0 条，直到 **S4 删动态 / 再进主页** 才出现；或方案 B 消融（`antirevoke.sns_sqlblock_off=true`，`docs/_r42_bit_paths.md:219-222`）才出现 | ✅ 可区分（需配 S4） | (a) 换好友即出现、(b) 换好友不出现但换动作出现 ⇒ 一次只动一个自变量即可分开（A1 互斥归因） |
| **(c)** `parseClear9` 漏识别 | **两个判据分歧**：`BLOCK sqlClear9>0` / `blkOff>0` / `BLOCK w2 sql-clear9` 打出 `-9` 原文 / `BLOCK w2 sql-unmatched` 有未识别原文，但 WP `matched=0`；若两判据**同为 0** ⇒ 排除 (c) | ✅ **判别力最强** | 关开关后无改写，原文形态最干净：`BLOCK`（只认 `-9`，`SnsBitLogic.kt:188`）与 WP（认 `-9\|-1`，`SnsWriteProbe.kt:98-101`）在同一原文上比对，谁漏一目了然 |
| **(d)** 改写发生在捕获之前 | 若 (d) 成立，关前 WP 应见 `rewritten>0`（`-1` 兜底命中）、关后 `rewritten=0`；**实测关前 `rewritten=0` 且 `BLOCK sqlClear9=0`** ⇒ 已被排除（`docs/_r49_rc_impl_review.md:101-123` C1 PASS） | ✅ **决定性反证** | 关开关后**根本没有改写**，`& -9` 原样执行；此时若仍 `matched=0` 且 `BLOCK sqlClear9=0`，「以前记到的是 `-1`」的假设不成立 |
| **(e)** 版本 / 翻页时序 | S3 首屏 0 条，但 **S5 翻页/下拉/冷启后**出现；或 `BLOCK w2 sql-unmatched` 出现新形态（常量/绑定参数变体）；`op=kvget` 回读开关态与 R42 取证态有差异 | ⚠️ 部分（需配 S5 + 版本回读） | 关开关只解除改写，**不制造触发条件、不改变版本**；时序依赖要靠逐档观测，版本差异要靠 `op=kvget`/版本回读，与关开关无直接因果 |

### 6.5 区分力小结（不夸大）
- **单独可区分**：(c)（双判据对齐）、(d)（无改写的反证）、(a)（换好友后原文最干净）。
- **需配另一个自变量才可区分**：(b)（+删动态动作）、(e)（+逐档时序 / 版本回读）。
- ⛔ **关开关组单独不能**在 (a)/(b)/(e) 之间裁定「是谁让 SQL 出现」——必须一次只动一个自变量（换好友 / 换动作 / 换时序），否则三者混淆（`docs/_r49_rc_probe_verdict.md:225` A1 互斥归因）。
- ⛔ **关开关组不能替代「进 veirty 主页」**（Q3）：关开关只解除 `-9→-1` 改写，**不制造「服务端未返回」这个触发条件**；在 Buudaooo 上关开关仍会 0 条，且这 0 条仍不能判 R-C 死。
- ⛔ **关开关组不能**排除 Q8 的盲区（native 层写库 / 服务端响应组装 / UI 显示）：它只动了 Java `execSQL` before 的改写与 A 层。

[DONE] Q6

## Q7 · 三档边界（逐档写，⛔ 复位档不写「同返回」）

> 依据：`docs/_r49_rc_probe_verdict.md:451-459`（8.5 三档表）、`docs/_r49_rc_lifeline_review.md:289-297`（7.3 复位档四条）、`SnsWriteProbe.kt:314-316`（`stat()` 三档提示）、`docs/_r49_rc_impl_review.md:228-240`（C9 复核 PASS，含「⛔ 复位≠返回」逐字核对）。

### ① 进入（进 veirty 主页首屏）
| 项 | 内容 |
|---|---|
| 定义 | 实验组「veirty（三天可见）」**首次进主页首屏**；`arm` 之后、`sub=reset` 清零后的干净窗口 |
| 该跑 | N0 的 `op=sql`（S_A 行数 / `snsId` 范围 / `sourceType` 分布，Q3 §3.2 第 7 条）、`sub=stat`/`sub=dump`、`op=snsg sub=blockstat`、`dev.py q 'BLOCK w2 sql-clear9'`、P-14 首屏 `firstScreenHasOlder`、`op=snsg sub=stat` 确认 `snsDb=true` |
| 预期（**条件成立时**） | 「服务端未返回、本地有 bit3」成立 ⇒ 一组 `& -9` 按 `snsId` 区间分页（约 6/8 条，`docs/_r42_bit_paths.md:187`、`:211`）出现在 before；WP `matched/clear9` 增长、`fillHits` 出 `hitOlder3dTotal`（`SnsWriteProbe.kt:418`）；**首屏仍只三天内**（首屏由网络响应重建，`docs/_r49_rc_probe_verdict.md:455`、`docs/_r42_wechatmagician.md:40-41` 转引） |
| ⛔ 硬纪律 | 进主页前后 `op=sql` **库快照差分必然假阴性**（`-9→-1` no-op 改写，`SnsWriteProbe.kt:314`、`docs/_r49_rc_probe_verdict.md:90`、`:112`）⇒ **只看 before 原文账本**，不看库值变化 |
| 状态 | 模块钩子/账本内存态、`snsDb` 句柄（捕获后卸载 captureHook，`docs/_r49_rc_probe_verdict.md:455` 转引 `SnsGuard.kt:1016-1017`）；`arm` 之前的写事件**不追溯**（`SnsWriteProbe.kt:316`、`docs/_r49_rc_impl_review.md:238`） |

### ② 返回（切页回来 / 下拉刷新 / 后台同步回来 / 冷启）—— 逐子场景写
共同点：**模块仍启用、钩子仍在、库位仍在**，只是内存列表/进程状态变化；账本「继续追加、不重置」（`SnsWriteProbe.kt:315`）。每个子场景前 `op=logclear` + 重跑 `sub=stat`/`sub=dump`/`blockstat`/P-14（`docs/_r49_rc_probe_verdict.md:456`）。

| 子场景 | 机制 / 预期 | 账本与库位 | 锚点 |
|---|---|---|---|
| **切页再进** | 每进一次（触发条件成立时）再发一组清位写；账本追加、不清零 | 库位不变（改写态） | `docs/_r49_rc_probe_verdict.md:456`；`SnsWriteProbe.kt:315` |
| **下拉刷新** | **新响应覆盖内存列表** ⇒ 首屏回三天内；翻页走 `d2.t5` 查表，仍可能带出库里的行 | 内存列表变、**库磁盘态不变** | `docs/conclusions/moments.md:259-261`；`docs/_r49_rc_lifeline_review.md:294` |
| **后台同步回来** | `NetSceneSnsSync`（getType=214）响应**不含三天外 id** | 库位不变 | `docs/_r49_rc_probe_verdict.md:456`（转引 `MomentsSyncTrigger.kt:76`） |
| **冷启** | 钩子 reinstall、`snsDb` 重捕获（记 `SNS sns db captured` 时刻，P-17）；**账本进程内结构全清零（预期、非 bug），须重新 `arm`**；但**库里的位是磁盘态仍在** ⇒ 反而可能比刷新后**更容易翻到** | ⚠️ 账本清零 / 库位仍在，**两者反向** | `docs/_r49_rc_probe_verdict.md:320`、`:456`；`SnsWriteProbe.kt:315`；`docs/_r49_rc_lifeline_review.md:294`；`docs/_r49_rc_impl_review.md:181`、`:193` |

### ③ 复位（关两个 R42 开关 / 关探针 / 对方改回全部可见 / 清缓存）—— ⛔ **复位 ≠ 返回**
定义：**模块能力关闭或外部条件归零**（`docs/_r49_rc_lifeline_review.md:292`、`docs/_r49_rc_probe_verdict.md:292`），与「返回」的「模块仍启用、只是内存列表被覆盖」有本质区别。四个子动作**逐一单独观测**（`docs/_r49_rc_probe_verdict.md:457`、`:297`）：

| 子动作 | 具体机制 | 观测 | 锚点 |
|---|---|---|---|
| **关两个 R42 开关** | `block = ANTIREVOKE_SNS \|\| ANTIREVOKE_SNS_BLOCK`（`SnsGuard.kt:195`）⇒ 两关 ⇒ `block=false` ⇒ B 层改写停、A 层保位停、`& -9` **原样执行真清 bit3**（`blkOff` 增长） | ⛔ **库位发生真实变化且不回滚**；重跑 `op=sql` 读 `sourceType`、`blockstat` 看 `blkOff` | `SnsGuard.kt:195-199`、`:888`；`SnsBlockProbe.kt:581-584`；`docs/_r49_rc_probe_verdict.md:457` |
| **关探针** | `op=writeprobe sub=disarm`：只改 `sns.write_probe` KV + 内存 `armed`，**账本停止增长、明细保留供末次 dump**；⛔ 与关 R42 开关**是两件事**（Q6.1） | 账本停止、`memArmed=false` | `SnsWriteProbe.kt:436-439`、`:316`；`docs/_r49_rc_impl_review.md:147`、`:173` |
| **对方改回全部可见** | 服务端恢复下发 ⇒ 被保位的旧行与真实响应**并存、可能重复/顺序异常** `[推断]`（R31h 先例） | P-15 重复 `snsId`、P-19 残留、P-14 界面 | `docs/_r49_rc_lifeline_review.md:295`；`docs/_r49_rc_probe_verdict.md:457`、`:459` |
| **清缓存** | **唯一可能把「本地行还在」前提整个抹掉**的动作；但微信清缓存对 `SnsInfo` 的实际行为**无取证** `[需真机]` | 重跑 P-01（T）看 S 是否还在 | `docs/_r49_rc_probe_verdict.md:459`；`docs/_r49_rc_lifeline_review.md:295` |

复位档状态残留（**两组量必须分列**）：
- **库位（磁盘）**：已写进微信库的 `sourceType` 保底值 / `[已删除]\n` content 前缀是**持久磁盘状态，不随开关/冷启回滚**；R42 已把 `undoInsert/rollbackStep/restore/show` **整段删除、当前源码无任何回滚路径**（`docs/_r49_rc_lifeline_review.md:293`、`docs/_r49_rc_probe_verdict.md:457` 转引 `docs/_r42_purge_report.md:51-56`；`docs/_r49_rc_impl_review.md:263`）。
- **账本（进程内）**：`disarm` 停增长、`reset` 清零、冷启全清零（`SnsWriteProbe.kt:261-268`、`:315-316`、`:330-338`）。

### 7.4 ⭐ 两组量的差异（题面点名，逐维度对照）
| 维度 | 已写进库的 bit3 / `sourceType`（**磁盘状态**） | 进程内账本（WP / `SnsBlockProbe`） |
|---|---|---|
| 载体 | 微信 `SnsMicroMsg.db` 的 SQLite 文件 | Java `object` 实例字段（`SnsWriteProbe.kt:261-268`；`SnsBlockProbe.kt:28` 纯内存 `AtomicInteger`） |
| 关两个 R42 开关 | **不回滚**；且关后 `& -9` 原样执行还会**继续清位**（`OFF` 分支，`SnsGuard.kt:888`） | **不受影响**（零耦合，`docs/_r49_rc_impl_review.md:147`、`SnsWriteProbe.kt:22-24`） |
| 冷启 | **仍在**（`docs/conclusions/moments.md:263`、`docs/_r49_rc_lifeline_review.md:294`） | **全清零（预期非 bug）**，且 `memArmed` 恒 false ⇒ 必须重新 `arm`（`SnsWriteProbe.kt:315`、`docs/_r49_rc_impl_review.md:181`、`:193`） |
| `sub=disarm` / `sub=reset` | 完全不影响 | `disarm` 停增长保留明细；`reset` 清账本+计数但**不改 `armed`**（`SnsWriteProbe.kt:330-338`、`:429`、`:439`） |
| 回滚路径 | **无**（R42 已删回滚段，`docs/_r42_purge_report.md:51-56`） | 天然可清零，属「内存重建」而非「回滚」 |
| `BLOCK` 计数 | — | 也是进程级全局累计，**不随 WP `reset` 清零**（`docs/_r49_rc_probe_verdict.md:205`）⇒ 跨档比较须用「前后差值」 |

⇒ **判据结论**：复位档必须**单独观测一次** `hitOlder3dTotal` / 界面 / **库位**，四个子动作各自独立记录，⛔ **不得并入返回档、不得写成「同返回」**；报告里「库位（`op=sql` 读 `sourceType`）」与「账本计数（`stat` 的 `seen/matched`）」必须分列，否则会把「账本冷启清零」误读成「库位已回滚」，或把「关开关后 bit3 真被清」误读成「模块撤销了写」。`docs/_r49_rc_probe_verdict.md:459` 列的复位档三易漏点（关开关≠恢复原状 / 改回全可见引入重复源 / 清缓存无取证）在本轮逐条成立。

[DONE] Q7

## Q8 · 最终裁定表（三态映射 + 什么等于证死 + 盲区）

### 8.1 记法（沿用 Q3 §3.4，⛔ 不引入不可复算量）
- `S_A` = veirty 的三天外行集合；`T` = `S_A` 非空 ∧ `content/attrBuf` 完整 ∧ `<T0>` 已校准（`docs/_r49_rc_probe_verdict.md:251-262`）。
- `win` = 进入 / 逐屏翻页 / 返回 / 复位**全时序窗口**（Q7 三档）。
- `selfOK` = P-10 阳性对照抓到写 ∧ P-11 阴性对照正常 ∧ 六钩子齐全（`docs/_r49_rc_probe_verdict.md:298`）∧ 前台/投递自证通过（`:330`）。
- `Q1excl` = Q1 的 (c)/(d) 已由源码 + 双判据排除、(a)/(b)/(e) 已由换好友 / 换删除动作 / 翻页·下拉·冷启逐档排除（Q6）。
- 本轮实测输入 = 「Buudaooo（全部可见）普通浏览：`matched=0 clear9=0 rewritten=0`、`BLOCK sqlClear9=0`、`hitOlder3dTotal=0`、未进 veirty、未做阳性对照」。

### 8.2 ★ 三态判定表（逐条写触发条件）
| 判定 | 触发条件（观测结果组合） | 锚点 |
|---|---|---|
| **R-C 可实现** | `T=true`（且按 `<wxidA>` 复核 `S_A` 的 `sourceType ∈ 64 值含 bit3 白名单`）∧ `win` 内 `matched>0`（或 `clear9>0`/`rewritten>0`）∧ `hitOlder3dTotal>0`（**行级命中三天外行**）∧ `selfOK` ∧ 拦住写后**翻页** `older3dShown>0`（**首屏允许为空**）∧ 三档边界可复现 | `docs/_r49_rc_probe_verdict.md:468`（V-4）、`:284`；`docs/_r49_rc_lifeline_review.md:284`；`docs/_r42_wechatmagician.md:40-41`（P-14 首屏/翻页分离） |
| **R-C 证伪（证死·强证据）** | `T=true` ∧ `win` 内 `matched=0 ∧ clear9=0 ∧ rewritten=0 ∧ BLOCK sqlClear9=0` ∧ `hitOlder3dTotal=0`（6 条 `snsId` 区间 ∩ `S_A` = ∅）∧ `selfOK` ∧ `Q1excl` | `docs/_r49_rc_probe_verdict.md:466-467`（V-2/V-3）、`:283`；⛔ 理由须按 `docs/_r49_rc_lifeline_review.md:216` **换掉「空转」措辞**，改为「写事件行级未落在 S 上」 |
| **R-C 证伪（无米之炊，0 装包）** | `T=false`（0 行 / `clen=0` / `chead=''` / `<T0>` 未校准）⇒ 共性前置不成立，R-A/R-B/R-C 同收口 | `docs/_r49_rc_probe_verdict.md:465`（V-1）、`:266`；`docs/_r49_rc_stage1_result.md:169`、`:271` |
| **证据不足** | 见 8.3 八个子条，任一成立即落此档 | `docs/_r49_rc_probe_verdict.md:285`、`:469-474`、`:287` |

⛔ **绝不越级**：`docs/_r49_rc_lifeline_review.md:189-193`（R4）明定「有写事件 ≠ R-C 可实现」，中间还隔着「覆盖 S」「拦住写 ⇒ 界面显示」两道坎；`docs/_r49_rc_probe_verdict.md:474` 明定「不得误判为证死的三种」（有写没拦到 / 首屏空 / 干扰致数据不可信）一律落证据不足。

### 8.3 「证据不足」八个子条（逐条触发条件）
| # | 触发条件 | 锚点 |
|---|---|---|
| E1 | **本轮现状**：只有 Buudaooo 阴性对照 0 条，未进 veirty、未做阳性对照 ⇒ 触发条件根本没成立 | Q2/Q3；`docs/_r49_rc_probe_verdict.md:309`（P-11 是基线不是判死样本） |
| E2 | `hitOlder3dTotal>0` 但翻页仍 `older3dShown=0`（P1.6 未定） | `docs/_r49_rc_probe_verdict.md:470`（V-6） |
| E3 | 把**首屏空**当失败（已知效果上限，首屏由网络响应重建） | `docs/_r49_rc_probe_verdict.md:470`、`docs/_r49_rc_lifeline_review.md:184` |
| E4 | 阳性对照没抓到写 / `rawMethodCnt>0 但 beforeCnt=0`（挂错类、签名不匹配、钩子未注册） | `docs/_r49_rc_probe_verdict.md:469`（V-5） |
| E5 | P-20~P-26 任一自证失败（前台 / 投递 / `TAG_present` / `snsDb_captured` / `not_stale` / `installed` 缺项）致数据不可信 | `docs/_r49_rc_probe_verdict.md:471`（V-7） |
| E6 | 开关态混淆（Q1(e)）或**把「关探针」误当「关两个 R42 开关」对照组**（Q6.1）⇒ `seen` 恒 0 落第一态 | Q6.1；`SnsWriteProbe.kt:255-256`；`docs/_r49_rc_impl_review.md:131-134` |
| E7 | 日志被 LogX 5000 行环形覆盖 / SQL 原文被 1500 字符截断 / 异步窗口错位 | `docs/_r49_rc_lifeline_review.md:244-248`（R6 漏洞 2/3/4）；`docs/_r49_rc_probe_verdict.md:471` |
| E8 | 任何「必须写库才能闭环」的验证未做（真 before 写回后是否被二次覆盖、补 content 后界面能否渲染） | `docs/_r49_rc_probe_verdict.md:472`（V-8）、`:340`（V6 标红：本轮不实现 R-C、只读替代只给间接证据） |

### 8.4 ⭐ 什么结果等于「R-C 被证死」（单独明确写）
> **不是本轮结果。** 「Buudaooo（全部可见）浏览时 0 条 `& -9`」**不等于**证死（Q1/Q2/Q3）。

**唯一充分组合**（四个条件必须同时成立）：
1. **场景对**：在 **veirty（三天可见）** 主页、`win` 全时序窗口（进入 + 逐屏翻页 + 返回四子场景 + 复位四子动作）内观测——即「服务端截断」这个唯一触发条件已成立；
2. **探针自证有效**：`selfOK`——P-10 阳性对照（删一条动态）**抓到写**、阴性对照正常、六钩子齐全、投递自证通过 ⇒ 排除「探针坏/挂错」的假阴性；
3. **目标写行级为 0**：`matched=0 ∧ clear9=0 ∧ rewritten=0 ∧ BLOCK sqlClear9=0`，且 `hitOlder3dTotal=0`（6 条 `snsId` 区间 ∩ `S_A` = ∅），且翻页 `older3dShown=0`；
4. **替代解释全排除**：`Q1excl`——(c)/(d) 由源码 + 双判据排除（Q1 表），(a)/(b)/(e) 由换好友 / 换删除动作 / 逐档翻页·刷新·冷启排除（Q6）。

一句话：**证死 = 「该触发的场景里，条件触发了、探针也灵，但目标清位写事件行级就是没落在三天外行上、界面也翻不出三天外动态」。**
⛔ 即便如此，也只判「R-C 在**当前客户端 Java DB 层路线**上不可行」，不得外推到 native 层 / 服务端（见 8.5 盲区）。

### 8.5 仍然测不到的盲区（题面点名三条 + 补充五条）
| # | 盲区 | 为什么测不到 | 锚点 |
|---|---|---|---|
| **B1** | **native 层写库** | 探针只挂 Java `SQLiteDatabase.execSQL` / `updateWithOnConflict`（`SnsGuard.kt:152-163`）。微信 8.0.48 用 WCDB，若清位/落库走 native `sqlite3_*` 或 `SQLiteStatement.executeUpdateDelete/executeInsert/execute`，Java before 钩子**完全看不到**；`docs/_r49_rc_probe_verdict.md:158`（P-06）列了这些临时钩子，`docs/_r49_rc_probe_verdict.md:355`（V7 条件性 B6）也只在「真机显示写只在 SQLiteStatement 层」时才补挂 `[需真机]` | `SnsGuard.kt:152-163`；`docs/_r49_rc_probe_verdict.md:158`、`:355`；`docs/_r49_rc_lifeline_review.md:158`（`SnsHuntProbe` 同为 before、LSPosed 回调顺序不确定） |
| **B2** | **服务端响应组装** | 首屏列表由网络响应内存对象渲染：`docs/conclusions/moments.md:259-260`「`w5.onSceneEnd` 全方法**无一次 `d2`（SnsInfo 存储层）调用**」，「`SnsInfo` 只是旁路持久化缓存」；R-C **首屏解决不了**（`docs/_r42_wechatmagician.md:40-41` 转引 `docs/_r49_rc_lifeline_review.md:184`）。探针在客户端 DB 层，看不到「服务端是否下发三天外 id」「响应列表怎么组装」 | `docs/conclusions/moments.md:259-261`；`docs/_r49_rc_lifeline_review.md:184`、`:187`（「真正挡住的是响应列表里没有这些 id」）；`docs/_r49_rc_probe_verdict.md:470` |
| **B3** | **UI 是否显示三天外动态** | `SnsWriteProbe.kt:9-10` 自述「本探针只解决『行级命中』，**界面能否显示留给真机人工判读**」；`dev.py ui` 只给可见条数（P-14），无法把「某条三天外动态」与「某次写事件」做因果关联；且还有 SQL 白名单 → `type ∈ {1,15}` → `isDieItem()` 三门槛 | `SnsWriteProbe.kt:9-10`；`docs/_r49_rc_probe_verdict.md:312`（P-14）；`docs/未完成项与计划.md:329` 转引 `docs/_r49_rc_lifeline_review.md:91`、`:185` |
| **B4** | **真实命中行数（`changes()`）** | `execSQL` 是 void，三种做法 Java 侧都「成功」（`SnsSqlRewrite.kt:17-19`）⇒ `hitOlder3dTotal` 只是 `buildHitCountSql` 的**等价镜像 count**（几何相交），不是真实 UPDATE 命中行数；「区间相交 ≠ 行级命中」 | `SnsSqlRewrite.kt:17-19`；`docs/_r49_rc_lifeline_review.md:222`、`:248`；`SnsWriteProbe.kt:237-245`、`:463-477` |
| **B5** | **判据自身的工程盲区** | ① `fillHits` 的 `userName` 解析失败**保守跳过**（`SnsWriteProbe.kt:465-468`）⇒ 可能低估；② `dedupKey` 含 SQL 全串（`:79`），`?` 绑定参数/同名不同区间可能折叠；③ `hitOlder3d` 纯函数镜像与导出 SQL **未做真机对账**，桥接层 `captureUpdate/captureExecSql/export/resolveT0/fillHits` **零单测** | `SnsWriteProbe.kt:79`、`:465-468`；`docs/_r49_rc_impl_review.md:208`、`:225`（C7/C8 风险 3、盲区） |
| **B6** | **SQL 形态变异（版本）** | 三个判据（`SnsSqlRewrite.CLEAR_BIT3_ASSIGN` / `SnsBitLogic.sqlClear` / `SnsWriteProbe.CLEAR9_SET`）**都只认 `-9`（或 `-1`）形态**；若 8.0.48/2588 换成其它常量、`?` 绑定、`CAST`，会**一起漏**，只能靠 `BLOCK w2 sql-unmatched` 人工判读 `[需真机]` | `SnsSqlRewrite.kt:45-48`；`SnsBitLogic.kt:186-188`；`SnsWriteProbe.kt:98-101`；`SnsBlockProbe.kt:601-604`；Q1(c) |
| **B7** | **库位与服务端状态不一致 / 无回滚** | R-C 是写微信库路线，保位后 `sourceType` 与服务端状态不一致是**持久残留**，且 R42 已删除全部回滚路径；对方改回全可见还会引入重复条 | `docs/_r49_rc_lifeline_review.md:185`、`:293`；`docs/_r42_purge_report.md:51-56`；`docs/conclusions/moments.md:214-217`（`snsId` 无唯一约束）转引 `docs/_r49_rc_probe_verdict.md:457` |
| **B8** | **样本有效性 / 身份映射** | veirty 与 R42 昵称 verity 是否同一人未坐实；`docs/conclusions/moments.md:270-275` 记载 verity 名下动态曾 `src=0`「死来源」，若 `S_A` 的 `sourceType` 多为 `2/0`（不在 64 值白名单）则 `& -9` 天然不匹配；`op=who`/`op=label` 昵称↔wxid 映射本身也可能错 `[推断][需真机]` | `docs/conclusions/moments.md:270-275`；`docs/_r49_rc_lifeline_review.md:63`（R2 G2）、`:97`；`docs/_r49_rc_probe_verdict.md:162` |

### 8.6 本轮落档
> **本轮 = 「证据不足」（E1）**：0 条 `& -9` 出现在「全部可见 + 无删除 + 普通浏览」这一**不该触发**的阴性对照条件下，既不能判 R-C 死（Q1/Q2/Q3），也不能判活（8.2 可实现条件一条未满足）。
> 最小下一步 = Q3 §3.3 的 **N1（进 veirty 主页全时序观测 `& -9` + 行级命中）+ N2（删动态阳性对照自证）**；判「可实现」再加 N3（翻页 `older3dShown`）。
> 关两个 R42 开关的对照组（Q6）用于**排除 Q1 的 (c)/(d) 并校准判据**，⛔ 不替代进 veirty 主页。

---

## 自检（只读约束核对）
- 全程**未修改**任何源码 / 测试 / 配置 / 其它报告；**未碰** adb / 真机 / Gradle / 装包 / kvset（Q6 的 `kvset` 仅为「交主会话/操作员」的判据建议，本 lane 未执行）。
- 唯一写入文件 = `docs/_r49_rc_stage2_verdict.md`，按节追加、逐节 `[DONE]`。
- 源码 / 文档均用 Grep/Read 的 `offset/limit` 分段读（单段 ≤300 行），未整读 >300 行文件；`SnsWriteProbe.kt` 分 1-300 / 300-518 两段、`SnsBlockProbe.kt` 分段、`SnsGuard.kt` 分段、长报告均分段。
- 每条结论附 `文件:行号`；不确定处标 `[推断]` / `[需真机]`；好友一律用题面昵称 veirty / Buudaooo，wxid 仅在 R42 原文引用处出现。
- ⛔ 本 lane 为**只读推理**，所有「改措辞 / 关开关 / 进主页」均为**判据 + 下一步观测建议**，未指挥主会话改代码或改配置。

[DONE] Q8
[DONE] ALL

---

## 后续实测纠偏（2026-11-10，主会话补记）

⛔ **本报告的结论已被后续真机实测推翻，保留原貌仅作证据链，不再作为结论来源。**

- 本报告写作时只有**阴性对照**「不岛剧本·粥粥」（Buudaooo，全部可见）的 0 条 `& -9`，
  未进实验组 ⇒ §8.6 判「证据不足（E1）」在当时成立，但**不代表 R-C 的可行性结论**。
- 2026-11-10 进实验组 **verity**（三天可见，用户纠正拼写；昵称/微信号已由资料页坐实）
  主页后实测：`WP stat seen=1 matched=1 clear9=1`、`hitOlder3dTotal=33`、
  `BLOCK w2 sqlClear9=3 sqlBlock9=3`、库位翻页前后逐项不变 ⇒ **写时拦截不空转且已生效**；
  但逐屏翻页 8 次到底仍只显示三天内动态 + 原生提示「朋友仅展示最近三天的朋友圈」
  ⇒ `older3dShown=0`。
- ⭐ 本报告的**三态判据存在范畴错误**：把「零写事件」写成证死（那是触发条件未成立的
  假阴性），把「机制生效但界面不显示」悬成 E2「证据不足」；真正决定性判据应是
  「**机制确认生效后，界面是否显示三天外动态**」。
- **最终判定 = `R-C 证伪（证死）`，置信度高**；修订版判定表、V3/V4「反向对照与阳性对照
  均不必做」的裁定、根因归因与三档边界审计见 `docs/_r49_rc_stage3_verdict.md`。
  原始实测数据见 `docs/_r49_rc_stage2_result.md`。
