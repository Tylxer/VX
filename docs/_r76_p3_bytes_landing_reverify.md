# R76 · P3「字节落盘」方案 A 实现复核裁决（只读复核子代理）

> lane：只读复核（不改任何源码/测试/配置，不碰 adb/gradle/git，不派子代理）。唯一写操作 = 本文档。
> 复核对象：R76 P3 方案 A 已落盘实现（FileSender.kt 第6参+q1.j 覆盖、ForceForward.kt prepareBigImg/copyP3Temp/installP3CleanupHook、Keys.kt、EntryReceiver.kt、两个 R76P3 单测）。
> 判据设计不重做（R75 双线 + R76 方案 lane 已完成）；本复核只审「实现是否按判据正确落地 + 有没有引入新风险」。
> 证据分档：【源码确定】= dex 反汇编 code_off / 字符串字面 / 源码行号直接可见；【真机确定】= 真机日志/用户肉眼事实（本轮无新增真机，沿用判据文档已记录的真机结论）；【推断】= 由已证实外推，必标置信度。
> 宿主：微信 8.0.48 / versionCode 2588（`_cache/mm.apk`，dex 在 `_cache/dex/classes*.dex`）。锚点直接采信 `docs/_r75_dex_anchor_reverify.md`，不重新反汇编。

---

## 总览（审完逐条回填）

| # | 风险点 | 结论 | 分档 |
|---|---|---|---|
| 1 | 清理钩子关联方式（thisObject String 字段匹配） | 降级可用 | 源码确定 + 推断中置信，命中待真机 |
| 2 | onGYNetEnd 参数判定 0/0 是否正确 | PASS | 源码确定（dex 0x2b0284） |
| 3 | 并发安全（删早/删晚竞态） | PASS | 源码确定 + 推断中置信 |
| 4 | 单调不倒退（默认 null / 开关注入 / 降级分支） | PASS（默认 true 须记账） | 源码确定 |
| 5 | 下载姿势照抄正确性 | **FAIL（核心功能缺口·必修）** | 源码确定 + 推断高置信 |
| 6 | 单测真假绿 | PASS（结构锁，非行为锁） | 源码确定 |

**总体裁决：必修后装机。** 风险点 5 的「异步落盘结果无人消费」使方案 A 对目标坏图实际空转（下载了但没用），这是功能不成立，装机前必须修；风险点 1 关联不可达属可接受降级（不删靠系统回收），但须改正认知与记账。详见文末必修项汇总。

---

## 风险点 1 · 清理钩子关联方式（最高优先级）

**结论：降级可用（不删靠系统回收）——关联几乎不可能命中，但因此反而无「删早截断」风险；不阻断装机，但 `onGYNetEnd 删除` 不得当作 P3 正确性判据。**

分档：**【源码确定】**（tempPath 只写 q1.j、hook 只扫 j2 自身 String 字段、j2 字段清单）+ **【推断·中置信】**（tempPath 不落 j2 String 字段，待真机确认）。

### ① thisObject(j2 实例) 的 String 字段里会不会有 tempPath？—— 源码强烈怀疑「不会」

