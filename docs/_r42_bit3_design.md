# R42 · bit3 写回方案 · 只读设计复核

> 复核人：bitchk（只读设计复核，不改源码、不跑 adb、不装包、不 commit）
> 范围：wxgroup / 微信 8.0.48（2588 GP）朋友圈防撤回
> 本文独立于实现者（bitfix）的写法，基于命题描述 + 取证材料做独立推演；父 turn 随时可能被打断，故每答完一节立即写盘并留 `[DONE-N]`。

## 命题（待复核的既定方案）
- `SnsGuard.kt` 的 before 钩子：微信要把 `SnsInfo.sourceType` 写 0 或纯降位时，把 `sourceType` 键从 `ContentValues` 摘掉 ⇒ UPDATE 不碰该列 ⇒ 库里旧值不变，实现「防撤回」。
- 位语义：`bit1=2` 时间线可见；`bit3=8` 「被好友主页相册列表拉过」的记账位；相册查询用含 bit3 的集合 `sourceType IN (8,264,10,266,12,268,...)`。
- 缺口：删除前没进过好友主页 ⇒ 该动态 `src=2` ⇒ 拦下后仍是 2 ⇒ 时间线在、但主页相册查不到（集合里没有 2）。
- 已拍板修法：拦截成功那次**把 sourceType 写回 ContentValues**，值由库里旧值补齐 `bit1+bit3`（典型 `2→10`），零额外 IO、无竞态。禁止事后补位 / 额外 UPDATE / 注入 / 扫描。

## 待答五节
1. **公式**：唯一合理的写回值表达式；为何 `src or 8` / `src or 2` / 常量 `10` 各自在哪个 case 上错；`src=0` 该不该补位（会不会把早已不可见的动态复活）；`src` 含高位（264/266）时为何不能只留低 4 位。
2. **触发面**：`applyBlock` 同时被「写 0 路径」和「非 0 纯降位路径」调用，`newValue: Int?` 是区分标志；两条路径上写回值是否都合理；`bits == null` 时为什么必须降级。
3. **after 形态校验**：`onSnsUpdateBlockAfter` / `SnsBlockProbe.isBlockedValues` 现在靠「ContentValues 里读不到 sourceType」判定甲生效、区分「拦住 / 没拦住」；写回值后判据失效 → 设计一个**互斥**新判据（两项不能同时为真）+ 真机验收计数/日志。
4. **副作用清单**：① 反向消融（只关开关）② 方案 B `execSQL & -9 → -1` ③ 灰标台账 `SnsDeletedTag.onBlocked(id, bits, want)` 的「丢时间线位」判据 ④ `left==0` 的 SKIP_EMPTY 分支 ⑤ 微信后续重试删除是否会再触发写回、值会不会越写越大 ⑥ 所有被拦下的动态都进相册列表这个代价是否可接受、能否只补「本来该在相册里」的那些。
5. **回滚路径**：真机发现主页重复/错序，改回旧行为需动哪几处、能否只靠开关关掉。

## 给主会话的验收清单
（为避免父 turn 中途被打断，本清单放在**文末**，见最后一节；五节推演在其之前）

## 取证基线说明

- 本文推演基于**本 turn 开头读到的源码快照**（`SnsGuard/SnsBitLogic/SnsBlockProbe/SnsDeletedTag/SnsDeletedTagLogic`）+ 4 份 docs。
- 期间一次符号核对显示 `SnsBlockProbe.kt` 已出现 `R42 bit3 写回改造` 的注释变更 —— 这是 `bitfix` 的**在途中间产物**，按任务纪律**未再读取、未据此调整结论**。本文所有「现状」描述指改造**之前**的既有行为，避免与并行实现互相污染。
- 位语义取自 `docs/conclusions/moments.md` R27 + `docs/_r42_bit_paths.md` §1：`bit1(2)` 时间线、`bit3(8)` 相册记账位、`bit2(4)` 私密、`bit5(32)` 广告、`bit7(128)` 折叠、`0` 死来源（`isDeadSource()`）；相册查询是 **`sourceType IN (8,264,10,266,12,268,...,478)` 的 64 值枚举**（不是 `& 8` 位测试）。

---

## 1. 公式：唯一合理的写回值

> **R42 修订记录（team-lead 三条更正，已并入本文；[DONE-1]…[DONE-5] 标记不变）**：
> ① **§3 前提纠正** —— `SnsBlockProbe.isBlockedValues(cv)` / `blockedCvs` 是**死代码**（全项目无调用点，只在 `SnsGuard.kt:218` KDoc 被提到）；真正的 after 校验是 `onSnsUpdateBlockAfter → hasPending() → finishAfterWrite`。写回值**不会破坏** after 校验，只让日志文案 `(键已摘)` 变成假话。§3 已整体改写为「`before/after` 读回值互斥自证」判据。
> ② **守卫裁决** —— 降级条件从「只排除 `B==0`」改为 **`(B & 10) == 0`**（以实现方为准），§1.4 写明分歧与理由，并加守门单测防后人改回去。
> ③ **闭包实证** —— `130|10=138` 在 64 值枚举内、全库 262,374 行 17 种真实旧值全部 IN 枚举，§1.5 的「需真机确认」担忧**已关闭**。
> ④ 新增反向对照调试键 **`antirevoke.sns_album_fix_off`**（只关补位、保留甲/A/B），写进 §4①/§5.2/验收清单 F。
> ⑤ **真机基线**（team-lead 刚取，同一好友 `wxid_xlrbelf6hv0w22`，`localFlag` 均为 2）：`snsId=-3397762123896778223` → `sourceType=10`（删前进过主页）；`snsId=-3397763077853081064` → `sourceType=2`（**删前没进主页，缺口样本**）。

### 1.1 表达式

设 `B = bits.src`（写库**前**点查到的旧值，`SnsBlockProbe.readBits` 已有，零额外 IO），`want` = 微信本次想写的值（写 0 路径 `0`，降位路径 `2/8/...`）。

```
W(B) = B or BIT_TIMELINE or BIT_ALBUM = B or 10        // 仅当 B 可读且 B != 0
降级   = 不写回，保持旧的「摘键」行为                  // B == null 或 B == 0
```

即：**把 `sourceType` 键写回 `ContentValues`，值 = 旧值按位或上 `2|8`，其余位一个不动**。典型 `2→10`、`8→10`、`10→10`（幂等）、`264→266`、`266→266`。

### 1.2 为什么「唯一」

四个约束同时成立，能同时满足的函数只有 `B | 10`：

1. **只吃旧值**：`bits` 是 before 钩子里唯一已经拿到的现场（`onSnsUpdateDrop` 点查一次、`onSnsUpdateBefore` 写 0 路径点查一次）。要看 `want` 之外的信息就得再查一次库/注入响应 ⇒ 违反「零额外 IO、无竞态、禁止注入/扫描」。
2. **必须同时含 bit1 与 bit3**：时间线判据 `(sourceType & 2) != 0`；相册判据是「值落在含 bit3 的 64 值枚举里」。写回值若缺任一位，就还有一格查不到 ⇒ 至少要把 `2|8=10` 或进去。
3. **不许清任何位**：`B` 里的 bit2(私密)/bit5(广告)/bit7(折叠)/256 及更高位是微信自己的状态，且相册 `IN` 枚举是按「基值 / 基值+256 / 基值+高位组合」成组列出的。清位会 (a) 让 `isAd()/isPrivate()/折叠` 语义错乱，(b) 把值踢出枚举集合 ⇒ 相册**仍然**查不到（等于修了个寂寞）。⇒ 只能用 `or`，不能用 `and` / 掩码 / 常量。
4. **最小且幂等**：`or 10` 是满足 2、3 的最小加法；`f(f(x)) = f(x)`，一次写回后即到不动点（§4⑤ 的关键）。或进任何额外位（`|32`、`|256`）都是替微信造它从没写过的值，不最小。

### 1.3 三个错误表达式各错在哪个 case

| 候选 | 错在哪个 case | 具体后果 |
|---|---|---|
| `B or 8`（只补相册位） | **`B=8` / `B=12` / `B=264` 等「有 bit3、缺 bit1」** 的旧值 | `8|8=8`、`264|8=264` ⇒ 写回值不含 bit1 ⇒ **时间线仍不可见**。`& -3`（清 bit1）已落地的那一刀补不回来。命题要求「时间线在 + 主页在」双可见，缺一不可。 |
| `B or 2`（只补时间线位） | **`B=2`（全库 159,241 行，本次缺口的主体）**，以及一切不含 bit3 的旧值（`130=128\|2` 折叠动态等） | `2|2=2` ⇒ 写回值不含 bit3 ⇒ **主页相册 `IN` 枚举里没有 2，仍然查不到**。这恰好是命题要修的那一格，`or 2` 在最高频 case 上原样失效。 |
| 常量 `10` | **`B=264/266/268/478/130/136` 等任何带高位或带其它语义位的旧值** | 丢掉 256/128/32/4 等全部高位：`264→10`、`266→10`、`478→10`。① 与 `& -9` 的 `IN (8,264,10,266,...,478)` 枚举错位（两个不同枚举项被压成同一个）；② `isAd()`(32)/`isPrivate()`(4)/折叠(128) 语义被抹掉，界面把广告/私密/长文当普通动态渲染；③ 在 `keys=18` 的整行 upsert 里主动写入一个微信从没写过的值，比「摘键」侵入性更大。 |

