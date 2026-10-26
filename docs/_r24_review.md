# R24 改动复核报告

> 复核范围：`wxgroup/` 下 R24 改动（PickerPage 四 Tab / 跨 Tab 勾选 / ChatroomSource 补群聊 / ListResolver / NotifyMute g:/t: / MomentsExport 快照 / ConvFilter invalidateScope / MainPage、MassSendPage 调用方 / EntryReceiver op=room）。
> 方法：只读源码，未跑 gradle、未用 adb。

---

## 1. 结论

**暂不能上真机验收：有 1 条阻塞级缺陷**（`PickerPage` 搜索/切 Tab 状态错位，会让用户看到错误名单），另有 4 条次要问题。阻塞项修掉后可上机。

- 阻塞：`PickerPage.Adapter.filter()` 引用的是构造时固定的 `sections` 字段，切 Tab 后 `reset()` 换了可见行但没更新该字段 ⇒ 切 Tab 或清空搜索框时列表会回跳到「好友 Tab」的内容（Q3 的怀疑成立）。
- 其余 Q1/Q2/Q4（ChatroomSource 主逻辑）/Q5（线程安全）/Q6/Q7/Q9/Q10 均通过，详见第 4 节。

---

## 2. 阻塞级问题

### B1 · `PickerPage` 切 Tab 后搜索/清空会把列表带回「好友 Tab」的内容

**文件**：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/PickerPage.kt`

- `Adapter` 的 `sections` 是**构造参数 `private val`**（`:293`），构造后永不改变：
  ```kotlin
  private class Adapter(
      private val ctx: Context,
      private val sections: List<Section>,   // ← 固定 = tabs[0].sections
      private val selected: Set<String>,
  )
  ```
- `reset(list)` 只重建 `visible`，**不回写 `sections`**（`:303-311`）：
  ```kotlin
  fun reset(list: List<Section>) {
      visible.clear()
      for (s in list) { ... }   // 只改 visible
      notifyDataSetChanged()
  }
  ```
- `filter(keyword)` 里两处都用的是 `sections` 字段（`:344`、`:348`）：
  ```kotlin
  if (kw.isEmpty()) { reset(sections); return }
  val kept = sections.mapNotNull { ... }   // ← 永远是第 0 个 Tab 的 sections
  reset(kept)
  ```

**触发条件**（两种，都很日常）：
1. 在「好友」Tab 搜索了「张」→ 切到「群聊」Tab。`switchTo`（`:219-231`）先 `adapter.reset(tabs[i].sections)` 正确换成群聊，但随后 `if (keyword.isNotBlank()) adapter.filter(keyword)`（`:224`）用**旧 `sections`** 重算，把可见行替换成「好友里含『张』的项」。结果：Tab 高亮在「群聊」，列表却显示好友 —— 用户看到错误名单。
2. 切到「群聊 / 我的分组 / 微信标签」任一非首 Tab 后，在搜索框输入或**清空**关键字：`afterTextChanged → adapter.filter(keyword)`（`:146`）与 `filter("") → reset(sections)`（`:344`）同样落在旧 `sections` 上，列表回跳成好友内容。

**影响**：跨 Tab 勾选本身没坏（`selected` 不受影响），但「当前 Tab 该显示谁」彻底错乱，是**用户可见的错误结果**，且会把「群聊/分组/标签 Tab 的搜索功能」整体废掉。直接违背 R24 需求 1。

**建议改法**（二选一，最小改动）：
- 把 `private val sections` 改成 `private var sections`，并在 `reset(list)` 第一行 `sections = list`；
- 或者让 `filter()` 基于 `visible` 当前数据源过滤（如保存一份当前 `sections` 副本随 `reset` 更新）。

改完后 `switchTo` 里的 `adapter.reset(tabs[i].sections)` 会顺带更新 `sections`，`filter` 即作用在正确的 Tab 上。

---

## 3. 次要问题

### S1 · 无名群会显示原始 wxid（含 `@chatroom`），而非「群号」

**文件**：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/PickerTabs.kt:96-100` + `wxgroup/app/src/main/java/com/lx/wxgroup/data/Friend.kt:19-23` + `GroupNameLogic.kt:107-113`

