# R86c r0.e 覆盖 —— Hook 链路重裁代理（第4轮）裁决

> 角色：`r86c-route2-verdict`（第4轮重裁）。性质 = Pro 类：推翻复核「FileSender ThreadLocal 交接 bigImgPath/tempPath」方案的隐藏死路 + 在用户已授权扩 FileSender 权限的前提下重裁方案A 唯一可行 hook 点 + go/no-go。
> 约束：**只读推理 + 写本文档**；未落码、未跑 gradle、未碰 adb/开关。唯一写文件 = `docs/_r86c_r0e_route2_verdict.md`。
> 三档标注纪律：【真机实证·高|中】/【源码实证·高】/【dex 实证·高】/【推断·高|中|低】/【UNKNOWN 未闭合】。【推断】绝不写成【已证】。判据只对**用户肉眼 / 文件字节**，不用 `hit` / `UploadHitCacheType` 裁决。每条结论带来源（dbg 行号 / 源码行号 / dex 文档锚点）。
> 落盘策略：骨架 → 每答完一问立即写盘留 `[DONE Qn]` → 三件套 → 真 `[DONE ALL]`。

---

## 【第4轮修订 R1-R4】（本轮 = 续接 fix 代理，就地修订，旧 Q1-Q4 中被推翻处以此节为准）

> 修订原因：主会话对 `_r86c_v2_dbg.txt` **逐行核验线程列**后推翻本文件旧 Q2/Q3 的 ThreadLocal 隔离方案。本轮只读 + 就地修订本文档；未落码、未跑 gradle、未碰 adb/开关。
> 三档标注同文首纪律。【推断】不写成【已证】。判据只对肉眼/文件字节。

### R1 撤回错误线程旁证 + 修正 Q2 置信度（已落盘）

- **撤回对象**：旧 Q2 置信度表 :95「隔离须靠 FileSender 目标标记【推断·高】｜V2 :868-925 线程旁证」、Q2.2 :83「send→m1.e 同在 `wxg-forward` 线程，线程亲和在本链有真机旁证」、Q3.2 :118 的 ThreadLocal `TargetToken` 设计。
- **改判**：【真机实证·高·否定】——FileSender 侧与 m1.e 侧**分属不同线程**，ThreadLocal 跨不过线程边界：
  - 【真机实证·高】FileSender / arm 侧全在 `wxg-forward`：`_r86c_v2_dbg.txt:868` `SCD2 g9c … md5Pre=ca47f83e`、`:901` `FileSender queued … wxid=filehelper`、`:902` `FORWARD resend-file … imgLocalId=883190 p3=false … target=filehelper`、`:904` `FORWARD done`；`:869` `FORWARD CDN_FIELD arm skip reason=no localPath` 亦在 `wxg-forward`。
  - 【真机实证·高】m1.e / c2c 侧全在 `[D]#worker`：`_r86c_v2_dbg.txt:921` `SCD m1e in`、`:922` `task fileId= md5=`、`:923` `field_fullpath=…/image2/bd/64/bd64…jpg_ field_midimgpath=null`、`:924` `SCD2 c2c … pathTail=d81.jpg_hevc … fbytes=9554 fmd5pre=0ef205c1`、`:925` `m1e out`；第二段 `:1057-1061` 同点同线程。
  - 【真机实证·高】`_r86c_step1b_dbg.txt:4007-4010` m1.e 亦为 `[D]#worker`（与 V2 一致）。
  - 【源码实证·高】`core/FileSender.kt:236 q1.a() → :251-252 l1.a()` 在 `wxg-forward` 入队（`_r86c_v2_dbg.txt:900/:550` `l1.a queued=true th=wxg-forward`），真正的上传链 `j2.R/j2.O/m1.e` 由 `[D]#worker` 异步执行 ⇒ send 与 m1.e **不可能同线程**。
- **后果**：旧方案在 `wxg-forward` 线程 `ThreadLocal.set(token)`，m1.e before 在 `[D]#worker` 读必为 null ⇒ 命中恒 false ⇒ 落码 100% 零生效，与 M1 同性质翻车。**旧 :95「线程旁证」判断无效，已撤回；不得再引用作隔离依据。**
- **未推翻的受保护事实**（保留原文效力）：M1 BLOCKER、5/5 模糊、step1b 45522B/cff4f5a5 成功、V2 失败链闭合、`field_fullpath` 是唯一绝对路径点、禁用 xml `ca47f83e`、判据只对肉眼/字节。

`[DONE R1]`

---

### R2 重定唯一可行的逐帧隔离机制（本轮核心，已落盘）

#### R2.0 必做 dex 核验 —— `tj/k` 字段清单（本轮亲跑，非引用旧文档）

- 【dex 实证·高】`python _cache/_r14_fields.py fields 'Ltj/k;'` → `classes7.dex def#10272`，`fields total=90`：
  - String 字段含 `field_talker`、`field_fullpath`、`field_filemd5`、`field_mediaId`、`field_fileId`、`field_aesKey`、`field_wxmsgparam`、`field_svr_signature`、`field_thumbpath`、`field_midimgpath`、`d/g/o/p/q/u/C/G/K`；
  - long 字段仅 `W:J`、`t:J`、`field_startTime:J`、`field_lastProgressCallbackTime:J`（**无一丝语义指向 imgLocalId/msgSvrId**）；int 字段 `h/i/r/s/v/w/T/U/V/L/F/P/Q/R`；
  - **不存在** `field_imgLocalId` / `field_msgSvrId` / `field_toUser` 字段。
