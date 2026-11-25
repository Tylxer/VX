# R86c B5 独立 Pro 复核（fresh eyes · 证伪/找 BLOCKER）

> 角色：`r86c-b5-review`，独立 Pro 复核（不续用实现 lane）。工作根 `C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`。
> 性质 = 判据设计 + Hook 链路 + 反射 + 编译期/运行期边界裁决。模型 HY4-FLASH-FREE（用户临时规则，不写文件）。
> 纪律（硬）：**只读复核**，唯一允许写 = 本文档；禁改源码 / 禁跑 gradle/单测 / 禁碰 adb/装机/设备开关 / 禁 git commit。
> 档位：【真机实证·高|中】/【源码实证·高】/【dex 实证·高】/【推断·高|中|低】/【UNKNOWN】，【推断】不写成【已证】，每条带来源行号/锚点。
> 判据纪律：只对 C2 `field_exist_whencheck` / `field_filemd5` 身份前缀裁决，`hit`/`UploadHitCacheType` 只记录不裁决。
> 落盘：骨架 → 每问写盘留 `[DONE Qn]` → 三件套 → 真 `[DONE ALL]`。先写文件再回报。

状态进度：
- [x] Q1 fullpath 填值自洽性 & 零生效路径（重中之重）
- [x] Q2 判据纠偏 & 可达成性
- [x] Q3 改动范围 / 单测可信度 / 变异
- [x] 三件套（结论 / go-no-go / 最小下一步）
- [x] 真 `[DONE ALL]`

---

## Q1 —— fullpath 填值裁决是否自洽 & 是否埋零生效路径（重中之重）

先给总判：**实现 lane 选的「第三种 = 动态定位 + Y 安全回退」在 note 文本层自洽，但落码层名不副实，且埋了一个高概率的零生效/错生效路径。核心矛盾：note §1 声称主路径按「内容 MD5 准入」对齐本轮事实（q4b 字节决定论），但代码根本不算 MD5；实测路径几乎必走 3 字节临时占位回退，而该回退的机制前提（占位+fileId 锚点）正是 q4b 已推翻、q4 fullpath verdict 又被主会话附录撤回「124B=占位体积」论证的旧假设。**下面逐条给档位。

### Q1.1 主路径命中率 + 「对齐本轮事实」是否名不副实

**结论：主路径在真机 forward 帧几乎不可达（【推断·高】，前提为【真机实证·高】）；「主路径对齐本轮事实」名不副实（【源码实证·高】）。**

核验链：

1. **主路径依赖 u/map 含 `/MicroMsg/<32hex>/` 绝对路径**：`B6CheckExistProbe.kt:55` `RE_DATA_ROOT = Regex("(.*/MicroMsg/[0-9a-fA-F]{32}/)")`，`:359-363` 只在 `map?.values` 与 `u` 里 find。而 generalize `docs/_r86c_generalize_verdict.md:105`（Q2-b #1）证同数据根**多图共享**：实验2 清晰 hevc 在 `image2/23/72`（:368）、模糊 hevc 在 `image2/90/c8`（:288/470），根 `89476c7f…` 相同；Q2-b #2a/:106、#2c/:108 证转发帧源 md5/g9c 恒空或不等价。note §6:151 自己也承认「forward 帧 u/map 无本地路径属性」。⇒ `RE_DATA_ROOT.find(u/map)` 大概率 null；即便侥幸取到根，`hits.size==1` 因同根多 hevc 也大概率不成立（generalize :109/#1 同一根 ≥2 张 hevc）。主路径 `:370 if (hits.size == 1)` 两道门同时难过 ⇒ **大概率回退**。档位：u/map 无本地路径 =【真机实证·高，generalize :105-108】；B5 主路径实际不可达 =【推断·高】（本任务禁真机，未直接观测 B5 帧）。