`roomTab` 写成：
```kotlin
title = GroupNameLogic.displayNameOf(it.wxid, it.displayName)
```
但 `it.displayName` 是 `Friend.displayName`，取值顺序 `remark > nickname > alias > wxid`（Friend.kt:19-23）。群名（remark/nickname）都空时，`displayName` **已经回落到 wxid**（如 `19501172869@chatroom`）。此时 `GroupNameLogic.displayNameOf` 拿到的是一个**非空** name，直接原样返回，`removeSuffix("@chatroom")` 兜底逻辑（GroupNameLogic.kt:111-112）永远不会走到。

结果：无名群在「群聊」Tab 显示 `19501172869@chatroom`，而不是设计意图（注释明说的「兜底成群号」`19501172869`）。注释 `PickerTabs.kt:87-89` 声称「写 wxid 会让搜 `chatroom` 命中全部群」，实际上因为 title 里带了 `@chatroom`，搜 `chatroom` 仍会命中无名群 —— 与注释意图相悖。

**建议改法**：`roomTab` 传原始群名而不是已回落的 `displayName`：
```kotlin
title = GroupNameLogic.displayNameOf(it.wxid, GroupNameLogic.pick(it.remark, it.nickname))
```
（`GroupNameLogic.pick` 在 remark/nickname 都空时返回 null，`displayNameOf` 才会剥 `@chatroom` 成群号。）

**影响**：仅显示层，勾选/写入的 `id` 仍是正确 wxid，不丢数据、不选错人，属次要。

### S2 · 「可见 N」把段标题也数进去了

**文件**：`PickerPage.kt:278`（`refreshCounter`）

`counter.text = "已选 ${selected.size} 项 · 可见 ${adapter.count}"`，其中 `adapter.count` = `visible.size`，而 `visible` 里含 `Row.Header`（每个段一个标题行）。好友 Tab 只有一段，`可见` 显示 N+1；`Tab.count`（`:71`）才是真正条目数 N。

**影响**：计数器差 1（每段多算 1 个 header），不影响功能，属文案精度问题。可改成用 `tabs[currentTab].count` 或让 `Adapter` 单独暴露「条目数」字段。

### S3 · `MassSendPage.onEnterSpec` 空数据时返回 false → 提示「暂未接入」，文案误导

**文件**：`MassSendPage.kt:189-198`（`db==null` / `tabs.isEmpty()` 均 `return false`）＋ `Rows.kt:207-210`

`Rows.pref` 对 ENTER 行：`if (!onEnter(spec)) UiKit.toast(ctx, "「${spec.title}」暂未接入")`。当群发目标 `PickerTabs.all` 返回空（好友缓存空/无群/无分组/无标签）时，用户会看到「『选择发送目标』暂未接入」，而真实原因是「好友缓存为空」。对比 `MainPage.onEnterSpec`（`MainPage.kt:101-104`）在同样场景是明确 toast「好友缓存为空，先去通讯录滚一遍再回来」。

**影响**：不是「点了没反应」（有 toast），但文案会把人往「功能没接线」的方向误导。属 UX 次要，建议让 `MassSendPage` 也 toast 真实原因。

### S4 · `NotifyMute` 首次展开 g:/t: 名单时在通知回调里同步查库

**文件**：`NotifyMute.kt:338-368`（`scopeBuckets` → `loadBuckets`）

`inScope` 跑在 `onNotify` 的 hook 帧上（`NotifyMute.kt:231-254`）。当名单含 `g:`/`t:` 且缓存过期（30s TTL 或刚 `invalidateScope`）时，`loadBuckets` 会同步执行 `db.listGroups()` + 逐组 `db.memberWxids(g.id)`（`NotifyMute.kt:359-368`）。分组多（几十）且成员上千时，是几十次 SQLite 查询在通知链路上同步跑，可能让当条 @ 通知延迟。

**缓解因素**：只对「@我且开启被@静音」的群消息触发（`NotifyMute.kt:251`），不是每条通知都走；且有 30s 缓存。KDoc（`:315-320`）已明确这是「容忍配置晚 3 秒生效、不能容忍通知卡顿」的取舍，但当前实现里 `loadBuckets` 仍同步压在一次通知帧上，并非后台预载。

