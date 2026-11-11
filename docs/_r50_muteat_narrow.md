# R50「群聊防止被@未生效」假设收窄（第三轮 · 选择器写入格式纠偏）

> 状态：**本轮为第三次静态收窄（选择器写入格式纠偏），未经真机探针验证**。用户新增口述与主会话选择器链路核对均不构成「已确认根因」，一切仍待探针证实。
> 边界：本轮**只修改本文件**，不改代码、不跑场景、不下真机（无 adb/install/kvset）。改动方案留待用户授权后的开发轮次。
> 结论来源纪律：dex 里有 SQL 字符串 ≠ 该业务调用它；「A 推 B」时序结论必须标假设；结论对着**用户能看见的事实 / 日志行**写，不对内部计数器（matched/muted/bound/known）写最终判据；单测须变异验证。
> 上一轮产物：`docs/_r50_muteat_rootcause.md`；本文件为单一权威产物，历次收窄在此迭代。

---

## 0. 本轮输入：用户口述真机事实（全部待证实，非已验证）

| # | 口述（00:27 及前两轮综合） | 定性 |
|---|---|---|
| F1 | **三个群**：①「测试群聊」**生效**；②「宁波快乐桌游 狼人杀 阿瓦隆」**@所有人 不生效**；③「酷迪学府壹号狼人杀馆」**@所有人 不生效** | 待探针证实（「生效/不生效」均为口述） |
| F2 | 「@我」在**测试群聊也生效**；另两群「@我」**未测**，只知 @所有人 不生效 | 测试群聊 @我 生效待证实；⛔ 另两群 @我 **不许写成失效**，登记为待确认项 |
| F3 | 三群**只在允许名单**（排除名单为空） | 待探针证实（`scope` 看 `exclude=[]`） |
| F4 | 「**是在选择器里选择的**」⇒ 名单元素格式由选择器代码决定，非用户手填 | 代码事实（见 §0.1） |

### 0.1 选择器写入链路（代码事实，主会话已核，直接采信）

- `Rows.kt:203-211`：`PrefKind.ENTER` 行点击只走 `onEnter(spec)`，**无文本编辑入口** ⇒ 名单内容只可能来自选择器，不可能手填群名。
- `MainPage.kt:89-128`：`onEnterSpec` → `PickerPage(...){ ids -> Kv.putList(spec.key, ids) }` ⇒ **`Item.id` 原样写入，不展开、不归一化**。
- `PickerPage.kt:154,158`：`selected.add(item.id)`；`confirm()` 交回 `onOk`。
- `PickerTabs.kt:107` 群聊 Tab `Item.id = it.wxid`（形如 `xxx@chatroom`）；`:125` 分组 Tab `id = "g:" + id`；`:140` 标签 Tab `id = "t:" + id`。
- `ListResolver.kt:40,43`：`PREFIX_GROUP="g:"`、`PREFIX_LABEL="t:"`。
- ⚠️ `PickerTabs.kt:70-72` 原文警告：「下游消费方若**不支持**前缀展开，名单里存了 `g:3` 就是**静默失效**（拿 talker 去比 `g:3` 永不命中）」。
- 会展开 `g:/t:` 的 `ListResolver.inScope`（ListResolver.kt:125-140）**只被旧 `core/NotifyMute` 使用**，而 `HookEntry.kt:263-275` 已把它注释停用；**现役 `MuteAtLogic.groupInScope` / `hit()`（MuteAtLogic.kt:103-121）不展开前缀，只做 trim 后精确相等**。

### 0.2 归属纠偏（本轮最重要结论）

- 名单**只能由选择器写入** ⇒ 元素必然是 **id（`xxx@chatroom` 或 `g:`/`t:` 前缀），不是群显示名**。
- ⇒ 上一版 §2 的 **②「群名精确相等不成立」、③「条目类型差异/懒加载」全部不适用**，从存活域移除。
- ⇒ 上一版补的「群备注 `conRemark` 优先 / NBSP / 全角空格 / 内部空格 / `GroupNameSource` 懒加载」等机制**同样全部不适用**。
- 保留「三群一生效两失效」这条判别性事实；不复活全局链路类假设（H1/H6/H7/H8/H11/H12 方向性证伪保持）。

---

## 1. 假设三态表（重画）

> 三态：`已证伪` / `仍存活` / `方向性证伪` / `需探针判定` / `不适用`。
> 判别性事实：三群一生效两失效（F1，待证实）。凡「全局单值/全局能力」类假设若成立应所有群一起失效，与该差异矛盾 ⇒ 方向性证伪（除非标注残余）。

