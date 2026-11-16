# R55 路线 E 实现复核（lane: general-purpose-1，只读）

> 复核对象：`feature/GconvPage.kt`（新增 395 行）+ `ConvFilter.kt` 点击改动 + `HookEntry.kt:196` 注册。
> 静态依据：本轮亲自反汇编 `_cache/mm.apk`（classes4.dex）复核，不照抄 `docs/_r55b_convbox.md`；
> 每条可疑结论都标了 code 偏移，可复现。
> 纪律：本轮**未改任何 `app/src/main` 文件、未跑 adb、未 install**。

---

## 结论

**改完再上。** 方向没问题（路线 E 的数据源/标题/生命周期三条链路都能挂住，adapter 字段名与时机也已实证），
但 **P0 一条**：`AntiRevoke.wxDb()` 在冷启后是 null，会让页面出现「我们的标题 + 微信原生折叠群聊列表」的错位态；
另有 4 条 P1 建议一起改（其中「extra 触发宿主回写我们自己的 rconversation 行」直接压到零写入红线上）。

[SEC1 DONE]

---

## 必改项 · P0

### P0-1 `AntiRevoke.wxDb()` 冷启必为 null ⇒ 「我们的标题 + 原生列表」

**位置**：`feature/GconvPage.kt:361-373`（`query()`）、`:253-256`（`replaceData()` 首行）、`:193-194`（claim 里无条件上标题）

**链已核实，成立**：

1. `AntiRevoke.kt:515-516`：`msgDb` **只在 `table == TABLE_MESSAGE` 时**捕获（`guard()` 是 `updateWithOnConflict` 的 before）。
   `TABLE_CONV = "rconversation"` 走 `:512` 的表名过滤后**不赋值** `msgDb`（只赋 `lastDb`，见 `:504`）。
2. 唯一的第二条捕获路径 `AntiRevokeHint.kt:206-212` 也只在「SQL 是 message 查询」时捕获
   （`SqlHideCond.isMessageSelect`）—— 聊天列表页不查 `message` 表，冷启打开列表时不会触发。
3. 于是：**冷启 → 一条消息没来过 / 没进过任何聊天 → `wxDb()` 恒 null**（`AntiRevoke.kt:828` 只 `return msgDb`，
   且 `:819-826` 明确禁止退到 `lastDb`）。
4. `GconvPage.kt:362` `?: return null` ⇒ `replaceData` `:256` `return false` ⇒ `:244` 不 `setResult`
   ⇒ Hook B 整体放行 ⇒ `t0.N` 的 `s4.t(5,null,"conversationboxservice",-1)` 照跑 ⇒ 列表 = **微信原生「折叠的群聊」**。
5. 但 `claim()` 在 `:193-194` 已经 `applyTitle`（`rows == null` ⇒ 标题只有组名、没有 `(N)`）⇒
   **标题是我们的、列表是微信的**，即用户看到的错位态。

这不是理论风险：gconv 的伪会话行是**上一次进程**写进库里的（持久化），冷启后照样渲染，
所以「行在、库句柄不在」是**每天早上第一次点分组**的常态路径，不是边角。
（同一根因也影响 `GroupConvStore.sync()/stat()`，那两条只是数据不新鲜，不产生错位 UI。）

**改法（两处，都要做）**

**A. 自己捕获 EnMicroMsg.db 句柄（推荐，主路径）** —— 抄 `core/ContactSqlSource.kt:101-133,145-167` 的成熟套路，
把过滤条件从 `sql.contains("rcontact")` 换成 `rconversation`：

```kotlin
// GconvPage.kt 顶部
private const val WCDB = "com.tencent.wcdb.database.SQLiteDatabase"
private const val DB_MARK = "EnMicroMsg.db"

/** 只在抓不到时用一次：一旦拿到即卸载钩子 */
@Volatile private var convDb: Any? = null
private val capUnhooks = java.util.Collections.synchronizedList(ArrayList<XC_MethodHook.Unhook>())

/** 查询句柄：优先自己抓到的（来源确定），退回 AntiRevoke 的消息库（同文件，见下） */
private fun db(): Any? = convDb ?: AntiRevoke.wxDb()

private fun installDbCapture(lpparam: XC_LoadPackage.LoadPackageParam) {
    if (convDb != null) return
    val cls = runCatching { XposedHelpers.findClass(WCDB, lpparam.classLoader) }.getOrNull() ?: return
    val set = runCatching { XposedBridge.hookAllMethods(cls, "rawQuery", captureHook) }.getOrNull()
    if (!set.isNullOrEmpty()) capUnhooks.addAll(set)   // 一个都没挂上也别打 error，走 AntiRevoke 兜底
}

private val captureHook = object : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        runCatching {
            if (convDb != null) { unhookCapture(); return }
            val sql = param.args?.getOrNull(0) as? String ?: return
            // ① 只认「宿主自己在查 rconversation」的这一发
            if (!sql.contains("rconversation")) return
            val db = param.thisObject ?: return
            // ② 再认文件：rconversation 只存在于 EnMicroMsg.db（另 7 个库都是 no such table）
            val p = XposedHelpers.callMethod(db, "getPath") as? String ?: return
            if (!p.endsWith(DB_MARK)) return
            convDb = db
            LogX.i("$TAG conv db captured path=$p")
            unhookCapture()
        }
    }
}
```

