# R49 · R-C 复用写时拦截 · Stage3 最终裁决（lane I / 独立裁决 lane）

> 角色：独立最终裁决 lane I（Pro 类：判据设计 / 根因推理 / Hook 链路复核）。**全只读**；不碰设备/源码/配置，不指挥主会话改代码。仅给「判定 + 判据 + 下一步观测建议」。
> 唯一诉求：验证 **R-C 复用写时拦截** 这一技术路线能否实现「突破好友三天可见」；不考虑 R-A/R-B。
> 实验组：**verity**（三天可见，`wxid_xlrbelf6hv0w22`）；阴性对照：**不岛剧本·粥粥**（微信号 Buudaooo，全部可见）。全程用昵称。
> 输入：本轮真机实测原始证据 N0/N1/N3 + 上轮阴性对照 + lane H 三态表与必读材料。结论不确定处标 `[推断]` / `[需真机]`。

## 待答清单（9 问）
- V1 三态表重判（本轮核心：matched=1 ∧ hitOlder3d=33 ∧ 改写生效 ∧ 库位未变 ∧ older3dShown=0）
- V2 根因归因（bit3 保住但界面仍三天 + 原生截断提示）
- V3 反向对照裁定（关两开关是否仍必要）
- V4 阳性对照 P-10 裁定（请 verity 删一条动态是否仍必要）
- V5 `hitOlder3dTotal=33` 口径校准（fillHits 实现）
- V6 阴性对照再解读（Buudaooo `sqlClear9=0`）
- V7 三档边界覆盖审计（进入/返回/复位）
- V8 最终判定表（修订版，以「界面是否显示」为终点）
- V9 收尾建议 + 剩余盲区 + 最小可执行收尾动作

[START]

## V1 · 三态表重判（本轮核心）

### 输入对齐（原样，不改写）
本轮组合 = `matched=1 ∧ clear9=1 ∧ hitOlder3dTotal=33 ∧ 改写已生效`（N1 `WP dump rewritten=0`=探针在改写前捕获 `& -9` 原文；`BLOCK w2 sqlClear9=3 sqlBlock9=3`）`∧ 库位前后不变`（N3 翻页后 `op=sql` verity `sourceType` 分布 `0|20 10|8 2|4 8|1` 与进主页前逐项一致）`∧ older3dShown=0`（逐屏 swipe 8 次日期恒 `06|11月|11|2026年`、图片节点 8、末屏含「朋友仅展示最近三天的朋友圈」原生提示）。

### 裁定：**判「R-C 证伪（证死）」** ⛔ 不落「证据不足」、不落「可实现」、不和稀泥。

按 lane H §8.2（`docs/_r49_rc_stage2_verdict.md:338-344`）逐格比对：
- 「可实现」格（`:341`）末项要求翻页 `older3dShown>0` ⇒ 本轮 `=0`，**不落此格**。
- 「证死·强证据」格（`:342`、`:363-369`）要求 `matched=0 ∧ clear9=0 ∧ rewritten=0 ∧ BLOCK sqlClear9=0 ∧ hitOlder3dTotal=0` ⇒ 本轮五项**全部取反**，按现表**也不落此格**。
- 于是 lane H 只能把本轮塞进 E2「证据不足」（`:352`「`hitOlder3dTotal>0` 但翻页仍 `older3dShown=0`（P1.6 未定）」；`docs/_r49_rc_probe_verdict.md:470` V-6）。

**但 E2 的前提在本轮已被逐一观测消解，必须重判为证死：**
1. E2 成立的前提是「P1.6 未定 / 界面为何不显示尚不清楚」。lane H 自列「不得误判为证死的三种」（`docs/_r49_rc_probe_verdict.md:474`）：V-5 有写没拦到 / 首屏空 / 干扰致数据不可信。本轮三种**全被专门观测排除**：①「首屏空」不算失败——本轮逐屏翻页 **8 次**到加载终止、末屏出原生截断提示，不是只看首屏（lane H 自己也写「首屏允许为空」`:341`）；②「有写没拦到」不成立——`sqlBlock9=3` + 库位前后逐项一致 = 改写生效、bit3 保住；③「干扰致数据不可信」不成立——双探针交叉印证 + `op=kvget` 开关态 + 身份坐实（资料页昵称 verity / `wxid_xlrbelf6hv0w22` 与 `docs/conclusions/moments.md:270-275` 同一人 ⇒ B8 消除）+ 阴性对照干净（详见 V3/V4/V6）。
2. 本轮是 **R-C 机制完整闭环生效**：写了（N1 before 捕获 `UPDATE SnsInfo SET sourceType = sourceType & -9 ...`，`rewritten=0`）、拦了（`sqlBlock9=3`）、保住了（0|20 10|8 2|4 8|1 不变）、翻页穷尽了（8 屏 + 账本无新增 + `hitOlder3dTotal` 恒 33）。**唯一缺的就是用户可见目标**。这正是「机制生效但目标不达成」= 证伪，不是「证据不足」。
3. 按项目**自己**定的决定性判据就该判死：`docs/_r49_rc_stage1_result.md:90-92`（「『拦住写』≠『界面显示』」）、`:110-111`（「判据从『有没有写事件』改为『拦住写之后界面是否显示』」）、`docs/_r49_rc_lifeline_review.md:193`（「可行性判据应升级为『拦住写后翻页是否显示』」）、`:187`（「真正挡住的是响应列表里没有这些 id」）。本轮第一次把这条决定性判据跑完，结果否定。

### ⭐ lane H 判据错在哪（为何证死判据必须修订）
- **把「有无写事件」当唯一分档变量**：`证死 = matched=0 ∧ clear9=0 ∧ rewritten=0 ∧ BLOCK sqlClear9=0 ∧ hitOlder3dTotal=0`（`:342`、`:369`「目标清位写事件行级就是没落在三天外行上」）。这是**范畴错误**——全 0 写事件只说明「触发条件没成立 / 探针没抓到 / 场景没进对」，属**假阴性**，按 `:469`(V-5)、`:474` 本就该落「证据不足」；lane H 却把「R-C 没被触发」写成「R-C 被证伪」。
- **表结构性地判不出死**：「可实现」把 `matched>0 ∧ hitOlder3d>0` 当必要条件（`:341`），又把唯一决定性的「拦住写 ⇒ 界面显示」降为末位 `∧` 项；于是当界面不显示时，表里**没有为「机制生效但界面不显示」留任何一格**，只能悬空成 E2。
- **真实决定性判据**应是：在「机制确认生效（`& -9` 原文 + 改写拦下 + 库位保住）」前提下，**用户可见事实「界面是否显示三天外动态」**。写事件有无只是「机制是否跑起来」的确认项，不是分档变量。修订版见 V8。