### 1.4 哪些旧值**不该**补位：守卫是 `(B & 10) == 0`，不是 `B == 0`（⛔ 裁决记录）

> **⛔ 分歧与裁决（务必留档，别让后人改回去）**：本文初稿主张「只排除 `B == 0`」，实现方主张「排除 `old & 10 == 0`」。**team-lead 已裁决以实现方为准**，本文据此更正。理由：
>
> - 时间线可见性的真判据是 `(sourceType & 2) != 0`（`moments.md` R27 §1），相册可见性是「值落在含 bit3 的 64 值枚举里」。`SnsInfo.isDeadSource() = (sourceType == 0)` 只覆盖「值为 0」这一种死法，**不等于「两处都不可见」**。
> - `B=4`（bit2 私密）、`B=128`（bit7 折叠）、`B=20/84/68` 等值：`B != 0` 所以「只排除 0」的守卫会放行补位，但它们 `B & 10 == 0`（bit1、bit3 全无）⇒ **时间线本来就查不到、相册枚举里也没有** ⇒ 这些行和 `B=0` 一样「本次写之前两处都不可见」。补成 `B|10`（4→14、128→138）会把它们点亮成可见 = **误复活**：以旧 `createTime` 重新插进时间线与相册，位置错乱（§5 的重复/错序正来源于此）。
> - 初稿的错在于把「早已不可见」错误地等价于 `B==0`；`(B & 10) == 0` 才是对「两处都不可见」的准确刻画。

**守卫定义**：

```
可写回 ⇔ B 可读 且 (B & 10) != 0     // 旧值至少含 bit1 或 bit3 之一
降级   ⇔ B == null 或 (B & 10) == 0  // 保持旧的「摘键」行为，不写回
```

- 真机全库 **262,374 行**里落在降级集合的 **6 种真实旧值**：`0 / 128 / 20 / 84 / 4 / 68`（team-lead 实证；其中 `B=0` 的 16,426 行见 `_r42_bit_paths.md` §1）。它们**全部**不写回、不复活、不改写。
- 可写回集合里的 11 种真实旧值：`2/8/10/130/136/138/22/6/86/70/12`，补位结果全部落在 64 值枚举内（§1.5 已实证关闭）。
- ⇒ 公式必须带 **`(B & 10) != 0` 前置守卫**；不满足时**降级为旧行为（纯摘键、不写回）**，库里保持旧值。降级与 §2 的 `bits == null` 走同一分支、同一计数，语义一致。
- ⚠️ 补位只发生在「这条动态本来至少有一处可见」的行上 —— 这正是「防撤回」的语义边界：**保住本来能看到的，不制造本来不能看到的**。

### 1.5 含高位（264/266）时为什么不能「只留低 4 位」

「只留低 4 位」= `B and 0x0F`（或再 `or 10`）。三种具体失败：

1. **丢 bit1**：`264 = 0x108 = 256+8` ⇒ 低 4 位 = `8`（只有 bit3、没有 bit1）。若写回 `8`，时间线判据 `(src & 2) != 0` 不成立 ⇒ **时间线仍然查不到**；`266` 低 4 位 = `10` 看似对，但 256 也没了。
2. **踢出枚举集合**：相册查询是 `IN (8,264,10,266,12,268,...,478)` 的**闭集枚举**，不是 `& 8`。枚举里 `264/266/268/.../478` 的 256 位和 478 里的 128/64/16 位是**区分不同枚举项的组成部分**。把 `478(256+222)` 砍到低 4 位得 `14`，把 `264` 砍成 `8`/`10` —— 得到的值要么不在枚举里 ⇒ 相册查不到（缺口没修上），要么与另一条本来不同的动态**塌缩成同一个值**，微信后续按精确值做对账/分页时会把它当另一类处理，引出新的清位—补位来回（正是「无竞态」要避开的）。
3. **破坏微信自己的语义位**：bit2 私密、bit5 广告、bit7 折叠都是**功能性**判据（`isAd()=isSourceExist(32)`，`moments.md` R28 订正）。清零它们 = 私密变公开、广告变普通、长文不折叠，属于本次修复范围之外的回归。

`B | 10` 恰好规避全部三条：它**只置位、不清位**，且枚举集合对「在含 bit3 的基值上置 bit1」是封闭的（列表本就成组给出 `8/10`、`264/266`、`12/14`），所以 `B` 在枚举里 ⇒ `W` 也在枚举里。

> **✅ 闭包边界已由真机实证关闭（team-lead 2026-11-07，不再是「未验证」）**：初稿担心「`B` 原本不含 bit3 时 `W = B|10` 会新增 bit3，`130|10 = 138` 是否仍落在相册 64 值枚举里」。实证结论：**落在**。
> - 完整枚举（从落盘日志抠出的 `sourceType in (...)` 64 个值）：
>   `8,264,10,266,12,268,14,270,24,280,26,282,28,284,30,286,72,328,74,330,76,332,78,334,88,344,90,346,92,348,94,350,136,392,138,394,140,396,142,398,152,408,154,410,156,412,158,414,200,456,202,458,204,460,206,462,216,472,218,474,220,476,222,478`。
> - 对真机全库 `select sourceType,count(*) from SnsInfo group by sourceType` 的 **262,374 行 / 17 种真实旧值**逐一验算：`2→10 / 8→10 / 10→10 / 130→138 / 136→138 / 138→138 / 22→30 / 6→14 / 86→94 / 70→78 / 12→14` **全部 IN 枚举**（含初稿点名担心的 `130|10=138`）；其余 `0/128/20/84/4/68` 因 `(B & 10) == 0` 走降级、根本不写回（§1.4 裁决）。
> - 理论上 `0..1023` 共 1024 个组合里有 672 个 `|10` 后会 OUT，但**真机库里一行都没有** ⇒ 对真实数据闭包风险关闭。
> - ⇒ 验收清单 C 的「原样 `IN` 枚举点查」仍保留，但性质从「探边界」降级为「回归守门」；§4⑥(c) 的收窄不再以「闭包不够」为理由，只以「暴露面太大」为理由。

[DONE-1]

## 2. 触发面：`applyBlock` 的两条路径与 `newValue: Int?`

### 2.1 现状（基线快照）

`SnsGuard.applyBlock(values, api, id, bits, keys, hasContent, hasAttr, newValue: Int?)` 是**两条路径共用的唯一动作点**：

| | 写 0 路径 | 非 0 纯降位路径 |
|---|---|---|
| 入口 | `onSnsUpdateBefore` 里 `st == 0`（`SnsGuard.kt:327-341`） | `onSnsUpdateDrop`（`SnsGuard.kt:355-379`） |
| 触发写 | `2→0` / `8→0`（`_r42_bit_paths.md` §2 纯文字/彻底清零） | `10→8` 砍 bit1、`10→2` 砍 bit3（`decide` 判 `isPureDowngrade && loses(bit1\|bit3)`） |
| `newValue` | **`null`** | **`st`（非 0，如 2/8/264）** |
| `bits` 来源 | `if (id != 0L) readBits(db, id) else null` —— **可能为 null** | `readBits(db, id)`，且 `id != 0L` 已硬校验 |
| 判据 | `decide` 规则①：`new == 0 ⇒ BLOCK`，**不依赖旧值**（KDoc 明写「宁多拦不漏拦」） | `decide(src, st) != ALLOW` 才走到 `applyBlock` |

关键区分：**`newValue: Int?` 是「写 0 路径 / 降位路径」的唯一标志**（`null` vs 非 0），`record()` 里就是靠 `isDrop = newValue != null && newValue != 0` 把 `hit/updZero` 与 `dropSeen/dropBlocked` 两套计数分开的（`SnsBlockProbe.kt:301-309`）。

### 2.2 两条路径上写回值是否都合理

**① 写 0 路径（`newValue = null`）—— 合理，且是本次缺口的主体。**
- 缺口 case「删除前没进过好友主页 ⇒ `src=2`」在真机轨迹里就是 `2→0`（`_r42_bit_paths.md` §2 纯文字行），**只可能出现在这一路径**。`W = 2|10 = 10` 正是对症。
- `B=8`（`8→0`）⇒ `W=10`，同时补回时间线位；`B=10`（`10→0`）⇒ `W=10` 幂等；`B=264` ⇒ `W=266` 保留高位。全部落在 §1 的约定内。
- 这一路径 `keys=18`（整行 upsert，真机 7.2 日志实证），写回只改 18 列里的 1 列，其余 17 列照微信原意写，不引入新列、不新增 SQL。

**② 降位路径（`newValue = st`）—— 数学上合理，但语义上「通常冗余、偶尔放大」，建议收窄。**
- 合理：仍是 `W = B|10`，纯函数、只置位、保留高位、幂等；`10→8`/`10→2` 时 `B=10 ⇒ W=10`，写回 == 摘键的最终库值，等价无害。
- 冗余：能被 `decide` 判 BLOCK 的降位，`B` **必然含有被清的那一位**（要清 bit3 就得先有 bit3）。对绝大多数 `10/266` 这类同时含 bit1+bit3 的旧值，`W == B`，写回并没有补任何新位 —— 这一路径本来就没有缺口。
- 放大：`B` 只含 bit1 不含 bit3 的降位（如 `130 = 128|2` 折叠动态被 `& -3` 式写成 `128` 砍 bit1）也会命中，此时 `W = 138` **把 bit3 加给了一条从未进过相册的动态**。这就是 §4⑥ 的「所有被拦动态都进相册」代价的来源。
- ⇒ **独立结论**：若只想修缺口、不想扩大暴露面，**把写回限定在 `newValue == null`（写 0 路径）即可**；降位路径保持旧行为「只摘键」。这样 `10→8/10→2` 仍能保住时间线与主页（旧值本就含 bit3），而 `2→0` 的缺口被补上。代价是 `130→128` 这类罕见降位仍查不到主页（宁可少补）。这条收窄值得交主会话拍板。

