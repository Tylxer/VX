# R86c B5 修正 lane 独立 fresh-eyes Pro 复核（review2 · 证伪/找 BLOCKER）

> 角色：`r86c-b5-review2`，**独立 fresh-eyes Pro 复核**（不续用实现 lane `r86c-b5-fix`，避免自审盲区）。工作根 `C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`。
> 性质 = 判据设计复核 + Hook 链路/反射边界 + 单测可信度裁决 ⇒ Pro 档。模型 `HY4-FLASH-FREE`（用户会话临时规则，不写入文件）。
> 档位：【源码实证·高】/【真机实证·高】/【dex 实证·高】/【推断·高|中|低】/【UNKNOWN】；每条带文件:行号锚点；【推断】不写成【已证】。
> 硬纪律：**只读复核**，唯一允许写 = 本文档；禁改源码/单测/任何其它文档，禁跑 gradle/`_junit.sh`，禁碰 adb/装机/设备开关，禁 git commit/push。
> 职责 = 证伪/找 BLOCKER，**不是盖通过章**。发现零生效路径/判据污染升级 BLOCKER；宁可 NO-GO 也不为 GO 放宽判据。
> 落盘策略（防打断）：骨架 → 每问写盘留 `[DONE Qn]` → 三件套 → 真 `[DONE ALL]`。**先写文件再回报**。

状态进度：
- [x] Q1 原 BLOCKER-1（note 虚假 MD5 准入 + 目标判据零有效达成路径）是否真消除
- [x] Q2 原 BLOCKER-2（fileId mid 判据污染 + `ca47f83e` 身份误判）是否真消除
- [x] Q3 注释一致性 + 单测可信度 + 三闸
- [x] Q4 装机 go/no-go + 现场口径
- [x] 三件套（结论 / go-no-go / 最小下一步）
- [x] 真 `[DONE ALL]`

---

## Q1 —— 原 BLOCKER-1（note 虚假 MD5 准入 + 目标判据零有效达成路径）是否真消除

### Q1.1 虚假陈述撤回：源码层属实，撤回为「新 note 覆盖旧 note」的声明式处置

**结论：源码层撤回属实（【源码实证·高】）；原 impl note 原文未改、无撤回标记，但按项目多轮 verdict 覆盖惯例可接受，须同步 test KDoc（见 Q3 R1）。**

- 修正 note §1:25-35 逐条承认并撤回「4 参签名 / 内容 MD5 准入 / 对齐 q4b 原图字节」；源码逐行实证：`B6CheckExistProbe.kt:369` `resolveFullpath(u: String, map: Map<*, *>?)` **仅 2 参**；全文 `MessageDigest`/`.digest(`/`readBytes` **零命中**（grep 实证）；`:389` filter 仅 `it.isFile && name.endsWith(".jpg_hevc") && length() > 0L`，`:393` `!timedOut && hits.size == 1`——纯文件名/非空/唯一性，零字节内容校验；`:399` 回退仅写 `byteArrayOf(0x42,0x36,0x58)` 3B 占位。⇒ note §1 的撤回与落码一致【源码实证·高】。
- 但 `wxgroup/docs/_r86c_b5_impl_note.md` **原文未改**（mtime 20:16:13，早于 fix note 20:35:44；全文无 `撤回`/`RETRACTED`/`作废` 标记），`:38` 仍写 `resolveFullpath(u, map, fileId, xmlMd5)` 4 参、`:40` 仍写「内容 MD5 前 8 == 源图身份前缀」、`:43` 仍标【真机实证·高 + dex 实证·高】。fix note §6:161 声称「已撤回」= 声明式覆盖，非原文修订。⇒ 按本项目惯例（如 `q4_fullpath_verdict.md:67` 的「fileId 决定量」被 `q4b:98` 覆盖但 q4 原文未改）可接受；但 `B6CheckExistProbeTest.kt:11` 仍把 impl note §1/§2 当判据来源，构成残留（Q3 R1）【源码实证·高】。

### Q1.2 新判据 (b)：原「零有效达成路径」是否被真消除

**结论：原零路径（3B 占位内容摘要物理不可能 == 原图身份）被结构性消除；新判据是诚实、可证伪、静态不可判的机制验证，不是伪造通过章（【源码实证·高 + 推断·中】）。**

