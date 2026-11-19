# R70 · 微X「删除分组后会话列表分组行即时消失」只读取证 + 我方可落地方案

> 角色：r70-l2-microx。本轮**未改任何 app 源码、未跑 gradle、未碰 adb/设备**；设备锁/构建锁归主会话。
> 宿主：微信 8.0.48 / versionCode 2588，权威源 `_cache/mm.apk`，解包 dex `_cache/dex/classes*.dex`（15 个）。
> 微X：`me.yun.fkwechat`（框架侧 `com.fkzhang.wechatxposed`），本地 apk `_mods/wechatxposed.apk` / `_cache/wxposed.apk`（均 4,423,605B）。
> 判据纪律（CLAUDE.md §四/§五）：只认「用户能看见的事实」+ dex 原文 + 源码行号 + 真机日志时间戳；「A 推 B」一律标 `[推断]`/`[需真机]`，不冒充 `[已证实]`。
> 证据分档：`[已证实]`=dex/源码/设计定案直接可见 ｜ `[推断]`=由已证实事实外推 ｜ `[需真机]`=有锚点无实测 ｜ `[取证缺口]`=材料不存在/解不出。
>
> **取证工具（只读）**：`_cache/_r55_disasm.py`（复用其 Dex 解析器，逐方法反汇编 classes*.dex）。本轮所有 dex 地址均为该脚本实跑输出，可复核。

---

## 0. 一句话结论（先给主会话）

1. **微X 业务 dex 加密（7 个密文 + 剥符号 .so），静态拿不到它的删组代码 —— [取证缺口]**。但 R66 真机已一锤定音其**建组**走「`insertWithOnConflict` 写库 → `s4.b(3,s4,user)` → `p3.e` → `i3.s` → `P()=false` 不拦 → `p3.f` 重查 → item[0] 出现新行」这条**宿主通知链**，**不是**渲染层内存注入。据此，微X**删组最可能是同一条链的逆过程**：删 `wx__c_` 行 → 发 `s4.b(5,s4,user)` → 宿主重查 → 行消失 —— `[推断·高]`（对称性 + 下方宿主 delete dex 铁证；删组本身无真机日志，**[需真机]**）。
2. **我方「删了还显示」的症结与 R66 建组完全同源**：`execSQL` 直写删行（GroupConvStore.kt:176-179）**不发宿主通知** ⇒ 宿主 `p3.f` 不重查 ⇒ 内存 adapter 旧行还在 ⇒ 等周期/冷启才消失。已加的 force sync 只解决「库行 0.5s 删掉」，不解决「界面秒消失」。
3. **推荐方案 A′（宿主通知补发）**：execSQL 删行**保持不变**，事务提交后反射补发 **`s4.b(5, s4, "wxgrp_<hex>")`**（具体 username，非全清空串），触发宿主重查让行即时消失。理由：宿主自己的 `s4.k(String)` 删单行就是这么发的（dex 铁证），且 `s4.b` 只发事件不写库、不碰消息 ⇒ **满足红线**；事件码 5 的 `p3.e` 分支在 `i3.s` 里**跳过 `a94.e.P` 过滤门**，`wxgrp_` 陌生 username 不会被拦（dex 结构 `[推断]`，待真机）。
4. **方案 B（渲染层内存注入）不建议**：用户已明确否决自绘行（R65 C8），且我方现已确立 b2 原生插入（NativeConvInsert）路线，gconv=1 下 `GroupedAdapter` 刻意不插自绘行（ConvFilter.kt:1329-1332）。再引入注入会与 b2 双轨冲突、动 K7 位置映射（R1~R34「点错人」历史区），收益不比 A′ 大。

**推荐方案 = A′（execSQL 删行 + 补发 `s4.b(5,s4,wxgrp_<hex>)` 宿主删除通知），独立开关默认关、VersionGate 绑定 2588、真机正反对照后再 flip。**

---

## 取证纪律声明（必读，避免把推断当已证实）

