# R35-1 朋友圈已删除动态打灰标 —— 挂载点取证与重构

> lane: general-purpose-1（独立取证 + 实现，R35-1 续跑）
> 目标微信：8.0.48 / 2588 GP 版
> 状态：**进行中**
> 取证手段：本地 `_cache/dex/*.dex` 静态解析（`_r35_sns_dex.py` / `_r35_sns_scan.py` / `tools/_r27_disasm.py`），**未碰 adb**

## 0. 进度

- [x] 建文档
- [x] 读现状代码
- [x] dex 取证：现挂载点是否还在
- [x] 候选挂载点
- [x] 判据设计
- [x] 落地代码
- [x] 真机取证命令清单

## 0.1 取证前提与置信度

| 项 | 值 | 置信度 |
|---|---|---|
| dex 来源 | `_cache/dex/classes*.dex`（15 个，落盘时间 2026-10-13 00:33） | ⚠️ **未证实**与本轮真机 8.0.48/2588 完全一致，见 §5 需主会话核一条 |
| 反汇编工具 | `tools/_r27_disasm.py`（本仓库既有，带指令偏移） | 高 |
| 类/字段/方法名 | 直接从 dex string pool / class_def 读出 | 高（是 dex 里的事实，不是推断） |
| 「业务真调用了它」 | 全部以 `invoke-*` 指令 + 调用者链为准，见每条证据的 `invoke @0x…` | 高 |
| 时序 | **没有任何时序结论**；所有涉及顺序的说法都标注为**假设** | — |

---

## ① 现挂载点失效取证

### 1.1 现在挂在哪

`SnsTag.install()`（`feature/SnsTag.kt:121`）做两件事：

1. `hookAdapterClass(WxRecyclerAdapter)` —— `c.methods` 过滤 `name == "onBindViewHolder"`，
   形参 `>=2` 且 `参数[1] == int`，且是「2 参」或「3 参 + List」。
2. `hookSetAdapter(cl)` —— 挂 `WxRecyclerView.setAdapter`，抓具体 adapter 类再补挂。

真机 `SNS_TAG installed enabled=true hooks=3` ⇒ **1 挂上了 2 个 + setAdapter 1 个**。

### 1.2 这两个 `onBindViewHolder` 到底是哪个类的方法（dex 事实）

`WxRecyclerAdapter` **自己没有声明** `onBindViewHolder`。完整继承链（跨 dex 实测）：

```
Lcom/tencent/mm/view/recyclerview/WxRecyclerAdapter;            [classes8.dex]  ← 无 onBindViewHolder
  └ Lki4/i0;                                                     [classes.dex]  ← 声明 onBindViewHolder(c3,I,List)
      └ Lcom/tencent/mm/ui/recyclerview/SynchronizedAdapter;      [classes7.dex] ← 声明 onBindViewHolder(c3,I,List)
          └ Landroidx/recyclerview/widget/w1;                     [classes12.dex]← RecyclerView.Adapter 本体
                                                                                   声明 onBindViewHolder(c3,I) 与 (c3,I,List)
```

`WxRecyclerAdapter` 自身 42 个方法里**没有** `onBindViewHolder`（有 `onAttachedToRecyclerView` /
`onViewRecycled` / `onViewDetachedFromWindow` 等，唯独没有 bind）。
⇒ `Class.getMethods()` 返回的两条都是**继承来的**：

| # | 实际 declaringClass | 签名 | 是否会被 SNS 走到 |
|---|---|---|---|
| H1 | `Landroidx/recyclerview/widget/w1;` | `onBindViewHolder(Lc3;I)V` | ❌ **死钩**（见 1.3） |
| H2 | `Lki4/i0;` | `onBindViewHolder(Lc3;ILjava/util/List;)V` | ✅ 应能触发（见 1.3） |

+ `setAdapter` 1 条 = **hooks=3**，与真机日志**完全对上**。

### 1.3 H1 为什么是死钩（有 invoke 证据，不是猜）

`SnsTag` 那条注释写「2 参和 3 参都要挂，只挂 2 参可能完全静默失效」—— 方向对了，但**本次反了**：
在这一版里是 **3 参那个才是活的**，2 参那个是死的。

`tools/_r27_disasm.py --class Lki4/i0; --method onBindViewHolder`（classes.dex，code=0x6b7478）体内只有：

```
0x0026  invoke-virtual Lki4/i0;->k0 {…}            # 分支 A
0x0051  invoke-virtual Lki4/i0;->i0 {…}            # 分支 B
0x005b  invoke-virtual Lki4/i0;->l0 {…}            # 分支 C（主路径）
```

**全程没有 `invoke-super`，也没有任何一处 `invoke-… onBindViewHolder(Lc3;I)V`。**
⇒ `ki4/i0` 覆写 3 参版后**根本不再调用 2 参版**，框架也不直接调 2 参版
（`w1.bindViewHolder → w1.onBindViewHolder(3参)` 的调用点被 `ki4/i0` 的覆写截胡）。
⇒ 挂在 `w1.onBindViewHolder(c3,I)` 上的那条钩子**永远不会回调**。

而 3 参版是被框架调用的（`classes12.dex`：`Landroidx/recyclerview/widget/w1;->bindViewHolder(Lc3;I)V`
`invoke @… -> Landroidx/recyclerview/widget/w1;->onBindViewHolder(Lc3;ILjava/util/List;)V`），
虚派发到链上最具体的实现 = `ki4/i0` 的 ⇒ **H2 是活的**。

### 1.4 由此得出的「为什么还是不显示」

**`hooks=3` 既不能证明挂载点错，也不能证明挂载点对。** 真正需要的数是 `binds / snsBinds / resolved / hits / missed / missedTime`，
真机上还没取过（见 §5）。按 dex 证据把失效空间收窄到这 4 个（按可能性排序）：

| # | 失效点 | dex 证据 | 一眼判据 |
|---|---|---|---|
| **F1** | `resolve()` 拿不到/拿错 id | `SnsTag.resolve` 用「`getData()` 下标 ±2 窗口 + 作者名对账」定位数据。`ki4/i0.onBindViewHolder` 里 `pos` 与实际取数下标的关系**在 dex 里没有一对一的 invoke 证据**（它走 `ArrayList.get(…)` 前还过了 `b0()` / `ki4/r->d()` 两个分支）⇒ 偏移校准是**猜**的。且 `getData()` 实测 = `iget WxRecyclerAdapter->data`（`getData` 反汇编只有 2 条指令），是**当前分页快照**，`list.size` 一变 `offsets` 就全部失效重来 | `missed` 远大于 `resolved` / `lastMiss=ambig…` |
| **F2** | `itemViewOf(holder)` 拿错 View | holder 实测类型是 `Lki4/m0;` → `Lki4/l0;` → `Landroidx/recyclerview/widget/c3;`，**itemView 字段名是 `d`**：`WxRecyclerAdapter.c1` 反汇编 `0x005f const-string 'itemView'` / `0x0061 iget-object v9, Landroidx/recyclerview/widget/c3;->d:Landroid/view/View;`。现代码靠「扫 6 层 View 字段 + parent 是 RecyclerView」兜底，而 `m0` 上还有 `F:Landroidx/recyclerview/widget/RecyclerView;` 字段（被 `isRv` 排除）⇒ 容易选到非 item 根 View | `missed` 涨且 `lastMiss=noitemview…`；或 `resolved>0` 但界面上标记出现在错误位置 |
| **F3** | `timeTextOf` 取不到时间控件 | `getPublishTimeText()` 在 dex 里**还在**，两个类各一份：`Llh3/l1;`（`ImproveTimelineItemBuild`，返回 `Landroid/widget/TextView;`）和 `Lxh3/o2;`（返回 `Lcom/tencent/mm/kiss/widget/textview/PLSysTextView;`）。**⚠️ 类名与 R30 取证时记的 `lh3/x1` 已漂移成 `xh3/o2`**。`PLSysTextView` 实测 `→ SysTextView → X2CTextView → AppCompatTextView → TextView`，`as? TextView` 成立；但**字段 `o` 那条兜底是否还叫 `o` 无证据**（`lh3/l1` 现在可见的字段是 `E…S` 一串 `Lgr4/g;` + `d/e/f/g/h`，没看到 `o`） | `missedTime>0` |
| **F4** | 打上了但被冲掉 | `SnsDeletedTag` 的 KDoc 已记：`ImproveTimelineItemMeasure.d()`（realTimeUpdate）会周期性把纯原文写回。sweep 每 2s 才补一次 | `hits>0` 且 `missedTime=0`，但界面上标记闪一下就没了 |

> ⛔ **没有证据支持「方法没了 / 签名变了」这一假设**：`WxRecyclerAdapter`、`WxRecyclerView`、
> `ImproveRecyclerView`、`getPublishTimeText()`、`ImproveInfo(name=…, snsID='…')` 字面量（`classes3.dex` @11409883 附近）
> 在 dex 里**全部还在**。所以本轮不是「符号漂移」，是「装的地方拿不到/拿错数据」或「画上去被冲掉」。

### 1.5 顺带查清的几件事（都是 dex 事实，后面要用）

| 事实 | 出处 |
|---|---|
| 朋友圈新版时间线的 adapter 是 `Lcom/tencent/mm/plugin/sns/ui/improve/component/u1;` | 继承链 `u1 → Lju2/s0; → WxRecyclerAdapter`；`u1` 只声明 `<init>(MvvmList, ki4/n, t1)` 与 `g(Lju2/n0;)V`，**不覆写** `onBindViewHolder` |
| ⚠️ 中间层 `Lju2/s0;` 是**混淆包名**，不在 `com.tencent.mm.plugin.sns.` 下 | `WxRecyclerAdapter` 的 50 个子类里**没有一个**类名含 `sns`；唯一能连到朋友圈的就是 `ju2/s0 → u1` |
| ⛔ 但 `u1` 本身是 `com.tencent.mm.plugin.sns.ui.improve.component.u1`，**前缀过滤不会误杀它** | 类名原样 |
| `WxRecyclerAdapter` 只声明了 `<init>` / `g(Lju2/n0;)V` / `l1()Z`… 之外的 42 个方法，`onBindViewHolder` 不在其中 | `python _r35_sns_dex.py meth view/recyclerview/WxRecyclerAdapter` |
| SNS 包里**没有任何** adapter 声明 `onBindViewHolder`（唯一一个是广告落地页 `…/AdLandingPageComponent/component/e`，与本功能无关） | 全 dex 扫描 |
| 时间线 item 根 View 类：`Llh3/l1;` extends `LinearLayout`，带 `getPublishTimeText()` | `classes3.dex` |
| `ViewHolder` 的 `itemView` 字段名 = **`d`** | `c1` 反汇编 `0x005f/0x0061` |

---

## ② 候选新挂载点（按证据强度 + 实现代价排序）

### 🥇 候选 A：`WxRecyclerAdapter.c1(Lki4/m0;I)V` + `d1(Lki4/m0;ILjava/util/List;)V` —— **推荐**

**证据（全部是 `invoke` 事实）**

```
ki4/i0.onBindViewHolder(3参)                # 框架入口（classes12: w1.bindViewHolder → 虚派发到此）
  0x005b  invoke-virtual Lki4/i0;->l0 {…}
            └ WxRecyclerAdapter.l0(Lc3;I)V   [classes8 @0x4f8174]  只有 1 条有效指令
                0x0002 invoke-virtual WxRecyclerAdapter->c1 {…}     → 真正在干活的
  0x0051  invoke-virtual Lki4/i0;->i0 {…}  → WxRecyclerAdapter.a1(m0,I,ki4/r)
  0x0026  invoke-virtual Lki4/i0;->k0 {…}  → WxRecyclerAdapter.b1(m0,I,ki4/r)   [k0 @0x4f8158: 只调 b1]
  n0(c3,I,List) [classes8 @0x4f81c0] → 0x0002 invoke-virtual WxRecyclerAdapter->d1 {…}
```

`c1` / `d1` 体内（`classes8` @0x4f7f50 / @0x4f805c）**直接把数据写进 holder**：

