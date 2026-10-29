# R35-3 待拍板事项（决策材料）

> 生成者：general-purpose-2（只读分析 lane）
> 目标：微信 8.0.48 / 2588 GP 版，包 `com.lx.wxgroup`
> 性质：**拍板材料**。每项给可选方案 + 代价 + 推荐与理由，主人一句话拍板。
> 纪律：`app/src/main/**`、`app/src/test/**` 全程只读，未构建、未装包、未碰 adb。

---

---

## P1 · `VersionGate` fail-open（8.0.72 条目 vc=0 / sha=null）

### 现状（代码事实，逐条可核）

| 位置 | 事实 |
|---|---|
| `VersionGate.kt:45-56` | 白名单两条：① `8.0.48` / vc=`2588L` / sha=`0fe4ff85…a3c7c`；② `8.0.72` / vc=**`0L`**（默认值）/ sha=**`null`**（默认值） |
| `VersionGate.kt:61` | 匹配只按 `versionName`，`firstOrNull`；匹配不到 → `Reject("version X not whitelisted")` |
| `VersionGate.kt:67` | `if (matched.versionCode != 0L && matched.versionCode != actual)` → **`target.vc==0` 时整段跳过**（不校验） |
| `VersionGate.kt:73` | `if (matched.sha != null && current != matched.sha)` → **`target.sha==null` 时整段跳过**（不校验） |
| ⇒ 合成结论 | `8.0.72` 条目 = **两个校验都短路 = 无条件 `Ok`**。这就是 fail-open |

### 真机 8.0.48 / 2588 实际走哪条

走**第①条的全校验路径**：`versionName=="8.0.48"` 命中 → vc `2588 == 2588` → sha 比对。
**fail-open 分支与当前真机版本无关**，改它不会动到 8.0.48 的任何行为。

### `vc == 0` 真机会不会出现 —— **无证据，是纯假设**

三条理由（都在代码里，不是推测）：

1. **取值路径产生不了 0**：`versionCodeOf()`（`HookEntry.kt:390-396`）SDK≥P 走 `longVersionCode`、否则走 `versionCode.toLong()`。一个真实安装的微信，PackageManager 不可能返回 0。
2. **「拿不到版本号」根本走不到闸门**：`hostPackageInfo()`（`HookEntry.kt:385-388`）是 `runCatching{}.getOrNull()`，`getPackageInfo` 抛异常 → 返回 null → `bootstrap` 在 `:103` **直接 return**，压根不调用 `check()`。所以「拿不到」的代码形态是**中止**，不是 `vc=0`。
3. **`versionName` 也有一条兜底**：`pkgInfo.versionName ?: "unknown"`（`:106`）——真拿不到就是 `"unknown"`，会命中 `not whitelisted` 被拒。

⚠️ **反方向的真缺口反而存在，且是 fail-close**：`hostSignatureSha256()`（`VersionGate.kt:87-95`）同样 `runCatching{}.getOrNull()`，`GET_SIGNATURES` 失败或 `signatures` 为空 → 返回 **null** → 第①条 `sha != null && null != sha` ⇒ **`Reject("signature mismatch: null")`**。
⇒ 真机上真正会发生的「拿不到」场景是**签名读不到 ⇒ 模块被整体禁用**（安全，但表现为静默全失效，容易误判成别的 bug）。这一点值得单独记一笔，但它**不是** fail-open，不需要 P1 拍板。

### 各方案在什么场景出事

| 方案 | 出事场景 | 后果 |
|---|---|---|
| **维持现状（fail-open）** | 微信升到 **GP 8.0.72** | 闸门放行 ⇒ 拿 `Anchors848` 里 **8.0.48 硬编码**的常量去挂勾。锚点全是常量、源码里**没有任何按版本分派**的逻辑（grep `Anchors848` 全是常量引用；`DexKitLocator.kt:45` 仍写「M1 接入后」，跨版本自动定位**未接入**）。<br>两种落法：① `findClass` 找不到 → 钩子静默不生效，**日志里既没有 `DISABLED` 也没有报错**，排查极难；② 类名恰好还在但字段/方法语义变了 → 反射取到错对象 → **崩微信**。<br>② 正是 `VersionGate.kt:8` 自己写要避免的那一态（「宁可功能失效，也不能让微信闪退」）。 |
| **A 删 8.0.72 条目** | 同场景 | `Reject("version 8.0.72 not whitelisted, supported: 8.0.48")` —— 明确拒绝、模块整体不打桩。<br>代价：以后要支持 8.0.72 得重新加条目。但**本来也必须重新取证锚点**，所以这条代价≈0。 |
| **B `vc==0` 时直接 Reject** | 同场景 | 安全性与 A **等价**（该版本必被拒）。副作用两条：① Reject 文案仍列 `supported: 8.0.48, 8.0.72`，**错误信息误导**，让人以为 8.0.72 支持；② `Target.versionCode` 的语义从「0=不校验」翻成「0=永不通过」，以后新加版本的人填一条空条目会困惑「我加了为什么还装不上」。 |

### 推荐：**A（删掉 8.0.72 条目）**

