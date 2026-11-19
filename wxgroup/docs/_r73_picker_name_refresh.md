# r73 转发选人器好友昵称不更新 —— 只读取证

> 视角：只读，不碰设备 / gradle / adb / 代码。宿主微信 8.0.48（2588）。
> 所有行号来自 `app/src/main/java/com/lx/wxgroup/`，均为读取时快照。

---

## 0. 结论速览

**根因（代码证据充分）**：转发选人器只读取本地 `friend_cache` 快照（`ForwardPicker.kt:186`），
自身没有任何好友昵称刷新入口（唯一触发的 `ChatroomSource.runIfNeeded()` 只补群聊，`ForwardPicker.kt:183`）。
本地快照的好友昵称**唯一**靠 `ContactSync.doSync()` 从**通讯录 UI adapter 的对象图**读取更新
（`ContactSync.kt:152-209`、`ContactReader.kt:24-39`），而这条路上有三道节流锁
（adapter 实例去重 `:66`、进程内 5 次上限 `:70` / `:30`、30 分钟 crawl 冷却 `:42/:87-91`）。
`ContactSqlSource` 虽已捕获 `EnMicroMsg.db` 句柄、具备直读 rcontact 的能力，
但只把它用于**一次性补 `@openim` 好友**（`ContactSqlSource.kt:53/:250-333`），
**从不刷新普通好友的 nickname/remark**，也不监听 rcontact 变化。
⇒ 好友改名后，只要没走「打开通讯录 → ContactSync 成功重跑」这条链，`friend_cache` 一直是旧昵称，
转发选人器自然显示旧值。

**推荐修法**：候选 (a) —— 在 `ContactSqlSource` 新增一个「按 wxid 批量读 rcontact 的
nickname/conRemark/alias → `upsertFriends` 覆盖」的轻量方法，由 `ForwardPicker.loadTabs()` 在建 Tab 前调用。
理由：rcontact 是落地权威表，直读同时绕开「微信内存 h4 对象可能是旧值」的问题（见 §5）；
单表点查/批查代价远低于全表扫；不冲击 `ContactSync` 的 `runCount` 与 `ChatroomSource` 的 CAS 锁。
候选 (b) 的 `op=room sub=sync` **不刷好友昵称**（只写 `@chatroom` 行），且 6h 节流，排除。
候选 (c) 仅靠用户操作不可靠，见 §4/§5。

---

## 1. `ContactSync.doSync()` 到底从哪读好友数据？刷新粒度如何？

### 1.1 数据源：通讯录 UI adapter 的对象图，不是 rcontact 普通好友行

**证据链**：

1. 触发源是 UI：`UiProbe.dumpList()` 抓到 adapter，认出 `CONTACT_ADAPTER` 后调
   `ContactSync.onContactList(listView, adapter)` —— `UiProbe.kt:243-266`（关键 `:261/:264`）。
2. `doSync` 真正取数的两行：
   - `ContactSync.kt:162`：`val all = if (full) crawl(listView, adapter) else ContactScanner.scan(adapter)`
   - `crawl()`（`ContactSync.kt:107-131`）每轮 `ContactScanner.scan(adapter)` 并合并（`:119`）。
   - `ContactScanner.scan()`（`ContactScanner.kt:40-66`）BFS 遍历对象图，命中实体就交给
     `ContactReader.read()`（`:58`）。
3. `ContactReader.read()`（`ContactReader.kt:24-39`）读的是**微信内存联系人实体**
   （`Anchors848.CONTACT_CLASS`，8.0.48 实测 `com.tencent.mm.storage.h4`，见 `:10/:25`）
   的 String 字段：`nickname` / `remark` / `alias` 分别来自 `FIELD_NICKNAME` / `FIELD_REMARK` /
   `FIELD_ALIAS`（`:34-36`）。**不是**从数据库列读。
4. 落库：`ContactSync.kt:189` `db.upsertFriends(all)`，按 wxid `CONFLICT_REPLACE`
   （`GroupDb.kt:789-791`，`wxid` 是 PRIMARY KEY `GroupDb.kt:372`）整行覆盖 nickname/remark/alias/type
   （`GroupDb.kt:783-787`）。 ⇒ **写路径本身支持更新 nickname**，问题不在写，在「读不到新值 / 根本没触发」。
5. 代码自己的定性：`ChatroomSource.kt:26-28` 明说 `ContactSync.doSync`（其 `:186`）遍历的是
   **通讯录 tab 的 adapter 对象图**。

