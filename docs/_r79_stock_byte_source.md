# R79 Pro 裁决：stock 微信自带转发 216b 坏图为清晰的字节来源 + R77/R78 落点重裁

> 档位=Pro（根因推理 + Hook 链路推导 + dex 反汇编取证）
> 本轮为**推翻性重裁**，非复读历史结论。带着 22:49 新真机证据 `docs/_r78_stockcdn_realdev_clean.log`（清后 93 行）裁决。
> 硬约束：只读；唯一写操作 = 本文档；code_off 由本代理自算 `_cache/dex/classes*.dex`；锚点区分【源码确定】【真机确定】【推断+置信度】。
> 三条纪律：① dex 有字符串 ≠ 业务调用；② "A 推 B" 时序标假设；③ 引用单测说明是否变异验证。

## 结论速览（最终写回）

- Q1：清晰字节在 **CDN 服务端**，经 checkExist 秒传（"support second !!"）到对端；本地 36B 是 ImgInfo2 记录占位非字节源。`B6 @0x62c98c` 从坏图 xml 解析 cdnbigimgurl/aeskey/md5 → `j0.C2 @0x84307c` 秒传命中建新记录 → `f2.C2 @0x2aa888` 重建带 cdnbigimgurl 的消息 → 对端拉原图。**推翻 R77/R78「r0.e 指大图字节」的立论前提**。
- Q2：选 (b) 两条路径不同 listener——R78 挂错对象。真机 `m1e task listener.f=f2` 来自 `j2.O @0x2acc44` 的 `task_NetSceneUploadMsgImg`（`task.f=j2.S=f2`）；B6 的 checkExist task（listener=qf4/j0）走 `mq/k.B8`，与 m1e 无关。真上传完成回调 = `f2.C2 @0x2aa888`，非 R78 的 `qf4/j0.C2 @0x84307c`。
- Q3：**探针读错**（u==null，非"真没有"）。`uLen=-1` 是兜底值；`findObjectOfType(a,this,r0)` 在 B6 入参 `(String,int,t0)` 找不到 r0（dex：r0 是 `b1.Q2/a3 @0x004a/0x00b2` 的局部变量）。R77 log:55 是 02:11 的 `MIO j2O in`（j2.O 探针），非 B6。正确读取：`b1.a3(talker,msgSvrId)→r0` → `g9.c(r0.u,"msg","")`（**三参**）→ `.msg.img.$cdnbigimgurl/$aeskey/$md5`。样本闸门改用 `j2init ori`/`m.a`（已命中）。
- Q4 最终裁决：**(b)** —— 方案 B 落点需改：从"注入 `r0.e/f` 本地字节"改为"复刻 B6 `g9.c(r0.u)` 拿 cdnbigimgurl/aeskey/md5 + `mq/k.B8` checkExist 秒传建新记录，让转发消息 xml 带原图 cdnbigimgurl"。不退 (c)：机制 dex 全可见、我方源码已在向其靠拢。
- 是否推翻 R77/R78 落点：**推翻（机制/落点层面）**。R77"r0.e 本地字节=清晰决定量"、R78"CDN 回调=qf4/j0.C2、样本闸门=B6 r0.u"均被证伪；保留"走 stock CDN 链"大方向，但重定性为"CDN URL 透传 + 秒传"。

[DONE0]

---

## Q1（核心·推翻性）：stock 清晰但 r0.e 指 36B 占位，清晰大图字节从哪来、经什么路径到对端？

### 一句话裁决

【源码确定 + 真机确定 + 推断，高置信】**清晰字节不在本地，而在 CDN 服务端；到对端走的是"秒传（second transmission / checkExist 命中）"，本地 36B 文件只是 ImgInfo2 新记录的路径占位，不是字节源。** stock 从**坏图 216b 自身的消息 xml**里读出原图的 `cdnbigimgurl/aeskey/md5`，填进 `tj/k` 上传任务 → 服务端命中已有原图 → 完成回调重建一条带 `cdnbigimgurl` 的新消息发给对端 → 对端凭 URL 从 CDN 拉原图 → 清晰。这同时解释"r0.e 指 36B 仍清晰""fileId 非空而 md5 空""`j2.R()` 只 logging 不产字节"三件事。

> 这个结论**推翻 R77/R78 的隐含前提**：R77/R78 把"清晰"归因于「`j2.R()` 的 `r0.e` 指向大图文件字节」（方案 B 的全部立论），但真机 `r0.e=882d...jpg` 是 36B 占位、本地无大图、对端却清晰 ⇒ **清晰度决定量不是 `r0.e` 的本地字节，而是消息 xml 里的 CDN 原图 URL + 秒传命中**。

### 1.1 完整字节路径（dex code_off 本轮自算，classes7.dex）