**为什么这个库一定是 EnMicroMsg.db（回答 `AntiRevoke.kt:819-826` 的教训）**：
`rawQuery` 是**宿主自己**发起的、SQL 里**已经含 `rconversation`** 的那一发 —— 如果这个实例没有这张表，
宿主这条 SQL 自己就会 `no such table`，不可能跑到我们的 before 里来。再叠一道 `getPath().endsWith("EnMicroMsg.db")`
把「别的库里恰好也有同名表」这种不可能情形排除。**两道判据都是「宿主已证明」而不是「我们猜」**，
所以不属于 `lastDb` 那种「最近一次任意表 update 的库」的猜测路径。
（`AntiRevoke.wxDb()` 仍保留为兜底：它给的是 message 表所在的库，`rconversation` 与 `message` 同文件 ——
`GroupConvStore.sync()` 真机就是用这个句柄写 `rconversation`/`rcontact` 成功的，所以它不是「错库」。）

**B. 拿不到句柄就别冒充自己的页（防守）**

```kotlin
// claim()：在读完 members/name 之前先判句柄
if (db() == null) {
    reset()
    LogX.w("$TAG claim: conv db not ready, keep native page (gid=$gid)")
    return          // ⛔ 不改标题：宁可整页是原生「折叠的群聊」，也不要半套
}
```

理由是失败方向必须**自洽**：现在的行为是「标题我们的、列表微信的」，用户会以为分组数据错了；
不改标题时最坏只是「点了分组进了一个叫折叠的群聊的页」，一眼就知道是功能没生效，且不动任何数据。

**C（可选但建议）：`open()` 在句柄未就绪时退回 `GroupDetailPage`**

```kotlin
fun open(context: Context?, gid: Long) {
    if (context == null) return
    if (db() == null) {
        val n = GroupDb.current()?.listGroups()?.firstOrNull { it.id == gid }?.name ?: "分组"
        LogX.w("$TAG open: conv db not ready, fallback GroupDetailPage gid=$gid")
        GroupDetailPage.open(context, gid, n)
        return
    }
    ...
}
```

这样冷启第一次点分组仍有可用的成员列表（旧行为），P0 就从「错位 UI」降级成「入口降级」。

**验收判据**：冷启后第一条日志必须是 `K7 gpage conv db captured path=/data/user/0/com.tencent.mm/MicroMsg/<hash>/EnMicroMsg.db`，
且点分组后紧跟 `K7 gpage reload gid=… rows=…`。

[SEC2 DONE]

---

## 必改项 · P1

### P1-1 `Contact_User = "wxgrp_<hex>"` 会让宿主**回写我们自己的 rconversation 行**（压到零写入红线）

**位置**：`feature/GconvPage.kt:104-108`（`usernameOf(gid)` 当 extra）

**证据（本轮新发现，`_r55b` 未覆盖）**：`ConvBoxServiceConversationFmUI.onPause()` @ classes4.dex `code=0x564a44`

```
0008-000c  vj0.h8.b().v() -> s4
0010       iget-object v1, this.superUsername
0014       s4.b0(String)              // ★ "updateUnreadByTalker"
```

`s4.b0(String)` @ `code=0x4a4930` 是**真写库**（先 `s4.p(username)` 取行，取不到就直接 return，零写入）：

```
001f  if-nez v2 -> 直接 return
0023  s4.i(r4,false)                     // 只改内存对象（parentRef 归一化）
0035  r4.K0() 判空分两条分支，两条都落到一次 UPDATE：
  A(0x0047-0x0076): s4.Z(username, 0x00100000, false, r4.g0())   // flag 位操作 + 落库
                    ContentValues{parentRef, atCount, UnReadInvite}
                    k74.i0.c(=update, "username= ?", [username])
  B(0x0078-0x00b4): ContentValues{unReadCount=0, unReadMuteCount=0,
                                  UnReadInvite=0, atCount=0,
                                  attrflag = g0() & 0xFFEFFFFF}   // 抹 0x100000 位
                    k74.i0.c(=update, "username= ?", [username])
```

我的 `wxgrp_<hex>` **在 `rconversation` 里是真实存在的行**（R54 写进去的伪会话），
所以 `s4.p()` 非 null ⇒ **每次退出分组页，宿主都会对我们这一行执行一次 UPDATE**
（清未读四列、抹 `attrflag` 的 `0x100000` 位；分支 A 还经 `s4.Z` 做 flag 位操作）。

性质判定：
- 写入对象**只有我们自己的 `wxgrp_` 行**（`where username = 'wxgrp_<hex>'`），不会碰宿主真实会话 ⇒ 不是数据事故；
- 但它**字面违反「零数据写入 rconversation」红线**；
- 若分支 A 的 `s4.Z` 真的动了 `flag` 列，`flag` 就会与 `GroupConvPlan.flagOf(time)` 不等 ⇒ 下一次 `sync()`
  判成 `flag/time` 漂移 ⇒ 多一轮无意义 repair 写。（这一条是**推断**，靠验证清单 #11 的 `stat` 前后 `flag` 比对裁定；
  不管动不动 flag，「宿主回写我们那一行」本身已经成立。）

