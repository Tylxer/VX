# R61 — 分组行长按改用微信原生菜单：链路取证 + 方案设计（只读取证，v3 定稿）

> 用户诉求：R60 只把自绘 `GroupRowMenu` 末项文案改成「解散聊天分组」；用户否定「自绘」方向本身——
> 「长按的是自绘，我希望跟微X一样调用微信自带的」。即 gconv=1 长按 `wxgrp_` 伪会话行要弹**微信原生 PopupWindow 菜单**，
> 再像微X那样往原生菜单里插项，不再弹 AlertDialog。
>
> v3 修订（覆盖 v2 §0.1 决策1）：用户最终拍板「**加入分组只在正常会话（真实 wxid）长按时显示；分组行不插这一项**」
> （原话「只在正常会话长按时显示是否可以」）⇒ 分组行原生菜单收敛为 **5 项**；并据主会话复核修正 v2 硬伤
> （v2「全局删除标记已读」会破普通会话零差异）——改为 `addItems` 按 gid 分流，真实会话分支逐字不动。
> 其余（三开关基线、过时结论裁决、点击链不动、A1/A2/A3、危险项双层拦截、无开关直接替换）全部保留。
> 本轮零代码改动、未跑 gradle、未碰设备。主会话持设备锁与构建锁。
>
> 判据纪律：只写「用户能看见的事实」；互斥判据不并列；「A 推 B」时序结论一律标假设，只认 invoke 地址单调递增或真机日志时间戳。

---

## 0. 前提、基线与裁决（先读，决定后面所有结论）

### 0.1 用户已拍板的决策（权威，v3 以此为准）

- **决策1 · 菜单项集合（v3 最终，两条路径分开）**：
  - **分组行（伪会话 `wxgrp_<hex>`）原生菜单 = 5 项**，逐字与顺序：
    1. `解散聊天分组` —— 我方，**放最前**（prepend）；语义 = 现有「删除分组」，只解除归组、成员不丢。
    2. `标为未读` —— 宿主原生，保留。
    3. `取消置顶` —— 宿主原生，保留（置顶状态不同时对应的宿主项）。
    4. `不显示该聊天` —— 宿主原生，保留。
    5. `删除该聊天` —— 宿主原生，保留（⚠️ 对伪会话行危险，见 §2-A1 / §3.3 拦截）。
    - **不含** `加入分组`、**不含** `标记已读`、**不含** `归拢设置`（归拢设置语义未知，`_r55c:91-92`）。
  - **正常会话（真实 wxid）菜单**：**逐字保持现状**——宿主 4 项 + footer 的「加入分组 + 标记已读」两项，一个字不改。
  - 用户原话依据：「只在正常会话长按时显示是否可以」⇒「加入分组」只在真实 wxid 长按时出现，分组行不插。
- **决策2 · 上线策略**：**直接替换、默认开、无独立开关**。不加 `chat.gconv_native_menu`；gconv=1 直接用原生菜单、自绘长按不再走。
  ⇒ 回滚改为**改代码 / git 回退**，不靠开关；⇒ 因为没有开关兜底，**代码必须自己安全**——§3.3 给出对「删除该聊天/不显示该聊天」的具体拦截方案。

### 0.2 真机基线（三开关，设备 467cf5bc / 微信 8.0.48，主会话 22:5x 读回）

| 开关 | 当前值 | 对 R61 的意义 |
|---|---|---|
| `chat.gconv_enabled` | **true** | 走路线 E，伪会话行存在，R61 前提成立 |
| `chat.conv_menu_enabled` | **true** | ConvMenuInject 注入链开着，能做 prepend/footer 注入 |
| `chat.conv_longclick_remap` | **false** | 弃用态：**不会**抹掉原生 4 项菜单，放开长按的前提成立（`conv_list.md:69`、`Keys.kt:116-126`） |

- ⚠️ **需确认项**：`chat.conv_longclick_remap` 必须保持 false。若真机/备份把它置 true，放开长按后原生 4 项菜单会消失（包装回环，`conv_list.md:69,83`）。上线前 `kvget` 复核一次。

### 0.3 结论冲突裁决：`conv_list.md:81`「无可挂 hook」是**过时结论**

- `docs/conclusions/conv_list.md:81` 写「分组行长按自己弹 AlertDialog，别注入微信的长按菜单（那个 PopupWindow 无可挂 hook）」——这是**历史演进的早期判断**。
- **推翻证据（已证实）**：同文件 `:70-74` 明确列出注入方案 hook `View.performLongClick` + `PopupWindow.showAsDropDown/showAtLocation`；`feature/ConvMenuInject.kt:159-167` 已实装；`docs/_r55c_longpress_menu.md:50-78` 真机日志 `CMI popup injected wxid=... kind=footer` + `va4.j3/va4.m4` 在 `classes4.dex` 命中，证明**宿主的原生菜单 PopupWindow 我们能 hook、能插项**。
- ⇒ **R61 的整个前提就是「注入链已验证可用」**。不要因为读到 `:81` 就得出「改原生菜单不可行」的错误判断；`:81` 仅在「gconv=0 历史自绘路径」语境下仍描述旧事实，对 gconv=1 已失效。
- ⇒ 直接回答 A3 的核心：微X 能弹原生菜单不是因为宿主菜单不可 hook，恰恰相反——那 8 项里的宿主 4 项 + 微X 2 项 + 我方 2 项都在**同一条可 hook 的 PopupWindow** 里（详见 A3）。

### 0.4 范围红线：点击链不动，只动长按