| 步 | dex 锚点 | 证据（逐指令） | 语义 |
|---|---|---|---|
| ① 读坏图 xml | `MsgRetransmitUI.B6` @`0x62c98c` | `0x00b6 r0.u` → `0x00bb g9.c(r0.u,"msg","")` → `0x00e8 ".msg.img.$cdnbigimgurl"`、`0x00fe ".msg.img.$aeskey"`、`0x014e ".msg.img.$md5"` | **从坏图 216b 的消息 xml 解析出原图 CDN 参数**（不是从本地文件） |
| ② 探服务端 | B6 同上 | `0x0157 new tj/k`、`0x015c d="task_MsgRetransmitUI_2"`、`0x0161 new qf4/j0`、`0x0188 task.f=qf4/j0`、`0x01a5-0x01c0 field_fileId/aesKey/filemd5/...`、`0x01cc mq/k.B8(task)` | 构造 checkExist 任务，listener = **qf4/j0**，探测服务端是否已有原图 |
| ③ 秒传命中回调 | `qf4/j0.C2` @`0x84307c` | `0x0006 iget-boolean Ltj/f;->field_exist_whencheck`、`0x000d "support second !! sceneResult:[%s]"`、`0x0036 b1.C4(h9.o0(),Z)`、`0x0130/0x016b b1.j6(...)`、`0x0238/0x027b b1.u8(...)` | **"support second !!" 即秒传命中**；建新消息 + `b1.j6` 落新 localId + `b1.u8` 持久化新记录 |
| ④ 新记录路径占位 | `b1.C4` @`0x2a563c` | 把 `THUMBNAIL://`/普通引用解析为磁盘路径（`z3`） | 新记录的 `r0.e` = `882d...jpg`（**占位路径**，非大图字节来源） |
| ⑤ 上游 j2 上传任务 | `j2.O(r0;I)Z` @`0x2acc44` | `0x00a2 j2.S:Ltj/i`（=f2）→ `0x00a4 task.f`；`0x0076-0x0094 z3(r0.e/f/g/j)`；`0x0124 field_fullpath=z3(r0.e)`；`0x02dd ok0/m1.e(tj/k)` | 真机 `m1e` 命中的 task（listener=f2）；`field_fullpath` 指向的就是 `r0.e`（36B） |
| ⑥ 上传完成→重建消息 | `f2.C2` @`0x2aa888` | `0x0181 field_UploadHitCacheType`、`0x0191 "UploadHitCacheType: %d, needSendMsg:%b"`、`0x0205 '" hdlength="'`、`0x0216 '" cdnbigimgurl="'`、`0x021e '<msg><img aeskey="'` | CDN 上传完成回调**重建 `<msg><img ... cdnbigimgurl=... hdlength=...>`**，把原图 URL 写进消息发对端 |

### 1.2 真机锚点逐条对齐（`_r78_stockcdn_realdev_clean.log`）

- 【真机确定】样本是坏图 216b：`INP j2init ori=.../image2/21/6b/216b1f7c...jpg`（`:27`）+ `m.a=883190`（`:28`）+ `str[3]=.../image2/21/`（`:21`）。
- 【真机确定】stock 入口确实走：`SCD B6 in msig=...MsgRetransmitUI.B6`（`:15`）/ `SCD B6 out`（`:32`）。
- 【真机确定】**新 localId + 占位路径**：`j2R after localIds=[a=884519 b=0→1034490892219222103]`（`:33/:56`）；`paths e=882d5de0...jpg f=null g=THUMBNAIL_DIRPATH://th_42cb...`（`:34`）。
- 【真机确定】**秒传特征**：`m1e task fileId=305f0201... md5=（空） onlycheckexist=false`（`:36`）——fileId 非空而 md5 空，与"服务端已有原图、只报 fileId"一致；`field_fullpath=.../88/2d/882d...jpg⌖`（`:37`），`listener.f=com.tencent.mm.modelimage.f2`（`:37`）。
- 【真机确定】`SCD s0b ret path=882d...jpg`（`:30`）——s0.b 读到的也是这个占位路径，非大图。
- 【真机确定 + 文件事实】`882d...jpg⌖` = 36B、`216b...jpg` = 36B、`th_216b...` = 15906B，cache 顶层无 22:49 大图（任务给出的 su ls 结果）——**本地根本不存在可供 `o6.N` 读出的大图字节**。
- 【真机确定】用户肉眼清晰、非缓存复用（任务给定）。

### 1.3 四个候选方向逐条裁决

