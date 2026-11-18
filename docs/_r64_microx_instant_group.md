# R64 微X「新建分组实时显示」机制取证 + 我方复用方案（Pro 级只读分析）

> 角色：r64-microx-instant-probe。本轮**未改任何代码、未跑 gradle、未碰 adb/设备**；主会话持设备锁与构建锁。
> 宿主：微信 8.0.48（versionCode 2588，本地 `_cache/mm.apk`）。模块：`wxgroup`。微X：`com.fkzhang.wechatxposed`（`_mods/wechatxposed.apk` / `_cache/wxposed.apk`，均 4,423,605B）。
> 判据纪律（CLAUDE.md §四/§五 + `docs/开发回路速查.md`）：只认「用户能看见的事实」+ 源码行号 + 真机日志时间戳 + dex 原文；
> 「A 推 B」时序结论一律标 **[推断]** / **[需真机]**，不冒充 [已证实]。
> 证据分档：`[已证实]` = dex/源码/设计定案直接可见 ｜ `[推断]` = 由已证实事实外推 ｜ `[需真机]` = 有锚点但无实测 ｜ `[取证缺口]` = 材料不存在/解不出。

---

## 0. 一句话机制（给赶时间的人）

**微X 不靠「写库后等宿主把新行读进内存」——它在数据层写 `wx__c_` 伪会话做持久化，同时在 adapter/渲染层用 hook 把分组行和标题「注入」进当前会话列表；标题注入是因为它 `rcontact` 全空名（R53 §8.4），行即时可见最可能也来自同一套 `getCount/getItem` 注入（R55 候选 B 已推定微X 用这套 hook 喂分组页）。** 我方 R54 走的是「纯写库 + 靠宿主重查」，宿主的会话内存缓存（`ConversationStorage`）和联系人缓存两层都不认新行 ⇒ 只有冷启重查库才显示；这是 R54 §10.2 **主动接受**的设计代价，不是回归。

---

## 1. 结论摘要

| 问题 | 结论 | 等级 |
|---|---|---|
| 微X 写库后靠什么让会话列表即时出现新行 | **不是**「写库→宿主自己 notify」（我方那条死路），而是**数据层持久化 + adapter/渲染层注入双轨**：数据层写 `wx__c_` 行（R53 §8 已证），渲染层 hook（标题注入已证；行即时可见最可能同源 `getCount/getItem`，[推断]） | [已证实]部分 + [推断]部分 |
| 微X 是否也冷启才显示（候选 a） | **无法静态排除**，但用户「微X 即时」的观察 + 微X 必然有渲染 hook 的架构，使「也冷启、用户没注意」成立概率低；无真机时间戳前不定案 | [推断]+[需真机] |
| 微X 的 dex 里具体 hook 点 | **静态取不到**（7 个熵 8.00 密文 + 剥符号 .so + hook 表隔离），但**可用运行时侧观察绕过**：hook 微信 API 看微X操作了什么对象（R55c 已证手法），微信 dex 全量已解包 ⇒ §4b 给出 6 个可执行探针 | [静态缺口]+[可绕过] |
| 我方能否复用 | **能，但不走微X的dex**：我方 `ConvFilter.GroupedAdapter` **本就是 adapter 包装链**（gconv=0 已证能即时插行），把它在 gconv=1 下**恢复注入**（不再纯 passthrough）+ 写库保留持久化 ⇒ 新组即时可见，冷启后由库行接管、天然去重 | [推断·中高] |
| 与 R54「不要撬联系人缓存」冲突 | **不冲突**：推荐方案用自绘 `GroupRowView` 从我方库取标题，**完全不碰联系人缓存、也不写 `rcontact.nickname` 依赖**；真正被部分回退的是 R54 §10.2「保留包装链只走 passthrough」这一条设计取舍（换来即时性，代价见 B5） | [推断] |
| 风险 / 开关 | **中高风险**（要动位置映射，R1~R34「点错人」历史全在这块）。建议**新增独立子开关 `chat.gconv_instant`，默认关**，真机验收通过后再 flip 默认；不要沿用 R61「直接默认开」的先例 | [设计裁决] |
| 一句话推荐 | **走「包装链恢复注入 + 自绘标题行 + 库行冷启去重」**，不追微X的dex（取不到）、不撬宿主缓存（高风险）；若连这也做不出，退而求其次给「手动刷新分组行」入口 | — |

---

## 2. A / B / C 逐条结论（证据 + 等级）

### A. 微X 实时显示到底怎么做到的

#### A1. 四个候选逐个证实/推翻

