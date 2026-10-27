# R27 M3 朋友圈「防删除」时间线数据源根因分析（v2，含 src=0 决定性证据复核）

> 版本：微信 8.0.48 (2588, GP版)。包名 `com.lx.wxgroup`。
> 纪律：任何「谁调用谁」结论必须给 `invoke-*` 地址 + 所在方法 + 调用者链；时序结论标注假设；判不准写「证据不足」。
> ⚠️ v2 关键变化：**推翻「好友删除拦不住」的旧结论** —— 根因是 `sourceType=0`（"死来源"）被时间线过滤，与「删除」无关。M3 的判据样本选错了。

---

## 0. 现场事实摘要（真机实测，含 team-lead 补充与更正）

### 场景 A：用户删自己发的私密动态（19:51，snsId=-3405840021235297608, type=2, src=4）
- 微信调 `SQLiteDatabase.delete` **3 次**，全被 SnsGuard 拦下（`param.result=1` 骗微信"已删"）
- 日志：`SNS visibility restored snsId=... 2 -> 18 rows=1` → `SNS blocked delete snsId=... snap=true flag=18` → `SNS archived ... ok=true`
- 库里行还在，`localFlag=18`；**个人页（SnsUserUI）里能看到**

### 场景 B：好友 verity 删一条动态（20:18，snsId=-3405827759906942447, t=1793016948, type=2, **src=0** ⚠️ 已更正）
- 删除前基线（20:11 `sub=dump`）：`localFlag=2`
- 删除后：**零拦截日志**（`SNS blocked` / `SNS delete seen` / `SNS visibility` / `SNS archived` 全无）
- 库里该行仍在，`localFlag` 仍 2（`content=337B` 未清）
- 用户没刷新时还能看到；下拉刷新后消失
- ⚠️ **「零拦截日志 ⇒ 没走本地 SQL」这条只对 `delete`/`execSQL` 成立，强度降为【中】**：
  我们只挂了这两个方法，**排除不了** `update`/`updateWithOnConflict`/`insertWithOnConflict`/`SQLiteStatement.executeUpdateDelete()`。
  但反证较强：行内容 337B 未清、`localFlag` 未变 ⇒ 倾向「真没写」（见 Q2）。

### 场景 B2：断网冷启实验（team-lead 补充，决定性）
断 WiFi+移动数据 → 杀微信 → 冷启 → 进朋友圈：**列表照常渲染全部动态**（只多一个「无法连接网络」气泡），被删那条依然不在。
⇒ 列表数据在**本地**可渲染，不是「服务器不下发所以没有」。

### 场景 C：活着的 src=0 动态也不显示（team-lead 补充，决定性）
20:29/20:31 两次 dump 最近 12 条，**全部 `localFlag=2`**，`src` 只有 0/2/4：
```
-3405820845342780801  wxid_4850728   20:29:32  type=1 src=2
-3405821384538516869  wxid_oyfy3qy   20:28:28  type=3 src=2
-3405822959101603201  wxid_7lfu9h7   20:25:20  type=2 src=2   ← 界面「王贝拉」
-3405823948479524355  xiaoyun53718   20:23:22  type=1 src=2   ← 界面「宁波塑之华人事」
-3405824444311989648  wxid_55uz575   20:22:23  type=3 src=2   ← 界面「北京拾柴王韧」
-3405824697470283239  wxid_xlrbelf   20:21:53  type=2 src=0   ← ❗ 活的，没删过
-3405824845406522742  wxid_mtqdsp9   20:21:35  type=1 src=2   ← 界面「吴泽庆」（6 图）
-3405824909980790092  wxid_myzsbcu   20:21:27  type=2 src=4   ← 自己
-3405827759906942447  wxid_xlrbelf   20:15:48  type=2 src=0   ← 被删那条
-3405830652137369075  wxid_xlrbelf   20:10:03  type=2 src=0
...
```
截图逐屏核对：界面在 20:22:23（北京拾柴王韧）与 20:21:35（吴泽庆）之间**紧挨着，没有 20:21:53 那条**。
⇒ **一条从未被删除、完好的 `src=0 type=2` 动态，同样不出现在时间线里。**
⇒ `wxid_xlrbelf` 的全部 3 条动态**清一色 `type=2 src=0`**，其他联系人都是 `src=2`/`src=4`。
⇒ 另有：verity 三条动态 `content=337B attr=174B` **完全相同**（20:10:03 / 20:15:48 / 20:21:53）。
  - 可能只是「同作者、同形态、短文本」的正常现象（337B 主要是 protobuf 结构开销，正文差异小）；也可能是 verity 在测"1""2""3"这类同形内容。
  - **静态无法判定内容**（需真机 `op=snsg sub=one <id>` 看 content 字节）。**不影响 src=0 结论**，仅备注。
