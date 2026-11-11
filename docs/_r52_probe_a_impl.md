# R52 Pro 实现 lane A · 补探针定位（让 MTG 钩子可观测）实现报告

> **lane 性质**：Pro 实现 lane A，目标＝「一次装包后，能用一条广播读出账本、能用日志看清每条 delete/execSQL 为什么被放行」。
> **独占文件（只改这两个）**：
> - `wxgroup/app/src/main/java/com/lx/wxgroup/feature/MediaTableGuard.kt`
> - `wxgroup/app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt`
> ⛔ **lane B 独占 `feature/AutoHdImg.kt`，本 lane 全程未碰**（`EntryReceiver.kt:698-701` 的 `op=hdimg` 分支原样保留）。
> ⛔ 未碰 adb / 装包 / gradle / 微信库 / 任何开关默认值 / 现网拦截逻辑 / `MediaDeleteLogic` / `cachedTokens` 生成时机 / `invalidate()`·`refresh()` 调用点。
> **昵称铁律**：好友一律 **verity** / **测试群聊** / **文件传输助手**，不写裸 wxid。
> 不确定一律标 `[推断]` / `[需真机]`（本 lane 不跑真机，接线后的现象全部 `[需真机]`）。
> **分步落盘**：先建骨架，每完成一个子步即写盘并留一行 `[DONE] <子步>`；末尾 `[DONE] ALL`。
> 锚点说明：T1 的 `文件:行号` 是**改前现状**；T2/T3 里描述的新增位置以**改后**函数/分支名定位。

---

## T1 现状锚点（改前，file:line）

### `feature/MediaTableGuard.kt`（358 行，全程 Grep + Read(offset,limit)，未整读）

| 锚点 | 位置 | 内容 |
|---|---|---|
| `TAG = "MTG"` | `:61` | 消息前缀；`LogX` 的 logcat TAG 固定 `WXGroup`（`LogX.kt:19`，本文件 `:60` 注明），靠消息前缀供 `dev.py q 'MTG'` 过滤 |
| `WINDOW_MS = 600_000L` | `:70` | 撤回期窗口 10min，与 `VoiceFileGuard.PROTECT_MEDIA_MS` 一致 |
| `MAX_EVENTS=64` / `MAX_LOG_KEYS=64` / `LOG_TTL_MS=60_000L` | `:73-75` | 事件去重封顶 / 日志节流 key 封顶 / 60s 节流 |
| `FAKE_ROWS = 1` | `:78` | 命中 delete 时伪装受影响行数 |
| 运行时状态（⛔ reset 不许清） | `enabled :81`、`gen :85`、`lastIssuedMs :89`、`cachedTokens :93`、`mediaPoolRef :97` | 判定依据，非账本 |
| 只读账本（reset 要清） | `seen :101`、`matched :102`、`blocked :103`、`skipped :104`（4×`AtomicLong`）；`events :107`、`logAt :109`（`ConcurrentHashMap`） | 进程内，不写微信库 |
| `install()` | `:113-143` | `hookAllMethods(cls,"delete",deleteGuard) :127` + `execSQL :130`；`installed hooks=delete,execSQL enabled=… pool=… :138-142` |
| `refresh()` | `:146-148` | `enabled = Kv.getBool(Keys.ANTIREVOKE_MEDIA_DELETE)`，只在 `install()`/`invalidate()` 调 |
| `invalidate()` | `:156-161` | `gen++` / `lastIssuedMs` / `cachedTokens=snapshotLiveTokens()` / `refresh()` |
| **`stat()`** | **`:164-173`** | **只读账本导出；注释 `:163` 明写「M3 本轮不接 `EntryReceiver`」⇒ 这就是本 lane 根因：账本全不可见** |
| 两个 callback | `deleteGuard :177-186` / `execSqlGuard :188-196` | `try { onBefore(...) } catch { throttledLog("cb-*") }`，异常绝不外泄 |
| `Parsed` | `:198-203` | `(table, where, args)` 只读解析结果 |
| **`onBefore()`** | **`:209-260`** | **整个包在 try/catch 里；判据顺序最便宜判断先行** |
| ├ 静默 return① `!enabled` | `:212` | 原注释「零字符串解析 / 零对象分配 / 零日志」 |
| ├ 静默 return② `param.args ?: return` | `:215` | 参数为空 |
| ├ 静默 return③ `p == null` | `:219` | `fromExecSql`/`fromDelete` 解析不出（execSQL 的非 DELETE 语句绝大多数落这里） |
| ├ 静默 return④ 非目标表 | `:222` | `!MediaDeleteLogic.isTargetTable(p.table)` |
| ├ 静默 return⑤ `cachedTokens` 空 | `:228-231` | `skipped++ return` |
| ├ 静默 return⑥ `shouldBlock=false` | `:233-239` | `skipped++ return` |
| ├ `param.result = null`（execSQL） | `:245` | void 方法跳过方法体 |
| ├ `param.result = FAKE_ROWS`（delete） | `:248` | 纯 Java 方法体跳过 |
| ├ `blocked.incrementAndGet()` + 唯一业务日志 | `:250` / `:251-255` | `throttledLog("block", "$TAG blocked … table=… where=…(48) gen=…")` |
| └ try/catch 兜底 | `:256-259` | `throttledLog("err", …)` |
| `fromDelete` / `fromExecSql` | `:263-269` / `:278-299` | 只读，不生成/不改 SQL、不改 `param.args` |
| `resolvePool` / `snapshotLiveTokens` | `:307-318` / `:321-331` | install 期一次性反射 `VoiceFileGuard.protectedMedia`；失败则快照恒空、只记账永不拦 |
| `noteEvent` / **`throttledLog`** | `:336-347` / **`:350-357`** | **60s 按 key 节流 + `MAX_LOG_KEYS` 封顶 + `LogX.i`；复用点** |

### `entry/EntryReceiver.kt`（971 行，只读 op 路由结构）

