# R20b · SNS 反射级读库通路 独立复核

> 复核对象：`SnsGuard.kt` 的 `listCurrentSns` / `liveRows` / `rawQueryMethod` / `snsDb` 捕获链，
> 以及 `MomentsExport.kt` 的双源扫描段。
> 复核人 lane：`sns-live-review`。纯只读，未改任何 .kt / 未跑 gradle / 未碰 adb。

---

## §0 结论

**PASS —— 可上真机。阻断项 = 0。非阻断建议项 = 1（额外项，见 §1-8）。**

七条必查项全部 PASS，反射读库通路的正确性由 R19 真机输出（`_r19_a4_probe.md` 的
`SNSHIT snsId=-3407160091643670016 ids=1 hit=1`）**直接背书**：`getLong` 装箱匹配、
`rawQuery` 反射、负值 64 位 snsId 读出、键集分页首轮 `Long.MAX_VALUE` 全部在真机上
已经跑通过。唯一要改的是「额外项」——一处把「正常降级」与「反射异常」混成同一个
`null` 且吞掉异常的无日志问题，属可观测性缺陷，不阻断功能。

---

## §1 逐条审查

### 1. `rawQueryMethod` 的 `Array<Any>` 分支 —— **PASS**

- 判定：**不是缺陷，是可用的防御性代码**。
- 证据：`SnsGuard.kt:480-489`。
- 分析：
  - hook 目标类明确是 `com.tencent.wcdb.database.SQLiteDatabase`（`SnsGuard.kt:86-90`），
    **不是** `android.database.sqlite.SQLiteDatabase`。任务里「AOSP rawQuery 只有
    `(String,String[])`」的前提不适用于 wcdb。
  - `rawQueryMethod` 用 `db.javaClass.methods`（= `Class.getMethods()`，含继承的 public 方法）
    找 `rawQuery`，只认两种二参形态：`(String,String[])` 或 `(String,Object[])`。
    wcdb 的 `rawQuery` 是 `(String,String[])`；`Object[]` 变体是 wcdb 在
    `execSQL/delete/update` 那一族才有的签名。作者把两种都兜了。
  - 关键：**无论命中哪种，`listCurrentSns` 里的分支（`:538-542`）都与
    `rawQueryMethod` 的过滤条件自洽**——`m.parameterTypes[1]` 只可能是 `String[]` 或
    `Object[]` 之一，`else` 分支覆盖 `Object[]`，不会错配。
  - 真机背书：R19 的 `probeHit` 走的是同一条 `rawQueryMethod(db)`（`SnsGuard.kt:819`），
    拿到合法 Method 并产出 `SNSHIT` 结果 ⇒ `rawQueryMethod` 在真机上**不返回 null**，
    取到的是 `String[]` 分支。
- 结论：`Array<Any>` 分支在 wcdb 的 rawQuery 场景下是死代码，但无害且正确，不构成
  静默降级风险。

### 2. `XposedHelpers.callMethod(cur, "getLong", i)` 装箱匹配 —— **PASS**

- 判定：**能匹配到 `Cursor.getLong(int)`，整条通路不会因此恒返回 0 行**。
- 证据：`SnsGuard.kt:593-595`（`cLong`）、`:436`（`readRow`）、`:834`（`probeHit`）。
- 分析：
  - `i: Int` 传给 `callMethod(Object, String, Object... args)` 时被 Kotlin 装箱成
    `java.lang.Integer`。`callMethod` 内部走 `findMethodBestMatch`（不是 `findMethodExact`）：
    先 `findMethodExact(clazz,"getLong",Integer.class)` 失败（无 `getLong(Integer)`），
    被 `catch (NoSuchMethodError)` 吞掉后，遍历 `clazz.getMethods()` 用
    `ClassUtils.isAssignable(parameterTypes, method.getParameterTypes(), allowSubclasses)`
    匹配；`allowSubclasses` 默认 `true`，而 Xposed 的 `ClassUtils.isAssignable`（Apache
    Commons Lang3 语义，autoboxing=true）会把 `Integer` ↔ `int` 互转，`int.class` 命中。
  - **决定性真机证据**：`_r19_a4_probe.md:156-161` 的
    `SNSHIT snsId=-3407160091643670016 / -3407175466649382227` 就是
    `XposedHelpers.callMethod(cur, "getLong", 0)`（`:834`）读出来的——`0` 是 `Int` 字面量、
    同样装箱成 `Integer`，读出了**完整的负 64 位值**。若装箱匹配失败，这里只会得到 0。
