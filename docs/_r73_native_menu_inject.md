# R73 · 用微信原生菜单 API 注入「转发到多个（模块）」取证与实现规划

> 子代理：r73-native-recon（只读取证）　批次：R73
> 任务：把 `ForceForward.kt` 现的「自绘裸 TextView 塞 PopupWindow」改成「**调微信原生 Menu API 加一条目，让微信自己渲染成和转发/复制一样的格子，排最后；我方只接管点击**」。
> 纪律：只读；不改源码、不碰设备、不跑 gradle、不 commit。证据只认已落盘的 dex 反汇编产物（`_cache/_s9dump/classes*.txt`、`_cache/_chatroom4_dis.txt`、`_cache/_r11_dex7_dis.txt`）与 `docs/_r73_blocked_forward_forensics.md`，不重新扫 dex。
> 证据档：`[已证实]`=反汇编/源码直接可见 ｜ `[推断]`=由已证实外推 ｜ `[需真机]`=有锚点无实测 ｜ `[取证缺口]`=材料解不出。
> 关键地址约定：`classes7.dex` = `_cache/_s9dump/classes7.txt`（`va4/r3`、`va4/s3` 类定义）；`classes4.dex` 定向反汇编 = `_cache/_chatroom4_dis.txt`（`wg4/*`）；`_r11_dex7_dis.txt` = classes7 dex 全量反汇编。

---

## 1 结论摘要

**能否走通：能，而且比现方案更干净。** 微信菜单有一套完整的原生门面 `va4/r3`（实现 `android.view.Menu`/`ContextMenu`）+ 实现 `va4/s3`（实现 `MenuItem`）；条目就是 `r3.add/add/c/d/f...` 往 `List<MenuItem> d` 里塞 `s3`。显示层 `MMPopupMenu`（=`wg4/q`）从这个 List 读 `size()`/`getItem(i)` 自绘格子。**用原生 API 加一条目 → 微信必然渲染出同款格子；给该条目 set 一个 `OnMenuItemClickListener` 返回 true → 点击被我方消费，宿主 `e0` 分发链自动跳过。** 无需自绘、无需手动调 layout/dismiss。

**推荐方案（一句话）**：在 `wg4/q.p(II)Z`（`MMPopupMenu.showGridMenu`）**before** hook 里，对 `this.y`（`va4/r3`）调 `r3.c(groupId, OUR_ID, order, "转发到多个（模块）", iconResId)` 加一条目，紧接着对返回的 `MenuItem` 调 `setOnMenuItemClickListener`（动态代理，回调 `ForceForward.onForwardClick()` 并返回 `true`）；加之前用 `r3.findItem(OUR_ID)` 幂等、用「长按时间窗 + 当前聊天页 + 能从 pressed view 拿到 h9」做上下文门禁。

| 关键结论 | 等级 |
|---|---|
| `va4/r3` 无「提交/显示」方法，是被动数据容器；显示转换在 `wg4/q.p(II)Z` | `[已证实]` |
| 网格**按 `r3.d` 插入顺序渲染**（`for i in 0..size: getItem(i)`，无 sort）⇒ **排最后 = 最后一个 add**，与 `order` 数值无关 | `[已证实]` |
| 条目点击：`r3.d.get(pos)` → `s3.c()`（invoke listener）→ 返回 true 则宿主**跳过** `va4/d4.onMMMenuItemSelected` 原生分发 | `[已证实]` |
| 原生转发项 id=111 / 收藏=116 / 另一组转发=118，均为小整数（实测 add 点还有 100/13/20）⇒ 选 `0x5747xxxx` 大整数可避冲突 | `[已证实]` |
| 屏蔽时长按是否仍走 `g0`/`r3.c` 仍 `[需真机]`（§5 给只读探针方案）；但本方案 add 时机在 `q.p`（显示前），不依赖具体某个 `g0` 是否 early-return | `[推断·高]` |

**风险**：① 误注入到会话列表/图片浏览菜单（用「聊天页 + h9 可取」双门禁收紧）；② 两条菜单构建路径（`g0` / `onCreateMMMenu`）与 `q.n/o/p` 多次调用导致重复 add（`findItem` 幂等必须做）；③ `setOnMenuItemClickListener` 动态代理的类加载/接口签名需真机确认；④ 屏蔽场景若连 PopupWindow 都不弹（场景 b），本方案整体失效，需退回长按直取 h9 的兜底路径。

