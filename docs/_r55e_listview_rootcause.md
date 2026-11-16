# R55e — ConvBoxServiceConversationUI ListView 不渲染的静态取证

> lane: r55e-lane-pro（静态取证，只读）
> 目标：给真机 bug「组内 1 条会话塞进 adapter 后页面仍显示『暂无群聊』、ListView 不在视图树」定位根因，并给最小修改方案。
> 宿主：微信 8.0.48 / 2588 GP，dex 离线副本 `_cache/dex/classes*.dex`。
> 工具：`tools/_r12_dexclass.py`（`Dex` 解析器）+ 本轮自带的 dalvik 逐指令反汇编（内联执行，未落盘）。
> 证据等级：`dex-证`（有方法体指令） / `推断`（只有间接证据，显式标注）。

---

## 摘要（TL;DR）

1. `ConvBoxServiceConversationFmUI` 的**全部 View 字段只有 2 个**：`appbrandMessageLV`（`android.widget.ListView`，**不是** MMListView）和 `emptyTipTv`（`android.widget.TextView`）。父类链（BaseConversationFmUI → MMFragment → FragmentActivitySupport → … → Fragment）里也没有任何绑定本页列表/空态的 View 字段。
2. `setShowView(I)` 的**唯一**作用就是在这两个字段之间切可见性（count>0 → emptyTipTv GONE、appbrandMessageLV VISIBLE；否则反过来）。
3. 真机反射实名（21:39，View 字段全覆盖）：`appbrandMessageLV` = ListView id=`pmu`，`emptyTipTv` = TextView id=`dqm`，**只有一套**列表 + 空态。`dqm` 就是 `emptyTipTv`，早期「dqm≠emptyTipTv」是采样时刻不同造成的误判，已作废。
4. 根因（dex-证 + 真机）：我方 `replaceData` 塞入的 1 行是对的，但宿主有**周期性刷新**（`d2.onNotifyChange → x1.run → 重查`），每几秒把 adapter 游标换成它自己查出的空游标（`parentRef='conversationboxservice'` 下 0 行）→ `getCount()=0` → `setShowView(0)` 把空态翻回；且这条刷新**不经过 `t0.N()`**（hookB 全程未 fire），只钩 `N()` 拦不住。
5. 最小改法：拦截点下到周期刷新换游标的**汇合处 `w8.W(Cursor)`**（加防递归），或最上游 `s4.t(...)` 兜底（详见 Q5）。

---

## Q1 — ConvBoxServiceConversationFmUI 字段表

来源：`tools/_r12_dexclass.py`，`--class Lcom/tencent/mm/ui/conversation/ConvBoxServiceConversationUI$ConvBoxServiceConversationFmUI;`（classes4.dex）。

**实例字段全表（12 个，dex-证）**：

| 字段名 | 类型 | flags |
|---|---|---|
| adapter | `Lcom/tencent/mm/ui/conversation/d2;` | 0x2 (private) |
| **appbrandMessageLV** | **`Landroid/widget/ListView;`** | 0x2 (private) |
| clickStatusController | `Lcom/tencent/mm/ui/conversation/m2;` | 0x2 |
| conversation | `Lcom/tencent/mm/storage/r4;` | 0x2 |
| **emptyTipTv** | **`Landroid/widget/TextView;`** | 0x2 (private) |
| isFirstOnResume | `Z` | 0x0 |
| mAppId | `Ljava/lang/String;` | 0x2 |
| superUsername | `Ljava/lang/String;` | 0x2 |
| talker | `Ljava/lang/String;` | 0x2 |
| tipDialog | `Lsg4/i3;` | 0x2 |
| x_down | `I` | 0x2 |
| y_down | `I` | 0x2 |

**结论（dex-证）**：
- View 字段**只有 2 个**：`appbrandMessageLV`（ListView）和 `emptyTipTv`（TextView）。
- `appbrandMessageLV` 的类型是 **`android.widget.ListView`**（`check-cast v0, Landroid/widget/ListView;`，initView 0x0011），**不是** `Lcom/tencent/mm/ui/base/MMListView;`。

**父类链 View 字段排查（dex-证）**：