### 1.2 `ContactSqlSource` 的角色：只补 `@openim` 窄片，不读普通好友

- 设计目的写死在 KDoc：`ContactSqlSource.kt:18-35` —— 补的是「通讯录 UI 不渲染」的
  企业微信好友 `@openim`（真机 254 条），SQL 常量 `SUPPLEMENT_LIKE = "%@openim"`（`:53`），
  查询 `select * from rcontact where username like ?`（`:266`）。
- 明确拒绝全量读 rcontact 的理由（`:31-35`）：rcontact 里混着群成员/已删好友/公众号/陌生人，
  「全量替换要么按 type 硬筛（已证不可行），要么把上万条非好友灌进 friend_cache」，
  所以只读 `like '%@openim'` 这一窄片，用 `upsertFriends` **合并**，谁也不覆盖谁。
- 句柄捕获 hook 在一次成功后即卸载：`ContactSqlSource.kt:101/:112-139`（`:131` unhook），
  捕获判据是「SQL 含 rcontact」+「`getPath()` 以 `EnMicroMsg.db` 结尾」（`:126-127/:145-167`）。
- `doSupplement()` 只跑一次：`supplementDone` 标志（`:65/:236/:317/:328`），跑完永不重跑。
  **它不会因好友改名而重新读 rcontact。**

⇒ 回答 Q1：**两条源并存但分工不同**——
- **ContactSync（对象图）**：刷新粒度 = 通讯录 adapter 当前持有的联系人对象；全量模式靠滚列表凑齐
  约 2245 条（`:33-35/:107-131`，最多 30 轮、每轮 700ms）；每次把扫到的**所有类型**整行覆盖写回
  （注意 `:189` 写的是 `all` 而非过滤后的 `friends`）。受 5 次上限 / 30min crawl 冷却 / adapter 去重限制。
- **ContactSqlSource（rcontact 直读）**：刷新粒度 = 仅 `username like '%@openim'` 的 254 条，一次性补读合并；
  **普通好友的 nickname/remark 完全不经这条路**。

### 1.3 为什么有了 rcontact 直读还得滚通讯录？

不是「拿不到句柄」，而是**刻意不读普通好友**：微信自己按好友关系过滤好的通讯录 UI 才是普通好友的
权威视图（rcontact 直读会把非好友灌进来，见 `ContactSqlSource.kt:31-35`）。
而通讯录是懒加载的（一次几百条，真实 2245，见 `ContactSync.kt:33-35/:100-106`），
所以要靠滚列表逼微信加载剩下的。

---

## 2. 改名后要更新到新昵称，用户必须做什么？确切触发路径

**唯一路径（代码证据充分）**：

1. 用户**打开微信「通讯录」tab** → adapter 被设置 → `UiProbe.dumpList()` 认出 `CONTACT_ADAPTER` →
   `ContactSync.onContactList()` —— `UiProbe.kt:261/:264`。
2. `onContactList()` 过三关：
   - adapter 实例去重 `touched`（`ContactSync.kt:45/:66-69`）—— 同一个 adapter 只触发一次；
   - 进程内次数上限 `runCount >= MAX_RUNS(5)` 就永久跳过（`ContactSync.kt:30/:70-73`）——
     **冷启前最多 5 次，之后打开通讯录也不再同步**；
   - 通过则起 `wxgroup-sync` 后台线程（`:76-79`）。
3. `doSync()`：
   - `ContactSqlSource.trySupplement()`（`:155`）—— 只补 @openim，且 `supplementDone` 后 no-op；
   - `shouldCrawl()`（`:87-91`）：库里 <200 人 **或** 距上次全量 >30min（`FULL_INTERVAL_MS`，`:42`）才 crawl；
     否则只 `visible-only` 扫当前 adapter（`:162`）；
   - crawl 滚列表（`:107-131`）→ `ContactScanner.scan()` → `ContactReader.read()` 从 h4 对象取新 nickname；
   - `db.upsertFriends(all)` 覆盖写回（`:189`），昵称/备注在此更新；`markFullSync()`（`:197`）；
   - 末尾 `ChatroomSource.runIfNeeded()`（`:202`）**只补群聊**。
4. **下一次**打开转发选择器，`ForwardPicker.loadTabs()` 读 `db.allFriends()`（`ForwardPicker.kt:186`），
   才显示新昵称。

