# R86c Q6 重裁 —— 判据未覆盖新形态（fresh-eyes 独立重审）

> 子代理 `r86c-q6-verdict`｜只读｜工作根 `C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`
> 本轮按用户口头临时规则派发（规则内容依要求不写入任何文件）。
> 档位：【日志实证】/【源码实证】/【推断·高|中|低】/【UNKNOWN】/【用户报告·非日志可核】。
> 源码路径前缀 `wxgroup/app/src/main/java/com/lx/wxgroup/`，下文 `ForceForward.kt:2546` 即 `feature/ForceForward.kt:2546`。
> 现场边界：全探针 off 回读确认，本文不触碰；需真机取证处只给方案与判据，由主会话执行。

## 骨架（随问写盘，防父 turn 被打断）

- [DONE Q1] 五条事实逐条回原始日志核验
- [DONE Q2] 判据适配裁决（判据缺陷 vs 新机制；能否按 C1 归档）
- [DONE Q3] 产物 p3-keysrc 冲突处置（真键是否需修正）
- [DONE Q4] go/no-go 与是否补取证
- [DONE Q5] 边界口径
- [DONE 三件套]（结论 / go-no-go / 最小下一步）
- [DONE ALL]

---

## Q1 五条事实逐条回原始日志核验

对主会话提取的 5 条，**逐条回 `_r86c_q6_fire1.txt` 与历史日志核实，不直接采信**。结论：5 条**全部属实**，但第 2 条须补两处关键限定（s0b 与 p3 键是不同来源；"首值"非转发消息专属），第 1 条须补一处语义限定（`md5Head` 是"最后一次被拒候选"，不是"真键声明"）。

### 事实 1 — 诊断行全文与配对：属实

`_r86c_q6_fire1.txt:946` 原文逐字核对：

```
23:08:51.724 main FORWARD P3 origin-by-md5 miss reason=no-exist md5Head=aba57a8d pathTail=/a5/aba57a8d758fcfdcc8d43da62a632e58.jpg exists=false size=- gate=no-exist cand=4
```

7 字段 `reason/md5Head/pathTail/exists/size/gate/cand` 齐全，值与主会话摘录**逐字一致**。【日志实证】

配对正确性回源码 `ForceForward.kt:2554-2557`：`lastReason = reason`、`lastAttemptedFile = f`、`lastMd5 = md5`、`lastPath = path` 在**同一循环迭代内相邻赋值**，四者永远指向同一次被拒尝试；`:2560-2567` 的 miss 日志再由这四者取字段。故本行四元组自洽：

| 字段 | 值 | 自洽核对 |
|---|---|---|
| `md5Head` | `aba57a8d` | = `lastMd5.take(8)`（`:2563`）；`aba57a8d` 正是 pathTail 文件名的前 8 位 |
| `pathTail` | `/a5/aba57a8d758fcfdcc8d43da62a632e58.jpg` | = `lastPath.takeLast(40)`（`:2564`）；拼径 `image2/ab/a5/<md5>.jpg`（`:2546`，桶 = `md5[0:2]/md5[2:4]` = `ab/a5`），尾 40 恰为 `/a5/<32hex>.jpg` |
| `exists` | `false` | = `attempt.exists()`（`:2565`），模块进程视角下该文件不存在 |
| `size` | `-` | `attempt.exists()==true` 才打 `length()`（`:2566`），与 `exists=false` 自洽 |
| `gate` | `no-exist` | = `gateCode = lastReason ?: "no-candidate"`（`:2561`），`P3AdmitGate.admit(f,true)` 既有码 |
| `reason` | `no-exist` | 与 `gate` **同值**（`:2562` 同为 `$gateCode`），是刻意兼容字段，**不是两个独立证据** |
| `cand` | `4` | = `candidates.size`（`:2567`），经 `:2528` 过滤后的候选串总数 |

配对【源码实证】+ 字段值【日志实证】。**限定**：`md5Head` 语义是"最后一次形成文件尝试且被闸门拒绝的候选 md5 的前 8 位"（`:2540` 无 md5 则 `continue`、`:2554-2557` 仅在被拒时赋值），**不是"本消息源图真键"的声明**——详见 Q2/Q3。

