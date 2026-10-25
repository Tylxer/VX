# R21c · 防撤回提示行带上「被撤回内容」摘要

状态：**代码与单测已落地，`testDebugUnitTest` 待跑 / 已跑**（见 §5）
改动范围：
- 新建 `data/HintTextLogic.kt`（零 Android 依赖纯 object）
- 新建 `data/HintTextLogicTest.kt`（JVM 单测）
- 改 `feature/RevokeRescue.kt` 的 `hintTextFrom` / `insertHintOnce`（仅这两处）
- `feature/AntiRevoke.kt` **零改动**（接口方案见 §1）

---

## §1 接口方案选型

### 1.1 现状：调用点那一刻手上没有 `origType` / `origContent`

`insertHintOnce` 的唯一调用点在 `AntiRevoke.guardMessage()`：

```
if (RevokeRescue.record(id, talker, revokeText, extra)) {
    RevokeRescue.applyOne(id, revokeText, extra)
    if (c.showHint && !talker.isNullOrBlank()) {
        RevokeRescue.insertHintOnce(id, talker, revokeText)   // ← 手上只有 id/talker/revokeText
    }
}
```

`origType` / `origContent` 是 `record()` 内部查微信库才拿到的（`queryOriginal`），
`guardMessage` 此刻拿不到。三个可选方向对比：

| 方向 | 做法 | 代价 | 是否破坏硬约束 |
|---|---|---|---|
| (a) `record()` 返回 `OrigRow` | 改 `record()` 返回值，guardMessage 拿到后传给 `insertHintOnce` | 改 `record()` 签名 + 改 `AntiRevoke.kt:529/534` 两处调用点 | **破坏**「record() 失败返回 false 不许插提示」——`if (record(...))` 结构要改成 `if (record(...) != null)`，语义漂移 |
| (b) `insertHintOnce` 内部回查台账 | `insertHintOnce` 用 `GroupDb.getRescued(msgId)` 拿 `origType/origContent` | 调用点 `AntiRevoke.kt:534` 零改动 | 不破坏 |
| (c) 其他 | — | — | — |

### 1.2 选 (b)，且**并非「多一次查询」**

关键事实：**现有 `insertHintOnce` 本来就已经查了一次 `getRescued(rescuedMsgId)`**（为了读
`hintMsgId` 做判重）：

```kotlin
val existing = db.getRescued(rescuedMsgId)?.hintMsgId ?: 0
if (existing != 0L) return existing
```

选 (b) 后把这行改成「取整行 `Rescued`」，**同时**读 `hintMsgId` + `origType` + `origContent`，
查询次数不变（还是那一次），只是多读两个字段。所以 (b) 的成本比 team-lead 最初设想的
「多一次查询」更低。

### 1.3 选 (b) 的理由（逐条）

1. **调用点零改动**：`AntiRevoke.kt:534` 保持 `insertHintOnce(id, talker, revokeText)` 原样，
   天然满足「改 AntiRevoke 调用点」这一项 —— 不是不改，是**不需要改**。
2. **保住两条硬约束**：
   - `record()` 返回值仍是 Boolean，`if (record(...)) { ... }` 结构不动，`record()` 失败
     （查不到行 / 台账没写进）时 `insertHintOnce` 根本不会被调。
   - `insertHintOnce` 判重语义 `hintMsgId != 0L` 原样保留。
3. **数据是权威的**：`getRescued` 读到的 `origType/origContent` 正是 `record()` 刚写进台账的
   那一份（`recordRescued` 是「首记录为准」），不存在 `record()` 与 `insertHintOnce` 之间
   数据不一致的窗口。
4. **时序安全**：`record()` 在 `guardMessage` 里先执行、成功后才会调 `insertHintOnce`，
   所以 `insertHintOnce` 内 `getRescued` 必然命中（`getRescued` 返回 null 属不可能路径，
   此时返回 0 不插提示，仍是安全降级）。

### 1.4 选型附带说明：`hintTextFrom` 签名变化

`hintTextFrom(revokeContent)` 现在需要摘要，签名改为
`hintTextFrom(revokeContent, origType, origContent)`。grep 确认它**只有** `insertHintOnce`
一个调用方，改签名无其它影响面。

---

## §2 各 type 的摘要判据表

> 判据入口：`HintTextLogic.summary(origType, origContent)`。返回值统一是「显示串」：
> - 有实际内容 → 直接返回内容（文本/时长/文件名/被引用原文）
> - 只有类型可判 → 返回 `[标签]`
> - 什么也拿不出（空文本、系统消息）→ 返回 `""`（空串 = 无内容可展示）

