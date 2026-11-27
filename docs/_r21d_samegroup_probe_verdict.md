# R21d BLOCKER-2 同群撞号只读取证裁决（fresh eyes · 纯读证）

## 目标
用**已有证据**裁决 22:59 角标错贴到底是不是「同群内不同发送者 msgId 撞号」，还是「跨会话撞号」/「同群同发送者」/「复用残留」；并裁决发送者级判据（getContent 前缀 vs origContent 前缀）的可行性。

## 输入（纯读，禁碰设备）
- `docs/派发审计台账.md` 末尾三段（22:59 判据 A 结果 / 23:1x F3+F4 交付 / 23:2x NO-GO dex 亲核 BLOCKER-2）
- `docs/_r21d_verdict_a_ui.xml` + `_dbg_pull.txt`（22:59 dump ring 未删节原文）
- `docs/_r21d_reuse_rootcause_verdict.md`、`docs/_r21d_f3f4_fix_note.md`
- 源码 `HintBadge.kt` / `HintBadgeLogic.kt` / `RevokeRescue.kt` / `AntiRevoke.kt`
- 历史只读日志 `_dbg_raw.txt` / `_cache/grp.txt` / `_cache/_dbg_*.txt` / `_inst.txt`；DB `.tmp/wxgroup_pull.db`（rescued/friend_cache）；`_cache/dex/` 静态解析（仅只读 Python）
- git history（只读 `git log`/`git show`）

## 红线
- **进入**：只读任意源码/文档/台账/dex/git history；唯一可写文件 = 本文。
- **返回**：骨架 → 每子步 `[DONE]` → 真 `[DONE ALL]`；产物含①四问逐裁决②判定矩阵③补抓字段清单④发送者判据可行性+误伤⑤诚实边界。
- **复位（绝对禁区）**：⛔ 禁碰 adb/设备/开关/发图/装包/git 写；禁改任何源码/单测/其它文档；禁写模型路由字样。
- **分档**：【源码实证】/【dex 实证】/【真机实证】/【推断·高|中|低】/【UNKNOWN】。禁止把【推断】写成【已证】。

---

## 一句话总裁决（先看结论）

**22:59 不能坐实为「同群不同发送者 msgId 撞号」。** 已有证据反而指向相反方向：22:59 的 stat `verifyFail=0`（`_dbg_pull.txt:44`）证明文本命中那条 bind 的 `contentOk` **通过了** —— 而 `contentOk` 的 probe 恰是 `origContent` 的 `wxid_6714...` 前缀（`HintBadgeLogic.kt:20,67-77`），即 `h9.getContent()` **含同一 wxid 前缀** ⇒ **绑定消息与台账原文同发送者、同内容**。角标所在 itemView 可见的「赛博朋克 / 小胖的大哥…」是**另一条消息的内容挂在同一 View 上** = itemView 复用残留 / view-data 漂移，不是 msgId 撞号。BLOCKER-2 的前提【推断·中】**不能被已有证据支持**；发送者级判据在本次事件上**本已由 contentOk 等价执行且通过**，放行 ⇒ 即使实现也修不了这次错贴。

`[DONE] 骨架 + 一句话总裁决`

---

## ① 四个取证问题逐条裁决

### Q1. `ledTk=43809452760@chatroom` 是否 = 当前群「眼子钻粉总部5群」的群号？

**裁决：【真机实证·高】是。** 不需要猜，已有历史只读日志直接把二者并列：

- `_dbg_raw.txt:2163`：`23:24:57.772 Thread-30 GCONV   username=43809452760@chatroom | nickname=眼子钻粉总部5群` —— 微信会话表读出的 `username↔nickname` 映射。
- `_cache/grp.txt:219/221/224/225/226`：`CMI target wxid=43809452760@chatroom pos=33 name=眼子钻粉总部5群` / `CMI join …` / `PICKER shown for 43809452760@chatroom (眼子钻粉总部5群)` —— 多条独立日志同一映射。
- `_dbg_pull.txt:9,14`：22:56 两条被救消息的 `RRV record ... talker=43809452760@`（截断的 `@chatroom`），即台账写入时会话就是该群。
- 22:59 UI XML 标题 `眼子钻粉总部5群(499)`（`_r21d_verdict_a_ui.xml`，本轮解析 text 节点确认）。