---

## 2 菜单构建链与 hook 点候选表

### 2.1 已证实的完整构建→显示链（`[已证实]`，反汇编逐条可复核）

```
View.performLongClick
  └→ 宿主 AdapterView.OnItemLongClickListener = wg4/a.onItemLongClick  [classes4 0x8b0f80]
       ① r3.clear()                                       [0x8b0f98]
       ② log "registerForPopupMenu AbsListView long click"
       ③ new AdapterView$AdapterContextMenuInfo(view,pos,id)
       ④ OnCreateContextMenuListener.onCreateContextMenu(r3, view, info)
            └→ 消息策略 g0(r3,view,info,h9) / component onCreateMMMenu(r3)
                 └→ va4/r3.c/d/f...(groupId,itemId,order,title,icon) → new va4/s3 → 加入 r3.d
       ⑤ 遍历 r3.d，对每个 s3: s3.x = ContextMenuInfo            [0x8b0fca~0x8b0ffe]
       ⑥ q.n()                                                [0x8b1000]
            └→ wg4/q.n()Z  [0x8b1ca0] = return o(0,0)
                 └→ wg4/q.o(II)Z  [0x8b1cbc]（检查 e()/d()/a()/PopupWindow）
                      └→ wg4/q.p(II)Z  [0x8b1f20]  ← deobf 标记 "showGridMenu(II)V"
                           └→ 读 r3.size()/r3.getItem(i)  [0x8b245c / 0x8b250e]
                              按 i 升序取 s3，渲染「图标 WeImageView + TextView」格子
                           └→ PopupWindow.showAsDropDown / showAtLocation  ← ForceForward 现 hook 点
```

关键含义：
- **显示与 add 在同一条同步链上，且 add 全部发生在 `q.p` 读 List 之前**。任何 add 只要在 `q.p` 之前完成，就一定被渲染。
- `r3.d` 是 `ArrayList`，`showGridMenu` 用 `getItem(i)` **按插入顺序**取，未见 `Collections.sort`（`[已证实]`，`_chatroom4_dis.txt` 全量扫 r3 引用仅 `wg4/a.onItemLongClick` 一处读 `d` 字段用于设置 ContextMenuInfo，其余 `size/getItem` 在 `p` 内顺序遍历）。⇒ **order 数值不决定位置，插入顺序才决定**。
- 下一次长按开头 `r3.clear()` 会清空 ⇒ 无需手动移除我方条目（但仍建议 `findItem` 幂等防同一次长按内重复 add）。

### 2.2 `va4/r3` 全方法签名（`[已证实]`，classes7.dex `Class #10581`，`_s9dump/classes7.txt:679613`）

字段：`d:Ljava/util/List;`（PUBLIC FINAL，条目容器）、`e:Ljava/lang/CharSequence;`（header 标题）、`f:Landroid/content/Context;`（PUBLIC FINAL）。

- 构造：`<init>(Context)V`
- add 家族（**全部返回 `Landroid/view/MenuItem;`**）：
  `a(II)`、`add(I)`、`add(IIII)`、`add(IIILjava/lang/CharSequence;)`、`add(CharSequence)`、`b(III)`、`c(IIILjava/lang/CharSequence;I)`、`d(IILjava/lang/CharSequence;)`、`e(IILjava/lang/CharSequence;IIZ)`、`f(ILjava/lang/CharSequence;)`、`g(ILjava/lang/CharSequence;I)`、`h(ILjava/lang/CharSequence;II)`、`i(ILjava/lang/CharSequence;III)`、`j(ILjava/lang/CharSequence;IIZ)`、`k(ILjava/lang/CharSequence;IZ)`、`l(ILjava/lang/CharSequence;Landroid/graphics/drawable/Drawable;)`、`m(...;Drawable;I)`、`n(ILjava/lang/CharSequence;Ljava/lang/CharSequence;)`、`o(...,I)`、`p(...,II)`、`q(...,Drawable;IZ)`、`r(...,Drawable;Z)`、`s(ILjava/lang/CharSequence;Z)`、`t(ILjava/lang/CharSequence;ZZ)`、`u(MenuItem)`、`v(III)`
