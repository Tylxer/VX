# R89 阶段1 —— 朋友圈式「查询层过滤」判据定稿

> 目标功能：`wxgroup` 的「加入分组后主会话列表不再显示该会话，只有进分组页能看到」。
> 复刻微 X 做法：**在会话列表 SQL 查询层过滤**，不删 `rconversation` 行、不动 message。
> 本文档是阶段 1 的判据定稿 + 纯函数实现说明 + 单测覆盖 + 变异验证记录。

## 0. 前置事实（实测，不重新验证）

- 所有 `rconversation` 查询汇聚到 `com.tencent.wcdb.database.SQLiteDatabase.rawQueryWithFactory`
  （SQL 在 `param.args[1]`），`hookAllMethods` 覆盖完整。
- 6 种真实 SQL 形态：I（主列表全量）、C（批量明细）、F（未读聚合）、A/B/E（点查）、G（折叠页）。
- 目标：**只改写 I / C / F**；A / B / E / G / 我方诊断查询一律不动。

---

## 1. 六形态决策表

| 形态 | 真实 SQL 特征（判据依据） | 判据谓词 | 是否改写 | 依据原文片段 |
|------|--------------------------|----------|----------|--------------|
| **I 主列表全量** | 19 列显式投影、无 limit、`order by flag desc`、无 `username in` | `message_fold` 命中（`parentRef in ('', 'message_fold')` 折叠条件） | ✅ 改写 | `where ((parentRef is null) or (parentRef in ('', 'message_fold'))) and ( 1 != 1 or ... like '%@chatroom' ... ) ... order by flag desc` |
| **C 批量明细** | 与 I 同前缀、WHERE 多 `username in ('a','b',…)`、无 order by | `message_fold` 命中 + 无 `username = `（`in` 不是 `=`） | ✅ 改写 | 同 I 前缀 `… and rconversation.username in ('a','b')` |
| **F 未读聚合（JOIN 版）** | `from rconversation, rcontact`、`unReadCount > 0`、`(type & 512) == 0` | `rcontact` 命中 + `unReadCount > 0` | ✅ 改写 | `from rconversation, rcontact where rconversation.unReadCount > 0 AND … AND (rconversation.parentRef is null or parentRef = '') AND ( type & 512 ) == 0 AND rcontact.username != 'officialaccounts'` |
| **F 未读聚合（union 版）** | `like 'wx^_^_%' ESCAPE '^'` + `union` + `inner join rcontact` | `rcontact` 命中 + `union` 命中 | ✅ 改写 | `… from rconversation where rconversation.username like 'wx^_^_%' ESCAPE '^' union SELECT … from rconversation inner join rcontact WHERE …` |
| **A 点查** | `SELECT * FROM rconversation WHERE username=?` | `username = ?` 豁免 | ❌ 不动 | `WHERE username=?`（78 条最多形态，动了伤收发） |
| **B 点查** | `username = 'gh_xxx' AND unReadCount > 0` | `username = '…'` 豁免 | ❌ 不动 | `where username = 'gh_xxx' AND unReadCount > 0` |
| **E 点查 / 我方查询** | `username='x'` / `substr(username,1,6)='wxgrp_'` / `count(*) … 'wxgrp_'` / `select username,conversationTime from rconversation` | `username = '…'` 豁免 + `wxgrp_` 豁免 | ❌ 不动 | `select msgCount from rconversation where username='x'` |
| **G 折叠页** | `parentRef = 'conversationboxservice'`、`order by flag desc, conversationTime desc` | `conversationboxservice` 豁免 | ❌ 不动（本轮待定） | `where parentRef = 'conversationboxservice' and (…) order by flag desc, conversationTime desc` |

要点：

- **列清单会漂**（另一版 I 带 `hasSpecialFollow`），判据**绝不比对列清单**，只比对 WHERE 结构。
- **I 与 C 的唯一可靠共同指纹是 `message_fold`**：I 有 `order by flag desc`、C 没有；I 无 `username in`、C 有。所以不能用 order by / in 来统一 I、C，必须用折叠条件。
- **F 与 A/B/E 的区别**：F 带 `rcontact`（未读聚合要 join 官方号排重），A/B/E 都不带。