- 原零路径成因证据链：q4b `_r86c_q4b_final_verdict.md:98` 坐实 fullpath **字节内容 MD5 == 源图身份**为决定量 ⇒ 3B `"B6X"` 占位的内容摘要物理上不可能等于 `cff4f5a5`，旧判据「C2 第二次回包 exist=true + 原图身份 md5」零达成【源码实证·高 + 推断·高】。
- 修正 note §2.2:53-55 把判据降为「同一会话 off/on 对照下 C2 第二次真实回包 `exist` false→true 且 `md5Len` 0→>0（`logC2Shape` 排除空壳）」，md5 前缀**只记录不裁决身份**，不声称命中原图；源码 `:294/:302` 与该措辞一致（`md5Pre`/`md5Len` 只打日志，无身份比较）。⇒ 零路径随「身份要求」一起消失【源码实证·高】。
- 新判据**静态不可判**：需要真机 off→on→off 三次回包 + stock 同步对照才能裁决。note §2.3:66 与 §6:163 均如实写明「【推断·中 → 现场升格 / UNKNOWN】；若 off 态也 exist=true 则判据失效、不得写通过」。⇒ 未把不可判写成已证【源码实证·高】。

### Q1.3 §2.3「区分张力」正面回答是否站得住：**结论属实，但支撑证据有缺陷（【推断·中】，列 R2 必改，不升级 BLOCKER）**

1. **「有回包」不区分有效/无效——张力属实，但 fix note 锚点误引**【真机实证·高 + 源码实证·高】：b6step 真实第二次回包 `exist=false md5Len=0` 的原始出处是 `docs/_r86b_b6step_state_final_raw.txt:213`、`docs/_r86b_b6step_impl_result.md:91/98`、`docs/_r86b_b6step_verdict.md:25/89`，不是 fix note §2.3:59 引用的 `_r86c_b5_review.md:29`（该行讲的是主路径不可达）或 `direction_verdict.md:163`（该行讲的是 B2 排「读错对象/空壳」）。**事实成立、锚点须改**。
2. **Q4 B-1 先例只证必要性、不证充分性**【真机实证·高 + 推断·高】：`_r86c_q4_fullpath_verdict.md:66` `fullpathLen=0 → hit=0 exist=false`（q4b:98 定 fullpath 为唯一字节入口）⇒ 非空 fullpath 是 hitCheck 启动**必要**条件；但 `docs/_r86c_fileid_vs_fullpath_verdict.md:77` 明写「哪类字节足以命中 cff4f5a5【UNKNOWN 未闭合】」。fix note §2.3:62-64 由「必要」推到「补齐三字段 ⇒ exist 必翻」属【推断·中】归纳，**非 dex/真机已证**。
3. **b3 stock 先例存在归因错误**【真机实证·高事实 / 推断·高错误归因】：`wxgroup/_r86c_b3_stock_raw.txt:152` 实测 `md5=cff4f5a5 md5Len=32 hit=1 exist=true fidLen=194 fidPre=305f0201` 属实；但 fix note §2.3:63-64 据此写「服务端对 mid 锚点 `305f0201` 仍秒传命中」，把 exist=true 归因到 fileId——与 `q4b:98`（fileId 非充分、仅伴生）、`_r86c_fileid_vs_fullpath_verdict.md:57`（输出 fileId 逐字相同仍一清晰一模糊、零区分力）**正面冲突**。b3 的 exist=true 伴随真实 hevc 路径（路径串 124，`.workbuddy/memory/2026-11-24.md:17`），不是探针的 3B 临时占位。⇒ 该先例只能证「本会话服务端可返回 exist=true、排除服务端过期」，**不能**证「补三字段必翻」。
4. **综合裁决**：(b) **不是**「把判据挪到同样不可达/无区分力的位置」——它给出可证伪的现场 A/B、明确的 UNKNOWN 逃生口，且 note §6:163 诚实承认机制层归纳非 dex 直接证明；但它依赖的区分力支撑被夸大（必要性当充分性、b3 归因 fileId、锚点误引），须在 R2 修正后并入现场口径。判据本身未因此失真 ⇒ **不升级 BLOCKER**。