### 若坚持「证据不足」，唯一可执行且尚未做的观测（不泛泛）
只缺 V2 的 A/B 区分：在 `w5.onSceneEnd`（`docs/conclusions/moments.md:259-260`）/`h3.N(Lq54/jc5;...)`（`docs/未完成项与计划.md:320-321`）before 里 dump 响应内存对象 `LinkedList<SnsObject>` 的 snsId 集合，与 N0 的 33 个三天外 snsId（`-3405830652137369075 ~ -3397130994131660261`）比对。工具现成（`SnsSqlSpy` 只读旁听 `docs/未完成项与计划.md:325`；`SnsHuntProbe` P-06 `docs/_r49_rc_probe_verdict.md:304`），本轮未开 ⇒ `[需真机]`。⛔ 但这条**只改归因精度、不改档位**：无论 A/B，「DB 层保 bit3」与「界面显示」的因果链都已被本轮切断。

> **一句话：本轮判「R-C 证伪（证死）」，置信度高；lane H 错在用「有无写事件」分档，把「机制没触发」写成证死、把「机制生效但界面不显示」悬成证据不足。**

[DONE] V1

## V2 · 根因归因（bit3 保住但界面仍三天 + 原生截断提示）

### 硬锚点逐条对照
- `docs/conclusions/moments.md:259-260`：**首屏/刷新列表由响应内存对象渲染**（`w5.onSceneEnd` code=0x394640 全方法**无一次 `d2`（SnsInfo 存储层）调用**），「`SnsInfo` 只是旁路持久化缓存」；`:261` 翻页走 `d2.t5` 带 `stringSeq` 游标（查表）；`:262` 内存层过滤不读 `sourceType`（`isDieItem()` 查 `getLocalFlag()`、`isInValid()` 查 `l0()`/snsId）。
- `docs/_r42_wechatmagician.md:40-41`：首屏**解决不了**（首屏由网络响应重建），两方案同。
- `docs/未完成项与计划.md:329`：相册三门槛 ① SQL `userName + sourceType in (64 全含 bit3) + type in (宽白名单)` ② Java 装载 `qg3/k.b()` `type` 必须 ∈{1,15} 否则 continue ③ `isDieItem() = (localFlag & 32)!=0` 则 continue。
- `docs/未完成项与计划.md:345`（6.3 项目硬结论）：「最近三天/一个月可见」是**服务端过滤**，超出范围对象**根本不下发**、「客户端手里没有的字节，任何模块都变不出来」；`:348`「客户端数据通路完全不按天过滤，三天可见纯属服务端截断」。
- `docs/未完成项与计划.md:320`：相册响应 `q54/jc5`，游标字段 `ic5.f=MaxId / ic5.h=LastRequestTime`（`:322` 相册是读库渲染 `qg3/i` 吃 `SnsInfo` 实体 + SQL `SnsInfoStorage.getUserMediaSql`）。
- `docs/_r49_rc_lifeline_review.md:184-187`：R-C 效果上限 = 保住 `sourceType` ⇒ 翻页能翻到、首屏仍解决不了；`& -9` 拦的是**清 bit3**、保的是**已有位**，不能凭空给没进过相册的行补 bit3，也不能让响应列表凭空多出三天外 id。

### 两种解释，明确区分
- **解释 A · 服务端根本不下发这些 snsId**：三天可见是服务端权限截断（`未完成项与计划.md:345`/`:348`）。服务端只组装三天内 `SnsObject` 并带「仅三天」边界标记，响应内存对象里**没有** N0 那 33 个三天外 snsId。首屏吃响应对象（`moments.md:259-260`）⇒ 库里保住的 bit3 对首屏完全不可见；翻页游标 `MaxId`/`LastRequestTime`（`:320`）被服务端边界钉死 ⇒ adapter 到边界插入「朋友仅展示最近三天的朋友圈」并停止加载，**根本不会去 `d2.t5` 查更早表行**（`:261`）。
- **解释 B · 下发了但本地渲染门槛挡住**：响应里有这些 id，被三门槛（`未完成项与计划.md:329`）逐条过滤，或 `SnsInfo` 旁路缓存未被读。尤以 `type∈{1,15}` 为甚：`docs/未完成项与计划.md:330-331` 已实测 verity 动态 `type=2`（视频）本就不进相册网格、命中 0 条——与 bit3 **无关的独立上限**。

### 本轮证据倾向（可复算）
**解释 A 为主且被双重坐实，解释 B 至多是叠加的次生门槛**：
1. 末屏「朋友仅展示最近三天的朋友圈」是**微信 UI 依据响应里的权限/截断标记渲染的提示**，非本地 DB 层可产生（`未完成项与计划.md:345` 服务端过滤 + `:348` 客户端不按天过滤）⇒ 直接证明响应体带了「只展示三天」边界信号。若是纯解释 B（静默少几条），不会出现这条权限文案。
2. N3 翻页 8 次**账本无新增写事件**（seen=1 matched=1 clear9=1 与 N1 逐项一致）、`BLOCK w2` 逐项一致、库分布逐项一致 ⇒ 翻页**没有**触发查表/写事件去消费那 9 行含 bit3 的三天外行，加载在三天边界终止；`moments.md:261` 的「翻页查表」路径在本场景未越过服务端边界。
3. `& -9` 的触发语义本身（`docs/_r42_bit_paths.md:79`「服务端未返回、本地有 bit3 → 清 bit3」）：微信发这 3 条清位，正说明**服务端这次响应没返回**那批行，本地只是配合清理缓存位。R-C 拦的是「配合清理」，拦不住「不下发」。

### 能区分二者的只读判据（本轮测不到，如实写盲区）
在响应层 before dump `LinkedList<SnsObject>` 的 snsId 集合（工具见 V1 末段），与 N0 的 33 个三天外 snsId（`-3405830652137369075 ~ -3397130994131660261`）比对：响应列表**无**这些 id ⇒ A；**有**但 UI 不渲染 ⇒ B。本轮探针只挂 DB 写层（`SnsWriteProbe.kt:9-10` 自述「界面能否显示留给真机人工判读」）、`SnsSqlSpy` 默认关闭（`未完成项与计划.md:325`）⇒ `[需真机]` 盲区。辅助只读判据：抓 `SnsUserUI` 相册查询 SQL 的 `stringSeq`/`MaxId`/`LastRequestTime`（`未完成项与计划.md:320`、`docs/_r49_rc_probe_verdict.md:325`）看游标是否被钉在三天边界。
⛔ 无论 A/B 都**不改变 V1 证伪档位**：两者都说明「DB 层保 bit3 ≠ 界面显示」，区分只为归因精度。

