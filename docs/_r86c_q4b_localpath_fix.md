# R86c Q4b `no localPath` 修复收口规格（别名路由·降档重派）

> 任务性质：Hook 链路设计 + 接入点选型 + dex 可达性取证 + 落码规格（不落码）
> 唯一目标：收口首次真机验收 `arm skip reason=no localPath` 导致修复未生效，给出「原图字节路径从哪取、在哪个时点取」的唯一可行规格，交 Flash lane 机械落码。
> 三档标注：【dex 实证】/【真机实证】/【推断·高|中|低】/【UNKNOWN 未闭合】

## 失败事实（真机，不得推翻）
- Q4b 落码包已装机（设备 APK 3154270B），`FORWARD CDN_FIELD hook ok0.m1.e exact` 挂载成功。
- `_r86c_q4b_landing_verify_raw.txt:4`：`02:50:38.427 wxg-forward FORWARD CDN_FIELD arm skip reason=no localPath` —— arm 阶段未注入、未写 `field_fullpath`。
- 出口未翻转：`:5` `SCD2 c2c in ... pathTail=ae6.jpg_hevc ... fbytes=9554 fmd5pre=0ef205c1`（仍模糊态）；`:7` `SCD2 x0cb in md5=0ef205c1 ... hit=3` —— 与上轮模糊态逐字相同，md5 未翻回 `cff4f5a5`。
- 结论：安全降级（没崩溃、没污染普通发图）但**修复未生效**；肉眼预期仍模糊（待终审）。
- 根因代码：`ForwardCdnFieldHook.kt:177-181`，`img = o8.o2(imgLocalId)`（o8=`d3.O8()`）后遍历 `img.javaClass.declaredFields` 找「以 `/` 开头且含 `/MicroMsg/`」的 String 字段 → 真机取不到 → `skip("no localPath")`。

## 输入清单（按序读毕，全部只读）
1. `docs/_r86c_q4b_landing_spec.md`（Q2 路径解析 / Q3 完整规格 / Q4 判据，本轮修的正是其 arm 内 localPath 前置 :99/:176-181）
2. `docs/_r86c_q4b_final_verdict.md` + `docs/_r86c_q4b_forensics_raw.md` + `_r86c_q4b_fullpath_raw.txt` / `_r86c_q4b_stepAB_raw.txt`
3. `wxgroup/app/src/main/java/com/lx/wxgroup/core/ForwardCdnFieldHook.kt`（全文，重点 arm :142-196 / inject :108-133 / resolveOriginFullpath :219-252 / Pending :54-59）
4. `wxgroup/app/src/main/java/com/lx/wxgroup/core/FileSender.kt`（:156-165 arm 调用时机早 / :199-231 q1.j 三级覆盖）
5. `wxgroup/app/src/main/java/com/lx/wxgroup/probe/StockCdnProbe.kt:423-470`（SCD-c2c 同点同字段实证）
6. `docs/_r82_b2_netscene_forensics.md` / `docs/_r81_r80a_falsified_verdict.md` / `docs/_r86c_d3_dex_forensics.md`（历史反证 + x0.u 字段映射）
7. 本轮亲跑 dex：`_r35_adnd_dex.py` `cls/meth/fields/calln`、`_r35_sns_dis.py bodyx`、`_r86c_off.py` 自算偏移（off 全部本轮核准，非照抄）。

## Q1 根因收口

**结论（一句话）**：`no localPath` 不是 `imgLocalId` 传错，也不是"字段名/类型不同但字段存在"——而是 **`img = o8.o2(imgLocalId)` 返回的 `com.tencent.mm.modelimage.r0`（即所谓 ImgInfo2）里根本没有一个以 `/` 开头、含 `/MicroMsg/` 的 String 字段**；`r0` 的 String 字段装的是 DB cursor 列字符串（CDN URL / URI / xml 内容），真正的本地 fullpath 是 `j2.O` 调用 `b1.z3(r0.e/f/g/j...)` **现场拼出来的**（经 VFS `qi2/a.b()`/`h7.b()`），从不作为绝对路径字面量存在 `r0` 的 String 字段里。故 `ForwardCdnFieldHook.kt:177-181` 的遍历必然返回 null → `skip("no localPath")`。【dex 实证·高】

### 证据链（全部本轮亲跑，偏移自算核准）

**(1) `o8` 的真实类型与 `o2` 签名 —— 纠正输入里的"d3.o2"表述**
- 【dex 实证】`d3.O8()` 的返回类型是 `Lcom/tencent/mm/modelimage/b1;`（`calln 'modelimage/d3'` 输出 `d43/i0;->invoke() -> d3.O8()Lcom/tencent/mm/modelimage/b1;`，多处一致），**不是 d3 自身**。故 `o8` 实际是 `b1`，`arm()` 里 `callMethod(o8, "o2", imgLocalId)` 调的是 `b1.o2`。
- 【dex 实证】`b1.o2(Ljava/lang/Long;)Lcom/tencent/mm/modelimage/r0;`，classes7.dex，`code_off=0x2a52a0`（`_r86c_off.py` 自算），指令绝对偏移 = `0x2a52a0+16+2·i`。方法体：
  - `i=0x0005 Long.longValue()` → `i=0x0009 a1.a(J)` 拼 `id=?` → `i=0x0028 invoke-virtual b1.B7(...)` 查 DB → `i=0x002e Cursor.moveToFirst()` → `i=0x0034 invoke-virtual r0.b(Cursor)` → `i=0x003a return v0`（**always new r0，非 null**）。
  - ⇒ `img` 对象永远非 null（空 r0 也非 null）；`imgLocalId` 用于 `id=?` 查询，`FileSender.kt:202-203` 把同一个 `ctx.imgLocalId` 写进 `hl0/m.a`，而 `j2` 构造器用 `m.a>0 → d3.O8().o2(m.a)` 挂 ImgInfo2（`FileSender.kt:194-197` 注释 + 本轮 dex 印证），**同一 id 在真实发送链能查到记录并拼出 124 长度 fullpath**（`_r86c_q4b_fullpath_raw.txt:5/7` 真机），故 `imgLocalId` 传参正确、非根因。【dex 实证·高 + 真机实证·高】