```
FmUI
└─ BaseConversationUI$BaseConversationFmUI   (classes4.dex)
     └─ MMFragment                            (classes8.dex)
          └─ FragmentActivitySupport          (classes4.dex)
               └─ HellAndroidXFragment        (classes8.dex)
                    └─ androidx.fragment.app.Fragment (classes11.dex)
```

- `BaseConversationFmUI`：实例字段只有 `fmStatus`(b0)、`ui`(BaseConversationUI)，**无 View 字段**。
- `MMFragment`：View 相关只有 `mSwipeBackLayout : Lcom/tencent/mm/ui/widget/SwipeBackLayout;`（侧滑返回外壳），无列表/空态字段。
- `FragmentActivitySupport` / `HellAndroidXFragment`：无业务 View 字段。
- `Fragment`（androidx）：`mView` / `mContainer` 是框架字段，不是本页列表。

⇒ **不存在「某个父类字段持有 dqm」的情况**。本页能通过 `getObjectField` 拿到的列表/空态对象只有 `appbrandMessageLV`、`emptyTipTv` 两个。

[Q1 DONE]

---

## Q2 — inflate 哪个 layout + findViewById→字段 映射

### 2.1 Fragment 的 layout id（dex-证）

`FmUI` **没有** `onCreateView`（virtual 方法表里只有 getLayoutId/getUserName/initView/onActivityCreated/onActivityResult/onDestroy/onPause/onResume）。layout 由基类框架用 `getLayoutId()` 决定：

`getLayoutId()` @ classes4.dex code=0x5645f8：
```
0000  const v0, #0x7f0c17f6     ; = 2131498998
0003  return v0
```

`MMFragment.onCreateView` @ classes8.dex code=0x4cafd0：
```
0010  getContentView() -> v5            ; 内容视图（= ba.t() → ba.d，控制器缓存）
0013  isSupportNavigationSwipeBack() -> v0
0016  if-eqz v0, +91                    ; 不支持侧滑则直接返回内容视图
0018  ... inflate(0x7f0c173f) -> SwipeBackLayout   ; 0x7f0c173f = 2131498815
...   removeView/addView + setContentView(contentView)   ; 内容视图包进 SwipeBackLayout
```

⇒ Fragment 最终返回的是 `SwipeBackLayout(0x7f0c173f)` 包着 `getLayoutId()=0x7f0c17f6` 的内容视图。

### 2.2 initView 的「字段 ← 资源 id」映射（dex-证）

`initView()` @ classes4.dex code=0x5646ac 全量逐指令：

| offset | 指令 | 含义 |
|---|---|---|
| 0000 | `const v0, #0x7f101011` + `getString` + `setMMTitle` | 标题 = `0x7f101011` = 2131759121 = "折叠的群聊" |
| 000a | `const v0, #0x7f0957c5` + `findViewById` + `check-cast ListView` + `iput appbrandMessageLV` | **appbrandMessageLV ← 0x7f0957c5 (2131318725)** |
| 0015 | `const v0, #0x7f0917ed` + `findViewById` + `check-cast TextView` + `iput emptyTipTv` | **emptyTipTv ← 0x7f0917ed (2131302381)** |
| 0024 | `getColor(0x7f0600cb)` → `emptyTipTv.setTextColor` | 空态文字颜色 |
| 0038 | `iput v1=0xc8, topMargin` → `emptyTipTv.setLayoutParams` | 空态文字上边距 200px |
| 0041 | `const v1, #0x7f101010` → `emptyTipTv.setText` | emptyTipTv 文案 = 0x7f101010 = 2131759120 = "暂无群聊" |
| 004f~005f | `new t0(thisActivity(), superUsername, i0(this))` → `iput adapter` | 建 adapter（类型 d2，监听器 i0） |
| 006a | `appbrandMessageLV.setAdapter(adapter)` | 列表接 adapter |
| 0076 | `adapter.getCount()` 拼日志 "data count:" | — |
| 008e/0098/00a2 | `appbrandMessageLV.setOnItemClickListener/OnTouchListener/OnItemLongClickListener` | 三个列表监听 |
| 00b3 | `getActivity().findViewById(0x7f093196)` + `check-cast ViewGroup` | 无障碍 ViewGroup（**不落字段**，只 setImportantForAccessibility） |