**改法（零成本）：extra 不要用真实 username，改用不对应任何行的哨兵**

```kotlin
/** 页面暗号：⛔ 不能等于任何 rconversation.username，否则 onPause 的 s4.b0 会回写那一行 */
private const val MARK = "gconv_view:"

fun open(context: Context?, gid: Long) {
    ...
    intent.putExtra(EXTRA_USER, MARK + gid.toString(16))   // 例：gconv_view:5
}

private fun gidOfMark(raw: String?): Long? =
    raw?.takeIf { it.startsWith(MARK) }?.removePrefix(MARK)?.toLongOrNull(16)
```

`claim()` 里把 `GroupConvPlan.groupIdOf(raw)` 换成 `gidOfMark(raw)`（两者语义一致：只认自己的暗号，原生页 extra 是
空或 `conversationboxservice`，反解为 null ⇒ 照旧 `reset()` 放行）。
`GroupConvPlan.usernameOf()` 仍用于查库/建行，**只不在 Intent 里出现**。

> 替代方案（不推荐，等价成本更高）：保留 `wxgrp_<hex>`，另挂一个 `s4.b0` before hook，
> 参数以 `wxgrp_` 开头就 `param.result = false`。多一个 hook、多一处版本耦合，收益相同。

### P1-2 跳过原生 `N()` 时连带跳过 `listener.a(null)` ⇒ 空态/ListView 可见性永不收敛

**位置**：`feature/GconvPage.kt:244`（`param.result = null`）

**证据**：`t0.N()` @ `code=0x5841a4`

```
000f  s4.t(...) -> Cursor
0013  w8.W(cursor)
0016  iget-object v0, w8.h            // v8 监听器（本页是 i0）
0018  if-eqz +5
001a  v8.a(null)                      // ★ 我们把它一起跳掉了
001d  notifyDataSetChanged()
```

`i0.a(Object)` @ `code=0x578fc8` → `FmUI.access$100(count)` → `FmUI.setShowView(int)` @ `code=0x564b44`：

```
0003  if-gtz v3, +13          // count <= 0
0005  emptyTipTv.setVisibility(VISIBLE); appbrandMessageLV.setVisibility(GONE)
0010  emptyTipTv.setVisibility(GONE);    appbrandMessageLV.setVisibility(VISIBLE)
```

⇒ **本页里 `setShowView` 的调用链只有 `i0.a → access$100`**（`access$100` 这个合成访问器就是编译器为 `i0` 调私有
`setShowView` 生成的；页内其它监听器 `h0/j0/k0/q0` 的方法表里都没有这条调用），被我们跳过后可见性停在 `initView` 的初始值：
- 正常组：若 XML 默认 `emptyTipTv` 可见（未核实，见真机清单 #5），会出现「列表上方/中间压着『暂无群聊』」；
- 空组：`emptyTipTv` 可能永远不显示，页面一片空白，像是卡住了。

**改法**：`replaceData()` 算出 `rows` 后自己补一次（`setShowView` 是 private，`XposedHelpers.callMethod` 会自动 setAccessible）：

```kotlin
val rows = intOf(cursor, "getCount")
val frag = activeFragment.get()
if (frag != null) {
    runCatching { XposedHelpers.callMethod(frag, "setShowView", rows ?: 0) }
}
// 空组文案可选：emptyTipTv.setText("该分组暂无会话")
```

### P1-3 游标稳定泄漏：`w8.W()` 只换引用、不关旧游标

**位置**：`feature/GconvPage.kt:253-275`

**证据**（这也是 team-lead 疑点 2 的正面回答）：

| 方法 | code | 方法体 |
|---|---|---|
| `w8.W(Cursor)` | `0x598e78` | `iput f = cursor; iput i = -1; return`（**6 条指令，无 close**） |
| `w8.k()` | `0x598ec0` | `g.clear(); if (f != null) f.close(); i = -1` ← 只有它会 close |
| `d2.m0()` | `0x5746a0` | `…; w8.k(); h = null` ← **onDestroy 会调它** |
| `d2.p()` | 见 `w8.p()` `0x598f0c` | `h.b(); k(); N(); h.a();` |

⇒ onDestroy 只关掉**最后一个**游标；`onResume → N → replaceData` 每次都 `W(新游标)`，旧的那只再没人持有、也永不 close
⇒ **每次页面 resume 泄漏一个 CursorWindow（约 2MB 级）**。`onNotifyChange → d2.x1 → p()` 这条路径会先 `k()`，
所以事件刷新不漏，**只有 onResume 那条漏**，且用户每次进出分组都 +1。

**改法**：先取旧游标（**不要用 `w8.q()`** —— `q()` 在 `f == null` 时会调 `L()`，`d2.L()` 就是 `N()`
⇒ 重新进入我们的 hook，形成递归），`f` 是 public 字段（flags=0x1），直接读：

