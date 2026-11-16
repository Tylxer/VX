# R55 路线 E 实现记录 — 分组行点击复用「折叠的群聊」页

> 用户拍板：点击 = 路线 E（复用 `ConvBoxServiceConversationUI` + hook 数据源 / 标题）；
> 长按 = 本轮不动（`GroupRowMenu` 原样保留）。
> 静态依据：`docs/_r55b_convbox.md`（classes4.dex 反汇编，微信 8.0.48 / versionCode 2588）。
> 本轮**只改代码 + 编译**，未 install、未 adb、未写宿主任何表。

---

## SEC1 — 改动清单

| 文件 | 改动 |
|---|---|
| `wxgroup/app/src/main/java/com/lx/wxgroup/feature/GconvPage.kt` | **新增**：`open()` 点击入口 + 三处页面 hook；P0/P1 修复后追加第 4 处（wcdb `rawQuery` 句柄捕获），差异见 `docs/_r55_fix.md` |
| `wxgroup/app/src/main/java/com/lx/wxgroup/feature/ConvFilter.kt:1829` | 伪会话行点击由 `GroupDetailPage.open(v.context, gid, name)` 改为 `GconvPage.open(v.context, gid)`（该行本身属 R54 未提交改动） |
| `wxgroup/app/src/main/java/com/lx/wxgroup/feature/ConvFilter.kt` | **无此类改动**（订正）：仓库里从来没有 `groupNames` 这个符号（HEAD 与工作区 grep 均 0 命中，见 `_r55_impl_review.md` 疑点 #7）。点击/长按那段是 R54 起就在工作区里的未提交改动（HEAD 的 `ConvFilter` 连 `setOnClickListener` 都没有），R55 只把那一行的 `GroupDetailPage.open` 换成 `GconvPage.open` |
| `wxgroup/app/src/main/java/com/lx/wxgroup/HookEntry.kt:196` | `ConvFilter.install` 之后注册 `GconvPage.install(lpparam)` + import |

未动：
- `GroupDetailPage` / `GroupRowMenu` 文件原样保留（长按仍指向 `GroupRowMenu`）。
- `ConvFilter.kt` 的 convertView 污染防御块（`rowGid == null && cv?.getTag(...)`）一字未改 —— 删行后行号整体前移 3 行，内容不变。
- `rconversation` / `rcontact`：**零写入**（本轮没有任何 insert / update / delete）。

[SEC1 DONE]

---

## SEC2 — 静态核实（本轮新增/复核的符号）

| 符号 | 结论 | 依据 |
|---|---|---|
| `com.tencent.mm.ui.conversation.t0.N()V` | **public**，非 static，无参无返回（flags=`0x1`） | classes4.dex code=`0x5841a4` |
| `com.tencent.mm.ui.w8.W(Landroid/database/Cursor;)V` | **public**，非 static，返回 void（flags=`0x1`），方法体只有 `iput f=cursor; iput i=-1; return` | classes4.dex code=`0x598e78` |
| `com.tencent.mm.ui.w8` 的包名 | 是 `com.tencent.mm.ui.w8`，**不是** `ui.conversation.w8` | `d2` 的 super = `Lcom/tencent/mm/ui/w8;` |
| `com.tencent.mm.ui.conversation.d2` | `access=0x401`（public abstract），super = `com.tencent.mm.ui.w8`，实现 `getView / n(Object,Cursor)` | classes4.dex |
| `ConvBoxServiceConversationFmUI.adapter` | 字段名就叫 `adapter`，类型 `Lcom/tencent/mm/ui/conversation/d2;`，private | 类字段表 |
| `BaseConversationFmUI.setMMTitle(Ljava/lang/String;)V` | public，另有 `setMMTitle(CharSequence)` 重载 ⇒ 反射传 String 时按参数类型选得准 | classes4.dex |
| `ConvBoxServiceConversationFmUI.onActivityCreated(Landroid/os/Bundle;)V` / `onDestroy()V` | 均 public、均有独立实现（不是纯继承） | classes4.dex |

> 注意：`t0` 的 `N()` 是**虚方法覆写**，页面 `onResume()` 里 `adapter.N()` 的静态类型虽然是 `w8`，
> 分派到的是 `t0.N()`，因此只挂 `t0.N` 即可覆盖「onActivityCreated 后首次加载」与「每次回前台」。

[SEC2 DONE]

---

## SEC3 — 点击入口 `GconvPage.open(context, gid)`

