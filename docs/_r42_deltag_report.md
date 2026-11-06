# R42 · 朋友圈「已删除」灰标（line 样式）实现报告 —— deltag2

> 作者：deltag2（wxgroup 实现子代理）
> 日期：2026-11-06
> 范围：在既有三层「写时拦截」（甲 / A / B）**旁边**新增「已删除」灰标记录与渲染，不改既有拦截行为。

## 大纲 / 进度

- [x] [DONE-1] 读资产、定落点（落点与理由）
- [x] [DONE-2] 记录点 + 表结构 + 迁移
- [x] [DONE-3] self wxid 获取与降级
- [x] [DONE-4] 渲染 + 探针
- [x] [DONE-5] 单测 + 变异
- [x] [DONE-6] 构建通过

---

## [DONE-1] 设计决策

**核心前提**：三层写时拦截（甲 `updateWithOnConflict` before 摘 `sourceType==0`；A 纯降位丢 bit1/bit3 摘键；B `execSQL` `& -9` → `-1`）已真机验证，全部保留。拦截后微信自己不知道删除失败，库里**没有**「已删除」现成信号（实测 `localFlag` 删除前后都是 `2`）。⇒ 灰标必须**模块自己记**，在「真摘键且丢时间线位」那一刻落台账。

**决策清单**：

1. **只盯 bit1，不盯 bit3**。`sourceType` 位语义：`bit1(2)` 时间线可见、`bit3(8)` 好友主页相册、`bit2(4)` 私密、`bit5(32)` 广告、`bit7(128)` 折叠。「删除」在时间线上的表现是微信把 bit1 清掉（`10→8` / `2→0`）。`10→2` 只丢相册位（bit3）、时间线仍可见 ⇒ **不算删除**，不落台账 —— 否则进好友主页触发 `& -9` 会被误判成删除。
2. **只实现 line 样式**。`inline` 不做；`off` 是关闭对照。
3. **不推翻三层拦截**：新增代码全部挂在拦截「旁边」，不改 `SnsGuard` 的既有 `Outcome` 语义，只在 `Outcome.BLOCKED` 后追加一行 `onBlocked` 调用。
4. **落点复用已验证链**：`WxRecyclerAdapter.onBindViewHolder` + `getData()` 映射链（沿用 SnsTag 旧版，见 `docs/conclusions/moments.md` §4/§5），只借「落点与绘制手法」，不搬归档/恢复/响应注入。

## [DONE-2] 记录点 + 表结构 + 迁移

**记录点**：`SnsGuard.applyBlock` 在 `Outcome.BLOCKED` 后追加一行（唯一接线，不改拦截逻辑）：

```kotlin
SnsDeletedTag.onBlocked(id, bits, newValue ?: 0)
```

`onBlocked` 判据走纯函数 `SnsDeletedTagLogic.shouldRecord(old, new, author, self)`：
- 旧值含 bit1 且这次写不含 bit1（`isTimelineLoss`）
- 且作者不是自己（`!isSelf`）
- 同时成立 ⇒ `db.recordSnsDeleted(snsId)` + `gen.incrementAndGet()`

**表结构**（模块自己的库，AOSP SQLite，**不走 wcdb**，读写不触发挂在微信库上的钩子）：

```sql
CREATE TABLE IF NOT EXISTS sns_deleted (
    sns_id     INTEGER PRIMARY KEY,
    created_at INTEGER NOT NULL
)
```

- `sns_id INTEGER PRIMARY KEY` + `insertWithOnConflict(..., CONFLICT_IGNORE)`：同一 snsId 只保留一条、首记录为准（保留首次时间戳）。
- 记录时 `snsId` 用**有符号 Long**（与 SQLite 里 `SnsInfo` 位模式一致）；渲染/探针对外展示时用 `Long.toUnsignedString` 换回无符号十进制（`ImproveInfo.snsID` 的写法）。

**迁移**：`DB_VERSION` 9 → 10，`onUpgrade` 加幂等块：

```kotlin
if (oldVersion < 10) {
    createSnsDeletedTable(db)  // CREATE TABLE IF NOT EXISTS
}
```

`onCreate` 末尾同样建表。幂等，不破坏既有表。

**作者取用**（A 层 `ContentValues` 整行 upsert 确认项）：采用独立点查方案 —— `SnsBlockProbe.Bits` 增加第 5 字段 `userName`，`readBits` 的 SQL 加 `userName` 列（`select sourceType,localFlag,serverExtFlag,createTime,userName from SnsInfo where snsId=?`）。语义等价于从 `ContentValues` 键集拿作者，且零成本（本来就是同一次点查）。