1. **安全性与 B 完全相同**（都是拒），但错误信息诚实，不产生「支持 8.0.72」的假象；
2. **零新增代码**，不引入「同一字段两种语义」的长期负担；
3. 8.0.72 **本来就没有对应锚点**，留条目只会让人误判支持范围；
4. `8.0.48` 那条已经把 vc + sha 都填实了 ⇒ 删 8.0.72 后白名单「每条都完整」这个不变量立刻成立，不需要额外约束去维持它。

> **附（不占拍板额度，可顺手）**：`VersionGate.kt:11` KDoc 写「版本不匹配时**静默**退出，一行桩都不打」，与 `HookEntry.kt:117` 实际打 `LogX.w("DISABLED: …")` 不符 —— 注释漂移，改一行即可。
>
> **以后取证期确实需要临时放行怎么办**：别用 `vc=0` 隐式表达。要么本地临时改、**不入库**；要么显式加一个 `allowIncomplete: Boolean = false` 字段并在日志里打 `gate ok (INCOMPLETE TARGET)`。隐式短路就是这次这个坑的成因。

### 判据（拍板后怎么证明选对了）

| # | 判据 | 通过标准 |
|---|---|---|
| P1-J1 | 静态：`grep -n "Target(" core/VersionGate.kt` | 白名单**每条**都有非 0 `versionCode` + 非 null `signatureSha256` |
| P1-J2 | 真机 8.0.48 冷启一次 | 日志同时满足：有 `host version=8.0.48 code=2588`、有 `gate ok: gp`、**无** `DISABLED` ⇒ 证明没误伤当前版本 |
| P1-J3 | 造一次不匹配（本地把 `whitelist` 的 `versionName` 临时改成 `8.0.49` 编一版，**验收后改回、不入库**） | 日志出现 `DISABLED: version 8.0.49 not whitelisted`，且**无** `hook registered` / 无 `boot completed` ⇒ 证明闸门真的关得上（不是只改了文案） |
| P1-J4 | 未来真升到 GP 8.0.72 时（被动判据，无需主动创造） | 出现 `DISABLED: version 8.0.72 not whitelisted`，微信**不崩**、功能全失效 ⇒ A 的目的达成。⚠️ 这一条只能等自然升级，**不要为了验它去升微信** |

---

## P2 · `SnsAlbumMerge` 的「注入候选」口径

### 当前口径是什么（逐行可核）

**install 状态**：`HookEntry.kt:270-272` 三行**仍是注释**，理由写在 `:260-269`（见下「推荐」）。
即：现在真机上这个类**一行都不跑**。

**候选口径**（开了会跑的那条链）：

| 步骤 | 位置 | 内容 |
|---|---|---|
| ① 取候选集 | `SnsAlbumMerge.kt:162` | `SnsAlbumMergeLogic.pickForAlbum(gdb.snsLedgerIds().toList(), respIds, MAX_PER_ROUND=32)` |
| ② 候选集定义 | `GroupDb.kt:602-613` | `SELECT sns_id FROM T_SNS UNION SELECT sns_id FROM T_SRC WHERE fixed_orig IS NOT NULL`，**无 LIMIT** |
| ③ 筛选 | `SnsAlbumMergeLogic.kt:32-47` | 去 `snsId==0`、去「本页响应里已有」、去重、限 32 |
| ④ 归属闸门 | `SnsAlbumMerge.kt:188-194` + `SnsAlbumMergeLogic.kt:60` | `ownerMatches(obj.Username, h3.e)`：大小写敏感 + 两端 trim + 空值一律 false |
| ⑤ 取正文 | `SnsAlbumMerge.kt:214-235` | `SELECT attrBuf FROM SnsInfo WHERE snsId=?` —— **只查微信自己的 `SnsInfo`** |

⇒ 口径的完整表述：**「补位台账并集 − 本页已有 − 非本人」，最多 32 条。缺的正是「被删」这个条件。**

⛔ **它违反了已写明的不变量**：`GroupDb.kt:814` 白纸黑字 ——
「已删判据用 `snsTagIds`，注入候选用 `listArchiveIds`，**两者都只认 `T_SNS`**」。
全仓 `snsLedgerIds` 的引用点只有两个：定义处、和 `SnsAlbumMerge.kt:162`。
（`GroupDb.kt:531` 那句「唯一合法用途：按台账回捞动态（`SnsAlbumMerge`）」是 R31h 之前的旧话，与 `:814` 已自相矛盾。）

### 这个口径会误伤什么（按相册路径推演，非真机实测）

相册**同样分页**。对 owner=X 打开相册第 1 页：

1. 台账里 X 的动态，只要被 `SnsTimelineSourceFix` 补过位就**全在** `fixed_orig` 里（R31 后是 **3447 条**量级，`GroupDb.kt:521`）；
2. 本页只有十几条 ⇒ 「不在本页」的那批全被 `pickForAlbum` 判成「缺失」⇒ 注入；
3. 这些是**正常动态**，`SnsInfo` 里行还在 ⇒ `readAttrBuf` **一定读得到** ⇒ `attempt` 与 `ok` 一起涨，
   形态与时间线事故那次 `attempt=24 ok=24 emptyBuf=0` 完全一致；
4. **真被删的那批反而一条都进不来**：物理 DELETE 的 8 条在 `SnsInfo` 里**没有行** ⇒ `readAttrBuf` 空 ⇒ `emptyBuf++`。
   `SnsAlbumMerge` **没有**时间线那份归档兜底（`SnsTimelineMerge.kt:192-198` 的 `gdb.snsArchivedAttrBuf(id)` / `src="archive"`）。

