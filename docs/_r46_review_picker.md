# 装包前独立复核：选人组件「已选中的人排在列表前面」

> 独立第二视角复核；只读源码，不改任何源码/测试文件（本结论文件是唯一写入目标）。

## 复核范围（只读）

| # | 文件 | 关注点 |
|---|------|--------|
| 1 | wxgroup/app/src/main/java/com/lx/wxgroup/feature/PickerTabLogic.kt | `selectedFirst` |
| 2 | wxgroup/app/src/main/java/com/lx/wxgroup/feature/PickerPage.kt | `Adapter.render` / `filter` / `init` / `selected` 字段 |
| 3 | wxgroup/app/src/main/java/com/lx/wxgroup/feature/ForwardPicker.kt | `TabAdapter.reset` / `filter` / `selected` |
| 4 | wxgroup/app/src/test/java/com/lx/wxgroup/feature/PickerTabLogicTest.kt | 单测覆盖 |

基准提交：`4ac6aae`（「选人组件中已选中的人排在列表前面」）。上一轮为主会话自审，本轮为补做的独立复核。

## 复核方法

1. 以 `4ac6aae` 的 diff 为线索，独立逐行阅读上述 4 个文件，自行得出判断，不预设结论、不复述既有结论。
2. 沿数据流逐条核对 7 个问题，每条给出 `PASS`/`FAIL` 并标注「文件:行号」证据。
3. 问题 6 对全源码树 grep `visible.add` / `Row.Entry` / `Row(` 的全部调用点，确认所有「展开可见行」入口均被覆盖。
4. 判定维度：稳定分区语义、三档边界（进入/返回/复位）、`selected` 引用共享、勾选当下不重排、异常输入健壮性、入口完整性、最小回退路径。
5. 结论分块落盘：先写范围与方法，之后每答完 2 个问题追加一次；末尾给出总体结论、问题清单与 `[DONE]`。
6. 全程只读源码；不接触 adb / 真机 / Gradle / 装包。

## 逐条复核

[PASS] 问题1 — 证据: PickerTabLogic.kt:66-73; PickerTabLogicTest.kt:143-146,150-153,156-159,169-181 — 说明: 真·稳定分区。`val (picked, rest) = items.partition { idOf(it) in selected }`(L71) 的 partition 稳定、两组内部各自保持原相对顺序，`return picked + rest`(L72) 生成新 List；入参声明为只读 `List<T>`，函数体只做 `in` 判定与拼接，不 add/remove/sort，不改动入参；无成员字段、无缓存、无写回（selected 为只读 `Set<String>`、idOf 只读 id），唯一副作用是返回值。调用方把返回值直接喂给 `visible.add(...)`(PickerPage.kt:415, ForwardPicker.kt:431)，未回写 source/sections。单测佐证：两组内部稳定(L150-153)、全选仍原序(L156-159)、重复 id 不去重且保序(L169-173)、幂等无逐轮漂移(L177-181)、无选中时整表原序(L143-146)。

