# R26 朋友圈图片导出 交叉复核

<!-- DONE:8/8 -->

## 1. 单航班与探针并发

**PASS**

- `imgfill` 分支（`MomentsExport.kt:1686`）与 `runAsync()`（`MomentsExport.kt:474`）用**同一个** `running.compareAndSet(false, true)`，互斥成立：任一刻只有一条线程持 `running=true`。
- 探针拿不到锁**不静默**：`imgfill` 走 `else if (!running.compareAndSet(...))` → 返回 `"img backfill SKIP a round is running"`（L1687）。`runAsync` 的 CAS 失败是 `return`（L474），但那是调度节流的既有语义，`pokeExport` 还会返回 `!running.get()`（L523）。
- 探针在 **io 线程同步**跑回填：入口 `EntryReceiver.kt:538` 把整个 `moments` 探针包在 `io { }` 里（L534 注释明确「img/scan 要读库 → 都走后台线程」）。同步阻塞几十秒只占一条 io 线程，**不碰主线程，无 ANR**。
- 主循环已持锁时探针 CAS 失败直接返回提示，**无死锁、无嵌套抢锁**。
- 残留注意（非缺陷）：`imgfill` 同步阻塞直到回填结束，无超时/取消；被阻塞的 io 线程在回填期间不能再服务别的探针。可接受。

## 2. imgDetailLeft 线程安全

**PASS**

- `imgDetailLeft--`（L1119）确为读改写非原子，`@Volatile`（L319）只保证可见性、不保证原子。
- 但真正的串行保证来自 `running` 单航班，**不是** `@Volatile`。全部写点都在 `running` 保护内：
  - 主循环：`imgDetailLeft = IMG_LOG_MAX`（L621）、`--`（L1119）、`= -1`（L1127）；
  - 探针：`imgDetailLeft = 0`（L1690），发生在 CAS 拿到锁之后。
- 两者不可能并发（item 1 已证互斥），所以当前无竞态。KDoc（L316-317）「只在单航班的导出线程里读写，故用普通 Int 即可」表述正确，`@Volatile` 属于额外保险。
- 注意（低）：`imgfill` 的 `imgDetailLeft = 0`（L1690）实际是死代码——回填传 `detail=false`，`copyRowImages` 根本不会读写 `imgDetailLeft`。无害，但注释「顺手把状态摆正」略误导。若未来有人在 `running` 之外加写点，`--` 会立刻变成竞态，建议届时改成 `AtomicInteger` 或 `decrementAndGet`。

## 3. 回填死循环风险

**PASS**

- 游标**无条件**推进：`cur.advanceTo(row.createTime, row.snsId)`（L1223）在 `seen.add`（L1225）与 `st.rows++`（L1226）之前，被去重跳过的行也推游标，与主循环同一条规矩（L680）。
- 「page 一直返回 PAGE 条但 st.rows 不增长」只能发生在整页全是 dup 时；但游标每行推进 → 下页取的是更旧的行 → 两个源都是有限表，终会耗尽 → 空页/短页触发 `nextSource()`（L1217-1219）→ 最终 `break`。**无无限循环**。
- 切源不漏扫：`if (page.size < PAGE && !cur.nextSource()) break`（L1242）——短页会切 ARCHIVE（`nextSource` 返回 true，故 `!true=false` 不 break）；CURRENT 恰为整页(100)耗尽时，靠下一轮空页分支（L1217）切源，仍正确。
- 跨源不重复处理：`seen` 是 snsId 级去重（L1225），同一条在 CURRENT/ARCHIVE 各出现一次也只处理一次。
- 结论：无死循环、无漏扫、无重复扫。唯一代价是「去重行仍占游标推进 + 每页仍读库」，但这只是 O(N) 扫描，非死循环。

## 4. 四态不变式

**PASS（附一处口径失真，建议改）**

