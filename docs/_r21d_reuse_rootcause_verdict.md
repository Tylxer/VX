# R21d 角标错贴 —— 根因深挖裁决（fresh eyes · 只读）

## 目标
裁决 R21d「已阻止撤回下标乱了 / 角标贴到别的消息」的**真根因**。独立分析三条竞争链，给主次排序 + 源码实证/推断分档证据，回答 `hits=0 + 肉眼角标 12368 + bound=0` 如何自洽，逐条给出 6 个关键验证点结论、对真机可见落点的区分判据、修法 + 变异验证法、诚实边界。

## 输入（按顺序必读）
1. `docs/_r21d_badge_misplace_forensics.md`（Lite 根因取证：Q1 三候选、Q3 探针缺口、Q4 修法候选、Q5 升级判据）
2. `docs/_r21d_misplace_onsite_verdict.md`（现场只读取证）
3. `docs/_r21d_dump_enhance_note.md`（观测增强 E 的设计自述 + 诚实边界）
4. `docs/派发审计台账.md` 末尾「2026-11-27 22:25 装机后现场取证」+「22:2x 派发 Pro 根因深挖」
5. 源码 `wxgroup/app/src/main/java/com/lx/wxgroup/feature/HintBadge.kt`（bound WeakHashMap :141-142、talker 闸门 :368-370、onBind 贴角标 :426-435、apply/ensure :643-649、ensureSweep :594-599、sweep :605+）

本轮新增真机事实（增强 E 新包现场抓取）：
- `dump`: `force=0 rescued=546 via=bound rvCls=none bound=0`，逐条一条未打出。
- `stat`: `enabled=true msg=true hint=true rescued=546 bound=0 added=0 binds=27 chat=27 resolved=25 hits=0 missed=0 verifyFail=0 masked=0 lastMiss= talkerMismatch=0`。
- uiautomator: 前台确是错贴会话（`ChattingUI`，「史俊杰 a?」在），但此刻屏上无「已阻止撤回」/12368（角标瞬时不可见）。
- 结论：增强 E「dump 唯一定案 talker 空串放行」设计预期未达成（证伪 2 命中），不能拿它定案。

## 红线
- **只读**：可读任意源码/文档/台账/git history。唯一可写文件：本文件 `docs/_r21d_reuse_rootcause_verdict.md`。其余一律不改。
- **复位边界（绝对禁区）**：禁碰 adb/装机/设备/开关/让用户发图/gradle 之外的设备操作；禁 git 写操作；不改源码/文档/单测；不写模型路由字样。
- 每个子步完成写一行 `[DONE] <子步名>`；最后写 `[DONE ALL]`。
- 【推断】不得写成【已证】；可行性分析 ≠ 已实现 ≠ 已验证。

---

## 裁决总表（三条竞争链）

> 分档：`【源码实证】`/`【推断·高|中|低】`/`【证伪】`/`【UNKNOWN】`。
> 关键前提事实（22:25 现场，【真机实证】）：`hits=0 added=0 bound=0`，27 binds / 25 resolved，**本进程冷启（22:12）以来无任何一条 bind 命中台账、未贴过任何角标**。

