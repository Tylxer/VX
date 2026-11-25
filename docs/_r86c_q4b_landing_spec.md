# R86c Q4b 落码规格收口裁决（Pro 显式档 402 · 别名路由）

> 性质：Hook 链路设计 + 接入点选型 + 原图字节路径解析 + 落码规格判据（**不落码**）。
> 收口对象：前轮 `_r86c_q4b_final_verdict.md` 末尾主会话独立复核附录抓出的两处落码阻断风险。
> 纪律：三档标注（【dex 实证】/【真机实证】/【推断·高|中|低】/【UNKNOWN 未闭合】），偏移自算核准，判据对肉眼清晰/模糊，只读不落码。
> 状态：**进行中（分步落盘，防父 turn 打断）**。

## 两处风险描述（收口目标）
- **风险①（写点全新 hook 点）**：项目当前无任何 `ok0/x0.u` hook。现有 `ForwardCdnFieldHook` 挂 `ok0/m1.e before`，其 `pending` 由 m1.e 消费，`FileSender.kt:160` 每目标调 `arm()`。裁决 Q4 写「x0.u before 复用 ForwardCdnFieldHook 的一次性 pending+TTL」表述不准——pending 消费逻辑在 m1.e hook 内，x0.u 没有。需定 `field_fullpath` 唯一写点。
- **风险②（原图字节路径解析）**：实测两态 fullpath 均 `image2` 分桶 + `.jpg_hevc` 后缀（A=45522B/cff4f5a5，B=9554B/0ef205c1），而 `p3OriginFromMd5`（`ForceForward.kt:2514-2556`）拼 `.jpg` 且过 `P3AdmitGate` 64KB 门槛（45522B below-floor、`.jpg_hevc` bad-shape）；`chosenP3Src` 又把 `p3SrcLocalPath`（缩略图）设为第一优先致 p3OriginFromMd5 轮不到。需定原图字节路径唯一拿法。

## 输入清单（按序，只读）
1. `docs/_r86c_q4b_final_verdict.md`（裁决全文 + 末尾主会话独立复核附录两处风险）
2. `docs/_r86c_q4b_forensics_raw.md`（补观测事实；raw txt 另寻）
3. `docs/_r86c_d3_dex_forensics.md`（x0.u setFilePath/field 映射、m1.m 分派、native acc 不可挂）
4. `wxgroup/app/src/main/java/com/lx/wxgroup/core/ForwardCdnFieldHook.kt`（全文 :29-170）
5. `wxgroup/app/src/main/java/com/lx/wxgroup/core/FileSender.kt`（:134-165/:180-235/:252-260）
6. `wxgroup/app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt`（:2360-2410/:2514-2560/:2696-2765）
7. `wxgroup/app/src/main/java/com/lx/wxgroup/HookEntry.kt`（hook 安装接线）
8. `docs/_r81_r80a_falsified_verdict.md` / `docs/_r82_b2_netscene_forensics.md`（历史反证）

---

## Q1（写点唯一选型）— 已收口
**结论（唯一推荐）：选 B（复用 `ok0/m1.e before`，在 `ForwardCdnFieldHook.inject` 内加写 `field_fullpath`），不选 A（新增 `x0.u before` hook）。** 写点与 pending 消费点统一在 m1.e，最小改动面、可达性已证、闸门现成复用；且 `j2.O` 写 fullpath 在 m1.e **之前**，不会覆盖 m1.e before 的注入。裁决 Q4「x0.u before 复用 pending」的表述据此修正为「m1.e before 写 fullpath，与 fileId 注入同一 hook 同一次消费」。

### 三候选验证（含唯一选型）

