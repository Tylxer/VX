# R52 · 「被撤回图片可打开」修复判据设计（Pro 只读 lane）

> **Lane 性质**：本轮**只设计、不改源码**。⛔ 硬红线：不改任何源码、不碰 adb、不装包、不跑 gradle、不 kvset、不写微信库。唯一可写文件即本文档 `docs/_r52_fix_design.md`。
> **档位判定**：判据设计 / 根因推理 / Hook 链路推导 ⇒ `CLAUDE.md:107-116` §四② **Pro 类活**，由本 lane 独占；主会话只做接线 / 单测 / 文档 / 跑验证。
> **证据分级**：决定性真机证据标「已证（非推断）」；不确定一律标 `[推断]` / `[需真机]`；外部掘金 coder-pig / 微X 实验一律标 `[转引]`，⛔ 不得当本设备实测（`_r49_revokeimg_reference.md:26,100`、`_r52_revokeimg_rootcause.md:5,21`）。
> **好友一律用昵称**：**verity** / **测试群聊** / **文件传输助手**（`CLAUDE.md:12`），⛔ 不写裸 wxid。
> **判据纪律**：`seen/matched/blocked/skipped/row=0/verdict/tokens/gen` 只作**定位/归因**，⛔ 不作功能判据；主判据始终是「用户点开能否看到完整大图」（`_r52_device_log_snapshot.md:176-178`）。

## 决定性证据（01:03:59，好友 **verity**，msgId=19640720，md5=`3fadbde02a26c4ab4034c3ab3a604e51`）

```
01:03:59.051 RRV record ok=true origType=3 imgPath=THUMBNAIL_DIRPATH://th_3fadbde02a26c4ab4034c3ab3a604e51
01:03:59.053 VFG protectMedia img=3fadbde02a.. ttl=600000ms pool=1
01:03:59.055 RRV imgAudit row=0 status=-999 big=NULL       ← 口径错，非「行被删」证据
01:03:59.057 AR blocked revoke
01:03:59.072 VFG blocked MEDIA delete img=3fadbde02a..      ← 缩略图文件保住
01:03:59.080 [D]#worker MTG delete table=ImgInfo2 where=id=? reason=shouldBlock-false gen=1   ★决定性
01:04:37.180 MTG stat gen=1 seen=43 matched=1 blocked=0 skipped=1
             verdict=REAL_EVENT pool=ok tokens=1 events=1 switch=antirevoke.media_delete
```

### 补充决定性证据（01:06，HDIMG trace 因果链闭合；team-lead 同步，本设备一手实测）

用户点开 01:03 那张被撤回图后（反馈「还是看不到」），新探针抓到（好友 **verity**，msgId=19640720）：

```
01:06:32.206 VFG blocked MEDIA delete ... th_3fadbde02a…_tmp.jpg      ← 连临时文件都保住
01:06:34.877 HDIMG fired msgId=19640720 localId=0 t=0 img=null
01:06:34.879 HDIMG trace msgId=19640720 ... ctor=n=5 a[0]=Long:0 a[1]=Long:19640720
              a[3]=m:com.tencent.mm.modelimage.m@25821f6
              fld[h=0 i=0 j=0 o=0 q=19640720] stack=m.h|m.b|u0.x|u0.h|d0.D
01:06:34.881 HDIMG trace db msgId=19640720 imgPath=THUMBNAIL_DIRPATH://th_3fadbde02a26c4ab4034c3ab3a604e51
```

**形态逐字对照**：00:13 老图（19640543）、23:55 老图（19640471）同批触发，与 01:03 那张**逐字一致**：`a[0]=Long:0`、`fld[h=0 i=0 j=0 o=0 q=<msgId>]`、`img=null`；而 `HDIMG trace db` 三张图**都能读出完整 `imgPath`**。正常未撤回图则是 `h/i/j=875524`、`img=SERVERID://…`（`_r52_device_log_snapshot.md:85-86`）。

⛔ **已排除两支**：①「微信从 `message` 表拿不到 `imgPath`」——`HDIMG trace db` 三张图都读出完整 `imgPath`；②「磁盘文件被删」——`VFG blocked MEDIA delete … th_…_tmp.jpg` 连临时文件都保住。

**因果链已闭合（⛔ 非 `[转引]`，本设备一手实测）**：`message.imgPath` 在 + 磁盘文件在 ⇒ 唯一断点是 ㊸ `MTG delete table=ImgInfo2 where=id=? reason=shouldBlock-false`（判据放行 ⇒ 索引行被删）⇒ `com.tencent.mm.modelimage.m` 取不到该行的 `bigImgPath` / `msgSvrId` ⇒ 构造 `t1` 时 `fld[h=0 i=0 j=0 o=0]`、`img=null` ⇒ 大图组件拿不到 `SERVERID://…`。⇒ **修复只需让 `ImgInfo2 where=id=?` 这条 delete 被拦住，预期不必改渲染层 / 不必重建消息 / 不必额外写库**（对 T2 的直接影响，见 T2 增量复核）。

**已证结论（⛔ 非推断，`_r52_device_log_snapshot.md:161-175`）**：
1. 微信撤回删媒体索引行**确实走 wcdb `delete()`**，钩子亲眼看到 `table=ImgInfo2` ⇒「钩子没被调用」「删走 `execSQL` / 预编译 statement / `INSERT OR REPLACE` / native」三种猜测**全部证伪**。
2. `matched=1` / `tokens=1` / `gen=1` ⇒ 目标表命中、保护池有 md5 token、`invalidate()` 已跑（撤回窗口已开）⇒ 不是「没进窗口」也不是「tokens 空」。
3. ⛔ `reason=shouldBlock-false` ⇒ 判据自己把这条**放行**了；`blocked=0 skipped=1` ⇒ **`ImgInfo2` 索引行被真删掉**。
4. 根因 = **判据覆盖缺口**：判据只在 `where`/`args` 里找 26/32 位 md5 token，而这条删除是 **`ImgInfo2 where=id=?`（数值主键、args 无 md5）** ⇒ 天然匹配不上。⇒ **R51 的 Hook 链路是对的，错的是判据**。
5. 其它已证：`message.imgPath` 仍在、磁盘缩略图被 `VFG` 保住；点开被撤回图时 `HDIMG fired ... localId=0 t=0 img=null`（正常图 `img=SERVERID://… localId=875524`）。
6. ⭐ **因果链已闭合**（01:06 补充证据，本设备一手实测，⛔ 非 `[转引]`）：`message.imgPath` 在 + 磁盘文件在（连 `th_…_tmp.jpg` 临时文件都被 `VFG` 保住）⇒ 唯一断点是 ㊸ 判据放行 ⇒ `ImgInfo2` 行被删 ⇒ `com.tencent.mm.modelimage.m` 取不到 `bigImgPath`/`msgSvrId` ⇒ `fld[h=0 i=0 j=0 o=0]` / `img=null` ⇒ 看不到大图。00:13 / 23:55 / 01:03 三张老图形态逐字一致。⇒ **修复面收敛到「拦住这一条 delete」，⛔ 不必改渲染层 / 重建消息 / 额外写库**。
7. ⛔ **必须区分两个「闭合」（本轮最关键的证据分级，防止自相矛盾）**：
   - **断点定位闭环 = 已证**：「判据放行 ⇒ 行被删 ⇒ `h/i/j=0` ⇒ `img=null` ⇒ 看不到大图」这条**因 → 果**链由 01:03 + 01:06 两段一手日志闭合。
   - **修复后闭合 = `[需真机]`**：拦住 delete 后 `ImgInfo2` 行保留，微信是否仍因**别的字段/状态**把 `h/i/j` 填 0，**本设备从未观测，⛔ 不作定论**（T2 增量复核、T5 判定表专列此支）。⇒ `_r52_revokeimg_rootcause.md:21` 那句「『拦住 DB 行 ⇒ UI 显示大图』本设备未闭环」在**修复后**这一半**依然成立**，不得因断点定位闭环就当成已证。

[DONE] HEAD-rev（01:06 HDIMG trace 证据 + 「断点定位闭环已证 / 修复后闭合 `[需真机]`」分级）

## 分步清单

- [ ] T1 逐条走判据（带 `文件:行号`，指名放行行）
- [ ] T2 ⭐ D2 路线对照表（≥2 条可证伪路线 + 推荐 + 理由）
- [ ] T3 判据纯函数化 + 单测契约 + 变异验证
- [ ] T4 三档边界表（① 进入 / ② 返回 / ③ 复位逐条，TTL 与冷启各自单列）
- [ ] T5 真机验收与反向对照

> **01:06 补充证据修订**（team-lead 同步 `HDIMG trace` 因果链闭合）：`HEAD-rev` / `T1-rev` / `T2-rev` / `T5-rev`。
> **T3 契约订正修订**（`r52-fix-a` 实现反馈）：`T3-rev`（`shouldBlock` id 出口 / B7 / B15b / C12 / M2 / M5）。
> **T3 双口径最终对齐**（`r52-fix-a` 实测确认）：`T3-rev2`（设计侧 **7 组 `7/7`** + 脚本侧 **8 个变异体 `8/8`**；M2 合并为 1 组、组内 M2a·M2b 两个子变异体，脚本键 `M2`=M2a / `M2b`=M2b，⛔ 不写「第 8 组」；源码 hash `b1893060affd3b34` 未变）。
> **T3 精确措辞对齐**（`r52-fix-a` 方法名/断言名）：`T3-rev3`（B15 `assertFalse(isNumericKeyDelete(...))` 断言 + `b15PathColumnGoesToTokenChannelOnly` / B15b `b15bPathColumnWithNumericRightValueStillRejected` 变异后 `{"875556"}/BLOCKABLE/true` / C12 三条全名 / M5 `Failures=2` 两条腿互不重叠 / M2b `Failures=3` 三条全名）。
> 红线不变 —— 本轮仍**只设计、不改源码、不碰设备、不跑 gradle**，唯一可写文件仍为本文档。

[DONE] skeleton

## T1 逐条走判据

### 输入还原（从决定性证据 + 磁盘源码）

| 参数 | 本次取值 | 来源 |
|---|---|---|
| `table` | `"ImgInfo2"` | `01:03:59.080 MTG delete table=ImgInfo2` |
| `whereClause` | `"id=?"` | 同上 |
| `whereArgs` | `[数值主键 id]`（1 个元素） | `MTG delete ... where=id=?`；`MediaTableGuard.fromDelete()` `:292-298` 按位置取 `a[2] as? Array<*>`，**不按类型取**（`:291` KDoc：wcdb 有 `String[]`/`Object[]` 两种重载） |
| `protectedTokens` | `{ "3fadbde02a26c4ab4034c3ab3a604e51" }` | `cachedTokens` = `snapshotLiveTokens()`（`:350-360`）；`stat tokens=1` |
| `nowMs` / `tokenIssuedMs` | `SystemClock.elapsedRealtime()`；`lastIssuedMs` 由 `invalidate()` `:161` 刚设 | `gen=1` |
| `ttlMs` | `WINDOW_MS = 600_000L` | `MediaTableGuard.kt:70` |

### args 是 `Long` 还是 `String`？（题目点名）

- **单从日志判不出来** `[需真机]`：`fromDelete()` `:296` 是 `a.getOrNull(2) as? Array<*>`，只按位置取数组，不区分 `String[]`（元素如 `"875556"`）还是 `Object[]`（元素 `Long`）。要定类型需探针把 `param.args[2]` 各元素的 `javaClass.name` 打出来。
- ⛔ **但这个不确定性对判据结果零影响**：`MediaDeleteLogic.extractTokens()` `:205-209` 对每个元素统一 `val s = a?.toString() ?: continue`，无论 `Long` 还是 `String` 都得到**十进制数字串**（数值主键，非 hex、长度远小于 32）；`:208` `collectHex(s, spec.tokenLen, out)` 里 `IMG_SPEC.tokenLen=32`（`:81`），正则 `MediaDeleteLogic.kt:134` 要求「恰好 32 位、前后均非 hex」⇒ 数字串**永远抽不出** 32 位 md5。`:211` 对 `whereClause="id=?"` 的内联字面量同样抽不出。这正是决定性证据第 4 条「args 里没有任何 md5」在代码层的写照。

### 逐步走查（逐行，⛔ 不写笼统结论）