### 2.3 `bits == null` 为什么**必须**降级

1. **公式需要 `B`**。`W = B|10` 的输入就是旧值；没有 `B` 就只能猜一个常量（§1.3 已证常量 `10` 会抹高位、踢出枚举）。「猜一个值写进微信库」比「不写」恶劣一个量级：可能复活死行、可能把值写进枚举之外、可能覆盖微信的状态位。
2. **`bits == null` 是写 0 路径上真实可达的状态**，不是理论边角：`snsIdOf` 抠不出 id（`SnsGuard.kt:336-339` 有「抠不出 id 本条照拦」的告警）、`snsDb` 句柄尚未捕获、`readBits` 的 `rawQuery` 反射失败、行本就不存在 —— 四种都会返回 null。而写 0 路径按 `decide` 规则①**故意在查不到旧值时也拦**，所以「拦了但没 B」必然会发生。
3. **降级的代价明确且可接受**：降级 = 保持旧的「摘键」行为 ⇒ UPDATE 不碰 `sourceType` 列 ⇒ 库里旧值不变 ⇒ **时间线仍在（防撤回主目标达成）**，只是不补 bit3（回到主页查不到的旧缺口）。「宁可少补一位，不可误写一位」与 `onSnsUpdateDrop` 里 `old == null → ALLOW` 的「宁可少拦、不放猜值改库」（`SnsGuard.kt:351-354`）是同一口径。
4. **降位路径上 `bits == null` 不该出现**：`decide(null, st) = ALLOW` 会提前返回，能走到 `applyBlock` 就证明 `old != null`。若实现里在降位路径也见到 null，说明 `readBits` 在 `decide` 之后又失败了一次（同一 id 两次点查结果不一致），属**异常**，应单独 `LogX.w` 告警而不是静默降级。
5. **降级必须可观测**：单独计数（建议 `writeBackDegrade`）+ event 行 `wb=-` + 区分 `(B & 10) == 0` 与 `B == null` 两个原因。否则 `blocked`/`dropBlocked` 与 `writeBackOk` 对不上账，会被误读成「写回失败 / 漏拦」，而真相是「主动降级」。
6. `(B & 10) == 0`（§1.4 裁决的 6 种真实旧值 `0/128/20/84/4/68`）与 `B == null` 共用**同一降级分支**：都是「可拦但不可写回」，都不复活、都不改写、都记同一类降级账。

### 2.4 `newValue` 不能被 `W` 顶替（连锁约束）

写回值 `W` **只能**用于 `ContentValues.put("sourceType", W)` 这一个动作和新增的 `wb` 记账字段。`newValue` 还有 5 处下游语义必须原样保留：

| 下游 | 依赖 | 若误传 `W` 的后果 |
|---|---|---|
| `SnsBlockProbe.record` 的 `isDrop` | `newValue != null && != 0` | `hit/updZero` 与 `drop*` 两套计数全乱，`want` 字段不再是微信意图 |
| `Event.want` / `logBlockedThrottled` 的 `hit\|drop` tag | `newValue ?: 0` | 事件账本失去「微信想写什么」，§3 的互斥判据失去比对基准 |
| `noteSkipEmpty(id, isDrop)` | `newValue != null` | SKIP_EMPTY 记错计数桶 |
| `SKIP_EMPTY` 的放回值 | `newValue ?: 0` | 空 values 兜底放回的不是微信原值 ⇒ 可能崩/改库 |
| **`SnsDeletedTag.onBlocked(id, bits, newValue ?: 0)`** | `want` | **灰标全漏**（§4③ 详述） |
| `onSnsUpdateBlockAfter` 的 `want` 比对 | `want` | §3 判据失效 |

⇒ 实现上最安全的形态：`applyBlock` 保留 `newValue` 形参不动，只把「`remove`」换成「`remove` + `putInt(W)`」或「`putInt(W)`」，`W` 作为**局部新变量**单独算、单独记。

[DONE-2]

## 3. after 校验的连锁影响与互斥判据（⛔ 前提已按源码更正）

### 3.1 前提更正：写回值**不会**破坏 after 校验

> ⛔ **本文初稿第 3 节的前提错了**，team-lead 核源码后纠正，此处以更正后为准：
> - `SnsBlockProbe.isBlockedValues(cv)` / `blockedCvs` 是**死代码**：全项目**无调用点**，只在 `SnsGuard.kt:218` 的 KDoc 里被提到一次。初稿误把它当成在跑的形态判据，并据此推出「读不到 `sourceType` ⇒ 甲生效」的二选一 —— **那套判据并不存在于活代码**。
> - 真正的 after 校验链只有一条：`SnsGuard.onSnsUpdateBlockAfter()` → `SnsBlockProbe.hasPending()` → `finishAfterWrite { readBits(db, id) }`。`finishAfterWrite` 把**库里回查值** `bits.src` 写进 `e.after`、打印 `BLOCK after snsId=.. before=.. after=.. (键已摘)`、递增 `afterOk` / `afterFail`（`SnsBlockProbe.kt:354-372`）。

**因此写回值的影响只有一条，且不是「判据失效」**：

- `hasPending()` 读的是 `pending: ThreadLocal<Event?>`，由 `record(... Outcome.BLOCKED ...)` 里 `pending.set(e)`（`cv != null` 时）设置，`onSnsUpdateBefore` 开头 `clearPending()` 清理。**`pending` 与 ContentValues 里有没有 `sourceType` 键完全无关** —— 写回只改了 `values` 的内容，没动 `pending`。⇒ **after 校验的触发链照常工作**：不需要改判据、不需要引入身份集合、不需要动 `isBlockedValues`（死代码，别去改它）、不需要动 `onSnsUpdateBlockAfter` 的调用结构。
- 唯一坏掉的是**日志文案**：写回后 `finishAfterWrite` 会打印 `before=2 after=10`，尾巴却仍写 `(键已摘)` —— 键没被摘，是被改写后写回去了。这句是假话，必须改口径（§3.4）。
- 另一处语义要澄清：`afterOk` / `afterFail` **只表示「回查有没有读到行」，不表示「拦没拦住」**。写回后 `afterOk` 在「拦住并补位」「拦住但没补位」「甚至没拦住」几种情况下都会增 ⇒ **绝不能拿 `afterOk` 当「补位成功」的计数**（初稿 §3.3 把它与成功并列，易被误读）。区分靠 `before / after` 的**读数关系**（§3.2）。
- ✅ **已证伪，无需担心（team-lead 2026-11-07 亲验）**：初稿曾提醒「`record()` 的 `pending.set(e)` 挂在 `if (cv != null)` 上（`SnsBlockProbe.kt:329-332`），写回改造若把 `cv` 实参弄丢 ⇒ 整条 after 校验静默失效（`hasPending()` 恒 false）」。team-lead 核实现方代码证伪：`SnsGuard.applyBlock` 的**写回分支（489 行）与降级分支（542 行）都传了 `cv = values`**，两条路径 `pending.set(e)` 都会执行 ⇒ **after 校验不会静默失效**。此条**降级为回归守门**：将来若重构 `applyBlock` 动了 `record(...)` 的实参，仍需保证 `cv = values` 不丢（判据见验收 D 的 `afterOk` 有增）。

### 3.2 互斥判据：用 `before / after` 读回值自证（两项不能同时为真）

**锚点**：判据必须落在**缺口样本 `before=2`** 上（真机基线 `snsId=-3397763077853081064`，`sourceType=2`、删前没进主页）。`before=10` 的样本（`-3397762123896778223`，删前进过主页）上 `W = 10|10 = 10` 是幂等 no-op，补不补位读数一样，**本来就分不出两组**，不该拿它做互斥验收。

一次 BLOCKED 事件（`hasPending()` 为真，已证明「我们决定拦了」）取三元组 `(before, want, after)`，`after` = `finishAfterWrite` 回查到的**库值**：

| 态 | 判据 | 含义 |
|---|---|---|
| **R「拦住并补位」** | `after == (before or 10)` **AND** `before & 10 != 0` **AND** `afterOk` 增、**`afterFail` 不涨** | UPDATE 确实把 `sourceType` 写成了我们算的 `W`，库里读回自证 |
| **S「拦住但未补位 / 降级摘键」** | `after == before` **AND** `afterOk` 增、`afterFail` 不涨 | UPDATE 没碰 `sourceType` 列（摘键生效或主动降级），库里旧值不变 |
| **F「没拦住 / 清位落地 / 故障」** | `after == want` **OR** `after & 10 != 10` **OR** `afterFail` 涨（`e.after = "?"`） | 微信的清位写进去了，或回查失败没有可比读数 |

**互斥性证明（R ∧ S = ∅，且两者都排除 F）**：