**A · 新增 `ok0/x0.u before` hook ——【dex 实证】可达但否决（非最小面）**
- access flags / 签名自跑核准（本轮亲跑 `_r35_adnd_dex.py calln ok0/x0 u`）：`ok0/x0.u` 落在 `V` 列表（virtual methods），宣告 `V Lok0/x0;->u(Ltj/k;)I`，classes13.dex，`code_off=0x3a98cc`，`regs=7 ins=2 outs=3`。即 public/protected 虚方法（非 private/static/final），`XposedHelpers.findAndHookMethod("ok0.x0", loader, "u", kCls)` 可挂【dex 实证·高】。
- 字段可达性：`param.args[0]`=task(v6)（D3 Q2 2.1 已证 ins=2→v6），`i=0x0009 @0x3a98ee iget field_fullpath → i=0x000b @0x3a98f2 setFilePath`，本轮 self-run bodyx 逐条复核一致【dex 实证·高】。
- **否决理由**：① 项目当前零 `x0.u` hook（主会话 grep `x0`+`"u"` 零命中，输入事实）；新增需 findClass("ok0.x0")+findMethodExact+install 全套 + **独立 pending 消费逻辑**（现有 pending 消费在 m1.e hook 内，x0.u 内没有——主会话风险①原话）。② 改动面更大、且需重新接线 install。③ 真机"x0.u hook 是否稳定命中"未经本项目任何先例验证【UNKNOWN】。作为"离 setFilePath 最近"的点理论更优，但不是最小改动面，且要重建闸门，不满足收口目标。

**B · 复用 `ok0/m1.e before`（ForwardCdnFieldHook）——【dex 实证 + 真机实证】唯一推荐**
- 现成全套：`ForwardCdnFieldHook.install`（:57-91）挂 `ok0/m1.e(Ltj/k;)Z`，`inject`（:97-116）一次性消费 pending 写 `field_fileId`/`field_aesKey`，`arm`（:125-158）由 `FileSender.kt:160` 每目标调用装 pending，`clear`（:161-164）失败清理。`TTL_MS=2000`（:34）。
- **可达性已真机证**：R80(a) 阶段 m1.e before 注入曾真机生效（`ok=11 fail=0` 探针基线；现 R86 B2-a 取证包因防污染主动摘 install，`HookEntry.kt:137-142` 注释掉，非"hook 不可达"）【真机实证·中-高】。x0.u 无此先例。
- **j2.O 覆盖问题（裁决原标"未核"）→ 本轮闭合**：本轮 `calln ok0/m1 e` 实证 `com.tencent.mm.modelimage.j2.O(Lcom/tencent/mm/modelimage/r0;I)Z`（classes7）**调用** `ok0/m1.e`(Ltj/k;)Z；`bodyx j2 O` 见 j2.O 内 `i=0x0124/0x013f/0x01f8 iput-object v4,v15,Ltj/k;->field_fullpath`（task 构造期写 fullpath），`i=0x009a new-instance Ltj/k`。即 j2.O **先** new task + 写 fullpath（来自 z3(r0.e)，`i=0x0076/0x0080/0x008a/0x0094 invoke b1.z3`），**后**调 m1.e 下发。故 m1.e before 的注入发生在 j2.O 写 fullpath **之后**——不存在"j2.O 在 m1.e 之后覆盖"【dex 实证·高，未核项闭合】。m1.m 只读 fullpath 打日志（D3:44-47），x0.u 只读不回写（bodyx 零 filed fullpath iput）⇒ m1.e before 写入的值一路保留到 setFilePath。
- 即使 j2.O 晚写（反事实），m1.e before 与 x0.u before 都同处"j2.O 写 → m1.e → m1.m → x0.u"链的下游，覆盖风险对 A/B 相同；无理由为规避覆盖而选 A。

### 闸门隔离（沿用现成，零新增状态）
- `ForwardCdnFieldHook.enabled` 默认 false（:45），非本次转发不碰 task；pending 一次性消费（:107-108 注入前即清），TTL 2000ms（:99-105）；发送失败 `FileSender.kt:260 clear()`。普通发图 `forwardCtx==null` ⇒ 不 arm ⇒ m1.e before 首行 `if(!enabled)return`（:74）⇒ 逐字节不变。
- 多目标：`FileSender.kt:160` 每目标 arm 一次、每目标独立 pending，串味防护已内建。

### 唯一选型小结
| 维度 | A 新增 x0.u before | B 复用 m1.e before（推荐） |
|---|---|---|
| dex 可达 | ✅ virtual 可挂【实证】 | ✅ 已挂范式【实证】 |
| 真机 hook 先例 | 无【UNKNOWN】 | 有（R80a ok=11）【实证】 |
| 改动面 | 新 hook+findClass+install+独立 pending | 仅 `inject` 加 1 行 setObjectField + arm 加 1 路径参数 |
| pending/TTL | 需新建 | 现成复用 |
| j2.O 覆盖 | 不覆盖 | 不覆盖（j2.O 在 m1.e 前写） |
| 闸门隔离 | 需重建 | 现成 |