⇒ **verity（wxid_xlrbelf）标为【无效样本】**：其权限已实地核对（聊天/朋友圈/微信运动 + 「不让他看」关 + 「不看他」关，排除屏蔽/仅聊天），但其动态 src=0 不进时间线，与删除无关。

### 场景 D：正常动态的 flag 值
- 全部 `localFlag=2`；`localFlag=50` 只在旧动态（R26 已定案，`localFlag & 16` 不是可见位）

---

## Q1. 主时间线（SnsTimeLineUI）刷新的完整数据通路 —— 修正版

[DONE]

### 结论（置信度：高）

时间线渲染有**两条路径**，都受 `sourceType & 2 != 0` 过滤，**都不按 `localFlag` 过滤**：

1. **冷启/离线（本地）路径**：查 `SnsInfo` 表 —— `d2.t5/u5/v5/C4/C1/d2/i4` 全部带 `where (sourceType & 2 != 0)`。
2. **在线刷新路径**：`c3`(NetSceneSnsTimeLine) → `w5.onSceneEnd`(SnsLogic) 内存分发（v1 已证不读 `d2`）；响应处理里 `c3.N`(handleNormalResp) 也查 `select snsId from SnsInfo where (sourceType & 2 != 0) limit 10`。

**关键修正**：v1 我只证了「刷新不读表」，漏了「列表其实按 `sourceType & 2` 过滤」。两条路径里，`sourceType` 的 bit1(2) 才是 8.0.48 真正的「时间线可见位」，不是 `localFlag` 的 bit16。

### 证据（invoke 地址 + 调用者链）

**A. 时间线 DB 查询带 sourceType 过滤（本地渲染路径）**
- `d2.t5` 的 SQL（classes3.dex string[73079]）：`select *,rowid from SnsInfo where (sourceType & 2 != 0) AND SnsInfo.stringSeq > '...'`
- 调用者链：`w5.d`（SnsLogic 的 DB 加载方法）`invoke-virtual d2->t5 @0x7c`（classes3.dex，`_r27_callers.py` 实测命中）
- 同源 SQL 还被 `d2.C1/C4/d2/u5/v5/i4` 引用（`_r4_dexref.py` 对 string[73077]/[1178] 的反查结果）
- `c3.<init>` 也调 `d2.t5 @0x10d`（NetSceneSnsTimeLine 构造时预热读库）

**B. 在线刷新响应处理也查 sourceType**
- `c3.N`（= handleNormalResp，code=0x3642fc）引用 string[73121] `select snsId from SnsInfo where (sourceType & 2 != 0) limit 10`（`_r4_dexref.py` 反查）

**C. 分页键是 stringSeq，不是 localFlag**
- string[73079] 尾段 `AND SnsInfo.stringSeq > '` 实锤：时间线分页靠 `stringSeq` 键集，`localFlag` 完全不在 WHERE 里。

### 为什么 src=0 被过滤（把现场和字节码对上）

`0 & 2 == 0` ⇒ 不满足 `(sourceType & 2 != 0)` ⇒ 被排除。现场场景 C 里 `wxid_xlrbelf` 的 3 条 `src=0` 全部进不了时间线查询结果，与「删没删」无关。

### ⚠️ v1「现场反证第 2 条」作废（样本 polluted）

v1 曾写：「若刷新会重查表，这条 `flag=2` 的行理应在时间线里继续出现；实测刷新后消失 ⇒ 列表不是从表里读出来的」。**这条现场反证作废**：
- 因为「被删那条」和「从未被删的对照条」**同为 verity 名下 `src=0`**，全都不显示 ⇒「刷新后消失」这个现象**无法区分**「删除导致」和「本来就不显示」。
- **字节码反证仍然有效（硬证据）**：`w5.onSceneEnd`（code=0x394640）全方法 invoke 表无 `d2` 调用 —— 这条不依赖任何样本，直接证「在线刷新分发不读表」。
- **verity 三条动态标为【无效样本】**：不能用于 M3「防删除」的任何判据（它们 src=0，与删除无关，见 Q6）。

---

## Q2. 服务器在「好友删除动态」时下发什么 —— 修正版

[DONE]

### 结论（置信度：中偏高，一条假设待证伪）

**「好友删动态」不产生本地 DELETE（这条对 `delete`/`execSQL` 确证）；且被删动态从时间线消失的直接原因是它的 `sourceType=0` 被过滤，不是「被删」本身。** 服务器下发的形态有二选一，尚未定死：
- **形态 1（更可能）**：这条动态的 `sourceType` 位（bit1=2）在客户端从来就没被打上 / 被打掉了，服务器只是「不再把它归入时间线分类」。
- **形态 2（待证伪）**：服务器在响应里带 `SnsObject.DeleteFlag=1`（protobuf 里确有 `DeleteFlag` 字段，见下），客户端据此清掉 `sourceType` bit1（`UPDATE SnsInfo SET sourceType = sourceType & ~2`）。

