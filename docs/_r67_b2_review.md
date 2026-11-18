# R67 · b2 实现独立复核报告

> 角色：r67-l3-review（**独立复核**，只读）。本轮**未改任何 app/src/main 源码、未跑 gradle、未碰 adb/设备**；构建锁/设备锁归主会话。
> 复核对象（b2 刚落盘实现）：
> - 新增 `wxgroup/app/src/main/java/com/lx/wxgroup/feature/NativeConvInsert.kt`
> - 改 `wxgroup/app/src/main/java/com/lx/wxgroup/feature/GroupConvStore.kt`（sync 的 Op.Insert 分支 + 事务分段）
> - 改 `wxgroup/app/src/main/java/com/lx/wxgroup/data/Keys.kt`（开关 `chat.gconv_native_insert` 默认 false）
> - 新增 `wxgroup/app/src/test/java/com/lx/wxgroup/feature/NativeConvInsertTest.kt`
> - 关联改动（install 接线）：`wxgroup/app/src/main/java/com/lx/wxgroup/HookEntry.kt:202-207`
>
> 权威对照：`docs/_r67_b2_native_insert.md`（435 行，dex 结论已由主会话独立复核吻合）。
> 证据分档：只认源码行号 / dex 原文 / 现有先例，不臆测。`[已证实]`=源码行/dex 原文 ｜ `[推断]`=外推 ｜ `[需真机]`。

---

## 复核清单（逐条 PASS/FAIL + 理由 + 行号）

### 0. 复核过程中的动态（重要，影响结论口径）

复核期间实现方仍在改盘，team-lead 又追加了「按最新态补审 install 时序 + 开关装机读一次」的要求。本报告**以当前磁盘最终落盘态为准**（已逐次重新 Read 确认，mtime：NativeConvInsert.kt 22:33、GroupConvStore.kt 22:30、HookEntry.kt 22:31）：

- `NativeConvInsert.kt:66-71` = `install(lpparam)` 存 `hostCl=lpparam.classLoader` + 装机读一次 `enabled`
- `NativeConvInsert.kt:107` = `fun insertConv(username, timeMs, groupId): Long?`（3 参，用内部 hostCl）
- `NativeConvInsert.kt:124` = `getOrNull()?.takeIf { it >= 0L }`（-1 归一化）
- `GroupConvStore.kt:195` = `NativeConvInsert.insertConv(u, d.timeMs, d.groupId)`
- `HookEntry.kt:43` import + `:202-207` install 调用（`NativeConvAvatar.install` 之后、`GconvPage.install` 之前）

复核中发现并**已由实现方在复核期间修复**的问题（不计入最终 FAIL，仅存档）：
- **install 调用点缺失**（初读时 `NativeConvInsert.install()` 在整个 src 无调用点 ⇒ enabled 恒 false、hostCl 恒 null ⇒ b2 100% 死代码）。现已在 `HookEntry.kt:202-207` 补齐，且位于 VersionGate 通过路径内（见第 8 条）。**当前态 PASS**。
- **`s4.I` 返回 -1 未归一化**（初读时第 5 条 FAIL，会丢 rconversation 行）。现已在 `NativeConvInsert.kt:124` 加 `?.takeIf { it >= 0L }` 归一为 null 触发降级，KDoc `:104-105` 契约同步更新。**当前态 PASS**（详见第 5 条更新说明）。

**最终 8 条清单 + 2 条追加审计全部 PASS，FAIL=0。**

---

### 1. 反射链正确性 —— PASS

逐项对照 `NativeConvInsert.kt` 与 dex 原文（设计 §1-§3）：

