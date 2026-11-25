# R86c B5 arm 入口零生效 —— 独立 fresh-eyes Pro 根因 + 裁决（armroot）

> 角色：`r86c-b5-armroot`，**独立 fresh-eyes Pro 根因推理 + Hook 链路取值裁决**（非续用 fix/review2 lane）。工作根 `C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`。
> 性质 = 运行时零生效路径根因 + arm 入口 talker 语义裁决 + 最小修复规格 ⇒ **Pro 类**。模型 `HY4-FLASH-FREE`（用户会话临时规则，不写入文件）。
> 档位：【源码实证·高】/【真机实证·高】/【dex 实证·高】/【推断·高|中|低】/【UNKNOWN】；每条带文件:行号锚点；【推断】不写成【已证】。
> 硬纪律：**只读裁决**，唯一允许写 = 本文档；禁改源码/单测/任何其它文档，禁跑 gradle/`_junit.sh`，禁碰 adb/装机/设备开关，禁 git。
> 职责 = 找零生效路径 + 给可执行修复，**不为 GO 放宽判据、不虚构 BLOCKER**。判据口径（C2 第二次回包 exist false→true + md5Len 0→>0）与 C2 只读红线不变。
> 落盘策略（防打断）：骨架 → 每问写盘留 `[DONE Qn]` → 三件套 → 真 `[DONE ALL]`。**先写文件再回报**。

状态进度：
- [x] 骨架 + 证据锚点总表
- [x] Q1 arm 入口零生效根因链 + 第二根因（留 `[DONE Q1]`）
- [x] Q2 talker 语义裁决 + 最可信非空来源（留 `[DONE Q2]`）
- [x] Q3 最小修复规格 + 免改码路径评估（留 `[DONE Q3]`）
- [x] Q4 go/no-go + 最小下一步（留 `[DONE Q4]`）
- [x] 三件套（结论 / go-no-go / 最小下一步）
- [x] 真 `[DONE ALL]`

---

## 证据锚点总表（先落盘）

| 编号 | 事实 | 档位 | 锚点 |
|---|---|---|---|
| E1 | 首轮真机模块转发 B6X 零生效，全链唯一行 `armFromForward skip invalid talker/svrId`；无自建 checkExist task、无 B6X C2 回包 | 【真机实证·高】 | 台账 `docs/派发审计台账.md:5919-5926`；`_r86c_b5_first_try_raw.txt:283` |
| E2 | `armFromForward` 入口先判 `!enabled` return，再判 `talker.isBlank()||msgSvrId<=0` 打 skip 日志后 return | 【源码实证·高】 | `B6CheckExistProbe.kt:138-145`（:139/:140/:141） |
| E3 | 调用点 `armFromForward(ctx.sourceChat, ctx.msgSvrId)` 在外层 `if (ctx.imgLocalId>0 && ctx.msgSvrId>0)` 内 | 【源码实证·高】 | `FileSender.kt:158-169`（:159/:167） |
| E4 | 本轮 imgLocalId=885441、外层 gate 已过 ⇒ `msgSvrId>0`，跳过只因 talker 空 | 【真机实证·高 + 源码实证·高】 | `_r86c_b5_first_try_raw.txt:253/:276/:291`；`FileSender.kt:159` |
| E5 | `Snapshot.sourceChat = currentChatTalker()`；`currentChatTalker()` 读 `HostContext.activity().intent["Chat_User"]` | 【源码实证·高】 | `ForceForward.kt:1506/:2811/:1847-1851`；`ForwardLogic.kt:401` |
| E6 | `Chat_User` extra 在 LauncherUI Activity intent 恒空（挂在 Fragment 内层对象） | 【真机实证·高 + dex/源码实证·高】 | `docs/_r73_l3_review.md:58/:71`；`ChatOpenProbe.kt:22` |
| E7 | Snapshot 另有非空源会话前缀 `srcTalkerPrefix`（形如 `wxid_xxx:`，带尾冒号），本轮 = `filehelper:` | 【源码实证·高 + 真机实证·高】 | `ForwardLogic.kt:429`；`ForceForward.kt:1477-1485/:1512`；`_r86c_b5_first_try_raw.txt:254/:291` |
| E8 | `srcTalkerPrefix` 源自 `ref.talker`(=`h9.C0()`=源消息 `field_talker`)；`imgLocalIdOf` 用同一 talker 经 `d3.O8()→Q2(talker,msgId)` 真机取到 imgLocalId=885441 ⇒ 该 talker 非空且对该 storage 有效 | 【源码实证·高 + 真机实证·高(旁证)】 | `ForceForward.kt:1412-1426/:1479-1485`；`_r86c_b5_first_try_raw.txt:253` |
| E9 | stock B6 读源 xml 用 `msg.C0()`(源消息 talker) 作 `a3(talker,msgSvrId)` 入参；a3 dex = `a1.b(talker)` + `msgSvrId=?` 查库 | 【dex 实证·高】 | `_r79_stock_byte_source.md:157/:161`；`_r86c_q4b_refmd5_verdict.md:146`；`_r34_hdimg_anchor.md:263` |
| E10 | `d3.O8().a3(talker,msgSvrId)` 的 talker 是**表选择/源消息存储会话**键，不是目标会话 | 【dex 实证·高】 | `_r86c_q4b_refmd5_verdict.md:146`；`docs/_r86c_probe_forensics.md:44` |
| E11 | `task.field_talker → toUser`（目标会话）；真链 C2 的 `field_toUser=wxid_xlrbelf6hv0w22`(目标)。arm() 当前把同一 `talker` 同时用于 a3 读源 xml 与 `put(field_talker)` | 【dex 实证·高 + 真机实证·高 + 源码实证·高】 | `docs/_r86c_d3_dex_forensics.md:91`；`_r82_b2_netscene_forensics.md:49`；`_r86c_b5_first_try_raw.txt:325`；`B6CheckExistProbe.kt:184/:268` |
| E12 | 手工 b6step arm 需 `talker` + 19 位 `svrid`，广播侧只有 msgId ⇒ 手工入口也断 | 【源码实证·高】 | `EntryReceiver.kt:300-314`；`B6CheckExistProbe.kt:133-136`；台账 `:5923` |
| E13 | 现场双探针已 off 回读确认；未改码/未提交 | 【真机实证·高】 | 台账 `docs/派发审计台账.md:5924` |

