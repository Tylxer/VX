# R73 图片转发字段补齐（Hook/反射级改动）— 只读交叉复核（装机前第二视角）

- lane：`r73-ctx-review2`（**只读**：不改任何源码/测试/探针，不碰 adb/gradle/git；唯一写操作=本文档，分步落盘）
- 档位：Pro（Hook 链路级复核；model 按用户临时规则 hy4-flash-free，不入文件）
- 证据优先级：dex 反汇编字节（code_off + 原文）> 源码行号 > 真机日志/取证台账 > 结论文档转述
- 反汇编语料：`_cache/_r11_dex7_dis.txt`（dexdump 全量，2,814,146 行），关键类 `hl0/m`/`hl0/q1`/`MsgRetransmitUI` 均在 `classes7.dex`
- 复核对象（4 生产/测试文件 + 1 回归锁）：
  - `wxgroup/app/src/main/java/com/lx/wxgroup/feature/ForwardLogic.kt`（Snapshot 加 3 字段）
  - `wxgroup/app/src/main/java/com/lx/wxgroup/core/FileSender.kt`（send 第 5 参 + runCatching 内补齐 m.b/q1.r/q1.j）
  - `wxgroup/app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt`（snapshotOf 填值、resendFile 传第 5 参、attachForwardInfo 追加）
  - `wxgroup/app/src/test/java/com/lx/wxgroup/feature/R73ImgLocalIdFixTest.kt`（回归锁：两处断言改用 `codeLines` 判定真实签名/调用）
  - 新测试 `R73ForwardCtxTest.kt` / `R73ForwardCtxSendTest.kt`
- 被复核改动的目标：按原生 `MsgRetransmitUI.k6`(0x62d368) 字节锚点补齐 `m.b`/`q1.r`/`q1.j`，单调追加、不倒退已上线的 `m.a` 修复

## 复核结论速览

| 维度 | 结论 | 关键自证 |
|---|---|---|
| 1. 字段名/类型/时序 | **PASS** | dex field 表：`m.b=J`(8cc4)、`q1.r/j=LString`(8cf5/8ced)；k6 原文 62d534/62d4ea/62d4d6；顺序 205<208<214 |
| 2. 单调不倒退（最高红线） | **PASS** | null/默认0/""/"" 三字段全跳过、与修复前逐字节相同；m.a 序列未动；无新增更差路径；回归锁假绿已修 |
| 3. srcBigImgPath 取值鲁棒性 | **PASS**（附真机风险提示） | 坏图216b 上取值为""（15KB th_ 被 `!th_` 挡、36B 被 1KB 滤），q1.j 不覆盖、不写坏路径；有效性依赖 m.b/q1.r，仍模糊需 P3 CDN 兜底 |
| 4. NATIVE 备选链安全 | **PASS** | 默认关/真机未走；fw.a 回退原样、fw.b 值等价 fi.msgId；q1.r/q1.j 非空才写、早于 q1.a() |

## 一、字段名/类型/时序

### 1.1 字段在 dex 中真实存在且类型正确（不依赖注释，直接读 field 表）

`_cache/_r11_dex7_dis.txt`：

- `hl0/m` 类段起始 **行 1306646**，instance fields 原文：
  - `#0 name='a' type='J'`（field@8cc3，forwardFromImgId）
  - `#1 name='b' type='J'`（field@8cc4，forwardMsgId）→ **long**
  - `#2..#5 c/d/e/f = Ljava/lang/String;`
  - `<init>` 字节 `71076e [0x0007] const-wide/16 v0,#0` → `710772 [0x0009] iput-wide v0,m.b:J`：newInstance 后 m.b 默认 0。
- `hl0/q1` 类段起始 **行 1309018**，instance fields 原文：
  - `#13 name='j' type='Ljava/lang/String;'`（field@8ced）→ **引用类型 String**
  - `#21 name='r' type='Ljava/lang/String;'`（field@8cf5）→ **引用类型 String**
  - `#23 name='t' type='Lhl0/m;'`（field@8cf7，m 挂靠位）
  - `<init>` 字节 `71236c [0x000e] iput-object v0,q1.j:String`（v0=""）、`712370 [0x0010] iput-object v0,q1.r:String`：默认均为 ""。

