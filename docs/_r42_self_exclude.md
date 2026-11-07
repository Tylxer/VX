# R42 · 自己删除动态「彻底放行」实现记录（self-exclude）

> 实现者：**self-exclude**（改代码并落盘，不只给方案）。
> 范围：`SnsGuard.kt` / `SnsBlockProbe.kt` / 本文件。
> ⛔ 未做且禁止：adb、装包、commit、改其它功能、改测试期望值。

## 0. 命题（抄自 team-lead，用户最新指令）

「保位（keep = old|10）不执行」—— 自己删自己的动态必须**真的被删掉**，不是「拦下来但不打标记」，
是**彻底放行**：不保位、不加前缀，与微信原生删除行为一致。

## 1. 改动点清单

### 1.1 `SnsGuard.kt`

- **`applyBlock` 增加 self-exclude 早退**：在 `blockEnabled` 的 OFF 检查之后、keep 计算之前，
  新增：
  ```kotlin
  if (isSelfAuthor(bits?.userName)) {
      SnsBlockProbe.record(id, bits, keys, hasContent, hasAttr,
          SnsBlockProbe.Outcome.SELF, newValue = newValue)
      return
  }
  ```
  命中即 `record(SELF)` 后 return：**不 put keep、不 remove、不 attachPrefix**，
  微信这次 `update`（`sourceType=0` / 降位）原样落地，动态真被删掉。
- **KDoc 同步**：`applyBlock` 新增「R42 self-exclude」小节；`snsBlockGuard` 的「两个拦截点」
  补一行例外说明；类注释「一律拦」补 self-exclude 例外。

### 1.2 `SnsBlockProbe.kt`

- `Outcome` 枚举新增 `SELF`（作者是自己 ⇒ 彻底放行）。
- 新增两个独立计数器 `selfExcluded`（写 0 路径）/ `selfExcludedDrop`（降位路径）。
- `record()` 的 `when` 补 `Outcome.SELF` 分支：累加对应计数器 + 限流日志，**不 set pending**。
- 新增 `logSelfExcludedThrottled`（复用 `hitLogAt` 5s/id 节流，日志 `BLOCK self hit|drop`）。
- `stat()` 新增一行 `BLOCK self zero=.. drop=..`。

## 2. 五个判定问题的回答

### 2.1 early return 放哪一层：`applyBlock` 开头一处，不放两个调用点

理由：
1. 两个调用点（写 0 的 `onSnsUpdateBefore`、降位的 `onSnsUpdateDrop`）都在调用前已算好 `bits`
   并作为参数传入；判据唯一输入就是 `bits?.userName`，`applyBlock` 已持有，放调用点**没有任何
   IO / 反射节省**（写 0 路径本来就要 `readBits` 算 keep，降位路径本来就要 `readBits` 判 decide）。
2. 单一决策点：排除判定 + 留痕（`record SELF`）只写一处，避免两处各自早退导致判据漂移
   （一处漏加、或降级条件不一致）。
3. OFF 检查必须保持第一（硬约束 1）。self 排除紧随其后，只作用于「开关开」的路径。

### 2.2 execSQL 的 `& -9` 路径：自己删除**不走**，方案 B 无需（也不应）排除自己

**结论：不是缺口，是两条正交通道，登记为「不需要改」。**

1. 自己删动态的真实通道是 `update("SnsInfo", {sourceType=0}, "snsId=?", [id])` →
   `updateWithOnConflict`（写 0 路径），由 `applyBlock` 处理 —— 不是 execSQL。
2. `& -9`（方案 B）是「进好友主页时批量清 bit3（主页相册记账位）」的裸 SQL：
   `UPDATE SnsInfo SET sourceType = sourceType & -9 WHERE SnsInfo.userName="<好友>"
   AND (sourceType IN (8,264,10,...)) AND ...`。它清的是 bit3，**不是删除**；且 WHERE 命中集合
   `sourceType IN (...)` 全含 bit3、**不含 0** ⇒ 已被删（`sourceType=0`）的行根本不在命中集合里。
3. 即便假设未来出现「进自己主页也跑这条、且想排除自己」的场景，**也不可行/不该做**：
   - `SnsSqlRewrite` 的核心约束是「只动 SET，WHERE 一个字节不碰」—— 因为改 WHERE 会改变命中集合
     与 `changes()`，破坏微信按区间翻页的正确性；
   - 排除自己必须追加 `AND SnsInfo.userName != '<self>'`，属于改 WHERE，违反上述约束。
   因此 `& -9` 对「自己删除」无拦截关系，本轮一行未动（与 bit3 实现的「方案 B 未动」口径一致）。