- **dex 级静态取证受阻**：微X 业务代码不在 `_mods/x/classes.dex`（外壳，仅 `WxCoreLoader`/`InMemoryDexClassLoader`），而在 `assets/` 下 7 个 hash 密文（530707/353907/323411/276456/258499/48191/41471B，头 `92 c5 01 00`），key 在剥符号 ELF .so 里（R53 §1/R64 §A2 已证）。本轮**不碰 adb、不解密文**，故**微X删组的具体点击回调/写库方式/是否发通知，无 dex 原文** —— 全部基于「R66 建组真机链 + 宿主自身 delete dex」外推，标 `[推断]`。
- **可绕过**：R64 §4b / R65 §3.0 的「运行时侧观察」手法（hook 微信 API 看微X删组那一刻宿主被怎么操作）由主会话持设备执行，可一次性定案。本文件末尾 §5 给出**必须真机验证的判据清单**。
- 结论一句：**dex 级静态取证受阻，以下「微X删组链」为基于 R66 建组真机证据 + 宿主 delete dex 的推断，不是已证实；我方方案 A′ 的可行性有宿主 dex 强支撑但效果需真机确认。**

---

## 问题 1：微X删组走哪条链？（dex 证据/地址 + 证据等级）

### 1.1 微X建组链（真机已证，作为删组对称性的锚点）—— `[已证实·真机]`

R65 §甲-2（2026-11-18 21:49:18，HCR 13/13 探针全程）原文逐帧：

```
21:49:18.014 HCR sql   insert table=rconversation username=wx__c_1a1eeeb4338
21:49:18.016 HCR s4.b   event=3 user=wx__c_1a1eeeb4338
21:49:18.068 HCR p3.e   event=3 user=wx__c_1a1eeeb4338
21:49:18.070 HCR P      user=wx__c_1a1eeeb4338 pass=false impl=a94.k
21:49:18.177 HCR p3.f   cursor=1576→1578
21:49:18.179 HCR count  1578
21:49:18.206 HCR item[0]=wx__c_1a1eeeb4338
```

定案（R66）：**微X走宿主通知链（候选 b），不是渲染层内存注入（候选 c 已排除）**。写库用 `insertWithOnConflict`（直写），通知用手动 `s4.b(3,s4,user)`（事件码 3=update，非宿主 insert 的 2）。`a94.e.P(wx__c_)` 返回 false 但**不拦**，重查照常、新行照常出现。

> 这条证据直接回答你问题 1 的第三个子问题：**微X并没有在 `getCount/getItem/w8.W` 里维护内存分组列表靠移除来「即时消失」——至少建组不是**。R64 §A1 曾推断「行即时可见最可能来自 getCount/getItem 注入」，**该推断已被 R66 真机推翻**（建组有 `p3.f` 重查、count+1、item[0] 新行 = 宿主内存列表真实变化，非注入假对象）。

### 1.2 微X删组：最可能的链 —— `[推断·高]`，删组本体 `[需真机]`

基于 1.1 的对称性，微X删组最可能：

```
用户点「解散聊天分组/删除分组」→ 微X删 wx__c_ 行（宿主 API 或 SQL，二者皆可能）
  → s4.b(5, s4, "wx__c_<hex>")   // 事件码 5 = delete（下方 1.3 dex 铁证）
  → p3.e(5,...) → i3.s(...) → p3.f 重查 → cursor/count -1 → 该行从 item 中消失 → notifyDataSetChanged
```

**为什么是高置信推断（不是凭空猜）**：
- 微X建组**必须发 `s4.b(3)` 才能让宿主认新行**（1.1 真机证）——说明它的「即时性」完全建立在宿主通知链上，而非自己的内存。
- 宿主 delete 事件码就是 5，且**用具体 username 发**（1.3 dex 铁证）。微X要让删行即时消失，发 `s4.b(5,s4,user)` 是最自然、与建组对称的做法。
- 唯一不确定：**微X删行用宿主 `s4.k(String)` API 还是 SQL 直写 + 手动通知**。这决定它是否「删行也走宿主」，但不影响我方方案——我方**无论如何都保留自己的 execSQL 删行**（红线，见问题 3），只学它「发通知」这一环。
- **删组本体的真机日志缺失** ⇒ 以上链标 `[推断]`，判据见 §5。

### 1.3 宿主自身删除链 dex 铁证（这是方案 A′ 的基石）—— `[已证实]`

逐方法反汇编（脚本实跑，classes4.dex）：