`[DONE Q1]`

## Q2（路径解析唯一选型）— 已收口
**结论（唯一推荐）：选②（新增独立「上传 fullpath 原图字节解析器」，不过 `P3AdmitGate`，以 `File.length()>0 && 内容MD5前8 == 源图md5前8` 准入，从 image2 分桶按 img 里抠出的 md5 拼 `.jpg_hevc`），并把解析放在 `arm()` 阶段（FileSender.send 前、仅本次目标调用），结果随 pending 传到 m1.e before 写点。①（改 chosenP3Src 优先级）与 ③（写点 hook 内现拼）否决。**

### 先厘清两个 md5（Q2 的关键，不可混用）
- **路径拼装 md5（=文件名 md5 / 微信 img 逻辑 md5）**：A 态完整路径 `image2/88/da/88da961e8032ad9d6d2f6e95a40596ff.jpg_hevc` 中的 `88da961e…`，分桶 `88/da` 即取它前 4 位；B 态 `dbfc01cd…`。来源 = ImgInfo2 的字符串字段（沿用 `p3OriginFromMd5` 的 `nameRe` 抠 32 位 hex 范式，`ForceForward.kt:2522-2539`）【真机实证：完整路径为输入事实；来源字段归属【UNKNOWN 未闭合】，未直接 hook 该 img 字段】。
- **内容准入 md5（=源图字节 MD5）**：A 态 `cff4f5a5`（前 8；全长 `cff4f5a52813d02be816367d4c58a8be`，forensics_raw:19/23 + final_verdict:56）。来源 = 消息 xml `<img md5>` = g9.c map `.msg.img.$md5`（D3 Q3 :184 `i=0x014e` 实证 B6 把它写进 filemd5）。arm 现成链 `d3.O8().a3→r0.u→g9.c(u,"msg","")`（`ForwardCdnFieldHook.kt:132-141`）加一个 key 即可取【dex 实证·高 + 真机实证（A 态出口 filemd5=cff4f5a5）·高】。
- **⚠️ 两者不同**（88da… ≠ cff4f5a5）。故"按源图 md5 拼路径"的 md5 与"内容 MD5 准入"的 md5 不是同一个；规格里必须分开取，否则拼错路径或准入永不命中。**决定量是内容 MD5（cff4f5a5）；文件名 md5 只用于定位文件。**

### 三候选评估（源码/ dex 可达性 + 是否动已闭环链 + 风险）
**① 改 `chosenP3Src` 优先级让 `p3OriginFromMd5` 先跑 + 适配 `.jpg_hevc` + 内容 MD5 准入 —— 否决（动已闭环链且被双层门槛卡死）**
- 源码可达：`chosenP3Src = p3ReadyPath ?: p3BigImgSrc`（:2735），`p3BigImgSrc = p3SrcOf(img) ?: p3OriginFromMd5(...) ?: …`（:2400-2403）；下游 `copyP3Temp(chosenP3Src)`（:2740）→ `P3AdmitGate.admit(f,false)`（:2574，失败 skip）→ 写 `bigImgPath` 传 `FileSender.send`（:2741）→ `FileSender.kt:224-229` 再过 `exists && length>15*1024 && !th_` → 覆盖 q1.j → j2.O z3 再解析 → field_fullpath。
- **否决理由**：a) 45522B < `P3AdmitGate.FLOOR=65536`（:108/:124）→ `below-floor` 必拒，且 `.jpg_hevc` 不匹配 `IMAGE2_RE`（:114-115 仅 jpg/jpeg/png/webp）→ `bad-shape`；要让它先跑必须下调 FLOOR 或改 Gate —— **回退 R85 止血**（主会话附录确认 FLOOR 属实），越红线。b) 即便放行，下游 `copyP3Temp` 再过 Gate（:2595）、`FileSender` 再过 >15KB，`j2.O` 再 `z3` 解析，链路最长、覆盖点最多。c) 改的是已闭环 P3 落盘链，违反"q1.j 三级覆盖不动"（final_verdict Q4 表格）。