**仲裁**：
- `m.b` → `XposedHelpers.setLongField(m,"b",ctx.srcMsgId)`（`FileSender.kt:196`）：字段为 `J`，**setLongField 正确**。
- `q1.r` → `setObjectField(q1,"r",ctx.srcTalkerPrefix)`（`FileSender.kt:200`）：字段为 `Ljava/lang/String;`，**setObjectField 正确**。
- `q1.j` → `setObjectField(q1,"j",ctx.srcBigImgPath)`（`FileSender.kt:205`）：字段为 `Ljava/lang/String;`，**setObjectField 正确**。
- 三个字段名 `b`/`r`/`j` 在对应类里**确实存在**且类型与调用 API 匹配。**PASS**。

### 1.2 原生 k6 字节锚点核对（值语义与字段一致）

`MsgRetransmitUI.k6:(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Lcom/tencent/mm/modelbase/t0;J)V` code=`0x62d368`，`_r11_dex7_dis.txt` 行 1047843 起：

| 项 | code_off | 字节原文 | 语义 |
|---|---|---|---|
| 造 m | `62d51e [0x00d3]` / `62d522 [0x00d5]` | `new-instance v1,Lhl0/m;` / `invoke-direct v1,m.<init>` | 我方 newInstance(mCls) 对齐 |
| m.a | `62d52c [0x00da]` / `62d530 [0x00dc]`(注：residual 文档记作 0x00da，实际 0x00da=move-wide、0x00dc=iput-wide) | `move-wide/from16 v2,v28` / `iput-wide v2,m.a:J`(field@8cc3) | = k6 第6参 J(imgLocalId)，**已上线修复** |
| **m.b** | `62d530 [0x00dc]` / `62d534 [0x00de]` | `iget-wide v2,MsgRetransmitUI.f:J`(field@6802) / `iput-wide v2,m.b:J`(field@8cc4) | 原生 m.b=源消息 msgId(long)；**setLongField 语义一致** |
| **q1.j** | `62d4d2 [0x00ad]` / `62d4d6 [0x00af]` | `iget-object v1,MsgRetransmitUI.g:String`(field@6804) / `iput-object v1,q1.j:String`(field@8ced) | 原生 q1.j=源图路径(String)；**setObjectField 语义一致** |
| **q1.r** | `62d4ea [0x00b9]` | `iput-object v4,q1.r:String`(field@8cf5)，v4=k6 第4参(带冒号源前缀) | 原生 q1.r=转发源前缀(String)；**setObjectField 语义一致** |
| q1.f(m) | `62d56c [0x00fa]` | `invoke-virtual {v10,v1},q1.f(Lhl0/m;)` | 三条字段写在 q1.f(m) **之前** |
| q1.a() | `62d5a8 [0x0118]` | `invoke-virtual {v10},q1.a()` | 在 q1.f(m) 之后 |

### 1.3 q1.j 覆盖是否真在 q1.a() 入队前

`FileSender.kt` 关键行号（源码行 → 动作）：

- `:187` `newInstance(mCls)` → `:189` `setLongField(m,"a",forwardFrom)`
- `:193-207` `forwardCtx?.let { ... }`：`:196` m.b、`:200` q1.r、`:205` **q1.j 大图覆盖**
- `:208` `callMethod(q1,"f",m)`
- `:211` `setIntField(q1,"h",13)`
- `:214` `callMethod(q1,"a")` ← 入队构造点

⇒ **205 < 208 < 214**：q1.j 覆盖严格发生在 `q1.f(m)` 之前、`q1.a()` 之前，与原生 k6 顺序（字段写 → q1.f → q1.a）同构。`FileSender.kt:173` 的 `q1.j=path`（缩略图）先写、`:205` 大图覆盖后写，形成「大图优先、空值退缩略图」。单测 `R73ForwardCtxSendTest.q1jBigPathOverridesThumbnail`（第 56-68 行）用 `codeLines` 锁住 `thumbIdx < overrideIdx < buildIdx` 顺序，判据真实（已核 `codeLines` 过滤注释、`:214` 为真实代码行，非注释假绿）。