| 方法 | dex:code | 反汇编关键行 | 发的通知 |
|---|---|---|---|
| `s4.k(String):void`（单会话删除 "delChatContact"） | `0x4a5d3c` | `001c k74.i0->delete("username=?",[user])` → `0020 if-eqz v0,+6`（删到行才发）→ **`0022 const/4 v0,#5`** → `0023 k74.y0->b(5, s4, user)` | **`b(5, s4, 具体username)`** |
| `s4.n(String):boolean`（按名前缀删 "deleteMessageEndByName"） | `0x4a4c74` | `0033 k74.i0->j("delete from rconversation where username like '%..%'")` → **`0039 const/4 v1,#5`** → `003a b(5, s4, nameTag)` | `b(5, s4, nameTag)` |
| `s4.l():boolean`（全清 rconversation+rbottleconversation） | `0x4a4c20` | `0000/000a delete from ...` → **`001a const/4 v1,#5`; `001b const-string v2,""`** → `001d b(5, s4, "")` | **`b(5, s4, "")`（全清才用空串）** |
| `k74/y0.b(int,k74/y0,Object):void`（通知入口本体） | `0x6941e8` | `new k74/w0(...)` → `iget k74.y0.c:k74.t0` → `k74.t0->d(w0)` + `k74.t0->c()` | **只投递事件总线，无任何 delete/execSQL** |

**三条可直接落地的结论 `[已证实]`**：
1. **事件码 5 = delete，由宿主自己的删除方法发出**；payload 语义：**删单行 → 具体 username；全清 → 空串**。⇒ 我方删单个 `wxgrp_` 行，正确 payload 是**具体 username**（对齐 `s4.k`），不是空串。
2. **`b()` 只发事件、不写库、不删消息**（`k74/y0.b` 反汇编只有 `new k74/w0` + `k74.t0.d/c`）。⇒ 反射调 `s4.b(5,...)` 本身**不会连带删消息/清空聊天记录**，与「宿主 delete API（`s4.k/n/l`，真删 SQL）」有本质区别。这是我方方案 A′ 满足红线的核心依据。
3. **`s4.k` 删到行才发通知**（`if-eqz v0,+6`），删 0 行不发 ⇒ 幂等：重复通知不会「通知了但库没变」造成宿主状态漂移。

### 1.4 事件码 5 的解释权：`p3.e` / `i3.s` 如何处理（含过滤门分析）—— `[已证实]` dex + `[推断]` 分支归属

`p3.e(int,k74/y0,Object)`（`0x580ebc`，日志 tag `MicroMsg.ConversationWithCacheAdapter`）：

- `000d-000f`：`const/4 v2,#2` / `const/4 v3,#5` / `const/4 v4,#0` —— 事件码 2/5 常量就在这。
- `0013-001f`：`if-eqz v1` 打日志 `"unreadcheck onConversationStorageNotifyChange event type %d, username %s"`。
- **`0054-0059`：`const-string ""` → `v10.equals("")`（判断 payload 是否空串）**。
- **`005c`：`if-ne v56,v3,+13`（event != 5 则跳走）；`005e-0063`：event=5 且 username=="" → 置 `p3.N=true` + `i3.r(null,1)` + return** —— 这是「全清」分支（`s4.l()` 的发法）。
- `0064-0075`：username 非空（具体用户名）→ 加入 `p3.M` HashSet → **`0075 i3->s(user, event, a94/a, Z)`** —— 逐 username 分支。⇒ **`b(5,s4,"wxgrp_xxx")` 走这条，不是全清分支**。

`i3.s(Object,int,a94/a,Z)`（`classes10.dex 0x40a5b4`）：

- **`0019 const/4 v4,#5` → `001c if-eq v75,v4,+240`（event==5 直接跳到 `0x010c`）**。
- `0x010c-0x0117`：event==5 → **`i3.g HashMap.clear()` + put(null,null)**（清空 events 表），走「全量重置」而非逐条事件合并。
- `0x0135-0x0172`：`i3.j()` 取 count → `i3.u(1,Z)` / `i3.u(0,Z)` → **触发 `p3.f` 重查 + `notifyDataSetChanged`**。
- **过滤门 `a94.e.P(username)` 在 `0x0034-0x0048`** —— 该段位于 `0x0032 if-ne v96,v6,+206` 之后；**event==5 在 `0x001c` 已跳走，到不了 `0x0034` 的 P() 门**。`[推断]`（反汇编寄存器/跳转因自定义脚本有少量噪声，分支归属标推断；但 event==5 早跳的结构清晰）。
- 各 `P()` 实现（`[已证实]`）：`a94.k.P`（`0x1cd97c`）`n=false → return false`、否则委托 `a94.b.P`；`a94.g.P`（`0x1cd180`）数组 OR；`z84.c.P`（`0x7b21b8`）**恒 false**。R66 真机 `impl=a94.k, P()=false` 不拦，与之一致。

