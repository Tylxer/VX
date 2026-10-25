## 🔴 朋友圈评论（需求 6.2 评论域）—— 2026-10-19 真机取证（阶段0，未完）

- ⚠️ **评论不在 `SnsInfo.attrBuf` 里** —— 推翻了「删评论会重写 `attrBuf`」的猜测。
  评论是 `SnsMicroMsg.db` 的独立表 **`SnsComment`（16 列）**：
  `snsID | parentID | isRead | createTime | talker | type | isSend | curActionBuf | refActionBuf |
  commentSvrID | clientId | commentflag | isSilence | springWishFlag | isReminding | msgRelevanceType`
  实测样本：`type` 见 1/2 两种；`commentflag` 见 0/2；`curActionBuf`/`refActionBuf` 是 BLOB 且**实测全为 null**。
- 探针：`op=snsg sub=cmts`（⚠️ **它忽略 `id` 参数，返回全表** `rows=20 limit=20`）；
  ~~`op=snsc sub=stat|list|mark|...`（走 `SnsCommentGuard` + `SnsCommentTag` 两个 probe）。~~
  ⛔ **2026-10-24 N7：评论域代码已物理删除，`op=snsc` 已移除**（执行只会打一行「已移除」告警）。
  本条与下面两条的**技术结论仍然有效**（它们是取证结果，不是实现），
  但**探针入口不再存在**；原实现备份在 `docs/_r18_snscomment_backup/`。
- ✅ `feature/SnsCommentGuard.kt` **已经在做被删评论归档**：真机 `op=snsc sub=stat` →
  `binds=132`（评论 hook 挂上了），`archived=0` 只是因为开关 `antirevoke.comment` 没开。
- **⇒ 「[已删除] 标签」的正确判据方向**：归档里有、但 `SnsComment` 表里已查不到的那条（差集），
  而不是去解 `attrBuf`。
- ✅ **已结案（2026-10-19 18:40 静态取证，不需要真机）**：真删一条评论 = **物理 DELETE 行**，
  不是打标记。⇒ 差集判据**成立**，`DELETED_FLAG_MASK` 是死路（详见下节）。

### ✅ 静态取证结论：删单条评论走物理 DELETE（`_cache/base.apk`，离线）

工具：`tools/_r6_snsdex.py`（字符串/类型/引用反查）、`tools/_r6_snsdis.py`（按方法反汇编 const-string 序列）。
**类名没有被混淆** —— `plugin/sns/{storage,model,ui}` 全是可读名，这是本模块取证最大的便利。

**① 删单条评论的入口（`SnsCommentGuard` T1 待取证点，现已确定）**

```
Lcom/tencent/mm/plugin/sns/storage/s1;->Q0(JJI)Z          ← snsID, commentSvrID, type
Lcom/tencent/mm/plugin/sns/storage/s1;->P0(J)Z            ← 按 snsID 整条动态清空
```
`Q0` 反汇编出的 SQL 拼接（`tools/_r6_snsdis.py classes3.dex 'Lcom/tencent/mm/plugin/sns/storage/s1;' Q0`）：
```
delete from SnsComment where snsID = <J> and commentSvrID = <J> and type in (8,16)
```
> `type in (2)` 是另一条分支（两串常量 `'(2)'` / `'(8,16)'` 在 `Q0` 与 `Y0` 里都是同一组合并进来的）。

**② 读单条评论（`SnsCommentTag` T3/T4 待取证点，现已确定）**
```
Lcom/tencent/mm/plugin/sns/storage/s1;->Y0(JJI)Lcom/tencent/mm/plugin/sns/storage/r1;
   → select *, rowid from SnsComment where snsID = <J> and commentSvrID = <J> and type in (8,16)
Lcom/tencent/mm/plugin/sns/storage/s1;->q1(JLjava/lang/String;ILjava/lang/String;)Z   ← isExistComment
   → select count(*) from SnsComment where snsID=<J> and createTime=<I> and talker='<s>' and clientId='<s>'
Lcom/tencent/mm/plugin/sns/storage/s1;->f1()Ljava/lang/String;                          ← 动态拼 snsID 的来源
```

**③ `commentflag` 的语义 —— 不是"单条评论被删"，且没有任何读者**
- DDL（`classes3.dex` string[2232]）：
  ` snsID LONG,  parentID LONG,  isRead SHORT default '0' ,  createTime INTEGER,  talker TEXT,  type INTEGER,
   isSend INTEGER default 'false' ,  curActionBuf BLOB,  refActionBuf BLOB,  commentSvrID LONG,  clientId TEXT,
   commentflag INTEGER,  isSilence INTEGER default '0' `
  （后 3 列 `springWishFlag` / `isReminding` / `msgRelevanceType` 是迁移追加的，不在初始 DDL 里）
- 全 APK 只有 **2 条**写它的 SQL，都在 `Lcom/tencent/mm/plugin/sns/model/x2;`（`NetSceneSnsSync`）：

  | 方法 | 方法名（日志 tag） | SQL | 语义 |
  |---|---|---|---|
  | `x2#N` | `setCommentDeleted` | `update SnsComment set commentflag = 1 where snsID = <J> and talker = '<user>'` | **某人删掉了自己在这条动态下的（全部）评论** |
  | `x2#U` | `updateSnsDel` | `update SnsComment set commentflag = 2 where snsID = <J>` | **整条动态被删** |

  ⚠️ 两者都**不带 `commentSvrID`** —— 都不是「单条评论被删」。