```
c1: 0x000e iget-object v8, WxRecyclerAdapter->data:Ljava/util/ArrayList;
    0x0010 ArrayList.get(…)
    0x001b iput-object …, v9, Lki4/m0;->E:Ljava/lang/Object;     ← ★ 数据 item 落进 holder.E
    0x001d invoke-virtual Lki4/i0;->P0(…)                        ← 拿 RecyclerView
    0x0021 iput-object …, v9, Lki4/m0;->F:…RecyclerView;
    0x005f const-string 'itemView'
    0x0061 iget-object v9, Landroidx/recyclerview/widget/c3;->d:Landroid/view/View;   ← ★ itemView
    0x0066 invoke-virtual WxRecyclerAdapter->g1(…)               ← 真正的渲染
d1: 0x0013 …data… 0x0026 iput-object …, v9, Lki4/m0;->E:… （同）
```

`ki4/m0` 自身：`z()Ljava/lang/Object;` 反汇编 = `iget-object v1, Lki4/m0;->E` + `return-object`
⇒ **`E` 就是「这一条的数据对象」的官方 getter 后端**。

**收益**

- `args[0]`（holder）里**同时**有 `E`（数据）和 `d`（itemView）⇒ **一次拿到 View + snsId**。
- **彻底不需要** `getData()` + `pos ±2` 窗口 + 作者名对账 ⇒ 直接消灭 F1（最危险的错位打标）和 F2。
- 位置对账不需要了 ⇒ 也就不受「分页 / 下拉刷新 / 头部结构变化」影响。

**失效风险**

| 风险 | 说明 | 兜底 |
|---|---|---|
| `c1`/`d1`/`a1`/`b1` 是**混淆名** | 版本一变就飘 | 四个全挂；安装时按「名字前缀 c1/d1/a1/b1 + 形参 (m0,I[，List|ki4/r])」*形状*匹配而不是硬编码，匹配不到就记日志降级到候选 B |
| `E` 也是混淆名 | 同上 | 三级兜底：`z()` → 字段 `E` → 退化到候选 B 的 `getData()[pos]` |
| 分支 `a1`/`b1` 没覆盖 | `i0`/`k0` 分支只在 `ki4/r->d()` 为 null 时走（**假设**，无时序证据） | 四条全挂即可覆盖 |

### 🥈 候选 B：`WxRecyclerAdapter.l0(Lc3;I)V` + `n0(Lc3;ILjava/util/List;)V` + `k0(Lc3;ILki4/r;)V`

**证据**：同上，`ki4/i0.onBindViewHolder` 体内 `0x0026 k0` / `0x0051 i0` / `0x005b l0` 三个 `invoke-virtual`，
且 `WxRecyclerAdapter` **确实覆写**了 `l0`、`n0`、`k0`（dex 里有同名同签名的声明）。

**收益**：比现状（`onBindViewHolder`）更靠下、更接近真实渲染；不需要 `onBindViewHolder` 那层虚派发。
**代价**：仍然只有 `(holder, pos)`，还得靠 `getData()` + 位置对账 ⇒ **F1 没解决**，只是把入口挪稳了。
**失效风险**：同样依赖混淆名；且 `i0` 分支（`WxRecyclerAdapter` 未覆写）覆盖不到。

### 🥉 候选 C：保留现状 `onBindViewHolder`，只修 `resolve` / `itemViewOf` / `timeTextOf`

**收益**：改动最小、零新增 hook 风险。
**代价**：`pos ↔ data` 偏移仍靠猜（dex 里**找不到** `pos` 与 `ArrayList.get` 实参一一对应的证据），
`itemViewOf` 的「面积最大 / parent 是 RecyclerView」启发式在 `m0`（自带 `F:RecyclerView` 字段）上天然不稳。
**失效风险**：F1/F2 只能缓解不能根除；一旦微信再改一次头部结构就又静默失效。

### 结论

**上候选 A**，同时把 B 作为 A 装不上时的降级路径，**保留 C 的 `apply()` 呈现逻辑**（那部分没问题，只是入口不对）。

---

## ③ 判据（真机上怎么一眼看出「生效 / 误伤」）

### 3.1 ⛔ 先说清什么**不能**当判据

> **「某条动态不在本轮响应里」⇒ 被删了** —— 这是**错的**，时间线是分页的，
> 实测会把 190/200 条误判。判「已删」只认**物理删除归档 `GroupDb.snsTagIds()`**，
> 界面侧只负责「把清单里的 id 画上灰标」。

### 3.2 生效判据（三条全中才算生效）

| # | 怎么验 | 命令 | 期望 |
|---|---|---|---|
| P1 | 新挂载点装上了 | `python tools/dev.py q 'SNS_DELTAG installed' 5` | `SNS_DELTAG installed hooks=N`，**N≥2**（`c1/d1/a1/b1/l0/n0/k0` 里至少命中 2 条 + setAdapter 1 条） |
| P2 | 钩子弹了、解析通了 | `python tools/dev.py q 'SNS_DELTAG stat' 20` | `binds>0 sns>0` 且 **`resolved`≈`sns`**、`missed=0`、`lastMiss=`（空） |
| P3 | 界面真的画上了 | `python tools/dev.py ui 删除已拦截` | 至少命中 1 个节点；且 `deleted=N` 时命中数 ≤ N（**等于最好，少于说明有 id 不在当前屏**） |

辅助：`python tools/dev.py probe snstag sub stat --grep SNSTAG` 仍可用（那是 **SnsTag** 老链路的数，
用来做**反向对照**：若 `SNS_DELTAG` 的 `resolved` 涨而 `SNSTAG` 的 `resolved` 不涨，
就直接证明①的 F1（老链路 resolve 失败）成立）。

### 3.3 误伤判据（**任一命中即判定为 bug，必须回退**）

| # | 现象 | 怎么验 |
|---|---|---|
| N1 | 清单外的动态被打标 | `dev.py ui 删除已拦截` 命中数 **>** `SNS_DELTAG stat deleted=N` |
| N2 | 标错到邻居 | `probe snstag sub fake --es id <dump 里某条 u=>` → 界面上标记必须落在**那条**动态的时间行上（`dev.py ui` 看 y：标记 y 应≈该动态时间行 y，而不是上下邻居的） |
| N3 | 标记叠字 | `dev.py ui 删除已拦截` 出现 `[删除已拦截] [删除已拦截]` |
| N4 | 串味（复用残留） | 进朋友圈 → 快速上下滚 3 屏 → `dev.py ui 删除已拦截`，命中数应**不变**；变了就是 `strip` 没剥干净 |
| N5 | 关掉开关还挂着 | 关 `Keys.ANTIREVOKE_SNS` → 等 3 秒 → `dev.py ui 删除已拦截` 应**零命中** |

### 3.4 一眼看「到底是 F1/F2/F3/F4 里的哪一个」

看 `SNS_DELTAG stat` 一行即可（`SnsDeletedTag.kt:589`）：

```
SNS_DELTAG stat on=true deleted=5 bound=12 hooks=3 binds=60 sns=60 resolved=58 missed=2 hits=3 missedTime=0 lastMiss=nodata(…)
```

| 读数 | 结论 |
|---|---|
| `hooks=0` | 形状匹配没命中（微信改了 `c1/d1` 签名）→ 回退，改用硬编码名字兜底 |
| `binds=0` | 钩子没弹 → 挂载点错了 |
| `binds>0 sns=0` | 类名前缀过滤写错（真朋友圈 adapter 实测是 `com.tencent.mm.plugin.sns.ui.improve.component.u1`，前缀没问题；若报 0 说明 adapter 换了包） |
| `sns>0 resolved=0 / missed≈sns` | 看 `lastMiss`：`noitemview(…)` = F2，`nodata(…)` = holder 上取不到数据对象 |
| `resolved>0 hits=0` | 归档 id 与解析出的 id 对不上（位模式问题）→ 用 `SnsDeletedTag.dump()` 打 `u=` 与 `deleted()` 对比 |
| `hits>0 missedTime>0` | F3：时间控件反射不到 |
| `hits>0 missedTime=0` 但界面没有 | F4：被 `realTimeUpdate` 冲掉了 |

---

## ④ 代码改动

### 4.1 `feature/SnsDeletedTagLogic.kt`（纯逻辑，可 JVM 单测）

| 行 | 改动 | 说明 |
|---|---|---|
| 36 | 新增 `private val ID_RE` | 从 `toString()` 抓 `snsID='…'`。**新增**而不是复用 `SnsTagLogic.ID_RE`：那个是 private，且本类要保持零依赖 |
| 74 | **新增** `snsIdOf(obj: Any?): Long?` | 候选 A 拿到的 holder 数据对象直接 toString 解析 —— 这是「不再需要 pos 对账」的关键 |
| 85 | **新增** `parseSnsId(raw: String): Long?` | 形状校验（纯数字 ±15~20 位），最后一道防线 |
| 其余 | 原样保留 | `MARK` / `taggedIds` / `isTagged` / `appendTag` 四个函数的契约**一个字节都没动**，既有 7 个单测继续过 |

### 4.2 `feature/SnsDeletedTag.kt`（挂载点 + 呈现）

| 行 | 改动 | 说明 |
|---|---|---|
| 114 | 新增 `bound: WeakHashMap<View, Long>` | 本类自持的「itemView → snsId」，不再依赖 SnsTag 的 `bound` |
| 127 | **新增** `install(lpparam)` | 新挂载点入口（需主会话在 `HookEntry.kt` 加 1 行，见 4.3） |
| 132 | **新增** `ensureInstalled(cl)` | 幂等；先 `findClass` 成功再占位，失败不消耗机会 |
| 163 | **新增** `hookBinds(c)` | 候选 A+B：按**形状**匹配（`形参[0]` 是 ViewHolder、`形参[1]` 是 int）扫本类 + 4 层父类的声明方法 |
| 186 | **新增** `matchesBindShape(m)` | ⛔ 显式排除 `onBindViewHolder`（dex 已证 2 参版是死钩） |
| 196 | **新增** `isViewHolder(t)` | 沿继承链找 `androidx.recyclerview.widget.*` |
| 211 | **新增** `hookSetAdapter(cl)` | 防「子类覆写 `c1/d1` → 挂基类成死钩」（这正是 R35 的病根形态） |
| 249 | **新增** `bindHook` | afterHookedMethod：朋友圈限定 → 取 itemView → 取数据 → 解析 id → paint |
| 293 | **新增** `on()` | 只看 `Keys.ANTIREVOKE_SNS`（R30 拍板：同一开关，不新增 Key） |
| 313 | **新增** `itemViewOf(holder)` | ✅ 优先取**声明在 `androidx.recyclerview.widget.*` 上的 View 字段**（实测字段名 `d`），修掉 F2 |
| 364 | **新增** `dataOf(holder)` | 三级兜底 `z()` → 字段 `E` → 字段 `D`，落负缓存防掉帧 |
| 403 | `apply()` **保持原签名** | 新增：`snsId==0` 时不再无条件还原，改用本类 `bound` 里自己解析出的 id —— 防 SnsTag 解析失败把我们打上的标擦掉（两条链路打架） |
| 426 | **新增** `paint()` | 从 `apply()` 里抽出「判定 + 计数 + 落笔」，供 bindHook / sweep / apply 三处复用 |
| 451 | **新增** `restore()` | 只还原不判定 |
| 477 | **新增** `sweeper` / `sweep()` | 本类自持 2s 重判。⚠️ 必须自己扫：SnsTag 的 sweep 以它自己的 `bound` 为准，它解析不出来时根本不排 → 我们的标记被 `realTimeUpdate` 冲掉后没人补 |
| 533 | **新增** `deleted()` / `loadIds()` | 与 SnsTag 同口径（首屏同步、之后后台、5s TTL、只读 `GroupDb.snsTagIds()`） |
| 589 | **新增** `stat()` / `dump()` | 自诊断。⚠️ **没接探针**（`entry/EntryReceiver.kt` 不在本 lane 允许改的文件里），所以改成**打日志**：`resolved<=3` 与 `resolved%20==0` 时各打一行 `SNS_DELTAG stat …`，真机用 `dev.py q 'SNS_DELTAG stat'` 抓 |
| 623 | `strip()` 原样保留 | 7 个既有单测继续过 |

### 4.3 ⚠️ 需要主会话补的 1 行（不在本 lane 允许改的文件里）

`wxgroup/app/src/main/java/com/lx/wxgroup/HookEntry.kt:249` 现在是：

```kotlin
SnsTag.install(lpparam)
```

在它**下面**加一行：

```kotlin
SnsDeletedTag.install(lpparam)
```

