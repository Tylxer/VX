# R86b 态③ B6 复刻：可行性裁决与接入链路设计

> 任务性质：**只出设计与裁决，不落码、不改源码、不装机**。本次为降档执行（Pro 余额不足），按最高标准做 Hook 链路推理；dex 调用逐条核。
> 输入：R86 锁源铁证、v2 自审修正、R79 §4.3/4.4/4.6、R82 §Q4.1/§Q5、R10 CdnLogic native 结论、我方源码三份。本轮另用 `_r35_sns_dis.py`/`_r35_adnd_dex.py` 对 `_cache/dex/classes7.dex` **只读反汇编复核**了 B6 / a3 / g6 / j0.C2 / m1.m / mq/k.B8 / Ltj/i / tj/k。
> 标注纪律：【真机坐实】/【源码确定·dex】/【推断·高/中/低】；dex 有字符串 ≠ 业务调用；「A 推 B」只认 invoke 地址递增或真机时间戳，否则标假设；零命中如实记；禁止把期望写成证据。
> 核心命题边界：**「checkExist 命中 → 建新记录 → 对端清晰」在 R79 §4.6 是未验证假设，本裁决全程不把它当已证事实推进。**

## 章节索引（全部完成）
- Q1 可行性裁决（逐 dex 调用核 + go/no-go） → `[DONE Q1]`
- Q2 接入点 → `[DONE Q2]`
- Q3 xml 写入 → `[DONE Q3]`
- Q4 判据 → `[DONE Q4]`
- Q5 风险与回退 → `[DONE Q5]`
- 文末三件套（裁决 / 最小步 / 替代路线） → `[DONE ALL]`

---

## Q1 可行性裁决：脱离原生 B6 入口主动重演，逐 dex 调用核

### 1.0 一句话裁决（前置）

**【源码确定·dex + 真机坐实】GO——但 go 的是「机制可达、可反射、可构造、无 native/Activity 硬阻断」这一技术裁决，不是「复刻后必然清晰」这一业务结论。** 六个 dex 调用点本轮逐条反汇编，**全部为 Java 方法/接口/构造器，Xposed 可反射调用或可构造**；CdnLogic 的 native 不可 hook（R10 一手结论）**不在本链上**（本链止于 `mq/k.B8` → `ok0/m1.e`，均 Java）。真正的工程量与风险不在「能不能调」，而在「调完后 `qf4/j0.C2` 那段 ~0x230 字节的回调副作用能否最小充分复刻」——这一项标【需补 dex 取证】，构成最小步的验证目标。

### 1.1 逐调用点核（每个都给指令/签名/可反射性判定）

