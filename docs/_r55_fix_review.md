# R55 修复的上机前独立复核（lane: general-purpose-1，只读）

> 复核对象：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/GconvPage.kt`（584 行）
> 对照：`docs/_r55_impl_review.md`（P0×1 + P1×4）、`docs/_r55_fix.md`、`docs/_r55b_convbox.md`
> 本轮自行反汇编 `_cache/mm.apk` 的 classes4/10/13.dex 复核调用链，不照抄上一棒结论；
> 每条结论标 `[已证实]`（类名 + 描述符 + code 偏移，可复现）/ `[推断]` / `[证据不足]`。
> 纪律：**未改 `app/src/main` 任何文件、未跑 adb、未 install、未 commit**。

---

## 结论

**改完再上。**

方向没问题（数据源 / 标题 / 生命周期三条链路都能挂住），**旧报告里的 P1-1~P1-4 四条确实修好了**（逐条见「核实过没问题的点」）；
**但 P0 没修好** —— 新加的第 4 个 hook 打在了 `SQLiteDatabase.rawQuery`，而宿主的会话列表查询
**已经证明**走的是 `SQLiteDatabase.rawQueryWithFactory`（`rawQuery` 根本不在链上）：

```
t0.N()  0x5841a4
  → s4.t(...)                        0x4a3e5c   （invoke-interface k74.i0->k）
  → z84.z.k(String,String[])         0x7b63dc
  → z84.z.g(String,String[],int)     0x7b6308
  → z84.e.s(String,String[],int,Z)   0x7b2a88   （invoke-virtual rawQueryWithFactory）
  → SQLiteDatabase.rawQueryWithFactory 0x4bbf48(4参) / 0x4bbf70(5参)