**结论（dex-证）**：`initView` 只做 **2 次 `findViewById` 并落字段**：`0x7f0957c5`→appbrandMessageLV、`0x7f0917ed`→emptyTipTv。第 3 次 `findViewById(0x7f093196)` 是无障碍 ViewGroup，不落字段。

### 2.3 关键机制：findViewById 会「回退到 Activity」（dex-证，本轮新发现）

`FragmentActivitySupport.findViewById` @ classes4.dex code=0x4ab098：
```
0000  Fragment.getView() -> v0
0002  if-eqz v0, +7        ; getView()==null 则跳
0004  v0.findViewById(id) -> v0
0007  goto +2
0008  const/4 v0, #0
000a  if-eqz v0, +3        ; 在 Fragment view 里找到了 → return
000c  return v0
000e  thisActivity() -> v0
0010  Activity.findViewById(id) -> v2   ; ★ 找不到就回退到 Activity 的 contentView
0012  return v2
```

**含义**：`initView` 的 `findViewById(0x7f0957c5)` 若在 Fragment 自己的 view 树里找不到，会**静默回退到 Activity 的 `setContentView` 视图**（layout `0x7f0c023e`）里找同 id。这是「字段可能指向非本 Fragment 树的 ListView」的**硬机制**，是 Q4 根因的直接证据之一。

[Q2 DONE]

---

## Q3 — `dqm` 资源 id 由哪个字段持有？空态文案还是替代 View？

> ⚠️ **真机更正（21:39 反射枚举，覆盖本文早期结论）**：`dqm` **就是** `emptyTipTv`，`pmu` **就是** `appbrandMessageLV`。
> 早期「dqm ≠ emptyTipTv」的结论作废——那是**采样时刻不同**造成的误判：我方 `setShowView(1)` 把 emptyTipTv 设 GONE 之后，宿主周期性刷新又把它翻回 VISIBLE，两个时刻各采一次就对不上。
> 即：本页**只有一套** ListView（`pmu`）+ 一套空态 TextView（`dqm`），`setShowView` 管的就是它们，字段/可见性机制本身是对的。真正的 bug 是「宿主周期刷新把游标换空 + setShowView(0) 翻回来」，见 Q4 更正。

### 结论（dex-证 + 真机更正）：`dqm` 就是 `emptyTipTv`

- 真机反射枚举（21:39，View 字段全覆盖）：`emptyTipTv` → `android.widget.TextView`，id=`dqm`。`dqm` 就是 `emptyTipTv`，**不是**第三个 View。
- dex 侧自洽：`initView` @ 0x5646ac 只 bind 两个 View 字段——`0x7f0957c5`→appbrandMessageLV、`0x7f0917ed`→emptyTipTv；`setShowView` @ 0x564b44 只在这两个字段之间切 GONE/VISIBLE，没有引用第三个 View 类型资源 id。
- `emptyTipTv` 文案在 `initView` 里 `setText(0x7f101010)`（= "暂无群聊"），是**程序控制的空态 TextView**。

### `dqm` 是「空态文案」还是「列表为空时的替代 View」？（dex-证）

- 是「**列表为空时的替代 View**」：`setShowView(count)` 按 count 正负切 `emptyTipTv` / `appbrandMessageLV` 的 GONE/VISIBLE，`emptyTipTv`（=dqm）就是列表空态兜底文案。
- 早期「`dqm` 是外层容器另一个 TextView、`setShowView` 管不到」的结论作废——那是**采样时刻不同**（我方 `setShowView(1)` 后宿主周期刷新又 `setShowView(0)` 翻回）造成的误判。

[Q3 DONE]

---

## Q4 — 为什么 appbrandMessageLV childCount==0 且不出现在视图树

> ⚠️ **真机更正**：早期「孤儿 ListView / 两棵树」推断作废。真机 21:39 决定性证据表明：`appbrandMessageLV` 就是界面那个 ListView（id=`pmu`），`emptyTipTv` 就是界面那个空态（id=`dqm`），**只有一套**。bug 不是「改错树」，而是「改对了、但 3 秒后被宿主周期刷新换空」。本 Q4 重写如下。

### 4.1 事实核对（dex-证 + 真机）