## [DONE-3] self wxid 获取与降级

**三段式**（主路径 → 候选 → 降级绝不静默）：

1. **主路径**（标准 Android API，不碰未取证微信类名）：
   `context.getSharedPreferences("com.tencent.mm_preferences", MODE_PRIVATE).getString("login_weixin_username")`
2. **候选列表**（按可靠性排序，取第一个非空）：
   - `com.tencent.mm_preferences / login_weixin_username`
   - `com.tencent.mm_preferences / login_user_name`
   - `system_config_prefs / login_weixin_username`
   - `com.tencent.mm / login_weixin_username`
3. **降级**：全部取不到 ⇒ `self = null` ⇒ `isSelf` 恒 false ⇒ **照记不误**（不排除任何作者），但 `sub=stat` 输出 `self=? (未知，未排除自己)`，并 `LogX.w("SNSDEL: self wxid unknown (未排除自己) …")`。宁可明说失效，不让用户以为「排除自己」生效了。

取到后 `self` 缓存到 `@Volatile` 字段，`selfSource` 记下来源文件/键，`sub=who` 探针可诊断。

## [DONE-4] 渲染 + 探针

**挂钩**：`install(lpparam)` 挂 `WxRecyclerAdapter.onBindViewHolder(holder, pos)`（基类 + 子类覆写补偿：`WxRecyclerView.setAdapter` 抓具体 adapter 类再补挂），`SNS_PREFIX` 只认朋友圈 adapter。

**line 插入方式（复用清理）**：
- `holder → itemView` 排除 RecyclerView（优先选 `androidx.recyclerview.widget.*` 声明的 View 字段）。
- `resolve(adapter, pos, view)`：`getData()` 拿列表，作者名在 ±2 窗口**唯一命中**才采信（防封面头错绑）；`textsOf` 跳过自己的灰标节点（`MARK_MAGIC`）避免污染语料。
- `ensureMark(view)`：**先 `removeMark` 移除上一次加的标记**，再 `root.addView(tv, root.childCount, lp)` 追加到 itemView 根 ViewGroup 末尾 —— 满足「正文下方单独一行」「纯图片动态仍在图片上方（图片之后）」，⛔ 不在 RecyclerView 层面操作。
- 视觉规格：文案「该动态已被作者删除」、颜色 `0xFF9B9B9B`、字号 12sp（正文约 15sp，小 3sp）、`MARK_TOP_DP=5`、`MARK_LEFT_DP=56`（约头像宽+间距，真机可微调）。标记 View 用 `View.generateViewId()` 作 tag key + `MARK_MAGIC` 作 value，进程内唯一、与微信 tag 不冲突。

**缓存策略（照抄 VoiceGuard 模式）**：
- 三条件命中（`SnsDeletedTagLogic.cacheUsable`）：`cachedSize > 0`（空集永不算命中，避免吃掉紧随其后的新记录）&& `cachedGen == gen`（构建后有 record/purge 则失效）&& `now - cachedAt < TTL`（兜底）。
- `stampAfterBuild`：空集记成「立即过期」（`now - ttl`），下次必然重查。
- `gen`（`AtomicLong`）在 `recordSnsDeleted` 与 `purge` 时自增。`SET_TTL_MS = 5_000`。

**探针**（`EntryReceiver` 的 `snsdel` 分支分派，`sub` 默认 `stat`；⛔ 不包 `io{}`，style/purge 要 `resweep` 必须主线程）：
- `op=snsdel sub=style --es val line|off`：切样式 + `resweep()` 即时重绘。
- `op=snsdel sub=stat`：`style / rows / self / src / recent / bound / binds / sns / resolved / missed / hits / lastMiss`；self 为空输出 `? (未知，未排除自己)`。
- `op=snsdel sub=who`：self 来源诊断（装包后一条命令确认取没取到）。
- `op=snsdel sub=purge`：清空台账（便于重测）+ `resweep` 摘掉当前屏幕标记。

## [DONE-5] 单测 + 变异

纯判据全部抽到 `SnsDeletedTagLogic.kt`（零 Android/Xposed 依赖），`SnsDeletedTagLogicTest.kt` **25 用例**覆盖：样式解析（5）、丢时间线位（4）、排除自己（2）、落台账判据（4）、缓存失效（2）、snsId 符号互转（3）、`parseInfo`（3）、作者名对账（2）。

**变异验证**（改坏一处看用例变红，均先确认有语义）：

