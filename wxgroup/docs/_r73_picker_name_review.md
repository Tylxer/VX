# r73 转发选人器昵称刷新 —— 独立对抗复核

> 视角：只读、不改码、不碰设备 / adb / gradle / commit。宿主微信 8.0.48（2588）。
> 复核对象：`docs/_r73_picker_name_refresh.md`「## 实现」小节及其落盘代码。
> 行号均为读取时快照，路径前缀 `app/src/main/java/com/lx/wxgroup/`。

---

## 0. 裁决速览

**裁决：可装机**（无 P0）。

- 6 个复核点逐条结论：点 1/3/4/5 **通过**，点 2 为 **P1 需真机实测**（不阻断装机），点 6 **基本有效但有局限**。
- 真机铁证场景（好友 `wxid_xlrbelf6hv0w22`：rcontact=`路过人间`、本地缓存=`verity`）在代码层面可被本次刷新修复：交集内、type 保留、`select` 的 4 个列在本机 8.0.48 上均被同文件既有代码证明可读。
- 未发现「把 rcontact 非好友写进缓存」「把群聊串改成好友」「删除本地已有行」等数据完整性问题。
- 两条需主会话真机确认的非 P0 风险：① 主线程同步刷新耗时（`docs/...` 自称 `<100ms` 无基准数据支撑）；② 冷启后几秒内打开选人器可能句柄未捕获、本次仍显示旧值（再开一次恢复）。

---

## 1. 复核点 1：空列覆盖风险（P0 候选）

**结论：不构成 P0 回归。不需要在写回前加「新值全空则跳过该行」守卫（但加算是廉价加固，非阻断）。**

### 1.1 写回机制复核
- `textAt()`（`ContactSqlSource.kt:309-312`）：列下标 `<0` → `""`；`getString` 抛异常/返回 null → `""`。确认会把「列缺失」和「值为空」两种不同情况**都**折叠成 `""`。
- 写回（`ContactSqlSource.kt:321-327`）：对交集内每一行无条件组装 `Friend` 写库，确实**没有**「新值全空则跳过」判断。`upsertFriends` 是 `CONFLICT_REPLACE` 整行替换（`GroupDb.kt:789-791`），所以空值会覆盖旧值。

### 1.2 为什么不构成"昵称反而丢失"的回归（关键证据链）
1. **三个名字字段的来源同一**：本地 friend_cache 的三列全部来自 rcontact 的同名列——`ContactSync` 经 `ContactReader.read()` 读 h4 对象，而 `Anchors848.kt:39-42` 明确映射 `field_nickname/field_conRemark/field_alias` → `nickname/conRemark/alias`；`doSupplement`（`ContactSqlSource.kt:419-424`）和 `ChatroomSource.readChatrooms`（`ChatroomSource.kt:383-392`）也是直接读 rcontact 这三列。**没有任何一路缓存名字来自 rcontact 之外的源**（全库仅 3 个 upsertFriends 调用点：`ChatroomSource.kt:214`、`ContactSqlSource.kt:337/442`、`ContactSync.kt:189`）。
   ⇒ 因此「本地缓存有有效展示名、而 rcontact 三列全空」在真实数据里不会出现：rcontact 全空则缓存当初也无从得到该名；若 rcontact 现在全空，那是权威值的真实状态，覆盖是corrective 而非丢失。
2. **部分列空时 `Friend.displayName` 兜底链仍在**（`Friend.kt:19-23`）：备注 > 昵称 > 微信号 > wxid。
   - nickname 空 + conRemark 有值 → `displayName = remark`，仍有效；
   - nickname/conRemark 都空 + alias 有值 → `displayName = alias`，仍有效；
   - 三列全空 → 退化 wxid。唯一文档记载的三列全空类是**无名群**（`ChatroomSource.kt:247-248/385-387`、`GroupDb.kt:856-857`），而这类行 `ChatroomSource` 从同三列读到的本就是空，缓存侧展示早已是兜底群号，刷新不引入新退化。
