# R65 · 宿主会话缓存重查入口只读取证（Pro 级）

> 角色：r65-host-cache-probe。本轮**未改任何代码、未跑 gradle、未碰 adb/设备**；主会话持设备锁与构建锁。
> 宿主：微信 8.0.48 / versionCode 2588，权威源 `_cache/mm.apk`，解包 dex `_cache/dex/classes*.dex`（15 个）。
> 判据纪律（CLAUDE.md §四/§五 + `docs/开发回路速查.md`）：只认「用户能看见的事实」+ dex 原文 + 源码行号 + 真机日志时间戳；
> 「A 推 B」时序结论一律标 `[推断]` / `[需真机]`，不冒充 `[已证实]`。
> 证据分档：`[已证实]` = dex 原文/源码/真机栈直接可见 ｜ `[推断]` = 由已证实事实外推 ｜ `[需真机]` = 有锚点无实测 ｜ `[取证缺口]` = 材料不存在/解不出。

---

## 1. 结论摘要

| 问题 | 结论 | 等级 |
|---|---|---|
| 宿主重查入口存不存在 | **存在，且有两个**：① 会话存储 `com.tencent.mm.storage.s4`（ConversationStorage）的 SQL 查询方法族 + 通知入口 `b(int, s4, Object)`；② adapter 侧 `com.tencent.mm.ui.conversation.p3.f(): La94/e;` 直接执行 `select ... from rconversation ... order by flag desc`，与 `t0.N()` 的 `s4.t()` 重查 + `w8.W(cursor)` 换游标 | `[已证实]` dex |
| 通知入口混淆名 | **`com.tencent.mm.storage.s4.b(int, com.tencent.mm.storage.s4, Object)`**（继承自 `Lk74/y0;`），宿主自有写库方法用事件码 **2=insert / 3=update / 5=delete**。另有通用通知器 `Lk74/q0;.doNotify()/.doNotify(String)/.doNotify(String,int,Object)`（classes5.dex） | `[已证实]` dex |
| 不 hook 私有方法能否解决 | **不能**。dex 字符串扫描未发现任何「通知会话表变了」的公开 ContentObserver / 广播入口（只有 wcdb 内部游标跟踪用的 `ContentObserver` 与 `SettingsContentObserver`）。必须从 module 侧反射调用上述混淆方法 | `[已证实]`（未见公开入口）+ `[取证缺口]`（不排除未扫到的隐蔽入口） |
| 写库后调它能否让 `p3.getCount()` +1 并原生渲染 | **机制上成立但未实测**：dex 已证通知链 `s4.b→k74/t0→p3.e→i3.r/s→i3.u→i3.f()` 会重新执行 rconversation 查询；但 `i3.s()` 有 `a94.e.P(username)` 门，**未知 username 有被过滤风险**，必须真机探针确认 | `[推断]` + `[需真机]` |
| **原生行 = 像素级一致吗（标题从哪来）** | **会话重查不连带刷新联系人缓存**（两套独立 storage）；但 `p3.getView()→p3.F()→q4.n(username,true)` 在联系人缓存 `p4` **未命中时会回查 rcontact**（dex 原文 SQL，带 `nickname` 列）⇒ **新建组（缓存无条目）标题可自动正确；改名（缓存已有旧值）仍要冷启或额外失效**。微X 全空名靠渲染 hook 注入，不依赖此路 | `[已证实]` dex + `[需真机]` |
| DexKit 反查能力 | **现成可用，无需从零接入**：`probe/DexProbe.kt:6-7,42,58,72-96` 已接入 `DexKitBridge` 并手动从模块 APK 加载 `libdexkit.so`；依赖 `org.luckypray:dexkit:2.2.0`（`app/build.gradle.kts:69`）；目标 `_cache/mm.apk`（379MB）就位。空骨架的只是 `core/DexKitLocator.kt:40` | `[已证实]` 源码 |
| 两条路线优先级（team-lead 更正后） | **首要**：运行时侧观察探针（R64 §4b P1/P5，对比微X建组时宿主 notify / `getCount` / `w8.W` 游标），不碰加密 dex、不装包、不冷启，真机一次性定案微X走 b（撬宿主通知）还是 c（改数据源注入）；**本 dex 入口取证为「探针发现候选 b 后的实现落点储备」**。两条并列，用户暂不取舍（「先别定，看取证结果」） | `[设计裁决]` |
| 一句话推荐 | **有条件可行**：入口拿得到、可调用，但「调用后新行不冷启即出现 + 标题正确」尚未证实，且存在「未知 username 被过滤」与「改名标题走旧缓存」两道风险门。**先跑微X对比探针 / 只读探针，真机正反对照通过后才决定是否真调；默认关灰度** | — |

> ⚠️ **纠正前序文档的一处机制误述**：R54 §3.2 / R63 A2 写「`p3.getItem()` 读 `ConversationStorage` 内存会话列表」。dex 反汇编显示主列表 `p3`（父类 `i3`）与 folded-box `d2`/`t0`（父类 `w8`）**都是游标型 adapter**：`p3.f()` 直接执行 SQL 拿 Cursor，`w8.q()` 持有 `Cursor f`。「纯写库不刷新」的真因是**已有 Cursor 未被关闭/重查**（`w8.q()` 仅在 `f==null||f.isClosed()` 时才 `L()` 重查；`i3.getCount()` 仅在 `f==null` 时才 `f()` 重建），不是「内存列表」。这个差别决定了本轮的修复方向：**触发宿主重查**（而非「往内存列表塞行」）。

---

## 2. A / B / C 逐条结论

### A. 宿主重查入口到底存不存在、叫什么

**A1. 加载 `rconversation` 的 SQL 字符串落在哪个类 → `com.tencent.mm.storage.s4`（ConversationStorage）** `[已证实]`

