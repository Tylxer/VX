# R73-L3 · 原生菜单 API 注入改动 · 只读对抗复核

> 子代理：r73-l3-review（只读，装机前门禁）　批次：R73
> 审查对象：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt`（git diff 约 +201/-35，含 `NativeMenuGate` / `install` / `installNativeMenuInject` / `onShowGridMenu` / `nativeStatus`）
> 设计依据：`docs/_r73_native_menu_inject.md`（224 行）
> 纪律：只读——未改源码、未碰设备/adb、未跑 gradle、未 commit。证据档：源码行号 + 已落盘 dex 反汇编（`_cache/_chatroom4_dis.txt`、`_cache/_r11_dex7_dis.txt`、`_cache/_s9dump/classes{4,7}.txt`），不重新扫 dex。

---

## 0 结论摘要

**未发现可确认的 P0 缺陷**（无崩溃、无注入全失败、无双入口、无误触原生转发）；DEX 证据链闭合，机制成立。判定 **可装机（带强制真机验证清单）**。

但有三处需如实标注：① 设计文档两处 `[推断]` 与 DEX 实况不符（§2/§3 详述）——其中 `o()` 内 `p()` 是**互斥分支**而非双调（风险更低），`s3.C` 是**行位置标志**而非 isVisible（不影响渲染）；② 代码**未实现设计文档 §6 建议的自动回退**，若真机出现"菜单不弹/原生 add 失败"，功能彻底失效且无降级（P1-1）；③ 复核期间队友 r73-l3b-gate 落盘了 **L3b 门禁修复**（五条件→四条件，删 `talker`），本复核已基于最新盘面重做门禁项并确认该修复**正确且安全**（§1-3、§2.4、§3-P0）。

> **盘面变更说明**：我最初的门禁结论基于旧五条件版本；R73-L3b 把 `NativeMenuGate.shouldInject` 从 5 参改 4 参（删 `talker.isNotBlank()`），`onShowGridMenu`(:324) 调用同步改 4 参，单测 32→16 组并新增 `gateIgnoresTalker`。下文所有门禁结论均以**最新四条件**为准。

### 0.1 审查方法与证据口径

本复核不重新扫 dex，只做三件事：
1. **读 diff**：`git diff -- wxgroup/.../ForceForward.kt`（+201/-35），锁定新增的 `NativeMenuGate`、`installNativeMenuInject`、`onShowGridMenu`、`nativeStatus`，以及被改写的 `findMenuBox`。
2. **追调用闭包**：确认 `install → installNativeMenuInject → onShowGridMenu → onForwardClick → snapshotNow → pressedView/msgRefOf/currentChatTalker`，以及自绘路径 `installPopupInject → tryInject → injectWhenReady → addItems` 已断开。
3. **核 DEX 反汇编**：对每一条"设计文档声称"的宿主事实，回到 `_chatroom4_dis.txt` / `_r11_dex7_dis.txt` / `_s9dump/classes*.txt` 找原始指令，避免把 `[推断]` 当成 `[已证实]` 来放行。

证据等级沿用设计文档：`[已证实]`=反汇编/源码直接可见；`[推断]`=外推；`[需真机]`=有锚点无实测。本复核发现的**新取证**均标为 `[已证实]`（§2），未把任何 `[需真机]` 项当作已通过。

---

## 1 逐项核查结论表

| # | 核查项 | 结论 | 行号 / 理由 |
|---|---|---|---|
| 1 | hook 时机与签名 | **通过**（1 观察） | `installNativeMenuInject`:274-297、`onShowGridMenu`:300-305：`hookAll(cls,"p",cb)` 挂到的 `wg4/q` 仅**一个** `p(II)Z` 方法（`p:I` 是字段，`declaredMethods` 不含它，classes4.txt:689176/689518），`(II)Z` 过滤精确，无误判/漏判。时机：原生 add 发生在 `wg4/a.onItemLongClick`(0x8b0f98 clear→0x8b0fc4 onCreateContextMenu→0x8b1000 q.n) 与 `wg4/b.onLongClick`(0x8b108e clear→0x8b10a2→0x8b10d0 q.n)，均早于 `p()`；before-hook 在原生 add 之后，故我方为"最后 add"→排最后。`o()` 的 `p()` 两处调用（0x8b1e90 / 0x8b1e9c）是 if/else **互斥分支**，单次只调一次。幂等成立：`r3.findItem(I)`(0x8fa030) 逐项比 `getItemId()`=s3.g，而 `r3.add`(0x8f9ea0) 把第 2 参写入 s3.g(0x8fabf4)。`clear()` 仅在长按入口，不在两次 p 之间。**观察**：before-hook 早于 p() 的早退检查（P2-3）。 |
| 2 | 菜单对象获取 | **通过** | `wg4/q.y` 类型实测 `Lva4/r3;`（classes4.txt:689175-689177）；`va4/r3` 实现 `Landroid/view/ContextMenu;`（classes7.txt:679617），而 `ContextMenu extends Menu` ⇒ `as? android.view.Menu`（:309）安全转换成立。用的是 `as?` 非 `as`，y 为 null/异常都返回 null，无 ClassCastException。接口调用落到 r3 已证实实现：`add(IIILCharSequence;)`@0x8f9e88、`findItem(I)`@0x8fa030、`size()`（q.p 0x8b213c 调用点）；`setOnMenuItemClickListener` 落到 s3.y 字段，类型 `MenuItem$OnMenuItemClickListener`（classes7 s3 块 #24）。8.0.48 已证实，换版本需重核混淆名（P2-4）。 |
| 3 | 门禁 / 10s TTL | **通过**（四条件已修；TTL 为既有行为） | **门禁现为四条件**：`NativeMenuGate.shouldInject`:63-77 = `enabled && lastPressAt>0 && age<=WINDOW_MS && hasRef`，调用 :315-333。旧五条件含 `talker.isNotBlank()` 且真机 `talker` 恒空 → 门禁恒关、注入全失败（实质 P0），已由 L3b 修复，本复核确认修复正确（§2.4）。**hasRef 单条足够**：`msgRefOf` 的 list 只认 `listKindOf` 继承链精确命中 `ChattingRecyclerView`/`ChattingListView`（:1766-1791），会话列表/输入栏/图片浏览菜单返回 `unknown`/`notList` → hasRef=false，不会误注入。**TTL**：add 在长按瞬间（4s 窗内），点击回调 `onForwardClick`:626 → `snapshotNow`:637 → `pressedView`:793-797 走 `BURST_TTL_MS=10s`；>10s 慢选 → pressedView 返回 null → snapshot UNKNOWN → SKIP（不发错消息，但功能失效）。**非回归**：停用的自绘路径 `addItems`:529-533 同款 TTL。残余：`pressedView` 取"最近一次长按"(10s 窗)而非"本次菜单"，跨菜单 4s 内有极窄误注入窗（P2-3）；`talker` 恒空使 `snapshotOf.sourceChat`:665 亦空 → 选人器无法排除当前会话（P2-5）。 |
| 4 | 点击消费 | **通过** | `s3.c()`@0x8faa34 反汇编：`if(y==null) return false; return y.onMenuItemClick(this)` ⇒ lambda `return true`(:352) 使 s3.c() 返回 true。`j5.onItemClick`@0x62a1a8：0x62a22a 调 s3.c()→v8；0x62a248 `if-eqz v8,0060`，v8=0 才走 0x62a280 `onMMMenuItemSelected`（原生 e0）；v8≠0 仅 `sg4/d1.q()`(0x62a26a) 清理后返回。证据链闭合，不误触原生转发。SAM lambda 实现的是 framework 接口（boot classloader），非宿主混淆类，Xposed 安全；`item==null` 防御合理（:348）。 |
| 5 | 双入口 | **通过** | `install`:212-221 只调 `installNativeMenuInject()`，`installPopupInject()` 已注释(:219)。`tryInject` 唯一调用点 = `installPopupInject` 回调(:255)；`addItems` 唯一调用点 = `injectWhenReady`(:426)，其唯一入口 `tryInject`(:382)。全仓 `ForceForward.install` 仅 HookEntry.kt:184 一处。无自绘 TextView 入口。`nativeInjectedOk`:190/361 仅状态标志，未接回退（P1-1）。 |
| 6 | 崩溃面 | **通过** | `findClass`/`hookAll` 全 runCatching(:281/295)；hook 回调 :289-292 `runCatching{onShowGridMenu}` 兜底，异常只打日志不冒泡；`onShowGridMenu` 内取 menu(:308)/门禁(:320)/findItem(:336)/add+icon+listener(:339-356) 全 runCatching；`hookAll`:1813-1823 逐方法 runCatching。`currentChatTalker()`(:317) 内部 intent 读取亦有 runCatching(:830)，且整体在总兜底内。未见裸调用。 |
| 7 | itemId 冲突 | **通过** | `ITEM_ID=0x5747_0001` 高位正整数（NativeMenuGate:46），宿主 `e0` 用 `const/16 #111/#118` 小整数比较，不会命中。`setOnMenuItemClickListener` 只挂我方新建的 `mi`(:340-354)，未触碰任何原生 s3 的 y。补充：r3.add 把 itemId 写 s3.g，findItem 比对一致，幂等自洽。 |
| 8 | 与既有逻辑无回归 | **通过**（边界说明） | diff 未改 `onForwardClick/snapshotOf/msgRefOf/pressedView/currentChatTalker/hookAll` 实现，仅新增调用。`ForwardLogic.classifyMenu` 与 `CHAT_MENU_WORDS/CONV_MENU_WORDS` 词表**未被本次触碰**（ForwardLogic.kt diff 只新增 `MenuNode`/`MenuBoxCandidate`/`findMenuBoxCandidate`）。边界：同工作区 `ForceForward.findMenuBox`:494-521 与 ForwardLogic 新增纯函数属自绘路径的 R73 根因修复，该路径**当前已停用**，不影响原生方案，但需连带编译通过。`HookEntry.kt`/`EntryReceiver.kt` 改动是无关的只读探针 `MicroxFwdProbe`。 |

