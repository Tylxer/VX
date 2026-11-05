# R41 探针修正（r41-probe-lane）

三个子步：补 INSERT 采样 + 判据改并集；采样移到开关判断前；每轮记录可见位。

## 待办清单
- [ ] 子步 1：SnsGuard 补 INSERT 采样 + SnsRoundLedger 增 insertIds / 判据改并集 / 日志改格式
- [ ] 子步 2：SnsTimelineMerge.onBeforeResp 采样移到 enabled() 之前，复用同一份结果
- [ ] 子步 3：WIN gate 后追加 `WIN src ...` 可见位统计行
- [ ] 构建：assembleDebug --rerun-tasks
- [ ] 交回 team-lead

## 落盘记录

### [DONE] 子步 1：补 INSERT 采样 + 判据改并集
- `SnsRoundLedger.kt`
  - `Round` 增 `insertIds: MutableSet<Long>`（:50）
  - 增 `windowInsertIds` 字段（:74），`onResponse`/`reset` 里初始化（:135/:254）
  - 增 `onInsert()`（:158-165），语义同 `onUpsert`，纯内存 HashSet.add
  - `closeWindowLocked` 判据改 `respIds ⊆ (upsertIds ∪ insertIds)`，miss 扣两个集合（:180-181）
  - gate 日志加 `insertN=<J>`（:187/:196）；dump 每行加 `ins=<J>`（:230）
- `SnsGuard.kt`
  - `install()` 补挂 `insertWithOnConflict` + `insert`（:129-134），更新「常驻钩子」注释（:144-150）
  - 增 `snsInsertGuard` 对象（:266-271）
  - 增 `snsIdOfValues()`：wcdb `com.tencent.wcdb.ContentValues` 反射 `get("snsId")`，兼容 Long/Integer/String 装箱（:348-362）
  - 增 `onSnsInsertAfter()`：零反射短路 `SnsInfo` → 按类名含 ContentValues 定位 values → 取 id → `SnsRoundLedger.onInsert`；取不到 id 打节流告警（AtomicBoolean）（:364-381）
  - 增 `VIS_PROBE_MAX=10` 常量（:63）

### [DONE] 子步 2：采样移到 enabled() 之前
- `SnsTimelineMerge.kt`
  - 增 `RoundSample` 数据类（list/respIds/times 一并复用）（:88-92）
  - 增 `sampleRound()`：抽「取 a[3] → 取 f → 遍历 Id/CreateTime」，整体 runCatching（:239-266）
  - `onBeforeResp` 改为：先 `sampleRound` → 空响应闸 → `SnsRoundLedger.onResponse` → `if (!enabled()) return` → 注入主链路复用 `sample.*`（:128-237）
  - 注入体内 `respIds`/`times`/`list`/`list.size` 全部改为 `sample.*`，不重复遍历

### [DONE] 子步 3：每轮可见位统计
- `SnsGuard.kt` 增 `probeVisibleBits(roundNo, ids)`（:1097-1137）：复用 `rawQueryMethod`，查 `select snsId,sourceType from SnsInfo where snsId in (...)`（最多 10 个），统计 `&2!=0` 个数，失败打 `WIN src skip: <原因>` 不阻塞不重试
- `SnsRoundLedger.closeWindowLocked` gate 后 `worker.post` 调 `SnsGuard.probeVisibleBits`（:199-206），不在锁内做 IO

### [DONE] 构建
- `./gradlew assembleDebug --rerun-tasks` → `BUILD SUCCESSFUL in 1m 28s`，35 actionable tasks: 35 executed
- 改动文件无编译警告（编译警告均为历史文件 AvatarLoader/VersionGate/EntryReceiver 等，与本次无关）

[ALL-DONE]