**不会发生的路径**：`ContactSqlSource` 后台自动捕获普通好友 rcontact 变化 —— 它的 hook 只用于**抓库句柄**
（拿到即卸载），`doSupplement()` 只读一次 @openim（`ContactSqlSource.kt:240-333`）。
⇒ 单纯改名、不开通讯录：本地缓存不动。
⇒ 打开通讯录但已触发满 5 次或 30min 内：走 visible-only 或不触发，仍可能看不到更新。

---

## 3. 现成的「按需刷新单个好友昵称」能力盘点

| 能力 | 位置 | 结论 |
|---|---|---|
| `op=room sub=sync` | `EntryReceiver.kt:323-350`，实际调 `ChatroomSource.syncBlocking(force=true)`（`:331`） | **不能刷好友昵称**。全表扫 rcontact 但 `readChatrooms()` 只保留 `@chatroom` 后缀行（`ChatroomSource.kt:363-401`，关键 `:364/:382`），`upsertFriends(rooms)` 只写群聊（`:214`）。另有 6h 节流（`FULL_INTERVAL_MS`，`:64-70/:183`），非即时。 |
| `ContactSqlSource.who(rawIds)` | `ContactSqlSource.kt:202-229` | **只读诊断，不写缓存**。按 wxid 列表查 rcontact 的 nickname/conRemark/alias（`:208/:218-221`），只格式化成日志字符串返回；且 `.take(30)`（`:203`）封顶 30 个。 |
| `ContactSqlSource.ready()` / `mainDbOrNull()` | `:180` / `:191` | 可判断句柄是否捕获、拿到原始 `EnMicroMsg.db` 句柄（`internal`）。新增刷新方法可复用。 |
| `rawQueryMethod` / `columnIndexOf` / `cText` / `cLong` | `:342-369` | private 反射工具，新增方法可直接复用（同 `who()` 的做法：`select *` + 按列名取，规避跨版本列名差异）。 |
| `queryByWxid` / `allFriends` 返回 `Friend` | —— | **不存在**。全库 grep 无此接口。`GroupDb.findFriend/allFriends`（`GroupDb.kt:847/:870`）读的是本地缓存，不是 rcontact。 |

⇒ 回答 Q3：**没有现成的单好友昵称刷新 API**。最接近的是 `who()`，但它只读不写、且限 30 个；
底层句柄与反射工具齐备，加一个批量刷新方法是小改动。

---

## 4. 最小修法方向评估与推荐

前提事实：`upsertFriends` 用 `CONFLICT_REPLACE`（`GroupDb.kt:789-791`），喂进去的同 wxid 新 Friend
会覆盖旧 nickname/remark/alias —— **写路径无需改**，只缺「把新值喂进来」的触发。

### (a) 在 `ForwardPicker.loadTabs` 触发一次轻量 rcontact 昵称刷新 —— **推荐**

做法（仅描述）：在 `ContactSqlSource` 仿 `who()` 新增 `refreshNames()`：用已捕获的 `mainDb` 读
`select username,nickname,conRemark,alias from rcontact`（按 wxid 批查或一次全表扫均可），
组装 `Friend(wxid, nickname, remark, alias, type=FRIEND)` 后 `GroupDb.upsertFriends` 覆盖；
`ForwardPicker.loadTabs()` 在读 `db.allFriends()`（`:186`）之前调用。

- 工程量：小。新增 1 个方法 + 1 处调用；复用现成句柄与反射工具，不引入新 hook、不碰设备。
- 性能：`rcontact.username` 是主键，按 2245 个 wxid 批查（注意 SQLite 变量上限，分批）
  比 `ChatroomSource` 的全表扫（`ChatroomSource.kt:364` 注为「几百毫秒级」）更轻。
  切勿每次切 Tab / 每次 refresh 都跑 —— 用短冷却（如 1~5min）或仅首次 build 跑。
- 对既有锁的冲击：**无**。不占用 `ContactSync.runCount`、`touched`，也不抢 `ChatroomSource.loading` CAS。
- 附带收益：直读 rcontact 绕开「微信内存 h4 对象可能是旧值」的问题（见 §5）。
- 需注意的坑：`ForwardPicker` 的 `tabs` 只在首次 build 装载、refresh 复用（`ForwardPicker.kt:77-79/:100-104`）。
  若刷新走异步，已构建的 Tab 不会自动更新；要做原地刷新需持有 View 并处理页退出——注释已承认这类改动
  需真机验证（`ForwardPicker.kt:179-182`，群聊那次的未做的一半）。⇒ **首开即时刷新宜同步做**（若实测足够快）
  或「异步刷完 + 清 `tabs` 重建」，后者工程量上升。

