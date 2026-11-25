# R86c D3 dex 静态反汇编取证 + 探针落码规格设计

## 背景
R86b 最小步：自建 checkExist task（`tj/k`，12 字段，**无 field_fullpath**），经 `mq/k.B8` 下发，`Ltj/i` Proxy 的 C2 回调读到 `exist=false md5Len=0`（未命中原图）→ no-go 裁决。头号根因 Q1①【推断·高，未坐实】：自建 task 缺 `field_fullpath` / `field_enable_hitcheck` / `field_fake_bigfile_signature`，mars 侧不具备命中条件。本轮任务 = dex 静态反汇编坐实/否证，并设计探针落码规格。

## 工具/方法
- **只读脚本**（均在工作区根目录）：
  - `_r35_adnd_dex.py`——`cls`（定位类所在 dex）/`calln`（方法表 + 调用者）/`fields`（字段表）。**注意**：任务描述里的别名 `meth` 在本仓实际模式名是 `calln`；`_r27_disasm.py`/`_r9_cdn.py`/`_r4_dexref.py` 在根目录**不存在**（已 `ls` 核实），实际反汇编主力是 `_r35_sns_dis.py`。
  - `_r35_sns_dis.py`——`bodyx <类名子串> <方法名>`（全指令反汇编，含未识别 opcode 的 `op/u0/u1/u2`）、`fields <类描述符>`（含父类链字段）、`put/get/call`（全 dex 扫读写/调用）。
  - 本轮新建 `_r86c_off.py`（自算 code_off/regs/ins/insns），`_r86c_m1m.txt`/`_r86c_B6.txt`/`_r86c_x0u.txt`（反汇编落盘存档）。
- **偏移口径（关键，历史踩坑点）**：`_r35_sns_dis.py body[x]` 打印的 `0xXXXX` 是**指令索引 i（code unit）**，不是字节偏移；**绝对字节偏移 = code_off + 16 + 2·i**。本文凡写 `i=0x00a4` 均为指令索引，凡写 `code_off=0x…` 为 dex 方法体起始字节偏移，二者不混用。
- **类所在 dex（本轮自己 `cls` 扫全 15 dex 核实，不照抄历史）**：
  - `ok0/m1` → **classes7.dex**（历史线索曾写 classes13，**已纠正**）；`ok0/x0`、`CdnLogic$C2CUploadRequest` → classes13.dex。
  - `tj/k`、`mq/k`、`Ltj/i`、`Ltj/f`、`MsgRetransmitUI`、`modelimage/r0` → classes7.dex；`g9/c` → classes11.dex。
- **自算 code_off（`_r86c_off.py` 实跑）**：
  | 方法 | dex | code_off | regs/ins/insns |
  |---|---|---|---|
  | `ok0/m1.e(Ltj/k;)Z` | classes7 | `0x7b52b4` | 7 / 2 / 83 |
  | `ok0/m1.m(Z)V` | classes7 | `0x7b6700` | 43 / 2 / 1235 |
  | `ok0/x0.u(Ltj/k;)I` | classes13 | `0x3a98cc` | 7 / 2 / 268 |
  | `MsgRetransmitUI.B6(…)V` | classes7 | `0x62c98c` | 28 / 4 / 544 |
  | `mq/k.B8(Ltj/k;)Z` | classes7 | `0x78f618` | 3 / 2 / 9 |
  - 由此：m1.m 指令 i 的绝对字节偏移 = `0x7b6710 + 2·i`；x0.u = `0x3a98dc + 2·i`；B6 = `0x62c99c + 2·i`；m1.e = `0x7b52c4 + 2·i`。
- **标注纪律**：【dex 实证】=给 dex+指令 i（+绝对偏移）；【推断·高/中/低】；【UNKNOWN 未闭合】。dex 有字符串 ≠ 业务调用；「A 推 B」只认 invoke 地址递增/真机时间戳，否则标【假设】；零命中如实记。

---

## Q1（D3 核心）ok0/m1.m 控制流

### 结论（一句话）
**【dex 实证】`field_fullpath=null` 且 `field_fileId` 非空时，控制流落到 `ok0/x0.u`（上传/秒传链），不是 download 分支；分派判据是 `task.e`（isSend，恒 true）与 `task` 的运行时类型 `Ltj/k`（非 `Ltj/m`），与 `field_fullpath` 是否为空无关。** 设计 §D3 担心的"无 fullpath 静默走错分支"**未发生**：fullpath 的 null 检查只用于打日志取长度，且随后被规整为空串 `""`，不影响分派。补 fullpath 对"能否进入 x0.u"这个问题**不是必要条件**——这条链已经进 x0.u。

### 证据链（指令级）
1. **下游下发点强制置 `e=true`**【dex 实证】，classes7 `ok0/m1.e(Ltj/k;)Z`，code_off=0x7b52b4：
   - `i=0x001c` `iget-object v0, v6, Ltj/k->field_fileId`；`i=0x0020` `if-nez v0,+4`（非空跳过清空）→ 印证 R82：fileId 非空被保留。
   - **`i=0x002a` `const/4 v0,#1`（u0=0x1012）→ `i=0x002b` `iput-boolean v0, v6, Ltj/k->e:Z`（u0=0x0112, u1=0x0006=v6）**：`e` 被无条件置 `1`。绝对偏移 `0x7b52c4+2·0x2a=0x7b5318`、`…0x2b=0x7b531a`。
   - ⇒ 凡经 `mq/k.B8`（classes7 co=0x78f618，3 指令纯 delegate 到 `m1.e`）下发的 task，`e` 恒为 true。