- 【源码确定】tempPath 的唯一写入点 = `FileSender.kt:216` `XposedHelpers.setObjectField(q1, "j", bigImgPath)`。写入对象是 **`q1`（`hl0/r1.a(o1)` 造出的 builder）**，不是 j2。方案 A 明确没有 hook `j2.R()` 去写 `r0.e/f`（那是方案 B，`_r76_p3_bytes_landing_plan.md` §3.3/§3.5）。
- 【源码确定】清理钩子 `ForceForward.kt:2385-2406`：hook `com.tencent.mm.modelimage.j2.onGYNetEnd`，`afterHookedMethod` 里 `param.thisObject` 就是 **j2 NetScene 实例**，只遍历 `obj.javaClass.declaredFields` 中 `f.type == String::class.java` 的字段，用字段值去 `p3PendingTemps.remove(v)`。它 **不递归进 `j2.q`（`r0` 对象）**。
- 【源码确定】j2 的 String 实例字段清单（`_cache/_s9dump/classes7.txt:751436` 起，`/` `_s9_classes7_dis.txt` 交叉）：`B C F J L P d n o x`（共 10 个）。构造器 `j2.<init>` code_off `0x2aef10` 的反汇编里，路径类 String 只写进 `L(2af0fe)` / `B(2af13e)` / `P(2af1e0,2af208)` / `F(2af3ee)` / `C(2af46a,2af4d0)`；`d=TAG(2aef52)`、`n/o/x=""(2aef5a/5e/a8)`。
- 【源码确定，R75 锚点】真正被 `doScene` 读字节的是 `z3(r0.e/f/g)`，`r0 = b1.o2(j2.i)` 来自 ImgInfo2（`j2.R()` code_off `0x2ad864`）。P3 从不调 `b1.u8`（plan §3.4 红线，ForceForward 全文件无 `u8` 调用），所以 `r0.e` 里仍是 ImgInfo2 原值，**不会是 `_<uuid>.jpg` tempPath**；而 hook 又不扫 `r0`。
- 【推断·中置信】综上，方案 A 下 tempPath 停留在 q1 builder 对象里，j2 自身 String 字段拿到的是构造器 ori / 展示名 / TAG 等，**没有源码证据表明其中任何一个会等于 `cacheDir/_<uuid>.jpg`**。若如此 ⇒ `p3PendingTemps.remove(v)` 恒为 null ⇒ 临时文件**永远匹配不到、不删（孤儿）**。
- **UNKNOWN（需真机定，不编造证据）**：q1.j → j2 构造器 ori 的具体字段映射（`t81/g.a` 字节码）本复核未逐指令追通，无法 100% 排除「某 j2 String 字段恰为 tempPath」。真机验证法：open P3 开关转发坏图后查日志 `FORWARD P3 temp cleaned onGYNetEnd 0/0 name=...` —— 若转发成功（有 `FORWARD resend-file ok=true` + 对端清晰）却**从不出现该行**，即证实孤儿路径。

### ② 会不会误匹配宿主其它 String 字段？—— 概率极低，后果无害

- 【源码确定】`p3PendingTemps` 的 key 全是 `copyP3Temp` 生成的 `cacheDir/_<uuid>.jpg`（`ForceForward.kt:2341`，UUID 随机 32 位十六进制）。宿主 j2 的 String 字段（B/C/F/L/P 等）是 ori/展示名/TAG，取值空间与随机 UUID 路径不重叠。
- 【推断·高置信】即便某宿主字段恰等于该随机串，也只会命中「我们自己的那份 tempPath」（因为只有我们的 key 在表里），删除的仍是我们自己的临时文件；跨目标因 UUID 唯一而不会串味。无「误删宿主图库文件」风险（`_` 前缀隔离，plan §3.1）。

### ③ 有没有更可靠的关联方式？—— 有

- 判据自身已给出两条更稳的路：`_r76_p3_bytes_landing_plan.md` §3.5 方案 B 的 **ThreadLocal**（`q1.a()` 前 set / finally remove，`j2.R()` after 取），或 §8.4 兜底的「**按 imgLocalId/msgId 为 key 的短 TTL Map + 首次命中即移除**」。当前实现两者都没用，选了最弱的「值匹配」。
- 最小改法（若要真删）：在 `FileSender.send` 写 tempPath 到 q1.j 的同一处，把「本目标 tempPath → 本目标 NetScene 标识」显式绑定；或干脆按 §4.2 的进程兜底接受不删。

### 裁决

- **可用，但语义降级为「不删、靠 cacheDir 系统回收」**：plan §4.2 已明确该兜底可接受（残留仅几十 KB~几 MB、匿名 `_<uuid>`、不会被 `resolveImagePath` 当候选、不建清理线程）。当前实现安全（①若不命中则不删 ⇒ 不存在删早截断；②误匹配无害），故不阻断装机。
- **必须做的一件事**：把 `FORWARD P3 temp cleaned onGYNetEnd 0/0` 从任何「成功/已清理」判据里剔除；它最多是 best-effort 日志。若要让它可靠，必须改关联方式（ThreadLocal / msgId key），列为决定项而非可选项。


---

## 风险点 2 · onGYNetEnd 参数判定 0/0 是否正确

**结论：PASS（索引正确，未判反、未把中间态当成功）。但附带一处判据文档的内部瑕疵（非实现 bug）。**

分档：**【源码确定】**（dex 反汇编 j2.onGYNetEnd `0x2b0284` 直接给出 errtype/errcode 的寄存器来源）。

