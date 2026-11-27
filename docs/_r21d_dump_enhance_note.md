# R21d 观测增强 E 落码说明（dump 唯一定案 talker 空串放行）

> lane=`r21d-dump-enhance`，档位=Pro（Hook/反射级改动），执行者=子代理。
> 唯一可改源码=`wxgroup/app/src/main/java/com/lx/wxgroup/feature/HintBadge.kt`，唯一文档产物=本文。
> 分档沿用权威输入：`【源码实证】`/`【推断·高|中|低】`/`【UNKNOWN】`；【推断】不写成【已证】；可行性分析 ≠ 已实现 ≠ 已验证。
> 硬口径：角标文案必须「从 View 的 TextView.text 实读」，不能是 `badgeText()` 按台账现算。

## 目标（唯一）

让 `op=hintbadge sub=dump` 能唯一定案「是否 talker 空串放行导致的撞号错贴」。

具体 = dump 每条可见 item 打出：`msgId`、`当前 item 的 h9.getTalker()`、`台账该 msgId 的 rescued.talker`、`台账 origContent 原文前 ~40 字`、**实际贴到 View 上的角标文案（从 View 的 TextView.text 实读）**，以及 `type/origType/pos`（可选）。

## 锚点（权威输入，按顺序已读）

1. `docs/_r21d_badge_misplace_forensics.md`（Lite 根因取证）：Q3「现有探针能力/缺什么」、Q4 候选 E、Q5「升级条件命中 ⇒ go 到落码观测增强」。
2. `docs/_r21d_misplace_onsite_verdict.md`：现场真机坐实撞号错贴；`stat` 实测 `bound=0`；升级判据命中；第二缺陷（`bound=0 ⇒ ensureSweep() return ⇒ sweep 停摆`）。
3. `docs/派发审计台账.md` 末段「2026-11-27 21:4x 用户『要』」：档位/红线/关键矛盾（dump 遍历 bound，bound=0 时逐条为空）。

## 红线（三档严格遵守）

- **进入边界**：唯一可改源码 `HintBadge.kt`（dump 及必要私有观测辅助）；唯一可写文档产物本文；如需单测仅 `wxgroup/app/src/test/java/com/lx/wxgroup/data/` 下配套；⛔ 不动 talker 闸门 336-337、不动 ensureSweep 562-567、不动 `GroupDb.kt`、不动 pos 换算、`apply()`/`onBind()` 贴摘逻辑一律不改（观测-only）；⛔ 不改其它任何源码/文档/单测。
- **返回边界**：先建本文骨架（目标/锚点/红线），再逐步 append；每子步写盘一行 `[DONE] <子步名>`；最后 `[DONE ALL]`；产物须含①核心矛盾裁决②改动清单（文件:行）③新 dump 输出格式示例④三闸结果⑤单测/变异验证或「无法单测」说明⑥诚实边界（UNKNOWN）；先写文件再回报；【推断】不写成【已证】；可行性分析 ≠ 已实现 ≠ 已验证；三闸在 `wxgroup/` 跑（定向单测/全量单测/`assembleDebug`），跑不通用 `|| echo` 记录；若写单测必须做有语义的变异验证（改了确实该让某测试变红），等价变异 0 红不算。
- **复位边界**：⛔ 禁碰 adb/装机/设备/开关/让用户发图/gradle 之外的设备操作；⛔ 禁 git（commit/push/checkout）；⛔ 现场探针 off、G1 状态不得触碰；⛔ 不装机（装包前交叉复核由主会话另派）；⛔ 不写入模型路由字样。

---

[DONE] 建立产物骨架（目标/锚点/红线）

## ① 核心矛盾的裁决（本 lane 的设计点）

### 矛盾复述（【源码实证】+【真机实证】）
- `dump()`（`HintBadge.kt:1032-1049`）遍历 `snapshot()`，而 `snapshot()`（`:591-600`）遍历 `bound`（`WeakHashMap<View,Long>`，`:141-142`）。
- 现场 `stat` 实测 `bound=0`，dump 只打 header、逐条行为空；现场靠 uiautomator 直接看 View 才定位到角标（现场裁决 §3）。
- ⇒ **单纯在现有 dump 循环里加字段，在错贴现场仍一行打不出来**。必须让观测数据不依赖 `bound` 存活。