### Q1.4 其余源码边界（非 BLOCKER）

- `resolveFullpath` 性能边界已落实：`File(root,"image2")`（`:383`）+ `elapsedRealtime` 250ms 预算（`:58/:381/:385`）+ `!timedOut`（`:393`）；半途扫描放弃主路径【源码实证·高】。真机是否彻底消除 worker 秒级全树扫描未重测【推断·中】。
- fail-closed 几乎不触发：`File.createTempFile`（`:398`）在临时目录几乎必成功 ⇒ `:257-260` `fullpath==null` 分支几乎不进；fix note §1:31 已如实标为「字面兜底、非常态」【源码实证·高】。

`[DONE Q1]`

---

## Q2 —— 原 BLOCKER-2（fileId mid 判据污染 + `ca47f83e` 身份误判）是否真消除

### Q2.1 `ca47f83e` 身份误判：源码层真消除（【源码实证·高】）

- 主会话已 grep、本复核复核一致：`B6CheckExistProbe.kt` 全文 `ca47f83e` 出现数 **=0**（含 KDoc，单测 `ca47f83eExcludedValid:546` 正是 `"ca47f83e" !in src`，判红锁 M16 佐证）。
- C2 handler（`:285-310`）只 `getObjectField` 读 `field_exist_whencheck`/`field_filemd5` 并打 `md5Pre`/`md5Len` 日志（`:295-302`），**无任何**与 `cff4f5a5`/`ca47f83e`/`0ef205c1` 的字面比较或身份裁决；`md5Pre` 只作记录。⇒ 原「把 ca47f83e 当原图身份通过」的代码路径不存在【源码实证·高】。q4b:84 的同图性【UNKNOWN】被如实保留、不回答。

### Q2.2 fileId mid 判据污染：两条处置路径都给结论，**站得住的是「判据降级致污染自然消失」**（【源码实证·高 + 推断·高】）

- **记录级处置本身不足以单独消除污染**：`:239-243` `idVia != "map" && idVia != "regex:big"` 时仅打 WARN「非原图锚点（不当 big 用）」，**不是 fail-closed**——mid/thumb 取值仍被写入 task（`:265 put(field_fileId, fileId)`）并下发（`:316 dispatch`）。`:257-260` fail-closed 日志补 `idVia` 也只便于回放、不拦下发。⇒ 若判据仍是「C2 md5 非 0ef205 即原图命中」，mid 命中 `ca47f83e` 仍会被误判为通过——**记录级 WARN 挡不住**。
- **但判据已降级为 (b)**：新判据完全不看 md5 身份（Q1.2），`md5Pre` 只记录不裁决，`ca47f83e` 源码零出现。⇒ 原 BLOCKER-2 的「mid 命中被当原图通过」**结构性不可能发生**，污染随判据降级自然消失。这是本复核采信的站得住的路径；`idVia` WARN 是辅助审计记录，非污染消除的成因。档位【源码实证·高 + 推断·高】。
- note §2.4:70-73「不许写死常量 / 不裁决身份」与 M8/M16 锁一致：M8 锁 `REF_MD5_PRE="cff4f5a5"` 判红（`mutationHardcodedFingerprintIsDetected`）、M16 锁注入 `ca47f83e` 判红（`mutationCa47f83eInjectedIsDetected`）、`criterionNoCa47f83eIdentity` 锁 note 必含「非原图身份」、M17 锁「内容 MD5 准入」声称判红⇒ 与 §2.4 一致【源码实证·高】。措辞微瑕：note §3.1:97 称「**强制校验** fileId 来源」，实际是「强制记录 + WARN 标注」（`:241-243` 无拦截），§2.5:83 的「强制记录」更准确；列 R3 必改，非 BLOCKER。

`[DONE Q2]`

---

## Q3 —— 注释一致性 + 单测可信度 + 三闸

### Q3.1 两处历史注释续修真改净（【源码实证·高】），但发现 1 处新残留（R1）

