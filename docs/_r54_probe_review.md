# R54 伪会话写库探针 · 只读复核

- 对象：`probe/GroupConvProbe.kt`（592 行）、`EntryReceiver.kt` 的 `gconv` 分支（diff 新增 :191-203）。
- 参照：`LabelHuntProbe.kt:466/1090/1026`、`AntiRevoke.kt:828`（`wxDb()=msgDb`）；真机模板 `docs/派发审计台账.md:3964-3969`、`docs/_r53_microx_grouprow.md:293-321`。
- 方式：只读复核（Read / Grep / git diff），未改源码、未跑 gradle、未碰 adb；编译层以主会话 `assembleDebug --offline` EXIT=0 为准。
- **快照时点**：下方 9 条判定基于 **592 行 / 修复前** 版本；代码侧随后修过一轮（现 599 行），修复状态见文末「复核后状态更新」，**排期请以文末修订 verdict 为准**。
- 等级 = 该条发现的最严重问题等级；完全 PASS 的条目标 P2（无阻塞）。

## 逐条（等级 · 结论 · 证据行号）

1. **P2 · PASS** clean 两表 DELETE 都带 `OWN_WHERE=substr(username,1,6)='wxgrp_'`（:70 / :358-359），用 `substr` 而非 `like` 避开 `_` 通配，且前后各打一次「非本方行数」自证（:347-348 / :363-374）；例外是 trial 清旧行（:195,197）、rename UPDATE（:326）改用精确 `username='wxgrp_t1'`，不带 substr，但 user 恒为我方常量，不误伤他人行。
2. **P2 · PASS** `q()` 正确 `'`→`''`（:570），`--es val` 经 `q(v)`（:326）拼进 UPDATE，注入串如 `x'; drop table …` 被包成单个合法字面量，改不到别的表/行；`--es sql` 不是拼进 SQL，而是整条经闸门后 rawQuery（:406），不经过任何写语句拼接，无注入口子。
3. **P2 · PASS** `isReadOnly`（:381-393）与 `LabelHuntProbe`（:466-476）逐字一致：`with … delete` / `attach` / 注释开头都不以 select|explain|pragma 开头被拒，`pragma` 另查白名单 + 挡 `=`（:387-392），`load_extension` 被挡（:385）；`select … ; drop` 依赖「rawQuery 只 prepare 首条语句」，该论据来自 AOSP（LabelHuntProbe:463-464），**wcdb 侧未在本仓库验证**，建议补「分号后非空即拒」。
4. **P2 · PASS** 全文 db 一律 `Any`、无 AOSP 强转；`query()`（:457-468）每次重扫 `rawQuery(String,String[]|Object[])` 不缓存 Method、`m.invoke(db,sql,null)` 参数类型正确；`exec()`（:471-484）扫 `execSQL(String)` 单参 + `XposedHelpers.callMethod` 兜底；`readAll`（:508-524）用 callMethod 读 Cursor 且 `.also { close(cur) }` 必关；`msgOf`（:493-497）正确解包 `InvocationTargetException.targetException`，写路径 `execTry`（:487-490）能看到真实 SQLiteException。备注：`query()` 读路径异常被 `getOrNull()` 静默吞（参照 :1105-1107 有日志），读失败时 `countOf` 得 -1，会让 clean 的「非本方行不变」自证变成假 PASS；`exec()` 兜底依赖 wcdb 存在单参 `execSQL`。
5. **P1 · FAIL** trial（:193-194）/ clean（:355-356）写前都调 `backupRows`，`f.appendText`（:429）是真写盘到 `filesDir/gconv_backup.txt`（:443-447），不是只打日志；但 **rename（:319-338）是写库路径却完全没有备份调用**，单独执行 rename 后旧 nickname 不可恢复。另：备份只有 `col=val` 文本（:434）、无可执行恢复语句 / 列类型，BLOB 经 `getString` 失真，`runCatching`（:427）吞写盘失败不打日志，clean 后只能人工手拼 SQL 恢复（无 restore 子命令），信息勉强够。
6. **P2 · PASS** EntryReceiver:194 `sub ?: "stat"` → `run()`（:105-116）只走 stat / 打日志；不带 `sub` 无任何写库路径；`wxDb()` 为 null 直接返回（:100-103）。
7. **P2 · PASS** `io{}`（EntryReceiver:89-95）起 Thread + runCatching、`onReceive`（:114-115）runCatching、`run()`（:104-120）try/catch Throwable 三层兜住，打不挂广播线程；但探针在后台线程裸 `execSQL`，无 `beginTransaction`、无 `SQLITE_BUSY` 重试 / busy_timeout，trial 的 delete+insert（:195-201）非原子，撞微信写事务会失败或留半状态（均限我方前缀、clean 可收尾，不污染他人数据）；`OWNERSHIP_STAMP`（:72）共享 `SimpleDateFormat` 非线程安全。
8. **P2 · PASS** imports 齐全、`AntiRevoke.wxDb()` 为 internal 同 module 可调、`` `val` `` 反引号参数在调用方用位置参数（EntryReceiver:199）对得上；`PIN_BASE+time` 不溢出 Long、`defaultLiteral` / `cols.first`（:300）不会抛；仅运行时边界：`runSql` 的 `take(n)`（:408）未 coerce，`--ei n -1` 会抛但被 `run()` catch 兜住（同 R53 复核 P2-3）。
9. **P0 · FAIL** 模板值全部与实测吻合：`flag=2^62+conversationTime`（:53 / :234，台账:3968 逐位吻合）、`msgType=1/status=3/chatmode=1/isSend=1`（:236，台账:3965-3967）、20 列清单（:62-67）与 `_r53_microx_grouprow.md:160-162` 一致、`showHead=87/type=0`（:277-278，台账:3969）；`insertConv` 两段式（:235-258，先 7 列失败再全 20 列，`execTry` 返回 null=成功）逻辑正确。**但 `insertContact` 的 minimal 只写 `username/type/showHead`（:290-292），把 `lit["nickname"]`（:278）漏掉** → t1 的「测试组A」在首选路径根本不落库，t1/t2 的 rcontact 行完全一样，trial「唯一差别就是 nickname」（:165-166）的实验设计失效，会得出「nickname 不生效」的假阴性，整轮白跑。修法：把 `nickname` 加进 `minimalCols`。

