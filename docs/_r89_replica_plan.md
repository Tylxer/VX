# R89 复刻方案：微X式「查询层过滤」完整实现方案（分阶段，逐节写盘）

> 状态：**仅方案**。未改 `app/src/main` 一个字节，未装机，未操作设备，未提交。
> 目标：加入分组后，归组会话从微信**主会话列表**消失（adapter 原始 `n` 1581→1580），只有进入分组才能看到；**不改微信库行、不删消息、不写 `rconversation`/`rcontact`/`message`**。
> 上一版 R1（layout 显示层过滤）方案 `docs/_r89_group_hide_main_plan.md` 已被用户否决，本文件**不做 R1**，改走其 §11 对比表里的「查询层复刻」路线。
> 本文依据的实证基线、查询链 dex 证据、现成源码能力全部来自团队已确认材料；结论按 `[已证实]` / `[推断]` / `[需真机]` 分级，**不编造 dex 地址与方法签名**，不确定处给出取证方法。

---

## 0. 证据基线与分级（开卷必读）

### 0.1 已确认硬证据（不要推翻，本方案的地基）

| 编号 | 事实 | 级别 |
|---|---|---|
| E1 | 微X实测 2026-11-28：临时分组 `wx__c_1a22270cbc3`；加成员 → 主 adapter `n` 1581→1580、分组页 `w8.W count` 0→1、伴随 `s4.b(event=3)`；移出 → `n` 回 1581 | `[已证实]`（R89 §10 现场日志/截图） |
| E2 | 加组期间 `rconversation` 行仍在（`msgCount=11407, unReadCount=0, parentRef='', attrflag=0`），`message` 中该 talker 11407 条仍在 ⇒ 隐藏发生在**数据源/查询层**，不是 `getView` 隐藏、不是删库 | `[已证实]`（R89 §10.2 SQL 取证） |
| E3 | 真实映射：显示名「坚锋-方旭凯」= `wxid_bwmfoul0pp0021`（`conRemark`；nickname「喧嘩」）；同名 `@openim` 是另一联系人 | `[已证实]`（R89 §10.1） |
| E4 | 宿主会话列表查询链 `t0.N → s4.t → z84.z.k → z84.z.g → z84.e.s → rawQueryWithFactory`；SQL 在 `param.args[1]`；两重载（4 参 0x4bbf48 / 5 参 0x4bbf70）；全 15 dex 无 `SQLiteDatabase` 子类 ⇒ `hookAllMethods` 覆盖完整；`param.thisObject` 是 wcdb 库实例 | `[已证实]`（`docs/_r55_fix.md` SEC9 + `GconvPage.kt:462-471`） |
| E5 | 原生会话列表 SQL 开头 `"select * from rconversation where "`；这条链 **100% 不走 `rawQuery`** ⇒ 现有 HCR 的 `sqlHook`（只挂 `rawQuery`/`execSQL`/`insert`）看不到 | `[已证实]`（任务书 + R55 SEC9；SQL 原文待阶段0 探针在本机复核 ⇒ 整机语义 `[需真机]`） |
| E6 | 我方现成能力：`GconvPage.kt:483-548` 的 `installDbCapture` + `captureHook`（命中即 `unhookCapture` 的**卸载语义本方案不采用**）；`SnsSqlRewrite.kt` 纯函数惯例；`GroupDb.kt:696-703 allMemberWxids()`；`HostConvDelete.kt:69-79 notifyDeleted` 反射链；`HostCacheRefreshProbe.kt`；`EntryReceiver.kt` 探针入口；`tools/dev.py probe`；`PinFoldLogic.configStamp` | `[已证实]`（源码逐行读） |
| E7 | R1 方案的 §10/§11 已给出微X基线、R1 vs 查询层实证对比；查询层的真实优势是**语义覆盖**（搜索/@选人器/未读聚合）与**无映射层**，但这两项**未实测** | `[已证实]`（文档事实）；优势本身 `[需真机]` |

### 0.2 分级约定

- `[已证实]`：来自已读源码 / 已读文档 / 团队给的硬证据，本文不重新推翻。
- `[推断]`：基于已证实事实的设计推导，装机前不能当结论用，必须标注并给验证路径。
- `[需真机]`：必须装机后由用户手动导航 + 只读日志/截图确认；**本方案不代替用户点击、不自动操作设备**。

### 0.3 关键决策：为什么必须阶段0 先行

E4/E5 只证明了「走 `rawQueryWithFactory`」和「SQL 开头前缀」，**没有证明宿主 SQL 的完整 WHERE 形状**（有无 `order by` / `limit` / 复杂 `or` / 参数占位 `?` / 前缀注释）。这些直接决定阶段1 纯函数的判据边界与插入位置。若跳过阶段0 直接写死判据，等于在看不见的 SQL 上动刀，是本项目 R42-N1N6 教训的典型触发方式。⇒ **阶段0 是前置门槛，抓不到 `"select * from rconversation where "` 原文，不得进入阶段1 定稿。**

---

## 阶段0 — 抓宿主 SQL 原文（前置，最重要）

### 0.0 目标与成功判据

**唯一目标**：在**不改任何行为**的前提下，看到宿主主会话列表查询发送给 `rawQueryWithFactory` 的 **SQL 原文全文**，并证明它确实来自 `s4.t` 那条链。

- 成功判据（缺一不可）：
  1. 日志里出现以 `HCR SQLW` 开头的行，`sql=` 后**能看到 `"select * from rconversation where "` 原文**（大小写按 E5 前缀，允许后续任意 WHERE 内容）；
  2. 同一发的 `stack=` 里能找到 `s4.t` 或 `z84.z` / `z84.e` 关键帧（E4 链的实现帧名）；
  3. 从加组前到加组后，这条 SQL 的**文本不变**（微X是查询层过滤但用别家机制；我方阶段1 才改写，阶段0 只见原文）。
- 不成功时：停在阶段0，拿 `HCR hook <name> FAIL` / 0 行 `HCR SQLW` 回报，**不得猜 SQL 形状往下写判据**。

### 0.1 探针设计：扩展 `HostCacheRefreshProbe`，新增 `sub=sqlwatch`

复用现成 HCR（tag `HCR`），**不新建探针文件**；在 `HostCacheRefreshProbe.kt` 内新增一个独立开关与一个 hook：

| 项 | 设计 | 级别 |
|---|---|---|
| 开关变量 | `@Volatile var sqlWatch = false`，**与现有 `enabled` 完全独立**（`enabled` 控 H1~H8 常规观测，`sqlWatch` 只控新增 SQL 原文观测） | `[已证实]`现有 `enabled` 写法见 `HostCacheRefreshProbe.kt:44-46`；新增变量为 `[推断]`设计 |
| 冷启默认 | `false`。广播翻转，**不写 Kv、不持久化**，冷启即恢复静默（与 HCR 现有探针语义一致） | `[推断]`（沿用 HCR 纪律，`HostCacheRefreshProbe.kt:33-38`） |
| Hook 落点 | `com.tencent.wcdb.database.SQLiteDatabase` 的 `rawQueryWithFactory` **全部重载**，`XposedBridge.hookAllMethods(cls, "rawQueryWithFactory", sqlWatchHook)` | `[已证实]`落点与 SQL 参数位置见 E4 / `GconvPage.kt:490`；`sqlWatchHook` 为新增 `[推断]` |
| Hook 安装时机 | 常驻安装（跟 HCR `install()` 一起，在 `HookEntry.kt:347` 的 `runCatching` 里）；`guard("H8b-sqlwatch")` 单钩失败只记 FAIL + WARN，**绝不整体禁用 HCR、绝不崩微信** | `[推断]`（复用 `guard()` 与 `install()` 现有模式，`HostCacheRefreshProbe.kt:298-306`） |
| 覆盖率自检 | 安装后日志 `HCR hook H8b-sqlwatch OK/FAIL`；成功时打 `HCR SQLW installed x<重载数>`（E4 预期 x2） | `[推断]` |

### 0.2 `beforeHookedMethod` 回调体（严格只读）