1. 在缺口样本 `before = 2`：`before or 10 = 10`，而 `2 != 10`。`after` 是**单次回查得到的单个 Int**，不可能同时等于 `10` 和 `2` ⇒ **R 与 S 严格互斥**。
2. F 与 R/S 也错开：没拦住时微信想写 `want`（写 0 路径 `want=0`；降位路径 `want` 缺至少一位）⇒ `after = want` 且 `want & 10 != 10`；R 要求 `after & 10 == 10`（`W = before|10` 必含两位）；S 要求 `after == before`。三者互不重叠。
3. `afterFail` 涨 ⇒ `e.after = "?"`，**没有读数可比** ⇒ 一律归 F，绝不硬塞进 R 或 S（不许把未知自欺成成功）。
4. 数学支点（初稿结论保留）：`W = before|10` ⇒ 恒有 `W & 10 == 10`；被拦的 `want` ⇒ 恒有 `want & 10 != 10`（`SnsBitLogic.decide` 只拦「丢 bit1 或 bit3」的纯降位 / 写 0，`SnsBitLogic.kt:68-69`）⇒ **`W != want` 恒成立**。这就是「`after` 能一眼分出是我们写回的值还是微信写的值」的根。
5. 关补位组的 `after=2 == before` 还顺带自证「甲层没失手」：若连 `after` 都变成 `0`，说明清位落地 ⇒ 落 F，该组实验作废。

**为什么两组读数能区分「拦住并补位」与「没拦住」**（单变量反向对照，调试键 `antirevoke.sns_album_fix_off` 只关补位、保留甲/A/B）：

| 开关 | 同一复现流程的读数 | 界面 | 判据落点 |
|---|---|---|---|
| **开补位**（默认，`album_fix_off=false`） | `before=2 → after=10`，`afterOk` 增、`afterFail` 不涨 | 好友主页**出现**该条 | R |
| **关补位**（`antirevoke.sns_album_fix_off=true`） | `before=2 → after=2`，`afterOk` 增、`afterFail` 不涨 | 好友主页**不出现** | S |

- 两组唯一自变量是「写回有没有发生」，因变量是**同一个 `after` 读数**：`10` vs `2`，互斥、不可同时成立。
- 开补位组 `after=10 = before|10` 证明「写回值落库」，配合 `op=sql` 原样枚举点查（§3.3）证明「10 真在相册集合里」⇒ 主页出现的因果链闭合。
- 关补位组 `after=2 == before` 证明「摘键仍生效、防撤回主目标（时间线）没坏」。
- ⇒ 这两组读数**同时**覆盖「补位生效」与「没补位时旧行为不回归」，且互相排除对方，是「拦住并补位」与「没拦住/没补位」的互斥自证。

**配套改动（只动记账与文案，不动触发链）**：

1. `Event` 增 `wb` 字段（`BLOCK ev` 打印用）；`finishAfterWrite` 拿到 `after` 后按上表归类，就地递增 `wb*` 计数（读数与计数同一处产出，不矛盾）。
2. `BLOCK after ...` 的 `(键已摘)` 按 `(before, want, after)` 分派成 `(写回)` / `(摘键·未补位)` / `(回查失败)` 三选一。
3. 降级（§2.3，`(B&10)==0` 或 `B==null`）显式记 `wb=-`、归 S，并在恒等式里单独计数 `wbDegrade`。
4. ⛔ **不引入身份集合**：初稿的「身份 × 值双因子」建立在错误前提（以为 `isBlockedValues` 在跑）上，更正版**不需要**。`pending` 已经把「这次调用被我们拦过」这个事实圈定了，合法加位写根本不会设 `pending` ⇒ 天然不会被误判成 R。

### 3.3 真机验收：计数 / 日志 / 探针

**既有计数（口径不变，别重定义）**：`afterOk` 增、`afterFail = 0` 是开/关两组**共同前提**；`hit / blocked / nonZero`、`drop seen/blocked/off/allow/unknown/empty/noApi`、`skipOff/dropOff`、`skipEmpty/dropEmpty`（§4④）、`sqlClear9 / sqlBlock9 / blkOff / blkFail / rng / max`、`sqlClear`（`& -3`，恒 0）、`delSns/delRow`（零 DELETE）、`sqlOwn`（0，§4②）照旧看。

**新增计数（落在 `finishAfterWrite` 拿到 `after` 之后就地判定，与读数是同一处产出、不矛盾）**：

| 计数 | 归类（§3.2） | 期望 |
|---|---|---|
| `wbOk` | R（拦住并补位：`after == before or 10`） | 开补位组 > 0、关补位组 = 0 |
| `wbRemoveOk` | S（拦住但未补位：`after == before`） | 关补位组 > 0；开补位组仅降级/守卫命中时不为 0 |
| `wbDegrade` | `(B&10)==0` 或 `B==null` 的主动降级（§2.3） | 与这两类之和一致 |
| `wbLost` | F 中「`after == want` / `after & 10 != 10`」 | **恒 0** |
| `wbMismatch` | F 中「`afterFail` 涨 / `after="?"`」 | **恒 0** |
| `afterOk` / `afterFail` | 只记回查成败，**不表示拦没拦住**（§3.1） | `afterFail = 0` |

恒等式：`blocked + dropBlocked == wbOk + wbRemoveOk + wbDegrade + wbLost + wbMismatch`。任一对不上 = 记账漏分支。⛔ **不要用 `afterOk` 代替 `wbOk`**（§3.1：它只证明读到行，不证明补位）。

**日志/探针**：

- `BLOCK ev ... before=.. want=.. wb=.. after=..` —— 增 `wb` 字段；开补位成功样本 `before=2 want=0 wb=10 after=10`，关补位样本 `before=2 want=0 wb=- after=2`。
- `BLOCK after snsId=.. before=.. after=.. (写回)` / `(摘键·未补位)` / `(回查失败)` 三选一，**删掉 `(键已摘)` 这个假话尾巴**；新增 `BLOCK wb-lost` / `BLOCK wb-mismatch` 限流告警，一行带全 `before/want/W/after`。
- `op=snsg sub=blockstat`（5 行计数 + 最近 10 事件）、`op=snsg sub=stat`（`blockEnabled=true sqlBlock=true` + 新增 `albumFix=<true|false>` 回读）、`op=snsg sub=trace --el id <snsId>`（30s 采样：开补位 `src` 恒 10；关补位 `src` 恒 2，都无跳变 —— 无竞态/不越写越大，§4⑤）。
- `op=sql`：① 点查 `select snsId,sourceType,type,createTime from SnsInfo where snsId=<id>` ⇒ 开 `10` / 关 `2`；② **原样 64 值 `IN (...)` 点查该 id**（完整列表见 §1.5，已实证 `138` 在内）⇒ 开补位返回 1 行、关补位返回 0 行（性质是回归守门，不再是探边界）；③ `select sourceType,count(*) from SnsInfo group by 1` 看降级集合 `0/128/20/84/4/68` 的行数**不下降**（§1.4 没误复活）、`10` 的行数随被拦动态上升。
- `op=snsdel sub=stat`：`rows` 不因本次写回异常跳增（§4③）。

### 3.4 配套改动清单（比初稿少得多）

1. `finishAfterWrite` 的**日志文案**：按 `(before, want, after)` 分派 `(写回)` / `(摘键·未补位)` / `(回查失败)`，删 `(键已摘)`。
2. `Event` 增 `wb` 字段；`finishAfterWrite` 里顺手按 §3.2 归类并递增 5 个 `wb*` 计数。
3. `SNS stat` / `blockstat` 增 `albumFix` 回读 + 5 个计数（注意「总行数 ≤ 16」约束）。
4. ⛔ **不需要动**：`hasPending()` / `pending`（触发链不受写回影响）、`isBlockedValues` / `blockedCvs`（死代码，别改）、`SnsGuard.onSnsUpdateAfter`（本就空转）、`onSnsUpdateBlockAfter` 的调用结构。
5. ✅ **已证伪（见 §3.1）**：初稿要求「⛔ 保留 `record(..., cv = values, ...)` 实参，否则 after 校验静默失效」—— 实现方写回分支（489 行）与降级分支（542 行）**都已传 `cv = values`**（team-lead 亲验），无需额外动作。仅作为回归守门保留：将来重构 `applyBlock` 时别把该实参弄丢。

[DONE-3]

## 4. 副作用清单（①~⑥ 逐条）

### ① 反向消融（只关开关）—— 写回会把 B 层的消融结论「自愈」掉

