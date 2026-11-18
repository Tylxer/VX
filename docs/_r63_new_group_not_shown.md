# R63 新建分组后会话列表不显示、冷启才有 —— 根因确认 + 最小修复方案

> 角色：r63-newgroup-probe（Pro 级只读分析）。本轮**未改任何代码、未跑 gradle、未碰 adb/设备**。
> 宿主：微信 8.0.48。模块：`wxgroup`。
> 判据约定（CLAUDE.md §四/§五 + `docs/开发回路速查.md`）：只认「用户能看见的事实」+ 源码行号 + 真机日志时间戳；
> 「A 推 B」的时序结论一律标 **[推断]** / **[需真机]**，不冒充 [已证实]。
> 证据等级：`[已证实]` = 源码/日志/设计定案直接可见；`[推断]` = 由已证实事实推得；`[需真机]` = 尚无日志锚点。

---

## 1. 结论摘要

**根因（一句话）**：不是「createGroup 没触发 postSync / 没写库」。三个入口都经 `refreshMembers()→refresh()→kickGconvSync()` 触发了 `postSync()`，wxgrp_ 伪会话行也已写进微信库（真机 `gcs stat`: own=3、`wxgrp_000000000000009c`/nickname=788）。真正原因是**两层**：① `postSync()` 默认 60s 节流，冷启兜底刚跑过、60s 内再建组时这次 sync 被**静默丢弃且无重试**，导致「新建那一刻」行可能还没写；② 即便写了行，R54 定案已证实**纯写库不会改微信内存会话列表**（`p3.getItem()` 读 `ConversationStorage` 内存缓存），`notifyDataSetChanged` 是「假修法」，只有**冷启重查库**才让行出现 —— 这一条是 R54 §3/§10.2 主动接受的设计代价。

**推荐修复（一句话）**：**最小且低风险**的一步是把「建组/成员变更」这条链路的 sync 改成 `postSync(force=true)` 绕过 60s 节流（1 行 + 保留 gconv 守卫，保证「新建瞬间行已落库」）；但**必须向主会话讲清：这一步只保证「写库即时」，仍不保证「不冷启就显示」**，后者需要 R54 §3.3/§3.4 的「宿主内存缓存失效」Hook，属另一个 Pro 级、需真机取证的高风险决策，不应塞进本轮最小修复。

> ⚠️ **推翻主会话 A1 假设**：`createGroup 后没有触发 postSync` 不成立。若按 A1 直接「三个入口各补一次 postSync」，是**冗余 no-op**（已在调用链上）+ 仍是普通 postSync（仍被 60s 节流）+ 仍不解决内存缓存 → 修完用户依旧「等一会也没有」。

> **两条硬约束的确定取舍（详见 B6/B7）**：
> ① **gconv=0 零差异**：`postSync` 裸补进 refreshMembers 会**确定破红线**（gconv=0 写 wxgrp_ 行 → 微信原生伪会话行与自绘分组行并存 + 搜索污染，R54 §7.2/§10.2）；必须把绕节流做在**带 gconv 守卫的 `kickGconvSync`**（`:652` 早退）内，gconv=0 零 GCS 写库。
> ② **绕 60s 节流**：选**调用方 `force=true`**，**不选** postSync 内部「分组表变化」旁路 —— 二者写库效果等价，但 force 把守卫留在唯一 choke point、零数据层改动、低风险；内部旁路稀释守卫职责且新增状态，对红线①更不利。
> ③ **不可消除的边界**：以上只修「写库即时」。**「UI 不冷启即时显示」本轮做不到**（R54 已证实直写库不改内存列表，仅冷启重查可见），需另立 §3.3/3.4 Hook。请主会话先定「即时」的定义（写库 vs UI 可见）再定范围。

---

## 2. A / B / C 逐条结论

### A. 根因确认

**A1. 「新建不显示 = 没触发 postSync / 没写 wxgrp_ 行」—— 推翻（主假设不成立）** `[已证实]`

