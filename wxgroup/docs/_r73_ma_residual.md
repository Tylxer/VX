# R73 `m.a` 之外的第二个判别标准 — 只读根因裁决（residual lane）

- lane：`r73-ma-residual`（只读；不改源码/测试/探针，不碰 adb，不跑 gradle，不 commit；唯一写操作=本文档）
- 档位：Pro（Hook 链路级根因推理；model 按用户临时规则 hy4-flash-free，不入文件）
- 证据优先级：dex 反汇编字节（code_off + 原文）> 源码行 > 真机日志 > 结论文档转述
- 反汇编工具：`tools/_r27_disasm.py` 的 `Dex` 类读 `_cache/dex/classes7.dex`（关键类均在此 dex）

## 一、已确立事实（真机确定，不推翻）

- 坏图 md5 `216b1f7ccd59b1268349fd0eb3c46801`（桶 image2/21/6b；磁盘仅 36B `<md5>.jpg` 占位 + 15KB `th_<md5>` 真缩略图，无大图）。
- 微信自带转发该图 → 对端清晰大图；我方「转发（模块）」多目标 → 对端模糊（用户肉眼两次确认）。
- Q1-R 裁决（`_r73_wxnative_image_source.md`）曾称判别标准 = `hl0/m.a`（源图 imgLocalId）：原生 `m.a>0` 走 `d3.O8().o2(m.a)` 挂 ImgInfo2/CDN 拿清晰；我方旧值 -1 读本地缩略图。
- **但装 m.a 修复后真机**：我方 `m.a=883190`，与微信自带**逐字节相同**，对端**仍模糊** ⇒ `m.a` 必要非充分，Q1-R「唯一判别」不成立。
- 证据出处：`docs/派发审计台账.md` 末尾「R73 m.a 修复真机验收」段（行 5166-5175）。

## 二、待解释的逐参差异（微信清晰 vs 我方模糊，同一张坏图、m.a 都=883190）

来源：INP 探针 `j2init` 逐参 dump（`ImageNetSceneProbe.kt:113-121`，`str[i]=a[i]`，i 即构造器入参下标）。

| 参数 | 微信自带（清晰，[GT]ColdPool#6） | 我方模块（模糊，wxg-forward） | 可信度 |
|---|---|---|---|
| `m.a` | 883190 | 883190（相同） | 高（getLongField 实测） |
| `q1.B` | NA | NA | 探针盲区（j2 构造器不可达 q1），恒 NA |
| `str[1]`（args[1], String） | `wxid_myzsbcur81xr21`（源 talker） | `th_216b1f7ccd…`（缩略图名） | 高（逐参 dump） |
| `str[2]`（args[2], String） | `wxid_xlrbelf6hv0w22`（目标） | 目标（同） | 高 |
| `str[3]`（args[3], String） | `…/image2/21/`（目录） | `…/image2/21/`（同） | 高 |
| `str[7]`（args[7], String） | `wxid_4krmxebor2gm22:`（带冒号转发源前缀） | 空 | 高（逐参 dump） |
| `ori`（探针启发式挑的 String） | `…/216b1f7ccd…46801.jpg`（大图名） | `…/th_216b1f7ccd…`（缩略图） | **中（启发式，可能挑错参数）** |

j2 主构造器 proto（复核 lane 反汇编确定）：
`V(I,String,String,String,I,t0,I,String,String,Z,I,Z,I,F,F,String,Lhl0/m;,String)`（18 个显式入参，args[0..17]；`m` 是 args[16]）。
探针把 `str[i]` 的 i 直接对应 `param.args[i]`。

## 三、候选（待逐字节验证，不预设）

- 候选 A（q1.B / o1 分派）：微信自带可能走 `q1.B=CDN_IMAGE(o1.g)` → `k2/NetSceneUploadMsgImgForCdn` 秒传，而非 j2 本地重传。但我方 INP 见到 j2init 触发——是「微信自带也构造 j2 但同时/先后走 k2」还是「两者都 j2、差异在别处」？j2 与 k2 关系要逐字节厘清。
- 候选 B（j2 某 String 入参决定读哪个文件）：`str[1]`/`str[7]` 差异是否就是宿主 `j2.doScene` 里读图/决定走 CDN 还是本地 ori 的关键？`j2.doScene` 有字面 `doScene hd img info is null or error` / `doScene img info is null or error`，查它到底读哪个字段判分支。
- 候选 C（ori/上传源字段）：微信 ori 指大图名、我方指缩略图名——若 j2 真从这里读本地文件字节上传，则 m.a 挂 ImgInfo2 也没用（印证本地无大图 ⇒ 只能缩略图）。查清 j2 到底从哪里读二进制（ori 字段 vs ImgInfo2 的 SERVERID→CDN）。

## 四、候选验证记录（分步 append，每条含 evidence 出处 + 字节锚点）

### 反汇编基础设施说明

- 主用 `_cache/_r11_dex7_dis.txt`（dexdump 全量，带绝对 code 地址 + 相对偏移，寄存器号可靠）。
- 辅助 `tools/_r27_disasm.py` 的 `Dex`（method_id/proto_str/code_off 定位）。实测该脚本的 `dump_method` 对复杂 35c/3rc 之后的部分 10t/分支 opcode 解码会失步（出现 `v7498`/`goto -15865` 之类伪值），**故关键控制流判定一律以 `_r11_dex7_dis.txt` 为准**，脚本只用于取 code_off / proto。
- 所有关键类（j2 / MsgRetransmitUI / t81.g / b1 / modelimage.a / hl0.q1 / hl0.m）均在 `classes7.dex`。

### 关键结构：j2 主构造器 19 参 = q1 字段的逐字段搬运

`com.tencent.mm.modelimage.j2.<init>` 主 19 参构造器 code=`0x2aef10`（ins=19，即 18 显式参 + this），proto：
`V(I,String,String,String,I,t0,I,String,String,Z,I,Z,I,F,F,String,Lhl0/m;,String)`。

`hl0/q1.a()` → `t81/g.a(q1)`（code=`0x8bb740`）的 `q1.h==13` 分支（`0x8bba04`：`const/16 v3,#13; if-ne v2,v3`）把 q1 字段搬进这 19 参（`0x8bba18`-`0x8bba72`），逐字段对应：

| j2 参位 | 来源 q1 字段 | 探针标签 | 我方 FileSender 填入 |
|---|---|---|---|
| args[1] String | `q1.b` | `str[1]` | `q1.g(f.name)`=文件名 `th_<md5>` |
| args[2] String | `q1.a` | `str[2]` | `q1.h(wxid)`=目标会话 |
| args[3] String | `q1.j` | `str[3]` | `setObjectField(q1,"j",path)`=完整本地路径 |
| args[5] t0 | `q1.l` | — | null |
| args[7] String | `q1.r` | `str[7]` | **未设置（q1 默认 "")** |
| args[8] String | `q1.s` | `str[8]` | **未设置（q1 默认 "")** |
| args[15] String | 常量 `""`（`0x8bba6a`）或 h==19 分支=q1.x（`0x8bbba0`） | `str[15]` | 我方 h==13 ⇒ `""` |
| args[16] `Lhl0/m;` | `q1.t`（`0x8bba44` `iget-object q1.t`，经 `q1.f(m)` 挂入） | `m.a` | `setLongField(m,"a",883190)` |

证据：`_r11_dex7_dis.txt` 行 2609525 起 `t81.g.a`；FileSender.kt:159-183；ForceForward.kt:2062-2077/2116-2130。

### 候选 A（q1.B / o1 分派：CDN_IMAGE→k2 秒传 vs IMAGE→j2）— **排除（不是判别标准）**

1. 原生 `MsgRetransmitUI.k6`（`0x62d368`）`[0x009f] sget-object v10, hl0/o1.e`（**IMAGE，非 CDN_IMAGE**）→ `[0x00a1] r1.a(o1.e)` 造 q1 → 后续 `q1.a()`→`t81/g.a()`→j2。**原生这次真机就是 k6→j2，与我方同入口**（证据：`_r11_dex7_dis.txt` k6 段 `62d4b6`/`62d4ba`）。
2. `t81/g.k`（CDN_IMAGE 分派，`0x8bbd74`）造的是 **`com.tencent.mm.modelimage.k2`**（`0x8bbdd2 new-instance k2`，`0x8bbdda invoke-direct k2.<init>(I,ot5,r0,String,tj/f,l0,J,J)`，即 NetSceneUploadMsgImgForCdn）。`g.k` 走 k2 的前提是 q1 实例为 `modelimage/g`（`[0x0005] instance-of v15, modelimage/g`），即 `q1.B=CDN_IMAGE`。**我方 `r1.a(o1.e=IMAGE)` 造的是 hl0/q1 基类，非 modelimage/g，永远不可能进 g.k/k2**。
3. 关键：**真机两条链都触发了 `INP j2init`**（微信自带 `[GT]ColdPool#6`、我方 `wxg-forward` 均打出 j2 构造器日志，`_dbg_pull.txt:755-765/871-891`）。若原生走 k2（NetSceneUploadMsgImgForCdn），j2 构造器**不会被调用**、探针不会有 j2init 日志。⇒ 这次原生清晰图**不是** k2 秒传贡献的，是 j2 贡献的。
4. 结论：**j2 vs k2 不是本次差异来源**。两者都构造 j2；`q1.B` 探针恒 NA（`_r73_ma_review.md` 风险点 4.2 已证 j2 构造器不可达 q1），且 q1.B 本身在两条链里都是 IMAGE（原生 k6 用 o1.e），无区分度。**候选 A 排除。**