- 【dex 实证·高】`python _cache/_r67_fields.py 'Ltj/k;'` → `<init>()V` 触碰 `field_talker/field_mediaId/field_filemd5/field_fullpath` 等（仅构造期）。
- 【dex 实证·高】`python tools/_r27_disasm.py --class 'Lcom/tencent/mm/modelimage/j2;' --method O`：`j2.O(r0;I)Z` @`0x2acc44`；`:0x0098 new-instance Ltj/k`、`:0x00a8/:0x012a iput field_mediaId`、`:0x00ae iput field_talker`、`:0x0124/:0x013f/:0x01f8 iput field_fullpath`；`field_filemd5` 在 j2.O **无写入**。
- 【真机实证·高】V2 `:922 m1e task fileId= md5=`（空）、`:924 md5Len=0` ⇒ `field_filemd5` 在 m1.e 侧为空，**不可作对位 key**。
- 【dex 实证·高 + 真机实证·中】`docs/_r86c_d3_dex_forensics.md:91/:97` 映射 `field_talker → toUser (i=0x0027)`；step1b `:4059` `field_toUser=filehelper`，与 FileSender `wxid=filehelper`（V2 `:901/:902`、step1b 同）一致 ⇒ **`field_talker` 是唯一双侧可取且值对应的 key 候选**（V2 m1.e 探针未直接打印 field_talker，故值对应为【真机实证·中】而非高）。
- ⇒ 主会话台账 `:5769` 预查「无直接 imgLocalId/msgSvrId ⇒ (a) imgLocalId key 取不到」**成立**；但「`field_talker` 双侧可取」为本轮新增证据，改变 (a) 的可行性评估。

#### R2.1 候选逐条评估（唯一结论）

**(a) 全局 TTL Map（FileSender 登记目标 key，m1.e before 从 task 取同一 key 命中）**

- 【源码实证·高】现码 `core/ForwardCdnFieldHook.kt:36 TTL_MS=2000`、`:58-66 Pending(data class ts)`、`:65-66 @Volatile pending`、`:112-136 inject()`、`:146-201 arm()` 已是**全局 TTL Map（单槽）**：`FileSender.kt:158-165` 在 `wxg-forward` 调 `arm()`，`:89-93` 的 m1.e before 在 `[D]#worker` 消费 ⇒ **跨线程机制本身在现码已存在且已验证可跨线程**（只有 `@Volatile`，无 ThreadLocal）。
- 【真机实证·高】但现 `arm()` 在 V2 `:869 / :1004` 均以 `reason=no localPath` 失败（`ForwardCdnFieldHook.kt:183-187` 取 ImgInfo2 String 字段含 `/MicroMsg/` 不存在 —— 与 M1 同源），pending 从未登记 ⇒ 现码机制对目标**零生效**。改造须把「登记目标标记」移到 `localPath` 解析**之前**，字节路径改由 m1.e 从 `task.field_fullpath` 现场枚举获得。
- key 裁定：imgLocalId/msgSvrId key **dex 实证不存在 ⇒ 此路不通**；`field_talker` key **dex 实证存在 + 真机值对应（中）⇒ 唯一可用**。规格用 `ConcurrentHashMap<String,Pending>`（key=`field_talker`），一次性 `remove` + TTL，跨线程安全。
- 置信度：key 存在/写入【dex 实证·高】；key 值对应【真机实证·中】；同 talker 并发多图隔离【UNKNOWN 未闭合】。
- 裁决：**唯一可行的隔离机制（附条件）**。

**(b) hevc 天然闸门（不看目标标记，m1.e before 仅查本 task 的 `field_fullpath` + 全局唯一 hevc）**

- 【真机实证·高】V2 `:923` 失败帧 `field_fullpath=…/bd/64/bd64…jpg_hevc`、`:924 fbytes=9554 fmd5pre=0ef205c1`；第二段 `:1059/:1061` `de29…jpg_hevc`、**同为 9554B/0ef205c1 但文件名不同** ⇒ **`.jpg_hevc` 后缀不能区分模糊帧/目标帧**（两帧都已是该后缀）。
- 【真机实证·高】目标态唯一判据 = `fbytes=45522 && fmd5pre=cff4f5a5`（step1b `:4010/:4024/:4038 verdict scanned=22 a45522=1 cff4=1 hit=1 ambiguous=false`）。
- 诚实评估：
  - 无「本次转发」判别 ⇒ 普通发同一张图（非转发）也会命中同一 9554B/0ef205c1 态 ⇒ 被改写指向另一张图的字节 ⇒ **fail-open（复核 M3）**【推断·高】；
  - 多目标并发时全局唯一 hevc 被配给任一先到的模糊帧；若两帧视觉同内容则可接受，但「45522B 是 9554B 的清晰同内容版本」仅为【推断·中】（Q2.1 已标 45522B 属 step1b 另一帧 09ec74ee，非 216b 帧本体），**未证**；
  - 「26 分钟后 hevc 仍在场且仍唯一」【UNKNOWN 未闭合】（q4b :293）；单目标场景（用户一次只发 1 张）误配概率低但仍不能替代目标判别。
- 裁决：**只能作 hook 内的「字节准入」（what），不能单独作「逐帧隔离」（who）**。单独采用 ⇒ NO-GO。

**(c) 其它 hook 点**

- 【真机实证·高 + 源码实证·高】`l1.a()` 与 FileSender 同在 `wxg-forward`（V2 `:900/:550 l1.a queued=true th=wxg-forward`；`FileSender.kt:251-252`），但 `l1.a()` 不接收 task，且在 j2.O `new task` 之前 ⇒ 无法写 `field_fullpath` ⇒ **否决**。
- 【dex 实证·高】`j2.O after` 拿不到 task（task 在方法内 `:0x0098` new）、r0 七 String 字段无绝对路径（M1）⇒ **否决**。
- 【dex 实证·高】hook `tj/k.<init>`：早于 `field_fullpath` 写入且拿不到目标信息 ⇒ **否决**。

#### R2.2 唯一结论 + 置信度 + UNKNOWN + 降级条件

**唯一结论：选 (a) —— 全局 TTL Map（跨线程 `ConcurrentHashMap`/`@Volatile`），key = `field_talker`（目标会话 wxid）；(b) 降为该 hook 内的字节准入条件（模糊态 9554B/0ef205c1 + 唯一 45522B/cff4f5a5），(c) 全部否决。**