| 候选 | 判定 | 等级 | 证据 / 理由 |
|---|---|---|---|
| (a) 它也冷启才显示，只是用户没注意/rebuild 了列表 | **不能完全排除**，但概率低 | [推断]+[需真机] | 微X 必然有渲染期 hook（见 A1-c 的标题注入事实），用户明确观察到「实时显示」（派发审计台账 `:4752`），两者叠加使「纯冷启」解释变弱；但**没有任何真机时间戳**证明「新建→不冷启→N 秒内出现」，这是本轮最大取证空白。真机判据见 C8-变异2。 |
| (b) hook `ConversationStorage` 缓存刷新 / `doNotify` / 会话插入路径，让内存列表认新行 | **可能，但无直接证据** | [推断]+[取证缺口] | R54 §3.3/§3.4 提过「候选 A（让宿主重查）」正是这条路，但**从未证实微X走它**。LSPosed hook 表隔离（R53 §8.6、R55 Q2），`op=xh sub=hook` 读不到微X挂的 hook（R55 `:126-128`）；dex 加密 ⇒ 静态拿不到类名。优点：宿主缓存与 adapter 一起更新，顺序不会错；缺点：混淆名随版本变，风险高。 |
| (c) 不写 `rconversation`，用内存假会话对象注入 adapter（hook `p3.getCount/getItem`） | **部分成立 + 最可能解释「即时」** | [已证实]标题注入 + [推断]行注入 | ①[已证实] 微X **确写** `rconversation`（R53 §8.1 12 条 `wx__c_` 与界面秒级吻合、§8.2 flag 置顶排序、§8.3 模板）⇒「根本不写库」**被推翻**。②[已证实] 标题是渲染期注入：`rcontact` 那 12 行所有名字列全空（`conRemark/nickname/pyInitial/quanPin/alias/encryptUsername`），界面却显示「美妆/日化」⇒ 标题必由 hook 塞入（R53 §8.4）。③[推断] 既然微X **必须在渲染路径有 hook**（否则标题出不来），且 R55 Q1/Q2 已高置信推定它用 `getCount/getItem/setTitle` 注入分组页的「按标签筛选会话集合」（`_r55_microx_click_longclick.md:67-76`），那么**同一套注入机制极可能同时让主列表的新行即时出现**——这是「即时」最经济的解释。 |
| (d) 走微信自己的会话插入 API（宿主自己 notify） | **可能性低，无证据** | [推断]+[取证缺口] | 若走宿主 insert API，宿主会按「未知 username」过滤；微X 的 `rcontact` 行是全空名（`type=0`），不像真实插入路径会写 nickname。且宿主 insert 无法解释「标题渲染期注入」。无 dex/日志证据，记为低可能。 |
| (e) 其它：分组行是「常驻已存在行」，新建只是改标题/归属，不是新行 | **无法排除** | [需真机] | 微X 12 条 `wx__c_` 跨多次冷启常驻（R53 §8.5），若「新建分组」实际是复用已有伪会话行，则「即时」只是换标题（标题 hook 天然即时）。真机需在新建前后 `sub=sql` 对比 `wx__c_` 行数是否 +1（C8-变异3）。 |

**A1 小结（给主会话的判断）**：候选 (c) 是**唯一被已证实事实部分支撑、且能解释「即时」**的机制；其精确形态是——**数据层写行（持久化）+ 渲染/数据层 hook 注入（即时 + 标题）双轨**。我方现状只有「数据层写行」这一轨，且标题走 `rcontact.nickname`（依赖联系人缓存），所以两轨全靠宿主重查 ⇒ 冷启。

#### A2. 具体类.方法 + 混淆名 + dex 地址（可挂 hook 点清单）

**结论：微X 侧的精确 hook 点取不到 —— [取证缺口]。** 三项独立封锁：

1. **业务 dex 加密**：`_mods/x/assets/` 下 9 个 hash 文件，7 个密文（530707/353907/323411/276456/258499/48191/41471B），头 `92 c5 01 00`；逐字节分析零字节占比 0.41%、16B 块重复数 `dup=0` ⇒ 流/CTR/CBC 加密，key 在 `74fab57…`(arm32 276456B) / `dc7be70…`(arm64 461560B) 两个剥符号 ELF 的 .so 里（R53 §1:13-42）。`shell.txt`（40MB dexdump）只覆盖 `classes.dex` 外壳，含 `WxCoreLoader`/`InMemoryDexClassLoader` 等，无业务方法。
2. **hook 表隔离**：LSPosed v101 不给每个模块共享 `XposedBridge` 的可反射 hook 注册表（`fields=[]`），`op=xh sub=hook` 读不到任何微X hook（R53 §8.6、R55 `:126-128`）。
3. **配置库打不开**：`FKZ_WX_DATA` 0 张表、`FKZ_WX_ChatroomTag`(16KB) 加密（R53 §2.1/§8.4）⇒ 连「标签→会话」映射是表还是内存 Map 都不知道。

**可挂的、宿主侧 hook 点（从 R54/R55 已实证清单反推，非微X dex）**：

| 用途 | 类.方法（混淆名） | 来源/证据 | 我方是否已有 |
|---|---|---|---|
| 分组页数据注入 | `com.tencent.mm.ui.conversation.p3.getCount()` / `getItem(int)` | [推断] R55 `:67-76` 高置信推定微X用这套 | 否（我方包装链已覆盖外层，无需 hook 宿主） |
| 分组页标题 | `setTitle(CharSequence)` / `Activity.onResume` | [推断] R55 `:69` | 否 |
| 会话列表读取（我方现成） | `HeaderViewListAdapter.mAdapter` → `p3` → `getItem()` = `r4(field_username)` | [已证实] `conv_list.md:2`、R54 §4 | 是（`readSnaps`，`ConvFilter.kt:1599-1615`） |
| 宿主通知点（候选 b，未证实微X用） | `com.tencent.mm.ui.conversation.p3.notifyDataSetChanged()` + `ConversationStorage` 的 doNotify/查库方法（**混淆名未取证**） | [推断] R54 §3.3 `DexKitLocator` 按 `select...from rconversation` 字符串反查 | 否 |

> **给主会话的关键提醒**：不要因为「拿不到微X hook 点」就卡住 —— 我方**不需要复刻它的 dex**，因为**我方本来就有它想要的 adapter 包装链**（见 B4）。真正需要 dex 反查的只有「让宿主缓存自己失效」这条高风险路（候选 b），而那条路恰恰是 R54 §10.2 已经决定不走、且本方案能绕开的。