### 事实 2 — s0b path 漂移：数值属实，但须补两处限定

数值逐条核对（各日志**首值**）：

| 日志 | 首值 | 锚点 |
|---|---|---|
| on1 | `de291b561b364deb702a8c0972e45c76.jpg` | `_r86c_b5_armfix_on1.txt:594` |
| on2 | `e8b43a5eb6f7929c32b22214fd78eaac.jpg` | `_r86c_b5_armfix_on2.txt:522`（`:524` 重复） |
| stock | `e8b43a5eb6f7929c32b22214fd78eaac.jpg` | `_r86c_b5_armfix_stock.txt:172`（`:174` 重复） |
| 5x | `6ca01e2a89229ebdd12704892d070d36.jpg` | `_r86c_p3_transient_5x.txt:283` |
| 本次 | `a7a7b8f84512e9fe58841bbc3c5fa454.jpg` | `_r86c_q6_fire1.txt:950`、`:1018` |

"每次转发首值都不同"属实。【日志实证】

**限定 A（不同来源，不可混为一谈）**：`SCD s0b ret path` 来自 hook `modelimage/s0.b`，**返回 `ImgInfo2.e`（大图本地路径单字段）**（`StockCdnProbe.kt:231-241`）；而 `p3OriginFromMd5` 的 md5 来自 `ForceForward.kt:2522-2529` 对 **ImgInfo2 全部 String 字段**跑正则 `([0-9a-fA-F]{32})(?:\.(jpg|jpeg|png|webp))?`。二者是**两个不同来源**，s0b 漂移不直接等于 p3 键漂移。【源码实证】产物 p3-keysrc Q1 表格（`:37-40`）本已区分二者，本次冲突的根源是把二者又混用了（详见 Q3）。

**限定 B（"首值"非转发消息专属）**：s0b 在一次会话里对**多条消息**反复触发（会话列表渲染等），首值只是日志顺序产物：on1 首值 `de291b56…` 后紧接 `e8b43a5e…`（`:622`）；5x 首值 `6ca01e2a…` 后紧接 `e8b43a5e…`×2（`:341/:343`）；本次 fire1 两次 s0b 均为 `a7a7b8f8…`（`:950/:1018`）。故"e8b43a5e 稳定"与"每次都变"两种说法各执一端，**都不能由首值单独证成**。【日志实证】

补充：本次 `a7a7b8f8…` 确与本次发送链自洽——`_r86c_q6_fire1.txt:957/:969/:986` 的 `j2R after paths=[e=a7a7b8f84512e9fe58841bbc3c5fa454.jpg …]`、`:961` 的 `field_fullpath=…/image2/a7/a7/a7a7b8f84512e9fe58841bbc3c5fa454.jpg_ field_midimgpath=null` 同值；且该文件在 c2c 中 `fbytes=9554`（`:962`），是**小图**，非 335644B 源图大图。【日志实证】

### 事实 3 — 出口链：属实（肉眼部分须降档）

- `_r86c_q6_fire1.txt:948` `FORWARD P3 copy skip: reason=th-prefix name=th_bb7ef5cbb35141ba26529` ✓【日志实证】
- `:973` `SCD2 x0cb in md5=0ef205c1 md5Len=32 hit=3 exist=true fidLen=194` ✓【日志实证】
- `:984` `field_exist_whencheck=true field_UploadHitCacheType=3` ✓【日志实证】
- 加固同链：`:962` c2c `fbytes=9554 fmd5pre=0ef205c1`；`:983` `field_filemd5=0ef205c165e99eb0f4a1c0084e65f1aa`、`field_thumbfilemd5=f6ab3ac5…`。【日志实证】
- "用户肉眼模糊"= **【用户报告·非日志可核】**，不得写成日志实证；其与日志出口方向一致（都指向 9554B/0ef205c1 小图出口），但"一致"是方向吻合，不是同档证据。

### 事实 4 — `download fired r=true`，r 非判据：属实

