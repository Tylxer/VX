# R86c route2 静态二次独立复审（r86c-route2-review2）

> 工作根：`C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`
> 性质：只读二次复审，续用上一轮 `docs/_r86c_route2_review.md` 全部证据链与判据纪律。
> 唯一写文件 = 本报告；禁改源码、禁跑 gradle/单测、禁碰 adb/装机/设备开关、禁 git commit。
> 判据只对肉眼 / 文件字节；`hit`/`UploadHitCacheType` 只记录不裁决。
> 档位：【真机实证·高|中】/【源码实证·高】/【dex 实证·高】/【推断·高|中|低】/【UNKNOWN 未闭合】。
> **【推断】一律不写成【已证】。**
> 本轮范围：只审静态修正是否真解 B1/B2/M1 或引入新 BLOCKER；**条件③（真机 probe-off/hevc-on 间隔观测）本轮不判**。

## 状态追踪
- [x] 骨架 + Q1 写盘（含 `[DONE Q1]`）
- [x] Q2 写盘（含 `[DONE Q2]`）
- [x] Q3 写盘（含 `[DONE Q3]`）
- [x] 三件套 + 真 `[DONE ALL]`

---

## Q1 —— B1（TTL）修正是否成立

### Q1.1 TTL 值与时钟改动亲核（源码字节）
- `ForwardCdnFieldHook.kt:50` `private const val TTL_MS = 15000L` ✓【源码实证·高】
- 时钟全文件仅 3 处且均为单调钟：`:146` `markTarget` 登记 `SystemClock.elapsedRealtime()`、`:170` TTL 判定 `SystemClock.elapsedRealtime() - p.ts > TTL_MS`、`:202` 有界重登记 `SystemClock.elapsedRealtime()`。
  `grep currentTimeMillis` 在本文件 **0 命中** ⇒ 墙钟已彻底移除，未泄露到探针链/arm/其它判定。【源码实证·高】

### Q1.2 15s 是否真覆盖已亲核间隔 + 重传间隔
- 上一轮逐行亲核的最大 queued→m1.e = **10071ms**（19b9 long2，`:1375`→`:1442`）；另一长帧 6345ms（d07f）。15s > 10.071s，**覆盖成立**【真机实证·高 + 源码实证·高】。
- 同转发 host 重传两帧间隔 = `:1442` 22:24:56.591 → `:1523` 22:25:07.449 = **10.858s**。本轮修正后 ts 在每次成功写入后续期（`:202`），故第二帧的判定基准是 `:202` 重登记时刻（≈22:24:56.59），10.858s < 15s ⇒ **重传帧也在 TTL 内**【真机实证·高 + 源码实证·高】。
- 结论：**B1 零生效路径（2000ms 必超时清零）已被消除**【源码实证·高】。

### Q1.3 TTL 拉长后引入的新风险评估
1. **残留窗口**：pending 驻留从 2s 延至 15s，且重登记可续期至多 3 帧（理论 ~45s）。但 `:182` basename 闸、`:217-218` 9554B/0ef205c1 门、`:187` MAX_HITS 早停共同收口，非"无界残留"【源码实证·高】。**但**：窗口变长会放大 M2（见 Q2.4），属【推断·中】，非新 BLOCKER。
2. **普通发图误命中**：`findTargetHevc` 入口 `:217-218` 仍强制 `exists && length()==9554L && md5PrefixOfFile()=="0ef205c1"`；任一不满足 `return null` ⇒ 不写。门禁逐字未放宽【源码实证·高】。故即使 TTL 内有同 talker 的普通发图 m1.e 误命中 pending，其原图若非 9554B/0ef205c1 仍 fail-closed【推断·中】。9554B 门禁**挡得住**。
3. **同 talker 串行多图**：key 仍是 `field_talker`，`markTarget` 覆盖写会顶掉前一张的 value；见 Q2.4 裁决——沿用上一轮 M2【UNKNOWN 未闭合】，本轮不升级 BLOCKER。

`[DONE Q1]`

---

## Q2 —— B2（重试帧 × 一次性消费）修正是否成立（本轮重中之重）