**② 新增独立解析器（不过 Gate，内容 MD5 准入，image2 分桶拼 `.jpg_hevc`）—— 唯一推荐**
- 源码可达：新写 `resolveOriginFullpath(img, localPath, contentMd5)`，范式直接借鉴 `p3OriginFromMd5`（:2514-2557，它已实现"从 img 抠 md5 + 从 localPath 取 `MicroMsg/<hash>` 根 + 拼 image2 分桶"），改三处：后缀 `.jpg`（:2540/:2542）改 `.jpg_hevc`；准入 `P3AdmitGate.admit`（:2545）改 `File.length()>0 && md5前8==contentMd5前8`；去掉 strictShape magic 检查。**纯只读**（File/MD5），不写宿主对象/DB。
- 不动已闭环链：不碰 P3AdmitGate / chosenP3Src / copyP3Temp / q1.j 三级覆盖 / j2.O；作用域仅本次模块转发 task 的 `field_fullpath`。符合 final_verdict Q4 修正 A「用独立解析器 + MD5 准入，不下调全局 FLOOR」。
- 内容 MD5 准入与决定量（Q2① of final_verdict）严格一致，且规避了"36B 占位/64KB 门槛"两个历史坑。
- **兜底（照写即可）**：主路径按 img md5 拼 `.jpg_hevc` 命中即返回；若 img 抠出的文件名 md5 与磁盘 `.jpg_hevc` 文件名不符（【UNKNOWN 未闭合】：文件名 md5 来源字段未直接核），回退在 `localPath` 所在 `MicroMsg/<hash>/image2/` 下**有限枚举** `*.jpg_hevc`（只扫 2 层分桶、按内容 MD5 命中即停，命中数=1 才采信，>1 视为歧义返回 null 安全降级）。绝不用空占位/缩略图硬凑。

**③ 在写点（m1.e before / x0.u before）内直接用已 arm 源图信息现拼 —— 部分否决（解析位置错）**
- 可达性：`arm()` 时已持 `ctx.sourceChat/msgSvrId/imgLocalId`（`FileSender.kt:158-165`），源图信息在手，理论可拼。
- **否决理由**：m1.e before 是**全局图片上传下发热路径**（所有图片发送必经，`ForwardCdnFieldHook.kt:25-26` 注释），把"文件 IO + 全文件 MD5 计算（45522B）"塞进该回调会拖慢宿主、且异常面扩大；正确分层 = 解析在 `arm()` 阶段做（仅本次模块转发目标调用、本就只读反射），把结果路径随 pending 带到写点。**"写点用已 arm 信息"这一点采纳（并入②的实现），但"写点内现拼"否决**。

### 唯一规格要点（Flash lane 照写）
- 解析器 `resolveOriginFullpath` 在 `ForwardCdnFieldHook.arm()` 内调用（`arm` 现成拿 r0.u/g9.c map，再加取 `.msg.img.$md5` + img 的 `d3.O8().o2(imgLocalId)`）；产出路径存进 `Pending新增字段 originPath: String?`。
- 准入：`File.exists() && File.length() > 0 && contentMd5Prefix(file, 4) == contentMd5Prefix(xmlMd5, 4)`；**明确不用 P3AdmitGate**（64KB 门槛会 below-floor 拒 45522B）。
- 解析失败（路径不存在 / MD5 不符 / 歧义）→ 返回 null → `arm` skip（清 pending 关闸门）→ 本次转发整条不注入，保持现状（模糊），**不硬凑**。这与 final_verdict Q4 修正 B「前置拿不到即 no-go」一致，但顺序提前到 arm（落码时即执行），避免"落码后才发现拿不到"。

`[DONE Q2]`

## Q3（完整落码规格，Q1=B + Q2=② 拼接）— 已收口
> 目标：Flash lane 照本节写即可。三处文件改动 + 一个新增解析器，**不碰** P3AdmitGate / chosenP3Src / copyP3Temp / q1.j 三级覆盖 / j2.O / x0.u / fileId 注入以外的任何字段。