### 证据

1. **反证（强，倾向「真没写」）**：场景 B 行内容 337B 未清、`localFlag` 未变 ⇒ 连「UPDATE 清可见位」都没发生（自我删除那条路是「先 UPDATE 再 DELETE」，好友删除这条路两步都没走）。但注意此反证对 `update` 类方法**不覆盖**，强度【中】。
2. **`SnsObject` protobuf 有 `DeleteFlag` 字段**（`Lcom/tencent/mm/protocal/protobuf/SnsObject;->DeleteFlag:I`，classes3.dex fields）——这是「服务器显式下发删除标记」的**候选载体**，但**尚未取证**它是否在好友删除时被置位、客户端是否据此清 sourceType。
3. **`SnsObject` protobuf 没有 `Source/SourceType` 字段**（53 个字段里无）⇒ `sourceType` 不是服务器直接下发的字段，是客户端落库时算出来的（见 Q3 语义）。
4. **`UPDATE SnsInfo SET sourceType = sourceType & <mask>`** 存在（`d2.S7` = `updateFilterTimeLine`，code=0x3e1cbc，SQL string[37346]）——客户端有「清 sourceType 位」的能力，是形态 2 的机制载体。

### 对方案的含义

关键转变：**M3 要处理的不再是「拦删除」，而是「让被过滤的 src=0 动态重新可见」**。而「可见」的判据是 `sourceType & 2`，不是 `localFlag`。

---

## Q2b. `sourceType` 的确切语义（新增，本轮核心）

[DONE]

### 结论（置信度：高 —— 语义已实锤；「为什么这位好友全 src=0」证据不足）

**`sourceType` 是客户端落库的位字段（DB 列），不是服务端直接下发的字段。** 位定义（部分实锤）：

| 位 | 值 | 语义 | 证据 |
|---|---|---|---|
| bit1 | 2 | **「时间线可见/时间线来源」** | 时间线查询一律 `sourceType & 2 != 0`；`i2.onGYNetEnd` 用 `addSourceFlag(2)` 打上（见下） |
| bit2 | 4 | 「私密/仅自己」（src=4 是自己私密动态，只上个人页不上时间线） | 现场：自己 `src=4` 只在 SnsUserUI 可见 |
| bit7 | 128 | 「广告」 | `select ... where (sourceType & 128 != 0)`（string[73075]/[73076]） |
| — | 0 | **「死来源 / 无来源」**（dead source） | `SnsInfo.isDeadSource()` = `sourceType==0`；`isSourceExist()` = `sourceType>0` |

### 证据（invoke + 方法名，全部 classes3.dex）

1. **bit1(2) 的赋值点 = `NetSceneSnsClassifyTimeLine.onGYNetEnd`**
   - `i2` = `com.tencent.mm.plugin.sns.model.NetSceneSnsClassifyTimeLine`（`i2.onGYNetEnd` 内 const-string 自证，tag `@__classify_timeline`）
   - `0x0082 const/4 v2, 0x2` → `0x00a7 invoke-virtual SnsInfo.addSourceFlag {...,v2}` ⇒ **`addSourceFlag(2)`**，即把「时间线来源」位打上。
   - 语义：「classifytimeline」响应把某条动态**分类进时间线**时，客户端给它 `sourceType |= 2`。

2. **存储路径的兜底赋值 = `SnsInfoStorageLogic.replace`**
   - `l5` = `com.tencent.mm.plugin.sns.model.SnsInfoStorageLogic`（`l5.o` 内 const-string 自证，方法名 `replace`）
   - `0x0081 invoke-virtual SnsInfo.isSourceExist` → `0x0085 if-nez`（sourceType==0 时）→ `0x0087 invoke-virtual SnsInfo.addSourceFlag`
   ⇒ 落库时若 sourceType 为 0，补一个默认来源位。

3. **sourceType==0 = 「死来源」的字面证据**
   - `SnsInfo.isDeadSource()`（code=0x3db658）：`iget field_sourceType` + `if-nez`（sourceType==0 判定）
   - `SnsInfo.isSourceExist()`（code=0x3dbc24）：`iget field_sourceType` + `if-lez`（sourceType>0 判定）
   ⇒ 微信自己把 sourceType=0 命名为「死来源」。