- `_r53_microx_grouprow.md:529-531, 564-581` [已证实]：**点击**放行我方 listener ⇒ 微信把 `wxgrp_` 当普通会话打开「对方还不是你的朋友」空聊天页（死路）。原文定案「把我方 listener 去掉、放行给原生 = 死路」。
- ⚠️ 这条**只针对点击**，不外推到长按。点击必须继续走 `GconvPage.open`（路线 E，已验收），对应 `feature/ConvFilter.kt:1833 v.setOnClickListener { GconvPage.open(...) }`——**该行本轮一个字都不动**。
- R61 只动 `ConvFilter.kt:1834-1837` 的 `v.setOnLongClickListener {...}` 这一条。两者是不同的 listener，不要因为「点击放行=死路」就误判长按也不能放开——用户要的正是长按弹原生菜单。

---

## 1. 结论摘要

**推荐（已被用户拍板锁定）：gconv=1 长按 `wxgrp_` 行直接放开给微信原生菜单，分组行只 prepend「解散聊天分组」1 项（共 5 项）；真实会话菜单逐字不动（仍有「加入分组 + 标记已读」。无独立开关，默认开；回滚靠 git。**

一句话理由：宿主原生菜单 PopupWindow 已证可 hook 可插项（`_r55c`），「像微X一样调用微信自带的」技术上走得通；真正的硬骨头不是「弹不弹得出」，而是**原生菜单把伪会话行当真实会话处理**——放开后宿主 4 项里「删除该聊天 / 不显示该聊天」作用到 `wxgrp_<hex>` 上。v3 把两条路径彻底分开：**真实会话分支逐字保持现状（普通会话零差异），伪会话分支只插「解散聊天分组」+ 拦截危险项**，用户要的「加入分组只在正常会话显示」自然满足。

| 关键问题 | 结论 | 档位 |
|---|---|---|
| 分组行放开后弹什么菜单 | 5 项：`解散聊天分组`（最前）+ 宿主 4 项；无加入分组/标记已读/归拢设置 | [推断·高置信]（微X 同类行 dump 实锤） |
| 普通会话菜单 | 逐字保持现状：宿主 4 项 +「加入分组 + 标记已读」footer | [已证实]（代码分流保证，见 §3.2） |
| 点「删除该聊天」后果 | 精确删 `wxgrp_` 行、**不误删宿主真会话**，但会删我方伪会话行且 sync **补不回**，分组行消失（靠 clean 可重建） | [推断·高置信]（代码链确定） |
| z2 重入守卫坑 | 不涉及：放开是**移除**包装，不是新增；两种口径均不包装宿主 list listener | [已证实]（逻辑） |
| 微X 是否替换宿主项 | 否：prepend 2 项，宿主 4 项逐字保留；且宿主菜单**本就可 hook** | [已证实]（真机 dump） |
| 注入拿到的 wxid | 伪会话 id `wxgrp_<hex>`（`fallbackWxid` 路径），非真实群/成员 id；本方案**只用于判分支 + 危险项拦截**，不写任何表 | [推断·高置信]（主会话实证落点） |
| 危险项能否代码层拦住 | 能：注入时包装菜单 ListView 的 item-click + 危险项 View 吞点击，伪会话命中即安全提示 | [推断·可实现]，机制需真机复核（非阻断） |

---

## 2. A / B / C 逐条结论（证据指向文件:行号）

### A1. 放开后弹什么菜单 + 两种口径 (a)/(b) 分别评估 + 危险项后果

**弹什么菜单** [推断·高置信]：宿主标准会话 4 项，逐字「标为未读 / 取消置顶 / 不显示该聊天 / 删除该聊天」。
- 证据：`docs/_r55c_longpress_menu.md:2-3, 20-32, 39-48`——真手长按微X 分组行 `wx__c_19d94cc78fb`（同类**伪会话行**）dump 出 8 项，宿主 4 项逐字如上述 ⇒ 微信对伪会话行一律当普通会话处理、弹标准会话菜单。`docs/conclusions/conv_list.md:82`：长按回调 return false 会冒泡「弹『删除该聊天』/变点击」。我方 `wxgrp_` 是写进 `rconversation` 的真实行（`data/GroupConvPlan.kt:99-103`），同类。
- ⚠️ 我方 `wxgrp_` 行上未单独 dump 过（`_r55c` 按的是微X 行）；真机首验即可升级 [已证实]（判据 V1）。

**两种放开口径必须分开下结论，不合并：**

- **口径 (a)：不 setOnLongClickListener（推荐）** [推断·高置信，可行]
  - 机制：`feature/ConvFilter.kt:1827-1833` 说明 `wrapped.getView` 内部已给行设过宿主 listener，`v` 是微信渲染好的行；我们**不覆盖**即宿主自己的 long-click 生效 ⇒ 弹原生菜单。
  - 风险：我方侧零新增逻辑；唯一风险是宿主原生 4 项对伪会话的危险语义（见下），与口径无关。
  - `View.performLongClick` 仍会被宿主 long-click 调用 ⇒ wxid trap 照常记录 wxid（`ConvMenuInject.kt:127`），注入链照常触发。
  - **结论：选 (a)**，最贴近「交回微信原生」，且不引入任何我方 View 级监听器。

- **口径 (b)：保留覆盖但 return false** [推断，可行但劣于 (a)]
  - 机制：`conv_list.md:82`，return false 冒泡到 ListView 的 OnItemLongClickListener ⇒ 微信按会话处理弹菜单/变点击。
  - 风险点1：`setOnLongClickListener` 会**替换**宿主在 `wrapped.getView` 里设的 View 级 long-click listener（若宿主用的是 View 级而非 list 级），可能破坏宿主原行为；口径 (a) 不碰它，更安全。
  - 风险点2：return false 的冒泡结果「弹菜单/变点击」两可（`conv_list.md:82`），不如 (a) 确定。
  - 关于 z2 重入坑（`:83`）：该坑针对**我方包装宿主 `getOnItemLongClickListener()`** 的路径；(b) 只是设了个 View 级 listener 返回 false，**没有包装 list listener**，故不触发 z2 回环——但 (a) 连 View 级 listener 都不设，更干净。
  - **结论：(b) 能达成弹原生菜单，但多一层 shadow 宿主 listener 的风险，不选。**