---

## Q1 —— arm 入口零生效的根因链是否成立、有无第二根因

### Q1.1 根因链逐环核实：成立（【源码实证·高 + 真机实证·高】）

主会话初判完整成立，逐环闭合：

1. **环1 `currentChatTalker()` 恒空**【源码实证·高 + 真机实证·高】：`ForceForward.kt:1847-1851` 读 `HostContext.activity().intent.getStringExtra(EXTRA_CHAT_USER)`；`docs/_r73_l3_review.md:58` 已定案「`Chat_User` 挂在 Fragment 内部对象（3~4 层深），Activity intent 上恒空」，`:71` 记真机铁证 `gate closed ... talker= ref=true`。`ForceForward.kt:1506/:2811` 把该恒空值写入 `Snapshot.sourceChat`（`ForwardLogic.kt:401`）。⇒ `snap.sourceChat` 恒空。
   - 注意：本轮 source 会话是 `filehelper`（日志 `:254/:291`），但**无论源会话是什么，`sourceChat` 都恒空**（因为读的是 Activity intent 而非消息对象），这不是 filehelper 特例【推断·高；由 E5/E6 结构性推出】。
2. **环2 调用点把恒空值传入**【源码实证·高】：`FileSender.kt:167` `B6CheckExistProbe.armFromForward(ctx.sourceChat, ctx.msgSvrId)`，其中 `ctx` 就是 `ForwardLogic.Snapshot`（`FileSender.kt:135` `forwardCtx: ForwardLogic.Snapshot?`）。
3. **环3 入口安全降级跳过**【源码实证·高 + 真机实证·高】：`B6CheckExistProbe.kt:138-145` 先 `if (!enabled) return`（:139），再 `if (talker.isBlank() || msgSvrId <= 0L)` 打 `armFromForward skip invalid talker/svrId` 后 return（:140-143）。真机日志 `:283` `21:02:17.052 wxg-forward B6X armFromForward skip invalid talker/svrId` 与该行逐字对应。由于 skip 日志在 `!enabled` 之后，**能打出该行即证明 `enabled=true`**（现场 B6X on，台账 `:5920`），排除「探针没开」这一替代解释【源码实证·高 + 真机实证·高】。
4. **环4 判据无法触发**【真机实证·高 + 源码实证·高】：skip 后 `arm()` 未执行 ⇒ 无 `tj/k` 自建 task（`:262-276` 未达）、无 `dispatch`（`:316` 未达）、无自建 Proxy C2 回包（`:285-310` 未达）。GO 判据要求的「C2 第二次回包 exist false→true + md5Len 0→>0」（fix note §2.2:53、review2 Q4.2:104）**在当前自动 arm 路径下没有自建 C2 可供判读**。日志 `:325/:326` 的 `field_toUser=wxid_xlrbelf6hv0w22 / exist=true` 属真实 SCD 重发链，与 B6X 自建链无关（台账 `:5921` 已明确），不得拿来充当判据。

⇒ **根因链成立**：`currentChatTalker()` 恒空 → `snap.sourceChat` 恒空 → `armFromForward(ctx.sourceChat,…)` 的 talker 恒 blank → 全部转发安全降级跳过。这是静态两轮复核 + 三闸（文本断言锁）覆盖不到的**运行时零生效路径**【真机实证·高】。

### Q1.2 `msgSvrId` 是否有问题：无，确认跳过只因 talker 空（【源码实证·高 + 真机实证·高】）

