# R86 B2-a Pro 根因判定与 Hook 链路落点

> 输入：`docs/_r86_b2a_three_state_raw.md`（核心）、`docs/_r86_b2a_state2_raw.txt`（461 行）、`docs/_r86_b2_forensics_plan.md`、`docs/contributions/` 相关结论、`StockCdnProbe.kt` E1~E3 取值代码。
> 标记体系：【真机坐实】/【源码确定】/【推断·高/中/低】。dex 有字符串 ≠ 业务调用它。
> 时序结论：只认 invoke 地址递增 / 真机日志时间戳，「A 推 B」一律标假设。
> 铁律：判据对着用户可见事实（对端清晰/模糊），互斥判据不写成并列，零命中如实记零命中。

---

## Q1　hitCache 取值 1（态①清晰）vs 3（态②模糊）的准确语义

### 1.1 先钉死字段来源（避免读错对象）

- 【源码确定】E1 探针 `SCD-x0cb` 读的是 `ok0/x0.onC2CUploadCompleted(String, C2CUploadResult)` 的 `param.args[1]`（回包对象），字段 `hitCache` 来自 `com.tencent.mars.cdn.CdnLogic$C2CUploadResult`（`StockCdnProbe.kt:394-410`，签名 `sig(... "com.tencent.mars.cdn.CdnLogic$C2CUploadResult")`，`getObjectField(res,"hitCache")`）。
- 【源码确定】dex 实证（`_cache/dex/classes13.dex`，`_r12_dexclass.py --class 'Lcom/tencent/mars/cdn/CdnLogic$C2CUploadResult;'`）：`C2CUploadResult` 有 39 个实例字段，其中 `hitCache:I`、`existOnSvr:Z`、`filemd5:Ljava/lang/String;`、`fileid:Ljava/lang/String;`。即 `hit` 是**服务端回包的 int**，不是客户端计数器。
- 【源码确定】同一回包对象里 `hitCache` 与 `tj/f.field_UploadHitCacheType` 是**同一个数**：反汇编 `ok0/x0.onC2CUploadCompleted@0x3aa9bc`（classes13）可见 `iget v8.hitCache` → 仅 `if-ltz` 负值守卫 → `iput v0.field_UploadHitCacheType`（`0x001e/0x001f/0x0020`）。客户端**没有** `==1`/`==3` 的语义分支；数值含义由服务端/native 决定。
- 【源码确定】`CdnLogic`（classes13）确有 hit 常量名：`NO_HITCACHE`、`HIT_FILEID`、`HIT_NEWMD5`、`HIT_UPLOADCHECKMD5`、`HIT_UPLOADWITHMD5`、`HIT_VIDEODROPRATE`（均 `public static final I`，flag `0x19`）。但**本工作区 dex 中查不到这些常量的数值表**：全 dex 扫 `sput` 到这些字段 = **零命中**（被 D8/R8 内联为立即数，无运行时赋值表）。⇒ **无法给出"1=X、3=Y"的 dex 铁证**。

### 1.2 真机直接观测到的相关关系（唯一可当证据的相关性）

【真机坐实】把本轮三态 + 历史 R79/R81 的「上传 task fileId / hitCache / 对端肉眼」三列并列：

| 样本 | 上传 task `fileId` | `hitCache` | 对端肉眼 | 出处 |
|---|---|---|---|---|
| R86 态① stock 原生转发 | `fidLen=194`（非空） | **1** | 清晰 | 本轮 `three_state_raw.md:22-23` |
| R86 态② 模块转发 | `fidLen=0`（空） | **3** | 模糊 | 本轮 `three_state_raw.md:40-41` |
| R79 我方（fileId 空） | 空 | **3** | 模糊 | `_r81_r80a_falsified_verdict.md:47`（转述 R79 日志） |
| R81 我方（注入 fileId） | 非空（`0f836cfd` body） | **1** | **仍模糊** | 同上 `:42-47` |

### 1.3 判定