[PASS] 问题2 — 证据: PickerPage.kt:132,390-392,400-419,241-246,163-170,462-476,274-305; ForwardPicker.kt:102,419-435,276-285,142-149,468-482; PickerTabLogicTest.kt:183-189 — 说明: 三档全覆盖，且没有缓存排序结果、没有写回 source。①进入：构造 Adapter(PickerPage L132)→`init{ reset(initialSections) }`(L390-392)→reset(L400-403)→render(L405-419)；ForwardPicker 构造 TabAdapter(L102)→init reset(L419-421)→reset(L423-435)，首次打开即按当时 selected 排前。②返回：切 Tab 走 reset/filter→render(PickerPage L244 及 keyword 非空时 L246；ForwardPicker L280 `filter(keyword)`→L470/481 reset)，输入搜索关键字走 filter→render(kept)(PickerPage L168→L462-476；ForwardPicker L147→L468-482)，清空搜索框走 kw 空分支 `render(source)`/`reset(sections)`(PickerPage L464-466；ForwardPicker L470-472)，群聊补跑刷新走 onRoomsReady→applyTabList→`adapter.reset(...)`(PickerPage L274-305，尤其 L303-304)。清空搜索框能回到完整全量，因为 source 仅由 reset 整体替换、从未被过滤结果污染。③复位：selectedFirst 每次现算(L71-72)，render/reset 每次先 `visible.clear()` 再按最新 selected 重排；反选/清空当下不重排（见问题4），下一次任何 render 即按最新 selected；单测 L183-189 明确覆盖清空/换一组已选后重算。排序结果只落进 visible，未写回数据源：PickerPage source(L386) 只在 reset(L401) 被整体替换，filter 用 mapNotNull 生成新 kept(L469-475)；ForwardPicker sections(L413) 是构造期 `private val`，filter 生成新 kept(L475-480)。边界说明：ForwardPicker 侧没有群聊补跑后的原地刷新入口（其 L179-182 明示该半步留待后续轮次），故不存在"刷新后未按最新 selected 重排"的路径；这是既有设计，不是本次改动引入的回归。

[PASS] 问题3 — 证据: PickerPage.kt:77,132,370,386,400-403,405-419,450,462-476,241-253,293-306; ForwardPicker.kt:70,102,413-414,423-435,463,468-482,276-285 — 说明: 是页面级可变集合且按引用共享。`selected` 都是实例字段 `LinkedHashSet`（PickerPage L77 带 initial，ForwardPicker L70），不是 build 内局部变量；构造 Adapter/TabAdapter 时原样传入同一实例（PickerPage L132→L370 `private val selected: Set<String>`，ForwardPicker L102 对三个 TabAdapter 都传同一个 `selected`→L414）。因此 `selectedFirst(..., selected)`(PickerPage L414 / ForwardPicker L430) 与勾选态判定 `selected.contains(r.item.id)`(L450) / `item.id in selected`(ForwardPicker L463) 读的是同一对象，勾选后内容变化下次 render 立即可见。切 Tab 没有 clear：switchTo(PickerPage L241-253、ForwardPicker L276-285) 与 applyTabList(PickerPage L293-306) 全程不碰 selected，类注释 L39-43 / L238-239 还把它列为不可破的不变量；全树 `selected.clear()` 仅出现在单选点中(PickerPage L153)、用户点「清空」(PickerPage L337、ForwardPicker L314) 与 BatchPicker 的「清空选择」(其 L148)，无任何切 Tab 路径。filter 一律走 render/reset 而非对 source 破坏性操作：PickerPage filter(L462-476) 调 `render(source)` 或 `render(kept)`，render 只 `visible.clear()`+add(L405-419)，source(L386) 仅在 reset(L401) 整体替换；ForwardPicker filter(L468-482) 调 `reset(sections)`/`reset(kept)`，sections(L413) 是构造期 val、reset 只动 visible。即过滤结果从不写回全量数据源，清空搜索框能回到完整列表。

[PASS] 问题4 — 证据: PickerPage.kt:150-161,310-341,443-453; ForwardPicker.kt:289-294,296-318,451-465; PickerTabLogic.kt:63-64 — 说明: 勾选当下只 `notifyDataSetChanged()`，不重排。`onItemClick` 多选分支(PickerPage L158-160) 只做 `selected.add/remove` → `adapter.notifyDataSetChanged()` → `refreshCounter()`；quickRow 全选/反选/清空(PickerPage L313-320) 同样只 notify+counter；ForwardPicker toggle(L289-294) 与 quickRow(L299-304) 亦然。上述四条勾选路径（PickerPage 两处、ForwardPicker 两处）都**没有**调用 render/filter/reset，`visible` 不会被 clear/add，行位置不变，不会"点一行该行立刻跳走"。notifyDataSetChanged 只触发 getView 重绑勾选态：`v.bind(r.item, selected.contains(r.item.id))`(PickerPage L450) / `item.id in selected`(ForwardPicker L463)，复选框按最新 selected 更新而顺序保持。重排只发生在下一次显式 render/reset（切 Tab / 搜索 / 清空搜索 / 补跑刷新），与纯判据 KDoc(L63-64) 声明的"什么时候重排由调用方决定"一致。

