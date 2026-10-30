# R37 朋友圈导出退化排查（rows=500 written=0 listMiss=499）

> 目标：微信 8.0.48 / 2588 GP，模块包 `com.lx.wxgroup`
> 现象：R26 历史 `rows=500 written=26 listMiss=474` → 今天 `rows=500 written=0 listMiss=499`
> 约束：纯离线排查（Read/Grep/git），**未碰 adb/设备**，**未改 `app/src/main`**，**未跑 assembleDebug**
> 名词：`actor=` 行 —— `wxgroup/app/src/main/java/com/lx/wxgroup/...`（下文所有行号均指该文件）

---

## 0. 前置：先看历史结论，避免重复考古

| 来源 | 结论 |
|---|---|
| `docs/_r35_pend_decisions.md:127-138` | 已核过 `rows=500 written=26 listMiss=474`（引自 `docs/_r26_img_export_真机验证.md:32`），明确 `listMiss` = **没命中名单**，**不是失败**。**没有**任何关于「退化到 499」的存量结论 ⇒ 本次不是重复考古 |
| `docs/conclusions/moments.md:17` | snsId 双写：**库里有符号 Long**（`-3442401033659805004`），`ImproveInfo.snsID` 是**无符号十进制 String**（`15004343040049746612`）⇒ SQL 里必须用有符号 |
| `git log` | `MomentsExport.kt` / `MomentsLogic.kt` 最后一次改动 = `f231af2`（R24-R29 累积），**最近一次提交 `d351b11`（R34 P2）完全没碰这两个文件**（只碰了 `ListResolver.kt` +13 行 KDoc）⇒ **判定链自 R24 起无变更，不存在近期代码回归** |

---

## Q1：名单是「裸 wxid」还是「昵称」？比对口径是什么？

### 结论：**两种都认，且裸 wxid 在代码上是能命中的** ⇒ 「名单存 wxid 所以必然全 miss」这条分支**不成立**

命中判定入口 `MomentsExport.kt:781`：

```kotlin
// MomentsExport.kt:780-784
val nick = nickOf(row.userName, nickCache)
if (!listHitResolved(c, row.userName, nick)) {
    lm++            // ← listMiss 的唯一来源
    continue
}
```

`listMiss` 的定义与用户给的完全一致（`:308` 声明 / `:857` 累加 / `:878` 输出）。

判定函数 `listHitResolved`（`MomentsExport.kt:467-473`）—— 三层：

| 层 | 行号 | 内容 |
|---|---|---|
| ① 不限制 | `:468` | `if (c.listUnrestricted) return true` —— 名单为空 = 全量导出 |
| ② 不可能集 | `:469` | `if (c.listImpossible) return false` —— 配了但展开不出任何人 ⇒ **全体 miss**（见 Q2 的残留陷阱） |
| ③ wxid 集合 | `:470-471` | `val w = wxid?.trim().orEmpty()`；`if (w.isNotEmpty() && w in c.ids) return true` —— **Set 精确成员判定** |
| ④ 字面兜底 | `:472` | `MomentsLogic.listHit(c.list, wxid, nick)` |

兜底层 `MomentsLogic.listHit`（`MomentsLogic.kt:834-853`）：

| 行号 | 形态 | 比对方式 |
|---|---|---|
| `:842` | 名单项 == **wxid** | `e.equals(w, ignoreCase = true)` |
| `:843` | 名单项 == **昵称** | `e.equals(n, ignoreCase = true)` |
| `:844-850` | 名单项形如 `名字(wxid)` | 拆 `(` 后，后半比 wxid（`:848`）、前半比昵称（`:849`） |

**比对口径的确切答案：**

- **是 `String.equals`，全程没有一个 `contains`**（`:842` `:843` `:848` `:849` 四处全 `equals`；`contains` 只在 `MomentsLogic` 的 CDN/md5 画像链路里出现，与本判定无关）。
- **normalize 只有 `.trim()` 一处**：`MomentsExport.kt:470`（作者侧）、`ListResolver.kt:86`（名单侧，由 `:95` 入集合）、`MomentsLogic.kt:840`（兜底层名单侧）。**没有**去前缀、没有全角/半角折叠。
- **大小写**：`:471` 的 `c.ids` 走 `Set.contains`，**区分大小写**；`:842/:843` 兜底层 `ignoreCase = true`。两者差异实际无影响 —— wxid 恒为小写，`GridDb.SnsRow.userName` 取的是微信原列，也是小写。
- **昵称也认**（`:843`），所以「名单里存昵称」同样能命中。**不存在「必须存 wxid」或「必须存昵称」的单向依赖。**