> **一句话根因：R-C 拦的是客户端 DB 写（`SnsInfo.sourceType` 的 bit3 缓存位），而「三天可见」的边界在服务端响应组装层 + UI 渲染层（`moments.md:259-260`、`未完成项与计划.md:345/348`）；两条不在同一数据通路上 ⇒ 保住 bit3 对首屏不可见、翻页游标被服务端边界截断 ⇒ 界面仍三天内。**

[DONE] V2

## V3 · 反向对照裁定（关两开关是否仍必要）

### 铁律与风险
- 项目硬结论：临时全放行必须 `antirevoke.sns` 与 `antirevoke.sns_block` **两个都关**（`docs/_r49_rc_probe_verdict.md:310` P-12「⛔ 两个都关」、`docs/_r49_rc_stage2_verdict.md:335` Q6、`SnsGuard.kt:195`）；铁律要求真机做反向对照（`docs/_r49_rc_probe_verdict.md:310`、`:341` P-12 `kvset` 交主 agent）。
- ⛔ 风险：关两个开关 ⇒ `& -9` **真实落到磁盘**、把 verity 三天外 9 行含 bit3 的行清成 `10→2`/`8→0`（`docs/_r42_bit_paths.md:79-80`），磁盘状态**不随开关/冷启回滚**（`docs/_r49_rc_lifeline_review.md:293`、`:185`、`docs/_r49_rc_stage2_verdict.md:381` B7、`docs/_r42_purge_report.md:51-56` R42 已删全部回滚路径；`docs/conclusions/moments.md:214-217` snsId 无唯一约束、重复条风险）。这是**不可逆污染真实微信库**，且会破坏本就残缺的 verity 相册样本。

### 裁定：**不需要做，且不建议做**（明确裁定，不模棱两可）
反向对照（P-12 / Q1 消融）唯一目的是排除「Q1 替代解释」——「探针抓到的是不是真 `& -9` 清位」「改写是不是真生效」「库位不变是不是因为根本没写」。本轮这三条已被**三条独立证据闭环**：
1. **① 改写前 `& -9` 原文**：N1 `WP dump` 打出 `sql=UPDATE SnsInfo SET sourceType = sourceType & -9 WHERE ... userName="wxid_xlrbelf6hv0w22"`、`rewritten=0`；源码顺序坐实探针拿原文（`docs/_r49_rc_stage2_verdict.md:38`(d)、`docs/_r49_rc_impl_review.md:101-123` C1：`SnsGuard.kt:844` 局部不可变 `sql`、`:916` `applySqlRewrite` 只改 `param.args[0]` 不碰局部、`noteSql(sql=sql)` 首行 `captureExecSql(sql)` 早于改写）。
2. **② `BLOCK w2 sqlClear9=3 sqlBlock9=3` 双探针交叉印证**：`SnsBitLogic` 只认原文 `-9`（`docs/_r49_rc_stage2_verdict.md:38`、`SnsBitLogic.kt:198` KDoc）、`shouldBlock` 认 SET 段清位（`SnsSqlRewrite.kt:65-69`）；两个挂载点相同、正则不同的独立判据都记到 3 条并拦下，且 `sqlUnmatched=0`。
3. **③ 库位前后不变**：N3 翻页后 `op=sql` verity `0|20 10|8 2|4 8|1` 与进主页前逐项一致 ⇒ 9 行含 bit3 的位**未被清掉**（若改写没生效、真执行 `& -9`，`10|8` 与 `8|1` 两档必变）。

### 「Q1 哪几条替代解释已被现有证据排除」（逐条对 `docs/_r49_rc_stage2_verdict.md:30-39` 五条表）
| Q1 | 内容 | 本轮裁定 |
|---|---|---|
| (a) `:35` 触发条件层：`& -9` 只在删除/私密/服务端截断时发 | **正面证实**：实验组 verity 三天可见触发（matched=1），阴性对照「不岛剧本·粥粥」全部可见不触发（seen=11 matched=0）⇒ 语义自洽（见 V6），不是待排除项 |
| (b) `:36` 路径层：只有「删一条动态」或「方案 B 消融（关开关）」才发 | **排除**：本轮**既没删动态、也没关任何开关**，纯进 verity 主页即 `seen=1 matched=1` ⇒ 「必须删/消融才发」的独有签名被否定。⛔ 这一条恰是 P-12 反向对照想区分的，现已由正面观测直接排除 |
| (c) `:37` 判据层：`parseClear9` 漏识别 / SQL 形态变异 | **排除**：WP `matched=1` + `dump` 完整明细（原文/`userName`/caller/`hitSample`）+ `BLOCK sqlClear9=3` 与 WP 一致，未出现「BLOCK 有原文而 WP matched=0」的分歧签名；`sqlUnmatched=0` 排除整条换形态 |
| (d) `:38` 顺序层：改写发生在探针捕获之前、记成 `-1` 误判无写 | **排除**：探针拿到 `-9` 原文、`rewritten=0`；`BLOCK sqlClear9=3`（只认 `-9`）同步为 3 ⇒ 不是「记到 -1 没认出」，恰是源码顺序的正面自证 |
| (e) `:39` 版本·时序层：首屏不发、需翻页/下拉/冷启才发第二组 | **排除**：进入档 4.5s 即触发（`sub=reset` 后进 SnsUserUI），翻页 8 次无新增（N3）⇒ 「首屏不发」签名被否定；`op=kvget` 四开关态与预期一致 |

⇒ Q1 五条里 (b)(c)(d)(e) **已被本轮证据直接排除**、(a) 正面证实；反向对照要排除的替代解释**已全部闭合**，其信息增量≈0。

### 风险 / 收益
- 收益：重复确认一个已由三重独立证据闭环的事实。
- 代价：不可逆清掉 9 行 bit3、污染真实微信库、破坏样本、`kvset` 写模块配置库（`docs/_r49_rc_probe_verdict.md:310`、`:341`，本 lane 禁执行）。
- ⇒ **不必做，且不建议做**。若用户仍坚持，须先完整备份 `SnsInfo` 并明确接受「磁盘位不回滚 / 重复条」的不可逆后果，属需重新授权的操作，**不在本裁决建议内**。