---

## 2. `isConvSelect(sql)` 完整判据（升序短路）

每条按顺序短路，命中即返回；只在**全绿**时才走到末尾的正判定。

| 序 | 判据 | 为什么 | 反例 SQL（若误判成「是」会导致） |
|----|------|--------|----------------------------------|
| 1 | `sql.length > MAX_SQL(4096)` → false | 超长不是列表页正常查询，扫描/改写成本高，且可能是批量脚本 | 我方导出大段 `in` 的查询（会白改） |
| 2 | `sql.contains(MARK)` → false | 已注入过，防重复注入（`MARK`=`/*wr*/`） | 同一 SQL 二次改写 → 条件叠加、括号堆叠 |
| 3 | `sql.contains(BYPASS)` → false | 模块自己诊断 SQL 带 `/*w*/` 前缀，绕过自己的钩子 | 我方 `count(*)` 取证被过滤 → 取证工具瞎 |
| 4 | 不 `startsWith("select")` → false | 非查询（insert/update/delete 不动） | `update rconversation …` 被注入 → 语法错误熔断 |
| 5 | 不含 `rconversation` → false | 只关心会话表 | 其他表同名 `message_fold` 字面量（理论） |
| 6 | 含 `wxgrp_` → false | 我方诊断查询的 `substr(username,1,6)='wxgrp_'` 前缀 | 我方 `select username,flag,conversationTime … substr(…)='wxgrp_'` 被过滤 → 分组页读不到自己 |
| 7 | 含 `conversationboxservice` → false | G 折叠页本轮明确不过滤 | `where parentRef='conversationboxservice' …` 被过滤 → 折叠会话从折叠页消失 |
| 8 | `username = ?` 或 `username = '单值'` → false | A/B/E 点查（消息到达/会话更新/角标读单行），动了伤收发 | `SELECT * FROM rconversation WHERE username=?` 被过滤 → 收到消息后会话取不到行，收发异常 |
| 9 | 正判定：`isIC(s) \|\| isF(s)` | 只认三种可过滤形态 | 见下 |

### 2.1 `isIC(s)` —— I / C 共有的折叠指纹

```kotlin
private fun isIC(s: String) = MESSAGE_FOLD.containsMatchIn(s)   // \bmessage_fold\b
```

- **为什么**：I 与 C 的 WHERE 都**必带** `parentRef in ('', 'message_fold')` 折叠条件；而 A/B/E/我方查询、G 折叠页都不带 `message_fold`（G 是 `conversationboxservice`，F 是 `parentRef = ''` 不带 `message_fold`）。所以 `message_fold` 是「主列表/批量明细」的唯一可靠结构指纹，比「包含 rconversation」强得多。
- **反例**：`select … from rconversation where username='x'`（E）不含 `message_fold` → isIC=false，正确放行。

### 2.2 `isF(s)` —— 未读聚合指纹

```kotlin
private fun isF(s: String) =
    RCONTACT.containsMatchIn(s) &&                                    // \brcontact\b
    (UNION_KW.containsMatchIn(s) || UNREAD_GT0.containsMatchIn(s))    // \bunion\b 或 \bunreadcount\s*>\s*0\b
```

- **为什么**：F 两种形态都 join `rcontact`（官方号排重），且要么 `union`（union 版）、要么 `unReadCount > 0`（JOIN 版）。A/B/E/I/C/G 都不带 `rcontact`，所以 `rcontact` 把 F 从其余形态里唯一切出来。
- **反例**：B（`username = 'gh_xxx' AND unReadCount > 0`）虽含 `unReadCount > 0`，但不含 `rcontact`，且第 8 步已先被 `username = '…'` 豁免 —— 双保险放行。

### 2.3 为什么第 8 步（点查豁免）必须在正判定之前