⇒ **Q1 答案 OK**：名单口径没有「只认昵称」的错位。把 3 个裸 wxid 放进名单，在 `:471` 这一层就能直接命中。**这条不是根因。**

---

## Q2：名单是怎么被读进来的？

### Q2-1 读取路径

| 环节 | 行号 | 内容 |
|---|---|---|
| Key 定义 | `Keys.kt:223` | `const val MOMENTS_EXPORT_LIST = "moments.export_list"` |
| PrefSpec | `Keys.kt:394` | `PrefSpec(MOMENTS_EXPORT_LIST, PrefKind.ENTER, "[]", "朋友圈", "设置自动导出名单")` ⇒ **默认值 `"[]"`** |
| 唯一读者 | `MomentsExport.kt:384` | `val raw = Kv.getList(Keys.MOMENTS_EXPORT_LIST)`（在 `refresh()` `:383` 里） |
| 解析 | `Kv.kt:44-49` | `getString` → `gson.fromJson<List<String>>` → `.filter { isNotBlank() }` |
| 展开 | `MomentsExport.kt:387` → `:434-448` | `resolveList(raw)` |
| 写进 Config | `MomentsExport.kt:390-399` | `list = raw`（`:393`）、`ids = resolved.ids`（`:394`）、`listUnrestricted`（`:395`）、`listImpossible`（`:396`） |
| 有效期 | `MomentsExport.kt:206 / :451` | `CFG_TTL_MS = 3_000`，超 3 秒 `cfg()` 自动 `refresh()` |

### Q2-2 「读不到就用默认 `[]`」的分支：**有，共 4 处**

| 行号 | 触发条件 | 返回值 |
|---|---|---|
| `Kv.kt:29` | `GroupDb.current() == null`（模块自己的 kv 库还没就绪） | `Prefs.defaultOf(key)` = `"[]"` |
| `Kv.kt:31` | 查询抛异常 | `Prefs.defaultOf(key)` = `"[]"` |
| `Kv.kt:46` | 读到的串 `isBlank()` | `emptyList()` |
| `Kv.kt:48` | JSON 解析失败 | `emptyList()` |

### Q2-3 ⭐ 但「实际生效值是空」这条假设 —— **被观测数据直接证伪**

反证链（两步都指向同一个矛盾）：

1. 若 `raw` 为空 ⇒ `ListResolver.Resolved.unrestricted = true`（`ListResolver.kt:56`：`raw.isEmpty()`）
2. ⇒ `listHitResolved:468` 对所有行 `return true` ⇒ **`written` 应该 ≈ 500**，而不是 0
3. 而且 `autoBlockedByEmptyList`（`MomentsExport.kt:605-616`）会在 `:495`（`onTick`）把整轮**拦掉并打 WARN**「导出名单为空」；观测到 rows=500 说明轮确实跑了。
   - 手动路径（`:597` KDoc 说手动走 `runAsync` 不受此阀管）也救不了这个假设：空名单时 `listHit` 依然对全体返回 true ⇒ 还是 written≈500。

⇒ **名单在运行进程里是非空的**，那 3 个 wxid **就是实际生效值**（至少同非空）。
⇒ 主会话「`op=kvget` 读不到 ⇒ 可能读成了空」这条担心**不成立**；`kvget` 读不到是探针侧问题，处置见 Q4-A0。

### Q2-4 `g:` / `t:` 前缀展开：**有，且裸 wxid 原样保留 ⇒ 记忆里那条硬结论在当前代码上已修（OK）**

| 环节 | 行号 | 内容 |
|---|---|---|
| 前缀常量 | `ListResolver.kt:40 / :43` | `PREFIX_GROUP = "g:"`、`PREFIX_LABEL = "t:"` |
| 展开主体 | `ListResolver.kt:78-99` | `:90-91` 分组、`:92-93` 标签、**`:95` `else -> add(ids, id)` 裸 wxid 原样进集合** |
| 入口 | `MomentsExport.kt:434-448` | `resolveList()`；`:435-438` 判定要不要查库 |
| 快捷路径 | `MomentsExport.kt:439` | `if (!needBuckets) return ListResolver.expand(raw)` —— 纯 wxid 名单**完全不碰 DB** |

⇒ 本例名单是 3 个裸 wxid，`needBuckets=false`，走 `:439`，`ids` 拿到 3 个元素 ⇒ `unrestricted=false`、`impossible=false`。**这条 OK。**

