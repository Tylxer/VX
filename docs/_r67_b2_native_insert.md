# R67 · b2 方案（宿主 insert API `s4.I(r4)`）只读取证 + 落点设计

> 角色：r67-pro-beacon。本轮**未改任何 app/src/main 源码、未跑 gradle、未碰 adb/设备**；设备锁/构建锁归主会话。
> 宿主：微信 8.0.48 / versionCode 2588，权威源 `_cache/mm.apk`，解包 dex `_cache/dex/classes*.dex`（15 个）。
> 取证工具（只读，本轮新增 `_cache/_r67_probe.py`、`_cache/_r67_fields.py`，复用 `_cache/_r55_disasm.py` 的 Dex 解析器）：
> `s4`/`r4`/`jj.y1`/`ml.a`/`vj0.h8`/`vj0.e` 均在 `classes4.dex`（`po2.a` 在 `classes.dex`，`k74.i0`/`k74.f0` 在 `classes3.dex`，`vj0.h8` 在 `classes7.dex`）。
>
> 证据分档：`[已证实]` = dex 原文/行号 ｜ `[推断]` = 外推 ｜ `[需真机]` ｜ `[取证缺口]`。绝不把 [推断] 冒充 [已证实]。

---

## 0. 一句话结论（先看这个）

**b2 可行，且比 b1 更接近「微X 走宿主通知」的原生链**：`s4.I(r4)` 写 `rconversation` 成功后**自己调 `b(2, s4, username)`**（事件码 2=insert，dex 原文 code=0x4a5528 的 0038–003d），宿主随后自行重查（R66 已证 `insert → s4.b → p3.e → i3.s → P()=false → p3.f 重查 → count+1 → item[0]=新行`）。代价是**要反射构造完整的 `r4` 对象**（60 个字段里至少 7 个 setter），且 **`s4.I` 只写 `rconversation`、不写 `rcontact`** —— 标题来源的 `rcontact` 仍要我方 execSQL 直写（或另走联系人宿主 API，本轮不展开）。

**最关键的风险门已证伪**：R66 真机坐实 `a94.e.P(username)` 对陌生 username（`wx__c_`）返回 false 但**不拦**，重查照常发生、新行照常出现。b2 只多一条「构造 r4 字段对不对」的工程风险，无新的机制风险。

---

## 问题清单（逐条答，每条标证据档）

1. `s4.I(r4)` 的精确签名 + 是否内部发 `b(2,...)` 通知
2. `r4` 类的构造方式 + 各字段 setter 混淆名/值
3. `s4` 单例获取路径（`vj0.h8.b().v()` 各环节混淆名/返回类型）
4. 事务与并发：`s4.I` 是否开事务、与 `rcontact` 写会不会撕裂
5. b2 vs b1 三轴对比；b2 是否还需保留 `insertContactSql`
6. 判据设计（正反对照 / 变异验证 / 回滚 / 独立开关命名）
7. 风险与红线（事件总线副作用 / VersionGate / 字段缺失渲染异常）

---

（以下各节边取证边落盘）

---

## 1. `s4.I(r4)` 精确签名 + 是否内部发通知

### 1.1 签名 `[已证实]`

| 项 | 值 |
|---|---|
| 类 | `com.tencent.mm.storage.s4`（父类 `Lk74/y0;`，实现接口 `com.tencent.mm.storage.o8`） |
| 方法 | `J I(Lcom/tencent/mm/storage/r4;)` |
| 访问 | public（access=0x1） |
| dex:code | `classes4.dex` `0x4a5528`，registers=10, ins=2, insns=79 |

- 参数类型 `r4` 全限定名：**`com.tencent.mm.storage.r4`**（`Lcom/tencent/mm/storage/r4;`）。
- 返回类型：**`long`**（`J`）= 插入后的 rowid；失败返回 `-1`。
- 同类相关方法（同文件 s4 方法表，`classes4.dex`）：
  - `s4.w(r4) : J`（private，`0x4a55d8`）—— 插入前的 flag 重算助手
  - `s4.F(String) : String`（`0x4a5240`）—— 表名选择（普通→`"rconversation"`，漂流瓶→`"rbottleconversation"`）
  - `s4.h(r4, Z) : void`（`0x4a59d4`）—— message_fold 折叠置顶处理
  - `s4.d : Lk74/i0;` —— wcdb 连接（插入走 `s4.d.b(...)`）

### 1.2 方法体反汇编（`s4.I`，code=0x4a5528，去噪）`[已证实]`

```
0000: v0 = r4.N0()                    // jj.y1.N0() = getUsername()
0006: if (v0 == null) v0 = ""
000a: v1 = v0.length()
0013: if (v1 > 0) goto 001b            // 用户名非空才继续
0015: log "insert conversation failed, username empty"
001a: return -1                        // 空用户名直接失败
001b: v6 = s4.w(r4)                    // w(r4) = po2.a.c(flag, conversationTime)
001f: r4.b1(v6)                        // jj.y1.b1(long) = setFlag(v6)（重算并覆盖 field_flag）
0023: s4.h(r4, true)                   // message_fold 折叠处理（我方非折叠会话，无副作用）
0026: v1 = s4.F(username)              // → "rconversation"
002a: v6 = r4.convertTo()              // → ContentValues（只含 dirty 字段，见 §2.3）
0030: v5 = s4.d.b(v1, null, v6)        // k74.i0.b = wcdb insert(table, null, values) → rowid
0034: cmp rowid, -1
0036: if (rowid == -1) goto 0041       // 失败分支
0038: v0 = 2                           // ← 事件码 2 = insert
0039: v9 = r4.N0()                     // username
003d: k74.y0.b(2, this, v9)            // ← 发通知 b(2, s4, username)
004e: return rowid
0041: log "insert failed return -1, table:%s"
```