### (b) 复用 `op=room sub=sync` 的全表扫 —— **不可行（原样）**

`ChatroomSource.readChatrooms()` 只写 `@chatroom` 行（`ChatroomSource.kt:382/:214`），好友昵称一条不刷；
且 6h 节流（`:64-70`）保证「改名后立刻转发」拿不到新值。要改造它同时收集 FRIEND 行，
等于在做 (a) 但多背一层 6h 节流与 `loading` CAS 耦合，不更省。

### (c) 提示用户改名后冷启 / 滚通讯录（不动码）—— **不可靠**

- 冷启本身不刷新 `friend_cache`：`ContactSync` 只在打开通讯录时由 UiProbe 触发（§2），冷启不会自动重查。
- 滚通讯录受 5 次上限 + 30min 冷却 + adapter 去重（§2），且数据源是可能滞后的 h4 内存对象（§5）。
- 只能作为临时话术，不是修法。

**推荐**：(a)，并保留 (c) 作为兜底提示。若担心首开同步耗时，可把刷新放进
`ChatroomSource.runIfNeeded()` 已有的后台线程里顺带做，但要给它**独立的短冷却**，不要复用 6h `shouldRun`。

---

## 5. 是不是「改名后立刻转发」才出现？冷启一次就好吗？（微信内存缓存）

**结论**：「冷启一次就好」**不是模块缓存自动修好**，而是冷启重建了微信自己的内存联系人对象 + 重置了模块进程内计数，
让用户再打开通讯录时 `ContactSync` 能读到新的 h4 字段。单纯冷启、不打开通讯录，模块缓存不会变。

**证据**：

- 我方 `ContactReader.read()` 读的是微信**内存 h4 对象的字段**（`ContactReader.kt:24-39`），不是 rcontact 表。
- 项目自己两条注释明确记载微信内存联系人缓存的滞后性：
  - `Keys.kt:76-79`：「微信把会话标题/联系人放在内存缓存里，纯写库不刷列表、改名不重启也不生效……
    改名统一走 `GroupConvStore.sync()` 写 `rcontact.nickname`，**下次冷启生效**。」
  - `ConvFilter.kt:1812-1815`：「改名统一走 `GroupConvStore.sync()` 写 rcontact.nickname，
    微信有联系人内存缓存，**下次冷启才生效**……所以这里只接点击/长按，不碰标题、不 setText。」
- 这两条针对的是**我方直接写 rcontact.nickname**（gconv 改名）的场景，揭示的事实是：
  rcontact 表与微信内存联系人缓存可能不同步，内存侧要冷启才刷新。

**对我方缓存刷新的影响（推断，需标证据不足）**：
- 若改名走微信自己的 UI（对方改昵称 / 我方在微信里改备注），微信一般会同步更新内存缓存，
  此时打开通讯录重跑 `ContactSync` 能读到新值（但仍受 5 次 / 30min 节流）。
- 若改名是外部/我方直接写 `rcontact.nickname`，微信内存缓存要到冷启才更新，
  此时即使滚通讯录，`ContactReader` 从 h4 对象读到的仍是旧值 ⇒ 必须冷启。
- ⚠️ **证据不足处**：上述两条注释来自 gconv 伪会话改名经验，能否外推到「好友远程改名 / 用户改备注」
  的内存缓存刷新时机，代码里**没有直接证明**，需真机观察确认。
- **直读 rcontact 不受此影响**：`rcontact.nickname` 是落地权威值（改名确实写它，由 `Keys.kt:79` 佐证），
  候选 (a) 直读它即可绕过内存缓存滞后。

---

## 6. 需用户确认的现象（无法从代码单独判定）

1. 用户所说「改名」具体是哪种：对方改昵称 / 我方在微信改备注 / 我方模块写 rcontact？
   —— 决定微信内存缓存是否已同步（§5）。
2. 「冷启一次就好」是否属实：冷启后**是否还打开了通讯录**？若没打开却好了，说明另有我方未知的重读路径，
   当前只读取证未发现，需补日志。
3. 5 次上限是否已触发：微信号若长期开着，很可能早已用满 5 次（`ContactSync.kt:30/:70`），
   此后无论怎么滚通讯录都不再同步 —— 可用 `SYNC trigger: run limit reached` 日志（`ContactSync.kt:71`）确认。
