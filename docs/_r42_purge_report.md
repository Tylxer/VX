# R42 乙方案代码拆除报告

> 执行人：general-purpose-1 · 时间：2026-11-05
> HEAD：652a969（含完整乙方案代码，可回溯）
> 目标：删除「路线乙 = sourceType 事后补救」全部代码，保留「路线甲 = 写时拦截」，构建可编译、甲不回归。

## 步骤 1：勘察现状与归属确认
[DONE] git status 显示工作树干净（仅 docs/ 与 wxgroup/_*.py 等未跟踪文件，均不在 app/src 内，不触碰）。
[DONE] HEAD = 652a969，符合预期。

### 文件归属确认（读头部注释）
- SnsTimelineSourceFix.kt → 「时间线来源位修复 srcfix 手动触发版」，乙（补位核心）→ 删
- SnsTimelineSrcLogic.kt → srcfix 纯判据，乙 → 删
- SnsRoundLedger.kt → P0 取证账本（响应轮次对账），乙 → 删
- SnsTimelineMerge.kt / SnsTimelineMergeLogic.kt → 方案A Response-Merge 时间线注入，乙 → 删
- SnsAlbumMerge.kt / SnsAlbumMergeLogic.kt → 相册页 Response-Merge 注入，乙 → 删
- SnsDeletedTag.kt / SnsDeletedTagLogic.kt → 「[删除已拦截]」灰标 UI，乙 → 删
- SnsRestoreLogic.kt → 从 sns_archive 归档恢复判据，乙 → 删
- SnsSqlSpy.kt → rawQuery 旁听取证，乙 → 删
- SnsSyncDelProbe.kt → 同步删除取证探针，乙 → 删
- SnsSql.kt → delete 语句纯字符串识别，**甲在用 → 保留**
- SnsBlockProbe.kt → 路线甲写时拦截账本/采样器，**保留**
- **SnsTag.kt / SnsTagLogic.kt → 判定为「朋友圈灰标」（乙方案④），删除。**
  - 依据：SnsTag.kt 头部注释明确「朋友圈动态『[已删除]』标签（需求 6.2 第三项）」，object 内部 `MARK = "wxgroup.sns.deleted.tag"`，是给被删动态打灰标的 UI 落点，与好友分组无关。
  - SnsTagLogic.kt 头部「SnsTag / 将来的评论标签共用的纯逻辑」，实际代码仅被 SnsTag.kt（灰标）与 SnsDeletedTagLogic.kt（灰标）调用，无分组功能依赖。
  - 精确引用核查：`SnsTag` 对象的实际代码调用点仅在 EntryReceiver.kt(snstag 子命令) 与 HookEntry.kt(install)；其余均为注释性引用，无编译依赖。

### 测试文件归属
- 删除：SnsAlbumMergeLogicTest / SnsDeletedTagLogicTest / SnsDeletedTagTest / SnsRestoreLogicTest / SnsTagLogicTest / SnsTimelineMergeTest / SnsTimelineSrcLogicTest（7 个，均针对已删类）
- 保留：SnsSqlTest（SnsSql 甲在用）、SnsCacheMatchTest / SnsProtoTest（R15 图片缓存，与乙无关）

## 步骤 2：删除乙方案文件（feature 14 + 单测 7）
[DONE] 已删除 feature 文件 14 个：SnsTimelineSourceFix / SnsTimelineSrcLogic / SnsRoundLedger / SnsTimelineMerge(+Logic) / SnsAlbumMerge(+Logic) / SnsDeletedTag(+Logic) / SnsRestoreLogic / SnsSqlSpy / SnsSyncDelProbe / SnsTag / SnsTagLogic。
[DONE] 已删除单测 7 个：SnsTimelineSrcLogicTest / SnsRestoreLogicTest / SnsDeletedTagLogicTest / SnsDeletedTagTest / SnsTagLogicTest / SnsTimelineMergeTest / SnsAlbumMergeLogicTest。
[DONE] 保留 SnsBlockProbe.kt（甲的账本/采样器）、SnsGuard.kt（待裁剪）、SnsSql.kt（待裁剪 insertSql/placeholders）。
[DONE] 保留单测 SnsSqlTest / SnsCacheMatchTest / SnsProtoTest（均与乙无关）。