lane 选方向 (b)：`(talker, 原图 basename)` 有界重登记。逐条独立核验。

### Q2.1 逐帧推演 p.basename / p.hits / p.ts 演化（能否让第二次也注入）
真机证据：19b9 两次 m1.e（`:1444`/`:1525`）间**无新 queued**（下一 queued 在 `:2540` 23:08:54），第二次是**新 task**（新 s0 `aupimg_...1795530286071`，`:1497`），第二次 c2c（`:1526`）仍 `fbytes=9554 fmd5pre=0ef205c1`。【真机实证·高】

| 步骤 | p（进入时） | :170 TTL | :182 basename 闸 | :187 hits 闸 | :193 remove | :196 写 | :201~203 重登记 |
|---|---|---|---|---|---|---|---|
| `markTarget`(queued :1375) | `Pending(ts0, null, 0)` | — | — | — | — | — | `ts0=elapsedRealtime` |
| 首帧 m1.e (:1442, +10.071s) | `(ts0, null, 0)` | 10.071s<15s ✓ | `p.basename==null` ⇒ 跳过（不挡） | 0<3 ✓ | 成功 | 写 hevc | `Pending(ts1, bn, 1)` ts1≈:1442 |
| 重传帧 m1.e (:1523, 距 ts1 10.858s) | `(ts1, bn, 1)` | 10.858s<15s ✓ | `p.basename==bn` ⇒ 通过 | 1<3 ✓ | 成功 | 写 hevc | `Pending(ts2, bn, 2)` ts2≈:1523 |

⇒ **第二次 host 重传帧同样被注入**，`p.basename` 首帧为 null 不挡、重传帧 match，`p.hits` 0→1→2 均在 MAX_HITS 内，`p.ts` 每次成功后续期。逻辑成立【源码实证·高 + 真机实证·高】。
- 边界：第 3 帧若出现，`p.hits=2<3` 仍写，但 `:201` `p.hits+1=3 < 3` 为假 ⇒ 不重登记；第 4 帧 `pendingMap[...] ?: return` 无命中 ⇒ 不写。即**最多 3 帧**。

### Q2.2 basename 取自何处、是否稳定 —— 本轮亲核重点，不假设
- 来源：`ForwardCdnFieldHook.kt:181` `val bn = File(fullpath).name`，`fullpath` 取自 `:177-179` `getObjectField(task,"field_fullpath")`。【源码实证·高】
- 真机两帧 `field_fullpath` 逐字节核对（step1b 原始日志）：
  - 首帧 `:1444` = `.../image2/19/b9/19b98c620134f3e2448be0bf573e47bf.jpg_`
  - 重传帧 `:1525` = `.../image2/19/b9/19b98c620134f3e2448be0bf573e47bf.jpg_`
  - **两帧 basename 完全相同**（同为 `19b98c620134f3e2448be0bf573e47bf.jpg_`，含尾部 `_`），目录桶同为 `image2/19/b9/`，localId 同（`:1438`/`:1501` 均 `a=886522`）。【真机实证·高】
- ⇒ 真机重传**未换文件名**，`:182` basename 闸**不会 fail-closed**，B2 在真机证据路径上确解。lane §5② "basename 同、localId 同、其间无新 queued" 的自述**经逐字节复核成立**【真机实证·高】。
- 未证边界：若 host 重传换名（basename 不同），:182 会 fail-closed ⇒ B2 未解。但真机现有证据无此情形【推断·低】，仅【UNKNOWN 未闭合】残留。

### Q2.3 MAX_HITS=3 是否够
- 全量核查 step1b 日志：19b98c 的 m1.e 仅 **2 帧**（`:1444`/`:1525`），全日志 m1.e 共 10 条，无 19b9 第 3/4 帧证据；d07f 仅 1 帧（`:2654`）。【真机实证·高】
- `MAX_HITS=3` = 首帧 + 2 次重试，覆盖已观测 2 帧并余 1 次，够用。第 4 帧未写属 fail-closed，但真机无此证据【推断·低】。

