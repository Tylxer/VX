# R21 · 分组批量添加好友搜不到企业微信好友（friend-src lane）

状态：**方案 + 判据 + 代码已落盘，待主会话统一构建 / 真机验证**（本文件不声称已修复）
改动范围（本 lane）：`core/ContactSync.kt`、`data/Friend.kt`、新建 `core/ContactSqlSource.kt`、
新建 `data/ContactFilterLogic.kt`、新建 `app/src/test/.../data/ContactFilterLogicTest.kt`
（`data/GroupDb.kt` 无需改动，见 §5-Q5）

---

## §1 根因复盘（主会话已真机锁定，这里只做承接）

### 1.1 症状
分组批量添加好友（`feature/BatchPicker.kt`）搜索框搜「Lynsey」等企业微信好友，搜不到。

### 1.2 两层根因

- **第一层（不是主因）**：`WxidKind.typeOf`（`data/Friend.kt:49-58`）对 `@openim` 返回
  `FRIEND`。这个判定**是对的**（`@openim` 不满足任何排除分支，落到 `else -> FRIEND`）。
  ⇒ `typeOf` 本身不需要改，只需纳入测试锁住。

- **⛔ 第二层（真根因）**：`friend_cache` 里根本没有 `@openim` 数据。
  数据源 `ContactScanner.scan(adapter)`（`core/ContactScanner.kt:40`）从通讯录列表 adapter
  的对象图 BFS 捞 `com.tencent.mm.storage.h4` 实例。而 `@openim` 联系人**不在通讯录 UI 渲染**
  （企业微信联系人走独立入口，不进 `AddressLiveList`）⇒ 扫不到 ⇒ 缓存恒缺。
  真机 `select count(*) from friend_cache where wxid like '%@openim'` = **0**。

### 1.3 关键实测数据（承接，供判据对齐）

| 查询 | 结果 |
|---|---|
| `rcontact` 总行数 | **23154** |
| 带 `@` 的 username 后缀分布 | `@app 933`、`@chatroom 841`、`@openim 254`、`@stranger 59`、`@fakeuser 1` |
| `@openim` 的 type/verifyFlag 分布 | `0/0→43`、`1→68`、`4→139`、`5→2`、`513→2` |
| `@openim` 且 `type=0` 样本 | `25984981937809637@openim / Lynsey`、`…/奥春科技`、`…/诚信赢天下`、`…/吴建红`、`…/郑翔宇`（全是真实个人好友） |
| `rcontact` **只在 `EnMicroMsg.db`** | 其余 7 个库全部 `no such table: rcontact` |
| 现网 `friend_cache` | `0 → 2257`、`9 → 2`（`weixin`/`filehelper`） |

---

## §2 方案选型

### 2.1 结论：选 **A（rcontact 直读），但做成「补充直读」而非「全量替换」**

| 方案 | 结论 | 理由 |
|---|---|---|
| A. rcontact 直读 | ✅ **采用**（补充式） | 只补 UI 扫不到的 `@openim`（254 条），不碰已验证正确的 2257 条普通好友 |
| B. 扩 UI 入口 | ❌ | 企业微信入口页结构未知，且 `@openim` 未必在该页 |
| C. 只改过滤 | ❌ | **已证无效** —— 数据没进来，放开 `allRealFriends` 也没东西可放 |

### 2.2 为什么是「补充直读」而不是「全量替换」

主会话验收判据 #3 是硬约束：`friend_cache` 从 2257 **升到 ~2500+**（= 2257 + 254），
而不是 23154 或 21000。这个数字说明 rcontact 里**绝大多数 no-`@` 行不是当前好友**：

- `rcontact` 是微信的「关系流水账」，除了通讯录好友，还装着：进过群的人（群成员）、
  已删除的好友、公众号/服务号、群聊本身、陌生人等。
- 真机对照：no-`@` 行约 21066 条，但 UI 通讯录只渲染出 **2257** 个真好友。
- 若全量替换，要么按 `type` 硬筛（已证不可行，见 §3.1），要么把 1.8 万条群成员/陌生人
  灌进 `friend_cache`，直接把「批量加好友」页面打爆。

所以唯一稳的做法：**UI 扫描仍是普通好友的主数据源（保持 2257 不变），rcontact 直读只补
UI 不渲染的 `@openim`（254）**。两者 `upsert` 合并，谁也不覆盖谁。

### 2.3 回滚路径

