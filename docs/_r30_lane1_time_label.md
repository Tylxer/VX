# R30 Lane1 静态取证：朋友圈时间线 item「发布时间」控件

> 子代理：r30-timelabel。只读静态 dex 取证，不改源码、不跑 gradle、不用 adb。
> 目标：微信 8.0.48 (2588) 国际版，15 个 dex 在 `_cache/dex/classes*.dex`。
> 判据纪律：只认 invoke 地址 / const-string / const 立即数 / 字段读写（field_id + 指令偏移），
> 不认「看起来像」。所有类名/方法名给出 dex 文件 + 指令地址或 field_id。
>
> ⚠️ 本节结论全部来自**静态字节码**，未上真机。行号/偏移以 `_r27_disasm.py` 输出的 code-unit 偏移为准。

---

## 0. 结论速览（TL;DR）

| # | 问题 | 结论 |
|---|---|---|
| 1 | 列表 adapter | `com.tencent.mm.plugin.sns.ui.improve.component.u1`（父 `ju2/s0`），由 `ImproveMainUIC.f2()` 产出后 `WxRecyclerView.setAdapter` |
| 1 | item 视图类 | `com.tencent.mm.plugin.sns.ui.improve.item.ImproveTimelineItemBuild`（= `lh3/l1`，extends LinearLayout）；数据模型 `com.tencent.mm.plugin.sns.ui.improve.model.ImproveSnsInfo`（= `oh3/p`） |
| 1 | 时间 setText 所在方法 | `lh3/x1`（= `ImproveTimelineItemMeasure`）`d()`（= `realTimeUpdate`），指令 `0x001e invoke-virtual TextView.setText` |
| 2 | 时间控件 | 无 XML 资源 id，是**代码 new 出来的** `ImproveTextView`；ViewHolder 字段 `lh3/l1.o:Landroid/widget/TextView;`，getter `getPublishTimeText()` |
| 3 | 时间格式化入口 | `com.tencent.mm.plugin.sns.ui.ns`（= `SnsTimeUtil`）`a(Landroid/content/Context;J)Ljava/lang/String;`（= `formatSnsTimeInTimeLine`） |
| 4 | 复用范围 | `ns.a`(x分钟前)/`oh3/p.J0` 仅时间线用；详情页走 `ns.c`，陌生人/消息列表走 `ns.d`；**好友个人相册（SnsAlbumUI→`qg3/i`+XML+`qg3/h.A`）不复用时间线时间控件/时间链**，只用 `ns.f`(getYear) 做日期分组 |
| 5 | 建议 hook 点 | 复用 SnsTag 的 `WxRecyclerAdapter.onBindViewHolder` 链拿 snsId + itemView 的时间 TextView 追加；另 hook `lh3/x1.d()`（realTimeUpdate）兜底定时刷新覆盖 |

---

## 1. 列表与 ViewHolder（adapter / 视图类 / bind 方法）

### 1.1 Adapter 全限定名：`com.tencent.mm.plugin.sns.ui.improve.component.u1`

**证据**（classes3.dex）：
- `ImproveMainUIC.f2() -> Lcom/tencent/mm/plugin/sns/ui/improve/component/u1;`（方法签名，`_r4_dexref.py methods` 输出）。
- `ImproveMainUIC.onCreate` 字节码（`_r27_disasm.py`，classes3.dex，code=0x487070）：
  ```
  0x0029  invoke-virtual ImproveMainUIC 组件 j.getRecycleView
  0x002d  invoke-virtual ImproveMainUIC.f2                ← 取 adapter
  0x0031  invoke-virtual WxRecyclerView.setAdapter         ← 塞给 RecyclerView
  0x003a  ... ImproveLayoutManager.<init> → setLayoutManager
  ```
- `u1` 父类：`Lju2/s0;`（MVVM 列表基类，class_def 的 superclass_idx）。
- `u1` 构造器：`<init>(Lcom/tencent/mm/plugin/mvvm/list/MvvmList;Lki4/n;Lcom/tencent/mm/plugin/sns/ui/improve/component/t1;)V`。
- `u1` 只有两个方法：`<init>` 和 `g(Lju2/n0;)V`，其余继承自 `ju2/s0`（MVVM 框架）。真正的 onBind 逻辑下沉到 item 类（见 1.4）。

> 与既有结论对账：`docs/conclusions/moments.md` #4 说时间线标签走 `WxRecyclerAdapter.onBindViewHolder` + `adapter.getData()`，且 adapter 类名以 `com.tencent.mm.plugin.sns.` 开头 —— `u1` 全名正满足该前缀。真机上 `setAdapter` 进来的就是这个 `u1` 实例。

### 1.2 item 视图类（ViewHolder 的「根 View」）：`ImproveTimelineItemBuild`（= `lh3/l1`）

**证据**（classes3.dex）：
- `lh3/l1` 的 `getPublishTimeText()` 方法体内 const-string：
  ```
  0x0002  const-string v1, 'com.tencent.mm.plugin.sns.ui.improve.item.ImproveTimelineItemBuild'
  ```
- `lh3/l1` 父类：`Landroid/widget/LinearLayout;`（class_def superclass）。→ 它就是 item 的根 View（与 `moments.md` #5「item 根布局实测 LinearLayout 子类」吻合）。
- `lh3/l1` 的成员方法名（`_r4_dexref.py methods`）直接暴露了 item 的全部子控件 getter：
  `getPublishTimeText / getNickNameText / getContentText / getCommentContainer / getItemHeader / getItemFooter / getAvatarImage / getDebugLabel / getAddressStub / getTypeIcon ...` —— 这是时间线 item 的完整结构。

### 1.3 相关兄弟类（同一条 item 链上的三个类）