1. `setShowView(I)` @ code=0x564b44 体里**只有 4 条 `View.setVisibility`**，作用对象**只有** `emptyTipTv` 和 `appbrandMessageLV`：
   ```
   0000 const/4 v0,#0        ; VISIBLE
   0001 const/16 v1,#0x8     ; GONE
   0003 if-gtz v3, +13       ; n>0 → 跳 0x0010
   n<=0: emptyTipTv=VISIBLE(0), appbrandMessageLV=GONE(8)   ; 0x0005~0x000f
   n>0 : emptyTipTv=GONE(8),    appbrandMessageLV=VISIBLE(0) ; 0x0010~0x0019
   ```
2. 数据变更链：`i0.a` @ code=0x578fc8 = `access$000(fragment)`→adapter，`w8.getCount()`，`access$100(fragment,count)`→`setShowView(count)`。
3. `w8.getCount()` @ code=0x598c7c 返回 `i + A()`；`w8.A()` @ 0x598c68 **恒返回 0** ⇒ count = 缓存 `i`。`w8.W(Cursor)` @ 0x598e78 = `f=cursor; i=-1`（不 close 旧游标）；`w8.k()` @ 0x598ec0 = clear map `g` + close `f` + `i=-1`。
4. 真机时序（21:39）：我方 `replaceData` 完成那刻 `n=1 listVis=0 listCount=1` 正确；**3 秒后** `count=0 cacheI=0 curId=80852747`（游标被换空）；**14 秒后**再换一次 `curId=202293038`。`hookB`（钩在 `t0.N()`）全程未 fire。

### 4.2 真正的根因（dex-证 + 真机）

**宿主有一条「周期性数据刷新」链路，它独立于 `t0.N()`，每次都会把 adapter 的游标 `f` 换成它自己查出来的空游标（`parentRef='conversationboxservice'` 下 0 行），`getCount()` 重算成 0，随后 `i0.a` 走 `setShowView(0)` 把 `emptyTipTv`(dqm) 设 VISIBLE、`appbrandMessageLV`(pmu) 设 GONE。** 我方 `replaceData` 只改了一次，挡不住后续周期覆盖。

### 4.3 周期性刷新方法链（Q4 追加，dex-证定位到链，真机判定不经过 N）

真机事实：`hookB`（`t0.N()`）未 fire ⇒ 这条周期刷新**不经过 `N()`**，只钩 `N()` 拦不住。

静态追到的候选链（从观察者到重查）：

1. **观察者注册**：`FmUI.onActivityCreated` @ 0x5648a4 末尾 `vj0.h8.b().v() -> k74.y0`，`y0.a(adapter)` —— 把 adapter 注册成会话数据观察者。
2. **观察者入口**：`d2.onNotifyChange` @ 0x5747f4（`d2` 覆写 `w8.onNotifyChange`）——数据变更时被调；「前台（mIsFront `v`）」分支走 `a4.e()` 判去抖 + `v3.h(new x1(this))` post 出 `x1` Runnable；非前台则置 `mChangedBackground`(`w`)=true 延后。
3. **去抖/周期执行体**：`x1.run` @ 0x5876e4 —— 账号就绪时 `w8.p()`（= `k()`，close 游标 `f` + `i=-1`）+ `a4.c(delay,what)` **重新排程自己**（`a4.c` @ 0x4d3388 = `o3.sendEmptyMessageDelayed`，`a4.d` @ 0x4d33b4 = removeMessages）⇒ 自维持的周期刷新。
4. **重查入口（游标关闭后）**：`w8.q()` @ 0x598bfc —— `f` 为 null/closed 时调 `w8.L()`；`d2.L` @ 0x574600 = `invoke w8.N()`；`w8.N` 是 abstract（0x401），虚分派应落到 `t0.N()` @ 0x5841a4 → `s4.t(5,null,"conversationboxservice",-1)` + `W(cursor)` + `listener.a(null)`。