**危险项后果（点「删除该聊天 / 不显示该聊天」会发生什么）** [推断·高置信，代码链确定]：
- 「删除该聊天」按 username=`wxgrp_<hex>` **精确删除**，得益于独立前缀（`GroupConvPlan.kt:9-14` 用 `wxgrp_` 而非微X 的 `wx__c_`）⇒ **不会误删宿主真实会话**（证据：wxid 形态独立 + 精确匹配）。
- 但会**删掉我方伪会话行本体**，且 sync **补不回**：`GroupConvStore.kt:83-87, 286-313`——微信删会话通常只删 `rconversation` 行、保留 `rcontact` 联系人；`readExisting` 取两表**并集**（`:305-306`），删掉的 username 仍从 `rcontact` 侧留在 `have` 里 ⇒ `GroupConvPlan.plan`（`:72-80`）只产 **Update** 不产 Insert ⇒ `repairSql` 是 `UPDATE rconversation ...`（`GroupConvPlan.kt:114-116`）作用 0 行 ⇒ **行不会恢复**。这正是 `:85-87` 标注的「半行不会被下一次 sync 自愈」缺陷。
  - ⇒ 可见后果：用户点一次「删除该聊天」，该分组行从列表**永久消失**，直到 `op=gcs sub=clean` 全删重建。GroupDb 里的分组定义 + 成员**没丢**（可重建），但用户视角是「分组行没了」，属破坏性 bug。
- 「不显示该聊天 / 标为未读」会写宿主 flag/unread 字段，可能**覆盖我方 PIN_BASE=2^62 置顶位**（`GroupConvPlan.kt:16, 45-48`，真机有 flag 漂移先例）⇒ 分组行掉出置顶区。
- ⇒ 必须代码层拦截（§3.3）。这是无开关兜底下的**最高优先级**。

### A2. `conversation.z2` 重入守卫坑是否仍存在

**[已证实]（逻辑）不涉及。** 该坑触发条件是「我方**包装宿主 `OnItemLongClickListener`** 后，`conversation.z2` 在 `onItemLongClick` 内回头调 `list.getOnItemLongClickListener()` 拿到 wrapper → 无限回环」（`conv_list.md:83`、`Keys.kt:116-126`）。
- 口径 (a) 不设任何 listener、口径 (b) 只设 View 级 listener 返回 false——**两者都不包装 list listener**，不进入回环条件。
- `chat.conv_longclick_remap` 真机 = false（§0.2），包装本就弃用。两条分组行路径都是 View 级（`ConvFilter.kt:1834-1837` 与 `:1751-1758`），不经 list 包装链。
- 唯一禁忌：不要为拦截危险项去包装**会话列表**的 `getOnItemLongClickListener()`（那会重踩坑）；§3.3 的包装对象是**菜单 PopupWindow 内的 ListView 的 OnItemClickListener**，是另一个 View、另一个 listener，不受 z2 影响。

### A3. 微X 怎么弹原生菜单 / 是否替换宿主项 / 宿主菜单能否 hook

**[已证实] 微X = 往宿主那条 PopupWindow 的 ListView prepend 2 项，宿主 4 项逐字保留；没替换、没屏蔽「删除该聊天」。宿主菜单 PopupWindow 我们能 hook 能插项。**
- `docs/_r55c_longpress_menu.md:20-32, 39-48`：dump 顺序固定 = 微X 2（解散聊天分组/归拢设置）→ 宿主 4 → 本模块 2。宿主 4 项逐字在列。
- `:50-78`：`va4.j3`/`va4.m4` 在 `classes4.dex` 命中（类定义），`child=6` = 我方追加前已有 6 项（微X 2 + 宿主 4）⇒ 排除「微X 自绘仿原生」，确认「往宿主菜单插项」。
- `_r55_microx_click_longclick.md:220-232, 242-249`：`解散聊天分组/归拢设置/归拢` 在宿主 `resources.arsc` 0 命中 ⇒ 微X 注入。
- 结合 §0.3：`conv_list.md:81` 的「无可挂 hook」过时，R55c 已实证宿主菜单可 hook。⇒ A3 关键：**微X 能弹原生菜单不是因为宿主菜单不可 hook，而是宿主菜单本就可 hook，微X 和我们都往里插项**。team-lead 的理解「宿主 4 项保留」正确。
- ⚠️ 「保留」不等于「语义合理」——宿主那 2 项危险项对分组行不成立（`_r55_microx_click_longclick.md:445-447` 风险清单第5条：照抄 = 用户点了不知道会发生什么）。用户决策明确保留，故走 §3.3 拦截。

### B4. `installWxidTrap` 拿到的是伪 id 还是真实 id / 假 id 污染的规避