| 链 | 结论分档 | 裁决 |
|---|---|---|
| ① 撞号 + talker 空串放行 | 漏洞存在=【源码实证·高】；对**本次可见错贴**的归属=【UNKNOWN·被削弱】；不能作为"贴到别的 cell"的已证根因 | 漏洞确实存在（`:368-370` + `AntiRevoke.kt:710` + `GroupDb.kt:177/262` + `talkerOf:533-536` 逐字核过）。但本轮 `hits=0` ⇒ 没有任何 bind 走到 talker 闸门，`forceSet` 也空 ⇒ **链①本次根本没运行**。21:28 的"摘要≠正文"四证同样能被残留机制解释，**不足以唯一定案撞号**。链①至多解释"角标内容从哪来"，不解释"为何贴到 a? 那条 cell"。 |
| ② itemView 复用残留 | **字面 GC 前提=【证伪】；现象方向=【推断·高】（机制须修正）** | "itemView 仍在 View hierarchy 被复用时 WeakHashMap key 被 GC"逻辑自相矛盾（见验证点 1），`bound=0` 不是 GC 造成。但"复用导致角标留在别的消息上"的**现象成立**，真机制是——**摘角标路径 `ensure(false)` 在 onBind 时 view 未 attach → `:664` 静默 return 且无重试（与贴路径 `apply` 的 post 重试 `:647` 不对称），而 `bound.remove` 已先把 view 清出登记 → sweep 不接管 → `bound` 空 → `ensureSweep():595` 停摆 ⇒ 残留角标永久挂在该 itemView 上**。这是本次可见错贴的**主因**。 |
| ③ 台账记错会话 | 作为独立错贴成因=【证伪/推断·低】；talker="" 写入=【源码实证】助因 | `RevokeRescue.record` 的 msgId/talker/origContent 全部来自**同一个 msgId 的 DB 行**（`:81 orig=queryOriginal(msgId)`、`:90-98`、`AntiRevoke:710 talker` 来自 `meta?.talker`、meta 由 `select isSend,talker from message where msgId=?` 反查 `:861/:901/:916`）⇒ 台账不会把 A 的摘要挂到 B 的 msgId。talker 写 `""` 只是给链①喂了"闸门跳过"的助因，不是独立错贴源。 |

### 主次排序（对"用户肉眼可见错贴"）
1. **主因**：itemView 复用残留，但机制须修正为"摘角标 `ensure(false)` 未 attach 静默失败 + `bound.remove` 先清登记 + sweep 只认 bound + `ensureSweep` 停摆"——【推断·高】，源码 `:664`/`:349-350`/`:606`/`:610-612`/`:595`/`:647` 支撑。
2. **次因（上游内容来源）**：链① 撞号 + talker 空串放行——【源码实证】漏洞存在，但**本轮 hits=0 未被触发**，只能解释"12368 这段文字属于谁"，不能解释"贴到 a? cell"。
3. **助因中的助因**：链③ 的 `talker ?: ""` 写入使核对闸门长期失效——【源码实证】，是链①的前提条件之一。

### 对 Lite「四证坐实撞号」的 fresh-eyes 修正
Lite 现场裁决用「摘要与正文不匹配 + 全屏无源正文 + talkerMismatch=0 + 角标确为模块所画」坐实撞号。但这四证**无法区分**两种来源：
- (a) 摘要来自另一会话的撞号消息（链①，贴的当下命中台账）；
- (b) 摘要来自**此前 bind 过、已滚出屏的另一条被救消息**，角标随 itemView 复用残留到 a?（主因）。

两者都满足"摘要不在当前屏/当前正文里"。`talkerMismatch=0` 在 (b) 下是**必然**（当前 a? 的 bind 没命中台账，压根没走到 `:368`），不是链①的特征证据。⇒ Lite 的"坐实"强度应下调为【推断·中】。

[DONE] 裁决总表（三链分档 + 主次排序 + Lite 四证修正）

---

## 三者自洽：`hits=0` + 肉眼角标「12368」+ `bound=0`

> 关键时间轴：21:28 现场（旧进程）`hits=4 added=3 bound=0`，肉眼角标 12368 在屏；22:12 装包+双冷启（**新进程，计数器全清零**）；22:25 现场（新进程）`hits=0 added=0 bound=0`，屏上无角标。