4. **清位能力 = `updateFilterTimeLine`**
   - `d2.S7`（= `SnsInfoStorage.updateFilterTimeLine`，code=0x3e1cbc）：`UPDATE SnsInfo SET sourceType = sourceType & <mask>`

### 客户端 or 服务端？（team-lead 问的第 5 点）

- **是客户端落库的位**（`addSourceFlag` / `UPDATE sourceType` 都是客户端代码；`SnsObject` 里没有这个字段）。
- **但取值受服务端驱动**：bit1 是否被置位取决于 `NetSceneSnsClassifyTimeLine` 响应里**服务端把哪些动态分类进时间线**。
- ⇒ **可被我们改**（DB UPDATE 即可置位），但若服务端每轮分类响应都把它清掉，改一次可能被下一轮覆盖。这一点是 M3 方案成败的关键，需真机验证（见 §证伪实验）。

### 「为什么 `wxid_xlrbelf` 全 src=0」——证据不足

现场这位好友 3 条动态**清一色 src=0**，其他好友都是 src=2/4。可能原因（均未证实，勿猜）：
- (i) 该好友的动态从未进入 classifytimeline 分类响应（服务端对 TA 的特殊处理/关系标记）；
- (ii) 该好友的动态来源（内容里 `ObjectDesc` 的「来源」字段）落到 sourceType=0；
- (iii) 该好友在你本机的某种关系状态（如「不看 TA / 被拉黑」）导致 sourceType 位被清。

**判定办法见 §证伪实验**，不在这里猜。

---

## Q3. 「把本地已保留的行补回时间线」能落在哪一层 —— 修正版

[DONE]

### 结论（置信度：中）

既然真正的可见判据是 `sourceType & 2`，**最干净的落点不是「往响应里塞对象」，而是「给被过滤的保留行把 `sourceType` 的 bit1 补回去」**—— 与现有 `restoreVisibility`（补 localFlag bit16）同构，只是目标是正确的那个位。

### 候选方案（按可行性排序）

| # | 层 | 具体做法 | 可行性 | 风险 |
|---|---|---|---|---|
| **1（推荐）** | DB 位修复 | 刷新/同步后，对本模块保留集合里「sourceType 缺 bit1」的行，`UPDATE SnsInfo SET sourceType = sourceType \| 2 WHERE snsId=?`（走我们自己的库句柄，不拦微信写） | **高**：只是 UPDATE 一个客户端位，机制与 SnsGuard 同构 | 低；但**待证伪**：服务端 classify 响应是否会下一轮又清掉 |
| 2 | 响应合并 | hook `w5.onSceneEnd` / `c3.onGYNetEnd`，把本地保留 SnsObject merge 进响应列表 | 中：需构造/反序列化 SnsObject，元素类型待取证 | 中高：动核心分发 |
| 3 | adapter 数据源 | hook `WxRecyclerAdapter.getData()` 注入 `oh3/p`(ImproveInfo) | 低：`oh3/p` 27 个 lazy 字段构造极脆 | 高：塞非法对象最可能崩 |

### 方案 1 的证据基础

- `sourceType` 是客户端 DB 列（Q2b 已实锤），`UPDATE ... | 2` 是纯客户端写。
- 时间线查询 `sourceType & 2 != 0`（Q1 已实锤）⇒ 置位后冷启/离线路径**直接**会把它读出来。
- 与现有 `restoreVisibility` 同构：SnsGuard 已有 `updateFlag(db, snsId, flag)` 统一写位路径（`SnsGuard.kt`），可复用反射/句柄基础设施，只是目标列从 `localFlag` 换成 `sourceType`。

### 关键风险（必须真机验证，否则方案 1 可能无效）

**服务端会不会在下一轮 classifytimeline 响应里把 bit1 再清掉？** 若会，单次 UPDATE 只能保到下一次刷新。这是「客户端位」方案 vs「服务端驱动」的胜负手。验证方法见 §证伪实验 步骤 2/3。

---

## Q4. 方案成本 / 风险 / 回滚 + 能自证的真机判据

[DONE]

### 方案名：**「M3 来源位修复」（SourceFlag-Fix）**（替代 v1 的 Response-Merge）

### 实施步骤

1. **先跑证伪实验（§证伪实验）**，确认「src=0 是这位好友固有属性」还是「删除/分类驱动被清」。
2. **若确认是固有 src=0**：M3 的判据样本作废，进入「是否要让 src=0 动态显示」的产品决策（可能不属于「防删除」范畴）。
3. **若确认是删除/分类驱动被清**：新增 `SnsTimelineSourceFix.kt`，复用 SnsGuard 的库句柄 + `updateFlag` 同款路径，对保留行 `sourceType |= 2`。
4. 开关复用 `Keys.ANTIREVOKE_SNS`，关即停。全程 `runCatching`，失败只打日志。

