# R73 · 「原生全链路」可行性只读取证：menu.add() 条目能否变主网格原生格子并走 s3.c() 点击链

> 子代理：r73-native-fulllink（只读取证）　批次：R73
> 任务：查清能否让 `menu.add()` 加出的条目被微信渲染成**主网格原生格子**（带图标 + TextView 原生 id `0x7f095769`），从而点击走微信自带的 `j5.onItemClick → s3.c()` 链，由 `OnMenuItemClickListener` 接管（免自挂 View.OnClickListener）。
> 纪律：只读——未改源码、未碰设备/adb、未跑 gradle、未 commit。证据只认已落盘反汇编产物 + 前序真机定案，不重新扫 dex。
> 证据档：`[已证实]`=反汇编/源码/真机日志直接可见 ｜ `[推断]`=由已证实外推 ｜ `[需真机]`=有锚点无实测 ｜ `[取证缺口]`=材料解不出。
> 地址口径：`dex7` = `_cache/_r11_dex7_dis.txt`（= `_s9_classes7_dis.txt`，`va4/r3`、`va4/s3`、`j5`）；`dex4` = `_cache/_chatroom4_dis.txt`（`wg4/*` 定向）；`dex2` = `_cache/_s9_classes2_dis.txt`（finder-live 插件）。类声明 = `_cache/_s9dump/classes*.txt`。

---

## 0 结论摘要（一句话）

**不可行。** 微信把「网格弹窗（MMPopupMenu=`wg4/q`）」和「ListView 子菜单（`j5`=MMListMenuHelper）」做成了**两套独立点击系统**：`s3.c()`（即 `OnMenuItemClickListener` 的唯一触发点）**只被 `j5.onItemClick` 等 ListView 路径调用**；我方条目所在的主网格 `wg4/q.p` 每条格子的点击是 `wg4.j/k/l.onClick → q.w.onMMMenuItemSelected(MenuItem,pos) → dismiss`，**全程不碰 `s3.c()`**（dex4 全量 0 处 `s3.c()` 调用，真机探针亦零回调）。因此**即便把 `C` 写成 true、把格子做成带图标+原生 id 的完美原生格子，点击也进不了 `s3.c()`，`setOnMenuItemClickListener` 永不触发**。硬限制在「宿主网格点击链不读 MenuItem 监听器」，不在 `C` 或图标。`C` 只是 finder-live 专属的行位置标志，无公开写入语义；图标与 id 由 cell 布局 inflate 决定、与 C 无关。**View 层直接挂 OnClickListener（当前 `installItemClickBind`）是唯一可靠退路，且已被真机证伪原生链后确认为必要。**

---

## 1 关键辨析：两套菜单系统，不能混为一谈

前序设计文档 `docs/_r73_native_menu_inject.md` §4.1 把「网格点击」认定为 `j5.onItemClick → s3.c()`。本次复核以 dex 原文推翻——这是两条不同的链：

| 系统 | 类 / hook 点 | 载体 | 点击监听器 | 是否调用 `s3.c()` |
|---|---|---|---|---|
| **网格弹窗**（我方条目所在） | `wg4/q.p = MMPopupMenu.showGridMenu(II)Z`（dex4 0x8b1f20） | LinearLayout 行 + inflate 的 cell View | `View.OnClickListener wg4.j/k/l`（MMPopupMenu$7/$8/$9） | **否**（0 处） |
| **ListView 子菜单** | `com.tencent.mm.ui.tools.j5.onItemClick`（dex7 0x62a1a8，MMListMenuHelper/MMSubMenuHelper） | AdapterView + `r3` 适配器 | `AdapterView.OnItemClickListener` | **是**（0x62a22a） |

我方 hook `wg4/q.p`，条目加进 `q.y`（Lva4/r3），由网格系统渲染 —— 走的是第一行的链，永远到不了 `j5.onItemClick`。这是整份取证的根。

---

## 2 逐问题结论

### Q1 `s3.C` 从哪来、能否设为 true