| 候选 | 裁决 | 依据 |
|---|---|---|
| ① j2 其它 String 字段（field_fullpath/midimgpath）指真大图 | ❌ 否 | dex `j2.O` 里 `field_fullpath=z3(r0.e)`（B6/真机都指 882d 占位）；真机 `f=null`、`field_midimgpath=null`（`:34/:37`）；无第二个大图字段被读。`R()` 读图只经 `z3(r0.e/f/g)→o6.l→o6.N`（R75 已独立重验），数据源就是占位路径 |
| ② CDN 下载字节在内存直传、不落 image2 | ⚠️ 部分对，但要纠偏 | 不是"我方本地内存字节上传"——`j2.O` 走 `field_fullpath` 路径 + `o6.l` 长度检查，`f2.C2` 处理的是 CDN 回调结果并**重建消息指向 CDN**。真正的字节在**服务端**，对端自行拉取；本地未产大图字节 |
| ③ 36B 是"记录占位"，真图在 SERVERID:// 解析或另一 localId | ✅ 采纳（修正版：真图在 CDN 服务端，由新记录的 cdnbigimgurl 指向） | `b1.z3` 遇 `SERVERID://` 会调 `Y2(msgSvrId)`，但本例 `r0.e=882d...jpg` 是实体路径非 SERVERID；新 localId 884519 确由 `b1.j6` 建，但其 `e` 仍是 36B ⇒ "另一 localId"成立但"本地大图"不成立，大图在 CDN |
| ④ 微X/state 污染 | ❌ 否 | 全程 `MsgRetransmitUI.B6`+`j2`+`ok0/m1.e` 均为 stock 标准链，日志无任何微X dex/私有类命中；样本确为 216b（非缓存复用） |

### 1.4 对方案 B 的致命含义（先埋伏笔，Q4 展开）

方案 B 的数据流假设是"**字节 → 注入 r0.e/f → doScene z3(r0.e)→o6.N 读大图 → 上传**"。但本轮证明 stock 清晰**根本不经这条本地读图上传链**：本地 36B 被 z3/o6.N 读出也无大图，对端清晰靠的是**消息 xml 里的 cdnbigimgurl**。因此：
- 把 `r0.e/f` 指向自有大图字节，**如果消息 xml 里没有正确的 `cdnbigimgurl`/`aeskey`/fileId**（或 CDN 服务端无对应原图），对端仍拿不到清晰图；
- 反过来，只要消息 xml 带原图 `cdnbigimgurl`（坏图 216b 的 xml 里本就有，B6 已解析出），**即使本地是 36B，对端也清晰**——这正是本次事实。

⇒ 方案 B 的"字节注入点"选错了对象：应该注入的是**消息 xml 的 CDN 参数 / 走秒传**，而不是 `r0.e/f` 的本地路径。（Q4 据此重裁 a/b/c。）

### 1.5 纪律标注

- 纪律①：dex 里有 `cdnbigimgurl` 字符串 **不等于** 本次下载了大图；本次是 **checkExist 命中秒传、本地不产字节**，靠真机 fileId 非空/md5 空 + 文件 36B 双重确认。
- 纪律②：`B6 → B8(checkExist) → j0.C2 → k6 → j2.O → f2.C2 → 对端拉图` 是跨方法调用链**推断**；真机只观测到 B6 in/out、m1e、j2R、s0b（`:15/:32/:33/:36/:30`），`j0.C2`/`f2.C2` 本次零命中（R78 探针挂的是 `qf4/j0.C2`，真身上传回调是 `modelimage/f2.C2` @`0x2aa888`，见 Q2）——`field_exist_whencheck`+"support second" 分支是**合理解释但未经真机 hook 直接观测**，标【推断，高置信】。
- 纪律③：本问未引用单测；结论依赖 dex 反汇编 + 真机日志 + 文件事实，不依赖 `StockCdnProbeTest`。

---

## Q2（listener 真身）：f2 vs j0 矛盾怎么解？f2 的 CDN 完成回调签名 + code_off

### 一句话裁决

【源码确定 + 真机确定，高置信】**选 (b)：存在两条不同的 addSendTask/CheckExist 路径，用了两个不同的 `Ltj/i` listener**。R78 只看到 B6 里的 `qf4/j0` 就断言"listener=j0 / CDN 完成回调=qf4/j0.C2"，挂错对象；真机 `m1e` 命中的 `tj/k.f=f2` 是**另一条任务**——`j2.O()` 构造的 `task_NetSceneUploadMsgImg`，其 listener = `j2.S`（构造期 `new f2(j2)`）。两条路径的 listener / 下发 API / 回调职责都不同，R78 把两条混为一条。

### 2.1 两条路径逐条对照（dex 自算，均 classes7.dex）