---

## 2 关键 DEX 取证（新增证据，纠正设计文档两处推断）

1. **`o()` 内 `p()` 是互斥分支，不是双调**（设计文档 §2.4/§7-3 的「双调」描述偏保守）。
   `q.o`@0x8b1e54~0x8b1ea4：`if(!e()) goto 00e8`；`if(i!=null && i.isShowing) v2=1`；`if(v2){ if(d()){ a() && p()@0x8b1e90; return } }`；`00e8: p()@0x8b1e9c; return`。两条 `p()` 调用互斥。**风险低于文档预期**；`findItem` 幂等仍保留，足够覆盖"同 r3 多次 p"的残余情形。
2. **`s3.C` 是行位置标志，非 isVisible**（设计文档 §7-4 的 `[推断]` 不成立）。
   `q.p` 主渲染循环 0x8b2784：`iget-boolean s3.C`；`if(C==false) goto 0438`。C=true → `v6.addView`（主网格，0x8b2798）；C=false → `v5.addView`（次级行，0x8b27ac）或回退 v6。**两分支都 addView**，我方条目 C 默认 false（s3 构造 0x8fabe0 置 0）仍会渲染，不会"被跳过"。视觉上可能落次级行，需真机核对位置。
3. **前置 `any-C` 检查不门控渲染**：0x8b251a~0x8b252e 若无任何 C=true 项则跳 0x0347，但 0x0347 之后仍进入主渲染循环（0x8b25f8 `r3.size()`），只是跳过一段 reset/visibility 设置。故无"整菜单不渲染"风险。
4. **`va4/r3` 实现的是 `ContextMenu`**（非直接 `Menu`），`ContextMenu extends Menu` ⇒ 接口转换成立。`q.y` 字段类型确为 `Lva4/r3;`。
5. **itemId 落点闭环**：`r3.add(IIILCharSequence;)`@0x8f9e88 → `s3.<init>(ctx, itemId, groupId)`@0x8f9ea0 → `getItemId()` 返回 s3.g@0x8fab4c → `findItem` 比对 s3.g@0x8fa06e。传参 `(GROUP, ITEM, ORDER, TITLE)` 的 ITEM 正确落到 s3.g；`order` 参数未被构造器使用（印证"按插入顺序渲染"）。