- 列名 `commentflag` 的引用只有 3 处，全是 WCDB 实体类 `Ljj/t9;` 的
  `initAutoDBInfo` / `convertFrom` / `convertTo` —— **纯 ORM 管道，业务代码零读取**。
- **⇒ `SnsCommentLogic.DELETED_FLAG_MASK` 永远填不出来，也不该填。**
  它连"单条评论被删"的语义都不承载；`DelForm.FLAGGED` 在 8.0.48 **没有对应物，应删除**。

**④ 判据定型**
| 现象 | 判据 | DelForm |
|---|---|---|
| 归档里有、`select * from SnsComment where snsID=? and commentSvrID=?` 查不到 | **物理删除** | `MISSING` |
| 行还在、正文空/全空白/纯标点 | 正文被清 | `EMPTY` |
| 行还在、正文正常 | 活着 | `ALIVE` |
| ~~flag 命中掩码~~ | **不存在，删除** | ~~FLAGGED~~ |

**⑤ 拦截点（`SnsCommentGuard` T1 已可填）**
```
Lcom/tencent/mm/plugin/sns/storage/s1;->Q0(JJI)Z      ← 真正的删除动作，返回 boolean
   ↑ 唯一调用点：Lcom/tencent/mm/plugin/sns/model/m2;->onGYNetEnd(...)V   (NetSceneSnsObjectOp)
```
- `m2` 是**全部动态对象操作**的统一 NetScene（改可见性 / 静默 / 点赞 / 评论 / 删除），
  内部 `L(SnsObject)` = `clearSnsObjectExcludeBlackList`、`M(SnsObject)` = `clearSnsObjectIncludeContactList`
  —— **不是删除评论的分支**。删除走的是 `s1.Q0`。
- ⚠️ **拦截点必须放在 `Q0`，不能放在 `m2.onGYNetEnd`**：后者同时处理十几种 op，
  在它上面拦会误伤改可见性/静默/点赞。`Q0` 参数就是 `(snsID: J, commentSvrID: J, type: I)`,
  语义唯一、无需解析。
- `type` 取值：删除与查询都用 `type in (8,16)`（另一分支 `(2)`）；
  评论实测样本见过 `1` / `2`，**具体哪个值对应文字评论尚未逐值确认**，但不影响拦截
  （三个值一起拦即可，`commentSvrID` 已经唯一定位）。

**⑤-a `Q0` 的调用形态（2026-10-19 补证，决定 hook 怎么写）**

```
access_flags = 0x0001        ⇒ public instance（**不是 static**）
sig          = Q0(JJI)Z      ⇒ (snsID: long, commentSvrID: long, type: int): boolean
iget [12201] = s1->d : Lz84/z;    ⇒ 拿到的 db 是 **z84/z 包装器**，不是 SQLiteDatabase
内部执行     = z84/z.j(String table, String sql)Z
```

- `z84/z` 的方法表里 **`s()` → `com.tencent.wcdb.database.SQLiteDatabase`** ——
  这意味着 `Q0` 内部与我们 execSQL hook 命中的是**同一个 db 实例**，归档时能直接读行。
- `param.thisObject` 是 `s1` 实例（`Q0` 是实例方法），因此 hook 里能走
  `thisObject.d → z84/z.s() → SQLiteDatabase` 拿到可查询的 db。
- ⚠️ **不能只靠 `Q0` 拦住删除**：`Q0` 返回 `boolean`，若置 `param.result = true`，
  `m2` 会认为删成功并继续走后续清理（更新 UI 缓存 / 触发刷新）。真正「不让它删」的
  硬拦截仍在 `execSQL` 层（返回 void，置 null 即跳过方法体）。
  ⇒ **两层分工**：`execSQL` 层负责**拦**（已有），`Q0` 层负责**拿语义**
  （`snsID` / `commentSvrID` / `type` 三个结构化参数，不用解析 SQL 字符串）。
- `type` 的语义在 `Q0` 里是拼 SQL 用的：`type` 命中某分支就拼 `(2)`，否则拼 `(8,16)`。
  ⇒ 三个值（1 / 2 / 8 / 16）**都要一起拦**，不能按值筛选。


**⑥ 评论行 ViewHolder（`SnsCommentTag` T3/T4 已可填）**

> ⛔ **本段已被双重推翻，仅存档静态取证过程，勿据此实现**
> - ① **⑦ 段**推翻挂载假设：该 ViewHolder 是评论**详情页**条目，不是时间线评论行。
> - ② **⑩ 段（2026-10-19 真机 6/6 实测）**推翻取 ID 前提：**UI 层 proto `q`/`r` 恒为 0，根本没有 `commentSvrID`**，
>   匹配键必须换 `(createTime, talker, type)`（= `s1.q1.isExistComment`）。
>
> 本段内「T4 正确写法 = 读 `holder.h` → `q95.r`」「这是精确路径」「`q95.r` 已佐证」等句**均已失效**；
> `:599` 那句「剩下唯一不确定 `q` 与 `r` 的语义标签」也已由 ⑩ 定案。现行判据以 **⑩ + `MEMORY.md` 锚点**为准。

```
Lcom/tencent/mm/plugin/sns/ui/SnsCommentDetailUI$CommentListAdapter$ViewHolder   ← T3
```
字段（全部实测，`_r6_snsdex.py fields`）：

