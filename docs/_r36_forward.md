# R36 实现：G1「原生转发」接通

> 实现 lane：`r36-forward`　模块：`wxgroup`（微信 8.0.48 / 2588 GP 的 LSPosed 模块）
> 任务：把 `ForceForward.nativeForward()` 从「恒 false」改成真能发、且带「转发」标记的链路。
> 边界：只改 `feature/ForceForward.kt`、`feature/ForwardLogic.kt`、`feature/ForwardNativeLogic.kt`（新建）、对应单测。
> ⛔ 未做：不跑 gradle 构建、不碰 adb、不装包（主会话统一构建）；不改 `core/`（含 `Anchors848.kt`）、`HookEntry.kt`、`data/`。

---

## 0 进度

| 段 | 状态 |
|---|---|
| 1 取证要点 | [DONE] 1 取证要点 |
| 2 现状 | [DONE] 2 现状 |
| 3 方案 | [DONE] 3 方案 |
| 4 实现 | 进行中 |
| 5 单测与变异 | 待做 |
| 6 反射点档位清单 / 未做项 | 待做 |

---

## 1 取证要点（逐条读原文后的摘录）

来源：`docs/_r29_g1_forward_forensics.md`（G1，379 行）+ `docs/_r29_g2_msg_object.md`（G2，294 行）
+ `docs/_r16_g2_msgsnap.md` §3.2/§3.3（h9 字段与 getter 实证表）。

### 1.1 核心结论（G1 §0 / §5，①）

- **微信没有独立的「原生转发入口」**。转发 = 普通发送 = 同一条链：
  `hl0/r1.b(talker)` → `hl0/q1`（builder）→ NetScene（`hl0/q0` 文本 / `modelimage/j2` 图片）。
- 差别只有两点：① 往 `q1` 多挂一个 `hl0/m`（ForwardInfo，setter 是 `q1.f(m)`）；② 设 `q1.h`（int 子类型，**无 setter，直写字段**）。
- `hl0/m` 六字段由其 `toString` 明文钉死（①）：

  | 字段 | 类型 | 语义 | `<init>` 默认 |
  |---|---|---|---|
  | `a` | long | forwardFromImgId（**源图 CDN imgId**） | -1 |
  | `b` | long | forwardMsgId（**源消息 msgId**） | 0 |
  | `c` | String | forwardSnsLocalId | "" |
  | `d` | String | forwardMsgUuid | null |
  | `e` | String | forwardMsgSvrId | null |
  | `f` | String | talkerUsername（**源会话 talker**） | null |

- `q1.h` 值表（①，`t81/g.l` + `qf4/o4.O8/Q8` + `MsgRetransmitUI.z6` 三处交叉）：

  | h | 含义 |
  |---|---|
  | 2 | 重发/引用 |
  | **4** | **文本转发（吃 `m.b`=forwardMsgId）** |
  | 5 | 普通文本（吃 `q1.g` Object） |
  | **13** | **图片转发** |
  | 19 | 多图转发 |
  | 0 | 文本走 default 空 `q0` |

- 文本推荐序列（G1 §5.1）：`r1.b(talker)` → `q1.e(content)` → `new m(){b=源msgId, f=源talker}` → `q1.f(m)` → `h=4` → `q1.b()`。
- 图片推荐序列（G1 §5.2）：复用 `FileSender` 的字段序列（已逐字段复刻 `qf4/o4.O8`），只把 `new m()` 换成填了源图标识的 `m`，`h=13` 不变。
- 判据（G1 §6，①）：`l1.a()` 返回 true = 入队成功；`false`/NPE = 未接通。

### 1.2 拿源消息对象（G2，①）

唯一全 ① 路径：`长按 View → 向上找列表 → 定位 position → adapter.getItem(pos) → h9`。

- 列表形态按**继承链**判定（不能用类名 contains，运行时是子类）：
  `ChattingRecyclerView`→recycler、`ChattingListView`→listview。
- position：listview 走 `getPositionForView(View)`；recycler 走混淆名候选链
  `F0 / G0 / getChildAdapterPosition / getChildLayoutPosition`（`ChattingRecyclerView.getPositionForView` 是恒 -1 的空桩）。