```kotlin
// 新增（伪代码，落点为 HostCacheRefreshProbe.kt，H8 之后）
// ⛔ ThreadLocal 重入守卫：AntiRevokeHint.kt:41-51 真机记录 —— hook 回调里 LogX/Kv
//    若走 AOSP SQLiteDatabase.query，内部转调 queryWithFactory → rawQueryWithFactory
//    = 我们 hook 的方法 ⇒ config→Kv.getBool→query→本钩子→config 无限递归，差点必现 ANR。
//    下面 LogX.i ×3 + hostStack + getPath 都可能触发，必须包守卫 + finally 复位。
private val inSqlWatch = ThreadLocal<Boolean>()

val sqlWatchHook = object : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        if (!sqlWatch) return                      // 冷启默认关：第一行 return，零反射零拼接
        if (inSqlWatch.get() == true) return       // 重入守卫（见上）
        inSqlWatch.set(true)
        try {
            // ⚠️ SQL 在 args[1]（E4 / GconvPage.kt:518 已核实）；args[0] 是 CursorFactory
            val sql = param.args?.getOrNull(1) as? String ?: return
            if (!sql.contains("rconversation", true)) return  // 只记录会话库相关查询，控日志量
            // ① SQL 原文（整条；超长用 head+tail 分段，避免单行过长被截断）
            LogX.i("HCR SQLW sql=$sql")
            // ② 调用栈关键帧：用现成 hostStack(10)（已过滤 Xposed/LSPosed/本模块帧），
            //    判"是否来自 s4.t"看栈里有没有 s4.t / z84.z / z84.e
            LogX.i("HCR SQLW stack ${hostStack(10)}")
            // ③ 只读辅助：db 路径（getPath 是只读，失败不认领也不抛）
            val path = runCatching { XposedHelpers.callMethod(param.thisObject, "getPath") as? String }.getOrNull()
            LogX.i("HCR SQLW db=${path ?: "?"}")
        }.onFailure { /* 热路径静默或只 WARN 一次，绝不影响宿主查询 */ }
        finally { inSqlWatch.set(false) }          // ⛔ 必须复位，否则本线程后续查询永远不打日志
    }
}
```

**只读红线（最高优先级，取证阶段不可越）**：

1. ⛔ **绝不写 `param.args[...]`、绝不写 `param.result`** —— 阶段0 是纯观测，连 SQL 改写都不能做。
2. ⛔ **绝不主动调用宿主方法**（`s4.b` / `p3.f` / `t0.N` 等一律不碰）；上面 `getPath()` 是 wcdb 库实例的只读 getter，若审查认为仍越线，可整段删掉，不影响主判据。
3. ⛔ **不改微信库、不点归拢设置、不自动点击** —— 所有导航由用户手动完成（用户明确「不要自动点击，让我来点」）。
4. 探针**只打日志**；hook 安装/回调任何异常只 WARN 或静默，绝不把宿主查询搞挂（沿用 `captureHook` 的 `runCatching` 纪律，`GconvPage.kt:502-547`）。
5. ⛔ **必须有 ThreadLocal 重入守卫**（`inSqlWatch`）：理由见 `AntiRevokeHint.kt:41-51` 的 ANR 记录。`try/finally` 复位必须写全——只设不复位会让本线程后续所有查询静默，造成「探针装了但没日志」的假阴性。

### 0.3 探针入口命令与参数名

- **入口**：复用 `EntryReceiver.kt:220-232` 的 `"hcr"` 分支，新增两个 sub：
  - `sub=sqlwatch` → `HostCacheRefreshProbe.turnSqlWatch(true)`，并回显 `HCR SQLW ARMED`；
  - `sub=sqlwatchoff` → `turnSqlWatch(false)`，并回显 `HCR SQLW DISARMED`；
  - 无 sub / `status` → `status()` 额外回显 `sqlWatch=on|off` 与 `H8b` 钩子 OK/FAIL。
- **命令行**（一条搞定：清日志 → 开探针 → 等 → 拉日志 → 过滤；`dev.py probe` 已支持 `--clear` / `--grep` / `--n`）：

```bash
cd C:/Users/lx/WorkBuddy/2026-09-29-20-23-21
python tools/dev.py probe hcr sqlwatch --clear --grep 'HCR SQLW' --n 200 --wait 2
# 状态自检（确认探针 armed 且 H8b 挂上）：
python tools/dev.py probe hcr status --grep 'HCR' --n 40
# 取证结束后关掉（冷启也会自动恢复静默）：
python tools/dev.py probe hcr sqlwatchoff --grep 'HCR SQLW' --n 20
```

> 注意（来自 `dev.py` 现有注释，避免踩坑）：
> - `op=hcr` 的 sub 走 `--es sub`（`dev.py` 位置参数第 2 个），**不是** `--val` / `--q`；`op=sql` 才用 `--q`，`op=gconv` 才用 `--sql`。
> - 微信在后台时广播不投递；`dev.py probe` 有三道闸（Broadcast completed / 文件大小变化 / `--grep` 命中 0 行告警），0 行匹配时先看是否 `am start` 把微信切前台。
> - 日志 5000 行环形覆盖，取证前必须 `--clear`。

### 0.4 日志标签与判据字段

| 行 | 标签 | 用途 |
|---|---|---|
| `HCR SQLW installed x2` | `HCR SQLW` | 确认 hook 覆盖两个重载 |
| `HCR SQLW sql=select * from rconversation where ...` | `HCR SQLW` | **核心判据**：SQL 原文全文 |
| `HCR SQLW stack t0.N <- s4.t <- z84.z.k <- ...` | `HCR SQLW` | 判是否来自 `s4.t` 链 |
| `HCR SQLW db=.../EnMicroMsg.db` | `HCR SQLW` | 辅助：确认库文件 |
| `HCR hook H8b-sqlwatch OK` | `HCR` | 安装自检 |

- **成功（按 §0.7 实测修正）**：`grep 'HCR SQLW sql='` 里能看到**全量形态**——
  以 `select unReadCount, status, isSend, …` 开头、带 `from rconversation where ((parentRef is null) or …)`
  且以 `order by flag desc` 结尾，**无** `username in (`。相邻 `stack` 行含 `p3.f` 或 `s4.M`。
  ⛔ 原判据「`select * from rconversation where` 前缀」**作废**（宿主是显式列投影，不是 `select *`）。
- **失败样式与处置**：
  - 只有 `HCR SQLW installed` 没有任何 `sql=` ⇒ 冷启后用户没进主会话列表，请用户手动进一次；
  - `HCR hook H8b-sqlwatch FAIL` ⇒ 混淆类名/版本漂移，**停下取证并回报**，不猜替代类名；
  - `sql=` 有但前缀不是 `select * from rconversation where` ⇒ E5 在本机不成立，阶段1 判据必须按实际原文重写，**不得沿用猜测**。

### 0.5 装机后取证步骤（全程用户手动导航）

1. （用户操作，本方案不执行）装机并重启微信；确认 LSPosed 模块已激活。
2. 用户手动把微信切到前台。
3. 执行 `python tools/dev.py probe hcr sqlwatch --clear --grep 'HCR SQLW' --n 200 --wait 2`。
4. **用户手动**：进主会话列表 → 上下滚动几屏（触发多轮 `p3.f` 重查）→ 进任一已有分组页 → 返回主列表。全程方案侧只读日志/截图。
5. 执行 `python tools/dev.py probe hcr sqlwatchoff --grep 'HCR SQLW' --n 20`（取证即关，减少热路径开销）并 `python tools/dev.py q 'HCR SQLW' | head -80` 回看。
6. 记录并回报：SQL 原文全文（尤其 WHERE 形状、`order by`/`limit`/`?` 占位）、`stack` 关键帧、db 路径。
7. **不要**：改微信库、点归拢设置、自动点击任何控件、在取证期开启后续阶段的改写 hook。

### 0.6 阶段0 退出条件

- ✅ 拿到 SQL 原文 + 栈证据 ⇒ 阶段1 判据以**原文**为准定稿。
  已达成（§0.7）：全量原文 606 字符 + 栈 `p3.f`×32 / `s4.M`×12。
- ⛔ 任一判据不满足 ⇒ 停在阶段0，带 `HCR` 日志回报 team-lead，禁止「先写了再说」。

### 0.7 实测结果（2026-11-28 23:03~23:07，真机 2588）

探针装好并自检通过（`HCR SQLW installed x2` / `HCR hook H8b-sqlwatch OK` / `ok=14 fail=0`）。
用户**手动**滚动主列表 + 进出分组页后，抓 65 行、64 条去重 SQL。