- 查询：`findItem(I)`、`getItem(I)`、`size()`、`hasVisibleItems()`、`w(I)I`、`x()Z`
- 删除/组/头：`removeItem(I)`、`removeGroup(I)`、`setGroupVisible(IZ)`、`setGroupEnabled(IZ)`、`setGroupCheckable(IZZ)`、`clear()`、`clearHeader()`、`close()`、`setHeaderTitle(I/CharSequence)`、`setHeaderIcon(I/Drawable)`、`setHeaderView(View)`、`setQwertyMode(Z)`
- 标准 Menu：`addIntentOptions(...)`、`addSubMenu(...)`×4、`isShortcutKey(ILandroid/view/KeyEvent;)`、`performIdentifierAction(II)`、`performShortcut(...)`

**结论**：`r3` 上**没有任何「构建完成/提交/转 View」方法**（`x()Z`/`w(I)I` 是纯布尔/int helper，`getItem/size` 是给显示层查询用的）。所谓「菜单完成」的落点在 `wg4/q`（MMPopupMenu），不在 `r3`。

### 2.3 网格转换点（`[已证实]`）

- 类：`wg4/q`，deobf 原名 **`com/tencent/mm/ui/widget/menu/MMPopupMenu`**（`_chatroom4_dis.txt:8b249a` 的 `const-string` 标记）。类定义见 `_s9dump/classes4.txt:689034`。
- 持有菜单的门面字段：`y:Lva4/r3;`；持有弹窗：`i/j/n/o:Lva4/n4;`（PopupWindow 系）、`h:Lcom/tencent/mm/ui/base/MMListPopupWindow;`。
- 关键方法：`n()Z`（`0x8b1ca0`，`return o(0,0)`）→ `o(II)Z`（`0x8b1cbc`，两次调 `p(II)`）→ **`p(II)Z`（`0x8b1f20`）= showGridMenu**，内部 `r3.size()`（`0x8b2460`）、`r3.getItem(i)`（`0x8b250e`）顺序渲染格子，并检查 `s3.C:Z`（可见性标志，`[推断]` 即 isVisible）。
- **add 必须早于 `p(II)` 的 `0x8b250e`**；`q.n()` 之前 add 即可满足。

### 2.4 hook 点候选表

| 候选 | hook 目标 | 时机 | 优点 | 缺点/风险 | 评级 |
|---|---|---|---|---|---|
| **A（推荐）** | `wg4/q.p(II)Z` before（`_chatroom4_dis.txt:0x8b1f20`） | 所有原生 add 之后、网格读取之前（最贴近「最后一次 add 后」的确定时刻） | 一次长按通常触发一次；直接拿到 `this.y`=r3；不依赖具体 g0 是否 early-return，屏蔽场景仍可 add | `o()` 内 `p()` 被调用 2 次 ⇒ 必须 `findItem` 幂等；`q` 实例服务多种菜单 ⇒ 必须上下文门禁 | **首选** |
| A′ | `wg4/q.n()Z` before（`0x8b1ca0`） | 同上，早一跳 | 单次调用，避开 p 双调 | 全 dex 有 16+ 处调 `q.n()`（输入框 + 菜单等），误注入面更大，门禁要求更高 | 备选 |
| B | 各 `viewitems/*.g0(...h9)Z` after + 各 `onCreateMMMenu(Lva4/r3;)V` after | 每条策略 add 完 | 能直接拿到 h9（第 4 参）做快照；`g0` early-return 时 after 仍可补 add | 类多（几十个）、签名统一但需枚举类批量 hookAll；同一次长按可能多策略回调 ⇒ 需「一次长按只加一次」标志；仍需门禁 | 可用（次选） |
| C | hook `va4/r3.c(IIILCharSequence;I)` after | 每次 add 都进 | 探针价值高（数 id111 是否在） | **无法判断「最后一次」**：r3 无 commit 回调，add 后仍可能有别的 add；常驻全局高频 | 不适合做注入点（仅探针） |
| D | hook `va4/r3` 的「完成方法」 | — | — | **不存在**（§2.2），此路不通 | 否决 |

