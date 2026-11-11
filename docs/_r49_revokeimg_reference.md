# R49 「微X + 网上开源实现」Hook 链路差分取证（lane C，只读）

- 角色：只读取证 lane C；本轮唯一允许写入的文件即本文档 `docs/_r49_revokeimg_reference.md`。
- 硬约束：全只读，禁止改任何源码/测试/配置/其它报告；禁止碰 adb / 真机 / Gradle / 装包 / kvset；禁止整读 >300 行文件（一律 offset/limit）。
- 标记约定：每条结论附 `文件:行号`；不确定处标 `[推断]` / `[需真机]`，不写成事实。
- 项目硬规则：`CLAUDE.md` D1（先查后设计）、D2（动手前必须出路线对照表）、D4（控制优先于观测）。
- 前置结论直接引用：`docs/_r49_revokeimg_forensics.md`（lane B，318 行）——**(c) 部分实现**；三段缺口；`ImgInfo2` 幸存性为 `[推断]`。

[START]

## 待答问题清单（Q1–Q9）
- **Q1** 逐条对账：网上三件套（update 拦撤回 / delete 拦媒体表 / `File.delete` 拦媒体文件）+ `executeUpdateDelete()` 返回值伪装，逐条对到 wxgroup 现状，列「外部 hook 点 / wxgroup 有没有 / 文件:行号 / 判据覆盖面 / 缺什么」表；特别核 `VoiceGuard.kt` 已挂的 wcdb `delete` 可否复用扩展到 `ImgInfo2`/`WxFileIndex2`，还是必须新挂。
- **Q2** wcdb 差异：`com.tencent.wcdb.database.SQLiteDatabase` 的 `delete` / `deleteWithOnConflict` 与 AOSP 签名差异；`SQLiteStatement.executeUpdateDelete()` 在 wcdb 对应什么类/方法（Grep `_cache/dex/` dexdump/smali 留档、`docs/conclusions/`、源码 hook 注册处）。复核「wcdb `SQLiteDatabase` 无 `deleteWithOnConflict` ⇒ 死代码」硬结论，若网上方案依赖该方法须指出 8.0.48 是否可用。
- **Q3** 根因判定：结合 lane B `[推断]` 与掘金实验，回答「图片打不开」最可能原因是否 `ImgInfo2` 行被删；给一条能区分「文件没了 / 索引行没了 / 两者都没了」的只读真机判据（`op=sql` 探针，BLOB 用 `hex()` 不用 `cast as text`）。
- **Q4** 微X对照：这台设备上微X是否启用防撤回（配置项 / 设置页证据）？Grep `docs/_r44_chain.md`、`docs/conclusions/`、设备取证留档。查不到写「未取证」，不编造 hook 锚点；「同构推断」与「已证实事实」分开列。
- **Q5** 最小补丁方案：改哪几个文件、每个改什么（函数级）、是否额外写微信库、竞态、失效场景、回滚；≥2 候选（扩 `VoiceGuard` 判据 / 新增独立媒体表 delete 钩子 / 到达即备份大图到模块自有目录）；D4 能阻止删除就不退到事后补救。
- **Q6** D2 路线对照表：核心动作 / 预估代码量 / 是否额外写库 / 竞态 / 失效场景 / 验证成本，留用户拍板，不自评最优。
- **Q7** 三档边界：①进入（刚撤回）②返回（隔几分钟点开、切页回来、下拉刷新、冷启）③复位（关 `antirevoke.msg`、微信重试删除、TTL 过期、清缓存）；复位档不许写「同返回」；逐档列残留状态（保护池 token、备份文件、已挂 hook、10 分钟 TTL、DB 行）。
- **Q8** 单测设计：哪些判据能抽成零 Android 依赖纯函数（表名白名单 / 是否撤回期 / 是否媒体目录）；用例矩阵 + 变异验证点（改坏哪几处必须变红）；用例必须能区分正确实现与看似合理的错误实现，拒绝等价变异假验证。
- **Q9** 真机判据 + 反向对照：对着「点开图片能不能看到」写总判，给 `python tools/dev.py` 子命令 / 广播 op / 过滤关键字；反向对照关哪个 Key（`antirevoke.msg`，默认 `false`，先核源码确认）、关后预期；提及好友一律用昵称不用裸 wxid。

## 取证区（每答完 2 题追加落盘）

## Q1 逐条对账表（外部三件套 + `executeUpdateDelete()` 返回值伪装 → wxgroup 现状）

> 外部实现来源（主会话 WebSearch，二手转录）：`CPWechatXposed` `RevokeMsgHook.kt` 4 点、`XposedRedDevil` `RevokeMsgHook.java:69-155` 三件套、掘金 coder-pig《…6.你的表白撤不回了》决定性实验。以下「外部 hook 点」列均标注为**外部证据**；wxgroup 侧每条都给 `文件:行号`。

| # | 外部实现的 hook 点 | wxgroup 有没有 | 文件:行号 | 判据覆盖面 | 缺什么 |
|---|---|---|---|---|---|
| 1 | `com.tencent.wcdb.database.SQLiteDatabase.updateWithOnConflict` 拦 `type=10000` 撤回改写 | ✅ **有**（唯一撤回拦截点） | `AntiRevoke.kt:371`（`hookAllMethods`）、`:387-392`（`revokeGuard.beforeHookedMethod`→`guard`）、`:359-360`（明写「只挂 updateWithOnConflict，挂一个就够」）、`:384`（安装日志）；调用链 `guard→guardMessage→RevokeRescue.record` 见 `docs/_r21c_delay_review.md:30-31`；拦法 `param.result=FAKE_ROWS` 见 `docs/_r21c_voice_root.md:50` | 只认 `message` 表的撤回形状写入（type 低 16 位 10000/10002，`docs/_r16_g2_msgsnap.md:340` 转录 `AntiRevoke.kt:23-27`） | **呈现方式不同**：开源「把原消息改内容后重插 + 微调时间戳」，wxgroup 是只拦不重插，靠 `RevokeRescue` 台账（`RevokeRescue.kt:122`）+ 提示/角标（`AntiRevokeHint.kt`）呈现。属路线差异，非链路断点 |
| 2 | `SQLiteDatabase.delete` 识别媒体表 `voiceinfo`/`ImgInfo2`/`videoinfo2`/`WxFileIndex2`，**放行返回成功、实际不删** | ⚠️ **钩子有、判据只认 1 张表** | `VoiceGuard.kt:136`（挂 `delete`）、`:169-186`（`onDelete` 判据 + `param.result=FAKE_ROWS`）、`:173-174`（`table.equals(TABLE)` 早退）、`:53`（`TABLE="voiceinfo"`）；`VoiceGuardLogic.kt:38`（`TABLE="voiceinfo"`）、`:55-58`（`EQ_OR_IN` 只认 `filename\|msglocalid`）；`SnsGuard.kt:149`（另一份 `delete`，只服务 `SnsInfo`）；`docs/_r49_revokeimg_forensics.md:132` | 只覆盖 `voiceinfo`（语音）；`SnsGuard` 覆盖 `SnsInfo`（朋友圈独立库）。**`ImgInfo2`/`videoinfo2`/`WxFileIndex2` 全无拦截** | 缺 3 张媒体表的表名白名单、各自列名判据、以及 `protectedSet()` 里 `orig_type=34` 的语音限定（`VoiceGuard.kt:247-248`）。⇒ **这正是「图片打不开」的链路缺口**（见 Q3） |
| 3 | `java.io.File.delete` 拦 `/image2/` `/voice2/` `/video/` 目录，返回 `true` 欺骗微信 | ✅ **有**（全进程，图片/视频/语音共用） | `VoiceFileGuard.kt:251-252`（挂 `File.delete`/`deleteOnExit`）、`:306`（`param.result=true`）、`:312`（`VFG blocked MEDIA delete`，60s 节流）；`docs/_r49_revokeimg_forensics.md:120,134` | 按「文件名含 imgPath token」判，不限媒体类型；`MediaGuardLogic.kt`（`docs/_r49_revokeimg_forensics.md:108`：R31g 只实证 `imgPath=THUMBNAIL_DIRPATH://th_<32位md5>`） | 只覆盖 Java 层 `File.delete`，**拦不到 native `unlink()`**（`docs/_r49_revokeimg_forensics.md:140` 引 `VoiceFileGuard.kt:35-38`）；且图片/视频**无 backup/restore 兜底**（`VoiceBackup.kt:111` 只认 `type=34`；`RevokeRescue.kt:121-127` 图片分支只有 `protectMedia`+`auditImage`）。10 分钟 TTL（`VoiceFileGuard.kt`，lane B 前置结论） |
| 4 | `SQLiteStatement.executeUpdateDelete()` 把返回值伪装成 1（掘金决定性实验：只拦 `File.delete` 时图片/视频仍打不开，补这条后才打开） | ❌ **没挂**（但有不挂在 `delete()` 入口的**等价替代**） | 替代：`VoiceGuard.kt:186`（`param.result=FAKE_ROWS` 于 `delete()` 入口）；机理 `docs/_r21c_voice_sqlform.md:206`（`delete()` 纯 Java，置 result 即跳过整个方法体 ⇒ `executeUpdateDelete()` 不执行）、`docs/conclusions/itemview_voice.md:131`；探针侧明确「故意不挂」：`RevokeHuntProbe.kt:122-125`、`SnsHuntProbe.kt:172` | 等效于「伪装受影响行数」，且更彻底（连 SQL 都不拼） | 无功能缺口；**但**该替代只作用于 `delete()` 路径。若微信删 `ImgInfo2` 走 `execSQL` 或预编译 `SQLiteStatement` 直调（见 Q2），入口置 result 覆盖不到 |
| 5 | `com.tencent.mm.storage.bj.c` 消息插入时缓存原消息 | ⚠️ **有同构物，锚点不同** | `AntiRevoke.kt:382`（挂 `insertWithOnConflict` 的 `insertProbe`）、`:377-383`（`anchorImage` 到达锚点）、`:461`（提前入 `VoiceFileGuard` 保护池）、`RevokeRescue.kt:122`（撤回时填台账/保护池） | 缓存的是 msgId/msgSvrId/imgPath 三元组 + `rescued` 台账，不是「整条原消息对象」 | 缺「撤回后按缓存重插原消息 + 微调时间戳」的呈现层；`insertWithOnConflict` 至今**只看不拦**（`AntiRevoke.kt:395-400`、`docs/_r49_revokeimg_forensics.md:141`），`INSERT OR REPLACE` 覆盖原行未拦 |

### Q1 特别核对：`VoiceGuard.kt` 已挂的 wcdb `delete` 能否复用扩展？

**结论：钩子可以复用，无需新挂 `delete`；必须改的是「判据」，不是「注册」。** `[推断]` 部分已标注。