[DONE] V3

## V4 · 阳性对照 P-10 裁定（是否仍必须请 verity 删一条动态）

### lane H 设定
- `selfOK` = P-10 阳性对照抓到写 ∧ P-11 阴性对照正常 ∧ 六钩子齐全 ∧ 投递自证（`docs/_r49_rc_stage2_verdict.md:334`）。lane H 把 `selfOK` 列为判死/判活**必要条件**（`:341`、`:342`、`:365`），明写「阳性对照未做 ⇒ 无法排除探针挂错/判据失效假阴性」（`:101`、`:334`、`:365`）。
- P-10 = 请实验组删一条动态、确认账本能抓到 `want=0` 写（`docs/_r49_rc_probe_verdict.md:308`、`:310`、`:396`「需用户配合」）；`docs/_r49_rc_stage2_verdict.md:101` 原话「两个探针一致为 0，恰恰因为没有阳性对照锚，不能自证有效」。
- 成本：需 verity 配合删动态 + 扰动真实库 + 引入删除场景（删除是「服务器不下发删除标记」的另一条路径，`docs/conclusions/moments.md:265-268`）。

### 本轮正向自证（已构成）
- `matched=1` + `WP dump total=1 show=1` 完整明细（`ts / kind=EXECSQL / table=SnsInfo / sql 原文 / cvKeys / where / snsIds / userName / caller / hitOlder3d=33 / hitSample`）：探针**挂对了、判据识别了目标 SQL、能抓到真实写事件**。
- `BLOCK w2` 独立探针 `sqlClear9=3 sqlBlock9=3 delSns=1 delRow=1`：另一套正则也认到目标写并拦下。
- 阴性对照「不岛剧本·粥粥」`seen=11 matched=0 clear9=0 rewritten=0`：探针在**不该触发**时不误报（V6），明细仅 `CREATE TABLE IF NOT EXISTS StarSnsInfo` + 1 条 `cvSourceType=10 where=snsId=?` 普通 UPDATE。
- `verdict()` 三态互斥逻辑已由单测锁住（`docs/_r49_rc_impl_review.md:131`、`SnsWriteProbe.kt:255-258`），非本轮疑点；设备前置 `installed hooks=4` / `MOMENT export installed` / `K7 installed` / `sns db captured via execSQL` + `WP switch armed=true` ⇒ E4「挂错类/签名不匹配/钩子未注册」（`docs/_r49_rc_probe_verdict.md:354`）无迹象。

### 裁定：**P-10 不再必须做**
- P-10 的价值是**当 `matched=0` 时**区分「真没写」与「探针坏/挂错」（`docs/_r49_rc_probe_verdict.md:469` V-5、`:474` 不得误判为证死的三种之一）。本轮 `matched=1` 且有 `dump` 明细 ⇒ 探针有效性已被**正向事件直接证明**，不再需要「请好友删一条动态」来间接反证。
- 双向自证已闭合：实验组正向抓到目标写（排除假阴性）+ 阴性对照不误报（排除假阳性）+ 双探针交叉（排除单判据漏识别）。`selfOK` 想防的三类假阴性本轮均无迹象。
- 成本 / 收益：P-10 需 verity 配合删动态、扰动真实库；能新增的信息仅「删动态走的是不是同一条 `& -9` 通道」，属机制外延，**与 R-C 死活无关**。
- ⇒ **不必做**。⛔ 这不否定 lane H 在「`matched=0`」情境下设 P-10 的正确性；只是本轮情境已变（`matched=1`），该前提失效，且本轮走的是「机制生效 + 界面不显示」的证死路径，`selfOK` 三项内容已由现有观测等价满足。

> 一句：P-10 的假阴性排除使命已被 `matched=1` + dump 明细 + 阴性对照 + 双探针**正向自证**替代，不需用户配合删动态。

[DONE] V4

## V5 · `hitOlder3dTotal=33` 口径校准（核 `fillHits` 实现）

### `fillHits` / `buildHitCountSql` 源码复算
- `SnsWriteProbe.kt:457-480` `fillHits(t0)`：逐条事件 `parseClear9(ev.sql)` → `c9.lo/c9.hi/c9.userName` → `:469` `HitCond(t0, c9.lo, c9.hi, c9.userName)` → `:470-472` `runReadOnlySql(buildHitCountSql(cond), 1)` → `:474` `ev.hitOlder3d = cnt` → `:476` `total += cnt`。即 33 = 导出阶段**只读 count**，不是 `changes()`。
- `SnsWriteProbe.kt:237-245` `buildHitCountSql`：
  ```
  select count(*),min(snsId) from SnsInfo where createTime < <t0>
    [and snsId > lo] [and snsId < hi]
    [and userName='<user>']
    and hex(content)<>'' and hex(attrBuf)<>''
  ```
  ⛔ **没有 `sourceType` 条件**（不复刻 `& -9` 的 64 值白名单）；`parseClear9`（`:117-138`）抠出的 `sourceTypes` **不进入 `HitCond`**（`docs/_r49_rc_impl_review.md:203` 谓词清单仅 createTime/snsId/userName/BLOB 四项）。
- **本轮 `lo/hi = null`**：N1 SQL WHERE 为 `userName=<verity> AND sourceType IN (64 含 bit3 值)`，**无 `snsId` 区间** ⇒ `c9.lo/c9.hi` 皆 null（`:133-134`），`snsId` 两行条件都不拼。旁证：`BLOCK w2` 的 `rng=1 max=3x@?` —— 只有 1 个 rangeKey 且解析成 `?`（`SnsSqlRewrite.kt:93-98` 抠不出下界返回 null、`docs/_r42_bit_paths.md:227`「只有上界/无下界形态 rangeKey 记 `?`」）⇒ 本轮微信发的是**无 snsId 分页区间的单形态清位**，与 R42 记载「6 条按 `snsId` 区间分页」（`docs/_r49_rc_stage2_verdict.md:342`、`docs/_r49_rc_probe_verdict.md:296`）形态不同，属 Q1(e) 版本·时序层的新事实（`docs/_r49_rc_stage2_verdict.md:39`）。

