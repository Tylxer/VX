# R21c · VoiceGuard 修复「防撤回语音后播放失败」—— SQL 形态 / 时序 / hook 覆盖性 裁决

> lane：`voice-sql-form`（纯 DEX 反汇编取证，不碰 adb、不改源码、不跑 gradle）
> 裁决对象：`wxgroup/.../data/VoiceGuardLogic.kt` 的 `shouldBlock()` + `feature/VoiceGuard.kt` 的 hook
> 唯一结论先行（详见 §4）：**「有效」，但推翻 team-lead 前提里「撤回走子查询」的判定**。
> 撤回删 `voiceinfo` 真实走的是**单行参数化 `FileName=?`**，且经 `SQLiteDatabase.delete()`（不是 execSQL），
> 时序上 **DELETE 在 UPDATE（record）之后** —— 现有方案 A 的三条前提（形态/入口/时序）全部命中。

---

## §1 真实 DELETE 形态

**结论：微信 8.0.48 里 `voiceinfo` 的删除有且仅有两条代码路径，形态不同、用途不同：**

| # | 形态 | SQL / 参数 | 走哪个入口 | 用途 | 撤回用不用 |
|---|------|-----------|-----------|------|-----------|
| **A** | 子查询 | `FileName IN (SELECT imgPath FROM message WHERE talker IN (...))` | `SQLiteDatabase.execSQL()` | **整会话删除**（删聊天/清空记录） | ❌ 不用 |
| **B** | 单行参数化 | `FileName= ?` + `whereArgs=[26位hex imgPath]` | `SQLiteDatabase.delete()` | **单条消息删除**（含撤回） | ✅ **用这条** |

### 1.1 路径 A：子查询（整会话删除，`deleteMsgByTalkerSync`）

- 字符串（classes4.dex，string_id 6943，逐字）：
  `'DELETE FROM voiceinfo WHERE FileName IN (SELECT imgPath FROM message WHERE talker IN '`
- 引用者：`Lvj0/a9;->j`（签名 `j(Ljava/util/List;Lvj0/j9;JJLcom/tencent/wcdb/support/CancellationSignal;)I`）
- 该类方法体内 const-string `'MicroMsg.MsgInfoStorageLogic'` + `'deleteMsgByTalkerSync '` ⇒ `Lvj0/a9` = `MsgInfoStorageLogic`，`j` = `deleteMsgByTalkerSync`。
- 完整 SQL 由 StringBuilder 拼出（反汇编地址逐字）：
  ```
  [330] const-string 'DELETE FROM voiceinfo WHERE FileName IN (SELECT imgPath FROM message WHERE talker IN '
  [332] append v6                       ← v6 = "(talker1,talker2,...)"（talker 列表内联成字面量，见 [82]-[118] TextUtils.join）
  [338] append v5 = ' AND type = '
  [341] const/16 #34 → append           ← type = 34
  [346] append v4 = ' AND message.createTime >= ? AND message.createTime < ?)'
  [353] invoke-virtual SQLiteDatabase->execSQL(sql, bindArgs=[Long(start),Long(end)], cancellationSignal) V
  ```
  ⇒ 最终 SQL：`DELETE FROM voiceinfo WHERE FileName IN (SELECT imgPath FROM message WHERE talker IN (<talker字面量>) AND type = 34 AND message.createTime >= ? AND message.createTime < ?)`
- **调用点**：`j` 只有 1 个调用者 `Lib1/a->run()`；其外层 `Lvj0/a9->h(String,Lvj0/h9;)`（单 talker 删除）有 34 个调用者，**全部是 UI「删会话/清空聊天记录」入口**（`ui/conversation/*.onClick`、`ui/ng.onClick`、`ui/pg.onClick`、`chatroom/ui/p.onClick`、`dd4/s.onMMMenuItemSelected` 等）。**没有任何一个是撤回链路。**

### 1.2 路径 B：单行参数化（撤回走这条）

调用链（每层都有反汇编实证）：

```
撤回 vj0.s.i（doRevokeMsg）
  → 调度 vj0.p（deleteLocalFile）
  → vj0.a9->e(h9,true)
  → modelbase.r0.Ab(p0)   = 分发 onPreDelMessage
  → Lem0/x0->Ab           = VoiceMsgExtension.onPreDelMessage
  → Lem0/v0->f(imgPath)   = deleteRecordFile
  → Lem0/e1->f(imgPath)   = VoiceStorage 删除记录
  → z84.z->delete('voiceinfo','FileName= ?',[imgPath])
  → SQLiteDatabase.delete('voiceinfo','FileName= ?',[imgPath])
```