### Q2.4 lane 自标残留 UNKNOWN（串行不同图 markTarget 覆盖）裁决与档位
- **机制亲核**：`markTarget` 是覆盖写（`:146` `pendingMap[wxid] = ...`）。若前一张 A 在重传窗口内又对同 talker `markTarget(B)`，key 被覆盖为 `Pending(tsB, null, 0)`，此时 `p.basename==null` ⇒ `:182` basename 闸被**整段跳过**（条件 `p.basename != null && ...`）。A 的重传帧会消耗 B 的 pending、按 A 图写 hevc 并以 `bnA` 重登记；B 后续帧因 `bnA != bnB` 在 `:182` 全部 fail-closed ⇒ **B 图零生效**。此为【源码实证·高】的机制推演，但触发需"A 重传与 B 登记时间重叠"，真机**无此场景实证** ⇒ 整体【推断·中】。
- **裁决**：**不升级为 BLOCKER**。理由：① 该 key 碰撞自第4轮即存在（上一轮 Q2.5/Q2.8 M2 已列【UNKNOWN 未闭合】，明确定性 MAJOR"不单独阻挡"）；② 修正未新引入该缺陷，只是 TTL 变长使窗口扩大（【推断·中】放大，非新因）；③ 无真机证据触发。**维持 M2 = MAJOR /【UNKNOWN 未闭合】**，不阻挡静态维度。
- **诚实纠偏**：lane §5② 自述"basename 闸使同 talker 并发不同图 fail-closed"**仅在 `p.basename != null` 时成立**；markTarget 覆盖后 basename 归 null，该闸失效。lane §5② 末尾已诚实承认此残留 UNKNOWN，但正文"四道闸"第 2 闸的防串味论证对覆盖场景**过强**，需按本条收敛。

### Q2.5 方向 (a) 被否的理由是否成立 / (b) 是否更易错 —— 诚实评估
- lane 否决 (a) 的理由"不区分 basename 会串味"**部分成立**：纯后置消费 + 同 talker 计次确实不区分图，会跨图耗预算；此即 M2 的本质。【推断·中】
- 但 **(b) 的 basename 闸同样在 markTarget 覆盖后失效**（Q2.4），故 (b) **并未真正比 (a) 多解决 M2**；(b) 的复杂度（Pending 三字段、basename 闸、重登记、MAX_HITS 四道闸）高于 (a) 的"后置消费 + 计次"，引入的指针/状态更多、出错面更大【源码实证·高：结构复杂度】。
- 结论：真机证据路径上 (b) 确解 B2（Q2.1/Q2.2），**接受**；但" (b) 因 basename 而优于 (a) 的防串味"这一理由**不成立**——两者对 M2 都无解。(b) 是"能解 B2 的可用实现"，非"比 (a) 更安全"。

`[DONE Q2]`

---

## Q3 —— M1 修正与变异可信度

### Q3.1 findTargetHevc 扫描优化亲核（`:215-251`）
- 结构：`:225` `origin.parentFile?.listFiles()?.forEach` 同目录优先；`:229` `if (hits.isEmpty())` 才走 `root/image2` 两级兜底；`:237` `scanned >= MAX_SCAN_FILES(1024)` 早停；`:241` `hits.size > 1` 早停；`:248` 结尾仍 `if (hits.size == 1) hits.first() else null`。【源码实证·高】
- **自述 <0.3s 可信度**：结构上成立——`admitTarget` `:255` 先 `exists()+length()` 才 `:256` 做 MD5，非 `.jpg_hevc` 或长度!=45522 的文件只做 stat；真机探针 `R86C FS cand` 条目均为 45748B/9554B/36235B 等（`:1378-1402`），`verdict scanned=23/22 a45522=0 cff4=0`（`:1403`/`:1495`），即**真机目录内 45522B 候选为 0 ⇒ 无 45KB MD5 开销**；且本 hook 无逐文件 `LogX.i`（探针 5.76s/9.13s 含逐候选日志，上一轮 Q1.3），故不构成同量级阻塞。【真机实证·中 + 推断·中】。保守上界 1024×stat + 少量 MD5 < 1s 合理；**精确 <0.3s 属 lane 自估，标【推断·中】，不写成【已证】**。
- **1024 上限是否够**：真机兜底扫描量 22~23 文件 << 1024，够【真机实证·中】。但极端情况（目标 hevc 排在 1024 个文件之后）`break@outer` 会漏扫 ⇒ 0 命中零生效【推断·低】，同目录优先已大幅降低此概率。
- **同目录优先是否违反"命中数==1" fail-closed**：**严格意义上违反全局唯一性**——若同目录恰有 1 个 45522/cff4 而全树另有 1 个，`:229` 的兜底因 `hits.isEmpty()` 为假**不执行**，直接返回同目录那 1 个，不再校验全树是否还有第 2 个。【源码实证·高】。但两候选若同长度且同 MD5 前 8（cff4f5a5），内容按判据等价，写任一均清晰 ⇒ 实际影响【推断·低】，非 BLOCKER。这是"优化换严格唯一"的取舍，lane note 未披露此语义收窄，应如实记录。