⇒ **现状口径是精确反的**：能注入的全是正常动态，真正要救的 8 条一条也进不来。

⚠️ **额外未验证风险（无证据，不能当结论）**：注入的对象会经微信自己的 `l5.o` **写回 `SnsInfo`**。
而 `SnsGuard.kt:332-347` 正在同一张表、同一批行上做「写库瞬间补 bit8」，且带 `bypass` 标志防自触发。
响应注入走的是微信原生路径，**会不会触发补位风暴 / 把补好的 `10` 打回 `2`，无证据**。

### 上一轮「朋友圈误伤」怎么修的（时间线侧，四条全有代码 + 真机证据）

| # | 修法 | 位置 |
|---|---|---|
| 1 | **候选换源**：`listSrcFixedIds()`（补位台账）→ `listArchiveIds(500)`（**只认 `T_SNS` 物理删除归档**） | `SnsTimelineMerge.kt:172`，理由见 `GroupDb.kt:552-559`（R31h 铁证 `attempt=24 ok=24 emptyBuf=0`：24 条全能从 SnsInfo 读回 attrBuf ⇒ **没一条是删掉的**） |
| 2 | **轮转游标**：归档 > `MAX_PER_ROUND` 时不能永远从头切 12 条，否则第 13 条起永远轮不到（饥饿）且**无任何日志** | `SnsTimelineMerge.kt:76-77`、`:169-186` |
| 3 | **`badCt` 闸门**：`CreateTime <= 0` 直接拒，否则 `insertIndexDesc` 会把它**静默沉底** | `SnsTimelineMerge.kt:68` ⛔ **`SnsAlbumMerge` 缺这一条** |
| 4 | **attrBuf 双来源**：先微信 `SnsInfo`，读不到再吃 `SnsGuard` 拦下 DELETE 时存的归档快照 | `SnsTimelineMerge.kt:192-198` ⛔ **`SnsAlbumMerge` 缺这一条** |

**真机验收**（`并行开发轮次计划.md:104`，22:06 包）：`attempt=8 ok=8 badCt=0`，首条 `src=archive buf=169`
⇒ 8 条全部来自归档、`badCt` 归零。

**同源的灰标事故**（`GroupDb.kt:518-535`）：`fixed_orig IS NOT NULL` 一度被当「已删除」判据，
R31 把补位扩到全量后涨到 **3447 条** ⇒ 3447 条正常动态被插「已阻止删除」灰标（**用户真实报障**）。
修法不是改 SQL，是**拆语义**：`snsTagIds`（已删判据）/ `snsLedgerIds`（补位台账）/ `listArchiveIds`（注入候选）三个方法各管一件事。

#### ⚠️ 关于 `MOMENT export done rows=500 written=0 listMiss=500` 这条 —— **全仓检索无此行，别拿它当输入**

实际抓到的最接近的是 `docs/_r26_img_export_真机验证.md:32`：

```
19:19:06 MOMENT export done rows=500 written=26 skipped=0 listMiss=474 err=0
```

- `listMiss` 语义（`MomentsExport.kt:781-783`）：扫到的行**没命中导出名单**就 `lm++`，是「名单外」计数，**不是失败**；
- 同仓另有 `rows=7 written=0 skipped=7 listMiss=0`（`_r25_moments_bg.md:119`），`written=0` 是 `scanExportedIds` 命中既有文件的**幂等 skip**，`_r14_device_pass2.md:15` 有明文解释「不是失败」。

⇒ **不构成「导出全空」的证据**。P2 的判断不依赖它。

### 修法对新口径的四条硬约束

1. **候选只能认 `T_SNS`（物理删除）**，不得掺 `fixed_orig`（`GroupDb.kt:807-814` + 3447 条事故）；
2. **必须有归档 attrBuf 兜底**，否则物理删除的 8 条永远 `emptyBuf`；
3. **必须有 `badCt` 闸门**（`CreateTime <= 0` 拒），否则沉底且无日志；
4. **必须有轮转 + 限流**。相册翻页比时间线更密（一页十几条），`MAX_PER_ROUND=32` 在分页场景下**不是保护，是放大器**。

### 可选口径与代价

| 口径 | 内容 | 误伤什么 | 代价 |
|---|---|---|---|
| **① 现状** | `snsLedgerIds ∪` − 本页 − 非本人 | 相册里重复塞进 owner 的**正常动态**；真被删的进不来 | 0（不用改）⛔ **一开就是事故** |
| **② 与时间线对齐** | `listArchiveIds` + 轮转 + `badCt` + 归档 attrBuf 兜底 + owner 判据前置（省掉每轮几千次 SQL+parse） | 理论上不误伤。但相册网格还有 **`type ∈ {1,15}`** 那道闸（`未完成项与计划.md:279`），`type=2` 的视频动态补了也进不去 | ~40 行 + 单测；**与已启用的治本路线功能重叠** |
| **③ 维持停用** | 不改口径、不启用（现状） | 无 | 0 |
| **④ 物理删除整个类** | 删 `SnsAlbumMerge.kt`(259) + `SnsAlbumMergeLogic.kt`(77) + 对应单测 | 无 | 清 ~336 行死代码；⛔ 但 `docs/_r30_lane2_album_page.md` 的取证结论（响应类 `q54/jc5`、wxid=`h3.e`、`l5.o` 写库）会随代码一起失去落脚点 |