- 注册是方法级的：`XposedBridge.hookAllMethods(cls, "delete", deleteGuard)`（`VoiceGuard.kt:136`）钩在 `SQLiteDatabase.delete` **方法**上，任何表的 delete 都会回调 `onDelete`（`VoiceGuard.kt:156-161`）。是否拦由回调内判据决定（`VoiceGuard.kt:169-186`），不是按表分别注册 ⇒ **扩表不需要第 5 个钩子**。
- 需要改的 4 处（全在判据侧）：
  1. `VoiceGuard.kt:53` `private const val TABLE = "voiceinfo"` → 表名集合 `{voiceinfo, ImgInfo2, videoinfo2, WxFileIndex2}`；
  2. `VoiceGuard.kt:173-174` 的 `table.equals(TABLE, ignoreCase=true)` 早退 → 白名单命中；
  3. `VoiceGuardLogic.kt:38` `TABLE` + `:55-58` `EQ_OR_IN`（现只认 `filename|msglocalid`）→ 补 `ImgInfo2` 的 `msglocalid`/`bigImgPath`/`thumbImgPath`、`WxFileIndex2` 的键列名；`:48` 的 26 位 hex 正则对图片 token 是否仍适用 `[需真机]`（图片 md5 是 32 位，`MediaGuardLogic.kt:20-22`，`docs/_r49_revokeimg_forensics.md:108`）；
  4. `VoiceGuard.kt:247-248` `recentVoiceMsgIds()` 的 `"orig_type=?"` + `TYPE_VOICE=34`（`:54`）→ 补图片 `type=3`/视频 `type=43`（`docs/_r16_g2_msgsnap.md:333,340` 转录类型表）。
- ⛔ **一个可能推翻复用的 caveat**：`ImgInfo2` 的删除面证据是 `DELETE FROM ImgInfo2 WHERE msglocalid IN (…)` 与泛型 `delete from %s where %s=%s`（`docs/_r35_revoke_img_anchor.md:126-135`；`docs/_r49_revokeimg_forensics.md:139`）。**这些 SQL 到底经过 `SQLiteDatabase.delete()` 还是 `execSQL()`/预编译语句，尚未取证** `[需真机]`。若走 `execSQL`，扩 `VoiceGuard` 判据也覆盖不到，必须补挂 `execSQL` —— 而 lane B 已证「聊天防撤回侧 `execSQL` 零挂点」（`docs/_r49_revokeimg_forensics.md:133`）。故 Q5 候选 2 必须把「新挂 `execSQL`」作为可选项，不能只写「扩判据」。
- 「有没有 / 在哪个文件哪一行 / 判据覆盖面 / 缺什么」逐格见上表；lane B 的 `docs/_r49_revokeimg_forensics.md:128-134` 另有一份「现有代码实际挂的 hook」表，与本表互为交叉核对（两表一致）。

[DONE] Q1-Q2-a

## Q2 wcdb 差异 + 死代码硬结论复核 + 8.0.48 可用性

### 2.1 方法签名差异（AOSP vs wcdb `com.tencent.wcdb.database.SQLiteDatabase`）

| 方法 | AOSP `android.database.sqlite.SQLiteDatabase` | 微信 8.0.48 wcdb | 证据 |
|---|---|---|---|
| `delete` | `delete(String,String,String[])I`，内部**转调** `deleteWithOnConflict` | `delete(String,String,String[])I` —— **唯一 delete 重载、纯 Java**；内部拼 `DELETE FROM <t> WHERE <w>` → `new SQLiteStatement(db,sql,whereArgs)` → `SQLiteStatement.executeUpdateDelete()`，**不转调** `deleteWithOnConflict` | `docs/_r21c_voice_sqlform.md:178,192-204`（反汇编 `[0]..[55]`）；`docs/conclusions/itemview_voice.md:122`；`VoiceGuard.kt:125-132` 注释自承「内部转调 deleteWithOnConflict 是对 AOSP 的误记」见 `docs/_r21c_voice_sqlform.md:207` |
| `deleteWithOnConflict` | `deleteWithOnConflict(String,String,String[],int conflictAlgorithm)I` | ❌ **不存在** | `docs/_r21c_voice_sqlform.md:188`；`docs/conclusions/itemview_voice.md:79,127`；方法表复核脚本 `tools/_r21c_verify_sql.py:59-80` |
| whereArgs 类型 | `String[]` | 存在 `String[]` 与 `Object[]` 两种重载，`VoiceGuard` 按**位置**取第 3 参、不按类型 | `VoiceGuard.kt:165-167`；`docs/_r16_g2_msgsnap.md:368`（`rawQueryMethod` 处理 WCDB `String[]` vs `Object[]` 重载） |
| `execSQL` | 3 个重载 | 3 个重载 `execSQL(String)` / `(String,Object[])` / `(String,Object[],CancellationSignal)` | `docs/_r21c_voice_sqlform.md:185`；`docs/conclusions/itemview_voice.md:126` |
| `updateWithOnConflict` | 存在 | ✅ 存在且**实挂生效** | `docs/_r21c_voice_sqlform.md:184`；`AntiRevoke.kt:371,384` |

### 2.2 `SQLiteStatement.executeUpdateDelete()` 在 wcdb 里对应什么

- 类：`com.tencent.wcdb.database.SQLiteStatement`（源码 hook 注册处 `RevokeHuntProbe.kt:115`、`SnsHuntProbe.kt:192`；方法名 `SnsHuntProbe.kt:196` `STMT_METHODS = listOf("executeUpdateDelete","executeInsert","execute")`）。另有 AOSP 同名类 `android.database.sqlite.SQLiteStatement`（`RevokeHuntProbe.kt:116`、`SnsHuntProbe.kt:193`）作对照。
- 方法：`executeUpdateDelete()I`，是 wcdb `delete()` 内部**真正的执行点**（`docs/_r21c_voice_sqlform.md:202` 反汇编 `[48] SQLiteStatement->executeUpdateDelete()I`）；类定义在 classes10.dex（`docs/_r21c_voice_sqlform.md:176,194`）。
- 项目里**谁挂过**：`SnsHuntProbe`（`SnsHuntProbe.kt:190-196,307,459,504,523`，探针，反射从 `SQLiteProgram.mSql` 抠 SQL）；`RevokeHuntProbe` 把 `SQLiteStatement` 列进 `SQL_CLASSES` 但 `:122-125` 明写 `executeInsert/executeUpdateDelete` **故意不挂**（理由：预编译语句只有 long handle 拿不到 SQL、最热路径）；`docs/_r27_clear_api_forensics.md:8` 记录曾一次挂满 46 hooks（含 `SQLiteStatement.executeUpdateDelete/execute`）；`docs/_r27_m3_scene_facts.md:29` 记录「没挂 `update`/`updateWithOnConflict`/`insertWithOnConflict`/`SQLiteStatement.executeUpdateDelete` ⇒ 结论只到中等强度」。

### 2.3 复核「wcdb `SQLiteDatabase` 无 `deleteWithOnConflict` ⇒ 死代码」硬结论

**一致，不矛盾。** 结论出处：`docs/conclusions/itemview_voice.md:79,127-131`、`docs/_r21c_voice_sqlform.md:188-190,261-262,338`。落到源码：`VoiceGuard.kt:133-135` 的 `hookAllMethods(cls,"deleteWithOnConflict",deleteGuard)` 返回空集，`ok` 列表不含它，日志形如 `VG installed hooks=delete enabled=true`（`VoiceGuard.kt:125-132,143`）；`VoiceGuard.kt:125-128` 注释已把这一点写进代码。**本轮不推翻、不改写该结论。**

### 2.4 网上方案在微信 8.0.48 上是否可用（关键判定）

| 外部依赖 | 8.0.48 wcdb 可用性 | 依据 |
|---|---|---|
| `updateWithOnConflict`（拦撤回，CPWechatXposed / XposedRedDevil 都挂） | ✅ **可用**，wxgroup 已实挂生效 | `AntiRevoke.kt:371,384`；`docs/_r21c_voice_sqlform.md:184` |
| `SQLiteDatabase.delete`（拦媒体表） | ✅ **可用**，wxgroup 已实挂生效 | `VoiceGuard.kt:136,143`；`docs/conclusions/itemview_voice.md:122,131` |
| `java.io.File.delete` | ✅ 可用（Java 层），wxgroup 已挂 | `VoiceFileGuard.kt:251-252,306` |
| `deleteWithOnConflict` | ❌ **不可用**（wcdb 无此方法 ⇒ 挂了也是死代码） | `docs/conclusions/itemview_voice.md:79,127-129` |
| `SQLiteStatement.executeUpdateDelete()`（掘金伪装返回值） | ✅ 类/方法**存在**，但 wxgroup **不必挂** | `RevokeHuntProbe.kt:115`、`SnsHuntProbe.kt:192-196`、`docs/_r21c_voice_sqlform.md:202`；不挂理由 `RevokeHuntProbe.kt:122-125` |

⇒ **网上三件套没有一条依赖 `deleteWithOnConflict`**（依赖的是 `updateWithOnConflict` + `delete` + `File.delete`），所以「死代码」硬结论与开源方案**不冲突**；`deleteWithOnConflict` 在 8.0.48 上不可用，但不影响三件套移植。

⇒ **机制差分（本轮最重要的一条）**：掘金方案 hook `SQLiteStatement.executeUpdateDelete()` 伪装返回值，是因为按 AOSP 的 `delete→statement` 链路只能拦到语句层；而 wcdb 的 `delete()` 是**纯 Java**，在 `beforeHookedMethod` 里 `param.result=1` 直接跳过整个方法体，`executeUpdateDelete()` 根本不执行（`docs/_r21c_voice_sqlform.md:206`；`docs/conclusions/itemview_voice.md:131`）。**wxgroup 现有的 `VoiceGuard.deleteGuard` 就是「伪装返回值」的等价且更彻底的实现，落点已在 `delete()` 入口，无需照抄 statement 层 hook。** 因此 Q5 的修复重点不是「补 `executeUpdateDelete` 钩子」，而是「把 `delete` 判据从 `voiceinfo` 扩到图片/视频媒体表」。

### 2.5 取证边界（如实标注）

- 本 lane 工作区内 `Glob _cache/dex/**/*`、`**/*.smali`、`**/shell.txt`、`**/classes*.dex` **均无匹配**（原始 dexdump/smali/`_mods/` 留档不在本工作区）；Q2 的 dex 级结论全部转引自已落盘的转录文档 `docs/_r21c_voice_sqlform.md` / `docs/conclusions/itemview_voice.md` 与只读复核脚本 `tools/_r21c_verify_sql.py:59-80`。**原始 dex 逐字节复核 `[需真机]` / 需主会话留档**，本轮不声称已重跑。
- 「`ImgInfo2` 删除究竟走 `delete()` / `execSQL` / 预编译语句」**未取证** `[需真机]`（`docs/_r49_revokeimg_forensics.md:139`、`docs/_r35_revoke_img_anchor.md:121-143` 自述「无证据，待真机」）；这直接决定 Q1 的「复用扩展」是否够用。

[DONE] Q1-Q2

## Q3 根因判定 + 一条区分三态的只读真机判据

