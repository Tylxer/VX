# R86c B5 干预实验实现记录（Pro 实现 lane · r86c-b5-impl）

> 角色：`r86c-b5-impl`，Pro 实现 lane。工作根 `C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`，源码 `wxgroup/`。
> 性质 = 判据设计（field_fullpath 填值分歧裁决）+ Hook 链路改动（补字段 / 自建 checkExist 下发）+ 宿主反射 + 变异单测。
> 纪律（硬）：只改代码 / 单测 / 文档；**禁碰 adb、禁装机、禁在设备上翻开关、禁 git commit/push**。
> 模型档位：HY4-FLASH-FREE（用户临时规则，不写入任何文件）。三档标注【dex 实证】/【真机实证】/【推断·高|中|低】/【UNKNOWN 未闭合】，【推断】不写成【已证】。
> 判据纪律：只对 `field_exist_whencheck` / `field_filemd5` 身份前缀裁决；`hit` / `UploadHitCacheType` 只记录不裁决。
> 落盘策略（防打断）：先建骨架 + §1 + §2 写盘 → 改代码 → 补单测 → 三闸各自留结论 → 补齐 §3~§6 → 真 `[DONE ALL]`。**先改文件再回报，产物以落盘为准。**

状态进度：
- [x] §1 field_fullpath 填值裁决（三裁决张力 + 定夺 + 档位）
- [x] §2 B5 可达成判据纠偏（推翻肉眼翻转门槛）
- [x] §3 改动清单
- [x] §4 变异判红表
- [x] §5 三闸结果
- [x] §6 诚实边界
- [x] 真 `[DONE ALL]`

---

## §1 field_fullpath 填值裁决（三裁决张力 → Pro 定夺）

### 1.1 三裁决真实张力（逐条带档位 + 锚点）

| # | 裁决 | 对 field_fullpath 的要求 | 档位 / 锚点 |
|---|---|---|---|
| A | **Q4 fullpath verdict**（`docs/_r86c_q4_fullpath_verdict.md:87/140-146`） | 选 **Y**：指向**小占位文件（36B~124B）** + 保留 fileId(305f) + enableHit=true + fakeSig=""；认定「fileId 锚点主导引用哪张图，占位字节只是触发令牌」，明确不选 X | 【真机实证·高 + 推断·高】A/B-2 同为 124B `.jpg_hevc`，唯一硬差异 fileId 有无 → A 命中 cff4f5a5 清晰 / B-2 命中 0ef205c1 模糊（:67-70） |
| B | **Q4b final verdict**（`docs/_r86c_q4b_final_verdict.md:73-74/92/164/265`） | **修正 A**：本轮 A 态实测 fullpath = **45522B 原图字节**（fmd5pre=`cff4f5a5`）；「36B 占位本轮未复现」；决定量 = **fullpath 字节内容 MD5 = 源图身份**；「修复选型必须对齐本轮事实（原图字节），不照抄 36B 占位」 | 【真机实证·高】fmd5pre=回调md5=出口filemd5前8 三态同源（:55-58）；fileId 逐字相同仍一清晰一模糊（R81/R82）⇒ fileId 零区分力（:92/99） |
| C | **generalize verdict**（`docs/_r86c_generalize_verdict.md:24/87/164`） | hevc 字节**逐图变**（实验1 148929/`484bc088`、实验2 45522/`cff4f5a5`）；固定字节值对新图 **0 命中**；目标态/模糊态固定值判据都必须撤 | 【真机实证·高】两轮清晰态形态同为 `.jpg_hevc` 但字节全不同（:48/87）；主会话附录证 `cff4f5a5` 是 216b 单图指纹（clearness_reverdict:116/184） |

张力实质：A 说「fileId 是决定量、占位字节只管启动」；B 说「字节内容 MD5 是决定量、fileId 零区分力、不照抄占位」；C 说「任何固定字节值都不能写死」。
三者的**适用链不同**是本裁决的收口钥匙（见 1.2）。

### 1.2 Pro 定夺（唯一填值 + 档位）

