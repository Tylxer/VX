# 数据层伪会话分组行 · 独立复核结论

## ① verdict

**需先修 4 条（1 个 P0 + 3 个 P1）再装包验收。**

- 默认 `GCONV_MODE="false"`（`Keys.kt:75` / `Keys.kt:442`）时，回滚路径逻辑等价成立（见 F），**装包本身不会破坏现有自绘分组行**，这一条不构成阻塞。
- 但本轮上机的目的是验证「开关 ON」后的效果，而 ON 路径存在一项确定会引入的新问题：**A（P0）** 把「全表读 3120 行 + 读写微信主库」同步压在主线程 250ms 去抖回调里，且 `GroupConvStore` 没有任何锁/事务，两个入口并发。这既直接违背本轮改造要修的「体感不流畅」初衷，也违背了自己的设计定案 `docs/_r54_gconv_design.md:294-302`。带着它上机，掉帧与库写入竞态会让验收结论不可信。
- 另外 3 条 P1（B/C/H）会让「开了开关却看不到分组行」「普通会话误开分组页」「热切换半生效」，建议在同批修掉。
- D、F 通过；E、G 及 F 的非逐字节差异可上机后观察。

判定口径：凡给出「确定」结论的均有源码行号；静态判不了的一律写「证据不足，需真机验证」，不硬下结论。

## ② A~H 逐条结论

### A. 并发 / 异步（最高优先）
**结论：P0 阻塞。确证「无锁、无事务、两入口并发、主线程同步跑重活」四点，且实现与自身设计定案冲突。**

**1）两个入口确证并发，且共享同一个 wcdb 连接对象**

`GroupConvStore.sync` 全仓只有两个调用点（`Grep GroupConvStore.sync`）：

- `ConvFilter.kt:1489`：`GroupedAdapter.onDataChanged()` 的 250ms 去抖回调内。`mainHandler = Handler(Looper.getMainLooper())`（`ConvFilter.kt:245`），回调由 `mainHandler.post{...postDelayed(...,250)}`（`ConvFilter.kt:1452-1455`）派发 ⇒ **主线程同步执行**，`sync` 未被包在任何线程切换里（注释 `ConvFilter.kt:1481-1482` 自己写明「不起新线程」）。
- `EntryReceiver.kt:206-218`：`op=gcs sub=sync` 在 `io { }` 后台线程（`:208`）直接调 `GroupConvStore.sync(...)`（`:211-213`）。

`AntiRevoke.wxDb(): Any? = msgDb`（`AntiRevoke.kt:828`，`msgDb` 声明在 `:814`，无 `@Volatile`）返回的是**同一个 wcdb `SQLiteDatabase` 实例**；`sync` 内部再次取这个实例（`GroupConvStore.kt:60`）。即两个线程对同一个 wcdb 连接同时 `rawQuery` + `execSQL`。此外 `op=gconv` 阶段0 探针也在 `io{}`（`EntryReceiver.kt:198-202`），是第三个潜在写库入口（可控实验，验收时勿同时跑）。

**2）`GroupConvStore` 确证没有任何锁 / 事务 / 重入保护**

通读 `GroupConvStore.kt:40-369`：`object` 单例，`sync()`(`:58-106`)、`clean()`(`:109-141`)、`stat()`(`:144-179`) 全是**局部变量**（`out`/`times`/`desired`/`existing`/`ops`），没有字段级共享状态，也没有 `@Synchronized`、`synchronized{}`、`ReentrantLock`、`beginTransaction`/`endTransaction` 中任何一个。

- 没有共享可变字段 ⇒ 单纯「方法重入」不会破坏对象内部状态，这一点不算问题。
- 但它是一次典型的 **read-modify-write 且非原子**：`:63 readConvTimes` → `:65 membersOf` → `:68 readExisting` → `:69 plan` → `:71-93 逐条 execSQL`。多个 `execSQL` 隐式自动提交，`Insert` 分支甚至是 4 条独立 SQL（`:87-90`：del conv / del contact / insert conv / insert contact）。
- 因此并发时 **会读到半更新状态**：A 删了 conv 还没插 contact，B 的 `readExisting`（`:200-228`）把 conv/ct 按 username 合并、缺的一侧补空（`:222-225`）⇒ B 基于「半行」算 `plan`，可能产出错误 `Op`（把刚插的 conv 又判成待删、或漏掉 contact）。**重复 insert / 行数漂移亦可能**：两人同时读到 existing 空 ⇒ 都算 `Insert` ⇒ 第二条 insert 撞唯一索引失败，被 `execLog` 吞掉只打日志（`GroupConvStore.kt:296-303`），先删后插的窗口还会互相删掉对方刚写的行。多数竞态靠幂等最终收敛，但**不保证**（Delete 场景尤甚），且异常全被吞 ⇒ 表现为 `stat` 的 `other`/行数偶发漂移，肉眼难查。
- `Cursor` 关闭这一条**没有问题**：`readAll` 用 `.also { close(cur) }`（`GroupConvStore.kt:341`）+ `close()`(`:356-358`)，Cursor 生命周期收在单次调用内，并发时各线程持有各自 Cursor 对象，不存在共享 Cursor / 未关 Cursor。**但「同一 wcdb 连接上两个 Cursor 同时打开 + 一个线程在写」是否安全，静态判不了，证据不足，需真机验证。**
- 顺带两处文档与实现自相矛盾，佐证这层保护是漏做而非有意省略：`GroupConvPlan.kt:60` 写「让 Store **一次事务**按序执行」、`docs/_r54_gconv_design.md:292` 写「两表必须同一个事务」，而 Store 无事务。

**3）实现直接违反设计定案 §6.7**

`docs/_r54_gconv_design.md:294-302` 的「结论 6.7 写库竞争」要求：
1. 「我方所有写**串行到主线程单一 `Handler` + `synchronized` 闸门**，不与宿主并发」——违反：`sync` 无 `synchronized`，且 `op=gcs` 走 `io{}` 后台线程（`EntryReceiver.kt:208`）。
2. 「幂等 `insert ... or ignore`，**绝不 delete+insert**」——违反：`GroupConvStore.kt:87-90` 就是先删再插（`GroupConvPlan.kt:99-103` 的 `insertConvSql` 是普通 `insert`）。注释 `GroupConvStore.kt:85-86` 以「username 有唯一索引、先删再插才幂等」为由改了做法，属实测推翻设计，本身可讨论，但在无锁并发下「宿主可能正持有该行游标」（设计 `:296` 原话）的风险被放大。
3. 「`rconversation` + `rcontact` 两表写包在一个事务里」——违反：`:71-93` 全是无事务直写。
4. 「绝不跨库缓存 `Method`」——**遵守**（`GroupConvStore.kt:266-277` 每次重扫方法表）。
5. 设计 `:300-301` 的判据「高频收发期间无 `SQLiteException`/`FATAL`、`stat` 的 `other` 恒定」在无锁下不可保证。