- 置信度汇总：跨线程 Map 机制现码已证【源码实证·高】；`field_talker` 字段存在+写入【dex 实证·高】；key 值对应【真机实证·中】；字节准入判据【真机实证·高】；写后肉眼清晰【推断·中】（B2/R79 未消解）；同 talker 并发多图【UNKNOWN 未闭合】。
- **UNKNOWN 必须写进规格并 fail-closed**：① 同 `field_talker` 并发多图（命中多个 pending ⇒ 一律不写）；② 模块转发当刻 cff4f5a5 hevc 是否在场/唯一；③ field_talker 值对应仅 step1b 间接闭合，须保留只读核验日志（不写完整值）；④ 「视觉同内容替换」前提【推断·中】。
- **降级条件（硬）**：若主会话判定「必须支持同 talker 并发多图」且不接受单目标约束，则 (a) 亦失效，(a)(b)(c) 全无可靠逐帧隔离 ⇒ **Q4 改判【维持 NO-GO / 继续只读验证】**，不硬撑 go。

`[DONE R2]`

---

## 输入材料核验（只读，按序读毕）

1. `_r86c_r0e_diff_review.md`：独立复核 NO-GO；M1=BLOCKER（dex 级）；方案A=「FileSender 在 q1.a() 前把 tempPath 写 ThreadLocal 交 j2.R after」。
2. `派发审计台账.md:5757-5768`（「落码试」全段）：用户拍板=方案A 扩 FileSender 权限重做；主会话 synthesis 抓出复核方案A 隐藏死路（bigImgPath 被 64KB floor 卡死）；第4轮重裁派出。
3. `_r86c_q4b_localpath_fix.md:25-46,60-106`：r0 7 个 String 字段全是 DB cursor 相对名/URI，fullpath 由 `j2.O→b1.z3(r0.e)` 现场拼；Q2 唯一选型 = inject(m1.e before) 读 `task.field_fullpath` 取根（同点同字段真机可读）；`refMd5(ca47f83e) ≠ 内容 md5(cff4f5a5)` 致命断裂（:281-289）。
4. `_r86c_microx_route_verdict.md:60-101,140-173`：第3轮裁决 §4.4/Q2；j2.R after hook 可挂、绕开 floor；竞争理论 B2/R79 服务端秒传挑战；多目标 ThreadLocal 同线程前提 UNKNOWN。
5. 日志：`_r86c_step1b_dbg.txt:3994-4010,4024,4038,4055`、`_r86c_v2_dbg.txt:869,905,921-925,1005`、`_r86c_stability_dbg.txt`（背景）。
6. 源码：`core/J2R0eOverrideHook.kt`（全文 368 行，M1 在 :218/:137-140）、`core/FileSender.kt:119-263`、`core/ForwardCdnFieldHook.kt:108-137,146-202,292-325`、`feature/ForceForward.kt:106-131,2559-2606,2718-2765`、`probe/StockCdnProbe.kt:435-501`。
7. dex 字段：本轮工作树 `_cache/_r14_fields.py` 已不存在（亲跑退出码 2），`_s9dump/classes7.txt` 复核亦记录路径不存在；r0 七 String 字段结论引用 `_r86c_q4b_localpath_fix.md:36-38` 与 `_r86c_r0e_diff_review.md:32-39` 已闭合的 dex 实证，**不重复宣称本轮亲跑**。

### 决定性证据逐条核验（均成立）

- 【真机实证·高】step1b 发原图成功链：`_r86c_step1b_dbg.txt:3995` r0 `e=09ec74ee294859cdaaca7a8ff2018dc8.jpg f=同 g=THUMBNAIL_DIRPATH://th_2e53b988…`（全相对名/URI）；`:4009` `field_fullpath=/data/user/0/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37/image2/09/ec/09ec74ee294859cdaaca7a8ff2018dc8.jpg_`（探针 `take(120)` 截断，真值 124 字符，尾部为 `.jpg_hevc`）；`:4010` `pathTail=dc8.jpg_hevc … fexists=true fbytes=45522 fmd5pre=cff4f5a5`；`:4024` 文件扫描 `tail=2018dc8.jpg_hevc len=45522 md5pre=cff4f5a5`；`:4038` 22 个候选中恰 1 个命中；`:4055` `x0cb md5=cff4f5a5` 上传成功。**完整绝对路径只出现在上传 task 的 `field_fullpath` 上，r0 字段只有相对名** —— 主会话新证据成立。
- 【真机实证·高】V2 模块转发失败链：`_r86c_v2_dbg.txt:905` `e=bd64a9fd9bd5a93d8ee5c64878970d81.jpg f=null g=THUMBNAIL_DIRPATH://th_ea49dcab…`；`:923` `field_fullpath=…/image2/bd/64/bd64a9fd9bd5a93d8ee5c64878970d81.jpg_`（同 124 字符截断）；`:924` `pathTail=d81.jpg_hevc fbytes=9554 fmd5pre=0ef205c1`；`:945` `x0cb md5=0ef205c1`。**两帧 field_fullpath 都已是 `.jpg_hevc` 后缀，但一帧 45522B、一帧 9554B**。
- 【源码实证·高】`StockCdnProbe.kt:166` 探针打 fullpath 用 `take(120)`；`:443/446/474-489` 证实 pathTail=`takeLast(12)`、fbytes=`File(path).length()`、fmd5pre=文件字节 MD5 前 8 —— 上述「截断」判读是探针行为，不是路径本身缺后缀。
- 【真机实证·高】`_r86c_v2_dbg.txt:869/1005` 两次 `arm skip reason=no localPath`（同源谓词失败）；`:866/902` `bigimg ready=false … p3=false`。
- 【源码实证·高】`ForceForward.kt:2574` `P3AdmitGate.admit(srcFile,false)`，`:108 FLOOR=64*1024`，`:124 if(f.length()<=FLOOR) return "below-floor"`；`:2740 bigImgPath = copyP3Temp(chosenP3Src)`。⇒ 45522B 必被拒 ⇒ `bigImgPath=null`。
- 【dex 实证·高】`b1.z3(String,String,String,Z)String` 纯路径构造、无 iput/sput（`_r86c_q4b_localpath_fix.md:43,225`）；`j2.O` @`0x2acc44` 内 `i=0x0072/0x007e/0x0088/0x0092` 四处 `getfield r0.e/f/g/j → invoke z3`，`i=0x0098 new tj/k`（同文档 :42）；`field_fullpath` 写入 @`0x2ace9c`（台账 :5242）。