- `B6CheckExistProbe.kt:153` 现为「构造含 B5 三字段（含 field_fullpath）的 checkExist task」——旧句「构造不带 fullpath」grep 零残留；`:395-396` 现为「身份判定不属本探针（md5 前缀只记录不裁决，旧稿的 fileId 锚点身份假设已撤回）」——旧句「身份靠 fileId 锚点」grep 零残留；fix note §3.1:101 已同步补记 + `[DONE FIX-COMMENTS]`（note:173）⇒ 主会话亲核的两处属实改净【源码实证·高】。
- **新残留 R1**（主会话未抓到）：`B6CheckExistProbeTest.kt:11` 仍写「判据来自 `wxgroup/docs/_r86c_b5_impl_note.md` §1/§2：**B5 验证「补齐三字段后自建 checkExist 能否命中原图」**」——这是已撤回的旧判据（fix note §2.2 已降为「三字段能否启动 checkExist、不声称命中原图」），且把已声明撤回的 impl note 当作判据来源。属「note 撤回但测试注释仍肯定」残留，**不改运行时与断言**，列必改、非 BLOCKER【源码实证·高】。

### Q3.2 单测可信度：30 例 = 13 契约 + 17 变异属实，真变异、非等价、纯 JVM；但只覆盖拼写级契约（【源码实证·高】）

- 计数实证：`grep -c "@Test"` = **30**、`grep -c "fun mutation"` = **17**，与 fix note §4「30 例 = 13 契约锁 + 17 变异判红」一致。13 契约 = `probeShapeMatchesContract`/`taskFieldsCompleteWithB5Fields`/`b5ThreeFieldsWritten`/`fullpathValueDynamicNoHardcode`/`logPrefixAndC2FieldNamesLocked`/`listenerMethodsLocked`/`c2CallbackReadOnly`/`noNativeHook`/`wiringPresent`/`idViaSourceValidationPresent`/`scanBoundedToImage2WithTimeBudget`/`criterionNoCa47f83eIdentity`/`noteMd5ClaimConsistentWithSource`（`:78-269`）。
- **真变异、非等价**：M1~M17 每个 `source(PROBE).replace(needle, …)` 均有 `assertTrue(mutated != source(PROBE))` 前置真改断言 + `assertFalse(validator(mutated))` 判红（`:274-433`）。本复核逐字核 needle 均在当前源码存在：M8 `MAX_SCAN_FILES = 1024`（`:55`）、M16/M17 `SCAN_TIME_BUDGET_MS = 250L`（`:58`）、M12 `非原图锚点（不当 big 用）`（`:242`）、M13 `no_fullpath (fail-closed) idVia=$idVia`（`:259`）、M14 `File(root, "image2")`（`:383`）、M15 `SystemClock.elapsedRealtime()`（`:381/:385`）、M5 `logC2Shape(f)`（`:294`）、M9 `"z" -> null`（`:306`）、M10 `"field_exist_whencheck"`（`:52`）等——非同义改写、判红对象正确【源码实证·高】。
- **纯 JVM**：仅依赖 `org.junit.Assert`/`org.junit.Test`/`java.io.File`（`:3-6`），`source()` 读源码文本、`codeLines()` 去注释（`:438-455`），不实例化探针、零 Android/Xposed 类路径依赖 ⇒ 纯 JVM 成立【源码实证·高】。
- **可信度边界**：这是**文本断言锁**，只验「源码字面是否含某串」——覆盖防删字段/防写死常量/防删来源校验/防删性能边界/防读错字段，**覆盖不了运行时机制有效性**（Q1.3 的必要≠充分、3B 占位能否启动 checkExist 均无法由单测裁决）。fix note §6:166 已自认「30 例全绿 ≠ B5 通过」⇒ **不得把 30 例全绿当 B5 通过章**【源码实证·高】。

### Q3.3 三闸：主会话两轮全绿属实记录；注释变更不改逻辑、但需重验——已重验，不作 BLOCKER（【推断·中】，按主会话实测数采信）

- 台账 `:5904`（首轮）compile EXIT=0/18s、定向 **OK (30 tests)**、全量 **OK (1212 tests)**、assembleDebug EXIT=0/5s；`:5908`（续修注释后重跑）compile EXIT=0/17s、定向 **OK (30 tests)**、全量 **OK (1212 tests)**、assembleDebug EXIT=0/4s。本复核**只读、不重跑**，按「注释变更不改逻辑、但需重验」原则裁决：续修仅 `:153`/`:395-396` 注释字面、单测未动（mtime 单测 20:31 早于源码 20:35），主会话已重跑全绿 ⇒ **不把「待重跑」当 BLOCKER**【推断·中，以主会话实测为准】。

