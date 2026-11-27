# R21d 角标贴错消息（"下标乱了"）根因取证 · fresh-eyes Lite

> 本文件为唯一可写产物。全程只读，禁改源码/单测/其它文档；禁跑 gradle / `_junit.sh`；禁碰 adb / 装机 / 设备 / 开关 / 让用户发图；禁 git。
> 分档：`【源码实证】` / `【dex 实证】` / `【日志实证】` / `【推断·高|中|低】` / `【UNKNOWN】`。
> 【推断】绝不写成【已证】；可行性分析 ≠ 已实现 ≠ 已验证。
> 判据对着**用户可见的角标落点**写，不对内部计数器（`bound` 只是"绑定过的 View 集合"，与开关无关，`_r21d_badge_chain.md:494-497`）。

## 报障与确认事实（主会话前置，引用不重证）
- 用户 2026-11-27 报障"已阻止撤回下标乱了"。
- AskUserQuestion 确认：现象 = **角标贴到别的消息上**（相邻/上下那条，非被撤回那条）= R21d 最严重失败模式"下标错位"；时机 = **最近才出现**（以前正常）= 回归，非一直存在的缺陷。

## Q1 错位机制分类裁决（① pos 坐标系 / ② 台账撞号+talker 核对失效 / ③ 锚点选错 / ④ 其它）

### ① pos 坐标系错位 —— 【dex 实证·高】排除
- 11-16 新 `classes4.dex`（唯一含 `q0/q3/t3` 的 dex；其余 13 个 dex 均 0 命中）反汇编：
  - `q0.getItem(I)` code_off=`0x48b698`：`invoke q3.g0()` → `if-ge`/`h0` 判 header/footer → `sub-int/2addr v2, v0`（pos − headerCount）→ `iget q0.g:t3` → `invoke-interface t3.getItem(I)`（`:0018`/`:001b`）。与 `_r21d_badge_chain.md:101-121` 的旧结论逐指令一致。
  - `q3.g0()` code_off=`0x48b880` = `d:SparseArray.size()`（header 数），未变。
  - `q3.h0(I)` code_off=`0x48b84c` 仍是 `pos >= headerCount + coreCount` 的 footer 判定。
  - `q0` 虚方法表仅 5 个（`E/getItem/getItemId/getView/<init>`），**未覆写 onBindViewHolder**；`q3` 仍持有 2 参 + 3 参 `onBindViewHolder`（code=`0x48ba2c`/`0x48ba58`）⇒ `HintBadge.kt:153-158` 的 hook 点仍活。
- 【日志实证·旧】`resolved/chat=0.99~1.00`（`_r21d_badge_chain.md:296/:307`）仅证 10-25 旧版本，不照搬；本次以 11-16 dex 为独立证据。
- ⇒ 即使微信在 11-16 升级，`getItem(pos)` 换算未变。①**不成立**。

### ② 台账 msgId 撞号 + talker 核对失效 —— 【源码实证】漏洞存在；【推断·高】最符合现象
- 台账跨会话：`GroupDb.listRescued()` 只按 `at DESC` 取 5000 条、无 talker 过滤（`GroupDb.kt:253-264`），`rescued` 表跨版本累积（`_r21d_badge_chain.md:326-328`，真机 10-28 已 208 条）。
- 核对会被跳过：`HintBadge.kt:336-337` 要求 `!curTalker.isNullOrBlank() && rescued.talker.isNotBlank()` 才核对，两侧任一空即放行。
- 台账 talker 可为空串：`AntiRevoke.kt:710` 传入 `talker ?: ""`；`rescued.talker TEXT NOT NULL`（`GroupDb.kt:177`）只禁 NULL 不禁空串；`listRescued` 用 `?: ""` 读出（`GroupDb.kt:262`）⇒ 空串条目对核对完全免疫。
- `talkerOf(h9)` 反射 `getTalker` 失败返回 null（`HintBadge.kt:501-504`）⇒ 同样跳过。
- 撞号后对账兜底也弱：非文本消息无 contentOk（`HintBadge.kt:363` 仅 `TYPE_TEXT` 调用）；文本消息 probe 取台账 origContent 前 12 字（`HintBadgeLogic.kt:67-77`），群消息前 12 字是 `wxid_xxx` 前缀，发送者不同则对不上（会拒绝），但同发送者/私聊同文案则可能对上而误贴。
- ⇒ 撞号消息被当作"被救"贴上角标，落点在**当前会话那条同号消息**（视觉上就是相邻/上下的另一条）⇒ 与用户确认现象一致。②**最可能**。