- adapter：`MMChattingListView.getBaseAdapter()`（未混淆，优先）→ 退回 `ChattingListView/ChattingRecyclerView.m()`（混淆名）。
- `getItem(int)` 返回 `Object`（运行时是 `h9`），**按名字反射取值，不做强转**。
- h9 取值（`jj/m7` 层，字段名未混淆）：

  | 需要 | getter（公开） | 字段（PRIVATE） |
  |---|---|---|
  | msgId | `getMsgId()J` | `field_msgId` |
  | msgType | `getType()I` | `field_type` |
  | 正文/XML | `getContent()String` | `field_content` |
  | 图片路径 | — | `field_imgPath` |
  | talker | `C0()String`（混淆名） | `field_talker` |
  | 服务端 msgId | — | `field_msgSvrId`（`J`，R16 §3.2 ①） |

### 1.3 必须在文档里钉死的「未取证 / ② 档」

| # | 事项 | 档 | 内容 | 错了会怎样 |
|---|---|---|---|---|
| A | 文本转发的「源 msgId」是否参与落库 | ② | G1 附：`q0.(String,String,II,J)` 的 J 参数在构造器体内未被引用 ⇒ 文本转发与重发**字节码层面几乎等价**。保守做法仍填 `m.b` | 对端可能看不到「转发」标记，但**不影响消息发出**（最坏 = 等价于现在的降级重发） |
| B | `m.e`(forwardMsgSvrId) 的取值格式 | ② | 字段 `e` 是 `String`，我填 `field_msgSvrId` 的十进制字符串（long→String） | 服务端若不认，最多忽略该字段；不参与 `t81/g.l` 的 h==4 分派（那条只读 `m.b`） |
| C | `m.a`(forwardFromImgId) | ③ 未做 | 没有①档的 CDN imgId 获取路径（不在 h9 字段表里） ⇒ 恒填默认 -1 | 图片转发会**重传**（与现在降级重发一致），但 `h=13`+`m.b` 仍走转发分派 |
| D | 视频/普通文件/语音 | ③ 未做 | `modelimage/o3.j()`（视频，`h=20`）与 `qf4/r5`（文件）未取证 | 不接，保持 SKIP |

[DONE] 1 取证要点

---

## 2 现状（读代码后）

- `ForceForward.nativeState()`（`:624`）**恒返回 `UNKNOWN`** ⇒ `ForwardLogic.planFor` 永不挑 `NATIVE`，
  真机上 100% 走降级重发（`resendText`，`TextSender` 已真机验证）。
- `nativeForward()`（`:633`）只打一行 `native anchor not located yet (G1)` 并返回 false。
- `snapshotNow()`（`:460`）**只走 View 树取文本**：`kind` 恒 `UNKNOWN`、`localPath` 恒空 `""`。
  即「G2 的消息对象链路」在探针 `msgsnapReport()` 里已经跑通，但**生产路径没用它**
  ⇒ 现在连「源 msgId」都拿不到（`Snapshot` 里压根没这个字段）。
- `ForwardLogic.Snapshot` 字段：`kind / text / localPath / fileExists / sourceChat / raw`（`raw: Any?` 是给 hook 侧传宿主对象预留的）。
- `runBatch()`（`:570`）：每个目标 `runCatching` 一次，失败只计数，**没有「原生失败→降级」的回落**。
- `ForwardLogic.planFor`：`AVAILABLE` 时对所有 covered kind 一律 NATIVE —— 但原生只支持 TEXT/IMAGE，
  必须加「种类支持」判据，否则名片/位置/链接会被送进原生链然后撞壁。

[DONE] 2 现状

---

## 3 方案

1. **新增 `feature/ForwardNativeLogic.kt`（零 Android 依赖，纯函数）**：
   - `q1.h` 子类型映射（`subTypeOf` / `subTypeValue`）；
   - ForwardInfo 六字段规格（`forwardInfoOf(snap)` / `hasForwardMark`）；
   - 失败分阶段（`FailStage`：CLASS / METHOD / CONSTRUCT / SEND / MATERIAL）+ 结构性判定（`isStructural`）；
   - 原生失败后的回落策略（`fallbackStrategy`）与原生状态迁移（`nativeStateAfter`）。
