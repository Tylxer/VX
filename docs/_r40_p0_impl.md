# R40 P0 纯取证探针实现日志

## 待办清单
- [ ] 子步 1：新建 `feature/SnsRoundLedger.kt`（纯内存单例，零 DB 写入）
- [ ] 子步 2：`SnsTimelineMerge.kt` 接线 `onResponse`
- [ ] 子步 3：`SnsGuard.kt` 接线 `onUpsert`
- [ ] 子步 4：`EntryReceiver.kt` 的 `snsg` 分支新增 `sub=win` / `sub=winreset`
- [ ] 构建 `assembleDebug --rerun-tasks` 成功


## 子步 1：新建 `feature/SnsRoundLedger.kt`
新建文件 `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsRoundLedger.kt`。
- `object SnsRoundLedger`，纯内存单例，零 DB 写入。
- `data class Round(no, atMs, n, ctTop, ctBot, ids, upsertIds, upsertClosed, subset)`。
- `onResponse(ids, times)`：空/重复响应只累加 `dupResp`；独立轮次自增 `roundNo`、环形保留 12 轮、打 `WIN new round=...`；同时开 3000ms upsert 窗口（Handler 到期回调）。
- `onUpsert(snsId)`：窗口开时才收集，写入 `windowUpsertIds` + 所属轮次 `upsertIds`。
- `closeWindowLocked()`：算 `subset = respIds ⊆ upsertIds`（全量 set 包含，非数量比较），打 `WIN gate round=... subset=... miss=前5个`；轮次被挤出时打 WARN 不静默。
- `dump(n=12)`：12 轮 + 2 行汇总（`summary` + `c1`），限行 14。
- `reset()`：清空账本。
- 全方法 `synchronized(lock)`，onResponse/onUpsert 来自不同线程（网络线程 vs DB 写线程）。

[DONE] 子步1

## 子步 2：`SnsTimelineMerge.kt` 接线（`:149-156`）
在 `if (list.isEmpty()) { ... return }` 之后、`firstHit` 日志之前加 4 行：
`runCatching { SnsRoundLedger.onResponse(respIds, times.map { it.toLong() }) }.onFailure { LogX.w(...) }`。
- 位于空响应闸之后（空响应已 return，不会触发账本）。
- 不动 respIds 收集逻辑、不动注入顺序（respIds 收集仍在注入之前）。
- runCatching 包裹，异常只 LogX.w，不影响主链路。

[DONE] 子步2

## 子步 3：`SnsGuard.kt` 接线（`onSnsUpdateAfter` 内）
在 `onSnsUpdateAfter` 中，`if (st == null) {...return}` 之后、`if (st != 0)` 之前加 3 行：
`runCatching { snsIdOf(a).takeIf { it != 0L }?.let { SnsRoundLedger.onUpsert(it) } }`。
- 位置在所有零反射短路（table/values/sourceType 判空）之后 ⇒ 成功读到 snsId 才记。
- 覆盖 st=0（清位）与 st!=0（正常写 2/10）两类写库，符合「窗口内微信写库的 snsId」语义。
- 不改 reFixAfterClear 调用时机、不改 lastUpdateZeroAt 节流、不改任何补位行为。
- runCatching 包裹，探针异常不影响补位主链路。

[DONE] 子步3

## 子步 4：`EntryReceiver.kt` 的 `snsg` 分支新增 `sub=win` / `sub=winreset`
在 `when (sub)` 里 `"stat"` 之后加两个分支：
- `"win"` → `for (l in SnsRoundLedger.dump(n)) LogX.i(l)`（n 沿用 `intent.getIntExtra("n", 20)`）。
- `"winreset"` → `SnsRoundLedger.reset(); LogX.i("WIN reset ok")`。
- 位于 `io { }` 内，与同分支其它 case 一致。

[DONE] 子步4

## 构建
`cd wxgroup && ./gradlew assembleDebug --rerun-tasks`（JAVA_HOME=G:/Program Files/Microsoft/jdk-21.0.7.6-hotspot，ANDROID_HOME=C:/Users/lx/AppData/Local/Android/Sdk）：
```
BUILD SUCCESSFUL in 1m 32s
35 actionable tasks: 35 executed
```
编译仅有的 warning 均为存量（AvatarLoader/VersionGate/EntryReceiver 旧代码等），SnsRoundLedger.kt 无新 warning。

[ALL-DONE]

## 子步 3 修正：采样点必须早于 sourceType 判定（team-lead 纠正）
陷阱：`onSnsUpdateAfter` 有三处提前 return —— `st == null`（:321）、`st != 0` 分支（:351）、`bypass`（:353）。
正常动态的 upsert（写 2 或 10）在 `st != 0` 分支就提前 return，根本走不到 `:352` 之后的补位逻辑。
若把 `onUpsert` 采样点放在 sourceType 判定之后，`upsertIds` 永远只收「写 0」的行 ⇒ subset 恒 false ⇒ 假设 A 被误判证伪。