**影响**：性能/延迟，极端分组规模下可能卡通知。可后续优化为后台预载 + 通知帧内只读缓存；本轮不阻塞。

### S5 · `ChatroomSource.syncBlocking` 的 `force=false` 分支是死代码

**文件**：`ChatroomSource.kt:132-141`

```kotlin
fun syncBlocking(force: Boolean = true): Int = runCatching {
    ...
    if (!force && !shouldRun(db)) { ... }   // ← 无调用方传 force=false
```

全仓只有两处调用：`runIfNeeded` 里 `syncBlocking()`（无参 ⇒ force=true，`:116`），探针 `syncBlocking(force = true)`（`EntryReceiver.kt:221`）。`!force` 分支永远进不去，属死代码（不是 bug，`runIfNeeded` 已先 `shouldRun` 守卫，不会每次全表扫）。

**影响**：无功能影响，仅代码整洁度。可下轮把 `force` 参数删掉或让 `runIfNeeded` 改调 `syncBlocking(force=false)` 以消歧义。

---

## 4. 确认无问题的点（Q1–Q10 逐条）

- **Q1 勾选跨 Tab 成立** ✅
  - `selected` 是页面实例字段 `LinkedHashSet(initial)`（`PickerPage.kt:75`），`Panel.refresh()` 重建 View 但实例不变，故选择不丢。
  - `Adapter` 构造传入的 `selected: Set<String>`（`:294`）就是同一个 Set 引用，`build` 里 `Adapter(ctx, tabs[currentTab].sections, selected)`（`:110`）无拷贝。
  - `switchTo`（`:219-231`）只 `reset(tabs[i].sections)` + 重放 filter + 重绘 + 计数，**完全不碰 `selected`**（`:216-217` 注释与实现一致）。
  - `reset()`（`:303-311`）只改 `visible`，不碰 `selected`。跨 Tab 勾选存活确认成立。

- **Q2 「确定」计数语义** ✅（见 S2 的一个小瑕疵）
  - `已选 ${selected.size}` 是全局跨 Tab（`selected` 唯一）；`可见 ${adapter.count}` 来自同一个 `adapter` 实例的 `visible.size`。
  - `adapter` 是 `lateinit var`（`:80`），`build` 里只 `list.adapter = adapter` 赋值一次（`:115`），**从不重建**，`switchTo` 后 `adapter.count` 就是新 Tab 的可见行数。语义正确（仅 header 计数见 S2）。

- **Q3 搜索框切 Tab 的行为** ❌ **不成立，见 B1**。`Adapter.filter` 的 `sections` 是构造时固定引用，`reset` 换新 sections 但 `filter` 仍读旧 sections。

- **Q4 ChatroomSource 正确性**
  - `while (scanned < READ_MAX_ROWS && moveNext(cursor))`（`:234`）不会死循环（`scanned` 每轮递增、`moveNext` 每轮推进游标），也不会漏行（除非 rcontact >60000 行则提前停，但日志 `scanned rows=60000` 可定位）。
  - `stringAt(cursor, i)` 在 `i<0` 时返回 null（`:312-313`），`readChatrooms` 对 `iNick`/`iRemark`/`iAlias` 都做了 `>=0` 守卫（`:239-240`、`:248`），降级正确。
  - 群名两空仍保留行 ✅（`:241-243`）；但两层显示叠加有 S1 的显示瑕疵（见次要问题，不影响选人正确性）。
  - `syncBlocking(force=true)` 默认 + `runIfNeeded` 先 `shouldRun` 再 `syncBlocking()`：不矛盾。`runIfNeeded` 的 `shouldRun`（`:96-102`）在缓存群聊数为 0 或超 6h 才放行，放行后才全表扫；`syncBlocking` 内部 `!force` 分支为死代码（S5），但**不会每次触发都全表扫**（`KEY_LAST_FULL` 落盘 + `markFullSync` 用 commit，`ChatroomSource.kt:265-270`）。