### ③ 锚点选错 —— 【源码实证】不能造成"贴到别的消息"
- `selectMessageRow` 只在**单个 item 根**的子节点里挑高节点（`HintBadgeLogic.kt:126-142`），`resolveRowAnchor` 从 `target.getChildAt(idx)` 取回（`HintBadge.kt:881-898`），`addBadgeBelowMessageRow` 挂到该 item 内（`HintBadge.kt:838-843`）。
- `itemViewOf` 从 holder 的 View 字段挑 `isRvChild` / 面积最大者（`HintBadge.kt:415-451`），Field 属于该 holder 实例。
- ⇒ 此支最多造成**同一 cell 内**贴错位置（如贴到时间戳下、贴到 item 左上角——10-25 曾发生并已修，`_r21d_badge_chain.md:310-357`），不会跨到相邻 cell。用户报障是"贴到别的消息"，故③**不是本次现象的机制**。
- 残留风险【推断·低】：`itemViewCache` 按 holder 类名缓存首次选出的 Field（`HintBadge.kt:439`），首次 bind 时几何未稳定可能选错 Field，但仍在同 holder 内，不跨 cell。

### ④ 其它 —— 【UNKNOWN】
- 11-16 微信 dex 更新可能改变 item 内部 View 结构（影响锚点几何）或 `getContent()` 返回值形态，但**无 11 月真机 UI 截图 / dex 内 item 类实证**，不裁决。
- 弱引用 `bound`（`HintBadge.kt:141-142`）+ 2 秒 sweep 可能让旧 View 角标残留一瞬，但 sweep 在 view detach 时移除（`_r21d_badge_chain.md:494-497`），不解释稳定错贴。

[DONE Q1]

## Q2 "最近才出现"回归源定位（85eb5e6 talker 核对 / contentOk 口径 / 其它提交）

### 源码改动时间线（文件系统 mtime + 源码注释；纪律禁 git，故非 git 实证）
- `HintBadge.kt` = 2026-10-28 02:29；`HintBadgeLogic.kt` = 10-28 00:05；`HintTextLogic.kt` = 10-28 02:03 ⇒ 角标核心逻辑自 85eb5e6（10-28 03:22 提交）后未再改。
- `GroupDb.kt` = 11-07 21:39，但源码内唯一 11-07 注释是删除 `sns_deleted` 灰标台账（`GroupDb.kt:529-530`），rescued 表 DDL（`:172-189`）与 `listRescued`（`:253-274`）无 11 月改动痕迹。
- `RevokeRescue.kt` = 11-11 18:48，唯一 R21d 注释是废弃 insertHint（`RevokeRescue.kt:470`），`record` 的 talker/origContent 写入（`:90-98`）未变。
- `EntryReceiver.kt` = 11-25 00:32，hintbadge 命令分派（`:787-797`）仍在。
- ⇒【推断·中】10-28 之后无角标逻辑回归提交，与主会话初查一致；但 mtime 只能证"文件被写过"，不能证"哪一段改过"，故非 git 实证。

### 85eb5e6 的 talker 核对 —— 【源码实证】是修复，但留残余漏洞；不是"最近才出现"的充分原因
- 核对逻辑（`HintBadge.kt:335-352`）确实能挡住"台账 talker 非空 + h9.getTalker 非空 + 两者不等"的跨会话撞号（计数 `talkerMismatch`，日志前 8 条打 `cur/led/orig`）。
- 但"两侧任一为空即跳过"（`:336`）把最危险的两类放行：台账 talker=`""`（来源 `AntiRevoke.kt:710` 的 `talker ?: ""`）、h9 反射拿不到 talker（`:501-504`）。⇒ 撞号仍贴错。这是 10-28 就已存在的残余通道，不能单独解释 11-27 才出现。