### 1.3 关键结论 `[已证实]`

1. **`s4.I` 写库成功后自己发 `b(2, s4, username)`**（dex 原文 0038–003d：`const/4 v0, #2` → `invoke-virtual {v8, v0, v8, v9}, k74.y0->b(ILk74/y0;Ljava/lang/Object;)V`）。事件码 **2 = insert**，payload = `r4.N0()`（username 字符串）。与 R65 §A2 的「事件码 2 = `s4.I(r4):J`（insert，4a5528）」完全吻合。
2. **发通知的时机在 insert 语句之后、方法返回之前**，同步发出（同一调用栈），无需我方额外补发 `s4.b` —— 这正是 b2 相对 b1 的核心价值。
3. **返回值 rowid**；插入失败（rowid==-1）走日志分支**不发通知**，返回 -1。所以「写库失败」与「通知未发」天然绑定，不会出现「库没写成功却通知了」的撕裂。
4. 空 username 直接返回 -1 且不发通知（0015–001a）。

### 1.4 对通知链的映射（结合 R65/R66，`[已证实]` + `[需真机]`）

`b2: s4.I(r4)` 触发链与 R66 微X 对照组同构：
```
s4.I → s4.d.b(insert rconversation) → b(2, s4, wxgrp_<hex>)
     → p3.e(event=2) → i3.s → a94.e.P(wxgrp_) [R66 已证 false 不拦]
     → p3.f 重查 cursor 1576→1577 → getCount +1 → item[0]=新行 → notifyDataSetChanged
     → getView → p3.F → q4.n(username,true) 缓存未命中 → 回查 rcontact.nickname → 标题正确
```
- 微X 对照组用的事件码是 **3**（`insertWithOnConflict` 直写 + 手动 `s4.b(3,...)`），b2 用 **2**（`s4.I` 自带）。R66 已证 `P()` 门不拦、重查照样发生，事件码 2/3 对「触发重查」无差别；但 `p3.e` 内对 2/3 的分支处理略有差异（见 R65 §A2），**是否影响最终渲染仍需真机确认**（标 `[需真机]`）。

---

## 2. `r4` 构造方式 + 字段 setter 映射

### 2.1 继承链与构造 `[已证实]`

```
com.tencent.mm.storage.r4  (classes4.dex, 2 实例字段: w1:Lh9, x1:I; 1 构造器)
  └─ ml.a                  (classes4.dex, 静态字段 v1:Lk74/e0 = 表 DBInfo)
      └─ jj.y1              (classes4.dex, 60 实例字段 = 全部 field_xxx; 构造器只初始化 dirty flags)
          └─ k74.f0         (classes3.dex, 字段 systemRowid:J)
```

- **构造方式：`new r4()`，无工厂方法** `[已证实]`。`r4.<init>()V`（`0x4a2dd4`）仅 `invoke-direct ml.a.<init>()V`；`ml.a.<init>()`（`0x6da0bc`）再链 `jj.y1.<init>()`（`0x6760d4`）→ `k74.f0.<init>()`。整条链空构造，**不预置任何字段值**。
- `ml.a.<init>(Ljava/lang/String;)`（`0x6da0d4`）存在一个带表名参数的构造器，但 `r4` 没用到；b2 用默认构造 `new r4()` 即可。

### 2.2 字段 → setter/getter/dirty-flag 全映射 `[已证实]`

字段全部定义在 **`jj.y1`**（60 个实例字段，`field_xxx` 命名，access=0x2=private）。每个字段对应：
- 一个 getter（无参）、一个 setter（单参）、一个 dirty flag（`Z` 布尔字段，控制 `convertTo()` 是否输出该列）。

> `convertTo()`（`jj.y1` code=0x6753c4）反汇编逐列确认：**只有 dirty flag 为 true 的字段才 `ContentValues.put`**。setter 同时 `iput` 字段值 + `iput-boolean #1` 对应 dirty flag（由 `_r67_fields.py` 对每个 setter 的字段访问逐一定位）。⇒ b2 只需调**需要的 setter**，其余列不进 insert（与 R54 §10.3「7 列 minimal insert」同构）。

**b2 需要的 7 列（= R54 §10.3 模板）+ 关键相邻字段**：