### 2.4 L3b 门禁修复的正确性复核（`talker` 删除）

队友给出的真机日志 `gate closed enabled=true age=151 talker= ref=true` 是**决定性证据**：长按普通聊天消息、hasRef=true 时 talker 仍为空，旧门禁必被挡死。源码侧交叉验证：

1. **`Chat_User` 不在 Activity intent 上**：`ChatOpenProbe.kt:22` 明确「会话 Intent（带 `Chat_User`）挂在 **Fragment 内部的对象**上（实测在 3~4 层深）」；`currentChatTalker()`:828-832 读的是 `HostContext.activity().intent["Chat_User"]`，自然恒空。修复方向正确。
2. **删 talker 不增加误注入面**：`msgRefOf`:685-747 里 `list` 只由 `listKindOf(node)` 返回严格 `"recycler"/"listview"` 时才赋值（:694-695），而 `listKindOf`:1766-1791 只认**继承链上精确命中** `ChattingRecyclerView`/`ChattingListView` 两个聊天列表基类；其余列表一律 `unknown`/`notList`，`list` 不占位 → `msgRefOf` 早退 → hasRef=false。兜底 `outer` 又要求父链含 `MMChattingListView`。故 `hasRef=true` 几乎等价于"聊天消息长按"，能独立承担原来 talker 承担的"确在聊天页"职责。
3. **回归锁到位**：`NativeMenuInjectTest.gateIgnoresTalker` 直接构造"talker 不存在但其余三条件成立"断言放行；`gateTruthTable` 16 组穷举（`32→16`）与四参实现一致；`gateWindowBoundary`/`gateRejectsWhenAnySingleConditionFails` 覆盖每条。等价变异（M1 去 hasRef / M2 去时间窗）会让真值表变红。
4. **talker 未彻底删除是正确的**：:317 仍取 `currentChatTalker()` 仅供 gate closed 日志诊断，:665 `snapshotOf.sourceChat` 与 :843 `openTargetPicker` 仍用它作选人器排除集——保留不坏，但因其恒空，排除集实际失效（见 P2-5，非本门禁修复引入）。