- `_cache/dex/classes4.dex` 字符串表命中 102 条含 `rconversation` 的字符串（非类名/索引名候选 ~20 条），其中主查询片段：
  - `s[ded6]`（反汇编 4a387c）`select unReadCount, status, isSend, conversationTime, username, content, msgType, flag, digest, digestUser, attrflag, editingMsg, atCount, unReadMuteCount, UnReadInvite, hasTodo, hbMarkRed, remitMarkRed, hasSpecialFollow from rconversation where `
  - `s[ded8]`（4a398e）`select ... parentRef from rconversation where username in (`
  - `s[de4e]`（891342）`select 1,unReadCount,status,isSend,conversationTime,rconversation.username,content,rconversation.msgType,rconversation.flag,rcontact.nickname from rconversation,rcontact where rconversation.username = rcontact.username`
  - 证据形态（对齐 R55c 记法）：`classes4.dex {'Lcom/tencent/mm/storage/s4;':1}`（类定义计数，全 15 dex 唯一）；同 dex 内 `s4` 的 `B/C/D/s/t/u` 等 6 个方法引用上述 SQL。
- 类定义铁证：`s4` 方法 `R(r4)` 的日志 tag 常量为 `"MicroMsg.ConversationStorage"`（反汇编 4a4270 第 0002 行），`<clinit>`/构造签名 `(Lk74/i0;)V`。

**A2. 「通知会话列表变化」的方法混淆名 + 签名** `[已证实]`

- `s4` 的每个写库方法末尾都调用继承自 `Lk74/y0;` 的：
  ```
  k74/y0.b(int, k74/y0, Object) : void     // classes4.dex，code=0x6941e8
  ```
  反汇编：把 `(eventCode, this, payload)` 包成 `k74/w0`，然后
  ```
  iget-object v8, v7, k74.y0.c : k74.t0
  invoke-virtual {v8, v6}, k74.t0->d(Ljava/lang/Object;)Z
  invoke-virtual {v8}, k74.t0->c()V
  ```
  即投给宿主事件总线 `k74/t0`。这就是宿主自己的 `doNotify` 等价物。
- 事件码（逐方法反汇编取 `const/4` 立即数）：
  | 码 | 触发方法 | payload | 含义 |
  |---|---|---|---|
  | 2 | `s4.I(r4):J`（insert，4a5528） | username | 新增会话 |
  | 3 | `s4.R/S/T/U/Y/Q/Z/b0`（置顶/更新/改字段，4a4270 等） | username | 会话变更 |
  | 5 | `s4.k/n/l`（delete/clear，4a5d3c/4a4c74/4a4c20） | username 或 `""` | 删除/全清 |
- 另有通用通知器 `Lk74/q0;`（**classes5.dex**，类定义计数 1），方法 `doNotify():V` / `doNotify(String):V` / `doNotify(String,int,Object):V`，被其它 storage 通过接口 `Lk74/j0;` 调用；`s4` 走的是 `k74/y0.b` 这条路，不是 `k74/q0`。
- 关键汇合点（真机栈，R55d/R64 GconvPage `feature/GconvPage.kt:820-865` 已落盘）：
  `AdapterView$AdapterDataSetObserver.onChanged → w8.getCount → w8.q → W(宿主游标)`；
  `com.tencent.mm.ui.conversation.t0.N()`（`classes4.dex` code=0x4a5008 邻近）反汇编确认是 `s4.t(-1,null,"conversationboxservice",0)` 重查 → `w8.W(cursor)` → `notifyDataSetChanged()`。

**A3. `ConversationStorage` 类名 + 关键方法（取列表 / 重查 / 失效）** `[已证实]`

| 用途 | 类.方法（混淆名） | 签名 | dex:code |
|---|---|---|---|
| 会话存储本体 | `com.tencent.mm.storage.s4` | 父类 `Lk74/y0;`，字段 `d:Lk74/i0;`(=wcdb 连接) | classes4.dex |
| **主列表取数** | `com.tencent.mm.ui.conversation.p3.f` | `()La94/e;` | classes4.dex 反汇编 0x…：SQL `select ... from rconversation where ... order by flag desc`，经 `s4.d.g(sql,null,4)` 拿 Cursor |
| adapter 父类 | `com.tencent.mm.ui.i3` | `getCount/getItem` → `a94.e`/`a94.m` 游标提供者 | classes10.dex，`{'Lcom/tencent/mm/ui/i3;':1}` |
| 存储变更回调 | `com.tencent.mm.ui.conversation.p3.e` | `(int, k74/y0, Object)V` | classes4.dex，日志 `"unreadcheck onConversationStorageNotifyChange event type %d, username %s"` |
| observer 桥 | `com.tencent.mm.ui.conversation.g3.onNotifyChange` | `(String, k74/u0)V` → `p3.notifyDataSetChanged()` | classes4.dex |
| 重查/换游标 | `com.tencent.mm.ui.conversation.t0.N` | `()V` → `s4.t(...)→w8.W(cursor)→notifyDataSetChanged()` | classes4.dex |
| 游标 adapter | `com.tencent.mm.ui.w8.q / W / p / k` | `q():Cursor`、`W(Cursor)V`、`p/k` 关游标+重查 | classes4.dex，`{'Lcom/tencent/mm/ui/w8;':1}` |
| 存储通知入口 | `com.tencent.mm.storage.s4.b`（继承 `k74/y0.b`） | `(int, k74/y0, Object)V` | classes4.dex code=0x6941e8 |
| 单例获取路径 | `vj0.h8.b().v()` | 返回 `Lcom/tencent/mm/storage/o8;` 实为 `s4` | 反汇编 p3.f / t0.N 均有此链 |

**A4. 公开 ContentObserver / MStorage「通知某表变了」入口** `[已证实（未见）+ 取证缺口]`

- 全 15 dex 字符串扫描 `ContentObserver` / `notifyChange`：只见 wcdb 内部游标跟踪类（`AbstractCursor$SelfContentObserver`、`CursorToBulkCursorAdaptor$ContentObserverProxy`、`IContentObserver`）与 `SettingsContentObserver`（设置项监听），**没有任何面向业务表（rconversation）的公开通知 API**。
- `k74/q0.doNotify()` 是宿主内部通知器，仍需反射拿到实例；不属于「公开 API」。
- 结论：**不存在「不需 hook 私有方法、只发广播/用公开 API」就能让微信重查会话表的路**。任何方案都要反射宿主混淆方法。