| 假设 | 三态 | 依据 | 能否解释 per-group 差异 |
|---|---|---|---|
| H1 开关未开（全局单值） | **方向性证伪** | 代码事实：`shouldMute` 第①层 `Kv.getBool(CHAT_MUTE_AT_ENABLED)` 全局单值（MuteAt.kt:61） | **不能** |
| H2 别名留空（仅 @我 分支） | 仍存活（仅 @我；@所有人 已证伪） | 代码事实：`isAtMe` 先硬编码 `@所有人`（MuteAtLogic.kt:93）；别名全局配置 | **不能**（全局配置） |
| H3 名单作用域 | **仍存活（存活域核心）** | 代码事实：`groupInScope` 排除优先（MuteAtLogic.kt:111）、`hit()` trim 精确相等（116-121）；**①排除名单分支已因 F3 证伪**（口述排除名单为空，待 `scope` 证实） | **能** |
| H4 DND/GRP 抢占 | **已证伪**（方向错） | 代码事实：前两分支作用是静音并 return（MuteAt.kt:287-299），用户看到「照常响」 | **不能** |
| H5 前台假阴性 | **已证伪**（口述排除前提） | 用户口述「退后台」；前台假阴性对所有群一致 | **不能** |
| H6 装包未重载 | **方向性证伪** | 代码事实：旧代码对所有群一视同仁 | **不能** |
| H7 `NotificationItem`/`a(nargs=1)` 找不到 | **方向性证伪**（绝对全局） | 代码事实：`itemHooked=false` 时 talker 恒 null，全群一起 false（MuteAt.kt:215-244） | **不能** |
| H8 notify hook 失败 | **方向性证伪**（绝对全局） | 代码事实：`notifyHooked=false` 时 install 整体 false（MuteAt.kt:173-176） | **不能** |
| H9 talker 解析为空 | **方向性证伪（残余待探针判定）** | 代码事实：`parseItem` 每条通知独立执行，理论上部分成功部分失败可成立 ⇒ 不一刀切 | **条目相关（残余）** |
| H10 懒加载冷启降级（群名匹配） | **不适用** | 代码事实：选择器写入的是 id（`xxx@chatroom`），**不写群名**（§0.1）；群名匹配链路根本不会被触发 | — |
| H11 静音通道降级 | **方向性证伪** | 代码事实：`wxgroup_silent` 全局唯一通道（MuteAt.kt:118）；「测试群聊生效」反证通道没降级 | **不能** |
| H12 @所有人 渲染串 ≠ 字面 `@所有人` | **方向性证伪** | 代码事实：`isAtMe`/`notificationText` 无群参数，三群共用同一份结果（MuteAt.kt:79-84） | **不能** |
| **H13 名单含 `g:`/`t:` 前缀而现役 MuteAt 不展开** | **仍存活（待 `scope` 证实）** | 代码事实：`hit()` 只 trim 精确相等、不展开前缀（MuteAtLogic.kt:116-121）；`g:`/`t:` 永不命中 `xxx@chatroom` 的 talker（PickerTabs.kt:70-72） | **能** |

### 1.1 存活域结论

> 判别性事实把存活域收敛到 **`hit()` 的「条目 vs talker 精确相等」**：H3（含 H13 前缀子项）为主，H9（部分 talker 解析失败）为残余，H2 仅对「@我」分支（全局别名，非 per-group）。⛔ 无一条可标「已确认根因」。

---

## 2. 唯一存活层：`hit()` 的「条目 vs talker 精确相等」（互斥总判）

> 核心：`shouldMute` 三层里只有第②层 `groupInScope(talker, nameOf(talker), allow, exclude)` 按群判定，且因排除名单为空（F3）、选择器只写 id（§0.1），判定完全落在 `hit()` 的**精确相等**上（MuteAtLogic.kt:116-121）：`room in entries`（talker 逐字符等于某条目）。断点必在「条目字符串」与「通知里解析出的 talker 字符串」之间。

### 2.1 互斥总判（写成「A 或 B 或 C」，⛔ 不并列、⛔ 不定论）

- **A：`allow` 含 `g:`/`t:` 前缀条目** → `hit()` 不展开前缀，`g:3`/`t:5` 永不等于 `xxx@chatroom` 的 talker ⇒ 这些群静默失效。
  - ⚠️ **逻辑约束（必须处理）**：若 `allow` 里**三个**条目**都是**前缀，则「测试群聊生效」就无法解释（测试群聊也应是前缀 ⇒ 也失效）。⇒ A 要成立，`allow` 必须**混合**：至少一个**裸 `xxx@chatroom`**（对应测试群聊）+ 另两条是前缀（对应两失效群）。
  - 可证伪判据：**「三个条目都是前缀，但测试群聊仍生效」⇒ A 证伪**；或「两个前缀条目对应的正是两失效群，且测试群聊对应裸 wxid」⇒ A 成立。