**[推断·高置信] 拿到的是伪会话 id `wxgrp_<hex>`，不是真实群/成员 id——该风险已识别，本方案通过「伪会话行不插 join/read」规避。**
- 主会话实证落点：`feature/ConvMenuInject.kt:116` 先试 `ConvFilter.wxidAt(list,pos)`；`ConvFilter.kt:571` 对分组行 `groupIdAt != NOT_A_GROUP` 时 `return null` ⇒ trap 落到 `fallbackWxid`（`ConvMenuInject.kt:137-140`）→ `ConvReader.username(adapter.getItem(pos))` ⇒ 拿到 `wxgrp_<hex>`。
- 补充旁证：R55c 日志（`_r55c:55`）抓的是微X `wx__c_19d94cc78fb`，同类伪会话 id 形态。
- **假 id 污染后果（若不处理）**：`onJoin(wxid)`（`ConvMenuInject.kt:732-742`）→ `db.findFriend("wxgrp_xxx")` 必为 null → 构造 `Friend(wxid="wxgrp_xxx", typeOf→UNKNOWN)` → `GroupPicker.show` ⇒ 把**不存在的假好友**塞进 `group_member` 污染数据。
- **本方案规避（v3）**：`addItems` 入口按 `gid = GroupConvPlan.groupIdOf(wxid)`（`GroupConvPlan.kt:30-35`）分流——
  - `gid == null`（真实会话）⇒ 走现有 `onJoin`，拿真实 wxid，零问题；
  - `gid != null`（伪会话）⇒ **只插「解散聊天分组」1 项，根本不构造 join/read**，所以 `wxgrp_xxx` 永不传给 `findFriend/GroupPicker/onMarkRead`。
  - ⇒ 伪 id 在本方案里**只用于判分支与危险项拦截**（§3.3），不写任何表；B4 的污染风险自然消除，无需「给 join 编造语义」。

### B5. 菜单项集合与分流改动点（用户决策1）

- **分组行（伪会话，gid != null）= 5 项**：`解散聊天分组`（最前 prepend）→ 宿主 `标为未读/取消置顶/不显示该聊天/删除该聊天`。**不插** `加入分组`/`标记已读`/`归拢设置`。
- **正常会话（真实 wxid，gid == null）= 现状逐字不变**：宿主 4 项 + footer「加入分组 + 标记已读」。
- **改动点**（`ConvMenuInject.kt:355-441 addItems`）：
  - 入口算 `val gid = GroupConvPlan.groupIdOf(wxid)`；
  - **`gid == null` 分支：现有 `addItems` 代码逐字保留**——`mine = listOf(join, read)`、`TAG_READ`、`onMarkRead`、`:394` 的 `box.addFooterView(read, ...)` 全部一个字不改（**普通会话零差异红线**）；
  - **`gid != null` 分支：另起新分支**，只构造 `dissolve` 1 项，不构造 join/read。
- **prepend 实现**：伪会话分支要让 `addHeaderView` 合法，先确保 box 的 adapter 是 `HeaderViewListAdapter`（必要时加 0 高、isSelectable=false 的占位 footer），再 `box.addHeaderView(dissolve, null, false)`；`dissolve` 用 `AbsListView.LayoutParams` + `isSelectable=false`（`:384-392` 的坑）。⚠️ 前插时机/`addHeaderView` 是否真机可用需复核（D5 降级：不通就放末尾，别上脆弱反射重排）。
- `dissolve` onClick：`db.deleteGroup(gid)` + toast（`GroupRowMenuLabels.deleted(true)`，语义等价 R60，`_r60_menu_label.md:2`）+ 先 `popup.dismiss()`。

### B6. 注入时机：同一条链对新菜单是否仍生效 / 有无「拿不到 wxid」风险

**[已证实]（链路）+ [需真机]（文案判据）生效；trap 一定拿到 wxid（伪 id），但本方案只用其判分支，不用于选注入目标。**
- 注入 hook 在 `PopupWindow.showAsDropDown/showAtLocation`（`ConvMenuInject.kt:159-167`），与「长按是自绘还是原生」无关——R55c 正是在「长按交回宿主/微X 原生菜单」场景成功注入（`_r55c:52-61` 日志）。放开后宿主原生菜单照常弹出，同一条链照常触发。
- trap 挂在 `View.performLongClick`（`:127`），伪会话行是 View ⇒ 一定触发，拿到伪 id（B4），不存在「拿不到」。
- ⚠️ [需真机] `MENU_WORDS`（`:151-154`）≥2 判据在 `wxgrp_` 行上是否命中：微X `wx__c_` 行上 4 项都在（dump 已证），我方同类应同样命中；若不命中 ⇒ **安全降级为不注入**（`:66-72, 147-149`，不会误伤，但「解散聊天分组」也不会出现）。真机首验即知（判据 V2）。

### C7. 用户能看见的真机判据（正反互斥，不并列）

- **V1 · gconv=1 分组行（放开）= 5 项**：长按 `123` 分组行 → 弹出**白底卡片、裸 TextView、无 resource-id**的微信原生菜单（**不是**带标题「123」+「取消」的 AlertDialog）——这一条把「原生 vs 自绘」判死，互斥二选一。菜单项逐字且仅 5 项：最前 `解散聊天分组`，随后 `标为未读/取消置顶/不显示该聊天/删除该聊天`；**无** `加入分组`、**无** `标记已读`、**无** `归拢设置`。
- **V2 · 注入链日志（定位辅助，非用户可见）**：`python tools/dev.py q CMI 30` 有 `CMI target wxid=wxgrp_...`、`CMI popup injected ... wxid=wxgrp_...`；分组行注入项应只有 dissolve（prepend），**无 join/read**。
- **V3 · gconv=0（红线①，零差异）**：长按 `123` 分组行 → 仍是**自绘 AlertDialog**：标题「123」+ 五项「置顶该分组/重命名/管理成员/标记已读/删除分组」+「取消」按钮（`GroupRowMenu.kt:98-107, 109-148`），末项文案逐字「删除分组」**不是**「解散聊天分组」。与 V1 互斥：同一行 gconv=1 见原生 5 项、gconv=0 见自绘 Dialog 不可同时成立；若 gconv=0 也弹原生 ⇒ 破红线①，立即回退。
- **V4 · 「没有误把伪会话当真实会话删除」**：操作前 `op=gcs stat` + 备 `rconversation`/`rcontact` 我方行（记「非本方行数」）；在测试分组上点原生「删除该聊天」→ **应见 §3.3 的安全提示、分组行仍在、会话列表真实会话一条不少**；验后按需 `op=gcs sub=clean` 重建。⚠️ 不要在真实分组上试宿主删除（`_r55c:91-92`）。
- **V5 · 危险项拦截生效**：伪会话行长按菜单点「不显示该聊天」→ 应见安全提示、分组行仍在置顶区（flag 未被覆盖）。
- **V6 · 普通会话零差异（与 V1 互斥）**：真手长按任一真实群会话 → 菜单仍是宿主 4 项 **+ footer「加入分组 + 标记已读」两项**（功能正常：点「加入分组」正常弹 GroupPicker、点「标记已读」正常清未读），且**无**我方危险拦截（宿主删除功能正常）。与 V1 互斥：V1 的分组行菜单没有这两项，V6 的真实会话菜单有；若真实会话也变 5 项 ⇒ 破「普通会话零差异」。