- 外层 `FileSender.kt:159` `if (ctx.imgLocalId > 0L && ctx.msgSvrId > 0L)` 已过——真机 `:291` 打出 `FORWARD resend-file ok=true imgLocalId=885441`，证明该分支进入，`imgLocalId=885441>0` 且 `ctx.msgSvrId>0`【真机实证·高】。
- `armFromForward` 的 skip 条件是**或**关系（`:140`），日志只打合并文案、未区分哪个分量。既然 `msgSvrId>0` 已由外层 gate 证伪，则 skip 唯一可能是 `talker.isBlank()`【源码实证·高 + 推断·高（由 gate 语义推出，非日志直接分列）】。
- 附带旁证：`arm()` 内 `field_mediaId = "checkExist_$msgSvrId"`（`:264`）用 msgSvrId，说明其确为 19 位服务端 id；`Snapshot.msgSvrId` 来自 `ref?.msgSvrId`（`ForceForward.kt:1509` ← `h9.w0()/field_msgSvrId`，`ForceForward.kt:1415/:1688`），与 `sourceChat` 来自不同对象，**msgSvrId 不走 currentChatTalker 这条断链**【源码实证·高】。

### Q1.3 第二根因 / 其它 arm 不落地路径：本轮未观测到；列 3 条「修好后仍需盯」的潜在路径（不写成已证）

- **本轮唯一 B6X 行就是 skip（`:283`）**，之后无任何 `B6X arm try …` / `no_xml` / `dispatched` 行 ⇒ 本轮没有第二条同时生效的阻断路径【真机实证·高】。
- **潜在路径 P1（Q2 fallback 实参错配，【源码实证·高】代码层 / 真机未触发）**：`B6CheckExistProbe.kt:183-184` 循环 `for (m in arrayOf("a3","Q2"))` 却对两者都传 `callMethod(o8, m, talker, msgSvrId)`；KDoc `:176-178` 明写回退应为 `Q2(talker,msgId)`（本地 id），且 `ForceForward.imgLocalIdOf:1417-1422` 真机验证的是 `Q2(talker, ref.msgId)` 成功（`:253 src=Q2`）。⇒ 即便 talker 修好，若 a3 失败，fallback 会以 msgSvrId 当 msgId 调用 Q2，**可能拿不到 r0/u**。本轮因在 :140 早退未触及。修 talker 时应一并核对（见 Q3 R2）。
- **潜在路径 P2（talker 一双值复用，【dex 实证·高 + 源码实证·高】）**：`arm()` 把同一 `talker` 既用于 `a3(talker,msgSvrId)` 读源 xml（`:184`，语义=源会话，E9/E10）又用于 `put(task,"field_talker",talker)`（`:268`，语义=`toUser`=目标会话，E11）。两语义不同：stock B6 也是「源 talker 读 xml + 目标 wxid 作 toUser」。若把源 talker 填进 field_talker，checkExist 请求 toUser 可能与目标会话不符，**可能影响 C2 回包形态**【推断·中，真机未判】。详见 Q2.4 / Q3。
- **潜在路径 P3（下游失败，本轮均未达）**：`no_xml`（`:216-218`）、`no_fileId`（`:236-238`）、`no_fullpath fail-closed`（`:257-260`）、dispatch 双失败（`:317-320`）均在 arm 内部，本轮未执行；`resolveFullpath` 回退 3B 占位几乎必成功（fix note §1 处置2，【源码实证·高】），故 P3 触发概率低【推断·中】。

`[DONE Q1]`


## Q2 —— arm 需要的 talker 到底该是什么语义（决定性）

### Q2.1 判定：talker 语义 = **源消息存储会话**（`msg.C0()` / `ref.talker`），不是目标会话（【dex 实证·高】）

- `arm()` 取 xml 的链是 `d3.O8() → r0 → g9.c(u,"msg","")`，r0 由 `a3(talker,msgSvrId)` / `Q2(talker,…)` 取得（`B6CheckExistProbe.kt:175-215`）。a3 的 dex 实证（`_r86c_q4b_refmd5_verdict.md:146`）：`i=0x0005 a1.b(talker)` → `i=0x000a const-string 'msgSvrId=?'` → `i=0x0023 b1.w7(...)` 查库 → `r0.b(Cursor)`。**talker 参与 `a1.b(talker)` 的库/表选择，`msgSvrId=?` 是其内的行过滤** ⇒ talker 是把查询定位到「源消息所属存储会话」的键，不能用目标会话顶替。
- stock B6 以 `msg.C0()` 作该 talker（`_r79_stock_byte_source.md:157/:161`），而 `C0()→field_talker`（`_r29_g2_msg_object.md:245`）即源消息所在会话。⇒ 语义锁死为**源消息存储会话**【dex 实证·高】。
- `_r86c_probe_forensics.md:44` 佐证：`a3(talker,msgSvrId)` 与 `o2(imgLocalId)` 走不同查询键返回**两个不同对象**，但字段内容指向同一条图片记录 ⇒ 要拿源消息 xml 的 `u`，必须走带 talker 的 a3/Q2，不存在「只给 id 不要 talker」的等价捷径【dex 实证·高 + 推断·高】。

### Q2.2 候选逐条评估