⇒ **台账 talker 与当前会话是同一个群**，不存在「跨会话撞号」的会话级证据。

- dex/源码侧：群号↔群名是**运行时数据**（rcontact/conversation 表），不是 dex 字符串常量，纯静态 dex 解析无法给出该映射；本问靠真机日志闭合，不需 dex。
- 若仍要二次确认（补抓）：只读 `GCONV`/`op=groups` 再打一行 `username→nickname` 即可，但本轮已有铁证，非必需。

`[DONE] Q1 群号=群名裁决`

### Q2. `orig` 前缀 `wxid_6714767147311:` 是「发送者 wxid」还是「会话 wxid」？

**裁决：【源码实证 + 真机实证·高】是发送者 wxid（更准确：群消息 content 第一段的发言人标识），不是会话 wxid。**

证据链（逐环）：

1. **写入链**：`RevokeRescue.record(msgId, talker, …)`（`:78`）→ `queryOriginal(msgId)`（`:81`）：SQL `select type,content,isSend,lvbuffer,reserved,imgPath from message where msgId=?`（`:180`），`orig.content = getString(1)`（`:186`）→ `GroupDb.recordRescued(origContent=orig.content)`（`:90-98`）。`talker` 是**另一个参数**，来自 `AntiRevoke.kt:680 meta?.talker`（`select isSend,talker from message where msgId=?`，`:901`），`:710 RevokeRescue.record(id, talker ?: "", …)`。⇒ `origContent` 就是 message 行的 `content` 列原文，**前缀是 content 自带的，不是会话 talker**。
2. **源码结论**：`HintBadge.kt:423-425` 明写「微信群消息的 `content` **自带 `wxid_xxx:\n` 前缀**（真机现场 `orig=[wxid_0mnuc8zqglzt21:\n现在节目效果…]`）」；`派发审计台账.md:468`（R21c 判据设计）明写「`content` 索引串**第 1 段是消息发送者**，与 `"昵称"`（撤回者）不是一回事」。
3. **DB 实证**（`.tmp/wxgroup_pull.db`，153 条 rescued）：
   - 前缀与撤回者昵称稳定配对：`wxid_6714767147311 ↔ 俊俊运营`（6 条）、`wxid_8uf0b5dpx0sl21 ↔ 常有晴`（12 条）、`wxid_nfxgrcl90rqc22 ↔ 逍遥池`（9 条）……而同一批记录的 `talker` 列是 `43809452760@chatroom`（**与前缀值完全不同**）。
   - 私聊记录（talker = `wxid_...`）的 content **无此前缀**（如 `type=1` 私聊 sample `'123'`）⇒ 该前缀是**群消息专属的发言人段**。
4. **22:59 现场配对**：`_dbg_pull.txt:9` msgId=19810205 revoke=`"俊俊运营" 撤回了一条消息`，orig 前缀 `wxid_6714...`；`:14` msgId=19810203 revoke=`"亿口煎包" 撤回了一条消息`，orig 前缀 `wxid_yi1q...` —— 同一 msgId 的「撤回者昵称 ↔ content 前缀」一一对应。

**边界**：前缀**不一定以 `wxid_` 开头**。DB 中群消息还有 `wangxumar:` / `cx605830125:` / `liunicholas:` 等自定义 ID 形态（截图见 §④误伤风险）。故发送者判据必须按「**第一个冒号前整段**」抽取，不能写死 `wxid_`。

`[DONE] Q2 orig 前缀=发送者 wxid 裁决`

### Q3. 当前 cell sender「赛博朋克」的 wxid 是否 = `wxid_6714767147311`？

**裁决：【UNKNOWN】昵称↔wxid 映射在已有证据中缺失；但【推断·中-高】二者不是同一人。更关键：真正决定 22:59 性质的是「绑定消息的发送者」，而它已被 contentOk 证为 wxid_6714。**