| 环节 | 实现 | dex 原文/设计 | 结论 |
|---|---|---|---|
| 单例容器类 | `CLS_H8 = "vj0.h8"`（L34） | §3 表：`Lvj0/h8;` classes7.dex | PASS |
| 取业务核心 | `callStaticMethod(h8cls, "b")`（L129） | §3：`vj0.h8.b():Lvj0/e;` static，`0x9013d0`；⚠️ 字段 `b` 与方法 `b()` 同名，须走方法 | PASS（用 `callStaticMethod` 走方法，非读字段） |
| 取会话存储 | `callMethod(vj0e, "v")`（L130） | §3：`vj0.e.v():o8`（实为 s4），`0x890800` | PASS |
| 会话行对象 | `newInstance(r4cls)`，`CLS_R4="com.tencent.mm.storage.r4"`（L35,L136） | §2.1：`new r4()` 无工厂，`r4.<init>()V 0x4a2dd4` | PASS |
| 7 setter 名 | `B1/T0/b1/r1/j1/Q0/n1`（L43-49） | §2.2 表逐项吻合：username=B1, conversationTime=T0, flag=b1, status=r1, isSend=j1, chatmode=Q0, msgType=n1 | PASS |
| msgType 值 | `MSG_TYPE_TEXT = "1"`（String），setter `n1("1")`（L52,L95） | §2.5：`field_msgType : String`，须传字符串 `"1"`，传 int 反射抛异常 | PASS（确为 String，非 Int） |
| flag 值 | `flagOf = PIN_BASE + timeMs`，`PIN_BASE=4611686018427387904`=2^62（L80，经 GroupConvPlan:17,48） | §2.4：`2^62`，`s4.w` 经 `po2.a.c` 重算成 `2^62|time`，值不变 | PASS |
| s4.I 调用 | `callMethod(s4, "I", r4)`（L119） | §1.1：`J I(Lcom/tencent/mm/storage/r4;)`，`0x4a5528`；§3 证 s4 的 I 无重载 | PASS |
| 返回值类型 | `as? Long` + `takeIf{>=0}`（L120-124） | 返回 primitive `long` → 反射装箱 `java.lang.Long`，`as? Long` 类型正确；-1 经 takeIf 归一为 null（见第 5 条） | PASS |

**重载歧义核实**：`XposedHelpers.callMethod(s4, "I", r4)` 按方法名 + 实参运行时类型（`com.tencent.mm.storage.r4`）做 best-match。dex 证 s4 仅有一个 `I(r4)`（设计 §1.1/§3，同类相关方法为 w/F/h/d，无第二个 I），且即便父类有同名方法，单 r4 实参也只会匹配 `I(r4)`。**调用方式安全，PASS**。

**setter 调用健壮性**：7 个 setter 集中一处、逐个 `runCatching` 并打具体环节名（L134-140），符合 §7.3「集中构造 + 每个失败可定位」。PASS。

---

### 2. classLoader 取法 —— PASS

- 最终实现：`install(lpparam)` 存 `hostCl = lpparam.classLoader`（NativeConvInsert.kt:66-67），`insertConv` 用该 `hostCl`（L107-110）做 findClass。`lpparam.classLoader` 就是宿主 com.tencent.mm 的 PathClassLoader，**等价于宿主 CL**，比 `HostContext.app()?.classLoader` 更直接、无 null 中间态。
- 对照先例：`ContactSqlSource.kt:87-94` 用 `context.classLoader`（注释明说「Application 的 classLoader 就是宿主 PathClassLoader，等价 lpparam.classLoader」）；`HostContext.app()`（HostContext.kt:63）返回 Application Context，其 classLoader 同为宿主 CL。本实现取法与先例同档，**无错用模块 CL 的风险**。
- null 降级：`hostCl == null` 时打 WARN 并 `return null`（L107-110），上层降级 execSQL。**PASS**。

---

### 3. 事务分段（最关键）—— PASS

- **s4.I 绝对不在事务内**：`inTransaction(db, out) { ... }` 块闭合于 `GroupConvStore.kt:188`；`s4.I` 调用在 `L192-203` 的段二，**在事务块之外**。通知 `b(2)` 由 s4.I 在 insert 后同步发出（dex §1.2 0038-003d），此时事务已提交。**PASS**。
- **rcontact 标题来源在事务内先写**：native 分支的 `insertContactSql` 在事务块内 `L178`，在 deferred 之前执行并提交。**PASS**。
- **段二同 worker 线程、紧随事务、count 之前**：`sync()` 整体由 `postSync` 的 `worker.execute`（L90-107）调用，`L192-203` 段二与事务 `L156-188` 同线程串行；count 统计在 `L204+`，在段二之后。**PASS**。
- 事务降级路径无新撕裂：`inTransaction` 反射失败降为无事务直写（L437-445），若 rcontact 写失败则抛错、段二被整体跳过，两表都不写，下次 sync 重插，不产生新半行。**PASS**。

