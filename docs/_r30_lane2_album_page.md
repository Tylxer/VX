# R30 · Lane2 好友个人相册页 数据通路静态取证

> 目标：给「进入某人的朋友圈（好友个人相册页）」的数据通路做静态 dex 取证，
> 回答方案 A（Response-Merge）能否照搬到相册路径。
> 只读，不改源码，不碰 adb，不跑 gradle。
> 取证基线：微信 8.0.48 (versionCode 2588)，15 个 dex 已解包在 `_cache/dex/classes*.dex`。
> 本文所有「地址」均为 code_item 内的 insns 偏移（code unit 偏移），非 dex 文件绝对偏移。

---

## 0. 结论速览（先给答案）

1. 好友个人相册 = `h3`（`NetSceneSnsUserPage`，getType=212，CGI `mmsnsuserpage`）。
   `k3`（`NetSceneSnsWwUserPage`，getType=5203，CGI `mmsnswwuserpage`）是**企业微信**朋友圈，不是个人好友相册。
2. 相册响应类是 **`q54/jc5`**（**不是**时间线的 `q54/zb5`），但它的 `f` 字段**同样是
   `LinkedList<SnsObject>`**（0x11 PUBLIC\|FINAL），且写库走的是**同一条 `l5.o`**。
   ⇒ 方案 A 的注入代码**可复用**，只改「响应类名 + 钩子方法签名 + wxid 判据来源」。
3. 目标 wxid 在**请求对象 `q54/ic5` 的字段 `e`（String）**，在 `h3.<init>` 的
   **0x00ba** 处被赋值；同时在 `h3` 实例字段 **`e`（String）**（0x0037 赋值，`getUserName()` 返回它）。
   ⇒ hook 时从 **`this.e`**（反射读字段 `e`）拿 wxid 最稳。
4. 结论：**可行**。在 `h3.N(Lq54/jc5;Ljava/lang/String;)V` 的 `before` 里往
   `param.args[0].f`（Xposed 视角：`param.thisObject`=h3、`args[0]`=jc5 响应、`args[1]`=stringSeq）
   注入 SnsObject，即可走「写库 + 事件 + UI 渲染」全链路。
   ⚠️ 注：Xposed/LSPosed 实例方法的 `param.args` **不含 this**（this 在 `param.thisObject`）。

---

## 1. 类与方法签名 [DONE]

### 1.1 类名

| 混淆名 | 全限定名（dex 内字面量） | getType | CGI | 语义 |
|---|---|---|---|---|
| `h3` | `com.tencent.mm.plugin.sns.model.NetSceneSnsUserPage` | 212 | `/cgi-bin/micromsg-bin/mmsnsuserpage` | **好友个人相册页** |
| `k3` | `com.tencent.mm.plugin.sns.model.NetSceneSnsWwUserPage` | 5203 | `/cgi-bin/micromsg-bin/mmsnswwuserpage` | **企业微信朋友圈页** |

证据：
- `h3` 定义于 `classes3.dex`；`getType()` 返回 `const/16 v0, 212`（code=0x376b04, @0x000a）。
- `k3` 定义于 `classes3.dex`；`getType()` 返回 `const/16 v0, 5203`（code=0x37bae0, @0x000a）。
- 类名字面量来自两方法体 `markStartTimeMs` 前的 `const-string`：
  `h3.onGYNetEnd` @0x000b `'com.tencent.mm.plugin.sns.model.NetSceneSnsUserPage'`；
  `k3.onGYNetEnd` @0x000b `'com.tencent.mm.plugin.sns.model.NetSceneSnsWwUserPage'`。
- CGI/type 来自构造函数：`h3.<init>`(0x376c90) @0x0087 `const-string '/cgi-bin/micromsg-bin/mmsnsuserpage'`、@0x008b `const/16 212`；
  `k3.<init>` @0x0085 `'/cgi-bin/micromsg-bin/mmsnswwuserpage'`、@0x0089 `const/16 5203`。

### 1.2 方法签名

两个类 `onGYNetEnd` 签名完全一致（标准 NetScene 回调）：

```
onGYNetEnd(IIILjava/lang/String;Lcom/tencent/mm/network/t0;[B)V
```

响应处理方法（这才是真正的注入点）：

| 类 | 方法 | 完整签名 | 作用（方法体内 const-string） |
|---|---|---|---|
| `h3` | `N` | `N(Lq54/jc5;Ljava/lang/String;)V` | `insertListAndUpdateFaultInfo` |
| `h3` | `M` | `M(II)V` | 处理 `jc5.s`（ext 列表） |
| `h3` | `O` | `O()Z` | 处理 `jc5.x`（star/置顶列表） |
| `k3` | `N` | `N(Lq54/vc5;)V` | （请求侧） |
| `k3` | `O` | `O(Lq54/wc5;Ljava/lang/String;)V` | （响应侧，k3 的对应方法） |

证据：`tools/_r28fw_cls.py 'Lcom/tencent/mm/plugin/sns/model/h3;' --methods` /
`'...k3;' --methods`（classes3.dex 输出，method_ids 直读，无解码歧义）。

对照（**Xposed 视角，`param.args` 不含 this**）：时间线 `c3.N(IILjava/lang/String;Lq54/zb5;)V`
的响应对象在 `args[3]`；相册 `h3.N(Lq54/jc5;Ljava/lang/String;)V` 的响应对象在 **`args[0]`**
（第 2 个声明参数是 `stringSeq`，在 `args[1]`）。参数位置不同。

---

## 2. 响应结构 [DONE]

### 2.1 相册响应类 ≠ 时间线响应类

- 时间线响应：`q54/zb5`（c3 用）。
- 相册响应：**`q54/jc5`**（h3 用）。k3 响应是 **`q54/wc5`**。