#### A2′. 绕开加固 dex 的正解：运行时侧观察（R55c 手法，主会话可执行）

**[方法论·已证实]** 补取证路线：拿不到微X 的类/方法，不代表拿不到它的**行为**。R55c（`:52-78`）已实证这条路径——hook 微信自己的 API，从日志里拿到「微X操作了哪个微信对象」，再拿这个对象去**微信 dex** 里验证类定义（各 1 次命中 = 类定义）。本机具备全部条件：

- **微信 dex 全量已解包**：`_cache/dex/classes.dex` ~ `classes15.dex`（微信 8.0.48，`AutoDndGroup.kt:299` 已确认路径）——不用反编译微X，只要知道类名就能离线验证定义（R55c 用这招验证了 `va4.j3`/`va4.m4` 是宿主类）。
- **我方已有 hook 会话 adapter 的现成设施**：`AutoDndGroup.kt:305-368` hook `android.widget.BaseAdapter.notifyDataSetChanged()`，`afterHookedMethod` 里 `param.thisObject` **就是活的会话 adapter**（dex 实证 `p3` 覆写 notify 但首句 `invoke-super`），`:382-414` `isConversationAdapter()` 已能稳定识别它，`:417-426` `enumerateGroups()` 已能遍历全部行取 username。
- **我方已知宿主刷新的唯一汇合点**（`GconvPage.kt:821-864` 真机栈实证）：`AdapterView$AdapterDataSetObserver.onChanged → w8.getCount → w8.q → W(宿主游标)`；`t0.N()` 是原生查询入口。⇒ 任何「让新行出现在会话列表」的机制，**必然**在这条链上留下可观测痕迹（要么 notify、要么换 `w8` 的游标/数据源、要么改 `p3.getCount/getItem`）。

⇒ **完整探针方案见 §4b**。它能在不碰微X dex 的前提下，把 A1 的候选 b/c/d 和 (e) 一锤定音。

#### A3. `wx__c_` 行 vs 我方 `wxgrp_` 行：微信如何看待（type / parentRef / 配对消息）

| 维度 | 微X `wx__c_` | 我方 `wxgrp_` | 差异是否导致微X即时/我方冷启 |
|---|---|---|---|
| `rconversation` 模板 | `flag=2^62+conversationTime` / `msgCount=0` / `chatmode=1` / `status=3` / `isSend=1` / `msgType=1` / `content,digest` 空（R53 §8.3） | **逐列相同**（R53 §12.3 字段对照表：28 列逐列一致，仅 `parentRef` 不同） | **否**：行本身宿主渲染完全一样（同为 `ConversationFolderItemView`，pitch 259px，R53 §10.1） |
| `rcontact.type` | `type=0`（R53 §8.3） | `type=0`（R54 §10.1 #3「type=0 已足够排除通讯录泄露」） | **否**：两者都靠 type=0 不进通讯录 |
| `rcontact` 名字列 | **全空**（R53 §8.4：12 行 `conRemark/nickname/pyInitial/quanPin/alias/encryptUsername` 全空） | `nickname=组名`（R54 §10.1 #2） | **是（核心差异）**：微X **不写 nickname**、标题走渲染 hook ⇒ 无联系人缓存依赖；我方写 nickname、标题走 `rcontact.nickname` ⇒ 依赖联系人内存缓存，改名也要冷启（R54 §10.1 #5）。⚠️ 注意方向：**写 nickname 是我方的做法，不是微X的** |
| `parentRef` | 快照时空串（R53 §12.3） | 插入空 → 9 分钟后宿主自填 `message_fold`（R54 §10.1 意外发现、R53 §12.3） | **否**：R53 §12.2 反向对照已证「点击行为差异与数据列无关」；`parentRef` 长期影响仍未验证（R54 §10 清单） |
| 是否需要配对消息/联系人 | 有 `rcontact` 同名空行供宿主拿到联系人对象（R53 §8.3） | 有 `rcontact` 同名行 + nickname | **否**：两者都满足「会话有配对联系人」条件；但微X 全空名行是否会被宿主冷启清理，R53 §8.5 未见证据 |

**A3 结论 [已证实]**：两行在「微信如何看待」上**几乎无差异**（宿主渲染、type、parentRef、配对联系人全同），唯一实质差异是**标题来源**——微X **不写 `rcontact.nickname`**、渲染期注入（无缓存依赖），我方写 `rcontact.nickname`（有联系人缓存依赖）。⇒ **「微X即时」不是因为它的行更「合法」，而是因为它把「即时性」放在 hook 层、不交给宿主缓存。** 这一条同时说明：我方若要即时，**标题也必须摆脱 `rcontact.nickname`**（主推方案的自绘标题读我方库正是对齐这一点）。

---

### B. 我方能否复用（可行性 + 风险）

#### B4. 能否落地 + 最小改动路径

**能。[推断·中高] 核心洞察：我方的 `ConvFilter.GroupedAdapter` 本来就是 adapter 包装链，gconv=0 下已经在「即时插自绘分组行」并已验收——「adapter 注入即时可见」这条路我方早就走通，只是 gconv=1 时被 R54 §10.2 改成纯 passthrough 关掉了。**

最小改动路径（**供主会话审，本轮未改**）：