证据出处：`_r11_dex7_dis.txt` k6 段（`62d4b6`）、`t81.g.k`（`8bbdd2/8bbdda`）、`_dbg_pull.txt:755-891`。

### 候选 B（j2 某 String 入参决定读哪个文件 / 走 CDN 还是本地）— **部分成立，但不是清晰度根因；是 str[1]/str[7]/ori 差异的来源**

把 `str[1]/str[3]/str[7]` 追到 j2 内部用途（`_r11_dex7_dis.txt` j2 主构造器 0x2aef10）：

- **`str[7]`（q1.r）→ `j2.B`**：`2af13a [0x010d] v11=v40(=args[7])` → `2af13e [0x010f] iput-object v11,j2.B`。`j2.B` 在 doScene 的 `2ade6e [0x0261] iget-object v3,j2.B` → `[0x0263] a8.F0(v3)` → 仅用于消息来源展示（`msg.N0()`），**不参与读图字节**。
- **`str[3]`（q1.j）→ `j2.L`**：`2af0fa [0x00ed] v5=v36(=args[3])` → `2af0fe [0x00ef] iput-object v5,j2.L`。doScene `2adbbc [0x0108] iget-object v15,j2.L` → 用于缩略图/目录推导（`o6.q`/`po0/x0.a`），非原图字节源。
- **`ori`（探针按 startsWith("/")||contains("image2/") 启发式挑，可能挑错）**：原生 ori=`…/<md5>.jpg`（大图名）、我方 ori=`…/th_<md5>`（缩略图名）。但 j2 真正读原图字节**不走这些 String 字段**：

**j2 读原图字节的真实来源 = `b1.o2(m.a)` 挂的 ImgInfo2 记录（r0），路径由 `r0.e`/`r0.j`/`r0.g`/`r0.f` 经 `b1.z3` 解析：**

- 构造器 `m.a>0` 分支 `2af15c [0x011c] iget-wide m.a` → `[0x0120] if-lez` → `2af16c [0x0126]…2af17e [0x012c] b1.o2(Long(m.a))→r0` → `2af188 [0x0134] r0.j` → `[0x0139] b1.z3` → `iput-object r0,t0.o`（`t0`=fv/t0 上传参数）；同时 `2af262 [0x01a2]/2af2d2[0x01da] b1.L5(t0)` 建上传记录 → `j2.h` → `[0x01e3] j2.i(=j2.h)`。
- doScene `2adaa6 [0x007d] R()` → `R()`(`0x2ad864`) = `j2.i` 空则 `b1.o2(j2.i)→r0`；再 `b1.i2(r0.a)` 查 HD 图；通过 then `2adece[0x0291] b1.z3(r0.e)`、`2adee2[0x029b] b1.z3(r0.g)`、`2adf06[0x02ad] b1.z3(r0.f)` 得真实文件路径 → `o6.l()` 取文件长度上传。
- 另一路 `j2.U(r0)`（`0x2ae668`）：`r0.k==1 ? z3(r0.j) : z3(r0.e)`，同样是 ImgInfo2 字段，不是 ori/String 入参。

⇒ **候选 B 判定**：str[1]/str[7]/ori 的差异是**两条链构造 q1 时填值不同的表征**（见下「唯一差异根因」），但 j2 决定"读本地缩略图 vs 服务端大图"的开关是 **`m.a>0` 分支是否成功挂上带 SERVERID/CDN 凭证的 ImgInfo2 记录**，不是这些 String。它们单独改了**不足以**改变清晰度。**候选 B 不是充分判别标准。**（与 Q1-R.4 ① 一致：ori 非数据源。）

证据出处：`_r11_dex7_dis.txt` j2 构造器 `2af15c-2af19a/2af262/2af2d2`、doScene `2adaa6/2adece-2adf0c`、`j2.U`(`2ae668`)、`j2.R`(`2ad864`)。

### 候选 C（ori/上传源字段：j2 是否从这里读字节）— **排除读 ori；但暴露真正根因**

由候选 B 的字节链已证：j2 读字节走 `b1.o2(m.a)`→ImgInfo2→`z3(r0.e/j/g/f)`，**不走 ori 字段**。`m.a>0` 时 ori（哪怕指向 36B 占位）不是上传源。⇒ 单纯"改 ori 指向"无效。**候选 C 的"读 ori"假设排除。**

但候选 C 的关键副产物（必须解释的现象）：md5 216b 本地**只有 36B 占位 + 15KB 缩略图、无大图**。既然 j2 读的是 ImgInfo2 的 `r0.e/j/g/f`，那么**清晰度取决于 `b1.o2(m.a=883190)` 取回的那条 ImgInfo2 记录里 `r0.e/j/g/f` 指向的是"服务端大图引用(SERVERID/CDN)"还是"本地缩略图路径"**。两条链 m.a 相同（883190），`o2` 查询应返回**同一条** ImgInfo2 记录才对——那为何结果不同？⇒ 见下「唯一根因」：差异不在 m.a 查询本身，而在**构造 j2 时 `m` 对象其它字段（`m.b`/`m.d`/`m.c`/`m.f`，均来自原生 intent）把我方引向了不同的上传分支**。

证据出处：同上；`_r11_dex7_dis.txt` `b1.z3`(`2a6ae4`) 的 `SERVERID://`→`Y2(msgSvrId)` 分支 `[0x000a]-[0x0024]`、`THUMBNAIL_DIRPATH://`→`th_` 前缀分支 `[0x0047]-[0x0091]`。

---

### 唯一根因（综合判定，逐字节自证）

**差异根 = 原生 k6 在造 `m`/q1 时，除了 `m.a`，还从 `MsgRetransmitUI` 的字段 / intent 填入了我方完全没有填的一组字段；其中 `m.b`（forwardMsgId=源消息 localid）与 q1 的 `b/r/j` 值决定了 j2 走"服务端引用"分支是否成立。我方只补了 `m.a`，其余仍是照抄 `qf4/o4.O8`（普通发本地图）的默认值，于是 j2 在 `m.a>0` 拿到 ImgInfo2 后，后续 `u3(m.b)`→h9→`z3(h9.imgPath)` 解析出的是**本地缩略图路径**，上传 15KB ⇒ 模糊。**

逐字节对照原生 k6（`0x62d368`，`_r11_dex7_dis.txt` 行 `62d368` 起）：

| k6 填入 | 字节锚点 | 值（原生） | 我方现状 |
|---|---|---|---|
| `m.a` | `62d528 [0x00d8] m.a=v28`（k6 第6参 J，=imgLocalId） | 883190 | 已补 883190 ✔ |
| `m.b` | `62d530 [0x00da] iget-wide MsgRetransmitUI.f → m.b`（field@6802） | 源消息 msgId（**原生从 Activity 状态取**） | `attachForwardInfo:2125` 写 `fi.msgId`；FileSender **完全没写 m.b（默认 0）** |
| `m.d` | `62d538 [0x00e0] MsgRetransmitUI.I → m.d`（field@67eb） | 转发源 sns/uuid 串（探针 `str[1]=wxid_…` 可能与此相关） | `attachForwardInfo:2127` 仅 `fi.uuid!=null` 才写；FileSender 不写 |
| `m.c` | `62d568 [0x00f8] m.c=v6`（k6 起始处的 String，来自 B6 的 `msg_forward_sns_obj_id` intent） | sns obj id | 恒 ""/null |
| `m.e`/`m.f` | `62d550/62d564 [0x00ec/0x00f6] intent getStringExtra` | geta8key_username / serverMsgID | 我方不写 |
| `q1.b` | `62d4c6 [0x00a7] q1.g(v3)`,`62d4d2 [0x00ad] q1.j=MsgRetransmitUI.g` | v3=B6 传入的"源串" | `q1.g(f.name)`=`th_<md5>`（缩略图名） |
| `q1.r` | `62d4ea [0x00b9] q1.r=v4`（k6 第4参 String） | `wxid_4krmxebor2gm22:`（带冒号转发源前缀） | **未设（""）** |
| `q1.j` | `62d4d2 [0x00ad] q1.j=MsgRetransmitUI.g`（源图完整路径=`<md5>.jpg`） | 大图路径 | `snap.localPath`=缩略图路径 |

**这正好逐项解释真机逐参 dump**（`_dbg_pull.txt`）：
- `str[1]` 原生=`wxid_myzsbcur81xr21`（源 talker，来自 k6 的 msg/h9 链）vs 我方=`th_<md5>`（缩略图名）⇐ q1.b 填值不同。
- `str[7]` 原生=`wxid_4krmxebor2gm22:` vs 我方="" ⇐ **我方根本没写 q1.r**。
- `ori` 原生=`<md5>.jpg` vs 我方=`th_<md5>` ⇐ `q1.j` 填值不同。

**为什么 `m.a` 相同还不够**：j2 在 `m.a>0` 拿到 ImgInfo2(r0) 后，仍会用 `m.b`（经 `u3(m.b)`→h9.imgPath，`2af1cc[0x0156]`/`2afc44[0x017c]`）与 q1.j 参与"上传哪张图/拼 ot5"的下游判定（`2af1cc-2af208`、`doScene 0x0108-0x0116`）。我方 m.b 在 `FileSender` 里是 **0**、q1.r 空、q1.b/q1.j 是缩略图 ⇒ 下游回到本地缩略图。原生 m.b=源 msgId、q1.r=转发源前缀、q1.j=大图路径 ⇒ 下游命中服务端引用/秒传。**m.a 只是入口必要条件；m.b + q1.r + q1.j 这组"转发上下文"才是第二判别标准。**