### 索引判定正确

- 【源码确定】`com.tencent.mm.modelimage.j2.onGYNetEnd(IIILjava/lang/String;Lcom/tencent/mm/network/t0;[B)V`，`classes7.dex` code_off **`0x2b0284`**（`_s9_classes7_dis.txt:2126307`，与 `docs/_r34_hdimg_anchor.md:120` 签名一致）。
- 【源码确定】该方法入口反汇编自证参数语义：
  - `2b0298 move v0, v26` / `2b029c move v1, v27`（v26/v27 = 形参列表里的第 2、3 个 int，即 `args[1]`/`args[2]`）；
  - `2b02a4 const-string "cdntra onGYNetEnd errtype:"` → `2b02ae append(v0)` ⇒ **v26 = errType**；
  - `2b02b4 const-string " errcode:"` → `2b02be append(v1)` ⇒ **v27 = errCode**。
  - 即 `args[1]=errType`、`args[2]=errCode`（`args[0]` 是 netId，微信 NetScene 三件套惯例 `onGYNetEnd(netId, errType, errCode, errMsg, t0, [B)`）。
- 【源码确定】hook 实现（`ForceForward.kt:2389-2391`）：`errType = a.getOrNull(1)`、`errCode = a.getOrNull(2)`、`if (errType != 0 || errCode != 0) return`。**与 dex 语义逐位对应**：只有 errType==0 且 errCode==0 才进入删除分支。未判反。
- 【源码确定】hook 方法筛选（`ForceForward.kt:2379-2384`）要求 6 参且前 3 参均为 `int`，与 dex 签名一致，且与 H1 探针（`ImageNetSceneProbe.kt:75-80`）用同一筛选口径；不会挂错重载。

### 未把中间态当成功

- 【源码确定】onGYNetEnd 是一次 NetScene 的终态回调（非多段进度），errType/errCode 一次给定；不存在「先 0/0 后再变非 0」的中间态序列。hook 用 `afterHookedMethod`（`:2386`），取的是终态入参。
- 【推断·高置信】唯一需真机确认的残留：判据文档写的「INP a0=0 a1=0」与探针日志定义冲突——
  - 探针 H1 原文（`ImageNetSceneProbe.kt:86-90`）打印 `a0=args[0] a1=args[1] a2=args[2]`；按 dex，a0=netId、a1=errType、a2=errCode。
  - plan §4.2/§6.4 却把「a0=0 a1=0」称为成功「0/0」。按 dex 这实为「netId=0 + errType=0」，**漏了 errCode(a2)**。
  - **这是判据文档表述瑕疵，不是实现 bug**：实现正确检查了 args[1]/args[2]（errType/errCode），比文档口径更严。真机核对法：`dev.py q 'INP onGYNetEnd'` 看同一行 `a0/a1/a2` 三值，确认成功样本里 `a2(errCode)=0`（若 a2 恒 0 则文档「a0/a1」只是笔误；若 a2 有时非 0，则文档口径会漏判，实现反而对）。
- **裁决：PASS，实现无需改。** 建议（非必修）：把 plan §4.2/§6.4 的「a0=0 a1=0」更正为「a1=0 a2=0（errType/errCode）」，避免后续 lane 误用。


---

## 风险点 3 · 并发安全

**结论：PASS（无「删早截断」；「删晚/不删」已归入风险点 1 的降级语义，可接受）。**

分档：**【源码确定】**（并发原语与调用序列）+ **【推断·中置信】**（竞态时序）。

### 并发原语正确

- 【源码确定】`p3PendingTemps = ConcurrentHashMap`（`ForceForward.kt:319`）：hook 线程与发送线程对它的 `put`/`remove` 线程安全，无 CME。
- 【源码确定】`p3CleanupHookMounted = AtomicBoolean`（`:322`），`installP3CleanupHook` 首行 `compareAndSet(false,true)` 幂等（`:2375`），失败/loader 缺失时 `set(false)` 复位（`:2376/:2410`）。多次 `prepareBigImg`（多图/多轮）不会重复挂 hook；hook 安装异常也不锁死后续重试。
- 【源码确定】`@Volatile p3BigImgSrc`（`:315-316`）：预热线程写、发送线程读可见。

### 关键竞态：会不会「删早」（o6.N 未读完字节 ⇒ 截断红叹号）？