```kotlin
Intent().setClassName("com.tencent.mm",
        "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI")
    .putExtra("Contact_User", GroupConvPlan.usernameOf(gid))   // 例：wxgrp_0000000000000005
```

- 同 task push，不加 `FLAG_ACTIVITY_NEW_TASK`（与微信自己点「折叠的群聊」一致）；
  仅当 `context !is Activity` 才补 flag 兜底。
- `exported=false` 不影响：模块跑在微信进程内、同 UID，显式 `setClassName` 可直启。
- `Contact_User` 只是**暗号**：静态已证实它只落进 `superUsername` 字段、不参与 SQL
  （`t0.<init>` 的 invoke-direct 直接跳过了该形参）。真正换数据源靠 Hook B。
- 失败（类名变更 / ActivityNotFoundException）走 `runCatching` + `K7 gpage open failed` 日志，不静默。

[SEC3 DONE]

---

## SEC4 — Hook A：`onActivityCreated` after（认领 + 预计算 + 标题）

落点：`com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI$ConvBoxServiceConversationFmUI.onActivityCreated(Bundle)` **after**。

流程：
1. `XposedHelpers.callMethod(fragment, "getActivity") as? Activity` 拿宿主 Activity（模块**没有** androidx.fragment 依赖，不能直接强转 `Fragment`，只能反射）。
2. `activity.intent.getStringExtra("Contact_User")` → `GroupConvPlan.groupIdOf(raw)`。
   - 解析不出 id ⇒ 微信原生「折叠的群聊」页 ⇒ `reset()` 后直接返回（Hook B 全放行）。
3. `GroupDb.current() ?: GroupDb.open(act)`：取组名与成员。
   - `db.memberWxids(gid)`（`Set<String>`，过滤空白）。
   - `db.listGroups().firstOrNull { it.id == gid }?.name`，取不到退回 extra 原文。
4. 写入顺序：`activeMembers → activeName → activePage → activeFragment → activeAdapter → activeGid`。
   **gid 最后写**，Hook B 先读 gid ⇒ 读到即代表其余状态已就绪（`@Volatile` 单写者语义）。
5. 行数：`select count(*) from rconversation where <成员条件>`（见 SEC5 的条件），供标题「名称 (N)」。
6. 标题：`setMMTitle(String)`（走宿主 ActionBar 更新链）→ 失败退 `Activity.setTitle`。
   `updateTitle` 内部会把字符串当 username 查显示名，查不到原样返回 ⇒ 自定义串安全。
7. 记 `activeAdapter = fragment.adapter`（字段名已核实为 `adapter`）。

[SEC4 DONE]

---

## SEC5 — Hook B：`t0.N()` before（换数据源）

落点：`com.tencent.mm.ui.conversation.t0.N()V` **before**。

放行条件（任一不满足即 `return`，宿主原逻辑照跑）：
- `activeGid > 0`；
- `param.thisObject === activeAdapter.get()`。

> 为什么必须比对 adapter 身份：`t0` 是所有「折叠的群聊」页共用的 Adapter 类。按返回键时宿主顺序是
> 「原生页 `onResume()`（调 `N`）→ 我们的页 `onDestroy`」，只靠全局 `activeGid` 会把我们的成员集合
> 喂进**原生页**。实例比对后谁家的 adapter 只吃谁家的数据。

SQL（只读）：

```sql
select * from rconversation
 where username in ('<wxid1>','<wxid2>',...)
   and substr(username,1,6)<>'wxgrp_'
 order by flag desc, conversationTime desc
```

- `substr(username,1,6)<>'wxgrp_'`：分组伪会话行本身就在 `rconversation` 里，不排掉会出现「分组页里再套一行分组」。
  用 `substr` 而非 `LIKE 'wxgrp_%'`：`_` 是单字符通配，会误伤 `wxgrpX…`（与 `GroupConvPlan.ownWhere` 同口径）。
- 成员为空 ⇒ 条件替换为 `1=0`（`in ()` 是语法错误；空列表必须是空表而不是异常）。
- 每个 wxid 过 `GroupConvPlan.quote()`（`'` → `''`），防止昵称里的引号闭合字符串。

游标构建（照抄 `core/ChatroomSource.kt` 的 `rawQueryMethod`）：

```kotlin
db = AntiRevoke.wxDb()            // wcdb 的 com.tencent.wcdb.database.SQLiteDatabase
m  = db.javaClass.methods.firstOrNull { it.name == "rawQuery" && 2 参 &&
        (第2参 == Array<String> || 第2参 == Array<Any>) }
cursor = m.invoke(db, sql, null)  // Method 每次现找、不跨库缓存
```