⚠️ **诚实边界（UNKNOWN）**：`m.b`/`q1.r` 影响 ot5 拼装与 `z3` 选路的下游控制流极长（j2 构造器 1192 code units + doScene），本 lane 已定位到"k6 填了、我方没填"这一确定差异，并用它逐项解释了 str[1]/str[7]/ori 三个逐参 dump；但**"具体是哪一条 if 因 m.b/q1.r 取本地缩略图"未逐指令追到唯一分支**（受时间/篇幅限制，剩余分支在 `2af208`-`2af6d0` 与 doScene `0x027c`-`0x05d5`）。因此给出的是"按原生 k6 逐字段补齐"的修法（不依赖这条未追完的分支），而非赌单一字段。

## 五、唯一可实施修法 + 真机判据

### 修法（唯一实施方向：按原生 k6 `0x62d368` 字节锚点，把我方自建的 q1/m 补全成原生同构）

**不改 j2/k2/o1 分派（候选 A 排除）、不改 hook 点、不重建 CDN 下载链**；只把"我方写 q1/m 的字段集"从"照抄 `qf4/o4.O8` 普通发图默认值"升级为"照抄 `MsgRetransmitUI.k6` 转发填值"。**保留已上线的 `m.a=883190` 修复不动（向前兼容，不倒退）。**

| # | 文件 | 改动 | 值来源（全部只读宿主 / 已有 snap） |
|---|---|---|---|
| 1 | `core/FileSender.kt` `send()` | 新增参数 `forwardCtx: ForwardCtx`（msgId/talker/svrId/snsObjId/srcPath/srcTalker），`newInstance(mCls)` 后除 `a` 外再补：`m.b=msgId`、`m.c=snsObjId`、`m.d=uuid/sns`、`m.e=svrId`、`m.f=srcTalker`；`q1` 侧补 `setObjectField(q1,"r",srcTalker+":")`、`q1.j=srcPath`（大图路径） | 与 ForceForward 同源，从源消息 h9 + ImgInfo2 只读取 |
| 2 | `feature/ForceForward.kt` `nativeForwardImage`/`attachForwardInfo` | `attachForwardInfo` 现只写 `a/b/c/d/e/f` 6 字段（`2124-2129`），其中 `b=fi.msgId` 已对；补齐 `q1.r=转发源前缀`=`fi.talker+":"`（或源 talker）、`q1.j` 用**源图大图路径**而非 `snap.localPath`（缩略图） | `fi=forwardInfoOf(snap)`；大图路径从源 ImgInfo2 的 `r0.e/j`（`z3` 解析）取，与原生 `MsgRetransmitUI.g` 同源 |
| 3 | `feature/ForwardLogic.kt` `Snapshot` | 增 `srcImgPath`/`srcTalker`/`snsObjId` 字段（`snapshotOf` 里从源 h9/ImgInfo2 只读填），与已加的 `imgLocalId` 并列 | 只读 |
| 4 | `probe/ImageNetSceneProbe.kt` | 现有 `str[1]/str[7]/ori/m.a` 日志已够，无需改；真机判据直接用这些日志 | — |
| 5 | 单测 | 增"q1/m 字段集与 k6 同构"断言（`q1.r` 非空且带冒号、`m.b>0`、`q1.j` 指向大图名而非 `th_` 前缀） | — |

⛔ 不改 `q1.h`（仍 13 / SUBTYPE_IMAGE_FORWARD，两条分支都进同一 19 参 j2 构造器，已证）、不改 o1/r1 分派、不重建 k2/ot5。**全部字段值必须来自已验证的宿主只读取值（沿用 `imgLocalIdOf` 的 `h9`→`d3.O8().Q2/a3`→`r0` 范式），严禁造宿主未验证对象。**

### 实施风险

- **不会让 m.a 修复倒退**：`m.a=883190` 赋值原样保留，本次只**追加**字段；取不到值时各字段回退到当前默认（q1.r=""/m.b=0），等同现状。单调性成立。
- **多目标**：`q1/m` 按目标逐个新建（`resendFile` 每目标独立 `send()`），源上下文字段对所有目标相同（源消息属性），无跨目标串味。
- **最大风险**：`q1.j` 改指大图路径，但坏图本地**无大图文件**（36B 占位）。若 j2 在该分支仍 `o6.l(q1.j)` 读本地文件，可能读 36B 失败。⇒ 必须与真机判据 P3 联动：若改后 `onGYNetEnd` 非 0/0 或 P2 仍模糊，说明该图必须走 CDN 下载（候选 C 的兜底），此时 q1.j 应指 SERVERID 引用而非本地路径，需再开一轮取证。**不承诺一次成功，按判据闭环。**

### 真机判据（改后怎么看）

- `q1.B=` 探针**恒 NA，禁止当判据**（j2 构造器不可达 q1，已两次确认）；`m.a` 两者同值 883190，**也不足以判清晰度**。
- **P1 机制看日志**：INP 开 → 我方转发 → 每个 j2 `INP j2init` 应满足：`str[1]=wxid_…`（源 talker，非 `th_` 前缀）、`str[7]=wxid_…:`（**非空带冒号**）、`ori=…/<md5>.jpg`（**非 `th_` 前缀**）、`m.a=883190`、`q1.B=NA(忽略)`。**三项 str 与原生 baseline 对齐**即为字段补齐成功。
- **P2 主判据（人眼）**：坏图多目标 → 对端**清晰大图**（保存字节 ≫15KB）+ 2+ 目标都收到。仅 `ok=true`/`onGYNetEnd 0/0` 不算（历史假阳性）。
- **P3 反向对照**：若 P1 全对齐但 P2 仍模糊 ⇒ 证明该图本地无大图、j2 该分支读本地文件，必须转 CDN 下载方案（入口：`b1.R5`（`j6 [0x0037]`，code `0x62d176` 调用）拉大图 / `b1.z3` 的 `SERVERID://`→`Y2(msgSvrId)` 引用；多目标下载需确认 `R5` 对多目标的兼容性——**UNKNOWN，另开取证**）。
- **P4 不倒退**：单目标普通本地大图转发仍清晰；`m.a` 取不到（纯本地图）时行为与修复前一致、无红叹号/ANR。

## 六、总体裁决

- **候选 A（q1.B/k2 分派）：排除** —— 两条链都构造 j2（o1.e=IMAGE），k2 秒传本次未发生。
- **候选 B（String 入参）：是差异表征，非充分判别** —— str[1]/str[7]/ori 差异来自 q1 填值不同，但 j2 读字节走 ImgInfo2（m.a 查询），String 字段单独改不足以变清晰。
- **候选 C（读 ori）：排除读 ori** —— j2 读 `o2(m.a)→ImgInfo2.e/j/g/f`，非 ori；但暴露"本地无大图"决定了必须有服务端引用/下载。
- **第二判别标准 = 原生 k6 在 q1/m 里填入而我方缺失的一组"转发上下文字段"，核心是 `m.b`(源 msgId)、`q1.r`(转发源前缀，`str[7]`)、`q1.j`(大图路径，`str[3]`/`ori`)**。`m.a` 是必要非充分；这组字段决定 j2 下游是命中服务端引用还是回退本地缩略图。
- **唯一修法**：按 `MsgRetransmitUI.k6`(0x62d368) 字节锚点，把我方 q1/m 字段集补全成原生同构（追加、不改 m.a、不改分派）。**可实施**（全部只读取值 + 现成 dex 锚点照抄，不造未验证对象）；**不会倒退 m.a 修复**（单调追加）；**风险**集中在坏图本地无大图——P3 不成立时需转 CDN 下载（另开取证）。
- **置信度**：差异定位（k6 填、我方缺）与 str[1]/str[7]/ori 解释 = 高（逐字节 + 逐参 dump 双向印证）；"具体哪条下游 if 因这些字段选缩略图" = UNKNOWN（控制流未追完，但修法不依赖它）。

`[DONE]` R73 m.a 之外第二判别标准只读根因裁决完成。全过程中止于 dex 字节 + 真机日志 + 源码三方交叉，未碰源码/adb/gradle/commit。产物 = 本文档。

---

## 七、补充验证：CDN check 副作用假设

- 触发：主会话交叉复核指出本裁决未排除一个更简单的解释——微信自带在构造 j2 前先做 `field_onlycheckexist=true` 的 CDN 存在性检查，若该 check **更新了 ImgInfo2 记录本身**（把 `r0.e/j/g/f` 从本地缩略图路径改成 SERVERID/CDN 凭证），则两条链 `m.a` 虽同、原生 `o2(m.a)` 返回"被更新过的记录"、我方返回"旧记录"⇒ 结果不同。本节只读取证排除/坐实此假设。
- 纪律不变：全程只读；证据优先级 dex 字节（code_off + 原文）> 源码 > 日志 > 结论转述；主用 `_cache/_r11_dex7_dis.txt`，`tools/_r27_disasm.py` 只取 code_off/proto。
- 不推翻已确立事实：`m.a` 同值 883190、对端仍模糊、str 三项差异、k2 秒传本次未发生（候选 A）。

### Q1 — `field_onlycheckexist=true` 的 check 调哪个方法？是只读还是写回 ImgInfo2？

**调用点（原生 B6，`0x62c98c`，字节自证）**：