**批量 hookAll 可行性（候选 B）**：`g0` 签名统一 `g0(Lva4/r3;Landroid/view/View;Landroid/view/ContextMenu$ContextMenuInfo;Lcom/tencent/mm/storage/h9;)Z`，但分散在 `viewitems/b9/fo/ic/ep/k/...` 等几十个**互不继承**的类里（除共同基类 `viewitems/s0`），没有一个可 `hookAllMethods` 的公共宿主；需先用 dex 类枚举得到类名清单，再逐类 `hookAllMethods(cls,"g0",cb)`。`onCreateMMMenu(Lva4/r3;)V` 同理分散在 `ui/chatting/component/*`。因此 B 的工程量明显高于 A。

---

## 3 条目参数取值建议

### 3.1 已确认的原生 itemId 集合与冲突规避（`[已证实]`）

从 `r3.c` add 点反汇编读到的 `const/16`/`const`：

| itemId | 含义 | 出处 |
|---|---|---|
| 111 (`0x6F`) | 转发（文本/图片等多数策略） | forensics §1.2；`viewitems/b9/fo/ic.g0` |
| 116 (`0x74`) | 收藏 | `fo.g0` 后 `removeItem(116)`；`_chatroom4_dis.txt:51ba88` |
| 118 (`0x76`) | 另一组转发（语音/视频号类 `ui/vi`） | forensics §1.2 |
| 100 (`0x64`) | 其它菜单项 | `_chatroom4_dis.txt:51bac4` |
| 13 / 20 | 其它菜单项 | `_chatroom4_dis.txt:51ba70/78` |

规律：**全部是小整数（≤ 三位十进制）**。→ 选一个**高位 int**即可确定性避开：`itemId = 0x5747_0001`（十进制 1463496705，助记 "WXG"）。宿主 `e0` 里的判定是 `const/16 v0 #111` / `#118` 的 16 位比较，不会命中该值（`[已证实]`）。`groupId` 同样取独立高位 `0x5747_0000`，避免与宿主常用 group 0 混组（`setGroupVisible(0,...)` 波及原生项）。

### 3.2 参数表（`r3.c(groupId,itemId,order,title,icon)`）

| 参数 | 建议值 | 理由 |
|---|---|---|
| groupId | `0x5747_0000` | 独立分组，便于 `setGroupVisible`/`removeGroup` 精准操作我方项，不碰宿主 group 0 |
| itemId | `0x5747_0001` | 高位固定值，避开原生 111/116/118/100/13/20 等小整数，`e0` 的 `#111/#118` 比较不会命中 |
| order | `Integer.MAX_VALUE - 1`（如 `0x7FFFFFFE`） | 原生 `order` 多为 0（`[已证实]`），显示又按插入顺序 ⇒ 该值仅语义表态「排最后」，**真正决定位置的是「最后一个 add」**；不要依赖它排序 |
| title | `String "转发到多个（模块）"` 直接传（第 4 参是 `Ljava/lang/CharSequence;`) | `c()` 内部 `iput-object title, s3.i`，无需资源 id；与现有 `LABEL_FORWARD`（`ForceForward.kt:77`）逐字一致 |
| icon | `0x7f0f0529`（原生转发图标资源 id，第 5 参是 int → `setIcon(I)`） | 原生格子是「WeImageView + TextView」有图标（`[已证实]` 真机 23:42 菜单树），传 0 会缺图标导致样式不一致；复用转发图标语义也贴切 |

> `[已证实]` `c()` 反汇编（forensics §1.1，classes7 `0x8f9f2c`）：`new s3(Context,?,?)` → `iput-object title → s3.i` → `setIcon(I)` → `r3.d.add(s3)`；**没有 `setVisible(false)`**，「可见」= 被 add。故我方条目 add 后天然可见，无需额外 setVisible。

---

## 4 点击分发链与我方接管方案

### 4.1 完整点击链（`[已证实]`）