`[DONE Q3]`

---

## Q4 —— 装机 go/no-go + 现场口径

### Q4.1 裁决 = **GO**（装机 go；BLOCKER 清单为空）

**一句话：原 BLOCKER-1/2 均真消除，无残留零生效路径、无残留判据污染；(b) 是诚实可证伪、静态不可判的机制判据；三闸两轮全绿。⇒ GO。装机现场口径见 Q4.2；R1~R3 文档/锚点必改项不升级 BLOCKER、不阻断本轮装机裁决（详见三件套 ③）。**

- 不升级 BLOCKER 的判据逐条：零有效达成路径——原「原图身份 MD5」路径随身份要求删除而结构性消除，新判据有 falsifiable 现场 A/B（Q1.2）；判据污染——`ca47f83e` 源码=0 + 判据不看 md5 身份，mid 命中误判为通过结构性不可能（Q2.1/Q2.2）；注释残留——两处历史注释已改净，新发现 R1 为纯文档字面（Q3.1）；单测——30 例真变异非等价纯 JVM，且 lane 自认不覆盖机制有效性（Q3.2）；三闸——两轮全绿（Q3.3）。
- 岗位纪律注记：本复核是**证伪/找 BLOCKER**，但「不存在 BLOCKER」时如实给 GO，不为 GO 放宽判据、也不为显严格而虚构 BLOCKER。R1~R3 是诚实边界内的文档必改项，须并入现场口径下发前修正，但不属于「零路径/判据污染」两类 BLOCKER。

### Q4.2 装机后唯一判据口径（GO 路径）

1. **开关与对照**：双探针 SCD + B6X 全开 → `b6step on`；**同会话 off→on→off 三次回包 + stock 同步对照**，确认 `exist` 翻转与三字段同进同退；若 off 态也 `exist=true` ⇒ 判据失效、如实判【UNKNOWN】、不得写「通过」。
2. **唯一判据**：Proxy C2 **第二次真实回包**（第一次 `exist=NA` 空壳不参与判读）`field_exist_whencheck` 由 b6step 基线 `false` 翻到 `true` **且** `field_filemd5` 长度 `0`→`>0`；`logC2Shape` 须显示 `cls`=Ltj/f、String 字段非全空，排除读错对象/空壳。
3. **只记录不裁决**：`md5Pre`（含 `cff4f5a5`/`ca47f83e`）、`hit`、`UploadHitCacheType`、`idVia` 一律**只记录、不裁决身份**。
4. **现场必须统一声明**（非代码 BLOCKER，但必须写进现场口径）：C2 变化只证「三字段能启动 checkExist 秒传探测」，**≠清晰**；本探针不发消息 / 不调 j6 / 不写宿主字段，**肉眼不会有任何变化**；清晰须后续 B6 全量功能 + 用户肉眼 A→B→A 终审。

`[DONE Q4]`

---

## 三件套（交付）

### ① Q1~Q4 一句话结论 + 档位

- **Q1**：虚假陈述源码层真撤回（【源码实证·高】），原「原图身份 MD5 零达成路径」随身份要求删除而结构性消除、新判据 (b) 是诚实可证伪但静态不可判的机制验证（【源码实证·高 + 推断·中】）；§2.3 区分张力结论属实但支撑证据有锚点误引 / 必要当充分 / b3 归因 fileId 三处缺陷（【推断·中】，列 R2，非 BLOCKER）。**BLOCKER-1 真消除。**
- **Q2**：`ca47f83e` 源码=0、C2 不裁决身份（【源码实证·高】）；mid 污染随判据降级结构性消失（【源码实证·高 + 推断·高】），`idVia` WARN 只是辅助记录、不足以单独消除污染；note §3.1「强制校验」措辞偏大（R3，非 BLOCKER）。**BLOCKER-2 真消除。**
- **Q3**：两处历史注释改净（【源码实证·高】），新残留 R1=`B6CheckExistProbeTest.kt:11` 仍写旧判据「能否命中原图」（【源码实证·高】，非 BLOCKER）；30 例 =13+17 真变异非等价纯 JVM、但只覆盖拼写级契约（【源码实证·高】），30 绿≠B5 通过；三闸两轮全绿，注释续修后已重验（【推断·中】，以主会话实测为准）。
- **Q4**：**GO**（【推断·中 → 现场升格 / UNKNOWN 取决于 off/on A/B 回包】）；BLOCKER 清单为空；唯一判据 = C2 第二次真实回包 `exist` false→true 且 `md5Len` 0→>0 + `logC2Shape` 非 Ltj/f 空壳，md5 前缀/`hit`/`UploadHitCacheType` 只记录不裁决；现场口径 = C2 变化只证「三字段能启动 checkExist」≠清晰、探针不发消息肉眼不变、清晰须后续 B6 全量功能 + 用户肉眼 A→B→A 终审。

