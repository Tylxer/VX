# R86 B2-a v2 根因判定复核与修正（主会话自审 · 2026-11-23）

> 背景：v1（`docs/_r86_b2a_pro_verdict.md`）由 Flash 档产出（`model=reasoning` 别名继承父会话，非 Pro）。原本要派 Pro 显式档复核，但 `custom-local:deepseek-v4-pro` 与 `custom-local:deepseek-flash` 均返回 **402 Insufficient Balance**（台账已登记三件套）。按处置三选一取第三条：主会话自审 + 台账登记。
> 自审方法：逐条核 v1 引用证据的真实性 + 用项目已定结论（R79/R81/R85）交叉验证逻辑自洽。
> 标注：【真机坐实】/【源码确定】/【推断·高/中/低】。

## 0. 主会话对 v1 证据引用的核验（先证真）

| v1 引用 | 核验方法 | 结果 |
|---|---|---|
| E4 j0C2 两态零命中 | grep `state2_raw.txt`: `j0C2 in/out`=0、`support second`=0，仅安装行 1 | ✅ 属实 |
| FORWARD 链 RESEND_FILE→P3 miss→copy skip th-prefix→pick=th_ | 逐行 grep | ✅ 属实（md5=`216b1f7ccd...`，与 216b 样本一致） |
| R81 反证：注入 fileId 前后 `field_filemd5` 逐字相同、仍模糊 | 读 `_r81_r80a_falsified_verdict.md:42-50` | ✅ 属实（`0ef205c1...` 两次相同，HitCacheType 3→1） |

⇒ v1 的**事实引用可靠**，问题只可能在推理层（尤其判5）。

## 1. 五判裁定

### 判1（hitCache 影子指标）— **认同**
【推断·高】v1 用 R81 反例否决「hitCache=1 = 命中原图秒传」成立：R81 注入 fileId 后 hit=1、exist=true 但对端仍模糊，故 1≠"原图秒传"。四行样本 `fileId非空⇔hit=1`、`fileId空⇔hit=3` 零例外，影子指标判定成立。
⚠️ 一处措辞需要注意（非推翻）：v1 §1.3-5 说 R82 的「3→清晰、1→模糊」方向"被推翻"。更准确的说法是 **R82 那段本就是无回包直采的推断、且方向无证，本轮 E1 首次直采回包后应以本轮为准**；但本轮同样没做常量数值取证，所以只能说「R82 方向标注无据、不予沿用」，不能说「R82 被反证为错」。判据纪律同样适用于 v1 自己。

### 判2（fileId 伴生非根因）— **认同**
【真机坐实 + 推断·高】R80(a)「补齐 fileId 仍模糊」+「回包 fileId 两态同为 194」双重证伪 fileId 为决定量，成立。决定量为回包内容身份 md5（态① ca47 / 态② 0ef205）方向正确。

### 判3（hitCheck 因果未闭合的诚实度）— **认同**
v1 明确标【推断·中·假设】「本轮只观测时间戳递增，未直接 hook setFilePath→native hitCheck」，诚实度足够。E4 零命中也已如实记。

### 判4（A/B 同源未锁定）— **认同**
两态 md5/pathTail 不同、方案要求的 `j2init ori=`/`m.a=` 三重锁本轮未落地、源图未做 `md5sum`，故「A/B 唯一自变量未验证」判定成立。补证动作（同源锁行 + 本地 md5sum + 同态开关翻转）正确。

### 判5（Q4 最小落点）— **推翻并修正**（核心）

**v1 主张**：`ok0.m1.e(tj/k)` before 改 `field_fullpath` 指向 36B 原图占位 → 让服务端认原图。

**推翻理由（两条独立硬证据）**：

1. **因果倒置**。【源码确定】R79 Q1/Q4：`stock` 清晰的因果链是「先 `B6` 从坏图 xml 读出 `cdnbigimgurl/aeskey/md5` → `mq/k.B8` checkExist 秒传命中 → `qf4/j0.C2` 建带原图 CDN URL 的新记录（localId=884519）→ 新记录的 `r0.e` 才是 36B 占位」。**36B 占位是秒传命中后的产物/记录占位，不是触发秒传的输入**。v1 把产物当输入，倒因为果。

