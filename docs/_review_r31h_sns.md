diffhash: 44ca6b8b47a092cd8801aa05f23b1c273fdc8091

### Q1 SUSPECT

**结论：`snsTagIds()` 的 3 个直接调用方语义全部成立；但「同一并集」被搬到 `snsLedgerIds()` 后仍留在 `SnsAlbumMerge`（本轮同批改动），那里契约没变、bug 也没变，只是当前被注释掉。**

直接用 grep 全量比对（`app/src` 全目录，含 `feature/`、`data/`、`entry/`、测试），`snsTagIds()` 的真实调用点只有 **3 处**（另 1 处是本轮被改写成 `snsLedgerIds()` 的 `SnsAlbumMerge`，即「疑似 4 处」的第 4 处）：

| # | 位置 | 期望 | 现在拿到 | 一致？ |
|---|---|---|---|---|
| 1 | `app/src/main/java/com/lx/wxgroup/feature/SnsTag.kt:646` → `loadIds()` | 「已救下 = 被删过」的 snsId，用来给时间行追加 `[删除已拦截]` 灰标 | 只有物理 DELETE 归档 | ✅ 成立（见下"代价"） |
| 2 | `app/src/main/java/com/lx/wxgroup/feature/SnsGuard.kt:1029`（探针 `srcmap`） | 拿已删清单去和微信库对账：「库里有行 / 无行（物理删除）」 | 同上 | ✅ 成立，且比原来更正确 |
| 3 | `app/src/main/java/com/lx/wxgroup/feature/SnsGuard.kt:1185`（探针 `rankSql`） | 把窗口外的行标成 `[已删台账]` | 同上 | ✅ 成立 |
| 4 | `app/src/main/java/com/lx/wxgroup/feature/SnsAlbumMerge.kt:162` | 「按台账回捞、这一轮响应里没有的」→ 注入好友相册 | **3447 条补位台账**（`snsLedgerIds`） | ❌ 契约本身仍是坏的 |

证据 1（调用方 1，功能主路径）：
```
SnsTag.kt:642  // ⛔ 数据源 = [GroupDb.snsTagIds]，**只认物理删除归档 T_SNS**。
SnsTag.kt:646  return runCatching { db.snsTagIds() }
SnsTag.kt:573  SnsDeletedTag.apply(v, id, tagged)   // tagged = del + forceSet
```
期望「被删过」，收窄后拿到的正是「被物理删过」⇒ 一致。**代价是真实存在的**：`UPDATE sourceType=0` 那条删除路径（R30 实测 4 条）从此不再打灰标。这与 `Keys.kt:328` 的开关文案「显示动态[已删除]标签」相比是**覆盖变小**，KDoc 已把它写成"已知并接受"（`GroupDb.kt:533-535`），属产品取舍，不是 bug。

证据 2（探针 `srcmap`）：
```
SnsGuard.kt:1029  val ids = runCatching { GroupDb.current()?.snsTagIds() }.getOrNull().orEmpty()
SnsGuard.kt:1033  out += "SRCMAP tagged n=${ids.size} 库里有行=$alive 无行(物理删除)=${ids.size - (alive.toLongOrNull() ?: 0)}"
```
这段的算术 `无行(物理删除) = ids.size - alive` **只有在 ids 全是物理删除时才有意义**；并集时代它把补过位的正常动态也算进分母，输出恒为负数级误导。收窄后一致，✅。

证据 3（探针 `rankSql`）：
```
SnsGuard.kt:1185  val tagged = runCatching { GroupDb.current()?.snsTagIds() }.getOrNull().orEmpty()
SnsGuard.kt:1197  (if (inTag) " [已删台账]" else "")
```
「已删台账」字面就是物理删除，✅。