- 直读逻辑全部收敛在新文件 `core/ContactSqlSource.kt` + `data/ContactFilterLogic.kt`，
  接入点只有 `ContactSync.init()` 一行。回滚 = 删掉 `ContactSync.init` 里那一行调用，
  行为退回「只靠 UI 扫描」（即现状）。
- `ContactFilterLogic` 是纯函数，改了判据先跑 JVM 单测，不污染任何 Android 路径。

---

## §3 好友判据设计（本 lane 核心）

### 3.1 为什么不能按 `type` 白名单/黑名单筛（实测）

| type | 普通好友数量 | `@openim` 好友数量 | 结论 |
|---|---|---|---|
| 0 | 2344 | 43 | type 同时覆盖好友与非好友，**既不能当白名单也不能当黑名单** |
| 1 | — | 68 | |
| 3 | 2405 | — | |
| 4 | 16233 | 139 | |
| 5 | — | 2 | |
| 33 | 25 | — | |
| 513 | — | 2 | |
| 8388611 | 16 | — | |

⇒ 判据必须落在 **username 形态**上（跨版本稳定的命名惯例），`type` 只做辅助对账，
不参与过滤。

### 3.2 排除清单（每个都写理由）

| 形态 | 数量 | 排除理由 |
|---|---|---|
| `@chatroom` | 841 | 群聊，不是个人好友，不该进「好友分组」 |
| `@app` | 933 | 服务号/应用号（公众号体系），不可分组 |
| `gh_` 前缀 | N（真机未给精确数） | 订阅号（公众号体系），不可分组 |
| `SYSTEM_IDS`（`weixin`/`filehelper`/…） | 个位数 | 系统号/文件传输助手，不是可分组的联系人 |
| `@stranger` | 59 | 陌生人 ≠ 好友（群成员/未通过验证的人），不可分组 |
| `@fakeuser` | 1 | 占位假用户，不可分组 |

**保留**：`@openim`（254，企业微信好友）+ 所有 no-`@` 且非 `gh_`、非 `SYSTEM_IDS` 的普通好友。

### 3.3 必须回答的三个问题

#### Q1：`@openim` 254 条里哪些真是好友？

**全部 254 条都是好友。** 理由（真机 `_sns.txt` 三轮取证，全部可复现）：

- **`deleteFlag=0` 全覆盖**：`select type,deleteFlag,count(*)` 显示 254 条全部
  `deleteFlag=0`（type=0:43 / type=1:68 / type=4:139 / type=5:2 / type=513:2，无一条
  deleteFlag=1）⇒ 没有「已删除的好友」混在里面。
- **`chatroomFlag=0` 全覆盖**：254 条全部 `chatroomFlag=0` ⇒ 全是 1:1 联系人，没有群聊借用
  `@openim` 形态。
- **`verifyFlag` 无区分力**：`verifyFlag` 全 0（取证结论），不能用它筛，也不必用它筛。
- **`type` 是噪声**：散在 `0/1/4/5/513` 五个值，与「是不是好友」无关。
  12 条带 `conRemark` 的全是真实商务联系人（`Anthony Meng`→「温州桁斯蒙总」、
  `…@openim`→「比亚迪王洪宇」「长安汽车陶丹妮」等），备注只能在通讯录里加 ⇒ 是好友。
- **`type=0` 样本**（Lynsey/奥春科技/诚信赢天下/吴建红/郑翔宇）全无备注但都是真实个人好友。

⇒ 判据落定：**`@openim` 全收**。绝不能要求 `conRemark` 非空 —— `type=0` 的 43 条（含 Lynsey）
全无备注，若按「有备注才算好友」筛，Lynsey 会被误删，验收判据 #2「搜 Lynsey」直接失败。
「宁多不漏」在这里成立：企业微信好友只会「搜不到」，不会「多搜出陌生人」。

#### Q1.5：用什么信号区分「好友」与「外部会话/非好友」？用不用 rconversation 交叉？

**没有可靠信号，也不该去求一个可靠信号 —— 判据就是 `@openim` 后缀本身，全收。**

- **候选信号逐个证伪**（均有真机数据）：
  - `type`：既非白名单（type=0 只有 43 条，会漏掉 type=1 的 68 条），也非黑名单
    （type=4 的 139 条无备注但证不了非好友）。
  - `verifyFlag`：254 条全 0，零判别力。
  - `deleteFlag`：254 条全 0，筛不出「已删好友」。
  - `conRemark`：仅 12 条有备注，当必要条件会误删 Lynsey（type=0）。
  - `chatroomFlag`：254 条全 0，不是群，无过滤作用。