**A5. 不 hook 私有方法的可行解** `[已证实]`

- 无。次优的「纯公开 API」路径只有 **冷启**（用户主动杀进程重进）——这正是 R54 §10.2 已接受的行为。若要求不冷启，必须反射调用 A2/A3 的混淆方法。

### B. 可行性 + 落点

**B4. 从 A 找到的入口，写库后调用能否让 `p3.getCount()` +1 并原生渲染** `[推断] + [需真机]`

- 正向证据链（全部 dex 原文，已证实各环节存在）：
  1. `p3.f()`（classes4.dex）执行 `select ... from rconversation where ... order by flag desc`，我方 `wxgrp_` 行若已按 R54 §10.3 模板写库，会被这条 SQL 选中（条件仅 `s4.P()` 的过滤串，未见按 username 白名单排除）。
  2. `s4.b(2/3/5, s4, username)` → `k74/t0.d(Object)+c()` → 事件分发。
  3. `p3.e(int,k74/y0,Object)` 接收事件 → `i3.r/s(Object,int,a94/a,Z)` → 维护 events HashMap → `i3.u(Z,Z)`（classes10.dex code=0x40a8b0）在 `newcursor reset` 分支调 `i3.f()` 重新查询 → `i3.h(cursor)` → `i3.q(newCursor,Z,Z)` 换游标 → `notifyDataSetChanged`。
  4. 换游标后 `p3.getView()` 用宿主原生 `ConversationFolderItemView` 渲染（R54 §10.1 #8 真机已证：冷启后同样库行 pitch 259px、零视觉差异）。
- **风险门（关键，dex 原文）**：`i3.s()`（classes10.dex code≈0x…, insns=374）在 0x0034-0x0048 调用 `a94.e.P(username)`，若返回 false 则打 `"newcursor event pass"` 并 `return`（不刷新）。`a94.e` 是接口（classes10.dex，`{'La94/e;':1}`），实现类 `La94/g;/La94/k;/Lz84/c;` 的 `P(Object)` 多为委托/恒 false。**我方 `wxgrp_<hex>` 不在宿主游标里 ⇒ `P()` 很可能返回 false ⇒ 事件被 pass 掉**。这是「宿主按未知 username 过滤行」的具体落点，`[需真机]` 证实。
  - 绕门候选：`s4.b(5, s4, "")`（全清事件，payload 空串）—— `p3.e` 对 `username==""` 有专门分支（0x0050-0x0063：置 `p3.N=true` 并 `i3.r(...)`），可能触发整体重置而非逐 username 过滤；宿主 `s4.l()` 自己就是这么发的 `[已证实]`。是否真能重置重查 `[需真机]`。
- **另一条更稳的重查入口**：直接反射调 `com.tencent.mm.ui.conversation.t0.N()`（folded-box adapter 的 `s4.t()` 重查 + `w8.W` + notify）——但它作用于 `conversationboxservice` 的 adapter，**未必是主会话列表的 p3**（`p3` 父类是 `i3`，`t0` 父类是 `d2→w8`，两条平行链）。`[取证缺口]`：主列表 `p3` 的对应「强制全量重查」公开方法名未找到，只有 `p3.f()`（被 `i3.getCount()` 在 `f==null` 时调用）。
- 结论：**机制链路存在，但「新行是否出现」取决于事件是否过 `a94.e.P` 门**，目前 `[推断]`，必须真机验。

**B4′. 标题从哪来 —— 原生行 ≠ 像素级一致（team-lead 追加的关键问题）** `[已证实] dex + [需真机]`

> 归属纠正（team-lead / R53 §8.4）：**微X 的 12 条 `wx__c_` 行 rcontact 所有名字列全空**，标题是**渲染期 hook 注入**；**写 `rcontact.nickname` 是我方 R54 的做法**，这正是 R54 §10.1 #5「改名要冷启」的根因。

- **宿主重查会话列表不带刷新联系人缓存**：会话存储 `com.tencent.mm.storage.s4`（`k74/i0` 连接）与联系人存储 `com.tencent.mm.storage.q4`（tag `"MicroMsg.ContactStorage"`，classes4.dex code=0x49f858）是**两套独立 storage、两套事件通知**。`s4.b(2/3/5,...)` 只发会话变更事件，不会让 `q4` 重建缓存。
- **但标题不一定依赖「联系人缓存失效」——dex 原文给出两条路**：
  1. `p3.getView()`（0x0389、0x069f）调 `p3.F(String):h4`（0x57e774），`F()` 再调 `q4.n(username, true)`（ContactStorage.get）。
  2. `q4.n()` 反汇编（0x0024-0x0029）：先查**内存联系人缓存** `q4.i : com.tencent.mm.storage.p4` 的 `get(username)`；**缓存命中就直接返回**（0x002b-0x0041，无 DB 查询）。
  3. **缓存未命中**才走 0x0042-0x0092：拼 SQL `select username, alias, conRemark, ..., nickname, ... from rcontact where username=... or encryptUsername=...`，经 `q4.d.g(sql,null,2)` **回查 rcontact 库**，`convertFrom(cursor)` 后 `q4.i(h4)` 写回缓存。
- **对我方 wxgrp_ 的推论**：
  - **新建分组（缓存 p4 里没有该 username）**：即便宿主只重查会话、不清联系人缓存，`getView→F→q4.n` 也会因**缓存未命中而回查 rcontact**，读到我方写入的 `nickname` ⇒ **标题可自动正确，无需撬联系人缓存**。这是「原生行 + 正确标题」在新建场景可能成立的关键。
  - **改名（缓存 p4 里已有旧 nickname）**：缓存命中直接返回旧值 ⇒ **标题仍是旧的**，必须等冷启重建缓存或额外触发联系人缓存失效（R54 §10.2 明确不走的高风险路）。`p3.e()` 里确有独立的 `"unreadcheck onContactStorageNotifyChange event type %d, username %s, isIniting %b"` 分支（0x008c），证明宿主自己改联系人时**靠另一条通知**刷新，而我方 `execSQL` 直写 rcontact 不产生该通知。