2. **「主路径内容 MD5 准入」是 note 的单方面宣称，代码未实现（【源码实证·高】）**：
   - note §1.2 `wxgroup/docs/_r86c_b5_impl_note.md:40`：「动态定位一个本地文件，其**内容 MD5 前 8 == 源图身份前缀（非 0ef205c1）**。命中即取其绝对路径」；§1.3:47 称「主路径『内容 MD5 准入』=【真机实证·高 + dex 实证·高】」；§1.2:38 甚至写方法签名 `resolveFullpath(u, map, fileId, xmlMd5)`（4 参）。
   - 实测源码：`:356 private fun resolveFullpath(u: String, map: Map<*, *>?): String?`（**仅 2 参**，无 fileId/xmlMd5）；全文 grep **无任何 `MessageDigest`/`digest`/`readBytes`/MD5 计算**（唯一 MD5 相关 :78/:236 是取 xml `$md5` 字符串填 task，与文件内容无关）；主路径 filter `:367` 仅 `name.endsWith(".jpg_hevc") && length() > 0` + `hits.size==1`——**纯按文件名/非空/唯一性准入，零字节内容校验**。
   - ⇒ note §1.2/§1.3 的「内容 MD5 准入」「对齐本轮事实（q4b 原图字节）」「4 参签名」全部**名不副实**：实现的是「同根唯一 hevc 文件名」，不是 q4b 要求的「内容 MD5 == cff4f5a5」。测试 `fullpathValueDynamicNoHardcode`（test:104-112）只锁 `RE_DATA_ROOT`/`hits.size==1`/`createTempFile`/`fail-closed`，**不校验内容 MD5**，所以单测全绿也盖不住这个落差。档位【源码实证·高】。

3. **与 q4b 裁决的关系**：q4b `docs/_r86c_q4b_final_verdict.md:42/98/120/164` 已把「决定量 = fullpath 字节内容 MD5 == 源图身份」坐实为真机实证·高，并明确「36B 占位本轮未复现 / 不照抄占位 / 原图 45522B 对齐」。note §1 声称「第三种」同时满足 B（对齐原图字节）+ C（不写死），但**落码并未真正实现 B**（见上）。若主路径真按文件名唯一命中一张 hevc，也**不保证**其内容 MD5 是源图身份——可能命中同根近期别图的 hevc（generalize :105 多候选场景被 `hits.size==1` 挡掉后回退，但若只剩一张别图 hevc 则会误选）。档位【推断·高】。

### Q1.2 回退占位的有效性（核心 BLOCKER 候选）

**结论：回退造 `{系统临时目录}/b6x_XXXX.jpg_hevc` 3 字节、随机名、无 magic、全微信进程全新路径，与 Q4/R79 里能触发秒传的「微信自己 ImgInfo2 记录里的路径」是不同物种；三处差异（路径位置 / 文件名语义 / 内容身份）均无法从现有 dex/文档排除风险，标【UNKNOWN】；但「3B 内容 MD5 ≠ 原图身份」是【源码实证·高】。综合评估：构成 BLOCKER。**

逐条：

1. **占位路径来源不同（【真机实证·高】差异事实 + 【UNKNOWN】后果）**：Q4/R79 真机里触发秒传的占位是微信 ImgInfo2 记录里的路径——`docs/_r79_stock_byte_source.md:10/35/44/46`：`r0.e=image2/88/2d/882d…jpg` = 36B，由 `b1.C4/j6` 在 MicroMsg 数据根下新建记录生成；Q4 `docs/_r86c_q4_fullpath_verdict.md:24-25/:140` 的 36B `.jpg_`/124B `.jpg_hevc` 占位也都在数据根下、是 stock 同款命名。而本实现回退 `B6CheckExistProbe.kt:374 File.createTempFile("b6x_", ".jpg_hevc")` 落在系统临时目录、`b6x_` 随机名、非微信命名。⇒ 这是**新造的、微信从未见过的路径**。后果（是否必须位于 MicroMsg 数据根下 / magic 校验 / setFilePath 对临时目录权限）：
   - dex 侧：`ok0/x0.u` 仅证 `field_fullpath → setFilePath` 是唯一字节入口（q4b :109 自算核准 `0x3a98ee/0x3a98f2`），**未证** setFilePath 要求路径在数据根下或有 magic 校验；
   - 文档侧：Q4 fullpath verdict:150「fullpath 只要求非空字节入口，stock 36B `.jpg_`（非合法 jpg 后缀）都能清晰，不要套 magic/64KB」，倾向**不需要 magic**；但该结论依附的「A 段 124B=占位体积」被主会话附录 :185 撤回（124 是路径串长非字节），支柱已松。
   - ⇒ 三处后果均【UNKNOWN 未闭合】，无法从 dex/文档排除。