- **查不到映射**：UI XML 只有 sender 显示名「赛博朋克」、头像 content-desc「张添翔头像」（本轮解析 cell 子树 `com.tencent.mm:id/bxt`=`赛博朋克`、`bpz` desc=`张添翔头像`）；`friend_cache`（3355 行，`.tmp/wxgroup_pull.db`）中对 `wxid_6714...`/`wxid_yi1q...` 以及 `赛博朋克`/`张添翔`/`亿口煎包`/`白也` **全部零命中**（该库快照为 10-27，可能未含群成员）。全项目 grep `赛博朋克` 仅 UI XML + 台账 22:59 段。⇒ 昵称↔wxid 映射【UNKNOWN】。
- **不同人的旁证**：`wxid_6714...` 在 DB 中稳定对应「俊俊运营」（6+ 条），22:56 现场 revoke 也写「俊俊运营」；cell 显示的是「赛博朋克」（头像 desc「张添翔头像」），同一群内一个人只有一个群昵称，三个不同名字同时出现 ⇒【推断·中-高】**赛博朋克 ≠ wxid_6714**。
- **⚠️ 决定性反证（指向「不是不同发送者撞号」）**：22:59 stat `verifyFail=0`（`_dbg_pull.txt:44`）。`contentOk` 只对 `origType=TYPE_TEXT(1)` 跑，probe = `flat(origContent).take(12)` = `wxid_6714767…`（`HintBadgeLogic.kt:20,71`），比对 `h9.getContent()`（`HintBadge.kt:427-431`，注释明写「两者同口径、都是完整原文」）。要 `verifyFail=0`，`h9.getContent()` **必须含同一 wxid 前缀**。⇒ **这条 bind 拿到的 h9 就是 wxid_6714 那条被救消息本身**，不是「另一个发送者的同号消息」。可见 cell 上的「赛博朋克 / 小胖的大哥…」只能来自 **View 复用后未更新/未摘的残留内容**，而非撞号。
- 补充时间线：`badge added #1/#2` 在 22:58:18/19，dump 在 22:59:52（相隔 ~94s，用户可见地滚动过）；ring 里 `u=19810205` 记录的 `txt` 是 **dump 当时**从 View 实读（`obsLine:1238`），不是 bind 当场快照 —— 这 94 秒足够 View 被复用于另一条消息（复用残留机制见 `_r21d_reuse_rootcause_verdict.md` 验证点 3）。

`[DONE] Q3 赛博朋克 wxid 映射 + contentOk 反证裁决`

### Q4. 判定矩阵 + 发送者级判据可行性（详见 §② §④）

见 §② 判定矩阵与 §④ 可行性分析。

`[DONE] Q4 入口（矩阵与可行性在 §② §④ 展开）`

---

## ② 判定矩阵（三情形 × F4 会话判据 / 发送者级判据）

> 前提：F4 现状 `curTk=<null> ⇒ talkerOk=false ⇒ 抑制`；若 B1 修好 `talkerOf` 加 `field_talker` 兜底，则群聊 `cur=群号`。

| 情形 | 事实是否命中 22:59 | 现状 F4（curTk=null ⇒ 抑制） | B1 修好后的 F4（群号 vs 群号） | 发送者级判据（getContent 前缀 vs origContent 前缀） |
|---|---|---|---|---|
| **A 同群不同发送者撞号** | ❌ 未被证据支持（contentOk 通过 ⇒ 同发送者） | 抑制 ⇒ 症状被"漏画"掩盖（非修复） | `cur=led=43809452760@chatroom` ⇒ `talkerOk=true` **放行 ⇒ 错贴照旧**（BLOCKER-2 原文担忧成立，但前提不成立） | 前缀不同 ⇒ 抑制 ⇒ 能修（但该情形本次未发生） |
| **B 同群同发送者 / 同一条被救消息（22:59 实际）** | ✅ contentOk 通过 ⇒ 命中本行 | 抑制 ⇒ 正常角标被误杀（全量黑屏风险 B1） | 放行 ⇒ 数据层正确贴出；**但 View 复用残留使角标仍挂在显示别的消息的 cell 上 ⇒ 肉眼错贴照旧** | 前缀相同 ⇒ 放行 ⇒ **同样修不了 View 残留**（本已等价执行并通过） |
| **C 跨会话撞号（不同群/私聊）** | ❌ Q1 已证 ledTk=当前群，排除 | 抑制 ⇒ 症状被掩盖 | `cur≠led` ⇒ 抑制 ⇒ 修好 | 前缀不同 ⇒ 抑制 ⇒ 通常修好；但同发送者跨会话、私聊无前缀时**漏检/误伤**（见 §④） |