| 混淆名 | 原名（据 const-string） | 父类 | 角色 |
|---|---|---|---|
| `lh3/l1` | `com.tencent.mm.plugin.sns.ui.improve.item.ImproveTimelineItemBuild` | LinearLayout | item 根视图，持有时间 TextView 字段 `o` |
| `lh3/x1` | `com.tencent.mm.plugin.sns.ui.improve.item.ImproveTimelineItemMeasure` | `lh3/l1` | 继承 Build；`d()`=realTimeUpdate 真正 setText |
| `lh3/l0` | `com.tencent.mm.plugin.sns.ui.improve.item.ImproveTimelineItemLoad` | `lh3/s1` | 装载/绑定逻辑，`G()`=`onBindViewHolder$suspendImpl` |
| `oh3/p` | `com.tencent.mm.plugin.sns.ui.improve.model.ImproveSnsInfo` | `jj/e6` | 数据模型（内存里的那条动态） |

**证据**：每个类的某个方法体里都带 `const-string vX, '<原类名>'` 埋点串（`SnsMethodCalculate.markStartTimeMs` 的第二/三参），见各方法反汇编。

### 1.4 bind 方法（数据 → View 的入口）

improve 时间线是 **MVVM**，没有传统单一大 `bind()`，拆成三段：

- `lh3/l0.G(Llh3/l0;Lki4/m0;Lqh3/a;IZLkotlin/coroutines/Continuation;)Ljava/lang/Object;`
  = `onBindViewHolder$suspendImpl`（classes3.dex，code=0x6e4820，const-string `'onBindViewHolder'` + 类名 `'...improve.item.ImproveTimelineItemLoad'`）。
  这是 RecyclerView 绑定的真正回调（suspend 版）。
- `lh3/x1.s()` = `loadNecessaryInfo`（code=0x6ee79c，const-string `'loadNecessaryInfo'`），装载头像/正文/时间等必要信息。
- `lh3/x1.d()` = `realTimeUpdate`（code=0x6ee088，const-string `'realTimeUpdate'`），**把时间文案写到 TextView**（见 §2.3）。

---

## 2. 时间控件（TextView）

### 2.1 资源 id：**无 XML 资源 id —— 代码 new 出来的**

**证据**（classes3.dex）：
- `lh3/l1.<init>`（code=0x6e5420）在 insn `0x459` 处：
  ```
  0x459  iput-object v0, vX, Llh3/l1;->o:Landroid/widget/TextView;   (field_id=31998)
  ```
  其上一句 `0x455` 是 `invoke-static/range lh3/l1.l(...)`（method@0xa1e9）的 `move-result-object`，
  即 `o = l(...)`。`l()` 方法体（code=0x6e4fa4）：
  ```
  0x0000  const-string v0, 'createImproveTextView$default'
  0x0002  const-string v1, 'com.tencent.mm.plugin.sns.ui.improve.item.ImproveTimelineItemBuild'
  0x0023  const-string v14, 'createImproveTextView'
  0x0035  new-instance v3, Lcom/tencent/mm/plugin/sns/ui/improve/view/ImproveTextView;
  0x003b  invoke-direct ImproveTextView.<init>
  0x0040  invoke-virtual TextView.setSingleLine
  0x0049  invoke-virtual TextView.setTextColor
  0x005b  invoke-virtual ImproveTextView.setTextSize
  ```
  ⇒ 发布时间 TextView 是 `com.tencent.mm.plugin.sns.ui.improve.view.ImproveTextView` 实例，**在构造器里程序化创建**（`createImproveTextView`），不是 `findViewById(R.id.xxx)` 从布局 inflate 的。
  ⇒ 因此**不存在**形如 `com.tencent.mm.plugin.sns:id/xxx` 的资源 id（`l()` 里 `setId(vX)` 是可选参数，仅当传入 id>0 才设置，且构造器对 `o` 未走该分支——`o` 赋值前后无 `setId` 常量）。
  ⚠️ 这是与「老版时间线」的本质区别：老版时间线 item 来自布局 XML；新版 improve 时间线 item 是纯代码构建。

### 2.2 ViewHolder 字段：`lh3/l1.o`，类型 `Landroid/widget/TextView;`

**证据**：
- field_id=31998，`class=Llh3/l1; name='o' type='Landroid/widget/TextView;'`（classes3.dex field_ids 表）。
- getter/setter：
  - `getPublishTimeText()Landroid/widget/TextView;`（code=0x6e4f78）：
    ```
    0x0007  iget-object v0, v1, Llh3/l1;->o:Landroid/widget/TextView;
    0x000c  return-object v0
    ```
  - `setPublishTimeText(Landroid/widget/TextView;)V`（iput-object `->o`）。
- 字段读写点全部集中在 `lh3/l1.<init>`（0x459 iput）、`getPublishTimeText`（0x0007 iget）、`setPublishTimeText`（0x0008 iput），无其它类直接碰 `o`。

### 2.3 setText 指令地址：`lh3/x1.d()` 的 `0x001e`

**证据**（classes3.dex，`_r27_disasm.py --class Llh3/x1; --method d`，code=0x6ee088）：
```
0x0000  const-string/jumbo v0, 'realTimeUpdate'
0x0003  const-string v1, 'com.tencent.mm.plugin.sns.ui.improve.item.ImproveTimelineItemMeasure'
0x0008  iget-object vX, vY, Llh3/x1;->V:Lqh3/a;          ← 数据持有者（包着 ImproveSnsInfo）
0x000c  invoke-virtual Lqh3/a;->k                          ← k() 取模型
0x0013  invoke-virtual Llh3/l1;->getPublishTimeText        ← 取时间 TextView（字段 o）
0x0017  if-nez v3, ...                                     ← null 则跳过
0x001a  invoke-virtual Loh3/p;->J0                         ← ImproveSnsInfo.getTimeString()
0x001e  invoke-virtual Landroid/widget/TextView;->setText   ← ★ setText 落点
```
即：**`realTimeUpdate`（`lh3/x1.d()`）在 0x0013 取时间 TextView，0x001a 取文案，0x001e setText**。