2. **3 字节 vs 36B/124B 体积（【推断·中】影响有限，但内容身份硬失败【源码实证·高】）**：
   - 体积门槛：B-1 先例 `fullpathLen=0 → hit=0`（q4 :66）只证「非空必要」，Q4:150 称「几十~百字节即可」。3B 非空，体积本身是否不够【UNKNOWN】；按现有文档倾向「大小不是决定量」，影响【推断·中】。
   - **内容身份硬失败**：回退字节 `byteArrayOf(0x42,0x36,0x58)`（`:375`，即 "B6X"）的内容 MD5 **物理上不可能等于** `cff4f5a5`/`ca47f83e`。按 q4b 坐实的字节决定论（回包 md5 = 文件字节内容 MD5，:55-58），**这 3B 喂进去得到的一定是垃圾 md5，checkExist 不可能命中原图身份**【源码实证·高 + 推断·高】。这与 note §1.2:41「回退身份完全由 fileId 305f 锚点决定、占位只是启动令牌」直接冲突——该论断来自 q4/q79 的「fileId 决定量」旧裁决，已被 q4b「fileId 零区分力」真机反证（q4b :99/:125；本实现 note §1.1 表格 B 行自己承认 fileId 逐字相同仍一清晰一模糊）。

3. **fileId 取错值仍下发（【源码实证·高】行为 + 【推断·高】判据污染）**：`:218-229` fileId 取 map.big → xml 正则 big→mid→thumb；方向 verdict `docs/_r86c_direction_verdict.md:164`（B4）已证「坏图 attrs 无 cdnbigimgurl，只有 cdnmidimgurl(305f)」⇒ 本轮几乎必取 **mid(305f)** 而非 big。note §1.2 声称 fileId 是「原图锚点」，但实际是 mid。若 C2 回包 `field_filemd5` 前缀是 mid 对应值（如 `ca47f83e`），note §2:74 判据「非 0ef205 的原图身份前缀（cff4f5a5/ca47f83e）」会把 `ca47f83e` 当「原图身份命中」——而 q4b:84 明示 `cff4f5a5` 与 `ca47f83e` 是否同图【UNKNOWN】。⇒ 判据可能把「mid 命中」误判为 B5 通过，污染结论。档位：取 mid 行为【真机实证·高，direction :164 + 源码 :218-229】；污染风险【推断·高】。

4. **BLOCKER 升级判定**：按职责「发现零生效路径升级 BLOCKER，不降级」——主路径不可达（Q1.1）+ 回退 3B 临时占位按 q4b 字节决定论无法产出原图身份（Q1.2.2）+ fileId 取 mid 判据污染（Q1.2.3）三者叠加，**B5探针在当前落码下几乎不可能达成 note §2 锁定的「C2 第二次回调 exist=true 且非 0ef205 原图身份」判据**。虽然「C2 回调本身是否发生」非严格零（网络请求会发，C2 会回），但**目标判据的有效达成路径 = 零**。→ **BLOCKER-1（Q1 主路径名不副实 + 回退占位机制失效）成立，NO-GO。**

### Q1.3 fail-closed 自洽性

**结论：字面自洽（【源码实证·高】），但 note 承诺与实质有张力，且挡不住 fileId 错值下发（【推断·高】）。**

- `:373-377` 回退 `File.createTempFile` 在临时目录几乎不会失败 ⇒ 实际几乎总会拿到一个占位路径、`:245-249` 的 `fullpath==null` 分支几乎不触发 ⇒ **几乎总下发**。note §1.4:54「全失败 fail-closed 不下发」字面没错（仅主+回退都异常才不下发），但 §1.4:54「绝不自造身份常量硬凑」与实现冲突：回退本质就是自造 3B 占位。⇒ 承诺名实分离【源码实证·高】。
- 更关键：fail-closed 只管 `fullpath` 空/异常，**不管 fileId 取的是 big 还是 mid**（Q1.2.3）。fileId 错值不影响 fullpath 非空 ⇒ 仍下发 ⇒ 污染判据。note §1.4 未把「fileId 必须 big」列为 fail-closed 条件【源码实证·高：代码无此校验】。

### Q1.4 walkTopDown 性能

**结论：比上一轮全树 MD5 轻，但仍是 worker 线程同步扫描，最坏秒级，需标注而非 BLOCKER（【推断·中】）。**