```
网格格子 (LinearLayout: WeImageView + TextView)
  └→ AdapterView.OnItemClickListener = com.tencent.mm.ui.tools.j5.onItemClick
        (MMListMenuHelper / MMSubMenuHelper，classes7 0x62a...)
        ├─ r3 = j5.i (Lva4/r3);  s3 = (Lva4/s3) r3.d.get(position)   [0x62a212~0x62a226]
        ├─ boolean consumed = s3.c()   ← invoke OnMenuItemClickListener [0x62a22a]
        ├─ if (consumed) { sg4/d1.q(); return; }   ← ★ 被消费：不进原生分发 [0x62a248~0x62a270]
        └─ else { j5.g (Lva4/d4).onMMMenuItemSelected(MenuItem, int); sg4/d1.q(); }
                    └→ 各 viewitems/*.e0(MenuItem,Lib4/c,h9)Z 判 id 111/118 → 组 Retr_* Intent → MsgRetransmitUI
```

**关键分水岭**：`s3.c()` 返回值。返回 `true` ⇒ 宿主只做 popup 清理（`sg4/d1.q()`）就返回，**根本不会走 `onMMMenuItemSelected` / `e0`**。这正是「我方只接管点击、原生转发逻辑不误触」的机制保证。

### 4.2 我方接管方案（推荐 + 备选）

**推荐：原生监听器接管（零全局 hook 点击）**
1. 在 §2.4-A 的 add 之后，拿 `MenuItem mi = r3.c(groupId, OUR_ID, order, title, icon)`。
2. 对 `mi` 调 `setOnMenuItemClickListener(proxy)`；`proxy.onMenuItemClick(item)` 内：
   - 判 `item.getItemId() == OUR_ID`（防串扰）；
   - 调 `ForceForward.onForwardClick()`；
   - **`return true`**（消费点击 ⇒ 宿主 `s3.c()` 返回 true ⇒ 跳过原生 `e0`）。
3. `proxy` 用 `java.lang.reflect.Proxy.newProxyInstance` 以宿主 classLoader 加载 `android.view.MenuItem$OnMenuItemClickListener` 接口生成；`invoke` 里只处理 `onMenuItemClick`，其余方法返回默认值。
   - `[需真机]` 确认 `s3.c()` 确实调用该监听器（反汇编已见 `s3.c()` 被 `j5.onItemClick` 当「是否有监听器并消费」用，且 `s3` 有 `setOnMenuItemClickListener` 与字段 `y:L...OnMenuItemClickListener;`，`[已证实]`；接口动态代理在 Xposed 里是标准手段）。

**备选：全局 hook `va4/s3.c()Z` before**
- 签名：`c()Z`（`_s9dump/classes7.txt:680520`）。before 里 `if (((MenuItem)param.thisObject).getItemId() == OUR_ID) { ForceForward.onForwardClick(); param.setResult(true); }`（setResult 跳过原方法）。
- 优点：不依赖动态代理；缺点：全局 hook 高频方法，需严格按 itemId 过滤，且 `param.setResult(true)` 后 `s3` 自身状态（如 dismiss 标志）可能未更新 ⇒ 优先推荐方案。

**为什么不 hook `viewitems/*.e0`**：我们的 itemId 不是任何策略的 111/118，不会有 `e0` 分发到我们；hook `e0` 接不到我方点击。必须在 `s3.c()` 这一层（或监听器层）接。

### 4.3 消息快照复用（`[已证实]` 现有机制可直接用）

`ForceForward.kt` 现有链路：
- `installLongPressTrap()`（`:178`）hook `View.performLongClick` before，用 `burstDeepest:WeakReference<View>` + `burstStartAt` 记一次长按最深处 View，`BURST_TTL_MS=10_000L`（`:89`、`:635`）。
- `onForwardClick()`（`:468`）→ `snapshotNow()` → `snapshotOf(pressedView())` → `msgRefOf(v)`（`:527`）沿 View 链找 `MMChattingListView` → `getBaseAdapter()`/`m()` → `getItem(pos)` 拿 h9 → 取 `getMsgId/getType/getContent/field_imgPath/field_talker/field_msgSvrId`。
- 结论：**点击我方原生条目时直接调现有 `onForwardClick()` 即可**，无需改快照逻辑——原生条目点击发生在长按后数秒内，`pressedView()` 的 10s TTL 仍有效，h9 可由 View 链取到。
- 增强（可选）：若用候选 B（hook `g0` after），可直接把第 4 参 h9 缓存进 `ForceForward`，点击时用 `snapshotOf(h9)` 免 View 链反射，更稳；但非必需。
- 注意：现 `addItems` 里的 `popup.dismiss()` + `onForwardClick()` 组合（`:373-376`）在原生方案里由宿主 `sg4/d1.q()` 负责 dismiss，我方监听器只需 `onForwardClick()`，避免重复 dismiss。