---

[DONE 骨架 + 输入核验]

---

## Q1 方案A hook 点重定

### Q1.0 先判「FileSender 交接 tempPath」是否死路

**结论：确为死路 —— 但死的只是「从 `bigImgPath` 取 tempPath 交接」这一条具体取法，不是 FileSender 这个对象本身。**

- 【源码实证·高】`FileSender.kt:224-229` 的 q1.j 覆盖唯一来源是第 6 参 `bigImgPath`：`ForceForward.kt:2740` `bigImgPath = copyP3Temp(chosenP3Src)`；`copyP3Temp` 在 `:2574` 调 `P3AdmitGate.admit(srcFile,false)`，`:108/:124` `FLOOR=64*1024`、`length<=FLOOR ⇒ "below-floor"`，45522B < 65536 ⇒ `:2575-2577` 直接 return null ⇒ `bigImgPath=null` ⇒ `FileSender.kt:224` 整块跳过。
- 【真机实证·高】V2 `:866 bigimg ready=false path=cd59b126…`、`:902 p3=false`，与源码推断逐字吻合。
- ⇒ 复核建议「FileSender 在 `q1.a()` 前把 tempPath 写 ThreadLocal」**照做仍零字节**：FileSender 此刻根本没有 tempPath 可交。主会话 synthesis 洞察成立。
- 【推断·高】修正口径：FileSender **可以**继续作为「目标帧标记 + 字节路径获取点」，只要它不再从 `bigImgPath` 取路径，而改用（a）文件系统有限枚举 /（b）从上传链捕获并在本进程缓存。用户的「扩 FileSender 权限」授权因此**仍有用，但用法必须改**。

### Q1.1 候选逐条评估（唯一结论）

| 候选 | dex/源码锚点 | 时序 | 绕开 P3AdmitGate | 逐帧隔离 | 裁决 |
|---|---|---|---|---|---|
| **(a) `b1.z3(...)String` after 改写返回值** | z3 @`0x2a6ae4` 纯函数无 iput/sput（q4b :43/:225）；调用点 j2.O @`0x2acc44` i=0x0072/0x007e/0x0088/0x0092、doScene @`0x2adece→o6.l@0x2adf0c`（microx :67） | 单帧内 z3 被调用 ~8 次（V2 :872-886、:907-920），入参 s0 各异（thumb URI / r0.e / null），且 UI 缩略图也走同一 z3 | 是（不经 gate） | **否**：z3 入参只有 4 个字符串/布尔，无 r0/localId/task，无法判定哪一次喂给 field_fullpath；`param.result=` 会同时改写 UI 缩略图及其它帧 | **否决** |
| **(b) `ok0/m1.e(tj/k)Z` before，直接 `setObjectField(task,"field_fullpath",hevcAbsPath)`** | `ForwardCdnFieldHook.kt:83-100` 已挂此点，`:108-137 inject()` 已同点写 fileId/aesKey/fullpath；`StockCdnProbe.kt:435-501` SCD2 c2c 同点同字段真机读 | V2 `:921 m1e in → :923 field_fullpath → :924 c2c 读 9554B → :925 out`；step1b `:4007-4010` 同点读到 45522B。**晚于 j2.O 写 fullpath、早于上传消费** | 是（只 File.exists/length/MD5，无 gate） | **可**：`param.args[0]` 就是 task；**【R1/R2 修订】隔离用全局 TTL Map（key=`field_talker`，跨线程 ConcurrentHashMap），一次性 remove + TTL；旧「ThreadLocal + :868-925 同线程 + finally remove」已被逐行线程核验否定** | **唯一最优（隔离机制以 R2 为准）** |
| **(c) `j2.O()` after 改写** | `j2.O(r0;I)Z` @`0x2acc44`，task 在 i=0x0098 `new tj/k`，fullpath 写入 @`0x2ace9c` | V2 `:921-925 m1.e` 发生在 `:926 j2O out` **之前** ⇒ m1.e 被 j2.O 内部调用；j2.O after 晚于 fullpath 写入且晚于 m1.e 消费；且 j2.O 返回 `Z`，不暴露 task 引用 | 是，但**无效/不可达** | 差：拿不到 task；若改 r0.e，doScene 的 `z3(r0.e)→o6.l` 虽读 hevc，但上传字节由 `field_fullpath` 决定（step1b `:4010` 与 `:4055` 对应），仍是缩略图 | **否决（严格劣于 b）** |
| **(d1) 当前落码 j2.R after 写 r0.e/f** | `J2R0eOverrideHook.kt:137-140,215-268` | r0 七 String 字段无 `/MicroMsg/` 路径（复核 M1【dex+真机实证·高】）⇒ `:218` 恒 null ⇒ `:137-140` 恒 skip | 是 | 无写入（但换取根会触发 M3 fail-open） | **否决（M1 BLOCKER）** |
| **(d2) FileSender `bigImgPath` → ThreadLocal** | `ForceForward.kt:2574` below-floor，V2 `:866/902` p3=false | FileSender 无 tempPath 可交 | — | — | **否决（Q1.0 死路）** |
| **(d3) dataDir 枚举根 + 全局 hevc 写所有帧** | `ForceForward.kt:1340-1343` 范式 | 可得根 | 是 | **否**：全局唯一 hevc 让所有线程所有帧命中，fail-closed 翻 fail-open（复核 M3） | **否决** |

### Q1 唯一裁决