2. **m1.m 的分派 if 链只看 `e`**【dex 实证】，classes7 `ok0/m1.m(Z)V`，code_off=0x7b6700：
   - `i=0x0059` `if-nez v6,+9`（task 非空守卫）；`i=0x0094` `iget-boolean v7, v6, Ltj/k->e:Z`（**载入 e 到 v7**）。
   - **`i=0x00a0` `if-eqz v7, +0x0101`**（op=0x38，u0=0x0738，u1=0x0101 → 目标 i=0x00a0+0x0101=**0x01a1**）。即 `e==false` 才跳 0x01a1（下载链）；自建 checkExist task 的 `e=true` ⇒ **不跳，顺序落到 0x00a2 起的上传链**。绝对偏移 `0x7b6710+2·0xa0=0x7b6850`。
   - `i=0x00a0` 之前还有 `i=0x008c sget-boolean a8.a`、`i=0x008e…`，但那是日志/调试开关，不是分派。

3. **`field_fullpath` 的 null 检查不参与分派，只用于日志**【dex 实证】：
   - `i=0x00a4` `iget-object v9, v6, Ltj/k->field_fullpath`；`i=0x00a6` `if-nez v9,+4`（非空跳过）；空则 `i=0x00a8 const/4 v9,#0`、`i=0x00aa String.length()`、`Integer.valueOf` —— **这是给 `l2.j` 日志 `"summersafecdn tryStart send file:%d thumb:%d…"`（i=0x00f0）填长度参数**，空串走长度 0，非空走真实长度，两条路都汇合到 0x00b4（thumbpath 同样处理）继续往下。
   - **`i=0x00f6-0x0102`**：`iget fullpath`→`if-nez`→`iput-object v9(""), fullpath`（i=0x00fc）；thumbpath 同（i=0x0102）。即 **null fullpath 在此被改写成空串 `""`**，不是拿 null 去分派，也不是抛 NPE。
   - 结论：fullpath 为 null 与否，**不改变任何一个分派跳转**，只影响日志数字和 x0.u 内 `setFilePath` 收到的实参（null 已被换成 ""）。

4. **终点确认：上传链在 i=0x0129 调 x0.u**【dex 实证】：
   - `i=0x0125` `invoke-static ok0/s2.c9()→ok0/x0;`；**`i=0x0129` `invoke-virtual ok0/x0.u(Ltj/k;)I`**（绝对偏移 `0x7b6710+2·0x129=0x7b6962`），返回值入 v11（i=0x012c `move-result`）。
   - 这是 i=0x00a0 不跳后的顺序落点，中间 0x00a2–0x0128 全是日志采样、ConcurrentHashMap 去重（`field_mediaId` 作 key，i=0x0108 `put`/0x0110 `get`）、idkeyStat 上报（i=0x0122），**无任何 jump 到下载链**。

5. **旁证：下载链长什么样（i=0x01a1 起）**【dex 实证】：
   - `i=0x01ad` `instance-of v6, Ltj/m;` → `i=0x01af if-eqz +0x60`（非 `Ltj/m` 跳走）；命中后 `i=0x01bb new-instance C2CDownloadRequest`、`i=0x01c6 setSavePath`、`i=0x0208 CdnLogic.startSNSDownload`。即 download 分支要求 task 是 `Ltj/m` 类型，而 checkExist task 是 `Ltj/k`（见 Q3 构造点 `new Ltj/k`）⇒ 即使误入也类型不匹配。

6. **反证 survey：m1.m 全程不读 `field_fileId`**【dex 实证 · 零命中】：对 `_r86c_m1m.txt` 全量 grep `field_fileId` = 0 命中。m1.m 读的 field 仅为 `mediaId/fullpath/thumbpath/svr_signature/aesKey/fileType/onlycheckexist/largesvideo/appType/chattype` 等（见 0x0062–0x0e8、0x01c0–0x0… 各 iget）。⇒ 「fileId 非空」在 m1.m 层根本不是条件；它被读是在 **x0.u**（i=0x001b → forwardFileid，见 Q2）和 m1.e（清空守卫）里。

### 置信度与边界
- 分派结论 =【dex 实证·高】：if 条件（e）、跳转目标（0x01a1）、终点（0x0129 x0.u）、fullpath null 的两处处置均有指令与绝对偏移，闭合。
- 设计 §D3 原命题"确认 fullpath=null 落到 x0.u 而非 download"——**本轮坐实为真，但理由与设计预期不同**：不是"fullpath 参与分派并正确导向 x0.u"，而是"fullpath 根本不参与分派（只被日志读+规整为空串），分派由 e=true 与 Ltj/k 类型决定，因此必然到 x0.u"。
- 【UNKNOWN 未闭合】本轮未反汇编 `ok0/h1.run`/`m1.q/r` 的队列细节；B8→m1.e→h1→m1.m 的线程时序为 R82/Q1 既有【推断】，本文沿用不改判。但这不影响"落到 x0.u"的结论——它是 m1.m 方法体内的控制流，与入口时序无关。
- 对"补 fullpath 能否救活原路线"的直接含义：补 fullpath **不会改变分支**（本来就进 x0.u）；fullpath 的作用在 x0.u 的 `setFilePath`（Q2）与 mars hitCheck（R82 Z.3），属"命中条件"而非"分派条件"。⇒ 见文末三件套②。

`[DONE Q1]`

---

## Q2（探针落码规格）ok0/x0.u(tj/k)I 入参字段可读性