**定夺 = 第三种（动态定位 + Y 安全回退），不照抄 X、也不照抄 Y 的「36B 当原图字节源」语义。**

`field_fullpath` 取值 = 独立方法 `resolveFullpath(u, map, fileId, xmlMd5)` 的返回值，优先级如下：

1. **主路径（对齐本轮事实 / B+C）**：从已取到的 `u`/`map`（`d3.O8().a3→r0.u→g9.c(u,"msg","")`，现成、不新增 hook）动态定位一个本地文件，其**内容 MD5 前 8 == 源图身份前缀（非 `0ef205c1`）**。命中即取其绝对路径写入 `field_fullpath`。**不写死任何字节数 / 内容 MD5 常量**（`45522`/`148929`/`cff4f5a5`/`484bc088`/`36`/`124` 一律不进代码）——这同时满足 B「对齐原图字节事实」与 C「不写死、换图不失效」。
2. **安全回退（对齐 A / B6 秒传链）**：主路径定位不到时，退回一个**非空小占位文件路径**（几十~百字节即可，无 JPEG magic / 64KB / 分桶要求），身份**完全由保留的 `field_fileId=305f…` 锚点决定**，占位字节只作 hitCheck 启动令牌。此处**不是**「照抄 36B 占位当原图字节源」——语义不同：B 的「不照抄占位」针对的是**真实上传链**（字节决定出口 filemd5），而 B5 是**checkExist 秒传探测链**，身份来自服务端凭 fileId 的秒传命中（R79 / b3 佐证），占位只负责让 `x0.u setFilePath` 有非空字节入口。

**档位**：主路径「内容 MD5 准入」=【真机实证·高 + dex 实证·高】（B 的 fmd5pre 链 + `x0.u i=0x0009→0x000b setFilePath` 唯一字节入口，自算核准偏移 `0x3a98ee/0x3a98f2`）；回退「fileId 锚点决定 checkExist 命中谁」=【真机实证·高】（A/B-2 同占位不同 fileId → 不同 md5，Q4 Q2）；「36B 占位语义不同、不构成照抄」=【推断·高】（两链上游分叉，direction verdict Q2-b）；定位成功率与真实字节可获得性 =【UNKNOWN 未闭合】（forward 帧 u/map 无本地路径属性，generalize Q2-b #2a/#2c；随规格携带、fail-closed）。

### 1.3 dex / 真机依据（为何这样填）

- **dex**：`ok0/x0.u(Ltj/k;)I` 的 `field_fullpath → setFilePath`（i=0x0009→0x000b）是**唯一字节入口**，`field_fileId → forwardFileid`（i=0x001b→0x001d）只是转发头锚点，`field_enable_hitcheck → enableHitCheck`（i=0x0085→0x0087）是开关；`x0.u` **全程不读 field_filemd5**（grep 零命中）【dex 实证·高，Q4b :103-116 本轮自算核准】。⇒ fullpath 必须非空才能让 hitCheck 启动（B-1 先例 `fullpathLen=0 → hit=0`），但「命中谁」在 checkExist 链由 fileId 锚点决定、在真实上传链由字节内容 MD5 决定。
- **真机（服务端未过期，排除 Q1③）**：`wxgroup/_r86c_b3_stock_raw.txt:152` 此刻 stock 仍 `md5=cff4f5a5 md5Len=32 hit=1 exist=true fidLen=194 fidPre=305f0201`，:157 出口 `field_filemd5=cff4f5a52813…`，:158 `field_exist_whencheck=true`。⇒ 服务端此刻对 fileId `305f0201` 仍命中原图，回退链有真实服务端依据。
- **真机（fileId 对 checkExist 的决定力）**：Q4 A/B-2 同为 124B `.jpg_hevc`，A 带 fileId → cff4f5a5 清晰、B-2 无 fileId → 0ef205c1 模糊【真机实证·高，Q4 :67-70】。⇒ B5 保住 fileId 是命门，绝不清空。
- **真机（不写死字节）**：generalize 两轮清晰态字节全不同【真机实证·高，:24/87】。⇒ 代码内禁用任何固定字节 / MD5 指纹常量。