`i3.r(Object,int)`（`0x40a598`）：仅 `i3.s(user,code,null,true)` 的包装 —— 全清分支 `i3.r(null,1)` 最终也落到 `i3.s`，殊途同归触发重查。

**结论 `[推断·中高]`**：删组 `b(5,s4,具体user)` → `p3.e` 逐 username 分支 → `i3.s(event=5)` 清空 events + `i3.u` 全量重查 → 行消失；**`a94.e.P` 过滤门在 event=5 路径上不构成阻力**（且不拦陌生 username 已被 R66 证伪其风险）。**必须真机确认 event=5 这条分支确实让 `p3.f cursor-1`、item 少一行**（§5）。

### 1.5 「渲染层内存列表移除」假设的判定 —— `[已证伪（建组）/ 推断（删组）]`

- 你问的「微X是否在 `p3.getCount/getItem`/`w8.W(Cursor)` hook 里维护内存列表、删组时移除即即时消失」——**建组场景已被 R66 真机证伪**：建组时 `p3.f` 真重查、`count` 真 +1、`item[0]` 真是宿主对象（`q4.n` 也真被调用），是宿主内存列表真实变化，不是我方/微X注入的假对象。
- 删组场景无真机日志，但既然建组的「即时」靠宿主通知链，删组**同样最可能靠宿主通知链**而非独立内存列表 —— `[推断]`。
- 对我方的含义：**我方无需、也不应引入「自绘内存行列表」**；只需补上宿主通知这一环，让宿主自己重查，天然与 b2 原生插入同轨、零视觉差异、零位置映射风险。

---

## 问题 2：对我方的可落地方案（具体到方法 + 风险）

### 2.0 我方现状精确落点（源码行号，`[已证实]`）

- 删除触发：`GroupBar.kt:1529` / `GroupManagerPage.kt:90` / `GroupRowMenu.kt:141`（用户删组）→ `db.deleteGroup(id)`（只删我方分组表 `GroupDb.kt:409`）→ `GroupConvStore.postSync(force=true)`。
- 伪行删除：`GroupConvStore.sync()` `Op.Delete` 分支（`GroupConvStore.kt:176-179`）—— `execLog(deleteConvSql/deleteContactSql)` 从 `rconversation`/`rcontact` **execSQL 直写删行**，带 `ownWhere()+精确username` 双保险（`GroupConvPlan.kt:141-146`）。
- force sync 只解决「库行 0.5s 删掉」（R69 真机证），**不发宿主通知** ⇒ HCR 探针删组全程应见 `s4.b` 无 `wxgrp_` event=5、`p3.f` 零调用、`getCount` 不减（与 R66 甲-1 建组镜像，删组侧**待真机复测**，§5）。
- 建组已有 b2 原生插入 `NativeConvInsert.kt`（`s4.I(r4)` 自动发 `b(2)`），**删组没有对称的「通知补发」** —— 这就是缺口。

### 2.1 方案 A′（推荐）：execSQL 删行 + 补发 `s4.b(5, s4, "wxgrp_<hex>")`

**做法**（最小改动，不动 `SYNC_THROTTLE_MS`、不动建组）：
1. 在 `GroupConvStore.sync()` 的 `Op.Delete` 分支（`GroupConvStore.kt:176-180`），事务提交后（**事务外**，同 worker 线程，仿 b2 的 `deferredNative` 分段 `:206-220`），对每个被删 `u` 调一个新增的 `HostConvDelete.notifyDeleted(u)`。
2. 新建 `feature/HostConvDelete.kt`（仿 `NativeConvInsert.kt` 结构）：反射链 `vj0.h8.b().v()` 拿 s4（复用 `NativeConvInsert` 的同款 2 步，可提取共用 private helper 或直接复制），调 `s4.b(5, s4, u)`；每个反射点 `runCatching` + `LogX.w`，失败只告警不崩。
3. 独立开关 `chat.gconv_delete_notify`（默认 false，`Keys.kt` + `Prefs.ALL`），`enabled = GCONV_DELETE_NOTIFY ∧ GCONV_MODE`，install 时读一次（与 b2 同款冷启生效语义）；`VersionGate` 绑定 2588；反射混淆名（`vj0.h8.b` / `vj0.e.v` / `s4.b`）集中定义便于审查。

**逐子问题评估**：