### 33 到底是什么（可复算）
本轮实际执行的只读 SQL 等价于：
```
select count(*),min(snsId) from SnsInfo where createTime < 1794059483 and userName='wxid_xlrbelf6hv0w22' and hex(content)<>'' and hex(attrBuf)<>''
```
= **verity 名下「createTime < t0 且 BLOB 完整」的全部行数**。N0 实测：verity 全量 `c=33`、`mn=1793016603 mx=1794053683`（全部 `< t0=1794059483`）、`wc(content>0)=33 wa(attrBuf>0)=33`、`older3d=33` ⇒ count 恰 = **33**。
而 `& -9` 这条 SQL **真实会 UPDATE 的行** = `userName=verity AND sourceType IN (64 含 bit3 值)` = N0 的 **`bit3=9`**（`sourceType=8` 一行 `-3405064359876226540` + `sourceType=10` 八行；`src0=20`、`bit1=12` 中不在白名单者不计）。`BLOCK sqlBlock9=3` 是 **3 条清位语句**被改写为 no-op（语句数 ≠ 行数）。

### 裁定：**33 = 「该好友三天外总行数（BLOB 完整）」，不是「SQL 真实命中行数」**
- 它是 `buildHitCountSql` 的**等价镜像 count**（`docs/_r49_rc_stage2_verdict.md:378` B4「不是真实 UPDATE 命中行数 / `execSQL` 是 void」、`docs/_r49_rc_impl_review.md:208`「`hitOlder3d` 纯函数是导出 SQL 的等价镜像」、`:203`），本轮因**无 `snsId` 区间 + 无 `sourceType` 条件**进一步退化成「该好友三天外全量行」，把 33 行里不含 bit3 的 24 行也算了进去，较真实命中面 9 行**高估约 3.7×**。lane H B4/B5 盲区（`:378-379`）本轮实测正好坐实。
- ⛔ 正确措辞：不能说「33 行三天外行被行级命中 / 被拦下」；应说「33 = verity 三天外总行数；`& -9` 真实命中面是其中含 bit3 的 9 行；`sqlBlock9=3` = 3 条清位语句被改写为 no-op；库位 `0|20 10|8 2|4 8|1` 前后一致证明这 9 行 bit3 未被清掉」。
- 附口径差异：WP `clear9=1 / dedupKeys=1`（`SnsWriteProbe.kt:79` `dedupKey` 含 SQL 全串、`:284-291` 去重折叠）vs BLOCK `sqlClear9=3`——两探针挂点相同、正则不同（`docs/_r49_rc_stage2_verdict.md:42`），3 条语句在 WP 账本被折叠成 1 事件。**不影响「改写生效」判定**（三条独立证据闭环），但勿把 3 当成 WP 命中行数。

### 会不会影响 V1 判定？**不会**
- 修订判定表（V8）以「界面是否显示」为唯一终点，`hitOlder3d` 降为机制确认项；本轮 `intercept_OK` 由「① `& -9` 原文 ② `sqlBlock9=3` ③ 库位前后不变」三条独立闭环，**不依赖 33 这个数**。
- 它只削弱 lane H 旧表「`hitOlder3dTotal>0` = 目标写行级落在三天外行上」这一辅助证据的精度，不产生新的「证据不足」。

[DONE] V5

## V6 · 阴性对照再解读（Buudaooo `sqlClear9=0`）

### 触发语义锚点
- `docs/_r42_bit_paths.md:79`（AAA 对照）：「**服务端未返回、本地有 bit3 → 清 bit3**」`10 → 2`，25 条；`:83`「`sqlSrc=0` ⇒ 清 bit3 走的是 `update` 写 `2`，不是 `& -9`」；`:87-88`「被清的 25 条只能是『服务端这次没返回』，不存在只保留最近 N 条的分页策略」。
- `docs/conclusions/moments.md:16-18`「只关 B 层再进主页 ⇒ 主页消失、`src 10→2`」；`SnsSqlRewrite.kt:8-13` KDoc「进好友主页时微信会执行这条裸 SQL 把 bit3 削掉」；`docs/_r49_rc_lifeline_review.md:85`「清位的触发语义**天生就是冲着『服务端没下发的行』去的**」；`docs/_r49_rc_stage2_verdict.md:64` 复述：「本地相册位（bit3）是『曾被好友主页相册拉取过』的缓存标记；服务端本次响应里若不再出现该行，微信就把这枚本地标记清掉」。

### 两类好友上的体现
| | 服务端下发 | 「未返回但本地有 bit3」差额 | 预期 `& -9` | 实测 |
|---|---|---|---|---|
| **不岛剧本·粥粥**（Buudaooo，全部可见，阴性对照） | 全量下发，本地带 bit3 的行服务端都还在返回 | ≈ 0 | **不该触发** | 上轮 `seen=11 matched=0 clear9=0 rewritten=0`、`BLOCK w2 sqlClear9=0 sqlBlock9=0`；明细仅 `CREATE TABLE IF NOT EXISTS StarSnsInfo` + 1 条 `cvSourceType=10 where=snsId=?` 的普通 UPDATE（加位/更新，非清位，对应 `docs/_r42_bit_paths.md:78`「服务端返回了、本地无 bit3 → 加 `2→10`」，放行非零） |
| **verity**（三天可见，实验组） | 截断，三天外 SnsObject 不下发（`docs/未完成项与计划.md:345`、`:348`） | **多**：N0 三天外 33 行、其中含 bit3 的 9 行 | **最该触发** | 本轮 N1 `matched=1 clear9=1`、`BLOCK sqlClear9=3 sqlBlock9=3`、`hitOlder3dTotal=33` |

### 裁定：`sqlClear9=0` 是**预期**，不是反例
- 结合本轮实验组结果，阴性对照 `sqlClear9=0 sqlBlock9=0` **完全预期**——`docs/_r49_rc_stage2_verdict.md:69-72`（Q2.2）早已推出「全部可见好友不该触发」，`:86`（Q2.4）明写「0 条是『预期』，不是『反例』」。
- 两类好友的差异**正好印证** `& -9` 的触发语义：它是「服务端响应 ∩ 本地 bit3 缓存」的**差额清理**。全部可见 ⇒ 差额 0 ⇒ 微信无需发；三天可见 ⇒ 差额 = 三天外仍留 bit3 的行 ⇒ 微信发 3 条清位。
- 同时反证**探针判别力**：该触发时触发（verity `matched=1`）、不该触发时不触发（Buudaooo `matched=0`），构成 V4 的双向自证，也排除 Q1(a) 的误读（`docs/_r49_rc_stage2_verdict.md:35`）。
- 更关键一层（接 V2）：阴性对照「0 差额」与实验组「有差额」共同坐实——**三天可见的截断发生在服务端响应层**，微信本地只是配合做 bit3 清理。R-C 拦的是这个「配合清理」，**拦不住「服务端不下发」**（`docs/_r49_rc_lifeline_review.md:187`、`docs/未完成项与计划.md:345`）。阴性对照不是 R-C 的适用对象（`docs/_r49_rc_probe_verdict.md:309` P-11 是基线不是判死样本），其 0 条既不判活也不判死，只作对照锚。