### C8. 回滚路径（决策2：无开关，改代码/git）

- 无 `chat.gconv_native_menu`，默认开。回滚 = **git 回退 R61 对 `ConvFilter.kt` / `ConvMenuInject.kt` 两个文件的改动**即可：`ConvFilter.kt` 恢复 `v.setOnLongClickListener { GroupRowMenu.show(...); true }`，`ConvMenuInject.kt` 恢复原「加入分组/标记已读」成对注入（真实会话分支本就逐字未改，伪会话分支是新增的）。`GroupRowMenu` / `GroupRowView` 全程保留不删，零数据迁移。
- 与 gconv=0 无关（那条本就没动），回滚不碰红线①。
- ⚠️ 若用户真点了未拦住的宿主「删除该聊天」导致伪会话行消失：GroupDb/成员没丢，用 `op=gcs sub=clean` 全删重建即可恢复（`GroupConvStore.kt:193-219`）。

### C9. 变异验证点（判据写完后必须做变异才算数）

| # | 变异动作 | 期望变红判据 | 证明 |
|---|---|---|---|
| M1 | 把 gconv=0 也放开长按 | V3 变红（gconv=0 应见自绘 Dialog） | 红线① 非空转 |
| M2 | 把伪会话分支误插 `join`（`gid!=null` 时也 `addFooterView(join)`） | V1「无加入分组」变红 + 日志 `CMI join wxid=wxgrp_...` 变红 | 「伪会话不插 join」非空转 |
| M3 | 去掉「危险项拦截」（§3.3） | V4/V5 变红（伪会话行真被删/隐藏） | 安全路径非空转 |
| M4 | `MENU_WORDS` 判据 ≥2 改成 ≥1 | 误伤其它浮层回归 | ≥2 判据非空转 |
| M5 | 把「解散」动作改成直删 rconversation 而非 `deleteGroup` | V4 成员丢失判据变红 | 语义等价（成员不丢）非空转 |
| M6 | 把普通会话的 `read` 也删掉（全局移除标记已读） | **V6 变红**（真实会话菜单缺「标记已读」） | 普通会话零差异非空转 |

---

## 3. 推荐实现方案（供主会话审，未落地）

### 3.1 改动文件清单（2 个功能文件；均 Pro 类，须派 Pro 子代理实现）

| 文件 | 改动点 | 档位 |
|---|---|---|
| `feature/ConvFilter.kt` | `:1834-1837` 删除 gconv=1 的 `v.setOnLongClickListener {...}`（口径 a），交回宿主 long-click。点击 `:1833` **一字不动**。 | Pro（Hook 链路） |
| `feature/ConvMenuInject.kt` | `addItems`（`:355-441`）入口按 `gid` 分流：**`gid==null` 逐字保持现有「加入分组+标记已读」footer 注入（普通会话零差异）**；`gid!=null` 只 prepend「解散聊天分组」1 项 + 危险项拦截（§3.3）。 | Pro（Hook + 反射 + 重入） |

- 无需改 `Keys.kt`（无新开关，决策2）。`GroupRowMenuLabels` / `GroupRowMenu` 保留给 gconv=0 与 toast 复用，不改。
- ⚠️ 改动面 2 个独立功能文件且都是 Hook/反射类 ⇒ 按 `CLAUDE.md §四②`，必须派 1 个 `deepseek-v4-pro` 子代理实现，主会话勿自审。

### 3.2 各文件具体改动

**ConvFilter.kt**：仅删 `v.setOnLongClickListener { GroupRowMenu.show(v.context, gid); true }`（:1834-1837）及其上方「长按仍走原 GroupRowMenu」的过时注释；保留 `v.setOnClickListener { GconvPage.open(...) }`（:1833）。开关 `gconvEnabled` 分支仍控制点击路由，不动。

**ConvMenuInject.kt**（核心是「按 gid 分流，真实会话一个字不改」）：
1. `addItems` 入口算 `val gid = GroupConvPlan.groupIdOf(wxid)`。
2. **`gid == null`（真实会话）：现有 `addItems` 代码逐字保留**——`mine = listOf(join, read)`、`TAG_JOIN`/`TAG_READ`、`newItem(onJoin)`/`newItem(onMarkRead)`、`:393-394` 的 `box.addFooterView(join/read, null, false)` 全部不动。**这是「普通会话零差异」在代码里的保证：这条分支不改一个字。**
3. **`gid != null`（伪会话）：另起分支**，只构造并 prepend `dissolve` 1 项（onClick = `db.deleteGroup(gid)` + toast `GroupRowMenuLabels.deleted(true)` + `popup.dismiss()`），不构造 join、不构造 read、不调 `onJoin/onMarkRead`。prepend 用「先确保 `HeaderViewListAdapter`，再 `addHeaderView(dissolve, null, false)`」（§B5；D5 降级见 §5.3）。
4. 新增危险项拦截（§3.3），在 `injectWhenReady`（`:203-264`）注入成功后紧接执行；拦截只在 `gid != null` 时生效，真实会话完全不触发。