证据：`h3.<init>`(0x376c90) @0x0080 `new-instance Lq54/jc5;` → @0x0085 存入
`modelbase/j.b`（响应槽）。`onGYNetEnd` 从 `modelbase/m.b → modelbase/l.a` 取出后按
`Lq54/jc5` 访问字段（@0x007e `iget-object ..., jc5->f`）。

### 2.2 `jc5` 字段表（20 个实例字段）

| 字段 | 类型 | 说明（据 onGYNetEnd / op() / N() 交叉证实） |
|---|---|---|
| `d` | String | 返回的 FirstPageMd5（@0x026f 与 h3.p equals 判 "md5 is nochange"） |
| `e` | I | **objCount**（onGYNetEnd 日志 `' objCount:' + jc5.e`） |
| **`f`** | **LinkedList** (0x11) | **主动态列表（SnsObject）** ← 注入目标 |
| `g` | I | （未在相册日志暴露，protobuf 字段 5） |
| `h` | Lq54/hc5; | （protobuf 子结构） |
| `i` | I | **NewRequestTime**（N() 日志 `' NewRequestTime %s'`） |
| `j` | I | **ForSameMd5 count**（日志 `'ForSameMd5 count: ' + jc5.j`） |
| `n` | Lq54/mb5; | （子结构） |
| `o` | J | 长整型（→ h3.z） |
| `p` | J | 长整型（日志附带） |
| `q` | String | retTips（→ h3.q） |
| `r` | I | （protobuf 字段） |
| `s` | LinkedList (0x11) | **第二列表（SnsObject）**，`M(II)` 处理 |
| `t` | Z | bool |
| `u` | String | |
| `v` | I | |
| `w` | I | |
| `x` | LinkedList (0x11) | **star/置顶列表（SnsObject）**，`O()` 处理 |
| `y` | J | |
| `z` | Lq54/x95; | |

### 2.3 `f` 的元素类型 = SnsObject（死证据）

`jc5.op()`（code=0x8268ac，protobuf 读写）里对 `f` 的反序列化：
```
0x02d1 LinkedList.size / 0x02d9 LinkedList.get
0x030b new-instance Lcom/tencent/mm/protocal/protobuf/SnsObject;
0x030d invoke-direct SnsObject.<init>
0x0318 iget-object ..., jc5->f
0x031a LinkedList.add
```
即 `jc5.f` 元素逐个 `new SnsObject().parseFrom(...)` 后 `add` 进列表。
`l5.h`（insertSnsList）@0x0038 也对列表元素 `check-cast ..., SnsObject`，@0x0045 读
`SnsObject.Username`、@0x0057 读 `SnsObject.CreateTime`。三处交叉印证：**`f` 是 `LinkedList<SnsObject>`**。

`jc5.s`、`jc5.x` 同样是 `LinkedList<SnsObject>`（op() 中 @0x0224/@0x0275/@0x0231 各自 `new SnsObject` + `add`）。

### 2.4 与 `zb5`（时间线）对比

`zb5` 也有 `f`(0x11 LinkedList)、`o`/`r`/`t`/`v`/`w`/`z` 等多个 LinkedList 与 `e:I`。
**两类的「主列表字段都叫 `f` 且都是 `LinkedList<SnsObject>`」**，因此方案 A 里
`反射 getObjectField(resp, "f") as MutableList` 这一句**原样复用**，只需把响应类从
`zb5` 换成 `jc5`。

---

## 3. 目标 wxid [DONE，含指令地址]

### 3.1 请求对象与字段

| 类 | 请求对象 | wxid 字段 | 类型 | 赋值指令地址 |
|---|---|---|---|---|
| `h3` | `q54/ic5` | **`e`** | String | `h3.<init>`(code=0x376c90) **@0x00ba** |
| `k3` | `q54/vc5` | **`e`** | String | `k3.<init>` **@0x0099** |

证据（h3 构造函数，用修正版解码器 `_cache/_r30_disasm.py` 反汇编）：
```
0x0037  iput-object v7, v0, h3->e:String      ← h3.e = 第1个构造参数(String)
0x00ba  iput-object v7, v14, ic5->e:String    ← ic5.e = 同一个 String（用户名）
0x00bc  iput-wide   v8, v14, ic5->f:J         ← ic5.f = 第2个参数(long)
0x00c0  iput        v2, v14, ic5->j:I         ← ic5.j = 第7个参数(int, filterType)
0x0113  iput        v10,v14, ic5->g:I         ← ic5.g = 第5个参数(int, source)
0x010b  iput-wide   v1, v14, ic5->h:J         ← ic5.h = h3.j (lastRequestTime)
0x0111  iput        v1, v14, ic5->i:I         ← ic5.i = a1.c() (pullType)
0x0156  iput-object v2, v14, ic5->d:String    ← ic5.d = FirstPageMd5
```
寄存器回绕说明：`iput-object v7, v14, ic5->e` 中 v7 = `move-object/from16 v7, v17`（@0x0002）
即第 1 个 String 参数；v14 = `check-cast`（@0x00b8）得到的 `ic5` 请求对象。

### 3.2 构造方法签名与参数映射

`h3` 与 `k3` 各有两个构造器（9 参是 10 参的转发壳）：

```
<init>(Ljava/lang/String;JZIIIII)V     ← 转发壳（code=0x376c58，直接 invoke-direct 10参版）
<init>(Ljava/lang/String;JZIIIIII)V    ← 真实现（code=0x376c90, regs=27 ins=11）
```

真实现参数表（p0=this）：
| 参数位 | 类型 | 落到 |
|---|---|---|
| p1 | String | **用户名（wxid）** → `h3.e`(@0x0037) → `ic5.e`(@0x00ba) |
| p2 | long | → `h3.i`(@0x0039) → `ic5.f`(@0x00bc) |
| p3 | boolean | → `h3.g`(@0x003d) |
| p4..p9 | int×6 | 依次 source/filterType/sourceType/serverType 等（日志 `'filterType:%d sourceType:%d serverType:%d'` 用 valueOf(v23,v24,v25)） |

### 3.3 hook 时怎么拿 wxid（关键）