- 三个入口都在 createGroup 后调用 `ConvFilter.refreshMembers()`：
  - `GroupBar.kt:1505-1519` `createGroup()` → `:1515 refreshMembers()`
  - `GroupManagerPage.kt:74-88` 新建按钮 → `:80 refreshMembers()`
  - `GroupPicker.kt:134-164` `apply()` → `:143 db.createGroup(newGroupName)` → `:161 refreshMembers()`（target 非空时）
- `refreshMembers()`（`ConvFilter.kt:673-687`）末尾调 `refresh()`（`:686`）；
  `refresh()`（`:620-642`）去抖块末尾调 `kickGconvSync()`（`:639`）；
  `kickGconvSync()`（`:651-654`）在 gconv 开时调 `GroupConvStore.postSync()`（`:653`）。
  ⇒ **postSync 在调用链上，主会话「没有 postSync」的前提不成立。**
- 真机日志 `wxgroup/_dbg_pull.txt:2-14`：`GROUPS id=156 name=788 members=1`；`gcs stat conv own=3`、
  `wxgrp_000000000000009c`（= 156 的 16 位 hex，`GroupConvPlan.usernameOf`）存在、
  `contact ...009c | 788`。⇒ **行已写库、nickname 正确**。主会话取证结论「数据层通、伪会话行有」与此一致。
- 全局 `postSync` 调用点仅 3 处（`grep` 已核）：`ConvFilter.kt:653`（kickGconvSync）、`:1497`（onDataChanged）、
  `EntryReceiver.kt:212`（`op=gcs sub=sync`，force=true）。

**A1 补充 —— 那「新建那一刻」到底缺什么？两层，都要讲** `[已证实]+[推断]`

- **层 1（写库时序，[推断] 强，需真机时间戳坐实）**：`postSync()` 默认受 60s 节流（`GroupConvStore.kt:72` `SYNC_THROTTLE_MS=60_000`；
  `:89-108` `:95-97` `due = force || 首次 || now-lastSyncMs>=60s`；**接受任务即推进 lastSyncMs，失败/未 due 都不重试**）。
  冷启时 `refresh()`/`install()`（`:926`）的 kickGconvSync 已把 `lastSyncMs` 推到当下 ⇒ 若用户在冷启后 60s 内建组，
  这次 postSync 被**静默丢弃**，行要等下一次冷启（lastSyncMs 重置为 MIN_VALUE，`:70`）才写。
  这正好解释「新建当时没有 → 等一会也没有（无重试）→ 冷启才有」。
  ⚠️ 本轮取到的 `_dbg_pull.txt` 是 23:59:43 的 `op=groups`/`stat` 快照（行已在），**没有 createGroup 当时到 sync 的时间戳**
  ⇒ 层 1 是 [推断]，落 [需真机] 待主会话用带时间戳的 create→sync 日志确认。
- **层 2（可见性，[已证实] = R54 定案）**：即便行已写库，R54 §3.2（`docs/_r54_gconv_design.md:124`）已证实
  `p3.getItem()` 读 `ConversationStorage` 内存列表（`readSnaps` → `wrapped.getItem(i)`，`ConvFilter.kt:1599-1615`；
  `conv_list.md:2`），**直接 SQL 写库不经过宿主 insert/update 路径 ⇒ 内存缓存没有新行**，「只 notify 是假修法」。
  R54 §10.2 最终裁决（`:480`）明确「冷启即可，不需要找 doNotify/observer 通知点」，
  并把「改名/成员变更下次冷启生效」写进产品提示（`:502-503`）。
  ⇒ **gconv 行「冷启才显示」是当前已接受的设计行为，不是回归 bug**。这是「新建不即时显示」的根因主体。

**A2. `ConvFilter:1497` 的 onDataChanged 为什么在新建分组时没触发？** `[已证实]（机制）+ [推断]（具体归因）`

- onDataChanged 由**挂在微信 `inner` adapter（p3）上的 DataSetObserver** 触发（`ConvFilter.kt:841-877`）：
  只有**微信自己** `inner.notifyDataSetChanged()`（收消息/置顶变化）时才回调。
