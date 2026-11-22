# R80 收口裁决：图片转发模糊问题最终可执行路线

> 本轮定位：**收口裁决**。经历 R73~R79 十余轮，现已有决定性的 fileId 空/非空硬真机对照（对照 A=stock 自带转发清晰 / 对照 B=我方模块转发模糊）。本文基于两条独立真机对照 + 自算 dex 证据，给出最终可执行路线（或如实判不可行）。
>
> 硬约束：只读分析，唯一写操作 = 本文。全程中文。每条结论带可自证锚点（dex code_off / 真机日志行 / 文件事实 / 源码行），区分【源码确定】【真机确定】【推断+置信度】。
>
> 三条纪律：① dex 有字符串 ≠ 业务调用；② "A 推 B" 必须标假设；③ 引用单测需说明是否变异验证。
>
> 工作目录：`C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`，源码在 `wxgroup/`，dex 在 `_cache/dex/classes*.dex`。

---

## 关键硬对照（本轮裁决的事实基座）

- **对照 A（22:49 stock 自带转发，肉眼清晰）**：`docs/_r78_stockcdn_realdev_clean.log`
  - `SCD B6 in/out` ✅（原生转发入口 `MsgRetransmitUI.B6`）→ `SCD m1e out addSendTask ret=true`
  - `SCD m1e task fileId=305f020100044b3049...（非空）md5=（空） onlycheckexist=false`
  - `SCD m1e task listener.f=com.tencent.mm.modelimage.f2`，`field_fullpath=.../88/2d/882d...jpg_`
  - `j2.R a=884519`（新记录），`r0.e=882d...jpg` = 36B 占位
- **对照 B（23:13 我方模块转发，肉眼模糊）**：`docs/_r79_dualsample_realdev.log`
  - `FORWARD plan strategy=RESEND_FILE reason=原生转发不可用，降级重发文件`（我方进不了 B6）
  - `SCD B6` 命中 = 0；`INP j2init ori=.../th_216b...`（缩略图），`m.a=883190`
  - `SCD m1e task fileId=（空）md5=（空） onlycheckexist=false`（对比 A 的非空）
  - `SCD m1e task listener.f=com.tencent.mm.modelimage.f2`，`field_fullpath=.../c8/1a/c81ae4ab...jpg_`
  - `j2.R a=884531`（新记录），`r0.e=c81ae4ab...jpg` = 15906B 缩略图
  - `f2C2 in field_exist_whencheck=true field_UploadHitCacheType=3`
  - `f2C2 fStrFields field_fileId=305f...（非空，上传过程中服务端分配）field_filemd5=0ef205...`
  - `MSG_HAS_URL` 命中 = 0

---

## Q1 机制收敛：fileId 空/非空能否自洽解释全部事实

### 一句话裁决

【源码确定（dex 自算）+ 真机确定 + 推断，高置信】**能。一个差异——上传 task 的 `field_fileId` 是否携带原图 CDN 身份——自洽解释全部事实。** 但要修正核心假设的**落点**：fileId 不是 B6 手工塞进 task 的，而是 `j2.O` 自己从 `j2.B`（=待发送消息的 xml）解析出来的；B6 的作用是**保证待发送消息保留源坏图 xml 里的 `cdnbigimgurl`**。fileId 非空 ⇒ 服务端认出"这是已存在的原图" ⇒ 把原图交给对端 ⇒ 清晰；fileId 空 ⇒ 服务端只见到本地 15KB 缩略图 ⇒ 当新图上传 ⇒ 对端拿到缩略图 ⇒ 模糊。

### 1.1 决定量：`j2.O` 里 fileId 的唯一来源（本轮 dex 自算，classes7.dex）

`j2.O(Lcom/tencent/mm/modelimage/r0;I)Z` @`0x2acc44`，逐指令（本轮 `python _r35_sns_dis.py body modelimage/j2 O` 实跑）：