1. 【真机坐实】本轮两态的 `hitCache` 数值事实就是：**清晰态=1、模糊态=3**；`f2C2.field_UploadHitCacheType` 同值（态①=1、态②=3，`three_state_raw.md:24/42`），因为它是同一回包字段的拷贝。
2. 【推断·高】**`hitCache` 的观测相关性是「上传 task 的 fileId 是否非空」，不是「内容是否原图/对端是否清晰」**：上表四行里 `fileId=非空 ⇔ hit=1`、`fileId=空 ⇔ hit=3` **零例外**；而清晰度并不随 hit 单调——`hit=1` 同时出现在「清晰（R86 ①）」和「仍模糊（R81 注入后）」两种结果里。⇒ **`hitCache` 不是清晰度的决定量，它至多是「走没走 fileId 命中路径」的影子指标**。
3. 【推断·中/低，必须标假设】若按 WeChat 常见枚举顺序猜测数值含义（`0=NO_HITCACHE`、`1=HIT_FILEID`、`3=HIT_UPLOADCHECKMD5`），则 1 可读作"按 fileId 命中"、3 读作"上传中按 md5 校验命中"。**但这只是基于常量名的推测**：本地 dex 无数值表（1.1 末条铁证为"零命中"），本轮也没有做「只改 hitCache 数值、其余字段锁死」的反向实验，**禁止**把它升级为事实。
4. **明确否决错误写法**：不能写"hitCache=1 = 命中原图秒传"。反证就是 R81 那一行——fileId 注入后 `hitCache=1`、`exist_whencheck=true`，但对端**仍模糊**（【真机坐实】）；若 1 真等于"命中原图秒传"，R81 不可能模糊。
5. 【推断·高】R82 文档（`_r82_b2_netscene_forensics.md:63-64/126-127`）写的"`UploadHitCacheType=3`→命中原图清晰、`=1`→命中缩略图模糊"与本轮真机**方向相反**（本轮清晰=1、模糊=3）。R82 那段是【推断】且基于当时**未直接 hook 回包**的 R79/R81 转述；本轮 E1 是首次直接读到回包 `hitCache`，**以本轮为准，R82 的方向标注应视为被推翻**（至少对"1/3 各自指向原图还是缩略图"这一语义判死，不能继续沿用）。

> Q1 一句话：`hitCache` 是服务端回包的 int（【源码确定】），本地 dex 无数值语义表（【源码确定·零命中】）；真机可证的只有"清晰=1、模糊=3"（【真机坐实】）且它实为 fileId 是否非空的影子、非清晰度决定量（【推断·高】）；"1=命中原图秒传"被 R81 反例否决，**禁止书写**。

[DONE Q1]

---

## Q2　态② c2c 上传任务 field_fileId=0 与态①=194 的差异：根因还是伴生现象

### 2.1 先把"入参差异"摆正：它是真的，但不是孤立的

- 【真机坐实】态① c2c（上传任务构造点 `ok0.m1.e` before）`fidLen=194 fidPre=305f0201`；态② `fidLen=0 fidPre=空`（`three_state_raw.md:61-62 / :40`）。同一差异在 `SCD m1e task fileId=` 行复现：态② `fileId= md5= onlycheckexist=false`（`state2_raw.txt:372-373`）。此差异客观存在。
- 【真机坐实】但态②的**回包** `SCD2 x0cb ... fidLen=194 fidPre=305f0201`（`state2_raw.txt:388` / `three_state_raw.md:41`）——即**上传任务入参 fileId=0，并不妨碍服务端回包 fileId=194**。换言之 `194` 这个串本身在两态回包里都有；差别只在"构造 task 时客户端有没有提前把 fileId 填进去"。
- 【源码确定】`ok0/x0.onC2CUploadCompleted@0x3aa9bc` 的 `field_fileId` 写回值 `iput` 来自回包 `fileid`（Q1 反汇编 `0x0022-0x0024`），而回包 `fileid` 由服务端/native 给，**不是** task 入参的回显。⇒ `task.fileId` 与 `回包.fileId` 是两个不同来源的数。