- 新建分组只写**我方 GroupDb**（`db.createGroup`，`GroupManagerPage.kt:76` / `GroupBar.kt:1508` / `GroupPicker.kt:143`），
  我方 GCS 又用反射 `execSQL` 直写微信库（`GroupConvStore.kt:365-379`），**都不走微信自身的 insert/update 路径**
  ⇒ 微信的 observer 不会被触发 ⇒ onDataChanged 不跑 ⇒ `:1497` 的 postSync 也不跑。
- `:1490-1491` 注释「收消息 / 成员变更 / 分组增删改都会触发 inner observer → onDataChanged」是**事实性错误**：
  「成员变更/分组增删改」是我方写自己的库 + 直写微信库，不产生微信 inner adapter 的 notify。**这条错误注释正是 R63 的认知源头。**
- 区分主会话问的两种可能：**两者都是，但第二种更本质** —— 「微信库没动所以 observer 不响」是表象；
  本质是「我方 createGroup 只写自己的 GroupDb，GCS 直写库也不经过宿主的 notify 路径，所以微信 observer 无从触发」。
  `[需真机]`：hook `p3.notifyDataSetChanged` after 打栈（R54 §3 命令②，`_r54_gconv_design.md:154`），新建分组时日志应为 0 条。

**A3. `refreshMembers()` 对 gconv=1 有无副作用/意义？** `[已证实]`

- `refreshMembers()` 的两件事：重读 `cachedMembers`（`:674-680`）+ `membersVersion++`（`:681`）+ `NotifyMute.invalidateScope()`（`:685`）+ `refresh()`（`:686`）。
- 对 gconv=1：`rebuild()` 走**直通分支**（`:1332 if (groups.isEmpty() || gconvEnabled)` → `:1336 ConvLayout.build(..., enabled=false)`），
  **不插自绘分组行**；`readSnaps` 只读微信内存列表，不含新写的 wxgrp_ 行。
  ⇒ `refreshMembers()` 的 `refresh()` **对 gconv=1 的分组行显示零作用**；它唯一与 gconv 相关的意义是 `refresh()→kickGconvSync()→postSync()`（写库）。
- `membersVersion++` / `cachedMembers` / invalidateScope 在 gconv=1 下服务于**免打扰作用域与筛选态**（若用户曾筛选），
  不是「显示新分组行」。⇒ 结论：**不必在 gconv=1 下跳过 refreshMembers**（跳过会丢 postSync 触发与免打扰失效），
  但要清楚它对「让新行出现」本身无能；把它当成「新建后显示」的修复点是找错了对象。

**A4. 三个入口行为是否一致（都漏 postSync）？** `[已证实]`

- 三者**行为一致**：都经 `refreshMembers()` 间接触发 `kickGconvSync()→postSync()`；**没有哪一个「更少调用」**。
- `GroupPicker.kt:138` 那条（带新组名）同样在 `:161` 调 `refreshMembers()`（target/added/removed 任一非空即调），
  因此也走同一条 postSync 链。
- 差异仅在 `GroupBar.createGroup` 多一次 `refresh(bar)`（重绘横条），与会话列表写库/显示无关。
- ⇒ 「三个入口各补一次 postSync」的 (a) 方案是三处重复且冗余。

### B. 修复方案（最小、低风险）

**B5. 最小修复点选择** `[推断 + 设计裁决]`

| 方案 | 评价 |
|---|---|
| (a) 三个 createGroup 入口各补 `postSync()` | ❌ 冗余（已在链上）+ 普通 postSync 仍被 60s 节流 + 仍不解决内存缓存 ⇒ 用户问题仍在 |
| (b) `ConvFilter.refreshMembers()` 里统一补 `postSync()` | ⚠️ 比 (a) 好（单点收敛），但**普通 postSync 仍被节流**，且 refreshMembers 被成员增删/恢复备份频繁调用 ⇒ 增多次全表读却仍不解决可见性 |
| **(c) 推荐：在 `kickGconvSync()` 加 `force` 参数，`refreshMembers()` 走 force=true** | ✅ 1 处改动；保留 gconv 守卫；绕过节流保证「建组/成员变更瞬间行已落库」；sync 幂等 + 单worker线程 ⇒ 无重复写/竞态 |