- 本次 `_r86c_q6_fire1.txt:945` `download fired localId=885441 msgId=19757536 r=true` ✓【日志实证】
- 5x 五发全 `r=false`：`_r86c_p3_transient_5x.txt:278/:379/:464/:540/:658` ✓
- off1 命中那发 `r=false`：`_r86c_b5_armfix_off1.txt:159` `r=false`，紧接 `:160` `origin-by-md5 path=929c32b22214fd78eaac.jpg size=335644` **命中** ✓
- ⇒ `r=true`（本次 miss）/ `r=false`（off1 hit、5x miss）三态混杂，**r 不构成命中判据**。【日志实证】

### 事实 5 — B6X 全程静默：属实

`grep B6X _r86c_q6_fire1.txt` 全文仅 1 行：`:16` `B6X STATUS enabled=false installed=true classes=true`。无 `SCD B6 in/out` 等业务行。【日志实证】

### Q1 核验结论

5 条主会话事实**全部属实**，无虚构、无出入；但第 1 条 `md5Head` 的语义（最后一次被拒候选，非真键声明）、第 2 条 s0b 与 p3 键的来源区分及"首值"局限，必须在下游裁决中显式带上，否则会重蹈"把观测字段当判据、把两个来源当一个"的覆辙。

`[DONE Q1]`

---

## Q2 判据适配裁决：判据设计缺陷，非新机制；按**修正版 C1′**归档，不按字面 C1、不按 C2

### 2.1 字面比对：三档判据全不匹配

本次观测 `md5Head=aba57a8d exists=false gate=no-exist cand=4`（`_r86c_q6_fire1.txt:946`）：

| 判据（note `docs/_r86c_p3_q6impl_note.md:110-120`） | 字面条件 | 本次 | 是否满足 |
|---|---|---|---|
| **C1** | `exists=false` + `gate=no-exist` + `md5Head=e8b43a5e` + `pathTail` 指 `image2/e8/b4` | md5Head=`aba57a8d`、pathTail=`/a5/aba57a8d…` | **否**（md5/桶均不符） |
| **C2** | `exists=true` + `size≈335644` + `md5Head=e8b43a5e` + gate 非 no-exist | `exists=false`、`size=-` | **否** |
| 特殊情形 | `md5Head/exists/size` 全 `-` 且 `gate=no-candidate` | 三字段有值、gate=no-exist | **否** |

主会话"判据未覆盖新形态"的判断成立。【日志实证】

### 2.2 是"判据设计缺陷"，不是"新机制"

**（一）C1 把 `md5Head=e8b43a5e` 设为归档必要条件，是设计缺陷。** 三条独立理由：

1. **该字面值本身只是【推断·高】，从未被【已证】**：产物 p3-keysrc Q1（`:38`、`:44`）明确写 "`e8b43a…` 即 335644B 文件的内容 md5"是【推断·高】——由 off1 hit 的 `takeLast(24)` 反推，miss 时未打全路径。note 把一条【推断·高】升格为归档的硬门槛，是判据层的过度承诺。
2. **源码模型根本没有"单一真键"**：`p3OriginFromMd5`（`ForceForward.kt:2539-2557`）遍历**所有**候选（本发 `cand=4`），逐个 `nameRe.find` → 拼一条路径 → `admit` → 命中即 `return`，全拒才打一条 miss 行且**只打最后一次被拒的候选**（`:2554-2557`）。所以 `md5Head` 的语义天然是"最后一次被拒者"，要求它恒等于某固定值，与代码结构相悖。【源码实证】
3. **miss 行存在本身已证明"无任何候选命中"**：任一候选 `reason==null` 就在 `:2550-2552` 打 hit 行并 `return`，不会走到 `:2562` 的 miss 行。因此本次 miss 行 = 候选集中**无任何一条形成过闸命中的文件尝试**的直接后果，不需要也不应该靠某个 `md5Head` 字面值来确认这一事实。注：`cand=4` 是 `:2528` 过滤后的候选**串**总数，其中无 32hex 者会在 `:2540` `continue`、不形成文件尝试（note `:120` 已申明 `cand` ≥ 实际尝试数），故不得写成"4 个文件全部被拒"。【源码实证】