```kotlin
private fun replaceData(adapter: Any): Boolean {
    ...
    val cursor = query(selectSql(members)) ?: return false
    val w = wMethod(adapter) ?: run { closeQuietly(cursor); return false }
    // ★ 先记下宿主当前那只，W 之后把它关掉（W 自己不 close，见 _r55b §2.4 与 code=0x598e78）
    val old = runCatching { XposedHelpers.getObjectField(adapter, "f") }.getOrNull()
    runCatching { w.invoke(adapter, cursor) }.onFailure { closeQuietly(cursor); throw it }
    if (old != null && old !== cursor) closeQuietly(old)
    ...
}
```

若 `f` 字段名在将来版本变化：`getObjectField` 抛 ⇒ `getOrNull()` 为 null ⇒ 退化成现状（不崩），
但要在日志里留一行 `warn` 便于发现（`w8.k()` 是等价替代，但 `k` 这种单字母方法名更不稳，不推荐）。

### P1-4 `open()` 失败无任何回退（注释写了，代码没写）

**位置**：`feature/GconvPage.kt:113-116`

现状：`ActivityNotFoundException` / 类名变更 ⇒ 只打 `K7 gpage open failed`，**点击变成无反应**。
这是相对 R54（点 → `GroupDetailPage`）的**功能回归**：老入口 100% 可用，新入口在换微信版本后可能整条失效而没有退路。

**改法**：`onFailure` 里退 `GroupDetailPage`（与 P0-1 C 是同一处代码，建议一次改完）：

```kotlin
}.onFailure {
    LogX.w("$TAG open failed gid=$gid: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
    val n = GroupDb.current()?.listGroups()?.firstOrNull { it.id == gid }?.name ?: "分组"
    GroupDetailPage.open(context, gid, n)
}
```

[SEC3 DONE]

---

## 建议项 · P2

### P2-1 删掉 `countRows()`，标题 N 只从 Hook B 的游标取

`GconvPage.kt:193` 在主线程 `count(*)`，`replaceData():268` 又 `cursor.getCount()` —— **同一个数查两遍**，
且第一次查的时候我们马上就要用第二遍的结果覆盖它。

**这不是 ANR 风险**（宿主自己的 `t0.N()` 也在主线程跑 `select * from rconversation`，见 `code=0x5841a4`；
`count(*)` 还能吃 `username` 的唯一索引），但它是纯浪费，并且会让标题先闪一个「组名 (N)」再刷成同样的值。

改法：删 `:193` 的 `countRows` 与 `applyTitle(..., rows)`，只留 `applyTitle(act, fragment, name, null)`
（标题 = 组名），N 由 `replaceData()` 补。附带收益：P0 里 Hook B 没接管时，标题不会再假装有 N。

### P2-2 SQL 少了原生的 `type=5` 过滤片段

`s4.t()` @ `code=0x4a3e5c`：
`"select * from rconversation where "` + `parentRef = '…'` + `vj0.e2.a(false, 5)` + `" order by flag desc, conversationTime desc"`。
我们的 `selectSql()`（`:339-340`）少中间那段（`vj0.e2.g`，即 `rconversation.username != '@qqim'` 一类系统会话排除）。

影响面很小（成员 wxid 撞 `@qqim` 才可见），但既然目标是「跟微X一模一样」，建议补上并注释来源：

```sql
… and rconversation.username != '@qqim'
```

未补则在 `whereClause` 旁边注释「有意省略，理由：…」，避免下一个人当成 bug 重复排查。

### P2-3 组名兜底会显示 `wxgrp_0000000000000005`

`GconvPage.kt:178-180`：`db.listGroups()` 里找不到（组被删 / 名字为空）时退成 `raw`（extra 原文）。
P1-1 改成哨兵后这句会变成 `gconv_view:5`，更难看。建议直接 `?: "分组"`。

### P2-4 三个 hook 只打失败日志，真机无法确认「装上了」

`install()` 只 `runCatching` + 失败日志。真机排查时「没有日志」既可能是没触发、也可能是没装上。
建议每个 hook 安装成功各打一行：

```kotlin
LogX.i("$TAG hookA installed")   // 以及 hookB / hookC
```

这是本轮验证清单里 **#1 判据的前提**（否则「只有 claim 没有 reload」到底该怀疑什么就分不清）。

### P2-5 原生页的 `claim()` 会无条件 `reset()`，可能清掉还活着的那一页

`GconvPage.kt:159-164`：只要 extra 不是我们的，就 `reset()`。单页场景没问题（我们的页 onDestroy 已经 reset 过），
但如果出现「我们的页 A 还活着、又创建了一个原生折叠页 B」（极边缘，比如未来加了页内跳转），
B 的 claim 会把 A 的成员集合清掉 ⇒ A 下一次 `N()` 回落到原生列表。

建议按实例归属清：

```kotlin
private fun resetIfOwned(act: Activity?) {
    val cur = activePage.get()
    if (cur == null || act == null || cur === act) reset()
}
```

### P2-6 `GroupDb.open(act)` 在主线程（`:166`）