4. 转发选择器首开时的 Tab 缓存：是否已打开过一次选择器（`tabs` 已装载，`ForwardPicker.kt:100-104`），
   这决定候选 (a) 需要同步刷新还是「异步 + 重建 Tab」。

---

## 7. 证据充分度自检

- Q1 数据源与粒度：**充分**（`ContactSync.kt:152-209`、`ContactReader.kt:24-39`、`ContactSqlSource.kt:18-43/:250-333`、`ChatroomSource.kt:26-30`）。
- Q2 触发路径：**充分**（`UiProbe.kt:261/:264`、`ContactSync.kt:30/:42/:66/:70/:87-91/:155/:162/:189/:197/:202`）。
- Q3 现成能力：**充分**（`EntryReceiver.kt:323-350`、`ChatroomSource.kt:214/:382`、`ContactSqlSource.kt:202-229/:180/:191`）。
- Q4 修法评估：**充分**（性能数字「几百毫秒级」来自 `ChatroomSource.kt:201` 注释；2245 来自 `ContactSync.kt:33`）。
- Q5 冷启 / 内存缓存：**部分不足** —— 注释佐证「我方直接写 rcontact 需冷启」，
  但「好友远程改名 / 用户改备注」的内存缓存时机无直接代码证据，已在 §5 标注，需真机确认。

---

## 实现

按 §4 候选 (a) 落地，选**同步刷新**（tabs 首开装载后 refresh 复用，同步才能保证本次打开即新昵称，
且改动最小；异步需持有 View + 处理「页已退出」重建 Tab，复杂度高）。

### 改动文件与行号

- `app/src/main/java/com/lx/wxgroup/core/ContactSqlSource.kt`
  - `:231-345` 新增「昵称刷新」小节：KDoc `:233-261`、`fun refreshFriendNames(): Int` `:262-342`、
    分批常量 `REFRESH_BATCH_SIZE=400` `:344-345`。
  - 方法要点：`:263` 用 `mainDbOrNull()` 取句柄；`:272` `groupDb.allFriends()` 建本地
    `wxid → 原 type` 映射（`:276-277`）；`:284-289` 本地 wxid 去重后按 400 分批；
    `:292` SQL `select username,nickname,conRemark,alias from rcontact where username in (...)`；
    `:300-308` 缓存 4 个列下标（每行只付 4 次 getString 反射）；`:317-327` 交集守卫
    `typeByWxid[wxid] ?: continue` + 组装 `Friend(type=本地原值)`；`:337` `groupDb.upsertFriends()` 覆盖写回；
    全程 runCatching，失败只 WARN 返回 0。
- `app/src/main/java/com/lx/wxgroup/feature/ForwardPicker.kt`
  - `:17` 新增 `import ...core.ContactSqlSource`；`:186-194` 在 `loadTabs()` 读 `db.allFriends()`（`:197`）
    **之前**同步调用 `ContactSqlSource.refreshFriendNames()`（`:193`，外层 runCatching 保护），
    KDoc 注释注明同步理由与性能假设；`:184` 原有 `ChatroomSource.runIfNeeded()` 未动，两者并存。
- `app/src/test/java/com/lx/wxgroup/feature/R73PickerNameRefreshTest.kt`（新增，10 个用例，纯 JVM）
  - 5 个逻辑等价复刻：`newNicknameOverridesOld`（新 nickname 覆盖 verity）、`typeKeptFromLocalCache`
    （群聊 type=2 不串改成 FRIEND）、`onlyIntersectionIsWritten`（公众号/陌生群不进缓存）、
    `displayNamePriorityUnchanged`（备注>昵称优先级未改）、`upsertReplacesWholeRow`（整行替换）。
  - 5 个源码断言：`refreshMethodReadsRcontactColumns`、`refreshMethodFiltersByLocalWxids`、
    `refreshMethodPreservesType`、`pickerCallsRefreshBeforeReadingCache`（含调用顺序 callAt<readAt）、
    `pickerKeepsChatroomSupplement`。文件头 KDoc 逐条写明 M1~M6 变异有效性。

### 方案评估与性能假设