2. **`ForwardLogic`**：给 `Snapshot` 加 `msgId` / `msgSvrId`；`planFor` 加「种类是否被原生支持」判据。
3. **`ForceForward`**：
   - `install()` 存 classLoader；`nativeState()` 改成**运行时探测**（`hl0.r1` / `hl0.m` 能否找到）。
   - `snapshotNow()` 接 G2 链路拿 h9 ⇒ 填 kind / msgId / msgSvrId / localPath / fileExists / raw。
   - `nativeForward()` 真发（文本 h=4、图片 h=13），返回 `(ok, stage)`。
   - `runBatch()`：原生失败 ⇒ 按 `fallbackStrategy` 回落降级重发；结构性失败 ⇒ 本批后续不再试原生。
4. **fail-safe 铁律**：任何一步反射失败都不抛到微信；`nativeForward` 失败一律回落，
   **绝不重复发**（`q1.a()` 拿到 l1 后就不再调 `q1.b()`）。

[DONE] 3 方案

---

## 4 实现（代码已全部落盘）

> ⚠️ lane `r36-forward` 在第 4 段（实现）中途被**上游 429 频率限制**打断（24m57s），
> 单测与文档未写完。代码本身已落盘完整（类闭合、单测闭合、可编译），
> 第 5/6 段与本节由**主会话**补齐并实测。

### 4.1 变更文件

| 文件 | 改动 | 说明 |
|---|---|---|
| `feature/ForwardNativeLogic.kt` | **新建** 213 行 | 纯判据层，零 Android 依赖：子类型映射、ForwardInfo 六字段、失败分阶段、回落策略 |
| `feature/ForceForward.kt` | +594 行 | `nativeProbe` / `nativeForward` / `nativeForwardText` / `nativeForwardImage` / `attachForwardInfo` / `nativeReadyReport`；`snapshotNow` 接 G2 链路取 h9 |
| `feature/ForwardLogic.kt` | +18 行 | `Snapshot` 加 `msgId` / `msgSvrId`；`planFor` 加「原生只支持 TEXT/IMAGE」判据 |
| `test/.../ForwardNativeLogicTest.kt` | **新建** 15 例 | 见 §5 |
| `data/Keys.kt` | +1 开关 | `chat.forward_native`（默认 **false**，见 §4.2） |

### 4.2 ⭐ 主会话加的闸门：原生转发**默认关闭**

lane 的实现把 `nativeState()` 改成「运行时探测通过 ⇒ `AVAILABLE`」⇒ `planFor` 会挑 `NATIVE`
⇒ **默认启用**。这是本轮唯一没经过真机验证、却会**写微信内部对象**的路径：

- 写 `hl0/q1` 的 `h` 字段（子类型）、挂 `hl0/m`、再直接调 `q1.a()/q1.b()` 入队；
- 锚点探测只证明「`hl0.r1` / `hl0.m` 类与方法还在」，**不证明语义对**；
- 填错的后果：最轻是「消息发了但没有转发标记」（界面看不出来 = 假成功），最重是发出空壳或让微信抛异常。

⇒ 加 `Keys.FORWARD_NATIVE`（`chat.forward_native`，`PrefKind.SWITCH`，默认 `"false"`），
`nativeState()` 开头即判，关闭时连探测都不做、直接 `UNKNOWN` ⇒ **行为与引入本链路之前逐字节相同**
（一律走已真机验证的降级重发）。

开法：`kvset chat.forward_native true` 后重启微信。
> key 前缀必须与 `Prefs.ALL` 的域一致（`PrefsVisibilityTest.keyPrefixMatchesDomainGrouping`），
> 所以写成 `chat.forward_native` 归「聊天」域，**不是** `forward.native`（第一版写错过，单测立刻拦下）。

[DONE] 4 实现

---

## 5 单测与变异（主会话实跑）

### 5.1 用例