**（二）无"新机制"证据。** 反证清单：
- `p3OriginFromMd5` 全程只读（`ForceForward.kt:2511`、`:2523-2529` 仅读字段、`:2548` 仅 `File(...)`、`:2554-2557` 仅写局部变量），无写宿主/DB/键的入口；"模块改写键"【推断·低，倾向否证】（沿用产物 Q4 `:97-98`、Q5 `:111`）。
- B6X 全程静默（Q1 事实 5），无新 hook、无新 gate 行为。
- `SCD s0b` 漂移、`aba57a8d` 出现，均可由**既有代码路径**解释：s0b 读 `ImgInfo2.e`（`StockCdnProbe.kt:231-241`）、md5 正则扫全部 String 字段并取最后被拒者（`ForceForward.kt:2522-2529/:2554-2557`）。无需引入新机制假设。

⇒ 定性：**判据设计缺陷（把推断值硬编码为门槛 + 误读 last-rejected 字段语义），不是模块新机制。**【源码实证 + 日志实证】

### 2.3 能否用 `exists=false` 直接坐实并归档？——分两层答

**第一层（可坐实的部分）：`exists=false` 直接坐实"最后一次尝试的那个 image2 文件本地缺席"。**
这是探针调用 `File.exists()` 的直接返回值（`ForceForward.kt:2565`），是模块进程视角的**直接观测量**，非推断。`gate=no-exist` 进一步表明拒绝原因是"文件不在"，而非"文件在但被闸门拒"（`th-prefix/bad-shape/magic-mismatch/below-floor/thumb-sibling` 均需文件先存在）。⇒ **C2 被直接排除**。【日志实证】

**第二层（不可坐实的部分）："尝试的键是否正确"仍是【UNKNOWN】。**
- `md5Head=aba57a8d` 不等于产物断言的 `e8b43a5e`；本次只发一发、只有一条 `md5Head`，无法判断 4 个候选各自是什么、哪个才是"真正的源图键"。
- diag 只打最后被拒候选（`:2554-2557`），不打候选全集；`cand=4` 只给总数。
- 本次消息 `ImgInfo2.e` = `a7a7b8f8…jpg`（`:957/:961`，9554B 小图），既非 `e8b43a5e` 也非 `aba57a8d`；"源图大图键"这个概念在本发日志里根本没有直接对应物。
⇒ 不得写"源图大图本地缺席坐实"，不得写"键=aba57a8d 已确认"。【UNKNOWN】

**但是：这个 UNKNOWN 不阻塞归档。** 因为 C1 真正要回答的问题是"有没有代码缺陷靶点"，而该问题的答案是**没有**：C2 已直接排除；C3（键错）无写点证据、off1 同链曾命中、p3 只读【推断·高，倾向否证】；C4（下游）被 `:948` `copy skip reason=th-prefix` + 无大图 hit 排除【日志实证】。无论最后被拒候选是不是"真键"，4 个候选全未命中 + 出口 9554B/0ef205c1，都与已定案的"本地源图缺席→缩略图→模糊"分支同构，且 `p3OriginFromMd5` 只读、**没有可修的"键"靶点**。

### 2.4 明确裁决

- **不按字面 C1 归档**（md5Head/pathTail 不符，硬套会构成"为结论放宽判据"）。
- **不按 C2 narrow go**（`exists=false`，无闸门误拒事实）。
- **按修正版 C1′ 归档（no-go）**：
  > **C1′**：miss 行 `exists=false` + `gate=no-exist`（且非 `no-candidate`）⇒「最后一次尝试的 image2 文件本地缺席」【日志实证】；miss 行存在 ⇒ 无任何候选命中【源码实证】；C2/C3/C4 均未触发 ⇒ **无代码缺陷靶点 ⇒ 归档**。
- `md5Head/pathTail/cand` **从"归档判据"降级为"观测量"**：只用于辨认最后被拒的是哪个候选、供未来加固参考，不再作为 go/no-go 门槛。
- 原 note C1 中的 `md5Head=e8b43a5e`+`pathTail 指 image2/e8/b4` 应视为**需废止的判据条款**（判据缺陷），本裁决不修改 note 文件，只在本文记录建议。