关键反汇编逐字引用：

- `Lem0/e1;->f(Ljava/lang/String;)Z`（classes7.dex，类日志 `'MicroMsg.VoiceStorage'`）：
  ```
  [14] const-string        v0, 'FileName= ?'          ← whereClause（注意 = 后有空格）
  [16] filled-new-array {v7} ... → v3                  ← whereArgs = [fileName]
  [22] const-string/jumbo  v5, 'voiceinfo'
  [25] invoke-virtual {v4,v5,v0,v3}, z84.z->delete()I  ← delete('voiceinfo','FileName= ?',[fileName])
  [29] if-gtz v0 → log 'delete failed, no such file:'
  ```
- `z84.z$$c.a()`（`z84.z.delete` 的实际执行体，classes10.dex）：
  ```
  [34] invoke-virtual {v4,v5,v0,v1}, com.tencent.wcdb.database.SQLiteDatabase->delete()I
  ```
  ⇒ `z84.z.delete()` **最终落到 `SQLiteDatabase.delete()`**，不是 execSQL。
- `Lem0/v0;->f(Ljava/lang/String;)Z`（`deleteRecordFile`，类日志 `'MicroMsg.VoiceLogic'`）：
  ```
  [ 0] log 'deleteRecordFile() called with: fileName = [...]'
  [33] invoke-static {} em0.p0->F8()em0.e1      ← 拿 VoiceStorage DAO
  [37] invoke-virtual {v0,v9} em0.e1->f()Z      ← 删 voiceinfo 行（FileName=?）
  [40] invoke-static {v9} em0.v0->d()V          ← 再删 VFS 文件本体
  ```

### 1.3 排除「第二条参数化 DELETE」与「execSQL 旁路」

全 15 个 dex 搜 `voiceinfo` 字符串，仅命中：
- classes4.dex `[6943]`（路径 A 的子查询 DELETE）
- classes7.dex 17 条：`CREATE TABLE` / `Alter table voiceinfo add ...`（×8）/ `CREATE INDEX`（×2）/ `CREATE UNIQUE INDEX` / `SELECT FileName,...FROM voiceinfo WHERE ...`（×4，其中 `FileName=?`/`MsgId=`/`MsgLocalId=` 三种查询）/ `PRAGMA table_info` / 表名 `'voiceinfo'`
- classes10.dex `[36894]` `'SELECT SUM(VoiceLength) FROM voiceinfo,message ...'`（统计）

表名 `'voiceinfo'` 的引用者：`Lem0/e1`（`<init>`/`P0`=insert/`f`=delete）、`Lem0/v0->x`（update 记录，日志 `'update failed, no values set'`）。
⇒ **`voiceinfo` 的写路径只有：INSERT（e1.P0）、UPDATE（v0.x）、DELETE 单行（e1.f）、DELETE 子查询（a9.j）。** 没有第二条参数化 DELETE，也没有别的 execSQL 删 voiceinfo 的路径。

[DONE-§1]

---

## §2 时序结论：DELETE 在 UPDATE（record）之后

**结论：撤回语音时，`voiceinfo` 的 DELETE 被排程在 `UPDATE message`（`j9.va`）之后，且是异步 runnable。**
即：**先被 `AntiRevoke` 拦下 UPDATE 并 `record()` 进 `rescued` 表，之后才轮到 DELETE 执行** —— 方案 A 的时序前提成立。

### 2.1 撤回主流程（`vj0.s.i` = `doRevokeMsg`）内的顺序

- `Lvj0/s;->b9`（类日志 `'MicroMsg.BigBallSysCmdMsgConsumer'`）是 revokemsg 系统消息消费者：
  ```
  [467] const-string 'revokemsg' → equals
  [475] log 'mm hit MM_DATA_SYSCMD_NEWXML_SUBTYPE_REVOKE'
  [538] invoke-virtual {v1..v8} vj0.s->i()V   ← 调 doRevokeMsg
  ```
- `Lvj0/s;->i`（日志 `'doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s'`）内的顺序（地址单调递增，逐字）：
  ```
  [493] const/4 v5, #4
  [497] m7->N0(...) / [506] g1('') / [512] setType(...)   ← 改内存态
  [515] vj0.a9->n(h9)                    ← fixRecvMsgWithAddMsgInfo（无 voiceinfo 操作）
  [532] com.tencent.mm.storage.j9->va()V ← ★ UPDATE message（AntiRevoke 拦这里）
  [537] new RevokeMsgEvent
  [560] IEvent->d()Z                     ← 发 RevokeMsgEvent
  [568] y70.k1->e() → v3 (executor)
  [576] new vj0.p(s, h9)                 ← deleteLocalFile runnable
  [579] v3->j(v0, v1)                    ← ★ 排程 deleteLocalFile（在 [532] 之后）
  ```