- **不会。** 【源码确定】删除动作只在两处触发：
  1. `ForceForward.kt:2466-2468`：`send()` 返回 **false**（`l1.a()` 未入队）时删——此时 NetScene 根本没跑、`o6.N` 未被调用，删安全。
  2. hook `afterHookedMethod`（`:2386-2405`）：只在 j2.onGYNetEnd 回 0/0（上传 CGI 终态成功）后删——按 R75 锚点 `doScene 0x2ad99c`，`o6.l/o6.N` 读字节发生在 CGI 发出**之前**的 task 构造/发送段，CGI 回 0/0 时字节早已读完，删安全。
- 【源码确定】writing 与入表的顺序也对（`ForceForward.kt:2458-2464`）：先 `copyP3Temp` 建文件 → `FileSender.send`（内部完成 `q1.a()→l1.a()` 入队）→ 入队成功才 `p3PendingTemps[bigImgPath]=true`。**不会先入表后建文件**。
- 【推断·中置信】唯一的时序窗口是：hook 在 0/0 到达时遍历 j2 字段，若此时 tempPath 已进某字段（风险点 1 判定「几乎不会」），则删——但如上，CGI 0/0 = 字节读完，不构成截断。

### 「删晚/不删」

- 若 tempPath 没进 j2 字段（风险点 1 主结论）⇒ 0/0 档永不命中 ⇒ 孤儿，靠 cacheDir 系统回收。属判据 §4.2 已接受的兜底，不是正确性回归（残留文件不影响发送、不被当候选图）。
- 多目标隔离：每目标独立 UUID 临时文件（`ForceForward.kt:2339-2341`，plan §4.1/§4.3），`p3PendingTemps` 按 path 为 key，无共享文件被并发删的问题。

**裁决：PASS，无需改。** 风险点 1 若改为可靠关联（ThreadLocal/msgId），需同步复核新关联方式下 0/0 删除仍满足「CGI 成功后删」——按 dex 该前提成立。


---

## 风险点 4 · 单调不倒退

**结论：PASS（默认 null 逐字节等同修复前；降级分支不写坏路径、不 ANR、不留坏孤儿；开关注入符合用户要求，但与 plan §9.1「默认 false」有偏差，需记录）。**

分档：**【源码确定】**。

### 默认 null 逐字节等同修复前

- 【源码确定】`FileSender.send` 第 6 参 `bigImgPath: String? = null`（`FileSender.kt:135`）。P3 覆盖块整体被 `if (!bigImgPath.isNullOrBlank())`（`:213`）包住——null/blank 时**一行都不执行**。
- 【源码确定】`:173/:176`（q1.i/q1.j=path 缩略图）、`:196-210`（forwardCtx 三项）、`:219`（q1.f(m)）、`:222`（q1.h=13）、`:225`（q1.a()）、`:241`（l1.a()）**一字未动**。P3 块插在 `:211-218`，仅在 `:216` 条件命中时多一次 `setObjectField(q1,"j",bigImgPath)`，优先级 P3>原生大图>缩略图，三处都在 `:225 q1.a()` 之前——与 plan §3.5 完全一致。
- 【源码确定】调用侧 `ForceForward.kt:2458-2459`：第 5 参仍 `snap`、第 4 参仍 `snap.imgLocalId`、第 3 参仍 `snap.localPath`，仅追加第 6 参。**前 5 参逐字未动**。

### 开关默认值

- 【源码确定】`Keys.kt:190` `FORWARD_P3_BIGIMG = "chat.forward_p3_bigimg"`，`Keys.kt:524` PrefSpec 默认 `"true"`（默认开）；`EntryReceiver.kt:261-279` 有 `p3`(on/off/status)/`p3on`/`p3off` 三个分支可即时反向对照。
- **符合用户「默认开」要求**：反向对照靠 `p3off`/`p3 --es sub off` 即可（plan §6.2 的自变量唯一）。
- ⚠️ **与 plan §9.1 的偏差（非 bug，须记账）**：plan 建议默认 `false`（修复增量默认关、真机通过后再单独开）。实现按用户要求改为默认 `true`。后果：装机即生效，若 risks 1/5 成立会直接影响所有用户转发。需确保反向对照开关在真机验收中确实可用，并在台账记录「默认 true 系用户明确要求，覆盖 plan 建议」。