### contentOk 口径改动 —— 【源码实证】同口径，不是错贴源
- 台账 `origContent` = `orig.content`（微信 message 表完整原文，群消息含 `wxid_xxx:\n` 前缀，见 `RevokeRescue.kt:94` 与 `_r21d_badge_chain.md:366-368`）；`h9.getContent()` 取同一 message 对象的 content（`HintBadge.kt:370-373`）⇒ 两侧同口径。
- `stripSender` 只用于**显示摘要**（`HintTextLogic.kt:167-170/:198` 经 `badgeText`），**不参与 contentOk 对账**（`HintBadgeLogic.kt:67-77` 直接对 `origContent` 折平）⇒ 不存在"剥前缀后与 getContent 不同口径"的问题。
- 也不会恒 false / 恒 true：折平 + 前 12 字 probe + emoji 代理对处理（`HintBadgeLogic.kt:67-87`）逻辑自洽。⇒ 该改动至多影响"漏画/正常贴"，不造成"贴到别的消息"。

### 真正"最近才出现"的回归源 —— 【推断·中~高】数据累积/撞号显现，而非源码回归
- 依据：①角标源码 10-28 后未动（mtime 实证）；②11-16 新 dex 的 pos 链路未变（Q1① dex 实证）；③台账跨版本单调累积、撞号概率随条目数上升（`_r21d_badge_chain.md:326-328`）；④ talker 空串条目使核对长期失效。
- ⇒ 最符合"以前正常、最近才出现"的是：**近期某次恢复聊天记录 / 换机 / 备份导入或台账累积使 msgId 撞号从无到有，恰落在有角标的会话**，talker 核对因空串跳过 ⇒ 错贴显现。
- 【UNKNOWN】只读取证无法确认用户是否做过恢复/换机，也无法读设备台账确认撞号条目与 talker="" 的实际分布；须用 Q3 现场判据证伪/坐实。

[DONE Q2]

## Q3 可复现的只读取证判据（现有 dump|stat|fake|unfake 能打什么 / 缺什么 / 现场布法）

### 现有探针能力（【源码实证】，命令分派 `EntryReceiver.kt:787-797`）
| 探针 | 能打什么 | 不能打什么（关键缺口） |
|---|---|---|
| `stat`（`HintBadge.kt:1060-1065`） | `enabled/msg/hint/rescued/bound/added/binds/chat/resolved/hits/missed/verifyFail/masked/lastMiss/talkerMismatch` | 全是**计数器**，无一条对应"用户肉眼可见的角标落点"；`talkerMismatch` 只计"两侧非空且不等"的，空串跳过的不计 |
| `dump`（`HintBadge.kt:1032-1049`） | 遍历 `bound`（**绑定过的 View 集合**，非屏幕全量），每条打 `u=<无符号 msgId> hit=<bool> badge=<按 hitMap 现算文案> txt=<item 子树可见文字前 12 字> host/LL/FL` | `badge=` 是**按台账现算**（`:1040`），不是从 View 上实际读出的文案 ⇒ 证明"按台账该贴什么"，**不能**证明"屏幕上实际贴了什么"；不打 talker（当前/台账）、origContent 原文、type/origType、pos、adapter 类名 |
| `fake(id)`（`HintBadge.kt:1007-1023`） | 塞 `forceSet` + 对 `snapshot()` 立即贴 `BADGE_ONLY` | **跳过 talker/type/contentOk 全部闸门**（走 `forced` 分支，`:314/:398-403`）⇒ 只能验 pos 链路，**不能复现撞号错贴**（撞号恰恰是这些闸门应挡的） |
| `unfake`（`:1025-1029`） | 清 `forceSet` + sweep | — |