**推荐 (c) 的具体形态与理由**：

```kotlin
// ConvFilter.kt:651-654 改为：
private fun kickGconvSync(force: Boolean = false) {
    if (!runCatching { Kv.getBool(Keys.GCONV_MODE) }.getOrDefault(false)) return  // 守卫原样保留
    GroupConvStore.postSync(force = force)
}
// ConvFilter.kt:673-687 refreshMembers() 末尾，在 refresh() 之后或之内追加一行：
kickGconvSync(force = true)   // 建组/成员变更无低频，强制即时落库
```

- 为什么 force：`postSync(force=true)` 绕过 60s 节流（`GroupConvStore.kt:95`），
  与 `EntryReceiver.kt:212`（`op=gcs sub=sync`）同款 —— 那是已验证的「立即灌库」先例。
  新建分组是低频用户操作，force 不会造成频率问题。
- 对其它调用 refreshMembers 场景的副作用评估：
  - **加/删成员**：本就该触发 sync（GCS 用成员会话算 group time，`GroupConvStore.kt:146-149`）⇒ force 是**正确**的，非副作用。
  - **恢复备份**：应据恢复后的 GroupDb 重写伪会话行 ⇒ force 正确。
  - **置顶/排序/重命名（走 refresh，不走 refreshMembers）**：不动 force 链；GCS 靠 250ms onDataChanged 或下次冷启更新，保持现状。
  - 代价：每次 refreshMembers 多一次 worker 线程的全表读（3120 行，`:135`）——低频操作，可接受；**不在主线程**（`:1493-1495` 已下沉）。

**⚠️ 必须同时讲清的边界**：(c) 只修「层 1（写库即时）」，**不修层 2（可见性）**。
若用户诉求是「不冷启也立刻显示新分组行」，那是 R54 §3.3/§3.4 的「宿主内存缓存失效 + adapter 通知」，
需 DexKit 反查 `ConversationStorage` 的 doNotify 类/混淆名 + 真机 hook 验证，**高风险、需单独开 Pro 决策**，
不应与 (c) 混为一轮「最小修复」。

**B6. gconv=0 零差异红线（红线①）—— 确定结论：`postSync` 裸补会破红线，必须保留调用方守卫** `[已证实]`

- `GroupConvStore` / `GroupConvPlan` **内部无 gconv 开关守卫**（`GroupConvPlan.plan() :67-82` 不读 Kv；
  `ownWhere() :89` 只按 `wxgrp_` 前缀限定影响面，不判 gconv）。守卫全在调用方：
  `kickGconvSync()` `ConvFilter.kt:652` + `onDataChanged()` `:1497`。
- **「gconv=0 写 wxgrp_ 行是否无害」的明确判断：有害，不是无害。** 依据 R54 设计定案：
  - `_r54_gconv_design.md:325-328`（§7.2）：关开关务必 `clean()` 库，因为「UI 已直通但库里还留着伪会话」
    的中间态会「列表同时出现原生伪会话行和自绘分组行」—— gconv=0 稳态下若库里有 wxgrp_ 行，
    微信原生渲染它们 + 模块 `ConvLayout` 自绘分组行（`ConvFilter.kt:1337-1347`）⇒ **两套分组行并存**，
    用户可见的重复行。
  - `_r54_gconv_design.md:505`（已知代价）：伪账号会出现在搜索里。
  - ⇒ `op=gcs sub=clean` 回滚工具的存在**恰是「残留有害、需显式清理」的证据**，不是「写库与开关解耦故无害」的证据。
- 因此：
  - ❌ **方案 (a)/(b) 裸补 `postSync()`**（无守卫写进入口/refreshMembers）：gconv=0 也会写 wxgrp_ 行
    ⇒ **确定破红线①**（重复分组行 + 搜索污染），不可取。
  - ✅ **推荐 (c)** 把 force 加在 `kickGconvSync()` 内部、守卫 `:652` 原样保留：
    gconv=0 时 `:652` 直接 return，**零 GCS 写库 ⇒ 红线①确定不破**。这是 (c) 相对 (a)/(b) 的核心优势，不只是简洁。