NATIVE 链同理：`attachForwardInfo` 内 q1.j/q1.r 写入（`ForceForward.kt:2167/2170`）在两个调用点 `ForceForward.kt:2003→2011`、`:2105→2113` 均早于 `q1.a()`。**PASS**。

**维度 1 裁决：PASS**（字段名/类型/时序三项全部自证，无 UNKNOWN）。


## 二、单调不倒退（最高红线）

### 2.1 三条门控逐条核对（取不到值 ⇒ 严格等同修复前）

`FileSender.send()` 新增第 5 参 `forwardCtx: ForwardLogic.Snapshot? = null`（`FileSender.kt:132`），默认 null。**所有生产调用点只有一个**：`ForceForward.resendFile` 的 `FileSender.send(kind, target, snap.localPath, snap.imgLocalId, snap)`（`ForceForward.kt:2232`，已 grep 全仓确认唯一）。其余潜在调用点因默认参数不受影响。

门控块 `FileSender.kt:193-207`：

| 字段 | 写法 | 取值条件 | 取不到时行为 | 门控是否足够 |
|---|---|---|---|---|
| m.b | `if (ctx.srcMsgId > 0L) setLongField(m,"b",srcMsgId)` | `>0` | 不写 ⇒ m.b 保持 newInstance 默认 0（=修复前默认） | ✅ |
| q1.r | `if (ctx.srcTalkerPrefix.isNotBlank()) setObjectField(q1,"r",...)` | 非空 | 不写 ⇒ q1.r 保持 `<init>` 默认 "" | ✅ |
| q1.j | `if (ctx.srcBigImgPath.isNotBlank()) setObjectField(q1,"j",...)` | 非空 | 不写 ⇒ q1.j 保持 `:173` 已写的 `path`（缩略图）=修复前值 | ✅ |

- **外层 `forwardCtx?.let`**：传 null ⇒ 整块跳过，三字段全不写，与修复前逐字节相同。✅
- **内层三 `if` 门控**：即便传了非 null 的 `snap`，只要三字段是安全默认（`srcMsgId=0` + 两空串，`ForwardLogic.kt:403-405`），三项也全部跳过。✅
- **每项独立 `runCatching` + onFailure 日志**：任何一项反射失败都不中断其余项、不抛出宿主（`:195-197/:199-201/:204-206`）。✅

⇒ **取不到值时的写入行为严格等同修复前，门控充分。PASS**。

### 2.2 m.a 修复未倒退（不变量核对）

- `FileSender.kt:188` `val forwardFrom = if (imgLocalId > 0L) imgLocalId else -1L` 与 `:189` `setLongField(m,"a",forwardFrom)` **原样未动**，本次只在 `:191-207` 追加字段，`m.a` 序列一字不改。
- `Snapshot.imgLocalId` 默认值仍 `-1L`（`ForwardLogic.kt:397`），`R73ForwardCtxTest.snapshotDefaultsKeepNewFieldsSafe` 断言 `imgLocalId == -1L`（第 57 行）。
- `q1` 字段序列其余项（`:170-178` i/g/h/j/k/l/n/p/o、`:211` h=13、`:208` q1.f、`:214` q1.a、g6/l1.a）全部保留。回归锁 `R73ImgLocalIdFixTest.fileSenderKeepsRestOfFieldSequence`（第 105-112 行）+ `R73ForwardCtxSendTest.redLinesUntouched`（第 100-121 行）用 `codeLines` 锁定，已核断言串均为真实代码行。

### 2.3 是否存在「写入错误正数/错误路径，比旧行为更差」的路径

逐条排查（结论：无新增劣化路径）：