> **不加也不会死**：`SnsDeletedTag.apply()` 被 SnsTag 第一次调到时会用
> `itemView.javaClass.classLoader` 懒装一次（`SnsDeletedTag.kt:405`）。
> 但**加了更稳**（不等 SnsTag 那条链路先弹一次），建议加。

---

## ⑤ 需要主会话在真机执行的取证命令

> 顺序执行。**每条之间不要重启微信**，除非写了 `restart`。

### S0（先做，30 秒）：核实 dex 缓存是不是这版微信

```bash
cd C:/Users/lx/WorkBuddy/2026-09-29-20-23-21
adb shell dumpsys package com.tencent.mm | grep -E "versionName|versionCode"
```

**预期**：`versionName=8.0.48` / `versionCode=2588`（或更高）。
⚠️ 若不一致 ⇒ 本文所有「dex 事实」降级为**参考**，必须重拉 dex 再说
（重拉手段见 `docs/开发回路速查.md`；本 lane 不碰 adb）。

### S1：基线（改代码之前先抓一份，用来对照）

```bash
python tools/dev.py restart
# 微信进「发现 → 朋友圈」，上下滚 2 屏
python tools/dev.py q 'SNS_TAG installed|SNS_TAG stat' 30
python tools/dev.py probe snstag sub stat --grep SNSTAG
python tools/dev.py probe snstag sub dump --grep SNSTAG
```

**预期/判读**：记录老的 `binds / sns / resolved / missed / hits / lastMiss`。
- 若 `SNS_TAG stat` 里 `binds=0` ⇒ 老挂载点**完全没弹**（`w1.onBindViewHolder` 死钩确证）；
- 若 `binds>0` 但 `resolved=0` 且 `lastMiss=ambig…/nodata…` ⇒ F1 确证；
- 若 `resolved>0` 但 `hits=0` ⇒ 归档 id 与解析 id 对不上（**这是另一种病，本轮的改动治不了，立刻回报**）。

### S2：装新包

```bash
./gradlew assembleDebug          # 主会话统一做
adb install -r <apk>
python tools/dev.py restart
```

### S3：看新挂载点装上没有

```bash
python tools/dev.py q 'SNS_DELTAG installed|SNS_DELTAG hooked|SNS_DELTAG: ' 20
```

**预期**：
```
SNS_DELTAG hooked adapter=com.tencent.mm.view.recyclerview.WxRecyclerAdapter +7
SNS_DELTAG installed hooks=8
```
（`c1/d1/a1/b1/l0/n0/k0` 7 条 + setAdapter 1 条；少几条也算正常，≥2 就行）

⚠️ 若出现 `SNS_DELTAG: com.tencent.mm.view.recyclerview.WxRecyclerAdapter not found`
⇒ 类名漂移，**立刻回报**，别继续。

### S4：进朋友圈滚 2 屏 → 抓自诊断

```bash
python tools/dev.py q 'SNS_DELTAG stat|SNS_DELTAG holder' 40
```

**预期**：
```
SNS_DELTAG holder=m0 pick=LinearLayout cands=[…]
SNS_DELTAG stat on=true deleted=5 bound=12 hooks=8 binds=60 sns=60 resolved=60 missed=0 hits=3 missedTime=0 lastMiss=
```
- `holder=…` 那行是**选字段明细**，用来核对 `itemViewOf` 有没有选错（`pick=` 应该是 item 根布局，不是 `RecyclerView`）
- 对照 §3.4 的表判读

### S5：界面验证

```bash
python tools/dev.py ui 删除已拦截
```

**预期**：命中节点数 ≤ `deleted=N`，且每个命中的 `bounds` 都在某条动态的时间行位置。

### S6：反向对照（关开关）

关掉「防撤回 → 朋友圈」开关（或 `probe kvset --val antirevoke.sns=false`）→ 等 3 秒：

```bash
python tools/dev.py ui 删除已拦截
```
**预期：零命中**（N5）。等 3 秒后重新打开，**预期：标记自己贴回来**（不用等下一次 bind）。

### S7：误伤验证（最关键）

```bash
python tools/dev.py probe snstag sub dump --grep SNSTAG   # 拿一条当前屏上的 u=<id>
# 用 SnsDeletedTag 自己 dump 出的 u= 也行
```

然后走 `tools/_snssim.py`（真删一条）或 `probe snstag sub fake --es id <u=>` 后看标记是否落在**那条**上（N2）。

---

## ⑥ 风险与回退

| 风险 | 概率 | 影响 | 回退 |
|---|---|---|---|
| `c1/d1/a1/b1/l0/n0/k0` 是**混淆名**，换版就飘 | 中 | 静默失效（`hooks=0`） | 已改成**形状匹配**而非硬编码；真飘了就降级到候选 B（`l0/n0/k0` 名字硬编码）或候选 C。判据：`hooks=0` |
| holder 字段 `E` / `z()` 换名 | 中 | `missed` 涨、`lastMiss=nodata(…)` | 三级兜底失败时**自动不打标**（绝不误打）。修法：补字段名候选 |
| 与 SnsTag 两条链路打架 | 中 | 标记闪 | 已处理：`apply(snsId=0)` 时改用本类 `bound` 的 id（`:403`）。真打架就把 `SnsTag.onBind` 里对 `SnsDeletedTag.apply` 的调用摘掉（**不在本 lane 文件里，需另派**） |
| 多挂 7 条 hook → 滚动掉帧 | 低 | 性能 | 形状匹配只挂朋友圈 adapter 的绑定方法；`dataOf`/`itemViewOf` 都有 per-class 缓存 + 负缓存。若实测掉帧，砍到只挂 `c1`+`d1` 两条 |
| 自己读 DB 多一份 5s TTL 缓存 | 低 | 双份缓存，与 SnsTag 的 `delSet` 可能短暂不一致 | 只读 `snsTagIds()` 一列，不读 BLOB。用户「清完还挂着标记」最多 5 秒 |
| `realTimeUpdate` 仍会冲掉标记 | 已知 | 标记闪一下 | 靠 2s sweep 补（`:477`）。若 2s 不够，把 `SWEEP_MS` 调到 1000 |
| **dex 缓存不是这版微信** | 未知 | 全部结论失效 | S0 先验版本；不一致就重拉 dex |

### 回退步骤（一键）

1. 删掉 `HookEntry.kt:249` 下面新增的那行 `SnsDeletedTag.install(lpparam)`；
2. `SnsDeletedTag.kt` / `SnsDeletedTagLogic.kt` 用 `git checkout` 还原；
3. ⚠️ **不要**动 `SnsTag.kt`：老链路仍在跑，`apply()` 契约没变，回退后功能回到改动前状态。

### 没做 / 待定

- ⛔ **没碰** `SnsAlbumMerge*.kt`（候选口径是待拍板项）、`AntiRevoke*`、`data/**`、`EntryReceiver.kt`。
- ⛔ **没加探针入口**：`stat()` / `dump()` 写好了但没接 `op=snstag`（文件不允许改）。
  现在靠**打日志**取证；若主会话觉得需要交互探针，另开 lane 在 `EntryReceiver.kt` 加
  `"deltag" -> …` 分支。
- ⛔ **没做单测**：`app/src/test/` 不在允许写的文件里。`SnsDeletedTagLogic.snsIdOf` /
  `parseSnsId` 是纯函数、可测，建议另派一个 lane 补 `SnsDeletedTagLogicTest`。
- ⛔ **没碰时序**：本文没有任何「谁先谁后」的结论，所有涉及顺序的说法都标了**假设**。

[DONE]

---

# [ROUND2] R35-1b：取 snsId 失败的根因与修复

> 接手 R35-1。上一棒的锚点（`c1/d1/a1/b1/l0/n0/k0` 形状匹配 + setAdapter 补挂）**不动**，
> 真机 `hooks=10` 证明安装没问题。本轮只修「拿到 holder 之后怎么把 snsId 读出来」。
>
> 反汇编器：`_r35_sns_dis.py`（本轮新建，未改动任何既有文件）。
> ⚠️ 上一棒的两个脚本 `_r35_sns_scan.py` / `_r35_adnd_dex.py` 的**指令解码是错的**
> （把每条指令都当成 2 字节定长、且字段类 opcode 表整体偏了 2），本轮结论一律以
> `_r35_sns_dis.py` 为准；上一棒基于那个解码器得出的「字面量位置」结论需要重新核。

## ① `ki4.m0` 完整字段清单（classes4.dex，raw `class_data_item` 逐字段解码）

`Lki4/m0;` extends `Lki4/l0;` extends `Landroidx/recyclerview/widget/c3;`（`c3` = R8 改名后的
`androidx.recyclerview.widget.RecyclerView.ViewHolder`）。

instance 字段共 **6** 条（`static_fields = 0`）：

| # | field_idx | 字段名 | 类型 | dex 里写的 declaring class |
|---|---|---|---|---|
| 0 | 2 | `a` | `La03/d;` | `La03/c;` ⚠️ |
| 1 | 33655 | `C` | `Lo4/a;` | `Lki4/m0;` |
| 2 | 33656 | `D` | `Ljava/lang/Object;` | `Lki4/m0;` |
| 3 | 33657 | **`E`** | `Ljava/lang/Object;` | `Lki4/m0;` |
| 4 | 33658 | `F` | `Landroidx/recyclerview/widget/RecyclerView;` | `Lki4/m0;` |
| 5 | 33659 | `e` | `I` | `Lki4/q0;` ⚠️ |

⚠️ 第 0 / 5 条的 `field_idx` 解析出的 class 与 `m0` 不同（R8 纵向合并/打包产物痕迹）。
运行时 `getDeclaredFields()` 大概率能看到它们，但**字节码里没有任何一处对 `m0->a` / `m0->e`
做 iput/iget**，故本轮不把它们当候选（真机 dump 会证伪）。

父类 `Lki4/l0;`（classes2.dex）：instance 字段 1 条 `d : La12/a;`（同上，declaring class 记为
`La12/a;`）；`c3`（classes12.dex）里真正有用的是 **`d : Landroid/view/View;`（= `itemView`）**，
真机 `cands=[c3.d:FrameLayout]` 里那条就是它。

`ki4/m0` 自身声明的方法（与取数相关的三条）：
```
Lki4/m0;-><init>(Landroid/view/View;)V
Lki4/m0;->z()Ljava/lang/Object;          ← 体内就是 iget-object m0->E + return-object
Lki4/m0;->G(Ljava/lang/Object;)V         ← 体内就是 iput-object m0->E
```
⇒ **`E` 是数据字段、`z()` 是它的 getter、`G()` 是 setter**，上一棒这一条是对的。✅

## ② 定论：`m0` 是 ViewHolder，**不是**数据 item

证据三条，互相独立：

1. **继承链**：`ki4/m0 → ki4/l0 → androidx/recyclerview/widget/c3`（`c3` 就是 `RecyclerView.ViewHolder`，
   微信把 androidx 也一起 R8 混淆过了）。`m0` 的构造器是 `<init>(Landroid/view/View;)V`。
2. **真机日志**：`itemViewOf` 在 `m0` 上扫 6 层只找到 1 个 View 字段 —— `c3.d`(`FrameLayout`)。
   与 dex 完全对得上：`m0` 的 View 字段只有 `F`(RecyclerView)，被 `isRv()` 排掉了，
   剩下的就是 ViewHolder 的 `itemView`。
3. **`c1` 体内同时出现两条**（classes8.dex 反汇编）：
   ```
   0x001b iput-object v0, v9   Lki4/m0;->E : Ljava/lang/Object;   ← 往 holder 里塞数据
   0x0061 iget-object v9, v9   Landroidx/recyclerview/widget/c3;->d : Landroid/view/View;  ← 取 itemView
   ```
   「数据」和「itemView」是**两个不同的字段**，说明 `m0` 是容器（ViewHolder），不是数据本身。

⇒ 代码把 `m0` 当 ViewHolder 去读字段是**对的**，「两者混了」这个怀疑**排除**。

### 那为什么还是读不到？——`E` 的赋值来源不止一处，且有先后顺序

`WxRecyclerAdapter` 里四条写 `E` 的方法（全部 classes8.dex）：