**(2) `r0`（ImgInfo2）的全部 String 字段 —— 没有一个存绝对路径**
- 【dex 实证】`r0 extends Ljava/lang/Object;`（classes7.dex），String 字段仅 7 个：`e/f/g/j/l/m/u`（`_r35_adnd_dex.py fields 'modelimage/r0'`，`r0.b` 反汇编 `code_off=0x2b3ca0`，绝对偏移 `0x2b3cb0+2·i`）：
  - `e ← Cursor.getString(4)`（i=0x001e/0x0022）、`g ← getString(5)`（i=0x0025/0x0029）、`u ← getString(12)`（i=0x005c/0x0060）、`m ← getString(16)`（i=0x0074/0x0078）、`f ← getString(18)`（i=0x0084/0x0088）、`j ← getString(20)`（i=0x0094/0x0098）、`l ← getString(22)`（i=0x00a4/0x00a8）。
  - 全是 DB cursor 列的**相对/URI/标识字符串**，无一是 `/data/user/0/.../MicroMsg/...` 绝对路径。`ForwardCdnFieldHook.kt:180` 的 `it.startsWith("/") && it.contains("/MicroMsg/")` 谓词对全部 7 个字段恒 false ⇒ `firstOrNull` = null ⇒ `skip("no localPath")`。
- 【dex 实证】`r0.e()`（`code_off=0x2b3b94`，绝对偏移 `0x2b3ba4+2·i`）返回的是 CDN URL 而非路径：`i=0x0008 g9.c(r0.u, "msg", "")` → `i=0x0012 const-string '.msg.img.$cdnmidimgurl'` / `i=0x001b '.msg.img.$cdnbigimgurl'` → `Map.get` → `return`。即 `r0` 的 String 字段装的是 CDN 引用 key 与 xml 内容，不是本地字节路径。

**(3) 真正的 fullpath 从哪来：`j2.O` → `b1.z3` 现场拼（不在 r0 字段里）**
- 【dex 实证】`j2.O(Lcom/tencent/mm/modelimage/r0;I)Z`，classes7.dex，`code_off=0x2acc44`（自算核准）。关键指令：`i=0x0072 iget-object r0->e` → `i=0x0076 invoke b1.z3(...)`；`i=0x007e r0->f` → `i=0x0080 z3`；`i=0x0088 r0->g` → `i=0x008a z3`；`i=0x0092 r0->j` → `i=0x0094 z3`；随后 `i=0x0098 new-instance Ltj/k`（task 构造）。即 fullpath 是 `z3(r0.e/f/g/j)` 的返回值写入 task 字段，**不是直接读 r0 的某个绝对路径字段**。
- 【dex 实证】`b1.z3(String,String,String,Z)String`，classes7.dex，`code_off=0x2a6ae4`（自算核准）。方法体按前缀分支：`i=0x0008 'SERVERID://'`、`i=0x0045 'THUMBNAIL_DIRPATH://'`、`i=0x005a 'THUMBNAIL_APPMSG_DIR://'`，否则 `i=0x0041 qi2/a.b()`、`i=0x0077 jx2/p.z8()` 取目录，最终 `i=0x008e g1.a(...)` 合成路径返回。
  - `qi2/a.b()`（classes7，`code_off` 未单独算，bodyx 直读）`i=0x0005 'image2'` → `h7.b("image2")` → `+ "/"` 返回 **image2 根目录**（VFS 抽象）。
  - `h7.b(String)`（classes10，`code_off=0x41e7ac`，绝对偏移 `0x41e7bc+2·i`）→ `i=0x0002 sget w6.a` → `w6.b(fsName)` 从 VFS 挂载表解析。
  - ⇒ MicroMsg 数据根来自 **VFS 层运行时解析**，不在 r0 字段里；r0 的 String 字段只是 z3 的输入 URI（SERVERID/THUMBNAIL 前缀或相对标识）。

**(4) 三选一判定**
- 是"字段本就不在 ImgInfo2" ⇒ **是**（本轮 dex 坐实：r0 7 个 String 字段无绝对路径；fullpath 由 z3 拼）。
- 是"字段名/类型不同但字段存在" ⇒ **否**（没有等价字段被漏读；遍历已逐个 get 全部 String 字段）。
- 是"`imgLocalId` 传错" ⇒ **否**（`o2` 用 `id=?` 查库，同 id 在 FileSender.m.a 链能拼出 fullpath，真机 task 非空；`img` 非 null 不因 id 错而变）。

### 置信度
- 「r0 无 `/MicroMsg/` String 字段 / fullpath 由 z3 拼 / o2 返回 r0」=【dex 实证·高】（本轮亲跑 calln/fields/bodyx/off，偏移自算）。
- 「imgLocalId 传参正确」=【dex 实证·高 + 真机实证·高】（同 id 的 m.a 链能拼 124 长度 fullpath）；注意 `o2` 恒返回非 null r0，故"img 非 null"本身不能单独证明 id 正确，靠 m.a 链交叉印证。
- 【UNKNOWN 未闭合（不影响本结论）】r0.e/f/g/j 精确 DB 列名与 z3 各分支在真机取哪一支（只读静态未 hook 运行时寄存器）；VFS `w6.b("image2")` 运行时解析出的字面根串（本轮未真机打印）。

`[DONE Q1]`

## Q2 取根来源唯一选型