### 结论（一句话）
**【dex 实证】hook `ok0/x0.u(Ltj/k;)I` 的 before，`param.args[0]` 就是 task（寄存器 v6），可直接读到全部 6 个 task 字段；但 `C2CUploadRequest` 是 x0.u 方法内的**局部变量 v0**，before 时尚未构造、after 时也不是返回值/参数——**仅 hook x0.u（含 after）拿不到 request 对象**，必须改为 hook native 入口 `CdnLogic.startC2CUpload`/`startSSUpload` 的 before 才能读 request 的 `filePath/forwardFileid/enableHitCheck/checkExistOnly`。**

### 证据链

#### 2.1 入参 task = v6 = param.args[0]【dex 实证】
- `ok0/x0.u` classes13 co=0x3a98cc，`regs=7 ins=2 outs=3`：实例方法 `ins=2` = this(v5)+1 参；参数 `Ltj/k` 落在 **`v6`**（= 7−1）。Xposed hook before 时 `param.args[0]` 即该 `Ltj/k` task。

#### 2.2 task 六字段全部可读（before 直接 iget 自 v6）【dex 实证】
逐条来自 `_r86c_x0u.txt`（`i` 指令索引，绝对偏移 = 0x3a98dc+2·i）：

| 探针要读字段 | x0.u 中的读取指令（源 v6=task） | 绝对字节偏移 | before 可读 |
|---|---|---|---|
| `field_fullpath` | `i=0x0009 iget-object v1,v6,Ltj/k->field_fullpath` → `i=0x000b setFilePath(v1)` | 0x3a98ee | ✅ |
| `field_fileId` | `i=0x001b iget-object v1,v6,Ltj/k->field_fileId` → `i=0x001d iput forwardFileid` | 0x3a98f6 | ✅ |
| `field_fileType` | `i=0x0013 iget v1,v6,field_fileType` → `i=0x0015 iput fileType` | 0x3a98ea | ✅ |
| `field_enable_hitcheck` | `i=0x0085 iget-boolean v2,v6,field_enable_hitcheck` → `i=0x0087 iput enableHitCheck` | 0x3a9942 | ✅ |
| `field_onlycheckexist` | `i=0x0063 iget-boolean v2,v6,field_onlycheckexist` → `i=0x0065 iput checkExistOnly` | 0x3a9902 | ✅ |
| `field_fake_bigfile_signature` | `i=0x00a2 iget-object v2,v6,field_fake_bigfile_signature` → `i=0x00a4 iput fakeBigfileSignature` | 0x3a9980 | ✅ |

- 六个字段**全为 `iget* v*, v6, Ltj/k->…`**，无一个是经方法调用/反射间接取得 ⇒ XposedHelpers `getObjectField/getBooleanField/getIntField(param.args[0], 字段名)` 可直读，字段名即表内 dex 字段（已由 `tj/k` 字段表核对存在，见 Q3）。
- 另可读旁证字段：`field_aesKey`→forwardAeskey(i=0x0017)、`field_thumbpath`→setThumbfilePath(i=0x000e)、`field_midimgpath`→setMidimgPath(i=0x0091)、`field_svr_signature`→bigfileSignature(i=0x0096)、`field_talker`→toUser(i=0x0027)、`field_sendmsg_viacdn`→sendmsgFromCDN(i=0x002f)、`field_bzScene/appType/needStorage`→bizid/apptype(i=0x0040–0x0061)。
- **重要负值证据（零命中）**：x0.u 全程**不读 `field_filemd5`**（`_r86c_x0u.txt` grep `filemd5` 零命中），印证 R82 Z.3：请求侧 md5 由 mars 从 `setFilePath` 字节重算。

#### 2.3 C2CUploadRequest 构造点与可见性【dex 实证】
- **构造**：`i=0x0000 new-instance v0, CdnLogic$C2CUploadRequest`；`i=0x0002 invoke-direct <init>()V`。request 存于**局部变量 v0**。
- **字段写入映射**（v0 = request，值来自 v6 = task）：
  - `i=0x0007 fileKey←field_mediaId`；`i=0x000b setFilePath(field_fullpath)`；`i=0x0010 setThumbfilePath`；`i=0x0015 fileType←field_fileType`；`i=0x0019 forwardAeskey←field_aesKey`；`i=0x001d forwardFileid←field_fileId`；`i=0x0029 toUser←field_talker`；`i=0x0031 sendmsgFromCDN←field_sendmsg_viacdn`；
  - `i=0x0065 checkExistOnly←field_onlycheckexist`；`i=0x0087 enableHitCheck←field_enable_hitcheck`；`i=0x0098 bigfileSignature←field_svr_signature`；`i=0x00a4 fakeBigfileSignature←field_fake_bigfile_signature`；`i=0x00b0 fakeBigfileSignatureAeskey←field_fake_bigfile_signature_aeskey`。
- **可见性判定**：x0.u 返回 `I`（`i=0x0106/0x010b return v6`，v6 此处是 native 返回码，非 request）；request 从未 `iput` 到 this、从未作为入参传出、从未进 `param`。⇒ **before 时 v0 尚未 new；after 时 v0 是栈上局部变量，hook 回调拿不到**。结论：**不能靠 hook x0.u（含 after）读 request**——这一点与 R82 §Q4.2/P2 的判断一致。

