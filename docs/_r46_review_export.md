# R46 独立复核报告 — 朋友圈视频导出收口 + R44 废弃探针摘除

## 复核范围
- 复核提交：`4ac6aae`（三处「朋友圈视频导出收口」改动 + R44 废弃探针摘除）
- 复核性质：只读、独立第二视角，不改任何源码 / 测试文件；不接触 adb / 真机 / Gradle / 装包
- 复核对象：
  - `feature/MomentsLogic.kt` → `shouldDecryptVideo`
  - `feature/MomentsExport.kt` → `downloadVideo` gate 调用（约 1456 行）、`viddl` 分支 `dir.mkdirs()`（约 1530 行）、生产 `sub=export` 分支（约 815 行）
  - `HookEntry.kt`、`entry/EntryReceiver.kt` → R44 探针 `SnsDispatchProbe` / `VidTrigProbe` 接线是否摘净
  - `app/src/test/java/com/lx/wxgroup/feature/MomentsVideoGateTest.kt`
- 已定案设计（不推翻，仅核实现是否走样）：
  - 朋友圈视频 CDN 回来的前 128KB 为密文，key 来自 `SnsInfo.content` protobuf `f8→f5→f25`
  - `f25>0` 必须解密；`f25==0` 是合法明文、禁止 XOR
  - `X-encflag` 只作日志、绝不参与判据（R45 真缺陷是响应头缺失会让加密视频静默留成密文）

## 复核方法
1. 逐行读当前工作树源码，自己得出结论、不预设结论。
2. `git show 4ac6aae -- <file>` 对照改动前后，确认 gate 调用替换未夹带其它行为改动。
3. 全源码树 grep 探针残留关键字，核对 when 分支删除后的可达性 / 未用变量 / import。
4. 对测试做变异分析：把判据分别改成 `>=0` / `!=0` / 恒真 / 恒假，检查现有用例是否至少一条变红。
5. 每条独立给 `[PASS|FAIL] 问题N — 证据: 文件:行号 — 说明`；每答完 2 个问题追加落盘一次。
6. 末尾给「总体结论：可装包 / 需修复」+ 问题清单 + `[DONE]`。

---

## 复核结论（问题 1–2）

[PASS] 问题1 — 证据: wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsLogic.kt:871；wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsExport.kt:1456,1418,1459,1464,1448 — shouldDecryptVideo 函数体 `= encKey > 0L` 与旧判据 `encKey > 0L` 逐字等价（同表达式、Long、0L 字面量）；X-encflag / encFlag 未以任何形式重新混进判据。
- 等价性：`MomentsLogic.kt:871` 为 `fun shouldDecryptVideo(encKey: Long): Boolean = encKey > 0L`；调用点 `MomentsExport.kt:1456` `if (MomentsLogic.shouldDecryptVideo(encKey))` 只传 `encKey`。
- encFlag 全树落点：`MomentsExport.kt:1418` 读取（`readHeaderInt(c, "X-encflag")`）、`:1459` 日志 `encflag=$encFlag`、`:1464` 日志 `encflag=$encFlag`、`:1448` 警示注释、`:1485` 函数注释。单参签名使 encFlag 物理上无法进入判据；`SnsVideoDecProbe.decryptInPlace(f, key, enclen)`（`SnsVideoDecProbe.kt:338`）亦不接收 encFlag。
- 代码与警示注释区分：`MomentsExport.kt:1443-1455` 整段是 `//` 注释，其中 `:1448` 的 `encFlag != 0` 出现在「一旦把 `encFlag != 0` 写成 AND 条件」这句反例警示里，是说明文字、不是可执行条件；`MomentsLogic.kt:867` 同为注释警示。真正的判据只有 `:1456` + `:871` 两行。
- 附带核验：`X-enclen`（`MomentsExport.kt:1419`）只作解密长度参数，缺失/非法回退 0 → `SnsVideoDecProbe.kt:331` 回退 `DEF_LEN=131072`，不参与「是否解密」，不构成 R45 同类缺陷。