| DB 列 | 字段 (jj.y1) | 类型 | **setter（混淆名）** | getter | dirty flag |
|---|---|---|---|---|---|
| username | `field_username` | String | **`B1(String)`** | `N0()` | `e` |
| conversationTime | `field_conversationTime` | long | **`T0(long)`** | `j0()` | `j` |
| flag | `field_flag` | long | **`b1(long)`** | `r0()` | `r` |
| status | `field_status` | int | **`r1(int)`** | `I0()` | `h` |
| isSend | `field_isSend` | int | **`j1(int)`** | `x0()` | `i` |
| chatmode | `field_chatmode` | int | **`Q0(int)`** | `i0()` | `g` |
| msgType | `field_msgType` | **String** | **`n1(String)`** | `C0()` | `o` |
| content | `field_content` | String | `R0(String)` | `getContent()` | `n` |
| digest | `field_digest` | String | `V0(String)` | `l0()` | `s` |
| digestUser | `field_digestUser` | String | `W0(String)` | `m0()` | `t` |
| attrflag | `field_attrflag` | int | `P0(int)` | `g0()` | `w` |
| atCount | `field_atCount` | int | `O0(int)` | `e0()` | `y` |
| unReadCount | `field_unReadCount` | int | `x1(int)` | `K0()` | `f` |
| msgCount | `field_msgCount` | int | `l1(int)` | `B0()` | `d` |
| parentRef | `field_parentRef` | String | `o1(String)` | `D0()` | `v` |
| editingMsg | `field_editingMsg` | String | `X0(String)` | `o0()` | `x` |
| unReadMuteCount | `field_unReadMuteCount` | int | `A1(int)` | `M0()` | `A` |
| lastSeq | `field_lastSeq` | long | `k1(long)` | `y0()` | `B` |
| UnDeliverCount | `field_UnDeliverCount` | int | `v1(int)` | `J0()` | `C` |
| UnReadInvite | `field_UnReadInvite` | int | `z1(int)` | `L0()` | `D` |
| firstUnDeliverSeq | `field_firstUnDeliverSeq` | long | `Z0(long)` | `q0()` | `E` |
| editingQuoteMsgId | `field_editingQuoteMsgId` | long | `Y0(long)` | `p0()` | `F` |
| hasTodo | `field_hasTodo` | int | `f1(int)` | `v0()` | `G` |
| hbMarkRed | `field_hbMarkRed` | int | `i1(int)` | `w0()` | `H` |
| remitMarkRed | `field_remitMarkRed` | int | `p1(int)` | `F0()` | `I` |
| hasSpecialFollow | `field_hasSpecialFollow` | int | `c1(int)` | `s0()` | `J` |
| hasTrunc | `field_hasTrunc` | int | `g1(int)` | — | `u` |
| sightTime | `field_sightTime` | long | `q1(long)` | — | `z` |
| showTips | `field_showTips` | int | （无独立 setter，走 convertFrom） | `G0()` | `q` |
| customNotify | `field_customNotify` | String | （无独立 setter） | — | `p` |

### 2.3 b2 构造 r4 的字段与值（对齐 R54 §10.3 + R66 微X insert values）`[推断·需真机]`

```kotlin
val r4 = XposedHelpers.newInstance(r4Class)          // new r4()
XposedHelpers.callMethod(r4, "B1", username)          // field_username = "wxgrp_<16hex>"
XposedHelpers.callMethod(r4, "T0", timeMs)            // field_conversationTime = 组内最新消息时间（§6.3）
XposedHelpers.callMethod(r4, "b1", PIN_BASE + timeMs) // field_flag = 2^62 + time（s4.I 会重算，见 2.4）
XposedHelpers.callMethod(r4, "r1", 3)                 // field_status = 3
XposedHelpers.callMethod(r4, "j1", 1)                 // field_isSend = 1
XposedHelpers.callMethod(r4, "Q0", 1)                 // field_chatmode = 1
XposedHelpers.callMethod(r4, "n1", "1")               // field_msgType = "1"（⚠️ String，见 2.5）
// 其余列（content/digest/...）不 setter ⇒ convertTo() 不输出 ⇒ 用列默认值，与 R54 minimal insert 一致
val rowid = XposedHelpers.callMethod(s4, "I", r4) as Long   // 写库 + 自动 b(2) 通知
```

对照 R54 §10.3 模板：`flag=2^62+time` / `msgType=1` / `status=3` / `chatmode=1` / `isSend=1` / `conversationTime=time` / `username`，**逐项吻合**。

### 2.4 `s4.I` 会重算 flag `[已证实]`

- `s4.w(r4)`（`0x4a55d8`）= `po2.a.c(r0()=flag, j0()=conversationTime)`。
- `po2.a.c(JJ)J`（`classes.dex` `0x70aa8c`）反汇编：`(flag & 0xff00000000000000) | (time & 0x00ffffffffffffff)`。
- 即：**保留 flag 的高字节（含 2^62 置顶位），低 56 位取 conversationTime**。我方 `flag = 2^62 + time` 经此重算 = `2^62 | time`，值不变。
- 旁证：`po2.a.a(JIJ)J`（`0x70a9a0`）packed-switch 明确 `2^62 = 0x4000000000000000`（置顶位）、`2^60 = 0x1000000000000000`（次级位，折叠/静音用）。`0x4000000000000000` == R54 `PIN_BASE = 4611686018427387904L` ✓。
- 含义：**只要我方 setter 写入的 flag 高位含 2^62 位，`s4.I` 都会把 flag 规整成 `2^62 | time`**；即便我方漏写 flag，若 conversationTime 正确，也可让 `s4.I` 自己算出置顶 flag（但保险起见仍显式 setter b1）。

### 2.5 关键类型差异：msgType 是 String `[已证实]` + `[需真机]`