[PASS] 问题5 — 证据: PickerPage.kt:48,139-141,152-157,174,343-348,408,426,441,444-453; ForwardPicker.kt:426,442,449,452-465; PickerTabLogic.kt:66-73; PickerTabLogicTest.kt:163-165,169-173,193-195 — 说明: 五类边界均无崩溃/错序风险。单选(multi=false)：PickerPage L152-157 点中即 `selected.clear()`+`add(item.id)`+`confirm()`(L343-348 直接回调并 pop)，不经过 render，用户看不到任何重排；quickRow 只在 multi 时挂载(L139-141)，单选下无全选/反选/清空，底部按钮是「取消」(L174)；selected 至多 1 个元素，分区结果至多前移 1 行且不可见。ForwardPicker/BatchPicker 无 multi 参数、恒多选，不涉及。空 section：两处循环开头都 `if (s.items.isEmpty()) continue`(PickerPage L408 / ForwardPicker L426)，连标题一起隐藏，不留空 Header 行。空列表：`for (s in list)` 不执行，visible 为空、getCount=0；`selectedFirst(emptyList)` 返回空(单测 L193-195)。重复 id：partition 不去重、保序，同 id 多行一起前移且组内原序(单测 L169-173)。selected 含列表不存在的 id：`in` 判定只作用于实际存在的元素，不产生幽灵行、不影响顺序(单测 L163-165)。崩溃面：纯函数无按索引直取入参；`itemAt` 用 `getOrNull`(PickerPage L426 / ForwardPicker L442)、点击回调 `?: return`(L151 / L290)，Header 行 itemAt 返回 null 且 isEnabled=false(L441 / L449)，getView 用 when 穷举 sealed class(L444-453) 或 `item==null` 分支(ForwardPicker L452-465)，无越界/空解引用。

[FAIL] 问题6 — 证据: BatchPicker.kt:24-36,69,76,87-89,126-131,191,204-218; PickerPage.kt:409,415; ForwardPicker.kt:427,431; PickerTabLogicTest.kt:120-128; MainPage.kt:80,107; MassSendPage.kt:219; GroupManagerPage.kt:115; GroupDetailPage.kt:139,300-327; MemberPage.kt:81; GroupPicker.kt:86-95; SettingPage.kt:34 — 说明: 存在第三个「展开可见行」入口，且未被覆盖。全树 grep `visible.add` / `Row.Entry` / `Row(` 除两个已知入口(PickerPage.kt:409,415 与 ForwardPicker.kt:427,431)外，还命中 `BatchPicker.kt:208 visible.addAll(all)` 与 `:214 visible.add(f)`；其余命中(MuteHuntLogic.kt:182 等 Row 定义、SettingPage.kt:34、MainPage.kt:150、LabelHuntProbe.kt:749、MuteHuntLogicTest.kt:23)是数据类/设置页行/条件判断，不是列表可见行展开。BatchPicker 是确凿的多选选人组件：类注释 L24-36 写明「批量勾选加人」，`selected = LinkedHashSet(db.memberWxids(groupId))`(L69) 带已有成员预选、`FriendAdapter(ctx, all, selected)`(L76)、全选/反选/清空按钮(L87-89)、行点击勾选(L126-131)；其 `FriendAdapter.filter`(L204-218) 独立重建可见行(clear→addAll/add→notify)，`visible` 初值也是全量原序(L191)，**全程没有调用 selectedFirst**，因此「已选中的人排前」对该组件不生效（打开时预选成员不排前，搜索/清空搜索后也不排前）。范围核对：测试注释 L120-128 列举的四类场景（名单/允许·排除名单/群发/转发目标）经调用点核实确实只走两个已知入口（MainPage.kt:80 明确「所有 ENTER 类配置项都推同一个 PickerPage」，L107、MassSendPage.kt:219 亦然），BatchPicker 属分组管理→批量加人（GroupManagerPage.kt:115、GroupDetailPage.kt:139、MemberPage.kt:81），不在四类内；GroupPicker.kt:86-95 虽带勾选态但直接 `addView(cb)`、无 visible 集合、选择对象是分组而非人，GroupDetailPage.MemberAdapter(L300-327) 用 `shown` 字段且只有 remove、无勾选集，二者均不计入。结论：「所有多选选人组件都被覆盖」不成立，判 FAIL。性质为覆盖缺口而非崩溃/错序回归（BatchPicker 保持改动前原序，行为安全）；`selectedFirst` 是泛型 + idOf，对它只需 `selectedFirst(all, selected){ it.wxid }` 一行即可补齐，成本极低。处置二选一：补齐该入口，或在需求/文档中显式声明分组管理入口不在本次范围内。

