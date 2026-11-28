# R89：加入分组后主页面隐藏该会话，仅进入分组可见 — 方案（待用户按 D2 选路线）

> 状态：**仅方案**。未改 `app/src/main`，未装机，未提交。
> 档位：Pro（会话列表过滤判据 + Hook 链路推导 + 误伤边界裁决）。
> `ghide-pro` 已 402，已按三件套在 `docs/派发审计台账.md` 登记为主会话自审。
> 本轮依据 D2「路线对照表前置」先出表并请用户选路线；未选路线前不落码。

---

## 1. 诉求与判定边界

用户诉求：

> `wxgroup加入分组后，在主页面上不显示会话，只有进入分组才能看到，实现该功能`

落到当前项目语境：

1. **主页面** = 微信会话列表 `ConversationListView`，当前 gconv=1 主路径。
2. **已归组会话** = `group_member.wxid` 中出现的真实会话 wxid（好友 / 群均可）。
3. **进入分组可见** = 点击 `wxgrp_<hex>` 伪会话行进入 `GconvPage`，该页用组内成员 wxid 独立查 `rconversation`。
4. 隐藏的是**显示行**，不是微信数据库里的会话 / 消息。库里行必须原样存在，未读、消息、聊天记录都不能动。

---

## 2. 现状链路（源码实证）

### 2.1 主页面 gconv=1 当前是 passthrough

`wxgroup/app/src/main/java/com/lx/wxgroup/feature/ConvFilter.kt:1313-1336`：

```kotlin
layout = when {
    db == null || !enabled -> {
        ConvLayout.build(snaps, emptyList(), emptyMap(), enabled = false)
    }
    filterId > 0 -> {
        ConvLayout.filtered(snaps, cachedMembers)
    }
    else -> {
        if (groups.isEmpty() || gconvEnabled) {
            ConvLayout.build(snaps, emptyList(), emptyMap(), enabled = false)
        } else {
            // gconv=0 自绘分组行路径
        }
    }
}
```

`ConvLayout.build(..., enabled=false)` 最终走 `passthrough()`：

```kotlin
private fun passthrough(convs: List<ConvEntry>): List<ConvRow> =
    convs.indices.map { ConvRow.Conv(it) }
```

⇒ gconv=1 主页面真实会话全部保留。这正是 R89 要改的那一行。

### 2.2 显示行由 `layout` 决定，不是由 `snaps` 决定

`ConvFilter.kt:1723-1729`：

```kotlin
override fun getCount(): Int = layout.size
override fun getItem(position: Int): Any? =
    runCatching { wrapped.getItem(position) }.getOrNull()
```

`ConvLayout.Layout.convIndexOf(position)` 把显示位置换回微信原始下标。

⇒ **可以在 layout 层删掉某些显示行，同时保留原始下标映射**；不能把 `snaps` 本身过滤成短表，否则 `getItem(position)` 仍打微信原始 adapter，会造成「点错人」。

### 2.3 分组内视图是另一条链，不会被主页面过滤影响

gconv=1 时点击 `wxgrp_` 伪会话行由 `ConvFilter.kt:1834` 调：

```kotlin
v.setOnClickListener { GconvPage.open(v.context, gid) }
```

`GconvPage` 在 `onActivityCreated` 认领 gid / 成员 / 组名，在 `t0.N()` 与 `w8.W(Cursor)` 两处把 adapter 游标换成：

```kotlin
private fun selectSql(members: List<String>): String =
    "select * from rconversation where ${whereClause(members)} order by flag desc, conversationTime desc"
```

`whereClause` 明确排除 `wxgrp_`：

```kotlin
sb.append(") and substr(username,1,6)<>'wxgrp_'")
```

⇒ **「进入分组仍能看到成员会话」由 GconvPage 独立保证**；主页面 layout 过滤不会碰到它。

### 2.4 成员全集现成可用

`GroupDb.kt:696-703`：

```kotlin
fun allMemberWxids(): Set<String> {
    readableDatabase.query(T_MEMBER, arrayOf("wxid"), null, null, null, null, null)
        .use { cursor ->
            val out = HashSet<String>()
            while (cursor.moveToNext()) out.add(cursor.getString(0))
            return out
        }
}
```