- `imgAlready` **确实在四态之外**：L1102 命中 `imgDone` 后 `continue`，不调 `copyOneFromCache`、不落任何四态。KDoc（L303-311）「压根没尝试」表述正确。
- `imgNoMedia`（按动态）也独立（L1090），不混四态。四态（`imgRootMiss/imgFileMiss/imgReadFail/imgCopied`）互斥穷尽（`countImg` 的 `when` 无 else 静默出口，L1155-1176）。
- **但**：「同一 mediaId 同一轮会被算两次」——**成立**。主循环新写 txt 时 `copyRowImages(detail=true)` 一次；随后 `backfillImages`（L761-762）对**刚写入的动态**再 `copyRowImages` 一次（回填不知道哪些是「本轮刚写的」）。
  - 已拷成功的图：回填轮走 `imgDone` 短路 → `imgAlready++`，**不进四态**，无重复；
  - 主循环里 **miss 的图**：回填轮 `dst` 不存在 → 再调 `copyOneFromCache` → **再 miss 一次** → `imgFileMiss/readFail/rootMiss` 同轮被计两次。
- 后果：判据 #5「四态之和 = 已尝试 mediaId 总数」在「唯一 mediaId」口径下失真；在「尝试次数」口径下仍成立。非文件正确性 bug（不产生重复文件、不丢数据），但会让真机对账把「双计」误读成「链路多跑了一遍」。
- **建议改（低）**：把主循环本轮新写入的 snsId 集合传给 `backfillImages` 预置 `seen`（加一个局部 `newThisRound: HashSet<Long>`，在 ok 分支 L737 处 add，L762 传入），回填就跳过本轮刚写过的动态，四态回到「唯一 mediaId」口径。

## 5. 回填的目录重算

**PASS（可接受降级，非 bug）**

- 回填的 `dir` 由 `nickOf` + `MomentsLogic.exportDir` 重算（L1227-1232）。若两次导出之间备注/昵称变了，重算出新目录，`txt.exists()` 为假 → `noTxt++`（L1235-1236），该动态的图本轮不补。
- 且主循环去重按 snsId 文件名全树扫描（`scanExportedIds`），旧目录里的 txt 仍被当「已导出」永不重导（L703-706）→ 该动态的图**永久**补不回，直到备注再改回来。
- 判定：**可接受降级**。文本本身无损（txt 还在旧目录，内容完整）；图片是「机会主义缓存快照」，对改名好友补不到图属于已知边界；`noTxt` 在聚合日志里**可见**（L1245，`backfillImages` 内部 `LogX.i(line)` L1249 自行打出，不依赖调用方），不静默。
- 可选加固（低）：回填不用「重算目录」，改为按 snsId 在导出树里定位 txt（复用 `scanExportedIds` 思路扩展到「返回路径」），即可规避改名问题。非必须。

## 6. stat() 的 images= 读取

**PASS**

- `cfg()` TTL 3 秒（`CFG_TTL_MS`，L191）。`kvset` 链：`EntryReceiver.kt:179` `Kv.putString` → `:193` `MomentsExport.refresh()` **同步**执行（19ms 实测），`refresh()` 里 `copyImages = Kv.getBool(...)`（L363）并更新 `cfgAt`（L365）。
- 随后 `sub=stat` → `probe("stat")` → `stat()` → `cfg()`：因 TTL 未过期直接返回刚刷新的 `config`，**读到新值，无旧值残留**。
- 残留风险（非正确性）：设置页 UI 的 `Kv.putBool` **无回调**（参照 `RevokeRescue.kt:276-278`），不会触发 `refresh()`；此时最坏 3 秒 TTL 内 `stat()` 读旧值，下一次 `cfg()` 自愈。对真机判据无影响（判据看文件系统，不看 `images=` 字段）。

## 7. copyOneFromCache 先 delete 再 rename

**PASS（可接受取舍）**