**形态归类**

| # | 形态 | 条数 | 归属 |
|---|---|---|---|
| 1 | `SELECT * FROM rconversation WHERE username=?` | 12 | 单行点查（绑定参数） |
| 2 | `select unReadCount, status, isSend, …(21 列)… from rconversation where username in ('a','b',…) and ((parentRef is null) or (parentRef in ('', 'message_fold'))) and ( 1 != 1 or rconversation.username like '%@chatroom' …) and rconversation.username != 'qmessage' and rconversation.username != 'appbrand_notify_message' [and rconversation.username not in ('gh_…' ×N)]` | 13 | 运行期批量明细，`in` 里**只 1~3 个** |
| 3 | `select rconversation.unReadCount, rconversation.username from rconversation, rcontact where … AND ( type & 512 ) == 0 …`（另有 `/*fkz*/` 前缀版） | 2 | 未读聚合（带 JOIN） |
| 4 | `select msgCount from rconversation where username='xxx'` | 25 | 逐会话计数点查 |
| 5 | `select username,conversationTime from rconversation`；`select username,flag,conversationTime … substr(username,1,6)='wxgrp_'`；`count(*) … 'wxgrp_'` | 4 | 我方分组页 / 计数 |
| 6 | `SELECT … like 'wx^_^_%' ESCAPE '^' union SELECT …`（未读聚合 union 版） | 2 | 未读聚合 |
| 7 | `select * from rconversation where username = '…'`（含 `/*fkz*/` 前缀 2 条） | 4 | 单会话全列点查 |

**结论 A（第二次抓取修正）：主列表全量查询**存在**，且运行期就会发。**
装上调栈修正版探针后（23:14 窗口，armed 后 3 分钟，135 条 SQL），抓到 **46 条全量查询**。
精确原文（606 字符，逐字）：

```sql
select unReadCount, status, isSend, conversationTime, username, content, msgType, flag, digest, digestUser, attrflag, editingMsg, atCount, unReadMuteCount, UnReadInvite, hasTodo, hbMarkRed, remitMarkRed, parentRef from rconversation where ((parentRef is null) or (parentRef in ('', 'message_fold'))) and ( 1 != 1  or rconversation.username like '%@im.chatroom' or rconversation.username like '%@chatroom' or rconversation.username like '%@openim' or rconversation.username not like '%@%' )  and rconversation.username != 'qmessage' and rconversation.username != 'appbrand_notify_message' order by flag desc
```

三个必须记住的偏差（全部推翻 E5 的形态假设）：

1. **不是 `select *`**，是 20 列显式投影 ⇒ 「`select * from rconversation where` 前缀」判据作废；
2. **没有 `limit`**、**没有 `conversationTime desc`**，只有 `order by flag desc`；
3. 列清单会漂：这一版**缺** `hasSpecialFollow`，而 `in (…)` 版**带**它
   ⇒ 判据**绝不能比对列清单**，只能判「表名 + 结构特征」。

**结论 A2：栈证据（SQL 形态 ↔ 宿主帧，同批日志配对）**

| 形态 | 宿主栈 | 次数 |
|---|---|---|
| 全量（`order by flag desc`） | `e.s <- z.g <- p3.f <- h3.a <- g3.handleMessage` | 32 |
| 全量（`order by flag desc`） | `z$$a.a <- z.f <- s4.p <- s4.M <- s4.K <- x4.k <- x4.Z <- v1.a` | 12 |
| `username=?` 点查 | `z$$a.a <- z.f <- s4.p <- z.k <- z.d <- i9.f <- t0.e`（后台 `i2.run`） | 22 |
| `username=?` 点查 | `z$$a.a <- z.f <- s4.p <- i9.f <- t0.e`（后台 `i2.run`） | 20 |
| `username=?` 点查 | `e.s <- z.g <- p3.f`（adapter 逐条取行） | 14 |

- 两个入口（`p3.f` UI 刷新 / `s4.M` 服务侧同步）**都汇聚到 `z.g → e.s → rawQueryWithFactory`**，
  与 `docs/_r55_fix.md` SEC9 的 `z84.e.s` 一致 ⇒ **注入点选最底层 `rawQueryWithFactory` 是对的**。
- ⚠️ 实测栈里是 `s4.p`（不是方案早期写的 `s4.t`），链路名以本表为准。
- ⛔ `username=?` 点查是本轮**最多**的形态（78 条），`isConvSelect` 的点查豁免必须保留：
  后 14 条来自 `p3.f`（全量过滤后天然不含隐藏会话），前 42 条是消息到达/会话更新路径，动了会伤收发。

**结论 B：持久化已实现，且冷启读回路径已真机验证。**
`sqlWatch` 落 Kv 键 `probe.hcr.sqlwatch`（**不登记 `Prefs.ALL`**：不进设置页、不进备份导出），
`install()` 读回、`turnSqlWatch()` 写盘。
**验证方式**：`23:16:39` force-stop 后冷启，`23:16:45.062` 首帧即打出全量 SQL —— 新进程能打，只能来自
Kv 读回的 `true`（进程内状态已被 force-stop 清空）。
⚠️ 直接证据（`HCR SQLW sqlWatch=true` 那行）**没能留档**：冷启把日志环形缓冲的早期行冲掉了
（5000 行上限），`q 'sqlWatch'` 与 `q 'SQLW installed'` 都是 0 行。判持久化只能靠上面的间接证据。
⛔ 取证结束必须显式 `op=hcr sub=sqlwatchoff`（已执行，`HCR SQLW DISARMED` @ 23:17:30），
否则每次冷启持续抓 SQL。

**结论 C：注入挂点比预想更友好。**
宿主 SQL 是「可选子句拼装」出来的：`and ( 1 != 1 or … )` 里的 `1 != 1` 是恒假占位，
说明它用 `or` 串可选条件、缺项留空格（`1 != 1  ` vs `1 != 1    ` 两种空格数即两套拼装）。
末尾已有 `and rconversation.username != 'qmessage' …` 这类**现成的 `and` 链**，
追加 `and rconversation.username not in (…)` 有稳定落点，`SqlHideCond.inject` 可直接复用
（它会把整段含 `or` 的 WHERE 括号化后再 `AND`）。

**结论 D：`hostStack` 帧全废，已重写并当场验证。**
旧实现「逐条前缀排除 + `indexOfFirst`」的栈首是 `dalvik.system.VMStack.getThreadStackTrace`，
不属于任何被排除前缀 ⇒ `indexOfFirst` 恒返回 0，10 帧全是本模块自己的帧，**宿主帧一个都没有**。
改为「以本模块最后一帧为界、只取其后帧」再滤 Xposed/反射/`VMStack`，深度 10 → 24。
修完立刻拿到上面那两条全量栈 ⇒ 修正是**必要且充分**的。

**结论 E：对阶段1 判据的直接影响**

| 形态 | 是否过滤 | 理由 |
|---|---|---|
| 全量 `order by flag desc` | **要（核心）** | 主列表数据源，`p3.f` + `s4.M` 两个入口都走它 |
| `in (…)` 批量明细 | **要** | 运行期每屏明细；漏了则已分组会话回流主列表 |
| 未读聚合（`inner join rcontact` / `union`） | **要** | 否则角标虚高。⚠️ 必须**放开 JOIN 豁免**，且条件用 `rconversation.username` 限定避免列歧义 |
| `username=?` / `username='x'` 点查 | **不要** | 打开聊天 + 消息到达判定 |
| `msgCount` / 我方 `substr='wxgrp_'` | **不要** | 与列表无关 |
| 折叠页 `parentRef='conversationboxservice'` | **待定** | 语义不同，本轮不动（写进阶段3 观察项） |

⛔ 阶段2 注意：全量查询按 `flag desc` 排序、**行数会因注入而变**，必须配 `s4.b(event=3)`
通知 adapter 重建（阶段2 §2.3 已写），否则出现「隐藏了但屏幕不刷新」。

[阶段0 DONE：主列表全量 SQL 原文 + 两条栈证据 + 形态决策表 已到手]

---

## 阶段1 — 纯函数判据 + 单测（复用 `SqlHideCond` 骨架，阶段0 原文到手后定稿）

### 1.1 决策：不重造轮子，直接复用 `SqlHideCond`