### 2.2 决定性反证：把 fileId 从 0 改成非空，对端仍模糊（R80(a) 已证伪）

- 【真机坐实】R80(a) 做过的正是"只把 fileId 头补成非空"：`ForwardCdnFieldHook` 在 `m1.e before` 写 `task.field_fileId/field_aesKey`。结果 `fileId` 由空转非空、`305f...` 与 stock 同前缀、`f2C2 field_exist_whencheck=true`、`UploadHitCacheType=1`——**机制全命中但对端仍模糊**（`_r86_b2_forensics_plan.md:29`，引 `_r81_r80a_falsified_verdict.md:42-47`）。
- 【真机坐实】同一反证里 `field_filemd5=0ef205c165e99eb0f4a1c0084e65f1aa` 在"空 fileId（R79）"与"注入 fileId（R81）"两次**逐字相同**（R81 `:43`）。⇒ 改 fileId 头**没有换掉被上传的内容身份**。
- 【推断·高】这条是判"根因 vs 伴生"的硬标尺：**若 fileId 是根因，把它补齐就应清晰**；真机补齐后仍模糊 ⇒ **fileId 不是决定清晰度的根因**。

### 2.3 与方案 §2.2「决定量是 fullpath 字节 → hitCheck 命中对象」对齐

- 【真机坐实】态②这一侧，模块转发链自己打出了一条完整的"为什么拿到的是缩略图字节"的证据：`FORWARD P3 origin-by-md5 miss reason=no-exist`（`state2_raw.txt:357`）、`bigimg ready=false`（`:358`）、`copy skip: reason=th-prefix`（`:359`）、`resolveImage ... pick=th_216b1f7ccd...`（`:342`）。即**模块没能从本地取到原图字节，转发的是 `th_` 缩略图**——这与 §2.2 "决定量 = fullpath 指向的内容是 36B 占位还是缩略图"方向一致。
- 【真机坐实】回包内容身份两态不同：态① x0cb `md5=ca47f83e`，态② `md5=0ef205c1`（`three_state_raw.md:23/41`）；`f2C2.field_filemd5` 态② = `0ef205c1`（`state2_raw.txt:396`）。回包 md5 是"服务端实际处理的字节"的指纹（【源码确定】Q1 同字段），两态指向不同内容。
- 【推断·高】综合 2.2 反证 + 2.3 内容身份差异，**根因方向 = 上传的内容字节（fullpath 指向的是原图身份还是缩略图）决定对端清晰度；fileId=0/194 只是"走了哪条转发入口"的影子**：
  - 态①走 stock B6 链 → task 能填出原图 fileId（194）→ 且 fullpath 指向能触发原图身份的内容 → 回包 md5=ca47f83e → 清晰；
  - 态②走模块 RESEND_FILE 链（`FORWARD plan strategy=RESEND_FILE reason=原生转发不可用`, `state2_raw.txt:355`）→ task 无 fileId（0）→ 且 fullpath 指向缩略图 → 回包 md5=0ef205c1 → 模糊。
  - fileId 差异与清晰度差异**同源（都来自入口不同）但非因果**——补齐 fileId（R80(a)）不改入口、不改内容，故无效。
- 【推断·中，必须标假设】"A 推 B"时序：本轮只直接观测到 `m1e → c2c → x0cb → f2C2` 的日志时间戳递增（`state2_raw.txt:372→375→388→395`，地址/时间均递增），**没有直接 hook** `ok0/x0.u` 内部的 `setFilePath → mars startC2CUpload → hitCheck`。因此"fullpath 字节 **导致** hitCheck 命中原图/缩略图"仍是**假设**，本轮只坐实了"两态 fullpath 尾缀不同（be6/f2f）+ 回包 md5 不同（ca47/0ef2）+ 对端一清一糊"，因果链未闭合。