### 改动 1 — `ForwardCdnFieldHook.kt`（核心）
**1a. `Pending` 数据类（:48）加一个字段**：
```kotlin
private data class Pending(val fileId: String, val aesKey: String,
                           val originPath: String?, val ts: Long)   // 新增 originPath
```
**1b. `arm()`（:125-158）三段式，在现成 r0.u/g9.c map 之后加取源图 md5 + 解析原图路径**：
- 复用现成（:132-144）：`o8=d3.O8()` → `r0=o8.a3(talker,msgSvrId)` → `u=r0.u` → `map=g9.c(u,"msg","")` → `fileId`(cdnbigimgurl?:cdnmidimgurl)、`aesKey`。
- **新增源图内容 md5**：`val contentMd5 = map[".msg.img.\$md5"] as? String`（键 Dex 实证 D3 Q3 :184 `i=0x014e`；R79:170 `map[".msg.img.$md5"]`）。空则 `skip("no contentMd5")`（内容 md5 是准入参照，缺了无法校验，安全降级）。
- **新增 img 对象**：`val img = runCatching { XposedHelpers.callMethod(o8, "o2", imgLocalId) }.getOrNull()`（o8 已是 `d3.O8()` 单例，`o2(localId)` 范式见 `ForceForward.kt:2377-2379`；源 localId 即 `FileSender` 传入的 `imgLocalId`）。
- **新增 localPath 来源**：从 img 的字符串字段里取"以 `/` 开头、含 `/MicroMsg/`"的本地路径作 `localPath` 供取数据根；取不到则 `skip("no localPath")`。
- **新增解析器调用**：`val originPath = resolveOriginFullpath(img, localPath, contentMd5)`；为 null 则 `skip("origin unavailable")`——按 Q4「字节不可获得 ⇒ no-go，不硬凑」。
- **改 pending 构造（:150）**：`pending = Pending(fileId, aesKey.orEmpty(), originPath, System.currentTimeMillis())`。

**1c. 新增私有方法 `resolveOriginFullpath(img, localPath, contentMd5): String?`**（照写）：
```
1) 从 localPath 正则取根：Regex("/MicroMsg/[0-9a-fA-F]{32}/").find(localPath) → root=localPath[0..range.last]（范式 ForceForward.kt:2531-2533，不硬编码 hash）
2) 从 img 的字符串字段抠 32 位 hex（文件名 md5，定位用）：
   Regex("([0-9a-fA-F]{32})") —— 字段名 md5 可能是纯 32 位或带后缀，多重候选全部收集（范式 ForceForward.kt:2522-2529）
3) 主路径：对每个候选 md5，拼 path = root + "image2/${md5[0..1]}/${md5[2..3]}/$md5.jpg_hevc"
   （⚠️ 后缀是 .jpg_hevc，不是 p3OriginFromMd5 的 .jpg；本轮 A/B 两态实测均 .jpg_hevc）
4) 准入（唯一判据，禁 P3AdmitGate）：
   f=File(path); if (f.exists() && f.length()>0 && contentMd5Prefix(f)==contentMd5Prefix(contentMd5)) return path
   —— contentMd5Prefix = 读文件字节算 MD5 取前 4 字节 hex（口径对齐 SCD fmd5pre = MD5 前 4 字节，forensics_raw:15）。
   —— 绝不用 P3AdmitGate.admit（FLOOR=65536 会 below-floor 拒 45522B；IMAGE2_RE 不认 .jpg_hevc）。
5) 主路径全 miss ⇒ 兜底有限枚举：遍历 root+"image2/" 下两级分桶目录的 *.jpg_hevc，
   同样做 (4) 内容 MD5 校验；命中数==1 才返回该路径，==0 或 >1 一律 null（歧义不采信）。
6) runCatching 全包，任何异常 → null（安全降级）。日志只打 path.takeLast(24)/len/md5前8，不打完整 hash。
```
**1d. `inject()`（:97-116）在写 fileId/aesKey 之后加一行写 fullpath**：
```kotlin
p.originPath?.let { op ->
    runCatching { XposedHelpers.setObjectField(task, "field_fullpath", op) }
        .onFailure { LogX.w("$TAG set field_fullpath failed: ${it.javaClass.simpleName}") }
}
```
位置必须在「一次性清 pending（:107-108）」之后、「return（:109 task 空）」拿到 task 之后。`field_fullpath` 写入与 fileId/aesKey 同一 hook 同一次消费（修正裁决 Q4 表述）。

