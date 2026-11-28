## 会话列表（K7 / ConvFilter）
- 结构：`ConversationListView` → `HeaderViewListAdapter`(headers=14,footers=1) → `mAdapter=com.tencent.mm.ui.conversation.p3`（2900+ 项）→ `getItem()=com.tencent.mm.storage.r4`(field_username)。
- **「停在顶部」≠ child0.top==0**：顶部栏是第一个 header，正常停靠 **-2713**（运行时抓，别硬编码）。`setSelectionFromTop(0,0)` 会制造 ~2051px 虚假下拉 → 弹小程序面板。
- **`ListView.correctTooHigh()` 会整块下移内容**（`fillSpecific` 里也调）→ 只能 hook 吞掉（`before` 里 `param.result=null`）。⚠️ 已废弃方案：补尾部填充行（会把 lastBottom 钉死 → cannotScrollUp 恒假 → 上拉滚到底空白）。
- 换 adapter 后必须按新 `viewTypeCount` 重建 `RecycleBin`，否则回收池串类型 → 会话行整片空白。
- **AOSP 滚动边界对巨型 header 不成立**：`cannotScrollDown` 恒假 → 下拉无限制。见 `ScrollDock`：hook `trackMotionScroll`，after 里把 child0.top 钳回停靠位（`offsetChildrenTopAndBottom` 是 @hide，**必须反射**，失败一次永久停用）。
  ⚠️ **两个守卫分开**：吞 correctTooHigh 只看结构（`firstVisiblePosition==0 && child0.top<0`）；下拉钳制只看筛选态（`currentGroupId()>0`）。不筛选时 firstVisiblePosition **也是 0**。
- **`headerAnchor` 只认藏得最到位的那次（取 min）**（可动区间 [dock,0]，用户下拉停在 -1500 也是 0）。`restoreAnchor` 拿不到锚点要 **return 不要退回 0**。
- 行点击走**每行自己的 OnClickListener**，不走 `performItemClick`（`onItemClick` hook 零命中）。
  ⚠️ 因此**改 `realPosition` / 位置映射治不了「点错人」** —— 唯一办法是让 layout 不漏重建。
  ⚠️ `ConvFilter.kt` 里那条 `hookItemClick`（`performItemClick` 三层全挂）是**死代码**，
  真机零命中，其 KDoc 还写着"点击走它"。**已于 2026-10-25 连同 KDoc 一起删除**，
  现文件内保留的是「为什么删」的说明，三方说法已统一。
- `ConversationListView` 覆写了 set/get `OnItemClickListener` → `is ClickRemap` 判幂等失效 → 必须剥到最里层（`unwrapLongClick`）+ ThreadLocal 重入守卫，否则无限递归 ANR。
- `AbsListView.LayoutParams.viewType` 是包内可见字段，读要反射。
- 实测项数：不筛选 2957（内容溢出），筛选态 2。

### K7 快照失效判据（R22 定案 · 已实现，待真机验收）

**症状**：点分组行 G2 进去后「点错人」，同时分组行自身的摘要/时间/未读是旧的。

**三重根因**（缺一不可，修一条不够）：

| # | 根因 | 旧代码 |
|---|---|---|
| R1 | **快照冻结** | `if (snaps.size != n) snaps = readSnaps(n)` —— 收新消息导致**重排而条数不变**时 snaps 永不重读 ⇒ layout 冻结 ⇒ 点错人 |
| R2 | **成员入口不刷新** | 6 处写 `group_member` 表的调用点（`GroupDetailPage`/`MemberPage`×2/`BatchPicker`/`GroupPicker`/`GroupManagerPage`/`BackupFlow`）全都不通知会话列表 |
| R3 | **裸 wxid 显示** | `db.findFriend(w)?.displayName ?: w` —— 群内未加好友的人回落成 `wxid_xxx` |

**修复判据（分层失效，三层各自的触发条件）**：

```
configStamp = "$n|$enabled|$filterId|$showUnread|${cachedMembers.hashCode()}|${groups.hashCode()}"
orderDirty  = size 变 || orderChanged(snaps, fresh)     // 只看 wxid 序列
membersDirty = membersVersion 变
summariesDirty = summariesVersion 变
```

1. `orderDirty` ⇒ **全量重建**（layout + models + 重读 memberSets）
2. 顺序没变、但 `timeFingerprint`（只滚 timeMs）变了 ⇒ **layout 复用，只 `rebuildModels`**
3. 全同 ⇒ `return@synchronized`（零开销）