`h3.N(jc5, String)` 的参数表里**没有 wxid**——N 的第 2 个参数是 **stringSeq**（分页游标，见 §4）。
wxid 在实例字段 `h3.e` 上：`getUserName()` 直接 `iget h3->e` 返回（code=0x376b88 @0x000a）。

⇒ 注入时判据来源：**反射读 `param.thisObject` 的 `e` 字段（String）**，或调 `getUserName()`。
**不要**拿 `h3.N` 的 `args[1]` 当 wxid（那是 stringSeq，拿它过滤会把 A 的动态插进 B 的相册）。

---

## 4. 分页游标 [DONE]

### 4.1 请求侧（`q54/ic5`，11 字段）

| 字段 | 类型 | 语义 | 赋值地址（h3.<init>） |
|---|---|---|---|
| `e` | String | **用户名** | @0x00ba |
| `d` | String | **FirstPageMd5** | @0x0156 |
| `f` | J | **MaxId** | @0x00bc |
| `h` | J | **LastRequestTime** | @0x010b |
| `i` | I | **PullType** | @0x0111 |
| `j` | I | **FilterType** | @0x00c0 |
| `g` | I | **Source** | @0x0113 |

protobuf 字段顺序（`ic5.op()` code=0x814cdc 序列化顺序）：BaseRequest → `d`(field2) → `e`(field3) → `f`(field4) → `g`(field5) → `h`(field6) → `i`(field7) → `j`(field8) → `n/o/p/q`(field9~12)。
与「SnsUserPageRequest」标准 schema 一致（field2=FirstPageMd5, field3=Username）。

### 4.2 响应侧（`q54/jc5`）

| 字段 | 语义 |
|---|---|
| `e` | objCount（本页条数） |
| `j` | ForSameMd5 count |
| `d` | 返回的 FirstPageMd5（"md5 is nochange" 判据） |
| `i` | NewRequestTime |
| `o`/`p` | 长整型游标 |

`h3.N` 里再从 `jc5.f` 取 `getFirst().Id → h3.i`(maxId)、`getLast().Id → h3.j`(minId)，
三者一起进 `a1.d(userName, maxId, minId, NewRequestTime)` 更新下次请求游标。
另外 `h3.N` 调 `l5.h(v3=userName, v4=continueID, v2=list, v12=stringSeq)`（@0x000d，35c 寄存器
`{v3,v4,v2,v12}` 已核）——即 `l5.h(String,int,LinkedList,String)` 的第 4 个 String 是
**stringSeq**（SnsInfo 表的分页游标列），这也就是 `h3.N` 的第 2 个入参。

### 4.3 「缺条」不必然等于「真删」

响应是**分页**的（首屏 `jc5.f` + 翻页用 maxId/minId + FirstPageMd5 续传）。
某条不在当前 `jc5.f`，可能只是**这一页没带**。但注意：被删动态在服务端已被删，
**任何一页都不会返回**，所以对「被删动态」来说「缺条=真删」成立，注入必须做。
这与时间线 `c3.N` 结论一致（注入只影响首屏，翻页由方案 B 兜底）。

---

## 5. 可见范围 / 三天可见信号 [DONE]

在整条相册数据通路里**没有「只显示最近 N 天」的字段**：

- `SnsObject`（52 个实例字段，全列见 §附）里无 day/limit/visible 字段，只有
  `CreateTime`(I)、`DeleteFlag`(I)、`Id`(J) 等。
- 请求 `ic5`（11 字段）、`vc5`（9 字段）：无 day 字段。
- 响应 `jc5`（20 字段）、`wc5`（15 字段）：无 day 字段。

结论：微信的「三天可见/半年可见」是**服务端截断**——服务端按可见范围直接决定返回哪些
SnsObject，客户端数据路径不做按天过滤。⇒ 我们在响应里 `add` 注入对象 = 绕过服务端截断，
注入对象会进入渲染（只要 DB 写成功 + UI 判活通过，见 §6）。

---

## 6. 二次过滤（oh3/p · ImproveSnsInfo）[DONE]

### 6.1 类身份

`oh3/p` 的混淆路径是 **`Loh3/p;`**（**不是** `Lq54/oh3;`，后者是另一个 protobuf 类）。
方法体内 `const-string` 给出实名：**`com.tencent.mm.plugin.sns.ui.improve.model.ImproveSnsInfo`**。

### 6.2 isDieItem / isInValid 读什么

