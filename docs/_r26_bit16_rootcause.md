# R26 朋友圈导出「只处理旧动态」根因分析（bit16 语义）

<!-- DONE:5/5 -->

## 待答 5 问

### 1. bit4(16) 在 8.0.48 的真实语义是什么？

**结论：bit4(16) 不是 8.0.48 的「可见位」。它是旧结论里从旧版时间线 SQL 反推出来的一个 flag，
8.0.48 里新落库的动态根本不置它。证据强度：强（反证）。**

**支持「不是可见位」的证据（强）：**
- 实测最新 50 行 `flag ∈ {2, 4098, 5122}`，含 bit16 的行数 = **0**；
  但同一台设备朋友圈 UI dump 里明确显示「8分钟前」的新动态。可见动态没有 bit16 ⇒ 直接反证。
- 旧结论的推导链是「时间线白名单 `localFlag in (16,2064,18,2066,24,2072,…)` 每个值都置 bit4，
  且删动态时先清 bit4 落成 2 ⇒ 所以 bit4 = 可见位」。这条链的**第一步从未在 8.0.48 上验证过
  「新动态落库时 bit16 是否真的被置位」**；它把「删除会清位」偷换成了「可见必须置位」。
- 7 条老动态（6月～10-16）全 `flag=50(32|16|2)`，50 条新动态（3.3 小时内）全无 bit16，
  时间上形成清晰分界 ⇒ 更像「客户端/服务端 flag 语义随版本变更」，而不是「新动态不可见」。

**候选真实语义（均未证实，弱）：**
- bit16 可能是旧版「详情已拉取 / 本地已完整同步」标记，8.0.48 已废弃或挪作他用；
- 也可能是「自己的动态」标记（见 Q2 假设 B）——但现有 7 条老动态样本太少，定不死。

### 2. `50` / `2` / `4098` / `5122` 各是什么组合？

**解码：**

| flag | 二进制 | 组合 | 出现 |
|---|---|---|---|
| 2 | `0b10` | bit1 | 新动态 45 条 + 全部老动态公共位 |
| 4098 | `0b1_0000_0000_0010` | bit12(4096)\|bit1(2) | 新动态 2 条 |
| 5122 | `0b1_0100_0000_0010` | bit12(4096)\|bit10(1024)\|bit1(2) | 新动态 3 条 |
| 50 | `0b11_0010` | bit5(32)\|bit4(16)\|bit1(2) | 老动态 7 条 |

**可确认的观察：**
- **bit1(2) 是所有行（新旧）的公共位** ⇒ 最接近「该行有效/非墓碑」，但**不是「可见」判别**：
  旧结论称删除第一步把 flag 清成 2（bit1 仍置位），所以「已删被拦下」的行也带 bit1，
  按 `(localFlag & 2)` 过滤会把已删垃圾一起导出。
- **bit4(16)/bit5(32) 只出现在 7 条老动态**（flag=50），新动态一条都没有。
- **bit12(4096) 出现在 5 条新动态、bit10(1024) 只在 3 条新动态**（5122）。

**可检验假设（证据强度均弱）：**
- 假设 A「时间/版本分界」：bit16/bit32 是旧客户端或旧服务端语义写下的值，新动态不再写。
- 假设 B「作者维度」：bit16/bit32 标记「自己的动态」vs 好友动态（7 条老动态里 `wxid_myzsbcu` 疑似本人）。
- 假设 C「内容维度」：bit12/bit10 与 `type`/`sourceType` 或「视频/多图」相关。

**一条能区分假设的真机实验（只读，禁用 sub=show）：**
1. `op=snsg sub=dump n=200` → 拉 `(snsId, userName, createTime, type, sourceType, flag)` 时间序列。
2. 判读：
   - 若 flag 沿 `createTime` 出现「某时间点之前全含 bit16/bit32、之后全不含」的**干净分界**
     → 支持假设 A（版本/语义变更），bit16 是历史遗留、不是可见位。
   - 若 `flag=50` 与 `flag=2` 在时间上**交错**、且 `flag=50` 集中在本人的 `userName` → 支持假设 B。
   - 若 `4098`/`5122` 集中对应某几个 `sourceType`/`type` → 支持假设 C。
3. 再用 `op=snsg sub=one <id>` 各挑一条 `flag=4098` 与 `flag=5122`，
   比 `content` / `attrBuf` / `postBuf` 长度差异 → 判断 bit10 是否与「视频/多图」相关。

### 3. 导出源应当用什么 SQL 判据筛「用户当前看得见的动态」？

**推荐：去掉 `(localFlag & 16) != 0` 过滤，只保留 `createTime` 倒序键集分页。**
理由：这是「防撤回语义」的主数据源，**漏采 = 用户动态被删后永久找不回来**，比多采严重一个量级；
而 flag 判据已被 Q1/Q2 证伪（bit16 不是可见位、bit1 又分不出「可见」和「已删被拦」）。
宁可多带，不可漏采。

**可直接粘贴的 WHERE 子句（替换 `SnsGuard.listCurrentSns` L534-537 的 where 段）：**

```sql
select snsId,userName,createTime,type,sourceType,localFlag,content
from SnsInfo
where (createTime < ? or (createTime = ? and snsId < ?))
order by createTime desc, snsId desc limit N
```

**每个条件的理由 + 会漏掉/多带什么：**