- `j9.va(J,h9)` 本体（`Lcom/tencent/mm/storage/j9`，classes4.dex）**只做 UPDATE，不碰 voiceinfo**：
  ```
  [48] h9->convertTo() → ContentValues
  [72] const-string 'msgId=?'
  [74] z84.z->c(table, values, 'msgId=?', [msgId]) I   ← update（z84.z.c → SQLiteDatabase.update → updateWithOnConflict）
  ```
  这正是 `AntiRevoke` 钩在 `updateWithOnConflict` 上的那条写入。

### 2.2 DELETE 的执行体（异步，必然晚于 record）

- `Lvj0/p;->run()`（deleteLocalFile，日志 `'[deleteLocalFile] id:%s type:%s serverType:%s'`）：
  ```
  [57] const/16 #34 → if-eq（type==34 语音）
  [84] vj0.a9->e(h9, true)
  ```
- `Lvj0/a9;->e(h9,Z)`：
  ```
  [ 0] if-nez v3（h9==null 则 return）
  [39] modelbase.r0->Ab(p0)V    ← 分发 onPreDelMessage（只要 r0!=null 就必执行，bool 不 gate 它）
  [42] if-eqz v4 → [72]        ← bool 只 gate 后面 DeleteMsgEvent 的发布，不影响 [39]
  [69] IEvent->d()Z            ← 发 DeleteMsgEvent（删文件本体）
  ```
- `Lem0/x0;->Ab`（`onPreDelMessage`，日志 `'onPreDelMessage '`）内的判据：
  ```
  [39] h9->C0() → v0           ← C0() = talker（jj.m7->C0()String）
  [43] h4->J3(v0)Z
  [47] if-eqz v0 → [50]        ← J3==false 才删
  [50] h9->o0() → v4           ← o0() = imgPath（jj.m7->o0()String）
  [54] em0.v0->f(v4)Z          ← deleteRecordFile → voiceinfo 行删除
  ```
- `h4.J3(String)Z`（classes4.dex）本体：
  ```
  [2] const-string 'gh_22b87fa7cb3c'
  [4] str.equalsIgnoreCase('gh_22b87fa7cb3c')
  ⇒ J3 仅当 talker == 官方号 'gh_22b87fa7cb3c' 时为 true
  ```
  ⇒ 普通会话（非该官方号）撤回语音，`J3` 返回 false ⇒ **删除照常执行**。

### 2.3 结论与方案 A 的关系

- DELETE 的入口 `vj0.p` 在 `j9.va`（UPDATE）**之后**才被 `executor.submit`，且 run 在独立线程。
- `AntiRevoke` 在 UPDATE 的 hook 里**同步**完成 `param.result=FAKE_ROWS` + `RevokeRescue.record(msgId,...)`。
- ⇒ 当 `deleteLocalFile` 真正执行 `SQLiteDatabase.delete('voiceinfo','FileName=?',[imgPath])` 时，`rescued` 表里**已经存在**该 msgId 的 `orig_type=34` 行；VoiceGuard 反查 `message.imgPath` 也因 UPDATE 被拦而**原样未动**。
- ⇒ **「DELETE 恒在 record 之前、只发一次」这个让方案 A 作废的假设，被反汇编证伪。**

[DONE-§2]

---

## §3 hook 点覆盖性：delete / deleteWithOnConflict / execSQL 三路逐一核

**结论：**
| 入口 | wcdb 是否有此方法 | 撤回删 voiceinfo 走不走 | VoiceGuard 挂没挂 | 撤回场景能否命中 |
|------|------------------|----------------------|------------------|----------------|
| `delete(table,where,args)` | ✅ 有（唯一一个 delete 重载） | ✅ **走**（§1.2 路径 B） | ✅ 挂了 | ✅ **命中** |
| `deleteWithOnConflict(...)` | ❌ **不存在** | — | ⚠️ 挂了但返回空 | 不适用 |
| `execSQL(...)` | ✅ 有（3 个重载） | ❌ 只用于整会话删除（§1.1 路径 A） | ❌ 没挂 | 不适用（撤回不走它） |

### 3.1 `deleteWithOnConflict` 在 wcdb 里根本不存在