| type（低 16 位语义） | 判据 | `summary` 返回 | 最终文案（`who="\"张三\""`） | 依据 |
|---|---|---|---|---|
| 1 文本 | `content` 非空 → 压成单行 + 截断 | `你好` | `"张三" 尝试撤回一条消息（已阻止）：你好` | team-lead 实证 orig_type=1→57 条 |
| 1 文本（空） | `content` null/空白 | `""` | `"张三" 尝试撤回一条消息（已阻止）` | 无内容可展示 |
| 3 图片 | 类型标签 | `[图片]` | `…（已阻止）：[图片]` | orig_type=3→9 条；图片 content 本就是 NULL |
| 34 语音 | 解析 `content` 第 2 段时长（毫秒） | `[语音 30秒]` | `…（已阻止）：[语音 30秒]` | orig_type=34→6 条；见 §3 |
| 42 名片 | 类型标签 | `[名片]` | `…（已阻止）：[名片]` | ForwardLogic `MSG_TYPE_CARD=42`（中置信） |
| 43 视频 | 类型标签 | `[视频]` | `…（已阻止）：[视频]` | ForwardLogic `MSG_TYPE_VIDEO=43`（高置信） |
| 47 表情 | 类型标签 | `[表情]` | `…（已阻止）：[表情]` | orig_type=47→1 条 |
| 48 位置 | 类型标签 | `[位置]` | `…（已阻止）：[位置]` | ForwardLogic `MSG_TYPE_LOCATION=48`（中置信） |
| 49 应用消息（XML） | 见 §2.1 子类型 | `[文件 x]` / `[引用 x]` / `[链接 x]` / `[视频]` / `[表情]` / `[聊天记录]` / `[消息]` | `…（已阻止）：[文件 report.docx]` | team-lead 明确「文件(49)/引用(49 子类)」 |
| 10000 系统消息 | 无内容可展示 | `""` | `"张三" 尝试撤回一条消息（已阻止）` | ForwardLogic `MSG_TYPE_SYSTEM=10000` |
| **未知**（如 805306417 / 822083633） | 兜底占位 | `[消息]` | `…（已阻止）：[消息]` | team-lead 明确「未知 type → 占位文案，不崩」 |

### 2.1 应用消息（type 49，content 是 XML）子类型

- **引用**（XML 含 `<refermsg>`）：优先取 `<refermsg><content>` 原文 → `[引用 原文摘要]`。
  这是「识别被撤回的那一条」最有价值的场景：引用消息被撤回时，用户最想看到的是**被引用的
  原文**，而不是回复正文。
- **文件**（`<type>6</type>`）：取 `<title>` → `[文件 文件名]`。
- **链接**（`<type>5</type>`）：取 `<title>` → `[链接 标题]`。
- **视频 appmsg**（`<type>4</type>`）→ `[视频]`；**表情 appmsg**（`<type>8</type>`）→ `[表情]`；
  **合并转发聊天记录**（`<type>19</type>`）→ `[聊天记录]`。
- 其余子类型 / content 为 null/空白 → `[消息]`。

### 2.2 文本截断与单行化

- **上限 `MAX_TEXT = 30`**（UTF-16 码元计数，中文 BMP 字符 = 1 个码元）。
  理由：提示行是单行，前缀 `"张三" 尝试撤回一条消息（已阻止）：` 已有约 15 个全角字符，
  再留 30 字符摘要，总长约 45 字符，微信会话列表一屏放得下且足够辨识；超过 30 就
  `take(30) + "…"`（省略号占 1 字符）。
- **换行折叠**：`content.replace(Regex("\\s+"), " ").trim()`，把 `\n`/`\r`/`\t`/连续空格全部
  折成单个空格，保证提示行**单行**。

---

## §3 语音时长单位论证

### 3.1 问题

语音 `content` 是索引串 `sender:duration:flag...`（段数不固定），`duration` 是纯数字，但
单位没写在串里。team-lead 明示「**没被授权直接假定单位，要论证并给判据**」。

### 3.2 判据：为什么是**毫秒**（不是秒）

| 样本 `duration` | 若单位=秒 | 若单位=毫秒 |
|---|---|---|
| `30478` | 8.46 小时 | **30.478 秒 ≈ 30 秒** |
| `20785` | 5.77 小时 | 20.785 秒 ≈ 21 秒 |
| `12424` | 3.45 小时 | 12.424 秒 ≈ 12 秒 |

- **判据 1（量级排除法）**：微信语音有 60 秒硬上限。三个样本若按「秒」理解分别是 3.5～8.5
  小时，物理上不可能；按「毫秒」理解是 12～30 秒，正好落在 0～60 秒的正常语音区间。
  ⇒ 秒单位被排除。
- **判据 2（与微信惯例一致）**：微信 message 表语音行 `content` 的通用格式是
  `{wxid}:{duration_ms}:{voice_type}`，时长字段是毫秒（开源微信消息解析器通用结论，本项目
  `ForwardLogic` 也确认语音 content 是「索引串、不含文件路径」）。