| 步 | 位置 | 条件 | 本次取值 | 结果 |
|---|---|---|---|---|
| 0 | `MediaTableGuard.kt:229-236` `onBefore` | `!enabled` / `param.args == null` | `enabled=true`（`MTG stat enabled=true`）、`a` 非空 | 通过 |
| 1 | `:239-243` | `fromDelete(a)` | `Parsed("ImgInfo2", "id=?", [id])` | 解析成功（非 `delete-unparsed`） |
| 2 | `:246-250` | `MediaDeleteLogic.isTargetTable(p.table)` | `normalizeTable`（`:114-118`）→ `"imginfo2"` ∈ `TARGET_TABLES`（`:64`） | ✅ **白名单命中** ⇒ `matched++`（对应 `matched=1`） |
| 3 | `:254-259` | `cachedTokens.isEmpty()` | 非空（`tokens=1`） | 通过，**不是** `tokens-empty` 放行 |
| 4 | `MediaDeleteLogic.kt:250` `shouldBlock` | `!isTargetTable(table)` | false | 通过 |
| 5 | `:251` | `protectedTokens.isEmpty()` | false | 通过 |
| 6 | `:252` → `isRevocationWindow` `:226-233` | 半开窗口 `0 <= now-issued < ttl` | 撤回刚发生、`gen=1` ⇒ delta 远小于 600000；同为 `elapsedRealtime()`、单位自洽；`ttl>0` | ✅ **窗口通过 = true**（⛔ 关键：**不是窗口把它放行**，这一关是过的） |
| 7 | `:253` → `classifyDelete` `:164` | `!isTargetTable` | 表命中 | 继续 |
| 8 | `:166-167` | `w == null || w.isEmpty()` | `w = "id=?"` | 继续 |
| 9 | `:169` | `UNSAFE.containsMatchIn(w)`（正则 `:102-104`） | `"id=?"` 不含 `select/like/is [not] null/in/!=< >/and/or/,/;` | 继续（⛔ 注意 `\bin\b` 不会误伤 `id`） |
| 10 | `:170` | `(whereArgs?.size ?: 0) > 1` | size=1 | 继续 |
| 11 | `:172` | `specFor("imginfo2")` | `IMG_SPEC`（`:76-82`，`tokenLen=32`） | 拿到 spec |
| 12 | `:174` | `SINGLE_EQ.matchEntire("id=?")`（正则 `:110-112`） | 命中：`column="id"`、`groupValues[2]="?"` | 继续 |
| 13 | `:175-176` | `column in spec.keyColumns` | ⭐ **`"id"` 确实在 `IMG_SPEC.keyColumns`（`:78`）** | ✅ **列白名单这一关也过了** —— ⛔ 把「id 列不在键列」当原因是错的 |
| 14 | `:178-179` | 占位符数量与 `whereArgs` 对齐 | `?`=1、`whereArgs.size`=1 | 继续 |
| 15 | `:181` → `extractTokens` `:198-213` | 抽 32 位 hex token | args 数字串 + 内联 `"id=?"` 都无 32 位 hex ⇒ **`tokens = ∅`** | ⭐ 空集 |
| 16 | **`:182`** | **`if (tokens.isEmpty()) return DeleteClass.UNKNOWN`** | 命中 | ⛔ **这就是把这条放行的那一行条件**（在 `classifyDelete` 内） |
| 17 | **`:253-255`** | **`if (classifyDelete(...) != DeleteClass.BLOCKABLE) return false`** | `UNKNOWN != BLOCKABLE` ⇒ true | ⛔ **`shouldBlock` 出口的放行行** |
| 18 | `:256-259`（token 命中保护集） | — | **根本没执行到**（已在 `:254` 返回） | — |
| 19 | `MediaTableGuard.kt:261-268` | `shouldBlock(...)` 返回 false | `skipped++`（`skipped=1`）、`observe(execSql, p, "shouldBlock-false")`（`:266`） | ⛔ **不置 `param.result`（跳过 `:270-279` 的 `FAKE_ROWS`）⇒ 微信 `delete()` 正常执行 ⇒ `ImgInfo2` 索引行被真删** |

### 指名到行的结论

- **直接放行行 = 两处**：① `MediaDeleteLogic.kt:182` 的 `if (tokens.isEmpty()) return DeleteClass.UNKNOWN`（`classifyDelete` 内第一个把形状正确但无 token 的删除判为放行的分支）；② 由它传导的 `MediaDeleteLogic.kt:253-255` `if (classifyDelete(...) != DeleteClass.BLOCKABLE) return false`（`shouldBlock` 出口）。最终落点 `MediaTableGuard.kt:266` 打出 `reason=shouldBlock-false`。
- **根因**：`extractTokens` 的设计前提是「`where`/`args` 里能抽到 26/32 位 md5 token」（`MediaDeleteLogic.kt:191-196` KDoc、`_r52_device_log_snapshot.md:168-169`），而真机删除是 `where=id=?` **数值主键**，args 无任何 md5 ⇒ 前提天然不成立。判据把「键列的值长什么样」和「键列是不是白名单」混为一谈了。
- ⛔ **三个常见误判在此澄清**：不是「钩子没被调用」（`matched=1`）；不是「没进撤回窗口」（`isRevocationWindow` 为 **true**）；不是「`id` 列不在键列白名单」（`id` 在 `:78`）。错的是**键值是数值、不是 md5**。
- ⛔ `imgAudit row=0 status=-999 big=NULL` 是**哨兵值**（`_r52_revokeimg_rootcause.md:69-81`），不是「行被删」的证据；但它同时暴露了「按 msgId 查 `ImgInfo2` 查不到」这一事实，是 T2 甲路线的头号预警（见 T2）。
- ⭐ **下游因果链已在 01:06 闭合**（补充证据，见头部「补充决定性证据」）：本表第 19 步的 `reason=shouldBlock-false` 放行 ⇒ `ImgInfo2` 行被真删 ⇒ `HDIMG trace` 里 `fld[h=0 i=0 j=0 o=0]` / `img=null` ⇒ 点开看不到大图。⛔ **已排除两支**：「微信从 `message` 表拿不到 `imgPath`」（`HDIMG trace db` 三张老图都读出完整 `imgPath`）、「磁盘文件被删」（`VFG` 连 `th_…_tmp.jpg` 临时文件都保住）。⇒ T1 指名的这两处放行行就是**唯一断点**，修复不需动渲染层。⛔ 但「拦住 delete 后行保留、`h/i/j` 是否恢复」仍 `[需真机]`（头部第 7 条、T2 增量复核），不作定论。

[DONE] T1
[DONE] T1-rev（补 01:06 下游因果链 + 排除两支）

## T2 ⭐ D2 路线对照表（本轮重点）

> 依 `CLAUDE.md:42-43` **D2 路线对照表前置**（核心动作 / 代码量 / 是否额外写库 / **是否需要动渲染层** / 竞态 / 失效场景 / 验证成本，没有这张表不许开工）；依 `CLAUDE.md:51-52` **D5**：⛔ 不许用「太简单所以不可靠」否决任何方案，要指名「在什么情况下失效」并给判别实验。

### 补充证据（01:06 HDIMG trace）与因果链闭合 —— T2 的立论前提

> 完整日志与形态对照见本报告头部「补充决定性证据」段（team-lead 同步，**本设备一手实测**，⛔ 与 `_r49_revokeimg_reference.md:26,100` 掘金 coder-pig / 微X `[转引]` 实验严格分开，不得写成外部实验外推）。

用户点开 01:03 那张被撤回图后（反馈「还是看不到」）：`HDIMG fired msgId=19640720 localId=0 t=0 img=null`；`HDIMG trace ... ctor=n=5 a[0]=Long:0 a[1]=Long:19640720 a[3]=m:com.tencent.mm.modelimage.m@25821f6 fld[h=0 i=0 j=0 o=0 q=19640720] stack=m.h|m.b|u0.x|u0.h|d0.D`；`HDIMG trace db ... imgPath=THUMBNAIL_DIRPATH://th_3fadbde02a26c4ab4034c3ab3a604e51`；`VFG blocked MEDIA delete ... th_3fadbde02a…_tmp.jpg` 连临时文件都保住。00:13（19640543）/ 23:55（19640471）老图同批触发、**形态逐字一致**（`a[0]=Long:0`、`fld[h=0 i=0 j=0 o=0 q=<msgId>]`、`img=null`，而 `HDIMG trace db` 三张图都读出完整 `imgPath`）；正常未撤回图 `h/i/j=875524`、`img=SERVERID://…`。

**因果链（⛔ 已证，非推断）**：`message.imgPath` 在 + 磁盘文件在 ⇒ 唯一断点 ㊸ `MTG delete table=ImgInfo2 where=id=? reason=shouldBlock-false` ⇒ 索引行被删 ⇒ `com.tencent.mm.modelimage.m` 取不到该行 `bigImgPath`/`msgSvrId` ⇒ 构造 `t1` 时 `fld[h=0 i=0 j=0 o=0]`、`img=null` ⇒ 大图组件拿不到 `SERVERID://…`。⛔ **已排除两支**：「微信从 `message` 表拿不到 `imgPath`」（`HDIMG trace db` 三张图都读出完整 `imgPath`）、「磁盘文件被删」（`VFG` 连 `th_…_tmp.jpg` 临时文件都保住）。

**对 T2 的直接影响（team-lead 同步结论）**：修复只需让 `ImgInfo2 where=id=?` 这条 delete 被拦住，**预期不必改渲染层 / 不必重建消息 / 不必额外写库**。⇒ 对照表新增「是否需要动渲染层」维度；甲 / 乙 失效场景按下「增量复核」重估。⛔ **但必须区分两个闭合**（头部第 7 条）：**断点定位闭环**（因→果）已证；**修复后闭环**（拦住后行保留、`h/i/j` 是否恢复）**仍 `[需真机]`**，本设备从未观测，⛔ 不作定论。

### 共同约束（五条路线都适用）

- **自变量唯一**：只改判据，⛔ 不动 hook 注册。`_r49_revokeimg_reference.md:38,40` 已证 `XposedBridge.hookAllMethods(cls,"delete",…)` 是**方法级**、任何表的 delete 都回调，扩判据无需新挂钩子。
- ⛔ **保守性红线**（`MediaDeleteLogic.kt:14-22`）：媒体表同时服务微信正常清理（删过期媒体 / 空间 GC）。无条件拦 delete 会让微信 GC 失效、存储只增不减，**比「图片打不开」严重得多**。
- **D4 控制优先于观测**（`CLAUDE.md:48-49`）：能阻止删除发生就不退到事后补救；⛔ 需要判据本身就说明在补救。
- 「是否额外写库」= 是否写**微信库**；五条路线**都只读不写微信库**（本轮红线）。
- ⭐ **渲染层不动**（01:06 新增约束）：因果链闭合后，甲 / 乙 / 丙① / 丙② 的修复面只落在判据，⛔ 预期不触碰 `com.tencent.mm.modelimage.m` / `t1` 构造 / 大图 UI 组件；只有丙′③ 需动渲染层或写回微信库 ⇒ 越界。

### 路线对照表（8 列 = 路线 + 7 个维度；⭐ 新增「是否需要动渲染层」维度，因 01:06 因果链闭合）

| 路线 | 核心动作 | 代码量 | 是否额外写库 | **是否需要动渲染层**（01:06 闭合后新增） | 竞态 | 失效场景（⛔ 每条都要写，详见下文） | 验证成本 |
|---|---|---|---|---|---|---|---|
| **甲｜id 反查法** ⭐ | `invalidate()` 时按 `protectedMedia` 里的 **md5** 反查 `ImgInfo2` 得到 `id` 集合缓存为 `@Volatile cachedRowIds`；`shouldBlock` 对 `id=?` 用 `whereArgs` 归一后与该集合比对，命中 ⇒ 拦 | 中：feature 层加 1 次只读 `rawQuery` + 1 个 `@Volatile` 字段；纯函数层加 ~4 个函数 + `classifyDelete`/`shouldBlock` 各 1 分支。⛔ 不动 hook 注册 | **否**（`rawQuery` 只读，`cachedRowIds` 是进程内集合） | ⭐ **否**：01:06 因果链已闭合 —— 唯一断点是 `ImgInfo2` 行被删；拦住 delete ⇒ 行保留 ⇒ `com.tencent.mm.modelimage.m` 取到 `bigImgPath`/`msgSvrId` ⇒ `h/i/j` 由该行填充 ⇒ ⛔ **渲染层零改动**。`[需真机]` 复核「行保留仍填 0」支（见增量复核） | ⭐ **有**：反查在 `record()` 线程、删除在 `[D]#worker` 线程，真机间隔约 10~25ms；连撤两张会因快照整体重建漏掉第一条 | ① 反查沿用 `auditImage` 的 msgId 口径 ⇒ 真机已证 `row=0` 全军覆没；② 删除用批量/内联形态 ⇒ 不覆盖；③ 反查赶不上删除 ⇒ 行已删不可回滚；④ `ImgInfo2` 行本就不存在；⑤ ⭐ 行保留但 `h/i/j` 仍 0 `[需真机]` | 中：撤前用 `op=label sub=sql` 留档 `origImgMD5`/`thumbImgPath` 列形态 `[需真机]` + JVM 单测/变异 + 一轮真机正反向 |
| **乙｜窗口内目标表全拦** | `shouldBlock` 里只要 `isTargetTable(table) && isRevocationWindow(...)` 就 true，⛔ 旁路 `classifyDelete`（`:253`）与 token 闸门（`:256-259`），窗口 600s 内命中白名单表的 delete 一律 `param.result=FAKE_ROWS` | **最小**（约 1~3 行旁路） | 否 | **否**（同甲：拦 delete 即可）。⛔ 但乙多拦的 GC / 删会话 / 删无关图对修复**零贡献**，纯属副作用 | **无**（不依赖反查时序） | ⭐ **微信 GC / 删会话 / 删无关图 / 朋友圈清理落在窗口内 ⇒ 整批误拦、存储只增不减**；窗口边界差 1ms；重试风暴；乙-7 行保留仍填 0（与甲-7 合流） | **低**（代码），但**高**（证明没破坏 GC 要做 T2 乙表里的判别实验） |
| **丙①｜upstream 合并反查**（甲的省一次 query 变体） | 把反查合并进 `RevokeRescue.kt:246` 已有的 `auditImage()` 那次 `rawQuery`，顺手 `select id, msglocalid, origImgMD5` 交给 `invalidate()` 缓存 | 比甲略小（零额外 query） | 否 | **否**（同甲，仅换反查位置） | 比甲**小**（同线程同一次查询，`:126`→`:128` 之间无新 IO） | ⛔ `auditImage` 本身 `row=0` ⇒ 合并进去**同样拿不到 id**，且换口径的机会被绑死在 `auditImage` 的 `where` 上；改诊断函数语义会让读 `row=0` 日志的人误判 `[推断]` | 中（同甲，且需额外解释 `row=0` 语义变化） |
| **丙②｜before 里关联 `message` 表** | `id=?` 到达时按数值关联 `message.imgPath`/`msgId` 判断是否刚被撤回 | 中 | 否 | **否**（仅换判据查库位置，⛔ 不动渲染层） | **大**：`before` 在微信 DB 线程热路径同步查库，性能/互锁风险（`AntiRevoke.kt:863`） | `message.imgPath` 仍是 `THUMBNAIL_DIRPATH://th_<md5>` ⇒ 仍要抽 md5 再比，绕回甲的口径；无 `AntiRevokeHint.BYPASS` 会被自家 SQL hook 改写；`message` 行虽被 `AR blocked revoke` 保住但关联键对不上（`imgAudit row=0`） | 高（热路径查库 + 真机性能观测） |
| **丙′③｜到达即备份**（D4 真·事前控制，最重） | 图片消息到达（`type=3`）即把 `ImgInfo2` 关键列 + `image2` 文件备份到模块自有目录，撤回后还原 | **最大**（完整备份 + 还原链 + 幂等） | ⛔ **若还原需写回微信库则违反本轮红线**；只写模块自有库则不违反但喂不回 UI | ⭐ **是**：备份喂不回 UI（01:06 已证 `img=null` 源于 `ImgInfo2` 行缺失），要生效须 hook 渲染层或写回微信库 ⇒ 越界本轮红线 | 小（到达即做） | ⛔ `HDIMG fired ... img=null`（`_r52_device_log_snapshot.md:85`）说明组件拿不到路径，备份在模块目录里**喂不回去**；「已被删的行不可回滚」正是当前困局，写回即破坏红线；代码量/回归面最大（`_r52_revokeimg_rootcause.md:61`） | **最高**（需 hook UI 层或写回微信库，超出本轮边界） |