1. **m.b 写错误正数**：srcMsgId 来自 `ref?.msgId ?: 0L`（`ForceForward.kt:1333`），与已上线的 `imgLocalId` 同源于「长按冻结 ref」(`resolveRefForBurst`)。若 ref 误选到别的消息，srcMsgId 会错——但**这是 R73-A1 冻结 ref 的既有风险，imgLocalId/m.a 早已依赖同一 ref**，本次没有引入新的取值源。不属于本次改动新增的劣化，标为「同源既有风险，非本改动回归」。
2. **q1.j 写错误路径**：srcBigImgPath 取值有 `!th_` 前缀 + 扩展名 + 1KB 有效文件三重门控（详见维度三），坏图 216b 上取值为 ""（不写），**不会把 36B 坏占位写进 q1.j**。✅
3. **q1.r 写错前缀**：srcTalkerPrefix 来自 `ref?.talker`（h9.field_talker）+"尾冒号"，与原生 k6 q1.r=带冒号源前缀语义一致；取不到为 ""（不写）。srcTalker 为空/已带冒号均有处理（`ForceForward.kt:1337-1341`），无重复冒号、无空串误写。✅
4. **NATIVE 链 fw.b**（见维度四）：值等价于旧 fi.msgId，无劣化。

⇒ **monotonicity 成立，无「比旧行为更差」的新路径。PASS**。

### 2.4 回归锁自身有效性（「历史签名注释假绿」修复复核）

任务背景指出 `R73ImgLocalIdFixTest` 两处断言此前被「历史签名」注释骗过（假绿），本轮已改用 `codeLines` 判定真实代码行、注释已删。复核：

- `fileSenderSendSignatureCarriesImgLocalId`（第 61-73 行）：`codeLines(source(FILE_SENDER))` 后断言 `"imgLocalId: Long = -1L"` 与 `"forwardCtx: ForwardLogic.Snapshot? = null"` 在代码行中。源码 `FileSender.kt:131-132` 为真实签名行（非注释），命中真实。✅
- `forceForwardFeedsBothChains`（第 136-154 行）：`codeLines(src).any { "FileSender.send(kind, target, snap.localPath, snap.imgLocalId, snap)" in it }`，源码 `ForceForward.kt:2232` 为真实调用行。✅
- `codeLines`（`R73ImgLocalIdFixTest.kt:206-209`）过滤 `//`、`*`、`/*` 起始行——能滤掉注释假绿。全仓 grep 确认旧 4 参签名 `FileSender.send(kind, target, snap.localPath, snap.imgLocalId)` 仅存于注释/已删，无残留可命中代码行。✅

**维度 2 裁决：PASS**（单调红线守住，无更差路径，回归锁假绿已修复且判据真实）。


## 三、srcBigImgPath 取值鲁棒性

### 3.1 取值链（源码行号自证）

`srcBigImgPath` 在 `ForceForward.snapshotOf`（`ForceForward.kt:1342-1351`）：

```
val bigExt = setOf("jpg","jpeg","png","webp","gif","bmp","heic","heif")
val srcBigImgPath = if (
    kind == Kind.IMAGE &&
    path.isNotBlank() &&
    !File(path).name.startsWith("th_") &&
    path.substringAfterLast('.', "").lowercase() in bigExt
) path else ""
```

其中 `path`（`:1322-1324`）= IMAGE 且 rawPath 非空时 `resolveImagePath(rawPath)` 的结果，否则 rawPath。`resolveImagePath`（`:1166-1237`）经 `ForwardLogic.pickImageFile(pairs, md5)`（`ForwardLogic.kt:341-366`）挑选，其 `valid = files.filter { bytes >= MIN_VALID_IMAGE_BYTES }`（`:345`，阈值 1024B，`:318`）**先滤掉 36B 坏占位**，再按「大图(非 th_, 图片扩展名, 最大) → HD 缩略图 → 缩略图兜底」三级挑选。

⇒ 三重门控：**① 1KB 有效文件过滤（pickImageFile）→ ② `!th_` 前缀 → ③ 图片扩展名**。36B `<md5>.jpg` 坏占位在 ① 就被滤掉，即便漏过也会被 ② 的 `!th_` / 扩展名门控再挡。单测 `R73ForwardCtxSendTest`（第 86 行）锁定 `!File(path).name.startsWith("th_")` 条件存在；`pickImageFile` 的 1KB 过滤由 `ForwardLogic.kt:318/345` 源码 + `docs/_r73_image_path_forensics.md` §4.3 双重佐证。