---

## 5 屏蔽场景探针方案（真机验证清单，只读）

> 目标：判定「官方屏蔽转发的消息」长按是否仍走 `g0`/`r3.c`、是否弹 PopupWindow、能否拿到 h9；不改宿主行为。

**探针清单（新增只读 hook，before/after 仅打日志，不改 param、不主动发消息）**

| # | hook 方法 | 打印内容 | 判据用途 |
|---|---|---|---|
| P1 | `View.performLongClick` before（复用 FF trap 日志） | `lastPressAt`、目标 View 类名 | 长按是否触发（图片卡片可能不触发，R71 §5） |
| P2 | `wg4/q.p(II)Z` before | `this.y`（r3）`size()`、当前 `r3.d` 各 `s3.getItemId()`/`getTitle()` | **菜单是否仍构建/显示、原生 111 在不在**；本方案 add 点是否可达 |
| P3 | `va4/r3.c(IIILCharSequence;I)` after | `groupId,itemId,order,title,icon`（按 itemId 过滤，只打 111/116/118/我方 id） | 原生转发项是否 add、我方 add 是否成功 |
| P4 | `viewitems/ep.g0(...h9)Z` before/after | `h9.getType()/t2()/p2()`、`jj/m7.G` 是否含 `announcement@all`、返回值 | 机制 A1 early-return 判据（forensics §2.1） |
| P5 | `ml1.d.b(RepairerConfig...)` after | 返回 int | 机制 B 新转发开关是否 ==1 |
| P6 | hook `va4/s3.c()Z` after（探针态） | `getItemId()`、返回值 | 点击时哪条被消费、我方 id 是否进来 |

**判据表**

| 观察 | 结论 | 本方案是否成立 |
|---|---|---|
| P2 有调用、`r3.size()>0`、但列表无 111 | 场景 (a)：菜单在、转发项被屏蔽 | ✅ 成立：`q.p` before 仍能 add 我方项 |
| P2 有调用、列表有 111 | 普通消息 | ✅ 成立（需门禁避免与普通转发重复感知，功能正常） |
| P2 完全不调用 / 无 PopupWindow show | 场景 (b)：连菜单都不弹 | ❌ 本方案失效 ⇒ 退回「长按 View 直取 h9 + ForwardPicker」兜底 |
| P4 `ep.g0` 走 `return 1` | 机制 A1 确认（按类型屏蔽） | ✅ 成立（本方案不依赖 ep.g0 成功） |
| P5 ==1 | 机制 B（官方新转发 UI） | ✅ 成立（点击仍走我方 picker，不受影响） |
| 抓到 `OpenForwardMsgViewSpam` CGI 被拒 | 机制 C（服务端） | ✅ 成立（绕菜单自建入口正是本方案价值） |

**纪律**：全程只读；P4 仅用 getter（`getType/t2/p2` 等无副作用）；空日志先做探针前置校验（hook 是否挂上）；时间窗内不触发其它发送避免日志污染。判场景 (a/b) 的硬指标是 P2 是否在被屏蔽消息长按时触发。

---

## 6 与现有 `ForceForward.kt` 的关系