### 三方案评估（可行性分析，均"只观测不改行为"）
| 方案 | 改动面 | 宿主性能/侵入 | bound=0 能否打出数据 | 满足"角标从 View 实读" | 裁决 |
|---|---|---|---|---|---|
| ① dump 时从宿主列表可见 child 直接反射解析 | `HintBadge.kt` 内新增：bind 钩子包装层捕获列表/adapter 的弱引用 + dump 时遍历可见 child（复用仓库既有 `F0/G0/getChildAdapterPosition/getChildLayoutPosition` 候选链，见 `ForceForward.kt:3046`） | 反射只在 dump（低频）发生；bind 路径只多存两个弱引用 + 一次 parent 链短走；零强引用钉住宿主 View | **能**：屏幕可见 child 由列表持有，与 `bound` 无关 | **能**：直接 `getChildAt` → 子树找 `MARK` tag 的 `TextView` → 读 `tv.text` | **选为主路径** |
| ② onBind 各分支写独立非弱引用有界环形缓冲，dump 打缓冲 | 需在 `onBind()` 内埋点（碰贴/摘逻辑所在函数），并维护强引用缓冲 | bind 热路径每帧多写一条；强引用 View 有轻微留存（需封顶）；且缓冲存的是"bind 当时打算贴的文案"，等于台账现算 | 能，但缓冲里是历史 bind 快照，未必=当前屏幕可见项；且文案非 View 实读 | **不能**（存的是 intend-text，不是 `tv.text`） | 不单独采用；仅吸收其"非弱有界缓冲"作兜底数据源 |
| ③ 新增 `sub=inject` 人为造撞号条目 | 需新探针 + 改台账写入 | 干预实验、非只读 | 无关 | 无关 | 超本 lane 范围（不改台账/行为） |

### 选定：**① + ②的"非弱有界环形缓冲"作兜底 = 混合方案**
- **主路径（①）**：在 `bindHook.afterHookedMethod`（钩子包装层，非 `onBind()` 本体）捕获聊天列表 View 与 adapter 的**弱引用**；`dump()` 主线程上从列表 `getChildAt` 枚举当前屏幕可见 child，对每个 child：
  - 位置：复用仓库坐实的混淆名候选链 `F0/G0/getChildAdapterPosition/getChildLayoutPosition`（`ForceForward.kt:2911-2932/3046`，classes12.dex 反汇编实证），ListView 形态退 `getPositionForView`；
  - `h9 = adapter.getItem(pos)` → `msgId/getTalker/getType`（沿用本类既有 `getItem/msgIdOf/talkerOf/typeOf`，**未改这些函数**）；
  - 台账 `hitMap[msgId]` 取 `talker/origContent/origType`；
  - **角标文案 = 从该 child 子树中 `tag==MARK` 的 `TextView.text` 实读**（硬口径，绝不调 `badgeText()`）。
- **兜底（②）**：bind 时把"命中台账/forceSet 的那些 itemView + 当场解析出的 msgId/talker/type/台账字段"写入一个**强引用、有界（24 条）**环形缓冲 `ring`（与 `bound` 完全独立）。当列表弱引用失效（如界面重建）时，`dump()` 改打该缓冲，仍能在 `bound=0` 时出数据；文案同样从缓冲里保存的 View 实读。
- **为什么这样能定案**：输出直接并排给出「当前 item 的 talker」与「台账该 msgId 的 talker」与「台账原文」与「View 上实际角标文案」。若某错贴 item 显示 `ledTk=<EMPTY>`（或 `<null>`）而 `viewBadge` 非空、`orig` 摘要与 `txt` 正文不同源 ⇒ talker 空串放行导致的撞号错贴**唯一定案**；`tkSkip=` 标志（两侧任一空白）由这两个实读字段派生，一眼可读。
- **三方案对比里为何不单用②**：`bound=0` 的精确成因【UNKNOWN】（WeakHashMap GC vs view detach，现场裁决 §5）；② 存的是 bind 当时"打算贴"的文案，恰好等于被本 lane 判定为"不能定案"的台账现算 `badge=`，不满足硬口径，且缓冲条目≠当前可见项。故②只做数据源兜底，文案一律回 View 实读。
- **零行为改动**：`apply()/onBind()` 的贴/摘判定、talker 闸门 336-337、`ensureSweep` 563、`GroupDb`、pos 换算全部不动；新增代码只在钩子包装层 + `dump()` 及其私有只读辅助。

[DONE] 核心矛盾裁决（选①主路径+②非弱缓冲区兜底，文案从 View 实读）

## ② 改动清单（文件:行）