一次查询即可拿到所有已归组 wxid，无需新增 SQL / schema。

### 2.5 gconv=0 已经符合诉求

`ConvLayout.build(enabled=true)` 的循环里，真实成员会话被隐藏，分组行取代最新一条成员会话的位置：

```kotlin
GroupConvPlan.isOurs(wxid) -> Unit        // 伪会话必须隐藏，否则出现两套分组行
owner == null -> rows.add(ConvRow.Conv(i))
anchorOf[owner] == i -> rows.add(ConvRow.Group(owner))
else -> Unit                               // 同组其余会话隐藏
```

⇒ R89 是补 gconv=1 主页面的缺口，不是改 gconv=0 行为。

---

## 3. D2 路线对照表（必须用户选）

| 路线 | 核心动作 | 代码量 | 额外写库 | 竞态 / 时序 | 失效场景 | 验证成本 | 裁决 |
|---|---|---:|---|---|---|---|---|
| **R1 layout 层过滤（推荐）** | `ConvFilter.rebuild()` 主页面 gconv=1 分支读一次 `allMemberWxids()`；新增纯函数 `ConvLayout.mainFiltered(convs, grouped)` 删除已归组真实会话行，保留 `wxgrp_` 伪会话行 / 空白占位 / 原始下标 | 小：纯函数 ~15 行 + 接线 ~25 行 + 单测 6~8 条 | 无。只有开关的 Kv 读写；不改微信库、不删会话 | 无新 Hook；读写都在微信 `DataSetObserver` 去抖后的主线程重建里；`membersVersion` 已覆盖成员增删改 | 分组行 `wxgrp_` 没同步出来时主页面看不到入口；`allMemberWxids()` 查库失败 ⇒ 保守不隐藏；开关关 / gconv 关 ⇒ 原样 | 低：JVM 单测 + 一次冷启正反 A/B | **推荐** |
| R2 `snaps` 层过滤 | 读快照时直接跳过已归组会话，让 `snaps` 变短 | 小 | 无 | **高危**：`snaps` 必须与微信原始 adapter 下标一一对应；变短后 `getItem(position)` 仍打原始 adapter | 必然点错人 / 长按加错分组 / 折叠锚点错位 | 低但要真机救火 | **否决**：直接违反 `ConvFilter.kt:1593-1597` 的下标铁律 |
| R3 SQL 数据源过滤 | Hook 主会话查询（如 `rawQueryWithFactory` / `s4.t`），在 WHERE 中排除已归组 wxid | 大：新 Hook + SQL 模板判据 + 版本适配 | 无直接写库，但改宿主查询 | 高：主列表 / 分组页 / 原生折叠页共用查询链；需可靠区分页面，否则分组自身也空 | 微信换混淆名；GconvPage 被连坐；微X / 宿主其它页面行为漂移 | 高：需要多页面交叉真机 | **否决**：用大 Hook 换一个小显示需求 |
| R4 改库挪会话 | 写 `parentRef` / 改 `rconversation`，把成员会话藏进伪会话 | 大 | **是，且动微信主库** | 高：与宿主 unread / 置顶 / flag / 删除链路互相写 | 消息丢失、未读错乱、删除分组后残留 | 高且不可回滚 | **否决**：违反「不写宿主会话库」红线 |
| R5 仅 `getView` 隐藏 | `layout` 行数不变，命中已归组会话时返回 0 高度 / GONE View | 小 | 无 | 中：count 不变 ⇒ 滚动、底部 Tab、点击位置全按旧行数算 | 用户滚到一片空白；点击落到隐藏行 | 中 | **否决**：视觉上不是消失，只是变空 |

---

## 4. 推荐路线 R1 的最小落点

### 4.1 新增纯函数（`data/ConvLayout.kt`）