3. **本机列可读已证**：`select username,nickname,conRemark,alias` 是固定列清单（`:292`），若某列名不对整条查询抛异常 → `runCatching.getOrNull() ?: continue` 会跳过整批（不会写空，只会不刷新）。但同文件 `who()`（`ContactSqlSource.kt:219-221`）和取证文档（测试文件头 `:13`、`docs/_r73_wxid_in_text_forensics.md:11` 的 `dbg.txt` 铁证 `nickname=路过人间、conRemark 空`）已证明 8.0.48 上这三列存在且可读 ⇒ 固定列清单在本机不会触发整批跳过。

### 1.3 残留的次要风险（P2，非阻断）
- `textAt()` 把「`getString` 瞬时反射失败」折叠成 `""`，若某一批 nickname 列反射偶发失败，该批好友 nickname 会被写空。但同一 `getString` 反射模式在 `cText()`（`:480-485`，被 `who()`/`doSupplement` 长期真机使用）已验证稳定，风险低。
- 建议（可选加固）：写回前判 `if (nick.isBlank() && rem.isBlank() && ali.isBlank()) continue`，可同时消掉上面这个瞬时失败写空和跨版本列缺失的尾部风险，成本一行。**不作为打回条件。**
- **证据不足标注**：项目内**未找到**「微信 rcontact 中 nickname 为空好友的占比」统计样本（只有无名群 `nickname=''` 的定性记载）。但因 1.2 的来源同一性论证，占比多少不影响本结论。

---

## 2. 复核点 2：同步刷新主线程卡顿（P0 候选）

**结论：不构成 P0（不会 ANR），但 `<100ms` 是无基准数据的假设，列为 P1 需真机实测。**

### 2.1 调用栈确认在主线程
- `ForwardPicker.build()`（`ForwardPicker.kt:91`）由 `Panel.render()`（`Panel.kt:197-207`，`b.addView(page.build(c))`）调用，属 Dialog/View 构建路径，运行在主线程。
- 刷新在 `loadTabs()`（`ForwardPicker.kt:193`）同步执行；`ForwardPicker` 每次转发都 new 一个新实例（`ForceForward.kt:1166`），故**每次打开选人器都会跑一次**（不是全进程一次）。`tabs` 只是单实例内复用，不影响跨打开频次。

### 2.2 量级估算（标注为估算，非实测）
- 批次数：`local = groupDb.allFriends()`（`ContactSqlSource.kt:272`）取的是 friend_cache **全表**（含约 2245 好友 + 841 群聊 + 254 @openim 等，见 `docs/...:68/70/249`），`chunked(400)`（`:288`）实际约 **8~9 批**，比 `docs/...:254` 宣称的「约 6 批（按 2245 好友算）」偏多。
- 反射次数：每批 4 次 `getColumnIndex`（`:305-307`）+ 每行 `moveToNext` 1 次（`:315`）+ `getString` 4 次（`:323-325`）≈ **5 × 3300 ≈ 1.6 万次** `XposedHelpers.callMethod`（实现/KDoc 只算了「4 次 getString」，漏算 moveToNext）。`XposedHelpers.callMethod` 内部有方法缓存，单次通常 microseconds 级。
- SQL：每批 `where username in ('...'×400)` 命中主键索引，单批毫秒级。另 `allFriends()` 在刷新内（`:272`）和 `loadTabs`（`ForwardPicker.kt:197`）各读一次全表。
- 合计量级估计 **几十 ms ~ 低百 ms**。此为推算，仓库内**无任何真机计时证据**（全库 grep 仅 `docs/...:254` 的「预期 <100ms」自称，无日志/基准）。

### 2.3 判定与最小缓解
- ANR 阈值为输入事件 5s，即使估算翻倍（~200~300ms）也**远达不到 ANR**；最坏是首开有 1~十几帧的卡顿/掉帧，属可感知但不致命。故不列 P0。
- `<100ms` 无依据 → **要求主会话真机打开选人器计时**（日志可加 `SystemClock.elapsedRealtime()` 包住 `refreshFriendNames()` 打点，属验证动作非本次改动）。
- 若真机超标，最小缓解（均不触碰 Tab 重建复杂度）：① 加进程级短冷却（如 30~60s），避免短时间内反复打开反复刷；② 只在 `mainDb` 句柄就绪后首次打开刷一次并用时间戳记住；③ 若接受异步，走 `docs/...:160-161` 的「放进 ChatroomSource 后台线程 + 独立短冷却」，但需处理页已退出。当前同步方案作为最小改动成立。