| 指令偏移 | 指令 | 语义 |
|---|---|---|
| `0x0238` | `iget-object v2, j2 ->B : String` | 取 `j2.B`（消息 xml） |
| `0x023e` | `invoke-static g9.c(String,String,String)Map @0x4fd510` | 解析 `j2.B` |
| `0x0249/0x0251` | `const ".msg.img.$cdnmidimgurl"` → `iput field_fileId` | fileId 先取中图 URL |
| `0x0264/0x026c` | `const ".msg.img.$cdnbigimgurl"` → `iput field_fileId`（**覆盖**） | **fileId 最终 = xml 的 cdnbigimgurl** |
| `0x027c/0x0284` | `const ".msg.img.$aeskey"` → `iput field_aesKey` | aesKey 来自 xml |
| `0x0293-0x02b1` | aesKey 为空/"null" ⇒ `CdnLogic.createAeskey()` | **只有 aesKey 有兜底生成，fileId 没有** |
| `0x02d2-0x02dd` | `field_fileId→j2.n`、`field_aesKey→j2.o`、`ok0/m1.e(task) @0x7b52b4` | 下发上传 task |

【源码确定】**`field_fileId` 在 `j2.O` 中只有一个写入来源：`g9.c(j2.B,"msg","")` 的 `.msg.img.$cdnbigimgurl`（`0x026c`），若为空则 `$cdnmidimgurl`（`0x0251`），再为空即空串——没有"服务端分配后回填 task"之外的生成逻辑。** 这把"fileId 为何 A 非空 B 空"收敛为"消息 xml 是否带 cdnbigimgurl"。

`j2.B` 的来源同样自算：`j2.<init>(II)V` @`0x2ae6b4` 的 `0x01a2 h9.getContent()` → `0x01a6 iput j2.B`。【源码确定】**`j2.B = 待发送消息 h9.content`（原样 xml）**，不是我方塞进去的字节。

### 1.2 两条对照逐条对位

| 事实 | 对照 A（stock 清晰） | 对照 B（我方模糊） | 来源 |
|---|---|---|---|
| 入口 | `SCD B6 in/out`（原生转发）`:15/:32` | `FORWARD plan RESEND_FILE 原生转发不可用` `:3`，`SCD B6` 命中 0 | 真机确定 |
| j2.B（消息 xml） | 带源坏图 `aeskey=60af...`、cdnbigimgurl（j2init `:23-25`） | 只打印 ori=th_216b 缩略图、无 cdn 参数（`:7-15`） | 真机确定（B 由 1.1 推断） |
| task.fileId | `305f0201...4326230`（非空）`:36` | 空 `:24` | 真机确定 |
| 本地字节 | 36B 占位 `882d...`（新记录 a=884519）`:33-34` | 15906B 缩略图 `c81ae4ab`（新记录 a=884531）`:20-22` | 真机+文件确定 |
| 上传结果 fileId | f2C2 未挂（探针当时无此 hook） | f2C2 回调 fileId `305f...33363531...`（**另一串**）+ md5 `0ef205...` `:45` | 真机确定 |
| hitcache | 未观测 | `UploadHitCacheType=3`、`exist_whencheck=true` `:44/:46` | 真机确定 |
| 肉眼 | 清晰 | 模糊 | 真机确定 |

### 1.3 核心假设：确认 + 一处关键修正