`GroupDb.open()` 会 `created.writableDatabase`（`GroupDb.kt:55-63`）⇒ 建库/升级在主线程。
常态下 `current()` 非空（bootstrap 已开），只有「冷启第一次就点分组」才会走到；量级是一次 SQLite open，
不致命，但值得挪到 `GroupDb` 自己的 warm-up（或干脆在 `claim` 里改成 `db == null` 就 `reset` + log，
让入口走 P0-1 C 的回退）。

### P2-7 `applyTitle` 的 `viaFragment` 判定不可靠

`:205-208` 用「反射没抛异常」当作成功判据，但 `setMMTitle(String)` @ `code=0x564470` 是：

```
iget-object v0, this.ui
if-eqz v0, +5          // ui 为 null 就静默 return-void
invoke BaseConversationUI.setTitle(v2)
```

`ui` 为 null 时不抛异常、也什么都没做，`viaFragment` 仍为 true ⇒ 标题不生效且无日志。
本轮实测链路可证明 `ui` 在 `onActivityCreated` 时非空（同一方法里的 `initView()` 就是靠它把标题设成「折叠的群聊」的，
真机能看到该标题），所以**当前不会命中**；但建议判据换成「`getObjectField(fragment,"ui") != null`」再调，
否则退 `act.title`，免得将来换版本时静默失效。

### P2-8 成员很多时的日志与上限（结论：不需要截断）

SQL 长度 ≈ `26 + Σ(len(wxid)+3)`；wxid 约 20 字符 ⇒ **每成员约 24 字节**。
SQLite 默认 `SQLITE_MAX_SQL_LENGTH = 1,000,000` 字节 ⇒ 约 4 万个成员才触顶，微信单个分组不可能到。
用的是字面量不是绑定参数，也不受 `SQLITE_MAX_VARIABLE_NUMBER`(999/32766) 限制。
**结论：不用加长度保护**；建议 `sqlLen` 日志保留（已有），并在成员 > 2000 时补一条 `LogX.w`，将来出问题一眼看到。

### P2-9 空组文案

空组走 `1=0`（`:329`）⇒ 空游标，不崩。修完 P1-2 后会显示 `emptyTipTv`，但文案是资源 `2131759120` = **「暂无群聊」**
（`initView` @ `0x0041-0x0044` 写死）。分组里可能装的是好友会话，建议一并 `setText("该分组暂无会话")`。

[SEC4 DONE]

---

## 我核实过但没问题的点

### 先逐条回你的 8 个疑点（结论 + 落在哪节）

| # | 你的疑点 | 我的结论 | 位置 |
|---|---|---|---|
| 1 | `wxDb()` 可能为 null | **成立，P0**。并给了可直接落地的替代获取路径（rawQuery + `rconversation` + `getPath()` 双判据） | 必改 P0-1 |
| 2 | 游标泄漏 | **成立**。`W` 只有 6 条指令、绝不 close（`code=0x598e78`）；`k()` 才 close（`0x598ec0`），只有 `d2.m0()`（onDestroy）和 `d2.p()` 会调 | 必改 P1-3 |
| 3 | 主线程查微信库 / ANR / locked | **不是 ANR 风险（宿主自己也这么干），但是纯浪费的一次重复查询**；`SQLiteDatabaseLockedException` 不会因为「主线程」而出现（wcdb 读走 WAL/连接池，且我们 `runCatching` 兜住），已被 B 的 `getCount` 覆盖 ⇒ 删掉 | 建议 P2-1 |
| 4 | `wMethod()` 匹配 / 可见性 | **没问题**。描述符 `W(Landroid/database/Cursor;)V`，flags=`0x1`（public）、非 static，`t0` 未覆写 ⇒ `Class.getMethods()`（含继承 public）取得到 | 本条下方 |
| 5 | `activeAdapter` 字段与时机 | **没问题**。字段名就是 `adapter`、类型 `d2`、private；`initView()` 里 `0x005f iput adapter`，而 `initView` 在 `onActivityCreated` 的 `0x001d` 被调 ⇒ hook 在 **after** 一定读得到 | 本条下方 |
| 6 | 只挂 onDestroy | **够用**。manifest `configChanges=0x4A0` 含 orientation/screenSize/keyboardHidden ⇒ 旋转不重建；出栈必走 FmUI 的 `onDestroy`；关键顺序由 adapter 比对兜住且失败方向是放行 | 本条下方 |
| 7 | 删 `groupNames` 的回归 | **不存在这个变量**（HEAD 与工作区 grep 均 0 命中，HEAD 的 ConvFilter 连 `setOnClickListener` 都没有 —— 整块接管是 R54 未提交改动）。`_r55_impl.md` SEC1 那段描述与仓库实况不符，属文档误差。长按/点击各自 `db.listGroups()`，**无回归** | 本条下方 |
| 8 | `select *` 的列能否被 `d2.getView` 消费 | **没问题**。原生 `s4.t` 拼的就是 `"select * from rconversation where "`（`code=0x4a3e5c` 的 `0x0002`），列集完全一致；`d2.Y` 只做 `r4.convertFrom(cursor)` | 本条下方 |

### #4 `w8.W` 的准确描述符与可见性（已逐字节核实）