**结论（唯一推荐）**：选 **① —— 在 `inject()`（m1.e before）读 `param.args[0]`（task=`tj/k`）的 `field_fullpath`，正则取 `/MicroMsg/<32hex>/` 数据根**。这是唯一满足「inject 才有 task、arm 没有」时点事实的点，且同点同字段已被 SCD-c2c 探针真机实证可读、根串含完整 MicroMsg。②（arm 阶段从别的对象/方法取根）与③（其它）全部否决。**随之把原图解析整体从 arm 移到 inject**：`Pending.originPath` 取消，改携带 `contentMd5`（准入参照）；arm 只读取 map + 装 pending + 开闸，inject 负责取根 + 解析 + 写 `field_fullpath`。**"文件名 md5 候选"降级为可选快路径，不再作为依赖——主定位改为按内容 MD5 的有限枚举**。

### 决定性时点事实（对齐红线）
- 【真机实证】`arm()` 调用点 `FileSender.kt:160`，在 `q1.a()`（:236）、`j2.O`（写 field_fullpath）、`m1.e`（inject）**之前**——arm 时**根本没有 `tj/k` task**，不可能读 `task.field_fullpath`。
- 【真机实证 + dex 实证】`inject()` 挂 `ok0/m1.e(tj/k)Z before`（`ForwardCdnFieldHook.kt:83-100`），`param.args[0]` = task；`j2.O` 在 m1.e 之前已把 fullpath 写进 task（`landing_spec:37` 本轮已闭合 j2.O→m1.e 顺序；本轮 dex 复核 `j2.O i=0x0072-0x0094` 四处 `z3`→task 字段、`i=0x0098 new Ltj/k`，见 Q1(3)）。
- 【真机实证】失败验收当刻 `02:50:38.656 SCD2 c2c in ... fullpathLen=124 pathTail=ae6.jpg_hevc ... fexists=true fbytes=9554 fmd5pre=0ef205c1`（`_r86c_q4b_landing_verify_raw.txt:5`）——**同点同字段**证明 inject 处 `field_fullpath` 非空、可读、含完整根；历史完整路径 `_r86c_q4b_fullpath_raw.txt:4/6` 逐字给出根 `/data/user/0/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37/image2/`（本轮 `grep -o` 复核唯一值）。

### 候选逐条评估（可达性 + 时点 + 改动面 + 风险）

**① inject(m1.e before) 读 `param.args[0].field_fullpath` 取根 ——【dex 实证·高 + 真机实证·高】唯一推荐**
- 可达性：`param.args[0]` = `tj/k`（D3 Q2 2.1 i=2 参落 v6），`XposedHelpers.getObjectField(task, "field_fullpath") as? String` 直读；SCD-c2c 同点同法已真机跑通（`StockCdnProbe.kt:436-438`）。正则 `Regex("/MicroMsg/[0-9a-fA-F]{32}/").find(path)` 取根（范式 `ForceForward.kt:2531-2533`）。
- 时点：正确——inject 晚于 j2.O 写 fullpath、早于 x0.u `setFilePath` 消费（`landing_spec:37`/R82 Q1.2）；此刻 task 上手持根，且原图字节文件已落盘（02:11 A 态 45522B 存在）。
- 改动面：`ForwardCdnFieldHook.kt` 一个文件——arm 去掉 img/localPath、pending 改带 contentMd5；inject 加取根+解析+写 fullpath；`FileSender.kt`/`HookEntry.kt` 不动（Q3 详列）。
- 风险：唯一风险 = "本地无 cff4f5a5 原图字节文件"（字节不可获得）→ 枚举 0 命中 → 安全降级保持模糊，按 Q4 互斥表 no-go，不硬凑。可控、可前置只读确认。

**② arm 阶段从别的对象/方法取根 —— 全部否决**
- **②a `qi2/a.b()` / `h7.b("image2")` VFS 取 image2 根** ——【dex 实证】可达但否决：`qi2/a.b()` static no-arg（`classes7`，`i=0x0005 'image2'`→`h7.b`→`+"/"`，calln 见 b1.u3、di.a 等多处调用）；`h7.b`→`w6.b(fsName)` 查 VFS 挂载表（`classes10`，`code_off=0x41e7ac`）。**否决理由**：① 返回的是 VFS 抽象路径（`w6.c` HashMap→`k1.b` 经 `f1.a(Map)` 解析），**真机字面根串 `/data/user/0/.../MicroMsg/<hash>/` 未取证**【UNKNOWN】；② arm 时 task 不存在、r0 又无 localPath（Q1），即便拿到根仍缺文件名 md5 候选，回到 img 依赖；③ 改动面更大（新增 VFS 反射 + 运行时解析），不比①更稳。
- **②b 从 `img`(`r0`) 字段取 localPath**（即现行失败写法）——【dex 实证】已证伪：Q1 证明 r0 的 7 个 String 字段无一是绝对路径，且 fullpath 由 z3 现场拼。这就是本次失败的 root cause，直接删除。
- **②c 在 arm 调 `b1.z3(...)` 现场拼当前图 fullpath** ——【dex 实证】可达但否决：`z3` 需 `r0.e/f/g/j` 的精确 URI 语义（Q1 已标 UNKNOWN），且它拼出的是**当前缩略图**路径（9554B），不是原图字节；在 arm 调属额外宿主调用，无收益。
- **②d 硬编码根 `/data/user/0/com.tencent.mm/MicroMsg/89476c…/`** ——否决：违反"不硬编码 hash"（`landing_spec:105`），跨账号/设备/重装失效。