主会话交叉核对发现项目里已有**几乎同构**的 SQL 改写实现：`feature/AntiRevokeHint.kt` 的 `object SqlHideCond`（`:300+`），配 `AntiRevokeHint` 的 hook 骨架（`:166-266`），且有 JVM 单测 `AntiRevokeHintTest.kt`（**现有 21 条 `@Test`**，团队口径约 20 条）。本方案**改为复用**，原「从零写 `rewriteConvSql` + 自造 8~14 条测试」的设计作废。

可直接复用（逐字引用，已读源码）：

| 能力 | 位置 | 复用方式 |
|---|---|---|
| `inject(raw, cond): String?` | `AntiRevokeHint.kt:408-474` | **直接复用**：已处理尾分号摘除（`:409`）、子查询投影星号校验（`:416`）、顶层 `where`/`order by`/`group by`/`limit`/`union` 定位（`:421-437`）、**原 WHERE 有 OR 时整段括号化**（`:443-465`）、无 WHERE 时新建 `WHERE (cond)`（`:468`）。`[已证实]` |
| `scan()` 括号深度 + 字符串内判定 | `AntiRevokeHint.kt:488-510` | `inject` 内部已用，不重复写；引号里的 `order by` / 括号不算深度的坑都已填 |
| BYPASS `/*w*/` | `AntiRevokeHint.BYPASS`（`:62`，`internal const`） | **沿用同一个前缀**：我方 `GconvPage` / 诊断 SQL 加 `/*w*/` 避开改写（`isConvSelect` 走 `startsWith("select")` 自然不匹配） |
| MARK `/*wr*/` | `SqlHideCond.MARK`（`:312`，`private const`） | **沿用同一个指纹**：注入条件自带 MARK，既是「已注入过」判据，也防 4/5 参连进两发重复注入 |
| `notIn(ids): String` 的空集收口 | `AntiRevokeHint.kt:384-388` | **照抄形态**：空列表返回 `"$MARK 1=1"`（恒真），绝不生成 `NOT IN ()` 语法错误 |
| 前置筛选套路 | `isMessageSelect()`（`:391-401`） | **仿写** `isConvSelect`：超长/MARK/点查/JOIN/`startsWith select`/表名引用 |
| 熔断 + 防递归 hook 骨架 | `AntiRevokeHint.kt:43-51, 96-99, 195-240` | **照抄**（阶段2 详述） |

### 1.2 只需新写 3 个纯函数（放进 `SqlHideCond` 同文件 / 同 object）

放进 `feature/AntiRevokeHint.kt` 的 `object SqlHideCond` 内，这样能直接用它的 `private MARK` / `BYPASS` / `scan` / `inject`，**不改任何可见性**：

```kotlin
// 新增到 SqlHideCond（伪代码）
private val FROM_CONVERSATION = Regex("""\bfrom\s+rconversation\b""", RegexOption.IGNORE_CASE)
private val CONV_POINT_QUERY = Regex("""\bwhere\s+username\s*=\s*\?""", RegexOption.IGNORE_CASE)
private const val MAX_WXIDS = 200   // 对齐 SqlHideCond.MAX_IDS 口径 [推断]

/** 仿 isMessageSelect：只认「读 rconversation 的 select」，其余一律不碰 */
fun isConvSelect(sql: String): Boolean {
    if (sql.length > MAX_SQL) return false          // MAX_SQL=4096，复用现成常量
    if (sql.contains(MARK)) return false            // 已注入过（含 4/5 参连进两发）
    if (JOIN.containsMatchIn(sql)) return false      // 列名歧义会触发熔断，放弃改写
    if (CONV_POINT_QUERY.containsMatchIn(sql)) return false // 按主键点查要能打开聊天，别动
    val s = sql.trimStart()
    if (!s.startsWith("select", ignoreCase = true)) return false
    if (!s.contains("rconversation", true)) return false
    return FROM_CONVERSATION.containsMatchIn(s)      // 词边界：排除 appbrandnotifymessage
}

/** 字符串 wxid 版 notIn：与 notIn(List<Long>) 唯一差异是单引号 doubled */
fun notInWxids(wxids: List<String>): String {
    if (wxids.isEmpty()) return "$MARK 1=1"         // 与 :387 同款收口
    return "$MARK username NOT IN (" +
        wxids.joinToString(",") { "'" + it.replace("'", "''") + "'" } + ")"
}

/** 入口：清洗 → 前置筛选 → 生成条件 → 复用 inject */
fun rewriteConvSql(sql: String, grouped: Set<String>): String? {
    if (!isConvSelect(sql)) return null
    val clean = grouped.asSequence().map { it.trim() }
        .filter { it.isNotBlank() }
        .filterNot { it.startsWith("wxgrp_") || it.startsWith("wx__") }
        .distinct().toList()
    if (clean.isEmpty() || clean.size > MAX_WXIDS) return null   // 降级：不隐藏
    return inject(sql, notInWxids(clean))           // ⭐ 直接复用，OR 括号化/子句边界全在里面
}
```

**与 `notIn(ids: List<Long>)` 的关键差异（交叉核对点）**：`AntiRevokeHint` 过滤的是 `msgId: Long`，拼出来是裸数字、无转义；本方案过滤的是 `username: String`（wxid），**必须做单引号 doubled**（与 `GroupConvPlan.quote`（`data/GroupConvPlan.kt:106`）逐字同口径：`"'" + s.replace("'", "''") + "'"`）。这是不能照抄 `notIn` 函数体的唯一一处。

### 1.3 降级路径（`rewriteConvSql` 返回 null ⇒ 原文放行，调用方打一次告警）

| 降级 | 触发 | 判据（阶段3 观测） |
|---|---|---|
| `isConvSelect` 前置不命中 | 超 4096 字符 / 含 MARK / 含 JOIN / `where username=?` 点查 / 非 `from rconversation` / BYPASS `/*w*/` | `HIDE` 降级告警一次；该查询不隐藏但微信正常；**点查豁免**须验「从搜索结果点进聊天仍能打开」 |
| 子查询投影非 `*` / `别名.*` | `inject` 的 `SUB_SELECT` 校验（`:416-419`）返回 null | 主列表该会话可能显示；但**必须不崩**（否则 `no such column` 触发熔断） |
| 空集 / 超 200 wxid | `clean.isEmpty()` 或 `> MAX_WXIDS` | 空分组一条不隐藏；超大分组不隐藏 + 告警（微X是否降级 `[需真机]`） |
| 注入后 SQLite 拒绝 | 见阶段2 `afterHookedMethod` 熔断 | `HIDE disabled: rewritten SQL rejected` 一次性日志 + 之后完全不改写（逐字恢复） |

> `JOIN` 豁免是**故意保守**：`AntiRevokeHint.kt:363-370` 记录了两张表都有同名列时 `ambiguous column` 会进熔断名单、一次查询废掉整个功能。会话列表 SQL 若是 `select * from rconversation where ...` 无 JOIN（E5 前缀），正常注入；带 JOIN 则放弃隐藏。

### 1.4 单测：**复用现有 21 条 + 新增 2 组**（不重写 8 条）

- `AntiRevokeHintTest.kt` 现有 **21 条 `@Test`**（实测数；团队口径「20 条」），覆盖：嵌套子查询插外层、order by 前插入、无 order by 追加、聚合查询、尾分号、单引号里括号、括号配对、子查询投影非星号放弃、count 括号、字符串字面量关键字、按主键点查、BYPASS 前缀、distinct/别名星号、嵌套里层投影、group by/union、空清单恒真、原 WHERE 有 OR 整段括号化、字符串里 order by、子查询带别名、group by+having、带 join 放弃。**这些随 `inject` 直接复用，不必重写。**
- 新增 2 组（`ConvSqlHideTest.kt`，纯 JVM）：

| 组 | 用例 | 期望 |
|---|---|---|
| **① 表名识别**（4 例） | `select * from rconversation ...` | `isConvSelect=true` |
| | `select * from appbrandnotifymessage ...` / `select * from message ...` / `select * from SnsInfo ...` | `false`（词边界 `\bfrom\s+rconversation\b` 不误命中） |
| | `/*w*/ select * from rconversation ...` | `false`（BYPASS 我方查询） |
| **② wxid 转义与收口**（5 例） | `notInWxids(listOf("wxid_o'hara"))` | `'wxid_o''hara'`（单引号 doubled） |
| | `notInWxids(emptyList())` | `"$MARK 1=1"`（恒真，无 `NOT IN ()`） |
| | `grouped={" ", "", "wxid_ok", "wxid_ok", "wxgrp_x"}` | 只留 `'wxid_ok'`（空白/重复/伪会话前缀全过滤） |
| | 含 `MARK` 的 SQL 再走 `rewriteConvSql` | `null`（幂等，4/5 参连进两发不重复注入） |
| | 同一条 `select * from rconversation where ...` | `isMessageSelect=false` 且 `isConvSelect=true`（两套判据互斥，不会互相改） |