### 改动面

| 文件 | 改动 | 风险 |
|---|---|---|
| `feature/SnsTimelineSourceFix.kt`（新） | sourceType 位修复 hook/逻辑 | 主风险点 |
| `feature/SnsGuard.kt` | 复用其 `snsDb`/`updateFlag` 基础设施（若抽公共函数则最小改） | 低 |
| 探针 | `op=snsg sub=srcstat|srcfix` | 低 |

### 风险清单

| 情形 | 后果 | 严重度 | 可逆性 |
|---|---|---|---|
| 服务端每轮清位 | 修复被覆盖，功能只在两轮之间短暂生效 | 中 | 可逆 |
| 置位把「真的不该显示」的动态（广告/拉黑）也放出来 | 时间线多出不该有的条目 | 中 | 可逆（按保留集合白名单，只修我们确认过要保的） |
| `sourceType` 语义在未来版本漂移 | 功能失效 | 低 | 可逆 |
| UPDATE 误伤 | 改错行 | 中 | 按 snsId 精确匹配 + 保留集合白名单 |

### 回滚

- 关 `ANTIREVOKE_SNS` 即停；卸载/重装全还原。
- **有 DB 写**（`sourceType |= 2`），但**可逆**：回滚 = `sourceType &= ~2` 或直接删行重拉。写入前记录原值，失败可倒序回滚（同 `restoreVisibility` 的 updateFlag 计数套路）。

### 能自证的真机判据（用户可见）

⚠️ **判据必须用 `src=2` 的好友做，不能用 verity**（verity 是 src=0 无效样本）。

- **主判据**：一位 **`src=2` 的好友**删一条动态 → 下拉刷新 → 那条**还在时间线里**。
- **反向对照**：关开关 → 同一位 `src=2` 好友再删一条 → 下拉刷新 → 那条**消失**。
- **稳定性对照**：连续刷新 3 次，被保的那条**仍在**（证明没被服务端下一轮清掉 —— 这是方案 1 成立与否的硬判据）。
- **冷启对照**：杀微信重进朋友圈 → 被保的那条**仍在**（证明 DB 位修复在离线/冷启路径生效）。

---

## Q5. `restoreVisibility`（`2 -> 18`）需要动吗 —— 复核后定论

[DONE]

### 结论（置信度：高）

**`restoreVisibility` 的前提（「时间线白名单要求 localFlag 置 bit16」）在 dex 里查无实据 —— team-lead 复核正确，我复核也确认：`strs "localFlag in"` 全 dex 0 命中；`localFlag` 相关 SQL 只有列名/DDL/索引/`field_localFlag`。** 时间线的真实过滤是 `sourceType & 2 != 0`，**根本没有 `localFlag` 条件**。因此：

- **`restoreVisibility`（`2->18`）对「好友删除」从未参与**（好友删除无 DELETE，不触发它）。
- **对「自我删除」场景 A 也纯无用**：场景 A 里个人页能看到，是因为**行没被删**（DELETE 被拦），不是因为 `2->18` 写回了 bit16（个人页/时间线都不按 localFlag bit16 过滤）。
- **可以整个删掉，零风险**（它写的是一个没有任何查询在用的位）。

### 复核证据

- `python tools/_r6_snsdex.py strs "localFlag in"` → **0 命中**（全 dex）。
- `strs "localFlag"` 全部结果（classes3.dex 13 条 + classes4/7/8）：只有 `field_localFlag` / DDL（`CREATE TABLE SnsInfo (... localFlag INTEGER ...)`）/ `snsLocalflagIndex` 索引 / toString 格式串 `' localFlag='`。**没有任何 WHERE 子句用 localFlag**。
- 与 SnsInfo 相关的唯一 `localFlag IN (...)` 是 classes7.dex 的 `DELETE FROM FinderFeedItem WHERE localFlag IN (0,1,2)` —— **那是 FinderFeedItem（视频号）表，不是 SnsInfo**。
- 结论：`SnsGuard.kt` L232 注释里的「时间线白名单 `localFlag in (16,2064,18,…)`」是**旧版本或臆断**，8.0.48 不成立。**该注释应改为「8.0.48 时间线可见位 = sourceType & 2」**（代码注释勘误，非本次必做）。

### 处置

- **本轮不动 `restoreVisibility` 代码**（无害无用，避免顺手改动引入回归面）。
- 但**必须纠正认知**：后续所有「可见性」判据一律看 `sourceType & 2`，别再碰 `localFlag`。

---

## Q6. sourceType 语义与样本有效性（新增）

[DONE]

### 6.1 `sourceType` 位定义（dex 赋值点 + 字段来源）