> **与真机的矛盾点（必须明示，不能掩盖）**：按 4.3 第 4 步，重查应经 `q()→L()→N()→t0.N()`，那 `hookB` 就该 fire；但真机实测未 fire。两种可能（均为 `推断`，需真机再取一个「在 `w8.W` / `w8.q` / `s4.t` 上临时打点」才能定）：
> (i) 周期刷新在 `x1.run` 或某个未追到的观察者里**直接** `s4.t(...)` + `W(cursor)`，完全绕开 `N()`（`curId` 每次变 = 新游标 = 新查询，支持这条）；
> (ii) `q()→L()→N()` 这链在真机上因某种虚分派/内联没走到 `t0.N()`（可能性低）。
> 无论 (i)/(ii)，结论一致：**拦截点必须下在 `N()` 之下游的汇合处（`W(Cursor)`）或最上游（`s4.t`），不能只依赖 `N()`**。

### 4.4 一句话根因（更正后）

**我们往 adapter 里塞的 1 行数据是对的，但宿主的周期观察者（`d2.onNotifyChange → x1.run → 重查`）每隔几秒就把游标换回它自己的空游标，再 `setShowView(0)` 把列表翻成空态；我们只钩 `t0.N()` 拦不住这条不走 `N()` 的路径。**

[Q4 DONE]

---

## Q5 — 最小修改方案（让「组内 1 条会话显示出来」）

> 根因已定（Q4）：宿主周期刷新每几秒把 adapter 游标换空 + `setShowView(0)`，且**不走 `t0.N()`**。因此 Q5 的核心是**选一个能挡住这条周期刷新的拦截点**，而不是继续调 `setShowView`（它会被翻回）。

### 四个拦截点比较

| 方案 | 能挡住周期刷新吗 | 递归风险 | 影响面 |
|---|---|---|---|
| (a) 钩 `t0.L()`（`N()` 同族） | **大概率挡不住**：`L()` 只是 `N()` 上游一层（`q()→L()→N()`），真机 `hookB`(`t0.N()`) 未 fire，说明周期刷新要么绕开 `N()`、要么连 `L()` 都没走到；且钩 `L()` 拦不住上游 `x1.run` 的周期调度本身。需真机打点才能定 L 是否在路径上。 | 低（我方不主动调 `L()`） | 中：`L()` 是 reload 入口，改它会连宿主正常的列表刷新（下拉/切账号）一起影响，可能让列表永不更新 |
| (b) 钩 `w8.q()` / `A()` | `q()` 是「游标 null/closed → 调 `L()` 重查」的入口，**只有当周期刷新确实走 `q()` 才挡得住**；`A()` 恒返回 0，钩它意义不大（改了 getCount 也改变不了「游标被换空」的事实）。真机未证 q 在路径上。 | 中：`q()` 在游标失效后被宿主多处调，我方若在 q 里再调宿主方法可能重入 | 中：`q()`/`A()` 是 adapter 公共接口，改它影响本页所有重查路径 |
| (c) 钩 adapter 的 `w8.W(Cursor)`（**汇合点**） | **能**：`W(Cursor)` 是 dex-证上唯一 `iput f=cursor` 的方法，无论宿主走 `N()` 还是直接 `s4.t`，换游标都必须过 `W`。在 `W` 里把「非我方游标」替换成我方缓存的 1 行游标（或丢弃空游标、保留我方游标），即可挡住周期换空。 | **高**：我方 `replaceData` 自己也会调 `W(我方游标)`，hook 会重入。必须加防递归——用「我方游标身份」（如 `MatrixCursor` + 我方专属标记列 `getColumnIndex(MARK)>=0`）或 ThreadLocal/实例标志在 replaceData 期间短路 hook。 | 小：只拦本页 adapter（`t0`/`d2` 实例）；但要按「gconv 哨兵 + 我方已 claim」条件收敛，避免误拦宿主正常刷新（切账号/真实折叠群聊页） |
| (d) 钩 `s4.t(...)` 直接返回我方合成游标（**最上游**） | **能**：所有查 `conversationboxservice` 的路径都经 `s4.t`，直接按参数 `(5,null,"conversationboxservice",-1)` 命中后返回我方游标，连宿主自己的空查询都替换掉。 | 低：`s4.t` 是静态查询入口，我方一般不主动调它；若我方 `replaceData` 也走 `s4.t` 取真数据，则需按调用栈/参数区分避免重入。 | **最大**：`s4.t` 是通用会话查询，被大量页面调用；即使按参数收敛到本页，仍是全局 hook，版本/调用点变更风险高 |