问题 4（第 4 处，本轮同批改动但语义没修）：
```
SnsAlbumMerge.kt:155  //    ⚠️ 用 snsLedgerIds() **不是** snsTagIds()：后者已收窄成「只认物理删除」的
SnsAlbumMerge.kt:162  val missing = SnsAlbumMergeLogic.pickForAlbum(gdb.snsLedgerIds().toList(), respIds, MAX_PER_ROUND)
SnsAlbumMergeLogic.kt:26-31  @param ledgerIds 台账候选（相册版来源是 GroupDb.snsLedgerIds() 的并集…）
HookEntry.kt:271  //     SnsAlbumMerge.install(lpparam)   // 响应注入方案：相册是读库渲染…
```
`pickForAlbum` 的判据仍然是「不在这一轮响应里 ⇒ 缺了 ⇒ 注入」，而好友相册也是**分页**（`SnsAlbumMerge.kt:118` 起 `h3.N` 带 stringSeq 游标）。换成 3447 条的台账后，一旦有人取消 `HookEntry.kt:271` 的注释，就会 1:1 重演本轮时间线的重复注入。它今天不生效**只是因为 install 被注释**，不是因为逻辑被修好。

**附带发现（低危）**：`GroupDb.snsTagIds()`（`GroupDb.kt:537-547`）与既有的 `GroupDb.snsArchivedIds()`（`GroupDb.kt:501-513`）现在**逐字节同语义**（都全量扫 `T_SNS.sns_id`），而 `SnsGuard.kt:1716`、`SnsGuard.kt:1742` 仍在用后者。三个方法（+ `listArchiveIds`）读同一张表，后续任何一处改判据都会漏改另一处。

影响（真机可观察）：
- 修好的一面：正常动态的 `[删除已拦截]` 灰标消失（`SnsTag.loadIds` 从 3447 条降到个位数）。
- 残留的一面：目前**无法**观察到（相册注入未安装）。但 `snsTagIds` 只覆盖一种删除路径，`sourceType=0` 那类删除在时间线上彻底无提示。

建议：
1. `SnsAlbumMerge.kt:162` 的候选**不要**用 `snsLedgerIds()` 的并集；至少先 `INNER JOIN` 一个「被拦下的真删」判据，或者干脆保持 `install` 注释状态并在文件头写死「候选口径未修，禁止启用」。
2. 删掉 `snsArchivedIds()` 或用它实现 `snsTagIds()`（`fun snsTagIds() = snsArchivedIds()`），消灭同一判据的两份实现。
3. 若产品要保住 `sourceType=0` 的灰标，按 R31h 方案 B 加「最近一次回包是否包含」列，别回到 `fixed_orig`。

### Q2 FAIL

**结论：`pickMissing` 的入参契约在「类型」上还成立（`List<Long>` + 已排序），但「排序口径」和「每轮截面」两处已经不对；真正会咬人的是 (c) 没有任何跨轮去重，`MAX_PER_ROUND=12` 会让归档超过 12 条时第 13 条起永远注入不到，且同一条会被追加进每个页窗口。**

#### a) `T_SNS.attr_buf` 是完整快照还是可能残缺？

**是微信真实 protobuf 完整快照，不是拼接出来的，但可能为 NULL。**

证据（写入路径只有一条，且在 DELETE 生效前取整行）：
```
SnsGuard.kt:464  val row = runCatching { readRow(db, id) }.getOrNull()
SnsGuard.kt:466  param.result = null          // 置 null = returnEarly，删不掉
SnsGuard.kt:470  saveAsync(row)
SnsGuard.kt:731  attrBuf = blobOf("attrBuf"),  // select * 原始列，不经任何重组
GroupDb.kt:432   put("attr_buf", row.attrBuf)
```
`readRow` 用的是 `select * from SnsInfo where snsId=?`（`SnsGuard.kt:682`）并按列名取值，`attrBuf` 就是微信 `l5.o` 写进去的那份字节 ⇒ 与微信自己 `new SnsObject().parseFrom(attrBuf)`（`SnsTimelineMerge.kt:36`）同源，**完整**。

两个"可能残缺"的真实入口，都属于 NULL 而不是"半份"：
```
SnsGuard.kt:715  if (gotId != snsId) { LogX.w("SNS read mismatch…"); return null }   // 错位直接拒绝归档
SnsGuard.kt:731  attrBuf = blobOf("attrBuf")   // 列本身为 NULL / getBlob 抛 → 返回 null
```
`blobOf` 内部是 `runCatching { getBlob }`（`SnsGuard.kt:705-710`），**BLOB 超过 CursorWindow 上限（2MB）时会抛并被吞成 null** ⇒ 这条归档的 `attr_buf` 是 NULL，注入时永远走 `emptyBuf`，且日志里看不出是"太大"还是"本来没有"。