| 维度 | 路径 A：B6 的 checkExist 任务 | 路径 B：j2.O 的上传任务（真机 m1e 命中） |
|---|---|---|
| 构造点 | `MsgRetransmitUI.B6 @0x62c98c`：`0x0157 new tj/k`、`0x015c d="task_MsgRetransmitUI_2"` | `j2.O @0x2acc44`：`0x0098 new tj/k`、`0x009d d="task_NetSceneUploadMsgImg"` |
| listener 赋值 | `0x0161 new qf4/j0` → `0x0188 task.f = qf4/j0` | `0x00a2 读 j2.S:Ltj/i` → `0x00a4 task.f = j2.S`（j2 构造期 `0x004a new f2` / `0x004f iput j2.S=f2`） |
| 下发 API | `0x01cc mq/k.B8(task)`（checkExist） | `0x02dd ok0/m1.e(task)`（**真机 `SCD m1e in` 命中的就是这个**） |
| 完成回调 | `qf4/j0.C2 @0x84307c`：`0x0006 field_exist_whencheck` + `0x000d "support second !!"` → 秒传命中建新记录 | `modelimage/f2.C2 @0x2aa888`：`0x0181 field_UploadHitCacheType` / `0x0191 "UploadHitCacheType: %d, needSendMsg:%b"` → 上传完成回调 |
| 职责 | 探测原图是否已存在（秒传判定），产出新 localId（Q1） | 真正把 CDN 上传结果写回消息 xml（`cdnbigimgurl`/`hdlength`），发对端 |

### 2.2 contradiction 拆解

- 【源码确定】R78 说的"`B6` 把 `qf4/j0` 挂进 `tj/k.f`"**这一句本身没错**（B6 `0x0188` 确凿），错在把它当成"**唯一的 / m1e 的 listener**"。真机 `m1e` 的 `field_fullpath=.../image2/88/2d/882d...jpg`（`:37`）与 `j2.O` 的 `field_fullpath=z3(r0.e)`（`0x0124`）同源，而 B6 的 checkExist task **没有** `field_fullpath` 赋值（它带的是 fileId/aesKey/md5）——**任务字段指纹直接证明 m1e 命中的是 j2.O 的 task，不是 B6 的**。
- 【源码确定】`modelimage/f2` 与 `qf4/j0` 的方法表**几乎同构**（都实现 `Ltj/i` 的 `C2/I/z` 三方法），这正是 R78 混淆的根源：把"接口实现类的签名"当成了"listener 类本身"。二者差异：
  - `modelimage/f2` 方法（classes7.dex）：`<init>(j2)V`、`C2(String,int,Ltj/e,Ltj/f,Z)I @0x2aa888`、`I(String,[B)[B`、`a(String)String`、`z(String,ByteArrayOutputStream)V`。
  - `qf4/j0` 方法（classes7.dex）：`<init>(MsgRetransmitUI,h9,II,String,String,String,String,I,r0)V`、`C2(...)I @0x84307c`、`I(...)[B`、`z(...)`。
- 【真机确定】`SCD m1e in task listener.f=com.tencent.mm.modelimage.f2`（`:37`）+ `SCD-CDNcb`（挂 `qf4.j0.C2`）与 `SCD-b1j6` 零命中（任务给定）——R78 探针挂的 `qf4/j0.C2` 是 checkExist 回调，本次样本若未走 checkExist 命中分支（或 hook 时序未捕获）即零命中；而上传回调真身是 `f2.C2`，探针根本没挂它。

### 2.3 给 Flash 的精确 hook 落点（本次新结论）

| 目的 | 精确落点（classes7.dex，本轮自算） | 说明 |
|---|---|---|
| **真正的上传完成回调** | `Lcom/tencent/mm/modelimage/f2;->C2(Ljava/lang/String;ILtj/e;Ltj/f;Z)I` @`0x2aa888` | 替代 R78 误挂的 `qf4/j0.C2`；`Ltj/i` 接口实现，用 `hookAllMethods(findCls("com.tencent.mm.modelimage.f2"),"C2",cb)` 挂全 |
| 上传任务构造（listener 写入点） | `Lcom/tencent/mm/modelimage/j2;->O(Lcom/tencent/mm/modelimage/r0;I)Z` @`0x2acc44` 的 `0x00a2-0x00a4` | 在此可改 `tj/k.f`（换 listener）或读 `field_fullpath`（=z3(r0.e)） |
| j2 的 listener 来源 | `j2.S : Ltj/i` 字段；构造期 `0x004a new f2` / `0x004f iput j2.S=f2`（多个 `<init>` 重载一致） | 想在 j2 层拦截可 hook `task.f` 的写入 |
| checkExist 秒传回调（保留观测） | `Lqf4/j0;->C2(...)I` @`0x84307c` | 看 `field_exist_whencheck` 是否秒传命中，`b1.j6 @0x2a74f8` 落新记录 |
| 任务下发点 | `ok0/m1.e(tj/k)Z @0x7b52b4`（上传任务）/ `mq/k.B8(tj/k)Z`（checkExist） | 区分两条路径用哪个 API |

### 2.4 纪律标注

- 纪律①：本节**不**因"`f2`/`j0` 都有 `C2` 方法名"就断言二者同一回调角色；是用 `task.f` 的实际赋值指令 + 任务字段指纹（`field_fullpath` 只出现在 j2.O task）+ 真机 listener 名三重锁定。
- 纪律②：`B6 → 秒传 → k6 → j2.O → f2.C2` 的跨方法时序仍为推断，标假设（同 Q1.5）。
- 纪律③：本问未引用单测。R78 §Q4.2 的探针补丁把 CDN 回调挂到 `qf4/j0.C2`，本轮证明该落点错——若 Flash 照 R78 补丁实现，会重演零命中；须改用本节 `f2.C2 @0x2aa888`。