**4）主线程一次 sync 的耗时量级（与「修体感不流畅」初衷冲突）**

主导成本是 `readConvTimes`（`GroupConvStore.kt:184-197`）→ `readAll`（`:316-346`）：一条 `select username,conversationTime from rconversation` 全表 3120 行，逐行 `moveToNext` 反射 + 每行 2 个 cell 各一次 `XposedHelpers.callMethod(cur,"getString",i)`（`:334-338`）⇒ 约 **9000+ 次反射调用**；再加 `:95-98` 的 4 次 `countOf`（各一次 `rawQuery`）和每组 2~4 条 `execSQL` 写微信主库。注释自己承认「要全表读 3120 行算组时间、再读写微信主库」（`ConvFilter.kt:89`、`:1477`）。

项目自定的主线程闸门是 `PROBE_WARN_MS = 8L`（`docs/conclusions/conv_list.md:61`），全量 `rebuild()` 真机 9ms 已被认为接近上限（`ConvFilter.kt:1427`）。一次 sync 的量级是几十 ms 起步、反射密集时可能上百 ms，**明确超过一帧（16.6ms），确定造成掉帧**。节流 60s/次（`ConvFilter.kt:93`、`:1485`）只降低频率，不降低单次阻塞时长；且它挂在「修 R53 体感不流畅」的同一条 250ms 去抖回调末尾，属于本轮新引入的卡顿源。

**5）最小改法（不改源码，仅建议）**

1. 把 `GroupConvStore` 的 `sync/clean/stat` 全部串行化到**单一后台线程**（专用 `HandlerThread` 或 `Executors.newSingleThreadExecutor()`），两个入口只 `post`、不等待；`ConvFilter.kt:1483-1498` 主线程块退化成「post 一个 sync 任务」，`EntryReceiver.kt:208` 同样 post 到这个单线程而不是另起 `io{}`。这一步同时解决「主线程卡顿」与「两入口并发」，且不与宿主并发（单一线程）。
2. 若不想引入新线程，退而求其次：`sync` 加 `@Synchronized`（或对象级 `lock`）+ 把 `op=gcs` 改投主线程 `mainHandler`；但**全表读仍压在主线程**，卡顿问题不解决，不推荐。
3. 在 `sync` 的 `runCatching` 内用 wcdb 的 `beginTransaction`/`setTransactionSuccessful`/`endTransaction` 把 `rconversation` + `rcontact` 两表写包住，兑现设计 `:297` 与 `GroupConvPlan.kt:60` 的假设；事务内失败整段回滚，避免半行。
4. 节流状态 `lastGconvSyncMs`（`ConvFilter.kt:96`）现注释「主线程读写」，挪到后台后要改 `@Volatile`/`AtomicLong` 并**统一维护**：`op=gcs` 目前完全不碰这个字段（`EntryReceiver.kt:211` 无引用），手动 gcs 后自动 sync 不会重置计时，可能紧接着又跑一次 —— 并发概率反而更高。
5. 串行化后 `sync` 完成在后台，列表不立即刷新是已知前提（背景：纯写库不刷列表、下次冷启生效），不冲突，无需额外 notify。

**证据不足，需真机验证**：wcdb `SQLiteDatabase` 单连接上「两线程同时 rawQuery/execSQL」是否抛 `SQLiteException` 或死锁；以及一次 sync 的实测耗时（建议用 `K7 perf` 同款探针在真机量一次）。这两点静态判不了，但**不影响 A 的 P0 判定**——无锁 + 无事务 + 主线程全表读本身已是确定缺陷。

### B. convertView 复用污染
**结论：P1。代码在「非命中行」完全没有复位 listener，安全性全押在一个未经证实的宿主行为假设上；建议加防御。宿主是否重设 listener —— 证据不足，需真机验证。**

**1）代码事实：命中行覆盖、非命中行零复位**

`ConvFilter.kt:1793-1815`：`if (gconvEnabled) { ... GroupConvPlan.groupIdOf(username)?.let { gid -> v.setOnClickListener{...}; v.setOnLongClickListener{...} } }`。`?.let` 只有命中分支，**没有 `else`**：当 `groupIdOf` 返回 null（普通会话、`wxgrp_t1` 这类反解不出 gid 的残留行、`ConvReader.username` 读不出字段返回空串）时，对 `v` 的 listener 不做任何处理。整段还被 `runCatching{}.onFailure{}`（`:1814`）包住，异常也只打日志。

**2）伪会话行与普通会话行必然共用同一个回收池（这一点是确定的）**

伪会话行只是 `username = wxgrp_<gidHex>` 的**微信原生会话行**（背景已定案，由微信自己渲染），不是 `GroupRowView`。`getView` 里 `layout.groupIdAt(position)` 对它们返回 `NOT_A_GROUP`（`ConvFilter.kt:1737`），走 `ConvFilter.kt:1762-1774` 的普通会话分支；`getItemViewType(position)` 对非 Group 行返回 `wrapped.getItemViewType(r)`（`ConvFilter.kt:1874-1879`）——即 viewType 完全由微信按会话类型决定，**与 username 无关**，伪会话行与普通会话行同类型。`cv` 的校验只有 `viewTypeOf(it) == getItemViewType(position)`（`ConvFilter.kt:1770`），不区分二者。回收池按 viewType 分池（`docs/conclusions/conv_list.md:5`）⇒ **一个曾被我们设过 listener 的伪会话行 View，一定会被复用成普通会话行的 convertView**，反之亦然。复用污染的场景是真实存在的，不是假想。

**3）「微信复用时会不会重设 listener」——证据不足**

- 支持「会重设」的间接证据：`docs/conclusions/conv_list.md:9`「行点击走**每行自己的 OnClickListener**」、`ConvFilter.kt:1804-1805` 注释「微信在 `wrapped.getView()` 内部已给行设过自己的 listener，我们在之后覆盖才有效」。但这两处都只说明「微信会设 listener」，**没有说明是否在 convertView 复用时每次都重设**。
- 反向疑点（更值得警惕）：微信长按链路走 `onItemLongClick`（`docs/conclusions/conv_list.md:14` 提到 `ConversationListView` 覆写 `set/get OnItemLongClickListener`、`unwrapLongClick`、`LongClickRemap`），有可能是 **ListView 级别**的 `OnItemLongClickListener` 而非每行 `View.setOnLongClickListener`。若微信普通会话行 View 上**本来就没有** per-row long-click listener，它就不会去 `setOnLongClickListener(null)` 清场 ⇒ 我们在 `:1809` 设的 `GroupRowMenu` 监听会在复用成普通行后**残留**，长按普通会话弹出分组菜单。`conv_list.md:82-83` 也只强调「长按回调必须 return true」，没涉及复用清理。
- 仓库现有结论（`docs/conclusions/conv_list.md` 全文）**没有**任何一条记录「微信 `p3.getView` 复用 convertView 时是否重设 OnClick/OnLongClick listener」。⇒ **证据不足，需真机验证**：建议开开关后滚动列表，点/长按一个普通会话，看是否误开 `GroupDetailPage`/`GroupRowMenu`（对照 `conv_list.md:9` 的基线）。

