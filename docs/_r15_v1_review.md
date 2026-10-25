# R15 · V1 复核报告

> 复核对象：R15「朋友圈图片从 SNS 磁盘缓存直读」实现（I1 产出）
> 复核方式：只读代码 + 独立脚本验证算法（`_cache/` 临时脚本，未改生产代码）
> 依据：`docs/_r15_sns_cache_plan.md`（Q2b 最终设计）、`docs/_r15_i1_impl.md`、三个源文件

---

## 结论：PASS WITH CONDITIONS

**代码正确性 PASS，但当前不允许上真机。**

- 4 个纯函数（`fliterPre` / `snsNameCandidates` / `matchSnsFileName` / `isSnsMediaName`）经 23 例单测 + 本轮独立脚本验证，**全部正确**。
- 接线（`buildSnsIndex` / `copyOneFromCache` / 四态计数）IO、回滚、20MB 上限、失败隔离均完备。
- **唯一硬阻塞**：`mediaIdsOf()` 返回 `emptyList()`（protobuf 未接），`imgCopied` 恒为 0，真机装了也验不出核心链路。

---

## P0 问题（必须修）

### P0-1 · `mediaIdsOf()` 返回空 —— 上真机的硬阻塞

- **位置**：`MomentsExport.kt:670-675`
- **现状**：`return emptyList()`，`TODO(R15-I1)` 标注 protobuf 未接。
- **后果**：`COPY_IMAGES=true` 时，`copyImagesForRow`（`MomentsExport.kt:684-691`）对**每条**动态都走 `ids.isEmpty()` 分支 → `imgFileMiss +1` + 一行 `noMediaId` 日志。`imgCopied` **恒为 0**，`imgRootMiss` 也**恒为 0**（因为永远到不了 `copyOneFromCache` 里的 `rootFound` 检查）。
- **是否静默**：**不静默**。有显式计数 + 独立日志行 `MOMENT img noMediaId snsId=… (protobuf 未接…)`，符合项目硬规矩。
- **能否区分「没 mediaId」**：**计数上不能**（与「有 mediaId 但文件 miss」同进 `imgFileMiss`），**日志上能**（独立 `noMediaId` 行）。方案 Q5 的 `imgFileMiss` 定义本就含「mediaId 未知」（`MomentsExport.kt:119` 注释），所以**符合方案**，不算超规。
- **改法**：按方案 Q1 落地 —— `row.content`（BLOB）→ `TimeLineObject.ContentObj`(字段6) → `i40.h`(字段8, repeated bytes) → 逐个 `vm3` 手动走 varint/wire-type 取字段 1 = `d`（String）= mediaId。**禁止反射调 `vm3.parseFrom`**（方案 Q1 已定）。项目已有 varint 读取范式（`SnsCommentLogic.kt:856`）。这是唯一改动点，签名已定 `List<String>`。

### P0-2 · `fliterPre` 二次截断 —— 主会话提示有误，**双候选能兜住**

> ⚠️ 这是本报告最重要的独立结论：team-lead 的「双候选不能兜住」提示，**经我独立验证是错的**。

- **位置**：`fliterPre` `MomentsLogic.kt:406-411`；`snsNameCandidates` `MomentsLogic.kt:423-433`。
- **可复现推理步骤**（我用脚本实际跑过，非照抄提示）：
  1. 设真机文件名后缀 `N = fliterPre(vm3.d)`（真机文件 = `"snsb_" + N`）。
  2. 若 `mediaIdsOf()` 返回的 mid **已是** `N`（被砍过一次的串）：
     - `snsNameCandidates(N)` 生成 `keys = [fliterPre(N), N]`（`MomentsLogic.kt:426-428`：`fliterPre(N)` 非空时，`N` 原值也会进 keys）。
     - `fliterPre(N)` = 再砍一次 = `M`（错串）。
     - 候选 = `[snsb_M, snsb_N, snsu_M, snsu_N, snst_M, snst_N]`。
     - 真机文件名 `"snsb_N"` = 候选里第 2 项 → **精确命中** ✅。
  3. 脚本验证：`mid_already = "38644089917813396"`（即 `fliterPre("15038644089917813396")`），候选里存在 `snsb_38644089917813396`，真机文件名命中 `True`。