[DONE] V6

## V7 · 三档边界覆盖审计（进入 / 返回 / 复位，逐档标已观测 / 未观测）

### ① 进入（以「进 verity 主页」为唯一触发动作）
- **本轮已观测**：N1 `sub=reset` 后点进 `SnsUserUI` 相册主页、等 4.5s ⇒ `WP stat armed=true seen=1 matched=1 verdict=有真实写事件(matched>0)`、`WP ledger size=1/400 dedupKeys=1 clear9=1 rewritten=0 execSql=1`、`hitOlder3dTotal=33`、capture 到 `& -9` 原文；`BLOCK w2 sqlClear9=3 sqlBlock9=3`；N3 首屏「07」「11月」+ 5 个图片节点 + 昵称 verity。
- ⛔ 边界纪律：**唯一触发动作是进 verity 主页**。聊天页 / 资料页的写事件**不混入**本档：身份坐实用的 `com.tencent.mm/.plugin.profile.ui.ContactInfoUI` 资料页只用于读昵称/微信号（与 `docs/conclusions/moments.md:270-275` 同一人，消除 B8），不是写事件观测窗口。`sub=reset` 在点进主页**之前**执行（账本清零后重测）；冷启两次（pid=25263）是为让广播投递生效、发生在 `sub=reset`+`sub=arm` **之前**，不算「进入档内」的冷启子场景。

### ② 返回（切页回来 / 下拉刷新 / 后台同步回来 / 冷启）
- **本轮已观测（翻页 / 切屏子档）**：N3 **逐屏 swipe 8 次** + 每屏日期标记恒 `06 | 11月 | 11 | 2026年`、图片节点 8、末屏完整文本含「朋友仅展示最近三天的朋友圈」；翻页后 `WP stat` 与 N1 **逐项一致**（seen=1 matched=1 clear9=1 hitOlder3dTotal=33，**无新增写事件**）、`BLOCK w2` 与 N1 **逐项一致**（sqlClear9=3 sqlBlock9=3）、`op=sql` 库分布 `0|20 10|8 2|4 8|1` 与进主页前**逐项一致**。⇒ **「翻页 8 次 + 账本无新增」是返回档的实测，不是未测**（红线）。它同时排除 Q1(e)「首屏不发、需翻页才发第二组」（`docs/_r49_rc_stage2_verdict.md:39`）与「翻页能翻出三天外动态」。
- **本轮未观测**：下拉刷新、后台同步（`NetSceneSnsSync` getType=214，`docs/_r49_rc_probe_verdict.md:456`）回来、以及「进主页之后」的冷启再进。按 `docs/_r49_rc_probe_verdict.md:456` 返回档预期：下拉刷新用新响应覆盖内存列表（首屏回三天内）、冷启钩子 reinstall + `snsDb` 重捕获但**库位磁盘态不变**；这些 `[需真机]`。

### ③ 复位（关两个 R42 开关 / 关探针 / 对方改回全部可见 / 清缓存）
- **本轮全部未观测**；⛔ **不许写「同返回」**（`docs/_r49_rc_lifeline_review.md:292`「复位 ≠ 返回」、`docs/_r49_rc_probe_verdict.md:457` 独立成档、`docs/_r49_rc_lifeline_review.md:289-297` 单列一档）。四个子动作一个都没跑：`op=kvget` 四开关全 `true`（未关）、`sub=arm` 后未 `disarm`、未请 verity 改回全可见、未清缓存。
- ⛔ **必须写明的两个状态差异**：
  - **已写进库的 bit3 是磁盘状态，不随开关/冷启回滚**：R-C 保住的 `sourceType` 位与 `[已删除]\n` content 前缀是**持久微信库残留**（`docs/_r49_rc_lifeline_review.md:293`、`:185`、`docs/_r42_wechatmagician.md:42`），R42 已把 `undoInsert/rollbackStep/restore/show` 整段删除、当前源码**无任何回滚路径**（`docs/_r42_purge_report.md:51-56`）；`docs/conclusions/moments.md:214-217` snsId 无唯一约束 ⇒ 对方改回全可见还可能引入重复条 / 顺序异常（`docs/_r49_rc_lifeline_review.md:295` `[推断]`）。
  - **进程内账本冷启清零**：`SnsWriteProbe.kt:260`（账本环形 + 进程内冷启清零）、`:419-420`「冷启 memArmed 恒 false, 须重新 arm」、`:427-430` `sub=reset`、`:431-435` `sub=arm`；`docs/_r49_rc_probe_verdict.md:456`「账本内存态重建/冷启清零；**库磁盘态不变**」。
  - ⇒ 二者一个是**微信库磁盘状态**、一个是**模块内存账本**，**不会互相回滚**：冷启只让 WP 账本归零（本轮因此必须重新 `sub=reset`+`sub=arm`），但库里保住的 bit3 依旧在；关开关 / 清缓存也不回滚已落的位。⛔ 这正说明「关开关」不是零成本操作（见 V3）。
- ⛔ **本轮没做哪一档 ⇒ 哪个结论不能外推**：没做**复位档** ⇒ ① lane H「可实现」格要求的「三档边界可复现」（`docs/_r49_rc_stage2_verdict.md:341`）不成立（本轮判证伪，不依赖它）；② 不能外推「库位持久残留在对方改回全可见后是否引入重复条 / 顺序异常」「清缓存后 `SnsInfo` 行是否整个消失」（`docs/_r49_rc_lifeline_review.md:295`、`docs/_r49_rc_probe_verdict.md:457` `[需真机]`）、「关两开关后微信原生界面是否恢复」。⛔ 但**不影响 V1 证伪判定**：证伪只需「进入档机制生效 + 翻页档界面不显示」这一终点事实，复位档缺失不提供任何「界面其实能显示」的反证。

> 一句：进入档 ✅ 已观测；返回档翻页子档 ✅ 已实测（下拉 / 后台同步 / 冷启 ❌ 未观测）；复位档 ❌ 全未观测。磁盘位不回滚 ≠ 内存账本冷启清零；复位档缺失只限制边界外推，不动摇证伪档位。