| 步骤 | code_off | 原文字节 | 判定 |
|---|---|---|---|
| 置 check 标志 | `62cd18 [0x01be]` | `5c40 82c6  iput-boolean v0, Ltj/k;.field_onlycheckexist:Z // field@c682`（v0=1，来自 `62cd16 const/4 v0,#1`） | `onlycheckexist=true` 确认 |
| 置 safecdn | `62cd1c [0x01c0]` | `5c40 8fc6  iput-boolean v0, Ltj/k;.field_trysafecdn:Z` | 同对象 tj/k |
| 取服务单例 | `62cd20-62cd30 [0x01c2-0x01ca]` | `const-class v1, Lnq/e0;` → `d94/n0.c(v1)` → `check-cast v1, Lmq/k;` | check 服务是 `nq/e0`，cast 成 `mq/k` |
| **发起 check** | `62cd34 [0x01cc]` | `6e20 53be 4100  invoke-virtual {v1, v4}, Lmq/k;.B8:(Ltj/k;)Z // method@be53` | **调用 = `mq/k.B8(tj/k)`**（不是 `j9.*`、也不是 `b1.*`；`j9.u3`/`b1.Q2|a3` 只在 `62c9c4`/`62ca30`/`62ca8a` 用来**取 r0**，不是 check 本身） |
| 提交失败兜底 | `62cd40 [0x01d2]` | `const-string "check exist fail! download img before retransmit"` → `62cd74 [0x01ec] invoke j6(...)` | B8 返回 false ⇒ 下载兜底 j6 |

**check 方法体本身只读（【字节确定】）**：
- `mq/k.B8:(Ltj/k;)Z` code=`0x78f618`，全文 6 条指令：`invoke-static {} Lok0/s2;.e9:()Lok0/m1;` → `invoke-virtual {v0,v2}, Lok0/m1;.e:(Ltj/k;)Z` → `return v2`。
- `ok0/m1.e:(Ltj/k;)Z` code=`0x7b52b4`：打日志 `"summersafecdn addSendTask ..."`，校验 mediaId/fileId/aesKey，仅对 **tj/k 任务对象自身** 写 `iput-object field_fileId/aesKey`、`iput-boolean Ltj/k;.e`（`7b531a`），再 `hn4/t0.h(new ok0/h1(this,tj/k))` 投线程池、`return 1`。**全程无 `b1.o2/u8`、无 `r0.*` 写、无 SQLite UPDATE** ⇒ 同步 check 调用对 ImgInfo2 **只读**；它只是投递一个异步 CDN 任务。

**但异步回调 `qf4/j0.C2`（code=`0x84307c`）确实写回 ImgInfo2（【字节确定】，假设的"写回"部分成立）**：
- `843098 [0x0006] iget-boolean v3, v1, Ltj/f;.field_exist_whencheck:Z`；`8430a2 [0x000b] if-eqz v3, 0361`：
  - **命中（support second）** → `8430a6 [0x000d] "support second !! sceneResult:[%s]"`，随后：`8433de [0x01a9] b1.o2(Long)→r0(v14)`；`8433ee [0x01b1] r0.o(J)`；`8433f4 [0x01b4] r0.i(String)`；`843480 [0x01fa] r0.j(1)`；`843488 [0x01fe] r0.k(int)`；`8434fc [0x0238]` 与 `843582 [0x027b]` **两次 `b1.u8(Long,r0)`**（SQLite UPDATE image 表）。
  - **未命中** → `843750 [0x0362] "not support second !! dealDownLoadImg"` → `84378e [0x0381] invoke MsgRetransmitUI.j6(...)`；j6(`0x62d0f8`) 内 `62d176 [0x0037] b1.R5(...)`（建记录）、`62d1a4 [0x004e] b1.o2`、`62d1ae [0x0053] r0.m(I)`、`62d2ba [0x00d9] r0.o(J)`、`62d2e6 [0x00ef] b1.u8(...)` —— 也写回。

**决定性反证：写回的字段全不是 j2/`z3` 读取的 `r0.e/j/g/f`（【字节确定】）**。逐 setter 核对其真实写入字段：
- `r0.i:(String)` `2b3e24 [0x000b] iput-object v2, r0;.u:String`（cdninfo）+ 脏标记 J；
- `r0.j:(I)` `2b3e6c [0x0000] iput v1, r0;.h:I` + 脏标记 N；注意这是**方法 `j(I)`**，与 `z3` 读的**字段 `r0.j:String`**（field@143e）同名不同命名空间；
- `r0.k:(I)` `2b3e88 [0x0000] iput v1, r0;.r:I` + 脏标记 H；
- `r0.o:(J)` `2b3f00 [0x0000] iput-wide v1, r0;.o:J` + 脏标记 E；
- `r0.m:(I)` `2b3ece [0x0007] iput v2, r0;.v:I` + 脏标记 L；
- 真正写路径字段的是 `r0.l:(String)→r0.j:String`（`2b3ea4`）与 `r0.n:(String)→r0.f:String`（`2b3ee4`）——**C2 与 j6 均未调用 l()/n()。**
- `b1.u8`(`0x2a5398`) 只做 `r0.c()`→ContentValues→`z84/z.c("table", values, "id=?", ...)` SQLite UPDATE，按脏标记持久化，**不重算/不改写 e/j/g/f**。

**Q1 结论**：check 调用 = `mq/k.B8`→`ok0/m1.e`（纯异步任务投递，同步只读）；回调 C2/j6 的写回只动 cdninfo/长度/标志等**元数据字段**，**从不触碰 `r0.e/j/g/f` 路径字段**。`z3` 的 `SERVERID://→Y2`（`2a6ae4 [0x000a] const-string "SERVERID://"`，`2a6b30 [0x001e] b1.Y2(J)`，`2a6b38 [0x0022] iget-object r0.e`）消费的是记录**自带**的 SERVERID 引用，不是 check 写进去的。⇒ **主会话的"check 更新记录路径字段导致 o2(m.a) 返回不同记录"假设，byte-level 排除。** （置信：高——调用点、setter 写入字段、u8 语义均有 code_off 原文。）

### Q2 — 我方 FileSender 是否完全跳过该 check？`q1.p=true` 会不会触发同类 check？

**完全跳过（【字节确定】）**。我方链路 `r1.a(o1.e=IMAGE)`→逐字段填 q1→`q1.a()`(code=`0x712168`)→按 `q1.B` 分派。我方 `r1.a(o1.e)` 造的是 `hl0/q1` 基类（非 `modelimage/g`），`q1.B≠CDN_IMAGE` ⇒ 不进 `t81/g.k`，而进 **`t81/g.a`(code=`0x8bb740`)→j2**。对 `t81/g.a` 全文（`8bb740`–`8bbc8c`）逐行扫：`B8` / `Ltj/k;` / `Lmq/k;` / `Lnq/e0;` / `onlycheckexist` / `b1.` / `NetScene` / `cdn` **零命中**——每个 `q1.h` 分支都只是 `iget` q1 字段 → `new-instance j2` → `invoke-direct j2.<init>(...)` → `j2.W()` → `goto 019b`。⇒ 我方这条链**没有任何 CDN check 调用**。

**`q1.p=true`（FileSender.kt:166）不触发 check，只当 j2 构造参数（【字节确定】）**。h==13 分支原文：
- `8bba04 [0x015a] const/16 v3, #int 13 // #d`；`8bba08 [0x015c] if-ne v2, v3, 01a0`；
- `8bba3c [0x0176] iget-boolean v13, v0, Lhl0/q1;.p:Z // field@8cf3`；
- `8bba72 [0x0191] invoke-direct/range {v3..v21}, Lmodelimage/j2;.<init>:(I...,ZIZIFFLjava/lang/String;Lhl0/m;Ljava/lang/String;)V // method@1e15` —— v13（q1.p）只是构造器第 10 个 `Z` 入参；整个 h==13 分支无 invoke 到任何 check/下载方法。
- 同理 `q1.p` 在 h==9/10/11/14/19 分支也只作 `iget-boolean`→构造器布尔参数（8bb9d6/8bba3c/8bbace/8bbb70），从不决策 check。

**Q2 结论**：我方完全跳过 B6/B8/C2/j6 的 CDN check；`q1.p=true` 仅是 j2 构造器布尔参数，不会触发同类 check。（置信：高。）

### Q3 — 唯一推荐：X 还是 Y？

**唯一推荐：X（字段补齐 m.b/q1.r/q1.j 是更可能的根因）；Y 仅作为 X 失败后的 P3 兜底，不是本次根因。**

判据链：
1. Q1 已 byte-level 排除"check 更新 `r0.e/j/g/f`"这一 **Y 的核心机制**：两条链 `o2(m.a=883190)` 取回的 ImgInfo2 记录**相同**，其 `e/j/g/f`（含 SERVERID 引用）也相同——"不同记录"解释不成立。既然记录相同、清晰度却不同，差异只能落在**同一条记录如何被 j2 消费**的下游控制流，而这正是本裁决已定位的"k6 填、我方缺"的 `m.b/q1.r/q1.j`（见 §四唯一根因/§六）。
2. check 命中分支 C2 构造的是 `modelimage/g`→k2（`8436b0 [0x0312] new-instance modelimage/g`；`8436be [0x0319] q1.B=...`；`84371e` 等），**不构造 j2**；真机两条链都触发 j2init（候选 A 已证），说明这次原生清晰度的贡献者不是 check/k2 这条支线。给我方"补一个 CDN check"既不会复现同一条 j2，也不会改变 `o2(m.a)` 返回值。
3. 因此"m.a 同值却一清一糊"的最简且未被排除的解释仍是 X —— 与本裁决 §五修法、§六裁决一致，不另起炉灶。