`com.tencent.wcdb.database.SQLiteDatabase`（classes10.dex，super=`SQLiteClosable`）方法表里与写库相关的是：
```
delete(String,String,String[])I
deleteDatabase(File)Z
insert(String,String,ContentValues)J
insertOrThrow(String,String,ContentValues)J
insertWithOnConflict(String,String,ContentValues,I)J
update(String,ContentValues,String,String[])I
updateWithOnConflict(String,ContentValues,String,String[],I)I
execSQL(String)V  /  execSQL(String,Object[])V  /  execSQL(String,Object[],CancellationSignal)V
rawQuery(...)  × 2
```
⇒ **没有 `deleteWithOnConflict`**（与 AOSP 不同）。`VoiceGuard.install()` 里
`hookAllMethods(cls, "deleteWithOnConflict", ...)` 会返回空集，`ok` 列表里**不会**出现
`deleteWithOnConflict`，实际只装 `delete` 一个 hook。日志将形如 `VG installed hooks=delete enabled=true`。

### 3.2 `delete()` 本体：不是转调 deleteWithOnConflict，而是拼 SQL + executeUpdateDelete

wcdb `SQLiteDatabase.delete(String,String,String[])` 反汇编（classes10.dex）：
```
[ 0] const-string ' WHERE ' / [2] 'DELETE FROM '
[ 4] SQLiteClosable->acquireReference()
[ 7] new SQLiteStatement
[ 9] new StringBuilder('DELETE FROM ') .append(table)
[17] TextUtils.isEmpty(whereClause) ? '' : (' WHERE ' + whereClause)
[45] new SQLiteStatement(db, sql, whereArgs)
[48] SQLiteStatement->executeUpdateDelete()I   ← 真正的执行点
[52] close / [55] releaseReference
```
⇒ 三点关键：
1. `delete()` 是**纯 Java 方法**（非 native），`beforeHookedMethod` 里 `param.result=1` 会跳过整个方法体 ⇒ `executeUpdateDelete()` 不执行 ⇒ 删除被真正拦下。与 `AntiRevoke` 拦 `updateWithOnConflict` 同款机制，成立。
2. `delete()` **不调 `deleteWithOnConflict`**（因为不存在）⇒ 不会出现「第一个 hook 置 result、第二个又判一次」的双重处理。VoiceGuard 代码注释里「delete() 内部转调 deleteWithOnConflict」是**对 AOSP 的误记**，但对 wcdb 的效果恰好无害：只挂到一个 hook。
3. `whereClause='FileName= ?'`（`=` 后有空格）会被 `VoiceGuardLogic.EQ_OR_IN`（`(?:filename|msglocalid)\b\s*(?:=|\bin\b)`）命中；`whereArgs=[26hex]` 被 `hexTokensFrom` 抽出 26 位 hex 与 `rescuedImgs` 求交集 ⇒ `shouldBlock` 返回 true。

### 3.3 `execSQL()`：撤回不走，但整会话删除走它（不拦是正确保守）

`deleteMsgByTalkerSync`（§1.1 路径 A）用 `execSQL(sql, bindArgs, cancellationSignal)`。
VoiceGuard 只挂 `delete`/`deleteWithOnConflict`，**没挂 `execSQL`** ⇒ 这条子查询 DELETE **不会被拦**。
这恰好符合 `shouldBlock` 第 4 步「子查询一律放行」的设计意图，且这条路径只用于「删会话/清空记录」，
不拦它是**正确**的（否则微信删会话时 voiceinfo 清不掉、存储只增不减）。

### 3.4 覆盖性小结

- 撤回语音的 voiceinfo 删除 = 路径 B = `SQLiteDatabase.delete()` ⇒ **`delete` hook 必然命中**，且只命中一次。
- 唯一「绕过 delete 走 execSQL」的 voiceinfo 删除是路径 A（整会话删除），与撤回无关。
- ⇒ hook 覆盖性对撤回场景是**完备**的。

[DONE-§3]

---

## §4 对现有实现的裁决：**有效**

**明确选一个：有效。**

三条前提全部被反汇编证实（不是「猜」）：

| 前提 | 反汇编结论 | 现有实现是否覆盖 |
|------|-----------|----------------|
| ① 撤回删 voiceinfo 走「参数化 `FileName=?`」 | ✅ §1.2：`Lem0/e1->f` → `z84.z.delete('voiceinfo','FileName= ?',[imgPath])` | ✅ `EQ_OR_IN` 正则认 `FileName=`；`hexTokensFrom` 认 26 位 hex |
| ② 撤回删 voiceinfo 走 `delete()`（非 execSQL） | ✅ §3.2：`z84.z$$c.a` → `SQLiteDatabase.delete()` | ✅ hook 挂在 `SQLiteDatabase.delete` |
| ③ DELETE 在 UPDATE（record）之后 | ✅ §2：`j9.va`(UPDATE, 被拦+record) → 之后才排程 `deleteLocalFile` | ✅ `rescued` 行已存在，`rescuedImgs` 非空 |