### 3.1 结论：「wxgroup 现在图片打不开」最可能原因 = `ImgInfo2` 行被删（索引/信息表记录没了），不是文件没了

**判定：是（置信 `[推断]`，高；wxgroup 侧从未真机闭环，不写成事实）。** 三条依据：

1. **外部决定性实验**（掘金 coder-pig《抱歉,Xposed真的可以为所欲为——6.你的表白撤不回了》，主会话 WebSearch 转录）：只 hook `File.delete` 拦 `/voice2` `/image2` `/video` 后，**只有语音能正常打开，图片和视频都无法打开**；文件明明还在，每次点开还会再执行一次删除；跟踪 `SQLiteDatabase.delete()` 参数发现撤回会删 `voiceinfo`/`ImgInfo2`/`videoinfo2`/`WxFileIndex2` 四表；再 hook `SQLiteStatement.executeUpdateDelete()` **把返回值伪装成 1**（微信按受影响行数判断）后，**图片和视频都能打开**。⇒ 公认根因：**不是文件没了，是索引/信息表记录没了**（`docs/派发审计台账.md:3244-3246` 亦记此条为核心外部证据）。
2. **wxgroup 缺口形状与该实验完全对上**：`File.delete` 已全进程拦（`VoiceFileGuard.kt:251-252,306`），但 `delete` 判据只认 `voiceinfo`（`VoiceGuard.kt:53,173-174`；`VoiceGuardLogic.kt:38,55-58`），`ImgInfo2`/`videoinfo2`/`WxFileIndex2` **零拦截**（`docs/_r49_revokeimg_forensics.md:132,139`）。即 wxgroup 恰好停在掘金实验里「图片打不开」的那一步。
3. **同构依据**：R21c 已实证「即使 `AntiRevoke` 拦下 `UPDATE message`，`voiceinfo` 的 DELETE 仍由 `executor.submit` 异步执行」（`docs/_r21c_voice_sqlform.md:93,157`；`docs/conclusions/itemview_voice.md:77,95-103`）⇒ 图片侧媒体表 DELETE 大概率照跑 `[推断]`。

**不能写成事实的原因**：lane B 明确把「撤回时 `ImgInfo2` 行还在」标为 **② 推断、未取证、待真机**（`docs/_r35_revoke_img_anchor.md:141-143`；`docs/_r49_revokeimg_forensics.md:157,163,297,313`）；且 `docs/_r35_revoke_img_anchor.md:138-140` 自述「光靠字符串取证答不了」——全 dex 扫到的 `DELETE FROM ImgInfo2` 全是 `WHERE talker IN (…)` 删会话形态（`:126-127,141`），单条删除走泛型 `delete from %s where %s=%s`（`:132,139`），**撤回是否真的删单条 `ImgInfo2` 行无一手证据**。
**另两种可能不能排除**：文件没了（native `unlink` 拦不到，`VoiceFileGuard.kt:35-38`；或大图从未下载 `big=MISS`，`docs/_r35_revoke_img_anchor.md:345`）、两者都没了。⇒ 必须给能区分三者的判据。

### 3.2 ⛔ 判据前置：本轮发现的「`op=sql` 通道陷阱」（先读，否则 SQL 会打空）

题目要求「用 `op=sql` 探针」，但**源码静态证据显示该通道打不到 `ImgInfo2`**：

| 事实 | 文件:行号 |
|---|---|
| `--es op sql --es q …` 分支调 `SnsGuard.runReadOnlySql(q, n)` | `EntryReceiver.kt:652-661` |
| 分支注释自述「取样（**sns.db**）为例」 | `EntryReceiver.kt:653` |
| `runReadOnlySql` 用 `snsDb` 句柄，`snsDb==null` 直接返回 `SQLX no sns db yet` | `SnsGuard.kt:1280-1281` |
| `snsDb` 只在 SQL 含 `SnsInfo` 时被捕获（朋友圈域） | `SnsGuard.kt:842,944,1014,1035,1043` |
| **朋友圈是独立库，不是主消息库 `EnMicroMsg.db`** | `SnsGuard.kt:69`；`docs/conclusions/moments.md:183` |
| 只放 `select` / 只读 pragma，且内部 `;` 一律拒 ⇒ 不能靠 `ATTACH` 绕到主库 | `SnsGuard.kt:1268-1279` |
| `op=sql` 读 extra `q`，不是 `val`；`kvget` 才是模块配置库 | `tools/dev.py:21,261-266` |

⇒ `[推断]` `op=sql` 对 `ImgInfo2` 会返回 `no such table` 或 `no sns db yet`。**`docs/_r49_revokeimg_forensics.md:226,266` 与 `docs/_r35_revoke_img_anchor.md:302-303` 把 `select count(*) from ImgInfo2` 挂在 `op=sql` 上，属通道误用** `[需真机]` 证伪/证实（先跑一次 `probe sql --q 'select count(*) from ImgInfo2' --grep SQLX` 看返回）。**修正通道**：
- **① 主库通道（推荐）**：`--es op label --es sub sql --es sql '…'`（`CLAUDE.md:17`），实现在 `LabelHuntProbe.kt:246`（`"sql" -> runSql(sql)`）、`:478`（`runSql`）、`:1028`（`val main = AntiRevoke.wxDb()`）、`:1082-1101`（`rawQuery` 反射）。实测范例查 `message`：`docs/conclusions/send_msg.md:49-50`、`docs/_archive/标签数据源取证方案.md:85`。⚠️ 该通道用 `--es sql`（**不是 `--q`**），且 `tools/dev.py probe` 不映射 extra `sql`（`tools/dev.py:254-270`；`docs/_r49_revokeimg_forensics.md:283` 同述）⇒ 需手工 `am broadcast` 或用 `tools/` 现成脚本。
- **② 模块内既有审计**：`RRV imgAudit`（`RevokeRescue.kt:230-243`；`docs/_r49_revokeimg_forensics.md:101`）直接给 `row=`（行在不在）与 `big=OK(n>0)`（文件在不在，`docs/_r35_revoke_img_anchor.md:341-345`）。

### 3.3 「一条」判据：同一条只读 SQL，撤回前 / 撤回后各取一次快照，交叉 2×2 定三态

**探针命令**（按题目给 `op=sql` 形式；执行前先按 3.2 证通道，若打空则换 ①）：

```bash
# <msgId> 取自 `AR imgAnchor msgId=...`（docs/_r35_revoke_img_anchor.md:331）
python tools/dev.py probe sql --q 'select count(*) as n, hex(bigImgPath) as big_hex, hex(thumbImgPath) as thumb_hex, status, iscomplete from ImgInfo2 where msglocalid=<msgId>' --grep SQLX
```

- **BLOB 用 `hex()` 不用 `cast as text`**：`docs/conclusions/moments_cdn.md:350-352` 实证 `cast(BLOB as TEXT)` 遇 `0x00` 在首个 NUL 截断 ⇒ 假阴性，原文「BLOB 列一律 `hex()` 导出后本地解析，禁止 `cast as text` 检索」。`ImgInfo2` 列名依据 `docs/_r49_revokeimg_forensics.md:119`（`bigImgPath/thumbImgPath/status/iscomplete/hashdthumb`）与 `docs/_r35_revoke_img_anchor.md:303`（`id,msgSvrId,msglocalid,bigImgPath,thumbImgPath,status,iscomplete`）。
- ⚠️ SQL 含空格/逗号经 adb → 设备 sh 会分词（`tools/dbq.py:4` 记此坑），须整体引用；`--grep SQLX` 必须见 ≥1 行（`tools/dev.py:295-299`；`docs/_r35_revoke_img_anchor.md:318` 闸门三），0 行一律判「探针没执行」，不解读。
- **基线**：收到图、**不要点开**（`docs/_r35_revoke_img_anchor.md:305`）时先跑一次，记 `n≥1` + `big=OK`；好友撤回后（等 3~5 秒，`docs/_r49_revokeimg_forensics.md:230`）再跑**同一条** SQL + 取 `RRV imgAudit`（`docs/_r49_revokeimg_forensics.md:231`）。两次快照的差值即判定。

| `ImgInfo2` 行（SQL `n` / `RRV imgAudit row=`） | 文件（`RRV imgAudit big=`） | 判定 | 处置指向 |
|---|---|---|---|
| 在（n≥1） | 在（`big=OK(n>0)`，`docs/_r35_revoke_img_anchor.md:344`） | **两者都在**：若仍打不开 ⇒ 另有原因（呈现层/缓存/点开时二次删除），不是本轮三态 | 查 Q1 #4 二次删除 / `insertWithOnConflict` |
| 在（n≥1） | 不在（`big=MISS`/`NA`，`:345`,`:327`） | **文件没了** | native `unlink`（`VoiceFileGuard.kt:35-38`）/ 从未下载；Q5 候选 3 备份路线 |
| 不在（n=0 / `row=0`，`:210`） | 在（`big=OK`） | **索引行没了** ⭐ 掘金根因，Q1 对账表第 2/4 行的缺口 | Q5 候选 1/2（拦媒体表 delete） |
| 不在（n=0） | 不在 | **两者都没了** | 两路都要补 |

> ⛔ 全部 `[需真机]`：本 lane 全只读、不碰 adb / 真机 / kvset，以上为主会话执行清单。判「点开能不能看到」仍以用户可见事实为主判（`docs/开发提效与后续计划.md:89` A.2；`docs/_r49_revokeimg_forensics.md:254`），SQL 快照是定位三态的辅助。

[DONE] Q3

## Q4 微X对照：这台设备是否启用防撤回？同构推断 vs 已证实事实（严格分开）

### 4.1 查证范围

`docs/_r44_chain.md:465-505`（R44 真机续）、`docs/conclusions/moments_cdn.md:359-368`、`docs/派发审计台账.md:2993-2995,3240-3248`、全 `docs/` Grep `FKZ_WX_DATA|wechatxposed|fkzhang|微X|防撤回`；另本 lane `Glob _mods/**/*` 与 `**/classes*.dex`、`**/shell.txt` **均无匹配**（`_mods/wechatxposed.apk`、`_mods/x/classes.dex`、`_mods/shell.txt` 不在本工作区，无法重跑 dexdump）。

### 4.2 已证实事实（带锚点，可写）

