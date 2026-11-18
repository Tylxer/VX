# R69 独立对抗复核报告

- 复核视角：真机装机前最后一道代码关（只读）
- 项目：`wxgroup`（Gradle / Kotlin，LSPosed 模块，宿主微信 8.0.48）
- 本轮改动：`GroupBar.kt:1529` 与 `GroupManagerPage.kt:90` 各新增一行 `GroupConvStore.postSync(force = true)`
- 复核方式：逐条对抗验证，每条给 PASS/FAIL + 文件:行号证据

---

## 1. 节流绕过真伪 — PASS

- `GroupConvStore.postSync`（`GroupConvStore.kt:89-108`）：
  - `:95` `val due = force || lastSyncMs == Long.MIN_VALUE || (now - lastSyncMs >= SYNC_THROTTLE_MS)` —— `force` 为第一个短路项，`force=true` 时 `due` **恒为 true**，无任何条件可阻止进入写库分支。
  - `:98` `lastSyncMs = now` 在 `if (due)` 内被推进，`force=true` 同样推进。
  - `:90` `worker.execute { ... }` 整段（含 `:99-102` 的 `GroupDb.current()` / `listGroups()` / `memberWxids()` / `sync()`）在 `gconv-store` 单线程 worker（`:59-61`）内执行；主线程只投递。
- 是否存在 force 仍被吞的路径：**无**。`:91` 的 `runCatching` 只捕获异常，不改变 `due` 判定；`sync()` 自身（`:153-233`）用 `runCatching/getOrElse` 兜底，wxDb 为 null 时 `:155` 仅返回 `"no db"` 日志，不属于节流吞。force 任务必然进入写库分支。
- 判定：PASS。

## 2. 幂等 / 不重复插行 — PASS

- 串行前提：`worker` 是 `newSingleThreadExecutor`（`GroupConvStore.kt:59-61`），force sync、250ms 去抖 sync、60s 周期 sync 在同一线程 FIFO 执行；后一个任务读 `existing` 时前一个已提交。
- `GroupConvPlan.plan`（`GroupConvPlan.kt:81-97`）关键分支：
  - `:82-83` `want`/`have` 均 `associateBy { username }`，同一 username 在单侧被去重为唯一项。
  - `:85` `deletes = have - want`；`:86-94` `updates = want ∩ have`，其中 `:90` flag/time 漂移产 Update、`:91` 昵称变化产 Update、`:92` 否则 `null`（no-op）——已存在 username 只落 Update/no-op，**不会进 inserts**。
  - `:95` `inserts = want - have` —— 只插入 `existing` 中不存在的 username。
- 双保险：`sync()` 的 Insert 分支（`GroupConvStore.kt:186-201`）在 `:190-191` 先 `insertPreDelConv` / `insertPreDelContact` 再插，即使 plan 误判也不会产生唯一键冲突或重复行。
- 判定：PASS（force 先插、后续去抖/周期 sync 对同一 username 只产 no-op/Update）。

## 3. 主线程不阻塞 — PASS

- `postSync`（`GroupConvStore.kt:89-108`）仅 `worker.execute { }` 后立即返回，无 `join/get/await`。
- 全表读 `readConvTimes`（`:313-326`）/ `readExisting`（`:329-357`）与全部写库（`:173-220`）均在 worker 线程内，调用方主线程零全表读/写。
- 两个调用点：`GroupBar.kt:1529`、`GroupManagerPage.kt:90` 均只在点击事件末尾 post 一行；此前主线程的 `db.createGroup`（`GroupBar.kt:1508`/`GroupManagerPage.kt:76`）是单行 insert，`ConvFilter.refreshMembers()`（`:1515`/`:80`）是内存刷新，均非全表读/写库。
- 判定：PASS。

## 4. 作用域精确 — PASS

- diff 仅涉及两个文件各新增一行 `postSync(force = true)`（`GroupBar.kt:1529`、`GroupManagerPage.kt:90`），全仓 `postSync(force = true)` 调用点仅此两处 + 既有手动入口 `EntryReceiver.kt:212`（`op=gcs sub=sync` 成熟路径，本次未改）。
- `SYNC_THROTTLE_MS = 60_000L`（`GroupConvStore.kt:72`）本身未改，GroupConvStore.kt 完全不在本轮 diff 内。
- 成员变更 / 加删成员路径逐字未变：`ConvFilter.kickGconvSync`（`:651-654`）内部 `postSync()` 仍无 force；两个兜底调用 `:639`、`:926` 未改；`GroupManagerPage` onChanged 收口 `:68` 仍仅 `refreshMembers()`。
- 判定：PASS。

## 5. lastSyncMs 推进副作用（重点对抗）— PASS

- 机制：`postSync` 接受任务即推进 `lastSyncMs`（`GroupConvStore.kt:98`），force 也推进。建组 force 后立即推进时间戳，会把**紧随其后的非 force sync**（250ms 去抖 sync / 60s 周期 sync）节流 60s。
- 判定为**期望行为、非回归**，理由：
  1. force sync 是全量 `sync()`（`:153-233`），已把新组行一次性写库；紧随的去抖/周期 sync 无新信息，节流掉恰好避免 60s 内重复全表读（`:313` 读 3120 行）。
  2. 「建组推进 `lastSyncMs`」本就是既有语义：原建组链 `refreshMembers()→refresh()→kickGconvSync()→postSync()` 在 due 时同样执行 `:98` 推进。force 只是把这一步**提前并强制**，没有引入新类型的副作用。
  3. 若建组后立刻再建一个组，第二个 `force=true` 仍由 `:95` 第一项短路 due，不会被吞；被节流 60s 的只有**非 force** 路径。
  4. 需留意的边界：force 是异步任务，若某非 force sync 依赖「force 之后的新变更」立即落库，会被节流 60s——但本次两处已修入口（GroupBar/GroupManagerPage）建组后无后续 addMember，不受影响（GroupPicker 场景见第 6 条，其本身未加 force）。