| # | 调用点 | dex 实证（本轮自算 classes7.dex） | Java 可反射/可构造 | 判定 |
|---|---|---|---|---|
| 1 | `b1.a3` | `B6 0x003e d3.O8()→b1`；`0x0077/0x00b2 invoke-virtual b1.a3(String,J)→r0`；方法体 `0x0000 new r0` → `0x0023 b1.w7(...)` 查库 → `0x002d r0.b(Cursor)` → `return r0`。**实例方法**，宿主实例来自 `d3.O8()`（`O8` 是 `invoke-static`，内部 `B8()` 单例 + `new b1(z84/z)`） | ✅ `XposedHelpers.callStaticMethod(d3,"O8")` → `callMethod(o8,"a3",talker,msgSvrId)`。**我方 `ForwardCdnFieldHook.arm():133-136` 已在线上代码这么调且真机命中**，非纸面推演 | 可达·已真机验证 |
| 2 | `g9.c` 三参 | `B6 0x00bb invoke-static g9.c(String,String,String)Map`；实参 `r0.u , "msg" , ""`（`0x00b6`/`0x00b8`）。`SCD-g9c` 探针 sig 同为 `(String,String,String)Map` | ✅ `callStaticMethod(g9,"c",u,"msg","")` as Map。同在 `arm():139-141` 真机验证 | 可达·已真机验证 |
| 3 | `tj/k` 构造器 + 字段 | `B6 0x0157 new-instance v6 <- Ltj/k;` `0x0159 invoke-direct Ltj/k.<init>()V` —— **无参构造**。`fields Ltj/k` 实证全为 instance 字段：`d:String`、`f:Ltj/i`、`field_mediaId/fileId/aesKey/filemd5/talker:String`、`field_fileType/priority:I`、`field_svr_signature:String`、`field_onlycheckexist/trysafecdn:Z`、`field_fullpath:String`（B6 全程**不写**它） | ✅ `XposedHelpers.newInstance(kCls)` + `setObjectField/setIntField/setBooleanField` 逐字段写。`StockCdnProbe.logTaskFields` 已反射只读同名字段成功 | 可达·可构造 |
| 4 | `mq/k.B8` | `B6 0x01c2-0x01ca d94/n0.c(Lnq/e0;)→check-cast Lmq/k`；`0x01cc invoke-virtual mq/k.B8(Ltj/k;)Z`。B8 方法体仅 3 指令：`invoke-static ok0/s2.e9()→ok0/m1` → `invoke-virtual m1.e(task)Z` → return（**纯 delegate，非 native**） | ✅ 两条取实例路：①照抄 `d94/n0.c(nq/e0.class)`（`invoke-static (Class)Ld94/m`，跨包调用 ⇒ public）；②更稳：hook `m1.e`/`B8` 取 `param.thisObject` 缓存宿主 m1 实例（本轮 SCD-B8/SCD-m1e 已挂）。然后 `callMethod(m1Inst,"e",task)` 等价 B8 | 可达·Java delegate |
| 5 | `tj/i` listener 接口 | `adnd calln Ltj/i;` 实证 **interface，仅 3 个虚方法**：`C2(String,int,Ltj/e,Ltj/f,Z)I`、`I(String,[B)[B`、`z(String,ByteArrayOutputStream)V`。`Lqf4/j0;` 与 `Lmodelimage/f2;` 均 implements 之且方法表同构 | ✅ `java.lang.reflect.Proxy.newProxyInstance(宿主cl, arrayOf(ltjICls), handler)` 自建实现类；三方法全在 InvocationHandler 里分发。**不依赖 Activity**（对照 `qf4/j0.<init>(MsgRetransmitUI,h9,II,String×4,I,r0)` 强依赖 Activity——这正是 R80「挂错回调」的根源，Proxy 方案直接绕开） | 可达·可自建 |
| 6 | `b1.j6` | `b1.j6(String,[B,I,J,Z,String,PString,PInt,PInt)J @0x2a74f8` 方法体两条指令：`const-string ''` → `invoke-virtual b1.g6(12 参)J` → return-wide。**实例方法**（同 `d3.O8()` 取实例）。g6 体实证：`0x0007 "SERVERID://"+J` → `0x001d "th_"` → `0x0023 b1.z3(...)` → `0x002d x.B([B)` 解码 byte[] 生成缩略图 → `0x0097 b1.r1()` → `0x00dd b1.a6(...)J` 落库 → `doNotify` → return | ✅ `callMethod(o8,"j6", 9 参)` 可反射；`PString/PInt` 用 `XposedHelpers.newInstance` 造。**无 Activity/Context 入参，纯 DB 写** | 调用可达；**参数来源待补取证（见 1.4）** |

### 1.2 异步回调如何安全接住（不依赖 Activity）

**【源码确定·dex + 推断·高】** `mq/k.B8` 同步返回 boolean 只是「入队」，`Ltj/i.C2` 是 native/m1 线程池稍后回调（`ok0/h1.run` → `m1.m` → `x0.u` → `CdnLogic.startC2CUpload` native → 回 `task.f.C2`）。因此**不能**在 `resendFile` 里「发完 B8 就地拿到新 localId 再 send」——回调在另一个线程、晚于当前栈。设计上必须做成**两段式**：

1. **arm 段（同步）**：在 `resendFile` 决定 RESEND_FILE 后、`FileSender.send` 之前，构造 checkExist task，`task.f = 自建 Ltj/i Proxy`，下发 B8；Proxy 与一个 `pending` 槽（key = `msgSvrId + talker + 目标 wxid`，值 = snap + 目标清单）绑定，**此段不调 j6、不发消息**。
2. **C2 段（异步回调）**：InvocationHandler 收到 `C2(String,int,Ltj/e,Ltj/f,Z)I`：先只读 `Ltj/f.field_exist_whencheck`（`j0.C2 0x0006` 同款字段）+ `field_filemd5`；命中才在**回调线程**里调 `b1.j6` 落新记录、拿 `param.result`/返回 localId；然后**在回调线程**驱动后续发送（新 localId 作为 FileSender 第 4 参）——整个两段不碰 Activity、不依赖 `MsgRetransmitUI` 存活。
3. **关联与降级**：多线程/多目标用 `ConcurrentHashMap<pendingKey,Snapshot>` + TTL；回调超时/未命中 ⇒ 清 pending、走现有 `resendFile` 原路径（模糊但**逐字节等于修复前**，不崩微信）。回调里所有宿主调用单独 `runCatching`，仿 `StockCdnProbe.guard` 纪律。