| 子问题 | 结论 | 证据/等级 |
|---|---|---|
| payload 用具体 username 还是空串？ | **具体 username**（`wxgrp_<hex>`）。宿主 `s4.k` 删单行发具体 user、`s4.l` 全清才发 `""`；空串会走 `p3.N=true + i3.r(null,1)` 全清分支，副作用大（可能整表重置、丢滚动位）。删单个组行绝不应用空串。 | `[已证实]` dex 1.3 + `[推断]` 分支归属 1.4 |
| 会不会过 `a94.e.P` 过滤门？ | event=5 在 `i3.s 0x001c` 早跳，到不了 P() 门（`0x0034`）；且 R66 已证 `P(wx__c_)=false` 不拦 ⇒ `wxgrp_` 同样不会被拦。 | `[推断·中高]` dex + `[已证实·真机]`（P 不拦） |
| 会不会误伤真实会话？ | **不会**。`s4.b` 只发事件不写库/不删消息（`k74/y0.b` 铁证）；`p3.e` 对非空 username 只把它加入 `p3.M` 事件集、逐条处理；真实会话行仍在库里，`p3.f` 重查照常读出。无本地删除副作用。 | `[已证实]` dex + `[推断]`（事件总线其它 observer 副作用见风险） |
| 事件码 5 的解释权（`p3.e` 对 5 的处理） | 已厘清：非空 payload → `i3.s` 逐条 → 清空 events + `i3.u` 全量重查；空 payload → `p3.N=true + i3.r(null,1)` 全清。两种都能触发重查，但**删单行走非空、语义正确、副作用最小**。 | `[已证实]` `p3.e` 反汇编 + `[推断]` 分支 |
| 刷新是否即时？ | 宿主收到 `b(5)` 后 `p3.f` 重查（worker 线程），count-1、行消失、原生 notify —— 与建组 `b(3)` 镜像。预期 1~2s 内可见消失。 | `[推断]` + `[需真机]` |

**风险**：
- 反射宿主私有混淆方法 `s4.b`（继承 `k74/y0.b`）+ 单例链，事件总线副作用：可能触发其它 observer（置顶重排、unread 统计）——但**正常删会话就发这个事件**，不引入新事件类别 `[推断]`。
- 混淆名随版本变 ⇒ 必须 VersionGate 绑 2588 + 每反射点 runCatching + 跨版本重跑探针。
- 通知与库删的原子性：`execSQL` 删行在事务内，`b(5)` 在事务外紧随其后（同 worker）。若通知先于提交发出行为不确定 ⇒ 按 b2 的「事务外发通知」分段纪律（`:206-220`）规避。`s4.k` 自己也是「先 `delete` 再 `b(5)`」同序。
- **不碰消息、不碰聊天记录、不走宿主 delete API** ⇒ 红线满足（问题 3 表）。

### 2.2 方案 B：渲染层内存注入（对齐 R64 早期推断）—— 不推荐

**做法**：恢复 `ConvFilter.GroupedAdapter` 在 gconv=1 下的注入（`ConvFilter.kt:1332-1347`），维护我方内存分组行列表，删组时从列表移除 + refresh，让行「自绘消失」，不依赖宿主重查。

**评估**：
- **现状已不走这套**：gconv=1 下 `GroupedAdapter` 刻意不插自绘行、让宿主原生渲染库行（`ConvFilter.kt:1329-1332`）；R69 建组即时出现靠的是 b2 原生插入（`s4.I` 自动 `b(2)`），**不是注入**。
- **用户已否决自绘行**（R65 C8「用户已否决自绘行，本轮不做」）：引入注入等于推翻用户拍板。
- **双轨冲突**：若同时保留 b2 原生插入 + 注入，冷启前后会有「宿主库行」与「注入行」两套，去重逻辑（R64 B4-2）复杂且动 K7 位置映射/锚点（`ConvLayout.kt:202-250`），是 R1~R34「点错人」「下拉糊屏」历史高发区。删组只是「消失」，不值得为此重开高风险区。
- **收益不比 A′ 大**：A′ 让宿主原生重查，零视觉差异、零位置映射；B 要自绘、需视觉一致性验收，且改名/标题仍要处理（R65 B4′）。

### 2.3 推荐方案 + 理由 + 最小改动落点 + 真机判据

