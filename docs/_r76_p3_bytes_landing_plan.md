# R76 · P3「字节落盘」实施方案（只读设计，交 Flash 落码 / 真机验证）

> lane：`r76-p3-bytes-landing-plan`（Pro 档，只读设计）。唯一写操作 = 本文档。
> 纪律：不改任何源码/测试/配置，不碰 adb/gradle/git，不派子代理。全程中文。
> 证据分档（三档）：【源码确定】= dex 反汇编 code_off / 字符串字面 / 源码行号直接可见；【真机确定】= 真机日志行 / 用户肉眼事实；【推断】= 由已证实外推，必须标置信度。
> 宿主：微信 8.0.48 / versionCode 2588（权威源 `_cache/mm.apk`，dex 在 `_cache/dex/classes*.dex`）。
> 结论锚点直接采信 R75 独立反汇编复核（`docs/_r75_dex_anchor_reverify.md`），不重新反汇编。

---

## 0. 一句话结论

P3 = **在转发前把「源图大图字节」落到本地文件，并让 stock j2 经 `z3(r0.e)` 读到它**。
推荐主入口是「**触发宿主原生下载（DownloadImgService，R34 ① 已取证、AutoHdImg 已用）→ 等落盘 → 复用现有 `resolveImagePath`/`q1.j` 链重发**」；若该链在真机上仍指到缩略图，回退到「**hook `j2.R()` after → 直接 `setObjectField(r0,"e",tempPath)` 覆盖出站记录的大图路径**」。两条都给出精确接入点，判据互斥可验收。

---

## 1. 目标与红线

### 1.1 终局定义（唯一判据）

> 【源码确定】`modelimage/j2.doScene`（`classes7.dex` `0x2ad99c`）读图字节链 =
> `j2.R()`(`0x2ad864`) → `b1.o2(j2.i)` → `r0` → `b1.z3(r0.e/f/g)` → `vfs/o6.l(长度)` → `vfs/o6.N(读字节)`。
> 上传 task 的 `field_fullpath = z3(r0.e)`（R75 §2/§3）。

**终局 = 我方发起的这条转发里，`z3(r0.e)` 解析出的本地文件是「大图字节」**（非 `th_` 前缀、字节 ≫15KB、≈源图分辨率）。达成即可，不关心 `r0.u`/cdninfo 是否被写（R74 Q4 已收窄）。

### 1.2 红线（不碰，已闭环）

1. **不改 `CONSTRUCTOR_ID_IMAGE = 13`**（`FileSender.kt:94`）与 `Q1_I_DEFAULT=4`/`Q1_O_RES_ID`。
2. **不碰 m.a / q1 已闭环修复**：`imgLocalId`（`Snapshot.imgLocalId` → `hl0/m.a`）、`srcMsgId`/`srcTalkerPrefix`/`srcBigImgPath`（`FileSender.kt:193-206`）**一字不动**，P3 在其后叠加。
3. **不重建 k2/ot5、不动 `q1.B`**：这些是上传构造体，证据不足，碰了等于赌微信不崩。
4. **不持久化写坏宿主表**：不主动 `b1.u8`（见 §3.4）。

### 1.3 单调性（不倒退）

取不到字节 / 下载失败 / r0 拿不到的任何一条分支，都**逐字节等同现状**：
`resendFile` 仍传 `snap.localPath`（缩略图），`FileSender.send` 第 5 参 `forwardCtx` 仍传 `snap`，
`srcBigImgPath` 取不到仍留 `""`（`ForceForward.kt:1342-1351` 判据不变）。**P3 只做「有机会时把大图路径补上」，绝不做「没机会时改坏路径」**。

---

## 2. 拿字节的可行入口对比

> 坏图事实（R75 采信）：`md5=216b1f7ccd…`，本地 36B 占位 + ~15KB 缩略图，**无大图**；源 `imgLocalId=883190`。
> `msgSvrId` **可取**：`msgRefFromH9` 已取 `h9.field_msgSvrId`（`ForceForward.kt:1544`）→ `Snapshot.msgSvrId`（`ForwardLogic.kt:388`）→ `resendFile` 经 `snap` 拿到（`ForceForward.kt:2232`）。`imgLocalId` 经 `imgLocalIdOf`（`ForceForward.kt:1255`）取 `r0.a`。

四条入口（任务点名）+ 一条补充，按「是否真能拿坏图大图字节」排序：

| # | 入口 | dex 锚点（R75） | 能力 | 前置条件 | 返回 | 坏图能否拿到大图字节 | 风险 |
|---|---|---|---|---|---|---|---|
| A | **DownloadImgService.c**（补充，R34 ① 已取证） | `d3.F8()`→`m`；`m.c(JJILt0;I)Z` | **真下载**（getmsgimg CGI 109 → t1 → 落盘） | `localId`+`msgSvrId`（都已有） | boolean（受理≠完成） | ✅ 是（唯一真下载器） | 异步，需等落盘；AutoHdImg 已用，姿势已定 |
| B | `b1.R5` | `0x2a710c`，`R5(String,String,I,I,I,PString,PInt,PInt)J` | 下载大图落 image2 + 返回新 localId（R75 标注） | 5 个 value 参数语义 **U1 未知** | localId(J) + PString path | ✅ 理论上（但参数未取证） | **高**：盲调 = 赌参数 |
| C | `z3(SERVERID://)` | `0x2a6ae4`→`Y2(msgSvrId)`(`0x2a363c`)→`r0.e` | **解析** server 引用→本地路径（不下载） | msgSvrId 可解析 | 路径 String | ❌（只解析，坏图解析出缩略图/空） | 低 |
| D | `b1.q1(J,[B,PString,PInt,PInt)Z` | `0x2a2fa0` | 读本地记录字节到 buffer（不下载） | 大图已本地 | boolean + byte[] | ❌（坏图无本地大图） | 低 |
| E | `o6.N(String,II)[B` | —（`o6.l` 取长度、`o6.N` 读字节） | 读本地文件字节（不下载） | 路径存在 | byte[] | ❌（单独无路径） | 低 |

### 2.1 主选与理由

- **拿字节主选 = A（DownloadImgService.c）**，理由：R34 ① 指令级已把下载链坐实（`m.b` 入队 → `m.h` new `t1` → `p1.f(t1)`），且 **AutoHdImg.kt:411-420 已在用同款反射**（`d3.F8()` 取 service、`d3.O8()` 取 storage、`o2(Long)` 取 r0），姿势真机验证过。相比 B（R5）参数 U1 未知、盲调风险高，A 是唯一「已取证且已在用」的真下载器。
- **路径解析副选 = C（z3 SERVERID://）**：下载落盘后，用 `z3("SERVERID://"+msgSvrId, …)` 或直接再跑一次 `resolveImagePath`（`ForceForward.kt:1166`，其 `pickImageFile` tier-1 会命中新落盘的 `<md5>.jpg` 大图）拿到大图绝对路径。
- **读字节末选 = E/D**：只在需要「把大图文件拷贝成独立临时文件」时才用（见 §3 方案 B）。