| 字段 | 类型 | 用途 |
|---|---|---|
| `a` | `ImageView` | 头像 |
| `b` / `c` / `d` | `TextView` ×3 | **文本落点**（昵称 / 正文 / 详情页那种多行布局） |
| `e` | `FrameLayout` | 容器（贴标签的候选父） |
| `f` | `SnsTranslateResultView` | 翻译结果 |
| `g` | `Object` | 通用负载 |
| **`h`** | **`Lq54/q95;`** | **评论 proto 对象 —— `commentSvrID` 从这里取** |
| `i` | `String` | 昵称（已拼好的显示串） |
| `j` | `EmojiStatusView` | emoji 状态 |

- ⚠️ **T4 的「无参 getter 方法」不存在，原设计方向错了** —— 这个 ViewHolder 只有一个构造函数、
  没有任何方法。`q95` 也不是标准 protobuf（无 getter），是 **lite protobuf：字段全 public、直读**。
  ⇒ T4 正确写法：**读 `holder.h`（字段反射）→ 再读 `q95.r`（`long`）**。
- **`commentSvrID` = `q54/q95.r`（`J`）**，实证依据（`m2.onGYNetEnd` 指令序）：
  ```
  [843] iget m.field_attrBuf
  [851] iget SnsObject.CommentUserList
  [873] iget q95.r        ← 与 CommentUserList 紧邻，取的是 commentSvrID
  [884] iget SnsObject.CommentUserList
  [907] iget m.field_snsId
  [909] iget m2.i
  [911] iget q95.r
  ```
  `q95` 字段表：`d/e/h/o` String · `f/g/i/j/n/p/s/t/v` int · **`q`/`r` long** · `u` LinkedList · `w`/`x` jx4。
  ⇒ `q95.r` 是唯一被当作评论 ID 使用的 long；`q95.q` 全类无读取点（可能是 feed id，未用）。
  ⚠️ 剩下唯一不确定：`q` 与 `r` 的 proto 语义标签（哪个是 `commentSvrID`、哪个是 `commentId`）。
  但 `m2` 里取 ID 只用了 `r`，**这一处就够用**。
- 这是**精确路径**：拿到 `commentSvrID` 后，`SnsCommentTag.rowOwnersByText` 的降级模糊 owner
  可以升级成按 `svrId` 精确归属，`N²` 与「模糊 owner 不重打」两个局限一起消掉。
- ⚠️ 真机必须**先验 `h` 非 null** 再读 `r`（RecyclerView 复用期 `h` 可能为空），不能盲调。
- ✅ `q95.r` 是 commentSvrID 已由**读取点统计**独立佐证：`q95.r` 被 **31 个方法**读取
  （含 `m2.onGYNetEnd`、`bf3/k0` 的正文/昵称 setter），而 `q95.q` 只有 2 个
  （`compareContent` / `op`）。⇒ 只有 `r` 参与评论业务，`q` 不是。

**⑦ ⛔ T3/T4 的落点修正（2026-10-19 复核，推翻 ⑥ 的挂载假设）**

⑥ 认定的 `SnsCommentDetailUI$CommentListAdapter$ViewHolder` **不是时间线的评论行**：

```
CommentListAdapter 的全部方法：getCount / getItem / getItemId / getView(I,View,ViewGroup)
```

⇒ 它是 **BaseAdapter + ListView** 模式，**根本没有 `onBindViewHolder`**；
而 `SnsCommentTag` 挂的是 `WxRecyclerAdapter.onBindViewHolder`（时间线）。
两者**没有任何交集** —— 按 ⑥ 写的精确路径在时间线上是**永远不执行的死代码**。
该类实际只用于**评论详情页**（`SnsCommentDetailUI`），由 `CommentListAdapter.getView` 使用。

**时间线真正的评论渲染链（已实证）**：

```
Lcom/tencent/mm/plugin/sns/ui/item/improve/recycle/TimelineCommentView;
   super = android.widget.LinearLayout          ← 整块「评论+点赞」容器
   b(View, CharSequence, CharSequence, int, int, Lq54/q95;)V    ← 逐条评论绑定，第 6 个参数就是评论 proto
     └─ 每条评论的**单条容器**：
        Lcom/tencent/mm/plugin/sns/ui/widget/SnsCommentCollapseLayout;
           super = android.widget.RelativeLayout
           d = SnsComment2LinePreloadTextView   ← 折叠态正文
           e = SnsCommentPreloadTextView        ← 展开态正文
           f = TextView                          ← 昵称/其他
           d(CharSequence, CharSequence, int, int)V   ← 正文写入（时间线每次 bind 都调）
           setTag(Object) / getTag                   ← 复用机制（Object，非 ViewHolder）
```

- **T3 应该换成 `SnsCommentCollapseLayout`**：它是「单条评论」的 View，
  `getNormalCommentTv()` / `get2LineCommentTv()` 直接拿到正文 TextView，
  贴在它上面就落在单条评论下方，位置正确。
- ⚠️ **但 `SnsCommentCollapseLayout` 上没有任何 `q95` 字段**（字段只有 2 个 TextView +
  4 个 CharSequence/int）⇒ **无法只靠它取 commentSvrID**。
- **能同时拿到「单条评论 View」与「commentSvrID」的唯一位置**：
  挂 `TimelineCommentView.b(...)`，在 `afterHookedMethod` 里用第 6 个参数（`q95`）取 `.r`，
  再在 `thisObject` 子树里按「本次新增/绑定的那条 `SnsCommentCollapseLayout`」定位。