#### 2.4 可读 request 的正确落点：native 入口【dex 实证 + 推断·中】
- x0.u 末尾两条分支：
  - `i=0x00fe iget-boolean v6,v0,sendmsgFromCDN` → `i=0x0100 if-eqz +7` → 真：`i=0x0102 invoke-static CdnLogic.startSSUpload(C2CUploadRequest, SessionCallback, UploadCallback)I`；
  - 假：`i=0x0107 invoke-static CdnLogic.startC2CUpload(C2CUploadRequest, UploadCallback)I`。
- 两个 native 入口的 `param.args[0]` **就是上面构造的 request**（invoke-static 第一参 v0=request）⇒ hook 它们 before 可读 request 全字段。
- 【推断·中，需真机验】`CdnLogic.startC2CUpload/startSSUpload` 是 `static native`；Xposed 对 native 声明方法的 Java 层 hook 通常可行（方法在 Java 层有声明），但**本轮只读取证无法证实真机 hook 是否生效**——标注为需实现 lane 真机验证。R82 H4/H5 与 P2 已按此范式给过探针规格，本文沿用。

### 可直接落码的 XposedHelpers 读法（给 Flash lane）

```kotlin
// —— 探针 A：hook ok0/x0.u before，读 task（param.args[0] = Ltj/k）——
// findMethod: XposedHelpers.findMethodExact("ok0.x0", loader, "u", <tj/k 的 Class>)
//   —— 因 Ltj/k 是宿主类，用 findClass("tj/k", loader) 取 clazz 作为签名参数：
val kCls = XposedHelpers.findClass("tj/k", lpparam.classLoader)
XposedHelpers.findAndHookMethod("ok0.x0", lpparam.classLoader, "u", kCls,
  object : XC_MethodHook() {
    override fun beforeHookedMethod(p: MethodHookParam) {
      val task = p.args[0] ?: return                 // = v6
      val fullpath = XposedHelpers.getObjectField(task, "field_fullpath") as? String
      val fid     = XposedHelpers.getObjectField(task, "field_fileId") as? String
      val ftype   = XposedHelpers.getIntField(task, "field_fileType")
      val enable  = XposedHelpers.getBooleanField(task, "field_enable_hitcheck")
      val only    = XposedHelpers.getBooleanField(task, "field_onlycheckexist")
      val fakeSig = XposedHelpers.getObjectField(task, "field_fake_bigfile_signature") as? String
      // 日志：fullpath?.length + tail / fid?.take(8) / ftype / enable / only / fakeSig?.isEmpty
    }
  })

// —— 探针 B：hook CdnLogic.startC2CUpload / startSSUpload before，读 request ——
val reqCls = XposedHelpers.findClass(
  "com.tencent.mars.cdn.CdnLogic\$C2CUploadRequest", lpparam.classLoader)
val cbCls = XposedHelpers.findClass(
  "com.tencent.mars.cdn.CdnLogic\$UploadCallback", lpparam.classLoader)
XposedHelpers.findAndHookMethod("com.tencent.mars.cdn.CdnLogic", lpparam.classLoader,
  "startC2CUpload", reqCls, cbCls, object : XC_MethodHook() {
    override fun beforeHookedMethod(p: MethodHookParam) {
      val req = p.args[0] ?: return                  // = v0 request
      val path    = XposedHelpers.getObjectField(req, "filePath") as? String
      val fid     = XposedHelpers.getObjectField(req, "forwardFileid") as? String
      val enable  = XposedHelpers.getBooleanField(req, "enableHitCheck")
      val only    = XposedHelpers.getBooleanField(req, "checkExistOnly")
      val reqMd5  = XposedHelpers.getObjectField(req, "filemd5") as? String
      // 判据：reqMd5==null ⇒ md5 由 mars 重算；path?.length≈36 ⇒ 占位；≈15906 ⇒ 传缩略图
    }
  })
// startSSUpload 三参 (C2CUploadRequest, SessionCallback, UploadCallback)，签名多一个 SessionCallback，同法。
```

- 找类：`tj/k`（task）、`com.tencent.mars.cdn.CdnLogic$C2CUploadRequest`（request）、`CdnLogic`（native 宿主）、`CdnLogic$UploadCallback`/`$SessionCallback`（回调签名）。
- 找方法：`ok0.x0.u(tj/k)I`、`CdnLogic.startC2CUpload(C2CUploadRequest,UploadCallback)I`、`startSSUpload(C2CUploadRequest,SessionCallback,UploadCallback)I`。
- getObjectField 字段名（已核 dex 存在）：task 侧 `field_fullpath/field_fileId/field_fileType/field_enable_hitcheck/field_onlycheckexist/field_fake_bigfile_signature`；request 侧 `filePath/forwardFileid/enableHitCheck/checkExistOnly/filemd5`（字段名由 C2CUploadRequest 字段表确认）。
- **纪律**：两个探针都只读、不改 param、不调宿主业务方法；enabled 广播默认关；String take(80)、路径 take(120)、fileId 只打长度+前缀。

### 置信度
- task 六字段可读、request 为局部变量不可从 x0.u 拿、native 入口第一参=request =【dex 实证·高】。
- native 方法 hook 真机可用性 =【推断·中，未验证】。零命中（x0.u 不读 filemd5）如实记。

`[DONE Q2]`

---

## Q3（字段偏移 + B6 赋值）tj/k 三字段与 B6 field_fileId 实参来源

### 结论（一句话）
**【dex 实证】三字段 `field_fullpath`/`field_enable_hitcheck`/`field_fake_bigfile_signature` 确在 `Ltj/k;`（classes7）字段表与 string 池中；B6 的 `field_fileId` 实参 = `g9.c(r0.u,"msg","")` 解析出的 CDN URL，经 `r0.h` 条件二选一 `cdnbigimgurl`/`cdnmidimgurl`，由寄存器 `v5→v22→v1` 写入 `field_fileId`。** 即 B6 **不写死 big**——这是 Q4 的关键拼图。