| # | 事实 | 文件:行号 |
|---|---|---|
| 1 | 微X v2.44 `com.fkzhang.wechatxposed` **确实装在同一台设备** | `docs/_r44_chain.md:465-505`；`docs/conclusions/moments_cdn.md:361` |
| 2 | `xposed_init` = `com.fkzhang.wechatxposed.XposedInit` | `docs/派发审计台账.md:2995` |
| 3 | 有完整**朋友圈**保存/自动导出功能；设置页 `com.fkzhang.wechatxposed/.MainActivity` → 设置 → 朋友圈 **实测条目**：保存目录、`导出朋友圈动态`、`批量导出`、`自动导出朋友圈动态`（附注「需要开启定时刷新和自动加载朋友圈功能」）、路径可预览、`设置自动导出名单`、`定时刷新朋友圈 + 自动加载朋友圈动态 + 刷新间隔` | `docs/_r44_chain.md:491-501`（`:503` 结论「与 R44 设计几乎一模一样」） |
| 4 | 配置库 **`FKZ_WX_DATA`**，已见配置项 `sns_save` / `settings_save_dir` | `docs/_r44_chain.md:504`；落点目录树含 `微X模块_<wxid>_settings.json`（明文 JSON、值仍密文）`:488` |
| 5 | 导出的 `video.mp4` 13,885,521 字节是**明文** ⇒ 微X 内部做了解密 | `docs/_r44_chain.md:506-528`；`docs/conclusions/moments_cdn.md:364-366` |
| 6 | 微X 是**加密壳架构**：`classes.dex` 只是壳 loader，真逻辑在 `assets/` 下 `92 c5 01 00` 魔数高熵块 + 两个 ELF so，经 `InMemoryDexClassLoader` 内存加载 ⇒ 静态逆向不可行 | `docs/conclusions/moments_cdn.md:367-368`；`docs/派发审计台账.md:2993-2994`；题目给定「dexdump 40MB 中 `ImgInfo2`/`WxFileIndex2`/`revoke`/`updateWithOnConflict` 全部 0 命中」 |

### 4.3 ⛔ 未取证（明确写「未取证」，绝不编造）

- **微X 是否启用了防撤回：未取证。** `docs/_r44_chain.md:465-505` 的设置页清单**全部是朋友圈域**，无「防撤回 / 消息撤回 / anti-revoke」条目；`FKZ_WX_DATA` 只见到 `sns_save`/`settings_save_dir`（`:504`），未见防撤回配置项；全 `docs/` Grep 未出现「微X + 防撤回/revoke」同现的**取证行**（唯一同现是 `docs/派发审计台账.md:3240-3248` 的**本轮任务描述**，不是证据）。⇒ 不能回答「已启用」或「未启用」。
- **微X 防撤回的 hook 锚点、判据、表名白名单、是否也拦 `ImgInfo2`：未取证，不编造。** 按题目硬约束：只能写「**与开源同构系的实现族，具体锚点未取证**」。壳内实现本轮**未解壳**（`docs/派发审计台账.md:2993-2994`、`docs/conclusions/moments_cdn.md:367-368`）。
- ⇒ **微X 不能作为 wxgroup 补丁的一手依据**；本轮补丁依据只能是：① 开源三件套 + 掘金实验（外部证据）、② wxgroup 自身源码（已给行号）、③ Q3/Q9 的真机判据。

### 4.4 「同构推断」与「已证实事实」分栏

| 类别 | 内容 | 强度 |
|---|---|---|
| **已证实事实** | 4.2 表的 6 条：同设备、包名/入口、朋友圈保存能力、`FKZ_WX_DATA`、明文导出、加密壳 | 有 `文件:行号` 锚点 |
| **同构推断 `[推断]`** | 微X 与 `CPWechatXposed` / `XposedRedDevil` 属「同一代 Xposed 防撤回实现族」，推测也走 `updateWithOnConflict` 拦撤回 + `delete` 拦媒体表 + `File.delete` 拦文件 + 消息缓存恢复 | 仅基于「同类模块常见三件套」的族系外推，**无任何微X 一手锚点**，不得写成事实、不得据此选 hook 落点 |
| **明确否定/未取证** | 微X 防撤回是否开启、开关 key 名、是否拦 `ImgInfo2`、是否用 `executeUpdateDelete` 伪装返回值 | **未取证**；`classes.dex` 壳内 0 命中（题目给定），不能反推「微X 不用这套」 |

[DONE] Q3-Q4

## Q5 最小补丁方案（≥2 候选，D4 控制优先于观测）

> 前置：`CLAUDE.md:48-49` **D4｜控制优先于观测**「能阻止事件发生，就不要先费力检测事件是否发生。只有无法阻止时才退到『观测 + 补救』」。⇒ 候选 A/B 是「阻止删除」，候选 C 是「观测 + 补救」，C 只能作 A/B 的兜底补充，**不能单独作主方案**（详见 5.3）。
> 三条候选**都不写微信库**（只在 `before` 里 `param.result` 伪装成功、跳过删除）；「改内容重插 + 微调时间戳」那种外部呈现方式才需要写库（`CPWechatXposed`），本轮不采用 —— 项目吃过写库亏：`HookEntry.kt:253-258`（负数 msgId 提示行导致闪退/不相邻，已废）、`docs/SOP审计日志.md:1452`。
> 所有 `[推断]` 标在未取证处；本 lane 不写码，以下为改到函数级的方案。

### 5.1 候选 A：扩 `VoiceGuard` 判据（复用已挂的 `delete` 钩子，零新 hook）

**依据**：Q1 特别核对 —— `VoiceGuard.kt:136` 是方法级 `hookAllMethods(cls,"delete",deleteGuard)`，任何表的 delete 都会进 `onDelete`，是否拦由判据决定 ⇒ 扩表**不需要新挂钩子**。

| 文件 | 改什么（函数级） | 锚点 |
|---|---|---|
| `data/VoiceGuardLogic.kt` | ① `TABLE`（`:38`）由单值改表名集合 `{voiceinfo, ImgInfo2, videoinfo2, WxFileIndex2}`；② `shouldBlock`（`:139-170`）第 1 步 `:146` 表名判断改白名单；③ `EQ_OR_IN`（`:55-58`）现只认 `filename\|msglocalid`，补 `ImgInfo2` 的 `msglocalid`/`bigImgPath`/`thumbImgPath`、`videoinfo2` 的 `filename`、`WxFileIndex2` 的键列；④ `HEX`（`:48`）是 **26 位** hex（语音 imgPath），图片 token 是 **32 位 md5**（`MediaGuardLogic.kt:40-55`）⇒ `hexTokensFrom`（`:75-78`）必须按表分派两套长度，或改范围匹配 | `VoiceGuardLogic.kt:38,48,55-58,75-78,139-170`；`docs/_r16_g2_msgsnap.md:333,340` 类型表 |
| `feature/VoiceGuard.kt` | ① `TABLE`（`:53`）→ 集合；② `onDelete`（`:169-186`）`:173-174` 早退改白名单；③ `recentVoiceMsgIds()`（`:239-255`）的 `"orig_type=? AND at>?"` + `TYPE_VOICE=34`（`:54`）→ 补 `orig_type IN (3,43,34)`；④ `buildProtectedSet()`（`:231-237`）/`imgPathsOf()`（`:258-280`）的 26 位 hex 口径 → 图片 32 位 md5 | `VoiceGuard.kt:53,54,169-186,231-280`；`MediaGuardLogic.kt:20-35` |
| `data/Keys.kt` / `data/Kv.kt` | **不改**：复用 `antirevoke.msg`（`VoiceGuard.kt:147`；`Keys.kt:140`） | `VoiceGuard.kt:146-154` |
| `HookEntry.kt` | **不改**：`VoiceGuard.install(lpparam)` 已在 `:222-224` 注册 | `HookEntry.kt:218-224` |

- **是否额外写微信库**：否（`VoiceGuard.kt:186` `param.result=FAKE_ROWS`，纯拦）。
- **竞态**：`protectedSet()` 3s 缓存 + `gen` 失效（`VoiceGuard.kt:214-229`；`VoiceGuardLogic.kt:82-126`）⇒ 连撤两条图片同样有「空集缓存 / 陈旧缓存」风险，靠 `RevokeRescue.record()` → `VoiceGuard.invalidate()`（`VoiceGuard.kt:96-105`）兜；时序沿用 R21c「UPDATE 被拦 → 异步 DELETE」（`docs/_r21c_voice_sqlform.md:93,157`）`[推断]`。
- **失效场景（关键，必须写）**：① ⛔ **`ImgInfo2` 已知 DELETE 全是 `IN (SELECT …)` 子查询形态**（`docs/_r35_revoke_img_anchor.md:126-127,141`），而 `VoiceGuardLogic.SUBQUERY`（`:66`）在 `shouldBlock` `:151-155` **主动放行子查询** ⇒ 候选 A 对 `ImgInfo2` 可能**完全不拦**，这正是它最大的失效口；② 媒体表删除若走 `execSQL` / 预编译 `SQLiteStatement`（Q1 caveat、`docs/_r49_revokeimg_forensics.md:133` 聊天侧 `execSQL` 零挂点），`delete` 钩子覆盖不到；③ 泛型单条删除 `delete from %s where %s=%s`（`docs/_r35_revoke_img_anchor.md:132,139`）最终落点未取证 `[需真机]`；④ token 长度 26/32 不兼容导致静默漏保护（`MediaGuardLogic.kt:44-53` 记过同型「静默失效」）；⑤ 漏视频 `type=43` ⇒ 视频仍打不开。
- **回滚**：把 `VoiceGuardLogic.kt:38` 的表集合收回 `{voiceinfo}`（单常量，1 行），或 `kvset antirevoke.msg=false`（`Keys.kt:437` 默认 `"false"`）；`EntryReceiver.kt:181-182` kvset 后主动 `refresh()`，**不用冷启**（`docs/_r49_revokeimg_forensics.md:288`）。

### 5.2 候选 B：新增独立媒体表 delete 钩子（含补 `execSQL`）

| 文件 | 改什么（函数级） | 锚点 |
|---|---|---|
| 新增 `data/MediaTableGuardLogic.kt` | 零 Android 依赖纯判据：表名白名单 + 列名/token + 是否撤回期（阶段 1，`CLAUDE.md:27` 范式 `SnsTagLogic.kt`） | `VoiceGuardLogic.kt:35-170` 作模板 |
| 新增 `feature/MediaTableGuard.kt` | `install(lpparam)`：挂 `com.tencent.wcdb.database.SQLiteDatabase.delete`（范式 `VoiceGuard.kt:136`）**+ `execSQL`**（`SnsGuard.kt:152` 已证可挂）；仅当真机证实微信绕过 `delete()` 直调预编译语句时，才补挂 `com.tencent.wcdb.database.SQLiteStatement.executeUpdateDelete`（`RevokeHuntProbe.kt:115`、`SnsHuntProbe.kt:192-196`）—— Q2 已证 wcdb `delete()` 纯 Java 入口置 result 更彻底，通常**不必**挂 statement 层 | `VoiceGuard.kt:113-144`；`SnsGuard.kt:148-160`；`docs/_r21c_voice_sqlform.md:206` |
| `HookEntry.kt` | `:223` `VoiceGuard.install` 之后加一行 `MediaTableGuard.install(lpparam)` | `HookEntry.kt:218-224` |
| `feature/RevokeRescue.kt` | `record()`（`:122`）里加 `MediaTableGuard.invalidate()`（同 `VoiceGuard.invalidate()` 范式） | `VoiceGuard.kt:96-105`；`RevokeRescue.kt:110-145` |