**结论**：`C` 是 `va4/s3` 的 **PUBLIC 非 final boolean 字段**，构造器无条件写 `false`，**无任何 setter、无任何 add 参数（groupId/itemId/order/icon）控制它**。全 dex 唯一的 `C=true` 写入在 **Finder Live 评论弹窗插件**（非聊天菜单），是插件对其自有菜单 add 之后的**后置反射式改写**（按 itemId 命中预设 Set 才写）。模块可用 `XposedHelpers.setBooleanField(mi,"C",true)` 直接写字段，但**写入对该菜单的点击链无任何作用**（见 Q4）。

**dex 证据**：
- 字段声明：`_s9dump/classes7.txt:680404` 附近，`#2 name='C' type='Z' access=0x0001 (PUBLIC)`；字段号 dex7 `field@cace` / dex4 `field@ba5e` / dex2 `field@b124`（同一字段，不同 dex 编号）。
- 构造器写 false：dex7 `0x8fabe0` `iput-boolean v0(=0), v3, s3.C`；构造器 `s3.<init>(Context,int,int)`@`0x8fabb4` 的 3 个入参只落到 `B`(Context)/`g`(itemId)/`h`(groupId) 与 `u`(默认色)，**未触碰 C**（0x8fabca–0x8fac16）。
- 无 setter：s3 方法表（`classes7.txt:680394` 起）无 `setC`；全 dex 扫 `iput-boolean … s3.C` 仅 3 处 —— 构造器 false（dex7 0x8fabe0）+ finder-live 两处 true。
- 唯一 true 写入：`com.tencent.mm.plugin.finder.view.uh.a(Lva4/r3;…)`@dex2 `0x40cdec`，`0x40d290` `const/4 v5,#1 → iput-boolean v5,s3.C`；`com.tencent.mm.plugin.finder.view.vh.a`@`0x40d80c` 同理。二者都是 `for(item : r3.d) if (itemId ∈ qd.Z/qd.p0 Set) s3.C=true` 的**后置批处理**，与 `add` 参数无关。
- 注意区分：`wg4/q.C`（dex4 `field@bda4`）是**弹窗自身的样式布尔**，与 `s3.C` 无关（`0x8b1ff0/0x8b2066/0x8b293e` 读取的是 `q.C`）。

**等级**：字段可写/来源 `[已证实]`；写入 C=true 的效果 `[推断]`（见 Q4：对点击链无效）。

### Q2 图标为什么没显示

**结论**：`setIcon(int)` 在 MenuItem 数据层**生效正常**（resId 存入 `s3.t`，`getIcon()` 惰性 `Resources.getDrawable(t)`），不是混淆实现失效。图标不显示是因为**我方格子根本不是从「带 WeImageView 的原生 cell 布局」inflate 出来的**（真机 MH 树：`LinearLayout#0x7f09122c → TextView#-(无 id) 202x114`，无 WeImageView、无原生 id），即渲染走了另一条路径/配置；这与 C 无关，setIcon 也无错。若格子确实经原生 cell 布局（q.T==null 时 inflate `0x7f0c1326`/`0x7f0c1327`/`0x7f0c132a`）渲染，则 `getIcon()` 会被 setImageDrawable 到 WeImageView 上、图标会出现——但**即便出现也不能让点击进 s3.c()**。

**dex 证据**：
- `s3.setIcon(I)`@dex7 `0x8fa90c`：`iput v1, s3.t`（field@cadf，存 resId）。
- `s3.getIcon()`@`0x8fa790`：`iget-object v0,s3.v`；若 v==null 且 `s3.t!=0` 且 `B(Context)!=null` → `Resources.getDrawable(t)`（0x8fa7c4）返回；否则 null。⇒ 数据层 setIcon 生效。
- `r3.c(groupId,itemId,order,title,icon)`@`0x8f9f2c`：`new s3` → `iput title→s3.i` → `invoke setIcon(I)`（0x8f9f4e）→ `r3.d.add`。确认 add 时图标落到 s3.t。
- 原生 cell inflate：dex4 网格循环 `0x8b2640` `View.inflate(ctx, 0x7f0c1326, null)`；`0x8b264e` `findViewById(0x7f092d81)→WeImageView`；`0x8b2684` `MenuItem.getIcon()→setImageDrawable`。第二循环 `0x8b2cf2` inflate `0x7f0c132a` 同理。⇒ 图标**仅在 cell 经此布局 inflate 时出现**。
- 自定义 cell 工厂 `q.T`(`Lwg4/m`) 可能被设为 `vp1/f`（finder-live，`0x3232d6` `iput-object vp1/f.a → q.T`）；`vp1/f.a`@`0x8a11a4` inflate `0x7f0c1325`，只 setImageDrawable、**不 setText**。普通聊天菜单 q.T 一般为 null，但这属 `[需真机]` 配置细节。