`Loh3/p;` extends `Ljj/e6;`（SnsInfo 存储模型基类）。两方法：
```
isDieItem()  ==  (getLocalFlag() & 32) != 0  &&  l0() != 0

> ⛔ **2026-10-28 订正**：本节原写 `& 2`，**错**。字节级实测 `code 0x3bdbf0`
> `0x001c const v1, #0x20`（=32）→ `0x0020 and-int v0, v0, v1`。
> 全文见 `docs/_r30_62_album_gate.md` §2。
isInValid()  ==  l0() != 0
```
- `getLocalFlag()` = `iget field_localFlag:I`（Ljj/e6，code=0x64a274 @0x0000）
- `l0()` = `iget-wide field_snsId:J`（Ljj/e6，code=0x64a388 @0x0000）

即：`isDieItem` 判死条件 = **SnsInfo.localFlag 的第 1 位（值 2）置位**且 snsId 非 0。

### 6.3 相册路径用不用它 / 我们的对象会不会被过滤

- `oh3/p`（ImproveSnsInfo）被 503 处引用，全部来自 `sns.ui.improve.*`。`isDieItem()` 的
  直接调用者是 **两个时间线 improve item**：
  `Llh3/x1;->b(Lki4/m0;Lqh3/a;I)Z`(@0x6ec9e6) 与 `Lmh3/a0;->a(ZLjava/lang/String;)V`(@0x71d050)。
  其中 `lh3/x1` 的父类是 `lh3/l1`（`ImproveTimelineItemBuild`），`mh3/a0` 的构造器吃 `oh3/p`——
  二者都是**时间线 improve 渲染项**，不是相册。
- **相册 UI 是独立传统 adapter**：`qg3/i` = `com.tencent.mm.plugin.sns.ui.album.SnsAlbumAdapter`
  （父类 `androidx/recyclerview/widget/w1` = 原生 RecyclerView.Adapter，非 improve item）。
  其字段 `d:Ljava/util/List;` 为数据列表，`f:Ljava/lang/String;` 为用户名；**不引用 `oh3/p`**，
  即相册渲染路径**根本不走 `isDieItem()`**。（此点与 r30-timelabel 的 lane1 取证一致：
  相册用独立 adapter + XML 布局，不复用时间线 improve item。）
- 相册写库 `l5.h`（insertSnsList）→ 逐条 `l5.o`（与时间线 c3.N 调的是**同一个 `l5.o`**）。
  `l5.o` 对注入对象是 `new SnsInfo()` + `setSnsId/setContent/setCreateTime/setAttrBuf...`，
  **不设置 `field_localFlag`**（反汇编 grep `localFlag` 无 setter 调用），新 SnsInfo 的
  localFlag 默认为 0。即使有 `isDieItem`（`(localFlag&2)!=0 && snsId!=0`）也不会命中。
  （`l5.o` 里 `addSourceFlag` 设的是 sourceType，与 localFlag 是不同字段。）

⇒ 结论（比时间线更宽松）：相册写库走同一 `l5.o`，且相册渲染层**没有 isDieItem 过滤**，
注入对象面对的判活门槛比时间线更低，无需补任何字段。

---

## 7. 能否照搬：结论 [DONE]

**可行，需三处改动**，卡点已全部排除：

| # | 项 | 时间线（现状） | 相册（需改） |
|---|---|---|---|
| 1 | 钩子方法 | `c3.N(IILjava/lang/String;Lq54/zb5;)V` | `h3.N(Lq54/jc5;Ljava/lang/String;)V` |
| 2 | 响应对象/列表 | args[3] = `zb5`，字段 `f` | args[0] = `jc5`，字段 `f`（同名同型 `LinkedList<SnsObject>`） |
| 3 | wxid 判据 | 时间线无按人过滤 | 反射读 `param.thisObject.e`（String）= 目标 wxid |

不变量（无需改）：对象构造 `new SnsObject().parseFrom(attrBuf)`、写库 `l5.o`、事件发布
`onSceneEnd` 全部与时间线共用同一条链。判活门槛更低：相册渲染层（`qg3/i` SnsAlbumAdapter）
**不走 isDieItem**，注入对象仅需满足 `l5.h` 的 `checkSnsObjectValid`（`Username` 非空、
`CreateTime > 0`）即可，而这两项 `parseFrom(attrBuf)` 天然满足。

**唯一新增的硬要求**：注入前必须**按 `this.e`（wxid）过滤**，否则会张冠李戴——把 A 的已删动态
插进 B 的相册（相册页天然只显示 owner 的动态，串了会非常显眼且难回滚）。

### 7.1 渲染数据源确认（跨 lane1 印证）[DONE]

r30-timelabel（lane1，相册 UI 取证）追到相册 adapter `qg3/i`(SnsAlbumAdapter) 的数据源是
**SnsInfo 存储实体（读库），不是响应事件对象**，与我 lane2 的写库链完全闭环：

- `qg3/i.b0()`(=access$000) @0x0007 `iget-object qg3/i;->d:Ljava/util/List;`（数据列表字段）；
- `onBindViewHolder`(code=0x95682c) @0x0085 `invoke-virtual SnsInfo.getHead`（元素是 SnsInfo）；
- `h0()`(addSize) @0x003f `iget-wide Ljj/ca;->field_snsId:J`（SnsInfo 的存储表实体字段）。

⇒ 我 lane2 经 `h3.N → l5.h → l5.o` 写进 SnsInfo 表（snId/createTime/attrBuf）的注入行，
会被相册查询查出并塞进 `qg3/i.d` 进列表渲染；**无需过 oh3/p(ImproveSnsInfo)**（比时间线更干净）。

⚠️ 唯一待真机确认点（lane1 提出，合理）：相册 item 还依赖 `createTime` 做**日期分组**，且相册
查询可能有**分页游标窗口**（`getLimtiSeq`/`limitSeq`）。若注入行 `CreateTime` 落在首屏返回的
时间跨度之外，可能**不在首屏显示、需翻到对应时间位置**（由方案 B 兜底，与时间线 plan_a 同款）。
注入按 `CreateTime` 降序插到正确位置（insertIndexDesc）后，只要台账里的被删动态是「近期删除」，
其 `CreateTime` 天然落在首屏跨度内，风险低。

---

## 附：给主会话的实现建议

### 钩子精确签名

```
h3 = com.tencent.mm.plugin.sns.model.NetSceneSnsUserPage   (classes3.dex)
hook 方法：N(Lq54/jc5;Ljava/lang/String;)V   （before）
⚠️ Xposed 视角（param.args 不含 this）：
  param.thisObject = this (h3 实例)
  param.args[0]    = 响应 q54/jc5
  param.args[1]    = stringSeq（⚠️ 不是 wxid！）
  param.args.size  = 2
```

### 注入流程（伪码）

```
onBefore(N):
  this_ = param.thisObject; resp = param.args[0]
  wxid = reflectField(this_, "e") as String          // h3.e = 用户名
  if (wxid != 台账里要补的目标 wxid) return            // ⛔ 必须过滤
  list = reflectField(resp, "f") as MutableList       // jc5.f : LinkedList<SnsObject>
  收集 respIds / respTimes → pickMissing → new SnsObject().parseFrom(attrBuf) → list.add