| # | 文件:位置 | 改动 | 说明 |
|---|---|---|---|
| 1 | `feature/ConvFilter.kt` `rebuild()` `:1332-1347`（gconv 直通分支） | gconv=1 时**不再恒直通**：构造 layout 时注入我方分组行置于顶部（对齐微X置顶块）；位置映射沿用 `ConvLayout`（`data/ConvLayout.kt:202-250`） | 这是「即时显示」的开关点；必须在 `if(gconvEnabled)` 块内 |
| 2 | `feature/ConvFilter.kt` `readSnaps()` `:1599-1615` | 读宿主 `getItem()` 时**记录已存在的 `wxgrp_<gid>` gid 集合**（去重依据）；注入时跳过这些 gid | 冷启后宿主内存已有库行 → 不再重复注入 ⇒ 恒 N 行不翻倍。K7 已逐条 getItem，顺手收集零额外成本 |
| 3 | `feature/ConvFilter.kt` `getView()` `:1746-1853` + `getItemViewType()` | 注入位返回我方 `GroupRowView`（自绘标题，**从 `db.listGroups()` 取，不读 `rcontact.nickname`**），窄口径 `GroupConvPlan.groupIdOf` 认领；点击/长按沿用 `:1842`/`:1824` 现成覆盖 | 标题即时、无联系人缓存依赖；点击/长按零新增 hook |
| 4 | `data/GroupConvStore.kt` + `GroupConvPlan.kt` | **不动**（继续写库做持久化）+ R63 的 `force=true` 保证建组瞬间落库 | 持久化与 UI 即时解耦：UI 靠注入（即时），库靠 sync（冷启接管） |
| 5 | `data/Keys.kt` + `ConvFilter` | **新增子开关 `chat.gconv_instant`（默认 false）**，注入逻辑全在其守卫内；`gconv=0` 时整块不走 | 红线① + 灰度回滚（见 B7/C9） |

**为什么这比「撬宿主缓存」优**：不依赖宿主混淆名（`ConversationStorage.doNotify` 未取证）、不写宿主内存容器（R54 §3.5「塞错位置打乱 2900 项点错人」风险）、不动联系人缓存（R54 §10.2 红线）。代价是分组行在「注入态」用自绘 View（视觉/交互与冷启后的宿主原生行可能略有差异，需 C8-正对照验收一致性）。

#### B5. 与 R54「不要撬联系人缓存」冲突如何化解

- **推荐方案不撬联系人缓存**：标题从我方 `db.listGroups()` 取，用自绘 `GroupRowView` 画（gconv=0 已验收的老路），`rcontact.nickname` 仍写（供冷启后宿主原生行显示），但**UI 即时态不依赖它**。⇒ R54 §10.2「不要去撬微信的联系人缓存」**原样遵守**。
- **被部分回退的是另一条**：R54 §10.2「保留包装链、只让它走 passthrough，是最优解」。本方案让包装链在 gconv=1 下**恢复注入**，等于把它从 passthrough 改回 active。须向主会话讲清这是**设计取舍回退**，不是 bug 修复：用「原生行零视觉差异 + 零 hook」换「即时显示」。
- **微X 做法的风险（若真去撬缓存/复制它的渲染 hook）**：
  - **版本升级失效**：宿主混淆名（`p3`/`ConversationStorage.doNotify`）每版变，微X dex 加密说明它自己也在不停适配；我方已有多轮适配成本（R53 §9「跨版本鲁棒性中低」）。
  - **改名不即时**：我方若继续靠 `rcontact.nickname`，即使行即时出现，改名仍冷启才生效（R54 §10.1 #5）——除非连标题也注入（本方案做了）。
  - **搜索泄露**：写 `rcontact` 就进搜索（R54 §10.1 #4，与 nickname 无关）；微X 全空名也一样进搜索。本方案不消除此代价（已知代价接受，R54 §10.2）。
  - **崩溃/白屏**：R54 §9 已踩过 `getView` 返回 null 白屏、observer 未挪不刷新；注入态多一种 viewType，convertView 复用若串型会整片空白（`conv_list.md:5`）。
  - **清理/迁移竞争**：数据层库行会被宿主「清空聊天记录/会话迁移」处理（R53 §8.5），本方案库行依旧有此风险，但 UI 注入不依赖它，宿主清掉后冷启会少行（需 GCS sync 兜底重写）。

#### B6. 与 ConvFilter passthrough + readSnaps（K7 snaps/折叠/置顶锚点）兼容