### Q2-5 ⚠️ 残留陷阱（**本次未触发**，记录备查）

`MomentsExport.kt:387-389`：

```kotlin
val resolved = runCatching { resolveList(raw) }
    .onFailure { LogX.w(...) }
    .getOrDefault(ListResolver.Resolved(raw = raw))   // ← ids 默认空集
```

若 `resolveList` 抛异常 ⇒ `raw` 非空而 `ids` 空 ⇒ `impossible = true`（`ListResolver.kt:59`）⇒ **`listHitResolved:469` 对全体 return false ⇒ 症状恰好是 `written=0 listMiss=rows`**。

当前不可达：`resolveList` 内部的 `:441-446` 全包了 `runCatching`、`:447` 的 `ListResolver.expand` 自身不抛。**判 OK，但建议给 `:389` 的兜底加一行 warn**（现只在 `:388` 打，`getOrDefault` 这条静默路径值得留痕）。

---

## Q3：作者字段取的是什么？

### Q3-1 答案：**作者 = `SnsInfo.userName`（wxid），昵称只是兜底**

行类型 `GroupDb.SnsRow`（`GroupDb.kt:394-409`），`userName: String?` 在 `:396`。两条数据源：

| 源 | 读取函数 | userName 来源 |
|---|---|---|
| ① CURRENT（主） | `SnsGuard.listCurrentSns` `SnsGuard.kt:798-815`，SQL 在 `:806`：`select snsId,userName,createTime,type,sourceType,localFlag,content from $TABLE …` | `liveRows` `SnsGuard.kt:817-857`，**`userName = cText(cur, 1)` 在 `:839`** —— 按 select 列表**下标 1** 取，`SnsInfo.userName` **原列**（`:803` 注释「下标即契约」） |
| ② ARCHIVE（补） | `GroupDb.listSnsArchiveBefore` `GroupDb.kt:478-491` | `Cursor.toSnsRow()` `GroupDb.kt:1479-1494`，**`userName = getString(getColumnIndexOrThrow("user_name"))` 在 `:1481`**（注意归档列名是 `user_name` 蛇形） |

昵称：`nickOf`（`MomentsExport.kt:917-931`）→ `GroupDb.current()?.findFriend(w)`（`:921`）查 friend_cache，**备注优先于昵称**（`:924-928`），查不到返回 `null`。

### Q3-2 ⚠️ 用户点名的坑 —— **不存在**

> 「如果比对用的是昵称（`ImproveInfo.name`）而名单里是 wxid ⇒ 恒 miss」

**全文件零命中**：`MomentsExport.kt` 里 `ImproveInfo` 一次都没出现（grep `ImproveInfo` 在该文件 0 结果）。参与比对的是 `row.userName`（wxid，`:781`）与 `nickOf` 产出的 **备注/昵称**（`:780`），且顺序是 **wxid 优先**（`:471` 先过 `c.ids`）。**这条 OK，不是本次的坑点。**

### Q3-3 「这条动态要不要导出」的完整判定链（输入 → 出口）

| # | 行号 | 判定 | 不通过时计入 |
|---|---|---|---|
| 0 | `:743-754` | 取一页（CURRENT 反射查微信 sns.db / ARCHIVE 查 `GroupDb`）；CURRENT 返回 null ⇒ 降级到 ARCHIVE（`:756-764`） | — |
| 1 | `:776` | `cur.first(row.snsId, exported)` —— 磁盘已导出 / 本轮已处理 ⇒ 跳过 | `sk` |
| 2 | `:780` | `nick = nickOf(row.userName, nickCache)` | — |
| 3 | **`:781`** | **`listHitResolved(c, row.userName, nick)`** —— ① `listUnrestricted`→true ② `listImpossible`→false ③ `wxid.trim() in c.ids` ④ `listHit(list, wxid, nick)` 三种字面形态 | **`lm` → `listMiss`** |
| 4 | `:787` | 广告黑名单 `MomentsLogic.isAd(sourceType)`（bit5=32，**不是 128**，见 `Keys.kt:226-230`） | `ad` → `adSkipped` |
| 5 | `:791-800` | `mkdirs` | `er` → `errs` |
| 6 | `:801-807` | 目标 txt 已存在 ⇒ 去重跳过 | `sk` |
| 7 | `:808-819` | 可用空间 < `MIN_FREE_BYTES` ⇒ abort 整轮 | — |
| 8 | `:821-852` | `tmp.writeText` + `renameTo`（原子落盘） | 成功 `w`+`written`，失败 `er` |
| 出口 | `:856-858` / `:878-879` | `skipped / listMiss / adSkipped` 累加；`return "$TAG export done rows=$rows written=$w skipped=$sk listMiss=$lm adSkipped=$ad err=$er root=$root"`（由 `:527` 打日志） | — |