| 候选 | 是否非空（本轮） | 语义是否匹配 a3 | 裁决 | 档位 |
|---|---|---|---|---|
| `ctx.sourceChat` | 恒空（Q1/E5/E6） | 名义上匹配（源会话）但恒空 | **排除**（当前零生效根因） | 【源码实证·高 + 真机实证·高】 |
| 目标 wxid `wxid_xlrbelf6hv0w22`（日志 `field_toUser=` `:325`） | 非空 | **不匹配**：a3 按 `a1.b(talker)`+`msgSvrId=?` 查源会话表，拿目标 wxid + 源 msgSvrId 极可能 r0=null 或取不到源消息 xml；目标 wxid 的正确落点是 `task.field_talker→toUser`（E11） | **不能作 a3 的 talker**（可作 field_talker，见 Q2.4） | 【dex 实证·高 + 推断·高】 |
| `snap.srcTalkerPrefix`（本轮 `filehelper:`，带尾冒号） | **非空**（`:254/:291`；`ForwardLogic.kt:429` + `ForceForward.kt:1481-1485`） | 形态=源会话 talker + 尾冒号，正是 `ref.talker`(=h9.C0()) 加 `:` 得来（`ForceForward.kt:1479-1485`） | **最可信非空来源**；**须去尾冒号**后用 | 非空【真机实证·高】；去冒号后等价于 a3 talker【推断·高，见 Q2.3】 |
| 从 `d3.O8()`/宿主反射直接按 msgSvrId 或 msgId 取 r0（不经 talker） | — | a3 必须传 talker（`a1.b(talker)`）；`o2(imgLocalId)` 走 `id=?` 但返回 ImgInfo2、不是含 xml 的 r0.u；g9.c 需要 r0.u | **静态不可达**（要源 xml 仍得经 a3/Q2+talker） | 【dex 实证·高 + 推断·高】，见 `_r86c_q4b_refmd5_verdict.md:93/:146`、`_r86c_probe_forensics.md:44` |

### Q2.3 最可信的非空取值来源 = `snap.srcTalkerPrefix.trimEnd(':')`（去尾冒号）

- **非空已被真机坐实**：日志 `:254` `FORWARD ctx srcMsgId=19757536 prefix=filehelper:`、`:291` `ctx=19757536/filehelper:/` ⇒ `srcTalkerPrefix` 本轮确为非空 `filehelper:`【真机实证·高】。
- **该 talker 对本 storage 有效有真机旁证**：`imgLocalIdOf`（`ForceForward.kt:1399-1427`）用 `talker = h9.C0() ?: ref.talker`（:1413-1414）经 `d3.O8()→Q2(talker, ref.msgId)` 真机取到 `imgLocalId=885441`（`:253 src=Q2`）。而 `srcTalkerPrefix` 正是同一 `ref.talker` 加尾冒号（:1479-1485）。⇒ 存在一条**已被真机验证非空且对该 d3 storage 查询有效**的源 talker，且它已随 Snapshot 传到 FileSender 的 `ctx`（`FileSender.kt:135`），**无需新增宿主访问**【源码实证·高 + 真机实证·高(旁证)】。
- **尾冒号处理**【推断·高，非已证】：`ForceForward.kt:1481-1485` 保证 `srcTalkerPrefix` 恰好带一个尾冒号（已是 `x:` 则不重复加）；而 `a3` 的入参应是不带尾冒号的 `talker`（stock 用 `msg.C0()`= `field_talker`，`_r29_g2_msg_object.md:245`，日志形态为 `filehelper` 无冒号）。⇒ 用 `trimEnd(':')` 归一化后再传 a3/Q2 是最稳选择。但 `ref.talker` 本身是否可能原生带尾冒号（KDoc :1479 承认「已带尾冒号则不重复加」）无法静态排除 ⇒ `trimEnd(':')` 在「原生无冒号」与「原生带冒号」两情形下都得到无冒号 talker，与 stock `C0()` 形态一致【推断·高】。
- **残余 UNKNOWN（不掩盖）**：① `a3(talker,msgSvrId)` 在本轮真机**未被直接调用成功**（imgLocalId 成功的是 `Q2(talker,msgId)`），a3 用该 talker 能否命中源记录属【推断·高】，由 dex（`_r86c_probe_forensics.md:44` 两对象同记录）+ stock B6 使用 a3（`_r79_stock_byte_source.md:161`）支撑，非本轮真机直接证；② 若 `h9.C0()` 与 `ref.talker` 形态不同，需以实际 `C0()` 为准——但两者都指向同一源消息会话，归一到无冒号后应一致【推断·中】。这两点由修复后的自检日志（`:187-204` 已打 `uLen`/`map`/`keys`）在真机一次性闭合，不必单列补探针轮次（详见 Q4）。

### Q2.4 决定性补充：`arm()` 里同一个 `talker` serves 两种语义，须拆开（【dex 实证·高 + 源码实证·高】）