---

## 3. 时间文案格式化入口（CreateTime → 「x分钟前 / 昨天」）

### 3.0 ⭐ 先纠正任务书的一个前提

**时间文案的中文常量不在 dex 里，而在 `resources.arsc`。**

证据：
- 全 15 个 dex 原始字节搜 `分钟前 / 小时前 / 昨天 / 天前 / 秒前 / 月前`：**0 命中**；`分钟`/`小时`/`刚刚` 命中的全是无关语境（「5分钟」「半小时」「清理多少（分钟）以前的朋友圈」等），无一条是时间文案。
- 而 `_cache/base.apk` 的 `resources.arsc`（已导出 `_cache/resources.arsc`）中：
  ```
  0x7f0e0009  plurals/j  "%d小时前"
  0x7f0e000a  plurals/k  "%d分钟前"
  0x7f0e000b  plurals/l  "%d天前"      （据 ns.a 0x015c 引用，见下）
  0x7f102d51  string/hi9 "昨天"
  ```
  （aapt2 `dump resources` 输出，见 `_cache/_res_dump.txt`）
- ⇒ 任务书里「可能带中文常量 `分钟前`/`昨天`/`小时前`」这个假设在 8.0.48 国际版不成立：文案走 Android 资源，dex 里只有资源 id 的 `const vX, 0x7f0e0xxx` 立即数。

### 3.1 格式化类：`com.tencent.mm.plugin.sns.ui.ns`（= `SnsTimeUtil`）

**证据**（classes3.dex）：
- `ns.a` 方法体：
  ```
  0x0003  const-string v0, 'formatSnsTimeInTimeLine'
  0x0005  const-string v2, 'com.tencent.mm.plugin.sns.ui.SnsTimeUtil'
  ```
  ⇒ `ns` 的原类名 = `com.tencent.mm.plugin.sns.ui.SnsTimeUtil`（log tag `MicroMsg.SnsTimeUtil`）。

### 3.2 方法签名：`ns.a(Landroid/content/Context;J)Ljava/lang/String;`

- `ns.a` = `formatSnsTimeInTimeLine`，**static**（`invoke-static` 调用，`direct` 方法）。
- 完整签名（`_r4_dexref.py methods`）：
  `Lcom/tencent/mm/plugin/sns/ui/ns;->a(Landroid/content/Context;J)Ljava/lang/String;`
- 时间计算用 `GregorianCalendar` 逐档判断（`ns.a` code=0x4d986c）：
  ```
  0x008b  const v7, 0x7f0e000a   → getQuantityString  (x分钟前)
  0x00e5  const v7, 0x7f0e0009   → getQuantityString  (x小时前)
  0x0119  const v0, 0x7f102d51   → getString          (昨天)
  0x015c  const v7, 0x7f0e000b   → getQuantityString  (x天前)
  ```
`ns`（= `SnsTimeUtil`）**全套方法签名**（classes3.dex，`_r28fw_cls.py` 输出，父类 `Ljava/lang/Object;`，access=0x0401 即 `public abstract`）：

| 签名 | 原名（据方法体 const-string） | 用途 |
|---|---|---|
| `static a(Context;J)String` | `formatSnsTimeInTimeLine` | **时间线「x分钟前/昨天」**（本报告主角） |
| `static b(Context;J)CharSequence` | （被 `c`/`d` 内部调用的底层，带样式 span） | 详情/陌生人列表的底层 |
| `static c(Context;J)String` | `fromatSnsTimeInDetail`（微信拼错 fromat） | **详情页** |
| `static d(Context;J)String` | （陌生人评论详情 / 朋友圈消息列表） | 陌生人/消息列表 |
| `static e(Context;I)String` | `getMonth` | 取月份名（string-array 0x7f030042） |
| `static f()I` | `getYear` | 取当前年份 int（`GregorianCalendar.get(YEAR)`，code=0x4d97f4） |
| `static g(Context;String)V` | — | 未取证用途（本 lane 不涉及） |
| `static h(J)Z` / `static i(J)Z` | — | 时间是否今天的判定 |

### 3.2bis ⭐ 格式化入口**独立于 item 布局**（✅ 确认）

**证据**：
- `ns` 父类是 `Ljava/lang/Object;`（不是任何 View/Activity/item 类），全部方法 `static`，签名只吃 `(Context, 原始类型)`，**不引用也不返回任何 item/View 类**（方法表见上）。
- `ns.a` 方法体里只碰 `GregorianCalendar` + `Resources.getQuantityString`，无 `lh3/*`、无 `TextView`、无 `setText`。
- ⇒ **换文案不用碰 View**：时间文案的生成（`ns.a`）与文案的落点（`lh3/x1.d()` 里的 `TextView.setText`）物理分离。要改「x分钟前」的字样只需在 arsc 或 `ns.a` 层动手，与 item 布局无关。

> ⚠️ 但要区分「格式化入口独立」≠「SnsTimeUtil 只被时间线用」：`ns` 这个**类**被相册页复用了（用的是 `ns.f`=getYear 做相册日期分组，见 §4.5），只是**时间线专属的 `ns.a`（formatSnsTimeInTimeLine）不碰相册**。两者要分开陈述。

### 3.3 调用链（CreateTime int → 界面文案）

```
SnsObject.CreateTime (int, 秒)
   └─ jj/e6.getCreateTime()                    (ImproveSnsInfo 父类，返回 createTime)
        └─ oh3/p.J0()                          (= ImproveSnsInfo.getTimeString)
             └─ ns.a(Context, createTime)      (= SnsTimeUtil.formatSnsTimeInTimeLine)
                  └─ Resources.getQuantityString(0x7f0e000a, n)  → "n分钟前"
```