**输入小结**：`(row.userName = SnsInfo.userName 或 sns_archive.user_name, nick = friend_cache 备注/昵称)` × `(c.list = 原名单, c.ids = 展开后 wxid 集合)`。

### Q3-4 ⚠️ 真正的结构性短板（不是 bug，是取证盲点）

`:730/:731` 声明 `curRows` / `archRows`，`:770` 累加，**但全文件再无消费点**（grep 只命中这 3 行）⇒ **`export done` 这行日志看不出那 500 行来自 CURRENT 还是 ARCHIVE**。要判断归档侧 `user_name` 是否有空值（Q4 的假设 B），目前**离线做不到**。

---

## ⭐ Q3 补：最贴合「26 → 0」的解释 —— 扫描窗口只有「最新 500 行」

| 行号 | 事实 |
|---|---|
| `MomentsExport.kt:223` | `private const val MAX_ROWS_PER_RUN = 500` |
| `:734` | `while (rows < MAX_ROWS_PER_RUN && !stop)` —— **单轮上限就是 500，`rows=500` 是撞上限，不是数据只有 500 条** |
| `:719` | 每轮 `val cur = SnsScanCursor()` —— **全新游标** |
| `SnsScanCursor.kt` `:117` | 默认 `ct = Long.MAX_VALUE` —— **每轮都从最新一条往回扫** |
| `:1307`（`backfillImages` KDoc） | 「主循环每轮都从最新开始 —— 新动态必须优先」；跨轮持久游标 `Keys.MOMENTS_FILL_CURSOR`（`:1319-1322`）**只有补图那一轮用** |
| `:1292` | `backfillImages` **只补图、不写 txt** |

⇒ **唯一能写 txt 的主循环，视野永远只有「最新 500 条」。**
⇒ 这 3 个人一旦其最新动态被新内容挤出「最新 500 条」，**就再也不会被导出**（且磁盘去重只在写过的范围内生效，退回窗口内也不会补）。
⇒ R26 时他们的最新动态在窗口内（26 条命中），现在被挤出去了（0 条命中）。**与 474→499 的幅度完全自洽。**

---

## Q4：怎么验（离线可判的取证步骤）

> ⛔ 以下命令均为「真机时该跑什么」，本次**一条都没跑**。

### A0. 先把 `kvget` 读不到这件事修对（0 代码改动）

`op=kvget` 实现在 `EntryReceiver.kt:847-864`：
- ACTION = `com.lx.wxgroup.action.PROBE`（`EntryReceiver.kt:63`），op 的 extra 名 = **`op`**（`EntryReceiver.kt:67`）
- **单个 key 的 extra 名是 `key`，不是 `val` / `sql`**（`:848`）
- **无白名单**（`:849-859` 直接拿 `--es key` 包成 list）⇒ `moments.export_list` **本来就可读**

```
adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op kvget --es key moments.export_list
```
期望：`PROBE kv moments.export_list = ["wxid_xij8e08lqr3e22","wxid_faryuijzyxj022","wxid_l2ocidx1m40021"]`，且**没有尾部 `(未登记)`**（带 `(未登记)` ⇒ `Prefs.spec(k)==null`，说明 key 拼错）。

⚠️ **假阴陷阱**：`Kv.getString` 经 `Kv.kt:29`，`GroupDb.current()==null` 时直接返回 spec 默认值 `"[]"`（= `Keys.kt:394` 的 `"[]"`）。读到 `[]` **不能**推出「配置为空」—— 要结合 Q2-3 的反证一起看。

### A1. 这 3 个 wxid 在 `SnsInfo` 里到底有没有动态

```
adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op sql --es q \
  "select userName, count(*) c, max(createTime) mx from SnsInfo where userName in ('wxid_xij8e08lqr3e22','wxid_faryuijzyxj022','wxid_l2ocidx1m40021') group by userName"
```
（`op=sql` 入口在 `EntryReceiver.kt:877`，实际执行 `LabelHuntProbe.runSql` `LabelHuntProbe.kt:478-500`；只放行 `select/explain/只读 pragma`（`:466-476`）；**对当前所有已捕获的 DB 实例逐个跑**（`:489-499`），所以只有微信最近开过 sns.db 才有 rows。另外 `:489` 之前要先 `sub=on` 开捕获窗口，否则会直接回 `no db instance`（`:490-492`）。