**结论：`sourceType` 是客户端落库的位字段（DB 列），不是服务端 SnsObject 直接下发的字段。** 位定义：

| 位 | 值 | 语义 | 置信度 | 证据 |
|---|---|---|---|---|
| bit1 | 2 | 「时间线来源/时间线可见」 | **高** | 时间线查询一律 `sourceType & 2 != 0`（d2.t5 等）；`i2.onGYNetEnd` 用 `addSourceFlag(2)` 打上（const/4 v2,2 @0x0082 → invoke @0x00a7） |
| bit2 | 4 | 「自己发/私密」（src=4 只上个人页） | **中**（推断） | 现场 src=4 是「自己」；未见独立 SQL 过滤证据 |
| bit7 | 128 | 「广告」 | **高** | `select ... where (sourceType & 128 != 0)`（string[73075]/[73076]） |
| bit3(8)/bit6(64) | — | 未知（上传/其它来源） | 证据不足 | `a8`(UploadPackHelper) 调 `addSourceFlag(64)`；需补全 `addSourceFlag` 各调用点常量（见 §证据不足 #5） |
| — | 0 | **「死来源」（dead source）** | **高** | `SnsInfo.isDeadSource()` = `sourceType==0`；`isSourceExist()` = `sourceType>0` |

**赋值点（谁 set 它、从哪来）：**
- `i2.onGYNetEnd`（`NetSceneSnsClassifyTimeLine`，classes3.dex）：`addSourceFlag(2)` —— 服务端「classifytimeline」分类响应驱动客户端把某条动态标记为「时间线来源」。
- `l5.o`（`SnsInfoStorageLogic.replace`，code=0x380db0）：`if (!isSourceExist()) addSourceFlag(...)` —— 落库兜底，sourceType==0 时补默认位。
- `d2.S7`（`SnsInfoStorage.updateFilterTimeLine`，code=0x3e1cbc）：`UPDATE SnsInfo SET sourceType = sourceType & <mask>` —— 清位能力（掩码精确值待取证，见 §证据不足 #6）。
- `SnsObject` protobuf **53 个字段里没有 Source/SourceType** ⇒ sourceType 一定不是服务端直发字段，是客户端从「classifytimeline 响应」+「内容来源」算出来的。

### 6.2 `sourceType=0` 是什么语义（4 个假设判定）

**结论：字面 = 「死来源」（WeChat 自己命名），效果 = 被 `sourceType & 2` 过滤、不进时间线。四个假设逐一判定：**

| 假设 | 判定 | 依据 |
|---|---|---|
| (a) 私密/部分可见 | **否** | 私密是 `src=4`（自己私密动态），src=0 是另一个值；verity 的动态不是私密（权限已核对） |
| (b) 非时间线来源（未进入 classifytimeline 分类） | **最可能** | bit1(2) 由 `NetSceneSnsClassifyTimeLine` 响应驱动；src=0 = 没被打上 bit1 = 没被归入时间线分类 |
| (c) 客户端旧版本上报缺省值 | **否** | 同一设备、同一版本，其他好友全是 src=2/4，只有 verity 是 0 —— 不是全局缺省 |
| (d) 好友关系降权 | **可能但未被证实** | verity 权限已核对（非屏蔽/仅聊天），但「服务端对某关系链不返回 classify 分类」仍是可能路径 |

**综合判断（置信度：中，倾向 (b)，(d) 为其上游原因）**：`sourceType=0` 的**直接**含义是「这条动态没有被客户端标记为『时间线来源』（bit1=0）」，所以被时间线查询过滤。**为什么只有 verity 的动态没被打 bit1**，是上游问题（服务端未把 verity 的动态归入时间线分类），静态无法定死，需真机实验（§6.4）。

### 6.3 对 Response-Merge 方案的直接影响（会不会被再过滤）

**结论：`sourceType & 2` 过滤发生在 DB 查询层（`d2.t5` 等），不在 improve 内存模型层。** 因此：

- **内存层不会按 sourceType 再过滤**：improve 内存模型 `oh3/p`（= `ImproveSnsInfo`）的 `isDieItem()` 查 `getLocalFlag() & 32`（⛔ 2026-10-28 订正：既有的 `<=0` / `& 2` 两种写法**都错**，见 `docs/_r30_62_album_gate.md` §2）、`isInValid()` 查 `l0()`(=snsId) —— **都不读 sourceType**（`_r27_disasm.py` 实测）。
- ⇒ **Response-Merge（把保留 SnsObject merge 进内存响应列表）在当次刷新里不会被「同一处 sourceType 逻辑」再滤一次** —— 它只过 `localFlag`/`snsId` 校验（保留行两者都合法）。
- ⚠️ **但 merge 只救「当次内存列表」**：冷启/DB 重建路径（`d2.t5`）仍按 `sourceType & 2` 过滤，被 merge 的 src=0 行**冷启后照样消失**。
- ⇒ **结论**：Response-Merge 与 SourceFlag-Fix **不是二选一，是互补**：
  - Response-Merge 救「当次刷新」（内存路径，不过 sourceType）。
  - SourceFlag-Fix（`sourceType |= 2`）救「冷启/离线」（DB 路径，过 sourceType）。
  - 要「刷新后 + 冷启后都还在」，两者都要做；只做其一只能覆盖一半场景。