**证据**：`oh3/p.J0`（code=0x761fdc）：
```
0x0000  const-string v0, 'getTimeString'
0x0002  const-string v1, 'com.tencent.mm.plugin.sns.ui.improve.model.ImproveSnsInfo'
0x0007  sget-object v2, z2.a                      (全局 Context)
0x0009  invoke-virtual Ljj/e6;->getCreateTime
0x0011  invoke-static ns.a                        (formatSnsTimeInTimeLine)
0x001d  return-object v2
```
（注：`J0` 返回 String，`ns.a` 是 static，`getCreateTime` 在父类 `jj/e6` 上。）

`J0`（getTimeString）的调用点（`/tmp/callers.py` 全 dex 扫描）：
```
lh3/x1.d()   (realTimeUpdate)          ← 真正 setText 处
lh3/x1.s()   (loadNecessaryInfo)       ← 日志用
lh3/l0.G()   (onBindViewHolder)        ← 绑定用
OldVersionItemView.d()                 ← improve 时间线的「老版兼容」视图
oh3/p.toString()                       ← 数据元素 toString（见 §4 复用风险）
```

---

## 4. 复用范围（关键）

### 4.1 时间格式化 `ns.a` 只被「时间线」调用

`ns` 各方法调用点（全 dex，`/tmp/callers.py`）：

| 方法 | 场景 | 调用者（类 → 方法，含身份） |
|---|---|---|
| `ns.a`（时间线） | 时间线 | `bf3/t0.y0`（= `SnsUtil.snsInfoToSnsStruct`，**老版时间线**数据转换）；`oh3/p.J0`（= `ImproveSnsInfo.getTimeString`，**新版时间线**模型） |
| `ns.c`（详情） | 详情页 | `SnsCommentDetailUI$CommentListAdapter.getView`、`SnsCommentDetailUI.m7`、`SnsInfoFlip.G` |
| `ns.d` | 陌生人类/消息列表 | `mo.getView`（= `SnsStrangerCommentDetailUI$CommentListAdapter`）、`xj.getView`（= `SnsMsgUI$SnsMsgAdapter`） |
| `ns.f`（getYear） | **好友个人相册** | `qg3/i.onBindViewHolder`（= `SnsAlbumAdapter`）`0x00ab`，用于相册日期分组（见 §4.5） |

**结论**：`ns.a`（formatSnsTimeInTimeLine）的调用者**只有时间线**（老版 `SnsUtil` + 新版 `ImproveSnsInfo`），相册/详情页不碰它。`SnsTimeUtil` 类本身被相册复用，但只用了 `ns.f`（getYear）做日期分组，与「x分钟前」文案无关。

### 4.2 时间 TextView 所在类 `lh3/l1`（ImproveTimelineItemBuild）仅 improve 时间线用

- `oh3/p`（ImproveSnsInfo）的 `y0()`（= `getLocalIDNumber`，即 snsId）调用点（全 dex）全部落在 improve 时间线相关包：`ch3/d`、`ch3/y`、`lh3/m1`、`lh3/x1`、`mh3/a0`、`qh3/a`（= `getUniqueId`）、`rh3/l`、`rh3/m`。
- `lh3/l1.getPublishTimeText` 调用点只有 `lh3/w1.run` 与 `lh3/x1.d`，都在 improve item 包内。
- `ImproveSnsInfo`/`ImproveTimelineItemBuild`/`ImproveTimelineItemMeasure`/`ImproveTimelineItemLoad` 全在 `com.tencent.mm.plugin.sns.ui.improve.*` 包，这是**新版时间线专属包**；详情页（`SnsCommentDetailUI`）、消息列表（`SnsMsgUI`）、陌生人页（`SnsStrangerCommentDetailUI`）走 `com.tencent.mm.plugin.sns.ui` 下的老类。

### 4.3 相册（好友个人相册）不在这条链上

- `ns` 全套方法（a/b/c/d/e）的调用者里**没有**相册相关类（`SnsTimeLineUserPagerUI` 等）。
- improve item 类（`lh3/*`、`oh3/p`）的实例化/引用点也全在 improve 时间线包。
- ⇒ 追加文字**不会误伤相册**：相册若展示时间，走的是另一套（不在 `ns.a`/`oh3/p.J0` 链上）。

### 4.4 一个真实风险：`oh3/p.J0` 被 `toString()` 复用

`oh3/p.toString()` 也调 `J0()`（getTimeString）。而 `docs/conclusions/moments.md` #4 的标签范式是 `adapter.getData()` 元素的 `toString()` 里解析 `snsID`。
⇒ **若直接 hook `J0()` 返回「原文案+[删除已拦截]」，会污染数据元素的 toString，进而污染 SnsTag 的 snsID 解析。** 这就是「复用时必须还原原文案」的具体含义之一：不要在模型层 `J0`/`ns.a` 上追加，要在**纯 UI 层**（TextView.setText 之后）追加。

### 4.5 ⭐ 好友个人相册页（SnsAlbumUI）不复用时间链 —— 铁证

**页面**：`com.tencent.mm.plugin.sns.ui.album.SnsAlbumUI`（父类 `Lcom/tencent/mm/ui/MMFragment;`，classes3.dex）。

**adapter**：`qg3/i`（= `SnsAlbumAdapter`，父类 `Landroidx/recyclerview/widget/w1;` 即原生 `RecyclerView.Adapter`），由 `SnsAlbumUI.dealContentView` 塞入：
```
0x003e  new-instance Lqg3/i;                     ← SnsAlbumAdapter
0x0053  const-string '...ui.album.SnsAlbumAdapter'   ← 类名埋点串
0x008c  RecyclerView.setAdapter                  ← 塞给原生 RecyclerView（非 WxRecyclerView）
0x0091  new-instance LinearLayoutManager         ← 原生 LinearLayoutManager
```