- **与微X的差异**：微X rcontact 全空名 + 渲染期 hook 注入标题，**从根上不依赖 q4 缓存**；我方走原生行则依赖 `q4.n` 的缓存命中行为。⇒ 「像素级一致」路线在**新建场景**可能不需要撬联系人缓存，在**改名场景**仍需要——这是判断该路线是否真正可行的关键边界，必须真机验证 B4′ 的两条推论。
- **判据**：真机新建组后（不冷启）若新行标题正确 ⇒ 支持「缓存未命中回查 DB」；随后重命名该组（不冷启）若标题仍旧 ⇒ 坐实「改名仍依赖缓存失效」。

**B5. 落点方案（供主会话审，本轮未改）**

| 文件 | 改动 | 说明 |
|---|---|---|
| `probe/HostCacheRefreshProbe.kt`（**新增，纯日志**） | hook 下表 6 个点，after/before 只打日志/调用栈，`param.result` 一律不动 | 只读探针，主会话真机验完再决定要不要真调 |
| `core/HostApi.kt`（新增，仅探针用） | 反射取 `s4` 单例（`vj0.h8.b().v()`）+ 反射壳 `s4.b(int,s4,Object)`、`p3.f()` | **探针里只打印「如果调用会传什么参数」，不实际调用** |
| `EntryReceiver.kt` | 加一个 `op=hcr` 广播分发（开/关/flush 探针） | 与现有 `op=gconv` / `op=gcs` 并列，不改行为 |
| `feature/ConvFilter.kt` / `data/GroupConvStore.kt` | **本轮不动**；真机确认可行后才在 `GroupConvStore.sync()` 写库事务成功后追加一次 `s4.b(2/5,...)` 通知 | 保持写库与通知原子分离，回滚简单 |

**B6. 混淆名随版本变的风险缓解** `[设计裁决]`

- ① **VersionGate 白名单**：`VersionGate` 现已是版本白名单机制（见 `docs/开发回路速查.md` / `core/VersionGate.kt`），把 `s4 / k74/y0.b / p3.f / t0.N / w8.W` 这套混淆名绑到 2588；版本不匹配 ⇒ 能力不可用（只打 WARN，不崩）。
- ② **DexKit 字符串反查（能力现成，非从零接入）** `[已证实]`：
  - `probe/DexProbe.kt:6-7` 已 import `DexKitBridge` / `FindClass` / `ClassMatcher`；`:42` `bridge.use { dex -> dex.findClass(...) }` 已在用（当前找 RecyclerView.Adapter 子类 `:11` 与含 `"wxid_"` 的 Contact 类 `:58`）；`:72-96` 从模块 APK 路径手动 `System.load("$moduleApk!/lib/$abi/libdexkit.so")` 加载 so（注释 `:16` 说明宿主进程拿不到模块包）。依赖 `org.luckypray:dexkit:2.2.0`（`app/build.gradle.kts:69`）。**空骨架的只是 `core/DexKitLocator.kt:40`**，与本项无关。
  - 只需把查找目标从「Adapter 子类」换成「SQL 字符串所在方法」：`FindMethod.create().matcher(MethodMatcher.create().usingStrings = listOf("select unReadCount, status, isSend, conversationTime, username, content, msgType, flag, digest"))` 定位 `s4.B`/`p3.f`；`usingStrings=listOf("unreadcheck onConversationStorageNotifyChange")` 定位 `p3.e`；`usingStrings=listOf("MicroMsg.ConversationWithCacheAdapter")` 定位主 adapter。`FindClass` 已验证能跑，`findMethod + StringMatcher` 同属 DexKitBridge 2.2.0 API（**具体 matcher 类名/参数需主会话按 2.2.0 实际 API 核对**，本轮只做只读取证、未跑构建）。
  - 目标 dex：`_cache/mm.apk`（379MB，就位）或 `DexKitBridge.create(lpparam.classLoader, true)` 覆盖所有已加载 dex（含 split）。
- ③ **多候选名软匹配**：hook 安装逐个 `runCatching`，失败只记「该锚点不可用」，不整体禁用模块。
- ④ 本轮 dex 结论基于 2588；跨版本升级后必须重跑本探针反查（R54 §9 风险 10 同款纪律）。

### C. 判据与决策

**C7. 真机判据（正反对照，主会话可执行）** `[需真机]`

- 前置：gconv=1 且已冷启（组名/行已落库但列表无新行，R54 §10.1 #1 基线）；开 `op=hcr` 只读探针。
- 正向：探针 hook `p3.f()` after 应打出 SQL + `cursor.getCount()`；**新建分组 → 写库（gcs sync force）→ 触发通知 → 不冷启看列表**。判据三条同时满足才算成立：
  1. `s4.b` 探针有日志（event code + username）；
  2. `p3.f()` 被调到且 `getCount()` 比建组前 +1、`cursor` 里能查到 `wxgrp_<hex>`；
  3. 用户肉眼看到原生分组行（pitch 259px，`op=xh sub=row` 显示宿主 View 类），点击进 `GroupDetailPage`。
- 反向 1（过滤门）：若 `i3.s` 探针打 `"newcursor event pass"` 且 `p3.f()` 未被调 ⇒ 坐实「未知 username 被过滤」，本路不通。
- 反向 2（假 notify）：若 `p3.notifyDataSetChanged` 有日志但 `getCount()` 不变 ⇒ 只通知未重查（R54 §3.2 的假修法），本路不通。
- 变异点：把通知 payload 从 `wxgrp_<hex>` 换成 `""`（event=5）再跑，看是否能绕过 P() 门触发 `p3.f()` 全量重查。
- 冷启对照：触发失败时冷启一次应出现（证明库写对了，只是通知没生效）。

**C8. 风险分级 + 灰度建议** `[设计裁决]`