### 改动 2 — `HookEntry.kt`（恢复 install，当前被摘）
- `HookEntry.kt:141-142` 现为注释（R86 B2-a 取证包为防污染摘除，:137-140 注释说明）。**落码版恢复**：
```kotlin
runCatching { ForwardCdnFieldHook.install(lpparam) }
    .onFailure { LogX.w("ForwardCdnFieldHook install failed: ${it.javaClass.simpleName}") }
```
- 保留 `FileSender.kt:163` 的 `B6CheckExistProbe.armFromForward`（默认关，零影响），不动。
- 不新增 x0.u install（Q1 已否决 A）。

### 改动 3 — `FileSender.kt`（无需改签名，参数已够）
- `FileSender.kt:160` 现调用 `arm(ctx.sourceChat, ctx.msgSvrId, ctx.imgLocalId)` — **三参已足够**（imgLocalId 用于 `o2()` 取 img，talker/msgSvrId 用于 a3/g9.c）。**无需改 arm 签名、无需改此行**。
- `FileSender.kt:224-229` q1.j 的 P3 覆盖、**不动**；`:260 clear()` **不动**。

### 值来源 / 字段 / 判据 速查表
| 项 | 值来源 | 取法锚点 |
|---|---|---|
| `field_fullpath` 写入值 | `resolveOriginFullpath` 返回的 image2 `.jpg_hevc` 绝对路径 | Q3 1c；A 态=`/data/user/0/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37/image2/88/da/88da961e8032ad9d6d2f6e95a40596ff.jpg_hevc`（45522B） |
| 路径分桶 md5（定位用） | img（`d3.O8().o2(imgLocalId)`）字符串字段抠 32 位 hex | `ForceForward.kt:2522-2539` 范式 |
| 内容准入 md5（参照） | xml `.msg.img.$md5`（g9.c map，a3→r0.u→g9.c） | D3:184 / R79:170；A 态预期=cff4f5a5 |
| MicroMsg 数据根 | localPath 正则 `/MicroMsg/[0-9a-f]{32}/` | `ForceForward.kt:2531-2533` |
| 准入判据 | `exists && length>0 && 内容MD5前8==参照md5前8` | 禁用 P3AdmitGate（64KB/IMAGE2_RE） |

### 闸门隔离（沿用现成，零新增状态机）
- `enabled` 默认 false（ForwardCdnFieldHook:45）；非本次转发不碰 task。
- 一次性 pending（:107-108 注入前即清）+ TTL 2000ms（:34/:99-105）+ 失败 `clear()`（`FileSender.kt:260`）。
- 普通发图 `forwardCtx==null` ⇒ 不 arm ⇒ m1.e before 首行 `if(!enabled)return`（:74）⇒ 逐字节不变。多目标每目标独立 arm（:160）。
- `enableHit` 保持 true（A/B 两态本就 true，final_verdict Q4 表），不动；`filemd5` 不动（x0.u 不读）；`fileId` 仍按现成 arm 写（字段对齐，非决定量）；`onlycheckexist` 不动。

### 失败回退（照写）
| 失败点 | 行为 |
|---|---|
| talker/msgSvrId/imgLocalId 无效 | `skip`，不 arm，保持现状 |
| r0.u 空 / g9.c null / 无 fileId / 无 contentMd5 | `skip`，不 arm |
| 无 img / 无 localPath / originPath=null（字节不可获得） | `skip` ⇒ 本次不注入 ⇒ **按 Q4 互斥表「字节不可获得」no-go，不得用空占位/缩略图硬凑** |
| inject 里 setObjectField 任一字段失败 | `runCatching` 吞掉、只告警，不影响其余字段与宿主 |
| TTL 超时（>2000ms 未命中 m1.e） | pending 清、enabled 关，不写 task（:99-105） |

`[DONE Q3]`

## Q4（判据 + go/no-go）— 已收口
**结论：机制层 go（两处风险均已收口：写点唯一=B/m1.e before、路径唯一=②独立解析器内容MD5准入），可达性 dex 实证齐全；结果层「已清晰」必须由真机肉眼 A→B→A 翻转终审，不预判。** 判据一律对肉眼清晰/模糊；`hit` / `UploadHitCacheType` 绝不作判据（两轮映射翻转，final_verdict:78/101）。