**item 是 XML inflate，不是代码 new 的 improve item**（`qg3/i.onCreateViewHolder`）：
```
0x0010  LayoutInflater.from
0x0014  const v2, 0x7f0c1605 / 0x7f0c1607 / 0x7f0c1604   ← 三种 item 布局资源 id
0x0017  LayoutInflater.inflate
0x001b/0x0033/0x0048  new-instance qg3/d / qg3/g / qg3/h   ← 三种 ViewHolder
```
ViewHolder 是 `qg3/d`、`qg3/g`、`qg3/h`，**不是** `lh3/l1`（ImproveTimelineItemBuild）。

**相册自己的日期 TextView**：`qg3/h.A:Landroid/widget/TextView;`（`onBindViewHolder` `0x00a7 iget-object v15, v0, Lqg3/h;->A:Landroid/widget/TextView;`），走的是 `handleDateTv` + `ns.f`（getYear），不是 `lh3/l1.o` 也不是 `ns.a`：
```
0x0099  const-string 'handleDateTv'
0x00a7  iget-object v15, v0, Lqg3/h;->A:Landroid/widget/TextView;   ← 相册日期 TextView（字段 A）
0x00ab  invoke-static Lcom/tencent/mm/plugin/sns/ui/ns;->f          ← getYear（年份分组）
0x00bd  const v9, 0x7f104b15                                        ← 相册日期相关文案资源
```

**结论（确定性）**：
1. 好友个人相册页的列表 = `qg3/i`（SnsAlbumAdapter）+ XML 布局 + 原生 RecyclerView，**与时间线 improve item 链（`lh3/l1`、`oh3/p`、`ns.a`）完全不相交**。
2. 相册自己的日期控件是 `qg3/h.A`（`findViewById` 得到，显示「2023年/今天」式分组），**不是** 时间线的 `lh3/l1.o`（代码 new 的 ImproveTextView，显示「x分钟前」）。
3. ⇒ **在时间线时间 TextView（`lh3/l1.o` / `getPublishTimeText`）上追加 `[删除已拦截]` 不会误伤好友个人相册页**：相册 item 根本没有 `getPublishTimeText` getter、没有字段 `o`，主会话 §5.1 里 `callMethod(itemView, "getPublishTimeText")` 会返回 null 并被 `if (timeText != null)` 天然跳过。
4. 附注（供另一并行 lane）：`SnsTimeLineUserPagerUI`（好友**个人时间线**，父类 `SnsTimeLineUI`，classes3.dex 只有 `<init>`、逻辑全在父类）继承的是**时间线** UI 而非相册 UI，属时间线范畴，不在「相册」这条误伤判断内。

5. ⭐ 相册渲染数据源 = **SnsInfo 存储实体（读库）**，非响应事件对象（供 lane2 对接）：
   - `qg3/i.b0()`(=access$000，code=0x95630c) 0x0007 `iget-object qg3/i;->d:Ljava/util/List;`（数据字段 d）。
   - `qg3/i.h0()`(=addSize，code=0x956498) 0x000f iget d → 0x003f `iget-wide Ljj/ca;->field_snsId:J`（元素带 field_snsId）。
   - `qg3/i.onBindViewHolder`（code=0x95682c）0x0085 `invoke-virtual com.tencent.mm.plugin.sns.storage.SnsInfo;->getHead`（元素类型 = 未混淆 SnsInfo）。
   - 类型关系：`SnsInfo` super = `Ljj/ca;`（`jj/ca` 是 SnsInfo 存储表实体，有 `batchInsert/insert/select/selectByCreateTime/selectByHead/delete` + `field_snsId/field_createTime`，父类 `k74/f0` 自动 DB 实体基类）。
   - ⇒ 相册渲染层直接拿 SnsInfo 存储对象，**不经过 `oh3/p`(ImproveSnsInfo)**；lane2 写 SnsInfo 表的注入行（snId/createTime/attrBuf）会被相册查询查出并进入 `qg3/i.d` 渲染。与时间线「oh3/p 模型层」是两套数据通路。

6. ⭐ 相册主数据装载路径 + 分页窗口（供 lane2 判断「首屏立现 vs 需翻页」）：
   - 装载类：`qg3/k` = `SnsAlbumAdapterHelper`（父类 `com.tencent.mm.plugin.sns.ui.n`，classes3.dex）。
   - `qg3/k.b()` = `loadData`（code=0x956bbc，const-string 'loadData'）主装载：
     ```
     0x0009  iget qg3/k.h:Map → LinkedHashMap.clear      ← 清分组 Map
     0x001c  iget qg3/k.e:String                          ← limitSeq（分页游标）
     0x002a  const-string 'loadData isSelf=%s limitSeq=%s isPrivate=%s'   ← 日志直证 limitSeq
     0x0033  const-string 'getSnsMediaListByUserName' → f4.ia（SnsInfoStorageLogic）
     0x0041  const-string 'getCursorForAlbumHistory' → d2.H5（SnsInfoStorage）+ d2.p6/d2.Y2 拼 WHERE
     0x0092  iget d2.d → k2.k 执行查询                    ← 返回 Cursor
     0x00ae  moveToFirst → 0x00b6 new SnsInfo → 0x00b9 convertFrom(Cursor) → 0x00bc ArrayList.add（循环 moveToNext）
     ```
   - 填充：查询结果 List 交给 `qg3/k.c()` = `publicNotify`（code=0x957034）：`0x0038 ArrayList.clear`（清 `qg3/i.d`）→ `0x0043 add 分组头('my_timeline')` → `0x0054 addAll(List)` → `0x00a3 notifyDataSetChanged`。
   - **分页窗口 = 有**：`qg3/k.e:String` = limitSeq（构造器 `<init>(qg3/j;String;Z)` 初始 `''`，`0x000a iput -> e`），查询 WHERE 用 `getCursorForAlbumHistory` 按 `userName + limitSeq` 过滤。
   - ⇒ **注入行是否首屏立现，取决于其 createTime 是否落在 limitSeq 窗口内**（limitSeq 是时间游标，查库按时间倒序取窗口）。createTime 越新越可能首屏可见；若不在首屏窗口则需翻页/下拉到对应时间位置触发下一窗口查询。lane2 真机验证时建议重点看「进相册首屏是否可见注入行」。
   - 附带确认：相册日期分组用 `ns.i`(formatTimeInSnsAlbum，loadData 0x0116) + `ns.h`(今天判定，0x012f)，日期头用 `ns.f`(getYear)——**三者均非时间线 `ns.a`**，再次坐实「时间线 vs 相册的时间格式化互不共享 ns.a」。