- **SQL 方案选择**：采用「按本地 wxid 批查」（`where username in (...)`，每批 400，约 6 批），
  **不是**全表扫后再过滤。理由：`rcontact.username` 是主键，点查是索引命中；真机 rcontact 混着
  `@app 933` + `@chatroom 841` + `@stranger 59` + `@fakeuser 1` 等非好友（见 §1.2），
  批查只回读约 2245 个本地已有 wxid，比全表扫少一半以上行数与反射开销，内存也只持有交集结果。
  两道交集保险：SQL 的 `in (本地 wxid)` + Kotlin 侧 `typeByWxid[wxid] ?: continue`，确保**只更新已有行、
  绝不新增非好友**；`type` 取本地原值，避免把群聊行改成好友类型。
- **性能假设（需真机确认）**：2245 好友 × 只读 4 列 × 缓存列下标（每行 4 次 getString 反射），
  预期 <100ms。若真机超标，退路是「异步刷新 + 清 `tabs` 重建」，但需处理页已退出、选中集保留等，
  复杂度显著上升，故本次先取同步方案。
- **未碰红线**：`Friend.displayName` 优先级、`GroupDb` 建表/索引/其它查询、`ContactSync` 三道节流、
  `ForwardPicker` 的 Tab 结构/搜索/选中集/Result 语义、其它 picker 均未改。

### 待主会话闸门跑单测 + 真机确认

单测本代理未跑（按分工由主会话统一执行）。真机判据：打开转发选人器「好友·群聊」Tab，
该好友（`wxid_xlrbelf6hv0w22`）应显示 **「路过人间」** 而非旧值 **「verity」**；
群聊 Tab 不应出现类型被串改的群（type 保留为 CHATROOM）。

---

## 节流修复（R73-t：转发选人器打开卡顿）

### 问题与主会话真机铁证

用户反馈「显示正常了，打开选人器会卡顿」。主会话抓日志：

```
02:28:09.637 I/WXGroup: CSQL refresh names: read=3364 upsert=3364
02:28:09.649 I/WXGroup: FWDPICK load tabs=3 names=好友·群聊,模块分组,微信标签
02:28:50.076 I/WXGroup: CSQL refresh names: read=3364 upsert=3364
02:28:50.087 I/WXGroup: FWDPICK load tabs=3 names=好友·群聊,模块分组,微信标签
```

每次打开选人器都跑一次全量刷新（read=3364）。复核 `docs/_r73_picker_name_review.md` §2 已标 P1：
`ForwardPicker.build()` 在主线程（`Panel.kt:197-207`），`ForwardPicker` 每次转发都 new（`ForceForward.kt:1166`），
而 `loadTabs()` 每次都同步调 `refreshFriendNames()` ⇒ 每开一次就全量刷一次。真机约 8~9 批 SQL、
约 1.6 万次 `XposedHelpers.callMethod`（几十~低百 ms），短时间反复打开即累积成可感知卡顿。

### 修法：进程级时间戳节流（不碰取数主体、不改成异步）

只加「节流外壳」，取数 / SQL / 交集 / type 保留 / upsert 主体**一行未动**；
首次打开仍**同步**刷新（异步需重建 View + 处理页已退出，复核确认复杂度高，不改）；
节流只消除「30s 内重复打开」的重复全量刷。

- `app/src/main/java/com/lx/wxgroup/core/ContactSqlSource.kt`
  - `:4` 新增 `import android.os.SystemClock`（单调时钟，避免墙钟回拨误判冷却）。
  - `:234-251` 新增三个成员：`FRIEND_NAMES_REFRESH_COOLDOWN_MS = 30_000L`（`:240`，30 秒——好友改名低频，
    30s 内重复打开不必重刷；改名后需立刻生效则等 30s 或冷启，可接受）、
    `@Volatile private var lastFriendNamesRefreshAt = 0L`（`:247-248`，单调时刻、0=从未成功刷过）、
    `private val refreshNamesLock = Any()`（`:251`，串行锁，防多实例并发重复刷）。
  - `:277-282` KDoc 纠正旧错误声明「只在首次 build 触发」（实际每次 new 都调），补节流说明。
  - `:288` 方法改为 `= synchronized(refreshNamesLock) { ... }` 表达式体；内部 `return 0` 相应改为
    `return@synchronized 0`（仅控制流语法适配，无逻辑变化）。
  - `:291-297` 开头冷却判断：`last > 0L && now - last < COOLDOWN` 时打
    `CSQL refresh names skipped: cooling down age=<n>ms` 并 `return 0`，不跑任何 SQL / 反射。
  - `:376-377` **仅 `written > 0`（成功写回）才更新 `lastFriendNamesRefreshAt`**；
    句柄未就绪 / GroupDb 未就绪 / local 为空 / 查询失败 / upsert 失败均不推进时间戳 ⇒ 下次打开还能重试
    （同 g0 P1 的教训）。
  - 旧 KDoc `:255` 的「<100ms 性能假设」保留未动。