判读：
- **`rows=0`** ⇒ ① 这三个作者在 SnsInfo 里一条都没有，或 ② sns.db 没被捕获。先用 `select count(*) from SnsInfo` 判 ②（有 rows>0 才是 ①）。
- **`rows=1~3`** ⇒ 有动态，继续 A2 看是不是在窗口内。

### A2. ⭐ 唯一判据：窗口内命中数 `hit500`

把「最新 500 行的窗口下界」直接写进同一条 SQL（`order by createTime desc, snsId desc limit 1 offset 499`）：

```
adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op sql --es q \
  "select count(*) hit500 from SnsInfo t where t.userName in ('wxid_xij8e08lqr3e22','wxid_faryuijzyxj022','wxid_l2ocidx1m40021') and t.createTime >= (select createTime from SnsInfo order by createTime desc, snsId desc limit 1 offset 499)"
```
（窗口口径与主循环完全同源：`SnsGuard.kt:806-808` 的 `order by createTime desc, snsId desc limit`，游标语义见 `MomentsExport.kt:130-133`。）

### A3. 排除「作者字段取空」这条链路（区分假设 B）

```
... --es q "select count(*) total, sum(case when userName is null or userName='' then 1 else 0 end) bad from SnsInfo"
... --es q "select count(*) total, sum(case when user_name is null or user_name='' then 1 else 0 end) badu from sns_archive"
```
判读：两处 `bad` 都 = 0 ⇒ 假设 B 排除（走 `:470`+`:838` 恒 false 的路没了）；任一处 > 0 ⇒ 命中 B，(B) 就是真凶。

### A4. 「名单问题」vs「代码 bug」的**唯一区分判据**

> **只看 A2 的 `hit500`：**
> - **`hit500 = 0`** ⇒ **名单问题**（含「作者已挤出每轮只扫最新 500 行的窗口」）：名单非空、比对链认 wxid、作者字段是 wxid 三点已由 Q1/Q2/Q3 锁死，`listMiss=499` 与代码完全自洽 ⇒ **代码零改动**。
> - **`hit500 > 0`** 却仍 `written=0 & listMiss=499` ⇒ **代码 bug**。此时按次序只查三处（都有行号）：
>   1. `MomentsExport.kt:469` `listImpossible` —— 唯一能把非空名单变成全 false 的开关，只可能由 `:389` 的异常兜底触发（`getOrDefault` 那条**不打自己的日志**）
>   2. `MomentsExport.kt:471` `w in c.ids` —— Set `contains` **区分大小写** + 只 trim
>   3. `MomentsExport.kt:451` `cfg()` 的 3 秒 TTL / `refresh()` `:383` —— 名单读取是否 stale（理论上撑不到 500 行）

   ⇒ 判据不需要编译、不需要 hook，**一条 SQL 定案**。

### A5. snsId 大数坑：**SQL 里一律不许出现超过 2^63-1 的数字字面量**

背景（`docs/conclusions/moments.md:17`，并被 `docs/_r18_snscomment_backup/SnsCommentLogicTest.kt:23-24` 用真机常量钉住）：

| 表示 | 值 |
|---|---|
| 库里有符号 | `-3442401033659805004` |
| 无符号十进制（UI / `ImproveInfo.snsID` 里的形态） | `15004343040049746612` |

⚠️ SQLite 会把 `15004343040049746612` 当 **REAL** 解析 ⇒ 精度丢 ⇒ `where snsId = 15004343040049746612` **恒 rows=0**。

**推荐写法：完全不出现数字 —— 用 `CAST(snsId AS TEXT)` 比字符串**（`CAST(INTEGER AS TEXT)` 精确无损）：

```sql
select snsId, userName, createTime from SnsInfo
where CAST(snsId AS TEXT) = '-3442401033659805004'
```

无符号 → 有符号的离线换算（已用上面那对常量验过，逐位相符）：

```python
u = 15004343040049746612
s = u - 2**64 if u >= 2**63 else u      # -> -3442401033659805004  ✓
```

⛔ 别在 shell 里用手算十进制，`android.util-ish` 的 `--el` 也传不了无符号 64（`docs/conclusions/comment.md:534-535` 已证过：超 `Long.MAX_VALUE` 直接 `NumberFormatException`）。

---

## 根因块