**4）明确回答「是否需要在非命中行显式复位 listener」**

**需要加防御，但「无条件 `v.setOnClickListener(null)` / `setOnLongClickListener(null)`」是错的**：`wrapped.getView` 在 `:1773` 已经跑完，若它确实重设了微信自己的 listener，无条件清空会把微信刚设的 listener 一起清掉 ⇒ 普通会话行变得点不动。两种宿主行为下「正确的复位动作」相反，不能硬编码一种。

最小且对两种宿主行为都安全的改法（建议，不改源码）：**在源头阻断被污染的 View 被复用**，而不是事后猜该复位成什么。
1. 只在命中行的 `v` 上打标记（如 `v.setTag(key, gid)`，`:1808` 同处）。
2. 在 `:1770` 决定 `cv` 之前，先判「convertView 是否带我方标记」且「本行是否为伪会话行」；本行的 gid 可提前用 `GroupConvPlan.groupIdOf(ConvReader.username(wrapped.getItem(r)))` 拿到（与 `:1797-1799` 同一份数据，不新增查库）。
3. 若「convertView 曾被我方接管」且「本行非伪会话行」⇒ **把 `cv` 置 null 传给 `wrapped.getView`**，强制微信新建一个干净的 View（自然带它自己的 listener），而不是复用被污染的；其余情况维持现有 `:1770` 的类型校验。
4. 命中行照旧覆盖，并刷新标记；这样无论微信是否重设 listener，普通行都不会残留我方 listener。

**定级理由**：默认 `GCONV_MODE=false` 时整个 `if (gconvEnabled)` 块不执行（`:1793`），复用污染不会出现 ⇒ 不阻塞装包。但 ON 后一旦宿主假设不成立就是「点普通会话开错页」的功能性错误，且修法小、风险低，故定为 P1，建议与 A 同批修。

### C. 首次 sync 触发
**结论：P1。「冷启首次 onDataChanged 必触发一次同步」的说法不成立**——两处代码事实就足以否掉，另有宿主行为需真机验证。

**1）`lastGconvSyncMs = 0L` + `elapsedRealtime()` ⇒ 开机 60s 内首次 sync 必被跳过（确定）**

`ConvFilter.kt:96` `private var lastGconvSyncMs = 0L`；`:1484` `val now = android.os.SystemClock.elapsedRealtime()`；`:1485` `if (now - lastGconvSyncMs >= GCONV_SYNC_THROTTLE_MS)`，`GCONV_SYNC_THROTTLE_MS = 60_000L`（`:93`）。`elapsedRealtime()` 是**开机至今**的单调时长，开机 <60s 时 `now - 0 < 60000` ⇒ 条件为假 ⇒ 首次 sync 不执行。注释 `:1480` 只论证了「不用 `currentTimeMillis` 防用户改系统时间」，没有处理「初值 0 把开机时刻伪造成上次同步时刻」这个副作用。

更糟的是 `lastGconvSyncMs = now` 只在**通过节流之后**才赋值（`:1486`）：被吞掉的这一次不会推进时间戳，所以开机 60s 内的每一次 onDataChanged 都继续被吞；必须等开机满 60s **且再来一次 onDataChanged** 才会 sync。而 onDataChanged 是事件驱动的（见下），用户静置无新消息时这一次可能很久不来 ⇒ 分组行迟迟不出现。

用单调时钟本身是对的，问题只在初值。最小改法：`lastGconvSyncMs = -GCONV_SYNC_THROTTLE_MS`（或 `if (lastGconvSyncMs == 0L || now - lastGconvSyncMs >= ...)`），保证首次必触发。

**2）`install()` 不跑 sync，也不触发 onDataChanged（确定）**

`sync` 全仓只有两个调用点（见 A），**生产侧自动同步只有 `onDataChanged` 的 250ms 回调这一处**（`ConvFilter.kt:1483-1498`）。而 `install(list)` 末尾是 `fa.rebuild()`（`:910`）→ `restoreAnchor` → `fa.notifyDataSetChanged()`（`:912`），**没有 `fa.onDataChanged()`**；`rebuild()`（`:1201-1326`）里也只更新 `gconvEnabled` 字段（`:1221`）、算 layout，完全不碰 sync。

`:912` 的 `fa.notifyDataSetChanged()` 不能替代 onDataChanged：`install` 里把 ListView 的 `mDataSetObserver` 从 `inner` 挪到了 `fa`（`:820-828`），而转发 `onDataChanged` 的那个 observer 是**注册在 `inner` 上**的（`:832 inner.registerDataSetObserver(object: DataSetObserver(){ override fun onChanged(){ ... fa.onDataChanged() } })`，`:859`）。DataSetObserver 只由「被注册的那个 adapter」的 notify 触发 ⇒ fa 的 notify 不会回调 inner 上的 observer ⇒ **不会触发 `fa.onDataChanged()`**。

**3）`onDataChanged` 在冷启 / 列表创建时是否必定触发 —— 证据不足，需真机验证**

`registerDataSetObserver` 注册时不会立即回调 `onChanged`；`onChanged` 只在 `inner` 自己 `notifyDataSetChanged()`（收新消息 / 置顶变化，`docs/conclusions/conv_list.md:56`）时调用。若微信在 `install()` 之前就完成了首次数据加载与 notify，我们后挂的 observer 收不到 ⇒ 冷启首次没有 onDataChanged。这一点静态判不了，属宿主行为，**证据不足，需真机验证**：冷启后看 `K7 debounce ...` 日志（`ConvFilter.kt:1465-1468`）是否在没有任何用户操作时出现。但无论宿主是否触发，第 1）条的 60s 节流缺口都是确定存在的。

**4）最小改法（建议，不改源码）**

1. `ConvFilter.kt:96` 初值改为 `-GCONV_SYNC_THROTTLE_MS`（或首次判定加 `lastGconvSyncMs == 0L` 短路），保证第一次 250ms 回调必跑 sync。
2. 补一个**不依赖 onDataChanged 的兜底入口**：在 `install()`（`:910` 附近）或 `refresh()`（`:631` 附近）post 一次同步任务。`kvset` 已主动调 `ConvFilter.refresh()`（`EntryReceiver.kt:233`），在 refresh 里顺带 post 一次可覆盖「开开关后一直没有 onDataChanged」的场景。
3. 兜底必须走 A 条建议的**串行化单线程入口**，不能在 `install()`/`refresh()` 的主线程里直接跑 `sync`，否则等于把全表读写库又搬回主线程。
4. 已有手动兜底 `op=gcs sub=sync`（`EntryReceiver.kt:206-218`），但它不受节流、也**不更新** `lastGconvSyncMs`（`:211` 无该字段引用），验收时可用它先把库灌上；长期不能只依赖手动命令。