✅ 已改为：在 `val values = a[1] ?: return` 之后、`val st = sourceTypeOf(values)` 之前插入：
```kotlin
runCatching { snsIdOf(a) }.getOrNull()?.let { if (it != 0L) SnsRoundLedger.onUpsert(it) }
```
- `:313` 的 `a[0] != "SnsInfo"` 已挡掉所有非 SnsInfo 的 update，到采样点只处理 SnsInfo。
- `onUpsert` 内部零 IO、不碰 LogX（只做 synchronized 内存 HashSet.add）。
- 覆盖 st=0/2/10 全部写库，符合「窗口内微信写库的 snsId」语义。

[DONE] 子步3修正

[ALL-DONE]

## 复修（team-lead 第二轮纠正，3 处）

### 问题 1（严重）：`ids == lastRespIds` 会堵死「双轮缺席」
原 `onResponse` 用 `ids.isEmpty() || ids == lastRespIds` 判重复。推演删除流程：

| 轮次 | 服务端响应 | 旧判定 |
|---|---|---|
| 1 | `{A,B,C,X}` | ✅ 新轮，X 在 |
| 2（刷新，X 还没清） | `{A,B,C,X}` | ❌ 判 dup 跳过 |
| 3 | `{A,B,C,X}` | ❌ 跳过 |
| n（X 消失） | `{A,B,C}` | ✅ 新轮，X 缺席 1 轮 |
| n+1（再刷） | `{A,B,C}` | ❌ 又 dup 跳过 |

⇒ X 永远只有 1 轮缺席记录，第 2 期「连续 ≥2 轮」凑不齐。
✅ 改为「内容相同 + 时间间隔」：`DUP_GAP_MS=3000L`，`ids.isEmpty() || (same && gap < DUP_GAP_MS)` 才判 dup；
同内容但间隔 ≥3s 视为用户真的又刷一次（新轮）。新增 `lastRespAt` 字段（`reset()` 清零），
日志加 `gap`/`same`，`Round` 加 `same` 字段，`dump` 每行加 `same=`。

### 问题 2：`MAX_IDS=120` 对 HashSet 截断随机丢 id
`ids.take(120)` 的截断顺序不确定，会随机丢目标 id。✅ 改 `MAX_IDS=200`（首屏 10~30 条，正常不触发），
`dump` 行尾加 `trunc=${r.n > r.ids.size}` 便于发现截断。

### P0-2 补探针（上次漏的）
- `SnsRoundLedger` 加 `fun seenAny(id: Long): Boolean`（该 id 是否进过任一保留轮次 ids）。
- `SnsGuard` 加 `fun winCoverage(limit=60): List<String>`（只读反射 rawQuery，`localFlag=2` 最近 60 条，
  统计 `inLedger`/`pct`，输出 `WIN cover recent60=... inLedger=... pct=...%`；不是 gate，只记录）。
- `EntryReceiver` 的 `"win"` 分支在 dump 之后追加 `winCoverage()` 输出（仍在 `io {}` 内）。

[DONE] 复修

[ALL-DONE]

---

## [待修] 探针被功能开关挡住（2026-11-03 22:25 首次真机暴露）

**现象**：装包冷启后，请用户「进朋友圈 → 刷新两次 → 退出 → 再进」，
`snsg sub=win` 输出 `rounds=0 dup=0 nonEmpty=0` —— **一轮都没记上**。

**根因（两层）**：
1. **直接原因**：R39 做「关开关对照」时执行了 `kvset antirevoke.sns false`，**实验后未恢复**。
   `SnsTimelineMerge.onBeforeResp` 第一行是 `if (!enabled()) return`（`SnsTimelineMerge.kt:123`），
   `enabled()` 读的正是 `Keys.ANTIREVOKE_SNS`（`:286-287`）⇒ 整条链路（含探针采样）被短路。
2. **设计缺陷**：`SnsRoundLedger.onResponse` 被放在 `enabled()` 判断**之后**（`:155` 附近），
   而第 0 期恰恰要求「开/关 `antirevoke.sns` 各做一遍，结论应一致」（`_r38_plan.md` 第 0 期验收判据 ④）。
   探针依赖功能开关 ⇒ **正反对照在结构上做不了**。

**修法（下次装包必改）**：把账本采样移到 `enabled()` 判断**之前**：
```kotlin
private fun onBeforeResp(param: MethodHookParam) {
    // P0 取证：账本必须在开关判断之前采样 —— 否则「关开关对照」永远拿不到数据。
    // 代价：开关关闭时也要解析 respIds（一次 list 遍历 + 反射读 Id/CreateTime）。
    runCatching { sampleRound(param) }
    if (!enabled()) return
    ...
}
```
即把「取 `a[3]` → 取 `f` → 遍历 `Id`/`CreateTime`」这段抽成 `sampleRound()`，
在 `enabled()` 之前调用一次；此后主链路复用同一份 `respIds`，**不得重复遍历**。

**同时治本**：`kvset antirevoke.sns false` 这类对照实验，必须在验证手册里写成
「**成对命令块**」（`... false` 与 `... true` 一起给出），并在收尾 checklist 里回读一次。