`[DONE Q2]`

---

## Q3 产物 p3-keysrc 冲突处置：需**修正键模型**，不推翻根因裁决

### 3.1 先拆开两个来源（Grep 已确认字段口径）

| 来源 | 口径 | 锚点 |
|---|---|---|
| `SCD s0b ret path` | hook `modelimage/s0.b`，返回 `ImgInfo2.e`（**单一**大图本地路径字段） | `StockCdnProbe.kt:231-241`（注释 `:231`、"返回 ImgInfo2.e"，打印 `:241`） |
| `origin-by-md5` 的 `md5Head` | 对 `ImgInfo2` **全部 String 字段**跑正则 `([0-9a-fA-F]{32})(?:\.(jpg\|jpeg\|png\|webp))?` 抠候选，逐个拼 `image2/<2>/<2>/<md5>.jpg`，取**最后被拒者** | `ForceForward.kt:2522-2529`（提取）、`:2546`（拼径）、`:2554-2557`（last 赋值）、`:2563`（head） |

二者【源码实证】为不同来源。**s0b path 每次转发都变，不构成对 p3 键的证伪**——这是本次"冲突"的最大误读点。

### 3.2 逐项裁决

**（一）"真键 = 稳定源图 md5 e8b43a5e"——需修正，不能维持原表述。**

- 产物 p3-keysrc Q1（`:31/:42/:165`）把 P3 查找键概括为"源图 md5 `e8b43a5eb6f7929c32b22214fd78eaac`"，Q5 根因裁决（`:108-111`）据此写"键没坏、分叉在在场性"。
- 源码显示 p3 的模型是**候选集遍历**（本发 `cand=4`），不是单一稳定键（Q2 §2.2）；`e8b43a5e` 只是 off1 那发**恰好命中**的候选，产物自己已把它标为【推断·高】（`:44`）。
- 本发 `md5Head=aba57a8d` 是**首个直接观测到的 p3 尝试键**（此前 on/off/5x 只有 hit 尾或空 miss，从未打出实际尝试键），它 ≠ `e8b43a5e`。
- ⇒ 裁决：**"真键=单一稳定 md5 e8b43a5e"被证伪，须修正为候选集模型**：
  > **修正表述**：`p3OriginFromMd5` 从 `ImgInfo2` 全部 String 字段筛出 32hex 候选（本发 `cand=4`），逐个按 `image2/<2>/<2>/<md5>.jpg` 试文件、首个过闸者命中；off1 命中的候选是 `e8b43a5e`（335644B），本发最后被拒的候选是 `aba57a8d`（`exists=false`）。**不存在"唯一稳定真键"这一对象。**
- 锚点：`_r86c_b5_armfix_off1.txt:160`（off1 hit 尾 `929c32b22214fd78eaac.jpg` = `e8b43a5e` 尾 20+扩展名）、`_r86c_q6_fire1.txt:946`（本发 `aba57a8d`）、`ForceForward.kt:2539-2557`。

**（二）"分叉在源图大图在场性"——不被证伪，维持【推断·高】。**

- 本发候选集无任何过闸命中（miss 行存在即证，见 Q2 §2.2）、无 hit、出口 9554B/0ef205c1，`copy skip th-prefix`（`:948`）与 5x 五发（`_r86c_p3_transient_5x.txt:281/:382/:467/:543/:661`）同形态 ⇒ 与"源图缺席→缩略图→模糊"分支同构。
- 本发消息 `ImgInfo2.e=a7a7b8f8…jpg` 仅 9554B（`:957/:961/:962`），**当前本地确实没有大图文件**——这条新证据反而**加强**"在场性"解读。
- 但"源图大图本地缺席"仍不能写成【已证】：设备侧 `adb ls` 不可行（note `:35`、产物 Q6 `:137`），且 mtime/清理时序未知。维持【推断·高】。

**（三）"p3 实际尝试的键是否等于源图大图文件名"——【UNKNOWN】。**