---

## 3. 复核点 3：交集与类型保留正确性

**结论：通过。三项子问题均确认正确。**

### 3.1 不会把群聊改成好友
- `typeByWxid` 取本地原 type（`ContactSqlSource.kt:276-277`，`f.type` 来自 `allFriends()` 的 `wxid_type`），写回 `type = type`（`:326`）。`ChatroomSource` 写入的群聊行 type=CHATROOM=2（`GroupDb.kt:1001`），刷新保持 2，不会变 FRIEND=0。逻辑复刻单测 `typeKeptFromLocalCache` + 源码断言 `refreshMethodPreservesType` 覆盖了这条。

### 3.2 双重交集能防非好友入库
- SQL `where username in (本地 wxid)`（`:292`）把 rcontact 收敛到本地 wxid；Kotlin 侧 `typeByWxid[wxid] ?: continue`（`:320`）再兜一次。写回集合只包含本地已存在的 wxid ⇒ **不会新增任何 rcontact 非好友行**（公众号/陌生人/群成员）。注意：`HashMap<String,Int>` 的 value=0（FRIEND）不为 null，Elvis 只在 key 缺失时触发，故真好友 0 值不会被误跳过——这点正确。
- ⚠️ 澄清：刷新**会**覆盖本地已有的非 FRIEND 行（群聊/公众号），因为 `allFriends()` 含全类型。但这是"更新已有行的名字"，不是"把非好友写进缓存"，且 type 保留、来源同为 rcontact，无副作用。

### 3.3 只写交集 + CONFLICT_REPLACE 不会删本地独有行
- `upsertFriends`（`GroupDb.kt:773-800`）只有 `insertWithOnConflict(CONFLICT_REPLACE)`，**没有 delete**；本地有而 rcontact 没有的行（如已删好友残留）不会被删除。`replaceFriends()`（`:803-826`）才有 delete 全表，但本次未调用。安全。

---

## 4. 复核点 4：句柄时序

**结论：通过但有一个窄边角，可接受，不需本轮处理。**

- 捕获时机：`ContactSqlSource.install()` 在 `ContactSync.init()` 的 bootstrap/attach 阶段安装（`ContactSync.kt:51-57`），早于微信任何 rcontact 查询；句柄 `mainDb` 在微信首次以「SQL 含 rcontact + getPath 以 EnMicroMsg.db 结尾」查询时写入并卸载钩子（`ContactSqlSource.kt:112-167`），`docs/...:38/187` 记载真机冷启约 3 秒捕获。
- 边界场景：若微信刚冷启（<数秒）就立刻打开转发选人器，`mainDb` 可能尚未捕获 → `refreshFriendNames()` 在 `:263-266` 静默返回 0 → 本次仍显示旧昵称。因每次打开都 new 实例重跑（`ForceForward.kt:1166`），**再开一次即恢复**。
- 判定：窗口仅冷启后数秒且需"恰好立刻转发"，概率低、可自愈；不阻断。若要消除，可在 `ready()==false` 时延迟重试或提示，但属超范围改动。
- 证据充分度：捕获时机为代码 + 文档双证；「冷启约 3 秒捕获」为文档记载（`docs/...:187`），非本轮实测。

---

## 5. 复核点 5：红线 git diff 核

**结论：全部通过，红线未破。**

| 红线项 | 核对方法 | 结果 |
|---|---|---|
| `Friend.displayName` | `git diff -- .../data/Friend.kt` | 无差异，未动 |
| `GroupDb` 建表/索引/其它查询 | `git diff -- .../data/GroupDb.kt` | 无差异，未动 |
| `ContactSync` 三道节流 | `git diff -- .../core/ContactSync.kt` | 无差异，未动 |
| `ForwardPicker` Tab 结构/搜索/选中集/Result 语义 | `git diff -- .../feature/ForwardPicker.kt` | 仅 +11 行：1 行 import + 10 行同步调用（`loadTabs` 内 `ChatroomSource.runIfNeeded()` 之后、`db.allFriends()` 之前）。`Result`/`tabRow`/`filter`/`toggle`/`selected` 等均未改 |
| 其它 picker（PickerPage/BatchPicker/GroupPicker/PickerTabs） | `git diff --stat` 各文件 | 全部无差异，未动 |
| `ContactFilter`/`Anchors848` 等判据 | `git diff --stat` | 无差异，未动 |