| 方案 | 风险 | 建议 |
|---|---|---|
| 本轮主推（先只读探针） | **低**（只 hook 打日志，不改 param.result、不写库） | 可直接上，`op=hcr` 默认 **false** |
| 真调 `s4.b(2/5,...)` 触发重查 | **高**：反射宿主混淆私有方法 + 事件总线副作用（可能触发其它 observer / 置顶重排 / 空聊天页）；混淆名随版本变 | **必须新增独立开关 `chat.host_cache_refresh`，默认 false**，真机 C7 全过后由主会话 flip；不沿用 R61「默认开」先例 |
| 真调 `t0.N()` | 中高：作用于 folded-box adapter，可能刷错页 | 仅在 `p3.f()` 路证伪后试，且同样灰度 |
| R64 包装链恢复注入（自绘行） | 中高（动 K7 位置映射/锚点） | **用户已否决自绘行，本轮不做** |

- 保守建议：**探针与真调分两个版本**。先发「只打日志」版给用户装，真机按 C7 取证据；证据齐全再由主会话拍板是否发「真调」版。绝不把反射调用塞进 `sync()` 默认路径。

**C9. 若 dex 取证证明不可行 → 明确说 + 次优** `[设计裁决]`

- 本轮 dex 已证「入口拿得到、可调用」，**未证伪**。但「未知 username 被过滤」有明确代码门（`i3.s` 的 `a94.e.P`），是真实失败可能。
- 若 C7 反向 1 坐实过滤门且 `b(5,"")` 变异也无效 ⇒ **明确判定「让宿主自己重查这条路不可行」**，停止追加 hook。
- 用户坚持「像素级一致（原生行）」前提下的次优（按推荐度）：
  1. **走宿主 insert API `s4.I(r4)` 代替 execSQL**：dex 已证 `s4.I` 写完库自己发 `b(2, s4, username)`（真·宿主原生通知，无需额外 hook 通知点）。代价：需反射构造 `r4` 全字段（`com/tencent/mm/storage/r4`），工程量大；且仍可能被 `a94.e.P` 门过滤，需真机验。这是「最接近原生、最像微X」的路。
  2. **手动刷新入口 + 冷启兜底**：设置页/广播加「刷新分组行」，触发一次通知 + 提示「若未出现请冷启」。低风险、可立刻交付，但非自动即时。
  3. **接受冷启**（现状，R54 §10.2）：零风险，用户已明确不满意，仅在 1/2 都失败时作为最终兜底。
- **不硬凑**：若 1 也失败，不要退回自绘行（用户已否决）；如实告知「在不自绘行的前提下，本版本做不到不冷启即时显示」，把决定权交回用户。

---

## 3. 只读探针设计（主会话可落地，不改行为）

> **路线优先级（team-lead 更正 + 用户最新信息「先别定，看取证结果」）**：
> - **路线甲·首要（运行时侧观察，R64 §4b）**：微X 已确认真机装着且启用（`com.fkzhang.wechatxposed`）。**不碰微X加密 dex、不装包、不冷启**，直接 hook 微信自己的 API，看微X建组那一刻宿主被怎么操作，**一次性定案微X走 b（撬宿主通知）还是 c（改数据源注入）**。主会话持设备可执行，比我方从零反 dex 更快更实。
> - **路线乙·储备（本 dex 入口取证）**：本文件 A/B 节的 `s4.b` / `p3.f` / `t0.N` 混淆名，作为**路线甲发现候选 b 后的实现落点**。两条路线并列，最终取舍交用户拍板。

### 3.0 路线甲·首要：R64 §4b 运行时侧观察探针（复用现成设施，非从零）

> 全部复用我方现成 hook 设施与已解包微信 dex；**一次只开一个探针**，`dev.py q <tag> N` 拉日志后立刻 `sub=off`；微X 与我方分时段测（关 `chat.gconv_enabled` 采微X日志）。

| # | hook 落点（现成设施） | 观测什么 | 结论映射 |
|---|---|---|---|
| 甲-P1 | `BaseAdapter.notifyDataSetChanged` after（`AutoDndGroup.kt:305-368` 现成，按 `:382-414 isConversationAdapter` 过滤） | **微X新建分组那一刻，会话 adapter 被 notify 几次**（带时间戳） | 有 notify ⇒ 候选 b/d（走宿主通知/插入）；0 条但行仍即时出现 ⇒ 候选 c（直接改数据源） |
| 甲-P2 | `com.tencent.mm.ui.w8.W(Cursor)` before（`GconvPage.kt:833-864` 现成） | 微X建组时 `W` 入参游标行数是否 +1、是否被换成含 `wx__c_` 的游标 | 换游标 ⇒ 微X 在游标层注入（候选 c 的具体落点） |
| 甲-P3 | `com.tencent.mm.ui.conversation.p3.getCount()/getItem(int)` after | 建组前后 `getCount()` 是否 +1、`getItem(i)` 是否冒出 `wx__c_` 对象 | 是 ⇒ 候选 c 铁证，给精确 hook 点 |
| 甲-P4 | `com.tencent.mm.ui.conversation.t0.N` before（`GconvPage.kt:793-812` 现成） | 微X建组是否触发原生查询入口 `N()` | 触发 ⇒ 走宿主查库（b/d）；不触发但行出现 ⇒ c |
| 甲-P5 | `SQLiteDatabase.execSQL`/`insertWithOnConflict` before（`AntiRevoke.kt:377-382` 现成），按 `rconversation` 过滤 | 微X建组**是否真写新行**、SQL 原文 | 行数 +1 ⇒ 排除候选 e（复用常驻行） |
| 甲-P6 | `op=xh sub=row`（`HookListProbe` 现成） | 新行 `viewType`/`tag`/`item` 类型 | 定「宿主原生行」vs「注入假对象」 |

> 这套探针同时回答 team-lead 的归属矛盾：若甲-P3 命中 `wx__c_` 对象且甲-P6 显示宿主 View，则微X标题必来自渲染 hook（与 R53 §8.4 全空名一致）；若甲-P1 有 notify 且 `p3.e` 有日志，则候选 b 成立、本文件 B 节落点直接可用。