### 2.4 判定（互斥，不并列）

- **根因（主判定）**：【推断·高】**上传内容的身份（原图 md5=ca47... vs 缩略图 md5=0ef205...），即 §2.2 的"fullpath 字节 → hitCheck 命中对象"**。本轮真机坐实了它的"因侧观测差异"（fullpath 尾缀、回包 md5）和"果侧事实"（一清一糊），但 hitCheck 内部机制仍是假设。
- **伴生现象（明确降格）**：【真机坐实 + 推断·高】`task.field_fileId=0 vs 194`、`hitCache=3 vs 1`、`field_exist_whencheck` 等，都是"入口是 RESEND_FILE 还是 B6"的伴生影子；**R80(a) 已用真机反证它们不是决定量**。
- **不要写成并列**：不能说"fileId 和 fullpath 共同决定清晰度"——真机已证只改 fileId 无效，二者地位不等。

> Q2 一句话：`field_fileId=0 vs 194` 是【真机坐实】的入参差异，但被 R80(a)"补齐仍模糊"和"回包 fileId 两态同为 194"双重证伪为**伴生现象**；决定量【推断·高】是回包 md5 锁定的**内容身份**（原图 ca47... vs 缩略图 0ef205...），hitCheck 内部因果【推断·中·假设】未闭合。

[DONE Q2]

---

## Q3　两态 md5 与 pathTail 不同是否影响 A/B 唯一自变量成立

### 3.1 事实：差异确实存在，且用户口述"同一张坏图"未获客观锁定

- 【真机坐实】态① c2c `pathTail=be6.jpg_hevc`、x0cb `md5=ca47f83e`、`s0b ret path=...96be6.jpg`（`three_state_raw.md:22-23/27`）；态② c2c `pathTail=f2f.jpg_hevc`、x0cb `md5=0ef205c1`、`s0b ret path=aa01148...f2f.jpg`（`:40-41`、`state2_raw.txt:363`）。**两态落的是不同本地文件名、不同回包 md5、不同 s0b 路径**。
- 【真机坐实·且是缺口】`three_state_raw.md:77-78` 自己已标注："不能预设是同一字节源……不得凭用户口述当作已证事实"；且态①日志在切态②前被 `logclear` 覆盖，态①字段是**终端逐行转录（非 pull 文件）**（`:17`），证据等级低于态② raw。
- 【真机坐实·缺口】方案 §5.1 进入闸门第 4 条要求"样本身份用 `INP j2init ori=.../21/6b/216b1f7c...jpg` + `m.a=883190` + 完整 md5 三重锁定，禁止 tail24"（`_r86_b2_forensics_plan.md:234`）。**本轮三态记录里没有任何 `j2init ori=` / `m.a=` 行**——即方案要求的样本身份闸门**本轮未落地**。态②虽有 `FORWARD resolveImage ... md5=216b1f7ccd ... pick=th_216b...`（`state2_raw.txt:342`）可作模块侧身份线索，但态①无对应行可比。

### 3.2 判定：影响成立，且当前无法排除混淆

【推断·高】md5/pathTail 差异对 A/B 的影响，取决于"两态是否真的同一字节源"，而这**当前无法用真机数据判定**：

- **若同一源图**：回包 md5 差异（ca47 vs 0ef2）是**转发路径导致的机制变量/因变量**（stock 让服务端认原图身份、模块让服务端认缩略图身份），此时它是 Q2 根因链的**证据**，不破坏 A/B——唯一自变量仍是"转发入口（B6 vs RESEND_FILE）"。
- **若不同源图**：md5/pathTail 差异就是**混淆变量**，A/B 唯一自变量不成立，不能把清晰度差归因于转发路径。

【真机坐实】现有数据**不足以在这两种解释间二选一**：没有对源图本地文件做 `md5sum`/`ls -l`，没有两态共享的源 xml md5 锁行（态① Even 只有回包 md5 和被覆盖前的转录），没有 `j2init ori=` 三重锁。⇒ **A/B 唯一自变量目前是"未验证"而非"已成立"**；Q2 的根因判定因此带一条前置条件（见 Q4 验证要求）。