- 改动范围：`ContactSqlSource.kt` +116/-0、`ForwardPicker.kt` +11/-0，**纯新增**。新增单测 `R73PickerNameRefreshTest.kt` 为未跟踪新文件。无删除、无改写既有逻辑。
- （注：`git status` 中 HookEntry/EntryReceiver/ForceForward/ForwardLogic/dev.sh 等 M 状态属前序 R70-R73 取证轮次遗留，非本轮昵称刷新改动，本复核不追责。）

---

## 6. 复核点 6：单测有效性

**结论：源码断言层"真锁"，逻辑复刻层"玩具"，整体可用但有明确局限。**

### 6.1 源码断言（后 5 例）—— 对变异真敏感，通过
- 断言直接读生产源码字符串（`R73PickerNameRefreshTest.kt:156-207`），M1~M6 变异均会使其变红：
  - M1 把 `type = type` 改成固定推导 → `refreshMethodPreservesType`（`:181-186`）红（`type = type,` 消失 / 出现 `WxidKind.typeOf`）；
  - M2 去掉交集守卫 → `refreshMethodFiltersByLocalWxids`（`:173-178`）红；
  - M3 改用旧 nickname → `refreshMethodReadsRcontactColumns`（`:157-170`）红；
  - M4/M5 删调用/挪到读缓存后 → `pickerCallsRefreshBeforeReadingCache` 的 `callAt in 0 until readAt`（`:195-196`）红；
  - M6 删 `ChatroomSource.runIfNeeded()` → `pickerKeepsChatroomSupplement`（`:202-206`）红。
- 断言对"调用顺序"和"红线保留"的锁是实打实的，非自证。

### 6.2 逻辑复刻（前 5 例）—— 是玩具，不随生产变异
- `StubFriend/StubRow/mergeRefresh`（`:44-90`）是手写等价副本，**不引用生产代码**。若生产 `mergeRefresh` 对应逻辑被改（如真的把 `type = type` 改成固定值、去掉交集），这 5 个用例仍全绿。文档头 `:26-38` 宣称「M1 ⇒ typeKeptFromLocalCache 变红」是**不成立的**——该用例只测副本，真正兜住 M1 的是源码断言层。
- 判定：这 5 例只能证明"这套算法思路自洽"，不能证明"生产实现正确"。因有源码断言层兜底，整体回归防护仍成立；但不应把 5 例当独立验证计数。

### 6.3 覆盖盲区（需在总评中标注）
- 无任何用例**真正执行生产 `refreshFriendNames()`**（零 Android/SQLite/Xposed 依赖）⇒ 运行时问题（反射失败、SQL 列名不匹配、cursor 处理、`upsertFriends` 事务）全靠真机。
- 无任何用例覆盖本复核点 1（空列覆盖）、点 2（耗时）、点 4（句柄时序）——这些是本轮最需防的回归，却无自动锁。
- 源码断言脆弱（改变量名/格式即红），但作为回归锁可接受。

---

## 7. P0 汇总与真机判据

**P0：无。裁决 = 可装机。**

非阻断待办（交主会话）：
1. **P1 真机计时**：真机打开转发选人器，确认无 >100ms 级可感知卡顿/ANR；若超标按 §2.3 加短冷却或走异步。
2. **P1 句柄时序**：真机冷启后立刻首次打开选人器，确认是否显示旧昵称（句柄未捕获）；如是，再开一次应恢复——验证"可自愈"。
3. **P2 可选加固**：写回前判三列全空则跳过该行（§1.3）。

真机判据（`docs/...:261-263` 一致）：
- 打开转发选人器「好友·群聊」Tab，`wxid_xlrbelf6hv0w22` 应显示 **「路过人间」** 而非 **「verity」**；
- 群聊 Tab 不应出现被串改成好友类型的群（type 保留 CHATROOM）；
- 有备注的好友仍显示备注（displayName 优先级未变）；
- 打开选人器无明显卡顿；冷启即开若偶发旧名，再开一次应为新名。

---

[DONE]