- `:366 File(root).walkTopDown().maxDepth(6).filter{ name endsWith .jpg_hevc && length()>0 }.take(1025).toList()`：只做文件名过滤 + `File.length()`（stat），**不算 MD5/不读内容**，比上一轮 `findTargetHevc` 全树 MD5 轻一个量级【源码实证·高：源码可见只 stat】。
- 但范围是整个 `/MicroMsg/<32hex>/` 数据根（不限 image2 子目录）、maxDepth 6，账号数据根下文件可达数千；`take(1025)` 只限制结果集不限制遍历量，且 **filter 在 take 之前** ⇒ 仍要遍历整棵树直到收集到 1025 个 hevc 或遍历完。单文件 stat ~0.1–1ms ⇒ 数千文件 ≈ 0.1–数秒【推断·中，未真机测】。
- 线程：`armFromForward` 由 `FileSender.kt:167` 在 `send()` 内调用，send 走 `[D]#worker`/`wxg-forward` 类工作线程（日志前缀佐证），**非主线程** ⇒ ANR 风险低，但会阻塞该转发流程【推断·中】。建议限制扫描到 `image2/` 子目录或加时间预算。

`[DONE Q1]`

---

## Q2 —— 判据纠偏 & 可达成性

### Q2.1 Proxy C2 确实只读、纠偏成立（【源码实证·高】）

- 核 `B6CheckExistProbe.kt:273-298` handler：C2 分支 `:282 logC2Shape(f)` + `:283-290` 反射读 `field_exist_whencheck`/`field_filemd5` + `:291 return 0`；I/z 分支 `:293-294` 返回 null、不打日志；`:274` 首行 `if (!enabled)` guard。handler 区内**无** `setObjectField`/`setIntField`/`setBooleanField`/`callMethod`/`callStaticMethod`/`j6`/`send`/`FileSender`/`m.a`（与 test:176-185 锁一致）。⇒ 自建 checkExist task 与真实转发消息无耦合，「肉眼 A→B→A 翻转不属 B5」纠偏成立【源码实证·高】。note §2:64-80 的纠偏论证与源码一致。

### Q2.2 「C2 命中 ≠ 已清晰」是否讲清（质地清楚，但现场口径有误读风险）

- note §6:154 明确写「写后清晰仍【UNKNOWN】……C2 不调 j6/不发消息/不写宿主字段，肉眼翻转在 B5 不可能发生……出口 filemd5 翻转 + 肉眼 A→B→A 属后续 B6 功能判据，本实验不宣称已清晰」。§2:77-80 也把「出口 filemd5 翻转 / 肉眼翻转」划归后续 B6。⇒ **note 文本层已讲清**【源码实证·高】。
- 风险：note §2:73-75 把「C2 第二次回调 exist=true + 非 0ef205 身份」定为「B5 唯一可达成判据 / 通过」，措辞强，用户/现场容易把「B5 通过」误读成「问题解决了」。加上 Q1.2.3 的 mid 判据污染，误读风险放大【推断·中】。**现场开关口径必须统一为：C2 命中只证明「checkExist 机制可达」，不等于清晰；清晰需后续 B6 全量功能 + 用户肉眼 A→B→A 终审。** 非代码 BLOCKER，但需 team-lead 写进现场口径。

### Q2.3 logC2Shape 只读性与异常影响（【源码实证·高】无影响）

- `:381-393 logC2Shape`：`f?.javaClass?.declaredFields` 过滤 String 字段，`field.isAccessible=true` 后 `field.get(f)?.length`，只 join「字段名:长度」，**不打值、不写宿主字段**。`:383-391 runCatching` 包裹，`IllegalAccessException`/InaccessibleObjectException 等会被吞成 `getOrDefault("ERR")`，日志打 `strFields=ERR` 后 C2 仍 `:291 return 0`。⇒ 不影响回调返回、不崩微信【源码实证·高】。Android 上反射私有 String 字段受 runCatching 保护，无编译期/运行期阻断。

`[DONE Q2]`

---

## Q3 —— 改动范围 / 单测可信度 / 变异

### Q3.1 git status 与改动范围（【源码实证·高】本轮只动两文件）