**③ 其它 —— 否决**
- **③a 从 `contentMd5`(cff4f5a5) 直接拼 image2 路径** ——否决：文件名 md5（88da…）≠ 内容 md5（cff4f5a5），两者不同源（landing_spec:62；A 态 88da…=路径文件名、cff4f5a5=文件内容 MD5）。用它拼路径必错。
- **③b 从 task 当前 fullpath 的文件名（ae6…/a77…）当候选** ——否决：那是**另图/缩略图**的文件名 md5，非原图 88da…；失败验收 `pathTail=ae6.jpg_hevc` 正是反例。

### 文件名 md5 候选怎么来 / 有限枚举怎么限界（Q2 关键子问）
- **不再依赖 img 抠 32 位 hex 当主路径**（该映射历史 UNKNOWN，`landing_spec:191`；Q1 又证 r0 String 字段不含 localPath）。改为：**主定位 = 按内容 MD5 的有限枚举**（下面 1)。
- 可选快路径（保留但非必需）：若 `r0` 的 String 字段里能 regex 出 32-hex 候选，先按候选拼 `image2/<2>/<2>/<md5>.jpg_hevc` 试命中（命中即返回，省枚举）；**不命中不报错**，直接落枚举兜底。候选来源 dex 可达（`r0.e/f/g/j/l/m/u` 均 String，Q1），但映射 UNKNOWN ⇒ 只作加速、不作依赖。
- **有限枚举限界（与现码 :238-248 同范式，收紧）**：
  1. 只扫 `root + "image2/"` 下**两级**分桶目录（`image2/<2hex>/<2hex>/`），不递归更深、不跨 root、不读 thumb/ 等其它目录；
  2. 只看后缀 `.jpg_hevc`（A/B 两态实测形态）；
  3. 准入 `f.exists() && f.length()>0 && md5PrefixOfFile(f)==refPre`（refPre=`contentMd5` 前 8，来自 arm 的 g9.c map）；
  4. **命中数必须 == 1 才采信**；==0（字节不可获得）或 >1（歧义）一律 `null` → 安全降级，绝不硬凑、绝不取第一个。
  5. 全程 runCatching；日志只打 `path.takeLast(16)/len/md5前8`，不打完整 hash。

### 改动面与风险小结
| 维度 | ① inject 读 task.fullpath 取根（推荐） | ② arm 取根 / ③ 其它 |
|---|---|---|
| 时点对齐「inject 才有 task」 | ✅ 天然对齐 | ❌ arm 无 task / 需额外 VFS 反射 |
| 取根可达性 | ✅ 同点同字段真机实证 | ②a VFS 抽象根【UNKNOWN 未闭合】/ ②b 已证伪 / ②c 拼错图 / ③ 硬编码或错 md5 |
| 真机证据 | ✅ 02:50:38.656 同点可读 + 完整根逐字 | 无 |
| 改动面 | 单文件（ForwardCdnFieldHook.kt） | 更大（新 VFS 取数/硬编码/依赖 img） |
| 失败风险 | 仅字节不可获得 → 安全降级 no-go | 根错/拼错路径/跨账号失效 |

`[DONE Q2]`

## Q3 完整可落码规格（Flash lane 照写即可）

> 目标：只改 `ForwardCdnFieldHook.kt` 一个文件；`HookEntry.kt`/`FileSender.kt` 不动。**核心 = 删除 arm 里取不到的 ImgInfo2 localPath 前置，把取根+解析整体移到 inject，从 task 自带 fullpath 取根。**
> 不动：P3AdmitGate / chosenP3Src / copyP3Temp / q1.j 三级覆盖 / j2.O / x0.u / fileId 注入以外的字段。

### 职责切分（arm ↔ inject）

| | arm（`FileSender.kt:160` 调，早，无 task） | inject（`m1.e before`，晚，有 task） |
|---|---|---|
| 输入 | talker / msgSvrId / imgLocalId（后两者用于 a3/g9.c map） | `param.args[0]`=task（含 j2.O 已写好的 `field_fullpath`）+ pending |
| 取数 | 只读 g9.c map → fileId / aesKey / **refMd5**（`.msg.img.$md5`） | 读 `task.field_fullpath` → 正则取 MicroMsg 根 → 有限枚举定位原图字节路径 |
| 产出 | `Pending(fileId, aesKey, refMd5, ts)` + `enabled=true` | 命中则写 `field_fileId`/`field_aesKey`/`field_fullpath`；未命中则不写 |
| 失败 | `skip(...)` 清 pending 关闸（安全降级） | `origin==null` ⇒ 不动 task 任何字段（保持现状模糊，不硬凑） |

- **`Pending.originPath` 取消**（arm 时根本算不出，Q2 时点事实）。改为携带 **`refMd5: String`**（准入参照，arm 从 map 取，inject 用）；B 态失败验收停在 `no localPath`、未报 `no contentMd5` ⇒ **map 里 `.msg.img.$md5` 真机可读**（`_r86c_q4b_landing_verify_raw.txt:4` 反证），故 refMd5 前置在真机可满足。
- 为何 inject 不现读 refMd5：`m1.e before` 时 `task.field_filemd5` 为空（`_r86c_q4b_stepAB_raw.txt:4/15` `md5Len=0`），mars 在 x0.u 之后才从 fullpath 字节重算 ⇒ 参照 md5 只能由 arm 从 map 预取并随 pending 带入。

### 改动 1 — `ForwardCdnFieldHook.kt`（唯一改动文件）

**1a. `Pending`（:54-59）—— 字段替换**
```kotlin
private data class Pending(
    val fileId: String,
    val aesKey: String,
    val refMd5: String,   // 替换原 originPath：内容 MD5 参照（g9.c map .msg.img.$md5）
    val ts: Long,
)
```
- 类注释（:49-53）同步：`refMd5 = arm 从 xml 取的内容 MD5 参照，inject 用它对本地原图字节做准入`。