**矩阵结论**：
1. BLOCKER-2 的放行链（B1 修好后 F4 放行导致错贴照旧）**逻辑上成立**，但它依赖的前提 A（同群不同发送者撞号）**未被证据证实**，而现场数据（verifyFail=0）**指向 B**。
2. 在 B 情形下，**F4 和发送者级判据都修不了肉眼错贴** —— 因为二者比的是数据身份，而错贴在 View 层（复用残留）。F4 现状只是靠 `curTk=null` 把角标全部抑制（漏画）才"看不见错贴"，这正是 BLOCKER-1 的全量黑屏，不是修复。
3. ⇒ **发送者级判据不能作为 BLOCKER-2 的解药**；它既解不了本次错贴，又引入新误伤面。

`[DONE] 判定矩阵`

---

## ③ 一锤定音补抓字段清单（本代理不抓，交主会话/用户）

> 只读探针能拿到的：dump ring 已抓 `origContent`（台账）、`viewBadge`（View 实读）、`txt`（View 实读）、`curTk/ledTk`。**缺的是 bind 当帧的 `h9.getContent()` 与 View 身份/当帧可见文本** —— 这正是区分 A/B/C 的决定性字段。

**最关键一项（单点定案）**：
> **抓命中 bind 当帧的 `h9.getContent()` 全文（含 wxid 前缀）并与 `origContent` 逐字比对。** 相同 ⇒ 同发送者同消息 ⇒ 排除撞号，转 View 复用残留；不同 ⇒ 真撞号。现有 `verifyFail=0` 已强烈暗示"相同"，补抓把它从推断变实证。

其余配套字段（按优先级）：
1. **bind 当帧的 `textsOf(itemView)` 快照 + 该 View 的身份标识（hashCode / `System.identityHashCode` / adapter pos）** —— 现有 `txt` 是 dump 时读的，会因复用漂移；要在 `observeBind` 命中瞬间（`:1139-1159`）同帧记下 view 上的 sender 标签（`bxt`）与气泡文本。
2. **肉眼那枚角标所在 cell 的真实 msgId**（`msgsnap` / 从 adapter `getItem(pos)` 取）—— 确认 cell 显示的「赛博朋克」消息 msgId 是否 = 19810205。≠ ⇒ 复用残留坐实；= ⇒ 撞号。
3. **`wxid_6714767147311` / `wxid_yi1qmyujq9z612` 的群昵称映射**（只读 `rcontact`/群成员表，或 `op=who` 类探针）—— 确认是否 = 俊俊运营/亿口煎包，及其与「赛博朋克」「张添翔头像」的关系。
4. **message 表该 msgId 的 `talker` / `isSend` 列**（`select isSend,talker from message where msgId=?`，只读）—— 确认消息归属会话。
5. **badge added 时刻到 dump 时刻之间的 rebind 次数与 View 身份变化**（`binds` 增量 + 每次 hit 的 view id）—— 量化复用漂移。
6. **图片命中（u=19810203）的 `getContent()`**：type=3 的 content 是 XML（DB 实证 `wxid_acap…:\n<?xml…`），确认前缀抽取规则在 XML 形态上成立。

`[DONE] 补抓字段清单`

---

## ④ 发送者级判据可行性 + 误伤风险

### 成立性分场景