- `B6CheckExistProbe.kt:184` `a3(talker,msgSvrId)` 的 talker = **源会话**（Q2.1）；`:268` `put(task,"field_talker",talker)` 的 field_talker → `toUser`（`docs/_r86c_d3_dex_forensics.md:91`、`_r82_b2_netscene_forensics.md:49`）= **目标会话**（本轮真链 `field_toUser=wxid_xlrbelf6hv0w22`，`:325`）。
- 两者**必须分开取值**：源 talker 用于读 xml；目标 wxid 用于 `field_talker`。把源 talker（`filehelper`）填进 field_talker 会使自建 checkExist 的 toUser 偏离目标会话【推断·中，可能影响 C2 回包形态，真机未判】。
- 修复规格须同时满足：a3 用 `srcTalkerPrefix.trimEnd(':')`，field_talker 用**目标 wxid**（FileSender 作用域内现成的 `wxid` 形参，`FileSender.kt:135/:148`）。这是 stock B6「源读 xml / 目标作 toUser」的同构复刻。

`[DONE Q2]`


## Q3 —— 最小修复方案（不改判据、不破只读红线）

> 本 lane **只出规格不出码**；源码改动由主会话随后另派实现 lane。以下行号基于当前落盘源码，实现时以实际 diff 为准。

### Q3.1 方案 A（推荐）：2 文件、双 talker 拆开、保住 stock 语义

| 序 | 文件:行 | 改成 | 理由 | 档位 |
|---|---|---|---|---|
| A1 | `FileSender.kt:167` | `B6CheckExistProbe.armFromForward(ctx.srcTalkerPrefix.trimEnd(':'), ctx.msgSvrId, wxid)` | 第 1 参给 a3 用源 talker（Q2.2/Q2.3）；第 3 参给 `field_talker` 用目标 wxid（Q2.4）。`wxid` 是 `send` 形参（`FileSender.kt:135/:148`），无需新增宿主访问 | 【源码实证·高 + 推断·高(go 后真机升格)】 |
| A2 | `B6CheckExistProbe.kt:138` | `fun armFromForward(talker: String, msgSvrId: Long, taskTalker: String = talker)`；函数体调用 `arm(talker, msgSvrId, taskTalker)` | 唯一调用点是 `FileSender.kt:167`，改签名无其它调用面 | 【源码实证·高】 |
| A3 | `B6CheckExistProbe.kt:156` | `fun arm(talker: String, msgSvrId: Long, taskTalker: String = talker = ...)`（写法以编译通过为准）；`:268` 改为 `put(task, "field_talker", taskTalker)` | a3（`:184`）仍用 `talker`=源会话；field_talker（`:268`）改用 `taskTalker`=目标 wxid。`taskTalker` 带默认值 ⇒ `EntryReceiver.kt:313` 的手工调用 `arm(talker,svrid)` **零改动兼容** | 【源码实证·高 + dex 实证·高】 |
| A4（强烈建议，随 A 一并做） | `B6CheckExistProbe.kt:176-184`；`FileSender.kt:167`；`B6CheckExistProbe.kt:138/:156` | 增可选 `msgId: Long = 0L` 参数，`FileSender` 传 `ctx.srcMsgId`；`:183-184` 循环按方法选参：`a3` 用 `msgSvrId`、`Q2` 用 `msgId`（`msgId<=0` 时回退 msgSvrId 以兼容手工通道） | 修 Q1.3-P1：`Q2(talker,msgId)` 才是真机验证过的回退参（`:253 src=Q2`、`ForceForward.kt:1417-1422`），当前代码给 Q2 传 msgSvrId 属实参错配 | 【源码实证·高】，真机触发【推断·中】 |

- **A4 可裁剪性**：若实现 lane 要求绝对最小 diff，A4 可暂缓（a3 首选成功则 fallback 不触发）；但 A1~A3 必须全做，否则 open「a3 失败即二次零生效」的口子。
- **不改其它**：`ForwardLogic.kt`/`ForceForward.kt` **无需改动**——`srcTalkerPrefix`（`:429` ← `:1481-1485`）和目标 wxid 都已在 Snapshot/send 形参中现成可达，这是最小改动面的关键。

### Q3.2 方案 B（退回路径）：只改 1 行、不换签名

`FileSender.kt:167` → `B6CheckExistProbe.armFromForward(ctx.srcTalkerPrefix.trimEnd(':'), ctx.msgSvrId)`，`arm()` 签名与 field_talker 写法不动。
- 优点：单行 diff、编译/单测面最小，足以让 armFromForward 不再 skip、真正进入 `arm()`。
- 缺点：`field_talker` 会变成源 talker（`filehelper`）而非目标 wxid，偏离 stock「toUser=目标会话」（Q2.4/E11），自建 checkExist 的 C2 回包形态可能受影响【推断·中】。判据口径不变，但 A/B 判读的可靠性不如方案 A。
- 定位：**兜底/快速定位用**（若只想知道 arm 能否落地），不作为最终修复提交。

### Q3.3 只读红线逐条核对（方案 A/B 均不破）