- **rconversation 交叉：不用**（双刃）：
  - 漏：`rconversation` 命中 `@openim` 仅 **69/254**。254−69=185 条「加了但没聊过」的好友
    会被整片漏掉 —— Lynsey 若没聊过就被漏 ⇒ 验收判据 #2 失败。
  - 误：可能引入「没加好友但聊过」的外部会话。
  - 两个方向都错，且 rconversation 的列（msgCount/unReadCount/…）没有一个是「是否好友」的直接信号。
- **代价权衡**：误收（外部会话进选择器）= 多一个名字，用户可跳过，微信也会在
  「加非好友进群」时拒绝；漏收 = 真好友搜不到（本 bug 本身）。**宁多不漏**。

> 未来若想收紧 type=4 的 139 条，可再探 `uiType` / `contactLabelIds` 两列（本次未取证，
> 当前不做 —— 收紧的前提是「不会误删好友」，而那需要更多一轮真机）。

#### Q2：`@stranger`（59）要不要收？

**不收。** 理由：`@stranger` 是「陌生人」—— 群成员但非好友、或对方发了验证你还没通过。
把他们加进「批量加好友」会污染分组（用户根本不可能想给陌生人建分组），且与「好友」
语义直接冲突。陌生人 ≠ 好友，倾向不收，与主会话一致。

#### Q3：过滤后预期条数

**补充直读只读 `@openim` 254 条**，合并进现有 2257 ⇒ 预期 `friend_cache` ≈ **2511**。

（若按「全量 rcontact 形态过滤」的算式，是
`23154 − 841(@chatroom) − 933(@app) − 59(@stranger) − 1(@fakeuser) − N(gh_) − S(system_ids) ≈ 21320 − N − S`
—— 但那个数字**不代表真好友数**，因为 no-`@` 的 2.1 万条里绝大多数是群成员/已删好友，
见 §2.2。所以本 lane **不采用全量算式**，改用「2257 + 254 = 2511」这个有据可依的数字。）

---

## §4 实现细节（文件:行号）

### 4.1 `data/ContactFilterLogic.kt`（新建，纯判据，零 Android）

- `object ContactFilter`：`enum Kind { FRIEND, CHATROOM, OFFICIAL_ACCOUNT, UNKNOWN }`。
- `classify(wxid)`：形态判据单源。排除顺序：blank → `@chatroom` → `gh_` → `SYSTEM_IDS`
  → `contains("@app")` → `@stranger` → `@fakeuser` → 其余 `FRIEND`（含 `@openim`）。
- `isFriendShape` / `isOfficialAccount` / `isChatroom` 三个便捷谓词。
- `needsRcontactSupplement(wxid)`：仅 `@openim` 返回 true —— 这是「要直读补」的唯一形态。
- `RcontactRow`（纯数据类）+ `shouldSupplementFromRcontact(row)`：rcontact 直读补录的
  **可执行判据**，KDoc 里逐字段写明「为什么忽略 type/verifyFlag/deleteFlag/chatroomFlag/
  conRemark、为什么不用 rconversation 交叉」（见 §3.3 Q1.5）。生产路径把真实行喂进来，
  单测把真机分布喂进来，判据不是「只写在文档里」。

### 4.2 `data/Friend.kt:34-41`（改）

- `WxidKind` 的规则与常量全部下放给 `ContactFilter`，`typeOf` 只做
  `Kind → GroupDb.WxidType` 的穷举映射。判据单源，不再有两份 `SYSTEM_IDS`。

### 4.3 `core/ContactSqlSource.kt`（新建，直读源）

- `install(context)`（:90）：`context.classLoader` 找 `com.tencent.wcdb.database.SQLiteDatabase`，
  hook `rawQuery`（`XposedBridge.hookAllMethods`），拿 `EnMicroMsg.db` 即卸载。
- `captureHook`（:112）：`sql.contains("rcontact")` 且 `isMainDb(db)` 命中 → 记句柄 + 卸载 + 补读。
  `CAPTURE_MAX_CALLS=20000` 兜底认输，防常驻。