[PASS] 问题2 — 证据: wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsExport.kt:1456-1472；`git show 4ac6aae -- wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsExport.kt` — 当前实现逐条满足行为契约；但 git 层面无法做「替换前后」严格对照（见证据边界）。
- 解密失败仍只打日志：`:1457` `val ok = SnsVideoDecProbe.decryptInPlace(tmp, encKey, encLen)`，`ok` 仅出现在 `:1459` 日志串 `ok=$ok`，全函数无 `if (!ok)` 分支 → 失败不中断、不删 tmp。
- 保留密文：gate 块（`:1456-1466`）内无 `tmp.delete()`；解密失败时 tmp 内容原样为密文，`:1467` `tmp.renameTo(dst)` 仍把它落成 dst；catch 块（`:1473-1481`）只在下载异常时删 tmp。
- `tmp.renameTo(dst)` 仍执行：`:1467`，位于 gate 之后的无条件路径，仅 rename 失败才走 `:1468-1470` 删 tmp + `IO_FAIL`。
- outcome 仍 COPIED：`:1472` `return VidResult(VidOutcome.COPIED, n, dst)`。
- 日志字符串与字段：`:1459` `vid decrypt key=$encKey ok=$ok encflag=$encFlag enclen=$encLen dst=${dst.name}`、`:1464` `vid decrypt skip (encKey=$encKey encflag=$encFlag) dst=${dst.name}`，`encflag` / `enclen` 仍在日志字段中（符合「只作日志」）。
- 证据边界（独立发现）：`git show 4ac6aae --unified=0 -- .../MomentsExport.kt` 显示 `downloadVideo` 与 `copyVideosForRow` 整块为本提交首次新增（hunk `@@ -1249,0 +1338,262 @@`），父提交 `5358408` 无该函数 —— `git grep -n -E "downloadVideo|shouldDecryptVideo|decryptInPlace|X-encflag|encKey" 5358408 -- .../MomentsExport.kt` 返回空。因此 R45 的「替换前」版本只存在于未提交工作树，git 无法逐字对照「日志字符串与字段不变」，只能做当前实现的符合性核验。
- 无夹带改动：本提交 MomentsExport.kt 共 5 行删除，全部与 gate 无关（`DL_READ_MS` 30_000→120_000、`DL_UA`→`VID_UA`/`VID_REFERER`、install 日志加 `videos=` 字段、`probe` 签名加 `id`、else 提示串加 `vidfill|viddl`）；`downloadVideo` 块内零删除行，不存在「替换 gate 时顺手改其它行为」的空间。

## 复核结论（问题 3–4）

[PASS] 问题3 — 证据: wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsExport.kt:1533,1534,1535,815,1530-1532；`git show 4ac6aae -- .../MomentsExport.kt`；`git grep -n "if (!dir.exists() && !dir.mkdirs())" 5358408 -- .../MomentsExport.kt` 命中 761 行 — mkdirs 顺序正确、可消除 outcome=space 假阳性；生产 `sub=export` 分支未被误改；目录已存在时无副作用。
- 顺序：`:1533` `if (!dir.exists()) dir.mkdirs()` 紧邻 `:1534` `if (dir.usableSpace < MomentsLogic.MIN_FREE_BYTES)` 之前，先建目录再取剩余空间。
- 消除假阳性机理：`File.usableSpace` 对不存在的路径返回 0L，`0L < MIN_FREE_BYTES` 会误落 `:1535` `VidOutcome.SPACE`；`:1530-1532` 注释亦明确此根因。建目录后 `usableSpace` 返回真实剩余空间 → `outcome=space` 假阳性消除。
- 生产分支未误改：`:815` `if (!dir.exists() && !dir.mkdirs())` 未出现在 4ac6aae 的任何 diff hunk 中；父提交 `5358408` 的同一行在 761 行（761→815 的行号偏移来自本提交前部插入的 54 行），内容一致。diff 中 `@@ -811,0 +866,2 @@` 只新增 `if (c.copyVideos) copyVideosForRow(...)` 两行。
- 已存在时无副作用：`:1533` 的 `!dir.exists()` 守卫使目录已存在时**根本不调用** `mkdirs()`；即便调用，`File.mkdirs()` 对已存在目录返回 false、不创建、不修改、不抛异常。`copyVideosForRow`（`:1505-1539`，diff 注释「生产与 `viddl` 探针共用本函数」）在生产路径共用，因生产已建过目录，此处为 no-op，与 `:1532` 注释一致。
- 非阻断观察：`:1533` 忽略 `mkdirs()` 返回值，若创建失败（权限/坏路径）仍会落到 `:1534` 并按 `SPACE` 记账；这是探针诊断分支的归类粒度，不影响「消除目录不存在导致的假阳性」这一结论。