### 6.4 判定实验设计（界面可见 + 查 sourceType）

**目标：证明「src=0 ⇒ 不显示」是因果关系，且判 src=0 是固有还是被清。**

1. **让 verity 发一条明确「公开」的纯文字动态，不要删。** 发完后的第一次拉取立刻 `op=snsg sub=one <新snsId>` 查 sourceType：
   - 若**首拉即 src=0 且界面不显示** ⇒ src=0 是这位好友动态的**固有属性**（服务端对 TA 不归入时间线分类），与「公开/私密/删除」全无关 ⇒ **M3 该场景判据彻底作废**。
   - 若**首拉 src=2 且界面显示**、之后某轮才变 0 ⇒ src 位是**被服务端/分类驱动清掉**，与删除/分类相关 ⇒ SourceFlag-Fix 可行。
2. **反向对照（用 src=2 好友）**：让一位正常好友发「公开」动态，确认首拉 src=2 且显示；再删掉，确认「删了才消失」。用这条坐实「正常好友的消失确实是删除导致」，与 verity 的「src=0 导致消失」形成对照。
3. **界面可见判据**：verity 的「公开」动态在发完第一次拉取后**界面有没有它** —— 这是最直接的因果判据（有 ⇒ src=0 不是必然；无 ⇒ 坐实 src=0 与不显示的因果）。

### 6.5 content=337B 相同的备注

verity 三条动态 `content=337B attr=174B` 完全相同。**静态无法判定是「同形短文本的正常现象」还是「同一测试内容」**（需真机 `snsg one` 看 content 字节）。**无论哪种，都不影响 src=0 结论**：src=0 过滤与 content 大小无关。

---

## 证伪实验设计（team-lead 问的第 3 点）

[DONE]

### 目标

判定「是删除导致消失」还是「src=0 本来就不显示」，并进一步判定 src=0 是「好友固有属性」还是「服务端分类驱动」。

### 实验（3 步，只读 + 用户配合，不写库）

**步骤 1 —— 判别「删除 vs 固有 src=0」（核心）**
- 让 `wxid_xlrbelf` 新发一条 `type=2` 纯文字动态，**不要删**。
- 在它发完后的**第一次拉取**（不手动刷新，让微信自己同步）后，立刻 `op=snsg sub=one <新snsId>` 查它的 `sourceType`。
- **判据**：
  - 第一次拉取就 `src=0` ⇒ src=0 是这位好友动态的**固有属性**，与删除无关 ⇒ **M3 的删除判据样本确实选错了**；「防删除」在这条上无意义。
  - 第一次是 `src=2`（且界面可见）、之后某轮才变 0 ⇒ src 位是**被服务端/分类驱动清掉的**，与删除/分类强相关 ⇒ M3 方案 1（sourceType 位修复）成立。

**步骤 2 —— 判别「src=0 是否随时间/分类被清」（方案 1 的胜负手）**
- 对一位正常好友（src=2）的动态，连续下拉刷新 3 次，每次 `op=snsg sub=one <id>` 看 sourceType 是否稳定为 2。
- 对步骤 1 里如果出现「先 2 后 0」的那条，记录它变 0 的时机（是否恰好在某次 classifytimeline 响应之后）。
- **判据**：若正常好友的 src=2 稳定、且「先 2 后 0」只在特定响应后发生 ⇒ 服务端分类驱动清位，方案 1 需要在**每次响应后再补一次位**（挂在 `i2.onGYNetEnd` 或 `c3.onGYNetEnd` 之后）。

**步骤 3 —— 反向对照（界面可见，证伪「删除无关」）**
- 关掉模块开关，让 `wxid_xlrbelf` 删一条 src=0 动态，下拉刷新。
- 换一位 src=2 的好友删一条，下拉刷新。
- **判据**：src=0 那位「删不删都不显示」，src=2 那位「删了才消失」⇒ 彻底坐实「src=0 是过滤根因，删除只是恰好同源」。

### 对 team-lead 提议实验的评估