### 2.3 after 半场统计污染推论：成立

- `record()` 里只有 `Outcome.BLOCKED` 且 `cv != null` 才 `pending.set(e)`。
- self 排除走 `Outcome.SELF`（不传 cv），`SELF` 分支不 set pending。
- `onSnsUpdateBlockAfter` 第一句 `hasPending()`（`pending.get() != null`）短路 ⇒ after 直接 return，
  不调 `finishAfterWrite` ⇒ `afterOk`/`afterFail`/`shape*` **零增量、零污染**。
- 补充：`onSnsUpdateBefore` 开头已 `clearPending()`，即便上一次 BLOCKED 的 after 未跑（异常），
  残留也会被清掉，不会跨调用污染下一条。

> 我采用 `record(SELF)` 而非「完全不 record」：二者在「不 set pending ⇒ after 短路」上等价；
> `record(SELF)` 额外保留了 `hit`/`updSeen`/`updZero`（写 0）或 `dropSeen`（降位）的「事件发生」
> 计数 + 事件账本留痕，更利于排查（见 2.5）。

### 2.4 `bits == null` 降级：硬红线，已满足

- `isSelfAuthor(null)` 恒 false（`author != null` 不成立）。
- `selfWxid()` 取不到时 `isSelfAuthor` 也恒 false。
- ⇒ `bits == null` 或 `self wxid` 取不到时，`isSelfAuthor(bits?.userName) == false`，**不排除**，
  照常走保位/摘键拦截。绝不因拿不到作者放行。
- `isSelfAuthor` 逻辑本轮**未改**（team-lead 明确「逻辑本身不要改」）。

### 2.5 可观测性：新增独立计数器 + 事件账本 + 限流日志，不污染现有计数器

- `Outcome.SELF` 独立于 BLOCKED/OFF/SKIP_*；`selfExcluded`/`selfExcludedDrop` 是两个**新**计数器。
- 不碰 `blocked`/`skipOff`/`afterOk`/`afterFail`/`shape*`（team-lead 点名的五项）。
- `hit`/`updSeen`/`updZero`（写 0）与 `dropSeen`（降位）**保留递增**：它们语义是「事件发生了」
  （`hit` 定义即「`sourceType=0` 且 table=SnsInfo 的次数，**不论开关、不论后来是否真拦**」），
  自己删除确实是事件，不该从通道计数里消失，否则会出现 `selfExcluded=1 但 hit=0` 的自相矛盾。
- 事件账本会记 `before=<old> want=0 keep=- blk=0`，`blockstat` 的最近事件可见。
- `stat()` 新增 `BLOCK self zero=.. drop=..` 一行（固定行 5→6，仍满足 `take(16)` 上限）。
- 日志 `BLOCK self hit|drop snsId=.. before=.. want=..（自己删除，彻底放行…）`，复用 `hitLogAt` 节流。

## 3. 三条红线书面确认

### 3.1 进入边界（哪些路径、哪些条件排除自己）

- 仅在 `applyBlock` 内、`cfg().blockEnabled == true` 时（OFF 分支在前，先 return）。
- 判据 `isSelfAuthor(bits?.userName) == true`，即：`readBits` 成功读回该行 `userName`，
  且 `selfWxid()` 成功读到当前登录 wxid，且两者**相等**。
- 覆盖两条调用路径：写 0（`newValue == null`）与纯降位（`newValue == st`）。

### 3.2 返回边界（哪些情况**不**排除）

- `bits == null`（snsId=0 / 无句柄 / readBits 失败 / 无此行）⇒ `bits?.userName == null` ⇒ 不排除。
- `selfWxid()` 取不到（4 个候选 prefs 全 null/blank，或 HostContext 未 attach）⇒ 不排除。
- `bits.userName == null`（该行 userName 列为 null，罕见）⇒ 不排除。
- 开关关闭（`blockEnabled == false`）⇒ 走 OFF 分支（不写字节、只记账），与 self 排除无关
  （行为上也是放行，但计 OFF 而非 SELF）。

### 3.3 复位边界（是否引入状态/缓存/计数器/ThreadLocal，如何保证不影响紧接着的他人动态）

- 本轮**未引入**任何新的状态/缓存/ThreadLocal。
- 复用既有 `selfWxidCache`（只缓存成功值，与 `attachPrefix` 的排除共用，无新状态）；
  它只影响「能否判 self」，判不出就照常拦截，不会跨调用残留「上次是 self」的状态。