### 3.1 路线乙·储备：宿主重查入口只读探针（新文件，不改行为）

新文件 `probe/HostCacheRefreshProbe.kt`，开关 `chat.host_cache_probe`（默认 false，`Prefs.ALL` 加一行即可），广播 `op=hcr sub=on|off|flush`。**所有 hook 的 `before/after` 只打日志 + `Thread.currentThread().stackTrace` 过滤 LSPosed 桥接帧（从第 4 帧起），绝不修改 `param.args` / `param.result`、不主动调用任何宿主方法。**

| # | hook 落点 | 时机 | 打印内容（判据） |
|---|---|---|---|
| P1 | `com.tencent.mm.storage.s4.b(int, k74/y0, Object)`（用 `XposedHelpers.findAndHookMethod("k74.y0", cl, "b", int, "k74.y0", Object)` 覆盖子类） | before | `HCR s4.b event=<code> user=<payload>` + 8 层栈；判「宿主自己有没有通知」 |
| P2 | `com.tencent.mm.ui.conversation.p3.e(int, k74/y0, Object)` | before | `HCR p3.e event=<code> user=<payload>`；判「通知有没有到主 adapter」 |
| P3 | `com.tencent.mm.ui.conversation.p3.f()` | after | `HCR p3.f sqlLen=<n> cursor=<count>` + `cursor` 里 `wxgrp_` 行数（只读遍历 `getString`）；**核心证据**：重查是否发生、是否含我方行 |
| P4 | `com.tencent.mm.ui.i3.s(Object,int,a94/a,boolean)` | before | `HCR i3.s event=<code> has=<a94.e.P(user)>`；若出现 `"event pass"` 语义 ⇒ 过滤门坐实 |
| P5 | `com.tencent.mm.ui.conversation.p3.getCount()` / `getItem(int)`（after） | after | `HCR count=<n> item[0..3]=<username>`；判 `getCount()` 是否 +1 |
| P6 | `android.widget.BaseAdapter.notifyDataSetChanged`（after，按 `isConversationAdapter` 过滤，复用 `AutoDndGroup.kt:305-368` 现成设施） | after | `HCR notify <adapterClass>`；判「只 notify 未重查」的假修法 |
| P7 | `com.tencent.mm.storage.q4.n(String, boolean)`（after）+ `p3.F(String)`（after） | after | `HCR q4.n user=<u> cacheHit=<bool> nick=<value>`（只读 `h4` 的 nickname 字段）；**判 B4′ 标题来源**：新建组是否因缓存未命中回查 rcontact、改名是否命中旧缓存 |

- 日志统一 tag `HCR`，`dev.py q HCR N` 拉取；`sub=off` 立即摘 hook（P5/P6 在热路径，长时间挂会拖慢微信）。
- P1/P2 的栈要能看出「谁发起通知」——这是判定微X是否走宿主通知链的关键（R64 §4b 的运行时侧观察手法）。
- 探针**不提供** `sub=notify` 之类的真调入口；真调必须是独立开关下的独立代码路径（C8）。

---

## 4. 真机验证步骤（正反对照）

> **用户最新信息**：微X 真机装着且启用（`com.fkzhang.wechatxposed`），可做对比探针；用户对「像素级一致 vs 自绘行保底」暂不取舍（「先别定，看取证结果」）。因此**阶段甲（微X 对比）为首要**，阶段乙（我方 dex 入口）为储备。

### 阶段甲·首要：微X 运行时侧观察（R64 §4b，不碰加密 dex、不装包、不冷启）

前置：关我方 `chat.gconv_enabled`（避免我方 notify 混入 P1 日志）；微X 启用；`A`/`P`/`W` 沿用 `docs/_r54_gconv_design.md:145-149`。

1. **基线**：`op=xh sub=row` + 微X分组页截图，记现有 `wx__c_` 行数与标题。
2. **一次只开一个探针**：甲-P1 → 甲-P2 → 甲-P3 → 甲-P4 → 甲-P5 → 甲-P6，各开各关，微X新建测试组，`dev.py q <tag> N` 拉日志后立刻 `sub=off`。
3. **定案表**：notify 次数 / `w8.W` 入参游标是否 +1 / `p3.getCount()` 是否 +1 / `t0.N` 是否触发 / `execSQL` 是否真写新行 / 新行 View 类型 —— 一次性把 A1 候选 b/c/d/e 定死（当前全是 `[推断]`）。
4. **若是候选 b**（微X走宿主通知）⇒ 本文件 B 节 `s4.b` / `p3.f` 落点直接可用，进阶段乙验证我方复用。
5. **若是候选 c**（微X改数据源注入）⇒ 宿主重查路线对「即时」无帮助；如实告知用户并转 C9 次优（不自绘行前提下）。

### 阶段乙·储备：我方宿主重查入口探针（dex 落点验证）

前置：gconv=1 + 冷启（组内行已写库但列表无新行）；`kvset chat.host_cache_probe 1`；`A`/`P`/`W` 变量沿用 `docs/_r54_gconv_design.md:145-149`。