- **team-lead 推理的错点**：把「mid 本身（`N`）」误当成「候选」。候选是 `前缀 + key`，`key = N` 时候选 = `"snsb_N"` = 真机文件名。`N` 确实不等于 `"snsb_N"`（差前缀），但候选生成补上了前缀。
- **因此**：二次截断**不是 bug**，双候选设计恰好是「无论 mid 是 `vm3.d` 原值还是 `fliterPre(vm3.d)`，都能命中」的稳健形态。
- **残余风险（P1 级）**：双候选兜不住的是「mid 是**第三种未知形态**」（例如 `mediaIdsOf` 返回了带 `snsb_` 前缀的完整文件名，或某种非 fliterPre 的加工串）。这不是 `snsNameCandidates` 该负责的，而是 `mediaIdsOf` 的契约问题。
- **推荐修法**：**不在 `snsNameCandidates` 里加「若不以 15/14 开头则只放原值」**。理由：
  1. 双候选已经覆盖「原值 / 已砍值」两种形态，再加形状判断是**冗余**。
  2. 「15/14 开头」是**脆弱启发式**：方案 Q1 实测 mediaId 长度 8 种（20~28），前两位只是观测样本，协议无保证。硬校验会在微信升级后**误伤**（引入新 bug 风险）。
  3. 正确做法：**在 `mediaIdsOf` 落地时把契约钉死为「返回 `vm3.d` 原值（字段 1 的原始 String，未加工）」**，并在真机验证第一步（方案 Q5 判据 #1）打印 `vm3.d` 原值 与 `fliterPre(vm3.d)` 对比文件名，确认「只砍一次」成立。`snsNameCandidates` 保持现状不动。

---

## P1 问题

### P1-1 · 索引过滤位置正确

- `buildSnsIndex`（`MomentsExport.kt:560`）在建索引时用 `isSnsMediaName` 过滤，`snstblur_`/`wxid_`/`sight_` **没有混进来**。
- **过滤在建索引时做是对的**：排除规则是「这些文件永远不该导出」的静态语义，做一次即可；查表时（`matchSnsFileName`）保持纯字符串语义，无需重复判断。
- 附注（P2）：`SNS_EXCLUDE_PREFIXES`（`MomentsLogic.kt:395`）三项**全部冗余** —— `snstblur_`/`wxid_`/`sight_` 均不以 `snsb_`/`snsu_`/`snst_` 开头（`snstblur_` 第 5 字符 `b` ≠ `_`），白名单 `startsWith` 已天然排除。无害，但属防御性冗余。

### P1-2 · `matchSnsFileName` 前缀兜底边界充分

- **位置**：`MomentsLogic.kt:462-475`。边界规则：`cand` 之后下一字符 `next in '0'..'9'` 则拒绝（`MomentsLogic.kt:471`）。
- **尝试构造反例（脚本验证）**：
  - A 无缓存、B 有缓存且 B 的后缀 = A 的后缀 + 数字尾（`snsb_38644089917813396` vs `snsb_386440899178133960`）：A 候选 `snsb_38644089917813396` 对 B 名前缀兜底，下一字符 `'0'`（数字）→ 拒绝 ✅ 不误配。
  - 反向（B 候选更长）因 `n.length > cand.length` 不满足 → 跳过 ✅。
- **结论**：对**图片**（`snsb_`/`snsu_`/`snst_`，方案 Q3 确认图片 `vm3.D != 4` 直接返回基础名、**无 `&enc`/`_V2` 尾巴**），「下一字符非数字」这条边界是**充分**的，找不到能骗过的真机文件名。前缀兜底分支对图片实为**防御性死代码**（尾巴只存在于 `sight_`，已排除），但无害。