- `app/src/main/java/com/lx/wxgroup/feature/ForwardPicker.kt`：**未改**（调用点、顺序、runCatching 保护原样）。
- 红线：`Friend.displayName`、`GroupDb`、`ContactSync`、其它 picker 均未碰。

### 单测（纯 JVM，追加 5 例，合计 15 例）

`app/src/test/java/com/lx/wxgroup/feature/R73PickerNameRefreshTest.kt`：

- 源码断言 1 例 `refreshMethodHasCooldownThrottle`（`:302-322`）：锁 COOLDOWN 常量=30_000L、
  @Volatile 时间戳字段、开头冷却判断、`cooling down age=` 日志、`last > 0L` 守卫、
  `if (written > 0) lastFriendNamesRefreshAt`、`synchronized(refreshNamesLock)`、`SystemClock.elapsedRealtime()`。
- 逻辑复刻 4 例（`:189-245`，`ThrottleReplica` 等价副本 `@172-187`）：
  `throttleSkipsWithinCooldown`（刚刷过 age<30s → skip，且不跑刷新、不推进时间戳）、
  `throttleAllowsAfterCooldown`（age>30s → 放行）、`throttleAllowsOnFirstOpen`（时间戳=0 → 放行）、
  `throttleAllowsRetryAfterFailedRefresh`（失败未更新时间戳 → 仍按旧成功时刻算 age → 允许重试；
  从未成功过时永不进冷却）。
- 文件头 KDoc 补 M7/M8 变异有效性说明，并明确「逻辑复刻是等价副本、真正随生产变异变红的是源码断言层」
  （同复核 §6.2 结论）。

变异有效性：去掉开头冷却判断 → `refreshMethodHasCooldownThrottle` 红；把时间戳改成无条件更新（含失败路径）
→ 该源码断言红，且逻辑复刻的失败重试语义亦被锁住。

### 真机判据（区分「真刷了」vs「冷却跳过」，请主会话验证）

1. 冷启后**首次**打开转发选人器（30s 内无成功刷新）：仍见 `CSQL refresh names: read=3364 upsert=3364`
   —— 首开同步刷新不变，昵称正常显示新值（本次卡顿只降不升，仍是那一次全量刷）。
2. **紧接着 30s 内再开**：只应见 `CSQL refresh names skipped: cooling down age=<n>ms`，
   **不应**再出现 `CSQL refresh names: read=... upsert=...` 行 —— 即重复打开不再全量刷，卡顿消除。
3. 等 >30s 后再开：应重新出现 `read=... upsert=...`（冷却到期，能刷新改名）。
4. 失败可重试：若句柄未就绪 / upsert 失败那次只 WARN 不推进时间戳，下次打开应能重试刷新。

（单测本代理未跑，按分工由主会话统一执行 gradle 闸门。）

### 真机结果回填（R73-t，主会话，02:31 后）

- 装机前闸门：`assembleDebug EXIT=0` / `compileDebugUnitTestKotlin EXIT=0` / 全量单测 **OK 992 tests**（987 + 5）。
- 装机后首启未挂载，二次 `dev.sh restart` 后模块日志恢复。
- 用户操作判据：第一次打开转发选人器仍有一次全量刷新带来的卡顿，昵称显示 **「路过人间」**（旧值 `verity` 已消失）；紧接着 30s 内第二次打开 **不卡**。
- ⇒ 体感判据通过：首刷保证昵称即时生效，进程级 30s 冷却消除了短时间重复打开的全量刷。
- 日志补抓：事后 `adb logcat -d` 宽泛过滤 `CSQL refresh names|FWDPICK load|cooling|read=|upsert=` 为空，判断为环形缓冲覆盖 / 日志已清导致；本次以用户体感 + 首刷前后强对比为准，不阻塞结论。
- 未做：等 >30s 后的第三次打开日志复核（单测已锁冷却到期放行与失败可重试）；若后续要排障，可在打开选人器前 `logclear` 后连开两次抓 `cooling down age=<n>ms`。

[DONE]