- **主开关** `antirevoke.sns=false` ⇒ `blockEnabled=false` ⇒ `applyBlock` 首行 `record(OFF); return`，不 remove、不 put ⇒ CV 原样 ⇒ 微信清位照写。⇒ **写回动作必须放在 `if (!cfg().blockEnabled)` 闸门之内**（基线 `SnsGuard.kt:396-403`），否则关了开关还在写库。
- ⚠️ **连锁风险（最容易被忽略的一条）**：`_r42_bit_b_report.md` §8 的反向消融是「**只关 B 层** `sns_sqlblock_off=true`，主开关保持开」。原轨迹：`& -9` 原样执行 ⇒ `10→2` 主页消失；紧随 `22:10:23 before=2 want=0 blk=1 after=2`（甲拦写 0，停在 2）。**有写回后**，那次 `want=0` 的拦会按 `B=2` 写回 `W=10` ⇒ `after=10` ⇒ bit3 又回来了 ⇒ **主页重新可见**。⇒ 消融会得出「B 层不必要」的**错误结论**，而且把一个真实故障自愈掉了、掩盖了 B 层失效。原判据链 `sqlClear9↑ / sqlBlock9 不变 / blkOff↑ / src 10→2` 只有在「**写回已停**」的前提下才成立。
- ⚠️ `ANTIREVOKE_SNS_BLOCK` 是「或」关系覆盖（`SnsGuard.refresh():149`：`block = ANTIREVOKE_SNS || ANTIREVOKE_SNS_BLOCK`）⇒ 只关主开关**不一定**关得掉。做消融前要用 `op=probe kvset` 回读两个键。
- ⇒ **已定名 `antirevoke.sns_album_fix_off`（team-lead 拍板，实现方落地）**：仿 `ANTIREVOKE_SNS_SQLBLOCK_OFF` 形态，`albumFixEnabled = blockEnabled && !Kv.getBool(ANTIREVOKE_SNS_ALBUM_FIX_OFF)`，只关补位、保留甲/A/B 三层。这样 ① 能做单变量对照（验收清单 F 的反向对照就靠它），② 是 §5 的运行时回滚抓手，③ 不破坏 §8 消融。⚠️ 语义与 `sns_sqlblock_off` 严格区分：前者关「写回补位」，后者关「`-9→-1` 改写」，两者都是「只关一层」，别混成一个键。

### ② 方案 B 的 `execSQL & -9 → -1` —— 代码不用动，但有两个交叉点要验

- B 层把 `& -9` 常量换成 `-1`（恒等），只改常量、WHERE / 命中集合 / `changes()` 全不变（`_r42_bit_b_report.md` §1）。写回是 **ContentValues 通道**的动作，**不产生任何新 `execSQL` SQL** ⇒ `SnsBitLogic.sqlClear` / `SnsSqlRewrite.shouldBlock` 的识别面不变，`sqlClear9 == sqlBlock9`、`blkFail=0` 的判据不变，`& -3` 仍只识别不拦。**`SnsSqlRewrite` 与 B 层代码不需要改。**
- 交叉点 1：**`sqlOwn` 必须保持 0**。`SnsBitLogic.OWN_FIX_RE` 匹配 `sourceType = sourceType | 10|2`（`SnsBitLogic.kt:90-92`）。若实现图省事用 `execSQL("UPDATE ... | 10")` 补位（正是命题禁止的「额外 UPDATE / 事后补位」），会被 `isOwnFix` 记进 `sqlOwn`，并引入 B 层看不见的第二条写库通道。⇒ 写回**只能**用 `ContentValues.put`，`sqlOwn=0` 是硬指标。
- 交叉点 2：**`& -9` 的 WHERE 是否还匹配 `W`**。改写后 `& -1` 是恒等，命中 `W` 的行值不变（bit3 保住）。`W` 在枚举内 ⇒ 匹配、被改写、无害；`W` 在枚举外 ⇒ SQL 根本不命中该行 ⇒ 同样无害，但说明该行脱离了 B 层的对账视野。⇒ 仍需 §3.3 的「原样 `IN` 枚举点查」证明 `W` 真在集合内，别只证明「值没被削」。✅ **闭包风险已对真实数据实证关闭**（§1.5：64 值完整枚举 + 全库 262,374 行 17 种真实旧值，`130|10=138` 等全部 IN），此处点查性质降为**回归守门**。
- `& -9` 进一次主页执行 6 条（`_r42_bit_paths.md` §8.2），写回后命中行集里会多出「原 `src=2`、现被补成 10」的动态 ⇒ `rng/max` 可能变化。`max > 6` 或持续上涨仍按 §3 R3 判「重试」，**不要误读成写回引入的重试风暴**（§4⑤ 证明写回本身收敛）。

### ③ 灰标台账 `SnsDeletedTag.onBlocked(id, bits, want)` 的「丢时间线位」判据 —— 最容易被 `W` 顶替的一处

- `SnsDeletedTagLogic.shouldRecord(old, new, ...)` → `isTimelineLoss(old, new) = old != null && (old & 2) != 0 && (new & 2) == 0`（`SnsDeletedTagLogic.kt:57-58`）。它吃的是 **`want`（微信想写的值）**，用来判定「这次写是不是砍了 bit1」（`_r42_deltag_report.md` [DONE-1] 决策 1「只盯 bit1，不盯 bit3」）。
- ⛔ **致命连锁**：若把 `SnsGuard.kt:441` 的 `SnsDeletedTag.onBlocked(id, bits, newValue ?: 0)` 里的 `newValue` 换成 `W`，则 `new = W` 恒含 bit1 ⇒ `(new & 2) != 0` ⇒ `isTimelineLoss` **恒 false** ⇒ **所有灰标全部漏记**，「只盯 bit1」的既定判据被架空。
- ✅ 正确处置：**原样传 `want`**（`newValue ?: 0`），写回值不进这条链。写 0 路径 `want=0, B=2` ⇒ `isTimelineLoss(2,0)=true` 照记；降位 `10→8` ⇒ `want=8` ⇒ true 照记；`10→2` ⇒ `want=2` 含 bit1 ⇒ false 不记（与「只丢相册位不算删除」一致）。
- 另外：`onBlocked` 只在 `Outcome.BLOCKED` 后调用，写回不改变 BLOCKED 集合 ⇒ 台账条数不变；`sns_deleted` 用 `sns_id PRIMARY KEY` + `CONFLICT_IGNORE`，重试删除重复调用也只留一条、`gen` 只在真插入时自增 ⇒ 不重复标灰。⚠️ 但 `SKIP_EMPTY` 分支**不**调 `onBlocked`（`SnsGuard.kt:425-433` 提前 return），别在改 §4④ 时顺手把 `onBlocked` 挪进去。
- 验收：`op=snsdel sub=stat` 的 `rows` 在写回前后对同一批删除应**相同**，`match - hits = 0`，`self=?` 降级语义不变。

### ④ `left == 0` 的 SKIP_EMPTY 分支 —— 防闪退兜底，不能删也不能改错放回值

- 现状：`remove("sourceType")` 后若 `size(values)==0`，SQLite 的 `update` 会抛 `IllegalArgumentException` ⇒ 代码把**微信原本要写的值** `newValue ?: 0` 放回去、`noteSkipEmpty` + `record(SKIP_EMPTY)`、放弃拦截（`SnsGuard.kt:423-434`）。真机 `keys=18`（整行 upsert）时 `left=17`，几乎不触发，但它是**防闪退**兜底，必须保留。
- 写回后三种形态与风险：
  - **(A) `remove` + `putInt(W)`**：`size` 先 0 后 1 ⇒ 不崩。但 `putInt` 可能失败（`CvApi.putInt` 靠 `getMethod` 缓存，wcdb 形态变化时为 null，`SnsGuard.kt:253-256`）⇒ 若 put 静默失败，`size` 仍是 0 ⇒ **微信写库线程抛异常、朋友圈闪退**。⇒ 必须**在 put 之后**再校验一次 `size > 0`。
  - **(B) 直接 `putInt(W)` 覆盖、不 remove**：`size` 恒 ≥ 1 ⇒ 该分支理论上**永不触发**。此时 `skipEmpty/dropEmpty` 若还在涨，就是 `putInt` 失效的强信号，必须查而不是忽略。
  - **(C) 误把 `left==0` 的放回值改成 `W`**：语义上能成立（非空、不崩），但与 `SKIP_EMPTY`「放弃拦截」的记账口径冲突 —— 要么把 Outcome 改为 `BLOCKED` 并计入 `wbOk`，要么保持放回 `want`。**二者只能选一**，否则账本自相矛盾。
- ✅ 推荐：保留 guard，改成「**所有 CV 编辑做完后统一校验 `size(values) >= 1`**；不满足 ⇒ 放回 `newValue ?: 0`（微信原值）、记 `SKIP_EMPTY`、不调 `onBlocked`」。`noteSkipEmpty(id, isDrop = newValue != null)` 的分桶不变。
- 验收：`skipEmpty + dropEmpty` 期望 **0**；非 0 先看 `BLOCK skip: empty values ... isDrop=..` 与 `putInt` 是否为 null。

### ⑤ 微信后续重试删除：会再触发写回，但**值不会越写越大**

- **会再触发**：微信对失败/未确认的删除会重试（防撤回侧实测 5 秒 360 次；`_r42_bit_b_report.md` §7.5 `rng=3 max=4x@?` 有 2~4 次/区间）。每次重试仍是 `updateWithOnConflict("SnsInfo", {sourceType: 0/2/8, ...})` ⇒ before 钩子再次命中 ⇒ 再次 `applyBlock`。
- **不会越写越大**，两条硬理由：
  1. `W = B|10` **幂等**：`f(f(x)) = (x|10)|10 = x|10`。第一次写回后库值变 `W`，重试读到 `B'=W`，写回 `W|10 = W` ⇒ **一次即到不动点**，后续所有重试写的是同一个常量。高位同理稳定（`264 → 266 → 266 …`）。
  2. 只 `or` 不 `+`、只置 `2|8` 两位、不清位 ⇒ 值单调不减且有上界 `B|10`。若写成 `B + 10`、`B | (1 shl n)` 之类才会漂移 —— 实现里**禁止算术加/移位，纯 `or`**。