**定级理由**：默认 false 时整段不执行，不阻塞装包；但 ON 后「开了开关却看不到分组行」会让本轮验收直接得到假阴性，故 P1。

### D. passthrough 下标与 username 取值
**结论：通过（PASS），无需修改。passthrough 分支下 `r == position` 确证；`ConvReader.username` 能取到 username，取不到时保守放行。**

**1）identity 确证**

`ConvFilter.kt:1762` `val r = layout.convIndexOf(position)`；`convIndexOf` 定义为 `(rows.getOrNull(position) as? ConvRow.Conv)?.index ?: -1`（`ConvLayout.kt:233-234`）。gconv ON 时 `:1314` `if (groups.isEmpty() || gconvEnabled)` 恒真 ⇒ 走 `:1318` `ConvLayout.build(snaps, emptyList(), emptyMap(), enabled = false)`；`ConvLayout.build` 在 `!enabled || groups.isEmpty()` 时返回 `Layout(passthrough(convs))`（`ConvLayout.kt:86`），而 `passthrough(convs) = convs.indices.map { ConvRow.Conv(it) }`（`ConvLayout.kt:213-214`）⇒ 第 i 行就是 `ConvRow.Conv(i)` ⇒ `convIndexOf(position) == position`。**`r == position`，取 item 不会错位。**

三条消费 `r` 的路径完全自洽：`wrapped.getView(r, cv, parent)`（`ConvFilter.kt:1773`）、`wrapped.getItem(r)`（`:1797`）、`getItemViewType` 的 `wrapped.getItemViewType(r)`（`:1876-1878`）——都用同一个 `r`，不存在一处用 position、一处用 r 的混用。

补充（不影响结论）：`when` 另两个分支不是 identity —— `db == null || !enabled` 走 `:1301`（同样 `enabled=false` 的 passthrough，仍是 identity）；`filterId > 0` 筛选态走 `ConvLayout.filtered`（`:1307`、`ConvLayout.kt:169-176`），`r` 是过滤后的原始下标而非 position。但筛选态下 `filtered` 只保留 `wxid in members` 的会话，`wxgrp_` 行不是成员 wxid ⇒ 不会出现在筛选列表里 ⇒ `groupIdOf` 不会命中、不接管。且即便命中，三条路径依旧统一用 `r`，不会点错人。

**2）`ConvReader.username(item)` 确证可取，null/失败时无害**

`ConvFilter.kt:1797-1798` `val item = wrapped.getItem(r); val username = ConvReader.username(item)`。`ConvReader.username`（`ConvReader.kt:42-46`）沿类链按名字找 `field_username`（`:25`、`:109-125`，缓存到 `ConcurrentHashMap`），`conv == null` 返回 `""`（`:43`），字段找不到/反射失败也返回 `""`（`:44-45`）。类注释 `ConvReader.kt:9-14` 记录 8.0.48/2588 实到会话对象 `com.tencent.mm.storage.r4` 的父类有该字段（示例 `wx__c_...` / `...@chatroom`）⇒ **能从微信会话 item 拿到 username**，返回类型是非空 `String`。

`GroupConvPlan.groupIdOf("")`（`GroupConvPlan.kt:30-35`）先判 `username == null || !startsWith(PREFIX)` ⇒ 直接 null ⇒ `?.let`（`ConvFilter.kt:1800`）不执行 ⇒ **保守放行，交回微信原行为，不接管、不崩溃**。与 `:1784-1787` 注释一致。

**3）窄口径的一个固有限制（P2 观察，非阻塞）**

`groupIdOf` 只做「`wxgrp_` 前缀 + 后缀 `toULongOrNull(16)` 能反解」（`GroupConvPlan.kt:30-35`），`isOurs` 与之等价（`:42`）。`wxgrp_t1` 这类含非 hex 字符的探针残留行会返回 null、正确放行（`:1784-1787`）。但**任何 `wxgrp_` + 纯合法 hex 的非本方行都会被误判为我方行**（例如若探针用 `wxgrp_deadbeef` 之类纯 hex 命名）。真实微信会话 username 不会落在这个命名空间（背景：本方独立前缀），风险极低；建议上机后 `op=gconv sub=stat` 看一眼残留行命名即可，不必改码。

### E. 热路径查库
**结论：P2（可上机后观察）。确实是热路径查库，但量级小；建议顺手把组名缓存进 `rebuild()` 快照，改法几乎零成本。**

**1）命中行每行一次，且无缓存（确定）**

`ConvFilter.kt:1800-1803`：`GroupConvPlan.groupIdOf(username)?.let { gid -> val name = GroupDb.current()?.listGroups()?.firstOrNull { it.id == gid }?.name ?: username ... }`。位置在 `?.let` 内 ⇒ **只有命中我方伪会话行才查**；但 `getView` 是逐行调用，滚动时每个可见伪会话行各一次，没有 memoize。`:1801` 注释也承认「只在命中我方行时才查库取组名」。

**2）开销评估**

`GroupDb.listGroups()`（`GroupDb.kt:421-429`）是 `readableDatabase.query(T_GROUP, null, null, ...)` —— 对 `groups` 表**全表读**并带 `ORDER BY pinned DESC, sort_order ASC, id ASC`，逐行 `Cursor.toGroup()` 建 `Group` 对象（`:973-980`），再 `firstOrNull { it.id == gid }` 线性找。要点：
- 读的是**模块自己的 `groups` 表**（分组定义，通常几十行），不是 A 条那条 3120 行的微信 `rconversation` 全表 ⇒ 单次量级小（通常 <1ms）。
- 走 AOSP `SQLiteOpenHelper`（`GroupDb.kt:36`），注释 `:169-170` 明确「模块自己的库不走 wcdb、不触发挂在微信库上的钩子」⇒ **与 A 条微信主库 wcdb 的并发写库不是同一个连接，不会互相踩**，这一点 E 独立成立。
- 但仍是主线程 `getView` 里的一次「打开 query + 遍历 cursor + 分配 List<Group>」，滚动时每秒可能几十次，属于 `ConvFilter.kt:1219-1222`、`:1054-1063` 自己反复强调要避免的「热路径查库」（那两处正是为了把 Kv 查询从每行挪到 rebuild 才加的字段）。
- `GroupDb.current()` 是 `@Volatile` 单例读（`GroupDb.kt:51-65`），本身无开销。