- **是否额外写微信库**：否。
- **竞态**：同 A 的 `gen`/缓存；两个 `delete` 回调并存 —— 靠表名白名单互斥（`VoiceGuard.kt:173-174` 只认 voiceinfo ⇒ 天然互斥，不用去重）；`execSQL` 是热路径，`before` 里必须「最便宜判断最先做」（`VoiceGuard.kt:172` 同款）。
- **失效场景**：① 表名白名单不全（漏 `WxFileIndex2` / `AppMessage`，`docs/_r35_revoke_img_anchor.md:130`）；② `execSQL` 的 `IN (SELECT …)` 按 talker 批量形态定位不到单条 msgId ⇒ 要么放行（漏拦）要么全拦（破坏微信 GC，`VoiceGuardLogic.kt:151-155` 的保守理由）；③ ⛔ **`execSQL` 返回 `void`**，`delete()` 那套 `param.result=1`「伪装受影响行数」语义**不适用**，需改为跳过方法体且不返还行数（写法 `[需真机]` 验证）—— wxgroup 目前 `SnsGuard.kt:152` 的 `execSQL` 只识别/计数**不拦**（`docs/_r49_revokeimg_forensics.md:122`），无先例；④ `INSERT OR REPLACE` 覆盖原行（`AntiRevoke.kt:395-400`）不在 delete 钩子范围；⑤ 新 hook 的 `installed` 日志需新增并用 `q` 验挂（`CLAUDE.md:75-76`）。
- **回滚**：删 `HookEntry.kt` 那一行注册 + 关 `antirevoke.msg`；两个新文件可整体移除，**不改任何既有文件语义**（这是 B 相对 A 的回滚优势）。

### 5.3 候选 C：不拦删除，改为「到达即备份大图到模块自有目录」（兜底，非主方案）

| 文件 | 改什么（函数级） | 锚点 |
|---|---|---|
| `data/VoiceBackupLogic.kt` | `needsBackup(origType)` 从「只认 `type=34`」扩到 `3/43`；新增图片三形态路径反解纯函数 | `VoiceBackup.kt:111,117-119`；`MediaGuardLogic.kt:121-123`；`VoiceFileGuard.kt:97-99` |
| `feature/VoiceBackup.kt` | `:127` 源路径硬编码 `voice2/<md5桶>/msg_<imgPath>.amr` ⇒ 改 `image2/<桶>/<md5>` / `<md5>hd` / `th_<md5>`；备份目录由 `voice_bak/` 扩/新增 `media_bak/` | `VoiceBackup.kt:42-45,100-135` |
| `feature/RevokeRescue.kt` | `record()` 图片/视频分支（`:121-127`）加 `backup(...)`，并补 `scheduleRestore`（语音同款 `:137-144`） | `RevokeRescue.kt:110-145`；`docs/_r21c_voice_file.md` |

- **是否额外写微信库**：否（只写**模块自有目录**，`VoiceBackup.kt:42-45`）。
- **竞态（三条里最大）**：到达时（`AntiRevoke.kt:461` anchorImage）大图可能**还没下载完**（`docs/_r35_revoke_img_anchor.md:345` `big=MISS`）⇒ 备份到半截/0 字节；语音侧是在 `record()` 同一次拦截里备份（`RevokeRescue.kt:110-115`「此刻文件还在，晚一步就永远拿不到」），图片侧同样必须在 `record()` 做，不能只在到达时做。
- **失效场景**：① ⛔ **不解决 Q3 根因** —— 文件搬回来了，`ImgInfo2` 索引行没了照样点不开（掘金实验的决定性结论）；② `File.delete` 已被拦时（`VoiceFileGuard.kt:306`）备份其实多余；③ 图片三形态路径未取证（`RevokeRescue.kt:238-243` 自标 `NA`/`[假设]`；`docs/_r49_revokeimg_forensics.md:108`）；④ 项目**没有播放路径的 hook**（`VoiceGuard.kt:196` 自述）⇒ 冷启/TTL 过期后没有「用户点开前 restore」的触发点，要补就得新挂图片查看入口（新工作面）；⑤ native `unlink` 之外微信清缓存可能直接删备份目录外文件。
- **回滚**：关 `antirevoke.msg`；备份目录整体删。
- **D4 判定**：C 是「观测 + 补救」，按 `CLAUDE.md:48-49` **仅当 A/B 无法阻止删除时才退到这里**；且不与 A/B 互斥，可作 `A+C` / `B+C` 组合的兜底层。

### 5.4 三条候选的共性约束

- 都受 `antirevoke.msg` 总开关约束（`VoiceGuard.kt:147`、`VoiceFileGuard.kt:192`；`Keys.kt:140,437` 默认 `"false"`），关开关即全链路停拦。
- 都必须保留「**无条件拦 delete 会破坏微信 GC**」的保守性（`VoiceGuardLogic.kt:23-26,145-157`；`VoiceFileGuard.kt:67-68,175-177`）：只拦命中「已救消息 + 撤回期」的删除，不拦 `null whereClause` 删全表 / `<` `>` `LIKE` `IS NULL` 清理形态 / 子查询整会话删除。
- 都必须按 `CLAUDE.md:20-30` 五阶段走：阶段 0 先按 Q3 判据证「`ImgInfo2` 行到底被没被删 + 走哪条 SQL 通道」，阶段 1 纯逻辑 + JVM 单测（Q8），阶段 3 真机 + 反向对照（Q9）。**D1**：本轮已完成开源检索（Q1 外部证据），不自研体系。

[DONE] Q5

## Q6 D2 路线对照表（贴给用户拍板，**本 lane 不自评最优**）

> `CLAUDE.md:42-43` **D2｜路线对照表前置**：「动手前必须写一张表并贴给用户选：核心动作 / 代码量 / 是否额外写库 / 竞态 / 失效场景 / 验证成本。**没有这张表不许开工。**」`docs/派发审计台账.md:3238` 亦记本轮「未拍板不写码」。
> 代码量/验证成本为 `[推断]` 估算，参照 `docs/_archive/开发流程与原则.md:34-36`（「写时拦截约 300 行 / 事后补救约 5000 行」的历史量级）与 `docs/_r42_retro.md:62`（甲方案 1 小时闭环）。

| 路线 | 核心动作 | 预估代码量 `[推断]` | 是否额外写库 | 竞态 | 失效场景 | 验证成本 |
|---|---|---|---|---|---|---|
| **A 扩 `VoiceGuard` 判据** | 改 `VoiceGuardLogic.kt`（表集合/列名/token 双长度）+ `VoiceGuard.kt`（表集合/`orig_type` 白名单/`imgPathsOf` 口径）；**零新 hook、零新文件** | 最小，约 **60~120 行**，改 2 个既有文件 | **否** | `gen` 缓存 + `invalidate()`（沿用）；连撤两条同源风险 | ⛔ 子查询 `IN (SELECT …)` 被主动放行 ⇒ 对 `ImgInfo2` 可能全漏；`execSQL`/预编译旁路；token 26/32 不兼容；漏视频 type=43 | **低**：复用已挂 hook，只需扩 `VoiceGuardLogicTest` + Q9 一轮真机；但**先要按 Q3 证删除走 `delete()` 而非 `execSQL`** |
| **B 新增独立媒体表 delete 钩子** | 新增 `MediaTableGuardLogic.kt`（纯判据）+ `MediaTableGuard.kt`（挂 `delete` + `execSQL`），`HookEntry.kt` 加 1 行注册，`RevokeRescue.record` 加 1 行 `invalidate` | 中，约 **200~350 行**，2 个新文件 + 2 行接线 | **否** | 同 A；两个 `delete` 回调需表名互斥；`execSQL` 热路径 | `execSQL` 返回 void ⇒ `param.result=1` 伪装不适用（无项目先例）；批量 `IN (SELECT …)` 无法定位单条；`INSERT OR REPLACE` 旁路；白名单漏表 | **中**：需新增 `installed` 日志并验挂 + 真机证 `execSQL` 通道是否真被撤回使用 |
| **C 到达即备份大图（事后补救）** | 扩 `VoiceBackupLogic.needsBackup` 到 type=3/43、改 `VoiceBackup.kt` 路径三形态、`RevokeRescue.record` 图片分支加 `backup` + `scheduleRestore`，可能需新挂图片查看入口做 restore 触发点 | 中~大，约 **250~400 行** | **否**（只写模块自有 `media_bak/`） | **最大**：到达时大图未下载完；无播放路径 hook ⇒ 缺 restore 触发点 | ⛔ 不解决 `ImgInfo2` 行被删（Q3 根因）；路径三形态未取证；冷启/TTL 后无触发点；native unlink | **高**：要新增「点开前 restore」触发链，项目无先例（`VoiceGuard.kt:196`） |
| **A+C / B+C 组合**（可选项，非自评推荐） | 用 A 或 B 阻止 DB 行删除，C 作为文件层兜底 | A/B 量 + C 量 | 否 | 两者叠加 | 各自失效场景叠加 | 高（两轮真机） |

**留给用户的拍板点**（本 lane 不替用户选）：
1. 先按 Q3 判据跑一次真机，确认「`ImgInfo2` 行是否被删」以及「删除走 `delete()` / `execSQL` / 预编译」—— **这个结果直接决定 A 是否够用、B 是否必须**；
2. A 的「子查询放行」保守判据（`VoiceGuardLogic.kt:66,151-155`）要不要为媒体表放宽 —— 放宽能拦 `ImgInfo2`，但可能破坏微信整会话 GC；
3. C 是否值得做（D4：只有 A/B 都拦不住才做），以及是否接受「新挂图片查看入口」这个新工作面；
4. 是否采用外部开源「改内容重插 + 微调时间戳」的呈现方式（那会变成**写微信库**，与三条候选的「零写库」不同，需单独评估）。

[DONE] Q5-Q6

## Q7 三档边界（进入 / 返回 / 复位）——复位档独立写，不与返回档合并

> 项目硬要求：`docs/_r44_chain.md:1026` 原话「三档边界（**R42-N1N6 教训：只写『进入 / 返回』会漏掉复位路径**）」（经 `docs/_r49_revokeimg_forensics.md:171` 转录）。R42 实证 `docs/_r42_n1n6_verify.md:60-83,125`「滚回顶部 / 下拉刷新」是唯一复位路径，曾因复位判据 `pos <= prev` 误判导致灰标闪烁（`_r42_n1n6_impl.md:419,429` 修为 `pos == prev`）。
> 本档逐条独立写，**③ 复位不写「同返回」**。

### 7.1 三档主表