- ⚠️ 这**不是** RecyclerView + ViewHolder 模型，所以现有 `SnsCommentTag` 的
  「`onBindViewHolder` → holder.itemView → rowsOf」整套结构**不适用**于时间线评论行。

**⑧ ✅✅ 最终定案：improve 时间线从 View 直接读 commentSvrID（2026-10-19 指令级 + 双解码器复核）**

⑦ 的结论**只对旧版时间线 / 评论详情页成立**。真机实际使用的 UI 是 **improve 时间线**，
它走完全不同的渲染链，`rf3.a.b` 在那条链上**一次都不被调用**（真机实测 `binds=746 bindIds=0`）。

**improve 链（已用两个独立解码器 + androguard 全量 54362 方法逐条比对，100% 一致）**：

```
lh3.x1.b(...)                                    ← item 绑定（1168 条指令的巨型方法）
  └─ ImproveInteractionLayout.setupLayout()
       └─ setupCommentLayout(oh3/p)              ← 逐条评论循环，insns_size=406 / 192 条指令
            [154] const/4 v7, #0                 ← i = 0
            [167] if-ge v7, v5 -> [402]          ← 循环头（出口 402）
            [177] getChildAt(i) → check-cast ImproveCommentView   ← 评论行 View
            [209] LinkedList.get(i) → check-cast q54/q95          ← 该行的评论 proto
            [220] iget q54.q95.j:I               ← 拼 key
            [229] iget-wide q54.q95.r:J          ← ⭐ commentSvrID
            [268] iput-object ImproveCommentView.o:q54.q95         ← ⭐ 写进 View 自己的字段
            [338] ImproveCommentView.l()         ← loadContent（消费 o）
            [394] add-int/lit8 v7, v7, #1        ← i++
            [400] goto/16 -> [167]               ← 回循环头
```

- **⭐ 关键事实**：`ImproveCommentView` 自身声明字段 `o: Lq54/q95`（不是继承来的），
  `q95.r:J` = commentSvrID。⇒ **从 View 子树直接反射读即可，不需要 hook 任何方法**。
- **支配性已程序化证明**：`[268]` 所在基本块被循环体尾部支配 —— 屏蔽它之后**无法回到循环头**
  ⇒ 每次迭代必然执行 `iput`，每个 `ImproveCommentView` 实例的 `o` 都一定被写入。
- **时序**：`setupCommentLayout` 跑在 `onBindViewHolder` **内部**，
  我们的钩子挂 `afterHookedMethod` ⇒ 读到的必然是本次 bind 的新值，不存在跨帧陈旧。
- ⛔ **不要再挂 `rf3.a.b(View, q95)`**：它只覆盖旧版时间线与评论详情页；
  `TimelineCommentView.b` 的唯一调用者 `a(ms,int)` 方法体只处理点赞布局，注释区根本不走。
- ⛔ **落点容器在 improve 链上是 `ImproveCommentView` 本身**，不是 `SnsCommentCollapseLayout`
  （后者在 improve 链上恒不出现 ⇒ `rowsOf(itemView, SnsCommentCollapseLayout)` 恒空 ⇒
  精确路径「解析成功但一条都打不上」的第二个坑）。

**⑨ ⚠️⚠️ ⑧ 的两条结论被真机推翻 —— 真正的时间线结构（2026-10-19 21:10 真机探针定案）**

⑧ 的字段链（`ImproveCommentView.o.r`）**本身是对的**，但它对「什么时候读、从哪个 View 读起」
的两个前提全错。真机事实如下（全部来自新加的 `probe("rv")` / `probe("types")` / `probe("kids")`）：

| 事实 | 探针 | 实测值 |
|---|---|---|
| 朋友圈列表就是 improve 列表 | `rv` | `ImproveRecyclerView.getAdapter() = ...improve.component.u1`，`itemCount=32` |
| 该 adapter 的继承链 | 静态 dex | `u1 → ju2.s0 → WxRecyclerAdapter → ki4.i0 → SynchronizedAdapter` |
| **只有 `ki4.i0` 声明 `onBindViewHolder`** | `rv` 反射 | `i0.onBindViewHolder (c3,int)` 与 `(c3,int,List)`；`u1`/`s0` 只声明 `g(n0)` |
| `onBindViewHolder` 收到的 holder | 日志 | `ki4.m0`，`itemView = ImproveRecyclerView`（**整个列表**，`childCount=3`） |
| 真正单条动态容器 | `kids` | `ImproveRecyclerView → L1: lh3.f3 ×2 / lh3.g2 ×1` |
| `lh3.*` 是什么 | 静态 dex | `lh3.j0 → lh3.z1 → mh3.i1 → … → LinearLayout`，**是 View 不是 adapter** |
| `lh3.j0.g` 是什么 | 静态 dex | 字符串常量 `'onBindViewHolder'` → `R()` = `onBindViewHolder$suspendImpl` |
| `lh3.j0` 的子类（6 个） | 静态 dex | `b0 / f3 / g2 / u3 / v / y2`；**只有 `f3` 覆写 `g`** |

**⛔ 结论 1：`onBindViewHolder` 的 `afterHookedMethod` 时机太早，读不到评论行。**
真机双向证据：同一个 `itemView`（`ImproveRecyclerView`），
- 在 `onBind` 里扫 → `rows=0`（`SNSC_FUNNEL`），`ids=0`；
- 手动触发 `probe("types")` 扫 → `improve=1`，**能扫到 `ImproveCommentView`**。
⇒ `afterHookedMethod` 时列表里 32 个 item 的内部尚未布局完，评论行还没挂上树。