### 1.4 fail-closed 边界（明确）

- `u`/`map` 取不到、`fileId` 为空、反射异常、`resolveFullpath` 主路径与回退均失败 → 只打 WARN，**不下发 checkExist**（不写半个 task），绝不自造身份常量硬凑。
- 主路径命中数必须**唯一**（==1），0 或多候选一律不写、走回退；不 `remove` 他人条目。
- 全程 runCatching 单钩守护；默认 `enabled=false` 首行 return（零反射）；C2 只读、不调 j6、不发消息、不写宿主字段；I/z 返回默认值。

`[DONE §1]`

---

## §2 B5 可达成判据纠偏（推翻「肉眼 A→B→A 翻转」门槛）

**方向裁决 `_r86c_direction_verdict.md:166` Q3-c 把「用户肉眼 A→B→A 翻转」列为 B5 通过门槛——这是错的，本实现不沿用。**

依据（只读红线，`wxgroup/app/src/main/java/com/lx/wxgroup/probe/B6CheckExistProbe.kt:18-20`）：
- Proxy C2 回调**只做**：反射读 `Ltj/f` 的 `field_exist_whencheck` / `field_filemd5` + 打日志，然后返回 `0`；
- **绝不调 `b1.j6`、绝不调 `FileSender.send`、绝不写任何宿主字段、绝不改 m.a、绝不发消息**；
- `I`/`z` 只返回默认值、不打日志。

⇒ 自建 checkExist task 与真实转发消息**无任何耦合**：C2 里即使命中也不会触发 send / 写消息 `<img md5>`，所以**肉眼翻转在 B5 这个只读探针里不可能发生**。把它列为 B5 门槛是判据错配（把「后续 B6 功能实现」的结果判据套到「机制验证干预实验」上）。

**B5 唯一可达成判据（本实现锁定）**：
> Proxy C2 **第二次（真实回包）回调** `field_exist_whencheck=true` **且** `field_filemd5` 为非 `0ef205c1` 的原图身份前缀（如 `cff4f5a5` / `ca47f83e`）。
> 辅以只读日志：`args[3].javaClass.name` 与 `Ltj/f` 全部 String 字段名 / 长度（把 b6step【基本排除】的「读错对象 / 空壳」坐到【完全排除】）。

**不属于 B5、留给后续真正 B6 功能实现的判据**：
- 出口 `filemd5` 从模糊身份翻到源图身份；
- 用户肉眼 A→B→A 翻转。
这两项是 B6 全量功能（会调 j6 / 发消息 / 写宿主字段）的验收判据，本探针只读红线决定了它做不到也不该做。

`[DONE §2]`

---

## §3 改动清单（只改这两处，先文件后回报）

### 3.1 `wxgroup/app/src/main/java/com/lx/wxgroup/probe/B6CheckExistProbe.kt`
- KDoc 改为 **R86c B5 干预实验**定位；只读红线重写：C2 只读、绝不调 j6 / 不发消息 / 不写宿主字段、I/z 返回默认值；明确「不追肉眼翻转」。
- `TASK_FIELDS` 补三字段 → 全集 15 项：`field_fullpath` / `field_enable_hitcheck` / `field_fake_bigfile_signature`。
- 新增独立方法 `resolveFullpath(u, map)`（§1 填值裁决落码，便于单测/替换）：
  - 主路径：从 `u`/`map` 用 `RE_DATA_ROOT`（`(.*/MicroMsg/[0-9a-fA-F]{32}/)`）动态截数据根，枚举唯一非空 `.jpg_hevc` 候选（`hits.size == 1`，`MAX_SCAN_FILES=1024`）；**不写死任何字节数 / 内容 MD5 常量**。
  - 回退：`File.createTempFile("b6x_", ".jpg_hevc")` 写 3B 非空占位，身份靠保留的 fileId 锚点。
  - 全失败返回 `null` → `arm` 打 WARN `no_fullpath (fail-closed)` 并 **不下发**（不写半个 task）。