- 判定：PASS。

## 6. 第三个建组入口遗漏（重点对抗）— **FAIL**

全仓 Grep `createGroup(` / `新建分组` 共发现 **4 处** `db.createGroup(...)` 调用，team-lead 所述「建组入口只有两处」**不成立**：

| # | 文件:行号 | 场景 | force sync | 判定 |
|---|-----------|------|-----------|------|
| 1 | `GroupBar.kt:1508` | 横条/菜单「新建分组」 | 已加 `:1529` | PASS |
| 2 | `GroupManagerPage.kt:76` | 管理页「新建分组」按钮 | 已加 `:90` | PASS |
| 3 | **`GroupPicker.kt:143`** | **给好友设分组时输入新名建组（真实用户建组路径）** | **无** | **FAIL** |
| 4 | `BackupFlow.kt:163` | 备份恢复 createGroup fallback（`:161-162` 注释「正常走不到」） | 无 | 观察项（非阻断） |

- **FAIL 主项 `GroupPicker.kt:143`**：`apply()` 中 `db.createGroup(newGroupName)` 建组后，`:155` 立即 `db.addMember(target, ...)` 把好友加进新组，`:161` 仅调 `ConvFilter.refreshMembers()` → `refresh()` 去抖 → `kickGconvSync()` → `postSync()`（非 force，`ConvFilter.kt:651-654`）。距上次 sync <60s 时同样被 `SYNC_THROTTLE_MS` 吞，新组要等下一次宿主 `onDataChanged(~60s)` 才出现在会话列表——**与本轮 R69 修的 bug 完全同源**。该路径是活的用户入口（`:131` 日志 `PICKER shown for friend`；dialog 由好友分组操作触发），应同样补 `GroupConvStore.postSync(force = true)`（在所有 addMember 之后、`:161` 附近），否则 R69 只修了 2/3 的用户建组入口。
- 观察项 `BackupFlow.kt:163`：data 层批量恢复的兜底分支，非「手动新建即时可见」场景，有自己的 `BackupMerge.reconcile`（`:183`）与刷新流程，不强制要求 force；不列为阻断，但建议 team-lead 知悉其存在。
- 判定：**FAIL**（遗漏 `GroupPicker.kt:143`）。

## 7. 时序竞态 — PASS

- 两处调用点均先同步提交建组，再 post force sync：
  - `GroupBar.kt:1508` `db.createGroup(name)` → `:1529` `postSync(force=true)`。
  - `GroupManagerPage.kt:76` `db.createGroup(name)` → `:90` `postSync(force=true)`。
- `GroupDb.createGroup`（`GroupDb.kt:387-399`）用 `writableDatabase.insertWithOnConflict(...)`（`:396-398`）同步写库并返回 rowid，方法返回时新组已落盘。
- worker 异步投递（`GroupConvStore.kt:90`），其读 `GroupDb.current()` / `listGroups()` / `memberWxids()` 发生在 `:99-101`，必然在建组提交**之后**执行；不存在「sync 先于提交」的窗口。
- 判定：PASS。

## 8. 编译 / 可见性 — PASS

- 两文件均为 `package com.lx.wxgroup.feature`，与 `GroupConvStore` 同包；`GroupConvStore` 是 `object`，`postSync(force: Boolean = false)`（`GroupConvStore.kt:89`）为 public，简写调用 `GroupConvStore.postSync(force = true)` 可见，无需新增 import。
- 调用语法与既有 force 调用点 `EntryReceiver.kt:212` 完全一致，无编译隐患。
- 主会话已 `assembleDebug` exit 0（采信），本次复核仅静态扫视，未见新增 import 需求或符号冲突。
- 判定：PASS。

---

## 总判定：需修后装机 FAIL 1

逐项汇总：
1. 节流绕过真伪 — PASS
2. 幂等 / 不重复插行 — PASS
3. 主线程不阻塞 — PASS
4. 作用域精确 — PASS
5. lastSyncMs 推进副作用 — PASS
6. 第三个建组入口遗漏 — **FAIL**
7. 时序竞态 — PASS
8. 编译 / 可见性 — PASS

**FAIL 项（1 条，装机前必须修）：**

- **FAIL-1（对应第 6 条）**：建组入口不止两处，遗漏第三处 `GroupPicker.kt:143`（给好友设分组时输入新名建组）。该路径 `:161` 仅走非 force 的 `refreshMembers()` 链，仍会被 `SYNC_THROTTLE_MS(60s)` 节流吞，新组延迟 ~60s 才出现在会话列表，与本轮 R69 修复的 bug 同源。需在 `GroupPicker.apply()` 所有 addMember 之后（`:161` 附近）补一行 `GroupConvStore.postSync(force = true)`，再重新复核。
  - 附带观察（非阻断）：备份恢复 `BackupFlow.kt:163` 也存在 `db.createGroup`，属批量恢复 fallback，不强制要求 force，但建议 team-lead 知悉。

[DONE]
