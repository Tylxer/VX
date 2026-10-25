# _r16 f3 fliterPre P0 修复记录

任务：修复 `MomentsLogic.fliterPre` 的 `var i = 2` 错误（应为 `var i = 0`），同步 KDoc 与单测，更新方案文档。

## 状态

- [x] 修改 MomentsLogic.kt
- [x] 修改测试文件
- [x] 跑测试全绿
- [x] 更新 docs/_r15_sns_cache_plan.md

## [DONE] MomentsLogic.kt

`fliterPre` 的 `var i = 2` 已改为 `var i = 0`，KDoc 全文重写：
- 「从下标 0 开始，跳过所有前导 `'0'`」
- 示例改为：`"15038644089917813396"` → 原样返回；`"0015038873180398883448"` → `"15038873180398883448"`
- 保留「真机 mediaId 长度 8 种，不要截断到定长」
- 新增溯源注释（DEX 反汇编 `[19] const/4 v3,#0`）

## [DONE] 测试文件

### SnsCacheMatchTest.kt
- `fliterPreDropsFirstTwoCharsThenLeadingZeros` 改名 `fliterPreSkipsLeadingZerosOnly`：
  - `"15038644089917813396"` → 原样返回
  - `"0015038873180398883448"` → `"15038873180398883448"`
  - `"001500"` → `"1500"`
- `fliterPreKeepsLeadingNonZero`：`"12345"` → `"12345"`
- `fliterPreEmptyAndAllZeroCases`：删除「长度>2 但前 2 位之后全是 0」的 `"1500"` 断言（旧语义），保留全 0 断言
- 新增 `fliterPreOnlySkipsZeroNotOtherChars`（`"12"`/`"10"`/`"00a0b"`）、`fliterPreSkipsMultipleLeadingZeros`（`"00123"`/`"0003"`）
- `candidatesTierOrderIsBigThenMidThenThumb` 改用带前导 0 输入 `"0015038873180398883448"`，双候选仍 6 项
- `candidatesFallBackToMediaIdWhenFliterPreIsEmpty` 输入 `"1500"` → `"000"`（旧 `"1500"` 在新语义下不再为空）
- `candidatesPreferOriginalOverThumbnail` 改用带前导 0 输入
- `excludedNamesNeverWinAgainstRealCandidate` 候选输入 `"15038644089917813396"` → `"0038644089917813396"`（其 fliterPre = `38644089917813396` 命中索引里的 `snsu_38644089917813396`）

### SnsProtoTest.kt
- `mediaIdIsReturnedVerbatim`：反证改用带前导 0 输入 `"0015038873180398883448"`（`fliterPre(raw) != raw` 成立）
- `endToEndCandidatesFromParsedId`：输入 `"0015038873180398883448"`，候选 0 改 `snsb_15038873180398883448`
- `endToEndAlreadyTrimmedIdStillHits`：`trimmed` 改用 `"15038873180398883448"`
- `endToEndMultipleIdsMapToDistinctFiles`：输入 `"0015038873180398883448"`/`"0015038873180398883449"`

## [DONE] docs/_r15_sns_cache_plan.md
- §4.3 表 `MD5(fliterPre(mediaId))[0]/[1]` 行下方加注记：数值用修正前 `i=2` 算的，已作废，需用 `i=0` 重算；其余两行不受影响
- 「重大订正」节末尾补「✅ 已修（2026-10-24）」句

## [DONE] 测试结果

命令：`python tools/gradle_run.py testDebugUnitTest`

输出：
```
EXIT=0
BUILD SUCCESSFUL in 19s
```

汇总（从 `wxgroup/app/build/test-results/testDebugUnitTest/TEST-*.xml` 聚合）：
```
test classes=26  tests=606  failures=0  errors=0  skipped=0
```

- 与基线（606 tests / 0 failures / 0 errors / 26 类）完全一致：测试数不变。
- 说明：本次是「改名 + 改断言值」，没有增删测试方法，故 tests 数不变。
- 涉及的两个测试类 `SnsCacheMatchTest`、`SnsProtoTest` 均为新文件（`??` 未跟踪），`git diff --stat`
  不计入；`MomentsLogic.kt` 的 `git diff --stat` 显示 +253/-12（该文件还包含本轮 R16 其它已改内容，
  本 lane 只动了 fliterPre 的 KDoc 与 `var i`，未触碰其它函数）。

## 遗留
- `MD5(fliterPre(mediaId))[0]/[1]` 表值 4/1029 尚未重算（需真机文件快照，本 lane 纯离线，手上只有文件名无 mediaId），已在文档加「数值已作废」注记。