## 额外发现

- **P2** `stat` 的 `substr(username,1,7)='wx__c_'`（:154）前缀长度错：`wx__c_` 实测 6 字符，前 7 字符为 `wx__c_1`，恒不相等 ⇒ `pinWx` 永远 0，「置顶区里微X 行数」判据失效；应为 `substr(...,1,6)`。
- **P2** 探针只取 `AntiRevoke.wxDb()` 单库（:99），未像 `LabelHuntProbe.allDbs()/dbWith()`（:1026 / :1037）校验 rconversation/rcontact 是否在该库；trial 有 `columnsOf` 空即中止（:180-183）兜底，clean/stat 无（表不存在时 `execTry` 报 `no such table` 不崩、不会误删）。

## 复核后状态更新（本人重新 Read / Grep 核实，非转述）

代码侧已修复一轮，探针现 **599 行**。以下按当前文件行号复核：

- **P0（第 9 条）已闭环**：`minimalCols` 现为 `listOf("username","type","showHead","nickname")`（:292，:290-291 附说明注释），`lit["nickname"]`（:278）在首选路径即生效，t1=「测试组A」/ t2=`''` 的对照恢复判别力。
- **P1（第 5 条）已闭环**：`rename()` 在 UPDATE（:330）**之前**新增 `backupRows(db, CONTACT, user)`（:329，附「复核 P1」注释），单独跑 rename 的旧 nickname 已有备份线索。
- **额外发现 1 已闭环**：`pinWx` 现为 `substr(username,1,6)='wx__c_'`（:154），前缀长度与 6 字符常量一致。
- **仍成立（P2，不阻塞）**：`backupRows()`（:428-447）仍是 `appendText` 的 `col=val` 纯文本、无可执行恢复语句 / 列类型；`run()` 的 sub 白名单仍为 `stat|trial|rename|clean|sql`（:105-116），**无 `restore` 子命令**；`runCatching`（:434）仍吞写盘失败不打日志 ⇒ 备份只是人工还原线索，不是可恢复备份。
- **已复核无注入面**：`q()` 现位于 :577，仍是 `s.replace("'", "''")`；`rename --es val` 走 `q(v)`（:330）。
- **未变的 P2 备注**：wcdb 多语句假设（第 3 条）、`query()` 读路径吞异常（第 4 条）、并发无事务 / 无 busy 重试、`SimpleDateFormat` 共享（第 7 条）、`take(n)` 负数（第 8 条）、单库未校验（额外发现 2）—— 本轮修复未触及，仍按原判定。
- **新引入的轻微注意点（P2）**：`minimalCols.filter { it in names }`（:292）依赖运行时 pragma 真有 `nickname` 列；若真机 `rcontact` 无此列会被静默过滤、判别力再次丢失。建议每次 trial 前先 `sub=sql` 回读 `length(nickname)`（t1>0 / t2=0），两行相同则本轮 trial 作废 —— 与 `docs/_r54_gconv_design.md` §2 前置校验口径一致。
- 本轮修复均为局部改动（2 行注释、1 行 backupRows、1 处 substr 长度、minimalCols 加 1 列），未跑 gradle（复核 lane 约束），未见明显编译风险；编译状态以主会话为准。

## verdict（修订）

- 数据安全层面：所有写仍限 `wxgrp_` 前缀，闸门 / 转义 / 反射 / 三层兜底未变 ⇒ **可上机**。
- 取证有效性：P0、P1 已由代码侧修复并经本人复核，剩余全部为 P2 ⇒ **需先修 0 条**。

`verdict=可上机`（原判定「需先修 2 条」已失效：P0×1 / P1×1 均已闭环；剩余 P2 不阻塞。置顶位模板不受修复影响，可直接引用）