而 team-lead 前提里「撤回走子查询（`FileName IN (SELECT imgPath ...)`）」这条被**证伪**：
那条子查询 DELETE 属于 `deleteMsgByTalkerSync`（`Lvj0/a9->j`），唯一调用链是「删会话/清空记录」的 34 个 UI 入口，
与撤回（`vj0.s.i`）无任何调用关系（§1.1）。

### 4.1 有效性的边界（哪些情况仍可能失效，需真机确认）

1. **`record()` 是否真把语音的 `orig_type=34` 写进 `rescued`**：team-lead 已真机确认（6 条 orig_type=34），此处不再重复取证。若 `record()` 因故没写，`recentVoiceMsgIds()` 查不到 ⇒ 失效。
2. **`imgPathsOf()` 反查 `message.imgPath` 是否拿到 26 位 hex**：依赖 `AntiRevoke.wxDb()` + rawQuery；UPDATE 被拦后 imgPath 原样在，理论上必中。真机要看 `VG blocked` 日志里的 args 是否真是 26hex。
3. **60s 时间窗**：DELETE 由 `vj0.p` 在 UPDATE 之后立刻（毫秒级）执行，远小于 60s，无风险。
4. **`rescued` 行 `at` 字段**：`recentVoiceMsgIds()` 用 `at > now-60s` 过滤；若 `record()` 写的 `at` 是 0 或异常，可能被过滤掉。真机要看 `AR blocked revoke` 与 `VG blocked voiceinfo delete` 是否成对出现。

### 4.2 确认方法（一锤定音，无需改代码）

撤回一条**他人**语音后，看模块日志是否成对出现：
```
AR blocked revoke msgId=<id> self=false      ← 先（UPDATE 被拦 + record）
VG blocked voiceinfo delete where=FileName= ? args=<26hex>   ← 后（DELETE 被拦）
```
再查库验证 `voiceinfo` 行仍在（§6 命令）。**两条日志都在 ⇒ 有效实锤。**

### 4.3 附带澄清（不改代码，但值得写进结论）

- VoiceGuard `install()` 里 `deleteWithOnConflict` 那个 hook 在 wcdb 上是**死代码**（找不到方法），
  `ok` 永远只含 `delete`。无副作用，但日志「VG installed hooks=...」里**不会出现 deleteWithOnConflict**，别据此误判「hook 没装全」。
- `shouldBlock` 第 4 步「子查询放行」**保留正确**：那条子查询只在删会话时出现，放行才不破坏微信 GC。

### 4.4 ⚠️ 独立缺陷：3 秒缓存会把「刚 record 的行」吃掉（不影响主流程，但应修）

team-lead 发现的 `CACHE_TTL_MS = 3_000L`（`VoiceGuard.kt:55`）缺陷，**独立于时序前提成立**，主会话的判断正确：

```
protectedSet():
  if (now - cacheAt < 3000) return cachedImgs   ← 缓存命中
  cachedImgs = buildProtectedSet(); cacheAt = now
```

失效链路（需要一个「先查空、后写库、再重试删」的先后）：
1. 某次**无关**的 voiceinfo DELETE 先到 → 此时 `rescued` 为空 → `buildProtectedSet()` 返回**空集**并缓存 3s
2. 随后撤回的 `record()` 写进 `rescued`
3. 撤回的 `deleteLocalFile` 在 <3s 内重发 DELETE → `protectedSet()` **命中旧空集** → `shouldBlock` 第 3 步 `rescuedImgs.isEmpty()` → 放行

**对本裁决的影响评估（独立判断）**：
- 主流程（正常撤回、此前 3s 内无别的 voiceinfo DELETE）**不受影响**：`cacheAt` 初值 0 ⇒ 首次 `protectedSet()` 必 miss、必 fresh 构建，此刻 `rescued` 已有该行 ⇒ 非空 ⇒ 拦得住。
- 该缺陷只在「撤回前 3 秒内恰好发生过一次 voiceinfo DELETE 且当时 rescued 为空」的窄竞态下才触发。概率低，但**空集缓存零收益、错了代价最大**（漏拦一条语音），语义上「空集」恰等于「什么都没保护」，是最不该缓存的结果。
- ⇒ 维持 §4 裁决「有效」不变，但把该缺陷列为**应修**（修法见 §5，推荐 ②）。