grep 校验（feature 目录）：
  ls feature | grep Sns → 仅 SnsBlockProbe / SnsGuard / SnsSql
  ls test | grep Sns → 仅 SnsCacheMatchTest / SnsProtoTest / SnsSqlTest

## 步骤 3：裁剪 SnsGuard.kt
[DONE] 行数 2828 → 1207（净删 1621 行）。
[DONE] 保留（甲的骨架，未动）：
  - 6 个钩子注册（install 内 delete:99 / execSQL:102 / updateWithOnConflict after:108 / updateWithOnConflict.before:113 / insertWithOnConflict:118 / insert:121）。
  - snsBlockGuard（before 摘 sourceType 键）、onSnsUpdateBlockAfter（after 回查）、delete 计数 onDelete、execSQL 计数 onExecSql、INSERT 通道计数 countInsertSource。
  - listCurrentSns / liveRows / cLong / cText / cBlob（导出数据源，MomentsExport 用）。
  - runReadOnlySql / pragmaReadOnly / cellOf（op=sql 取证）。
  - probeMediaIds / probeMediaPaths / probeHit / scanCacheNames / scanDigitPaths / digitRuns（A4 图片缓存取证，与乙无关）。
  - comments（评论表取证，其 KDoc 已明确「去留已裁定保留」）。
[DONE] 删除（乙方案事后补救 + 归档恢复 + 写库 + 指向已删类的探针）：
  - one / winCoverage / probeVisibleBits / srcMap / setSrc / scalar / rows / dump / rankSql（诊断段整段，809-1165 行）。
  - FlagState / queryFlag / readFlag / countRows / flags / restoreAll / restore / restoreBatch / rollbackStep / snsInfoColumns / tableInfo。
  - 写微信库整段：toLogic / contentValuesOf / insertRow / insertViaExecSql / execSqlMethod / updateFlag / writeFlag / undoInsert / updateMethod / CONFLICT_IGNORE / insertMethod。
  - show / forceDelete / simulateDelete。
  - VISIBLE_BIT / WINDOW 常量、bypass ThreadLocal（仅被删除代码使用）、import android.content.ContentValues、import Handler/HandlerThread（FixWorker 已删）。
[DONE] stat() 重写：`SNS stat blockEnabled=${cfg().blockEnabled} snsDb=${snsDb != null}`（不再残留 enabled/blocked/archived 字段）。
[DONE] 头部 KDoc「拦 + 归档」策略改为「写时拦截（R42 路线甲）」。
[DONE] 清理对 SnsTimelineSourceFix / SnsRoundLedger / SnsSqlSpy / restoreVisibility / GroupDb.archiveSns / readRow / sns_archive 的注释引用。

grep 校验（SnsGuard.kt 内对已删类的残留）：
  grep -n "SnsRestoreLogic|SnsTimelineSourceFix|SnsSqlSpy|SnsRoundLedger|SnsTimelineSrcLogic|SnsDeletedTag|SnsTag|SnsTimelineMerge|SnsAlbumMerge|VISIBLE_BIT|restoreVisibility|CONFLICT_IGNORE" → 仅 1 行：
  460:  // execSQL 计数判据（随 SnsTimelineSrcLogic 删除内联到这里）—— 说明性注释，合法保留。

## 步骤 4：裁剪 HookEntry.kt 与 EntryReceiver.kt
[DONE] HookEntry.kt：
  - 删 6 个 import：SnsAlbumMerge / SnsSqlSpy / SnsSyncDelProbe / SnsDeletedTag / SnsTag / SnsTimelineSourceFix。
  - 删 7 处 install：SnsTag.install / SnsDeletedTag.install / SnsSqlSpy.install / SnsAlbumMerge 注释块 / SnsTimelineSourceFix.installAlbumTrigger / installAlbumSyncTrigger / SnsSyncDelProbe.install。
  - 保留 SnsGuard.install / MomentsExport.install。
  - 注释更新：SnsGuard 安装处由「拦 DELETE + 归档」改为「R42 路线甲写时拦截」；MomentsExport 处「sns_archive →」改为「扫当前 SnsInfo →」。