| 方法 | 写进 `E` 的东西 | 是 item 吗 |
|---|---|---|
| `c1(Lki4/m0;I)V` | `(Lki4/c) data.get(pos)`（`WxRecyclerAdapter->data : Ljava/util/ArrayList;`） | ✅ 是 |
| `d1(Lki4/m0;ILjava/util/List;)V` | 同上 | ✅ 是 |
| `a1(Lki4/m0;ILki4/r;)V` | `v11` = 第 3 形参 `Lki4/r;`（只有 `a()I / b()View / d()Z` 的 bind-info） | ❌ 不是 |
| `b1(Lki4/m0;ILki4/r;)V` | 同 `a1` | ❌ 不是 |

`regs/ins` 已核对：`c1` regs=11 ins=3 ⇒ `this`=v8, `m0`=v9, `I`=v10；
`a1` regs=12 ins=4 ⇒ `this`=v8, `m0`=v9, `I`=v10, `Lki4/r;`=v11。

调用序（dex，`ki4/i0.onBindViewHolder(c3,I)` 体内）：
```
0x0026 invoke-virtual ki4/i0->k0(c3, I, ki4/r)   →  WxRecyclerAdapter.b1(m0, I, ki4/r)   ← 先跑
0x0051 invoke-virtual ki4/i0->i0(c3, I, ki4/r)   →  WxRecyclerAdapter.a1(m0, I, ki4/r)   ← 再跑
0x005b invoke-virtual ki4/i0->l0(c3, I)          →  WxRecyclerAdapter.c1(m0, I)          ← 最后跑（写真 item）
```
（3 参版则是 `k0` → `i0` → `n0(c3,I,List)` → `d1(m0,I,List)`。）

**⇒ 病根（F1 的具体形态）**：我们同时挂了 `a1/b1/c1/d1`，一次 bind 会触发**多条** hook 的
`afterHookedMethod`。`a1/b1` 先触发，此时 `E` 是 `ki4/r`（toString 是默认 `ki4.r@xxx`，没有
`snsID=`）⇒ `dataOf` 三个候选全失败 ⇒ **给 `ki4.m0` 打上永久负缓存** ⇒ 后面 `c1` 把 `E` 写成
真 item 了，代码却因为负缓存直接返回 null，再也不重试。**真机 `no snsId on holder ki4.m0`
100% 失败就是这样来的**，与「只有 2 行日志（被 `logOnce` 的全局 5 条上限吞掉）」自洽。

### item 的真实类型链（顺带把 `snsID` 的位置钉死）

```
adapter.data.get(pos)
   └─ check-cast Lki4/c;              ← 泛型擦除后的形参类型
        └─ check-cast Lqh3/a;         ← com.tencent.mm.plugin.sns.ui.improve.repository.ImproveListItem
             ├─ Lqh3/a;->k()Loh3/p;   ← getter 名 `k`，体内 = iget-object Lqh3/a->d : Loh3/p;
             └─ Lqh3/a;->toString()   ← 直接 return oh3/p.toString()（委托！）
                  └─ Loh3/p; = com.tencent.mm.plugin.sns.ui.improve.model.ImproveSnsInfo
                       toString() = "ImproveInfo(name=<w0()>, snsID='<D0()>', ad:<isAd()>,
                                     time:<J0()> rowid=<y0()>, createTime=… localFlag=…)"
```
`D0()` 返回 `String`（无符号十进制），正是 `snsID=` 后面那段。
所以 **`snsIdOf()` 的正则 `snsID\s*=\s*'?(-?\d{15,20})` 没问题** ——
只要 `E` 真的是 item（`qh3/a`），`E.toString()` 就一定能命中。真机没命中 ⇒ `E` 当时不是 item。

备用（未启用，风险较高）：`Ljj/e6;->l0()J` = `iget-wide Ljj/e6->field_snsId : J`
（`oh3/p` extends `jj/e6`），是 signed long 形态的 snsId。名字 `l0` 是混淆名，
**只有在 toString 路线全挂时才值得试**，本轮不启用。


## ③ 改后的取 id 路径（行号 = 改完后的 `SnsDeletedTag.kt`）

### 3.1 主流程

一次 bind 里 `dataOf(holder, adapter, pos)`（`:400`）按下面顺序找第一条**能解析出 snsId** 的路径，
找到就把路径缓存到 `dataPathCache`（按 holder 类），后面直接走缓存：

| 优先级 | step | 含义 | 理由 |
|---|---|---|---|
| 1 | `@` | `adapter.data[pos]` | **与 `m0.E` 完全无关**的独立来源。`WxRecyclerAdapter->data` 字段名没混淆；这正是微信自己在 `c1` 里取 item 的方式，不受 `a1/b1` 把 `E` 临时写成 bind-info 影响 |
| 2 | `z` | `holder.z()`（= `m0.E`） | 上一棒的官方 getter，保留 |
| 3 | `E` | `holder.E` | 同上，直接读字段 |
| 4 | `D` | `holder.D` | 上一棒的老兜底 |

每个候选先直接 `snsIdOf`；不中再走 **一层展开** `unwrap()`（`:441`）：
先扫该对象的实例字段（跳过 static / primitive / View，最多 24 个、上溯 3 层），
再退到白名单无参 getter `k()`（`qh3/a.k()` → `oh3/p`）。
展开路径记成 `E;f:d` / `E;m:k` 这种形式，`walkPath()`（`:466`）解释执行。

### 3.2 关键：负缓存改成「有预算的探测」（就是 F1 的修法）

```kotlin
// :408
if (!takeProbe(key)) { logDataFail(key); return null }   // 预算 12 次，扣完才认命
```
旧实现（`dataPathCache[key] = null` 一次性判死）在 `a1/b1` 先触发、读到 `ki4/r` 时就把
`ki4.m0` 判死了，之后 `c1` 写对了也永远读不到。现在：
- 有缓存路径 → 直接走（命中就返回）；
- 没路径但还有预算 → 全量探测一次；
- 预算耗尽 → 走 `logDataFail`（只记数，不再反射）。

一次 bind 会触发 3~4 条 hook，`PROBE_MAX=12` 足够覆盖「前几次读到错的 `E`」这种情况，
同时又不会在真的读不到时永远每帧都扫字段。

### 3.3 顺带修的第二个 bug：解析失败不再无条件擦标记

`bindHook`（`:272-281`）原来是 `bound.remove(view)` + `restore(view)` 无条件执行。
因为一次 bind 有多条 hook、其中一部分必然解析失败，这会把刚由 `c1` 打上的标记擦掉。
改成：**只有这个 view 从来没解析成功过**（`bound[view] == null`）时才 `restore`。

### 3.4 行号索引

| 位置 | 内容 |
|---|---|
| `SnsDeletedTag.kt:269-270` | 取 `pos`（`param.args[1]`，形状匹配保证是 int）+ 调 `dataOf(holder, adapter, pos)` |
| `SnsDeletedTag.kt:272-281` | 失败分支：计数 + 有条件 `restore`（不再无条件擦） |
| `SnsDeletedTag.kt:357-383` | 新常量与缓存：`PROBE_MAX` / `UNWRAP_MAX` / `dataPathCache` / `probeLeft` / `Tally` / `dataTally` / `dumped` |
| `SnsDeletedTag.kt:400` | `dataOf` |
| `SnsDeletedTag.kt:423` | `probePath`（候选顺序 `@` → `z` → `E` → `D`） |
| `SnsDeletedTag.kt:441` | `unwrap`（一层展开） |
| `SnsDeletedTag.kt:466` | `walkPath` |
| `SnsDeletedTag.kt:486` | `stepOf` |
| `SnsDeletedTag.kt:500` | `adapterItem`（`@` 的实现） |
| `SnsDeletedTag.kt:526` | `logDataFail`（带 fail/seen 的节流日志） |
| `SnsDeletedTag.kt:556` | `dumpFields`（一次性全量 dump） |
| `SnsDeletedTag.kt:784` | `stat()` 增加 `paths=` |
| `SnsDeletedTagLogic.kt:71` | 新增纯函数 `brief()`（日志截断，可单测） |

⛔ 没动：`HookEntry.kt`、`SnsTag.kt`、`itemViewOf()`、hook 选择逻辑（`hooks=10` 那条路径一行没改）。

## ④ 新增 dump 日志格式

每个（角色 × 类名）只打一次，进朋友圈首屏最多 2~5 组，不会刷屏。

```
SNS_DELTAG fields cls=<全限定类名> tag=<角色> names=[f1,f2,...] types=[T1,T2,...]
SNS_DELTAG vals tag=<角色> vals=[v1 | v2 | ...]
```

- `tag` 取值：`holder`（holder 本体，`ki4.m0`）、`item(@)`、`item(z)`、`item(E)`、`item(D)`
- `names` / `types` 一一对应，取该类 + 上溯 3 层的非 static 字段，最多 40 个
- `vals` 是同一顺序的 `toString()`，**每个截断到 80 字符**（`SnsDeletedTagLogic.brief`）

**判读要点**：`tag=item(@)` 那组里应该看到一个值形如
`ImproveInfo(name=xxx, snsID='15033729556253184615', ad:false, time:... rowid=... createTime=... localFlag=...)`
——看到就说明取 id 通了；`tag=holder` 那组里 `E` 的值如果是 `ki4.r@....` 而不是
`ImproveInfo(...)`，就实锤了 §② 的「`a1/b1` 把 bind-info 写进 `E`」。

另有两条辅助日志：
```
SNS_DELTAG path m0 -> @                                  ← 探测成功，缓存下来的路径
SNS_DELTAG: no snsId on holder ki4.m0 (fail=3/seen=5)    ← 失败比例（每类节流）
```
`fail/seen` 的节流规则：`fail <= 3`、`fail == 5`、`fail == 10`、之后每 50 条打一次。
⇒ `fail==seen` 就是全失败，`fail << seen` 就是只有部分 hook 解析不出来（预期内，无害）。

## ⑤ 真机验证命令与期望输出

> ⛔ 本 lane 不碰 adb/构建/装包，命令交给主会话执行。

### V0：先看版本（与 dex 一致才继续）

```bash
adb shell dumpsys package com.tencent.mm | grep -m1 versionName
```
期望：`versionName=8.0.48`（dex 侧已核对 8.0.48 / 2588；不一致就先重拉 dex，本文结论作废）

### V1：装包 → 手动进朋友圈 → 滚两屏 → 取日志

```bash
adb logcat -d | grep SNS_DELTAG
```

**期望（按顺序）**：
```
SNS_DELTAG hooked adapter=com.tencent.mm.view.recyclerview.WxRecyclerAdapter +9
SNS_DELTAG installed hooks=10
SNS_DELTAG holder=m0 pick=FrameLayout cands=[c3.d:FrameLayout]
SNS_DELTAG fields cls=ki4.m0 tag=holder names=[...] types=[...]
SNS_DELTAG vals tag=holder vals=[...]
SNS_DELTAG fields cls=qh3.a tag=item(@) names=[d,...] types=[...]
SNS_DELTAG vals tag=item(@) vals=[ImproveInfo(name=..., snsID='15033729556253184615', ...)]
SNS_DELTAG path m0 -> @
SNS_DELTAG stat on=true deleted=N bound=K hooks=10 binds=.. sns=.. resolved=.. missed=.. hits=.. paths=1 lastMiss=
```
**判据**：`resolved` 接近 `sns`（不再像上一轮那样 `resolved=0`），`paths>=1`。

### V2：对账（标记有没有贴到对的 item 上）

```bash
adb logcat -d | grep "SNS_DELTAG stat" | tail -1
```
期望：`hits > 0`（前提是 `deleted=N` 里 N>0 且那些动态在屏上）。

### V3：失败比例的读法

如果仍然失败，看 `no snsId on holder` 那行：
- `fail==seen` ⇒ 真的全失败，把 `tag=item(@)` / `tag=holder` 两组 dump 发回来，
  下一轮按真值改候选（不用再猜）。
- `fail < seen` ⇒ 只有部分 hook 解析不出来（预期内），看 `resolved` 是否 > 0；
  `resolved > 0` 就不用管（那些失败的 hook 本来就不该打到标）。

### V4：误伤回归（最关键）

删一条动态 → 看标记是否只落在**那条**上；再滚几屏看标记会不会串到别的 item。
判据：`hits` 不涨、屏上标记数 == `deleted` 里在屏上的条数。

## ⑥ 风险与回退