### 推荐：**③ 维持停用**（若主人想清代码则 ④）

按权重排序的四条理由：

1. **注入方案的前提已被否掉**。`HookEntry.kt:260-269` 停用注释写明：相册是**读库渲染**
   （`qg3/i` 的 `d:List` 元素是 `SnsInfo` 存储实体，SQL 由 `SnsInfoStorage.getUserMediaSql` 生成）⇒
   往 `h3.N` 的 `jc5.f` 塞对象**对 UI 无直接增量**，只能借道 `l5.o` 写库，属绕路。
2. **6.2 已换治本路线且已启用**：`SnsTimelineSourceFix.installAlbumTrigger`（`h3.N` after）
   + `installAlbumSyncTrigger`（`x2.onGYNetEnd`）+ `SnsGuard.kt:332-347`「写库瞬间同步补 bit8」。
   再开 `SnsAlbumMerge` 就是**同一目标的第二套实现**，两套都往 `SnsInfo` 写，必然打架。
3. **当前样本证伪不了也证实不了它**。`未完成项与计划.md:282`：12 条被删动态**无一可验证 6.2**
   （verity 全 11 条 `type=2` 视频；其余带 die 位 / `type=28` / 物理删除无行）。没样本 ⇒ 开了也看不出对错，只增加噪声。
4. **真要开，成本也不在口径上**：口径改对（②）只能让它「不误伤」，不能让它「有用」——`type ∈ {1,15}` 那道闸还在。

> **若主人选②**：必须一次做齐上面「四条硬约束」，缺一条就是重演事故；且**不要与**
> `installAlbumTrigger` / `installAlbumSyncTrigger` 同时开，先二选一上机对比。
> **若主人选④**：删之前先把 `_r30_lane2_album_page.md` 的三条取证结论摘进常驻文档（响应类 `q54/jc5`、wxid = `h3.e`、`l5.o` 写库），否则这些取证随代码一起消失。

### 判据

| # | 判据 | 通过标准 |
|---|---|---|
| P2-J1（②/④ 共用 · 静态） | `grep -rn "snsLedgerIds" app/src/main` | 只剩 `GroupDb.kt` 定义处与 `SnsTimelineSourceFix` 的合法用途；`SnsAlbumMerge` 里**不再出现** |
| P2-J2（选③） | 装包后日志 | **无** `SNS-ALBUM hook h3.N installed`；**有** `SNS-ALBUMFIX hook …onGYNetEnd installed`（证明治本路线在跑） |
| P2-J3（选②） | `op=snsa sub=stat` 读 `SNS-ALBUM-STAT` | `ok` 必须是 **0 或个位数**；且 **`emptyBuf` 不能等于 `attempt`**（等于说明在读得到的正常动态上重复注入 = 事故特征） |
| P2-J4（选② · 人眼） | 好友相册页 | **没有**同一条动态出现两次；滚到底不出现「第 1 页已有的动态又冒出来」 |
| P2-J5 | 反证 | 关掉 `ANTIREVOKE_SNS` 后 `SNS-ALBUM-STAT switch=false` 且 `attempt` 不涨 ⇒ 开关真管得住 |

> ⚠️ 判据纪律：时间线修后那条 `attempt=8 ok=8 badCt=0` **只证明「注入成功」，不证明「没误伤」**。
> 不误伤的判据只能是「界面上没有重复条目」，必须人眼对比服务端原图。

---

## P3 · §三 C.2「相册网络回包覆盖」

### 回包覆盖的实际链路 —— **有代码证据，且防法已经在跑**

链路（`SnsTimelineSourceFix.kt:353-355` KDoc 原文，与 `:410` 另一处表述一致）：

```
h3.N 收响应 → updateWithOnConflict 按服务端值整行 upsert（sourceType=2，bit8 丢）
            → 事件 → loadData 读库（WHERE sourceType in (…全部含 bit8…)）
```

三条独立证据：

| 档 | 证据 | 出处 |
|---|---|---|
| ① 代码里写着 | 「好友个人主页同步时它按服务端值整行 upsert，把补好的 `10`（时间线\|相册）打回 `2`（只有时间线位）」 | `SnsGuard.kt:326-331` |
| ① 代码里写着 | 「补位守不住：微信同步会把 10 打回 2 ⇒ 必须在同步结束（`x2.onGYNetEnd`）后再补一次」 | `HookEntry.kt:280-283` |
| ② 用户亲述现象 | 「进好友朋友圈，那几条已删动态**闪一下马上就消失**」（首屏吃旧内存数据看得见，刷新一读库就没了） | `SnsTimelineSourceFix.kt:352`；`_r30_62_album_gate.md:158-159`「点进去的一瞬间有，马上消失」 |

⚠️ **一条必须点出来的文档过期**：`_r30_62_album_gate.md` §F（`:153-162`）把回包覆盖写成
「收敛后的根因（**待最后一步证据**）」，并据此建议「6.2 的正确解法**不是改库**，是干预 `NetSceneSnsUserPage`
的响应合并（`SnsAlbumMerge` 方向）」。**这个建议后来没有采纳** —— 现行代码走的是**改库**路线
（`fixAlbum` + `patchAlbumBit`），并把补位点压进了写库事务窗口。现在拿 §F 当依据会得出相反结论。