- 新增的 `selfExcluded`/`selfExcludedDrop` 是纯累加计数器，无读改写回，不参与拦截决策。
- `pending`（ThreadLocal）不在 SELF 分支 set；`onSnsUpdateBefore` 开头 `clearPending()` 已清残留
  ⇒ 上一条 self 的放行不会让下一条他人动态的 after 误触发或误配对。
- 因此「紧接着的他人动态」的拦截路径（OFF 检查 → self 判据 false → keep/摘键）与改动前**逐字节一致**。

## 4. `& -9` 路径结论（解决 / 缺口）

**结论：不需要改，登记为「结论」而非「缺口」。** 详见 2.2。
真机反向对照里附带一条判据：自己删除后 `sqlClear9`/`sqlBlock9` 不因 self 而变，仍只随
「进好友主页」增长 —— 证明 self 排除没有误伤方案 B。

## 5. 真机反向对照方法（自己 vs 他人，各一条，预期差异写清）

### 5.1 自己动态（预期：真被删掉）

1. 发一条自己可见的动态，`op=sql` 查并记下 snsId 与删除前 `sourceType`（基线）。
2. `op=snsg --es sub blockstat` 记基线计数。
3. 微信里删除这条自己的动态。
4. 预期：
   - `blockstat` 出现 `BLOCK self hit snsId=.. before=<old> want=0`（写 0 路径）或
     `BLOCK self drop`（降位路径），`BLOCK self zero/drop` 计数增长。
   - **不出现** `BLOCK hit`（BLOCKED 类）日志；`blocked`/`dropBlocked` 不因这条增长。
   - `op=sql` 回读该 snsId：**行已消失**（sourceType 落 0 后被清，或整行 delete）。
     核心判据：库里这条动态不再可见。
   - 时间线 / 好友主页里这条动态**真的没了**。
   - content **没有** `[已删除]` 前缀（若行已删通常查不到 content）。

### 5.2 他人动态（预期：仍被拦）

1. `op=snsprefix --es sub simdrop --el id <他人 snsId>`（或真机让他人删除，本地对应行）。
2. 预期：
   - `blocked`/`dropBlocked` 增长、`shape=BLOCKED_BY_REWRITE`（或 `BLOCKED_BY_REMOVE`），
     日志 `BLOCK hit/drop ... before=<old> want=0 keep=<old|10>`。
   - content 出现 `[已删除]` 前缀（`hasPrefix=true`）。
   - 动态在本地库里保留（sourceType=old|10 或 old），时间线/主页仍可见。
   - **不出现** `BLOCK self`。

**两条互斥**：自己 ⇒ `BLOCK self` + 行消失 + 无前缀；他人 ⇒ `BLOCK hit/drop` + 保位 + 有前缀。

## 6. 硬约束复核

1. **主开关关闭行为完全不变**：OFF 分支仍在 `applyBlock` 第一行、self 检查在其后（开关关时
   根本到不了 self 检查），`record(OFF)` 不变，不写任何字节。
2. **不改现有测试期望**：未改任何测试文件；`SELF` 枚举值 / 新计数器是纯增量，`when` 补全分支，
   无现有断言依赖 `Outcome` 完整集合或 `stat()` 行数。
3. **只改 `app/src/main`**：实际只改 `SnsGuard.kt`、`SnsBlockProbe.kt` 两个 main 文件 + 本 doc，
   未改 test。
4. **不 commit**。

## 7. 收口声明

自本文件落盘起，源码进入静止状态，等待装包 / 真机验收（真机由主会话执行，self-exclude 不碰 adb）。
`& -9` 路径按 §4 登记为「结论（不需要改）」，若未来真机出现反例（自己删除还触发针对 self userName
的 `& -9` 且影响删除），再另行评估 —— 但那与「删除」语义正交（bit3 清 ≠ 删行），不在本轮范围。

[ALL-DONE]

---

## 8. 真机验收（主会话执行，2026-11-07 22:30–22:37）

### 8.1 前置取证：self wxid 在真机确实取得到

子代理在 §7 把「self wxid 能否取到」列为待确认。主会话用两条**互相独立**的证据闭环：

1. **root 直读**（绕过模块）：`/data/data/com.tencent.mm/shared_prefs/com.tencent.mm_preferences.xml`
   里 `login_weixin_username = wxid_myzsbcur81xr21`（19 字符）。