**关键定案（易错点）**：

- **`stamp` 不能含顺序**：旧 `stamp` 里没有 wxid 序列 ⇒ 「重排但条数不变」被判成「配置没变」。这正是 R1 的本质。
- **`orderChanged` 必须把空 wxid 当「顺序未知」直接返 true**：`readSnaps` 在 `getItem(i)` 返回 null 时塞 `ConvEntry("")` 占位以对齐下标。那种位置上「两次都读到空」**不代表底层没变**，只是两次都没读到。把 `"" == ""` 当相等会漏判 ⇒ 又变成点错人。正常会话不可能有空 wxid（`build()` 也会过滤 blank），所以保守返 true 的代价只是 adapter 重载窗口里多跑一次全量重建。
- **`timeFingerprint` 有盲区，不是万能的**：`unread`/`digest` 不在 `readSnaps` 产出里，只能拿 timeMs 当代理。
  - 「收新消息」→ 微信 bump conversationTime → 覆盖得到 ✅
  - 「**标记已读**」→ unread 清 0 但 **timeMs 不动** → 指纹不变 → 被短路，角标一直挂着。**这是高频日常操作，不是边缘场景。**
  ⇒ 所以有 `refreshSummaries()` 专门维护 `summariesVersion` 破这个短路。
- **`refresh()` ≠ `refreshMembers()` ≠ `refreshSummaries()`**，三者语义分开：
  - `refresh()`：只重算 layout。**不重读 `cachedMembers`** ⇒ 成员关系变化用它会无效功。
  - `refreshMembers()`：先重读 `cachedMembers` + `membersVersion++`，再 refresh。**成员增删/备份恢复必须用它。**
  - `refreshSummaries()`：`summariesVersion++` + refresh。**只改 unread 不改时间（标记已读）必须用它。**
- **`refreshMembers()` 走方案 X（不动 `cachedId`）**：`cachedId` 由 `setGroup()` 独占管理；若走 `refreshCache()` 全量重读，会在 `setGroup` 写 `FILTER_GROUP` 的竞态窗口内读到旧值覆盖新的 `cachedId`。已核实 9 个调用点全在主线程，与该竞态串行，安全。
- **observer 不能走 `refresh()`**：`refresh()` 会 `rememberAnchor` + `restoreAnchor`（`setSelectionFromTop`）把列表拽回顶部，收消息时会打断阅读。用 `onDataChanged()`（250ms 去抖，不碰滚动锚点）。
- **两条去抖链用独立标记**（`rebuildPending` / `observerRebuildQueued`），同队列串行，无字段互相覆盖。

**`Group` 是 data class，不含成员字段**（`GroupDb.kt` 只有 `id/name/color/sortOrder/createdAt/pinned`）⇒ **成员增删不改 `groups.hashCode()`**，这是为什么必须有 `membersVersion` 而不能只靠 `configStamp`。

**⚠️ 性能闸门（`PROBE_WARN_MS = 8L`）**：R1 修复把 `readSnaps(n)` 从「条数变化才走的冷路径」变成「每次 rebuild 都走的热路径」（2900 项逐条 `getItem` + 反射读字段，主线程每 250ms 最多一次）。
`getItem` 实现体在微信 dex 里，静态判不出成本，所以加了探针：超阈值打 `K7 perf n=... snaps=...ms total=...ms path=...`。
**若真机日志反复出现该行**，说明该退化成「先比 count + 首末 wxid，不符才全量读」的两段式（代价：中部局部重排可能漏检）。

## 会话长按菜单（A15 / ConvMenuInject）
- **微信长按菜单是自绘 PopupWindow，跟 AOSP ContextMenu 是两条平行链路**：
  `PopupWindow$PopupDecorView(601x884)` → `va4.m4`(容器) → `va4.j3`(525x808, ListView 子类) → 4 个 TextView（行高 202）。
  不走 `MenuBuilder.add`（零命中）、不走 `View.createContextMenu`（那条链全空）。