### 落码后真机验收最小步（肉眼终审）
1. **落码前只读取证（顺序前置，纠正主会话附录 :289「前置放落码后」的顺序错）**：不开闸，用现有 SCD 只读探针/文件系统确认模块转发设备上存在 `image2/../*.jpg_hevc` 且内容 MD5 前 8 == `cff4f5a5`（A 态原图字节）。拿不到 ⇒ 直接按互斥表「字节不可获得」no-go，不落码生效、不硬凑。
2. **基线**：同图 A 原生转发（清晰，fmd5pre=cff4f5a5/45522B）、B 模块转发（模糊，fmd5pre=0ef205c1/9554B），站住事实（forensics_raw:19-20）。
3. **开闸落码**：装含本规格 APK；模块转发 → `arm` 装 pending（含 originPath + fileId 对齐）→ m1.e before 写 `field_fullpath`=原图字节路径。
4. **机制旁证（非结论）**：SCD 读写入 fullpath 的 `fbytes≈45522 fmd5pre=cff4f5a5`、`enableHit=true`；x0cb 出口 `field_filemd5` 前 8 == cff4f5a5（fmd5pre=回调md5=出口filemd5 前 8，forensics_raw:30）。`fidPre=305f` 仅旁证。
5. **肉眼终审（唯一 go 判据）**：对端肉眼清晰 —— 跑 **A(清晰)→B(模糊)→开闸 B(应翻清晰)→A(清晰)** 翻转，开闸 B 翻清晰才坐实。计数器只记录不裁决。

### 互斥判据表
| 观测（肉眼最高） | 机制旁证 | 裁决 | 下一步 |
|---|---|---|---|
| 肉眼清晰 + A→B→A 翻转成立 | 写入 fullpath `fmd5pre=cff4f5a5`、出口 filemd5=cff4f5a5 | **go 坐实，两风险收口成立** | 固化 m1.e before 写点 + originPath 闸门；SCD 探针回归只打前缀 |
| 肉眼清晰但旁证矛盾 | 以肉眼为准 | **go（终审压倒旁证）** | 补一轮取数，不改判 |
| 仍模糊，写入 fullpath `fmd5pre=0ef205c1`/`fbytes≈9554`（字节没换） | pending 未命中 / originPath 落空 / arm skip | **接入失败（非方案证伪）** | 查 arm skip 原因、TTL、install 是否恢复（HookEntry:141）；修后重测 |
| 仍模糊，写入 fullpath `fmd5pre=cff4f5a5` 但出口 filemd5=0ef205c1 | 字节对了但回包认另图 | **机制未闭环（native hitCheck 未认原图）** | 同刻 stock 对照；核对 fileId 对齐是否进 forwardFileid；仍失败如实 no-go |
| 落码前只读确认本地无 cff4f5a5 原图字节文件 | 解析器拼路径不存在/MD5 不符 | **go 前置不满足 ⇒ no-go（字节不可获得）** | 补「原图字节下载/落盘」另案，不得硬凑 |
| 微信崩溃 / 发送失败 / 双发 | hook 抛异常 / l1.a()=false | **no-go，立即回退** | 恢复 HookEntry install 注释态；`FileSender.kt:260 clear()` 清 pending 防串味 |

### go/no-go 一句话
**dex 可达性层面 go（写点 B 与路径②均 dex 实证可达、闸门现成、j2.O 覆盖风险已闭合）；是否落码生效取决于真机前置「本地存在 cff4f5a5 原图字节文件」——前置满足则落码 go，结果级由肉眼 A→B→A 翻转坐实；前置不满足则 no-go（字节不可获得）。**

### 未闭合项（如实标注，不影响本次 go 方向）
- 【UNKNOWN 未闭合】本轮 raw txt（`_r86c_q4b_fullpath_raw.txt` / `_r86c_q4b_stepAB_raw.txt`）在工作区不存在（Glob 全仓仅 md），完整路径/fbytes/fmd5pre 以 forensics_raw.md + final_verdict.md 记录为准，未推翻。
- 【UNKNOWN 未闭合】img 字符串字段中"文件名 md5（88da…）"与磁盘 `.jpg_hevc` 文件名的确切映射；故 Q2/Q3 解析器带「有限枚举 + 内容 MD5 命中数==1」兜底。
- 【UNKNOWN 未闭合】xml `$md5` 字段值是否逐字 = 内容 md5 cff4f5a5（R79:172/175 警告 xml `$md5` 未必等于文件名 md5）；准入只用其前 8 作参照，且以真机 fmd5pre 复核。
- 【真机实证·中-高】m1.e before hook 可达（R80a ok=11 baseline）；当前 HookEntry 摘 install 是取证包防污染所致，非不可达（Q3 改动 2 恢复）。