| 风险 | 概率 | 影响 | 回退 |
|---|---|---|---|
| `@`（`adapter.data`）字段名被改/不是 `ArrayList` | 低 | 该候选恒 null，自动降级到 `z/E/D` | 判据 `path` 日志里出现 `z`/`E` 而不是 `@`。`adapterItem` 全包 `runCatching`，异常不外溢 |
| 探测期反射扫字段拖慢首屏 | 低 | 首屏多几次反射（≤12 次 × ≤24 字段） | 走通后只留 2 次反射/bind。真掉帧就把 `PROBE_MAX` 降到 4、`UNWRAP_MAX` 降到 8 |
| 解析失败不再无条件 `restore` ⇒ 复用残留 | 中 | ViewHolder 复用时可能残留上一条的标记 | 已有 2s sweep 兜底；且只在「从没解析成功过」时才 restore。若真出现串标，把 `:279` 的判断改回无条件（但会退化成标记闪烁） |
| `unwrap` 扫字段时读到「长得像 snsId」的无关 long | 低 | 打错标签（比不打糟得多） | 只接受 `snsID='...'` 前缀 + 15~20 位纯数字，`snsIdOf` 的形状校验挡着 |
| `dumpFields` 里 `toString()` 有副作用 | 很低 | 未知 | 只对数据对象调 toString；`runCatching` 包住，异常不外溢。若怀疑，把 `dumpFields` 两行注掉即可（一行 `if (!dumped.add(k)) return` 改成 `return`） |
| 本轮 dex 结论基于静态反汇编，未真机验证 | **已知** | §② 的「`a1/b1` 写 bind-info」是按寄存器解码推的 | 真机 `tag=holder` 的 `vals` 里看 `E` 的值：`ImproveInfo(...)` = 猜错；`ki4.r@...` = 猜对。**没看到这行之前，本结论标为「静态推断、待真机」** |

### 回退（一键）

1. 只回退取数逻辑：`git checkout` 掉 `SnsDeletedTag.kt` + `SnsDeletedTagLogic.kt` 两个文件即可
   （`HookEntry.kt` 的接线不动，上一棒的挂载点还在，只是取 id 回到「永久负缓存」的旧行为）。
2. 全量回退：删掉 `HookEntry.kt:249` 下面新增的 `SnsDeletedTag.install(lpparam)`。

### 没做 / 待定

- ⛔ 没真机验证（设备锁）：§② 的「`a1/b1` 写进 `E` 的是 `Lki4/r;`」是**静态寄存器解码**结论，
  **无真机证据**，已在风险表里标出。真机 dump 出来前不要当成事实。
- ⛔ 没启用 `jj/e6.l0()J`（signed long 的 snsId）：名字是混淆名，误用会打错标签，留作下下轮备选。
- ⛔ 没加单测：`app/src/test/` 不在允许写的文件里。`SnsDeletedTagLogic.brief` /
  `parseSnsId` 是纯函数，建议另派 lane 补。
- ⛔ 没动 `logOnce` 的全局 5 条上限（那是 `itemViewOf` 用的）；新增的失败日志走独立的
  `logDataFail`，不受它限制。

[ROUND2]

---

# [ROUND3] R35-1c：时间 TextView 取不到 —— 根因与重写

> 接手 R35-1b。上一棒把取 snsId 修通了（真机 `resolved=3 missed=0 paths=1`），
> 现在卡在最后一步：`hits=0 missedTime=1`，日志 `no time TextView … host=android.widget.FrameLayout`。
> 本轮只改「拿到 itemView 之后怎么找到时间控件」。反汇编器仍用 `_r35_sns_dis.py`。
> ⛔ 未碰 adb / 未构建 / 未装包；⛔ 未改 `HookEntry.kt`、`SnsTag.kt`。

## ① `getPublishTimeText()` 的存在性与声明层（dex 事实）

全 dex 扫描（`python _r35_sns_dis.py body "" getPublishTimeText`）**只有两个类声明它**：

| 类描述符 | 原类名 | 父类 | 返回类型 | 方法体 |
|---|---|---|---|---|
| `Llh3/l1;` | `com.tencent.mm.plugin.sns.ui.improve.item.ImproveTimelineItemBuild` | `Landroid/widget/LinearLayout;` | `Landroid/widget/TextView;` | `iget-object v2, Lh3/l1;->o : Landroid/widget/TextView;` + `return-object` |
| `Lxh3/o2;` | `com.tencent.mm.plugin.sns.ui.item.improve.TimelineItemBuild` | `Landroid/widget/LinearLayout;` | `Lcom/tencent/mm/kiss/widget/textview/PLSysTextView;` | `iget-object v2, Lxh3/o2;->p : PLSysTextView;` + `return-object` |

调用方（`call getPublishTimeText`）也只有 4 处，全部在 classes3.dex：

```
Llh3/w1;->run()V                 → invoke-virtual Llh3/l1;->getPublishTimeText()TextView
Llh3/x1;->d()V                   → invoke-virtual Llh3/l1;->getPublishTimeText()TextView   ← realTimeUpdate
Lxh3/a4;->run()V                 → invoke-virtual Lxh3/o2;->getPublishTimeText()PLSysTextView
Lxh3/b4;->A(Lcom/tencent/mm/plugin/sns/ui/ms;Z)V → invoke-virtual/range Lxh3/o2;->getPublishTimeText()…
```

**① 的答案：`ki4.m0` 的继承链上【没有】`getPublishTimeText()`。**

`Lki4/m0;`（classes4.dex）自己声明的方法全集：

```
Lki4/m0;-><init>(Landroid/view/View;)V
Lki4/m0;-><init>(Landroid/view/View;Lo4/a;)V
Lki4/m0;->B()Landroid/content/Context;
Lki4/m0;->C()Landroidx/recyclerview/widget/RecyclerView;
Lki4/m0;->F(I)Landroid/view/View;        ← 体内：iget-object c3->d  +  View.findViewById(I)
Lki4/m0;->G(Ljava/lang/Object;)V         ← 写 E
Lki4/m0;->H(II)Lki4/m0;
Lki4/m0;->I(ILjava/lang/CharSequence;)Lki4/m0;
Lki4/m0;->J(II)Lki4/m0;
Lki4/m0;->K(II)Lki4/m0;
Lki4/m0;->z()Ljava/lang/Object;          ← 读 E
```

父类 `Lki4/l0;`（classes2.dex）只有 `<init>(View)` 与 `y()`；再上一层
`Landroidx/recyclerview/widget/c3;` 是 ViewHolder 本体。⇒ **holder 上也没有这个方法。**

### ⇒ 修正 team-lead 的假设

「**该传 holder 却传了 itemView**」**不成立**：holder（`ki4.m0`）同样没有
`getPublishTimeText()`。真病根是 —— **时间控件不在根 View 上，也不在 holder 上，
而在 itemView 的子树里（或压根是另一层结构）**。

### 顺带钉死的一条：`itemViewOf` 没选错 View

`Lki4/m0;->F(I)Landroid/view/View;` 反汇编：

```
0x0000 iget-object    v0, v1   Landroidx/recyclerview/widget/c3;->d : Landroid/view/View;
0x0002 invoke-virtual Landroid/view/View;->findViewById(I)Landroid/view/View;
0x0006 return-object  v2
```

`m0.F(id)` 是微信自己「在 itemView 里找控件」的官方入口，它读的就是 `c3.d`。
真机 `cands=[c3.d:FrameLayout]` ⇒ **我们传给 `timeTextOf` 的那个 FrameLayout
就是微信认定的 itemView**，不要再怀疑 `itemViewOf`。

## ② 时间 TextView 的真实位置

### 2.1 时间文案是怎么写上去的（dex 铁证）

`Llh3/x1;->d()`（= `ImproveTimelineItemMeasure.d()`，realTimeUpdate）：

```
0x0008 iget-object   v2, v8   Llh3/x1;->V : Lqh3/a;      ← 数据 item
0x000c invoke-virtual Lqh3/a;->k()Loh3/p;               ← ImproveSnsInfo
0x0013 invoke-virtual Llh3/l1;->getPublishTimeText()Landroid/widget/TextView;
0x001a invoke-virtual Loh3/p;->J0()Ljava/lang/String;
0x001e invoke-virtual Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V
```

⇒ **屏幕上时间行的原文 == `data.k().J0()`**。这给了本轮一个不靠猜的锚点：
数据对象 `toString()` 里 `time:` 后面就是 `J0()` 的返回值（真机 dump
`ImproveInfo(name=昌亚刘丽萍, snsID='15043160246930444808', ad:false, time:44分钟前 rowid…`
已经自证），于是可以**按内容**在子树里认出时间行。

### 2.2 根 View 应该是哪个类（dex）与真机的矛盾

`o0.c(RecyclerView)`（= `ImproveMainUIC$buildItemConvertFactory$1$getItemConvert$1`，
唯一的 sns 侧 `ki4/m` 子类）创建的 item 根 View 有 13 个候选：

```
Llh3/q2; Llh3/f0; Llh3/v; Llh3/b0; Llh3/a3; Llh3/u3; Llh3/i3; Llh3/g2; Llh3/f3;
Llh3/y2; Llh3/v2; ImproveRepostView; …improve.compatibility.OldVersionItemView
```

继承链实测：

```
Llh3/q2; → Llh3/z1; → Lmh3/i1; → Llh3/o1; → Llh3/b2; → Llh3/l0; → Llh3/s1; → Llh3/x1; → Llh3/l1; → LinearLayout
Llh3/b0; → Llh3/j0; → Llh3/z1; → …同上… → LinearLayout
com.tencent.mm.plugin.sns.ui.improve.compatibility.OldVersionItemView → android.widget.FrameLayout
```

⇒ 按 dex，**根 View 应该是 `lh3/l1` 的某个子类（LinearLayout 系）**，
那样 `getPublishTimeText()` 直接就能调到。**但真机根 View 是裸 `android.widget.FrameLayout`**，
而全 dex 里 sns/lh3 包下继承 `FrameLayout` 的只有 `OldVersionItemView`（类名不匹配）。

| 解释 | 证据状态 |
|---|---|
| A. 裸 FrameLayout 是外层容器，`lh3/xxx` build 是它的子 View | **与真机自洽**，但 dex 里**找不到**创建这层容器的代码 ⇒ 无证据 |
| B. 真机走 `OldVersionItemView` 兼容模式 | 与「根是 FrameLayout」自洽，但 `javaClass.name` 应为全限定名 ⇒ **与日志矛盾** |
| C. 缓存 dex 与真机包不是同一份产物 | 无法排除；team-lead 已核 8.0.48/2588 |
| D. item 结构在本版换成了「FrameLayout 根 + 内部 build」 | 同 A |

**结论：无证据、待真机。** ⛔ 不猜。本轮按「A/D」这个最可能形态做实现（遍历子树），
同时**新加 View 树 dump**（§④），下一轮一看树就知道是 A 还是别的。

### 2.3 本轮采用的定位策略（不依赖上面任何一个未定论）

| 级 | 手段 | 依据 |
|---|---|---|
| ① 缓存 | `host.findViewById(binding.tvId)` | 命中一次后记住资源 id，sweep 每 2s 重判走 O(1) |
| ② getter | 子树任一节点（含 4 层父类）声明 `getPublishTimeText()` → 调它 | dex §①：`lh3/l1`、`xh3/o2` 两个类都声明了，返回类型是 TextView 子类 |
| ③ 内容 | 子树里 `strip(text) == binding.hint` 的 TextView | dex §2.1：时间行原文 == `J0()` == 数据 `toString()` 的 `time:` 段 |
| ④ 兜底 | 子树里 `isTimeLike(text)` 的 TextView | 形状收得很紧（≤20 字 + 相对/绝对时间样式），最后才试 |

②③④ 任一成功就把 `tv.id` 记回 `binding.tvId`。⛔ 没有「读字段 `o`/`p`」这一步了 ——
`o` 是 `lh3/l1` 的字段、`p` 是 `xh3/o2` 的字段，都是**混淆名 + 非根 View 上的字段**，
对裸 FrameLayout 永远无效（这正是上一轮 `missedTime=1` 的直接原因）。

## ③ 改动与行号

### 3.1 `feature/SnsDeletedTagLogic.kt`（纯函数，可 JVM 单测）

