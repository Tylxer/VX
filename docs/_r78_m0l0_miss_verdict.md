# R78 裁决：R77 前置探针真机「m0l0/R5 零命中」根因

> 任务：裁决 m0l0/R5 零命中根因，决定 Q4.3 门槛②是否闭合、方案 B 下一步。
> 档位：Pro（Hook 链路推导 + 根因推理 + dex 取证）。
> 约束：只读；唯一写操作 = 本文档；分步落盘（先骨架 [DONE0]，每答一问追加，全完成写 [DONE]）。
> 三纪律：dex 有字符串 ≠ 业务调用；"A 推 B" 时序标假设；引用单测说明是否变异验证。
> 标注体系：【源码确定】/【真机确定】/【推断+置信度】。

---

## Q1：m0l0/R5 零命中根因（探针没抓到 vs stock 没走该链）

### Q1.1 一句话裁决

【源码确定 + 真机确定 + 推断，高置信】**两者都沾，但根因不是"签名漂移"，也不是"stock 没走 CDN 链"，而是 R77 把回调落点认错了类**：探针 hook 的 `qf4/m0.l0` **不是 CDN 完成回调，而是 `MsgRetransmitUI.j6` 内部为 CGI `d3.F8().c(...)` 注册的 `fv/e0` 回调**（j6 只在"B6 的 checkExist 失败、要下载后再转发"分支才走）；B6 真正挂进 CDN task（`tj/k.f : Ltj/i`）的 listener 是 **`qf4/j0`**（实现 `Ltj/i` 的 `C2/I/z`）；stock 在"checkExist 成功/秒传"分支改由 `qf4/j0.C2` → `b1.j6(@0x2a74f8)` 落新记录，**根本不经过 `b1.R5`**。因此 `m0l0/R5` 零命中是**探针落点错位 + 本次样本命中了 checkExist 成功分支**的叠加，**不是 stock 没抓到字节的证据**，也不能据此判定门槛②证伪。

### Q1.2 逐项核对：探针签名是否与 dex 一致（本轮自算 code_off）

> 计算方法：复用 `_r35_adnd_dex.py` 的 dex 二进制 uleb 解析（class_data→encoded_method.code_off）+ `_r35_sns_dis.py` 逐指令解码；对 `_cache/dex/classes7.dex` 逐个读 method_id/proto_id。以下地址均本轮自算，未抄 R77 现成表。

| hook | 探针声明签名（StockCdnProbe.kt） | dex 实况（classes7.dex，本轮自算） | 签名是否准确 |
|---|---|---|---|
| SCD-m0l0 | `l0(JJIIObjectIIString,k1)V`（9 参，selector: [0]/[1]=J、[7]=String、void，L157-163） | `Lqf4/m0;->l0(JJIILjava/lang/Object;IILjava/lang/String;Lc/tencent/mm/modelbase/k1;)V` @ **0x844a0c**，regs=22 ins=12；`Lqf4/m0` 只声明 4 方法（`<init> 0x8449cc`/`Y 0x8449f8`/`l0 0x844a0c`/`q 0x844b38`），**无重载** | ✅ 精确命中；hookRobust 走 exact 分支（L336-338），`hookAllMethods` 兜底**未触发** |
| SCD-R5 | `R5(String,String,int,int,int,PString,PInt,PInt)J`（L222-231） | `Lb1;->R5(Ljava/lang/String;Ljava/lang/String;IIILPString;LPInt;LPInt;)J` @ **0x2a710c**；全 dex 仅此一个 R5 重载 | ✅ 精确命中，无重载遗漏 |
| SCD-m1e | `e(tj/k)Z` | `Lok0/m1;->e(Ltj/k;)Z` @ **0x7b52b4** | ✅ |
| SCD-s0b | `b(r0)String` | `Lmodelimage/s0;->b(Lmodelimage/r0;)Ljava/lang/String;` @ **0x2b429c** | ✅ |
| SCD-j2R | `R()r0` | `Lmodelimage/j2;->R()Lmodelimage/r0;` @ **0x2ad864** | ✅ |
| SCD-B6 | `B6(String,int,t0)V` | `Ltransmit/MsgRetransmitUI;->B6(Ljava/lang/String;ILmodelbase/t0;)V` @ **0x62c98c** | ✅ |

- 【源码确定】**签名维度全部对**，且 `hookStatus ok=6 fail=0`（02:36 装机）、`L336-343` 的 exact 兜底逻辑说明：这几个类里没有第二个同名重载可供"漏挂"。所以"签名/参数类型/个数/返回写错"这一解释**排除**（高置信）。
- 【真机确定】日志 `docs/_r77_stockcdn_realdev.log:9-11` 有 `SCD m1e in/out`、`msig=public boolean ok0.m1.e(tj.k)`；`:4/:50` 有 `SCD s0b ret path=d88c...jpg`；`:6-7` 等 16 行有 `SCD j2R after`；`:8` 有 `SCD B6 out`。即**同批 hook 里 m1e/s0b/j2R/B6 都活**，唯独 m0l0/R5 零行 ⇒ 不是 enabled 关、不是整体 hook 失效。
- ⚠️ 纪律注记：日志缺 `SCD B6 in`（只有 `:8` 的 out）——before 回调理论上必打，缺行疑似日志裁切/时序，本轮不据此下结论；标为【真机事实不完整】。