**3）建议的缓存改法（P2，不改源码）**

`rebuild()` 在 `:1230-1231` **已经读过一次** `db.listGroups()` 存进局部 `groups`（并用于 `configStamp` 的 `groups.hashCode()`，`:1245`）。把这份 `List<Group>` 或一张 `Map<Long, String>` 存成 `@Volatile` 字段，`getView` 改查内存 Map，即可完全去掉每行查库。缓存失效**无需额外逻辑**：`configStamp` 已含 `groups.hashCode()`（`:1245`），组名/置顶/排序变化会改变指纹 ⇒ 触发 rebuild ⇒ 快照自然刷新（与 `docs/conclusions/conv_list.md:85`「指纹必须含分组状态」的定案一致）。

**定级理由**：单次开销小、默认 false 不执行、不影响正确性，不构成阻塞 ⇒ P2。但既然 `rebuild()` 已有现成快照，顺手复用是最省的做法，建议与 A/B/C 同批处理时一并带上。

### F. 回滚零差异
**结论：通过（PASS，回滚生命线成立）。逻辑等价，但严格说不是「逐字节零差异」——OFF 时每次 rebuild 多一次 Kv 查库，列 P2 观察。**

逐条核对：

**1）`|| false` 与原 `groups.isEmpty()` 等价（确定）**

`ConvFilter.kt:1314` `if (groups.isEmpty() || gconvEnabled)`，diff 前为 `if (groups.isEmpty())`。`gconvEnabled` 为 `Boolean`，初值 `false`（`:1058` 附近声明 `@Volatile private var gconvEnabled = false`，在 `GroupedAdapter` 内、与 `layout`/`snaps` 并列），Kotlin `||` 短路语义保证 `X || false ≡ X` ⇒ **表达式逐字等价**，OFF 时 `if` 内 layout 分支与原来完全相同（`:1315-1318` 仍走 `ConvLayout.build(..., enabled=false)`）。

**2）`gconvEnabled` 初值与读取时机（确定，且偏保守）**

- 初值 `false`；缺省也是 `false`：`Kv.getBool`（`Kv.kt:35-38`）走 `Prefs.defaultOf(key)`，而 `Keys.kt:442` 的 `PrefSpec(Keys.GCONV_MODE, PrefKind.SWITCH, "false", ...)` 默认值就是 `"false"`；`Kv.kt:28-31` 在 `GroupDb.current() == null` 或读异常时都回落默认值，`:1221` 再套 `getOrDefault(false)`。**三层兜底都指向 false ⇒ 配置缺失/DB 未就绪/读异常时一律按 OFF 回滚**，方向正确。
- 读取时机：`:1221` 位于 `rebuild()`（`synchronized(lock)`，`:1201`）开头，在 `configStamp` 短路判断 `:1269` **之前**，且无条件执行 ⇒ 即使被短路 return，`gconvEnabled` 也已刷新（这一点对 H 有影响，见 H；对 F 无影响，OFF 时恒 false）。

**3）OFF 时 `AntiRevoke.wxDb()` 因短路完全不被调用（确定）**

`:1483` `if (gconvEnabled && AntiRevoke.wxDb() != null)` —— Kotlin `&&` 短路，左值为 false ⇒ 右侧 `AntiRevoke.wxDb()`（`AntiRevoke.kt:828`）**不执行**；整块 `:1484-1498`（含 `:1489` 的 `GroupConvStore.sync`）也不进入，故 `sync` 内部 `GroupConvStore.kt:60` 的那次 `wxDb()` 同样不发生。`:1793` `if (gconvEnabled)` 同理 ⇒ getView 里 `wrapped.getItem(r)`（`:1797`）、`GroupDb.current()?.listGroups()`（`:1802`）、两个 `setXxxListener`（`:1808-1812`）全部不执行。**OFF 路径没有任何微信主库读写、没有额外查库、没有 listener 覆盖。**

**4）`getItemViewType` / `getViewTypeCount` / `configStamp` 未被改动（确定）**

对照 `git diff -- Keys.kt ConvFilter.kt` 全文与 `git diff --stat`：ConvFilter.kt 仅 7 处 hunk（import `GroupConvPlan`、`GCONV_SYNC_THROTTLE_MS`+`lastGconvSyncMs`、`gconvEnabled` 字段、`:1221` 读开关、`:1314` 表达式、`:1471-1498` onDataChanged sync、`:1776-1818` getView 接管）。`getItemViewType`（`:1874-1879`）、`getViewTypeCount`（`:1881`）、`configStamp` 构造（`:1239-1247`，`PinFoldLogic.configStamp(n, enabled, filterId, showUnread, membersHash, groupsHash, pinFolded)`）**均不在任何 hunk 内** ⇒ 未改动。`ConvLayout` / `GroupRowView` / `GroupedAdapter` 包装链 / `ConvReader` / `GroupDb` / `GroupConvPlan` / `GroupConvStore` 在本次 diff 中也无删除（`git diff --stat` 只有 Keys.kt、ConvFilter.kt、EntryReceiver.kt 三个源码文件改动） ⇒ **回滚有形**，置回 false 即恢复旧自绘分组行，符合 `Keys.kt:62-65` 的回滚描述。

**5）不是「逐字节零差异」的两点（P2，如实记录）**

- `rebuild()` 每次多了 `:1221` 的一次 `Kv.getBool(GCONV_MODE)` ⇒ 一次 `GroupDb.getPref` 主键查询（`Kv.kt:28-31` → `GroupDb.kt:917-923`）。rebuild 最多每 250ms 一次（`:1455` / `:627` 去抖），单次开销 negligible，但**运行时确实非零**。
- 新增的常量/字段/import（`:93`、`:96`、`:1058`、import `GroupConvPlan`）只占声明，无运行时开销。
- `configStamp` 未把 `gconvEnabled` 纳入（`:1239-1247`）⇒ 这是 H 条的热切换问题，不属于 OFF 回滚路径。

其余 OFF 行为（`:1282` light 路径 `rebuildModels`、`docs/conclusions/conv_list.md:33` 的指纹判据、`CHAT_GROUP_ROW_ENABLED` 默认 `"true"`）均未改动。综合：**回滚逻辑等价成立，可以放心把 `GCONV_MODE` 置回 false 作为回退手段**；「零成本」按 `Keys.kt:63` 的语义（无需发版、无需清数据）成立，按「零额外指令」不成立。

### G. 互斥开关
**结论：P2。两者同开**不会**出现两套分组行（`:1314` 的 `|| gconvEnabled` 已把自绘行抑制掉），`Keys.kt:67-68` 的注释与实现不符；代码层无需为「防重复行」强制互斥，但建议显式化以去掉隐含依赖和一处性能空跑。**