### 1.3 go / no-go 的硬边界（把期望与证据分开）

- **可定 go 的部分（证据足）**：六个 dex 调用全部 Java 可达；无 native 阻碍（R10）；无 Activity 硬依赖（Proxy 自建 listener）；源 xml 确有原图 CDN 参数（【真机坐实】E3 `g9c hasBig=true bigLen=194` + R86 旁证 `g9c md5Pre=ca47f83e`）。
- **不可定「必然清晰」的部分（证据不足）**：①「checkExist 命中 → j6 建新记录 → 对端清晰」是假设（R79 §4.6 明示；R86 E4 `j0C2` 两态零命中=\!命中，仅证我方当前没走这条链）；② `b1.j6` 的 9 个实参在 `qf4/j0.C2` 里如何由 `Ltj/f`+源 h9 拼出（本轮只反汇编到 `invoke-virtual/range j6 [v11..v21]`，**逐寄存器映射未闭合**）；③ j6 之后 j0.C2 还有 `o2(newLocalId)→r0.o/r0.i`、`z3(...,"th_",...)`、`h9.W0(...)` 等 ~0x230 字节副作用，哪些是「清晰」的必要条件未定。三项全标【需补 dex 取证】，是最小步要回答的问题，**不是 go 的否决项，而是 go 的范围约束**。

### 1.4 【需补 dex 取证】清单（取什么，逐项）

| 编号 | 取证目标 | 取什么 | 为何必须 |
|---|---|---|---|
| D1 | `qf4/j0.C2` 的 j6 实参映射 | 对 `0x0106-0x0130`、`0x0142-0x016b` 两段做 VERBOSE 全指令 dump（含 `move-*`），把 v11..v21 逐个还原成 `Ltj/f` 字段 / h9 字段 / PString·PInt | 决定自建 listener 能否只调 j6 就得新记录，还是必须连 h9 重建一起复刻 |
| D2 | j6 之后到「发消息」的最小充分尾段 | 反汇编 j0.C2 `0x016f-0x0230`：`g9.c` 二读、`o2(newLocalId)`、`z3("th_")`、`h9.W0`、`fa(h9)` 之后是否 `l1.a()`/入队 | 决定新记录 localId 之外是否还要重建/重发消息 |
| D3 | `m1.m` 对「无 field_fullpath 的 checkExist task」的分派 | 反汇编 `ok0/m1.m` `0x00a4-0x0129` 的 if 链，确认 `field_fullpath=null + field_fileId非空` 落到 `x0.u` 而非 download 分支 | 确认我方构造的 B6 task 会被正确下发，不静默走错分支 |
| D4 | `ok0/x0.u` 对 `field_filemd5` 的处置（复核 R82） | 已由 R82 Z.3 证「不读 filemd5、request md5 由 mars 重算」→ 本链只需填 task 首值，不必指望改引擎 | 防实现 lane 走回 R80(a) 改 fileId 头的死路 |

> 裁决口径：**D1/D2 不闭合 ≠ no-go**，而是把 go 限制在「最小步只验 H1+H2+只读 C2 观测，不下发 j6、不改发送」。若 D1/D2 取证后判定 j0.C2 副作用不可最小复刻，再翻 no-go（见 Q5 回退）。

[DONE Q1]

---

## Q2 接入点：挂哪个我方方法、什么时机、如何与 resendFile / FileSender.send 共存

### 2.1 接入点选择（唯一推荐）

**【源码确定·我方代码】** 挂点 = `ForceForward.resendFile`（`ForceForward.kt:2696`）内部、`FileSender.send` 调用（`ForceForward.kt:2741`）**之前的 RESEND_FILE 分支**；更精确地说，是在 `send()`（`:2017-2025`）已判定 `strategy==RESEND_FILE` 之后、`resendFile` 完成 P3 快照（`:2727-2740`）之后、调用 `FileSender.send`（`:2741`）之前插入一个**开关闸门的 B6 复刻分流**。理由：

- 该处是「已决定降级重发、且已拿到 `snap`（含 `imgLocalId`/`msgSvrId`/`sourceChat`/`srcBigImgPath`）与 target」的**唯一汇合点**——H1 需要的 `b1.a3(talker,msgSvrId)` 两个入参在此齐备，无需再往上翻 UI。
- 不挂 `MsgRetransmitUI.B6` 本身（用户实测该场景原生转发按钮被屏蔽、B6 不会被触发，挂了也白挂）；不挂 `FileSender.send` 内部（那里已经把 q1 三层写死，插进去会破坏闭环）。