另：`SnsGuard.liveRows()`（`SnsGuard.kt:838` 起）构造的是 `attrBuf = null` 的**半行**，但它只喂导出、不喂 `archiveSns`（`GroupDb.kt:391-392` 已把这条纪律写死）⇒ 不构成残缺快照来源。✅

#### b) `Id` / `CreateTime` 解析成 0 或异常时插到哪？

**0 → 追加到列表末尾（正是 R31h 看到的"底部"）；异常值 → 可能空降到第 1 位；而异常本身会让整轮循环中断且 STAT 日志丢失。**

证据：
```
SnsTimelineMergeLogic.kt:43  while (i < times.size && times[i] >= createTime) i++
```
- `createTime = 0`：`times[i] >= 0` 对所有非负时间恒真 ⇒ `i == times.size` ⇒ **插到末尾**。不破坏其它条目相对次序，但被注入的动态固定沉底，视觉上就是"跑到最下面"，与真实时间序不一致。
- `createTime` 漂移成一个很大的 int（字段串位、版本升级）：`times[0] >= ct` 立刻为假 ⇒ `idx = 0` ⇒ **空降到时间线第一条**，这才是真正的顺序污染。
- `Id` 为 0 时已有兜底（`SnsTimelineMerge.kt:183-185`），但 **`CreateTime` 与 `Id` 两次 `getIntField` / `getLongField` 都没有 `runCatching`**（`SnsTimelineMerge.kt:183,186`），与同文件自己在 129-132 行立的规矩直接矛盾：
```
SnsTimelineMerge.kt:125  // ⚠️ 两个值**必须各自兜底**：任一抛异常都会让 times 与 list 错位
SnsTimelineMerge.kt:186  val ct = XposedHelpers.getIntField(o, "CreateTime")   // ← 裸调用
```
字段改名/缺失时异常冒泡到 `mergeGuard`（`SnsTimelineMerge.kt:102-103`）被整包吞掉 ⇒ **本轮剩余 id 全部跳过**，且 `SNS-INJECT-STAT`（`SnsTimelineMerge.kt:197`）根本不会执行，现场只剩一行 `SNS-INJECT before err: …`。这正是本项目最忌讳的"静默失效"。

（`times` 的同步维护是对的：`SnsTimelineMerge.kt:193 times.add(idx, ct)`，插入后下标结构一致 ✅）

#### c) 跨轮次会不会重复注入？`done` / 去重存在吗？

**不存在。每轮都重新注入同一批，且 >12 条时后面的永远轮不到。**

证据（`pickMissing` 的去重只在**单轮内**）：
```
SnsTimelineMergeLogic.kt:23  val seen = HashSet<Long>()          // 函数局部
SnsTimelineMergeLogic.kt:27  if (id in respIds) continue         // respIds 只来自这一轮的 zb5.f
SnsTimelineMergeLogic.kt:29  if (!seen.add(id)) continue
SnsTimelineMerge.kt:157      pickMissing(gdb.listArchiveIds(200), respIds, MAX_PER_ROUND)
```
`respIds` 每轮由当前响应重建（`SnsTimelineMerge.kt:127-132`），而**被删动态永远不会出现在服务端响应里**，所以「候选 ∩ 响应」恒为空 ⇒ 每轮都命中同一批。归档行也没有消费/标记动作：`deleteSnsArchive` 全仓只有一个调用点 `SnsGuard.kt:2403`，且那是调试探针 `forceDelete` 里。⇒ 三个可观察后果：