```

`s4` 的库句柄就是 `z84.z` 实例（`b1.F8` @ 0x35b894 用 `y70.z.i : z84.z` new 的 `s4`），
所以这条链**不会经过 `rawQuery`**（`rawQuery` @ 0x4bbef8 反而只是转发到 `rawQueryWithFactory`）。

后果：冷启（没收到过 message 表 update）时 `capDb` 仍为 null、`AntiRevoke.wxDb()` 也为 null
⇒ `claim()` 的闸门（`GconvPage.kt:319-323`）直接 `reset()` 不认领 ⇒ 用户点分组行后
**看到的是微信原生「折叠的群聊」**（列表还是别人的数据），且 `open()` 没有任何回退。

另有一条 P1 建议一并做（见必改项 P1-5）：`open()` 现在只在 `startActivity` 抛异常时回退，
而「进得去但认领不了」这条更常见的失败没有退路。

[SEC1 DONE]

---

## 必改项

### P0-1 `captureHook` 打错方法：会话列表走 `rawQueryWithFactory`，不是 `rawQuery`

**位置**：`feature/GconvPage.kt:229`（`hookAllMethods(cls, "rawQuery", captureHook)`）、`:245`（`args[0] as? String`）

**结论：不成立（捕获不到）** —— 不是「有概率」，是这条路径 100% 不经过 `rawQuery`。

**[已证实] 逐层调用链（classes10.dex / classes4.dex，偏移可复现）**

1. `com/tencent/mm/ui/conversation/t0;->N()V` @ code=**0x5841a4**：
   `invoke-virtual s4->t(ILjava/util/List;Ljava/lang/String;I)Landroid/database/Cursor;`，第 3 参 `"conversationboxservice"`（与 `_r55b` §2.2 一致）。
2. `com/tencent/mm/storage/s4;->t(ILjava/util/List;Ljava/lang/String;I)Landroid/database/Cursor;` @ code=**0x4a3e5c**：
   拼完 SQL 后 **`invoke-interface {v5, v3, v4}, k74.i0->k(Ljava/lang/String;[Ljava/lang/String;)Landroid/database/Cursor;`**（0x0079）。
   ⚠️ 不是 `SQLiteDatabase.rawQuery`，是一个**抽象接口** `k74.i0`（classes3.dex，`access=0x601` = public interface abstract）。
3. `s4` 拿的是哪个实现：`com/tencent/mm/plugin/messenger/foundation/b1;->F8()Lcom/tencent/mm/storage/s4;` @ code=**0x35b894**
   → `iget-object v2, y70.z.i : z84.z` → `new-instance s4` → `s4.<init>(Lk74/i0;)V` @ **0x4a567c**（`:0081 iput-object v6, s4.d`）。
   ⇒ `s4.d` 的运行时类型是 **`z84.z`**（`k74.i0` 在 classes10/7 只有 3 个实现类：`z84.y` / `z84.z` / `vc2.j`）。
4. `z84.z;->k(String,String[])Cursor` @ code=**0x7b63dc**：`invoke-virtual z84.z->g(String,String[],I)Cursor`（第 3 参 0）。
5. `z84.z;->g(String,String[],I)Cursor` @ code=**0x7b6308**：`iget-object v0, z84.z.b : z84.e` →
   `invoke-virtual z84.e->s(String,String[],IZ)Cursor`。
6. `z84.e;->s(String,String[],IZ)Cursor` @ code=**0x7b2a88**：**两条分支都只调
   `com/tencent/wcdb/database/SQLiteDatabase;->rawQueryWithFactory(...)`** ——
   0x0060 调 5 参（`CursorFactory,String,Object[],String,CancellationSignal`），0x0074 调 4 参；**无 `rawQuery`**。
7. 反证：`com/tencent/wcdb/database/SQLiteDatabase;->rawQuery(String,Object[])Cursor` @ code=**0x4bbef8**
   方法体只有 6 条指令，**自己就是转发到 `rawQueryWithFactory`**（0x0006 invoke-virtual 5 参）。
   4 参 `rawQueryWithFactory` @ 0x4bbf48 也是转发到 5 参 @ 0x4bbf70（后者 `SQLiteDirectCursorDriver` → `SQLiteCursorDriver.query`）。
   ⇒ **`rawQuery` 是上层入口，不是宿主的实际调用点，挂它等于挂在没人走的路上。**

**旁证（重要，避免把话说死）**：本模块 `ContactSqlSource` 用同样的 `rawQuery` 钩子，真机日志确实出现过
`CSQL EnMicroMsg.db captured via rawQuery`（`_a4.txt:220`、`_inst.txt:293/368/…`）。
说明 ① `getPath()` 在 wcdb 实例上可用、② `rawQuery` 钩子本身能触发、③ **某些** SQL 会走 `rawQuery`。
但那是 `rcontact` 的另一条调用方，**不能证明 `rconversation` 的会话列表也走它**；而唯一可复现的会话列表
生产者（`t0.N → s4.t`）已证明不走。⇒ 该捕获**不可依赖**，冷启第一次点分组大概率仍是 null。

**改法（最小改动）**：把钩子从 `rawQuery` 换成 **`rawQueryWithFactory`**，SQL 在 **`args[1]`**：

```kotlin
// installDbCapture()（GconvPage.kt:222-236）
val set = runCatching {
    XposedBridge.hookAllMethods(cls, "rawQueryWithFactory", captureHook)
}.getOrNull()

// captureHook.beforeHookedMethod（GconvPage.kt:245）
// rawQueryWithFactory(CursorFactory, String sql, Object[] bindArgs, String editTable[, CancellationSignal])
val sql = param.args?.getOrNull(1) as? String ?: return
```

- 4 参重载会转发 5 参 ⇒ **只挂一次会进来两发**（先 4 参、后 5 参）：第一发命中即 `capDb` 置位 +
  `unhookCapture()`，第二发已经没钩子了，不会重复；也不会漏。
- `rawQuery` 的调用方（如果有）同样会转发进 `rawQueryWithFactory` ⇒ 换钩后 **覆盖面只增不减**，
  可以**删掉** `rawQuery` 那一次 `hookAllMethods`（两处都挂只会让第一发多跑一次判断）。
- 建议顺手把挂到的**方法签名**打进日志（`param.method`），真机上能一眼看出打在哪个重载：
  `LogX.i("$TAG db capture hook: ${m.name}${m.parameterTypes.size}")`。
- 备选挂点（不推荐，仅作退路）：`z84.e.s(String,String[],int,boolean)` @ 0x7b2a88，`args[0]` 就是 SQL，
  但 `thisObject` 是 `z84.e` 而不是 db（db 在字段 `a`/`b` 里，还得再 `getObjectField`），
  且 `z84.e` 是更不稳定的混淆名 ⇒ 优先 `rawQueryWithFactory`。

**改完的验收判据**（与 `_r55_impl_review.md` P0 一致）：冷启后（未收消息、未进聊天）
依次出现
```
K7 gpage db capture installed ...
K7 gpage db captured path=/data/user/0/com.tencent.mm/MicroMsg/<md5>/EnMicroMsg.db
```
然后再点分组行出现 `K7 gpage claim gid=… name=… members=N` 与 `K7 gpage reload gid=… rows=N`。

> 附：`args[0]` 类型问题**不成立** —— 该类只有 `rawQuery(String,Object[])` 与
> `rawQuery(String,Object[],CancellationSignal)` 两个重载（classes10.dex method 表），
> 首参都是 `String`（`Object[]` 是**第二**个形参）。真正的问题不是下标/类型，是**方法名错**。

---

### P1-5 `open()` 只兜 `startActivity` 异常，兜不住「进得去但认领不了」

**位置**：`feature/GconvPage.kt:151-173`（`open()`）

**结论：成立（且是这次 P0 修复**新引入**的可见回归）**

- 改前（P0 未修但没闸门）：标题是我们的、列表是原生的（错位但至少「进去了」）。
- 改后：`claim()` @ `:319-323` 发现 `convDb() == null` ⇒ `reset()` + 不认领 ⇒
  **整页是微信原生「折叠的群聊」**：标题「折叠的群聊」、列表是**别人**（微信折叠集合）的会话。
  用户从「点分组」跳到一页完全不相干的会话列表，比 R54 的「点 → 分组详情页」是**功能倒退**，
  且 `open()` 的 `onFailure` 完全不会触发（`startActivity` 是成功的）。

**改法**：`open()` 入口先判一次库句柄，没就绪就走 R54 老路（与 `_r55_impl_review.md` P0-1 C 同款）：

```kotlin
fun open(context: Context?, gid: Long) {
    if (context == null) { …; return }
    if (convDb() == null) {                       // 冷启未捕获到库：不要进「别人的页」
        LogX.w("$TAG open: no conv db, fallback GroupDetailPage gid=$gid")
        runCatching { GroupDetailPage.open(context, gid, groupNameOf(gid)) }
            .onFailure { LogX.w("$TAG fallback failed: ${it.javaClass.simpleName}") }
        return
    }
    …
}
```

这样最坏情况是「回到 R54 的入口」，而不是「点分组跳到别人的列表」。
`groupNameOf()` 已核实只返回组名或 `wxgrp_<hex>`（`:192-196`），**不会**把哨兵串当组名。

[SEC2 DONE]

---

## 建议项

### S-1 捕获钩子加「认输上限」，别让它常驻
现在只有「命中才卸」。命中失败（换版本 / 库名不符 / 该组件不再走这条 API）时，
`rawQuery` 是**全进程最热的 DB 入口之一**，钩子会常驻整个进程生命周期，
每个查询都要过一次 `capDb != null` + `sql.contains("rconversation")`。
`ContactSqlSource` 已有 `CAPTURE_MAX_CALLS=20000` + `gaveUp` 的先例（`ContactSqlSource.kt:55,119-124`），
照抄即可（日志 `K7 gpage db capture gave up after N`）。
**注意**：修好 P0（换 `rawQueryWithFactory`）后命中率很高、通常启动几秒内就卸，
这条只是防「换版本后静默常驻」。

### S-2 `getPath()` 失败即认领，与注释「两道判据都满足」不一致
`GconvPage.kt:250-259`：`getPath` 返回 null 时走 `capDb = db` **照样认领**。
注释（`:213-218`）声明的是「① SQL 含 rconversation + ② 路径以 EnMicroMsg.db 结尾，两道都成立才认领」。
若 `getPath` 将来不可用，这条降级会让「别的库里恰好有 rconversation」被误认。
建议：失败时**只 warn 不认领**（真机见过一次 `path=?` 再决定要不要放宽），
或把 `db.javaClass.name` + `sql.take(80)` 一起打出来便于事后判。

### S-3 库名判据是单点（`endsWith("EnMicroMsg.db")` 大小写/改名）
真机日志已证 `getPath()` 在 wcdb 实例上可用且确实是 `…/EnMicroMsg.db`（`_a4.txt:220`），
所以**当前版本没问题** [已证实]。但换版本/换账号目录结构时一旦不匹配，
捕获就永远不命中、且只有 `claim: no db` 一条间接线索。
建议：路径不匹配时也打一条**限频**日志（例如首次 + 每 1 万次一条）
`db capture skip path=…`，把「为什么没命中」变成可读的。

### S-4 `capDb` 强引用 + 卸钩后无恢复路径
`capDb` 是强引用，永不清空；钩子命中即卸。若微信在进程内**重开/关闭**主库
（账号切换、DB 损坏自愈、`z84.e.close()` 后重建），`capDb` 会指向已关闭实例
⇒ `query()` 抛（被 runCatching 吞）⇒ `replaceData` false ⇒ 回落原生列表，且不会再捕获。
建议：`replaceData`/`query` 失败时调用一次幂等的 `installDbCapture(lpparam)`
（需要把 `lpparam` 存成字段，或存 classLoader），实现自愈。
账号切换微信本身会重启进程，概率低，故列为建议而非必改。

### S-5 `claim()` 读不到 `adapter` 字段时仍会认领 ⇒ 又回到「我们的标题 + 原生列表」
`GconvPage.kt:345-350`：`activeAdapter` 取不到只打 warn，`activeGid` 照样置位、标题照样改。
Hook B 的实例比对（`:403`）随后恒不成立 ⇒ 错位态重现。
`adapter` 字段已核实存在（`flags=0x2 Lcom/tencent/mm/ui/conversation/d2; adapter`），当前不会命中；
建议把「`activeAdapter.get() == null`」也并入 `:319` 的闸门（不认领）。

### S-6 `replaceData` 没有清 `w8.g`（按 position 的 item 缓存）
`w8.getItem(I)` @ 0x598d04 会先查 `g`（`Map`）里的 position→Object 缓存；
原生 `d2.p()` 走 `k()`（0x598ec0：`g.clear(); f.close(); i=-1`）会清它，
我们只做了 `W(cursor)`（0x598e78：`f=cursor; i=-1`，**不清 g**）。
同一页重进/成员变更后，理论上可能有一帧用到旧 position 的 `r4`（数据已拷出，不会崩，只是可能短暂陈旧）。
数据只在 onResume/onNotifyChange 刷新，风险很低；若想与原生完全对齐，
可在 `w.invoke` 后反射 `g?.clear()`（`g` 是 public `Map`，`flags=0x1`）。

### S-7 关于「注册顺序太晚」：不成立，但有一条相关提醒
`HookEntry.kt:195-197` 在 `handleLoadPackage` 里调用 `GconvPage.install`，
早于任何 UI（也早于用户能看到分组行）。而「首次会话列表查询」只要发生在**点击之前**就够
（点分组行必须先看到列表）⇒ 修好 P0 后不存在「错过首批查询」。
`ConvFilter.install` 在前、`GconvPage` 在后**没有影响**（两者 hook 的类不相交）。
真正的时序风险是**库句柄捕获前的第一次点击**（例如从通知/快捷方式直接深链到分组页），
这正是 P1-5 回退要兜的场景。

### S-8 日志可读性
建议 `claim` 失败分支把三个原因分开打（现在只有 `no db` / `db not ready` 两种）：
`no db`（句柄）、`adapter missing`（字段）、`member empty`（`1=0` 空表）。
真机上三者表现完全不同（整页原生 / 错位 / 空列表），一行日志能省一轮 adb。

[SEC3 DONE]

---

## 核实过没问题的点

### A1 总判定：**不成立（捕获不到）** —— 见必改 P0-1

以下三条只回答子问题（都不是根因）。

#### A1-① 判据有没有写反 / 短路错 —— **没有（逻辑正确）**
`GconvPage.kt:241-260` 的顺序是：`capDb != null → 卸钩`；`args[0] as? String`；
`!sql.contains("rconversation") → return`；`thisObject`；`getPath()`；`!path.endsWith("EnMicroMsg.db") → return`；
`capDb = db`；`unhookCapture()`。没有反向判断、没有把 `||` 写成 `&&`、没有提前 `return` 掉成功分支。
唯一与注释不一致的是 `getPath` 失败时的降级认领（见建议 S-2）[已证实：逐行读代码]。

#### A1-② `args[0]` 一定是 String 吗 —— **是（不是问题）**
`com/tencent/wcdb/database/SQLiteDatabase` 的 method 表里 `rawQuery` 只有两个：
`rawQuery(Ljava/lang/String;[Ljava/lang/Object;)Lcom/tencent/wcdb/Cursor;`（code=0x4bbef8）与
`rawQuery(Ljava/lang/String;[Ljava/lang/Object;Lcom/tencent/wcdb/support/CancellationSignal;)…`（0x4bbf20）。
**首参都是 `String`**，`Object[]` 是第二参。`rawQueryWithFactory` 同理首参是 `CursorFactory`、SQL 在**第二参**。
⇒ 类型判断不会出错；**真正错的是方法名**（P0-1）。[已证实：classes10.dex method 表]

#### A1-③ 卸钩方式 —— **正确（用保存的 Unhook 自卸）**
用的是保存的 `XC_MethodHook.Unhook`（`capUnhooks`，`:127-128`），不是 `XposedBridge.unhookAll`（后者会连带卸掉别人挂的钩子，更危险）。
在 `beforeHookedMethod` **内部**自卸：XposedBridge 的回调遍历用的是**快照数组**，当前这发会继续跑完，
后续调用看不到已移除的回调；`unhookCapture()` @ `:268-274` 每个 `u.unhook()` 都包了 `runCatching`。
[推断：XposedBridge/LSPosed 的 `handleHookedMethod` 语义；本项目 `AntiRevoke` / `ContactSqlSource` 已在真机用同款写法长期运行，未见异常]

### A2 哨兵 extra（P1-1）—— **成立（修好了）**

| 子项 | 结论 | 证据 |
|---|---|---|
| ① hex 口径一致 | **成立** | `open()` `:156,176-177`：`SENTINEL + GroupConvPlan.usernameOf(gid).removePrefix("wxgrp_")`；`gidOfMark()` `:184-189`：去掉 `gconv_view:` 后 `groupIdOf("wxgrp_" + hex)`。`usernameOf` = `wxgrp_` + **16 位补零小写** unsigned hex（`GroupConvPlan.kt:23-24`），`groupIdOf` = `hex.toULongOrNull(16)?.toLong()`（`:30-35`），往返无损、两边同口径 [已证实：读源码] |
| ② 非法字符会不会抛 | **不成立（不抛）** | `toULongOrNull` 是 Kotlin 的 **null 返回**版本（不是 `toULong`），空/非法/溢出都返回 null；`:185-188` 还有 `startsWith` + `isEmpty` 两道前置 [已证实] |
| ③ `s4.b0(哨兵)` 是否零写入 | **成立** | `s4.b0(String)Z` @ **0x4a4930**：`0x0007 if-eqz v15 → +296`（null 直接到底）；`0x0009 length()` `0x000d if-gtz → goto/16 +288`（空串到底）；`0x001a invoke-virtual s4->p(String)r4`、`0x001f if-nez v2 → goto/16 +268`（**取不到行就直接返回**）。`s4.p` @ 0x4a4e78 只做一次 `k74.i0->f(...)` 查询 + `moveToFirst`，无 `moveToFirst` 才 close 返回 null，**全程只读**（无 insert/update）[已证实] |

补充：`s4.p` 先把 username 交给 `s4.F(String)` 选表（`0x4a5240`：`h4.S2(s)` 真则 `rbottleconversation` 否则 `rconversation`），
哨兵 `gconv_view:…` 不含 `wxgrp_`，走 `rconversation` 查不到 ⇒ 零写入 [已证实]。
`FmUI.onPause()` @ 0x564a44 只有 `s4.b0(superUsername)` 这一处写库入口；
`onDestroy()` @ 0x564948 **不碰 `superUsername`**（只反注册 adapter / `m0()`）[已证实]。

### A3 `setShowView`（P1-2）—— **成立（修好了）**

- `setShowView(I)V` 在 `ConvBoxServiceConversationFmUI` 里**只有一个**、`flags=0x2`（private）、`code=0x564b44`
  [已证实：classes4.dex class_data]。`getDeclaredMethod("setShowView", Int::class.javaPrimitiveType)` **正确**
  （用 `getMethod` 会找不到）。
- 方法体（27 units）：`const/4 v0,#0` / `const/16 v1,#8` / `if-gtz v3,+13` ⇒
  **count<=0 → emptyTipTv VISIBLE + ListView GONE；count>0 → 反之**，只有 4 条 `setVisibility`，无其它副作用 [已证实]。
- 补偿的语义是**等价**的：`i0.a(Object)` @ 0x578fc8 只有三条有效指令 ——
  `access$000(fm)` 取 adapter → `w8.getCount()` → `access$100(fm, n)` → `setShowView`，
  没有任何其它状态 [已证实]。我们传 `rows`（= 我们 cursor 的 `getCount`）与它同口径
  （`w8.getCount()` 的 `A()` 恒 0，见 `w8.A()` @ 0x598c68）[已证实]。
- `setAccessible(true)` 在 Android 14 上对**微信自己的类**不会抛 `InaccessibleObjectException`：
  该异常来自 JPMS（`java.base` 模块系统），Android 没有模块系统；hidden-API 限制只针对 boot classpath。
  `frag.javaClass` 是宿主 classloader 加载的 FmUI，同进程反射无障碍 [推断，依据 Android 反射模型]。
- `rows == null`：`intOf` 是 `XposedHelpers.callMethod(cursor,"getCount") as? Int`，`Integer → Int` 会成功拆箱，
  **不会恒 null**；真为 null 时跳过 `setShowView` 是安全方向（不动可见性 > 误判空态）[推断 + 代码]。
- `activeFragment` 为 null 时直接 return（`:470`），不会 NPE。

### A4 关旧游标（P1-3）—— **成立（修好了）**

- 字段名 `f`：`com/tencent/mm/ui/w8;.f : Landroid/database/Cursor; flags=0x1`（public）[已证实]。
  `getObjectField(adapter,"f")` 会沿父类找到，`t0`/`d2` 都没覆写 ✓。
- `W(Cursor)V` @ 0x598e78 方法体 **6 units**：`iput-object f = cursor; const/4 v1,#-1; iput i = -1; return-void`
  ⇒ **确实不 close 旧游标** [已证实]；`k()V` @ 0x598ec0 才 `g.clear(); if(f!=null) f.close(); i=-1` [已证实]。
- **顺序**：`GconvPage.kt:433` 先读 `old` → `:436` `w.invoke(adapter,cursor)` → `:440` `closeQuietly(old)` → `:441` `notifyDataSetChanged()`。
  即「W 之后、notify **之前**」关旧游标。
  这个顺序**可以**：W 已把 `w8.f` 指向新游标，ListView 在 notify 后只会经 `q()` 拿到新游标；
  旧游标此后没有任何字段引用（`d2` 的 item 缓存 `g` 存的是 `r4` 对象、数据已 `convertFrom` 拷出，不持有 Cursor）。
  ⇒ 不会出现「ListView 拿到已关闭游标」[推断，依据 `w8.W` / `w8.q` / `w8.getItem` 反汇编]。
  唯一残留风险是「若未来版本把 Cursor 再缓存到别处」，见 S-6。
- `old === cursor` 时跳过：`query()` 每次都新建游标，这个分支实际上不可达，留着无害 [已证实]。
- **我们自己造的游标归属**（B 第 1 问）：
  `claim()` 里已删掉 `countRows`，不建游标；`replaceData` 每次 1 个，由下一轮 `replaceData` 关旧、
  或页面销毁时宿主 `d2.m0() → w8.k()` 关最后一个 ⇒ **不漏** [已证实：`d2.m0` @ 0x5746a0 调 `k()`]。
  仅剩一个理论漏点：`w.invoke` 成功、但随后的 `notifyDataSetChanged()` 抛异常
  （`:441` 没有 runCatching），此时 `param.result` 未置位 ⇒ 宿主原生 `N()` 再 `W()` 覆盖，
  我们那个游标失去引用 ⇒ 漏一个。极低概率，可在 `:441` 包 runCatching 后仍 `return true`。

### A5 `open()` 回退（P1-4）—— **成立（修好了，但覆盖面不够，见 P1-5）**

- `GroupDetailPage.open(Context?, Long, String)` 签名存在（`GroupDetailPage.kt:44`），内部 `mainHandler.post` [已证实]。
- `groupNameOf(gid)` @ `:192-196` 只可能返回 `listGroups()` 的组名或 `GroupConvPlan.usernameOf(gid)`（`wxgrp_<hex>`），
  **不会**把哨兵串 `gconv_view:…` 当组名 [已证实]。
- 缺口：只在 `startActivity` 抛异常时触发；「进得去但 `claim` 不认领」不触发（见必改 P1-5）。

### B 其它新问题盘点

| 关注点 | 结论 |
|---|---|
| `capDb` 是微信的库句柄，我们 `query()` 的游标归谁 close | 见 A4 末段：**不漏**（下一轮/宿主 `k()`），只剩 `notifyDataSetChanged` 抛异常时的理论漏点 |
| 第 4 个 hook 在**全进程 DB 热路径**，会不会拖慢微信 | 代码已把 `capDb != null` 放第一行（`:241`），命中后即卸 ⇒ 稳态接近零开销。**但当前永不命中 ⇒ 钩子常驻**：每个 `rawQuery` 多一次 volatile 读 + 一次 `contains`（微秒级，不构成卡顿，但属于「永远不消失的税」）。修好 P0 后自然消失；再叠 S-1 的认输上限即可兜住换版本场景 [推断：开销量级] |
| 卸钩后 `capDb` 被清空 / 库重建，有无恢复路径 | 没有（见 S-4）。`capDb` 是强引用且从不置空；库重开后会指向已关闭实例，`query()` 抛被吞 ⇒ 回落原生，不会崩 |
| `HookEntry` 注册顺序（`ConvFilter` 之后） | **不影响**：`install` 在 `handleLoadPackage` 内，早于 UI；点分组行前必须先看到会话列表，列表查询必然在点击之前发生 [已证实：`HookEntry.kt:188-197`] |
| 零数据写入 | **成立**：`GconvPage.kt` 全文无 `insert/update/delete/execSQL/ContentValues`（只有注释里出现 update 字样）；唯一的宿主写库链路 `onPause → s4.b0` 被哨兵挡死（A2③）[已证实] |
| `ConvFilter` 防御块 | **原样保留**：`ConvFilter.kt:1787` `if (rowGid == null && cv?.getTag(GCONV_ROW_TAG_KEY) != null) cv = null` 一字未动 [已证实] |
| `GroupDetailPage` / `GroupRowMenu` | **都还在**（`feature/GroupDetailPage.kt` 15KB、`feature/GroupRowMenu.kt` 5.4KB）[已证实] |
| 长按逻辑 | **未改**：`ConvFilter.kt:1830-1833` 仍是 `setOnLongClickListener { GroupRowMenu.show(...); true }` [已证实] |
| `select *` 列集 | 与原生 `s4.t` 的 `"select * from rconversation where "` 一致（0x4a3e5c 的 0x0002），`d2.getView` 只做 `r4.convertFrom(cursor)` [已证实] |
| SQL 注入 | `whereClause()` 用 `GroupConvPlan.quote()`（`'` → `''`），成员来自本地表可被备份导入污染 ⇒ 这道转义是必要的，写法正确 [已证实] |

[SEC4 DONE]

---

## 装包后最先看什么

### 第 0 步：三条日志就能定性（冷启后不做任何操作先 grep）

```
adb pull /sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt
grep "K7 gpage" wxgroup_dbg.txt
```

| 看到的行 | 含义 |
|---|---|
| `K7 gpage db capture installed …` | 钩子装上了（没有这一行 ⇒ `findClass`/`hookAllMethods` 失败，看紧跟的 warn） |
| `K7 gpage db captured path=…/EnMicroMsg.db` | **库句柄成立 —— 这是本轮唯一的总开关** |
| `claim: no db, treat as native page (gid=…)` | 总开关没打开（当前未修的代码必定命中这一行） |
| `claim gid=… name=… members=N` + `reload gid=… rows=N` | 页面接管成立 |

> ⚠️ 当前这版（P0 未修）预期结果：**第 2 行 0 命中、第 3 行必命中**。
> 如果真机上意外看到了第 2 行（说明别的子系统刚好用 `rawQuery` 查过 rconversation），
> 那是运气，不代表修复成立 —— 换一台/换一天冷启就可能不复现。

### 按可能性排序的 5 种坏法

**① 点分组 → 进了「折叠的群聊」，标题不是组名、列表不是组内会话**
- 可能性：**最高**（当前版本的确定性结果）
- 现象：页面标题 `折叠的群聊`，列表是微信自己的折叠群聊集合；返回后回到分组列表；**没有闪退**
- 日志关键字：有 `db capture installed`，**没有** `db captured`；点完只有 `claim: no db, treat as native page (gid=…)`，没有 `claim gid=`
- 怎么确认：`grep -c "db captured" == 0` 且 `grep "no db, treat as native page"` 有命中 ⇒ P0 未修好，不是别的原因

**② 点分组 → 直接回到分组详情页（R54 老页面）**
- 可能性：中（做完必改 P1-5 之后才会出现；当前不会）
- 现象：点分组行没进折叠页，而是弹出我们自己的分组详情
- 日志关键字：`open: no conv db, fallback GroupDetailPage gid=…`
- 怎么确认：说明库句柄仍未就绪，但**降级是对的**；继续按 ① 查捕获

**③ 进了页、标题是 `组名 (0)`，列表空白 + 「暂无群聊」**
- 可能性：中低（库句柄捕获到了，但 SQL 没匹配到行）
- 现象：标题带 `(0)`；页中提示「暂无群聊」；不崩
- 日志关键字：`reload gid=… rows=0 sqlLen=…`（`sqlLen` 若 ≈ 26 说明成员集合为空，走了 `1=0`）
- 怎么确认：
  - `members=0` ⇒ 成员表没数据（`GroupDb.memberWxids`），不是 SQL 问题；
  - `members>0` 但 `rows=0` ⇒ 成员 wxid 与 `rconversation.username` 口径不一致
    （典型：组里存的是群 `xxx@chatroom` 而会话行是别的形态），此时要拿成员串去真机 `op=sql` 手查一次

**④ 进页瞬间闪退 / 列表渲染到一半崩**
- 可能性：低（设计上所有路径都 `runCatching`，但渲染在宿主里，兜不住）
- 现象：`FATAL EXCEPTION`，栈顶在 `com.tencent.mm.ui.conversation.d2.getView` / `com.tencent.mm.storage.r4.convertFrom` /
  `android.database.StaleDataException` / `IllegalStateException: attempt to re-open an already-closed object`
- 日志关键字：崩前最后一条是 `K7 gpage reload gid=… rows=… oldCursor=closed`；崩后再无 `K7 gpage`
- 怎么确认：看崩溃时间戳与最后一条 `reload` 是否相邻（≤1s），相邻即我们喂的游标/数据源有问题；
  优先怀疑 S-6（`w8.g` 未清）和 P1-3 的 close 时机；临时把 `closeQuietly(old)` 注释掉再复现一次即可二分

**⑤ 返回列表后，打开微信原生「折叠的群聊」看到我们分组的会话**
- 可能性：低（有 `activeAdapter` 实例比对 + `onDestroy.reset()`）
- 现象：原生折叠页被我们的成员集合污染
- 日志关键字：原生页打开时仍出现 `reload gid=<我们的组>`（正常应一条都没有）
- 怎么确认：对照 `claim gid=…` 与页面栈时序；若命中，说明 `activeAdapter.get()` 为 null
  （`claim: adapter field missing`）导致比对退化 ⇒ 即 S-5

### 装包前的最后一条建议

按现在的代码装上去，**唯一确定的结果是 ①**（白跑一趟，还得再装一次）。
把 P0-1（换 `rawQueryWithFactory` + `args[1]`）和 P1-5（`open()` 判空回退）改完重编再上，
一次就能验到「`db captured` → `claim` → `reload`」这条主链。

[SEC5 DONE]

[DONE]