⭐ **新维度读法**：01:06 因果链闭合后，「是否需要动渲染层」只剩丙′③ 答「是」（且它因此越界）。甲/乙/丙①/丙② 都只需拦 `ImgInfo2 where=id=?` 这一条 delete，**预期不必改渲染层 / 不必重建消息 / 不必额外写库** —— 这正是 team-lead 同步证据对 T2 的直接影响。⛔ 但「拦住后行保留、`h/i/j` 是否恢复」仍 `[需真机]`，见下增量复核，⛔ 不作定论。

### 甲｜id 反查法（展开：线程 / 句柄 / 耗时 / 查不到怎么办）

**反查在哪个线程、哪个句柄上做**：
- **线程**：`RevokeRescue.record()` 所在线程（真机 `01:03:59.051 RRV record` 打在 `[GT]ColdPool#14`，即撤回回调线程池），**不是** `before` 所在的 `[D]#worker`（`01:03:59.080 MTG delete`）。⛔ **绝不能在 `beforeHookedMethod` 里查库**：那是微信 DB 线程热路径，同步 `rawQuery` 会拖慢甚至与写事务互锁（`AntiRevoke.kt:863` 已就此立过约束）。
- **句柄**：`AntiRevoke.msgQueryHandle()`（`AntiRevoke.kt:851-855`）返回 `(msgDb, rawQueryMethod)`；`msgDb` 是写库 hook 里捕获的**消息库实例**（`:828`），`ImgInfo2` 就在该库（`_r35_revoke_img_anchor.md:73-79` 的关联式 `SELECT bigImgPath,thumbImgPath FROM ImgInfo2, message` 证明两表同库）。SQL 前缀必须带 `AntiRevokeHint.BYPASS`（`RevokeRescue.kt:269` 同款），防被模块自己的 SQL hook 改写/拦。
- ⛔ **反查口径必须用 md5，不能沿用 `auditImage` 的 msgId/msglocalid 口径**：`auditImage`（`RevokeRescue.kt:246-302`）就是按 `where msglocalid=? or msgSvrId=?` 查 `ImgInfo2`，真机 **`row=0`**（`01:03:59.055`）；而保护池里的 key 是 `MediaGuardLogic.hexToken(imgPath)` 取到的 **32 位 md5**（`VoiceFileGuard.kt:129,151`）。⇒ 应改查 `where origImgMD5=?`（列 + `origImgMD5_index` 索引见 `_r35_revoke_img_anchor.md:64`）或 `where thumbImgPath like '%<md5>%'`（`_r35:99-106` 已证 `hexToken` 能从 `THUMBNAIL_DIRPATH://th_<md5>` 取到该 md5）。⛔ **`origImgMD5`/`thumbImgPath` 列值是否确实含这个 md5 ⇒ `[需真机]`**：撤前按 T5 第 4 步 `select ... origImgMD5, hex(thumbImgPath) ...` 留档一次即可定。
- **耗时**：`auditImage` 那次同步 `rawQuery` 在真机上约 2ms 量级（`.055`→`.057` 之间还夹着 `AR blocked`）；`msglocalid` 有 `msgLocalIdIndex`（`_r35:60`）、`origImgMD5` 有 `origImgMD5_index`（`:64`），按索引查同量级 ⇒ 目标 ≤10ms，`[需真机]` 用 `elapsedRealtime()` 前后打点实测。⛔ 超过 20ms 要评估下方竞态。

**查不到时怎么办（必须写）**：
1. **保守放行，但必须留痕**：日志形如 `MTG id-lookup MISS md5=... row=0`，⛔ 不能静默 `return`（`VoiceFileGuard.kt:130-138`「一行日志都没有害得排查半天」的教训）。
2. ⛔ **不退化成乙**：查不到就「窗口内全拦」会把 GC 一起拦掉；⛔ 不随便挑一个 id 凑数。
3. `cachedRowIds` 为空 ⇒ `shouldBlock` 对数值主键形态恒 false（不确定即放行，`MediaDeleteLogic.kt:21-22`）；**原有 md5 token 通道保持逐字不动** ⇒ 甲是**增量**一条分支，不替换既有 `BLOCKABLE` 路径。
4. 备选兜底 `[需真机]`：若 md5 反查恒查不到，改用「从 `message` 行已保住的 `imgPath` 按 `_r35:78` 关联式 `ImgInfo2.msglocalid = message.msgId` 反查」——但这正是 `imgAudit row=0` 的口径，须先真机证 `row=0` 到底是「行已被删」还是「关联键对不上」。

**竞态（真实，带证据）**：
- 真机时序：`invalidate()` 落在 `.055`~`.072` 之间（`RevokeRescue.kt:126 auditImage` → `:128 invalidate`），删除 `.080` 到达 `[D]#worker` ⇒ 反查必须在这 ~10~25ms 内完成并发布 `cachedRowIds`。本次 `gen=1`/`tokens=1` 证明赶上了，但两件事在**两个不同线程**，⛔ `[推断]` 无 happens-before 保证 ⇒ `cachedRowIds` 必须 `@Volatile`（同 `cachedTokens` `:93`）。
- **连撤两张图**：第二次 `record()→invalidate()` 会**整体重建** `cachedRowIds`（不是追加），若第一次删除尚未到达，会被第二次快照漏掉 ⇒ 需要按 token 分桶或取并集并各自带 TTL，`[需真机]`。
- 冷启后 `cachedRowIds` 空、池也空（`_r51_media_table_guard_impl.md:231`），重试删除无人拦。

**甲的具体失效场景（⛔ 不许只写优点）**：
| # | 失效场景 | 判别实验 |
|---|---|---|
| 甲-1 ⭐ | **反查沿用 msgId 口径 ⇒ 全军覆没**：`imgAudit row=0` 已是一手证据；改按 md5 查又要求 `origImgMD5`/`thumbImgPath` 列形态符合 `[需真机]` | T5 第 4 步撤前 `ImgInfo2` 快照 + 撤时 `MTG id-lookup rows=<n>` 日志；`rows=0` 即停手 |
| 甲-2 | 微信删除用的不是 `id=?` 而是 `msglocalid=?` / `msgSvrId=?` / `msgid=?` 数值主键（本次是 `id=?`，版本/消息类型可能变） | `dev.py q 'MTG'` 抓真实 `where=` 原文，数值主键列集合须覆盖这四列 |
| 甲-3 | 删除是 `id IN (?,?,?)` / `id BETWEEN` / `id>?` **批量或比较形态** ⇒ 现有 `UNSAFE`（`:169`）与 `whereArgs.size>1`（`:170`）判 `BULK_OR_UNSAFE` 放行，甲**有意不覆盖** | 撤图时看 `MTG delete ... where=` 是否含 `IN`/比较符；有则甲不覆盖，需单列决策 |
| 甲-4 | 反查完成前删除已发生（竞态）⇒ 行已被删、**不可回滚** | 打点 `id-lookup cost=<ms>` 与 `MTG delete` 时间戳对比 |
| 甲-5 | `ImgInfo2` 行在撤回时本就不存在（图还没下完就撤回）⇒ 反查 `row=0`、无 id 可缓存。⛔ **01:06 修订**：原写「此时根因可能根本不在 DB 行」已被**证伪** —— `img=null` 恰是 DB 行缺失的下游后果（`HDIMG trace db` 三张老图都读出完整 `imgPath`，排除 `message` 表支）；本场景的**可观测后果**就是 `h/i/j=0, img=null` 链路起点，不再是等权的「另有根因」假设 | 撤前快照确认目标 md5 行是否存在；对照 `HDIMG trace db` 是否有 `imgPath` |
| 甲-6 | 走 `execSQL(sql)` **无 bindArgs**、内联 `... where id=875556` ⇒ `fromExecSql`（`:307-327`）把整串塞进 `where`、`args=null`；`extractTokens` 从内联数字串抽不出 32 位 ⇒ 仍 UNKNOWN。甲需额外支持「内联数值右值」比对 | T3 单测 B5；真机 `dev.py q 'MTG'` 看 `reason=` 前缀是 `delete` 还是 `execSQL` |
| 甲-7 ⭐ | **`[需真机]` 拦住 delete、`ImgInfo2` 行保留，但微信仍因别的字段/状态把 `h/i/j` 填 0** —— 因果链闭合后**唯一未被证伪的残留支**。`fld[h=0 i=0 j=0 o=0 q=<msgId>]` 全 0 可能不只由「行不存在」引起，也可能由行内 `status`/`iscomplete`/`hashdthumb`/`msgSvrId`/`bigImgPath` 为空、撤回时微信**改写该行字段**（UPDATE 而非 delete）、或 `t1` 还依赖第三张表 / 内存缓存引起。⛔ **不作定论**：本设备从未观测「行被拦住保留后」的 `HDIMG trace` | 修复装包 → 请 **verity** 发**新图**撤回 → 撤后快照确认目标行 `n: 1→1` → 点开读 `HDIMG trace` 的 `fld[h/i/j/o]` 与 `img=`；三种互斥结果见下「增量复核」 |

### 乙｜窗口内目标表全拦（⛔ 按 D5 指名具体失效场景，不许用「太简单」否决）

**核心动作**：`shouldBlock` 内把 `MediaDeleteLogic.kt:253` 的 `classifyDelete` 闸门与 `:256-259` 的 token 闸门在窗口内旁路，只留 `isTargetTable` + `isRevocationWindow`。

⛔ **不按 D5 用「太简单所以不可靠」否决**。逐条指名具体失效场景并给判别实验（`CLAUDE.md:51-52` 要求「指名在什么情况下失效，再去做那个实验」）：

| 失效场景 | 具体触发 | 后果 | 判别实验（一次能跑完、只读） |
|---|---|---|---|
| **乙-1 微信自己清理过期图片缓存 / 空间 GC 落在窗口内** | `_r35_revoke_img_anchor.md:126-127` **实证**存在 `DELETE FROM ImgInfo2 WHERE msglocalid IN (SELECT …)` / `... WHERE id IN (SELECT …)` 整会话清理；撤回后 600s 内微信恰好做一次空间 GC | 该批删除被整批拦掉 ⇒ `ImgInfo2` 该清的行没清、`image2` 文件被 `VFG` 同时保住 ⇒ **存储只增不减**（`MediaDeleteLogic.kt:14-16`、`VoiceFileGuard.kt:67-68`）——⛔ 这是有实证的「破坏微信 GC」，不是「太简单」 | 撤一张图后**立刻在窗口内**触发微信「设置→通用→存储空间→清理缓存」，撤前/清后各取一次 `ImgInfo2` 行数快照（撤前按最新 N 行全量留档，修 `_r52_device_log_snapshot.md:173-174` 缺口）与 `image2` 目录文件数；两者都不降 ⇒ 过度拦截成立 |
| **乙-2 删除会话 / 清空聊天记录落在窗口内** | 撤回后用户手动删掉**另一个**会话的聊天记录，SQL 同 `_r35:126-127` 的 `IN (SELECT …)` | 无关会话的图片索引行被保住，用户「删了却还在」 | 撤 A 图 → 窗口内删除「**文件传输助手**」或「**测试群聊**」里一条普通图片消息 → 查该图对应 `ImgInfo2` 行是否仍存在；存在 ⇒ 误拦 |
| **乙-3 用户主动删除一张无关图片落在窗口内** | 撤回后 10 分钟内用户长按删掉会话里另一张普通图（微信会删其 `ImgInfo2` 行） | 无关图被复活 / 残留行 | 同乙-2，用另一张普通图作对照 |
| **乙-4 朋友圈 / 收藏等链路复用白名单表** | `[推断]` `wxfileindex2` 可能被其它业务共用（其键列本身 `[需真机]`：`_r51_media_delete_logic.md:89-93`） | 非撤回业务的删除被拦 | 窗口内触发一次朋友圈图片清理，看 `MTG blocked` 是否出现在非撤回图 delete 上 |
| **乙-5 窗口边界外沿** | 撤回后满 600s 同一毫秒的删除、时钟回拨（`isRevocationWindow:228`） | 「差 1ms」判据抖动 | JVM 单测 `delta == ttlMs` ⇒ false（`:222` 严格 `<`，已有用例） |
| **乙-6 重试删除风暴** | `_r51_media_table_guard_impl.md:243` 记防撤回侧实测 **5 秒 360 次**重试 | 窗口内 360 次全拦 ⇒ `events` 封顶 64 + 60s 节流虽兜住日志，但每次 `param.result` 都伪装；微信是否进入重试死循环 `[需真机]` | `dev.py q 'MTG'` 看窗口内 `blocked` 增速与 `HDIMG` 是否异常 |