### 2.2 与 resendFile / FileSender.send 共存（两条路径严格互斥）

| 维度 | OFF 路径（现状，默认） | ON 路径（B6 复刻） |
|---|---|---|
| 触发 | `resendFile` 照旧 `FileSender.send(kind,target,snap.localPath,snap.imgLocalId,snap,bigImgPath)` | 同一位置先 `B6Armer.arm(snap,target)` → 构造 checkExist task + 自建 Proxy listener + 下发 B8 → **本函数不再同步调 FileSender.send**，返回「已排队」交由 C2 回调驱动 |
| 发送驱动 | 当前调用栈同步 | 异步：C2 命中 → 回调线程内调 `FileSender.send(kind,target,snap.localPath, newLocalId, snap, null)`（第 4 参换成新 localId、第 6 参 P3 bigImgPath 传 null，见 Q3） |
| R80a `ForwardCdnFieldHook.arm` | 保持原样（FileSender.kt:158-162） | **跳过**：ON 路径不调 arm，避免其 m1.e before 把 fileId/aesKey 注进检查 task 造成双重身份污染；B6 task 的 fileId/aesKey 在构造时一次性写全 |
| P3 字节落盘（`FORWARD_P3_BIGIMG`） | 原样（可叠加） | ON 路径第 6 参传 null ⇒ `FileSender.kt:221-226` 整块跳过，不让 P3 缩略图路径覆盖新记录的 36B 占位路径 |

**三层闭环保护（硬约束）**：`q1.i/g/h/j/k/l/n/p/o`（`FileSender.kt:181-189`）、`q1.f(m)`（`:227`）、`q1.h=13`（`:230`）、`g6(l1)`+`l1.a()`（`:241-249`）**逐字节不动**。ON 路径唯一改的是 `m.a` 的**取值来源**（源 localId → j6 新 localId），而 `m.a` 的写入动作本身仍是 `FileSender.kt:199-201` 那一行——结构上仍是同一条 `m.a → q1.f → q1.h → g6/l1.a` 链，不是新发明的发送器。

### 2.3 开关设计（默认关 + 唯一自变量）

- **新增配置键** `FORWARD_B6_REIMPL`（`Kv.getBool`），**默认 false**，与 `FORWARD_NATIVE`（`ForceForward.kt:2046` 默认关的先例）同规格。关时 ON 路径整段不可达，`resendFile` 行为与引入前逐字节相同。
- **运行时反向对照开关**：复用广播探针范式（`StockCdnProbe.turn`/`ForceForward` 的 `op=forward sub=on|off`），增加 `sub=b6on|b6off`，只切这一个键，**其余开关（`FORWARD_P3_BIGIMG`、`FORWARD_NATIVE`、所有本地判据阈值）本轮一律冻结**。这样 A/B 的唯一自变量 = B6 复刻是否触发，符合 R86 锁源铁证「唯一自变量 = 源身份」的精神。
- **幂等/防串味**：hook 安装沿用 `guard`+`installed` 幂等（`StockCdnProbe:79-82,714-722`）；pending 用 `msgSvrId+talker+target` 复合 key + TTL，命中即清（仿 `ForwardCdnFieldHook.inject():106-108` 一次性消费），禁止跨目标串用新 localId。
- **不碰的三层**：`m.a/q1.f/q1.h/q1.j` 的写入代码一行不改；A/B 差异只体现在 `m.a` 的**值**与是否走 ON 分支，可在日志里以 `FORWARD B6 arm/hit/id` 前缀单行区分，不新增内部计数器作为判据。

### 2.4 时机顺序（时序图，文字版）

```
长按→选目标→策略=RESEND_FILE
  └─ resendFile(snap,target)
       ├─ [OFF] FileSender.send(..., snap.imgLocalId, ..., bigImgPath)   // 现状，逐字节不变
       └─ [ON]  B6Armer.arm(snap,target)
                  ├─ b1 = d3.O8()                      // 1 次/进程可缓存
                  ├─ r0 = b1.a3(snap.sourceChat, snap.msgSvrId)   // H1 读源 xml
                  ├─ map = g9.c(r0.u,"msg","")         // 三参，取 cdnbigimgurl/aeskey/md5
                  ├─ task = new tj/k; 写 field_*; task.f = Proxy(Ltj/i)
                  ├─ B8(task)  // 下发 checkExist，同步返回「已入队」
                  └─ return true（不在此处 send）
       ...稍后，另一线程...
       Proxy.C2(..., Ltj/f, ...)
          ├─ 只读 field_exist_whencheck / field_filemd5 判命中
          ├─ [命中] newLocalId = b1.j6(...)            // 待 D1/D2 闭合后启用
          └─ FileSender.send(..., newLocalId, ..., null)  // 第4参换、第6参null
```