唯一改动源码：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/HintBadge.kt`（行号为落码后实测）：

| 位置 | 改动 | 性质 |
|---|---|---|
| `:24` `:28` | 新增 `import java.lang.ref.WeakReference` / `java.util.ArrayDeque` | 支撑观测引用与环形缓冲 |
| `:146-172` | 新增观测字段：`rvRef`/`adapterRef`（弱引用）、`ObsRec` 数据类、`ring`（强引用有界 24 条）、`RING_MAX` | 与 `bound` 完全独立 |
| `:288-289` | 在 `bindHook.afterHookedMethod` 的 try 内加一行 `runCatching { observeBind(param) }` | 钩子**包装层**，非 `onBind()` 本体；不改贴/摘判定 |
| `:1063-1170` | 新增 7 个私有只读辅助：`observeBind`/`findChatList`/`positionOf`/`badgeOnView`/`tkLabel`/`flat`/`obsLine` | 全部只读；`badgeOnView` 是硬口径落点（从 View 的 `TextView.text` 实读） |
| `:1176-1228` | **重写 `dump()`**：不再遍历 `bound`；主路径从宿主列表可见 child 取数，兜底走 `ring`，最后才退回 `bound` | 观测-only |

**未改（红线自检）**：talker 闸门 `:336-337` 原样；`ensureSweep()` `:562-567` 原样（第二缺陷"关 show_hint 角标不消失"已观测到、**不在本 lane 范围**）；`GroupDb.kt` 未动；pos 换算 / `apply()` / `onBind()` 的贴摘逻辑未动；其它源码/文档/单测未动；未碰 adb/设备/开关/装包/发图；未跑 git。

[DONE] 改动清单（文件:行 + 红线自检）

## ③ 新 dump 输出格式示例（一行假数据，字段含义见 obsLine）

```
HINTBADGE dump force=0 rescued=540 via=rv rvCls=ScrollControlRecyclerView bound=0
  rvChildren=7 adapter=q0
  u=1234567890123 s=1234567890123 pos=12 hit=true type=1 origType=1 curTk=1234567890@chatroom ledTk=<EMPTY> tkSkip=true orig=[wxid_abc:/12368 你转我这块钱] viewBadge=[已阻止撤回 · 12368] txt=[a?|史俊杰] host=xi