### 3.2 坏图 216b1f7ccd 上实际会取到什么值（逐字节/日志推演）

取证实事（不改，来自台账与取证文档）：

- 坏图 `field_imgPath` = 虚拟 URI `THUMBNAIL_DIRPATH://th_216b1f7ccd59b1268349fd0eb3c46801`（`_r73_image_forward_frozen_miss_forensics.md:336`）⇒ `resolveImagePath` 不走 step1 直接路径分支（`:1172` 要求 `startsWith("/")`），走 md5 分桶。
- 桶 `image2/21/6b` 内容：`th_<md5>hd⌖` 36B（坏占位）、`<md5>.jpg⌖` 36B（坏占位）、`th_<md5>` 15906B（真缩略图）、**无有效大图 .jpg**（`docs/派发审计台账.md:5121/5142`）。
- 真机 1KB 修复版日志：`FORWARD resolveImage md5=216b1f7ccd bucket=21/6b hit=11 pick=th_216b1f7ccd...46801 exists=true`（台账 5135）⇒ `pickImageFile` 滤掉两个 36B 文件，tier-1(大图)未命中、tier-2(HD)未命中、tier-3 取 15KB `th_<md5>`。

推演 `srcBigImgPath`：

1. `path` = `/.../image2/21/6b/th_216b1f7ccd...46801`（15KB 缩略图绝对路径）
2. `kind=IMAGE` ✓、`path.isNotBlank()` ✓
3. `!File(path).name.startsWith("th_")` ⇒ 文件名为 `th_216b...`，**startsWith("th_")=true ⇒ 条件为 false**
4. ⇒ **`srcBigImgPath = ""`**

⇒ 在坏图 216b 上，`srcBigImgPath` 取值为**空串**，`FileSender.kt:204` 的 `isNotBlank()` 门控为 false ⇒ **q1.j 不被覆盖，保持 `:173` 已写的 15KB 缩略图路径**。这与修复前一致，**不会把 36B 坏占位或任何错误本地路径写进 q1.j**。三重门控对坏图有效。**PASS**。

### 3.3 与 residual 文档「最大风险」的对照（重要澄清）

`_r73_ma_residual.md` §五曾标注「最大风险：q1.j 改指大图路径，但坏图本地无大图（36B 占位），若 j2 仍 `o6.l(q1.j)` 读本地文件，可能读 36B 失败」。**本次实现的取值纪律比该最坏假设更安全**：

- 36B `<md5>.jpg` 在 `pickImageFile` 的 1KB 过滤下**根本不会被选中**；
- 即便某图有 >1KB 的非 th_ 大图文件被选中，那也是真实大图（40~194KB），写入 q1.j 是正确行为；
- 坏图这种「本地无有效大图」的情形，`srcBigImgPath=""` ⇒ q1.j 回退缩略图（15KB），**不指向 36B 坏文件** ⇒ 不触发「读 36B 失败」。

⇒ 36B 坏文件写进 q1.j 的风险在本实现下**不成立**。

### 3.4 真机风险提示（诚实边界）