- `arm()` 构造段：写 `field_fullpath=fullpath` / `field_enable_hitcheck=true` / `field_fake_bigfile_signature=""`；**保留** `field_fileId=fileId(305f)` / `field_filemd5=xmlMd5` / `field_onlycheckexist=false` 等现有字段；sent 日志加 `fpathLen` / `fpathTail`（只长度/尾缀，不打完整路径）。
- Proxy C2 加只读自检 `logC2Shape(f)`：打 `args[3].javaClass.name` + `Ltj/f` 全部 String **字段名/长度**（`declaredFields` + `?.length`，**不打值**），把 b6step【基本排除】坐到【完全排除】；仍 `return 0`，不调 j6 / 不发消息 / 不写宿主字段。
- `enabled=false` 首行 return 保持；全程 runCatching 单钩守护；install 只找类不 hook；**不碰 native 上传入口**。

### 3.2 `wxgroup/app/src/test/java/com/lx/wxgroup/probe/B6CheckExistProbeTest.kt`
- 重写为 R86c B5 回归锁：共 **20 例**（9 条契约锁 + 11 条源码变异判红），纯 JVM 零 Android/Xposed 依赖。
- 锁：三字段写入 + fileId 不清空 + `resolveFullpath` 独立方法 + 禁固定指纹常量 + C2 shape 只读自检 + Proxy 三方法 shape + C2 不调 j6/send + enabled 默认静默 + 不 hook native + 接线。
- 未改：`ForwardCdnFieldHook.kt` / `FileSender.kt` / `ForceForward.kt` / `HookEntry.kt` / `Keys.kt` / `EntryReceiver.kt`；未新增 hook 点 / 未改接线 / 未新增 Key（§1 裁决不需要）。

`[DONE §3]`

---

## §4 变异判红表（真实源码变异，确认非等价）

各变异在**源码文本上真实改动**（`mutated != src` 前置断言），断言对应锁判红（`assertFalse(validator(mutated))`）；恢复后全绿。20 例全绿即 11 个变异全部成功判红 + 9 条锁通过。

| 变异 | 真实改动 | 判红测试 | 判红对象 |
|---|---|---|---|
| M1 | 删 `put(task, "field_fullpath", fullpath)` | `mutationDeleteFullpathFieldIsDetected` | `b5FieldsValid` |
| M2 | 删 `put(task, "field_enable_hitcheck", true)` | `mutationDeleteEnableHitFieldIsDetected` | `b5FieldsValid` |
| M3 | 删 `put(task, "field_fake_bigfile_signature", "")` | `mutationDeleteFakeSigFieldIsDetected` | `b5FieldsValid` |
| M4 | `put(task, "field_fileId", fileId)` → 写空串 | `mutationClearFileIdIsDetected` | `fileIdWriteValid` |
| M5 | C2 handler 注入 `callMethod(f, "j6")` | `mutationC2CallsJ6IsDetected` | `c2ReadOnlyValid` |
| M6 | install 注入 `findAndHookMethod("startC2CUpload", …)` | `mutationNativeHookIsDetected` | `noNativeHookValid` |
| M7 | `TASK_FIELDS` 漏 `field_fullpath` | `mutationTaskFieldDroppedIsDetected` | `taskFieldsValid` |
| M8 | 写死 `REF_MD5_PRE="cff4f5a5"` | `mutationHardcodedFingerprintIsDetected` | `noHardcodedFingerprint` |
| M9 | Proxy 删 `z` 分支（`z`→`Z`） | `mutationProxyTwoMethodsIsDetected` | `listenerMethodsValid` |
| M10 | 读错字段名 `exist_whenchek` | `mutationWrongC2FieldNameIsDetected` | `c2FieldsValid` |
| M11 | 删 map 的 md5 键 | `mutationMapKeyRemovedIsDetected` | `mapKeysValid` |

判据：M1~M4 + M7 覆盖「删任一字段 / 清空 fileId」，M5 覆盖「C2 改成调 j6」，M6 覆盖「改 hook native 入口」——任务要求的四类变异**各自至少 1 条判红**，且确认是「真改动源码 + 锁确实拒绝」的非等价变异（非同义改写）。