| 档 | 触发 | 发生什么（锚点） | 用户可见 | 状态残留 |
|---|---|---|---|---|
| **① 进入**（刚撤回那一刻） | 好友（昵称见 Q9）撤回一张图片 | `AntiRevoke.revokeGuard.before` 拦 `updateWithOnConflict`（`AntiRevoke.kt:371,387-392`）⇒ `message` 行保持 type=3 / `imgPath` 在（`docs/_r49_revokeimg_forensics.md:117`）；`RevokeRescue.record()` 写 `rescued` 台账（`RevokeRescue.kt:100-104,122`）+ 图片分支 `protectMedia`（`:121-123`）+ 只读 `auditImage`（`:126,245-301`）；`anchorImage` 早在消息到达时已入池（`AntiRevoke.kt:461`）；微信异步 `executor.submit(vj0.p)` → `File.delete(image2/…)` 被 `VoiceFileGuard.deleteGuard` 命中（`:263-315`）⇒ `param.result=true`（`:306`）+ `VFG blocked MEDIA delete`（`:312`，60s 节流 `:79-80,309-313`）+ `dumpDir`（`:313`）。R21c 同构时间线：`docs/_r21c_voice_file.md:181-190`（+2ms 登记、+4ms 备份、+36ms 删除被拦） | 点开该图**能看到大图**（前提：文件在盘 + `ImgInfo2` 行在；`RevokeRescue.kt:295-299,303-311` 的 `big=OK(n>0)`、`docs/_r35_revoke_img_anchor.md:344`） | `protectedMedia` token（TTL **10 分钟** `VoiceFileGuard.kt:112`）、`hasMedia=true`（`:119,155`）、`logAt` 记 1 次（60s）、`imgAnchor` 1 项（`AntiRevoke.kt:467-472,494`，30 分钟 TTL）、`rescued` 台账 1 行、hook 全挂、**图片侧无备份文件**（`RevokeRescue.kt:121-127`）、微信库行 + `ImgInfo2` 行 + `image2` 原文件在 |
| **② 返回**（隔几分钟点开 / 切页回来 / 下拉刷新 / 冷启） | 纯 UI 重建或进程重启，**不产生新的撤回 UPDATE** | 切页/下拉刷新只读 `message` + `ImgInfo2`（`RevokeRescue.kt:268-277` 是这类查询形态），池与 hook 不变、文件仍在 ⇒ 能打开；**冷启**：`HookEntry` 重新 `install()`（`HookEntry.kt:208-262`）`[推断]`（Xposed 常规行为，本 lane 未验证），**内存状态全丢**（`protectedMedia`/`hasMedia`/`logAt`/`imgAnchor`），持久化状态保留（`rescued` 台账、`image2` 原文件、微信库行、语音 `voice_bak/`） | 隔几分钟点开**仍能看到**（10 分钟 TTL 内）；切页/下拉刷新仍能看到；**冷启后是否仍能看到 `[需真机]`** —— 池空了，若冷启后微信重新发起删除（补拉/重试）无人拦；`docs/_r42_prefix_refactor.md:71` 记同类「冷启/下拉刷新被覆写」风险 | `auditImage` 的 Cursor 每次 `close()`（`RevokeRescue.kt:286-288`）**不残留游标**；`AntiRevoke.msgQueryHandle()` 的 DB 句柄是长期缓存（`docs/_r16_g2_msgsnap.md:366-368`） |
| **③ 复位**（关开关 / 微信重试删除 / TTL 过期 / 清缓存） | 见 7.2 逐条，**与返回档本质不同** | 见 7.2 | 见 7.2 | 见 7.2 + 7.3 残留总表 |

### 7.2 ⛔ 复位档逐条（**不写「同返回」**）

| 复位动作 | 发生什么 | 与返回档的本质差异 |
|---|---|---|
| **关 `antirevoke.msg`** | `VoiceFileGuard.refresh()` 只改 `enabled`/`cfgAt`（`:191-194`），`on()` 有 **3 秒 TTL 缓存**（`:185-189`）⇒ 关后**最多 3 秒内仍会拦**；3 秒后 `deleteGuard` 每次仍跑（hook **不卸载**，`install()` 无 unhook，`:240-261`），但 `on()=false` 直接 `return`（`:277,304`）。`AntiRevoke.config` 由 `kvset` 主动 `refresh()`（`EntryReceiver.kt:181-182`）。⛔ **`protectedMedia` 不清空**，旧 token 靠 TTL 自然过期 ⇒ 关后短时间内再开，旧 token 可能仍在保护 | 返回档 hook 与开关**都不变**；复位档开关翻转 + 3 秒延迟 + hook 常驻空转 + 池残留。这是唯一区分「配置态 vs 已装代码」的档（`docs/_r48_review_autoplay.md:167`「复位档从无观测」的教训） |
| **微信重试删除** | `VoiceFileGuard.kt:64` 注释「微信可能重试」；`docs/_r42_bit3_design.md:287`「防撤回侧实测 **5 秒 360 次**」。重试落在 **TTL 内** ⇒ 每次都被拦，但 60s 节流只打 1 行日志（`:309-313`）；重试落在 **TTL 外或冷启后池空** ⇒ **无人拦、文件被删** | 返回档默认「删除只发生一次且已被拦」；复位档把「重试时机」变成自变量。⛔ 语音侧还有 `scheduleRestore` 兜底（`RevokeRescue.kt:137-144`），**图片侧没有**（Q3 缺①）⇒ 一旦漏拦即永久丢失。此即 R42「漏掉唯一复位路径」同型事故 |
| **TTL 过期** | `PROTECT_MEDIA_MS=600_000L`（`:112`）到期 ⇒ `mediaHit` 返回 null、`anyAlive` 把 `hasMedia` 复位 false（`:297-302,301`；`MediaGuardLogic.anyAlive` `:110-113`），池清空、快判回「连 path 都不取」的零开销（`:299-301`） | 返回档池**还在**；TTL 过期后再发生删除**无人拦**（微信清缓存/重试）⇒ 从「保护态」变为「裸奔态」，返回档无此转变 |
| **清缓存** | `op=logclear` 只清 LogX 环形缓冲（`EntryReceiver.kt:165-168`），**不动池/台账/文件**。若指微信「存储空间 → 清缓存」删 `image2`：开关**开着** ⇒ `File.delete` 被拦 ⇒ 存储只增不减（`VoiceFileGuard.kt:67-68,175-177` 自述「比播放失败严重得多」）；开关**关着** ⇒ 真删 | 返回档**不会删文件**；这是唯一能真正把已保护图片删掉的路径 |
| **反向对照：关开关再撤一张** | `AntiRevoke` 不再拦 UPDATE ⇒ 微信正常把 `message` 行改成撤回提示；`RevokeRescue.record()` 不触发 ⇒ **不入池、不写台账、不打 `RRV imgAudit`、不打 `VFG blocked MEDIA delete`**（`docs/_r35_revoke_img_anchor.md:347-348`）；`File.delete` 因 `on()=false` 放行 ⇒ 图片真删 | 返回档 `message` 行与台账**都还在**；复位档**原文消失**。用户可见：点开是「已撤回」提示 / 图片打不开 —— 反向对照的硬判据（Q9） |

### 7.3 复位档状态残留总表（五项规定项 + 补充）

| 状态 | 载体 | 复位后残留 | 清除条件 |
|---|---|---|---|
| **保护池 token** | `VoiceFileGuard.protectedMedia`（`:109,151-155`） | **不清空**，旧 token 仍在 | 10 分钟 TTL（`PROTECT_MEDIA_MS=600_000L`，`:112`）或进程死 |
| **备份文件** | `VoiceBackup.kt:42-45` `voice_bak/` | **图片侧没有**（`RevokeRescue.kt:121-127`）；语音备份保留 | 手工 / 微信 GC；图片无副本可还原（`docs/_r49_revokeimg_forensics.md:282`） |
| **已挂 hook** | `HookEntry.kt:208-262`（`VoiceGuard` `:223`、`VoiceFileGuard` `:233`、`SnsGuard` `:241`） | **永不卸载**，`install()` 无 unhook，关开关后空转 | 仅进程死亡 |
| **10 分钟 TTL** | `:112` | 到期后 `hasMedia=false`（`:301`） | 到期 |
| **DB 行** | `message` / `ImgInfo2` / `rescued` | `rescued` 台账**跨关开关、跨冷启保留**（模块自己的库，只有 `ardel` / `RevokeRescue.sync` 会动，`EntryReceiver.kt:586`、`RevokeRescue.kt:416,429`）；微信库行关开关后重试删除 / 清缓存**可真删** | 显式删除 / 微信删除 |
| 补充：`imgAnchor` | `AntiRevoke.kt:467-472,494` | 不清，冷启丢 | 30 分钟 TTL / 256 上限 / 进程死 |
| 补充：`logAt` 节流 | `VoiceFileGuard.kt:79-80` | 冷启丢 | 60s / 进程死 |
| 补充：开关缓存 | `VoiceFileGuard.on()` `:185-189`、`VoiceGuard.cfg()` `:151-154` | **3 秒 TTL 延迟** | `kvset` 主动 refresh（`EntryReceiver.kt:181-182`） |

[DONE] Q7

## Q8 单测设计：零 Android 依赖纯函数 + 用例矩阵 + 变异验证点

> `CLAUDE.md:27`：「判据（解析 / 对账 / SQL 改写 / 布局计算）放进**零 Android 依赖**类 + JVM 单测」。现有范式：`VoiceGuardLogic.kt:4`（自述「零 Android / 零 Xposed 依赖 → 可直接跑 JVM 单测」）、`MediaGuardLogic.kt:4`、`VoiceGuardLogicTest.kt:8-17`、`MediaGuardLogicTest.kt:9-15`。

### 8.1 可抽成纯函数的判据清单

| 判据 | 现有实现 | 文件:行号 |
|---|---|---|
| 表名白名单 | `shouldBlock` 第 1 步 `table.equals(TABLE, ignoreCase=true)` | `VoiceGuardLogic.kt:139-146`；常量 `:38` |
| 是否撤回期（时间窗/缓存） | `cacheUsable` / `stampAfterBuild` / `anyAlive` | `VoiceGuardLogic.kt:107-126`；`MediaGuardLogic.kt:110-113` |
| 是否媒体目录 | `inMediaDir`（`MEDIA_DIRS` 含两侧斜杠） | `MediaGuardLogic.kt:60-61,102-107` |
| token 抽取 / 长度口径 | `hexToken` / `isMd5` / `hexTokensFrom` | `MediaGuardLogic.kt:40-100`；`VoiceGuardLogic.kt:48,75-78` |
| 清理形态 / 子查询 / `=` `IN` | `EQ_OR_IN` / `SUBQUERY` + `shouldBlock` 第 2/4/5 步 | `VoiceGuardLogic.kt:55-58,66,147-157` |
| 命中判据 | `mediaHit` / `shouldBlock` 候选值求交 | `MediaGuardLogic.kt:125-130`；`VoiceGuardLogic.kt:159-170` |

**Q5 补丁新增应抽的纯函数**（不要写进 feature 层）：`isMediaTable(table)`（白名单，含大小写/null/近似表名）、`columnSetFor(table)`（`ImgInfo2` 用 `msglocalid`/`msgSvrId`/`bigImgPath`、`videoinfo2` 用 `filename`、`WxFileIndex2` 待定 `[需真机]`）、`tokenSpecFor(table)`（语音 26 位 / 图片 32 位分派，`MediaGuardLogic.kt:31-36` 已证「长度随消息类型变是已证实的事实」）、`isRevokeWindow(now, at, ttlMs)`。

