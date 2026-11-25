# R86c B5 独立复核 NO-GO 修正记录（修正实现 lane · r86c-b5-fix）

> 角色：`r86c-b5-fix`，B5 独立复核 NO-GO 后的修正实现 lane（Pro 档）。工作根 `C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`，源码 `wxgroup/`，Git Bash 路径用正斜杠。
> 性质 = 判据设计（B5 可达成判据重定）+ Hook 链路/反射改动 + 变异单测设计 ⇒ Pro 类（子代理任务，非主会话自审）。
> 模型档位：HY4-FLASH-FREE（用户会话临时规则，不写入任何文件）。三档标注【源码实证·高】/【真机实证·高】/【dex 实证·高】/【推断·高|中|低】/【UNKNOWN】；【推断】不写成【已证】，每条带文件:行号锚点。
> 硬纪律：只改 `wxgroup/app/src/main/java/com/lx/wxgroup/probe/B6CheckExistProbe.kt`、`wxgroup/app/src/test/java/com/lx/wxgroup/probe/B6CheckExistProbeTest.kt`、本文档三处；禁碰 adb/装机/设备开关/gradle/git commit；三闸由主会话统一跑。
> 判据纪律：C2 只记录 `field_exist_whencheck`/`field_filemd5` 前缀与长度，**不裁决 md5 身份**；`hit`/`UploadHitCacheType` 只记录不裁决。
> 落盘策略（防打断）：骨架 + §1 写盘 → §2 裁决写盘 → 改源码 → §3 写盘 → 改单测 → §4 写盘 → §5/§6 → 真 `[DONE ALL]`。**先改文件再回报，产物以落盘为准。**

状态进度：
- [x] §1 两项 BLOCKER 逐条处置 + 档位
- [x] §2 二选一裁决（含 (b) 区分张力的正面回答）
- [x] §3 改动清单
- [x] §4 变异判红表
- [x] §5 三闸待跑
- [x] §6 诚实边界
- [x] 真 `[DONE ALL]`

---

## §1 两项 BLOCKER 逐条处置 + 档位

### BLOCKER-1（note 虚假陈述 + 目标判据零有效达成路径）—— 逐条处置

**事实承认（不再辩解，【源码实证·高】）**：
- 原 note `wxgroup/docs/_r86c_b5_impl_note.md:38/40` 声称 `resolveFullpath(u, map, fileId, xmlMd5)` 为 4 参、主路径按「内容 MD5 前 8 == 源图身份前缀」准入、`:47` 称该准入为【真机实证·高 + dex 实证·高】；实测源码 `B6CheckExistProbe.kt:356` **仅 2 参**（`u, map`），全文零 `MessageDigest`/`digest`/`readBytes`（复核报告 `_r86c_b5_review.md:33` 已逐行核）；`:367` filter 仅 `.jpg_hevc` + 非空 + `hits.size==1`，零字节内容校验；`:374-375` 回退 `File.createTempFile("b6x_", ".jpg_hevc")` 写 3B `byteArrayOf(0x42,0x36,0x58)` 随机名占位。⇒「对齐 q4b 原图字节」为**虚假陈述**，本 note 予以撤回。
- 零有效达成路径的机制链【源码实证·高 + 真机实证·高】：generalize `_r86c_generalize_verdict.md:105` 证同根多图共享、≥2 张 hevc ⇒ `hits.size==1` 几乎不成立 ⇒ 几乎必走回退；回退 3B 字节的摘要物理上不可能等于源图身份（`cff4f5a5`/`484bc088`，q4b `:92` 字节决定论）⇒ 原判据「C2 第二次回包 exist=true + 原图身份 md5」在旧落码下**零有效达成路径**。本修正不沿用该判据，重定见 §2（选 b）。

**处置 1 —— 撤回虚假表述**：本实现实际准入 =「同根唯一 hevc 文件名（纯文件名/非空/唯一性，无内容校验）**或** 3B 临时占位」，不是「内容摘要准入」。与 q4b 字节决定论的冲突如实写【UNKNOWN】（旧稿把 checkExist 秒传探测链与真实上传链混同，"两链分叉"仅为【推断·高】，无 dex/真机直接证）；不再声称"主路径对齐本轮事实"。

**处置 2 —— fail-closed 表述纠偏**【源码实证·高】：`File.createTempFile` 落在系统临时目录、几乎不会失败 ⇒ `:246-249` 的 `fullpath==null` 分支几乎不触发 ⇒ **几乎总下发**；原 note「全失败 fail-closed 不下发」只是**字面兜底**，不是运行期常态。本 note 如实标注。