```kotlin
/**
 * gconv=1 主页面：隐藏「已归组的真实会话」，保留分组伪会话行与未知占位。
 * 返回的行仍指向原始 convs 下标，绝不重建紧凑下标。
 */
fun mainFiltered(convs: List<ConvEntry>, grouped: Set<String>): Layout {
    val rows = ArrayList<ConvRow>(convs.size)
    for (i in convs.indices) {
        val w = convs[i].wxid
        val pseudo = GroupConvPlan.isOurs(w)
        val hide = !pseudo && w.isNotBlank() && w in grouped
        if (!hide) rows.add(ConvRow.Conv(i))
    }
    return Layout(rows)
}
```

三条硬判据：

1. `GroupConvPlan.isOurs(w)` **放在 `in grouped` 之前**：`wxgrp_` 是进入分组的唯一入口，任何情况下都不删。
2. `w.isNotBlank()` 才允许隐藏：`unreadable=true` / 折叠入口占位不能丢。
3. `ConvRow.Conv(i)` 用原始 i：后续 `getItem` / `getItemId` / 长按定位全部依赖它。

### 4.2 新增开关（`data/Keys.kt`）

```kotlin
const val GCONV_HIDE_GROUPED = "chat.gconv_hide_grouped"
```

`PrefSpec`：

```kotlin
PrefSpec(
    Keys.GCONV_HIDE_GROUPED, PrefKind.SWITCH, "true", "聊天",
    "主页面隐藏已归组会话，仅分组内可见（gconv=1；切换后冷启生效）"
)
```

- 默认 `true`：直接满足用户诉求；现有用户 Kv 无该 key 时也拿到 `true`。
- 关掉 = 完整回滚到当前 passthrough；与 `GCONV_MODE` 独立，方便反向对照。
- 生效条件：`enabled && gconvEnabled && filterId == NONE && db != null && Kv.getBool(GCONV_HIDE_GROUPED)`。
- 不改 `CHAT_GROUP_ROW_ENABLED` 的既有语义；避免 R54 那个「回滚时两个开关必须一起复位」的坑。

### 4.3 接线（`feature/ConvFilter.kt`）

`GroupedAdapter` 增加字段：

```kotlin
@Volatile private var hideGrouped = false
@Volatile private var groupedMain: Set<String> = emptySet()
```

`rebuild()` 开头读开关（与 `gconvEnabled` 同处一次，绝不放 `getView` 热路径）：

```kotlin
hideGrouped = enabled && gconvEnabled &&
    runCatching { Kv.getBool(Keys.GCONV_HIDE_GROUPED) }.getOrDefault(true)
```

配置指纹必须带 `hideGrouped`，否则只切开关会被 :1287 短路：

```kotlin
val configStamp = PinFoldLogic.configStamp(..., hideGrouped = hideGrouped)
```

> 建议给 `PinFoldLogic.configStamp` 增加**带默认值**的末参 `hideGrouped: Boolean = false`，
> 老 7 参调用与现有测试逐字不变；只新增 1 条「开关翻转 ⇒ stamp 必变」的测试。

完整重建时读成员全集（只此一次，不进 light / 短路径）：

```kotlin
groupedMain = if (filterId == NONE && hideGrouped) {
    runCatching { db?.allMemberWxids() }.getOrDefault(emptySet())
} else emptySet()
```

主页面分支：

```kotlin
if (groups.isEmpty() || gconvEnabled) {
    memberSets = emptyMap()
    models = emptyMap()
    lastSpecs = emptyList()
    if (gconvEnabled && hideGrouped) {
        ConvLayout.mainFiltered(snaps, groupedMain)
    } else {
        ConvLayout.build(snaps, emptyList(), emptyMap(), enabled = false)
    }
}
```

`filterId > 0`（gconv=0 旧筛选态）与 `db == null || !enabled`（零改动分支）**逐字不动**。

### 4.4 观测（最小）

只在隐藏条数变化时打一行，避免刷屏：

```kotlin
LogX.i("K7 ghide rowsIn=${snaps.size} rowsOut=${layout.size} hidden=$hidden grouped=${groupedMain.size}")
```

日志只是辅助；验收判据必须是**用户能看见的事实**：主页面是否还有已归组会话、分组页是否还能看到、关开关后是否逐字恢复。