```

### 失败日志设计（要能区分四种情况）

| 日志 | 含义 |
|---|---|
| `SNS-ALBUM install: class missing` | 类/方法没 hook 上（钩子未触发） |
| `SNS-ALBUM hook N failed` | hook 安装失败 |
| `SNS-ALBUM resp class != jc5 (got <类名>)` | 响应类不对（args[1] 反射失败/类型不符） |
| `SNS-ALBUM wxid <v> != target <t> skip` | wxid 不匹配（该响应不是目标相册） |
| `SNS-ALBUM inject snsId=… parse=ok idx=… size=…` | 注入成功（L1） |
| `SNS-ALBUM-FAIL no field f / buf empty / parse fail` | 字段/BLOB/解析异常 |

判据分层沿用方案 A：L1 `list.add` 成功 → L2 `sourceType=2` 落库 → L3 界面逐屏可见。

### 建议的验证顺序

1. 先 hook 只打 `wxid` 与 `resp.getClass()`，确认进相册触发的是 `h3.N` 且 `this.e` 正确。
2. 再开注入，只对台账目标 wxid 注入 1 条，L1→L2→L3 逐层确认。
3. 最后放量到多条，验证顺序（CreateTime 倒序）与「不串到别人相册」。

---

## 附：SnsObject 全字段（52 个实例字段，供判断有无 day/limit 字段）

`Id(J)` `CreateTime(I)` `DeleteFlag(I)` `ExtFlag(I)` `FlowerFlag(I)` `LikeFlag(I)` `LikeCount(I)`
`CommentCount(I)` `GroupCount(I)` `GroupUserCount(I)` `BlackListCount(I)` `BlackContactTagIdListCount(I)`
`WithUserCount(I)` `WithUserListCount(I)` `WithTaListCount(I)` `WithTaHasOther(I)` `WithChatroomListCount(I)`
`ObjectType(I)` `NoChange(I)` `IsNotRichText(I)` `InTopList(I)` `GuideFinder(I)` `GuideQW(I)` `GuideTop(I)`
`WeiShangFeedType(I)` `WeiShangSellerType(I)` `WeiShangVideoSourceType(I)`
`Username(String)` `Nickname(String)` `ReferUsername(String)`
`ObjectDesc(Lq54/jx4;)` `ObjectOperations(Lq54/jx4;)` `PreDownloadInfo(Lq54/ng4;)` `SnsRedEnvelops(Lq54/jb5;)` `WeAppInfo(Lq54/i95;)`
`BlackContactTagIdList(LinkedList)` `BlackList(LinkedList)` `CommentUserList(LinkedList)` `FlowerUserList(LinkedList)`
`GroupContactTagIdList(LinkedList)` `GroupList(LinkedList)` `GroupUser(LinkedList)` `LikeUserList(LinkedList)`
`NewWithTaList(LinkedList)` `WithTaList(LinkedList)` `WithUserList(LinkedList)`
`ReferId(J)`

（无任何 day/limit/visible 字段，印证 §5。）

---

## 取证工具与临时产物说明

- 反汇编：`tools/_r27_disasm.py`（原始脚本在 `const/4`/`move-*`/`22c`/`3rc` 处有解码缺陷，
  会 mislabel 寄存器导致 desync）。
- 本次补丁脚本：`_cache/_r30_disasm.py`（修正了 move-* 系列、22c A/B 位宽、cmp-long 长度、
  filled-new-array 等格式；**§8 又补修了 22b（and-int/lit8 立即数取自 CC 字节而非 BB）、
  22s（lit16 的 vA/vB 取 4bit）、23x（aget/aput 为 2 units，第二单元为 BB|CC）**。
  修复前 22b 的「立即数」实为源寄存器号（如 `and-int/lit8 v5,v0,4` 里的 4 其实是 vB，真立即数被吞），
  导致 §8 之前的掩码读数不可信；§8 全部掩码已用修复版重跑。只读，不落业务产物）。
- 中间反汇编：`_cache/_r30_h3_onGYNetEnd.txt`（h3.onGYNetEnd 全量，供复核 N 调用点 @0x0247）。

---

## 附：主会话独立复核（2026-10-27 20:5x · 不采信子代理原话，逐条重跑反汇编）

| # | 复核项 | 命令 | 结果 |
|---|---|---|---|
| 1 | `h3.N` 方法存在且签名 | `tools/_r28fw_cls.py 'Lcom/tencent/mm/plugin/sns/model/h3;' --methods` | ✅ `N(Lq54/jc5;Ljava/lang/String;)V`；另有 `getUserName()Ljava/lang/String;` |
| 2 | `jc5.f` 类型 | `_cache/_r30_disasm.py 'Lcom/tencent/mm/plugin/sns/model/h3;' N jc5` | ✅ `iget-object v2, v11, Lq54/jc5;->f:Ljava/util/LinkedList;`（@0x0007、@0x0016 两处） |
| 3 | `h3.e` 是不是用户名 | 同上 `getUserName` | ✅ @0x000a `iget-object v0, v2, h3;->e:Ljava/lang/String;` → `return-object` |
| 4 | `k3` 是不是个人相册 | `getType` + `<init>` | ✅ **不是**。类名 `NetSceneSnsWwUserPage`、`getType()=5203`（@0x000a `const/16 v0,5203`）、CGI `@0x0085 '/cgi-bin/micromsg-bin/mmsnswwuserpage'`（**ww = WeWork**）⇒ 企业微信朋友圈 |
| 5 | **`isDieItem()` 判死条件** | `_cache/_r30_disasm.py 'Loh3/p;' isDieItem` | ✅ 子代理正确，**但推翻了项目既有结论**，见下 |

### ⛔ 复核发现的既有结论错误（必须订正）

`docs/_r27_m3_timeline_source.md:311` 写的是「`isDieItem()` 查 `getLocalFlag()<=0`」。

实测反汇编（`Loh3/p;#isDieItem sig=Z() units=32`）：