### 发生会怎样

`sourceType` 被服务端值整行覆盖 ⇒ 相册位 bit3(8) 丢失 ⇒ 该行不满足相册 SQL 的
`sourceType in (…64 值，全部含 bit8…)` 白名单 ⇒ **读库渲染时消失**。
时间线位 bit1(2) 服务端还会给 ⇒ 时间线正常、只有好友个人相册里看不到 —— 这正是 6.2 要解决的问题本身。

### 现在的防法（已在启用，不是待开发）

三处，都在 `HookEntry.kt:274-283` **实际启用**（非注释）：

| 防点 | 位置 | 作用 |
|---|---|---|
| `installAlbumTrigger` | `SnsTimelineSourceFix.kt:421` | 挂 `h3.N` 的 **after**（写库后、读库前）补 bit8 |
| `installAlbumSyncTrigger` | `SnsTimelineSourceFix.kt:448` | 挂 `x2.onGYNetEnd`（scene 256 同步统一回包）后再补一次 |
| **`patchAlbumBit`** | `SnsTimelineSourceFix.kt:364` ← 由 `SnsGuard.kt:339` 在 **`SnsInfo` 的 update after** 里调 | ⭐ **治本**：把「覆盖」和「补回」压进**同一个数据库事务窗口**，读库时不可能看到中间态（`:359-360`） |

`patchAlbumBit` 的四道闸门（决定它会不会误伤正常刷新）：

1. `SnsGuard.kt:313` —— `(a[0] as? String) != "SnsInfo"` 直接 return，**零反射短路，必须是第一判断**（微信所有表的 update 都走这个钩子）；
2. `SnsGuard.kt:332` —— `(st and ALBUM_BIT) == 0` 才动，**已经有相册位的不碰**；
3. `SnsGuard.kt:334` —— `SnsTimelineSourceFix.isAlbumTarget(id)`，**只认窄台账**（`T_SRC.fixed_orig IS NOT NULL`，真机十几条；带 5s TTL 缓存 `:287-293`）；
4. `patchAlbumBit:370` —— `isPrivate(curSrc)`（bit2=4，用户自己设的「仅自己可见」）**直接 return 0**，加相册位会把它推给好友看。

外加 `bypass` 标志（`SnsGuard.kt:337/343`）防补位自身那次 update 递归触发。

### 防它会不会误伤正常刷新 —— **不会**

- **误伤面就是那十几条**：正常动态不在窄台账里，第 3 道 `isAlbumTarget` 就挡掉了。
  `SnsTimelineSourceFix.kt:415-419` 明写：全库 **17.6 万条正常的 `sourceType=2`**，微信本来就不让它们进个人相册；
  2026-10-27 曾把「缺 bit8 就补」接到**广谱**路径上，几百条正常动态一夜之间涌进好友相册、标记也跟着乱标 —— 所以这条边界是**由 `fixAlbum` 只认窄台账从结构上保证的，不靠小心**。
- **性能**：翻页有 5s 节流（`ALBUM_FIX_MIN_INTERVAL_MS`，`:400`，注释说明 15s→5s 的原因）+ 单线程池，每轮 `limit=64` 且绝大多数是 point query，「实测成本可忽略」。
- ⚠️ **唯一真实缺口是可观测性，不是误伤**：`patchCount` 只在 `n <= 8` 时打日志（`:374`），第 9 次起**静默**。排查时容易误判成「没触发」。

### 推荐：**防（现状即正确答案），并且「就此收口」—— 不新开发响应注入**

1. **防法已落在正确的窗口里**：写库 after、与覆盖同处一个事务窗口（`:359-360`），比「响应注入」更靠根因，且不引入第二套写库路径；
2. **响应注入（P2 的 `SnsAlbumMerge`）与它重复且绕路**（`HookEntry.kt:260-272` 停用注释：相册读库渲染，注入 `jc5.f` 对 UI 无直接增量）——P3 若选「做响应注入」，等于同时开启两套都往 `SnsInfo` 写的东西；
3. ⛔ **顺带订正原表格里的一句过期依赖**：「若选做，D（突破三天可见）依赖同一套注入链路」—— **不成立**。
   `未完成项与计划.md:296` 写明 6.3 与 6.2 是**同一套补位链路**（本地 `attrBuf` + `CreateTime`），差别只在「候选怎么筛」和「时间窗闸门放不放」，**不是响应注入链路**。⇒ D **不**依赖 P3 的选择；
4. **剩下真正缺的是验收，不是实现**：`C.1 回包取证未做` ⇒ 不知道 `patchCount` 是否真 >0、`h3.N`/`x2.onGYNetEnd` 是否真的挂上了。

### 判据