- **Q5 NotifyMute TTL 线程安全**
  - `scopeCache`/`scopeAt` 虽非 volatile，但**读写全部在 `synchronized(scopeLock)` 内**（`scopeBuckets` `:343-350`、`invalidateScope` `:371-373`），monitor 的 happens-before 保证可见性，**无并发可见性 bug**。
  - `inScope` 在通知回调里跑、`loadBuckets` 查 `group_member` 的阻塞问题已在 S4 记录（性能，非正确性）。

- **Q6 MomentsExport 配置快照**
  - `Config` 新增 `ids`/`listUnrestricted`/`listImpossible` 均有默认值。全仓 `Config(` 仅两处：初始值 `Config(false, Prefs.DEFAULT_EXPORT_PATH, emptyList())`（`:219`，其余走默认）与 `refresh()`（`:299-306`，全部显式传参）。无遗漏构造点。
  - 初始值 `listUnrestricted=true` **安全**：此时 `autoExport=false`，`onTick` 首行 `if (!c.autoExport) return`（`:377`）不会触发导出；且 `install` 第一句就是 `refresh()`（`:286`）用真实名单替换。默认「不限制」方向与「没配名单=全导」语义一致，不会静默导出错误范围。

- **Q7 ListResolver 语义**
  - `unrestricted = raw.isEmpty()`：`Kv.getList` 已 `filter { it.isNotBlank() }`（`Kv.kt:48`），`expand` 再 trim+去空（`ListResolver.kt:86-87`）。「全空白名单 → raw 空 → unrestricted=true」是**正确**的（等价于「没配」= 不限制）。真正要防的「配了空分组 → 别当成不限制」由 `impossible`（`:59`）承载，单测 `configured_but_expands_empty_is_impossible_not_unrestricted`（ListResolverTest.kt:104-110）已锁住。
  - `inScope` 中 `excludeRaw` 为空时 `expand(emptyList()).ids` 是空集，`who in 空集` 恒 false，行为正确（`ListResolver.kt:123-126`）。
  - `prefix_matches_senders` 能跑：`MassSendLogic.PREFIX_GROUP/PREFIX_LABEL`（MassSendLogic.kt:34/37）与 `ForwardLogic.PREFIX_GROUP/PREFIX_LABEL`（ForwardLogic.kt:151/154）都是 `object` 内 `const val`（public），单测可访问，编译+断言均成立。
  - （注：`ListResolverTest` 实为 **22 条 `@Test`**，需求描述写「18 条」，不影响正确性。）

- **Q8 PickerTabs 空 Tab 处理**
  - `all()` 四源全空返回空 list（`PickerTabs.kt:43-54`）。两个调用方都检查 `isEmpty()`：`MainPage.onEnterSpec`（`:101-104`）toast「好友缓存为空」并返回 true；`MassSendPage.onEnterSpec`（`:197`）返回 false。
  - `MassSendPage` 返回 false 的后果是 `Rows.pref` 弹 toast「『选择发送目标』暂未接入」（`Rows.kt:207-210`），**不是「点了没反应」**，但文案误导（见 S3）。

- **Q9 无编译期遗漏**
  - 全仓 `PickerPage(` 仅 3 处：类声明（`:49`）、`MainPage.kt:107`、`MassSendPage.kt:200`，后两处均已改为 `tabs`/`initial`/`hint`/lambda 新签名。
  - `PickerPage.Section`/`PickerPage.Item` 定义未变，`ForwardPicker.kt`（`:64/67/177-226/401-465`）仍正常引用，不受影响。
  - `PickerPage.Tab` 是新类型，仅被 `PickerTabs`（`PickerTabs.kt:47-133`）与 `PickerPage` 自身使用，无散落引用。

- **Q10 无死代码/矛盾注释**
  - 全仓 `labelSection`、`GROUP_PREFIX` 均 **0 命中**（grep 无结果），确认 `MainPage`/`MassSendPage` 删除后已无残留引用。
  - 唯一的死代码是 `ChatroomSource.syncBlocking` 的 `force=false` 分支（S5），非本轮 UI 改动引入。

---

## 5. 真机验收建议