### Q1.3 决定性 dex 证据：`qf4/m0` 是 CGI 回调，CDN 回调是 `qf4/j0`

1. 【源码确定】`MsgRetransmitUI.B6 @0x62c98c` 反汇编：
   - `0x0157 new-instance Ltj/k;`（构造 CDN task）、`0x015c task.d="task_MsgRetransmitUI_2"`；
   - `0x0161 new-instance Lqf4/j0;` → `0x0185 invoke-direct/range Lqf4/j0.<init>(MsgRetransmitUI,h9,II,String,String,String,String,I,r0)V` → `0x0188 iput-object ... Ltj/k;->f : Ltj/i`。
   - **B6 挂进 task 的 listener 是 `qf4/j0`（Ltj/i），不是 `qf4/m0`。**
   - `Lqf4/j0` 自身声明（全 dex 唯一）：`C2(String,int,Ltj/e,Ltj/f,Z)I @0x84307c`、`I(String,[B)[B @0x843064`、`z(String,ByteArrayOutputStream)V @0x8437d8`；`Ltj/i` 的三个接口方法与之一一对应。**`qf4/j0` 不实现 `fv.e0`，没有 l0。**
2. 【源码确定】谁构造 `qf4/m0`？全 dex `invoke-direct qf4/m0.<init>` 只有一处：`MsgRetransmitUI.j6 @0x62d0f8` 的 `0x0116 new-instance Lqf4/m0;` / `0x0125 <init>` / `0x012b invoke-virtual Ld3/m.c(JJILObject;ILfv/e0;)Z`。即 **`qf4/m0` 是 `d3.F8().c(...)`（CGI `getmsgimg`，R77 Q2 已证此通道对坏图不产字节）的 `fv/e0` listener**——R77 Q1.2 步③把它标成"CDN 完成回调"是**误标**。
3. 【源码确定】`qf4/m0.l0 @0x844a0c` 逐指令：`0x0051 s0.b(r0)` → `0x0057 b1.z3(...)` → `0x005d iput-object MsgRetransmitUI.g`（写 Activity 字段）→ `0x0089 k6(...)`。**它确实"只写 Activity 字段 g"，与 R77 Q4.3 担心的形态一致——但它是 CGI 回调，不是 CDN 回调。** 同类的 `m0.q @0x844b38`/`m0.Y @0x8449f8` 均为空 `return-void`（本轮反汇编确认），**不存在"实际回调走 q/Y 而探针挂 l0"的可能**（该分支排除）。
4. 【源码确定】`j6` 同时是 `b1.R5` 在本流程的唯一调用者：`0x0037 invoke-virtual/range b1.R5(...)`，紧接 `0x004e o2(新localId)`、`0x0053 r0.m`、`0x00ef b1.u8`、`0x0109 d3.F8().c(...)`。而 B6 调 j6 是**条件分支**：`0x01cc mq/k.B8(task)`（CDN checkExist）后 `0x01cf` 有条件跳转；`0x01d2 const-string "check exist fail! download img before retransmit"` → `0x01ec` 才进 j6。**checkExist 成功 ⇒ 不进 j6 ⇒ 既不构造 qf4/m0，也不调 b1.R5。**
5. 【源码确定】checkExist 成功分支的真实动作在 `qf4/j0.C2 @0x84307c`：开头 `0x0006 iget-boolean Ltj/f;->field_exist_whencheck`、`0x000d "support second !!"`，`0x0130 invoke-virtual/range b1.j6(String,[B,I,J,Z,String,PString,PInt,PInt)J @0x2a74f8`（本轮自算 code_off）——**用 `b1.j6` 落新记录（新 localId），不是 `b1.R5`**。这与真机 `R5` 零命中完全自洽。

### Q1.4 对四个子问题的直接回答