### 8.2 用例矩阵（每组注明「能抓住哪个看似合理的错误实现」）

| 组 | 用例 | 输入 → 期望 | 能区分的错误实现 |
|---|---|---|---|
| 1 表名白名单 | `voiceinfo`/`ImgInfo2`/`videoinfo2`/`WxFileIndex2` + 大小写变体 → true；`message`/`rconversation`/`null`/`ImgInfo2X`/`imgInfo2_backup` → false | 仿 `VoiceGuardLogicTest.kt:32-37` 扩表 | `table.contains("ImgInfo2")` 会把 `ImgInfo2X` 误收；`==` 不忽略大小写会漏 `imginfo2` |
| 2 是否撤回期 | `now < deadline` → true；**`now == deadline` → false**（`MediaGuardLogic.anyAlive` `:111` 用 `v >= now`，边界必须钉死）；`deadline+1` → false；`cacheUsable` 空集 / `gen` 变化 / 过期 → false | `VoiceGuardLogic.kt:107-126`；`docs/_r21c_voice_sqlform.md:438-439` | 把 `>=` 写成 `>`（边界差 1ms）；省掉 `cachedGen == gen`（连撤两条漏拦）；空集缓存 |
| 3 是否媒体目录 | `/image2/ab/cd/<md5>`、`/video2/`、`/video/` → true；`myimage2/…`、`image2abc`、`/image2`（无尾斜杠）、`/voice2/…` → false | `MediaGuardLogic.kt:60-61,102-107`；`docs/_r21c_voice_file.md:144` | `path.contains("image2")` 误命中 `myimage2`；把语音目录也算进图片 |
| 4 token 长度/形态 | `THUMBNAIL_DIRPATH://th_<32md5>`、bare md5、大写归一 → md5（`MediaGuardLogicTest.kt:24-33,52-56`）；31/33 位、18/19 位纯数字串 → null（`:36-41,89-95`）；串里混入 sha1 40 / aes 64 → 取「恰好 32 位」段（`:65-76`）；26 位语音 imgPath → 26 位正则命中、32 位口径不命中 | `MediaGuardLogic.kt:13-30,66-100`；`VoiceGuardLogic.kt:41-48` | 下限改回 16（纯数字时间戳误收）；「取最长段」退化（静默失效，`MediaGuardLogic.kt:44-53`）；26/32 统一成一个正则 |
| 5 清理形态 / 子查询 | `<` `>` `LIKE` `IS NULL` `!=` → 不拦（`VoiceGuardLogicTest.kt:147-154`）；`FileName IN (SELECT …)` → 不拦（`:94-103`）；`whereClause=null` → 不拦（`:39-46`）；`FileNameX`/`index`/`into` → 不拦（`\b` 边界） | `VoiceGuardLogic.kt:50-58,66,147-157` | 把 `SUBQUERY` 放宽成「拦所有 delete」⇒ 破坏微信 GC（`VoiceGuardLogic.kt:23-26,151-155`）；删 `\b` 边界 |
| 6 命中 / 大小写 / 空集 | whereArgs 与内联 hex 双通道命中（`VoiceGuardLogicTest.kt:84-92`）、大写归一（`:121-129`）、空受保护集（`:105-111`）、25/27/34 位边界（`:131-143`） | `VoiceGuardLogic.kt:159-170` | 只查 `whereArgs` 漏内联 SQL；不做 lowercase 归一静默漏保护 |
| 7 缓存精确值 | `stampAfterBuild` 非空 → `now`；空集 → **精确断言** `now - ttlMs`（不能只断言 `>0`） | `docs/_r21c_voice_sqlform.md:438-439` | 空集返回 `now` 的错误实现在「只断言 >0」下**恒绿**（项目吃过这个亏） |

### 8.3 ⛔ 变异验证点（改坏哪几处必须变红；每条给「预期变红用例 + 条数」，0 条不算验证）

| # | 注入变异 | 必须变红的用例（预期条数） | 依据 / 非等价性 |
|---|---|---|---|
| 1 | 删 `cachedGen == gen` | `cacheIsStaleAfterNewRecord`（**恰好 1 条**） | `docs/_r21c_voice_sqlform.md:445` 实证「且只有它红」 |
| 2 | `stampAfterBuild` 空集也返回 `now` | `emptyBuildStampsAlreadyExpired` + `stampAfterBuildAvoidsUptimeZeroTrap`（**2 条**） | `docs/_r21c_voice_sqlform.md:446` 实证 |
| 3 | `MEDIA_DIRS` 两侧斜杠删掉（`/image2/`→`image2`） | 组 3 的 `myimage2` / `image2abc` 反例（**≥2 条**） | `docs/_r21c_voice_file.md:144` 同型真护栏「恰好 2 条」 |
| 4 | `hexToken` 「优先取恰好 32 位段」退化成「取最长段」 | `hexTokenPrefersMd5OverLongerHexSegment`（`MediaGuardLogicTest.kt:65-76`，**4 个断言**） | `MediaGuardLogic.kt:24-30,66-100` 记过此型静默失效 |
| 5 | `MIN_TOKEN`/`MD5_LEN` 32 → 16 | `hexTokenRejectsNumericOnlyString` + 19 位 msgSvrId 用例（**≥2 条**） | `MediaGuardLogicTest.kt:36-41,89-95`（19 位样本专门防「只放 18 位锁不住」） |
| 6 | `EQ_OR_IN` 的 `\b` 边界删掉 | 组 5 的 `FileNameX`/`index`/`into` 用例（**≥3 条**） | `VoiceGuardLogic.kt:50-53` 注释明写防这三误认 |
| 7 | `HEX` 的 `(?<!hex)` / `(?!hex)` 边界删掉 | `wrongLengthHexNotRecognized`（`VoiceGuardLogicTest.kt:131-143`，**≥3 断言**） | 25/27 位、34 位串内 26 位前缀 |
| 8 | `SUBQUERY` 放行删掉 | `subqueryIsNotBlocked`（`:94-103`，**2 条**） | 误拦整会话删除破坏 GC |
| 9 | `whereClause == null` 判断删掉 | `nullWhereClauseIsNotBlocked`（`:39-46`，**2 条**） | 删全表绝不拦 |
| 10 | 表名 `equals` 改 `contains` | 组 1 的 `ImgInfo2X` / `imgInfo2_backup` 用例（**≥2 条**） | 白名单外溢 |
| 11 | `param.result = FAKE_ROWS` 改成 `0` | `[需真机]`（非纯函数）：微信按受影响行数判断，0 会触发重试 | 掘金实验、`VoiceGuard.kt:73-74`「返回 1 比 0 更像成功」 |

### 8.4 ⛔ 拒绝「等价变异假验证」（项目实证教训，必须写）

- `docs/_r21c_voice_file.md:146,152-154`：`endsWith(".amr")` → `contains(".amr")` **0 条变红**，穷举 26 条输入两版**行为完全等价**（`.amr.tmp` 被 `isValidImgPath` 长度校验挡住）⇒ 天然无语义，**不算验证**；必须换成「整体删掉后缀判据」（`:147`）才 2 条变红。
- `docs/_r21c_voice_file.md:149-150`：改 `bucketOf` 内部切分「入参本来就是 md5，等于没改」⇒ 教训「**变异必须打在真正决定结果的那一层**」。
- `docs/_r21c_voice_sqlform.md:438-439`：用例只断言 `stamped > 0`，错误实现也绿（另一型假绿）⇒ 必须**精确断言** `assertEquals(7_000L, …)`。
- `docs/SOP审计日志.md:1617-1618`：第三条变异因构建炸了「没有取得断言级证据」，按「未验证」记录，**不算 PASS**。
- `docs/_r21c_voice_sqlform.md:448`：变异还原后必须全绿（`tests=632 failures=0` 范式）。
- ⇒ **本轮硬要求**：每条变异写明「预期变红的用例名 + 条数」，**0 条一律不算验证**；变异只打在判据分支 / 边界断言 / 常量这些决定结果的层，不打在内部无关实现；用例必须能区分正确实现与「看似合理的错误实现」（组 1~7 最后一列逐条对应）；还原后全量单测复绿。

[DONE] Q7-Q8

## Q9 真机判据 + 反向对照（对着「点开图片能不能看到」这个用户可见事实）

### 9.0 总判（用户可见事实优先）

> **主判据**：请 **verity** 在「**测试群聊**」里发一张图 → 用户侧**先别点开** → 等 3~5 秒请 **verity** 撤回 → **隔 3~5 分钟（仍在 10 分钟 TTL 内，`VoiceFileGuard.kt:112`）回到该聊天，点开那张被撤回的图片，用眼睛确认能看到大图**（不是转圈 / 空白 / 「图片已过期或已被清理」）。再各做一次：切页再回来点开、下拉刷新后点开、**冷启后点开**（Q7 返回档，冷启档 `[需真机]`）。
> 依据：`docs/开发提效与后续计划.md:89` 批次 A.2 的「能打开」是主判据；`docs/_r49_revokeimg_forensics.md:254`「判据对着用户能看见的事实写，不要只报 `blockedMediaCount` 这类内部计数器」；`CLAUDE.md` 同款硬规则。
> ⛔ **不要只 grep `VFG blocked MEDIA delete` 就下结论**：该串只在真拦下一次删除时打、60s 节流，0 行**不能反推功能失效**（`docs/_r49_revokeimg_forensics.md:106-107`；`VoiceFileGuard.kt:309-313`）。判「已加载」用 `VFG installed hooks=`，判「已入池」用 `VFG protectMedia`。

### 9.1 Q9 判据表（`python tools/dev.py` 子命令 / 广播 op / 过滤关键字）

> 子命令总览：`tools/dev.py:4-36`（`loop` / `q` / `probe` / `ui` / `restart`），分发在 `:302-320`。广播 action `com.lx.wxgroup.action.PROBE`、host `-p com.tencent.mm`（`tools/_broadcast.py:73`、`docs/_archive/标签数据源取证方案.md:70`；`tools/dev.py:53-54`）。`--grep` 必须见 ≥1 行，0 行一律判「探针没执行」（`tools/dev.py:295-299`；`docs/_r35_revoke_img_anchor.md:318` 闸门三）。