- `field_msgType : Ljava/lang/String;`（String），setter `n1(String)`，getter `C0()` 返回 String。
- R54 execSQL 写的是 `msgType=1`（**整数**字面量），7 列 minimal insert 真机成功。SQLite 亲和性会把两者归一（TEXT 列存成 `"1"`、INTEGER 列存成 `1`）。
- **b2 走 r4 路径必须写 `n1("1")`（字符串）**，不能写整数。`[需真机]`：验证 r4 路径写入后 `pragma table_info` 或 `stat` 回读 msgType 与 execSQL 路径一致（避免「渲染摘要/消息类型判错」）。
- 同理 `field_msgType` 的 `convertFrom(Cursor)` 用 `getAsString`，进一步佐证该列是 TEXT 亲和。

### 2.6 `r4` 自有字段与其余方法（插入路径无关）`[已证实]`

- `r4.w1 : Lh9`（消息对象引用）、`r4.x1 : I`：`G1(h9)` 会从消息对象回填 status/isSend/conversationTime/content/parentRef 等 —— **b2 无消息对象，不调 G1**，直接 setter 即可。
- `r4.F1()V`（`0x4a2dec`）是「重置为默认值」：`status=0, isSend=0, content="", msgType="0", unReadCount=0, ...`。注意它的默认 `status=0/isSend=0/msgType="0"` 与 R54 模板（3/1/"1"）不同 —— **不要依赖 F1() 的默认值**，显式 setter 覆盖。
- `r4.convertTo()`（`0x4a2d9c`）仅 `return super.convertTo()`，无额外列 ⇒ 列集完全由 jj.y1 的 dirty flag 决定。

---

## 3. `s4` 单例获取路径 `[已证实]`

R65 给的 `vj0.h8.b().v()` 成立，逐环节 dex 原文如下：

| 环节 | 混淆名 / 签名 | dex:code | 说明 |
|---|---|---|---|
| 单例持有类 | `Lvj0/h8;`（classes7.dex，静态字段 `b : Lvj0/h8;`，access=0x49=public static volatile） | — | 单例容器 |
| 单例实例 | `vj0.h8.d() : Lvj0/h8;`（static） | `0x901420` | 懒加载 + 双重检查，写字段 `b` |
| 取业务核心 | `vj0.h8.b() : Lvj0/e;`（static） | `0x9013d0` | 返回 `d().a`（`a : Lvj0/e;`） |
| 取会话存储 | `vj0.e.v() : Lcom/tencent/mm/storage/o8;` | `0x890800` | 返回 `s4` 实例（声明类型是接口 `o8`） |

`vj0.e.v()`（`0x890800`）内部链（反汇编）：
```
const-class oo2.d3
y70.k1.s(oo2.d3.class) → d80.a            // 服务定位器 getService
cast → plugin.messenger.foundation.b1
b1.F8() → com.tencent.mm.storage.s4      // ← 真实返回对象
```

**结论**：
- `o8`（`com.tencent.mm.storage.o8`，classes4.dex，access=0x601=public interface）是 `s4` 实现的接口；`vj0.e.v()` 声明返回 `o8`，**实际对象是 `s4`**（R65 已记「返回 o8 实为 s4」）。
- 反射链（module 侧，2 步即可，无需自己走 `oo2.d3`/`b1.F8`）：
```kotlin
val h8cls = XposedHelpers.findClass("vj0.h8", classLoader)
val vj0e  = XposedHelpers.callStaticMethod(h8cls, "b")   // → vj0.e（内部已走 d().a）
val s4obj = XposedHelpers.callMethod(vj0e, "v")          // → com.tencent.mm.storage.s4
```
- ⚠️ 注意 `vj0.h8` 里**字段 `b`（静态单例字段）与方法 `b()`（静态方法）同名**。要调的是**方法 `b()`**（返回 `vj0.e`），不是读字段 `b`（字段 `b` 是 `vj0.h8` 实例，调它没有 `.v()`）。`XposedHelpers.callStaticMethod(h8cls, "b")` 走的是方法。
- 后续调用 `I(r4)`：`s4` 是具体类，直接按 `com.tencent.mm.storage.s4` 找方法即可：
```kotlin
val s4cls  = XposedHelpers.findClass("com.tencent.mm.storage.s4", classLoader)
val r4cls  = XposedHelpers.findClass("com.tencent.mm.storage.r4", classLoader)
XposedHelpers.callMethod(s4obj, "I", r4obj)   // 或 findMethodExact(s4cls, "I", r4cls).invoke(s4obj, r4obj)
```

---

## 4. 事务与并发

### 4.1 `s4.I` 自己不开事务 `[已证实]`

`s4.I` 方法体（`0x4a5528`）**无任何 beginTransaction/endTransaction**，只有一次 `s4.d.b(table, null, cv)`（`k74.i0.b` = wcdb `insert`，单语句）。`k74.i0`（classes3.dex）是接口，`b(String,String,ContentValues):long` 对应 `insert()`，**单语句天然原子**。事务是调用方职责。

### 4.2 `s4.I` 只写 `rconversation`、不写 `rcontact` `[已证实]`