---

### 4. 关闭路径零差异 —— PASS

- `nativeInsert=false` 的 Op.Insert 分支（`GroupConvStore.kt:180-184`）SQL 顺序：`deleteConvSql(insertPreDelConv)` → `deleteContactSql(insertPreDelContact)` → `insertConvSql(insertConv)` → `insertContactSql(insertContact)`。
- 与改动前 diff（`git diff` 原文）逐字一致：原为 `deleteConvSql` / `deleteContactSql` / `insertConvSql` / `insertContactSql` 四条同序同参。**PASS**。
- 新增语句无副作用：`L154 val nativeInsert = isEnabled()`（纯读 volatile）、`L155 deferredNative`（本地 ArrayList）、段二 `if (nativeInsert && ...)`（关时整段跳过）。HookEntry 新增 install 仅存 CL + 读开关到字段，不改任何既有钩子行为。Keys.kt 新增 PrefSpec 仅多一个默认 false 的实验开关。**关时零行为变化，PASS**。

---

### 5. 降级完整性 —— PASS（初读 FAIL，复审计已修复）

**初读 FAIL（已修复）**：`s4.I` 插入失败时返回 `-1`（设计 §1.1：`失败返回 -1`；§1.2 dex 反汇编 `0034 cmp rowid,-1 / 0036 if == -1 goto 0041`；§1.3 #3：`rowid==-1` 走日志分支、不发通知、返回 -1）。初读时 `as? Long` 对 `-1L` 返回非 null、原样上抛，而 `GroupConvStore.kt:196` 仅 `if (rowid == null)` 才降级 ⇒ `-1L` 被当成功打 `"OK rowid=-1"`、不降级，造成 rcontact 已提交而 rconversation 无行的孤儿半行（`plan()` 产 Update、`repairSql` UPDATE 影响 0 行不自愈，需 clean 兜底）——即清单第 5 条点名的「既没走 s4.I 也没走 execSQL 的丢行路径」。

**当前态 PASS**：实现方已采用报告建议的方案①，在反射封装内归一化：

- `NativeConvInsert.kt:120-124`：`runCatching { callMethod(s4,"I",r4) as? Long }...getOrNull()?.takeIf { it >= 0L }` —— `-1L` 被 `-1 >= 0` 判 false ⇒ 归一为 `null`，正确触发上层降级；非负值原样返回 rowid。
- `NativeConvInsert.kt:104-105` KDoc 契约同步更新为「插入成功返回非负 rowid；hostCl 未装、任一步反射失败、或 `s4.I` 返回 -1 均返回 null」——契约与实现一致，失败判定集中在反射封装一处，调用方无需理解 s4.I 语义。
- `GroupConvStore.kt:196` 判据 `if (rowid == null)` 保持不变即可（上游已归一），无重复逻辑。
- 带注释 `:122-123` 说明为何要归一（否则 -1 绕过降级判断、造成孤儿半行），可追溯。

**其余降级路径均完整**（逐条核实）：
- hostCl 为 null → `NativeConvInsert.kt:108-111` return null → execSQL 降级（GroupConvStore.kt:196-198）。注：最终版无单独 hostLoader 判空分支（CL 改由 install 存内部 hostCl），null 判定统一在 insertConv 内，等价覆盖。PASS。
- 单例链失败 → L112-114 return null → 降级。PASS。
- r4 构造/任一 setter 失败 → L116-118 + L134-140 return null → 降级。PASS。
- callMethod 抛异常 → L120-122 onFailure getOrNull=null → 降级。PASS。
- rowid=null（`as? Long` 失败）→ null → 降级。PASS。
- rowid=-1（s4.I DB 层失败）→ `takeIf { it >= 0L }` 归一为 null → 降级（本次修复）。PASS。