**等级**：setIcon 数据层生效 `[已证实]`；图标缺失源于渲染路径（非 C、非 setIcon）`[推断]`；具体哪个 cell 布局/q.T 配置 `[需真机]`。

### Q3 TextView id `0x7f095769` 从哪来

**结论**：**来自布局资源 inflate，不是代码 setId**。原生 cell 由 `View.inflate(ctx, 0x7f0c1326, null)` 产生，其中 TextView 的 `android:id=0x7f095769`，渲染循环用 `findViewById(0x7f095769)` 取到并 setText。我方格子无此 id ⇒ 证明它**不是从该原生 cell 布局 inflate 的**，与 C 值无关。**进主网格（C=true）并不能保证拿到该 id**——id 由「用哪个 cell 布局 inflate」决定，而三条渲染循环的**非 q.T 路径都会 inflate 带该 id 的布局**（`0x7f0c1326`/`0x7f0c1327`/`0x7f0c132a`），所以问题的关键是「走哪条渲染路径/配置」，不是 C。

**dex 证据**：
- `0x7f095769` 全部作为 `findViewById` 参数出现：dex4 `0x8b2660`（网格循环，inflate `0x7f0c1326` 后）、`0x8b2d14`（第三循环，inflate `0x7f0c132a` 后）、adapter `wg4.p.getView` `0x8b19c8`（inflate `0x7f0c1329` 后）；无任何 `setId(0x7f095769)` 指令。
- 配套 WeImageView `0x7f092d81`（`0x8b264e/0x8b19ac/0x8a11c4`）同属这些 cell 布局。

**等级**：id 来自 inflate `[已证实]`；我方 cell 走了不同于原生 cell 的路径 `[已证实]`（由 MH 树 + 无 setId 共同证成）；具体路径 `[需真机]`。

### Q4 点击链依赖什么（决定性结论）

**结论**：`j5.onItemClick` **确实是**按 `r3.d.get(position)` 取条目（cast `s3`）后调 `s3.c()`，且**只服务 ListView 子菜单**（`j5` 实现 `AdapterView.OnItemClickListener`），**不服务我方主网格**。我方主网格的点击是 `wg4.j.onClick`：`q.w.onMMMenuItemSelected((MenuItem)q.y.getItem(idx), idx)` 然后 `dismiss()`，**整条链不调用 `s3.c()`**（dex4 全量 0 处调用）。真机探针：点我方格子时 `s3.c()` 与 `j5.onItemClick` 均零回调、`setOnMenuItemClickListener` 永不触发 —— 与该 dex 事实完全吻合。**因此 C=true 进主网格、拿到图标和 id，都不会让点击走 `j5.onItemClick → s3.c() → 我方监听器`；这条路在本菜单配置下不存在。**