[PASS] 问题7 — 证据: 提交 4ac6aae 的 diff; PickerTabLogic.kt:56-73; PickerPage.kt:410-416; ForwardPicker.kt:428-433; PickerTabLogicTest.kt:119-196 — 说明: 回滚路径清晰、隔离性好。本改动只落在 4 个文件、且排序逻辑只有 `selectedFirst` 一个来源，没有数据迁移、没有写回数据源、没有跨模块耦合。最小回退（行为完全回到改动前且可编译）只需还原 2 个生产调用点：① PickerPage.kt:414-416 改回 `for (it in s.items) visible.add(Row.Entry(it))`（并删 L410-413 注释）；② ForwardPicker.kt:430-432 改回 `for (it in s.items) visible.add(Row(null, it))`（并删 L428-429 注释）。此时保留 PickerTabLogic.kt:56-73 的纯函数与 PickerTabLogicTest.kt:119-196 的测试类不影响编译与运行（函数无调用方、测试仍引用它且仍通过），故这是最小集。若要彻底清除痕迹（完整回退），再删 PickerTabLogic.kt:56-73 与 PickerTabLogicTest.kt:119-196 两个新增块。⚠️ 不要直接 `git revert 4ac6aae`：该提交 stat 显示共 26 文件 / +5325 行，还含朋友圈视频导出等无关改动，整体回滚会误伤；应只手工还原上述行。两套选择器的 selected 字段、source/sections、Tab 逻辑均无需改动。

## 总体结论

**需修复**

7 条中 6 条 PASS、1 条 FAIL。核心两入口（PickerPage.Adapter.render、ForwardPicker.TabAdapter.reset）在稳定分区、三档边界、引用共享、勾选不重排、异常输入健壮性、可回退性上均成立，未发现崩溃、错序、勾选丢失或搜索清空后回不到全量的风险；唯一发现是覆盖缺口（问题6），不构成核心链路的功能回归。若确认分组管理入口不在需求范围内，本结论可降级为「可装包 + 已知范围外差异」，但需把该边界显式写进需求/文档，而不是留在隐含状态。

## 问题清单

1. **[FAIL / 问题6] 第三个「展开可见行」入口未被覆盖**：`BatchPicker.FriendAdapter.filter`(BatchPicker.kt:204-218，命中 L208/L214 的 `visible.add`) 是带预选 selected 的多选选人组件，未调用 `selectedFirst`，「已选中的人排前」对它不生效。性质：需求覆盖缺口，非崩溃/错序回归（保持改动前原序，行为安全）。建议二选一：在 L204-218 用 `PickerTabLogic.selectedFirst(all, selected) { it.wxid }` 补齐同口径（含 L191 初值），或显式声明该入口不在本次范围内。

[DONE]