**结论**：L3b 四条件修复**通过**，是本次落盘中唯一消除"真机注入全失败"的关键改动；保留意见仅 §3-P2-5。

---

## 3 问题清单（按严重度）

**P0（会崩 / 注入全失败 / 双入口 / 误触原生转发）：无遗留确认项；发现并已消除 1 项。**

- **P0-RESOLVED · 旧五条件门禁 talker 恒空 ⇒ 注入全失败**：真机日志 `gate closed enabled=true age=151 talker= ref=true` + `ChatOpenProbe.kt:22`（`Chat_User` 在 Fragment intent 非 Activity intent）证明旧 `talker.isNotBlank()` 门禁恒关。已由队友 r73-l3b-gate 修复为四条件（删 talker），本复核确认修复正确且安全（§2.4），当前盘面无此缺陷。
- 真机硬门槛见 §4，属"待验证"而非"已发现缺陷"。

**P1-1 · 无自动回退，原生方案失败即功能全失（建议修，非阻断）**
`ForceForward.kt:190/361` 的 `nativeInjectedOk` 只写不读；`install`:212-221 已把 `installPopupInject()` 注释(:219)；`onShowGridMenu` add 失败分支(:357-360) 仅打日志返回。设计文档 §6/§7-2 明确"场景 b（屏蔽消息连菜单都不弹）本方案整体失效"。当前无 `nativeInjectFailed` 开关、无自绘降级、无"长按直取 h9 + ForwardPicker"兜底。
**修法**：最小改动——让 `nativeStatus()`(:1408) 暴露失败计数便于装机观测；若要真正兜底，按文档 §6 另开"长按直取 h9"路径（注意文档自承场景 b 下自绘也无 PopupWindow 可挂，故自绘不是有效降级）。