### 3.3 需补采什么（明确的补证动作，不是泛泛而谈）

1. **【补·样本身份】两态各采一条同源锁行**：态①补 `INP j2init ori=` + `m.a=` + E3 `g9c md5Pre`（方案 §5.1-4 / U2，`_r86_b2_forensics_plan.md:322`）；态②已有 `FORWARD resolveImage md5=216b1f7ccd`，对齐到同一 `216b1f7ccd59b1268349fd0eb3c46801` 完整 md5（禁止用 `takeLast(24)` 的 tail24 分桶，R85 §1.1 陷阱）。
2. **【补·字节事实】源图本地文件只读核**：两态转发前后各 `ls -l` + `md5sum` 源图文件与 `th_` 缩略图（216b 桶 `.../21/6b/`、7ccd 桶），判据"同一完整 md5 + 同一字节数"⇒ 同源坐实；否则判混淆。
3. **【补·最强·反向对照】在同一态内做单自变量翻转**：固定同一张图、同一模块转发路径，只翻 `chat.b2_forensics`（或 B2 复刻开关）on/off，冷启后各发一次（`_r86_b2_forensics_plan.md:268`）。**若字节源与所有入参锁死后，清晰度随开关翻转，则 A/B 唯一自变量由实验本身坐实**，不再依赖"用户说同一张图"。这是把 Q2/Q3 从"推断·高"升级到"真机坐实"的唯一干净路径。
4. **【补·态①证据等级】**态①改为 pull 原始日志文件归档（不再终端转录），避免 `logclear` 覆盖导致的一手证据缺失。

> Q3 一句话：md5/pathTail 差异**确实影响**A/B 唯一自变量成立，当前因"源图同一性未客观锁定 + 方案要求的 ori/md5 三重锁未落地"而**无法判定同源**（【推断·高】）；须补两态 `j2init ori=`/完整 md5 锁行 + 本地文件 `md5sum`/`ls`，并以**同态开关翻转的反向对照**做最强坐实。

[DONE Q3]

---

## Q4　B2 最小改法落点（具体类/方法/字段）与反向对照验证

### 4.1 先诚实记零命中（铁律 3）

- 【真机坐实·零命中】E4（`SCD-CDNcb` / `qf4.j0.C2` checkExist 回调）**本轮两态均零命中**：`state2_raw.txt` 中 `qf4/j0.C2` 仅出现 1 次即安装行 `:774 SCD hook SCD-CDNcb OK`（grep 计数=1），**无任何 `j0C2 in/out`、`support second !!` 日志**；`three_state_raw.md` 亦无。⇒ 方案 go 门槛 2 要求的 `SCD2 j0C2 exist_whencheck=true`（`_r86_b2_forensics_plan.md:299`）**本轮无数据**，"checkExist 秒传命中建新记录"这段既未在态①也未在态②被直接观测。
- 【真机坐实】E1（`x0cb`）、E2（`c2c`）、E3（`g9c`）三 hook 均有真机命中（Q1/Q2 已引）；其中 E3 `g9c hasBig=true bigLen=194` 在两态均出现（`three_state_raw.md:28`、`state2_raw.txt:441/446`）⇒ **源坏图 xml 确有原图 `cdnbigimgurl`，B2 命题前提存在**（go 门槛 3 满足）。

### 4.2 最小改法落点（主推荐）

**落点＝`ok0/m1.e(tj/k)Z` 的 before —— 即 E2 / `SCD-c2c` 同一 hook 点（`StockCdnProbe.kt:423-468`），但写入目标从"头"换成"身"。**