### 1.5 语义变异验证（沿用现有测试，4 组必做）

| 变异 | 施加方式 | 预期红灯 |
|---|---|---|
| M1 | 删 `isConvSelect` 的 BYPASS/MARK 检查 | 新增①的 BYPASS 例 + 现有 MARK 例红 |
| M2 | 删 `inject` 的 OR 整段括号化（`:450`） | 现有「原 WHERE 有 OR 必须整段括号化」红（静默失效变可见） |
| M3 | `notInWxids` 不做 `'` doubling | 新增②转义例红 |
| M4 | 空集合不收口成 `MARK 1=1` | 新增②空集例红（`NOT IN ()` 语法错误） |
| M5 | `isConvSelect` 改成 `contains("rconversation")` | 新增①的 `appbrandnotifymessage` 例红 |
| M6 | 去掉 `inject` 的子查询投影校验 | 现有「子查询投影非星号放弃」红 |

> 全部通过才允许进入阶段2。新增测试只测**我们新写的判据与转义**，`inject` 的正确性由现有 21 条背书。

[阶段1 DONE]

---

## 阶段2 — Hook 实现规格

### 2.1 落点与常驻策略

**新增文件**：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/ConvSqlHide.kt`（建议名），在 `HookEntry.kt` 的 `GconvPage.install(lpparam)`（现 `:231`）**之后**装一次：

```kotlin
runCatching { ConvSqlHide.install(lpparam) }
    .onFailure { LogX.w("ConvSqlHide install failed: ${it.javaClass.simpleName}") }