1. **`hits=0 added=0`（22:25 新进程）**：唯一写 `bound` 的是 `:428`，其前置是 `:353 hits++`（要求 `rescued!=null || forced`）。本进程 27 binds / 25 resolved 无一命中 ⇒ 没有一条走到 `:428`，也没有一条走到 `:643 apply()` ⇒ `added=0` 是**必然**。⇒ 22:25 这一刻，模块在本进程内**从未贴过任何角标**。
2. **`bound=0`**：由上条直接推出——从未写入即为 0；同时 `:330/:338/:349/:382/:390/:419` 六条闸门失败路径都会 `bound.remove(itemView)`，`:349` 更是每条"非被救消息"都执行（占 25 resolved 的大多数），`:610-612` sweep 对 detach 的 view 也 remove。⇒ `bound=0` = "本轮无命中 + 主动摘登记 + 新进程清零"，**与 WeakHashMap GC 无关**（见验证点 1/2）。
3. **肉眼角标「12368」与上述并存**：角标不是本进程贴的，它是**挂在 itemView 子树上的一枚 `MARK` TextView 残留**（21:28 旧进程贴的，或更早某次命中贴的）。解释残留为何没被摘掉：
   - `:349` 对复用后显示 a?（非被救消息）的 itemView 先执行 `bound.remove(itemView)`（清登记），再执行 `ensure(itemView,false,"")`；
   - 但 `ensure():664` 在 `!target.isAttachedToWindow` 时**直接 return false**，而 onBind 常发生在 view attach 之前（源码 `:709-711`、`:735-736` 及 `apply()` 的 post 重试 `:647` 都佐证作者知道这一时序）⇒ 摘 View 的调用**静默失败且无重试**；
   - view 随后被 attach 并显示 a?，角标 TextView 仍挂在上面；
   - 该 view 已被清出 `bound` ⇒ `sweep():606` 只遍历 `snapshot()=bound`，**永远扫不到它**；`bound` 空 ⇒ `ensureSweep():595` 直接 return ⇒ 定时器都不再调度 ⇒ 没有第二条路径会摘这枚角标。
4. **三者自洽结论**：`hits=0 added=0` 证明"这枚角标不是这次贴的"，`bound=0` 证明"sweep 这条摘角标链路已停摆且这枚 view 已不在其视野内"，肉眼角标证明"角标 View 作为 itemView 子节点独立存活、随复用漂移"——三者共同指向**复用残留**，与链①（本轮未触发）无关。

### 增强 E 打不出数据：削弱了谁、支持了谁
- **削弱链①**：增强 E 的唯一目标是抓 talker 字段定案"talker 空串放行"。它三路径全废（`rvRef` 未捕获 / ring 空 / bound=0），且 `hits=0` 意味着**没有任何 bind 命中台账**，即便 dump 正常也无 talker 可打。⇒ 链①失去唯一的现场取证手段，本轮更无法坐实。
- **支持主因（复用残留）**：`dump` 靠遍历 `bound/ring/rv` 取数，三路径全废本身就说明"当前没有活跃绑定、没有命中记录、列表弱引用失效"——这与"残留角标来自历史 bind、已脱离所有观测与清理链路"完全一致。`hits=0 + added=0` 是残留机制的特征指纹（角标不是当下的 bind 贴的）。
- **证伪 2 命中 ≠ 证伪链①**：复核的"证伪 2"是"摘要都能在 txt 找到 ⇒ 非撞号"，本轮是"摘要匹配不上但 dump 打不出"，属于**取证能力失效**，不是"链①被反证"。

[DONE] 三者自洽 + 增强 E 打不出数据的裁决意义

---

## 6 个关键验证点逐条结论

### 验证点 1：`bound` 的 key(itemView) 在复用显示别的消息时是否会被 GC？——【证伪链②GC前提】
- WeakHashMap 的 key 只被 `WeakReference` 持有（`HintBadge.kt:143-144`）。GC 回收 key 的**充要条件**是该对象已无任何强引用。
- itemView 要"被 RecyclerView 复用显示 B 消息"，必须仍被列表的 child 列表或 `RecycledViewPool` **强引用**（否则不可能被复用）。⇒ 此刻它是 strong-reachable，**不可能被 GC**，`bound` 条目必然还在。
- 反过来说：若 key 真被 GC，说明 itemView 已无任何强引用 ⇒ 它不在 View hierarchy、也不在复用池 ⇒ **不可能正在显示 B 消息**。
- ⇒ "itemView 仍在 hierarchy 被复用时 key GC → bound=0"逻辑自相矛盾。链②的字面前提**证伪**。
- 补充：`bound` 的值 (`Long msgId`) 是强引用，但 WeakHashMap 只在 **key** 被回收时清条目；key 不 GC ⇒ 条目不会凭空消失。`bound=0` 只能来自"从未写入/主动 remove/新进程"（验证点 2）。