**X 的兜底 = Y，且就是原 P3（明确回答 team-lead 的追问）**：若 P1 三项 str 已与原生 baseline 对齐、`m.a` 同值，但 P2 仍模糊，则说明该图本地无大图、j2 在 `z3(r0.e/j/g/f)` 解析出的路径上读不到字节，此时才转 Y——即原 P3 的 CDN 下载/服务端引用入口：`b1.R5`（j6 内 `62d176 [0x0037]`，建/取本地 image 记录）、`b1.z3` 的 `SERVERID://`→`Y2(msgSvrId)`（`2a6ae4`→`2a6b30`→`2a6b38`）。这与 §五 P3 完全一致，无需新开入口；多目标 `R5` 兼容性仍为 UNKNOWN（原文档已标注，另开取证）。

**置信边界（诚实）**：
- 高：Q1 check 调用点/只读性/写回字段范围、Q2 完全跳过与 q1.p 用途、"check 改路径字段"假设的排除——均有 code_off + 字节原文。
- 中："记录相同 ⇒ 差异在下游消费 ⇒ X 更可能"是基于上述字节事实的推理（与 §四 str 三项 dump 双向印证），非单一 if 指令直证。
- UNKNOWN（沿用，不新增）：`m.b/q1.r` 具体在哪条下游 if 决定本地缩略图（§四已标注，控制流未追完）；真机原生那次 j2 究竟来自 k6 直连还是 j6 下载兜底（check 命中应走 k2 与"见到 j2init"未完全调和，需真机 hook C2 的 `field_exist_whencheck` 才能定，本 lane 不碰设备）。

`[DONE2]` R73 补充验证（CDN check 副作用假设）完成：全程只读，唯一写操作=本文档 §七；未改源码/测试/探针，未碰 adb/gradle/git。

---

## 八、第三轮：字段补齐版真机反证后的最终裁决

> 证据基线：全程只读，主证据源 `_cache/_r11_dex7_dis.txt`，一律 `code_off + 字节原文`。证据优先级 dex 字节 > 源码行 > 真机日志 > 结论转述。不推翻已确立事实：`q1.r` 已对齐仍模糊、`str[1]` 仍缩略图、`m.a=883190` 同值、check/C2/j6 不写 `r0.e/j/g/f`、本图本地无有效大图（36B 占位 + 15KB `th_` 缩略图）。

### 8.1 逐字节回答 Q1：`q1.b`（`str[1]`）的真实语义

**来源链（dex 直证）**
- `MsgRetransmitUI.k6` 入口 `62d368`，签名 `(String,int,String,String,t0,J)V`；`62d384: move-object/from16 v3, v25` ⇒ `v3` = 第 4 参 String。
- `62d4ba: sget-object v10, Lhl0/o1;.e` → `62d4ba(0x00a1): invoke-static {v10}, Lhl0/r1;.a:(Lhl0/o1;)Lhl0/q1;` 取 q1 实例。
- `62d4c2: iput v2, v10, Lhl0/q1;.i:I`（写 q1.i）。
- **`62d4c6: 6e20 89a8 3a00 invoke-virtual {v10, v3}, Lhl0/q1;.g:(Ljava/lang/String;)Lhl0/q1;`** ⇒ `q1.g(v3)`。
- `hl0.q1.g`（`7122e8` 起）内 **`712300: 5b01 e58c iput-object v1, v0, Lhl0/q1;.b:Ljava/lang/String;`** ⇒ 写入 `q1.b`。
- 真机对照（证据等级低于 dex）：微信自带 `str[1]=wxid_myzsbcur81xr21`，即**源 talker**；我方补字段版 `str[1]=th_216b...`（缩略图名）——这是 X 修法把 `f.name` 填进 q1.b 的直接后果，值本身语义就是"来源标识字符串"。

**消费链（dex 直证）—— 只进上传请求元数据，不进读图分支**
- j2 主构造器 `0x2aef10`：q1.b 经 `v34`（构造器 `args[1]`）→ **`2af6ae: move-object/from16 v6, v34` → `2af6b2: iput-object v6, v5, Lq54/kx4;.d:Ljava/lang/String;` → `2af6ba: iput-object v5, v4, Lq54/ot5;.e:Lq54/kx4;`**。即 q1.b → `ot5.e.d`，是 NetSceneUploadMsgImg 请求 protobuf 的**来源/source 元数据字段**。
- 反向验证"不读图"：j2 真正选图字节的两处入口——构造器 `2af158~2af19a`（`m.a>0 → b1.o2(m.a) → z3(r0.j)`）与 doScene `2adece/2adee2/2adf06`（`z3(r0.e/g/f)`）以及 `j2.O` 内 `2acd38/2acd50/2acd64/2acd78`（`z3(r0.e/f/g/j)`）——**均无 q1.b / `ot5.e.d` 参与**。q1.b 决定的是"上传请求里 source 写什么"，不是"从本地哪个文件读字节"、也不是"走不走 CDN"。

**Q1 结论**：`q1.b` 是上传请求来源元数据（写 `ot5.e.d`），不参与本地读图分支，也不决定 CDN 取舍。因此**补 q1.b 无法把缩略图换成大图**——它与 `q1.r`（→ `j2.B` 仅 `msg.N0()` 来源展示 + `modelimage.a.c` 解析 appinfo）同属"来源展示/请求元数据"一类，上轮把它当第二判别标准不足以解释清晰度，本轮字节予以确认。

### 8.2 逐字节回答 Q2：同一 `o2(883190)` 记录为何一清一糊（解开核心 UNKNOWN）

上轮"check 不改 `r0.e/j/g/f`、两条链 `o2(883190)` 返回同一记录"的判断**仍然正确，但过窄**：遗漏了 check/下载链对同记录的 **`r0.u`（cdninfo）** 写入和**大图下载副作用**。逐段还原：

**(a) B6 的分派（原生链独有）**
- `62cd16: const/4 v0,#int 1` → `62cd18: iput-boolean v0, v4, Ltj/k;.field_onlycheckexist:Z`；`62cd1c: .field_trysafecdn:Z = v0`。
- `62cd34: invoke-virtual {v1, v4}, Lmq/k;.B8:(Ltj/k;)Z`（原生 CDN check）。
- `62cd3c: if-nez v1, 01db`；失败分支 `62cd40: "check exist fail! download img before retransmit"`。
- 结果二择一：`62cd74: invoke-virtual/range {...}, MsgRetransmitUI.j6(...)`（下载兜底）或 `62cdaa/62cdd4: MsgRetransmitUI.k6(...)`（直接转发）。**我方模块链 FileSender 自组 q1 直接进 j2，完全跳过 B6→B8→C2→j6 这一整段。**

**(b) check 内部 C2 对同记录写 cdninfo / 下载大图（dex 直证）**
- `843362: b1.j6(String,[B,I,J,Z,PString,PInt,PInt)J → v11(wide)`（C2 内 modelimage 层下载，返回 imgLocalId）。
- `8433d6: Long.valueOf(v11,v12)` → **`8433de: b1.o2(Long) → r0`**（按该 id 取记录）。
- `8433ee: r0.o(J)`；**`8433f4: r0.i(String v10)`** ⇒ `r0.i` 在 `2b3e24` 处 `iput-object ..., Lr0;.u:Ljava/lang/String;`，即**写 cdninfo 到 `r0.u`**。
- 即：原生 check 命中时，C2 已对记录做过 `b1.j6 下载大图 + r0.i 写 cdninfo` 的副作用。

**(c) j6 下载兜底（dex 直证）**
- `62d154: d3.O8() → b1` → **`62d176: b1.R5(String,String,IIILPString;PInt;PInt;)J`**（建/取本地 image 记录、下载大图）→ `62d1a4: b1.o2(Long) → r0` → `62d1ae: r0.m(1)` → 构造 NetSceneUploadMsgImg 上传。

**(d) j2 消费同记录时的分叉点（dex 直证，`j2.O` 起于 `2acc44`）**
- **`2accc0: iget-object v4, v1, Lr0;.u:Ljava/lang/String;`** → `2accc4: a8.F0(r0.u)`（判空）→ `2acccc: if-eqz v4, 0056`。
- 分支：`ok0/m1.n(1)`（`2accb4`）为 0 且 `r0.u` 为空时 → `2accd0/2accfe: "cdntra not use cdn flag:%b getCdnInfo:%s"` → `return v6`，**j2.O 提前返回，不构造 CDN 上传任务**。
- 反之 `r0.u` 有值 → 落到 `2acd38~2acd84: z3(r0.e/f/g/j)` + 构造 `tj/k "task_NetSceneUploadMsgImg"`，走 CDN 上传（用 cdninfo 指向的原始大图字节）。

**Q2 裁决（自洽解开矛盾）**：`o2(883190)` 的**记录 id 确实相同**，但两条链消费的**记录状态不同**——原生链经过 check/C2（`843362 b1.j6 下载大图` + `8433f4 r0.i 写 cdninfo`）与 j6（`62d176 b1.R5` 下载兜底），使该记录的 `r0.u` 携带有效 cdninfo、且本地已有大图字节；我方模块链跳过整段原生 CDN 链，`r0.u` 为空、本地仅缩略图。于是 `j2.O` 在 `2accc0~2acccfe` 对同一 id 选了不同分支：原生走 CDN 原始大图（清晰），我方走本地直传缩略图（模糊）。**一清一糊的根因是"是否经过原生 CDN check/下载副作用"，不是 q1.b/q1.r/m.b 字段值，也不是 o2 返回了不同记录。** 这闭合了上轮遗留的核心 UNKNOWN。