- 结论：装箱匹配无问题，且有真机负值 snsId 作为铁证。

### 3. `VISIBLE_BIT` 判据 `(localFlag & 16) != 0` —— **PASS**

- 判定：**白名单每个值都置了 bit4，KDoc 没算错**。
- 证据：`SnsGuard.kt:290`（`VISIBLE_BIT=16`）、`:505-508`（KDoc 白名单说明）。
- 逐值验算：

  | 白名单值 | 二进制 | bit4(16) |
  |---|---|---|
  | 16  | `0b0001_0000` | ✓ |
  | 2064 | `0b1000_0001_0000`（2048+16） | ✓ |
  | 18  | `0b0001_0010`（16+2，bit1+bit4） | ✓ |
  | 2066 | `0b1000_0001_0010`（2048+16+2） | ✓ |
  | 24  | `0b0001_1000`（16+8，bit3+bit4） | ✓ |
  | 2072 | `0b1000_0001_1000`（2048+16+8） | ✓ |

  - `localFlag=2`（`0b0010`，微信删动态第一步清掉可见位）：`2 & 16 == 0` ⇒ 排除 ✓。
  - `localFlag=0`：`0 & 16 == 0` ⇒ 排除 ✓。
- 唯一措辞瑕疵：KDoc 写「**等价**超集」（`:507`）。按位判实际上是白名单的**真超集**——
  会连带纳入「bit4 置位但不在白名单」的行（如 `localFlag=48=16+32`）。对「预防性留存」
  语义这是**多留不少留**，无害，但措辞宜改为「超集」以免误读成严格等价。
- 结论：算术正确，语义安全（不会漏可见行）。

### 4. `snsDb` 捕获时机 × 降级永久性 —— **PASS**

- 判定：**降级不是永久的**，下一轮会重建游标并重试 CURRENT 源。
- 证据：
  - 游标是**局部变量**：`MomentsExport.kt:436` `val cur = SnsScanCursor()`，位于
    `runBlocking()` 函数体内（每轮调用新建），不是 object 级字段。`SnsScanCursor` 类
    （`:100-149`）的 `src` 初始即 `Src.CURRENT`（`:105`）。
  - `snsDb` 一经捕获**不再复位**：全仓唯一赋值点在 `SnsGuard.kt:276`（`isSnsDb` 命中），
    声明处 `:78` 为 `null`，grep 确认**没有任何 `snsDb = null` 的复位**。捕获钩子
    `installCapture`（`:322-333`）拿到即 `unhookCapture()`，`execSQL` 路径（`:199-205`）
    也顺带捕获。
- 结论：每轮 `runBlocking()` 都以 `src=CURRENT` 起步，`snsDb==null` 只会让**当轮**降级
  为只扫归档；一旦微信碰过 SnsInfo（进朋友圈/刷新），`snsDb` 被捕获并**常驻到进程结束**，
  之后每轮都能扫到当前源。无永久失效。

### 5. `liveRows` 的 `guard < 5_000` 上限 —— **PASS**

- 判定：**当前不会触发，将来调大 `PAGE` 也不会静默截断**。
- 证据：`SnsGuard.kt:559`（`while (guard++ < 5_000 && ...)`）、`:530`
  （`limit.coerceIn(1, 200)`）、`MomentsExport.kt:172`（`PAGE=100`）。
- 分析：`liveRows` 的 SQL 尾缀是 `limit $lim`，而 `lim` 在 `listCurrentSns` 里被
  `coerceIn(1, 200)` **硬钳到 ≤200**（`:530`）。所以即便把 `PAGE` 调到 500，单页也最多
  返回 200 行，`guard<5_000` 永远够不到——它是一道**不可能触发的兜底**，而非会产生
  静默截断的限流。真正的上限是 `coerceIn(1,200)`，它本身是文档化的显式钳位，不是 bug。
- 结论：无静默截断风险。