`[DONE Q4]`

---

## 完整落码规格（Q1+Q2 拼接，摘要；详细逐行见 Q3）
- **写点唯一 = B**：`ok0/m1.e before` 的 `ForwardCdnFieldHook.inject()` 加写 `field_fullpath`，与 fileId/aesKey 同 hook 同一次消费；不新增 x0.u hook（A 否决：零先例 + 需重建闸门 + 更大面）。x0.u 是 virtual 可挂【dex 实证】但非最小面。
- **路径唯一 = ②**：新增 `resolveOriginFullpath`，不过 P3AdmitGate，`File.length()>0 && 内容MD5前8==xml$md5前8` 准入，image2 分桶拼 `.jpg_hevc`，解析在 arm 阶段、结果随 pending 传写点；①改优先级动已闭环链且被 64KB 门槛卡死（否决）、③写点内现拼拖慢全局热路径（否决）。
- **三处改动**：`ForwardCdnFieldHook.kt`（Pending 加 originPath / arm 加解析 / inject 加写 fullpath）、`HookEntry.kt:141-142`（恢复 install）、`FileSender.kt`（不改签名、q1.j 不动）。
- **闸门**：复用现成 enabled + 一次性 pending + TTL 2000ms + clear；普通发图逐字节不变。
- **失败回退**：originPath=null ⇒ skip 不注入 ⇒ 按 Q4「字节不可获得」no-go，不硬凑。

## 三件套
**① 两处风险是否收口（写点唯一 + 路径唯一）？**
**均已收口到可落码粒度。** 风险①：写点唯一=B（复用 m1.e before，与 pending 同址），x0.u access=virtual 可挂【dex 实证】但非最小面故否决，裁决 Q4 表述不准已修正；j2.O 在 m1.e 之前写 fullpath、不覆盖【dex 实证·高，原"未核"闭合】；pending/TTL 现成复用。风险②：路径唯一=②独立解析器，对齐实测 `.jpg_hevc`+image2 分桶+45522B+内容MD5准入,cff4f5a5，禁用 P3AdmitGate 64KB 门槛；区分"文件名 md5（定位）"与"内容 md5（准入）"两个不同源；①/③否决理由完整。

**② 落码 go/no-go 一句话？**
**dex 可达性层面 go（两风险 dex 实证可达）；前置「本地存在内容MD5=cff4f5a5 的 image2 `.jpg_hevc` 原图字节文件」满足则落码 go、结果由肉眼 A→B→A 翻转坐实，不满足则 no-go（字节不可获得，不硬凑）。**

**③ Flash lane 最小改动清单 + 真机肉眼判据？**
**改动清单（3 文件）**：(1) `ForwardCdnFieldHook.kt` — Pending(:48)加 `originPath`；`arm()`(:125-158) 在 g9.c map 后加取 `.msg.img.$md5` + `d3.O8().o2(imgLocalId)` 取 img/localPath + 调 `resolveOriginFullpath`，null 则 skip；新增解析器（image2 分桶拼 `.jpg_hevc`，`exists&&length>0&&内容MD5前8==参照前8` 准入，有限枚举兜底、命中==1 才采信，禁 P3AdmitGate）；`inject()`(:97-116) 写 `field_fullpath=p.originPath`。(2) `HookEntry.kt:141-142` 恢复 `ForwardCdnFieldHook.install(lpparam)`。(3) `FileSender.kt` 不改签名、q1.j/:260 不动。**判据**：前置只读确认 cff4f5a5 原图字节存在 → 基线 A 清晰/B 模糊 → 开闸模块转发 → 看写入 fullpath 的 fmd5pre 与出口 filemd5 前 8 是否翻回 cff4f5a5（旁证）→ **肉眼 A→B→A 翻转为唯一 go 坐实**；仍模糊/字节不可获得/崩溃按 Q4 互斥表处置；`hit`/`UploadHitCacheType` 绝不作判据。

**写点/路径唯一选型**：写点 = **B（m1.e before 复用）**；路径 = **②（独立解析器 + 内容MD5准入，arm 阶段解析）**。

[DONE ALL]