### 唯一定案 Q1② 所缺的字段（按优先级）
1. **当前 item 的 `h9.getTalker()` + 台账该 msgId 的 `rescued.talker`** —— 直接判"核对是被挡住还是被跳过"；现有 dump 无此字段，`talkerMismatch` 计数器也无法覆盖空串跳过（【源码实证】缺口）。
2. **台账该 msgId 的 `origContent` 原文前 40 字** —— 现有只打 `badgeText` 摘要（截断 + 剥 `wxid_` 前缀，`HintTextLogic.kt:131-133/:167-170`），撞号时无法核对摘要与当前消息正文是否同源。
3. **实际贴到 View 上的角标文案**（从 `tv.text` 读）—— 才能"对着用户肉眼可见的落点"。
4. `type/origType`、`pos`、`adapter 类名`、`headerCount(g0())` —— 运行时兜底排除 pos 错位（dex 已排除，但现场可自证）。

### 主会话可执行的只读取证布法（三段法变体，参考 `_r21d_badge_chain.md:139-149`）
1. `op=logclear` 清 LogX（`CLAUDE.md:78`）；微信置前台。
2. 进入**出现错贴的会话**，滚到"被撤回那条 A"和"被贴错那条 B"同屏；`op=hintbadge sub=dump` 落盘。
   - **落点判据（肉眼可见）**：若角标出现在 B 上，而 dump 中 B 那条 `badge=<摘要>` 在 `txt=` 里找不到对应正文 ⇒ **撞号错贴**坐实（摘要来自另一会话的 A）。若 A/B 的 `u=` 相同 ⇒ msgId 撞号坐实。
   - 若角标落在 `txt` 不含该内容的 item 上且 `u` 与 msgsnap 不符 ⇒ pos 错位（dex 已排除，机会低）。
3. `op=hintbadge sub=stat` 看 `talkerMismatch`：
   - 有错贴且 `talkerMismatch=0` ⇒ 无法区分"双方 talker 相同"与"一侧为空跳过" ⇒ **必须补 dump 的 talker 字段**才能定案。
   - `talkerMismatch>0` 但错贴仍在 ⇒ 核对只挡了部分，仍有漏网通道。
4. `op=forward sub=msgsnap` 拿 A/B 的 msgId，与 dump 的 `u=` 对照（`_r21d_badge_chain.md:145`）。
5. **反向对照**（`CLAUDE.md:29` 硬要求）：`kvset show_hint=false` 等 3 秒看角标是否消失（`_r21d_badge_chain.md:485-519` 已证双向生效）⇒ 排除"其它 UI 残留冒充角标"。
6. ⚠️ 不要单用 `fake` 复现本故障：`fake` 绕过闸门，证明不了 talker 核对的行为。

### 结论
- 【源码实证】现有 `dump/stat/fake/unfake` **能定位"贴到了哪个 View / 哪条 msgId"**，但**不能唯一定案"为什么 talker 核对没挡住"**——缺 talker / origContent 原文 / 实际角标文案三类字段。
- 【推断·高】要唯一定案 Q1②，须最小改 `HintBadge.dump()` 增上述字段（纯观测、不改行为），或新增 `sub=inject` 人为造撞号台账条目（干预实验，非只读取证）。

[DONE Q3]

## Q4 修法候选与风险（不动台账/不动 pos 约束下能否修 / 每支风险与变异验证法）

> 前置：Q1①（pos）与 Q1③（锚点跨 cell）已排除，故修法**不需要动 `getItem(pos)` 换算、不需要动台账表结构**即可覆盖最可能的 Q1②。但以下均为**可行性分析**，未实现、未验证（诚实边界）。