| 项 | 具体值 |
|---|---|
| Java 落点类/方法 | `com.lx.wxgroup` 侧新增一个**写** hook 挂 `ok0.m1.e(tj.k)` before（复用 E2 已验证能挂上的 `sig("e", ["tj.k"], "boolean")`），**替代**已证伪的 `ForwardCdnFieldHook.install`（`HookEntry.kt:137-142` 现为摘除态） |
| 写入字段（关键） | ① `tj/k.field_fullpath` ← 改为 stock 式"36B 原图引用占位"（内容模板取 R79 stock 的 `882d...jpg` 36B，**须先按 4.4 补证坐实**）；② `tj/k.field_fileId` / `field_aesKey` ← 来自 E3 `g9.c` 从源消息 xml 解析出的原图 `cdnbigimgurl`/`aeskey`（`g9.c hasBig=true bigLen=194` 已真机命中）；③ `enableHitCheck` 保持 true（两态本就 true） |
| 读取/配合点 | E3 `com.tencent.mm.sdk.platformtools.g9.c(String,String,String)Map` before（`StockCdnProbe.kt:473-503`）只读原图 CDN 身份；E1 `ok0/x0.onC2CUploadCompleted`（`:381-416`）验回包 md5；E4 `qf4/j0.C2`（checkExist）本轮零命中，须在改法中**新增前置 checkExist task（`mq/k.B8`）并确认它会命中**，否则不完整 |
| 不在落点 | 不改 `hitCache`/`exist_whencheck`（它们是回包影子，Q1/Q2 已证非决定量）；不重复 R80(a) 的"只补 fileId/aesKey 头" |

### 4.3 为什么这个落点比 R80(a) 改 fileId/aesKey 更可能奏效

1. 【真机坐实·反证】R80(a) 在**同一个 `ok0.m1.e before` 点**只写 `field_fileId/field_aesKey`，机制字段全命中（`fileId` 非空、`305f` 同前缀、`exist_whencheck=true`、`UploadHitCacheType=1`），但对端**仍模糊**，且 `field_filemd5=0ef205c1...` 跨空/非空 fileId 两次**逐字相同**（Q2 引 R81 `:42-47`）。⇒ **改"头"无效是已证事实，不是猜测**。
2. 【源码确定】R82 dex 实证：`ok0/x0.u@0x3a98cc` 的 `C2CUploadRequest` 构造**不调 setFilemd5**，请求 md5 由 mars native 从 `setFilePath(field_fullpath)` 的**文件字节重算**（`_r86_b2_forensics_plan.md:30/46`，本轮未推翻）。⇒ 服务端回包 md5（`field_filemd5`）唯一由 `field_fullpath` 指向的字节决定。`field_fullpath` 是**客户端唯一能改变"服务端认原图还是缩略图"的入参**。
3. 【真机坐实】本轮回包 md5 两态不同（态① `ca47f83e` / 态② `0ef205c1`，Q2 引），且态② `FORWARD P3 origin-by-md5 miss` / `copy skip th-prefix` / `pick=th_216b...`（`state2_raw.txt:342/357-359`）直接说明**模块把缩略图字节送进了上传**。改 `field_fullpath` 正是掐住这条因。
4. 【推断·高】因此"改身（fullpath）"直接作用于**已观测差异的上游**，而"改头（fileId/aesKey）"作用于**已被真机证明无关的影子变量**——前者命中因、后者命中果的伴生。
5. **但必须标限制**：【推断·中·假设】"36B 占位 → mars hitCheck 命中原图 → 只引用不传字节"的完整因果**本轮未被直接观测**（E4 零命中、无 `setFilePath`/native hitCheck hook、无 36B 文件本轮 `md5sum`）；stock 36B 占位清晰是 R79 历史文件事实（一手 log 当前不在工作区，方案 U7）。⇒ 该落点是"当前数据下最可能奏效"的**最小尝试**，不是已证必成。

### 4.4 该改法需要的真机反向对照验证（互斥判据，对着用户可见事实）

**前置（Q3）**：同一张图、同源锁行（`j2init ori=` + 完整 md5）+ 本地 `md5sum`/`ls -l` 先过，否则样本作废。