1. **饥饿**：`listArchiveIds` 按 `create_time DESC`（`GroupDb.kt:563`）稳定排序，`pickMissing` 取前 12 条（`SnsTimelineMergeLogic.kt:31`）。归档 20 条时，**第 13～20 条永远注入不到**，而且没有任何日志提示"被截断"。
2. **跨页重影**：`c3.N` 同时服务首屏与加载更多（同一 NetScene），每页都是一份新的 `zb5.f` ⇒ 同一条被删动态会被**追加进每个页窗口**；再加上注入对象会经 `l5.o` 落库（`SnsTimelineMerge.kt:30` 链路口径），后续按库分页也会带出它 ⇒ 用户翻页/下拉刷新后可能看到同一条多次。
3. **空转开销**：每次 `c3.N`（真机每秒一次，见 `SnsTimelineMerge.kt:133-136`）只要响应非空就重跑 12 次 `readAttrBuf` + 12 次 `list.add`，`ok` 计数器会被这些重复动作一路抬高，让 `attempt=24 ok=24` 这类日志**再也无法用来判断"是否真的恢复了 24 条"**。

影响（真机可观察）：
- 归档 ≤12 条时：重复注入无可见副作用（每轮列表重建），但 `ok` 计数失真、CPU 空转。
- 归档 >12 条时：老的那几条**永远不出现**，与"把人删掉的动态补回来"的需求直接冲突。
- 一旦出现 `CreateTime` 解析异常：注入中断 + 无 STAT 日志，排查时会误判成"钩子没触发"。

建议（按性价比排序）：
1. `SnsTimelineMerge.kt:186` 的 `CreateTime`、`183` 的 `Id` 包进 `runCatching`，`ct <= 0` 直接 `continue` 并打 `reason=bad-ct`；**不要**把 0 交给 `insertIndexDesc` 沉底。
2. 加进程内 `done: MutableSet<Long>`（`ConcurrentHashMap.newKeySet()`），注入成功后 `done += id`；`pickMissing` 之后过滤掉 `done`，或干脆注入成功即 `gdb.deleteSnsArchive(id)`（已有 API，`GroupDb.kt:615`）。这样才真正实现"补到位就结案"，同时天然解决饥饿。
3. 若要保留"每轮都能补"的语义，至少改成游标轮转（记住上轮最后一个 id），别永远从 `create_time DESC` 的第 1 条切 12 条。
4. `SnsTimelineMergeLogic.kt:16` 的 `@param ledger 已按 last_seen DESC 排好` 已过期，实际是 `create_time DESC`，改掉，否则下一个人会按错的排序假设写去重。

### Q3 SUSPECT

**结论：`getType(0)==4` 在"NULL 安全"这一层是够的（NULL 返回 0，不进 `getBlob`，不会抛）；但在"失败可归因"这一层不够 —— BLOB 超过 CursorWindow 上限时 `getBlob` 会抛、被吞成 null，和"这条没存快照"完全无法区分。线程侧无并发 bug（SQLiteOpenHelper 串行化 + 每轮局部变量），唯一代价是注入线程上的逐条 SQL。**

#### 1) `getType(0)==4` 是否足够

证据：
```
GroupDb.kt:582-585  readableDatabase.query(
                      T_SNS, arrayOf("attr_buf"), "sns_id=?", arrayOf(snsId.toString()),
                      null, null, null, "1",
                    ).use { c -> if (c.moveToFirst() && c.getType(0) == 4) c.getBlob(0) else null }
GroupDb.kt:226      attr_buf        BLOB,
GroupDb.kt:432      put("attr_buf", row.attrBuf)     // 传 null 就存 NULL
```
- `attr_buf IS NULL`：`Cursor.getType(0)` = `FIELD_TYPE_NULL`(0) ⇒ 判据为假 ⇒ **返回 null，`getBlob` 根本不会被调用**，不会抛。✅
- `attr_buf` 若被写成 TEXT（`FIELD_TYPE_STRING`=2）：同样返回 null。当前只有 `put("attr_buf", ByteArray?)` 一处写入，不会出现，但这条分支与"没快照"不可区分，属"可接受但无日志"。
- 空 BLOB：`getType` 仍 = 4，`getBlob` 返回 `byte[0]` —— 靠调用方 `buf.isEmpty()` 拦住（`SnsTimelineMerge.kt:166`）✅。
- ⛔ **真正的漏洞**：单条 `attr_buf` 超过 CursorWindow（2MB）时，`getBlob` 抛 `SQLiteBlobTooBigException`（`Row too big to fit into CursorWindow`）。这里被 `runCatching` 兜成 null 并只打一行 `GroupDb snsArchivedAttrBuf failed`（`GroupDb.kt:586`），**调用方拿到的是 null，和"压根没归档"走同一条 `emptyBuf` 分支**。带视频/长文的 SnsObject 序列化确实可能到这个量级 ⇒ 会表现成"这条删了就是恢复不了"，却查不到原因。