- `git status --short`：`B6CheckExistProbe.kt` 与 `B6CheckExistProbeTest.kt` 均为 `??` 未跟踪新文件；note 称「只改这两处」。
- mtime 核验（本机 stat）：两文件 = 2026-11-25 **20:14:19 / 20:14:54**，impl note = **20:16:13**；而 `HookEntry.kt`(00:32)、`FileSender.kt`(00:31)、`ForwardCdnFieldHook.kt`(00:53)、`Keys.kt`(00:51)、`EntryReceiver.kt`(00:32)、`ForceForward.kt`(11-23 20:50) 均远早于本轮 20:14。⇒ 工作树里这些文件的 `M` 状态是**前几轮累积改动**，本轮 B5 实现 lane **未新增触碰**，与 note §3.2:102 声称一致【源码实证·高】。接线（HookEntry:374 install / EntryReceiver:300 b6step / FileSender:167 armFromForward）为前几轮既有，本轮沿用未改【源码实证·高】。

### Q3.2 11 项变异判红与测试纯 JVM（【源码实证·高】形式成立）

- 核 `B6CheckExistProbeTest.kt:226-321`：11 个 `mutation*` 各自 `source(PROBE).replace(needle, …)` + `assertTrue(mutated != source(PROBE))` 前置真改断言 + `assertFalse(validator(mutated))` 判红，非同义改写。M1~M4 删/清四字段、M5 注 j6、M6 注 native hook、M8 写死指纹、M9 删 z 分支、M10 错字段名、M11 删 map md5 键——needle 均能在当前源码逐字找到（M5 `logC2Shape(f)` 在 :282、M6 install 签名在 :105、M9 `"z" -> null` 在 :294、M10 `"field_exist_whencheck"` 在 :49），变异非等价【源码实证·高】。
- 测试仅依赖 `org.junit.Assert`/`org.junit.Test`/`java.io.File`，`source()` 读源码文本、`codeLines()` 去注释，**不实例化探针、无 Android/Xposed 类路径依赖**⇒ 纯 JVM，`_junit.sh` 可跑【源码实证·高】。
- **但**：测试是**文本断言锁**，只验「源码字面是否含某串」，不验运行时行为。它证明不了 Q1.1 标红的「无内容 MD5 校验」之外的运行时正确性——单测全绿与「主路径名不副实 / 回退占位机制失效」并不矛盾。即：**单测可信度仅覆盖「防删字段/防调 j6/防写死常量」等拼写级契约，覆盖不了机制有效性**【源码实证·高】。

### Q3.3 三闸自报自洽性（【推断·中】仅记录核验，未重跑）

- note §5:132-142 记录闸1=20 tests、闸2=1202 tests、闸3=assembleDebug 全绿，并说明先 compileDebugUnitTestKotlin 前置。按指令**只读核验、不重跑**，无法独立复现结果，仅确认 note 记录与源码改动量自洽（两文件改动 + 20 例测试数一致）【推断·中】。不把这当作通过章。

`[DONE Q3]`

---

## 三件套（交付）

### ① Q1~Q3 一句话结论 + 档位

- **Q1（重中之重）**：note「第三种=动态定位+Y 安全回退」文本自洽但落码名不副实——主路径（内容 MD5 准入）代码未实现（【源码实证·高】）、真机几乎不可达（【推断·高】），实测几乎必走 3B 临时占位回退；该回退按 q4b 字节决定论无法产出原图身份（【源码实证·高 + 推断·高】）、fileId 取 mid 有判据污染风险（【推断·高】），**埋了「目标判据零有效达成路径」的 BLOCKER-1**。walkTopDown 是 worker 线程秒级扫描、非 BLOCKER（【推断·中】）。
- **Q2**：Proxy C2 只读、纠偏成立（【源码实证·高】）；note §6 已讲清「C2 命中≠已清晰」（【源码实证·高】），但现场口径有误读风险（【推断·中】）；logC2Shape 只读且 runCatching 护体、不影响回调（【源码实证·高】）。
- **Q3**：本轮只改两文件属实（【源码实证·高】）；11 变异真改非等价、测试纯 JVM（【源码实证·高】）；三闸仅记录核验未重跑（【推断·中】）。

### ② 是否可装机 go/no-go（含 BLOCKER 清单）

**NO-GO。任一 BLOCKER 即 NO-GO，本轮 BLOCKER 清单：**