**⛔ 结论 2：`lh3.j0` 才是「单条动态」的绑定点，且它是 suspend 桥接。**
`g()` 只是 `SnsMethodCalculate.markStartTimeMs` 包着的 `onBindViewHolder` 入口，
真正干活在 `R()`（`onBindViewHolder$suspendImpl`）。⇒ 要挂就得挂 `lh3.j0` 的 6 个子类的 `g`，
**且必须给每个子类单独挂**（`f3` 覆写了 `g`，其他子类继承基类的）。

**✅ 结论 3：snsId 不必再靠 ±2 窗口猜正文。**
`lh3.x1.setData(qh3.a)` → `qh3.a.d : oh3.p` → `oh3.p` 就是 improve 链的 SnsInfo：
- `oh3.p.toString()` 输出 `"ImproveInfo(name=…, snsID='…')"`（与本文档第 66 行一致）
- `oh3.p.getCommentList()` 读字段 `x1: gr4.g`，**评论列表（含 commentSvrID）在这里**
- `qh3.a.getUniqueId()` 拼 `"sns_table_"` / `"ad_table_"` 前缀
⇒ 数据层直读 `lh3.<item>.getData().d` 比遍历 View 更可靠，也不受布局时机影响。


---

## ✅ 「显示未读」开关 —— 2026-10-19 真机双向验收通过

需求：设置页 `聊天 → 显示未读`（键 `chat.show_unread`），控制分组行右上角未读角标。

**改动（3 处，缺一不可）**
1. `GroupRowView.Model` 加 `showUnread: Boolean = true`；渲染判断改 `if (m.showUnread && m.unread > 0)`。
2. `ConvFilter.rebuild()` 里**读一次** `Kv.getBool(CHAT_SHOW_UNREAD)`（不放 `buildModel`/`build()` 热路径 ——
   那里每行跑一次就是一次 SQLite），**并把它加进重建指纹 stamp**
   （`"$n|$enabled|$filterId|$showUnread|..."`）。少了这一项，只改开关时指纹不变 →
   被「指纹相同直接返回」短路 → 表现为**开关失效**。
3. ⚠️ `EntryReceiver.kvset` 分支补 `ConvFilter.refresh()`。原来那批 `runCatching { xxx.refresh() }`
   漏了 ConvFilter，改完开关会话列表不重建 → 表现为**改了没反应**（也是验收最容易踩的假阴性）。

**真机结果（`tools/_r5_unread.py`，双向对照）**
```
开关开：角标 1 个  bounds=[1252,638][1354,705] text=99+ desc=未读1079
开关关：角标 0 个
✅ 双向对照通过
```

**方法论：给角标加 `contentDescription = "未读N"`**
uiautomator 读不到背景色，只能看 text；角标 text 是 `99+` 这类数字，跟别处数字无法区分。
加 content-desc 后 `dev.py ui 未读` 就能直接断言，**不用截图**。

### ⚠️ 会话列表验收的三个坑（都真机踩过，脚本已修）
1. **不要向下滑**（`swipe 700 1600 → 700 700`）：分组行是列表**最上面**几行，一滑就出屏幕 → 假阴性。
2. **不要连续向上猛滑**：微信在顶部继续上拉会触发「下拉」手势，把**「最近使用的小程序」整块拉出来**。
   （`ConvFilter.RestoreAnchor` 注释里专门写过这个耦合，所以它还原锚点时刻意还原**原锚点**而不是 0。）
3. 小程序面板一旦被拉出来**会盖住会话列表，`am start` 也推不掉**，必须先 `input keyevent KEYCODE_BACK`。
   ⇒ `_r5_unread.py` 的 `refresh()` 现在是：**BACK 收浮层 → am start → 不做手势**。

### 6.3 自然触发验收 —— ✅ 2026-10-19 双向 PASS（连修 7 个根因）

首次验收（开关 ON、真人发 0.01 红包并点开）结果：**没自动拆**，`found=0 scans=9 clicked=0`。
但人工补发一条 `op=redpacket sub=dry` **立刻命中** `cd="开"` —— 说明判断逻辑（`RedPacketLogic`）是对的，
问题全在**扫描调度与点击方式**。逐层挖出 7 个根因，全部修完后双向验收通过。

#### 根因 1~3：调度层（扫描根本没跑起来）

**① `REDPACKET_DELAY_MS` / `REDPACKET_MAX_ATTEMPTS` 是空壳配置**
设置页有「识别后的等待时长」（默认 800ms）和「同一红包最多试图决策几次」（默认 3），
但代码里扫描延迟写死 `SCAN_DELAYS = [220, 600, 1100]`，这两个键当时**只有已删除的探针读出来打印**。
⇒ 改成 `scanDelays` 由 `REDPACKET_DELAY_MS` 按比例缩放（base/3、base*3/4、base*11/8），
再补一拍长尾 `base + 2000ms` —— 网络慢时按钮要好几秒才渲染，「开」按钮是**晚到**不是没有。