- 本发只观测到最后一个被拒候选 `aba57a8d`，未见候选全集；`img` 字段原文未打。
- `e8b43a5e`、`a7a7b8f8`、`aba57a8d` 三个 md5 在本发/历史日志并存且互不相等；哪个是"该消息真正的源图文件名"无法从现有日志判定。
- 但此 UNKNOWN 不改变 Q2/Q4 裁决（无代码缺陷靶点）。

### 3.3 冲突定性

所谓"冲突"= **产物的一处表述过度概括 + note 判据把该过度概括硬编码为门槛**，不是新发现的代码 bug，也不是根因裁决被推翻。处置 = **修键模型表述（本文记录，不改产物文件）+ 废止 C1 字面 md5 条件（Q2 §2.4）**；根因"在场性"维持。

`[DONE Q3]`

---

## Q4 go/no-go：归档收尾（no-go）；本轮证据已足，**不强制补取证**，给可选加固方案

### 4.1 裁决：no-go（归档收尾）

| 候选去向 | 裁决 | 依据 |
|---|---|---|
| C2 narrow go（闸门/拼径 bug） | **否** | `exists=false`、`size=-`、`gate=no-exist`（`:946`）；文件根本不在，无"在盘被误拒"事实。C2 直接排除【日志实证】 |
| C3 narrow go（键选错 bug） | **否** | p3 全程只读（`ForceForward.kt:2511/:2523-2529/:2548/:2554-2557`），无写点；off1 同链曾以 `e8b43a5e` 命中 335644B（`_r86c_b5_armfix_off1.txt:160`）；`md5Head` 漂移是"最后被拒候选"语义 + s0b/e 字段另一来源，非改写机制【推断·高，倾向否证】 |
| C4 narrow go（下游 copy/发送 bug） | **否** | `:948` `copy skip reason=th-prefix`，与 5x 五发同形态（`_r86c_p3_transient_5x.txt:281/:382/:467/:543/:661`），根本没走到下游发送【日志实证】 |
| 再补一轮只读取证 | **非必需** | 本发 7 字段诊断 + 出口链已能完成 C1′ 裁决；补证只能加固【推断·高】，不改变 go/no-go（见 §4.2） |
| **归档收尾** | **是** | C2/C3/C4 全否；无代码缺陷靶点；p3 只读、无可修"键"；输出与已定案"缺席→模糊"分支同构 |

### 4.2 翻转条件（仅下列任一出现才 narrow go）

1. 未来某发 `exists=true + size≈335644 + gate` 为非 `no-exist` 码（`bad-shape/magic-mismatch/thumb-sibling/below-floor`）⇒ C2 真 bug。
2. 未来某发 p3 **hit**（打 `origin-by-md5 path=… size=…` 且 `copy` 未 skip）但出口仍 `0ef205c1` / 肉眼模糊 ⇒ C4 重启。
3. 出现可定位的**写点**证据（某 hook 改写了 ImgInfo2 String 字段或拼径）⇒ C3 重启。

本次三条均未出现；不为满足"求结论"而制造 BLOCKER。

### 4.3 可选加固（非必需；**由主会话执行，本文不触碰现场**）

若主会话希望把【推断·高】加固，建议**保持全探针 off 现状不动**，仅在决定补证时按下列只读布法做一轮；本文不开探针、不要求用户发图：

- **现场布法**：复用已存在只读 hook（SCD on / B6X off / r86c off / hevc off），logclear，同图 `srcMsgId=19757536/localId=885441/prefix=filehelper:` 同目标 `wxid_xlrbelf6hv0w22` 模块转发 1 发；不改码、不装新包、不提交。
- **要看的字段（全部来自现有日志，不新增探针）**：
  1. 新格式 miss 行 7 字段，重点 `exists/size/gate/cand`（`_r86c_q6_fire1.txt:946` 格式）；
  2. `FORWARD P3 copy skip` 是否仍 `th-prefix`（C4 复核）；
  3. `SCD s0b ret path` + `j2R after paths=[e=…]`（**只作 ImgInfo2.e 口径，勿当 p3 键**）；
  4. `SCD2 c2c` 的 `fbytes/fmd5pre` + `SCD2 x0cb` 的 `md5/hit` + `f2C2` 的 `field_filemd5/field_UploadHitCacheType`（出口复核）；
  5. 若设备侧只读 `ls` 变为可行，记录两个 `image2` 桶目录内 `<32hex>.jpg` 的 size/mtime（当前 adb 不可行，note `:35`，拿不到就维持【推断·高】）。