- **"签名没抓到"还是"stock 没走该链"？** ——【推断，高置信】**签名都对，是"探针挂错了回调角色 + 本次样本走了 checkExist 成功分支"**。m0l0/R5 零命中不能直接推出"stock 没走 m0l0/R5 链"——准确说是 stock 这次**既没构造 qf4/m0 也没调 R5**，但它**走了 `ok0/m1.e` addSendTask（真机 :9-11 已证）并由 `qf4/j0.C2`/`b1.j6` 处理完成**。所以"stock 走没走 CDN 链"的答案是**走了**；"stock 走没走 m0l0/R5 这两个具体方法"的答案是**没走**。
- **R77 Q1.2 说的完成回调在 `fv.e0`/`qf4/m0.l0`，真机走的是不是这个重载？** ——【源码确定】**不是**。dex 里 `m0.l0` 是 j6 内 CGI 下载的 listener；CDN 完成回调是 `qf4/j0.C2 (Ltj/i)`。R77 把两条回调通道混为一条。
- **有没有可能是 `m0.l0` 另一重载或 `fv.e0` 的 `q`/`l0`/`a`？** ——【源码确定】**排除**：`Lqf4/m0` 仅 4 方法、l0 无重载；`m0.q`/`m0.Y` 反汇编为空 `return-void`；`fv.e0` 接口（`classes7.dex`，code_off=0，abstract）声明 `Y/l0/q` 三个方法，没有 `a`。
- **`hookAllMethods` 漏挂可能？** ——【源码确定】**未触发**：exact selector 命中（签名维度 dex 完全一致），走 `XposedBridge.hookMethod(exact)`；且这两个类无同名重载，**兜底不触发反而正确**。真正的漏挂是"没 hook `qf4/j0`/`Ltj/i` 这一整个回调类"——探针 6 个 hook 里没有任何一个落在该类上。

### Q1.5 "A 推 B" 假设标注与纪律

- 【推断，高置信，标假设】"本次样本 B8 checkExist 成功 ⇒ 跳过 j6 ⇒ 无 R5/m0l0"：dex 有 `0x01cf` 条件跳转 + "check exist fail" 日志分支支撑，但**本轮未真机抓到 `qf4/j0.C2` 是否命中**，故"checkExist 成功"是**由 R5 零命中反推的时序假设**，不是已观测事实；需 Q4 修探针后验证。
- 纪律①：本节**不**因 "B6 里有 `cdnbigimgurl` 字符串" 就断言下载发生；关键证据是 `tj/k.f` 的 listener 类赋值和 `j6/C2` 的字段写入指令。
- 纪律②：B6→B8→C2 的回调时序为跨方法调用链推断，已标假设。
- 纪律③：探针单测 `StockCdnProbeTest.kt` 的 `criticalDexSignaturesPresent`（L76-101）只校验**源码里出现 `robustCallHas(code,"m0cls","l0")`、`"R5"` 字面量**，并**不校验 dex 真实签名/回调角色**；其变异测试（M2 删 hook code、M3 改方法名，L23-27/L160-181）**没有覆盖"把 l0 换成 qf4/j0.C2"这一变异**——所以单测全绿不能证明探针落点正确。本问结论不依赖单测，依赖 dex + 真机日志。

---

## Q2：d88c0173…jpg 的身份裁决

### Q2.1 一句话裁决

【推断，中高置信】(b) 最可能：**用户这次转发的不是坏图 216b，而是另一张本地已有大图的普通图；`d88c01739da4eb9f90cddb9194eb776b.jpg` 就是它的真实大图，`th_a55ca3347b603986673b9632fc90a79e` 是它的缩略图**。`m1e` 的 addSendTask 触发只是 CDN checkExist 的动作（与图是否坏无关），`s0.b` 返回的 d88c 与 `j2.R() r0.e` 指向的 d88c 都是**本就存在的记录路径**，不是本次下载新生成。⇒ **不能把 d88c 当"216b 经 CDN 下载后的新大图"来宣告门槛②间接满足**。(a) 未证伪但证据明显弱；(c) 保留为低概率兜底。

### Q2.2 三解释逐条裁决

| 解释 | 核心主张 | 裁决 | 依据（可自证锚点） |
|---|---|---|---|
| **(a)** | d88c 是 stock 对坏图 216b 走 CDN 下载后 `R5` 新记录的大图，mtime≈02:42，216b 原记录仍在 | 【推断，低-中置信】**未证伪，但与日志矛盾、置信最低** | 若 (a) 必须：① 日志出现 216b/FORWARD/INP `j2init` 标记；② `qf4/m0` 或 `qf4/j0` 完成回调 + `R5`/`b1.j6` 落新记录；③ `g` 应指 `th_216b...` 或新记录缩略图。真机 log 全 57 行**无 216b、无 FORWARD、无 INP j2init**（`:1-57`），`g=THUMBNAIL_DIRPATH://th_a55ca334...`（`:7` 等 16 行）——三条全不满足 |
| **(b)** | d88c 是另一张本地已有大图的普通图；stock 走 checkExist 成功/服务端引用秒传，不下载；m1e 仍因 checkExist 触发 | 【推断，中高置信】**采纳** | ① `g=th_a55ca334...` 与坏图 `th_216b...` **md5 不同**——同一张图的 `r0.e`/`r0.g` 必同源（Q2.3 dex 锚点），故 d88c/a55c 不可能是 216b；② `SCD s0b ret d88c` 命中（`:4/:50`）但 `m0l0/R5` 零命中——`s0.b` 在 dex 有 22+ 调用者（Q1.3），普通转发 `m4.b`/`a13.c2.l0`/`h4.l0` 都会调它读**已存在**的 `r0.e`，无需下载/换 localId；③ `j2.R after` `f=null`（`:7` 等）——`f` 空是"该记录没有独立 mid 图"的常见普通图形态；④ `onlycheckexist=false`（`:10`）仅说明 task 字段值，与"是否真拉字节"无必然联系（Q2.4） |
| **(c)** | 其它（如日志采样丢失、d88c 是缓存中间态等） | 【推断，低置信】保留 | 无正面证据；仅当 adb mtime/DB 显示 d88c 记录既非 02:42 新建、又与 th_a55c 不同源时考虑 |