| 行 | 改动 | 说明 |
|---|---|---|
| 122 | **新增** `TIME_RE` | `time:(\S+)`，从 `ImproveInfo(…, time:44分钟前 rowid=…)` 抓发布时间原文 |
| 127 | **新增** `timeHintOf(obj)` | ① 的纯逻辑；取不到返回 `""`（调用方当「无提示」，自动降级到 ④） |
| 134 / 137 | **新增** `REL_RE` / `DATE_RE` | 相对时间 / 绝对时间样式 |
| 147 | **新增** `isTimeLike(s)` | ④ 的闸门：长度 >20 直接否；必须命中相对或日期时间样式 |
| 其余 | 原样 | `MARK` / `taggedIds` / `isTagged` / `appendTag` / `brief` / `snsIdOf` / `parseSnsId` 契约未动，既有单测继续过 |

### 3.2 `feature/SnsDeletedTag.kt`

| 行 | 改动 | 说明 |
|---|---|---|
| 126 | **新增** `private class Binding(val id: Long)` | 承载 `tvId`（时间控件资源 id，默认 `View.NO_ID`）+ `hint`（发布时间原文） |
| 132 | `bound` 从 `View -> Long` 改成 `View -> Binding` | ⚠️ **不存 holder / data 本体**：`WeakHashMap` 的 value 强引用 key 指向的对象（holder 强引用 itemView）会让条目永不回收 |
| 300-307 | `bindHook` 写绑定 | 复用同 id 的旧 `Binding`（**保住已解析出的 `tvId`**）；换 id 才新建并算 `hint` |
| 622 | `apply()` | 签名**未变**（`SnsTag.kt` 三处调用不受影响）；内部按 id 取/建 `Binding` |
| 649 | `paint(host, b, tagged)` | 形参从 `id: Long` 改成 `b: Binding`；新增 `dumpTree(host)`；失败日志带上 `hint='…'` |
| 675 | `restore(host)` | 内部改调 `timeTextOf(host, bound[host])` |
| 685 / 688 / 691 | **新增** `WALK_MAX=6` / `WALK_KIDS=16` / `TREE_DEPTH=4` | 遍历深度、每层最多看几个孩子、dump 深度 |
| 694 | **新增** `timeMethodCache` | 类名 → `getPublishTimeText()` 的 `Method?`（**负缓存也存**，避免每帧反射扫方法表） |
| 697 | **新增** `treeDumped` | View 树 dump 去重（按根 View 类名，每个类只打一次） |
| 725 | **重写** `timeTextOf(host, b)` | 四级解析（§2.3）；成功即回填 `tvId` |
| 737 | **新增** `resolveTime(host, hint)` | ②→③→④ 的编排 |
| 746 | **新增** `walkForGetter` | 子树 DFS 找声明了 `getPublishTimeText()` 的节点 |
| 763 | **新增** `walkForText` | 子树 DFS 按谓词找 TextView；比对前先 `strip`（已打过标的行也要认得出，幂等） |
| 779 | **新增** `publishTimeMethod(c)` | 类 + 4 层父类里找无参、返回类型可赋给 TextView 的 `getPublishTimeText` |
| 811 | **新增** `timeHintOf(data)` | `toString()` 的 `time:` → 兜底 `k()` 然后 `J0()`。⚠️ **只读不 hook** |
| 829 | **新增** `dumpTree(host)` | §④ |
| 839 | **新增** `collectPaths` | 生成 `A > B > TextView("…")` 形式的路径，最多 40 条、深度 ≤4 |
| 902 | `snapshot()` | 返回类型改成 `List<Pair<View, Binding>>`；顺带滤掉 value 为 null 的条目 |
| 973 / 979 | `stat()` 增加 `tv=` + 新增 `tvCached` | 已记住 `tvId` 的 item 数。`tv=0` 且 `missedTime>0` ⇒ 四级解析全挂 |
| 983 | `dump()` | 每行补 `tvId=`（16 进制，`-` 表示未解析）与 `hint=` |

⛔ 没动：`HookEntry.kt`、`SnsTag.kt`、`hookBinds` / `matchesBindShape` / `itemViewOf` /
`dataOf` / `probePath` / `unwrap` / `walkPath` / `adapterItem` / `deleted` / `loadIds`
（挂载点与取 id 链路一行未改，`hooks=10` / `resolved=3` 不受影响）。

⚠️ `sweep()`（`:888`）仍只用 `bound` 里的 View 重判，那条路**保持可用**：
现在每个 View 对应一个 `Binding`，`paint(v, b, tagged)` 内部先走 ①（缓存 id），
命中就一次 `findViewById`，不遍历。

## ④ 新增 dump 日志格式

```
SNS_DELTAG tree depth=4 host=FrameLayout paths=37 [FrameLayout > LinearLayout > TextView("44分钟前") | FrameLayout > LinearLayout > ImageView | …]
```

- 每个**根 View 类**只打一次（`treeDumped` 去重）
- `paths` = 收集到的路径总数；方括号里最多列 30 条、整体截断到 2000 字符
- 节点只打**类简名**；`TextView` 额外打 `text` 前 12 字（`SnsDeletedTagLogic.brief(s, 12)`）
- 路径分隔符 ` > `，从根的孩子开始（根本身作为前缀出现）

**判读**：
- 看到 `TextView("44分钟前")` 之类的时间行 ⇒ ②/③/④ 必中；若仍 `missedTime>0`，
  说明该 TextView 深度 > 6 或在第 16 个孩子之后，把 `WALK_MAX` / `WALK_KIDS` 调大即可。
- 看到 `FrameLayout > l1 > …` / `… > q2 > …` ⇒ 解释 A 成立（根是容器，build 是子 View）。
- 树里**根本没有**像时间的 TextView ⇒ 根 View 不是这条动态的界面，
  要回头查 `itemViewOf`（但 §2.1 的 `m0.F(I)` 证据基本排除了这个可能）。

另有两条定位辅助：
```
SNS_DELTAG: no time TextView snsId=-3403583826779106808 host=android.widget.FrameLayout hint='44分钟前'
SNS_DELTAG stat … hits=3 missedTime=0 tv=8 …
```
`hint=''` 说明 `timeHintOf` 没取到（数据对象 `toString()` 里没有 `time:` 段），
此时 ③ 级跳过、直接走 ④。

## ⑤ 真机验证命令与期望输出

> ⛔ 本 lane 不碰 adb/构建/装包，全部交给主会话。

### V1：装包 → 手动进朋友圈 → 上下滚 2 屏 → 抓日志

```bash
adb logcat -d | grep SNS_DELTAG
```

**期望（顺序）**：
```
SNS_DELTAG installed hooks=10
SNS_DELTAG holder=m0 pick=FrameLayout cands=[c3.d:FrameLayout]
SNS_DELTAG path m0 -> @
SNS_DELTAG tree depth=4 host=FrameLayout paths=N [FrameLayout > … > TextView("44分钟前") | …]
SNS_DELTAG stat on=true deleted=8 bound=K hooks=10 binds=.. sns=.. resolved=.. missed=0
  hits=H missedTime=0 tv=K paths=1 lastMiss=
```

**判据（三条全中才算本轮修好）**：

| # | 判据 | 说明 |
|---|---|---|
| **P1** | `hits > 0` | 归档里的 id 有落在屏上的（前提是 `deleted=N` 里 N>0） |
| **P2** | `missedTime = 0` 且 `tv >= 1` | 时间控件定位通了，且已记住资源 id |
| **P3** | `python tools/dev.py ui 删除已拦截` 命中数 **≤** `deleted=N` | 不误伤（N1/N2） |

### V2：误伤回归

```bash
python tools/dev.py ui 删除已拦截
```
命中数应 == 屏上「在已删清单里」的条数；滚 3 屏后命中数不应无端增长（N4）。

### V3：四级解析全挂时怎么办

若仍 `missedTime>0`：
1. 把 `SNS_DELTAG tree …` 那一行发回来 —— 直接看时间 TextView 在第几层、类名是什么；
2. `hint=''` 还是 `hint='44分钟前'` —— 判断 ③ 级能不能用；
3. 按 §④ 的判读表改 `WALK_MAX` / `WALK_KIDS`，或把树里那条路径硬编码成
   「先 `findViewById(容器id)` 再往下」的两级定位。

## ⑥ 风险与回退

| 风险 | 概率 | 影响 | 回退 / 判据 |
|---|---|---|---|
| ④ 级（时间样式兜底）认错 TextView，把灰标打到昵称/正文上 | 中 | **误伤（比不打标糟）** | ③ 级精确匹配优先，④ 只在③失败后才试；`isTimeLike` 限长 20 字 + 严格样式。真机用 N1/N2 判据：`dev.py ui 删除已拦截` 命中数 > `deleted` 即回退 |
| 每次 bind 遍历子树拖慢滚动 | 低 | 掉帧 | 深度 ≤6、每层 ≤16 孩子；**且首次成功后即走 `findViewById`**（`tv>=1` 说明生效）。真掉帧就把 `WALK_MAX` 降到 3 |
| ViewHolder 复用后 `tvId` 失效 | 低 | 标记打不上 | `findViewById` 返回 null 时自动把 `tvId` 置回 `NO_ID` 并重新全量解析（`:730`） |
| `timeHintOf` 调 `J0()` 有副作用 | 很低 | 未知 | **只读不 hook**；`runCatching` 包住。⛔ `oh3/p.J0` 的 hook 禁令仍然成立，本轮没有新增任何 hook |
| `dumpTree` 主线程 IO | 低 | 首屏抖一下 | 每个根 View 类**只打一次**，路径截断 2000 字符 |
| `bound` 从 `Long` 变 `Binding`，与 `SnsTag` 老链路打架 | 低 | 标记被擦 | `apply()` 签名未变；`snsId != 0` 时会复用/新建 `Binding`，`snsId == 0` 时仍走「用我们自己解析的 id」，与 ROUND1 `:403` 的语义一致 |
| 根 View 确实是裸 FrameLayout 且子树里没有 build（解释 C：dex 与真机不同源） | 未知 | 四级解析全挂 | 判据 `missedTime>0` 且 `tv=0`；此时应**重拉 dex**再定位，不要在代码里硬猜 |

### 回退（一键）

1. 只回退时间定位：`git checkout` 掉 `SnsDeletedTag.kt` + `SnsDeletedTagLogic.kt`
   即可（`HookEntry.kt` 接线不动，挂载点与取 id 链路仍在，只是时间控件回到
   「`getPublishTimeText()` + 字段 `o`」的旧行为 = 打不上，但不误伤）。
2. 全量回退：删掉 `HookEntry.kt` 里 `SnsDeletedTag.install(lpparam)` 那一行。

### 没做 / 待定

- ⛔ **没做真机验证**（设备锁）。§2.2 的「根 View 为什么是裸 FrameLayout」标为
  **无证据、待真机**；`SNS_DELTAG tree` 那一行出来之前不要当事实。
- ⛔ 没加「holder 子 ViewHolder（真机 dump 里的 `n` / `o` : `c3`）」这条兜底：
  真机那两个字段当时都是 `null`，且需要把 holder 存进 `Binding`（会有
  `WeakHashMap` value→key 强引用泄漏风险）。若 `tree` 打出来后确认 build 挂在
  子 ViewHolder 上，下一轮再加（用 `WeakReference` 存 holder）。
- ⛔ 没加单测：`app/src/test/` 不在允许写的文件里。`SnsDeletedTagLogic.timeHintOf` /
  `isTimeLike` 是纯函数，建议另派 lane 补。
- ⛔ 没动 `WALK_MAX` / `WALK_KIDS` 的默认值自适应 —— 等真机 `tree` 出来再定。

[ROUND3]

---

# [ROUND4] R35-1d：时间控件到底在哪 —— 定论与收口

> 接手 R35-1c。前三棒把「挂载点（`hooks=10`）」和「取 snsId（`resolved=3 paths=1`）」修通了，
> 卡在最后一环：`tv=0 hits=0`。主会话把 `WALK_MAX 6→12`、`TREE_DEPTH 4→9` 之后
> `missedTime 3→0`（深度够了）但 `tv=0` 没动 —— 说明**不是深度问题**。
> 本轮用 dex 把这件事一次性说死。反汇编器：`_r35_sns_dis.py`。
> ⛔ 未碰 adb / 未构建 / 未装包；⛔ 未改 `HookEntry.kt`、`SnsTag.kt`。