- **K7 的 `LongClickRemap` 会让这 4 项菜单消失** → 开关 `chat.conv_longclick_remap` **默认 false（弃用该包装）**。分组行长按走 `GroupRowMenu`，不受影响。
- 注入方案：hook `View.performLongClick`(2 重载) 的 **before** 记 lastWxid（`getPositionForView`+`ConvFilter.wxidAt`，早于 show 约 60ms）；hook `PopupWindow.showAsDropDown/showAtLocation`(5 重载) 的 **after**，4 秒窗口内动手。
  ⚠️ show 瞬间容器是空的 → 主线程轮询重试（0/40/80…ms，最多 8 次）。
  ⚠️ 容器是 AdapterView，`addView` 抛 UnsupportedOperationException → 走 `ListView.addFooterView(v,null,false)`（isSelectable=false 必须，否则同时触发微信 OnItemClick，footer 位置 getItem 为 null 可能崩）。
  ⚠️ 加进去的 View 必须是 `AbsListView.LayoutParams`（`setupChild` 无条件强转，普通 LP → CCE 崩微信）。
  ⚠️ 窗口固定高度（601x884，列表 808=4×202 刚好填满）→ 必须让 box/父容器/popup 一起长高 `行高×项数`，再 `popup.update(w,h+need)`（只接受 >0）。
- **adb 长按触发不了微信菜单**（被识别成点击），必须真手长按。做基线对照时触发方式要和实验组一致。
- 探针 `probe/MenuHuntProbe.kt`：`op=menuhunt sub=on|off|flush|tree|win|snap|clear [--ei sec N]`，70 个候选 hook + 不依赖 hook 的 View 树 diff。长按自动开 8 秒窗口；测完务必 `sub=off`（hook 了 addView/setVisibility 会拖慢微信）。
  ⚠️ 调用栈过滤没剔 LSPosed 桥接帧 → 要看真实调用点读每条 HIT 行 `at` 后**第 4 帧起**。
- 自弹 AlertDialog 方案（v0.2.5 `feature/ConvMenu.kt`）**已删除**。

## 分组行长按菜单 / 标记已读（v0.2.2）
- 长按菜单自己弹 AlertDialog（`GroupRowMenu`），**别注入微信的长按菜单**（那个 PopupWindow 无可挂 hook）。
- **长按回调必须 return true**（false 会冒泡 → 微信按会话处理弹「删除该聊天」/ 变点击）。
- **长按 listener 包装必须有调用期重入守卫**：微信 `conversation.z2` 在 `onItemLongClick` 内会回头调 `list.getOnItemLongClickListener()` 拿到我们的 wrapper → 无限回环栈溢出。`unwrapLongClick` 救不了。重入时返回 **true**。
- **Dialog 的 Context**：`HostContext.activity()` 靠 hook `Activity.onResume`，但 **LauncherUI 覆写了 onResume，hook 基类不触发** → 恒 null → BadTokenException。已改 `Application.registerActivityLifecycleCallbacks`。需 Dialog 处**优先用 View.getContext**。
- `GroupedAdapter.rebuild()` 的指纹缓存**必须含分组状态**（`db.listGroups().hashCode()`），否则置顶/重命名/删分组后列表不刷新。
- A7 标记已读只改内存（`field_unReadCount`=0），重启微信恢复；真落库需 DexKit 定位写库接口，别硬猜混淆名。
- A13：会话对象 `field_flag` 恒为 `conversationTime | (1<<62)`，**没有置顶位** → adapter 判不出原生置顶区块，pinned 分组行排最前。

## 返回键 / 退出行为（v0.2.1）
- **LauncherUI 的返回不在 `onBackPressed` 里**：走 `dispatchKeyEvent` → `HomeUI.r()` → `LauncherUI.moveTaskToBack()`，拦 onBackPressed **永远来不及**。
- 要拦返回：hook `LauncherUI`（沿继承链）的 `dispatchKeyEvent`，`KEYCODE_BACK + ACTION_DOWN + repeatCount==0` → `param.result=true`。
- 判定「在会话列表而非聊天页」：我们包装的 ListView 是否 `isShown && windowVisibility==VISIBLE`；取不到**保守放行**。
- 筛选态没有「返回全部」行，退出筛选靠系统返回键。
- 定位「谁把 App 退出去了」：hook `Activity.moveTaskToBack`/`finish` 打 16 层 stackTrace。


## 查询层隐藏（R89 · 复刻微X「加入分组后主列表不显示」）

**需求**：加入分组的会话不再出现在微信主聊天列表，只有进分组页可见。数据一行不删。

### 行为基线（微X 真机实测，2026-11-28）
加入微X 分组后主 adapter 原始条数 **1581→1580**，移出后回到 **1581**；
`rconversation` 行仍在（`msgCount=11407, unReadCount=0, parentRef='', attrflag=0`），
`message` 表该 talker **11407 条全部保留**。
⇒ 隐藏在**查询层**，不是 `getView` 隐藏、更不是删库。R64 早期「getCount/getItem 注入」推断不适用（R66/R67 已推翻）。