### Q2.3 dex 侧能否给出"`j2.R()` 返回的 r0 是 o2(哪个 localId)"的判据

【源码确定 + 真机确定】**能，但本次探针打错了字段，判据没落到日志里**：

- `modelimage/j2.R()` @ **0x2ad864** = `if (q == null) q = b1.o2(Long.valueOf(j2.i)); return q;`（R75 §3、R76 plan §3.3 已复核，本轮 code_off 重算一致）。⇒ 决定"取哪条 ImgInfo2 记录"的是 **`j2.i`（J 型 localId）**，入参来自 `Lhl0/q1` 构造的 `j2`（`k6 @0x62d368` 把 `q1` 的 localId 写入）。
- 返回的 `r0` 里：**`r0.a : J` 才是该记录的 localId**（`B6 0x0052`/`j6 0x010d` 均 `iget-wide r0.a` 当 localId 用），`r0.b : J` 是 msgId；`r0.e/f/g` 是大图/mid/缩略图路径；`r0.u` 是消息 xml。
- ⚠️ **探针 `SCD-j2R` 打的是 `i/h/j/Q`（StockCdnProbe.kt:267-271），不是 `a/b`**：`r0.i : I`、`r0.h : I` 是**整型状态字段**，`r0.j : String`、`r0.Q : Z`（Q2.2 字段表本轮 `fields` 实读）⇒ `getLongField` 读 j/Q 必得 NA。真机 `localIds=[i=2 h=0 j=NA Q=NA]`（`:6` 等）里的 `i=2/h=0` **根本不是 localId**，`j=NA/Q=NA` 印证字段类型错配。**这是探针第二处可自证的观测缺陷。**
- **可用判据（修探针后）**：在 `j2.R()` after 打 `r0.a`（localId）+ `r0.b`（msgId）+ `r0.e/f/g`：
  - 若 `r0.a` = 本次 `R5`/`b1.j6` 返回的新 localId 且 `r0.e=d88c` ⇒ 指向 (a)；
  - 若 `r0.a` 是会话里早已存在的老 localId、`r0.e=d88c` mtime 旧 ⇒ 指向 (b)。
  - 该判据 dex 可用，但**当前日志缺失**（探针没打 `a/b`），故本轮**无法靠 dex 单方面定案**，须配合 adb 文件/DB mtime。

### Q2.4 `m1e onlycheckexist=false` 的语义澄清（避免误判 a）

- 【真机确定】`:10` `onlycheckexist=false`；【源码确定】`B6 @0x01be` 有 `iput-boolean Ltj/k;->field_onlycheckexist`（const/4 v0, 值受寄存器复用影响，本轮未静态定值）；R77 Q1.2 声称 B6 设 true，与本轮真机读数**不一致**，R77 该细节须修订。
- 【推断，中置信】`ok0/m1.e`（`0x7b52b4`）反汇编只做 `field_mediaId` 校验 + `new ok0/h1(m1,task)` 提交线程池（`0x0044-0x004f`），**不改 onlycheckexist**；该字段是 task 构造期属性，决定"只探存在 vs 拉全字节"的是 CDN 服务端/客户端后续逻辑。⇒ **不能仅凭 false 断言"本次一定下载了大图"**，这正是 (a) 不能靠 m1e 单独成立的原因。

### Q2.5 一锤定音需要的真机证据（明确命令与判定）

> 以下为**建议执行**，本代理只读、未执行 adb。路径根来自 `:22` 真机日志 `db=/data/user/0/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37/EnMicroMsg.db`。

1. **查 d88c 与 th_a55c 文件 mtime（区分 a/b 的第一判据）**：
   ```
   adb shell 'find /sdcard/Android/data/com.tencent.mm/MicroMsg -type f \( -name "d88c01739da4eb9f90cddb9194eb776b*" -o -name "th_a55ca3347b603986673b9632fc90a79e*" \) -exec ls -l --full-time {} \;'
   ```
   - (a) ⇒ d88c 文件 mtime ≈ `11-22 02:42`、大小 >15KB，且同目录附近能找到 216b 记录文件；
   - (b) ⇒ d88c 文件 mtime **远早于 02:42**（旧图）。