[DONE] EntryReceiver.kt（1186 → 934 行）：
  - 删 kvset 里 SnsTag.refresh()。
  - snsg 子命令删：win/winreset/ls/dump/setsrc/srcmap/sqlspy/one/show/del/merge/flags/fixall/restore/restoreall/cols/plan/apply/pragma/simdel/srcstat/srcfix。
  - 保留 snsg 子命令：stat/cmts/mid/mpath/hit/block/blockstat/trace + else。
  - 删整个 snstag 分支（SnsTag 灰标）。
  - 保留 op=sql（runReadOnlySql）与 op=sns（SnsHuntProbe，与乙无关）。

grep 校验（两文件对已删类的残留）：
  grep -n "SnsTimelineMerge|SnsTimelineSourceFix|SnsTag|SnsSqlSpy|SnsRoundLedger|SnsDeletedTag|SnsAlbumMerge|SnsSyncDelProbe|restoreBatch|snsInfoColumns|simulateDelete|forceDelete|winCoverage|listArchived|setSrc|srcMap|rankSql|restoreAll" HookEntry.kt EntryReceiver.kt → 0 命中。

## 步骤 5：裁剪 GroupDb.kt / Keys.kt / MomentsExport.kt / SnsBlockProbe.kt + 单测适配
[DONE] GroupDb.kt（1519 → 1013 行）：
  - 删 T_SNS / T_SRC 常量、createSnsTable / createSrcTable、onCreate 两处建表调用、onUpgrade 的 <7 / <9 分支。
  - 删全部 SNS 读写方法：archiveSns / readRow / listArchived / listSrcFixedIds / srcFixedCount 及 Cursor.toSnsRow() 扩展。
  - 保留 data class SnsRow（SnsGuard.listCurrentSns 的导出数据源），KDoc 标题改为「朋友圈动态（当前 SnsInfo 导出）」。
  - **DB_VERSION 不升版**。理由：删表走 DROP TABLE 无字段变更；T_SNS/T_SRC 建表均为 CREATE TABLE IF NOT EXISTS；老库残留表无任何代码读写，不影响分组数据。删表无需 onUpgrade 迁移。
[DONE] Keys.kt：
  - 删 ANTIREVOKE_SNS_DELETED_TAG（灰标，随 SnsTag 删除）、DEBUG_SNS_SYNCDEL_PROBE 及对应 PrefSpec。
  - ANTIREVOKE_SNS KDoc 改为「R42 起驱动路线甲」。
  - ANTIREVOKE_SNS_BLOCK KDoc 改为调试覆盖语义（blockEnabled = getBool(ANTIREVOKE_SNS) || getBool(ANTIREVOKE_SNS_BLOCK)），默认 "false" 不打架。
[DONE] MomentsExport.kt（1837 → 约 1470 行）：
  - Src 枚举只余 CURRENT；nextSource() 恒 false；主循环与 backfillImages 去掉 when(cur.src) 与归档分支。
  - 删 curRows / archRows / curNoDb、GroupDb.current() 依赖。
  - 删归档探针 probeSnsCache / downloadMedia / logImgUrls 及辅助 hexHead / topFieldNums / scanEnd / nestedTop / in6dump。
  - probe() 删 snsprobe / imgurl / imgdl 分支，else 串改为 subs=estat|path|export|snsidx|imgfill|scan。
  - fillSrcCode / fillSrc / fillSrcName 简化为单源；多处 KDoc 清理归档表述。
[DONE] SnsBlockProbe.kt：仅 switch() KDoc 由「主开关负责事后补位」改为「R42 路线甲的调试覆盖开关」，代码零改动。
[DONE] 单测适配（2 处编译适配）：
  - MomentsExportScanTest.kt：删 import SnsScanCursor.Src；删 sameSnsIdIsDedupedAcrossSources / nextSourceSwitchesToArchiveAndResetsCursor / archiveIsLastSourceAndSwitchReturnsFalse 三个归档相关测试；KDoc 四件事→三件事。
  - MomentsBackfillCursorTest.kt：删 import assertFalse/assertTrue；seek* 由 Src.ARCHIVE 改为 Src.CURRENT；删 seekToArchiveDoesNotSwitchBackToCurrent / seekToCurrentStillSwitchesToArchiveFromItsNewest。