| 红线 | 核对 | 档位 |
|---|---|---|
| C2 Proxy 只读（不调 j6 / 不发消息 / 不写宿主字段） | 改动止于 `:138/:156/:184/:268` 与 `FileSender:167`；C2 handler `:285-310` 与 `logC2Shape(:404-417)` 完全不动 | 【源码实证·高】 |
| off/on 反向对照自变量唯一 | `armFromForward:139` `if (!enabled) return` 保持 ⇒ off 态零动作、on 态才 arm；talker 取值的改变不引入第二个开关自变量 | 【源码实证·高】 |
| `arm()` 内 `d3.O8()→g9.c` 只读 | A1/A3/A4 只换入参/字段落点，`:179-215` 的取 xml 链仍是只读反射，不写消息表/不写宿主字段 | 【源码实证·高】 |
| install 只找类不 hook | `:111-124` 不动 | 【源码实证·高】 |
| 不碰 native | `dispatch` `:336-355` 仍只走 `mq/k.B8` / `ok0/m1.e` Java 层；不 hook `startC2CUpload`（acc=0x109，`_r86c_d3_dex_forensics.md:285`） | 【源码实证·高】 |
| 判据口径不变 | C2 第二次回包 exist false→true + md5Len 0→>0、`md5Pre`/`hit`/`idVia` 只记录不裁决，全部不动（fix note §2.2、review2 Q4.2） | 【源码实证·高】 |

### Q3.4 「无需改码，仅靠真机补探针 / 换操作路径」能否让 arm 落地：**不能**（【源码实证·高 + 真机实证·高】）

- **换操作路径（从群聊发起转发使 srcTalkerPrefix 非空）不成立**：本轮源是 filehelper，`srcTalkerPrefix` **已经非空**（`:254 prefix=filehelper:`）；而失败入参是 `ctx.sourceChat`，它由 `currentChatTalker()` 决定，**与源会话类型无关、恒空**（Q1.1/E5/E6）。换群聊不会改变 `sourceChat` ⇒ armFromForward 仍走同一 skip【源码实证·高 + 推断·高】。
- **真机补探针（只打 sourceChat/msgSvrId 实值）不改变落地**：补日志只能再次确认 sourceChat 恒空、msgSvrId>0（已由 `:283`+`:291` 证），无法给 `armFromForward` 换入一个非空 talker——入参在 `FileSender.kt:167` 写死为 `ctx.sourceChat`，非运行时可注入【源码实证·高】。
- **手工 b6step 通道也救不了**：需 `talker` + 19 位 `svrid`，广播侧只有 msgId，KDoc `B6CheckExistProbe.kt:133-136` 自明「广播拿不到 19 位 msgSvrId，故以宿主 ctx 为准」；而宿主 ctx 的 sourceChat 恒空 ⇒ 手工/自动两路同断（E12、台账 `:5923`）【源码实证·高】。
- ⇒ **结论：arm 要落地必须改码（最少 `FileSender.kt:167` 一行，推荐方案 A 的 2 文件 3~4 行）**。补探针的价值只在「改码后 a3 仍拿不到 xml」时用于定位（Q2.3 残余 UNKNOWN），不作为免改码捷径。

`[DONE Q3]`


## Q4 —— go/no-go + 最小下一步

### Q4.1 裁决：**当前状态 NO-GO（对「用现包继续装机验证 B5」）**；条件性 GO =「改码后重装再验」

- **为什么不是「先补真机探针确认 talker 语义再定」**：talker 语义已由 dex（a3 `a1.b(talker)`+`msgSvrId=?`，E9/E10）+ stock B6（`msg.C0()`，E9）+ 本轮真机旁证（同一 talker 经 Q2 取到 imgLocalId=885441，E8）+ `srcTalkerPrefix=filehelper:` 非空（E7）**四路交叉锁定到【推断·高】**，唯一残余是尾冒号归一化与 a3 本轮未直接命中（Q2.3 UNKNOWN），而这两点**已内建在 arm 自检日志中**（`B6CheckExistProbe.kt:187-204` 打 `try a3 r0=…`/`u=blank`/`uLen`/`map`/`keys`），改码重装一次即可一并闭合，无需单独补探针轮次【推断·高】。
- **为什么当前 NO-GO**：现包的 `armFromForward` 对每一笔转发都 100% 走 skip（E1/E2/E5/E6），无自建 task、无自建 C2；在此包上重复装机/off-on 对照只能拿到**假阴性**，判据没有可判读对象，不是「信号弱」而是「信号不存在」【真机实证·高】。按硬纪律「不为 GO 放宽判据、不把假阴性写成 NO-GO 通过」，必须对现包判 NO-GO【裁决】。
- **条件性 GO 的前置（全满足才翻 GO）**：① 实现 lane 按 Q3 方案 A 落码（A1~A3 必做，A4 强烈建议）；② 单测按 Q4.3 改接线锁；③ 主会话跑三闸全绿；④ fresh-eyes 复核 diff 确认 Q3.3 六条红线不破；⑤ 重装后按 review2 Q4.2 口径跑同会话 off→on→off + stock 对照判读。任一不满足 ⇒ 维持 NO-GO。

### Q4.2 BLOCKER 清单