- `s4.I` 只 insert 到 `s4.F(username)` = `"rconversation"`（`0x4a5240`：非漂流瓶恒返回 "rconversation"）。
- 发的是 `b(2, s4, username)` **会话变更事件**，不触发联系人存储 `q4`（`"MicroMsg.ContactStorage"`）的缓存失效（R65 §B4′ 已证两套独立 storage）。
- ⇒ **b2 仍需单独写 `rcontact.nickname`**（标题来源），`s4.I` 不代劳。标题能否即时正确依赖 R65 §B4′ 的「q4.n 缓存未命中回查 rcontact」链路（新建组缓存无条目 → 回查 DB → 正确）。

### 4.3 与现有 `GroupConvStore.inTransaction`（:400）的关系 `[推断]`

现有 `sync()` 把「预删除 + rconversation 写 + rcontact 写」全包进一个 wcdb 事务（`inTransaction`，`GroupConvStore.kt:400`）。b2 引入 `s4.I` 后：

**问题**：`s4.I` 内部 insert 完成后**同步**发 `b(2)` 通知。若把 `s4.I` 放进外层事务，通知会在**事务提交前**发出 → 宿主重查（`p3.f` 用 `s4.d.g` 查同一个 wcdb 连接）可能读到未提交行（read-your-writes 可见）或读不到（不同连接）。行为不确定。

**建议**：**不要把 `s4.I` 放进 `inTransaction`**。改为两段：
1. 段一（事务内，execSQL）：`insertPreDelConv` + `insertPreDelContact` + `insertContactSql`（rcontact 先写、标题来源就位）。
2. 段二（事务外，同 worker 线程，紧随其后）：`s4.I(r4)`（写 rconversation + 自动 `b(2)` 通知）。

这样：通知发出时 rcontact 已提交、rconversation 由 `s4.I` 独立原子写，宿主重查能同时看到「新会话行 + 标题」。

### 4.4 撕裂分析 `[推断]`

| 失败点 | 后果 | 恢复 |
|---|---|---|
| `s4.I` 成功、`rcontact` 已提交 | 正常 | — |
| `rcontact` 成功、`s4.I` 失败（如 username 撞唯一索引/构造字段缺） | 孤儿 rcontact 行（有昵称无会话行） | 现有 `plan()` 对「contact 在、conv 缺」产 Update（`repairSql` 是 UPDATE 影响 0 行）**不自愈**，需 `op=gcs sub=clean` 全删重建（`GroupConvStore.kt:87` 已注「半行不自愈」） |
| 预删除成功、后续失败 | 该组行被删 | 下次 sync 重插（plan 对缺行产 Insert） |

- **缓解**：b2 模式下仍保留现有「先删后插」幂等套路（`insertPreDel*`），把 `s4.I` 失败当普通 FAIL 打日志（不吞原文），`clean` 兜底。
- **与现有 `AntiRevoke.wxDb()` 的关系**：现有 execSQL 用 `AntiRevoke.wxDb()` 拿 wcdb `SQLiteDatabase`；`s4.d` 是 `k74.i0` 连接封装。两者是否同一底层连接 `[取证缺口]`（dex 无法静态证明），但不影响上述「分段」设计 —— 段一用 `wxDb()` 直写、段二走 `s4.I` 自己拿它的 `s4.d`，天然规避「同一事务内混用两个入口」的问题。

### 4.5 并发安全 `[已证实]`

- 现有 `worker` 单后台线程串行（`GroupConvStore.kt:59`）保证我方不与宿主并发；`s4.I` 在同一 worker 线程内调用即可，无新增并发面。
- `s4.I` 是宿主自己的写库方法，宿主自身也用它，其内部的 `s4.d.b` 与通知顺序由宿主保证；我方只是复用，不改宿主并发语义。

---

## 5. b2 vs b1 三轴对比 + `insertContactSql` 去留

### 5.1 三轴对比

| 轴 | b1（execSQL 直写 + 反射补发 `s4.b`） | b2（走宿主 `s4.I(r4)`） |
|---|---|---|
| **像微X程度** | 中：微X 是 `insertWithOnConflict` 直写 + 手动 `s4.b(3)`，b1 结构几乎一致（直写 + 手动通知），只是我方用 execSQL、事件码可选 2/3 | **高**：`s4.I` 是宿主自己的 insert API，写库+通知都由宿主完成，通知时机/参数/事件码（2）与宿主原生新增会话完全一致，最接近「让宿主自己发通知」的初衷 |
| **工程量** | 低：复用现有 `insertConvSql`，只加一步反射 `s4.b(2/3, s4, username)` | 中高：需反射构造 `r4` 对象 + 7 个 setter + 单例链 `vj0.h8.b().v()` + `s4.I` 调用；还要处理 msgType String 差异、事务分段 |
| **风险** | 中：反射 `s4.b`（继承自 `k74.y0`）私有混淆方法 + 事件码语义要自己保证（2/3/5 选错可能触发错误分支） | 中：反射点更多（单例 + 构造 + 7 setter + I），但**通知语义由宿主保证**，不会选错事件码；字段缺失风险是新增项（见 §7.3） |
| **通知正确性** | 依赖我方选对事件码 + payload | **宿主保证**（`s4.I` 内部 `b(2, s4, username)`，写失败不发） |
| **版本漂移面** | 1 个类名（`s4`）+ 1 个方法（`b`）+ 事件码常量 | 3 个类（`vj0.h8`/`vj0.e`/`r4`+`jj.y1`）+ 8 个方法（`d`/`b`/`v`/7 setter + `I`） |