**dex 证据**：
- `j5.onItemClick`@dex7 `0x62a1a8`：`0x62a212 iget j5.i:Lva4/r3` → `0x62a21e r3.d.get(position)` → `0x62a226 checkcast s3` → `0x62a22a invoke s3.c()→v8`；`0x62a248 if-eqz v8` → v8==true 时只 `sg4/d1.h()/q()` 清理并返回（0x62a25e–0x62a276）；v8==false 时 `0x62a280 j5.g.onMMMenuItemSelected(item,pos)` 再清理。⇒ j5 是 ListView 菜单，`s3.c()` 在此被消费。
- 网格点击监听器 `wg4.j.onClick`@dex4 `0x8b1770`：`0x8b17ba q.w:Lva4/d4` → `0x8b17c6 q.y.getItem(j.d)` → `0x8b17d2 invoke-interface d4.onMMMenuItemSelected(item,pos)` → `0x8b17dc q.j.dismiss()`。`wg4.k`(0x8b181c)、`wg4.l`(0x8b18c8) 同构。**无一处 `s3.c()`**。
- 全 dex4 扫 `s3.c()` 调用 = 0。全 dex7 扫 `s3.c()` 调用仅 5 处：`0x62a22a`(j5) / `0x67c132` / `0x792422`(`mz0.c0.onMMMenuItemSelected`,AppBrand) / `0x8a5f12` / `0x8a5f8e` —— 均非 `wg4/q` 网格链。
- 少数 `d4` 实现（如 `mz0.c0`@`0x792400`）会调 `s3.c()`，但**聊天消息菜单注入的 `q.w` 实现实测不调**（真机零回调为证）。⇒ 是否触发监听器取决于具体 `q.w`，而聊天菜单的 `q.w` 不读监听器。
- 点击监听器在 `0x8b275e`（setOnClickListener wg4.j）绑定，**早于 C 判断 `0x8b2784`** ⇒ C 值不影响是否绑定点击、也不影响走哪条点击回调；C 只决定 addView 到哪个 LinearLayout（行位置）。

**等级**：两条链的存在与差异 `[已证实]`；C=true 无法接通 s3.c() `[已证实]`（逻辑必然 + 真机双证伪）。

### Q5 有没有更直接的 add 重载

**结论**：`va4/r3` 的 add 家族（`a(II)/add(I)/add(IIII)/b(III)/c(...)/d/e/f/g/h/i/j/k/l(…Drawable)/m/n/o/p/q/r/s/t/u/v`）**全部收束到 `new s3(Context,itemId,groupId)` + 设 title/icon(+可选 Drawable/色)**，**没有一个会写 C=true，也没有一个能把条目「标记为原生主格子」**。带 `Drawable` 的重载（`l/m/q/r`）只是把 `s3.v` 设为 Drawable（影响 getIcon，仍不碰 C、不碰点击链）。故**无可用重载**实现「add 即原生格子 + 原生点击」。

**dex 证据**：
- `r3.c`@`0x8f9f2c` 的 5 步（见 Q2）是所有 c/d/f 类的模板；构造器 `0x8fabb4` 不读 C。
- `s3.setIcon(Drawable)`@`0x8fa8e4`：`iput-object v1, s3.v`（field@cae1）；`setIcon(I)` 走 `s3.t`。均不触碰 `C`/`y` 点击字段。
- 全 dex `C` 写入点仅构造器(f)+finder-live(t) ⇒ add 重载无法产生 C=true。

**等级**：`[已证实]`。

### Q6 fetch 顺序：怎么分流 v6 主网格 / v5 次级行

**结论**：`q.p` 读 `r3.d` 时**按插入顺序** `for i in 0..size-1: getItem(i)`（无按 order/group/itemId 排序）。分流**只看 `s3.C` 一个标志**：`C=true → addView 到 v6`，`C=false → addView 到 v5`（v5 满则回退 v6）；**两分支都 addView，条目都会被渲染**（不会被跳过）。每行上限 `v13=5`（`const/4 v13,#5`@`0x8b2380`，三处循环同）。`itemId`/`groupId`/`order` **不参与**分流（order 连构造器都不存）。

**dex 证据**：
- 顺序遍历：`0x8b245c r3.size()` → `0x8b250e r3.getItem(i)` → checkcast s3（网格循环 `0x8b25f8` 起，`0x8b2604 if-ge v7,v10,044f` 退出）。
- C 分流：`0x8b2784 iget-boolean s3.C`；`0x8b2788 if-eqz → 0438`；C=true 路径 `0x8b2798 v6.addView(v10)`；C=false 路径 `0x8b27ac v5.addView(v10)`；满则 `0x8b27c0 v6.addView`。容器：`v5=findViewById(0x7f093b11)`@`0x8b2402`，`v6=findViewById(0x7f093b13)`@`0x8b240e`（⚠️ 寄存器在循环内被复用，此映射以入口赋值为准；MH 树中我方 C=false 条目位于 `0x7f093b13`，与入口 v6 号存在表观出入 ⇒ 具体哪个 LinearLayout 是「主网格」以真机为准，见 `[需真机]`）。
- 前置 any-C 检查 `0x8b251a–0x8b252e`（无任何 C=true 则跳 `0347`），但 `0347` 之后仍进主渲染循环 `0x8b25f8`，**不门控渲染**。
- 上限 5：网格 `0x8b2792/0x8b27a8` 与 `v13`#5 比；adapter `getView` 亦按 5 列。