```
Lcom/tencent/mm/ui/w8;  virtual W(Landroid/database/Cursor;)V  flags=0x1  code=0x598e78
字段: public Landroid/database/Cursor; f      (flags=0x1)
     public I i                                (缓存计数)
方法: public getCount()I  0x598c7c   →  i<0 时 q().getCount(), 再 + A()（A() 恒 0，见 0x598c68: const/4 v0,#0; return）
     public q()Landroid/database/Cursor;  0x598bfc
     public k()V  0x598ec0（唯一会 close(f) 的地方）
```

- `flags=0x1` = public，非 static，返回 void ⇒ `adapter.javaClass.methods` 里按「name=="W" && 1 参 && Cursor 可赋值」筛
  **能命中**；`t0`/`d2` 都没覆写 `W`，`getMethods()` 返回继承的 public 方法。
- 唯一隐患不是可见性而是**方法名 `W` 是混淆名**：换版本会变成别的字母 ⇒ 现在 `wMethod()` 找不到时走
  `closeQuietly + LogX.w + return false` ⇒ 宿主原生 N 放行（fail-safe，不会崩，但功能静默失效）。
  配合 P2-4 的成功日志，就能在真机上一眼分辨。

### #5 `adapter` 字段名/时机（已逐字节核实）

```
FmUI 实例字段（classes4.dex class_data）:
  flags=0x2  Lcom/tencent/mm/ui/conversation/d2;  adapter      ← 就是这个名字，private
  flags=0x2  Landroid/widget/ListView;             appbrandMessageLV
  flags=0x2  Landroid/widget/TextView;             emptyTipTv   ← P2-9 的落点
  flags=0x2  Ljava/lang/String;                    superUsername

onActivityCreated(Bundle)  0x5648a4:
  001d: initView()                       ← adapter 在这里被 new 出来并 iput
initView()  0x5646ac:
  004f: new-instance t0
  005c: t0.<init>(activity, superUsername, i0(this))
  005f: iput-object v0, this.adapter : d2   ★
  0061-006a: appbrandMessageLV.setAdapter(adapter)
```

Hook 挂在 `onActivityCreated` 的 **after** ⇒ 字段必然已赋值；`XposedHelpers.getObjectField` 对 private 字段会
`findField + setAccessible`，能拿到。**你担心的「恒 null ⇒ Hook B 永不接管」不会发生**。
（真发生了也不会污染原生页：`param.thisObject !== null` 恒真 ⇒ 放行，fail-safe。）

### #6 只挂 onDestroy 是否残留

- **配置变更**：`AndroidManifest` 里该 activity `configChanges=0x4A0` = `orientation(0x80) | keyboardHidden(0x20) | screenSize(0x400)`
  ⇒ 旋转/分屏都不重建，不存在「重建后 state 错位」。真要重建也会先 `onDestroy`（reset）再 `onActivityCreated`（重新 claim），顺序安全。
- **页面复用**：Fragment 不 retained，`FmUI` 有独立 `onDestroy` 实现（`code=0x564948`，不是纯继承），
  Activity 销毁必然走 fragment 生命周期 ⇒ `reset()` 一定执行。
- **关键返回顺序**：从我们的页 A 回到下面的原生折叠页 B，框架顺序是
  `A.onPause → B.onRestart/onResume(⇒ t0.N) → A.onStop → A.onDestroy` ——
  **B 的 N 确实会在 A 的 reset 之前跑**，所以「只靠全局 activeGid」会污染 B。
  这正是 `activeAdapter` 实例比对存在的理由，而它是对的（B 的 adapter 是另一个 t0 实例）。
- **残留的具体后果**（假设比对失效）：会把分组 G 的成员集合喂进微信原生「折叠的群聊」，让用户在原生页看到别处的会话 ——
  现在有比对兜住，最坏也只是「我们的页自己显示原生列表」。
- `onDetach` 不挂没有额外收益：detach 必然伴随 destroy（或 Activity 销毁），reset 已覆盖。

### #7 `groupNames` 与长按链路

```
$ git show HEAD:…/ConvFilter.kt | grep -n groupNames      → 0 命中
$ grep -rn groupNames app/src/main/java/                  → 只有 BackupFlow/BackupMerge 的局部参数名
$ git show HEAD:…/ConvFilter.kt | grep -n setOnClickListener → 0 命中
```

⇒ HEAD 里根本没有「伪会话行接管 + 点击开 GroupDetailPage」这段，`groupNames` 也从未存在过。
**结论**：`_r55_impl.md` SEC1 那句「删除 `groupNames`（:1092 声明 / :1261 赋值）」与代码不符，
是本轮文档的描述误差（不影响可编译性，但建议改掉，免得下一轮 review 又去追一个不存在的符号）。

长按链路独立于 ConvFilter：`ConvFilter.kt:1830-1833` → `GroupRowMenu.show(ctx, gid)` →
`GroupRowMenu.showInternal` 自己 `db.listGroups().firstOrNull{it.id==groupId}` 取名字（`GroupRowMenu.kt:89-93`）。
点击链路 `GconvPage.claim` 同样自己查 `listGroups()`。**两处都不依赖被删的快速快照，无回归**。
`GroupDetailPage` 仍被 `EntryReceiver.kt:1039`、`GroupBar.kt:1455/1471` 引用，未成孤儿。