---

## 5. 给主会话的实现建议（精确签名 + 日志判据）

### 5.1 推荐方案：UI 层追加，不碰模型/格式化层

**主链（拿 snsId + itemView）** —— 复用现有 `SnsTag` 的范式，零新增微信类名依赖：

```kotlin
// ① 已有钩子：com.tencent.mm.view.recyclerview.WxRecyclerAdapter#onBindViewHolder(holder,pos)
//    （SnsTag 已挂，2参/3参重载 + WxRecyclerView.setAdapter 自愈补挂）
// ② 判据：adapter.javaClass.name.startsWith("com.tencent.mm.plugin.sns.") 早退
// ③ snsId：adapter.getData()[pos].toString() → SnsTagLogic.parseSnsId（已有）
// ④ itemView = holder.itemView（必须是 LinearLayout 子类，排除 RecyclerView，SnsTag 已有）
```

**新增的「时间标签」落点**：在 ④ 拿到 itemView 后，取时间 TextView 并追加：

```kotlin
// itemView 即 ImproveTimelineItemBuild（lh3/l1）。时间 TextView 字段名已混淆为 'o'，
// 用公开 getter 更稳（字段名 'o' 也可能随版本漂移，getter 名 'getPublishTimeText' 更可读但同样可能被混淆——两者都给出）：
val timeText: TextView? = runCatching {
    XposedHelpers.callMethod(itemView, "getPublishTimeText") as? TextView
}.getOrNull() ?: runCatching {
    XposedHelpers.getObjectField(itemView, "o") as? TextView
}.getOrNull()

if (timeText != null && isBlockedDeleted(snsId)) {
    val base = stripMarker(timeText.text.toString())   // ★ 还原原文案：先剥掉上次追加的标记
    timeText.text = "$base [删除已拦截]"               // 追加灰字
}
```

### 5.2 兜底（防 `realTimeUpdate` 定时覆盖）

`lh3/x1.d()`（realTimeUpdate）会周期性重写时间文案（§2.3 的 setText），单次追加会被覆盖。兜底 hook：

```kotlin
// 类 = com.tencent.mm.plugin.sns.ui.improve.item.ImproveTimelineItemMeasure（= lh3/x1）
// 方法 = "d"（realTimeUpdate，无参，void）
XposedHelpers.findAndHookMethod(
    "com.tencent.mm.plugin.sns.ui.improve.item.ImproveTimelineItemMeasure",
    classLoader, "d",
    object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // this = lh3/x1（继承 getPublishTimeText）
            val timeText = XposedHelpers.callMethod(param.thisObject, "getPublishTimeText") as? TextView ?: return
            val snsId = snsIdOf(param.thisObject)   // 见下
            if (isBlockedDeleted(snsId)) {
                val base = stripMarker(timeText.text.toString())
                timeText.text = "$base [删除已拦截]"
            }
        }
    }
)

// snsIdOf：从 lh3/x1 拿模型 → 模型拿 snsId
//   lh3/x1 字段 V:Lqh3/a;（数据持有者）→ qh3/a.k() 返回 oh3/p（ImproveSnsInfo）
//   → oh3/p.I0()=getSnsTableId() 返回 String（无符号十进制） / oh3/p.y0()=getLocalIDNumber() 返回 long
```

### 5.3 为什么推荐「UI 层追加」而不是格式化方法

| 候选 hook 点 | 能否拿到 snsId（区分哪条被删） | 复用风险 | 结论 |
|---|---|---|---|
| `ns.a`(formatSnsTimeInTimeLine) | ❌ 参数只有 `(Context,long)`，无 snsId | 老/新时间线共用 | 只适合「全部时间线统一加字」，不符合「只给被删的加」 |
| `oh3/p.J0`(getTimeString) | ✅ 在模型上 | ❌ 被 `toString()` 复用，污染 SnsTag 的 snsID 解析 | 不推荐 |
| `lh3/x1.d`(realTimeUpdate) 之后 setText 追加 | ✅ `this` 上有模型 `V→oh3/p` | 仅 improve 时间线 | ✅ 兜底 |
| `WxRecyclerAdapter.onBindViewHolder` 后取 itemView 的时间 TextView 追加 | ✅ 已有 `getData()[pos]→snsId` | 仅 improve 时间线（itemView 判类型） | ✅ 主链（与 SnsTag 同构，工程心智一致） |

**「复用时必须还原原文案」怎么满足**：
1. RecyclerView 复用：`stripMarker()` 先把上次追加的 `[删除已拦截]` 剥掉，再按「当前 item 是否被删」决定是否重新追加，避免（a）同 View 二次追加叠字、（b）被删 item 的 View 复用到正常 item 后残留标记。
2. 定时覆盖：`realTimeUpdate` 每次 setText 都会用**纯原文案**覆盖，所以 `afterHookedMethod` 里必须再次 `stripMarker → 追加`，等价于每次重算时「先还原再追加」。