- **确认**：fileId 是清晰度的分水岭。A 带原图 fileId ⇒ 对端拿到原图；B 不带 ⇒ 服务端把缩略图当新图（f2C2 分配**全新** fileId、md5=0ef205... 与源图不符）⇒ 对端模糊。R75 "r0.e 本地字节=清晰"已被 A（36B 仍清晰）彻底推翻；本文不再复活它。
- **修正**：R79 描述的"stock 靠 B6 从原图 xml 解析 cdnbigimgurl/aeskey/md5 → 塞进 task"**方向对、落点不准**。dex 显示 `j2.O` 自己也解析 `j2.B` 的 cdnbigimgurl 写 fileId（`0x026c`），且这是上传 task 的**唯一** fileId 来源。B6 的 checkExist task（`tj/k`，`mq/k.B8 @0x78f618`）带的是 fileId/aesKey/md5 去**探测**原图存在与否；它与真正上传的 `j2.O` task 是两条 task（R79 Q2 已确立）。"清晰"发生在上传 task 这一条上，决定量是它的 fileId。
- **对 `UploadHitCacheType=3` 的正名**：B 也命中 hitcache=3、exist_whencheck=true（`:44/:46`），说明 hitcache 只表示"服务端判定这块字节已存在、可秒传"，**不携带"是哪张图"的身份**；身份由请求里的 fileId 决定。A 原图 fileId 命中 ⇒ 秒传原图；B 空 fileId ⇒ 上传的缩略图命中它自己的缓存 ⇒ 仍是缩略图。**这解释了"两边都 hitcache 却一清晰一模糊"的表面矛盾**，是本轮 fileId 对照的最大价值。

### 1.4 置信度与限制（如实标注）

- 【源码确定】1.1 全部 `j2.O`/`j2.<init>` 指令、`f2.C2` 重建（见 Q3）、各 code_off——本轮从 `classes7.dex` 二进制实跑自算，非引用历史文档。
- 【真机确定】1.2 全部字段值、字节大小、肉眼结果——取自两条独立真机日志。
- 【推断，高置信】"j2.B 在 A 含 cdnbigimgurl、在 B 不含"——因 dex 已锁 fileId 唯一来源 = j2.B 的 cdnbigimgurl，且 A fileId 非空 / B 空，反推成立；但 B 探针未直接 dump j2.B 全文，非单一直接观测。
- 【限制 1】对照 A 当时未挂 `f2.C2` 探针，故 A 的 `UploadHitCacheType`/f2C2 重建消息**未直接观测**；A 的秒传命中是 fileId 非空+36B+肉眼清晰多源汇聚。
- 【限制 2】"服务端据 fileId 认出原图并秒传"跨微信服务端，无法本地 dex 全证；属机制解释，非已 hook 事实。
- 纪律①：本文不因 dex 有 `cdnbigimgurl` 字符串就断言下载；fileId 差异由真机 field 值锁定。纪律②：`B6→j2.B 保留原图 xml→fileId→秒传→清晰` 是跨方法/跨进程推断，已标层级。纪律③：本问不依赖单测，未引用变异验证结果。

## Q2 最小可行路径（最关键）：能否只补 task 的 fileId/aeskey 字段

### 一句话裁决

**(a) 可行·最小改动**——【源码确定 + 真机确定，高置信】**不需要整套复刻 B6，也不需要 hook `j2.O` 改字段写入逻辑。** 只需在上传 task 下发前（`ok0/m1.e @0x7b52b4` 之前）把源坏图 xml 解析出的 `field_fileId` + `field_aesKey` 补进 `tj/k`。前提：源坏图 216b 的消息 xml 仍带 `cdnbigimgurl`/`aeskey`（真机 j2init `:23-25` 已读到明文 aeskey；R76 fail log 旁证 cdnmidimgurl `305f...`）——若 xml 已损坏到无这些 key，则无 fileId 可补，须退 (b)/(c)。

### 2.1 dex 自算：`j2.O` 字段从 r0 哪些字段来（`0x2acc44`，本轮实跑）