## ① `lh3.i3`（以及 `u3/f3/q2/g2`）的完整继承链 —— 有 `Llh3/l1`

**答案：有。** 而且是**每一条**真机 item 根都有（`classes3.dex`，逐层 `class_def.superclass_idx` 实测）：

```
Llh3/i3;  ─┐
Llh3/q2;  ─┴→ Llh3/z1; → Lmh3/i1; → Llh3/o1; → Llh3/b2; → Llh3/l0; → Llh3/s1; → Llh3/x1; → Llh3/l1; → Landroid/widget/LinearLayout;

Llh3/g2;  ─┐
Llh3/u3;  ─┼→ Llh3/j0; → Llh3/z1; → Lmh3/i1; → Llh3/o1; → Llh3/b2; → Llh3/l0; → Llh3/s1; → Llh3/x1; → Llh3/l1; → Landroid/widget/LinearLayout;
Llh3/f3;  ─┘
```

层级（`getPublishTimeText()` 声明所在的 `Llh3/l1;` 距根几跳）：

| item 根 | 到 `Llh3/l1;` 的跳数 |
|---|---|
| `Llh3/i3;` / `Llh3/q2;` | **8** |
| `Llh3/g2;` / `Llh3/u3;` / `Llh3/f3;` | **9**（多一层 `j0`） |

**⇒ `getPublishTimeText()` 在 item 根上就能调，这是可以确定的。**

### 那为什么 `timeTextOf` 第一级没拿到？—— 不是返回 null，是**根本没调到**

`SnsDeletedTag.publishTimeMethod()`（R35-1c 写的）扫父类时写的是：

```kotlin
while (up < 4) { … cur = cc.superclass; up++ }   // ← 只看 4 层
```

而 `getPublishTimeText()` 在**第 8~9 层**。

**⇒ 这个方法对每个 item 根类恒返回 `null` 并写进负缓存**（`timeMethodCache[name] = null`），
②级「调 getter」**一次都没触发过**。剩下 ③（按内容比对 `hint`）与 ④（时间样式兜底）在
bind 那一刻不可能中 —— 见 §②末 —— 于是 `tv=0`、`missedTime` 也不涨（因为 `missedTime`
只在 `resolveTime` 返回 null 时 +1，而 `WALK_MAX` 抬高后 `walkForText` 能扫到更多 TextView，
`isTimeLike` 有机会命中 ⇒ 表现为 `missedTime 3→0` 但 `tv=0` 不变）。

> ⚠️ 也就是说：`missedTime=0` 是**假阴性**（④级误命中了一个长得像时间的 TextView，
> 或者压根没走到 `paint`），不能当「定位通了」的证据。真正的判据只有 `tv`（已记住资源 id 的 item 数）。

## ② 时间控件的真实位置（类 + 字段 + 怎么上屏）

### 2.1 声明层：`Llh3/l1;`，字段 `o`，且**没有任何子类覆写**

```
Llh3/l1;  =  com.tencent.mm.plugin.sns.ui.improve.item.ImproveTimelineItemBuild
             extends android.widget.LinearLayout
  字段：o : Landroid/widget/TextView;
  方法：getPublishTimeText()Landroid/widget/TextView;
        0x0007 iget-object v2, v3  Llh3/l1->o : Landroid/widget/TextView;
        0x000c return-object v2
        另有 setPublishTimeText(TextView) → 0x0008 iput-object v3, v2  Llh3/l1->o
```

把 `g2 / j0 / z1 / mh3.i1 / o1 / b2 / l0 / s1 / x1` 九层的 `declaredMethods` **逐条列过**，
**没有任何一层声明 `getPublishTimeText`**（`grep PublishTime` 在这九层全部为空）。
⇒ 运行时 `getDeclaredMethod` 只能从 `l1` 那一层拿到，这就是「必须扫完整条链」的硬证据。

### 2.2 它是怎么被创建、怎么上屏的（`Llh3/l1;-><init>` 反汇编，classes3.dex）

```
0x0400 const-string 'buildBottomLineLayout'
0x0405 new-instance v3 <- android.widget.LinearLayout  （bottom line 容器）
…
0x0455 invoke-static/range Llh3/l1->l(Llh3/l1;ZIIZIILjava/lang/String;ILjava/lang/Object;)Landroid/widget/TextView;
0x0459 iput-object   v0, v10  Llh3/l1->o : Landroid/widget/TextView;        ← ★ 时间 TextView 落字段
0x0461 iget-object   v2, v10  Llh3/l1->o
0x0465 invoke-virtual ViewGroup->addView(o, LinearLayout$LayoutParams)      ← 加进 bottom line 容器
…
0x0538 invoke-virtual/range Llh3/l1->getRightPanelLayout()Landroidx/constraintlayout/widget/ConstraintLayout;
0x053c invoke-virtual      Llh3/l1->i(Lgr4/l;Landroid/view/ViewGroup;)V     ← bottom line 加进 rightPanelLayout
```

`Llh3/l1;->l(...)`（= `createImproveTextView`）体内：

```
0x0035 new-instance v3 <- com.tencent.mm.plugin.sns.ui.improve.view.ImproveTextView
0x0040 TextView.setSingleLine()
0x0049 TextView.setTextColor(…)
0x005b ImproveTextView.setTextSize(…)
0x0060 View.setId(I)                       ← ★ 有资源 id，命中后 findViewById 可用
0x0065 AppCompatTextView.setBackgroundResource(…)
0x0077 return-object v3
```

⇒ **`o` 是 `ImproveTextView`（extends AppCompatTextView → TextView），带资源 id，
物理上挂在 `… > ConstraintLayout(rightPanelLayout) > LinearLayout(bottom line) > ImproveTextView`。**

### 2.3 它的文案是谁写的（realTimeUpdate，`Llh3/x1;->d()`）

```
Llh3/x1;  =  com.tencent.mm.plugin.sns.ui.improve.item.ImproveTimelineItemMeasure   extends Llh3/l1;
Llh3/x1;->d()V   （const-string 'realTimeUpdate'）
  0x0008 iget-object   v2, v8  Llh3/x1->V : Lqh3/a;      ← 数据 item
  0x000c invoke-virtual Lqh3/a->k()Loh3/p;               ← ImproveSnsInfo
  0x0013 invoke-virtual Llh3/l1->getPublishTimeText()Landroid/widget/TextView;   ← ★
  0x001a invoke-virtual Loh3/p->J0()Ljava/lang/String;
  0x001e invoke-virtual TextView->setText(CharSequence)V
```

⚠️ 注意 `Llh3/x1` **extends `Llh3/l1`** —— 也就是说 **item 根 `g2` 本身就是一个 `x1`**，
`realTimeUpdate` 就是在 item 根自己身上跑的。这同时否掉了「时间控件在另一块 / 由 Header 统一渲染」
这一路猜想：**它就在 item 内，且在 item 根的直接字段里。**

### 2.4 那为什么 depth=9 的 View 树 dump 里没有任何一个 TextView 的文案是时间？

因为**渲染是 suspend 的**：

```
Llh3/j0;->g(Lki4/m0;Lqh3/a;IZLkotlin/coroutines/Continuation;)Ljava/lang/Object;
Lmh3/i1;->g(Lki4/m0;Lqh3/a;IZLkotlin/coroutines/Continuation;)Ljava/lang/Object;
Llh3/o1->I / Llh3/b2->H / Llh3/l0->G / Llh3/s1->E   全部带 Continuation
```

我们挂在 `WxRecyclerAdapter.c1/d1/a1/b1` 的 `afterHookedMethod` 上，那一刻 `c1` 刚把
数据塞进 holder、渲染协程**还没跑完**，`o` 的文案还是 `createImproveTextView` 出来的空串。
⇒ dump 里那几个 `ImproveTextView("")` **其中一个就是时间控件**（另两个是正文/调试标签）。
**这不是「控件不在树里」，是「控件在树里但还没填字」。** 三级按内容比对（③/④）因此必然失败，
只有 ②（按方法拿控件）能在这一刻成功 —— 而 ② 恰好被 4 层上限挡死了。

> 📌 这也顺带解释了 R35-1c 里「解释 A（根是容器，build 是子 View）」为什么**不成立**：
> 根 `g2` **就是** build 本身（`g2 → … → l1`），不需要再往下找 build。

## ③ 落点方案对比与推荐

| # | 方案 | 怎么拿到 | 代价 | 失效风险 | 结论 |
|---|---|---|---|---|---|
| **A** | **扫完整条父类链，在 item 根上调 `getPublishTimeText()`** | `root.javaClass` 沿 `superclass` 一路 `getDeclaredMethod`（上限 16 层，命中即缓存） | 每个根类**一次**反射扫方法表（之后走缓存）；命中后记住 `tv.id`，sweep 走 `findViewById` | 微信删/改名 `getPublishTimeText` ⇒ 自动降级到 ③④（③ 在渲染完成后可能中）。`l1.o` 由 `<init>` 赋值，不存在 null 风险 | 🥇 **推荐** |
| B | 找 `Llh3/x1` 实例，从它拿 | 同 A：`g2` **IS-A** `x1 IS-A l1`，拿到 item 根就等于拿到 `x1` 实例 | 与 A 完全相同，只是多绕一层概念 | 同 A | 与 A **等价**，不需要单独实现（A 已覆盖） |
| C | 从 `itemView.parent` 往上找 | `view.parent` 逐层向上找声明了该方法的 View | 每帧都要走父链（父是 RecyclerView，容易拿到容器） | 高：item 根自己就有这个方法，往上走只会拿错 | ❌ 不需要，且更险 |
| D | 自己 `addView` 一个 TextView / 在 bottom line 上追加 | 要先找到 `rightPanelLayout > bottom line LinearLayout`，再 `addView` | 布局风险高（宽高/权重/`GONE` 状态/复用残留），且要额外处理「什么时候移除」 | 中高：改布局会跟微信的下一次改版直接打架 | ❌ 只作 A/B/C 全失效时的最后手段 |
| E | 走 `xh3/o2` 那条旧线（`PLSysTextView`，字段 `p`） | 同 A 的方法名扫描，已天然覆盖 | 0（A 的实现按**方法名**找，两个类都会被扫到） | 真机若走 `OldVersionItemView` 兼容模式才会用到；本次真机根是 `g2`（`lh3` 系），用不上 | ✅ 已被 A 免费覆盖，无需额外代码 |

**⇒ 落地 A。** 改动极小（把 `while (up < 4)` 改成扫到 `Object`，上限 16），
且**不引入任何新 hook、不改布局、不碰模型层**。

## ④ 改动与行号

只改了 `SnsDeletedTag.kt` 一个文件（`SnsDeletedTagLogic.kt` 本轮**未改**；
`HookEntry.kt` / `SnsTag.kt` 未碰）。

| 行 | 改动 | 说明 |
|---|---|---|
| `298` | `bindHook` 的 `nodata` 分支新增 `retryResolve(holder, adapter, pos, view)` | 顺带修 `missed=2`（见 ④.2） |
| `333` | **新增** `RETRY_MAX = 12` | 延迟重试总预算 |
| `335` | **新增** `retryLeft` | 扣完就不再排队 |
| `348` | **新增** `retryResolve(holder, adapter, pos, view)` | `mainHandler.post { … }` 排一拍 |
| `354` | **新增** `retryPaint(...)` | 下一拍重新 `dataOf` → 解析 → `paint` |
| `748` | **新增** `SUPER_MAX = 16` | 父类扫描上限（真机需要 9，留 7 层余量） |
| `803` | `walkForGetter` 新增两条 `logOnce` | 成功打 `SNS_DELTAG tv host=… cls=… id=0x… depth=…`；拿到方法但返回 null 时打一行 |
| `856` | **重写** `publishTimeMethod` 的扫描循环 `while (up < 4)` → `while (up < SUPER_MAX)` + `Object` 短路 | ★ **本轮唯一的功能性修复** |
| `782` / `841` | KDoc 同步更新 | 说明「为什么要扫完整条链」 |

⛔ 未动：`hookBinds` / `matchesBindShape` / `itemViewOf` / `dataOf` / `probePath` / `unwrap` /
`adapterItem` / `paint` / `restore` / `sweep` / `deleted` / `stat` / `dump` / `strip`。
（`hooks=10` 与 `resolved` 两条链路一行未改。）

### 4.2 `missed=2 lastMiss=nodata(m0)` 的兜底（次要）