---

## 5. 阶段 1：纯逻辑单测（`ConvLayoutTest.kt`）

| # | 用例 | 期望 |
|---|---|---|
| T1 | `grouped` 为空 | 与 `passthrough` 逐项等价 |
| T2 | 3 条会话，隐藏中间 1 条 | 只剩首尾两条，且尾条 `convIndexOf == 2`（原始下标） |
| T3 | 连续多条同组会话 | 全隐藏，未归组会话相对顺序不变 |
| T4 | `wxgrp_0000...0001` 也在 `grouped` 集合里 | 伪会话行仍保留（pseudo 判据优先） |
| T5 | `unreadable=true` 的空白占位 | 保留，不被混入 grouped 误删 |
| T6 | 空白 wxid 且 grouped 含空串 | 保留 |
| T7 | 输入 `convs` / `grouped` | 调用后不被修改 |
| T8 | 隐藏后 `Layout.convIndexOf/getItemId` 映射 | 仍指回原始会话对象 |
| T9 | `PinFoldLogic.configStamp` 新旧签名 | `hideGrouped=true/false` 得到不同 stamp；默认参保持旧调用不变 |

变异验证（必须做，证明测试不是摆设）：

| 变异 | 预期红灯 |
|---|---|
| 删掉 `!pseudo` 前置 | T4 红 |
| `w in grouped` 改成 `w !in grouped` | T2/T3 红 |
| `ConvRow.Conv(i)` 改成紧凑下标 `rows.size` | T2/T8 红 |
| `configStamp` 不接 `hideGrouped` | T9 红 |

---

## 6. 三档红线（进入 / 返回 / 复位）

### 进入（什么绝对不能做）

- ⛔ 不改 `rconversation` / `rcontact` / `message` 一个字节。
- ⛔ 不改 `snaps` 的 1:1 下标语义；不把 `snaps` 过滤成短表。
- ⛔ 不碰 `GconvPage` 的 `activeGid` / `activeMembers` / `activeCursor` / `reset()`。
- ⛔ 不把 `wxgrp_` 伪会话行当成「已归组真实会话」隐藏。
- ⛔ 不改 gconv=0 的自绘分组行路径；不改 `filterId > 0` 的 `ConvLayout.filtered`。

### 返回（关开关 / gconv 关 / 无 DB / 读库失败）

- `GCONV_HIDE_GROUPED=false` ⇒ 主页面必须逐字回到当前 `ConvLayout.build(..., enabled=false)` passthrough。
- `GCONV_MODE=false` ⇒ 本功能零生效；旧自绘分组行仍按原逻辑。
- `GroupDb.current()==null` 或 `allMemberWxids()` 抛异常 ⇒ `groupedMain=emptySet()` ⇒ 保守不隐藏。
- 只切开关时必须靠 `configStamp` 的 `hideGrouped` 破短路；不能依赖冷启以外的「碰巧有别的参数变了」。

### 复位边界（R42-N1N6 教训：必须写全三档，不能只写两个时点）

| 时点 | 必须成立 |
|---|---|
| 冷启首屏 gconv=1 + hide=true | 已归组真实会话不在主页面；`wxgrp_` 分组行在；点击能进分组 |
| 加入 / 移除成员后（`refreshMembers`） | `membersVersion` 触发全量重建；新成员会话消失 / 被移除成员会话重新出现 |
| 关开关 → 再开 → 回到主页面（含滚回顶部） | 每次翻转都由 stamp 触发全量重建；**滚回顶部不得把隐藏集清掉、也不得残留旧隐藏集**；不得依赖滚动方向重置 `groupedMain` |
| 删除分组后 | 该组成员从 `allMemberWxids()` 消失 ⇒ 会话回到主页面；同时 `wxgrp_` 行随 R70 机制消失 |
| gconv ON→OFF 热切换 | `hideGrouped=false`（因 `gconvEnabled=false`）；layout 回到旧路径；回收池里的带 tag View 仍由现有 :1787 分支清理 |

---

## 7. 阶段 3：真机验收（待路线确认 + 交叉复核后再做）