**最便宜的一条共性判别实验**：撤一张图后的 600s 窗口内，对**一张与撤回无关的普通图片**做一次删除，然后查 `ImgInfo2` 该行是否仍在 + `dev.py q 'MTG'` 是否出现 `blocked`。若「无关删除也被拦」⇒ 乙过度拦截成立，**直接违反 `MediaDeleteLogic.kt:14-22` 写明的保守性红线**。

**乙也不是全无优点（⛔ 不许只写缺点）**：代码量最小（1~3 行）、零查库、零反查竞态、对 `id=?` / `IN` / `execSQL` / 内联字面量**所有形态**一视同仁全覆盖 —— 甲会漏的批量形态乙反而能兜住。这正是它值得留在表里的原因；代价是把「拦撤回删除」变成「拦窗口内一切删除」。

### 丙｜其它路线的失效场景（⛔ 同样逐条写）

- **丙① upstream 合并反查**：失效 = `auditImage` 本身 `row=0` ⇒ 合并进去也拿不到 id（与甲-1 同坑，且换口径的机会被绑死在 `auditImage` 的 `where` 上）；另外改的是诊断函数，读 `RRV imgAudit row=0` 日志的人会以为语义变了 `[推断]`。
- **丙② before 关联 `message` 表**：失效 = 热路径查库的性能/互锁（`AntiRevoke.kt:863`）；`message.imgPath` 仍需抽 md5 ⇒ 绕回甲；无 BYPASS 会被自家 hook 改写；`imgAudit row=0` 说明关联键对不上。**不推荐**。
- **丙′③ 到达即备份**：失效 = `HDIMG img=null` 说明组件拿不到路径，模块目录里的备份**喂不回 UI**；还原若写回微信库违反本轮红线；「已被删的行不可回滚」正是当前困局；代码量与回归面最大。**不推荐（越界）**。

### ⭐ 因果链闭合后的增量复核（甲 / 乙 失效场景 + team-lead 点名的 `[需真机]` 支）

> 依据：01:06 `HDIMG trace` 一手证据（T2「补充证据」小节）。⛔ 本节只做**评估**，不下「修复后必然能显示大图」的定论。

#### 甲（id 反查法）在闭合因果链下

- ✅ **修复面收敛（利好）**：唯一断点是**一条** `ImgInfo2 where=id=?` 单条删除；甲精确拦它 ⇒ 行保留 ⇒ `com.tencent.mm.modelimage.m` 应能取到该行 `bigImgPath`/`msgSvrId` ⇒ 预期 `h/i/j` 恢复、`img=SERVERID://…` ⇒ ⛔ **不必改渲染层 / 不必重建消息 / 不必额外写库**。这把对照表「是否需要动渲染层」列压到「否」。
- ⚠️ **甲-1~甲-6 失效场景不变**（反查口径、批量形态、竞态、行本就不存在、内联 `execSQL`），但 **甲-5 措辞已按 01:06 修订**：`img=null` 不再是「根因可能在别处」的等权假设，而是 DB 行缺失的下游后果。⛔ 注意「断点定位闭环」只证明了「删 ⇒ 打不开」，**没有**证明「不删 ⇒ 打得开」。
- ⭐ **新增甲-7（team-lead 点名，`[需真机]`）**：拦住 delete 后 `ImgInfo2` 行保留，微信**是否仍因别的字段/状态把 `h/i/j` 填 0**？⛔ **不作定论**——本设备从未观测「行保留后」的 `HDIMG trace`。候选来源（`[需真机]` 逐条排除，均非定论）：① 行内 `status` / `iscomplete` / `hashdthumb` 标量列被改；② `bigImgPath` / `msgSvrId` 为空；③ 撤回时微信对 `ImgInfo2` 行做了 **UPDATE 改写字段** 而非删除（本轮只拦 `delete`，⛔ 拦不到 UPDATE）；④ `t1` 构造还依赖第三张表 / 内存缓存（如 `WxFileIndex2`，其键列本身 `[需真机]`：`_r51_media_delete_logic.md:89-93`）。
- **判别实验（一次装包能跑完，全只读）**：修复装包 → 请 **verity** 在「**测试群聊**」发**新图**并撤回 → 撤后 `ImgInfo2` 快照确认目标 md5 行 `n: 1→1`（拦住了）→ 隔 3~5 分钟点开并读 `HDIMG trace` 的 `fld[h/i/j/o]` 与 `img=`。结果按下表三分（互斥）：

| 结果 | 判定 | 下一步 |
|---|---|---|
| ① `h/i/j≠0` + `img=SERVERID://…` + **点开看到完整大图** | ✅ 因果链闭合、甲成立、⛔ 未动渲染层 | 交付；补 T4 冷启 / TTL 边界 `[需真机]` |
| ② 行在（`n: 1→1`）但 `h/i/j` 仍全 0、`img=null` | ⛔ 命中**甲-7**：另有字段/状态 | 按 **D3** 停手（`CLAUDE.md:45-46`）：撤前/撤后各取一次行快照对比 `status`/`iscomplete`/`bigImgPath`/`msgSvrId`，`dev.py q 'MTG\|HDIMG trace'` 看撤回瞬间有没有 `ImgInfo2` 的 UPDATE；⛔ **不许继续加第三条判据**，也不许因此改渲染层 |
| ③ 行不在（`n: 1→0`） | ⛔ 拦截没生效（甲-1~甲-4） | 回头查 `MTG delete ... reason=`，按 T5 判定表处理 |

- ⛔ 若结果 ② 成立，修复面才需要越过判据，但**仍预期不必动渲染层**：更可能是补一次 UPDATE 拦截或字段保全（`AntiRevoke.kt:371` 已有 `updateWithOnConflict` 拦截链可复用），而非改 UI 组件 —— 这是 `[推断]`，须按 D3 先取证，⛔ 不作定论。

#### 乙（窗口内目标表全拦）在闭合因果链下

- ⛔ **因果链闭合不改变乙的核心缺陷**：乙-1~乙-6（微信 GC / 删会话 / 删无关图 / 朋友圈清理落在 600s 窗口内 ⇒ 整批误拦、存储只增不减）与「拦得对不对」无关 —— 乙拦的是**窗口内一切目标表 delete**。断点定位闭环只提高了「拦对了能修好」的把握，⛔ **不降低乙破坏微信 GC 的风险**（`MediaDeleteLogic.kt:14-22` 保守性红线，判别实验见乙表）。
- ⭐ **闭合链条反而强化「不必用乙」**：既然唯一断点是**一条** `id=?` 单条删除，精确到该条即可（甲）；乙多拦的那些 GC / 删会话 / 删无关图对修复**零贡献**，纯属副作用。⇒ 乙在「是否需要动渲染层」列也答「否」，但代价仍是过度拦截。
- ⭐ **新增乙-7（`[需真机]`，与甲-7 合流）**：若拦住的 delete 恰是微信**自己为重建 `ImgInfo2` 行 / 字段**而发的删除（例如撤回后「先删再插」、或 GC 前重整），全拦会阻断该重建 ⇒ 行虽在但字段仍不对、`h/i/j` 仍 0 ⇒ 与甲-7 残留支合流。判别实验同甲-7 结果 ②（`n: 1→1` 但 `h/i/j=0`）。

#### team-lead 点名问题的直接回答

**「拦住 delete 后 `ImgInfo2` 行保留，微信是否会因别的字段/状态仍把 `h/i/j` 填 0？」** ⇒ ⛔ **`[需真机]`、不作定论**。本设备只观测到「行被删 ⇒ `h/i/j=0`」，**从未观测**「行保留 ⇒ `h/i/j` 恢复」。上面甲-7 / 乙-7 与判别实验就是为这一支预留的；T5 判定表已把「行在但仍 `img=null`」单列一行、按 D3 停手复核，⛔ 不拿 `MTG blocked` 当成功。

### ⭐ 推荐 + 理由

**推荐：甲（id 反查法）—— 按 md5 口径反查、查不到保守放行并留痕；甲不覆盖的批量形态保持现有保守放行，⛔ 不与乙合并。**

1. **直接对准决定性证据**：真机是 `ImgInfo2 where=id=?` 数值主键，甲就是给这条补判据；乙是「绕开判据」，丙是「换入口」。
2. **守住保守性红线**（`MediaDeleteLogic.kt:14-22`）：甲只拦「命中已救消息 id 集合的单条删除」，微信 GC / 整会话 `IN (SELECT…)` / 无关图删除照常放行；乙在窗口内全拦会把这些一起拦掉（已给 6 条判别实验）。
3. **D4 控制优先**（`CLAUDE.md:48-49`）：甲仍在 `before` 阻止删除发生，不是事后补救；丙′③ 才是真·事前但代价越界。
4. **零额外写库**：反查走 `rawQuery` 只读（`AntiRevoke.kt:851`），不写微信库；`cachedRowIds` 是进程内 `@Volatile` 集合，与 `cachedTokens`（`:93`）同构、冷启整体清零。
5. **判据可纯函数化**（T3）：`idMatches` / `extractRowIds` 是纯逻辑，能进 `MediaDeleteLogic`、能做 JVM 单测 + 变异验证（`CLAUDE.md:63` 省 token 第 6 条）。
6. **代码量可控**：feature 层 `invalidate()` 加一次反查 + 一个 `@Volatile` 字段；纯函数层加 ~4 个函数 + 2 个分支；⛔ 不动 hook 注册（`_r49:38,40` 已证无需新挂）。
7. **D3 保险**（`CLAUDE.md:45-46`）：本次是判据**第一次**被证伪（R51 判据 → R52 决定性证据），不是「连塌两轮」，允许改判据；若甲在下一轮真机再被证伪（反查恒 `row=0` 且 md5 列形态也不符），必须**停手回头问「是不是问题选错了」**。⛔ **01:06 修订**：原本「转去验 `HDIMG img=null` 那一支（`_r52_device_log_snapshot.md:182-183` 待办）」已由补充证据**闭合**（`img=null` 正是判据放行的下游后果），不再是待验方向；新的回头方向是 **甲-7**（拦住后行保留、但 `h/i/j` 仍被填 0 ⇒ 另有字段/状态），按增量复核的判别实验 ② 处理，⛔ 而不是继续加第三条判据。
8. ⭐ **因果链闭合把修复面收敛到判据（01:06 新增理由）**：`message.imgPath` 在 + 磁盘文件在（连 `th_…_tmp.jpg` 临时文件都被 `VFG` 保住）、`HDIMG trace db` 三张老图都读出完整 `imgPath` ⇒ 唯一断点就是 ㊸ 那条 `ImgInfo2 where=id=?` 的判据放行。⇒ 拦住它即预期闭合，**⛔ 不必改渲染层 / 不必重建消息 / 不必额外写库**；甲精确拦这一条，乙把 600s 内所有目标表 delete 一起拦（对修复零贡献的副作用，且直接违反保守性红线）。

⛔ **推荐不是无条件**，**四条**前提全部 `[需真机]`：① `origImgMD5`/`thumbImgPath` 列值确实含保护池 md5；② 反查耗时与跨线程竞态可接受；③ 微信删除确实用 `id=?` 数值主键而非批量/内联形态；④ ⭐ **拦住 delete 后 `ImgInfo2` 行保留，微信仍不把 `h/i/j` 填 0**（甲-7 / 乙-7 残留支，本设备从未观测）。任一不成立 ⇒ 按 D3 回头，不硬改。

[DONE] T2
[DONE] T2-rev（01:06 证据 → 新增「是否需要动渲染层」列 / 甲-5 措辞修订 / 甲-7·乙-7 `[需真机]` 支 + 判别实验 / 推荐第 8 条 / 四条前提）

## T3 判据纯函数化 + 单测契约

**纪律**：修复后的判据必须放进**零 Android 依赖纯函数类**（范式 `SnsTagLogic.kt` / `MediaDeleteLogic.kt:1-34` KDoc：不接线、不读 `Kv`、不改 `param.result`、不写微信库、不生成/拼接任何 SQL，落地即零行为变化）。⛔ **反查 DB 的动作不能进纯函数类**（有 Android/DB 依赖），只能留在 `MediaTableGuard.invalidate()`；纯函数只消费「反查结果快照 `cachedRowIds`」。单测范式 `app/src/test/java/com/lx/wxgroup/data/MediaDeleteLogicTest.kt`（现有 46 例 + 变异验证，见 `_r51_media_delete_logic.md:125-210`）。

### 新增 / 修改的函数签名契约