- **两个可观测副作用（要验收，不是要修）**：
  - 每次重试都做一次真实 UPDATE（写 `sourceType=W`，值与库里相同）。原本「摘键」时该列不写但**其余 17 列照写**，所以**没有额外 IO**（与 `_r42_bit_b_report.md` §3 R5 同构）—— 没有多一条查询、没有多一条 SQL，「零额外 IO」成立。WAL 增长与关开关组应零差异。
  - 每次重试都调 `onBlocked` ⇒ 后台 writer 多 post 一个任务，但主键 + `CONFLICT_IGNORE` ⇒ 不重复落库、`gen` 不反复自增（③ 已述）。`hit/blocked/dropBlocked` 随重试线性增长是**正常**的，别当成「写回被反复触发」的故障；判风暴用 `rng/max` 和 `wbOk` 与去重 id 数对比。
- 验收：`trace` 30 分钟 `src` 恒为 `W` 无跳变；`wbOk` 可大于被删动态条数（重试），但 `wbLost/wbMismatch` 恒 0；`max` 不持续上涨。

### ⑥ 所有被拦下的动态都进相册列表：代价是否可接受 / 能否只补「本来该在相册里」的那些

- **代价（写回把它放大了）**：B 层原本只是「已有 bit3 的行不再被 `& -9` 削掉 ⇒ 相册列表停止收缩」（`_r42_bit_b_report.md` §3 R1/R2，用户已接受）。写回 `W = B|10` 会把 bit3 **加给原本没有它的行**（`B=2` 的 15.9 万行主体、`B=130` 折叠动态等）⇒ 暴露面从「不收缩」升级为「**从未进过相册的动态也进相册**」：
  1. 好友主页相册条目数膨胀，且不再随服务端「本次没返回」而收缩（R1/R2 的更坏版本）；
  2. 对方已设为私密/部分可见、或早已过期的动态重新出现（`_r42_bit_paths.md` §6 的取舍当时只针对 `10→2`，没覆盖「凭空加 bit3」）；
  3. 用户已拍板「主页相册不标灰」（`_r42_deltag_report.md` 00:30 取证）⇒ 这些「本不该在」的动态在相册里**没有灰标**，观感上是凭空冒出来的旧动态，正是 §5 的重复/错序来源；
  4. `(B & 10) == 0` 的 6 种「两处都不可见」旧值（`0/128/20/84/4/68`，含 0 的 16,426 行）若不守卫会批量复活（§1.4 裁决已挡住，是这条代价里最危险的一支；**只排除 0 会漏掉 4/128/20/84/68 五种**）。
- **可接受吗**：作为「已拍板修法」的复核，判断是「**有条件可接受，但必须显式告知用户新增暴露面**」，且**强烈建议收窄**，否则把「防撤回」悄悄变成了「把所有被删动态塞进好友主页」。
- **能否只补「本来该在相册里」的那些？—— 单看库里旧值做不到**：bit3 本身就是「被好友主页相册列表拉过」的记账位，缺口 case 恰恰**没有**它；而「该不该在相册里」取决于**服务端本次是否返回**，本地在写时拿不到（`_r42_bit_paths.md` §4：服务器不下发删除标记 / 响应体无 deletedIds、tombstone；`moments.md` R27 §4）。任何区分都要额外信息源：
  - (a) 响应注入 / 扫服务端返回 ⇒ 命题明确禁止（注入/扫描/额外 IO）；
  - (b) 模块内自记「本进程见过哪些 id 被 `2→10` 加位」的台账：before 钩子本来就看得到所有加位写（`noteUpdateNonZero` + `needsOld` 快速放行），理论上零额外 DB IO 可记。但它是**进程内状态**：冷启后库里 bit3 是持久值、台账是空的 ⇒ 同一条动态重启前后行为不一致，还要处理淘汰/并发/上限 ⇒ 重新引入「无竞态」想避开的那类生命周期问题。**不推荐**作主方案。
  - (c) ✅ **最小可行收窄（推荐交主会话拍板）**：**只在 `newValue == null`（写 0 路径）写回，降位路径仍只摘键**（§2.2 已论证缺口只在这一路径）。`2→0` 补成 10 ⇒ 修好；`10→8`/`10→2` 的 `B` 本就含 bit3 ⇒ 主页本来查得到 ⇒ 不用补，也就不会把「从没进过相册」的降位动态塞进去；代价是 `130→128` 这类「旧值含 bit1 不含 bit3」的罕见降位仍查不到主页（宁可少补）。暴露面从「所有被拦动态」收窄到「被删的、无相册位的动态」，且**零新状态、零额外 IO、无冷启不一致**。
  - (d) 「只补 bit3 不补 bit1」（`W = B|8`）**不可取**：§1.3 已证 `B=8/264` 时结果不含 bit1 ⇒ 时间线仍丢，违背「双可见」目标。
- 验收（无论是否收窄）：好友主页条目数开/关对照、是否冒出私密/过期/很久以前的动态、`snsdel rows` 不异常、`op=sql select sourceType,count(*) group by 1` 里 `10/266` 的增量与「被拦动态条数」是否吻合（远大于 = 收窄没生效 / 重试被重复计入）。

[DONE-4]

## 5. 回滚路径（真机出现主页重复/错序时）

### 5.1 先分清「行为回滚」与「数据回滚」—— 这是本节最重要的结论

写回是通过**微信自己的 `updateWithOnConflict`** 把 `W` 落进 `SnsMicroMsg.db` 的，是**持久化写入**。⇒

> ⛔ **关开关 / 改代码只能停掉「以后」的写回，擦不掉「已经」写进去的 `W`。** 已经出现的重复/错序不会因此消失。

而且 B 层把 `& -9` 改成了 `& -1`（恒等），所以只要 `sqlBlockEnabled=true`，微信自己的清 bit3 对账**也不会**把这些行的 bit3 削掉 ⇒ 被写回的行会**长期**留在相册枚举里。⇒ 光改代码等于「止血不排毒」。

**让已有 `W` 行真正消退的唯一不违规路径**：关掉 B 层（或主开关）让 `& -9` 原样执行，靠**微信自己**把 bit3 清回 `2`（这正是 `_r42_bit_b_report.md` §8 反向消融观测到的 `10→2`），但**前提是写回已停用**：否则 `& -9` 削成 2 之后，紧随的 `want=0` 写 0 又被甲拦、又被写回成 10（§4① 的连锁），清了等于没清。⇒ 顺序必须是「**先停写回 → 再让 `& -9` 执行 → 等微信对账收缩**」。

### 5.2 只靠开关能关掉吗？分三种

| 关法 | 停掉新写回 | 已有重复/错序是否消退 | 副作用 |
|---|---|---|---|
| **主开关** `antirevoke.sns=false`（且 `antirevoke.sns_block` 未置 true 覆盖） | ✅ `blockEnabled=false` ⇒ `applyBlock` 首行 `record(OFF); return`，不 remove 不 put | ⚠️ **有可能**：`sqlBlockEnabled = block && ...` 一并变 false ⇒ `& -9` 原样执行 ⇒ bit3 被清 ⇒ 相册逐步收缩。但要等微信下次跑那条 SQL，非立即 | **防撤回整体失效**（时间线也一起没了）；且需确认 `ANTIREVOKE_SNS_BLOCK` 这个「或」关系覆盖键没被置 true（`SnsGuard.refresh():149`） |
| **只停补位** `antirevoke.sns_album_fix_off=true`（§4① 已定名） | ✅ 单变量、保留甲/A/B | ❌ **不会**：B 还开着，`& -9` 被改写成恒等，已有 `W` 行保持 bit3 | 最小、最适合做 A/B 对照（验收清单 F）与「止血」，但不能回滚数据 |
| **只关 B 层** `antirevoke.sns_sqlblock_off=true`（§8 原实验） | ❌ 写回仍在 | ❌ 反而被写回自愈（§4①） | 会得出「B 层不必要」的错误结论 |

⇒ **结论**：「只靠开关」能干净地关掉**新行为**（推荐用独立写回开关，主开关做兜底），但**不能只靠开关回滚已写入的数据**；要消退已有症状，必须「停写回 + 让微信自己的 `& -9` 跑一遍」，或接受带外人工修库（而「额外 UPDATE / 事后补位」正是命题禁止的，模块不该提供这条路）。

### 5.3 代码回滚要动哪几处（按依赖顺序列全）