| task 字段 | 写入指令 | 来源 | 是否可外部补 |
|---|---|---|---|
| `field_fullpath` | `0x0124/0x013f/0x01f8` | `z3(r0.e)`（本地原图路径） | 已是本地缩略图，无需改 |
| `field_thumbpath` | `0x0126/0x0141/0x01fa` | `z3(r0.g)` | 不动 |
| `field_midimgpath` | `0x0128/0x0143/0x01ff` | `z3(r0.j)` | 不动 |
| **`field_fileId`** | `0x0251←$cdnmidimgurl`，`0x026c←$cdnbigimgurl`（覆盖） | **`g9.c(j2.B,"msg","")` 的 map** | **✅ 目标：补这里** |
| **`field_aesKey`** | `0x0284←$aeskey`；`0x02b1←createAeskey()`（仅空/"null"时兜底） | `g9.c(j2.B)` map，可生成兜底 | ✅ 目标：补这里 |
| `field_midFileLength/totalLen` | `0x025e/0x0260/0x0278/0x027a` | `$length` | 建议一并补（见 2.3） |
| listener `f` | `0x00a4 ← j2.S`（`new f2`） | 构造期固定 | 不动（R79 已确认 f2 是真身） |

【源码确定】`field_fileId` **无兜底生成**：aesKey 空会 `createAeskey()`（`0x02ad`），但 fileId 空就是空串直接下发（`0x02dd ok0/m1.e`）。这正是 B fileId 空的 dex 根因——`j2.B` 无 cdnbigimgurl。

### 2.2 为什么"补字段"而非"整套 B6"就够：对照 A 已给出充分性证明

- 【真机确定】对照 A 的上传 task：`fileId=305f...`（非空）+ `field_fullpath=.../882d...jpg_`（36B 占位，`:36-37`）⇒ 肉眼清晰。**这直接证明：只要上传 task 带原图 fileId，本地字节是不是大图都无所谓**（服务端按 fileId 认图，36B 也能清晰）。
- ⇒ 我方 task 的 `field_fullpath` 指向 15KB 缩略图（`:25`）不影响可行性；唯一缺的是 fileId/aesKey 这两个"身份字段"。补上即满足 A 的充分条件。
- 【源码确定】补字段不破坏 `j2.O` 已闭环的本地路径链（`z3(r0.e)` 等），也不碰 `listener=f2`；改动面收敛到 2~4 个字段。

### 2.3 精确落码规格（(a) 路线）

**前提读取（拿原图 CDN 参数，照抄 `j2.O`/B6 dex，本轮自算）**：
```kotlin
// 源坏图 localId=883190 / msgId=19733550（真机 :4/:14 固定，或由转发上下文传入）
val r0 = callStaticMethod(findCls("com.tencent.mm.modelimage.d3"), "O8").let {
           callMethod(it, "a3", talker, msgSvrId) }   // b1.a3 @0x2a36b0
val map = callStaticMethod(findCls("com.tencent.mm.sdk.platformtools.g9"),
           "c", getObjectField(r0, "u"), "msg", "") as Map<*,*>  // g9.c @0x4fd510（三参）
val fileId = map[".msg.img.$cdnbigimgurl"] ?: map[".msg.img.$cdnmidimgurl"] // 优先大图 URL
val aesKey = map[".msg.img.$aeskey"]
```
- ⚠️ 读取落点必须用 `b1.a3(talker,msgSvrId)→r0.u`，**不要**用 R78 的 `findObjectOfType(a,this,r0)`（R79 Q3 已证 B6 探针读不到，uLen=-1 是定位失败）。

**hook 落点（唯一改动点，本轮自算）**：
```kotlin
// 最佳：在上传 task 下发前补字段。ok0/m1.e(tj/k)Z @0x7b52b4 是 j2.O 0x02dd 的下发调用，
// 也是真机 SCD m1e 探针命中的同一方法（:23/:35），改这里字段探针可直接-view 验证。
hook("com.tencent.mm.modelbase...ok0/m1", "e",
     parameterTypes = arrayOf(findCls("tj.k")), object : XC_MethodHook() {
  override fun beforeHookedMethod(p) {
    val task = p.args[0]
    if (fileId != null) setObjectField(task, "field_fileId", fileId)   // 0x026c 的来源
    if (aesKey != null) setObjectField(task, "field_aesKey", aesKey)   // 0x0284 的来源
  }
})
```
- 备选落点 A（若 m1.e 被内联/难挂）：hook `j2.O @0x2acc44` 的 `0x02dd` 前（用 `beforeHookedMethod` 拿不到 task，故需在 `ok0/m1.e` 挂，等价）——实际仍以 `m1.e` 为准。
- 备选落点 B（不推荐，牵连较大）：在 `j2.O` 前 `setObjectField(j2,"B", 源坏图原始 xml)`，让 `0x026c` 自行解析。风险：`j2.B` 还被 `f2.C2 @0x01bb`（`a.c(j2.B)`）读取，改它会影响 md5/originsourcemd5 等其它 cdnInfo 字段。