### 5.2 结论 `[设计裁决]`

- **首选 b2**（用户已拍板，且更原生），但**保留 b1 作为 b2 反射失败时的降级兜底**（同一条代码路径 `runCatching` 包住 b2，失败走 b1 直写 + 补发 `s4.b(3,...)`）。两者都是「触发宿主重查」，b2 失败不致命、降级 b1 后至少不劣于现状（b1 也失败则回到冷启可见）。
- **不建议 b1/b2 长期并存两套开关**：b2 是 b1 的「原生化」替换，做成功后可让 b1 代码路径仅作 fallback，不暴露第二个开关。

### 5.3 `insertContactSql`（rcontact）去留 `[设计裁决]`

**保留，且必须保留。** 理由（`[已证实]` + `[推断]`）：
1. `s4.I` **只写 `rconversation`、不写 `rcontact`**（§4.2 dex 原文）—— 标题来源的 `rcontact.nickname` 必须由我方单独写。
2. `rcontact` 没有「即时显示」的强需求：R65 §B4′ 已证新建组场景「q4.n 缓存未命中回查 rcontact」即可让标题正确，**不需要 rcontact 走宿主 API**；改名场景本来就要冷启（R54 §10.1 #5），走不走宿主 API 都不即时。
3. ⇒ **rcontact 继续 execSQL 直写（`insertContactSql` 原样保留），不引入 rcontact 的宿主 API**。这也把 b2 的反射面收敛到「会话表」一侧，不扩散到联系人存储（`q4`/`h4`），风险更小。
4. `[需真机]`：若真机验证发现「rcontact execSQL 直写后，宿主在某些路径（如全局搜索、通讯录）对 wxgrp_ 行有额外处理」，再考虑 rcontact 是否也要改走 `q4` 的 insert API（本轮不展开，留作后续）。

---

## 6. 判据设计（真机正反对照 / 变异 / 回滚 / 开关命名）

### 6.1 独立开关命名（对齐 `Keys` 规范）

在 `data/Keys.kt` 加一个键，`Prefs.ALL` 加一行（沿用 `GCONV_MODE` 同款「实验、默认关」语义）：

```kotlin
// Keys.kt
const val GCONV_NATIVE_INSERT = "chat.gconv_native_insert"

// Prefs.ALL（聊天域）
PrefSpec(Keys.GCONV_NATIVE_INSERT, PrefKind.SWITCH, "false", "聊天",
    "数据层分组行：新建走宿主原生插入 API（实验，默认关；切换后冷启微信生效）")
```

- **默认 false**：关着 = 现状 b1 直写路径（execSQL 直写 rconversation），零行为变化。
- 运行时 `enabled = GCONV_NATIVE_INSERT ∧ GCONV_MODE`；仅当两者都开才走 `s4.I`。
- 命名理由：`chat.gconv_*` 与现有 `chat.gconv_enabled` / `chat.gconv_avatar` 同域同前缀，`native_insert` 语义自明（「原生插入 API」），不与 R65 的探针开关 `chat.host_cache_probe` / 真调开关 `chat.host_cache_refresh` 撞名。

### 6.2 正反对照（真机，主会话可执行）`[需真机]`

前置：`gconv=1` + 冷启（现有 `wxgrp_` 行已落库但列表无新行，R54 §10.1 #1 基线）；`kvset chat.gconv_native_insert 1` + **冷启微信**（开关装机读一次）。

| 步 | 操作 | 判据（用户能看见的事实） |
|---|---|---|
| 1 | 关 b2（`native_insert=false`）+ 冷启 → UI 新建分组「R67A」 | 列表**不即时显示**（现状已知行为）；`dev.py q GCONVSTORE` 有 `insertConv` execSQL 日志、无 `nativeInsert` 日志 |
| 2 | 开 b2（`native_insert=true`）+ 冷启 → UI 新建分组「R67B」 | **不冷启**看列表：新行**即时出现**、标题显示「R67B」；`dev.py q GCONVSTORE` 有 `nativeInsert OK rowid=<n>`；HCR 探针（R66 那套 13/13，或复用）见 `s4.b event=2 user=wxgrp_<hex>` → `p3.f cursor+1` → `getCount +1` → `item[0]=wxgrp_<hex>` → `q4.n nick=R67B` |
| 3 | 反向：`native_insert=false` 再建组「R67C」 | 回到不显示（坐实「即时显示」是 b2 带来的，非其它变量） |
| 4 | 标题确认：b2 建的组，`op=xh sub=row` 看 View 类 | 宿主原生 `ConversationFolderItemView`、pitch 259px、标题文本=R67B（与 R54 §10.1 #8 一致） |
| 5 | 点击：真手点 b2 新行 | 进 `GroupDetailPage`（不落空白聊天页，R54 §10.1 #6 已有点击拦截） |
| 6 | 冷启兜底：若 b2 即时显示失败，冷启一次 | 行应出现（证明库写对了、只是通知没生效，与 R66 甲-1 同判据） |

### 6.3 变异验证点