### 6. 复合游标首轮 `Long.MAX_VALUE` —— **PASS（有一处注释未写明）**

- 判定：**「漏一行」理论上存在、现实中不可能，可接受；但代码注释没把这个边界写明**。
- 证据：`SnsGuard.kt:534-537`（SQL）、`:519-521`（KDoc）。
- 分析：SQL `createTime < ? or (createTime = ? and snsId < ?)`，首轮三参全传
  `Long.MAX_VALUE`。唯一漏掉的行是「`createTime == Long.MAX_VALUE` **且**
  `snsId == Long.MAX_VALUE`」。真机 snsId 是负值雪花 id（`-3447438253133393222`），
  createTime 是秒/毫秒级时间戳，二者都到不了 `Long.MAX_VALUE` ⇒ 这行不可能存在。
  KDoc（`:519-521`）解释了「为什么用复合游标」（防页边界丢同一秒的行），但**没有**把
  「首轮 MAX/MAX 理论上漏一行」这层边界写出来。归档侧 `GroupDb.listSnsArchiveBefore`
  （`GroupDb.kt:428-441`）同样没写，属全仓一致的既有缺口。
- 结论：可接受（实践上不可能），建议补一句注释说明「MAX/MAX 行理论不可达」。

### 7. `liveRows` 的 `id == 0L continue` —— **PASS**

- 判定：**「0 = 读失败」约定全仓一致，不会误丢真实行**。
- 证据：`SnsGuard.kt:562-565`（`if (id == 0L) continue`）、`GroupDb.kt:368`
  （`if (row.snsId == 0L) return false`）。
- 分析：两处都把 `snsId == 0` 当作「没读出来/无效」处理，约定一致。真实 `snsId=0`
  的行在 SnsInfo 表里不存在（snsId 是服务端雪花 id，主键，恒非零）。因此跳过是安全的。
  - ⚠️ 附带说明：`continue` 本身**无日志无计数**，如果 `cLong` 将来因某种原因恒返回 0，
    这里会把所有行静默跳过、`liveRows` 恒空。但这是「如果 cLong 坏掉」的假设态，
    当前 `cLong` 已被真机证伪为正常（见 §1-2）。
- 结论：约定一致，无缺陷。

### 8.（额外）`runCatching { listCurrentSns(...) }.getOrNull()` —— **FAIL（非阻断）**

- 判定：**不可接受**（违反项目「禁止静默吞」硬规矩），但**不阻断上真机**，给最小改法。
- 证据：`MomentsExport.kt:451-456`（`runCatching { SnsGuard.listCurrentSns(...) }.getOrNull()`）、
  `:457-466`（`page == null` 分支）。
- 问题：`listCurrentSns` 的正常降级返回 `null` 与「反射/查询抛异常」被混成**同一个 null**，
  异常被 `runCatching` 吞掉后**零日志**。且 `page == null` 分支打的日志是
  `source=current UNAVAILABLE (sns db not captured) -> archive only`（`:462`）——
  若 null 实际来自异常，这条日志是**误导**的（把「查询坏了」说成「句柄没拿到」）。
- 缓解因素（为何不阻断）：`listCurrentSns` 内部已层层防御——`snsDb==null` 返回 null、
  `rawQueryMethod` 自己 `runCatching`、`liveRows` 里 `m.invoke` 的失败**已有**
  `LogX.w("SNS live query failed ...")`（`SnsGuard.kt:550-555`）留痕。所以现实里
  `listCurrentSns` 几乎不可能向外抛异常，外层 `runCatching` 基本吃不到东西。
- 最小改法（二选一）：
  1. 保留降级行为、补日志：
     ```kotlin
     SnsScanCursor.Src.CURRENT ->
         runCatching { SnsGuard.listCurrentSns(cur.ct, cur.sid, PAGE) }
             .onFailure {
                 LogX.w("$TAG export source=current threw ${it.javaClass.simpleName}: " +
                     "${(it.cause ?: it).message?.take(80)}")
             }
             .getOrNull()
     ```
  2. 若想进一步区分两种 null 的来源，把 `listCurrentSns` 的「句柄未捕获」与「查询异常」
     分别用可区分的返回/日志信号表达（当前 `liveRows` 已经对 invoke 失败打日志，
     只需保证外层不再吞掉其余异常即可）。