### 8.3 唯一最终推荐：(II) 转 P3 —— X 修法到此为止

**二选一裁决：选 (II) 转 P3，不补 q1.b。** 三条字节级理由：
1. Q1 已证 q1.b 只写 `ot5.e.d`，不进 `z3(r0.e/j/g/f)` / `j2.O` 读图分支——补它无法改变读图来源。
2. Q2 已证清晰度差异来自 CDN check/下载副作用（`r0.u` cdninfo + 大图），字段补齐注入不了 cdninfo，也造不出本地大图。
3. 该图本地无有效大图（36B 占位 + 15KB 缩略图），q1.j no-op 已由真机证实；即便把 str[1] 改成 talker 名，j2 仍只能从 `z3(r0.e/j/g/f)` 读到缩略图。

**实施入口（唯一，与原生链同源）**
- `b1.R5`（`62d176 [0x0037]`，建/取记录+下载大图）——即原生 j6 用的同一个下载入口；
- `b1.z3` 的 `SERVERID:// → b1.Y2(msgSvrId)`：`2a6ae4` 方法入口 → `2a6b30: invoke-virtual {v11,v2,v3}, Lb1;.Y2:(J)Lr0;` → `2a6b38: iget-object v0, v0, Lr0;.e`——服务端引用解析回本地大图路径。二者与 §五 P3 完全一致，无需新开入口。

**多目标兼容性**：`b1.R5` 入参含 talker(String)/`PString`/`PInt` 输出指针，无目标硬编码，按目标逐个调用天然兼容；`z3 SERVERID://→Y2` 按 msgSvrId 查记录，与目标 talker 无关。UNKNOWN（沿用，另开取证）：多目标并发时 `R5` 的 PString 输出隔离需真机验证，本 lane 不碰设备。

**是否撤回本轮已落盘的 X 改动**：**保留，不撤回**。字段补齐单调不倒退——真机已证 `q1.r/m.b` 生效（`str[7]` 从空→带冒号对齐）、`q1.j` 在坏图上 no-op（不写坏路径），未引入回归；P3 在同一转发链路叠加即可，无需回滚。

**是否值得 discriminating probe**：**不值得**。Q1/Q2 已由 code_off + 字节原文闭环，补 q1.b 的无效性与 CDN 副作用根因均为 dex 直证；再挂 hook（如 `C2` 的 `field_exist_whencheck` / `m1.n(1)` 真机值）只能细化分支细节、不改变"转 P3"的裁决，多花一轮装机无收益。

### 8.4 置信边界
- **高**：Q1 来源/消费链、Q2 的 B8 分派 + C2 写 cdninfo + j6 R5 下载 + j2.O 读 `r0.u` 分支——均有 code_off + 字节原文。
- **中**："两条链 o2 同 id 但记录状态不同 ⇒ 一清一糊"是基于上述字节的因果拼接（与真机 str 三项 dump 双向印证），非单条指令直证。
- **UNKNOWN（沿用/新增，均不影响裁决）**：`ok0/m1.n(1)` 与真机原生那次 j2 究竟来自 C2 直连还是 j6 兜底（需 hook 才能定）；多目标并发 `R5` 输出隔离。本 lane 不碰设备。

`[DONE3]` R73 第三轮只读根因裁决完成：逐字节回答 q1.b 语义（源 talker→`ot5.e.d` 元数据，非读图分支）、自洽解开同一记录一清一糊（原生 CDN check/下载副作用写 `r0.u` cdninfo，我方跳过），唯一推荐转 P3（X 到此为止、不撤回已落盘字段补齐、无需 probe）。全程只读，唯一写操作=本文档 §八；未改源码/测试/探针，未碰 adb/gradle/git。

---

## 九、第四轮：微X图片转发真机观察后的机制裁决

> 基线（纪律不变）：全程只读；证据优先级 dex 字节（`code_off`+原文）> 源码行 > 真机日志 > 结论转述；主用 `_cache/_r11_dex7_dis.txt`。不推翻真机事实：微X `m.a=-1`、`r0.u=EMPTY`、下载链 `R5/j6/B8/C2` 全零、`ori=cache/_ffb490d4…`（临时文件）、用户肉眼清晰且非缓存、本图本地无有效大图（36B 占位+15KB `th_`）。
> 本轮要解的核心：第三轮称「j2 读图字节必走 `o2(m.a)→z3(r0.e/j/g/f)`，m.a 是源图 id」。微X `m.a=-1`/`r0.u` 空却清晰 ⇒ 要么存在我从构造器 String 入参直读本地文件的分支，要么第三轮判据有误。逐字节裁决如下。

### 9.1 Q1：`m.a<=0` / `r0.u` 空时 j2 是否从构造器 String 入参直读本地文件？— **没有，byte-level 排除**

**（a）`ori`/`str[3]` 的字节身份确认**
- 构造器 `j2.<init>` `0x2aef10` 19 参映射：`v34=arg1(str[1])`、`v35=arg2(str[2])`、`v36=arg3(str[3])`、`v38=arg5(t0)`、`v49=arg16(m)`。
- **`2af0fa [0x00ed] 0805 2400 move-object/from16 v5, v36` → `2af0fe [0x00ef] 5b15 bc13 iput-object v5, v1, j2.L // field@13bc`** ⇒ 探针 `ori/str[3]`（arg3）唯一落点 = `j2.L`。
- 构造器日志原文 `2af0b2 [0x00c9]` `"dkupimg init uploadsrc:%d from:%s to:%s ori:%s …"`，`ori` 即 arg3，与探针启发式挑选一致。

**（b）m.a 分支是互斥二选一，m.a<=0 走「不挂 r0 / 不解析路径」支**
- `2af154 [0x011a] 3800 2600 if-eqz v0(v49=m), 0140`；`2af158 530d c38c iget-wide v13, m.a`；`2af15c 310d 0d1b cmp-long v13,v13,0`；**`2af160 [0x0120] 3d0d 2000 if-lez v13, 0140`**。
- `m.a>0` 支（0144 前）：`2af164→2af178 b1.o2(m.a)→r0(v13)`；`2af188 54df 3e14 iget-object v15, r0.j // field@143e`；`2af192 6e5b bf1d … b1.z3(v15=r0.j,"","",1)→v14`；`2af19a 5bcd 1a84 iput-object v13, v12(fv/t0), t0.o // field@841a`。
- **`m.a<=0` 支（`2af1a0 [0x0140]`）：`move-object/from16 v4,v29; const/4 v11,#1; const/4 v14,#0`（`2af1a6`）——v14=0，既不调用 `o2(m.a)`，也不挂 `t0.o`，路径解析整段被跳过。**
- ⇒ 两条分支**互斥**（if-lez 二选一），无「先后」；m.a<=0 时 `t0.o=null`、`t0.c(=v14)=0`。

**（c）构造器 String 入参的两条去向都不读图字节**
- arg3→`j2.L`：doScene `2adbbc [0x0108] 540f bc13 iget-object v15, j2.L` → `2adbc4 7110 5789 invoke-static {v15}, vfs.o6.q` → `2adbd0 6e20 a3c8 po0/x0.a` → `2adbd8 5b03 bf13 iput-object v3, j2.P // field@13bf`；`j2.P` 后续 `2adc2e[0x0141] h9.I2(v3)` / `2adea0[0x027a] ot5.o` 均为**消息 source/展示元数据**，不是 `o6.l()` 读文件长度。
- arg3→`fv/t0.b`：`2af216 [0x011b] 5bc5 0d84 iput-object v5(=v36=arg3), t0.b // field@840d`；L5 内 `t0.b` 仅 `2a6f68/2a70fa 5411 0d84 iget-object v1,t0.b` → `xy/a1.z8(v1)`（通知/埋点），**非路径读取**。
- 反证：若 ori(arg3) 被当图源，L5 必对它有 `o6.k()/o6.l()/o6.c()`；但 L5 里被 `o6.k` 判存在的字段是 **`t0.c`**（`2a6ee4 5414 0e84 iget-object v4,t0.c` → `2a6ee8 o6.k(v4)` → `2a6f20 o6.c(源,目标)` 复制），而 `t0.c=v14`，m.a<=0 时 v14=0（见 b）。**ori 不进 `t0.c`，不进任何 `o6.*` 读文件调用。**

**（d）j2 读图字节的唯一入口（与第三轮一致，且更精确）**
- doScene：`2adaa6 … j2.R()→r0`；`2adece[0x0291] z3(r0.e)`、`2adee2[0x029b] z3(r0.g)`、`2adf06[0x02ad] z3(r0.f)` → `2adf0c o6.l(path)` 取字节长度；长度≤0 走 `2adf30 goto 05d5`（错误出口）。
- `j2.R()` `0x2ad864`：`2ad874 iget-object v0,j2.q // field@13d0`；空则 `2ad890 b1.o2(j2.i)→r0` 缓存到 `j2.q`。**r0 来自 `o2(j2.i)`（j2.i=本地图片记录 id，由 L5 插入返回），不是 `o2(m.a)`** —— 这是第三轮未点明的精度修正：构造器把 `o2(m.a)` 结果挂到 `t0.o`，而 doScene 用的是 `o2(j2.i)` 的缓存 `j2.q`。