1. **基线**：`op=hcr sub=on` → `op=xh sub=row` + `gcs stat`（记 `own=N`、列表顶部无新组行）；`dev.py q HCR` 应无 `p3.f` 日志（无重查）。
2. **写库**：UI 新建测试组「R65T」→ 1~2s 内 `op=gcs sub=sync`（force）→ `stat own=N+1`、`wxgrp_<hex>` + `nickname=R65T` 出现。
3. **只读观察（关键，不触发通知）**：不冷启看列表 → 应仍无新行（R54 已知行为）；`dev.py q HCR` 看 P1/P2 是否有日志（预期 0，因为直写 execSQL 不经过宿主通知）⇒ 坐实 R63 A2。
4. **正向（触发宿主通知）**：由主会话在**独立真调开关**下触发一次 `s4.b(2, s4, "wxgrp_<hex>")`（或变异 `b(5, s4, "")`），**不冷启**看列表。同时看 P3：`p3.f` 是否被打到、`cursor` 是否含我方行、`getCount()` 是否 +1。
5. **过滤门判定**：若 P4 打 `"newcursor event pass"` 且 P3 无日志 ⇒ 过滤门坐实，本路不通，跳到 C9 次优。
6. **原生渲染 + 标题确认（B4′）**：若出现新行，`op=xh sub=row` 看 View 类是否为宿主 `ConversationFolderItemView`、pitch 259px；真手点是否进 `GroupDetailPage`（不进空白聊天页）。**标题两条判据**：① 新建组（不冷启）标题正确 ⇒ 支持 `q4.n` 缓存未命中回查 rcontact（P7 应显示 `cacheHit=false`）；② 随后**重命名该组（不冷启）**标题若仍旧 ⇒ 坐实「改名仍依赖联系人缓存失效」（P7 应显示 `cacheHit=true`），此时「像素级一致」在改名场景不成立，需交用户拍板是否接受。
7. **反向红线**：`kvset chat.gconv_enabled 0` + 冷启 → 建组 → `stat own` 不变、无 wxgrp_ 行、无 HCR 通知日志（探针在 gconv=0 下应静默）。
8. **收尾**：`op=hcr sub=off`；若写脏 `op=gcs sub=clean` 幂等清场。

---

## 5. 风险、灰度建议、兜底

- **最高风险**：反射调用宿主私有混淆方法 `s4.b` / `p3.f` / `t0.N` —— 事件总线副作用不可预测（可能触发置顶重排、清空游标、打开空聊天页），且混淆名随版本失效。缓解：探针先行 + 独立开关默认 false + VersionGate 白名单 + 每步真机判据。
- **数据风险**：本轮探针**不写库**；真调通知本身不改库，但宿主收到 insert 事件后可能自行重排/清理 `rconversation`。真调版必须在事务成功后调用，且保留 `op=gcs sub=clean` 兜底。
- **性能**：P4/P5/P6 在热路径（每行 getItem、每次 notify），探针只打日志也可能拖慢；`sub=off` 必须及时，验证完就摘。
- **灰度**：`chat.host_cache_probe`（探针）与 `chat.host_cache_refresh`（真调）**两个独立开关，均默认 false**；先发探针版，真机闭环后再发真调版。
- **用户取舍未定**：用户明确「像素级一致 vs 自绘行保底先别定，看取证结果」⇒ 本文件**不替用户拍板**。阶段甲（微X 对比）+ 阶段乙（重查入口）+ B4′ 标题判据的取证结果，是用户下一步取舍的输入。无论哪条路，**本轮只交付只读探针与取证结论，不交付会改变用户可见行为的真调代码**。
- **回滚**：关两个开关 → 回到 R54 passthrough（冷启才显示，功能降级但零风险）；极端 `git revert` 本轮新增文件，不动表结构/配置指纹。
- **兜底次序**：宿主重查不通 ⇒ `s4.I(r4)` 原生 insert API（C9-1）⇒ 手动刷新入口（C9-2）⇒ 接受冷启（C9-3）。**不自绘行**（用户已否决），**不硬凑**。

---

## 6. 本轮实际取证材料（可复核）