### 验证点 2：GC 前提不成立时 `bound=0` 的成因（结合 added=0/hits=0）——【源码实证】
四条成因，本轮逐条判：
1. **从未登记（主因）**：`bound[itemView]=msgId` 唯一写入点 `:428`，其前置 `:353 hits++`。本轮 `hits=0 added=0` ⇒ 从未写。
2. **被 onBind 主动 remove**：`:330`（h9 null）、`:338`（msgId≤0）、`:349`（非被救消息，复用最常见路径）、`:382`（talker 不等）、`:390`（type 闸门）、`:419`（content 对账失败）——每条都是 `bound.remove(itemView)`。
3. **sweep 的 detach remove**：`sweep():610-612`，`!v.isAttachedToWindow` ⇒ `bound.remove(v)`（注意此处**只清登记、不摘 View**，见验证点 3）。
4. **进程重启后新实例**：22:12 双冷启，计数器与 `bound` 全部从零开始。
⇒ `bound=0` 由 1+2+4 解释充分，GC 既非必要也非充分。

### 验证点 3：`sweep()` 摘角标的依据？是否第二缺陷与错贴的共同根？——【源码实证·共同根成立】
- `sweep():605-621` = `snapshot()`（`:606`，而 `snapshot():623-632` 只遍历 `bound.entries`）。**它只处理 `bound` 里的 View，绝不遍历屏幕可见 View。**
- 对每个 `v`：
  - `!v.isAttachedToWindow` ⇒ 仅 `bound.remove(v)`（`:611`），**不调用 `ensure(v,false)`** ⇒ 不摘 View；
  - 否则 `ensure(v, show, text)`（`:619`），`show = forced || map[id]!=null`，开关关时 `map=emptyMap()` ⇒ `show=false` ⇒ 摘 View。
- `ensureSweep():594-599` 在 `bound.isEmpty()` 时直接 return（`:595`）⇒ 定时器不再调度。
- ⇒ **第二缺陷（关 show_hint 角标不消失）与错贴是同一个根的两面**：
  - 错贴：角标 View 所在的 itemView 因 `:349` 被清出 `bound`（且 `ensure(false)` 未 attach 静默失败没真正摘 View），脱离了 sweep 的唯一视野；
  - 关开关不消失：开关关时唯一的摘角标执行器是 sweep，但它只认 `bound`，`bound=0` ⇒ 无人执行；且 `ensureSweep` 停摆 ⇒ 也不会再调度。
- 这与 R21d §10.5 历史"双向 PASS"不矛盾：那次 `bound=2`，view 仍在 sweep 视野内。

### 验证点 4：talker 闸门 `:368-370` 空串放行真机是否可触发？——【源码实证】可触发，但本轮未触发
- **当前侧**：`talkerOf(h9):533-536` 反射 `getTalker()`，方法不存在/调用异常/返回非 String 均返回 `null` ⇒ `:368` 的 `!curTalker.isNullOrBlank()` 为 false ⇒ 跳过核对。
- **台账侧**：`AntiRevoke.kt:710` `RevokeRescue.record(id, talker ?: "", ...)`；`talker` 来源 `meta?.talker`（`:680`），meta 由 `select isSend,talker from message where msgId=?` 反查（`:861/:901/:916`）⇒ DB 行缺失/查询异常/talker 列空 ⇒ 写 `""`。`GroupDb.kt:177 talker TEXT NOT NULL` 只禁 NULL 不禁空串，`:262` 读出 `?: ""`。
- ⇒ 两侧空串在真机**完全可能**，漏洞是【源码实证】。
- **但本轮**：`hits=0` ⇒ 没有任何 bind 命中 `rescued()[msgId]` ⇒ `:368` 一次都没执行；`talkerMismatch=0` 在此语境下**不是漏洞的证据而是未触发的证据**。⇒ 链①对本次可见错贴【UNKNOWN·被削弱】。