---

## Q3（r0.u 矛盾）：`hasCdnBigImgUrl=false` 是探针读错还是 R77 log 错？B6 解析 r0.u 的 key/API

### 一句话裁决

【源码确定 + 真机确定，高置信】**探针读错（u==null），不是真没有 cdnbigimgurl，也不是 R77 log:55 错。** 本次 `uLen=-1` 是 `u?.length ?: -1` 的兜底值，等价于 `u==null`；根因是 `StockCdnProbe.kt:104` 的 `findObjectOfType(a, this, "com.tencent.mm.modelimage.r0")` 在 **B6 入参 `(String,int,t0)` + this(MsgRetransmitUI) 字段里找不到 r0**——dex 证明 B6 的 r0 是**方法内局部变量**，由 `b1.Q2/a3(...)` @`0x004a/0x0077/0x00b2` 查出，既不在入参也不是 Activity 字段。R77 的 `log:55` 是**另一探针（`MIO j2O in`）在 02:11 R76 实验**里读的 `j2.O` 入参 r0.u，**不是 B6 日志**，不能用来判本次 B6 是否读到 CDN 参数。

### 3.1 `uLen=-1` 的自证链路（探针源码 + dex）

- 【源码确定】`StockCdnProbe.kt:104-109`：
  ```
  r0 = findObjectOfType(a, param.thisObject, "com.tencent.mm.modelimage.r0")
  u  = getObjectField(r0,"u") as? String        // runCatching，失败→null
  hasBig = u?.contains("cdnbigimgurl")==true
  bigLen = Regex(...).find(u?:"")?.groupValues?.get(1)?.length ?: -1
  log("... uLen=${u?.length ?: -1}")
  ```
  `uLen=-1`（`:16`）⇒ `u==null`；此时 `hasBig=false`、`bigLen=-1` **是 u 为 null 的连带兜底值，不构成"u 里没有 cdnbigimgurl"的证据**。
- 【源码确定】`findObjectOfType`（`StockCdnProbe.kt:419-454`）只扫 `param.args[]` 各对象及其字段（深度≤2）和 `param.thisObject` 字段（深度≤4）。B6 签名 `(String,int,t0)`：入参是 wxid 字符串、int、`t0`；r0 若为局部变量则**两者都扫不到**。
- 【源码确定】B6 dex（`0x62c98c`）逐指令：`0x004a b1.Q2(talker,msgSvrId)→r0`、`0x0077/0x00b2 b1.a3(talker,w0())→r0`、`0x00b6 r0.u`、`0x00bb g9.c(r0.u,"msg","")`。**r0 是 `b1` 查询返回的局部对象，未作为 B6 入参、未证明挂到 this 字段** ⇒ 探针 `findObjectOfType` 拿不到，`u=null` 是必然。

### 3.2 旁证：本次 xml 确实有 CDN 参数（j2init 探针读到了 u）

- 【真机确定】`INP j2init str[7]=<?xml ...>\n<msg>\n<img aeskey="60af7983188a7701a...`（`:23-25`）——这是**另一个探针（j2init）从 j2 对象的 r0 字段读到的消息 xml**，含明文 `aeskey`。`j2.R()` 把 r0 缓存在 `j2.q`（R75 §3），故 j2init 能读到 r0.u，而 B6 探针读不到——**同一对象、不同探针落点，一有一无，反证 B6 探针是字段/对象定位问题**。
- 【真机确定】`m1e task fileId=305f020100044b3049...`（`:36`）非空。对照 R76 fail log:55 的 `cdnmidimgurl="305f0201..."` 前缀**完全一致**——fileId 正是从消息 xml 的 CDN URL 解析出来的，间接证明本次坏图 xml 同样携带 CDN URL。
- 【真机确定】注意：本次 j2init 的 `aeskey=60af7983...` 与 R76 log:55 的 `aeskey=95fee190...` **不同**（不同消息对象/会话副本），但 `305f0201...` fileId 前缀一致 ⇒ 同一原图的 CDN 身份不变。这条差异不影响"xml 带 CDN 参数"的结论。

### 3.3 R77 log:55 的性质（不是 B6，也不是本轮）

- 【真机确定】`docs/_r76_p3_realdev_fail1.log:54`（R77 文档 `:50` 引用为 "log:55"）原文是 `11-22 02:11:02.088 ... MIO j2O in r0.u=<msg><img aeskey="95fee190..." cdnmidimgurl="305f0201..." ...>`。
  - 探针前缀是 **`MIO j2O in`**（我方 `j2.O` 探针），不是 `SCD B6`；
  - 时间是 **02:11 的 R76 实验**，不是本轮 22:49；
  - 它读的是 `j2.O` 的入参 r0.u（j2.O 入参就是 r0，见签名 `O(Lr0;I)Z`），**天然能读到 r0.u**——这与 B6 探针"读不到"毫无矛盾。