| # | 函数 | 签名 | 输入参数含义 | 返回值语义 |
|---|---|---|---|---|
| 1 新增 | `MediaDeleteLogic.normalizeId` | `fun normalizeId(v: Any?): String?` | `v` = `whereArgs` 单个元素，可能是 `Long`/`Int`/`String`/`null`/其它 | 归一成**十进制数字串**（去首尾空白、去前导 `+`、拒绝负数/小数/空/非数字）⇒ 返回该串；任何不确定返回 `null`。⛔ 不做 `toLong()`（防溢出/异常），只做字符串归一 |
| 2 新增 | `MediaDeleteLogic.extractRowIds` | `fun extractRowIds(table: String?, whereClause: String?, whereArgs: Array<out Any?>?): Set<String>` | 三参与 `extractTokens` 同形 | 仅当「白名单表 + `whereClause` 为单列精确 `=` + 列 ∈ `NUMERIC_KEY_COLUMNS`」时，返回候选行 id 集合（来自 `whereArgs` 归一 **与** `whereClause` 内联数值右值双通道）；否则空集。⛔ 不解析 `IN`/`LIKE`/比较符/多条件，一律空集（保守） |
| 3 新增 | `MediaDeleteLogic.isNumericKeyDelete` | `fun isNumericKeyDelete(table: String?, whereClause: String?): Boolean` | 同上 | 是否「数值主键单条 `=` 删除」形态（决定走 id 通道还是 token 通道）。`whereClause==null` / 批量 / 非数值键列 ⇒ false |
| 4 新增 ⭐ | `MediaDeleteLogic.idMatches` | `fun idMatches(candidateIds: Set<String>, protectedRowIds: Set<String>): Boolean` | `candidateIds` = `extractRowIds(...)` 结果；`protectedRowIds` = `invalidate()` 反查得到的 `cachedRowIds` 快照（已按 `normalizeId` 同口径归一） | 是否有交集 ⇒ true。⛔ 任一为空 ⇒ false（不确定即放行） |
| 5 修改 | `MediaDeleteLogic.classifyDelete` | 追加可选参数 `protectedRowIds: Set<String> = emptySet()`（排在 `protectedTokens` 之后） | — | 在现有 `:181-182` `tokens.isEmpty() ⇒ UNKNOWN` **之前**插入一条分支：若 `isNumericKeyDelete(...)` 且 `idMatches(extractRowIds(...), protectedRowIds)` ⇒ 直接 `return BLOCKABLE`；否则**原逻辑逐字不变** |
| 6 修改 ⭐ | `MediaDeleteLogic.shouldBlock` | 追加可选参数 `protectedRowIds: Set<String> = emptySet()` | — | ① 透传给 `classifyDelete`；② ⛔ `:251` 必须改为 `if (protectedTokens.isEmpty() && protectedRowIds.isEmpty()) return false`（否则 id 通道被空 `protectedTokens` 短路，见 C7 / M2a）；③ ⛔ `:252` 窗口闸门位置与语义不变；④ ⭐ **必须在 `:253` 判为 `BLOCKABLE` 之后、`:256-259` 的 token 复检之前加一条 id 出口**：`if (idMatches(extractRowIds(...), protectedRowIds)) return true`，⛔ 否则 `id=?` 的 `extractTokens` 恒空集 ⇒ `:257 return false` ⇒ **`shouldBlock` 恒 false、修复完全无效**。原 token 通道 `:256-259` 逐字保留给 md5 路径。⭐ 本条为 **lane A（`r52-fix-a`）实现反馈驱动的设计侧遗漏补齐**（⛔ 非 lane A 擅改契约）：只改 `classifyDelete` 不改 `shouldBlock` 出口是最隐蔽的漏改，见 C12 / M2b |
| 7 新增 | `NUMERIC_KEY_COLUMNS` | `private val NUMERIC_KEY_COLUMNS = setOf("id", "msglocalid", "msgsvrid", "msgid")` | — | ⛔ 刻意**不含** `bigimgpath`/`thumbimgpath` 等路径列（它们走 md5 token 通道，不能混入数值比对） |

**契约纪律**：
- 所有新增函数**纯**：无 `android.*`、无 `Kv`、无 `LogX`、无 `XposedHelpers`、无 DB、无 SQL 生成（只解析）。
- 「带默认值的新参数」不是向后兼容 hack，而是让 R51 已落盘的 46 例单测**零改动继续跑**（`_r51_media_delete_logic.md:179`），新通道由新增用例覆盖。
- `protectedRowIds` 与 `protectedTokens` 是**两条独立通道**：token 通道（26/32 位 hex）逐字原样；id 通道只服务数值主键。⛔ 禁止把 id 集合塞进 `extractTokens`（会破坏 `MediaDeleteLogic.kt:24-27`「两套 token 长度分派」这条已证事实）。
- `invalidate()` 侧反查结果的发布：`@Volatile cachedRowIds: Set<String>`，与 `cachedTokens` 同一 `gen` 重建；**归一口径必须与 `normalizeId` 完全一致**，否则 `idMatches` 恒 false —— 又是一次静默失效（`VoiceFileGuard.kt:139-142` 记过此型）。
- ⭐ **id 出口是硬契约的一部分**（lane A 反馈，设计侧原遗漏）：`shouldBlock` 的 `:256-259` token 复检对数值主键恒空集，必须在 `classifyDelete==BLOCKABLE` 后、token 复检前直接返回 true；⛔ 只改 `classifyDelete` 不改 `shouldBlock` 出口 ⇒ 修复无效。这是与「`:251` 双空集闸门」**并列的第二个漏改面**，由 C12 / M2b 专抓。
- ⭐ **B7 的形态判定边界**（lane A 反馈）：`isNumericKeyDelete(table, whereClause)` 按硬契约**无 `whereArgs` 入参**，只做纯形状判定 ⇒ `id=?` 恒为 `true`；「多参数」的保守性**不由它承担**，而由 `extractRowIds` 的 `size>1 ⇒ ∅` + `classifyDelete:170` 的 `BULK_OR_UNSAFE` 双保险承担。设计文档原写 `isNumericKeyDelete=false` 与自身签名矛盾，已订正（见 B7）。

### 单测用例清单（`MediaDeleteLogicTest.kt` 追加；⛔ 每条注明抓哪个看似合理的错误实现）

边界按题目点名齐全：`id=?` 的 args 为 `Long` / `String` / `null`、多参数、非撤回窗口、非目标表、`IN (…)`、`LIKE`、`whereClause == null`。

**A 组 `normalizeId`（类型边界）**

| 用例 | 输入 | 期望 | 抓的错误实现 |
|---|---|---|---|
| A1 | `875556L`（`Long`） | `"875556"` | 只认 `String` 不认数值类型 ⇒ 真机 `Object[]` 全漏（决定性证据那条） |
| A2 | `875556`（`Int`） | `"875556"` | 同上 |
| A3 | `"875556"`（`String`） | `"875556"` | 只认数值类型 |
| A4 | `" 875556 "` | `"875556"` | 不 `trim()` |
| A5 | `"+875556"` | `"875556"` | 前导 `+` 未归一 ⇒ 与反查结果对不上 |
| A6 | `null` | `null` | `null` 触发 NPE（`extractTokens:207` 已有 `?.toString()` 同款教训） |
| A7 | `"-1"` / `"875556.0"` / `"875556abc"` / `""` / `"   "` | `null` | 非法值被放行 ⇒ 误命中 |
| A8 | `Long.MAX_VALUE` 的字符串 | 原串原样返回 | 用 `toLong()` 溢出抛异常 |

**B 组 `extractRowIds` / `isNumericKeyDelete`（形状边界）**

| 用例 | 输入（`table, where, args`） | 期望 | 抓的错误实现 |
|---|---|---|---|
| B1 ⭐ | `ImgInfo2, "id=?", [875556L]` | `{"875556"}` | **决定性证据那条**：`id=?` args=`Long` 必须抽得出 id |
| B2 | `ImgInfo2, "id=?", ["875556"]` | `{"875556"}` | args=`String` 同结果 |
| B3 | `ImgInfo2, "id=?", [null]` | `∅` | **args = `null`** 静默跳过（题目点名） |
| B4 | `ImgInfo2, "id=?", null` | `∅` | `whereArgs == null` |
| B5 | `ImgInfo2, "id=875556", null`（内联字面量） | `{"875556"}` | ⛔ 只认占位符不认内联右值 ⇒ `execSQL(sql)` 无 bindArgs 形态漏（T2 甲-6） |
| B6 | `ImgInfo2, "msglocalid=?", [19640720L]` | `{"19640720"}` | 数值主键列覆盖不全（T2 甲-2） |
| B7 ⭐ | `ImgInfo2, "id=?", [875556L, 875557L]`（**多参数**） | `extractRowIds=∅`；`isNumericKeyDelete("ImgInfo2","id=?") = true`（⛔ **lane A 订正**：该函数按硬契约只有 `(table, whereClause)` 两参，**感知不到 `whereArgs`**，只做纯形状判定 ⇒ `id=?` 形状为 true）；`classifyDelete=BULK_OR_UNSAFE`、`shouldBlock=false` | ⛔ 原写 `isNumericKeyDelete=false` 与自身契约签名矛盾（**设计侧措辞错误，非 lane A 改契约**）。多参数保守性由 **`extractRowIds` 的 `size>1 ⇒ ∅` + `classifyDelete:170` 的 `BULK_OR_UNSAFE`** 双保险承担，⛔ 不许「取第一个」；结论仍是「一律放行」 |
| B8 | `ImgInfo2, "id IN (?,?)", [...]` | `∅` | **`IN (…)`** 必须 `BULK_OR_UNSAFE`/空集，⛔ 不许放宽（保守性红线） |
| B9 | `ImgInfo2, "thumbImgPath LIKE ?", [...]` | `∅` | **`LIKE`** 必须放行 |
| B10 | `ImgInfo2, null, null`（**`whereClause == null`**） | `∅` | 删全表 ⇒ 绝对不能进 id 通道 |
| B11 | `ImgInfo2, "", null` / `"   "` | `∅` | 空 where |
| B12 | `message, "id=?", [875556L]`（**非目标表**） | `∅` | 非白名单表越权识别 |
| B13 | `voiceinfo, "id=?", [875556L]` | `∅` | `voiceinfo` 交给既有 `VoiceGuard`，不重复拦（`MediaDeleteLogic.kt:9-10,63`） |
| B14 | `ImgInfo2, "status=?", [1L]` | `∅` | `status` 是标量列、不在 `NUMERIC_KEY_COLUMNS`（`:75` 同款） |
| B15（`b15PathColumnGoesToTokenChannelOnly`） | `ImgInfo2, "bigImgPath=?", [MD5_A]` | `extractRowIds=∅`（走 token 通道）；**`assertFalse(MediaDeleteLogic.isNumericKeyDelete("ImgInfo2","bigImgPath=?"))`**（`bigImgPath` 不属数值键列） | 路径列不能混入数值通道。⭐ **lane A 精确措辞**：M5 变红**不靠** `extractRowIds`（MD5_A 非数字，正确/错误实现同为 `∅` ⇒ 该腿全绿），而靠这条 `assertFalse` —— 把 `bigimgpath` 扩进 `NUMERIC_KEY_COLUMNS` 后 `isNumericKeyDelete` 返回值 **false→true**、`assertFalse` 断言**由通过翻成失败** ⇒ RED，实测 M5 `Failures=2` 含 `b15`。⛔ 真正抓「`extractRowIds` 数值串味」的是 **B15b**；⛔ 单用 md5 右值配 C8 杀不死 M5（等价变异，见 M5 行） |
| **B15b** ⭐（`b15bPathColumnWithNumericRightValueStillRejected`） | `ImgInfo2, "bigImgPath=?", [875556L]`（**路径列 + 数值右值**）且 `protectedRowIds=setOf("875556")` | 正确实现 `extractRowIds=∅` / `classifyDelete=UNKNOWN` / `shouldBlock=false`；**M5 变异后**变 `{"875556"}` / `BLOCKABLE` / `true` ⇒ RED | ⭐ **lane A 补**：专杀变异 M5 里「`extractRowIds` 数值串味」那条腿。原 M5 配 B15/C8 用 **md5 右值（非数字）** ⇒ `extractRowIds` 恒空集 ⇒ 错误实现与正确实现同为绿 ⇒ **等价变异 / 假验证**；必须换成「数值右值 + 命中保护集」，才能让「路径列被扩进 `NUMERIC_KEY_COLUMNS`」的错误实现真的 `extractRowIds` 非空、被本用例 RED 抓住 |
| B16 | `ImgInfo2, "id>?"` / `"id<>?"` / `"id=? OR id=?"` / `"id=?;..."` | `∅` | 比较符 / 多条件 / 多语句一律放行 |
| B17 | `ImgInfo2, "[id]=?"` / ``"`id`=?"`` | `{"875556"}` | 须复用 `SINGLE_EQ`（`:110-112`）的引号处理，⛔ 不能另写正则 |

**C 组 `idMatches` / `classifyDelete` / `shouldBlock`（组合边界）**