| 候选 | 做法 | 约束 | 风险 | 变异验证法（对肉眼落点） |
|---|---|---|---|---|
| **A. 收紧 talker 闸门（最小，推荐先评估）** | `HintBadge.kt:336-337` 改为：台账 talker 为空**或**当前 talker 取不到时**抑制角标**（不再放行），把"宁可漏判"的口子反向收紧为"漏画优于错贴" | 不动台账、不动 pos | 【推断·中】历史台账 talker="" 条目**全部不再贴角标** ⇒ 用户可能感觉"角标又没了"（漏画回归）。治标不治本：talker 真相同（同群同人）的撞号仍挡不住 | 造一条 talker="" 的台账条目 + 当前会话同号消息：改前贴到相邻消息、改后不贴（角标消失） |
| **B. 强化 content 对账兜底** | 非文本消息也做某种对账，或文本 probe 由前 12 字改为**完整串/更长窗口**比对，降低"不同消息碰巧同前缀"误 true | 不动台账、不动 pos | 【推断·中】`getContent()` 在折叠/富文本态可能返回不同形态 ⇒ 大面积漏画；完整串比较对超长消息有成本 | 构造同号不同内容两条消息：改前贴错、改后拒绝贴 |
| **C. 补台账 talker 来源** | `AntiRevoke.kt:710` 的 `talker ?: ""` 改为"拿不到 talker 就不记台账/换稳定标识" | **动台账写入侧** | 【推断·高】撤回拦截时拿不到 talker 会丢台账 ⇒ **防撤回本体失效**，比角标错贴严重得多。不推荐 | 拦截瞬间 talker 缺失场景：确认台账是否仍写入、防撤回是否仍生效 |
| **D. 台账按 talker 分桶** | `listRescued` 按当前会话 talker 过滤，`hitMap` key 改 `(talker,msgId)` | **违反"不动台账"约束**（改 GroupDb 查询 + HintBadge 缓存） | 历史 talker="" 条目无法归类；改动面大，需 Pro lane + 真机 | 跨会话同号两条：只贴当前会话那条 |
| **E. 只加观测（不改判据）** | `HintBadge.dump()` 增 Q3 所列字段（talker/origContent/type/pos/实际角标文案） | 不动台账、不动 pos、不改行为 | 零行为风险；但要落码（go 到落码层），且需重新装包验证 | 改后 dump 能直接显示"某条错贴 item 的台账 talker 为空 / 与当前不符" |

- **在"不动台账/不动 pos"约束下**：A / B / E 均可行；C / D 不可行（须动台账）。
- ⚠️ 但 A/B 属于**判据改动**，必须在 Q3 现场判据坐实"确实是 talker 跳过导致的撞号"之后再动，否则违反 `CLAUDE.md` D3「判据连塌即停」与"可行性分析 ≠ 已实现 ≠ 已验证"。

[DONE Q4]

## Q5 可行性裁决 + go/no-go（①可行/②条件可行/③不可行；go 到哪层；升级与证伪条件）

### 裁决：② **条件可行**
- "可行"的部分：根因机制已闭环到【源码实证】+【dex 实证】，现场只读取证（现有 dump/stat/msgsnap 三段法）**立即可 go**。
- "条件"的部分：①"最近才出现"的具体触发（恢复/换机/台账分布）【UNKNOWN】；②现有探针缺 talker/origContent/实际角标文案，无法现场坐实"为什么核对没挡住"；③修法 A/B 是判据改动，未见现场判据前不得落码。

### go 到哪层
| 层 | 裁决 | 说明 |
|---|---|---|
| 只读取证（现有探针三段法） | **GO** | 主会话按 Q3 步骤 1-5 执行，不改任何源码 |
| 干预实验（新增 dump 字段 / inject 撞号探针） | **条件 GO** | 需 team-lead 批准落码（最小改 `HintBadge.dump()`），走 Pro lane（Hook 类改动）；装包前按 `CLAUDE.md` §四⑥ 交叉复核 |
| 落码修法（A/B） | **NO-GO（待判据）** | 现场 dump 未证明 talker 跳过前不得改判据；D3 判据连塌即停 |

### 升级条件（对**用户肉眼可见角标落点**）
- 现场 dump 显示：错贴 item 上的角标摘要与 `txt=` 正文不匹配（摘要来自另一条消息），且 `stat` 的 `talkerMismatch=0` 或 `< 错贴条数` ⇒ 升级到"落码观测增强"（E），再决定是否收紧闸门（A）。
- 或 A/B 弹出后**同一会话同屏两条消息出现同一摘要** ⇒ 直接坐实撞号漏挡，升级 Pro lane 修法。