2. **查 ImgInfo2 记录是否本次新建（第二判据）**：
   ```
   adb shell 'sqlite3 /data/user/0/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37/EnMicroMsg.db "select localId,msgSvrId,bigImgPath,midImgPath,thumbImgPath,createtime from ImgInfo2 where bigImgPath like \"%d88c0173%\" or thumbImgPath like \"%a55ca334%\" or bigImgPath like \"%216b1f7c%\";"'
   ```
   - (a) ⇒ 出现一条**新 createtime≈02:42** 的记录指向 d88c，且 **216b 原记录仍在**（两行）；
   - (b) ⇒ 只有老 createtime 的 d88c 记录，无 216b 新记录。
   - 列名以真机表结构为准；若 `sqlite3` 不可用，用已装的 GCONVSTORE/MTG hook 回显替代（日志 `:22-27` 证明该通道可用）。
3. **修探针补 `r0.a/b` 日志**（Q4 细化为 `SCD-j2R` 补丁），让 `localIds=[a=? b=?]` 直接给出 localId/msgId，与 DB 行对齐。

### Q2.6 纪律

- 纪律①：本节未因 "`d88c` 是 .jpg 路径字符串" 就断言"它是下载产物"——`s0.b` 有 22+ 调用者，路径字符串本身不证明下载。
- 纪律②："checkExist 成功→秒传 d88c" 是**由零命中反推的假设**，已明确标注；定案靠 Q2.5 的 mtime/DB。
- 纪律③：未引用单测（本问为 dex + 真机日志取证）。

---

## Q3：门槛②是否闭合

### Q3.1 一句话裁决

【推断，高置信】**按 R77 字面（"完成回调 `qf4/m0.l0` 能拿到大图路径出参、非仅 Activity 字段"）——未闭合，但应判"可跳过"**：Q1 已证 `m0.l0` 是 CGI `d3.F8().c(...)` 的回调、不是 CDN 回调，且 R77 Q4.1 自己已确立"方案 B 不依赖新 localId 必须是 R5 产物，只要让 `j2.R()` 返回的 r0 的 `e/f` 指向大图文件即可"。⇒ **m0l0 回调出参对方案 B 不是必要条件，R77 把它定为"必须门槛"属于过严 + 锚点错配**。但"可跳过 m0l0"≠"字节来源已证"：Q2 显示本次 d88c 大概率是另一张旧图，对**坏图 216b** 的 CDN 字节产出**仍未验证**——真正的必要门槛应改写为"CDN 链对 216b 能产出 >15KB 大图字节 + 我方非 Activity 上下文能拿到该路径/字节"，这项**未闭合**。

### Q3.2 三种状态逐项排除

| 状态 | 是否成立 | 理由（锚点） |
|---|---|---|
| **已闭合** | ❌ 否 | `SCD m0l0` 零行、`SCD R5` 零行（log 全 57 行）；Q1 证 m0l0 非 CDN 回调、Q2 证 d88c 大概率非 216b。若硬把 d88c 当"大图路径出参已拿到"，等于用错样本 + 错回调自证，违反纪律① |
| **证伪 → 退 (c)** | ❌ 否 | 零命中不能推出"stock 拿不到大图字节"：真机 `m1e addSendTask ret=true` 已证 CDN task 下发（log:9-11）；stock 走的是 `qf4/j0.C2`/`b1.j6` 另一条完成路径（Q1.3 dex）。R77 Q4.1 已明确方案 B 不依赖 R5/m0l0 出参。**没有任何新证据表明方案 B 不可行**，退 (c) 为时过早 |
| **未闭合但可跳过**（采纳） | ✅ | 字面门槛锚错回调且非方案 B 必要条件 ⇒ 可跳过；但替换后的真门槛（坏图字节可得）未闭合 ⇒ 不能据此直接派实现（见 Q4 为何仍选 b 而非 a） |

### Q3.3 独立判断：R77 把门槛②定为"必须"是否过严