**等级**：顺序渲染/C 唯一分流/两分支都 addView/上限 5 `[已证实]`；具体 LinearLayout 谁是主网格 `[需真机]`。

---

## 3 DEX 证据表（可复核）

| # | 事实 | 位置 | 等级 |
|---|---|---|---|
| E1 | `s3.C` PUBLIC 非 final | classes7.txt:680404 field #2 | 已证实 |
| E2 | 构造器写 C=false，无参控制 | dex7 0x8fabe0 / 0x8fabb4 | 已证实 |
| E3 | C=true 唯一写入=finder-live | dex2 0x40d290 (uh.a) / 0x40d80c (vh.a) | 已证实 |
| E4 | `setIcon(I)→s3.t`，`getIcon()` 惰性解析 | dex7 0x8fa90c / 0x8fa790 | 已证实 |
| E5 | 原生 cell inflate 0x7f0c1326，WeImageView 0x7f092d81，TextView 0x7f095769 | dex4 0x8b2640/0x8b264e/0x8b2660 | 已证实 |
| E6 | `0x7f095769` 全为 findViewById，无 setId | dex4 0x8b2660/0x8b2d14/0x8b19c8 | 已证实 |
| E7 | `j5.onItemClick` 按 pos 取 s3 调 s3.c()（ListView 菜单） | dex7 0x62a1a8 / 0x62a22a | 已证实 |
| E8 | 网格 `wg4.j.onClick→d4.onMMMenuItemSelected→dismiss`，无 s3.c() | dex4 0x8b1770 / 0x8b17d2 / 0x8b17dc | 已证实 |
| E9 | dex4 全量 s3.c() 调用 = 0 | grep `Lva4/s3;.c:` on _chatroom4_dis.txt | 已证实 |
| E10 | 点击绑定早于 C 判断 | dex4 0x8b275e < 0x8b2784 | 已证实 |
| E11 | 顺序渲染 + C 唯一分流 + 两分支 addView + 上限5 | dex4 0x8b250e/0x8b2784/0x8b2798/0x8b27ac/0x8b2380 | 已证实 |
| E12 | 真机：我方格子无图标/无 id、s3.c 与 j5 零回调、监听器不触发 | ForceForward.kt:388-395 注释 + MH 树 | 已证实（真机） |

---

## 4 最终可行性判定

**判定：原生全链路不可行（就本菜单配置而言，属宿主硬限制）。**

硬限制位置（不是 C、不是图标）：
1. **点击链不读 MenuItem 监听器**——主网格 `wg4/q.p` 的格子点击是 `wg4.j.onClick → q.w.onMMMenuItemSelected → dismiss`，与 `s3.c()`（`OnMenuItemClickListener` 唯一触发点）**物理隔离**。真机双证伪（s3.c / j5 零回调）坐实这一点。
2. **`s3.c()` 的调用方是另一套 ListView 菜单**（`j5`），其 `j5.i/r3` 由 Nearby/Shake/子菜单等场景构造，我方 `q.p` 网格不经过它；无条件把条目塞进 `r3` 不会让 `j5` 来渲染/点击。
3. **`C` 无公开写入语义**：PUBLIC 可反射写，但仅 finder-live 内部按自有 itemId 规则使用；写它只改行位置，不改点击回调，且对本菜单的「原生格子资格」（图标/id）无决定作用。
4. **图标与 id 来自 cell 布局 inflate**，与 C、与 setIcon 正确性无关；我方格子缺图标/id 的真实原因是走了不同的渲染路径/配置（`[推断]`，真机已见现象），而非可被 add 参数或 C 修复。