```

| 项 | 规格 | 级别 |
|---|---|---|
| Hook 方法 | `com.tencent.wcdb.database.SQLiteDatabase` 的 `rawQueryWithFactory`，`XposedBridge.hookAllMethods(cls, "rawQueryWithFactory", hook)` 覆盖 4/5 参两重载 | `[已证实]` E4 / `GconvPage.kt:490` |
| SQL 参数 | `param.args[1] as? String`（args[0] 是 CursorFactory） | `[已证实]` E4 / `GconvPage.kt:518` |
| 生命周期 | **常驻不卸载**（与 GconvPage `captureHook` 的「命中即 unhook」相反）；冷启装一次，靠开关控制行为 | `[推断]`设计选择：卸载/重装在成员变更+热路径下有时序坑 |
| 回调体 | 只写 `param.args[1]`（命中时）；**绝不写 `param.result`、绝不主动调宿主方法、绝不写库** | `[推断]`红线 |
| **递归保护** | **必须有自己的 `ThreadLocal<Boolean> inHook` 重入守卫**，照抄 `AntiRevokeHint.kt:43-51, 96, 199-220`：`if (inHook.get()==true) return; inHook.set(true); try { ... } finally { inHook.set(false) }` | `[已证实]` ANR 教训：hook 回调里 `Kv.getBool`/`LogX` 若走 AOSP `SQLiteDatabase.query` → 转调 `rawQueryWithFactory` → 重入本钩子无限递归。本方案 `LogX` / 读快照 / 熔断日志都在回调里，**不设守卫可能直接卡死 DB 线程** |
| **熔断** | `afterHookedMethod` 照抄 `AntiRevokeHint.kt:223-236`：只在本次改写的 SQL 被 SQLite 判为 `syntax error`/`no such column`/`misuse`/`ambiguous column` 时 `disabled=true` 永久停用本进程改写；`database is locked` 等环境异常不算 | `[已证实]`熔断机制同构；复用 `rewritten` ThreadLocal 记录本次改写串，after 比对 `param.hasThrowable()` |

**热路径三级短路**（顺序固定，越便宜越靠前）：

1. **开关**：`if (!enabled) return` —— 第一行，`enabled=false` 时零反射、零字符串拼接、零集合读取。
2. **SQL 前缀快筛**：`val sql = param.args?.getOrNull(1) as? String ?: return`；`if (!sql.startsWith("select", true) || !sql.contains("rconversation", true)) return` —— 绝大多数全进程查询在这一层被挡掉（朋友圈 / 联系人 / 搜索库等）。
3. **最后取 grouped 快照**：读 `@Volatile` 引用（不读库），再调 `rewriteConvSql(sql, groupedSnapshot)` 做完整识别与拼接；非 null 才 `param.args[1] = out`。

> 开销量级 `[推断]`：开关关闭时 hook 本身约等于一次 `if`；开启时全进程查询多一次 `startsWith` + 一次 `contains`，只有宿主会话列表那几发走到完整扫描。真机用 `HCR` 时间戳观察是否有卡顿/ANR（阶段3 F 项）。

### 2.2 grouped 集合缓存与失效（热路径绝不读库）

| 字段 | 规格 |
|---|---|
| `@Volatile private var groupedSnapshot: Set<String> = emptySet()` | **不可变快照**，整体替换引用；遍历时不修改同一对象 |
| `@Volatile private var groupedVersion: Long = 0L` | 每次刷新 +1；日志/判定带版本号，便于时间戳核对 |
| `refreshGrouped()` | 读 `GroupDb.current()?.allMemberWxids()`（`GroupDb.kt:696-703`）→ `toSet()` 冻结 → 整体发布到 `groupedSnapshot` + `groupedVersion++`；成功/失败都打一行带时间戳的 `HIDE grouped refreshed v=$v n=$n` / `HIDE grouped read fail → emptySet()` |

> ⛔ **硬约束：`allMemberWxids()` 绝不能在 `rawQueryWithFactory` 的 before 热路径里同步调用**。`GroupDb.kt:696-703` 是一次 `readableDatabase.query(T_MEMBER...)` **全表查询 + 逐行 cursor.moveToNext**，无缓存；而 `rawQueryWithFactory` 是微信全进程 DB 查询的唯一落点。在热路径里调它 ⇒ 每查一次会话列表就多一次全表 cursor 全扫，还会与宿主查询**抢同一个 wcdb 连接**（项目已有 R54「两线程同时 rawQuery/execSQL 同连接」风险记录）。`[已证实]`源码形态 + `[推断]`风险评估。
>
> 正确形态：模块级 `@Volatile cachedGrouped` 不可变快照（发布即整体替换，避免读到半更新集合）；**只在 `ConvFilter.refreshMembers()` 标记 dirty**，真正的重读放到**非热路径**（`refreshMembers()` 的 io 线程或 install 时读一次），热路径只读这个 volatile 引用。目标开销：一次 volatile 读 + 一次开关判断 + 一次 SQL 前缀判断，取集合 **O(1)**。

**失效触发点**（均为低频、非 SQL 热路径）：

1. **冷启 install**：起**一次性后台线程**读一次（DB 文件在磁盘，`GroupDb.current()` 可打开）；不占 hook 回调。读失败置 `refreshPending=true`，下一次候选 SQL 到来时仅一次在后台重试。
2. **成员变更（单挂接，主会话复核修正）**：⛔ **不要挂 `GroupConvStore`** —— 它只覆盖伪会话写库，漏掉真实的成员增删路径。唯一汇聚点是 `ConvFilter.refreshMembers()`（`ConvFilter.kt:673-687`，源码注释 :684 明写「全部 10 个成员写库入口都收敛到本方法」）。在这里调 `refreshGrouped()` + `notifyRefreshed()`：一处覆盖 `GroupDb.addMember/removeMember/addMembers/removeMembers` 等全部成员写路径。同时复用现有 `membersVersion`（:681 已 ++），不要另造版本号。
3. **即时生效的先后顺序（关键）**：成员变更路径必须 **先 `refreshGrouped()` 完成，再 `notifyRefreshed(event=3)`**（见 2.3）。否则宿主重查拿到的还是旧快照，白刷新。
4. **读库失败**：发布 `emptySet()`（**宁可暂时显示，绝不残留隐藏**），并 WARN；下一事务再刷回。

**与 `configStamp` 的关系**：

- 本 hook 在**宿主查询层**，不在 `ConvFilter` 的 layout 层 ⇒ **不依赖 `PinFoldLogic.configStamp`**（`PinFoldLogic.kt:62-70`），configStamp 的 7 参签名与现有测试逐字不动。
- `[推断]`风险提示：若将来 R1 与本方案**同时**开启，两边必须共用同一版本快照，否则会出现「layout 用新分组、SQL 用旧分组」。届时给 `configStamp` 加一个**带默认值**的末参 `groupedVersion: Long = 0L`（老 7 参调用与测试不变），并加 1 条「版本翻转 ⇒ stamp 必变」的测试。**本次 R1 已否决，不做此改动。**

### 2.3 即时生效：成员变更后补发 `s4.b(event=3)`

微X实测加组伴随 `s4.b(event=3)`（E1）。我方复用 `HostConvDelete` 的反射链，只把事件码从 5 改为 3：

**类名 / 方法签名 / 参数形状**（来自已读 R70/R55 文档，本文未重新反编译）：

| 项 | 值 | 级别 |
|---|---|---|
| 单例容器 | `vj0.h8`（`classes7.dex`），static `b()` → `vj0.e` | `[已证实]` R70 文档 / `HostConvDelete.kt:34-36,84-88` |
| 取实例 | `vj0.e.v()` → `s4` 实例（声明类型 `o8`） | `[已证实]` 同上 |
| 通知方法 | `k74/y0.b(int, k74/y0, Object)`，`s4` 继承未覆写（`classes4.dex 0x6941e8`）；**只投递事件总线，不写库、不删消息** | `[已证实]` R70 反汇编结论 / `HostConvDelete.kt:15-18,37` |
| 参数形状 | `(3, s4, username: String as Object)` —— 第 2 参传 `s4` 自己，第 3 参传**具体 username**（⛔ 绝不传空串：空串走 `p3.N=true + i3.r(null,1)` 全清分支，副作用大） | `[已证实]`形状来自 R70；`username` 取值语义 `[推断]` |
| payload 取值 | 微X基准是分组伪会话 id `wx__c_...`（E1）；我方建议先用 `wxgrp_<hex>`（`GroupConvPlan.usernameOf`），真机以 `HCR s4.b event=3 user=...` 与 `HCR count` 变化核对；若无效再试成员真实 wxid | `[推断]` + `[需真机]` |
| 实现落点 | ⚠️ **不要复用 `HostConvDelete.enabled`**（`HostConvDelete.kt:44-56` 绑死 `Kv.getBool(GCONV_DELETE_NOTIFY) && Kv.getBool(GCONV_MODE)`，install 时读一次）——关掉「删除通知」会把「隐藏即时生效」一并带关。建议给 `HostConvDelete` 加**通用** `notify(event: Int, username: String)`（只封装反射链、不读任何业务开关），由各业务开关各自调用；或新建独立封装。**本功能的开关必须是独立的 `GCONV_SQL_HIDE`**（见 2.5） | `[已证实]`耦合事实见 `HostConvDelete.kt:44-56`；`[推断]`改造形态 |
| 空串红线 | payload 必须用**具体 username**（分组行 `wxgrp_<hex>` 或真实 wxid）；⛔ 绝传空串——空串走 `p3.N=true + i3.r(null,1)` 全清分支（`HostConvDelete.kt:20-21` 注释，真机踩过） | `[已证实]` |

```kotlin
// 伪代码：与 HostConvDelete.kt:69-79 逐字同构，只改 EVENT=3
XposedHelpers.callMethod(s4, "b", 3, s4, username)   // 只发事件，不调 s4.k/n/l（那些才是真删 SQL）
```

- **触发时机**：在 `ConvFilter.refreshMembers()`（:673-687，全部 10 个成员写库入口的汇聚点）里挂一次；加入 / 移出 / 删组都会收敛到这里。**先 `refreshGrouped()`，后 `notifyRefreshed()`**（2.2.3）——顺序反了宿主重查会拿到旧快照，等于白刷新。
- **为什么这样就即时**：`s4.b(3,...)` 触发宿主主 adapter 重查 → 再次进 `rawQueryWithFactory` → 我们的常驻 hook 用**新快照**改写 SQL → 会话消失/恢复。闭环 `[推断]`，真机以 `HCR s4.b` 与 `HCR count` 的时间戳对齐验证。
- ⛔ **绝不**调用宿主 delete API `s4.k/n/l`；那才是真删 SQL，会连带删消息，违反红线。

### 2.4 与现有 hook 的冲突排查（全项目已有 6 处挂 `rawQueryWithFactory`）

**逐处清单（2026-11-28 用 Grep 实测，`file:line` 为 hook 安装点）**

| # | 位置 | 性质 | 装法 |
|---|---|---|---|
| 1 | `feature/GconvPage.kt:490` | 自建捕获 db 句柄，命中即卸 | `hookAllMethods("rawQueryWithFactory")` |
| 2 | `feature/AntiRevokeHint.kt:177` | 常驻，改写 message 查询 | `for (m in listOf("rawQueryWithFactory","rawQuery"))` |
| 3 | `probe/LabelHuntProbe.kt:288` | 只读探针（默认关） | 同上双挂 |
| 4 | `probe/RevokeHuntProbe.kt:135` | 只读探针（默认关） | 批量签名列表含 `rawQueryWithFactory` |
| 5 | `probe/SnsHuntProbe.kt:187` | 只读探针（默认关） | 批量签名列表含 `rawQueryWithFactory` |
| 6 | `probe/HostCacheRefreshProbe.kt:257` | R89 阶段0 sqlwatch（默认关，已持久化） | `hookAllMethods("rawQueryWithFactory")` |

> 早期版本写「4 处」漏了 `SnsHuntProbe` 与阶段0 探针本身，以本表为准。
> ⚠️ 探针类（3/4/5/6）默认全关，但 **hook 是常驻的**（`enabled=false` 首行 return）⇒ 阶段2 的改写 hook
> 一旦开始工作，这些探针若被临时打开，看到的是**改写后**的 SQL，不要拿它当原文证据。


| 对象 | 是否冲突 | 依据 / 处置 |
|---|---|---|
| `GconvPage.captureHook`（命中即卸） | **不冲突，靠 MARK 指纹** | 4 参版内部转发 5 参 ⇒ 一次宿主查询连进两发 hook（E4/R55 SEC9）。第一发改写写入含 `/*wr*/` MARK 的 SQL，第二发 `isConvSelect` 见 MARK 直接 false ⇒ 不重复注入。captureHook 只读 `contains` + `getPath` + 命中即卸自己，不写 args/result ⇒ 不受改写影响。 |
| `AntiRevokeHint.hook`（常驻，有 `inHook`） | **判据互斥，不会互相改** | `isMessageSelect`（`from message`）与本方案 `isConvSelect`（`from rconversation`）**表名互斥**；同一条 SQL 不会被两个改写。两边各有独立 `ThreadLocal inHook`，但注意：**两套 hook 都在同一方法上**，我方回调里 `LogX` 触发的重入只被我方 `inHook` 挡住，AntiRevokeHint 的 `inHook` 是它自己的实例 —— 靠各自守卫即可，不共享。 |
| `LabelHuntProbe` / `RevokeHuntProbe`（只读探针） | **不冲突** | 只读观测、不改 args/result；默认关/低频。串在 hook 链上不会改变改写结果。 |
| `SnsSqlRewrite` / `SnsGuard` | **不冲突** | 落点是 `execSQL` 的 `UPDATE SnsInfo`（`SnsSqlRewrite.kt` / `SnsGuard.kt:873-911`），不同方法；纯函数 + nullable 契约，互不读对方 param。 |
| HCR `sqlwatch`（阶段0 探针） | **阶段0 无冲突；阶段2+ 注意顺序** | 阶段0 取证装机**不含改写 hook**（纯探针构建）⇒ 看到的一定是原文。阶段2+ 两个都开时：**先装者先见原文**（Xposed 多 hook 的 `before` 执行顺序本文按「安装顺序」`[推断]`，**不写死**，真机用两条 tag 时间戳核对）。需要原文时关掉改写开关，或单独用阶段0 构建。 |
| HCR 常规 `sqlHook`（`rawQuery`/`execSQL`/`insert`） | **不冲突** | 落点不同（`rawQuery` 是上层入口，E4 证明宿主会话链不走它）；探针默认关、只打日志。 |
| 我方 `GconvPage` / 诊断 SQL | **必须 BYPASS，否则分组页空白 / 诊断失明** | 分组页走 `rawQuery(sql,null)`（`:1148`）转发进 `rawQueryWithFactory` ⇒ 会被本 hook 看到。统一改用**现成 BYPASS `/*w*/`**（`AntiRevokeHint.BYPASS :62`，不再用旧稿的 `/*wxg*/`）：`GconvPage.selectSql` 前缀 `/*w*/`；我方所有 `rconversation` 查询与诊断 SQL 同样加前缀。 |

**安装顺序建议** `[推断]`：`GconvPage.install`（`:231`）→ `ConvSqlHide.install`（其后）→ HCR（`:347`）；让 captureHook 先看原文认领 db。AntiRevokeHint 的 install 位置若在更前，其 hook 先跑也只看 `from message`，与本方案互斥。**该顺序不写死为事实**，真机用 tag 时间戳核对；captureHook FAIL 按 §0 处置回报。

**熔断与降级的可观测判据**：

- 注入后 SQLite 报 `syntax error` / `no such column` / `misuse` / `ambiguous column` ⇒ `HIDE disabled: rewritten SQL rejected` 一行 ⇒ 之后**本进程完全不改写**（逐字恢复原文），验收按「功能失效但微信不崩」处理，不视为成功。
- `JOIN` / `where username=?` 点查 / 子查询投影非星号 ⇒ `rewriteConvSql` 返回 null ⇒ 该查询不隐藏但微信正常；阶段3 F 项必须验「从搜索结果点进聊天仍能打开」（点查豁免的副作用边界）。

### 2.5 开关与逐字恢复

- **持久化开关**：新增 `Keys.GCONV_SQL_HIDE = "chat.gconv_sql_hide"`，`PrefSpec` 默认 **`false`**（`[推断]`：宿主查询层 Hook 比 R1 侵入性大，默认关、显式开启；与 `GCONV_MODE` 独立便于反向对照），冷启生效。
- **即时 A/B 开关**（仅内存、不写 Kv）：`op=convsqlhide sub=on|off|status`，对应 `ConvSqlHide.turn(on)` / `status()`；用于阶段3 正反对照，冷启回到持久化值。
- **关闭语义**：`enabled=false` ⇒ hook 第一行 return，**不改 `param.args`、不改 `param.result`、不调宿主方法** ⇒ 宿主 SQL 逐字执行，adapter `n` 应回 1581（E1；真机判据见阶段3）。
- **不卸载 hook**：常驻，仅开关控制，避免卸载/重装与成员变更的时序竞态。
- **日志**：只在状态变化（刷新快照 / 改写命中 / 降级告警）时打带时间戳的一行，命中日志做节流（如每秒 ≤1 行），**绝不每发 SQL 刷屏**；阶段3 的时间戳判据以这些行为准。

[阶段2 DONE]

---

## 阶段3 — 真机验收 + 三档红线

### 3.1 验收纪律

- 前置：阶段0 原文判据已拿到；阶段1 单测（14 条 + M1~M7 变异）全绿；阶段2 `assembleDebug` + `testDebugUnitTest` 全绿；diff 交第二视角复核；装包前核分包下限 / Pro 类归属；`pm path` + `lastUpdateTime` 确认真重装，必要时冷启两次。
- **判据只认两类证据**：① 用户可见事实（截图/目视：某会话行在不在、昵称对不对、点开是不是那个人）；② 带时间戳的日志（`HCR s4.b event=3` / `HCR count` / `HIDE rewrite` / `HIDE grouped refreshed` 的**先后与时间差**）。
- ⛔ **不认**我方内部 hook 命中计数、纯布尔状态位、版本号自身作为「功能成功」的证据；它们只能做辅助定位。`n`（`HCR count`）是宿主 adapter 原始计数、带时间戳，可作**交叉证据**，但主判据仍是用户可见行。
- 所有导航**由用户手动**完成；方案侧只读日志/截图、不改库、不点归拢设置、不自动点击。

### 3.2 A~F 场景表（正反对照）

> 统一对象：显示名「坚锋-方旭凯」= `wxid_bwmfoul0pp0021`（E3，用昵称核对，**不只报 wxid**）；临时分组 `wx__c_1a22270cbc3` / 我方对应 `wxgrp_<hex>`。

| 场景 | 操作（用户手动） | 正判据（用户可见 + 日志时间戳） | 反判据 / 反向对照 |
|---|---|---|---|
| **A 基线** | 开关关，冷启进主列表 | 主列表能看到「坚锋-方旭凯」一行；分组页 `count=1`；`HCR count=1581`；`HCR SQLW` 原文无 `not in` | 若关态就看不到 ⇒ 不是本功能导致，先查别处过滤 / 账号数据 |
| **B 开关键** | `kvset chat.gconv_sql_hide=1` + `kvget` 回读 + 冷启 | 主列表**看不到**该会话；点分组行进入后按昵称**能看到**该成员；`HIDE rewrite` 时间戳紧邻 `HCR count=1580` | 关开关（或 `op=convsqlhide sub=off`）后该会话**重新出现**，`HCR count` 回 **1581**，`HIDE rewrite` 停止 ⇒ 逐字恢复（E1 反向对照） |
| **C 分组页 BYPASS** | B 状态下进分组页、滚动、返回 | 分组页能看到组内成员会话（含 `wxid_bwmfoul0pp0021`）；`HCR w8.W count≥1`；**不出现空页** | 若分组页空/少人 ⇒ BYPASS 失效（现成 `/*w*/` 没加或被别的查询漏审计），**立即停测回报**，这是最高危回归 |
| **D 移出成员** | B 状态下用户手动把该成员移出分组 | 主列表该会话**重新出现**；`HCR s4.b event=3` 时间戳紧邻 `HCR count` 1580→**1581**；`HIDE grouped refreshed` 快照 `n-1` | 若只刷新了 UI 但 `n` 不变 / 会话不回来 ⇒ 补发通知或快照刷新顺序错（2.2.3 / 2.3） |
| **E 加入成员** | A 状态下用户手动把该成员加入分组 | 主列表该会话**消失**；分组页 `count` 0→1；`s4.b event=3` 与 `HCR count` 1581→**1580** 时间对齐 | 与 D 互为正反；若需冷启才生效 ⇒ 即时生效链路没打通 |
| **F 非主列表入口（必验，宣称优势未实测）** | B 状态下分别：① 微信全局搜索框搜「坚锋-方旭凯」；② 进群聊点 `@` 选人器搜该联系人；③ 看有无未读聚合入口显示该会话 | 逐项记录**能否找到/选到**该项；`[需真机]`：E1/§11.3 声称查询层会让这些入口**一并隐藏**，本轮**第一次实测**，结果不作预设 | 关开关后再逐项对照：若这些入口重新出现 ⇒ 证明是查询层改写连带；若开关前后都找得到 ⇒ 宣称优势不成立，**如实回报，不圆场**；并验证从搜索结果点进聊天仍可用（不能因隐藏导致无法发消息） |

- **反向对照硬指标**：关开关 ⇒ `n` 回 **1581**；移出成员 ⇒ `n` 回 **1581**（E1 两条都必做）。
- **F 项裁决原则**：查询层方案宣称的「其他入口也看不到」是**优势也是风险**——若实测连带隐藏了 `@` 选人器里该联系人，导致用户**无法 @ 他/给她发消息**，应作为回归上报并讨论是否要给选人器查询加 BYPASS，而不是当作成功。

### 3.3 三档红线（R42-N1N6 教训：三档必须写全，每档给可观测判据）

#### 3.3.1 进入边界（⛔ 什么绝对不能做）

| # | 红线 | 可观测判据（违反即刻停测） |
|---|---|---|
| I1 | 不改 `rconversation` / `rcontact` / `message` 一个字节；不改 `parentRef` / `attrflag` / `flag` / 未读 | 阶段前后 `op=sql --q "select msgCount,unReadCount,parentRef,attrflag from rconversation where username='wxid_bwmfoul0pp0021'"` 逐字相同；`message` 计数 11407 不变（E2） |
| I2 | 不写 `param.result`、不返回假 Cursor、不跳过宿主查询 | 日志不出现 `HIDE` 的 result 注入；`p3.f` 返回的 cursor 行数与 SQL 改写预期一致 |
| I3 | 不调宿主删 API `s4.k/n/l`、不删消息、不清未读 | `HCR s4.b` 只应见 `event=3`；出现 `event=5`（删除）且非我方删组操作 ⇒ 违规 |
| I4 | 不碰 `GconvPage` 的 `activeGid` / `activeMembers` / `activeCursor` / `reset()` | C 场景分组页正常；无 `K7 gpage` 异常日志 |
| I5 | 不把 `wxgrp_` / `wx__` 伪会话行过滤掉（分组入口必须留） | A/B/C 场景主列表分组入口行始终可见；阶段1 T12 单测绿 |
| I6 | hook 热路径不读库；只读 `@Volatile` 快照 | `HIDE grouped refreshed` 只出现在成员变更/冷启，不随每发 SQL 刷屏 |
| I7 | 阶段0/验收期不自动点击、不点归拢设置 | 所有导航由用户手动；方案侧只发只读广播与拉日志 |

#### 3.3.2 返回边界（任一条件成立时必须逐字放行原 SQL）

| 条件 | 必须成立 | 判据 |
|---|---|---|
| 开关关 / `GCONV_MODE` 关 | hook 第一行 return；不改 args/result、不调宿主方法 | B 反向对照：`n` 1581、该会话可见、`HIDE rewrite` 停止 |
| 识别失败（SQL 形状漂移：无顶层 `where` / 非 select / 非 rconversation） | `rewriteConvSql` 返回 null，原文执行；打一次告警 | `HIDE` 出现降级告警但微信无 SQL 异常/崩溃；阶段1 T3/T14 绿 |
| `grouped` 清洗后为空 / 全空白 | 返回 null，不隐藏 | 无分组或空分组时一条会话都不隐藏（对照 R1 §7 F） |
| 读库失败 | 发布 `emptySet()`，宁可显示不残留；下一次事务再刷 | `HIDE grouped read fail → emptySet()` 后用户可见会话**回来**（不是继续隐藏） |
| 超阈值（`>900` 字面量或改写后 `>64KB`） | 返回 null，不产超长 SQL；告警 | 阶段1 T10 绿；真机大字分组不卡死、不 ANR |
| BYPASS（`/*w*/`） / 已改写（含 `/*wr*/` MARK） | 返回 null，不二次改写 | C 场景分组页不空；4/5 参连进两发不重复追加（新增②的幂等例 + 现有 MARK 例绿） |

#### 3.3.3 复位边界（每个时点都要有可观测判据）

| 时点 | 必须成立（可观测判据） |
|---|---|
| **关开关 → 再开**（含回到主页面） | 关：`HCR count` 回 1581、该会话可见、`HIDE rewrite` 时间戳停止；再开：重新 1580、会话消失。两次翻转由开关广播/冷启触发，不依赖「碰巧别的参数变了」 |
| **移出成员** | 该会话回主列表；`s4.b event=3` 时间戳紧邻 `count` 1580→1581；快照 `n-1`；反向对照成立 |
| **删分组** | 该组**所有**成员会话都回主列表；`wxgrp_` 行随 R70 机制消失；SQL 里不再出现这些 wxid 的 `not in`（用 `HCR SQLW` 在关改写构建下复核） |
| **滚回顶部 / 上下滚动** | 开态 `HCR count` 稳定 1580、关态稳定 1581；已归组会话**不因滚动方向丢失或残留**；无成员变更时 `HIDE grouped refreshed` 版本号不变 |
| **冷启** | 开态首次进主列表**即隐藏**（不要求先进一次分组才生效）；C 场景 BYPASS 仍生效（分组页可见成员）；首条 `HIDE rewrite` 时间戳在冷启之后、`HCR count` 1580 之前 |
| **账号切换 / 库重建** | `HIDE grouped refreshed v=… n=…` 在新账号首次出现且其 `n` 与新账号分组成员数一致；A 账号归组的 wxid **不在** B 账号主列表被隐藏（目视 + 昵称核对）；旧快照不跨账号残留 |

### 3.4 不做 / 待定（本轮边界）

- 本方案不改 R1 的 `ConvLayout` 任何代码，configStamp 不为本轮改动（2.2）；但 **`ConvFilter.refreshMembers()` 是唯一挂点**（3.5 回炉核对第 3 条），要加 `refreshGrouped()` + `notifyRefreshed()` 两行。
- 不改 `GconvPage` 的 `selectSql` **除加现成 BYPASS `/*w*/` 前缀外**的任何逻辑（成员集合、`whereClause`、游标换页保持原样）。
- 阶段0 探针不写 Kv、不持久化、不新建探针文件；阶段2 新增开关是唯一持久化项。
- F 项的最终裁决（是否给选人器入口加例外）**等真机数据**，不在方案里预设结论。
- 未装机、未提交；`docs/_r89_group_hide_main_plan.md`（R1）与 `docs/派发审计台账.md` 保持原样，不混入本轮改动。

### 3.5 交付与后续

1. 先按阶段0 取证；原文判据不满足就停。
2. 阶段1 复用 `SqlHideCond` + 新增 2 组单测 + M1~M6 变异全绿（现有 21 条随 `inject` 复用）→ 阶段2 接线编译。
3. 交叉复核 diff + 装包前核对 → 阶段3 用户手动 A~F + 三档红线。
4. 全部通过后，把 `[已证实]` 项固化到 `docs/conclusions/conv_list.md` 与 `docs/当前状态.md`，`[需真机]` 项按实测结果改写级别，按项目惯例拆 commit。

### 3.6 回炉核对（主会话 5+ 条补充事实逐条落位）

| # | 补充事实 | 落位 | 状态 |
|---|---|---|---|
| 1 | 复用 `AntiRevokeHint.SqlHideCond` 骨架（`inject` / BYPASS `/*w*/` / MARK `/*wr*/` / 空集收口 / scan） | §1.1、§1.2、§2.4 | ✅ 已纳入，原从零设计的 T1~T14 作废 |
| 2 | 现有 `AntiRevokeHintTest` 21 条 `@Test` 复用 + 新增 2 组 | §1.4 | ✅ 已纳入，明确「复用 20+ 条 + 新增 2 组」 |
| 3 | `s4.b(3, s4, username)` 反射链；⛔ 不复用 `HostConvDelete.enabled`（绑死 `GCONV_DELETE_NOTIFY ∧ GCONV_MODE`）；空串触发全清 | §2.3 表两行 + 伪代码 | ✅ 已纳入，开关独立见 §2.5 `GCONV_SQL_HIDE` |
| 4 | 成员变更单点挂 `ConvFilter.refreshMembers()`（:673-687，10 入口汇聚点），复用 `membersVersion`；event=3 与缓存失效都挂这一处 | §2.2 第 2 条、§2.3 触发时机 | ✅ 已纳入，明确 ⛔ 不挂 `GroupConvStore` |
| 5 | `allMemberWxids()` 全表查询禁热路径；`@Volatile` 不可变快照惰性重读；R54 同连接风险 | §2.2 硬约束段 | ✅ 已纳入，热路径 O(1) |
| 6 | 全项目 4 处挂 `rawQueryWithFactory`（GconvPage.captureHook / AntiRevokeHint / LabelHuntProbe / RevokeHuntProbe）；`isConvSelect` vs `isMessageSelect` 互斥；自有 ThreadLocal | §2.4 冲突表 + §2.1 递归保护行 | ✅ 已纳入 |
| 7 | `afterHookedMethod` 语法错误熔断（照抄 AntiRevokeHint） | §2.1 熔断行 + §2.4 末尾判据 | ✅ 已纳入 |
| 8 | JOIN / 点查 / 子查询投影降级路径的观测判据 | §1.3 表 + §2.4 末尾 | ✅ 已纳入，F 项验「点进聊天仍能打开」 |
| 9 | 阶段0 `sqlWatchHook` ThreadLocal 重入守卫 | §0.2 代码块 + 红线第 5 条 | ✅ 已由并发编辑补入（装机安全硬伤） |

> 本回炉核对为收尾自检；若后续再收到新事实，按同样格式追加行并重打 `[DONE]`。

[DONE]