| 用例 | 输入 | 期望 | 抓的错误实现 |
|---|---|---|---|
| C1 ⭐ | `cls("ImgInfo2","id=?",[875556L], protectedRowIds=setOf("875556"))` | `BLOCKABLE` | **决定性证据那条修复后必须能拦** |
| C2 | C1 但 `protectedRowIds=emptySet()` | `UNKNOWN` | 反查没结果 ⇒ 保守放行，⛔ 不静默拦 |
| C3 | C1 但 `protectedRowIds=setOf("875557")`（不命中） | `UNKNOWN` | 「形状对就拦」，不校验 id 命中 |
| C4 | C1 但 `nowMs-issued >= 600000`（**非撤回窗口**） | `shouldBlock=false` | 新增 id 通道不得旁路 `:252` 窗口闸门 |
| C5 | C1 但 `table="message"`（**非目标表**） | `false` | `isTargetTable` 闸门不变 |
| C6 | C1 但 `ttlMs=0` / 时钟回拨 | `false` | `isRevocationWindow` 既有边界不被破坏 |
| C7 ⭐ | `protectedTokens=emptySet()` 但 `protectedRowIds` 非空 | `BLOCKABLE` | ⭐ 抓「只改 `classifyDelete`、**忘记改 `:251` 空保护集闸门`」这个最可能的漏改（T3 契约第 6 条） |
| C8 | 原有 token 通道用例（`msglocalid=?` + `MD5_A` + `protectedTokens=setOf(MD5_A)`） | 仍 `BLOCKABLE` | ⛔ **回归**：新增 id 通道不得破坏 R51 既有 46 例 |
| C9 | C1 但 `protectedRowIds=setOf("0875556")`（前导 0） | `false` | 归一口径不一致 ⇒ 静默漏（契约纪律最后一条） |
| C10 | C1 但 `protectedRowIds` 含重复/大写无关（数字串无大小写） | `BLOCKABLE` | 误加多余变换 |
| C11 | `verdict(seen=1, matched=1)` | `REAL_EVENT`（不变） | 回归：账本三态不受影响（`MediaDeleteLogic.kt:267-270`） |
| **C12** ⭐ | C1（`id=?` args=`Long`、`protectedRowIds` 命中）的 `shouldBlock`，但**漏加契约第 6 条的 id 出口**（`classifyDelete` 已返回 `BLOCKABLE`，仍走 `:256-259` token 复检） | 正确实现 `shouldBlock=true`；错误实现 `false` | ⭐ **lane A 反馈**：数值主键的 `extractTokens` 恒空集 ⇒ `:257 return false` ⇒ 只改 `classifyDelete` 不改 `shouldBlock` 出口，修复**完全无效**；且 `classifyDelete` 单测全绿、极难发现。与 C7（`:251` 空集闸门）**并列的第二个「shouldBlock 漏改」面**，专供变异 M2b 杀死。⛔ **实现侧语义覆盖（lane A 精确措辞）**：**无同名 `c12` 方法**，该语义由 `c1IdEqualsHitIsBlockable` / `c1bInlineLiteralHitIsBlockableEndToEnd` / `c7EmptyTokensButNonEmptyRowIdsStillBlocks` 三条的 `assertTrue(blockId(...))` 覆盖 —— M2b（删 id 出口）实测 **`Failures=3` 恰好是这三条**，即 C12 的变异实证；**83 例总数不变**，设计侧无需保留独立同名用例 |

⛔ **判据纪律**：以上全部是**纯函数 JVM 单测**，⛔ 不拿真机 `seen/matched/blocked/skipped/row=0` 当结论（`_r52_device_log_snapshot.md:176-178`）；单测只证明「判据代码正确」，功能是否成立由 T5 真机验收定。

### 变异验证设计（⛔ ≥4 组**有语义**变异，逐组说明「为什么有语义」）

沿用 `_r51_media_delete_logic.md:181-210,339-345` 的范式：Python/Kotlin 复刻判据 → 注入 mutant → 跑**同一份用例表**，每个 mutant 必须至少 1 条 RED。⛔ 等价变异（换变量名 / 加空行 / 改注释 / 调整 `when` 分支书写顺序但结果不变）**不算**。

| # | 注入点 | 变异内容 | **为什么有语义**（⛔ 不是等价变异） | 预期 / **实测** RED 用例（lane A 直编实测） |
|---|---|---|---|---|
| **M1** | `classifyDelete` 新增分支 | 把 `idMatches(...)` 返回值**取反**（`!idMatches`）或整支删掉 | 语义反转：本该拦的 `id=?` 放行、不该拦的反被拦。它直接改变「要不要拦」这个唯一出口，不是代码形态变化 | C1、C3；**实测 10 条 RED** |
| **M2** ⭐（**1 组含 2 个子变异体**：`M2a` 空集闸门、`M2b` id 出口；脚本键 `M2`=设计侧 M2a、`M2b`=设计侧 M2b，⛔ **不是第 8 组**） | `shouldBlock:251`（M2a）/ `:253~:256`（M2b） | **M2a**：把 `protectedTokens.isEmpty() && protectedRowIds.isEmpty()` 改回 `protectedTokens.isEmpty()`（漏改空集闸门）；**M2b**：删掉 `:253` 判 `BLOCKABLE` 后、`:256-259` token 复检**之前**的 id 出口 | 两个**互不重叠**的漏改面：M2a 让 id 通道被空 `protectedTokens` **静默短路**；M2b 让数值主键的 `extractTokens` 空集在 `:257 return false` ⇒ **`shouldBlock` 恒 false、修复完全无效**，且 `classifyDelete` 单测全绿、最难发现（lane A 实现反馈、设计侧原遗漏）。后果都是「单测看似绿、真机恒放行」的假验证 | 两个子变异体**各实测 3 条 RED**（`Failures=3`）：`c7` / `c1` / `c1b`。⛔ 原写「C7 唯一覆盖」偏窄 —— `c1`/`c1b` 同样用空 `protectedTokens` + 非空 `protectedRowIds`，与 `c7` 交叉变红、多条覆盖；M2b 的这三条 `assertTrue(blockId(..))` 即 **C12 语义**，实现侧**未新增同名 `c12` 方法**（83 例不变），**M2b 即 C12 的变异实证** |
| **M3** | `normalizeId` | 删掉 `trim()`/前导 `+` 归一，或把 `a.toString()` 改成 `a as? String`（只接受 `String`） | 语义丢失：真机 `Object[]` 里是 `Long`（决定性证据），只认 `String` ⇒ 全部抽不出 id ⇒ **修复等于没修**。这是决定性证据的反面，不是等价改写 | A1、A2、A5、B1、C1；**实测 9 条 RED** |
| **M4** | `extractRowIds` / `isNumericKeyDelete` | 放开 `IN (?,?)` / `whereArgs.size>1` / `LIKE` 的保守闸门（挪走 `UNSAFE` 检查、取第一个参数） | 语义放宽：直接**破坏保守性红线**（`MediaDeleteLogic.kt:14-22`），批量清理 / 整会话删除被拦 ⇒ 微信 GC 失效。这是「改对了 id 却改坏了边界」的典型 | 预期 B7/B8/B9/B16；**实测 2 条 RED**：`b16` / `b16b`。⛔ B7 未红（删 `size>1` 闸门后仍有「占位符数量 ↔ args 数量对齐」兜底）、B8/B9 未红（`SINGLE_EQ` 无 `=`）—— **多层保守兜底属预期，非用例缺口** |
| **M5** ⭐ | `NUMERIC_KEY_COLUMNS` | 把 `bigimgpath`/`thumbimgpath` 路径列扩进集合 | 语义串味：路径列被塞进数值通道 ⇒ **数值右值**也能被当 id 命中 ⇒ 误拦 / 污染既有 token 通道（`:24-27` 两套口径）。⛔ 原配 B15/C8 用 md5 右值 ⇒ `extractRowIds` 恒空集 ⇒ 错误实现与正确实现同为绿 ⇒ **等价变异 / 假验证，杀不死** | 实测 **`Failures=2`**：`b15`（`assertFalse(isNumericKeyDelete(...))` 形状断言，抓**形状串味**）+ `b15b`（`extractRowIds=∅/UNKNOWN/false` 翻成 `{"875556"}/BLOCKABLE/true`，抓**数值串味**），两条腿**互不重叠**。⛔ 原写含 C8 已删 —— C8（token 通道回归）实测**不** RED；⛔ `extractRowIds` 在 md5 右值下正确/错误同为 `∅`，是等价变异、杀不死 M5 |
| **M6** | `shouldBlock` 窗口闸门 `:252` | 把 `isRevocationWindow(...)` 短路成 `true`（新增 id 通道时「顺手」旁路窗口） | 语义越权：窗口外的 `id=?` 删除也被拦 ⇒ 撤后 10 分钟之外微信正常 GC 被堵。它改变时间边界，不是等价变异 | C4、C6；**实测 3 条 RED** |
| **M7** | `extractRowIds` | 只查 `whereArgs`、漏掉 `whereClause` 内联右值（或反之） | 语义缺口：`execSQL(String sql)` 无 bindArgs 时 `args=null`，内联 `id=875556` 抽不出 ⇒ 漏一条真实通道（T2 甲-6） | B5（或反向则 B1）；**实测 2 条 RED** |

⭐ **双口径定义（与 `r52-fix-a` 脚本 `python _cache/r52_mutate.py report` 输出逐字对齐，⛔ 本轮最终口径）**：设计侧**组**与脚本侧**变异体**是两个不同计数，不许混为一谈，也 ⛔ **不许写「第 8 组」**：

| 口径 | 计数单位 | 全集 | 实测结果 | 脚本输出键 |
|---|---|---|---|---|
| **设计侧组口径** | 「组」：M1 / M2 / M3 / M4 / M5 / M6 / M7（**M2 组含 M2a·M2b 两个子变异体，仍算 1 组**） | **7 组** | `7/7` 全 RED | `设计侧 7 组口径: 7/7` |
| **脚本侧变异体口径** | 「变异体」：M1 / M2a(=脚本键 `M2`) / M2b(=脚本键 `M2b`) / M3 / M4 / M5 / M6 / M7 | **8 个变异体** | `8/8` 全 RED | `变异体至少 1 条 RED: 8/8` |

> 脚本键映射：`r52_mutate.py` 里键 `M2` = 设计侧 **M2a**（空集闸门）、键 `M2b` = 设计侧 **M2b**（id 出口）。M2b 是 **M2 组内的第二个子变异体**，不是独立第 8 组；故 `7/7`（组）与 `8/8`（变异体）**同时成立、不矛盾**。

**验收口径**：每个变异体至少 1 条用例变 RED；任何变异体全绿 ⇒ 用例矩阵有缺口，**必须补用例后重跑**（等价变异自检对照 `_r51_media_delete_logic.md:339-345`、`_r49_revokeimg_reference.md:343-350`）。7 组（含 M2 的两个子变异）覆盖「出口反转 / 空集闸门漏改 / **id 出口漏改** / 类型归一 / 保守边界 / **数值键列串味** / 窗口旁路 / 双通道缺口」等互不重叠的语义面。⛔ **项目纪律：单测必须做变异验证，否则视为假验证；等价变异（如原 M5 配 B15/C8）不算。**

⭐ **lane A（`r52-fix-a`）实现侧实测回报（最终态，已并入本节口径）**：直编真实源码 + `JUnitCore` 全量重跑，**设计侧 7 组 `7/7` + 8 个变异体 `8/8` 全部实测 RED**，**83 tests 全绿**，源码 hash `b1893060affd3b34` **未变**（本 lane 只读、⛔ 未碰源码）。各变异体 RED 条数：M1=10、M2a(脚本键 `M2`)=3、M2b=3、M3=9、M4=2、M5=2、M6=3、M7=2；其中 **M2b `Failures=3` = `c1IdEqualsHitIsBlockable` / `c1bInlineLiteralHitIsBlockableEndToEnd` / `c7EmptyTokensButNonEmptyRowIdsStillBlocks`**（即 C12 语义，实现侧无同名 `c12` 方法）、**M5 `Failures=2` = `b15` + `b15b`**（形状串味 / 数值串味两条腿）、M4=`b16`/`b16b`。证据：`_cache/r52_mut_M*.log`（8 份）+ `.patch`（8 份），`python _cache/r52_mutate.py report` 一键双口径汇总。B7 / B15b / C12 三条用例与契约第 6 条的 id 出口均按硬契约签名实现，⛔ **非擅自改契约**。**设计文档与实现口径以本节为准。**

[DONE] T3
[DONE] T3-rev（`r52-fix-a` 实现反馈订正：契约 6 补 `shouldBlock` 的 id 出口 / 契约纪律补 2 条 / B7 与自身签名矛盾订正 / 补 B15b·C12 / M2 组内拆 M2a·M2b 两个子变异体 / M5 换 RED 为 B15b / 验收口径并入实测；⛔ 保持 M1~M7 七组编号不变，与实现侧口径一致）
[DONE] T3-rev2（`r52-fix-a` 双口径最终确认对齐：⛔ 表格回退为设计侧 7 组（M2 合并为 1 组、组内保留 M2a·M2b 两个子变异体、脚本键 `M2`=M2a / `M2b`=M2b，⛔ 不写「第 8 组」）；验收口径改双口径 `7/7 组 + 8/8 变异体` 并加口径对照表；实测回报补 M2b `Failures=3`(c7/c1/c1b) / 逐变异体 RED 条数 / 8 份 log+patch 证据 / `r52_mutate.py report` / 源码 hash `b1893060affd3b34` 未变；B15 补 `isNumericKeyDelete` 形状断言与实测 RED、C12 补实现侧语义覆盖（无同名 `c12` 方法、由 c1/c1b/c7 覆盖、83 例不变）；⛔ 本 lane 仍只读、未碰源码/设备/gradle）
[DONE] T3-rev3（`r52-fix-a` 精确措辞对齐：B15 补 `assertFalse(MediaDeleteLogic.isNumericKeyDelete("ImgInfo2","bigImgPath=?"))` 断言调用与方法名 `b15PathColumnGoesToTokenChannelOnly`、厘清「M5 变红靠形状断言翻失败、非 `extractRowIds`（md5 非数字同为 ∅ 全绿）」；B15b 补方法名 `b15bPathColumnWithNumericRightValueStillRejected` 与变异后 `{"875556"}/BLOCKABLE/true`；C12 补 `c1IdEqualsHitIsBlockable`/`c1bInlineLiteralHitIsBlockableEndToEnd`/`c7EmptyTokensButNonEmptyRowIdsStillBlocks` 三条全名（⛔ 无同名 `c12` 方法、83 例不变）；M5 行与实测回报补 `Failures=2=b15+b15b` 两条腿互不重叠、M2b `Failures=3` 三条全名；⛔ 口径结论不变：`7/7 组 + 8/8 变异体`、83 tests 全绿、源码 hash `b1893060affd3b34` 未变，本 lane 仍只读）

## T4 三档边界表（进入 / 返回 / 复位逐条）

> 语境：以下描述「T2 推荐路线（甲）接线后」的预期状态，**全部 `[需真机]` 才能定**；本轮零接线 ⇒ 现网行为完全不变。好友一律 **verity** / **测试群聊** / **文件传输助手**（`CLAUDE.md:12`）。依据 `_r51_media_table_guard_impl.md:220-247`、`_r51_media_delete_logic.md:351-382`、`_r49_revokeimg_reference.md:265-295`。
> ⛔ 硬要求：**三档齐全**；② 档里 **TTL 时间维度** 与 **冷启进程维度** 各自单列、不与「切页回来」合并；③ 复位**逐条分列**、⛔ 不许写「同返回」。

### ① 进入（冷启 install / 首次撤回 record()→invalidate() / kvset 开关 / 保护池 token 登记）

| 触发 | 发生什么（带 `文件:行号`） | 用户可见 | 状态残留 |
|---|---|---|---|
| **冷启 `install()`** | `MediaTableGuard.install`（`:116-146`）：`findClass` wcdb（`:118`）、`refresh()` 读开关（`:124`）、`resolvePool()` 反射 `VoiceFileGuard.protectedMedia`（`:126,336-347`）、`hookAllMethods(cls,"delete"/"execSQL",…)`（`:130-135`）、打 `MTG installed hooks=delete,execSQL enabled=… pool=ok switch=antirevoke.media_delete`（`:141-145`） | 无（`installed` 日志供 `dev.py q 'MTG'` 验挂，`CLAUDE.md:75-76`） | hook 全挂、`mediaPoolRef`、`enabled` 内存值；`cachedTokens` / ⭐`cachedRowIds` **空**、`gen=0`、`lastIssuedMs=0`、账本 4 计数全 0 |
| **首次撤回 `record()→invalidate()`** | **verity** 撤图 ⇒ `AntiRevoke` 拦 `updateWithOnConflict`（`AntiRevoke.kt:371`）⇒ `message` 行保住；`RevokeRescue.record()` 图片分支（`:115-129`）依次 `VoiceFileGuard.protectMedia(orig.imgPath)`（`:122`）、`auditImage`（`:126`）、**`MediaTableGuard.invalidate()`（`:128`）**：`gen++`、`lastIssuedMs=now`（`:160-161`）、`cachedTokens=snapshotLiveTokens()`（`:162`）、⭐ **新增 `cachedRowIds = 按 md5 反查 ImgInfo2 得到的 id 集合**、`refresh()`（`:163`）；随后微信 `delete("ImgInfo2","id=?",…)` 到达 `[D]#worker` ⇒ `idMatches` 命中 ⇒ `param.result=FAKE_ROWS`（`:277`）跳过删除 | 撤回到删除约 10~25ms（`01:03:59.051` → `.080`）内无 UI | 池 token、`cachedTokens`、`cachedRowIds`、`gen`/`lastIssuedMs`、账本 `seen/matched/blocked`、`MTG installed`/`MTG blocked` 日志；微信 `message` 行 + `ImgInfo2` 行 + `image2` 缩略图文件都在；⛔ 图片侧**无备份文件**（`RevokeRescue.kt:121-127`） |
| **`kvset antirevoke.media_delete` 开关** | `EntryReceiver` 写**模块自有库**（`_r51_media_table_guard_impl.md:237`）；⛔ `refresh()` 只在 `install()` / `invalidate()` 调（`MediaTableGuard.kt:148-151`），`EntryReceiver` 不含本类主动 refresh ⇒ `[推断]` 单独 kvset **不改内存 `enabled`**，须等下一次 `record()→invalidate()` 或冷启 | 无 | 开关落库；内存 `enabled` 可能仍旧值（真机 `00:00:31 kvset=true`、`00:02:49` 冷启后才 `installed enabled=true`，`_r52_revokeimg_rootcause.md:36-37`） |
| **保护池 token 登记** | `VoiceFileGuard.protectMedia(orig.imgPath)`（`:122-159`）：`MediaGuardLogic.hexToken()` 取 32 位 md5（`:129`）、`protectedMedia[t]=now+600000`（`:151`）、`hasMedia=true`（`:155`）、打 `VFG protectMedia img=… ttl=600000ms pool=1` | 无 | `protectedMedia` 1 条（10min TTL）、`hasMedia=true` |