- **判据（加固用，不推翻本次裁决）**：
  - 再现 `exists=false + gate=no-exist`（无论 `md5Head` 何值）⇒ C1′ 复核通过 ⇒ 归档加固；
  - 出现 `exists=true + size≈335644 + gate 非 no-exist` ⇒ C2 ⇒ narrow go；
  - `md5Head/exists/size` 全 `-` 且 `gate=no-candidate` ⇒ 独立情形（无文件尝试），回看 `cand` 与 img 字段，不归 C1/C2；
  - p3 hit 但出口模糊 ⇒ C4 重启。
- **不做**：不开用户肉眼 A→B→A（那是 B6 全量修复验收阶段才做）、不改码、不装新包、不要求用户发图。

`[DONE Q4]`

---

## Q5 边界口径：如何陈述才不误导

逐条给"应说"与"禁说"，供主会话对外/对台账落笔时照抄。

1. **本次 C2 自建探针静默**
   - 应说：q6impl 自建的只读诊断补丁（`docs/_r86c_p3_q6impl_note.md`）本次只观测到 `exists=false gate=no-exist`（`:946`），**未出现 C2 所需观测量**（`exists=true + size≈335644 + gate 非 no-exist`），故 **C2 未被触发、不构成命中**。
   - 禁说："C2 探针报了 miss 所以 C2 命中/闸门有问题"——把"未触发"说成"命中"是反向误读。C2 是 narrow go 的充分条件，本次未成立。

2. **肉眼模糊与真实链 0ef205c1 一致**
   - 应说："用户肉眼模糊（**【用户报告·非日志可核】**）与日志侧出口方向一致：`:962` c2c `fbytes=9554 fmd5pre=0ef205c1`、`:973` x0cb `md5=0ef205c1 hit=3`、`:983` `field_filemd5=0ef205c165e99eb0f4a1c0084e65f1aa`、`:984` `UploadHitCacheType=3`，均指向缩略图/小图出口。"
   - 禁说："日志实证用户看到模糊"——肉眼不可日志核；"一致"仅指方向吻合，不是同档证据合并。

3. **诊断日志只加观测不改行为**
   - 应说：补丁仅在 `p3OriginFromMd5` 内新增局部诊断变量（`:2535-2538`）、同步配对赋值（`:2554-2557`）与富字段 miss 日志（`:2559-2567`）；`P3AdmitGate.admit`、`nameRe`、拼径 `:2546`、`admit(f,true)` `:2549`、hit 返回 `:2550-2553` 及所有业务写入逐字未动（note Q2 `:56-64`、台账 `:6011`），**肉眼不会因补丁变化**。【源码实证】
   - 禁说："诊断补丁改变了转发结果/使图更模糊"——因果倒置。

4. **C2 命中 ≠ 清晰**（硬结论，不可越过）
   - 应说：即便未来 C2 命中（确属闸门 bug），也只说明找到缺陷靶点；**清晰仍须 B6 全量功能 + 用户肉眼 A→B→A 终审**才能定案。本轮不做、不授权、不要求用户发图。
   - 禁说："C2 命中 = 问题已解决/已清晰"。

5. **talker 整体【真机实证·中】**
   - 应说：talker 相关结论维持整体档位【真机实证·中】，本次 Q6 只读取证不改变其档位，也不升档。
   - 禁说："本轮把 talker 升为【已证】/通过"。

6. **B5 机制只是"信号·推断·高"，非通过**
   - 应说：B5 机制维持【信号·推断·高】，不是验证通过；本次未产生可将其升档的新证据。
   - 禁说："B5 已通过/已确认"。