`[DONE §4]`

---

## §5 三闸结果（cwd=`wxgroup/`）

> 前置：`_junit.sh` 用预编译 class，故先跑 `python ../tools/gradle_run.py compileDebugUnitTestKotlin` → `EXIT=0 BUILD SUCCESSFUL`（闸 1/2 的前提，非要求三闸之一）。

| 闸 | 命令 | 结果 |
|---|---|---|
| 闸 1 | `bash ../tools/_junit.sh com.lx.wxgroup.probe.B6CheckExistProbeTest` | `OK (20 tests)`，exit 0 |
| 闸 2 | `bash ../tools/_junit.sh`（全量） | `OK (1202 tests)`，exit 0 |
| 闸 3 | `python ../tools/gradle_run.py assembleDebug` | `EXIT=0` / `BUILD SUCCESSFUL in 16s` |

三闸全绿。未跑 `build`（按指令只 `assembleDebug`）；未碰 adb / 装机 / 设备开关；未 git commit/push。

`[DONE §5]`

---

## §6 诚实边界（不预判、不夸大）

- **泛化边界**：代码内禁写任何固定字节数 / 内容 MD5 常量（M8 锁死），主路径按「唯一 hevc 候选」相对信号动态定位，换图后出现 0 或多候选即回退小占位。B5 只需在选定样本图上让 C2 命中，**不承诺对新图泛化**；换图泛化仍受 generalize NO-GO(0.75) 约束。
- **真实字节可获得性 =【UNKNOWN 未闭合】**：forward 帧 `u`/`map` 无本地路径属性（generalize Q2-b #2a/#2c），`resolveFullpath` 主路径依赖 `u`/`map` 中出现 `/MicroMsg/<32hex>/` 绝对路径；真机是否命中主路径未测（本任务禁真机），拿不到即 fail-closed 回退并以 C2 日志如实记录，不用固定指纹硬凑。
- **回退语义**：回退占位只作 hitCheck 启动令牌，身份靠 fileId(305f) 锚点（Q4 A/B-2 + b3:152 服务端未过期）；这与 Q4b 纠正的「真实上传链字节决定身份」是**不同链**，本实现不宣称回退占位在真实上传链能产出原图身份。
- **对端解码兼容 =【UNKNOWN】**：占位/hevc 字节替换后对端能否稳定解码、是否格式回退，本两轮无对端解码观测（generalize Q3-b），不预判。
- **写后清晰仍 =【UNKNOWN】**：本探针只读红线决定 C2 不调 j6 / 不发消息 / 不写宿主字段，肉眼翻转在 B5 不可能发生（§2）。**出口 filemd5 翻转 + 用户肉眼 A→B→A 翻转属后续真正 B6 功能实现的判据，不属本实验**，本实验不宣称已清晰。
- **回调时序**：C2 判据须取**第二次（真实回包）**回调；首次可能为空壳，`B6X C2 shape` 日志用于排除读错对象，`hit`/`UploadHitCacheType` 只记录不裁决。
- **交叉验证**：用户级「真机测试前必须交叉验证」——本任务三闸（20/1202/assembleDebug）+ 后续独立 Pro 复核即为交叉验证，不跳过、不直接真机。

`[DONE §6]`

---

真 `[DONE ALL]`

> 交付摘要：产物 `wxgroup/docs/_r86c_b5_impl_note.md`。§1 裁决 = 第三种「动态定位 + Y 安全回退」，禁写死字节/MD5，identity 靠 fileId(305f) 锚点，fail-closed；§2 纠偏 = B5 唯一判据是 C2 第二次回调 `exist=true` + 非 0ef205 身份前缀，肉眼翻转不属本实验。改动仅 `B6CheckExistProbe.kt`（补三字段 + resolveFullpath + C2 shape 只读日志）与 `B6CheckExistProbeTest.kt`（20 例 + 11 变异判红）。三闸：20 / 1202 / assembleDebug 全绿。未碰 adb/装机/设备/git。