```
0x0007  invoke-virtual Ljj/e6;->getLocalFlag
0x000b  and-int/lit8 v2, v2, 2        ← 判的是「第 1 位（值 2）是否置位」
0x000d  if-lez v2, +14                ← (flag & 2) <= 0 ⇒ return false
0x000f  invoke-virtual Ljj/e6;->l0     ← l0() = snsId
0x0013  const-wide/16 v4, 0
0x0017  if-nez v2, +4                 ← snsId == 0 ⇒ return false
0x0019  const/4 v2, 1
0x001f  return v2
```

⇒ **`isDieItem() = ((localFlag & 2) != 0) && (snsId != 0)`**，**不是** `localFlag<=0`。

这条订正的意义：`l5.o` 写库时**不设** `field_localFlag`（默认 0）⇒ `0 & 2 == 0` ⇒ **注入对象不会被判死**。
若按旧结论（`localFlag<=0` 即 die）推导，会得出「注入对象必被过滤、方案 A 不可能成功」——
与 R27 真机 L3 已 PASS 的事实直接冲突。**旧结论错、子代理对，以本文为准。**

> 📌 `_r27_plan_a.md:101` 当时就把它标成「未定项」，后来靠真机 L3 绕过去了，没回头订正静态结论。
> 这次一并钉死。

### ⚠️ 关于取证工具的两个已知缺陷（防止下次踩）

1. `tools/_r27_disasm.py` 在 `move-*` / `22c` / `35c` 处解码有误，会 desync。
   本次用 `_cache/_r30_disasm.py`（子代理修的）。
2. **但 `_r30_disasm.py` 的 `22b`（`and-int/lit8` 族）寄存器标注仍然错**：
   实测输出 `and-int/lit8 v2, v0, 2`，真实指令是 `and-int/lit8 v2, v2, 2`（vB 位被误当成 v0）。
   ⇒ **偏移（`0x000b`）可信，寄存器编号不可全信**。判定语义时按 opcode 语义 + 上下文推，
   别逐字照抄寄存器。

---

## 附：第二轮订正（子代理补充 + 主会话态度标注）

子代理追加了一条**对结论无害但更精确**的修正：

| 项 | 原写法（本文 §6/§7） | 订正 |
|---|---|---|
| 注入对象会不会被判死 | 「相册与时间线共用 `isDieItem` 过滤，但 `localFlag` 默认 0 故不被过滤」 | **相册压根不走 `isDieItem`**。`isDieItem` 只属于时间线消息层（`lh3/x1.b` / `mh3/a0.a` 调用，全在 `sns.ui.improve.*`）。相册写库路径 `l5.h` → `l5.o` **无 isDieItem 门槛**，只过 `l5.h` 内的 `checkSnsObjectValid`：**`Username` 非空 + `CreateTime > 0`** |

**结论不变（仍然可行，且门槛更低）**。对实现有两条正面影响：
1. 我们的 `ownerMatches` 本来就要求 `Username` 非空（空值一律 false ⇒ 跳过），天然满足 `checkSnsObjectValid` 的一半；
2. 另一半 `CreateTime > 0` 由"被删的老动态"天然满足（负值时间戳不存在）。

⚠️ **诚实标注**：`checkSnsObjectValid` 这条我**未能独立复核** —— 混淆名 `l5` 的全限定名没定位到
（`_r28fw_cls.py 'Ll5;'` 命中的是 `Lq54/ll5;`，改正则 `^com/tencent/mm/plugin/sns/.*l5;$` 零命中）。
从 `c3.N` 里也 grep 不到 `l5` 字样（写库调用点不在 `N` 内）。
⇒ 该条**采信子代理**，但它只影响"为什么不被过滤"的解释，不影响注入点与判据，
真机 L1（`list.add` 成功）→ L2（落库 `sourceType=2`）→ L3（界面可见）三层判据照旧能验证到底。

---

## §8 相册渲染读库查询链追溯（Q1/Q2/Q3 收口，含确凿 SQL WHERE）[DONE]

> 本节回答 team-lead 三个追问，并**修正了 §9.2 的一处旧结论**（`d2.x` 不是「排除清单含 0」，
> 而是 `sourceType in (64)`，见 8.1）。所有地址来自 `_cache/_r30_disasm.py`。
> 本节新增补修了解码器的 **22b（and-int/lit8 立即数）/ 22s（lit16 寄存器）/ 23x（aget/aput 第二单元）**
> 三处缺陷，此前 22b 的「立即数」实为源寄存器号，会导致掩码读错（见文末工具说明）。

### 8.1 Q1：填充 `qg3/i.d` 的查询链 + 确凿 SQL

数据装载链（lane1 已证，本节省复核）：

```
SnsAlbumUI → qg3/i(SnsAlbumAdapter).d:List<SnsInfo>
  装载类 qg3/k = SnsAlbumAdapterHelper（父类 com.tencent.mm.plugin.sns.ui.n）
  └─ b() = loadData（code=0x956bbc, 523 units）
       ├─ 0x003a f4.ia() → d2（SnsInfoStorage 单例）
       ├─ 0x0049 iget qg3/k->c:String（目标 userName；<init> 0x001a 由第2参赋值）
       ├─ 0x004b d2.H5(0, userName, isSelf, 0) → 基础 SQL    ← getUserMediaSql
       ├─ 0x004f d2.p6(limitSeq) → seqAvailable
       ├─ 0x0062 d2.Y2(limitSeq) → stringSeq/snsId 窗口片段（仅 seqAvailable 时拼接）
       ├─ 0x006d~0x0091 拼 ORDER BY（isSelf ? d2.q : d2.r）
       ├─ 0x0092 iget d2->d:k2 → 0x0095 k2.k(sql, null) → Cursor
       └─ 0x00ae 起逐行 new SnsInfo + convertFrom(Cursor) + ArrayList.add
  └─ c() = publicNotify（clear → 加分组头 → addAll → notifyDataSetChanged）→ qg3/i.d
```

**确凿 SQL 基础串（`d2.H5` = `getUserMediaSql`，code=0x3e0748, 67 units）：**