```

字段判读（对着用户肉眼落点）：
- `via=rv` + `bound=0` —— 证明不再依赖 `bound` 也能逐条打出（核心矛盾的验收点）。
- `curTk`（当前 item 的 `h9.getTalker()`）与 `ledTk`（台账该 msgId 的 `rescued.talker`）—— 本例 `ledTk=<EMPTY>` 即 talker 空串条目对核对免疫。
- `tkSkip=true` —— 由上面两个实读字段派生（两侧任一空白即跳过 talker 核对）。
- `orig=[...]` —— 台账原文前 40 字（换行折平）；`txt=[...]` —— 当前 item 可见正文。本例摘要 `12368` 不在 `a?` 里 ⇒ 摘要来自另一条消息。
- ⭐ `viewBadge=[...]` —— **从该 child 子树 `tag==MARK` 的 `TextView.text` 实读**（不是 `badgeText()` 现算）。

定案式：某错贴 item 同时满足 `ledTk=<EMPTY>`（或 `<null>`）+ `tkSkip=true` + `viewBadge` 非空 + `orig` 摘要与 `txt` 正文不同源 ⇒ **talker 空串放行导致的撞号错贴唯一定案**。

[DONE] 新 dump 输出格式示例

## ④ 三闸结果（均在 `wxgroup/` 目录，JDK21 + `--offline`，PATH 按文档清理以保 test worker）

| 闸 | 命令 | 结果 |
|---|---|---|
| 定向单测 | `./gradlew testDebugUnitTest --offline --tests "*HintBadgeLogicTest" --tests "*HintTextLogicTest"` | BUILD SUCCESSFUL（EXIT=0）；XML：`HintBadgeLogicTest` 31 条 / `HintTextLogicTest` 23 条 = 54 条，0 失败 0 错误 |
| 全量单测 | `./gradlew testDebugUnitTest --offline --rerun-tasks` | BUILD SUCCESSFUL（EXIT=0）；XML 汇总：68 类 / 1215 条，0 失败 0 错误 0 跳过 |
| 编译 | `./gradlew assembleDebug --offline` | BUILD SUCCESSFUL（EXIT=0）。首次编译曾报 `HintBadge.kt:1126 Return type mismatch`（`positionOf` 用 `getOrDefault` 推成 `Int?`），已改 `getOrNull() ?: -1` 修复后通过 |

> 说明：本 lane 未改任何判据逻辑类，定向/全量单测用于确认无回归；计数相对历史（45 类/925）的差异来自当前工作树既有测试集状态，与本改动无关（本改动未新增/删除测试）。

[DONE] 三闸结果

## ⑤ 单测 / 变异验证说明

**未新增单测，故未做变异验证（无需做）。** 理由（诚实边界，非硬造弱测试）：

- 本 lane 的新增逻辑全部是**只读字符串拼装 + 宿主 View/adapter 反射**（`dump()` 及其私有辅助），依赖真机 `TextView`/RecyclerView 实例，无纯逻辑（不依赖 Android/Xposed）可单测。
- 唯一两个纯字符串小函数 `tkLabel`（空值打标）/`flat`（换行折平）为 `HintBadge` 私有成员、且逻辑平凡；为其造测试需要反射私有方法或改可见性（超红线且是弱测试），按派发要求「不要硬造弱测试」，明确说明为"无法合理单测"。
- 真正的验收判据是**现场 dump 对着肉眼落点读字段**（见 ③ 定案式），属装包后真机观测，由主会话另派交叉复核（本 lane 不装机）。

[DONE] 单测/变异验证说明（未新增单测，观测为只读拼装无纯逻辑可单测）

## ⑥ 诚实边界（仍 UNKNOWN / 未验证）

- 【UNKNOWN】`bound=0` 的精确成因（WeakHashMap GC vs view detach）—— 现场裁决 §5 已记；本 lane 不追，只保证 dump 不依赖它。
- 【UNKNOWN】错贴两条消息 A/B 的 msgId 是否相同（未做 msgsnap，避免长按改界面）；撞号来自恢复/换机还是台账自然累积未知。
- 【UNKNOWN】本 lane **未装机、未跑真机**，③ 的输出为假数据示例；"改后 dump 能唯一定案"是**设计预期**，须主会话装包后现场验证才算【已证】。可行性分析 ≠ 已实现 ≠ 已验证：本 lane 已实现并通过编译/单测，但**真机定案未验证**。
- 【推断·高】talker 空串放行是唯一未被反证的解释分支（现场裁决 §4）；本观测增强只负责取证，不改判据、不修复。
- 兜底与限制：
  - `findChatList` 从 itemView 沿 parent 链按类名后缀找列表；若未来微信内嵌同名容器抢先命中（【推断·低】），`rvRef` 可能指向错误列表 ⇒ `via` 退回 `ring`/`bound`，此时 ring 仍能在 `bound=0` 出数据。
  - `positionOf` 复用仓库坐实的 `F0/G0` 混淆名候选链；若 11-16 后再次改名且原名也未命中 ⇒ 实时反解 `pos=-1`，但命中项会优先走 ring 记录（不依赖实时 pos）。
  - `ring` 只记命中台账/fake 的 bind（现场 hits=4，量极小），封顶 24 条；未命中的可见 item 在 rv 路径走实时反解。
  - 第二缺陷（关 `show_hint=false` 角标不消失，`ensureSweep` `bound=0` 停摆）**已观测到、不在本 lane 范围**，未改。
  - 现场探针 off 状态 / G1 状态未触碰；本轮未装机、装包前交叉复核由主会话另派。

[DONE] 诚实边界（UNKNOWN + 兜底限制）

## ⑦ 修正记录（装机前取证失真修复）

- **唯一源码改动**：`HintBadge.kt:1193`，`ring.firstOrNull { it.view === child }` → `ring.lastOrNull { it.view === child }`。其它源码/逻辑/判据/第二缺陷（ensureSweep / talker 闸门 / GroupDb / apply / onBind 贴摘）一律未碰。
- **理由（非风格调整）**：同一 `itemView` 在 RecyclerView 复用时会 bind 多次，`ring` 会累积同一 view 的多条 `ObsRec`；`firstOrNull` 取**最旧**那条，会用「旧 msgId/orig」拼上「当前 View 实读的角标/正文」，恰好**伪造**出「摘要与正文不同源」的错贴假阳性。本 lane 唯一产出是「唯一定案」，现场又是滚动后才见错贴，命中概率高；`lastOrNull` 取最近一次 bind（与当前 View 同帧）才对。
- **复核出处**：独立复核（装机前交叉复核）给出装机 GO，同时指出该取证失真风险；主会话读源码 `HintBadge.kt:1193` 亲核确认属实后下发此极小修正。

### 三闸重跑结果（修正后，干净 PATH，均在 `wxgroup/`）

| 闸 | 结果 |
|---|---|
| 定向单测 | BUILD SUCCESSFUL（EXIT=0）：`HintBadgeLogicTest` 31 / `HintTextLogicTest` 23 = **54 条**，0 失败 0 错误 |
| 全量单测 | BUILD SUCCESSFUL（EXIT=0）：**68 类 / 1215 条**，0 失败 0 错误 0 跳过 |
| 编译 | `./gradlew assembleDebug --offline` BUILD SUCCESSFUL（**EXIT=0**） |

> 环境说明：首次在默认 PATH 下跑定向单测，test worker 因 Git Bash PATH 污染 `java.library.path` 报 `finished with non-zero exit value 1`（环境问题，非代码）；按文档 `export PATH="/usr/bin:/bin:/c/Windows/System32:/c/Windows"` + `./gradlew --stop` 清理后三闸均一次通过。

[DONE] 修正记录（firstOrNull→lastOrNull）+ 三闸重跑（54 / 1215 / assembleDebug EXIT=0）

[DONE ALL]