### 2.2 关键澄清：坏图「拿字节」的本质是「触发下载」，不是「读字节」

四条任务点名的入口里 **没有一条自己会下载**（B 疑似下载但参数未取证；C/D/E 都只读/只解析）。
坏图的大图字节**根本不在本地**（36B 占位），所以「读字节」之前必须先「下载」。真正被 R34 ① 坐实的下载器是 **DownloadImgService**（入口 A）。这条澄清决定 §3 的落盘方案必须**先解决下载**，否则四条入口对坏图全是空转。

### 2.3 从哪取 localId / msgSvrId

- `msgSvrId`：`Snapshot.msgSvrId`（`ForwardLogic.kt:388`），来自 `h9.field_msgSvrId`（`ForceForward.kt:1544`），【源码确定】。
- `imgLocalId`（供 DownloadImgService.c 第 1 参）：`Snapshot.imgLocalId`（`ForwardLogic.kt:397`），来自 `imgLocalIdOf` 的 `b1.Q2(talker,msgId)`/`a3(talker,msgSvrId)` → `r0.a`（`ForceForward.kt:1273-1282`），坏图真机已取到 `883190`，【真机确定】。

---

[DONE0] 骨架 + §0/§1/§2

---

## 3. 落盘方案（A 主 / B 备，两条都给精确接入点）

> 本节只解决 §2.2 澄清后的唯一问题：**坏图大图字节不在本地 ⇒ 先下载、再落盘、再让 `z3(r0.e)` 读到它**。
> 终局锚点（§1.1）：`j2.O`（`classes7.dex 0x2acc44`）把 `field_fullpath` 填成 `z3(r0.e)`；`doScene`（`0x2ad99c`）走 `b1.z3(r0.e/f/g) → vfs/o6.l → vfs/o6.N` 真正读字节。**"落盘"因此分两层**：① 字节落到一个我方可控的本地文件；② 让出站 r0 的 `e`/`f` 指向它。

### 3.1 临时文件路径（对齐微X `cache/_<uuid>`）

- 【真机确定】微X真机：临时大图放在 `cache/_<uuid>`，发送后删除，对端收到清晰大图。我方**不可复制其加密 dex 的字节来源**，只能复现这个终局形态。
- 我方路径根 = 宿主 application 的 `cacheDir`。取法沿用模块已有的 application context 获取范式（`install`/`EntryReceiver` 阶段取一次并缓存）；**取不到 context 就直接降级（§5.1），绝不猜 `/data/data/<pkg>/cache` 硬编码路径**。
- 文件名 = `"_" + UUID.randomUUID().toString().replace("-", "") + ".jpg"`：
  - `_` 前缀 + UUID 对齐微X的匿名临时文件命名，**也让"发后删除"不会误删宿主图库文件**；
  - 带 `.jpg` 扩展名：方案 B 直接 `setObjectField(r0,"e",absPath)` 时扩展名不参与判据，方案 A 依赖 `resolveImagePath`/`pickImageFile` tier-1 命中 `<md5>.jpg` 时要过 `ForceForward.kt:1345-1351` 的 `bigExt` 门禁 ⇒ 统一带 `.jpg` 两边都安全。【推断，低风险】
- 准入三门槛（与 `ForceForward.kt:1346-1351` 的 `srcBigImgPath` 判据同构，【源码确定】的是后者的源码逻辑）：`File.exists() && length() > 15 * 1024 && !name.startsWith("th_")`。三条任一不满足 ⇒ 该文件不得进入路径覆盖。
- 写入位置只在 `cacheDir`，**不写宿主图库 / ImgInfo2 目录 / 任何 DB 文件**（红线 §1.2.4）。

### 3.2 大图字节写入

- 字节来源两条（对应 §2.1 的主/末选）：
  - **方案 A（主）**：DownloadImgService 下载落盘后，宿主自己的大图文件已经存在。路径用 §2.1 副选 C `z3("SERVERID://"+msgSvrId, ..., true)` 解析，或再跑一次 `resolveImagePath`（`ForceForward.kt:1166`，其 `pickImageFile` tier-1 命中新落盘的 `<md5>.jpg`）。拿到路径后用标准 `FileInputStream → FileOutputStream` 复制到 §3.1 的临时文件。
  - **方案 B（备，仅当 A 链仍指缩略图时才需要读字节）**：`b1.q1(J,[B,PString,PInt,PInt)Z`（`0x2a2fa0`）或底层 `o6.N(String,II)[B` 读字节到 `byte[]`，再写临时文件。二者都是读本地文件，不下载（§2.2 已澄清）。
- 写入纪律：
  1. 先写同目录 `<uuid>.part`，`flush()+close()` 后再 `renameTo(_<uuid>.jpg)`，避免半截文件被 `o6.l`/`o6.N` 读到（长度不足 ⇒ 上传 task 读空 ⇒ 红叹号）。【推断，低风险】
  2. 复制后复核 `length() > 15*1024` 才放行；不达标 ⇒ 删除临时文件并降级（§5.2）。
  3. 全程 `runCatching`，任何 IO 异常只丢这一次 P3，不影响 `send()` 外层已有的 `runCatching`（`FileSender.kt:158/237`）。

### 3.3 `r0.e`/`r0.f` 写入的精确时机（本节最关键）

**先纠正一个会写错代码的点**——【源码确定，R75 §5 / `docs/_r75_dex_anchor_reverify.md` 锚点 E】：

| 方法 | code_off | 实际写入字段 | 副作用 |
|---|---|---|---|
| `r0.l(String)` | `0x2b3e94` | **`j`**（不是 e） | 置 `P=true` 脏标记 |
| `r0.n(String)` | `0x2b3ed4` | **`f`** | 置 `B=true` 脏标记 |

而 `j2.O`（`0x2acc44`）里四次 `b1.z3` 分别吃 `r0.e / r0.f / r0.g / r0.j`，**`field_fullpath` 取的是 `z3(r0.e)`**（R75 §2）。⇒ 结论：

- **调用 `r0.l()` 并不能写 `r0.e`**。要让 doScene 读到我方大图，必须用 `XposedHelpers.setObjectField(r0, "e", tempPath)` 直接写 `e`；`f` 可用 `r0.n(tempPath)` 或直接 `setObjectField(r0, "f", tempPath)`。**建议 e+f 双写**，覆盖 doScene 的两处读取（e 决定 field_fullpath，f 对应 R75 §3 提到的"原图"文件读取）。
- 【真机确定】微X真机 `r0.u=EMPTY`、下载链全零 ⇒ 它走的是 `j2.O` 里 `ok0/m1.n(1)` 短路后的 CDN task 构造段，而非 `r0.u` 判空 early-return 段（R75 §2 的分支方向）。我方不依赖 `r0.u`，只保证 `e/f` 指向大图字节。

**时机窗口（三步严格有序，都不能挪）**：