一次 bind 会触发多条 hook（`ki4/i0.onBindViewHolder` 体内 `k0→b1`、`i0→a1`、`l0→c1`），
先跑的那几条可能早于数据落位 ⇒ `dataOf` 这一拍三个候选全空。
`retryResolve` 排到**主线程下一拍**再判一次（`c1` 那时已把真 item 写进 `m0.E`）。

- 只补**一拍**，不做多帧轮询（滚动时堆 Runnables 会掉帧）；
- 总预算 12 次，扣完就彻底不再排队；
- 拿到的是 holder **当前**的数据：就算这两帧之间 ViewHolder 被复用，`holder → itemView`
  的对应关系不变，画上去的仍是这一格此刻的正确内容；
- 失败静默，不额外计数、不打日志。

## ⑤ 真机验证命令与期望输出

> ⛔ 本 lane 不碰 adb/构建/装包，全部交给主会话。

### V1：装包 → 进朋友圈 → 上下滚 2 屏 → 抓日志

```bash
adb logcat -d | grep SNS_DELTAG
```

**期望（顺序）**：

```
SNS_DELTAG installed hooks=10
SNS_DELTAG holder=m0 pick=g2 cands=[c3.d:g2]
SNS_DELTAG path m0 -> @
SNS_DELTAG tv host=g2 cls=ImproveTextView id=0x7f0aXXXX depth=0        ← ★ 本轮新增，出现即证明②级通了
SNS_DELTAG stat on=true deleted=8 bound=K hooks=10 binds=.. sns=.. resolved=.. missed=0
  hits=H missedTime=0 tv=K paths=1 lastMiss=
```

**判据（三条全中才算收口）**：

| # | 判据 | 说明 |
|---|---|---|
| **P1** | 出现 `SNS_DELTAG tv host=… cls=ImproveTextView` | ②级生效；`host=` 应是 `g2`/`i3`/`u3`/`f3`/`q2` 之一，`depth=0`（根自己就有） |
| **P2** | `tv >= 1` 且 `hits > 0` | 已记住资源 id，且归档里的 id 有落在屏上的（`deleted=8`） |
| **P3** | `python tools/dev.py ui 删除已拦截` 命中数 **≤** `deleted=8` | 不误伤（N1/N2） |

### V2：误伤回归

```bash
python tools/dev.py ui 删除已拦截
```
命中数应 == 屏上「在已删清单里」的条数；滚 3 屏后命中数不应无端增长（N4）。

### V3：万一仍然 `tv=0`

看这三行：

1. `SNS_DELTAG tv host=…` **没出现** ⇒ `publishTimeMethod` 还是没找到。抓
   `adb logcat -d | grep 'SNS_DELTAG tree'` 里 `host=` 的类名发回来（可能是另一个
   item 类型，其链上确实没有 `l1`），按新类名再查一次链。
2. 出现 `SNS_DELTAG: getPublishTimeText() returned null on <类名>` ⇒ 该类确实有这个方法但
   返回 null（与 dex 矛盾，说明 dex 与真机不同源）⇒ **重拉 dex**，不要在代码里硬猜。
3. `tv>0` 但界面上标记闪一下就没 ⇒ 是 `realTimeUpdate` 冲掉了，靠 2s sweep 补；
   若 2s 太久把 `SWEEP_MS`（`:72`）调到 1000。

## ⑥ 风险与回退

| 风险 | 概率 | 影响 | 回退 / 判据 |
|---|---|---|---|
| 扫完整条父类链 → 每个新根类多扫几次 `getDeclaredMethods` | 低 | 首屏多十几次反射 | 有 `timeMethodCache`（含负缓存），**每个类只扫一次**；且 `findViewById` 命中后不再走这条路。判据 `tv>=1` |
| 微信改版把 `getPublishTimeText` 改名/移到别的类 | 中 | ②级失效，自动降级到 ③④ | ③ 在渲染完成后有机会命中（`hint` 精确比对）；④ 是时间样式兜底。判据：不再出现 `SNS_DELTAG tv …`，且 `missedTime` 回升 |
| ④级（时间样式兜底）误命中昵称/正文 | 中 | 误伤（比不打标糟） | ②级一旦生效就根本不会走到 ④；真机用 N1/N2 判据：`dev.py ui 删除已拦截` 命中数 > `deleted` 即回退 |
| `retryResolve` 在快速滚动时排 Runnables | 低 | 掉帧 | 预算 12 次、只补一拍、只排主线程下一个消息；`retryPaint` 里 `dataOf` 探测预算已耗尽时是 O(1) 返回 |
| `retryPaint` 拿到的是复用后的新数据 | 低 | 理论上会画错一格 | `holder → itemView` 对应关系不变，画的是这一格**当前**应该显示的内容，且下一拍 bind/sweep 会纠正 |
| dex 与真机不同源 | 未知 | 全部结论失效 | 判据：`SNS_DELTAG tv host=` 不出现 + `getPublishTimeText() returned null`。此时**重拉 dex**再定位 |
| 标记在 bind 时被写、随后被渲染协程覆盖 | **已知** | 标记闪一下 | 2s sweep 会补（`:72 SWEEP_MS`）。本轮**没有**改这条 —— 若真机觉得闪，把 `SWEEP_MS` 调到 1000 |

### 回退（一键）

1. 只回退本轮：`git checkout` 掉 `SnsDeletedTag.kt` 即可
   （`HookEntry.kt` 接线不动，挂载点 + 取 id + 时间定位回到 R35-1c 的行为 = 打不上，但不误伤）。
2. 全量回退：删掉 `HookEntry.kt` 里 `SnsDeletedTag.install(lpparam)` 那一行。

### 没做 / 待定

- ⛔ **没做真机验证**（设备锁）。§① 的继承链、§② 的「`o` 在 `<init>` 赋值、挂在
  rightPanelLayout 下的 bottom line 里」都是**静态 dex 事实**；「真机上 `getPublishTimeText()`
  确实返回非 null」是**由这两条推出的必然结论**，但**真机日志出来之前仍标为待证**。
- ⛔ 没改 `SnsDeletedTagLogic.kt`（本轮不需要新判据）。
- ⛔ 没动 `SWEEP_MS`（2000ms）。真机若反馈「标记闪」再调。
- ⛔ 没启用「跨 render 完成后再补一次 paint」：渲染是 suspend，没有稳定的完成回调可挂，
  靠 2s sweep 兜底即可，不为了这个新增 hook。
- ⛔ 没加单测：`app/src/test/` 不在允许写的文件里。

[ROUND4]

## 真机验收（2026-10-30 00:00~00:08，设备 467cf5bc）

### 结论：灰标功能端到端可用；`hits=0` 是**数据不匹配**，不是 bug

三步取证，每步都可独立复现。

#### ① 先证「那 8 条到底在不在当前视口」—— 不在，且差得很远

`snstag dump` 的 `deleted(8)`（无符号）：

```
14947097412467233359 14994491238995792459 15009503771386188415 14984054144429593161
15022669716078138038 15033764437568787118 15004343040049746612 15040904052474254008
```

拿这 8 个 id 直接查微信 `SnsInfo`（`op=sql`，只读）：7 条命中、1 条已不在库里
（`…3405840021235297608`，说明那条是**真被删干净了**）。createTime 分布：

| createTime | 日期 |
|---|---|
| 1781832858 | 2026-06-19 |
| 1786238445 | 2026-08-09 |
| 1787482645 | 2026-08-23 |
| 1788657068 | 2026-09-06 |
| 1789272278 | 2026-09-13 |
| 1790841780 | 2026-10-01 |
| 1792164377 | 2026-10-16 |

而当前时间线顶部（屏上 3 条的 createTime）是 **1793283963 / 1793284445 / 1793285972 ≈ 2026-10-29 22:34**。
最新一条已删动态距今 **13 天**，最老 132 天。

⇒ 时间线是虚拟化的，滚动 3 屏只覆盖当天。**`hits=0` 是判定没错、数据不在视口**，
与代码无关。判据链上的「`hits>0`」这一条**必须在数据落在视口内时才有意义**。

#### ② 零改码验渲染：`snstag fake <屏上无符号 id>` 就够了

原来以为要「加个 fake 探针 + 编译装包」，实际不用：

`SnsTag.kt:673` —— `SnsDeletedTag.apply(host, id, del + forceSet)`。
第三参就把 `SnsTag.forceSet` **传导进来了**。所以 `snstag fake` 一次能验到
**SnsDeletedTag 的 `paint()` 全链路**（判定 → 取时间控件 → 落笔），不必碰代码。

实测（对屏上第二条 `u=15043127691604857463`）：

```
SNS_TAG fake u=15043127691604857463 s=-3403616382104694153
→ SNS_TAG stat … hits=1          （fake 前 hits=0）
→ dev.py ui 删除已拦截
  y=1489  bounds="[251,1489][639,1547]"  昨天 [删除已拦截]
```

标记落在**那条**动态的时间行上（`昨天` 正是它的 `hint`）⇒ 落点没串格。

#### ③ 反向对照：`snstag unmark` 后标记消失

```
→ dev.py ui 删除已拦截
  未找到含 '删除已拦截' 的节点
```

双向都过 ⇒ 既证「命中会画上」，也证「不命中会剥掉」（复用残留不会串味）。
**这一条比正向更值钱**：R31h 的事故形态就是「标记擦不掉/串到邻居」。

### 本轮挖到的三个坑（都会误导下一个人）

#### 坑 1：20 位无符号 snsId 直接写进 SQL 查不到 —— SQLite 把它当 REAL

```sql
-- ❌ 恒 rows=0（即使数据在）
select * from SnsInfo where snsId in (15043160246930444808, …)
-- ✅ 转成有符号
select * from SnsInfo where snsId in (-3403583826779106808, …)
```

原因：`snsId` 是 SQLite **INTEGER（有符号 64 位）**；字面量 `15043160246930444808 > 2^63-1`
⇒ 按 REAL 解析，尾数只有 53 位 ⇒ 精度丢失 ⇒ 与整数列比较恒不等。
`dev.py` 打印的一律是 `Long.toUnsignedString`，**取证时必须自己减 2^64**。

#### 坑 2：`tv=` 是**坏判据**，别看它

`SNS_DELTAG stat … tv=0` 看着像「一次都没记住时间控件」，实际是**度量口径错**：
`tvCached` 数的是 `Binding.tvId != View.NO_ID`，而真机上目标控件是

```
SNS_DELTAG tv host=f3 cls=ImproveTextView id=ffffffff depth=0
```

**`id=ffffffff` ⇒ 没有资源 id ⇒ `tvId` 恒为 `NO_ID` ⇒ `tv` 恒为 0**，与成败无关。

⇒ 判「时间控件找没找到」只认两条：
- `SNS_DELTAG tv …` 有没有出现（出现 = 找到了）
- `missedTime` 还涨不涨（不涨 = 新 item 都找到了）

实测：滚动 3 屏后 `missedTime` 从 14 冻住不再增（`binds` 44→64，`sns` 20→40），
即新绑定的 20 条**全部取到**了时间控件。

> 这是 R34 那条「对着用户能看见的事实写，别对着内部计数器」的第二次复现。
> 计数器本身也会坏 —— **加计数器时要顺手问一句「它在什么情况下恒为 0」**。

#### 坑 3：`SNS_TAG stat missed=2 lastMiss=pos=0/10 texts=[换封面|正在加载...]` 是**正常**的

「换封面」是朋友圈封面 header，不是动态 item，SnsTag 老链路的 offset 猜测在它身上必然失手。
不影响：SnsDeletedTag 自己那条链路是 `resolved=40 missed=0`。
看到这条 `lastMiss` 不要去修 offset。

### 仍未验（需要有真实动作，无法自造）

| 项 | 为什么自造不了 | 判据 |
|---|---|---|
| 真·已删动态落在视口内时会不会打标 | 最新一条已删动态在 13 天前，滚过去不现实 | 用户删一条**刚发的**朋友圈 → `hits>0` + 界面出现标记 |
| `realTimeUpdate` 冲掉标记后的 2s sweep 补回 | 同上（无命中项时 sweep 画的是空操作） | 标记稳定不闪 |
| 归档里 id 与 `ImproveInfo.snsID` 的长期一致性 | 需要新删除样本 | `deleted` 增量的同时 `hits` 也增 |