---

## §2 静默失败风险清单

以下失败模式**不报错、不崩溃、日志不显或显错**，按危险程度排序：

| # | 失败模式 | 症状 | 是否有日志 | 当前状态 |
|---|---|---|---|---|
| 1 | `cLong` 的 `getLong` 反射失败（`runCatching` 吞异常→返回 0L，`:593-595`） | 所有 `id==0` → `liveRows` 恒空，`curRows=0` | **无**（cLong 完全静默） | 当前被 §1-2 真机证伪不触发；但它是整条链路**唯一**能把「读失败」伪装成「0 行」的点 |
| 2 | `rawQueryMethod` 返回 null（`:480-489`，若将来 wcdb 改 rawQuery 签名） | `listCurrentSns` 返回 null → 降级只扫归档 | **无** | 当前不触发（§1-1 已证） |
| 3 | 句柄未捕获 `snsDb==null` | `listCurrentSns` 返回 null → 降级只扫归档，摘要 `rows=0 written=0` | 有 `curNoDb` 一行（`MomentsExport.kt:460-463`） | 预期降级，但**极易误判成「没有动态」**，是现场排查第一大坑 |
| 4 | 额外项：外层 `runCatching` 吞异常 | 与 #3 同症状，且日志误导成「sns db not captured」 | 有，但**内容错误** | §1-8 建议修 |
| 5 | `liveRows` 的 `m.invoke` 抛异常 | 返回空 list，上层当「这一页空」切源 | 有 `LogX.w("SNS live query failed")`（`:550-555`） | 不算静默，但空页会被当成「扫完」，注意区分 |
| 6 | `(localFlag & 16) != 0` 是白名单超集 | 多导出几条 bit4 置位但不在时间线白名单的行 | — | 非缺陷（多留不少留） |
| 7 | `lim.coerceIn(1,200)` 硬上限 | 将来调 `PAGE`>200 单页被静默压到 200 | 无 | 当前 PAGE=100 不触发 |

**最危险的是 #1**：它是唯一能让「读失败」表现为「正常导出但 0 行」且零日志的路径。
其余路径要么有日志、要么被真机证伪。

---

## §3 上真机前必须做的验证动作（给主会话）

按顺序执行，每步附判据：

1. **冷启动、未进朋友圈前**跑 `op=moments sub=export`。
   判据：日志出现 `source=current UNAVAILABLE (sns db not captured) -> archive only`。
   证：捕获钩子已装、降级路径正确。

2. **进一次朋友圈**（触发 rawQuery/execSQL）。
   判据：日志出现 `SNS sns db captured via rawQuery/execSQL`。
   证：句柄捕获成功，`snsReady()` 变 true。

3. **再跑** `op=moments sub=export`。
   判据：摘要 `rows>0 written>0`，且**不再**出现 `source=current UNAVAILABLE`；
   对账变量 `curRows`（当前源扫到行数）**> 0**。
   证：反射读库通路端到端打通——这是 §1 全部 7 条的共同验收点。

4. **判据性冒烟**：`op=snsg sub=hit --ei n 10`。
   判据：`SNSHIT sum rows=10 ...`，且每行 `snsId=` 是**负的 64 位值**。
   证：`getLong` 装箱匹配 + `rawQuery` 反射 + 负值 Long 读出三项一次验全。

5. **反证「静默 0 行」**：若 export 摘要 `rows=0 curRows=0 archRows=0`，
   **先查有没有** `source=current UNAVAILABLE`——有 = 句柄没拿到（非链路坏）；
   无 = 真异常，去查 `SNS live query failed` / `SNS read err` 等 w 日志。

6. **确认无错误日志**：全程**不应**出现 `SNS live query failed`、`SNS read err`、
   `SNS getPath failed`。任何一条出现即代表某处反射失败被兜住，必须查根因后再上 M1。

7. **日志长度**：注意单行 295 字符硬上限（R19 实测），命中文件名/路径过长会被截断，
   对账时别把「截断」误判成「没输出」。

8. **（建议，非必须）**修掉 §1-8 的 `.onFailure` 日志，避免未来把「查询异常」误读成
   「句柄未捕获」。

---

[DONE]