`username = 'gh_xxx'` 是单值等值（点查），`username in ('a','b')` 是集合（批量明细 C），`username like '%@chatroom'` 是模式（主列表 I）。只有 `=` 是「取单行」，必须豁免；`in` / `like` 不是，所以要过滤。若把点查豁免放在正判定之后，`username = 'gh_xxx' AND unReadCount > 0`（B）会先被 `isF` 的 `unReadCount > 0`……但 B 不含 `rcontact`，所以实际不会误命中 —— 但点查豁免**仍必须显式在前**，理由：语义上「点查 = 取单行 = 伤收发」是最高优先的排除项，不能依赖「它恰好不含 rcontact」这种巧合。

---

## 3. 强指纹评估（对上游假设的采纳/推翻）

### 3.1 采纳：「I 与 C 都必带 `parentRef` 折叠条件」—— 作为 isIC 主指纹

- **采纳理由**：实测两条 I/C 都带 `(parentRef is null) or (parentRef in ('', 'message_fold'))`，而 A/B/E/我方/G 都不带。比「包含 rconversation」强一个量级。
- **修正（细化）**：上游说「折叠条件是 `(parentRef is null) or (parentRef in ('', 'message_fold'))`」，但逐条比对后发现 **`message_fold` 才是真正唯一的那部分**——F 的 JOIN 版也有 `(rconversation.parentRef is null or parentRef = '')`（有 `parentRef is null`，但**没有 `message_fold`**）。所以判据必须锚定 `message_fold`，而不是「有 `parentRef is null`」。若锚定后者，F 的 JOIN 版会同时命中 isIC 和 isF，虽然结果仍是「改写」（无害），但判据就失去了「I/C vs F」的区分能力。**结论：`message_fold` 一词锁定 I/C，比上游建议的整段折叠条件更准。**

### 3.2 采纳：「注入条件必须带 `rconversation.username` 表名限定」

- **采纳理由**：F 带 JOIN（`from rconversation, rcontact`）/ union（两个 SELECT 都引用 rconversation）。裸 `username` 在 `rconversation, rcontact` 里两表都有该列 → `ambiguous column name: username`，且该错误在熔断名单里 → 一次查询废掉整个功能。带 `rconversation.` 限定后两个分支都合法。

### 3.3 补充（上游未明确，但必须定）：F 的统一指纹用 `rcontact`

- 上游只给了 F 的两种 SQL 文本，没给「怎么用一条判据统一 F 且不误伤 A/B/E/I/C/G」。本稿定：**`rcontact` 命中 +（`union` 或 `unReadCount > 0`）**。理由见 2.2。

---

## 4. 注入条件形态

```kotlin
fun notInWxids(ids: Collection<String>): String =
    if (ids.isEmpty()) "$MARK 1=1"
    else "$MARK rconversation.username NOT IN ('" +
        ids.take(MAX_IDS).joinToString("','") { it.replace("'", "''") } + "')"
```

- **表名限定**：`rconversation.username NOT IN (...)` —— F 带 JOIN/union，裸 `username` 会歧义（见 3.2）。
- **单引号转义**：id 里的 `'` 翻倍成 `''`（SQLite 字符串转义规则）。
- **`MARK` 前缀**：`/*wr*/` 同时充当「已注入」指纹，复用现有约定。
- **产物形态示例**（id = `wxgrp_a`、`wxgrp_b`）：
  ```sql
  /*wr*/ rconversation.username NOT IN ('wxgrp_a','wxgrp_b')
  ```

### 4.1 `rewriteConvSql` 组装

```kotlin
fun rewriteConvSql(sql: String, ids: Collection<String>): String? {
    if (!isConvSelect(sql)) return null
    if (ids.isEmpty()) return null            // 没有要排除的会话 → 等价不过滤
    return inject(sql, notInWxids(ids))
}
```

- 组装顺序：`isConvSelect` 否 ⇒ null；空集 ⇒ null（不注入）；否则 `notInWxids` ⇒ `inject`（复用现有 `inject`，已处理尾分号/顶层 where/order/union 定位/OR 括号化）。

---

## 5. 收口策略