### Q3.2 M6/M7/M8 变异可信度
三项均满足"真改源码文本 + 各自判红 + 非等价"：
- M6（`:283-295`）`TTL_MS = 15000L → 2000L`，`occur==1`，`!ttlOk` 判红；ttlOk（`:349`）精确匹配 `TTL_MS = 15000L`【源码实证·高】。
- M7（`:298-309`）TTL 判定行 `elapsedRealtime → currentTimeMillis`，`occur==1`，`!monotonicClockOk` 判红；判定函数（`:353-354`）精确匹配整行【源码实证·高】。
- M8（`:312-324`）删重登记行 → `Unit`，`occur==1`，`!retryBoundedOk` 判红；`retryBoundedOk`（`:360-364`）四要素缺一即假【源码实证·高】。
- **keyedGateOk 收紧**（`:339-342`）：现精确要求 `if (!pendingMap.remove(field_talker, p)) return` 整行，不再被 `:172`/`:188`/`:193` 三处 `pendingMap.remove` 稀释；M4 变异锚点 b（`:242`）与该整行一致、`occur==1` ⇒ M4 恢复判红【源码实证·高】。对 M3/M5/M8 等调用 `keyedGateOk(mc)` 的断言，因这些变异不触碰该行，收紧未削弱其它断言【源码实证·高】。
- note §6 自述的定向 17 例 / 全量 1194 / assembleDebug 三闸**本轮不复核**（只读纪律，未跑 gradle）；仅核单测文本逻辑，判定可信【源码实证·高】。

### Q3.3 第4轮已验证项是否回退
逐项核对，**全部未回退**【源码实证·高】：
- 唯一 hook 点：`install()` 仅 `findClass("ok0.m1")`，无 j2.R hook；测试 `:54-56` 锁在。
- key=`field_talker`：`:165-169` `getObjectField(task,"field_talker")`，测试 `:125` 锁在；`FileSender.kt:163 markTarget(wxid)` 目标会话、非 sourceChat，测试 `:154-157` 锁在。
- 单字段写：全文件 `grep setObjectField` 仅 `:196` 一处（field_fullpath），测试 `:59-61` 锁在。
- M1 绕开：`findTargetHevc` 取根来自 `field_fullpath` 正则 `:219`，无 r0 String `/MicroMsg/`、无 xml md5、无 param.result；测试 `:71-83` 锁在。
- 旧 hook 删除：`J2R0eOverrideHook.kt`/`Test.kt` 物理不存在（`ls` 确认），测试 `:174` 锁在。
- Key 只有一个：`Keys.kt:200 FORWARD_TASK_FULLPATH_HEVC`，`FORWARD_R0E_HEVC` 全仓 0 命中；测试 `:177-180` 锁在。
- **额外修正**：`Keys.kt:538` PrefSpec 默认表已注册该 Key（SWITCH/"false"/聊天组），上一轮 Q2.6 MINOR"Key 未注册"已闭合【源码实证·高】。