**处置 3 —— 目标判据重定**：见 §2。选 (b) 后，判据不再要求 md5 前缀等于原图身份，从而消除 BLOCKER-1 的「原图身份零有效达成路径」；但**不伪造**一个可达的"身份判据"，C2 只记录身份量、不裁决身份。

**档位小结**：虚假陈述撤回【源码实证·高】；同根多 hevc 致主路径不可达【真机实证·高（generalize :105）+ 推断·高】；3B 摘要≠源图身份【源码实证·高 + 推断·高】；两链分叉解释【推断·高，不写成已证】；冲突后果【UNKNOWN】。

`[DONE §1]`

---

## §2 二选一裁决（含 (b) 区分张力的正面回答）

### 2.1 裁决 = **(b) 判据降级**，不甩给主会话

**正面结论：选 (b)** —— B5 判据降为「只验三字段能否启动 checkExist 秒传探测」，**不声称命中原图**。

**为什么不选 (a)（真实字节准入）**：(a) 的前置是「须先证 `field_fullpath` 指向真实 `image2` 桶 `.jpg_hevc` 且内容摘要 == 源图身份的字节文件可获得」。该前置**未闭合**：generalize `_r86c_generalize_verdict.md:105-108`（Q2-b #1/#2a/#2c）【真机实证·高】已证转发帧 `u`/`map` 无本地路径属性、源图 md5/g9c 恒空或不等价；generalize `:185` Q4 对「普适可落码判据」判 NO-GO(0.75)。在此前提下选 (a) 只能把**不可达主路径写成"已实现"**，正是 BLOCKER-1 的成因，不能消除 NO-GO。故本 lane 不把 (a) 标为已实现，仅在 §6 作为后续前置未闭合方向记录。

**(b) 的合法性**：复核报告 `_r86c_b5_review.md:137` 明确把 (b) 列为推荐方向，且要求「正面回答区分张力、无法区分则如实写【UNKNOWN】、不要伪造假判据」。下面 2.2/2.3 正面回答。

### 2.2 B5 新判据（本 lane 锁定，写进 note 与单测锁）

> **B5 唯一可达成判据（降级后）**：同一会话内，以「三字段补齐」为唯一自变量的 off/on A/B 对照中，Proxy C2 **第二次（真实回包）**回调的 `field_exist_whencheck` 从 b6step 基线 `false` 翻到 `true`，**且** `field_filemd5` 长度从 `0` 翻到 `>0`（`logC2Shape` 已排除空壳/读错对象）。
> **判据纪律**：`md5Pre` / `md5Len` **只记录、不裁决身份**；不把任何 md5 前缀（含 `cff4f5a5`、`ca47f83e`）当"原图身份命中"。`hit` / `UploadHitCacheType` 只记录不裁决。
> 不翻（off/on 两次 C2 均 `exist=false md5Len=0`）⇒ 三字段未能启动 checkExist ⇒ B6 方向 NO-GO，不伪造通过章。

### 2.3 正面回答区分张力（"有回包"不足以区分有效/无效）

**张力属实**【源码实证·高 + 真机实证·高】：b6step 在「off、缺三字段」时**本来就有真实第二次回包**，但内容是 `exist=false md5Len=0`（真实出处 `docs/_r86b_b6step_state_final_raw.txt:213-214`：`00:50:22.931 B6X C2 exist=NA md5Pre=NA md5Len=-1` 空壳 → `00:50:23.056 B6X C2 exist=false md5Pre= md5Len=0` 真实回包；`docs/_r86b_b6step_impl_result.md:91/98`）。所以「有回包」这个事实本身**不能**区分「补字段有效」与「补字段无效」——这一点本 lane 明确承认，不用它做判据。