按「现象 → 对应改动 → 如何区分真失败/假阴性」组织，先验阻塞修复，再验功能面。

### 5.1 阻塞修复（B1）验收
- **先修 B1 再上机**。修完后重点复现两种操作：
  1. 好友 Tab 搜「张」→ 切「群聊」Tab，列表应显示群聊（被搜索词过滤后的群聊），**不得**显示好友；
  2. 在「群聊/分组/标签」Tab 输入再清空搜索框，列表应回到**当前 Tab** 的全量，**不得**跳回好友。
- 判别：若 Tab 高亮与列表内容不一致（高亮「群聊」却显示好友），即 B1 未修好；日志 `PICKER tab ... visible=... selected=...`（`PickerPage.kt:227-230`）可核对 `visible` 数是否对应当前 Tab。

### 5.2 跨 Tab 勾选（Q1）
- 好友 Tab 勾 3 人 → 群聊 Tab 勾 2 群 → 切回好友，勾选应还在（`已选` 全局计数不减）。确认时 `onConfirm` 收到的 `ids.size` 应 = 跨 Tab 累计。
- 判别真/假阴性：用 `PICKER confirm ... n=...`（`PickerPage.kt:270`）看最终 `n`；若 n 对但「点确定没反应」，先排除下游（如群发 `resolveTargets` 展开为空会 toast「目标展开后为空」，`MassSendPage.kt:263-265`），不是选择器丢勾选。

### 5.3 「现在没有群聊」修复（ChatroomSource）
- 触发链：打开通讯录滚一遍 → `ContactSync.doSync` 收尾 `ChatroomSource.runIfNeeded()`（`ContactSync.kt:202`）。随后 `adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op room --es sub stat`。
- 判据：
  - `stat` 里 `cached=` 应 >0、`types=` 应出现 `chatroom=N`（N>0）；若 `cached=0` 且 `lastError` 非空，看 `CHATROOM sync failed: ...` 定位（db not ready / rawQuery not found / 0 chatroom rows）。
  - 若 `lastError="0 chatroom rows"`：是全表扫到 0 行，用 `sub=list` 与 `sampleText` 对照群名；可能 rcontact 里群聊本来就没有（换号验证），**别**误判成「用户没群」。
  - `sub sync` 手动强制全表扫一次（`EntryReceiver.kt:220-224`），对比 `stat` 里 `upsert=` 与 `cached=` 是否增长。

### 5.4 免打扰 g:/t: 前缀（NotifyMute）
- 名单里勾一个分组/标签 → 发一条 @我的群消息，应静音。改完名单后立即发消息应立即生效（`MainPage.kt:116` 已 `invalidateScope`）。
- 判别假阴性：若「改名单后 30 秒内行为没变」，先看是否 `invalidateScope` 没被触发（日志 `K9` 无 `scope buckets` 重查），而不是判定「前缀展开失效」——这是 S4 的 30s 缓存窗口，不是 bug。

### 5.5 朋友圈导出名单展开（MomentsExport）
- 配一个含 `g:`/`t:` 的导出名单 → `op=moments sub=export`，观察 `listMiss` 与导出条数。
- 判别：`listMiss` 高说明名单命中失败；用 `stat` 里 `list=<n>` 与日志 `resolve list failed`（`MomentsExport.kt:297`）区分「名单没配」与「展开失败」。若名单配了但一条都不导出，是 `listImpossible`（`MomentsExport.kt:350-356`）在起作用——确认分组/标签确实有成员，而不是代码误判。

### 5.6 四 Tab 数据源齐全性（PickerTabs）
- 四个 Tab 应各只出现一次且顺序固定（好友→群聊→我的分组→微信标签）。空数据源对应 Tab 直接不出现（`PickerTabs.kt:20-24`），别误判成「Tab 丢失」。
- 群聊 Tab 里有无名群时观察显示（S1）：显示 `xxx@chatroom` 即为 S1 未修，`xxx` 为修好——纯显示，不影响选人。

### 5.7 单测
- 未跑 gradle，但 `ListResolverTest` 22 条断言覆盖三态语义与常量对齐，逻辑侧可在 JVM 直接锁住，真机不必重复验纯判据。