1. **在 j2 构造读 ImgInfo2 之后** —— 【源码确定】`j2.R()`（`0x2ad864`）= `if (q==null) q = b1.o2(Long.valueOf(j2.i)); return q;`，即 r0 实例是在 `R()` 里才从 `o2(j2.i)` 取到并缓存到 `j2.q` 的。构造期就写会被随后的 `o2` 整对象覆盖。
2. **在 `j2.O`/`doScene` 读字节之前** —— 【源码确定】`doScene 0x2ad99c` 内 `z3(r0.e) → o6.l → o6.N`；晚于此则已读走缩略图字节。
3. **精确落点 = hook `j2.R()` 的 after** —— 【源码确定，由 1+2 推出】`R()` 返回的正是缓存实例 `q`，而 `O()`/`doScene` 用的是同一个 r0；after 回调里 `setObjectField(param.result, "e"|"f", tempPath)` 即在窗口内。这与 §0 的回退方案完全一致。
- 【推断，中置信】若真机日志证明 `j2.O()` 在 `R()` 之前被调（当前 dex 证据是 `O(r0;I)` 必须持有一个 r0 入参，故必先拿到 r0，概率低），则把落点前移到 `j2.O()` before。F2（`MIO z3 out ...`）/ D5（`MIO j2O in/out`）的先后顺序可直接判，无需改探针。

### 3.4 `b1.u8` 是否需要持久化 —— 明确不调

- 【源码确定，R75 §5】`b1.u8(Long, r0)I`（`0x2a5398`）：`r0.c()` 转 `ContentValues` → `z84/z.c(..., "id=?", ...)` UPDATE **ImgInfo2 表** → `r0.a()` 清脏 → `doNotify()` 通知观察者。
- **结论：P3 禁止调用 `u8`**。三条理由：
  1. §1.2.4 红线「不持久化写坏宿主表」；
  2. 临时文件发后即删（§4.2），若把它的路径 UPDATE 进 ImgInfo2 表，宿主下次读图会指向已删除文件 —— 坏路径被永久化，且 `doNotify()` 会把坏值推给观察者；
  3. 本次 doScene 只读内存中的 r0，`e/f` 在 §3.3 窗口内覆盖已足够，不需要落库。
- 若真机发现"不落库则 O() 拿不到"，**优先改用 §3.3 的 R() after 覆盖**，仍不要退到 u8。
- 微X是否调 u8 属 §8 UNKNOWN；即便它调了，它也用自有加密 dex 字节源，我方 A/B 方案无需 mimic。

### 3.5 精确接入点（落到源码行）

**方案 A（主，不需要新增 hook）**

- 触发下载：`ForceForward.kt` 在长按冻结 ref 之后、爆发发送之前调一次 `prepareBigImg(snap)`，复用 `AutoHdImg.kt:415-479` 已真机验证的姿势：`d3.F8()` 取 service → `svc.c(localId, msgId, TYPE_HD, Long.valueOf(msgId), 0, listener)`（`TYPE_HD` 同 AutoHdImg）。`localId` 取 `Snapshot.imgLocalId`（`ForwardLogic.kt:397`），`msgId` 取 `Snapshot.msgId`（`:383`）。**不改 `imgLocalIdOf`(`:1255`)/`snapshotOf`(`:1298`)/`resendFile`(`:2205`) 的任何已闭环取值**（§1.2.2），只在它们之后叠加。
- 路径透传：`FileSender.send` 增加**第 6 个可选参数** `bigImgPath: String? = null`（默认 null ⇒ 所有老调用点逐字节不变，§1.3）。在 **`FileSender.kt:207`**（`forwardCtx?.let{...}` 块结束）之后、**`:208`**（`q1.f(m)`）之前插入覆盖块：
  ```
  if (!bigImgPath.isNullOrBlank()) {
      val bf = File(bigImgPath)
      if (bf.exists() && bf.length() > 15*1024 && !bf.name.startsWith("th_"))
          runCatching { XposedHelpers.setObjectField(q1, "j", bigImgPath) }
              .onFailure { LogX.w("FileSender P3 q1.j skipped: ...") }
  }
  ```
  `:214` 的 `q1.a()` 会经 `t81/g.a` 读 `q1.j` 取大小、决定"当大图不压缩"（`FileSender.kt:46-49` 的取证结论）。
- 优先级说明：`:173` 写 `q1.j=path`（缩略图）→ `:204-206` 可能用 `srcBigImgPath` 覆盖（原生大图）→ **本次 P3 在其后再覆盖** ⇒ 形成「P3 大图 > 原生大图 > 缩略图」的单调优先级；三处都在 `:214 q1.a()` 之前，且 P3 缺省时 `:173/:204-206` 的结果一字不变。
- 调用侧：`ForceForward.kt:2232` 的 `FileSender.send(kind, target, snap.localPath, snap.imgLocalId, snap)` 追加第 6 参 `bigImgPath`（本目标临时文件路径，取不到传 `null`）。

**方案 B（备，仅当 A 在真机上 F2 `z3 out` 仍指 `th_` 时启用）**

- 新增 `feature/P3BigImgOverride.kt`：`install()` 时 `XposedBridge.hookMethod(j2.R(), after)`；回调里按 §3.3 写 `r0.e/f`。
- 交接方式：`FileSender.send` 在 `:214 q1.a()` 之前把**本目标** tempPath 写进 `ThreadLocal<String>`（在 `finally` 里 remove）；R() after 回调从 ThreadLocal 取。线程局部是本方案成立的前提，须按 §8 取证确认 `R()` 与 `q1.a()` 同线程；不成立则改走「按 imgLocalId 为 key 的短 TTL Map + 只匹配一次即移除」的兜底（仍不共享文件，§4）。
- 方案 B 不改 `FileSender.kt:173/204-206/214` 的任何既有字段写入，只在 `:214` 前后各加一行 ThreadLocal set/remove。

[DONE §3]

---

## 4. 多目标隔离（一份目标 = 一份临时文件）

> 背景：`Snapshot.imgLocalId`/`msgSvrId`/`srcBigImgPath` 是**源消息属性**，对所有目标相同（注释见 `ForwardLogic.kt:395/401`）；但本方案的临时文件是**本次发送尝试的属性**，必须逐目标独立。爆发转发 N 个目标 ⇒ N 份临时文件。

### 4.1 硬规则

1. **每个目标在 `resendFile(snap, target)` 内部自行生成 UUID 临时文件**（`_<uuid>.jpg`，§3.1），不复用、不共享、不缓存给下一个目标。
2. 字节来源可以共享（宿主下载好的那一份大图是只读的），**复制出的可写临时文件不共享**。
3. 该目标拿不到字节 / 下载失败 ⇒ 该目标不建临时文件、直接按现状发缩略图（§5），不受其它目标结果影响。
4. 方案 A 的 `q1.j`（`FileSender.kt:173/204-206`）与方案 B 的 `r0.e/f`（`P3BigImgOverride.kt`）都只接收**本目标**的 tempPath。

### 4.2 清理时机