| # | 判据 | 通过标准 |
|---|---|---|
| P3-J1 | 装包日志 | 同时出现 `SNS-ALBUMFIX hook h3.N installed` **和** `SNS-ALBUMFIX hook …onGYNetEnd installed`，且**无** `SNS-ALBUMFIX class missing` / `not found`（`:427`、`:461` 的 warn） |
| P3-J2（核心） | `op=snsg sub=srcfix sub2=album` 后读 `SNS-ALBUMFIX` 的 `albumpatch count=`（`SnsTimelineSourceFix.kt:383`） | **`count > 0`** ⇒ 证明「写库瞬间补位」这条治本路径真被走到了（当前这条**还没有真机值**，属缺口） |
| P3-J3（误伤反证） | 打开一个**没有**被删动态的好友相册 | `albumpatch count` **不涨**；`op=snsg sub=srcmap` 前后对比 `sourceType=2` 的条数**不变**（17.6 万行不受影响） |
| P3-J4（现象判据） | 进好友个人相册，**刷新之后**看 | 已删动态**不再「闪一下就没」** —— 这是用户报障的原话，也是唯一与 P3 一对一吻合的判据 |
| P3-J5（私密位） | 找一条自己设了「仅自己可见」的动态 | 它**没有**被加 bit8（`isPrivate` 短路，`patchAlbumBit:370`） |

> ⚠️ 判据纪律：`count > 0` 只证明「补位跑了」，**不证明「覆盖了被防住」**。
> 真正对应 P3 的是 **P3-J4**（刷新后仍在）。J4 需要一位好友配合删一条**带图片**的动态
> （`未完成项与计划.md:283`：当前 12 条被删动态无一可验证 6.2，verity 全是 `type=2` 视频）。

---

## P4 · §三 F 三项

> 📌 **读完代码的三条总判断（先看这个）**
> F 三项里 **两项已经落地、一项已经物理删除**，`未完成项与计划.md` §一 1.3/1.4 与 §四 表格
> **都已过期**。所以 P4 实质上不是「定方向」，而是「**确认现状 + 补真机验 + 订正文档**」。

| 项 | 文档里写的现状 | 代码里 actual 现状 | 结论 |
|---|---|---|---|
| F-1 `ChatroomSource` 触发时机 | 「只挂在 `ContactSync.doSync` 尾部 ⇒ 不打开通讯录就永远不跑」（`未完成项与计划.md:116`） | **已按选项②实施**：`PickerTabs.kt:52` 在 `all()` 入口补了 `ChatroomSource.runIfNeeded()`，`PickerPage.kt:261` 注册弱引用完成回调 | ⛔ 文档过期；**代码已选②**，只差真机验 |
| F-2 无名群排序 | 「`ORDER BY remark, nickname` 把空名群排最前」（`:123`） | **已实施**：`data/ChatroomSortLogic.kt`（`sortNamedFirst` 稳定排序）+ `GroupDb.kt:1340` 在 **Kotlin 侧**沉底 | ⛔ 文档过期；**代码已改**，只差真机观感验 |
| F-3 评论防删除代码去留 | 「代码保留冻结，**是否物理删除待你定**」（§四 表格首行） | **已于 2026-10-24 物理删除**（N7）：`HookEntry.kt:343-346`、`EntryReceiver.kt:616-617`、`SnsGuard.kt:1572-1573` 三处留档 | ⛔ 文档过期；**这个问题已经不存在了** |

---

### F-1 · `ChatroomSource` 触发时机

**现状（代码）** —— 两个触发点并存：

| 触发点 | 位置 | 说明 |
|---|---|---|
| `ContactSync.doSync` 收尾 | `ContactSync.kt:202` | 打开通讯录滚一遍才会跑 |
| `PickerTabs.all()` 入口 | `PickerTabs.kt:49-52` | ⭐ F1 新增：首次打开任何名单功能即补跑，`runIfNeeded()` **立即返回**（起 daemon 线程 + `loading` CAS 节流），不阻塞 UI |
| 完成回调闭环 | `ChatroomSource.kt:156-169` + `PickerPage.kt:261` | 弱引用监听器，扫完 `written>0` 时主线程回调 `PickerPage` 原地刷新，把「群聊」Tab 补出来 |

补跑判据 `shouldRun`（`ChatroomSource.kt:109-114`）：`cached==0` 或 `now-last > FULL_INTERVAL_MS` 才跑；
`runIfNeeded` 内部**再判一次**（`force=false`，`:128-131`，消掉 R24 复核 S5 的死代码歧义）。
两条可区分日志：`:136`「命中补跑：群聊缓存缺失或过期，起全表扫」/ `:139`「跳过补跑：群聊缓存新鲜，无需全表扫」。

**可选做法与代价**

| 做法 | 内容 | 代价 |
|---|---|---|
| ① 回退到只挂 `ContactSync` | 删掉 `PickerTabs.kt:52` 一行 | 「首次打开选择器时群聊 Tab 不存在」（空数据源不产出 Tab），用户以为「还是没有群聊」—— 这正是 F-1 要解决的问题 |
| **② 维持现状**（推荐） | — | 首开多一次全表扫（文档记 **2.4s**，`未完成项与计划.md:118`）；已由「daemon 线程 + 不阻塞 UI + 扫完回调补 Tab」化解。**代价已经付过了** |
| ③ 再加冷启触发 | 模块 `bootstrap` 里也跑一次 | 每次微信冷启都扫一遍 rcontact，与 ② 的 `shouldRun` 节流重叠收益低；且冷启时 `GroupDb` 可能还没 ready（`shouldRun` 首行 `db==null → false`，`:110`）⇒ 大概率白跑 |