### Q3.4 note 与代码的一处描述偏差（诚实记录）
lane §5② 称"消费时机后置到写入成功之后"，但代码 `:193` `pendingMap.remove` 发生在 `:195` `findTargetHevc` / `:196` 写**之前**；实际实现是"**写后重登记**"而非"写后消费"。【源码实证·高】。影响：若首帧 `findTargetHevc` 返回 null（0 命中/歧义/门禁不过），`:195 ?: return` 会在已 remove 后提前返回，**不重登记**，重传帧将无 pending ⇒ 不注入【推断·中】。此路径下本就无有效 hevc 可写，影响有限，非 BLOCKER，但 note 措辞需 correction。

`[DONE Q3]`

---

## 三件套交付

### ① 三问一句话结论 + 置信度
- **Q1（B1）**：**修正成立** —— TTL 15000L + `elapsedRealtime` 覆盖真机最大 10071ms 迟到帧与 10.858s 重传间隔，零生效路径消除，9554B 门禁仍挡普通发图；残余窗口放大 M2 属推断。【真机实证·高 + 源码实证·高】
- **Q2（B2）**：**修正成立（真机证据路径上）** —— 逐帧推演两帧 basename 相同（`:1444`/`:1525` 逐字节核实同为 `19b98c...jpg_`）、hits 0→1→2 未越界、ts 每次续期，第二次新 task 同样被注入；MAX_HITS=3 覆盖已观测 2 帧。【真机实证·高 + 源码实证·高】
- **Q3（M1/变异）**：**修正成立且变异可信** —— 同目录优先 + 1024 上限 + 早停结构性缓解 worker 阻塞，M6/M7/M8 各自真改文本判红、keyedGateOk 收紧恢复 M4 判红、第4轮六项全未回退；但"全局唯一命中"语义被同目录优先收窄、"消费后置"措辞与代码不符。【源码实证·高；耗时上界/全局唯一影响【推断·中】】

### ② 静态维度 go / no-go（是否进条件③真机观测）：**GO**
- **无 BLOCKER**：B1、B2、M1 三项静态修正均成立，未引入新的确定性 BLOCKER。上一轮的 B1 BLOCKER 已消除，B2 已处置。
- 可**进入装机前的条件③真机观测**（probe-off/hevc-on 只读间隔观测，确认登记→消费 < 15s）。本轮不判条件③。
- 随行 Residual（均不阻挡，但条件③应一并留观）：
  - M2 同 talker 串行/并发多图 markTarget 覆盖 ⇒ 不同图零生效【UNKNOWN 未闭合 / 推断·中】，TTL 延长使其窗口放大；
  - 同目录优先收窄"全局命中数==1"语义【源码实证·高 / 影响推断·低】；
  - note"消费后置"与代码 `:193` 先 remove 不符，findTargetHevc 失败时重传帧不注入【推断·中】；
  - step1b 真机探针 `verdict a45522=0 cff4=0`（`:1403`/`:1495`）显示当刻树内无 45522/cff4 hevc ⇒ 即便逻辑正确也可能零生效【UNKNOWN 未闭合，第4轮已列】，条件③宜同时记录目标 hevc 是否在场/唯一；
  - fallback 多 overload、`register` 条件过窄等旧 UNKNOWN 沿用上一轮，不重复。

### ③ 最小下一步
1. **主会话装机前执行条件③**：probe-off + hevc-on 当前构建一轮只读观测，记录 queued→m1.e in 间隔（应 < 15s）、两次重传帧 basename 是否仍同、目标 hevc 是否在场且唯一；只记录不改判据。
2. 条件③通过后，方可宣称静态 + 真机双通道闭合；若观测到 basename 换名或间隔 > 15s 或 hevc 不在场，回退 B2/B1 重评。
3. 建议 lane 后续（非本轮阻挡项）：收敛 note §5②"消费后置"措辞为"写后重登记"；若产品需支持同 talker 并发/串行多图，按上一轮门槛0 另立 key（如 talker+basename）重构，当前架构前提仍是单目标串行。

`[DONE ALL]`