### ② 返回（撤后几分钟点开 / 切页回来 / 下拉刷新）

⛔ **TTL 时间维度与冷启进程维度各自单列，不与「切页回来」合并**（任务硬要求；`_r49_revokeimg_reference.md:279,287`）。

| 档 | 触发 | 发生什么 | 用户可见 | 状态残留 |
|---|---|---|---|---|
| **② 纯 UI 返回** | 撤后几分钟点开大图 / 切页回来 / 下拉刷新（**无新撤回 UPDATE**） | 只读 `message` + `ImgInfo2`；`cachedTokens` / ⭐`cachedRowIds` 不变、`isRevocationWindow` 仍 true；若此刻微信重试 `delete("ImgInfo2","id=?",…)` ⇒ `idMatches` 命中 ⇒ `param.result=FAKE_ROWS`（`:277`）跳过删除 | ⭐ **主判据：点开能否看到完整大图** | `auditImage` 的 Cursor 每次 `close()`（`RevokeRescue.kt:286-288`）不残留游标；账本不清零 |
| **②-a TTL（时间维度，单列）** | 撤回后满 **10 分钟**（保护池 `PROTECT_MEDIA_MS`，`VoiceFileGuard.kt:112`）/ **30 分钟**（imgAnchor `IMG_ANCHOR_TTL_MS`，`AntiRevoke.kt:470`） | 保护池到期 ⇒ `MediaGuardLogic.anyAlive`（`MediaGuardLogic.kt:110-113`）把 `hasMedia` 复位 false（`VoiceFileGuard.kt:301`）；`WINDOW_MS`（`MediaTableGuard.kt:70`）同步到期 ⇒ `isRevocationWindow` false ⇒ `shouldBlock` 恒 false ⇒ **不再拦**；imgAnchor 另有 256 上限淘汰，**不同载体、不同时长** | TTL 后若微信重试删除 ⇒ 行被删、图打不开 | ⛔ 池从「保护态」变「裸奔态」；**账本仍在、hook 仍在、开关仍开、`cachedRowIds` 仍在（只是窗口判 false）**；⛔ 不与「切页回来」合并 |
| **②-b 冷启（进程维度，单列）** | 微信进程被重建 | `HookEntry` 重新 `install()` ⇒ `MTG installed` 重打、`mediaPoolRef` 重反射；`cachedTokens` / ⭐`cachedRowIds` / `gen` / `lastIssuedMs` / **账本 4 计数 + `events`(≤64) + `logAt`(≤64) 全部清零**；`verdict` 回 `NO_EVENT`（`MediaDeleteLogic.kt:267-270`） | **冷启后能否仍打开 `[需真机]`**（池空，若冷启后微信重新发起删除无人拦） | ⛔ **与 TTL 分列**：TTL 只让池过期、账本与 hook 都在；冷启是**进程内集合整体清零**；持久化态（`rescued` 台账、`image2` 原文件、语音 `voice_bak/`）保留 |

### ③ 复位逐条（⛔ 10 条齐全，每条写清除条件与残留，⛔ 不许写「同返回」）

| # | 复位动作 | 清除条件（何时发生） | 清掉什么 / 残留什么 | 与 ② 返回档的本质差异 |
|---|---|---|---|---|
| 1 | **关 `antirevoke.media_delete`** | `kvset false`；因 `refresh()` 只在 `install()`/`invalidate()` 调（`:148-151`），`[推断]` 要等**下一次 `record()→invalidate()`** 或冷启才把 `enabled=false` 刷进内存 | 清：`before` 第一行 `if(!enabled) return`（`:232`）⇒ **账本停止增长、不再拦**；残留：hook **不卸载**只空转、`protectedMedia` / `cachedTokens` / `cachedRowIds` **不清空**（旧 token 靠 TTL 自然过期）；⛔ **不残留任何对微信库的写入** | 返回档开关/池/hook 都不变；此档开关翻转 + hook 常驻空转 + 池残留 + 账本停增 |
| 2 | **关 `antirevoke.msg`** | `kvset false` 后**下一次撤回**即生效（`AntiRevoke` 不再拦 UPDATE） | 清：`RevokeRescue.record()` 不触发 ⇒ 图片分支 `protectMedia` 与 `MediaTableGuard.invalidate()` **都不跑** ⇒ 池不新增、`cachedTokens`/`cachedRowIds` 不重建、账本不增长、`AR blocked revoke` 消失 ⇒ **`message` 行被微信改写、原文消失**；残留：`VoiceFileGuard.on()` 3 秒 TTL（`:185-189`）关后最多 3 秒内仍拦 `File.delete`；既有 `rescued` 台账仍在 | 返回档原文与台账都在；此档原文消失、台账不写、池不新增、账本静止 |
| 3 | **保护池 10min TTL** | 自 `protectMedia` 起满 `PROTECT_MEDIA_MS = 600_000ms`（`VoiceFileGuard.kt:112`） | 清：`anyAlive` 复位 `hasMedia=false`（`MediaGuardLogic.kt:110-113`）、`WINDOW_MS` 到期 ⇒ `isRevocationWindow` false ⇒ 不再拦；⛔ `protectedMedia` 条目**不主动删**，仅在 `size>256` 时 `removeIf`（`:152-154`）；残留：池容器 / 账本 / hook / 开关 / `cachedRowIds` 全在 | 返回档池还在保护态；TTL 是**时间维度**单独复位，⛔ 不与「切页回来」合并（`_r49:279,287`） |
| 4 | **imgAnchor 30min TTL** | `IMG_ANCHOR_TTL_MS`（`AntiRevoke.kt:470`）到期 / 256 上限淘汰 / 进程死 | 清：消息到达锚点（`imgAnchorOf`，`RevokeRescue.kt:268`）失效 ⇒ `auditImage` 取不到 `svrId`、退化为只按 `msglocalid` 查；⛔ **不清空整个集合、冷启才丢**（`_r49:292`）；残留：与保护池 **不同载体、不同时长** | 返回档 imgAnchor 不变；此档按 30min / 256 上限 / 进程死**三条独立**清除 |
| 5 | **账本冷启清零** | 微信进程死亡重建（**唯一条件**） | 清：`seen`/`matched`/`blocked`/`skipped` 4 个 `AtomicLong` + `events`(≤64) + `logAt`(≤64) + `obsLogAt` + `cachedTokens` + ⭐`cachedRowIds` + `gen` + `lastIssuedMs` **全部进程内整体清零**；`verdict(seen,matched)` 回 `NO_EVENT`；残留：持久化态（`rescued` 台账、`image2` 文件、语音 `voice_bak/`） | ⛔ 与 TTL **分列**：TTL 到期时账本仍在、hook 仍在；冷启是**进程维度**整体清零，触发条件与残留完全不同 |
| 6 | **hook 随进程死才卸** | `HookEntry.kt:222-224` 的 `install()` **无 unhook**；关开关 / TTL 过期 / 清缓存**都不卸载** | 清：**仅进程死亡**时 `delete`/`execSQL` 上的 callback 才真正消失；残留：关开关后 callback 仍常驻但空转（`enabled=false` 直接 return） | 返回档 hook 常驻且生效；此档 hook 常驻但可能空转 |
| 7 | **微信重试删除落在窗口外或冷启池空** | 撤回 600s **之后**的重试，或冷启后 `protectedMedia`/`cachedRowIds` 为空时的重试（`_r51_media_table_guard_impl.md:243` 记防撤回侧实测 **5 秒 360 次**） | 清：无人拦 ⇒ `ImgInfo2` 行被真删、`param.result` 不置；⛔ 池空时 `:254-259` 走 `tokens-empty` 分支直接 return，甚至不进 `shouldBlock`（`skipped` 也不增）；残留：⛔ 图片侧**无 backup/restore**（`RevokeRescue.kt:121-127`）⇒ 漏拦即**永久丢失** | 返回档默认「删除只发生一次且已被拦」；此档把「重试时机」变成自变量 |
| 8 | **清缓存** | `op=logclear`（`EntryReceiver.kt:165-168`）或微信「存储空间→清缓存」删 `image2` | 清：`op=logclear` **只清 LogX 环形缓冲**，⛔ 不动池 / 台账 / `cachedRowIds` / 文件；微信清缓存时开关**开着** ⇒ `File.delete` 被 `VFG` 拦 ⇒ 存储只增不减（`VoiceFileGuard.kt:67-68,175-177`）；开关**关着** ⇒ 真删文件；残留：DB 行不受影响 | 返回档**不会删文件**；这是唯一能真正把已保护图片文件删掉的路径（`_r49:280`） |
| 9 | ⭐ **LogX 满 5000 行整文件清空重来** | 落盘日志行数达到 `MAX_LINES = 5000`（`LogX.kt:22`）的那一刻 | 清：`LogX.kt:83-85` `java.io.File(path).writeText("$ts ---- log rotated: 已达 5000 行，清空重来（最新日志始终保留）----\n")` ⇒ **整个文件一次性清空重写**，只留一行 **`log rotated`** 标记 + 之后的新日志；⛔ **非逐行 FIFO**（不是删最老一行）：撤回到验收之间的 `RRV record` / `VFG protectMedia` / `MTG blocked` / `HDIMG` 证据行可能**整批消失**，只剩标记；残留：池 / 账本 / `cachedRowIds` / hook / 文件**一概不动**，行数计数器归零后重新累加 | 返回档日志累积但不清；此档是**日志载体的整文件截断**，⛔ 直接威胁 T5 取证链 ⇒ 验收前必须 `op=logclear` 并控制日志量（见 T5） |
| 10 | ⛔ **已被删的 DB 行 / 文件不可回滚** | `beforeHookedMethod` 未能阻止删除（判据放行 / `execSQL` void 写法 `[需真机]` 不成立 / native `unlink`） | 清：`ImgInfo2` 行一旦被删、`image2` 文件一旦被删，**无副本可还原**（`_r51_media_delete_logic.md:370`、`_r51_media_table_guard_impl.md:245`）；残留：`message.imgPath` 可能仍在、`th_` 缩略图可能被 `VFG` 保住，但 `HDIMG fired … img=null`（`_r52_device_log_snapshot.md:85`）⇒ 组件拿不到路径 | 返回档前提是「行与文件都还在」；这是**不可逆终态**，不属于任何「返回」路径 ⇒ **T5 必须重发新图** |