| 场景 | content 前缀形态（DB/现场实证） | 发送者判据是否成立 |
|---|---|---|
| 群文本 type=1 | `wxid_xxx:\n正文`，getContent() 同口径（`HintBadge.kt:423-431`） | ✅ 成立：两边抽「首个冒号前」比对。**但 22:59 本已等价执行并通过（contentOk），对本次错贴零作用** |
| 群图片 type=3 | DB 实证 `wxid_acap…:\n<?xml…`，22:59 `wxid_yi1q…:/<?xml…` | ✅ 前缀存在、可抽；⚠️ content 是 XML，须只取冒号前段，勿解析 XML |
| 群语音 type=34 | `wxid_xxx:30478:0` | ✅ 可抽（`HintTextLogic` 已按段解析） |
| 私聊 | DB 实证 content **无 sender 前缀**（sample `'123'`） | ❌ **不成立**：两侧都无前缀 → 比不出身份。必须回退到 talker 判据（私聊 talker=对方 wxid，本身唯一标识发送者） |
| 自定义 ID 发送者 | `wangxumar:` / `cx605830125:` / `liunicholas:`（DB sample） | ⚠️ 可抽但**不能写死 `wxid_` 前缀**，否则这些人的消息恒被误判 |

### 误伤 / 漏检风险

1. **对本次错贴无效（最大风险）**：contentOk 已等价执行且 `verifyFail=0` 放行 ⇒ 发送者判据也会放行 ⇒ 肉眼错贴照旧，白改一轮。
2. **同发送者撞号漏检**：群内同一人连发两条、msgId 撞号时，前缀相同 ⇒ 无法区分 ⇒ 放行（仍错贴）。
3. **私聊无前缀**：强行比对会把所有私聊判为"无法核对"→ 要么全抑制（漏画），要么空前缀相等放行（漏检）。必须分流到 talker 判据。
4. **前缀形态多样**：写死 `wxid_` 会误伤自定义 ID 用户。
5. **与 F4 的耦合**：若按 BLOCKER-2 设想"修好 talkerOf + 发送者判据"，在 B 情形（实际）下两者都放行，View 残留仍在 ⇒ 需另有 F1/F2（摘角标路径对齐 post 重试 / detach 按 MARK 清理）才治本。

**裁决**：发送者级判据【可行性分析·未验证】，**不建议作为 BLOCKER-2 解药**；若仍要做，必须①按首冒号抽前缀②私聊分流 talker③与 F1/F2 组合，且先按 §③ 补抓 `getContent()` 确认撞号真伪再决定。

`[DONE] 发送者级判据可行性 + 误伤风险`

---

## ⑤ 诚实边界（UNKNOWN / 未证）

- 【UNKNOWN】「赛博朋克」/「张添翔头像」的 wxid 映射 —— 全项目只读证据中缺失，不能断言 = wxid_6714。
- 【UNKNOWN】22:59 命中 bind 的 `h9.getContent()` **原文全文未被记录**（dump 只打 `orig`/`txt`，不打 getContent）；「同发送者」结论由 `verifyFail=0` + `contentOk` 源码逻辑**反推**【推断·中-高】，非直读实证。按 §③ 补抓即可坐实。
- 【UNKNOWN】肉眼 cell「赛博朋克」消息的 msgId（未做 msgsnap），无法直接证明它与 19810205 不同。
- 【推断·中】BLOCKER-2「同群不同发送者撞号」**前提未被证据支持**；不扩大为「撞号漏洞不存在」（跨版本累积撞号漏洞在源码层确实存在，只是 22:59 未触发）。
- 【推断·高】View 复用残留（`ensure(false)` 未 attach 静默失败 + `bound.remove` 先清登记 + sweep 只认 bound）—— 引自 `_r21d_reuse_rootcause_verdict.md` 的源码实证链，本轮未现场逐帧验证。
- 【dex 实证·高】`getTalker()` 在消息类 `Ljj/m7;` 不存在、`talkerOf:558-561` 恒返回 null（引自 23:2x 复核，本轮未重新反汇编，仅引用）。
- 本轮**全程只读**：未碰 adb/设备/开关/发图/装包/git 写；未改任何源码/单测/其它文档；唯一写入 = 本文。可行性分析 ≠ 已实现 ≠ 已验证。

`[DONE] 诚实边界`

[DONE ALL]