**根因 = 未定，但排除法后只剩「数据问题」一条活路 —— 倾向「名单内作者已掉出主循环每轮只扫最新 500 行的窗口」；一行 SQL（`hit500`）即可钉死。**

排除掉的项（逐条都有行号）：

| 假设 | 结论 | 依据 |
|---|---|---|
| 名单比对只认昵称、存 wxid 必然全 miss | **否** | `MomentsLogic.kt:842` 直接 `e.equals(w)` 比 wxid；`MomentsExport.kt:471` 先比 `c.ids`。两种形态都认 |
| 用 `contains` 或 normalize 出错 | **否** | 四处全 `equals`（`:842/:843/:848/:849`），normalize 只有 `trim` |
| 比对用 `ImproveInfo.name` 与名单错位 | **否** | `MomentsExport.kt` 里 `ImproveInfo` 零引用；作者取 `SnsInfo.userName`（`SnsGuard.kt:839`） |
| 名单被读成了空 | **否（被观测反证）** | 空 ⇒ `unrestricted`（`ListResolver.kt:56`）⇒ `:468` 全 true ⇒ `written≈500`；且 `:605` 安全阀会拦下整轮。实测 `written=0` 且轮跑了 |
| `g:`/`t:` 不展开 | **否** | `ListResolver.kt:95` 裸 wxid 原样进集合；`:439` 快捷路径纯 wxid 名单不查库 |
| 近期代码回归 | **否** | `MomentsExport.kt`/`MomentsLogic.kt` 最后一次改动 = `f231af2`（R24-R29），`d351b11`（R34）没碰 |

活着的候选，按可能性排序：

1. **〔最可能〕数据/窗口**：`MAX_ROWS_PER_RUN=500`（`:223`）+ 主循环每轮从最新重扫（`:719`/`:117`）⇒ 这 3 人的动态一旦老化出窗口，**永久**导不到（补图轮 `:1292` 不写 txt）。与 `26 → 0` 完全自洽。
2. **〔次〕作者字段空值**：`SnsInfo.userName` 或 `sns_archive.user_name` 为 null/空 ⇒ `:470` 的 `w` 空 + `nick` null ⇒ `MomentsLogic.kt:838` `return false`。由 A3 一票否决。
3. **〔最低〕`listImpossible` 被异常兜底点亮**：`MomentsExport.kt:389`，当前不可达。
4. **〔未排除-取证缺口〕**：500 行到底来自 CURRENT 还是 ARCHIVE —— `curRows/archRows` 算了不打印（`:730/:731/:770`），离线判不了。

### 下一步（按代价从小到大）

1. **跑两条只读广播**（0 改动，不重装包）：A0 的 `kvget`（把 `[]` 这种假阴堵掉）+ **A2 的 `hit500`**。这是唯一必须先做的事，其余都依赖它的结果。
2. **若 `hit500=0`**（数据问题）：三选一 ——
   - (a) 代价最小：把「导出名单内作者若不在最新 500 条内则不会导出」写进 UI/文案，让用户知道要手动触发或让对方有新动态；
   - (b) 给主循环加跨轮持久游标，复用 `backfillImages` 那套 `Keys.MOMENTS_FILL_CURSOR` 的思路（`MomentsExport.kt:1302-1307`）；⚠️ 但 `:1307` 明确写了「主循环每轮都从最新开始 —— 新动态必须优先」，动之前要先过这个语义；
   - (c) 手动导出路径放开 `MAX_ROWS_PER_RUN`。
3. **若 `hit500>0`**（代码 bug）：按 A4 的三条顺序查 `:389` → `:471` → `:451`。优先给 `:389` 的 `getOrDefault` 兜底补一行 warn（现只在 `:388` 打，静默路径无痕）。
4. **顺手修取证盲区**（代价最小、收益最高）：把 `:878` 的 `export done` 行补上 `cur=$curRows arch=$archRows`（两值已在 `:770` 算好，纯打印改动）⇒ 下次同类问题第一行日志就能分出 CURRENT/ARCHIVE。

### 严重度

**P1**（理由：对该用户而言功能**静默全失效** —— 配了 3 个人、`written=0`、日志里只有 `listMiss` 在涨，没有任何一行指向「窗口」或「名单」，用户无从察觉。非崩溃、非数据损坏，故不到 P0。）
→ 若 A2 确认 `hit500=0` 且属于纯时效性（对方新发一条就恢复），**降 P2**；若确认 `hit500>0`，**升 P0**（判定链有真 bug）。

---

[DONE]