| 条件 | 理由 | 漏掉 | 多带 |
|---|---|---|---|
| 去掉 `localFlag & 16` | bit16 已证伪；`localFlag & 2` 也不能区分可见 vs 已删被拦 | 基本不再漏真动态 | 广告/推荐位、已删被拦的行、历史遗留隐藏行 |
| 保留 `createTime < ? or (= ? and snsId < ?)` | 复合键集分页，同一秒多行不丢 | 仅当某可见行 `createTime=0`（实测没有） | 无 |
| `order by createTime desc, snsId desc` | 从新到旧，防撤回优先留最新的 | 无 | 无 |
| **暂不**按 `sourceType` 排除广告 | 实测 `sourceType∈{0,2,6,8,10,130}`，但**没有证据说哪个值=广告**，贸然黑名单会漏真动态 | 若误删真动态的 sourceType | （留到 Phase 2） |

**替代候选评估：**
- `(localFlag & 2) != 0`：可见行都带 bit1，但「已删被拦」也带 → 排除不了垃圾，且不解决广告，**不如无过滤**。
- `sourceType not in (…)`：等 `dump` 定死广告值后再加，属 Phase 2 精修，本轮不动。
- 广告的兜底：`renderText` 已经输出 `来源=sourceType`，多带出来的广告 txt 事后可筛可删，**可逆**；漏采不可逆。

### 4. 改判据后对现有 7 条已导出动态有没有回退？

**回退：无。** 7 条 txt 已在盘上，改判据后有三道去重都会把它们跳过：
`scanExportedIds`（扫目录收集已导出 id）、`SnsScanCursor.first(snsId, exported)`（本轮 + 磁盘去重）、
`txt.exists()`（兜底）。它们只会被 `sk++` 跳过，不会重写、不会覆盖、不会重复拷图。

**行为变化（期望的）：** CURRENT 源会返回更多行（50 新 + 7 旧 + 可能的其它历史/广告）。
7 旧被去重跳过，50 新进入「写 txt + 拷图」路径 → 正好修复本次卡点。

**`SnsScanCursor` 去重/切源逻辑是否需要跟着改：不用。**
- 去重按 `snsId`（`seen` HashSet + `exported` Set），跨源（CURRENT vs ARCHIVE）依然正确。
- 切源 `page.isEmpty() → nextSource()` 仍成立：CURRENT 现在返回非空页，扫完自然切 ARCHIVE。
- 唯一观察项（非正确性问题）：`MAX_ROWS_PER_RUN=500` 按「检查过的行数」计数（含被去重跳过的）。
  无过滤后单轮可能被历史/广告行占满预算；但多轮 tick/poke 会持续推进（已导出的被跳过、游标前进），
  只需在验收日志里留意 `rows` vs `written` 的比例即可。

### 5. 风险与回滚

**最坏情况：**
| 情形 | 后果 | 严重度 | 可逆性 |
|---|---|---|---|
| 导出大量垃圾（广告/推荐位/已删历史） | `written` 暴增、目录多出非内容 txt | 中 | 可逆（删文件；renderText 带 `来源=sourceType` 可事后筛） |
| 漏采真动态 | 动态被删后永久找不回来 | **高（不可逆）** | 本方案**消除**了它（去掉了唯一会漏的 flag 门槛） |
| 微信界面异常 | 无 | 0 | 不涉及 |

**微信界面零风险的依据：** `listCurrentSns` 是纯 SELECT（`liveRows` 只读游标），
不写微信库、不改 flag、不触发 `restoreVisibility`。唯一写库路径 `restoreVisibility` 在 delete 钩子里，本次不动。

**最小回滚路径：**
- 改的只有 `listCurrentSns` 一行 WHERE。回滚 = 恢复 `(localFlag & 16) != 0` 子句 + 重编译重装。
  无 DB 写、无状态迁移，不产生需要清理的脏数据。
- 已多导出的 txt 是「加法」，不覆盖旧数据；用户手动删多余目录，或保留等 Phase 2 `sourceType` 精修后再重导。
- 不建议为此加「配置键开关」做 A/B：判据是一行纯读代码，直接改 + 真机验收更快。

## 结论

**根因一句话：** `SnsGuard.listCurrentSns` 用 `(localFlag & 16) != 0` 筛「可见动态」，但 bit4(16)
在 8.0.48 里不是可见位——新动态落库为 `flag=2`（仍可见），全都不含 bit16，于是导出永远只扫到
7 条带 bit16 的老动态。

**推荐判据（代码片段 + 位置）：** 删掉 `SnsGuard.kt` L535 的 `(localFlag & $VISIBLE_BIT) != 0` 子句，
只保留键集分页：

```sql
select snsId,userName,createTime,type,sourceType,localFlag,content
from SnsInfo
where (createTime < ? or (createTime = ? and snsId < ?))
order by createTime desc, snsId desc limit N
```

（`SnsGuard.listCurrentSns` L534-537；先不做 `sourceType` 广告过滤，防漏采优先。）

**建议的真机实验步骤（含预期观察值，全部只读、禁用 sub=show）：**
1. `op=snsg sub=dump n=200` → 看 flag 沿 createTime 是否出现干净分界（老行含 bit16/bit32、新行不含）。
   预期：分界明显 ⇒ bit16 是版本遗留，非可见位。
2. `op=moments sub=export` → 摘要应从 `rows=7` 跳到 `rows≥57`（50 新 + 7 旧），`written>0`，
   `imgCopied`/`imgAlready` 随滚动缓存增长。
3. 交叉验证 flag 组合含义：`op=snsg sub=one <id>` 各挑 `flag=4098` 与 `5122` 一条，比 content/attrBuf/postBuf 长度。

**证据不足之处：**
- bit1(2) 到底是不是「非墓碑」位：只知它新老可见行都带、删除第一步后仍带，未见「物理删除后」的对照。
- bit16/bit32/bit12/bit10 各自的真实语义仍未知（假设 A/B/C 待 dump 定）。
- 广告的 `sourceType` 值映射未定死（已知出现 0/2/6/8/10/130，不知哪个是广告）——故 Phase 1 不做黑名单，多带可逆。

[DONE]