```
0x0009  const-string/jumbo 'select *,rowid from SnsInfo '
0x0016  const-string ' WHERE '
0x001b  sget-object d2->x            ← WHERE 条件来自静态字段 x
0x0036  const-string ' AND type in ( 1 , 15)'
```

即：`select *,rowid from SnsInfo WHERE <d2.x> AND type in ( 1 , 15)`

**`d2.x` 确凿内容（`d2.<clinit>` code=0x3e13d0 全程复核）：**

- `d2.x` 初值 null（0x0065），最终在 **0x0329 `P0(v0,v3)` → 0x032d `sput-object d2->x`** 被赋为
  `buildLimitString(list, 'sourceType')` 产物。
- `P0(List,String)`（code=0x3e07e0）：0x0007 `const/4 v2,0` → 0x0008 `Q0(v3,v4,v2)`；
  `Q0(List,String,Z)`（code=0x3e0810）第 3 参 notIn=**0(false)**，0x000e `if-eqz v6` 走 0x0025 `' in ('` 分支。
  ⇒ 产出 **`sourceType in (...)`**（不是 `not in`）。
- 喂给 `d2.x` 的集合是 `d2.E`（0x0307 `sget d2->E`），过滤条件 **0x031d `and-int/lit8 ..., 64`**：
  `(value & 64) != 0` 才保留（0x031f `if-eqz` 跳过）。
- `d2.E` = `[2, 4, 8, 16, 64, 128, 256]`（0x00c7~0x00e3，7 元素；0x00cb `valueOf{v11}` 其中 v11=4 来自
  0x0097 `const/4 v11,0x4`）。
- 仅 `64 & 64 = 64 ≠ 0` 通过，其余全滤掉。

⇒ **`d2.x` = `sourceType in ( 64 )`**

**最终相册查询 SQL（铁证）：**

```sql
select *,rowid from SnsInfo
WHERE sourceType in ( 64 )
  AND type in ( 1 , 15)
  [ AND ( stringSeq >= "<limitSeq>" 或 snsId >= <long> ) ]   -- 仅当 limitSeq 非空（d2.p6 + d2.Y2）
ORDER BY head desc [, createTime desc | snsId desc]
```

三问答案：

| 子问题 | 答案 | 证据 |
|---|---|---|
| ① 按 sourceType 过滤？ | **是**：`sourceType in (64)`（SQL 等值，非位测试） | H5 0x001b + clinit 0x023c/0x0307/0x031d/0x032d |
| ② 按 createTime 范围过滤？ | **否**：WHERE 无 createTime，createTime 仅在 ORDER BY（d2.q/d2.r） | H5 全文无 createTime 条件 const-string |
| ③ 按作者 username 过滤？ | **否**：userName 作为 H5 第 2 参传入但 H5 全文未引用（各参数寄存器 0x0009 起被 const-string 覆盖） | H5 67 units + loadData 0x0049/0x004b |

> ⚠️ ③ 的补充（诚实标注）：存在**另一条**构造器 `d2.f1`（`conUserWhere`，code=0x3e0c48）会拼
> `WHERE SnsInfo.userName="<user>" AND sourceType in (2)`（非 self 分支 0x001c/0x003d）或
> `WHERE sourceType in (4)`（self 分支 0x000b 用 d2.v）。**但相册装载（loadData）不走 f1，走 H5。**
> 所以「好友个人相册」这条 SQL 确实不含 username 过滤——单人范围靠 `sourceType in (64)` + `head desc`
> 排序 + adapter 侧分组实现（loadData 0x00d4~0x01a2 内存过滤只判 type∈{1,15}、TimeLine≠null、
> ContentObj≠null、ContentObj.h 非空，**同样不判 username**）。此反直觉结论供主会话留意。

### 8.2 Q2：为何相册看不到被删动态（结合真机事实）

真机事实：`sns_archive` 8 条（物理 DELETE 被拦、行已从 SnsInfo 删除）；
`sns_visible_src.fixed_orig IS NOT NULL` 4 条（sourceType 0→2 被补回）。

结合 8.1 的 `WHERE sourceType in (64) AND type in (1,15)`：

| 情况 | 判断 |
|---|---|
| **查询带 sourceType 过滤（8.1 已确凿）** | ① sourceType=0（被删标记）：0∉(64) → 排除 → 看不到 ✅ ② sourceType=2（补回值）：2∉(64) → **仍排除 → 补回 2 对相册无效** ⚠️ ③ 物理删除 8 条：无行 → 查不到 ✅ |
| 查询不带 sourceType 过滤 | sourceType=0/2 的行只要 type∈{1,15} 就会被查出 → 应该看得到，与真机矛盾 ⇒ 排除 |

**结论**：相册查询确凿带 `sourceType in (64)`，这是「看不到被删动态」的直接原因。
且「补回 sourceType=2」只命中**时间线**门槛（时间线用 d2.w = `sourceType in (2)`，见 f1 0x003d），
**对相册无效**——相册要的是 sourceType=64。

### 8.3 Q3：h3.N 注入还有没有必要

**(a) `jc5.f` 对象会不会写进 SnsInfo 表？**
- 会。`h3.N → l5.h(insertSnsList, code=0x381820) → 逐条 l5.o(replace, code=0x380db0)`。
- `l5.o` 0x0081 `isSourceExist` / 0x0087 `addSourceFlag(SnsInfo, v12)`：把 l5.h 第 2 参（continueID）
  作为 sourceType 标志 OR 进 `SnsInfo.sourceType`（`addSourceFlag` = `field_sourceType |= flag`，
  0x3dcdf0 0x0009 `or-int/2addr`）。⇒ 注入对象**确实落库**，但落库 sourceType = continueID，**未必是 64**。

**(b) 物理删除的 8 条，SnsInfo 表里还有没有行？**
- **没有**。物理 DELETE 的行已从 SnsInfo 删除（模块仅把 attr_buf 归档到 sns_archive 挡一道，删除照常执行）。
  这 8 条**读库读不回来**，只能「从归档 attr_buf 重建一行写回 SnsInfo」。