### 验证点 5：三条链的修法与变异验证法
见后文「修法建议 + 变异验证法」专节（含逐条改法、反向对照、无误伤对照）。

### 验证点 6：角标此刻不在屏的所有可能，哪些证伪链②？——【推断】
| 可能 | 与残留主因的关系 | 能否证伪链② |
|---|---|---|
| (a) 滚动复用：带角标 itemView 滚出屏 | 角标随 view 离屏，符合"依附 View 而非数据" | 否，中性 |
| (b) sweep 主动摘了 | 若该 view 曾被重新登记进 bound 且 attach，sweep 可摘；但本轮 `hits=0` ⇒ 无登记，可能性低 | **唯一可能证伪链②的项**：若证实是 sweep 摘掉且重进会话角标确定性重现 ⇒ 转向链①；当前数据不支持 |
| (c) 用户切走 / 界面重建 ⇒ View hierarchy 销毁 | 残留 View 随之销毁，角标消失 | **否，反而支持残留**：若为链①数据驱动，重进同会话应重现 |
| (d) 角标随 itemView 滚出屏 | 同 (a) | 否 |
| (e) 22:12 冷启 / 重装新进程 | 新 View 层级无残留 + `hits=0 added=0` ⇒ 无角标 | **否，最强支持残留、削弱链①确定性**：冷启后同会话无重现 |
- 综合：此刻不在屏**不证伪链②**，其中 (c)(e) 反而支持"角标依附 View 生命周期"；能证伪链②的只有 (b)，但 `hits=0 bound=0` 使其在本轮几乎不可能。

[DONE] 6 个关键验证点（1 证伪 GC / 2 bound 成因 / 3 sweep 共同根 / 4 talker 可触发但未触发 / 6 角标离屏）

---

## 对真机可见落点的区分判据（下一步一锤定音抓什么）

> 红线：以下为**建议**，本子代理不碰 adb/设备/开关/装机/发图。增强 E 的 dump 三路径已证伪，**不能依赖它定案**。

### 判据 A（首选，最强）：错贴在屏那一刻的 `hits/added` 增量
1. 先 `op=logclear` 或记录 `stat` 基线；让用户进入**肉眼能看到错贴**的会话并停住。
2. uiautomator 抓错贴 item（角标 TextView + 正文），若能 `op=forward sub=msgsnap` 取该条 msgId 更好（避免长按改界面则用滚动）。
3. 立即再抓一次 `stat`，比较 `hits`/`added` 的**增量**：
   - **错贴在屏 + 对应 rebind 期间 `hits`/`added` 增量 = 0** ⇒ 角标不是这次贴的 ⇒ **复用残留主因坐实**（正是 22:25 的事实）。
   - **增量 ≥1**，且该 bind 的 msgId 与角标摘要来源 msgId 不同 ⇒ **链① 撞号 + talker 空串放行坐实**。
   - 增量 ≥1 且 msgId 相同（同一条被救消息，只是位置看着别扭）⇒ 回到锚点/cell 内分支（Lite Q1③，已排除跨 cell）。

### 判据 B（反向对照）：强制 rebind 同一 cell
把错贴 item 滚出屏再滚回（触发新 bind / 可能新 ViewHolder），立刻 stat + 截图：
- **残留主因**：若回 bind 的是非被救消息 a?（`hits` 不增）⇒ 角标**不再出现**（证明错贴依附旧 View，非数据驱动）；
- **链①**：角标在新 View 上**确定性重现**，`hits` 再增 ⇒ 数据驱动，撞号坐实。