### 5.4 失败时的日志判据（区分「钩子没触发」 vs 「触发了但判定为不需要标记」）

必须打三类日志，缺一类就无法区分：

| 日志 | 含义 | 反静默要点 |
|---|---|---|
| `TIMELABEL hook installed (adapter / realTimeUpdate)` | 钩子安装成功 | install 后各打一条；`onFailure` 打 `hook failed + 异常类名` |
| `TIMELABEL bind cls=<adapter类名> pos=<pos> snsId=<id> itemView=<itemView类名> timeText=<null/类名>` | 触发且有 itemView/时间控件 | 首屏 first-hit 去重打一次；每 N 次打统计。`timeText=null` 说明类名/字段漂移 |
| `TIMELABEL decide snsId=<id> hit=<0/1> base=<原文案> result=<追加后>` | 判定结果 | **hit=0 也必须打**（这是「触发了但不需要标记」的判据，不打就分不清是漏判还是没触发） |

区分逻辑：
- 只有 `installed` 没有 `bind` → 钩子没触发（adapter 类名变了 / 早退判据误杀）。
- 有 `bind` 但 `timeText=null` → 触发了但取不到时间控件（字段名 `o`/getter 漂移，换 `getObjectField(itemView,"o")` 或读 `itemView` 子 View）。
- 有 `bind` + `timeText!=null` 但 `hit=0` → 判定正确，只是这条不是被删动态（正常现象）。
- `hit=1` 但界面没显示 → 被 `realTimeUpdate` 覆盖了（兜底钩没生效），查 `realTimeUpdate` 的 after 日志。

### 5.5 与既有 `SnsTag.kt` 的叠加关系（对照基准）

主会话已有 `feature/SnsTag.kt`（836 行，`HookEntry.kt:244-246` 注册，真机在跑）实现了「时间线 item onBind 打标」的完整链，本 lane 不重复推导、只叠加「时间 TextView 定位」这一块空白。对照如下：

| SnsTag 已实现（本 lane 直接复用） | 关键点（SnsTag.kt 行号） | 本 lane 新增的叠加 |
|---|---|---|
| `ADAPTER_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerAdapter"` 挂 `onBindViewHolder` | `hookAdapterClass` :152 | 无需改 |
| `WxRecyclerView.setAdapter` 自愈补挂（防 adapter 动态重建） | `hookSetAdapter` :176 | 无需改 |
| `adapter.javaClass.name.startsWith("com.tencent.mm.plugin.sns.")` 早退 | `onBind` :265-266 | ⚠️ 相册 `SnsAlbumAdapter` 也满足该前缀，会进 onBind；靠「时间控件取不到 → 跳过」兜住（见下） |
| `itemViewOf(holder)` 排除 RecyclerView 本身 | `itemViewOf` :321 | 无需改 |
| `resolve(adapter,pos,itemView)` 名字对账 ±2 窗口唯一命中 → snsId | `resolve` :395 → `infoAt` :439 → `infoOf` :492 | 无需改 |
| `ensure`/`sweep` 幂等 addView index0 + 每 2s 重判 | `ensure` :664 / `sweep` :568 | 无需改 |
| — | — | **新增：拿 snsId 后取时间 TextView 追加 `[删除已拦截]`**（§5.1 的 `getPublishTimeText`/字段 `o`） |
| — | — | **新增：hook `lh3/x1.d()`（realTimeUpdate）兜底防定时覆盖**（§5.2） |

**叠加点的一句话说明**：SnsTag 已经在 onBind 里拿到了 `itemView`（= `lh3/l1`）和 `snsId`，主会话只需在 `apply(itemView, snsId)` 内部，用 §5.1 的两步回退取到时间 TextView 后追加即可；**无需新增任何微信类名依赖、无需再挖 adapter 链**。

**相册误伤的自愈**：SnsTag 的早退前缀会放进 `SnsAlbumAdapter`（`com.tencent.mm.plugin.sns.ui.album.SnsAlbumAdapter`），但 §4.5 已证相册 item 是 `qg3/h`（无 `getPublishTimeText`、无字段 `o`），`callMethod(itemView,"getPublishTimeText")` 抛异常 → `runCatching` 取 null → `if (timeText != null)` 跳过，**不会误追加**。若要更省，可在 `apply` 前加一句 `itemView.javaClass.name == "com.tencent.mm.plugin.sns.ui.improve.item.ImproveTimelineItemBuild"` 精确判型（比字段判空更早退，且带日志可观测）。

---

## 附：证据索引（dex + 类 + 方法 + 偏移）