| 变异 | 预期 | 判定 |
|---|---|---|
| `s4.I` 参数少一个字段（如不 setter `status`，只 setter username/T0/b1） | 仍插入成功，但 status 列走默认值（0），渲染可能异常（摘要/状态错） | 证明「字段缺失 → 渲染异常」风险真实存在，§7.3 的「必填字段清单」必须全 setter |
| `s4.I` 参数少 `flag`（不 setter b1） | `s4.w` 重算 flag = `(0 & 0xff..) | (time & 0x00ff..)` = time（**无置顶位**）→ 行落普通区 | 证明「flag 不 setter → 掉出置顶区」，坐实 R54 §5.1 置顶位重要性；setter b1 不能省 |
| 事件码观察：b2 自动发 `event=2`，对照 R66 微X 的 `event=3` | 两者都能触发 `p3.f` 重查 | 若 b2 的 event=2 路径在 `p3.e` 有不同分支（如只更新未读不换游标），则降级 b1 用 event=3 |
| msgType 传 `"1"`（String）vs 传整数 1（若反射强转 int 会抛异常） | String 正常、int 抛 `IllegalArgumentException`（参数类型不匹配） | 坐实 §2.5 的 String 类型结论 |

### 6.4 回滚路径

1. `kvset chat.gconv_native_insert false` + 冷启 → 回到 execSQL 直写（现状）。
2. 若 b2 写脏：`op=gcs sub=clean` 全删重建（现有幂等清理）+ 关 `native_insert`。
3. 代码层：b2 只新增一个独立方法（如 `GroupConvStore.nativeInsertConv()`），不动现有 `insertConvSql` 生成逻辑 ⇒ `git revert` 或注释开关即回滚，与 R54 §8.4「不删老路」同构。

### 6.5 落地形态（供主会话实现参考，本轮不改）`[设计裁决]`

在 `GroupConvStore.sync()` 的 `Op.Insert` 分支，把「写 rconversation」替换为：

```kotlin
is GroupConvPlan.Op.Insert -> {
    val u = GroupConvPlan.usernameOf(op.d.groupId)
    execLog(db, GroupConvPlan.deleteConvSql(u), "insertPreDelConv", u, out)
    execLog(db, GroupConvPlan.deleteContactSql(u), "insertPreDelContact", u, out)
    execLog(db, GroupConvPlan.insertContactSql(op.d), "insertContact", u, out)  // rcontact 仍 execSQL
    if (nativeInsertEnabled) {
        nativeInsertConv(op.d, u, out)          // b2: 构造 r4 + s4.I
    } else {
        execLog(db, GroupConvPlan.insertConvSql(op.d), "insertConv", u, out)   // b1/现状兜底
    }
}
```

其中 `nativeInsertConv` 内部：取单例链 → `new r4()` → 7 setter → `s4.I(r4)`，`runCatching` 兜底降级 b1；事务分段见 §4.3（rcontact 在事务内、`s4.I` 在事务外、同 worker 线程）。

---

## 7. 风险与红线

### 7.1 反射宿主私有混淆 API 的副作用 `[推断]`

- **事件总线副作用**：`s4.I` 发的 `b(2, s4, username)` 走 `k74/y0.b → k74/t0`（R65 §A2）投递给所有注册 observer。`s4.<init>`（`0x4a567c`）里注册了 `t4`（field g）与 `u4`（field h）两个 observer，还有 `p3.e` 等 UI observer。正常新增会话就发这个事件，副作用与宿主自己建会话等同，**不引入新副作用类别**；但 `wxgrp_` 是宿主不认识的 username，仍可能触发未知的 observer 分支（如 `p3.e` 内对非 chatroom username 的 unread 统计、置顶重排）。
- **置顶重排**：`s4.h(r4, true)`（`0x4a59d4`）在 message_fold 场景会改 `parentRef`；我方非折叠会话不触发。但 `s4.I` 写的是 flag=`2^62|time` 的置顶行，宿主收到 insert 事件后可能对置顶区重排（R54 §5.1 已证宿主会重置 2^62 位，长期 12→9）。**置顶位补偿写机制必须保留**（`repairSql`，§5.3）。
- **空聊天页**：点击 `wxgrp_` 行会进「对方还不是你的朋友」空页（R54 §10.1 #6），靠现有 `GroupedAdapter` 点击拦截（`GroupDetailPage.open`）兜住，b2 不改变点击落点。
- **空 username 分支**：`s4.I` 对空 username 直接返回 -1 不发通知（§1.3），我方 `usernameOf()` 恒产 `wxgrp_<16hex>` 非空，无此风险。

### 7.2 版本白名单 `[设计裁决]`

- 整套混淆名（`vj0.h8` / `vj0.e.v` / `r4`+`jj.y1` 7 个 setter / `s4.I` / 事件码 2）**绑 2588**。`VersionGate`（`core/VersionGate.kt`）已是版本白名单（`8.0.48` / `versionCode=2588` / 签名），版本不匹配 → `Reject` → 不打钩、不反射。
- b2 的反射调用**必须包在 VersionGate 通过的路径内**（与现有 hook 同门槛），且每个反射点 `runCatching`，失败只 WARN 不崩、降级 b1（§5.2）。
- 跨版本升级后必须重跑本探针反查混淆名（R65 §B6 同款纪律）。

### 7.3 字段缺失导致渲染异常 `[推断·需真机]`