| 变异 | 改坏方式 | 变红用例数 |
|------|----------|-----------|
| `isTimelineLoss` | 去掉 `new` 丢位判断（只要 old 含 bit1 就 true） | 3 |
| `isSelf` | 去掉 `author == self` | 2 |
| `cacheUsable` | 去掉 `cachedGen == gen` | 1 |
| `parseStyle` | 恒返回 `LINE` | 1 |
| `parseStyle` | `v.isNullOrBlank() -> LINE` 改成 `-> OFF`（默认值回归） | 1 |

五处均为**有语义变异**，无等价变异假验证。

## [DONE-6] 构建通过

- `SnsDeletedTagLogicTest`：**25 例全绿**。
- 全量 `testDebugUnitTest`：**771 例 0 失败**（含 avatar 子代理新增的 3 例 + 本任务新增 2 例 + 布尔语义收尾新增 1 例，基线 765 未破坏）。
- `./gradlew assembleDebug`：**BUILD SUCCESSFUL**（⛔ 未跑 `build`）。

---

## 复核修正（team-lead 2026-11-06 复核）

复核发现 3 个问题，已全部修复：

### 1（致命）样式默认值是 OFF，装包后灰标永不显示

- **根因**：`Keys.SNSDEL_STYLE` 只加了常量、未登记 `PrefSpec`，`Prefs.defaultOf` 走 `byKey[key]?.def ?: ""` 返回空串；而旧 `parseStyle` 空串 ⇒ `OFF` ⇒ `refresh()` 里 style=OFF ⇒ 一条灰标都不渲染。
- **修法（双兜底）**：
  - `Keys.kt`：登记 PrefSpec，`def = "line"`，`PrefKind.TEXT`（`def="line"` 不是 true/false，不能用 SWITCH，否则 `Kv.getBool` 把 `"line"` 当 false），域「防撤回」，文案「已删除灰标」。
  - `SnsDeletedTagLogic.parseStyle`：改成「`v.isNullOrBlank()` ⇒ LINE；显式 `off` ⇒ OFF」，默认开。
- **key 名调整**：`snsdel.style` → `antirevoke.sns_deleted_tag`。原因：`PrefsVisibilityTest.keyPrefixMatchesDomainGrouping` 强制「key 前缀必须映射到 domain」，`snsdel` 前缀不在已知映射表（`antirevoke`→防撤回 / `sns`→朋友圈）。既然按 team-lead 要求归「防撤回」域，前缀必须换成 `antirevoke`，否则该测试红。`op=snsdel` 的探针名不受影响（op 名与持久化 key 独立）。

### 2 单测覆盖默认值回归

- `SnsDeletedTagLogicTest` 样式解析从 2 用例扩到 4 用例：新增 `style_defaults_to_line_when_unset_or_blank`（`parseStyle(null)`=LINE、`parseStyle("")`=LINE、`parseStyle("   ")`=LINE）、`style_off_is_explicit_off`（`off`/`OFF`=OFF）。
- 变异验证新增第 5 处：`v.isNullOrBlank() -> LINE` 改成 `-> OFF` ⇒ `style_defaults_to_line_when_unset_or_blank` 1 条变红。

### 3 `onBlocked` 在微信写库线程做同步磁盘 IO

- **根因**：`SnsGuard.applyBlock` 在 `updateWithOnConflict` 的 **before** 钩子里同步调用 `onBlocked`，直接 `db.recordSnsDeleted(snsId)` = 在微信写库调用栈里同步落盘。
- **修法**：`SnsDeletedTag` 加单线程后台 executor（daemon，命名 `wxgroup-snsdel`）。判定仍在调用线程做完（`shouldRecord` 纯函数 + `selfWxid` 有缓存），只把 `recordSnsDeleted` + `gen.incrementAndGet()` + 日志整段 post 到后台线程。`gen` 自增保持在写库成功之后、同一条后台任务里，不挪前。

---

## 复核修正（team-lead 第二轮，2026-11-06）

复核通过异步写库的 `gen` 位置正确（写库成功后才自增、同一条后台任务），770 例独立复跑 EXIT=0。剩两处收尾：

### 4 `selfWxid()` 的 SharedPreferences 首读仍在微信写库线程

- **根因**：`onBlocked` 第一行仍是调用线程里的 `selfWxid()`，主路径走 `ctx.getSharedPreferences(...).getString(...)`，**首次调用会同步加载整个 xml**；self 有缓存所以只痛第一次，但第一次恰好就是最坏情况 —— 它发生在 `updateWithOnConflict` 的 before 钩子栈里（微信写库线程）。
- **修法**：`install(lpparam)` 里主动调一次 `selfWxid()` 预热，把这笔 IO 挪到模块加载线程（与 `GroupDb.open()` 同一时机等级），而不是用户某次删除动态的瞬间。加注释说明「预热是为了不让首读落在微信写库线程上」。