#### 2) 日志归属缺口（本轮改动引入）

```
SnsTimelineMerge.kt:171-174
  if (buf == null || buf.isEmpty()) {
      emptyBuf.incrementAndGet()
      LogX.w("$TAG-FAIL snsId=$id reason=empty-buf size=${list.size}")   // ← 没有 src=
```
本轮给 `parse-ex`（179 行）和 `ok`（195 行）都补了 `src=snsinfo|archive`，**唯独最需要它的 `empty-buf` 没补**。而 `empty-buf` 正是"两个来源都没读到"的唯一证据：没有 `src` 就无法判断是 `db?.let{…}` 因为 `snsDbHandle()` 为 null（`SnsTimelineMerge.kt:160`）整段跳过，还是 archive 里也没快照。

#### 3) 线程与并发

证据：
```
SnsTimelineMerge.kt:102  runCatching { onBeforeResp(param) }        // 跑在触发 c3.N 的线程
SnsTimelineMerge.kt:168  buf = gdb.snsArchivedAttrBuf(id)           // 同上线程，循环内逐条查
SnsTag.kt:608-613        首屏同步：val s = loadIds()                // 主线程
SnsTag.kt:616-628        之后：Thread { delSet = loadIds() }        // 后台 "sns-tag-ids"
GroupDb.kt:36            class GroupDb … : SQLiteOpenHelper(…)      // 无自定义 WAL 配置
```
- `onBeforeResp` 由 `c3.N` 的 before 钩子调用，`c3.N` 是 NetScene 回调，**不在 UI 线程**；`snsTagIds()` 首屏那一次跑在主线程（`SnsTag.kt:608`），后续在后台线程。两者都走 `SQLiteOpenHelper.readableDatabase`，`SQLiteDatabase` 内部有连接池 + 锁，**并发读安全**，且 `snsArchivedAttrBuf` / `listArchiveIds` / `snsTagIds` 内部只有局部变量，无共享可变状态 ⇒ **没有数据竞争**。
- 真正的代价不是正确性而是延迟：注入循环里最多 12 次**单行**查询（`SnsTimelineMerge.kt:164` 的 SnsInfo 读 + `168` 的归档读），且因 Q2(c) 每轮重复，`c3.N` 每秒一次时会形成常驻 SQL 压力；主线程首屏那次全表扫（`T_SNS` 现只有个位数行）在收窄后反而比原来的 `UNION` 更轻 ✅。

影响（真机可观察）：
- 大 attrBuf 的归档 ⇒ 该条永远注入失败，`emptyBuf` 涨 1，日志只有 `reason=empty-buf`，看不出是"太大"。
- 归档为 NULL 的条目（`blobOf` 在写库时就已吞掉异常）同样静默。
- 无并发崩溃/脏读现象；`SnsTag` 主线程不会因为这次收窄而变慢。

建议：
1. `SnsTimelineMerge.kt:173` 的 `empty-buf` 日志补上 `src=$src`（一行改动，收益最大）。
2. `GroupDb.kt:581-587` 捕获 `SQLiteBlobTooBigException`（或 `Throwable` 里带 `message.contains("too big")`）单独打一条 `reason=blob-too-big len=?`；或改 `SELECT length(attr_buf)` 先判尺寸再决定是否读。
3. 若担心主线程，把 `snsArchivedAttrBuf` 的 12 次单行查询合成一次 `WHERE sns_id IN (…)`（`listArchiveAttrBufs(ids)`），每轮 1 次 SQL。
4. `SnsTag.loadIds()` 的返回值现在是常量表级数据，可加一次内存快照 + 失效标记（已有 `SET_TTL_MS`/`invalidateIds`），无需改动。

[DONE]