**选 (b)：`ok0/m1.e(tj/k)Z` before 直接写 `task.field_fullpath`。** 它是全链唯一同时满足「有 task 句柄」「真机同点同字段可读可写」「晚于 j2.O 写 fullpath、早于上传消费」「可借 FileSender 目标标记做逐帧隔离」「绕开 64KB floor」五条件的点。配合 FileSender 改为「按 `wxid` 登记跨线程 keyed 目标标记（**非 ThreadLocal**，见 R1）+ hevc 路径由 m1.e 从 `task.field_fullpath` 现场枚举」，构成**重裁后的方案A**。(a)/(c)/(d) 分别因「无法定位喂 fullpath 的那一次 z3 调用」「拿不到 task 且晚于消费」「M1 恒失败 / M3 污染」否决。

`[DONE Q1]`

---

[DONE Q1]

---

## Q2 字节来源与逐帧隔离

### Q2.1 核验主会话推断 —— **半真半假，不能照字面成立**

**推断原文**：「z3 拼路径输入是 `r0.e` + data root ⇒ 45522B hevc 绝对路径本就是按本目标 r0.e 拼出来的，天然逐帧隔离；因此只需在 z3 after 把『指向 9554B 缩略图的路径』替换成『同 r0.e 但 `.jpg_hevc` 后缀的原图路径』。」

逐项核验：

1. **真的部分【真机实证·高】**：fullpath 确实由本帧 `r0.e` 现场拼出 —— step1b `e=09ec74ee…dc8.jpg`（`:3995`）→ `field_fullpath=image2/09/ec/09ec74ee…dc8.jpg_hevc`（`:4009`+`:4010`）；V2 `e=bd64a9fd…d81.jpg`（`:905`）→ `field_fullpath=image2/bd/64/bd64a9fd…d81.jpg_hevc`（`:923`+`:924`）。**在 z3 after 捕获路径时，确实可以按该次调用的 `r0.e` 做到逐帧隔离**。
2. **假的部分【真机实证·高】**：45522B hevc 属于 **step1b 那张新发原图（09ec74ee）**，而模块转发失败帧的 r0.e 是 **bd64a9fd / 216b 那条记录**（V2 `:905`、`:864-865` `localId=883190 msgId=19733550`，与 step1b `:3994 localId=886540` 不同）。**两者不是同一个 r0.e**。失败帧的 field_fullpath **本来就已是 `.jpg_hevc` 后缀**（V2 `:924 pathTail=d81.jpg_hevc`），只是文件仅 9554B/0ef205c1 —— 「同 r0.e 换 `.jpg_hevc` 后缀」得到的是同一份 9554B 文件，**不会变成 45522B**。
3. **净判**：推断的「捕获可隔离」成立；「替换后缀即拿到目标字节」「天然逐帧隔离到目标帧」**不成立**。要把 45522B hevc 注入模块转发帧，必须引入**外部路径来源**（从 step1b 类上传链捕获并缓存，或在 image2 树里按内容 MD5 有限枚举），而这正是复核 M3 的 fail-open 风险点。

### Q2.2 逐帧隔离的正确落点

- ~~【推断·高】隔离靠目标帧 ThreadLocal（V2 :868-925 同线程旁证）~~ **【R1 已撤回：该线程旁证被主会话逐行核验否定，:868/:902/:904 为 `wxg-forward`、:921-925 为 `[D]#worker`，ThreadLocal 跨不过线程】**。隔离机制改由文首「R2」节重裁：**全局 TTL Map（跨线程 @Volatile/ConcurrentHashMap），key = `field_talker`（目标会话 wxid）**，FileSender 在 send 时登记目标，m1.e before 从 `param.args[0].field_talker` 取同一 key 命中；一次性消费 + TTL 兜底。普通发图未登记 ⇒ hook 零动作。**下文 Q3 的 ThreadLocal 规格全部以此为准作废，见 R3 同步修订。**
- 【真机实证·高】目标字节的唯一定位判据：`File.length()==45522`（`:4024`）且内容 MD5 前 8 == `cff4f5a5`（`:4010/:4038/:4055`）；step1b 22 个候选中恰 1 个命中，枚举空间可收敛。
- 【真机实证·高】**禁止**用 xml `.msg.img.$md5` 当参照：V2 `:868/:1004 md5Pre=ca47f83e`，而目标字节是 `cff4f5a5`；用 ca47f83e 枚举必 0 命中（`_r86c_q4b_localpath_fix.md:281-289` 已定此为致命错误）。
- 【推断·中】语义边界：45522B hevc 是**另一张图的字节**用作「视觉相同内容替换」（microx :48/:152），不是「216b 记录本体变清晰」；对端 hevc 解码兼容仍 UNKNOWN。实测转正仍须肉眼 A→B→A。

### Q2 置信度总表

| 结论 | 档位 | 来源 |
|---|---|---|
| fullpath 由本帧 r0.e + data root 现场拼 | 【真机实证·高】 | step1b :3995/:4009/:4010；V2 :905/:923/:924 |
| 45522B hevc 属于 09ec74ee 帧、非 216b 帧 | 【真机实证·高】 | step1b :3994/4024 vs V2 :864/905/924 |
| 「换后缀即可」否决 | 【真机实证·高】 | V2 :924 已是 `.jpg_hevc` 且 9554B |
| ~~隔离须靠 FileSender 目标标记（ThreadLocal）~~ **【R1 已撤回】** | **【真机实证·高·否定】** | 主会话逐行核验推翻：FileSender 侧 :868/:901/:902/:904 全 `wxg-forward`；m1.e 侧 :921-925 全 `[D]#worker`（step1b :4007-4010 一致）；跨线程 ⇒ ThreadLocal 恒 null。详见文首「R1」节 |
| 目标字节判据 = 45522B & cff4f5a5 | 【真机实证·高】 | step1b :4024/:4038/:4055 |

`[DONE Q2]`

---

[DONE Q2]

---

## Q3 落码最小规格（不落码，只定规格）

> **【R3 同步修订】全部规格改建立在 Q1=(b)、R2=(a) 全局 TTL Map（key=`field_talker`，跨线程）之上；旧 ThreadLocal `TargetToken` / `finally remove` 规格全部作废。** 目标：**只让本次转发目标 task 的 `field_fullpath` 指向 45522B/cff4f5a5 hevc**，其余逐字节不变。判据（3.4）不变。