**1）同开是默认会发生的情况，不是边缘场景**

`Keys.kt:67-68` 只以注释要求「开 GCONV_MODE 时把 CHAT_GROUP_ROW_ENABLED 关掉」，代码无强制（全文只有 `Keys.kt:75` 常量、`:442` PrefSpec、`ConvFilter.kt:1218`/`:1221` 两次独立读取，没有任何互斥检查）。而 `CHAT_GROUP_ROW_ENABLED` 的 PrefSpec 默认值是 **`"true"`**（diff 中 `PrefSpec(Keys.CHAT_GROUP_ROW_ENABLED, PrefKind.SWITCH, "true", "聊天", "启用分组行")`）⇒ 用户只把 `GCONV_MODE` 置 1、不手动关另一项 ⇒ **必然同开**。

**2）逐分支推演：收纳态下自绘行被抑制，注释说法不成立**

同开时 `enabled = true`（`:1218`）、`gconvEnabled = true`（`:1221`）、收纳态 `filterId == NONE`：
- `:1296` `db == null || !enabled` ⇒ false，不命中；
- `:1303` `filterId > 0` ⇒ false，不命中；
- `:1309` else ⇒ `:1314` `if (groups.isEmpty() || gconvEnabled)` **恒真** ⇒ `:1315-1318` 把 `memberSets`/`models`/`lastSpecs` 清空并 `ConvLayout.build(snaps, emptyList(), emptyMap(), enabled = false)` ⇒ **passthrough，自绘分组行一条都不插**。
- `getView` 里所有行都是 `ConvRow.Conv`，`layout.groupIdAt(position)` 返回 `NOT_A_GROUP`（`:1737`）⇒ 不进 `:1738-1759` 的 `GroupRowView` 分支；`gconvEnabled=true` ⇒ 只对 `wxgrp_` 行在 `:1800-1813` 接管。

⇒ 结果只有**一套**行（微信原生伪会话行），`Keys.kt:67-68` 说的「自绘行 + 微信原生伪会话行同时出现」**与实现不符**。真正保证互斥的是 `|| gconvEnabled` 这个隐含副作用，不是用户的手动操作。

**3）同开仍有的两处真实副作用**

- **性能空跑**：`:1282` light 路径 `if (filterId == NONE && db != null && enabled) rebuildModels(db, groups, memberSets, showUnread)` —— `enabled` 仍是 true ⇒ 对 `groups` 每个组跑一次 `buildModel`（`:1645-1690`），而 `memberSets` 已被 `:1315` 清成 `emptyMap()` ⇒ `buildModel` 的 `for (i in snaps.indices)`（`:1657`）仍遍历全部约 2900 条 `snaps`、每条 `wxid !in wx` 立刻 continue ⇒ **O(组数 × 2900) 的纯 CPU 空转**，与项目自定主线程闸门 `PROBE_WARN_MS = 8L`（`docs/conclusions/conv_list.md:61`）相冲。
- **指纹**：`configStamp` 含 `enabled`（`:1241`），不含 `gconvEnabled` ⇒ 手动关 `CHAT_GROUP_ROW_ENABLED` 会改指纹触发一次 rebuild（无害）；但反过来只翻 `GCONV_MODE` 不改指纹 —— 那是 H 条。
- `getItemViewType`/`getViewTypeCount`（`:1874-1881`）不受 `enabled` 影响，无回收池风险。

**4）是否需要代码层强制互斥**

**不必为了「防两套行」强制**（`||` 已保证），但建议把隐含依赖显式化，二选一：
- (a) 在 `:1218-1221` 读开关处引入 `val effEnabled = enabled && !gconvEnabled`，让 `:1241` 的 configStamp、`:1282` 的判据统一用 `effEnabled`（`:1296` 保持原样，避免与 `:1314` 的 passthrough 语义打架）。这一步同时消掉上面的空跑，并让「ON 时等价于自绘行关闭」成为代码事实而非注释约定。
- (b) 最低成本：只修 `Keys.kt:67-68` 的注释（写明同开不会两套行、但建议关掉以免空跑），并把 `:1282` 的条件补上 `&& !gconvEnabled`。

**5）操作层面的回滚坑（建议写进验收说明）**

按注释「开 GCONV_MODE 时关掉 CHAT_GROUP_ROW_ENABLED」操作后，回滚时若只把 `GCONV_MODE` 置回 false、**忘了把 `CHAT_GROUP_ROW_ENABLED` 开回 true**，`enabled=false` 会让 `:1296` 走「零改动」分支 ⇒ 既无伪会话行也无自绘行，表现为「回滚后分组行全没了」，容易被误判成回滚失败。回退必须**两个键一起复位**。

**定级理由**：无功能错误、默认 false 不触发、回收池无风险 ⇒ P2，可上机后观察；但注释误导 + 空跑 + 回滚操作坑都建议在验收前先对齐口径。

### H. 验收方式
**结论：说法「必须冷启才生效」结论正确，但理由不完整，且真实情况比「被吞掉」更别扭——是「字段热、layout 冷」的半生效中间态。列 P1（中间态）+ 给出验收 SOP。**

**1）`kvset` 不是「完全被吞掉」**

`EntryReceiver.kt:222-233`：`kvset` 写 `Kv.putString`（`:225`）后**主动调 `ConvFilter.refresh()`**（`:233`）。`refresh()`（`:623-643`）250ms 后对每个 wrapper **无条件** `fa.rebuild()`（`:631`）**且无条件** `fa.notifyDataSetChanged()`（`:639`）。所以：
- `rebuild()` 里 `gconvEnabled = runCatching{ Kv.getBool(Keys.GCONV_MODE) }...`（`:1221`）位于 `configStamp` 短路判断 `:1269` **之前**、无条件执行 ⇒ **`gconvEnabled` 字段会被热更新**，不需要冷启。
- `fa.notifyDataSetChanged()`（`:639`）⇒ **列表会重绘**，`getView`（`:1793`）会用更新后的字段。

**2）`configStamp` 短路确实让 layout 不重算（说法成立的那一半）**

`configStamp`（`:1239-1247`）= `n | enabled | filterId | showUnread | membersHash | groupsHash | pinFolded`，**不含 `gconvEnabled`**（也未在 diff 中改动）。翻转 `GCONV_MODE` 不改这 7 项 ⇒ 若 `!membersDirty && !orderDirty && configStamp == lastStamp && snaps.isNotEmpty()`（`:1269`）且 `fp == lastTimeFp && !summariesDirty`（`:1273`）⇒ `:1275` `return false` ⇒ **layout 不重算**、`rebuild` 内部不 notify。冷启时 `snaps` 初值为 `emptyList()`，`snaps.isNotEmpty()` 为 false ⇒ 短路不成立 ⇒ 全量重建 ⇒ 按新开关算出正确 layout。**「必须冷启 layout 才对」成立。**