- 【源码确定】R77 Q4.1（`docs/_r77_p3_verdict.md:146`）原文："方案 B 的落点（`j2.R()` after 写 `r0.e/f`）……**不依赖'新 localId 必须是 R5 产物'——只要让 `R()` 返回的 r0 的 `e/f` 指向大图文件，doScene 的 `z3(r0.e)→o6.N` 就读到大图**"。【源码确定，R76 plan §3.3 `docs/_r76_p3_bytes_landing_plan.md:105-124`】注入点 = `j2.R()` after 直接 `setObjectField(r0,"e"|"f",tempPath)`，窗口在 `o2(j2.i)` 之后、`doScene z3(r0.e)` 之前。
- 【推断，高置信】由上式：方案 B 的数据流是 **"字节 → 自有临时文件 → `R() after` 覆盖 r0.e/f → doScene 读"**；`m0.l0` 回调出参只出现在 **"字节来源"** 这一环的**某一种实现形态**（stock 用 Activity 字段 g 接 CGI 结果）。若字节来源改用 `qf4/j0` 的 `Ltj/i.C2` 出参、或直接从 CDN 落盘文件读（`s0.b`/`z3`），**完全绕开 `m0.l0`**。⇒ **门槛②把某一实现形态的回调出参当作整个方案 B 的必要条件，是过严的**。
- 【推断，中高置信】R77 同时把"完成回调能在非 Activity 上下文拿到路径"设为门槛，是因为担心"模块非 Activity 拿不到 g"——但该担心针对的是 **CGI listener `m0.l0`**（写 `MsgRetransmitUI.g`）；CDN listener `qf4/j0.C2` 同样持有 `MsgRetransmitUI` 引用（`qf4/j0.o`），也有"Activity 耦合"问题，故**"非 Activity 拿路径"这一关切本身仍有效，只是挂错了方法名**。正确的门槛表述应是："**能否在不依赖 `MsgRetransmitUI` 实例字段的前提下，从 CDN 下载结果拿到大图本地路径/字节**"——这对模块转发是真问题，仍需真机/DEX 收敛（`qf4/j0.C2` 的 `Ltj/f` 出参里 `field_exist_whencheck` 等字段是否含路径，本轮 `fields` 实读只见 `Ltj/e` 的 `field_finishedLength/field_status`、`Ltj/f` 未稳定读出路径字段 ⇒ 【UNKNOWN】）。
- **净结论**：字面门槛②可跳过（锚错 + 非必要），但其背后的"非 Activity 拿字节路径"关切必须保留为**改写后的门槛**，且该门槛当前未闭合。

### Q3.4 与 Q2 的耦合提醒

- 若 Q2.5 的 adb 证明 d88c mtime≈02:42 且 216b 原记录仍在 ⇒ (a) 成立 ⇒ CDN 对坏图确实能产字节 ⇒ 改写后门槛可闭合 ⇒ 可进 Q4(a)。
- 若证明 d88c 是旧文件 ⇒ (b) 成立 ⇒ 本次根本没测到坏图 ⇒ 改写后门槛**未闭合** ⇒ 必须 Q4(b) 重取样本。
- 本轮**没有 adb 结果**（只读纪律），故按证据保守裁决：**未闭合**。

---

## Q4：下一步最小动作

### Q4.1 裁决 = **(b) 修探针 + 重取 216b 坏图样本，闭合改写后的门槛后再派实现**

- **(a) 直接派方案 B 实现 —— 否决（本轮）**：Q2 显示本次 d88c 大概率是另一张旧图，对**坏图 216b 的 CDN 字节产出**没有任何真机证据；R77 Q4.3 的前置探针本意就是"先证字节来源"，在样本错、回调挂错的情况下派实现 = 用未验证的字节来源落码，重蹈 R76 方案 A"在错误通道上空转"的覆辙。【推断，高置信】
- **(c) 判方案 B 不可行 —— 否决**：Q1 已证零命中是探针落点/样本问题，不是 stock 拿不到字节；`m1e addSendTask ret=true` 仍成立；R77 Q4.1 的 `R() after` 注入点与 216b 无关。**无新证伪证据**，退 (c) 违反 R77 Q4.3 "不能不经验证就直接判 (c)"。
- **(b) —— 选定**：最小动作 = 把探针落点从 `qf4/m0.l0`（CGI 回调）改到 `qf4/j0.C2`（CDN 回调）+ `b1.j6`（新记录路径）+ 修 `j2R` 读 `r0.a/b`，并**用确认含 216b 的消息重做一次 stock 自带转发**。这是 R77 Q4.3 前置探针的正确版本，不是新增需求。

### Q4.2 探针补丁（精确 dex 签名 + code_off + 接入点）

| code | 动作 | 精确签名 / code_off（本轮自算） | 日志要点 / 目的 |
|---|---|---|---|
| **SCD-CDNcb（新增，替 SCD-m0l0 的角色）** | hook `qf4.j0` 的 `Ltj/i.C2` | `Lqf4/j0;->C2(Ljava/lang/String;ILtj/e;Ltj/f;Z)I` @ **0x84307c**（`classes7.dex`）；因是接口实现，用 `hookAllMethods(findCls("qf4.j0"),"C2",cb)` 挂全 | `before`: 打 param[0]/[1]/[4]、dump `Ltj/e`(field_status/field_finishedLength)、`Ltj/f` 的 String 字段；`after`: 打 ret=param.result。**这才知道 checkExist 成功/失败与是否产字节** |
| **SCD-b1j6（新增，替 SCD-R5 的角色）** | hook 真正的新记录路径 | `Lb1;->j6(Ljava/lang/String;[BIJZLjava/lang/String;Lcom/tencent/mm/pointers/PString;Lcom/tencent/mm/pointers/PInt;Lcom/tencent/mm/pointers/PInt;)J` @ **0x2a74f8**（`classes7.dex`） | `after`: 打 `PString.value`（大图本地路径出参）+ ret newLocalId。**取代 R5 观测点**（stock 在 checkExist 成功分支用 j6 而非 R5，Q1.3⑤） |
| **SCD-j2R（修字段）** | 仍 hook `j2.R()` | `Lj2;->R()Lr0;` @ **0x2ad864**（不变） | 把 `longFieldStr(r0,"i"/"h")` 改为 `"a"/"b"`（J 型 localId/msgId），路径仍打 `e/f/g`；删 `"j"/"Q"`（非 J 型，恒 NA）。**当前 `i=2/h=0` 不是 localId**（Q2.3） |
| **SCD-m1e（保留+加字段）** | 不变 | `Lok0/m1;->e(Ltj/k;)Z` @ **0x7b52b4** | 补打 `tj/k.f` 的 `javaClass.name`（确认 listener=qf4/j0）、`field_fullpath`/`field_midimgpath`/`field_onlycheckexist`；拿回任务级证据 |
| **SCD-s0b / SCD-B6（保留）** | 不变 | `s0.b @0x2b429c` / `B6 @0x62c98c` | B6 `before` 补打 `r0.u` 是否含 `216b1f7c`（只打"是否+长度"，不打完整 key）⇒ **样本身份闸门** |