### 3.1 hook 点

- **复用已挂载的** `ok0.m1.e(tj/k)Z` **before hook**（`ForwardCdnFieldHook.kt:72-106` 已挂、`:87-96` before 回调），`param.args[0]` = task；**不再新增第二个 hook**（旧规格「新增独立 hook + 注册顺序在后」作废，避免两个 hook 竞争同字段）。
- **重构 `ForwardCdnFieldHook.inject()`（`:112-137`）**：从「单槽 `pending` 一次性消费、写 fileId/aesKey/`p.originPath`」改为「按 `task.field_talker` 从 `ConcurrentHashMap` 命中 → 字节准入判定 → 只写 `field_fullpath`」。旧 `originPath`（依赖 `no localPath` 解析，V2 `:869/:1004` 必失败）整条删除；**字节路径改在 m1.e before 从 `task.field_fullpath` 现场枚举**，不再在 FileSender arm 阶段解析。
- 独立开关（新 Key，默认 false）、`@Volatile`、off 时回调首行 `return`（零反射、零 IO、零写）；冷启按 Key 默认复位为关；广播 on/off 不持久化。

### 3.2 字节来源与内容 MD5 校验放哪

1. **FileSender.send()（扩权限处，仅登记目标 key，不做路径解析）**：`forwardCtx != null` 时，在 `arm()` 的 `localPath` 解析**之前**先 `pendingMap[wxid] = Pending(wxid, System.currentTimeMillis())`；**不**设 ThreadLocal、**不**从 `bigImgPath` 取路径（死路）、**不**在此时解析 hevc 绝对路径（`no localPath` 必失败，V2 `:869/:1004`）。跨线程用 `ConcurrentHashMap` + `@Volatile`，不依赖任何线程亲和。
2. **m1.e before hook（命中 key 才执行，R2=(a)）**：先从 `param.args[0]` 读 `field_talker`（`tj/k` 有该字段，dex 实证；值对应见 R2.0），查 `pendingMap[field_talker]`；未命中/TTL 超/并发命中>1 ⇒ 立即 return 且**不 remove 别人的条目**。命中后一次性 `remove(key)`，再做字节准入：从 `task.field_fullpath` 正则取 `/MicroMsg/<32hex>/` 根（V2 `:923` 可读取；范式 `ForwardCdnFieldHook.kt:294-295`、`ForceForward.kt:2531-2533`），在 `root+"image2/"` 两级分桶下有限枚举 `*.jpg_hevc`：
   - **模糊态前置门禁**：本 task 原 `field_fullpath` 指向文件须 `f.exists() && f.length()==9554L && md5PrefixOfFile(f)=="0ef205c1"`（V2 `:924/:1061`），否则不写（防止对普通图/已清晰图误写）；
   - **目标态准入** = `f.length()==45522L` **且** `md5PrefixOfFile(f)=="cff4f5a5"`（前 4 字节 hex，与 SCD `fmd5pre` 同口径，`StockCdnProbe.kt:478-489`）；
   - **命中数必须 ==1**，0 或 >1 一律不写（不硬凑、不取第一个）；只写 `task.field_fullpath` = 该唯一 hevc 绝对路径；
   - 全程 `runCatching`，日志只打 `path.takeLast(16)/len/md5前8` 与 `field_talker.take(24)`（不打完整值）。
3. **参照 MD5 硬编码为目标常量 `cff4f5a5`**（step1b `:4010/:4055`），**禁用** xml `.msg.img.$md5`（V2 `:868/:1004` 为 `ca47f83e`，q4b :281-289 已定其致命）；模糊态常量 `0ef205c1` 仅作前置门禁，不作目标参照。

### 3.3 避免影响已闭环的 q1.j / P3 / R73 链

- **不碰**：`q1.j`（`FileSender.kt:187/218-220/224-229`）、`P3AdmitGate`/`FLOOR`/`copyP3Temp`/`chosenP3Src`（`ForceForward.kt:106-131,2559-2606,2727-2740`）、`q1.a()` 调用序（`:236`）、`b1.u8`、DB 写。
- **只增**：FileSender 内 `pendingMap[wxid] = Pending(...)` 一次（**不用 try/finally ThreadLocal**）+ 重构现有 m1.e before 的 inject 为 keyed 消费 + 单字段写。
- **一次性 remove + TTL**：每个目标只写一次；异常只丢本次，外层 `send` 的 `runCatching`（`FileSender.kt:259-262`）不受影响；`send` 失败/`clear()` 时按 `wxid` 精确 `pendingMap.remove(wxid)`（旧 `ForwardCdnFieldHook.clear()` 清单槽，改清 keyed 条目），不误清并发目标。

### 3.4 判据（肉眼 + 文件字节，不用 hit/UploadHitCacheType）

- **终审（唯一 go）**：肉眼 **A(原生清晰) → B(模块转发模糊) → 开闸 B(应翻清晰) → A(清晰)** 四步翻转；基线 5/5 模糊不得推翻。
- **字节旁证（非裁决）**：x0cb `md5` 由 `0ef205c1`（V2 `:945`）翻回 `cff4f5a5`（step1b `:4055`）；SCD c2c `fbytes` 由 9554（V2 `:924`）翻到 ≈45522、`fmd5pre` 由 `0ef205c1` 翻 `cff4f5a5`（step1b `:4010`）；`field_fullpath` tail = `09ec74ee…dc8.jpg_hevc`。
- `hit`(0↔1↔3)、`UploadHitCacheType` 只记录不裁决（纪律）。

### 3.5 回滚

- 开关 off 即刻停写；FileSender 失败路径按 `wxid` 精确 `pendingMap.remove(wxid)` + TTL 兜底，保证无残留标记（**不再靠 ThreadLocal finally remove**）；本设计**不复制临时文件**（直接指向已存在的 hevc 文件），无 orphan 文件需清理 —— 相对当前 `J2R0eOverrideHook` 的 `cache/_<uuid>` + onGYNetEnd cleanup 是净简化。
- 若开闸 B 肉眼仍模糊 / x0cb md5 未翻 ⇒ 立即 off 回退，按「接入失败 / 字节不可获得 / 机制未闭环（B2/R79 竞争理论）」分类排查，不硬推。