| 机制 | 是否冲突 | 处置 |
|---|---|---|
| `readSnaps` `:1599-1615` 逐条 `getItem` | **正向利用**：改造成顺手收集已存在 gid 做去重（B4-2）；注入行**不塞回宿主 getItem** ⇒ `readSnaps` 读到的仍是纯宿主行，不被污染 | 注入只发生在 GroupedAdapter 的外层计数/取项，不改 `wrapped.getItem` 语义 |
| K7 `orderDirty`/`timeFingerprint`（`conv_list.md:18-63`） | **需确认**：注入行不在 `readSnaps` 产出里 ⇒ K7 的 wxid 序列指纹**不含注入行**；但 `configStamp` 已含 `groups.hashCode()`（`:33`/`:85`），建组/改名会触发 rebuild ⇒ 注入自动刷新 | 必须保证建组走 `refreshMembers()`（`membersVersion++` + `groups.hashCode()` 变），不要只写库不 refresh |
| 置顶锚点 `headerAnchor`/`restoreAnchor`（`conv_list.md:8`） | **有风险 [推断]**：注入行走顶部，绝对 position 会 +N ⇒ 已存锚点（如 -2713 停靠位）相对偏移 | 注入后**不主动动锚点**（R55 Q4 已警告动锚点会伪造「下拉」弹面板）；真机看 C8-正对照的停位，必要时按注入行数平移 `restoreAnchor` |
| 折叠（`parentRef=message_fold`） | 不冲突：折叠是宿主对库行的标记，注入态自绘行不参与折叠；冷启后由宿主原生行接管折叠逻辑 | 无需处置 |
| `tag + cv=null` listener 污染防护（`ConvFilter.kt:93/1799-1801/1837`，红线②） | **不破**：不改这三行；注入行用 GroupRowView + 窄口径 gid 认领 | 保持原样，`ConvFilter.kt:1799-1801` 的丢弃判据原样保留 |
| `ScrollDock` 置顶钳制（`ScrollDock.kt:108-111`） | **需注意**：注入行走顶部，若进入某态使 `currentGroupId()>0` 会唤醒每帧 `offsetChildrenTopAndBottom`（R53 §10.3「下拉不跟手」根因） | 本方案是「建组后显示所有组的注入行」，不进 `setGroup` 筛选态 ⇒ `currentGroupId()` 仍 0 ⇒ ScrollDock 休眠，不唤醒（与 R55 路线 A 的坑不同） |

**B6 结论**：**可兼容，但有两处必须真机盯**——① 建组必须走 `refreshMembers()` 让 `configStamp` 变以触发 K7 重建；② 注入行 +N 后锚点停位是否漂移（C8-正对照）。其余机制（折叠/红线②/ScrollDock）不受影响或被正向利用。

#### B7. gconv=0 零差异红线（红线①）—— 注入必须加守卫

**必须加，且已有天然落点** [已证实]：

- 注入逻辑全部放进 `if (gconvEnabled)` 块 / `if (Kv.getBool(Keys.GCONV_MODE))`，与 R54 现有 `:1243`/`:1780`/`:1824`/`:1344` 同一处读开关。
- `gconv=0` 时：不写库（GCS 守卫 `kickGconvSync :652` 原样）、不注入、列表走**旧自绘分组行** `ConvLayout.build(enabled=true)`（`ConvFilter.kt:1337-1347`），逐字节与今天一致。
- **反向判据**：`kvset chat.gconv_enabled=0` + `op=gcs clean` + 冷启 → `op=xh sub=row` 应见 `vt=1 tag=GroupRowView$Holder`（旧自绘行）、**无** wxgrp_ 原生行、无注入行；`gcs stat` 的 `own` 恒 0。
- 子开关 `chat.gconv_instant` 也要嵌在 gconv 守卫内：gconv=0 时它无论取值都不生效（双守卫）。

---

### C. 判据与决策

#### C8. 真机判据（用户能看见的事实）

**正向（即时显示）**：
1. 开 gconv + 开 `gconv_instant` → 冷启微信 → 记列表顶部组行数 N → UI 新建测试组「R64T」→ **不冷启**，1~2s 内看列表顶部：应出现「R64T」注入行，行数 N+1，标题正确，点击进 `GroupDetailPage`/长按进 `GroupRowMenu`（与冷启后库行行为一致）。
2. 去重对照：**随后冷启一次** → 组行数应**仍是 N+1，不是 N+2**（注入行让位给宿主库行，无重复）。若出现两行同名 ⇒ 去重失效（B4-2 的 gid 收集有 bug）。
3. 视觉一致性对照：同一组在「注入态（未冷启）」与「库行态（冷启后）」分别 dump `op=xh sub=row`，比对 View 类 / 行高 / 标题文本，应用户肉眼无差异；若注入态是 `GroupRowView` 自绘、库行态是 `ConversationFolderItemView`，差异要让用户验收是否可接受。

**反向（红线①）**：
4. `kvset chat.gconv_enabled=0` + 冷启 → 新建组 → `gcs stat` own 不变、无 wxgrp_ 行、列表走旧自绘行（无注入）。

**变异点（证明不是靠冷启/不是巧合）**：
5. **关 `gconv_instant`**（其余不变）→ 新建组 → 预期：写库即时（R63 force 已保证 stat own +1）但 UI **不出现**，冷启才出现 ⇒ 坐实「即时显示」的**必要因子是注入，不是写库/冷启**。
6. **微X 侧对照**：在装有微X的设备上，新建微X分组前后各 `sub=sql` 数 `wx__c_` 行数，若 +1 且 UI 即时出现 ⇒ 排除候选 (e)「复用常驻行」，坐实微X 也写新行 + hook 注入（A1-e）。
7. **宿主 notify 归因**：hook `p3.notifyDataSetChanged` after，建组时应 0 条（R54 §3 命令②）⇒ 坐实我方注入不靠宿主 notify；若微X侧同 hook 有日志 ⇒ 候选 (b) 成立（[需真机]，主会话持设备可顺手验）。

#### C9. 风险分级 + 是否需要灰度开关