- **正向清理**：本目标 `j2.onGYNetEnd` 回 `0/0`（【真机确定】INP 日志 `INP onGYNetEnd a0=0 a1=0 a2=...`，`ImageNetSceneProbe.kt` H1 hook）**且**对端已收到该图（真机肉眼/对端相册 Bytes）之后，才 `File.delete()` 该目标的临时文件。
  - 为什么必须两个条件都满足：`onGYNetEnd 0/0` 只证明**我方上传 CGI 成功**（§6.4 假阳性），对端是否落库是另一回事；单看其一就删，可能在微信尚未读完文件（`o6.N` 还在读）时 unlink ⇒ 上传字节截断 ⇒ 红叹号。
- **异常清理**：`send()` 返回 false、复制/写入异常、P3 降级（§5 任一条）⇒ 在退出该目标分支前 `runCatching { File(temp).delete() }`，不留孤儿文件。
- **进程/冷启兜底**：临时文件在 `cacheDir`，Android 可在空间紧张时清理；即便残留也只占几十 KB~几 MB，且路径是匿名 `_<uuid>`，不会被 `resolveImagePath` 当成候选图（其 tier 按 md5/`th_` 命名匹配，`ForceForward.kt:361-365`）。不额外建清理线程（避免过度工程）。

### 4.3 为什么不能共享（三条独立理由）

1. **竞态删除**：N 个目标异步上传，各自 `onGYNetEnd` 到达时间不同。若共享一份文件，先完成的目标删除后，后完成的目标 `o6.l(path)` 取长度=0 / `o6.N` 读空 ⇒ 后者红叹号。**逐目标独立文件把删除作用域缩到单个目标。**
2. **并行改写**：方案 B 若用 R() after 覆盖 r0.e/f，N 个 NetScene 各持自己的 r0 实例，但若 tempPath 是全局单值，后写覆盖先写 ⇒ 目标 A 的 r0 指向目标 B 的文件，甚至该文件已被 B 删除。ThreadLocal（§3.5）或逐目标 Map 都要求文件本身也是逐目标的。
3. **判据可观测性**：§6 的"多目标都收到"要求能对**每个目标**独立核对 `z3 out` 路径与对端字节。共享路径会让所有目标的 F2 日志打同一个文件名，无法分辨是谁的字节、也无法定位是哪一次删早了。
- 【真机确定】微X的形态本身就是 `cache/_<uuid>`（每图一份、发后删），不是单一共享文件；我方复现其终局而非其加密字节来源。

[DONE §4]

---

## 5. 失败回退与不倒退（单调追加的铁律）

> 总原则（§1.3）：**P3 只做"有机会时把大图路径补上"，绝不做"没机会时改坏路径"**。任何取不到 / 下载失败 / 异常分支，都必须**逐字节等同现状** —— `resendFile` 仍传 `snap.localPath`（缩略图）、第 5 参 `forwardCtx` 仍传 `snap`、`srcBigImgPath` 取不到仍留 `""`（`ForceForward.kt:1342-1351` 判据不变）。

### 5.1 逐条降级表

| 失败点 | 判定 | 降级动作（逐字节回现状） | 是否记日志 |
|---|---|---|---|
| application `cacheDir` 取不到 | context 为 null / 反射失败 | **整轮 P3 不启用**：`bigImgPath=null`，不触发下载、不建文件、不装 hook | `LogX.w` 一行 + `[P3] disabled no cacheDir` |
| `msgSvrId <= 0`（`ForwardLogic.kt:388`） | 取不到服务端 id | 方案 A 副选 C 的 `SERVERID://` 解析不可用 ⇒ 只用 `resolveImagePath` 二次解析；仍拿不到 ⇒ `bigImgPath=null` 发缩略图。**下载本身用 localId+msgId，不强制依赖 msgSvrId** | `LogX.w [P3] no msgSvrId -> baseline` |
| `imgLocalId <= 0`（`ForceForward.kt:1255` 回 -1） | 取不到源图 localId ⇒ DownloadImgService 第 1 参无效 | 不调 `svc.c`，直接走现状本地 ori（`FileSender.send` 第 4 参仍 -1，m.a 写 -1，一环不动） | `LogX.w [P3] no localId -> baseline` |
| `d3.F8()` / `svc` 反射失败 | class/method 漂移 | 不下载、不建文件，`bigImgPath=null` | `LogX.w [P3] service reflect failed: <异常类>` |
| `svc.c(...)` 返回非 true | 拒绝受理（AutoHdImg.kt:483-489 已见此分支） | 视为本次下载失败 ⇒ 不建文件、发缩略图；**不占位、不重试阻塞** | `LogX.w [P3] svc.c rejected` |
| listener 回调超时 / 报 err / `onGYNetEnd` 非 0 | 下载未完成 | 不拿路径、发缩略图；按 §4.2 清掉可能已建的半成品 | `LogX.w [P3] download incomplete` |
| 下载后的路径解析仍指 `th_` / 空 / 不存在 | `z3`/`resolveImagePath` 没命中大图 | **方案 A 到此为止**：`bigImgPath=null` 发缩略图；只有真机确认此分支稳定复现才启用方案 B（`j2.R()` after 覆盖） | `LogX.w [P3] parsed path not big -> baseline` |
| 临时文件复制 IO 异常 / 长度 <15KB | §3.2 门禁失败 | 删临时文件、`bigImgPath=null`、发缩略图 | `LogX.w [P3] temp copy failed/short` |
| 方案 B 的 ThreadLocal 交接拿不到 / 线程不符 | §8 待取证项失败 | `j2.R()` after 回调第一行就 return（零反射），r0 保持宿主原值 ⇒ 回现状 | `LogX.w [P3] R() override skipped` |
| `q1.j` / `r0.e/f` 的 `setObjectField` 抛异常 | 字段签名漂移 | 单个 `runCatching` 吞掉、写 WARN，**不往外抛**（否则会把已闭环的 `send()` 从"成功入队"打成 false） | `LogX.w [P3] field write skipped: <异常类>` |

### 5.2 不写坏路径（三条硬 gate）

只有**同时**满足以下条件才允许把路径交给 `q1.j`/`r0.e/f`：

1. `File(path).exists()` —— 直接对应 `FileSender.kt:154` 的既有门禁（文件不存在本来就 return false）；
2. `length() > 15 * 1024` —— 坏图本地缩略图 ~15KB、占位 36B（§2 背景），阈值把二者挡在外面；
3. `!File(path).name.startsWith("th_")` —— 与 `ForceForward.kt:1349` 同款判据。

任一不满足 ⇒ 该路径**根本不进入**覆盖逻辑。**绝不"先写进去再说"**：写坏路径的后果是 `t81/g.a` 拿 `q1.j` 取大小失真 / `doScene` 的 `o6.N` 读空 ⇒ 红叹号，比现状更差，违反 §1.3。

### 5.3 不 ANR（下载是异步，禁止在发送事务里等）