**推荐：② 维持现状。** 理由：F-1 的判据（`cached==0` 或超 6h）已经让「重复全表扫」不可能发生
（`_r24_review.md:142` 复核结论：`KEY_LAST_FULL` 落盘 + `markFullSync` 用 commit，**不会每次触发都全表扫**），
2.4s 这一次只在新装/清缓存时出现一次。

**判据**

| # | 判据 | 通过标准 |
|---|---|---|
| P4-F1J1 | 清掉群聊缓存（或首次装机）后**直接打开选择器**，不进通讯录 | 日志出现 `ChatroomSource …命中补跑：群聊缓存缺失或过期，起全表扫` |
| P4-F1J2 | 紧接着再打开一次选择器 | 日志出现 `跳过补跑：群聊缓存新鲜，无需全表扫` ⇒ 节流真的生效（不是每次都扫） |
| P4-F1J3 | 首开那次观感 | 「群聊」Tab **出现**（可能晚 1~2 秒出现，由回调补出），且期间**界面不卡**（`runIfNeeded` 不阻塞主线程） |
| P4-F1J4 | 反向对照 | 缓存新鲜时打开选择器，**无**「命中补跑」⇒ 不打扰正常路径 |

---

### F-2 · 无名群排序（纯观感）

**现状（代码）** —— `data/ChatroomSortLogic.kt` 已落地：

- 判据 `hasName(remark, nickname) = remark.isNotBlank() || nickname.isNotBlank()`（`:31`）
  ⚠️ 用 `isNotBlank()` 不是 `isNotEmpty()`：真机源表里确实存在 `"   "` 这种纯空格值（`:28-29`）；
- `sortNamedFirst`（`:48-52`）用 `sortedWith(compareBy { 0/1 })` —— **稳定排序**（底层 TimSort），
  组内顺序 = 数据库已排好的 `remark, nickname` 顺序，不会被打乱；
- ⛔ 明确**不改** `GroupDb.queryFriends` 里共享的 `ORDER BY`（`:45-46`：好友列表、`findByDisplayName` 都在用）；
- 应用点：`GroupDb.kt:1340` 在 **Kotlin 侧**沉底。

**可选做法与代价**

| 做法 | 内容 | 代价 |
|---|---|---|
| **① 维持现状（推荐）** | Kotlin 侧稳定沉底 | 0（已付） |
| ② 改 SQL 层 `ORDER BY CASE WHEN …` | 在 SQL 里做 | ⛔ **已被源码明确排除**：那个 `ORDER BY` 是好友列表 / `findByDisplayName` **共用**的，改它会波及「好友」Tab（`ChatroomSortLogic.kt:45-46`） |
| ③ 回退（不排） | 删掉沉底 | 一进群聊 Tab 满屏数字群号（`ChatroomSortLogic.kt:6-8` 记录的原始观感问题） |

⚠️ 前置事实（避免误判）：无名群**显示群号是正确兜底**，不是缺陷 ——
这些群在微信源表里就没名字（`_r24_picker_tabs.md` 已证伪为「非缺陷」）。本项**只动顺序，不动显示内容**。

**推荐：① 维持现状。**

**判据**

| # | 判据 | 通过标准 |
|---|---|---|
| P4-F2J1 | 打开群聊 Tab | 有名群在前、**无名群（显示数字群号）全部沉到末尾** |
| P4-F2J2 | 反向对照（最硬） | **好友 Tab 的排列顺序与改动前一致** ⇒ 证明没碰到共享 `ORDER BY` |
| P4-F2J3 | 纯空格名群 | 名为 `"   "` 的群也被判成无名、沉到底（不是靠 `isNotEmpty()` 漏判） |
| P4-F2J4 | 组内顺序 | 无名群之间的相对顺序 = 数据库顺序，未被打乱（`_r29_chatroom_sort.md:79` 已预期） |

---

### F-3 · 评论防删除代码去留

**现状（代码）** —— **已于 2026-10-24 物理删除**（N7），不是「保留冻结」：

- 删除范围：`SnsCommentGuard` / `SnsCommentTag` / `SnsCommentLogic` / `SnsCommentLabelLogic` + 两个测试
  （约 **207KB / 121 个单测**）—— `HookEntry.kt:344-346`；
- 三处留档：`HookEntry.kt:343-354`、`EntryReceiver.kt:616-617`、`SnsGuard.kt:1572-1573`；
- 残留的 `SnsComment` 只有**数据库表名**（`SnsGuard.kt:1599` `select * from SnsComment`），不是功能代码，
  且 `SnsGuard.kt:1578-1586` 专门写了「哪些 `SnsComment` 不能删」。

**留档的两条重启硬阻碍**（`HookEntry.kt:348-353`，将来若重启需求先看这个）：

1. 评论的**可见列表载体是 `SnsInfo.attrBuf`（protobuf）**，`SnsComment` 是另一份明细表；
   删评论前微信可能已重写 attrBuf ⇒ 只保住 `SnsComment` 行**不一定够**；
2. 更硬的一条：UI 层评论 proto **不带 `commentSvrID`**（`q54/q95` 的 `q`/`r` 恒 0）
   ⇒ 无法按 svrId 精确匹配，只能退到渲染键 `"createTime:talker:type"` 做模糊匹配，
   `rows=0` / `del=0` 两项 P0 **始终未定案**。

