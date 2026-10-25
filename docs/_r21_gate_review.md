# R21 真机门禁复核报告（独立复核者 general-purpose-3）

复核范围：`wxgroup/app/src/main/java/com/lx/wxgroup/` 下这批「尚未上真机」的改动。
只读审查，未改任何文件、未跑 gradle、未碰 adb。

## 结论

**FAIL** —— `Panel.kt` 清 `FLAG_ALT_FOCUSABLE_IM` 的时机在 `show()` 之前，会被 `AlertDialog.show()` 内部的 `installContent()` 重新加回该 flag，导致问题 1（群发「选择发送目标」页搜索框不弹输入法）的核心修复**静默失效**，必须改。

---

## 阻断级（真机会炸或功能静默失效，必须改）

### 1. `Panel.kt` 在 `show()` 之前 `clearFlags`，会被 `AlertDialog.show()` 内 `installContent()` 重新设置 flag 覆盖

- **文件:行号**：`ui/Panel.kt:181-184`
- **现象/后果**：问题 1 的核心修复无效。`Panel` 承载的 `ForwardPicker` / `PickerPage` 搜索框点击后仍 `focused=true` 却 `mInputShown=false`、`sessionRequested=false`。而且因为 flag 没被真正清掉，`UiKit.bindSoftInput` 里的显式 `imm.showSoftInput(v, 0)` 也会被 IMM 丢弃（窗口带 `FLAG_ALT_FOCUSABLE_IM` 时 IMM 视其为「不与 IME 交互」，`sessionRequested` 恒 false）——两个手段一起失效，不是「clearFlags 失效、bindSoftInput 兜底」。

- **证据（代码原文）**：
  `Panel.kt:181-184`：
  ```kotlin
  runCatching {
      d.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
  }.onFailure { LogX.w("Panel clearFlags ALT_FOCUSABLE_IM failed: ${it.javaClass.simpleName}") }
  d.show()
  ```
  关键在 AOSP `com.android.internal.app.AlertController.installContent()`（本次改动所依赖的 flag 来源）：
  ```java
  public void installContent() {
      mWindow.requestFeature(Window.FEATURE_NO_TITLE);
      if (mView == null || !canTextInput(mView)) {
          mWindow.setFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                           WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
      }
      mWindow.setContentView(mAlertDialogLayout);
      setupView();
  }
  ```
  而 `installContent()` 会在**两个时机各执行一次**：
  1. `AlertDialog.Builder.create()` → `AlertParams.apply(mAlert)` → `installContent()`（第一次）；
  2. `Dialog.show()` → `dispatchOnCreate()` → `AlertDialog.onCreate()` → `mAlert.installContent()`（**第二次**）。

  `Panel` 的页面内容是**延迟渲染**的：`render()` 在 `setOnShowListener` 里才执行（`Panel.kt:161-167`），而 `OnShowListener` 由 `show()` 末尾的 `sendShowMessage()` 触发，**晚于** `show()` 内部的 `dispatchOnCreate()`。因此 `show()` 第二次 `installContent()` 执行时，`setView(scroll)` 里的 `scroll` 仍是空 `LinearLayout`（`body` 还没 addView 任何页面 View），`canTextInput(scroll)` 递归找不到 EditText → 返回 false → **`setFlags(FLAG_ALT_FOCUSABLE_IM)` 被再次执行**，把前面 `clearFlags` 的结果覆盖掉。

  旁证（StackOverflow 上同问题的高赞分歧恰好印证了这个时序坑）：有人反馈必须 `show()` 之后（或 `post{}` / `OnShowListener` 里）再 `clearFlags` 才有效，`create()` 之后 `show()` 之前清则无效。

- **建议改法**（把清 flag 移到 `show()` 之后；最稳是放进 `OnShowListener` 里、`render()` 之后，因为此时页面 EditText 已进入 view 树、且不会再有 `installContent` 覆盖）：
  ```kotlin
  d.setOnShowListener {
      backBtn = d.getButton(AlertDialog.BUTTON_NEUTRAL)
      backBtn?.setOnClickListener { pop() }
      render()
      // ⛔ 必须在这里清：show() 内部的 AlertController.installContent() 会在
      // render() 之前重新 setFlags(FLAG_ALT_FOCUSABLE_IM)（此时 view 树里还没有
      // EditText），show() 之前清会被覆盖。
      runCatching {
          d.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
      }.onFailure { LogX.w("Panel clearFlags ALT_FOCUSABLE_IM failed: ${it.javaClass.simpleName}") }
  }
  // 删除 d.show() 之前那段 clearFlags
  d.show()
  ```
  （若担心个别系统版本 `dispatchWindowAttributesChanged` 时序，也可在 `d.show()` 之后立即补一次 `clearFlags`，双保险。）

---

## 风险级（可能出问题，需真机重点观察）

### 2. `scheduleSupplement()` 在 hook 的 before 阶段启动线程，并发读同一 `SQLiteDatabase`