### 5 `PrefKind.TEXT` 不合适，改成 `SWITCH`

- **根因**：`PrefKind` 没有下拉，用 `TEXT` 会渲染成多行文本框，用户得手输 `line`/`off` 才能改状态，不像能用的开关。
- **修法（布尔语义）**：
  - `PrefSpec(Keys.SNSDEL_STYLE, PrefKind.SWITCH, "true", "防撤回", "已删除灰标")`。
  - `parseStyle` 改吃布尔串：`"true"` / `"1"`（去空白、忽略大小写）⇒ LINE；其余 ⇒ OFF；**空 / null 仍 ⇒ LINE**（保留上一轮双兜底，不退化）。
  - 探针 `op=snsdel sub=style` 取值改 `on|off`，持久化写 `"true"` / `"false"`；**同时兼容传 `line`**（等同 `on`），避免旧写法误关。
- **单测**：样式解析 4 用例 → 5 用例。覆盖：`null` ⇒ LINE、空 ⇒ LINE、`"true"` ⇒ LINE、`"1"` ⇒ LINE、`"line"` ⇒ LINE（兼容）、`"on"` ⇒ LINE（探针取值）、`"false"` ⇒ OFF、`"off"` ⇒ OFF。
- **变异第 5 处不变**：`isNullOrBlank() -> LINE` 改成 `-> OFF` 仍由 `style_defaults_to_line_when_unset_or_blank` 1 条变红。

## 最终构建 / 单测（第二轮收尾后）

- `assembleDebug`：**BUILD SUCCESSFUL**（EXIT=0）。
- `testDebugUnitTest --rerun-tasks`：**771 例 / 0 失败 / 0 错误 / 0 跳过**（基线 770 ⇒ +1；`SnsDeletedTagLogicTest` 24 ⇒ 25 例）。

---

## 真机第一轮：hits 有值但零渲染（2026-11-06 装包采集后）

### 现场

```
SNSDEL stat style=line rows=4 self=wxid_myzsbcur81xr21 src=com.tencent.mm_preferences/login_weixin_username
bound=0 binds=107 sns=103 resolved=82 missed=21 hits=8 lastMiss=ambig2(pos=1 texts=[verity|3])
```

- `rows=4` / `binds=107 sns=103` ⇒ 记录链路和钩子都活着，台账有数据；
- `hits=8` ⇒ 有 8 次判定了「该显示灰标」；
- **但 uiautomator dump 的 View 树里找不到「该动态已被作者删除」节点** ⇒ 一个都没真正加上。

### 根因

`itemViewOf()` 兜底链最后一级是 `?: cands.firstOrNull()?.second`——任意 View 都收。微信的 ViewHolder 若不自带 androidx 声明的 itemView 字段、且 bind 时 itemView 尚未 attach 到 RecyclerView（`parent` 为 null），前两级不命中就会落到「任意 View」级；一旦取到 `ImageView` 之类非 `ViewGroup`，`ensureMark` 里 `view as? ViewGroup ?: return` 静默返回，而 `hits++` 在 `applyMark` 分支里已加过 ⇒ 计数看着正常、实际零生效。

### 四处修法（只改 `SnsDeletedTag.kt` + 纯函数/单测）

1. **兜底不许选非 ViewGroup**：`itemViewOf` 的筛选链抽成纯函数 `SnsDeletedTagLogic.pickItemViewIndex`，在「面积最大 ViewGroup」之后加「任意 ViewGroup」一级，最后才退到任意 View。
2. **计数区分「匹配」与「真加上」**：`hits` 语义改回「真正 addView 成功的次数」（在 `ensureMark` 里 `addView` 成功后 `hits++`）；新增 `match` 计数（判定应显示的次数），两者一起进 `stat()`。`ensureMark` 遇到非 `ViewGroup` 时限流打一条 `LogX.w`（含 `view.javaClass.simpleName`），一眼看出「又选错 View」。
3. **插入位置改到正文下方**：新增纯函数 `SnsDeletedTagLogic.bodyTextPath`——取 itemView 根的第一个 `ViewGroup` 子节点作内容容器（昵称是根的第 0 个裸 TextView，被自然跳过），DFS 找第一个非空文本节点，在它的父容器里 `index+1` 处紧跟插入；找不到则退回根末尾（限流日志说明走了兜底）。`removeMark` 相应改成**递归**整棵子树（标记不再只在根的直接子节点）。⛔ 不改正文 TextView 自身的 text/color/大小。
4. **`markLayoutParams` 按实际插入目标父容器分派**：新增 `RelativeLayout` 分支，用 `RelativeLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)` + `setMargins(left, top, 0, 0)`（`RelativeLayout` 上 `leftMargin/topMargin` 不生效）。