### 降级分支：不写坏路径 / 不 ANR / 不留坏孤儿

- 【源码确定】`prepareBigImg`（`:2233-2280`）与 `copyP3Temp`（`:2326-2365`）全程 `runCatching`，失败一律 `return null` ⇒ `bigImgPath=null` ⇒ FileSender 整块跳过。逐条：
  - loader/localId/msgId 缺失（`:2237`）、`d3.F8()` null（`:2245`）、listener null（`:2259`）、`svc.c` 返回非 true（仅记日志 `:2268`，照抄 AutoHdImg）——均不建文件、不写字段。
  - cacheDir 取不到（`:2328`）、源三门槛不过（`:2334-2338`）、rename 失败（`:2349`）、复核 <15KB（`:2355`）、IO 异常（`:2361`）——删半成品（`:2351/2357/2363`）并返回 null。**write 用 `<uuid>.part`→flush→rename（`:2340-2349`），不会有半截文件被 `o6.l/o6.N` 读到**（plan §3.2/§5.4）。
- 【源码确定】`svc.c`（`:2264-2267`）是异步受理、立即返回，listener 是空代理（`:2286-2298`），**无任何 sleep/await/CountDownLatch**——不阻塞 UI/发送线程，不 ANR（plan §5.3 满足）。
- 【源码确定】FileSender 侧 `setObjectField` 单独 runCatching + onFailure WARN（`:216-217`），异常不外抛，不会把已闭环的 `send()` 从 true 打成 false。
- 【推断·高置信】「不留孤儿」在降级分支成立（失败即删）；唯一残留是风险点 1 的「成功入队但 hook 匹配不到」路径，已按 §4.2 降级为系统回收，非坏路径。

**裁决：PASS。唯一记账项 = 默认 true 偏离 plan §9.1（按用户要求），须在台账说明并确保 p3off 真机可用。**


---

## 风险点 5 · 下载姿势照抄正确性

**结论：FAIL（下载调用逐参照抄正确，但异步落盘结果从未被消费——对目标坏图 P3 实际「空转」，修复不生效）。这是本复核发现的核心功能缺口，列必修。**

分档：**【源码确定】**（调用参数、listener 空代理、`p3BigImgSrc` 赋值/读取点全链）+ **【推断·高置信】**（由此推出坏图场景 P3 空转）。

### ✅ 照抄正确的部分

- 【源码确定】`svc.c` 六参与 AutoHdImg 逐参一致：`ForceForward.kt:2264-2267` =
  `XposedHelpers.callMethod(svc, "c", localId, msgId, TYPE_HD, java.lang.Long.valueOf(msgId), 0, listener)`
  与 `AutoHdImg.kt:475-479` 一字不差。
- 【源码确定】前置链一致：`d3.F8()`→svc（`:2244` vs `AutoHdImg.kt:416`）、`d3.O8().o2(localId)` 取 img 与 `t`（`:2250-2254` vs `AutoHdImg.kt:420-424`）、`TYPE_HD=0`（`:164` vs `AutoHdImg.kt:70`）、`C_IMG_CORE="…d3"`（`:166`）、`C_IMG_LISTENER="fv.e0"`（`:168` vs `AutoHdImg.kt:61`）。单测 `R76P3WireTest.prepareBigImgCopiesAutoHdDownload` 锁的正是这些。
- 【源码确定】`localId=snap.imgLocalId.takeIf{>0}`、`msgId=snap.msgId.takeIf{>0}`（`:2235-2236`），取不到即整段降级（`:2237`），符合 plan §5.1。
- 【源码确定】listener 是**无状态空代理**（`:2283-2299`，逐位照抄 `AutoHdImg.kt:567-587`）：handler 只按方法返回类型给常量 `false/0/0L`，不持外部引用、不回调宿主对象 ⇒ **不会在异步回调里访问已释放对象**（该子问题 PASS，无 UAF/泄漏）。
- 【源码确定】`t==1` 判重（`:2255`）只在「原图已下完」时跳过下载，坏图无大图时 `t` 非 1 ⇒ 不跳过、仍触发下载。逻辑与 AutoHdImg 一致，未判反（该子问题 PASS）。

### ❌ 决定性缺口：下载完成回调没有置位标志，落盘结果无人消费