**区分点 = 回包的"结果量"从无到有，而非"回包存在"**：
1. 已有先例支撑「缺 fullpath ⇒ hitCheck 不启动」：Q4 B-1 `fullpathLen=0 → hit=0`（q4 裁决 `:66`，q4b `:98` 把 `field_fullpath` 定为唯一字节入口 `setFilePath`）。**仅证 fullpath 非空是 hitCheck 启动的必要条件【真机实证·高】**；b6step 的 `exist=false md5Len=0` 是"未启动/未命中"的表现。但**「哪类字节足以命中 cff4f5a5」=【UNKNOWN 未闭合】**（`docs/_r86c_fileid_vs_fullpath_verdict.md:77` 原文），补齐三字段后 `exist` 是否翻 =【推断·中 → 现场 off/on A/B 升格】，**不是 dex/真机已证**；不得由「必要」读出「补齐必翻」。
2. 已有真机支撑「本会话服务端此刻可返回 exist=true」：b3 stock 同时刻对照 `_r86c_b3_stock_raw.txt:152` 实测 `md5=cff4f5a5 md5Len=32 hit=1 exist=true fidPre=305f0201`【真机实证·高】；但该 `exist=true` **伴随真实 hevc 路径**（路径串长 124、`.jpg_hevc`），不是探针的 3B 临时占位。它只能证「服务端此刻可返回 exist=true、Q1③ 服务端过期已排除」，**不证「mid 锚点导致命中」**（fileId 零区分力：`docs/_r86c_fileid_vs_fullpath_verdict.md:57` 输出 fileId 逐字相同却一清晰一模糊），**也不证「补三字段必翻」**。
3. 因此，若补齐三字段后 C2 第二次回包 `exist: false→true` **且** `md5Len: 0→>0`，则该变化在 off/on 紧邻对照下对应「三字段补齐」这一自变量（同会话、同图，Q1③服务端过期已由 b3 `:152` 排除）⇒ 作为「三字段启动了 checkExist 秒传探测」的机制信号【推断·中 → 现场 off/on A/B 升格】，**非 dex/真机已证**。这是**机制验证**结论，不是"命中原图/已清晰"结论。

**诚实边界（不伪造判据）**【推断·中 / UNKNOWN】：「exist 翻转 ⇒ 三字段有效」仍是**机制层面的归纳**，非 dex 直接证明（native 内部如何由字节合成回包 md5 不外推，`startC2CUpload` acc=0x109 不可挂，q4b `:84`）。若现场 off 态也出现 `exist=true`，则本判据失效 ⇒ 【UNKNOWN】。最小下一步（给主会话/现场）：**同会话 off→on→off 三次回包对照 + stock 同步对照**，确认 exist 翻转与三字段同进同退；若不同进同退，如实判 UNKNOWN 并把 B5 结论写为「checkExist 探测链是否可被三字段启动＝未定」，不得写"通过"。

### 2.4 不许写死常量 / 不裁决身份（硬约束）

- 代码内禁写 `45522`/`148929`/`cff4f5a5`/`484bc088`/`36`/`124`/`ca47f83e` 任何字节数或摘要指纹常量（保留 M8 变异锁并扩 M16）；这些字面量只作 note 历史样本佐证。**md5 前缀永不作为"原图身份"裁决量**。
- C2 KDoc / 日志删除「cff4f5a5 / ca47f83e 作原图身份」表述；`ca47f83e` 与 `cff4f5a5` 是否同一原图仍【UNKNOWN】（q4b `:84`），本探针不回答该问题。
- **撤回标记 `MD5_CLAIM_RETRACTED`**：原 note §1.2/§1.3 的「内容 MD5 准入 / 4 参签名 / 对齐 q4b 原图字节」全部撤回；源码全文无内容摘要计算，本 note 不得声称内容 MD5 准入（单测 `noteMd5ClaimConsistentWithSource` 锁此文本一致性）。
- **`ca47f83e` 非原图身份锚点**：mid 命中（源码 `:218-229` big→mid→thumb）即便回包前缀为 `ca47f83e`，也只记录、**非原图身份**、不按原图身份裁决（单测 `criterionNoCa47f83eIdentity` 锁此）。

### 2.5 判据字段映射（与复核 §③ 第 3 条一致）

| 量 | B5 中的作用 | 档位 |
|---|---|---|
| `field_exist_whencheck` | **唯一判据量**：false→true 作为"三字段启动 checkExist"信号 | 【推断·高，机制先例支撑；现场 off/on 对照后升格】 |
| `field_filemd5` 长度 | 辅助判据：0→>0 排除空壳（与 logC2Shape 合用） | 【真机实证·高（b6step md5Len=0 vs b3 32）】 |
| `field_filemd5` 前缀 | **只记录不裁决**（不判身份、不判原图） | 【UNKNOWN（cff4f5a5 vs ca47f83e 同图性）】 |
| `hit` / `UploadHitCacheType` | 只记录不裁决（映射两轮翻转，q4b `:78/:101`） | 【真机实证·高】 |
| `field_fileId` 来源 | 强制记录 `idVia`，mid/thumb 标注"非原图锚点"，不当 big 用 | 【源码实证·高（:218-229）+ 真机实证·高（direction :164 坏图无 big）】 |