| 现有逻辑 | 处置 | 说明 |
|---|---|---|
| `LABEL_FORWARD = "转发到多个（模块）"`（`:77`） | **复用** | 原生条目 title 逐字沿用 |
| `onForwardClick()`（`:468`） | **复用** | 原生监听器直接调用，行为不变（snapshot → picker → 发送） |
| `snapshotOf/msgRefOf/positionOf`（`:493/527/596`） | **复用** | 长按 View→h9 快照链不动；10s TTL 覆盖点击时刻 |
| `openTargetPicker/execute/runBatch/send/nativeForward...`（`:684` 起） | **复用** | 选人器与 G1/G2 发送链完全不动 |
| `installLongPressTrap()`（`:178`） | **复用（加强）** | 原来只给自绘注入判上下文；现在还给原生 hook 提供「长按时间窗 + pressed view」门禁信号 |
| `installPopupInject()` / `tryInject()`（`:200/213`） | **拆分/停用** | 自绘注入点（`showAsDropDown/showAtLocation` after）在新方案下**必须停**，否则同一菜单会出现两个入口（原生格子 + 自绘 TextView） |
| `injectWhenReady/findMenuBox/addItems/newItem/grow`（`:234/336/371/424/451`） | **拆解** | 自绘注入全套（`classifyMenu`、`findMenuBox`、View 样式克隆、popup.update 长高）在原生方案下**不再需要**——微信自己渲染格子、自己排版 |
| 自绘注入是否保留作降级 | **建议保留代码、默认停** | 场景 (b)（连菜单都不弹）时自绘也无 PopupWindow 可挂，同样失效，真正的兜底是「长按 View 直取 h9 + ForwardPicker」；自绘仅在「原生 add 失败但菜单在」时有降级价值，可用运行时开关（如 `enabled` 之外加 `nativeInjectFailed`）回退，避免默认双入口 |

**迁移要点**：新增一个「原生菜单注入」hook 安装函数（在 `install()` `:163` 里与 `installLongPressTrap` 并列），把 popup 自绘 hook 的 `installPopupInject()` 调用默认关掉（保留函数体供降级）。门禁复用 `lastPressAt`（`:144`）+ `currentChatTalker()`（`:670`，非空）+ `msgRefOf(pressedView()) != null`，三者同时满足才 add。

---

## 7 未决项与风险

1. **`setOnMenuItemClickListener` 动态代理真机验证** `[需真机]`：接口 `android.view.MenuItem$OnMenuItemClickListener` 能否在 Xposed 里用宿主 classLoader 代理、`s3.c()` 是否确实调用它并采信返回值。若不成立，退回「全局 hook `s3.c()Z` before + `param.setResult(true)`」备选（§4.2）。
2. **屏蔽场景 (a/b) 分水岭** `[需真机]`：P2 探针是否触发决定本方案成败；场景 (b) 需兜底路径。
3. **`q.p` 双调与多菜单共用** `[已证实] 风险`：必须 `r3.findItem(OUR_ID)` 幂等 + 上下文门禁（聊天页 + h9），否则重复 add 或误注入会话列表/输入栏菜单。
4. **`s3.C` 可见性标志** `[推断]`：原生项 `c()` add 后未显式 setter，`showGridMenu` 却检查 `s3.C:Z`；需确认走 `r3.c()` 创建的 s3 的 `C` 默认为 true（否则我方格子会被跳过）。用 P2 探针看我方项是否出现在菜单里即可证。
5. **order 语义** `[已证实] 显示按插入顺序`：order 传大值仅为语义表态；若真机发现 showGridMenu 实际按 order 排序（反汇编未见 sort，`[需真机]` 复核），则大 order 仍保证排最后，结论不变。
6. **图标资源 id 稳定性** `[需真机]`：`0x7f0f0529` 在 8.0.48 有效（forensics 已解析）；换版本需重新解析资源。传 0 会丢图标、样式与原生不一致。
7. **原生 dismiss 行为** `[推断]`：宿主 `sg4/d1.q()` 会关弹窗；我方监听器不再手动 dismiss，需真机确认点我方项后弹窗确实关闭（否则残留浮层）。
8. **回退开关** `[待定]`：是否给原生方案加独立开关（灰度和 A/B 对照）由主会话决定；当前 `enabled` 只做全局反向对照。

> 一句话：**走原生 `va4/r3` 菜单 API 完全可行且更贴合用户诉求——add 时机卡在 `wg4/q.p(II)Z` 之前、排最后靠「最后 add」、点击靠 `setOnMenuItemClickListener` 返回 true 消费；唯一硬门槛是屏蔽场景菜单是否仍构建（§5 探针 P2 定案），真机确认后即可替换现有自绘注入。**