[DONE] T4

## T5 真机验收与反向对照

### 硬前提（⛔ 三条，违反即验收无效）

1. **自变量唯一**：本轮只改 `antirevoke.media_delete` 对应的判据（`MediaDeleteLogic` 的 id 通道 + `MediaTableGuard.invalidate()` 反查）。⛔ 不改 `antirevoke.msg`、不改 `VoiceFileGuard`、不改 hook 注册、不改 TTL、不装第二个包、不 kvset 其它 key。反向对照只翻 `antirevoke.media_delete` 一个。
2. ⛔ **已被删的 DB 行不可回滚**（T4 #10、`_r52_device_log_snapshot.md:179-180`）⇒ **修复装包后必须请 verity 重新发一张新图再撤回**，⛔ 绝不拿 23:55（msgId=19640471）/ 00:13（19640543）/ 01:03（19640720）这三张老图验收 —— 老图的 `ImgInfo2` 行已被真删（01:06 `HDIMG trace` 三张老图 `fld[h=0 i=0 j=0 o=0]` / `img=null` 正是该行缺失的下游后果），判据修得再对也变不回来。
3. ⭐ **主判据 = 用户能看见的事实：能否看到完整大图**。撤回后隔 3~5 分钟**点开被撤回的那张图，能否看到完整大图**（`_r52_device_log_snapshot.md:65-68,176-178`）；这是**唯一主判据**。⛔ 不许拿 `seen/matched/blocked/skipped/row=0/verdict/tokens/gen` 当结论——它们只作定位/归因；同样不许拿 `MTG blocked` 日志或 `HDIMG img!=null` 当「修好了」的结论（`MTG blocked` 可能拦错对象，见 T2 乙-1~乙-3；`img!=null` 只作定位）。⛔ 01:06 已证修复前「`message.imgPath` 在 + 磁盘文件在」（排除两支），但**「拦住后行保留 ⇒ `h/i/j` 恢复 ⇒ 能看到大图」仍 `[需真机]`**（甲-7），不得预设成立。

### 观测命令与顺序（严格按序，⛔ 顺序不能反）

**A. 撤前（基线）**
1. `op=logclear` —— 清 LogX 缓冲并归零行数计数（`LogX.kt:100-124`、`EntryReceiver.kt:165-168`），⛔ 把 T4 #9「满 5000 行整文件清空重来」把证据整批冲掉的风险归零。
2. `op=mtg sub=reset` —— 账本归零，确认 `seen=0 / NO_EVENT`（`_r52_device_log_snapshot.md:146-147` 同款）。
3. 冷启一次，`dev.py q 'MTG'` 确认出现 `MTG installed hooks=delete,execSQL enabled=true pool=ok switch=antirevoke.media_delete`（`CLAUDE.md:75-76`：验挂判据是日志里有没有 `installed`）。
4. ⭐ **撤前 `ImgInfo2` 快照**（修 `_r52_device_log_snapshot.md:173-174` 的缺口：上次只取了 `max(id)`、没取目标 md5 行数）：走 `op=label sub=sql` → `LabelHuntProbe.runSql()` → `AntiRevoke.wxDb()` **主库通道**（`_r52_revokeimg_rootcause.md:88-89` 明写 `op=sql` 走朋友圈 `snsDb` 会 `no such table`，只能验通道、不能当主库判据），取**最新 N 行全量**留档：
   ```
   op=label sub=sql sql='select id, msglocalid, msgSvrId, origImgMD5, hex(thumbImgPath) as th, hex(bigImgPath) as big from ImgInfo2 order by id desc limit 20'
   ```
   ⛔ BLOB 列一律 `hex()` 不用 `cast as text`（`_r49_revokeimg_reference.md:134` 实证首个 NUL 截断假阴性）。这一步**同时验证 T2 甲的反查口径**：看 `origImgMD5` / `thumbImgPath` 是否含即将那张图的 md5。
5. 请 **verity** 在「**测试群聊**」（或「**文件传输助手**」）发一张**新图**，**先别点开**，3~5 秒后撤回（`_r52_device_log_snapshot.md:65`）。

**B. 撤回瞬间（⛔ 只定位，不下结论）**
6. `dev.py q 'MTG|RRV|VFG|HDIMG'` 拉全量，逐行核对：
   - `RRV record ok=true origType=3 imgPath=THUMBNAIL_DIRPATH://th_<md5>` ⇒ 记下本次 md5；
   - `VFG protectMedia img=<md5>.. ttl=600000ms pool=1` ⇒ 保护池登记；
   - ⭐ `MTG id-lookup md5=<md5> rows=<n> ids=[…]`（甲路线新增日志，`invalidate()` 反查结果）⇒ **必须 `rows>=1`**；若 `rows=0` 即 T2 甲-1 头号失效场景，**当场停手按 D3 回头**（`CLAUDE.md:45-46`），不要继续点图下结论；
   - `MTG delete table=ImgInfo2 where=id=? … reason=blocked gen=<n>` ⇒ 从 01:03 的 `shouldBlock-false` 变成 `blocked`（**只作定位**）；
   - `VFG blocked MEDIA delete img=<md5>..` ⇒ 缩略图文件保住。
7. `op=mtg sub=stat` —— 读 `MediaTableGuard.stat()`（`MediaTableGuard.kt:167`）一行 `MTG stat enabled/gen/seen/matched/blocked/skipped/verdict/pool/tokens/events/switch`。⛔ **只用于定位有没有走到拦截分支**，不作判据（对照 01:04:37 那行 `blocked=0 skipped=1`）。

**C. 撤后（主判据）**
8. **撤后 `ImgInfo2` 快照**（同第 4 步 SQL）⇒ 目标 md5 对应那一行**仍在**（`n: 1 → 1`，而非 `1 → 0`）；对照 01:03 那次是「撤后目标 md5 0 命中」（`_r52_device_log_snapshot.md:171-172`）。⛔ 这仍只是辅助定位。
9. ⭐ **隔 3~5 分钟点开被撤回那张图**：能否看到**完整大图**（不是缩略图、不是灰图、不是「图片已过期 / 已被撤回」提示）。**这是唯一主判据**。同时 `dev.py q 'MTG|HDIMG'`（关键字含 `HDIMG`，可一并抓到 `HDIMG trace` 行）：读 `HDIMG trace` 的 **`fld[h/i/j/o]` 与 `img=`** —— 对照 01:06 老图基线 `fld[h=0 i=0 j=0 o=0 q=19640720]` / `img=null` 与正常图 `h/i/j=875524` / `img=SERVERID://…`（`_r52_device_log_snapshot.md:85-86`、头部「补充决定性证据」）。修复成立时预期 `h/i/j≠0`、`img=SERVERID://…`；⛔ 但 `img != null` 与 `h/i/j≠0` **仍只作定位**，**能不能看见完整大图**才是结论。⛔ 若行在（`n: 1→1`）但 `fld[h/i/j/o]` 仍全 0、`img=null` ⇒ 命中 T2 **甲-7** 的 `[需真机]` 支，按判定表处理。再 `op=mtg sub=stat` 收一次尾（只作定位）。
10. 补测 T4 ② 返回档：切页回来 / 下拉刷新 / 撤后满 5 分钟再点开一次，确认仍可见。

**D. 反向对照（自变量唯一）**
11. `kvset antirevoke.media_delete=false` → 冷启（或等下一次 `record()→invalidate()` 把 `enabled=false` 刷进内存，T4 #1）→ 请 **verity** **再发一张新图**并撤回 → 重复第 6~9 步。预期：`reason=shouldBlock-false` 回归、撤后 `ImgInfo2` 目标行 `n: 1→0`、**点开看不到大图 / 出现撤回提示**（`_r52_revokeimg_rootcause.md:61`）。⛔ 反向对照也必须用**新图**，不能复用第 5 步那张。
12. 二次复核：`kvget antirevoke.media_delete` 回读确认真关；对照结束把开关开回并冷启，确认 `installed enabled=true` 复现（`_r52_revokeimg_rootcause.md:36-37` 的 `kvset`→`kvget`→冷启三段）。

### 验收判定表（主列「用户可见事实」；括号内 `HDIMG trace` / `n` / `reason` 均为定位项，⛔ 定位项不作判据）

| 结果 | 判定 | 下一步 |
|---|---|---|
| 修复装包后**新图**撤后能点开看到**完整大图** + `HDIMG trace` 的 `h/i/j≠0`、`img=SERVERID://…`，反向对照**看不到** | ✅ 修复成立（自变量唯一、正反向闭合、⛔ 未动渲染层） | 交付；补 T4 冷启 / TTL 边界 `[需真机]` |
| 正反向**都看不到** | ⛔ 修复未生效；⛔ **01:06 修订**：`HDIMG img=null` 的因（判据放行 ⇒ 行被删）已闭合，不再是「另有待验根因」，应回头查 `MTG delete ... reason=` 与拦截是否生效 | 按 **D3** 停手，⛔ 不再改第三条判据 |
| 正反向**都能看到** | ⛔ 自变量没生效（开关没刷进内存 / 拿错图 / 反向对照用了老图） | 查 `installed enabled=` 与 `kvget`，重做，⛔ 不能算通过 |
| `MTG id-lookup rows=0` | ⛔ 甲反查口径失效（T2 甲-1、`imgAudit row=0` 同型） | 当场停手，按 T2 用 `thumbImgPath`/`origImgMD5` 列形态 `[需真机]` 复核，⛔ 不硬上乙全拦 |
| ⭐ 撤后 `ImgInfo2` 行在（拦住了，`n: 1→1`）、但 `HDIMG trace` 仍 `fld[h/i/j/o]` 全 0 / `img=null`、**点开看不到大图** | ⛔ 命中 **T2 甲-7 / 乙-7 的 `[需真机]` 残留支**：行保留但别的字段/状态仍让微信填 0（行内 `status`/`iscomplete`/`bigImgPath`/`msgSvrId` 被改、或撤回走 UPDATE 改写字段）。⛔ **不作定论**：本设备从未观测「行保留后」的 `HDIMG trace` | 按 **D3** 停手：撤前/撤后各取一次行快照对比上述字段，`dev.py q 'MTG\|HDIMG trace'` 查撤回瞬间有无 `ImgInfo2` 的 UPDATE；⛔ 别拿 `blocked` 当成功，⛔ 不继续加第三条判据，⛔ 也不因此改渲染层 |

### 纪律收尾

- ⛔ 外部掘金 coder-pig / 微X 的「拦住 DB 行就能显示大图」一律标 **`[转引]`**（`_r49_revokeimg_reference.md:26,100`、`_r52_revokeimg_rootcause.md:5,21`），⛔ 不得据此跳过第 9 步。**01:06 之后的分级修订**：本设备 01:03 + 01:06 一手证据已把**「判据放行 ⇒ 行被删 ⇒ `h/i/j=0` ⇒ `img=null`」这条断点定位链**闭合（⛔ 一手实测，非转引、非推断），不再笼统写「本设备从未闭环」；但 `_r52_revokeimg_rootcause.md:21` 所指的**「拦住 delete 后行保留 ⇒ UI 显示大图」这一修复后半段仍 `[需真机]`**（甲-7），本设备确实从未观测 ⇒ **仍须逐字按第 9 步 + 判定表验收**。两半不可混为一谈。
- ⛔ 好友只用 **verity** / **测试群聊** / **文件传输助手**（`CLAUDE.md:12`），⛔ 不写裸 wxid。
- ⛔ 本轮**不改源码**：以上命令与判据是「下一轮实现 lane 的验收契约」，本 lane 只交付设计。

[DONE] T5
[DONE] T5-rev（01:06 证据 → 硬前提「修复装包后须请 verity 重发新图」/ 第 9 步读 `HDIMG trace` 的 `fld[h/i/j/o]` / 判定表新增甲-7 支 / `[转引]` 与一手证据两半分级）

---

[DONE] ALL