- 必填字段（缺一个都可能渲染异常）：**username / conversationTime / flag / status / isSend / chatmode / msgType**（§2.2 表）。缺 status/isSend/chatmode 时列走默认值 0，渲染端（`ConversationFolderItemView` 的摘要/时间/未读）可能判错。
- 缺 flag → `s4.w` 重算成 `time`（无置顶位）→ 行掉普通区（§6.3 变异已列）。
- `msgType` 必须 String `"1"`，传 int 直接反射抛异常（§2.5）。
- 缓解：`nativeInsertConv` 内**集中一处构造 r4 + 7 setter**，加 `runCatching` + 打每个 setter 的失败；绝不散落各 setter。

### 7.4 数据红线（沿用 R54 §9）

- 所有删除仍走 `GroupConvPlan.deleteConvSql/deleteContactSql`（`substr(username,1,6)='wxgrp_'` + 精确 username 双保险），`s4.I` 只用于 insert，**绝不**把删除改走宿主 API（宿主 delete 会连带删消息/清空聊天记录）。
- `s4.I` 的 `b(2)` 通知不改库，但宿主收到 insert 事件后可能自行重排/清理 `rconversation`（R54 §9 风险 3）。保留 `op=gcs sub=clean` 兜底 + `stat` 的 `other` 恒定自证。

### 7.5 与其它模块共处 `[推断]`

- `s4.I` 写的 `wxgrp_` 行与微X `wx__c_` 行前缀不同，`ownWhere` 不会互删。两套伪会话同置顶区、排序交错，`stat` 已分打 `pinOurs`/`pinWx`（R54 §9 风险 9）。

---

## 8. 取证材料清单（可复核）

- 反汇编脚本（本轮新增，只读）：`_cache/_r67_probe.py`（class/hier/disasm）、`_cache/_r67_fields.py`（字段→方法映射）。
- dex 原文锚点（全部 classes4.dex，除非另注）：
  - `s4.I`：`0x4a5528`；`s4.w`：`0x4a55d8`；`s4.F`：`0x4a5240`；`s4.h`：`0x4a59d4`；`s4.R`：`0x4a4270`；`s4.<init>`：`0x4a567c`
  - `r4.<init>`：`0x4a2dd4`；`r4.convertTo`：`0x4a2d9c`；`r4.F1`：`0x4a2dec`；`r4.G1`：`0x4a2e50`；`r4.b1`：`0x4a3180`
  - `jj.y1`：60 实例字段 + `convertTo`：`0x6753c4` + `convertFrom(Cursor)`：`0x676838` + 全部 setter（`0x676168`~`0x677154`）
  - `po2.a.c`：`classes.dex` `0x70aa8c`；`po2.a.a`：`0x70a9a0`；`po2.a.b`：`0x70aa60`
  - `vj0.h8`：`classes7.dex`（`d()` `0x901420`、`b()` `0x9013d0`）；`vj0.e.v`：`0x890800`
  - `k74.i0`（wcdb 连接接口）：`classes3.dex`
- 前置结论引用：`docs/_r65_host_cache_refresh_probe.md`（s4.b 事件码、通知链）、`docs/_r54_gconv_design.md` §10.3（置顶模板）/§4（SQL）、`docs/conclusions/conv_list.md`（r4.field_username）。

---

## 9. 未决项（标档，留给真机/后续轮）

1. `[需真机]` b2 的 event=2 路径在 `p3.e` 内的分支处理是否与微X event=3 等价（§1.4）。
2. `[需真机]` msgType String `"1"` 与 execSQL 整数 `1` 在库内/渲染是否等价（§2.5）。
3. `[取证缺口]` `s4.d`（`k74.i0`）与 `AntiRevoke.wxDb()` 的底层 wcdb 连接是否同一对象（§4.4，不影响分段设计）。
4. `[需真机]` 缺字段（status/isSend/chatmode）的真实渲染表现（§7.3 变异验证）。
5. `[设计裁决]` rcontact 是否需改走宿主 `q4` 插入 API（本轮定：不改，§5.3）。

---

[DONE] R67 b2 方案只读取证 + 落点设计完成。

核心结论：
1. `s4.I(Lcom/tencent/mm/storage/r4;)J`（classes4.dex code=0x4a5528）写 rconversation 成功后**自己发 `b(2, s4, username)`**（事件码 2=insert，dex 原文 0038–003d），返回 rowid、失败返回 -1 且不发通知 —— b2 的「让宿主自己发通知」成立。
2. r4 构造 = `new r4()` + 7 个 setter（`B1/T0/b1/r1/j1/Q0/n1`，全在父类 jj.y1），字段按 dirty-flag 机制只写已 setter 的列；msgType 是 **String**（`n1("1")`），flag 会被 `s4.I` 经 `po2.a.c` 重算成 `2^62|time`（与 R54 模板一致）。
3. 单例链 `vj0.h8.b().v()`（`b()` static → `vj0.e` → `.v()` → s4 实例，声明类型 o8）成立，反射 2 步拿到 s4。
4. `s4.I` 不开事务、只写 rconversation；rcontact 仍 execSQL 直写；建议「rcontact 事务内先写 + s4.I 事务外后调」分段，规避通知在事务提交前发出的不确定。
5. b2 保留 `insertContactSql`（rcontact 必须我方写），rconversation 改走 s4.I，b1 降级兜底。
6. 开关建议 `chat.gconv_native_insert`（默认 false，与 `chat.gconv_enabled` 同域）。