[DONE-§4]

---

## §5 若无效的最小改法（本轮判定有效，本节仅备查）

> 本轮裁决为「有效」，§5 无必做改法。以下仅记录「若真机证伪后」的兜底方向，**当前不要实施**。

1. **若真机发现撤回走的其实是 execSQL 子查询**（与反汇编相悖，仅兜底假设）：
   最小改法 = 在 `VoiceGuard.install()` 追加 `hookAllMethods(cls, "execSQL", guard)`，并在
   `onDelete` 里对 `execSQL` 的首参（完整 SQL）先判 `table` 前缀 `DELETE FROM voiceinfo`，
   再对子查询形态**解析 `talker IN (...)` + 内联的 `SELECT imgPath`**，或退化为「解析子查询拿 imgPath 列表再求交集」。
   代价大、误拦面广，**非必需不要做**。
2. **若真机发现时序反了（DELETE 先于 record）**：
   方案 A 救不回已删行，只能换「快照+回插」或「按 message 行数据重建 voiceinfo 行」
   （`INSERT OR REPLACE INTO voiceinfo(FileName,MsgId,MsgLocalId,Status,TotalLen,FileNowSize,...)`
   由 `message.imgPath/msgId/msgSvrId` 推导）。当前反汇编已排除该情况。
3. **若 `imgPathsOf` 拿不到 imgPath**（message 行被污染）：
   退而用 `MsgLocalId=?` 匹配——在 `shouldBlock` 里让 `rescuedImgs` 同时装 msgId 十进制串，
   `EQ_OR_IN` 已认 `msglocalid` 列，`MsgLocalId=?` 的 args 也能命中。此路**现有代码已实现**（`recentVoiceMsgIds` 直接把 msgId 加进 `out`），无需改。

### 5.1 缓存缺陷（§4.4）的三条修法评估与推荐

| 修法 | 做法 | 优点 | 缺点 | 结论 |
|------|------|------|------|------|
| ① record 后主动 invalidate | `RevokeRescue.record()` 末尾调 `VoiceGuard.invalidateCache()`（反向依赖） | 精确失效，改动点最少命中 | 引入 `RevokeRescue→VoiceGuard` 反向依赖，耦合更紧；且 DELETE 走多线程，即使失效也可能被下一次旧值覆盖 | ❌ 不推荐 |
| ② **空集不缓存** | `buildProtectedSet()` 返回空集时，`cachedImgs` 仍更新但**跳过写 `cacheAt`**（或加 `emptyCacheAt` 单独短 TTL） | 语义最干净：空集=「无保护」，缓存它零收益、漏拦代价最大；改动最小（3 行内） | 无实质缺点（空集场景本来就该每次查库） | ✅ **推荐** |
| ③ TTL=0 | `CACHE_TTL_MS=0`，每次 delete 都查 `rescued` | 彻底无缓存 | delete 是热路径（微信 GC/删会话/正常清理都会触发），每次都 rawQuery `rescued` + 反查 `message`，代价不可忽略 | ⚠️ 可用但不优 |

**推荐 ②**：只对「空集」跳过缓存，非空集仍缓存 3s 抗重试风暴。理由：空集缓存唯一作用是省一次 `rescued` 查询，但那条查询极便宜；而空集缓存的唯一后果是「漏拦刚 record 的语音」，代价最大。非空集缓存仍有正收益（重试风暴不重复查库），保留即可。

> 注：以上为方案评估，**不改 .kt 源码**（改码归实现 lane / 主会话）。

[DONE-§5]

---

## §6 需真机补取的判据清单（主会话可直接执行）

> 目标：把「有效」从反汇编结论升级为真机实锤。全部只读，不装包、不改码。

### 6.1 判据一（最关键）：成对日志

```bash
# 测前清日志（不要 force-stop，见 docs/开发回路速查.md）
adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear -p com.tencent.mm
# 微信拉到前台
adb shell am start -n com.tencent.mm/.ui.LauncherUI
# 让另一账号撤回一条「语音」消息 → 拉日志
python tools/dev.py q 'VG blocked voiceinfo delete' 30
python tools/dev.py q 'AR blocked revoke' 30
```
判据：
- 出现 `VG installed hooks=delete enabled=true`（⚠️ 只有 `delete`，没有 `deleteWithOnConflict`，属正常）
- 出现 `AR blocked revoke msgId=<id> self=false`
- 出现 `VG blocked voiceinfo delete where=FileName= ? args=<26hex>`
- 三条齐 ⇒ 有效实锤；缺第三条而前两条在 ⇒ 回 §4.1 查 record/imgPath 那一环。