### 3.3 ⚠️ 危险项对伪会话的代码层拦截（最高优先级，无开关兜底）

目标：伪会话行长按弹出的原生菜单里，「删除该聊天 / 不显示该聊天」**点了也不能作用到 `wxgrp_<hex>`**；真实会话行完全不受影响（保留宿主原行为）。

**确定可执行方案（双层，均在已验证的注入链内完成）：**

**第 1 层 · 危险项 View 吞点击（首选）**：在 `injectWhenReady` 拿到 `box`（菜单 ListView，`va4.j3`）且确认是伪会话行（`lastWxid` 以 `wxgrp_` 开头）后，遍历 `box` 的子 View，找 `TextView` 且文本 ∈ `{"删除该聊天","不显示该聊天"}`（「标为未读」是否纳入见 D3），对每个：
- `isClickable = true; isFocusable = true`
- `setOnClickListener { popup.dismiss(); UiKit.toast(ctx, "分组行请用「解散聊天分组」，不支持删除/隐藏会话") }`
原理：可点击的子 View 会消费触摸事件，AbsListView 不再对该 position 派发 item-click ⇒ 宿主删除逻辑不执行。这与我方 footer 项用 View OnClickListener 已真机跑通（`_r55c`）同源。

**第 2 层 · 包装菜单 ListView 的 item-click（兜底，防第1层未拦住）**：对 `box` 做一次幂等包装（用 tag 标记避免重复包）：
- 反射读 `box` 的 `OnItemClickListener`（`AdapterView` 的 `mOnItemClickListener` 字段，`XposedHelpers.getObjectField`）；
- 用新 listener 替换：`onItemClick(parent, view, position, id)` 里先按 `position` 取该项文本，若 `isPseudo && text ∈ 危险集合` → 吞掉（不调宿主 listener）+ 安全提示；否则 delegate 给原 listener。
- ⚠️ 这是**菜单 PopupWindow 内 ListView** 的 OnItemClickListener，不是会话列表的 OnItemLongClickListener ⇒ **不触发 z2 重入坑**（A2）。
- 包装需 ThreadLocal 重入守卫（读原 listener 时别读到自己）；PopupWindow 复用时按 tag 判幂等，宿主重设 listener 时重新包。

**为何这是「确定路径」而非「需真机搪塞」**：
- 「点删除该聊天会发生什么」由代码链确定（A1：精确删 wxgrp_ 行、不误删宿主、sync 补不回、分组行消失）——这是 [推断·高置信] 的逻辑结论，不依赖真机。
- 拦截手段用的是 R55c 已真机跑通的「在同一条 PopupWindow 里操作 View」能力 + Android 标准的「可点击子 View 消费触摸」机制，执行路径确定。
- 真机（V4/V5）只负责**验证拦截确实生效**，不是用来「碰运气看会不会误删」——即便第1/2层都意外失效，最坏后果是「分组行消失但可 clean 重建、成员不丢、不误删宿主真会话」（A1），不是不可逆数据灾难。这是无开关兜底下可接受的安全边界，方案的确定结论是：**有可执行安全路径，不是死路**。

### 3.4 R60 探针 `op=menulabel` 去留（决策点 D4）

- 现状：`entry/EntryReceiver.kt` 的只读探针 `op=menulabel` 打印的是**自绘** `GroupRowMenuLabels` 映射（`_r60_menu_label.md`）。R61 gconv=1 改原生菜单后，该探针对 gconv=1 语义过时，但对 **gconv=0 回滚态仍有效**（那条仍用自绘）。
- **建议（非阻断）**：保留探针不改，文档注明它只反映 gconv=0 路径；gconv=1 的菜单验证改看 V1/V2（原生菜单 dump + CMI 日志），不与 menulabel 混用。若要加原生菜单探针，另起 `op=nativemenu` 打印注入项集合，不复用 menulabel 语义。

---

## 4. 真机验证步骤（含正反对照 + 变异验证点）

1. **前提复核**：`kvget chat.gconv_enabled=true`、`chat.conv_menu_enabled=true`、`chat.conv_longclick_remap=false`（§0.2）。remap 非 false 先置回 false。
2. **基线备份**：`op=gcs stat` + 备份 `rconversation`/`rcontact` 我方行，记非本方行数（V4 用）。
3. **V1 正向**：真手长按 `123` 分组行（⚠️ 必须真手，`adb input` 会被识别成点击，`conv_list.md:75`），菜单弹出后 `uiautomator dump` + `screencap`，逐字核对 **5 项**（`解散聊天分组` 最前 + 宿主 4 项）、确认是原生卡片非 AlertDialog、确认**无**「加入分组/标记已读/归拢设置」。
4. **V2 注入日志**：`python tools/dev.py q CMI 30` 看 target/injected 两行、wxid 为 `wxgrp_`、分组行注入项**只有 dissolve（prepend），无 join/read**。
5. **V4 危险项**：在测试分组上点原生「删除该聊天」→ 应见安全提示、分组行仍在（验证 §3.3 拦截）；点后 `op=gcs stat` 核对非本方行数不变、我方行数不变。
6. **V5**：点「不显示该聊天」→ 安全提示、分组行仍在置顶区。
7. **V3 反向（红线①）**：`kvset chat.gconv_enabled false` + 冷启，长按 `123` → 应见自绘 AlertDialog 五项、末项「删除分组」；确认无误后恢复 `true` + 冷启。
8. **V6 普通会话零差异**：真手长按任一真实群会话 → 菜单应仍是宿主 4 项 **+「加入分组 + 标记已读」两项**都在，且点「加入分组」正常弹 GroupPicker、点「标记已读」正常清未读、宿主「删除该聊天」功能正常——与 V1 的 5 项分组行菜单互斥。
9. **变异验证**：按 C9 的 M1~M6 逐条做变异（改代码 → 期望对应判据变红 → 还原），每条留日志。