**不改动的已闭环链**：`m.a`/`q1.f`/`q1.h`/`q1.j` 三层、`listener=f2`、`field_fullpath=z3(r0.e)`、`b1.j6` 建新记录——R73/R79 已闭环，本方案不碰。

### 2.4 互斥真机判据（一次跑完定案）

| 观测（hook 本方案后重跑我方转发 216b） | 裁决 |
|---|---|
| `SCD m1e task fileId=305f...`（非空，与 A 同串）+ 对端清晰 | **(a) 成立**，派实现 |
| `SCD m1e task fileId=305f...` 非空但值 ≠ A 的串（是中图/新串）+ 对端清晰 | (a) 成立，fileId 取错来源可从 `$cdnmidimgurl` 调回 `$cdnbigimgurl` |
| fileId 仍空 | hook 落点错或 `j2.B`/r0.u 无 cdnbigimgurl ⇒ 查 xml，退 (b) |
| fileId 非空但对端仍模糊 | 服务端已无该原图（305f... 过期）或 aesKey 不匹配 ⇒ 退 (b) 复刻 B6 checkExist 重探测 |
| xml 无 `$cdnbigimgurl`/`$aeskey`（读不到） | 源 xml 损坏，无 fileId 可补 ⇒ 退 (b)/(c) |

### 2.5 纪律标注

- 纪律①：本节不因 dex 有 `cdnbigimgurl` 字符串就断言可补；可行性锚在真机 `fileId` field 值差异 + A 的"非空 fileId+36B⇒清晰"充分性证明。
- 纪律②：`补 fileId ⇒ 服务端秒传原图 ⇒ 清晰` 是**假设**（依赖服务端行为，无法本地 dex 全证）；2.4 是其变异验证/证伪方案。
- 纪律③：本问不引用单测。若派实现，须补一条"hook m1.e 后 task.field_fileId 非空"的变异验证单测（目前没有任何单测覆盖该字段写入）。
- 【限制】前提"源 xml 带 CDN 参数"仅在 stock 样本上直接读到；我方转发路径下 j2.B 是否同源同 xml 未直接 dump（推断），2.4 第一行的探针可直接坐实。

## Q3 MSG_HAS_URL 零命中裁决：(a)/(b)/(c)

### 一句话裁决

**选 (a)**——【源码确定，高置信】重建**确实发生在 `f2.C2`**（`0x021e-0x02b7` 拼 `<msg><img aeskey="..." cdnbigimgurl="...">`），但重建出的整串 `<msg>` 是**方法内局部 StringBuilder 产物**，经 `g9.c` 拆成子字段写入 protobuf 请求对象 `q54/ot5`，**从不作为 String 字段挂在 `tj.f` 或 `f2`/`j2` 上**；R79 探针扫 `tj.f` 的 String 字段自然零命中。R79 这条**不算错**（方向对），但"承载消息的对象"理解偏了。**本环不阻塞 Q2**，但机制完整性需要补探针（落点见 3.3）。

### 3.1 dex 逐指令证明重建在 f2.C2（`@0x2aa888`，本轮实跑）