| 场景 | 策略 | 依据 |
|------|------|------|
| 空 id 集 | `notInWxids(empty)` 返回 `/*wr*/ 1=1`（恒真）；`rewriteConvSql` 直接返回 null（不注入） | `NOT IN ()` 是 SQLite 语法错误，进真实查询会触发熔断废掉功能。1=1 是防御兜底，rewrite 层的 null 是干净路径 |
| 超 200 条 | `notInWxids` 内部 `take(MAX_IDS=200)` 截断 | SQL 不能无限长（现有 `notIn` 同款约定） |
| 超 4096 字符 | `isConvSelect` 第 1 步直接 false | 不是列表页正常查询 |
| 已含 MARK | `isConvSelect` 第 2 步直接 false | 防重复注入 |
| 带 `/*w*/` 前缀 | `isConvSelect` 第 3 步直接 false | 模块自己的诊断 SQL 放行 |

---

## 6. 误伤清单（若被误改 → 用户可见故障）

| 被误改的 SQL 类 | 用户可见故障 |
|-----------------|-------------|
| A 点查 `WHERE username=?`（消息到达/会话更新取单行） | 收消息后对应会话取不到行 → 会话列表空白/不刷新、收发异常 |
| B 点查 `username='gh_xxx' AND unReadCount>0`（公众号角标读单行） | 公众号未读角标错乱 |
| E 点查 `username='x'` / `msgCount`（单会话计数） | 单会话计数取错 → 徽标/分组计数异常 |
| 我方 `substr(username,1,6)='wxgrp_'` / `select username,conversationTime` / `count(*) … 'wxgrp_'` | 分组页读不到自己建的会话 → 功能自残 |
| G 折叠页 `parentRef='conversationboxservice'` | 折叠会话从折叠页消失（本轮待定，先排除） |
| F 未读聚合被漏改（反向风险） | 主列表已隐藏的会话仍在未读角标里计数 → 角标虚高 |
| 注入条件漏表名限定（反向风险） | `ambiguous column name: username` → 触发熔断 → 整个功能被永久停用 |

---

## 7. 变异验证（5 个语义变异，逐个确认至少一条变红后恢复）

> 单测基线：`AntiRevokeHintTest` 41 条，0 失败 0 错误（含原有 21 条 + 阶段 1 新增 16 条 + union 分段新增 4 条）。

| # | 变异 | 哪些用例变红 | 结论 |
|---|------|-------------|------|
| A | 去掉 I/C 折叠指纹（`isIC` 恒 false） | `I 主列表原文：改写成功…`、`I 主列表原文的 OR 都在括号内…`、`C 批量明细…` 共 3 条 | 有语义 ✅ |
| B | 去掉 F 的 `unReadCount > 0` 约束（isF 只剩 `rcontact && union`） | `F 未读聚合 JOIN 版…` 1 条 | 有语义 ✅ |
| C | 去掉注入条件表名限定（`username NOT IN` 裸列名） | `I 主列表原文…`、`C 批量明细…`、`F JOIN 版…`、`F union 版…` 共 4 条 | 有语义 ✅ |
| D | 去掉 `inject` 的 OR 括号化（`hasTopLevelOr` 恒 false） | 原有 `原 WHERE 里有 OR 时必须整段括号化…` 1 条 | 有语义 ✅ |
| E | union 形态退回单段 `inject`（等于去掉双分支分段） | `F union 实测原文…`、`union all 版…`、`段数超过 4 返回 null` 共 3 条 | 有语义 ✅ |

### 7.1 一个被否掉的「伪变异」——重要发现

上游建议的变异之一「去掉点查豁免 / 去掉 G 折叠页排除」**全绿**，说明这两个负面豁免在**当前正判定的实现下是冗余防御**：

- A/B/E 点查（`username=?`/`username='x'`）不含 `message_fold`、也不含 `rcontact` → 正判定 `isIC || isF` 天然为 false，**不靠点查豁免就已经被排除**；
- G 折叠页（`parentRef='conversationboxservice'`）同样不含 `message_fold` / `rcontact` → 天然 false。