---

## 5. 风险与回滚

### 5.1 风险清单
1. **【最高】宿主危险项对伪会话执行**（A1）：删我方伪会话行且 sync 补不回、覆盖置顶 flag。→ §3.3 双层拦截；无开关兜底，代码必须安全。
2. **【已消除】假 id 污染**（B4）：伪会话行不插 join/read，`wxgrp_` 永不传给 `findFriend/GroupPicker`（M2 变异守）。
3. **红线① 破坏**：改到 gconv=0 路径（`ConvFilter.kt:1751-1758`）⇒ OFF 见原生菜单。严格只动 `:1834-1837`。
4. **普通会话零差异破坏**（v2 硬伤）：若把「不要标记已读」误实现成全局删除 ⇒ 真实会话菜单少一项（M6 变异守）。→ §3.2 真实会话分支逐字不动。
5. **菜单文案判据失效**：微信改字 ⇒ MENU_WORDS ≥2 不命中 ⇒ 不注入（安全降级，B6）。
6. **remap 被打开**：`chat.conv_longclick_remap=true` ⇒ 原生 4 项消失（§0.2 需确认项）。
7. **前插时机**：`addHeaderView` 在 adapter 已 set 时需先有 HeaderViewListAdapter（§B5 的「先确保再 addHeaderView」）；真机复核失败则退化成 footer 追加（D5）。
8. **热切换半生效态**：ON→OFF 带 tag 的 View 在回收池（`ConvFilter.kt:1782-1789` 已有防御），验新行为翻转不残留。

### 5.2 回滚（决策2：无开关，改代码/git）
- `git` 回退 R61 对 `ConvFilter.kt` / `ConvMenuInject.kt` 的改动：`ConvFilter` 恢复自绘长按，`ConvMenuInject` 删掉伪会话分支（真实会话分支本就未改）。`GroupRowMenu` 全程不删，零数据迁移。
- 若伪会话行被误删：`op=gcs sub=clean` 全删重建（GroupDb/成员未丢）。

### 5.3 需主会话/用户拍板的决策点（≤5，仅剩 3 个非阻断项）

> v3 已由用户拍板消除 D1（「加入分组」语义——伪会话不插该项，无需编造语义）；D2 已按 gid 分流解决（真实会话逐字保留，普通会话零差异），不再列为待拍板。

- **D3（非阻断）**：危险项「标为未读」是否也拦？用户决策1 要保留原生 4 项；本方案默认**只拦「删除该聊天 / 不显示该聊天」两项**（真正破坏性的），「标为未读」保留宿主行为。若用户要连它一起拦，确认。
- **D4（非阻断）**：R60 探针 `op=menulabel` 去留（§3.4）。建议保留但注明只反映 gconv=0，不为 gconv=1 复用。
- **D5（非阻断）**：前插失败时的降级策略——若 `addHeaderView` 真机不通，是否接受「解散聊天分组」暂时放末尾（而不是 UI 重排硬前插）？建议接受，避免为对齐位置引入脆弱反射。

---

## 6. 真机验收结果（2026-11-17，设备 467cf5bc / 微信 8.0.48）

**编译/单测**：`assembleDebug` EXIT=0；`testDebugUnitTest` 932/0（R60 的 928 + R61 新增 4），`GroupRowMenuLabelsTest` 7/7 且 mtime 为本次。代理变异 M2（伪会话误插 JOIN）/M6（真实会话去 read）/M3（guardDanger 误设 false）均精准变红。装包→双冷启 `loaded=true`；三开关基线复核 `gconv=true / menu=true / remap=false`（remap 保持 false，原生 4 项未被抹）。

**主会话交叉复核抓到并修复的 V6 bug**（编译前）：`DangerGuardListener` 构造时固化 wxid + 危险 TextView 残留 ClickListener，会在 PopupWindow 跨会话复用时把真实会话的「删除该聊天」吞掉。修法见 §3.3——第 2 层改运行时读 `lastWxid`、第 1 层打 tag 可还原、`clearDangerGuards` 在分流前无条件清残留（`ConvMenuInject.kt:390`、`:562`、`:637`）。修复后真实会话分支 diff 仅日志行变化、零逻辑改动。

### V1 — 分组行原生菜单 ✅（判据按真机事实修正）
- 真手长按 `123`（`wxgrp_...0099`）→ 弹出**微信原生白底裸文字卡片**（非自绘 Dialog），主目标达成。
- 日志：`CMI target wxid=wxgrp_... name=123` / `CMI popup injected gid=153 kind=prepend adapter=HeaderViewListAdapter` / `CMI danger guard installed` ⇒ **前插成功**（D5 降级未触发）、危险拦截已装。
- **逐字 9 项**（≠ 方案预期的 5 项）：
  ```
  解散聊天分组   ← 我方 prepend ✅
  聊天分组 / 备注 / 消息免打扰 / 推荐给好友 / 标为未读 / 取消置顶 / 不显示该聊天 / 删除该聊天   ← 微信原生 8 项
  ```
- **判据修正依据**：微信对 `wxgrp_` 伪会话行弹的是**群聊/联系人类 8 项菜单**（该行在 `rcontact` 有 type=0 记录），比微X `wx__c_` 行的宿主 4 项多「聊天分组/备注/消息免打扰/推荐给好友」。方案 §A1 的「标准会话 4 项」推断对 `wxgrp_` 行不成立，属证据等级 [需真机] 已被真机修正；产品行为正确（原生菜单 + 解散最前 + 无加入分组/标记已读/归拢设置）。