| 偏移 | 指令 | 语义 |
|---|---|---|
| `0x0216/0x021e` | `const '" cdnbigimgurl="'`、`const '<msg><img aeskey="'` | 拼串骨架（**是业务调用链的一部分，非孤立字符串**） |
| `0x0226-0x026c` | `new StringBuilder` + append（`0x022d field_aesKey`、`0x0235/0x023d/0x0254 field_fileId`）→ `toString()` | **拼出完整 `<msg><img aeskey=... cdnbigimgurl=...>`，存局部变量 v9/v7** |
| `0x0270` | `invoke f2.a(String)String @0x2ab79c` | 只是往 cdnInfo 追加 `imgsourceurl`（`0x0012 ' imgsourceurl="%s" '`），返回局部串 |
| `0x02bb` | `const 'cdn callback new build cdnInfo:%s'` | 日志证实该串是"构造中的 cdnInfo" |
| `0x02e7` | `invoke-static g9.c(String,String,String)Map @0x4fd510` | **把上面拼好的串反解析为 map** |
| `0x030e` | `iput-object v13, ot5.W : String`（`$originsourcemd5`） | 子字段写入 protobuf 请求对象 `q54/ot5` |
| `0x0318-0x0411` | 从 map 取 `$cdnbigimgurl/$aeskey/$hdlength` 等 → 再拼局部串 → `f2.a()` | 多次重建（"new build"/"rebuild"/"safeCDN" 三分支，日志 `0x02bb/0x051e/0x05e1`） |
| `0x06f7-0x0716` | `iput g.E=ot5, g.G=r0, g.F=tj.f, ...` | 最终把这些装进 `modelimage/g`（发送 g 消息的对象） |

**关键**：`python _r35_sns_dis.py bodyx modelimage/f2 C2 | grep iput` 全量结果显示，字符串字段写入只有 `ot5.W` 一处，其余是 `hl0/q1`/`modelimage/g` 的对象引用——**没有任何 `iput field_content`/`r0.u` 把整串 `<msg>` 挂回可被 `tj.f`/`f2` 字段扫描读到的地方**。⇒ R79 探针读 `tj.f`/this 的 String 字段零命中是**必然**，不是"没重建"。

### 3.2 三选一排除

| 选项 | 裁决 | 依据 |
|---|---|---|
| (a) 重建在 f2.C2 更深处/别的对象，探针读的 `tj.f` 不是承载体 | ✅ **采纳** | 3.1 全链在 f2.C2 内；承载体=局部 StringBuilder + `ot5` protobuf 子字段 |
| (b) 重建在其它回调（k6/B8） | ❌ | `f2.C2` 内 `0x021e`+`g9.c`+`ot5.W` 自洽完整，无需他处；`qf4/j0.C2 @0x84307c` 是 checkExist 回调（R79 Q2），职责不同 |
| (c) R79 推断本就错、URL 非 f2.C2 重建 | ❌ | dex 明证 f2.C2 拼了带 `cdnbigimgurl` 的 `<msg>` 串；R79 方向对，仅"承载对象"表述不准 |

### 3.3 是否需要补探针 + 精确落点

- **Q2 不依赖本环**：补 fileId 的可行性判据是 `SCD m1e task fileId`（Q2 §2.4），不需要 MSG_HAS_URL。本探针只为机制完整性。
- **建议补，精确落点（本轮自算）**：
  1. **最优**：hook `g9.c(String,String,String)Map @0x4fd510` 的 `beforeHookedMethod`，读 `p.args[0]`——它就是 f2.C2 拼好的 cdnInfo xml 串（`<msg><img ... cdnbigimgurl=...>`），判断是否含 `cdnbigimgurl`。一处覆盖 f2.C2 全部三分支。
  2. 备选：hook `q54/ot5` 字段写入（本轮 `put 'Lq54/ot5;'` 可列出写入者），读 `W/J/M/K/L/R/S` 等 String 字段，判断 aeskey/fileId 是否源图值。
  3. 不可用：R79 现探针（扫 `tj.f`/this 的 String 字段）——已证零命中是对象选错。
- 【真机旁证】对照 B 的 `f2C2 fStrFields` 里确实有 `field_fileId=305f...` + `field_aesKey`（`:45`），但**没有** `<msg>` 整串——与 dex "字段里只有子字段、整串在局部"完全吻合，反向印证 (a)。