**处理**：这两个豁免**仍然保留**（防御纵深——万一未来 I/C 形态漂出 `username=?` 或 `message_fold` 出现在非列表查询里，负面豁免兜底，且语义上「点查=取单行=伤收发」是最高优先排除项，不该依赖「恰好没命中正判定」这种巧合）。但变异验证改用 A~D 四个**有牙齿**的变异（全部命中正判定的核心结构），不把这两个伪变异当作「验证通过」。

---

## 8. 遗留风险 / 待主会话决策

1. **已修：顶层 union 分段注入**（见 §9）。原风险是 `inject` 把条件插在顶层 `union` 之前，第二个分支（`inner join rcontact`，**正是未读主查询**）完全不过滤 —— 复核确认这不是「可能」，而是**必然**虚高。已用 `injectUnion` 逐段注入收口，两个分支都过滤。
2. **F 的判别依赖 `rcontact`**：若未来微信用别的方式（不 join rcontact）做未读聚合，F 会漏判 → 角标虚高（漏改不伤功能，只是角标不准）。
3. **`message_fold` 是 I/C 的唯一锚**：若微信把折叠条件文案改成别的字面量（如 `parentRef in ('', 'message_fold_v2')` 或换结构），主列表过滤会整体失效（静默失效，不报错、不熔断）。
4. 以上均为「漏改」类风险（功能不生效或角标不准），**不触发熔断、不伤收发**；唯一「误改」类风险是注入条件漏表名限定，已被变异 C 锁死。

---

## 9. 顶层 union 分段注入（阶段1 补丁，为阶段3 角标验收项服务）

F union 版的第二个分支（`from rconversation inner join rcontact WHERE …`）正是未读角标的主查询。若只像单段 `inject` 那样把条件插在**顶层 `union` 之前**，第二个分支完全不过滤，已隐藏的会话仍被计入未读角标 → **角标必然虚高**（不是「可能」）。阶段 3 验收里角标是硬项，所以必须分段。

### 9.1 分段规则

- **只切顶层 `union`**：复用 `scan()` 的括号深度 + 字符串内判定。括号里 / 子查询里 / 字符串字面量里的 `union` 一律不算顶层；命中不了顶层 union 就退回单段 `inject`（走最外层注入，行为与无 union 一致）。
- **支持 `union` 与 `union all`**（大小写不敏感）：`UNION_SEG = \bunion\b(\s+all\b)?` 把 `union`（含 ` all` 与中间空格）作为一个整体边界。
- **保留原关键字及其空格**：切分后把原关键字文本原样拼回分段之间，不吞 `union`、不丢 `all`。
- **逐段 `inject`**：每个 SELECT 段独立注入，所以 `rconversation.username NOT IN (...)` 会出现在**每一个**分支里；每段都带 `MARK`（`/*wr*/`，来自 `notInWxids`）。

### 9.2 失败收口

- 任一段 `inject` 返回 null ⇒ **整体返回 null**（宁可不改，不能改一半——改一半会让 SQL 变成「一个分支过滤、另一个漏」，比不改还糟）。
- 段数 > 4（即 4 个以上顶层 union）⇒ 返回 null，防拼接形态失控。
- 空集仍在 `rewriteConvSql` 入口就返回 null，不进入分段。

### 9.3 为什么 `MARK` 不能替代分段

`MARK` 只是「已注入」的指纹，它跟着条件走、落在**第一段**里；它既不能把条件复制到第二个分支，也不能让第二个分支的 `inner join rcontact` 主查询被过滤。只靠 `MARK` 意味着第二个分支仍然漏过滤 → 角标依旧虚高。所以分段是唯一能把过滤落到所有分支的手段，`MARK` 只是每段注入时的防重入标记。

### 9.4 组装路由

```kotlin
fun rewriteConvSql(sql: String, ids: Collection<String>): String? {
    if (!isConvSelect(sql)) return null
    if (ids.isEmpty()) return null
    val cond = notInWxids(ids)
    return if (UNION_KW.containsMatchIn(sql)) injectUnion(sql, cond) else inject(sql, cond)
}
```

`UNION_KW` 命中（哪怕 union 在子查询里）先走 `injectUnion`；`injectUnion` 内部找不到顶层 union 时自动退回单段 `inject`，两条路语义一致。

---




[DONE]