| 锚点 | 位置 | 内容 |
|---|---|---|
| `ACTION_PROBE` / `EXTRA_OP="op"` / `EXTRA_SUB2="sub2"` | `:63` / `:67` / `:75` | 广播 action 与 extra 参数名 |
| `ACTION_PROBE` 路由块 | `:160-709` | `val op = getStringExtra(EXTRA_OP) ?: return :161` → `when (op) :162` → `else -> LogX.w("ENTRY PROBE unknown op=$op") :706` → `return :708` |
| `sub` 取值惯例 | `getStringExtra("sub") ?: "stat"`（`:245,308,343,443,457,479,505,529,600`）/ `.orEmpty()`（`:667,677,688`） | 二级子命令；少数域第三层 `sub2` |
| stat 类分支日志写法 | `LogX.i("SNSG " + SnsGuard.stat()) :349` / `LogX.i(HintBadge.stat()) :602` / `LogX.i(AutoHdImg.stat()) :700` | 前缀 + `stat()` 原文；本项目靠消息前缀过滤 |
| 写/读配置分支 | `kvset :176-209`（写 Kv + 刷新 6 类，**不含 `MediaTableGuard.refresh()`**）/ `kvget :624-641` / `logclear :165-168` | ⛔ 不与新分支冲突 |
| 其它只读/写探针 | `writeprobe :666-673` / `label :307-318` / `sql :654-661` / `hdimg :698-701`（lane B，未碰） | — |

### `docs/开发回路速查.md` §四 + `tools/dev.py`

| 锚点 | 位置 | 内容 |
|---|---|---|
| 探针广播模板 | `开发回路速查.md:121-123` | `am broadcast -a com.lx.wxgroup.action.PROBE --es op <op> --es sub <sub> -p com.tencent.mm` |
| op/sub 两层 | `:138` | `op` 选功能域、`sub` 选子命令；少数域有 `sub2` |
| 探针表 | `:142-159` | 各域子命令一览 |
| `logclear` / LogX 环形缓冲 | `:111`、`:165-168`；源码 `LogX.kt:22,79-88` | 文档称「5000 行上限，写满环形覆盖不是停写」；⛔ 源码精确语义是**满 5000 行 `writeText` 整文件清空重来** + 留一行 `---- log rotated: 已达 5000 行 ----` 标记（lane B 协作提醒后复核，见 T4 #9）。`logclear` 只 `LogX.reset()` 清日志不动池/台账 |
| 三道投递闸门 | `:46-56` | ③ `--grep` 匹配行数 >0 才算探针执行；后台微信不投递、静默失败 `:113` |
| **`srcfix` 坑** | `工具 dev.py:24-27`、`开发回路速查.md:456-461` | `op=snsg sub=srcfix` 的 `sub2` 拼错会掉进 `else -> run()`（含 snapshot 写台账 + 补位写库）；`snsg sub=show` 名字像诊断实则写 `localFlag`。⇒ **新分支 `sub` 拼错必须落只读 else，绝不 fall-through 到写库** |
| `dev.py probe` extra 映射 | `工具 dev.py:13,19-20,254-256` | `probe <op> [sub]` → `--es op <op>` + `--es sub <sub>`；`--grep P` 走闸门③ |

### `docs/_r52_revokeimg_rootcause.md`（本轮背景）

- 根因（本 lane 范围）：`MediaTableGuard.stat()` 未接 `EntryReceiver` 广播 ⇒ `seen/matched/skipped/blocked` 全不可见，连「钩子有没有被调用」都分不清；`onBefore` 有 4 条静默 return，现有日志只在命中 `blocked` 时打一行。
- 事实基线 `:23-41`：真机 `kvset true` + 冷启后 `MTG installed … enabled=true pool=ok`（`:37`），但 verity 撤回图后仍打不开，`dev.py q 'MTG'` 只有两条 installed、零业务日志。
- 该报告已有一份**功能（R51 拦截）视角**的三档边界表 `:261-286`；本 lane T4 是**观测账本/日志视角**，不照抄，且必含「`op=mtg sub=reset`」与「日志 5000 行写满清空导致旧证据消失」两条。
- TTL/冷启事实：保护池 10min（`:271,280`）、`imgAnchor` 30min（`:281`）、冷启进程内集合整体清零（`:272,282`）、hook 随进程死才卸（`:283`）、行/文件被删不可回滚（`:286`）。

[DONE] T1

## T2 `op=mtg` 广播分支（改后）

### 新增广播用法（一次装包后即可用，`dev.py probe` 正确映射 `--es op` / `--es sub`）

```bash
python tools/dev.py probe mtg stat  --grep 'MTG stat'   # 读账本
python tools/dev.py probe mtg reset --grep 'MTG stat'   # 账本归零 + 回打复位后的 stat
# 等价手工：am broadcast -a com.lx.wxgroup.action.PROBE --es op mtg --es sub stat|reset -p com.tencent.mm
```
> `--grep` 必带：过 `开发回路速查.md:46-56` 闸门③（匹配行数 >0 才算探针执行）；微信须前台，否则后台不投递、静默失败（`:113`）。

### `EntryReceiver.kt`（`ACTION_PROBE` 的 `when (op)` 内，紧跟 `hdimg` 之后、`else` 之前）