### P1-3 · 字典序最小不会误配

- **位置**：`MomentsLogic.kt:472`（`n < best`）。
- 精确命中优先（`MomentsLogic.kt:464`），只有多尾巴前缀兜底才走字典序；而图片无尾巴，多尾巴场景不触发。
- 「同一张图被两条动态各导一份」是**转发场景**的语义（两条动态各有目录、各导出完整内容），不是 `matchSnsFileName` 的 bug；「一条动态导错图」因精确命中优先 + 数字边界拒绝，不会发生。

### P1-4 · tmp + renameTo 回滚完备

- **位置**：`MomentsExport.kt:620-660`。
- `renameTo` 失败 → `runCatching { tmp.delete() }`（`MomentsExport.kt:650`）；异常 catch → `runCatching { tmp.delete() }`（`MomentsExport.kt:657`）。tmp **不会残留**。
- 即使 `delete()` 失败残留，也不影响正确性：判重看 `dst`（`MomentsExport.kt:628`），`scanExportedIds` 用 `parseExportedId` 只认 `.txt`（`.tmp` 不匹配），残留 tmp 下次导出会被覆盖。

### P1-5 · 20MB 上限是「拷贝前 + 拷贝中」双保险

- **位置**：拷贝前 `s.length() > MAX_MEDIA_BYTES`（`MomentsExport.kt:614`）；拷贝中累计 `n > MAX_MEDIA_BYTES`（`MomentsExport.kt:641`）。
- **两者都做了**：拷贝前看源文件 `length()` 快速拒绝（省得白读），拷贝中累计字节防「`length()` 不可靠 / 文件被并发写大」。这是最安全的实现，**够**。
- 判定用严格 `>`，等于 20MB 放行，与「超了多半读错」语义一致。

### P1-6 · 失败隔离正确

- **位置**：`copyImagesForRow`（`MomentsExport.kt:692-700`）每个 slot 独立调 `copyOneFromCache` + `countImg`，失败只计数 + 日志，不 break、不抛（外层 `runCatching.getOrElse` 兜底 `MomentsExport.kt:694`）。
- 单个文件失败**不影响**同一条动态其它图片，也**不影响**整轮导出（`runBlocking` 的文本循环不因图片失败中断）。

---

## P2 建议

### P2-1 · `imgFileMiss` 计数口径不统一（动态 vs mediaId）

- **位置**：`MomentsExport.kt:686-691`（`ids` 空 → `imgFileMiss +1`，按**动态**计）vs `MomentsExport.kt:692-700`（每个 slot 经 `countImg` → 按 **mediaId** 计）。
- 后果：方案 Q5 判据 #5「四态之和 = 已尝试的 mediaId 总数」在「有动态无 mediaId」时失真。例：10 条动态，5 条无 mediaId、5 条各 2 个 mediaId 全 miss → `imgFileMiss = 5 + 10 = 15`，而 mediaId 总数 = 10，两者不等。
- 建议：`ids` 空时**不计数**（或新增 `imgNoMedia` 独立计数），保持四态只按「mediaId」口径，让判据 #5 严格成立。

### P2-2 · 建索引 listFiles 层轻微静默

- **位置**：`MomentsExport.kt:536`（`cacheRoot.listFiles()` 失败有 `LogX.w`）之外，`MomentsExport.kt:551/554/557` 三层 `sns/b1/b2.listFiles()` 失败走 `getOrNull().orEmpty()` **无日志**。
- 影响：某层 listFiles 抛异常（罕见，如权限瞬时变化）时静默当空，可能把「实际有文件」误判为「没文件」，且无日志定位。建议这三层失败各补一行 `LogX.w`。

### P2-3 · 排除前缀冗余（见 P1-1 附注）

`SNS_EXCLUDE_PREFIXES` 三项不以白名单前缀开头，属防御性冗余，无害。

### P2-4 · 新旧路线不冲突