1. **`SnsGuard.applyBlock`**：把「`remove` + `putInt(W)`」（或「`putInt(W)` 覆盖」）改回**只 `remove`**；删掉 `W` 的计算、`putInt` 调用、`wb` 局部变量；`left==0` 分支的放回值改回 `newValue ?: 0`（微信原值），`SKIP_EMPTY` 语义恢复。⚠️ 写回必须仍位于 `if (!cfg().blockEnabled)` 闸门**之内**，别回滚时挪到闸门外。
2. **`SnsBlockProbe.record` / `Event`**：删掉新增的 `wb` 字段（`want` 保留）；`stat()` 去掉 `wbOk/wbRemoveOk/wbDegrade/wbLost/wbMismatch` 与恒等式那一行（注意恢复「总行数 ≤ 16」）；`logBlockedThrottled` 的日志形态回到 `BLOCK hit/drop ... want=..`。
3. **`SnsBlockProbe.finishAfterWrite` / `Event`**：日志文案由 `(写回)/(摘键·未补位)/(回查失败)` 三选一改回单一 `(键已摘)`；删 `Event.wb` 字段、`finishAfterWrite` 里的 5 个 `wb*` 归类计数与第三态告警；`stat()` 去掉 `albumFix` 回读；`afterOk/afterFail` 回到「只记回查成败」的原义（§3.1）。⛔ `hasPending()` / `pending` / `onSnsUpdateBlockAfter` 调用结构**都不用动**（写回本就没碰它们）。
4. ⛔ **`SnsBlockProbe.isBlockedValues` / `blockedCvs` 是死代码（全项目无调用点），本轮改造与回滚都不需要动它**；只需把 `SnsGuard.kt:218` KDoc 里那句过时描述同步一下，别让后人以为它还在跑。（初稿 §3 误判了这一处，见 §3.1 更正。）
5. **§2.4 那 6 处 `newValue` 下游**：逐条确认仍传 `want`（尤其 `SnsDeletedTag.onBlocked(id, bits, newValue ?: 0)`），`newValue` 形参不动。
6. **配置 / 开关**：删 `ANTIREVOKE_SNS_ALBUM_FIX_OFF` / `antirevoke.sns_album_fix_off` 的 `Keys` 常量 + `PrefSpec` 行 + `Config` 字段 + `refresh()` 里的 `albumFixEnabled` + `SNS stat` 里的 `albumFix=` 输出；否则设置页会多一个死开关、验收脚本 parse 失败。
7. **纯函数与单测**：`W(B)` 建议抽成 `SnsBitLogic.writeBack(B)` 之类的纯函数（零 Android 依赖、可单测），回滚时一并删函数 + `SnsBitLogicTest` 对应用例 + 变异脚本条目；全量单测基线（771/785 例，见 `_r42_deltag_report.md`）要重新对齐。
8. **文档订正**：`docs/conclusions/moments.md` 的 R42 订正表、`docs/_r42_bit_paths.md` §8/§9、`docs/_r42_bit_b_report.md` §7/§8 里「最终效果 `src` 停在 10」「三层不可省」的结论是在**无写回**前提下取得的；写回上线/回滚都要同步标注前提，避免下次拿旧结论反推。
9. ⛔ **不需要动**：数据库 schema（本轮无建表/加列，全是内存计数与微信既有列）、`SnsSqlRewrite` / 方案 B 的 `-9 → -1` 逻辑（§4② 已证写回不产生新 SQL 形态）、`SnsDeletedTag` 的渲染链、`GroupDb.sns_deleted` 表结构。

### 5.4 复核建议（给主会话）

- **上线前**先按 §4① 用 `antirevoke.sns_album_fix_off` 做一次「开/关补位」单变量对照（验收清单 F），别等到真机出问题才发现没有抓手。
- **验收前**先确认 `(B & 10) == 0` 守卫（§1.4 裁决）真的生效 —— 它是「批量复活 `0/128/20/84/4/68` 六类、含 0 的 16,426 行」的唯一挡板，也是重复/错序最可能的元凶。守门单测要断言这 6 种值**都不**写回，防后人按「只排除 0」改回去。
- 若真机仍出现重复/错序，优先怀疑三处：守卫集合写错（漏了 4/128 等五种）、`W` 落在 64 值枚举之外（§1.5 已对真实数据实证关闭，只剩理论可能）、`& -9` 被 B 层挡住导致微信无法自行收缩（§5.1）。

[DONE-5]

---

## 给主会话的验收清单（以下**必须同时成立**才算修好）

> 环境：微信 8.0.48 / 2588 GP，设备 `467cf5bc`；四开关 `antirevoke.sns=true`、`antirevoke.sns_block=false`、`antirevoke.sns_sqlblock_off=false`、`antirevoke.sns_album_fix_off=false` ⇒ `blockEnabled=true sqlBlock=true albumFix=true`（`op=snsg sub=stat` 回读确认）。
>
> ⚠️ **样本必须先改**：`_r42_bit_b_report.md` §7 的验收流程是「发 → **进主页**(2→10) → 删 → 再进主页」，测的是 `src=10` 的旧 case，**根本覆盖不到本次缺口**。必须补一条独立流程：**发动态 → 全程不进好友主页（保持 `src=2`）→ 对方删除 → 再进好友主页**。且按 `moments.md` R27 §5 的规矩，先用 `op=sql` 点查（如 `select snsId,sourceType from SnsInfo where snsId=<id>`）确认目标 `src & 2 != 0`（别拿 `src=0` 的死来源当样本）。
> **真机基线（team-lead 已取，同一好友 `wxid_xlrbelf6hv0w22`，`localFlag` 均为 2）**：`snsId=-3397763077853081064` → `src=2`（**缺口样本**，删前没进主页）；`snsId=-3397762123896778223` → `src=10`（对照样本，删前进过主页）。互斥读数必须在**缺口样本**上取（§3.2）。
>
> 探针命令形态（⛔ `op=sql` 的 SQL **只包一层双引号**；再包一层单引号会被 SQLX 白名单拒；以 `docs/_r42_bit3_verify.md` §2 为准）：
> `am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op snsg --es sub blockstat`、
> `--es op sql --es q "select snsId,userName,sourceType,localFlag from SnsInfo where userName='wxid_xlrbelf6hv0w22' order by createTime desc limit 6" --ei n 20`、`--es op snsg --es sub trace --el id <snsId>`、`--es op snsdel --es sub stat`。

### 命名对照表（⛔ 取证前必读）

本文 §2.3 / §3.3 / §3.4 与验收清单 B、D 段里的计数名、日志文案是**设计期建议命名**；实现方（bitfix）落地时用了另一套字段。逐行对齐如下：

| 设计期建议命名（本文） | 语义（§3.2 态） | 实现落地（可 grep 的字符串） |
|---|---|---|
| `wbOk` | R 拦住并补位 | `shape=rw:<写回>`（`op=snsg sub=blockstat` 第 2 行） |
| `wbRemoveOk` | S 拦住但未补位 / 降级摘键 | `shape=rm:<摘键>` |
| `wbLost` | F 清位落地（没拦住） | `shape=nb:<没拦住>` |
| `wbMismatch` | F 回查失败 / 形态异常 | `shape=un:<形态异常>` |
| `wbDegrade` / `writeBackDegrade`（§2.3 第 5 条、§3.3、§3.4 第 3 条） | `(B&10)==0` 或 `B==null` 的主动降级 | ⛔ **无独立计数**：两类都走同一降级路径（`keep=null` → 只摘键），并入 `shape=rm:` |
| `Event.wb` / `BLOCK ev ... wb=` | 本次写回值 | `Event.line()` 的 `keep=<写回值\|->`（**不是** `wb=`）；`BLOCK ev` 新增 `keep=` 与 `shape=` |
| `BLOCK after ... (写回)` / `(摘键·未补位)` / `(回查失败)`（§3.4 第 1 条） | 形态文案 | `BLOCK after snsId=.. before=2 after=10 keep=10 act=writeback shape=BLOCKED_BY_REWRITE (写回保位)`；降级为 `act=remove` / `(摘键降级)`；`shape=` 取四类枚举名 |
| `afterOk` / `afterFail` | 回查成败（§3.1 旧语义） | **一致、无需映射**：仍是 HEAD 旧语义，只由 `finishAfterWrite` 是否取到 `bits` 决定 |

⛔ **真机取证一律以 `docs/_r42_bit3_verify.md` 与 `blockstat` 实际字段为准，本表左列是设计期命名、不是日志里能 grep 到的字符串。**

- `afterOk/afterFail`（回查成败）与 `shape=`（形态判定）是**两套正交计数**，别拿 `afterOk` 当「补位成功」（§3.1）。
- §3.3 恒等式含 `wbDegrade` 一项；实现无该独立计数、降级并入 `shape=rm:`，按实现口径核对时合并到 `rm` 即可（§3.3 与验收清单 B 段原文不改）。

[DONE-6]

### A. 静态 / 单测（上线前）

- [ ] `W(B) = B or 10` 抽成零依赖纯函数，单测覆盖真机全库 17 种真实旧值 `0/2/8/10/130/136/138/22/6/86/70/12/128/20/84/4/68`、`B==null` 降级、**幂等** `f(f(x))==f(x)`。
- [ ] **⛔ 守门单测（防后人改回去）**：断言 `(B & 10) == 0` 的 6 种值 `0/128/20/84/4/68` **明确返回「不写回」**（§1.4 裁决：只排除 0 会误复活私密 4 / 折叠 128 等）。这条是裁决的唯一代码级挡板。
- [ ] 单测反向守门：`B or 8` 在 `B=8/264` 上结果不含 bit1；`B or 2` 在 `B=2` 上结果不含 bit3；常量 `10` 在 `B=266/478` 上丢高位；`B and 0x0F` 在 `B=264` 上丢 256 且不含 bit1。
- [ ] **闭包实证回归**：单测断言 11 种可写回旧值的 `B|10` 全部落在 §1.5 的 64 值枚举内（含 `130|10=138`、`136|10=138`、`22|10=30`、`12|10=14`），锁住「真机库里 672 个理论 OUT 组合一行都没有」这个已关闭的结论。
- [ ] 全量单测基线不被破坏（改动前 771/785 例，改后按新增用例重算，`0 failures`）、`assembleDebug` BUILD SUCCESSFUL。

### B. 计数（`op=snsg sub=blockstat` + `sub=stat`）—— 全部同时满足