前置：`assembleDebug` + `testDebugUnitTest` 全绿；改动 diff 交第二视角复核；`adb install` 前核分包下限 / Pro 类归属；装包后 `pm path` + `lastUpdateTime` 确认真重装，必要时冷启两次。

| 场景 | 可见判据 | 反向对照 |
|---|---|---|
| A：gconv=1 + hide=true | 主页面看不到任一已归组会话；分组行仍可见；点分组行进入后能看到该组成员会话（用昵称核对） | — |
| B：hide=false | 主页面重新出现 A 中隐藏的已归组会话 | `op=kvset --es key chat.gconv_hide_grouped --es val 0`，`kvget` 回读后冷启 |
| C：hide=true 再开 | 再次隐藏；A/B 可逆 | `kvset 1` + `kvget` 回读 + 冷启 |
| D：成员变更 | 新加入的已归组会话从主页面消失；移出成员的会话回到主页面 | 用昵称逐一核对，不只报 wxid |
| E：gconv=0 | 与改动前一致：成员会话已隐藏，自绘分组行存在 | 关 `GCONV_MODE` 冷启对照 |
| F：无分组 / 空分组 | `allMemberWxids()` 为空 ⇒ 一条会话都不隐藏 | 与 A 同页面 |

日志辅助：`dev.py q 'K7 ghide'` / `dev.py q 'K7 full rebuild'`。日志 5000 行环形覆盖，测前 `logclear`。

---

## 8. 不做 / 待定

- 本方案不处理「主页面分组行未读聚合」：gconv=1 的分组行是微信原生伪会话，本来就不聚合成员未读；隐藏真实会话后未读细节只在分组页可见。若用户要求聚合，另开需求。
- 本方案不改 `filterId > 0` 的旧筛选态；gconv=1 的「进入分组」走 GconvPage，不写 `FILTER_GROUP`。
- 不新增探针文件；观测复用现有 `K7` 日志与 `op=kvset/kvget`。
- 未提交。R70 方案 `docs/_r88_r70_residual_plan.md` 与 `docs/派发审计台账.md` 的本轮改动保持原样，不混入 R89。

---

## 9. 请用户确认

按 D2 必须选路线后才能落码。推荐 **R1 layout 层过滤**。

若确认 R1，下一步顺序：

1. 阶段 1：改 `ConvLayout.kt` 纯函数 + `ConvLayoutTest.kt` / `PinFoldLogicTest.kt` 单测与变异验证（不装机）。
2. 阶段 2：接线 `Keys.kt` / `ConvFilter.kt`，编译 + 全量单测。
3. 真机前交叉复核 diff（第二视角）+ 装包前核 ①分包下限 ⑦Pro 类归属。
4. 阶段 3：装机按 A~F 做正反对照。
5. 阶段 4：固化结论到 `docs/conclusions/conv_list.md` 与 `docs/当前状态.md`，按惯例拆 commit。

---

## 10. 微X基线实测（2026-11-28 22:05~22:15，只读取证）

用户已建微X临时分组；全程**用户手动导航**（用户明确要求「不要自动点击，让我来点」），主会话只截图 / 读日志 / 只读 SQL，不点归拢设置、不改库、不装包。

### 10.1 现场事实

| 时点 | 事件 | 主 adapter `n` | 分组页 | 备注 |
|---|---|---|---|---|
| 22:05 | 分组页 `临时分组 (0)` | 1581 | `w8.W count=0` | 组为空 |
| 22:07:32 | 用户加入成员 | 1580（22:08:37 首次重建） | `w8.W count=1` | `s4.b(event=3, user=wx__c_1a22270cbc3)` 同步刷新 |
| 22:08~22:14 | 已归组期间 | 稳定 1580 | 成员行「坚锋-方旭凯」16:49「直接寄其他规格好了，不影响」 | 用户目视主列表确认「没有」该会话 |
| 22:14:45 | 用户移出成员 | **1581** | — | 反向对照成立 |