**推荐：方案 A′**。理由：
1. **对齐用户方向「参考微X」**：微X建组真机证走宿主通知链（R66），删组对称推断也走通知链；A′ 正是补我方缺的「通知」这一环，是真正「照微X机制」而非自造。
2. **与已确立的 b2 原生插入同轨**：建组已用 `s4.I` 自发 `b(2)`，删组补发 `s4.b(5)`，一进一退都用宿主通知，架构一致、宿主原生行、零视觉差异。
3. **红线全满足**（问题 3 表）：execSQL 删行不变、不碰消息、不改节流、不破坏 b2。
4. **风险可控**：独立开关默认关 + VersionGate 绑 2588 + runCatching + 真机正反对照后再 flip。

**最小改动落点**（本轮只出方案，未改码）：

| # | 文件:位置 | 改动 |
|---|---|---|
| 1 | `feature/HostConvDelete.kt`（新增） | 仿 `NativeConvInsert.kt`：反射 `vj0.h8.b().v()` → `s4.b(5, s4, username)`；集中混淆名 + runCatching + LogX |
| 2 | `feature/GroupConvStore.kt:176-180`（Op.Delete 分支） | 事务提交后（仿 `:206-220` 分段，事务外同 worker）对每删行调 `HostConvDelete.notifyDeleted(u)`（开关开时） |
| 3 | `data/Keys.kt` + `Prefs.ALL` | 新增 `chat.gconv_delete_notify`（默认 false） |
| 4 | `NativeConvInsert.kt` / `HostConvDelete.kt` | 单例链 `s4Instance()` 可提取到共用 util 复用，避免两处重复反射（可选，非必须） |

**不动**：`SYNC_THROTTLE_MS`（`:72`）、`GroupConvPlan.delete*Sql`（红线④）、`NativeConvInsert`/b2 建组路径、`ConvFilter.GroupedAdapter` 注入逻辑、`GroupRowMenu`/`GroupManagerPage` 的 force sync 调用。

**真机判据**（主会话执行，见 §5 完整清单）：删组后**不冷启**，HCR 探针见 `s4.b event=5 user=wxgrp_<hex>` → `p3.e event=5` → `p3.f cursor-1` → `getCount-1` → `item` 少该行 → 用户肉眼见分组行秒消失；关开关反向对照须恢复「~40s 才消失」。

---

## 问题 3：红线核对（逐方案）

| 红线 | 方案 A′（推荐） | 方案 B（渲染层注入） |
|---|---|---|
| **不许「宿主 delete API 连带删消息/清空聊天记录」** | ✅ 满足：删行仍走我方 execSQL `delete*Sql`（`GroupConvPlan.kt:141-146`），只补发 `s4.b(5)` 通知；`k74/y0.b` dex 铁证「只发事件不写库/不删消息」。绝不调 `s4.k/n/l`（那才是真删 SQL）。 | ✅ 满足：注入只动 UI 内存，不碰宿主 delete API（但也没修库行删除，库行仍靠 execSQL）。 |
| **不许改 `SYNC_THROTTLE_MS`** | ✅ 满足：`:72` 常量不动；force sync 调用点不动；只在事务后加通知。 | ✅ 满足（但引入独立注入逻辑，与节流无关）。 |
| **不许破坏 R67 b2 建组路径** | ✅ 满足：`NativeConvInsert`/`s4.I(r4)` 路径逐字不动；A′ 只加「删」侧通知，`b(2)` 建组通知不受影响。两者事件码不同（2 vs 5）、方向不同，互不干扰。 | ⚠️ 有风险：b2 让宿主原生渲染库行，B 又注入自绘行，冷启前后双轨，需复杂去重；可能与 b2 的「原生行即时出现」打架。 |
| **gconv=0 零差异（红线①）** | ✅ 满足：`enabled = GCONV_DELETE_NOTIFY ∧ GCONV_MODE`，gconv=0 时整块不发通知。 | ⚠️ 需额外守卫（R64 B7），且用户已否决自绘行。 |
| **误删宿主真实会话** | ✅ 满足：`s4.b(5)` 只发通知不写库；删行 SQL 带 `ownWhere()+精确username` 双保险不变。 | ✅ 满足（不删库行）。 |

**总判定**：A′ 全绿；B 在「不破坏 b2 / gconv=0 零差异」两条上亮黄且违背用户拍板 ⇒ **推荐 A′，不推荐 B**。

---

## 5. 必须真机验证的判据清单（dex 静态受阻的补证，主会话持设备执行）

> 复用已挂的 `probe/HostCacheRefreshProbe.kt`（13/13 hook，`op=hcr sub=on/off`）；微X 与我方分时段测（采微X时关 `chat.gconv_enabled`，与 R65 甲同纪律）；一次只动一个变量、改完不冷启。