| ID | BLOCKER | 档位 | 必须处置 |
|---|---|---|---|
| **B1** | `armFromForward(ctx.sourceChat, …)` 的 `sourceChat` 恒空 ⇒ 自动 arm 对全部真机转发零生效，GO 判据无可判读的自建 C2 | 【真机实证·高 + 源码实证·高】E1~E6 | 改 `FileSender.kt:167` 入参为 `ctx.srcTalkerPrefix.trimEnd(':')`（Q3 A1）；这是本轮**唯一已在真机触发**的 BLOCKER |
| **B2** | `arm()` 把同一 `talker` 兼作 a3 源 talker 与 `task.field_talker`(=toUser)，两语义冲突：按 B1 只换 talker 会把源 `filehelper` 写进 toUser，偏离 stock「目标 wxid 作 toUser」，自建 checkExist 的 C2 形态可能受影响 | 【dex 实证·高 + 真机实证·高(toUser 语义) + 推断·中(后果)】E11/Q2.4 | 随 B1 一并拆成双 talker（Q3 A2/A3）；若实现 lane 选方案 B 单行修复，须书面接受该偏差并说明其对判据无害，否则 B2 不闭合 |
| — | 非 BLOCKER / 隐患：Q2 fallback 给 Q2 传 msgSvrId 而非 msgId（Q1.3-P1） | 【源码实证·高】代码层，真机未触发 | 建议随 A4 修；a3 首选成功则不触发，不单列 BLOCKER |
| — | 非 BLOCKER：review2 R1/R2/R3 文档 residue（test KDoc 旧判据、锚点误引、措辞） | 【源码实证·高】 | 并入现场口径下发前修正，不阻断本轮根因修复 |

### Q4.3 最小下一步（谁改什么、谁跑什么）

1. **team-lead（裁决/调度）**：对现包判 **NO-GO**，不重复装机、不翻开关；派实现 lane 按 Q3 方案 A 出码（本 lane 只出规格，不改码）。
2. **实现 lane（改 2 源码 + 1 单测）**：
   - `FileSender.kt:167` → Q3 A1（源 talker + 目标 wxid 双参；建议 A4 带 `ctx.srcMsgId`）；
   - `B6CheckExistProbe.kt:138/:156/:268/:183-184` → Q3 A2/A3/A4（a3 用源 talker、field_talker 用 taskTalker、Q2 用 msgId）；
   - **必须同步改单测接线锁**：`B6CheckExistProbeTest.kt:217-232` 的 `wiringPresent()` 当前把**错误调用**当契约锁死——`:232` 精确匹配 `"B6CheckExistProbe.armFromForward(ctx.sourceChat, ctx.msgSvrId)"`。⇒ 改成匹配新调用串，并**加一条反向断言**禁止 `ctx.sourceChat` 再作为 B6 arm 的 talker 入参（防回归）。这条锁正是「三闸全绿却真机零生效」的文本盲区根源，修复必须一并拔掉【源码实证·高】。
   - 红线自查按 Q3.3 六条逐条过；不改判据字段/常量、不扩 C2 写权限。
3. **主会话**：跑三闸（compile / 定向 `B6CheckExistProbeTest` / 全量 / assembleDebug），并复核单测总数与锁语义；全绿后派 fresh-eyes 再核 diff（重点 Q3.3 红线 + A1/A3 双 talker + A4 实参）。
4. **fresh-eyes（再核）**：确认 B1/B2 真消除、无新零路径、Q4.2 判据口径与 C2 只读红线未变；给装机 go/no-go 再裁决，不为 GO 放宽。
5. **装机现场（翻 GO 后）**：严格按 review2 Q4.2——双探针 SCD+B6X 全开、`b6step on`，同会话 off→on→off 三次回包 + stock 同步对照；判据=第二次真实回包 `exist` false→true 且 `md5Len` 0→>0 + `logC2Shape` 非 Ltj/f 空壳；md5Pre/`hit`/`idVia` 只记录不裁决；若 arm 自检日志仍 `no_xml`，按 Q2.3 残余 UNKNOWN 记录并交复核，**不得写通过**。
6. **现场口径（继续有效、不因本次修复放宽）**：C2 变化只证「三字段能启动 checkExist 秒传探测」，**≠清晰**；本探针只读、不发消息/不写宿主字段，**肉眼不会有任何变化**；清晰须后续 B6 全量功能 + 用户肉眼 A→B→A 终审（review2 Q4.2-4、fix note §6:162）。

`[DONE Q4]`


## 三件套（交付）

### ① Q1~Q4 一句话结论 + 档位