- 真机反向判据：gconv=0 → 新建分组 → `gcs stat` 的 `own` 必须纹丝不动、`rconversation` 无新 wxgrp_ 行；
  列表仍只有自绘分组行一套（`K7 ... layout` 含自绘行、无 wxgrp_ 原生行）。

**B7. 60s 节流与绕节流策略 —— 确定结论：选调用方 `force=true`，不选 postSync 内部「分组变化旁路」** `[已证实 + 设计裁决]`

- 事实：`SYNC_THROTTLE_MS = 60_000L`（`GroupConvStore.kt:72`）；普通 postSync 在距上次 sync <60s 时被丢弃且无重试
  （`:83-87,95-97`）。⇒ 普通 postSync **不能**保证「新建后即时」；这正是用户「等一会也没有」的节流解释。
- **两条候选对「gconv=0 零差异」的影响对比**：

| 候选 | 绕节流效果 | 对 gconv=0 零差异的影响 | 改动面/风险 |
|---|---|---|---|
| **A. 调用方 `force=true`**（kickGconvSync 加形参 + refreshMembers 传 true） | ✅ 本次强制 due，立即写库 | ✅ **安全**：守卫 `:652` 在 gconv=0 早退，postSync 根本不被调用；守卫单点、职责清晰 | 2 行、单一文件、不碰数据层；**低风险** |
| B. postSync 内部加「分组表变化」免节流旁路 | ✅ 同样立即写库 | ⚠️ **更危险**：守卫仍依赖所有调用方（kickGconvSync/onDataChanged）都记得判 gconv；旁路逻辑进数据层后，一旦未来任一调用方漏守卫，gconv=0 就会被旁路触发写库；且要新增/维护「分组变化」状态（hash/计数），引入新状态即新风险面 | 改 `GroupConvStore` 数据层 + 新状态 + 新判定；**中高风险** |

- **确定结论：选 A（调用方 force=true）**。理由：① 两者对「写库即时」效果等价；② A 把 gconv 守卫留在唯一 choke point（kickGconvSync :652），
  gconv=0 零差异由结构保证；B 把绕节流塞进无守卫的数据层、稀释守卫职责、增新状态，对红线①更不利且无额外收益。
- **必须同时讲清的不可消除的边界（关键，勿混淆）**：A 和 B 都只解决「**写库即时**」，**都不解决「UI 即时显示」**。
  R54 §3.2/§10.2（`_r54_gconv_design.md:124,480`）+ §8 真机表（`:465`「纯写库后 count 纹丝不动、UI 无新行；冷启后两行出现」）
  已证实：直写库不刷新微信内存会话列表（`readSnaps` 读 `wrapped.getItem(i)`，`ConvFilter.kt:1599-1615`），
  **只有冷启重查库能让行出现** —— 这是已接受的设计代价。
  ⇒ 若「新建后即时」指 **UI 上立刻看到新分组行**，则本轮任何 postSync 改动都**做不到**，
  需要 §3.3/§3.4 的宿主 `ConversationStorage` 缓存失效 Hook（DexKit 反查 doNotify 混淆名 + 真机验证），
  属另立 Pro 高风险任务。请主会话先明确「即时」的定义再定范围。
- 实现注：`refreshMembers()` 调用 `refresh()`（`:686`）会在 250ms 后经 `refresh→kickGconvSync()`（`:639`）再发一次
  **普通** postSync；它与 force 那次几乎同时排队，force 已推进 `lastSyncMs` ⇒ 普通那次 60s 内必被节流为 no-op，
  净效果=一次写库，无害。若嫌日志噪音，可让 `refresh()` 的 kickGconvSync 调用点显式传 false（非必须）。

### C. 判据与验证