### #8 `select *` 的列集与 `d2.getView`/`r4.convertFrom`

- 原生 SQL（`s4.t` @ `0x4a3e5c`）：`"select * from rconversation where "` + `parentRef='…'` + `vj0.e2.a(false,5)` +
  `" order by flag desc, conversationTime desc"`。我们 `:339-340` 与它**同构**（只少那段类型过滤，见 P2-2）。
- 列表渲染：`d2.getView` 走 `d2.Y(r4, Cursor)`（`0x572fd0`）→ `new r4(); convertFrom(cursor)`（`r4.convertFrom` @ `0x4a3198`
  直接 `invoke-super jj.y1.convertFrom`）。**没有任何 join 列**：原生自己也是 `select *` 单表，所以列集天然一致。
- `w8.getCount()` 的 `A()` 恒 0（`0x598c68`）⇒ **页面标题的 N 就是 `cursor.getCount()`**，与我们的 `rows` 同口径，不需要再加修正。

### 线程安全 / 可见性（自查项）

- `claim()`（onActivityCreated after）、`replaceData()`（t0.N，onResume 主线程）、`reset()`（onDestroy）
  **三者全在主线程** ⇒ 不存在跨线程竞态；`activeGid` 用 `@Volatile` 只是防御性的（写序 members→name→…→gid 最后写，
  读侧先读 gid，是具有 release/acquire 语义的正确顺序，即使将来挪到后台线程也成立）。
- 三个 `WeakReference` 字段不是 volatile：只被主线程读写，**当前无问题**；如果按 P0-1 A 把查询挪到后台线程，
  必须把它们也改成 `@Volatile`（或者只在主线程读取、把值作为参数传进后台任务）。已在改法里保持「查询在 N 的调用线程（主）」
  这一前提，不引入新线程。
- `reset()` 非原子：极端交错下读侧可能看到「gid>0 但 members 已清空」⇒ 一次 `1=0` 空列表，不崩。仅理论存在（同线程不可能）。

### 注入 / 转义（自查项）

- `whereClause()` 用 `GroupConvPlan.quote()`（`'` → `''`）；SQLite 无反斜杠转义，`''` 是唯一正确写法 ⇒ **安全**。
- 这道转义是**必要**的：成员 wxid 来自本地 `group_member` 表，备份导入可以塞任意字符串（`BackupMerge` 不校验字符集），
  不转义就是一条现成的注入面。
- `substr(username,1,6)<>'wxgrp_'` 用 substr 而非 `LIKE 'wxgrp_%'` 是对的（`_` 是单字符通配，会误伤）。
- 没有 `execSQL`/`insert`/`update`/`delete` 字符串 ⇒ `GconvPage` 代码层零写入。

### 其它已核实的小结论

- `param.result = null` 对 `void` 方法有效：`XC_MethodHook.MethodHookParam.setResult(null)` 会置 `returnEarly = true`，
  宿主 `t0.N()` 的 `void` 返回值被忽略 ⇒ 正确跳过。
- `setMMTitle` 有 `String` / `CharSequence` 两个重载，反射按运行时类型（`String`）选，命中 `0x564470` 那个 ⇒ 没问题。
- `rawQueryMethod()` 的两个候选 `(String,String[])` / `(String,Object[])`，传 `null` 实参对两者都合法；
  不缓存 `Method` 是对的（`GroupConvStore` 类注释里有同一条教训）。
- `open()` 不加 `FLAG_ACTIVITY_NEW_TASK`（`v.context` 是 Activity）与微信自己点折叠群聊一致；`exported=false` 不影响同进程显式启动
  （`AndroidManifest` 该 activity 无 intent-filter，默认 false —— 与 `_r55b` §1.6 一致）。
- 红线核对：`GconvPage` 全程只 `select`；`ConvFilter` 的 `rowGid == null && cv?.getTag(...) != null ⇒ cv = null` 防御块
  **原样保留**（diff 里只是 `val cv` → `var cv`，判定式一字未改）；`GroupDetailPage` / `GroupRowMenu` 均未删除。
  **唯一压线的是 P1-1（宿主替我们写）**，改 extra 即可归零。

[SEC5 DONE]

---

## 真机验证清单

> 前置约定（不满足就别开始，否则测的不是这条路径）：
> 1. `GCONV_MODE` **默认 false**（`Keys.kt` `Prefs.ALL` 里 `"false"`），必须先打开并**冷启**：
>    `adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op kvset --es key chat.gconv_enabled --es val true`
>    （`Kv.getBool` 认 `"true"`/`"1"`，见 `Kv.kt:35-38`）
> 2. 灌一次伪会话行：`… --es op gcs --es sub sync`（`force=true`，绕节流），日志出现 `GCONVSTORE sync done own=N`。
> 3. **`adb shell am force-stop com.tencent.mm` 后冷启，并且在此之前不要进任何聊天、不要收消息**
>    —— 这是复现 P0-1 的必要条件；带消息进来就测不出 `wxDb()` 为 null 那条。
>
> 日志只有一个权威出口：`/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt`（`LogX.kt:20-23`）。
> logcat（tag `WXGroup`）不可靠，只当辅助。
> 拉取：`adb pull /sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt`（Git Bash 记得 `export MSYS_NO_PATHCONV=1`）。
> 页面取证：`python tools/_r55_page.py list` / `tap <标题子串>` / `state`。