### 判据 C（冷启对照，本轮已部分发生）
冷启后重进同一会话滚到同一位置：
- 当前 22:25 事实：`added=0 hits=0 bound=0` + 屏上无角标 ⇒ 已倾向残留；
- 若用户确认"冷启后错贴消失、滚动一会儿又出现" ⇒ 残留链定案（角标依附 View，随 binds 漂移）；
- 若冷启后**确定性重现**同一错贴且 `hits≥1` ⇒ 转向链①。

### 判据 D（助因取证，仅必要时机）：最小绑定时日志
增强 E 的 dump 取数失败，建议把 talker 观测从"dump 时遍历 View"改为"`onBind` **命中瞬间**打一行日志"（`curTalker / ledTalker / origContent 前 40 字 / msgId / show 决定`），不依赖 `bound/ring/rv` 捕获，`hits≥1` 时必出。据此判链①助因是否参与：
- `ledTk=<EMPTY>` 或 `curTk=<null>` + `tkSkip=true` ⇒ talker 空串放行参与；
- 两侧非空且不等却仍贴出 ⇒ 核对代码未生效（装包版本问题）。

> 一锤定音最小动作：**判据 A**（错贴在屏的瞬间抓 `stat` 增量）。它只需只读探针，不改码、不装机。

---

## 修法建议 + 变异验证法（均【可行性分析】，未实现/未验证）

> 所有代码行号基于当前源码；本子代理**不落码**，以下仅为方案。

### F1（主因·最小）：摘角标路径对齐贴角标的 post 重试
- **改法**：把六条闸门失败路径的 `ensure(itemView,false,"")` 与 sweep 中 detach 外的 `ensure(v,false,...)` 统一为一个 helper：`if (!ensure(view,false,text)) view.post { runCatching { ensure(view,false,text) } }`（完全对称于 `apply():643-649`）。解决 `:664` 未 attach 静默 return 导致"摘登记成功但摘 View 失败"。
- **变异验证**：构造"先 bind 被救消息贴角标 → 滚动使同一 itemView 复用于非被救消息 a?"。改前角标残留；改后角标消失。反向把 post 重试删掉 ⇒ 残留复现（语义变异必须变红）。

### F2（主因·根治）：摘角标执行器不依赖 bound
- **改法**：在 itemView `onViewDetachedFromWindow` / `onViewRecycled` 时直接按 `MARK` tag 摘掉该 itemView 子树里的角标 TextView；或 sweep 增加一条"遍历当前 attach 的 itemView 子树按 MARK 摘"的清理路径，不再只认 `bound`。
- **变异验证**：贴一枚角标后把其 itemView detach（滚出屏）⇒ 改前角标随 view 残留、改后被摘；反向去掉 detach 清理 ⇒ 残留复现。

### F3（第二缺陷）：开关关闭时确保 sweep 跑一次
- **改法**：`ensureSweep():595` 的 `bound.isEmpty() return` 改为"若开关关闭仍 post 一次 sweep（按空清单全摘可见角标）"，或把开关切换事件接入一次全量清理。
- **变异验证**：贴角标后 `kvset show_hint=false` 等 3s：改前不消失、改后消失；反向去掉强制 sweep ⇒ 不消失（变红）。无误伤：重新 `show_hint=true` 后被救消息角标能回来。

### F4（助因·链①）：收紧 talker 闸门
- **改法**：`HintBadge.kt:368-370` 改为"台账 talker 为空 或 当前 talker 取不到时**抑制角标**"（宁可漏画不误贴），即 Lite Q4-A。
- **变异验证**：造 talker="" 台账条目 + 当前会话同号消息：改前贴到相邻消息、改后不贴；反向恢复放行 ⇒ 又贴错（变红）。无误伤对照：正常被救消息（talker 两侧非空且相等）仍能贴出角标，避免"角标又没了"的漏画回归。

### F5（助因·链③，不推荐单独做）：台账补稳定 talker
- `AntiRevoke.kt:710 talker ?: ""` 若改成"拿不到就不记台账"会让**防撤回本体失效**（比角标错贴严重），风险【推断·高】，不推荐。F4 已在消费侧兜住。