- 结论：R77 "fail log:55 证明坏图 r0.u 自带明文 CDN 参数"这句**事实成立**（那张 log 确凿），但 R77 把它当作 **B6/本次 stock 转发的样本身份闸门**（`docs/_r78_m0l0_miss_verdict.md` Q4.3 要求 `SCD B6 r0.u hasBigImgUrl=true`）是**探针落点错配**——B6 探针按现有写法永远读不到 r0.u。

### 3.4 正确的 CDN 参数读取方式（给 Flash 的修正探针）

照抄 B6 dex（`0x62c98c`），每一步都本轮自算：

```kotlin
// 1) 拿 r0：不要用 findObjectOfType(a,this,r0)——B6 的 r0 是局部变量
val msg   = <B6 入参 p2 / this.f 指向的 h9 消息对象>       // B6 0x0014 j9.u3(msgSvrId)
val talker = msg.C0()                                      // 0x0042/0x006f/0x00aa
val msgSvrId = msg.getMsgId()                              // 0x0018/0x0125
val r0 = XposedHelpers.callStaticMethod(
           findCls("com.tencent.mm.modelimage.d3"), "O8"
         ).let { callMethod(it, "a3", talker, msgSvrId) }   // 0x00b2 b1.a3(talker,w0())→r0
// 2) 解析 xml：g9.c 是【三参数】(String,String,String)Map，R77 文档写两参是省略
val map = XposedHelpers.callStaticMethod(
            findCls("com.tencent.mm.sdk.platformtools.g9"), "c",
            XposedHelpers.getObjectField(r0, "u"), "msg", "") as Map<*,*>
// 3) 取参数（key 精确，来自 B6 0x00dd/0x00e8/0x00fe/0x014e）：
val cdnMid  = map[".msg.img.$cdnmidimgurl"]    // 0x00dd
val cdnBig  = map[".msg.img.$cdnbigimgurl"]    // 0x00e8
val aesKey  = map[".msg.img.$aeskey"]          // 0x00fe
val imgMd5  = map[".msg.img.$md5"]             // 0x014e
// 4) 样本身份闸门：用 imgMd5/cdnBig 的长度或值片段——
//    不要用 u.contains("216b1f7c")：216b1f7c 是【文件名】，xml 的 $md5 字段值未必等于它
```

- ⚠️ 样本闸门修正：R78 Q4.3 的 `u.contains("216b1f7c")` 有两处错：① B6 探针根本拿不到 u；② 即便拿到，`$md5` 字段值也可能不是文件名 `216b1f7c`（本次新记录文件名是 `882d...`，拜 `b1.j6/C4` 所赐）。**可靠闸门 = `INP j2init ori=.../216b...jpg`（已在 `:27` 命中）+ `m.a=883190`（`:28`）**，这两条本轮已确证样本身份，不必依赖 B6 探针。

### 3.5 纪律标注

- 纪律①：本节**不**因"B6 有 `.msg.img.$cdnbigimgurl` 字符串"就断言本次一定读到/下载；关键区分是 `u==null`（探针定位失败）与 `u` 真不含该 key——本轮是前者，由 dex 局部变量证据 + j2init 探针对照双重锁定。
- 纪律②：`r0` 从 `b1.a3` 查得、`g9.c` 三参——均为 dex 静态事实；"j2init 读到 u 而 B6 读不到"是真机对照事实；二者组合为高置信推断，非单一来源。
- 纪律③：本问未引用单测。`StockCdnProbeTest.kt` 不覆盖"findObjectOfType 能否在 B6 定位 r0"，故单测全绿不能证明该探针取值正确。

---

## Q4（裁决与下一步）：(a)/(b)/(c) 三选一

### 最终裁决 = **(b) 方案 B 落点需改：从"注入 `r0.e/f` 本地字节"改为"复刻 B6 的 CDN 秒传参数 + checkExist 建新记录，让转发消息 xml 携带原图 cdnbigimgurl"**

**不硬撑 (a)，也不草率退 (c)。** 理由：Q1 证明 stock 清晰的决定量是**消息 xml 的 CDN 原图 URL + 服务端秒传命中**，不是 `r0.e` 的本地字节——方案 B 原落点（写 `r0.e/f`）针对的是错误的字节源；但 stock 的整条机制**dex 全可见**（`b1.a3→g9.c→tj/k→mq/k.B8→j0.C2→b1.j6→k6→j2.O→f2.C2`），不存在"dex 不可复刻"的硬障碍，故不退 (c)。

### 4.1 为什么否决 (a)：方案 B 字节注入点选错了对象