| # | 操作 | 判据（读什么、看到什么算过） |
|---|---|---|
| 1 | 冷启后立刻 `grep "K7 gpage"` | **装没装上**：应看到 `hookA installed` / `hookB installed` / `hookC installed`（P2-4 未改则这条无法判，只能靠后续行为倒推）。一条都没有 ⇒ 整个 `install()` 失败，看 `K7 gpage hook* install fail` 的异常类型 |
| 2 | **P0 复现位**：冷启（未收消息）直接点分组行 | 期望（改前）：日志只有 `claim gid=… rows=-1`、**没有** `reload`，且出现 `query: wx db not ready`；界面 = 组名标题 + 微信原生折叠群聊列表。改后：先出现 `conv db captured path=…EnMicroMsg.db`，再 `reload gid=… rows=N`，且标题带 `(N)` |
| 3 | `python tools/_r55_page.py state` | `topResumedActivity` 必须是 `com.tencent.mm/.ui.conversation.ConvBoxServiceConversationUI`（**别看界面像不像，只看类名**）；标题节点（`pkn`）= `组名 (N)`，N == 页内会话行数（`cqz` 计数） |
| 4 | `state` 里逐行读标题 | 每一行都应是**组内成员的真实会话**（群名/好友名 + 摘要 + 时间），不能出现 `wxgrp_0000…`（说明 `substr` 排除失效）也不能出现不在组里的人（说明成员集合/身份比对串了） |
| 5 | 看空态文案 | 正常组：列表上方/中间**不能**出现「暂无群聊」（P1-2 未改则很可能出现，这就是它在真机的样子）。空组：先建一个 0 成员分组再进页，应看到空态提示、不崩、不被换成原生折叠列表 |
| 6 | 页内点一条会话 | 进 `ChattingUI` 且是那一条会话（不是空白/不是分组详情页）。返回后仍是分组页，列表还在 |
| 7 | 页内长按一条会话 | 弹**微信原生**会话长按菜单（我们没有改这里的 listener）⇒ 证明只覆盖了分组行、没破坏宿主行 |
| 8 | 返回列表 → 再进同一分组，来回 3 次 | 每次都有新的 `reload`（说明每次 onResume 都接管成功）；P1-3 改后每次 reload 前应有关旧游标的日志。未改时在此步观察 `adb shell dumpsys meminfo com.tencent.mm \| grep -iE "Native Heap\|TOTAL"` 增长（只能看趋势，不是硬判据） |
| 9 | **污染位**：返回列表后打开微信原生「折叠的群聊」 | 标题必须是「折叠的群聊」、列表是微信自己的折叠集合，**不能**出现我们分组的成员。这一步验的是 adapter 实例比对 + reset（疑点 6） |
| 10 | 分组页在前台时，从另一台设备给组内某群发一条消息 | 页面应自动多出/刷新该行（`d2.onNotifyChange → p() → k()+N() → 我们的 reload`）；日志出现新的 `reload`，`state` 行数 +1 |
| 11 | **零写入位**：进分组页前先 `… --es op gconv --es sub sql --es sql "select username,flag,attrflag,unReadCount from rconversation where substr(username,1,6)='wxgrp_'"`，退出分组页后再跑一次 | 改前（P1-1 未改）：该行的 `unReadCount/attrflag` 一定变（`s4.b0` 的 UPDATE），`flag` 是否变取决于分支 A 的 `s4.Z` —— **只要有一列变就算红线被破**。改后（extra 换哨兵）：整行**逐字节不变**。⚠️ `op=gcs sub=stat` 只打 `username\|flag\|conversationTime`，**attrflag 不在里面**，所以判零写入必须用上面的 `sub=sql`（或 `stat` 看到 `flag` 变化也算命中的强证据，但 `flag` 不变**不能**证明没写） |
| 12 | 退出后 `adb shell dumpsys activity activities \| grep -i "ConvBox"` | 不应残留多个该 Activity 实例（每次点击只 push 一个，返回即销毁） |

补充说明（避免误判）：
- **第 2 步失败不要立刻怀疑 hook**：`wxDb()` 为 null 的现场特征是「有 `claim`、无 `reload`、有 `wx db not ready`」；
  **adapter 字段/比对**失败的特征是「有 `claim`、无 `reload`、**没有** `wx db not ready`」——
  两者靠这一行日志区分，这就是 P2-4 + claim 里的日志需要写清楚的原因。
- 第 9 步若失败（原生页被污染），说明 `activeAdapter` 为 null 且退化成「按全局 gid 接管」——
  正常实现下不会发生（比对失败是放行），出现即说明还有第三条 `N()` 调用路径绕过了 `t0` 覆写。
- 第 10 步若一直不刷新：先看 `d2.onNotifyChange` 是否因 `d2.v`（mIsFront）为 false 而没 post 刷新；
  这属于宿主行为，不是我们的 hook 问题。

[SEC6 DONE]

---

[DONE]