- `isMainDb`（:145）：`getPath()` 反射 + `endsWith("EnMicroMsg.db")` 认库，命中把句柄记进 `mainDb`。
- `trySupplement`（:186）：兜底入口，句柄已捕获但没补成功时再试。
- `doSupplement`（:201）：后台 `select * from rcontact where username like ?`（参数 `%@openim`，
  用 `select *` 而非固定列清单 —— 固定清单里任一列名对不上整条 rawQuery 直接抛，补读会静默失败）
  → 逐行按列名读（`getColumnIndex` 兜底：读不到的单列降级为空，不影响 username 主键列）
  → 构 `RcontactRow` 喂 `ContactFilter.shouldSupplementFromRcontact` 判据
  → `GroupDb.upsertFriends` 合并写入。
  username 列缺失（schema 变了）明确告警放弃，不把「读不到」伪装成「没有」。
  ✅ 列名已确证：取证脚本 `tools/_r21_openim.py` 实际跑通了
  `select type,username,nickname,conRemark,verifyFlag from rcontact`（结果在 `_sns.txt`），
  所以 `nickname`/`conRemark`/`type`/`verifyFlag`/`deleteFlag`/`chatroomFlag` 列名都不是推断；
  `alias` 未取证但读不到会优雅降级为空，无风险。

### 4.4 `core/ContactSync.kt`（改，接入点）

- `init(context)`（:51-58）：`ContactSqlSource.install(context)`，装在 bootstrap（attach）
  阶段，早于微信任何 rcontact 查询。
- `doSync`（:149-152）：开头加 `ContactSqlSource.trySupplement()` 兜底（用户打开通讯录时）。

### 4.5 `data/GroupDb.kt`（**不改**）

理由见 §5-Q5：`@openim` 已由 `WxidKind.typeOf` 判为 `FRIEND`（type=0），
`allRealFriends` / `searchFriends` / `ForwardPicker` 的 `wxid_type=?` 过滤天然覆盖，
数据进来即可被搜到，无需动 type 体系，也无需动表结构（`upsertFriends` 已支持合并）。

---

## §5 工程问题 5 条

### Q1 库归属：怎么认库而不是「遍历所有库试一遍」

**路径认库**（同 SnsGuard 的 `isSnsDb` 先例）：`getPath()` 反射拿路径，`endsWith("EnMicroMsg.db")`
即命中。配合「SQL 已含 `rcontact`」做廉价预筛（string contains 比 getPath 反射便宜），
两层都过才记句柄。**不做 sqlite_master 探针遍历** —— 那是 LabelHuntProbe 的探针策略，
会产生 7 次异常 + 7 条噪声日志（本任务数据「表归属」一栏已经证明只有 EnMicroMsg.db 有
rcontact，其余 7 个库全是 `no such table`）。

### Q2 触发时机：能不能不依赖用户操作

**能。** 捕获钩子挂在 `rawQuery` 上，微信启动时必查 rcontact（会话列表渲染要读联系人名），
所以 `mainDb` 会在启动后几秒内自动捕获，捕获即后台补读一次，**不需要用户打开通讯录**。
⚠️ 硬前提如实说明：**微信必须自己先碰过 EnMicroMsg.db** —— 钩子只能「看到」微信发的查询，
如果某个启动过程微信从头到尾不查 rcontact（理论上不会，启动渲染聊天列表就查），句柄就
拿不到，此时退化为现状（2257 条普通好友不受影响）。另外在 `doSync` 挂 `trySupplement`
兜底：用户打开通讯录时若句柄在但没补成功，再试一次。

### Q3 增量与覆盖：老数据怎么处理

**合并（upsert），不覆盖、不 replace。**
- 用 `GroupDb.upsertFriends`（`CONFLICT_REPLACE`）把 254 条 `@openim` 合并进现有 2257 条。
- **不用 `replaceFriends`**：它是「先 delete 再 insert」，会把 UI 扫到的 2257 条普通好友
  全清掉，而 rcontact 直读只读 `@openim` 一片，无法重建普通好友全集（见 §2.2 全量替换的坑）。
- 老数据（UI 扫到的普通好友）**保留原样**，只新增/刷新 `@openim` 这一片，互不覆盖。

### Q4 性能

- 读：`username like '%@openim'` 是一次 rcontact 全表扫（23154 行，无索引后缀匹配），
  但只返回 254 行，单次 rawQuery **量级 ~10-50ms**，跑在后台线程 `wxgroup-csql`，不进主线程。