| 方案 | 风险 | 理由 | 开关建议 |
|---|---|---|---|
| **推荐：包装链恢复注入 + 自绘标题 + 库行去重** | **中高** | 动 K7 位置映射 / viewType / 锚点 —— R1~R34「点错人」「下拉糊屏」历史全在这块（`conv_list.md:1-16`）；但全程在我方包装链内、零宿主 hook、可一键关 | **新增 `chat.gconv_instant` 默认 false**，真机 C8 全过后由主会话 flip 默认；**不**沿用 R61「直接默认开」先例 |
| 候选 b：撬宿主 `ConversationStorage` 缓存 + doNotify | **高** | 混淆名未取证、每版变、写宿主内存容器可能打乱 2900 项（`conv_list.md:10`/R54 §3.5「点错人」）、需真机逐方法比对 | 仅在前案失败后另立 Pro 任务，绝不与本方案混做 |
| 退路：手动「刷新分组行」入口 | **低** | 纯模块 UI + 触发一次 `refreshMembers` + 尽力 notify，不碰位置映射 | 可随开关常驻 |
| R63 的 `force=true`（仅写库即时） | **低** | 2 行、1 文件、保留守卫 | 可独立先上（但**不满足**「即时显示」目标，用户已否，台账 `:4752`） |

#### C10. 退而求其次的可行方案（dex 取不到 ⇒ 必须用）

**dex 确认取不到（A2 三项封锁）⇒ 给两条不依赖微X dex 的退路**：

| 方案 | 做法 | 体感差距 |
|---|---|---|
| **退路 1（推荐退路）：手动刷新入口** | 在 `GroupBar` 横条 or 分组管理页加「刷新分组行」按钮/广播 → 调 `ConvFilter.refreshMembers()` + 尝试对 `p3` 反射 `notifyDataSetChanged()`（R54 §3.5 候选 C，失败则打日志不崩）+ 提示「若未出现请冷启」 | 用户需**手动点一下**，不是「新建后自动即时」；且不保证成功（notify 可能被宿主缓存短路）。体感明显差于微X，但零高风险、可立刻上 |
| **退路 2：模拟一次宿主会话插入触发 notify** | 用宿主 insert API（而非 execSQL）写 wxgrp_ 行，寄望宿主自己 notify | [推断] 高风险：宿主会按「未知 username」过滤、可能开空聊天页（R53 §11.5）；且仍需 DexKit 定位 insert API 混淆名（未取证）。**不推荐** |

> 若连「包装链恢复注入」也不被主会话接受（担心位置映射风险），**退路 1 是唯一低风险可交付项**。

---

## 3. 推荐实现方案（落点汇总）

**主推**：包装链在 gconv=1 下恢复「顶部注入分组行」+ 自绘标题（读我方库，不读 `rcontact.nickname`）+ `readSnaps` 顺手收集 gid 去重 + 保留 GCS 写库持久化 + R63 `force=true`；新增 `chat.gconv_instant` 子开关（默认关，嵌在 gconv 守卫内）。

| 文件 | 改动 | 红线守卫 |
|---|---|---|
| `feature/ConvFilter.kt:1332-1347` `rebuild` | gconv=1 且 instant 开时，layout 顶部注入分组行（不再恒直通） | 在 `if(gconvEnabled)` 内 |
| `feature/ConvFilter.kt:1599-1615` `readSnaps` | 收集宿主已存在 `wxgrp_<gid>` 集合供去重 | 不改 getItem 语义 |
| `feature/ConvFilter.kt:1746-1853` `getView/getItemViewType` | 注入位返回 GroupRowView + 窄口径 gid 认领 + 现成点击/长按覆盖 | 红线②三行不动 |
| `data/ConvLayout.kt:202-250` | 位置映射支持顶部注入行（+N 偏移） | 不碰宿主 |
| `data/Keys.kt` + `ConvFilter` | 新增 `GCONV_INSTANT` 开关（默认 false）+ O(1) 读取字段 | 红线①双守卫 |
| `data/GroupConvStore.kt` | 不动（写库持久化）+ 采用 R63 `kickGconvSync(force=true)` | 红线③④不动 |

**不动**：`GroupConvStore`/`GroupConvPlan`（红线③④）、`ScrollDock`（不进筛选态，不唤醒）、`GroupDetailPage`/`GroupRowMenu`（沿用）、`tag+cv=null` 防护（红线②）。

**风险缓解**：① 全程我方包装链、零宿主 hook；② 去重保证恒 N 行；③ 子开关默认关 + 一键回滚；④ 不改锚点（真机先看停位）；⑤ 动位置映射的每一步都配 C8 真机判据（对照 R1~R34 的「点错人」纪律）。

---

## 4. 真机验证步骤（正反对照 + 变异点）

前置：主会话持设备锁；微X 是否安装/启用如实记录（影响 A1-e 对照）；`ADB`/`P` 变量沿用 `docs/_r54_gconv_design.md:145-149`；gconv 开关需「`kvset` → 冷启」才生效（R54 §10.2，运行时翻转不生效）。