`[DONE §2]`

---

## §3 改动清单（只改两源码文件 + 本文档，先文件后回报）

### 3.1 `wxgroup/app/src/main/java/com/lx/wxgroup/probe/B6CheckExistProbe.kt`（已落码）

- `:3` 新增 `import android.os.SystemClock`（仅用于扫描时间预算，无其它 Android 行为）。
- `:14-19` KDoc 判据重定：目标改为「三字段能否启动 checkExist 秒传探测」，删掉 `ca47f83e` 作原图身份的表述，明确「md5 前缀只记录、不裁决身份、不声称命中原图」；判据指向 fix note §2，原 impl note §1/§2 标为已撤回。
- `:37-39` `field_fullpath` 取值说明诚实化：主路径 =「同根唯一 hevc 文件名 + 非空」，**不计算内容摘要、不做身份准入**；与 q4b 字节决定论的关系标【UNKNOWN】。
- `:57-58` 新增 `SCAN_TIME_BUDGET_MS = 250L`（时间预算，非字节常量）。
- `:239-243` **强制记录 `idVia` + mid/thumb WARN 标注（不拦截下发）**：`idVia` 取 `map` / `regex:big` / `regex:mid` / `regex:thumb`；`regex:mid`/`regex:thumb` 打 WARN `非原图锚点（不当 big 用）`，但 mid 值仍写入 task（`:265`）并 dispatch（`:316`），**不拦截下发**。污染消除的成因是**判据降级为不看 md5 身份**（review2 Q2.2 / `_r86c_b5_review2.md:62-63`），**不是这条 WARN**；`idVia` 只作审计回放。
- `:257-260` `fullpath==null` fail-closed 日志补 `idVia=$idVia`（"同时校验 fileId 身份"落码，便于回放是否 mid 污染）。
- `:369-401` `resolveFullpath(u, map)` 保持 2 参、无 `MessageDigest`/`readBytes`：主路径从 `File(root)` 收窄为 `File(root, "image2")`；加 `SystemClock.elapsedRealtime()` 截止时间 + `timedOut` 标志，超时或半途扫描一律放弃主路径（`!timedOut && hits.size == 1`），保留 `createTempFile("b6x_", ".jpg_hevc")` 3B 回退与 `no_fullpath (fail-closed)` 字面。
- KDoc `:362-368`：标明仅按唯一候选+非空准入、限定 `image2/`+时间预算；旧稿「身份靠 fileId 锚点 / 两链分叉」降为【推断·高】、与 q4b 冲突【UNKNOWN】，不据此裁决身份。
- **历史注释同步（非机制变更，主会话三闸全绿后补记）**：`arm` KDoc `:153`「构造不带 fullpath」→「构造含 B5 三字段（含 field_fullpath）」；回退注释 `:395` 删「身份靠 fileId 锚点」肯定句 →「只作 hitCheck 非空字节入口启动令牌、身份判定不属本探针（md5 前缀只记录不裁决）」。两处纯注释字面，与本文 §1 撤回声明对齐；代码逻辑/判据常量/单测（30 例 17 变异）均未动，三闸由主会话重跑。
- **未改（只读红线三边界保持）**：C2 handler `:285-303` 只读反射 + `logC2Shape` + `return 0` 未动；`I`/`z` 返回默认值未动；`enabled=false` 首行 return 未动；`TASK_FIELDS`/`MAP_KEYS`/`LISTENER_*`/`install`（只找类不 hook）/`dispatch`/`put`/`findCls`/三字段写入/`byteArrayOf(0x42,0x36,0x58)` 回退均未动；不碰 native 上传入口、不调 `b1.j6`/`FileSender.send`、不写宿主字段、不发消息。

### 3.2 `wxgroup/app/src/test/java/com/lx/wxgroup/probe/B6CheckExistProbeTest.kt`（见 §4，随后落码）

- 在现有 20 例 / 11 变异（M1~M11）基础上，新增文本一致性锁（note 不得在源码无内容摘要计算时声称"内容 MD5 准入"；判据字面不得含 `ca47f83e` 作原图身份）与新增准入/来源校验的变异判红（M12~M17）。保持纯 JVM、零 Android/Xposed 依赖。

`[DONE §3]`

---

## §4 变异判红表（真实源码变异，确认非等价）