- 分组行 = `wx__c_1a22270cbc3`（微X伪会话），进入时 `q4.n` + `w8.W` + `K7 gpage native page`。
- 主列表可见基线：临时分组 / 日化 / 123 / 美妆 / 改性 / 客户 / 贸易 / 美妆2 / pet素材1 / 折叠置顶聊天。
- 实测昵称映射：显示名「坚锋-方旭凯」= `wxid_bwmfoul0pp0021`（`conRemark`，nickname「喧嘩」）；同名 `@openim` 是另一联系人，不可混。

### 10.2 关键裁决：微X不是删库，也不是显示行过滤

- 加组后 `rconversation` 中该会话行**仍在**：`msgCount=11407, unReadCount=0, conversationTime=1795855765043, content=直接寄其他规格好了，不影响, parentRef='', attrflag=0`。
- `message` 中该 talker **11407 条仍在**（`max msgId=19817266`）。
- `rconversation` 总行 3137（微X伪行 13 / 我方 `wxgrp_` 1），主 adapter 只有 1580 ⇒ 列表本来就有查询过滤。
- 加组/移出使**宿主 adapter 原始计数** 1581↔1580 同步变化，而 DB 行不变 ⇒ 微X的隐藏发生在**数据源/查询层**（SQL 改写或等价机制），不是 `getView` 隐藏，也不是改库删除。
- 这与 R64 早期「getCount/getItem 注入」推断不同（R66/R67 已推翻注入说），本次实测给出更精确落点：**计数就变了，不需要再改 getCount**。

### 10.3 对 R89 的影响

- 推荐路线 **R1（layout 层过滤）不变**：用户可见效果与微X一致（主列表消失 / 分组内可见），且**不碰宿主查询、不改库、不删消息**，可逆性更好。
- 差异需向用户明示：R1 下会话行仍在 `rconversation`，微信自己的搜索 / @ 选择器等非主列表入口可能仍能找到它；微X的查询层过滤在这些入口可能也一并隐藏，本轮未测这些入口，**不作为判据**。
- 真机验收 D 项（成员变更）已有微X作为正向参照：我方必须同样做到「加入即消失 / 移出即恢复」，但允许 `n` 不变（R1 只改 layout 显示行）。因此我方判据用**用户可见行**而非 `n` 的差值。

---

## 11. R1 vs 查询层复刻：实证版完整对比（2026-11-28 22:31 补）

### 11.0 为什么补这一节（自我纠错）

此前 §9 的优劣对比是**定性复述**：Pro 子代理 402 后主会话自审，直接拿 D2 路线表模板（路线/判据/误伤面/实现量）+ 记忆里的判断作答，**没有回到 `app/src/main` 逐行核对**。用户追问「过滤 adapter 不会引起页面不正常吗」后才补做源码实证，发现早期对比漏掉了决定 R1 能否安全落地的关键维度。结论方向未必错，但论证链不完整，属用定性复述冒充实证对比。本节补齐。

### 11.1 页面正确性：映射层实证（早期对比完全缺失）

源码事实：

| 方法 | 位置 | 是否映射回原始下标 |
|---|---|---|
| `getCount()` | `ConvFilter.kt:1723` | `layout.size`（过滤后行数） |
| `getView()` | `ConvFilter.kt:1762` → `:1792` | ✅ `r = layout.convIndexOf(position)` → `wrapped.getView(r, ...)` |
| `getItemViewType()` | `ConvFilter.kt:1905-1909` | ✅ `r = convIndexOf(position)` → `wrapped.getItemViewType(r)` |
| `getItem()` / `getItemId()` | `ConvFilter.kt:1725-1729` | ❌ 直接用 `position` 取 wrapped |
| `Layout` 行定义 | `ConvLayout.kt:61` | `ConvRow.Conv(index)` 的 index = **原始列表下标** |
| `convIndexOf` | `ConvLayout.kt:246-247` | 从 `rows[position]` 取原始 index，分组行返回 -1 |

推论：