[PASS] 问题4 — 证据: wxgroup/app/src/main/java/com/lx/wxgroup/HookEntry.kt:1-357(尤其 :44-54,:121-129)；wxgroup/app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt:75,162,284-285,346,376,422-423,457-466,674-686,863,866,869；`git grep 4ac6aae -- HookEntry.kt EntryReceiver.kt`；`git show 4ac6aae -- HookEntry.kt` — 探针活引用与残留 import 全净，无不可达分支 / 空 case / 未用变量，HookEntry diff 归零。
- 全源码树 grep `SnsDispatchProbe|VidTrigProbe|vidtrig|dispatch|EXTRA_SUB3|getStringExtra("sub3")|"sub3"`：在 `wxgroup/app/src` 零命中（其它文件里的 `dispatch` 均为 `dispatchTouchEvent` / `dispatchDraw` / `dispatcher` 等无关词汇）。两个探针类仅存于 `docs/_archive/r44_probes/SnsDispatchProbe.kt:64` / `VidTrigProbe.kt:47`，属归档、不在源码树、不参与编译。`git grep -n -E "SnsDispatchProbe|VidTrigProbe|vidtrig|dispatch|EXTRA_SUB3|sub3" 4ac6aae -- HookEntry.kt EntryReceiver.kt` 返回空。
- `EXTRA_SUB2` 保留为活引用而非残留：`EntryReceiver.kt:75` 定义；`:346`→`:376` `SnsBlockProbe.switch(sub2)`、`:863`→`:866`/`:869` 使用；另有 `:284`→`:285`、`:422`→`:423` 以字面量 `"sub2"` 读取。全树无 `EXTRA_SUB3`、无 `sub3` 变量。
- EntryReceiver 删分支后的结构：4ac6aae 的 EntryReceiver diff 为 `16+/1-`，**无任何 when 分支删除行**，两处改动是 `moments` 分支增 `id` 参数（`:457-466`，`probe(sub, n, id)`）与新增 `viddec` op 分支（`:674-686`）。当前 `when (op)`（`:162`）分支完整、有 else 兜底（如 `:384` `SNSG unknown sub=$sub`），无不可达分支、无空 case；所有 `sub2` 局部变量均被使用（`:284`→285、`:346`→376、`:422`→423/434-436、`:863`→866/869），不存在未用变量导致的编译告警或失败来源。
- HookEntry diff 归零：`git show 4ac6aae -- .../HookEntry.kt` 仅输出 commit header、无 diff；`git diff HEAD -- .../HookEntry.kt` 为空。全文 357 行的 import 列表（`:44-54`）无 `probe.SnsDispatchProbe` / `probe.VidTrigProbe`，bootstrap 的 `prepare(lpparam)` 列表（`:121-129`）亦无注册。

## 复核结论（问题 5–6）

[PASS] 问题5 — 证据: wxgroup/app/src/test/java/com/lx/wxgroup/feature/MomentsVideoGateTest.kt:24-25,31,37-38,44,50；wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsLogic.kt:871 — 4 种变异每种至少一条断言变红；另发现「调用点变异」抓不到的覆盖缺口（见末条）。
- 变异 `>=0L`（`encKey >= 0L`）：`0L` 由 false→true，`:31` `zeroKeyIsPlaintext` 的 `assertFalse(shouldDecryptVideo(0L))` 变红。
- 变异 `!=0L`（`encKey != 0L`）：`-1L` / `Long.MIN_VALUE` 由 false→true，`:37`、`:38` `negativeKeyNeverDecrypts` 变红。
- 变异恒真（`true`）：`:31`、`:37`、`:38` 三条 `assertFalse` 变红。
- 变异恒假（`false`）：`:24`、`:25`（3805912539L / 2965254400L）、`:44`（1L）、`:50`（Long.MAX_VALUE）的 `assertTrue` 变红。
- 结论：所列 4 种变异均可被现有 7 条断言抓到，无「抓不到」的变异，无需为这 4 种补用例。
- **独立发现的覆盖缺口（明确指出 + 补用例建议）**：7 条断言只锁 `MomentsLogic.shouldDecryptVideo` 纯函数，**不覆盖 `MomentsExport.kt:1456` 的调用点**。若把 1456 行改回 `if (encKey > 0L && encFlag != 0)`（正是 R45 真缺陷形态），或写成 `if (shouldDecryptVideo(encKey) && encFlag != 0)`，甚至把 `encFlag` 以任何形式混回调用点，现有测试**全绿**。`downloadVideo` 依赖 `HttpURLConnection` / `File`，无 JVM 单测覆盖，「gate 调用是否真的替换」本次仅靠代码审查确认。建议补一条约束调用点的用例：把「是否解密」决策抽成可注入的 `decide(encKey, encFlag)`（或可测薄封装），断言 `encFlag` 取 0 / 非 0 / 缺失时决策不变；或增加一条静态契约检查，确保 `downloadVideo` 中 `encFlag` 除日志字符串外零引用。