> 【推断·中】C2 回调所在线程是否为可发起 `l1.a()`（含 `modelbase/p1.g(cgi)`）的线程未真机验证；若 native 在特殊线程回调，需在 C2 内 `Handler(post)` 转到主线程/工作线程再 send——该项列入真机验证清单，不影响本轮 go 裁决（降级路径不依赖它）。

[DONE Q2]

---

## Q3 xml 写入：新 localId 如何进入我方发出的消息，证据够不够

### 3.1 写入桥（唯一通道，无需新增参数/自己写 DB）

**【源码确定·dex + 真机坐实】** 桥就是现成的 `imgLocalId` 第 4 参，链路逐跳可自证：

```
FileSender.send(..., imgLocalId, ...)           // ForceForward.kt:2741 第4参
  → m.a = imgLocalId                            // FileSender.kt:199-201（R73 已从 -1 改 >0）
  → q1.f(m) → q1.a() → l1.a()                  // FileSender.kt:227/233/249
  → j2.<init>(...Lhl0/m;...)                    // 19 参构造器，本轮 dex 实证其内部
      0x012c invoke-virtual b1.o2(Ljava/lang/Long;)Lcom/tencent/mm/modelimage/r0;
  → r0 = 该 localId 对应的 ImgInfo2 新记录
  → j2.O(r0): field_fullpath=z3(r0.e)=36B 占位 + fileId/aesKey 来自 r0.u 原图 CDN 参数（R79 §1.1 步⑤）
  → f2.C2 回包重建 <msg><img ... cdnbigimgurl=...>（R79 §1.1 步⑥ / R82 Q4.2）
  → 对端凭 URL 从 CDN 拉原图 ⇒ 清晰（假设，待真机）
```

- **本轮新增 dex 实证**：`j2` 有 6 个 `<init>` 重载，**带 `Lhl0/m;` 形参的那一个**（即我方 `q1.f(m)` 最终命中的 19 参构造器）在 `0x012c` 确有 `invoke-virtual b1.o2(Long)→r0`；`0x0130 d3.O8()`、`0x0134 r0.j` 紧随其后。⇒ `m.a>0 → o2(m.a)` 的桥在本轮独立复核成立（与 R79 §4.2、R73 源码注释一致）。
- **j6 新记录可拿 localId**：`b1.j6` 体 → `g6` 体 `0x00dd b1.a6(...)J` 落库、`0x00ea doNotify`、`return-wide`（本轮反汇编），即 j6 返回值就是新记录 localId（stock 样本 884519，R79 真机 `:33/:56`）。

### 3.2 第 4 参 imgLocalId 与新 localId 的关系（互斥，不叠加）

| 项 | 现状 imgLocalId（第 4 参） | B6 复刻 newLocalId（ON 路径第 4 参） |
|---|---|---|
| 来源 | `snap.imgLocalId` = 源坏图 216b 的**旧** ImgInfo2 localId（stock 样本 883190） | C2 命中后 `b1.j6` 返回的**新**记录 localId（stock 样本 884519） |
| 记录内容 | `r0.e`=36B 占位，但**未经历 checkExist 秒传建新记录**（R86 E4 零命中＝我方根本没触发） | 由 stock B6 同款 `g6` 构造：`SERVERID://` + `th_` 占位 + `a6` 落库（j6/g6 dex 实证） |
| 用途 | R73 修成 >0 让 `o2(源 localId)` 挂上源记录——方向对但**挂的是旧记录** | 替换旧值，让 `o2(newLocalId)` 挂上新记录；**第 4 参仍是同一个桥**，不新增第 7 参、不在我方直接写 ImgInfo2/消息表 |
| 共存 | OFF 路径照旧用它 | ON 路径用它作回调后 send 的实值；两值**互斥**，never 同时传入 |

> 关键澄清（防止 R80a 死灰复燃）：newLocalId **不等于**「把 `field_fileId` 注进旧 task」。R80(a) 改的是上传 task 的头（已被 R81/R82 双证伪）；这里改的是**挂到 j2 的 ImgInfo2 记录身份**——作用层级不同，是 R79 §4.2 裁决的正确落点。

### 3.3 dex 证据够不够（分层，不许把期望写成证据）