### 6.2 判据二：voiceinfo 行仍在（数据层交叉验证）

撤回语音后（防撤回开启）：
```bash
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
  --es op label --es sub on --ei sec 180          # 先开库窗口（见速查 §四的坑）
adb shell am start -n com.tencent.mm/.ui.LauncherUI
# 日志出现 db ready 后再查：
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
  --es op label --es sub sql \
  --es sql "select FileName,Status,TotalLen,FileNowSize from voiceinfo where FileName='<撤回语音的imgPath>'"
```
判据：撤回语音的 `FileName` 行**仍在**（`Status=99`、`TotalLen==FileNowSize`）⇒ DELETE 被拦成功；
对照「关掉防撤回再撤一条」应查不到该行 ⇒ 证明拦截就是那一步生效。

### 6.3 判据三：反证「删会话 ≠ 撤回」，确认子查询路径不误拦

```bash
# 长按删掉/清空一个会话（里面有语音），看日志
python tools/dev.py q 'VG blocked voiceinfo delete' 20
```
判据：**不出现** `VG blocked`（因为删会话走 `execSQL` 子查询，`delete` hook 不命中）；
且该会话的 voiceinfo 确被清空（`select count(*) from voiceinfo where FileName in (select imgPath from message where talker='<talker>')` 变小）。
⇒ 证明子查询放行正确、微信 GC 不被破坏。

### 6.4 判据四：时序先后（日志时间戳）

判据：同一次撤回里，`AR blocked revoke` 的日志行号/时间戳 **早于** `VG blocked voiceinfo delete`。
（反汇编 §2 保证 DELETE 排程在 UPDATE 之后；真机若相反则回 §5.2。）

### 6.5 判据五（兜底诊断）：确认撤回没有第二条 execSQL 删 voiceinfo

若要彻底排除「撤回还有一条 execSQL 旁路」（反汇编已排除，仅兜底）：
可在 `VoiceGuard.install()` 临时加一个 `execSQL` 探针（只打日志不拦），看撤回语音时是否出现
`execSQL DELETE FROM voiceinfo`。**当前无需做**，仅当判据一「有 record、无 VG blocked」时才需要。

---

## 附录：本次反汇编用到的方法签名锚点

| 类（dex） | 方法 | 语义（证据来源） |
|-----------|------|-----------------|
| `Lvj0/a9;`(classes4) | `j(List,j9,JJ,CancellationSignal)I` | `deleteMsgByTalkerSync`（const 'deleteMsgByTalkerSync '） |
| `Lvj0/a9;`(classes4) | `e(h9,Z)V` | 删单条消息媒体：`r0.Ab(p0)`(onPreDelMessage)+`DeleteMsgEvent` |
| `Lvj0/s;`(classes4) | `b9(String,Map,n0)o0` / `i(String,J,n0,String,String,String)V` | 撤回 sysmsg 消费者 / `doRevokeMsg` |
| `Lvj0/p;`(classes4) | `run()V` | `deleteLocalFile`（log '[deleteLocalFile]'） |
| `Lcom/tencent/mm/storage/j9;`(classes4) | `va(J,h9)V` | 撤回 UPDATE（`z84.z.c('msgId=?')`） |
| `Lcom/tencent/mm/storage/h4;`(classes4) | `J3(String)Z` | 仅当 talker=='gh_22b87fa7cb3c' 为 true |
| `Lem0/x0;`(classes7) | `Ab(p0)V` | `onPreDelMessage`（log 'onPreDelMessage '） |
| `Lem0/v0;`(classes7) | `f(String)Z` | `deleteRecordFile`（log 'deleteRecordFile() called with'） |
| `Lem0/e1;`(classes7) | `f(String)Z` | VoiceStorage 删记录：`z84.z.delete('voiceinfo','FileName= ?',[name])` |
| `z84/z;`(classes10) | `delete(String,String,String[])I` → `z84.z$$c.a()` | 最终 `SQLiteDatabase.delete()` |
| `com.tencent.wcdb.database.SQLiteDatabase;`(classes10) | `delete/update/updateWithOnConflict/execSQL` | §3 覆盖性证据；**无 deleteWithOnConflict** |

[DONE]





---

## §7 复核后追加（主会话，2026-10-25 22:3x）

> 本节由主会话在 `vg-review`（Pro 复核代理）返回 PASS 后追加。
> 复核结论：**PASS**，无阻塞真机的缺陷；但复核的 S2 条目把严重度报低了，**它描述的是一条真 bug**。