- **判据 3（第 3 段不是时长）**：样本第 3 段是 `0`/`0`/`1`，量级与语音格式标志位吻合
  （`1` 出现在 `:20785:1:` 这条 4 段形态里），与「时长」无关，不参与单位判断。

### 3.3 换算规则（锁定在单测里）

```
sec = max(1, round(duration_ms / 1000))    // 四舍五入，最低 1 秒
30478 → 30秒   20785 → 21秒   12424 → 12秒
```

解析失败（content 为 null / 空串 / 无冒号 / 段数 <2 / 第 2 段非数字 / `duration<=0`）→
退化为 `[语音]`（不带时长），**不崩**。

### 3.4 单位论证的「未闭环」部分（见 §6）

判据 1/2/3 是强证据，但**最终真机确认**仍需一条端到端对账：发一条已知时长的语音
（如录音 30 秒），`ardump` 看该行 `content` 第 2 段是否 ≈ `30000`。这条留主会话补取。

---

## §4 测试清单（`HintTextLogicTest`，逐条锁什么）

| # | 用例 | 锁定的判据 |
|---|---|---|
| 1 | 文本正常 | type=1 `"你好"` → `summary="你好"`；`buildHint` 拼出 `：你好` |
| 2 | 文本 null/空串/纯空白 | → `""`（空串 = 无内容） |
| 3 | 文本超长 | 40 字符 → 前 30 字符 + `…` |
| 4 | 文本含换行 | `"第一行\n第二行"` → `"第一行 第二行"`（单行） |
| 5 | 语音正常 | `wxid_3dmyz2iqavq822:30478:0` → `[语音 30秒]`（**锁毫秒单位**） |
| 6 | 语音四段变体 | `:20785:1:` → `[语音 21秒]`（段数不固定也能取到时长） |
| 7 | 语音降级 | null / `""` / 无冒号 / 第 2 段非数字 / `0` → `[语音]` |
| 8 | 语音不泄漏发送者 | `wxid_alice:30478:0` → 摘要里**不含** `wxid_alice`（锁群聊歧义） |
| 9 | 图片/视频/表情/位置/名片 | 3→`[图片]` 43→`[视频]` 47→`[表情]` 48→`[位置]` 42→`[名片]` |
| 10 | 未知 type 占位 | `805306417` → `[消息]`；`999999` → `[消息]`（不崩） |
| 11 | 应用消息-文件 | type=49 XML `<type>6</type><title>report.docx</title>` → `[文件 report.docx]` |
| 12 | 应用消息-引用 | type=49 XML 含 `<refermsg><content>被引原文</content>` → `[引用 被引原文]` |
| 13 | 应用消息-未知子类型/null | → `[消息]` |
| 14 | `whoFrom` 提取 | `"\"张三\" 撤回了一条消息"` → `"\"张三\""`（保留引号，与原行为一致）；null/空/裸 `撤回了一条消息` → null |
| 15 | `buildHint` 无摘要 | `buildHint(who, "")` → 仅 `who 尝试撤回一条消息（已阻止）`，不带冒号 |
| 16 | 系统消息 | type=10000 → `""` |

---

## §5 验证结果

命令：`python tools/gradle_run.py testDebugUnitTest`（必须走这个脚本，直接 gradle 会
`ClassNotFoundException: test worker`）。

控制台（脚本过滤后）：

```
EXIT=0
BUILD SUCCESSFUL in 51s
```

测试结果 XML（`app/build/test-results/testDebugUnitTest/*.xml` 汇总）：

```
files=28 tests=548 failures=0 errors=0 skipped=0
```

- 基线 518 条 → 现在 548 条，**无倒退、无 failures、无 errors**。
- 本 lane 新增 `HintTextLogicTest`：**16 条全部通过**（`tests=16 failures=0 errors=0`），
  锁定的判据见 §4 的 16 行。
- 548 − 518 = 30 与 16 的差，是**其他 lane 同期新增的单测**（`MomentsNotifyLogicTest` 等），
  与本 lane 无交集。

---

## §6 遗留问题 / 需要主会话真机补取什么

1. **语音时长单位端到端对账**（§3.4）：发 30 秒语音 → `ardump` 看 content 第 2 段是否 ≈30000。
2. **`805306417` / `822083633` 的真实语义**：两者低 16 位都是 `49`（`0x30000031` /
   `0x31000031`），即「应用消息 + 高位标志」。本轮按 team-lead 口径当「未知」处理（占位
   `[消息]`，不掩码）。若真机确认 `0x30000000` 是普通 flag 而非新类型，可考虑改用
   `origType and 0xFFFF` 掩码，让这 5 条也能走应用消息 XML 解析拿到更丰富的摘要。
   —— 属于可选的后续增强，不影响本轮验收。
3. **`[消息]` 占位文案是否够用**：未知 type 只给 `[消息]`，对「识别是哪一条」帮助有限。
   若真机发现某类高频未知 type，补进映射即可（纯逻辑层，改起来便宜）。

---

[DONE]