- 【源码确定】plan §5.3 / §3.5 要求「**预热 + 检查**两段式」：`listener` 回调**置位 volatile 标志**，`resendFile` 读该标志 + 三门禁决定 bigImgPath；「已完成⇒用 P3，未完成⇒发缩略图」。
- 【源码确定】实现偏离：
  1. `newP3Listener`（`:2283-2299`）是空代理，**不置任何标志**；全文件无「下载完成 volatile 标志」，`p3BigImgSrc` 也非回调写入。
  2. `p3BigImgSrc` 只在 `prepareBigImg` 同步赋值**一次**（`:2277`），来源是**下载前**就取好的 `img`（`:2251` 的 `o2(localId)`）经 `p3SrcOf(img)`（`:2271`）读出的 String 字段，取不到回退 `snap.srcBigImgPath`/`snap.localPath`（`:2272-2273`）。
  3. `resendFile` 读的是这个快照字段（`:2458` `copyP3Temp(p3BigImgSrc)`），**不再回读 ImgInfo2**。全文件 `p3BigImgSrc` 仅 :316 初始化 / :2277 写 / :2458 读三处。
- 【推断·高置信】后果（针对本方案要修的目标坏图）：坏图本地无大图 ⇒ `p3SrcOf(img)` 在下载完成前读 `img` 的 String 字段，命中不到 >15KB 非 th_ 的大图路径 ⇒ 回退 `snap.srcBigImgPath`（坏图为空）/`snap.localPath`（缩略图）⇒ `copyP3Temp` 三门禁（`:2334`）挡掉缩略图 ⇒ `bigImgPath=null` ⇒ 发缩略图，**模糊照旧**。异步 `svc.c` 真下载落盘的那份大图，没有任何代码在完成后回读并喂给 `FileSender`。
- 【推断·高置信】P3 唯一能产出 bigImgPath 的情形是「下载前 ImgInfo2/ snap 里已存在合格大图」（`t==1` 或 `p3SrcOf` 命中）——而这恰是 R73 原生大图路径（`snap.srcBigImgPath`）本就能覆盖的「本来就不模糊」场景（plan §6.3），P3 对真正的坏图无边际价值。
- 根因：把 AutoHdImg 的「fire-and-forget 空 listener」姿势照抄到了一个**必须同步拿结果**的场景。AutoHdImg 不需要完成回调（下完写 ImgInfo2，下次冷启/点开自然可读）；P3 需要，却没接。

### 必修修法（择一，交实现 lane）

1. 让 listener 在下载成功回调里**回读 `d3.O8().o2(localId)` 的 String 大图字段**（复用 `p3SrcOf`）并写 `@Volatile p3BigImgSrc`；`resendFile` 用「标志 + 三门禁」两态判定（plan §5.3 原设计）。注意回调线程（plan §8.6 UNKNOWN）与「不访问已释放对象」——只反射读宿主对象、只置 String/boolean，不引用 Activity/模块状态。
2. 或在 `resendFile`/`copyP3Temp` 前**现取一次 `o2(localId)`**（而非复用预热时的快照），用当次结果过三门禁；配合轻量「已落盘」标志避免读到下载中的半截文件（`.part→rename` 已保证单文件原子，但需防「还没开始下」被当旧缩略图）。
- 无论哪种，须补真机判据 §6.1 的「开 P3 对坏图 F2 `z3 out` 指非 th_、对端 ≫15KB、清晰」——当前实现预期该项**不满足**。

**裁决：FAIL，必修（核心功能缺口）。** 调用参数照抄 PASS，但异步结果消费缺失使方案 A 对目标坏图不生效。


---

## 风险点 6 · 单测真假绿

**结论：PASS（无假绿：所有断言走 `codeLines()` 去注释判定；6 个变异均对「真实源码文本的内存副本」做修改并判红）。但须声明这两个测试是「结构锁」而非「行为锁」，覆盖不到风险点 1/5 的功能缺口。**

分档：**【源码确定】**（测试源码逐行可读）。按 lane 纪律未跑 gradle，故「绿/红」是基于断言与源码字符串静态比对推定，非观测到的测试运行结果。

### 去注释判定 = 真