| 主题 | 位置 |
|---|---|
| adapter `u1` 被 setAdapter | classes3.dex `ImproveMainUIC.onCreate` 0x002d/0x0031（code=0x487070） |
| `u1` 父类 `ju2/s0` | classes3.dex class_def superclass_idx |
| item 视图 `lh3/l1` = ImproveTimelineItemBuild | classes3.dex `getPublishTimeText` 0x0002 const-string |
| 时间字段 `lh3/l1.o:TextView` | classes3.dex field_id=31998 |
| 时间 TextView 程序化创建 | classes3.dex `lh3/l1.l`（createImproveTextView，code=0x6e4fa4）0x0035 new-instance ImproveTextView |
| 时间 setText | classes3.dex `lh3/x1.d`（realTimeUpdate，code=0x6ee088）0x0013/0x001a/0x001e |
| 格式化 `ns.a` = formatSnsTimeInTimeLine | classes3.dex `ns.a`（code=0x4d986c）0x0003/0x0005 const-string；0x008b/0x00e5/0x0119/0x015c 资源 id |
| 模型 `oh3/p.J0` = getTimeString | classes3.dex code=0x761fdc 0x0009 getCreateTime / 0x0011 invoke-static ns.a |
| `oh3/p.y0` = getLocalIDNumber / `I0` = getSnsTableId | classes3.dex code=0x76228c / 0x761f98 |
| 资源 id ↔ 文案 | `_cache/_res_dump.txt`（aapt2 dump resources）0x7f0e0009/000a/000b、0x7f102d51 |
| 复用范围（ns 各方法调用者） | 见 §4.1 表（`_r27_callers.py` 全 dex 扫描） |
| 相册页 adapter `qg3/i` = SnsAlbumAdapter | classes3.dex `SnsAlbumUI.dealContentView`（code=0x464ee0）0x003e new-instance / 0x0053 类名串 / 0x008c setAdapter |
| 相册 item XML inflate | classes3.dex `qg3/i.onCreateViewHolder` 0x0010 LayoutInflater.from / 0x0014 const 0x7f0c1605|1607|1604 / 0x0017 inflate |
| 相册日期 TextView `qg3/h.A` + `ns.f`(getYear) | classes3.dex `qg3/i.onBindViewHolder`（code=0x95682c）0x0099 handleDateTv / 0x00a7 iget `qg3/h;->A` / 0x00ab invoke-static `ns;->f` |
| `ns.f` = getYear | classes3.dex `ns.f`（code=0x4d97f4）0x0000 const-string 'getYear' / 0x0007 GregorianCalendar |
| 格式化入口独立于 item 布局 | classes3.dex `ns` 父类 Object、全 static、签名只吃 Context+原始类型（§3.2bis 方法表） |

[DONE] 0 结论速览
[DONE] 1 列表与 ViewHolder
[DONE] 2 时间控件
[DONE] 3 时间文案格式化入口（含 3.2bis 独立性确认）
[DONE] 4 复用范围（含 4.5 相册页取证）
[DONE] 5 实现建议（含 5.5 SnsTag 对照基准）
[DONE] 附 证据索引

---

## 附：主会话独立复核（2026-10-27 21:0x · 逐条重跑反汇编，不采信原话）

| # | 复核项 | 命令 | 结果 |
|---|---|---|---|
| 1 | `getPublishTimeText()` 返回的是哪个字段 | `_cache/_r30_disasm.py 'Llh3/l1;' getPublishTimeText` | ✅ @0x0007 `iget-object v2, v3, Llh3/l1;->o:Landroid/widget/TextView;` → `return-object`（字段 `o`，类型 TextView） |
| 2 | `lh3/x1.d()` 里确有 `setText` | `_cache/_r30_disasm.py 'Llh3/x1;' d setText` | ✅ **两处**：`@0x001e` 与 `@0x0059`（子代理只报了 0x001e，实际 0x0059 也是） |
| 3 | `SnsTimeUtil` 的方法族 | `tools/_r28fw_cls.py 'Lcom/tencent/mm/plugin/sns/ui/ns;' --methods` | ✅ `a/b/c/d(Context,J)` 全在；`a()→String` 是时间线用的那个 |
| 4 | `oh3/p.J0` 是 getTimeString | `_cache/_r30_disasm.py 'Loh3/p;' J0` | ✅ @0x0000 `const-string 'getTimeString'`、@0x0009 `invoke getCreateTime` |

### 订正一处

子代理说 `d()` 的 setText 落点是 `0x001e` 一处 —— 实测**有两处**（`0x001e`、`0x0059`）。
⇒ 如果将来改成方案 (b)（hook `d()` 的 after 补追加），**两次 setText 都会覆盖**，hook 一处不够；
走方案 (a)（纳入 `sweep` 每 2s 重判）则天然覆盖两种情况。

### 主会话定的产品决策（执行 lane 照做）

同一条动态**不能出现两个标记**。原 `SnsTag` 会往 itemView 追加绿色 `[已删除]` TextView，
现统一改成"在发布时时间文案后面追加灰色 `[删除已拦截]`"，不再追加独立 TextView。
理由：两个标记语义高度重合（都是"这条已被删、我们留下了"），同时出现会让用户以为功能重复。

### 主会话追加复核：相册页会不会被误伤（2026-10-27 21:1x）

子代理说「相册不复用时间链」，我独立复算并补了一条它没提的**更强证据**：

| 闸 | 事实 | 证据 |
|---|---|---|
| 闸 1 | 相册 adapter `qg3/i` 的父类是 **`androidx.recyclerview.widget.w1`**（原生 `RecyclerView.Adapter`），**不是** `com.tencent.mm.view.recyclerview.WxRecyclerAdapter` | `tools/_r28fw_cls.py 'Lqg3/i;' --methods` → `super=Landroidx/recyclerview/widget/w1;` |
| 闸 2 | 即使被挂上，`SnsTag.onBind`(:271) 有 `adapter.javaClass.name.startsWith("com.tencent.mm.plugin.sns.")` 判定，而 `qg3.i` **不以该前缀开头** ⇒ 直接 return | `SnsTag.kt:271` |
| 闸 3 | 相册 ViewHolder `qg3/h` **没有** `getPublishTimeText()`，也没有 `getYear` 之外的时间 getter ⇒ 反射取时间控件会返回 null，实现里必须 null 跳过 | `tools/_r28fw_cls.py 'Lqg3/h;' --methods \| grep -i publishTime` → 空 |

⇒ **三道闸独立成立**，6.1 的标记不可能落到相册页。

> ⚠️ 对 6.2 的意义：相册页是**另一套 adapter + 另一套 item View**（`qg3/i` + XML inflate `0x7f0c1604/1605/1607`）。
> 所以 6.2 的注入必须在**数据层**（`h3.N` 的 `jc5.f`）生效，靠相册自己的 adapter 去渲染 ——
> 这也正是我们选的路（`SnsAlbumMerge` 就挂在数据层）。若当初选的是"UI 层补视图"方案，相册页得重写一套，成本高得多。