**甲·微X删组（定案微X链，补 §1.2 的 [需真机]）**
1. 基线：微X建一个测试分组，记 `wx__c_` 行数 + HCR 日志。
2. 真手在微X**删除/解散该分组**，HCR 全程开：
   - **Q1**：`op=hcr` 日志里删组那一刻有无 `s4.b event=5 user=wx__c_<hex>`？（有 ⇒ 坐实微X走宿主通知链，§1.2 定案；无 ⇒ 微X另有机制，需再查）
   - **Q2**：`p3.f` 是否被打到、`cursor` 是否 -1、`getCount` 是否 -1、`item` 是否少一行？（是 ⇒ 宿主重查导致消失）
   - **Q3**：`HCR sql delete` 是否真删了 `wx__c_` 行？（区分「宿主 API 删」vs「SQL 直写删」——若走 `s4.k` 则无我方 sql 日志、有 `s4.b(5)`；若直写则有 sql 日志 + 手动 `b(5)`）
   - **Q4**：用户肉眼是否在 1~2s 内见该行消失（不冷启）？坐实「即时」。

**乙·我方方案 A′（定案可行性）**

5. 前置：`gconv=1` + 冷启 + 开 `chat.gconv_delete_notify` + 装 A′ 代码；列表已有若干 `wxgrp_` 组行。
6. 正向：UI 删组「R70T」→ **不冷启**：
   - HCR：`s4.b event=5 user=wxgrp_<hex>` → `p3.e event=5` → `i3.s`（event=5，看有无 `"newcursor event pass"`，预期无）→ `p3.f cursor-1` → `getCount-1` → `item` 少该行。
   - 用户肉眼：1~2s 内分组行**消失**（核心判据）。
   - `gcs stat`：own 减 1（库行确已删）。
7. **过滤门反向**：若 `p3.f` 未被调、行没消失，看 HCR P() 行 —— 若 event=5 路径竟走到 P() 且拦 ⇒ §1.4 分支归属推断有误，需改方案（如退Bb1 event=3 对照）。
8. **payload 变异**：把 `b(5,s4,user)` 换成 `b(5,s4,"")` 跑一次，观察是否走全清分支（`p3.N=true`+`i3.r(null,1)`）、有无置顶重排/滚动跳位副作用 ⇒ 坐实「具体 username 更优」。
9. **误伤反向**：删组后检查真实会话行数/消息是否不变（`gcs stat other` 恒定、真手点几个真实会话正常）⇒ 坐实红线「不连带删消息」。
10. **关开关反向**：`kvset chat.gconv_delete_notify 0` + 冷启 → 删组 → 行**不秒消失**（~40s 或冷启才消失，回到现状）⇒ 坐实「即时消失」确由 A′ 带来，非其它变量。
11. **b2 不破坏**：开 `chat.gconv_native_insert` → 建组仍即时出现（`s4.b event=2`、`p3.f cursor+1`），删组仍即时消失（event=5）⇒ 一进一出双轨互不干扰。

---

## 6. 本轮实际取证材料（可复核）

- 只读工具：`_cache/_r55_disasm.py`（逐方法反汇编，实跑输出见本文件各 dex 地址）。
- 宿主 dex：`classes4.dex`（`s4.k 0x4a5d3c`/`s4.l 0x4a4c20`/`s4.n 0x4a4c74`/`k74.y0.b 0x6941e8`/`p3.e 0x580ebc`）、`classes10.dex`（`i3.s 0x40a5b4`/`i3.r 0x40a598`/`a94.k.P 0x1cd97c`/`a94.g.P 0x1cd180`/`z84.c.P 0x7b21b8`）。
- 源码（只读）：`feature/GroupConvStore.kt:153-233`（sync/Op.Delete）、`feature/NativeConvInsert.kt`（b2 反射链模板）、`data/GroupConvPlan.kt:141-146`（delete*Sql）、`feature/ConvFilter.kt:1329-1332`（gconv=1 不注入）、`feature/GroupRowMenu.kt:141`/`GroupManagerPage.kt:148`（删组触发 force sync）。
- 前置结论引用：`docs/_r64_microx_instant_group.md`（建组注入推断 + A3 行对照）、`docs/_r65_host_cache_refresh_probe.md`（s4.b 事件码 + R66 甲-2 建组真机链 + HCR 探针设计）、`docs/_r67_b2_native_insert.md`（b2 落点 + §7.4 删除红线）、`docs/_r69_review.md`（force sync 节流绕过）。
- 微X dex：`_mods/wechatxposed.apk` / `_mods/x/assets/` 7 密文 —— **[取证缺口]** 未解、未碰 adb，详见「取证纪律声明」。