- **BLOCKER-1（Q1.1+Q1.2，主）**：主路径「内容 MD5 准入」在源码里不存在（note:40/47 vs 源码 :356/:367 零 MD5 计算），note §1「对齐本轮事实」虚假；主路径在 forward 帧几乎不可达（u/map 无 `/MicroMsg/` 路径 + 同根多 hevc）；回退退化为 3B 随机名临时占位，其机制前提（占位+fileId 锚点决定身份）与 q4b 真机裁决（字节内容 MD5 决定、fileId 零区分力、36B 占位本轮未复现）正面冲突。⇒ 当前落码下 B5 目标判据「C2 第二次回调 exist=true 且非 0ef205 原图身份」几乎无有效达成路径。**必须修，否则装机白跑一轮且结论不可信。**
- **BLOCKER-2（Q1.2.3，辅）**：`fileId` 取 big→mid→thumb，真机坏图无 big 必取 mid(305f)，note §2:74 判据把 `ca47f83e` 当原图身份，而 q4b:84 证其与 cff4f5a5 是否同图【UNKNOWN】⇒ 存在「mid 命中误判为 B5 通过」的判据污染。fail-closed 不校验 fileId 来源（Q1.3）。**修判据或修取值。**

> 非 BLOCKER 但必改：note §1.2/§1.3 撤回「内容 MD5 准入 / 4 参签名 / 对齐本轮事实」表述（虚假陈述）；note §1.4 fail-closed 表述纠偏；walkTopDown 限定 `image2/` 子目录或加时间预算。

### ③ 最小下一步（no-go 路径：谁改什么）

1. **责任方 `r86c-b5-impl`（改 note + 改码 + 补锁）**：
   - note §1.2/§1.3：撤回「主路径内容 MD5 准入 / 对齐 q4b 原图字节 / 4 参签名」虚假表述；诚实标注本实现实为「同根唯一 hevc 文件名 或 3B 临时占位」，与 q4b 字节决定论的冲突如实写明（【UNKNOWN】）。
   - 代码二选一：(a) 让回退占位对齐 q4b——在真实 image2 桶/ `.jpg_hevc` 形态下取**内容 MD5 == cff4f5a5 的原图字节**作 fullpath（需先只读取证该字节文件可获得，q4b Q5 前置 1），或 (b) 明确 B5 只验「checkExist 是否能被三字段启动」（判据降为 C2 有真实第二次回包 + shape 排除空壳），**不声称命中原图**。
   - fileId：判据剔除 `ca47f83e` 或强制 big 来源校验，`fullpath==null` fail-closed 同时校验 fileId 身份；否则 mid 污染。
   - 性能：`walkTopDown` 限定 `image2/` 子目录 + 时间预算。
   - 补单测锁：覆盖「主路径无内容 MD5 时不得声称对齐事实」的文本一致性锁。
2. **责任方 team-lead**：改完派 fresh-eyes 再核一轮（本复核已证旧稿 BLOCKER），确认 BLOCKER-1/2 消除后再谈装机；统一现场口径「C2 命中≠已清晰」。
3. **装机后（若 go）B5 真机判据口径**：只读 SCD+B6X 双探针全开 → 跑模块转发 → 读 `B6X C2 shape`（确认非 Ltj/f 空壳）→ 取**第二次真实回包** `B6X C2 exist=? md5Pre=?` → 判据 = `exist=true` 且 `md5Pre ∈ {cff4f5a5}`（**剔除 ca47f83e，除非先证其与 cff4f5a5 同图**）；`hit`/`UploadHitCacheType` 只记不裁决。无论 C2 结果如何，**现场统一声明：本探针不发消息/不写宿主字段，肉眼不会变化，C2 命中仅证机制可达、不等于已清晰，清晰须后续 B6 功能 + 用户肉眼 A→B→A 终审。**

`[DONE ALL]`

> 交付声明：Q1~Q3 + 三件套全部落盘，真 `[DONE ALL]`。全程只读，唯一写文件 = 本文档；未改源码、未跑 gradle/单测、未碰 adb/装机/设备、未 git commit。判据只对 C2 `field_exist_whencheck`/`field_filemd5` 身份前缀，`hit`/`UploadHitCacheType` 只记录不裁决。所有【推断】未写成【已证】。职责=证伪/找 BLOCKER，已升级 2 项 BLOCKER、NO-GO，非盖通过章。