**无任何路径导致 rconversation 行「既没走 s4.I 也没走 execSQL」**：所有 s4.I 失败/异常/非法返回值都归一为 null，经 `GroupConvStore.kt:196-198` 降级 execSQL insertConv；成功则 s4.I 自己写库。闭环完整。

---

### 6. 开关与回滚 —— PASS

- 默认值 false：`Keys.kt:106` 常量 `chat.gconv_native_insert`；`Keys.kt:491-494` PrefSpec def=`"false"`；`NativeConvInsert.kt:60` `enabled = false` 初值。**PASS**。
- `Kv.getBool` 异常兜底双层：install 用 `runCatching { Kv.getBool(GCONV_NATIVE_INSERT) && Kv.getBool(GCONV_MODE) }.getOrDefault(false)`（L68-70）；`Kv.getBool` 本身 → `getString` 内 `runCatching{ d.getPref(...) }.getOrDefault(Prefs.defaultOf(key))`（Kv.kt:28-38）。Kv 取不到/抛异常均回落 false。**PASS**。
- `enabled = 本开关 ∧ GCONV_MODE`（L69），与设计 §6.1 一致（仅两者都开才走 s4.I）。PASS。
- 关闭后回到现状：见第 4 条，关闭走现状 execSQL 直写。**PASS**。
- `op=gcs sub=clean` 仍可清 b2 写的行：b2 写入的 username = `GroupConvPlan.usernameOf(d.groupId)` = `wxgrp_<16hex>`（GroupConvStore.kt:194 + GroupConvPlan.kt:23-24），前缀不变；`clean()` 用 `ownWhere()`=`substr(username,1,6)='wxgrp_'`（GroupConvPlan.kt:89）两表删除，命中 b2 行。**PASS**。

---

### 7. 单测有效性 —— PASS（1 处 KDoc 表述瑕疵，不影响变异捕获）

`NativeConvInsertTest.kt` 纯函数断言 vs 判据覆盖：

| 判据（设计） | 断言 | 结论 |
|---|---|---|
| §2.3 七 setter 名/值/顺序 | `settersAreSevenAndInOrder`：size==7，s[0..6].first 逐项 = B1/T0/b1/r1/j1/Q0/n1，second 逐项 = username/999L/PIN_BASE+999/3/1/1/"1"（L46-63） | PASS |
| §2.4 flag 置顶位 | `flagIsPinBitOrTime`（L38-43，PIN_BASE+123 + 锁死 2^62 字面值）+ s[2] 断言 | PASS |
| §2.5 msgType String | `msgTypeIsStringNotInt`：`assertTrue(msgType is String)`（L68）+ setters s[6]=="1" | PASS |
| status/isSend/chatmode 为 int | `statusIsSendChatmodeAreInts`：s[3..5].second is Int（L75-77） | PASS |
| §6.1 开关默认 false | `switchDefaultsToFalse`：`Prefs.defaultOf(...) == "false"`（L83） | PASS |
| 开关登记形态 | `switchIsChatDomainSwitchNotHidden`：kind=SWITCH/domain=聊天/not hidden（L88-91） | PASS |

**KDoc 变异清单（L23-33）逐条验真**：
1. `SET_FLAG to timeMs` ⇒ `settersAreSevenAndInOrder` 的 s[2]==PIN_BASE+999 **变红**，变异被捕获。⚠️ KDoc 称 `[flagIsPinBitOrTime]` 也变红**不准确**——该测的是 `flagOf()` 函数本身，mutation 只改 `nativeSetters` 组装、不改 flagOf，故它仍绿。但**变异仍被另一条断言捕获**，非假验证。建议把 KDoc 的「[flagIsPinBitOrTime] /」删去，避免误导。
2. `MSG_TYPE_TEXT` 改 Int 1 ⇒ `assertTrue(msgType is String)` 变红 + s[6]=="1" 变红。PASS。
3. `SET_STATUS to "3"` ⇒ `s[3].second is Int` 变红 + s[3]==3 变红。PASS。
4. PrefSpec def 改 `"true"` ⇒ `switchDefaultsToFalse` 变红。PASS。