- 【源码确定 + 真机确定】R77/R78 方案 B 的数据流 = "自有大图字节 → `j2.R() after` 写 `r0.e/f` → `doScene z3(r0.e)→o6.N` 读大图上传"。但：
  - 真机 `r0.e=882d...jpg` 是 36B 占位、`f=null`，`o6.N` 即便执行也读不到大图（Q1 §1.2 文件事实）；
  - stock 在此情形下**仍清晰**，因为对端凭消息 xml 的 `cdnbigimgurl` 从 CDN 拉原图（Q1 §1.1/§1.3），与本地 `r0.e` 的字节**无关**。
- ⇒ 把自有大图字节注入 `r0.e/f`，**若转发消息 xml 不带原图 `cdnbigimgurl`（或 CDN 服务端无对应原图），对端仍不清晰**；反之消息 xml 带了正确 URL，本地 36B 也能清晰（本次事实）。方案 B 的因果链断裂在"决定量误判"，不是"字节注入技巧不够"。

### 4.2 为什么不退 (c)：机制 dex 可见，且我方源码已在向它靠拢

- 【源码确定】stock 清晰机制的每一步都 dex 可见、可反射调用：`b1.a3(talker,msgSvrId)`（`0x00b2`）、`g9.c(r0.u,"msg","")`（`0x00bb`）、`new tj/k`+`mq/k.B8`（`0x01cc`）、`ok0/m1.e(tj/k) @0x7b52b4`、`b1.j6 @0x2a74f8`、`k6 @0x62d368`、`j2.O @0x2acc44`、`f2.C2 @0x2aa888`。Q8 曾判断"方案 B 不可复刻"的前提（回调挂错、落点错）已被 Q2/Q3 纠正为**探针问题**而非**机制不可达**。
- 【源码确定·我方代码】`ForwardLogic.kt:392-394` 注释已写明："微信自带转发把 imgLocalId 设为源消息 ImgInfo2 localId(>0) ⇒ j2 走 ImgInfo2/CDN 服务端引用拿清晰大图；我方旧值恒 -1 ⇒ j2 读本地 ori 缩略图 ⇒ 模糊"；R73 已把 `imgLocalId` 改成 >0 并 `d3.O8().o2(m.a)`（`ForceForward.kt:2204`、`FileSender.kt:184`）。⇒ 我方方向正确，只差**复刻 B6 的 checkExist 秒传建新记录**这一步。
- 【推断，高置信】我方仍模糊的根因：`o2(源localId)` 挂的是**源坏图 216b 的旧 ImgInfo2 记录**（`r0.e`=36B、xml 即使有 cdnbigimgurl 也未走 checkExist 秒传建新记录），而 stock 是通过 `B6→B8(checkExist)→j0.C2 "support second"→b1.j6` **新建一条带有效 cdnbigimgurl 的记录**（新 localId 884519）。二者差在"是否新建记录 + 是否触发服务端秒传"，不在"本地有没有大图字节"。

### 4.3 (b) 的最小改动（dex 锚点 + 落点）

> 只改**字节/URL 来源与 task 构造**，不改已闭环的 `m.a/q1.f/q1.h/q1.j` 三层；实例化 `tj/k` 照抄 B6，只读宿主状态。

1. **拿原图 CDN 参数**（替换方案 B 的 `prepareBigImg`/`DownloadImgService.c` CGI 调用）：
   - `r0 = b1.a3(msg.C0(), msg.getMsgId())` @`0x00b2`（或 `b1.Q2` @`0x004a`）；
   - `map = g9.c(r0.u,"msg","")` @`0x00bb`（**三参**）；取 `.msg.img.$cdnbigimgurl`/`$aeskey`/`$md5` @`0x00e8/0x00fe/0x014e`。**坏图 216b 的 xml 本就带这些**（Q3 §3.2 + R76 fail log:55 旁证），无需下载字节。
2. **触发 checkExist 秒传建新记录**（这是 stock 与当前的唯一实质差异）：
   - 照抄 B6 `0x0157-0x01c0` 构造 `tj/k`（`task_MsgRetransmitUI_2`、fileId/aesKey/filemd5/mediaId），`field_onlycheckexist` 由 `0x01be` 实际值确定（真机 `onlycheckexist=false`，R78 §Q2.4 已纠 R77 的 true）；
   - 调 `mq/k.B8(task)` @`0x01cc`（或 `ok0/m1.e @0x7b52b4`）；自建 `Ltj/i` listener 注入 `task.f`，在 `C2` 出参读秒传结果（**非 Activity 依赖**，修正 R78 §Q3.3 的关切），核心是让 `b1.j6 @0x2a74f8` 落新记录。
3. **无需注入 `r0.e/f` 本地字节**：新记录的 `r0.e` 即使是 36B 占位也无妨（本次事实）；关键是转发消息 xml 带 `cdnbigimgurl`。原 `j2.R() after` 写字节的 hook 可撤。

### 4.4 互斥真机判据（一次跑完可定案 (b) 是否真的让我方清晰）