**Q1 结论**：`j2` 构造器/doScene **不存在**「m.a<=0 / r0.u 空时从 String 入参（ori/str[3]）直读本地文件」的分支。`ori`(arg3) 仅作 source 元数据（`j2.P`/`ot5.o`）与埋点（`t0.b`）；读图字节唯一走 `R()=o2(j2.i)→r0 → z3(r0.e/f/g/j)→o6.l()`。因此「微X 靠把 cache 临时文件路径塞进 j2 的 ori 让 j2 直读清晰图」这一假设 **byte-level 不成立**。真机 ori=cache 临时文件只能说明微X**自己把大图字节写到了该文件**，但该文件不喂给 stock j2 的读图链。

### 9.2 Q2：`cache/_<uuid>` 临时文件的宿主侧产生入口 + 微X可能获取链

**宿主侧存在现成的「Intent STREAM → cache 临时文件 → 转发」形态（dex 直证，但命名不同）**
- `MsgRetransmitUI.C6:(String)V` `0x62cddc`（被 `A6` `0x62c8f8` 的 `62c96e invoke-virtual C6` 调用）：
  - `62ce42 [0x002b] 6e10 b604 invoke-virtual {v3}, Context.getCacheDir:()File` → `62ce4a getAbsolutePath()` → `62ce58 [0x0036] const-string v3, "/share_img_tmp_" // string@1dbe` → `62ce5c StringBuilder.append` 拼成 `getCacheDir()+"/share_img_tmp_"+…`。
  - `A6` `62c920 [0x000c] getExtras()` → `62c928 const-string "android.intent.extra.STREAM"` → `62c92c getParcelableArrayList(STREAM)` → 逐个 `C6` 处理。即宿主原生就有「从 Intent 的 STREAM 列表落到 cache 临时文件再转发」的路径。
- 其它宿主 cache 临时文件/命名原语（均非 `_<uuid>` 形态，`UNKNOWN` 归因）：`getCacheDir()` 13 处、`2e5a6c u1.createTempFile`（appbrand 媒体，非本链）、`218b34 "image_loader_ImageTempFile"`、`.tmp`（`2370e8`/`23df50`）。**dex7 内 `com.tencent.mm.cache` / `UUID.randomUUID` 零命中** ⇒ 宿主侧未见 `_<uuid>` 这种具体命名；微X的 `_ffb490d4-0f98-4698-99c0-57068cb6484a` 是 128-bit UUID 字符串，符合 Java `UUID.randomUUID().toString()` 形态，生成代码在微X加密 dex 内（R72 定案：8 个 `92c50100` 密文容器 + `libwxcoreloader.so::CallMethod` + rolling-XOR），静态不可解。

**微X获取链（黑盒推断 + 字节约束，明确标注置信度）**
- 【字节约束】Q1 已证 ori(arg3) 不被 j2 当文件读；j2 读字节唯一走 `z3(r0.e/f/g)`。所以微X要把大图字节送进 j2，必须让 `r0.e` 指向含大图字节的文件——两条宿主 dex 可见的达成方式：
  1. **落盘 + 写 ImgInfo2 路径字段**：微X用自己的下载通道（非宿主 `R5/j6`，真机全零）把大图字节写进 `cache/_<uuid>`，再用 `r0.l(String)`/`r0.n(String)`（`2b3ea4`→`r0.j`、`2b3ee4`→`r0.f`）把该路径写进 ImgInfo2 记录；j2 的 `z3(r0.e/f)` 即解析到该临时文件。**注意**：这与第三轮"C2/j6 不写 e/j/g/f"不冲突——那是说原生 check 回调不写这些字段；微X若主动调 `r0.l()/n()` 则是另一条写入者。
  2. **直接构造 ot5/fullpath**：微X不经 j2 读图链，自己拼 CDN 上传任务的 fullpath 指向该临时文件（j2.O 仅做 task 投递，`2ace9c field_fullpath=z3(r0.e)`）。
- 【UNKNOWN — 不脑补】微X究竟走 1 还是 2、`_<uuid>` 文件由哪个微X私有方法写、字节是「网络下载的原图」还是「已解码再编码的位图」，因加密 dex 静态不可解 + 本 lane 不碰设备（无 hook `r0.l/n`/C6 写入点的真机日志），**无法定论**。但有一条硬约束成立：**大图字节必然在转发前已存在于某个本地文件（cache 临时文件），j2 的 `z3(r0.e/f)` 必然指向它** —— 否则 doScene `2adf0c o6.l()` 取长度≤0→`2adf30 goto 05d5` 错误出口，对端不可能收到清晰大图。这与 Q1 的 ori=cache 临时文件真机 observation 互相印证（微X确实写了这么个文件，只是它不是经 j2 的 ori 参数被读）。

### 9.3 Q3：唯一可实施修法 + 是否倒退 + 真机判据

**二选一裁决的前置结论**：清晰度唯一决定量 = **`z3(r0.e)`（`r0`=`o2(j2.i)`）解析出的本地文件是否是清晰大图字节**；与 `m.a`、`r0.u`、`q1.*` 字段、ori 参数全部无关（前四者只影响能否走到 `o6.l()` 与用哪条上传通道）。`j2.O` 真机 `r0.u=EMPTY → ret=true` 亦自洽：`2accbc [0x0034] if-nez v4,0056`（`m1.n(1)` 全局 cdn 开关非 0）直接跳过 `r0.u` 判空 early-return；且 `2ad252 [0x02ff]` 之后 `r0.u` 空会写 `"CDNINFO_SEND"` 后 `2ad2a8 const/4 v1,#1; 2ad2aa return v1`。⇒ 第三轮把 `r0.u` 当"清晰判别开关"是**过窄**的：r0.u 空也能走 CDN task 且 ret=true，真正喂给 task 的是 `2ace9c field_fullpath=z3(r0.e)`。

**唯一可实施修法（照抄微X的"终局"——让 r0.e 指向本地大图字节——但字节来源用宿主现成 API，不碰加密 dex）**

| # | 改哪里 | 改什么值 | 值来源（只读宿主 + 已验证 API） |
|---|---|---|---|
| 1 | `core/FileSender.kt` `send()` | 在构造 j2 前、拿到 `j2.i`（本地记录 id）后，确保 `o2(j2.i)` 对应 ImgInfo2 的 `r0.e`（大图路径字段）指向**含大图字节的本地文件**。具体：若本地无大图（坏图场景），先经 `b1.R5`（`2a710c`，签名见观察文档 D1）下载大图落 `image2/21/6b/<md5>.jpg`，再 `r0.l(path)`（`2b3ea4`）写 `r0.e/j`、`r0.n()`（`2b3ee4`）写 `r0.f`，`b1.u8(id,r0)`（`2a5398`）持久化 | 与原生 `j6`（`62d176 b1.R5`）、C2（`8433f4 r0.i`）同源；R5 签名/调用点 dex 已证（`62d176 [0x0037] b1.R5`、`2a710c` 方法体 `2a6ee4 o6.k(t0.c)`→`2a6f20 o6.c(源,目标)` 复制字节到目标路径） |
| 2 | `feature/ForceForward.kt` | 保留已落盘的 `m.a=883190`、`q1.r`、`q1.j` 字段补齐（**不倒退，见下**）；新增一步：转发前用 `b1.C4(uri,true)`（`2a563c`）/`b1.z3(SERVERID://…)`（`2a6ae4`→`2a6b30 Y2(msgSvrId)`）把源图 ImgInfo2 的 SERVERID 引用解析为本地大图路径，作为 R5 的 `s1`（源图路径）入参 | `b1.Q2(talker,svrId)`（`62ca30`）、`a3`（`62ca8a`）取 r0 的范式已在 `MsgRetransmitUI.B6` 字节自证 |
| 3 | `probe/ImageNetSceneProbe.kt`（不动代码，仅看日志） | 真机判据新增：F2 `z3` hook 的 `s0` / `out` —— 需出现 `out=…/image2/…/<md5>.jpg`（大图路径，非 `th_`）；D5 `j2.O` 的 `before z3(r0.e)→o6.l()` 字节长度 ≫15KB | 观察探针已具备 D1/F2/D5，无需改探针 |

**是否倒退现有 m.a/X 改动：不倒退（单调追加）。** `m.a=883190` 赋值、`q1.r`（`str[7]` 带冒号）、`q1.j`、`q1.b` 字段补齐全部**保留原样**；本轮修法在其**之后**追加"大图字节落盘 + 写 r0.e/f"一步。取不到 r0/SERVERID 时各步回退到现状（无大图→仍模糊），等同当前行为，单调性成立。

**真机判据（闭环，参照观察文档 §6/§7）**
- **P-机制（日志）**：D1 `b1.R5 out imgLocalId=…`（下载真发生）或 F2 `z3 out=…/image2/…/<md5>.jpg`（大图路径被解析）；j2 构造 ori 不再是唯一线索——**关键是 `z3(r0.e)` 的 out 必须是大图路径**。
- **P-主判据（人眼，终局）**：坏图多目标 → 对端清晰大图（长按保存字节 ≫15KB、≈源图分辨率）+ 2+ 目标均收到。`ok=true`/`onGYNetEnd 0/0` 不算（历史假阳性）。
- **P-不倒退**：单目标普通本地大图仍清晰；`o2(j2.i)` 取不到记录时无红叹号/ANR。

### 9.4 Q4：P3 方向最终裁决 — **P3 仍必需，但需收窄：保留 P3 做「字节落盘」，放弃第三轮对 P3 的「cdninfo/r0.u 判别」描述**

**二选一：选「P3 仍是必需的（微X路径我方不可复制）」。字节理由如下（不骑墙）**