### 证据链

#### 3.1 三字段 dex 实证（存在性）【dex 实证】
- **字段表**（`_r35_sns_dis.py fields Ltj/k;`，classes7.dex，depth 0）：三条均在 `Ltj/k` 自身声明的 instance 字段中：
  - `instance field_fullpath : Ljava/lang/String;`
  - `instance field_enable_hitcheck : Z`
  - `instance field_fake_bigfile_signature : Ljava/lang/String;`（另有 `field_fake_bigfile_signature_aeskey : String`）。
- **string 池**（classes7.dex 二进制 `find`）：`field_fullpath`@0xb5ef9c(count=2)、`field_enable_hitcheck`@0xb5eb3d(count=1)、`field_fake_bigfile_signature`@0xb5ecc0(count=2)。字段名字符串真实存在，配合字段表 = 字段定义确凿。
- **对照**：`B6` 构造的 task 实测**只写** `d/mediaId/fileId/aesKey/filemd5/fileType/talker/priority/svr_signature/onlycheckexist/trysafecdn/f`（B6 i=0x015f–0x01c0，见 3.3）——**不写**上述三字段，与 R86b `B6CheckExistProbe.kt` 12 字段集一致（源码层已证）。

#### 3.2 B6 的 CDN URL map key（两个都在）【dex 实证】
classes7 `MsgRetransmitUI.B6`，code_off=0x62c98c（绝对偏移=0x62c99c+2·i）：
- `i=0x00bb invoke-static g9.c(String,String,String)Map` —— 三参解析（R79/R82 已证实参 `r0.u,"msg",""`；r0.u = `com.tencent.mm.modelimage.r0.u`，i=0x00c5 读）。结果 Map 入 v0。
- 解析出的四个 map key 常量均在 B6 出现并被 `Map.get`：
  - `i=0x00dd '.msg.img.$cdnmidimgurl'` → `Map.get` → cast String；
  - `i=0x00e8 '.msg.img.$cdnbigimgurl'` → `Map.get` → cast String；
  - `i=0x00fe '.msg.img.$aeskey'` → `Map.get` → v4；
  - `i=0x014e '.msg.img.$md5'` → `Map.get` → v7（=filemd5 来源）。
- string 池复核：四键分别在 classes7 偏移 0x9dd75d / 0x9dd700 / 0x9dd6ee / 0x9dd85d，各 count=1。**注意：dex 有 key 字符串 ≠ 本次取到值**——取哪个值由 3.3 的条件决定。

#### 3.3 `field_fileId` 实参来源的寄存器链（关键）【dex 实证】
B6 用 `r0.h`（`com.tencent.mm.modelimage.r0.h`，i=0x00d8 `iget v8,v10,->h`）做二选一分支：
- `i=0x00da const/4 v6,#1`（u0=0x1612）；`i=0x00db if-eq v8, v6, +0x000d`（op=0x32，u0=0x6832，u1=0x000d → 目标 `i=0x00e8`）。即 **`r0.h==1` 跳到 big 分支；否则落到 mid 分支**。
- mid 分支：`i=0x00dd` 取 `$cdnmidimgurl` → v1 → `i=0x00e5 move-object v5,v1`（op=0x07，u0=0x1507: dest v5, src v1）。
- big 分支：`i=0x00e8` 取 `$cdnbigimgurl` → v1 → `i=0x00f0 move-object v5,v1`（同源）。
- 两分支汇合 v5，经 `i=0x00f2 a8.F0(v1)` 非空校验（空→`'cdntra get cdnUrlfailed.'`，i=0x00f8）。
- **写入 task 的寄存器链**（`_r86c_reg.py` 反向追踪核实）：
  - `i=0x016e move-object v22 <- v5`（u0=0x1608,u1=0x0005）→ **v22 = 选中的 CDN URL**；
  - `i=0x01a7 move-object v1 <- v22`（u0=0x0108,u1=0x0016）；
  - **`i=0x01a9 iput-object v1, v4, Ltj/k->field_fileId`**（v4 = 新建的 `Ltj/k` task，见 i=0x0157）⇒ `field_fileId = v1 = v22 = v5 = 选中的 CDN URL`。
  - 对照：`field_aesKey` 同法 `v20<-v4(i=0x0123)`→`i=0x01ad`；`field_filemd5` 同法 `v27<-v7(i=0x0174)`→`i=0x01b1`（v7 来自 `$md5`，i=0x014e）。