- 不能强转 AOSP `SQLiteDatabase`（wcdb 实现是另一套，会静默失败）。
- `rawQuery` 有 `(String,String[])` / `(String,Object[])` 两个重载，只按名字找会 `findMethodExact` 落空。

写回：

```kotlin
w = adapter.javaClass.methods.firstOrNull { it.name == "W" && 1 个 Cursor 参数 }  // public 0x1
w.invoke(adapter, cursor)          // == w8.W(Cursor)：iput f=cursor; iput i=-1
XposedHelpers.callMethod(adapter, "notifyDataSetChanged")
param.result = null                // 吃掉原生 s4.t(5,null,"conversationboxservice",-1)
```

- 只有**全部成功**才 `param.result = null`；任一环节失败（库未就绪 / 方法找不到 / 反查异常）都放行，
  宁可显示原生「折叠的群聊」列表也不留白页。
- 拿到真实 `count` 后再刷一次标题（行数只有查完才知道），仍走 `setMMTitle`。

[SEC5 DONE]

---

## SEC6 — Hook C：`onDestroy` 复位

落点：`ConvBoxServiceConversationFmUI.onDestroy()` **before** → `reset()`：
`activeGid=0 → activeMembers=[] → activeName="" → activePage/activeFragment/activeAdapter=null`。

配合 SEC5 的 adapter 身份比对，形成两层防污染：
1. 我们的页离开后 `activeGid=0`；
2. 即使在「原生页 resume 先于我们的页 destroy」的窗口里 `activeGid` 还没清，adapter 身份不等也会放行。

[SEC6 DONE]

---

## SEC7 — 编译

```
cd wxgroup && sh dev.sh build
BUILD SUCCESSFUL in 16s
```

只跑了 `build`（`assembleDebug` 链路），**未** install / 未 adb / 未真机验证。

[SEC7 DONE]

---

## SEC8 — 风险与真机验证清单

1. **`AntiRevoke.wxDb()` 为空**（最可能的运行时失败点，`GconvPage.kt:361-366` 的 `query()`）：
   该句柄只在宿主 `updateWithOnConflict` 被 AntiRevoke 捕获后才有值。冷启后一条消息都没来过时，
   Hook B 会打 `K7 gpage query: wx db not ready` 并**放行**，页面显示原生「折叠的群聊」内容。
   验证：进组前后 `logcat | grep "K7 gpage"`，确认有 `claim … rows=n` 与 `reload … rows=n`。
2. **`notifyDataSetChanged` 后行内容是否真的换掉**：`d2` 内部有 `HashMap p` 缓存（`d2` 字段表），
   若宿主按位置缓存 `r4` 且不随 `W()` 失效，可能出现「行数对了、内容还是旧的」。
   验证：进一个 3 人组，确认 3 行标题都是组内会话而不是折叠群聊。
3. **标题被宿主回写**：`onResume` 里 `i1.N("@placeholder_foldgroup", 1)` 与 `onNotifyChange` 可能再次
   触发 `setMMTitle`。Hook B 在每次 `N()` 之后补刷标题，但若宿主在**之后**又设一次，标题会回退。
   验证：进页面停留 3s 看标题是否从「名称 (N)」变回「折叠的群聊」。
4. **空组**：成员全无会话时页面显示宿主空态「暂无群聊」（资源 2131759120 未替换）。是否要改文案待定。
5. **返回栈交叉**：从组页点会话进 ChattingUI 再返回，确认列表仍是我们组（同一 adapter 实例 ⇒ 会重新接管）。
6. **原生折叠页不受影响**：不进组、直接点微信原生「折叠的群聊」，列表与标题必须完全是微信原样。

[SEC8 DONE]

---

## SEC9 — 回滚

改回 `ConvFilter.kt` 那一行为 `GroupDetailPage.open(v.context, gid, name)`（`name` 需当场取，例如
`GroupDb.current()?.listGroups()?.firstOrNull { it.id == gid }?.name ?: gid.toString()` —— 原本并不存在
`groupNames` 快照，别去找它），并删掉 `HookEntry.kt` 里 `GconvPage.install` 一段即可。
`GconvPage.kt` 不注册就完全不生效
（所有 hook 都在 `install()` 内），且全程无持久化写入，不存在数据残留。

[SEC9 DONE]

[DONE]