1. **微X清晰不可复制的根因 = 加密 dex 私有下载通道**：微X `m.a=-1`、下载链 `R5/j6/B8/C2` 全零（真机），说明它**没走宿主 dex 里任何一条公开 CDN 下载 API**，大图字节来自加密 dex（`libwxcoreloader.so::CallMethod`，R72 定案）自有实现。我方模块是宿主 dex 内的 Java 层，**无法调用一个 dex 里不存在、且被 rolling-XOR + native 混淆的方法** ⇒ 微X的"字节来源"这一环我方不可照抄。
2. **但终局可被我方复现，且复现入口正是 P3**：Q1 字节链证明 j2 只认 `z3(r0.e)` 指向的本地文件；本图本地无大图（36B 占位+15KB th_），若我方不做任何下载，`o6.l()` 取长度≤0→错误出口，必模糊。要让 r0.e 指向大图字节，我方dex内**唯一现成入口**就是 P3 的 `b1.R5`（`2a710c`；原生 `j6` `62d176` 用同一入口）+ `b1.z3 SERVERID://→Y2`（`2a6ae4/2a6b30`）+ `b1.Q2/a3`（`62ca30/62ca8a`，B6 取 r0 范式）。这些都在宿主 dex 里、可反射调用、不碰加密 dex。⇒ **P3 是我方拿到大图字节的唯一可实施路径，必须保留**。
3. **第三轮对 P3 的机制描述被真机证伪，须收窄**：第三轮 §8.2/§8.3 把 P3 定义为"触发 check/下载副作用写 `r0.u` cdninfo，让 j2.O 在 `2accc0` 选 CDN 分支"。但真机 `r0.u=EMPTY` 却 ret=true、对端清晰，且 `j2.O` `2accbc`（m1.n(1) 旁路）与 `2ad252→2ad26e` 表明 r0.u 空照样能走 CDN task 并回写。⇒ **P3 的目的不是"拿 cdninfo"，而是"让 r0.e 指向本地大图字节"**；cdninfo 是否写入不影响清晰度。修法按此收窄（9.3 表）。

**置信边界（诚实）**
- **高**：Q1 全部字节链（ori→j2.L/t0.b 非读图、m.a 互斥分支、R()=o2(j2.i)、z3(r0.e/f)→o6.l、j2.O 的 2accbc/2ace9c/2ad252）；Q2 宿主 C6 `getCacheDir()+"/share_img_tmp_"` 与 B6 `Q2/a3`；Q4 的"j2 只认 r0.e 指向文件"+"微X下载通道不可复制"+"我方 P3 入口 dex 可见"。
- **中**：Q2 微X落盘链（写 r0.e 还是自建 task）——受加密 dex + 不碰设备限制，仅字节约束推断，未 hook 真机确认。
- **UNKNOWN（沿用/不影响裁决）**：微X `_<uuid>` 文件的私有生成方法、字节来源（下载原图 vs 解码重编码）；`m1.n(1)` 真机值（影响 2accbc 是否旁路，但不改变 r0.e 决定清晰度的结论）；多目标并发 `R5` 的 PString/PInt 输出隔离。

### 9.5 关键纠错（F0 语义逐字节确认）+ 真机补充物证（cache 临时文件发后已删）

> 本节为 §9.1-9.4 落盘后的两处补强：一条是 F0 语义的字节直证（纠正 §9.1/§9.4 中"2accbc 旁路跳过 r0.u 判空"的因果措辞），一条是 team-lead 补充的真机物证（ori 临时文件发后已删）。**不改 Q1-Q4 裁决结论，只校准依据与收敛 Q3 临时文件策略。**

**（1）F0 语义字节直证（最高优先级，已确认）**
- a8 类不在 classes7；`Lcom/tencent/mm/sdk/platformtools/a8;` 实际在 `_cache/dex/classes8.dex`，其 `F0:(Ljava/lang/String;)Z` 的 `code_off=0x49d544`（Dex 类解析类数据表直证）。
- 方法体 13 code units，逐 word 原文：`0038 000b 106e a012 0000 000a 003c 0003 0328 0012 000f 1012 000f`。解码（opcode 在低字节）：
  - `[0x0000] 0038 if-eqz v0,+0x000b` → 参数 String 为 null → 跳 `[0x000b]`；
  - `[0x0002] 106e/6e10 invoke-virtual {v0}, method@0xa012`（`method@0xa012 = java/lang/String.length()I`，Dex.method_id 直证）→ `[0x0005] 000a move-result v0`；
  - `[0x0006] 003c if-gtz v0,+0x0003` → length>0 → 跳 `[0x0009]`（`0012 const/4 v0,#0; 000f return v0` = **返回 0/false**）；
  - 否则 `[0x0008] 0328 goto +0x0003` → `[0x000b]`（`1012 const/4 v0,#1; 000f return v0` = **返回 1/true**）。
  - ⇒ **F0(s) = isNullOrEmpty**：s==null 或 s.length()==0 → true；非空 → false。
- **因果纠正**：j2.O `2accc4 F0(r0.u)` 后 `2acccc if-eqz v4,0056`：r0.u 空→F0=true(1)→if-eqz 不跳→继续 `2accfe return v6(0)`（early return）；r0.u 非空→F0=false(0)→跳 0056。**所以"r0.u 空 ⇒ early return false"第三轮 §八判据正确，未被证伪。** 我此前 §9.1(d)/§9.3/§9.4 写的"`2accbc if-nez v4,0056` 是旁路跳过判空"因果措辞有误，正确是：**`2accb4 m1.n(1)→v4`；`2accbc if-nez v4,0056` = 全局 CDN 开关 `ok0/m1.n(1)!=0` 时直接短路到 0056（构造 CDN task），根本不走到 r0.u 判空**。即真机 r0.u=EMPTY 却 ret=true 的机制 = **`m1.n(1)!=0`（宿主 CDN 全局开）走了 n(1) 短路分支**，不是 r0.u 判空的结果。此纠错**强化**而非推翻 Q4：清晰度仍只由 `2ace9c field_fullpath=z3(r0.e)` 指向的文件决定，r0.u 既不决定分支（被 n(1) 短路）也不决定字节。

**（2）真机补充物证：ori 临时文件发送后已删除（team-lead 23:11+ 观察）**
- 事实：`ori=/data/user/0/com.tencent.mm/cache/_ffb490d4-0f98-4698-99c0-57068cb6484a` 在转发完成后约 2~3 分钟用 `adb shell su -c 'stat <path>'` 查返回 **No such file or directory**（设备有 root、su 可用；普通 shell stat 为 Permission denied、run-as 因微信非 debuggable 失败）。即该 cache 临时文件**发送完成后已被删除**，"用完即清理"的一次性中转特征。
- 与 §9.2 推断一致并收敛：`_<uuid>` 是 Java UUID 形态，生成在微X加密 dex；链路大概率是"下载/解码大图 → 落 `cache/_<uuid>` 临时文件 → 把该路径写进 ImgInfo2 的 r0.e/f（经 `r0.l/n`）→ 喂 j2.O 用 `z3(r0.e)` 解析为 fullpath → 发送成功后 unlink 清理"。与宿主 `MsgRetransmitUI.C6` 的 `getCacheDir()+"/share_img_tmp_"` 一次性中转形态同类。

**（3）Q3 收敛（我方照抄时的临时文件生命周期策略，据物证收紧）**
- **何时落**：必须在 j2.O 调用 **之前**（doScene `2adece z3(r0.e)`→`2adf0c o6.l()` 取长度时文件须存在且为大图字节）。落点建议宿主现成的 `context.cacheDir` + UUID 名（与微X同形态，避开 image2/ 持久库，不污染相册）。
- **何时删**：发送成功后删——对齐微X物证。宿主 j2 的清理时机在 `onGYNetEnd`（`2b0284`）；我方在确认 `onGYNetEnd errType/errCode=0/0` 且对端收到后 unlink 临时文件。**关键风险**：多目标共享同一临时文件时，任一目标先完成就删会导致其它目标 j2.O 读不到字节 ⇒ **多目标必须各自一份临时文件**（每目标独立 `r0.e` 指向独立 UUID 文件），不可共享。这与 §五"多目标按目标独立 send()"单调性一致，现补上"临时文件也按目标独立 + 各自清理"。
- **不倒退**：临时文件生命周期完全在我方新增的"字节落盘"步内闭环；取不到 r0/SERVERID 时不落临时文件、不删任何宿主文件，等同现状。

`[DONE4]` R73 第四轮（微X图片转发真机观察后机制裁决）完成：逐字节证伪"j2 从 ori 直读本地文件"（arg3→j2.L/t0.b 均为元数据/埋点，读图唯一走 o2(j2.i)→z3(r0.e/f)→o6.l）；逐字节确认 a8.F0=isNullOrEmpty（classes8.dex code_off 0x49d544，String.length 直证），据此校准 r0.u 判空方向（第三轮正确）并以 m1.n(1)!=0 短路解释 r0.u=EMPTY 却 ret=true；确认清晰度唯一决定量 = z3(r0.e) 指向文件是否大图；查得宿主 C6 getCacheDir 临时文件入口 + 真机物证"ori 临时文件发后已删"（收敛 Q3：临时文件按目标各自落/各自清理）；裁决 P3 保留但收窄为"字节落盘"（微X加密 dex 下载通道不可复制，我方 P3 的 R5/z3/Q2 是拿大图字节的唯一 dex 可见入口）。全程只读，唯一写操作=本文档 §九；未改源码/测试/探针，未碰 adb/gradle/git。