### 3.6 单测反向断言（覆盖 M1，堵假绿）

- 断言新实现**不依赖 r0 的 String 字段含 `/MicroMsg/` 作为唯一取根**：新源码文本不得出现对 r0 字段的 `contains("/MicroMsg/")` 定位（直接反 M1）。
- 断言取根来自 `task.field_fullpath`（m1.e before 的 `param.args[0]`），而非 `j2.R` 的 `param.result`。
- 断言 `REF_MD5_PRE=="cff4f5a5"` **且**不引用 `.msg.img.$md5`；断言命中数==1 才写；断言不引用 `P3AdmitGate`/`FLOOR`/`q1.j`/`b1.u8`。
- **【R3 改】断言隔离 key 为 `field_talker`**：源码须出现 `getObjectField(task,"field_talker")` 与 `pendingMap[field_talker]` 命中；**不得出现 ThreadLocal / `TargetToken` / `finally remove`（线程亲和已 R1 否定）**；断言跨线程容器为 `ConcurrentHashMap`/`@Volatile`；断言 `pendingMap` 未命中/TTL 超/并发>1 时不写 task、不误删他人条目；断言 send 失败只 `remove(wxid)` 不清全表。
- 断言模糊态前置门禁 `9554L`/`0ef205c1` 存在且只作门禁（不作目标参照）。
- 变异真判红：改 `REF_MD5_PRE`→`deadbeef`、改 hook 点 m1.e→j2.R、删命中数==1 判断、**删 `field_talker` key 命中改为无条件写**、删模糊态门禁 —— 五项各自判红方为有效。

### 3.7 是否还需改 FileSender.kt

**需要，但用法与复核方案A不同。** 扩权限授权要用在「按目标 `wxid` 登记 `pendingMap` 标记（跨线程 keyed，无 ThreadLocal）」，**不是**「从 `bigImgPath` 交 tempPath」（64KB floor 卡死），**也不是**「在 send 内做 hevc 枚举/解析 originPath」（`no localPath` 必失败，V2 `:869/:1004`）。hevc 枚举移到 m1.e before 从 `task.field_fullpath` 现场做。若拒绝改 FileSender，则无可靠目标登记，只剩 (b) 无判别天然闸门 → 必然触发 M3 帧污染，不可接受。⇒ **授权不可省，但省掉了临时文件与 cleanup 链路**。

`[DONE Q3]`

---

[DONE Q3]

---

## Q4 go/no-go 与既有产物处置

### Q4.1 唯一建议

**【R4 最终裁定：条件 GO —— 进入落码设计，但隔离机制改为 R2=(a) 全局 TTL Map（key=`field_talker`），且受下列门槛与降级条件约束】**

- **门槛0（新增·硬，先于落码）**：确认「单目标/单 `field_talker` 串行」为本场景前提；若主会话要求支持**同 talker 并发多图**，则 (a) 的 keyed map 无法逐帧区分，(a)(b)(c) 全无可靠隔离 ⇒ **立即改判【维持 NO-GO / 继续只读验证】**，不硬撑 go。另须只读核验模块转发当刻 `field_talker` 真机值 == FileSender `wxid`（step1b `:4059 field_toUser=filehelper` 已间接闭合，V2 m1.e 探针未直接打印，日志只打 take(24)）。
- **门槛1（落码前只读）**：确认模块转发当刻，image2 树里仍存在内容 MD5=`cff4f5a5` 且 45522B 的 hevc 文件且命中数==1；step1b `:4024/:4038` 已证「相册发原图时」存在，但模块转发 26 分钟后（V2 23:44 vs step1b 23:18）是否仍在属【UNKNOWN 未闭合，q4b :293 已标】，落码前只读确认。
- **门槛1b（新增·硬）**：确认 `arm()` 改造后 `pendingMap` 登记发生在 `localPath` 解析**之前**（否则 V2 `:869/:1004` 的 `no localPath` 再次使隔离零生效），且 `inject()` 不再写 `p.originPath`、字节路径全部来自 `task.field_fullpath` 现场枚举。
- **门槛2（转正）**：肉眼 A→B→A→A 翻转 + x0cb md5 / c2c fbytes 旁证；任一不满足即 off 回退归档。

### Q4.2 与复核 NO-GO、用户方案A 的关系

- **不推翻复核 NO-GO 的事实基础**：M1 BLOCKER（r0 七 String 字段无路径）、5/5 模糊、step1b 45522B 成功、V2 链闭合、用户肉眼判据全部保留。复核 NO-GO 针对的是**当前落码 `J2R0eOverrideHook.kt`（j2.R after）**，该裁决对本轮仍然成立 —— 本轮不是给旧实现翻案，而是**换 hook 点重做**。
- **推翻的是复核方案A 的具体交接路径**：复核「FileSender 在 q1.a() 前把（来自 bigImgPath 的）tempPath 写 ThreadLocal 交 j2.R after」被 64KB floor 卡死（Q1.0【源码+真机实证·高】）；**保留**其「扩 FileSender 权限」前提，但改为「按 `wxid` 登记跨线程 keyed 目标标记 + hevc 字节在 m1.e 从 task.field_fullpath 现场枚举」，hook 点从 j2.R after 改到 m1.e before。
- **【R4 新增】同时推翻本文件旧第4轮自身的隔离方案**：旧「FileSender 设 ThreadLocal `TargetToken` + m1.e 命中 token」被主会话逐行线程核验否定（R1，:868/:902/:904 `wxg-forward` vs :921-925 `[D]#worker`），属与 M1 同性质的「设计上零生效」。本轮以 R2=(a) 替换，不保留 ThreadLocal 任何形式。
- **与用户拍板一致**：用户授权扩 FileSender 权限重做 = 本轮方案A 的前提；本轮交付的是该授权下的唯一可行重裁，未越过授权范围（未改源码、未装机）。

