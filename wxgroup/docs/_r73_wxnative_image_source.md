# R73 微信自带「转发」图片：本地无大图时清晰图的来源 只读取证

> lane：`r73-wxnative-image-source`｜**只读取证**：未改 `app/src/main` 任何源码、未跑 adb/装机、未跑我方转发、未跑 gradle、未 git commit。真机步骤只写进本文交主会话执行。
> 目标：判定**微信自带「转发」按钮在"本地缓存没有大图"时，是怎么让对端收到清晰大图的**，从而裁决我方「转发（模块）」多目标转发该如何获得同等能力（不清图、不降级 SKIP）。
> 证据等级：【源码确定】= dex 反汇编/项目源码字面（含 code_off 可复核）｜【真机确定】= 已有真机日志/SQL 实录｜【强推测】= 有完整调用链但缺最后一环实证｜【假设】= 未取证，必附装机验证方法｜【取证缺口】= 材料解不出或未实测。
> 反汇编环境：`_cache/dex/classes7.dex`（微信 8.0.48/2588），工具 `_cache/_r14_walk.py`（线性字节步进，反汇编可信）/ `_cache/_r55_disasm.py` / `tools/_r4_dexref.py`。所有 `code_off` 均为实跑输出。

> ⚠️ **【2026-11-21 真机实验后修正 · 必读】** 主会话补入的真机实验（微信自带转发 md5 `216b1f7ccd...` → INP 探针 **j2init 触发**、ori 指向 36B 的 `<md5>.jpg`、线程 `[GT]ColdPool/#15`、对端仍收到清晰大图）**推翻了本文 Q1"只走 k2、不构造 j2"的机制细节**。重新反汇编确认：原生转发**确实构造 j2**（`k6` 用 `o1.e=IMAGE`，见 `0x62d368`），真正决定"重传本地文件 vs 服务端引用"的是 **`hl0/m.a`（forwardFromImgId，是源图 imgLocalId）**：原生链 `B6` 把 `r0.a`(=imgLocalId) 传进 k6 → `m.a>0` ⇒ j2 构造器走 `d3.O8().o2(m.a)→r0` 把 **ImgInfo2 记录**挂进上传，实际读图走 `j2.U(r0)→b1.z3(r0.j/r0.e)`（含 `SERVERID://→Y2(msgSvrId)` 查 ImgInfo2）+ CDN `ot5` protobuf ⇒ 服务端引用/秒传拿清晰图；而**我方 FileSender 把 `m.a` 硬编码 -1** ⇒ j2 走"读本地 ori 文件"分支 ⇒ 本地无大图只能发 15KB 缩略图。**唯一推荐随之收敛为"把 m.a 从 -1 改成源图 imgLocalId"（最小改动），不再是重建整条 k2/ot5 链。** 详见文末 [Q1-R 真机实验修正](#q1-r-真机实验修正决定性)。下方 Q1~Q6 保留原始取证过程，凡与 Q1-R 冲突处以 Q1-R 为准。

## 目录

- [Q1 微信自带转发图片，本地无大图时数据从哪来](#q1-微信自带转发图片本地无大图时数据从哪来)（(a) 下载 vs (b) 服务端引用）
- [Q2 若(a)下载：触发下载的确切入口与可复用性](#q2-若a下载触发下载的确切入口与可复用性)
- [Q3 若(b)服务端引用：多目标能否复用](#q3-若b服务端引用多目标能否复用)
- [Q4 第三条路：j2/FileSender 从 SERVERID 构造 / hook b1.C4](#q4-第三条路j2filesender-从-serverid-构造--hook-b1c4)
- [Q5 唯一推荐 + 最小改动清单 + 主会话真机验收判据](#q5-唯一推荐--最小改动清单--主会话真机验收判据)
- [Q6 若所有复用路线都有硬约束：降级排序](#q6-若所有复用路线都有硬约束降级排序)
- [Q1-R 真机实验修正（决定性）](#q1-r-真机实验修正决定性)
- [DONE](#done)

## 进度

- [x] 建文件 + 目录
- [x] Q1 数据来源裁决（(a) vs (b)）
- [x] Q2 下载入口与可复用性
- [x] Q3 服务端引用多目标复用
- [x] Q4 第三条路评估
- [x] Q5 唯一推荐 + 最小改动 + 验收判据
- [x] Q6 降级排序
- [x] Q1-R 真机实验修正（决定性）
- [x] [DONE]

---

## Q1 微信自带转发图片，本地无大图时数据从哪来

### Q1.0 一句话裁决

【源码确定】**两者都走，但"本地无大图"这个具体场景下，走的是 (b) 服务端引用式转发（微信内部称「秒传」/ second transfer），不是 (a) 重新下载大图再上传。** 精确说：微信自带转发**先做一次 CDN "是否已存在"检查（`field_onlycheckexist=true`）**——
- 若服务端已有该文件（**图片来自_SERVERID_ / 对端已上传过 CDN ⇒ 几乎总是命中**）⇒ 命中「秒传」分支：构造 `com.tencent.mm.modelimage.k2`（`NetSceneUploadMsgImgForCdn`，type=110，CGI `/cgi-bin/micromsg-bin/uploadmsgimg`），把原图的 **cdn fileId / aesKey / md5 / MsgForwardType** 塞进 `ot5` protobuf 发给服务端，**客户端根本不读本地大图、不重传图片二进制**，服务端引用旧文件生成新消息 ⇒ 对端拿到清晰大图。
- 仅当 CDN 检查判定"服务端没有"（`not support second !! dealDownLoadImg`）才落入 (a)：`MsgRetransmitUI.j6()` 触发下载/落盘后再走 j2 重传。

> 这解释了真机铁证：md5 `216b1f7ccd...` 的桶里只有 36B 坏 `hd⌖` + 15KB 缩略图、**没有大图 `.jpg`**，我方 FileSender 只能拿 15KB 缩略图当 ori ⇒ 对端模糊图；而微信自带转发同一张图，**因为该图 SERVERID（`SERVERID://2772...`，`_r52_device_log_snapshot.md:88-98`）对应的大图已在 CDN**，走「秒传」直接服务端引用，对端收到清晰大图——**全程不依赖本地大图文件**。

### Q1.1 完整 dex 证据链（全部 code_off 实跑可复核）

入口链：`MsgRetransmitUI`（原生转发 Activity，`_r16` 已证入口）→ `l6()` scene 分发（`0x62d768`）→ 图片消息经 `B6()`（`0x62c98c`）→ **CDN 存在性检查** → 回调 `qf4/j0.C2()`（`0x84307c`）→ 二选一分支。

| 步骤 | 类/方法 | code_off | 关键字节码/字符串（【源码确定】） |
|---|---|---|---|
| ① 取源消息图信息 | `MsgRetransmitUI.B6` | `0x62c98c` | `j9.u3(this.f)` 取 `h9`（源消息）；`b1.Q2(h9.C0(), h9.getMsgId())` / `b1.a3(h9.C0(), h9.w0())` 拿 `r0`（图片信息，含 localId=a、cdn 元数据 u、size c/d） |
| ② 建 CDN 检查任务 | `B6` | `0x62c98c` | `new tj/k`；`field_mediaId=ok0.n1.b("downimg",msgId,"checkExist",...)`；`field_fileId/aesKey/filemd5`；**`field_onlycheckexist=true`（446）**；`field_trysafecdn=true`（448）；`callback=qf4/j0`（353）；`task_MsgRetransmitUI_2`（348） |
| ③ 检查失败日志 | `B6` | `0x62c98c` | 字符串 **`"check exist fail! download img before retransmit"`**（466）；`nq/e0.B8(tj/k)` 返回 false 时打 ⇒ 证明"检查存在性"是转发前置步骤 |
| ④ 检查成功→秒传 | `qf4/j0.C2` | `0x84307c` | 入参 `tj/f.field_exist_whencheck`；为 true 时字符串 **`"support second !! sceneResult:[%s]"`**（13）⇒ 「秒传」命中；为 false 时（865）**`"not support second !! dealDownLoadImg"`** → 转 (a) `j6()` |
| ⑤ 秒传构造 scene | `qf4/j0.C2` | `0x84307c` | `b1.C4(h9.o0(),true)` 取路径（仅备案）；`new com.tencent.mm.modelimage.g`（786）；**`q1.B=o1.g=CDN_IMAGE`（791）**；`g.E=ot5`（CDN protobuf，799）；`g.G=r0`（801）；`g.F=tj/f`（803）；`q1.a()→l1→l1.a()`（857/861）入队 |
| ⑥ 工厂分派 | `hl0/q1.a` → `t81/g.k` | `0x712168` → `0x8bbd74` | `q1.a()` 按 `q1.B` 分派：`o1.g(CDN_IMAGE)` → `t81/g.k(q1)`（116/123），`t81/g.k` 内 `new com.tencent.mm.modelimage.k2(...ot5,r0,fileId,tj/f,cb...)`（39/43） |
| ⑦ 秒传 scene 定义 | `modelimage.k2` | `0x2b0a2c` 等 | tag **`MicroMsg.NetSceneUploadMsgImgForCdn`**（100/173）；CGI `/cgi-bin/micromsg-bin/uploadmsgimg`；`getType()=110`；日志 `summersafecdn NetSceneUploadMsgImgForCdn MsgForwardType[%d], hitmd5[%d], key[%s], crc[%d], originMd5[%s]`（356）⇒ **带 fileId/aesKey/md5/hitmd5 的服务端引用式"秒传"，不发图片二进制** |
| ⑧ (a) 兜底下载 | `MsgRetransmitUI.j6` | `0x62d0f8` | `b1.R5(...)` 建/取本地 image 记录；`b1.o2/u8` 落 ImgInfo2；最终仍构造 `j2`/`k2` 上传 ⇒ 仅在秒传未命中时触发 |

**关键区分判据（直接回答 team-lead 的"(a)还是(b)"设问）**：微信自带转发在"本地无大图"场景**属于 (b) 服务端引用式转发（秒传）**——或经 `k2`/`NetSceneUploadMsgImgForCdn`（`ot5` protobuf 带 `MsgForwardType/hitmd5/aeskey/crc/originMd5`，让服务端复用 CDN 旧文件），或经 j2 但 `m.a>0` 挂 ImgInfo2/CDN（见 Q1-R 修正）。(a) 下载只是"服务端确实没有该文件"时的兜底。

> ⚠️ **机制更正见 [Q1-R](#q1-r-真机实验修正决定性)**：真机证明原生转发也构造 j2（`k6` 用 o1.e=IMAGE）；"k2 vs j2"不是判别标准，**`m.a` 才是**。本节表格描述的是 B6 的 CDN-检查-k2 子路径，直接 k6→j2 子路径见 Q1-R。

### Q1.2 对必答题 1 的直接回答

> 微信自带转发图片，本地无大图时数据从哪来？(a) 触发下载，还是 (b) 服务端引用式转发？

【源码确定】**主路径是 (b) 服务端引用式转发（秒传）**：命中条件是"源消息有 SERVERID / CDN 已有该文件"（本案例满足，`SERVERID://2772...`）。微信先把 `Select`/`Retr_Msg_Id` 定位到 DB 里的 `h9`，从 `ImgInfo2`/cdninfo 拿到 cdn fileId+aesKey+md5（B6 的 ①~③），做 `field_onlycheckexist=true` 的存在性检查；命中即 `qf4/j0.C2` 的「support second」分支，构造 `k2/NetSceneUploadMsgImgForCdn` 走服务端引用，**不重传本地二进制**。**(a) 仅是不命中时的兜底**（`dealDownLoadImg`→j6）。

> 关键区分：微信自带转发发出的消息，客户端有没有走 `com.tencent.mm.modelimage.j2`(NetSceneUploadMsgImg,type=110) 上传？

【源码确定（b 方向不变）+ 机制见 Q1-R 更正】**走了 NetSceneUploadMsgImg 系列且 type=110**。真机与再反汇编确认：原生转发**也构造 j2**（`k6` 用 `o1.e=IMAGE`；触发 j2init 探针），其"清晰"来自 **`m.a=imgLocalId>0` ⇒ j2 挂 ImgInfo2/CDN 服务端引用**；走 CDN 检查命中时另经 `k2/NetSceneUploadMsgImgForCdn`（`q1.B=CDN_IMAGE→t81/g.k`）。**与我方的本质差异不是 j2 vs k2，而是 `m.a`：我方 `m.a=-1`（`FileSender.kt:168-171`、`ForceForward.kt:2065`）⇒ j2 读本地 ori 缩略图 ⇒ 模糊；原生 `m.a=imgLocalId>0` ⇒ 服务端引用 ⇒ 清晰。详见 [Q1-R](#q1-r-真机实验修正决定性)。**

> 【取证缺口 / 强推测】"秒传命中"是否 100% 依赖 SERVERID 对应的 CDN 文件仍在（而非客户端上传历史）？

【强推测】是。B6 的 `field_fileId/aesKey/filemd5` 全部来自 `h9.w0()/C0()` 对应的 `r0`（ImgInfo2 的 cdninfo），即"这条消息当初的图片在 CDN 的标识"。若服务端已清理该文件（极久远消息），`B8` 检查返回 false ⇒ 落 (a) 下载兜底。**装机验证方法（交主会话，本 lane 不碰设备）**：hook `qf4/j0.C2`（`0x84307c`）打 `field_exist_whencheck` 与那两条 "support second / not support second" 日志 + hook `t81/g.k`（`0x8bbd74`）确认秒传分支命中；再 hook `q1.a()`（`0x712168`）打 `q1.B` 枚举名（IMAGE vs CDN_IMAGE）。若同一张 `216b1f7ccd...` 图微信自带转发时日志出现 `support second` + `q1.B=CDN_IMAGE` + `NetSceneUploadMsgImgForCdn` ⇒ 本裁决坐实。

[DONE-Q1]

## Q2 若(a)下载：触发下载的确切入口与可复用性

> Q1 已裁决"本地无大图"主路径是 (b) 秒传，但 (a) 下载兜底仍需回答：它的入口是什么、我方可否安全复用。

### Q2.1 下载的确切入口（【源码确定】）

【源码确定】下载兜底由 `qf4/j0.C2` 的 `not support second !! dealDownLoadImg` 分支（865）→ 调 `MsgRetransmitUI.j6(r0, h9, ...)`（`0x62d0f8`，897）。`j6` 内部：
- `b1.R5(...)`（`0x55`）建/查本地 image 记录（ImgInfo2），`b1.o2(localId)`（78）→ `r0.m(1)` 标记；
- 新建 `h9`、`j9.fa(h9)` 落消息表（180）、`b1.u8(localId,r0)`（239）关联；
- 最终拼 `upimg` mediaId、构造上传 scene 入队。

⚠️ **重要纠偏**：从 B6（②）看，微信自带转发**每次都先做 `field_onlycheckexist=true` 的 CDN 存在性检查**（`tj/k`+`qf4/j0` 回调），命中秒传就**不下载本地大图**。也就是说"点自带转发"这个动作本身**不是可靠的'确保大图落盘'触发器**——它优先走服务端引用，本地仍可能没有大图文件。⇒ 我方若想靠"模拟自带转发"来让大图落盘，逻辑上走不通（秒传命中时它根本不下载）。

### Q2.2 我方可否安全触发"确保该 msgId 大图已落盘"

【强推测 + 取证缺口】技术上存在可 hook 的下载触发面，但**不建议我方主动触发**，理由如下：

| 复用方式 | 锚点 | 可行性裁决 | 风险 |
|---|---|---|---|
| 反射调 `b1` 的下载/取图 | `com.tencent.mm.modelimage.d3.O8():b1`（`0x2a8eec`）+ `b1` 下载方法（如 `Q2/a3/o2`、`C4/z3`、`Z0` exportToJpg，`0x2a5d98`） | 【取证缺口】需真机确认哪个方法签名"同步确保落盘"；`C4/z3` 只算路径不下载 | 反射宿主私有 API，签名/单例获取需真机验；`Z0` 可能触发导出 IO 副作用 |
| hook 秒传检查回调强转下载 | `qf4/j0.C2`（`0x84307c`）改 `field_exist_whencheck` | ⛔ **违反只读纪律**：这是写 param.args，且会让宿主对每条转发都强制下载 | 改宿主对象、耗流量、破坏正常转发、并发竞争 |
| 直接调 `j6` / DownloadImgService | `MsgRetransmitUI.j6`（`0x62d0f8`）/`com.tencent.mm.modelimage.m`（tag `ModelImage.DownloadImgService`，`_r4_dexref` 命中） | 【强推测】j6 是 Activity 实例方法且会写消息表、构造 scene 入队 ⇒ 不是纯下载 | **写宿主消息表 + 入队发送**，会重复发消息，绝不可在我方转发链里调 |

**结论【强推测，偏否决】**：(a) 下载路线对我方**没有"只读取证式"的安全复用锚点**——所有能"确保大图落盘"的入口要么写宿主字段/消息表、要么本身就会入队发消息，要么只是路径计算不下载。且 download 触发依赖"秒传未命中"，而本案例恰好秒传命中（有 SERVERID）。**不推荐把"触发下载"作为我方方案**；下文 Q3 的"直接复用秒传 scene"才是正解。

---

## Q3 若(b)服务端引用：多目标能否复用

### Q3.1 核心问题：能不能不走 A（一次一目标），直接让每个目标都发"秒传清晰图"

【源码确定 + 强推测】**能，且这是本次唯一推荐方向。** 关键洞察：秒传的能力来自**"发什么 scene"（`q1.B=CDN_IMAGE` → `k2/NetSceneUploadMsgImgForCdn` + CDN 元数据），而不是"从哪个 Activity 启动"**。路径 A 被否的根因是"`MsgRetransmitUI` 一次 Intent 只能指定一个目标 + 双击选人器冲突"（`_r73_microx_image_route.md` Q2.3，源码确定：`Select_Conv_User=getStringExtra`+单元素 List）——**但那是用 Intent 启动整个 Activity 的约束**。我们自己用反射组装 `q1`/`g`/`k2` 时，**目标 wxid 是逐个填进 `q1.a`（`q1.h(target)`）的**，与 FileSender 现在的做法完全一样 ⇒ **天然支持多目标循环**，不受"一次一目标"限制。

### Q3.2 复用链的可行性裁决（锚点 + 与现有 FileSender 的对接）

【源码确定】秒传链的每个环节都能用与 FileSender 相同的反射手段逐个目标构造：

| 环节 | 我方已具备 | 秒传需要的增量 | 锚点（code_off） |
|---|---|---|---|
| 构造 `q1` builder | ✅ `r1.a(o1)`（现走 `o1.e=IMAGE`） | 改为 `o1.g=CDN_IMAGE`；`q1.B=o1.g` | `r1.a` @ `hl0/r1`；`o1.g` 见 `o1.<clinit> 0x710994` |
| 填 CDN 元数据 | ❌ 现在完全没有 | 从源 `h9` 的 cdninfo（`r0.u`，形如 `"<fileId>:$aeskey:$md5:..."`）解析出 fileId/aesKey/filemd5/originMd5/hitmd5/MsgForwardType | 解析范式见 B6 `0x62c98c`：`.msg.img.$aeskey`/`$md5`/`$cdnbigimgurl`/`$hdlength`/`$originsourcemd5`；`g9.c(r0.u,"msg")` |
| 建 `ot5` protobuf | ❌ | `new q54/ot5` + 逐字段 `d/e/f/n/o/H/T/W/X` | `qf4/j0.C2 0x84307c` [687-770] 有完整逐字段填充 |
| 建 `g` 场景载体 | ❌ | `new com.tencent.mm.modelimage.g`；`g.E=ot5; g.G=r0; g.F=tj/f`（CDN 文件元数据）；`g.J=qf4/l0` 回调 | C2 [786-817] |
| 工厂→k2→入队 | ✅ 已有 `q1.a()→l1.a()` 范式 | `q1.a()` 因 `q1.B=CDN_IMAGE` 自动走 `t81/g.k`→`new k2`；`l1.a()` 入队 | `q1.a 0x712168`→`t81/g.k 0x8bbd74`→`k2.<init> 0x2b0b88` |

**可行性裁决**：
- 【源码确定（结构）】多目标**无障碍**：目标 wxid 逐个填 `q1.h(target)`，与 FileSender 现有 `runBatch` 循环完全兼容，一次目标一组 `q1/g/k2`。
- 【强推测（端到端未真机验）】"清晰图"能否拿到取决于**能否从本条消息的 `h9`/ImgInfo2 正确解析出 cdninfo（fileId+aesKey+md5）**——这是秒传的"凭证"。本案例 `bigImgPath=SERVERID://2772...` 已在库（`_r52`），凭证存在面大，但 cdninfo 字符串格式/字段解析**必须真机打印验证**（见 Q5 验收判据）。
- 【强推测】新消息落库：秒传链里 `j9.fa(h9)`（C2:208）/ `j9.va(...)`（C2:613）会插入新 message 行，`MsgForwardType`/`originMd5` 写入 `ot5`——**对端据 originMd5 显示清晰原图**。转发标记（`hl0/m.a/b/f`）在 C2 [826-850] 也照常填（`m.b=源msgId`）。

### Q3.3 绕开 A 的具体做法（此前 A 被否的难点如何化解）

此前 A 被否 = "一次一目标 + 选人器冲突 + `Retr_Msg_Id` 查库未验"。本方案绕开方式：

1. **不选人器**：不启动 `MsgRetransmitUI`，不碰 `Select_Conv_User`/`SelectConversationUI`。目标来自我方自己的选人器（已闭环），逐个 wxid 直接填 `q1.h(target)`。⇒ 选人器冲突、一次一目标、UI 栈问题**全部消失**。
2. **不依赖 `Retr_Msg_Id` 查库走 Activity**：源消息从长按快照的 `snap.msgId`（`h9` DB 真值，`_r73_image_path_forensics.md` §2.2）直接 `j9.u3(msgId)` 取 `h9`，再取 cdninfo——与 B6 取源的方式同源，但**在我方线程内做**，不经 Activity 生命周期。
3. **每个目标独立构造 scene**：`ot5`/`g`/`k2` 是按目标新建的轻量对象（不含共享可变状态），`k2` 携带自己的广播回调 `qf4/l0`（C2:805 `g.J = new qf4/l0(...)`）⇒ 多目标并发彼此隔离。

⛔ **红线**：`h9`/`r0`/`q1` 这些是**宿主对象**，我方只读其字段（用 `callMethod`/`getObjectField` 读 cdninfo/msgId），**绝不写宿主消息表、不调 `j9.fa/va`、不调 `b1.u8/R5`**（那些由 `k2` 自己在入队后处理）。若尝试复用 `qf4/j0.C2` 整段逻辑，**只借鉴其"读字段 + 造 ot5"的构型**，不直接调用它（它是回调，会触发下载/补消息）。

【取证缺口】`q54/ot5` / `q54/kx4` 是混淆的 protobuf 类，字段 `d/e/f/n/o/H/T/W/X` 的**精确 proto 语义**（哪个是 fileId、哪个是 aesKey、MsgForwardType 该填几）静态只靠 C2 的搬运顺序推断，未逐字节坐实到 proto schema。**装机验证方法**：真机 hook `q1.a()`（`0x712168`）打 `q1.B`；hook `k2.<init>`（`0x2b0b88`）逐字段打 `ot5.d/e/f/n/o/H/T/W` + `r0` 的 cdninfo，与日志 `summersafecdn ... MsgForwardType[...] hitmd5[...]` 对照，确认填值正确。

[DONE-Q3]

## Q4 第三条路：j2/FileSender 从 SERVERID 构造 / hook b1.C4

### Q4.1 让 j2/FileSender 从 SERVERID 构造（不依赖本地大图文件）

【源码确定】j2 主构造器（`0x2aef10`）里**确有一段从 CDN id 解析路径的逻辑**，但它是从 `m.a`（forwardFromImgId）而非 SERVERID 出发：

```
[284] iget-wide v13, m.a            // forwardFromImgId
[288] if-lez v13 -> 320             // m.a<=0 跳过（我方恒 -1 ⇒ 必跳）
[290] d3.O8():b1
[300] b1.o2(Long.valueOf(m.a)) -> r0   // 按 imgLocalId 查 ImgInfo2
[313] b1.z3(r0.j, ..., true) -> path
[317] t0.o = r0                     // 传给 b1.L5() 做"秒传/复用"决策
```

- 【强推测】若把我方 `m.a` 从 `-1` 改成"源图 imgLocalId"（从 `b1.Q2/a3` 查 `r0.a`），j2 会走 `b1.o2(m.a)` 拿到 cdninfo，进而 `b1.L5(t0)` 内部**可能**也走 CDN 复用——但这**与 j2 的 `q1.B=IMAGE` 工厂分派耦合**，且 j2 的 `doScene` 仍有 `doScene hd img info is null or error` / `doScene img info is null or error` 前置校验（`j2.doScene` 字面），本地无大图时 `ImgInfo2` 的 hd 信息是否够用未证。
- 【取证缺口 + 偏否决】这条"给 j2 塞 m.a"的路**不如直接走 Q3 的 k2 正路干净**：k2 是微信为秒传专门设计的 scene（`NetSceneUploadMsgImgForCdn`），字段就是 CDN 元数据；j2 是"上传本地文件"的 scene，硬塞 CDN id 属于逆语义用法，且 m.a 在 j2 里只用于"解析本地路径"（o2→z3），不能证明能免本地大图。⇒ **不作为推荐**。

### Q4.2 hook 宿主 `b1.C4/z3` 解析器（此前记为路线 C）

【源码确定】`b1.C4(String,Z)`（`0x2a563c`）/ `z3(...)`（`0x2a6ae4`）/ 单例 `d3.O8()`（`0x2a8eec`）是 URI→路径解析器（`_r73_microx_image_route.md` Q1 已证）。本场景价值评估：

| 维度 | 评估 |
|---|---|
| 能否拿到"清晰图路径" | 【强推测】不能。C4/z3 做的是 `THUMBNAIL_DIRPATH://th_<md5>` → `image2/<xx>/<yy>/` 桶路径计算，**纯路径拼接，不下载、不校验文件存在**。本地桶里没有大图时，C4 返回的仍是那个"该在但不存在"的大图路径——治不了"本地无大图" |
| 能否触发宿主下载兜底 | 【源码确定】不能。C4/z3 字节码里只有 substring/前缀判断/`g1.a` 拼路径，无任何 NetScene/CDN 调用（Q1 反汇编已见） |
| 风险 | 【强推测】中：`b1.C4` 被宿主多处业务调用（如 `o63.m.b`，`0x7a76e0`），hook 它打印入参/返回可作**只读诊断**（Q1 证据⑤），但若改写返回值会污染所有宿主图片业务 |

**裁决【源码确定（结构）+ 强推测（价值）】**：hook `b1.C4` 对"本地无大图拿清晰图"**无价值**——它只算路径不下载、不引用 CDN；它唯一的价值是 Q1/Q3 里作为**只读诊断探针**（`_r73_microx_image_route.md` Q3.4 的 H9）确认微X/宿主是否走这条解析链。**不改变 Q3 的推荐**。

---

## Q5 唯一推荐 + 最小改动清单 + 主会话真机验收判据

### Q5.1 四维对比 + 唯一推荐

| 候选 | 对端清晰大图 | 保留多目标 | 宿主崩溃风险 | 实现与验证成本 |
|---|---|---|---|---|
| (a) 触发下载后重传 j2 | 【强推测】能（下载后） | ✅ 能 | 中高：下载入口多写宿主消息表/入队，易重复发、耗流量 | 高：无只读安全锚点，签名/兜底条件未验 |
| **(b) 复用服务端秒传 k2（Q3）** | **【源码确定(链)+强推测(凭证)】能：服务端引用，不依赖本地大图** | **✅ 能：逐个 wxid 填 q1.h，与 runBatch 兼容** | **中：需从 h9 读 cdninfo + 造 ot5/g/k2；全 runCatching + 只读宿主字段，不写消息表** | **中高：ot5 字段语义/proto 需真机打印坐实，但链完整、锚点齐全** |
| (c) hook b1.C4 解析器 | ❌ 不能（只算路径不下载/不引用） | ✅ 能 | 中：hook 全局业务 | 中：仅诊断价值 |
| (d) 当前自建缩略图（现状） | ❌ **已知模糊**（15KB th_ 当 ori） | ✅ 能 | 低 | 已成型但结果不可接受 |

### 唯一推荐（一句话）

**走 (b)：我方转发图片时，不再用 `q1.B=IMAGE`→j2 重传本地文件，而是复用微信自带的"秒传"链——从源 `h9`/ImgInfo2 读出 cdninfo（fileId/aesKey/md5/originMd5），造 `ot5` + `modelimage.g`（`q1.B=CDN_IMAGE`）→ 工厂自动走 `t81/g.k`→`k2/NetSceneUploadMsgImgForCdn`，对每个目标 wxid 独立构造一份入队；全程只读宿主人获取凭证、runCatching 全包、不写消息表、不启动 Activity ⇒ 多目标保留 + 服务端引用拿清晰大图（不依赖本地大图）。** 前提真机验收 cdninfo 解析正确性（Q5.3）。

### Q5.2 最小改动清单（本 lane 一行未动，均为交主会话的建议）

| # | 文件 | 改动 | 行数级 | 证据 |
|---|---|---|---|---|
| 1 | `core/FileSender.kt` 或新增 `core/CdnFastForward.kt` | 新增"秒传发送"方法 `sendCdnFast(imgLocalId 或 h9, wxid)`：反射 `o1.g(CDN_IMAGE)`→`r1.a` 得 q1、`q1.B=o1.g`；填 `q54/ot5` 各字段 + `new modelimage.g` 填 `E/G/F/J`；`q1.a()→l1.a()`。**返回前仍需评估 NetScene 异步结果**（沿用 INP 探针的 onGYNetEnd 判据） | ~60-90 | 【源码确定】C2 [687-864] + `t81/g.k 0x8bbd74` |
| 2 | `feature/ForceForward.kt` | `snapshotOf` IMAGE 分支：除了现有 `resolveImagePath`，新增读 cdninfo（从 `snap.msgId`→`j9.u3`→`r0.u` / 快照携带）；`runBatch` 里 IMAGE 且"有 SERVERID/cdninfo"时优先走 #1 秒传，**无 cdninfo 才退回现有 FileSender 缩略图路径或 SKIP** | ~30-40 | 【源码确定】B6 `0x62c98c` 取源范式 |
| 3 | 新增只读探针 `probe/CdnFastProbe.kt`（tag 如 `CFP`，默认关） | hook `qf4/j0.C2`(`0x84307c`) 打 `field_exist_whencheck`+support second 日志；hook `q1.a`(`0x712168`) 打 `q1.B`；hook `t81/g.k`(`0x8bbd74`)/`k2.<init>`(`0x2b0b88`) 逐字段打 ot5/r0 cdninfo。**全程 runCatching，只读，不改 param、不入队** | ~50 | 【源码确定】§Q1.1 表 |
| 4 | 既有 `probe/ImageNetSceneProbe.kt`（tag `INP`） | 扩展 hook `k2.onGYNetEnd`(`0x2b0eb4`) 与 j2 的 `0x2b0284` 并列，打 errType/errCode + tag（`NetSceneUploadMsgImgForCdn` vs `NetSceneUploadMsgImg`） | ~15 | 【源码确定】`k2` 方法表 |
| 5 | `feature/ForwardLogic.kt` | 可选：新增纯函数 `parseCdnInfo(String):CdnCreds`（解析 `fileId:aesKey:md5:...`），单测覆盖本案例 SERVERID 形态 | ~30 | 【强推测】字段顺序待真机坐实 |

⛔ 不改：`ForwardNativeLogic` 字段、`chat.forward_native` 默认值、`AntiRevoke`/`RevokeRescue`、任何 Key、任何消息状态；不直接调用 `qf4/j0.C2`/`MsgRetransmitUI.j6`/`j9.fa/va`/`b1.u8/R5`（会写消息表/入队/下载）；不跑 adb/装机/gradle/commit（交主会话）。

### Q5.3 主会话真机验收判据（**必须人眼对端清晰大图 + 多目标**）

**准备**：目标图片 = md5 `216b1f7ccd...` 那张（桶里只有 36B 坏 `hd⌖`+15KB 缩略图、无大图）——**这是最能证伪"依赖本地大图"的样本**。先发「文件传输助手」排除 openim 变量。

1. **【首要·坐实机制】只读观察微信自带转发**：开 `CFP` 探针 → 长按该图 → 点**微信自带**转发 → 选目标 → 看日志：应出现 `support second`、`q1.B=CDN_IMAGE`、`hit k2/NetSceneUploadMsgImgForCdn`、ot5 带 fileId/aesKey/md5；hook `k2.onGYNetEnd` errType/errCode **全 0**；对端**人眼清晰大图**。⇒ 坐实 Q1 裁决（(b) 秒传，不依赖本地大图）。
2. **【主判据·我方复用】开我方"转发（模块）"选 2+ 目标**：同样该图，日志应出现每个目标的 `CFP ... q1.B=CDN_IMAGE ... k2 ... target=<wxid>` × N、`INP ... NetSceneUploadMsgImgForCdn ... errType=0 errCode=0` × N；**每个目标对端都人眼收到清晰大图**（长按保存字节数/分辨率 ≫ 15KB 缩略图，接近源大图）。⇒ 同时验证"清晰 + 多目标保留"。
3. **【凭证解析正确性】**比对 `CFP` 打出的 ot5 字段与微信自带那一路（判据 1）的字段：fileId/aesKey/md5/originMd5/MsgForwardType 应一致或等价；若字段错位（如把 aesKey 填进 fileId）⇒ 秒传失败（errType 非零）或收到模糊/错图，回到改动 #1/#5 修字段映射。
4. **【兜底/降级】**找一张**无 SERVERID / 无 cdninfo**的图（纯本地拍照未上传）：我方应安全退回现有路径——有本地大图则 FileSender 正常发；无大图 → `planFor SKIP` + 一行 warn，**无空消息、无红叹号、无 FATAL/ANR**。
5. **【反向对照】**临时把 Image 分支强制回 `o1.B=IMAGE`（走 j2 重传）复现同一张图 ⇒ 对端模糊图，证明判据 2 的 PASS 确由秒传改造带来。
6. **【并发隔离】**2 目标连发时观察 `k2` 各自的 `qf4/l0` 回调独立返回，无串包/错目标；若 UI 卡顿或目标错乱 ⇒ 检查 ot5/g 是否有共享可变状态（改动 #1 需按目标 new 独立对象）。

**红线复述**：任何新增 hook/反射用 `runCatching` 全包，单步失败只 warn，绝不抛异常到微信线程；对宿主 `h9/r0/q1` **只读**、不写消息表、不调会入队/下载的方法；日志只打 md5 前 10 位 / cdninfo 尾段 / wxid 前 24 位，避免刷屏；探针默认关，按 `op` 显式开；"入队 ok=true"绝不作"对端收到"的证据，以人眼清晰大图为准。

---

## Q6 若所有复用路线都有硬约束：降级排序

> 本节仅在 Q5 推荐的 (b) 秒传路线**真机验收失败**（如 ot5 proto 字段无法坐实、或秒传被宿主风控拦截）时启用，作为向用户说明取舍的依据。

【源码确定 + 强推测】(b) 秒传路线在结构上无"一次一目标/选人器"硬约束（Q3.2），其唯一不确定性是"cdninfo 凭证解析"的真机正确性——**这属于可验证的实现风险，不是原理性硬约束**。⇒ **当前不应预先降级**。若真机证伪（ot5 字段填不对且短时无法修），伤害最小的降级排序：

| 排序 | 方案 | 对端结果 | 为何排此位 |
|---|---|---|---|
| 1（首选降级） | 保留多目标 + 本地大图存在时走 FileSender 大图；无本地大图时**对每个目标安全 SKIP + 明确提示"该图本地无大图，请先点开大图一次再转发"** | 收到清晰图 or 不发（用户明确告知） | 不产生模糊图（保护"清晰"底线），多目标能力仍在，用户有明确补救动作；提示后重转即可 |
| 2 | 保留多目标 + 全部走现有 FileSender（含缩略图 fallback） | **模糊图** | 保住"多目标 + 发出"，但牺牲清晰度——用户明确"不接受模糊图"，故仅次选 |
| 3（最后） | SKIP 所有无本地大图的图片，无任何替代 | 完全不发 | 最安全但功能最弱；用户要"保留转发能力"，故垫底 |

⚠️ **不建议的"降级"**：把 th_ 缩略图当 ori 喂 j2 期望"碰巧清晰"（`_r73_image_send_fail_forensics.md` §3 已证必模糊且疑似发送失败）、或改 `m.a`/hook C4 硬凑（Q4 已否决）——这些既不清晰也不稳，属"必现事故"，不进排序。

**结论**：Q5 的 (b) 秒传路线**无原理性硬约束**，按 Q5.3 真机验收即可；只有验收失败才按上表降级，且降级首选是"清晰 or SKIP+提示"而非"模糊图"。

---

## Q1-R 真机实验修正（决定性）

> 主会话补充真机实验（01:34~01:35），直接检验必答 1。本节用新证据 + 重新反汇编互斥掉各种解释，并收敛唯一推荐。**凡本文 Q1~Q6 与本节冲突，以本节为准。**

### Q1-R.1 真机实验事实（【真机确定】）

开 INP 探针（`op=inp sub=on`）→ 用户长按坏图 md5 `216b1f7ccd...` → 点**微信自带「转发」** → 文件传输助手：
- **结果 A（探针，线程 `[GT]ColdPool/#15`，非我方 `wxg-forward`）**：`INP j2init` **触发**，`str[1]=from`、`str[2]=to`、`str[3]=.../image2/21/`（目录段）、`str[7]=wxid_...:`（疑似 from 前缀），**`ori=.../image2/21/6b/216b1f7ccd...jpg oriTail=...46801.jpg`**。
- **结果 B（随后 `op=label sub=file` 列同桶）**：`<md5>.jpg⌖` **36B**（mtime 1795189896880 ≈ 转发时刻）、`th_<md5>hd⌖` 36B、`th_<md5>` 15906B。
- **结果 C（用户人眼）**：对端收到**清晰大图**。

**表面矛盾**：微信自带转发构造了 j2、ori 指向一个 36B 文件，却发出清晰大图。

### Q1-R.2 我原 Q1 裁决的错误点（自我修正）

【源码确定】我原称"原生转发走 k2、不构造 j2"——**机制细节错误**。重新反汇编坐实：
- `MsgRetransmitUI.k6`（`0x62d368`）`[159] sget-object v10, hl0.o1.e`（**IMAGE**，非 CDN_IMAGE）→ `r1.a(o1.e)` → `q1.a()` → `t81/g.a()` → **`com.tencent.mm.modelimage.j2`**。故 INP 的 `j2init` 必然触发，与真机一致。
- `B6`（`0x62c98c`）**有两条子路径**：① CDN 存在性检查命中 → `qf4/j0.C2`（`0x84307c`）→ `k2`/`NetSceneUploadMsgImgForCdn`（我原描述的秒传路线）；② **`[496-522]` 直接 `k6(..., v6=r0.a=imgLocalId)`** ⇒ j2（真机这次走的是这条，因为它构造了 j2）。
- ⇒ 真机结果 A **不能**像 team-lead 初判那样"偏 (a) 下载重传"——**j2 是否构造不是判别标准**。

### Q1-R.3 真正的判别标准 = `hl0/m.a`（forwardFromImgId = 源图 imgLocalId）

【源码确定，逐字节】：

| 事实 | 证据（code_off） |
|---|---|
| k6 把第 6 参（long）写进 `m.a` | `k6 [218] iput-wide v2, m.a`；`v2` = `move-wide/from16 v2, v28`（第 6 参） |
| B6 直接 k6 分支把 `r0.a`(imgLocalId) 当第 6 参传入 | `B6 [501] iget-wide v0, r0.a` → `[506] v6=v0` → `[519] k6(...,v6)`；r0 来自 `b1.Q2/a3`（B6 `[74]/[119]`）⇒ **原生链 m.a = imgLocalId > 0** |
| j2 构造器见 `m.a>0` 就把 ImgInfo2 记录挂进上传 | `j2.<init> 0x2aef10 [284] m.a` → `[288] if-lez 跳过` → `[290] d3.O8().o2(m.a)→r0` → `[317] t0.o=r0` → `[418] b1.L5(t0)` 建上传记录 |
| j2 实际读图走 ImgInfo2 记录，不是 ori 字段 | `doScene [125] R()→r0`（`R()`=`b1.o2(j2.i)`）；`[1379] j2.U(r0)`→`[1385] o6.N(path)`；`U()`（`0x2ae668`）= `b1.z3(r0.k==1 ? r0.j : r0.e)` |
| z3 支持 `SERVERID://` → 按 msgSvrId 查 ImgInfo2 | `b1.z3 [8] "SERVERID://"` → `[30] b1.Y2(long)`；`Y2`=`ImgInfo2 WHERE msgSvrId=?`（`[8] 'msgSvrId=?'`） |
| j2/ot5 也带 CDN 秒传字段 | `doScene` 拼 `ot5`，`j2` 日志 `summersafecdn ... MsgForwardType[...] hitmd5[...] originMd5[...]`；本案例 `bigImgPath=SERVERID://2772...`（`_r52:94`） |

**推论链（解释矛盾）**：原生转发 j2 的 `m.a = imgLocalId > 0` ⇒ j2 不再把 ori 文件当数据源，而是：
1. 构造器用 `o2(m.a)` 拿到**源消息的 ImgInfo2 记录** `r0`（内含 `bigImgPath=SERVERID://...`、cdninfo=fileId/aesKey/md5）；
2. doScene 实际读图路径由 `U(r0)→z3(r0.j/r0.e)` 决定，`SERVERID://` 再经 `Y2(msgSvrId)` 回查 ImgInfo2 ⇒ **指向服务端/别处的真实大图**，ot5 protobuf 带 CDN 凭证让服务端引用/秒传 ⇒ 对端清晰图。
3. 探针打的 `ori=.../<md5>.jpg` 是 j2 的 **`q1.j`/`B` 字段 = 消息 imgPath 元数据**，`36B ⌖` 文件是**转发此刻新建的输出占位/索引**（mtime=转发时刻），**不是被读取的上传源**。

### Q1-R.4 对 team-lead 四个解释的逐一体检

| 解释 | 裁决 | 依据 |
|---|---|---|
| ① ori 只是字段名，真数据走 SERVERID/ImgInfo2/CDN | ✅ **成立（主因）** | doScene 实际读图走 `U(r0)→z3(r0.*)`、`SERVERID://→Y2(msgSvrId)`、ot5 CDN 凭证（Q1-R.3）。ori/B 字段非数据源 |
| ② 先下载大图写别处，36B 是副作用 | ⚠️ **部分成立（占位是副作用，但"下载"非必需）** | 36B mtime=转发时刻 ⇒ 是转发时新建的输出占位；秒传命中时无需下载大图（Q1 `support second`），`dealDownLoadImg` 仅未命中兜底 |
| ③ b1.C4/z3 产出不同路径 | ⚠️ **部分成立（z3/Y2 是关键一环，C4 不是）** | 真正用到的是 `z3(r0.j/r0.e)` 与 `Y2(svrId)`（Q1-R.3）；`C4` 是 URI→路径的另一入口，此处非决定环节 |
| ④ 探针 ori 参数挑错 | ✅ **成立（探针标签误导，但非"挑错参数"）** | 探针确实取到构造器 ori 参数（=`pq1.j`/B 字段），没挑错；错在**把 ori 当数据源**——而 j2 在 `m.a>0` 时不读它。需补打 `m.a` 才能定位 |

**综合裁决【源码确定 + 真机确定】**：**根因是 `m.a`。** 微信自带转发 j2 的 `m.a = imgLocalId > 0` ⇒ 走 ImgInfo2/CDN 服务端引用 ⇒ 清晰图（或 B6 命中秒传 k2，同源）；我方 FileSender/nativeForwardImage 把 `m.a` 硬编码 **-1**（`FileSender.kt:168-171` 全默认 `new m()`；`ForceForward.kt:2065` `m.a=fi.fromImgId=-1`）⇒ j2 走"读本地 ori 文件"分支 ⇒ 本地无大图只能发 15KB 缩略图 ⇒ 模糊。**这就是"微信自带清晰、我方模糊"的真正机制差异，比原 Q1 的"k2 vs j2"更准确、更可落地。**

### Q1-R.5 INP 探针实验：两种结果如何改变推荐（回应 team-lead 首条补给）

> team-lead 初判："j2init 有日志 ⇒ 偏 (a) 下载重传；零日志 ⇒ 偏 (b) 服务端引用"。**本次真机是"有日志"，但按 Q1-R.2/3，j2 构造与本案例走的是服务端引用，故初判的二分法不成立**——判别必须看 `m.a`。

- **结果"j2init 有日志"**（本次实际发生）：⇒ 不能判 (a)。必须**补打 `m.a` 与 `q1.B`** 才能定性：`m.a>0`（=imgLocalId）⇒ (b) 服务端引用/秒传成立 ⇒ **推荐不变（甚至更强），且收敛为"改 m.a"最小方案**。
- **结果"j2init 零日志"**（未发生）：⇒ 原生转发完全不构造 j2 ⇒ 走纯 `k2`/其它非 j2 scene ⇒ 仍属 (b)，但我方"改 m.a 复用 j2"的前提失效 ⇒ **需回退到原 Q3/Q5 的"重建 k2/ot5 链"方案**。

**⇒ 一句话**：`INP j2init` 有/无日志**不是**推荐切换的开关；`m.a`（与 `q1.B`）才是。**本案例 j2 有日志 + 对端清晰 ⇒ 强烈支持"我方改 m.a=-1→imgLocalId"这一最小方案**，原 k2 重建方案降为备选。

### Q1-R.6 唯一推荐（修正版，取代 Q5.1）

**把 `hl0/m.a` 从 -1 改为"源消息的 imgLocalId"**（与微信原生 B6 完全同构），其余 j2/FileSender 字段序列不动：
- 源 imgLocalId 的取法（只读、与 B6 `[74]/[119]` 同源）：`snap.msgId` → `j9.u3(msgId)` 得 `h9` → `h9.C0()`(talker) + `h9.w0()`(msgSvrId) → `d3.O8().Q2(talker, msgId)` 或 `.a3(talker, w0)` → `r0.a`(imgLocalId)；或直接从 cdninfo/`r0` 取。
- FileSender：`new m()` 后 `setLongField(m,"a", imgLocalId)`（现为全默认 -1）；nativeForwardImage：`attachForwardInfo` 里 `m.a = imgLocalId`（现 `fi.fromImgId=-1`）。
- 该值**对所有目标相同**（是源消息属性），逐个目标各建一份 `m`/`q1`/j2，`runBatch` 多目标循环不变 ⇒ 多目标保留、零 UI 改动。
- 全程 `runCatching`、只读宿主对象、不写消息表、不起 Activity、不改 `q1.B`（仍 IMAGE→j2）。

### Q1-R.7 最小改动清单（修正版，取代 Q5.2）

| # | 文件 | 改动 | 行数级 |
|---|---|---|---|
| 1 | `core/FileSender.kt` | `send()` 增参 `imgLocalId:Long`；`new hl0.m()` 后 `setLongField("a", imgLocalId)`（原全默认 -1）。字段序列其余不动 | ~4 |
| 2 | `feature/ForceForward.kt` | `snapshotOf` IMAGE 分支读源图 `r0.a`（经 `j9.u3`→`h9`→`d3.O8().Q2/a3`，全程 runCatching）写入 Snapshot 新字段 `imgLocalId`；`attachForwardInfo`/`nativeForwardImage` 用它填 `m.a` | ~20-30 |
| 3 | `feature/ForwardLogic.kt` | `Snapshot` 增 `imgLocalId:Long=-1`（可选，或放 `ref` 元数据），不破坏现有构造 | ~2 |
| 4 | `probe/ImageNetSceneProbe.kt`（tag INP） | H2 hook 的 after 里**补打 `m.a`（从 j2 字段 N 取 hl0/m，再读其 a）与 `q1.B`**；H1 保留。这是坐实本裁决的关键探针（默认关） | ~15 |
| 5 | 单测 | `m.a` 取值/logic 纯函数单测（imgLocalId>0 走服务端引用、-1 走本地） | ~20 |

⛔ 不改 `q1.B`（保持 IMAGE→j2）、不重建 k2/ot5 链（降为备选）、不写宿主消息表、不跑 adb/装机/gradle/commit。

### Q1-R.8 主会话真机验收判据（修正版）

1. **【坐实机制】**开 INP（已补打 m.a/q1.B）→ 微信自带转发那张坏图 → 看日志：`j2init ... m.a=<imgLocalId> q1.B=IMAGE`、`INP onGYNetEnd ... errType=0 errCode=0`，对端清晰大图 ⇒ Q1-R 裁决坐实。
2. **【主判据·我方】**同样图用我方"转发（模块）"选 **2+ 目标** → 每个目标日志 `j2init m.a=<imgLocalId> q1.B=IMAGE`、`onGYNetEnd 0/0`，**人眼对端清晰大图**（保存字节数 ≫15KB）+ 多目标都收到 ⇒ 改 m.a 成功。
3. **【反向对照】**临时把 `m.a` 改回 -1 复现 ⇒ 对端模糊图/15KB ⇒ 证明 PASS 由改 m.a 带来。
4. **【兜底】**无 SERVERID/无 ImgInfo2 记录的纯本地图 → `imgLocalId` 取不到（=-1）→ 退回现有 FileSender 本地大图逻辑或安全 SKIP，无红叹号/ANR。

---

`[DONE]` R73 微信自带转发图片数据源只读取证完成（含真机实验后 Q1-R 决定性修正）。**最终裁决**：判别"重传本地 vs 服务端引用"的标准是 `hl0/m.a`（=源图 imgLocalId），不是 j2 是否构造。微信自带转发经 `B6` 把 `r0.a`(imgLocalId) 传入 `k6`（`k6 [159] o1.e=IMAGE` ⇒ 构造 **j2**），使 `m.a>0` ⇒ j2 用 `d3.O8().o2(m.a)` 挂 ImgInfo2 记录（`SERVERID://→Y2(msgSvrId)`、`ot5` CDN 凭证）⇒ 服务端引用/秒传拿清晰大图；命中 CDN 检查时走 `k2/NetSceneUploadMsgImgForCdn`（同源）。我方 FileSender/nativeForwardImage 把 `m.a` 硬编码 **-1** ⇒ j2 读本地 ori（36B/15KB 缩略图）⇒ 模糊。真机 INP `j2init` 触发 + ori=36B `<md5>.jpg` + 对端清晰，正由此解释（ori/B 是元数据字段、36B 是转发时新建占位，非数据源）。**唯一推荐（收敛）：把 `m.a` 从 -1 改为源图 imgLocalId（经 `j9.u3`→`h9`→`d3.O8().Q2/a3`→`r0.a` 只读获取），q1.B 仍 IMAGE→j2，逐目标各建 m/q1/j2 ⇒ 保留多目标 + 服务端引用清晰图；全 runCatching、只读宿主、不写消息表、不起 Activity。原"重建 k2/ot5 链"降为备选。** 最小改动 5 处（FileSender ~4 + ForceForward ~20-30 + Snapshot ~2 + INP 补打 m.a ~15 + 单测 ~20），真机验收 4 条（核心：坏图我方多目标对端人眼清晰 + m.a=imgLocalId + onGYNetEnd 0/0）。全程只读取证，未碰设备/源码/gradle/git。