**P1-2 · 门禁 4s 与快照 10s 不同步导致慢选失效（既有行为，建议仅观测）**
`NativeMenuGate.WINDOW_MS`:51=4s vs `BURST_TTL_MS`:131=10s。add 后用户 >10s 才点 → `pressedView()`:793-797 过期 → SKIP。停用的自绘路径同样行为，非回归。
**修法（可选，会改既有语义，不建议本轮动）**：`onForwardClick`:626 内把 `snapshotNow()` 的 `pressedView()` 换 `staleViewForProbe()`(:1800) 或放宽 TTL。

**P2-1 · 窗口常量双定义，注释声明"同值"但无锁定**
`NativeMenuGate.WINDOW_MS`:51 与 `ForceForward.LONG_PRESS_WINDOW_MS`:125 均 4000L，分离定义；单测 `NativeMenuInjectTest.constantsAreLocked`:23-28 只锁 NativeMenuGate 自身。
**修法**：`NativeMenuInjectTest` 加 `assertEquals(NativeMenuGate.WINDOW_MS, ForceForward.LONG_PRESS_WINDOW_MS)`（后者需 `internal`  visibility）或让常量引用单一来源。

**P2-2 · 我方格子可能落次级行（s3.C 默认 false）**
见 §2-2/§2-3。DEX 确认会渲染但可能不在主网格末位。**无需改码**，真机 P2 探针核对位置即可。

