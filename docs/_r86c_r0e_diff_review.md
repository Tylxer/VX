# R86c · j2.R after → 写 r0.e/f 指向 45522B hevc 字节 · 独立代码复核（装机前闸门）

> reviewer：`r86c-r0e-review`（fresh eyes / 独立复核代理）。性质 = Pro 类（Hook/反射/并发）。
> 只读：**未改任何源码、未跑 gradle、未碰 adb/开关**。唯一写文件 = 本文档。
> 输入：`git status`/`git diff`、新文件 `J2R0eOverrideHook.kt`(368 行) + `J2R0eOverrideHookTest.kt`(293 行)、`_r86c_r0e_impl_note.md`、真机 `_r86c_v2_dbg.txt`(1155 行)、`_r86c_microx_route_verdict.md`、`_r76_p3_bytes_landing_plan.md`、`_r86c_q4b_localpath_fix.md`、`_r86c_probe_forensics.md`、`_r86c_probe_landing_spec.md`、`_r78_m0l0_miss_verdict.md`、`_s9dump/classes7.txt`、`ForceForward.kt`/`ForwardCdnFieldHook.kt` 对照范式。
> 证据标注：【源码实证·高】/【dex 实证·高】/【真机实证·高|中】/【推断·高|中|低】/【UNKNOWN 未闭合】。每条结论带出处。
> 复核中途 team-lead 通知「实现 lane 又改了一轮」，已**重新读取当前工作树**：`J2R0eOverrideHook.kt` 与我首读逐字一致（`:137-140` 的 origin-unresolved skip / 四类带 thread 的 skip 日志 / 写帧 thread+md5+localId 日志 / TTL Map 只在字节命中后登记均已在首读版本中存在），`J2R0eOverrideHookTest.kt` 为 13 例（含 `byteHitGateBeforeWrite` + `mutationByteHitGateSkipRemoved`），impl note 里「11 tests」是过期记载。⇒ M2~M8 依据的就是最新版源码，结论不变；M1 按 team-lead 补证补写了 j/l/m 专项判定。

---

## 一句话结论（go/no-go）

**NO-GO —— 不能装机做真机 A/B。M1 是 BLOCKER：真机零生效，且是必然的，不是概率的。**

`J2R0eOverrideHook.resolveOriginLocal()` 取数据根的唯一入口是
`strings.firstOrNull { it.contains("/MicroMsg/") }`（`J2R0eOverrideHook.kt:218`），
但 j2.R() 返回的 `com.tencent.mm.modelimage.r0` 的 **7 个 String 字段（`e/f/g/j/l/m/u`）无一存绝对路径**
（【dex 实证·高】`_s9dump/classes7.txt:67054-67215` 字段清单 + `_r86c_q4b_localpath_fix.md:25,35-39,54`），
且同一谓词（更严格：还要求 `startsWith("/")`）在 ForwardCdnFieldHook 里于 V2 真机**两次命中 `skip reason=no localPath`**
（【真机实证·高】`_r86c_v2_dbg.txt:869,1005`）。⇒ 每次 `j2.R()` after 都会在 `J2R0eOverrideHook.kt:137-140`
以 `origin-unresolved` 返回，`e/f` **永远不写**。三闸全绿 = 编译绿 + **源码文本断言绿**，真机零生效。
装机前必须先修 M1；否则 A/B 只会拿到「无变化」的假阴性，并浪费一次装机窗口。

---

## 逐项结论（M1~M8）

### M1 字节定位可能永远失败 —— **BLOCKER**

**结论：成立，且证据闭合到 dex 级，不再是 UNKNOWN。**