`ForwardNativeLogicTest.kt`：**15 例**，覆盖
子类型映射（4 种 Kind × text/image/multi）、`subTypeValue` 与 `SUBTYPE_*` 一致性、
ForwardInfo 六字段取值与缺省（msgId 拿不到 / msgSvrId ≤ 0 / talker 空）、
`hasForwardMark` 的「只有 msgId」「只有 svrId」「都没有」三态、
`isStructural` 的 5 种 stage、`nativeStateAfter` 迁移、`fallbackStrategy` 与 `planFor` 降级段一致性。

### 5.2 变异实跑（两组，均精确变红后还原）

| 变异 | 位置 | 结果 | 等价变异？ |
|---|---|---|---|
| `hasForwardMark` 的 `\|\|` → `&&` | `ForwardNativeLogic.kt:141` | **1 红**（15 例中） | 否 |
| `isStructural` 把 `SEND` 也算结构性 | `ForwardNativeLogic.kt:189` | **2 红** | 否 |

还原后 `grep` 复核两处判据文本（`141: … || !info.svrId.isNullOrBlank()`、`189: … FailStage.SEND -> false`），
全量 **869 例 0 失败**。

### 5.3 全量闸门

- `assembleDebug` + `compileDebugUnitTestKotlin`：**BUILD SUCCESSFUL**
- `tools/_junit.sh`：**OK (869 tests)**（R32 时为 802 ⇒ +67，含 R35 `SnsDeletedTagLogicTest` 与本轮 15 例）
- APK：3,546,908 B

[DONE] 5 单测与变异

---

## 6 反射点档位清单 / 未做项

| 反射点 | 用途 | 档 | 出处 |
|---|---|---|---|
| `hl0.r1.b(String)` | 建 `q1` builder | ① | G1 §5.1 |
| `hl0.m` 构造 + 字段 `a/b/c/d/e/f` | ForwardInfo | ① | G1 §2.1（`toString` 明文） |
| `q1.e(String)` / `q1.f(m)` | 设正文 / 挂 ForwardInfo | ① | G1 §2.2 |
| `q1.h`（int 字段直写） | 4=文本转发 / 13=图片转发 | ① | G1 §2.4 值表（三处交叉） |
| `q1.a()` → `l1.a()` | 同步入队，拿布尔 | ① | G1 §3.1 |
| `q1.b()` 兜底 | 异步入队（`q1.a()` 拿不到 l1 时**才**用） | ① | `TextSender` 真机验证过 |
| `m.e` = svrId 十进制字符串 | forwardMsgSvrId | **②** | 字段是 String、源是 long，格式推断；错最多被忽略 |
| `m.a` 恒 -1 | forwardFromImgId | **②** | 无①档 CDN imgId 获取路径 ⇒ 图片会重传（与降级一致） |

**未做（有意）**：多图（`h=19`）、视频（需 `modelimage/o3` + `h=20`）、普通文件（需 `qf4/r5` + CDN 上传）、
`m.d`(uuid) 恒 null。`subTypeOf` 只放行 TEXT/IMAGE，**不许为「覆盖全」放宽**。

**真机验证判据（开 `chat.forward_native true` 后）**：
1. 转一条文本 → 应出 `FORWARD native ok`，对端**能看到「转发」标记**（唯一有效判据，日志不算）；
2. 转一张图 → 同上；`m.a` 恒 -1 ⇒ 会重传，属预期；
3. 反向：`kvset chat.forward_native false` + 重启 → 日志无 `FORWARD native probe`，转发出标记消失 ⇒ 闸门真的管得住；
4. 任何失败都必须落 `FORWARD native fail stage=CLASS|METHOD|CONSTRUCT|MATERIAL|SEND` **且消息仍发出**（回落生效）。
> ⚠️ `FORWARD native ok` 只证明「入队返回 true」，**不证明对端看到转发标记** —— 判据只能是 1/2 两条人眼观察。

[DONE] 6 反射点档位

---

## 7 主会话收尾（2026-10-30）

- lane 被 429 打断在建文档前，**代码落盘完整**（`ForceForward.kt` 类闭合、单测闭合），主会话复核 6 个反射点后补闸门、补文档。
- 编译 / 全量单测 / 变异 / APK 均见 §5。
- 待真机（未做，需用户指令）：§6 的四条判据。

[DONE] ALL