- 全部遵循原只读纪律（StockCdnProbe.kt:20-39）：只打日志、不写 args/result、不主动调宿主业务方法、enabled 首行 return、单钩 guard。

### Q4.3 重取样本的硬门槛（避免再次拿错图）

- 转发**确认携带 md5 `216b1f7c...` 的那条消息**（不是相册另选图）；日志必须同时出现：
  1. `SCD B6 r0.u hasBigImgUrl=true`（或 `u` 含 `216b1f7c`）⇒ 样本身份正确；
  2. `SCD m1e in/out addSendTask ret=true` ⇒ CDN task 确实下发；
  3. `SCD m1e task fileId=` 非空、`field_onlycheckexist` 值记录；
  4. `SCD j2R after` 的 `a=` 与 `INP j2init`/`FORWARD` 行的 `216b` 标识对齐。
- 三条任一不满足 ⇒ 本次取证作废，不能用来判门槛。

### Q4.4 互斥判据（修探针后一次真机跑完可定案）

| 观测组合 | 结论 | 后续 |
|---|---|---|
| C2 命中 + `b1.j6` 返回新 localId + `j2R r0.a`=新 id + `r0.e` 指向**本次 mtime≈测试时刻且 >15KB** 的文件 + 216b 标识齐 | (a) ⇒ CDN 对坏图产字节 ⇒ **改写后门槛闭合** | 进 Q4(a)：派方案 B 实现（接入形态见 Q4.5） |
| C2 命中（`field_exist_whencheck=true` / "support second"）但 `r0.e` 指向**旧文件**、无 216b 新记录 | 仍是 (b)/checkExist 秒传，**坏图字节未证** | 强制 task `onlycheckexist=false` 走真下载后重测；仍不产字节才考虑 (c) |
| 216b 样本下 `m1e` 都不触发 / C2 完全不命中 | CDN 入口对坏图不可用 | 才进入 (c) 评估，如实标注限制 |
| 旧图样本（d88c/a55c，无 216b） | 本次作废 | 重取样本，不计入任何裁决 |

### Q4.5 若转 (a) 的字节来源接入形态（给 Flash 可落码精度）

> 本节仅在 Q4.4 判 (a) 后启用；当前不派实现，但先把形态钉死避免下一轮再扯皮。

1. **触发下载**（照抄 B6 @0x62c98c 的字段构造，只读消息 xml）：
   - `r0 = d3.O8().o2(localId)`（`b1.o2 @0x2a52a0`）→ `map = g9.c(r0.u,"msg")`（B6 `0x00b8`）→ 取 `.msg.img.$cdnbigimgurl`/`$aeskey`/`$md5`（B6 `0x00d8-0x0155`）；
   - `new tj/k`（`0x0157`），`task.d="task_MsgRetransmitUI_2"`（`0x015c`），`field_mediaId/fileId/aesKey/filemd5/fileType/talker/priority/onlycheckexist/trysafecdn`（`0x01a5-0x01c0`）；
   - `ok0/m1.e(tj/k) @0x7b52b4` 下发。
2. **拿回大图路径（绕开 Activity）**：自建 `Ltj/i` 实现（`C2/I/z`）注入 `tj/k.f`，替代 `qf4/j0`；在 `C2` 出参里读 CDN 落盘结果（【UNKNOWN】`Ltj/e`/`Ltj/f` 是否直给路径，须 Q4.2 的 dump 确认）；兜底用 `s0.b(r0) @0x2b429c` / `b1.z3(...) @0x2a6ae4` 从结果 r0 取 `e`（大图本地路径）。**禁止**依赖 `MsgRetransmitUI.g`。
3. **落自有文件**：三门槛 `exists && length>15*1024 && !name.startsWith("th_")`，复制到 `cacheDir/_<uuid>.jpg`（R76 plan §3.1/§3.2，先 `.part` 后 rename）。
4. **注入点**：`XposedBridge.hookMethod(j2.R() @0x2ad864, after)` → `setObjectField(param.result,"e",tempPath)` + `"f"` 双写；交接用 ThreadLocal/短 TTL Map（R76 plan §3.3/§8.4）。**不调 `b1.u8`**（R76 plan §3.4）。
5. **红线**：不动 `CONSTRUCTOR_ID_IMAGE=13`、不动已闭环的 `m.a/q1.f/q1.h`、不写宿主 ImgInfo2/DB。