2. **模块内探针** `op=snsprefix --es sub self`：
   ```
   SELF com.tencent.mm_preferences/login_weixin_username = ok(len=19)   ← 命中
   SELF com.tencent.mm_preferences/login_user_name = null
   SELF system_config_prefs/login_weixin_username = null
   SELF com.tencent.mm/login_weixin_username = null
   SELF resolved=HIT len=19   SELF mine=8
   ```
   ⇒ 4 个候选里只有第 1 条命中，其余 3 条全 null；`len=19` 与 root 直读吻合。
   **「排除自己」不是静默失效**（这是本轮唯一可能让整个功能看起来对、实际不生效的入口）。

### 8.2 正反双向对照（同一套钩子、同一时刻，自变量只有作者）

| 样本 | 角色 | 探针命令 | 关键日志 | `src` | 前缀 |
|---|---|---|---|---|---|
| `-3437240302323363201` | **自己** | `simdrop` | `BLOCK self hit ... before=2 want=0` | **0**（真删） | **false** |
| `-5431437036424195932` | 他人 | `simdrop` | `BLOCK hit ... after=10 keep=10 act=writeback shape=BLOCKED_BY_REWRITE` | **10**（保位） | **true** |

自己那条的完整输出：
```
BLOCK self hit snsId=-3437240302323363201 before=2 want=0 （自己删除，彻底放行：不保位、不加前缀）
PREFIX simdrop id=-3437240302323363201 rows=1
PREFIX simdrop-verify id=-3437240302323363201 src=0 desc=[] hasPrefix=false
```

他人那条：
```
BLOCK hit snsId=-5431437036424195932 before=2 want=0 keys=1 ...
BLOCK after snsId=-5431437036424195932 before=2 after=10 keep=10 act=writeback shape=BLOCKED_BY_REWRITE
PREFIX simdrop-verify id=-5431437036424195932 src=10 desc=[[已删除] ] hasPrefix=true
```

⛔ 对照样本筛选踩过一个坑：最初选的 `-4998797776201305941` 基线 `hasPrefix=true`
（微X 留下的历史标记，库里有 13002 条同款），**无法区分是不是本次新加的**，已换掉。
⇒ 以后挑对照样本必须**先 probe 确认 `hasPrefix=false`**，否则会得出「保位没生效」的假阳性。

### 8.3 计数器互不污染（子代理在 §2.3/§2.5 声明的不变量，实测复核）

```
BLOCK stat hit=2 blocked=1 nonZero=9 skipOff=0 last=22:31:46
BLOCK self zero=1 drop=0
```

- `hit=2`：两次模拟都记「事件发生」（`hit` 语义本就含「不论后来是否真拦」）；
- `blocked=1`：**只有他人那条真被拦** ⇒ SELF 没有污染拦截计数；
- `self zero=1 / drop=0`：自己那条走写 0 路径，独立计数；
- `afterOk/afterFail/shape*` 无 SELF 增量 ⇒ §2.3「early return 不 set pending ⇒ after 短路」推论成立。

### 8.4 测试数据清理（全部复位，无残留）

| 动作 | 命令 | 结果 |
|---|---|---|
| 自己那条 `src 0→2` | `setsrc2` | `rows=1 src=2` ✓ |
| 他人那条 `src 10→2` | 临时 `kvset antirevoke.sns=false` + `antirevoke.sns_block=false` → `setsrc2` → 两个都设回 `true` | `rows=1 src=2` ✓ |
| 他人那条前缀 | `sub=strip`（本轮新增） | `before=[已删除]  after=  rows=1` ✓ |

⚠️ 两个坑记下来：
1. **只关 `antirevoke.sns_block` 不生效**：`blockEnabled = ANTIREVOKE_SNS || ANTIREVOKE_SNS_BLOCK`，
   主开关还开着照拦。要临时全放行必须**两个都关**。
2. **`setsrc2` 改不了前缀**：它只写 `sourceType`。而 `simdrop` 走生产路径 `buildPrefixedBlob`，
   设计上不落备份（微信写库线程不做文件 IO）⇒ 没有 `.bin` 可 `restore`。
   因此**新增 `sub=strip`**（`ContentDesc.removePrefix(PREFIX)`，幂等，不依赖备份）——
   这是 `prefix` 的对称逆运算，专治自动化测出来的前缀残留。

### 8.5 未覆盖 / 遗留

- **真实删除路径未跑**：本轮用 `simdrop` 模拟 `update(sourceType=0)`，等价于写时拦截入口，
  但**没有让用户在 UI 上真删一条自己的动态**。若要更强的证据，需要用户手动删一条自己发的动态再复查。
- **服务端同步覆盖前缀**：上一轮遗留的未验项，本轮仍未验（需一个完整同步周期）。
- `-3397129091654020486`（上轮 simdrop 遗留）本次复查 `hasPrefix=false`，`src` 未再核（非本轮范围）。