- 【源码确定】`AutoHdImg.kt:474-479` 的 `svc.c(...)` 返回 boolean（受理），真正完成在 listener 回调里；`AutoHdImg` 自己从未阻塞等待。
- `resendFile`（`ForceForward.kt:2205`）运行在爆发转发链路上（UI 线程）。**严禁在它内部睡眠 / 自旋 / `CountDownLatch.await()` 等下载完成** —— 那会 ANR。
- 采用"预热 + 检查"两段式：
  1. **预热**：长按冻结 ref 之后先触发一次 `prepareBigImg(snap)`（§3.5），下载在后台跑；
  2. **检查**：`resendFile` 内只读一个由 listener 回调置位的 `volatile` 标志 + 临时文件三门禁（§5.2）。**已完成 ⇒ 用 P3；未完成 ⇒ 立即按现状发缩略图**。两态都不阻塞。
- 真机正向验证时，先用探针提前触发预热、等 `HDIMG fired` / 临时文件出现日志后再长按转发，把"等待"移出发送事务；这属于测试步骤，不是生产代码里的等待。

### 5.4 不红叹号

- 红线场景：路径指向半成品文件 / 被提前删除的文件 / 缩略图。§5.2 三 gate + §4.2 清理时机 + §3.2 的 `.part → rename` 原子写，三者合起来覆盖。
- 【源码确定】上传 task 的 `field_fullpath = z3(r0.e)`（`j2.O 0x2acc44`），读字节 `o6.l(path)→o6.N(path,off,len)`（`doScene 0x2ad99c`）；路径坏 ⇒ 这两步拿不到正确字节 ⇒ CGI 失败 ⇒ 红叹号。所以 gate 必须放在写字段**之前**，不能寄望于事后补救。
- 方案 B 的 R() after 回调内**只写指针、不做 IO**（MIO 探针的只读纪律同款：`MicroXImageObserveProbe.kt` 明确禁止回调里调 `b1.R5/j6/o2/z3/Y2` 等替宿主做事）—— 避免回调里的下载 / DB / 发送副作用破坏只读前提并引入竞态。

### 5.5 `runCatching` 边界（分层，互不越界）

```
prepareBigImg(snap)              // 第 1 层：下载/复制，独立 runCatching，失败只置标志
   └─ listener 回调置位 volatile // 不抛回主线程
resendFile(snap, target)         // 第 2 层：读标志 + 三 gate，决定 bigImgPath 是否为 null
   └─ FileSender.send(...,bigImgPath)  // 第 3 层：外层已有 runCatching(158/237)
        └─ q1.j 覆盖块            // 第 4 层：单独 runCatching，失败 WARN 不外抛
             └─ (方案B) R() after  // 第 5 层：回调内单独 runCatching，只写指针
```

- 每一层的失败都**就地吞掉并降级**，绝不让内层异常穿过 `FileSender.send` 的外层 `runCatching`（`FileSender.kt:158/237`）把"本来能入队"变成 `false`。
- 与已闭环修复的关系：`m.a`（`:189`）、`q1.f(m)`（`:208`）、`q1.h=13`（`:211`）、`q1.a()→g6→l1.a()`（`:214-230`）**全部不碰**；P3 只新增第 6 参与一个覆盖块，失败时该覆盖块等于不存在。

[DONE §5]

---

## 6. 真机判据（可直接照抄的验收口径）

> 探针已存在且**不改**：`MicroXImageObserveProbe.kt`（MIO，13 hook，含 F2 `b1.z3` / D5 `j2.O`）、`ImageNetSceneProbe.kt`（INP，含 H1 `j2.onGYNetEnd`）。
> 日志行原文（【源码确定】，来自探针源码）：
> - F2 before：`MIO z3 in s0=... prefix=... suffix=... isThumb=<bool>`
> - F2 after ：`MIO z3 out path=<绝对路径>`
> - D5 before：`MIO j2O in r0.u=<值>`；D5 after：`MIO j2O out ret=<bool>`
> - INP H1 ：`INP onGYNetEnd a0=<int> a1=<int> a2=<int> errMsg='...'`
> - 开关：`op=microimg sub=on|off` **只控制探针日志量**（默认 false 冷启静默），**不是 P3 行为开关**；行为开关见 §9。

### 6.1 正向判据（四条全中才算 P3 成立）

| # | 判据 | 可自证证据 | 分档 |
|---|---|---|---|
| 1 | 出站 `z3(r0.e)` 解析到**大图**路径 | F2 after `MIO z3 out path=.../<md5>.jpg`，文件名**非 `th_` 前缀**、是真绝对路径（方案 B 形态为 `.../cache/_<uuid>.jpg`，同样非 `th_`）；F2 before 的 `isThumb=false` 佐证 | 【真机确定】日志行 + 【源码确定】`z3 0x2a6ae4 → Y2(msgSvrId) 0x2a363c` 的解析语义 |
| 2 | 对端保存字节 **≫15KB** | 对端相册里这张图的文件大小 / `op=label sub=sql` 查 `ImgInfo2` 或消息表字节数；远大于坏图的 ~15KB 缩略图与 36B 占位 | 【真机确定】 |
| 3 | 肉眼清晰 | 对端打开图片看清晰度，与源图对比（分辨率 ≈ 源图，非缩略图放大） | 【真机确定】 |
| 4 | 多目标都收到 | 每个目标都满足 1~3；**各自 F2 `z3 out` 路径不同**（§4.3 隔离的可观测证据），不存在"前一个删了后一个读空" | 【真机确定】 |

- 补充锚点：D5 `MIO j2O in r0.u=EMPTY` 与微X真机一致即可；**但 D5 单独不算终局**（它只读 `r0.u`，不证明字节来源），必须与 F2 + 对端字节合起来用。
- 判据成立 ⇒ 对应 §1.1 终局：`z3(r0.e)` 指向大图字节文件被 `o6.l/o6.N` 读取。

### 6.2 反向判据（自变量唯一 = 只切 P3 行为开关）

- 同一条坏图消息、**同一批目标**，只把 §9 的 P3 行为开关从开 → 关，其余一律不动：`imgLocalId`、`forwardCtx`、`srcBigImgPath`、`localPath`、探针开关状态全部保持。
- 预期：F2 after `MIO z3 out path=.../th_<md5>.jpg`（或解析不到大图）、对端字节退回 ~15KB、肉眼模糊。
- **反差必须发生**：开=清晰 / 关=模糊。若关了仍清晰，说明"本来就不是坏图"（选错样本）或 P3 没真正生效（开了那一轮是假阳性），回头查 §6.4 与 §8。
- 注意：`op=microimg sub=off` 只关日志，**不改变行为**，不能拿它做反向对照；反向必须切 §9 的 P3 行为开关。

### 6.3 不倒退判据（普通本地大图仍清晰）