### 下次装机要看哪几个数（`op=snsdel sub=stat` + `LogX`）

| 现象 | 结论 / 下一步 |
|------|--------------|
| `match>0` 且 `hits==match` 且 dump 里有节点 | ✅ 修复生效 |
| `match>0` 但 `hits==0` | `ensureMark` 又选错 View → 看日志 `ensureMark got <类型> (not ViewGroup)` |
| `match==hits>0` 但 dump 里仍没节点 | 位置/布局问题 → 看日志 `body text not found ... fallback to root end`（插入位置没找到正文）或 RelativeLayout 边距没生效 |
| `match==0` | 判定层问题（`deleted()` 没命中，看 `rows`/`lastMiss`） |

关键就是 **`match` 和 `hits` 的差量**：差量>0 说明「判定该显示但没加上」，配合限流日志能直接定位卡在哪一层。

---

## 未验证项（需真机）

1. **灰标视觉微调**：`MARK_LEFT_DP=56`、`MARK_TOP_DP=5`、字号 12sp 是基于「正文约 15sp」的估算，真机需按实际正文缩进/间距微调。
2. **`MARK_COLOR` 固定值**：`0xFF9B9B9B` 未按主题（深色模式）动态取色，深色主题下可能偏暗。
3. **`self` 主路径 key**：`login_weixin_username` 是主候选，若目标微信版本（8.0.48/2588）实际存的是别的键，`sub=who` 会显示 `src=?`，届时按 `sub=who` 诊断结果补候选。
4. **holder→itemView 字段选择**：`itemViewOf` 用「优先 androidx ViewHolder 字段 → 父链含 RecyclerView → 面积最大 ViewGroup」的启发式，真机不同朋友圈 item 类型（纯文本 / 图片 / 视频 / 分享链接）需验证选中的确实是 item 根而非子 View。
5. **`getData()` 映射链**：作者名 ±2 窗口唯一对账依赖 `ImproveInfo.name` 与 item 可见文字能对上，特殊昵称（纯 emoji / 极短）可能 `missed` 上涨，属预期降级（打错标签比不打标签糟糕）。
6. **好友主页共用 adapter 的落地**：任务要求「时间线 + 好友主页都标」，两者共用 `WxRecyclerAdapter` 时钩子天然覆盖；若好友主页是另一 adapter 类（不在 `SNS_PREFIX` 下），需按 `sub=stat` 的 `binds/sns` 计数判断是否命中，必要时在 `SNS_PREFIX` 补类名。

---

## 真机第二轮：ambig 导致 match 恒为 0

### 现场（装包后进朋友圈，`op=snsdel sub=stat`）

```
SNSDEL stat style=line rows=4 self=wxid_myzsbcur81xr21
  recent=[15048981949812773393@1793978489746, 15048980995856470552@1793978339411,
          15048919014479827481@1793978296987, 15048940383625351696@1793978294523]
  bound=1 binds=12 sns=8 resolved=2 missed=6 match=0 hits=0
  lastMiss=ambig2(pos=2 texts=[verity|2])
```

- `sns=8` ⇒ 钩子在朋友圈 adapter 上正常触发；
- **`resolved=2 missed=6`** ⇒ 8 次里只有 2 次解析出 snsId；
- **`match=0`** ⇒ 连解析出来的那 2 条也没命中「已删除」清单（且没真加上）；
- `lastMiss=ambig2(...)` ⇒ 最常见失败原因是**候选不唯一**。

### 根因

`resolve()` 旧逻辑是「收集 ±2 窗口里所有通过 `nameOk` 护栏的候选，只有 `hits.size == 1` 才采用，否则报 `ambigN` 放弃」。而 `nameOk` 是「界面文本里包含作者名」的宽松匹配 —— **同一个人在同屏出现两条动态时，`pos+0` 和 `pos+1` 的作者名都是同一个人 ⇒ 两个候选都通过 ⇒ `ambig2` ⇒ 直接放弃**。这就是 `resolved` 只有 2/8、`match` 恒为 0 的直接原因。

### 修法（只改 `SnsDeletedTag.kt` / `SnsDeletedTagLogic.kt` / 单测）