[DONE] V7

## V8 · 最终判定表（修订版，以「界面是否显示」为终点）

### 记法（全部可复算，不引入不可观测量）
- `T` = verity 三天外行 `S_A` 非空 ∧ `content/attrBuf` 完整 ∧ `t0` 运行时校准（`docs/_r49_rc_stage2_verdict.md:332`、`docs/_r49_rc_stage1_result.md:56-58`）；本轮 `t0=1794059483`、`older3d=33`、`wc=33 wa=33` ⇒ `T=true`。
- `scene_OK` = `T=true` ∧ 探针自证（实验组 `WP dump` 有完整明细 / 阴性对照干净 / 六钩子 installed / `armed=true` / 身份坐实）。
- `intercept_OK`（**机制确认项**，三项全可复算）：① `WP dump` 抓到改写前 `& -9` 原文且 `rewritten=0`；② `BLOCK w2 sqlBlock9>0`（改写拦下）；③ 进主页/翻页后 `op=sql` 的 verity `sourceType` 含 bit3 分布与进前**逐项一致**（bit3 保住）。⛔ `matched/clear9/hitOlder3dTotal` **不再作为分档变量**，只作 `intercept_OK` 佐证（V5 已证 33 是镜像 count）。
- **`UI_shown`（唯一终点变量）** = 在 verity `SnsUserUI` / 时间线里**逐屏翻页到加载终止**后，出现 `createTime < t0` 的动态（日期标记/内容为准）**且**不再出现「朋友仅展示最近三天的朋友圈」截断提示；`UI_shown=false` = 逐屏翻页穷尽仍只三天内 + 末屏出原生截断提示。

### 修订三态表
| 判定 | 触发条件（可复算） | 终点 |
|---|---|---|
| **R-C 可实现** | `scene_OK` ∧ `intercept_OK` ∧ **`UI_shown=true`**（且三档边界可复现） | 用户可见事实达成 |
| **R-C 证伪（证死）** | `scene_OK` ∧ `intercept_OK` ∧ **`UI_shown=false`**（机制完整闭环生效：写了 `& -9` 原文 / 拦了 / bit3 保住，但逐屏翻页穷尽仍只三天内、末屏原生截断提示、库位前后不变） | 用户可见事实证否 = **机制生效但目标不达成** |
| **证据不足** | `scene_OK=false`（探针没自证 / 场景没触发 / t0 未校准）∨ `intercept_OK` 三项任一无法判定（SQL 原文被 1500 字符截断看不出 `-9` / `sqlBlock9` 与库位快照矛盾 / 双探针分歧）⇒ 终点事实测不到 | 判据前提缺失 |

### 相对 lane H §8.2 的四条修订（逐条）
1. **废除「证死 = 全 0 写事件」**（`docs/_r49_rc_stage2_verdict.md:342`、`:369`）：全 0 只说明场景没触发 / 探针没抓到，归**证据不足**，不是证死。
2. **取消 E2**（`:352`、`:470` V-6）：`intercept_OK` 成立且翻页穷尽后 `UI_shown=false` ⇒ **直接判证伪**，不再悬空成「证据不足」——机制生效已被确认，界面不显示就是决定性反证。
3. **`matched/clear9/hitOlder3d` 降级**：从「可实现/证死」的必要条件降为 `intercept_OK` 的机制确认佐证（V5 口径校准）。
4. **唯一分档变量 = `UI_shown`**，以用户可见事实为终点，不再把「有无写事件」当唯一判据。

### 什么结果等于被证死 / 等于可实现（明确写出）
- **= R-C 被证死**：`scene_OK ∧ intercept_OK ∧ UI_shown=false`。即「该触发的场景里、探针也自证有效、R-C 的写时拦截确实跑通并把 bit3 保住了，但用户逐屏翻页到加载终止，界面仍只显示三天内且出现微信原生『朋友仅展示最近三天的朋友圈』」。⛔ 仅限「R-C 在当前**客户端 Java DB 层写时拦截**路线」上不可行，不外推 native 层 / 服务端 / 响应层（沿用 `docs/_r49_rc_stage2_verdict.md:370`）。
- **= R-C 可实现**：`scene_OK ∧ intercept_OK ∧ UI_shown=true`，且三档边界可复现（lane H `:341` 的边界要求保留在「可实现」侧）。

### 本轮落档
| 变量 | 本轮实测 | 值 |
|---|---|---|
| `scene_OK` | N0 `T=true`（t0=1794059483 / older3d=33 / wc=33 wa=33）；WP arm 前后自证、阴性对照干净、N1 dump 有明细、`installed hooks=4`、身份坐实消 B8 | **true** |
| `intercept_OK` | ① N1 `WP dump` `sql=... & -9`、`rewritten=0`；② `BLOCK sqlBlock9=3`；③ N3 翻页后 `op=sql` `0|20 10|8 2|4 8|1` 与进前逐项一致 | **true** |
| `UI_shown` | N3 逐屏 swipe 8 次恒 `06|11月|11|2026年`、图片节点 8、末屏「朋友仅展示最近三天的朋友圈」、账本无新增、库位不变 | **false** |

⇒ **本轮落在「R-C 证伪（证死）」格，置信度：高**。
理由：三个前置全部由**可复算的只读观测**闭环，终点由**用户可见事实**（8 屏 + 原生截断提示 + 截图 `_kb/r49_verity_album.png`）直接判定，无一条依赖推断；lane H 列为「证据不足」的所有替代解释（首屏空 / 没翻够页 / 探针坏 / 身份映射 / 干扰致数据不可信）本轮均被专门观测排除（V1/V3/V4/V6）。
置信度为何不是「中」：未覆盖的 V7 复位档与 V2 响应层 A/B 归因**都不改变「界面不显示」这一终点事实**，只影响边界外推与归因精度，故不降低档位置信度。

[DONE] V8

## V9 · 收尾建议（不改代码 / 不指挥主会话）