- [ ] `wbLost = 0`、`wbMismatch = 0`、`afterFail = 0`、`blkFail = 0`（四个恒 0 是硬红线）。
- [ ] `wbOk > 0`（开补位组、缺口样本上至少 1 次），事件形态为 `before=2 want=0 wb=10 after=10`；关补位组 `wbOk = 0`、改由 `wbRemoveOk` 记 `before=2 want=0 wb=- after=2`（§3.2 互斥读数）。
- [ ] ⛔ **不得用 `afterOk` 当「补位成功」计数**：它只证明回查读到行，开/关两组都会增（§3.1）。区分只靠 `before/after` 读数关系。
- [ ] 恒等式成立：`blocked + dropBlocked == wbOk + wbRemoveOk + wbDegrade + wbLost + wbMismatch`（§3.3）。
- [ ] `wbDegrade` 只对应 `(B & 10) == 0` 或 `B == null`；若降位路径出现 `wbDegrade` ⇒ 异常（§2.3 第 4 条）。
- [ ] `skipEmpty + dropEmpty = 0`（非 0 ⇒ `putInt` 失效或 `left==0` 处理错，先查日志再谈功能，§4④）。
- [ ] `sqlClear9 == sqlBlock9`（B 层全拦）、`blkOff = 0`、`sqlClear(& -3) = 0`、`sqlOwn = 0`（证明没走 `execSQL | 10` 的额外 UPDATE，§4②）。
- [ ] `delSns / delRow` 仍为全程零 DELETE；`rng / max` 不持续上涨（无重试风暴，§4⑤）。
- [ ] `blockEnabled=true sqlBlock=true albumFix=true`（四开关没被误拨；`albumFix` 是 `op=snsg sub=stat` 新增回读，§3.4 第 3 条）。
- [ ] `op=snsdel sub=stat` 的 `rows` 与写回前对同一批删除**相同**，`match - hits = 0`（灰标没被 `W` 顶替 `want`，§4③）。

### C. SQL（`op=sql`，只读）—— 三条全过

- [ ] 点查目标行：`select snsId,sourceType,type,createTime from SnsInfo where snsId=<id>` ⇒ 开补位 `sourceType=10` / 关补位 `=2`（缺口样本 `id=-3397763077853081064`）。对照样本 `id=-3397762123896778223` 两组都应恒为 `10`（幂等）。
- [ ] **枚举回归守门**（§1.5 已实证关闭，不再是探边界）：用**原样** 64 值 `where sourceType in (8,264,10,266,12,268,14,270,...,222,478) and userName='wxid_xlrbelf6hv0w22'`（完整列表见 §1.5）点查缺口样本 ⇒ **开补位返回 1 行、关补位返回 0 行**；对 `B=130/136/138` 这类高位折叠样本补 `|10` 后 IN 枚举的点查（应命中，已实证 `138` 在内）。
- [ ] `select sourceType,count(*) from SnsInfo group by 1`：降级集合 **`0/128/20/84/4/68` 六种值的行数全部不下降**（§1.4 裁决：没误复活「两处都不可见」的行，只查 0 会漏 5 种）；`10`（及对应高位值）的增量 ≈ 被拦动态条数，**不是**全库 `2` 的 15.9 万行都变 10（§4⑥ 的收窄是否生效）。

### D. 日志（`LogX` / `BLOCK` / `TRACE`）

- [ ] 每条 `BLOCK ev` 打出 `before / want / wb / after`，开补位成功样本 `before=2 want=0 wb=10 after=10`，关补位样本 `before=2 want=0 wb=- after=2`（§3.3）。
- [ ] `BLOCK after ...` 按 `(before, want, after)` 打出 `(写回)` / `(摘键·未补位)` / `(回查失败)` 三选一，**不再出现** `(键已摘)` 这个假话尾巴（§3.1 / §3.4 第 1 条）。
- [ ] 无 `BLOCK wb-lost` / `BLOCK wb-mismatch` / `BLOCK skip: empty values` / `BLOCK fail` / `sql-unmatched` 新增原文。
- [ ] after 校验触发链照常工作：`hasPending()` / `pending` 生效，`afterOk/afterFail` 有增（不是全 0）。✅ `record(..., cv=values, ...)` 实参在写回分支（489 行）与降级分支（542 行）**都已传**（team-lead 亲验，初稿担心的「静默失效」已证伪）；此条仅作回归守门 —— 若 `afterOk` 恒 0，先查是否重构时弄丢了该实参。
- [ ] `op=snsg sub=trace --el id <id>` 连续 30 分钟采样：开补位 `src` 恒 `10`、关补位 `src` 恒 `2`，**都无跳变**（幂等、无竞态、不越写越大，§4⑤）。
- [ ] 加位写仍被放行：`BLOCK drop seen/blocked/allow` 里 `allow` 随 `2→10` 正常增长，`drop blocked` 只对应真降位（`_r42_bit_b_report.md` §7.4 的「未误伤」证据不退化）。

### E. 界面表现（截图 / `uiautomator dump`）

- [ ] 好友删掉缺口样本（`id=-3397763077853081064`、`src=2`、**从未进过主页**）后：**朋友圈时间线仍可见** 且 **好友主页相册列表能查到该条**（本次缺口的唯一产品判据）。
- [ ] 对照样本（`id=-3397762123896778223`、`src=10`，发→进主页→删→再进主页）仍保持 `_r42_bit_b_report.md` §9 的「时间线 + 主页都可见」，**不回归**。
- [ ] 好友主页**无重复条目、无错序**（旧 `createTime` 的行没以历史位置冒出来）—— 这是 §5 触发回滚的症状，出现即判不通过。
- [ ] 相册里不冒出「私密 / 过期 / 很久以前 / 广告 / 折叠长文」等本不该在的动态（§4⑥）；若冒出 ⇒ 收窄没做或 `(B & 10) == 0` 守卫漏了（尤其别只挡 0 而漏 4/128）。
- [ ] 时间线灰标仍正常（`match>0 && hits==match && dump 有「该动态已被作者删除」节点`），主页相册按既定决策不标灰。

### F. 开关 / 消融对照（单变量）

- [ ] ⭐ **反向对照（必做，team-lead 拍板）**：调试键 `antirevoke.sns_album_fix_off=true` **只关补位、保留甲/A/B 两层**，对**同一缺口样本（`id=-3397763077853081064`）、同一复现流程**重跑：
  - 开补位（`album_fix_off=false`）：`BLOCK after ... before=2 after=10 (写回)`、`wbOk` 增、`afterFail` 不涨 ⇒ **好友主页出现该条**；
  - 关补位（`album_fix_off=true`）：`BLOCK after ... before=2 after=2 (摘键·未补位)`、`wbRemoveOk` 增、`afterFail` 不涨 ⇒ **好友主页不出现该条**；
  - 两组 `before` 都是 2、`after` 分别是 `10` 与 `2`，**单个读数互斥、不可同时成立**；`op=snsg sub=stat` 回读 `albumFix=false` 确认开关真生效；`sqlClear9/sqlBlock9/blkFail` 两组应一致（证明只动补位这一层、没连带关掉 A/B）。这组是「拦住并补位」与「没拦住/没补位」的自证核心（§3.2）。
- [ ] **关主开关** `antirevoke.sns=false`（回读确认 `antirevoke.sns_block` 未置 true 覆盖）：`skipOff/dropOff` 增长、`wbOk=0`，微信清位写原样执行 ⇒ 时间线与主页按微信原行为消失（§4①）。
- [ ] ⚠️ **重做 `_r42_bit_b_report.md` §8 反向消融时必须先停补位**（`album_fix_off=true`）：否则 `& -9` 削成 2 后紧随的 `want=0` 会被写回自愈成 10，得出「B 层不必要」的错误结论（§4① / §5.1）。原判据链 `sqlClear9↑ + sqlBlock9 不变 + blkOff↑ + src 10→2` 要在「补位关」的前提下才成立。

### G. 回归红线（任一不满足即不算修好）

- [ ] `blkFail = 0`、`afterFail = 0`、`wbLost = 0`、`wbMismatch = 0`、`sqlOwn = 0`、`skipEmpty+dropEmpty = 0`。
- [ ] 无 `SQLiteException` / `IllegalArgumentException` / 朋友圈闪退（`left==0` 的兜底仍在，`putInt` 失败有回退，§4④）。
- [ ] `sqlClear9 == sqlBlock9`、`sqlClear = 0`、`delRow` 零增长（三层拦截 + 方案 B 的既有真机结论不被破坏）。
- [ ] 冷启后重进主页，`src` 仍是 `W`（写回值持久、不依赖进程内状态；若采用 §4⑥(b) 的台账方案则此条必挂，故不推荐）。

---

**一句话总判据**：一条「删除前从未进过好友主页、`src=2`」的动态（缺口样本 `id=-3397763077853081064`），被对方删除后，库里 `sourceType` 从 `2` 变成 `10` 并稳定不动（`before=2 after=10`、`afterOk` 增），时间线与好友主页相册**同时**能查到它；把 `antirevoke.sns_album_fix_off` 拨到 true 重跑同一流程，读数必须变成 `before=2 after=2`、主页不出现（**两组读数互斥**）；且降级集合 `0/128/20/84/4/68` 的行数不下降、四个恒 0 计数全为 0、主页无重复错序。缺任何一条都不算修好。

[DONE-7]