### ② go/no-go + BLOCKER 清单

**GO。BLOCKER 清单 = 空（原 BLOCKER-1/2 均真消除，无新增零路径/判据污染）。**
- 非 BLOCKER 必改项：R1（test KDoc :11 旧判据残留）、R2（fix note §2.3:59 锚点误引 + :62-64 必要当充分 + b3 归因 fileId）、R3（note §3.1:97「强制校验」→「强制记录+WARN」措辞）。三项均不改运行时/判据字段/三闸结果，列 ③ 在装机现场口径下发前并入。

### ③ 最小下一步（GO 路径：现场判据口径 + 文档 residue 收口）

1. **责任方 team-lead（装机现场，判据口径）**：按 Q4.2 执行——双探针 SCD+B6X 全开 + `b6step on`，同会话 off→on→off 三次回包 + stock 同步对照；判据 = 第二次真实回包 `exist` false→true 且 `md5Len` 0→>0 + `logC2Shape` 非 Ltj/f 空壳；md5 前缀/`hit`/`UploadHitCacheType`/`idVia` 只记录不裁决；off 态也 exist=true ⇒ 判【UNKNOWN】不写通过。现场统一声明 Q4.2-4。
2. **责任方 `r86c-b5-fix`（装机前文档收口，纯注释/锚点、不改码不改锁）**：
   - R1：`B6CheckExistProbeTest.kt:11` 的 KDoc 判据来源改为 fix note §2 的降级判据，删「能否命中原图」旧句、不再把 impl note §1/§2 当现行判据；
   - R2：fix note §2.3:59 的 b6step 回包锚点改为 `_r86b_b6step_state_final_raw.txt:213` / `_r86b_b6step_impl_result.md:91/98`；§2.3:62-64 把「必要⇒充分」改为「非空必要、充分性【UNKNOWN】」，把 b3 :152 的 exist=true 归因从「mid 锚点」改为「伴随真实 hevc 路径、仅排除服务端过期，不证补字段必翻」；
   - R3：fix note §3.1:97「强制校验 fileId 来源」→「强制记录 idVia + mid/thumb WARN 标注（不拦截下发）」。
   - 注：R1/R2/R3 为纯文档改动，改后由主会话按需重跑定向闸确认文本锁仍绿；不新增变异、不扩锁。
3. **装机后若判据翻转成立**：仅记「checkExist 秒传探测可被三字段启动」机制信号，**不得**越级写「已清晰/问题已解决」；清晰结论留待后续 B6 全量功能 + 用户肉眼 A→B→A 终审（`docs/_r86c_clearness_reverdict.md` 口径）。

真 `[DONE ALL]`

> 交付声明：产物 `docs/_r86c_b5_review2.md`（本文本），逐问 Q1~Q4 与三件套全部落盘、真 `[DONE ALL]`。全程只读：唯一写文件 = 本文档；未改源码/单测/任何其它文档，未跑 gradle/`_junit.sh`，未碰 adb/装机/设备开关，未 git commit/push。所有【推断】未写成【已证】，每条带文件:行号锚点。职责 = 证伪/找 BLOCKER：核出两项原 BLOCKER 真消除、给 GO，同时如实列 R1~R3 文档 residue 与 R2 证据缺陷，未为 GO 放宽判据、未把 30 例全绿当 B5 通过章。