1. R1 隐藏中间行后，layout 剩余行仍各自带回自己的原始下标 ⇒ **显示行不错位**。
2. **反证（getItem 不映射为何无害）**：gconv=0 插入分组行时 `position` 早已偏移，若微信点击/长按走 `adapter.getItem`，分组模式第一天就会点错人；实际没出事 ⇒ 微信点击走的是 `getView` 里 wrapped 设的 listener（绑定 `wrapped.getItem(r)` 的对象），不走 `getItem`。所以 R1 隐藏行后 `getItem` 不映射也不会导致点击错乱。
3. 查询层复刻让行从微信数据源消失，宿主 adapter 原始 count/游标本来就是隐藏后的 ⇒ **不存在映射层**，天然没有这类错位风险。这是复刻方案在页面正确性上的真实优势，**早期对比漏掉了**。

### 11.2 R1 继承的两个约束（早期对比未列）

1. **快照必须实时**：R22「条数不变、顺序重排」坑——`snaps` 旧顺序 + `convIndexOf` 旧下标 r ⇒ `wrapped.getView(r)` 渲染成"现在占 r 的人" ⇒ 显示 A 点开 B。修法是 K7 顺序指纹（`size != n` 不够）；R1 **不得破坏**这条失效判据，hidden 集变化必须触发全量重建。
2. **hidden 集与 snaps 同版本**：`GroupDb.allMemberWxids()` 的结果必须和 `snaps` 来自同一次重建，否则成员变更 / 滚回顶部时出现"A 用新分组、B 用旧下标"。对应项目铁律「缓存集合必须挂版本号，光靠 TTL 必漏」。

### 11.3 实证对比矩阵（替换 §9 定性表）

| 维度 | R1 layout 过滤 | 查询层复刻（微X式） |
|---|---|---|
| 主列表隐藏 / 分组内可见 | ✅ / ✅ | ✅ / ✅ |
| adapter `n` | 不变（1581） | 1580，与微X逐字一致 |
| 页面映射正确性 | 依赖 `convIndexOf`，有 2 条继承约束（§11.2），守住则 ✅ | 数据源即隐藏后，无映射层，天然 ✅ ← **早期漏** |
| 点击/长按错乱风险 | `getItem` 不映射但微信不走它（§11.1 反证）⇒ 低 | 无此风险 |
| 搜索 / @选人器 / 未读聚合覆盖 | ❌ 只挡主列表 adapter（推断：这些入口仍可见） | ✅ 走同一 `s4.t` 链则连带隐藏（未实测，不作判据） |
| 改库 / 删消息 | 无 | 无（实测 §10.2） |
| Hook 位置 | 我方 adapter，安全 | 宿主 `rawQueryWithFactory`（DB 热路径） |
| 版本漂移风险 | 低 | 高（SQL 文本指纹） |
| 分组人数多 | 无影响 | `NOT IN` 变长需评估 |
| 可逆性 | 关开关即恢复 | 卸 hook 即恢复（DB 不动 ⇒ 天然可逆） |
| 即时生效 | 我方 layout 重建 | 需补发 `s4.b(event=3)` |
| 实现量 / 可验证性 | 小，纯函数+单测可先验证 | 大，需先只读探针抓宿主 SQL 原文 |

### 11.4 结论（修订）

- R1 在页面正确性上**不劣于**复刻，前提是 §11.2 两条约束写进单测与变异（不是早期说的"更安全所以全面优"）。
- 复刻的真实优势是**语义覆盖**（搜索 / 选人器等入口）与**无映射层**，不是"纯风险"。
- 推荐仍为 **R1**，理由改为：零宿主侵入 + 可分步验证，而非"全面优于"。
- 若用户要求严格「任何其他入口都看不到」，R1 不够，需叠加查询层方案。

### 11.5 R1 落码判据（写进单测 + 变异）

- 隐藏首尾行 / 隐藏中间行 / 连续多行 / 全隐藏 → `convIndexOf` 逐位映射到正确原始下标。
- 重排同条数 + 隐藏 → 重建后映射不指向旧下标（R22 变异）。
- 成员加入 / 移出后 → hidden 集与 snaps 同版本；滚回顶部不残留旧集。
- 关开关 → 再开 → 回到主页面 → hidden 集清空且无残留（三档复位边界）。