**为什么 View 层挂 onClick 是必要退路（当前实现方向正确）**：
- 既然点击必然落到 `q.w.onMMMenuItemSelected`，而我方 itemId=`0x57470001` 不是任何宿主 e0 策略的 111/118，宿主分发的唯一后果就是 dismiss 且不触发我方逻辑 —— 所以**只能在 View 层拦截**。
- 当前 `installItemClickBind()/bindMyItemClick()`（ForceForward.kt:399-470）hook `PopupWindow.showAsDropDown/showAtLocation` after，按文案 `LABEL_FORWARD` 定位我方 TextView（因其无原生 id），挂 `OnClickListener → dismiss + onForwardClick()`，并以 `TAG_FORWARD_CLICK` 去重、只挂点击不注入 View。**这是绕过宿主点击链的唯一可靠路径，应保留。**
- `setOnMenuItemClickListener` 不应再挂（当前已于 :357-359 注释停用），它是死代码。

### 需真机验证的点（不阻断上述判定，仅完善细节）
- 我方格子究竟由三条渲染循环中的哪条、哪个 cell 布局（0x7f0c1326/1327/132a/q.T 工厂）渲染；`q.T` 在聊天菜单是否为 null —— 决定「为何无图标/id」的精确归因（不影响 Q4 结论）。
- `s3.C` 写 true 后我方格子的**视觉位置**是否变化（行位置），以及是否会影响 `q.w` 的具体实现选择 —— 仅观感/边缘风险。
- 聊天菜单注入的 `q.w` 具体实现类确认（10 处赋值点之一），以静态坐实「不调 s3.c()」（真机零回调已等价证明）。

---

## 附：两条点击链原始字节码对照（决定性证据）

**ListView 子菜单 —— 会调 `s3.c()`（`j5.onItemClick`, dex7 0x62a1a8）**
```
62a212: iget-object v7, j5.i : Lva4/r3
62a216: iget-object v7, v7, r3.d : Ljava/util/List
62a21e: invoke-virtual {v7, v9}, ArrayList.get(I)      ; 按 position 取条目
62a226: check-cast v7, Lva4/s3
62a22a: invoke-virtual {v7}, Lva4/s3.c:()Z → v8        ; ★ 触发 OnMenuItemClickListener
62a248: if-eqz v8, 0060                               ; true→只清理；false→d4.onMMMenuItemSelected
62a280: invoke-interface {j5.g, item, pos}, d4.onMMMenuItemSelected
```

**主网格 —— 不调 `s3.c()`（`wg4.j.onClick`, dex4 0x8b1770）**
```
8b17ba: iget-object v0, q.w : Lva4/d4
8b17c2: iget-object v1, q.y : Lva4/r3
8b17c6: iget v2, j.d : I                               ; 格子索引
8b17ca: invoke-virtual {v1, v2}, r3.getItem(I)→item
8b17d2: invoke-interface {v0, item, v2}, d4.onMMMenuItemSelected   ; ★ 直接分发，无 s3.c()
8b17dc: invoke-virtual {q.j}, Lva4/n4.dismiss:()V
```

两者分水岭一目了然：网格链 `0x8b17d2` 直接把 `MenuItem` 交给 `d4` 按 id 分发（我方高位 id 无人认领 → 仅 dismiss），`s3.c()`/`y`(OnMenuItemClickListener) 字段在这条链上从未被读取。`j5` 链虽读 `s3.c()`，但其 `r3` 来自另一套 ListView 菜单构造，与 `q.p` 网格无交集。

---

## 5 一句话回报

**不可行**：微信主网格点击链是 `wg4.j.onClick → d4.onMMMenuItemSelected`，全程不调 `s3.c()`；`j5.onItemClick → s3.c()` 只属 ListView 子菜单，和 `wg4/q.p` 网格无关。写 `C=true` 只改行位置、拿不到图标/id 的决定权，更接不通监听器 —— 硬限制在宿主点击链不读 MenuItem 监听器。View 层 `OnClickListener`（当前 `installItemClickBind`）是必要且唯一退路。