**3）由此产生的半生效中间态（这才是实际问题）**

- **OFF→ON 热切换**：字段变 true、列表重绘，但 layout 仍是旧的「带自绘 `Group` 行」（`:1339` 的结果）。`getView` 对 `Group` 行在 `:1738-1759` 直接 `return GroupRowView`，**根本走不到 `:1793` 的 gconv 伪会话分支**；且 `sync` 没跑（sync 只在 `onDataChanged`，`refresh()` 不触发它，见 C 第 2 点），库里可能还没有 `wxgrp_` 行 ⇒ 用户看到「开关开了、界面完全没变」。
- **ON→OFF 热切换**：字段变 false、layout 仍是 passthrough ⇒ 不接管、也没有自绘行 ⇒ 分组行整体消失。
- 即「点击/长按接管」是热切换的，「layout 形态」是冷切换的，两者错位。验收者若按 `kvset` 后立刻看界面，会得到**假阴性**。

**4）建议（P1，改动小）**

把 `gconvEnabled` 纳入 `configStamp`（在 `:1239-1247` 增加一项；需同步给 `PinFoldLogic.configStamp` 加参数，该函数不在本轮 diff 内），或翻转时改走 `refreshMembers()`（`ConvFilter.kt:662-676`，递增 `membersVersion` 破 `:1269` 短路）⇒ 翻转即触发全量重建，layout 与字段一致，中间态消失。

注意：这只能消除中间态，**不能让组名热生效** —— 背景已真机定案「改名写 `rcontact.nickname`、微信有联系人内存缓存、下次冷启才生效」（`Keys.kt:70-73`、`ConvFilter.kt:1789-1792`）。因此「至少一次冷启」在真机验收中依旧必要，不要把「纳入指纹」理解成可以免冷启。

**5）真机验收正确操作顺序（SOP）**

1. `op=gconv sub=stat` 记基线（`convOwn`/`contactOwn`/`other`）；有残留 `wxgrp_` 行先 `op=gcs sub=clean` 清掉。
2. `kvset key=chat.gconv_enabled val=1`；按 G 条同时决定 `chat.group_row_enabled`（建议置 `0`，并记住回滚要一起复位）。
3. **冷启微信：强杀进程后重开**（不是切后台）。`kvset` 触发的 `refresh()` 重绘**不算**，不要在热态下判「开关无效」。
4. 触发一次同步：因 C 条首次可能不触发，优先手动 `op=gcs sub=sync`（`EntryReceiver.kt:206-218`），看 `GCONVSTORE` 日志 `sync done own=...` 是否等于分组数；再 `op=gconv sub=stat` 复核 `other` 与基线一致。
5. **再冷启一次微信**，让联系人内存缓存重读 `rcontact.nickname`，组名才显示。
6. 验收项：点击/长按伪会话行是否开 `GroupDetailPage`/`GroupRowMenu`；**重点回滚 B 条**——滚动后点/长按普通会话是否被污染；A 条的滚动流畅度与 `K7 perf`/`GCONVSTORE` 日志；E 条滚动是否可感知卡顿。
7. 回滚：`kvset key=chat.gconv_enabled val=0` **并且** `kvset key=chat.group_row_enabled val=1`（两个键一起，见 G 第 5 点）→ `op=gcs sub=clean` → 冷启 → `stat` 确认 `own=0`、`other` 不变。

**定级理由**：不改也能靠 SOP 拿到有效结论（故非 P0），但「热翻转看到界面没变」几乎必然被误判成开关失效，且修法只是给指纹加一项 ⇒ P1。

## ③ P0 / P1 / P2 清单（精确修改建议，不改源码）
以下只给修改建议，**不改动任何源码**（本复核为只读）。

### P0（阻塞装包，1 条）

**P0-1 · A：把 `GroupConvStore` 的写库串行化 + 加事务 + 从主线程卸载**
- 位置：`ConvFilter.kt:1483-1498`、`EntryReceiver.kt:206-218`、`GroupConvStore.kt:58-106`。
- 改法：
  1. 在 `GroupConvStore` 内建**单一后台线程**（专用 `HandlerThread` 或 `Executors.newSingleThreadExecutor()`），`sync/clean/stat` 全部 post 到它、串行执行、调用方不等待；两个入口只负责 post。
  2. `ConvFilter.kt:1483-1498` 主线程块改为「post 一个 sync 任务」；`EntryReceiver.kt:208` 的 `io{}` 改为 post 到同一个单线程（不要另起线程）。
  3. `GroupConvStore.kt:61-105` 的 `runCatching` 内用 wcdb 反射 `beginTransaction` / `setTransactionSuccessful` / `endTransaction` 把 `rconversation` + `rcontact` 两表写包住，失败整段回滚；兑现 `docs/_r54_gconv_design.md:297` 与 `GroupConvPlan.kt:60` 的既有假设。
  4. `lastGconvSyncMs`（`ConvFilter.kt:96`）改 `@Volatile`/`AtomicLong`，并让 `op=gcs`（`EntryReceiver.kt:211`）也更新同一份节流状态，避免手动 gcs 后自动 sync 立刻二次触发。
  5. 至少补一条耗时探针（复用 `K7 perf` 形式）在真机量一次 sync 耗时，确认不再占用主线程。
- 备选（不推荐）：只加 `@Synchronized` + 把 `op=gcs` 改投 `mainHandler`；能消并发但全表读仍压主线程，卡顿不解决。

### P1（建议先修，3 条）

**P1-1 · B：阻断被污染 View 的复用，而非事后清 listener**
- 位置：`ConvFilter.kt:1770`（`cv` 决定）、`:1793-1815`（gconv 块）。
- 改法：命中行在 `v` 上打标记（`:1808` 同处 `v.setTag(key, gid)`）；在 `:1770` 之前用 `GroupConvPlan.groupIdOf(ConvReader.username(wrapped.getItem(r)))` 预判本行 gid（与 `:1797-1799` 同源，不新增查库）。若「convertView 带我方标记」且「本行非伪会话行」⇒ **把 `cv` 置 null** 传 `wrapped.getView`，强制微信新建干净 View；其余维持现有 `viewTypeOf(it) == getItemViewType(position)` 校验。
- ⛔ 不要在非命中行无条件 `v.setOnClickListener(null)` / `setOnLongClickListener(null)`：`:1773` 的 `wrapped.getView` 已跑完，无条件清空会连微信刚设的 listener 一起清掉，普通行反而点不动。
- 上机重点验证：开开关滚动后，点/长按普通会话是否误开 `GroupDetailPage`/`GroupRowMenu`（宿主是否重设 listener 静态判不了）。