**② 红包页 resume 被节流吞掉 + 没扫到不补扫（连环坑）**
- `onResumed` 无条件走 `scheduleScan` 的 `BURST_MS=5200ms` 节流。真机实测拆完一个红包会连跳
  `NotHookReceiveUI → BeforeDetailUI → DetailUI → NotHookReceiveUI`，几次 resume 挤在 4~8 秒内，
  节流把**用户手动点开的下一个红包**一起吞了 → `scans` 卡在 9 不再增长。
  ⇒ 红包页 resume 改走 `force=true` 绕过节流（这是「用户真要拆一个红包」的强信号）。
- `cands.isEmpty()` 直接 return，不补扫。⇒ 加 `retryOnEmpty`：空结果时补扫，最多 4 次、间隔 1.5s
  （probe / verify 两路不补扫，避免双重排程把空扫配额提前耗光）。
- 派发过但按钮**还在**（动画未结束空响 / 界面又弹回来）时要补发 ⇒ `RESEND_DELAY_MS=700` 补扫。

**③ 去重键用全局 session 计数器 → 必然误判**
`session` 每次 Activity resume 自增，用它做去重键意味着「只要期间没 resume 过，同一个界面永久不点」。
⇒ 改成 **`identityHashCode(targetView) + 页面类名`**：第二个红包的按钮是不同 View 对象，天然不撞。

#### 根因 4~6：点击层（扫到了、点下去、但红包不开）

**④ `performClick()` 对微信这个 PAG 按钮无效**（最关键的一条）
`View.performClick()` 返回 `true`、日志打 `clicked`，红包**纹丝不动**。
那个「开」是 PAG 动画按钮，只认真实触摸流。⇒ 改成向**根 View** `dispatchTouchEvent`
派发 `ACTION_DOWN` / `ACTION_UP`（`MotionEvent.obtain` + `setSource(SOURCE_TOUCHSCREEN)`）。

**⑤ UP 必须真延迟 60ms，不能同一轮连着 dispatch**
同一轮里连续 dispatch DOWN、UP，实际间隔 ≈0ms，PAG 会判成长按/空响。
⇒ `mainHandler.postDelayed(..., 60)` 里派发 UP。**DOWN 抛异常时必须补一个 `ACTION_CANCEL`**，
否则 ViewGroup 的 `mFirstTouchTarget` / `mPressed` 留在按下态，会**吃掉下一次真实点击**。

**⑥ 坐标必须取自扫描命中的那个「开」View，不能上溯到 clickable 父容器**
旧代码上溯到父容器再 `performClick`，那时对象身份只决定"调谁的 listener"；
改成坐标派发后，**对象身份就是坐标** —— 上溯到全屏容器 = 点在整屏中心，必然点空或误点。
真机取证：「开」正好 `360x360@540,1853`，中心 `720,2033`。⇒ 坐标一律用 `target.view`。

#### 根因 7：验收判据本身写错了（假红 + 假绿）

**⑦ `clicked` 不能当成功判据，且只查一次必然判死**
- `clicked` 只说明"触摸派出去了"，**不代表拆开**（根因 ④ 就是被它骗过去的）。
- `opened` 判据必须**同时满足两条**：① 离开原页（`top` 变了）**且** ② 新页仍是
  `RedPacketLogic.isRedPacketClass`。只满足"离开了"不算 —— 用户可能自己按了返回，
  计进 `leftPage` 单独统计，**不计 opened**。
- 只查一次 900ms 会把慢的成功判死：真机实测派发→跳页 **1226~1424ms**。
  ⇒ 改成轮询：首查 `VERIFY_MS=900`，之后每 `VERIFY_STEP_MS=400` 查一次，
  总预算 `VERIFY_TOTAL_MS=4000`。

#### ✅ 双向验收结果

| 方向 | 条件 | 结果 |
|---|---|---|
| 正向 | 开关 ON，真发 0.01 | `dispatched=1 opened=1 leftPage=0`，**1424ms** 后跳到 `LuckyMoneyDetailUI`，**PASS** |
| 反向 | 开关 OFF | `scans=0 dispatched=0 opened=0`，8 分钟零动作，**PASS** |
| 链路 | 开关 OFF + `sub=dry` | `dry hit "开" android.widget.ImageButton 360x360`（扫描链路活着） |

真机日志（正向）：
```
02:52:08.568 dispatched "开" ImageButton loc=360x360@540,1853
02:52:09.473 verify still there sent=1 after 901ms      ← 900ms 时确实还没跳，只查一次就误判
02:52:09.793 resume LuckyMoneyBeforeDetailUI
02:52:09.994 opened (to detail) sent=1 after 1424ms
02:52:14.700 stat dispatched=1 opened=1 leftPage=0
```

> 验收方法：`python tools/_r5_redpacket.py --wait=N`（开关 ON 等人发红包）/ `--phase=off`（反向对照）。
> **判据只看 `opened` 增长**，不看 `dispatched` / `clicked`；同时核对 `leftPage` 没跟着涨。

### ⛔⛔ 已删除且**永久不做**：消息层「自动抢红包」引擎（2026-10-19 结案 · **2026-10-20 02:07 用户决定永久取消**）

> ⛔ **2026-10-20 02:07 用户决定：该功能永久不做，不再恢复。**
> 下方「代码恢复点 / 如何恢复」仅作历史存证，**不得据此重启**。

曾有 `feature/LuckyLogic.kt`（414 行）+ `probe/LuckyProbe.kt`（227 行）+ 496 行单测，
配上 6 个配置键（`redpacket.decide` / `type_codes` / `text_markers` / `allow` / `exclude` / `max_attempts`），
一副"能从消息层自动抢红包"的样子。