- 权威源：`CLAUDE.md` §四（:81-215）/§五（:252-274）；`docs/conclusions/conv_list.md`（全文）；`docs/开发回路速查.md`。
- 我方定案：`docs/_r54_gconv_design.md` §3（:117-159）/§10（:455-515）；`docs/_r63_new_group_not_shown.md`（全文 259 行）；`docs/_r64_microx_instant_group.md`（全文 285 行）；`docs/_r55c_longpress_menu.md`。
- 宿主 dex：`_cache/dex/classes4.dex`（`s4`/`p3`/`t0`/`w8`/`d2`/`i3` 部分）、`classes5.dex`（`k74/q0`）、`classes10.dex`（`i3`/`a94.e` 及实现），全部只读。
- 现成反汇编：`_cache/_r55c_d4.txt`（classes4.dex 的 dexdump）；脚本 `_cache/_r55_disasm.py` / `_cache/_r55_strref.py`（复用其 `Dex` 解析器，只读）。
- 真机栈来源（非本轮产生，引用既有落盘）：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/GconvPage.kt:820-865`。

---

[DONE] R65 只读取证完成（含 team-lead 三条更正）。入口存在且混淆名已取到（`s4.b(int,s4,Object)` + `p3.f()` + `t0.N()`），但「调用后不冷启即出现」受 `a94.e.P(username)` 过滤门约束，标 `[推断]+[需真机]`；标题来源经 dex 证实走 `p3.F→q4.n`，新建组可缓存未命中回查 rcontact、改名仍吃旧缓存。DexKit 已接入无需从零。方案改为两条并列路线（甲：微X运行时观察·首要；乙：dex 入口落点·储备），结论为「有条件可行，先取证后真调，两开关默认关，不替用户拍板」。

---

## 真机取证记录（R66 统一只读探针 · 2026-11-18）

探针：`probe/HostCacheRefreshProbe.kt`，`op=hcr sub=on/off`，13/13 hook 全挂上
（H1 s4.b / H2 p3.e / H3 p3.f / H4 i3.s / H4b a94.g+k+z84.c / H5 p3.count+item /
H6 notify / H7 w8.W / H8 sql / P7 q4.n）。只读纪律两道 grep clean。

### 甲-1：我方建组「测试990」（id=157=0x9d）——数据层通、UI 不显示

- 数据层：`op=groups` → id=157/name=测试990/members=0；`gcs stat` → `conv own=1`（`wxgrp_...009d`）、
  `contact own=2`（含 `wxgrp_...009d | 测试990`）。⇒ 伪会话行**确已写库**。
- UI：截图确认会话列表**无「测试990」行**。
- HCR 日志（建组全程）：
  - `s4.b` 事件里**无一条 `wxgrp_`**——全是真实会话 `43809452760@chatroom` 的 event=3、
    及内部计数 event=4 user=8195/8196；
  - `p3.f()` **零调用**（重查未发生）；`getCount` 恒定 **1576 未 +1**；
  - P7 `q4.n user=wxgrp_` **零输出**（行未进内存，标题未被读取）；
  - `item[0..3]` 全是 `wx__c_...`（微X伪会话行，在置顶区）。
- **结论 [已证实·真机]**：建组走 `execSQL` 直写库 → **未发宿主通知**（无 `s4.b(2,...,wxgrp_)`）
  → 宿主不知库变 → 不重查 → 内存列表无新行 → UI 不显示。冷启重查库才认。

### ⚠️ 推翻 R65 原风险判断（关键修正）

- R65 原判：「通知发了，但被 `a94.e.P(username)` 过滤门 pass 掉」——**建组场景不成立**。
- 真机事实：过滤门**根本没走到**，因为压根没有 `wxgrp_` 的通知发出。
- 含义：`a94.e.P` 过滤门风险**从"当前阻力"降级为"若改走通知后才可能遇到的后续风险"**。
  症结在更上游——"通知未发"，不在"通知被拦"。
- 新线索：微X 的 `wx__c_` 行 `getItem` 能返回（在宿主内存列表），说明微X让宿主认了它的行；
  两家行在库里结构几乎相同（R53 §12.3），差别只在"谁触发了宿主重查"。

### 甲-2：微X建组（2026-11-18 21:49:18，决定性证据）

用户真手在微X建组，HCR 全程开启（13/13）。日志原文（`dev.py q HCR` 去噪后逐帧）：

```
21:49:18.014 main            HCR sql insert insert table=rconversation values=... username=wx__c_1a1eeeb4338 conversationTime=1
21:49:18.016 main            HCR s4.b event=3 user=wx__c_1a1eeeb4338
21:49:18.068 main            HCR p3.e event=3 user=wx__c_1a1eeeb4338
21:49:18.069 main            HCR i3.s event=2 user=wx__c_1a1eeeb4338 pass=?
21:49:18.070 main            HCR P user=wx__c_1a1eeeb4338 pass=false impl=a94.k
21:49:18.177 [D]#worker      HCR p3.f sqlLen=-1 cursor=1578 scan=1578 wxgrp=1 wx__=15
21:49:18.179 main            HCR count=1578
21:49:18.206 main            HCR item[0]=wx__c_1a1eeeb4338
21:49:18.276 main            HCR q4.n user=wx__c_1a1eeeb4338 nick= ret=h4
```

**A/B 对照（用户能看见的事实 + 真机日志，非内部计数器）**：

| 环节 | 我方建组「测试990」（甲-1） | 微X建组（甲-2） |
|---|---|---|
| 写库方式 | `execSQL` 直写 rconversation | `insertWithOnConflict` 写 rconversation（HCR sql 行） |
| 宿主通知 `s4.b` | **无一条 wxgrp_ 通知** | ✅ `event=3 user=wx__c_1a1eeeb4338`（写库后 2ms） |
| 通知到主 adapter `p3.e` | 无 | ✅ `event=3 user=wx__c_...` |
| 过滤门 `i3.s → P()` | 未走到 | ✅ 走到，`P(...)=false`，**未拦** |
| 重查 `p3.f()` | **零调用** | ✅ `cursor=1576→1578` |
| `getCount` | 恒 1576 | ✅ `1576→1578` |
| 新行 `item[0]` | 无 | ✅ `wx__c_1a1eeeb4338` |
| 标题 `q4.n` | 零输出 | ✅ `nick=`（空，微X靠渲染注入，符合 R53 §8.4） |
| UI | 不显示 | 立即显示（用户肉眼） |

**结论 `[已证实·真机]`**：
1. **微X走的是路线 b（撬宿主通知链）**：`insert 写库 → s4.b(3,s4,user) → p3.e → i3.s → P() → p3.f 重查 → cursor/count +1 → 新行 item[0]`。R65 §3.0 候选 b 定案，候选 c/d/e 排除。
2. **R65 担心的 `a94.e.P(username)` 过滤门不成立**：新行 username 是宿主陌生的 `wx__c_`，`P()=false`，但重查照样发生、新行照样出现。过滤门风险**证伪**（至少对"新 username 首次出现"场景）。
3. **我方症结 = 缺宿主通知这一环**：`execSQL` 直写绕过宿主 API ⇒ 宿主不知库变 ⇒ 不重查。与 R66 结论一致，现由微X对照组反证坐实。
4. **额外铁证（event=8 批量通知）**：21:49:23.402-448 宿主发一批 `s4.b event=8`，其中**含 `wxgrp_000000000000009d`**（我方行）→ 21:49:23.523 `p3.f cursor=1579` → 21:49:23.611 `q4.n user=wxgrp_...009d nick=测试990`。即**我方 wxgrp_ 行一旦被宿主重查就能原生显示、标题正确**（我们写了 `rcontact.nickname`，不靠渲染 hook、不撬联系人缓存）。⇒ 修复只差"触发一次宿主通知"，数据源与标题链路都是通的。

**修复方向（未改代码，待用户拍板）**：
- b1 最小改动：`GroupConvStore` 直写库成功后，反射补发 `s4.b(3, s4, "wxgrp_<hex>")`。高风险（反射私有混淆方法 + 事件总线副作用），须独立开关默认 false + VersionGate 白名单 + 正反对照。
- b2 更原生：改走宿主 insert API `s4.I(r4)`，宿主自发通知（C9-1）。工程量大（构造 r4 全字段）。
- 变异验证：event 码 2/3/5 对比；关开关反向对照须恢复"不显示"。

探针状态：`21:51:22 HCR PROBE OFF`，已摘（热路径纪律）。

### 待验（若进入真调）

- `a94.e.P(wxgrp_<hex>)` 的 P() 真值仍需真机验：甲-2 只证了 `wx__c_`，event=8 批次里 wxgrp_ 走了 `i3.s` 但未见 `HCR P user=wxgrp_` 行（P() 实现在批处理路径可能未被调）。按 b1 真调时补测。