各变异在**源码文本上真实改动**（`mutated != src` 前置断言），断言对应锁判红（`assertFalse(validator(mutated))`）；恢复后全绿。M1~M11 沿用原实现 lane，M12~M17 为本 lane 新增（覆盖判据剔除 `ca47f83e`、fileId 来源校验、性能边界、文本一致性）。计 30 例 = 13 条契约锁 + 17 条变异判红。

| 变异 | 真实改动 | 判红测试 | 判红对象 |
|---|---|---|---|
| M1 | 删 `put(task, "field_fullpath", fullpath)` | `mutationDeleteFullpathFieldIsDetected` | `b5FieldsValid` |
| M2 | 删 `put(task, "field_enable_hitcheck", true)` | `mutationDeleteEnableHitFieldIsDetected` | `b5FieldsValid` |
| M3 | 删 `put(task, "field_fake_bigfile_signature", "")` | `mutationDeleteFakeSigFieldIsDetected` | `b5FieldsValid` |
| M4 | `put(task, "field_fileId", fileId)` → 写空串 | `mutationClearFileIdIsDetected` | `fileIdWriteValid` |
| M5 | C2 handler 注入 `callMethod(f,"j6")` | `mutationC2CallsJ6IsDetected` | `c2ReadOnlyValid` |
| M6 | install 注入 `findAndHookMethod("startC2CUpload",…)` | `mutationNativeHookIsDetected` | `noNativeHookValid` |
| M7 | `TASK_FIELDS` 漏 `field_fullpath` | `mutationTaskFieldDroppedIsDetected` | `taskFieldsValid` |
| M8 | 写死 `REF_MD5_PRE="cff4f5a5"` | `mutationHardcodedFingerprintIsDetected` | `noHardcodedFingerprint` |
| M9 | Proxy 删 `z` 分支（`z`→`Z`） | `mutationProxyTwoMethodsIsDetected` | `listenerMethodsValid` |
| M10 | 读错字段名 `exist_whenchek` | `mutationWrongC2FieldNameIsDetected` | `c2FieldsValid` |
| M11 | 删 map 的 md5 键 | `mutationMapKeyRemovedIsDetected` | `mapKeysValid` |
| **M12** | 删 mid/thumb「非原图锚点」WARN | `mutationIdViaMidWarnRemovedIsDetected` | `idViaSourceValid` |
| **M13** | 删 fail-closed 日志的 `idVia` 校验 | `mutationIdViaFailClosedRemovedIsDetected` | `idViaSourceValid` |
| **M14** | `File(root,"image2")` → `File(root)`（全数据根） | `mutationImage2ScopeRemovedIsDetected` | `scanBoundedValid` |
| **M15** | `SystemClock.elapsedRealtime()` → `System.currentTimeMillis()` | `mutationTimeBudgetRemovedIsDetected` | `scanBoundedValid` |
| **M16** | 注入 `REF_MD5_PRE="ca47f83e"` | `mutationCa47f83eInjectedIsDetected` | `ca47f83eExcludedValid` |
| **M17** | 注入 `MD5_CLAIM="内容 MD5 准入"` | `mutationMd5ClaimInjectedIsDetected` | `md5ClaimConsistent` |

新增文本一致性契约锁：`criterionNoCa47f83eIdentity`（源码无 `ca47f83e` + note 明确"非原图身份"）、`noteMd5ClaimConsistentWithSource`（源码无内容摘要计算 ⇒ note 必含 `MD5_CLAIM_RETRACTED`）、`idViaSourceValidationPresent`、`scanBoundedToImage2WithTimeBudget`。测试保持纯 JVM（只读源码文本 + note 文本，不实例化探针、零 Android/Xposed 依赖）；note 路径 `docs/_r86c_b5_fix_note.md` 由 `source()` 向上回溯至工作根解析。

`[DONE §4]`

---

## §5 三闸待跑（本 lane 未跑，主会话统一跑）

> 硬纪律：本 lane 禁跑 gradle / `_junit.sh` / adb，不自称已跑、不写"全绿"。以下全部**待主会话**执行。

| 闸 | 命令（cwd=`wxgroup/`） | 状态 |
|---|---|---|
| 闸 0（前置） | `python ../tools/gradle_run.py compileDebugUnitTestKotlin` | **待主会话**（闸 1/2 依赖预编译 class） |
| 闸 1 | `bash ../tools/_junit.sh com.lx.wxgroup.probe.B6CheckExistProbeTest` | **待主会话**（预期 30 tests） |
| 闸 2 | `bash ../tools/_junit.sh`（全量） | **待主会话** |
| 闸 3 | `python ../tools/gradle_run.py assembleDebug` | **待主会话** |