**假验证排查**：未发现「断言存在但变异不会红」。需说明的边界：这些是纯函数快照测试，锁定的是实现常量值（如 setter 名 B1/T0）；若实现把 dex 混淆名抄错、测试会随之一起错——这是纯函数单测的固有边界，不在本清单「变异不红」范畴。单测由主会话 `gradlew test` 实跑确认（本复核不跑 gradle）。

---

### 8. 遗漏检查（设计 §7 红线）—— PASS

| §7 红线 | 实现落点 | 结论 |
|---|---|---|
| §7.4 删除仍走 execSQL，s4.I 只用于 insert | `NativeConvInsert` 类 KDoc L23-24 明示不碰删除；`GroupConvStore` 的 Delete 分支（L159-163）与预删除（L173-174）仍用 `deleteConvSql/deleteContactSql`（含 ownWhere 双保险，GroupConvPlan.kt:127-131） | PASS |
| §7.2 版本白名单门槛 | `NativeConvInsert.install` 在 `HookEntry.kt:205-207`，位于 `VersionGate.check` 的 Reject→return（HookEntry.kt:116-122）之后；非主进程在 L76 已 return。版本不匹配不打钩、不反射 | PASS |
| §7.1 事件总线副作用 | 未新增事件码/未手动补发 `s4.b`；通知完全由 s4.I 内部 `b(2)` 产生（dex 原文），不引入新的我方主动事件 | PASS |
| §7.1 置顶位补偿 repairSql 保留 | Update 分支 `repairSql/renameSql` 未改（GroupConvStore.kt:164-168） | PASS |
| §7.4 clean 兜底 + 非本方行自证 | `clean()` 未改（L224-258），含 other-before/after 不变自证 | PASS |
| §7.5 与微X `wx__c_` 前缀隔离 | b2 写 `wxgrp_` 前缀，ownWhere 仍 substr 精确匹配，互不误删 | PASS |

无 §7 红线遗漏项。

---

### 9. 【追加审计】install 时序与竞态 —— PASS

team-lead 追加要求：确认 install 时序在任何 sync 之前、位置正确、import 没漏、install 失败是安全降级。

- **import 齐全**：`HookEntry.kt:43` = `import com.lx.wxgroup.feature.NativeConvInsert`。PASS。
- **位置正确**：install 调用在 `HookEntry.kt:202-207`，紧随 `NativeConvAvatar.install`（:199-200）之后、`GconvPage.install`（:214-215）之前，与同域 gconv 钩子排在一起；用 `runCatching{...}.onFailure{ LogX.e("NativeConvInsert install failed", it) }` 包裹，与相邻 install 同款。PASS。
- **install 在 sync 之前、无竞态**：
  - install 是 `handleLoadPackage` 里**主线程同步执行**的 install 序列之一（:70 起），`HookEntry.kt:145` 的 `GroupDb.open(context)` 更早，install 时 Kv/宿主 CL 均已就绪（见第 10 条）。
  - `GroupConvStore.sync()` 的触发点全部是 install 之后的 UI / 广播事件：`ConvFilter.kt:653`（rebuild）、`:1497`（gconv 开启时）、`EntryReceiver.kt:212`（手动 `op=gcs sub=sync`）。这些都需要宿主 UI 起来或外部广播，必然晚于 `handleLoadPackage` 的 install 序列。
  - `enabled`/`hostCl` 均为 `@Volatile`（NativeConvInsert.kt:55-60），install 写入发生在 sync 读取之前，happens-before 关系由「install 同步完成后宿主才开始投递 UI 事件」保证。**无竞态，PASS**。