### 推荐

**首选 (c) 钩 `w8.W(Cursor)`，加防递归保护。**

理由：
1. `W(Cursor)` 是周期刷新换空游标的**唯一汇合点**，不管宿主走 `N()`、`q()→L()` 还是直接 `s4.t`，最终都要 `iput f=cursor`，绕不开。
2. 影响面最小——只作用于本页 adapter 实例，不碰全局查询、不碰宿主写库。
3. 防递归可干净解决：我方 `replaceData` 里先置「安装中」标志再 `W(我方游标)`，hook 检测到标志/我方游标身份即放行；对「非我方游标」才拦截替换。

落地要点（最小改动）：
- 拦截 `w8.W(Landroid/database/Cursor;)`（code=0x598e78），在**方法入口**判断入参游标是否「我方游标」（我方用 `MatrixCursor` 且带专属标记列）。
- 非我方游标 → 把入参替换成我方缓存游标（组内 1 行），再放宿主执行 `iput f=cursor`；随后宿主 `getCount()` 重算 = 1，`i0.a` 走 `setShowView(1)`。
- 我方游标 → 直接放行（防递归）。
- **兜底**：若真机打点发现周期刷新**连 `W` 都不经过**（可能性低，因为换游标必有 `iput f`），再退回 (d) 钩 `s4.t` 或 (b) 钩 `w8.q()` 做针对性拦截。

> 下一步真机取证建议（一行打点即可定论）：在 `w8.W` 和 `s4.t` 各下一个临时 hook，看周期刷新时 `W` 是否 fire、`s4.t` 是否 fire，即可在 (c)/(d) 之间最终裁定，不再需要猜。

[Q5 DONE]

---

## 附：本轮关键 code 偏移索引（供复核）

| 结论 | 类.方法 | code offset | dex |
|---|---|---|---|
| getLayoutId = 0x7f0c17f6 | FmUI.getLayoutId | 0x5645f8 | classes4 |
| initView 两次 findViewById | FmUI.initView | 0x5646ac | classes4 |
| setShowView 只切 2 字段 | FmUI.setShowView | 0x564b44 | classes4 |
| 数据变更 → setShowView | i0.a | 0x578fc8 | classes4 |
| findViewById 回退 Activity | FragmentActivitySupport.findViewById | 0x4ab098 | classes4 |
| onActivityCreated（extra→initView） | FmUI.onActivityCreated | 0x5648a4 | classes4 |
| onResume（adapter.N()） | FmUI.onResume | 0x564ac0 | classes4 |
| Fragment 内容包 SwipeBackLayout | MMFragment.onCreateView | 0x4cafd0 | classes8 |
| Activity inflate 0x7f0c023e + add 0x7f093bdc | ConvBoxServiceConversationUI.onCreate | 0x564bd8 | classes4 |
| 控制器内容视图（ba.d=inflate，ba.g=FrameLayout 0x7f093bc8） | ba.b0 | 0x4d4e44 | classes8 |
| 观察者入口 onNotifyChange（post x1） | d2.onNotifyChange | 0x5747f4 | classes4 |
| 周期执行体（p() + a4.c 自排程） | x1.run | 0x5876e4 | classes4 |
| 去抖/延时重排程（sendEmptyMessageDelayed） | a4.c | 0x4d3388 | classes4 |
| 取消重排程（removeMessages） | a4.d | 0x4d33b4 | classes4 |
| 重查入口（f null/closed → L()） | w8.q | 0x598bfc | classes4 |
| reload 抽象分派（L() → N()） | d2.L | 0x574600 | classes4 |
| 查库 + W(cursor) | t0.N | 0x5841a4 | classes4 |
| 游标 set 入口（iput f=cursor; i=-1） | w8.W | 0x598e78 | classes4 |
| 清空/close 游标（i=-1） | w8.k | 0x598ec0 | classes4 |
| getCount（= i + A()） | w8.getCount | 0x598c7c | classes4 |
| A() 恒返回 0 | w8.A | 0x598c68 | classes4 |
| 通用会话查询（s4.t 静态入口） | s4.t | 0x4a3e5c | classes4 |

[DONE]