**1b. `arm()`（:142-196）—— 删除 img/localPath/解析，加 refMd5**
- 保留 :148-165（findClass/O8/a3/r0.u/g9.c map/fileId/aesKey 读取，全部只读）。
- 保留 :168-172 的 `contentMd5 = map[".msg.img.$md5"]` 读取与空值 skip（改名为 `refMd5` 或保留变量名均可，存进 pending）。
- **删除** :173-186 整段：`val img = callMethod(o8,"o2",...)`、`val localPath = img...firstOrNull{...} ?: skip("no localPath")`、`val originPath = resolveOriginFullpath(img, localPath, contentMd5)`、`if(originPath==null) skip("origin unavailable")`。
- **改** :188 pending 构造：`pending = Pending(fileId, aesKey.orEmpty(), contentMd5, System.currentTimeMillis())`。
- **改** :190 日志：`LogX.i("$TAG armed fileIdLen=${fileId.length} refMd5Pre=${md5PrefixOf(contentMd5) ?: "NA"}")`（**不再打 originTail**——arm 已无 origin 可打；originTail 移到 inject 日志）。
- 签名不变：`fun arm(talker: String, msgSvrId: Long, imgLocalId: Long)`——imgLocalId 保留但本函数体不再使用（供未来扩展；不删签名以免动 `FileSender.kt:160`）。Kotlin 会有未用参数告警，加 `@Suppress("UNUSED_PARAMETER")` 于函数即可，或保留 :144 的 `imgLocalId<=0` 校验继续用。

**1c. `inject()`（:108-133）—— 加取根+解析+写 fullpath**
顺序（保留 TTL :110-116、一次性清 pending :118-119、task 获取 :120 不变）：
```kotlin
val task = param.args?.getOrNull(0) ?: return
// ① 取根：从 task 自带 fullpath 正则提 MicroMsg/<32hex>/（Q2 唯一选型）
val curPath = runCatching { XposedHelpers.getObjectField(task, "field_fullpath") as? String }.getOrNull()
val originPath = if (curPath.isNullOrBlank()) null
                 else resolveOriginFullpath(curPath, p.refMd5)
if (originPath == null) {
    // 字节不可获得/歧义 ⇒ 不动 task，保持现状模糊（安全降级，不硬凑）
    LogX.w("$TAG injected origin=unavailable reason=no-origin-bytes")
    return
}
// ② 命中才写三字段（fileId/aesKey 沿用现成字段对齐，fullpath 是决定量）
runCatching { XposedHelpers.setObjectField(task, "field_fileId", p.fileId) }...
runCatching { XposedHelpers.setObjectField(task, "field_aesKey", p.aesKey) }...
runCatching { XposedHelpers.setObjectField(task, "field_fullpath", originPath) }...
LogX.i("$TAG injected fileIdLen=${p.fileId.length} aesKeyLen=${p.aesKey.length} origin=true originTail=${originPath.takeLast(16)}")
```
- **位置约束**：写 fullpath 必须在「一次性清 pending :118」与「拿到 task :120」之后；与 fileId/aesKey 同一 hook 同一次消费（修正裁决 Q4 表述）。
- **`originPath==null` 时的语义**：不写 fileId/aesKey/fullpath 任一字段（保持现状模糊）——与 R81/R82「不硬凑、不污染普通发图」一致。普通发图 `forwardCtx==null ⇒ 不 arm ⇒ enabled=false ⇒ :85 `if(!enabled)return` ⇒ 逐字节不变。

**1d. `resolveOriginFullpath` —— 改签名 + 主定位改为有限枚举**
签名由 `(img: Any, localPath: String, contentMd5: String)` 改为 `(curFullpath: String, refMd5: String): String?`：
```
1) 取根：Regex("/MicroMsg/[0-9a-fA-F]{32}/").find(curFullpath) ?: return null
        root = curFullpath.substring(0, hit.range.last + 1)   // → ".../MicroMsg/<hash>/"
2) refPre = md5PrefixOf(refMd5) ?: return null               // 前 8 hex（cff4f5a5）
3) 【可选快路径，非依赖】若能从 r0 字符串字段 regex 出 32-hex 候选（dex 可达但映射 UNKNOWN），
   先按 root + "image2/${md5[0..1]}/${md5[2..3]}/$md5.jpg_hevc" 试 admitOrigin；命中即返回。
   拿不到候选/不命中 ⇒ 不报错，直接落 4)。——注意：本函数已不接收 img 对象；若要做此快路径，
   需在 arm 把候选 md5 列表一并塞进 Pending（新增 List<String> 字段），或干脆省略（推荐省略，见下）。
4) 【主定位：有限枚举】只扫 root+"image2/" 下两级分桶目录（image2/<2hex>/<2hex>/），
   只认 *.jpg_hevc，做 admitOrigin(f, refPre)；hits.size==1 → 返回，==0 或 >1 → null。