**开关翻转（唯一自变量）**：固定图与路径，只翻新 hook 的开关 on/off（默认关，仿 `chat.b2_forensics` / R36 `chat.forward_native` 的"默认关 + 回读"纪律），冷启各发一次：

| 观测 | off（须回退态②基线） | on（目标态③） | 证据档 |
|---|---|---|---|
| 对端肉眼（**终极判据**） | 模糊、长按字节 ≈15KB 级 | **清晰、长按字节 ≫15KB、≈源图分辨率** | 【真机坐实·肉眼】 |
| E1 `x0cb md5` | `0ef205c1...` | **≠`0ef205...`（期望回到 `ca47f83e` 原图身份）** | 【真机坐实】 |
| E1 `x0cb exist` | true | true（不单独作判据） | 【真机坐实·影子】 |
| E2 `c2c pathTail/pathLen` | `f2f.jpg_hevc` / 124 | 变为占位特征（须先坐实 stock 占位尾缀） | 【真机坐实】 |
| E2 `c2c fidLen` | 0 | 非空（≈194） | 【真机坐实·影子】 |
| E4 `j0C2 exist_whencheck` | 零命中 | **必须由零命中变为 `true`**（否则改法不完整，checkExist 未触发） | 【真机坐实·当前缺口】 |
| `hitCache` 数值 | 3 | 任意（**不据此判成败**，Q1 已证非决定量） | 仅记录 |

**互斥判死（不写成并列）**：
- on 后 E1 md5 **仍 = `0ef205c1`** 却清晰 ⇒ 决定量不在可观测字段，B2 判死（方案 §6.2 no-go）。
- on 后 E1 md5 **≠ `0ef205`（身份换成功）但对端仍模糊** ⇒ 方向对但"新记录 xml 缺 `cdnbigimgurl`"，查 `b1.j6` 新记录 + E3/f2.C2 重建串是否真带 URL，仍失败则停。
- on 后 E4 仍零命中 ⇒ checkExist 前置未生效，落点不完整，先补 `mq/k.B8` 再判。
- off 不回退模糊 ⇒ 开关/样本污染（`CDN_FIELD injected` 复现则作废），重来。

> Q4 一句话：最小落点＝**`ok0.m1.e(tj/k)` before 改 `field_fullpath`（+ 用 E3 原图 CDN 身份填 fileId/aesKey、补 E4 checkExist 前置）**，【推断·高】比 R80(a) 同点只改头更可能奏效，因为 fullpath 是【源码确定】native 重算 md5 的唯一字节输入、且本轮回包 md5 差异已真机坐实；但 36B 占位因果与 checkExist 命中仍是【推断·中·假设】（E4 本轮零命中），须按 4.4 的同源锁定 + 开关翻转 + "md5 先换、肉眼后判"互斥判据坐实。

[DONE Q4]

---

## 总判定

**一句话总判定：B2 命题（秒传引用能让本地仅有缩略图时仍清晰）在本轮真机数据下 —— 仍待补证。** 本轮坐实的是"源 xml 有原图 URL（E3 hasBig=true）"+"清晰/模糊两态的回包内容身份不同（md5 ca47f83e vs 0ef205c1）"+"fileId/hitCache 非决定量（R80(a) 反证）"，**但没有出现态③、E4 checkExist 全程零命中、两态源图同一性未客观锁定** ⇒ "秒传引用本身→仅凭缩略图仍清晰"这条决定链**未被直接观测闭合**，既不坐实也未证伪；补证项 = ①两态同源锁行+本地 `md5sum`（Q3）；②让 E4 `qf4/j0.C2` 由零命中变为 `exist_whencheck=true`；③落 `ok0.m1.e` 改 `field_fullpath` 的最小改法并跑开关翻转，验 on 态 E1 `md5≠0ef205` 且对端肉眼清晰、off 回退模糊（Q4 §4.4 互斥判据）。

[DONE ALL]