| 步骤 | 动作 | 判据（用户能看见的事实） | 定什么 |
|---|---|---|---|
| 0 基线 | 冷启 → `op=xh sub=row` + `gcs stat` | 记顶部组行数 N、`own=M`、View 类 | 基线 |
| 1 写库即时（R63） | 新建测试组 → 1~2s `gcs stat` | `own=M+1`、`wxgrp_<hex>` + `nickname=R64T` 出现 | 证明 force 后落库（非本题主体，但排除层1） |
| 2 **即时显示正向** | 不冷启看列表 | 1~2s 内顶部出现「R64T」注入行，行数 N+1，标题正确，点/长按可用 | 证明注入让 UI 即时（核心） |
| 3 **去重对照** | 冷启一次再看 | 行数仍 N+1（非 N+2），同名行只有一条 | 证明库行接管、注入让位 |
| 4 视觉一致 | 步骤2/3 各 dump 一次行 | 用户肉眼行高/标题/位置无差异（差异需验收） | 注入态 vs 库行态一致性 |
| 5 **反向红线①** | gconv=0 + 冷启 + 建组 | `own` 不变、无 wxgrp_ 行、无注入、旧自绘行 | 红线① |
| 6 **变异：关 instant** | 关 `chat.gconv_instant` + 冷启 + 建组 | stat own+1 但 UI 不出现，冷启才现 | 坐实注入是必要因子 |
| 7 **变异：微X对照** | 微X 新建组前后 `sub=sql` 数 `wx__c_` | 行数 +1 且 UI 即时 ⇒ 排除候选 e | 定 A1-e |
| 8 **observer 归因** | hook `p3.notifyDataSetChanged` after，建组 | 我方 0 条；微X侧若有日志⇒候选 b | 定 A1-b |

---

## 4b. 运行时侧观察探针方案（主会话可执行，绕开微X加固 dex）

> 方法论来自 R55c `:52-78`，落点全部是我方**现成设施** + 已解包的微信 dex，本 agent 本轮禁碰设备仅出方案。
> 目标：在**不拿微X dex** 的前提下，把 A1 的候选 b/c/d/e 一锤定音，并拿到可挂 hook 点。

### 4b.1 为什么这条路成立（[已证实] 的方法论 + 设施）

- R55c 已证：hook 微信 API 的运行时日志能暴露「微X操作了哪个微信对象」，再用微信 dex 验证类定义（`classes4.dex` 里 `Lva4/j3;`、`Lva4/m4;` 各 1 次=类定义）。
- 微信 dex **全量已解包** `_cache/dex/classes*.dex`（15 个，微信 8.0.48/2588，`AutoDndGroup.kt:299`）。
- 会话刷新的**唯一汇合点**已被我方真机栈实证（`GconvPage.kt:821-864`）：`AdapterView$AdapterDataSetObserver.onChanged → w8.getCount → w8.q → W(宿主游标)`；`t0.N()` 是原生查询入口，`p3`/`d2` 是 adapter/view。
- 我方现成 hook：`AutoDndGroup.kt:305-368` 已 hook `BaseAdapter.notifyDataSetChanged`（`param.thisObject`=活的会话 adapter），`:382-414` 能识别会话 adapter，`:417-426` 能遍历行 username。

### 4b.2 探针清单（按可证伪性排序，逐条独立开/关，一次只动一个）

| # | hook 落点（微信 API） | 观测什么 | 结论映射 |
|---|---|---|---|
| **P1** | `android.widget.BaseAdapter.notifyDataSetChanged` after（`AutoDndGroup` 现成），按 `isConversationAdapter` 过滤 | **微X 新建分组那一刻，会话 adapter 被 notify 了几次**（带时间戳） | 有 notify ⇒ 候选 b/d（走宿主通知/插入）；**0 条但行仍即时出现 ⇒ 候选 c（直接改数据源，连 notify 都不靠）** |
| **P2** | `com.tencent.mm.ui.w8.W(Cursor)` before（`GconvPage` 现成），记录入参游标行数 | 微X 建组时 `W` 的入参游标行数是否 +1、是不是被换成含 `wx__c_` 的游标 | 被换游标 ⇒ 微X 在**游标层注入**（候选 c 的具体落点 = 可挂 hook 点） |
| **P3** | `com.tencent.mm.ui.conversation.p3.getCount()` / `getItem(int)` after | 微X 建组前后 `getCount()` 是否 +1、`getItem(i)` 是否冒出 `wx__c_` 对象 | 是 ⇒ 候选 c 的铁证，且给出**精确 hook 点 `p3.getCount/getItem`** |
| **P4** | `com.tencent.mm.ui.conversation.t0.N` before（`GconvPage` 现成） | 微X 建组是否触发原生查询入口 `N()` | 触发 ⇒ 走宿主查库（b/d）；不触发但行出现 ⇒ c |
| **P5** | `SQLiteDatabase.execSQL` / `insertWithOnConflict` before（`AntiRevoke.kt:377-382` 现成 hook 点）按表名 `rconversation` 过滤 | 微X 建组时**是否真的写了新行**、写的 SQL 原文 | 行数 +1 ⇒ 排除候选 e（复用常驻行）；同时拿到它的写库模板 |
| **P6** | `op=xh sub=row`（`HookListProbe`，我方现成） | 微X 新行在 adapter 里的 `viewType` / `tag` / `item` 类型 | 确定行是「宿主原生行」还是「注入的假对象」 |

### 4b.3 执行纪律（避免前人踩过的坑）

- **一次只开一个探针**（P1~P6 各自独立开关），否则多 hook 互相干扰时间戳；每轮 `dev.py q <tag> N` 拉日志后**立刻关**。
- **微X 与我方分时段测**：微X 探针日志要在**关掉我方 gconv 模块**（或至少 `chat.gconv_enabled=0`）时采，否则我方 `notifyDataSetChanged` 会混进 P1 日志（R63 已证我方建组 notify=0，但收消息会 notify，要控变量）。
- **改完不冷启**（R48/R49 纪律）：冷启会清进程内 hook/缓存，是第二个自变量；每组只改一个开关。
- **反编译只针对微信 dex**：若 P3 命中 `p3`，去 `_cache/dex/classes*.dex` 用 R55c 的 Python 脚本数 `Lcom/tencent/mm/ui/conversation/p3;` 类定义次数验证，**不要去啃微X的7个密文**。
- **adb 长按触发不了菜单**（R55 §11.3），本探针全是建组/notify，不涉及长按。