- **install 失败是安全降级而非丢行**：
  - 即便假设 install 的 `runCatching` 吞了异常（如 Kv 读取抛错），`enabled` 保持初值 false（:60）⇒ `GroupConvStore.kt:154` 的 `nativeInsert=false` ⇒ 走 Op.Insert 的 else 分支（:180-184）execSQL 直写 rconversation + rcontact。这是**现状路径**（设计 §6.1「关着 = 现状，零行为变化」），不写半行、不丢行，FAIL-open 到安全侧。PASS。

### 10. 【追加审计】开关「装机读一次」与 Kv 初始化 —— PASS

team-lead 追加要求：确认无「开关开着却 enabled 恒 false」的路径（如 Kv 在 install 时尚未初始化）；对照 AntiRevoke.install 读 Kv 先例。

- **Kv 在 install 前已初始化，无「开着却恒 false」**：
  - `Kv.getBool` → `getString` → `GroupDb.current()`（Kv.kt:24/28-38）；Kv 依赖 `GroupDb.open()` 已调用（Kv.kt:16-17 明示未开库时返回默认值=false）。
  - `GroupDb.open(context)` 在 `HookEntry.kt:145`，而 `NativeConvInsert.install`（读 Kv）在 `:206` —— **开库早于读 Kv 61 行**，install 时 `GroupDb.current()` 非 null，能读到用户真实设置。冷启微信后开关为 true 则 enabled=true，不会恒 false。PASS。
  - 双保险：install 内 `runCatching{...}.getOrDefault(false)`（:68-70），即使 Kv 读取异常也只是安全回落到 false（现状），不会崩、不会读到错值。PASS。
- **与先例同款（装机读一次语义）**：
  - `NativeConvAvatar.install:108-111`：`enabled = runCatching{ Kv.getBool(GCONV_AVATAR) && Kv.getBool(GCONV_MODE) }.getOrDefault(false)` —— 与 b2 `NativeConvInsert.install:66-70`（`GCONV_NATIVE_INSERT && GCONV_MODE`）**逐字同构**，同域同语义。gconv 开关本就是「切换后冷启生效」，b2 跟随这一约定，一致。PASS。
  - `AntiRevoke.install:357` + `refresh():249-255` 也是 install 期读 Kv 的完整先例（且 `runCatching{refresh()}` 包裹，:369），证明 install 期读 Kv 是本模块成熟模式。差异：AntiRevoke 有设置页切换时的即时感知点（:506），而 b2 按 gconv 语义刻意只装机读一次（切换需冷启）——这是设计选择（§6.1/Keys KDoc），非缺陷。PASS。
- **关闭路径零差异不受影响**：开关关（或 gconv 关）⇒ enabled=false ⇒ execSQL 现状路径（第 4 条已证逐字一致）。PASS。

---

## 总判定

**可装机** —— 8 条原始清单 + 2 条追加审计（install 时序/竞态、开关装机读一次）全部 PASS，FAIL=0。复核过程中发现的两处问题（install 调用点缺失、`s4.I` 返回 -1 未降级丢行）均已由实现方在复核期间按报告建议修复并复审计确认，当前磁盘最终态无机制级风险、无丢数据路径、无开关关时变行为。

- FAIL 条数：**0**
- PASS 条数：10（第 1-8 条 + 追加第 9/10 条）
- 最关键三维度均 PASS：反射链与 dex 原文吻合（第 1 条）、事务分段正确（s4.I 严格在事务外、rcontact 事务内、同 worker、count 之前，第 3 条）、开关关闭路径逐字零差异（第 4 条）。
- 装前注意（非 FAIL，`[需真机]` 设计未决项）：event=2 路径在 `p3.e` 的分支处理是否等价微X event=3、msgType String "1" 渲染、缺字段渲染表现——这些是设计 §9 留给真机的验收项，主会话按 §6.2 正反对照 SOP 实测即可，不影响本轮「可装机」判定（实验开关默认关，关着零行为变化）。

[DONE] R67 b2 独立复核（含追加审计）完成。产物：`docs/_r67_b2_review.md`。总判定：**可装机**；FAIL 0 条（原 2 处问题均已修复并复审计确认）。