1. **按优先级取第一个匹配，不再要求唯一**：把「收集全部再判唯一」改成「按 `0, 1, -1, 2, -2` 顺序取第一个通过 `nameOk` 护栏的」。`0` 偏移最可信（先试直接对应），只有当 pos 与 dataList 错位时才退到 ±1、±2。同一个人两条动态时 `pos+0` 被选中，不再 ambig。
2. **护栏保留**：`nameOk` 仍是防错标的的护栏（去掉会标到别人的动态），只是从「唯一性判据」降为「逐候选过滤」。
3. **`offsets` 缓存保持「先验后验」**：缓存命中时优先用缓存 `d`，但仍过 `nameOk` 护栏；护栏不过就 `offsets.remove(key)` 后走完整循环。命中非零偏移才缓存（0 偏移是默认优先项，不必缓存）。
4. **纯函数化**：抽 `SnsDeletedTagLogic.pickOffset(candidates: List<Pair<Int, Boolean>>): Int?` —— 输入「偏移 → 是否通过护栏」，按传入顺序返回第一个 `true` 的偏移，全 false ⇒ null。单测锁：全 false ⇒ null；首个 true 返回；`0` 与 `1` 都为 true 时返回 `0`（防优先级写反）；`-1` 在 `2` 之前（防排序写反）。

### 语义保障

`match`（判定「应显示灰标」的次数）与 `hits`（真正 `addView` 成功的次数）的计数语义**未动**，仍是下一轮判断「解析修好之后渲染是否也通过」的唯一依据。

### 构建与单测

- `assembleDebug`：BUILD SUCCESSFUL；
- `testDebugUnitTest --rerun-tasks`：BUILD SUCCESSFUL，全量 785 用例通过（`SnsDeletedTagLogicTest` 39 例，新增 4 例 `pickOffset`）。

### 下次装机要看哪几个数

`resolved` 应显著上升（8 次里应接近 8），`lastMiss` 不再出现 `ambig`；`match`/`hits` 是否 >0 则取决于这 4 条台账是否真的落在当前屏可见列表上。

---

## 真机验收（2026-11-06 23:50 → 2026-11-07 00:15）· deltag3

三轮装机才收敛，每轮都是「上一个盲点被前一个修复暴露出来」。**结论：灰标已能正确渲染**，
截图见 `_r42_deltag_shot.png`。

### 第 1 轮：`bind err: IllegalArgumentException`（渲染段第一次跑起来就崩）

- 现象：`SNSDEL holder=m0 pick=FrameLayout` 后紧跟 `SNSDEL bind err: IllegalArgumentException`，
  屏幕上一条灰标都没有。
- 根因：**`MARK_KEY = View.generateViewId()` 用错了 API**。
  `View.setTag(int, Object)` 第一行就是
  `if ((key >>> 24) < 2) throw new IllegalArgumentException("The key must be an application-specific resource id.")`；
  而 `generateViewId()` 只产出 `1..0xFFFFFF`（高字节 0）⇒ 必然抛。
- 取证方式（不是推理）：异常类名 + AOSP 契约逐字吻合；且**改完立刻不抛了**（第 2 轮日志无 bind err）。
- 修法：新增 `res/values/ids.xml`，声明 `<item name="wxgroup_sns_mark" type="id"/>`，
  `MARK_KEY = R.id.wxgroup_sns_mark`。构建产物 `runtime_symbol_list/R.txt` 实测 `0x7f020000`
  ⇒ `>>> 24 = 0x7f ≥ 2`，契约满足（**核的是生成物，不是"应该"**）。
- 顺带加固两处：
  1. `setTag` 与 `addView` 放进**同一个** `runCatching`——tag 失败时 View 既插不进也删不掉，宁可整条不插；
  2. `bind err` 日志补**首个栈帧 + message**（这次只报类名，靠反推花了半小时）。

### 第 2 轮：位置不对（分隔线之后 ≈ 挂在下一头上）

- 现象：`body text not found in f3, fallback to root end` → 灰字确实出现了，但落在
  **item 卡片外**（下一条动态上方 8px），视觉上像挂在别人头上。截图存档前的对照见下。
- 取证：`op=snsv sub=tree` 打 item 根的真实子节点顺序
  `[ImproveItemHeader, 内容 RelativeLayout(1440×652), View(1440×1 分隔线)]`
  ⇒ 按 `childCount` 插即落在分隔线**之后**。
- 修法：新增 `tailIndex(root)`，末尾那条 `android.view.View` 且高度 ≤ 2dp 就插到它**之前**。
- 同轮修的缩进：`MARK_LEFT_DP 56 → 72`（真机 1440×3200 / density 3.5，正文与昵称左边界在
  x=251px ⇒ 251/3.5≈71.7dp）。