**结论（决定「只写库」还是「注入+写库」）：**

- 相册渲染 = 读库（8.1 + lane1 双证）。**注入 jc5.f 本身不产生 UI 效果**——只有注入对象经 l5.o 落库后、
  再被相册查询查出才可见。注入 ≈ 借道 l5.o 的「写库」，属绕路。
- 可见性门槛 = **SnsInfo.sourceType 命中 `sourceType in (64)` 且 type∈{1,15}**。
  - 那 4 条「sourceType=0 被标记、行还在」：把 sourceType 置 64 即可 → **纯写库一条路，无需 h3.N 注入**。
  - 那 8 条「物理删除、行已不在」：必须先写回一行（从归档 attr_buf 重建）且 sourceType 置 64 → **同样只靠写库**；
    h3.N 注入只是借 l5.o 的一种写库实现，非唯一路，且其 sourceType 由 continueID 决定、不可控，反而更差。
- ⇒ **建议：走「只写库一条路」，h3.N 注入无必要性。** 写库时把 sourceType 置为 64（等值，勿用 OR 叠加出 66/192
  等组合值，否则 `sourceType in (64)` 不命中），type 置 1 或 15。
- ⚠️ 待真机核一项：正常可见动态的 SnsInfo.sourceType 实际值是否**恰为 64**。若实测为组合值（如 66），说明
  `in (64)` 与位掩码语义有出入，需以真机读到的真实值对齐写库值，而不是照抄 64。

---

## §9 相册是「读库」还是「读响应」—— 追到 SQL 了（2026-10-27 21:2x 主会话）

这条**动摇**了本文 §7「注入可行」的必要性前提。

### 9.1 装载链（lane1 交叉印证 + 主会话复核）

```
SnsAlbumUI.dealContentView
  └─ new qg3/i (SnsAlbumAdapter) → setAdapter        ← 父类 androidx/recyclerview/widget/w1
       └─ d:Ljava/util/List;  元素类型 = com.tencent.mm.plugin.sns.storage.SnsInfo
          （onBindViewHolder 0x0085 invoke SnsInfo.getHead；h0 0x003f iget field_snsId:J）
装载：qg3/k = SnsAlbumAdapterHelper
  └─ b() = loadData（code=0x956bbc）
       ├─ 0x004b invoke-virtual Lcom/tencent/mm/plugin/sns/storage/d2;->H5  ← getUserMediaSql
       └─ c() = publicNotify（clear → addAll → notifyDataSetChanged）
```

⇒ **相册渲染吃的是 `SnsInfo` 存储实体（读库）**，不经过 `oh3/p`(ImproveSnsInfo) 模型层。
这与主时间线（吃响应内存对象 `zb5.f` → `oh3/p`）是**两套通路**。

### 9.2 那条 SQL（`d2.H5` = `getUserMediaSql`，code=0x3e0748）

```
0x0009  const-string/jumbo 'select *,rowid from SnsInfo '
0x0016  const-string ' WHERE '
0x001b  sget-object Lcom/tencent/mm/plugin/sns/storage/d2;->x:Ljava/lang/String;   ← WHERE 条件来自静态字段 x
0x0036  const-string ' AND type in ( 1 , 15)'
```

即：`select *,rowid from SnsInfo WHERE <d2.x> AND type in (1,15)`

`d2.x` 在 `<clinit>`（code=0x3e13d0，859 units）里赋值，其中：
- `0x023c const-string/jumbo 'sourceType'` → `0x023f invoke-static d2.P0` → `0x0243 sput-object d2.w`
- `d2.P0` = `buildLimitString(List,String)` → `Q0(list, key, 0)`，按排除清单拼条件
- 排除清单 `d2.E` 在 `0x0064~0x00e3` 初始化，含 `0,1,2,3,4,5,6,7,8,9,16,32,64,128,256,512,2048…`

### 9.3 结论与处置

| 判断 | 依据 |
|---|---|
| 相册**不按 createTime 做「三天可见」本地过滤**，靠 `type in (1,15)` + `d2.x` | §9.2 |
| **很可能按 `sourceType` 过滤**（`d2.x` 里显式拼了 sourceType 条件，排除清单里含 0） | §9.2 |
| ⇒ 若确实拦 `sourceType=0`，则**方案 B 补成 2 之后相册本来就该显示**，`h3.N` 注入是绕路 | 推论 |
| ⇒ `HookEntry.kt` 的 `SnsAlbumMerge.install` **已注释停用** | 代码 |

**为什么不去静态追到底**：`<clinit>` 859 units、`Q0` 多层分发、`d2.E` 是运行时 List，
继续追的边际成本已超过直接真机看一眼。⇒ 改走真机判据（见 §9.4）。

### 9.4 真机判据（决定 6.2 走哪条路）

| 观察 | 结论 | 下一步 |
|---|---|---|
| 进相册首屏**能看到**那 4 条已补位的 | 方案 B 已覆盖相册 | **6.2 作废**，`SnsAlbumMerge` 永久停用（或删） |
| 首屏**看不到**，但往下翻能看到 | 查询有窗口但不受 sourceType 影响 | 6.2 降级为"只是分页位置问题"，无需注入 |
| **完全看不到** | 查询确实拦 `sourceType`（或另有过滤） | 打开 `SnsAlbumMerge`，按 L1/L2/L3 验注入 |

⚠️ 提醒：那 8 条**物理 DELETE 归档**的动态如果 `SnsInfo` 表里已无行，**任何"读库"方案都看不到它们**。
那种情况只有"从归档表 `attr_buf` 重建行"（写库）这一条路 —— 而 `h3.N` 注入恰好也能达到"写库"的效果
（`l5.o` upsert），所以**注入的真实价值可能只剩这一种场景**，不是常规可见性。