| 观测 | 结论 |
|---|---|
| 我方转发后：消息 xml 带 `cdnbigimgurl`（抓 `INP j2init str[7]` 或 `f2.C2 @0x2aa888` 重建的 `<msg>`）+ 对端清晰 | (b) 成立 ⇒ 派实现（§4.3） |
| xml 带 URL 但对端仍模糊 | 需查 CDN 服务端是否有该原图（fileId `305f...` 是否过期）+ `field_exist_whencheck` 是否命中；仍失败才考虑 (c) |
| xml 不带 URL / `g9.c` 取不到 `$cdnbigimgurl` | 坏图 xml 已损坏 ⇒ 回到方案 B 本地字节路径，但须有"CDN 服务端可上传原图"的前提，否则也不清晰 ⇒ (c) |
| 我方 XML 本就带 URL 且已清晰 | 说明当前 `m.a>0` 修复已够，方案 B/本改动均不需要（收尾） |

### 4.5 是否推翻 R77/R78 落点（本轮净结论）

- **推翻（落点/机制层面）**：
  - R77 定案"清晰度决定量 = `j2.R() r0.e` 指向本地大图字节、方案 B 写 `r0.e/f`"——**被 Q1 推翻**：决定量是消息 xml 的 cdnbigimgurl + 服务端秒传，本地 36B 也能清晰。
  - R77 "完成回调=`qf4/m0.l0`、字节来源靠回调拿大图路径"——**被 Q2 推翻**：该回调是 CGI 的 `fv.e0`；上传回调真身 `f2.C2 @0x2aa888`；listener 真身两条路径（Q2）。
  - R78 "前置探针 `SCD B6 r0.u hasBigImgUrl` 作样本闸门 / CDN 回调挂 `qf4/j0.C2`"——**被 Q3+Q2 推翻**：B6 探针读不到 u（定位错），R78 挂的 `qf4/j0.C2` 是 checkExist 回调非上传回调；样本闸门应靠 `j2init ori=216b...`/`m.a=883190`。
- **部分保留**：R77/R78 "走 stock CDN 链、iganore CGI、字节来源于原图 CDN 参数"的大方向对；错在把"字节"理解成本地文件字节、把回调/落点挂错类。本轮把机制重定性为**"CDN URL 透传 + 秒传"**，落点从 `r0.e/f` 转向 `B6` 参数构造 + `checkExist` 建新记录。

### 4.6 限制与纪律（如实标注）

- 【限制】Q1 §1.5/Q2 §2.4 的 `j0.C2→b1.j6→k6→j2.O→f2.C2` 时序为**跨方法推断**；真机未直接 hook 到 `j0.C2`/`f2.C2`（R78 探针挂错类）。"秒传命中建新记录"是 fileId 非空+md5 空+新 localId+36B+肉眼清晰多源汇聚的**高置信推断**，非单一回调直接观测。§4.4 的真机判据就是为坐实这一环。
- 纪律①：本节不因 dex 有 `cdnbigimgurl`/`support second` 字符串就断言已秒传；关键证据是 `field_exist_whencheck` 分支 + `b1.j6` 写入 + 真机 fileId/md5/新 localId/36B 四重事实。
- 纪律②：`checkExist 命中 → 建新记录 → 对端清晰`标注为假设，待 §4.4 真机验证，不冒充已证事实。
- 纪律③：引用单测说明——`ForwardLogic.kt` 的策略判据有 JVM 单测覆盖（如 `R73ForwardCtxSendTest`），但**没有任何单测覆盖"消息 xml 带 cdnbigimgurl ⇒ 对端清晰"这一因果**（本轮不依赖单测下结论）；若派实现须补该因果的变异验证。

---

证据基线（可自证）：dex code_off 由本代理用 `_r35_adnd_dex.py`/`_r35_sns_dis.py` 对 `_cache/dex/classes7.dex`（微信 8.0.48/2588）逐字节**本轮自算**，关键锚点：`B6 0x62c98c`、`j6 0x62d0f8`、`k6 0x62d368`、`j2.O 0x2acc44`、`j2.R 0x2ad864`、`ok0/m1.e 0x7b52b4`、`qf4/j0.C2 0x84307c`、`modelimage/f2.C2 0x2aa888`、`b1.j6 0x2a74f8`、`b1.C4 0x2a563c`、`b1.a3 0x2a36b0`、`b1.z3 0x2a6ae4`、`g9.c (String,String,String)Map 0x00bb@B6`；真机事实取自 `docs/_r78_stockcdn_realdev_clean.log`（93 行）+ 任务给定 su ls 文件事实；探针事实取自 `wxgroup/app/src/main/java/com/lx/wxgroup/probe/StockCdnProbe.kt`；我方链路取自 `ForwardLogic.kt`/`ForceForward.kt`/`FileSender.kt`。全程只读，唯一写操作 = 本文档；未改任何源码/测试/配置，未执行 adb/gradle/git。

[DONE]