**P1-2 · C：首次 sync 兜底**
- 位置：`ConvFilter.kt:96`、`:1485`、`:910`（`install`）、`:631`（`refresh`）、`EntryReceiver.kt:233`。
- 改法：
  1. `lastGconvSyncMs` 初值改 `-GCONV_SYNC_THROTTLE_MS`，或 `:1485` 条件加 `lastGconvSyncMs == 0L ||`，保证首次 250ms 回调必跑（现初值 `0L` 会把开机 <60s 的首次吞掉）。
  2. 在 `install()`（`:910` 附近）或 `refresh()`（`:631` 附近）补一次 post 兜底；`kvset` 已调 `ConvFilter.refresh()`（`EntryReceiver.kt:233`），顺带 post 可覆盖「开开关后无 onDataChanged」。
  3. 兜底必须走 P0-1 的串行化单线程，**不要**在 `install()`/`refresh()` 主线程直接跑 `sync`。
- 上机验证：冷启后看 `K7 debounce ...` / `GCONVSTORE sync start` 是否在无用户操作时出现。

**P1-3 · H：把 `gconvEnabled` 纳入配置指纹，消除半生效中间态**
- 位置：`ConvFilter.kt:1239-1247`（`configStamp` 调用）、`PinFoldLogic.configStamp` 签名（不在本轮 diff 内）。
- 改法：给 `configStamp` 增加 `gconvEnabled` 一项（或翻转 `GCONV_MODE` 时改走 `refreshMembers()` 递增 `membersVersion` 破 `:1269` 短路），使翻转开关触发全量重建，让 layout 形态与热更新的 `gconvEnabled` 字段一致。
- 说明：这只能消除中间态，**组名仍需冷启**（`rcontact.nickname` 内存缓存，背景已定案），SOP 中「至少一次冷启」保留。

### P2（可上机后观察，4 条）

**P2-1 · E：组名缓存进 rebuild 快照** —— `ConvFilter.kt:1230-1231` 已读 `db.listGroups()` 存局部 `groups`，把它（或 `Map<Long,String>`）升为 `@Volatile` 字段，`:1802` 改查内存 Map、去掉每行 `listGroups()`。失效无需额外逻辑：`configStamp` 已含 `groups.hashCode()`（`:1245`）。

**P2-2 · G：显式化互斥 + 修注释 + 回滚双键复位** —— 在 `ConvFilter.kt:1218-1221` 引入 `val effEnabled = enabled && !gconvEnabled`，让 `:1241` 的指纹与 `:1282` 的 light 路径判据用它（消掉 O(组数×2900) 空跑）；把 `Keys.kt:67-68`「两套分组行会同时出现」改为与 `:1314` 实现一致的表述；验收/回滚文档写明 `chat.gconv_enabled` 与 `chat.group_row_enabled` **两个键必须一起复位**。

**P2-3 · F 的非逐字节差异** —— `ConvFilter.kt:1221` 使每次 rebuild 多一次 `Kv.getBool(GCONV_MODE)` 查库（`Kv.kt:28-31` → `GroupDb.kt:917-923`），最多每 250ms 一次。量级可忽略，仅记录，不需改。

**P2-4 · D 的窄口径固有限制** —— `GroupConvPlan.groupIdOf`（`GroupConvPlan.kt:30-35`）会把任何 `wxgrp_` + 纯合法 hex 的行判为我方行；真实会话不会落在该命名空间，但上机前用 `op=gconv sub=stat` 确认探针残留行命名（如 `wxgrp_t1` 含非 hex 字符，可正确放行）即可。

### 通过（无需修改）
- **D**：passthrough 下 `r == position` 确证（`ConvLayout.kt:86`、`:213-214`、`:233-234`）；`ConvReader.username` 能取到 username、null/失败回落空串后保守放行（`ConvReader.kt:42-46`、`GroupConvPlan.kt:30-35`）。
- **F**：默认 false 时 `|| false` 等价、`AntiRevoke.wxDb()` 因 `&&` 短路完全不调、`getItemViewType`/`getViewTypeCount`/`configStamp` 未被 diff 改动 ⇒ 回滚生命线成立。

## ④ 实际读过的文件清单
只读复核，未修改任何 `app/src/main` 文件。实际读过：

**diff / 变更面**
- `git diff -- wxgroup/app/src/main/java/com/lx/wxgroup/data/Keys.kt wxgroup/app/src/main/java/com/lx/wxgroup/feature/ConvFilter.kt`（全文）
- `git diff -- wxgroup/app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt`（全文）
- `git diff --stat`

**源码（全文）**
- `wxgroup/app/src/main/java/com/lx/wxgroup/feature/GroupConvStore.kt`（1-369）
- `wxgroup/app/src/main/java/com/lx/wxgroup/data/GroupConvPlan.kt`（1-132）
- `wxgroup/app/src/main/java/com/lx/wxgroup/data/ConvLayout.kt`（1-253）
- `wxgroup/app/src/main/java/com/lx/wxgroup/core/ConvReader.kt`（1-132）
- `wxgroup/app/src/main/java/com/lx/wxgroup/data/GroupDb.kt`（1-1021）

**源码（区段 / 检索）**
- `wxgroup/app/src/main/java/com/lx/wxgroup/feature/ConvFilter.kt`：`:300-370`、`:620-705`、`:789-915`、`:1201-1326`、`:1425-1525`、`:1620-1850`、`:1865-1895`；另全文件 Grep `configStamp|install|refresh|onDataChanged|rebuild|getItemViewType|getViewTypeCount|mainHandler|\benabled\b` 等定位
- `wxgroup/app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt`：`:185-240`
- `wxgroup/app/src/main/java/com/lx/wxgroup/data/Keys.kt`：`:54-78`
- `wxgroup/app/src/main/java/com/lx/wxgroup/data/Kv.kt`：`:20-70`
- `wxgroup/app/src/main/java/com/lx/wxgroup/feature/AntiRevoke.kt`：`:814-836` 及 `wxDb`/`msgDb` 全文件检索
- 全 `app/src/main` Grep：`GroupConvStore.sync|GroupConvStore.clean|GCONV_MODE|GCONV_SYNC_THROTTLE_MS|lastGconvSyncMs`（确认 sync 仅两个调用点）

**结论库 / 设计文档**
- `docs/conclusions/conv_list.md`（全文 1-95）
- `docs/_r54_gconv_design.md`：`:288-305`（§6.7 写库竞争）及 `并发|线程|锁|事务|主线程|串行|掉帧` 全文件检索

**未读 / 未验证**：`PinFoldLogic.configStamp` 实现体、`GroupConvProbe` 内部 SQL、`AntiRevoke` hook 主体、任何 dex/微信宿主字节码；未连设备、未跑 adb、未跑构建。凡涉及宿主运行行为的判断均已标注「证据不足，需真机验证」。

[DONE]