### 7.1 复核发现的真 bug：3 秒内连撤两条，第二条被漏拦

复核标注为「建议」的 S2，经主会话推演确认是**当前这个 bug 的另一种形态**，不是理论风险：

```
t=0.0  撤回语音 A → record(A)
t=0.1  DELETE(A) → 缓存空 ⇒ 重建 {A} ⇒ 非空 ⇒ 缓存到 t=3.1
t=1.0  撤回语音 B → record(B)                  ← 缓存已陈旧
t=1.1  DELETE(B) → 命中 t=0.1 的缓存 {A} ⇒ B 不在其中 ⇒ 放行 ⇒ B 被删 ⇒ B 播放失败
```

连撤两条语音是常见动作。**因此 §5.1 里「只修空集、保留非空缓存」的判断不成立** ——
当时的理由是「非空缓存有抗重试风暴的正收益」，但复核第 3 点反过来证明了该收益不存在：
`voiceinfo` 的批量清理走 `execSQL`（`deleteMsgByTalkerSync`），而 `VoiceGuard` **没挂 `execSQL`**，
所以 `delete` hook 只接「撤回 / 删单条」这类人工低频路径，不存在重试风暴。

⇒ 收益未证实、代价是真的。已改用**版本号机制**（比单纯「record 后置空」更强，能跨线程消解竞态）：

| 改动 | 位置 |
|------|------|
| `VoiceGuardLogic.cacheUsable` 增加 `cachedGen == gen` 条件 | `VoiceGuardLogic.kt:114` |
| `VoiceGuard.gen` / `cachedGen` + `fun invalidate()` | `VoiceGuard.kt` |
| `protectedSet()` 构建时快照 `gen`、命中时比对该快照 | `VoiceGuard.kt` |
| `RevokeRescue.record()` 成功后调 `VoiceGuard.invalidate()` | `RevokeRescue.kt` |

### 7.2 复核指出的「假绿」用例已修

原 `emptyBuildStampsAlreadyExpired` 只断言 `cacheUsable(...)==false`，而这个 false 由
`cachedSize == 0` 短路保证，**与 stamp 值无关** ⇒ 即使 `stampAfterBuild` 空集错误返回 `now`，该用例仍绿。
已改为**精确断言** `assertEquals(7_000L, stamped)` / `assertEquals(-2_900L, stamped)`。

**变异验证（证明确实钉住了）**：

| 注入的变异 | 结果 |
|-----------|------|
| 删掉 `cachedGen == gen` | `cacheIsStaleAfterNewRecord` **FAILED**（且只有它红）✅ |
| `stampAfterBuild` 空集也返回 `now` | `emptyBuildStampsAlreadyExpired` + `stampAfterBuildAvoidsUptimeZeroTrap` **FAILED** ✅ |

变异已还原，还原后 `tests=632 failures=0`。

### 7.3 复核确认的 4 条 PASS（不再重复取证的依据）

1. `stampAfterBuild` 用 `now - ttlMs` 而非 `0L` **正确**：`elapsedRealtime()` 单调非负，
   减法无溢出风险；`now - ttlMs` 恒为「已过期」，是双保险（`cachedSize > 0` 是第一道）。
2. **首次调用不会误命中**：`cachedImgs` 唯一赋值点只有 `protectedSet()` 且紧跟 `cacheAt` 赋值，
   不存在「`cachedSize > 0` 而 `cacheAt` 仍为 0」的路径。
3. **热路径代价可忽略**：空集时 `recentVoiceMsgIds()` 只查模块自己的小表 `rescued`；
   `imgPathsOf` 在 `msgIds.isEmpty()` 时立即短路（`VoiceGuard.kt:213`），**不会** rawQuery 微信库。
4. **`param.result` 语义成立**：wcdb `delete()` 是纯 Java（拼 SQL + `executeUpdateDelete()`），
   before-hook 置 `param.result` 会跳过方法体并直接返回该值。

### 7.4 残留（已知、暂不改）

- **并发非原子更新**：`cachedImgs` / `cacheAt` / `cachedGen` 三字段各自 `@Volatile` 但非原子成对写。
  反方向交错（陈旧集合 + 新鲜时间戳 + 新鲜 gen）理论可达 ⇒ 极小概率漏拦。
  彻底归零需合成单个不可变快照对象，收益不抵改动面，暂不做。
- **非空缓存在 TTL 内**仍可能漏掉「构建后才 record 的新语音」——`gen` 机制已消除这一条，
  仅当 `record()` 调 `invalidate()` 失败（不可能，纯内存自增）时才会重现。