[PASS] 问题6 — 证据: wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsExport.kt:1456,1518-1519,1533,815；wxgroup/app/src/main/java/com/lx/wxgroup/data/Keys.kt:295,514；wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsLogic.kt:871；wxgroup/app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt:457-466,674-686；wxgroup/app/src/main/java/com/lx/wxgroup/HookEntry.kt:121-129 — 三处最小回退路径如下（只读列示，未执行）。
1. gate 判据收口：调用点 `MomentsExport.kt:1456` 改回 `if (encKey > 0L)` 即等价回退（`MomentsLogic.kt:871` 与 `MomentsVideoGateTest.kt` 均为纯新增，可保留、无运行时影响）。真机最小止血**无需改码**：`MomentsExport.kt:1518-1519` 的 `Kv.getBool(Keys.MOMENTS_VIDEO_DECRYPT)` 置 false（键 `Keys.kt:295`、默认 true `Keys.kt:514`），`encKey` 被置 0 → 全量跳过解密、保留密文，等价于回到解密能力启用前的行为且不丢文件。
2. `viddl` 建目录：`MomentsExport.kt:1533` 单行 `if (!dir.exists()) dir.mkdirs()` 删除即回退；`MomentsExport.kt:815` 生产 `sub=export` 分支本就未改，**不要动**。
3. R44 探针摘除：`HookEntry.kt` 本提交 diff 归零，无可回退；`EntryReceiver.kt` 本提交仅新增（`moments` 的 `id` 参数 `:457-466`、`viddec` 分支 `:674-686`）。若确需恢复探针，从 `docs/_archive/r44_probes/SnsDispatchProbe.kt` / `VidTrigProbe.kt` 拷回 `wxgroup/app/src/main/java/com/lx/wxgroup/probe/`，并在 `HookEntry.kt:121-129` 补 `prepare(lpparam)` 注册、`EntryReceiver.kt:162` 的 `when (op)` 补回 `vidtrig` / `dispatch` 分支及 `EXTRA_SUB3` 接线 —— 属「重新引入废弃探针」，一般不需要。⚠️ `SnsVideoDecProbe.kt` 与 `viddec` 分支是本次解密能力本体，不在探针摘除范围，回滚时勿误删。

## 总体结论

总体结论：可装包

6 条逐条复核均为 PASS，未发现阻断问题或实现走样：判据与旧 `encKey > 0L` 逐字等价且 `X-encflag` 未混入；gate 调用替换未夹带其它行为改动；`mkdirs()` 顺序正确、生产 export 分支未误改、目录已存在时无副作用；R44 探针接线与 HookEntry diff 全净；4 种判据变异均被单测抓到；回滚路径清晰。

发现的问题清单：
- 阻断问题：无。
- 非阻断 1（问题2 证据边界）：`downloadVideo` / `copyVideosForRow` 在 4ac6aae 为首次提交，父提交 5358408 无该函数，git 无法做「gate 替换前后」严格逐字对照；本次以当前实现符合性核验 + diff 零夹带定论。建议后续把替换前版本纳入版本管理或留对照快照。
- 非阻断 2（问题5 覆盖缺口，建议补用例）：`MomentsVideoGateTest` 只锁纯函数判据，锁不住 `MomentsExport.kt:1456` 调用点把 `encFlag` 重新混入（R45 真缺陷形态）；这类变异现有 7 条断言全绿。建议补一条约束调用点的用例或静态契约检查（详见问题5）。
- 非阻断 3（问题3 观察）：`MomentsExport.kt:1533` 忽略 `mkdirs()` 返回值，创建失败时仍按 `SPACE` 记账；仅影响探针诊断归类粒度，非生产阻断。

[DONE]