| 子问题 | 证据 | 结论 |
|---|---|---|
| 新 localId 能否写进发出消息（桥） | ✅ 本轮 dex 实证 `o2(Long)→r0` 在命中的 j2 构造器内 + R79 §4.2 + R73 源码 | **够**。写入机制不是瓶颈 |
| 桥写入点是否需改源码 | `FileSender.kt:199-201` 已是单次写入，ON 路径只换实参值 | **够**，零改动 |
| j6 新记录的 `r0.u` 是否真带原图 `cdnbigimgurl` | g6 体本轮反汇编到 `r0.h/r0.g=THUMBNAIL_DIRPATH://th_/r0.n/r0.D` 等写，**未见 `r0.u` 写入**；原图 URL 究竟存哪条字段未闭 | **不够 → D1/D2 补取证**。不能假设新记录 u 自带 URL |
| 重建消息的 URL 来自「新记录 xml」还是「C2CUpload 回包」 | R79 §1.1 步⑥ `f2.C2` 拼 `<msg><img cdnbigimgurl=...>`，但 URL 的取值源未逐字段反汇编 | **不够 → D2**。若来自回包，则新记录 u 是否带 URL 不关键；若来自记录，则必须确认 j6 写全了 URL |
| `newLocalId > 0` 闸门 | `FileSender.kt:199` `imgLocalId>0 ?: -1` | **够**：ON 路径回调拿到 id 必须先判 >0，否则按 -1 安全降级（写不进桥，退回模糊但不崩） |

### 3.4 结论

**写入桥 dex 证据足够、无需新增取证即可确定设计；但「新记录是否携带原图 URL、URL 从哪进重建消息」证据不足，必须补 D1/D2 才能决定 J6 之后要不要复刻 j0.C2 的 `z3/o2/W0` 尾段。** 本轮把「换第 4 参即可写入」定为设计结论，把「换完就清晰」严格留在假设区。

[DONE Q3]

---

## Q4 判据：互斥真机判据 + 反向对照，本轮可定 vs 真机才可定

### 4.1 互斥真机判据（行互斥，不并列；第一列永远是用户可见事实）

> 主线：**同一条源图消息（216b，`INP j2init ori=.../216b...jpg` + `m.a=883190` 锁行，R86 已坐实）**，目标固定 filehelper，单一变量 = `FORWARD_B6_REIMPL` 开关。

| # | 开关 | 对端肉眼 | 我方 listener 观测（自建 Proxy C2 日志） | 裁决（互斥） |
|---|---|---|---|---|
| A | **OFF** | **模糊** | 未触发 checkExist（与 R86 E4 `j0C2` 零命中一致） | ✅ 基线复现，与 R86 态②逐条相符；A/B 基点成立，继续做 B |
| B | **ON** | **清晰** | `field_exist_whencheck=true` + `field_filemd5` 原图前缀（非 0ef205）+ 拿到 `newLocalId>0` | ✅ 假设首次坐实 ⇒ (b) 成立，可进入全量实现 |
| C | ON | 模糊 | 命中原图 + 有 newLocalId（桥已走通） | 命中与清晰之间断链 ⇒ 查 D2：新记录是否带 URL / `f2.C2` 重建的 `<msg>` 是否带 `cdnbigimgurl`；**不得写成「差一点就成」** |
| D | ON | 模糊 | 未命中 / `md5Pre=0ef205` / j6 未拿到 id | checkExist 未命中或源 URL 失效（fileId `305f...` 过期）⇒ 回退，不硬推 |
| E | OFF | 清晰 | 未触发 | 说明 R73 `m.a>0` 已够，B6 复刻无必要 ⇒ 收尾，不为做而做 |

**零命中纪律**：R86 E4 `SCD2 j0C2` 两态零命中是事实（v2 §0 已逐行核），本文**不**把它误读成「checkExist 未命中原图」——它只能说明「我方当前没触发宿主 `qf4/j0.C2` 这条回调」。ON 路径用自建 Proxy listener 后，**宿主的 `qf4/j0.C2` 本来就不会被触发**，因此**不能拿 `SCD2 j0C2` 从零命中变 true 当作 ON 成功判据**——这是 R82 §Q5 P1 探针落点在本场景的缺陷，必须改为「我方 Proxy C2 内只读打印 `field_exist_whencheck`/`field_filemd5`/`newLocalId`」作为等价观测（不新建宿主 hook、不碰 param）。

### 4.2 反向对照（开关翻转，唯一自变量）