### Q4.6 纪律

- 纪律①：Q4.5 的下载动作以 Q4.4 判 (a) 为前提，**不因 dex 里有 `cdnbigimgurl` 字符串就当已下载**。
- 纪律②：C2→j6→新 localId 的时序是 dex 调用链推断 + 待真机验证，已标假设。
- 纪律③：探针单测（StockCdnProbeTest.kt）不覆盖"回调角色正确/样本身份"，补探针后须加对应断言并做变异验证（本轮只设计、不改码）。

---

## 结论

- **Q1**：m0l0/R5 零命中根因 = **探针挂错回调角色 + 本次样本走了 checkExist 成功分支**，不是签名漂移、也不是 stock 没走 CDN。dex 自算证实：`qf4/m0.l0 @0x844a0c` 是 `j6` 内 CGI `d3.F8().c` 的 `fv/e0` 回调；B6 @0x62c98c 挂进 CDN task 的是 `qf4/j0`（`Ltj/i.C2 @0x84307c`）；checkExist 成功分支用 `b1.j6 @0x2a74f8` 落新记录而非 `b1.R5 @0x2a710c`。探针 6 个 hook 签名全部与 dex 一致、无重载遗漏——**零命中不能当作门槛②证伪**。
- **Q2**：`d88c0173...jpg` 【推断，中高置信】是**另一张本地已有大图的真实大图**（`th_a55ca334...` 是其缩略图），不是 216b 经 CDN 下载的新大图——`g` 的缩略图 md5 与坏图不同、日志无 216b/FORWARD/INP 标记、`s0.b` 多调用者可读旧记录。dex 判据存在（`j2.R()=o2(j2.i)`，返回 r0 的 localId 在 `r0.a : J`）但探针错打 `i/h/j/Q`，故本轮日志无法定案；一锤定音靠 adb 查 d88c 文件 mtime + ImgInfo2 记录 createtime（Q2.5 命令）。
- **Q3**：字面门槛②**未闭合但可跳过**——R77 把"m0l0 回调出参"设为必需属过严且锚点错配（Q4.1 已确立方案 B 只需 `j2.R() after` 覆盖 r0.e/f）；但"非 Activity 上下文拿到 CDN 字节路径"这一真实关切须保留为改写后门槛，且**对坏图 216b 仍未闭合**。
- **Q4 最终 = (b)**：修探针（`SCD-CDNcb=qf4/j0.C2 @0x84307c`、`SCD-b1j6=b1.j6 @0x2a74f8`、`SCD-j2R` 改读 `r0.a/b`，保留 m1e/s0b/B6 并加样本身份闸门）+ 用确认含 216b 的消息重取样本；按 Q4.4 互斥判据一次定案后再决定派 (a) 实现或退 (c)。不直接派实现、不判不可行。

### 是否推翻 R77 门槛②设定

- **部分推翻（设定层面）**：R77 Q4.3 把"`qf4/m0.l0` 完成回调出参可取大图路径（非仅 Activity 字段）"设为方案 B 的**必须门槛**，本轮 dex 证明锚点错（该方法是 CGI 回调，CDN 回调是 `qf4/j0.C2`），且 R77 Q4.1 自己已论证方案 B 不依赖该出参 ⇒ **该门槛作为"必要条件"被推翻，应改写**。
- **未推翻（结论层面）**：R77 Q4 选 (b) 转方案 B、字节来源改走 stock CDN 链的**大方向未被推翻**；本轮只是把"前置探针怎么挂、挂哪里、拿什么样本"纠正，并把"字节来源对坏图仍待验证"从乐观假设拉回未闭合状态。**没有证据支持退 (c)。**

---

证据基线（可自证）：code_off 由 `_r35_adnd_dex.py`/`_r35_sns_dis.py` 对 `_cache/dex/classes7.dex`（微信 8.0.48/2588，权威源 `_cache/mm.apk`）逐字节解析，本轮**自算未抄历史表**；真机事实取自 `docs/_r77_stockcdn_realdev.log`（57 行）；探针事实取自 `wxgroup/app/src/main/java/com/lx/wxgroup/probe/StockCdnProbe.kt` 与 `StockCdnProbeTest.kt`；R77/R76 论断取自 `docs/_r77_p3_verdict.md`、`docs/_r76_p3_bytes_landing_plan.md`。全程只读，未改任何源码/测试/配置，未执行 adb/gradle/git。

[DONE]