主会话三闸后须 fresh-eyes 再核一轮，确认 BLOCKER-1/2 消除；BLOCKER 消除前**不装机 / 不提交 / 不声称肉眼清晰**。

`[DONE §5]`

---

## §6 诚实边界（写后清晰【UNKNOWN】、C2 命中≠已清晰、肉眼不会变化）

- **撤回已清晰化**：原 note `wxgroup/docs/_r86c_b5_impl_note.md:38/40/47/54` 的「内容 MD5 准入 / 4 参签名 / 对齐 q4b 原图字节 / 全失败 fail-closed 常态」已撤回；本 note §1 写清实际准入 =「同根唯一 hevc 文件名（无内容校验）或 3B 临时占位」，几乎总下发。与 q4b 字节决定论的冲突【UNKNOWN，两链分叉仅推断·高】。
- **写后清晰仍【UNKNOWN，未闭合】**：本探针只读红线决定 C2 只反射读字段 + `return 0`，**不调 j6 / 不发消息 / 不写宿主字段 / 不改 m.a**，自建 checkExist 与真实转发消息零耦合 ⇒ **用户肉眼不会因本探针发生 A→B→A 变化**。C2 命中只证明「checkExist 秒传探测可被三字段启动」，**不等于已清晰**；出口 filemd5 翻源图身份 + 用户肉眼 A→B→A 属后续 B6 全量功能 + 用户终审，不属本实验。
- **判据降级的验收边界**【推断·高 → 现场升格 / UNKNOWN】：`exist` false→true + md5Len 0→>0 只作「三字段启动 checkExist」机制信号，**不裁决原图身份**（`cff4f5a5` / `ca47f83e` 同图性【UNKNOWN，q4b:84】）。若现场 off 态也 `exist=true`，判据失效 ⇒ 如实【UNKNOWN】，最小下一步 = 同会话 off→on→off 三次回包 + stock 同步对照，确认翻转与三字段同进同退。
- **(a) 前置未闭合，不写成已实现**【真机实证·高（转发帧 u/map 无本地路径属性，generalize :105-108）+ 推断·高（同根多 hevc）】：真实 `image2` 桶 `.jpg_hevc` 内容摘要 == 源图身份的字节文件是否可获得未证（generalize Q5 前置未做）；主路径即便限定 `image2/` 仍可能因多候选回退 3B 占位。本 lane 不选 (a) 为已实现路径。
- **性能边界**【推断·中，未真机测】：已加 `image2/` 子目录 + 250ms `elapsedRealtime` 预算 + 上限 1024 + 超时 fail-closed；半途扫描的"唯一命中"不可信、一律放弃主路径。是否彻底消除 worker 线程秒级全树扫描未在真机复测。
- **单测可信度**【源码实证·高】：30 例是**文本断言锁**，只防拼写级契约（防删字段 / 防写死常量 / 防删来源校验 / 防删性能边界），覆盖不了运行时机制有效性；**30 例全绿 ≠ B5 通过**，运行时结论以现场 C2 回包 + 用户肉眼终审为准。
- **安装 / 提交 / 开关**：本 lane 未碰 adb / 装机 / 设备开关 / gradle / git commit；现场 SCD/hevc 开关状态由主会话掌握，本探针不自行翻转。

真 `[DONE ALL]`

> 交付声明：产物 `docs/_r86c_b5_fix_note.md`（本文本）+ `B6CheckExistProbe.kt` + `B6CheckExistProbeTest.kt` 三处落盘。§1 两项 BLOCKER 逐条处置、§2 裁决 (b) 判据降级并正面回答区分张力、§3 改动清单、§4 M1~M17 变异判红表、§5 三闸待跑、§6 诚实边界全部落盘。两项 BLOCKER 的消除以主会话亲核 + fresh-eyes 再核为准；本 lane 不自称通过、不装机、不提交。所有【推断】未写成【已证】。

`[DONE FIX-COMMENTS]`

## R1/R2/R3 收口（fresh-eyes review2 三件套 residue，纯文档）
- R1：单测 KDoc :11 判据来源改 fix note §2 降级判据。[DONE R1]
- R2：§2.3 锚点/必要充分/b3 归因三处纠偏（【推断】不写成【已证】）。[DONE R2]
- R3：§3.1:97「强制校验」→「强制记录+WARN 不拦截」。[DONE R3]
- 三处均为纯文档/证据锚点，运行时源码/单测断言/判据常量/三闸结果未变。
[DONE ALL-RESIDUE]