**可选做法与代价**

| 做法 | 内容 | 代价 |
|---|---|---|
| **① 就这样（推荐）** | 维持已删除状态 | 0。留档注释已把重启时的两个坑写清楚，不需要留代码 |
| ② 从 git 历史里捞回来重新冻结 | — | ⛔ 与 2026-10-20「停止该功能」+ 10-24「物理删除」两次决定相悖；且上面两条 P0 未定案，捞回来也是不能用的代码 |

**推荐：①。** 本项**不需要拍板** —— 已经删完了，`未完成项与计划.md` §四 那行「是否物理删除待你定」是过期描述，建议直接订正。

**判据**：`grep -rn "SnsCommentGuard\|SnsCommentTag\|SnsCommentLogic\|SnsCommentLabelLogic" app/src` ⇒ **零命中**。
（纯静态，不需要上真机。）

---

### 📎 P4 附带：建议顺手订正的三处过期文档

| 位置 | 原文 | 应改为 |
|---|---|---|
| `未完成项与计划.md:116` | 「`runIfNeeded()` 只挂在 `ContactSync.doSync` 尾部」 | 已加 `PickerTabs.all()` 入口触发（F1，`_r29_f1_chatroom_trigger.md`） |
| `未完成项与计划.md:123` | 「`ORDER BY remark, nickname` 把空名群排最前」 | 已由 `ChatroomSortLogic` 在 Kotlin 侧稳定沉底（R29-4） |
| `未完成项与计划.md` §四 表格首行 | 「代码保留冻结，**是否物理删除待你定**」 | 已于 2026-10-24 物理删除（N7），见 `HookEntry.kt:343-346` |

> ⚠️ 这三处若不定，下一轮还会把这三项当「待拍板」再问一遍。

---

## 一句话拍板摘要

| 事项 | 推荐选项 | 一句话理由 |
|---|---|---|
| **P1** VersionGate fail-open | **A**（删 `8.0.72` 条目） | 与 B 安全性等价（都是拒），但错误信息诚实、零新增代码；`8.0.72` 本来也没锚点，留条目只会让人以为支持 |
| **P2** `SnsAlbumMerge` 候选口径 | **③**（维持停用，不改口径） | 6.2 已改走「写库补 bit3」治本路线且已启用，再开注入是同目标的第二套实现、两套都写 `SnsInfo`；且当前 12 条样本无一能验证 6.2 |
| **P3** 相册回包覆盖 | **A=防**（现状已在防，**就此收口**，不新做响应注入） | 防法已压进写库事务窗口（`patchAlbumBit`），判据只认十几条窄台账、17.6 万正常行不碰；缺的是验收不是实现 |
| **P4-F1** `ChatroomSource` 触发时机 | **②**（维持现状：打开选择器时补跑） | 代码已经这么做了（`PickerTabs.kt:52`），节流保证不会重复全表扫，2.4s 只在新装时出现一次 |
| **P4-F2** 无名群排序 | **①**（维持现状：Kotlin 侧稳定沉底） | 代码已经这么做了（`ChatroomSortLogic.kt`），改 SQL 层会波及共用的好友列表排序 |
| **P4-F3** 评论防删除代码 | **①**（就这样 / 已删完，无需再定） | 已于 2026-10-24 物理删除（N7），文档那句「是否删除待你定」已过期，只需订正文档 |

**可直接回复的形式**：`P1=A P2=③ P3=A P4=②①①`

### 本次拍板真正会改动的代码（其余都是「维持现状 + 补验收 + 订正文档」）

| 事项 | 改动量 |
|---|---|
| P1=A | `VersionGate.kt` 删 1 条白名单条目（3 行）；可选顺手改 `:11` 注释漂移 1 行 |
| P2=③ | **0** |
| P3=A（收口） | **0** |
| P4=②①① | **0**（只需订正 `未完成项与计划.md` 三处过期描述） |

### ⚠️ 三条贯穿全篇的纪律提醒（拍板后开工前请再看一遍）

1. **真机判据优先于代码判据**：`attempt=8 ok=8 badCt=0` 只证明「注入成功」，**不证明没误伤**；
   不误伤的判据只能是「界面上没有重复条目」。
2. **「不在响应里」≠「被删」**：朋友圈和相册都分页。任何以「补全集 − 本页」为候选的注入，
   只要候选集不是「物理删除归档 `T_SNS`」，就是在重演 3447 条那次事故。
3. **判据的作用域决定它是修复还是破坏**：同一个 `fixed_orig` 判据，喂十几条的窄台账是修复，
   广谱扫表就是破坏（`未完成项与计划.md:284`）。

---

## 本文件自身的取证纪律声明

- 全程**只读源码**（`app/src/main/**`、`app/src/test/**` 未写），未构建、未装包、未碰 adb。
- 每条结论区分了三档：**① 代码里写着**（给出 `文件:行号`）/ **② 真机亲述或实测记录**（给出文档出处）/ **③ 推演**（明确标注「非真机实测」）。
- 检索不到证据的一律写「无证据」，例如：P1 的 `vc==0`（纯假设）、
  P2 的「响应注入会不会触发补位风暴」（未验证风险）、
  `MOMENT export done rows=500 written=0 listMiss=500`（**全仓检索无此行**）。

[DONE]