- 新增 `"mtg" -> { … }` 独立分支，**不与** `kvget/kvset/writeprobe/label/logclear/hdimg/...` 任何现有 `op` 冲突；`hdimg`（lane B）一行未动。
- `sub=stat` → `LogX.i(com.lx.wxgroup.feature.MediaTableGuard.stat())`，**打印 `stat()` 原文**。`stat()` 返回串本身以 `$TAG stat enabled=…` 起头（`MediaTableGuard.kt:167`），即前缀恒为 **`MTG stat`**（与 `TAG="MTG"` 一致），`dev.py q 'MTG'` 与 `installed` / `blocked` / 新增观测行一把抓。
- `sub=reset` → `resetLedger()` 后回打一次 `stat()`（同前缀），一行里能同时看到 `seen=0 matched=0 blocked=0 skipped=0 events=0`，且 `gen/enabled/tokens` 保持原值 ⇒ 直接核对「只动了账本」。
- ⛔ **防 `srcfix` 坑**：`when (sub)` 显式列出 `stat`/`reset`，`else` 只打一行只读日志 `"MTG unknown sub=$sub"`，不写任何库。`op` 分支互斥 + 末尾 `return`（`:725`），拼错的 `sub` 关在 `mtg` 分支内，**不会 fall-through 到 `kvset`/`writeprobe` 等写库分支**（对照 `tools/dev.py:24-27`：`snsg sub=srcfix` 的 `sub2` 拼错掉进 `else->run()` 写台账+补位写库）。
- ⛔ 广播不投递时 `onReceive` 不被调用 ⇒ 本分支自然不执行、模块静默；**未加任何「检测是否投递」的看门狗/额外诊断逻辑**。

### `MediaTableGuard.kt` 新增 `resetLedger()`（紧接 `stat()` 之后）

| 清（账本） | 保留（运行时状态，⛔ 不许清） |
|---|---|
| `seen.set(0)` / `matched.set(0)` / `blocked.set(0)` / `skipped.set(0)` / `events.clear()` / `logAt.clear()` | `enabled` / `cachedTokens` / `gen` / `lastIssuedMs` / `mediaPoolRef` 全部一行不动 |
- `public`（与 `stat()`/`refresh()`/`invalidate()` 同），供 `EntryReceiver` 全限定名调用，无需新增 import。
- ⛔ 与冷启「整体清零」语义区分：冷启＝进程死亡、整个 object 重建（连运行时状态一起没）；`resetLedger()` 只归零账本，hook 不卸、开关不变、token 快照不变。T3 新增的观测节流 map `obsLogAt` 会在 T3 一并纳入本方法 `clear()`。

[DONE] T2

## T3 `onBefore()` 全量限流记账日志（本 lane 核心，改后）

### 覆盖矩阵：每一条进入钩子的 `delete`/`execSQL` 都留痕

`onBefore()`（改后 `:229-289`）每个 return 分支都插一条 `observe(...)`；`observe()`（`:394-401`）按 `table+reason` 节流后打一行，**每条至少带 5 个字段**：`execSql(delete|execSQL)`、`table(截 24)`、`where(截 80)`、放行原因 `reason`、`gen`。