grep 校验（app/src 全量对已删类/方法的残留）：
  grep -rn "SnsTimelineSourceFix|SnsTimelineSrcLogic|SnsRoundLedger|SnsTimelineMerge|SnsAlbumMerge|SnsDeletedTag|SnsRestoreLogic|SnsSqlSpy|SnsSyncDelProbe|ANTIREVOKE_SNS_DELETED_TAG|DEBUG_SNS_SYNCDEL_PROBE|restoreVisibility|VISIBLE_BIT|archiveSns|sns_archive|T_SNS|T_SRC" → 仅剩注释/字符串级引用（无编译依赖）：
  - SnsGuard.kt:460 → 「execSQL 计数判据（随 SnsTimelineSrcLogic 删除内联到这里）」—— 历史说明性注释，合法。
  - MomentsExport.kt:1422 → 「依赖 sns_archive 归档表，随归档表一并删除」—— 历史说明性注释。
  - MomentsLogic.kt:35/216/814 → 导出文本对 sns_archive 的表述（814 为面向用户的说明字符串，非代码依赖）。
  - SnsViewProbe.kt / HintBadge.kt / MomentsAuto.kt / ContactFilterLogic.kt / HintBadgeLogic.kt / HintTextLogic.kt / PickerTabLogic.kt / MomentsLogic.kt / MomentsNotifyLogic.kt → 均为「同 SnsTag / SnsTagLogic 的约束」式注释引用，指向已删类但只作类比说明，无 import / 无方法调用。
  结论：全部为注释/字符串级，编译器不产生任何引用，不构成编译依赖。

## 甲方案未受影响的自证

R42 路线甲（写时拦截）依赖的 6 个常驻钩子，在改完后仍按原样注册于 SnsGuard.install()（wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsGuard.kt）：

| # | 方法 | 语义 | 注册位置（文件:行号） |
|---|------|------|----------------------|
| 1 | delete | 裸 DELETE 旁证计数 | SnsGuard.kt:93 |
| 2 | execSQL | 裸 SQL 五路计数 | SnsGuard.kt:96 |
| 3 | updateWithOnConflict (after) | 写后回查/通道计数 | SnsGuard.kt:102 |
| 4 | updateWithOnConflict (before) | **路线甲摘 sourceType 键** | SnsGuard.kt:107 |
| 5 | insertWithOnConflict | INSERT 通道计数 | SnsGuard.kt:112 |
| 6 | insert | INSERT 通道计数 | SnsGuard.kt:115 |

自证结论：
- 6 个钩子的注册语句（`XposedBridge.hookAllMethods(...)`）全程未改动，仅因删 import 使行号整体前移 6 行（步骤 3 记录的 99/102/108/113/118/121 → 本表 93/96/102/107/112/115）。
- 路线甲核心 `snsBlockGuard`（before 半场，反射 remove("sourceType")）与 `onSnsUpdateBlockAfter`（after 回查）完整保留，未做任何改动。
- 乙方案的所有触发面（sourceType 事后补位、归档恢复、灰标 UI、时间线/相册注入、SQL 旁听取证、同步删除探针）均已删除，不会再与甲的「写时拦截」产生并发写回冲突。

## 步骤 6：构建验证
[DONE] `cd wxgroup && ./gradlew assembleDebug` → **BUILD SUCCESSFUL in 51s**（35 actionable tasks: 6 executed, 29 up-to-date）。
[DONE] 产物路径：`wxgroup/app/build/outputs/apk/debug/app-debug.apk`（3,011,226 字节，2026-11-05 23:13 生成）。
[DONE] 编译期仅有 3 条与本次拆除无关的既有 warning（EntryReceiver.kt 的 `get()` deprecated ×2、`insertHint` deprecated ×1），无 error。
[DONE] 附加校验 `./gradlew compileDebugUnitTestKotlin` → **BUILD SUCCESSFUL**，确认删/改后的单测源码仍可编译。

[ALL-DONE]