#### 3.4 B6 task 字段赋值清单（i=0x0157–0x01c0）【dex 实证】
`new Ltj/k`(0x0157)→`d="task_MsgRetransmitUI_2"`(0x015f)→`listener f=qf4/j0`(0x0188)→ `mediaId`(0x01a5, `n1.b("checkExist",msgId,"","")`, i=0x01a1) / `fileId`(0x01a9) / `aesKey`(0x01ad) / `filemd5`(0x01b1) / `fileType`(0x01b5) / `talker`(0x01b7) / `priority`(0x01b9) / `svr_signature`(0x01bb) / `onlycheckexist`(0x01be, const/4 v0 #1? 见下注) / `trysafecdn`(0x01c0) → `mq/k.B8(task)`(0x01cc)。
- **注（诚实记）**：`i=0x01bd const/4 v0,#1`（u0=0x1012）紧邻 0x01be `iput-boolean v0,onlycheckexist` 与 0x01c0 `iput-boolean v0,trysafecdn`——**单看 dex 此处 v0=#1（true）**；但真机 R78/Z.1 四样本与态① :375 均实测 `onlycheckexist=false`。二者矛盾，说明 v0 在 0x01a1 `n1.b` 返回（i=0x01a4 move-result-object v0）后已被覆盖为 Boolean 装箱/其它值，**该 const/4 的精确目的寄存器需更细的寄存器数据流才能定论**——标【UNKNOWN 未闭合：B6 写入 onlycheckexist 的布尔字面量】。本项不影响 Q1/Q2/Q4 结论；R86b 依真机值填 false 是正确选择。

### 置信度
- 三字段存在性、map key、field_fileId 寄存器链 =【dex 实证·高】。
- B6 `onlycheckexist` 字面量 =【UNKNOWN 未闭合】（dex 字面与真机值矛盾，卡在寄存器覆盖；以真机 false 为准）。

`[DONE Q3]`

---

## Q4（Q1②大/小图语义）field_fileId 是 big 还是 mid URL 语义

### 结论（一句话）
**【dex 实证 + 真机确定，高置信】B6 原生 `field_fileId` 在坏图场景取的是 mid/thumb URL 语义（非原图 big URL）——因为 Q3 已证 B6 用 `r0.h` 二选一，而坏图 attrs 无 `cdnbigimgurl`（`state_final:208`），B6 该分支根本取不到 big；但 stock 的 `x0cb hit=1 md5=ca47f83e`（态①）恰好用的也是这个 `305f0201` 前缀 ⇒「305f0201 是 mid URL」**本身不能**解释未命中，根因②（fileId 语义错配）被真机反证降为【推断·低】，真正的决定量仍是 R82 Z.2/Z.3 的 `field_fullpath` 字节 + hitCheck（根因①）。**

### 证据链

#### 4.1 B6 原生 fileId 语义：由 r0.h 二选一，坏图取不到 big【dex 实证】
- Q3 已证：B6 `field_fileId = v5 = (r0.h==1 ? map[$.cdnbigimgurl] : map[$.cdnmidimgurl])`（i=0x00db if-eq → 0x00e8 big；否则 0x00dd mid）。
- 【真机确定】`state_final:208` 坏图 attrs：`cdnthumburl:194`、`cdnmidimgurl:194`、**无 `cdnbigimgurl`**；`B6CheckExistProbe.kt:56-61,201-211` 注释与回退链也记录"坏图 map 与 xml 均无 cdnbigimgurl"，故用 `regex:mid` 取到 `305f0201`（`:209 idVia=regex:mid fidPre=305f0201`）。
- ⇒ 原生 B6 在坏图上：`r0.h==1` 时 `map[$.cdnbigimgurl]` 为 null → `a8.F0` 失败（i=0x00f6）→ 报 `cdntra get cdnUrlfailed.` 放弃；`r0.h!=1` 时取 mid=305f0201。**两种情形都拿不到"原图 big URL"**——因为源 xml 根本没有 big 键。
- ⇒ 设计 §5.3「fileId 取 `.msg.img.$cdnbigimgurl`」的**前提在坏图样本被真机证伪**（R86b verdict 已记此点）；B6 真机逻辑本就允许 mid（r0.h!=1 分支）。

#### 4.2 "305f0201 是 mid URL"能否解释未命中 —— 不能【真机反证·高】
- 【真机确定】`305f0201...` 的前缀在 R76 log:55 明确出现在 `cdnmidimgurl="305f0201..."`（R79:139/144），确认其 **mid URL 身份**，不是 big。
- 【真机反证·决定性】但同一串 `305f0201` 在态①（stock，清晰）里是 `SCD m1e task fileId=305f0201...`（`lock_state1_raw.txt:373`）、`x0cb ... fidPre=305f0201 hit=1 exist=true md5=ca47f83e`（`:388`）——**stock 用这个 mid 前缀的 fileId 命中了原图且对端清晰**。R82 Z.1 四样本（02:42 stock / 22:49 stock 清晰，23:13/23:44 我方模糊）`fileId` 逐字相同（都 `305f0201...`）、`onlycheckexist` 全 false ⇒ **fileId 值对"清晰/模糊"零区分力**。
- ⇒ 若"mid URL 语义"是未命中根因，则 stock 用同一 mid 值也应未命中——但 stock 命中。**逻辑反证成立**：305f0201 是 mid 这件事本身**不是**未命中的充分/必要解释。

#### 4.3 那为什么 R86b 自建 task（用 mid 值）exist=false —— 根因归位【推断·高，对齐根因①】
- 自建 task 与 stock task 的**唯一已证字段差异**不是 fileId（同值 305f0201），而是：自建**无 `field_fullpath`、无 `field_enable_hitcheck`、无 `field_fake_bigfile_signature`**（R86b verdict §1.1①，`B6CheckExistProbe.kt:47-50` TASK_FIELDS 12 项）；态① stock 有 `field_fullpath=124B` + `c2c enableHit=true`（`:375`）。
- 【dex 实证】R82 Z.3：`ok0/x0.u` `i=0x000b setFilePath(field_fullpath)` 是唯一字节入口、全程不读 field_filemd5、请求 md5 由 mars 从 filePath 字节重算；`enableHitCheck` 透传参与 hitCheck。⇒ 缺 fullpath/enable_hitcheck ⇒ mars 无本地字节算 md5、hitCheck 不成立 ⇒ `exist=false md5Len=0`，与 R86b C2 第二次回包（`state_final:213`）形态一致。
- ⇒ R86b 未命中的因果链指向**根因①（缺字段）**，而非根因②（fileId 语义）。

#### 4.4 保留的 UNKNOWN（诚实标注）
- 【UNKNOWN 未闭合】`r0.h` 的精确语义与坏图样本取值未定：`r0.h` 由 `r0.b(Cursor)` 第 17 列读入、`r0.j(I)` 可写（i=0x0000 `iput v1,h`），其是否等于"大图模式标志"未经 dex 语义反汇编坐实；坏图 r0.h 实际值未取。故"原生 B6 在坏图走 mid 分支"是基于"无 big 键 + a8.F0 失败"的强推断，非直接观测分支寄存器值。
- 【UNKNOWN 未闭合】stock 的命中链经 R79/R82 认定为"上传 task（j2.O / m1e，带 fullpath）的 CUploadCompleted"，**非** B6 checkExist task 的命中；B6 原生 checkExist 在坏图上是否真能命中原图，真机未直接观测（R86b verdict 核心命题边界）。本文不把"stock 清晰"直接等同于"B6 checkExist 命中"。

### 置信度
- B6 fileId 二选一语义、305f0201=mid URL、stock 用同值命中、fileId 零区分力 =【dex 实证 + 真机确定·高】。
- "mid 语义不能解释未命中 / 根因归位①" =【推断·高】（基于上述反证 + R82 Z.2/Z.3 决定量）。
- r0.h 语义与坏图分支寄存器值 =【UNKNOWN 未闭合】。

`[DONE Q4]`

---

## 给主会话的探针落码规格小结
> 面向 Flash lane 机械实现；完整代码见 Q2 2.5 节。全部只读、不改 param、默认关广播。

| 探针 | hook 点（类.方法签名） | dex 类/偏移 | before/after | 读什么 | 判据 |
|---|---|---|---|---|---|
| A | `ok0/x0.u(tj/k)I` | classes13 co=0x3a98cc | **before**，`param.args[0]`=task(v6) | `getObjectField`: field_fullpath / field_fileId / field_fake_bigfile_signature；`getBooleanField`: field_enable_hitcheck / field_onlycheckexist；`getIntField`: field_fileType | 对照态① `:375`：自建 task 应显示 fullpath=null/enable=false/fakeSig=null；若 non-null 说明字段已补 |
| B | `CdnLogic.startC2CUpload(C2CUploadRequest,UploadCallback)I`（及 startSSUpload 三参） | classes13，x0.u i=0x0107/0x0102 调用 | **before**，`param.args[0]`=request(v0) | `getObjectField`: filePath / forwardFileid / filemd5；`getBooleanField`: enableHitCheck / checkExistOnly | filePath 长度≈36=占位/≈15906=缩略图；forwardFileid 前缀=305f0201；filemd5=null ⇒ 印证 mars 重算 |
| C（可选补 D3 链） | `ok0/m1.e(tj/k)Z` | classes7 co=0x7b52b4 | before | `getBooleanField(task,"e")`、`getObjectField(task,"field_fileId")` | 应恒 e=true、fileId 非空——坐实 Q1"必走 x0.u"的入口条件 |

- 找类：`tj/k`（task）、`com.tencent.mars.cdn.CdnLogic$C2CUploadRequest`（request）、`CdnLogic`（native 宿主）、`CdnLogic$UploadCallback`/`$SessionCallback`（回调签名）。
- 字段名（dex 已核）：task 侧 `field_fullpath/field_fileId/field_fileType/field_enable_hitcheck/field_onlycheckexist/field_fake_bigfile_signature`；request 侧 `filePath/forwardFileid/enableHitCheck/checkExistOnly/filemd5`。
- **关键坑**：不要试图在 `x0.u` 的 after 里读 request——它是局部变量 v0、非返回值/入参，**拿不到**；必须 hook native 入口 startC2CUpload/startSSUpload（Q2 2.3/2.4）。
- native 方法 hook 真机可用性 =【推断·中，需真机验】，实现 lane 首次跑需确认探针 B 是否命中；若不命中，退化为"只跑探针 A + 现有 SCD-m1e"。
- 纪律：只打长度+前缀，String take(80)、路径 take(120)、fileId 只打前 8；每 hook guard 包、默认关；不调 j6/不写宿主字段/不发消息。

---

## 文末三件套
① **Q1 是否坐实**：**坐实（dex 级）**——`ok0/m1.m` 用 `task.e`（由 `m1.e@0x002b` 无条件置 true）分派，`i=0x00a0 if-eqz(e)` 不跳、顺序落到 `i=0x0129 invoke x0.u`；`field_fullpath=null` 仅在 0x00a4 用于日志长度、0x00f6 被规整为 ""，**不参与分派**；m1.m 全程不读 field_fileId。设计 §D3 命题（无 fullpath 落 x0.u 而非 download）为真，但理由是"fullpath 根本不参与分派"。【dex 实证·高】

② **补 fullpath 能否救活原路线 go/no-go**：**no-go（就"靠补 fullpath 救活当前自建 checkExist task"而言）——但裁决依据被本轮改写**。补 fullpath **不会改变分支**（本就进 x0.u），所以"补 fullpath 让 task 走对路"是伪命题；fullpath 的作用在 x0.u 的 `setFilePath`（Q2）与 mars hitCheck（R82 Z.3），属"命中条件"。真正该补的是完整字段集（fullpath + enable_hitcheck + fake_sig 对齐态① `:375`），而非只补 fullpath；且即补齐，R86b 既有 no-go（C2 零命中）仍按设计唯一裁决点成立——**本轮只坐实根因①的因果机制，不翻转 R86b 的 no-go 总裁决**（"复刻后清晰"仍是 R79 §4.6 未验证假设）。若实现 lane 要做，须按 Q2 探针 A/B + 反向对照 A→B→A，对端肉眼"模糊→清晰→模糊"翻转成立才算救活。

③ **探针落码可行性**：**可行，分两层**——探针 A（hook x0.u before 读 task 六字段） dex 证据充分、`param.args[0]` 直读、可直接落码【dex 实证·高】；探针 B（hook 原生 startC2CUpload/startSSUpload before 读 request）字段映射确凿，但 native 方法 hook 真机可用性【推断·中，需真机验】，需实现 lane 首跑确认。x0.u 的 after **拿不到** request（局部变量），不要走这条。整体只读规格符合 R86b 红线。

`[DONE ALL]`

---

## 主会话独立复核附录（2026-11-24 01:2x，事实核验，不改上面结论）

> 按 CLAUDE.md「Pro 降档产物必须主会话严格事实核验、不盲从」补此节。全部核验用只读手段，独立重跑而非复述子代理结论。

### A. 关键 dex 指令逐条独立核验 —— 通过
- `_r86c_m1m.txt`（子代理落盘反汇编）独立 grep：`0x0094 iget-boolean v7,v6,Ltj/k;->e`、`0x00a0 op=0x38 u1=0x0101`（if-eqz e 跳 0x01a1）、`0x0129 invoke-virtual Lok0/x0;->u(Ltj/k;)I` 三条均在，偏移与文档一致 ⇒ Q1 分派链属实。
- `_r86c_x0u.txt` 独立 grep：`0x0000 new-instance C2CUploadRequest`、`0x0009 field_fullpath → 0x000b setFilePath`、`0x0085 field_enable_hitcheck → 0x0087 enableHitCheck`、`0x0102 startSSUpload` / `0x0107 startC2CUpload` 全中 ⇒ Q2 字段映射属实。
- 类名纠正核实：主会话全 dex 扫 `Lcom/tencent/mars/cdn/CdnLogic;` 只在 classes13 找到 5 个**混淆名方法**（M2evocmp 等），无 `startC2CUpload` 明文字样 ⇒ 子代理「start*Upload 在 classes13」的 dex 定位需谨慎（可能是它用 string 池引用而非类方法表）。**不影响 Q1/Q4 结论**（那两条只看 m1.m/x0.u），但影响 Q2 探针 B。

### B. 两处必须收紧的点（子代理标注偏乐观）
1. **探针 B（hook native startC2CUpload 读 request）= 死路，不是【推断·中】**：项目一手 `_r10_cdn_api.md` 逐方法 acc 实测 `CdnLogic.startSNSDownload/startC2CUpload` 全 `acc=0x109` NATIVE、无 Java 实现体，Xposed 不可直接挂。R82 B2-a 已因同一原因把 E2 从 native 改挂 Java 调用方 `ok0/m1.e`。⇒ 探针 B 应判 **no-go**，Q2「native hook 通常可行」的表述作废。
2. **Q3 §3.4 B6 listener=qf4/j0 与 R79 定案不冲突**：R79 Q2 已证「B6 checkExist task 的 listener=qf4/j0」正确，真机 `m1e` 命中的 f2 是**另一条** j2.O 上传 task，两者不是同一 task。子代理此处复述 R79 无误，主会话初疑「f2 vs qf4 矛盾」属误读，已排除。

### C. 关键优化发现：Q4 步骤 1 无需落码
- 文档 Q2 探针 A 要读的自建 task 六字段（fullpath/fileId/fileType/enable_hitcheck/onlycheckexist/fake_sig），**现有 `StockCdnProbe.SCD-c2c`（挂 `ok0/m1.e` before）已逐字段读全**（`StockCdnProbe.kt:435-458`，日志 `SCD2 c2c in fullpathLen=... enableHit=... only=... fakeSigEmpty=...`）。
- 本轮 Q1 dex 已证 `mq/k.B8` 纯 delegate 到 `m1.e`（classes7 co=0x78f618，3 指令）；自建 checkExist task 经 B8 下发**必经 m1.e**，读到的是同一个 task 对象。
- ⇒ Q4.2 步骤 1 与步骤 3（同时刻 stock 对照）可**零落码、零装机**用现有双探针（SCD + B6X）完成；仅需步骤 2（Proxy C2 加打 `args[3]` 类名 + Ltj/f 全字段）要改 B6X 一处 handler，但那是排 Q1④（已可基本排除）的加固项，可后置。
- 设备现状只读核实：`com.lx.wxgroup` 已装 3349574B（本地 APK 一致，00:36:51），SCD `enabled=false ok=11 fail=0`、B6X `enabled=false installed=true classes=true` —— 满足零落码取证条件。

### D. 主会话裁决（不改 R86b no-go）
D3 dex 坐实的是**根因①的机制**（fullpath 不参与分派、决定量是 setFilePath 字节 + hitCheck），但**不改变** R86b「C2 零命中 ⇒ no-go」总裁决；Q1③（服务端过期）仍需真机同时刻 stock 对照排除。下一步 = 用现有双探针跑一轮只读取证（开 SCD + B6X，stock 原生转发做对照 + 自建 checkExist 看 SCD2 c2c 字段），对着用户可见事实判读；不落码、不装机。