**P2-3 · before-hook 在 p() 早退检查之前 add**
`onShowGridMenu`(:300) 在 `q.p`(0x8b1f3c `e()`/0x8b1f52 `isShowing`/0x8b1f68 `d()`/0x8b1fda swiping 早退) 之前就 add。菜单被抑制时我方条目暂留 r3，靠下次长按 `clear()` 清理。低概率误注入（需"刚长按消息→未弹菜单→同 q 实例复用展示别的菜单"）。
**修法**：可接受（下次 clear 清理）；若要严，add 前用 `XposedHelpers.callMethod(param.thisObject,"e")/`d()` 自检后再加。

**P2-4 · 图标 `0x7f0f0529` 与混淆名版本相关**
`NativeMenuGate.ICON`:48 仅在 8.0.48 有效；换微信版本需重解析资源 id 与 `wg4.q`/`y`/`p` 混淆名。文档已标注。

**P2-5 · `talker` 恒空使选人器"排除当前会话"失效（L3b 修复暴露的既有缺口，非门禁缺陷）**
`currentChatTalker()`:828-832 恒空（§2.4），故 `snapshotOf.sourceChat`:665 与 `openTargetPicker`:843 的 `snap.sourceChat.ifBlank{currentChatTalker()}` 均为空 → 选人器不会把当前会话从候选里剔除，用户可把消息转发回原聊天。此行为在旧五条件门禁下被"根本不注入"掩盖，现注入打通后浮现。
**修法**：若要修，按 `ChatOpenProbe.kt:100` 的成熟做法——从 Fragment 对象图里取带 `Chat_User` 的 intent（3~4 层深），或 hook `Chat_User` 可见的生命周期点缓存 talker；属选人器范畴，建议另开任务，不并入本轮门禁修复。

### 3.1 为何这些 P2 不升为 P1

- **P2-1/P2-4** 是"未来漂移"风险，当前 8.0.48 取值正确，装机不会触发。
- **P2-2** 已由 DEX 证伪"被跳过"，唯一残余是视觉位置，属观感且可在真机一眼确认。
- **P2-3** 需要"刚长按消息 → 菜单被抑制 → 同一 q 实例立刻复用于别的菜单"的三连条件；而每条长按入口都以 `r3.clear()` 开头，窗口极窄，且我方门禁还要求 `talker`+`hasRef` 同时成立，误注入概率可忽略。
- **P1-2** 明确是既有 TTL 语义（自绘路径同款），本轮改动只是换了"谁触发 onForwardClick"，没有引入新的过期面；升级为 P1 会与"无回归"结论自相矛盾。
- **P2-5** 是选人器排除集的既有缺口，与"能否注入"正交；旧门禁下被完全掩盖，现注入打通才浮现。属功能增强而非装机阻断。

因此唯一需要主会话决策的是 **P1-1（回退策略）**，其余均为"装机验证时顺带看一眼"级别。L3b 门禁修复已把唯一的 P0（注入恒失败）消除，可装机门槛已满足。

---

## 4 总判定与装机验证清单

**总判定：可装机（可安装到真机验证）。**
理由：核查 8 项全部通过（基于 L3b 四条件最新盘面；带 5 项 P2 观察 + 2 项 P1 建议），未发现遗留的崩溃、注入全失败、双入口或误触原生转发确认缺陷；DEX 证据链（add 时机 / 接口转换 / findItem 幂等 / s3.c→j5.onItemClick 消费）闭合。发现的唯一 P0（旧门禁 talker 恒空致注入恒失败）已由 r73-l3b-gate 修复并复核通过。P1-1 是"失败后无降级"的韧性问题，不阻断本次装机，但**装机后必须按下列清单验证**；若验证失败，当前代码无自动回退，需人工回退或另补 P1-1。

**装机后只读验证清单（前 3 项为一票否决）：**
1. `op=forward sub=stat`：`nativeInjectedOk=true`，logcat 有 `FF NATIVE added ...` 与 `FF NATIVE installed wg4.q.p x1`。
2. 普通文本消息长按 → 菜单出现"转发到多个（模块）"且带图标；**点它 → 弹模块选择器，且不触发原生转发**（验 §1-4 消费链）。
3. 会话列表长按 / 输入框长按 → **不出现**我方格子（验门禁 §1-3 无误注入）。
4. 同一次长按内 → 只出现 **1** 个我方格子（验 findItem 幂等 §1-1）。
5. 我方格子位置应为**最后**（验 §2-2/§2-3 行位置）。
6. 长按后停留 >10s 再点 → 观察是否 SKIP（验 P1-2）。
7. 图片/语音/`announcement@all` 被屏蔽消息 → 是否弹菜单并含我方格子（验 P1-1 场景 a/b）。
8. 普通转发项（111/118）点击 → 仍走原生转发（验未覆盖原生监听 §1-7）。
9. **（L3b 新增）** 长按普通聊天消息：此前 `talker=` 空被挡死，修复后应能注入；确认 `gate closed ... talker= ref=true` 不再出现且格子出现（验 §2.4 门禁修复）。
10. **（L3b 新增）** 转发目标选择器里当前会话仍可被选中 → 观察是否符合预期（验 P2-5 排除集失效）。

---

## 5 修法建议汇总（精确到行号）

| 优先级 | 文件:行 | 建议 |
|---|---|---|
| P1-1 | `ForceForward.kt:1408`（`nativeStatus`）/ `:357-360`（add 失败分支） | 暴露失败计数；如要真降级另开"长按直取 h9 + ForwardPicker"路径（非本轮） |
| P1-2（可选） | `ForceForward.kt:626-637`（`onForwardClick`/`snapshotNow`） | 若真机确认慢选失效，换 `staleViewForProbe()` 或放宽 TTL（会改既有语义，谨慎） |
| P2-1 | `NativeMenuInjectTest.kt:23-28`；`ForceForward.kt:51` 与 `:125` | 加跨常量等值断言或单一来源 |
| P2-3（可选） | `ForceForward.kt:300-313`（`onShowGridMenu` 取 menu 后、add 前） | add 前自检宿主 `e()/d()` 状态，避免抑制菜单时残留条目 |
| P2-2 / P2-4 | 无需改码 | 真机核对位置；换版本重解析图标/混淆名 |
| P2-5（可选，另开任务） | `ForceForward.kt:828-832`（`currentChatTalker`）/ `:665`/`:843` | 按 `ChatOpenProbe.kt:100` 从 Fragment 对象图取 `Chat_User`，恢复选人器排除当前会话；不并入本轮门禁 |