### 4b.4 预期产出（主会话执行后回填本文档）

1. 一张「候选 b/c/d/e → 命中/排除」的定案表（现在全是 [推断]）。
2. 若是候选 c：拿到**精确到 `p3.getCount/getItem` 或 `w8.W` 的可挂 hook 点**——注意这正是我方**已有包装链**覆盖的层，进一步佐证 B4「不用复刻微X」。
3. 微X 建组写库 SQL 原文（P5）⇒ 核对是否与我方 `GroupConvPlan.insertConvSql`（`:99-110`）一致，确认「行模板」无隐藏差异。

---

## 5. 风险与回滚 + 取证缺口清单

### 风险与回滚
- **主要风险**：动 K7 位置映射 / viewType / 锚点 ⇒ 潜在「点错人」「下拉糊屏」「convertView 串型白屏」（R1~R34 历史、`conv_list.md:5/10`）。缓解：子开关默认关 + 不主动动锚点 + 每步配 C8 判据 + 窄口径认领。
- **回滚（三级）**：① 关 `chat.gconv_instant` → 回到 R54 passthrough（冷启才显示，功能降级但零风险）；② 关 `chat.gconv_enabled` + `op=gcs clean` + 冷启 → 回到 gconv=0 旧自绘行；③ 极端：`git revert` 本轮改动（若已提交）。均不动表结构/配置指纹。
- **搜索泄露**：写 `rcontact` 就进搜索（R54 已知代价），本方案不消除也不加剧；根治需另立搜索过滤 hook，别为此推翻本方案。

### 取证缺口清单（按重要性）
1. **[取证缺口·最关键]** 微X「新建组→不冷启→N 秒内出现」的**真机时间戳**：无任何日志/截图。这是 A1(a/e) 不定案的根因。主会话持设备，C8-变异6/7 + §4b P1/P5 可一次性补上。
2. **[静态缺口，可直接绕过]** 微X 业务 dex 加密（7 个密文、key 在剥符号 .so）⇒ 静态拿不到它的 hook 类/方法/dex 地址（A2）。**但不必绕**：§4b 的运行时侧观察（hook 微信 API 看微X操作了什么）已由 R55c 实证可行，且微信 dex 全量已解包。**不要去解微X的密文**。
3. **[取证缺口→§4b 可补]** LSPosed hook 表按模块隔离 ⇒ `op=xh sub=hook` 读不到微X已挂 hook（R53 §8.6/R55 Q2）⇒ 枚举不出它挂了什么。但**可从微信侧反推**：§4b P1~P4 观测宿主 API 的调用/游标变化，即可判定它走 b 还是 c（不依赖读它的 hook 表）。
4. **[取证缺口]** `FKZ_WX_ChatroomTag`(16KB)/`FKZ_WX_DATA` 加密打不开 ⇒ 微X 配置结构（标签→会话）未知，影响 A1-e 判断。C8-变异7 的 SQL 行数对照可绕过。
5. **[需真机]** 注入态自绘行 vs 库行态宿主原生行的**视觉/交互一致性**（C8-步骤4）；锚点停位是否漂移（B6）。
6. **[需真机]** 候选 b 的宿主 `ConversationStorage.doNotify` 混淆名（R54 §3.3 需用 `DexKitLocator` 按 `select...from rconversation` 字符串反查）—— 仅在前案失败时才需要。

---

## 6. 实际读过的材料（可复核）

- **权威源**：`CLAUDE.md` §四（:81-215）/§五（:252-274）；`docs/开发回路速查.md`（本轮未全文，沿用前序结论）；`docs/conclusions/conv_list.md`（全文 96 行）。
- **微X取证**：`docs/_r53_microx_grouprow.md`（§1 加密、§2 真机、§8 机制定案、§9 路线对照、§10 流畅归因、§11 R55补充、§12 ConvBox）；`docs/_r55_microx_click_longclick.md`（全文，Q1 候选 A/B、Q2 hook 点、Q6 红线）；`docs/_r55c_longpress_menu.md`（菜单，本轮未单独读，结论沿用 R55 Q3）；`docs/_r55e_listview_rootcause.md`（本轮未单独读，ListView/observer 结论沿用 conv_list.md）。
- **我方定案**：`docs/_r54_gconv_design.md`（§3 刷新链路 :117-159、§10.1 九项实测 :461-475、§10.2 修正 :477-483、§10.3 最终实现 :485-515）；`docs/_r63_new_group_not_shown.md`（全文 259 行）；`docs/派发审计台账.md:4740-4753`（用户拍板原文）。
- **二进制（只读）**：`_mods/wechatxposed.apk` / `_cache/wxposed.apk`（4,423,605B）、解包 `_mods/x/`（`classes.dex` 2,397,988B + `assets/` 9 hash 文件）、`_mods/shell.txt`（40MB dexdump，仅外壳）。

---

[DONE] R64 取证完成。机制一句话：微X = 数据层写 `wx__c_` 伪会话持久化 + 渲染/数据层 hook 注入（标题已证、行即时可见最可能同源 `getCount/getItem`）双轨；我方复用走「包装链恢复注入 + 自绘标题 + 库行去重」，不撬宿主缓存、不追加密 dex，新增 `chat.gconv_instant` 默认关灰度。