- 【源码确定】两个测试的所有 `source()` 调用都经 `codeLines()` 包裹：`R76P3FileSenderTest.kt` 的 :42/:56/:67/:76/:100/:109/:116，`R76P3WireTest.kt` 的 :41/:50/:79/:86/:96/:137/:144/:151。
- 【源码确定】`codeLines()`（FileSenderTest :180-183 / WireTest :210-213）`map trim` 后 `filter { 非空 && !startsWith("//") && !startsWith("*") && !startsWith("/*") }`，去掉整行注释。注释里的历史签名/死字符串无法制造绿。✓

### 6 个变异真改内存源码并判红 = 真

逐条核对「变异目标串真实存在于源码」+「变异后判据必为 false」：

| 测试 | 变异 | 目标串在源码 | 判据失效机理 | 判定 |
|---|---|---|---|---|
| FS M1 | `bf.length() > 15 * 1024`→`5 * 1024` | FileSender.kt:215 ✓ | `p3ThresholdsOk` 要求该精确串（`R76P3FileSenderTest.kt:159/161`），替换后无命中→false | 真红 ✓ |
| FS M2 | filterNot `bigImgPath: String? = null,` | :135 ✓ | `sixParamSignatureOk` 参数表含该项（:136），删后 indexOf=-1→false | 真红 ✓ |
| FS M3 | 交换 `setObjectField(q1,"j",bigImgPath)` 与 `callMethod(q1,"f",m)` 两行 | :216 / :219 ✓ | `p3OrderOk` 要 p3<fCall（:153），交换后 p3 在 fCall 后→false；且有前置断言 p3<fCall（:119）保证变异前成立 | 真红 ✓ |
| Wire M1 | `15 * 1024`→`5 * 1024`(FF 全文) | ForceForward.kt:2313/2334/2355 ✓ | `thresholdsAndPartOk` 要 `srcFile.length() > 15 * 1024` 与 `temp.length() <= 15 * 1024`（:169/:176），替换后失败→false | 真红 ✓ |
| Wire M2 | `snap, bigImgPath)`→`snap)` | ForceForward.kt:2459 ✓ | `sixthArgOk` 要 6 参且精确串（:161-163），改后 5 参→false | 真红 ✓ |
| Wire M3 | filterNot `"p3off" -> {` | EntryReceiver.kt:276 ✓ | `entryOpsOk` 要 p3off 分支（:189），删后→false | 真红 ✓ |

- 【源码确定】变异是在 `codeLines(source(...))` 的 `List<String>` 副本上 `map`/`filterNot`/交换元素，再喂同一判据函数——是「对真实源码内容做内存变异」，不是纸面声明。✓
- 【源码确定】锁值断言真实：`sixthArgOk` 精确比 `kind,target,snap.localPath,snap.imgLocalId,snap,bigImgPath`（WireTest :162-163），`keysOk` 锁 `chat.forward_p3_bigimg`+默认 `"true"`（:180-183），与 Keys.kt:190/524 一致。✓

### 重要限制：这两个测试是结构锁，证明不了 P3 生效

- 【源码确定】所有断言都是「源码里有没有这行文本 / 文本顺序」，属纯 JVM 文本比对，零运行时行为。
- 【推断·确定】因此：
  - `prepareBigImgCopiesAutoHdDownload` 只验 `svc.c` 六参照抄与 `t==1`，**验不到 listener 是否置位完成标志**——风险点 5 的功能缺口（下载结果无人消费）不会被判红。
  - `cleanupThreeTiersLocked` 只验 `p3PendingTemps[bigImgPath]=true` 与 `errType/errCode` 判定文本，**验不到 j2 字段里是否真有 tempPath**——风险点 1 的孤儿路径不会被判红。
- 即：**6 点全绿 ≠ 方案 A 生效**。测试真实性 PASS，但不能拿它替代 §6.1 真机判据（F2 `z3 out` / 对端字节 / 肉眼）。

**裁决：PASS（测试本身不假绿）。附加强制说明：不得以单测全绿作为装机依据，真机 §6.1 未验前 P3 有效性未证。**


---

## 必修项汇总与最终裁决

### 总体裁决：必修后装机（不建议撤回方案 A，缺口可局部修补）