### 第 3 轮：验收数据

- 计数：`binds=27 sns=23 resolved=19 missed=4 match=4 hits=4`，**`match - hits = 0`**
  （每条判定要显示的都真加到了视图树上）。
- 4 次 `missed` 全是 `empty(0)` / `empty(1)`（adapter 数据尚未就绪的瞬时态），已加 `miss#N` 限流日志
  把「瞬时没数据」与「解析不出 snsId」区分开，不再是只留一条 `lastMiss` 的黑盒。
- 界面证据：`uiautomator dump` 在同一次滚动里命中
  `text="该动态已被作者删除" bounds="[259,2349][1440,2407]"`，上方 `49分钟前`（2324 结束）、
  下方下一条 `verity`（2445 起）⇒ **上距 25px、下距 38px**，明确归属上一条。
- 自删排除仍在：`self=wxid_myzsbcur81xr21`，台账 4 条的作者是好友 `wxid_xlrbelf6hv0w22`
  （`SNSInfo` 的 `userName` 实测），自己删自己的动态不会落台账。

### 仍未做

- `bodyTextPath` 在真机上对 item 根始终返回 null（正文在更深层的 ConstraintLayout 里），
  现在**全靠 `tailIndex` 兜底**。视觉已达标，但插入点是「卡片末尾」而不是「正文下方」；
  若以后微信改了 item 根的子节点顺序，兜底会退化回「挂在下一头上」。
- 主页可见性缺口（`sourceType` 停在 2、无 bit3 ⇒ 好友主页相册查不到）未动，等拍板。

---

## 好友主页相册（SnsUserUI）为什么没有灰标 —— 取证与决策（2026-11-07 00:30）

**用户决策：主页相册不标灰**（只在时间线标）。以下取证留档，避免下次重新挖。

### 结论：那不是「没渲染」，是钩子压根不触发

真机实测：进出好友主页相册，`SNSDEL` 的 `binds` **纹丝不动**（35 → 35，零增量）。
即 `WxRecyclerAdapter.bindViewHolder` 在该页一次都不走 —— 那页根本不是 RecyclerView。

### 那页的真实结构（`op=snsdel sub=home` + `op=snsv sub=tree` 实测）

- Activity：`com.tencent.mm.plugin.sns.ui.SnsUserUI`
- 列表：`MMOverScrollView` 里的 **`android.widget.ListView`**
- adapter：`android.widget.HeaderViewListAdapter`（壳），被包装的真身是
  **`com.tencent.mm.plugin.sns.ui.zl`**（可由 `HeaderViewListAdapter.mAdapter` 反射取到）
- item：`com.tencent.mm.plugin.sns.storage.SnsInfo`，**每行一条动态**，`getSnsId()` 可取 id
  （`item[0]` 是 null，封面 header 行）
- 行布局：`LinearLayout(1382×316)`
  = 左日期列 `LinearLayout(245×92)`（`ImproveTextView txt="昨天"`）
  + 右内容列 `MaskLinearLayout(1079×316)`，**内部横向**排布
  `FrameLayout(316×316 缩略图)` + `RelativeLayout(727×316)` → `MMNeat7extView(727×79 文本预览)`

### 为什么没有照搬时间线的插法

时间线是「卡片末尾插一行」（纵向容器 + 尾部 1px 分隔线，`tailIndex()` 用得上）；
相册页右列是**横向**的，往末尾插会横着溢出到屏外；左日期列只有 245px 宽，长句折行会顶高整行；
而行高被缩略图钉在 316px。⇒ 若要标，只能改成「缩略图盖灰条」这类**叠加**形态，
与时间线的灰字是两种视觉。用户选择统一不做，故本页到此为止。

### 副产品

- `op=snsdel sub=home` 探针保留（只读）：打印当前页 ListView/adapter/包装 adapter/前 3 个 item
  的类名与 `getSnsId()` 取值，下次这页再出问题一条命令即可定位。
- `SnsViewProbe.MAX_DEPTH` 26 → 44：相册页的行结构在第 26 层之下，26 层时整段 dump 被截断。

---

## 真机误报：新动态被挂「已删除」灰标（2026-11-07 01:58，待修）

bit3 补位装机并正向验收 PASS（时间线在、主页在、`src=2→10` 稳定）之后，
用户让 **verity** 再**新发一条动态**（未被删除），该动态也出现了灰标。