1. **有效性风险（非正确性 FAIL）**：对坏图 216b，因本地无有效大图，`srcBigImgPath=""` ⇒ q1.j 补齐这一项对该图是 **no-op**。该图能否变清晰，取决于 `m.a`（已上线）+ 本次新增的 `m.b`/`q1.r` 是否足以让 j2 走服务端引用；若装机后 P2（人眼）仍模糊，按 `_r73_ma_residual.md` §五 P3 需转 CDN 下载/服务端引用兜底（`b1.R5` / `b1.z3 SERVERID://→Y2`，多目标 R5 兼容性仍 UNKNOWN，另开取证）。**这是修复有效性风险，不是字段写错的正确性风险。**
2. **UNKNOWN（理论边角，非阻断）**：`resolveImagePath` step1 直接路径分支（`:1172-1175`）对 `startsWith("/") && File.exists()` 的绝对路径**不校验大小/th_ 前缀**。若某条消息的 `field_imgPath` 恰为指向 36B 坏占位 `<md5>.jpg` 的绝对路径，则 `srcBigImgPath` 会取到该坏路径并写入 q1.j（因文件名非 th_、扩展名 jpg）。本 lane 未在生产日志中发现此类 `field_imgPath`（目标坏图为虚拟 URI，已证），且该分支是 `resolveImagePath` **既有行为**（早已用于 `snap.localPath`），非本次 q1.j 补齐新引入。标记 UNKNOWN，交真机留意；不构成装机阻断。
3. 虚拟 URI / 非源图路径：`resolveImagePath` 解析失败返回 null ⇒ `:1323` 保留原 URI 作 `path`，此时 `!th_`（虚拟 URI 常含 th_）+ 扩展名门控通常使其为空串；即便非空（`THUMBNAIL_DIRPATH://th_xxx` 不含 `.jpg` 结尾），扩展名门控也挡住。无「非源图路径误写 q1.j」通道。

**维度 3 裁决：PASS**（坏图上 srcBigImgPath=""、不写 q1.j、不引入 36B 坏路径；附有效性风险与一处非阻断 UNKNOWN）。


## 四、NATIVE 备选链安全

### 4.1 该链默认关闭、本次真机未走（前提确认）

`ForceForward.nativeState()`（`ForceForward.kt:1885-1899`）注释 + 代码明确：`if (!Kv.getBool(Keys.FORWARD_NATIVE)) return NativeState.UNKNOWN`——**默认关**，开法需 `kvset chat.forward_native true` + 重启微信。真机本次走的是 RESEND_FILE 降级链（`wxg-forward`），未走 NATIVE。⇒ 该链追加字段的**真机爆炸半径为零**（除非用户手动开启）。这是一层天然安全垫。

### 4.2 attachForwardInfo 追加三项逐条核对

`ForceForward.kt:2145-2172`：

| 项 | 写法 | 分析 | 结论 |
|---|---|---|---|
| fw.a | `if (snap.imgLocalId>0L) snap.imgLocalId else fi.fromImgId`（`:2152`） | **未改动**，仍沿用已上线 m.a 修复；取不到回退 fi.fromImgId(-1) | ✅ 不破坏回退 |
| fw.b | `if (snap.srcMsgId>0L) snap.srcMsgId else fi.msgId`（`:2156`） | **值等价**：`fi.msgId = snap.msgId`（`ForwardNativeLogic.kt:128`），而 `snap.msgId = ref?.msgId ?: 0L`（ForceForward:1364）= `srcMsgId`（:1333）。两分支写的是同一个值 ⇒ 与原 `fi.msgId` 逐字节相同 | ✅ 无行为变化 |
| q1.r | `if (snap.srcTalkerPrefix.isNotBlank()) runCatching{ setObjectField(q1,"r",...) }`（`:2166-2168`） | 非空才写，空回退默认 ""；独立 runCatching | ✅ 单调 |
| q1.j | `if (snap.srcBigImgPath.isNotBlank()) runCatching{ setObjectField(q1,"j",...) }`（`:2169-2171`） | 非空才写，覆盖 nativeForwardImage `:2096` 的 q1.j=snap.localPath（缩略图）；空回退缩略图；独立 runCatching | ✅ 单调 |

- **原有 fw.a/fi.fromImgId 回退未被破坏**：fw.a 一行原样保留（`:2152`），`ForwardNativeLogic.forwardInfoOf` 纯函数 `fromImgId=NO_IMG_ID=-1` 未改（`ForwardNativeLogic.kt:126-127`，回归锁 `R73ImgLocalIdFixTest.noRedLineChanges` 第 186 行锁 `"fromImgId = NO_IMG_ID"`）。
- **fw.c/d/e/f 原字段序列未动**：`:2158-2161` 仍按原顺序写 snsLocalId/uuid/svrId/talker，追加只在其后。
- **时序**：两处调用点 `ForceForward.kt:2003→2011`（native text）、`:2105→2113`（native image）均在 `attachForwardInfo` 后、各自 `q1.a()` 前 ⇒ q1.j 覆盖生效（与维度一同构）。
- **值来源**：srcTalkerPrefix/srcBigImgPath/srcMsgId 与 FileSender 主链同源（同一 snap），字段语义一致；srcBigImgPath 在坏图上同样为 ""（维度三），NATIVE 链 q1.j 亦不覆盖、不写坏路径。