方案 A 的接线骨架（第 6 参 / q1.j 覆盖块 / 三门槛 / 多目标独立文件 / 降级不倒退 / 开关与广播）**逐字节按判据落地，质量高**；Hook 参数索引（风险 2）、并发原语（风险 3）、不倒退（风险 4）、单测真实性（风险 6）均 PASS。**但风险点 5 是功能性硬伤**：实现照抄了 AutoHdImg 的 fire-and-forget 空 listener，却没有接通「下载完成 → 回读大图 → 喂给 FileSender」这一段，导致对用户要修的目标坏图 P3 空转、模糊照旧。这不是可降级项，是方案目的不成立，必须修。修法局部（改 prepareBigImg/resendFile 的源解析与标志），不推翻方案 A 的 q1.j 链路。

### 必修项（P0，装机前）

1. **【P0-1 · 风险 5】接通异步落盘结果的消费。** 让下载完成回调回读 `O8().o2(localId)` 的 String 大图字段并写 `@Volatile` 标志/路径，`resendFile` 按「标志 + 三门禁」两态决定 bigImgPath（plan §5.3 原设计）；或在发送前现取一次 `o2(localId)` 而非复用预热快照。禁止用空 listener 的 fire-and-forget（AutoHdImg 可那样做，P3 不行）。须满足不访问已释放对象（只反射读宿主对象 + 置 String/boolean）、不阻塞线程。
   - 验收：真机 §6.1 第 1/2/3 条——坏图开 P3 后 F2 `z3 out` 指非 th_、对端 ≫15KB、肉眼清晰；未过不得装机。

### 应修项（P1，强烈建议同轮处理）

2. **【P1 · 风险 1】清理钩子关联不可达。** 当前 `thisObject` String 字段匹配的临时文件删除几乎必然不命中（`ForceForward.kt:2392-2405`）。二选一：(a) 改为可靠关联（ThreadLocal 或 msgId/imgLocalId 为 key 的短 TTL Map + 命中即移除，plan §3.5/§8.4），并复核 0/0 删除仍在 CGI 成功后（风险 3 前提成立）；(b) 明确定位为「best-effort、失败即靠 cacheDir 系统回收」，并把 `FORWARD P3 temp cleaned` 从任何成功判据里剔除。

### 记账项（非 bug，须写入台账/文档）

3. **【记账 · 风险 4】默认开关 `true`** 覆盖 plan §9.1 的「默认 false 建议」，系用户明确要求。装机即生效 ⇒ 必修项 1 未修前全局受影响；须确保 `p3off`/`p3 --es sub off` 真机可用，台账注明此决策来源。
4. **【记账 · 风险 2】plan §4.2/§6.4 把 `a0=0 a1=0` 称成功「0/0」** 与探针日志定义冲突（a0=netId，成功应是 a1=errType=0 且 a2=errCode=0）。实现按 dex 正确检查了 args[1]/args[2]；建议更正判据文档措辞，避免后续 lane 误用。
5. **【记账 · 风险 6】单测全绿不代表 P3 生效**：两个测试是纯源码结构锁，验不到风险 1/5 的功能缺口；不得以单测绿作为装机依据，须以真机 §6.1 为准。

### 真机必验清单（修完必修项后）

- §6.1 正向四条全中（尤其 F2 `z3 out` 非 th_ + 对端 ≫15KB + 多目标各自路径不同）；
- §6.2 反向：`p3off` 后同一坏图退回模糊、字节 ~15KB（证明开/关差异真实、非假阳性）；
- §6.3 不倒退：普通本地大图开关两轮均清晰；
- §6.4 假阳性排查：不得用 `ok=true` / `onGYNetEnd 0/0` 单独下结论；
- 顺带核对 INP `a0/a1/a2` 三值，确认 errCode(a2) 在成功样本为 0（记账项 4）。

### UNKNOWN（本复核不编造源码证据）

- **风险点 1 的 j2 String 字段是否真含 tempPath**：源码层面强烈怀疑「不含」，但 q1.j→j2 构造器 ori 的逐指令映射未追通，标 UNKNOWN。真机验证法见风险点 1 ①。
- **风险点 5 的下载完成回调线程**：plan §8.6 已标 UNKNOWN；修必修项 1 时需真机确认回调线程以免新引入并发问题。
- **单测实际运行结果**：按 lane 纪律未跑 gradle，测试真相判定基于静态断言/源码比对，非观测到的 EXIT=0。

---

[DONE] 6 风险点全部审毕并落盘（1 降级可用 / 2 PASS / 3 PASS / 4 PASS / 5 FAIL 必修 / 6 PASS），总体裁决=必修后装机。