**实测结论：这一整套是空壳，已整块删除。**
- `LuckyLogic.decide()` 在生产代码里**零调用点**（全仓 grep 确认）。
- 两条识别判据是**空的**：没有红包消息的 `type` 低位、没有文案标记表 —— 谁也不知道
  `MSGTYPE` 里哪个值代表红包。`REDPACKET_TYPE_CODES` / `TEXT_MARKERS` 只是摆设。
- 6 个配置项打开**不会有任何效果**，唯一"消费者"是探针读出来打印。
  这比没有更糟：让人以为功能能做，实际点了什么都不发生。

**能力边界（写进需求清单 6.3）**：本模块红包域**只做 UI 层自动点击**（用户手动点开红包、
领取界面弹出时自动点「开」），**不做**消息层监听、**不做**后台自动抢。
~~要恢复消息层，**必须先取证出红包消息的 `type` 低位**（怎么做：静态扫 dex 找 redpacket 消息分发 →
真机 hook 消息插入把 `type` 打出来），否则就是在没有判据的情况下瞎猜。~~
⛔ **2026-10-20 02:07 用户决定永久不做** —— 以上恢复路径**作废**，保留仅作存证。

~~代码恢复点：`git show 86e7808:wxgroup/app/src/main/java/com/lx/wxgroup/feature/LuckyLogic.kt`~~
（`EntryReceiver` 里 `op=lucky` 分支与 `Keys.kt:131` 附近仍留有该恢复指令注释 ⇒ **是误导，待清理**，见 R9 计划 N1 项。）

---

## ⑩ ✅✅ 定案：UI 层评论 proto **不带 commentSvrID**，匹配键必须换成 (createTime, talker, type)

> 2026-10-19 21:32 真机实测。这一节**推翻了 ⑧/⑨ 段「`q54/q95.r` = commentSvrID」的假设**，
> 是评论区标签功能能不能落地的前提结论。**下轮不要再猜 `r` / `q` 了。**

### 实测：`q54/q95` 全部字段 dump（`probe("ids")`，真实评论行）

```
SNSC_TAGIDS #0 ImproveCommentView svr=0 proto=
  dS='wxid_jsmu3n3ucorv22'  eS='QQ'  hS='我想喝米线'
  fI=0  gI=2  iI=1792410895  jI=1  nI=0  oS=''  pI=1
  qJ=0  rJ=0  sI=0  tI=0  vI=0
```

字段语义（逐个对上 DB 的 `SnsComment` 16 列）：

| 字段 | 实测值 | 语义 | 对应 DB 列 |
|---|---|---|---|
| `d: String` | `wxid_jsmu3n3ucorv22` | 评论者 | `talker` |
| `e: String` | `QQ` | 备注/来源 | （不入库） |
| `h: String` | `我想喝米线` | 正文 | `curActionBuf` 解出 |
| `i: Int` | `1792410895` | **创建时间**（秒） | `createTime` |
| `g: Int` | `2` | 类型 | `type` |
| `j: Int` | `1` | 是否本人发送 | `isSend` |
| `q: Long` | **0** | — | — |
| `r: Long` | **0** | — | — |

⚠️ **`q` 与 `r` 两个 long 恒为 0（6/6 行实测）** ⇒ **UI 层根本没有 commentSvrID**。
DB 里 `commentSvrID` 是有的（实测 417/1185/1251/1347…），只是**微信在 UI 层主动丢弃了它**。
这解释了 `r:J` 全仓 47 处用法为何几乎全是 `StringBuilder.append` / `Long.valueOf`：
它确实被当 key 拼，但**值恒为 0**。

### ⛔ 结论 1：精确路径（按 svrId 定位行）**在当前微信版本不可行**

`IMPROVE_COMMENT_SVR_FIELD = "r"` 这条路**到此为止**。不要再去试 `q`/`j`/`n`/`v`/`s`/`p`。

### ✅ 结论 2：改用微信**自己**的判重键 `(snsID, createTime, talker)`

依据是微信源码里 `SnsCommentTag` 早就记下的那条（`docs` 第 497 行）：

```
Lcom/tencent/mm/plugin/sns/storage/s1;->q1(JLjava/lang/String;ILjava/lang/String;)Z   ← isExistComment
   → select count(*) from SnsComment where snsID=<J> and createTime=<I> and talker='<s>' and clientId='<s>'
```

**微信自己就是靠 `createTime + talker` 定位一条评论的**（`clientId` 是本地生成的、
UI 层也不带，所以只用前三项）。我们沿用同一个键，语义上与微信完全对齐。

- **UI 侧可得性**：`d`(talker) / `i`(createTime) / `g`(type) **三个字段全都有非 0 实测值**。
- **DB 侧可得性**：归档表 `sns_comment_archive` 存了全部 16 列，含 `createTime` / `talker` / `type`。
- **唯一性实测**：20 行样本里 `(snsID, createTime, talker)` 重复组 = 0，`(snsID, createTime)` 也 = 0。
  但**样本太小，不把唯一性当已证明的性质** ⇒ 代码里仍按 Set 处理（多命中就都标，不假设 1:1）。
- **时间单位一致**：UI `i=1792410895` → `2026-10-19 11:54:55 UTC`；DB `createTime=1791946393` 同量级（秒）。
  ⇒ **不需要任何单位换算**，直接比。

### 复现命令（下次直接跑，别重推）