- **B：`allow` 三群都是裸 `xxx@chatroom`，但与通知里解析出的 talker 不完全相等** → 大小写 / 首尾空白 / 别名 id / `ChatroomSource`（选择器）与 `NotificationItem.h`（通知）取值口径不同 ⇒ 精确相等失败。
- **C：`allow` 条目与 talker 都能对上，但那两群的 talker 没被解析出来** → H9 残余：`parseItem` 每条通知独立跑，可能部分失败（两群的 `NotificationItem` 解析不到 talker）。

### 2.2 每条：证实/证伪看哪条探针哪行日志 + 用户可见事实判据

| 判 | 证实看（探针 + 日志行） | 证伪看 | 用户可见事实判据 |
|---|---|---|---|
| A 前缀不展开 | `scope` → `allow=[g:3, t:5, xxx@chatroom, ...]` 含 `g:`/`t:` 前缀 | `scope` → `allow` 全是裸 `xxx@chatroom`，无 `g:`/`t:` | 两失效群正是「分组/标签」选择，测试群聊是「群聊」Tab 选的裸 wxid |
| B 裸 wxid 与 talker 不等 | `scope` 的 `allow=[xxx@chatroom,...]` 与通知 `talker=` 明文**逐字符比对不符** | 三群 `allow` 条目与 talker 逐字符完全一致 | 选择器里显示的群名/条目与通知里实际 talker 有肉眼差异（需 `map` 拿 talker 明文） |
| C talker 解析失败 | `map` → 通知里两失效群无对应 `talker=` 明文（或非 `@chatroom` 结尾） | 三群 talker 都被解析到且为 `@chatroom` | 两失效群通知弹出但日志无 `AT muted`（判定在 groupInScope 前断） |

> ⛔ `scope` 打的是**选择器写进去的条目原文**（id 而非群名）；`map` 若能带出 talker 明文，优先引明文与 `allow` 逐字符比对，不凭「看起来像」下结论。

---

## 3. 判别性最小探针序列（收窄到两条决定性命令）

> 排序：一条命令能分开 A vs B/C 优先。其余降为兜底。
> TAG 大小写敏感：`QUIET`、`PROBE kv`、`GROUPNAME`。
> ⛔ 探针跑前先 `am start -n com.tencent.mm/.ui.LauncherUI` 拉回前台（§5）。

### S1（决定性）：名单原文 → 分开 A vs B/C

- 命令：`python tools/dev.py probe quiet at scope --clear --grep QUIET`
- 预期日志 TAG：`QUIET`
- 预期日志行：`QUIET allow=[...] exclude=[...]`
- 成立看什么（对着原文，不看计数器）：
  - `allow` 里出现 `g:`/`t:` 前缀条目 ⇒ 进入 A；再核对是否「混合」（至少一个裸 `xxx@chatroom`）。
    - 混合 + 两前缀对应两失效群 ⇒ A 成立。
    - **三个条目全是前缀** ⇒ A 证伪（无法解释测试群聊生效）。
  - `allow` 全裸 `xxx@chatroom`、`exclude=[]` ⇒ A 排除，转 S2 判 B/C。
  - `exclude` 非空 ⇒ 与 F3 口述矛盾，登记待确认（口述「排除名单为空」被证伪）。
- 证伪后跳转：A 不成立（或全前缀）⇒ 转 S2。

### S2（决定性）：talker 解析与逐字符比对 → 分开 B vs C

- 命令：`python tools/dev.py probe quiet at map --clear --grep QUIET`
- 预期日志 TAG：`QUIET`
- 预期日志行：`QUIET mapStatus` 及（若有）带 `talker=` 明文的落地/快照行
- 成立看什么：
  - 把 `allow` 里的 wxid 与通知里的 `talker=` 明文**逐字符比对**：
    - 逐字符不符（大小写/空白/别名 id/取值口径不同）⇒ B 成立。
    - 两失效群**没有**对应 `talker=` 明文（或非 `@chatroom` 结尾）⇒ C 成立（H9 残余）。
- 证伪后跳转：三群 talker 都解析到且逐字符等于 `allow` 条目 ⇒ B/C 全证伪，回查 S1 是否漏判（或转兜底 S3）。
- ⛔ 不对 `tracked=N known=M` 数字下最终判据；`map` 有 talker 明文则优先引明文，无明文才把「两失效群无 talker 行」当 C 的证据。

### S3（兜底）：清场 + 落地层

- 命令：
  ```
  python tools/dev.py probe quiet at status --clear --grep QUIET
  python tools/dev.py probe quiet at grplist --clear --grep GROUPNAME
  ```
- 预期日志 TAG：`QUIET` / `GROUPNAME`
- 用途：
  - `status` 看 `registered/bridge` 清场（排除 H7/H8 意外复活）。
  - `grplist` 仅 8 个样本（`sampleText(8)`），本轮**不再是主判据**，只在核对 `ChatroomSource` 口径与 `NotificationItem.h` 差异（B 的取值口径子项）时参考。