5) runCatching 全包；日志只打 path.takeLast(16)/len/md5前8。
```
- **推荐省略快路径（3)）**：Q1 已证 r0 String 字段不含 localPath，其 32-hex 映射历史 UNKNOWN（`landing_spec:191`）；保留只会增加不确定性。**纯枚举已足够定位**（A 态文件 `image2/88/da/88da…jpg_hevc` 必落在两级分桶内，`_r86c_q4b_fullpath_raw.txt:4` 实证），且命中数==1 天然排除歧义。若 Flash lane 想加速，可在 Pending 增 `candidates: List<String>` 由 arm regex 预取——但那是可选优化，不改判据。
- **准入判据唯一**：`admitOrigin(f, refPre)` = `f.exists() && f.length()>0 && md5PrefixOfFile(f)==refPre`。**禁 P3AdmitGate**（FLOOR=65536 会 below-floor 拒 45522B；IMAGE2_RE 不认 `.jpg_hevc`，`final_verdict:283`）。
- **工具函数复用不改**：`admitOrigin`（:255-259）、`md5PrefixOfFile`（:262-273，全文件 MD5 前 4 字节 hex，与 SCD fmd5pre 口径一致）、`md5PrefixOf`（:276-279）原样保留。

**1e. 日志契约变更（影响 Q4 旁证）**
- 旧信号 `CDN_FIELD armed originTail=...`（:190）**消失**（arm 无 origin）。
- 新信号：`CDN_FIELD armed fileIdLen=.. refMd5Pre=cff4f5a5`（arm）+ `CDN_FIELD injected ... origin=true originTail=<16>`（inject，命中）/ `injected origin=unavailable reason=no-origin-bytes`（未命中）。Q4 验收改看新信号。

### 改动 2 — `HookEntry.kt`：不动
- 失败验收已证实 `FORWARD CDN_FIELD hook ok0.m1.e exact` 挂载成功（`_r86c_q4b_landing_verify_raw.txt:1` + 源码 :95 日志），install 已恢复，无需改。

### 改动 3 — `FileSender.kt`：不动
- `:160` arm 三参调用签名保持；`:224-229` q1.j P3 覆盖、`:260 clear()` 全部不动。

### 值来源 / 字段 / 判据速查表
| 项 | 值来源 | 取法锚点 |
|---|---|---|
| 取根 | inject 读 `task.field_fullpath` 正则 `/MicroMsg/<32hex>/` | Q2 ①；`_r86c_q4b_fullpath_raw.txt:4` 根逐字 |
| `field_fullpath` 写入值 | 枚举命中的 `.jpg_hevc` 绝对路径 | 1d(4)；A 态 `image2/88/da/88da…jpg_hevc`（45522B） |
| 内容准入参照 refMd5 | g9.c map `.msg.img.$md5`（arm 预取随 pending） | D3 Q3 :184；失败验收未报 `no contentMd5` ⇒ 可读 |
| 准入判据 | `exists && length>0 && 内容MD5前8==refPre`，命中数==1 | 1d；禁 P3AdmitGate |
| fileId/aesKey | 现成 map 读取（字段对齐，非决定量） | `ForwardCdnFieldHook.kt:159-161` |

### 闸门隔离（沿用现成，零新增状态）
- `enabled` 默认 false（:47）；非本次转发不碰 task。
- 一次性 pending（:118 注入前即清）+ TTL 2000ms（:36/:110-116）+ 失败 `clear()`（`FileSender.kt:260`）。
- 普通发图 `forwardCtx==null ⇒ 不 arm ⇒ :85 if(!enabled)return ⇒ 逐字节不变`。
- 多目标每目标独立 arm（:160）；枚举/file IO 只在 pending 存在时跑一次（一次性消费），不进无闸热路径——这是相对 `landing_spec:77`「写点内现拼拖慢热路径」关切的消解：解析被 enabled+pending 双重门控，且只跑一次、有界。

### 失败回退（照写）
| 失败点 | 行为 |
|---|---|
| talker/msgSvrId/imgLocalId 无效 | `skip`，不 arm，保持现状 |
| r0.u 空 / g9.c null / 无 fileId / 无 refMd5 | `skip`，不 arm |
| inject 处 `field_fullpath` 空或取根正则不命中 | `injected origin=unavailable`，**不写任何字段**，保持现状模糊 |
| 枚举 0 命中（字节不可获得）/ >1（歧义） | 同上，不硬凑、不取第一个 |
| `setObjectField` 任一失败 | runCatching 吞掉、只告警，不影响其余字段与宿主 |
| TTL 超时（>2000ms 未命中 m1.e） | pending 清、enabled 关，不写 task（:110-116） |
| 性能顾虑（热路径枚举） | 有界（两级分桶+`.jpg_hevc`）+ 一次性；如需可加扫描计数上限超出即 null 安全降级（可选加固，非必需） |

`[DONE Q3]`

## Q4 判据 + go/no-go

**结论（一句话）**：**机制层 go**——Q2 唯一选型（inject 读 task.fullpath 取根）dex 可达 + 同点同字段真机实证，Q1 根因 dex 坐实，闸门/判据现成；**结果层「已清晰」必须由真机肉眼 A→B→A 翻转终审，不预判**。判据一律对肉眼清晰/模糊；`hit`/`UploadHitCacheType` 两轮映射翻转（`final_verdict:78/101`）**绝不作判据**。"已清晰"在未翻转前一律写「机制可行/待验」。

### 落码后真机验收最小步（肉眼终审）
1. **落码前只读取证（前置，避免白落码）**：不开闸，用现有 SCD 只读探针/文件系统确认模块转发设备上存在 `image2/<2>/<2>/<32hex>.jpg_hevc` 且内容 MD5 前 8 == `cff4f5a5`（A 态原图字节，45522B 级）；并确认 inject 处 `task.field_fullpath` 非空含 `/MicroMsg/<hash>/`（失败验收 `:5` 已证）。任一项拿不到 ⇒ 按互斥表「字节不可获得」no-go，不落码生效、不硬凑。
2. **基线**：同图 A 原生转发（清晰，fmd5pre=cff4f5a5/45522B）、B 模块转发（模糊，0ef205c1/9554B），站住事实（`forensics_raw:19-20`）。
3. **开闸落码**：装含本规格 APK（单文件改动）；模块转发 → arm 装 pending（fileId/aesKey/refMd5）+ 开闸 → m1.e before inject 从 task.fullpath 取根、枚举定位原图字节、写 `field_fullpath`。
4. **机制旁证（非结论）**：
   - **新日志信号**（Q3 1e：`armed originTail` 已取消）：看 `CDN_FIELD armed fileIdLen=.. refMd5Pre=cff4f5a5` + `CDN_FIELD injected ... origin=true originTail=<16>`；若打 `injected origin=unavailable reason=no-origin-bytes` ⇒ 解析未命中（对应互斥表"仍模糊-but origin 没解析到"）。
   - SCD-c2c 读写入 fullpath 的 `fbytes≈45522 fmd5pre=cff4f5a5`；x0cb 出口 `field_filemd5` 前 8 == cff4f5a5（`stepAB:10/12` 口径：fmd5pre=回调md5=出口filemd5前8）；`fidPre=305f` 仅旁证。
5. **肉眼终审（唯一 go 判据）**：对端肉眼清晰——跑 **A(清晰)→B(模糊)→开闸 B(应翻清晰)→A(清晰)** 翻转，开闸 B 翻清晰才坐实。计数器只记录不裁决。

### 互斥判据表（清晰→go / 仍模糊-but origin 没解析到 / 仍模糊-but 字节对了回包错 / 崩溃→回退）
| 观测（肉眼最高，字节/MD5 辅助） | 日志/机制旁证 | 裁决 | 下一步 |
|---|---|---|---|
| **肉眼清晰 + A→B→A 翻转成立**（清晰→模糊→开闸清晰→清晰） | `injected origin=true`；写入 fullpath `fbytes≈45522 fmd5pre=cff4f5a5`；出口 `filemd5=cff4f5a5` | **go 坐实，修复成立** | 固化 m1.e before 取根+枚举写点 + 闸门；SCD 探针回归只打前缀 |
| 肉眼清晰但旁证矛盾（如 origin 日志缺失但图清晰） | 以肉眼为准 | **go（终审压倒旁证）**，记异常待查 | 补一轮取数确认日志信号，不改判 |
| **仍模糊，且 `injected origin=unavailable` / 无 origin 日志 / 写入 fullpath `fmd5pre=0ef205c1`、fbytes≈9554** | arm skip / 取根正则不命中 / 枚举 0 命中或>1 / pending 未命中 / TTL / install | **接入失败或字节不可获得（非方案证伪）** | 查 skip 原因、TTL、install、注入 fullpath 是否命中；前置补确认 cff4f5a5 文件；修后重测 |
| **仍模糊，但 `injected origin=true` 且写入 fullpath `fmd5pre=cff4f5a5`、出口 `filemd5=0ef205c1`** | 字节对了但回包认另图 | **机制未闭环（native hitCheck 未认原图）** | 同刻 stock 对照；核对 fileId 对齐（305f）是否进 forwardFileid；仍失败如实 no-go，不硬推 |
| **前置 1 失败**：本地无 `MD5=cff4f5a5` 原图字节文件（枚举 0 命中） | 解析器拼路径不存在/MD5 不符 | **go 前置不满足 → no-go（字节不可获得）** | 补「原图字节下载/落盘」能力（另案），不得空占位/缩略图硬凑 |
| **微信崩溃 / 发送失败 / 双发消息** | hook 抛异常 / `l1.a()=false` | **no-go，立即回退** | 恢复 HookEntry install 注释态；`FileSender.kt:260 clear()` 清 pending 防串味 |

### 判据纪律（对着肉眼，不对计数器）
- 唯一坐实 = **肉眼清晰 + A→B→A 翻转**；`fmd5pre`/`filemd5=cff4f5a5` 与 `injected origin=true` 是机制旁证（证明字节写对），非终审。
- `hit`(1↔3)、`UploadHitCacheType`(1↔3) 两轮映射翻转，**不得**作清晰/模糊判据或"机制通"证据。
- "字节写对但肉眼仍模糊" ≠ 已证伪方案，按表区分接入失败 / 机制未闭环 / 字节不可获得三种，如实处置。
- 禁把"机制可行"写成"已清晰"；结果级 go 只由真机肉眼翻转签发。

### go/no-go 收口（含 dex 可达性判定）
- **dex 可达性层面：go**。Q2 唯一选型（inject 读 `param.args[0].field_fullpath` 取根）=【dex 实证·高：task 参数落 v6、`field_fullpath` 字段存在于 `tj/k`、SCD-c2c 同点同字段真机可读】+【真机实证·高：02:50:38.656 同点读到完整根】；Q1 根因 dex 坐实；枚举/准入工具函数现成复用。不存在"dex 不可达 ⇒ no-go"情形，**无需补 dex 观测**。
- **唯一真机前置**：模块转发设备上存在内容 MD5=cff4f5a5 的原图字节文件（枚举可定位且命中数==1）。满足 ⇒ 落码 go；不满足 ⇒ no-go（字节不可获得，不硬凑）。
- **结果级**：未做 A→B→A 翻转前，状态 = 机制可行/待验，禁写"已清晰"。

`[DONE Q4]`

## 三件套

**① `no localPath` 根因是否收口（取根来源唯一选型）？**
**已收口。** 根因【dex 实证·高】：`o8=d3.O8()` 返回 `b1`，`o2(Long)` 返回 `r0`（ImgInfo2），`r0` 的 7 个 String 字段全是 DB cursor 列（CDN URL/xml 内容），无一是 `/MicroMsg/` 绝对路径；真正的 fullpath 由 `j2.O → b1.z3(r0.e/f/g/j)` 现场拼（VFS `qi2/a.b()`→`h7.b()`→`w6.b()`），从不存进 r0 字段 ⇒ `ForwardCdnFieldHook.kt:177-181` 遍历必然 null。不是 imgLocalId 传错（同 id 的 m.a 链能拼出 124 长度 fullpath）。**取根唯一选型 = inject(m1.e before) 读 `param.args[0].field_fullpath` 正则取 `/MicroMsg/<32hex>/` 根**——唯一满足"inject 才有 task、arm 没有"时点事实，同点同字段已被 SCD-c2c 真机实证可读（02:50:38.656）、完整根逐字可得；arm 取根（VFS 抽象/已证伪 z3/硬编码）与从内容 md5 拼路径全部否决。

**② 落码 go/no-go 一句话？**
**机制层 go（Q1 根因 dex 坐实 + Q2 唯一选型 dex 可达且同点同字段真机实证 + 闸门/判据现成）；结果级"已清晰"必须由真机肉眼 A→B→A 翻转坐实、不预判；唯一真机前置 = 本地存在内容 MD5=cff4f5a5 的原图字节文件（枚举命中数==1），满足则落码 go，不满足则 no-go（字节不可获得，不硬凑）。**

**③ Flash lane 最小改动清单 + 真机肉眼判据？**
**改动清单（仅 1 文件）**：`ForwardCdnFieldHook.kt` — (a) `Pending`(:54-59) 把 `originPath` 换成 `refMd5`；(b) `arm()`(:142-196) 删除 :173-186 的 `img/o2/localPath/resolveOriginFullpath` 前置，保留 g9.c map 的 `.msg.img.$md5` 存入 pending，日志改 `armed refMd5Pre`（不再打 originTail）；(c) `inject()`(:108-133) 在拿到 task 后读 `task.field_fullpath` 取根、调改签名的 `resolveOriginFullpath(curFullpath, refMd5)`（有限枚举两级分桶 `.jpg_hevc` + 内容 MD5 前 8 准入 + 命中数==1），命中才写 `field_fullpath`（与 fileId/aesKey 同 hook 同次消费），未命中不写任何字段；(d) 日志契约改为 `injected origin=true originTail=<16>` / `injected origin=unavailable reason=no-origin-bytes`。`HookEntry.kt`/`FileSender.kt` 不动。**准入禁 P3AdmitGate**（64KB 会拒 45522B）。**判据**：落码前只读确认 cff4f5a5 原图字节存在 + inject 处 fullpath 含根 → 基线 A 清晰/B 模糊 → 开闸模块转发 → 看 `injected origin=true` + 写入 fullpath 的 fmd5pre 与出口 filemd5 翻回 cff4f5a5（旁证）→ **肉眼 A→B→A 翻转为唯一 go 坐实**；仍模糊-but origin 没解析到 / 仍模糊-but 字节对了回包错 / 字节不可获得 / 崩溃按 Q4 互斥表处置；`hit`/`UploadHitCacheType` 绝不作判据。

**取根来源唯一选型**：**inject(m1.e before) 读 `param.args[0]`(`tj/k`) 的 `field_fullpath` 取 `/MicroMsg/<hash>/` 根**；解析随之从 arm 移到 inject，`Pending.originPath` 取消、改带 `refMd5`。

[DONE ALL]

---

## ⛔ 主会话独立复核附录（2026-11-24 02:5x，落码前拦截）

主会话按硬规则独立核验，抓出**一处致命逻辑断裂**，落码前必须收口，否则白落码：

### 断裂：refMd5 准入参照 ≠ 清晰态文件字节内容 MD5

- 产物 Q2/Q3 的枚举准入 = `md5PrefixOfFile(f) == refPre`，`refPre = md5PrefixOf(refMd5)`，`refMd5 = arm 从 map[".msg.img.$md5"] 取`。
- 产物 Q3 日志契约（:143/:188）直接写 `refMd5Pre=cff4f5a5`——**但无任何证据证明 xml md5 = cff4f5a5**。
- 真机铁证相反：
  - `docs/_r86c_q4_forensics_raw.md:43`：B-1 模块自建 task `md5Pre=ca47f83e`，标注"源图 md5"，其来源 = `B6CheckExistProbe.MAP_KEYS` 的 `.msg.img.$md5`（:53/:227）。
  - `docs/_r86c_q4b_forensics_raw.md:19` + `_r86c_q4b_stepA_raw.txt:4`：A 清晰态 fullpath 文件真实字节 45522B、**内容 MD5 = cff4f5a5**（SCD fmd5pre 实测）。
  - **ca47f83e ≠ cff4f5a5**：xml 记录的"源图 md5"与实际落盘的 hevc 原图字节内容 MD5 是两个不同的量（一个可能是下载前源 md5，一个是本地 hevc 转码字节 md5）。
- ⇒ 若按产物原样落码，枚举用 `refPre=ca47f83e` 去找内容 MD5=`cff4f5a5` 的文件 → **0 命中 → 永远 `injected origin=unavailable` → 保持模糊 → 修复无效**。产物把两个 md5 混为一谈，是致命错误。

### 另两处需 Pro 一并收口的点
1. **refMd5 到底该取什么**：若目标是「定位内容 MD5=cff4f5a5 的 hevc 文件」，参照 md5 不能是 xml `.msg.img.$md5`（ca47f83e）。可选：①从 fileId(305f) body 里取（`0f836cfd`/`d1e5e773`）、②从 A 态同样可读的某个字段取 cff4f5a5、③不靠 md5 改靠体积+分桶+唯一性、④承认本地根本拿不到 cff4f5a5 而改走别的准入。需 dex + 真机联合定论。
2. **"字节不可获得"前置其实未验**：产物 Q4 前置要求本地有 cff4f5a5 文件——A 态实测确有（45522B），但那是 A 原生转发时微信**当场**拉/生成的 hevc 文件；模块转发同一时刻该文件是否仍在、路径是否同（88da…ff.jpg_hevc），未经独立确认。落码前需只读取证。

### 主会话结论
**落码暂缓**：Q2 取根选型（inject 读 task.fullpath）dex 可达、真机可读，成立；但 Q3 准入参照 refMd5 的取值存在致命错误（ca47f83e vs cff4f5a5 混用），必须 Pro 收口后才能 Flash 落码。已登记台账，另派 Pro 针对「refMd5 取值 + 字节可获得性前置」收口。