| 分支（改后行） | 触发 | `observe` 的 reason | 放行原因 |
|---|---|---|---|
| `:232` `!enabled` | 开关关 | `disabled` | 钩子被调但开关关（区分「钩子没被调用」） |
| `:236` `param.args==null` | 参数为空 | `args-null` | 无参可解析 |
| `:241` `p==null` | `fromExecSql`/`fromDelete` 解析不出（execSQL 的非 DELETE 语句绝大多数落这里） | `sql-not-delete` / `delete-unparsed` | 保守放行；⭐ 不补这条就「覆盖不了每条 execSQL」 |
| `:247` 非目标表 | `!isTargetTable` | `non-target` | 白名单外（含 voiceinfo）交给既有 `VoiceGuard` |
| `:257` `cachedTokens` 空 | 池快照为空 | `tokens-empty` | 安全降级，未建快照 |
| `:266` `shouldBlock=false` | 判据不通过 | `shouldBlock-false` | 单列=` / 键列 / token / 10min 窗口任一不满足 |
| `:280-284` 命中 `blocked` | 真拦 | `reason=blocked`（写进既有 `block` 日志） | — |

- 前 4 条 `p==null` 分支（尚未解析出表）用 `table=- where=-` 占位，**不解析 SQL**（不引入 `Parsed`/子串分配）。
- 命中 `blocked` 仍走**原有** `throttledLog("block", …)`（`:280`），只把 `where` 截断 48→80 并补 `reason=blocked gen=$gen`，使其也满足 5 字段；key 仍是固定 `"block"`，落在隔离的 `logAt` 上 ⇒ **证据行永远打得出来**。

### ⭐ 限流（⛔ 不许每条都无脑打日志）

- 新增 `observe()` 复用节流机制：`emitThrottled(map, key, lazyMsg)`（`:404-411`）＝ 60s 按 key 一次 + `MAX_LOG_KEYS`(64) 封顶 + `LogX.i`；`key = "obs|reason|table"`，即**按「table+reason」组合节流**。
- 消息体用 `lazyMsg: () -> String`，**只有过节流才拼长字符串**；`LogX` 另有 1s dedup + 5000 行写满整文件清空重来（源码 `LogX.kt:79-88`，详见 T4 #9）。热路径不产生高频 `LogX.i`。
- 原 `throttledLog(key, msg: String)`（`:380-382`）签名/行为/4 个调用点（`:203,213,280,287`）**全部原样保留**，只把函数体委托给同一个 `emitThrottled(logAt, …)` ⇒ 真正「复用现有 throttledLog」而非另写一套。
- ⛔ **刻意用独立 `obsLogAt`（`:110-112`）而非共用 `logAt`**：`logAt` 只服务 `block/err/cb-*` 4 个固定 key。若共用，热路径 `non-target` 的高基数 `(table,reason)` key 会先占满 64 槽位，等真正命中 `blocked` 时 `MAX_LOG_KEYS` 封顶会把那行证据吞掉 —— 恰好毁掉本 lane 的目的。`obsLogAt` 已纳入 `resetLedger().clear()`（`:192`）。

### ⛔ 一行都不改拦截语义（逐条自查）

| 红线 | 状态 |
|---|---|
| `param.result` 赋值点 | **只有 2 个且原样**：`:274` `=null`(execSQL) / `:277` `=FAKE_ROWS`(delete)；**未新增任何赋值点**（见 T5 callback 顺序） |
| 判据顺序 | ①`!enabled` → ②`args` → ③解析 → ④`isTargetTable` → ⑤`tokens.isEmpty` → ⑥`shouldBlock` → ⑦命中，顺序未动；`observe()` 都插在「计数器自增之后 / `return` 之前」，不改任何 `if` 条件 |
| `seen/matched/skipped/blocked` 计数 | 自增位置与条件完全未变（`!enabled` 仍不计 `seen`） |
| `FAKE_ROWS` / `noteEvent` / `try/catch` 兜底 / 两个 callback 的 `catch` | 全部原样 |
| `MediaDeleteLogic` / `cachedTokens` 生成时机 / `invalidate()`·`refresh()` 调用点 | 全部未碰 |
| `stat()` 返回串 | 未改（仅注释更新），`op=mtg sub=stat` 打印原文 |
| 顶部「热路径纪律」注释（`:38-42`） | 更新为如实描述「`!enabled` 分支留一条 60s 节流观测日志」，避免留下「零日志」的过期文档 |

[DONE] T3

## T4 三档边界表（观测账本 / 日志视角）

> 语境：`antirevoke.media_delete=true` 已 `kvget` 回读、冷启后 `MTG installed enabled=true`；请 **verity** 在「**测试群聊**」重新发一张新图并撤回（⛔ 不拿 23:55 那张不可逆老图当样本）。
> ⛔ 本表是**观测账本/日志**视角（本 lane 补的探针看见什么），不是 `docs/_r52_revokeimg_rootcause.md:261-286` 那份「拦截功能」视角，二者不互相替代。
> ⛔ ② 档里 **TTL（时间维度）** 与 **冷启（进程维度）各自单列、不许合并**（`docs/_r51_media_delete_logic.md:360`）；③ 档**逐条分列、每条写清除条件与残留、不写「同返回」**。
> 所有「接线后能否真的读到 / 打开」全部 `[需真机]`；本表只描述**账本与日志证据的状态迁移**（静态可定部分不标）。

### 主表（① 进入 / ② 返回）

| 档 | 触发 | 账本 / 日志的可观测变化 | 清除条件 | 残留 |
|---|---|---|---|---|
| **① 进入 · 冷启 `install()`** | 微信进程重建 / 装包后 `force-stop`+`am start` | 整个 `MediaTableGuard` object **重建**：账本 4 计数=`0`、`events`/`logAt`/`obsLogAt` 空、`gen=0`、`lastIssuedMs=0`、`cachedTokens` 空、`mediaPoolRef` 重新反射；`refresh()` 从 KV 重读 `enabled`；重打 `MTG installed hooks=… enabled=… pool=…`（`:116-143`） | 仅进程死亡触发（旧进程态全丢） | 新进程态；持久化态（微信库行 / `image2` 文件 / `rescued` 台账）**不**随进程清 |
| **① 进入 · 首次撤回 `record()→invalidate()`** | verity 撤图：`protectMedia(`:122`) → `auditImage(`:126`) → `MediaTableGuard.invalidate()`(`:128`) | `gen++`、`lastIssuedMs` 置位、`cachedTokens` 建存活 token 快照、`refresh()` 重读开关；此后每条 `delete`/`execSQL` 开始 `seen++`，命中目标表 `matched++`，放行 `skipped++`，真拦 `blocked++`；`observe()` 按 `table+reason` 开始打放行原因行 | 无（这是开始，不是清除） | `gen`/`cachedTokens`/窗口起点；账本开始累积；`op=mtg sub=stat` 从此可读到非 0 |
| **① 进入 · `kvset` 改开关** | `op=kvset --es key antirevoke.media_delete --es val true|false` | ⛔ `kvset` 刷新清单**不含** `MediaTableGuard.refresh()`（`EntryReceiver.kt:176-200`、rootcause `:118/:146`）⇒ **内存 `enabled` 不即时变**；账本**不复位、不清零**；`op=mtg sub=stat` 此刻读到的 `enabled` 仍是旧值 `[推断]`（直到冷启或下一次 `invalidate()`）。本 lane **未改**这条接线 | 下一次撤回的 `invalidate()` 或冷启 `install()` 才把新值刷进 `enabled` | KV 值已新写；内存 `enabled` 滞后；账本、hook、池原样；残留「开关写库 ≠ 内存生效」这个已知缺口（不在本 lane 范围） |
| **① 进入 · `op=mtg sub=reset`** ⭐ 新增 | 一次装包内主动发广播 | `resetLedger()`：4 计数归 0、`events`/`logAt`/`obsLogAt` 清空；⛔ **不动** `enabled`/`cachedTokens`/`gen`/`lastIssuedMs`/`mediaPoolRef`；回打一行 `MTG stat … seen=0 matched=0 blocked=0 skipped=0 events=0 …`（`gen`/`enabled`/`tokens` 仍原值，可一眼核对「只动了账本」）。节流槽位清空 ⇒ 下一次同类放行会**重新打一行** observation | 主动发 `reset` 广播即清（**无需冷启、不卸 hook、不改开关**） | 运行时状态全保留；hook 常驻；`verdict` 回 `NO_EVENT`；便于「正向 / 反向」对照归零 |
| **② 返回 · 撤后几分钟点开大图 / 切页回来 / 下拉刷新** | 纯 UI 重建，**不产生新的撤回 UPDATE** | 账本**不清零、继续累积**；`observe()` 若此时有 DB 写/GC 触发 `delete`/`execSQL` 仍按 60s 节流打行；撤回瞬间打出的 `MTG … reason=…` / `blocked` 证据行**保留在 LogX 缓冲里**（除非被 #9 的整批清空吃掉） | 无（UI 动作不清除任何账本/集合） | 账本、池、`cachedTokens`、hook、节流槽位全在；`auditImage` 的 Cursor 每次 `close()` 不残留游标 |
| **②-a TTL · 保护池 10min（时间维度，单列）** | 撤回后满 `WINDOW_MS=600_000L`（`MediaTableGuard.kt:70`）/ `PROTECT_MEDIA_MS` | `shouldBlock` 恒 false、`isRevocationWindow=false`/`hasMedia=false` ⇒ 此后到达的媒体表 DELETE **放行**；⭐ 观测价值：此时会打出 `reason=shouldBlock-false` / `reason=tokens-empty` 行，`op=mtg sub=stat` 的 `skipped` 继续涨、`blocked` 不再涨 | 到期（时间维度）；进程死 | **账本仍在、hook 仍在、开关仍开**、`cachedTokens` 不自动清（靠窗口判定失效）、池从「保护态」变「裸奔态」；⛔ **不与「切页回来」合并**：切页回来池还在，TTL 到期池失效 |
| **②-a2 TTL · `imgAnchor` 30min（时间维度，单列）** | `AntiRevoke.IMG_ANCHOR_TTL_MS`（`AntiRevoke.kt:470`）+ 256 上限淘汰 | 与 MTG 账本**不同对象**，不直接改 `seen/…`；但锚点淘汰会让后续「按 md5 反查行」类取证拿不到锚点 | 30min TTL / 256 上限 / 进程死 | **不清空、冷启丢**（`docs/_r49_revokeimg_reference.md:292`）；⛔ **与保护池 10min 分列**：不同载体、不同时长、不同清除条件 |
| **②-b 冷启 · 进程维度（账本/集合整体清零，单列）** | 微信进程被重建 | `HookEntry` 重新 `install()`；**账本 4 计数 + `events` + `logAt` + `obsLogAt` + `cachedTokens` + `gen` + `lastIssuedMs` 全部进程内整体清零**，`verdict` 回 `NO_EVENT`，`MTG installed` 重打；`obsLogAt` 清空 ⇒ 新进程的放行日志重新从第一条开始打 | **仅进程死亡** | 持久化态保留（`rescued` 台账、`image2` 原文件、语音 `voice_bak/`、微信库行）；⛔ **与 TTL 分列**：TTL 只让池过期、账本与 hook 都还在；冷启是进程内集合**整体清零** + hook 重装 |

### ③ 复位档（逐条分列 · 每条写清除条件与残留 · ⛔ 不写「同返回」）

| # | 复位动作 | 发生什么（账本/日志视角） | 清除条件 | 残留 |
|---|---|---|---|---|
| 1 | **关 `antirevoke.media_delete`** | `refresh()` 只在 `install()`/`invalidate()` 调、`kvset` 不含本类 ⇒ 关后内存 `enabled` 仍是旧值；刷成 false 后 `onBefore:232` 第一行 return，但**现在会留 `reason=disabled` 观测行**，能区分「钩子没被调用」与「开关关」；`seen` 不再自增 | 下一次撤回的 `invalidate()` 或进程重启（**非即时**） | ≤10min 窗口内仍可能拦（rootcause `:278`）；hook **不卸**只空转；`protectedMedia` 不清、旧 token 靠 TTL；账本停止增长但**不清零**（读 stat 仍是旧数）、`obsLogAt` 节流槽位仍在；不写微信库 |
| 2 | **关 `antirevoke.msg`** | `AntiRevoke` 不拦 UPDATE ⇒ `RevokeRescue.record()` 不触发 ⇒ 图片分支 `protectMedia` 与 `MediaTableGuard.invalidate()` **都不跑**；`VoiceFileGuard.on()` 3s TTL 缓存关后最多 3s 内仍拦 `File.delete` | `kvset` 主动 `AntiRevoke.refresh()` + 3s TTL 到期 | 原文消失、`rescued` 台账不写、池不新增、`cachedTokens` 不重建、账本不增长、hook 常驻；`rescued` 台账**跨关开关保留**（模块自有库） |
| 3 | **保护池 10min TTL 到期** | `PROTECT_MEDIA_MS` 到期 ⇒ `MediaGuardLogic.anyAlive` 把 `hasMedia` 复位 false（`VoiceFileGuard.kt:301`）；`MediaTableGuard.WINDOW_MS` 同步到期 ⇒ `shouldBlock` 恒 false | 到期（`anyAlive`）、或进程死 | 旧 token 仍在 `protectedMedia` Map 但不再命中；`hasMedia=false`；`cachedTokens` 不自动清；账本/hook/开关都在；此后删除会打 `reason=shouldBlock-false` 行；⛔ **与 imgAnchor 30min 不同载体不同时长** |
| 4 | **`imgAnchor` 30min TTL** | `AntiRevoke.kt:470` `IMG_ANCHOR_TTL_MS` + `:468-470` 256 上限淘汰 | 30min TTL / 256 上限 / 进程死 | **不清空、冷启丢**（`docs/_r49_revokeimg_reference.md:292`）；与保护池 10min TTL **单独复位**；不直接动 MTG 账本 |
| 5 | **账本冷启清零 / `op=mtg sub=reset`** | 两条**不同语义**的路径：① 冷启＝进程死 ⇒ 整体清零（连 `enabled`/`cachedTokens`/`gen` 一起没）；② `reset`＝广播只归零账本（`seen/matched/blocked/skipped/events/logAt/obsLogAt`），**显式保留**运行时状态（T2 `resetLedger()`） | ① 仅进程死亡；② 主动发 `reset` 广播（不卸 hook、不改开关、不冷启） | 清零后 `verdict` 回 `NO_EVENT`；`reset` 后节流槽位清空 ⇒ 同类放行重新打一行（正反对照）；⛔ **与 TTL 分列**：TTL 到期账本仍在、hook 仍在 |
| 6 | **hook 随进程死才卸** | `HookEntry.install()` 无 `unhook`；关开关 / TTL 过期 / `logclear` / `reset` / 清缓存**都不卸** `delete`/`execSQL` 上的 callback | **仅进程死亡**（或删源码重装+重启，`docs/_r49_revokeimg_reference.md:289`） | 关开关后 callback 常驻但空转，`disabled` 观测行仍每 60s 一条；⛔ 与「运行时降级」「删源码彻底回滚」两种语义区分（`docs/_r51_impl_review.md:279`） |
| 7 | **微信重试删除落在窗口外 / 冷启池空** | 窗口内每次被拦、60s 节流只打 1 行；窗口外或冷启后 `cachedTokens` 空 ⇒ `isRevocationWindow=false` / `tokens.isEmpty()` ⇒ **无人拦、DB 行被删**；⭐ 观测价值：此刻会打出 `reason=tokens-empty` 或 `reason=shouldBlock-false` 行（若未被整批清空） | **无**（这是漏拦终态，无清除动作） | 图片侧**无 backup/restore**（`RevokeRescue.kt:121-129`）⇒ 行/文件一旦被删即**永久丢失**；语音侧还有 `scheduleRestore` 兜底，图片没有；账本里 `skipped` 会涨、`blocked` 不涨 |
| 8 | **清缓存** | `op=logclear` 只 `LogX.reset()` 清环形缓冲（`EntryReceiver.kt:165-168`），**不动池/台账/文件**，但会**连带清掉本 lane 刚打的观测日志行**；微信「存储空间→清缓存」删 `image2`：开关**开着** ⇒ `File.delete` 被拦 ⇒ 存储只增不减；开关**关着** ⇒ 真删 | `logclear` 只清日志；真删文件只在「关开关 + 微信清缓存」同时成立 | ② 返回档**不会删文件**；这是**唯一**能真正把已保护图片删掉的路径；`logclear` 后账本计数仍在（进程内存），可再 `op=mtg sub=stat` 读回，但**逐行明细已丢** |
| 9 | **日志 5000 行环形覆盖导致旧证据消失** ⭐ 观测专属（项目简称；真实行为＝满 5000 行 `writeText` 整文件清空重来 + 留 `log rotated` 标记 + `lines=0`，⛔ 非逐行 FIFO） | ⛔ 核源码 `LogX.kt:79-88`（lane B 协作提醒后复核）：满 `MAX_LINES=5000` **不是逐行环形挤掉**，而是 `writeText` **整文件清空从头写** + 留一行轮转标记 `---- log rotated: 已达 5000 行，清空重来 ----`（源码注释 `:81` 把「环形覆盖」与「清空从头写」并称，`开发回路速查.md:111` 沿用了简称）⇒ 撤回瞬间打出的 `MTG … reason=…` / `blocked` 行会**整批**消失，复现本轮真机「`dev.py q 'MTG'` 只有两条 installed、零业务日志」的**假象**。⭐ **额外判据**：`dev.py q` 拉不到 `MTG` 业务行时，查有没有那行 `log rotated` 标记 —— **有标记＝业务日志确实打过但被整批清空**，可直接排除「钩子没被调用」，正是本 lane 要解决的分不清问题。`K7 perf` 每秒刷屏 + **lane A/B 共用同一 `lines` 计数与同一文件**（lane B 简报确认 `HDIMG trace` 也写 LogX）会加速触顶 | **无法恢复**（整批清空即不可逆）；只能预防：测前 `op=logclear`（`LogX.reset()`，`LogX.kt:99-100`）、缩短取证窗口、用 `--grep 'MTG stat\|MTG '` 及时拉、必要时先 `op=mtg sub=stat` 读账本计数兜底 | 账本计数仍在进程内存（`stat()` 可读，含 `verdict`/4 计数），**不受日志清空影响**；但逐条 `table/where/reason` 明细日志行不可逆消失；⛔ 与 #8 `logclear` **主动清空**区分：本条是**被动整批清空**；⛔ 与 lane B 的 `HDIMG trace`（按 msgId 去重、上限 64×2 行）叠加会加快 5000 行触顶 |
| 10 | **已被删的 DB 行 / 文件不可回滚** | R51 只能在 `beforeHookedMethod` **阻止删除发生**，无任何 restore：`ImgInfo2` 行一旦被删、`image2` 文件一旦被 native `unlink`（`VoiceFileGuard.kt:35-38`）删除，均无副本可还原（`docs/_r51_media_table_guard_impl.md:245`） | **无**（**不可逆终态**） | 不属于任何「② 返回」路径；⛔ 返回档前提是「行与文件都还在」；23:55 那张 verity 老图即落此态，别拿它当验收样本；本 lane 的探针**只能记录「为什么没拦住」，不能把已删的行变回来** |

[DONE] T4

## T5 回滚路径 + 装包前复核要点

### 回滚路径：只 revert 这两个文件即可回到「现网零观测状态」

| 文件 | 本 lane 新增（要移除的） | 现网基线（要回到） |
|---|---|---|
| `feature/MediaTableGuard.kt` | `obsLogAt` 字段、`resetLedger()`、`stat()` 注释更新、顶部热路径纪律注释更新、`onBefore` 6 处 `observe()` 调用 + `!enabled`/`args`/`p==null` 分支改写、`blocked` 日志 `where80/reason=blocked` 增强、`throttledLog` 委托 `emitThrottled` 重构、`observe()`/`emitThrottled()` 两个新函数、`resetLedger` 里的 `obsLogAt.clear()` | `install()` 的 `installed` 日志、`stat()`（未接广播）、`blocked` 唯一业务日志、`throttledLog` 原内联实现、`onBefore` 4+2 条静默 return |
| `entry/EntryReceiver.kt` | `"mtg" -> {…}` 这一整个分支（含注释） | `op=hdimg` 之后直接是 `else -> LogX.w("ENTRY PROBE unknown op=$op")` |

- revert 后：`dev.py q 'MTG'` 只剩两条 `installed` + 命中 `blocked` 时那一行，账本重新不可读、放行原因重新不可见 ⇒ **现网零观测状态**。拦截语义（`param.result`/判据/`FAKE_ROWS`/try-catch）**始终没变过**，回滚也只是去掉观测，不触碰 R51 钩子本身。
- ⛔ **两个坑，回滚时别踩**：
  1. `MediaTableGuard.kt` 当前是 **untracked（`??`，R51 新增、从未 commit）**，没有 HEAD 版本可 `git checkout --` ⇒ 只能按上表**逐段移除本 lane 新增内容**（或用 R51 基线内容覆盖）；⛔ **别 `rm` 整个文件** —— R51 的两个 hook 要保留。
  2. `EntryReceiver.kt` 工作区里**本就有前几轮未提交的改动**（如 R49 `op=writeprobe` 分支，见 `git diff`）、且 lane B 正并发改 `AutoHdImg.kt`。⛔ **不要用 `git checkout -- EntryReceiver.kt` 整文件还原**，会连带抹掉这些既有/他人改动；只精确删掉本 lane 的 `"mtg"` hunk。
- ⛔ 不需要、也不应该动：`feature/AutoHdImg.kt`（lane B 独占）、`MediaDeleteLogic.kt`、`RevokeRescue.kt`、`VoiceGuard.kt`、`SnsGuard.kt`、`Keys.kt`、任何开关默认值、编译产物、微信库。
- `docs/_r52_probe_a_impl.md`（本报告）是本 lane 唯一允许写的文档，**不参与编译**，保留不影响模块行为；若要「代码树绝对干净」可一并删除。两 lane 的回滚互不影响。

### 探针是否可能拖慢热路径

- **`enabled=false`（开关关）**：原注释的「零对象分配 / 零日志」变成 → `observe(execSql, null, "disabled")` 多做 **1 个短 key 字符串 `"obs|disabled|-"` + 1 次 `ConcurrentHashMap.get` + 1 次 `SystemClock.elapsedRealtime()`**；消息体懒拼、**只有过 60s 节流才 `LogX.i`**。这是为「区分钩子没被调用 vs 开关关」付的**有界极小代价**，不做 SQL 解析、不改 `param`、不查库、不反射、不文件 IO。
- **`enabled=true`**：每个 return 分支多 1 次 `observe()`（同样 1 短串 + 1 次 map 查询）；开关开时本来就有 `Parsed`/`substring` 分配，新增的只是同量级的节流查询；`delete`/`execSQL` 仍不查库 / 不反射 / 不文件 IO / 不新增 `Kv.getBool`。
- **硬上限**：`obsLogAt` 最多 `MAX_LOG_KEYS=64` 个 key、每 key `LOG_TTL_MS=60s` 一条 ⇒ 观测日志最坏 ≈ **每 60s 64 行（约 1 行/秒）**；`logAt` 另有 `block/err/cb-*` 4 个固定 key 最多 4 行/60s；`LogX` 再叠 1s dedup + 5000 行写满整文件清空重来（源码 `LogX.kt:79-88`，详见 T4 #9）。⇒ **不会像 `K7 perf` 那样每秒刷屏**，也不存在「每条无脑打日志」。
- 结论 `[推断]`：单条 DB 操作多 1 次短字符串 + 1 次并发 map 查询 + 1 次时钟读取，量级远小于 DB 操作本身，**不会拖慢热路径**。真机若发现 `MTG` 行异常增多或干脆拉不到，先查 `LOG_TTL_MS`/`MAX_LOG_KEYS` 是否被改小、`dev.py q` 是否拉得太晚、日志里有没有 `log rotated` 轮转标记（＝满 5000 行被整批清空），而非探针逻辑。

### ⛔ 会不会影响 `VoiceGuard` / `SnsGuard` 同一方法上的 callback 顺序

- 事实：wcdb `SQLiteDatabase.delete` / `execSQL` 上同时挂了多个 `beforeHookedMethod` callback —— `VoiceGuard.deleteGuard`（只认 `voiceinfo`）、`SnsGuard.deleteGuard/sqlGuard`（朋友圈独立库 `SnsInfo`/`SnsComment`）、`MediaTableGuard.deleteGuard/execSqlGuard`（白名单 `{imginfo2,videoinfo2,wxfileindex2}`，不含 `voiceinfo`，`MediaDeleteLogic.kt:64`）。Xposed 按注册顺序调 before callback；⛔ **任一 callback 设置 `param.result` ⇒ `returnEarly=true` ⇒ 跳过原始方法体且不再调用后续 before callback**。
- ⛔ **本 lane 未新增任何 `param.result` 赋值点**：全文件 Grep `param.result` 仍只有 **`:274` `=null`(execSQL)** 与 **`:277` `=FAKE_ROWS`(delete)** 两处，赋值内容 / 位置 / 条件 / 判据顺序全部原样保留。
- 新增的 `observe()` 全是节流后的 `LogX.i`，**完全不碰 `param`**：不改 `param.result`、不改 `param.args`、不抛异常（两个 callback 的 `try/catch` 与 `onBefore` 的 `try/catch` 兜底原样）。放行分支仍是**不置 result 的裸 `return`** ⇒ 后续 before callback 照常执行，与现网一致。
- 命中 `blocked` 分支仍置 result ⇒ 跳过后续 callback —— 这是**现网既有行为**，本 lane 没把任何原本放行的分支改成置 result，也没改 `install()` 里 `hookAllMethods` 的注册顺序。
- 表名白名单天然互不重叠 ⇒ 实际上三方不会争同一条 delete（既有事实，本 lane 未改）。
- ⇒ **结论：对 `VoiceGuard`/`SnsGuard` 同方法 callback 顺序零影响**；「设置 `param.result` 会跳过后续 before callback」的风险面与现网**完全相同**，没有被本 lane 扩大或缩小。

### 装包前复核要点（静态自查，⛔ 本 lane 不跑 gradle / 装包 / adb / kvset）

1. 改动面：只有 2 个独占源码文件 + 本报告。`feature/AutoHdImg.kt`（lane B）零改动，`EntryReceiver.kt` 的 `op=hdimg` 分支原样（已用 `git diff` 复核，无并发写破坏）。
2. `Grep param.result` 应只有 2 处、均在 `onBefore` 命中分支；⛔ 无第三处。
3. ⛔ 未改 `MediaDeleteLogic` / `cachedTokens` 生成时机 / `invalidate()`·`refresh()` 调用点 / `install()` 的 hook 注册顺序 / 任何开关默认值 / `FAKE_ROWS`。
4. `resetLedger()` 只清 4 计数 + `events` + `logAt` + `obsLogAt`；⛔ 未碰 `enabled`/`cachedTokens`/`gen`/`lastIssuedMs`/`mediaPoolRef`。
5. 编译期：`EntryReceiver` 用全限定名 `com.lx.wxgroup.feature.MediaTableGuard.stat()/resetLedger()`，**无需新增 import**；`MediaTableGuard` 用到的 `ConcurrentHashMap`/`AtomicLong`/`SystemClock`/`LogX` 均已在 `:3-13` import。
6. 无重载歧义：`emitThrottled(ConcurrentHashMap<String,Long>, String, () -> String)` 单一签名；原 `throttledLog(key:String, msg:String)` 4 个调用点（`:203,213,280,287`）仍是 `(String,String)`；`observe` 用 `emitThrottled(obsLogAt, …) {…}` 尾随 lambda。
7. `op=mtg` 是 `when(op)` 新分支、`when(sub)` 有显式只读 `else`，不与 `kvget/kvset/writeprobe/label/logclear/hdimg` 冲突，拼错 sub 不写库。
8. 装包后判据 `[需真机]`：冷启看 `MTG installed … enabled=…`；`dev.py probe mtg stat --grep 'MTG stat'` 应出 ≥1 行（过 `开发回路速查.md:46-56` 闸门③）；`dev.py q 'MTG'` 应同时看到 `installed` / `MTG … reason=…` 放行行 / `MTG blocked … reason=blocked`；微信须前台（后台不投递、静默）。⭐ 若 `dev.py q 'MTG'` **只有 installed、无业务行**，查日志里有无 `---- log rotated: 已达 5000 行 ----` 标记：**有＝业务日志打过但被整批清空**（可直接排除「钩子没被调用」，见 T4 #9），无＝再回头查开关 / 广播投递。
9. 正反对照：`op=mtg sub=reset` 归零 → 请 **verity** 在「**测试群聊**」撤一张新图 → `op=mtg sub=stat` 读 4 计数 → 对照 `dev.py q 'MTG'` 的 `reason=` 行看每条为什么被放行。⛔ 别拿 23:55 那张不可逆老图当样本。⛔ **判据纪律**（与 lane B 报告口径一致）：`op=mtg sub=stat` 的 4 计数与 `dev.py q 'MTG'` 的 `reason=` 行**只作定位 / 归因**，⛔ **不作功能判据** —— 判据一律对着用户能看见的事实写（界面大图 / 文件系统里的文件 / 库里的行值 / 日志行，`docs/项目化配置.md:38-41`，转引 rootcause `:7`）；「有日志」≠「功能已生效」，最终「图片能不能打开」必须看界面。
10. 协作核对（lane B 简报）：两条 lane **共用同一个 `LogX` 落盘文件与 `lines` 计数**（`LogX.kt:30-34`）。lane B 的 `HDIMG trace` 已按 msgId 去重、上限 64×2 行；本 lane 观测最坏 ≈ 64 行/60s + 4 固定 key。叠加会**加快 5000 行触顶**，但都不无节制刷屏 ⇒ 预防仍是测前 `op=logclear` + 缩短窗口 + 必要时 `op=mtg sub=stat` 读账本兜底（账本在进程内存，**不受日志清空影响**）。lane B 未碰本 lane 两个独占文件，其 `HDIMG trace db` 复用既有主库句柄做只读 select、不写库，不影响 `op=mtg sub=stat/reset` 账本语义；两 lane **无源码冲突**。

[DONE] T5

---

## 落盘日志（分步）

[DONE] T1（现状锚点：MediaTableGuard/EntryReceiver/开发回路速查§四/rootcause 全量 file:line）
[DONE] T2（op=mtg sub=stat|reset 广播 + resetLedger()，只清账本不动运行时状态、防 srcfix 坑）
[DONE] T3（onBefore 6 条静默 return 全量记账 + table+reason 限流 + blocked 行 5 字段，零拦截语义改动）
[DONE] T4（三档边界表：①4 类进入 / ②返回 + TTL 时间维度与冷启进程维度单列 / ③ 10 条复位逐条分列）
[DONE] T5（回滚路径 + 热路径开销 + callback 顺序零影响 + 装包前复核 10 条）
[DONE] 协作补记（收到 lane B 共享 LogX 缓冲简报后核 `LogX.kt:22,79-88,99-100`：确认「满 5000 行＝整文件 `writeText` 清空重来 + 轮转标记」，非逐行挤掉；已据此精确化 T1 锚点 / T4 #9 / T5 措辞，并把 `log rotated` 标记补成一条新判据；两 lane 无源码冲突）

> 红线自检：只改 `MediaTableGuard.kt` + `EntryReceiver.kt` 两个独占源码文件，未碰 `AutoHdImg.kt`（lane B）、未碰 adb / 装包 / gradle / 微信库 / 开关默认值 / `param.result` / 判据顺序 / `FAKE_ROWS` / try-catch / `cachedTokens` 生成时机 / `invalidate()`·`refresh()` 调用点；唯一新建文档＝本文件。好友一律昵称 **verity** / **测试群聊** / **文件传输助手**。不确定标 `[推断]`/`[需真机]`。

[DONE] ALL