- **Q1**：根因链成立——`currentChatTalker()` 恒空→`snap.sourceChat` 恒空→`armFromForward(ctx.sourceChat,…)` 的 talker 恒 blank→全部转发 skip（【真机实证·高 + 源码实证·高】，E1~E6 / `B6CheckExistProbe.kt:138-145` / `FileSender.kt:167`）；`msgSvrId>0` 已由外层 gate+imgLocalId=885441 证实，跳过**只因 talker 空**【源码实证·高 + 推断·高】；本轮无第二生效路径，`Q2` 实参错配与 field_talker 双语义列为修后必盯隐患【源码实证·高 / 推断·中，真机未触发】。
- **Q2（决定性）**：talker 语义 = **源消息存储会话**（`msg.C0()`/`ref.talker`），非目标 wxid（【dex 实证·高】E9/E10）；最可信非空来源 = `snap.srcTalkerPrefix.trimEnd(':')`（本轮 `filehelper:` 非空【真机实证·高】+ 同一 talker 经 Q2 取到 imgLocalId 的旁证【真机实证·高】+ 去尾冒号归一化【推断·高】）；a3 必须带 talker，「只给 id 不经过 talker」静态不可达【dex 实证·高 + 推断·高】；`arm()` 另须把 `field_talker` 拆成目标 wxid（【dex 实证·高 + 源码实证·高】E11/Q2.4）。
- **Q3**：推荐方案 A（`FileSender.kt:167` 传源 talker+目标 wxid，`B6CheckExistProbe.kt:138/:156/:268` 拆双 talker，建议 A4 修 Q2 的 msgId 实参），2 文件 3~4 行、不碰 ForwardLogic/ForceForward（【源码实证·高 + 推断·高】）；方案 B 单行兜底但 field_talker 偏离目标会话【推断·中】；六条只读红线全保、【源码实证·高】；**免改码路径不存在**——换群聊/补探针/手工 b6step 都无法给 arm 换入非空 talker（【源码实证·高 + 真机实证·高】）。
- **Q4**：现包 **NO-GO**（零生效=假阴性，非信号弱）【真机实证·高】，条件性 GO = 改码+三闸+fresh-eyes+重装后按 C2 口径验证；不采用「先补探针再定」（语义已四路锁定，残余 UNKNOWN 由 arm 自检日志一次闭合）【推断·高】；现场口径不变（C2≠清晰、肉眼不变、清晰须 B6 全量+用户 A→B→A）。

### ② go/no-go + BLOCKER 清单

**当前对「用现包装机验证 B5」= NO-GO。BLOCKER 清单 = {B1, B2}（均须在改码轮闭合）：**
- **B1（真机已触发）**【真机实证·高 + 源码实证·高】：自动 arm 对全部转发零生效，判据无可判读自建 C2 → 改 `FileSender.kt:167` 入参（Q3 A1）。
- **B2（规格级，随 B1 处理）**【dex 实证·高 + 推断·中】：`arm()` 单一 talker 双语义冲突，若只换行参不拆，field_talker=`filehelper` 偏离目标 wxid → Q3 A2/A3 拆双 talker；选方案 B 须书面说明无害。
- 非 BLOCKER：Q2 的 msgId 实参错配（建议 A4）、review2 R1/R2/R3 文档 residue（现场口径下发前并入）。
- 现场口径（继续有效、不放宽）：C2 变化只证三字段能启动 checkExist 探测，≠清晰；探针肉眼不变；清晰须后续 B6 全量功能 + 用户肉眼 A→B→A 终审。

### ③ 最小下一步（谁改什么、谁跑什么）

1. **team-lead**：现包判 NO-GO，不重复装机/翻开关；派实现 lane 按 Q3 方案 A 出码（本 lane 只出规格）。
2. **实现 lane**：改 `FileSender.kt:167`（A1，建议带 A4 的 `ctx.srcMsgId`）+ `B6CheckExistProbe.kt:138/:156/:268/:183-184`（A2/A3/A4）；**必改单测** `B6CheckExistProbeTest.kt:217-232` 把锁死错误调用的 `wiringPresent()`（`:232` 精确匹配 `armFromForward(ctx.sourceChat, ctx.msgSvrId)`）改为新调用串并加反向断言禁 `ctx.sourceChat` 再入参——这是三闸全绿却真机零生效的文本盲区根源。
3. **主会话**：跑三闸全绿并复核锁语义，再派 fresh-eyes 核 diff（Q3.3 六红线 + 双 talker + A4）。
4. **fresh-eyes**：确认 B1/B2 真消除、判据口径与 C2 只读红线未变，给装机再裁决。
5. **装机现场（翻 GO 后）**：review2 Q4.2 口径——双探针全开 + `b6step on`，同会话 off→on→off + stock 对照，判据=第二次真实回包 `exist` false→true 且 `md5Len` 0→>0 + `logC2Shape` 非空壳；md5Pre/`hit`/`idVia` 只记录不裁决；arm 仍 `no_xml` 则记 UNKNOWN 交复核、不写通过。

真 `[DONE ALL]`

> 交付声明：产物 `docs/_r86c_b5_armroot_verdict.md`（本文本），证据锚点总表 + Q1~Q4 + 三件套全部落盘、真 `[DONE ALL]`。全程只读：唯一写文件 = 本文档；未改源码/单测/任何其它文档，未跑 gradle/`_junit.sh`，未碰 adb/装机/设备开关，未 git。所有【推断】未写成【已证】，每条带文件:行号锚点。职责 = 找零生效路径 + 给可执行修复：坐实 B1（talker 恒空零生效）+ B2（talker 双语义），给 2 文件规格与单测锁补丁，对现包判 NO-GO 而不为 GO 放宽判据、不虚构 BLOCKER（非 BLOCKER 项如实降级）。