1. 【dex 实证·高】r0 全字段清单：`_cache/_s9dump/classes7.txt:67054-67215`（`Class descriptor 'Lcom/tencent/mm/modelimage/r0;'`，Superclass `Ljava/lang/Object;`）：
   - String 字段仅 7 个：`e`(#22) / `f`(#23) / `g`(#24) / `j`(#27) / `l`(#29) / `m`(#30) / `u`(#38)，均为 `Ljava/lang/String;`；
   - `a`(#18):J、`b`(#19):J 为 long；`c/d/h/i/k/n/p/q/r/s/t/v`:I；`Q` 及 `A~R` 为 Z。
   - `_r86c_q4b_localpath_fix.md:36-39` 独立复核：这 7 个 String 字段全部来自 `r0.b(Cursor)` 的 `Cursor.getString(4/5/12/16/18/20/22)`，装的是 DB cursor 的相对文件名 / URI / xml 内容，**无一是 `/data/user/0/.../MicroMsg/...` 绝对路径**；真正的 fullpath 由 `j2.O → b1.z3(r0.e/f/g/j)` 现场拼（`j2.O` @`0x2acc44`，`iget→invoke b1.z3` @i=0x0072/0x007e/0x0088/0x0092），从不作为字面量存进 r0 字段。
2. 【源码实证·高】j2.R() 返回的**就是同一个类**：`_r78_m0l0_miss_verdict.md:80-81` 重算 `j2.R()` @`0x2ad864` = `if (q==null) q = b1.o2(Long.valueOf(j2.i)); return q;`，`b1.o2` 返回类型 `Lcom/tencent/mm/modelimage/r0;`；`_r86c_q4b_localpath_fix.md:30-33` `b1.o2(J)→r0` 亦证。⇒ 换对象（o2/a3/j2.R）救不回，都是同一 r0 类、同一 7 个 String 字段。
3. 【真机实证·高】V2 日志里 r0 的实际形态：`_r86c_v2_dbg.txt:905/932/935/1038/1105` 等 16 行 `SCD j2R after paths=[e=bd64a9fd…jpg f=null g=THUMBNAIL_DIRPATH://th_…]` / `e=de291b56…jpg …`，**e/f/g 全是相对文件名或 URI，没有一个以 `/` 开头**；两次转发 8+8 帧均如此。
4. 【真机实证·高·同源谓词已失败】`ForwardCdnFieldHook.kt:183-187` 的 `firstOrNull { it.startsWith("/") && it.contains("/MicroMsg/") }` 与本案 `J2R0eOverrideHook.kt:218` 的 `contains("/MicroMsg/")` **同源**（本案还更宽松：去掉了 `startsWith("/")`）。前者在 V2 真机 `_r86c_v2_dbg.txt:869` 与 `:1005` 两次 `FORWARD CDN_FIELD arm skip reason=no localPath`。更宽松的谓词不可能在更严格的谓词失败的字段集合上成功。
5. 【推断·高·必然】由 1+2+3+4 ⇒ `originStrings(r0)`（`J2R0eOverrideHook.kt:253-268`，取 e/f/g + 遍历全部 String 字段，恰好就是那 7 个）里没有任何 `contains("/MicroMsg/")` ⇒ `:218` 恒 null ⇒ `:137-140` 恒 `origin-unresolved` return ⇒ `:156-157` 的 e/f 双写**恒不执行**。
   - 诚实边界：新 hook 的 `R0E` 日志在 V2 日志中 **0 条**（`grep -c R0E = 0`，日志时段 23:42–23:45 早于本轮落码），故「装机后零写入」是【推断·高·必然】而非【真机实证】；但定位谓词的失败是【dex 实证·高 + 真机实证·高】，推断的前提已坐实。
   - 连带失效：`candidates`（`:225-228`）从这 7 个字段抠 32-hex，只会得到 `216b1f7c…`（源 jpg 文件名）与 `th_216b…`，**不含 hevc 文件名 `e64b3b2…`**（【真机实证·高】`_r86c_probe_forensics.md:23-28,48`）；即便数据根另有来源，主路径 `:231-234` 也拼不到 hevc，只剩兜底枚举 `:236-247`。

**是否存在其他取根路径？有，但本实现未采用，且直接套用会引入 M3 的帧污染：**
- 【源码实证·高】`ForceForward.kt:1340-1343` 的 `HostContext.app()?.dataDir` + 枚举 `MicroMsg/<hash>` 子目录是现成可用范式（多账号逐个试）。
- 【源码实证·高】`_r86c_postprobe_verdict.md:50,229` 推荐的候选②：在 `ok0/m1.e before` 读 `task.field_fullpath` 正则取 `/MicroMsg/<32hex>/` 根 —— 但那是 m1.e 写点，**j2.R() after 时点没有 task**，不能直接搬。
- 【源码实证·高】规格 `_r76_p3_bytes_landing_plan.md:136-159` 方案 B 的原设计：tempPath **由 FileSender 在 `q1.a()` 前经 ThreadLocal（或按 imgLocalId 的 TTL Map）交接给 R() after**，即字节路径是**外部传入**的，规格从未要求「从 r0 字段自定位」。本实现因禁改 FileSender.kt 而自造了 `resolveOriginLocal`，正是这一步引入了必然失败的谓词。

#### 对「r0 的 j/l/m 中是否存在完整 `/MicroMsg/<32hex>/image2/...` 路径」的明确判定

team-lead 补证（`_r14_fields.py` 44 字段清单、`r0.u` 真机为 CDN XML）后，把问题收敛如下：

- **j/l/m 的「原始值」层：V2 真机 UNKNOWN。** 我逐行复核 `_r86c_v2_dbg.txt`：SCD-j2R 只打 `e/f/g`（`:905` 等 16 行），`MIO j2O` 只打 `r0.u`（`:911/1047`，确为 `<msg><img aeskey=… cdnmidimgurl=…` 的 CDN XML，不是路径）。`j/l/m` 三字段的值在 V2 日志中**一次都没打印**。另一份 22:01 探针 `_r86c_probe_forensics.md:23-28` 曾打印 `j = NA`、`l = NA`（但那是另一次转发样本，且 `m` 从未打印）。⇒ 若严格只问「打印出的字面值」，j/l/m = UNKNOWN。**不凭字段名猜「应该有」。**
- **但「是否存在 `/MicroMsg/` 完整路径」这一决定性问题：真机已证否，不是 UNKNOWN。** 决定性证据是 V2 的 `skip reason=no localPath`（`:869/:1005`）：`ForwardCdnFieldHook.kt:183-187` 的遍历是
  `img.javaClass.declaredFields.filter{String}.mapNotNull{读值}.firstOrNull{ it.startsWith("/") && it.contains("/MicroMsg/") }`，
  **覆盖全部 7 个 String 字段（含 j/l/m 和 u）**，且作用于 `img = o8.o2(imgLocalId)`（`o8=d3.O8()=b1`，`_r86c_q4b_localpath_fix.md:30`）。而 `j2.R()` = `b1.o2(j2.i)`（`_r78:80-81`）——**同一个 `b1.o2(Long)→r0` 方法、同一个 `r0.b(Cursor)` 字段填充逻辑**。若 j/l/m 中任何一个携带完整 `/MicroMsg/` 绝对路径，这个遍历必然命中、不会 skip。它 skip 了两次 ⇒ 「o2 产出的 r0 的 String 字段集里存在完整路径字段」这一命题**被真机证伪**，j/l/m 已被该谓词覆盖。
- 本案谓词 `contains("/MicroMsg/")`（`:218`）比 ForwardCdnFieldHook 的 `startsWith("/") && contains("/MicroMsg/")` **更宽松**；WeChat 的 MicroMsg 路径是绝对路径（必以 `/` 开头），故两者对本场景等价。更宽松的谓词不可能在更严格的谓词失败的同一字段集上成功。
- **对 R74「r0.j 极可能是本地路径字段」的反驳**：`_r74_microx_impl_recheck.md:126/165` 原文即标注 【推断·中，UNKNOWN】「未 hook r0.l/n/C6 真机确认，故无法定论」；且「ImgInfo2 路径字段」在 V2 实测中装的是**相对文件名**（`e=bd64a9fd…jpg` 无 `/MicroMsg/` 前缀），即使 j 被 `r0.l(String)` 写过，其值形态也与 e 同类——相对名不含数据根。真正的 fullpath 由 `b1.z3(r0.e/f/g/j)` 现场拼（`_r86c_q4b_localpath_fix.md:42`），从不作为字面量存在 r0 字段。⇒ 不能以「j 是路径字段」推断「j 含完整路径」。
- **结论**：M1 维持 **BLOCKER**。原始值 UNKNOWN 不改变 go/no-go，因为决定性的谓词失败是真机实证、且已覆盖 j/l/m。
- 对 team-lead 提议的「可装机 + 真机加打 j/l/m 值验证」降级路径：**不建议采用**。它适用于「r0 字段证据拿不到、连谓词是否失败都不知」的情形；本案谓词失败已是真机实证，装机只会得到定义上的零生效 A/B。若仍想补描述性缺口，成本极低的做法是给 SCD-j2R 探针加打 `j/l/m` 原始值（不影响逻辑、不装机也可作为下一轮只读取证项），但**该补测不构成翻案依据、不应覆盖 NO-GO**。

**最小修复方向（不自行改）见文末「修复建议」。**

---

### M2 字段写对没 —— **OK（写逻辑正确，但当前不可达）**

- 【源码实证·高】`J2R0eOverrideHook.kt:155-157`：`setObjectField(r0, "e", tempPath)` + `setObjectField(r0, "f", tempPath)`。确为**直接写 r0 对象字段**，不是 `param.result=` / `param.args[]=`。
- 【dex 实证·高】写法正确：`r0.l(String)` 实际写 `j`（`0x2b3e94`）、`r0.n(String)` 写 `f` 且置脏（`0x2b3ed4`），`field_fullpath=z3(r0.e)`（`_r76_p3_bytes_landing_plan.md:105-116`；`_r86c_microx_route_verdict.md:68`）。故必须 `setObjectField(r0,"e",…)`，本案做对了。
- 【源码实证·高】未误调宿主业务方法：全文无 `r0.l(`/`r0.n(`/`b1.u8`/`doNotify`/`P3AdmitGate`/`65536`/`q1.j` 写（单测 `redLinesNoHostCalls` :60-77 亦锁此，我逐行复核确认非假绿）。
- e+f 双写**必要且正确**：e 决定 `field_fullpath`，f 覆盖 doScene 另一处读取（`_r76_p3_bytes_landing_plan.md:115` 明确「建议 e+f 双写」）。
- 结论：写法本身 OK；但因 M1，这段是**不可达代码**。

---

### M3 时机与多线程 —— **MAJOR（设计风险，当前被 M1 掩盖）**

- 【dex 实证·高】`localId` 取法**正确**：`J2R0eOverrideHook.kt:127` `getLongField(r0,"a")`；`r0.a:J`（`_s9dump/classes7.txt:67133-67135`），`_r78_m0l0_miss_verdict.md:81`「`r0.a : J` 才是该记录的 localId，`r0.b:J` 是 msgId」。变异测试 a→i 能判红（见 M7）。
- 【源码实证·高】闸门意图正确：`:137-140` 先定位字节、失败 return，避免把无关缩略图帧也改掉；无「已写过」缓存标志，命中帧每次重写以应对 e 被重建——与 V2 实证的 e 重建（`bd64a9fd`→`de291b56`，`_r86c_v2_dbg.txt:905 vs 1038`）吻合。
- 【真机实证·高】V2：16 次 `j2.R()` after 跨 `[D]#worker`/`ColdPool#N`/`Thread-38x`/`wxg-forward` 多线程，两帧 e 各 8 次；TTL Map 以 localId 为 key 的复用思路本身可行。
- **风险（关键）**：当前「字节命中天然闸门」的判别力**完全寄生于「数据根从 r0 字段取」**。一旦按 M1 把根换成 `dataDir/MicroMsg/<hash>`（账户级全局根），唯一那份 cff4f5a5/45522B hevc 在 `image2/` 两级分桶下是**全局唯一**（【真机实证·高】`_r86c_probe_forensics.md:16-20` G1 `hit=1 ambiguous=false`），于是**每一个 j2.R() 帧（含两帧缩略图、跨所有线程）都会命中同一份 hevc 并被写 e/f** ⇒ 闸门从「fail-closed」翻转为「fail-open」，**污染其它帧**，正是主会话担心的场景。
- 【推断·高】要 M1 修复后仍不污染，须增加**逐帧身份闸门**（只写「本次转发目标 localId/msgId 对应的那一帧」），或回到规格 §3.5 的外部 tempPath 交接（只有真正持有目标字节的帧才拿到 tempPath）。单纯换取根不够。
- 【推断·中·低】TTL 用 `System.currentTimeMillis()`（`:144,152,325`）而非 `elapsedRealtime()`，挂钟跳变理论上可影响 120s TTL；影响面小。
- 结论：当前因 M1 不产生任何写入（无污染事实），但设计在修复后有明显帧污染风险，判 MAJOR。

---

### M4 cleanup 正确性 —— **MINOR（结构正确，当前不可达）**

- 【源码实证·高】`onNetEnd`（`:301-330`）与已部署范式 `ForceForward.kt:2629-2675` **同构**：`param.args[1]/[2]` 取 errType/errCode、仅 `0/0` 删、120s TTL、按 localId 命中删。
- 【dex 实证·高】关联字段主链 `j2.q → r0.a/b`（`r0.a=localId`、`r0.b=msgId`）符合 R78 订正（`_r78_m0l0_miss_verdict.md:81`）；best-effort 兜底 `j2` 自身 `a/b/i/h/j/Q`（`:317-320`）中 `i/h` 实为 int 状态、`j` 为 String、`Q` 为 Z（`_s9dump/classes7.txt:67125-67170`；R78 :82），`getLongField` 读它们恒 NA —— **但仅兜底、不参与成功判据，无害**。
- 【推断·高】early unlink 风险低：0/0 是 NetScene 网络完成回调，晚于 `doScene z3(r0.e)→o6.l/o6.N` 读字节，不会截断上传；且删不到靠 cacheDir 系统回收、成功判据不依赖清理日志（`:299`，与 impl note §7 一致）。
- 【推断·中】若按 M3 风险场景多帧共享同一 tempPath，`0/0` 到达时可能删掉别的线程仍在读的那一份；当前因 M1 无任何登记，不发生。
- 结论：MINOR。

---

### M5 开关与回滚 —— **OK**

- 【源码实证·高】`enabled` `@Volatile` 默认 false（`:48-50`）；`install` 仅读一次 `Kv.getBool(Keys.FORWARD_R0E_HEVC)`（`:66`）；`turn()` 只翻内存（`:104-108`）；两回调首行 `if(!enabled) return`（`:76`、`:94`）。
- 【源码实证·高】冷启复位为关：`Keys.FORWARD_R0E_HEVC` 默认 false（`Keys.kt:192-200` 新增块），op=r0e on/off **不写 Kv**（`EntryReceiver.kt:344-356` 只调 `turn`），故冷启按 Keys 默认值复位，不持久化、不污染其它开关。
- 【源码实证·高】`status()` 回显开关/hook 安装态/temps 数（`:110-115`），排障够用。
- 结论：OK。唯一提醒（非缺陷）：`op=r0e on` 只在本进程有效，重启需重发广播——与注释一致，符合预期。

---

### M6 接线 —— **OK**

- 【源码实证·高】`git diff` 三处：
  - `Keys.kt`：新增 `FORWARD_R0E_HEVC = "chat.forward_r0e_hevc"`（默认 false），无重名。
  - `HookEntry.kt:139-143`：仅一处 `runCatching { J2R0eOverrideHook.install(lpparam) }`，import 正确（`:16`），无重复 install。
  - `EntryReceiver.kt:344-356`：新增独立 `"r0e" ->` 分支，与既有 `"r86c"`/`"b6step"`/`"p3"` 分支无 key 冲突，on/off/stat 三子命令齐全。
- 【源码实证·高】`HookEntry.kt` diff 中同时出现 `ForwardCdnFieldHook` import 恢复与 `B6CheckExistProbe.install`（`:373-380` 注释），属**更早轮次**（R86c Q4b / R86b B6）的既有改动，非本轮引入，未破坏既有功能。
- 结论：OK。

---

### M7 单测可信度 —— **MAJOR（三项变异真实，但未覆盖 M1 命门，构成假绿温床）**

- 【源码实证·高】13 例（9 主测 + 4 变异，`grep -c @Test`=13）全部读源码文本：`source()` 从候选父目录找文件、`codeLines()` 去注释只留代码行（`J2R0eOverrideHookTest.kt:265-284`）。**是文本断言，不是运行时行为测试**，无法证明 r0 有路径字段、无法证明字节真被复制、无法证明 e/f 真被宿主读到。
- 【源码实证·高】四项变异**是真改源码文本、真能判红**（已逐例核对）：
  - `:173-184` `setObjectField(r0,"f"…)` → `"g"`，`writesEAndF` 判红；
  - `:187-196` `getLongField(r0,"a")` → `"i"`，`localIdIsFieldA` 判红；
  - `:199-208` `REF_MD5_PRE="cff4f5a5"` → `"deadbeef"`，`hasContentMd5Gate` 判红；
  - 额外 `:210-222` 删 origin-unresolved 后的 `return`，`hasByteHitGate` 判红。**变异有效，非假变异。**
- **命门缺口（关键）**：`byteLocationByContentMd5`（`:81-98`）对取根的断言仅是「源码里出现 `"/MicroMsg/"` 和 `"[0-9a-fA-F]{32}"` 字符串」（`:87-88`）——纯文本，**永远无法发现 r0 没有携带该字段**。同理 `hasByteHitGate` 只查 `resolveOriginLocal` 在写 e/f 之前、`origin-unresolved` 后跟 return，**只证结构、不证 r0 真能定位到字节**。⇒ 「三闸全绿」在 M1 面前正是假绿：它证明不了本 hook 会在真机生效。
- 结论：MAJOR。变异设计值得肯定，但必须补一条**运行时/结构反向断言**：至少断言「取根不依赖 r0 的 String 字段含 `/MicroMsg/`」或「有外部 tempPath 交接/数据根枚举路径」，否则 M1 类缺陷会再次逃逸。

---

### M8 红线 —— **OK**

- 【源码实证·高】禁改 4 文件（`ForceForward.kt` / `FileSender.kt` / `ForwardCdnFieldHook.kt` / `StockCdnProbe.kt`）的 `git diff` 中 **grep `r0e|J2R0e|forward_r0e` 全部 0 命中** —— 本轮 lane 未触碰。`git status` 里它们显示 M 是**更早轮次**（R76/R86c Q4b/B6）累积的未提交改动，非本轮引入，按任务约束「不推翻 forensics 受保护事实」不予处理。
- 【源码实证·高】新 hook 全文无硬编码 `/data/data`（`grep` 0 命中），cacheDir 取 `HostContext.app()?.cacheDir`（`:172`）。
- 【源码实证·高】无 `P3AdmitGate` / `65536` / `FLOOR` / `b1.u8` / `doNotify` / `q1.j` 写（单测 `redLinesNoHostCalls` + 我的逐行复核）。
- 结论：OK。

---

## 三档标注汇总

| 项 | 严重度 | 主证据等级 |
|---|---|---|
| M1 字节定位必失败 | **BLOCKER** | 【dex 实证·高】+【真机实证·高】+【推断·高·必然】 |
| M2 字段写对 | OK（不可达） | 【源码实证·高】+【dex 实证·高】 |
| M3 时机/多线程 | MAJOR | 【dex 实证·高】+【真机实证·高】+【推断·高】 |
| M4 cleanup | MINOR | 【源码实证·高】+【推断·高】 |
| M5 开关回滚 | OK | 【源码实证·高】 |
| M6 接线 | OK | 【源码实证·高】 |
| M7 单测可信度 | MAJOR | 【源码实证·高】 |
| M8 红线 | OK | 【源码实证·高】 |

---

## go/no-go 明确口径

**NO-GO：现在不能装机做真机 A/B。**

- 理由：M1 不是「可装机但需真机 `R0E skip reason` 验证」的情形——那是给「r0 字段证据拿不到、留 UNKNOWN」时的兜底口径。本案证据已闭合：dex 字段清单（`_s9dump/classes7.txt`）+ 真机同谓词失败日志（`_r86c_v2_dbg.txt:869/1005`）+ 源码逐行复核三方交叉，**定位谓词对 r0 恒 false 已被证明**，「装机后零生效」是前提坐实的高置信推断，而非待验证猜测。j/l/m 的**原始值**在 V2 未打印（描述性 UNKNOWN），但该谓词遍历覆盖全部 7 个 String 字段（含 j/l/m），其真机失败已把「存在完整路径字段」证伪——值未打印 ≠ 谓词未验证。装机只会得到一次确定无变化的 A/B，消耗窗口且无法证伪竞争理论（B2/R79 服务端秒传，`_r86c_microx_route_verdict.md:157`）。
- 转 GO 的前置：M1 修复 + 补单测覆盖 + 三闸重跑；装机后仍须以**肉眼 A→B→A + 出口字节 md5 翻回 cff4f5a5** 为唯一转正判据（`_r86c_postprobe_verdict.md` 结果级判据），`R0E` 日志只作排障记录、不作裁决。

---

## 修复建议（最小改动，reviewer 不自行改）

**推荐方案 A（合规格，需扩权限）：恢复外部字节交接。**
- 规格原设计（`_r76_p3_bytes_landing_plan.md:136-159`）本就把 tempPath 从 FileSender 经 ThreadLocal / 按 imgLocalId 的 TTL Map 传给 R() after；本实现因禁改 FileSender.kt 自造 `resolveOriginLocal` 才踩坑。
- 改法：`FileSender.kt` 在 `q1.a()` 之前把本目标 tempPath 写入 ThreadLocal（finally remove），`J2R0eOverrideHook.onRAfter` 改为从 ThreadLocal/TTL Map 取 tempPath，**删除 `resolveOriginLocal` 的 `contains("/MicroMsg/")` 取根**（`J2R0eOverrideHook.kt:215-250` 整段替换）。
- 代价：需为本轮**重新申请改 FileSender.kt 的权限**（当前是硬红线）。若权限不可扩，则只能走方案 B。

**备选方案 B（不改 FileSender，但风险更高）：换根 + 逐帧身份闸门。**
- `J2R0eOverrideHook.kt:218` 的取根改为 `HostContext.app()?.dataDir` 下枚举 `MicroMsg/<32hex>` 目录（`ForceForward.kt:1340-1343` 范式）；主路径 `:231-234` 的候选 md5 拼法对 hevc 无效（`e64b3b2…` 不在 r0 字段），只保留 `:236-247` 的内容-MD5 兜底枚举。
- **必须同时**增加逐帧身份闸门，仅当本帧 `r0.a`（localId）/ `r0.b`（msgId）等于本次转发目标时才写 e/f——否则如 M3 所述，全局唯一 hevc 会让所有线程的所有帧都被改写。目标 localId/msgId 的可靠来源本轮未取证（【UNKNOWN】），这是方案 B 的最大未闭合点，需另派取证。

**配套（两方案都要）**：单测补一条反向断言，禁止 `resolveOriginLocal` 依赖 r0 String 字段含 `/MicroMsg/` 作为唯一取根；补一条「字节来自外部交接/账户根枚举且带逐帧闸门」的结构断言，堵住 M1 类假绿。

---

## 交付声明

- 全程只读复核：未修改任何源码/测试/开关/文档（除本文件），未运行 gradle，未接触 adb/真机。
- 未推翻任何 forensics 受保护事实；对 r0 字段的结论是对既有 dex/真机事实的引用与交叉验证，非新裁决。
- 诚实标注：新 hook 本身尚无真机 `R0E` 日志（V2 日志早于本轮落码），「装机零生效」为【推断·高·必然】；其定位谓词恒 false 的前提为【dex 实证·高 + 真机实证·高】。
- 唯一写文件：`docs/_r86c_r0e_diff_review.md`。

`[DONE REVIEW]`
