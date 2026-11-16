# R55 路线 E 修复记录（P0 × 1 + P1 × 4 + P2 × 5）

> 依据：`docs/_r55_impl_review.md`（复核结论 P0-1 / P1-1~P1-4 / P2-1~P2-9）。
> 改动文件：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/GconvPage.kt`（395 → 582 行）、
> `docs/_r55_impl.md`（仅订正 SEC1/SEC9 关于 `groupNames` 的错误描述）。
> 纪律：本轮**只改代码 + 编译**，未 install、未 adb、未写宿主任何表；
> `ConvFilter.kt` / `GroupDetailPage` / `GroupRowMenu` **零改动**（长按逻辑一字未动）。

---

## SEC1 — P0：自建 `EnMicroMsg.db` 句柄捕获

**问题**：`query()` 只用 `AntiRevoke.wxDb()`，而 `msgDb` 只在 `updateWithOnConflict` 且
`table=="message"` 时赋值（`AntiRevoke.kt:515-516`）。冷启没收到消息 ⇒ 恒 null ⇒ 查不到库 ⇒
Hook B 放行 ⇒ 「我们的标题 + 微信原生折叠群聊列表」错位态。

**改法（三处，同一根因）**

1. **自建捕获** `GconvPage.kt:222-275`
   - `install()` 第 4 个 hook（`:204`）：`XposedBridge.hookAllMethods(com.tencent.wcdb.database.SQLiteDatabase, "rawQuery", captureHook)`；
     0 个重载命中时只打 `db capture: no rawQuery overload hooked`，不阻断其它 hook。
   - `captureHook`（`:238-266`）判据**两条都满足**才认领：
     ① `param.args[0] as? String` 含 `rconversation`（`:247`）；
     ② `XposedHelpers.callMethod(db, "getPath") as? String` 以 `EnMicroMsg.db` 结尾（`:250`）。
     `getPath` 抛异常/返回 null 时**降级为只认 ①**并 `LogX.w("db captured path=? (getPath failed) …")`（`:251-255`）。
     命中即写 `@Volatile capDb`、打 `db captured path=…`，并 `unhookCapture()` 卸钩（后续零开销）。
   - 注释里写清了为什么这不是 `lastDb` 式猜测：`rconversation` 只存在于 `EnMicroMsg.db`，
     而这条 SQL 是**宿主自己**发起的 —— 这个实例没有该表，宿主自己就先 `no such table` 了，
     根本走不到我们的 before。两道判据都是「宿主已证明」，不是「最近一次任意表 update」的推断
     （对照 `AntiRevoke.kt:819-826` 的教训）。

2. **句柄来源** `convDb()` `:137`：`capDb ?: AntiRevoke.wxDb()`。
   `query()` `:520-533` 改为用它；两个都 null 时返回 null → `replaceData` 返回 false →
   Hook B 不 `setResult`（放行原生）。**没有**任何「随便找个库」的兜底分支。

3. **认领闸门** `claim()` `:319-324`：解析出 gid 后、`GroupDb.open()` 之前判 `convDb() == null`
   ⇒ `reset()` + `LogX.w("claim: no db, treat as native page (gid=…)")` + `return`（不认领、不改标题）。
   失败方向自洽：整页保持微信原生「折叠的群聊」，不会出现半套状态。

[SEC1 DONE]

---

## SEC2 — P1-1：extra 换哨兵，杜绝宿主回写我方行

**问题**：`superUsername = "wxgrp_<hex>"` 是 `rconversation` 里真实存在的行。
`FmUI.onPause()`（classes4.dex code=0x564a44）→ `s4.b0(superUsername)`（`updateUnreadByTalker`，
code=0x4a4930）：`s4.p(username)` 取到行就 UPDATE（清 `unReadCount/UnReadMuteCount/UnReadInvite/atCount`、
抹 `attrflag` 的 `0x100000`，另一分支经 `s4.Z` 动 `flag`）⇒ 字面违反「零写入」红线。

**改法**

- `SENTINEL = "gconv_view:"`（`:80`），注释写明 `onPause → s4.b0` 的完整链路与为什么要避开 `wxgrp_`。
- `open()` `:156`：`putExtra(EXTRA_USER, SENTINEL + hexOf(gid))`，`hexOf` 复用
  `GroupConvPlan.usernameOf(gid).removePrefix(GroupConvPlan.PREFIX)`（`:176-177`），不自己写 hex。
- `gidOfMark()` `:184-189`：先剥 `SENTINEL`，再 `GroupConvPlan.groupIdOf(GroupConvPlan.PREFIX + hex)`
  复用现有反解；原生页 extra（空 / `"conversationboxservice"`）⇒ null ⇒ 照旧 `reset()` 放行。
- 自证零写入：哨兵不对应任何 `rconversation.username` ⇒ `s4.p(哨兵)` 恒 null ⇒ `b0` 第一条 `if-nez` 直接 return。

[SEC2 DONE]

---

## SEC3 — P1-2：补回被跳过的 `setShowView`

**问题**：原生 `t0.N()`（code=0x5841a4）里有 `listener.a(null)` → `setShowView(count)`，
控制 `emptyTipTv` 与 `appbrandMessageLV` 的可见性；我们整体 `param.result = null` 后这句不执行。

**签名核实过程（dex，classes4.dex，非猜）**

用 `tools/_r12_dexclass.py` 的 `Dex` 解析器（`_cache/dex/classes4.dex`）读 `class_data` + `proto_ids` +
`type_list`，把 `ConvBoxServiceConversationFmUI` 的 16 个 direct + 8 个 virtual 方法全部导出：

```
direct   flags=0x2 code=0x564b44 setShowView(I)V
direct   flags=0x1009 code=0x564694 access$100(ConvBoxServiceConversationFmUI;I)V
virtual  flags=0x1 code=0x578fc8 (i0) a(Ljava/lang/Object;)V
```

- **描述符 `(I)V`**：`proto` 的 `return_type = V`、`parameters = [I]`；**全类只有这一个 `setShowView`，
  没有 boolean 重载**（8 个 virtual 方法里也没有）。
- `flags=0x2` = **private** ⇒ 必须 `setAccessible(true)` 才能调。
- 方法体：4 条 `invoke-virtual View.setVisibility(I)V`，无其它副作用（无字符串、无写库）。
- 调用链实证：`i0.a(Object)` 先 `invoke-virtual w8.getCount()I`，再 `invoke-virtual access$100(FmUI;I)V`，
  后者体只有一条 `invoke-virtual setShowView(I)V` ⇒ **参数就是 adapter 的行数**，与我们传 `rows` 同口径。
  （`w8.getCount()` 的 `A()` 恒 0，见 `_r55_impl_review.md` #4。）

**改法** `showView(rows)` `:467-483`，由 `replaceData()` `:446` 在 `notifyDataSetChanged` 之后调用：

```kotlin
val m = frag.javaClass.getDeclaredMethod("setShowView", Int::class.javaPrimitiveType)
m.isAccessible = true
m.invoke(frag, rows ?: 0)
```

按核实过的描述符精确取方法（不用 `callMethod` 让框架猜重载的装箱），整段 `runCatching`，
失败只打 `hookB: setShowView fail: …`。`frag` 来自 `activeFragment`，为 null 直接跳过；
`rows` 为 null（`getCount` 取不到）时也跳过 —— 传 0 会把正常列表压成空态，比不动更糟。

[SEC3 DONE]

---

## SEC4 — P1-3：游标泄漏（每次 onResume 漏一个）

**问题**：`w8.W(Cursor)`（code=0x598e78）只有 `f = cursor; i = -1`，**不 close 旧游标**；
只有 `w8.k()`（0x598ec0）会 close，而它只被 `d2.p()` 和 `d2.m0()`（onDestroy，只关最后一个）调到。

**改法** `replaceData()` `:433-440`

1. `W` **之前**读 public 字段 `f`：`XposedHelpers.getObjectField(adapter, "f")`（`flags=0x1` 已核实）；
   读失败只打 `adapter.f missing` warn，退化为不关（不崩）。
2. `W(cursor)` 成功之后：`if (old != null && old !== cursor) closeQuietly(old)`。
3. ⛔ 明确不用 `w8.q()`：`f == null` 时 `q()` 会 `L()` → `N()`，递归回自己的 hook（注释已写在代码里）。
4. `reload` 日志加 `oldCursor=none|same|closed` 字段，真机上可直接看出这条路径有没有走到。

[SEC4 DONE]

---

## SEC5 — P1-4：`open()` 失败回退 `GroupDetailPage`

**问题**：`onFailure` 只打日志 ⇒ 换一版微信（类名/清单变化）点击完全无反应，相对 R54 是功能回归。

**改法** `open()` `:163-174`：失败分支 `runCatching { GroupDetailPage.open(context, gid, groupNameOf(gid)) }`，
回退自身再失败就只剩一行 warn。`GroupDetailPage.open(Context?, Long, String)` 签名已读文件确认
（`GroupDetailPage.kt:44`，内部 `mainHandler.post` + `HostContext` 兜底）。
`groupNameOf()` `:193-197` 取 `GroupDb.current()?.listGroups()?.firstOrNull { it.id == gid }?.name`，
取不到退 `GroupConvPlan.usernameOf(gid)`。

[SEC5 DONE]

---

## SEC6 — P2（只做指定的 5 条）

1. **删重复计数**：`countSql()` / `countRows()`（旧 :342-352）与 `moveNext()` 一并删除（后者只被它们用）。
   `claim()` `:353` 只 `applyTitle(act, fragment, name, null)`（标题 = 组名，不留空标题）；
   `(N)` 由 `replaceData()` 的 `cursor.getCount()` 补 ⇒ 同一个数不再查两遍。
   附带收益：Hook B 没接管时标题不会假装有 N。
2. **安装可见性**：`hookA installed` `:295`、`hookB installed` `:413`、`hookC installed` `:505`，
   加 `db capture installed rawQuery xN` `:234`。`claim` 成功日志 `:354`、`reload` 日志 `:451-455`
   （`gid / rows / oldCursor / sqlLen`）保持并补字段。
3. **`applyTitle` 判空** `:362-366`：`act.isFinishing || act.isDestroyed` 直接 return + warn；
   `act` 来自 `activePage.get()`，为 null 时调用方已跳过。
4. **组名兜底** `:193-197`，`claim()` `:337-343`：`raw` 现在是哨兵，**绝不**当组名；
   取不到组名 → `GroupConvPlan.usernameOf(gid)`（`wxgrp_<hex>`，至少是个稳定标识）。
5. **文档订正** `docs/_r55_impl.md` SEC1 表格：删掉「删除 `groupNames`（:1092 / :1261）」这条不存在的改动，
   改为「仓库从来没有该符号；点击块本身是 R54 未提交改动」；SEC9 回滚说明同步改掉「需要恢复 `groupNames`」。

[SEC6 DONE]

---

## SEC7 — 没能确认 / 必须真机验的点

1. **`rawQuery` 是不是宿主取会话列表的那条路**（最可能仍然出错的一处）。
   若 `s4.t()` 最终走的是 `rawQueryWithFactory` / `SQLiteConnection` 而不是
   `SQLiteDatabase.rawQuery`，捕获钩子永不命中 ⇒ `capDb` 恒 null ⇒ 退回 `AntiRevoke.wxDb()`
   （冷启仍 null）⇒ 冷启第一次点分组会**整页保持原生**并打 `claim: no db, treat as native page`。
   判据：冷启后 grep `K7 gpage`，看不到 `db captured path=…EnMicroMsg.db` 就是这条。
2. **`EnMicroMsg.db` 之外的库是否也会查 `rconversation`**：理论上不会（真机 8 个库其余均 no such table），
   但 `getPath` 失败时的降级分支会放宽到只认 SQL —— 真机日志出现 `path=? (getPath failed)` 需复核。
3. **`setShowView` 的方向语义**：dex 只证明它按 count 调 4 次 `setVisibility`、单个 `(I)V` 重载，
   但「哪个 count 值对应空态」是按 `i0.a → getCount` 的语义推出的；真机要验「正常组不出现『暂无群聊』」
   「0 成员组显示空态且不被换回原生列表」。
4. **关旧游标是否会踩到正在渲染的 View**：`W()` 之后立刻 close 旧游标，理论上行数据在
   `convertFrom` 时已拷出；真机要验滚动 + 返回 + onNotifyChange 刷新无 `closed cursor` 异常。
5. **`adapter.f` 字段名**：`flags=0x1 public Cursor f` 已从 dex 核实，但若没读到会静默退化成不关；
   日志出现 `hookB: adapter.f missing` 即命中。
6. 空态文案仍是资源 `2131759120`「暂无群聊」（P2-9 未做，本轮不在范围）。

[SEC7 DONE]

---

## SEC8 — 编译

```
$ cd wxgroup && sh dev.sh build
BUILD SUCCESSFUL in 19s（末次调整后复编 16s，同样 SUCCESSFUL）
```

未 install、未 adb（遵守只 build 的约束）。

[SEC8 DONE]

---

## SEC9 — P0-1：捕获钩子从 `rawQuery` 换到 `rawQueryWithFactory`

**问题**（`_r55_fix_review.md` P0-1）：上一轮的捕获钩子挂在 `SQLiteDatabase.rawQuery`，
而宿主的会话列表查询链 `t0.N → s4.t → z84.z.k → z84.z.g → z84.e.s → rawQueryWithFactory`
**100% 不经过 `rawQuery`**（`rawQuery` 自己才是转发到 `rawQueryWithFactory` 的那一层）
⇒ `capDb` 恒 null ⇒ 冷启点分组必然退化成原生「折叠的群聊」。

**本轮自己复核的补充证据**（`_cache/dex/classes10.dex`，不照抄上一棒）：

| 结论 | 证据 |
|---|---|
| 只有两个重载，且 SQL 都在**第二参** | `rawQueryWithFactory(SQLiteDatabase$CursorFactory, String, Object[], String)` code=0x4bbf48；5 参版（多 `CancellationSignal`）code=0x4bbf70。两版 `params[1]` 都是 `Ljava/lang/String;` |
| 挂基类能覆盖到 | 全 15 个 dex 里 **没有任何类是 `SQLiteDatabase` 的子类**（扫 class_defs 的 superclass），`z84.z` / `z84.e` 也**没有声明** `rawQueryWithFactory` ⇒ 不存在「虚分派到子类覆写」导致钩子失效 |
| 收钩子的实例就是 wcdb 的库 | `Lz84/e;` 的实例字段 `a` / `b` 类型均为 `Lcom/tencent/wcdb/database/SQLiteDatabase;`（classes10）⇒ `param.thisObject` 直接是库实例，可直接 `getPath()` |
| 覆盖面只增不减 | `rawQuery` 的两个重载（0x4bbef8 / 0x4bbf20）方法体都只有几条指令、转发进 5 参 `rawQueryWithFactory` ⇒ 原来挂 `rawQuery` 能看到的所有查询，现在同样能看到 |

**改法**（`feature/GconvPage.kt`）

- `:286` `hookAllMethods(cls, "rawQueryWithFactory", captureHook)`（`hookAllMethods` 覆盖全部重载，
  实装 2 个：`:293` 日志 `db capture installed rawQueryWithFactory x2`）。
  删掉原来那次 `rawQuery` 的 `hookAllMethods`（两处都挂只会让第一发多跑一次判断）。
- `:314` 取 SQL 的索引跟着改：`param.args?.getOrNull(1) as? String ?: return`
  （`args[0]` 是 `CursorFactory`，上一轮 `args[0]` 只对 `rawQuery` 成立）。
- 判据①②、命中即 `unhookCapture()`、`capDb` 的 `@Volatile` 写法**全部保持不变**。
- 4 参版会转发 5 参 ⇒ 命中时可能连进两发：第一发置 `capDb` + 卸钩，第二发看到 `capDb != null`
  直接走卸载分支，不会重复认领、也不会漏。

[SEC9 DONE]

---

## SEC10 — 兜底：复用 `ContactSqlSource.mainDb`（不依赖本钩子）

**为什么**：即使 `rawQueryWithFactory` 这条路将来又变了，`core/ContactSqlSource`（bootstrap 阶段
由 `core/ContactSync.kt:56` 装钩）已经在真机证明**冷启约 3 秒**就能拿到 `EnMicroMsg.db`
（`CSQL EnMicroMsg.db captured via rawQuery`），而 `rconversation` / `rcontact` / `message`
本来就在同一个库里。

**改法**

1. `core/ContactSqlSource.kt:183-191` 新增
   `internal fun mainDbOrNull(): Any? = mainDb`（带 KDoc：给 `GconvPage.convDb()` 当第二级；
   判据是「SQL 含 `rcontact`」+「`getPath()` 以 `EnMicroMsg.db` 结尾」双约束，
   **与 `AntiRevoke.lastDb`（任意表最近一次 update 的库）不是一类东西**）。
2. `GconvPage.convDb()` `:168-171` 改成三级：
   `capDb ?: ContactSqlSource.mainDbOrNull() ?: AntiRevoke.wxDb()`；
   KDoc 里写清三级的可靠性排序和「绝不能退 `lastDb`」的原因。
3. 只读暴露：`mainDbOrNull()` 不触发补读、不改状态，纯 getter。

[SEC10 DONE]

---

## SEC11 — P1-5：`open()` 入口就要判库，兜住「进得去但认领不了」

**问题**：`claim()` 拿不到库会 `reset()` 不认领，但 `startActivity` 是**成功**的 ⇒
用户点分组却跳进一个装着**别人会话**的原生「折叠的群聊」页（标题「折叠的群聊」、
列表是微信自己的折叠集合），比 R54 的「点 → 分组详情页」还差；`open()` 的 `onFailure` 也不会触发。

**改法** `open()` `:194-200`（拿到 mark 之前）：

```kotlin
if (convDb() == null) {
    LogX.w("$TAG open: no db yet, fallback to GroupDetailPage gid=$gid")
    runCatching { GroupDetailPage.open(context, gid, groupNameOf(gid)) }
        .onFailure { LogX.w("$TAG open fallback failed: ${it.javaClass.simpleName}") }
    return
}
```

- `GroupDetailPage.open(Context?, Long, String)` 存在（`GroupDetailPage.kt:44`）。
- `groupNameOf()`（`:237-241`）只返回组名或 `wxgrp_<hex>`，**不会**把哨兵串当组名。
- 最坏情况 = 回到 R54 的入口，而不是「跳到别人的列表」。

[SEC11 DONE]

---

## SEC12 — 加固 4 条（只做指定的 4 条）

1. **认输上限**（`GconvPage.kt:71-79,146-153,304-308`）：照 `ContactSqlSource` 的
   `CAPTURE_MAX_CALLS` + `gaveUp` 做法加 `capCalls: AtomicInteger`、
   `capGaveUp`、上限 `CAPTURE_MAX_CALLS = 3_000`；到顶就 `unhookCapture()` + 打
   `db capture gave up after 3000 calls (rconversation 未命中)`。
   **为什么不是 300**：`rawQueryWithFactory` 是全进程所有查询的唯一落点，启动期几百上千发是常态，
   而「主界面首次会话列表查询」可能排在几百发之后 ⇒ 300 会在真正那一发**之前**认输，
   P0 又回到原点。3000 盖得住启动期，又能在「换版本后判据永不命中」时把钩子从热路径摘掉。
2. **判据与注释一致**（`:318-327`）：`getPath()` 失败时**不再认领**，只打
   `db capture: getPath failed on <cls>, skip sql=<前 60 字>` 后 `return`。
   注释写明理由：宁可走 `ContactSqlSource.mainDb` 那条已验证的路，也不要一个可能认错的库
   （别的库里恰好有同名 `rconversation` 就会把别人的数据当分组数据画出来）。
   另加一条**只打一次**的 `path mismatch path=…`（`capPathWarned`），避免热路径刷日志。
3. **adapter 取不到就不认领**（`:403-413`）：在 `convDb()` 闸门之后、查成员之前取
   `fragment.adapter`；为 null 就 `reset()` + `return` + `adapter missing, treat as native page`。
   因为整段 `reset()` 在 `applyTitle()` 之前返回 ⇒ **这种情况下不会 `setMMTitle`**，
   不会出现「我们的组名配原生内容」。后续 `activeAdapter = WeakReference(adapter)` 复用同一个对象。
4. **日志把失败原因拆开**：`query()` `:640-658` 分成
   `query fail: no db` / `no rawQuery method on <cls>` / `rawQuery threw: <异常>: <cause 的 message>`
   （`Method.invoke` 的包装异常取 `cause`）；`replaceData()` `:511` 的 `w8.W` 找不到改成
   `reload fail: W not found on <cls>`。真机上按一行就能定位是哪一级断了。

[SEC12 DONE]

---

## SEC13 — `w8.g`（按 position 的 item 缓存）：核实结论 **不做**

**问题**：复核 S-6 担心 `replaceData` 只调 `W(cursor)`（`f=cursor; i=-1`，不清 `g`），
可能让行内容不跟着新游标变。

**dex 核实**（`_cache/dex/classes4.dex`，`dexdump -d` 逐指令读）：

| 问题 | 结论 |
|---|---|
| ① 字段存在且是缓存？ | **是**。`Lcom/tencent/mm/ui/w8;` 实例字段 `g : Ljava/util/Map; flags=0x1`；`getItem(I)` 0x598d04 在 `0x000b` 读 `g`、`0x0015` `HashMap.get(Integer.valueOf(pos))`、命中即 `return`；未命中才 `q()`→`moveToPosition`→`n()`→`0x0049` `HashMap.put(pos, item)` ⇒ 纯粹是 position→item 缓存 |
| ② `W` / `k` 会自己清吗？ | **`W` 不清**（0x598e78 只有 `f=cursor; i=-1`）；**`k` 会清**（0x598ec0 `0x0000 iget g` → `0x0004 Map.clear()` → 再 `f.close()` → `i=-1`）。`k` 的调用方是 `p()`（0x598f0c），而 `p()` 就是 `onNotifyChange` 的全部实现 ⇒ 原生「收到库变更」的刷新路径**会**清 |
| ③ 需要清 / 能安全 clear 吗？ | **不需要，所以不做**。`g` 在 `w8.<init>` 里被赋 **null**，唯一会把它 new 出来的是 `Q(Z)`（0x598e34）的 `Q(true)` 分支；全 classes4 里 `Q` 只有 7 处调用，**没有一处在 `d2` / `t0` / 会话列表这条路上**（分别在 `ChatroomContactUI`、`CardIndexUI`、`card/ui/k`、`card/ui/s3`、`q91/k`、`s03/a`、`dp0/l5`），`d2.<init>`（0x5743e8）也不调 `Q`。⇒ 我们这个 adapter 实例的 `g` **始终为 null**，`getItem` 每次走 `0x002e` 的「无缓存」分支（`n(d, q())`，不 put）⇒ 不存在「按 position 的旧 r4」这回事 |

**顺带确认（以后换版本若改成 `Q(true)` 也不会崩）**：`clear()` 只是让 `getItem` 回到
「未命中 → 从游标重新 convert」的分支，与原生 `k()` 的处理等价；`g` 判空 + `runCatching` 后
`Map.clear()` 是安全的。但**本轮不加**：当前版本它恒 null，加了纯属无效反射，
而且会让人误以为这里有个必须维护的缓存契约。

[SEC13 DONE]

---

## SEC14 — 编译

```
$ cd wxgroup && sh dev.sh build
BUILD SUCCESSFUL in 17s            # 改完后复编 8s（up-to-date，产物 21:04 已更新）
$ ls -la app/build/outputs/apk/debug/app-debug.apk
3611761  Nov 16 21:04
```

未 install、未跑任何 adb 命令（遵守只 build 的约束）。

[SEC14 DONE]

---

## SEC15 — `claim()` 主动触发一次数据替换（真机确认 Hook B 没被调到）

**真机日志（21:09 冷启 → 21:10 点分组，装的是 SEC14 那一版）**：

```
21:09:39.873 K7 gpage hookA installed
21:09:39.874 K7 gpage hookB installed
21:09:39.876 K7 gpage hookC installed
21:09:39.878 K7 gpage db capture installed rawQueryWithFactory x2
21:09:40.851 K7 gpage db captured path=.../EnMicroMsg.db     ← P0 已修好（1 秒内捕获）
21:10:35.129 K7 gpage open gid=153 mark=gconv_view:0000000000000099
21:10:35.372 K7 gpage claim gid=153 name=123 members=1       ← claim 成功
（没有 reload 那一行 ⇒ replaceData 一次都没跑）
```

界面：进了对的页（`topResumedActivity = ConvBoxServiceConversationUI`）、标题已改成 `123`，
主体仍是微信原生空态「暂无群聊」⇒ **标题生效、数据未替换**。

**根因**（dex 已核实，不再怀疑）：

1. `t0` 的虚方法表是 `<init>` / `N`（code=0x5841a4）/ `a0`；`w8.N` 与 `w8.L` 是
   **abstract**（flags=0x401，NO-CODE）。`onResume`（0x564ac0）里的调用是
   `invoke-virtual Lcom/tencent/mm/ui/w8;->N`，运行时虚分派落到 `t0.N`
   ⇒ **钩子点挂在 `t0.N` 是对的**，问题不在挂载点。
2. `initView`（0x5646ac）的 invokes 里有 `Lcom/tencent/mm/ui/w8;->getCount`，
   而 `w8.getCount` 在缓存失效时走 `q()` → `L()` → `N()` ⇒ **`N()` 在 `initView` 里就被调过一次**，
   而 `initView` 发生在 `onActivityCreated` 内部、**早于**我们的 after-hook `claim()`
   ⇒ 那一次 `activeGid` 还是 0，Hook B 按设计放行。之后 `onResume` 是否再调一次 `N()`
   受 `isFirstOnResume` 影响（静态分不出），**实测没再调**。
3. 结论：不能只依赖 `t0.N()` 被调用来装数据。

**改法**（`feature/GconvPage.kt:449-456`）：在 `claim()` 里 `applyTitle(act, fragment, name, null)`
**之后**（此时 `activeMembers` / `activeName` / `activePage` / `activeFragment` / `activeAdapter`
全部就绪），直接调一次 `replaceData(adapter)`：

- 参数用 `claim()` 里刚反射取到的那个 `adapter` 实例，**不重新取字段**；
- 原 `applyTitle(..., null)` 保留作 reload 失败时的兜底，但它排在 reload **之前**，
  `replaceData` 末尾的 `applyTitle(..., rows)` 会把标题补成 `组名 (N)`，不会退化成无行数；
- ⛔ **不**反射调 `adapter.N()`（会绕回我们自己的 Hook B，语义绕且多一层）；
- ⛔ **不动 Hook B**：从聊天页返回等后续刷新仍要靠它接管。

## SEC16 — Hook B 三条出路的诊断日志

`t0.N()` 的 before 回调原先只有「替换成功」（`replaceData` 内的 `reload` 行）有日志，
另两条出路（gid<=0 放行 / adapter 不匹配放行）是静默的，真机上分不清到底走到哪
⇒ 本轮那次「没有 reload 行」就无法区分「N 没被调」与「被调了但实例比对失败」。

改法（`feature/GconvPage.kt:503-509`），顺序保持「先判 gid、再判实例、日志在判完之后」：

```kotlin
if (activeGid <= 0L) return                       // 保持静默（太吵）
if (param.thisObject !== activeAdapter.get()) {
    LogX.w("$TAG hookB skip: adapter mismatch gid=$activeGid")
    return
}
LogX.i("$TAG hookB fire gid=$activeGid")
```

原有 `replaceData` 调用与 `param.result = null` 的语义未变。

**装包后（冷启 → 点分组）应看到的新日志顺序**：

```
K7 gpage claim gid=153 name=123 members=1
K7 gpage reload gid=153 rows=1 oldCursor=none sqlLen=...
K7 gpage claim direct reload=true gid=153
```

若数据仍空，按下列分支判：

| 看到 | 含义 |
|---|---|
| `claim direct reload=false` 且之前有 `query fail:` / `reload fail: W not found` | 库或 W 方法问题（SEC15 这条路径已跑到） |
| `claim direct reload=true` 但界面仍空 | 数据已进 adapter ⇒ 看 `setShowView` / `notifyDataSetChanged` 是否被宿主覆盖 |
| 出现 `hookB fire` | 说明 `onResume` 之后确实又调了 `N()`（本机没有） |
| 出现 `hookB skip: adapter mismatch` | 别家的 adapter 实例，正常放行 |

**红线核对**：本轮两处改动均无 `insert/update/delete`；未动 `ConvFilter.kt`、长按逻辑、
`GroupDetailPage` / `GroupRowMenu`、`ContactSqlSource.mainDbOrNull()` / `convDb()` 三级取值；
hook 体内所有调用仍在 `runCatching` 中，异常只打日志、不阻断宿主。

## SEC17 — 编译

```
$ cd wxgroup && sh dev.sh build
BUILD SUCCESSFUL in 17s
$ ls -la app/build/outputs/apk/debug/app-debug.apk
3612214  Nov 16 21:14
```

未 install、未跑任何 adb 命令。

[SEC15 DONE]
[SEC16 DONE]
[SEC17 DONE]

[DONE]