- 新路线（`buildSnsIndex` + `copyImagesForRow`）仅在 `COPY_IMAGES=true` 且文本落盘成功后跑（`MomentsExport.kt:263/341`）。
- 旧路线（`logImgProbe`/`imgRoots`/`imgtry`）是独立探针或只读日志，不共享状态、不写生产文件。两条路各自扫各自的盘，**无干扰**。
- `COPY_IMAGES` 默认值 = `false`（`MomentsExport.kt:56`）✅。

---

## 逐条判定表

| # | 问题 | 判定 | 依据（文件:行号 / 推理） |
|---|---|---|---|
| P0 | `mediaIdsOf` 返回空，全链路静默？ | **不静默**（日志+计数），但 `imgCopied` 恒 0，**不能上真机** | `MomentsExport.kt:670-675,686-691` |
| P0 | 四态能否区分「没 mediaId」 | 计数上不能（混入 `imgFileMiss`），日志上能（`noMediaId` 行） | `MomentsExport.kt:119,689` |
| P0 | `fliterPre` 二次截断 | **双候选能兜住**，非 bug；team-lead 提示有误 | 脚本验证 `snsb_N` 精确命中；`MomentsLogic.kt:426-428` |
| P0 | 二次截断推荐修法 | 钉死 `mediaIdsOf` 契约=返回 `vm3.d` 原值；**不加** 15/14 形状校验 | 双候选已覆盖两形态，形状校验脆弱 |
| P1 | 索引混入 `snstblur_/wxid_/sight_`？ | **否**，建索引时过滤 | `MomentsExport.kt:560`；`MomentsLogic.kt:442-448` |
| P1 | 过滤在建索引 vs 查表 | **建索引时做，对** | 排除是静态语义，一次即可 |
| P1 | `matchSnsFileName` 边界够不够 | **充分**（对图片场景找不到反例） | `MomentsLogic.kt:471`；脚本验证数字尾被拒 |
| P1 | 字典序最小误配 | **不会**（精确优先 + 图片无尾巴） | `MomentsLogic.kt:464,472` |
| P1 | tmp 残留 | **不残留**（rename 失败 + catch 都删） | `MomentsExport.kt:650,657` |
| P1 | 20MB 判定位置 | **拷贝前 + 拷贝中双保险，够** | `MomentsExport.kt:614,641` |
| P1 | 单文件失败隔离 | **不扩散** | `MomentsExport.kt:692-700` |
| P2 | 四态互斥穷尽 | **单 mediaId 层面互斥穷尽**；动态/mediaId 口径不统一 | `MomentsExport.kt:704-723`（穷尽）vs `686-691`（口径） |
| P2 | 静默吞异常 | 四态路径**无** `?: return` 吞；建索引 3 层 listFiles 有轻微静默 | `MomentsExport.kt:694`（`getOrElse` 兜底）；`551/554/557`（无日志） |
| P2 | 新旧路线干扰 | **不干扰** | `MomentsExport.kt:263,341` vs 独立探针 |
| P2 | `COPY_IMAGES` 默认 | **false** ✅ | `MomentsExport.kt:56` |

---

## 是否允许进入真机验证

**NO。**

**前置条件（满足其一前，不得开 `COPY_IMAGES`、不得上真机）**：

1. **必须**：`mediaIdsOf()` 接好 protobuf（`MomentsExport.kt:670-675` 的 TODO 落地），返回 `vm3.d` 原值列表。否则 `imgCopied` 恒 0，真机验证只能得到「全部 noMediaId」的假象，验不出索引/匹配/拷贝链路是否工作，还可能误导排查方向。
2. **建议**（可并行）：`mediaIdsOf` 落地时同步打印 `vm3.d` 原值与 `fliterPre(vm3.d)`，用于方案 Q5 判据 #1 现场对比文件名，确认「只砍一次」契约成立。

**满足后上真机的最小判据**（沿用方案 Q5）：滚 3 屏 → `imgCopied>0`；破坏 cache 目录 → `imgRootMiss>0`；stat 行四字段可见。

---

[DONE]