### 3.4 纪律标注

- 纪律①：本节不是"见 `<msg><img` 字符串就判重建"；是 `const-string` + `StringBuilder.append(field_*)` + `toString` + `g9.c` + `ot5.W iput` 的完整调用链，且 `bodyx ... grep iput` 全量字段写佐证承载体。
- 纪律②：`f2.C2 → 局部串 → g9.c → ot5 → 对端拉图` 的跨对象时序为 dex 推断；真机仅直接观测到 `f2C2 in/out` 与 `fStrFields` 子字段（`:27-49`），未直接 hook `g9.c`/`ot5`，故 3.3 探针用于坐实。
- 纪律③：本问不引用单测；`f2.a` 的实际行为（加 imgsourceurl 而非重建 msg）由 dex `0x0000-0x002b` 实跑确认，纠正"f2.a 重建消息"的潜在误解。

## Q4 最终路线 a/b/c（本轮最重要产出）

### 最终裁决 = **(a) 可行·最小改动：补上传 task 的 fileId/aesKey 字段**

**不硬撑整套 B6，也不草率判不可行。** 两条独立真机对照 + dex 自算已收敛出统一机制，且对照 A 直接给出 (a) 的充分性证明（非空 fileId + 36B 本地字节 ⇒ 清晰）。

- **路线**：源坏图 xml 解析 `cdnbigimgurl`/`aeskey` → hook `ok0/m1.e(tj/k) @0x7b52b4` 的 `before`，写入 `tj/k.field_fileId` + `field_aesKey`（+建议 `field_midFileLength/totalLen`）→ 下发带原图身份的上传 task → 服务端认原图 → 对端清晰。
- **精确落码规格**：见 Q2 §2.3（前提读取 `b1.a3 @0x2a36b0` → `g9.c @0x4fd510` **三参** → key `.msg.img.$cdnbigimgurl`/`$aeskey`；hook 落点 `m1.e before`；不改 `listener=f2`、`z3(r0.e)`、`b1.j6` 已闭环链）。
- **互斥真机判据**：见 Q2 §2.4（核心：`SCD m1e task fileId` 由空变非空且等于 A 的 `305f...` + 对端清晰）。

### 为什么不选 (b)/(c)

- **(b) 整套复刻 B6**：需照抄 `MsgRetransmitUI.B6 @0x62c98c` 的 checkExist task（`new tj/k`、`mq/k.B8 @0x78f618`、`listener=qf4/j0.C2 @0x84307c`、`b1.j6 @0x2a74f8` 建新记录）。改动面大，且 R79 Q2 已证"checkExist task 与上传 task 是两条、两个 listener"——复刻极易重演挂错回调。**仅作为 (a) 失败后的退路**：fileId 补上仍模糊（原图过期/aesKey 不符）或 xml 无 CDN 参数时才启用。
- **(c) 不可行**：不成立。机制 dex 全可见（每个 code_off 本轮自算）、fileId 空/非空是硬真机对照、A 的充分性证明已排除"通道不可达"。微X能做到并非因加密私有通道——fileId 对照显示差异在**"是否带原图 CDN 参数"**这一可反射写入的字段上，而非通道能力。

### (a) 的前提、风险与限制（如实标注）

- 前提：源坏图 216b 的消息 xml 仍带 `cdnbigimgurl`/`aeskey`。【真机确定】stock 样本 j2init 已读到明文 `aeskey=60af...`（`:25`）+ 对照 A `fileId=305f...`；【推断】我方路径下 j2.B 是否同源同 xml 未直接 dump，Q2 §2.4 首行探针可直接坐实。
- 风险 1：服务端 305f... 原图已过期 ⇒ fileId 非空仍模糊 ⇒ 退 (b)。
- 风险 2：aesKey 不匹配 ⇒ 一并补 aesKey；`f2.C2` 的 `createAeskey` 兜底（`0x02ad`）只保 aesKey 不空，不保与 fileId 配对。
- 风险 3：`ok0/m1.e` 若被内联/换签名 ⇒ 退到 `j2.O @0x2acc44` 的 `0x02dd` 前挂钩（验证点仍是 `SCD m1e`）。
- 【限制】"补 fileId ⇒ 服务端秒传原图 ⇒ 清晰"跨微信服务端，是**假设**；Q2 §2.4 是它的真机证伪方案，未跑前不冒充已证。