**C8. 真机判据（正反 + 排除冷启依赖）** `[需真机 —— 交主会话执行]`

- **写库即时性正向**：打开 gconv → 冷启微信 → 立刻建「测试组」→ **不冷启**发 `op=gcs sub=stat`
  （force 的手动 sync 入口 `EntryReceiver.kt:212`）。预期：`own` 比建组前 +1，新 `wxgrp_<hex>` 与
  `contact nickname=测试组` 出现 ⇒ 证明「force 后新建那一刻已落库」。
  - 时间锚点：sync 全表读 3120 行 + 写库在 worker 线程，量级**亚秒级**；判据用「建组后 1~2s 内 stat 可见」，
    不是靠墙上时钟猜「等一会」。
  - 「等一会是多久」：当前无 force 时 = **不会自己来**（无重试），要等下一次冷启/某个微信 onDataChanged；
    这正是用户「等一会也没有」的语义 ⇒ 判据应判「无重试不出现」，而不是设一个等待上限。
- **可见性正向（诚实预期）**：按 R54 定案，**不冷启时新行的 UI 出现不是本轮 force 能修的**。
  真机应分别记录「stat 里行在」但「UI 未出现」，冷启后 UI 才出现 —— 这是 R54 已知代价，不是本轮回归。
  若主会话要求「不冷启也显示」，另立 §3.3/3.4 的 Hook 任务。
- **反向（gconv=0 红线）**：gconv=0 → 建组 → `stat` own 不变、无新行；列表仍走 `ConvLayout` 自绘分组行（`ConvFilter.kt:1337-1347`）。
- **变异点（证明不是靠冷启）**：把 `force=true` 改回普通 `postSync()` → 冷启后 60s 内建组 → stat own **不 +1**（被节流）
  ⇒ 证明 force 是必要因子；`force=true` 下同一场景 own +1 ⇒ 证明不靠冷启。
- **observer 归因变异**：hook `p3.notifyDataSetChanged` after，新建分组时应 0 条 ⇒ 证明 onDataChanged 未触发（A2）。

**C9. 单测 / 变异验证** `[推断]`

- 本轮改动是「一个布尔参数 + 一处 force 调用」，**不涉及 configStamp / 开关指纹** ⇒ 不需要新增「重建被短路」家族守门单测。
- 建议固化两条**真机判据**（写进 SOP/验收脚本）：① gconv=1 建组后 1~2s stat own +1；② gconv=0 建组后 stat own 不变。
- 可选纯逻辑单测：把 `postSync` 的 `due` 判定（`force || 首次 || now-last>=60s`）抽成纯函数做 3 例真值表
  （force 必 true / 首次必 true / 窗口内非 force 必 false），变异「删掉 force 分支」须变红。
  ⚠️ 抽函数会扩大改动面（动 GroupConvStore），若主会话追求最小 diff，可只保真机判据、不做此单测。

**C10. 风险与回滚** `[已证实 + 推断]`

- **重复写库**：不会。sync 幂等（`GroupConvPlan.plan :67-82` 按 username 差异产出 Delete→Update→Insert，
  已存在行只产 Update）；单一 worker 线程串行（`GroupConvStore.kt:58-61`），多次 post 排队不并发。
  `:83-87` 的注释也说明「接受即推进时间戳、失败不回滚」是为避免去抖忙循环。
- **竞态**：单 worker + wcdb 事务（`inTransaction :400-434`）保证我方不与宿主并发、两表不留半行。
  force 只是在 worker 队列里多排一个任务，不改变并发模型。
- **把已有 wxgrp_ 行搞乱**：不会。force 触发的是**同一套全量对账 sync**，对已存在行产 Update（repair/rename），
  不是全删重建；只有 `op=gcs sub=clean` 才全删。回滚 = 把 `force=true` 改回默认 + 恢复 kickGconvSync 签名，1 行，
  不动表结构、不动配置。极端情况用 `op=gcs sub=clean`（幂等、自检非本方行不变，`GroupConvStore.kt:192-227`）清场。