- 最终落地判据（对着日志行）：退后台重发一条 @所有人 到两失效群之一，看是否出现 `QUIET AT muted id=... talker=xxx@chatroom`；出现但仍响震 ⇒ 落地层（H11 方向性已证伪，作最后落点，真机核对 `wxgroup_silent` 通道）。

---

## 4. 探针序列总览（重排后）

| 步 | 命令 | TAG | 目标 | 成立看什么 | 证伪后跳 |
|---|---|---|---|---|---|
| S1 | `python tools/dev.py probe quiet at scope --clear --grep QUIET` | `QUIET` | H13/H3 | `allow` 原文看 `g:`/`t:` 前缀 + 是否混合 | A 否→S2 |
| S2 | `python tools/dev.py probe quiet at map --clear --grep QUIET` | `QUIET` | H9 残余/H3 | talker 明文与 `allow` 逐字符比对；两失效群无 talker | B/C 否→S3 |
| S3 | `status` + `grplist` | `QUIET`/`GROUPNAME` | 清场/落地层 | `registered/bridge`、8 样本参考、`AT muted` 落地行 | 全证伪→H11 落点 |

> H12 的 `extras` 取证、旧 §2 的 ②③ 群名/懒加载探针均因选择器写入格式事实（§0.1）**退出主序列**；只在 S1~S3 全证伪且出现「`AT muted` 仍响震」时，`extras` 作兜底复核渲染串（排除落地层之外的罕见串异常）。

---

## 5. 前后台操作卡

| 操作 | 微信状态 | 为什么 | 写反的假象 |
|---|---|---|---|
| 测 @消息（发真实 @所有人/@我，观察响震） | **后台** | 微信前台不响铃不震动（notify_mute.md 已实证） | 前台测 ⇒ 不响不振误判，实为前台固有行为 |
| 跑探针（`dev.py probe` / `am broadcast`） | **前台** | `am broadcast` 后台时 AMS 照回 `Broadcast completed` 但**不投递**（dev.py:276-288） | 后台跑 ⇒ 日志大小未变，dev.py 报错并拒打印旧日志 |
| 做反向对照（翻开关后重发） | 先**前台** kvset+探针，再**后台**重发 | kvset/探针要前台投递；验证响震要后台 | 后台 kvset ⇒ 没写入却以为翻了开关（假阴性） |

> ⛔ 用户说的「都在后台」是**测通知的姿势**；跑探针必须拉回前台。两者方向相反。

---

## 6. 复位边界

- **本轮为第三次静态收窄（选择器写入格式纠偏），用户口述（F1~F4）未经真机探针证实，未下真机、未改代码**；上表无一条是「已确认根因」，H13/H3/H9 只是「待 `scope`/`map` 证实的存活候选」，H1/H6/H7/H8/H11/H12 保持方向性证伪，H10 标「不适用」。
- **复位流程（A→B→C 逐条证伪后跳下一条）**：
  1. S1 `scope` → `allow` 含 `g:`/`t:` 前缀且混合 ⇒ A 成立（前缀不展开，两前缀对应两失效群）；三条目全前缀 ⇒ A 证伪，转 B/C；
  2. S2 `map` → `allow` 裸 wxid 与通知 `talker=` 明文逐字符不符 ⇒ B 成立；两失效群无 talker 明文 ⇒ C 成立（H9 残余）；
  3. 三群 talker 都解析到且逐字符等于 `allow` 条目 ⇒ B/C 全证伪，回查 S1 或转 S3 落地层；
  4. S3 出现 `QUIET AT muted` 但仍响震 ⇒ 落地层（H11 落点，真机核对系统通知设置）。
- **三个必须保留的未知**（不许写成已知）：①「测试群聊生效/不生效、@我 生效」仍为用户口述，非探针验证结论；②「@我」在另两群是否失效，用户未测 ⇒ 待确认，⛔ 不许写成「另两群 @我 也失效」；③ 三群在允许还是排除名单、条目是裸 wxid 还是 `g:`/`t:` 前缀 ⇒ 未验证（F3 口述排除名单为空，待 `scope` 证实）。
- **反向对照清场**：临时全放行验证时须同时关 `chat.dnd_enabled=false` 与 `chat.auto_dnd_new_group=false`，否则 DND/GRP 抢先静音得假阴性；但抢占分支作用是**静音**，故 H4 已证伪，不再作为「不生效」候选。
- ⛔ **禁止**给出「直接改某行代码」的改动方案（如「给 `hit()` 加前缀展开」「改 `ChatroomSource` 口径」等），改动轮留待用户授权。
- ⛔ **不得**把 `g:`/`t:` 前缀写成「已确认根因」——它只是待 `scope` 证实的存活候选。
- 本轮仅修改本文件；未修改 `_r50_muteat_rootcause.md` 或任何其他文件。

---

[DONE]