- 顺序固定：`OFF→跑一次（应模糊=A）→ON→跑一次（应清晰=B）→OFF→再跑一次（应回到模糊=A）`。同一源图、同一目标、同一设备、同一探针配置，中途 `logclear`。
- **互斥判据**只认两态对端肉眼「模糊 / 清晰」的翻转 + listener 观测的「未命中 / 命中」对应；出现 A→B→A 之外的组合（如 ON 不翻转、或 OFF 也清晰）即 hitting 判据失败，按 4.1 的 C/D/E 归位。
- 守卫：每态先确认样本身份闸门（`INP j2init ori=216b` + `m.a=883190`）命中，否则该次作废重来（防缓存复用/换源污染，R79 §3.4 已纠「不用 r0.u contains 216b」）。

### 4.3 本轮可定 / 必须真机才定 / 需补 dex 才定（三分）

| 类别 | 项目 | 依据 |
|---|---|---|
| **本轮可定（证据足）** | ① 两态同一源图、唯一自变量 = 喂上传链路的源身份；② 源 xml 带原图 CDN 参数（E3 `hasBig=true bigLen=194`、旁证 `md5Pre=ca47f83e`）；③ 决定量不在上传引擎字段（fileId/hitCache/filemd5/onlycheckexist 全被 R80a/R81/R82 证伪）；④ 六个 dex 调用全 Java 可达、无 native/Activity 硬阻断；⑤ `m.a→o2(newLocalId)` 写入桥 dex 实证；⑥ 我方当前 E4 零命中（没走 checkExist 链） | R86 铁证 + R79/R82 + 本轮反汇编，均【真机坐实】/【源码确定·dex】 |
| **必须真机才定** | ① ON 态 checkExist 是否真命中原图（`field_exist_whencheck`/`field_filemd5`）；② j6 是否落新记录且 `newLocalId>0`；③ 新记录/重建消息是否带 `cdnbigimgurl`；④ **对端清晰/模糊（终极判据）**；⑤ 反向对照 A→B→A 是否成立；⑥ C2 回调线程能否安全发起 send；⑦ fileId `305f...` 是否过期 | 全部跨方法跨进程，静态不可判；R79 §4.6 明标假设 |
| **需补 dex 才定（本轮不足）** | D1 j6 九参映射、D2 j0.C2 尾段最小充分集、D3 `m1.m` 无 fullpath 分支 | 本轮反汇编已定位到边界但未闭合；属静态可补，不依赖真机 |

[DONE Q4]

---

## Q5 风险与回退：R80 否决理由是否仍成立、最小步、替代路线

### 5.1 R80 两条否决理由逐条复核

| R80 理由 | 现在是否仍成立 | 依据与变化 |
|---|---|---|
| ①「挂错回调风险」 | **部分不再成立，但换成新风险** | R79 §Q2 已证两条 listener 路径不同（B6 checkExist=qf4/j0 vs j2.O 上传=f2），R78 挂 `qf4/j0.C2` 是「挂错对象」；本设计用 `Proxy(Ltj/i)` 自建 listener、不复用 `qf4/j0`（其构造器强依赖 `MsgRetransmitUI`），故「挂到宿主 Activity 回调」这一具体风险**已消除**。但新风险：Proxy C2 内**必须自己复刻 j6 + 必要的建记录副作用**，否则新记录建了却没写进消息（Q3/D2）——这不是「挂错」，是「副作用漏做」，仍需 D1/D2 闭合 |
| ②「工程量大」 | **仍然成立，且是本裁决的主要成本项** | j0.C2 实测约 0x230 字节、含 h9 重建/`j6` 双调/`o2`/`z3`/`W0`/入队；加两段式异步 + 多目标关联 + TTL + 开关互斥 + 防串味，远不止「调 6 个方法」。这也是本裁决把 go 限定在**最小步**而非全量实现的根因 |

### 5.2 其它新增风险（按严重度）

1. **回包身份不达**（高）：fileId `305f...` 可能过期 / 服务端无该原图 ⇒ checkExist 命中但回包 `md5Pre=0ef205` 或新建记录仍指缩略图（判据 D）。不可当作实现 bug 硬修，先查服务端存量。
2. **回调线程发起 send**（中）：C2 由 native/线程池回调，未必能直接 `l1.a()`；需真机验证，必要时 `Handler.post` 转线程（Q2.4 已标）。
3. **hook 互相污染**（中）：我方主动下发的 checkExist task 也过 `ok0/m1.e`，会触发 `ForwardCdnFieldHook`(若 OFF 路径 arm 未清)、`SCD-c2c`、`SCD-m1e` 探针；必须在 ON 路径跳过 arm（Q2.2）并给自建 task 打专属标记，避免探针把检查 task 误统计成上传 task。
4. **零命中误判**（中）：ON 路径不能拿 `SCD2 j0C2` 从零变 true 作成功判据（宿主 j0.C2 本就不触发，Q4.1 已纠 R82 P1 落点缺陷）。
5. **唯一自变量被污染**（中）：若同时开着 `FORWARD_P3_BIGIMG`，两态差异就不止一个开关 ⇒ 反向对照期间必须冻结其余开关（Q2.3）。