- **性能**：force 使每次 refreshMembers 多一次 worker 全表读（3120 行）；低频用户操作可接受，且不在主线程。
  若未来 refreshMembers 变成高频调用点，需重新评估是否改回「仅 createGroup 入口 force」。

---

## 3. 推荐实现方案（落点）

| 文件:行 | 改动 | 说明 |
|---|---|---|
| `feature/ConvFilter.kt:651-654` | `kickGconvSync()` 增加 `force: Boolean = false` 形参，`:653` 改为 `GroupConvStore.postSync(force = force)` | **保留 `:652` gconv 守卫**（红线①） |
| `feature/ConvFilter.kt:673-687` `refreshMembers()` 内 | 末尾追加 `kickGconvSync(force = true)`（或在 `refresh()` 调用前后） | 建组/成员变更后强制即时落库，绕开 60s 节流 |
| 三个入口 `GroupBar.kt:1505` / `GroupManagerPage.kt:74` / `GroupPicker.kt:134` | **不改** | 已经 refreshMembers 收敛，无需各补 |

- 开关守卫：复用 `kickGconvSync` 内 `:652` 的 `Kv.getBool(Keys.GCONV_MODE)`，gconv=0 不写库。
- 节流选择：`force=true`（理由见 B7）。
- 不碰：`GroupConvStore` / `GroupConvPlan` / `ConvLayout` / configStamp / 三个入口。
- **不在本轮做**：宿主内存缓存失效 Hook（R54 §3.3/3.4）—— 需单独 Pro 决策 + 真机取证。

---

## 4. 真机验证步骤（正反对照 + 变异点）

前置：`A`/`P`/`W` 变量沿用 `docs/_r54_gconv_design.md:145-149` 约定；gconv 开关已开（`kvset` → 冷启生效，`:508-510`）。

1. **基线**：`"$A" shell "$P --es op gcs --es sub stat"` 记 `own=N`。
2. **冷启**：`am force-stop com.tencent.mm` → `am start ...LauncherUI`（制造「冷启后 60s 内」的节流场景）。
3. **建组**：UI 新建测试组「R63T」；记日志时间戳 `MGR/BAR/PICKER create id=...`。
4. **写库即时性正向（改后）**：建组后 1~2s 内 `"$A" shell "$P --es op gcs --es sub sync"`（force 手动灌库）
   再 `stat` → 期望 `own=N+1` 且 `wxgrp_<hex>` + `contact nickname=R63T` 出现。**证明不靠冷启已落库。**
5. **可见性记录（诚实预期）**：不冷启直接看列表 —— 按 R54 定案，新行可能仍不出现；冷启后再看应出现。
   分别记 UI 现象与 stat，供主会话判断是否需要 §3.3/3.4。
6. **反向红线**：`kvset` 关 gconv → 冷启 → 建组 → `stat` own 不变、无新 wxgrp_ 行；自绘分组行正常。
7. **变异点**：临时把 `force=true` 改回普通 `postSync()`，重复 2~4 → 期望步骤 4 的 own **不 +1**（节流吞）
   ⇒ 坐实 force 是必要因子，恢复代码。
8. **observer 归因**：hook `p3.notifyDataSetChanged` after，步骤 3 建组时应 0 条 ⇒ 坐实 A2。

---

## 5. 风险与回滚

- 风险面：仅 2 行、1 个文件；不改数据层/视图层/开关/配置指纹。
- 重复写/竞态/搞乱已有行：见 C10，均不会（幂等 + 单 worker + 事务）。
- 回滚：删除 `force` 形参、`:653` 改回 `postSync()`、去掉 refreshMembers 里的 `kickGconvSync(force=true)`；无需清库。
  若真机写脏：`op=gcs sub=clean` 幂等清场（`GroupConvStore.kt:192-227`）。
- **未决风险（交主会话）**：若诉求含「不冷启即时显示」，本方案**不满足**，需要更高风险的宿主缓存 Hook，须另立任务。

---

[done] 根因确认 + 最小修复方案分析，产出本文件。所有结论指向文件:行号，时序归因均标证据等级。