- **文件:行号**：`core/ContactSqlSource.kt:129-133`（`beforeHookedMethod` 内）→ `191-199`（`scheduleSupplement`）→ `217-218`（`m.invoke(db, sql, args)`）
- **现象/后果**：`isMainDb(db)` 命中时，微信**自己的那次 rawQuery 还没执行完**（before 阶段），代码就先 `unhookCapture()` 再 `scheduleSupplement()` 启动线程，线程里用反射 `m.invoke(db, "select * from rcontact ...", args)` 对**同一个 db 实例**再发一次查询。
- **证据**：`ContactSqlSource.kt:131-132`：
  ```kotlin
  unhookCapture()
  scheduleSupplement()
  ```
  `ContactSqlSource.kt:217-218`：
  ```kotlin
  val cur = runCatching { m.invoke(db, sql, args) }...
  ```
- **判断**：不是阻断。理由：① `unhookCapture()` 先于 `scheduleSupplement()`，线程里的 rawQuery 不会再触发钩子（无递归）；② wcdb 的 `SQLiteDatabase` 走连接池、多线程安全，读读并发不阻塞；③ 查询在 `"wxgroup-csql"` 独立线程，不占微信主线程，不会直接 ANR。**但**：wcdb 具体版本对「before 阶段对同一 db 实例立刻发起反射查询」的行为（连接池繁忙时的等待、`SQLiteConnectionPool` 的 `acquire` 超时）未在真机验证过，且这是 `SnsGuard`（只 `unhook`，不并发读）没有的新路径。真机需观察首次补充读是否正常返回、有无锁等待导致的日志延迟。

### 3. `bindSoftInput` 在 `setOnFocusChangeListener` 里立刻 `showSoftInput`

- **文件:行号**：`ui/UiKit.kt:168-172`、`179`
- **现象/后果**：焦点变化回调里立刻 `showSoftInput(v, 0)`，若系统此刻 IME session 尚未建立可能返回 false 而静默失败（函数内 `runCatching` 吞掉了 false 返回值，无重试）。
- **判断**：低风险。焦点变化本身只发生在 View 已 attach 到窗口之后，此时 `showSoftInput` 多数能成功；且 `setOnClickListener` 分支（点击时）窗口早已 attach。真机若偶发不弹，建议 `kick` 内改用 `v.post { imm?.showSoftInput(v, 0) }` 延迟到下一个消息循环。

### 4. `CAPTURE_MAX_CALLS = 20_000` 认输上限

- **文件:行号**：`core/ContactSqlSource.kt:56`、`120-125`
- **判断**：低风险。该值照抄 `SnsGuard.kt:320`（同为 20_000，注释「迟迟碰不到才认输卸载」），而本功能找的是主库核心表 `rcontact`，微信启动后几秒内必查（比 `SnsGuard` 找朋友圈 `SnsInfo` 早得多），几乎不可能在拿到句柄前耗满 2 万次。真机只需看日志是否出现 `"CSQL capture gave up after 20000"`，出现即异常。

---

## 已核实无问题的点