### 5.3 最小步版本（go 的第一步，只验一件事）

**只做「checkExist 是否能由我方主动下发并命中原图」的只读验证，不落 j6、不改发送、不碰 m.a。**

1. 复用已在线上代码真机验证的 `arm()` 前半段：`d3.O8().a3(snap.sourceChat, snap.msgSvrId) → r0.u → g9.c(u,"msg","")`，取 `.msg.img.$cdnbigimgurl/$aeskey/$md5`（本轮 dex 全复核）。
2. 照 B6 `0x0157-0x01cc` 构造 `tj/k`：`d="task_MsgRetransmitUI_2"`、`field_mediaId/field_fileId/field_aesKey/field_filemd5/field_talker/field_priority/field_svr_signature` 全填、**不写 `field_fullpath`**、`field_onlycheckexist` 按真机值 false；`task.f = Proxy(Ltj/i)`。
3. Proxy 的 `C2` **只打日志**：`field_exist_whencheck`、`field_filemd5` 前缀、`Ltj/f` 关键 String 字段（照 `StockCdnProbe.dumpStringFields` 只读范式）。**不调 j6、不调 send、不写任何宿主字段。**
4. 调 B8(task)（或等 `ok0/s2.e9()` 取 m1 后 `m1.e(task)`），开关默认关、广播开跑一次。
5. **唯一裁决点**：C2 是否出现 `field_exist_whencheck=true` 且 `field_filemd5` 为非 `0ef205` 的原图前缀。命中 ⇒ 才进入 D1/D2 + 第二步（接 j6 与 newLocalId）；不命中 ⇒ 按 Q4 判据 D 回退，全量复刻判 no-go。

> 该步只产生一次 checkExist 网络请求（无 DB 写、无消息发送），是「验证假设」而非「实现功能」，符合铁律 4「不许把期望写成证据」。

### 5.4 若最终 no-go 的替代路线（≠ 改 fullpath 指向 36B 占位）

**【推断·中；需独立 dex 取证】** 不重演 B6，改在**消息重建出口直接补 URL**：源消息 xml 的原图 `cdnbigimgurl/aeskey/md5` 我方**已能通过 `arm()` 的 `g9.c` 路径稳定读到**（真机验证），因此在 `modelimage/f2.C2 @0x2aa888` 重建 `<msg><img ...>` 后（或构造 q1 时），**只读拿到原图参数、把重建消息的 content 字段补成带 `cdnbigimgurl` 的串**，跳过 checkExist 建新记录这一步——直接对准 R82 §Q4.2「剩余问题在转发消息 xml 是否带原图 cdnbigimgurl」。代价：需另取证「f2.C2 的 `<msg>` 拼串写在哪条字段/哪个出参、何时落库、覆盖是否影响已发消息」，属独立设计任务；且**不复用 R80(a) 改上传 task 头**（已双证伪）、**不复用 v1「改 fullpath 指 36B 占位」**（因果倒置，v2 已推翻）、**不依赖本地造原图字节**（R85 已证判别器不能当字节源）。

---

## 文末三件套

- **① 总裁决（一句话）**：**GO——六个 dex 调用全 Java 可达、无 native/Activity 硬阻断，机制上可脱离原生 B6 主动重演；但 go 仅限「最小步验证 checkExist 是否命中原图」，「复刻后对端清晰」仍是 R79 §4.6 未验证假设，本轮不判其成立。**
- **② 若 go 的最小步版本（第一步只验证什么）**：只验「我方主动下发的 checkExist task 是否命中原图」——复用 `a3→g9.c` 取源 xml 参数，照 B6 构造**不带 `field_fullpath`** 的 `tj/k` + 自建 `Proxy(Ltj/i)`，调 `B8` 后在 Proxy 的 `C2` **只读**打 `field_exist_whencheck` 与 `field_filemd5`；**不调 j6、不改 m.a、不发消息**。命中才进第二步，不命中即回退。
- **③ 若 no-go 的替代路线**：放弃复刻 B6，改走 `f2.C2`/q1 消息重建出口，把已能稳定读到的源 xml 原图 `cdnbigimgurl/aeskey/md5` 直接补进发出消息的 content（对准 R82 的剩余问题）；不碰 fullpath 占位、不碰上传 task 头、不造本地字节，另起独立 dex 取证。

[DONE ALL]