| 步 | 子命令 / 广播 op | 过滤关键字 | 期望（锚点） |
|---|---|---|---|
| 1 上机三件事① | `python tools/dev.py q 'VFG installed' 20` | `VFG installed` | `VFG installed hooks=delete,deleteOnExit`（`VoiceFileGuard.kt:260`）；`tools/dev.py:10-11` |
| 1b | `python tools/dev.py q 'AR installed' 20` | `AR installed` | `AR installed (antirevoke.msg=… alsoSelf=…)`（`AntiRevoke.kt:384`） |
| 1c | `python tools/dev.py q 'VG installed' 20` | `VG installed` | `VG installed hooks=delete enabled=true`（`VoiceGuard.kt:143`）⚠️ **只有 `delete`、没有 `deleteWithOnConflict` 属正常**（Q2 死代码结论） |
| 2 上机三件事② | `python tools/dev.py probe logclear --grep 'PROBE logclear'` | `PROBE logclear` | 清 LogX 环形缓冲（`EntryReceiver.kt:165-168`；`tools/dev.py:250-253`） |
| 3 上机三件事③ | 人工：微信在前台 | — | 后台广播 AMS 照收不投递（`CLAUDE.md:79`；`tools/dev.py:22-23,102,280-288`） |
| 4 开开关 | `python tools/dev.py probe kvset --key antirevoke.msg --val true --grep 'PROBE kvset'` | `PROBE kvset` | `kvset` 后 `AntiRevoke.refresh()` 当场生效、不用重启微信（`EntryReceiver.kt:176-182`；`docs/_r35_revoke_img_anchor.md:298`） |
| 4b 开日志 | `python tools/dev.py probe kvset --key debug.log --val true --grep 'PROBE kvset'` | `PROBE kvset` | `docs/_r35_revoke_img_anchor.md:299` |
| 5 证表 / 证通道 | `python tools/dev.py probe sql --q 'select count(*) from ImgInfo2' --grep SQLX` | `SQLX` | ⚠️ **先按 Q3 §3.2 证通道**：`op=sql` 走 `SnsGuard.runReadOnlySql`→`snsDb`（`EntryReceiver.kt:652-661`、`SnsGuard.kt:1280`、`:69`），静态推断打不到 `ImgInfo2`；若 `no such table` / `no sns db yet`，改手工广播 `--es op label --es sub sql --es sql '…'`（`CLAUDE.md:17`；`LabelHuntProbe.kt:246,478,1028`）。`op=sql` 用 `--q` 不是 `--val`（`tools/dev.py:21,261-264`） |
| 6 收图 | 人工：请 **verity** 在「**测试群聊**」发一张图，**不要点开** | — | `docs/_r35_revoke_img_anchor.md:305` |
| 7 到达锚点 | `python tools/dev.py q 'AR imgAnchor' 20` | `AR imgAnchor` | `type=3 md5=…(32) seen=1`（`AntiRevoke.kt:486-490`）；`BAD md5` ⇒ 形态变了 |
| 8 入池 | `python tools/dev.py q 'VFG protectMedia' 20` | `VFG protectMedia` | `ttl=600000ms pool=1`（`VoiceFileGuard.kt:156-159`） |
| 9 撤回 | 人工：等 3~5 秒请 **verity** 撤回 | — | R21c 同链 +36ms 完成删除拦截（`docs/_r21c_voice_file.md:181-188`） |
| 10 只读审计 | `python tools/dev.py q 'RRV imgAudit' 20` | `RRV imgAudit` | `row=1 big=OK(n>0)` 是**唯一采纳的 PASS 辅助判据**（`docs/_r35_revoke_img_anchor.md:344`；`RevokeRescue.kt:295-299,303-311`）；`row=0` ⇒ `ImgInfo2` 行没了（Q3 三态） |
| 11 辅助日志 | `python tools/dev.py q 'VFG blocked MEDIA delete' 20` | `VFG blocked MEDIA delete` | 辅助，**0 行不判失败**（见 9.0） |
| 12 ⭐ **总判** | 人工：隔 3~5 分钟**点开那张被撤回的图片** | 眼睛 | **能看到大图**；再做切页回来 / 下拉刷新 / 冷启后点开 |
| 13 锚点体检 | `python tools/dev.py probe ardump --grep 'AR cfg' 20` | `AR cfg` | `docs/_r35_revoke_img_anchor.md:312` |
| 14 可选 UI 断言 | `python tools/dev.py ui 已删除` | — | uiautomator 文本断言替截图（`tools/dev.py:29-33,186`） |
| 15 失败兜底 | `python tools/dev.py loop <场景> --grep 'VFG\|AR'` | `VFG|AR` | 自动 `loaded()` → lspfix → 双 force-stop 重试（`tools/dev.py:5-8,342-360`） |
| 16 重载模块 | `python tools/dev.py restart` | — | 双 force-stop + 拉起、LogX 归零（`tools/dev.py:35-36,311`） |

**广播 op 速查**（`EntryReceiver.kt:162` `when(op)` 分发）：`kvset`（`:176-182`）、`logclear`（`:165-168`）、`sql`（`:654-661`）、`ardump`（`:553-554`）、`ardel`（`:586`）、`voicebak`（`:210-227`，⚠️ 读 extra `sql`，`tools/dev.py probe` 不映射该 extra ⇒ 必须手工广播，见 `docs/_r49_revokeimg_forensics.md:283`）。⛔ `op=label sub=sql` 用 `--es sql`（`CLAUDE.md:17`），与 `op=sql` 的 `--q` 不同。

### 9.2 反向对照（硬要求）

| 项 | 内容 |
|---|---|
| 关哪个 Key | **`antirevoke.msg`**（`Keys.ANTIREVOKE_MSG`，`data/Keys.kt:140`） |
| **默认值（本轮核源码确认）** | **`"false"`** —— `Keys.kt:437` `PrefSpec(Keys.ANTIREVOKE_MSG, PrefKind.SWITCH, "false", "防撤回", "防止消息撤回")`；`Keys.kt:436` 注释「防撤回域：默认全关，由用户自己开（决策 #2）」；`Kv.kt:10,16`「默认值只在 `Prefs.ALL` 里定义一次，读不到就回落默认值」。⇒ 关回默认即恢复出厂行为。⚠️ `revoke_switches_preview.html:93` 显示「当前 开」是**当前运行值/预览稿**，不是默认值，别混淆 |
| 命令 | `python tools/dev.py probe kvset --key antirevoke.msg --val false --grep 'PROBE kvset'`（`docs/_r35_revoke_img_anchor.md:315`） |
| ⚠️ 等 >3 秒 | `VoiceFileGuard.on()` 有 3 秒 TTL 缓存（`:185-189`），关后立刻复现会误判「开关无效」 |
| 复现动作 | 再请 **verity** 在「**测试群聊**」发一张图并撤回，**只改这一个自变量**（`docs/_r48_review_autoplay.md:105`「每组只改一个 key；改完不冷启，冷启是第二个自变量」） |
| **关后预期用户可见** | 消息变成「**verity** 撤回了一条消息」提示 / 原文消失，**点开图片打不开**；日志侧 `AR blocked revoke` / `RRV imgAudit` / `VFG blocked MEDIA delete` **三条全部消失，撤回提示正常出现**（`docs/_r35_revoke_img_anchor.md:347-348`） |
| 别关错 | `antirevoke.restore_view`（`Keys.kt:188`）只是显示层伪装，`Keys.kt:180-186` 明写「防撤回照常在拦，消息原文一直躺在库里」⇒ 关它**不能**做反向对照（`docs/_r49_revokeimg_forensics.md:247`） |
| 配套调试 Key | `antirevoke.debug_self`（`Keys.kt:190-197`，默认 false）：开着可自己撤自己验链路，但会引入**第二个自变量**，严格单变量对照不要用；验完记得关（`:195`） |
| 恢复现场 | `python tools/dev.py probe logclear --grep 'PROBE logclear'` 清验证日志，并把 `antirevoke.msg` **开回 true**（`docs/_r49_revokeimg_forensics.md:291`） |
| 回滚不需冷启 | `EntryReceiver.kt:181`「改完立刻重读，不用重启微信」；`docs/_r48_review_autoplay.md:105` 反而禁止为回滚而冷启 |

### 9.3 ⛔ 好友一律用昵称（不许只报 wxid）

- 本轮一律用：**verity**（好友）、**测试群聊**（群）、**文件传输助手**（备选，自己发自己撤，须先开 `antirevoke.debug_self`）。
- 依据：`docs/_r19_M4M6M7_真机验证.md:8-14`（昵称 + id 并列，昵称铁律）；`docs/_r21c_voice_file.md:29,178`（R21c 语音闭环用的也是「**测试群聊**」群里 **verity** 发的语音）；`docs/_r49_revokeimg_forensics.md:251-253`；`docs/派发审计台账.md:3239` 硬约束「提及好友一律用昵称，不许只报 wxid」。
- ⛔ 本文档**不写裸 wxid**（遵守 Q9 要求）。

### 9.4 与 Q7 的对应

「隔几分钟点开 / 切页 / 下拉刷新 / 冷启」= Q7 **② 返回档**；「关开关再撤一张」= Q7 **③ 复位档**（不是「同返回」）。两档都要留观测，复位档从无观测是 `docs/_r48_review_autoplay.md:167` 记过的教训。

---

## 交付自检与边界声明

| 交付件 | 位置 |
|---|---|
| Q1 对账表 | §Q1 五列对账表 + 「`VoiceGuard` 能否复用扩展」特别核对 |
| Q6 路线对照表 | §Q6 六列表（A / B / C / A+C·B+C），**不自评最优，留用户拍板** |
| Q7 三档边界表 | §Q7 §7.1 主表 + §7.2 复位逐条（未写「同返回」）+ §7.3 五项状态残留总表 |
| Q8 用例矩阵 | §Q8 §8.2 七组矩阵（末列逐条标注「能抓住哪个看似合理的错误实现」）+ §8.3 11 个变异验证点 + §8.4 拒绝等价变异假验证 |
| Q9 判据表 | §Q9 §9.1 判据表 + §9.2 反向对照 + §9.3 昵称 |
| `[DONE] ALL` | 文末 |

**取证边界（如实标注）**：本 lane 全程**只读**，唯一写入文件即本文档；未改任何源码 / 测试 / 配置 / 其它报告，未碰 adb / 真机 / Gradle / 装包 / kvset。外部实现（CPWechatXposed / XposedRedDevil / 掘金）为**主会话 WebSearch 二手转录**，未在本 lane 复跑；`_cache/dex/`、`_mods/`、`shell.txt`、smali 留档在本工作区 `Glob` 均无匹配 ⇒ dex 级结论转引自已落盘转录文档（`docs/_r21c_voice_sqlform.md`、`docs/conclusions/itemview_voice.md`）与只读复核脚本 `tools/_r21c_verify_sql.py`，原始 dex 逐字节复核 `[需真机]` / 需主会话留档。微X 防撤回实现**未取证、未解壳**（Q4）。所有真机判据均标 `[需真机]`，由主会话执行；`ImgInfo2` 行幸存性、`image2` 文件名形态、`hd` 命名、冷启后能否打开均为 `[推断]` 或未取证，未写成事实。

落盘批次：`[DONE] skeleton` → `[DONE] Q1-Q2-a` → `[DONE] Q1-Q2` → `[DONE] Q3` → `[DONE] Q3-Q4` → `[DONE] Q5` → `[DONE] Q5-Q6` → `[DONE] Q7` → `[DONE] Q7-Q8` → `[DONE] Q9` → 下行为 ALL。

[DONE] Q9

[DONE] ALL