1. **unhook 在回调内部调用**：`ContactSqlSource.kt:169-175` 与 `SnsGuard.kt:364-370` 逐字同构，SnsGuard 已真机跑通，Xposed 允许在 `beforeHookedMethod` 内 `unhook()` 自身，无 `ConcurrentModificationException`/死锁（`synchronized(captureUnhooks)` + 先 `clear` 再遍历）。
2. **`getPath` 重入/递归**：`ContactSqlSource.kt:145-167` 只在 `sql.contains("rcontact")` 之后才调 `callMethod(db,"getPath")`；`getPath` 只返回 `mPath` 字段、不发 rawQuery，不会重入钩子。
3. **`param.thisObject` / `args[0]` 类型**：`rawQuery` 是实例方法，`thisObject` 必为 `SQLiteDatabase` 实例；`hookAllMethods(cls,"rawQuery")` 覆盖的 `rawQuery(String,String[])`/`rawQuery(String,Object[])`/带 `CancellationSignal` 的重载，`args[0]` 均为 SQL 字符串，`param.args?.getOrNull(0) as? String` 安全降级。
4. **反射读游标**：`getColumnIndex(String)`、`moveToNext()`、`getString(int)`、`getLong(int)`、`close()` 均为 `android.database.Cursor` 接口标准方法，wcdb 游标实现了该接口；`XposedHelpers.callMethod(...) as? Boolean == true` 对 `java.lang.Boolean` 成立（kotlin.Boolean 运行时即 java.lang.Boolean），返回 null（方法不存在）时 `== true` 为 false 安全退出。`while (guard++ < READ_MAX_ROWS && moveToNext...)` 短路顺序正确，未读完 `close()` 不抛异常（`Cursor.close()` 幂等）。整套写法与 `SnsGuard.readRow`（`SnsGuard.kt:416-478`）同构。
5. **非主线程写 `GroupDb`**：`doSupplement` 在 `"wxgroup-csql"` 线程里调 `GroupDb.current()/upsertFriends/countFriends/friendTypeCounts`；`GroupDb` 是 AOSP `SQLiteOpenHelper`，`getWritableDatabase()` 线程安全，`instance` 用 `synchronized` 创建（`GroupDb.kt:56-64`），且模块库 `wxgroup.db` 不走 wcdb（`GroupDb.kt:174-176` 注释），不会触发挂在微信库上的钩子。
6. **`bindSoftInput` 的 listener 覆盖**：实际只有 4 个调用点（`PickerPage.kt:70`、`BatchPicker.kt:80`、`ForwardPicker.kt:112`、`GroupDetailPage.kt:101`），四处 `EditText(ctx).apply{...}` 内部及之后都只用 `addTextChangedListener`，没有任何一处再 `setOnClickListener`/`setOnFocusChangeListener`，无覆盖 bug。
7. **`ContactFilterLogic.kt` 零 Android 依赖**：文件仅 `package com.lx.wxgroup.data`，无任何 `android.*`/`xposed.*` import；`RcontactRow`（`ContactFilterLogic.kt:84-92`）是纯 data class，`shouldSupplementFromRcontact`（`119-120`）纯函数，可跑 JVM 单测。
8. **`enum Kind` → `GroupDb.WxidType` 映射穷举**：`Friend.kt:35-40` 的 `when` 覆盖 `CHATROOM/OFFICIAL_ACCOUNT/FRIEND/UNKNOWN` 全部 4 个枚举值，无遗漏。
9. **`Array<String>::class.java` / `Array<Any>::class.java` 匹配**：`Array<String>::class.java == String[].class`、`Array<Any>::class.java == Object[].class`，分别匹配 wcdb 的 `rawQuery(String,String[])` / `rawQuery(String,Object[])`；`doSupplement` 里按 `m.parameterTypes[1]` 分派构造 `args`（`ContactSqlSource.kt:208-212`），与 `SnsGuard.kt:418-422` 完全一致。
10. **单测引用完整性**：`ContactFilterLogicTest.kt` 用到的 `ContactFilter.RcontactRow` / `shouldSupplementFromRcontact` 均已定义（`ContactFilterLogic.kt:84/119`），与 `ContactSqlSource.kt:243/253` 的实际调用一致。

## 多余改动（无害，但说明对根因的理解有偏差，供后续修正认知）

- **`BatchPicker.kt:124`、`GroupPicker.kt:122`、`Rows.kt:378`、`UiKit.kt:258` 的 `clearAltFocusableIm` 均多余**：这四处的 `AlertDialog.Builder.setView(root)` 传入的 `root` 在 `create()` 之前**已经**把 `EditText` addView 进去，`installContent()` 里 `canTextInput(root)` 会递归命中 EditText → 返回 true → **根本不会设置** `FLAG_ALT_FOCUSABLE_IM`（见 AOSP `installContent()` 的 `if (mView == null || !canTextInput(mView))`）。所以这些 dialog 的搜索框/输入框本来就能弹键盘，清 flag 是无操作。
- **`GroupDetailPage.kt:151` 的 `clearAltFocusableIm` 多余**：该页用裸 `Dialog(ctx)`（`GroupDetailPage.kt:133`），而 `FLAG_ALT_FOCUSABLE_IM` 是 **`AlertDialog`（经 `AlertController`）** 才有的行为（官方文档原文：*"The AlertDialog class takes care of automatically setting FLAG_ALT_FOCUSABLE_IM for you"*），裸 `Dialog` 不设置此 flag。
- 附带提示：`UiKit.kt:194-196` 的 KDoc「show() 之前窗口还没 addView ⇒ 此时改 flag 最干净，不会有『先带 flag 加窗再清』竞态」是**错误认知**，正是它把 `Panel.kt` 的清 flag 位置带偏。真实情况是 `AlertController` 在 `show()` 内会重新 setFlags，见阻断级 #1。

---

## 真机验收时最该盯的 3 件事

1. **群发「选择发送目标」页（ForwardPicker/PickerPage，走 Panel）搜索框是否真弹键盘**：这是问题 1 的验收判据，也正是阻断级 #1 的靶点。若真机仍 `focused=true / mInputShown=false`，先确认 `Panel` 的 flag 是否在 `show()` 后清掉。
2. **`@openim` 补充是否在启动后几秒内完成**：盯 `LogX` 里的 `"CSQL EnMicroMsg.db captured via rawQuery"` 与 `"CSQL supplement: read=254 upsert=... db 2257 -> 25xx"`。若只看到 capture 没有 supplement，或出现 `"CSQL capture gave up after 20000"`，即功能静默失效，需查 hook 时序/并发读。
3. **并发补充读是否引起任何卡顿/异常**：在 capture 命中后观察是否有 SQLite 连接等待、日志延迟或 ANR 迹象（对应风险级 #2），确认 wcdb 该版本对「before 阶段立即反射并发读同实例」的容忍度。