team-lead 的「让好友新发一条 type=2 不删、刷新看还在不在」**判别力足够**（在 vs 不在 → 区分删除 vs 过滤），但**建议升级**：加「第一次拉取立刻查 sourceType」这一步，把「固有 src=0」和「先 2 后 0」分开，这是决定方案 1 是否可行的关键分叉。单一「刷新后还在不在」只能回答「是否过滤」，回答不了「位是谁清的、能不能保住」。

---

## 推荐方案（修订）

[DONE]

**方案名：M3 来源位修复（SourceFlag-Fix）**

- **先做证伪实验**，确定 src=0 是固有还是被清。
- 若「被清」：对保留行 `UPDATE SnsInfo SET sourceType = sourceType | 2`（复用 SnsGuard 库句柄 + updateFlag 同款路径），必要时挂在 `i2.onGYNetEnd`（classifytimeline）之后补位。
- 若「固有」：M3 该场景判据作废，改为产品决策（是否要显式把 src=0 动态也纳入时间线）。
- **SnsGuard 的「拦 DELETE + 归档」继续保留**（自我删除那条路仍有效），SourceFlag-Fix 处理的是另一条路。

---

## 「证据不足」条目清单 + 补齐所需的具体取证动作（修订）

[DONE]

| # | 证据不足项 | 补齐动作（具体到类/方法/探针） | 影响 |
|---|---|---|---|
| 1 | `wxid_xlrbelf` 的动态为何全 src=0（固有 vs 被清） | §证伪实验步骤 1（新发一条，第一次拉取即查 `snsg one` 的 sourceType） | 决定 M3 是「修位」还是「判据作废」 |
| 2 | 服务端是否每轮 classifytimeline 清掉 bit1 | hook `i2.onGYNetEnd`（NetSceneSnsClassifyTimeLine，classes3.dex）打 `addSourceFlag`/清位调用 + 参数；或 §证伪实验步骤 2 | 方案 1 是否需「每轮补位」 |
| 3 | `SnsObject.DeleteFlag` 是否在好友删除时被置位、客户端是否据此清 sourceType | 真机 hook `c3.onGYNetEnd`（code=0x365048）/`c3.N`（code=0x3642fc）打印响应里各 SnsObject 的 `DeleteFlag` 值 | Q2 形态 1/2 定案 |
| 4 | 在线刷新路径是否也有「内存内 sourceType 过滤」（还是只靠 DB 查询过滤） | 静态追 `w5.onSceneEnd`（code=0x394640）处理后的 item 过滤点（`oh3/p.isInValid`/`isDieItem` 等）是否读 sourceType | 判断 DB 修位对在线刷新是否足够 |
| 5 | `addSourceFlag` 各调用点传入的位值全集（2/4/8/64/128 各自含义） | `_r27_callers.py --class SnsInfo --method addSourceFlag` 逐个 dump 常量；重点 `l5.o`(replace)、`a8.<init>`(UploadPackHelper)、`lg3/e.m`、`nf3/h.a` | 完整 sourceType 位定义表 |
| 6 | `d2.S7`(updateFilterTimeLine) 清的位掩码精确值 | 读 classes3.dex `d2.S7` code=0x3e1cbc 里拼进 SQL 的掩码常量（我粗读为 13，需反汇编器修好寄存器解码后确认） | 确认「清的是 bit1 还是别的位」 |
| 7 | 个人页 SnsUserUI 的查询是否真的不按 sourceType&2 过滤（src=4 能上个人页） | 反查 `mmsnsuserpage` 对应 NetScene + 其 DB 查询串是否含 `sourceType & 2` | 确认 src=4「仅个人页」的解释 |

---

## 附：本次新增/复核的 dex 取证命令（可复现）

```bash
# sourceType 过滤串（timeline 可见位）
python tools/_r6_snsdex.py strs "sourceType & "
# localFlag 查询串（验证「白名单不存在」）
python tools/_r6_snsdex.py strs "localFlag in"          # 0 命中
# sourceType==0 语义
python tools/_r27_disasm.py --class Lcom/tencent/mm/plugin/sns/storage/SnsInfo; --method isDeadSource
python tools/_r27_disasm.py --class Lcom/tencent/mm/plugin/sns/storage/SnsInfo; --method isSourceExist
# bit1 赋值点（classifytimeline）
python tools/_r27_disasm.py --class Lcom/tencent/mm/plugin/sns/model/i2; --method onGYNetEnd
# 调用者链（谁调 d2.t5 / addSourceFlag）
python tools/_r27_callers.py --class Lcom/tencent/mm/plugin/sns/storage/d2; --method t5
python tools/_r27_callers.py --class Lcom/tencent/mm/plugin/sns/storage/SnsInfo; --method addSourceFlag
```

---

[DONE] ALL