- 选一张**源消息本地就有大图**（非 36B 占位、`imgLocalId>0`、`srcBigImgPath` 非空）的消息，分别用 P3 开 / 关各发一轮到同一目标。
- 预期：两轮都对端清晰、字节 ≫15KB、F2 `z3 out` 都指非 `th_` 大图。**开 P3 不得让本来清晰的那条变差**（§5.2 三 gate + §5 降级表保证：路径本就合格时覆盖是等价替换，失败则回退原位）。
- 对照 §1.3 的已闭环修复：两轮的 `m.a`/`m.b`/`q1.r`/`q1.j`（原生值）/`q1.h=13` 日志一致；P3 只在"有新大图路径时"追加覆盖。

### 6.4 假阳性清单（单独出现一律不算 P3 成功）

| 假阳性信号 | 为什么不算 | 必须叠加的真判据 |
|---|---|---|
| `FileSender queued ... ok=true`（`FileSender.kt:235`） | 只证明 `l1.a()` **入队成功**，与字节是不是大图无关（类注释 `:76-77` 明说 ≠ 对端收到） | §6.1 的 1+2+3 |
| `INP onGYNetEnd a0=0 a1=0 errMsg=''` | 只证明**我方上传 CGI 成功**，缩略图也会返回 0/0 | §6.1 的 2+3（对端真字节/肉眼） |
| `MIO j2O out ret=true` | 只是 `j2.O()` 的返回值（CDN task 构造是否走），R75 §2 显示 `n(1)` 短路会绕过 `r0.u` 判空，与路径指向哪张图无关 | §6.1 的 1 |
| D5 `MIO j2O in r0.u=EMPTY` | 证明的是微信的分支走向，不证明我方字节来源 | §6.1 的 1+2 |
| F2 after 路径非 `th_` 但对端仍模糊 | 路径对了但字节可能被提前删除 / 读空（§4.2 竞态） | §6.1 的 2+3+4 |
| 对端收到一张清晰图但 F2 日志缺失 | 可能是宿主从 CDN 自行补的图，不是我方落盘链 | §6.1 的 1（F2 必须命中） |

- 严禁把 `ok=true` / `onGYNetEnd 0/0` 任一单独写进"通过"结论 —— 这正是 R74~R75 反复踩过的"入队当成发送成功"陷阱（见 `FileSender.kt:76-80` 边界声明）。

### 6.5 建议执行顺序（真机一次跑完，省轮次）

1. `op=logclear` → 确认模块 `installed`（CLAUDE.md §三上机三件事）；
2. `op=microimg sub=on` 开探针日志；P3 行为开关置 **开**；
3. 预热下载（§5.3），等 `HDIMG fired` / 临时文件出现；
4. 长按坏图 → 多目标爆发转发；
5. `dev.py q` 过滤 `MIO z3 out|MIO j2O|INP onGYNetEnd|FORWARD resend-file|\[P3\]`；
6. 切 P3 开关为 **关**，重复 3~5（反向对照）；
7. 用普通本地大图重复一轮（不倒退对照）；
8. 核对 §6.1~6.4 全部条目后下结论。

[DONE §6]

---

## 7. 改动面与分包（按 CLAUDE.md §四①）

### 7.1 要改 / 要新建的文件清单

| # | 文件 | 性质 | 改动内容 | 计数口径（§四①） |
|---|---|---|---|---|
| 1 | `wxgroup/app/src/main/java/com/lx/wxgroup/core/FileSender.kt` | 既有功能文件·**接线** | 加第 6 参 `bigImgPath: String?=null`；`:207` 后加 `q1.j` 覆盖块；`:214` 前后加 ThreadLocal set/remove（方案 B 时） | 接线，**不单独计数** |
| 2 | `wxgroup/app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt` | 既有功能文件·**接线** | 加 `prepareBigImg(snap)`（下载+复制+置标志）；`:2232` 追加第 6 参；§4.2 清理挂点 | 接线，**不单独计数** |
| 3 | `wxgroup/app/src/main/java/com/lx/wxgroup/feature/P3BigImgOverride.kt` | **新建 Hook 功能文件**（方案 B） | `install()` 挂 `j2.R()` after，ThreadLocal 交接写 `r0.e/f`；`status()` 回显 | 新建功能文件，**单独计数 = 1** |
| 4 | `wxgroup/app/src/main/java/com/lx/wxgroup/data/Keys.kt`（或现有 Keys 位置） | 共享常量·**接线** | 加 P3 行为开关 key（§9） | 常量，**不计数** |
| 5 | `wxgroup/app/src/main/java/com/lx/wxgroup/EntryReceiver.kt` | 共享广播入口·**接线** | 注册 `sub=p3on/p3off/p3`、方案 B 的 `install()` 调用 | 注册，**不计数** |

> 探针 `ImageNetSceneProbe.kt` / `MicroXImageObserveProbe.kt` **不改**（F2/D5/H1 判据已具备，§6 直接复用）—— 这点很重要，避免把"加日志"误算成改动面。
> 源码/测试/配置除上表外一律不动；不碰 adb/gradle/git（本 lane 纪律）。

### 7.2 实现 lane 数量判定

按 §四①「改动面怎么数」的原文口径：**新建功能文件单独计数；接线类改动（加分支/注册/常量）不计数**。

- **阶段一（方案 A）**：只改上表 1/2/4/5，全是接线 ⇒ 独立功能文件计数 = **0** ⇒ §四① 表第三行「只落在共享文件/单一功能文件 ⇒ 0 个 lane」，须在台账备注写明"全为接线改动"。
- **阶段二（方案 B，仅当方案 A 在真机上 F2 `z3 out` 仍指 `th_` 时启用）**：新建上表 3（Hook 模块，单独计数 = 1）⇒ §四① 表第三行 ⇒ 0 lane；但按 §四② 冲突取严条款「改动面只落在单一 Hook/反射类文件（Pro 类）⇒ ① 判 0、本条判 1，取严 ⇒ 派 1 个 Pro 实现 lane」。
- **若两阶段合并为一个实施轮次**（推荐，避免二次上机）：改动面 = FileSender.kt + ForceForward.kt + P3BigImgOverride.kt = **3 个不重叠功能文件** ⇒ §四① 表第一行 ⇒ **≥2 个实现 lane**。

**结论（取严，按合并实施）**：派 **2 个实现 lane**，文件互不重叠：

| lane | 档位 | 独占文件 | 任务 |
|---|---|---|---|
| P3-A | **Flash** | `FileSender.kt`、`ForceForward.kt`、`Keys.kt`、`EntryReceiver.kt` | 第 6 参 + `q1.j` 覆盖块 + DownloadImgService 预热/复制/清理 + 开关注册 |
| P3-B | **Flash** 实现 + **Pro** 复核 | `P3BigImgOverride.kt`（仅此文件） | `j2.R()` after Hook + ThreadLocal 交接 + status 回显；写完由独立 Pro 子代理复核 Hook/反射/异步时序 |

- 复核 lane 与实现 lane **分开计数**：上表 2 个是"实现 lane"；另需 **1 个 Pro 复核 lane**（复核 P3-B 的 `R()` after 时序、§5 降级链是否逐字节回退、§4 清理竞态），不计入实现 lane 数量。
- 上机前按 §四⑥ 把 `git diff` 正文交第二个视角复核，顺带过 ①⑦。