2. **与 v1 自身前提自相矛盾**。v1 §4.3-2 自己写「请求 md5 由 mars native 从 `setFilePath(field_fullpath)` 的文件字节重算」。【源码确定 + 真机坐实】若把 fullpath 指向 36B 占位，native 重算的 md5 只会是**占位文件的 md5**，不可能变成原图 md5 `ca47f83e`。v1 自己的机制陈述直接否定自己的建议。

3. **与项目已定结论冲突**。R85 §3 明确：「任何 `exists/length/宽高/magic/md5sum` 组合都只能回答'这个文件不是原图'，不能凭空生成原图字节」。改 fullpath 指向不含字节的占位 = 同类错误（判别器当成字节源）。

**修正后的正确落点**（回到 R79 Q4 早已裁定的方向，本轮真机数据进一步坐实其必要）：

| 项 | 修正值 |
|---|---|
| 真正决定量 | **消息 xml 是否带原图 `cdnbigimgurl`/`aeskey`/`md5` + 是否触发服务端 checkExist 秒传命中建新记录**（R79 Q1/Q4；本轮 E3 `g9c hasBig=true bigLen=194` 坐实源坏图 xml 确有原图 URL，前提存在） |
| 落点（主） | 复刻 `B6`（`com.tencent.mm.ui.transmit.MsgRetransmitUI.B6` @0x62c98c）的 `g9.c(r0.u)` 解析 → 构造 `mq/k.B8` checkExist task（`task.f=qf4/j0`、`0x01a5-0x01c0` 填 fileId/aesKey/filemd5/mediaId）→ `qf4/j0.C2` 命中回调 → `b1.j6` 建带原图 URL 新记录。**这是 R79 Q4 已裁定、本轮 E4 零命中所暴露的缺口所在**。 |
| 落点（辅） | 确保 `f2.C2` 重建的消息 xml 真带 `cdnbigimgurl`（E3 `hasBig=true` 是源侧证据，重建侧需 E4+`f2.C2` 探针补采；R80(a) 已证只改头不够，改的是"新建哪条记录/重建哪串 xml"，不是改上传字段） |
| 明确不在落点 | ❌ 不改 `ok0.m1.e` 的 `field_fullpath` 指向占位（因果倒置，本判推翻）；❌ 不重复 R80(a) 改 fileId/aesKey 头（已证伪）；❌ 不改 hitCache/exist_whencheck（回包影子）；❌ 不改任何本地判据阈值（R85 已证无效） |

**为什么修正落点比 v1 更可能奏效**：它作用于**已观测到的真机差异上游**（E4 本轮零命中 = 我方根本没触发 checkExist 秒传建新记录；E3 证明源 xml 有原图 URL 可用），而不是作用于 v1 那个"36B 占位→native 重算 md5"注定失败的死路。

## 2. 最终一句话判定

**B2 命题仍待补证，且 v1 的推荐落点（改 fullpath 指向 36B 占位）已被推翻（因果倒置 + 自相矛盾），正确落点回到 R79 Q4：复刻 B6→mq/k.B8 checkExist 秒传→b1.j6 建带原图 cdnbigimgurl 的新记录。** 本轮真机坐实的是「源 xml 有原图 URL」「两态回包内容身份不同（ca47 vs 0ef205）」「fileId/hitCache 非决定量（R80(a) 反证）」；未坐实的是「E4 checkExist 命中建新记录」（本轮零命中）、「两态同源锁定」（A/B 未验证）、「态③出现」（未做）。补证顺序：①两态同源锁行 + 本地 md5sum（判4）；②复刻 B6 建新记录后 E4 `j0C2 exist_whencheck` 由零命中变 true；③开关翻转验 on 态 E1 md5≠0ef205 且对端肉眼清晰、off 回退模糊（互斥判据见 v1 §4.4）。

## 3. 本轮自审的三件套登记（对应台账）

- ① Pro 显式档 `custom-local:deepseek-v4-pro` 返回 402 Insufficient Balance，无产物。
- ② 降档 Flash 显式档 `custom-local:deepseek-flash` 同样 402；别名 `reasoning` 可跑但继承父会话（ Flash），档位不符。
- ③ 故取第三处置：主会话自审 v1，产出本 v2，推翻判5 并修正落点；证据引用已逐条核验为真。

[DONE ALL]