### Q4.3 当前 `J2R0eOverrideHook.kt` 处置

**明确处置 = 删除重写（不保留改写）。**

- 理由：M1 是**结构性错误**（hook 点选在 j2.R after，而该时点拿不到字节绝对路径；`resolveOriginLocal` 依赖 r0 String 字段含 `/MicroMsg/` 必然失败，`J2R0eOverrideHook.kt:137-140/215-268`）。局部打补丁（如把根换 dataDir 枚举）会直接触发复核 M3 的 fail-open 帧污染。**不可修，只能换点重做。**
- 连带：`J2R0eOverrideHookTest.kt` 13 例均为旧结构的文本断言（复核 M7 假绿），随删除作废旧用例按 §3.6 重写；接线 `Keys.FORWARD_R0E_HEVC`/`HookEntry`/`EntryReceiver` 的 `r0e` 分支一并下线或改指新 Key（避免两个开关并存造成口径混乱）。
- **【R3 改】不新建 hook 文件**：复用重构 `ForwardCdnFieldHook.kt` 的既有 m1.e before（已挂，避免双 hook 竞争同字段）；若确需独立文件则名为 `TaskFullpathHevcHook.kt`，但须同时下线 `ForwardCdnFieldHook` 的同点 inject。无临时文件、无 cleanup hook。

`[DONE Q4]`

---

[DONE Q4]

---

## 三件套

**① 第4轮修订（R1-R4）一句话结论 + 置信度。**
复核方案A 的「bigImgPath→ThreadLocal 交 j2.R after」确为死路（64KB floor，V2 `p3=false`）【源码+真机实证·高】；**本文件旧隔离方案「FileSender ThreadLocal 目标标记」亦被主会话逐行线程核验推翻**（send 侧 `wxg-forward` vs m1.e 侧 `[D]#worker`，R1【真机实证·高·否定】）。唯一可行隔离重裁为 **R2=(a) 全局 TTL Map（跨线程 `ConcurrentHashMap`，key=`field_talker` 目标会话 wxid）**，m1.e before 命中 key + 模糊态门禁（9554B/0ef205c1）+ 唯一目标态（45522B/cff4f5a5）才写 `task.field_fullpath`；(b) hevc 天然闸门仅作字节准入、不能单独隔离；(c) 全否决。置信度：hook 点可达 + 绕开 floor + 字节在场【真机实证·高】；key 字段存在/写入【dex 实证·高】；key 值对应【真机实证·中】；同 talker 并发多图【UNKNOWN 未闭合】；写后肉眼清晰【推断·中】。

**② go/no-go 一句话。**
**【条件 GO —— 进落码设计，隔离机制按 R3 改为 keyed 全局 TTL Map】**：删除重写 `J2R0eOverrideHook.kt`（M1 结构性不可修），**复用重构现有 `ForwardCdnFieldHook` 的 m1.e before**（不新增第二 hook）；**硬门槛0** = 若必须支持同 talker 并发多图则改判【维持 NO-GO】；**硬门槛1b** = pendingMap 登记早于 `localPath` 解析、inject 不写 `originPath`。转正唯一判据 = 肉眼 A→B→A→A 翻转 + x0cb md5→cff4f5a5 / c2c fbytes→≈45522，**不用** `hit`/`UploadHitCacheType`。

**③ 最小下一步。**
交主会话：① 先答门槛0（同 talker 并发是否必须支持）与门槛1（cff4f5a5 hevc 当刻仍在场且唯一）→ ② 删除旧 `J2R0eOverrideHook.kt`/单测/接线 → ③ 重构 `ForwardCdnFieldHook`：`pendingMap[wxid]` 在 arm 的 localPath 解析前登记（跨线程 ConcurrentHashMap + TTL）、inject 改按 `task.field_talker` 命中 + 模糊态 9554B/0ef205c1 门禁 + 枚举 45522B/cff4f5a5 命中==1 才写 `field_fullpath`、失败按 wxid 精确 remove、独立开关默认关、单测含 §3.6 新反向断言（无 ThreadLocal）→ ④ 三闸重跑 → ⑤ 装机肉眼 A/B/A 终审。全程判据对肉眼/文件字节，未碰 adb/gradle/开关，唯一写文件 = 本文档。

---

## 交付声明

- 全程只读推理，**未落码、未跑 gradle、未碰 adb/开关**；唯一写文件 = `docs/_r86c_r0e_route2_verdict.md`。
- 未推翻任何受保护事实：M1 BLOCKER、5/5 模糊、step1b 45522B 成功、V2 链闭合、用户肉眼判据、微X字节来源 UNKNOWN、`hit` 不裁决。
- 诚实边界：step1b 是**「从相册发原图」上传链**、非模块转发；用它证明模块转发可行处均标【推断】并说明差异（帧的 r0.e 不同、当刻字节仍在场未证）。**本轮 dex 工具亲跑**：`_cache/_r14_fields.py fields 'Ltj/k;'` 与 `_cache/_r67_fields.py 'Ltj/k;'`、`tools/_r27_disasm.py --class 'Lcom/tencent/mm/modelimage/j2;' --method O` 均实跑并落 R2.0；旧「本轮工作树不存在」表述作废。
- 【推断】项均未写成【已证】：**目标标记线程亲和已被 R1 否定（不是推断而是反证）**；`field_talker` 值对应只到【真机实证·中】；同 talker 并发多图【UNKNOWN 未闭合】；写后清晰【推断·中】。判据只对用户肉眼/文件字节。
- **本轮撤回**：旧 Q2:95「线程旁证【推断·高】」、Q2.2 :83 ThreadLocal、Q3.2 :118 ThreadLocal `TargetToken`、Q3.3/Q3.5/Q3.6 `finally remove`、Q4/三件套「进入落码设计（ThreadLocal 隔离）」—— 全部以文首 R1-R4 为准。

真 `[DONE R-ALL]`
