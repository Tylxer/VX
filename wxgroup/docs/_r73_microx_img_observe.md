# R73 真机观察「微X图片转发为何清晰」最小 hook 集 + 判据 + 操作步骤

> lane：`r73-microx-img-observe`（**只读方案设计**）｜档位：Pro（Hook 链路级判据设计；model 按用户临时规则 `hy4-flash-free`，不入文件）。
> 纪律：**全程只读**；本 lane 唯一写操作 = 本文档。不改任何源码/测试/探针，不碰 adb/gradle/install/commit。真机步骤只写进本文交主会话/用户执行。
> 目的：坐实微X图片转发拿到清晰大图的机制，验证我方 P3 方案（触发原生 CDN check/下载副作用）方向对不对。
> 证据优先级：`_cache/_r11_dex7_dis.txt` dexdump 字节（`code_off` + 原文）> 源码行 > 真机日志 > 结论文档转述。所有 hook 签名的 descriptor 均已从该 dexdump 实抄（见 §2 各表「dex 自证」列）。

## 目录

- [0 一句话结论](#0-一句话结论)
- [1 背景：唯一根因与本次要补的缺口](#1-背景唯一根因与本次要补的缺口)
- [2 最小 hook 集总表（4 问 → hook）](#2-最小-hook-集总表4-问--hook)
- [3 Q1 微X是否触发原生 CDN 下载](#3-q1-微x是否触发原生-cdn-下载)
- [4 Q2 微X走哪条入队链](#4-q2-微x走哪条入队链)
- [5 Q3 微X从哪拿真实图片路径](#5-q3-微x从哪拿真实图片路径)
- [6 判据表（互斥 → 结论 → 对 P3 影响）](#6-判据表互斥--结论--对-p3-影响)
- [7 用户操作步骤（可执行）](#7-用户操作步骤可执行)
- [8 最小 hook 集约束 / 开销 / 风险 / UNKNOWN](#8-最小-hook-集约束--开销--风险--unknown)
- [9 DONE](#9-done)

## 进度

- [x] 骨架 + 目录
- [x] §0 一句话结论
- [x] §1 背景与缺口
- [x] §2 最小 hook 集总表
- [x] §3 Q1 CDN 下载链 hook 设计
- [x] §4 Q2 入队链 hook 设计
- [x] §5 Q3 路径来源 hook 设计
- [x] §6 互斥判据表
- [x] §7 用户操作步骤
- [x] §8 约束/开销/风险/UNKNOWN + [DONE]

---

## 0 一句话结论

**最小必选集 = 13 个只读 hook 落点（Q1 下载链 5 + Q2 入队链 5 + Q3 路径来源 3），另 2 项可选（`j2` 构造复用现有 `op=inp` 探针 / `m.<init>` 零日志不据此反推）。** 判清晰的**充分必要证据**是三件事同时成立：① 对端收到清晰大图（用户肉眼 + 保存字节 ≫15KB）；② `j2.O` before 读 `r0.u` **非空**（cdninfo 已写入）；③ `R5`/`j6` 下载链**至少命中一个**或 `C2` 的 `field_exist_whencheck=true`（原生 check 命中）。若 `r0.u` 为空却仍清晰 ⇒ 微X靠**别的机制**（本地已存在的大图或 ImgInfo2 的 SERVERID 引用直连，无下载副作用），**P3 被推翻或需补验**。本方案不改一行代码，只定义"挂哪些只读 hook、打什么日志、怎么看日志"。

## 1 背景：唯一根因与本次要补的缺口

### 1.1 已查清的唯一根因（`_r73_ma_residual.md` §八，dex 字节自证）

清晰图唯一判别 = **转发前有没有经过原生 CDN check/下载副作用**，与 `m.a`/`q1.b`/`q1.r` 字段值无关（字段补齐已真机证伪）：

| 链 | 路径 | `r0.u`（cdninfo） | 本地大图 | `j2.O` 分支 | 对端 |
|---|---|---|---|---|---|
| 原生（清晰） | `MsgRetransmitUI.B6`(`62c98c`) → `62cd18 field_onlycheckexist=true` → `62cd34 mq/k.B8` → 命中 `qf4/j0.C2`(`84307c`) 内 `843362 b1.j6 下载大图` + `8433f4 r0.i 写 cdninfo`；未命中 `j6`(`62d0f8`) 内 `62d176 b1.R5` 下载兜底 | **有值** | **有** | `2accc0` 读 `r0.u` → 非空 → `2acd38~2acd84 z3(r0.e/f/g/j)` → CDN 上传 | 清晰大图 |
| 我方（模糊） | FileSender 自组 q1 直接进 j2，**跳过整段** | **空** | 仅 36B 占位 + 15KB `th_` 缩略图 | `2accc0` 读 `r0.u` → `2acccc if-eqz` 空 → `2accfe "cdntra not use cdn flag" early return` → 本地直传缩略图 | 模糊 |

- **唯一 P3 修法入口** = `b1.R5`(`62d176`) / `b1.z3` 的 `SERVERID://→Y2`(`2a6b30`)。
- 多目标并发下 `R5` 的 `PString`/`PInt` 输出指针隔离 = **UNKNOWN**（沿用 §八，本次不验并发，先单目标）。

### 1.2 微X已知（静态解不出，只有文本真机样本）

- **静态**：8 个 `92c50100` 密文容器 + `libwxcoreloader.so::CallMethod` + rolling-XOR 字符串混淆（R72 定案，`docs/_r72_microx_forward_parse.md` §4），业务 dex 不可静态解。
- **真机（仅文本，R72b 22:42）**：`hl0/q0.<init>` type=522、`forwardMsgId=0`、线程 `wx_forward_send_thread_*`；`q1.a|b`/`q1.f`/`m.<init>` **零日志** ⇒ 微X入队路径与我方 G1 builder 不同；对端**无**转发标记。
- **图片从未真机观察过** —— 这就是本 lane 要补的核心缺口。
- **闪退前提已纠正**：微X转发按钮自身不闪退；此前闪退 = 点输入法收起触发（微信冷启 → 探针内存变量清零 → 该样本无效）。用户操作微X转发时**不要点输入法收起**。

### 1.3 为什么必须真机观察（本方案的存在理由）

我方 P3 方向"触发 CDN 下载"是否正确，取决于微X清晰度到底是不是靠这段下载副作用拿到的。静态 dex 解不出微X实现 ⇒ **唯一权威答案 = 只读真机观察微X图片转发那一刻对宿主做了什么**。本方案不预设结论：若观察到微X也走 `R5/j6` + `r0.u` 有值 ⇒ P3 被证实；若 `r0.u` 空而清晰 ⇒ 微X靠别的机制，P3 需重评。

## 2 最小 hook 集总表（4 问 → hook）

所有 descriptor 均从 `_cache/_r11_dex7_dis.txt` 实抄；`code_off` 为方法体指令区首地址，可复核。行内「调用点」指宿主代码里 invoke 该方法的字节位置（用于证明该 hook 确实在目标链上）。

### 2.1 必选 13 项

| # | hook 点（全限定名 + descriptor，dex 原文） | method `code_off` / 调用点 | 回答 | before / after 打印（简，详见 §3~§5） | 级别 |
|---|---|---|---|---|---|
| D1 | `com.tencent.mm.modelimage.b1.R5(Ljava/lang/String;Ljava/lang/String;IIILcom/tencent/mm/pointers/PString;Lcom/tencent/mm/pointers/PInt;Lcom/tencent/mm/pointers/PInt;)J` | `2a710c` / 原生调用 `62d176` | Q1 | before 两 String + 3 int；after `J`=imgLocalId | 必选 |
| D2 | `com.tencent.mm.modelimage.b1.j6(Ljava/lang/String;[BIJZLjava/lang/String;Lcom/tencent/mm/pointers/PString;Lcom/tencent/mm/pointers/PInt;Lcom/tencent/mm/pointers/PInt;)J` | `2a74f8` / C2 内调用 `843362` | Q1 | before arg0 String、arg1 `byte[]` **长度**、arg2 I、arg3 J、arg4 Z、arg5 String；after `J`=imgLocalId | 必选 |
| D3 | `mq.k.B8(Ltj/k;)Z` | `78f618` / 原生调用 `62cd34` | Q1 | before 读 `tj/k.field_onlycheckexist` / `.field_trysafecdn`；after `Z` | 必选 |
| D4 | `qf4.j0.C2(Ljava/lang/String;ILtj/e;Ltj/f;Z)I` | `84307c`（**另有同签名 63-unit 实现，UNKNOWN**） / 回调入口 | Q1 | before 读 `args[3]=tj/f.field_exist_whencheck`（`843098`）；after `I` | 必选 |
| D5 | `com.tencent.mm.modelimage.j2.O(Lcom/tencent/mm/modelimage/r0;I)Z` | `2acc44`（读 `r0.u` @`2accc0`） | Q1 / 判据 | before 读 `args[0]=r0` 的 `u:String`（cdninfo）；after `Z` 仅参考 | 必选·**终局判据** |
| E1 | `hl0.q0.<init>(Ljava/lang/String;Ljava/lang/String;IIJ)V` | `711560`（R72 H3 已验证点） | Q2 | hookAllConstructors + 5 参过滤；读第 5 参 `J`=forwardMsgId；after 只读 `getType()` | 必选 |
| E2 | `hl0.l1.a()Z` | `710360`（R72 H4 已验证点） | Q2 | before 打线程名；命中=入队硬判据。**注意同名 getter `a()Lk1;` 不是它** | 必选 |
| E3 | `hl0.q1.a()Lhl0/l1;` | `712168` | Q2 | before 读 `q1.h` 子类型 + `via=a` | 必选 |
| E4 | `hl0.q1.b()V` | `712388` | Q2 | before 读 `q1.h` 子类型 + `via=b` | 必选 |
| E5 | `hl0.q1.f(Lhl0/m;)Lhl0/q1;` | `7122c4` | Q2 | before 读 `hl0/m` 六字段（`m.a`=forwardFromImgId） | 必选 |
| F1 | `com.tencent.mm.modelimage.b1.C4(Ljava/lang/String;Z)Ljava/lang/String;` | `2a563c` | Q3 | before `args[0]`=URI；after result=解析路径 | 必选 |
| F2 | `com.tencent.mm.modelimage.b1.z3(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;` | `2a6ae4` | Q3 | before `args[0]`=待解析串（`SERVERID://…` / `th_<md5>`）；after result=路径 | 必选 |
| F3 | `qf4.o4.O8(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Lhl0/m;Lcom/tencent/mm/modelscan/ScanCodeInfo;)V` | `845720` | Q3 | before **第 5 参 String**（0-based `args[4]`）=localPath | 必选 |

### 2.2 可选 2 项（不重复挂 / 零日志不反推）

| # | hook 点 | 说明 |
|---|---|---|
| O1 | `com.tencent.mm.modelimage.j2` 全部构造（主 19 参 `2aef10`，descriptor `(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ILcom/tencent/mm/modelbase/t0;ILjava/lang/String;Ljava/lang/String;ZIZIFFLjava/lang/String;Lhl0/m;Ljava/lang/String;)V`） | **复用现有 `op=inp` 的 `ImageNetSceneProbe`**（其 `H2-j2init` 已打 `str[i]/ori/m.a/q1.B`），本探针**不要重复 hook**，避免同一次构造打两遍。若主会话只开 `op=microimg` 不开 `op=inp`，则本项升级为必选。 |
| O2 | `hl0.m.<init>` | R72 H2 佐证 m 构造；R72b 已证该点 + `q1.f` 零日志，**零日志不得解读为"微X没构造 m"**（maxParams 启发式漏无参重载）。仅作辅证，不进判据表。 |

### 2.3 为什么每个都不可省（最小性论证）

- **D1+D2 缺一不可**：原生两条下载路径互斥——`C2` 命中走 `b1.j6`（`843362`），未命中走 `MsgRetransmitUI.j6`→`b1.R5`（`62d176`）。只挂一个会漏判另一条。
- **D3 不可省**：`B8` 返回 `Z` 仅表示"异步任务投递成功"，**不等于 CDN 命中**；要靠它区分"微X根本没调 check"与"调了但没命中走兜底"。
- **D4 不可省**：hit 的唯一真值 = `tj/f.field_exist_whencheck`（`843098` 读它决定 `support second` vs `dealDownLoadImg`），`B8` 的返回给不了这个。
- **D5 不可省（最关键）**：`R5/j6` 只证明"下载被调用过"，**证明不了 `r0.u` 是否真被写上并被 j2 消费**。`j2.O` 的 `2accc0~2acccfe` 是 cdninfo 决定 CDN/本地的唯一分支点——这是"清晰 vs 模糊"的终局开关。
- **E1+E2 不可省**：回答"微X是否走原生 NetScene 发送链 / 是否真入队"，是区分"原生链"与"自建线程直发"的硬判据（R72 文本样本正是靠这两个坐实）。
- **E3+E4+E5 不可省**：R72 遗留问题"图片是否复现 `q1.a|b`/`q1.f` 零日志"必须两点都 hook 才能判；`q1.f` 的 `m.a` 是"微X是否拿到我方缺失的 CDN imgId"的唯一读数。
- **F1/F2/F3 不可省**：分别对应 Q3 三种路径来源——复用宿主解析器（`C4`）、SERVERID 引用直连（`z3`）、自建 localPath 直发（`O8` 第 5 参）。缺任一都无法定位"微X从哪拿真实路径"。

## 3 Q1 微X是否触发原生 CDN 下载

> 必答：**微X图片转发会不会触发原生 CDN 下载？** —— 看下面 D1~D5 的窗口日志组合。打印入参关键值（talker/md5/是否 hit）+ 返回 imgLocalId。
> 以下所有 `before/after` 均为**只读**：不改 `param.args`、不写 `param.result`、不主动调宿主业务方法；`enabled=false` 首行 return，`runCatching` 全包（统一范式见 §8）。日志统一走 `LogX.i/w`（`LogX.kt:19` 的 **`logcat tag = WXGroup`**），**`MIO <code> …` 是日志内容前缀、不是 logcat tag**；字符串 `take(80)`、路径 `take(120)` 防刷屏。⚠️ 抓日志据此按 `WXGroup` 过滤（§7.3），不要用 `-s MIO`。

### 3.1 D1 `b1.R5` —— 下载兜底入口（原生 `j6` 用的同一个）

- **签名（dex 原文 @`2a710c`）**：`com.tencent.mm.modelimage.b1.R5(Ljava/lang/String;Ljava/lang/String;IIILcom/tencent/mm/pointers/PString;Lcom/tencent/mm/pointers/PInt;Lcom/tencent/mm/pointers/PInt;)J`
- **为什么在链上**：原生 `MsgRetransmitUI.j6`(`62d0f8`) 在 `62d154 d3.O8()→b1`，`62d15c v8=jj/m7.C0():String`，`62d164 v9=MsgRetransmitUI.g:String`（`field@6804`，源图完整路径），`62d176 invoke-virtual/range {v7..v15}, b1.R5(...)` → `62d17c move-result-wide v7`（imgLocalId）→ `62d1a4 b1.o2(Long)→r0`。
- **before 打印**（参数下标 0-based，照 descriptor 顺序）：
  - `MIO R5 in s0=<args[0] String> s1=<args[1] String> i2=<args[2]> i3=<args[3]> i4=<args[4]>`
  - `s1` 由字节确证 = `MsgRetransmitUI.g`（源图路径，含 md5，是"md5/源图"读数）；`s0` 来自 `jj/m7.C0()`，**语义 UNKNOWN**（实现阶段对照 `s1`/会话 talker 判它是 talker 还是 svrId 串，见 §8.3）。
- **after 打印**：
  - `MIO R5 out imgLocalId=<param.result as Long>`
  - 加分项（只读输出指针，宿主已写入）：`MIO R5 outPtr path=<getObjectField(args[5],"value") take120> w=<getObjectField(args[6],"value")> h=<getObjectField(args[7],"value")>`（`PString`/`PInt` 为标准 `com.tencent.mm.pointers.*`，字段名 `value`；取不到打 NA，单独 runCatching）。
  - **`imgLocalId` = 下载/建记录后返回的本地图片 id**，是"下载真的发生了"的直接读数。

### 3.2 D2 `b1.j6` —— 真正下载大图的方法（C2 命中分支调用）

- **签名（dex 原文 @`2a74f8`）**：`com.tencent.mm.modelimage.b1.j6(Ljava/lang/String;[BIJZLjava/lang/String;Lcom/tencent/mm/pointers/PString;Lcom/tencent/mm/pointers/PInt;Lcom/tencent/mm/pointers/PInt;)J`
- **为什么在链上**：`qf4/j0.C2`(`84307c`) 在 `843362 invoke-virtual/range {v11..v21}, b1.j6(...)` → `843368 move-result-wide v11`（imgLocalId）→ `8433de b1.o2(Long)→r0` → `8433f4 r0.i(String)→r0.u`（写 cdninfo）。
- **before 打印**：
  - `MIO j6 in s0=<args[0] take80> dataLen=<(args[1] as? ByteArray)?.size ?: -1> i2=<args[2]> svrId=<args[3] as Long> z4=<args[4]> s5=<args[5] take80>`
  - **`byte[]` 只打 `.size`，严禁打内容**；`svrId`(=args[3], J) 是"是否带服务端引用"的关键读数。
- **after 打印**：
  - `MIO j6 out imgLocalId=<param.result as Long>`（同样可选读 `args[6]/[7]/[8]` 输出指针 `value`）。

### 3.3 D3 `mq/k.B8` —— CDN 存在性 check 的发起

- **签名（dex 原文 @`78f618`）**：`mq.k.B8(Ltj/k;)Z`（方法体仅 9 条指令：`ok0/s2.e9()→ok0/m1.e(tj/k)→return Z`，同步只读、只投递异步任务）。
- **为什么在链上**：原生 `B6`(`62c98c`) `62cd18 iput-boolean v0, tj/k.field_onlycheckexist`（v0=1）、`62cd1c .field_trysafecdn=v0`、`62cd34 invoke-virtual {v1,v4}, mq/k.B8:(Ltj/k;)Z`。
- **before 打印**（`args[0]` 是 `tj/k`）：
  - `MIO B8 in onlycheckexist=<getBooleanField(args[0],"field_onlycheckexist")> trysafecdn=<getBooleanField(args[0],"field_trysafecdn")>`
  - 字段名由 `62cd18`/`62cd1c` 字节自证（dexdump 显示 `Ltj/k;.field_onlycheckexist:Z // field@c682`）。
- **after 打印**：`MIO B8 out ret=<param.result as Boolean>`。
- ⚠️ **`B8` 返回 true ≠ CDN 命中**：它只表示任务投递成功。hit 看 D4。

### 3.4 D4 `qf4/j0.C2` —— 异步回调，命中/兜底的分叉点

- **签名（dex 原文 @`84307c`）**：`qf4.j0.C2(Ljava/lang/String;ILtj/e;Ltj/f;Z)I`。
- **为什么在链上**：`84307c` 入口 `843090 v1=v36(=args[3], Ltj/f)`，`843098 iget-boolean v3, v1, Ltj/f;.field_exist_whencheck:Z // field@c60c` → `8430a2 if-eqz v3, 0361`：
  - **命中** → `8430a6 "support second !! sceneResult:[%s]"` → `8433de o2→r0` → `8433f4 r0.i(String)→r0.u`（写 cdninfo）+ `843362 j6`（下载大图）；
  - **未命中** → `843750 "not support second !! dealDownLoadImg"` → `84378e MsgRetransmitUI.j6(...)` → `62d176 R5`（D1）。
- **before 打印**：
  - `MIO C2 in existWhenCheck=<getBooleanField(args[3],"field_exist_whencheck")> s0=<args[0] take80> i1=<args[1]> z4=<args[4]>`
  - **`existWhenCheck` 就是"是否 hit"的唯一真值**（`843098` 字节自证）；字段名带 `field_` 前缀，照抄。
  - ⚠️ **UNKNOWN + 核对法**：dexdump 在 `qf4/j0` 类区间有**两个同签名 `C2`**（904 code units 的 `84307c` 与 63 code units 的短实现）。实现阶段用 `XposedBridge.hookAllMethods(cls,"C2",cb)` 全挂，before 里额外打 `MIO C2 method=<param.method.toString().takeLast(80)>`；判据以 `existWhenCheck` + 后续 D1/D2 日志为准，不依赖区分两个实现（短实现即使触发也不会执行下载分支）。
- **after 打印**：`MIO C2 out ret=<param.result as Int>`。

### 3.5 D5 `j2.O` —— cdninfo 是否被消费的**终局判据**（不可省）

- **签名（dex 原文 @`2acc44`）**：`com.tencent.mm.modelimage.j2.O(Lcom/tencent/mm/modelimage/r0;I)Z`（812 code units）。
- **为什么是终局**：`2accc0 iget-object v4, v1, Lr0;.u:Ljava/lang/String; // field@1449` → `2accc4 a8.F0(v4)` 判空 → `2acccc if-eqz v4, 0056`：
  - **空** → `2accd0/2accfe "cdntra not use cdn flag:%b getCdnInfo:%s"` → `return v6`，**不构造 CDN 上传任务** ⇒ 走本地缩略图（模糊）；
  - **非空** → `2acd38~2acd84 z3(r0.e/f/g/j)` + 构造 `tj/k "task_NetSceneUploadMsgImg"` ⇒ CDN 大图（清晰）。
- **before 打印**（`args[0]` 是 `r0`）：
  - `MIO j2O in r0.u=<getObjectField(args[0],"u") as? String ?: "EMPTY">`
  - 字段名 `u` 由 `2accc0` 字节自证。只读 `getObjectField`，不调用 `ok0/m1.n(1)` 等宿主方法（那会引入副作用）。
- **after 打印**：`MIO j2O out ret=<param.result as Boolean>`（**仅参考，不作判据**：分支方向以 `r0.u` 为准）。

### 3.6 Q1 直接回答模板（窗口日志 → 结论）

| 你看到的日志 | 直接回答 |
|---|---|
| `B8 in onlycheckexist=true` + `B8 out ret=true` + `C2 in existWhenCheck=true` + `j6 out imgLocalId=…` + `j2O in r0.u=<非空>` | **会**：微X走了原生 CDN check，命中后下载大图并写入 cdninfo，j2 消费 cdninfo 走 CDN 上传 |
| `B8` 命中但 `C2 in existWhenCheck=false` + `j6/R5 out imgLocalId=…` + `j2O in r0.u=<非空>` | **会（兜底路径）**：check 未命中，走 `MsgRetransmitUI.j6`→`R5` 下载兜底，同样写 cdninfo |
| `B8`/`C2`/`j6`/`R5` 全零 + `j2O in r0.u=EMPTY` | **不会**：微X完全跳过原生 CDN 下载链；清晰度另找机制（见 §5/§6） |
| `R5/j6` 有下载但 `j2O in r0.u=EMPTY` | **部分**：下载发生了但 cdninfo 没落到该记录/没被 j2 消费 ⇒ 需补验时序（见 §6 矛盾分支） |

**talker / md5 从哪读**：`R5.s1`（= `MsgRetransmitUI.g`，含 `<md5>` 的源图路径）、`j6.s0`/`j6.s5`、`C2.s0`（宿主 check 用的串）即 md5/源标识读数；真正的"目标 talker"若日志里读不到，标 UNKNOWN，不脑补——判据不依赖它（§6）。

## 4 Q2 微X走哪条入队链

> 必答：**微X走 `q0`(type=522) vs `j2` vs `q1/m`？是否复现"q1.a|b / q1.f / m.<init> 零日志"的路径差异？**
> 复用 R72 的 H3/H4/H7 + H1/H5a/H5（本方案 E1~E5）；`j2` 构造**复用现有 `op=inp`**，不重复挂。
> ⚠️ **关键纠正（image_route Q3.3，`MsgRetransmitUI.g6` 字节自证）**：`type=522` 是文本 NetScene `hl0/q0`，`type=110` 是图片 NetScene `com.tencent.mm.modelimage.j2`；`q0.getType()` **恒 522**。所以**图片不能只看 `q0`，必须看 `j2` 构造**（O1/INP）。`q0` 零日志而 `j2` 有日志是**预期形态**，不是探针失败。

### 4.1 E1 `hl0.q0.<init>` —— NetScene 文本链 / forwardMsgId

- **签名（dex 原文 @`711560`）**：`hl0.q0.<init>(Ljava/lang/String;Ljava/lang/String;IIJ)V`（R72 H3 已验证点）。
- **实现要点**：`hookAllConstructors(q0cls, cb)` 后**按 descriptor 过滤**——该点有两个 5 参重载：目标 `(String,String,int,int,long)` @`711560` 与 `(String,String,int,int,Object)` @`711010`。**必须判第 5 参类型是 `long/J`**（`param.args[4] is Long`），否则误挂到 Object 重载。
- **before 打印**：`MIO q0Ctor in th=<线程名> forwardMsgId=<args[4] as Long>`
  - `forwardMsgId` = 第 5 参（R72b 文本样本 = 0）。
- **after 打印**：`MIO q0Ctor out type=<getType()>`（只读 `XposedHelpers.callMethod(thisObject,"getType")`，单独 runCatching，R72 已证安全）。
  - 图片样本若 `type=522` ⇒ 微X连文本 NetScene 一起构造了（罕见，需看 j2 是否同时构造）；若 q0 零日志 ⇒ 图片没走 q0，符合 type=110=j2 的预期。

### 4.2 E2 `hl0.l1.a()Z` —— 真入原生发送队列的硬判据

- **签名（dex 原文 @`710360`）**：`hl0.l1.a()Z`（43 code units，PUBLIC FINAL）。
- **实现要点**：`hl0/l1` 有**同名 getter** `a()Lcom/tencent/mm/modelbase/k1;`（字段 getter，无 code）。用 `findMethodExact("hl0.l1", cl, "a", java.lang.Boolean.TYPE)` 或扫 `declaredMethods` 选 `name=="a" && returnType==boolean && parameterTypes.isEmpty()`，**别挂到 getter**。
- **before 打印**：`MIO l1.a queued=true th=<线程名>`（命中即入队硬证据，R72 判据①同款）。

### 4.3 E3/E4 `hl0.q1.a()` / `q1.b()` —— 子类型与入队路径

- **签名（dex 原文）**：`hl0.q1.a()Lhl0/l1;` @`712168`（主路径）；`hl0.q1.b()V` @`712388`（兜底路径）。
- **before 打印**：
  - `MIO q1.a subtype=<getIntField(thisObject,"h")> via=a`
  - `MIO q1.b subtype=<getIntField(thisObject,"h")> via=b`
  - `q1.h` 子类型语义：4=文本、13=图片、19=多图（`_r73_ma_residual.md` §四 / R36 §6）。字段名 `h` 由 `hl0/q1` dexdump 字段表自证（`name 'h' type 'I' // #12`）。
- **判读**：若图片样本出现 `subtype=13|19` ⇒ 微X用微信原生 `q1` builder 语义入队，**与文本样本（q1.a|b 零日志）不同**；若仍零日志 ⇒ **路径差异复现**，微X图片入队不经 q1.a/b。

### 4.4 E5 `hl0.q1.f(hl0.m)` —— ForwardInfo / CDN imgId

- **签名（dex 原文 @`7122c4`）**：`hl0.q1.f(Lhl0/m;)Lhl0/q1;`（10 code units，ForwardInfo setter）。
- **before 打印**（`args[0]` 是 `hl0/m`，六字段 a~f 均只读）：
  - `MIO q1.f m[a=<getLongField(m,"a")> b=<getLongField(m,"b")> c=<getObjectField(m,"c")> d=<…> e=<…> f=<…>]`
  - **`m.a`(forwardFromImgId) ≠ -1 是"微X拿到我方缺失的 CDN imgId 路径"的唯一读数**（R72 判据②）；字段名 a~f 由 `hl0/m` dexdump 字段表 + `_r73_ma_residual.md` §四（k6 填值表）自证。
- **⚠️ 零日志解释纪律**：`q1.f` 零日志只能说明"没走这个 setter 挂 ForwardInfo"，**不能反推 `m.a=-1`**（R72b 判据②明确）；`m.<init>`（O2）零日志更不得解读为"没构造 m"。

### 4.5 O1 `j2` 构造 —— 复用 INP，不重复挂

- **签名（dex 原文 @`2aef10`，19 含 this = 18 显式参）**：`com.tencent.mm.modelimage.j2.<init>(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ILcom/tencent/mm/modelbase/t0;ILjava/lang/String;Ljava/lang/String;ZIZIFFLjava/lang/String;Lhl0/m;Ljava/lang/String;)V`
- **做法**：主会话**同时开 `op=inp`**（现有 `ImageNetSceneProbe`），用它的 `INP j2init str[i]=… ori=… m.a=… q1.B=NA` 行；本探针不再 hook j2 构造，避免同一次构造打两遍日志。
- **读点**：`j2.S():Lcom/tencent/mm/storage/h9;` @`2ad8e4`（可选只读 getter，拿 `field_imgPath`）；`j2` 主构造器 `m.a>0` 分支 @`2af15c~2af19a`（`b1.o2→z3`）证明图片链会读 ImgInfo2。

### 4.6 Q2 直接回答模板（窗口日志 → 结论）

| 日志组合 | 直接回答 |
|---|---|
| `INP j2init` 有图 + `l1.a queued` + `q0Ctor` 零 + `q1.a|b`/`q1.f` 零 | **自建 j2 直发**：微X图片与文本链同构（自建线程 + 原生 NetScene，不经 q0/q1 builder），**复现路径差异**；入队方式与我方 G1 不同 ⇒ 对应路线 C′ |
| `q1.a subtype=13|19` 或 `q1.b subtype=13|19` 有日志 | **走原生 q1 builder**：微X图片用与 G1 相同的入队语义，与文本样本不同（图片复用了原生转发路径） |
| `q0Ctor out type=522` 且 `j2init` 同时有 | q0 文本链与 j2 图片链**都**被构造（罕见），需按 §7 F4 窗口归因确认哪条属于本次转发 |
| `l1.a` 零但 j2 构造有 | 需补验：j2 构造后是否走别的入队口（`t81/g.a` 等）；不能判"没发"，R72b 已有"q0 有但 l1.a 零"先例 |

**归因硬前置（F2）**：上述任何"零日志"结论，**必须先**确认 `op=microimg` 状态回显 `enabled=true` 且各 hook `OK`（且 `op=inp` 若用于 O1 也 OK），否则空日志无效——不得据此判"微X没走该链"。详见 §7.3。

## 5 Q3 微X从哪拿真实图片路径

> 必答：**微X从哪拿真实图片路径？是否走宿主现成解析器？`localPath` 从哪来？**
> 对应 H9（`b1.C4`/`b1.z3`）与 H8（`qf4/o4.O8` 第 5 参）。
> ⚠️ `_r73_microx_image_route.md` 的"推荐 B 自建路径"是**旧阶段结论**（当时还没查清 CDN 根因）；**本轮不照搬旧推荐**，这三个 hook 只做"路径来源"取证，服务于 §6 判据。

### 5.1 F1 `b1.C4` —— URI→路径的宿主主解析器（H9）

- **签名（dex 原文 @`2a563c`）**：`com.tencent.mm.modelimage.b1.C4(Ljava/lang/String;Z)Ljava/lang/String;`（110 code units）。
- **字节自证**：`2a564e if-eqz v4`（入参 String 空则返回 null）、`2a5668 "THUMBNAIL://"` → `2a567c substring(12)` → `m2(int)→r0.g`；另有 `THUMBNAIL_DIRPATH://` → `substring(20)` → 去 `th_` → `z3(md5,"th_","",z)`（image_route Q1.2 表）。
- **before 打印**：`MIO C4 in uri=<args[0] as? String ?: "null" take120> z=<args[1]>`
  - `args[0]` 即 URI（期望形态 `THUMBNAIL_DIRPATH://th_<md5>`）。
- **after 打印**：`MIO C4 out path=<param.result as? String ?: "null" take120>`
  - 返回值若为 `…/image2/xx/yy/<md5>.jpg` ⇒ 宿主解析器给出了**真实大图路径**；若为 `…/th_<md5>` ⇒ 只到缩略图。

### 5.2 F2 `b1.z3` —— 路径构造 / SERVERID 引用解析（P3 入口）

- **签名（dex 原文 @`2a6ae4`）**：`com.tencent.mm.modelimage.b1.z3(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;`（147 code units）。
- **字节自证**：`2a6af4 a8.F0(args[0])` 判空 → `2a6b04 "SERVERID://"` → `2a6b14 substring(11)` → `2a6b30 b1.Y2(J)→r0` → `2a6b38 iget-object r0.e`（`_r73_ma_residual.md` §八 8.3 的 P3 入口）；另有 `THUMBNAIL_DIRPATH://`/`THUMBNAIL_APPMSG_DIR://` 前缀分支 → `g1.a` 拼桶路径。
- **before 打印**：`MIO z3 in s0=<args[0] take120> prefix=<args[1]> suffix=<args[2]> isThumb=<args[3]>`
  - `s0` 以 `SERVERID://` 开头 ⇒ 微X在消费**服务端引用**（与 P3 同源，`Y2` 会按 msgSvrId 查记录）。
- **after 打印**：`MIO z3 out path=<param.result as? String ?: "null" take120>`
  - 返回 `image2/...` 大图路径 ⇒ 引用解析成功；返回 `th_...` ⇒ 仍指缩略图。
- ⚠️ `z3` 内部 `Y2` 会触发 DB 只读查询；**hook 只打印，绝不主动调用 `z3`/`Y2`**（§8）。

### 5.3 F3 `qf4.o4.O8` —— 自建路径直发的 localPath（H8）

- **签名（dex 原文 @`845720`）**：`qf4.o4.O8(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Lhl0/m;Lcom/tencent/mm/modelscan/ScanCodeInfo;)V`（137 code units，返回 void）。
- **为什么第 5 参 = localPath**：descriptor 参数 0-based：`args[0]=Context`、`args[1]=String`、`args[2]=String`、`args[3]=int`、**`args[4]=String（第 5 参）=localPath`**、`args[5]=String`、`args[6]=hl0/m`、`args[7]=ScanCodeInfo`。image_route Q3.4.1 H8 已证该参喂 `q1.r`（`_r16` §1.3），是"自建路径后直接发图"的判据。
- **before 打印**：
  - `MIO O8 in localPath=<args[4] as? String ?: "null" take120> s5=<args[5] take80>`
  - `localPath` 为 `…/image2/...` 且**非** `th_` 前缀 ⇒ 微X自己解析出大图路径再发（路线 B）；为空/缩略图 ⇒ 不是这条路。
- **after**：返回 void，打 `MIO O8 out`（确认方法正常结束，非异常）即可。

### 5.4 Q3 直接回答模板（窗口日志 → 结论）

| 日志组合 | 直接回答 |
|---|---|
| `C4 in uri=THUMBNAIL_DIRPATH://th_<md5>` + `C4 out path=…/image2/…/<md5>.jpg` | **走宿主现成解析器**：微X把消息 imgPath 喂 `b1.C4`，拿到真实大图路径（对应路线 A/C 的解析步骤） |
| `z3 in s0=SERVERID://…` + `z3 out path=…/image2/…` | **走服务端引用解析**：与 P3 的 `SERVERID://→Y2` 同源，按 msgSvrId 查记录解析回大图路径 |
| `C4`/`z3` 零调用 + `O8 in localPath=…/image2/…/<md5>.jpg`（非 th_） | **自建路径 + O8 直发**：微X自己算桶路径再用原生发图接口（路线 B）；localPath 来源待追（可能是 ImgInfo2 查询或已有缓存） |
| `C4`/`z3`/`O8` 全零但 j2 构造有、图发出 | **自建 NetScene 直发**（路线 C′）：路径来自 j2 内部 `o2(m.a)→z3(r0.e/j/g/f)`，本探针的 F1/F2 是 C4 外层解析器未被调用；需看 INP 的 `m.a` 与 D5 的 `r0.u` |
| `O8 in localPath=…/th_<md5>`（缩略图） | 微X也只拿到缩略图 ⇒ 与"清晰"矛盾，触发 §6 矛盾分支（重测或时序问题） |

**对 P3 的意义**：若 Q3 落"走 SERVERID 引用解析"或"走宿主 C4 解析且伴随 D1/D2 下载" ⇒ 与 P3 入口同源，**证实方向**；若落"自建路径 + 本地大图、全程无下载无 SERVERID" ⇒ 微X靠本地已存在的大图，**P3 对微X不成立**，需补验我方能否在转发前先触发同样的本地大图落盘。

## 6 判据表（互斥 → 结论 → 对 P3 影响）

### 6.1 判据纪律（红线）

- **判据只用对端可见事实 + 宿主状态事实**：对端清晰与否（用户肉眼/保存字节）、`D5 j2O in r0.u` 空/非空、`D1/D2/D4` 下载链是否命中、`F1/F2/F3` 路径形态。
- **严禁把内部计数器当判据**：hook 安装数 `ok=N fail=0`、日志条数、调用次数、线程名出现次数**都不是判据**——它们只用于 F2 前置校验（§7.3）。线程名仅辅助归因，不单独定结论。
- **严禁把互斥条件写成并列**：先按"对端清晰 / 模糊"分；同侧再按 `r0.u` 非空 / 空分；再按"下载链命中 / 未命中"分；最后按"路径来源形态"分。下表每行是 AND 组合，**行间互斥**（条件里已写入排除对方的关键字）。

### 6.2「证实 P3」的充分证据（三要素同时成立，缺一不可）

```
① 对端可见：清晰大图 —— 长按保存字节 ≫15KB（明显大于本坏图 15KB th_ 缩略图 / 一般 th_ 3~8KB），分辨率≈源图
② D5 终局：MIO j2O in r0.u=<非空串>          ← cdninfo 已写入且被 j2 消费
③ 下载链：MIO j6 out imgLocalId=… 或 MIO R5 out imgLocalId=… 或 MIO C2 in existWhenCheck=true
```
三条同时成立 ⇒ **微X清晰的机制 = 原生 CDN check/下载副作用**，与我方 P3 入口（`b1.R5` / `z3 SERVERID://→Y2`）同源 ⇒ **证实 P3 方向**。

### 6.3 主判据表（行间互斥）

| 序 | ①对端事实 | ②`j2O in r0.u` | ③下载链 D1/D2/D4 | ④路径来源 F1/F2/F3 | 结论 | 对 P3 的影响 |
|---|---|---|---|---|---|---|
| **1** | 清晰 | **非空** | 命中（`j6`/`R5` 有 imgLocalId 或 `C2 existWhenCheck=true`） | 任意 | **微X靠原生 CDN check/下载副作用拿清晰大图**（充分证据成立） | **证实**：补 `b1.R5` / `z3 SERVERID` 入口方向正确 |
| **2** | 清晰 | **空** | **全零**，且 `z3 in s0=SERVERID://…` **未出现** | `C4 out=image2/…` 或 `O8 in localPath=image2/…`（非 `th_`） | **微X靠本地已存在的大图 / 自建路径解析**，不经 CDN 下载 | **推翻**（就微X而言）：P3 不是微X机制；需补验"我方能否在转发前先触发大图落盘 / 拿到正确 localPath" |
| **3** | 清晰 | **空** | **全零** | `z3 in s0=SERVERID://…` **出现**（且 `C4`/`O8` 大图路径未命中） | **微X走 ImgInfo2 的 SERVERID 引用直连**（记录自带服务端引用，无需下载副作用） | **需补验**：P3 的 `z3 SERVERID→Y2` 入口仍相关，但 `R5` 下载部分可省；须查我方 `o2(m.a)` 记录是否也带 SERVERID 引用 |
| **4** | 清晰 | **空** | **命中**（与行 2 的"全零"互斥） | 任意 | **矛盾样本**：下载发生了但 cdninfo 未被这次 j2 消费（异步未完成 / 窗口错位） | **需补验**：按 §7 重测一次；**不得**据此下方向结论 |
| **5** | 模糊 | 空 | 全零 | 任意 | **微X图片也没拿到清晰图**（与 R72b 文本"对端无标记"同构） | **推翻参考前提**：微X不能作为"清晰"参照；P3 仍是我方唯一修法方向，但失去微X背书 |
| **6** | 模糊 | 非空 | 命中（与行 5 的"全零"互斥） | 任意 | **矛盾样本**：cdninfo 有但字节读不到 | **需补验**：重测；不据此判方向 |

### 6.4「微X靠别的机制」的专门分支（对应行 2/3/5）

- **行 2（本地大图 / 自建路径）**：判据 = `r0.u` 空 + 下载链全零 + 清晰 + `C4` 返回或 `O8` localPath 为 `image2/.../<md5>.jpg`。含义：微X拿到的是**已经落盘的本地大图**，靠"选对文件"而非"触发下载"清晰。对 P3：若我方源图本地本就有大图（非本坏图那种 36B 占位），则正确 localPath 即可清晰，无需 P3。
- **行 3（SERVERID 引用直连）**：判据 = `r0.u` 空 + 下载链全零 + 清晰 + `z3` 收到 `SERVERID://`。含义：ImgInfo2 记录自带服务端引用，`z3→Y2` 直接解析回大图路径，**不发生 check/下载**。对 P3：`z3 SERVERID` 入口仍是对的，但需先确认我方记录里也有该引用。
- **行 5（微X也不清晰）**：判据 = 对端模糊 + `r0.u` 空 + 下载链全零。含义：微X图片转发与文本一样"绕原生链、对端普通消息"，"参考微X拿清晰图"这条路线**整体证伪**。对 P3：我方 P3 仍是唯一方向，但需靠我方自己的 dex 根因（`_r73_ma_residual.md` §八）背书，不再借微X。

### 6.5 归因与失败兜底

- **若窗口内出现多组 `j6`/`R5`/`C2` 日志**：按 §7 F4 时间窗 + 线程名归因到本次微X转发；无法归因的组**不计入判据**，重测。
- **若误触输入法收起 → 微X闪退 → 微信冷启**：探针内存变量清零，该样本**整段无效**（R72b 22:18 教训），不写入判据表任何行。
- **若 F2 前置校验 fail>0**：任何"零日志"结论无效，先修 hook 安装再测。
- **`D5` 读 `r0.u` 报 `READ_FAIL`**：不等于 `EMPTY`（真空）。字段名变更 / `r0` 未初始化导致读失败时归"需补验"，**不进 §6 表的空值分支**（U9）；只有确实读到 null 才打 `EMPTY` 并进判据。

## 7 用户操作步骤（可执行）

> 本 lane 不碰设备；以下命令交给主会话/用户执行。新探针命名 `MicroXImageObserveProbe`（**日志内容前缀 `MIO`、logcat tag=`WXGroup`**，见 `LogX.kt:19`），**广播 op=`microimg`、sub=`on`/`off`/`status`**（由 team-lead 核定：现有 op = `inp`/`kvset`/`label`/`msync`/`kvget`/`snsprefix`，另有第二类 `when(op)` 在 `EntryReceiver.kt:889`，均不含 `microimg`，不冲突；action 常量 `EntryReceiver.kt:63` = `com.lx.wxgroup.action.PROBE`）。

### 7.1 开测前准备

1. **样本图**：准备两张——(a) 一张已知本地有 `image2/.../<md5>.jpg` 大图的图（做基线）；(b) 若复现坏图 `216b1f7ccd59b1268349fd0eb3c46801`（本地仅 36B 占位 + 15KB `th_`），用它验 P3。**单目标先测**，多目标另开一轮。
2. **编译安装**带 `op=microimg` 探针的模块，LSPosed 勾选启用；**彻底杀掉微信进程再冷启动**——hook 在 `handleLoadPackage` 阶段安装，热启不会重新挂载。
3. **logclear**：`adb logcat -c`（必要时 `adb logcat -b all -c` 清全部 buffer）。

### 7.2 开探针 + F2 硬前置校验（解释任何零日志之前必做）

```bash
# 开新探针（必选 13 hook）
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op microimg --es sub on
# 状态回显（sub=status）——确认 enabled=true 且 13 个 hook 全 OK（sub=status 为主，无 sub 亦回显）
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op microimg --es sub status
# 可选：开 INP 复用 j2 构造日志（O1）
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op inp --es sub on
```
期望回显形如 `MIO STATUS enabled=true installed=true ok=13 fail=0` + 每个 hook 一行 `MIO hook D1 OK <label>`（**探针实现 code 固定为 `D1 D2 D3 D4 D5 E1 E2 E3 E4 E5 F1 F2 F3`，与 §2.1 总表 # 列一一对应**）。**若 `fail>0` 或 `enabled=false`，先修安装再测；此时任何"微X没走某链"的零日志结论一律无效。**

### 7.3 用户操作（精确步骤）

1. 在微信会话里**长按那张样本图**（不要用系统分享，要微X自己的转发入口）。
2. 长按弹出菜单里点**「微X模块 / 微X转发」**项（R71 §6.2 实证微X会往长按菜单注入自己的项）。
3. 选目标联系人：**先只选 1 个**（多目标会让 `j6`/`R5`/`C2` 日志成组，归因困难；多目标兼容性另开一轮验证，见 §1.1 UNKNOWN）。
4. 确认发送。
5. ⚠️ **全程禁忌**：不要点输入法收起（R71/R72 唯一记录的闪退触发动作 → 冷启 → 探针变量清零 → 样本无效）；不要切 App；**窗口内不要触发任何其它消息发送**（F4 归因协议，否则 `q0`/`l1.a`/`R5`/`j6` 产生同形日志无法归因）。
6. 发送完成后**立刻关闭探针**：
   ```bash
   adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op microimg --es sub off
   adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op inp --es sub off
   ```
7. 抓窗口日志：`adb logcat -d -s WXGroup:V | grep -E "MIO |INP j2init"`（**logcat tag 是 `WXGroup`（`LogX.kt:19`），`MIO` 只是内容前缀，不能用 `-s MIO` 过滤**；只看本次开→关之间这一段）。

### 7.4 肉眼判据 vs 日志判据的职责（不可互相替代）

| 判据 | 看什么 | 职责 | 不能替代什么 |
|---|---|---|---|
| **肉眼判据（主/终局）** | 对端长按图 →「保存图片」→ 看**字节数/分辨率**（清晰 ≫15KB、≈源图；模糊 ≈4~15KB 的 `th_` 缩略图） | 决定 §6 表第 ① 维：**对端到底清不清晰** | 日志再漂亮（含 `ok=true`/`onGYNetEnd 0/0`）也不能证明图清晰——历史多次假阳性 |
| **日志判据（机制归因）** | 窗口内 `MIO *` + `INP j2init` 组合 | 决定 §6 表第 ②③④ 维：**为什么清晰/不清晰**（走没走下载链、`r0.u` 是否有值、路径从哪来） | 日志全齐但对端模糊 ⇒ 不能判清晰，必落 §6 行 5/6 |

**两者同时成立才能下"微X靠 CDN 下载拿清晰图"的结论（§6.2 三要素）。** 只有其一都只能写"待补验"。

### 7.5 常见异常与处置

- **误触输入法收起 / 微X闪退 / 微信冷启**：该样本整段作废（R72b 22:18 教训），重新冷启 → logclear → 重测；闪退前的窗口日志仅作弱参考，不进判据。
- **窗口内出现多组下载链日志**：按时间窗 + 线程名（`th=`）归因到本次转发；归因不了的组不计入判据，重测。
- **`op=microimg` 状态 fail>0**：先按 §8.3 核对签名/重载（尤其 `C2` 双实现、`q0` 双 5 参重载、`l1.a` 同名 getter），修好再测。

## 8 最小 hook 集约束 / 开销 / 风险 / UNKNOWN

### 8.1 红线约束（照抄 `ImageNetSceneProbe.kt` 已验证范式）

1. **只读**：所有 `before/after` 只打日志；**绝不写 `param.args[...]`、绝不写 `param.result`**。
2. **不主动调宿主业务方法**：唯一例外是纯 getter（`E1 getType()`、`E2` 选方法、`D5/F1/F2` 反射读字段），单独 `runCatching` 包住，拿不到就打 NA 跳过。**严禁**在回调里调 `b1.R5/j6/o2/z3/Y2`、`mq/k.B8`、`qf4/j0.C2`、`q1.a/b`、`l1.a` 替宿主做事——那会引入下载/DB/发送副作用，破坏"只读观察"的前提。
3. **enabled 首行 return**：`enabled=false` 时回调第一行就 return（零反射、零字符串拼接）。hook 常驻安装，靠广播 `op=microimg sub=on|off|status` 控制日志量（默认 false，冷启静默）。
4. **runCatching 全包**：每个 hook 安装用 `guard(name){…}` 包住（`ImageNetSceneProbe.kt:209-217`），单钩失败只记 FAIL + 一条 WARN，**不崩微信、不整体禁用其它 hook**；回调体同样全包。
5. **日志裁剪**：String `take(80)`、路径 `take(120)`；`byte[]` 只打 `.size` 不打内容；不刷屏。
6. **幂等安装**：`installed` 标志保证只装一次；`status()` 回显 `enabled/installed/hookStatus`，供 F2 前置校验。

### 8.2 逐 hook 开销与风险（下载链重点）

| # | 开销 | 风险 / 缓解 |
|---|---|---|
| D1 `R5` | before 4 次只读字段/参数读 + 字符串 take；after 读 long + 可选指针 `value` | **不改变下载行为/时序**：只打印、不写参、不抛异常（runCatching）。回调可能在微X线程，用 `LogX` 同步落盘范式（INP 已验证）。`s0` 语义 UNKNOWN（U1），判据不依赖它 |
| D2 `j6` | `byte[].size` = O(1)；其余只读 | 同上。**严禁遍历/打印 `byte[]` 内容**（图片数据可能数十 KB，刷屏且耗时）。`svrId`(args[3]) 是关键读数，只读 long |
| D3 `B8` | 2 次 `getBooleanField` | 纯字段读，无副作用。`B8` 本身 9 条指令同步只读，hook 不改变其"仅投递异步任务"的性质 |
| D4 `C2` | 1 次 `getBooleanField` + take | **双同签名实现（U2）**：`hookAllMethods` 全挂可能打重复日志，但不影响宿主分支；判据以 `existWhenCheck` + D1/D2 为准，不依赖区分 |
| D5 `j2.O` | 1 次 `getObjectField(r0,"u")` | **纯字段读，不调用 `ok0/m1.n(1)`**（那会引入副作用）。`r0` 未初始化/字段名变更时读失败 → 打 `r0.u=READ_FAIL`（**不等于 EMPTY**，见 §6.5 / U9），单独 runCatching |
| E1 `q0.<init>` | 双重载过滤 + after `getType()` getter | `getType()` 是纯 getter，R72 已证安全、单独 runCatching。**必须按第 5 参 `is Long` 过滤**（U5），否则挂到 Object 重载 |
| E2 `l1.a()Z` | 无参无字段读 | **同名 getter `a()Lk1;`（U6）**：按 `returnType==boolean` 精确选；挂错则打不到队列日志（F2 会显示 OK 但零日志，误导判据） |
| E3/E4 `q1.a/b` | `getIntField(q1,"h")` | 纯字段读；`q1.h` 字段名由 dexdump 字段表自证。`subtype` 值语义按 4/13/19 解释，取不到打 NA |
| E5 `q1.f` | 2×`getLongField` + 4×`getObjectField` | `hl0/m` 字段名 a~f 随版本可能变（U3）→ 打 NA，不进判据（零日志不反推 `m.a=-1`，R72b 纪律） |
| F1 `C4` | before/after 各一次 take | `C4` 方法体本身会调 `b1.m2`（DB 只读查询）；hook 不增加调用次数。**严禁主动调用 C4** |
| F2 `z3` | before/after 各一次 take | `z3` 的 `SERVERID://` 分支会调 `Y2`→`b1.o2`（DB 只读查询）；同上，**严禁主动调用** |
| F3 `O8` | before 读第 5 参 + take；after 一行 | void 返回，hook 不改变发送行为；只读 `args[4]` |

**总体**：13 个 hook 全部为 before/after 只读打印，单钩开销 O(1)（无遍历、无下载、无 UI 操作、无持久化写）。相比宿主本身的图片下载（网络 + DB + IO）开销可忽略；风险集中在"挂错重载/字段名变更导致假零日志"，由 F2 前置校验 + 本表 U 清单兜底。

### 8.3 UNKNOWN 清单 + 实现阶段核对方法（拿不准不脑补）

| # | UNKNOWN | 核对方法（实现阶段 / 真机） |
|---|---|---|
| U1 | `b1.R5` 第 1 参 String（原生 `62d15c v8=jj/m7.C0()`）语义 | 打 `MIO R5 in s0=…` 与 `s1`（= `MsgRetransmitUI.g`，含 md5）及会话 talker 对照；必要时 dexdump `jj/m7.C0` 看返回来源。**判据不依赖它** |
| U2 | `qf4/j0.C2` 两个同签名实现（904-unit `84307c` / 63-unit 短实现） | `hookAllMethods` + before 打 `param.method.toString().takeLast(80)`；以 `existWhenCheck` + D1/D2 为准 |
| U3 | `hl0/m` 六字段 a~f 混淆名可能随版本变 | 取不到打 NA；核对 `hl0/m` dexdump 字段表；零日志不反推 `m.a=-1` |
| U4 | `PString`/`PInt.value` 标准字段名 | 反射 `declaredFields` 打印核对；取不到打 NA，不进判据 |
| U5 | `q0` 两个 5 参重载：`(String,String,IIJ)`@`711560` 与 `(String,String,IIObject)`@`711010` | `param.args[4] is Long` 精确过滤 |
| U6 | `hl0.l1` 同名 `a()Lk1;` getter 与 `a()Z` 入队方法 | `returnType==Boolean.TYPE && parameterTypes.isEmpty()` 精确选 |
| U7 | `hl0/m.<init>` 重载多，R72 maxParams 启发式会漏 | 标 OPTIONAL（O2），零日志不反推"没构造 m" |
| U8 | 多目标并发下 `R5` 的 `PString`/`PInt` 输出指针隔离 | 沿用 §八 UNKNOWN；本方案先单目标，多目标另开取证 |
| U9 | `D5` 读 `r0.u` 失败（`READ_FAIL`）≠ 空串 | 单独 runCatching：读到 null → `EMPTY`（进 §6 判据）；读字段抛异常 → `READ_FAIL`（**归需补验，不进空值判据**） |

### 8.4 对"Flash lane 直接照抄"的交付清单

- hook 全限定名 + descriptor + code_off：§2.1 表（已 dex 自证）。
- 每个 hook 的 before/after 打印字段与日志格式：§3.1~§3.5、§4.1~§4.5、§5.1~§5.3（`MIO <code> …` 统一前缀）。
- 安装范式（enabled / guard / status / 幂等）：`wxgroup/app/src/main/java/com/lx/wxgroup/probe/ImageNetSceneProbe.kt:40-217` 直接复用。
- 广播 op/sub 与步骤：§7（op=`microimg`，无冲突）。
- 判据表与 UNKNOWN：§6、§8.3。

---

## 9 DONE

`[DONE]` R73「真机观察微X图片转发为何清晰」方案设计完成。产出最小只读 hook 集 13 项（Q1 下载链 `b1.R5`/`b1.j6`/`mq/k.B8`/`qf4/j0.C2`/`j2.O` + Q2 入队链 `hl0/q0.ctor`/`l1.a`/`q1.a|b`/`q1.f` + Q3 路径 `b1.C4`/`b1.z3`/`qf4/o4.O8`，另 2 项可选），全部给 dex 实抄签名 + `code_off` + before/after 打印字段；判据表按"对端清晰 → `r0.u` 空/非空 → 下载链命中/未命中 → 路径来源"分层，行间互斥，明确三要素充分证据与 3 条"靠别的机制"分支；操作步骤含 `op=microimg` 广播命令、F2 前置校验、冷启/logclear、不碰输入法的用户手势、肉眼判据与日志判据职责分工；开销/风险/9 项 UNKNOWN 与实现阶段核对法齐备。全程只读，唯一写操作=本文档；未改源码/测试/探针，未碰 adb/gradle/git/install。

**给 team-lead 的一句话摘要**：微X是否靠 CDN 下载拿清晰图，落到一个终局开关 `j2.O` before 读 `r0.u`——非空且有 `R5/j6` 下载 ⇒ 证实 P3；空而清晰 ⇒ 微X靠本地大图/SERVERID 引用直连，P3 需补验；日志再齐也要对端肉眼清晰才算数。