### 7.3 Pro 类活标注（谁做、何时做）

| 活 | 档位 | 状态 |
|---|---|---|
| 判据设计（终局锚点 `z3(r0.e)`、F2/D5/INP 判据、正/反/不倒退口径） | **Pro** | ✅ **已在前 Pro lane 完成**：R75 双线独立复核（真机复现 + dex 反汇编）+ 本文档 Pro 设计 lane。本 Flash lane **不重做、不重判**。 |
| 根因推理 / Hook 链路推导（`j2.R() after` 落点、`r0.l/n` 写 j/f 的纠正） | **Pro** | ✅ 已完成（R75 §5 锚点 E + §3.3）。 |
| 复核 P3-B 的 Hook / 反射 / 异步时序 / 并发清理 | **Pro** | ⏳ 待派（见 7.2 表末行）。 |
| **落码**（按上述现成判据写代码：`q1.j` 覆盖块、下载预热、Hook、开关） | **Flash** | ⏳ 本轮实现 lane（P3-A / P3-B 均为 Flash 落码）。 |
| 文档 / 台账 / 日志过滤 | Lite | ✅ 本文档即 Lite 产物。 |

> 硬规则提醒（CLAUDE.md §四② / 触发点）：**Pro 类活不许主会话自己做**。本 lane 是 Lite 只读文档；任何源码落码必须由显式 Flash 子代理执行，P3-B 的复核必须由显式 Pro 子代理执行。

[DONE §7]

---

## 8. UNKNOWN 与待取证（逐条标是否阻断）

> 判定口径：**"阻断"= 不解决它就不能写出可上机的代码**；"不阻断"= 已有可降级/可绕过的实现，真机再收敛。
> 证据分档：每条都标【源码确定】/【真机确定】/【推断】；推断项一律给置信度与核对法。

### 8.1 `ok0/m1.n(1)` 的真机值

- 【源码确定，R75 §2】`j2.O`（`0x2acc44`）里 `ok0/s2.e9()` → `m1.n(1)`：`n(1)!=0` 时 `if-nez` 短路到 0x0056 直接构造 CDN task，跳过 `r0.u` 判空段；`n(1)==0` 时才走 `a8.F0(r0.u)` 判空，空则 `0x0055 return false`（early-return）。
- 【真机确定】微X真机 `r0.u=EMPTY`（MIO D5 `MIO j2O in r0.u=` 观测），说明微X那条链要么 `n(1)!=0` 短路，要么走了 early-return —— **D5 的 `ret` 值才能区分**，而当前证据未记录该真机 `ret` 与 `n(1)` 实测值。
- **是否阻断：不阻断**。
  - 理由：无论 `n(1)` 取何值，`doScene`（`0x2ad99c`）的 `z3(r0.e)→o6.l→o6.N` 读字节链都执行；我方把 `e/f` 指对即可，不依赖 `O()` 的返回值或 `r0.u`。
  - 核对法（真机一次收敛）：`dev.py q 'MIO j2O|MIO m1'` 看 D5 `out ret`；若需要精确值，用只读 hook 打一次 `ok0/s2.e9()` 后 `m1.n(1)`（**不要改探针，另开一次性只读探针或临时广播**）。

### 8.2 R5 多目标 PString 输出隔离（MIO U8）

- 【源码确定，R75 §4】`b1.R5`（`0x2a710c`）签名 `R5(String,String,I,I,I,PString,PInt,PInt)J`，后 3 个是输出指针；MIO D1 的 `MIO R5 outPtr path=... w=... h=...` 就是读它们。
- 【推断，中置信】并发到 N 个目标时，如果 R5 内部用静态/单例 PString 缓冲，后一次调用会覆盖前一次 ⇒ 多目标路径串味。当前**没有任何真机日志证明它隔离**。
- **是否阻断：不阻断（主方案不用 R5）**。
  - §2.1 主选 A 是 DownloadImgService.c，R5 只是 §2 表格里的入口 B（参数 U1 未知、盲调风险高），P3 实现**不调用 R5**。
  - 若未来把入口切到 B，则本项升级为**阻断**，必须先真机确认 PString 逐调用隔离（或改走每目标自建 PString 实例 + 立即取值）。

### 8.3 微X到底写 `r0.l/n` 还是自建 fullpath（还是根本不写 r0）

- 【真机确定】微X特征：`m.a=-1`、`r0.u=EMPTY`、下载链全零、`cache/_<uuid>` 临时大图发后删、对端清晰。
- 【推断，低置信】三种可能：(a) 在 j2 构造/R() 之后直接 `setObjectField(r0,"e",tempPath)`；(b) 调 `r0.l`/`r0.n` 写 j/f 再让 `z3` 解析（但 R75 §5 已证 `r0.l` 写 j 不写 e，单靠它不足以定 field_fullpath）；(c) 自建下载任务，把 fullpath 塞进 `tj/k` 而完全不碰 r0.e。由于 dex 加密，无法读其源码。
- **是否阻断：不阻断（我方 A/B 已覆盖 a/b）**。
  - 方案 B 采用 **e+f 双写**（§3.3），同时满足 (a) 与 (b)；(c) 形态下我方不碰 `tj/k`（§1.2.3 红线），但 doScene 仍读 r0.e/f，故仍可生效。
  - 真机收敛法：D5 hook 里加读 `r0.e/f/j` 一次性日志（**这需要改探针，本 lane 不做**；记为后续 Pro 取证项）；现阶段以 §6.1 的对端字节与肉眼结果为终局判据，不依赖微X内部写法。

### 8.4 方案 B 的 `j2.R()` 与 `q1.a()` 是否同线程（ThreadLocal 交接前提）

- 【推断，中置信】`q1.a()`（`FileSender.kt:214`）构造 `l1` 与内部 NetScene；若 `j2` 的构造/`R()` 在同一调用栈同步发生，ThreadLocal 交接成立。当前只有【源码确定】的 `R()` 语义（`0x2ad864`）与 `l1.a()` 异步入队（`:229-230`），**没有同线程证据**。
- **是否阻断：不阻断方案 A，阻断方案 B 的 ThreadLocal 实现形态**。
  - 降级：若不同线程，改用「以 `imgLocalId` 为 key 的短 TTL `ConcurrentHashMap` + 首次命中即 remove」交接，按 §4 仍是一目标一份文件；只是在多目标同 imgLocalId 时要靠目标 id 复合 key。实施前由 P3-B lane 按 §8.4 取证后在两种交接法里二选一。

### 8.5 `vfs/o6.l` / `o6.N` 是否接受普通 `cacheDir` 绝对路径