### 用户可见事实（UI dump，`wxgroup/_cache/ui_fp.xml`）

页面 `ImproveSnsTimelineUI`，按 bounds 的 y 排序：

| y | text | 判定 |
|---|---|---|
| 665 | `verity` | 第一条动态作者 |
| 1659 | `1分钟前` | **刚新发、未被删除** |
| 1742 | `该动态已被作者删除` | ❌ **误报** |
| 1838 | `verity` | 第二条动态作者 |
| 2832 | `8分钟前` | 01:48 那条真·被删动态 |
| 2915 | `该动态已被作者删除` | ✅ 正确 |
| 3011 | `万华化学—李思远pc` | 第三条（另一好友） |

⇒ 两条**相邻**动态同属 verity，一真一假都挂灰标。

### 记录层已排除（对着用户能看见的事实写）

- `SNSDEL stat rows=5`、`recent` 首条仍为 `15049056316837204499@1793987461147`
  （= 01:51:01 那条真·被删动态）⇒ **台账没新增，新动态不在 `sns_deleted` 表**。
- `BLOCK` 事件自 01:54:41 起零新增 ⇒ 新动态没触发拦截、`onBlocked` 没被调用。
- 但 `match/hits` 由 `1/1`（01:53:21）涨到 `7/7`（01:58:33），
  `binds 19→39 / sns 15→35 / resolved 11→27` ⇒ 疑点收敛在**渲染层**。

### 根因定案（`tagfix` 一手取证，链 2 已排除）

**链 1 唯一**：封面头造成 `pos` 与 `getData()` 下标的全局固定错位；为修 ambig2
曾把 `resolve()` 改成按 `0,1,-1,2,-2` 取第一个通过 `nameOk` 的候选，同一好友
相邻两条动态时作者名护栏失去区分力 ⇒ `d=0` 假命中相邻真·被删动态的 snsId ⇒
新动态误挂。旧 `offsets` 缓存 key 含 `list.size`，新发一条即使其失效，放大串位。

**链 2（View 复用残留）不是成因**，三条反证（原始 XML 逐 node 解析 73 个节点）：
1. 两条灰标左边界都是 `x=259`，与 `MARK_LEFT_DP=72` 同款 ⇒ 都是本模块
   `ensureMark` 主动 `addView` 插的，不是微信原生文字；
2. 两条动态**同时在屏**、各带一个标（正文分别是 `1` / `2`）⇒ 是两个不同
   itemView、两条不同数据，不是同一个 View 被先后复用；
3. 复用残留不会产生新的 `match/hits`，而真机 `match=hits` 同步 `1→7`
   （+6 次主动插标）⇒ 只能是这次绑定把新动态判成了「在台账里」。
   且负路径本来就有 3 处 `removeMark`（`onBind` info==null / `applyMark` else /
   `ensureMark` 开头），修复只是把它用 `markDecision` 纯判据锁死。

### 修复后的验收基线

按 UI dump 的 y 坐标逐条比对（可直接量化，不靠肉眼）：

- `y=1742`「该动态已被作者删除」（`1分钟前` 新动态下方）⇒ **应消失**；
- `y=2915`「该动态已被作者删除」（`8分钟前` 真被删下方）⇒ **应保留**；
- 上下滚动 / ViewHolder 复用不新增、不串位、不残留，重复绑定同一条不重复插标；
- ⭐ **取样姿势：冷启后不滚动，直接 dump 首屏**。先滚动会把 B1 类问题（首屏歧义降级）
  掩盖掉，测出来的「正常」不可信；
- `SNSDEL stat` 首屏出现 `lastMiss=ambig(...)` 需**重点复核**（可能是降级漏标），
  不能一律当「预期降级」放过；
- 装包后若看到「灰标偶发闪失后自动恢复」属预期：`d==0` 不缓存会让 `offsets` 回 null，
  下一次校准触发 resweep 补回，不是新缺陷。

⚠️ **装包前待决疑点**：`anchorOffset` 在「未校准 + 同名相邻窗口歧义」返回 `null`
⇒ 保守不打标。冷启首次可见顺序为 `verity 新动态 / verity 8分钟前真被删 /
万华化学—李思远pc`，而错位常数的校准只在逐条绑定时发生（第三条要滚到才绑定）。
⇒ **一进页面未滚动时 `y=2915` 会不会因降级而消失**，须由 `tagreview` 独立复核
明确判定（可接受降级 / 阻塞项 + 最小缓解建议），裁决前不装包。

⛔ 用户明确「先不做反向对照」，`antirevoke.sns_album_fix_off` 本轮不碰。