### 务实建议："如果用户只想尽快可用"（不依赖任何代码改动）

1. **立即可用（零代码）**：告知用户对该坏图走**微信原生转发**（长按图片 → 转发，不经模块）——对照 A 已证此路径肉眼清晰。这是最快、零风险的解法。
2. **低风险 UX 降级**：模块检测到"坏图"（本地仅 36B 占位/缩略图 + xml 带 cdnbigimgurl）时，弹提示"原图存于云端，建议用系统转发获得清晰图"或提供一键转原生转发，避免静默发出模糊图。
3. 这两条可与 (a) 实现并行落地；即便 (a) 最终因服务端原因失败，用户仍有可用路径——**承认模块在此场景的边界，不硬撑**。

---

## 最终结论

- **Q1**：能自洽解释——fileId 空/非空是清晰度分水岭；修正 R79 落点：fileId 由 `j2.O` 从 `j2.B`(=h9.getContent) 解析而非 B6 塞入；`UploadHitCacheType=3` 不区分图，身份由 fileId 决定。
- **Q2**：可行·最小改动——hook `ok0/m1.e` before 补 `field_fileId`/`field_aesKey` 即可，不需整套 B6；对照 A（非空 fileId+36B⇒清晰）给出充分性证明。
- **Q3**：选 (a)——f2.C2 确实重建 `<msg><img cdnbigimgurl=...>`，但整串是局部 StringBuilder + `ot5` protobuf 子字段，不挂在 `tj.f`；探针应改挂 `g9.c @0x4fd510` 入参；不阻塞 Q2。
- **最终路线**：**(a) 可行·最小改动（补 fileId 字段）**；失败退 (b) 复刻 B6；(c) 不成立。
- **务实建议**：优先让用户用微信原生转发（已证清晰），模块加"坏图建议原生转发"降级提示。
- **是否推翻 R79**：**部分推翻/修正**——推翻"B6 构造 task 时塞 fileId"与"f2.C2 重建整串挂在 tj.f"的落点表述；保留"CDN URL 透传 + 秒传"大方向、以及"上传回调真身 = modelimage/f2.C2 @0x2aa888"结论。
- **文档路径**：`docs/_r80_final_route.md`。

### 证据基线（可自证）

- dex code_off 本轮从 `_cache/dex/classes7.dex` 二进制实跑自算：`j2.O 0x2acc44`、`j2.<init>(II) 0x2ae6b4`、`f2.C2 0x2aa888`、`f2.a 0x2ab79c`、`g9.c 0x4fd510`、`B6 0x62c98c`、`ok0/m1.e 0x7b52b4`、`mq/k.B8 0x78f618`、`b1.a3 0x2a36b0`、`qf4/j0.C2 0x84307c`。关键偏移：`j2.O 0x0238-0x0284`（fileId/aesKey 写入）、`0x02dd`（下发）；`j2.<init> 0x01a2/0x01a6`（B=h9.getContent）；`f2.C2 0x021e-0x02b7`（拼 msg）、`0x02e7`（g9.c）、`0x030e`（ot5.W）、`0x06f7-0x0716`（装 g 消息）。
- 真机事实取自 `docs/_r78_stockcdn_realdev_clean.log`（对照 A，93 行）+ `docs/_r79_dualsample_realdev.log`（对照 B，91 行）；文件事实（36B/15906B）与肉眼清晰度由任务给定。
- 全程只读；唯一写操作 = 本文档；未改源码/测试/配置，未执行 adb/gradle/git，未派子代理。

[DONE]