- 【源码确定】`o6.l(String)J` / `o6.N(String,II)[B` 属 `com/tencent/mm/vfs/o6`（`doScene 0x2ad99c`）；R75 只确认签名，未确认路径格式要求（是否必须 VFS scheme / 是否接受 `/data/user/0/.../cache/_<uuid>.jpg`）。
- 【真机确定】微X用 `cache/_<uuid>` 且宿主能读 ⇒ 普通 cache 路径**大概率**可用。
- **是否阻断：不阻断**。§6.1 的 F2 `z3 out` 路径一旦命中且对端字节 ≫15KB，即实证路径格式 OK；若真机 F2 正常但对端读空，再查这一项（把临时文件改放到宿主图库同目录并复验）。【推断，高置信可用】

### 8.6 listener 回调线程 / 可重入性 / `TYPE_HD` 常量值

- 【源码确定】`AutoHdImg.kt:475-479` 的 `svc.c(localId, msgId, TYPE_HD, Long.valueOf(msgId), 0, listener)` 已真机跑通；但 `TYPE_HD` 的字面值、`newListener(cl)` 回调在哪个线程、同一 localId 重入是否安全，本方案只读了调用点，**未读其定义**。
- **是否阻断：不阻断**。直接照抄 `AutoHdImg.kt` 的 `TYPE_HD` 与 `newListener` 范式（不重新定义常量、不自建 listener），继承其已真机验证的行为；§5.3 的"预热+检查"不依赖回调线程。若真机出现下载标志永不变位，再补读 listener 定义（后续 Pro 取证）。

### 8.7 UNKNOWN 汇总表

| 项 | 阻断实施？ | 绕过 / 收敛时机 |
|---|---|---|
| 8.1 `m1.n(1)` 真机值 | 否 | 不影响 doScene 读字节；真机顺带看 D5 `out ret` |
| 8.2 R5 多目标 PString 隔离 | 否（主方案不用 R5） | 若切入口 B 则升级为阻断，先取证 |
| 8.3 微X写 r0.l/n 还是自建 fullpath | 否 | e+f 双写覆盖；后续 Pro 加只读 hook 收敛 |
| 8.4 R() 与 q1.a() 同线程 | 否（A 不受影响）；是（B 的 ThreadLocal 形态） | P3-B lane 取证后在 ThreadLocal / TTL Map 间二选一 |
| 8.5 VFS 接受 cache 绝对路径 | 否 | F2 + 对端字节实证；异常再换目录 |
| 8.6 listener 线程 / TYPE_HD 值 | 否 | 照抄 AutoHdImg 范式，异常再补读 |
| **有没有任何一项阻断方案 A 开工？** | **没有** | 方案 A 的全部前提均已【源码确定】或【真机确定】 |

[DONE §8]

---

## 9. 回滚路径（P3 失败时最小代价撤回）

### 9.1 开关设计

- **开关 key（建议）**：`Keys.FORWARD_P3_BYTES_LANDING = "forward_p3_bytes_landing"`，Kv boolean，**默认值 = `false`（关）**。
  - 默认关的理由：P3 是"加字节"的增量能力，默认关保证装上模块后行为与第 76 行前的现状**逐字节一致**；真机验证通过再打开，符合 §1.3 单调追加。
- **广播探针（建议）**：`op=forward sub=p3on|p3off|p3`（沿用 `ForceForward.probe` 已有的 `on|off|...` sub 范式，`ForceForward.kt:2259`）：
  - `p3on` / `p3off`：只写 Kv、不写库、不进设置，供 §6.2 反向对照即时翻转；
  - `p3`：回显当前值 + 临时文件计数 + 方案 A/B 状态（诊断用）。
- **不要复用已有开关**：`op=forward sub=on|off` 是转发总开关、`op=microimg sub=on|off` 是探针日志开关（§6 开头），二者都不是 P3 行为开关；混用会导致反向对照自变量不唯一。

### 9.2 三级回滚（代价从低到高）

| 级别 | 动作 | 代价 | 适用 |
|---|---|---|---|
| **L0 运行时** | `op=forward sub=p3off` 或删掉 Kv key（回默认 false）⇒ 下次发送 `bigImgPath=null`、不预热下载、不建临时文件、方案 B 的 R() after 回调首行 return（零反射） | 零代码改动、无需重装 | 真机 §6 正向失败 / 出现红叹号 / 倒退 |
| **L1 代码级** | 撤回 §7.1 的接线：`FileSender.kt` 删第 6 参与 `:207` 后覆盖块（及方案 B 的 ThreadLocal 两行）；`ForceForward.kt` 删 `prepareBigImg` 与 `:2232` 的第 6 参；`Keys.kt` 删 key；`EntryReceiver.kt` 删 sub 注册与方案 B 的 `install()` 调用 | 4 个接线文件 + 删 1 个新建文件 | 确认 P3 方向不成立，不再保留代码 |
| **L2 完全清理** | 在 L1 基础上删除 `P3BigImgOverride.kt`，确认无残留 hook/探针注册 | 5 文件 | 彻底退出 P3 方案 |

### 9.3 回滚的"不倒退"保证

- **已闭环修复一字不动**：`imgLocalId`→`m.a`（`FileSender.kt:189`）、`srcMsgId`→`m.b`（`:196`）、`srcTalkerPrefix`→`q1.r`（`:200`）、`srcBigImgPath`→`q1.j`（`:205`）、`q1.h=13`（`:211`）、`q1.a()→g6→l1.a()`（`:214-230`）全部不在回滚清单里 —— 撤回 P3 后这些与现状逐字节相同（§1.2.2 / §1.3）。
- **数据面无需清理**：P3 **从不调 `b1.u8`**（§3.4）⇒ 未写 ImgInfo2 表、未 `doNotify()`，回滚不涉及 DB/消息表；临时文件只在 `cacheDir`，L0 后不再新建，存量按 §4.2 的异常清理路径删除或由系统回收。
- **回滚验证**：L0 后跑 §6.2（反向）与 §6.3（普通大图不倒退）两轮；两轮都回到现状 ⇒ 回滚确认。

### 9.4 失败判定（何时触发回滚，不恋战）

按 CLAUDE.md §一·五 D3「判据连塌即停」：

1. §6.1 四条**任一连续两轮**不满足（尤其 F2 `z3 out` 始终指 `th_` / 对端字节不 ≫15KB）⇒ 停，先回 §8 查 UNKNOWN，不继续加补丁；
2. §6.3 不倒退失败（普通本地大图变模糊 / 出现红叹号）⇒ **立刻 L0**，这是红线；
3. §5.4 的红叹号复现 ⇒ 立刻 L0，查 §5.2 三 gate 是否被绕过；
4. 两阶段（A/B）都试过仍不成立 ⇒ 结论"当前 dex/真机无法复现微X字节来源"，L1/L2 退出 P3，把证据落 `docs/conclusions/` 与 `.workbuddy/memory/`，不把未验证代码留在 `main`。

### 9.5 收尾

- 开关默认 false + 回滚路径齐全 ⇒ 本方案可安全合入（合入 ≠ 默认生效）。
- 真机通过（§6.1~6.3 全过、反向对照有效）后，若要默认打开，单独走一个改默认值的 commit，并回填 `requestModelId` 与 SOP 审计台账（CLAUDE.md §四③/④、§六）。

[DONE]