- 写：254 条 upsert ≈ **~2ms**（现有 2257 条 upsert 实测 13ms，按比例）。
- 对 `BATCH open ... load` 的影响：`allRealFriends()` 从 2257 行变 2511 行，仍是 <20ms 的量级，
  不劣化。真机判据 #4（load 不劣化）可满足。

### Q5 调用方影响：type 体系改不改

**不改。** 理由：
- `@openim` 由 `WxidKind.typeOf`（现委托 `ContactFilter.classify`）判为 `FRIEND`（type=0）。
- `allRealFriends()`（`GroupDb.kt:956`）`wxid_type=?`、`searchFriends()`（`GroupDb.kt:982`）
  `wxid_type=?`、`ForwardPicker.kt:174-176` `type == FRIEND` 三处都按 `FRIEND=0` 过滤，
  数据以 type=0 进 `friend_cache` 后，这三处**无需任何改动**即可搜到/显示。
- 给企业微信单独加一个 `WxidType` 反而要连锁改这三处的过滤条件 + 迁移存量数据，收益为零
  （企业微信好友在功能语义上就是普通好友）。所以明确：**不改 type 体系**。

---

## §6 单测清单（`data/ContactFilterLogicTest.kt`）

| # | 用例 | 锁住什么 |
|---|---|---|
| 1 | `openimIsFriend` | `@openim` → FRIEND 且 `needsRcontactSupplement=true`（真机样本 wxid） |
| 2 | `chatroomIsNotFriend` | `@chatroom` → CHATROOM，非好友、不需补 |
| 3 | `officialAccountIsNotFriend` | `gh_` → OFFICIAL_ACCOUNT；`@app` → UNKNOWN，都不算好友 |
| 4 | `systemIdsAreNotFriend` | `weixin/filehelper/newsapp/…` → UNKNOWN |
| 5 | `strangerAndFakeuserAreNotFriend` | `@stranger`/`@fakeuser` → UNKNOWN |
| 6 | `plainWxidIsFriend` | `wxid_` 前缀、自定义 id → FRIEND，且不需补 |
| 7 | `blankIsUnknown` | 空串/空白 → UNKNOWN |
| 8 | `appFragmentDoesNotLeak` | `contains("@app")` 语义保留（`foo@appbar` → UNKNOWN） |
| 9 | `openimNotConfusedWithApp` | `@openim` 不含 `@app` 子串，两分支互斥，不会被误伤 |
| 10 | `supplementAcceptsAllOpenimTypes` | rcontact 补录：type=0/1/4/5/513 **五个 type 全收**（type 是噪声） |
| 11 | `supplementKeepsType1WithRemark` | **硬回归**：12 条带备注的 type=1 全收（取 3 条真机样本作代表） |
| 12 | `supplementKeepsType4WithoutRemark` | type=4 无备注（139 条）也收 |
| 13 | `supplementKeepsLynsey` | Lynsey 样本（type=0 无备注）收 → 验收判据 #2 |
| 14 | `supplementIgnoresNoiseFlags` | verifyFlag/deleteFlag/chatroomFlag 被忽略；形态不对（@chatroom/@stranger/gh_/wxid_）不收 |

> 说明：`WxidKind.typeOf` 已委托 `ContactFilter.classify`，其剩余逻辑只是
> `Kind→Int` 的穷举映射（4 行），无需单独测；`WxidKind` 的判据覆盖 = 本测试。
> 未跑构建 —— 按硬约束，构建与测试由主会话统一跑。

---

## §7 真机验收判据（与主会话对齐）

| # | 判据 | 预期 |
|---|---|---|
| 1 | `select count(*) from friend_cache where wxid like '%@openim'` | **>0**（当前 0，目标 ≈254） |
| 2 | 分组批量添加 → 搜「Lynsey」能搜到 | 命中 `25984981937809637@openim` |
| 3 | `select count(*) from friend_cache` 不暴跌 | 2257 → **≈2511**；`wxid_type` 分布 `gh(1)/chatroom(2)=0` |
| 4 | `BATCH open ... load=?ms` 不劣化 | 仍 <20ms 量级（当前 13ms） |
| 5 | （附加）日志 `CSQL supplement: read=254 ... db 2257 -> 2511` | 出现即补读成功 |

回滚：删 `core/ContactSync.kt:56` 的 `ContactSqlSource.install(context)` 一行即可退回现状。

[DONE]