### 证伪条件（对**用户肉眼可见角标落点**）
1. 错贴 item 的 `u=` 与 msgsnap 的 A msgId **不一致** ⇒ pos 错位分支成立，推翻 Q1②（但 dex 已排除，机会低）。
2. 所有错贴 item 的 `badge` 摘要都能在 `txt=` 里找到对应正文 ⇒ 不是撞号，转向"锚点/微信 item 结构变化"分支（Q1④）。
3. `talkerMismatch>0` 且错贴 item 的两侧 talker 都非空且不等却仍贴出 ⇒ talker 核对代码未生效（装的不是当前源码 / hook 未加载），转向"装包版本"排查。
4. 关开关后错贴角标**不**消失 ⇒ 不是 HintBadge 机制，整条链路推翻。

[DONE Q5]

## 三件套

### ① 一句话根因裁决 + 分档
**最可能根因 = 台账跨版本累积造成的 msgId 撞号，叠加 talker 核对"两侧任一为空就跳过"的残余漏洞，使角标被贴到当前会话那条同号消息（视觉上即相邻/上下那条）——【源码实证】漏洞存在 + 【推断·高】现象归属；pos 坐标系经 11-16 新 dex 独立反汇编证明未变【dex 实证·高】、contentOk 同口径【源码实证】、锚点只作用于单 cell【源码实证】，三支均排除。"最近才出现"更可能是撞号数据从无到有的显现而非代码回归【推断·中~高】，具体触发仍【UNKNOWN】。**

### ② 证伪 / 升级条件（对用户可见角标落点）
- **升级**：dump 中错贴 item 的角标摘要与 `txt=` 正文不匹配（摘要来自另一条）+ `stat` 的 `talkerMismatch=0` 或小于错贴数 ⇒ 升级"落码观测增强（E）→ 视结果收紧 talker 闸门（A）"。
- **证伪 1**：错贴 item 的 `u=` 与 msgsnap 的 A msgId 不一致 ⇒ pos 错位，推翻 Q1②。
- **证伪 2**：所有错贴 item 的摘要都能在 `txt=` 找到对应正文 ⇒ 非撞号，转向 Q1④（微信 item 结构/锚点）。
- **证伪 3**：两侧 talker 非空且不等却仍贴出 ⇒ 运行的非当前源码 / hook 未加载，转向装包版本排查。
- **证伪 4**：关开关后角标不消失 ⇒ 非 HintBadge 机制，整链路推翻。

### ③ 最小下一步 + 责任方
- **最小下一步**：主会话按 Q3 三段法在"出现错贴的会话"现场取 `dump` + `stat` + `msgsnap`，并做关开关反向对照；判据只认"角标出现在哪条消息、摘要是否与该消息正文同源"。
- **责任方**：主会话 / team-lead 执行只读取证（GO）；若判据指向 talker 跳过，另派 **Pro lane** 落码最小观测增强（仅扩 `HintBadge.dump()` 字段，不改行为），装包前按 `CLAUDE.md` §四⑥ 交叉复核；修法 A/B 待判据坐实后再议。本子代理不碰设备、不落码、不跑 gradle。

[DONE ALL]

## 诚实边界（未取证标 UNKNOWN）
- 【UNKNOWN】用户是否做过恢复聊天记录 / 换机 / 备份导入；设备台账中撞号条目与 `talker=""` 的实际分布；错贴那两条消息的 msgId / talker / 原文摘要。
- 【UNKNOWN】11 月真机 UI 截图 —— 微信 item 内部结构是否变化（影响 Q1④ 锚点几何）未取证；`h9.getContent()` 在 11-16 微信版本是否仍返回含 `wxid_` 前缀的完整原文（本次只反汇编了 adapter 类 `q0/q3`，未查 `h9` 类）。
- 【推断·中】"10-28 后无角标逻辑回归提交"依据文件系统 mtime + 源码注释，**非 git 实证**（纪律禁 git，未跑 git log）；mtime 只能证"文件被写过"，不能证"哪一段改过"。
- 【推断·高】Q1② 是"现象归属"推断，不是现场坐实；唯一定案须 Q3 增强判据。
- 未跑 gradle / `_junit.sh`；未碰 adb / 设备 / 开关；未让用户发图；未改任何源码 / 单测 / 其它文档；未跑 git。唯一写入文件 = 本文。
- **可行性分析 ≠ 已实现 ≠ 已验证**：Q4 五支候选均未落码、未上机、未单测。