### ① R-C 在客户端 Java DB 层写时拦截路线上还有无可挽救变体
- **本路线已到上限，无可挽救变体**。本轮实测证明「保住 `SnsInfo.sourceType` 的 bit3」与「界面显示三天外动态」之间**无因果通路**：首屏吃响应内存对象（`docs/conclusions/moments.md:259-260`）、翻页游标被服务端三天边界截断（V2）、且 `& -9` 本身是「服务端没下发行」的差额清理（`docs/_r42_bit_paths.md:79`）。任何仍停留在「改 `SnsInfo.sourceType` / 拦 DB 写 / 补 bit3」的变体都绕不开这个上限（`docs/_r49_rc_lifeline_review.md:187`「不能让响应列表凭空多出三天外的 id」）。
- 真正能触及目标的变体必须**上移到响应 / 渲染层注入**：在 `w5.onSceneEnd`（时间线）/`h3.N(Lq54/jc5;...)`（相册，`docs/未完成项与计划.md:320-321`）before 把本地 `SnsInfo` 三天外行 merge 进响应 `LinkedList<SnsObject>`，并去掉服务端「仅三天」截断标记——正是 `docs/未完成项与计划.md:346/:348` 6.3 所述「用本地已归档的 `attrBuf`+`CreateTime` 补回界面」「注入发生在渲染层可以绕过」。但：
  - ⚠️ 这**不是 R-C 变体，而是换路线**：R42 已**物理删除乙方案整族**（`docs/conclusions/moments.md:27-30`「响应注入 `SnsTimelineMerge` / `SnsAlbumMerge` 已废弃、代码已删除，勿按 git 历史恢复」；`docs/_r42_wechatmagician.md:63-66` 路线乙 + 第 3 期注入）；且 `docs/未完成项与计划.md:322` 已证「相册是读库渲染，`h3.N` 注入本身不产生 UI 效果，只能借 `l5.o` 写库，属绕路」——绕回 DB 层又撞回本轮上限。
  - ⚠️ 用户此前**只授权验证 R-C**（`docs/_r49_rc_stage1_result.md:3-5`），未授权重开响应层注入。
  - ⇒ 裁定：**R-C 路线内无可挽救变体；任何可能有效的方案都属换路线 / 重开已删族，需用户重新授权，不在本轮范围**。本裁决不建议、不指挥改 `SnsSqlRewrite.kt`、不写微信库。

### ② 剩余盲区清单（如实登记）
| # | 盲区 | 本轮状态 / 锚点 |
|---|---|---|
| B1 | **native 层写库**：WCDB 若清位走 native `sqlite3_*` / `SQLiteStatement.execute*`，Java before 钩子看不到 | `docs/_r49_rc_stage2_verdict.md:375`；本轮 `sqlBlock9=3` 说明至少 3 条走 Java `execSQL` 已被拦，是否存在第二条 native 清位通道 `[需真机]` |
| B2 | **服务端响应组装 / A-B 区分**：三天外 snsId 是否下发、截断标记如何组装 | `docs/_r49_rc_stage2_verdict.md:376`、`docs/未完成项与计划.md:345/:348`；本轮无响应层 hook，V2 的只读判据未跑 `[需真机]` |
| B3 | **UI 因果关联**：`dev.py ui` 只给可见条数，无法把「某条三天外动态」与「某次写事件 / 某个 bit3」绑定；三门槛逐层在哪挡住未逐项实测 | `docs/_r49_rc_stage2_verdict.md:377`、`SnsWriteProbe.kt:9-10`、`docs/未完成项与计划.md:329` |
| B4 | **`type` 独立上限**：verity 动态 `type=2`（视频）本就不进相册网格、命中 0 条 | `docs/未完成项与计划.md:330-331`；即便响应层注入成功，`type∈{1,15}` 仍挡这批样本（与 bit3 无关） |
| B5 | **`hitOlder3d` 口径**：镜像 count、无 sourceType / 区间退化（V5） | `docs/_r49_rc_stage2_verdict.md:378-379`、`SnsWriteProbe.kt:237-245` |
| B6 | **复位档残留 / 重复条 / 清缓存**（V7③） | `docs/_r49_rc_lifeline_review.md:293-295`、`docs/conclusions/moments.md:214-217` `[需真机]` |
| B7 | **单形态清位差异**：本轮 `& -9` 无 `snsId` 分页区间（`rng=1 max=3x@?`），与 R42「6 条分页」形态不同（V5） | `docs/_r42_bit_paths.md:227`、`docs/_r49_rc_stage2_verdict.md:39` Q1(e) `[需真机]` |

### ③ 最小可执行收尾动作（≤3 条，全只读 / 不改代码 / 不写微信库 / 不需用户配合好友）
1. **固化终点事实**：归档 `_kb/r49_verity_album.png` + N3 逐屏文本（「8 屏恒三天内 + 末屏原生截断提示」）作为 V8 `UI_shown=false` 的证据，不再重复进 verity 主页（避免扰动）。纯本地只读整理。
2. **区分 V2 的 A/B（可选、需另一次自然进主页）**：用现成 `SnsSqlSpy`（`docs/未完成项与计划.md:325`，只读旁听、默认关闭）或 `SnsHuntProbe` P-06（`docs/_r49_rc_probe_verdict.md:304`）抓一次响应层 / 相册查询 SQL 的 snsId 列表与 `stringSeq` 游标，与 N0 的 33 个三天外 snsId 比对 ⇒ 判定「服务端不下发」还是「本地门槛挡住」。⛔ 只改归因精度，不改 V1/V8 档位；属观测建议，不指挥改代码。
3. **对照锚复核**：对阴性对照「不岛剧本·粥粥」（Buudaooo）用只读 `op=kvget` / `op=sql` 确认「全部可见」权限确实生效，固化 V6 的「差额=0 ⇒ 不该触发」语义。纯只读，不碰好友设置。

> ⛔ 以上三条均为「判定 + 判据 + 下一步观测建议」；本 lane 全程不碰设备、不改配置、不指挥主会话改代码。是否采纳由用户决定。

---

## 裁决边界声明
- 证死结论**仅**覆盖「R-C 在当前客户端 Java DB 层写时拦截路线」，不外推 native 层 / 服务端响应层 / 渲染层注入（沿用 `docs/_r49_rc_stage2_verdict.md:370`）。
- 全程只读：未改任何源码 / 测试 / 配置 / 其它报告，未碰 adb / 真机 / Gradle / 装包 / kvset / git；唯一写入文件 = 本 `docs/_r49_rc_stage3_verdict.md`。好友一律昵称（verity / 不岛剧本·粥粥），wxid 仅作 SQL 参数。必读材料全部按 `offset/limit` 分段读取（单段 ≤ 100 行），未整读 > 300 行文件；不确定项标 `[推断]` / `[需真机]`。

[DONE] V9
[DONE] ALL