7. **本次新增的三条专属边界（防止新误导）**
   - `md5Head=aba57a8d` 只写"最后一次被拒候选的前 8 位"【日志实证】；**禁写**"源图键已确认/键坏了"，"尝试的键是否正确"=【UNKNOWN】。
   - `SCD s0b ret path` 漂移属 `ImgInfo2.e` 口径（`StockCdnProbe.kt:231-241`），与 p3 键（全 String 字段正则，`ForceForward.kt:2522-2529`）是两来源；**禁写**"s0b 漂移证明 p3 键不稳定"。
   - 根因"在场性"维持【推断·高】，**禁写**"本地源图缺席已坐实/已证"（设备侧 `ls` 不可行、仅模块视角 `exists=false` 覆盖最后被拒候选）。

`[DONE Q5]`

---

## 三件套

**1) 结论**

- 主会话 5 条事实**逐条核实全部属实**（`_r86c_q6_fire1.txt:16/:945/:946/:948/:950/:973/:984/:1018` 等），仅补两处限定：`md5Head`=最后被拒候选而非真键声明；s0b（`ImgInfo2.e`，`StockCdnProbe.kt:231-241`）与 p3 键（全 String 字段正则，`ForceForward.kt:2522-2529`）是两个来源，其漂移不等于 p3 键漂移。
- "判据未覆盖新形态"= **判据设计缺陷，非新机制**：note C1 把【推断·高】的 `e8b43a5e` 硬编码为归档门槛，且误读 `md5Head` 语义；源码是候选集遍历（本发 `cand=4`），不存在"单一稳定真键"（`ForceForward.kt:2539-2557`）。
- `exists=false` 直接坐实"最后一次尝试的 image2 文件本地缺席"（`ForceForward.kt:2565`，【日志实证】），并直接排除 C2；但"尝试的键是否正确"仍【UNKNOWN】。C1 真正问的"有无代码缺陷靶点"答案=无（C2/C3/C4 全否）。
- 产物 p3-keysrc "真键=稳定源图 md5 e8b43a5e" **需修正为候选集模型**（不改产物文件，本文记录建议）；根因"分叉在本地大图在场性"**不被证伪，维持【推断·高】**。

**2) go/no-go**

- **no-go，归档收尾**：不为"P3 查找键 / bigimgPath"继续投入修复。C2（闸门误拒）被 `exists=false` 排除；C3（键错）无写点、off1 同链曾命中、p3 只读；C4（下游）被 `copy skip th-prefix` 排除。无代码缺陷靶点，无可修的"键"。
- 翻转条件仅三条（见 Q4 §4.2）：`exists=true+size≈335644+gate 非 no-exist` / p3 hit 但出口仍模糊 / 定位到改写写点。本次均未出现。
- 红线不越过：C2 命中≠清晰；肉眼【用户报告·非日志可核】；talker 整体【真机实证·中】；B5 机制只信号·推断·高；固定字节/MD5 不泛化；微X 全复刻 no-go；清晰须 B6 全量 + 用户肉眼 A→B→A 终审。

**3) 最小下一步**

- 主会话按台账 `:6027` 已回读确认现场全探针 off，本文不触碰、不改码、不装包、不提交。
- 建议主会话将 note C1 的 `md5Head=e8b43a5e + pathTail 指 e8/b4` 条款标记为**废止**，改用本文 **C1′**（`exists=false + gate=no-exist` ⇒ 最后尝试文件缺席【日志实证】 + miss 行存在即无候选命中【源码实证】 + C2/C3/C4 否 ⇒ 归档）；`md5Head/pathTail/cand` 降级为观测量。
- 建议主会话在台账补记：p3-keysrc "真键"表述修正为候选集模型；"源图键是否正确"登记为【UNKNOWN】且不阻塞归档。
- 可选加固（非必需、由主会话执行）：按 Q4 §4.3 只读布法补一轮，看 `exists/size/gate/cand` + `copy skip` + `s0b/j2R e 字段` + `c2c/x0cb/f2C2` 出口，用 C1′/C2/no-candidate/C4 判据复核；不开用户肉眼 A→B→A。

`[DONE 三件套]`

---

`[DONE ALL]`