```bash
python tools/_r7_install.py 10    # 装包 + 两次重启 + 走 UI 点击进朋友圈 + 滚 10 屏
python tools/_r7_snscq.py ids --keep    # dump 当前屏评论行的 q54/q95 全字段
python tools/_r7_snscq.py stat --keep   # 漏斗：item/sns/del/rows/got
```

⚠️ **`ImproveSnsTimelineUI` 不可 export**，`am start -n` 起不来（会静默停在 `LauncherUI`），
**必须走 UI 点击**（发现 → 朋友圈）。`tools/_r7_install.py` 已内置该导航 + `act:` 校验。

## ⑪ ⛔ **功能已停止**（2026-10-20 02:22 用户决定「评论防删除 + `[已删除]` 标签不做了」）· 本节仅存档

> ⛔ **不再推进**：本节是**已停止功能**的取证记录，`del=0` / `rows=0` 两项 P0 **不再真机定案**。
> 保留价值：① 第三节「已知不可行路线清单」对**任何**朋友圈评论改动仍有效；② 若将来翻案，这是唯一起点。
> 本节只记录**已经实测到的事实**，不记录推测。

### 一、A 项 `del=0` —— 「精确匹配」那段代码从未被执行过

真机漏斗输出原文：

```
SNSC_FUNNEL item=48 sns=36 del=0 rows=0 got=0 keyOk=0 typeMiss=0
```

**`del=0` 的判据含义**：`delMap` 里当前时间线的任何 `snsId` 都没有对应的已删评论集合
⇒ 主流程里「精确匹配」那段代码**一次都没被执行过**。
因此 `got=0` **不能**用来证明「精确匹配逻辑有 bug」——它只说明入口条件从未满足。

**根因已查清（不是代码 bug）**：设备上的归档库 `sns_comment_archive` 只有 **1 行**历史 seed：

```
(snsID=-3412346577660079447, createTime=1792240578, talker='wxid_55uz5752kbtk21', type=1, commentSvrID=1)
```

该 `snsID` **不在当前时间线** ⇒ `delMap[snsId]` 恒为空 ⇒ `del` 恒 0。

**反证：snsId 解析本身是好的**（否则下面两个现象都不成立）：

- `sns=36` 且在涨；
- 新增的 `recentSns` 环形采样能打出真实 snsId（如 `-3410888102863957446`），
  且**每个 snsId 出现两次** —— 对应「先摘标签后打标签」的两趟 bind。

**下一步（尚未执行）**：把当前屏幕真实评论灌进归档库（`tools/_r8_seed.py`，R8 已写），
让 `del>0`，才能验精确路径。

### 二、B 项 `rows=0` —— 「时机太早」与「样本无评论」两种解释尚未区分

- 现象：连续滚 20 屏 + 单屏滚动后立即 `probe("ids")`，**连续 7 次**都是 `rows=0`：

```
SNSC_TAGIDS item=ImproveRecyclerView rowCls=ImproveCommentView rows=0 preciseIds=0
```

- **但历史上曾拿到过 `rows=6`**（事后扫描能扫到）⇒ 不能断言「永远扫不到」。
- 两种互斥解释：
  - (a) **时机太早**：`onBind` 触发时评论行尚未上树，此刻扫不到；
  - (b) **该条动态本就没有评论**：纯粹是样本问题。
- **尚未定案**。判死方法：滚动后按延迟序列（0 / 0.5 / 1 / 2 / 5 秒）多次探 `ids`，
  看 `rows` 是否随延迟上升（脚本 `tools/_r8_rows.py`，R8 已写）。
- 另有一条**静态取证** lane 在并行推进（看 `TimelineCommentView.b` 的调用时机），
  结论落在 `_cache/r8_static_rows.md`，定案后回填本段。

### 三、已知不可行路线清单（避免后人重踩）

1. **按 `commentSvrID` 定位评论行 —— 不可行**。
   UI 层评论 proto（`q54/q95`）的两个 long 字段 `q` / `r` **恒为 0**（6/6 行实测）。
   DB 侧 `commentSvrID` 确实存在且非 0（实测 417/1185/1251/1347），但微信**不带上去**。详见 ⑩ 段。
2. **`DELETED_FLAG_MASK` / `commentflag` 路线 —— 不可行**。
   `commentflag` 只表示「某人删光自己评论」=1、「整条动态被删」=2，业务零读取。
   删单条评论是**物理 DELETE**（`s1.Q0`）。详见既有结论段。
3. **`am start -n` 直接进朋友圈 —— 不可行**。
   `ImproveSnsTimelineUI` **未 export**，`am start -n` 会静默停在 `LauncherUI`。
   必须走 UI 点击（发现 → 朋友圈）。
4. **`am broadcast --el` 传 unsigned 64 位 snsId —— 不可行**。
   snsId 是 unsigned 64，超过 `Long.MAX_VALUE` 时 `--el` 抛 `NumberFormatException`，
   且**整条广播不送达**（不是参数丢失，是整条没投递）。必须转 signed： `u - 2**64`。
5. **靠 `probe("seed")` 长期注入 —— 不可行**。
   `SET_TTL_MS = 5000L`，seed 注入后 5 秒即被 `loadAsync()` 用 DB 内容覆盖 `delMap`。
   seed 必须在 5 秒内消费。

> 相关脚本：`tools/_r8_seed.py`（灌库）/ `tools/_r8_rows.py`（延迟探）；静态取证见 `_cache/r8_static_rows.md`。

---