---

## 7. 真机反向对照结果（§5 第 10 条，2026-11-19 20:17）

**目的**：坐实正向「727ms 秒删」确由 A′ 补发的 `s4.b(5)` 通知带来，而非其它变量（force sync / 宿主自然重查）。采用单变量 A/B——只关 `chat.gconv_delete_notify`，其余开关不动。

**单变量确认**（冷启后精确回读）：
```
chat.gconv_enabled       = true    （不变）
chat.gconv_native_insert = true    （不变）
chat.gconv_delete_notify = false   （唯一自变量）
```
`kvset chat.gconv_delete_notify false` → 冷启让 `HostConvDelete.install` 读到 false → HCR 探针 ON（20:06:51）→ logcat 清（20:06:54）。

**日志铁证**（用户建组「反向测试」再删除）：
```
20:17:39.984 MGR create id=165 name=反向测试
20:17:40.483 GCONVSTORE sync start groups=1
20:17:40.489 nativeInsert wxgrp_00000000000000a5 OK rowid=3693   ← 建组仍秒出（b2 开关 true，未受影响）
20:17:53.024 MGR delete id=165 name=反向测试
20:17:53.673 GCONVSTORE sync plan desired=0 existing=1 ops=1
20:17:53.674 op=deleteConv   user=wxgrp_...00a5 OK                ← 库行照删（force sync，+0.65s）
20:17:53.674 op=deleteContact user=wxgrp_...00a5 OK
                 ✗ 无 deleteNotify ... sent                       ← A′ 关闭，未补发通知
                 ✗ 无 s4.b event=5                                ← 宿主未收到删除通知
                 ✗ 无 p3.e event=5 / p3.f cursor-1 / notify ...p3  ← adapter 未重查
20:19:12.314 HCR notify com.tencent.mm.ui.conversation.t0          ← 仅 t0（非 p3），与会话列表无关
```
**用户肉眼**：删除后界面旧行不消失，**要冷启才消失**——与日志「无 event=5 / 无 p3 重查」完全吻合。

**A/B 对照判定**：

| 维度 | 正向（delete_notify=true，11-18 23:50） | 反向（delete_notify=false，11-19 20:17） |
|---|---|---|
| 库行删除 | deleteConv OK | deleteConv OK（两者均靠 force sync，与开关无关） |
| `s4.b event=5` | 删后 +0.46s 发出 | **无** |
| `p3.e`/`p3.f`/`notify p3` | 全部触发，count 1577→1576 | **全部未触发** |
| 界面消失 | 727ms 秒删（原生 adapter 重查） | **不消失，冷启才消失** |

**结论**：库行在两种条件下都删（force sync 与开关无关），唯一差异是 A′ 是否补发删除通知——无通知则宿主 adapter 旧行冻结、只能冷启重载。**§5 第 10 条反向判据 PASS**，正向+反向构成完整因果闭环：即时消失确由 A′ 的 `s4.b(5)` 带来。附带坐实两点：b2 建组（event=2）不受本开关影响（反向中建组仍秒出）；关开关对租用侧零影响（gconv 仍 true，其它功能正常）。

**现场恢复**：`kvset chat.gconv_delete_notify true`（20:20:19 now=true）→ HCR 探针 OFF（20:20:23）→ 冷启重载 → 三开关回读全 true。设备已还原为正向状态，无观测残留。

---

[DONE] R70 只读取证完成。微X 删组链 dex 级静态取证受阻（业务 dex 加密），基于 R66 建组真机链 + 宿主 delete dex 推断为「删行 + `s4.b(5,s4,user)` 宿主通知 → 重查消失」（删组本体 [需真机]）。宿主自身 delete dex 铁证：事件码 5、删单行用具体 username、全清才用空串、`b()` 只发事件不写库。推荐方案 A′＝execSQL 删行不变 + 事务外补发 `s4.b(5,s4,wxgrp_<hex>)`，新增 `feature/HostConvDelete.kt` + 开关 `chat.gconv_delete_notify`（默认关）+ VersionGate 绑 2588；全红线满足、不动节流、不破坏 b2。方案 B 渲染层注入因用户已否决自绘行 + 与 b2 双轨冲突，不推荐。必须真机验证判据见 §5。