### 宿主查询链与 hook 落点（阶段0 实测）
所有 `rconversation` 查询都汇聚到 `com.tencent.wcdb.database.SQLiteDatabase.rawQueryWithFactory`：
- SQL 在 `param.args[1]`；4 参版**内部转发** 5 参版 ⇒ 一次查询可能连进两发；
- 全 15 dex 无 `SQLiteDatabase` 子类 ⇒ `hookAllMethods` 覆盖完整；
- 两条入口栈：`e.s <- z.g <- p3.f`（UI adapter 刷新）/ `s4.p <- s4.M <- s4.K`（服务侧同步）。
  ⛔ 早期记的 `s4.t` 是错的，实测 **`s4.p`**。
- ⛔ 这条链**不走** `rawQuery`，所以只 hook `rawQuery` 的老探针永远看不到会话列表 SELECT。

### 主列表全量 SQL 原文（606 字符，判据锚点）
```sql
select unReadCount, status, isSend, conversationTime, username, content, msgType, flag, digest, digestUser, attrflag, editingMsg, atCount, unReadMuteCount, UnReadInvite, hasTodo, hbMarkRed, remitMarkRed, parentRef from rconversation where ((parentRef is null) or (parentRef in ('', 'message_fold'))) and ( 1 != 1  or rconversation.username like '%@im.chatroom' or rconversation.username like '%@chatroom' or rconversation.username like '%@openim' or rconversation.username not like '%@%' )  and rconversation.username != 'qmessage' and rconversation.username != 'appbrand_notify_message' order by flag desc
```
（上为**逐字单行原文**，606 字符，仅供判据比对；日常阅读可按 `where` / `and (` / `order by` 三段拆开看。）
- **不是 `select *`**：**19 列**显式投影；**无 `limit`**；`order by` **只有 `flag desc`**（没有 conversationTime）。
- ⛔ **列清单会漂**（另一版带 `hasSpecialFollow`）⇒ 判据**绝不能比对列名**。
- `1 != 1` 是宿主拼装可选子句的恒假占位（`or` 串条件、缺项留空格）。

### 六种真实形态与判据分流
| 形态 | 特征指纹 | 过滤 | 理由 |
|---|---|---|---|
| I 主列表全量 | `message_fold` | **要** | 主列表数据源 |
| C 批量明细 | `username in (1~3 个)` + `message_fold`，无 `order by` | **要** | 每屏明细，漏了会话回流 |
| F 未读聚合 | `rcontact` + (`union` 或 `unReadCount>0`) | **要** | 否则角标虚高；带 JOIN |
| A/B 点查 | `username=?` / `username='x'` | **不要** | 打开聊天 + 消息到达（`t0.e`，78 条里 42 条） |
| E `msgCount` | 单列点查 | **不要** | 与列表无关，改影响删聊天统计 |
| G 折叠页 | `parentRef='conversationboxservice'` | **不要** | 语义不同，本轮不做 |

⛔ **I/C 的指纹必须锚 `message_fold` 一词**，不能锚「有 `parentRef is null`」——F 的 JOIN 版也有 `parentRef is null`，会失去区分度。
⛔ 注入条件必须**带表名限定** `rconversation.username not in (…)`：F 带 JOIN/union，裸 `username` 会 ambiguous → SQLite 抛错 → 熔断废功能。