⇒ NATIVE 链追加**单调、值等价/安全、时序正确、不破坏原回退**。**PASS**。

### 4.3 次要观察（非阻断）

- q1.r 对 **native text** 链（`nativeForwardText`）也会被写入（srcTalkerPrefix 对所有 kind 计算，非仅 IMAGE）。这是新行为，但语义上 q1.r=转发源前缀对文本转发同样正确（原生 k6 也是带冒号源前缀），且该链默认关、真机未走 ⇒ 判为无害。如需零差异，可考虑把 q1.r/q1.j 写入限定 IMAGE，但**不构成装机阻断**。

**维度 4 裁决：PASS**（默认关 + 真机未走的双保险下，追加安全、fw.a 回退与 fw.b 值等价均守住）。


## 五、总体裁决

### 逐维结论

| 维度 | 裁决 | 是否影响正确性/单调性 |
|---|---|---|
| 1. 字段名/类型/时序 | PASS | 否 |
| 2. 单调不倒退（最高红线） | PASS | 否 |
| 3. srcBigImgPath 取值鲁棒性 | PASS（附真机风险提示） | 否（坏图取空串、不写坏路径） |
| 4. NATIVE 备选链安全 | PASS | 否 |

**无 FAIL 项；没有任何一条影响正确性或单调性。**

### 总体裁决：**可装机**

理由（全部可自证）：

1. **字段名/类型/时序**——`m.b=J`、`q1.r/q1.j=LString` 由 dex field 表（行 1306646/1309018）+ 原生 k6 字节原文（62d534/62d4ea/62d4d6）钉死，API 调用匹配；q1.j 覆盖在 q1.a() 前（205<208<214）。
2. **单调不倒退（红线）**——`forwardCtx=null` 或默认 0/""/"" 时三字段全不写、逐字节等同修复前；m.a 序列未动；无新增「错误正数/错误路径」劣化路径；回归锁假绿已修且判据真实。
3. **鲁棒性**——坏图 216b 上 `srcBigImgPath=""`，q1.j 不覆盖、不写 36B 坏路径；比 residual 文档最坏假设更安全。
4. **NATIVE 链**——默认关 + 真机未走双保险；fw.a 回退原样、fw.b 值等价；追加单调、时序正确。

### 装机附带说明（不影响裁决，交 team-lead 真机验收时留意）

- **有效性而非正确性风险**：坏图 216b 本地无有效大图 ⇒ q1.j 补齐对该图为 no-op，能否清晰取决于 m.a + m.b/q1.r；若装机后 P1(str 三项对齐)/P2(人眼清晰)仍不达标，按 `_r73_ma_residual.md` §五 P3 转 CDN 下载/服务端引用兜底（`b1.R5`/`b1.z3`，多目标 R5 兼容性 UNKNOWN，另开取证）。
- **非阻断 UNKNOWN**：`resolveImagePath` 直接路径分支（ForceForward.kt:1172-1175）不校验文件大小，若 `field_imgPath` 为指向 36B 坏占位的绝对路径（本 lane 未在目标坏图见到，目标为虚拟 URI）则可能取到坏路径；系既有行为非本次新引入，真机留意即可。
- **未执行项（纪律）**：本 lane 全程只读，未改源码/测试/探针、未碰 adb/gradle/git、未跑单测；变异有效性与真机判据交 team-lead 统一执行。

`[DONE]` R73 图片转发字段补齐只读交叉复核（装机前第二视角）完成。四个维度逐条 PASS/FAIL + 可自证锚点（dex 字节 code_off + 字节原文 / 源码行号 / 真机日志）已落盘；总体裁决 = **可装机**；唯一写操作=本文档，未碰任何源码/测试/探针/adb/gradle/git。