### V4/V5 — 危险项拦截 ✅
- 点「删除该聊天」→ `CMI danger swallowed(item) wxid=wxgrp_... pos=8 text=删除该聊天`（多次）。第 2 层 item-click 包装生效；第 1 层 View 吞点击未触发（ListView 子 View 触摸走 item-click），双层设计由第 2 层兜底。
- `gcs stat` 操作前后逐字一致：`conv own=1 other=3125`、`contact own=1 other=23539` ⇒ 分组行未删、真实会话一条不少。

### V6 — 普通会话零差异 ✅
- 真手长按真实群聊 `工人阶级大团结之集团君7.4.1`（`wxid=18777744571@chatroom`，@chatroom 结尾）→ `gid=null kind=footer need=404`，**无** `danger guard installed`。
- 菜单逐字：微信原生 7 项（聊天分组/加入群聊标签/解除消息免打扰/标为已读/置顶该聊天/不显示该聊天/删除该聊天）+ 我方 footer「加入分组 / 标记已读」2 项；**无**我方「解散聊天分组」。与 V1 互斥成立。
- 旁证：长按「美妆」（`wx__c_...`，微X 分组行）时菜单含微X 的「解散聊天分组/归拢设置」+ 我方 footer 两项——两套注入互不干扰，我方 dissolve 只在 `gid!=null` 时插。

### 已知 / 遗留
- D5 前插实测 `kind=prepend` 成功，未走 footer 降级。
- 代理汇报的日志串 `CMI prepend fallback footer` 实际代码中不存在；降级通过日志字段 `kind=footer` 体现，真机判据看 kind（已记，不影响功能）。
- 微信对 `wxgrp_` 行的 8 项菜单中，「聊天分组/备注/消息免打扰/推荐给好友」未做破坏性点击验证（非危险项、非我方注入）；已拦真正危险的「删除该聊天/不显示该聊天」，「标为未读」按决策保留宿主行为。
- 123 行头像已显示风景照（R59 lastSender 头像生效，非本次改动）。
- gconv=0 回滚路径代码未动（diff 已确认）、R60 已验过其文案，本轮按用户「直接默认开」未再反打。

---

## 7. R62：取消危险项拦截（2026-11-17，用户「取消拦截」）

**决策覆盖**：用户拍板移除 §3.3 的双层拦截——分组行的「删除该聊天/不显示该聊天」交回微信原生执行。§6 的 V4/V5「拦截生效」结论自此成为**历史**（当时的验收有效，R62 后行为变更）。已知后果：点「删除该聊天」会真删 `wxgrp_` 行（分组从列表消失、成员不丢、sync 自愈未验证），用户知情接受；分组没了用 `op=gcs sub=clean` 重建。

**改动**（`ConvMenuInject.kt`，Pro 类续派 r61-impl）：
- 删两处调用（`addItems` 开头 `clearDangerGuards`、伪会话分支末尾 `guardDangerItems`）；
- 删 4 个专属常量/字段（`DANGER_WORDS`/`TAG_DANGER_GUARD_KEY`/`TAG_DANGER_SWALLOW`/`guardInstalling`）；
- 删 3 个方法/类（`clearDangerGuards`/`guardDangerItems`/`DangerGuardListener`）；
- `GroupRowMenuLabels.MenuSet` 退化掉 `guardDanger` 字段，`itemSet()` 两分支只返回项集合。
- **保留**：`lastWxid`（wxid trap）、`lastDissolve`（dissolve 残留清理）、`MENU_WORDS`（菜单识别，非拦截）、真实会话分支、伪会话 prepend dissolve——逐字不动。

**验证**：
- 全仓 grep 拦截 8 项标识**零残留**；dex 里 `danger swallowed`/`danger guard installed`/提示文案全消失；「解散聊天分组」仍在。
- 编译 EXIT=0、全量单测 **932/0**（7 个 labels 用例全在）、变异 M-pseudo 变红。
- **真机**（设备 467cf5bc，装包→双冷启 loaded=true，三开关基线 gconv=true/menu=true/remap=false）：
  真手长按 `123` → `CMI target wxid=wxgrp_...009a` / `CMI popup injected ... gid=154 kind=prepend`，
  菜单逐字 5 项（解散聊天分组 + 标为未读/取消置顶/不显示该聊天/删除该聊天），
  **`danger guard installed`/`danger swallowed` 日志全无** ⇒ 拦截移除、菜单形态不变。
- `gcs stat` 基线 `own=2`（123 + 552 两个伪会话行）、`other=3126`，未做破坏性真删验证（避免丢现场）。
- V6 风险下降：任何会话都不再装 wrapper / 吞点击，宿主删除完全交回微信。

---

## 附：证据文件索引
- `docs/_r55c_longpress_menu.md` — 8 项 dump、容器 dex 归属、注入日志、前插提示
- `docs/_r55_microx_click_longclick.md:220-232, 242-249, 445-447` — 微X 注入判定 + 危险项风险
- `docs/conclusions/conv_list.md:65-87`（`:81` 过时结论已裁决）— 原生菜单链路、两条坑、adb 长按限制
- `docs/_r53_microx_grouprow.md:529-531, 564-581` — 点击放行死路（仅点击，不外推长按）
- `docs/_r60_menu_label.md` — R60 文案双态 + 语义等价
- `docs/当前状态.md` — R53~R60 已闭环
- `wxgroup/.../feature/ConvFilter.kt:1734-1853` — 两条分组行路径、点击/长按 listener、gconv 接管块
- `wxgroup/.../feature/ConvMenuInject.kt:106-441, 732-754` — trap + 注入 + 点击回调
- `wxgroup/.../data/ConvLayout.kt:139-169` — 伪会话 passthrough
- `wxgroup/.../data/GroupConvPlan.kt` / `feature/GroupConvStore.kt:83-87, 193-219, 286-313` — 伪会话 SQL + 并集自愈边界 + clean
- `wxgroup/.../data/Keys.kt:81-126, 467-495` — 开关定义 + Prefs 表