### 实现要点（`feature/ConvSqlHide.kt`）
- 三级短路，热路径零开销：`!enabled||disabled` → `grouped` 为空 → `!isConvSelect(sql)`。
- `inHook` ThreadLocal + **try/finally** 复位（漏 finally = 该线程后续查询全静默，参照 `AntiRevokeHint.kt:41-51` 的 ANR 血案）。
- 快照 `@Volatile Set<String>` **整体替换引用**，只在后台线程/成员变更时读 `GroupDb.allMemberWxids()`；读库失败发布**空集**（宁可暂时显示，绝不残留隐藏）。
- ⛔ **熔断必须按异常类型过滤**：`hasThrowable()` 即熔断的话，一次 `database is locked` 就把功能永久停用。只认结构类（syntax error / no such column / misuse / ambiguous / no such table），瞬态只 WARN。
- ⛔ **after 里用「对象同一性」匹配被改写的那一发**（`rewrittenParam` ThreadLocal）：因为 4 参转发 5 参，一次查询连进两发 after，只按 `origSql` 会清错层。
- **首次 refresh 只建基线、不发通知**（`groupedVersion==0` 识别），否则冷启会把全部历史成员当新增、批量发 `s4.b(event=3)`。
- 成员变更后对**差集**补发 `s4.b(3, s4, wxid)`。依据 `_r65:305-306`：宿主自己发 `s4.b event=3` 后 52ms `p3.e event=3` ⇒ event 3 = 会话更新。
- 失效挂点只有一个：`ConvFilter.refreshMembers()`（全部 10 个成员写库入口的汇聚点，含 `BackupFlow` 恢复路径）。
- ⛔ union 形态必须**分段注入**（`injectUnion`）：F 的 union 版第二分支（`inner join rcontact`）才是未读主查询，条件插在顶层 `union` 之前只作用第一分支 ⇒ 角标**必然**虚高。

### 分组页不会被误伤（关键）
`GconvPage.kt:1128` 的分组页查询（`username in (…)` 为节选，in 里是当前分组的成员）：
```sql
select * from rconversation where (username in (…)) and substr(username,1,6)<>'wxgrp_' order by flag desc, conversationTime desc
```
`isConvSelect` 第 6 步对含 `wxgrp_` 的 SQL 直接短路 ⇒ 不改写。
真机确认（`on` 状态下进分组）：标题 `123 (5)`，5 个会话全可见。
这是「功能自相矛盾」的唯一防线：分组页若也被过滤，会话将无处可见。

### 开关与探针
- 持久化开关 `Keys.GCONV_SQL_HIDE = "chat.gconv_sql_hide"`，默认 false，**冷启生效**。
- 内存 A/B：`op=convsqlhide --es sub on|off|status`（不写 Kv，冷启复位）。
- 取证探针 `op=hcr --es sub sqlwatch|sqlwatchoff`（R89 阶段0，**已收工**，Kv 键 `probe.hcr.sqlwatch` 不进设置页/备份）。
  ⚠️ 探针 hook 常驻：阶段2 上线后若再开它，看到的是**改写后**的 SQL，不能当原文证据。
- ⚠️ `turn()` 只翻内存、不刷 UI：切换后不会立刻变，要等宿主周期重查（实测约 4 秒一次）+ adapter notify。
- ⚠️ `enabled=false` 时 hook 第一行 return **不打日志** ⇒ 「off 后日志无 hit」不能反推宿主没重查。

### 真机验证（微信 8.0.48/2588，2026-11-29）
| 项 | 结果 |
|---|---|
| 默认关零行为变化 | ✅ `kv` 表无该键 ⇒ `getBool` 回落 false；主列表显示与装机前一致、零改写 |
| 开启命中 | ✅ `HIDE hit n=5 len=606->762`，`606` 与上面原文长度逐字吻合，`762-606=156` 即注入的 NOT IN 子句 |
| 持续生效 | ✅ hits 累计 74，`disabled` 从未置位（74 次改写无一次被 SQLite 拒绝） |
| 主列表 | ✅ on → 5 个会话全消失；off → 全部恢复 |
| 分组页 | ✅ on 时 `123 (5)` 全可见 |

### 排查坑
- ⛔ **模块库真实路径 = `/data/data/com.tencent.mm/databases/wxgroup.db`**（模块跑在微信进程、用微信 Context），
  **不在** `com.lx.wxgroup` 包名下 ⇒ 按自身包名 `find` 永远 No such file。读它需 root。
  ⚠️ `adb shell "su -c id"` 整条用双引号包住；`su -c 'id'` 单引号会被 adb shell 二次解析吞掉。
- ⛔ **调用栈过滤**：栈首是 `dalvik.system.VMStack.getThreadStackTrace`，不属于任何被排除前缀，
  `indexOfFirst` 恒返回 0 ⇒ 打出来全是本模块帧、宿主帧 0 条（8 条日志全废）。
  正解：以本模块**最后一帧**为界、只取其后帧，再滤 Xposed/反射/VMStack。深度 10 不够，用 24。
- ⛔ 微信库 14GB 且 SQLCipher 加密，**离线读不了**，别指望 pull 出来用 sqlite3 查。

细节全文：`docs/_r89_replica_plan.md`（方案 + 阶段0 取证）、`docs/_r89_stage1_criteria.md`（判据 + 变异表）。