[DONE] 区分判据（A hits增量 / B rebind / C 冷启 / D 绑定时日志）+ 修法 F1~F5 + 变异验证

---

## 三件套

### ① 一句话裁决
**主因 = itemView 复用残留（现象成立，但"WeakHashMap key GC"前提【证伪】，真机制是摘角标 `ensure(false)` 在 onBind 未 attach 时静默失败且无重试 + `bound.remove` 先清登记 + sweep 只认 bound + `ensureSweep` 停摆 ⇒ 角标随复用漂到 a? 那条消息）【推断·高】；链① 撞号+talker 空串放行是【源码实证】的上游助因，但本轮 `hits=0 added=0` 证明其未触发、不能定案本次可见错贴；链③ 台账记错会话作为独立成因【证伪】，其 `talker ?: ""` 只是链①的助因。**

### ② 证伪 / 升级条件（对肉眼可见角标落点）
- **升级到链①坐实**：判据 A 中错贴在屏且 `hits` 增量 ≥1，且 bind msgId ≠ 摘要来源 msgId；或判据 B 强制 rebind 后角标确定性重现且 `hits` 再增。
- **证伪主因（转向链①）**：判据 C 冷启后同位置错贴确定性重现且 `hits≥1`；或证实角标是 sweep 主动摘后由数据驱动重贴。
- **整链推翻**：关 `show_hint=false` 后角标不消失，且确认角标非 `MARK` TextView / 非模块所画（现场已证是模块所画，故未推翻）。
- **证伪链①**：判据 A 中错贴在屏但 `hits/added` 增量恒 0（本轮即此），或台账对应条目 talker 两侧非空且不等却仍被贴出。

### ③ 最小下一步 + 责任方
- **最小下一步**：判据 A —— 在肉眼见错贴那一刻同步抓 uiautomator + `stat` 增量（只读，不改码/不装机）。
- **责任方**：主会话/team-lead 执行只读取证（GO）；若增量 ≥1 指向链①，再议 F4 + 绑定时日志（D）；若增量 = 0 坐实残留，再议 F1/F2/F3。本子代理全程只读、未碰设备/开关/装包/git/源码。

---

## 诚实边界（UNKNOWN / 未证）
- 【UNKNOWN】21:28 肉眼那枚「12368」角标**最初是哪条 bind 贴的**（是链①撞号命中，还是正常被救消息命中后残留）——`hits=4` 的那批 bind 未留 talker/origContent 实读字段，增强 E 又未抓到 ⇒ 内容来源无法追。
- 【UNKNOWN】错贴两条消息 A/B 的 msgId 是否相同（未做 msgsnap）；撞号来自恢复/换机还是台账自然累积。
- 【UNKNOWN】onBind 执行时 itemView 的 `isAttachedToWindow` 真值（无设备实测）——验证点 3 的"未 attach 静默失败"是基于源码 `:664` + 作者多处注释（`:709-711/:735-736/:647`）的【推断·高】，非真机实证。
- 【UNKNOWN】角标此刻不在屏的精确触发（滚动 detach / 界面重建 / 冷启 / sweep）——验证点 6 给了全枚举与指向，但未现场逐项确认。
- 【推断·高】"复用残留为主因"是因果推理 + 源码实证支撑，**非现场一锤定音**；定案须判据 A。
- 【证伪】仅针对"链② WeakHashMap key GC"这一具体前提与"链③独立记错会话"；不扩大为"链①漏洞不存在"（它存在）或"残留机制已被真机证实"（未证实）。
- 本轮未跑 gradle / 单测 / 反汇编；未碰 adb / 设备 / 开关 / 装机 / 发图；未跑 git 写操作；未改任何源码/单测/其它文档。唯一写入文件 = 本文。可行性分析 ≠ 已实现 ≠ 已验证：F1~F5 均未落码、未上机、未单测。

[DONE] 三件套 + 诚实边界
[DONE ALL]