- 短路判据 `imgDone = exists && length > 0`（`MomentsLogic.kt:305`）只拦「已存在且非空」。**非 0 字节但内容损坏**的 dst（残缺 JPEG 头、被截断但 >0 字节）永远走短路、修不了。
- 但正常链路**不会**产出这种文件：`copyOneFromCache` 走 `tmp + renameTo`（L1002-1036），`dst` 只由 `renameTo` 一次性产生（近似原子），中途崩溃只留 `.tmp`（不产生 dst）。「非空残缺 dst」只能来自：外部篡改、历史 bug 版本、文件系统异常——概率低。
- 另注（低）：源缓存文件恰为 0 字节时 → 拷出 0 字节 dst → `imgDone(true,0)=false` → 下轮重拷、仍 0 字节 → **每轮重复 miss**（每轮一次，非死循环），且会虚增 `imgCopied`（rename 成功即 COPIED）。可接受。
- 若要加固：短路前比对 `dst.length() == src.length()`（需先查索引拿到 src，部分抵消短路省 IO 的收益），或 sniff magic——但设计 Q5 已决定暂不 sniff。**当前取舍可接受**。

## 8. 回填对已删动态的饥饿问题

**PASS（题目原问不成立）＋ 一处真实问题（非轮转扫描，建议改）**

### (a) 原问「归档吃掉 200 配额饿死存活动态」——**不成立**

`backfillImages` 严格 **CURRENT → ARCHIVE**（L1211-1216），CURRENT 先扫、先占配额：

- **一般情形 CURRENT 5000 / ARCHIVE 5000**：CURRENT 有 5000 行，回填处理最新 200 行即 `break@scan`（L1224），**归档根本轮不到**，配额全给存活动态。归档不但不饿死 CURRENT，反而永远饿不着（这正是期望：归档=已删动态，图早已取不回，浪费配额没意义）。
- **设备现状 CURRENT 7 / ARCHIVE 7 高度重叠**：CURRENT 7 行全处理（`seen={7}`），切 ARCHIVE 后重叠行被 `seen.add` 去重跳过（不占 `st.rows`），只有真正只在归档里出现的行才 `st.rows++`。`st.rows ≈ 7~14`，CURRENT 全量处理完，**无饥饿**。

### (b) 真实问题：非轮转扫描，「补不完的下一轮继续」不成立

- `backfillImages` 每轮 `val cur = SnsScanCursor()`（L1206）从 `ct=sid=MAX_VALUE`（最新）重扫，**无持久化游标**。
- CURRENT 5000 时：每轮只处理**最新 200 条**；已补完的最新 200 条仍每轮占满 `st.rows` 配额（`imgDone` 短路只省拷贝，`st.rows++` 已在 L1226 先行），**rank > 200 的存活动态与归档永远轮不到**。
- 这与 KDoc「补不完的下一轮继续」（L1197）**矛盾**——实际语义是「补不完的重头再来」。设备只有 7 行无实际影响，但文档语义不实，会误导真机验收与后续使用。
- **建议改（中）**：二选一——
  1. 持久化回填游标（`lastBackfillCt/lastBackfillSid` 存入内存或 Kv），每轮从上次位置继续，实现真轮转；
  2. 改 KDoc（L1196-1197）明说「每轮只处理最新的 N 条，历史/归档不补」，接受 newest-only 语义。

## 结论

**总判：PASS（可装真机）**。8 项均无 P0/P1——无死循环、无并发踩踏、无死锁、无文件损坏、无数据丢失、无静默失败。

建议改（非阻塞，按严重度）：

1. **（中）item 8(b) 非轮转扫描**：`backfillImages` 每轮从 newest 重扫、无持久化游标，`rank>200` 的存活动态与归档永远补不到，与 KDoc「补不完的下一轮继续」（`MomentsExport.kt:1197`）矛盾。改法：持久化游标实现轮转；或改 KDoc 为「每轮只补最新 N 条」。设备 7 行不受影响，但装真机前建议至少修正 KDoc 表述。
2. **（低）item 4 四态双计**：同一条动态同一轮被主循环 + 回填各处理一次，miss 的 mediaId 四态计数同轮被计两次，判据 #5「四态之和 = 唯一 mediaId 数」失真。改法：主循环本轮新写的 snsId 注入回填 `seen` 预置（`MomentsExport.kt:737` 收集、`L762` 传入）。

其余各项 PASS，可选加固点（改名好友补不到图 / 非空损坏文件修不了 / 0 字节源循环 miss）均为「机会主义缓存快照」的已知边界，无需本轮处理。

[DONE]
